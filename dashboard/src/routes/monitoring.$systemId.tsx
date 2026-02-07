import {createFileRoute, redirect} from '@tanstack/react-router'
import {useQuery} from '@tanstack/react-query'
import {api} from '@/lib/api'
import {formatRelativeTime} from '@/lib/utils'
import {Badge} from '@/components/ui/badge'
import {Button} from '@/components/ui/button'
import {Card, CardContent, CardHeader, CardTitle} from '@/components/ui/card'
import {Tabs, TabsContent, TabsList, TabsTrigger} from '@/components/ui/tabs'
import {Select, SelectContent, SelectItem, SelectTrigger, SelectValue} from '@/components/ui/select'
import {
  Activity,
  ArrowLeft,
  Cpu,
  HardDrive,
  MemoryStick,
  Network,
  Server,
  Thermometer,
} from 'lucide-react'
import {
  LineChart,
  Line,
  AreaChart,
  Area,
  XAxis,
  YAxis,
  CartesianGrid,
  Tooltip,
  ResponsiveContainer,
  Legend,
} from 'recharts'
import {useState} from 'react'
import {AlertsTab} from '@/components/monitoring/AlertsTab'

type TimeRange = '1h' | '6h' | '24h' | '7d' | '30d'

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

function getStatusColor(status: string) {
  switch (status) {
    case 'up':
      return 'bg-green-500'
    case 'down':
      return 'bg-red-500'
    default:
      return 'bg-yellow-500'
  }
}

export const Route = createFileRoute('/monitoring/$systemId')({
  beforeLoad: () => {
    if (!api.isAuthenticated()) {
      throw redirect({to: '/login'})
    }
  },
  component: SystemDetailPage,
})

function SystemDetailPage() {
  const {systemId} = Route.useParams()
  const [timeRange, setTimeRange] = useState<TimeRange>('24h')

  const {data: system, isLoading: systemLoading} = useQuery({
    queryKey: ['monitor-system', systemId],
    queryFn: () => api.getMonitorSystem(systemId),
  })

  const selectedRange = TIME_RANGES.find((r) => r.value === timeRange)!
  const now = new Date()
  const from = new Date(now.getTime() - selectedRange.seconds * 1000).toISOString()
  const to = now.toISOString()

  const {data: metrics} = useQuery({
    queryKey: ['system-metrics', systemId, timeRange],
    queryFn: () => api.getSystemMetrics(systemId, from, to),
    refetchInterval: 30000, // Refresh every 30s
  })

  const {data: containers = []} = useQuery({
    queryKey: ['system-containers', systemId],
    queryFn: () => api.getSystemContainers(systemId),
    refetchInterval: 30000,
  })

  if (systemLoading) {
    return (
      <div className="flex items-center justify-center min-h-screen">
        <div className="text-muted-foreground">Loading system details...</div>
      </div>
    )
  }

  if (!system) {
    return (
      <div className="flex items-center justify-center min-h-screen">
        <div className="text-muted-foreground">System not found</div>
      </div>
    )
  }

  // Transform metrics data for charts
  const cpuData =
    metrics?.cpu.map((point) => ({
      time: new Date(point.timestamp).toLocaleTimeString(),
      value: point.value,
    })) || []

  const memoryData =
    metrics?.memUsed.map((point, idx) => ({
      time: new Date(point.timestamp).toLocaleTimeString(),
      used: point.value,
      total: metrics.memTotal[idx]?.value || 0,
      usedPercent:
        metrics.memTotal[idx]?.value
          ? (point.value / metrics.memTotal[idx].value) * 100
          : 0,
    })) || []

  const diskData =
    metrics?.diskUsed.map((point, idx) => ({
      time: new Date(point.timestamp).toLocaleTimeString(),
      used: point.value,
      total: metrics.diskTotal[idx]?.value || 0,
      usedPercent:
        metrics.diskTotal[idx]?.value
          ? (point.value / metrics.diskTotal[idx].value) * 100
          : 0,
    })) || []

  const diskIoData =
    metrics?.diskReadBytes.map((point, idx) => ({
      time: new Date(point.timestamp).toLocaleTimeString(),
      read: point.value,
      write: metrics.diskWriteBytes[idx]?.value || 0,
    })) || []

  const networkData =
    metrics?.netRecvBytes.map((point, idx) => ({
      time: new Date(point.timestamp).toLocaleTimeString(),
      recv: point.value,
      sent: metrics.netSentBytes[idx]?.value || 0,
    })) || []

  const loadData =
    metrics?.load1.map((point, idx) => ({
      time: new Date(point.timestamp).toLocaleTimeString(),
      load1: point.value,
      load5: metrics.load5[idx]?.value || 0,
      load15: metrics.load15[idx]?.value || 0,
    })) || []

  const temperatureData =
    metrics?.tempMax.map((point) => ({
      time: new Date(point.timestamp).toLocaleTimeString(),
      temp: point.value,
    })) || []

  const gpuData =
    metrics?.gpuPercent.map((point, idx) => ({
      time: new Date(point.timestamp).toLocaleTimeString(),
      gpu: point.value,
      memory: metrics.gpuMemPercent[idx]?.value || 0,
    })) || []

  return (
    <div className="min-h-screen bg-background">
      <div className="border-b">
        <div className="container mx-auto px-4 py-4">
          <div className="flex items-center gap-4 mb-4">
            <Button
              variant="ghost"
              size="sm"
              onClick={() => window.history.back()}
            >
              <ArrowLeft className="h-4 w-4 mr-2" />
              Back to Systems
            </Button>
          </div>

          <div className="flex items-center justify-between">
            <div className="flex items-center gap-4">
              <div className="flex items-center gap-3">
                <Server className="h-8 w-8" />
                <div>
                  <h1 className="text-2xl font-bold">{system.name}</h1>
                  <div className="flex items-center gap-4 text-sm text-muted-foreground mt-1">
                    <div className="flex items-center gap-2">
                      <div className={`h-2 w-2 rounded-full ${getStatusColor(system.status)}`} />
                      <span className="capitalize">{system.status}</span>
                    </div>
                    {system.host && <span>{system.host}</span>}
                    {system.os && <span>{system.os}</span>}
                    {system.lastSeenAt && (
                      <span>Last seen {formatRelativeTime(system.lastSeenAt)}</span>
                    )}
                  </div>
                </div>
              </div>
            </div>

            <Select value={timeRange} onValueChange={(v) => setTimeRange(v as TimeRange)}>
              <SelectTrigger className="w-40">
                <SelectValue />
              </SelectTrigger>
              <SelectContent>
                {TIME_RANGES.map((range) => (
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
        <Tabs defaultValue="overview" className="space-y-6">
          <TabsList>
            <TabsTrigger value="overview">Overview</TabsTrigger>
            <TabsTrigger value="containers">Containers</TabsTrigger>
            <TabsTrigger value="disk-io">Disk I/O</TabsTrigger>
            <TabsTrigger value="network">Network</TabsTrigger>
            <TabsTrigger value="alerts">Alerts</TabsTrigger>
          </TabsList>

          <TabsContent value="overview" className="space-y-6">
            {/* Current Stats */}
            <div className="grid gap-4 md:grid-cols-2 lg:grid-cols-4">
              <Card>
                <CardHeader className="flex flex-row items-center justify-between pb-2">
                  <CardTitle className="text-sm font-medium">CPU Usage</CardTitle>
                  <Cpu className="h-4 w-4 text-muted-foreground" />
                </CardHeader>
                <CardContent>
                  <div className="text-2xl font-bold">{formatPercent(system.cpuPercent)}</div>
                  <p className="text-xs text-muted-foreground mt-1">
                    Load: {system.load1?.toFixed(2) || 'N/A'}
                  </p>
                </CardContent>
              </Card>

              <Card>
                <CardHeader className="flex flex-row items-center justify-between pb-2">
                  <CardTitle className="text-sm font-medium">Memory</CardTitle>
                  <MemoryStick className="h-4 w-4 text-muted-foreground" />
                </CardHeader>
                <CardContent>
                  <div className="text-2xl font-bold">
                    {system.memUsed && system.memTotal
                      ? formatPercent((system.memUsed / system.memTotal) * 100)
                      : 'N/A'}
                  </div>
                  <p className="text-xs text-muted-foreground mt-1">
                    {formatBytesShort(system.memUsed)} / {formatBytesShort(system.memTotal)}
                  </p>
                </CardContent>
              </Card>

              <Card>
                <CardHeader className="flex flex-row items-center justify-between pb-2">
                  <CardTitle className="text-sm font-medium">Disk Usage</CardTitle>
                  <HardDrive className="h-4 w-4 text-muted-foreground" />
                </CardHeader>
                <CardContent>
                  <div className="text-2xl font-bold">
                    {system.diskUsed && system.diskTotal
                      ? formatPercent((system.diskUsed / system.diskTotal) * 100)
                      : 'N/A'}
                  </div>
                  <p className="text-xs text-muted-foreground mt-1">
                    {formatBytesShort(system.diskUsed)} / {formatBytesShort(system.diskTotal)}
                  </p>
                </CardContent>
              </Card>

              <Card>
                <CardHeader className="flex flex-row items-center justify-between pb-2">
                  <CardTitle className="text-sm font-medium">Temperature</CardTitle>
                  <Thermometer className="h-4 w-4 text-muted-foreground" />
                </CardHeader>
                <CardContent>
                  <div className="text-2xl font-bold">
                    {system.tempMax ? `${system.tempMax.toFixed(1)}°C` : 'N/A'}
                  </div>
                  <p className="text-xs text-muted-foreground mt-1">Max sensor temp</p>
                </CardContent>
              </Card>
            </div>

            {/* CPU Chart */}
            <Card>
              <CardHeader>
                <CardTitle className="flex items-center gap-2">
                  <Cpu className="h-5 w-5" />
                  CPU Usage
                </CardTitle>
              </CardHeader>
              <CardContent>
                {cpuData.length > 0 ? (
                  <ResponsiveContainer width="100%" height={300}>
                    <AreaChart data={cpuData}>
                      <defs>
                        <linearGradient id="cpuGradient" x1="0" y1="0" x2="0" y2="1">
                          <stop offset="5%" stopColor="#3b82f6" stopOpacity={0.3} />
                          <stop offset="95%" stopColor="#3b82f6" stopOpacity={0} />
                        </linearGradient>
                      </defs>
                      <CartesianGrid strokeDasharray="3 3" className="stroke-muted" />
                      <XAxis dataKey="time" className="text-xs" />
                      <YAxis className="text-xs" domain={[0, 100]} />
                      <Tooltip
                        contentStyle={{
                          backgroundColor: 'hsl(var(--card))',
                          border: '1px solid hsl(var(--border))',
                        }}
                        formatter={(value: number) => `${value.toFixed(1)}%`}
                      />
                      <Area
                        type="monotone"
                        dataKey="value"
                        stroke="#3b82f6"
                        fillOpacity={1}
                        fill="url(#cpuGradient)"
                      />
                    </AreaChart>
                  </ResponsiveContainer>
                ) : (
                  <div className="h-[300px] flex items-center justify-center text-muted-foreground">
                    No data available
                  </div>
                )}
              </CardContent>
            </Card>

            {/* Memory Chart */}
            <Card>
              <CardHeader>
                <CardTitle className="flex items-center gap-2">
                  <MemoryStick className="h-5 w-5" />
                  Memory Usage
                </CardTitle>
              </CardHeader>
              <CardContent>
                {memoryData.length > 0 ? (
                  <ResponsiveContainer width="100%" height={300}>
                    <AreaChart data={memoryData}>
                      <defs>
                        <linearGradient id="memGradient" x1="0" y1="0" x2="0" y2="1">
                          <stop offset="5%" stopColor="#8b5cf6" stopOpacity={0.3} />
                          <stop offset="95%" stopColor="#8b5cf6" stopOpacity={0} />
                        </linearGradient>
                      </defs>
                      <CartesianGrid strokeDasharray="3 3" className="stroke-muted" />
                      <XAxis dataKey="time" className="text-xs" />
                      <YAxis className="text-xs" domain={[0, 100]} />
                      <Tooltip
                        contentStyle={{
                          backgroundColor: 'hsl(var(--card))',
                          border: '1px solid hsl(var(--border))',
                        }}
                        formatter={(value: number, name: string) => {
                          if (name === 'usedPercent') return `${value.toFixed(1)}%`
                          return formatBytes(value)
                        }}
                        labelFormatter={(label) => `Time: ${label}`}
                      />
                      <Area
                        type="monotone"
                        dataKey="usedPercent"
                        stroke="#8b5cf6"
                        fillOpacity={1}
                        fill="url(#memGradient)"
                      />
                    </AreaChart>
                  </ResponsiveContainer>
                ) : (
                  <div className="h-[300px] flex items-center justify-center text-muted-foreground">
                    No data available
                  </div>
                )}
              </CardContent>
            </Card>

            {/* Disk Usage Chart */}
            <Card>
              <CardHeader>
                <CardTitle className="flex items-center gap-2">
                  <HardDrive className="h-5 w-5" />
                  Disk Usage
                </CardTitle>
              </CardHeader>
              <CardContent>
                {diskData.length > 0 ? (
                  <ResponsiveContainer width="100%" height={300}>
                    <AreaChart data={diskData}>
                      <defs>
                        <linearGradient id="diskGradient" x1="0" y1="0" x2="0" y2="1">
                          <stop offset="5%" stopColor="#f59e0b" stopOpacity={0.3} />
                          <stop offset="95%" stopColor="#f59e0b" stopOpacity={0} />
                        </linearGradient>
                      </defs>
                      <CartesianGrid strokeDasharray="3 3" className="stroke-muted" />
                      <XAxis dataKey="time" className="text-xs" />
                      <YAxis className="text-xs" domain={[0, 100]} />
                      <Tooltip
                        contentStyle={{
                          backgroundColor: 'hsl(var(--card))',
                          border: '1px solid hsl(var(--border))',
                        }}
                        formatter={(value: number, name: string) => {
                          if (name === 'usedPercent') return `${value.toFixed(1)}%`
                          return formatBytes(value)
                        }}
                      />
                      <Area
                        type="monotone"
                        dataKey="usedPercent"
                        stroke="#f59e0b"
                        fillOpacity={1}
                        fill="url(#diskGradient)"
                      />
                    </AreaChart>
                  </ResponsiveContainer>
                ) : (
                  <div className="h-[300px] flex items-center justify-center text-muted-foreground">
                    No data available
                  </div>
                )}
              </CardContent>
            </Card>

            {/* Load Average Chart */}
            <Card>
              <CardHeader>
                <CardTitle className="flex items-center gap-2">
                  <Activity className="h-5 w-5" />
                  Load Average
                </CardTitle>
              </CardHeader>
              <CardContent>
                {loadData.length > 0 ? (
                  <ResponsiveContainer width="100%" height={300}>
                    <LineChart data={loadData}>
                      <CartesianGrid strokeDasharray="3 3" className="stroke-muted" />
                      <XAxis dataKey="time" className="text-xs" />
                      <YAxis className="text-xs" />
                      <Tooltip
                        contentStyle={{
                          backgroundColor: 'hsl(var(--card))',
                          border: '1px solid hsl(var(--border))',
                        }}
                        formatter={(value: number) => value.toFixed(2)}
                      />
                      <Legend />
                      <Line
                        type="monotone"
                        dataKey="load1"
                        stroke="#ef4444"
                        dot={false}
                        name="1 min"
                      />
                      <Line
                        type="monotone"
                        dataKey="load5"
                        stroke="#f59e0b"
                        dot={false}
                        name="5 min"
                      />
                      <Line
                        type="monotone"
                        dataKey="load15"
                        stroke="#10b981"
                        dot={false}
                        name="15 min"
                      />
                    </LineChart>
                  </ResponsiveContainer>
                ) : (
                  <div className="h-[300px] flex items-center justify-center text-muted-foreground">
                    No data available
                  </div>
                )}
              </CardContent>
            </Card>

            {/* Temperature Chart (if available) */}
            {temperatureData.length > 0 && (
              <Card>
                <CardHeader>
                  <CardTitle className="flex items-center gap-2">
                    <Thermometer className="h-5 w-5" />
                    Temperature
                  </CardTitle>
                </CardHeader>
                <CardContent>
                  <ResponsiveContainer width="100%" height={300}>
                    <LineChart data={temperatureData}>
                      <CartesianGrid strokeDasharray="3 3" className="stroke-muted" />
                      <XAxis dataKey="time" className="text-xs" />
                      <YAxis className="text-xs" />
                      <Tooltip
                        contentStyle={{
                          backgroundColor: 'hsl(var(--card))',
                          border: '1px solid hsl(var(--border))',
                        }}
                        formatter={(value: number) => `${value.toFixed(1)}°C`}
                      />
                      <Line type="monotone" dataKey="temp" stroke="#ef4444" dot={false} />
                    </LineChart>
                  </ResponsiveContainer>
                </CardContent>
              </Card>
            )}

            {/* GPU Chart (if available) */}
            {gpuData.length > 0 && gpuData.some((d) => d.gpu > 0 || d.memory > 0) && (
              <Card>
                <CardHeader>
                  <CardTitle className="flex items-center gap-2">
                    <Cpu className="h-5 w-5" />
                    GPU Usage
                  </CardTitle>
                </CardHeader>
                <CardContent>
                  <ResponsiveContainer width="100%" height={300}>
                    <LineChart data={gpuData}>
                      <CartesianGrid strokeDasharray="3 3" className="stroke-muted" />
                      <XAxis dataKey="time" className="text-xs" />
                      <YAxis className="text-xs" domain={[0, 100]} />
                      <Tooltip
                        contentStyle={{
                          backgroundColor: 'hsl(var(--card))',
                          border: '1px solid hsl(var(--border))',
                        }}
                        formatter={(value: number) => `${value.toFixed(1)}%`}
                      />
                      <Legend />
                      <Line
                        type="monotone"
                        dataKey="gpu"
                        stroke="#10b981"
                        dot={false}
                        name="GPU"
                      />
                      <Line
                        type="monotone"
                        dataKey="memory"
                        stroke="#8b5cf6"
                        dot={false}
                        name="Memory"
                      />
                    </LineChart>
                  </ResponsiveContainer>
                </CardContent>
              </Card>
            )}
          </TabsContent>

          <TabsContent value="containers" className="space-y-6">
            {containers.length > 0 ? (
              <div className="space-y-4">
                {containers.map((container) => (
                  <Card key={container.id}>
                    <CardHeader>
                      <div className="flex items-center justify-between">
                        <CardTitle className="text-lg">{container.name}</CardTitle>
                        <Badge
                          variant={
                            container.status === 'running' ? 'default' : 'secondary'
                          }
                        >
                          {container.status}
                        </Badge>
                      </div>
                      <p className="text-sm text-muted-foreground">{container.image}</p>
                    </CardHeader>
                    <CardContent>
                      <div className="grid grid-cols-2 md:grid-cols-4 gap-4">
                        <div>
                          <div className="text-sm text-muted-foreground">CPU</div>
                          <div className="text-lg font-semibold">
                            {formatPercent(container.cpuPercent)}
                          </div>
                        </div>
                        <div>
                          <div className="text-sm text-muted-foreground">Memory</div>
                          <div className="text-lg font-semibold">
                            {formatBytesShort(container.memUsed)} /{' '}
                            {formatBytesShort(container.memLimit)}
                          </div>
                        </div>
                        <div>
                          <div className="text-sm text-muted-foreground">Network In</div>
                          <div className="text-lg font-semibold">
                            {formatBytesShort(container.netRecvBytes)}
                          </div>
                        </div>
                        <div>
                          <div className="text-sm text-muted-foreground">Network Out</div>
                          <div className="text-lg font-semibold">
                            {formatBytesShort(container.netSentBytes)}
                          </div>
                        </div>
                      </div>
                    </CardContent>
                  </Card>
                ))}
              </div>
            ) : (
              <Card>
                <CardContent className="py-12 text-center text-muted-foreground">
                  No containers detected on this system
                </CardContent>
              </Card>
            )}
          </TabsContent>

          <TabsContent value="disk-io" className="space-y-6">
            <Card>
              <CardHeader>
                <CardTitle className="flex items-center gap-2">
                  <HardDrive className="h-5 w-5" />
                  Disk I/O
                </CardTitle>
              </CardHeader>
              <CardContent>
                {diskIoData.length > 0 ? (
                  <ResponsiveContainer width="100%" height={400}>
                    <LineChart data={diskIoData}>
                      <CartesianGrid strokeDasharray="3 3" className="stroke-muted" />
                      <XAxis dataKey="time" className="text-xs" />
                      <YAxis className="text-xs" />
                      <Tooltip
                        contentStyle={{
                          backgroundColor: 'hsl(var(--card))',
                          border: '1px solid hsl(var(--border))',
                        }}
                        formatter={(value: number) => formatBytes(value)}
                      />
                      <Legend />
                      <Line
                        type="monotone"
                        dataKey="read"
                        stroke="#3b82f6"
                        dot={false}
                        name="Read"
                      />
                      <Line
                        type="monotone"
                        dataKey="write"
                        stroke="#10b981"
                        dot={false}
                        name="Write"
                      />
                    </LineChart>
                  </ResponsiveContainer>
                ) : (
                  <div className="h-[400px] flex items-center justify-center text-muted-foreground">
                    No data available
                  </div>
                )}
              </CardContent>
            </Card>
          </TabsContent>

          <TabsContent value="network" className="space-y-6">
            <Card>
              <CardHeader>
                <CardTitle className="flex items-center gap-2">
                  <Network className="h-5 w-5" />
                  Network Throughput
                </CardTitle>
              </CardHeader>
              <CardContent>
                {networkData.length > 0 ? (
                  <ResponsiveContainer width="100%" height={400}>
                    <LineChart data={networkData}>
                      <CartesianGrid strokeDasharray="3 3" className="stroke-muted" />
                      <XAxis dataKey="time" className="text-xs" />
                      <YAxis className="text-xs" />
                      <Tooltip
                        contentStyle={{
                          backgroundColor: 'hsl(var(--card))',
                          border: '1px solid hsl(var(--border))',
                        }}
                        formatter={(value: number) => formatBytes(value)}
                      />
                      <Legend />
                      <Line
                        type="monotone"
                        dataKey="recv"
                        stroke="#8b5cf6"
                        dot={false}
                        name="Received"
                      />
                      <Line
                        type="monotone"
                        dataKey="sent"
                        stroke="#f59e0b"
                        dot={false}
                        name="Sent"
                      />
                    </LineChart>
                  </ResponsiveContainer>
                ) : (
                  <div className="h-[400px] flex items-center justify-center text-muted-foreground">
                    No data available
                  </div>
                )}
              </CardContent>
            </Card>
          </TabsContent>

          <TabsContent value="alerts" className="space-y-6">
            <AlertsTab systemId={systemId} />
          </TabsContent>
        </Tabs>
      </div>
    </div>
  )
}
