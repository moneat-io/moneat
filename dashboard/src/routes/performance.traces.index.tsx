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
import {useQuery} from '@tanstack/react-query'
import {useEffect, useMemo, useRef, useState, type ReactNode, type RefObject} from 'react'
import {
  CartesianGrid,
  Line,
  LineChart,
  ResponsiveContainer,
  Tooltip,
  XAxis,
  YAxis,
} from 'recharts'
import {
  AlertTriangle,
  ArrowUpRight,
  CalendarDays,
  CheckCircle2,
  ChevronLeft,
  ChevronRight,
  Clock,
  ExternalLink,
  Gauge,
  Hash,
  Info,
  Layers,
  RefreshCw,
  Search,
  Server,
  X,
} from 'lucide-react'
import {
  api,
  type ApmOverviewResponse,
  type ApmResourceStatsItem,
  type ApmStatusFilter,
  type ApmTimeRange,
  type ApmTraceListItem,
} from '@/lib/api'
import {SourceBadge} from '@/components/apm/SourceBadge'
import {Badge} from '@/components/ui/badge'
import {Button} from '@/components/ui/button'
import {Card, CardContent, CardHeader, CardTitle} from '@/components/ui/card'
import {Input} from '@/components/ui/input'
import {Select, SelectContent, SelectItem, SelectTrigger, SelectValue} from '@/components/ui/select'
import {Tabs, TabsContent, TabsList, TabsTrigger} from '@/components/ui/tabs'
import {Table, TableBody, TableCell, TableHead, TableHeader, TableRow} from '@/components/ui/table'
import {ExplorerShell} from '@/components/filters/ExplorerShell'
import {FacetRail} from '@/components/filters/FacetRail'
import {SearchFilterBar} from '@/components/filters/SearchFilterBar'
import type {FacetFilter, FacetRailSection, FacetSchema} from '@/lib/filters/types'
import {cn} from '@/lib/utils'

export const Route = createFileRoute('/performance/traces/')({
  component: PerformanceTracesPage,
})

const TRACE_PAGE_SIZE = 25
const RESOURCE_PAGE_SIZE = 25
const DURATION_BAR_MAX_NS = 1_000_000_000
const SEARCH_DEBOUNCE_MS = 300
const KPI_CARD_COUNT = 6
const SKELETON_TABLE_ROWS = 4

const TIME_RANGE_OPTIONS: Array<{value: ApmTimeRange; label: string}> = [
  {value: '1h', label: 'Last hour'},
  {value: '6h', label: 'Last 6h'},
  {value: '24h', label: 'Last 24h'},
  {value: '7d', label: 'Last 7d'},
  {value: '30d', label: 'Last 30d'},
  {value: '90d', label: 'Last 90d'},
]

const REFRESH_OPTIONS = [
  {value: 'off', label: 'Auto off', ms: false},
  {value: '15s', label: '15s', ms: 15_000},
  {value: '60s', label: '60s', ms: 60_000},
] as const

const SERVICE_DOTS = [
  'bg-chart-1',
  'bg-chart-2',
  'bg-chart-3',
  'bg-chart-4',
  'bg-chart-6',
  'bg-chart-7',
]

const TRACE_FACET_SCHEMA = [
  {
    key: 'service',
    label: 'Service',
    aliases: ['svc'],
    color: 'bg-chart-1',
    allowExclude: false,
  },
  {
    key: 'operation',
    label: 'Operation',
    aliases: ['resource', 'op'],
    color: 'bg-chart-2',
    singleSelect: true,
    allowExclude: false,
  },
  {
    key: 'env',
    label: 'Environment',
    aliases: ['environment'],
    color: 'bg-chart-3',
    singleSelect: true,
    allowExclude: false,
  },
  {
    key: 'status',
    label: 'Status',
    color: 'bg-chart-4',
    singleSelect: true,
    allowExclude: false,
    suggestions: ['error', 'ok'],
  },
  {
    key: 'source',
    label: 'Source',
    color: 'bg-chart-6',
    singleSelect: true,
    allowExclude: false,
  },
] satisfies FacetSchema

type RefreshValue = (typeof REFRESH_OPTIONS)[number]['value']

interface OverviewParams {
  timeRange: ApmTimeRange
  services?: string[]
  source?: string
  status?: ApmStatusFilter
  env?: string
  operation?: string
  search?: string
}

interface TraceListParams extends OverviewParams {
  search?: string
  limit: number
  offset: number
}

interface ResourceListParams extends OverviewParams {
  search?: string
  limit: number
  offset: number
}

interface SkeletonCellSpec {
  cellClassName?: string
  blockClassName: string
  leadingDot?: boolean
}

const SERVICE_HEALTH_SKELETON_CELLS: SkeletonCellSpec[] = [
  {cellClassName: 'pl-4', blockClassName: 'h-4 w-28', leadingDot: true},
  {cellClassName: 'text-right', blockClassName: 'ml-auto h-4 w-12'},
  {cellClassName: 'text-right', blockClassName: 'ml-auto h-4 w-20'},
  {cellClassName: 'pr-4 text-right', blockClassName: 'ml-auto h-4 w-24'},
]

const RESOURCE_HOTSPOT_SKELETON_CELLS: SkeletonCellSpec[] = [
  {cellClassName: 'pl-4', blockClassName: 'h-4 w-36'},
  {blockClassName: 'h-4 w-28'},
  {cellClassName: 'text-right', blockClassName: 'ml-auto h-4 w-20'},
  {cellClassName: 'pr-4 text-right', blockClassName: 'ml-auto h-4 w-12'},
]

const ERROR_GROUP_SKELETON_CELLS: SkeletonCellSpec[] = [
  {cellClassName: 'pl-4', blockClassName: 'h-4 w-36'},
  {blockClassName: 'h-4 w-28'},
  {cellClassName: 'text-right', blockClassName: 'ml-auto h-4 w-12'},
  {cellClassName: 'pr-4 text-right', blockClassName: 'ml-auto h-4 w-14'},
]

const ALL_ERRORING_RESOURCE_SKELETON_CELLS: SkeletonCellSpec[] = [
  {cellClassName: 'pl-4', blockClassName: 'h-4 w-40'},
  {blockClassName: 'h-4 w-28'},
  {cellClassName: 'text-right', blockClassName: 'ml-auto h-4 w-12'},
  {cellClassName: 'text-right', blockClassName: 'ml-auto h-4 w-12'},
  {cellClassName: 'text-right', blockClassName: 'ml-auto h-4 w-20'},
  {cellClassName: 'pr-4 text-right', blockClassName: 'ml-auto h-4 w-24'},
]

const RECENT_TRACE_SKELETON_CELLS: SkeletonCellSpec[] = [
  {cellClassName: 'pl-4', blockClassName: 'h-4 w-16'},
  {blockClassName: 'h-4 w-28', leadingDot: true},
  {blockClassName: 'h-5 w-14'},
  {blockClassName: 'h-4 w-40'},
  {cellClassName: 'text-right', blockClassName: 'ml-auto h-4 w-8'},
  {cellClassName: 'text-right', blockClassName: 'ml-auto h-4 w-24'},
  {cellClassName: 'text-center', blockClassName: 'mx-auto h-5 w-14'},
  {cellClassName: 'pr-4', blockClassName: 'h-4 w-36'},
]

function PerformanceTracesPage() {
  const [timeRange, setTimeRange] = useState<ApmTimeRange>('24h')
  const [refresh, setRefresh] = useState<RefreshValue>('15s')
  const [facetFilters, setFacetFilters] = useState<FacetFilter[]>([])
  const [search, setSearch] = useState('')
  const [page, setPage] = useState(0)
  const [showErroringResources, setShowErroringResources] = useState(false)
  const [erroringResourcesScrollKey, setErroringResourcesScrollKey] = useState(0)
  const erroringResourcesRef = useRef<HTMLDivElement | null>(null)

  const queryParams = useMemo(
    () => toOverviewParams(timeRange, facetFilters),
    [timeRange, facetFilters],
  )
  const debouncedSearch = useDebouncedValue(search.trim(), SEARCH_DEBOUNCE_MS)
  const traceQueryParams = useMemo<TraceListParams>(
    () => ({
      ...queryParams,
      search: debouncedSearch === '' ? undefined : debouncedSearch,
      limit: TRACE_PAGE_SIZE,
      offset: page * TRACE_PAGE_SIZE,
    }),
    [debouncedSearch, page, queryParams],
  )
  const overviewParams = useMemo<OverviewParams>(
    () => ({
      ...queryParams,
      search: debouncedSearch === '' ? undefined : debouncedSearch,
    }),
    [debouncedSearch, queryParams],
  )
  const refreshMs = REFRESH_OPTIONS.find((option) => option.value === refresh)?.ms ?? false

  const overviewQuery = useQuery({
    queryKey: ['apm-overview', overviewParams],
    queryFn: () => api.getApmOverview(overviewParams),
    refetchInterval: refreshMs,
  })

  const tracesQuery = useQuery({
    queryKey: ['apm-traces', traceQueryParams],
    queryFn: () => api.getApmTraces(traceQueryParams),
    refetchInterval: refreshMs,
  })

  const overview = overviewQuery.data ?? emptyOverview
  const traces = tracesQuery.data?.traces ?? []
  const totalTraces = tracesQuery.data?.totalCount ?? 0
  const totalPages = Math.max(1, Math.ceil(totalTraces / TRACE_PAGE_SIZE))
  const railSections = useMemo(() => buildTraceFacetSections(overview), [overview])

  useEffect(() => {
    if (page >= totalPages) {
      globalThis.queueMicrotask(() => {
        setPage((currentPage) => {
          if (currentPage < totalPages) return currentPage
          return Math.max(0, totalPages - 1)
        })
      })
    }
  }, [page, totalPages])

  useEffect(() => {
    const panel = erroringResourcesRef.current
    if (!showErroringResources || typeof panel?.scrollIntoView !== 'function') return
    panel.scrollIntoView({block: 'start', behavior: 'smooth'})
  }, [erroringResourcesScrollKey, showErroringResources])

  const updateFacetFilters = (filters: FacetFilter[]) => {
    setFacetFilters(filters)
    setPage(0)
  }

  const updateSearch = (value: string) => {
    setSearch(value)
    setPage(0)
  }

  const viewAllErroringResources = () => {
    setShowErroringResources(true)
    setErroringResourcesScrollKey((currentKey) => currentKey + 1)
  }

  return (
    <ExplorerShell
      className="min-h-[calc(100vh-4rem)]"
      title="Traces"
      icon={<Gauge className="h-4 w-4 text-muted-foreground" />}
      searchBar={(
        <SearchFilterBar
          query={search}
          onQueryChange={updateSearch}
          facetFilters={facetFilters}
          onFacetFiltersChange={updateFacetFilters}
          schema={TRACE_FACET_SCHEMA}
          placeholder="Search trace ID, resource, or service"
          trailing={(
            <Select
              value={timeRange}
              onValueChange={(value) => {
                setTimeRange(value as ApmTimeRange)
                setPage(0)
              }}
            >
              <SelectTrigger className="h-[30px] w-[154px] gap-2">
                <CalendarDays className="h-3.5 w-3.5 text-muted-foreground" />
                <SelectValue />
              </SelectTrigger>
              <SelectContent>
                {TIME_RANGE_OPTIONS.map((option) => (
                  <SelectItem key={option.value} value={option.value}>
                    {option.label}
                  </SelectItem>
                ))}
              </SelectContent>
            </Select>
          )}
        />
      )}
      actions={(
        <>
          <Button
            variant="outline"
            size="sm"
            className="h-[30px] gap-2"
            onClick={() => {
              void overviewQuery.refetch()
              void tracesQuery.refetch()
            }}
          >
            <RefreshCw className="h-3.5 w-3.5" />
            Refresh
          </Button>
          <Select value={refresh} onValueChange={(value) => setRefresh(value as RefreshValue)}>
            <SelectTrigger className="h-[30px] w-[92px]">
              <SelectValue />
            </SelectTrigger>
            <SelectContent>
              {REFRESH_OPTIONS.map((option) => (
                <SelectItem key={option.value} value={option.value}>
                  {option.label}
                </SelectItem>
              ))}
            </SelectContent>
          </Select>
        </>
      )}
      rail={(
        <FacetRail
          sections={railSections}
          facetFilters={facetFilters}
          onFacetFiltersChange={updateFacetFilters}
          title="Trace facets"
        />
      )}
      toolbar={<span className="text-xs text-muted-foreground">{formatCount(totalTraces)} traces</span>}
    >
      <div className="min-w-0 px-4 py-4 sm:px-6 lg:px-8">
        <div className="mb-4 border-b pb-4">
          <div className="min-w-0">
            <h1 className="text-xl font-semibold tracking-tight">Health overview</h1>
            <p className="mt-1 max-w-3xl text-sm text-muted-foreground">
              Analyze trace health, latency, and errors across services. Telemetry sources are metadata only.
            </p>
          </div>
        </div>

        <div className="mt-4 grid gap-3 md:grid-cols-2 xl:grid-cols-3 2xl:grid-cols-6">
          {overviewQuery.isLoading ? (
            Array.from({length: KPI_CARD_COUNT}, (_, index) => (
              <KpiCardSkeleton key={`kpi-card-skeleton-${index}`} />
            ))
          ) : (
            <>
              <KpiCard
                title="Total Traces"
                value={formatCount(overview.stats.totalTraces)}
                previous={overview.stats.previous.totalTraces}
                current={overview.stats.totalTraces}
                icon={<Layers className="h-4 w-4" />}
              />
              <KpiCard
                title="Error Rate"
                value={formatPercent(overview.stats.errorRate)}
                previous={overview.stats.previous.errorRate}
                current={overview.stats.errorRate}
                icon={<AlertTriangle className="h-4 w-4" />}
                inverted
              />
              <KpiCard
                title="p50 Latency"
                value={formatDuration(overview.stats.p50DurationNs)}
                previous={overview.stats.previous.p50DurationNs}
                current={overview.stats.p50DurationNs}
                icon={<Clock className="h-4 w-4" />}
                series={overview.latencySeries.map((point) => point.p50DurationNs)}
                inverted
              />
              <KpiCard
                title="p95 Latency"
                value={formatDuration(overview.stats.p95DurationNs)}
                previous={overview.stats.previous.p95DurationNs}
                current={overview.stats.p95DurationNs}
                icon={<Gauge className="h-4 w-4" />}
                series={overview.latencySeries.map((point) => point.p95DurationNs)}
                inverted
              />
              <KpiCard
                title="p99 Latency"
                value={formatDuration(overview.stats.p99DurationNs)}
                previous={overview.stats.previous.p99DurationNs}
                current={overview.stats.p99DurationNs}
                icon={<Gauge className="h-4 w-4" />}
                series={overview.latencySeries.map((point) => point.p99DurationNs)}
                inverted
              />
              <KpiCard
                title="Avg Spans / Trace"
                value={overview.stats.avgSpansPerTrace.toFixed(1)}
                previous={overview.stats.previous.avgSpansPerTrace}
                current={overview.stats.avgSpansPerTrace}
                icon={<Hash className="h-4 w-4" />}
                inverted
              />
            </>
          )}
        </div>

        <div className="mt-4 grid min-w-0 gap-3 xl:grid-cols-2 2xl:grid-cols-[1fr_1fr_1fr]">
          <ServiceHealthPanel overview={overview} isLoading={overviewQuery.isLoading} />
          <LatencyPanel overview={overview} isLoading={overviewQuery.isLoading} />
          <ErrorsResourcesPanel
            overview={overview}
            isLoading={overviewQuery.isLoading}
            isShowingAllErroringResources={showErroringResources}
            onViewAllErroringResources={viewAllErroringResources}
          />
        </div>

        {showErroringResources && (
          <AllErroringResourcesPanel
            panelRef={erroringResourcesRef}
            queryParams={queryParams}
            refreshMs={refreshMs}
            onClose={() => setShowErroringResources(false)}
          />
        )}

        <RecentTracesPanel
          traces={traces}
          totalTraces={totalTraces}
          page={page}
          totalPages={totalPages}
          isLoading={tracesQuery.isLoading}
          onPageChange={setPage}
        />
      </div>
    </ExplorerShell>
  )
}

function KpiCard({
  title,
  value,
  current,
  previous,
  icon,
  series = [],
  inverted = false,
}: {
  title: string
  value: string
  current: number
  previous: number
  icon: ReactNode
  series?: number[]
  inverted?: boolean
}) {
  return (
    <Card className="min-w-0 overflow-hidden rounded-lg">
      <CardContent className="flex min-h-[96px] items-center justify-between gap-3 p-4">
        <div className="min-w-0">
          <div className="mb-2 flex items-center gap-1.5 text-xs font-medium text-muted-foreground">
            {icon}
            <span>{title}</span>
            <Info className="h-3 w-3" />
          </div>
          <div className="text-2xl font-semibold tracking-tight tabular-nums">{value}</div>
          <Delta current={current} previous={previous} inverted={inverted} />
        </div>
        {series.length > 1 && (
          <MiniSparkline
            values={series}
            className="h-10 w-20 text-primary"
          />
        )}
      </CardContent>
    </Card>
  )
}

function KpiCardSkeleton() {
  return (
    <Card
      className="min-w-0 overflow-hidden rounded-lg"
      aria-busy="true"
      data-testid="performance-kpi-skeleton"
    >
      <CardContent className="flex min-h-[96px] items-center justify-between gap-3 p-4">
        <div className="min-w-0 flex-1 space-y-3">
          <div className="flex items-center gap-1.5">
            <SkeletonBlock className="h-4 w-4 rounded" />
            <SkeletonBlock className="h-3 w-24" />
            <SkeletonBlock className="h-3 w-3 rounded-full" />
          </div>
          <SkeletonBlock className="h-7 w-20" />
          <SkeletonBlock className="h-3 w-28" />
        </div>
        <SkeletonBlock className="h-10 w-20" />
      </CardContent>
    </Card>
  )
}

function ServiceHealthPanel({
  overview,
  isLoading,
}: {
  overview: ApmOverviewResponse
  isLoading: boolean
}) {
  return (
    <Card className="min-w-0 overflow-hidden rounded-lg">
      <CardHeader className="px-4 py-3">
        <CardTitle className="flex items-center gap-2 text-sm">
          <Server className="h-4 w-4" />
          Service health
        </CardTitle>
      </CardHeader>
      <CardContent className="px-0 pb-0">
        <div className="overflow-x-auto">
          <Table>
            <TableHeader>
              <TableRow className="hover:bg-transparent">
                <TableHead className="min-w-[170px] pl-4">Service</TableHead>
                <TableHead className="text-right">Traces</TableHead>
                <TableHead className="text-right">Error rate</TableHead>
                <TableHead className="min-w-[132px] text-right pr-4">p95 Latency</TableHead>
              </TableRow>
            </TableHeader>
            <TableBody>
              {isLoading ? (
                <SkeletonTableRows rowKey="service-health" cells={SERVICE_HEALTH_SKELETON_CELLS} />
              ) : overview.serviceHealth.length === 0 ? (
                <EmptyTableRow colSpan={4} label="No service health data" />
              ) : overview.serviceHealth.map((service, index) => (
                <TableRow key={`${service.service}-${service.source}-${index}`}>
                  <TableCell className="pl-4">
                    <div className="flex min-w-0 items-center gap-2">
                      <span className={cn('h-2 w-2 rounded-full', serviceDot(index))} />
                      <span className="truncate font-medium">{service.service}</span>
                    </div>
                  </TableCell>
                  <TableCell className="text-right tabular-nums">{formatCount(service.traceCount)}</TableCell>
                  <TableCell className="text-right">
                    <ErrorMeter rate={service.errorRate} />
                  </TableCell>
                  <TableCell className="pr-4">
                    <DurationMeter value={service.p95DurationNs} />
                  </TableCell>
                </TableRow>
              ))}
            </TableBody>
          </Table>
        </div>
        <Link
          to="/monitoring/service-map"
          className="flex items-center gap-1 border-t px-4 py-3 text-sm font-medium text-primary hover:underline"
        >
          Open service map
          <ArrowUpRight className="h-3.5 w-3.5" />
        </Link>
      </CardContent>
    </Card>
  )
}

function LatencyPanel({
  overview,
  isLoading,
}: {
  overview: ApmOverviewResponse
  isLoading: boolean
}) {
  const chartData = overview.latencySeries.map((point) => ({
    time: formatShortTime(point.timestamp),
    p50: nsToMs(point.p50DurationNs),
    p95: nsToMs(point.p95DurationNs),
    p99: nsToMs(point.p99DurationNs),
  }))

  return (
    <Card className="min-w-0 rounded-lg">
      <CardHeader className="px-4 py-3">
        <CardTitle className="flex items-center gap-2 text-sm">
          <Gauge className="h-4 w-4" />
          Latency distribution (ms)
        </CardTitle>
        <div className="flex items-center gap-4 text-xs text-muted-foreground">
          <LegendDot className="bg-chart-1" label="p50" />
          <LegendDot className="bg-chart-2" label="p95" />
          <LegendDot className="bg-chart-3" label="p99" />
        </div>
      </CardHeader>
      <CardContent className="h-[242px] px-3 pb-3 pt-0">
        {isLoading ? (
          <LatencyChartSkeleton />
        ) : chartData.length === 0 ? (
          <div className="flex h-full items-center justify-center text-sm text-muted-foreground">
            No latency samples in this window.
          </div>
        ) : (
          <ResponsiveContainer width="100%" height="100%" minWidth={0}>
            <LineChart data={chartData} margin={{left: 0, right: 8, top: 8, bottom: 0}}>
              <CartesianGrid strokeDasharray="3 3" vertical={false} stroke="hsl(var(--border))" />
              <XAxis dataKey="time" tick={{fontSize: 11}} axisLine={false} tickLine={false} />
              <YAxis tick={{fontSize: 11}} axisLine={false} tickLine={false} width={42} />
              <Tooltip
                formatter={(metricValue) => [
                  `${typeof metricValue === 'number' ? metricValue.toFixed(1) : metricValue}ms`,
                  '',
                ]}
                labelClassName="text-xs"
                contentStyle={{fontSize: 12, borderRadius: 8}}
              />
              <Line type="monotone" dataKey="p50" stroke="hsl(var(--chart-1))" strokeWidth={2} dot={false} />
              <Line type="monotone" dataKey="p95" stroke="hsl(var(--chart-2))" strokeWidth={2} dot={false} />
              <Line type="monotone" dataKey="p99" stroke="hsl(var(--chart-3))" strokeWidth={2} dot={false} />
            </LineChart>
          </ResponsiveContainer>
        )}
      </CardContent>
    </Card>
  )
}

function ErrorsResourcesPanel({
  overview,
  isLoading,
  isShowingAllErroringResources,
  onViewAllErroringResources,
}: {
  overview: ApmOverviewResponse
  isLoading: boolean
  isShowingAllErroringResources: boolean
  onViewAllErroringResources: () => void
}) {
  return (
    <Card className="min-w-0 overflow-hidden rounded-lg">
      <CardHeader className="px-4 py-3">
        <CardTitle className="flex items-center gap-2 text-sm">
          <AlertTriangle className="h-4 w-4" />
          Errors & top resources
        </CardTitle>
      </CardHeader>
      <CardContent className="px-0 pb-0 pt-0">
        <Tabs defaultValue="error-rate">
          <TabsList className="mx-4 h-9 rounded-none bg-transparent p-0">
            <TabsTrigger
              value="error-rate"
              className="rounded-none border-b-2 border-transparent px-3 data-[state=active]:border-primary"
            >
              By error rate
            </TabsTrigger>
            <TabsTrigger
              value="error-count"
              className="rounded-none border-b-2 border-transparent px-3 data-[state=active]:border-primary"
            >
              By error count
            </TabsTrigger>
          </TabsList>
          <TabsContent value="error-rate" className="m-0">
            <ResourceHotspotTable resources={overview.resourceHotspots} mode="rate" isLoading={isLoading} />
          </TabsContent>
          <TabsContent value="error-count" className="m-0">
            <ErrorGroupTable errors={overview.errors} isLoading={isLoading} />
          </TabsContent>
        </Tabs>
        <button
          type="button"
          onClick={onViewAllErroringResources}
          aria-controls="all-erroring-resources-panel"
          aria-expanded={isShowingAllErroringResources}
          className={cn(
            'flex w-full cursor-pointer items-center gap-1 border-t px-4 py-3 text-left text-sm',
            'font-medium text-primary hover:underline',
          )}
        >
          View all erroring resources
          <ArrowUpRight className="h-3.5 w-3.5" />
        </button>
      </CardContent>
    </Card>
  )
}

function AllErroringResourcesPanel({
  panelRef,
  queryParams,
  refreshMs,
  onClose,
}: {
  panelRef: RefObject<HTMLDivElement | null>
  queryParams: OverviewParams
  refreshMs: number | false
  onClose: () => void
}) {
  const [pageState, setPageState] = useState({key: '', page: 0})
  const [resourceSearch, setResourceSearch] = useState('')
  const debouncedSearch = useDebouncedValue(resourceSearch.trim(), SEARCH_DEBOUNCE_MS)
  const resourceFilterKey = [
    queryParams.timeRange,
    queryParams.services?.join(',') ?? '',
    queryParams.source ?? '',
    queryParams.env ?? '',
    queryParams.operation ?? '',
    debouncedSearch,
  ].join('\u001f')
  const page = pageState.key === resourceFilterKey ? pageState.page : 0

  const resourceParams = useMemo<ResourceListParams>(
    () => ({
      ...queryParams,
      status: 'error',
      search: debouncedSearch === '' ? undefined : debouncedSearch,
      limit: RESOURCE_PAGE_SIZE,
      offset: page * RESOURCE_PAGE_SIZE,
    }),
    [debouncedSearch, page, queryParams],
  )

  const resourcesQuery = useQuery({
    queryKey: ['apm-erroring-resources', resourceParams],
    queryFn: () => api.getApmResourceStats(resourceParams),
    refetchInterval: refreshMs,
  })

  const resources = resourcesQuery.data?.resources ?? []
  const totalResources = resourcesQuery.data?.totalCount ?? 0
  const totalPages = Math.max(1, Math.ceil(totalResources / RESOURCE_PAGE_SIZE))

  useEffect(() => {
    if (page >= totalPages) {
      globalThis.queueMicrotask(() => {
        setPageState((currentState) => {
          const currentPage = currentState.key === resourceFilterKey ? currentState.page : 0
          if (currentPage < totalPages) return currentState
          return {key: resourceFilterKey, page: Math.max(0, totalPages - 1)}
        })
      })
    }
  }, [page, resourceFilterKey, totalPages])

  const updateResourceSearch = (value: string) => {
    setResourceSearch(value)
    setPageState({key: resourceFilterKey, page: 0})
  }

  const updateResourcePage = (nextPage: (currentPage: number) => number) => {
    setPageState((currentState) => {
      const currentPage = currentState.key === resourceFilterKey ? currentState.page : 0
      return {key: resourceFilterKey, page: nextPage(currentPage)}
    })
  }

  return (
    <Card
      ref={panelRef}
      id="all-erroring-resources-panel"
      data-testid="all-erroring-resources-panel"
      className="mt-4 min-w-0 overflow-hidden rounded-lg"
    >
      <CardHeader className="flex flex-col gap-3 px-4 py-3 lg:flex-row lg:items-center lg:justify-between">
        <div>
          <CardTitle className="flex items-center gap-2 text-sm">
            <AlertTriangle className="h-4 w-4" />
            All erroring resources
          </CardTitle>
          <p className="mt-1 text-xs text-muted-foreground">
            Resources with errored traces in the selected time range and filters.
          </p>
        </div>
        <div className="flex flex-col gap-2 sm:flex-row sm:items-center">
          <div className="relative w-full sm:w-[320px]">
            <Search
              className={cn(
                'pointer-events-none absolute left-3 top-1/2 h-4 w-4 -translate-y-1/2',
                'text-muted-foreground',
              )}
            />
            <Input
              value={resourceSearch}
              onChange={(event) => updateResourceSearch(event.target.value)}
              className="h-9 pl-9"
              placeholder="Search resources..."
            />
          </div>
          <Button variant="ghost" size="icon" className="h-9 w-9" onClick={onClose}>
            <X className="h-4 w-4" />
          </Button>
        </div>
      </CardHeader>
      <CardContent className="px-0 pb-0">
        <div className="overflow-x-auto">
          <Table>
            <TableHeader>
              <TableRow className="hover:bg-transparent">
                <TableHead className="min-w-[220px] pl-4">Resource</TableHead>
                <TableHead className="min-w-[150px]">Service</TableHead>
                <TableHead className="text-right">Traces</TableHead>
                <TableHead className="text-right">Erroring</TableHead>
                <TableHead className="text-right">Error rate</TableHead>
                <TableHead className="min-w-[140px] pr-4 text-right">Avg duration</TableHead>
              </TableRow>
            </TableHeader>
            <TableBody>
              {resourcesQuery.isLoading ? (
                <SkeletonTableRows rowKey="all-erroring-resource" cells={ALL_ERRORING_RESOURCE_SKELETON_CELLS} />
              ) : resources.length === 0 ? (
                <EmptyTableRow colSpan={6} label="No erroring resources match the current filters." />
              ) : resources.map((resource) => (
                <AllErroringResourceRow
                  key={`${resource.service}-${resource.resource}-${resource.name}`}
                  resource={resource}
                />
              ))}
            </TableBody>
          </Table>
        </div>
        <div
          className={cn(
            'flex flex-col gap-3 border-t px-4 py-3 text-sm text-muted-foreground',
            'sm:flex-row sm:items-center sm:justify-between',
          )}
        >
          {resourcesQuery.isLoading ? (
            <SkeletonBlock className="h-4 w-44" />
          ) : (
            <span>
              Showing {resources.length === 0 ? 0 : page * RESOURCE_PAGE_SIZE + 1}-
              {Math.min((page * RESOURCE_PAGE_SIZE) + resources.length, totalResources)} of{' '}
              {formatCount(totalResources)} resources
            </span>
          )}
          <div className="flex items-center gap-2">
            <Button
              variant="outline"
              size="icon"
              className="h-8 w-8"
              disabled={resourcesQuery.isLoading || page === 0}
              onClick={() => updateResourcePage((currentPage) => Math.max(0, currentPage - 1))}
            >
              <ChevronLeft className="h-4 w-4" />
            </Button>
            {resourcesQuery.isLoading ? (
              <SkeletonBlock className="h-8 w-16" />
            ) : (
              <>
                <Badge variant="secondary" className="h-8 px-3">
                  {page + 1}
                </Badge>
                <span>of {totalPages}</span>
              </>
            )}
            <Button
              variant="outline"
              size="icon"
              className="h-8 w-8"
              disabled={resourcesQuery.isLoading || page >= totalPages - 1}
              onClick={() => updateResourcePage((currentPage) => Math.min(totalPages - 1, currentPage + 1))}
            >
              <ChevronRight className="h-4 w-4" />
            </Button>
          </div>
        </div>
      </CardContent>
    </Card>
  )
}

function AllErroringResourceRow({resource}: {resource: ApmResourceStatsItem}) {
  return (
    <TableRow>
      <TableCell className="max-w-[300px] truncate pl-4 font-mono text-xs">
        {resource.resource}
      </TableCell>
      <TableCell className="max-w-[180px] truncate text-xs text-muted-foreground">
        {resource.service}
      </TableCell>
      <TableCell className="text-right tabular-nums">{formatCount(resource.totalHits)}</TableCell>
      <TableCell className="text-right tabular-nums text-danger-fg">
        {formatCount(resource.totalErrors)}
      </TableCell>
      <TableCell className="text-right">
        <ErrorMeter rate={resource.errorRate} />
      </TableCell>
      <TableCell className="pr-4 text-right tabular-nums">
        {formatDuration(resource.avgDurationNs)}
      </TableCell>
    </TableRow>
  )
}

function ResourceHotspotTable({
  resources,
  mode,
  isLoading,
}: {
  resources: ApmOverviewResponse['resourceHotspots']
  mode: 'rate' | 'count'
  isLoading: boolean
}) {
  const sorted = [...resources].sort((a, b) => (
    mode === 'rate'
      ? b.errorRate - a.errorRate || b.errorCount - a.errorCount
      : b.errorCount - a.errorCount || b.errorRate - a.errorRate
  ))

  return (
    <div className="overflow-x-auto">
      <Table>
        <TableHeader>
          <TableRow className="hover:bg-transparent">
            <TableHead className="min-w-[180px] pl-4">Resource</TableHead>
            <TableHead className="min-w-[140px]">Service</TableHead>
            <TableHead className="text-right">Error rate</TableHead>
            <TableHead className="text-right pr-4">Traces</TableHead>
          </TableRow>
        </TableHeader>
        <TableBody>
          {isLoading ? (
            <SkeletonTableRows rowKey="resource-hotspot" cells={RESOURCE_HOTSPOT_SKELETON_CELLS} />
          ) : sorted.length === 0 ? (
            <EmptyTableRow colSpan={4} label="No resource hotspots" />
          ) : sorted.map((resource, index) => (
            <TableRow key={`${resource.service}-${resource.resource}-${resource.source}-${index}`}>
              <TableCell className="max-w-[220px] truncate pl-4 font-mono text-xs">
                {resource.resource}
              </TableCell>
              <TableCell className="max-w-[160px] truncate text-xs text-muted-foreground">
                {resource.service}
              </TableCell>
              <TableCell className="text-right">
                <ErrorMeter rate={resource.errorRate} />
              </TableCell>
              <TableCell className="pr-4 text-right tabular-nums">
                {formatCount(resource.traceCount)}
              </TableCell>
            </TableRow>
          ))}
        </TableBody>
      </Table>
    </div>
  )
}

function ErrorGroupTable({
  errors,
  isLoading,
}: {
  errors: ApmOverviewResponse['errors']
  isLoading: boolean
}) {
  return (
    <div className="overflow-x-auto">
      <Table>
        <TableHeader>
          <TableRow className="hover:bg-transparent">
            <TableHead className="min-w-[180px] pl-4">Resource</TableHead>
            <TableHead className="min-w-[140px]">Service</TableHead>
            <TableHead className="text-right">Errors</TableHead>
            <TableHead className="text-right pr-4">Trace</TableHead>
          </TableRow>
        </TableHeader>
        <TableBody>
          {isLoading ? (
            <SkeletonTableRows rowKey="error-group" cells={ERROR_GROUP_SKELETON_CELLS} />
          ) : errors.length === 0 ? (
            <EmptyTableRow colSpan={4} label="No trace errors" />
          ) : errors.map((error) => (
            <TableRow key={error.id}>
              <TableCell className="max-w-[220px] truncate pl-4 font-mono text-xs">
                {error.resource}
              </TableCell>
              <TableCell className="max-w-[160px] truncate text-xs text-muted-foreground">
                {error.service}
              </TableCell>
              <TableCell className="text-right tabular-nums text-danger-fg">
                {formatCount(error.count)}
              </TableCell>
              <TableCell className="pr-4 text-right">
                {error.traceId ? (
                  <Link
                    to="/performance/traces/$traceId"
                    params={{traceId: error.traceId}}
                    className="inline-flex items-center gap-1 text-primary hover:underline"
                  >
                    Open
                    <ExternalLink className="h-3 w-3" />
                  </Link>
                ) : (
                  <span className="text-muted-foreground">-</span>
                )}
              </TableCell>
            </TableRow>
          ))}
        </TableBody>
      </Table>
    </div>
  )
}

function RecentTracesPanel({
  traces,
  totalTraces,
  page,
  totalPages,
  isLoading,
  onPageChange,
}: {
  traces: ApmTraceListItem[]
  totalTraces: number
  page: number
  totalPages: number
  isLoading: boolean
  onPageChange: (page: number) => void
}) {
  return (
    <Card className="mt-4 min-w-0 overflow-hidden rounded-lg">
      <CardHeader className="flex flex-row items-center justify-between gap-3 px-4 py-3">
        <CardTitle className="text-sm">Recent traces</CardTitle>
        <span className="text-xs text-muted-foreground">{formatCount(totalTraces)} traces</span>
      </CardHeader>
      <CardContent className="px-0 pb-0">
        <div className="overflow-x-auto">
          <Table>
            <TableHeader>
              <TableRow className="hover:bg-transparent">
                <TableHead className="min-w-[140px] pl-4">Time</TableHead>
                <TableHead className="min-w-[160px]">Service</TableHead>
                <TableHead className="w-[96px]">Source</TableHead>
                <TableHead className="min-w-[220px]">Resource</TableHead>
                <TableHead className="text-right">Spans</TableHead>
                <TableHead className="min-w-[160px] text-right">Duration</TableHead>
                <TableHead className="text-center">Status</TableHead>
                <TableHead className="min-w-[190px] pr-4">Trace ID</TableHead>
              </TableRow>
            </TableHeader>
            <TableBody>
              {isLoading ? (
                <SkeletonTableRows
                  rowKey="recent-trace"
                  cells={RECENT_TRACE_SKELETON_CELLS}
                  rowTestId="recent-trace-skeleton-row"
                />
              ) : traces.length === 0 ? (
                <EmptyTableRow colSpan={8} label="No traces match the current filters." />
              ) : traces.map((trace) => (
                <TableRow key={trace.traceId}>
                  <TableCell className="pl-4 text-xs text-muted-foreground">
                    {formatRelativeTime(trace.startNs)}
                  </TableCell>
                  <TableCell>
                    <div className="flex items-center gap-2">
                      <span className={cn('h-2 w-2 rounded-full', serviceDotFromName(trace.rootService))} />
                      <span className="font-medium">{trace.rootService}</span>
                    </div>
                  </TableCell>
                  <TableCell>
                    <SourceBadge source={trace.source} />
                  </TableCell>
                  <TableCell className="max-w-[280px] truncate font-mono text-xs">
                    {trace.rootResource || trace.rootName}
                  </TableCell>
                  <TableCell className="text-right tabular-nums">{trace.spanCount}</TableCell>
                  <TableCell>
                    <DurationMeter value={trace.durationNs} />
                  </TableCell>
                  <TableCell className="text-center">
                    <TraceStatusBadge hasError={trace.hasError} />
                  </TableCell>
                  <TableCell className="pr-4">
                    <Link
                      to="/performance/traces/$traceId"
                      params={{traceId: trace.traceId}}
                      className={cn(
                        'inline-flex max-w-[190px] items-center gap-1 truncate font-mono text-xs',
                        'text-primary hover:underline',
                      )}
                    >
                      <span className="truncate">{trace.traceId}</span>
                      <ExternalLink className="h-3 w-3 shrink-0" />
                    </Link>
                  </TableCell>
                </TableRow>
              ))}
            </TableBody>
          </Table>
        </div>
        <div
          className={cn(
            'flex flex-col gap-3 border-t px-4 py-3 text-sm text-muted-foreground',
            'sm:flex-row sm:items-center sm:justify-between',
          )}
        >
          {isLoading ? (
            <SkeletonBlock className="h-4 w-40" />
          ) : (
            <span>
              Showing {traces.length === 0 ? 0 : page * TRACE_PAGE_SIZE + 1}-
              {Math.min((page * TRACE_PAGE_SIZE) + traces.length, totalTraces)} of {formatCount(totalTraces)} traces
            </span>
          )}
          <div className="flex items-center gap-2">
            <Button
              variant="outline"
              size="icon"
              className="h-8 w-8"
              disabled={isLoading || page === 0}
              onClick={() => onPageChange(Math.max(0, page - 1))}
            >
              <ChevronLeft className="h-4 w-4" />
            </Button>
            {isLoading ? (
              <SkeletonBlock className="h-8 w-16" />
            ) : (
              <>
                <Badge variant="secondary" className="h-8 px-3">
                  {page + 1}
                </Badge>
                <span>of {totalPages}</span>
              </>
            )}
            <Button
              variant="outline"
              size="icon"
              className="h-8 w-8"
              disabled={isLoading || page >= totalPages - 1}
              onClick={() => onPageChange(Math.min(totalPages - 1, page + 1))}
            >
              <ChevronRight className="h-4 w-4" />
            </Button>
          </div>
        </div>
      </CardContent>
    </Card>
  )
}

function LatencyChartSkeleton() {
  return (
    <div className="flex h-full flex-col justify-end gap-4" aria-busy="true">
      <div className="grid flex-1 grid-cols-8 items-end gap-3 px-1 pt-4">
        {Array.from({length: 8}, (_, index) => (
          <div key={`latency-chart-skeleton-${index}`} className="flex h-full items-end">
            <SkeletonBlock className={cn('w-full', index % 3 === 0 ? 'h-3/4' : 'h-1/2')} />
          </div>
        ))}
      </div>
      <SkeletonBlock className="h-3 w-full" />
    </div>
  )
}

function SkeletonTableRows({
  rowKey,
  cells,
  rowTestId,
}: {
  rowKey: string
  cells: SkeletonCellSpec[]
  rowTestId?: string
}) {
  return (
    <>
      {Array.from({length: SKELETON_TABLE_ROWS}, (_, rowIndex) => (
        <TableRow key={`${rowKey}-skeleton-${rowIndex}`} aria-busy="true" data-testid={rowTestId}>
          {cells.map((cell, cellIndex) => (
            <TableCell key={`${rowKey}-skeleton-cell-${cellIndex}`} className={cell.cellClassName}>
              {cell.leadingDot ? (
                <div className="flex items-center gap-2">
                  <SkeletonBlock className="h-2 w-2 rounded-full" />
                  <SkeletonBlock className={cell.blockClassName} />
                </div>
              ) : (
                <SkeletonBlock className={cell.blockClassName} />
              )}
            </TableCell>
          ))}
        </TableRow>
      ))}
    </>
  )
}

function SkeletonBlock({className}: {className?: string}) {
  return <span className={cn('block animate-pulse rounded bg-muted', className)} aria-hidden="true" />
}

function ErrorMeter({rate}: {rate: number}) {
  const pct = rate * 100
  const color = pct >= 5 ? 'bg-danger-solid' : pct > 0 ? 'bg-warning-solid' : 'bg-success-solid'
  return (
    <div className="flex items-center justify-end gap-2">
      <span className={cn('tabular-nums', pct > 0 && 'text-danger-fg')}>
        {formatPercent(rate)}
      </span>
      <span className="h-1.5 w-12 overflow-hidden rounded-full bg-muted">
        <span className={cn('block h-full rounded-full', color)} style={{width: `${Math.min(100, pct * 4)}%`}} />
      </span>
    </div>
  )
}

function DurationMeter({value}: {value: number}) {
  const ratio = Math.min(1, value / DURATION_BAR_MAX_NS)
  const color = ratio > 0.75 ? 'bg-danger-solid' : ratio > 0.3 ? 'bg-warning-solid' : 'bg-success-solid'
  return (
    <div className="flex items-center justify-end gap-2">
      <span className="tabular-nums">{formatDuration(value)}</span>
      <span className="h-1.5 w-16 overflow-hidden rounded-full bg-muted">
        <span className={cn('block h-full rounded-full', color)} style={{width: `${Math.max(4, ratio * 100)}%`}} />
      </span>
    </div>
  )
}

function TraceStatusBadge({hasError}: {hasError: boolean}) {
  if (hasError) {
    return (
      <Badge variant="destructive" className="gap-1 text-[11px]">
        <AlertTriangle className="h-3 w-3" />
        Error
      </Badge>
    )
  }
  return (
    <Badge variant="outline" className="gap-1 text-[11px] text-success-fg">
      <CheckCircle2 className="h-3 w-3" />
      OK
    </Badge>
  )
}

function Delta({
  current,
  previous,
  inverted,
}: {
  current: number
  previous: number
  inverted: boolean
}) {
  if (!previous) {
    return <div className="mt-1 text-xs text-muted-foreground">No prior window</div>
  }
  const rawDelta = (current - previous) / previous
  const good = inverted ? rawDelta <= 0 : rawDelta >= 0
  return (
    <div className={cn('mt-1 text-xs tabular-nums', good ? 'text-success-fg' : 'text-danger-fg')}>
      {rawDelta >= 0 ? '+' : ''}
      {(rawDelta * 100).toFixed(1)}% vs previous
    </div>
  )
}

function MiniSparkline({values, className}: {values: number[]; className?: string}) {
  const width = 80
  const height = 40
  const min = Math.min(...values)
  const max = Math.max(...values)
  const points = values.map((value, index) => {
    const x = values.length === 1 ? 0 : (index / (values.length - 1)) * width
    const y = max === min ? height / 2 : height - ((value - min) / (max - min)) * height
    return `${x.toFixed(1)},${y.toFixed(1)}`
  })

  return (
    <svg viewBox={`0 0 ${width} ${height}`} className={className} aria-hidden="true">
      <polyline points={points.join(' ')} fill="none" stroke="currentColor" strokeWidth="2" />
    </svg>
  )
}

function LegendDot({className, label}: {className: string; label: string}) {
  return (
    <span className="inline-flex items-center gap-1">
      <span className={cn('h-2 w-2 rounded-full', className)} />
      {label}
    </span>
  )
}

function EmptyTableRow({colSpan, label}: {colSpan: number; label: string}) {
  return (
    <TableRow>
      <TableCell colSpan={colSpan} className="py-8 text-center text-sm text-muted-foreground">
        {label}
      </TableCell>
    </TableRow>
  )
}

const emptyPreviousStats = {
  totalTraces: 0,
  errorRate: 0,
  p50DurationNs: 0,
  p95DurationNs: 0,
  p99DurationNs: 0,
  avgSpansPerTrace: 0,
}

const emptyOverview: ApmOverviewResponse = {
  stats: {
    totalTraces: 0,
    errorTraces: 0,
    errorRate: 0,
    serviceCount: 0,
    sourceCount: 0,
    p50DurationNs: 0,
    p95DurationNs: 0,
    p99DurationNs: 0,
    avgSpansPerTrace: 0,
    previous: emptyPreviousStats,
  },
  latencySeries: [],
  serviceHealth: [],
  resourceHotspots: [],
  errors: [],
  facets: {
    services: [],
    sources: [],
    environments: [],
    operations: [],
  },
}

function buildTraceFacetSections(overview: ApmOverviewResponse): FacetRailSection[] {
  const okTraces = Math.max(0, overview.stats.totalTraces - overview.stats.errorTraces)

  return [
    {
      key: 'service',
      label: 'Service',
      color: 'bg-chart-1',
      allowExclude: false,
      options: overview.facets.services,
    },
    {
      key: 'operation',
      label: 'Operation',
      color: 'bg-chart-2',
      singleSelect: true,
      allowExclude: false,
      options: overview.facets.operations ?? [],
    },
    {
      key: 'env',
      label: 'Environment',
      color: 'bg-chart-3',
      singleSelect: true,
      allowExclude: false,
      options: overview.facets.environments,
    },
    {
      key: 'status',
      label: 'Status',
      color: 'bg-chart-4',
      singleSelect: true,
      allowExclude: false,
      options: [
        {value: 'error', label: 'Errors', count: overview.stats.errorTraces},
        {value: 'ok', label: 'OK', count: okTraces},
      ],
    },
    {
      key: 'source',
      label: 'Source',
      color: 'bg-chart-6',
      singleSelect: true,
      allowExclude: false,
      options: overview.facets.sources,
    },
  ]
}

function facetValues(filters: readonly FacetFilter[], key: string): string[] {
  return filters
    .filter((filter) => filter.key === key && !filter.exclude)
    .map((filter) => filter.value)
}

function firstFacetValue(filters: readonly FacetFilter[], key: string): string | undefined {
  return facetValues(filters, key)[0]
}

function toStatusFilter(value: string | undefined): ApmStatusFilter | undefined {
  if (value === 'error' || value === 'ok') return value
  return undefined
}

function toOverviewParams(timeRange: ApmTimeRange, filters: readonly FacetFilter[]): OverviewParams {
  const services = facetValues(filters, 'service')

  return {
    timeRange,
    services: services.length > 0 ? services : undefined,
    source: firstFacetValue(filters, 'source'),
    status: toStatusFilter(firstFacetValue(filters, 'status')),
    env: firstFacetValue(filters, 'env'),
    operation: firstFacetValue(filters, 'operation'),
  }
}

function useDebouncedValue<T>(value: T, delayMs: number): T {
  const [debouncedValue, setDebouncedValue] = useState(value)

  useEffect(() => {
    const timeout = globalThis.setTimeout(() => setDebouncedValue(value), delayMs)
    return () => globalThis.clearTimeout(timeout)
  }, [delayMs, value])

  return debouncedValue
}

function formatDuration(ns: number): string {
  if (ns < 1000) return `${ns}ns`
  if (ns < 1_000_000) return `${(ns / 1000).toFixed(1)}us`
  if (ns < 1_000_000_000) return `${(ns / 1_000_000).toFixed(1)}ms`
  return `${(ns / 1_000_000_000).toFixed(2)}s`
}

function formatCount(value: number): string {
  if (value >= 1_000_000) return `${(value / 1_000_000).toFixed(1)}M`
  if (value >= 1_000) return `${(value / 1_000).toFixed(1)}k`
  return value.toLocaleString()
}

function formatPercent(value: number): string {
  return `${(value * 100).toFixed(1)}%`
}

function nsToMs(ns: number): number {
  return ns / 1_000_000
}

function formatShortTime(timestamp: string): string {
  const date = new Date(timestamp)
  if (Number.isNaN(date.getTime())) return timestamp
  return date.toLocaleTimeString([], {hour: '2-digit', minute: '2-digit'})
}

function formatRelativeTime(ns: number): string {
  if (!ns) return '-'
  const diffMs = Date.now() - ns / 1_000_000
  if (diffMs < 60_000) return `${Math.max(0, Math.floor(diffMs / 1000))}s ago`
  if (diffMs < 3_600_000) return `${Math.floor(diffMs / 60_000)}m ago`
  if (diffMs < 86_400_000) return `${Math.floor(diffMs / 3_600_000)}h ago`
  return `${Math.floor(diffMs / 86_400_000)}d ago`
}

function serviceDot(index: number): string {
  return SERVICE_DOTS[index % SERVICE_DOTS.length] ?? 'bg-muted-foreground'
}

function serviceDotFromName(service: string): string {
  const sum = service.split('').reduce((acc, char) => acc + char.charCodeAt(0), 0)
  return serviceDot(sum)
}
