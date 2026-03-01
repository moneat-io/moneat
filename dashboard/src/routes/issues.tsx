// Moneat - observability platform
// Copyright (C) 2026 Moneat
//
// This program is free software: you can redistribute it and/or modify
// it under the terms of the GNU Affero General Public License as published by
// the Free Software Foundation, either version 3 of the License, or
// (at your option) any later version.
//
// This program is distributed in the hope that it will be useful,
// but WITHOUT ANY WARRANTY; without even the implied warranty of
// MERCHANTABILITY or FITNESS FOR A PARTICULAR PURPOSE. See the
// GNU Affero General Public License for more details.
//
// You should have received a copy of the GNU Affero General Public License
// along with this program. If not, see <https://www.gnu.org/licenses/>.

import {createFileRoute, Link, Outlet, useMatches} from '@tanstack/react-router'

import {useMutation, useQuery, useQueryClient} from '@tanstack/react-query'
import {api} from '@/lib/api'
import type {ApmErrorGroup} from '@/lib/api'
import {trackEvent} from '@/lib/analytics'
import {useProject} from '@/contexts/project-context'
import {formatRelativeTime, cn} from '@/lib/utils'
import {Badge} from '@/components/ui/badge'
import {Button} from '@/components/ui/button'
import {Input} from '@/components/ui/input'
import {Select, SelectContent, SelectItem, SelectTrigger, SelectValue,} from '@/components/ui/select'
import {Card} from '@/components/ui/card'
import {Checkbox} from '@/components/ui/checkbox'
import {
  AlertCircle,
  AlertTriangle,
  CheckCircle2,
  Clock,
  Cpu,
  FolderKanban,
  Plus,
  Search,
  Server,
  Settings,
} from 'lucide-react'
import {useEffect, useMemo, useState} from 'react'
import {useToast} from '@/hooks/use-toast'
import {getNow} from '@/lib/demo'

// Helper function to get level color for badges
function getLevelColor(level: string): string {
  switch (level.toLowerCase()) {
    case 'fatal':
      return 'bg-red-900 text-red-100 hover:bg-red-900'
    case 'error':
      return 'bg-red-600 text-white hover:bg-red-600'
    case 'warning':
      return 'bg-orange-500 text-white hover:bg-orange-500'
    case 'info':
      return 'bg-blue-500 text-white hover:bg-blue-500'
    case 'debug':
      return 'bg-gray-500 text-white hover:bg-gray-500'
    default:
      return 'bg-secondary text-secondary-foreground'
  }
}

// Get color for level left-border indicator
function getLevelBorderColor(level: string): string {
  switch (level.toLowerCase()) {
    case 'fatal': return 'border-l-red-800'
    case 'error': return 'border-l-red-500'
    case 'warning': return 'border-l-amber-500'
    case 'info': return 'border-l-blue-500'
    case 'debug': return 'border-l-gray-400'
    default: return 'border-l-border'
  }
}

// Get freshness color based on how recently the last event occurred
function getLastSeenColor(lastSeen: string): string {
  const diff = getNow() - new Date(lastSeen).getTime()
  const hours = diff / (1000 * 60 * 60)
  if (hours < 1) return 'text-red-500 dark:text-red-400'
  if (hours < 24) return 'text-amber-600 dark:text-amber-400'
  return 'text-muted-foreground'
}

// Check if an issue is new (first seen within last 24 hours)
function isNewIssue(firstSeen: string): boolean {
  const diff = getNow() - new Date(firstSeen).getTime()
  return diff < 24 * 60 * 60 * 1000
}

// Format large numbers compactly
function formatCount(n: number): string {
  if (n >= 100000) return `${(n / 1000).toFixed(0)}k`
  if (n >= 10000) return `${(n / 1000).toFixed(1)}k`
  if (n >= 1000) return `${(n / 1000).toFixed(1)}k`
  return n.toLocaleString()
}

function normalizeApmTraceId(value: unknown): string | null {
  if (typeof value === 'number') {
    if (!Number.isInteger(value) || value < 0) return null
    return String(value)
  }
  if (typeof value !== 'string') return null
  const normalized = value.trim()
  return /^\d+$/.test(normalized) ? normalized : null
}

const ISSUE_TITLE_RENDER_MAX_CHARS = 240
const ISSUE_CULPRIT_RENDER_MAX_CHARS = 160

function clampText(value: string, maxChars: number): string {
  if (value.length <= maxChars) return value
  return `${value.slice(0, maxChars - 1)}…`
}

function getIssueDisplayTitle(issue: { title: string; culprit: string }): string {
  const title = clampText(issue.title?.trim() ?? '', ISSUE_TITLE_RENDER_MAX_CHARS)
  const culprit = clampText(issue.culprit?.trim() ?? '', ISSUE_CULPRIT_RENDER_MAX_CHARS)

  if (!title) return culprit || 'Unknown error'
  if (!culprit) return title

  const normalizedTitle = title.toLowerCase()
  const normalizedCulprit = culprit.toLowerCase()
  if (
    normalizedTitle.startsWith(`${normalizedCulprit}:`) ||
    normalizedTitle.startsWith(`${normalizedCulprit} `)
  ) {
    return title
  }

  return `${culprit}: ${title}`
}

// Simple sparkline component
function EventSparkline({ eventCount }: { eventCount: number }) {
  // Generate a simple frequency visualization
  const bars = Math.min(Math.ceil(eventCount / 10), 10)
  const heights = Array.from({ length: 10 }, (_, i) => {
    if (i < bars) {
      return 40 + ((i * 23 + 7) % 60) // 40-100% deterministic height for active bars
    }
    return 0
  })

  return (
    <div className="flex items-end gap-0.5 h-8 w-20">
      {heights.map((height, i) => (
        <div
          key={i}
          className="flex-1 bg-primary/60 rounded-sm transition-all"
          style={{ height: `${height}%` }}
        />
      ))}
    </div>
  )
}

export const Route = createFileRoute('/issues')({
  component: IssuesLayout,
})

function IssuesLayout() {
  const matches = useMatches()
  const showingChildRoute = matches.some((match) => match.id.includes('/issues/$issueId'))

  if (showingChildRoute) {
    return <Outlet />
  }

  return <IndexPage />
}

function IndexPage() {
  const [activeTab, setActiveTab] = useState<'issues' | 'apm-errors'>('issues')

  return (
    <div className="min-h-screen">
      <div className="border-b px-6">
        <div className="flex gap-4">
          <button
            type="button"
            onClick={() => setActiveTab('issues')}
            className={cn(
              'border-b-2 px-1 py-3 text-sm font-medium inline-flex items-center',
              activeTab === 'issues'
                ? 'border-primary text-foreground'
                : 'border-transparent text-muted-foreground hover:text-foreground'
            )}
          >
            <AlertCircle className="h-4 w-4 mr-1.5" />
            Issues
          </button>
          <button
            type="button"
            onClick={() => setActiveTab('apm-errors')}
            className={cn(
              'border-b-2 px-1 py-3 text-sm font-medium inline-flex items-center',
              activeTab === 'apm-errors'
                ? 'border-primary text-foreground'
                : 'border-transparent text-muted-foreground hover:text-foreground'
            )}
          >
            <Cpu className="h-4 w-4 mr-1.5" />
            APM Errors
          </button>
        </div>
      </div>
      {activeTab === 'issues' ? (
        <DashboardPage />
      ) : (
        <ApmErrorsTab isActive />
      )}
    </div>
  )
}

function DashboardPage() {
  const [searchQuery, setSearchQuery] = useState('')
  const [statusFilter, setStatusFilter] = useState<string>('unresolved')
  const [selectedIssues, setSelectedIssues] = useState<Set<string>>(new Set())
  const { selectedProjectId, setSelectedProjectId } = useProject()
  const { toast } = useToast()
  const queryClient = useQueryClient()

  const { data: projects, isLoading } = useQuery({
    queryKey: ['projects'],
    queryFn: () => api.getProjects(),
  })
  const hasProjects = (projects?.length ?? 0) > 0

  const projectId = selectedProjectId || projects?.[0]?.id
  const currentProject = projects?.find(p => p.id === projectId)

  // Auto-select first project after data load without mutating state during render.
  useEffect(() => {
    if (!selectedProjectId && projects && projects.length > 0 && projects[0]?.id) {
      setSelectedProjectId(projects[0].id)
    }
  }, [selectedProjectId, projects, setSelectedProjectId])

  const { data: issues = [] } = useQuery({
    queryKey: ['issues', projectId, statusFilter],
    queryFn: () => (projectId ? api.getIssues(projectId) : []),
    enabled: !!projectId,
  })

  const resolveMutation = useMutation({
    mutationFn: async (issueIds: string[]) => {
      await Promise.all(issueIds.map(id => api.updateIssue(id, { status: 'resolved' })))
    },
    onSuccess: () => {
      trackEvent('Issue Resolve', { count: String(selectedIssues.size) })
      queryClient.invalidateQueries({ queryKey: ['issues', projectId] })
      queryClient.invalidateQueries({ queryKey: ['stats', projectId] })
      toast({
        title: 'Success',
        description: `${selectedIssues.size} issue${selectedIssues.size === 1 ? '' : 's'} resolved`,
      })
      setSelectedIssues(new Set())
    },
    onError: () => {
      toast({
        title: 'Error',
        description: 'Failed to resolve issues',
        variant: 'destructive',
      })
    },
  })

  const handleToggleIssue = (issueId: string) => {
    const newSelected = new Set(selectedIssues)
    if (newSelected.has(issueId)) {
      newSelected.delete(issueId)
    } else {
      newSelected.add(issueId)
    }
    setSelectedIssues(newSelected)
  }

  const handleToggleAll = () => {
    if (selectedIssues.size === filteredIssues.length) {
      setSelectedIssues(new Set())
    } else {
      setSelectedIssues(new Set(filteredIssues.map(issue => issue.id)))
    }
  }

  const handleResolveSelected = () => {
    resolveMutation.mutate(Array.from(selectedIssues))
  }

  if (isLoading) return <div className="p-8">Loading...</div>

  const filteredIssues = issues.filter((issue) => {
    const matchesSearch =
      searchQuery === '' ||
      issue.title?.toLowerCase().includes(searchQuery.toLowerCase()) ||
      issue.culprit?.toLowerCase().includes(searchQuery.toLowerCase())
    const matchesStatus = statusFilter === 'all' || issue.status === statusFilter
    return matchesSearch && matchesStatus
  })

  return (
    <div className="min-h-screen">
      <div className="px-6 py-4">
        {/* Header */}
        <div className="mb-4 flex flex-col gap-4 sm:flex-row sm:items-center sm:justify-between">
          <div className="flex items-center gap-3">
            <div>
              <h2 className="text-2xl font-bold tracking-tight">Dashboard</h2>
              {currentProject && (
                <p className="text-sm text-muted-foreground">{currentProject.name}</p>
              )}
            </div>
            <span className="hidden sm:inline-flex h-2 w-2 rounded-full bg-emerald-500 animate-pulse" aria-hidden />
          </div>
          {hasProjects && (
            <div className="flex items-center gap-2 w-full sm:w-auto">
              {projectId && (
                <Link to="/projects/$projectId/settings" params={{ projectId: String(projectId) }}>
                  <Button
                    size="icon"
                    variant="outline"
                    className="border-primary/30 hover:bg-primary/10 hover:border-primary/50"
                    aria-label="Project settings"
                  >
                    <Settings className="h-4 w-4" />
                  </Button>
                </Link>
              )}
              <Link to="/projects" className="flex-1 sm:flex-none">
                <Button size="sm" variant="outline" className="w-full sm:w-auto border-primary/30 hover:bg-primary/10 hover:border-primary/50">
                  <Plus className="h-4 w-4 mr-2" />
                  New Project
                </Button>
              </Link>
            </div>
          )}
        </div>

        {!hasProjects ? (
          <Card className="p-12 text-center border-primary/20 bg-gradient-to-b from-card to-primary/5">
            <div className="max-w-md mx-auto space-y-4">
              <div className="flex justify-center">
                <div className="rounded-full bg-violet-500/15 p-4 ring-2 ring-violet-500/20">
                  <FolderKanban className="h-10 w-10 text-violet-600 dark:text-violet-400" />
                </div>
              </div>
              <div>
                <h3 className="text-lg font-semibold mb-2">No projects yet</h3>
                <p className="text-muted-foreground mb-4">
                  Create your first project to start tracking errors and monitoring your applications.
                </p>
              </div>
              <Link to="/projects" className="flex justify-center">
                <Button size="lg" className="w-full max-w-sm sm:w-auto sm:mx-auto">
                  <Plus className="h-4 w-4" />
                  Create Your First Project
                </Button>
              </Link>
            </div>
          </Card>
        ) : (
          <>
            {/* Toolbar */}
            <div className="mb-3 flex gap-3 items-center">
              {filteredIssues.length > 0 && (
                <div className="flex items-center gap-2">
                  <Checkbox
                    checked={selectedIssues.size === filteredIssues.length && filteredIssues.length > 0}
                    onCheckedChange={handleToggleAll}
                    aria-label="Select all issues"
                  />
                  <span className="text-sm text-muted-foreground whitespace-nowrap hidden sm:inline">Select all</span>
                </div>
              )}
              
              {selectedIssues.size > 0 && (
                <div className="flex items-center gap-2 bg-primary/10 border border-primary/20 rounded-lg px-3 py-1.5">
                  <CheckCircle2 className="h-4 w-4 text-primary" />
                  <span className="text-sm font-medium whitespace-nowrap">
                    {selectedIssues.size} selected
                  </span>
                  <Button
                    onClick={handleResolveSelected}
                    disabled={resolveMutation.isPending}
                    size="sm"
                    className="bg-green-600 hover:bg-green-700 h-7 ml-2"
                  >
                    {resolveMutation.isPending ? 'Resolving...' : 'Resolve'}
                  </Button>
                </div>
              )}

              <div className="relative flex-1">
                <Search className="absolute left-3 top-1/2 transform -translate-y-1/2 h-4 w-4 text-muted-foreground" />
                <Input
                  placeholder="Search issues..."
                  value={searchQuery}
                  onChange={(e) => setSearchQuery(e.target.value)}
                  className="pl-10"
                />
              </div>
              <Select value={statusFilter} onValueChange={setStatusFilter}>
                <SelectTrigger className="w-[180px]">
                  <SelectValue />
                </SelectTrigger>
                <SelectContent>
                  <SelectItem value="all">All Issues</SelectItem>
                  <SelectItem value="unresolved">Unresolved</SelectItem>
                  <SelectItem value="resolved">Resolved</SelectItem>
                </SelectContent>
              </Select>
              <span className="text-sm text-muted-foreground whitespace-nowrap hidden lg:inline">
                {filteredIssues.length} result{filteredIssues.length !== 1 ? 's' : ''}
              </span>
            </div>

            {filteredIssues.length === 0 ? (
              <Card className="p-12 text-center border-blue-500/20 bg-gradient-to-b from-card to-blue-500/5">
                <div className="max-w-md mx-auto space-y-4">
                  <div className="flex justify-center">
                    <div className="rounded-full bg-blue-500/10 p-4">
                      {searchQuery || statusFilter !== 'unresolved' ? (
                        <Search className="h-10 w-10 text-blue-600 dark:text-blue-400" />
                      ) : (
                        <AlertCircle className="h-10 w-10 text-blue-600 dark:text-blue-400" />
                      )}
                    </div>
                  </div>
                  <div>
                    <h3 className="text-lg font-semibold mb-2">
                      {searchQuery || statusFilter !== 'unresolved'
                        ? 'No issues match your filters'
                        : 'No issues yet'}
                    </h3>
                    <p className="text-muted-foreground">
                      {searchQuery || statusFilter !== 'unresolved'
                        ? 'Try adjusting your search or filters.'
                        : 'Start sending errors to this project to see them tracked here. Visit the setup guide to integrate your application.'}
                    </p>
                  </div>
                </div>
              </Card>
            ) : (
              <div className="rounded-lg border border-border/60 bg-card overflow-hidden">
                {/* Table header */}
                <div className="hidden md:flex items-center gap-3 py-2 px-4 bg-muted/40 border-b border-border/40 text-[11px] font-medium text-muted-foreground uppercase tracking-wider select-none">
                  <div className="w-5 shrink-0" />
                  <div className="w-14 shrink-0">Level</div>
                  <div className="flex-1 min-w-0">Issue</div>
                  <div className="hidden lg:block w-20 shrink-0">Platform</div>
                  <div className="hidden lg:block w-20 shrink-0 text-center">Trend</div>
                  <div className="w-[55px] shrink-0 text-right">Events</div>
                  <div className="hidden sm:block w-[45px] shrink-0 text-right">Users</div>
                  <div className="w-20 shrink-0 text-right">Last Seen</div>
                </div>
                {/* Issue rows */}
                <div className="divide-y divide-border/40">
                  {filteredIssues.map((issue) => (
                    <div
                      key={issue.id}
                      className={`hover:bg-accent/40 transition border-l-[3px] ${getLevelBorderColor(issue.level)}`}
                    >
                      <div className="flex items-center gap-2 sm:gap-3 py-2 sm:py-2.5 px-2 sm:px-4">
                        <Checkbox
                          checked={selectedIssues.has(issue.id)}
                          onCheckedChange={() => handleToggleIssue(issue.id)}
                          onClick={(e) => e.stopPropagation()}
                          aria-label={`Select ${issue.title}`}
                          className="shrink-0"
                        />
                        <Link
                          to="/issues/$issueId"
                          params={{ issueId: issue.id }}
                          className="flex-1 flex items-center gap-2 sm:gap-3 min-w-0"
                        >
                          <Badge className={`${getLevelColor(issue.level)} shrink-0 text-[10px] sm:text-[11px] px-1 sm:px-1.5 py-0 w-12 sm:w-14 justify-center`}>
                            {issue.level.toUpperCase().slice(0, 3)}
                            <span className="hidden sm:inline">{issue.level.toUpperCase().slice(3)}</span>
                          </Badge>
                          <div className="flex-1 min-w-0">
                            <div className="flex items-center gap-1.5 sm:gap-2 min-w-0">
                              <span className="font-semibold truncate flex-1 min-w-0 text-sm sm:text-base" title={getIssueDisplayTitle(issue)}>
                                {getIssueDisplayTitle(issue)}
                              </span>
                              <div className="flex items-center gap-2 shrink-0">
                                {isNewIssue(issue.firstSeen) && (
                                  <span className="text-[10px] font-bold text-emerald-500 bg-emerald-500/10 px-1.5 py-0.5 rounded uppercase">
                                    New
                                  </span>
                                )}
                                {issue.status === 'resolved' && (
                                  <Badge variant="default" className="bg-green-600 text-[11px] px-1.5 py-0">
                                    Resolved
                                  </Badge>
                                )}
                              </div>
                            </div>
                            <div className="text-xs text-muted-foreground mt-0.5">
                              <Clock className="inline h-3 w-3 mr-1 -mt-0.5" />
                              First seen {formatRelativeTime(issue.firstSeen)}
                            </div>
                          </div>
                          <div className="hidden lg:block w-20 shrink-0">
                            <Badge variant="outline" className="text-[11px] px-1.5 py-0">{issue.platform}</Badge>
                          </div>
                          <div className="hidden lg:flex w-20 shrink-0 justify-center">
                            <EventSparkline eventCount={issue.eventCount} />
                          </div>
                          <div className="w-[55px] shrink-0 text-right">
                            <div className="font-semibold text-foreground">{formatCount(issue.eventCount)}</div>
                            <div className="text-xs text-muted-foreground">events</div>
                          </div>
                          <div className="hidden sm:block w-[45px] shrink-0 text-right">
                            <div className="font-semibold text-foreground">{issue.userCount ?? 0}</div>
                            <div className="text-xs text-muted-foreground">users</div>
                          </div>
                          <div className="hidden md:block w-20 shrink-0 text-right">
                            <span className={`text-xs font-medium ${getLastSeenColor(issue.lastSeen)}`}>
                              {formatRelativeTime(issue.lastSeen)}
                            </span>
                          </div>
                        </Link>
                      </div>
                    </div>
                  ))}
                </div>
              </div>
            )}
          </>
        )}
      </div>
    </div>
  )
}

const APM_ERRORS_PAGE_SIZE = 50

function ApmErrorsTab({ isActive }: { isActive: boolean }) {
  const [selectedService, setSelectedService] = useState<string>('all')
  const [offset, setOffset] = useState(0)

  // Fetch all errors (no filter) to derive the service list
  const { data: allErrorsData } = useQuery({
    queryKey: ['apm-errors-all'],
    queryFn: () => api.getApmErrors({ limit: 500 }),
    enabled: isActive && api.isAuthenticated(),
    refetchInterval: 30000,
  })

  const services = useMemo(() => {
    const countByService = new Map<string, number>()
    for (const err of allErrorsData?.errors ?? []) {
      countByService.set(err.service, (countByService.get(err.service) ?? 0) + err.count)
    }
    return Array.from(countByService.entries())
      .map(([service, count]) => ({ service, count }))
      .sort((a, b) => b.count - a.count)
  }, [allErrorsData])

  const { data, isLoading } = useQuery({
    queryKey: ['apm-errors', selectedService, offset],
    queryFn: () => api.getApmErrors({
      service: selectedService === 'all' ? undefined : selectedService,
      limit: APM_ERRORS_PAGE_SIZE,
      offset,
    }),
    enabled: isActive && api.isAuthenticated(),
    refetchInterval: 15000,
  })

  const errors = data?.errors ?? []
  const totalCount = data?.totalCount ?? 0
  const canPrev = offset > 0
  const canNext = offset + errors.length < totalCount

  if (!isLoading && data && errors.length === 0 && totalCount > 0 && offset > 0) {
    setOffset(0)
  }

  function handleServiceChange(service: string) {
    setSelectedService(service)
    setOffset(0)
  }

  const MAX_VISIBLE_TABS = 5
  const visibleServices = services.slice(0, MAX_VISIBLE_TABS)
  const overflowServices = services.slice(MAX_VISIBLE_TABS)
  const overflowSelected = overflowServices.some((s) => s.service === selectedService)

  return (
    <div className="px-6 py-4">
      <div className="mb-4">
        <h2 className="text-2xl font-bold tracking-tight">APM Errors</h2>
        <p className="text-sm text-muted-foreground">
          Application performance errors from traces
        </p>
      </div>

      {services.length > 0 && (
        <div className="flex items-end gap-0 mb-4 border-b border-border/60">
          <button
            onClick={() => handleServiceChange('all')}
            className={cn(
              'flex items-center gap-1.5 px-3 py-2 text-sm font-medium border-b-2 -mb-px transition-colors whitespace-nowrap',
              selectedService === 'all'
                ? 'border-primary text-foreground'
                : 'border-transparent text-muted-foreground hover:text-foreground hover:border-muted-foreground/30',
            )}
          >
            All
          </button>
          {visibleServices.map((svc) => (
            <button
              key={svc.service}
              onClick={() => handleServiceChange(svc.service)}
              className={cn(
                'flex items-center gap-1.5 px-3 py-2 text-sm font-medium border-b-2 -mb-px transition-colors whitespace-nowrap',
                selectedService === svc.service
                  ? 'border-primary text-foreground'
                  : 'border-transparent text-muted-foreground hover:text-foreground hover:border-muted-foreground/30',
              )}
            >
              <Server className="h-3 w-3" />
              {svc.service}
              <span className="text-xs text-muted-foreground tabular-nums">
                {svc.count}
              </span>
            </button>
          ))}
          {overflowServices.length > 0 && (
            <div className={cn('pb-0.5 ml-1', overflowSelected && 'border-b-2 border-primary -mb-px')}>
              <Select
                value={overflowSelected ? selectedService : ''}
                onValueChange={(val) => handleServiceChange(val)}
              >
                <SelectTrigger className="h-8 text-sm border-0 shadow-none px-2 gap-1 text-muted-foreground hover:text-foreground focus:ring-0 w-auto">
                  <SelectValue placeholder={`+${overflowServices.length} more`} />
                </SelectTrigger>
                <SelectContent>
                  {overflowServices.map((svc) => (
                    <SelectItem key={svc.service} value={svc.service}>
                      <span className="flex items-center gap-2">
                        <Server className="h-3 w-3" />
                        {svc.service}
                        <span className="text-xs text-muted-foreground tabular-nums">{svc.count}</span>
                      </span>
                    </SelectItem>
                  ))}
                </SelectContent>
              </Select>
            </div>
          )}
        </div>
      )}

      {isLoading ? (
        <div className="py-16 text-center text-muted-foreground">
          Loading APM errors...
        </div>
      ) : errors.length === 0 ? (
        <Card className="p-12 text-center border-blue-500/20 bg-gradient-to-b from-card to-blue-500/5">
          <div className="max-w-md mx-auto space-y-4">
            <div className="flex justify-center">
              <div className="rounded-full bg-blue-500/10 p-4">
                <AlertTriangle className="h-10 w-10 text-blue-600 dark:text-blue-400" />
              </div>
            </div>
            <div>
              <h3 className="text-lg font-semibold mb-2">No APM errors found</h3>
              <p className="text-muted-foreground">
                Errors from application traces will appear here when spans report errors.
              </p>
            </div>
          </div>
        </Card>
      ) : (
        <div className="rounded-lg border border-border/60 bg-card overflow-hidden">
          {totalCount > APM_ERRORS_PAGE_SIZE && (
            <div className="px-4 py-2 text-xs text-muted-foreground border-b border-border/40">
              Showing {offset + 1}–{offset + errors.length} of {totalCount}
            </div>
          )}
          <div className="hidden md:flex items-center gap-3 py-2 px-4 bg-muted/40 border-b border-border/40 text-[11px] font-medium text-muted-foreground uppercase tracking-wider select-none">
            <div className="w-16 shrink-0">Service</div>
            <div className="flex-1 min-w-0">Error</div>
            <div className="w-16 shrink-0 text-right">Count</div>
            <div className="w-24 shrink-0 text-right">Last Seen</div>
            <div className="w-16 shrink-0 text-right">Trace</div>
          </div>
          <div className="divide-y divide-border/40">
            {errors.map((error: ApmErrorGroup) => {
              const traceId = normalizeApmTraceId(error.traceId)
              const stableKey = `${error.service}|${error.resource}|${error.errorMessage}|${error.errorType}`
              return (
                <div
                  key={stableKey}
                  className="hover:bg-accent/40 transition border-l-[3px] border-l-red-500"
                >
                  <div className="flex items-center gap-2 sm:gap-3 py-2 sm:py-2.5 px-2 sm:px-4">
                    <Badge
                      variant="outline"
                      className="shrink-0 text-[11px] px-1.5 py-0 gap-1"
                    >
                      <Server className="h-3 w-3" />
                      {error.service}
                    </Badge>
                    <div className="flex-1 min-w-0">
                      <div className="font-semibold truncate text-sm">
                        {error.errorMessage || error.resource}
                      </div>
                      {error.errorType && (
                        <div className="text-xs text-muted-foreground truncate">
                          {error.errorType}
                        </div>
                      )}
                      <div className="text-xs text-muted-foreground truncate mt-0.5">
                        {error.resource}
                      </div>
                    </div>
                    <div className="w-16 shrink-0 text-right">
                      <div className="font-semibold text-foreground">
                        {formatCount(error.count)}
                      </div>
                      <div className="text-xs text-muted-foreground">errors</div>
                    </div>
                    <div className="hidden md:block w-24 shrink-0 text-right text-xs text-muted-foreground">
                      {error.lastSeen ? formatRelativeTime(error.lastSeen) : '—'}
                    </div>
                    <div className="w-16 shrink-0 text-right">
                      {traceId && (
                        <Link
                          to="/performance/traces/$traceId"
                          params={{ traceId }}
                          className="text-xs text-primary hover:underline"
                          onClick={(e) => e.stopPropagation()}
                        >
                          View trace
                        </Link>
                      )}
                    </div>
                  </div>
                </div>
              )
            })}
          </div>
          {(canPrev || canNext) && (
            <div className="flex justify-end gap-2 px-4 py-2 border-t border-border/40">
              <Button
                size="sm"
                variant="outline"
                disabled={!canPrev}
                onClick={() => setOffset(Math.max(0, offset - APM_ERRORS_PAGE_SIZE))}
              >
                Previous
              </Button>
              <Button
                size="sm"
                variant="outline"
                disabled={!canNext}
                onClick={() => setOffset(offset + APM_ERRORS_PAGE_SIZE)}
              >
                Next
              </Button>
            </div>
          )}
        </div>
      )}
    </div>
  )
}
