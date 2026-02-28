// Moneat - observability platform
// Copyright (C) 2026 Moneat
//
// This program is free software: you can redistribute it and/or modify
// it under the terms of the GNU Affero General Public License as published by
// the Free Software Foundation, either version 3 of the License, or
// (at your option) any later version.

import {createFileRoute} from '@tanstack/react-router'
import {useMutation, useQuery, useQueryClient} from '@tanstack/react-query'
import {api, type DebuggerProbe} from '@/lib/api'
import CreateProbeDialog from '@/components/debugger/CreateProbeDialog'
import {Badge} from '@/components/ui/badge'
import {Button} from '@/components/ui/button'
import {Card, CardContent, CardDescription, CardHeader, CardTitle} from '@/components/ui/card'
import {Switch} from '@/components/ui/switch'
import {useToast} from '@/hooks/use-toast'
import {Activity, BookOpen, Bug, Loader2, Pencil, Plus, Trash2} from 'lucide-react'
import {cn} from '@/lib/utils'
import {useMemo, useState} from 'react'

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

function formatProbeType(probeType: string): string {
  switch (probeType) {
    case 'log_probe':
      return 'Log Probe'
    case 'snapshot':
      return 'Snapshot'
    case 'span_decoration':
      return 'Span Decoration'
    case 'metric_probe':
      return 'Metric Probe'
    default:
      return probeType
  }
}

function formatProbeLocation(probe: DebuggerProbe): string {
  if (probe.whereType === 'line') {
    return `${probe.sourceFile || 'Unknown file'}:${probe.sourceLines || '?'}`
  }

  const typeName = probe.typeName || 'Unknown type'
  const methodName = probe.methodName || 'unknownMethod'
  return `${typeName}.${methodName}()`
}

function DebuggerDashboard() {
  const {toast} = useToast()
  const queryClient = useQueryClient()
  const [createDialogOpen, setCreateDialogOpen] = useState(false)
  const [editingProbe, setEditingProbe] = useState<DebuggerProbe | null>(null)

  const {data: probesData, isLoading: probesLoading} = useQuery({
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

  const toggleMutation = useMutation({
    mutationFn: ({probeId, active}: {probeId: string; active: boolean}) =>
      api.updateDebuggerProbe(probeId, {active}),
    onSuccess: () => {
      queryClient.invalidateQueries({queryKey: ['debugger-probes']})
      toast({title: 'Probe updated'})
    },
    onError: (error: Error) => {
      toast({
        title: 'Failed to update probe',
        description: error.message,
        variant: 'destructive',
      })
    },
  })

  const deleteMutation = useMutation({
    mutationFn: (probeId: string) => api.deleteDebuggerProbe(probeId),
    onSuccess: () => {
      queryClient.invalidateQueries({queryKey: ['debugger-probes']})
      toast({title: 'Probe deleted'})
    },
    onError: (error: Error) => {
      toast({
        title: 'Failed to delete probe',
        description: error.message,
        variant: 'destructive',
      })
    },
  })

  const probes = probesData?.probes ?? []
  const logs = logsData?.logs ?? []
  const diagnostics = diagData?.diagnostics ?? []

  const latestDiagnosticByProbeId = useMemo(() => {
    const result = new Map<string, DebuggerDiagnostic>()

    diagnostics.forEach((diagnostic) => {
      const probeId = diagnostic.probeId?.trim()
      if (!probeId || result.has(probeId)) {
        return
      }
      result.set(probeId, diagnostic)
    })

    return result
  }, [diagnostics])

  const dialogOpen = createDialogOpen || editingProbe !== null

  return (
    <>
      <div className="container mx-auto px-4 py-4 space-y-4">
        <div className="flex items-center justify-between gap-3">
          <div className="flex items-center gap-3">
            <div className="flex items-center justify-center h-10 w-10 rounded-lg bg-gradient-to-br from-purple-500 to-pink-600">
              <Bug className="h-5 w-5 text-white" />
            </div>
            <div>
              <h1 className="text-2xl font-bold tracking-tight">Dynamic Instrumentation</h1>
              <p className="text-muted-foreground mt-1">Probe definitions, diagnostics, and execution logs</p>
            </div>
          </div>

          <div className="flex items-center gap-2">
            <Button
              onClick={() => {
                setEditingProbe(null)
                setCreateDialogOpen(true)
              }}
            >
              <Plus className="h-4 w-4 mr-1.5" />
              Create Probe
            </Button>
            <a
              href="/docs/datadog-agent/debugger"
              target="_blank"
              rel="noreferrer"
              className="inline-flex items-center gap-1.5 text-sm text-muted-foreground hover:text-foreground transition-colors"
            >
              <BookOpen className="h-4 w-4" />
              View docs
            </a>
          </div>
        </div>

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

        <Card className="overflow-hidden border-border/60 shadow-sm">
          <CardHeader>
            <CardTitle className="flex items-center gap-2 text-base">
              <Activity className="h-4 w-4 text-purple-500" />
              Probe Definitions
            </CardTitle>
            <CardDescription>Create and manage dynamic instrumentation probes.</CardDescription>
          </CardHeader>
          <CardContent>
            {probesLoading ? (
              <div className="flex items-center justify-center py-12">
                <div className="flex flex-col items-center gap-3">
                  <Loader2 className="h-6 w-6 animate-spin text-muted-foreground" />
                  <p className="text-muted-foreground text-sm">Loading probe definitions...</p>
                </div>
              </div>
            ) : probes.length > 0 ? (
              <div className="space-y-2">
                {probes.map((probe) => {
                  const diagnostic = latestDiagnosticByProbeId.get(probe.id)
                  const status = diagnostic?.status || 'unknown'

                  return (
                    <div
                      key={probe.id}
                      className="rounded-lg border bg-card p-3 transition-colors hover:bg-muted/30"
                    >
                      <div className="flex flex-col gap-3 lg:flex-row lg:items-center lg:justify-between">
                        <div className="min-w-0 space-y-1">
                          <div className="flex flex-wrap items-center gap-2">
                            <Badge variant="outline" className="text-xs">
                              {formatProbeType(probe.probeType)}
                            </Badge>
                            <Badge
                              variant="secondary"
                              className={cn('text-xs', statusColors[status] || statusColors.unknown)}
                            >
                              {status}
                            </Badge>
                            <span className="text-xs text-muted-foreground">
                              {probe.active ? 'Active' : 'Paused'}
                            </span>
                          </div>

                          <p className="text-sm font-medium">
                            {probe.service} · {probe.environment} · {probe.language}
                          </p>
                          <p className="text-xs text-muted-foreground">{formatProbeLocation(probe)}</p>
                        </div>

                        <div className="flex items-center gap-2 shrink-0">
                          <Switch
                            checked={probe.active}
                            onCheckedChange={(checked) =>
                              toggleMutation.mutate({probeId: probe.id, active: checked})
                            }
                            disabled={toggleMutation.isPending}
                            aria-label={`Toggle ${probe.id}`}
                          />
                          <Button
                            variant="ghost"
                            size="icon"
                            onClick={() => setEditingProbe(probe)}
                            aria-label={`Edit ${probe.id}`}
                          >
                            <Pencil className="h-4 w-4" />
                          </Button>
                          <Button
                            variant="ghost"
                            size="icon"
                            onClick={() => {
                              const confirmed = window.confirm('Delete this probe definition?')
                              if (!confirmed) return
                              deleteMutation.mutate(probe.id)
                            }}
                            aria-label={`Delete ${probe.id}`}
                            disabled={deleteMutation.isPending}
                          >
                            <Trash2 className="h-4 w-4 text-red-600" />
                          </Button>
                        </div>
                      </div>
                    </div>
                  )
                })}
              </div>
            ) : (
              <div className="py-12 text-center">
                <div className="mx-auto mb-4 flex h-12 w-12 items-center justify-center rounded-xl bg-gradient-to-br from-purple-500/10 to-pink-500/10">
                  <Activity className="h-6 w-6 text-purple-500" />
                </div>
                <p className="text-sm font-medium mb-1">No probe definitions yet</p>
                <p className="text-xs text-muted-foreground mb-4">
                  Create a probe to start collecting debugger diagnostics and logs.
                </p>
                <Button
                  onClick={() => {
                    setEditingProbe(null)
                    setCreateDialogOpen(true)
                  }}
                >
                  <Plus className="h-4 w-4 mr-1.5" />
                  Create Probe
                </Button>
              </div>
            )}
          </CardContent>
        </Card>

        <div className="grid gap-4 lg:grid-cols-2">
          <Card className="overflow-hidden border-border/60 shadow-sm">
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
                  {diagnostics.map((diagnostic, index) => (
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
                        className={cn('text-xs shrink-0 ml-3', statusColors[diagnostic.status ?? ''] || '')}
                      >
                        {diagnostic.status}
                      </Badge>
                    </div>
                  ))}
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

          <Card className="overflow-hidden border-border/60 shadow-sm">
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

      <CreateProbeDialog
        key={`${editingProbe?.id ?? 'create'}-${dialogOpen ? 'open' : 'closed'}`}
        open={dialogOpen}
        onOpenChange={(open) => {
          if (!open) {
            setCreateDialogOpen(false)
            setEditingProbe(null)
            return
          }
          if (editingProbe == null) {
            setCreateDialogOpen(true)
          }
        }}
        probe={editingProbe}
      />
    </>
  )
}
