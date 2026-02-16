// Moneat - Mobile-First Error Monitoring Platform
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

import type {LogTopValue} from '@/lib/api'
import {cn} from '@/lib/utils'

interface LogTopListProps {
  values: LogTopValue[]
  totalCount: number
  field: string
  onValueClick?: (value: string) => void
}

function formatCount(n: number): string {
  if (n >= 1_000_000_000) return `${(n / 1_000_000_000).toFixed(1)}B`
  if (n >= 1_000_000) return `${(n / 1_000_000).toFixed(1)}M`
  if (n >= 1_000) return `${(n / 1_000).toFixed(1)}k`
  return String(n)
}

export function LogTopList({values, totalCount, field, onValueClick}: LogTopListProps) {
  const maxCount = values.length > 0 ? values[0].count : 1

  if (values.length === 0) {
    return (
      <div className="flex items-center justify-center py-12 text-sm text-muted-foreground">
        No data for field "{field}"
      </div>
    )
  }

  return (
    <div className="space-y-1 px-1 py-2">
      <div className="mb-2 px-2 text-[10px] font-medium text-muted-foreground uppercase tracking-wider">
        Top values for {field}
      </div>
      {values.map((item) => {
        const pct = totalCount > 0 ? (item.count / totalCount) * 100 : 0
        const barWidth = maxCount > 0 ? (item.count / maxCount) * 100 : 0
        return (
          <button
            key={item.value}
            type="button"
            onClick={() => onValueClick?.(item.value)}
            className={cn(
              'group relative flex w-full items-center gap-2 rounded px-2 py-1.5 text-left transition-colors hover:bg-accent/50'
            )}
          >
            <div
              className="absolute inset-y-0 left-0 rounded bg-primary/10"
              style={{width: `${barWidth}%`}}
            />
            <span className="relative z-10 flex-1 truncate font-mono text-xs">{item.value}</span>
            <span className="relative z-10 shrink-0 font-mono text-[10px] text-muted-foreground">
              {formatCount(item.count)}
            </span>
            <span className="relative z-10 shrink-0 w-10 text-right font-mono text-[10px] text-muted-foreground/70">
              {pct.toFixed(1)}%
            </span>
          </button>
        )
      })}
    </div>
  )
}
