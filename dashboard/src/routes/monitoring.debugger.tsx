// Moneat - observability platform
// Copyright (C) 2026 Moneat
//
// This program is free software: you can redistribute it and/or modify
// it under the terms of the GNU Affero General Public License as published by
// the Free Software Foundation, either version 3 of the License, or
// (at your option) any later version.

import {createFileRoute} from '@tanstack/react-router'
import {useQuery} from '@tanstack/react-query'
import {api} from '@/lib/api'
import CreateProbeDialog from '@/components/debugger/CreateProbeDialog'
import {Badge} from '@/components/ui/badge'
import {Button} from '@/components/ui/button'
import {Card, CardContent, CardDescription, CardHeader, CardTitle} from '@/components/ui/card'
import {Tooltip, TooltipContent, TooltipProvider, TooltipTrigger} from '@/components/ui/tooltip'
import {Activity, Bug, HelpCircle, Loader2, Plus} from 'lucide-react'
import {cn} from '@/lib/utils'
import {useState} from 'react'

export const Route = createFileRoute('/monitoring/debugger')({
  component: DebuggerDashboard,
})

interface DebuggerLog {
  debuggerType?: string
  timestamp?: string
  message?: string
  service?: string
  probeId?: string
}

interface DebuggerDiagnostic {
  probeId?: string
  service?: string
  env?: string
  status?: string
}

const statusColors: Record<string, string> = {
  received: 'bg-blue-500/15 text-blue-700 dark:text-blue-300 border-blue-500/20',
  installed: 'bg-emerald-500/15 text-emerald-700 dark:text-emerald-300 border-emerald-500/20',
  emitting: 'bg-emerald-500/15 text-emerald-700 dark:text-emerald-300 border-emerald-500/20',
  error: 'bg-red-500/15 text-red-700 dark:text-red-300 border-red-500/20',
  blocked: 'bg-amber-500/15 text-amber-700 dark:text-amber-300 border-amber-500/20',
  unknown: 'bg-muted text-muted-foreground border-border',
}

function formatStatusLabel(status: string): string {
  switch (status) {
    case 'received':
      return 'Received'
    case 'installed':
      return 'Installed'
    case 'emitting':
      return 'Emitting'
    case 'error':
      return 'Error'
    case 'blocked':
      return 'Blocked'
    default:
      return 'Unknown'
  }
}

export function DebuggerHeaderActions() {
  const [createDialogOpen, setCreateDialogOpen] = useState(false)
  return (
    <>
      <CreateProbeDialog
        open={createDialogOpen}
        onOpenChange={setCreateDialogOpen}
      />
      <div className="flex items-center gap-2">
        <TooltipProvider>
          <Tooltip>
            <TooltipTrigger asChild>
              <a href="/docs/datadog-agent/debugger" target="_blank" rel="noreferrer"
                className="inline-flex items-center justify-center p-1.5 rounded-md text-muted-foreground hover:text-foreground hover:bg-muted/50 transition-colors"
                aria-label="View docs">
                <HelpCircle className="h-4 w-4" />
              </a>
            </TooltipTrigger>
            <TooltipContent>
              <p>View docs</p>
            </TooltipContent>
          </Tooltip>
        </TooltipProvider>
        <Button onClick={() => setCreateDialogOpen(true)}>
          <Plus className="h-4 w-4 mr-1.5" />
          Create Probe
        </Button>
      </div>
    </>
  )
}

function DebuggerDashboard() {
  const {data: probesData} = useQuery({
    queryKey: ['debugger-probes'],
    queryFn: () => api.getDebuggerProbes(),
  })

  const {data: logsData, isLoading: logsLoading} = useQuery({
    queryKey: ['debugger-logs'],
    queryFn: () => api.get<{logs: DebuggerLog[]}>('/v1/infra/debugger/logs?limit=50'),
  })

  const {data: diagData, isLoading: diagLoading} = useQuery({
    queryKey: ['debugger-diagnostics'],
    queryFn: () => api.get<{diagnostics: DebuggerDiagnostic[]}>('/v1/infra/debugger/diagnostics?limit=50'),
  })

  const probes = probesData?.probes ?? []
  const logs = logsData?.logs ?? []
  const diagnostics = diagData?.diagnostics ?? []

  return (
    <>
      <div className="container mx-auto px-4 py-4 space-y-4">
        <div className="flex items-center gap-3 text-sm">
          <div className="flex items-center gap-1.5">
            <Activity className="h-3.5 w-3.5 text-purple-500" />
            <span className="font-semibold tabular-nums">{probes.length}</span>
            <span className="text-muted-foreground text-xs">probe definitions</span>
          </div>
          <div className="h-4 w-px bg-border" />
          <div className="flex items-center gap-1.5">
            <Activity className="h-3.5 w-3.5 text-blue-500" />
            <span className="font-semibold tabular-nums">{diagnostics.length}</span>
            <span className="text-muted-foreground text-xs">diagnostics</span>
          </div>
          <div className="h-4 w-px bg-border" />
          <div className="flex items-center gap-1.5">
            <Bug className="h-3.5 w-3.5 text-pink-500" />
            <span className="font-semibold tabular-nums">{logs.length}</span>
            <span className="text-muted-foreground text-xs">log entries</span>
          </div>
        </div>

        <div className="grid gap-4 lg:grid-cols-2">
          <Card className="overflow-hidden border-border/60">
            <CardHeader>
              <CardTitle className="flex items-center gap-2 text-base">
                <Activity className="h-4 w-4 text-purple-500" />
                Probe Status
              </CardTitle>
              <CardDescription>Installation status reported by active runtime agents.</CardDescription>
            </CardHeader>
            <CardContent>
              {diagLoading ? (
                <div className="flex items-center justify-center py-12">
                  <div className="flex flex-col items-center gap-3">
                    <Loader2 className="h-6 w-6 animate-spin text-muted-foreground" />
                    <p className="text-muted-foreground text-sm">Loading diagnostics...</p>
                  </div>
                </div>
              ) : diagnostics.length > 0 ? (
                <div className="space-y-2">
                  {diagnostics.map((diagnostic, index) => {
                    const normalizedStatus = diagnostic.status || 'unknown'
                    return (
                      <div
                        key={`${diagnostic.probeId || 'probe'}-${index}`}
                        className="flex items-center justify-between rounded-lg border bg-card p-3 transition-colors hover:bg-muted/30"
                      >
                        <div className="min-w-0">
                          <p className="font-mono text-xs truncate">{diagnostic.probeId}</p>
                          <p className="text-xs text-muted-foreground mt-0.5">
                            {diagnostic.service} · {diagnostic.env}
                          </p>
                        </div>
                        <Badge
                          variant="secondary"
                          className={cn(
                            'text-xs shrink-0 ml-3',
                            statusColors[normalizedStatus] || statusColors.unknown
                          )}
                        >
                          {formatStatusLabel(normalizedStatus)}
                        </Badge>
                      </div>
                    )
                  })}
                </div>
              ) : (
                <div className="py-12 text-center">
                  <div className="mx-auto mb-4 flex h-12 w-12 items-center justify-center rounded-xl bg-gradient-to-br from-purple-500/10 to-pink-500/10">
                    <Activity className="h-6 w-6 text-purple-500" />
                  </div>
                  <p className="text-sm font-medium mb-1">No probe diagnostics</p>
                  <p className="text-xs text-muted-foreground">Probe status will appear here once agents report.</p>
                </div>
              )}
            </CardContent>
          </Card>

          <Card className="overflow-hidden border-border/60">
            <CardHeader>
              <CardTitle className="flex items-center gap-2 text-base">
                <Bug className="h-4 w-4 text-pink-500" />
                Probe Logs
              </CardTitle>
              <CardDescription>Recent log probe and snapshot entries.</CardDescription>
            </CardHeader>
            <CardContent>
              {logsLoading ? (
                <div className="flex items-center justify-center py-12">
                  <div className="flex flex-col items-center gap-3">
                    <Loader2 className="h-6 w-6 animate-spin text-muted-foreground" />
                    <p className="text-muted-foreground text-sm">Loading logs...</p>
                  </div>
                </div>
              ) : logs.length > 0 ? (
                <div className="space-y-2">
                  {logs.map((log, index: number) => (
                    <div
                      key={`${log.probeId || 'probe'}-${log.timestamp || index}`}
                      className="rounded-lg border bg-card p-3 transition-colors hover:bg-muted/30"
                    >
                      <div className="mb-1.5 flex items-center justify-between">
                        <Badge variant="outline" className="text-xs">{log.debuggerType}</Badge>
                        <span className="text-xs text-muted-foreground">{log.timestamp}</span>
                      </div>
                      <p className="text-sm leading-relaxed">{log.message || 'No message'}</p>
                      <p className="mt-1.5 text-xs text-muted-foreground">{log.service} · {log.probeId}</p>
                    </div>
                  ))}
                </div>
              ) : (
                <div className="py-12 text-center">
                  <div className="mx-auto mb-4 flex h-12 w-12 items-center justify-center rounded-xl bg-gradient-to-br from-purple-500/10 to-pink-500/10">
                    <Bug className="h-6 w-6 text-pink-500" />
                  </div>
                  <p className="text-sm font-medium mb-1">No probe logs</p>
                  <p className="text-xs text-muted-foreground">Log entries will appear here once probes emit data.</p>
                </div>
              )}
            </CardContent>
          </Card>
        </div>
      </div>
    </>
  )
}
