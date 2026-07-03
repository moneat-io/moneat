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

import {createFileRoute, Link, redirect} from '@tanstack/react-router'
import {useQuery} from '@tanstack/react-query'
import {api, type BillingUsage, type DdContainerResponse} from '@/lib/api'
import {formatRelativeTime} from '@/lib/utils'
import {Badge} from '@/components/ui/badge'
import {Button} from '@/components/ui/button'
import {Card, CardContent, CardDescription, CardHeader, CardTitle} from '@/components/ui/card'
import {StatCard} from '@/components/ui/stat-card'
import {StatusDot} from '@/components/ui/status-dot'
import {EmptyState} from '@/components/ui/empty-state'
import {Tabs, TabsContent, TabsList, TabsTrigger} from '@/components/ui/tabs'
import {Select, SelectContent, SelectItem, SelectTrigger, SelectValue} from '@/components/ui/select'
import {
    Activity,
    ArrowLeft,
    AlertTriangle,
    Box,
    Clock,
    Cpu,
    HardDrive,
    LayoutGrid,
    LayoutList,
    MemoryStick,
    Monitor,
    Network,
    Server,
} from 'lucide-react'
import {
    Area,
    AreaChart,
    CartesianGrid,
    Legend,
    Line,
    LineChart,
    ResponsiveContainer,
    Tooltip,
    XAxis,
    YAxis,
} from 'recharts'
import {useEffect, useMemo, useState} from 'react'
import {AlertsTab} from '@/components/monitoring/AlertsTab'
import {Sheet, SheetContent, SheetDescription, SheetHeader, SheetTitle} from '@/components/ui/sheet'
import {getNowDate} from '@/lib/demo'
import {useTimezone} from '@/hooks/useTimezone'
import {
    buildHostMetricQueryRange,
    compactHostMetricChartData,
    formatHostMetricAxisTick,
    formatHostMetricTooltipLabel,
    toHostMetricChartTimestamp,
} from '@/lib/host-metrics-chart'

type TimeRange = '1h' | '6h' | '24h' | '7d' | '30d' | '90d'
type ContainerViewMode = 'cards' | 'compact'
type HostMonitoringLimitKind = 'gb' | 'infraMetrics'
type ChartTooltipValue = number | null | undefined
type ChartTooltipEntry = { color: string; name: string; value: ChartTooltipValue; dataKey: string }
type VisibleChartTooltipEntry = ChartTooltipEntry & { value: number }

const CONTAINER_VIEW_MODE_KEY = 'moneat.host-containers.viewMode'
const BYTES_PER_GB = 1024 * 1024 * 1024

// Categorical chart colors from the shared palette (style guide chart-1..10).
const CHART_CPU = 'hsl(var(--chart-1))'
const CHART_MEM = 'hsl(var(--chart-2))'
const CHART_DISK = 'hsl(var(--chart-3))'
const CHART_LOAD_1 = 'hsl(var(--chart-4))'
const CHART_LOAD_5 = 'hsl(var(--chart-5))'
const CHART_LOAD_15 = 'hsl(var(--chart-6))'
const CHART_NET_RECV = 'hsl(var(--chart-2))'
const CHART_NET_SENT = 'hsl(var(--chart-3))'

interface HostMonitoringLimitState {
  kind: HostMonitoringLimitKind
  title: string
  message: string
  detail: string
  emptyMessage: string
}

function getInitialContainerViewMode(): ContainerViewMode {
  if (typeof window === 'undefined') return 'cards'
  return window.localStorage.getItem(CONTAINER_VIEW_MODE_KEY) === 'compact' ? 'compact' : 'cards'
}

interface TimeRangeOption {
  value: TimeRange
  label: string
  seconds: number
}

const TIME_RANGES: TimeRangeOption[] = [
  {value: '1h', label: 'Last Hour', seconds: 3600},
  {value: '6h', label: 'Last 6 Hours', seconds: 21600},
  {value: '24h', label: 'Last 24 Hours', seconds: 86400},
  {value: '7d', label: 'Last 7 Days', seconds: 604800},
  {value: '30d', label: 'Last 30 Days', seconds: 2592000},
  {value: '90d', label: 'Last 90 Days', seconds: 7776000},
]

function formatBytes(bytes: number | undefined): string {
  if (bytes === undefined) return 'N/A'
  const sizes = ['B', 'KB', 'MB', 'GB', 'TB']
  if (bytes === 0) return '0 B'
  const i = Math.floor(Math.log(bytes) / Math.log(1024))
  return `${(bytes / Math.pow(1024, i)).toFixed(2)} ${sizes[i]}`
}

function formatGb(bytes: number): string {
  return `${(bytes / BYTES_PER_GB).toFixed(2)} GB`
}

function formatCount(value: number): string {
  if (value >= 1_000_000_000) return `${(value / 1_000_000_000).toFixed(1)}B`
  if (value >= 1_000_000) return `${(value / 1_000_000).toFixed(1)}M`
  if (value >= 1_000) return `${(value / 1_000).toFixed(0)}K`
  return value.toLocaleString()
}

function formatBytesShort(bytes: number | undefined): string {
  if (bytes === undefined) return 'N/A'
  if (bytes === 0) return '0'
  const i = Math.floor(Math.log(bytes) / Math.log(1024))
  return `${(bytes / Math.pow(1024, i)).toFixed(1)}${['B', 'K', 'M', 'G', 'T'][i]}`
}

function formatBytesKb(kb: number | undefined): string {
  if (kb === undefined || kb === 0) return 'N/A'
  if (kb < 1024) return `${kb} KB`
  const mb = kb / 1024
  if (mb < 1024) return `${mb.toFixed(1)} MB`
  const gb = mb / 1024
  return `${gb.toFixed(1)} GB`
}

function formatPercent(value: number | undefined): string {
  if (value === undefined) return 'N/A'
  return `${value.toFixed(1)}%`
}

function getPercentColor(value: number | undefined): string {
  if (value === undefined) return 'text-muted-foreground'
  if (value >= 90) return 'text-danger-fg'
  if (value >= 75) return 'text-warning-fg'
  if (value >= 50) return 'text-info-fg'
  return 'text-success-fg'
}

function getHostMonitoringLimitState(usage: BillingUsage | undefined): HostMonitoringLimitState | null {
  if (!usage) return null

  const usedGbEligibleBytes = Math.max(
    0,
    usage.usedBytes - (usage.usedApmSpanBytes ?? 0) - (usage.usedInfraMetricBytes ?? 0),
  )
  const effectiveBytesLimit = usage.bytesLimit + (usage.bonusGbBytes ?? 0) + (usage.paygLimitBytes ?? 0)

  if (usage.bytesLimit > 0 && usedGbEligibleBytes > effectiveBytesLimit) {
    return {
      kind: 'gb',
      title: 'Host telemetry is paused by billing limits',
      message:
        `This organization has used ${formatGb(usedGbEligibleBytes)} of ` +
        `${formatGb(effectiveBytesLimit)} active GB-billed ingestion.`,
      detail:
        'New host metrics and infrastructure payloads are rejected until capacity is added, ' +
        'pay-as-you-go budget is raised, or the billing period resets.',
      emptyMessage: 'Ingestion is limited, so no new host telemetry is being stored.',
    }
  }

  const infraMetricLimit = usage.infraMetricSeriesHourLimit ?? 0
  const infraMetricOverageRate = usage.infraMetricOverageRateCentsPer100kSeriesHours ?? 0
  const effectiveInfraMetricLimit = infraMetricLimit + (usage.bonusUnits ?? 0)
  const infraMetricsBlocked =
    infraMetricLimit >= 0 &&
    infraMetricOverageRate <= 0 &&
    (usage.usedInfraMetricSeriesHours ?? 0) > effectiveInfraMetricLimit

  if (infraMetricsBlocked) {
    return {
      kind: 'infraMetrics',
      title: 'Host metrics are paused by infrastructure metric limits',
      message:
        `This organization has used ${formatCount(usage.usedInfraMetricSeriesHours ?? 0)} of ` +
        `${formatCount(effectiveInfraMetricLimit)} included infrastructure metric series-hours.`,
      detail:
        'New host metric points are rejected until capacity is added or the billing period resets.',
      emptyMessage: 'Infrastructure metric limit reached, so no new host metric points are being stored.',
    }
  }

  return null
}

export const Route = createFileRoute('/monitoring/hosts/$hostId')({
  beforeLoad: () => {
    if (!api.isAuthenticated()) {
      throw redirect({to: '/login'})
    }
  },
  component: HostDetailPage,
})

function ChartTooltip({
  active,
  payload,
  label,
  formatter,
  labelFormatter,
}: {
  active?: boolean
  payload?: ChartTooltipEntry[]
  label?: string | number
  formatter?: (value: number, name: string) => string
  labelFormatter?: (value: string | number | undefined) => string
}) {
  const visiblePayload = payload?.filter((entry): entry is VisibleChartTooltipEntry =>
    typeof entry.value === 'number' && Number.isFinite(entry.value)
  )
  if (!active || !visiblePayload?.length) return null
  const displayLabel = labelFormatter ? labelFormatter(label) : String(label ?? '')

  return (
    <div className="bg-popover/95 backdrop-blur-sm border rounded-lg px-3 py-2">
      <p className="text-xs text-muted-foreground mb-1">{displayLabel}</p>
      {visiblePayload.map((entry, idx: number) => (
        <div key={idx} className="flex items-center gap-2 text-sm">
          <div className="h-2 w-2 rounded-full" style={{backgroundColor: entry.color}} />
          <span className="text-muted-foreground">{entry.name}:</span>
          <span className="font-medium">
            {formatter ? formatter(entry.value, entry.dataKey) : entry.value}
          </span>
        </div>
      ))}
    </div>
  )
}

function HostMonitoringLimitBanner({state}: {state: HostMonitoringLimitState}) {
  return (
    <div className="rounded-lg border border-warning-border bg-warning-bg px-4 py-3">
      <div className="flex flex-col gap-3 md:flex-row md:items-start md:justify-between">
        <div className="flex gap-3">
          <div className="mt-0.5 flex h-8 w-8 shrink-0 items-center justify-center rounded-md bg-warning-bg">
            <AlertTriangle className="h-4 w-4 text-warning-fg" />
          </div>
          <div className="space-y-1">
            <p className="text-sm font-semibold text-warning-fg">{state.title}</p>
            <p className="text-sm text-warning-fg/90">{state.message}</p>
            <p className="text-xs text-muted-foreground">{state.detail}</p>
          </div>
        </div>
        <Button asChild size="sm" variant="outline" className="shrink-0 border-warning-border">
          <Link to="/settings" search={{tab: 'billing'}}>
            View Billing
          </Link>
        </Button>
      </div>
    </div>
  )
}

function HostDetailPage() {
  const {hostId} = Route.useParams()
  const [timeRange, setTimeRange] = useState<TimeRange>('24h')
  const [containerViewMode, setContainerViewMode] = useState<ContainerViewMode>(getInitialContainerViewMode())
  const [activeTab, setActiveTab] = useState('overview')
  const [selectedContainer, setSelectedContainer] = useState<DdContainerResponse | null>(null)
  const {timezone} = useTimezone()

  const {data: billingUsage} = useQuery({
    queryKey: ['billing-usage'],
    queryFn: () => api.getBillingUsage(),
  })
  const retentionDays = billingUsage?.retentionDays ?? 30
  const availableRanges = useMemo(() => {
    const filtered = TIME_RANGES.filter((option) => option.seconds <= retentionDays * 86_400)
    return filtered.length > 0 ? filtered : [TIME_RANGES[0]]
  }, [retentionDays])

  const effectiveTimeRange: TimeRange = availableRanges.some((option) => option.value === timeRange)
    ? timeRange
    : (availableRanges[availableRanges.length - 1]?.value ?? '24h') as TimeRange

  useEffect(() => {
    if (typeof window !== 'undefined') {
      window.localStorage.setItem(CONTAINER_VIEW_MODE_KEY, containerViewMode)
    }
  }, [containerViewMode])

  const {data: host, isLoading: hostLoading} = useQuery({
    queryKey: ['host', hostId],
    queryFn: () => api.getHost(hostId),
    refetchInterval: 30000,
  })

  const selectedRange =
    availableRanges.find((r) => r.value === effectiveTimeRange) ??
    availableRanges[availableRanges.length - 1] ??
    TIME_RANGES[0]
  const [from, to] = useMemo(
    () => buildHostMetricQueryRange(selectedRange.seconds, getNowDate()),
    [selectedRange.seconds],
  )

  const {data: metrics, isLoading: metricsLoading} = useQuery({
    queryKey: ['host-metrics', hostId, effectiveTimeRange, from, to],
    queryFn: () => api.getHostMetrics(hostId, from, to),
    refetchInterval: 30000,
  })

  const {data: containerData} = useQuery({
    queryKey: ['host-containers', hostId],
    queryFn: () => api.getHostContainers(hostId),
    refetchInterval: 30000,
  })

  const containers = containerData?.containers ?? []
  const hostMonitoringLimitState = getHostMonitoringLimitState(billingUsage)

  if (hostLoading) {
    return (
      <div className="flex items-center justify-center min-h-screen">
        <div className="flex flex-col items-center gap-3">
          <div className="h-8 w-8 animate-spin rounded-full border-2 border-primary border-t-transparent" />
          <p className="text-muted-foreground text-sm">Loading host details...</p>
        </div>
      </div>
    )
  }

  if (!host) {
    return (
      <div className="flex flex-col items-center justify-center min-h-screen gap-4">
        <Server className="h-12 w-12 text-muted-foreground" />
        <div className="text-muted-foreground text-lg">Host not found</div>
        <Button variant="outline" asChild>
          <Link to="/monitoring/hosts">Back to Hosts</Link>
        </Button>
      </div>
    )
  }

  const online = host.isOnline

  // Transform metrics data for charts
  const cpuData = compactHostMetricChartData(
    metrics?.data_points.map((point) => ({
      timestamp: toHostMetricChartTimestamp(point.timestamp),
      CPU: point.cpu_percent ?? null,
    })) || [],
    selectedRange.seconds,
    ['CPU'],
  )

  const memoryData = compactHostMetricChartData(
    metrics?.data_points.map((point) => ({
      timestamp: toHostMetricChartTimestamp(point.timestamp),
      Memory: point.mem_percent ?? null,
    })) || [],
    selectedRange.seconds,
    ['Memory'],
  )

  const diskData = compactHostMetricChartData(
    metrics?.data_points.map((point) => ({
      timestamp: toHostMetricChartTimestamp(point.timestamp),
      Disk: point.disk_percent ?? null,
    })) || [],
    selectedRange.seconds,
    ['Disk'],
  )

  const networkData = compactHostMetricChartData(
    metrics?.data_points.map((point) => ({
      timestamp: toHostMetricChartTimestamp(point.timestamp),
      Received: point.net_recv_bytes ?? null,
      Sent: point.net_sent_bytes ?? null,
    })) || [],
    selectedRange.seconds,
    ['Received', 'Sent'],
  )

  const loadData = compactHostMetricChartData(
    metrics?.data_points.map((point) => ({
      timestamp: toHostMetricChartTimestamp(point.timestamp),
      '1 min': point.load_1 ?? null,
      '5 min': point.load_5 ?? null,
      '15 min': point.load_15 ?? null,
    })) || [],
    selectedRange.seconds,
    ['1 min', '5 min', '15 min'],
  )

  const commonXAxis = {
    dataKey: 'timestamp',
    tick: {fontSize: 11},
    tickLine: false,
    axisLine: false,
    className: 'text-xs fill-muted-foreground',
    type: 'number' as const,
    domain: ['dataMin', 'dataMax'] as [string, string],
    scale: 'time' as const,
    tickFormatter: (value: string | number) =>
      formatHostMetricAxisTick(value, selectedRange.seconds, timezone),
  }

  const commonYAxis = {
    tick: {fontSize: 11},
    tickLine: false,
    axisLine: false,
    className: 'text-xs fill-muted-foreground',
    width: 40,
  }

  const commonGrid = {
    strokeDasharray: '3 3',
    className: 'stroke-muted/50',
    vertical: false,
  }
  const chartTooltipLabelFormatter = (value: string | number | undefined) =>
    formatHostMetricTooltipLabel(value, timezone)

  // Get latest data point for metric cards
  const latestPoint = metrics?.data_points[metrics.data_points.length - 1]

  return (
    <div>
      {/* Header */}
      <div className="border-b bg-card/50">
        <div className="container mx-auto px-4 py-4">
          <div className="flex items-center justify-between gap-4">
            <div className="flex items-center gap-3 min-w-0 flex-1">
              <Button variant="ghost" size="sm" asChild className="shrink-0 text-muted-foreground hover:text-foreground -ml-1">
                <Link to="/monitoring/hosts">
                  <ArrowLeft className="h-4 w-4" />
                </Link>
              </Button>
              <div
                className={`flex items-center justify-center h-10 w-10 rounded-lg shrink-0 ${
                  online
                    ? 'bg-success-bg text-success-fg'
                    : 'bg-danger-bg text-danger-fg'
                }`}
              >
                <Server className="h-5 w-5" />
              </div>
              <div className="min-w-0 flex-1">
                <h1 className="text-xl font-bold tracking-tight truncate">{host.hostname}</h1>
                <div className="flex items-center gap-2.5 text-xs text-muted-foreground mt-0.5 flex-wrap">
                  <Badge variant={online ? 'success' : 'danger'} size="sm" className="gap-1.5">
                    <StatusDot tone={online ? 'success' : 'danger'} size="sm" pulse={online} />
                    {online ? 'Online' : 'Offline'}
                  </Badge>
                  {host.os && (
                    <span className="flex items-center gap-1">
                      <Monitor className="h-3 w-3" />
                      {host.os}{host.platform && host.platform !== host.os ? ` (${host.platform})` : ''}
                    </span>
                  )}
                  {host.processor && (
                    <span className="flex items-center gap-1">
                      <Cpu className="h-3 w-3" />
                      {host.cpuCores} cores
                    </span>
                  )}
                  {host.agentVersion && (
                    <Badge variant="neutral" size="sm" className="font-mono">
                      Agent v{host.agentVersion}
                    </Badge>
                  )}
                  {host.lastSeenAt && (
                    <span className="flex items-center gap-1">
                      <Clock className="h-3 w-3" />
                      {formatRelativeTime(host.lastSeenAt)}
                    </span>
                  )}
                </div>
              </div>
            </div>

            <div className="shrink-0">
            <Select value={effectiveTimeRange} onValueChange={(v) => setTimeRange(v as TimeRange)}>
              <SelectTrigger className="w-40 h-9">
                <SelectValue />
              </SelectTrigger>
              <SelectContent>
                {availableRanges.map((range) => (
                  <SelectItem key={range.value} value={range.value}>
                    {range.label}
                  </SelectItem>
                ))}
              </SelectContent>
            </Select>
            </div>
          </div>
        </div>
      </div>

      <div className="container mx-auto px-4 py-4">
        <Tabs value={activeTab} onValueChange={setActiveTab} className="space-y-4">
          <div className="flex items-center justify-between">
            <TabsList className="h-8 bg-muted/50 p-1">
              <TabsTrigger value="overview" className="gap-1 px-2.5 py-1 text-xs">
                <Activity className="h-3 w-3" />
                Overview
              </TabsTrigger>
              <TabsTrigger value="containers" className="gap-1 px-2.5 py-1 text-xs">
                <Box className="h-3 w-3" />
                Containers
                {containers.length > 0 && (
                  <Badge variant="secondary" className="ml-1 h-4 px-1 text-[9px]">
                    {containers.length}
                  </Badge>
                )}
              </TabsTrigger>
              <TabsTrigger value="network" className="gap-1 px-2.5 py-1 text-xs">
                <Network className="h-3 w-3" />
                Network
              </TabsTrigger>
              <TabsTrigger value="alerts" className="gap-1 px-2.5 py-1 text-xs">
                <AlertTriangle className="h-3 w-3" />
                Alerts
              </TabsTrigger>
            </TabsList>

            {activeTab === 'containers' && containers.length > 0 && (
              <div className="flex items-center gap-1 rounded-lg border bg-background p-1 max-w-full overflow-x-auto [scrollbar-width:none] [&::-webkit-scrollbar]:hidden [&>button]:shrink-0">
                <Button
                  variant={containerViewMode === 'cards' ? 'secondary' : 'ghost'}
                  size="sm"
                  onClick={() => setContainerViewMode('cards')}
                  className="h-7 px-2"
                >
                  <LayoutGrid className="h-4 w-4" />
                </Button>
                <Button
                  variant={containerViewMode === 'compact' ? 'secondary' : 'ghost'}
                  size="sm"
                  onClick={() => setContainerViewMode('compact')}
                  className="h-7 px-2"
                >
                  <LayoutList className="h-4 w-4" />
                </Button>
              </div>
            )}
          </div>

          {hostMonitoringLimitState && (
            <HostMonitoringLimitBanner state={hostMonitoringLimitState} />
          )}

          <TabsContent value="overview" className="space-y-4">
            {/* Metric Summary Cards */}
            <div className="grid grid-cols-2 lg:grid-cols-4 gap-4">
              <StatCard
                label="CPU Usage"
                tone="info"
                icon={Cpu}
                value={metricsLoading ? '—' : formatPercent(latestPoint?.cpu_percent)}
                subtitle={`${host.cpuCores} cores · ${host.processor || 'Unknown'}`}
              />
              <StatCard
                label="Memory"
                tone="accent"
                icon={MemoryStick}
                value={metricsLoading ? '—' : formatPercent(latestPoint?.mem_percent)}
                subtitle={`Total: ${formatBytesKb(host.memoryTotalKb)}`}
              />
              <StatCard
                label="Disk Usage"
                tone="warning"
                icon={HardDrive}
                value={metricsLoading ? '—' : formatPercent(latestPoint?.disk_percent)}
                subtitle="Disk utilization"
              />
              <StatCard
                label="Load Average"
                tone="success"
                icon={Activity}
                value={metricsLoading ? '—' : (latestPoint?.load_1 ? latestPoint.load_1.toFixed(2) : 'N/A')}
                subtitle="1 min load average"
              />
            </div>

            {/* Charts */}
            <div className="grid gap-3 lg:grid-cols-2">
              {/* CPU Chart */}
              <Card>
                <CardHeader className="py-2 px-3 pb-0.5">
                  <div className="flex items-center gap-1.5">
                    <div className="flex items-center justify-center h-5 w-5 rounded bg-muted shrink-0">
                      <Cpu className="h-2.5 w-2.5 text-chart-1" />
                    </div>
                    <div className="min-w-0">
                      <CardTitle className="text-xs">CPU Usage</CardTitle>
                      <CardDescription className="text-[10px]">Percentage over time</CardDescription>
                    </div>
                  </div>
                </CardHeader>
                <CardContent className="px-3 pb-3 pt-0">
                  {metricsLoading ? (
                    <ChartSkeleton />
                  ) : cpuData.length > 0 ? (
                    <ResponsiveContainer width="100%" height={144}>
                      <AreaChart data={cpuData}>
                        <defs>
                          <linearGradient id="cpuGradient" x1="0" y1="0" x2="0" y2="1">
                            <stop offset="5%" stopColor={CHART_CPU} stopOpacity={0.25} />
                            <stop offset="95%" stopColor={CHART_CPU} stopOpacity={0} />
                          </linearGradient>
                        </defs>
                        <CartesianGrid {...commonGrid} />
                        <XAxis {...commonXAxis} />
                        <YAxis {...commonYAxis} domain={[0, 100]} />
                        <Tooltip
                          content={
                            <ChartTooltip
                              formatter={(v) => `${v.toFixed(1)}%`}
                              labelFormatter={chartTooltipLabelFormatter}
                            />
                          }
                        />
                        <Area
                          type="monotone"
                          dataKey="CPU"
                          stroke={CHART_CPU}
                          strokeWidth={2}
                          connectNulls
                          fillOpacity={1}
                          fill="url(#cpuGradient)"
                        />
                      </AreaChart>
                    </ResponsiveContainer>
                  ) : (
                    <EmptyChart limitState={hostMonitoringLimitState} />
                  )}
                </CardContent>
              </Card>

              {/* Memory Chart */}
              <Card>
                <CardHeader className="py-2 px-3 pb-0.5">
                  <div className="flex items-center gap-1.5">
                    <div className="flex items-center justify-center h-5 w-5 rounded bg-muted shrink-0">
                      <MemoryStick className="h-2.5 w-2.5 text-chart-2" />
                    </div>
                    <div className="min-w-0">
                      <CardTitle className="text-xs">Memory Usage</CardTitle>
                      <CardDescription className="text-[10px]">Percentage over time</CardDescription>
                    </div>
                  </div>
                </CardHeader>
                <CardContent className="px-3 pb-3 pt-0">
                  {metricsLoading ? (
                    <ChartSkeleton />
                  ) : memoryData.length > 0 ? (
                    <ResponsiveContainer width="100%" height={144}>
                      <AreaChart data={memoryData}>
                        <defs>
                          <linearGradient id="memGradient" x1="0" y1="0" x2="0" y2="1">
                            <stop offset="5%" stopColor={CHART_MEM} stopOpacity={0.25} />
                            <stop offset="95%" stopColor={CHART_MEM} stopOpacity={0} />
                          </linearGradient>
                        </defs>
                        <CartesianGrid {...commonGrid} />
                        <XAxis {...commonXAxis} />
                        <YAxis {...commonYAxis} domain={[0, 100]} />
                        <Tooltip
                          content={
                            <ChartTooltip
                              formatter={(v) => `${v.toFixed(1)}%`}
                              labelFormatter={chartTooltipLabelFormatter}
                            />
                          }
                        />
                        <Area
                          type="monotone"
                          dataKey="Memory"
                          stroke={CHART_MEM}
                          strokeWidth={2}
                          connectNulls
                          fillOpacity={1}
                          fill="url(#memGradient)"
                        />
                      </AreaChart>
                    </ResponsiveContainer>
                  ) : (
                    <EmptyChart limitState={hostMonitoringLimitState} />
                  )}
                </CardContent>
              </Card>

              {/* Disk Usage Chart */}
              <Card>
                <CardHeader className="py-2 px-3 pb-0.5">
                  <div className="flex items-center gap-1.5">
                    <div className="flex items-center justify-center h-5 w-5 rounded bg-muted shrink-0">
                      <HardDrive className="h-2.5 w-2.5 text-chart-3" />
                    </div>
                    <div className="min-w-0">
                      <CardTitle className="text-xs">Disk Usage</CardTitle>
                      <CardDescription className="text-[10px]">Percentage over time</CardDescription>
                    </div>
                  </div>
                </CardHeader>
                <CardContent className="px-3 pb-3 pt-0">
                  {metricsLoading ? (
                    <ChartSkeleton />
                  ) : diskData.length > 0 ? (
                    <ResponsiveContainer width="100%" height={144}>
                      <AreaChart data={diskData}>
                        <defs>
                          <linearGradient id="diskGradient" x1="0" y1="0" x2="0" y2="1">
                            <stop offset="5%" stopColor={CHART_DISK} stopOpacity={0.25} />
                            <stop offset="95%" stopColor={CHART_DISK} stopOpacity={0} />
                          </linearGradient>
                        </defs>
                        <CartesianGrid {...commonGrid} />
                        <XAxis {...commonXAxis} />
                        <YAxis {...commonYAxis} domain={[0, 100]} />
                        <Tooltip
                          content={
                            <ChartTooltip
                              formatter={(v) => `${v.toFixed(1)}%`}
                              labelFormatter={chartTooltipLabelFormatter}
                            />
                          }
                        />
                        <Area
                          type="monotone"
                          dataKey="Disk"
                          stroke={CHART_DISK}
                          strokeWidth={2}
                          connectNulls
                          fillOpacity={1}
                          fill="url(#diskGradient)"
                        />
                      </AreaChart>
                    </ResponsiveContainer>
                  ) : (
                    <EmptyChart limitState={hostMonitoringLimitState} />
                  )}
                </CardContent>
              </Card>

              {/* Load Average Chart */}
              <Card>
                <CardHeader className="py-2 px-3 pb-0.5">
                  <div className="flex items-center gap-1.5">
                    <div className="flex items-center justify-center h-5 w-5 rounded bg-muted shrink-0">
                      <Activity className="h-2.5 w-2.5 text-chart-4" />
                    </div>
                    <div className="min-w-0">
                      <CardTitle className="text-xs">Load Average</CardTitle>
                      <CardDescription className="text-[10px]">1m, 5m, 15m averages</CardDescription>
                    </div>
                  </div>
                </CardHeader>
                <CardContent className="px-3 pb-3 pt-0">
                  {metricsLoading ? (
                    <ChartSkeleton />
                  ) : loadData.length > 0 ? (
                    <ResponsiveContainer width="100%" height={144}>
                      <LineChart data={loadData}>
                        <CartesianGrid {...commonGrid} />
                        <XAxis {...commonXAxis} />
                        <YAxis {...commonYAxis} />
                        <Tooltip
                          content={
                            <ChartTooltip
                              formatter={(v) => v.toFixed(2)}
                              labelFormatter={chartTooltipLabelFormatter}
                            />
                          }
                        />
                        <Legend
                          iconType="circle"
                          iconSize={8}
                          wrapperStyle={{fontSize: '12px'}}
                        />
                        <Line
                          type="monotone"
                          dataKey="1 min"
                          stroke={CHART_LOAD_1}
                          strokeWidth={2}
                          connectNulls
                          dot={false}
                          activeDot={{r: 4, strokeWidth: 0}}
                        />
                        <Line
                          type="monotone"
                          dataKey="5 min"
                          stroke={CHART_LOAD_5}
                          strokeWidth={2}
                          connectNulls
                          dot={false}
                          activeDot={{r: 4, strokeWidth: 0}}
                        />
                        <Line
                          type="monotone"
                          dataKey="15 min"
                          stroke={CHART_LOAD_15}
                          strokeWidth={2}
                          connectNulls
                          dot={false}
                          activeDot={{r: 4, strokeWidth: 0}}
                        />
                      </LineChart>
                    </ResponsiveContainer>
                  ) : (
                    <EmptyChart limitState={hostMonitoringLimitState} />
                  )}
                </CardContent>
              </Card>
            </div>
          </TabsContent>

          <TabsContent value="containers" className="space-y-4">
            {containers.length > 0 ? (
              <>
                {containerViewMode === 'cards' ? (
                  <div className="grid gap-4 md:grid-cols-2">
                    {containers.map((container) => {
                      const isRunning = container.state === 'running'

                      return (
                        <Card
                          key={container.containerId}
                          onClick={() => setSelectedContainer(container)}
                          className={`relative overflow-hidden cursor-pointer hover:border-primary/50 transition-colors ${
                            isRunning ? 'border-success-border' : 'border-muted'
                          }`}
                        >
                          <div
                            className={`absolute top-0 left-0 right-0 h-0.5 ${
                              isRunning ? 'bg-success-solid' : 'bg-muted-foreground/40'
                            }`}
                          />
                          <CardHeader className="pb-3 pt-5">
                            <div className="flex items-center justify-between">
                              <div className="flex items-center gap-2.5 min-w-0">
                                <div
                                  className={`flex items-center justify-center h-8 w-8 rounded-lg shrink-0 ${
                                    isRunning
                                      ? 'bg-success-bg text-success-fg'
                                      : 'bg-muted text-muted-foreground'
                                  }`}
                                >
                                  <Box className="h-4 w-4" />
                                </div>
                                <div className="min-w-0">
                                  <CardTitle className="text-sm truncate">{container.name}</CardTitle>
                                  <p className="text-xs text-muted-foreground truncate">{container.image}</p>
                                </div>
                              </div>
                              <Badge variant={isRunning ? 'success' : 'neutral'} size="sm" className="shrink-0">
                                {container.state}
                              </Badge>
                            </div>
                          </CardHeader>
                          <CardContent className="pb-4">
                            <div className="grid grid-cols-2 gap-4">
                              <div className="space-y-1">
                                <div className="flex items-center gap-1.5">
                                  <Cpu className="h-3 w-3 text-chart-1" />
                                  <span className="text-xs text-muted-foreground">CPU</span>
                                </div>
                                <p className={`text-lg font-semibold ${getPercentColor(container.cpuPercent)}`}>
                                  {formatPercent(container.cpuPercent)}
                                </p>
                              </div>
                              <div className="space-y-1">
                                <div className="flex items-center gap-1.5">
                                  <MemoryStick className="h-3 w-3 text-chart-2" />
                                  <span className="text-xs text-muted-foreground">Memory</span>
                                </div>
                                <p className="text-lg font-semibold">
                                  {formatBytesShort(container.memUsage)}
                                  <span className="text-xs text-muted-foreground font-normal ml-1">
                                    / {formatBytesShort(container.memLimit)}
                                  </span>
                                </p>
                              </div>
                              <div className="space-y-1">
                                <div className="flex items-center gap-1.5">
                                  <Network className="h-3 w-3 text-chart-3" />
                                  <span className="text-xs text-muted-foreground">Net In</span>
                                </div>
                                <p className="text-lg font-semibold">
                                  {formatBytesShort(container.netRxBytes)}
                                </p>
                              </div>
                              <div className="space-y-1">
                                <div className="flex items-center gap-1.5">
                                  <Network className="h-3 w-3 text-chart-4 rotate-180" />
                                  <span className="text-xs text-muted-foreground">Net Out</span>
                                </div>
                                <p className="text-lg font-semibold">
                                  {formatBytesShort(container.netTxBytes)}
                                </p>
                              </div>
                            </div>
                          </CardContent>
                        </Card>
                      )
                    })}
                  </div>
                ) : (
                  <Card>
                    <div className="overflow-x-auto">
                      <table className="w-full">
                        <thead>
                          <tr className="border-b">
                            <th className="text-left py-3 px-4 text-sm font-medium text-muted-foreground">Container</th>
                            <th className="text-left py-3 px-4 text-sm font-medium text-muted-foreground">Status</th>
                            <th className="text-right py-3 px-4 text-sm font-medium text-muted-foreground">CPU</th>
                            <th className="text-right py-3 px-4 text-sm font-medium text-muted-foreground">Memory</th>
                            <th className="text-right py-3 px-4 text-sm font-medium text-muted-foreground">Net In</th>
                            <th className="text-right py-3 px-4 text-sm font-medium text-muted-foreground">Net Out</th>
                          </tr>
                        </thead>
                        <tbody>
                          {containers.map((container) => {
                            const isRunning = container.state === 'running'
                            return (
                              <tr
                                key={container.containerId}
                                onClick={() => setSelectedContainer(container)}
                                className="border-b last:border-0 hover:bg-muted/30 transition-colors cursor-pointer"
                              >
                                <td className="py-3 px-4">
                                  <div className="flex items-center gap-2.5">
                                    <div
                                      className={`flex items-center justify-center h-6 w-6 rounded shrink-0 ${
                                        isRunning
                                          ? 'bg-success-bg text-success-fg'
                                          : 'bg-muted text-muted-foreground'
                                      }`}
                                    >
                                      <Box className="h-3 w-3" />
                                    </div>
                                    <div className="min-w-0">
                                      <div className="text-sm font-medium truncate">{container.name}</div>
                                      <div className="text-xs text-muted-foreground truncate">{container.image}</div>
                                    </div>
                                  </div>
                                </td>
                                <td className="py-3 px-4">
                                  <Badge variant={isRunning ? 'success' : 'neutral'} size="sm">
                                    {container.state}
                                  </Badge>
                                </td>
                                <td className={`py-3 px-4 text-right text-sm font-medium ${getPercentColor(container.cpuPercent)}`}>
                                  {formatPercent(container.cpuPercent)}
                                </td>
                                <td className="py-3 px-4 text-right text-sm font-medium">
                                  {formatBytesShort(container.memUsage)}
                                  <span className="text-xs text-muted-foreground font-normal ml-1">
                                    / {formatBytesShort(container.memLimit)}
                                  </span>
                                </td>
                                <td className="py-3 px-4 text-right text-sm font-medium">
                                  {formatBytesShort(container.netRxBytes)}
                                </td>
                                <td className="py-3 px-4 text-right text-sm font-medium">
                                  {formatBytesShort(container.netTxBytes)}
                                </td>
                              </tr>
                            )
                          })}
                        </tbody>
                      </table>
                    </div>
                  </Card>
                )}
              </>
            ) : (
              <EmptyState
                icon={hostMonitoringLimitState ? AlertTriangle : Box}
                title={hostMonitoringLimitState ? hostMonitoringLimitState.title : 'No containers detected'}
                description={
                  hostMonitoringLimitState
                    ? hostMonitoringLimitState.emptyMessage
                    : 'Enable container monitoring by mounting the Docker socket when deploying the agent.'
                }
              />
            )}
          </TabsContent>

          <TabsContent value="network" className="space-y-4">
            <Card>
              <CardHeader className="py-2 px-3 pb-0.5">
                <div className="flex items-center gap-1.5">
                  <div className="flex items-center justify-center h-5 w-5 rounded bg-muted shrink-0">
                    <Network className="h-2.5 w-2.5 text-chart-2" />
                  </div>
                  <div className="min-w-0">
                    <CardTitle className="text-xs">Network Throughput</CardTitle>
                    <CardDescription className="text-[10px]">Bytes sent and received over time</CardDescription>
                  </div>
                </div>
              </CardHeader>
              <CardContent className="px-3 pb-3 pt-0">
                {metricsLoading ? (
                  <ChartSkeleton height={224} />
                ) : networkData.length > 0 ? (
                  <ResponsiveContainer width="100%" height={224}>
                    <AreaChart data={networkData}>
                      <defs>
                        <linearGradient id="netRecvGradient" x1="0" y1="0" x2="0" y2="1">
                          <stop offset="5%" stopColor={CHART_NET_RECV} stopOpacity={0.2} />
                          <stop offset="95%" stopColor={CHART_NET_RECV} stopOpacity={0} />
                        </linearGradient>
                        <linearGradient id="netSentGradient" x1="0" y1="0" x2="0" y2="1">
                          <stop offset="5%" stopColor={CHART_NET_SENT} stopOpacity={0.2} />
                          <stop offset="95%" stopColor={CHART_NET_SENT} stopOpacity={0} />
                        </linearGradient>
                      </defs>
                      <CartesianGrid {...commonGrid} />
                      <XAxis {...commonXAxis} />
                      <YAxis
                        {...commonYAxis}
                        width={60}
                        tickFormatter={(value) => formatBytesShort(value)}
                      />
                      <Tooltip
                        content={
                          <ChartTooltip
                            formatter={(v) => formatBytes(v)}
                            labelFormatter={chartTooltipLabelFormatter}
                          />
                        }
                      />
                      <Legend
                        iconType="circle"
                        iconSize={8}
                        wrapperStyle={{fontSize: '12px'}}
                      />
                      <Area
                        type="monotone"
                        dataKey="Received"
                        stroke={CHART_NET_RECV}
                        strokeWidth={2}
                        connectNulls
                        fillOpacity={1}
                        fill="url(#netRecvGradient)"
                      />
                      <Area
                        type="monotone"
                        dataKey="Sent"
                        stroke={CHART_NET_SENT}
                        strokeWidth={2}
                        connectNulls
                        fillOpacity={1}
                        fill="url(#netSentGradient)"
                      />
                    </AreaChart>
                  </ResponsiveContainer>
                ) : (
                  <EmptyChart height={224} limitState={hostMonitoringLimitState} />
                )}
              </CardContent>
            </Card>
          </TabsContent>

          <TabsContent value="alerts" className="space-y-4">
            <AlertsTab hostId={hostId} />
          </TabsContent>
        </Tabs>
      </div>

      <Sheet open={!!selectedContainer} onOpenChange={(open) => !open && setSelectedContainer(null)}>
        <SheetContent
          side="right"
          className="h-screen w-[50vw] max-w-[50vw] p-0 gap-0 border-none sm:max-w-[50vw]"
        >
          {selectedContainer && (
            <div className="flex flex-col h-full bg-background/50 backdrop-blur-sm">
              <div className="flex-none p-6 pb-4 border-b bg-background">
                <SheetHeader className="mb-6">
                  <div className="flex items-center gap-3">
                    <div className={`flex items-center justify-center h-10 w-10 rounded-xl ${
                      selectedContainer.state === 'running'
                        ? 'bg-success-bg text-success-fg'
                        : 'bg-muted text-muted-foreground'
                    }`}>
                      <Box className="h-5 w-5" />
                    </div>
                    <div>
                      <SheetTitle className="text-xl">{selectedContainer.name}</SheetTitle>
                      <SheetDescription className="font-mono text-xs mt-1">{selectedContainer.image}</SheetDescription>
                    </div>
                    <div className="ml-auto flex items-center gap-2">
                      <Button variant="outline" size="sm" onClick={() => setSelectedContainer(null)}>
                        Close
                      </Button>
                    </div>
                  </div>
                </SheetHeader>

                <div className="grid grid-cols-2 md:grid-cols-4 gap-4">
                     <div className="p-3 border rounded-lg bg-card/50">
                        <div className="text-xs text-muted-foreground mb-1">Status</div>
                        <Badge variant={selectedContainer.state === 'running' ? 'success' : 'neutral'} size="sm">
                          {selectedContainer.state}
                        </Badge>
                     </div>
                     <div className="p-3 border rounded-lg bg-card/50">
                        <div className="text-xs text-muted-foreground mb-1">Container ID</div>
                        <div className="font-mono text-xs truncate" title={selectedContainer.containerId}>{selectedContainer.containerId.substring(0, 12)}</div>
                     </div>
                     <div className="p-3 border rounded-lg bg-card/50">
                        <div className="text-xs text-muted-foreground mb-1">CPU Usage</div>
                        <div className="text-lg font-semibold">{formatPercent(selectedContainer.cpuPercent)}</div>
                     </div>
                     <div className="p-3 border rounded-lg bg-card/50">
                        <div className="text-xs text-muted-foreground mb-1">Memory Usage</div>
                        <div className="text-lg font-semibold truncate">
                          {formatBytesShort(selectedContainer.memUsage)}
                          <span className="text-xs text-muted-foreground font-normal ml-1 opacity-70">
                            / {formatBytesShort(selectedContainer.memLimit)}
                          </span>
                        </div>
                     </div>
                </div>
              </div>

              <div className="flex-1 p-6 overflow-auto">
                <div className="grid grid-cols-2 gap-4">
                  <div className="p-3 border rounded-lg bg-card/50">
                    <div className="text-xs text-muted-foreground mb-1">Network In</div>
                    <div className="text-lg font-semibold">{formatBytesShort(selectedContainer.netRxBytes)}</div>
                  </div>
                  <div className="p-3 border rounded-lg bg-card/50">
                    <div className="text-xs text-muted-foreground mb-1">Network Out</div>
                    <div className="text-lg font-semibold">{formatBytesShort(selectedContainer.netTxBytes)}</div>
                  </div>
                </div>
              </div>
            </div>
          )}
        </SheetContent>
      </Sheet>
    </div>
  )
}

function EmptyChart({
  height = 144,
  limitState,
}: {
  height?: number
  limitState?: HostMonitoringLimitState | null
}) {
  return (
    <div
      className="flex flex-col items-center justify-center text-muted-foreground gap-1"
      style={{height}}
    >
      {limitState ? (
        <AlertTriangle className="h-5 w-5 text-warning-fg opacity-80" />
      ) : (
        <Activity className="h-5 w-5 opacity-30" />
      )}
      <p className="text-[11px] font-medium">
        {limitState ? 'Telemetry paused' : 'No data available'}
      </p>
      {limitState && (
        <p className="max-w-[260px] text-center text-[10px] leading-snug">
          {limitState.emptyMessage}
        </p>
      )}
    </div>
  )
}

function ChartSkeleton({height = 144}: {height?: number}) {
  return (
    <div className="space-y-2 py-1" style={{height}}>
      <div className="flex items-end gap-1 h-full px-1">
        {Array.from({length: 20}).map((_, i) => (
          <div
            key={i}
            className="flex-1 animate-pulse rounded-sm bg-muted"
            style={{height: `${30 + Math.sin(i * 0.8) * 20 + (i % 3) * 10}%`}}
          />
        ))}
      </div>
    </div>
  )
}
