import {createFileRoute, Link, redirect} from '@tanstack/react-router'
import {useQuery} from '@tanstack/react-query'
import {api} from '@/lib/api'
import {formatRelativeTime} from '@/lib/utils'
import {Badge} from '@/components/ui/badge'
import {Button} from '@/components/ui/button'
import {Card, CardContent, CardDescription, CardHeader, CardTitle} from '@/components/ui/card'
import {Tabs, TabsContent, TabsList, TabsTrigger} from '@/components/ui/tabs'
import {Select, SelectContent, SelectItem, SelectTrigger, SelectValue} from '@/components/ui/select'
import {
    Activity,
    ArrowLeft,
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
    Thermometer,
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
import {Sheet, SheetContent, SheetHeader, SheetTitle, SheetDescription} from '@/components/ui/sheet'
import {EmbeddedLogs} from '@/components/logs/EmbeddedLogs'
import type {ContainerStats} from '@/lib/api'

type TimeRange = '1h' | '6h' | '24h' | '7d' | '30d' | '90d'
type ContainerViewMode = 'cards' | 'compact'

const CONTAINER_VIEW_MODE_KEY = 'moneat.containers.viewMode'

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

function formatBytesShort(bytes: number | undefined): string {
  if (bytes === undefined) return 'N/A'
  if (bytes === 0) return '0'
  const i = Math.floor(Math.log(bytes) / Math.log(1024))
  return `${(bytes / Math.pow(1024, i)).toFixed(1)}${['B', 'K', 'M', 'G', 'T'][i]}`
}

function formatPercent(value: number | undefined): string {
  if (value === undefined) return 'N/A'
  return `${value.toFixed(1)}%`
}

function getPercentColor(value: number | undefined): string {
  if (value === undefined) return 'text-muted-foreground'
  if (value >= 90) return 'text-red-500'
  if (value >= 75) return 'text-orange-500'
  if (value >= 50) return 'text-yellow-500'
  return 'text-emerald-500'
}

export const Route = createFileRoute('/monitoring/$systemId')({
  beforeLoad: () => {
    if (!api.isAuthenticated()) {
      throw redirect({to: '/login'})
    }
  },
  component: SystemDetailPage,
})

// Custom chart tooltip
function ChartTooltip({
  active,
  payload,
  label,
  formatter,
}: {
  active?: boolean
  payload?: any[]
  label?: string
  formatter?: (value: number, name: string) => string
}) {
  if (!active || !payload?.length) return null

  return (
    <div className="bg-popover/95 backdrop-blur-sm border rounded-lg px-3 py-2 shadow-xl">
      <p className="text-xs text-muted-foreground mb-1">{label}</p>
      {payload.map((entry: any, idx: number) => (
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

function MetricCard({
  title,
  value,
  subtitle,
  icon: Icon,
  iconColor,
  gradientFrom,
  gradientTo,
  borderColor,
}: {
  title: string
  value: string
  subtitle: string
  icon: any
  iconColor: string
  gradientFrom: string
  gradientTo: string
  borderColor: string
}) {
  return (
    <Card className={`relative overflow-hidden bg-gradient-to-br ${gradientFrom} ${gradientTo} ${borderColor}`}>
      <CardContent className="pt-5 pb-4">
        <div className="flex items-center justify-between">
          <div className="space-y-1">
            <p className="text-xs font-medium text-muted-foreground uppercase tracking-wider">
              {title}
            </p>
            <p className="text-2xl font-bold tracking-tight">{value}</p>
            <p className="text-xs text-muted-foreground">{subtitle}</p>
          </div>
          <div className={`flex items-center justify-center h-12 w-12 rounded-xl ${iconColor} bg-opacity-15`}>
            <Icon className="h-6 w-6" />
          </div>
        </div>
      </CardContent>
    </Card>
  )
}

function SystemDetailPage() {
  const {systemId} = Route.useParams()
  const [timeRange, setTimeRange] = useState<TimeRange>('24h')
  const [containerViewMode, setContainerViewMode] = useState<ContainerViewMode>(getInitialContainerViewMode())
  const [activeTab, setActiveTab] = useState('overview')
  const [selectedContainer, setSelectedContainer] = useState<ContainerStats | null>(null)

  const {data: billingUsage} = useQuery({
    queryKey: ['billing-usage'],
    queryFn: () => api.getBillingUsage(),
  })
  const retentionDays = billingUsage?.retentionDays ?? 30
  const availableRanges = useMemo(() => {
    const filtered = TIME_RANGES.filter((option) => option.seconds <= retentionDays * 86_400)
    return filtered.length > 0 ? filtered : [TIME_RANGES[0]]
  }, [retentionDays])

  useEffect(() => {
    if (!availableRanges.some((option) => option.value === timeRange)) {
      const fallback = availableRanges[availableRanges.length - 1]?.value ?? '24h'
      setTimeRange(fallback)
    }
  }, [availableRanges, timeRange, setTimeRange])

  // Persist container view mode to localStorage
  useEffect(() => {
    if (typeof window !== 'undefined') {
      window.localStorage.setItem(CONTAINER_VIEW_MODE_KEY, containerViewMode)
    }
  }, [containerViewMode])

  const {data: system, isLoading: systemLoading} = useQuery({
    queryKey: ['monitor-system', systemId],
    queryFn: () => api.getMonitorSystem(systemId),
  })

  const selectedRange =
    availableRanges.find((r) => r.value === timeRange) ??
    availableRanges[availableRanges.length - 1] ??
    TIME_RANGES[0]
  const now = new Date()
  const fromMs = now.getTime() - selectedRange.seconds * 1000
  const from = Math.floor(fromMs / 1000).toString()
  const to = Math.floor(now.getTime() / 1000).toString()

  const {data: metrics} = useQuery({
    queryKey: ['system-metrics', systemId, timeRange],
    queryFn: () => api.getSystemMetrics(systemId, from, to),
    refetchInterval: 30000,
  })

  const {data: containers = []} = useQuery({
    queryKey: ['system-containers', systemId],
    queryFn: () => api.getSystemContainers(systemId),
    refetchInterval: 30000,
  })

  if (systemLoading) {
    return (
      <div className="flex items-center justify-center min-h-screen">
        <div className="flex flex-col items-center gap-3">
          <div className="h-8 w-8 animate-spin rounded-full border-2 border-primary border-t-transparent" />
          <p className="text-muted-foreground text-sm">Loading system details...</p>
        </div>
      </div>
    )
  }

  if (!system) {
    return (
      <div className="flex flex-col items-center justify-center min-h-screen gap-4">
        <Server className="h-12 w-12 text-muted-foreground" />
        <div className="text-muted-foreground text-lg">System not found</div>
        <Button variant="outline" asChild>
          <Link to="/monitoring">Back to Systems</Link>
        </Button>
      </div>
    )
  }

  const memPercent =
    system.memUsed && system.memTotal ? (system.memUsed / system.memTotal) * 100 : undefined
  const diskPercent =
    system.diskUsed && system.diskTotal ? (system.diskUsed / system.diskTotal) * 100 : undefined
  const isOnline = system.status === 'up'

  // Transform metrics data for charts
  const cpuData =
    metrics?.data_points.map((point) => ({
      time: new Date(point.timestamp * 1000).toLocaleTimeString(),
      CPU: point.cpu_percent || 0,
    })) || []

  const memoryData =
    metrics?.data_points.map((point) => ({
      time: new Date(point.timestamp * 1000).toLocaleTimeString(),
      Memory: point.mem_percent || 0,
    })) || []

  const diskData =
    metrics?.data_points.map((point) => ({
      time: new Date(point.timestamp * 1000).toLocaleTimeString(),
      Disk: point.disk_percent || 0,
    })) || []

  const networkData =
    metrics?.data_points.map((point) => ({
      time: new Date(point.timestamp * 1000).toLocaleTimeString(),
      Received: point.net_recv_bytes || 0,
      Sent: point.net_sent_bytes || 0,
    })) || []

  const loadData =
    metrics?.data_points.map((point) => ({
      time: new Date(point.timestamp * 1000).toLocaleTimeString(),
      '1 min': point.load_1 || 0,
      '5 min': point.load_5 || 0,
      '15 min': point.load_15 || 0,
    })) || []

  const temperatureData =
    metrics?.data_points
      .filter((point) => point.temp_max)
      .map((point) => ({
        time: new Date(point.timestamp * 1000).toLocaleTimeString(),
        Temperature: point.temp_max!,
      })) || []

  const gpuData =
    metrics?.data_points
      .filter((point) => point.gpu_percent)
      .map((point) => ({
        time: new Date(point.timestamp * 1000).toLocaleTimeString(),
        GPU: point.gpu_percent!,
      })) || []

  const commonXAxis = {
    dataKey: 'time',
    tick: {fontSize: 11},
    tickLine: false,
    axisLine: false,
    className: 'text-xs fill-muted-foreground',
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

  return (
    <div className="min-h-screen bg-background">
      {/* Header */}
      <div className="border-b bg-card/50">
        <div className="container mx-auto px-4 py-4">
          <div className="flex items-center gap-2 mb-4">
            <Button
              variant="ghost"
              size="sm"
              asChild
              className="gap-2 text-muted-foreground hover:text-foreground"
            >
              <Link to="/monitoring">
                <ArrowLeft className="h-4 w-4" />
                Back to Systems
              </Link>
            </Button>
          </div>

          <div className="flex items-center justify-between">
            <div className="flex items-center gap-4">
              <div
                className={`flex items-center justify-center h-12 w-12 rounded-xl ${
                  isOnline
                    ? 'bg-emerald-500/10 text-emerald-600 dark:text-emerald-400'
                    : 'bg-red-500/10 text-red-600 dark:text-red-400'
                }`}
              >
                <Server className="h-6 w-6" />
              </div>
              <div>
                <h1 className="text-2xl font-bold tracking-tight">{system.name}</h1>
                <div className="flex items-center gap-3 text-sm text-muted-foreground mt-1 flex-wrap">
                  <Badge
                    variant="secondary"
                    className={`text-xs ${
                      isOnline
                        ? 'bg-emerald-500/15 text-emerald-700 dark:text-emerald-300 border-emerald-500/20'
                        : 'bg-red-500/15 text-red-700 dark:text-red-300 border-red-500/20'
                    }`}
                  >
                    <div
                      className={`h-1.5 w-1.5 rounded-full mr-1.5 ${
                        isOnline ? 'bg-emerald-500 animate-pulse' : 'bg-red-500'
                      }`}
                    />
                    {system.status}
                  </Badge>
                  {system.host && (
                    <span className="flex items-center gap-1">
                      <Monitor className="h-3.5 w-3.5" />
                      {system.host}
                    </span>
                  )}
                  {system.os && (
                    <span className="flex items-center gap-1">
                      <Box className="h-3.5 w-3.5" />
                      {system.os}
                    </span>
                  )}
                  {system.lastSeenAt && (
                    <span className="flex items-center gap-1">
                      <Clock className="h-3.5 w-3.5" />
                      {formatRelativeTime(system.lastSeenAt)}
                    </span>
                  )}
                </div>
              </div>
            </div>

            <Select value={timeRange} onValueChange={(v) => setTimeRange(v as TimeRange)}>
              <SelectTrigger className="w-44">
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

      <div className="container mx-auto px-4 py-6">
        <Tabs value={activeTab} onValueChange={setActiveTab} className="space-y-6">
          <div className="flex items-center justify-between">
            <TabsList className="bg-muted/50">
              <TabsTrigger value="overview" className="gap-1.5">
                <Activity className="h-3.5 w-3.5" />
                Overview
              </TabsTrigger>
              <TabsTrigger value="containers" className="gap-1.5">
                <Box className="h-3.5 w-3.5" />
                Containers
                {containers.length > 0 && (
                  <Badge variant="secondary" className="ml-1 h-5 px-1.5 text-[10px]">
                    {containers.length}
                  </Badge>
                )}
              </TabsTrigger>
              <TabsTrigger value="network" className="gap-1.5">
                <Network className="h-3.5 w-3.5" />
                Network
              </TabsTrigger>
              <TabsTrigger value="alerts" className="gap-1.5">
                <AlertTriangleIcon className="h-3.5 w-3.5" />
                Alerts
              </TabsTrigger>
            </TabsList>

            {/* View mode toggle */}
            {activeTab === 'containers' && containers.length > 0 && (
              <div className="flex items-center gap-1 rounded-lg border bg-background p-1">
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

          <TabsContent value="overview" className="space-y-6">
            {/* Current Stats Cards */}
            <div className="grid gap-4 md:grid-cols-2 lg:grid-cols-4">
              <MetricCard
                title="CPU Usage"
                value={formatPercent(system.cpuPercent)}
                subtitle={`Load: ${system.load1?.toFixed(2) || 'N/A'}`}
                icon={Cpu}
                iconColor="text-blue-500"
                gradientFrom="from-blue-500/5"
                gradientTo="to-cyan-500/5"
                borderColor="border-blue-500/10"
              />
              <MetricCard
                title="Memory"
                value={formatPercent(memPercent)}
                subtitle={`${formatBytesShort(system.memUsed)} / ${formatBytesShort(system.memTotal)}`}
                icon={MemoryStick}
                iconColor="text-violet-500"
                gradientFrom="from-violet-500/5"
                gradientTo="to-purple-500/5"
                borderColor="border-violet-500/10"
              />
              <MetricCard
                title="Disk Usage"
                value={formatPercent(diskPercent)}
                subtitle={`${formatBytesShort(system.diskUsed)} / ${formatBytesShort(system.diskTotal)}`}
                icon={HardDrive}
                iconColor="text-amber-500"
                gradientFrom="from-amber-500/5"
                gradientTo="to-orange-500/5"
                borderColor="border-amber-500/10"
              />
              <MetricCard
                title="Temperature"
                value={system.tempMax ? `${system.tempMax.toFixed(1)}°C` : 'N/A'}
                subtitle="Max sensor temperature"
                icon={Thermometer}
                iconColor="text-rose-500"
                gradientFrom="from-rose-500/5"
                gradientTo="to-red-500/5"
                borderColor="border-rose-500/10"
              />
            </div>

            {/* Charts - 2 column on large screens */}
            <div className="grid gap-6 lg:grid-cols-2">
              {/* CPU Chart */}
              <Card>
                <CardHeader className="pb-2">
                  <div className="flex items-center gap-2">
                    <div className="flex items-center justify-center h-8 w-8 rounded-lg bg-blue-500/10">
                      <Cpu className="h-4 w-4 text-blue-500" />
                    </div>
                    <div>
                      <CardTitle className="text-sm">CPU Usage</CardTitle>
                      <CardDescription className="text-xs">Percentage over time</CardDescription>
                    </div>
                  </div>
                </CardHeader>
                <CardContent>
                  {cpuData.length > 0 ? (
                    <ResponsiveContainer width="100%" height={250}>
                      <AreaChart data={cpuData}>
                        <defs>
                          <linearGradient id="cpuGradient" x1="0" y1="0" x2="0" y2="1">
                            <stop offset="5%" stopColor="#3b82f6" stopOpacity={0.25} />
                            <stop offset="95%" stopColor="#3b82f6" stopOpacity={0} />
                          </linearGradient>
                        </defs>
                        <CartesianGrid {...commonGrid} />
                        <XAxis {...commonXAxis} />
                        <YAxis {...commonYAxis} domain={[0, 100]} />
                        <Tooltip
                          content={
                            <ChartTooltip
                              formatter={(v) => `${v.toFixed(1)}%`}
                            />
                          }
                        />
                        <Area
                          type="monotone"
                          dataKey="CPU"
                          stroke="#3b82f6"
                          strokeWidth={2}
                          fillOpacity={1}
                          fill="url(#cpuGradient)"
                        />
                      </AreaChart>
                    </ResponsiveContainer>
                  ) : (
                    <EmptyChart />
                  )}
                </CardContent>
              </Card>

              {/* Memory Chart */}
              <Card>
                <CardHeader className="pb-2">
                  <div className="flex items-center gap-2">
                    <div className="flex items-center justify-center h-8 w-8 rounded-lg bg-violet-500/10">
                      <MemoryStick className="h-4 w-4 text-violet-500" />
                    </div>
                    <div>
                      <CardTitle className="text-sm">Memory Usage</CardTitle>
                      <CardDescription className="text-xs">Percentage over time</CardDescription>
                    </div>
                  </div>
                </CardHeader>
                <CardContent>
                  {memoryData.length > 0 ? (
                    <ResponsiveContainer width="100%" height={250}>
                      <AreaChart data={memoryData}>
                        <defs>
                          <linearGradient id="memGradient" x1="0" y1="0" x2="0" y2="1">
                            <stop offset="5%" stopColor="#8b5cf6" stopOpacity={0.25} />
                            <stop offset="95%" stopColor="#8b5cf6" stopOpacity={0} />
                          </linearGradient>
                        </defs>
                        <CartesianGrid {...commonGrid} />
                        <XAxis {...commonXAxis} />
                        <YAxis {...commonYAxis} domain={[0, 100]} />
                        <Tooltip
                          content={
                            <ChartTooltip
                              formatter={(v) => `${v.toFixed(1)}%`}
                            />
                          }
                        />
                        <Area
                          type="monotone"
                          dataKey="Memory"
                          stroke="#8b5cf6"
                          strokeWidth={2}
                          fillOpacity={1}
                          fill="url(#memGradient)"
                        />
                      </AreaChart>
                    </ResponsiveContainer>
                  ) : (
                    <EmptyChart />
                  )}
                </CardContent>
              </Card>

              {/* Disk Usage Chart */}
              <Card>
                <CardHeader className="pb-2">
                  <div className="flex items-center gap-2">
                    <div className="flex items-center justify-center h-8 w-8 rounded-lg bg-amber-500/10">
                      <HardDrive className="h-4 w-4 text-amber-500" />
                    </div>
                    <div>
                      <CardTitle className="text-sm">Disk Usage</CardTitle>
                      <CardDescription className="text-xs">Percentage over time</CardDescription>
                    </div>
                  </div>
                </CardHeader>
                <CardContent>
                  {diskData.length > 0 ? (
                    <ResponsiveContainer width="100%" height={250}>
                      <AreaChart data={diskData}>
                        <defs>
                          <linearGradient id="diskGradient" x1="0" y1="0" x2="0" y2="1">
                            <stop offset="5%" stopColor="#f59e0b" stopOpacity={0.25} />
                            <stop offset="95%" stopColor="#f59e0b" stopOpacity={0} />
                          </linearGradient>
                        </defs>
                        <CartesianGrid {...commonGrid} />
                        <XAxis {...commonXAxis} />
                        <YAxis {...commonYAxis} domain={[0, 100]} />
                        <Tooltip
                          content={
                            <ChartTooltip
                              formatter={(v) => `${v.toFixed(1)}%`}
                            />
                          }
                        />
                        <Area
                          type="monotone"
                          dataKey="Disk"
                          stroke="#f59e0b"
                          strokeWidth={2}
                          fillOpacity={1}
                          fill="url(#diskGradient)"
                        />
                      </AreaChart>
                    </ResponsiveContainer>
                  ) : (
                    <EmptyChart />
                  )}
                </CardContent>
              </Card>

              {/* Load Average Chart */}
              <Card>
                <CardHeader className="pb-2">
                  <div className="flex items-center gap-2">
                    <div className="flex items-center justify-center h-8 w-8 rounded-lg bg-emerald-500/10">
                      <Activity className="h-4 w-4 text-emerald-500" />
                    </div>
                    <div>
                      <CardTitle className="text-sm">Load Average</CardTitle>
                      <CardDescription className="text-xs">1m, 5m, 15m averages</CardDescription>
                    </div>
                  </div>
                </CardHeader>
                <CardContent>
                  {loadData.length > 0 ? (
                    <ResponsiveContainer width="100%" height={250}>
                      <LineChart data={loadData}>
                        <CartesianGrid {...commonGrid} />
                        <XAxis {...commonXAxis} />
                        <YAxis {...commonYAxis} />
                        <Tooltip
                          content={
                            <ChartTooltip
                              formatter={(v) => v.toFixed(2)}
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
                          stroke="#ef4444"
                          strokeWidth={2}
                          dot={false}
                          activeDot={{r: 4, strokeWidth: 0}}
                        />
                        <Line
                          type="monotone"
                          dataKey="5 min"
                          stroke="#f59e0b"
                          strokeWidth={2}
                          dot={false}
                          activeDot={{r: 4, strokeWidth: 0}}
                        />
                        <Line
                          type="monotone"
                          dataKey="15 min"
                          stroke="#10b981"
                          strokeWidth={2}
                          dot={false}
                          activeDot={{r: 4, strokeWidth: 0}}
                        />
                      </LineChart>
                    </ResponsiveContainer>
                  ) : (
                    <EmptyChart />
                  )}
                </CardContent>
              </Card>
            </div>

            {/* Full-width charts for conditionally visible metrics */}
            {temperatureData.length > 0 && (
              <Card>
                <CardHeader className="pb-2">
                  <div className="flex items-center gap-2">
                    <div className="flex items-center justify-center h-8 w-8 rounded-lg bg-rose-500/10">
                      <Thermometer className="h-4 w-4 text-rose-500" />
                    </div>
                    <div>
                      <CardTitle className="text-sm">Temperature</CardTitle>
                      <CardDescription className="text-xs">Max sensor reading over time</CardDescription>
                    </div>
                  </div>
                </CardHeader>
                <CardContent>
                  <ResponsiveContainer width="100%" height={250}>
                    <AreaChart data={temperatureData}>
                      <defs>
                        <linearGradient id="tempGradient" x1="0" y1="0" x2="0" y2="1">
                          <stop offset="5%" stopColor="#ef4444" stopOpacity={0.2} />
                          <stop offset="95%" stopColor="#ef4444" stopOpacity={0} />
                        </linearGradient>
                      </defs>
                      <CartesianGrid {...commonGrid} />
                      <XAxis {...commonXAxis} />
                      <YAxis {...commonYAxis} />
                      <Tooltip
                        content={
                          <ChartTooltip
                            formatter={(v) => `${v.toFixed(1)}°C`}
                          />
                        }
                      />
                      <Area
                        type="monotone"
                        dataKey="Temperature"
                        stroke="#ef4444"
                        strokeWidth={2}
                        fillOpacity={1}
                        fill="url(#tempGradient)"
                      />
                    </AreaChart>
                  </ResponsiveContainer>
                </CardContent>
              </Card>
            )}

            {gpuData.length > 0 && gpuData.some((d) => d.GPU > 0) && (
              <Card>
                <CardHeader className="pb-2">
                  <div className="flex items-center gap-2">
                    <div className="flex items-center justify-center h-8 w-8 rounded-lg bg-teal-500/10">
                      <Cpu className="h-4 w-4 text-teal-500" />
                    </div>
                    <div>
                      <CardTitle className="text-sm">GPU Usage</CardTitle>
                      <CardDescription className="text-xs">GPU utilization over time</CardDescription>
                    </div>
                  </div>
                </CardHeader>
                <CardContent>
                  <ResponsiveContainer width="100%" height={250}>
                    <AreaChart data={gpuData}>
                      <defs>
                        <linearGradient id="gpuGradient" x1="0" y1="0" x2="0" y2="1">
                          <stop offset="5%" stopColor="#14b8a6" stopOpacity={0.2} />
                          <stop offset="95%" stopColor="#14b8a6" stopOpacity={0} />
                        </linearGradient>
                      </defs>
                      <CartesianGrid {...commonGrid} />
                      <XAxis {...commonXAxis} />
                      <YAxis {...commonYAxis} domain={[0, 100]} />
                      <Tooltip
                        content={
                          <ChartTooltip
                            formatter={(v) => `${v.toFixed(1)}%`}
                          />
                        }
                      />
                      <Area
                        type="monotone"
                        dataKey="GPU"
                        stroke="#14b8a6"
                        strokeWidth={2}
                        fillOpacity={1}
                        fill="url(#gpuGradient)"
                      />
                    </AreaChart>
                  </ResponsiveContainer>
                </CardContent>
              </Card>
            )}
          </TabsContent>

          <TabsContent value="containers" className="space-y-6">
            {containers.length > 0 ? (
              <>
                {/* Cards view */}
                {containerViewMode === 'cards' ? (
                  <div className="grid gap-4 md:grid-cols-2">
                    {containers.map((container) => {
                      const isRunning = container.status === 'running'

                      return (
                        <Card
                          key={container.id}
                          onClick={() => setSelectedContainer(container)}
                          className={`relative overflow-hidden cursor-pointer hover:border-primary/50 transition-colors ${
                            isRunning ? 'border-emerald-500/10' : 'border-muted'
                          }`}
                        >
                          <div
                            className={`absolute top-0 left-0 right-0 h-0.5 ${
                              isRunning
                                ? 'bg-gradient-to-r from-emerald-500 to-teal-500'
                                : 'bg-gradient-to-r from-zinc-400 to-zinc-500'
                            }`}
                          />
                          <CardHeader className="pb-3 pt-5">
                            <div className="flex items-center justify-between">
                              <div className="flex items-center gap-2.5 min-w-0">
                                <div
                                  className={`flex items-center justify-center h-8 w-8 rounded-lg shrink-0 ${
                                    isRunning
                                      ? 'bg-emerald-500/10 text-emerald-600 dark:text-emerald-400'
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
                              <Badge
                                variant="secondary"
                                className={`text-xs shrink-0 ${
                                  isRunning
                                    ? 'bg-emerald-500/15 text-emerald-700 dark:text-emerald-300 border-emerald-500/20'
                                    : 'bg-zinc-500/15 text-zinc-700 dark:text-zinc-300 border-zinc-500/20'
                                }`}
                              >
                                {container.status}
                              </Badge>
                            </div>
                          </CardHeader>
                          <CardContent className="pb-4">
                            <div className="grid grid-cols-2 gap-4">
                              <div className="space-y-1">
                                <div className="flex items-center gap-1.5">
                                  <Cpu className="h-3 w-3 text-blue-500" />
                                  <span className="text-xs text-muted-foreground">CPU</span>
                                </div>
                                <p className={`text-lg font-semibold ${getPercentColor(container.cpuPercent)}`}>
                                  {formatPercent(container.cpuPercent)}
                                </p>
                              </div>
                              <div className="space-y-1">
                                <div className="flex items-center gap-1.5">
                                  <MemoryStick className="h-3 w-3 text-violet-500" />
                                  <span className="text-xs text-muted-foreground">Memory</span>
                                </div>
                                <p className="text-lg font-semibold">
                                  {formatBytesShort(container.memUsed)}
                                  <span className="text-xs text-muted-foreground font-normal ml-1">
                                    / {formatBytesShort(container.memLimit)}
                                  </span>
                                </p>
                              </div>
                              <div className="space-y-1">
                                <div className="flex items-center gap-1.5">
                                  <Network className="h-3 w-3 text-sky-500" />
                                  <span className="text-xs text-muted-foreground">Net In</span>
                                </div>
                                <p className="text-lg font-semibold">
                                  {formatBytesShort(container.netRecvBytes)}
                                </p>
                              </div>
                              <div className="space-y-1">
                                <div className="flex items-center gap-1.5">
                                  <Network className="h-3 w-3 text-indigo-500 rotate-180" />
                                  <span className="text-xs text-muted-foreground">Net Out</span>
                                </div>
                                <p className="text-lg font-semibold">
                                  {formatBytesShort(container.netSentBytes)}
                                </p>
                              </div>
                            </div>
                          </CardContent>
                        </Card>
                      )
                    })}
                  </div>
                ) : (
                  /* Compact table view */
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
                            const isRunning = container.status === 'running'
                            return (
                              <tr 
                                key={container.id} 
                                onClick={() => setSelectedContainer(container)}
                                className="border-b last:border-0 hover:bg-muted/30 transition-colors cursor-pointer"
                              >
                                <td className="py-3 px-4">
                                  <div className="flex items-center gap-2.5">
                                    <div
                                      className={`flex items-center justify-center h-6 w-6 rounded shrink-0 ${
                                        isRunning
                                          ? 'bg-emerald-500/10 text-emerald-600 dark:text-emerald-400'
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
                                  <Badge
                                    variant="secondary"
                                    className={`text-xs ${
                                      isRunning
                                        ? 'bg-emerald-500/15 text-emerald-700 dark:text-emerald-300 border-emerald-500/20'
                                        : 'bg-zinc-500/15 text-zinc-700 dark:text-zinc-300 border-zinc-500/20'
                                    }`}
                                  >
                                    {container.status}
                                  </Badge>
                                </td>
                                <td className={`py-3 px-4 text-right text-sm font-medium ${getPercentColor(container.cpuPercent)}`}>
                                  {formatPercent(container.cpuPercent)}
                                </td>
                                <td className="py-3 px-4 text-right text-sm font-medium">
                                  {formatBytesShort(container.memUsed)}
                                  <span className="text-xs text-muted-foreground font-normal ml-1">
                                    / {formatBytesShort(container.memLimit)}
                                  </span>
                                </td>
                                <td className="py-3 px-4 text-right text-sm font-medium">
                                  {formatBytesShort(container.netRecvBytes)}
                                </td>
                                <td className="py-3 px-4 text-right text-sm font-medium">
                                  {formatBytesShort(container.netSentBytes)}
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
              <Card className="border-dashed bg-gradient-to-br from-cyan-500/5 via-background to-blue-500/5">
                <CardContent className="py-12">
                  <div className="mx-auto max-w-2xl text-center">
                    <div className="mx-auto mb-4 flex h-16 w-16 items-center justify-center rounded-2xl bg-cyan-500/10 border border-cyan-500/20">
                      <Box className="h-8 w-8 text-cyan-600 dark:text-cyan-400" />
                    </div>
                    <h3 className="text-xl font-semibold mb-2">No containers detected</h3>
                    <p className="text-muted-foreground text-sm mb-6">
                      The agent is online, but it can’t read Docker container metadata yet.
                    </p>
                  </div>
                </CardContent>
              </Card>
            )}
          </TabsContent>

          <TabsContent value="network" className="space-y-6">
            <Card>
              <CardHeader className="pb-2">
                <div className="flex items-center gap-2">
                  <div className="flex items-center justify-center h-8 w-8 rounded-lg bg-indigo-500/10">
                    <Network className="h-4 w-4 text-indigo-500" />
                  </div>
                  <div>
                    <CardTitle className="text-sm">Network Throughput</CardTitle>
                    <CardDescription className="text-xs">Bytes sent and received over time</CardDescription>
                  </div>
                </div>
              </CardHeader>
              <CardContent>
                {networkData.length > 0 ? (
                  <ResponsiveContainer width="100%" height={400}>
                    <AreaChart data={networkData}>
                      <defs>
                        <linearGradient id="netRecvGradient" x1="0" y1="0" x2="0" y2="1">
                          <stop offset="5%" stopColor="#8b5cf6" stopOpacity={0.2} />
                          <stop offset="95%" stopColor="#8b5cf6" stopOpacity={0} />
                        </linearGradient>
                        <linearGradient id="netSentGradient" x1="0" y1="0" x2="0" y2="1">
                          <stop offset="5%" stopColor="#f59e0b" stopOpacity={0.2} />
                          <stop offset="95%" stopColor="#f59e0b" stopOpacity={0} />
                        </linearGradient>
                      </defs>
                      <CartesianGrid {...commonGrid} />
                      <XAxis {...commonXAxis} />
                      <YAxis {...commonYAxis} />
                      <Tooltip
                        content={
                          <ChartTooltip
                            formatter={(v) => formatBytes(v)}
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
                        stroke="#8b5cf6"
                        strokeWidth={2}
                        fillOpacity={1}
                        fill="url(#netRecvGradient)"
                      />
                      <Area
                        type="monotone"
                        dataKey="Sent"
                        stroke="#f59e0b"
                        strokeWidth={2}
                        fillOpacity={1}
                        fill="url(#netSentGradient)"
                      />
                    </AreaChart>
                  </ResponsiveContainer>
                ) : (
                  <EmptyChart height={400} />
                )}
              </CardContent>
            </Card>
          </TabsContent>

          <TabsContent value="alerts" className="space-y-6">
            <AlertsTab systemId={systemId} />
          </TabsContent>
        </Tabs>
      </div>
      
      <Sheet open={!!selectedContainer} onOpenChange={(open) => !open && setSelectedContainer(null)}>
        <SheetContent side="right" className="sm:max-w-2xl w-[800px] overflow-y-auto">
          {selectedContainer && (
            <div className="space-y-6">
              <SheetHeader>
                <div className="flex items-center gap-2">
                  <div className={`flex items-center justify-center h-8 w-8 rounded-lg ${
                    selectedContainer.status === 'running'
                      ? 'bg-emerald-500/10 text-emerald-600 dark:text-emerald-400'
                      : 'bg-muted text-muted-foreground'
                  }`}>
                    <Box className="h-4 w-4" />
                  </div>
                  <div>
                    <SheetTitle>{selectedContainer.name}</SheetTitle>
                    <SheetDescription>{selectedContainer.image}</SheetDescription>
                  </div>
                </div>
              </SheetHeader>

              <div className="grid grid-cols-2 gap-4">
                 <Card>
                    <CardHeader className="pb-2">
                      <CardTitle className="text-xs font-medium text-muted-foreground">Status</CardTitle>
                    </CardHeader>
                    <CardContent>
                      <Badge variant="secondary" className={selectedContainer.status === 'running' ? 'text-emerald-500' : ''}>
                        {selectedContainer.status}
                      </Badge>
                    </CardContent>
                 </Card>
                 <Card>
                    <CardHeader className="pb-2">
                      <CardTitle className="text-xs font-medium text-muted-foreground">Container ID</CardTitle>
                    </CardHeader>
                    <CardContent>
                      <div className="font-mono text-xs">{selectedContainer.id.substring(0, 12)}</div>
                    </CardContent>
                 </Card>
              </div>

              <div className="space-y-4">
                <h3 className="text-sm font-medium">Resources</h3>
                <div className="grid grid-cols-2 gap-4">
                  <div className="p-3 border rounded-lg bg-card">
                     <div className="text-xs text-muted-foreground mb-1">CPU Usage</div>
                     <div className="text-lg font-semibold">{formatPercent(selectedContainer.cpuPercent)}</div>
                  </div>
                  <div className="p-3 border rounded-lg bg-card">
                     <div className="text-xs text-muted-foreground mb-1">Memory Usage</div>
                     <div className="text-lg font-semibold">
                       {formatBytesShort(selectedContainer.memUsed)}
                       <span className="text-xs text-muted-foreground font-normal ml-1">
                         / {formatBytesShort(selectedContainer.memLimit)}
                       </span>
                     </div>
                  </div>
                </div>
              </div>

              <div className="space-y-4">
                <h3 className="text-sm font-medium">Logs</h3>
                <EmbeddedLogs 
                  systemId={system.id}
                  containerName={selectedContainer.name}
                  showHeader={false}
                  maxHeight="500px"
                  compact={true}
                />
              </div>
            </div>
          )}
        </SheetContent>
      </Sheet>
    </div>
  )
}

function EmptyChart({height = 250}: {height?: number}) {
  return (
    <div
      className="flex flex-col items-center justify-center text-muted-foreground gap-2"
      style={{height}}
    >
      <Activity className="h-8 w-8 opacity-30" />
      <p className="text-sm">No data available</p>
    </div>
  )
}

function AlertTriangleIcon(props: any) {
  return (
    <svg
      xmlns="http://www.w3.org/2000/svg"
      width="24"
      height="24"
      viewBox="0 0 24 24"
      fill="none"
      stroke="currentColor"
      strokeWidth="2"
      strokeLinecap="round"
      strokeLinejoin="round"
      {...props}
    >
      <path d="m21.73 18-8-14a2 2 0 0 0-3.48 0l-8 14A2 2 0 0 0 4 21h16a2 2 0 0 0 1.73-3Z" />
      <path d="M12 9v4" />
      <path d="M12 17h.01" />
    </svg>
  )
}
