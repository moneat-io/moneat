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
import {Badge} from '@/components/ui/badge'
import {Card, CardContent, CardHeader, CardTitle, CardDescription} from '@/components/ui/card'
import {Activity, BookOpen, Bug, Loader2} from 'lucide-react'
import {cn} from '@/lib/utils'

export const Route = createFileRoute('/monitoring/debugger')({
  component: DebuggerDashboard,
})

const statusColors: Record<string, string> = {
  received: 'bg-blue-500/15 text-blue-700 dark:text-blue-300 border-blue-500/20',
  installed: 'bg-emerald-500/15 text-emerald-700 dark:text-emerald-300 border-emerald-500/20',
  emitting: 'bg-emerald-500/15 text-emerald-700 dark:text-emerald-300 border-emerald-500/20',
  error: 'bg-red-500/15 text-red-700 dark:text-red-300 border-red-500/20',
  blocked: 'bg-amber-500/15 text-amber-700 dark:text-amber-300 border-amber-500/20',
}

function DebuggerDashboard() {
  const {data: logsData, isLoading: logsLoading} = useQuery({
    queryKey: ['debugger-logs'],
    queryFn: () => api.get('/v1/infra/debugger/logs?limit=50'),
  })

  const {data: diagData, isLoading: diagLoading} = useQuery({
    queryKey: ['debugger-diagnostics'],
    queryFn: () => api.get('/v1/infra/debugger/diagnostics?limit=50'),
  })

  const logs = logsData?.logs || []
  const diagnostics = diagData?.diagnostics || []

  return (
    <div className="container mx-auto px-4 py-4 space-y-4">
      <div className="flex items-center justify-between">
        <div className="flex items-center gap-3">
          <div className="flex items-center justify-center h-10 w-10 rounded-lg bg-gradient-to-br from-purple-500 to-pink-600">
            <Bug className="h-5 w-5 text-white" />
          </div>
          <div>
            <h1 className="text-2xl font-bold tracking-tight">Dynamic Instrumentation</h1>
            <p className="text-muted-foreground mt-1">Probe logs, snapshots, and diagnostics</p>
          </div>
        </div>
        <a href="/docs/datadog-agent/debugger" target="_blank" rel="noreferrer"
          className="inline-flex items-center gap-1.5 text-sm text-muted-foreground hover:text-foreground transition-colors">
          <BookOpen className="h-4 w-4" />
          View docs
        </a>
      </div>

      <div className="flex items-center gap-3 text-sm">
        <div className="flex items-center gap-1.5">
          <Activity className="h-3.5 w-3.5 text-purple-500" />
          <span className="font-semibold tabular-nums">{diagnostics.length}</span>
          <span className="text-muted-foreground text-xs">probes</span>
        </div>
        <div className="h-4 w-px bg-border" />
        <div className="flex items-center gap-1.5">
          <Bug className="h-3.5 w-3.5 text-pink-500" />
          <span className="font-semibold tabular-nums">{logs.length}</span>
          <span className="text-muted-foreground text-xs">log entries</span>
        </div>
      </div>

      <div className="grid gap-4 lg:grid-cols-2">
        <Card className="overflow-hidden border-border/60 shadow-sm">
          <CardHeader>
            <CardTitle className="flex items-center gap-2 text-base">
              <Activity className="h-4 w-4 text-purple-500" />
              Probe Status
            </CardTitle>
            <CardDescription>Installation status of active probes</CardDescription>
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
                {diagnostics.map((d: any, i: number) => (
                  <div key={i} className="flex items-center justify-between p-3 rounded-lg border bg-card hover:bg-muted/30 transition-colors">
                    <div className="min-w-0">
                      <p className="font-mono text-xs truncate">{d.probeId}</p>
                      <p className="text-xs text-muted-foreground mt-0.5">{d.service} · {d.env}</p>
                    </div>
                    <Badge variant="secondary" className={cn('text-xs shrink-0 ml-3', statusColors[d.status] || '')}>
                      {d.status}
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
                <p className="text-xs text-muted-foreground">Probe status will appear here once active.</p>
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
            <CardDescription>Recent log probe and snapshot entries</CardDescription>
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
                {logs.map((l: any, i: number) => (
                  <div key={i} className="p-3 rounded-lg border bg-card hover:bg-muted/30 transition-colors">
                    <div className="flex items-center justify-between mb-1.5">
                      <Badge variant="outline" className="text-xs">{l.debuggerType}</Badge>
                      <span className="text-xs text-muted-foreground">{l.timestamp}</span>
                    </div>
                    <p className="text-sm leading-relaxed">{l.message || 'No message'}</p>
                    <p className="text-xs text-muted-foreground mt-1.5">{l.service} · {l.probeId}</p>
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
  )
}
