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

import {Card, CardContent, CardHeader, CardTitle} from '@/components/ui/card'
import {Bar, BarChart as RechartsBarChart, CartesianGrid, Cell, ResponsiveContainer, Tooltip, XAxis, YAxis,} from 'recharts'

const BAR_COLORS = [
  'hsl(var(--chart-1))',
  'hsl(var(--chart-2))',
  'hsl(var(--chart-4))',
  'hsl(var(--chart-3))',
  'hsl(var(--chart-5))',
  'hsl(210 70% 55%)',
  'hsl(150 60% 45%)',
  'hsl(350 70% 55%)',
]

interface BarChartProps {
  readonly data: Record<string, number>
  readonly title: string
  readonly height?: number
  readonly color?: string
  readonly colors?: string[]
  readonly layout?: 'vertical' | 'horizontal'
}

export function BarChart({
  data,
  title,
  height = 300,
  color,
  colors,
  layout = 'horizontal',
}: BarChartProps) {
  const chartData = Object.entries(data)
    .map(([name, value]) => ({
      name,
      value,
    }))
    .sort((a, b) => b.value - a.value)

  const palette = colors ?? BAR_COLORS

  return (
    <Card className="h-full">
      <CardHeader className="px-4 py-3">
        <CardTitle className="text-sm">{title}</CardTitle>
      </CardHeader>
      <CardContent className="px-4 pb-3 pt-0">
        <ResponsiveContainer width="100%" height={height}>
          <RechartsBarChart
            data={chartData}
            layout={layout}
            margin={{ top: 5, right: 30, left: 20, bottom: 5 }}
          >
            <CartesianGrid strokeDasharray="3 3" className="stroke-muted" />
            {layout === 'horizontal' ? (
              <>
                <XAxis
                  dataKey="name"
                  fontSize={12}
                  tickLine={false}
                  axisLine={false}
                  className="fill-muted-foreground"
                />
                <YAxis
                  fontSize={12}
                  tickLine={false}
                  axisLine={false}
                  className="fill-muted-foreground"
                />
              </>
            ) : (
              <>
                <XAxis
                  type="number"
                  fontSize={12}
                  tickLine={false}
                  axisLine={false}
                  className="fill-muted-foreground"
                />
                <YAxis
                  type="category"
                  dataKey="name"
                  fontSize={12}
                  tickLine={false}
                  axisLine={false}
                  className="fill-muted-foreground"
                  width={100}
                />
              </>
            )}
            <Tooltip
              cursor={{ fill: 'hsl(var(--muted) / 0.5)' }}
              contentStyle={{
                backgroundColor: 'hsl(var(--popover) / 0.95)',
                border: '1px solid hsl(var(--border))',
                borderRadius: '6px',
                color: 'hsl(var(--popover-foreground))',
                padding: '8px 12px',
                fontSize: '13px',
              }}
              labelStyle={{
                color: 'hsl(var(--popover-foreground))',
                fontWeight: '500',
                fontSize: '13px',
              }}
              itemStyle={{
                color: 'hsl(var(--popover-foreground))',
                fontSize: '13px',
              }}
            />
            <Bar 
              dataKey="value" 
              radius={4}
              fill={color}
            >
              {!color && chartData.map((entry, index) => (
                <Cell key={`cell-${entry.name}`} fill={palette[index % palette.length]} />
              ))}
            </Bar>
          </RechartsBarChart>
        </ResponsiveContainer>
      </CardContent>
    </Card>
  )
}
