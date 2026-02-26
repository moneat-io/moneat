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
import {TraceList} from '@/components/apm/TraceList'
import {ServiceMap} from '@/components/apm/ServiceMap'
import {Tabs, TabsContent, TabsList, TabsTrigger} from '@/components/ui/tabs'
import {Input} from '@/components/ui/input'
import {
  Select,
  SelectContent,
  SelectItem,
  SelectTrigger,
  SelectValue,
} from '@/components/ui/select'
import {
  Activity,
  AlertTriangle,
  Clock,
  Layers,
  Search,
  Server,
} from 'lucide-react'
import {useState, useMemo} from 'react'

export const Route = createFileRoute('/apm-traces/')({
  component: ApmTracesIndexPage,
})

function formatDuration(ns: number): string {
  if (ns < 1000) return `${ns}ns`
  if (ns < 1_000_000) return `${(ns / 1000).toFixed(1)}µs`
  if (ns < 1_000_000_000) return `${(ns / 1_000_000).toFixed(1)}ms`
  return `${(ns / 1_000_000_000).toFixed(2)}s`
}

function ApmTracesIndexPage() {
  const [serviceFilter, setServiceFilter] = useState('')
  const [envFilter, setEnvFilter] = useState('')

  const {data} = useQuery({
    queryKey: ['apmTraces', serviceFilter, envFilter],
    queryFn: () =>
      api.getApmTraces({
        service: serviceFilter || undefined,
        env: envFilter || undefined,
        limit: 100,
      }),
    enabled: api.isAuthenticated(),
    refetchInterval: 15000,
  })

  const traces = data?.traces ?? []

  const stats = useMemo(() => {
    if (traces.length === 0) return null

    const errorCount = traces.filter((t) => t.hasError).length
    const services = new Set(traces.map((t) => t.rootService))
    const durations = traces.map((t) => t.durationNs).sort((a, b) => a - b)
    const p50 = durations[Math.floor(durations.length * 0.5)] ?? 0
    const p95 = durations[Math.floor(durations.length * 0.95)] ?? 0
    const avgDuration =
      durations.reduce((sum, d) => sum + d, 0) / durations.length

    return {
      totalTraces: data?.totalCount ?? traces.length,
      errorCount,
      errorRate: ((errorCount / traces.length) * 100).toFixed(1),
      serviceCount: services.size,
      p50,
      p95,
      avgDuration,
    }
  }, [traces, data?.totalCount])

  const availableEnvs = useMemo(() => {
    const envs = new Set<string>()
    // We don't have env on ApmTraceListItem, so this is placeholder
    // The API accepts env filter though
    return [...envs]
  }, [])

  return (
    <div className="p-6 space-y-5">
      {/* Header */}
      <div className="flex items-start justify-between gap-4">
        <div>
          <h1 className="text-2xl font-bold tracking-tight">APM Traces</h1>
          <p className="text-muted-foreground text-sm mt-0.5">
            Distributed tracing and application performance monitoring
          </p>
        </div>

        {/* Filters */}
        <div className="flex items-center gap-2 shrink-0">
          <div className="relative">
            <Search className="absolute left-2.5 top-2.5 h-4 w-4 text-muted-foreground" />
            <Input
              placeholder="Filter by service..."
              value={serviceFilter}
              onChange={(e) => setServiceFilter(e.target.value)}
              className="pl-9 h-9 w-[200px]"
            />
          </div>
          {availableEnvs.length > 0 && (
            <Select
              value={envFilter || '__all'}
              onValueChange={(v) => setEnvFilter(v === '__all' ? '' : v)}
            >
              <SelectTrigger className="h-9 w-[140px]">
                <SelectValue placeholder="All envs" />
              </SelectTrigger>
              <SelectContent>
                <SelectItem value="__all">All environments</SelectItem>
                {availableEnvs.map((env) => (
                  <SelectItem key={env} value={env}>
                    {env}
                  </SelectItem>
                ))}
              </SelectContent>
            </Select>
          )}
        </div>
      </div>

      {/* Summary stats */}
      {stats && (
        <div className="grid grid-cols-2 md:grid-cols-5 gap-3">
          <StatCard
            label="Total Traces"
            value={stats.totalTraces.toLocaleString()}
            icon={<Layers className="h-4 w-4" />}
          />
          <StatCard
            label="Services"
            value={String(stats.serviceCount)}
            icon={<Server className="h-4 w-4" />}
          />
          <StatCard
            label="Error Rate"
            value={`${stats.errorRate}%`}
            icon={<AlertTriangle className="h-4 w-4" />}
            variant={stats.errorCount > 0 ? 'error' : 'default'}
            subtitle={`${stats.errorCount} errors`}
          />
          <StatCard
            label="p50 Latency"
            value={formatDuration(stats.p50)}
            icon={<Clock className="h-4 w-4" />}
          />
          <StatCard
            label="p95 Latency"
            value={formatDuration(stats.p95)}
            icon={<Activity className="h-4 w-4" />}
            variant={stats.p95 > stats.p50 * 4 ? 'warning' : 'default'}
          />
        </div>
      )}

      {/* Tabs */}
      <Tabs defaultValue="traces">
        <TabsList>
          <TabsTrigger value="traces" className="gap-1.5">
            <Layers className="h-3.5 w-3.5" />
            Traces
          </TabsTrigger>
          <TabsTrigger value="services" className="gap-1.5">
            <Server className="h-3.5 w-3.5" />
            Service Map
          </TabsTrigger>
        </TabsList>
        <TabsContent value="traces" className="mt-4">
          <TraceList serviceFilter={serviceFilter || undefined} envFilter={envFilter || undefined} />
        </TabsContent>
        <TabsContent value="services" className="mt-4">
          <ServiceMap />
        </TabsContent>
      </Tabs>
    </div>
  )
}

function StatCard({
  label,
  value,
  icon,
  variant = 'default',
  subtitle,
}: {
  label: string
  value: string
  icon: React.ReactNode
  variant?: 'default' | 'error' | 'warning'
  subtitle?: string
}) {
  return (
    <div className="rounded-lg border bg-card px-4 py-3 flex flex-col gap-1">
      <div className="flex items-center gap-1.5 text-muted-foreground">
        {icon}
        <span className="text-xs font-medium">{label}</span>
      </div>
      <span
        className={`text-xl font-bold tabular-nums tracking-tight ${
          variant === 'error'
            ? 'text-red-500'
            : variant === 'warning'
              ? 'text-amber-500'
              : ''
        }`}
      >
        {value}
      </span>
      {subtitle && (
        <span className="text-[11px] text-muted-foreground">{subtitle}</span>
      )}
    </div>
  )
}
