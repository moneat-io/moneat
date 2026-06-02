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

import {createFileRoute, Link} from '@tanstack/react-router'

import {useMutation, useQuery, useQueryClient} from '@tanstack/react-query'
import {api} from '@/lib/api'
import type {ApmErrorGroup, ApmTimeRange} from '@/lib/api'
import {trackEvent} from '@/lib/analytics'
import {useProject} from '@/contexts/ProjectContext'
import {formatRelativeTime, cn} from '@/lib/utils'
import {Badge} from '@/components/ui/badge'
import {Button} from '@/components/ui/button'
import {Input} from '@/components/ui/input'
import {Select, SelectContent, SelectItem, SelectTrigger, SelectValue,} from '@/components/ui/select'
import {Card} from '@/components/ui/card'
import {PageHeader} from '@/components/ui/page-header'
import {levelBadgeVariant, levelBorderClass} from '@/lib/severity'
import {Checkbox} from '@/components/ui/checkbox'
import {DropdownMenu, DropdownMenuContent, DropdownMenuItem, DropdownMenuTrigger} from '@/components/ui/dropdown-menu'
import {
  AlertCircle,
  AlertTriangle,
  CheckCircle2,
  ChevronDown,
  Clock,
  Cpu,
  EyeOff,
  FolderKanban,
  Plus,
  Search,
  Server,
  Settings,
  Timer,
} from 'lucide-react'
import {useEffect, useMemo, useState} from 'react'
import {useToast} from '@/hooks/useToast'
import {getNow} from '@/lib/demo'
// Level → Badge variant and left-border indicator come from the shared severity
// helper (@/lib/severity) so every surface uses the same status language.

// Get freshness color based on how recently the last event occurred
function getLastSeenColor(lastSeen: string): string {
  const diff = getNow() - new Date(lastSeen).getTime()
  const hours = diff / (1000 * 60 * 60)
  if (hours < 1) return 'text-danger-fg'
  if (hours < 24) return 'text-warning-fg'
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
const ISSUE_SEARCH_TEXT_MAX_CHARS = 512
const ISSUE_ID_MAX_CHARS = 512

type SafeIssue = {
  id: string
  title: string
  culprit: string
  level: string
  platform: string
  firstSeen: string
  lastSeen: string
  eventCount: number
  userCount: number
  status: string
}

function clampText(value: string, maxChars: number): string {
  if (value.length <= maxChars) return value
  return `${value.slice(0, maxChars - 1)}…`
}

function toSafeString(value: unknown, fallback = '', maxChars = ISSUE_SEARCH_TEXT_MAX_CHARS): string {
  const raw = typeof value === 'string' ? value : fallback
  const trimmed = raw.trim()
  if (trimmed.length <= maxChars) return trimmed
  return trimmed.slice(0, maxChars)
}

function toSafeCount(value: unknown): number {
  const n = typeof value === 'number' ? value : Number(value)
  if (!Number.isFinite(n) || n < 0) return 0
  return Math.floor(n)
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
function EventSparkline({ eventCount, eventSeries }: { eventCount: number; eventSeries?: number[] }) {
  let heights: number[]
  if (eventSeries && eventSeries.length > 0) {
    const max = Math.max(...eventSeries, 1)
    const padded = eventSeries.slice(-10)
    while (padded.length < 10) padded.unshift(0)
    heights = padded.map(v => Math.round((v / max) * 100))
  } else {
    // Static activity indicator — not a trend
    const bars = Math.min(Math.ceil(eventCount / 10), 10)
    heights = Array.from({ length: 10 }, (_, i) => (i < bars ? 40 : 0))
  }

  return (
    <div
      className="flex items-end gap-0.5 h-8 w-20"
      aria-label="activity indicator, not a trend"
      title="activity indicator, not a trend"
    >
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

export const Route = createFileRoute('/issues/')({
  component: IndexPage,
})

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
  const [page, setPage] = useState(1)
  const pageSize = 25
  const { selectedProjectId, setSelectedProjectId } = useProject()
  const { toast } = useToast()
  const queryClient = useQueryClient()

  const { data: projects, isLoading, isError: projectsError, error: projectsErrorObj } = useQuery({
    queryKey: ['projects'],
    queryFn: () => api.getProjects(),
  })
  const hasProjects = (projects?.length ?? 0) > 0

  const projectId = selectedProjectId || projects?.[0]?.resourceId
  const currentProject = projects?.find(p => p.resourceId === projectId)

  // Auto-select first project after data load without mutating state during render.
  useEffect(() => {
    if (!selectedProjectId && projects && projects.length > 0 && projects[0]?.resourceId) {
      setSelectedProjectId(projects[0].resourceId)
    }
  }, [selectedProjectId, projects, setSelectedProjectId])

  // Reset selection and page when the visible dataset changes (React-recommended
  // "adjusting state during rendering" pattern avoids cascading effect renders).
  const [prevProjectId, setPrevProjectId] = useState(projectId)
  const [prevStatusFilter, setPrevStatusFilter] = useState(statusFilter)
  if (projectId !== prevProjectId || statusFilter !== prevStatusFilter) {
    setPrevProjectId(projectId)
    setPrevStatusFilter(statusFilter)
    setSelectedIssues(new Set())
    setPage(1)
  }

  const { data: issues = [], isError: issuesError, error: issuesErrorObj } = useQuery({
    queryKey: ['issues', projectId, statusFilter, page, pageSize],
    queryFn: () => (projectId ? api.getIssues(projectId, page, pageSize, statusFilter === 'all' ? undefined : statusFilter) : []),
    enabled: !!projectId,
  })

  const resolveMutation = useMutation({
    mutationFn: async (issueIds: string[]) => {
      const results = await Promise.allSettled(
        issueIds.map(id => api.updateIssue(id, { status: 'resolved' }))
      )
      const successCount = results.filter(r => r.status === 'fulfilled').length
      if (successCount === 0) throw new Error('All requests failed')
      return { submitted: issueIds.length, successCount }
    },
    onSuccess: ({ submitted, successCount }) => {
      trackEvent('Issue Resolve', { count: String(successCount) })
      queryClient.invalidateQueries({ queryKey: ['issues', projectId] })
      queryClient.invalidateQueries({ queryKey: ['stats', projectId] })
      if (successCount === submitted) {
        toast({
          title: 'Success',
          description: `${submitted} issue${submitted === 1 ? '' : 's'} resolved`,
        })
      } else {
        toast({
          title: 'Partial Success',
          description: `${successCount}/${submitted} issues resolved`,
          variant: 'destructive',
        })
      }
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

  const ignoreMutation = useMutation({
    mutationFn: async (issueIds: string[]) => {
      const results = await Promise.allSettled(
        issueIds.map(id => api.updateIssue(id, { status: 'ignored' }))
      )
      const successCount = results.filter(r => r.status === 'fulfilled').length
      if (successCount === 0) throw new Error('All requests failed')
      return { submitted: issueIds.length, successCount }
    },
    onSuccess: ({ submitted, successCount }) => {
      trackEvent('Issue Ignore', { count: String(successCount) })
      queryClient.invalidateQueries({ queryKey: ['issues', projectId] })
      queryClient.invalidateQueries({ queryKey: ['stats', projectId] })
      if (successCount === submitted) {
        toast({
          title: 'Success',
          description: `${submitted} issue${submitted === 1 ? '' : 's'} ignored`,
        })
      } else {
        toast({
          title: 'Partial Success',
          description: `${successCount}/${submitted} issues ignored`,
          variant: 'destructive',
        })
      }
      setSelectedIssues(new Set())
    },
    onError: () => {
      toast({ title: 'Error', description: 'Failed to ignore issues', variant: 'destructive' })
    },
  })

  const resolveNextReleaseMutation = useMutation({
    mutationFn: async (issueIds: string[]) => {
      const results = await Promise.allSettled(
        issueIds.map(id => api.updateIssue(id, { status: 'resolvedInNextRelease' }))
      )
      const successCount = results.filter(r => r.status === 'fulfilled').length
      if (successCount === 0) throw new Error('All requests failed')
      return { submitted: issueIds.length, successCount }
    },
    onSuccess: ({ submitted, successCount }) => {
      trackEvent('Issue ResolveInNextRelease', { count: String(successCount) })
      queryClient.invalidateQueries({ queryKey: ['issues', projectId] })
      queryClient.invalidateQueries({ queryKey: ['stats', projectId] })
      if (successCount === submitted) {
        toast({
          title: 'Success',
          description: `${submitted} issue${submitted === 1 ? '' : 's'} marked to resolve in next release`,
        })
      } else {
        toast({
          title: 'Partial Success',
          description: `${successCount}/${submitted} issues marked to resolve in next release`,
          variant: 'destructive',
        })
      }
      setSelectedIssues(new Set())
    },
    onError: () => {
      toast({ title: 'Error', description: 'Failed to update issues', variant: 'destructive' })
    },
  })

  const bulkPending = resolveMutation.isPending || ignoreMutation.isPending || resolveNextReleaseMutation.isPending

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

  const handleIgnoreSelected = () => {
    ignoreMutation.mutate(Array.from(selectedIssues))
  }

  const handleResolveNextReleaseSelected = () => {
    resolveNextReleaseMutation.mutate(Array.from(selectedIssues))
  }

  const safeIssues = useMemo<SafeIssue[]>(() => {
    return issues
      .map((issue) => {
        const id = toSafeString(issue.id, '', ISSUE_ID_MAX_CHARS)
        return {
          id,
          title: toSafeString(issue.title, '', ISSUE_SEARCH_TEXT_MAX_CHARS),
          culprit: toSafeString(issue.culprit, '', ISSUE_SEARCH_TEXT_MAX_CHARS),
          level: toSafeString(issue.level, 'error', 16) || 'error',
          platform: toSafeString(issue.platform, 'unknown', 64) || 'unknown',
          firstSeen: toSafeString(issue.firstSeen, ''),
          lastSeen: toSafeString(issue.lastSeen, ''),
          eventCount: toSafeCount(issue.eventCount),
          userCount: toSafeCount(issue.userCount),
          status: toSafeString(issue.status, 'unresolved', 32) || 'unresolved',
        }
      })
      .filter((issue) => issue.id.length > 0)
  }, [issues])

  const normalizedSearchQuery = searchQuery.trim().toLowerCase()

  const filteredIssues = safeIssues.filter((issue) => {
    const matchesSearch =
      normalizedSearchQuery === '' ||
      issue.title.toLowerCase().includes(normalizedSearchQuery) ||
      issue.culprit.toLowerCase().includes(normalizedSearchQuery)
    return matchesSearch
  })

  if (isLoading) return <div className="p-8">Loading...</div>

  if (projectsError) return (
    <div className="p-8 text-destructive">
      Failed to load projects: {projectsErrorObj instanceof Error ? projectsErrorObj.message : 'Unknown error'}
    </div>
  )

  return (
    <div className="min-h-screen">
      <div className="px-6 py-4">
        {/* Header */}
        <div className="mb-4 flex flex-col gap-4 sm:flex-row sm:items-center sm:justify-between">
          <div className="flex items-center gap-3">
            <div>
              <h2 className="text-xl font-semibold tracking-tight">Dashboard</h2>
              {currentProject && (
                <p className="text-sm text-muted-foreground">{currentProject.name}</p>
              )}
            </div>
            <span className="hidden sm:inline-flex h-2 w-2 rounded-full bg-success-solid animate-pulse" aria-hidden />
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
                <div className="rounded-full bg-primary/15 p-4 ring-2 ring-primary/20">
                  <FolderKanban className="h-10 w-10 text-primary" />
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
                  <DropdownMenu>
                    <DropdownMenuTrigger asChild>
                      <Button
                        disabled={bulkPending}
                        size="sm"
                        className="h-7 ml-2"
                      >
                        {bulkPending ? 'Updating...' : 'Actions'}
                        <ChevronDown className="h-3 w-3 ml-1" />
                      </Button>
                    </DropdownMenuTrigger>
                    <DropdownMenuContent align="end">
                      <DropdownMenuItem onClick={handleResolveSelected}>
                        <CheckCircle2 className="h-4 w-4 mr-2" />
                        Resolve
                      </DropdownMenuItem>
                      <DropdownMenuItem onClick={handleIgnoreSelected}>
                        <EyeOff className="h-4 w-4 mr-2" />
                        Ignore
                      </DropdownMenuItem>
                      <DropdownMenuItem onClick={handleResolveNextReleaseSelected}>
                        <Timer className="h-4 w-4 mr-2" />
                        Resolve in Next Release
                      </DropdownMenuItem>
                    </DropdownMenuContent>
                  </DropdownMenu>
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
              <Select value={statusFilter} onValueChange={(v) => { setStatusFilter(v); setPage(1) }}>
                <SelectTrigger className="w-[180px]">
                  <SelectValue />
                </SelectTrigger>
                <SelectContent>
                  <SelectItem value="all">All Issues</SelectItem>
                  <SelectItem value="unresolved">Unresolved</SelectItem>
                  <SelectItem value="resolved">Resolved</SelectItem>
                  <SelectItem value="ignored">Ignored</SelectItem>
                  <SelectItem value="resolvedInNextRelease">Resolve in Next Release</SelectItem>
                </SelectContent>
              </Select>
              <span className="text-sm text-muted-foreground whitespace-nowrap hidden lg:inline">
                {filteredIssues.length} result{filteredIssues.length !== 1 ? 's' : ''}
              </span>
            </div>

            {issuesError ? (
              <div className="p-6 text-destructive border border-destructive/30 rounded-lg bg-destructive/5">
                Failed to load issues: {issuesErrorObj instanceof Error ? issuesErrorObj.message : 'Unknown error'}
              </div>
            ) : filteredIssues.length === 0 ? (
              <Card className="p-12 text-center border-info-border/50 bg-gradient-to-b from-card to-info-bg">
                <div className="max-w-md mx-auto space-y-4">
                  <div className="flex justify-center">
                    <div className="rounded-full bg-info-bg p-4">
                      {searchQuery || statusFilter !== 'unresolved' ? (
                        <Search className="h-10 w-10 text-info-fg" />
                      ) : (
                        <AlertCircle className="h-10 w-10 text-info-fg" />
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
                  <div className="w-4 shrink-0" />
                  <div className="w-[4.5rem] shrink-0">Level</div>
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
                      className={`hover:bg-accent/40 transition border-l-[3px] ${levelBorderClass(issue.level)}`}
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
                          <div className="w-12 sm:w-[4.5rem] shrink-0">
                            <Badge variant={levelBadgeVariant(issue.level)} className="text-[10px] sm:text-[11px] px-1.5 py-0">
                              <span className="sm:hidden">{issue.level.toUpperCase().slice(0, 3)}</span>
                              <span className="hidden sm:inline">{issue.level.toUpperCase()}</span>
                            </Badge>
                          </div>
                          <div className="flex-1 min-w-0">
                            <div className="flex items-center gap-1.5 sm:gap-2 min-w-0">
                              <span className="font-semibold truncate flex-1 min-w-0 text-sm sm:text-base" title={getIssueDisplayTitle(issue)}>
                                {getIssueDisplayTitle(issue)}
                              </span>
                              <div className="flex items-center gap-2 shrink-0">
                                {isNewIssue(issue.firstSeen) && (
                                  <span className="text-[10px] font-bold text-success-fg bg-success-bg px-1.5 py-0.5 rounded uppercase">
                                    New
                                  </span>
                                )}
                                {issue.status === 'resolved' && (
                                  <Badge variant="success" className="text-[11px] px-1.5 py-0">
                                    Resolved
                                  </Badge>
                                )}
                                {issue.status === 'ignored' && (
                                  <Badge variant="secondary" className="text-[11px] px-1.5 py-0">
                                    <EyeOff className="h-3 w-3" />
                                    Ignored
                                  </Badge>
                                )}
                                {issue.status === 'resolvedInNextRelease' && (
                                  <Badge variant="info" className="text-[11px] px-1.5 py-0">
                                    <Timer className="h-3 w-3" />
                                    Next Release
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
                {(page > 1 || issues.length === pageSize) && (
                  <div className="flex justify-end gap-2 px-4 py-2 border-t border-border/40">
                    <Button
                      size="sm"
                      variant="outline"
                      disabled={page <= 1}
                      onClick={() => setPage(p => Math.max(1, p - 1))}
                    >
                      Previous
                    </Button>
                    <span className="text-sm text-muted-foreground self-center">
                      Page {page}
                    </span>
                    <Button
                      size="sm"
                      variant="outline"
                      disabled={issues.length < pageSize}
                      onClick={() => setPage(p => p + 1)}
                    >
                      Next
                    </Button>
                  </div>
                )}
              </div>
            )}
          </>
        )}
      </div>
    </div>
  )
}

const APM_ERRORS_PAGE_SIZE = 50
const APM_TIME_RANGE_OPTIONS: Array<{value: ApmTimeRange; label: string}> = [
  {value: '1h', label: '1h'},
  {value: '6h', label: '6h'},
  {value: '24h', label: '24h'},
  {value: '7d', label: '7d'},
  {value: '30d', label: '30d'},
  {value: '90d', label: '90d'},
]

function ApmErrorsTab({ isActive }: { isActive: boolean }) {
  const [selectedService, setSelectedService] = useState<string>('all')
  const [timeRange, setTimeRange] = useState<ApmTimeRange>('24h')
  const [offset, setOffset] = useState(0)

  const { data, isLoading } = useQuery({
    queryKey: ['apm-errors', selectedService, timeRange, offset],
    queryFn: () => api.getApmErrors({
      service: selectedService === 'all' ? undefined : selectedService,
      limit: APM_ERRORS_PAGE_SIZE,
      offset,
      timeRange,
    }),
    enabled: isActive && api.isAuthenticated(),
    retry: false,
    staleTime: 30000,
    refetchOnWindowFocus: false,
  })

  const errors = data?.errors ?? []
  const totalCount = data?.totalCount ?? 0
  const canPrev = offset > 0
  const canNext = offset + errors.length < totalCount

  const services = useMemo(() => {
    const countByService = new Map<string, number>()
    for (const facet of data?.serviceFacets ?? []) {
      countByService.set(facet.service, (countByService.get(facet.service) ?? 0) + facet.count)
    }
    return Array.from(countByService.entries())
      .map(([service, count]) => ({ service, count }))
      .sort((a, b) => b.count - a.count)
  }, [data?.serviceFacets])

  function handleServiceChange(service: string) {
    setSelectedService(service)
    setOffset(0)
  }

  function handleTimeRangeChange(value: string) {
    setTimeRange(value as ApmTimeRange)
    setOffset(0)
  }

  const MAX_VISIBLE_TABS = 5
  const visibleServices = services.slice(0, MAX_VISIBLE_TABS)
  const overflowServices = services.slice(MAX_VISIBLE_TABS)
  const overflowSelected = overflowServices.some((s) => s.service === selectedService)

  return (
    <div className="px-6 py-4">
      <PageHeader
        className="mb-4"
        title="APM Errors"
        description="Application performance errors from traces"
        actions={
          <Select value={timeRange} onValueChange={handleTimeRangeChange}>
            <SelectTrigger className="h-9 w-[110px]">
              <SelectValue />
            </SelectTrigger>
            <SelectContent>
              {APM_TIME_RANGE_OPTIONS.map((option) => (
                <SelectItem key={option.value} value={option.value}>
                  {option.label}
                </SelectItem>
              ))}
            </SelectContent>
          </Select>
        }
      />

      {(services.length > 0 || selectedService !== 'all') && (
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
                <SelectTrigger className="h-8 text-sm border-0 px-2 gap-1 text-muted-foreground hover:text-foreground focus:ring-0 w-auto">
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
        <Card className="p-12 text-center border-info-border/50 bg-gradient-to-b from-card to-info-bg">
          <div className="max-w-md mx-auto space-y-4">
            <div className="flex justify-center">
              <div className="rounded-full bg-info-bg p-4">
                <AlertTriangle className="h-10 w-10 text-info-fg" />
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
          <div className="hidden md:grid md:grid-cols-[8rem_1fr_4rem_6rem_4rem] items-center gap-3 py-2 px-4 bg-muted/40 border-b border-border/40 text-[11px] font-medium text-muted-foreground uppercase tracking-wider select-none">
            <div>Service</div>
            <div>Error</div>
            <div className="text-right">Count</div>
            <div className="text-right">Last Seen</div>
            <div className="text-right">Trace</div>
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
                  <div className="grid grid-cols-[auto_1fr_auto] md:grid-cols-[8rem_1fr_4rem_6rem_4rem] items-center gap-2 sm:gap-3 py-2 sm:py-2.5 px-2 sm:px-4">
                    <Badge
                      variant="outline"
                      className="shrink-0 text-[11px] px-1.5 py-0 gap-1 w-fit"
                    >
                      <Server className="h-3 w-3" />
                      {error.service}
                    </Badge>
                    <div className="min-w-0">
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
                    <div className="text-right">
                      <div className="font-semibold text-foreground">
                        {formatCount(error.count)}
                      </div>
                      <div className="text-xs text-muted-foreground">errors</div>
                    </div>
                    <div className="hidden md:block text-right text-xs text-muted-foreground">
                      {error.lastSeen ? formatRelativeTime(error.lastSeen) : '—'}
                    </div>
                    <div className="hidden md:block text-right">
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
