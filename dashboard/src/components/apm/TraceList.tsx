// Moneat - observability platform
// Copyright (C) 2026 Moneat
//
// This program is free software: you can redistribute it and/or modify
// it under the terms of the GNU Affero General Public License as published by
// the Free Software Foundation, either version 3 of the License, or
// (at your option) any later version.

import {useQuery} from '@tanstack/react-query'
import {api, type ApmTraceListItem} from '@/lib/api'
import {Badge} from '@/components/ui/badge'
import {Input} from '@/components/ui/input'
import {SourceBadge} from '@/components/apm/SourceBadge'
import {
  Tooltip,
  TooltipContent,
  TooltipProvider,
  TooltipTrigger,
} from '@/components/ui/tooltip'
import {
  Loader2,
  AlertTriangle,
  Search,
  ArrowUpDown,
  ArrowUp,
  ArrowDown,
  Clock,
} from 'lucide-react'
import {useState, useMemo} from 'react'
import {keepPreviousData} from '@tanstack/react-query'
import {Link} from '@tanstack/react-router'
import {cn} from '@/lib/utils'

function formatDuration(ns: number): string {
  if (ns < 1000) return `${ns}ns`
  if (ns < 1_000_000) return `${(ns / 1000).toFixed(1)}µs`
  if (ns < 1_000_000_000) return `${(ns / 1_000_000).toFixed(1)}ms`
  return `${(ns / 1_000_000_000).toFixed(2)}s`
}

function formatRelativeTime(ns: number): string {
  if (!ns) return '—'
  const ms = ns / 1_000_000
  const diff = Date.now() - ms
  if (diff < 0) return 'just now'
  if (diff < 60_000) return `${Math.floor(diff / 1000)}s ago`
  if (diff < 3_600_000) return `${Math.floor(diff / 60_000)}m ago`
  if (diff < 86_400_000) return `${Math.floor(diff / 3_600_000)}h ago`
  return `${Math.floor(diff / 86_400_000)}d ago`
}

function formatAbsoluteTime(ns: number): string {
  if (!ns) return '—'
  return new Date(ns / 1_000_000).toLocaleString()
}

const SERVICE_COLORS = [
  'bg-chart-1',
  'bg-chart-2',
  'bg-chart-3',
  'bg-chart-4',
  'bg-chart-5',
  'bg-chart-6',
  'bg-chart-7',
  'bg-chart-8',
  'bg-chart-9',
  'bg-chart-10',
]

const TRACE_LIST_COLUMNS_CLASS =
  'grid-cols-[minmax(160px,1.5fr)_104px_minmax(220px,3fr)_72px_132px_minmax(110px,1fr)_76px]'

function getDurationColor(ns: number, maxNs: number): string {
  if (maxNs === 0) return 'text-foreground'
  const ratio = ns / maxNs
  if (ratio > 0.8) return 'text-danger-fg'
  if (ratio > 0.5) return 'text-warning-fg'
  return 'text-foreground'
}

type SortField = 'duration' | 'time' | 'spans'
type SortDir = 'asc' | 'desc'

interface TraceListProps {
  serviceFilter?: string
  envFilter?: string
  basePath?: string
}

export function TraceList({serviceFilter: externalService, envFilter, basePath}: TraceListProps) {
  const [localServiceFilter, setLocalServiceFilter] = useState('')
  const [sort, setSort] = useState<{field: SortField; dir: SortDir}>({
    field: 'time',
    dir: 'desc',
  })

  const serviceFilter = externalService ?? localServiceFilter

  const {data, isLoading} = useQuery({
    queryKey: ['apmTraces', serviceFilter, envFilter],
    queryFn: () =>
      api.getApmTraces({
        service: serviceFilter || undefined,
        env: envFilter || undefined,
        limit: 100,
      }),
    enabled: api.isAuthenticated(),
    refetchInterval: 15000,
    placeholderData: keepPreviousData,
  })

  const traces = useMemo(() => data?.traces ?? [], [data?.traces])

  const serviceColorMap = useMemo(() => {
    const map = new Map<string, string>()
    const services = [...new Set(traces.map((t) => t.rootService))]
    services.forEach((svc, i) => {
      map.set(svc, SERVICE_COLORS[i % SERVICE_COLORS.length])
    })
    return map
  }, [traces])

  const maxDuration = useMemo(
    () => Math.max(...traces.map((t) => t.durationNs), 1),
    [traces]
  )

  const sortedTraces = useMemo(() => {
    const sorted = [...traces]
    sorted.sort((a, b) => {
      let cmp = 0
      switch (sort.field) {
        case 'duration':
          cmp = a.durationNs - b.durationNs
          break
        case 'time':
          cmp = a.startNs - b.startNs
          break
        case 'spans':
          cmp = a.spanCount - b.spanCount
          break
      }
      return sort.dir === 'asc' ? cmp : -cmp
    })
    return sorted
  }, [traces, sort])

  const toggleSort = (field: SortField) => {
    setSort((prev) =>
      prev.field === field
        ? {field, dir: prev.dir === 'asc' ? 'desc' : 'asc'}
        : {field, dir: 'desc'}
    )
  }

  const renderSortIcon = (field: SortField) => {
    if (sort.field !== field)
      return <ArrowUpDown className="h-3 w-3 text-muted-foreground/50" />
    return sort.dir === 'asc' ? (
      <ArrowUp className="h-3 w-3" />
    ) : (
      <ArrowDown className="h-3 w-3" />
    )
  }

  return (
    <TooltipProvider delayDuration={200}>
      <div className="space-y-3">
        {/* Filters row */}
        {externalService == null && (
          <div className="flex items-center gap-3">
            <div className="relative flex-1 max-w-sm">
              <Search className="absolute left-2.5 top-2.5 h-4 w-4 text-muted-foreground" />
              <Input
                placeholder="Filter by service..."
                value={localServiceFilter}
                onChange={(e) => setLocalServiceFilter(e.target.value)}
                className="pl-9 h-9"
              />
            </div>
            {data?.totalCount != null && (
              <span className="text-xs text-muted-foreground tabular-nums">
                {data.totalCount.toLocaleString()} traces
              </span>
            )}
          </div>
        )}

        {isLoading ? (
          <div className="flex items-center justify-center py-16">
            <div className="flex flex-col items-center gap-3">
              <Loader2 className="h-6 w-6 animate-spin text-muted-foreground" />
              <span className="text-sm text-muted-foreground">
                Loading traces...
              </span>
            </div>
          </div>
        ) : traces.length === 0 ? (
          <div className="flex flex-col items-center justify-center py-16 text-muted-foreground border rounded-lg border-dashed">
            <Clock className="h-10 w-10 mb-3 text-muted-foreground/40" />
            <p className="font-medium">No traces found</p>
            <p className="text-sm mt-1">
              Send traces via an OpenTelemetry SDK, Collector, or compatible agent.
            </p>
          </div>
        ) : (
          <div className="border rounded-lg overflow-x-auto">
            {/* Column headers */}
            <div
              className={cn(
                'grid min-w-[930px] gap-px bg-muted/50 border-b px-3 py-2 text-xs font-medium text-muted-foreground',
                TRACE_LIST_COLUMNS_CLASS
              )}
            >
              <div>Service</div>
              <div>Source</div>
              <div>Resource</div>
              <button
                onClick={() => toggleSort('spans')}
                className="flex items-center gap-1 hover:text-foreground transition-colors"
              >
                Spans {renderSortIcon('spans')}
              </button>
              <button
                onClick={() => toggleSort('duration')}
                className="flex items-center gap-1 hover:text-foreground transition-colors"
              >
                Duration {renderSortIcon('duration')}
              </button>
              <button
                onClick={() => toggleSort('time')}
                className="flex items-center gap-1 hover:text-foreground transition-colors"
              >
                Time {renderSortIcon('time')}
              </button>
              <div>Status</div>
            </div>

            {/* Trace rows */}
            <div className="divide-y">
              {sortedTraces.map((trace: ApmTraceListItem) => {
                const durationPct = (trace.durationNs / maxDuration) * 100

                return (
                  <Link
                    key={trace.traceId}
                    to={`${basePath || '/apm-traces'}/$traceId` as '/apm-traces/$traceId'}
                    params={{traceId: trace.traceId}}
                    className={cn(
                      'grid min-w-[930px] gap-px px-3 py-2 hover:bg-muted/40 transition-colors items-center group',
                      TRACE_LIST_COLUMNS_CLASS
                    )}
                  >
                    {/* Service */}
                    <div className="flex items-center gap-2 min-w-0">
                      <span
                        className={cn(
                          'w-2 h-2 rounded-full shrink-0',
                          serviceColorMap.get(trace.rootService) ?? 'bg-muted-foreground'
                        )}
                      />
                      <span className="font-medium text-sm truncate group-hover:text-primary transition-colors">
                        {trace.rootService}
                      </span>
                    </div>

                    {/* Source */}
                    <div className="flex items-center min-w-0">
                      <SourceBadge source={trace.source} className="max-w-[92px] truncate" />
                    </div>

                    {/* Resource */}
                    <div className="min-w-0">
                      <span className="text-sm text-muted-foreground truncate block font-mono text-xs">
                        {trace.rootResource}
                      </span>
                    </div>

                    {/* Spans */}
                    <div className="text-sm tabular-nums">{trace.spanCount}</div>

                    {/* Duration with bar */}
                    <div className="flex flex-col gap-0.5">
                      <span
                        className={cn(
                          'text-sm font-mono tabular-nums',
                          getDurationColor(trace.durationNs, maxDuration)
                        )}
                      >
                        {formatDuration(trace.durationNs)}
                      </span>
                      <div className="h-1 rounded-full bg-muted w-full">
                        <div
                          className={cn(
                            'h-full rounded-full transition-all',
                            trace.durationNs / maxDuration > 0.8
                              ? 'bg-danger-solid/70'
                              : trace.durationNs / maxDuration > 0.5
                                ? 'bg-warning-solid/70'
                                : 'bg-chart-2/60'
                          )}
                          style={{width: `${Math.max(durationPct, 2)}%`}}
                        />
                      </div>
                    </div>

                    {/* Time */}
                    <Tooltip>
                      <TooltipTrigger asChild>
                        <span className="text-xs text-muted-foreground tabular-nums cursor-default">
                          {formatRelativeTime(trace.startNs)}
                        </span>
                      </TooltipTrigger>
                      <TooltipContent side="top">
                        <span className="text-xs">
                          {formatAbsoluteTime(trace.startNs)}
                        </span>
                      </TooltipContent>
                    </Tooltip>

                    {/* Status */}
                    <div>
                      {trace.hasError ? (
                        <Badge
                          variant="destructive"
                          className="gap-1 text-[11px] h-5"
                        >
                          <AlertTriangle className="h-3 w-3" />
                          Error
                        </Badge>
                      ) : (
                        <Badge
                          variant="outline"
                          className="text-[11px] h-5 text-success-fg border-success-border"
                        >
                          OK
                        </Badge>
                      )}
                    </div>
                  </Link>
                )
              })}
            </div>
          </div>
        )}
      </div>
    </TooltipProvider>
  )
}
