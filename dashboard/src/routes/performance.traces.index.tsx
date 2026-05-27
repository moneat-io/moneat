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
  SlidersHorizontal,
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
import {Switch} from '@/components/ui/switch'
import {Tabs, TabsContent, TabsList, TabsTrigger} from '@/components/ui/tabs'
import {Table, TableBody, TableCell, TableHead, TableHeader, TableRow} from '@/components/ui/table'
import {cn} from '@/lib/utils'

export const Route = createFileRoute('/performance/traces/')({
  component: PerformanceTracesPage,
})

const TRACE_PAGE_SIZE = 25
const RESOURCE_PAGE_SIZE = 25
const DURATION_BAR_MAX_NS = 1_000_000_000
const SEARCH_DEBOUNCE_MS = 300

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
  'bg-rose-500',
  'bg-violet-500',
  'bg-blue-500',
  'bg-emerald-500',
  'bg-amber-500',
  'bg-cyan-500',
]

type TraceStatusSelect = 'all' | ApmStatusFilter
type RefreshValue = (typeof REFRESH_OPTIONS)[number]['value']

interface TraceFilters {
  service: string
  source: string
  status: TraceStatusSelect
  env: string
}

interface OverviewParams {
  timeRange: ApmTimeRange
  service?: string
  source?: string
  status?: ApmStatusFilter
  env?: string
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

function PerformanceTracesPage() {
  const [timeRange, setTimeRange] = useState<ApmTimeRange>('24h')
  const [refresh, setRefresh] = useState<RefreshValue>('15s')
  const [draftFilters, setDraftFilters] = useState<TraceFilters>(emptyFilters)
  const [appliedFilters, setAppliedFilters] = useState<TraceFilters>(emptyFilters)
  const [search, setSearch] = useState('')
  const [errorsOnly, setErrorsOnly] = useState(false)
  const [page, setPage] = useState(0)
  const [showErroringResources, setShowErroringResources] = useState(false)
  const [erroringResourcesScrollKey, setErroringResourcesScrollKey] = useState(0)
  const erroringResourcesRef = useRef<HTMLDivElement | null>(null)

  const queryParams = useMemo(
    () => toOverviewParams(timeRange, appliedFilters),
    [timeRange, appliedFilters],
  )
  const debouncedSearch = useDebouncedValue(search.trim(), SEARCH_DEBOUNCE_MS)
  const traceQueryParams = useMemo<TraceListParams>(
    () => ({
      ...queryParams,
      status: errorsOnly ? 'error' : queryParams.status,
      search: debouncedSearch === '' ? undefined : debouncedSearch,
      limit: TRACE_PAGE_SIZE,
      offset: page * TRACE_PAGE_SIZE,
    }),
    [debouncedSearch, errorsOnly, page, queryParams],
  )
  const refreshMs = REFRESH_OPTIONS.find((option) => option.value === refresh)?.ms ?? false

  const overviewQuery = useQuery({
    queryKey: ['apm-overview', queryParams],
    queryFn: () => api.getApmOverview(queryParams),
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

  useEffect(() => {
    const panel = erroringResourcesRef.current
    if (!showErroringResources || typeof panel?.scrollIntoView !== 'function') return
    panel.scrollIntoView({block: 'start', behavior: 'smooth'})
  }, [erroringResourcesScrollKey, showErroringResources])

  const applyFilters = () => {
    setAppliedFilters(draftFilters)
    setPage(0)
  }

  const updateSearch = (value: string) => {
    setSearch(value)
    setPage(0)
  }

  const updateErrorsOnly = (value: boolean) => {
    setErrorsOnly(value)
    setPage(0)
  }

  const clearFilters = () => {
    setDraftFilters(emptyFilters)
    setAppliedFilters(emptyFilters)
    setSearch('')
    setErrorsOnly(false)
    setPage(0)
  }

  const viewAllErroringResources = () => {
    setShowErroringResources(true)
    setErroringResourcesScrollKey((currentKey) => currentKey + 1)
  }

  return (
    <div className="min-w-0 px-4 py-4 sm:px-6 lg:px-8">
      <div className="mb-4 flex flex-col gap-3 border-b pb-4 lg:flex-row lg:items-end lg:justify-between">
        <div className="min-w-0">
          <h1 className="text-xl font-semibold tracking-tight">Health overview</h1>
          <p className="mt-1 max-w-3xl text-sm text-muted-foreground">
            Analyze trace health, latency, and errors across services. Telemetry sources are metadata only.
          </p>
        </div>
        <div className="flex flex-wrap items-center gap-2">
          <Select value={timeRange} onValueChange={(value) => {
            setTimeRange(value as ApmTimeRange)
            setPage(0)
          }}>
            <SelectTrigger className="h-9 w-[164px] gap-2">
              <CalendarDays className="h-4 w-4 text-muted-foreground" />
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
          <Button
            variant="outline"
            size="sm"
            className="h-9 gap-2"
            onClick={() => {
              overviewQuery.refetch()
              tracesQuery.refetch()
            }}
          >
            <RefreshCw className="h-4 w-4" />
            Refresh
          </Button>
          <Select value={refresh} onValueChange={(value) => setRefresh(value as RefreshValue)}>
            <SelectTrigger className="h-9 w-[92px]">
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
        </div>
      </div>

      <TraceFilterBar
        filters={draftFilters}
        overview={overview}
        onChange={setDraftFilters}
        onApply={applyFilters}
        onClear={clearFilters}
      />

      <div className="mt-4 grid gap-3 md:grid-cols-2 xl:grid-cols-3 2xl:grid-cols-6">
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
      </div>

      <div className="mt-4 grid min-w-0 gap-3 xl:grid-cols-2 2xl:grid-cols-[1fr_1fr_1fr]">
        <ServiceHealthPanel overview={overview} />
        <LatencyPanel overview={overview} />
        <ErrorsResourcesPanel
          overview={overview}
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
        search={search}
        errorsOnly={errorsOnly}
        isLoading={tracesQuery.isLoading}
        onSearch={updateSearch}
        onErrorsOnly={updateErrorsOnly}
        onPageChange={setPage}
      />
    </div>
  )
}

function TraceFilterBar({
  filters,
  overview,
  onChange,
  onApply,
  onClear,
}: {
  filters: TraceFilters
  overview: ApmOverviewResponse
  onChange: (filters: TraceFilters) => void
  onApply: () => void
  onClear: () => void
}) {
  return (
    <div className="grid gap-2 rounded-lg border bg-card p-3 lg:grid-cols-[1fr_1fr_1fr_1fr_auto_auto]">
      <FilterSelect
        label="Service"
        value={filters.service}
        placeholder="All services"
        options={overview.facets.services}
        onChange={(service) => onChange({...filters, service})}
      />
      <FilterSelect
        label="Source"
        value={filters.source}
        placeholder="All sources"
        options={overview.facets.sources}
        onChange={(source) => onChange({...filters, source})}
      />
      <div className="space-y-1">
        <div className="text-xs font-medium text-muted-foreground">Status</div>
        <Select
          value={filters.status}
          onValueChange={(status) => onChange({...filters, status: status as TraceStatusSelect})}
        >
          <SelectTrigger className="h-9">
            <SelectValue placeholder="All statuses" />
          </SelectTrigger>
          <SelectContent>
            <SelectItem value="all">All statuses</SelectItem>
            <SelectItem value="error">Errors</SelectItem>
            <SelectItem value="ok">OK</SelectItem>
          </SelectContent>
        </Select>
      </div>
      <FilterSelect
        label="Environment"
        value={filters.env}
        placeholder="All environments"
        options={overview.facets.environments}
        onChange={(env) => onChange({...filters, env})}
      />
      <div className="flex items-end">
        <Button variant="outline" size="sm" className="h-9 w-full gap-2" onClick={onClear}>
          Clear
        </Button>
      </div>
      <div className="flex items-end">
        <Button size="sm" className="h-9 w-full gap-2" onClick={onApply}>
          <SlidersHorizontal className="h-4 w-4" />
          Apply
        </Button>
      </div>
    </div>
  )
}

function FilterSelect({
  label,
  value,
  placeholder,
  options,
  onChange,
}: {
  label: string
  value: string
  placeholder: string
  options: Array<{value: string; count: number}>
  onChange: (value: string) => void
}) {
  return (
    <div className="space-y-1">
      <div className="text-xs font-medium text-muted-foreground">{label}</div>
      <Select value={value} onValueChange={onChange}>
        <SelectTrigger className="h-9">
          <SelectValue placeholder={placeholder} />
        </SelectTrigger>
        <SelectContent>
          <SelectItem value="all">{placeholder}</SelectItem>
          {options.map((option) => (
            <SelectItem key={option.value} value={option.value}>
              {option.value} ({formatCount(option.count)})
            </SelectItem>
          ))}
        </SelectContent>
      </Select>
    </div>
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
            className="h-10 w-20 text-violet-500"
          />
        )}
      </CardContent>
    </Card>
  )
}

function ServiceHealthPanel({overview}: {overview: ApmOverviewResponse}) {
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
              {overview.serviceHealth.length === 0 ? (
                <EmptyTableRow colSpan={4} label="No service health data" />
              ) : overview.serviceHealth.map((service, index) => (
                <TableRow key={service.service}>
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
          to="/performance/service-map"
          className="flex items-center gap-1 border-t px-4 py-3 text-sm font-medium text-primary hover:underline"
        >
          Open service map
          <ArrowUpRight className="h-3.5 w-3.5" />
        </Link>
      </CardContent>
    </Card>
  )
}

function LatencyPanel({overview}: {overview: ApmOverviewResponse}) {
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
          <LegendDot className="bg-violet-500" label="p50" />
          <LegendDot className="bg-blue-500" label="p95" />
          <LegendDot className="bg-teal-500" label="p99" />
        </div>
      </CardHeader>
      <CardContent className="h-[242px] px-3 pb-3 pt-0">
        {chartData.length === 0 ? (
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
              <Line type="monotone" dataKey="p50" stroke="#8b5cf6" strokeWidth={2} dot={false} />
              <Line type="monotone" dataKey="p95" stroke="#3b82f6" strokeWidth={2} dot={false} />
              <Line type="monotone" dataKey="p99" stroke="#14b8a6" strokeWidth={2} dot={false} />
            </LineChart>
          </ResponsiveContainer>
        )}
      </CardContent>
    </Card>
  )
}

function ErrorsResourcesPanel({
  overview,
  isShowingAllErroringResources,
  onViewAllErroringResources,
}: {
  overview: ApmOverviewResponse
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
            <ResourceHotspotTable resources={overview.resourceHotspots} mode="rate" />
          </TabsContent>
          <TabsContent value="error-count" className="m-0">
            <ErrorGroupTable errors={overview.errors} />
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
    queryParams.service ?? '',
    queryParams.source ?? '',
    queryParams.env ?? '',
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
                <EmptyTableRow colSpan={6} label="Loading erroring resources..." />
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
          <span>
            Showing {resources.length === 0 ? 0 : page * RESOURCE_PAGE_SIZE + 1}-
            {Math.min((page * RESOURCE_PAGE_SIZE) + resources.length, totalResources)} of{' '}
            {formatCount(totalResources)} resources
          </span>
          <div className="flex items-center gap-2">
            <Button
              variant="outline"
              size="icon"
              className="h-8 w-8"
              disabled={page === 0}
              onClick={() => updateResourcePage((currentPage) => Math.max(0, currentPage - 1))}
            >
              <ChevronLeft className="h-4 w-4" />
            </Button>
            <Badge variant="secondary" className="h-8 px-3">
              {page + 1}
            </Badge>
            <span>of {totalPages}</span>
            <Button
              variant="outline"
              size="icon"
              className="h-8 w-8"
              disabled={page >= totalPages - 1}
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
      <TableCell className="text-right tabular-nums text-rose-600 dark:text-rose-400">
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
}: {
  resources: ApmOverviewResponse['resourceHotspots']
  mode: 'rate' | 'count'
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
          {sorted.length === 0 ? (
            <EmptyTableRow colSpan={4} label="No resource hotspots" />
          ) : sorted.map((resource) => (
            <TableRow key={`${resource.service}-${resource.resource}`}>
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

function ErrorGroupTable({errors}: {errors: ApmOverviewResponse['errors']}) {
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
          {errors.length === 0 ? (
            <EmptyTableRow colSpan={4} label="No trace errors" />
          ) : errors.map((error) => (
            <TableRow key={error.id}>
              <TableCell className="max-w-[220px] truncate pl-4 font-mono text-xs">
                {error.resource}
              </TableCell>
              <TableCell className="max-w-[160px] truncate text-xs text-muted-foreground">
                {error.service}
              </TableCell>
              <TableCell className="text-right tabular-nums text-rose-600 dark:text-rose-400">
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
  search,
  errorsOnly,
  isLoading,
  onSearch,
  onErrorsOnly,
  onPageChange,
}: {
  traces: ApmTraceListItem[]
  totalTraces: number
  page: number
  totalPages: number
  search: string
  errorsOnly: boolean
  isLoading: boolean
  onSearch: (value: string) => void
  onErrorsOnly: (value: boolean) => void
  onPageChange: (page: number) => void
}) {
  return (
    <Card className="mt-4 min-w-0 overflow-hidden rounded-lg">
      <CardHeader className="flex flex-col gap-3 px-4 py-3 lg:flex-row lg:items-center lg:justify-between">
        <CardTitle className="text-sm">Recent traces</CardTitle>
        <div className="flex flex-col gap-2 sm:flex-row sm:items-center">
          <div className="relative w-full sm:w-[320px]">
            <Search
              className={cn(
                'pointer-events-none absolute left-3 top-1/2 h-4 w-4 -translate-y-1/2',
                'text-muted-foreground',
              )}
            />
            <Input
              value={search}
              onChange={(event) => onSearch(event.target.value)}
              className="h-9 pl-9"
              placeholder="Search by trace ID or resource..."
            />
          </div>
          <label className="flex items-center gap-2 text-sm text-muted-foreground">
            <Switch checked={errorsOnly} onCheckedChange={onErrorsOnly} />
            Show errors only
          </label>
        </div>
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
                <EmptyTableRow colSpan={8} label="Loading traces..." />
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
          <span>
            Showing {traces.length === 0 ? 0 : page * TRACE_PAGE_SIZE + 1}-
            {Math.min((page * TRACE_PAGE_SIZE) + traces.length, totalTraces)} of {formatCount(totalTraces)} traces
          </span>
          <div className="flex items-center gap-2">
            <Button
              variant="outline"
              size="icon"
              className="h-8 w-8"
              disabled={page === 0}
              onClick={() => onPageChange(Math.max(0, page - 1))}
            >
              <ChevronLeft className="h-4 w-4" />
            </Button>
            <Badge variant="secondary" className="h-8 px-3">
              {page + 1}
            </Badge>
            <span>of {totalPages}</span>
            <Button
              variant="outline"
              size="icon"
              className="h-8 w-8"
              disabled={page >= totalPages - 1}
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

function ErrorMeter({rate}: {rate: number}) {
  const pct = rate * 100
  const color = pct >= 5 ? 'bg-rose-500' : pct > 0 ? 'bg-amber-500' : 'bg-emerald-500'
  return (
    <div className="flex items-center justify-end gap-2">
      <span className={cn('tabular-nums', pct > 0 && 'text-rose-600 dark:text-rose-400')}>
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
  const color = ratio > 0.75 ? 'bg-rose-500' : ratio > 0.3 ? 'bg-amber-500' : 'bg-emerald-500'
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
    <Badge variant="outline" className="gap-1 text-[11px] text-emerald-600 dark:text-emerald-400">
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
    <div className={cn('mt-1 text-xs tabular-nums', good ? 'text-emerald-600' : 'text-rose-600')}>
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

const emptyFilters: TraceFilters = {
  service: 'all',
  source: 'all',
  status: 'all',
  env: 'all',
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
  },
}

function toOverviewParams(timeRange: ApmTimeRange, filters: TraceFilters): OverviewParams {
  return {
    timeRange,
    service: filters.service === 'all' ? undefined : filters.service,
    source: filters.source === 'all' ? undefined : filters.source,
    status: filters.status === 'all' ? undefined : filters.status,
    env: filters.env === 'all' ? undefined : filters.env,
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
  return SERVICE_DOTS[index % SERVICE_DOTS.length] ?? 'bg-slate-500'
}

function serviceDotFromName(service: string): string {
  const sum = service.split('').reduce((acc, char) => acc + char.charCodeAt(0), 0)
  return serviceDot(sum)
}
