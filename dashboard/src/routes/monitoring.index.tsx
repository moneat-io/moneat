import {createFileRoute, Link, redirect} from '@tanstack/react-router'
import {useMutation, useQuery, useQueryClient} from '@tanstack/react-query'
import {api, type MonitorSystemWithMetrics} from '@/lib/api'
import {cn, formatRelativeTime} from '@/lib/utils'
import {Badge} from '@/components/ui/badge'
import {Button} from '@/components/ui/button'
import {Card, CardContent, CardHeader, CardTitle} from '@/components/ui/card'
import {
  Dialog,
  DialogContent,
  DialogDescription,
  DialogFooter,
  DialogHeader,
  DialogTitle,
} from '@/components/ui/dialog'
import {Input} from '@/components/ui/input'
import {Label} from '@/components/ui/label'
import {Separator} from '@/components/ui/separator'
import {Switch} from '@/components/ui/switch'
import {Table, TableBody, TableCell, TableHead, TableHeader, TableRow} from '@/components/ui/table'
import {Tooltip, TooltipContent, TooltipProvider, TooltipTrigger,} from '@/components/ui/tooltip'
import {
  Activity,
  ArrowUpRight,
  Check,
  CheckCircle2,
  Copy,
  Cpu,
  HardDrive,
  LayoutGrid,
  MemoryStick,
  Network,
  Plus,
  Rows3,
  Server,
  ServerOff,
  Terminal,
  Thermometer,
  Trash2,
  Zap,
} from 'lucide-react'
import {useEffect, useState} from 'react'
import {useToast} from '@/hooks/use-toast'
import {Prism as SyntaxHighlighter} from 'react-syntax-highlighter'
import {oneDark, oneLight} from 'react-syntax-highlighter/dist/esm/styles/prism'

export const Route = createFileRoute('/monitoring/')({
  beforeLoad: () => {
    if (!api.isAuthenticated()) {
      throw redirect({to: '/login'})
    }
  },
  component: MonitoringListPage,
})

function formatBytes(bytes: number | undefined): string {
  if (bytes === undefined) return 'N/A'
  const sizes = ['B', 'KB', 'MB', 'GB', 'TB']
  if (bytes === 0) return '0 B'
  const i = Math.floor(Math.log(bytes) / Math.log(1024))
  return `${(bytes / Math.pow(1024, i)).toFixed(1)} ${sizes[i]}`
}

function formatBytesPerSec(bytes: number | undefined): string {
  if (bytes === undefined) return 'N/A'
  return `${formatBytes(bytes)}/s`
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

function getPercentBarColor(value: number | undefined): string {
  if (value === undefined) return 'bg-muted-foreground/30'
  if (value >= 90) return 'bg-red-500'
  if (value >= 75) return 'bg-orange-500'
  if (value >= 50) return 'bg-yellow-500'
  return 'bg-emerald-500'
}

type MonitoringViewMode = 'cards' | 'compact'

const MONITORING_VIEW_MODE_STORAGE_KEY = 'monitoring-systems-view-mode'

function getInitialMonitoringViewMode(): MonitoringViewMode {
  if (typeof window === 'undefined') return 'cards'
  return window.localStorage.getItem(MONITORING_VIEW_MODE_STORAGE_KEY) === 'compact' ? 'compact' : 'cards'
}

function MiniGauge({value, size = 40}: {value: number | undefined; size?: number}) {
  const percent = value || 0
  const radius = (size - 6) / 2
  const circumference = 2 * Math.PI * radius
  const strokeDashoffset = circumference - (Math.min(percent, 100) / 100) * circumference
  const colorClass = getPercentColor(percent)

  // Map tailwind color classes to actual stroke colors
  const strokeColor =
    percent >= 90
      ? '#ef4444'
      : percent >= 75
        ? '#f97316'
        : percent >= 50
          ? '#eab308'
          : '#10b981'

  return (
    <div className="relative" style={{width: size, height: size}}>
      <svg
        width={size}
        height={size}
        viewBox={`0 0 ${size} ${size}`}
        className="-rotate-90"
      >
        <circle
          cx={size / 2}
          cy={size / 2}
          r={radius}
          fill="none"
          stroke="currentColor"
          strokeWidth={3}
          className="text-muted/50"
        />
        <circle
          cx={size / 2}
          cy={size / 2}
          r={radius}
          fill="none"
          stroke={strokeColor}
          strokeWidth={3}
          strokeLinecap="round"
          strokeDasharray={circumference}
          strokeDashoffset={strokeDashoffset}
          className="transition-all duration-700 ease-out"
        />
      </svg>
      <div className={`absolute inset-0 flex items-center justify-center text-[10px] font-bold ${colorClass}`}>
        {value !== undefined ? `${Math.round(percent)}` : '—'}
      </div>
    </div>
  )
}


function AddSystemButton({onClick}: {onClick: () => void}) {
  return (
    <Button className="gap-2" onClick={onClick}>
      <Plus className="h-4 w-4" />
      Add System
    </Button>
  )
}

const DOCKER_SOCKET_REFERENCE_REGEX = /\/var\/run\/docker\.sock/
const SOCK_PATH_ASSIGNMENT_REGEX = /^\s*SOCK_PATH=/
const DOCKER_HOST_LINE_REGEX = /^\s*-e\s+DOCKER_HOST="unix:\/\/\/var\/run\/docker\.sock"\s*\\?\s*$/
const SOCK_PATH_LOOKUP_LINE = `SOCK_PATH="$(docker context inspect --format '{{.Endpoints.docker.Host}}' 2>/dev/null)"`
const SOCK_PATH_STRIP_UNIX_LINE = 'SOCK_PATH="${SOCK_PATH#unix://}"'
const SOCK_PATH_MAC_NORMALIZE_LINE = `case "$SOCK_PATH" in /Users/*/.docker/run/docker.sock) SOCK_PATH="/var/run/docker.sock" ;; esac`
const SOCK_PATH_FALLBACK_LINE = '[ -S "$SOCK_PATH" ] || SOCK_PATH="/var/run/docker.sock"'
const AGENT_ROOT_USER_LINE = '--user 0:0 \\'

function getInstallCommand(baseCommand: string, enableContainerMonitoring: boolean): string {
  const lines = baseCommand.split('\n')
  const withoutDockerSocket = lines.filter(
    (line) =>
      !SOCK_PATH_ASSIGNMENT_REGEX.test(line) &&
      !DOCKER_SOCKET_REFERENCE_REGEX.test(line) &&
      line.trim() !== '--user 0:0 \\' &&
      !DOCKER_HOST_LINE_REGEX.test(line)
  )

  if (!enableContainerMonitoring) {
    return withoutDockerSocket.join('\n')
  }

  const runLineIndex = withoutDockerSocket.findIndex((line) => line.trim().startsWith('docker run '))
  const insertLookupAt = runLineIndex >= 0 ? runLineIndex : 0

  withoutDockerSocket.splice(
    insertLookupAt,
    0,
    SOCK_PATH_LOOKUP_LINE,
    SOCK_PATH_STRIP_UNIX_LINE,
    SOCK_PATH_MAC_NORMALIZE_LINE,
    SOCK_PATH_FALLBACK_LINE,
    ''
  )

  const updatedRunLineIndex = withoutDockerSocket.findIndex((line) => line.trim().startsWith('docker run '))
  const userLineInsertAt = updatedRunLineIndex >= 0 ? updatedRunLineIndex + 1 : 4
  const userLineIndentation = (withoutDockerSocket[userLineInsertAt - 1]?.match(/^\s*/) || ['  '])[0]
  withoutDockerSocket.splice(userLineInsertAt, 0, `${userLineIndentation}${AGENT_ROOT_USER_LINE}`)

  const restartLineIndex = withoutDockerSocket.findIndex(
    (line, idx) => idx > userLineInsertAt && line.trim().startsWith('--restart ')
  )
  const insertMountAt = restartLineIndex >= 0 ? restartLineIndex + 1 : userLineInsertAt + 1
  const indentation = (withoutDockerSocket[insertMountAt - 1]?.match(/^\s*/) || ['  '])[0]

  withoutDockerSocket.splice(insertMountAt, 0, `${indentation}-v "\${SOCK_PATH}:/var/run/docker.sock:ro" \\`)

  const keyEnvLineIndex = withoutDockerSocket.findIndex((line) => line.trim().startsWith('-e MONEAT_KEY='))
  const imageLineIndex = withoutDockerSocket.findIndex((line) => line.trim().startsWith('adrianelder/moneat-agent:'))
  const insertDockerHostAt = keyEnvLineIndex >= 0 ? keyEnvLineIndex : imageLineIndex >= 0 ? imageLineIndex : withoutDockerSocket.length
  const envIndentation = (withoutDockerSocket[Math.max(insertDockerHostAt - 1, 0)]?.match(/^\s*/) || ['  '])[0]
  withoutDockerSocket.splice(insertDockerHostAt, 0, `${envIndentation}-e DOCKER_HOST="unix:///var/run/docker.sock" \\`)

  return withoutDockerSocket.join('\n')
}

function AddSystemDialog({isOpen, setIsOpen}: {isOpen: boolean; setIsOpen: (v: boolean) => void}) {
  const {toast} = useToast()
  const queryClient = useQueryClient()
  const [systemName, setSystemName] = useState('')
  const [createdSystem, setCreatedSystem] = useState<{id: string; dockerCommand: string} | null>(null)
  const [containerMonitoringEnabled, setContainerMonitoringEnabled] = useState(true)
  const [isDark, setIsDark] = useState(true)
  const [copied, setCopied] = useState(false)

  useEffect(() => {
    const root = document.documentElement
    setIsDark(root.classList.contains('dark'))
    const observer = new MutationObserver(() => setIsDark(root.classList.contains('dark')))
    observer.observe(root, {attributes: true, attributeFilter: ['class']})
    return () => observer.disconnect()
  }, [])

  const createMutation = useMutation({
    mutationFn: (name: string) => api.createMonitorSystem(name),
    onSuccess: (data) => {
      queryClient.invalidateQueries({queryKey: ['monitor-systems']})
      setCreatedSystem({id: data.system.id, dockerCommand: data.docker_command})
      setSystemName('')
    },
    onError: () => {
      toast({
        title: 'Error',
        description: 'Failed to create system. Please try again.',
        variant: 'destructive',
      })
    },
  })

  const handleSubmit = (e: React.FormEvent) => {
    e.preventDefault()
    if (systemName.trim()) {
      createMutation.mutate(systemName.trim())
    }
  }

  const handleClose = () => {
    setIsOpen(false)
    setTimeout(() => {
      setCreatedSystem(null)
      setSystemName('')
      setContainerMonitoringEnabled(true)
      setCopied(false)
    }, 200)
  }

  const handleCopyCommand = async () => {
    if (createdSystem) {
      await navigator.clipboard.writeText(getInstallCommand(createdSystem.dockerCommand, containerMonitoringEnabled))
      setCopied(true)
      toast({title: 'Copied!', description: 'Docker command copied to clipboard'})
      setTimeout(() => setCopied(false), 2000)
    }
  }

  return (
    <Dialog open={isOpen} onOpenChange={(open) => {
      if (!open) {
        handleClose()
      } else {
        setIsOpen(true)
      }
    }}>
      <DialogContent className="max-w-2xl">
        {!createdSystem ? (
          <>
            <DialogHeader>
              <DialogTitle className="flex items-center gap-2">
                <Server className="h-5 w-5 text-blue-500" />
                Add New System
              </DialogTitle>
              <DialogDescription>
                Enter a name for your system to get started with monitoring.
              </DialogDescription>
            </DialogHeader>
            <form onSubmit={handleSubmit}>
              <div className="space-y-4 py-4">
                <div className="space-y-2">
                  <Label htmlFor="name">System Name</Label>
                  <Input
                    id="name"
                    placeholder="e.g., Production Server, Dev Machine"
                    value={systemName}
                    onChange={(e) => setSystemName(e.target.value)}
                    required
                  />
                </div>
              </div>
              <DialogFooter>
                <Button type="button" variant="outline" onClick={handleClose}>
                  Cancel
                </Button>
                <Button type="submit" disabled={createMutation.isPending || !systemName.trim()}>
                  {createMutation.isPending ? 'Creating...' : 'Create System'}
                </Button>
              </DialogFooter>
            </form>
          </>
        ) : (
          <>
            <DialogHeader>
              <DialogTitle className="flex items-center gap-2">
                <CheckCircle2 className="h-5 w-5 text-emerald-500" />
                System Created Successfully
              </DialogTitle>
              <DialogDescription>
                Install the monitoring agent on your server using the command below.
              </DialogDescription>
            </DialogHeader>
            <div className="space-y-5 py-4">
              {/* Docker Command Section */}
              <div className="space-y-2">
                <Label className="flex items-center gap-2 text-sm font-medium">
                  <Terminal className="h-3.5 w-3.5 text-blue-500" />
                  Installation Command
                </Label>
                <div className="flex items-start justify-between gap-4 rounded-lg border bg-muted/20 px-3 py-2.5">
                  <div className="space-y-0.5">
                    <p className="text-sm font-medium">Enable container monitoring</p>
                    <p className="text-xs text-muted-foreground">
                      Auto-detects Docker socket path and uses Docker Engine API via <code className="rounded bg-muted px-1 py-0.5">DOCKER_HOST</code>.
                    </p>
                  </div>
                  <Switch
                    checked={containerMonitoringEnabled}
                    onCheckedChange={setContainerMonitoringEnabled}
                    aria-label="Toggle container monitoring"
                  />
                </div>
                <div className="relative group">
                  <div className="overflow-hidden rounded-lg border border-zinc-800 bg-zinc-950 dark:bg-zinc-900">
                    <SyntaxHighlighter
                      language="bash"
                      style={isDark ? oneDark : oneLight}
                      customStyle={{
                        margin: 0,
                        padding: '1rem',
                        paddingRight: '5rem',
                        fontSize: '0.8125rem',
                        lineHeight: 1.6,
                        background: 'transparent',
                      }}
                      codeTagProps={{
                        style: {
                          fontFamily: 'ui-monospace, SFMono-Regular, "SF Mono", Menlo, Consolas, monospace',
                        },
                      }}
                      showLineNumbers={false}
                      wrapLongLines={false}
                    >
                      {getInstallCommand(createdSystem.dockerCommand, containerMonitoringEnabled)}
                    </SyntaxHighlighter>
                  </div>
                  <Button
                    size="sm"
                    variant="secondary"
                    className="absolute top-2.5 right-2.5 h-8 gap-1.5 text-xs"
                    onClick={handleCopyCommand}
                  >
                    {copied ? (
                      <>
                        <Check className="h-3.5 w-3.5 text-emerald-500" />
                        Copied
                      </>
                    ) : (
                      <>
                        <Copy className="h-3.5 w-3.5" />
                        Copy
                      </>
                    )}
                  </Button>
                </div>
              </div>

              {/* Steps */}
              <div className="rounded-lg bg-blue-500/5 border border-blue-500/20 p-4 space-y-3">
                <h4 className="font-medium text-sm text-blue-700 dark:text-blue-300 flex items-center gap-2">
                  <Zap className="h-4 w-4" />
                  Quick Start
                </h4>
                <ol className="text-sm text-blue-600 dark:text-blue-300/80 space-y-2 list-none">
                  <li className="flex items-start gap-2.5">
                    <span className="flex items-center justify-center h-5 w-5 rounded-full bg-blue-500/15 text-blue-600 dark:text-blue-400 text-xs font-bold shrink-0 mt-0.5">1</span>
                    <span>Copy the command above</span>
                  </li>
                  <li className="flex items-start gap-2.5">
                    <span className="flex items-center justify-center h-5 w-5 rounded-full bg-blue-500/15 text-blue-600 dark:text-blue-400 text-xs font-bold shrink-0 mt-0.5">2</span>
                    <span>SSH into your server</span>
                  </li>
                  <li className="flex items-start gap-2.5">
                    <span className="flex items-center justify-center h-5 w-5 rounded-full bg-blue-500/15 text-blue-600 dark:text-blue-400 text-xs font-bold shrink-0 mt-0.5">3</span>
                    <span>Paste and run the command</span>
                  </li>
                  <li className="flex items-start gap-2.5">
                    <span className="flex items-center justify-center h-5 w-5 rounded-full bg-blue-500/15 text-blue-600 dark:text-blue-400 text-xs font-bold shrink-0 mt-0.5">4</span>
                    <span>Metrics will appear within seconds</span>
                  </li>
                </ol>
              </div>
            </div>
            <DialogFooter>
              <Button onClick={handleClose} className="w-full sm:w-auto">
                Done
              </Button>
            </DialogFooter>
          </>
        )}
      </DialogContent>
    </Dialog>
  )
}

function SystemCard({system, onDelete}: {system: any; onDelete: (id: string, name: string) => void}) {
  const metrics = system.latest_metrics
  const memPercent =
    metrics?.mem_used && metrics?.mem_total ? (metrics.mem_used / metrics.mem_total) * 100 : undefined
  const diskPercent =
    metrics?.disk_used && metrics?.disk_total ? (metrics.disk_used / metrics.disk_total) * 100 : undefined
  const isOnline = system.status === 'up'

  return (
    <Link
      to="/monitoring/$systemId"
      params={{systemId: system.id}}
      className="block group"
    >
      <Card className="relative overflow-hidden transition-all duration-200 hover:shadow-lg hover:shadow-primary/5 hover:border-primary/20 group-hover:-translate-y-0.5">
        {/* Top color accent bar */}
        <div
          className={`absolute top-0 left-0 right-0 h-1 ${
            isOnline ? 'bg-gradient-to-r from-emerald-500 to-teal-500' : system.status === 'down' ? 'bg-gradient-to-r from-red-500 to-rose-500' : 'bg-gradient-to-r from-yellow-500 to-amber-500'
          }`}
        />

        <CardHeader className="pb-3 pt-5">
          <div className="flex items-start justify-between">
            <div className="flex items-center gap-3 min-w-0">
              <div
                className={`flex items-center justify-center h-10 w-10 rounded-lg shrink-0 ${
                  isOnline
                    ? 'bg-emerald-500/10 text-emerald-600 dark:text-emerald-400'
                    : system.status === 'down'
                      ? 'bg-red-500/10 text-red-600 dark:text-red-400'
                      : 'bg-yellow-500/10 text-yellow-600 dark:text-yellow-400'
                }`}
              >
                {isOnline ? <Server className="h-5 w-5" /> : <ServerOff className="h-5 w-5" />}
              </div>
              <div className="min-w-0">
                <CardTitle className="text-base truncate">{system.name}</CardTitle>
                {system.host && (
                  <p className="text-xs text-muted-foreground truncate mt-0.5">{system.host}</p>
                )}
              </div>
            </div>
            <div className="flex items-center gap-2 shrink-0">
              <Badge
                variant={isOnline ? 'default' : 'secondary'}
                className={`text-xs ${
                  isOnline
                    ? 'bg-emerald-500/15 text-emerald-700 dark:text-emerald-300 hover:bg-emerald-500/20 border-emerald-500/20'
                    : system.status === 'down'
                      ? 'bg-red-500/15 text-red-700 dark:text-red-300 hover:bg-red-500/20 border-red-500/20'
                      : ''
                }`}
              >
                <div
                  className={`h-1.5 w-1.5 rounded-full mr-1.5 ${
                    isOnline ? 'bg-emerald-500 animate-pulse' : system.status === 'down' ? 'bg-red-500' : 'bg-yellow-500'
                  }`}
                />
                {system.status}
              </Badge>
              <Button
                size="sm"
                variant="ghost"
                className="h-7 w-7 p-0 opacity-0 group-hover:opacity-100 transition-opacity"
                onClick={(e) => {
                  e.preventDefault()
                  e.stopPropagation()
                  onDelete(system.id, system.name)
                }}
              >
                <Trash2 className="h-3.5 w-3.5 text-destructive" />
              </Button>
            </div>
          </div>
        </CardHeader>

        <CardContent className="space-y-4 pb-5">
          {/* Primary Metrics - Gauges */}
          <div className="grid grid-cols-3 gap-3">
            <TooltipProvider delayDuration={200}>
              <Tooltip>
                <TooltipTrigger asChild>
                  <div className="flex flex-col items-center gap-1.5 p-2 rounded-lg bg-muted/40 hover:bg-muted/60 transition-colors">
                    <MiniGauge value={metrics?.cpu_percent} />
                    <div className="flex items-center gap-1 text-xs text-muted-foreground">
                      <Cpu className="h-3 w-3" />
                      <span>CPU</span>
                    </div>
                  </div>
                </TooltipTrigger>
                <TooltipContent>
                  <p>CPU: {formatPercent(metrics?.cpu_percent)}</p>
                </TooltipContent>
              </Tooltip>
            </TooltipProvider>

            <TooltipProvider delayDuration={200}>
              <Tooltip>
                <TooltipTrigger asChild>
                  <div className="flex flex-col items-center gap-1.5 p-2 rounded-lg bg-muted/40 hover:bg-muted/60 transition-colors">
                    <MiniGauge value={memPercent} />
                    <div className="flex items-center gap-1 text-xs text-muted-foreground">
                      <MemoryStick className="h-3 w-3" />
                      <span>RAM</span>
                    </div>
                  </div>
                </TooltipTrigger>
                <TooltipContent>
                  <p>Memory: {formatPercent(memPercent)}</p>
                  <p className="text-muted-foreground">
                    {formatBytes(metrics?.mem_used)} / {formatBytes(metrics?.mem_total)}
                  </p>
                </TooltipContent>
              </Tooltip>
            </TooltipProvider>

            <TooltipProvider delayDuration={200}>
              <Tooltip>
                <TooltipTrigger asChild>
                  <div className="flex flex-col items-center gap-1.5 p-2 rounded-lg bg-muted/40 hover:bg-muted/60 transition-colors">
                    <MiniGauge value={diskPercent} />
                    <div className="flex items-center gap-1 text-xs text-muted-foreground">
                      <HardDrive className="h-3 w-3" />
                      <span>Disk</span>
                    </div>
                  </div>
                </TooltipTrigger>
                <TooltipContent>
                  <p>Disk: {formatPercent(diskPercent)}</p>
                  <p className="text-muted-foreground">
                    {formatBytes(metrics?.disk_used)} / {formatBytes(metrics?.disk_total)}
                  </p>
                </TooltipContent>
              </Tooltip>
            </TooltipProvider>
          </div>

          <Separator />

          {/* Secondary Metrics */}
          <div className="grid grid-cols-2 gap-x-4 gap-y-2.5 text-sm">
            <div className="flex items-center justify-between">
              <div className="flex items-center gap-1.5 text-muted-foreground">
                <Activity className="h-3.5 w-3.5 text-violet-500" />
                <span className="text-xs">Load</span>
              </div>
              <span className="font-mono text-xs font-medium">
                {metrics?.load_1?.toFixed(2) || '—'}
              </span>
            </div>

            <div className="flex items-center justify-between">
              <div className="flex items-center gap-1.5 text-muted-foreground">
                <Thermometer className="h-3.5 w-3.5 text-rose-500" />
                <span className="text-xs">Temp</span>
              </div>
              <span className="font-mono text-xs font-medium">
                {metrics?.temp_max ? `${metrics.temp_max.toFixed(0)}°C` : '—'}
              </span>
            </div>

            <div className="flex items-center justify-between">
              <div className="flex items-center gap-1.5 text-muted-foreground">
                <Network className="h-3.5 w-3.5 text-sky-500" />
                <span className="text-xs">Net In</span>
              </div>
              <span className="font-mono text-xs font-medium">
                {formatBytesPerSec(metrics?.net_recv_bytes)}
              </span>
            </div>

            <div className="flex items-center justify-between">
              <div className="flex items-center gap-1.5 text-muted-foreground">
                <Network className="h-3.5 w-3.5 text-indigo-500 rotate-180" />
                <span className="text-xs">Net Out</span>
              </div>
              <span className="font-mono text-xs font-medium">
                {formatBytesPerSec(metrics?.net_sent_bytes)}
              </span>
            </div>
          </div>

          {/* Footer */}
          <div className="flex items-center justify-between pt-1">
            <span className="text-xs text-muted-foreground">
              {system.lastSeenAt ? formatRelativeTime(system.lastSeenAt) : 'Never seen'}
            </span>
            <ArrowUpRight className="h-3.5 w-3.5 text-muted-foreground opacity-0 group-hover:opacity-100 transition-opacity" />
          </div>
        </CardContent>
      </Card>
    </Link>
  )
}

function SystemsCompactTable({
  systems,
  onDelete,
}: {
  systems: MonitorSystemWithMetrics[]
  onDelete: (id: string, name: string) => void
}) {
  return (
    <Card className="overflow-hidden border-border/60 shadow-sm">
      <CardContent className="p-0">
        <Table className="min-w-[860px]">
          <TableHeader>
            <TableRow className="hover:bg-transparent bg-muted/30">
              <TableHead className="pl-4">System</TableHead>
              <TableHead>Status</TableHead>
              <TableHead>CPU</TableHead>
              <TableHead className="hidden sm:table-cell">Memory</TableHead>
              <TableHead className="hidden md:table-cell">Disk</TableHead>
              <TableHead className="hidden lg:table-cell">Network</TableHead>
              <TableHead className="text-right">Last Seen</TableHead>
              <TableHead className="pr-4 text-right w-[80px]">Action</TableHead>
            </TableRow>
          </TableHeader>
          <TableBody>
            {systems.map((system) => {
              const metrics = system.latest_metrics
              const memPercent =
                metrics?.mem_used && metrics?.mem_total ? (metrics.mem_used / metrics.mem_total) * 100 : undefined
              const diskPercent =
                metrics?.disk_used && metrics?.disk_total ? (metrics.disk_used / metrics.disk_total) * 100 : undefined
              const cpuPercent = metrics?.cpu_percent
              const isOnline = system.status === 'up'

              return (
                <TableRow key={system.id} className="group">
                  <TableCell className="pl-4">
                    <div className="flex items-center gap-3 min-w-0">
                      <div
                        className={cn(
                          'flex h-8 w-8 shrink-0 items-center justify-center rounded-md',
                          isOnline
                            ? 'bg-emerald-500/10 text-emerald-600 dark:text-emerald-400'
                            : system.status === 'down'
                              ? 'bg-red-500/10 text-red-600 dark:text-red-400'
                              : 'bg-yellow-500/10 text-yellow-600 dark:text-yellow-400'
                        )}
                      >
                        {isOnline ? <Server className="h-4 w-4" /> : <ServerOff className="h-4 w-4" />}
                      </div>
                      <div className="min-w-0">
                        <Link
                          to="/monitoring/$systemId"
                          params={{systemId: system.id}}
                          className="inline-flex items-center gap-1 font-medium hover:underline underline-offset-4"
                        >
                          <span className="truncate max-w-[230px]">{system.name}</span>
                          <ArrowUpRight className="h-3.5 w-3.5 text-muted-foreground opacity-0 transition-opacity group-hover:opacity-100" />
                        </Link>
                        <p className="truncate text-xs text-muted-foreground mt-0.5">
                          {system.host || `ID: ${system.id}`}
                        </p>
                      </div>
                    </div>
                  </TableCell>

                  <TableCell>
                    <Badge
                      variant={isOnline ? 'default' : 'secondary'}
                      className={cn(
                        'text-xs',
                        isOnline
                          ? 'bg-emerald-500/15 text-emerald-700 dark:text-emerald-300 hover:bg-emerald-500/20 border-emerald-500/20'
                          : system.status === 'down'
                            ? 'bg-red-500/15 text-red-700 dark:text-red-300 hover:bg-red-500/20 border-red-500/20'
                            : ''
                      )}
                    >
                      <div
                        className={cn(
                          'h-1.5 w-1.5 rounded-full mr-1.5',
                          isOnline ? 'bg-emerald-500 animate-pulse' : system.status === 'down' ? 'bg-red-500' : 'bg-yellow-500'
                        )}
                      />
                      {system.status}
                    </Badge>
                  </TableCell>

                  <TableCell className="min-w-[120px]">
                    <div className="space-y-1">
                      <p className={cn('text-xs font-medium tabular-nums', getPercentColor(cpuPercent))}>
                        {formatPercent(cpuPercent)}
                      </p>
                      <div className="h-1.5 w-full rounded-full bg-muted/80 overflow-hidden">
                        <div
                          className={cn('h-full rounded-full transition-all', getPercentBarColor(cpuPercent))}
                          style={{width: `${Math.min(100, Math.max(0, cpuPercent || 0))}%`}}
                        />
                      </div>
                    </div>
                  </TableCell>

                  <TableCell className="hidden sm:table-cell min-w-[170px]">
                    <div className="space-y-1">
                      <p className={cn('text-xs font-medium tabular-nums', getPercentColor(memPercent))}>
                        {formatBytes(metrics?.mem_used)} / {formatBytes(metrics?.mem_total)}
                      </p>
                      <div className="h-1.5 w-full rounded-full bg-muted/80 overflow-hidden">
                        <div
                          className={cn('h-full rounded-full transition-all', getPercentBarColor(memPercent))}
                          style={{width: `${Math.min(100, Math.max(0, memPercent || 0))}%`}}
                        />
                      </div>
                    </div>
                  </TableCell>

                  <TableCell className="hidden md:table-cell min-w-[170px]">
                    <div className="space-y-1">
                      <p className={cn('text-xs font-medium tabular-nums', getPercentColor(diskPercent))}>
                        {formatBytes(metrics?.disk_used)} / {formatBytes(metrics?.disk_total)}
                      </p>
                      <div className="h-1.5 w-full rounded-full bg-muted/80 overflow-hidden">
                        <div
                          className={cn('h-full rounded-full transition-all', getPercentBarColor(diskPercent))}
                          style={{width: `${Math.min(100, Math.max(0, diskPercent || 0))}%`}}
                        />
                      </div>
                    </div>
                  </TableCell>

                  <TableCell className="hidden lg:table-cell">
                    <div className="text-xs font-medium tabular-nums leading-5">
                      <div className="text-sky-600 dark:text-sky-400">↓ {formatBytesPerSec(metrics?.net_recv_bytes)}</div>
                      <div className="text-indigo-600 dark:text-indigo-400">↑ {formatBytesPerSec(metrics?.net_sent_bytes)}</div>
                    </div>
                  </TableCell>

                  <TableCell className="text-right text-xs text-muted-foreground">
                    {system.lastSeenAt ? formatRelativeTime(system.lastSeenAt) : 'Never seen'}
                  </TableCell>

                  <TableCell className="pr-4 text-right">
                    <Button
                      size="sm"
                      variant="ghost"
                      className="h-7 w-7 p-0"
                      onClick={(e) => {
                        e.preventDefault()
                        e.stopPropagation()
                        onDelete(system.id, system.name)
                      }}
                    >
                      <Trash2 className="h-3.5 w-3.5 text-destructive" />
                    </Button>
                  </TableCell>
                </TableRow>
              )
            })}
          </TableBody>
        </Table>
      </CardContent>
    </Card>
  )
}

function MonitoringListPage() {
  const {toast} = useToast()
  const queryClient = useQueryClient()
  const [addDialogOpen, setAddDialogOpen] = useState(false)
  const [viewMode, setViewMode] = useState<MonitoringViewMode>(getInitialMonitoringViewMode)

  const {data: systems = [], isLoading} = useQuery({
    queryKey: ['monitor-systems'],
    queryFn: () => api.getMonitorSystems(),
    refetchInterval: 30000,
  })

  useEffect(() => {
    if (typeof window !== 'undefined') {
      window.localStorage.setItem(MONITORING_VIEW_MODE_STORAGE_KEY, viewMode)
    }
  }, [viewMode])

  const deleteMutation = useMutation({
    mutationFn: (systemId: string) => api.deleteMonitorSystem(systemId),
    onSuccess: () => {
      queryClient.invalidateQueries({queryKey: ['monitor-systems']})
      toast({
        title: 'System deleted',
        description: 'The system has been removed from monitoring.',
      })
    },
    onError: () => {
      toast({
        title: 'Error',
        description: 'Failed to delete system. Please try again.',
        variant: 'destructive',
      })
    },
  })

  const handleDelete = (systemId: string, systemName: string) => {
    if (confirm(`Are you sure you want to delete "${systemName}"? This cannot be undone.`)) {
      deleteMutation.mutate(systemId)
    }
  }

  const onlineSystems = systems.filter((s) => s.status === 'up')
  const offlineSystems = systems.filter((s) => s.status === 'down')

  return (
    <div className="min-h-screen bg-background">
      {/* Single dialog instance owned by the page */}
      <AddSystemDialog isOpen={addDialogOpen} setIsOpen={setAddDialogOpen} />

      {/* Header */}
      <div className="border-b bg-card/50">
        <div className="container mx-auto px-4 py-6">
          <div className="flex flex-col gap-4 sm:flex-row sm:items-center sm:justify-between">
            <div>
              <h1 className="text-3xl font-bold tracking-tight">Server Monitoring</h1>
              <p className="text-muted-foreground mt-1">
                Monitor system metrics, containers, and receive alerts
              </p>
            </div>
            <div className="flex flex-wrap items-center gap-2">
              <div className="inline-flex items-center rounded-lg border bg-background p-1">
                <Button
                  variant={viewMode === 'cards' ? 'secondary' : 'ghost'}
                  size="sm"
                  className="h-8 gap-1.5"
                  onClick={() => setViewMode('cards')}
                >
                  <LayoutGrid className="h-3.5 w-3.5" />
                  Cards
                </Button>
                <Button
                  variant={viewMode === 'compact' ? 'secondary' : 'ghost'}
                  size="sm"
                  className="h-8 gap-1.5"
                  onClick={() => setViewMode('compact')}
                >
                  <Rows3 className="h-3.5 w-3.5" />
                  Compact
                </Button>
              </div>
              <AddSystemButton onClick={() => setAddDialogOpen(true)} />
            </div>
          </div>
        </div>
      </div>

      <div className="container mx-auto px-4 py-6 space-y-6">
        {/* Summary Stats */}
        {systems.length > 0 && (
          <div className="grid gap-4 grid-cols-2 md:grid-cols-4">
            <Card className="bg-gradient-to-br from-blue-500/5 to-blue-600/10 border-blue-500/10">
              <CardContent className="pt-5 pb-4">
                <div className="flex items-center gap-3">
                  <div className="flex items-center justify-center h-10 w-10 rounded-lg bg-blue-500/15">
                    <Server className="h-5 w-5 text-blue-600 dark:text-blue-400" />
                  </div>
                  <div>
                    <p className="text-2xl font-bold">{systems.length}</p>
                    <p className="text-xs text-muted-foreground">Total Systems</p>
                  </div>
                </div>
              </CardContent>
            </Card>

            <Card className="bg-gradient-to-br from-emerald-500/5 to-emerald-600/10 border-emerald-500/10">
              <CardContent className="pt-5 pb-4">
                <div className="flex items-center gap-3">
                  <div className="flex items-center justify-center h-10 w-10 rounded-lg bg-emerald-500/15">
                    <CheckCircle2 className="h-5 w-5 text-emerald-600 dark:text-emerald-400" />
                  </div>
                  <div>
                    <p className="text-2xl font-bold">{onlineSystems.length}</p>
                    <p className="text-xs text-muted-foreground">Online</p>
                  </div>
                </div>
              </CardContent>
            </Card>

            <Card className="bg-gradient-to-br from-red-500/5 to-red-600/10 border-red-500/10">
              <CardContent className="pt-5 pb-4">
                <div className="flex items-center gap-3">
                  <div className="flex items-center justify-center h-10 w-10 rounded-lg bg-red-500/15">
                    <ServerOff className="h-5 w-5 text-red-600 dark:text-red-400" />
                  </div>
                  <div>
                    <p className="text-2xl font-bold">{offlineSystems.length}</p>
                    <p className="text-xs text-muted-foreground">Offline</p>
                  </div>
                </div>
              </CardContent>
            </Card>

            <Card className="bg-gradient-to-br from-violet-500/5 to-violet-600/10 border-violet-500/10">
              <CardContent className="pt-5 pb-4">
                <div className="flex items-center gap-3">
                  <div className="flex items-center justify-center h-10 w-10 rounded-lg bg-violet-500/15">
                    <Activity className="h-5 w-5 text-violet-600 dark:text-violet-400" />
                  </div>
                  <div>
                    <p className="text-2xl font-bold">
                      {systems.length > 0
                        ? (
                            systems.reduce((acc, s) => acc + (s.latest_metrics?.cpu_percent || 0), 0) / systems.length
                          ).toFixed(0)
                        : 0}
                      %
                    </p>
                    <p className="text-xs text-muted-foreground">Avg CPU</p>
                  </div>
                </div>
              </CardContent>
            </Card>
          </div>
        )}

        {/* Systems Grid */}
        {isLoading ? (
          <div className="flex items-center justify-center py-16">
            <div className="flex flex-col items-center gap-3">
              <div className="h-8 w-8 animate-spin rounded-full border-2 border-primary border-t-transparent" />
              <p className="text-muted-foreground text-sm">Loading systems...</p>
            </div>
          </div>
        ) : systems.length === 0 ? (
          <Card className="border-dashed">
            <CardContent className="py-16 text-center">
              <div className="mx-auto mb-6 flex h-20 w-20 items-center justify-center rounded-2xl bg-gradient-to-br from-blue-500/10 to-violet-500/10">
                <Server className="h-10 w-10 text-blue-500" />
              </div>
              <h3 className="text-xl font-semibold mb-2">No systems yet</h3>
              <p className="text-muted-foreground mb-8 max-w-sm mx-auto">
                Start monitoring your servers by adding your first system. You'll get a Docker
                command to deploy the agent in seconds.
              </p>
              <AddSystemButton onClick={() => setAddDialogOpen(true)} />
            </CardContent>
          </Card>
        ) : viewMode === 'compact' ? (
          <SystemsCompactTable systems={systems} onDelete={handleDelete} />
        ) : (
          <div className="grid gap-4 sm:grid-cols-2 lg:grid-cols-3">
            {systems.map((system) => (
              <SystemCard key={system.id} system={system} onDelete={handleDelete} />
            ))}
          </div>
        )}
      </div>
    </div>
  )
}
