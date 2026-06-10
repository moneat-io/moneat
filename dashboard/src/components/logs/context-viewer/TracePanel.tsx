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

import type {ApmSpanResponse, LogEntry} from '@/lib/api'
import {cn} from '@/lib/utils'
import {ExternalLink, GitBranch, List, Loader2} from 'lucide-react'
import {useMemo} from 'react'
import {computeLogPinPct, computeTraceLayout, formatTraceDuration} from './logContextHelpers'
import {Hint} from './ViewerPrimitives'

const OP_COL = 240 // px width of the operation column; the pin math depends on it

interface TracePanelProps {
  log: LogEntry
  spans: ApmSpanResponse[]
  isLoading: boolean
  isError: boolean
  traceHref?: string
  onViewAllTraceLogs?: () => void
}

const TYPE_COLOR: Record<string, string> = {
  server: 'bg-blue-500',
  client: 'bg-teal-500',
  db: 'bg-amber-500',
  cache: 'bg-orange-500',
  producer: 'bg-violet-500',
  consumer: 'bg-violet-500',
  internal: 'bg-muted-foreground/50',
}
const LEGEND: {label: string; cls: string}[] = [
  {label: 'server', cls: 'bg-blue-500'},
  {label: 'client', cls: 'bg-teal-500'},
  {label: 'db', cls: 'bg-amber-500'},
  {label: 'cache', cls: 'bg-orange-500'},
  {label: 'error', cls: 'bg-red-500'},
]

function typeColor(type: string): string {
  return TYPE_COLOR[(type || '').toLowerCase()] || 'bg-muted-foreground/50'
}

function EmptyTrace({children}: Readonly<{children: React.ReactNode}>) {
  return (
    <div className="p-3.5">
      <div className="rounded-md border border-border bg-card py-10 text-center text-xs text-muted-foreground">
        {children}
      </div>
    </div>
  )
}

export function TracePanel({
  log,
  spans,
  isLoading,
  isError,
  traceHref,
  onViewAllTraceLogs,
}: Readonly<TracePanelProps>) {
  const layout = useMemo(() => computeTraceLayout(spans), [spans])
  const pinPct = useMemo(
    () => (spans.length > 0 ? computeLogPinPct(layout, new Date(log.timestamp).getTime()) : null),
    [layout, spans.length, log.timestamp]
  )

  if (!log.traceId) return <EmptyTrace>This log is not part of a trace.</EmptyTrace>
  if (isLoading) {
    return (
      <div className="flex items-center justify-center gap-2 py-12 text-xs text-muted-foreground">
        <Loader2 className="h-3.5 w-3.5 animate-spin" /> Loading trace…
      </div>
    )
  }
  if (isError) return <EmptyTrace>Trace details are unavailable.</EmptyTrace>
  if (spans.length === 0) return <EmptyTrace>No spans found for this trace.</EmptyTrace>

  const stats = [
    {k: 'Duration', v: formatTraceDuration(layout.traceDurationNs), bad: false},
    {k: 'Spans', v: String(spans.length), bad: false},
    {k: 'Services', v: String(layout.serviceCount), bad: false},
    {k: 'Errors', v: String(layout.errorCount), bad: layout.errorCount > 0},
  ]

  return (
    <div className="p-3.5">
      <div className="mb-3 grid grid-cols-4 gap-px overflow-hidden rounded-md border border-border bg-border">
        {stats.map((s) => (
          <div key={s.k} className="bg-card px-2.5 py-2">
            <div className="mb-0.5 text-[10px] uppercase tracking-wide text-muted-foreground">{s.k}</div>
            <div className={cn('font-mono text-sm font-semibold', s.bad ? 'text-red-600 dark:text-red-400' : 'text-foreground')}>
              {s.v}
            </div>
          </div>
        ))}
      </div>

      <div className="flex flex-wrap items-center gap-3 rounded-t-md border border-b-0 border-border bg-muted/40 px-2.5 py-1.5">
        <span className="text-[10px] font-semibold uppercase tracking-wide text-muted-foreground">Types</span>
        {LEGEND.map((l) => (
          <span key={l.label} className="inline-flex items-center gap-1.5 text-[11px] text-muted-foreground">
            <span className={cn('h-2 w-2 rounded-[2px]', l.cls)} /> {l.label}
          </span>
        ))}
      </div>

      <div className="relative overflow-hidden rounded-b-md border border-border">
        <div
          className="grid bg-muted/40 px-2.5 py-1.5 text-[10px] font-semibold uppercase tracking-wide text-muted-foreground"
          style={{gridTemplateColumns: `${OP_COL}px 1fr`}}
        >
          <span>Operation</span>
          <span>Timeline ({formatTraceDuration(layout.traceDurationNs)})</span>
        </div>

        <div className="relative">
          {pinPct != null && (
            <div
              className="pointer-events-none absolute inset-y-0 z-10 border-l-2 border-dashed border-primary"
              style={{left: `calc(${OP_COL}px + (100% - ${OP_COL}px) * ${pinPct / 100})`}}
            >
              <span className="absolute left-1 top-0.5 inline-flex items-center gap-1 whitespace-nowrap rounded-sm bg-primary px-1.5 py-0.5 text-[9px] font-bold text-primary-foreground shadow-sm">
                <List className="h-2.5 w-2.5" /> log emitted
              </span>
            </div>
          )}

          {layout.rows.map((row) => {
            const highlighted = !!log.spanId && row.span.spanId === log.spanId
            const labelAtEnd = row.offsetPct + row.widthPct > 82
            return (
              <div
                key={row.span.spanId}
                className={cn(
                  'relative grid h-[30px] items-center border-b border-border/50 last:border-b-0',
                  highlighted ? 'bg-primary/[0.07]' : 'hover:bg-accent/40'
                )}
                style={{gridTemplateColumns: `${OP_COL}px 1fr`}}
              >
                {highlighted && <span className="absolute inset-y-0 left-0 z-[1] w-0.5 bg-primary" />}
                <div className="flex min-w-0 items-center gap-1.5 px-2.5 text-xs" style={{paddingLeft: 10 + row.depth * 14}}>
                  <span className={cn('h-2 w-2 shrink-0 rounded-[2px]', row.hasError ? 'bg-red-500' : typeColor(row.span.type))} />
                  <span className="shrink-0 font-semibold text-foreground">{row.span.service}</span>
                  <span className="truncate text-muted-foreground">{row.span.resource || row.span.name}</span>
                  {row.hasError && (
                    <span className="grid h-[15px] shrink-0 place-items-center rounded-sm border border-red-500/30 bg-red-500/10 px-1 text-[9px] font-bold text-red-600 dark:text-red-400">
                      ERR
                    </span>
                  )}
                </div>
                <div className="relative mr-2.5 h-[30px]">
                  <div
                    className={cn('absolute top-[9px] h-3 rounded-[2px] opacity-85', row.hasError ? 'bg-red-500' : typeColor(row.span.type))}
                    style={{left: `${row.offsetPct}%`, width: `${row.widthPct}%`}}
                  />
                  <span
                    className={cn('absolute top-2 whitespace-nowrap font-mono text-[10px] text-muted-foreground', labelAtEnd && 'right-0')}
                    style={labelAtEnd ? undefined : {left: `calc(${row.offsetPct + row.widthPct}% + 4px)`}}
                  >
                    {formatTraceDuration(row.span.durationNs)}
                  </span>
                </div>
              </div>
            )
          })}
        </div>
      </div>

      <Hint icon={<List className="h-3 w-3" />}>
        {log.spanId
          ? 'The highlighted span emitted this log. The dashed marker shows when, on the trace timeline.'
          : 'The dashed marker shows when this log was emitted, on the trace timeline.'}
      </Hint>

      <div className="mt-3 flex flex-wrap items-center gap-2">
        {traceHref && (
          <a
            href={traceHref}
            className="inline-flex h-[30px] items-center gap-2 rounded-md bg-primary px-3 text-xs font-semibold text-primary-foreground hover:bg-primary/90"
          >
            <GitBranch className="h-3.5 w-3.5" /> View full trace
          </a>
        )}
        {onViewAllTraceLogs && (
          <button
            type="button"
            onClick={onViewAllTraceLogs}
            className="inline-flex h-[30px] items-center gap-2 rounded-md border border-border bg-card px-3 text-xs font-semibold text-foreground hover:bg-accent"
          >
            <List className="h-3.5 w-3.5" /> All logs in this trace
          </button>
        )}
        {traceHref && log.spanId && (
          <a
            href={`${traceHref}?span=${log.spanId}`}
            className="inline-flex h-[30px] items-center gap-2 rounded-md border border-border bg-card px-3 text-xs font-semibold text-foreground hover:bg-accent"
          >
            <ExternalLink className="h-3.5 w-3.5" /> Open span
          </a>
        )}
      </div>
    </div>
  )
}
