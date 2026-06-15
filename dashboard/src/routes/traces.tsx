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
import {PageHeader} from '@/components/ui/page-header'
import {EmptyState} from '@/components/ui/empty-state'
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
  redis: {bg: 'bg-chart-8/15', text: 'text-chart-8', border: 'border-chart-8/30', dot: 'bg-chart-8'},
  postgresql: {bg: 'bg-chart-2/15', text: 'text-chart-2', border: 'border-chart-2/30', dot: 'bg-chart-2'},
  kafka: {bg: 'bg-chart-3/15', text: 'text-chart-3', border: 'border-chart-3/30', dot: 'bg-chart-3'},
  elasticsearch: {bg: 'bg-chart-5/15', text: 'text-chart-5', border: 'border-chart-5/30', dot: 'bg-chart-5'},
  mongodb: {bg: 'bg-chart-4/15', text: 'text-chart-4', border: 'border-chart-4/30', dot: 'bg-chart-4'},
}

function getServiceColor(service: string) {
  const key = Object.keys(serviceColors).find(k => service.toLowerCase().includes(k))
  if (key) return serviceColors[key]
  return {bg: 'bg-chart-9/15', text: 'text-chart-9', border: 'border-chart-9/30', dot: 'bg-chart-9'}
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
      rate >= 0.1 ? 'text-danger-fg' : rate >= 0.01 ? 'text-warning-fg' : 'text-muted-foreground',
    )}>
      {rate >= 0.01 && <AlertCircle className="h-3 w-3" />}
      {pct}%
    </span>
  )
}

function Traces() {
  const [serviceFilter, setServiceFilter] = useState('')

  const {data, isLoading, isError} = useQuery({
    queryKey: ['apm-resource-stats'],
    queryFn: () => api.getApmResourceStats({limit: 200}),
    refetchInterval: 60000,
  })

  const resources = useMemo(() => data?.resources ?? [], [data?.resources])

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
      <PageHeader
        title="APM Traces"
        description="Resource performance aggregated from trace stats"
        icon={Activity}
        actions={
          <div className="relative w-64">
            <Search className="absolute left-3 top-1/2 -translate-y-1/2 h-4 w-4 text-muted-foreground pointer-events-none" />
            <Input
              placeholder="Filter by service..."
              value={serviceFilter}
              onChange={e => setServiceFilter(e.target.value)}
              className="pl-9 h-9"
            />
          </div>
        }
      />

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
          <div className="animate-spin rounded-full h-8 w-8 border-2 border-muted border-t-primary" />
        </div>
      ) : isError ? (
        <EmptyState
          icon={AlertCircle}
          title="Couldn't load trace stats"
          description="Something went wrong while loading trace stats. Try refreshing in a moment."
        />
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
                        r.avgDurationNs > 500_000_000 ? 'text-danger-fg'
                          : r.avgDurationNs > 100_000_000 ? 'text-warning-fg'
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
        <EmptyState
          icon={Inbox}
          title="No trace stats found"
          description={
            serviceFilter
              ? 'No resources match your current filter.'
              : 'No APM trace stats have been recorded yet. Make sure your tracing agent is sending stats.'
          }
        />
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
