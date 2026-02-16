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

import {Cell, Legend, Pie, PieChart, ResponsiveContainer, Tooltip, type TooltipProps} from 'recharts'
import type {LogTopValue} from '@/lib/api'

interface LogPieChartProps {
  values: LogTopValue[]
  field: string
  height?: number
}

const COLORS = [
  'hsl(var(--chart-1))',
  'hsl(var(--chart-2))',
  'hsl(var(--chart-3))',
  'hsl(var(--chart-4))',
  'hsl(var(--chart-5))',
  '#6366f1',
  '#14b8a6',
  '#f59e0b',
  '#ef4444',
  '#8b5cf6',
]

function CustomTooltip({active, payload}: TooltipProps<number, string>) {
  if (!active || !payload || !payload.length) return null
  
  const data = payload[0]
  return (
    <div
      style={{
        backgroundColor: 'hsl(var(--popover) / 0.95)',
        border: '1px solid hsl(var(--border))',
        borderRadius: '6px',
        color: 'hsl(var(--popover-foreground))',
        padding: '6px 10px',
        fontSize: '11px',
      }}
    >
      <div>{data.name}: {data.value}</div>
    </div>
  )
}

export function LogPieChart({values, field, height = 240}: LogPieChartProps) {
  const chartData = values.slice(0, 8).map((v) => ({name: v.value, value: v.count}))

  if (chartData.length === 0) {
    return (
      <div className="flex items-center justify-center py-12 text-sm text-muted-foreground">
        No data for field "{field}"
      </div>
    )
  }

  return (
    <div className="px-2 py-2">
      <div className="mb-1 px-1 text-[10px] font-medium text-muted-foreground uppercase tracking-wider">
        Distribution by {field}
      </div>
      <ResponsiveContainer width="100%" height={height}>
        <PieChart>
          <Pie
            data={chartData}
            cx="50%"
            cy="50%"
            innerRadius={50}
            outerRadius={80}
            fill="#8884d8"
            paddingAngle={2}
            dataKey="value"
            label={(entry) => {
              const name = entry.name as string
              const percent = entry.percent as number
              return `${name} (${(percent * 100).toFixed(0)}%)`
            }}
          >
            {chartData.map((_, index) => (
              <Cell key={`cell-${index}`} fill={COLORS[index % COLORS.length]} />
            ))}
          </Pie>
          <Tooltip content={<CustomTooltip />} />
          <Legend wrapperStyle={{fontSize: '10px'}} />
        </PieChart>
      </ResponsiveContainer>
    </div>
  )
}
