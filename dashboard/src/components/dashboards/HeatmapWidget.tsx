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

import {useMemo} from 'react'

interface HeatmapWidgetProps {
  data: Record<string, unknown>[]
}

const HEAT_COLORS = [
  'bg-emerald-100 dark:bg-emerald-950',
  'bg-emerald-200 dark:bg-emerald-900',
  'bg-emerald-300 dark:bg-emerald-800',
  'bg-emerald-400 dark:bg-emerald-700',
  'bg-emerald-500 dark:bg-emerald-600',
  'bg-emerald-600 dark:bg-emerald-500',
]

export function HeatmapWidget({data}: HeatmapWidgetProps) {
  const {grid, xLabels, yLabels} = useMemo(() => {
    if (data.length === 0) return {grid: [] as number[][], xLabels: [] as string[], yLabels: [] as string[]}

    const columns = Object.keys(data[0])
    const timeKey = columns.find((k) => k.includes('time') || k.includes('bucket')) || columns[0]
    const groupKey = columns.find(
      (k) => typeof data[0][k] === 'string' && k !== timeKey
    )
    const valueKey = columns.find((k) => typeof data[0][k] === 'number') || columns[columns.length - 1]

    if (!groupKey) {
      // Single dimension: just time + value
      const values = data.map((r) => Number(r[valueKey]) || 0)
      return {
        grid: [values],
        xLabels: data.map((r) => String(r[timeKey] || '')),
        yLabels: [''],
      }
    }

    const uniqueX = [...new Set(data.map((r) => String(r[timeKey] || '')))]
    const uniqueY = [...new Set(data.map((r) => String(r[groupKey] || '')))]

    const valueMap = new Map<string, number>()
    data.forEach((r) => {
      const key = `${r[timeKey]}_${r[groupKey]}`
      valueMap.set(key, Number(r[valueKey]) || 0)
    })

    const gridData = uniqueY.map((y) =>
      uniqueX.map((x) => valueMap.get(`${x}_${y}`) ?? 0)
    )

    return {grid: gridData, xLabels: uniqueX, yLabels: uniqueY}
  }, [data])

  if (grid.length === 0) {
    return <div className="h-full flex items-center justify-center text-xs text-muted-foreground">No data</div>
  }

  const allValues = grid.flat()
  const maxVal = Math.max(...allValues, 1)

  const getColorClass = (val: number) => {
    const idx = Math.floor((val / maxVal) * (HEAT_COLORS.length - 1))
    return HEAT_COLORS[Math.min(idx, HEAT_COLORS.length - 1)]
  }

  return (
    <div className="h-full overflow-auto p-1">
      <div className="flex flex-col gap-0.5">
        {grid.map((row, yi) => (
          <div key={yi} className="flex items-center gap-0.5">
            {yLabels[yi] && (
              <span className="text-[9px] text-muted-foreground w-12 truncate shrink-0">
                {yLabels[yi]}
              </span>
            )}
            {row.map((val, xi) => (
              <div
                key={xi}
                className={`flex-1 min-w-[8px] h-4 rounded-sm ${getColorClass(val)}`}
                title={`${xLabels[xi]}: ${val.toLocaleString()}`}
              />
            ))}
          </div>
        ))}
      </div>
    </div>
  )
}
