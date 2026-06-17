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

import type {LogAggregateBucket, LogMonitorCondition} from '@/lib/api'
import {Loader2} from 'lucide-react'
import {
  Select,
  SelectContent,
  SelectItem,
  SelectTrigger,
  SelectValue,
} from '@/components/ui/select'
import {cn} from '@/lib/utils'
import {logLevelBadgeClass} from '@/lib/severity'
import {
  GROUP_BY_OPTIONS,
  KNOWN_LEVELS,
  LOG_MONITOR_CONDITIONS,
} from '@/components/logs/logManagementShared'

/** Read-only chips echoing the active level filter, collapsing to "All levels"
 * when nothing (or everything) is selected. */
export function LevelChips({levels}: {readonly levels: string[]}) {
  const active = Array.from(new Set(levels.map((level) => level.trim()).filter((level) => level !== '')))
  const hasAllKnownLevels = KNOWN_LEVELS.every((knownLevel) => active.includes(knownLevel))
  if (active.length === 0 || hasAllKnownLevels) {
    return <span className="text-[11px] text-muted-foreground">All levels</span>
  }
  return (
    <div className="flex flex-wrap items-center gap-1">
      {active.map((level) => (
        <span
          key={level}
          className={cn(
            'rounded border px-1.5 py-px font-mono text-[10px] uppercase leading-4',
            logLevelBadgeClass(level)
          )}
        >
          {level}
        </span>
      ))}
    </div>
  )
}

/** Editable level filter: toggle chips per known level. An empty selection
 * means "all levels" (no filter), matching {@link LevelChips}. */
export function LevelSelect({
  levels,
  onChange,
}: {
  readonly levels: string[]
  readonly onChange: (next: string[]) => void
}) {
  const active = new Set(levels.map((level) => level.trim()).filter(Boolean))
  const toggle = (level: string) => {
    const next = new Set(active)
    if (next.has(level)) {
      next.delete(level)
    } else {
      next.add(level)
    }
    // Keep canonical order so payloads/keys are stable regardless of click order.
    onChange(KNOWN_LEVELS.filter((known) => next.has(known)))
  }
  return (
    <div className="flex flex-wrap items-center gap-1">
      {KNOWN_LEVELS.map((level) => {
        const on = active.has(level)
        return (
          <button
            key={level}
            type="button"
            aria-pressed={on}
            onClick={() => toggle(level)}
            className={cn(
              'rounded border px-1.5 py-0.5 font-mono text-[10px] uppercase leading-4 transition-colors',
              on
                ? logLevelBadgeClass(level)
                : 'border-border text-muted-foreground/70 hover:border-foreground/30 hover:text-foreground'
            )}
          >
            {level}
          </button>
        )
      })}
      <span className="ml-0.5 text-[10px] text-muted-foreground">
        {active.size === 0 ? 'all levels' : `${active.size} selected`}
      </span>
    </div>
  )
}

export function GroupBySelect({
  value,
  onChange,
}: {
  readonly value: string
  readonly onChange: (next: string) => void
}) {
  return (
    <Select value={value} onValueChange={onChange}>
      <SelectTrigger className="h-9">
        <SelectValue />
      </SelectTrigger>
      <SelectContent>
        {GROUP_BY_OPTIONS.map((option) => (
          <SelectItem key={option.value} value={option.value}>
            {option.label}
          </SelectItem>
        ))}
      </SelectContent>
    </Select>
  )
}

export function ConditionSelect({
  value,
  onChange,
}: {
  readonly value: LogMonitorCondition
  readonly onChange: (next: LogMonitorCondition) => void
}) {
  return (
    <Select value={value} onValueChange={(next) => onChange(next as LogMonitorCondition)}>
      <SelectTrigger className="h-9">
        <SelectValue />
      </SelectTrigger>
      <SelectContent>
        {LOG_MONITOR_CONDITIONS.map((option) => (
          <SelectItem key={option} value={option}>
            {option}
          </SelectItem>
        ))}
      </SelectContent>
    </Select>
  )
}

/** Compact volume sparkbars over a set of aggregate buckets, with the total.
 * Shared by the metric / monitor create surfaces to preview real match volume. */
export function LogVolumeBars({
  buckets,
  limit = 8,
  isFetching = false,
  emptyLabel = 'No matching logs in this window.',
}: {
  readonly buckets: LogAggregateBucket[]
  readonly limit?: number
  readonly isFetching?: boolean
  readonly emptyLabel?: string
}) {
  if (isFetching && buckets.length === 0) {
    return (
      <span className="flex items-center gap-1.5 text-[11px] text-muted-foreground">
        <Loader2 className="h-3 w-3 animate-spin" /> Estimating volume…
      </span>
    )
  }
  const shown = buckets.slice(-limit)
  if (shown.length === 0) {
    return <p className="text-[11px] text-muted-foreground">{emptyLabel}</p>
  }
  const max = shown.reduce((largest, bucket) => Math.max(largest, bucket.count), 0)
  return (
    <div className="space-y-1">
      {shown.map((bucket) => (
        <div key={bucket.timestamp} className="flex items-center gap-2">
          <span className="w-[132px] shrink-0 truncate font-mono text-[10px] text-muted-foreground">
            {bucket.timestamp}
          </span>
          <span className="h-1.5 flex-1 overflow-hidden rounded-full bg-muted">
            <span
              className="block h-full rounded-full bg-primary/70"
              style={{width: `${max > 0 ? (bucket.count / max) * 100 : 0}%`}}
            />
          </span>
          <span className="w-10 shrink-0 text-right font-mono text-[10px] tabular-nums text-foreground">
            {bucket.count}
          </span>
        </div>
      ))}
    </div>
  )
}
