// Moneat Enterprise - proprietary module
// Copyright (c) 2026 Moneat. All rights reserved.
// See enterprise/LICENSE for license terms.

import {Card, CardContent, CardHeader, CardTitle} from '@/components/ui/card'
import {Area, AreaChart, CartesianGrid, ResponsiveContainer, Tooltip, XAxis, YAxis, Legend} from 'recharts'
import type {AnalyticsTimeseriesPoint} from '@/lib/api'
import {cn} from '@/lib/utils'

const formatTime = (timestamp: string) =>
  new Date(timestamp).toLocaleString('en-US', {
    month: 'short',
    day: 'numeric',
  })

const formatTimeFull = (timestamp: string) =>
  new Date(timestamp).toLocaleString('en-US', {
    month: 'short',
    day: 'numeric',
    hour: '2-digit',
    minute: '2-digit',
  })

interface AnalyticsChartProps {
  data?: AnalyticsTimeseriesPoint[]
  isLoading: boolean
  height?: number
}

export function AnalyticsChart({data, isLoading, height = 350}: AnalyticsChartProps) {
  const safeData = Array.isArray(data) ? data : []
  const chartData = safeData.map((point) => ({
    timestamp: new Date(point.timestamp).getTime(),
    time: formatTime(point.timestamp),
    visitors: point.visitors,
    pageviews: point.pageviews,
  }))

  return (
    <Card className="border-t-4 border-t-blue-500/50">
      <CardHeader className="px-4 py-2">
        <CardTitle className="text-sm">Visitors & Pageviews</CardTitle>
      </CardHeader>
      <CardContent className="px-4 pt-0 pb-3">
        {isLoading ? (
          <div className="flex items-end justify-between gap-2" style={{height}}>
            {[...Array(24)].map((_, i) => (
              <div
                key={i}
                className="flex-1 bg-muted animate-pulse rounded-t"
                style={{
                  height: `${30 + Math.random() * 70}%`,
                  animationDelay: `${i * 50}ms`,
                }}
              />
            ))}
          </div>
        ) : (
          <ResponsiveContainer width="100%" height={height}>
            <AreaChart data={chartData}>
              <CartesianGrid strokeDasharray="3 3" className="stroke-muted" />
              <XAxis
                dataKey="timestamp"
                type="number"
                domain={['dataMin', 'dataMax']}
                tickFormatter={(ts) => formatTime(new Date(ts).toISOString())}
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
              <Tooltip
                labelFormatter={(ts) => formatTimeFull(new Date(ts).toISOString())}
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
              <Legend
                wrapperStyle={{fontSize: '12px'}}
              />
              <Area
                type="monotone"
                dataKey="visitors"
                name="Visitors"
                stroke="hsl(var(--chart-1))"
                fill="hsl(var(--chart-1))"
                strokeWidth={2}
                fillOpacity={0.15}
              />
              <Area
                type="monotone"
                dataKey="pageviews"
                name="Pageviews"
                stroke="hsl(var(--chart-2))"
                fill="hsl(var(--chart-2))"
                strokeWidth={2}
                fillOpacity={0.1}
              />
            </AreaChart>
          </ResponsiveContainer>
        )}
      </CardContent>
    </Card>
  )
}
