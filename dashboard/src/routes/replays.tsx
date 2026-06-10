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
import type {Replay} from '@/lib/api'
import {cn, formatRelativeTime} from '@/lib/utils'
import {useTimezone} from '@/hooks/useTimezone'
import {formatDate as formatDateUtil, parseDate} from '@/lib/date-format'
import {type KeyboardEvent, useCallback, useMemo, useState} from 'react'
import {ExplorerShell} from '@/components/filters/ExplorerShell'
import {FacetRail} from '@/components/filters/FacetRail'
import {SearchFilterBar} from '@/components/filters/SearchFilterBar'
import {Badge} from '@/components/ui/badge'
import {EmptyState} from '@/components/ui/empty-state'
import {Avatar, AvatarFallback} from '@/components/ui/avatar'
import {Button} from '@/components/ui/button'
import {StatusDot} from '@/components/ui/status-dot'
import {
  Table,
  TableBody,
  TableCell,
  TableHead,
  TableHeader,
  TableRow,
} from '@/components/ui/table'
import {Select, SelectContent, SelectItem, SelectTrigger, SelectValue,} from '@/components/ui/select'
import {Tooltip, TooltipContent, TooltipProvider, TooltipTrigger,} from '@/components/ui/tooltip'
import {
  AlertCircle,
  ChevronLeft,
  ChevronRight,
  Monitor,
  Play,
  Search,
  Smartphone,
  Video,
} from 'lucide-react'
import {
  facetValues,
  serviceNamesForQuery,
  serviceRailSections,
  serviceScopeKey,
} from '@/lib/service-facet-scope'
import type {FacetFilter, FacetSchema} from '@/lib/filters/types'
import {
  browserOsLabel,
  deriveReplaySignals,
  formatReplayClock,
  getActivityLevel,
  isMobileOs,
  replayAvatarClass,
  replayDisplayName,
  replayEntryPath,
  replayExtraPageCount,
  replayInitials,
  replayStatusTone,
} from '@/lib/replays-view'

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

function formatDate(isoString: string, timezone: string) {
  if (!isoString) return 'N/A'
  const date = parseDate(isoString)
  if (isNaN(date.getTime())) return 'Invalid Date'
  return formatDateUtil(date, timezone)
}

type ReplayView = 'all' | 'errors' | 'mobile'

const VIEWS: ReadonlyArray<{ value: ReplayView; label: string }> = [
  { value: 'all', label: 'All sessions' },
  { value: 'errors', label: 'With errors' },
  { value: 'mobile', label: 'Mobile' },
]

function matchesView(replay: Replay, view: ReplayView): boolean {
  if (view === 'errors') return replay.errorCount > 0
  if (view === 'mobile') return isMobileOs(replay.osName)
  return true
}

// Facets this surface understands — drives the search bar's typeahead + chip routing.
const REPLAY_FACET_SCHEMA = [
  {key: 'service', label: 'Service', color: 'bg-chart-1', allowExclude: true},
  {
    key: 'environment',
    label: 'Environment',
    aliases: ['env'],
    color: 'bg-chart-3',
    singleSelect: true,
    suggestions: ['production', 'staging', 'development'],
  },
] satisfies FacetSchema

const REPLAY_ENVIRONMENTS = ['production', 'staging', 'development'] as const
const LOADING_ROW_KEYS = [
  'replay-loading-1',
  'replay-loading-2',
  'replay-loading-3',
  'replay-loading-4',
  'replay-loading-5',
  'replay-loading-6',
  'replay-loading-7',
  'replay-loading-8',
]

function handleReplayRowKeyDown(
  event: KeyboardEvent<HTMLTableRowElement>,
  replay: Replay,
  onOpen: (replay: Replay) => void
) {
  if (event.key !== 'Enter' && event.key !== ' ') return
  event.preventDefault()
  onOpen(replay)
}

function replayRowLabel(replay: Replay): string {
  return `Open replay ${replay.replayId.slice(0, 8)} for ${replayDisplayName(replay.user)}`
}

interface ReplayRowProps {
  readonly replay: Replay
  readonly timezone: string
  readonly onOpen: (replay: Replay) => void
}

function ReplayRow({ replay, timezone, onOpen }: ReplayRowProps) {
  const level = getActivityLevel(replay.activity)
  const signals = deriveReplaySignals(replay)
  const browserOs = browserOsLabel(replay)
  const mobile = isMobileOs(replay.osName)
  const extraPages = replayExtraPageCount(replay)

  return (
    <TableRow
      onClick={() => onOpen(replay)}
      onKeyDown={(event) => handleReplayRowKeyDown(event, replay, onOpen)}
      tabIndex={0}
      role="button"
      aria-label={replayRowLabel(replay)}
      className="cursor-pointer"
    >
      <TableCell className="pl-3 pr-0">
        <StatusDot tone={replayStatusTone(replay)} />
      </TableCell>
      <TableCell>
        <div className="flex items-center gap-2.5 min-w-0">
          <Avatar className="h-6 w-6 shrink-0">
            <AvatarFallback className={cn('text-2xs font-semibold', replayAvatarClass(replay.user))}>
              {replayInitials(replay.user)}
            </AvatarFallback>
          </Avatar>
          <div className="min-w-0 leading-tight">
            <div className="truncate text-xs font-medium">{replayDisplayName(replay.user)}</div>
            <div className="font-mono text-2xs text-muted-foreground/70">
              {replay.replayId.slice(0, 8)}
            </div>
          </div>
        </div>
      </TableCell>
      <TableCell className="text-right font-mono text-xs tabular-nums">
        {formatReplayClock(replay.durationMs)}
      </TableCell>
      <TableCell>
        <div className="flex items-center gap-2">
          <div className="h-1.5 w-16 overflow-hidden rounded-full bg-muted">
            <div
              className={cn('h-full rounded-full', level.barClass)}
              style={{ width: `${Math.min(100, Math.max(0, replay.activity))}%` }}
            />
          </div>
          <span className="w-6 text-right font-mono text-2xs tabular-nums text-muted-foreground">
            {replay.activity}
          </span>
        </div>
      </TableCell>
      <TableCell>
        {signals.length === 0 ? (
          <span className="text-muted-foreground/60">-</span>
        ) : (
          <div className="flex flex-wrap items-center gap-1">
            {signals.map((signal) => (
              <Badge key={signal.key} variant={signal.variant} className="gap-1 text-2xs">
                {signal.key === 'error' && <AlertCircle className="h-3 w-3" />}
                {signal.label}
              </Badge>
            ))}
          </div>
        )}
      </TableCell>
      <TableCell>
        <div className="flex items-baseline gap-1 min-w-0">
          <span className="truncate font-mono text-xs">{replayEntryPath(replay)}</span>
          {extraPages > 0 && (
            <span className="shrink-0 text-2xs text-muted-foreground">+{extraPages}</span>
          )}
        </div>
      </TableCell>
      <TableCell>
        {browserOs ? (
          <span className="inline-flex items-center gap-1.5 whitespace-nowrap text-xs text-muted-foreground">
            {mobile ? <Smartphone className="h-3.5 w-3.5" /> : <Monitor className="h-3.5 w-3.5" />}
            {browserOs}
          </span>
        ) : (
          <span className="text-muted-foreground/60">-</span>
        )}
      </TableCell>
      <TableCell className="text-right">
        <Tooltip>
          <TooltipTrigger asChild>
            <span className="whitespace-nowrap font-mono text-2xs text-muted-foreground">
              {formatRelativeTime(replay.startedAt)}
            </span>
          </TooltipTrigger>
          <TooltipContent>{formatDate(replay.startedAt, timezone)}</TooltipContent>
        </Tooltip>
      </TableCell>
    </TableRow>
  )
}

function ReplayTableSkeleton() {
  return (
    <div className="rounded-lg border border-border/60">
      {LOADING_ROW_KEYS.map((key) => (
        <div key={key} className="flex items-center gap-3 border-b border-border/40 p-2.5 last:border-0">
          <div className="h-6 w-6 animate-pulse rounded-full bg-muted" />
          <div className="h-3 w-40 animate-pulse rounded bg-muted" />
          <div className="ml-auto h-3 w-24 animate-pulse rounded bg-muted" />
        </div>
      ))}
    </div>
  )
}

interface ReplayContentProps {
  readonly hasReplayScope: boolean
  readonly isLoading: boolean
  readonly replays: Replay[]
  readonly filteredReplays: Replay[]
  readonly visibleReplays: Replay[]
  readonly timezone: string
  readonly onOpenReplay: (replay: Replay) => void
}

function ReplayContent({
  hasReplayScope,
  isLoading,
  replays,
  filteredReplays,
  visibleReplays,
  timezone,
  onOpenReplay,
}: ReplayContentProps) {
  if (!hasReplayScope) {
    return (
      <EmptyState
        icon={Play}
        title="No services match filters"
        description="Adjust the selected services to view session replays."
      />
    )
  }

  if (isLoading) return <ReplayTableSkeleton />

  if (!replays.length) {
    return (
      <EmptyState
        icon={Play}
        title="No replays yet"
        description="Session replays are recorded when you enable replay capture in a compatible SDK. Configure replays in your service setup to start capturing user sessions."
      />
    )
  }

  if (filteredReplays.length === 0) {
    return (
      <EmptyState
        icon={Search}
        title="No replays match your search"
        description="Try adjusting your search query or changing the filters."
      />
    )
  }

  if (visibleReplays.length === 0) {
    return (
      <EmptyState
        icon={Search}
        title="No replays in this view"
        description="No sessions on this page match the selected view. Try another view or page."
      />
    )
  }

  return (
    <TooltipProvider>
      <div className="rounded-lg border border-border/60 overflow-hidden">
        <Table>
          <TableHeader>
            <TableRow className="hover:bg-transparent">
              <TableHead className="w-7" />
              <TableHead className="text-2xs uppercase tracking-wide">User</TableHead>
              <TableHead className="text-2xs uppercase tracking-wide text-right">Duration</TableHead>
              <TableHead className="text-2xs uppercase tracking-wide w-[140px]">Activity</TableHead>
              <TableHead className="text-2xs uppercase tracking-wide">Signals</TableHead>
              <TableHead className="text-2xs uppercase tracking-wide">Entry page</TableHead>
              <TableHead className="text-2xs uppercase tracking-wide">Browser · OS</TableHead>
              <TableHead className="text-2xs uppercase tracking-wide text-right">Started</TableHead>
            </TableRow>
          </TableHeader>
          <TableBody>
            {visibleReplays.map((replay) => (
              <ReplayRow
                key={replay.replayId}
                replay={replay}
                timezone={timezone}
                onOpen={onOpenReplay}
              />
            ))}
          </TableBody>
        </Table>
      </div>
    </TooltipProvider>
  )
}

function ReplaysPage() {
  const navigate = useNavigate()
  const { timezone } = useTimezone()
  const [period, setPeriod] = useState<'24h' | '7d' | '30d' | '90d'>('7d')
  const [page, setPage] = useState(1)
  const [searchQuery, setSearchQuery] = useState('')
  const [facetFilters, setFacetFilters] = useState<FacetFilter[]>([])
  const [view, setView] = useState<ReplayView>('all')

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
  // Environment is a single-select facet now, replacing the old header dropdown.
  const includedEnvironments = useMemo(
    () => facetValues(facetFilters, 'environment', false),
    [facetFilters]
  )
  const environment = includedEnvironments[0]
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
  const railSections = useMemo(
    () => [
      ...serviceRailSections(projectList),
      {
        key: 'environment',
        label: 'Environment',
        color: 'bg-chart-3',
        singleSelect: true,
        allowExclude: false,
        options: REPLAY_ENVIRONMENTS.map((value) => ({value})),
      },
    ],
    [projectList]
  )
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
    queryKey: ['replays', 'organization', scopeKey, effectivePeriod, environment ?? 'all-env', page, serviceScopeParams],
    queryFn: () => api.getOrganizationReplays({
      page,
      limit: 25,
      period: effectivePeriod,
      environment,
      ...serviceScopeParams,
    }),
    enabled: hasReplayScope,
  })

  // Client-side text search (user / url / browser) over the loaded page.
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

  const visibleReplays = useMemo(
    () => filteredReplays.filter((r) => matchesView(r, view)),
    [filteredReplays, view]
  )
  const openReplay = useCallback((replay: Replay) => {
    navigate({ to: '/replays/$replayId', params: { replayId: replay.replayId } })
  }, [navigate])

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
        <SearchFilterBar
          query={searchQuery}
          onQueryChange={(value) => { setSearchQuery(value); setPage(1) }}
          facetFilters={facetFilters}
          onFacetFiltersChange={handleFacetFiltersChange}
          schema={REPLAY_FACET_SCHEMA}
          placeholder="Search user, URL, browser — or filter service:, env:"
        />
      }
      actions={
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
      <div className="flex flex-col gap-2 p-3">
        {/* Toolbar: saved-view tabs + result count */}
        <div className="flex items-center gap-2">
          <div className="flex items-center gap-1">
            {VIEWS.map((v) => (
              <button
                key={v.value}
                type="button"
                onClick={() => { setView(v.value); setPage(1) }}
                className={cn(
                  'h-7 rounded-md px-2.5 text-xs font-medium transition-colors',
                  view === v.value
                    ? 'bg-accent text-accent-foreground'
                    : 'text-muted-foreground hover:bg-muted/60 hover:text-foreground'
                )}
                aria-pressed={view === v.value}
              >
                {v.label}
              </button>
            ))}
          </div>
          <div className="ml-auto">
            <Badge variant="neutral" className="text-xs tabular-nums">
              {visibleReplays.length} session{visibleReplays.length === 1 ? '' : 's'}
            </Badge>
          </div>
        </div>

        <ReplayContent
          hasReplayScope={hasReplayScope}
          isLoading={isLoading}
          replays={replays}
          filteredReplays={filteredReplays}
          visibleReplays={visibleReplays}
          timezone={timezone}
          onOpenReplay={openReplay}
        />

        {/* Pagination */}
        {visibleReplays.length > 0 && (
          <div className="flex items-center justify-between">
            <p className="text-xs text-muted-foreground">
              Showing {visibleReplays.length} replay{visibleReplays.length === 1 ? '' : 's'}
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
