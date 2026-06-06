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

import {createFileRoute, Link, redirect, useNavigate} from '@tanstack/react-router'
import {useMutation, useQuery, useQueryClient} from '@tanstack/react-query'
import {api, type DdHostResponse} from '@/lib/api'
import {cn, formatRelativeTime} from '@/lib/utils'
import {Badge} from '@/components/ui/badge'
import {Button} from '@/components/ui/button'
import {Card, CardContent, CardHeader, CardTitle} from '@/components/ui/card'
import {StatusDot} from '@/components/ui/status-dot'
import {EmptyState} from '@/components/ui/empty-state'
import {Separator} from '@/components/ui/separator'
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
import {Switch} from '@/components/ui/switch'
import {Table, TableBody, TableCell, TableHead, TableHeader, TableRow} from '@/components/ui/table'
import {Tabs, TabsContent, TabsList, TabsTrigger} from '@/components/ui/tabs'
import {Tooltip, TooltipContent, TooltipProvider, TooltipTrigger} from '@/components/ui/tooltip'
import {
  Activity,
  ArrowUpRight,
  Check,
  CheckCircle2,
  CircleCheck,
  CircleX,
  Copy,
  Cpu,
  HardDrive,
  LayoutGrid,
  List,
  Loader2,
  MemoryStick,
  Microchip,
  Network,
  Plus,
  Search,
  Server,
  ServerOff,
  Terminal,
  Trash2,
  FileCode,
} from 'lucide-react'
import {useEffect, useMemo, useState} from 'react'
import {useIsSelfHosted} from '@/hooks/useEnterpriseFeatures'
import {useToast} from '@/hooks/useToast'
import {Prism as SyntaxHighlighter} from 'react-syntax-highlighter'
import {oneDark, oneLight} from 'react-syntax-highlighter/dist/esm/styles/prism'

const BACKEND_URL = import.meta.env.VITE_BACKEND_URL || 'https://api.moneat.io'

function datadogLogsEndpoint(ingestUrl: string): {address: string; noSsl: boolean} {
  try {
    const parsed = new URL(ingestUrl)
    const port = parsed.port || (parsed.protocol === 'http:' ? '80' : '443')
    return {
      address: `${parsed.hostname}:${port}`,
      noSsl: parsed.protocol === 'http:',
    }
  } catch {
    return {
      address: ingestUrl.replace(/^https?:\/\//, '').replace(/\/.*$/, ''),
      noSsl: false,
    }
  }
}

function datadogForwarderEndpoint(backendUrl: string): string {
  const normalized = backendUrl.replace(/\/$/, '')
  try {
    const parsed = new URL(normalized)
    const port = parsed.port ? `:${parsed.port}` : ''
    return parsed.protocol === 'https:' ? `${parsed.hostname}${port}` : normalized
  } catch {
    return normalized
  }
}

function datadogProfileEndpoint(backendUrl: string, apiKey?: string | null): string {
  const endpoint = backendUrl.replace(/\/$/, '') + '/api/v2/profile'
  return apiKey ? endpoint + '?api_key=' + encodeURIComponent(apiKey) : endpoint
}

function datadogTelemetryEndpoint(backendUrl: string): string {
  return backendUrl.replace(/\/$/, '') + '/dd/telemetry/proxy'
}

function MonitoringPage() {
  return <MonitoringHostsPage />
}

export const Route = createFileRoute('/monitoring/')({
  beforeLoad: () => {
    if (!api.isAuthenticated()) {
      throw redirect({to: '/login'})
    }
  },
  component: MonitoringPage,
})


// ─── Add Host (Datadog Agent) ────────────────────────────────────────

function formatBytes(kb: number): string {
  if (kb < 1024) return `${kb} KB`
  const mb = kb / 1024
  if (mb < 1024) return `${mb.toFixed(1)} MB`
  const gb = mb / 1024
  return `${gb.toFixed(1)} GB`
}


const TIER_HOST_LIMITS: Record<string, number> = {
  FREE: Infinity,
  PRO: Infinity,
  TEAM: Infinity,
}

async function copyToClipboard(text: string): Promise<boolean> {
  try {
    await navigator.clipboard.writeText(text)
    return true
  } catch {
    try {
      const textarea = document.createElement('textarea')
      textarea.value = text
      textarea.style.position = 'fixed'
      textarea.style.opacity = '0'
      document.body.appendChild(textarea)
      textarea.select()
      document.execCommand('copy')
      document.body.removeChild(textarea)
      return true
    } catch {
      return false
    }
  }
}

async function copyWithToast(
  text: string,
  setCopied: (v: boolean) => void,
  successDesc: string,
  errorDesc: string,
  toast: (opts: { title: string; description: React.ReactNode; variant?: 'destructive' }) => void
) {
  const ok = await copyToClipboard(text)
  if (ok) {
    setCopied(true)
    toast({title: 'Copied!', description: successDesc})
    setTimeout(() => setCopied(false), 2000)
  } else {
    toast({title: 'Copy failed', description: errorDesc, variant: 'destructive'})
  }
}

function getCreateKeyErrorToast(error: Error) {
  const isLimit = error.message.includes('limit')
  return {
    title: isLimit ? 'Host Limit Reached' : 'Error' as const,
    description: isLimit ? (
      <>
        You&apos;ve reached the maximum number of hosts for your plan.{' '}
        <Link to="/settings" search={{tab: 'billing'}} className="underline font-medium">
          Upgrade your plan
        </Link>{' '}
        to add more hosts.
      </>
    ) : 'Failed to create agent key. Please try again.',
    variant: 'destructive' as const,
  }
}

type StatusFilter = 'all' | 'online' | 'offline'
type SortField = 'hostname' | 'cores' | 'memory' | 'lastSeen'
type SortDir = 'asc' | 'desc'

type AgentOptions = {
  container: boolean
  apm: boolean
  logs: boolean
  processes: boolean
}

function getDatadogYaml(options: AgentOptions, apiKey?: string | null): string {
  const backendUrl = BACKEND_URL.replace(/\/$/, '')
  const ingestUrl = backendUrl + '/dd'
  const logsEndpoint = datadogLogsEndpoint(ingestUrl)
  const forwarderEndpoint = datadogForwarderEndpoint(backendUrl)
  const profileEndpoint = datadogProfileEndpoint(backendUrl, apiKey)
  const telemetryEndpoint = datadogTelemetryEndpoint(backendUrl)

  let yaml = `# Datadog Agent configuration for Moneat
docker_query_timeout: 15`

  if (options.apm) {
    yaml += `

# Continuous Profiling (agent uses this URL verbatim)
apm_config:
  profiling_dd_url: ${profileEndpoint}
  telemetry:
    dd_url: ${telemetryEndpoint}`
  }

  if (options.logs) {
    yaml += `

# Log Collection
logs_config:
  logs_dd_url: ${logsEndpoint.address}
  logs_no_ssl: ${logsEndpoint.noSsl}`
  }

  yaml += `

# EPForwarder tracks (cannot be set via env vars)
container_lifecycle:
  dd_url: ${forwarderEndpoint}
container_image:
  dd_url: ${forwarderEndpoint}
sbom:
  dd_url: ${forwarderEndpoint}
network_devices:
  metadata:
    dd_url: ${forwarderEndpoint}
  snmp_traps:
    forwarder:
      dd_url: ${forwarderEndpoint}
  netflow:
    forwarder:
      dd_url: ${forwarderEndpoint}
synthetics:
  forwarder:
    dd_url: ${forwarderEndpoint}
database_monitoring:
  metrics:
    dd_url: ${forwarderEndpoint}
  samples:
    dd_url: ${forwarderEndpoint}
  activity:
    dd_url: ${forwarderEndpoint}`

  return yaml
}

function getDockerRunCommand(apiKey: string, options: AgentOptions): string {
  const ingestUrl = BACKEND_URL.replace(/\/$/, '') + '/dd'
  const logsEndpoint = datadogLogsEndpoint(ingestUrl)
  
  let envs = `  -e DD_API_KEY="${apiKey}" \\\n  -e DD_DD_URL="${ingestUrl}" \\`
  
  if (options.apm) {
    envs += `\n  -e DD_APM_ENABLED=true \\\n  -e DD_APM_DD_URL="${ingestUrl}" \\`
  }
  
  if (options.logs) {
    envs +=
      `\n  -e DD_LOGS_ENABLED=true \\` +
      `\n  -e DD_LOGS_CONFIG_LOGS_DD_URL="${logsEndpoint.address}" \\` +
      `\n  -e DD_LOGS_CONFIG_LOGS_NO_SSL=${logsEndpoint.noSsl} \\` +
      `\n  -e DD_LOGS_CONFIG_CONTAINER_COLLECT_ALL=true \\`
  }
  
  if (options.processes) {
    envs += `\n  -e DD_PROCESS_AGENT_ENABLED=true \\\n  -e DD_PROCESS_CONFIG_PROCESS_DD_URL="${ingestUrl}" \\`
  }

  let volumes = options.container
    ? `\n  -v /var/run/docker.sock:/var/run/docker.sock:ro \\`
    : ''
  if (options.logs) {
    volumes += `\n  -v /var/lib/docker/containers:/var/lib/docker/containers:ro \\`
  }
    
  return `docker run -d \\
  --name dd-agent \\
  --restart always \\
  --network host \\
${envs}
  -v /etc/datadog-agent/datadog.yaml:/etc/datadog-agent/datadog.yaml:ro \\${volumes}
  -v /proc/:/host/proc/:ro \\
  -v /sys/:/host/sys/:ro \\
  gcr.io/datadoghq/agent:7`
}

function getDockerComposeCommand(apiKey: string, options: AgentOptions): string {
  const ingestUrl = BACKEND_URL.replace(/\/$/, '') + '/dd'
  const logsEndpoint = datadogLogsEndpoint(ingestUrl)
  
  let envs = `      - DD_API_KEY=${apiKey}\n      - DD_DD_URL=${ingestUrl}`
  
  if (options.apm) {
    envs += `\n      - DD_APM_ENABLED=true\n      - DD_APM_DD_URL=${ingestUrl}`
  }
  
  if (options.logs) {
    envs +=
      `\n      - DD_LOGS_ENABLED=true` +
      `\n      - DD_LOGS_CONFIG_LOGS_DD_URL=${logsEndpoint.address}` +
      `\n      - DD_LOGS_CONFIG_LOGS_NO_SSL=${logsEndpoint.noSsl}` +
      `\n      - DD_LOGS_CONFIG_CONTAINER_COLLECT_ALL=true`
  }
  
  if (options.processes) {
    envs += `\n      - DD_PROCESS_AGENT_ENABLED=true\n      - DD_PROCESS_CONFIG_PROCESS_DD_URL=${ingestUrl}`
  }

  let volumes = options.container
    ? `    volumes:\n      - /etc/datadog-agent/datadog.yaml:/etc/datadog-agent/datadog.yaml:ro\n      - /proc/:/host/proc/:ro\n      - /sys/:/host/sys/:ro\n      - /var/run/docker.sock:/var/run/docker.sock:ro`
    : `    volumes:\n      - /etc/datadog-agent/datadog.yaml:/etc/datadog-agent/datadog.yaml:ro\n      - /proc/:/host/proc/:ro\n      - /sys/:/host/sys/:ro`
  if (options.logs) {
    volumes += `\n      - /var/lib/docker/containers:/var/lib/docker/containers:ro`
  }

  return `cat > docker-compose.yml <<'EOF'
services:
  dd-agent:
    image: gcr.io/datadoghq/agent:7
    container_name: dd-agent
    restart: always
    network_mode: host
${volumes}
    environment:
${envs}
EOF

docker compose up -d`
}

function AddHostDialog({
  isOpen,
  setIsOpen,
}: {
  isOpen: boolean
  setIsOpen: (v: boolean) => void
}) {
  const {toast} = useToast()
  const queryClient = useQueryClient()
  const [keyName, setKeyName] = useState('')
  const [createdKey, setCreatedKey] = useState<string | null>(null)
  const [options, setOptions] = useState<AgentOptions>({
    container: true,
    apm: true,
    logs: false,
    processes: true,
  })
  const [isDark, setIsDark] = useState(() => document.documentElement.classList.contains('dark'))
  const [copied, setCopied] = useState(false)
  const [copiedYaml, setCopiedYaml] = useState(false)
  const [installType, setInstallType] = useState<'docker' | 'compose'>('docker')

  useEffect(() => {
    const root = document.documentElement
    const observer = new MutationObserver(() => setIsDark(root.classList.contains('dark')))
    observer.observe(root, {attributes: true, attributeFilter: ['class']})
    return () => observer.disconnect()
  }, [])

  const createAgentKeyMutation = useMutation({
    mutationFn: (name: string) => api.createAgentApiKey(name),
    onSuccess: (data) => {
      queryClient.invalidateQueries({queryKey: ['agent-api-keys']})
      setCreatedKey(data.key)
      setKeyName('')
    },
    onError: (error: Error) => toast(getCreateKeyErrorToast(error)),
  })

  const handleSubmit = (e: React.FormEvent) => {
    e.preventDefault()
    if (keyName.trim()) {
      createAgentKeyMutation.mutate(keyName.trim())
    }
  }

  const handleClose = () => {
    setIsOpen(false)
    setTimeout(() => {
      setCreatedKey(null)
      setKeyName('')
      setOptions({
        container: true,
        apm: true,
        logs: false,
        processes: true,
      })
      setCopied(false)
      setCopiedYaml(false)
    }, 200)
  }

  const handleCopyCommand = () => {
    if (!createdKey) return
    const command = installType === 'docker'
      ? getDockerRunCommand(createdKey, options)
      : getDockerComposeCommand(createdKey, options)
    copyWithToast(command, setCopied, 'Command copied to clipboard', 'Please copy the command manually', toast)
  }

  const handleCopyYaml = () => {
    copyWithToast(
      getDatadogYaml(options, createdKey),
      setCopiedYaml,
      'YAML config copied to clipboard',
      'Please copy the config manually',
      toast
    )
  }

  return (
    <Dialog open={isOpen} onOpenChange={(open) => (open ? setIsOpen(true) : handleClose())}>
      <DialogContent className="max-w-4xl">
        {!createdKey ? (
          <>
            <DialogHeader>
              <DialogTitle className="flex items-center gap-2">
                <HardDrive className="h-5 w-5 text-primary" />
                Add New Host
              </DialogTitle>
              <DialogDescription>
                Enter a name for the agent key, then deploy the agent on your server.
              </DialogDescription>
            </DialogHeader>
            <form onSubmit={handleSubmit}>
              <div className="space-y-4 py-4">
                <div className="space-y-2">
                  <Label htmlFor="name">Key Name</Label>
                  <Input
                    id="name"
                    placeholder="e.g., Production Server, Dev Machine"
                    value={keyName}
                    onChange={(e) => setKeyName(e.target.value)}
                    required
                  />
                </div>
              </div>
              <DialogFooter>
                <Button type="button" variant="outline" onClick={handleClose}>
                  Cancel
                </Button>
                <Button type="submit" disabled={createAgentKeyMutation.isPending || !keyName.trim()}>
                  {createAgentKeyMutation.isPending
                    ? 'Creating...'
                    : 'Create Key & Continue'}
                </Button>
              </DialogFooter>
            </form>
          </>
        ) : (
          <>
            <DialogHeader>
              <DialogTitle className="flex items-center gap-2">
                <CheckCircle2 className="h-5 w-5 text-success-fg" />
                Agent Key Created
              </DialogTitle>
              <DialogDescription>
                Configure and deploy the Datadog-compatible agent on your server.
              </DialogDescription>
            </DialogHeader>
            <div className="space-y-5 py-4 max-h-[70vh] overflow-y-auto">
              {/* Options toggles */}
              <div className="flex flex-col gap-3 rounded-lg border bg-muted/20 p-3">
                <div className="flex items-start justify-between gap-4">
                  <div className="space-y-0.5">
                    <p className="text-sm font-medium">Container Monitoring</p>
                    <p className="text-xs text-muted-foreground">
                      Mounts Docker socket for container metrics and stats.
                    </p>
                  </div>
                  <Switch
                    checked={options.container}
                    onCheckedChange={(c) => setOptions({...options, container: c})}
                  />
                </div>
                
                <div className="flex items-start justify-between gap-4">
                  <div className="space-y-0.5">
                    <p className="text-sm font-medium">APM & Profiling</p>
                    <p className="text-xs text-muted-foreground">
                      Enable tracing and continuous profiling collection.
                    </p>
                  </div>
                  <Switch
                    checked={options.apm}
                    onCheckedChange={(c) => setOptions({...options, apm: c})}
                  />
                </div>

                <div className="flex items-start justify-between gap-4">
                  <div className="space-y-0.5">
                    <p className="text-sm font-medium">Log Collection</p>
                    <p className="text-xs text-muted-foreground">
                      Enable log forwarding from containers and files.
                    </p>
                  </div>
                  <Switch
                    checked={options.logs}
                    onCheckedChange={(c) => setOptions({...options, logs: c})}
                  />
                </div>

                <div className="flex items-start justify-between gap-4">
                  <div className="space-y-0.5">
                    <p className="text-sm font-medium">Live Processes</p>
                    <p className="text-xs text-muted-foreground">
                      Monitor running processes and their resource usage.
                    </p>
                  </div>
                  <Switch
                    checked={options.processes}
                    onCheckedChange={(c) => setOptions({...options, processes: c})}
                  />
                </div>
              </div>

              {/* Step 1: datadog.yaml */}
              <div className="space-y-2">
                <Label className="flex items-center gap-2 text-sm font-medium">
                  <span className="flex items-center justify-center h-5 w-5 rounded-full bg-info-bg text-info-fg text-xs font-bold shrink-0">1</span>
                  <FileCode className="h-3.5 w-3.5 text-info-fg" />
                  Save configuration file
                </Label>
                <p className="text-xs text-muted-foreground">
                  Save this as <code className="rounded bg-muted px-1 py-0.5 font-mono text-[11px]">/etc/datadog-agent/datadog.yaml</code> on your server.
                  This configures advanced telemetry tracks that cannot be set via environment variables.
                </p>
                <div className="relative group">
                  <div className="overflow-hidden rounded-lg border bg-card">
                    <SyntaxHighlighter
                      language="yaml"
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
                      {getDatadogYaml(options, createdKey)}
                    </SyntaxHighlighter>
                  </div>
                  <Button
                    size="sm"
                    variant="secondary"
                    className="absolute top-2.5 right-2.5 h-8 gap-1.5 text-xs"
                    onClick={handleCopyYaml}
                  >
                    {copiedYaml ? (
                      <>
                        <Check className="h-3.5 w-3.5 text-success-fg" />
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

              {/* Step 2: Run the agent */}
              <div className="space-y-2">
                <Label className="flex items-center gap-2 text-sm font-medium">
                  <span className="flex items-center justify-center h-5 w-5 rounded-full bg-info-bg text-info-fg text-xs font-bold shrink-0">2</span>
                  <Terminal className="h-3.5 w-3.5 text-info-fg" />
                  Run the agent
                </Label>
                <p className="text-xs text-muted-foreground">
                  Run this command on your server to start the agent. It mounts the config file from step 1.
                </p>
                <Tabs value={installType} onValueChange={(v) => setInstallType(v as 'docker' | 'compose')} className="w-full">
                  <TabsList className="grid w-full grid-cols-2">
                    <TabsTrigger value="docker">Docker</TabsTrigger>
                    <TabsTrigger value="compose">Docker Compose</TabsTrigger>
                  </TabsList>
                  <TabsContent value="docker" className="mt-3">
                    <div className="relative group">
                      <div className="overflow-hidden rounded-lg border bg-card">
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
                          {createdKey ? getDockerRunCommand(createdKey, options) : ''}
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
                            <Check className="h-3.5 w-3.5 text-success-fg" />
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
                  </TabsContent>
                  <TabsContent value="compose" className="mt-3">
                    <div className="relative group">
                      <div className="overflow-hidden rounded-lg border bg-card">
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
                          {createdKey ? getDockerComposeCommand(createdKey, options) : ''}
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
                            <Check className="h-3.5 w-3.5 text-success-fg" />
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
                  </TabsContent>
                </Tabs>
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

function AddHostButton({onClick}: Readonly<{onClick: () => void}>) {
  return (
    <Button className="gap-2" onClick={onClick}>
      <Plus className="h-4 w-4" />
      Add Host
    </Button>
  )
}

function HostsHeaderActions({hosts, onAddHost}: Readonly<{hosts: DdHostResponse[]; onAddHost: () => void}>) {
  const {data: billingUsage} = useQuery({
    queryKey: ['billingUsage'],
    queryFn: () => api.getBillingUsage(),
    enabled: api.isAuthenticated(),
  })
  const currentPlan = billingUsage?.plan || 'FREE'
  const isSelfHosted = useIsSelfHosted()
  const hostLimit = isSelfHosted ? Infinity : (TIER_HOST_LIMITS[currentPlan.toUpperCase()] ?? Infinity)
  const isAtLimit = !isSelfHosted && hosts.length >= hostLimit

  return (
    <div className="flex items-center gap-2">
      {isAtLimit ? (
        <TooltipProvider>
          <Tooltip>
            <TooltipTrigger asChild>
              <Link to="/settings" search={{tab: 'billing'}}>
                <Button variant="default" size="sm" className="gap-2">
                  <Plus className="h-4 w-4" />
                  Upgrade to Add More
                </Button>
              </Link>
            </TooltipTrigger>
            <TooltipContent>
              <p>You&apos;ve reached the limit for your {currentPlan} plan</p>
            </TooltipContent>
          </Tooltip>
        </TooltipProvider>
      ) : (
        <AddHostButton onClick={onAddHost} />
      )}
    </div>
  )
}

function SortIndicator({field, sortField, sortDir}: {field: SortField; sortField: SortField; sortDir: 'asc' | 'desc'}) {
  if (sortField !== field) return null
  return <span className="ml-1 text-[10px]">{sortDir === 'asc' ? '▲' : '▼'}</span>
}

function HostCard({host, onDelete}: {host: DdHostResponse; onDelete: (id: number, name: string) => void}) {
  const online = host.isOnline

  return (
    <Link
      to="/monitoring/hosts/$hostId"
      params={{hostId: String(host.id)}}
      className="block group"
    >
      <Card className="relative overflow-hidden transition-all duration-200 hover:border-primary/20 group-hover:-translate-y-0.5">
        {/* Top status accent bar */}
        <div
          className={cn(
            'absolute top-0 left-0 right-0 h-1',
            online ? 'bg-success-solid' : 'bg-danger-solid'
          )}
        />

        <CardHeader className="pb-3 pt-5">
          <div className="flex items-start justify-between">
            <div className="flex items-center gap-3 min-w-0">
              <div
                className={cn(
                  'flex items-center justify-center h-10 w-10 rounded-lg shrink-0',
                  online ? 'bg-success-bg text-success-fg' : 'bg-danger-bg text-danger-fg'
                )}
              >
                {online ? <Server className="h-5 w-5" /> : <ServerOff className="h-5 w-5" />}
              </div>
              <div className="min-w-0">
                <CardTitle className="text-base truncate">{host.hostname}</CardTitle>
                {host.os && (
                  <p className="text-xs text-muted-foreground truncate mt-0.5">{host.os}</p>
                )}
              </div>
            </div>
            <div className="flex items-center gap-2 shrink-0">
              <Badge variant={online ? 'success' : 'danger'} size="sm" className="gap-1.5">
                <StatusDot tone={online ? 'success' : 'danger'} size="sm" pulse={online} />
                {online ? 'up' : 'down'}
              </Badge>
              <Button
                size="sm"
                variant="ghost"
                className="h-7 w-7 p-0 opacity-0 group-hover:opacity-100 transition-opacity"
                onClick={(e) => {
                  e.preventDefault()
                  e.stopPropagation()
                  onDelete(host.id, host.hostname)
                }}
              >
                <Trash2 className="h-3.5 w-3.5 text-destructive" />
              </Button>
            </div>
          </div>
        </CardHeader>

        <CardContent className="space-y-4 pb-5">
          {/* Primary Metrics - Cores, Memory, Agent */}
          <div className="grid grid-cols-3 gap-3">
            <TooltipProvider delayDuration={200}>
              <Tooltip>
                <TooltipTrigger asChild>
                  <div className="flex flex-col items-center gap-1.5 p-2 rounded-lg bg-muted/40 hover:bg-muted/60 transition-colors">
                    <div className="flex h-10 w-10 items-center justify-center">
                      <span className="text-lg font-bold tabular-nums text-chart-1">
                        {host.cpuCores ?? '—'}
                      </span>
                    </div>
                    <div className="flex items-center gap-1 text-xs text-muted-foreground">
                      <Cpu className="h-3 w-3" />
                      <span>Cores</span>
                    </div>
                  </div>
                </TooltipTrigger>
                <TooltipContent>
                  <p>CPU Cores: {host.cpuCores ?? '—'}</p>
                </TooltipContent>
              </Tooltip>
            </TooltipProvider>

            <TooltipProvider delayDuration={200}>
              <Tooltip>
                <TooltipTrigger asChild>
                  <div className="flex flex-col items-center gap-1.5 p-2 rounded-lg bg-muted/40 hover:bg-muted/60 transition-colors">
                    <div className="flex h-10 w-10 items-center justify-center">
                      <span className="text-xs font-bold tabular-nums text-chart-2 leading-tight text-center">
                        {host.memoryTotalKb ? formatBytes(host.memoryTotalKb) : '—'}
                      </span>
                    </div>
                    <div className="flex items-center gap-1 text-xs text-muted-foreground">
                      <MemoryStick className="h-3 w-3" />
                      <span>RAM</span>
                    </div>
                  </div>
                </TooltipTrigger>
                <TooltipContent>
                  <p>Memory: {host.memoryTotalKb ? formatBytes(host.memoryTotalKb) : '—'}</p>
                </TooltipContent>
              </Tooltip>
            </TooltipProvider>

            <TooltipProvider delayDuration={200}>
              <Tooltip>
                <TooltipTrigger asChild>
                  <div className="flex flex-col items-center gap-1.5 p-2 rounded-lg bg-muted/40 hover:bg-muted/60 transition-colors">
                    <div className="flex h-10 w-10 items-center justify-center">
                      <span className="text-xs font-bold tabular-nums text-chart-3 font-mono">
                        v{host.agentVersion || '—'}
                      </span>
                    </div>
                    <div className="flex items-center gap-1 text-xs text-muted-foreground">
                      <Activity className="h-3 w-3" />
                      <span>Agent</span>
                    </div>
                  </div>
                </TooltipTrigger>
                <TooltipContent>
                  <p>Agent Version: {host.agentVersion ? `v${host.agentVersion}` : '—'}</p>
                </TooltipContent>
              </Tooltip>
            </TooltipProvider>
          </div>

          <Separator />

          {/* Secondary Metrics */}
          <div className="grid grid-cols-2 gap-x-4 gap-y-2.5 text-sm">
            <div className="flex items-center justify-between gap-2 min-w-0">
              <div className="flex items-center gap-1.5 text-muted-foreground shrink-0">
                <Microchip className="h-3.5 w-3.5 text-chart-1" />
                <span className="text-xs">Processor</span>
              </div>
              <span className="font-mono text-xs font-medium truncate" title={host.processor}>
                {host.processor || '—'}
              </span>
            </div>

            <div className="flex items-center justify-between gap-2 min-w-0">
              <div className="flex items-center gap-1.5 text-muted-foreground shrink-0">
                <Network className="h-3.5 w-3.5 text-chart-3" />
                <span className="text-xs">Platform</span>
              </div>
              <span className="font-mono text-xs font-medium truncate" title={host.platform}>
                {host.platform || host.os || '—'}
              </span>
            </div>
          </div>

          {/* Footer */}
          <div className="flex items-center justify-between pt-1">
            <span className="text-xs text-muted-foreground">
              {host.lastSeenAt ? formatRelativeTime(host.lastSeenAt) : 'Never seen'}
            </span>
            <ArrowUpRight className="h-3.5 w-3.5 text-muted-foreground opacity-0 group-hover:opacity-100 transition-opacity" />
          </div>
        </CardContent>
      </Card>
    </Link>
  )
}

function MonitoringHostsPage() {
  const navigate = useNavigate()
  const {toast} = useToast()
  const queryClient = useQueryClient()
  const [addDialogOpen, setAddDialogOpen] = useState(false)
  const [searchQuery, setSearchQuery] = useState('')
  const [statusFilter, setStatusFilter] = useState<StatusFilter>('all')
  const [viewMode, setViewMode] = useState<'list' | 'grid'>('grid')
  const [sortField, setSortField] = useState<SortField>('hostname')
  const [sortDir, setSortDir] = useState<SortDir>('asc')

  const {data: ddHostsData, isLoading: isLoadingHosts} = useQuery({
    queryKey: ['hosts'],
    queryFn: () => api.getHosts(),
    enabled: api.isAuthenticated(),
    refetchInterval: 30000,
  })

  const hosts: DdHostResponse[] = useMemo(() => {
    return ddHostsData?.hosts ?? []
  }, [ddHostsData?.hosts])

  const maxMemory = useMemo(
    () => Math.max(...hosts.map((h) => h.memoryTotalKb), 1),
    [hosts]
  )

  const filtered = useMemo(() => {
    let result = hosts
    if (searchQuery) {
      const q = searchQuery.toLowerCase()
      result = result.filter(
        (h) =>
          h.hostname?.toLowerCase().includes(q) ||
          h.os?.toLowerCase().includes(q) ||
          h.platform?.toLowerCase().includes(q) ||
          h.processor?.toLowerCase().includes(q) ||
          h.agentVersion?.toLowerCase().includes(q)
      )
    }
    if (statusFilter === 'online') result = result.filter((h) => h.isOnline)
    else if (statusFilter === 'offline') result = result.filter((h) => !h.isOnline)

    result = [...result].sort((a, b) => {
      const dir = sortDir === 'asc' ? 1 : -1
      switch (sortField) {
        case 'hostname':
          return a.hostname.localeCompare(b.hostname) * dir
        case 'cores':
          return ((a.cpuCores || 0) - (b.cpuCores || 0)) * dir
        case 'memory':
          return ((a.memoryTotalKb || 0) - (b.memoryTotalKb || 0)) * dir
        case 'lastSeen':
          return (new Date(a.lastSeenAt).getTime() - new Date(b.lastSeenAt).getTime()) * dir
        default:
          return 0
      }
    })

    return result
  }, [hosts, searchQuery, statusFilter, sortField, sortDir])

  const onlineCount = hosts.filter((h) => h.isOnline).length
  const offlineCount = hosts.length - onlineCount
  const totalCores = hosts.reduce((sum, h) => sum + (h.cpuCores || 0), 0)
  const totalMemoryKb = hosts.reduce((sum, h) => sum + (h.memoryTotalKb || 0), 0)

  function toggleSort(field: SortField) {
    if (sortField === field) {
      setSortDir((d) => (d === 'asc' ? 'desc' : 'asc'))
    } else {
      setSortField(field)
      setSortDir(field === 'hostname' ? 'asc' : 'desc')
    }
  }

  const deleteMutation = useMutation({
    mutationFn: (hostId: number) => api.deleteHost(hostId),
    onSuccess: () => {
      queryClient.invalidateQueries({queryKey: ['hosts']})
      toast({
        title: 'Host deleted',
        description: 'The host has been removed from monitoring.',
      })
    },
    onError: () => {
      toast({
        title: 'Error',
        description: 'Failed to delete host. Please try again.',
        variant: 'destructive',
      })
    },
  })

  const handleDelete = (hostId: number, hostName: string) => {
    if (confirm(`Are you sure you want to delete "${hostName}"? This cannot be undone.`)) {
      deleteMutation.mutate(hostId)
    }
  }

  return (
    <div>
      <AddHostDialog
        isOpen={addDialogOpen}
        setIsOpen={setAddDialogOpen}
      />

      <div className="container mx-auto px-4 py-4 space-y-3">
        <div className="flex flex-col sm:flex-row items-start sm:items-center gap-3">
          {!isLoadingHosts && hosts.length > 0 && (
            <div className="flex items-center gap-3 text-sm flex-wrap">
              <div className="flex items-center gap-1.5">
                <HardDrive className="h-3.5 w-3.5 text-muted-foreground" />
                <span className="font-semibold tabular-nums">{hosts.length}</span>
                <span className="text-muted-foreground text-xs">hosts</span>
              </div>
              <div className="h-4 w-px bg-border" />
              <div className="flex items-center gap-1.5">
                <CircleCheck className="h-3.5 w-3.5 text-success-fg" />
                <span className="font-semibold tabular-nums">{onlineCount}</span>
                <span className="text-muted-foreground text-xs">online</span>
              </div>
              <div className="h-4 w-px bg-border" />
              <div className="flex items-center gap-1.5">
                <CircleX className="h-3.5 w-3.5 text-danger-fg" />
                <span className="font-semibold tabular-nums">{offlineCount}</span>
                <span className="text-muted-foreground text-xs">offline</span>
              </div>
              <div className="h-4 w-px bg-border" />
              <div className="flex items-center gap-1.5">
                <Cpu className="h-3.5 w-3.5 text-chart-1" />
                <span className="font-semibold tabular-nums">{totalCores}</span>
                <span className="text-muted-foreground text-xs">cores</span>
              </div>
              <div className="h-4 w-px bg-border" />
              <div className="flex items-center gap-1.5">
                <MemoryStick className="h-3.5 w-3.5 text-chart-3" />
                <span className="font-semibold tabular-nums">{formatBytes(totalMemoryKb)}</span>
                <span className="text-muted-foreground text-xs">memory</span>
              </div>
            </div>
          )}
          {!isLoadingHosts && hosts.length > 0 && (
            <div className="sm:ml-auto">
              <HostsHeaderActions hosts={hosts} onAddHost={() => setAddDialogOpen(true)} />
            </div>
          )}
        </div>

        {hosts.length > 0 && (
          <div className="flex flex-col sm:flex-row items-start sm:items-center gap-3">
            <div className="relative flex-1 max-w-md">
              <Search className="absolute left-2.5 top-2.5 h-4 w-4 text-muted-foreground" />
              <Input
                placeholder="Search by hostname, OS, or processor..."
                value={searchQuery}
                onChange={(e) => setSearchQuery(e.target.value)}
                className="pl-9"
              />
            </div>
            <div className="flex items-center gap-2">
              <div className="flex items-center gap-1 rounded-lg border bg-background p-1">
                {([
                  {key: 'all' as const, tone: 'neutral' as const, count: hosts.length},
                  {key: 'online' as const, tone: 'success' as const, count: onlineCount},
                  {key: 'offline' as const, tone: 'danger' as const, count: offlineCount},
                ]).map((f) => (
                  <button
                    key={f.key}
                    onClick={() => setStatusFilter(f.key)}
                    className={cn(
                      'inline-flex items-center gap-1.5 rounded-md px-3 py-1.5 text-xs font-medium transition-colors',
                      statusFilter === f.key
                        ? 'bg-secondary text-secondary-foreground'
                        : 'text-muted-foreground hover:text-foreground hover:bg-muted/50'
                    )}
                  >
                    <StatusDot tone={f.tone} size="sm" />
                    <span className="capitalize">{f.key}</span>
                    <span className="ml-0.5 text-[10px] text-muted-foreground">{f.count}</span>
                  </button>
                ))}
              </div>
              <div className="flex items-center gap-1 rounded-lg border bg-background p-1">
                <button
                  onClick={() => setViewMode('grid')}
                  className={cn(
                    'p-1.5 rounded-md transition-colors',
                    viewMode === 'grid'
                      ? 'bg-secondary text-secondary-foreground'
                      : 'text-muted-foreground hover:text-foreground hover:bg-muted/50'
                  )}
                >
                  <LayoutGrid className="h-4 w-4" />
                </button>
                <button
                  onClick={() => setViewMode('list')}
                  className={cn(
                    'p-1.5 rounded-md transition-colors',
                    viewMode === 'list'
                      ? 'bg-secondary text-secondary-foreground'
                      : 'text-muted-foreground hover:text-foreground hover:bg-muted/50'
                  )}
                >
                  <List className="h-4 w-4" />
                </button>
              </div>
            </div>
          </div>
        )}

        {isLoadingHosts ? (
          <div className="flex items-center justify-center py-16">
            <div className="flex flex-col items-center gap-3">
              <Loader2 className="h-8 w-8 animate-spin text-muted-foreground" />
              <p className="text-muted-foreground text-sm">Loading hosts...</p>
            </div>
          </div>
        ) : hosts.length === 0 ? (
          <EmptyState
            icon={HardDrive}
            title="No hosts yet"
            description="Start monitoring your servers by deploying the agent. You'll get a Docker command to deploy in seconds."
            action={<AddHostButton onClick={() => setAddDialogOpen(true)} />}
          />
        ) : filtered.length === 0 ? (
          <EmptyState
            icon={Search}
            title="No hosts match your filters"
            description="Try adjusting your search or status filter."
          />
        ) : viewMode === 'grid' ? (
          <div className="grid grid-cols-1 sm:grid-cols-2 lg:grid-cols-3 xl:grid-cols-4 gap-4">
            {filtered.map((host) => (
              <HostCard key={host.id} host={host} onDelete={handleDelete} />
            ))}
          </div>
        ) : (
          <Card className="overflow-hidden border-border/60">
            <CardContent className="p-0">
              <Table className="min-w-[900px]">
                <TableHeader>
                  <TableRow className="hover:bg-transparent bg-muted/30">
                    <TableHead className="pl-4">Status</TableHead>
                    <TableHead
                      className="cursor-pointer select-none hover:text-foreground transition-colors"
                      onClick={() => toggleSort('hostname')}
                    >
                      Host
                      <SortIndicator field="hostname" sortField={sortField} sortDir={sortDir} />
                    </TableHead>
                    <TableHead>OS / Platform</TableHead>
                    <TableHead>Processor</TableHead>
                    <TableHead
                      className="cursor-pointer select-none hover:text-foreground transition-colors"
                      onClick={() => toggleSort('cores')}
                    >
                      CPU Cores
                      <SortIndicator field="cores" sortField={sortField} sortDir={sortDir} />
                    </TableHead>
                    <TableHead
                      className="min-w-[160px] cursor-pointer select-none hover:text-foreground transition-colors"
                      onClick={() => toggleSort('memory')}
                    >
                      Memory
                      <SortIndicator field="memory" sortField={sortField} sortDir={sortDir} />
                    </TableHead>
                    <TableHead>Agent</TableHead>
                    <TableHead
                      className="text-right pr-4 cursor-pointer select-none hover:text-foreground transition-colors"
                      onClick={() => toggleSort('lastSeen')}
                    >
                      Last Seen
                      <SortIndicator field="lastSeen" sortField={sortField} sortDir={sortDir} />
                    </TableHead>
                    <TableHead className="pr-4 text-right w-[80px]">Action</TableHead>
                  </TableRow>
                </TableHeader>
                <TableBody>
                  {filtered.map((host: DdHostResponse) => {
                    const online = host.isOnline
                    const memPct = maxMemory > 0 ? (host.memoryTotalKb / maxMemory) * 100 : 0

                    return (
                      <TableRow
                        key={host.id}
                        className="group hover:bg-muted/50 transition-colors cursor-pointer"
                        onClick={() => navigate({to: '/monitoring/hosts/$hostId', params: {hostId: String(host.id)}})}
                      >
                        <TableCell className="pl-4">
                          <Badge variant={online ? 'success' : 'danger'} size="sm" className="gap-1.5">
                            <StatusDot tone={online ? 'success' : 'danger'} size="sm" pulse={online} />
                            {online ? 'Online' : 'Offline'}
                          </Badge>
                        </TableCell>

                        <TableCell>
                          <div className="flex items-center gap-3 min-w-0">
                            <div
                              className={cn(
                                'flex h-8 w-8 shrink-0 items-center justify-center rounded-md',
                                online
                                  ? 'bg-success-bg text-success-fg'
                                  : 'bg-danger-bg text-danger-fg'
                              )}
                            >
                              {online ? <Server className="h-4 w-4" /> : <ServerOff className="h-4 w-4" />}
                            </div>
                            <div className="min-w-0">
                              <TooltipProvider delayDuration={300}>
                                <Tooltip>
                                  <TooltipTrigger asChild>
                                    <p className="font-medium text-sm truncate max-w-[230px]">
                                      {host.hostname}
                                    </p>
                                  </TooltipTrigger>
                                  <TooltipContent side="top">
                                    <p className="font-mono text-xs">{host.hostname}</p>
                                  </TooltipContent>
                                </Tooltip>
                              </TooltipProvider>
                              {host.firstSeenAt && (
                                <p className="text-[11px] text-muted-foreground truncate max-w-[230px]">
                                  First seen {formatRelativeTime(host.firstSeenAt)}
                                </p>
                              )}
                            </div>
                          </div>
                        </TableCell>

                        <TableCell>
                          <div className="min-w-0">
                            <p className="text-sm truncate max-w-[180px]">{host.os || '—'}</p>
                            {host.platform && host.platform !== host.os && (
                              <p className="text-[11px] text-muted-foreground truncate max-w-[180px]">
                                {host.platform}
                              </p>
                            )}
                          </div>
                        </TableCell>

                        <TableCell>
                          <TooltipProvider delayDuration={300}>
                            <Tooltip>
                              <TooltipTrigger asChild>
                                <div className="flex items-center gap-1.5 min-w-0">
                                  <Microchip className="h-3.5 w-3.5 text-muted-foreground shrink-0" />
                                  <span className="text-xs font-mono truncate max-w-[160px]">
                                    {host.processor || '—'}
                                  </span>
                                </div>
                              </TooltipTrigger>
                              {host.processor && (
                                <TooltipContent side="top" className="max-w-xs">
                                  <p className="font-mono text-xs">{host.processor}</p>
                                </TooltipContent>
                              )}
                            </Tooltip>
                          </TooltipProvider>
                        </TableCell>

                        <TableCell>
                          <div className="flex items-center gap-1.5">
                            <Cpu className="h-3.5 w-3.5 text-chart-1" />
                            <span className="text-sm font-medium tabular-nums">
                              {host.cpuCores || '—'}
                            </span>
                          </div>
                        </TableCell>

                        <TableCell>
                          <div className="space-y-1">
                            <p className="text-xs font-medium tabular-nums">
                              {host.memoryTotalKb ? formatBytes(host.memoryTotalKb) : '—'}
                            </p>
                            {host.memoryTotalKb > 0 && (
                              <div className="h-1.5 w-full rounded-full bg-muted/80 overflow-hidden">
                                <div
                                  className="h-full rounded-full transition-all duration-500 bg-chart-3"
                                  style={{width: `${Math.min(100, memPct)}%`}}
                                />
                              </div>
                            )}
                          </div>
                        </TableCell>

                        <TableCell>
                          {host.agentVersion ? (
                            <Badge variant="neutral" size="sm" className="font-mono">
                              v{host.agentVersion}
                            </Badge>
                          ) : (
                            <span className="text-xs text-muted-foreground">—</span>
                          )}
                        </TableCell>

                        <TableCell className="text-right pr-4 text-xs text-muted-foreground">
                          {host.lastSeenAt ? formatRelativeTime(host.lastSeenAt) : 'Never seen'}
                        </TableCell>

                        <TableCell className="pr-4 text-right">
                          <Button
                            size="sm"
                            variant="ghost"
                            className="h-7 w-7 p-0"
                            onClick={(e) => {
                              e.preventDefault()
                              e.stopPropagation()
                              handleDelete(host.id, host.hostname)
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
        )}
      </div>
    </div>
  )
}
