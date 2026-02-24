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

import {memo} from 'react'

interface TopListWidgetProps {
  data: Record<string, unknown>[]
}

export const TopListWidget = memo(function TopListWidget({data}: TopListWidgetProps) {
  if (data.length === 0) return null

  const columns = Object.keys(data[0])
  const labelKey = columns.find((k) => typeof data[0][k] === 'string') || columns[0]
  const valueKey = columns.find((k) => typeof data[0][k] === 'number') || columns[1]
  const maxValue = Math.max(...data.map((r) => Number(r[valueKey]) || 0), 1)

  return (
    <div className="h-full overflow-auto space-y-1.5 p-1">
      {data.slice(0, 20).map((row, i) => {
        const value = Number(row[valueKey]) || 0
        const pct = (value / maxValue) * 100

        return (
          <div key={i} className="relative">
            <div
              className="absolute inset-0 rounded bg-primary/10"
              style={{width: `${pct}%`}}
            />
            <div className="relative flex items-center justify-between px-2 py-1 text-xs">
              <span className="truncate font-medium">{String(row[labelKey] ?? '')}</span>
              <span className="tabular-nums text-muted-foreground ml-2 shrink-0">
                {value.toLocaleString()}
              </span>
            </div>
          </div>
        )
      })}
    </div>
  )
})
