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
import type {ApmResourceStatsItem} from '@/lib/api'
import {Badge} from '@/components/ui/badge'
import {Input} from '@/components/ui/input'
import {
  Table,
  TableBody,
  TableCell,
  TableHead,
  TableHeader,
  TableRow,
} from '@/components/ui/table'
import {Activity, Search, Inbox, AlertCircle} from 'lucide-react'
import {useState, useMemo} from 'react'
import {cn} from '@/lib/utils'

export const Route = createFileRoute('/traces')({
  component: Traces,
})

const serviceColors: Record<string, {bg: string; text: string; border: string; dot: string}> = {
  redis: {bg: 'bg-red-500/10', text: 'text-red-400', border: 'border-red-500/20', dot: 'bg-red-500'},
  postgresql: {bg: 'bg-blue-500/10', text: 'text-blue-400', border: 'border-blue-500/20', dot: 'bg-blue-500'},
  kafka: {bg: 'bg-emerald-500/10', text: 'text-emerald-400', border: 'border-emerald-500/20', dot: 'bg-emerald-500'},
  elasticsearch: {bg: 'bg-amber-500/10', text: 'text-amber-400', border: 'border-amber-500/20', dot: 'bg-amber-500'},
  mongodb: {bg: 'bg-green-500/10', text: 'text-green-400', border: 'border-green-500/20', dot: 'bg-green-500'},
}

function getServiceColor(service: string) {
  const key = Object.keys(serviceColors).find(k => service.toLowerCase().includes(k))
  if (key) return serviceColors[key]
  return {bg: 'bg-violet-500/10', text: 'text-violet-400', border: 'border-violet-500/20', dot: 'bg-violet-500'}
}

function formatDuration(ns: number) {
  if (ns < 1_000) return `${ns}ns`
  if (ns < 1_000_000) return `${(ns / 1_000).toFixed(2)}µs`
  if (ns < 1_000_000_000) return `${(ns / 1_000_000).toFixed(2)}ms`
  return `${(ns / 1_000_000_000).toFixed(2)}s`
}

function formatHits(hits: number) {
  if (hits >= 1_000_000) return `${(hits / 1_000_000).toFixed(1)}M`
  if (hits >= 1_000) return `${(hits / 1_000).toFixed(1)}k`
  return String(hits)
}

function ErrorRateBadge({rate}: {rate: number}) {
  const pct = (rate * 100).toFixed(1)
  if (rate === 0) return <span className="text-sm text-muted-foreground tabular-nums">0%</span>
  return (
    <span className={cn(
      'text-sm font-semibold tabular-nums flex items-center gap-1',
      rate >= 0.1 ? 'text-red-400' : rate >= 0.01 ? 'text-amber-400' : 'text-muted-foreground',
    )}>
      {rate >= 0.01 && <AlertCircle className="h-3 w-3" />}
      {pct}%
    </span>
  )
}

function Traces() {
  const [serviceFilter, setServiceFilter] = useState('')

  const {data, isLoading, isError, error} = useQuery({
    queryKey: ['apm-resource-stats'],
    queryFn: () => api.getApmResourceStats({limit: 200}),
    refetchInterval: 60000,
  })

  const resources = data?.resources ?? []

  const services = useMemo(
    () => [...new Set(resources.map(r => r.service))].sort(),
    [resources],
  )

  const filtered = useMemo(() => {
    if (!serviceFilter) return resources
    return resources.filter(r =>
      r.service.toLowerCase().includes(serviceFilter.toLowerCase()),
    )
  }, [resources, serviceFilter])

  return (
    <div className="p-6 space-y-6">
      {/* Header */}
      <div className="flex items-start justify-between gap-4">
        <div className="flex items-center gap-3">
          <div className="flex items-center justify-center h-10 w-10 rounded-lg bg-gradient-to-br from-violet-500 to-indigo-600 shadow-lg shadow-violet-500/20">
            <Activity className="h-5 w-5 text-white" />
          </div>
          <div>
            <h1 className="text-2xl font-bold tracking-tight">APM Traces</h1>
            <p className="text-sm text-muted-foreground">
              Resource performance aggregated from trace stats
            </p>
          </div>
        </div>
        <div className="relative w-64">
          <Search className="absolute left-3 top-1/2 -translate-y-1/2 h-4 w-4 text-muted-foreground pointer-events-none" />
          <Input
            placeholder="Filter by service..."
            value={serviceFilter}
            onChange={e => setServiceFilter(e.target.value)}
            className="pl-9 h-9"
          />
        </div>
      </div>

      {/* Service chips */}
      {services.length > 1 && (
        <div className="flex flex-wrap gap-1.5">
          <button
            onClick={() => setServiceFilter('')}
            className={cn(
              'px-2.5 py-1 rounded-md text-xs font-medium border transition-all',
              !serviceFilter
                ? 'bg-foreground/10 border-foreground/20 text-foreground'
                : 'border-transparent text-muted-foreground hover:text-foreground hover:bg-muted/60',
            )}
          >
            All services
          </button>
          {services.map(s => {
            const sc = getServiceColor(s)
            const isActive = serviceFilter.toLowerCase() === s.toLowerCase()
            return (
              <button
                key={s}
                onClick={() => setServiceFilter(isActive ? '' : s)}
                aria-pressed={isActive}
                className={cn(
                  'px-2.5 py-1 rounded-md text-xs font-medium border transition-all',
                  isActive
                    ? `${sc.bg} ${sc.text} ${sc.border}`
                    : 'border-transparent text-muted-foreground hover:text-foreground hover:bg-muted/60',
                )}
              >
                {s}
              </button>
            )
          })}
        </div>
      )}

      {/* Table */}
      {isLoading ? (
        <div className="flex items-center justify-center py-20">
          <div className="animate-spin rounded-full h-8 w-8 border-2 border-muted border-t-violet-500" />
        </div>
      ) : isError ? (
        <div className="flex flex-col items-center justify-center py-20 border border-dashed rounded-xl">
          <h3 className="text-base font-semibold mb-1">Failed to load trace stats</h3>
          <p className="text-sm text-muted-foreground text-center max-w-sm">
            {error instanceof Error ? error.message : 'An unexpected error occurred.'}
          </p>
        </div>
      ) : filtered.length > 0 ? (
        <div className="rounded-xl border overflow-hidden">
          <Table>
            <TableHeader>
              <TableRow className="hover:bg-transparent">
                <TableHead className="w-[140px]">Service</TableHead>
                <TableHead>Resource</TableHead>
                <TableHead className="w-[100px] text-right">Requests</TableHead>
                <TableHead className="w-[120px] text-right">Avg Latency</TableHead>
                <TableHead className="w-[110px] text-right">Error Rate</TableHead>
              </TableRow>
            </TableHeader>
            <TableBody>
              {filtered.map((r: ApmResourceStatsItem, i: number) => {
                const sc = getServiceColor(r.service)
                return (
                  <TableRow key={`${r.service}-${r.resource}-${i}`} className="group">
                    <TableCell>
                      <Badge
                        variant="outline"
                        className={cn('text-xs font-medium gap-1.5', sc.bg, sc.text, sc.border)}
                      >
                        <span className={cn('h-1.5 w-1.5 rounded-full flex-shrink-0', sc.dot)} />
                        {r.service}
                      </Badge>
                    </TableCell>
                    <TableCell>
                      <div className="space-y-0.5 min-w-0">
                        <p className="text-sm font-medium truncate max-w-xl" title={r.resource}>
                          {r.resource}
                        </p>
                        {r.name && r.name !== r.resource && (
                          <p className="text-xs text-muted-foreground truncate">{r.name}</p>
                        )}
                      </div>
                    </TableCell>
                    <TableCell className="text-right">
                      <span className="text-sm tabular-nums font-semibold">
                        {formatHits(r.totalHits)}
                      </span>
                    </TableCell>
                    <TableCell className="text-right">
                      <span className={cn(
                        'text-sm tabular-nums font-semibold',
                        r.avgDurationNs > 500_000_000 ? 'text-red-400'
                          : r.avgDurationNs > 100_000_000 ? 'text-amber-400'
                          : 'text-foreground',
                      )}>
                        {r.avgDurationNs > 0 ? formatDuration(r.avgDurationNs) : '—'}
                      </span>
                    </TableCell>
                    <TableCell className="text-right">
                      <ErrorRateBadge rate={r.errorRate} />
                    </TableCell>
                  </TableRow>
                )
              })}
            </TableBody>
          </Table>
        </div>
      ) : (
        <div className="flex flex-col items-center justify-center py-20 border border-dashed rounded-xl">
          <div className="inline-flex items-center justify-center h-14 w-14 rounded-full bg-muted/60 mb-4">
            <Inbox className="h-7 w-7 text-muted-foreground" />
          </div>
          <h3 className="text-base font-semibold mb-1">No trace stats found</h3>
          <p className="text-sm text-muted-foreground text-center max-w-sm">
            {serviceFilter
              ? 'No resources match your current filter.'
              : 'No APM trace stats have been recorded yet. Make sure your tracing agent is sending stats.'}
          </p>
        </div>
      )}

      {/* Footer */}
      {!isLoading && filtered.length > 0 && (
        <div className="flex items-center justify-between text-xs text-muted-foreground px-1">
          <span>
            Showing {filtered.length} resource{filtered.length !== 1 ? 's' : ''}
            {serviceFilter && ` matching "${serviceFilter}"`}
            {data?.totalCount != null && data.totalCount > filtered.length && (
              <> of {data.totalCount} total</>
            )}
          </span>
          <span>Auto-refreshes every 60s</span>
        </div>
      )}
    </div>
  )
}
