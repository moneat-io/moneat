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

import {Server} from 'lucide-react'
import {cn} from '@/lib/utils'
import type {SyntheticLocationResponse} from '@/lib/api'
import {PHASE_COLORS, PHASE_ORDER, locationMeta} from '@/components/synthetics/syntheticsHelpers'

function uptimeStatusClass(status: string): string {
  if (status === 'failed') return 'bg-danger-solid'
  if (status === 'degraded') return 'bg-warning-solid'
  return 'bg-success-solid/90'
}

/** Overlapping circular location markers, like the overview/detail pins. */
export function LocationPins({
  codes,
  locations,
  max = 3,
}: Readonly<{codes: readonly string[]; locations?: readonly SyntheticLocationResponse[]; max?: number}>) {
  const shown = codes.slice(0, max)
  const extra = codes.length - shown.length
  return (
    <div className="flex items-center">
      {shown.map((code, i) => {
        const m = locationMeta(code, locations)
        return (
          <span
            key={code}
            title={m.name}
            className={cn(
              'grid h-4 w-4 place-items-center rounded-full text-[8px] font-bold text-white ring-1 ring-background',
              i > 0 && '-ml-1.5'
            )}
            style={{backgroundColor: m.color}}
          >
            {m.isPrivate ? <Server className="h-2 w-2" /> : m.abbr}
          </span>
        )
      })}
      {extra > 0 && <span className="ml-1 text-[10px] text-muted-foreground">+{extra}</span>}
    </div>
  )
}

/** A thin pass/fail availability strip (most-recent-last). */
export function UptimeStrip({
  statuses,
  className,
}: Readonly<{statuses: readonly string[]; className?: string}>) {
  if (statuses.length === 0) {
    return <div className="text-[11px] text-muted-foreground">No data</div>
  }
  return (
    <div className={cn('flex h-4 items-stretch gap-px', className)}>
      {statuses.map((s, i) => (
        <span
          key={`${s}-${i}`}
          className={cn(
            'min-w-[2px] flex-1 rounded-[1px]',
            uptimeStatusClass(s)
          )}
        />
      ))}
    </div>
  )
}

/** Stacked request-timing waterfall from a timings map (phase → ms). */
export function TimingWaterfall({timings}: Readonly<{timings?: Record<string, number>}>) {
  if (!timings) return null
  const phases = PHASE_ORDER.filter((p) => timings[p] != null && timings[p] >= 0)
  if (phases.length === 0) return <div className="text-xs text-muted-foreground">No timing data</div>
  const sum = phases.reduce((acc, p) => acc + (timings[p] ?? 0), 0) || 1
  const widths = phases.map((p) => ((timings[p] ?? 0) / sum) * 100)
  const lefts = widths.map((_, i) => widths.slice(0, i).reduce((a, b) => a + b, 0))
  return (
    <div className="flex flex-col gap-1.5">
      {phases.map((p, i) => (
        <div key={p} className="grid grid-cols-[64px_1fr_44px] items-center gap-2.5">
          <span className="text-xs capitalize text-muted-foreground">{p}</span>
          <span className="relative h-3 rounded-[3px] bg-muted">
            <span
              className="absolute top-0 h-full rounded-[3px]"
              style={{left: `${lefts[i]}%`, width: `${Math.max(widths[i], 1)}%`, backgroundColor: PHASE_COLORS[p] ?? '#6b7280'}}
            />
          </span>
          <span className="text-right font-mono text-xs tabular-nums">{Math.round(timings[p] ?? 0)}</span>
        </div>
      ))}
    </div>
  )
}
