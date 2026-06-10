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

import {api, type LogEntry, type LogPatternBreakdown} from '@/lib/api'
import {stripAnsi} from '@/lib/ansi'
import {cn} from '@/lib/utils'
import {useQuery} from '@tanstack/react-query'
import {Activity, Bell, List, Loader2, Zap} from 'lucide-react'
import {useMemo} from 'react'
import {derivePatternString, splitPatternSegments} from './logContextHelpers'
import {levelText, normalizeLevel} from './logLevelStyles'
import {Hint, SecLabel} from './ViewerPrimitives'

interface PatternsPanelProps {
  log: LogEntry
  from?: string
  to?: string
  onViewMatching?: (pattern: string) => void
  onCreateMetric?: () => void
  onCreateMonitor?: () => void
}

function formatCount(n: number): string {
  return n.toLocaleString('en-US')
}

function relativeFromIso(iso: string): string {
  if (!iso) return '—'
  const t = new Date(iso).getTime()
  if (Number.isNaN(t)) return '—'
  const diff = Date.now() - t
  if (diff < 60_000) return 'just now'
  if (diff < 3_600_000) return `${Math.floor(diff / 60_000)}m ago`
  if (diff < 86_400_000) return `${Math.floor(diff / 3_600_000)}h ago`
  return `${Math.floor(diff / 86_400_000)}d ago`
}

function Sparkline({values}: {values: number[]}) {
  if (values.length < 2) return null
  const w = 150
  const h = 40
  const max = Math.max(1, ...values)
  const pts = values.map((v, i) => {
    const x = (i / (values.length - 1)) * w
    const y = h - 4 - (v / max) * (h - 8)
    return `${x.toFixed(1)},${y.toFixed(1)}`
  })
  return (
    <svg width={w} height={h} viewBox={`0 0 ${w} ${h}`} fill="none" className="mt-2">
      <polyline points={`0,${h} ${pts.join(' ')} ${w},${h}`} className="fill-red-500/10" stroke="none" />
      <polyline points={pts.join(' ')} className="stroke-red-500" strokeWidth={2} strokeLinecap="round" strokeLinejoin="round" />
    </svg>
  )
}

function Breakdown({title, rows}: {title: string; rows: LogPatternBreakdown[]}) {
  if (rows.length === 0) return null
  const max = Math.max(1, ...rows.map((r) => r.count))
  const fills = ['bg-primary', 'bg-teal-500', 'bg-orange-500', 'bg-violet-500', 'bg-amber-500']
  return (
    <div className="mb-3.5">
      <SecLabel>{title}</SecLabel>
      {rows.map((r, i) => (
        <div key={r.value} className="grid h-6 grid-cols-[140px_1fr_52px] items-center gap-2.5 text-xs">
          <span className="truncate font-mono text-foreground">{r.value}</span>
          <span className="h-[7px] overflow-hidden rounded-full bg-muted">
            <span className={cn('block h-full rounded-full', fills[i % fills.length])} style={{width: `${(r.count / max) * 100}%`}} />
          </span>
          <span className="text-right font-mono text-muted-foreground">{formatCount(r.count)}</span>
        </div>
      ))}
    </div>
  )
}

export function PatternsPanel({log, from, to, onViewMatching, onCreateMetric, onCreateMonitor}: PatternsPanelProps) {
  const message = stripAnsi(log.message || log.body || '')

  const {data, isLoading, isError} = useQuery({
    queryKey: ['log-pattern', log.logId, message, log.service || null, from ?? null, to ?? null],
    queryFn: () => api.getLogPattern({logId: log.logId, message, service: log.service || undefined, from, to}),
    enabled: !!message,
    retry: false,
    staleTime: 60_000,
  })

  const patternString = data?.pattern || derivePatternString(message)
  const segments = useMemo(() => splitPatternSegments(patternString), [patternString])
  const level = normalizeLevel(data?.level || log.level)
  const analyticsMissing = !isLoading && (isError || !data)

  return (
    <div className="p-3.5">
      <div className="mb-3.5 rounded-md border border-border bg-card p-3.5">
        <div className="flex items-start gap-3.5">
          <div className="min-w-0 flex-1">
            <div className="mb-1.5 text-[10px] font-semibold uppercase tracking-wider text-muted-foreground">
              Matched pattern
            </div>
            <div className="break-words font-mono text-sm leading-snug text-foreground">
              {segments.map((s, i) =>
                s.wildcard ? (
                  <span
                    key={i}
                    className="mx-px rounded-sm border border-primary/40 bg-primary/10 px-1 text-primary"
                  >
                    {s.text}
                  </span>
                ) : (
                  <span key={i}>{s.text}</span>
                )
              )}
            </div>
            <div className="mt-2 flex flex-wrap gap-x-3.5 gap-y-1 text-xs text-muted-foreground">
              <span>
                severity <b className={cn('font-semibold uppercase', levelText(level))}>{level}</b>
              </span>
              {data && (
                <>
                  <span>
                    first seen <b className="font-semibold text-foreground">{relativeFromIso(data.firstSeen)}</b>
                  </span>
                  <span>
                    last seen <b className="font-semibold text-foreground">{relativeFromIso(data.lastSeen)}</b>
                  </span>
                  {data.trendPct != null && (
                    <span>
                      trend{' '}
                      <b className={cn('font-semibold', data.trendPct >= 0 ? 'text-red-600 dark:text-red-400' : 'text-emerald-600 dark:text-emerald-400')}>
                        {data.trendPct >= 0 ? '▲' : '▼'} {Math.abs(data.trendPct)}% vs {data.windowLabel}
                      </b>
                    </span>
                  )}
                </>
              )}
            </div>
          </div>
          <div className="shrink-0 text-right">
            <div className="font-mono text-2xl font-bold leading-none text-foreground">
              {isLoading ? (
                <Loader2 className="ml-auto h-5 w-5 animate-spin text-muted-foreground" />
              ) : data ? (
                <>
                  {formatCount(data.count)}
                  <span className="ml-1 text-xs font-medium text-muted-foreground">/ {data.windowLabel}</span>
                </>
              ) : (
                <span className="text-base text-muted-foreground">—</span>
              )}
            </div>
            {data && <Sparkline values={data.sparkline} />}
          </div>
        </div>
      </div>

      {data && <Breakdown title="Top services" rows={data.topServices} />}
      {data && <Breakdown title="Top hosts" rows={data.topHosts} />}

      {analyticsMissing && (
        <div className="mb-3.5 rounded-md border border-dashed border-border bg-muted/30 px-3 py-2.5 text-[11px] text-muted-foreground">
          Pattern rollups (match volume, trend, and top sources) appear once the pattern index is available.
        </div>
      )}

      <div className="flex flex-wrap gap-2">
        <button
          type="button"
          onClick={() => {
            if (data?.pattern) onViewMatching?.(data.pattern)
          }}
          disabled={!onViewMatching || !data?.pattern}
          className="inline-flex h-[30px] items-center gap-2 rounded-md bg-primary px-3 text-xs font-semibold text-primary-foreground hover:bg-primary/90 disabled:opacity-50"
        >
          <List className="h-3.5 w-3.5" /> {data ? `View ${formatCount(data.count)} matching logs` : 'View matching logs'}
        </button>
        <button
          type="button"
          onClick={onCreateMetric}
          disabled={!onCreateMetric}
          className="inline-flex h-[30px] items-center gap-2 rounded-md border border-border bg-card px-3 text-xs font-semibold text-foreground hover:bg-accent disabled:opacity-50"
        >
          <Activity className="h-3.5 w-3.5" /> Create log metric
        </button>
        <button
          type="button"
          onClick={onCreateMonitor}
          disabled={!onCreateMonitor}
          className="inline-flex h-[30px] items-center gap-2 rounded-md border border-border bg-card px-3 text-xs font-semibold text-foreground hover:bg-accent disabled:opacity-50"
        >
          <Bell className="h-3.5 w-3.5" /> Create monitor
        </button>
      </div>

      <Hint icon={<Zap className="h-3 w-3" />}>
        Patterns cluster structurally identical logs so one spike doesn’t read as thousands of separate problems.
      </Hint>
    </div>
  )
}
