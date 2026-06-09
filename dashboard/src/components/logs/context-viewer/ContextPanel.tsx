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

import {api, type LogEntry} from '@/lib/api'
import {stripAnsi} from '@/lib/ansi'
import {cn} from '@/lib/utils'
import {useTimezone} from '@/hooks/useTimezone'
import {formatTimeWithMs} from '@/lib/date-format'
import {useQuery} from '@tanstack/react-query'
import {ChevronDown, ChevronUp, Loader2, MoveRight} from 'lucide-react'
import {useEffect, useMemo, useRef, useState} from 'react'
import {buildContextVolume, formatDeltaMs} from './logContextHelpers'
import {levelDot, normalizeLevel} from './logLevelStyles'
import {Hint} from './ViewerPrimitives'

type Scope = 'host' | 'service' | 'trace' | 'all'
const WINDOW_STEP = 15 // seconds added per "load more"

interface ContextPanelProps {
  log: LogEntry
}

const scopeLabel: Record<Scope, string> = {
  host: 'this host',
  service: 'service',
  trace: 'this trace',
  all: 'all logs',
}

export function ContextPanel({log}: ContextPanelProps) {
  const {timezone} = useTimezone()
  const anchorMs = useMemo(() => new Date(log.timestamp).getTime(), [log.timestamp])
  const hasValidAnchor = Number.isFinite(anchorMs)

  const [scope, setScope] = useState<Scope>(log.host ? 'host' : 'all')
  const effectiveScope: Scope =
    scope === 'host' && !log.host
      ? 'all'
      : scope === 'service' && !log.service
        ? 'all'
        : scope === 'trace' && !log.traceId
          ? 'all'
          : scope

  const [beforeSec, setBeforeSec] = useState(WINDOW_STEP)
  const [afterSec, setAfterSec] = useState(WINDOW_STEP)

  // Reset the window when the anchor log or scope changes. Done in render via
  // the "storing information from previous renders" pattern rather than an
  // effect, so the new window is used on the very next render.
  const windowKey = `${log.logId}:${effectiveScope}`
  const [prevWindowKey, setPrevWindowKey] = useState(windowKey)
  if (windowKey !== prevWindowKey) {
    setPrevWindowKey(windowKey)
    setBeforeSec(WINDOW_STEP)
    setAfterSec(WINDOW_STEP)
  }

  const from = hasValidAnchor ? new Date(anchorMs - beforeSec * 1000).toISOString() : undefined
  const to = hasValidAnchor ? new Date(anchorMs + afterSec * 1000).toISOString() : undefined

  const {data, isLoading, isError} = useQuery({
    queryKey: ['log-context', log.logId, effectiveScope, from, to],
    queryFn: () => {
      if (!from || !to) throw new Error('Cannot load log context without a valid timestamp')
      return api.getLogs({
        from,
        to,
        limit: 200,
        service: effectiveScope === 'service' ? log.service || undefined : undefined,
        host: effectiveScope === 'host' ? log.host || undefined : undefined,
        traceId: effectiveScope === 'trace' ? log.traceId || undefined : undefined,
      })
    },
    enabled: hasValidAnchor,
  })

  // Surrounding logs ascending by time, with the anchor guaranteed present.
  const rows = useMemo(() => {
    if (!hasValidAnchor) return [log]
    const seen = new Map<string, LogEntry>()
    for (const l of data?.logs ?? []) seen.set(l.logId, l)
    seen.set(log.logId, log)
    return [...seen.values()].sort((a, b) => new Date(a.timestamp).getTime() - new Date(b.timestamp).getTime())
  }, [data, hasValidAnchor, log])

  const volume = useMemo(
    () =>
      hasValidAnchor
        ? buildContextVolume(rows.map((r) => new Date(r.timestamp).getTime()), anchorMs)
        : {buckets: [], markerPct: 50},
    [rows, anchorMs, hasValidAnchor]
  )

  const anchorRef = useRef<HTMLDivElement>(null)
  useEffect(() => {
    if (!hasValidAnchor) return
    anchorRef.current?.scrollIntoView?.({block: 'center', behavior: 'auto'})
  }, [log.logId, isLoading, hasValidAnchor])

  if (!hasValidAnchor) {
    return (
      <div className="p-3.5 text-xs text-muted-foreground">
        This log has an invalid timestamp, so surrounding context cannot be loaded.
      </div>
    )
  }

  const maxVol = Math.max(1, ...volume.buckets.map((b) => b.count))

  return (
    <div className="p-3.5">
      <div className="mb-2.5 flex flex-wrap items-center gap-2.5">
        <div className="flex overflow-hidden rounded-md border border-border">
          {(['host', 'service', 'trace', 'all'] as const).map((s) => {
            const disabled = (s === 'host' && !log.host) || (s === 'service' && !log.service) || (s === 'trace' && !log.traceId)
            return (
              <button
                key={s}
                type="button"
                disabled={disabled}
                onClick={() => setScope(s)}
                className={cn(
                  'h-[26px] px-2.5 text-xs font-medium capitalize transition-colors',
                  s !== 'host' && 'border-l border-border',
                  disabled && 'cursor-not-allowed opacity-40',
                  effectiveScope === s
                    ? 'bg-primary/10 font-semibold text-primary'
                    : 'text-muted-foreground hover:bg-accent hover:text-foreground'
                )}
              >
                {s === 'host' ? 'This host' : s === 'trace' ? 'This trace' : s === 'all' ? 'All logs' : 'Service'}
              </button>
            )
          })}
        </div>
        <span className="ml-auto text-xs text-muted-foreground">
          {rows.length} logs · {beforeSec}s before / {afterSec}s after · {scopeLabel[effectiveScope]}
        </span>
      </div>

      {/* volume strip */}
      <div className="relative mb-2 flex h-[34px] items-end gap-0.5 px-0.5">
        {volume.buckets.map((b, i) => (
          <div
            key={i}
            className={cn('min-h-[3px] flex-1 rounded-[1px]', b.hot ? 'bg-red-500' : 'bg-muted-foreground/40')}
            style={{height: `${6 + (b.count / maxVol) * 26}px`}}
          />
        ))}
        <div
          className="pointer-events-none absolute -top-1 bottom-[-4px] w-0.5 rounded bg-primary"
          style={{left: `calc(${Math.min(100, Math.max(0, volume.markerPct))}% - 1px)`}}
        />
      </div>

      <div className="overflow-hidden rounded-md border border-border bg-card">
        <button
          type="button"
          onClick={() => setBeforeSec((s) => s + WINDOW_STEP)}
          className="flex h-[30px] w-full items-center justify-center gap-1.5 border-b border-border/60 bg-muted/40 text-xs text-muted-foreground hover:bg-accent hover:text-foreground"
        >
          <ChevronUp className="h-3 w-3" /> Load earlier
        </button>

        {isLoading ? (
          <div className="flex items-center justify-center gap-2 py-8 text-xs text-muted-foreground">
            <Loader2 className="h-3.5 w-3.5 animate-spin" /> Loading surrounding logs…
          </div>
        ) : isError ? (
          <div className="py-8 text-center text-xs text-muted-foreground">
            Surrounding logs are unavailable for this window.
          </div>
        ) : (
          rows.map((row) => {
            const isAnchor = row.logId === log.logId
            const delta = new Date(row.timestamp).getTime() - anchorMs
            const lvl = normalizeLevel(row.level)
            return (
              <div
                key={`${row.logId}:${row.timestamp}`}
                ref={isAnchor ? anchorRef : undefined}
                className={cn(
                  'relative grid grid-cols-[62px_92px_16px_1fr] items-start gap-2.5 border-b border-border/50 px-2.5 py-1.5 font-mono text-[11px] last:border-b-0',
                  isAnchor ? 'bg-primary/[0.07]' : 'hover:bg-accent/40'
                )}
              >
                {isAnchor && <span className="absolute inset-y-0 left-0 w-0.5 bg-primary" />}
                <span
                  className={cn(
                    'pt-px text-right text-[10px]',
                    isAnchor ? 'font-semibold text-primary' : 'text-muted-foreground/70'
                  )}
                >
                  {formatDeltaMs(delta)}
                </span>
                <span className="text-muted-foreground">{formatTimeWithMs(new Date(row.timestamp), timezone)}</span>
                <span className={cn('mt-[5px] h-[7px] w-[7px] rounded-[2px]', levelDot(lvl))} />
                <span
                  className={cn(
                    'break-words leading-snug',
                    isAnchor ? 'font-medium text-foreground' : 'text-foreground/80'
                  )}
                >
                  {stripAnsi(row.message || row.body) || '—'}
                  {isAnchor && (
                    <span className="ml-2 inline-block rounded-sm border border-primary/40 bg-primary/10 px-1.5 align-middle font-sans text-[9px] font-bold uppercase tracking-wide text-primary">
                      This event
                    </span>
                  )}
                </span>
              </div>
            )
          })
        )}

        <button
          type="button"
          onClick={() => setAfterSec((s) => s + WINDOW_STEP)}
          className="flex h-[30px] w-full items-center justify-center gap-1.5 border-t border-border/60 bg-muted/40 text-xs text-muted-foreground hover:bg-accent hover:text-foreground"
        >
          <ChevronDown className="h-3 w-3" /> Load later
        </button>
      </div>

      <Hint icon={<MoveRight className="h-3 w-3" />}>
        Pinned to the selected event. Switch scope to widen by host, service, or the full trace.
      </Hint>
    </div>
  )
}
