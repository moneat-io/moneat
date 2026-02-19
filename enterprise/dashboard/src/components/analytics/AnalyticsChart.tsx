// Moneat Enterprise - proprietary module
// Copyright (c) 2026 Moneat. All rights reserved.
// See enterprise/LICENSE for license terms.

import {Card, CardContent, CardHeader, CardTitle} from '@/components/ui/card'
import {Area, AreaChart, CartesianGrid, ResponsiveContainer, Tooltip, XAxis, YAxis, Legend} from 'recharts'
import type {AnalyticsTimeseriesPoint} from '@/lib/api'

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

export function AnalyticsChart({data, isLoading, height = 220}: AnalyticsChartProps) {
  const safeData = Array.isArray(data) ? data : []
  const chartData = safeData.map((point) => ({
    timestamp: new Date(point.timestamp).getTime(),
    time: formatTime(point.timestamp),
    visitors: point.visitors,
    pageviews: point.pageviews,
  }))

  return (
    <Card>
      <CardHeader className="px-4 py-2">
        <CardTitle className="text-xs font-medium text-muted-foreground uppercase tracking-wider">Visitors & Pageviews</CardTitle>
      </CardHeader>
      <CardContent className="px-2 pt-0 pb-2">
        {isLoading ? (
          <div className="flex items-end justify-between gap-1 px-2" style={{height}}>
            {[...Array(20)].map((_, i) => (
              <div
                key={i}
                className="flex-1 bg-muted animate-pulse rounded-t"
                style={{
                  height: `${30 + Math.random() * 70}%`,
                  animationDelay: `${i * 40}ms`,
                }}
              />
            ))}
          </div>
        ) : (
          <ResponsiveContainer width="100%" height={height}>
            <AreaChart data={chartData} margin={{top: 4, right: 8, left: -12, bottom: 0}}>
              <CartesianGrid strokeDasharray="3 3" className="stroke-muted" vertical={false} />
              <XAxis
                dataKey="timestamp"
                type="number"
                domain={['dataMin', 'dataMax']}
                tickFormatter={(ts) => formatTime(new Date(ts).toISOString())}
                fontSize={11}
                tickLine={false}
                axisLine={false}
                className="fill-muted-foreground"
                tickMargin={4}
              />
              <YAxis
                fontSize={11}
                tickLine={false}
                axisLine={false}
                className="fill-muted-foreground"
                width={48}
              />
              <Tooltip
                labelFormatter={(ts) => formatTimeFull(new Date(ts).toISOString())}
                contentStyle={{
                  backgroundColor: 'hsl(var(--popover) / 0.95)',
                  border: '1px solid hsl(var(--border))',
                  borderRadius: '6px',
                  color: 'hsl(var(--popover-foreground))',
                  padding: '6px 10px',
                  fontSize: '12px',
                }}
                labelStyle={{
                  color: 'hsl(var(--popover-foreground))',
                  fontWeight: '500',
                  fontSize: '12px',
                }}
                itemStyle={{
                  color: 'hsl(var(--popover-foreground))',
                  fontSize: '12px',
                }}
              />
              <Legend
                wrapperStyle={{fontSize: '11px', paddingTop: '4px'}}
                iconSize={8}
              />
              <Area
                type="monotone"
                dataKey="visitors"
                name="Visitors"
                stroke="hsl(var(--chart-1))"
                fill="hsl(var(--chart-1))"
                strokeWidth={1.5}
                fillOpacity={0.12}
              />
              <Area
                type="monotone"
                dataKey="pageviews"
                name="Pageviews"
                stroke="hsl(var(--chart-2))"
                fill="hsl(var(--chart-2))"
                strokeWidth={1.5}
                fillOpacity={0.08}
              />
            </AreaChart>
          </ResponsiveContainer>
        )}
      </CardContent>
    </Card>
  )
}
