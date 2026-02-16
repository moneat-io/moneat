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
import {Area, AreaChart, CartesianGrid, ReferenceLine, ResponsiveContainer, Tooltip, XAxis, YAxis,} from 'recharts'
import type {TimelinePoint} from '@/lib/api'
import {cn} from '@/lib/utils'

export function EventsChartSkeleton({
  fillHeight = false,
  compact = false,
}: {
  fillHeight?: boolean
  compact?: boolean
}) {
  return (
    <Card className={cn("border-t-4 border-t-blue-500/50", fillHeight ? "h-full flex flex-col" : "h-full")}>
      <CardHeader className={cn("px-4 shrink-0", compact ? "py-1" : "py-2")}>
        <div className={cn("h-4 bg-muted rounded animate-pulse", compact ? "w-36" : "w-40")} />
      </CardHeader>
      <CardContent className={cn("px-4 pt-0", compact ? "pb-1" : "pb-3", fillHeight && "flex-1 min-h-0")}>
        <div className={cn("flex items-end justify-between", compact ? "gap-1" : "gap-2", fillHeight ? "h-full" : "h-[300px]")}>
          {[...Array(24)].map((_, i) => (
            <div
              key={i}
              className={cn("flex-1 bg-muted animate-pulse", compact ? "rounded-sm" : "rounded-t")}
              style={{
                height: `${30 + Math.random() * 70}%`,
                animationDelay: `${i * 50}ms`,
              }}
            />
          ))}
        </div>
      </CardContent>
    </Card>
  )
}

interface ReleaseMarker {
  version: string
  timestamp: string
}

interface EventsChartProps {
  data: TimelinePoint[]
  title?: string
  height?: number
  releaseMarkers?: ReleaseMarker[]
  /** When true, the chart fills its parent container's height instead of using a fixed height */
  fillHeight?: boolean
  /** Compact mode optimized for short containers */
  compact?: boolean
}

const formatTime = (timestamp: string) =>
  new Date(timestamp).toLocaleString('en-US', {
    month: 'short',
    day: 'numeric',
    hour: '2-digit',
  })

export function EventsChart({
  data,
  title = 'Events Over Time',
  height = 300,
  releaseMarkers = [],
  fillHeight = false,
  compact = false,
}: EventsChartProps) {
  const chartData = data.map((point) => ({
    timestamp: new Date(point.timestamp).getTime(),
    time: formatTime(point.timestamp),
    count: point.count,
  }))
  const counts = chartData.map((point) => point.count)
  const minCount = counts.length > 0 ? Math.min(...counts) : 0
  const maxCount = counts.length > 0 ? Math.max(...counts) : 0
  const compactRange = Math.max(maxCount - minCount, 1)
  const compactPadding = Math.max(1, Math.round(compactRange * 0.25))
  const compactDomain: [number, number] = [
    Math.max(0, minCount - compactPadding),
    maxCount + compactPadding,
  ]

  const releaseLines = releaseMarkers
    .map((m) => ({
      ...m,
      timestamp: new Date(m.timestamp).getTime(),
    }))
    .filter((m) => {
      if (chartData.length === 0) return false
      const min = chartData[0]!.timestamp
      const max = chartData[chartData.length - 1]!.timestamp
      return m.timestamp >= min && m.timestamp <= max
    })

  return (
    <Card className={cn("border-t-4 border-t-blue-500/50", fillHeight ? "h-full flex flex-col" : "h-full")}>
      <CardHeader className={cn("px-4 shrink-0", compact ? "py-1" : "py-2")}>
        <CardTitle className={cn(compact ? "text-xs leading-tight" : "text-sm")}>{title}</CardTitle>
      </CardHeader>
      <CardContent className={cn("px-4 pt-0", compact ? "pb-1" : "pb-3", fillHeight && "flex-1 min-h-0")}>
        <ResponsiveContainer width="100%" height={fillHeight ? "100%" : height}>
          <AreaChart
            data={chartData}
            margin={compact ? {top: 4, right: 2, left: 0, bottom: 0} : undefined}
          >
            {!compact && <CartesianGrid strokeDasharray="3 3" className="stroke-muted" />}
            <XAxis
              dataKey="timestamp"
              type="number"
              domain={['dataMin', 'dataMax']}
              tickFormatter={(ts) =>
                compact
                  ? new Date(ts).toLocaleString('en-US', {hour: 'numeric'})
                  : formatTime(new Date(ts).toISOString())
              }
              fontSize={compact ? 10 : 12}
              height={compact ? 16 : 30}
              minTickGap={compact ? 48 : 16}
              interval={compact ? 'preserveStartEnd' : 'preserveEnd'}
              tickLine={false}
              axisLine={false}
              className="fill-muted-foreground"
            />
            <YAxis
              hide={compact}
              domain={compact ? compactDomain : undefined}
              fontSize={12}
              tickLine={false}
              axisLine={false}
              className="fill-muted-foreground"
            />
            <Tooltip
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
            <Area
              type="monotone"
              dataKey="count"
              stroke="hsl(var(--primary))"
              fill="hsl(var(--primary))"
              strokeWidth={compact ? 2.25 : 1.5}
              fillOpacity={compact ? 0.35 : 0.2}
            />
            {releaseLines.map((marker) => (
              <ReferenceLine
                key={marker.version}
                x={marker.timestamp}
                stroke="hsl(var(--muted-foreground))"
                strokeDasharray="4 4"
                label={compact ? undefined : {
                  value: marker.version,
                  position: 'top',
                  fill: 'hsl(var(--muted-foreground))',
                  fontSize: 11,
                }}
              />
            ))}
          </AreaChart>
        </ResponsiveContainer>
      </CardContent>
    </Card>
  )
}
