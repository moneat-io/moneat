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

import {createFileRoute} from '@tanstack/react-router'
import {useQuery} from '@tanstack/react-query'
import {api} from '@/lib/api'
import {TraceList} from '@/components/apm/TraceList'
import {
  Activity,
  AlertTriangle,
  Clock,
  Layers,
  Server,
} from 'lucide-react'
import {useMemo} from 'react'

export const Route = createFileRoute('/performance/traces/')({
  component: PerformanceTracesPage,
})

function formatDuration(ns: number): string {
  if (ns < 1000) return `${ns}ns`
  if (ns < 1_000_000) return `${(ns / 1000).toFixed(1)}µs`
  if (ns < 1_000_000_000) return `${(ns / 1_000_000).toFixed(1)}ms`
  return `${(ns / 1_000_000_000).toFixed(2)}s`
}

function PerformanceTracesPage() {
  const {data} = useQuery({
    queryKey: ['apmTraces', undefined, undefined],
    queryFn: () =>
      api.getApmTraces({
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

    return {
      totalTraces: data?.totalCount ?? traces.length,
      errorCount,
      errorRate: ((errorCount / traces.length) * 100).toFixed(1),
      serviceCount: services.size,
      p50,
      p95,
    }
  }, [traces, data?.totalCount])

  return (
    <div className="p-6 space-y-5">
      {/* Header */}
      <div>
        <h1 className="text-2xl font-bold tracking-tight">Distributed Traces</h1>
        <p className="text-muted-foreground text-sm mt-0.5">
          Individual trace view from distributed tracing
        </p>
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

      {/* Trace list */}
      <TraceList basePath="/performance/traces" />
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
