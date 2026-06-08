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

import {createFileRoute, Outlet, redirect, useMatches, useNavigate} from '@tanstack/react-router'
import {useQuery} from '@tanstack/react-query'
import {api} from '@/lib/api'
import {formatRelativeTime} from '@/lib/utils'
import {useTimezone} from '@/hooks/useTimezone'
import {formatDate as formatDateUtil, parseDate} from '@/lib/date-format'
import {useMemo, useState} from 'react'
import {ExplorerShell} from '@/components/filters/ExplorerShell'
import {FacetRail} from '@/components/filters/FacetRail'
import {Badge} from '@/components/ui/badge'
import {Input} from '@/components/ui/input'
import {StatCard} from '@/components/ui/stat-card'
import {EmptyState} from '@/components/ui/empty-state'
import {Avatar, AvatarFallback} from '@/components/ui/avatar'
import {Button} from '@/components/ui/button'
import {Select, SelectContent, SelectItem, SelectTrigger, SelectValue,} from '@/components/ui/select'
import {Tooltip, TooltipContent, TooltipProvider, TooltipTrigger,} from '@/components/ui/tooltip'
import {
  AlertCircle,
  ChevronLeft,
  ChevronRight,
  Clock,
  Globe,
  Monitor,
  MousePointerClick,
  Play,
  Search,
  Timer,
  User,
  Video,
} from 'lucide-react'
import {
  facetValues,
  serviceNamesForQuery,
  serviceRailSections,
  serviceScopeKey,
} from '@/lib/service-facet-scope'
import type {FacetFilter} from '@/lib/filters/types'

export const Route = createFileRoute('/replays')({
  beforeLoad: async ({ location }) => {
    if (!api.isAuthenticated()) {
      throw redirect({ to: '/login', search: { redirect: location.href } })
    }
  },
  component: ReplaysLayout,
})

function ReplaysLayout() {
  const matches = useMatches()
  const showingChildRoute = matches.some((match) => match.id.includes('/replays/$replayId'))

  if (showingChildRoute) {
    return <Outlet />
  }

  return <ReplaysPage />
}

function formatDuration(ms: number) {
  if (ms < 1000) return `${ms.toFixed(0)}ms`
  if (ms < 60000) return `${(ms / 1000).toFixed(1)}s`
  const minutes = Math.floor(ms / 60000)
  const seconds = Math.floor((ms % 60000) / 1000)
  if (minutes < 60) return `${minutes}m ${seconds}s`
  const hours = Math.floor(minutes / 60)
  return `${hours}h ${minutes % 60}m`
}

function formatDate(isoString: string, timezone: string) {
  if (!isoString) return 'N/A'
  const date = parseDate(isoString)
  if (isNaN(date.getTime())) return 'Invalid Date'
  return formatDateUtil(date, timezone)
}

function getInitials(user?: { id?: string; email?: string; username?: string }): string {
  if (user?.username) {
    const parts = user.username.trim().split(/\s+/)
    if (parts.length >= 2) return (parts[0][0] + parts[1][0]).toUpperCase()
    return user.username.slice(0, 2).toUpperCase()
  }
  if (user?.email) return user.email.slice(0, 2).toUpperCase()
  if (user?.id) return user.id.slice(0, 2).toUpperCase()
  return '?'
}

// Deterministic avatar tint from the categorical chart palette (literal classes
// so Tailwind emits them); encodes identity, not status.
function getAvatarColor(user?: { id?: string; email?: string; username?: string }): string {
  const str = user?.username || user?.email || user?.id || ''
  let hash = 0
  for (let i = 0; i < str.length; i++) {
    hash = str.charCodeAt(i) + ((hash << 5) - hash)
  }
  const colors = [
    'bg-chart-1/20 text-chart-1',
    'bg-chart-2/20 text-chart-2',
    'bg-chart-3/20 text-chart-3',
    'bg-chart-4/20 text-chart-4',
    'bg-chart-5/20 text-chart-5',
    'bg-chart-6/20 text-chart-6',
    'bg-chart-7/20 text-chart-7',
    'bg-chart-8/20 text-chart-8',
  ]
  return colors[Math.abs(hash) % colors.length]
}

function getActivityLevel(activity: number): { label: string; color: string; bgColor: string; barColor: string } {
  if (activity >= 80) return { label: 'High', color: 'text-success-fg', bgColor: 'bg-success-bg border-success-border', barColor: 'bg-success-solid' }
  if (activity >= 40) return { label: 'Medium', color: 'text-warning-fg', bgColor: 'bg-warning-bg border-warning-border', barColor: 'bg-warning-solid' }
  if (activity > 0) return { label: 'Low', color: 'text-warning-fg', bgColor: 'bg-warning-bg border-warning-border', barColor: 'bg-warning-solid' }
  return { label: 'Dead', color: 'text-muted-foreground', bgColor: 'bg-muted border-border', barColor: 'bg-muted-foreground/70' }
}

function getDurationColor(ms: number): string {
  if (ms >= 300000) return 'text-success-fg' // 5+ min - good long session
  if (ms >= 60000) return 'text-info-fg' // 1-5 min
  if (ms >= 10000) return 'text-warning-fg' // 10s-1m
  return 'text-muted-foreground' // very short
}

function ReplaysPage() {
  const navigate = useNavigate()
  const { timezone } = useTimezone()
  const [period, setPeriod] = useState<'24h' | '7d' | '30d' | '90d'>('7d')
  const [environment, setEnvironment] = useState('all')
  const [page, setPage] = useState(1)
  const [searchQuery, setSearchQuery] = useState('')
  const [facetFilters, setFacetFilters] = useState<FacetFilter[]>([])

  const { data: projects, isLoading: projectsLoading, error: projectsError } = useQuery({
    queryKey: ['projects'],
    queryFn: () => api.getProjects(),
  })

  const projectList = projects ?? []
  const includedServices = useMemo(
    () => facetValues(facetFilters, 'service', false),
    [facetFilters]
  )
  const excludedServices = useMemo(
    () => facetValues(facetFilters, 'service', true),
    [facetFilters]
  )
  const hasServiceFilters = includedServices.length > 0 || excludedServices.length > 0
  const serviceNames = useMemo(
    () => serviceNamesForQuery(projectList, includedServices, excludedServices),
    [projectList, includedServices, excludedServices]
  )
  const scopeKey = serviceScopeKey(serviceNames, hasServiceFilters)
  const hasReplayScope = projectList.length > 0 && (!hasServiceFilters || serviceNames.length > 0)
  const serviceScopeParams = useMemo(() => (
    serviceNames.length > 0 ? {services: [...serviceNames]} : {}
  ), [serviceNames])
  const railSections = useMemo(() => serviceRailSections(projectList), [projectList])
  const handleFacetFiltersChange = (nextFilters: FacetFilter[]) => {
    setFacetFilters(nextFilters)
    setPage(1)
  }
  const { data: billingUsage } = useQuery({
    queryKey: ['billing-usage'],
    queryFn: () => api.getBillingUsage(),
  })
  const retentionDays = billingUsage?.retentionDays ?? 30
  const availablePeriods = useMemo(() => {
    const options = [
      { value: '24h', label: 'Last 24 hours', minDays: 1 },
      { value: '7d', label: 'Last 7 days', minDays: 7 },
      { value: '30d', label: 'Last 30 days', minDays: 30 },
      { value: '90d', label: 'Last 90 days', minDays: 90 },
    ] as const
    const filtered = options.filter((option) => retentionDays >= option.minDays)
    return filtered.length > 0 ? filtered : [options[0]]
  }, [retentionDays])

  const effectivePeriod = (availablePeriods.some((option) => option.value === period)
    ? period
    : availablePeriods[availablePeriods.length - 1]?.value ?? '7d') as '24h' | '7d' | '30d' | '90d'

  const { data: replays = [], isLoading } = useQuery({
    queryKey: ['replays', 'organization', scopeKey, effectivePeriod, environment, page, serviceScopeParams],
    queryFn: () => api.getOrganizationReplays({
      page,
      limit: 25,
      period: effectivePeriod,
      environment: environment === 'all' ? undefined : environment,
      ...serviceScopeParams,
    }),
    enabled: hasReplayScope,
  })

  const stats = useMemo(() => {
    const totalErrors = replays.reduce((sum, r) => sum + r.errorCount, 0)
    const avgDuration = replays.length > 0
      ? replays.reduce((sum, r) => sum + r.durationMs, 0) / replays.length
      : 0
    const uniqueUsers = new Set(
      replays
        .map((r) => r.user?.email || r.user?.username || r.user?.id)
        .filter(Boolean)
    ).size
    return {
      total: replays.length,
      totalErrors,
      avgDuration,
      uniqueUsers,
    }
  }, [replays])

  const filteredReplays = useMemo(() => {
    if (!searchQuery) return replays
    const q = searchQuery.toLowerCase()
    return replays.filter((r) => {
      const userName = r.user?.email || r.user?.username || r.user?.id || ''
      const urls = r.urls?.join(' ') || ''
      const browser = [r.browserName, r.osName].filter(Boolean).join(' ')
      return (
        userName.toLowerCase().includes(q) ||
        urls.toLowerCase().includes(q) ||
        browser.toLowerCase().includes(q)
      )
    })
  }, [replays, searchQuery])

  if (projectsLoading) {
    return <div className="p-8 text-sm text-muted-foreground">Loading services...</div>
  }

  if (projectsError) {
    return (
      <div className="p-8 text-destructive">
        Failed to load services: {projectsError instanceof Error ? projectsError.message : 'Unknown error'}
      </div>
    )
  }

  if (projectList.length === 0) {
    return (
      <div className="min-h-screen p-6">
        <EmptyState
          icon={Video}
          title="No services yet"
          description="Create a service to start capturing session replays."
        />
      </div>
    )
  }

  return (
    <ExplorerShell
      title="Session Replays"
      icon={<Video className="h-4 w-4 text-muted-foreground" />}
      searchBar={
        <div className="relative">
          <Search className="absolute left-2.5 top-1/2 -translate-y-1/2 h-3.5 w-3.5 text-muted-foreground" />
          <Input
            placeholder="Search by user, URL, or browser..."
            value={searchQuery}
            onChange={(e) => setSearchQuery(e.target.value)}
            className="h-7 pl-8 text-xs"
          />
        </div>
      }
      actions={
        <>
          <Select value={effectivePeriod} onValueChange={(value) => { setPeriod(value as '24h' | '7d' | '30d' | '90d'); setPage(1) }}>
            <SelectTrigger className="w-[140px] h-7 text-xs">
              <SelectValue />
            </SelectTrigger>
            <SelectContent>
              {availablePeriods.map((option) => (
                <SelectItem key={option.value} value={option.value}>
                  {option.label}
                </SelectItem>
              ))}
            </SelectContent>
          </Select>
          <Select value={environment} onValueChange={(v) => { setEnvironment(v); setPage(1) }}>
            <SelectTrigger className="w-[140px] h-7 text-xs">
              <SelectValue placeholder="Environment" />
            </SelectTrigger>
            <SelectContent>
              <SelectItem value="all">All Environments</SelectItem>
              <SelectItem value="production">Production</SelectItem>
              <SelectItem value="staging">Staging</SelectItem>
              <SelectItem value="development">Development</SelectItem>
            </SelectContent>
          </Select>
        </>
      }
      rail={
        <FacetRail
          sections={railSections}
          facetFilters={facetFilters}
          onFacetFiltersChange={handleFacetFiltersChange}
          title="Replays"
        />
      }
    >
      <div className="space-y-3 p-3">
        {/* Stats Cards */}
        {replays.length > 0 && (
          <div className="grid grid-cols-2 md:grid-cols-4 gap-3">
            <StatCard label="Sessions" value={stats.total} icon={Play} tone="info" />
            <StatCard label="Errors" value={stats.totalErrors} icon={AlertCircle} tone="danger" />
            <StatCard label="Avg Duration" value={formatDuration(stats.avgDuration)} icon={Timer} tone="success" />
            <StatCard label="Unique Users" value={stats.uniqueUsers} icon={User} tone="accent" />
          </div>
        )}

        {/* Content */}
        {!hasReplayScope ? (
          <EmptyState
            icon={Play}
            title="No services match filters"
            description="Adjust the selected services to view session replays."
          />
        ) : isLoading ? (
          <div className="space-y-2">
            {Array.from({ length: 6 }).map((_, i) => (
              <div key={i} className="rounded-xl border border-border/60 bg-card p-4 animate-pulse">
                <div className="flex items-center gap-4">
                  <div className="h-9 w-9 rounded-full bg-muted" />
                  <div className="flex-1 space-y-2">
                    <div className="h-4 w-32 rounded bg-muted" />
                    <div className="h-3 w-48 rounded bg-muted" />
                  </div>
                  <div className="h-6 w-16 rounded bg-muted" />
                  <div className="h-3 w-20 rounded bg-muted" />
                </div>
              </div>
            ))}
          </div>
        ) : !replays.length ? (
          <EmptyState
            icon={Play}
            title="No replays yet"
            description="Session replays are recorded when you enable replay capture in a compatible SDK. Configure replays in your service setup to start capturing user sessions."
          />
        ) : filteredReplays.length === 0 ? (
          <EmptyState
            icon={Search}
            title="No replays match your search"
            description="Try adjusting your search query or changing the filters."
          />
        ) : (
          <TooltipProvider>
            <div className="space-y-2">
              {filteredReplays.map((replay) => {
                const activityLevel = getActivityLevel(replay.activity)
                const durationColor = getDurationColor(replay.durationMs)
                const displayName = replay.user?.email || replay.user?.username || replay.user?.id || 'Anonymous'
                const initials = getInitials(replay.user)
                const avatarColor = getAvatarColor(replay.user)
                const hasErrors = replay.errorCount > 0

                return (
                  <div
                    key={replay.replayId}
                    onClick={() => navigate({ to: '/replays/$replayId', params: { replayId: replay.replayId } })}
                    className={`cursor-pointer rounded-xl border bg-card hover:bg-accent/50 transition-all border-l-[3px] border-border/60 ${
                      hasErrors ? 'border-l-danger-solid' : 'border-l-primary/50'
                    }`}
                  >
                    <div className="flex items-center gap-3 p-3">
                      <Avatar className="h-8 w-8 shrink-0">
                        <AvatarFallback className={`text-xs font-semibold ${avatarColor}`}>
                          {initials}
                        </AvatarFallback>
                      </Avatar>

                      <div className="flex-1 min-w-0">
                        <div className="flex items-center gap-2 mb-1">
                          <span className="font-semibold text-sm truncate">{displayName}</span>
                          <Tooltip>
                            <TooltipTrigger asChild>
                              <span className="text-xs text-muted-foreground ml-auto shrink-0 flex items-center gap-1">
                                <Clock className="h-3 w-3" />
                                {formatRelativeTime(replay.startedAt)}
                              </span>
                            </TooltipTrigger>
                            <TooltipContent>{formatDate(replay.startedAt, timezone)}</TooltipContent>
                          </Tooltip>
                        </div>

                        <div className="flex flex-wrap items-center gap-2">
                          <Tooltip>
                            <TooltipTrigger asChild>
                              <Badge variant="outline" className={`text-xs font-medium gap-1 ${durationColor}`}>
                                <Timer className="h-3 w-3" />
                                {formatDuration(replay.durationMs)}
                              </Badge>
                            </TooltipTrigger>
                            <TooltipContent>Session duration</TooltipContent>
                          </Tooltip>

                          <Tooltip>
                            <TooltipTrigger asChild>
                              <Badge variant="outline" className={`text-xs font-medium gap-1.5 ${activityLevel.bgColor} ${activityLevel.color}`}>
                                <MousePointerClick className="h-3 w-3" />
                                <span>{activityLevel.label}</span>
                                <span className="h-1.5 w-8 rounded-full bg-current/20 overflow-hidden inline-flex">
                                  <span
                                    className={`h-full rounded-full ${activityLevel.barColor}`}
                                    style={{ width: `${Math.min(100, replay.activity)}%` }}
                                  />
                                </span>
                              </Badge>
                            </TooltipTrigger>
                            <TooltipContent>Activity: {replay.activity}%</TooltipContent>
                          </Tooltip>

                          {hasErrors && (
                            <Badge variant="danger" className="text-xs font-medium gap-1">
                              <AlertCircle className="h-3 w-3" />
                              {replay.errorCount} error{replay.errorCount === 1 ? '' : 's'}
                            </Badge>
                          )}

                          {replay.urls?.length > 0 && (
                            <Tooltip>
                              <TooltipTrigger asChild>
                                <Badge variant="outline" className="text-xs font-normal gap-1 max-w-[220px] truncate">
                                  <Globe className="h-3 w-3 shrink-0" />
                                  <span className="truncate">
                                    {replay.urls[0]?.replace(/^https?:\/\//, '') || '-'}
                                  </span>
                                  {replay.urls.length > 1 && (
                                    <span className="shrink-0 text-muted-foreground">+{replay.urls.length - 1}</span>
                                  )}
                                </Badge>
                              </TooltipTrigger>
                              <TooltipContent className="max-w-xs">
                                <div className="space-y-1">
                                  {replay.urls.map((url, i) => (
                                    <div key={i} className="text-xs truncate">{url}</div>
                                  ))}
                                </div>
                              </TooltipContent>
                            </Tooltip>
                          )}

                          {(replay.browserName || replay.osName) && (
                            <Badge variant="outline" className="text-xs font-normal gap-1 text-muted-foreground">
                              <Monitor className="h-3 w-3" />
                              {[replay.browserName, replay.osName].filter(Boolean).join(' / ')}
                            </Badge>
                          )}
                        </div>
                      </div>

                      <div className="shrink-0 opacity-0 group-hover:opacity-100 transition-opacity">
                        <div className="rounded-full bg-[hsl(var(--primary)/0.12)] p-2 ring-1 ring-[hsl(var(--primary)/0.3)]">
                          <Play className="h-4 w-4 text-primary" />
                        </div>
                      </div>
                    </div>
                  </div>
                )
              })}
            </div>
          </TooltipProvider>
        )}

        {/* Pagination */}
        {filteredReplays.length > 0 && (
          <div className="flex items-center justify-between">
            <p className="text-xs text-muted-foreground">
              Showing {filteredReplays.length} replay{filteredReplays.length === 1 ? '' : 's'}
              {searchQuery && ' matching your search'}
              {' '}&middot; Page {page}
            </p>
            <div className="flex items-center gap-2">
              <Button
                variant="outline"
                size="sm"
                onClick={() => setPage((p) => Math.max(1, p - 1))}
                disabled={page <= 1}
                className="h-8 gap-1"
              >
                <ChevronLeft className="h-4 w-4" />
                Previous
              </Button>
              <div className="flex items-center gap-1 px-2">
                <span className="text-sm font-medium">{page}</span>
              </div>
              <Button
                variant="outline"
                size="sm"
                onClick={() => setPage((p) => p + 1)}
                disabled={replays.length < 25}
                className="h-8 gap-1"
              >
                Next
                <ChevronRight className="h-4 w-4" />
              </Button>
            </div>
          </div>
        )}
      </div>
    </ExplorerShell>
  )
}
