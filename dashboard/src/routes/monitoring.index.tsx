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
import {api, type DdHostResponse, mapMonitorHostToDdHost} from '@/lib/api'
import {cn, formatRelativeTime} from '@/lib/utils'
import {Badge} from '@/components/ui/badge'
import {Button} from '@/components/ui/button'
import {Card, CardContent, CardHeader, CardTitle} from '@/components/ui/card'
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
  BookOpen,
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
  Zap,
} from 'lucide-react'
import {useEffect, useMemo, useState} from 'react'
import {hasEnterpriseModule, useEnterpriseFeatures, useIsSelfHosted} from '@/hooks/useEnterpriseFeatures'
import {useToast} from '@/hooks/use-toast'
import {Prism as SyntaxHighlighter} from 'react-syntax-highlighter'
import {oneDark, oneLight} from 'react-syntax-highlighter/dist/esm/styles/prism'

const BACKEND_URL = import.meta.env.VITE_BACKEND_URL || 'https://api.moneat.io'

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


// ─── Moneat Agent (Add Host dialog) ────────────────────────────────────────

const DOCKER_SOCKET_REFERENCE_REGEX = /\/var\/run\/docker\.sock/

function getInstallCommand(baseCommand: string, enableContainerMonitoring: boolean): string {
  if (enableContainerMonitoring) {
    return baseCommand
  }

  // Remove docker socket mount when container monitoring is disabled
  const lines = baseCommand.split('\n')
  return lines.filter((line) => !DOCKER_SOCKET_REFERENCE_REGEX.test(line)).join('\n')
}

function getMoneatDockerComposeCommand(baseCommand: string, enableContainerMonitoring: boolean): string {
  // Extract the MONEAT_KEY from the docker run command
  const keyMatch = baseCommand.match(/-e MONEAT_KEY="([^"]+)"/)
  const key = keyMatch ? keyMatch[1] : 'YOUR_KEY_HERE'

  const volumes = enableContainerMonitoring
    ? `    volumes:
      - /var/run/docker.sock:/var/run/docker.sock:ro`
    : ''

  return `cat > docker-compose.yml <<'EOF'
services:
  moneat-agent:
    image: adrianelder/moneat-agent:latest
    container_name: moneat-agent
    restart: always
    network_mode: host
${volumes}
    environment:
      - MONEAT_KEY=${key}
EOF

docker compose up -d`
}


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

type StatusFilter = 'all' | 'online' | 'offline'
type SortField = 'hostname' | 'cores' | 'memory' | 'lastSeen'
type SortDir = 'asc' | 'desc'

type AgentOptions = {
  container: boolean
  apm: boolean
  logs: boolean
  processes: boolean
}

function getDockerRunCommand(apiKey: string, options: AgentOptions): string {
  const ingestUrl = BACKEND_URL.replace(/\/$/, '') + '/dd'
  
  let envs = `  -e DD_API_KEY="${apiKey}" \\\n  -e DD_DD_URL="${ingestUrl}" \\`
  
  if (options.apm) {
    envs += `\n  -e DD_APM_ENABLED=true \\\n  -e DD_APM_DD_URL="${ingestUrl}" \\`
  }
  
  if (options.logs) {
    envs += `\n  -e DD_LOGS_ENABLED=true \\\n  -e DD_LOGS_CONFIG_DD_URL="${ingestUrl}" \\`
  }
  
  if (options.processes) {
    envs += `\n  -e DD_PROCESS_AGENT_ENABLED=true \\\n  -e DD_PROCESS_CONFIG_PROCESS_DD_URL="${ingestUrl}" \\`
  }

  const dockerSocket = options.container
    ? `\n  -v /var/run/docker.sock:/var/run/docker.sock:ro \\`
    : ''
    
  return `docker run -d \\
  --name dd-agent \\
  --restart always \\
  --network host \\
${envs}${dockerSocket}
  -v /proc/:/host/proc/:ro \\
  -v /sys/:/host/sys/:ro \\
  gcr.io/datadoghq/agent:7`
}

function getDockerComposeCommand(apiKey: string, options: AgentOptions): string {
  const ingestUrl = BACKEND_URL.replace(/\/$/, '') + '/dd'
  
  let envs = `      - DD_API_KEY=${apiKey}\n      - DD_DD_URL=${ingestUrl}`
  
  if (options.apm) {
    envs += `\n      - DD_APM_ENABLED=true\n      - DD_APM_DD_URL=${ingestUrl}`
  }
  
  if (options.logs) {
    envs += `\n      - DD_LOGS_ENABLED=true\n      - DD_LOGS_CONFIG_DD_URL=${ingestUrl}`
  }
  
  if (options.processes) {
    envs += `\n      - DD_PROCESS_AGENT_ENABLED=true\n      - DD_PROCESS_CONFIG_PROCESS_DD_URL=${ingestUrl}`
  }

  const volumes = options.container
    ? `    volumes:\n      - /proc/:/host/proc/:ro\n      - /sys/:/host/sys/:ro\n      - /var/run/docker.sock:/var/run/docker.sock:ro`
    : `    volumes:\n      - /proc/:/host/proc/:ro\n      - /sys/:/host/sys/:ro`

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
  isEnterprise,
}: {
  isOpen: boolean
  setIsOpen: (v: boolean) => void
  isEnterprise: boolean
}) {
  const {toast} = useToast()
  const queryClient = useQueryClient()
  const [keyName, setKeyName] = useState('')
  const [createdKey, setCreatedKey] = useState<string | null>(null)
  const [createdMoneatHost, setCreatedMoneatHost] = useState<{
    id: number
    dockerCommand: string
  } | null>(null)
  const [containerMonitoringEnabled, setContainerMonitoringEnabled] = useState(true)
  const [options, setOptions] = useState<AgentOptions>({
    container: true,
    apm: true,
    logs: false,
    processes: true,
  })
  const [isDark, setIsDark] = useState(() => document.documentElement.classList.contains('dark'))
  const [copied, setCopied] = useState(false)
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
    onError: (error: Error) => {
      if (error.message.includes('limit')) {
        toast({
          title: 'Host Limit Reached',
          description: (
            <>
              You&apos;ve reached the maximum number of hosts for your plan.{' '}
              <Link to="/settings" search={{tab: 'billing'}} className="underline font-medium">
                Upgrade your plan
              </Link>{' '}
              to add more hosts.
            </>
          ),
          variant: 'destructive',
        })
      } else {
        toast({
          title: 'Error',
          description: 'Failed to create agent key. Please try again.',
          variant: 'destructive',
        })
      }
    },
  })

  const createMonitorHostMutation = useMutation({
    mutationFn: (name: string) => api.createMonitorHost(name),
    onSuccess: (data) => {
      queryClient.invalidateQueries({queryKey: ['monitor-hosts']})
      setCreatedMoneatHost({id: data.host.id, dockerCommand: data.docker_command})
      setKeyName('')
    },
    onError: (error: Error) => {
      if (error.message.includes('limit') || error.message.includes('Host limit')) {
        toast({
          title: 'Host Limit Reached',
          description: (
            <>
              You&apos;ve reached the maximum number of hosts for your plan.{' '}
              <Link to="/settings" search={{tab: 'billing'}} className="underline font-medium">
                Upgrade your plan
              </Link>{' '}
              to add more hosts.
            </>
          ),
          variant: 'destructive',
        })
      } else {
        toast({
          title: 'Error',
          description: 'Failed to create host. Please try again.',
          variant: 'destructive',
        })
      }
    },
  })

  const createMutation = isEnterprise ? createAgentKeyMutation : createMonitorHostMutation
  const showSuccess = isEnterprise ? !!createdKey : !!createdMoneatHost

  const handleSubmit = (e: React.FormEvent) => {
    e.preventDefault()
    if (keyName.trim()) {
      createMutation.mutate(keyName.trim())
    }
  }

  const handleClose = () => {
    setIsOpen(false)
    setTimeout(() => {
      setCreatedKey(null)
      setCreatedMoneatHost(null)
      setKeyName('')
      setContainerMonitoringEnabled(true)
      setOptions({
        container: true,
        apm: true,
        logs: false,
        processes: true,
      })
      setCopied(false)
    }, 200)
  }

  const handleCopyCommand = async () => {
    if (isEnterprise && createdKey) {
      const command =
        installType === 'docker'
          ? getDockerRunCommand(createdKey, options)
          : getDockerComposeCommand(createdKey, options)
      await navigator.clipboard.writeText(command)
      setCopied(true)
      toast({title: 'Copied!', description: 'Command copied to clipboard'})
      setTimeout(() => setCopied(false), 2000)
    } else if (!isEnterprise && createdMoneatHost) {
      const command =
        installType === 'docker'
          ? getInstallCommand(createdMoneatHost.dockerCommand, containerMonitoringEnabled)
          : getMoneatDockerComposeCommand(createdMoneatHost.dockerCommand, containerMonitoringEnabled)
      await navigator.clipboard.writeText(command)
      setCopied(true)
      toast({title: 'Copied!', description: 'Command copied to clipboard'})
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
        {!showSuccess ? (
          <>
            <DialogHeader>
              <DialogTitle className="flex items-center gap-2">
                <HardDrive className="h-5 w-5 text-blue-500" />
                Add New Host
              </DialogTitle>
              <DialogDescription>
                {isEnterprise
                  ? 'Enter a name for the agent key, then deploy the agent on your server.'
                  : 'Enter a name for your host to get started with monitoring.'}
              </DialogDescription>
            </DialogHeader>
            <form onSubmit={handleSubmit}>
              <div className="space-y-4 py-4">
                <div className="space-y-2">
                  <Label htmlFor="name">{isEnterprise ? 'Key Name' : 'Host Name'}</Label>
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
                <Button type="submit" disabled={createMutation.isPending || !keyName.trim()}>
                  {createMutation.isPending
                    ? 'Creating...'
                    : isEnterprise
                      ? 'Create Key & Continue'
                      : 'Create Host'}
                </Button>
              </DialogFooter>
            </form>
          </>
        ) : isEnterprise ? (
          <>
            <DialogHeader>
              <DialogTitle className="flex items-center gap-2">
                <CheckCircle2 className="h-5 w-5 text-emerald-500" />
                Agent Key Created
              </DialogTitle>
              <DialogDescription>
                Deploy the Datadog-compatible agent on your server using Docker or Docker Compose.
              </DialogDescription>
            </DialogHeader>
            <div className="space-y-5 py-4">
              <div className="space-y-2">
                <Label className="flex items-center gap-2 text-sm font-medium">
                  <Terminal className="h-3.5 w-3.5 text-blue-500" />
                  Installation Command
                </Label>
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
                <Tabs value={installType} onValueChange={(v) => setInstallType(v as 'docker' | 'compose')} className="w-full">
                  <TabsList className="grid w-full grid-cols-2">
                    <TabsTrigger value="docker">Docker</TabsTrigger>
                    <TabsTrigger value="compose">Docker Compose</TabsTrigger>
                  </TabsList>
                  <TabsContent value="docker" className="mt-3">
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
                  </TabsContent>
                  <TabsContent value="compose" className="mt-3">
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
                  </TabsContent>
                </Tabs>
              </div>

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
                    <span>Host metrics will appear within minutes</span>
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
        ) : (
          <>
            <DialogHeader>
              <DialogTitle className="flex items-center gap-2">
                <CheckCircle2 className="h-5 w-5 text-emerald-500" />
                Host Created Successfully
              </DialogTitle>
              <DialogDescription>
                Install the Moneat agent on your server using Docker or Docker Compose.
              </DialogDescription>
            </DialogHeader>
            <div className="space-y-5 py-4">
              <div className="space-y-2">
                <Label className="flex items-center gap-2 text-sm font-medium">
                  <Terminal className="h-3.5 w-3.5 text-blue-500" />
                  Installation Command
                </Label>
                <div className="flex items-start justify-between gap-4 rounded-lg border bg-muted/20 px-3 py-2.5">
                  <div className="space-y-0.5">
                    <p className="text-sm font-medium">Enable container monitoring</p>
                    <p className="text-xs text-muted-foreground">
                      Mounts Docker socket for container metrics and stats.
                    </p>
                  </div>
                  <Switch
                    checked={containerMonitoringEnabled}
                    onCheckedChange={setContainerMonitoringEnabled}
                    aria-label="Toggle container monitoring"
                  />
                </div>
                <Tabs value={installType} onValueChange={(v) => setInstallType(v as 'docker' | 'compose')} className="w-full">
                  <TabsList className="grid w-full grid-cols-2">
                    <TabsTrigger value="docker">Docker</TabsTrigger>
                    <TabsTrigger value="compose">Docker Compose</TabsTrigger>
                  </TabsList>
                  <TabsContent value="docker" className="mt-3">
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
                          {createdMoneatHost
                            ? getInstallCommand(createdMoneatHost.dockerCommand, containerMonitoringEnabled)
                            : ''}
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
                  </TabsContent>
                  <TabsContent value="compose" className="mt-3">
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
                          {createdMoneatHost
                            ? getMoneatDockerComposeCommand(createdMoneatHost.dockerCommand, containerMonitoringEnabled)
                            : ''}
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
                  </TabsContent>
                </Tabs>
              </div>
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

function AddHostButton({onClick}: {onClick: () => void}) {
  return (
    <Button className="gap-2" onClick={onClick}>
      <Plus className="h-4 w-4" />
      Add Host
    </Button>
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
      <Card className="relative overflow-hidden transition-all duration-200 hover:shadow-lg hover:shadow-primary/5 hover:border-primary/20 group-hover:-translate-y-0.5">
        {/* Top color accent bar */}
        <div
          className={cn(
            'absolute top-0 left-0 right-0 h-1',
            online
              ? 'bg-gradient-to-r from-emerald-500 to-teal-500'
              : 'bg-gradient-to-r from-red-500 to-rose-500'
          )}
        />

        <CardHeader className="pb-3 pt-5">
          <div className="flex items-start justify-between">
            <div className="flex items-center gap-3 min-w-0">
              <div
                className={cn(
                  'flex items-center justify-center h-10 w-10 rounded-lg shrink-0',
                  online ? 'bg-emerald-500/10 text-emerald-600 dark:text-emerald-400' : 'bg-red-500/10 text-red-600 dark:text-red-400'
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
              <Badge
                variant={online ? 'default' : 'secondary'}
                className={cn(
                  'text-xs',
                  online
                    ? 'bg-emerald-500/15 text-emerald-700 dark:text-emerald-300 hover:bg-emerald-500/20 border-emerald-500/20'
                    : 'bg-red-500/15 text-red-700 dark:text-red-300 hover:bg-red-500/20 border-red-500/20'
                )}
              >
                <div
                  className={cn(
                    'h-1.5 w-1.5 rounded-full mr-1.5',
                    online ? 'bg-emerald-500 animate-pulse' : 'bg-red-500'
                  )}
                />
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
                      <span className="text-lg font-bold tabular-nums text-violet-600 dark:text-violet-400">
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
                      <span className="text-xs font-bold tabular-nums text-orange-600 dark:text-orange-400 leading-tight text-center">
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
                      <span className="text-xs font-bold tabular-nums text-sky-600 dark:text-sky-400 font-mono">
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
                <Microchip className="h-3.5 w-3.5 text-violet-500" />
                <span className="text-xs">Processor</span>
              </div>
              <span className="font-mono text-xs font-medium truncate" title={host.processor}>
                {host.processor || '—'}
              </span>
            </div>

            <div className="flex items-center justify-between gap-2 min-w-0">
              <div className="flex items-center gap-1.5 text-muted-foreground shrink-0">
                <Network className="h-3.5 w-3.5 text-sky-500" />
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

  const {data: features} = useEnterpriseFeatures()
  const isEnterprise = hasEnterpriseModule(features, 'datadog')

  const {data: monitorHosts, isLoading} = useQuery({
    queryKey: ['monitor-hosts'],
    queryFn: () => api.getMonitorHosts(),
    enabled: api.isAuthenticated() && !isEnterprise,
    refetchInterval: 30000,
  })

  const {data: ddHostsData, isLoading: isLoadingDd} = useQuery({
    queryKey: ['hosts'],
    queryFn: () => api.getHosts(),
    enabled: api.isAuthenticated() && isEnterprise,
    refetchInterval: 30000,
  })

  const hosts: DdHostResponse[] = useMemo(() => {
    if (isEnterprise && ddHostsData?.hosts) return ddHostsData.hosts
    if (!isEnterprise && monitorHosts) return monitorHosts.map(mapMonitorHostToDdHost)
    return []
  }, [isEnterprise, monitorHosts, ddHostsData?.hosts])

  const isLoadingHosts = isLoading || (isEnterprise && isLoadingDd)

  const {data: billingUsage} = useQuery({
    queryKey: ['billingUsage'],
    queryFn: () => api.getBillingUsage(),
    enabled: api.isAuthenticated(),
  })

  const currentPlan = billingUsage?.plan || 'FREE'
  const isSelfHosted = useIsSelfHosted()
  const hostLimit = isSelfHosted ? Infinity : (TIER_HOST_LIMITS[currentPlan.toUpperCase()] ?? Infinity)
  const isAtLimit = !isSelfHosted && hosts.length >= hostLimit

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
    mutationFn: (hostId: number) =>
      isEnterprise ? api.deleteHost(hostId) : api.deleteMonitorHost(hostId),
    onSuccess: () => {
      queryClient.invalidateQueries({queryKey: ['hosts']})
      queryClient.invalidateQueries({queryKey: ['monitor-hosts']})
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
        isEnterprise={isEnterprise}
      />

      <div className="border-b bg-card/50">
        <div className="container mx-auto px-4 py-6">
          <div className="flex flex-col gap-4 sm:flex-row sm:items-center sm:justify-between">
            <div className="flex items-center gap-3">
              <div className="flex items-center justify-center h-10 w-10 rounded-lg bg-gradient-to-br from-blue-500 to-indigo-600">
                <HardDrive className="h-5 w-5 text-white" />
              </div>
              <div>
                <h1 className="text-2xl font-bold tracking-tight">Hosts</h1>
                <p className="text-muted-foreground mt-1">
                  Monitor your infrastructure with the Datadog-compatible agent
                </p>
              </div>
            </div>
            <div className="flex flex-wrap items-center gap-2">
              <a href="/docs/datadog-agent/agent-setup" target="_blank" rel="noreferrer"
                className="inline-flex items-center gap-1.5 text-sm text-muted-foreground hover:text-foreground transition-colors">
                <BookOpen className="h-4 w-4" />
                View docs
              </a>
              {isAtLimit ? (
                <TooltipProvider>
                  <Tooltip>
                    <TooltipTrigger asChild>
                      <Link to="/settings" search={{tab: 'billing'}}>
                        <Button variant="default" className="gap-2">
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
                <AddHostButton onClick={() => setAddDialogOpen(true)} />
              )}
            </div>
          </div>
        </div>
      </div>

      <div className="container mx-auto px-4 py-4 space-y-3">
        <div className="flex flex-col sm:flex-row items-start sm:items-center gap-3">
          {!isLoadingHosts && hosts.length > 0 && (
            <div className="flex items-center gap-3 text-sm flex-wrap">
              <div className="flex items-center gap-1.5">
                <HardDrive className="h-3.5 w-3.5 text-blue-500" />
                <span className="font-semibold tabular-nums">{hosts.length}</span>
                <span className="text-muted-foreground text-xs">hosts</span>
              </div>
              <div className="h-4 w-px bg-border" />
              <div className="flex items-center gap-1.5">
                <CircleCheck className="h-3.5 w-3.5 text-emerald-500" />
                <span className="font-semibold tabular-nums">{onlineCount}</span>
                <span className="text-muted-foreground text-xs">online</span>
              </div>
              <div className="h-4 w-px bg-border" />
              <div className="flex items-center gap-1.5">
                <CircleX className="h-3.5 w-3.5 text-red-500" />
                <span className="font-semibold tabular-nums">{offlineCount}</span>
                <span className="text-muted-foreground text-xs">offline</span>
              </div>
              <div className="h-4 w-px bg-border" />
              <div className="flex items-center gap-1.5">
                <Cpu className="h-3.5 w-3.5 text-violet-500" />
                <span className="font-semibold tabular-nums">{totalCores}</span>
                <span className="text-muted-foreground text-xs">cores</span>
              </div>
              <div className="h-4 w-px bg-border" />
              <div className="flex items-center gap-1.5">
                <MemoryStick className="h-3.5 w-3.5 text-sky-500" />
                <span className="font-semibold tabular-nums">{formatBytes(totalMemoryKb)}</span>
                <span className="text-muted-foreground text-xs">memory</span>
              </div>
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
                  {key: 'all' as const, color: 'bg-blue-500', count: hosts.length},
                  {key: 'online' as const, color: 'bg-emerald-500', count: onlineCount},
                  {key: 'offline' as const, color: 'bg-red-500', count: offlineCount},
                ]).map((f) => (
                  <button
                    key={f.key}
                    onClick={() => setStatusFilter(f.key)}
                    className={cn(
                      'inline-flex items-center gap-1.5 rounded-md px-3 py-1.5 text-xs font-medium transition-colors',
                      statusFilter === f.key
                        ? 'bg-secondary text-secondary-foreground shadow-sm'
                        : 'text-muted-foreground hover:text-foreground hover:bg-muted/50'
                    )}
                  >
                    <div className={cn('h-1.5 w-1.5 rounded-full', f.color)} />
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
                      ? 'bg-secondary text-secondary-foreground shadow-sm'
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
                      ? 'bg-secondary text-secondary-foreground shadow-sm'
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
          <Card className="border-dashed">
            <CardContent className="py-16 text-center">
              <div className="mx-auto mb-6 flex h-20 w-20 items-center justify-center rounded-2xl bg-gradient-to-br from-blue-500/10 to-violet-500/10">
                <HardDrive className="h-10 w-10 text-blue-500" />
              </div>
              <h3 className="text-xl font-semibold mb-2">No hosts yet</h3>
              <p className="text-muted-foreground mb-8 max-w-sm mx-auto">
                Start monitoring your servers by deploying the agent. You&apos;ll get a Docker
                command to deploy in seconds.
              </p>
              <AddHostButton onClick={() => setAddDialogOpen(true)} />
            </CardContent>
          </Card>
        ) : filtered.length === 0 ? (
          <div className="text-center py-12 text-muted-foreground">
            <p className="font-medium">No hosts match your filters</p>
            <p className="text-sm mt-1">Try adjusting your search or status filter.</p>
          </div>
        ) : viewMode === 'grid' ? (
          <div className="grid grid-cols-1 sm:grid-cols-2 lg:grid-cols-3 xl:grid-cols-4 gap-4">
            {filtered.map((host) => (
              <HostCard key={host.id} host={host} onDelete={handleDelete} />
            ))}
          </div>
        ) : (
          <Card className="overflow-hidden border-border/60 shadow-sm">
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
                          <Badge
                            variant="secondary"
                            className={cn(
                              'text-xs gap-1.5',
                              online
                                ? 'bg-emerald-500/15 text-emerald-700 dark:text-emerald-300 border-emerald-500/20 hover:bg-emerald-500/20'
                                : 'bg-red-500/15 text-red-700 dark:text-red-300 border-red-500/20 hover:bg-red-500/20'
                            )}
                          >
                            <div
                              className={cn(
                                'h-1.5 w-1.5 rounded-full',
                                online ? 'bg-emerald-500 animate-pulse' : 'bg-red-500'
                              )}
                            />
                            {online ? 'Online' : 'Offline'}
                          </Badge>
                        </TableCell>

                        <TableCell>
                          <div className="flex items-center gap-3 min-w-0">
                            <div
                              className={cn(
                                'flex h-8 w-8 shrink-0 items-center justify-center rounded-md',
                                online
                                  ? 'bg-emerald-500/10 text-emerald-600 dark:text-emerald-400'
                                  : 'bg-red-500/10 text-red-600 dark:text-red-400'
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
                            <Cpu className="h-3.5 w-3.5 text-violet-500" />
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
                                  className="h-full rounded-full transition-all duration-500 bg-sky-500"
                                  style={{width: `${Math.min(100, memPct)}%`}}
                                />
                              </div>
                            )}
                          </div>
                        </TableCell>

                        <TableCell>
                          {host.agentVersion ? (
                            <Badge
                              variant="outline"
                              className="text-[10px] font-medium font-mono border-violet-500/30 text-violet-600 dark:text-violet-400 bg-violet-500/5"
                            >
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
