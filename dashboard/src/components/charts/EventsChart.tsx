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
  readonly fillHeight?: boolean
  readonly compact?: boolean
}) {
  return (
    <Card className={cn("border-t-4 border-t-blue-500/50", fillHeight ? "h-full flex flex-col" : "h-full")}>
      <CardHeader className={cn("px-4 shrink-0", compact ? "py-1" : "py-2")}>
        <div className={cn("h-4 bg-muted rounded animate-pulse", compact ? "w-36" : "w-40")} />
      </CardHeader>
      <CardContent className={cn("px-4 pt-0", compact ? "pb-1" : "pb-3", fillHeight && "flex-1 min-h-0")}>
        <div className={cn("flex items-end justify-between", compact ? "gap-1" : "gap-2", fillHeight ? "h-full" : "h-[300px]")}>
          {Array.from({length: 24}, (_, i) => ({height: 30 + ((i * 17 + 11) % 70), delay: i * 50})).map(({height, delay}) => (
            <div
              key={`bar-h${height}d${delay}`}
              className={cn("flex-1 bg-muted animate-pulse", compact ? "rounded-sm" : "rounded-t")}
              style={{
                height: `${height}%`,
                animationDelay: `${delay}ms`,
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
  readonly data: TimelinePoint[]
  readonly title?: string
  readonly height?: number
  readonly releaseMarkers?: ReleaseMarker[]
  /** When true, the chart fills its parent container's height instead of using a fixed height */
  readonly fillHeight?: boolean
  /** Compact mode optimized for short containers */
  readonly compact?: boolean
}

const formatTime = (timestamp: string) =>
  new Date(timestamp).toLocaleString('en-US', {
    month: 'short',
    day: 'numeric',
    hour: '2-digit',
  })

type ChartPoint = {timestamp: number; time: string; count: number}

function computeCompactDomain(chartData: ChartPoint[]): [number, number] {
  if (chartData.length === 0) return [0, 1]
  const counts = chartData.map((p) => p.count)
  const minCount = Math.min(...counts)
  const maxCount = Math.max(...counts)
  const range = Math.max(maxCount - minCount, 1)
  const padding = Math.max(1, Math.round(range * 0.25))
  return [Math.max(0, minCount - padding), maxCount + padding]
}

function filterReleaseMarkers(markers: ReleaseMarker[], chartData: ChartPoint[]) {
  if (chartData.length === 0) return []
  const timestamps = chartData.map((p) => p.timestamp)
  const min = Math.min(...timestamps)
  const max = Math.max(...timestamps)
  return markers
    .map((m) => ({...m, timestamp: new Date(m.timestamp).getTime()}))
    .filter((m) => m.timestamp >= min && m.timestamp <= max)
}

const TOOLTIP_CONTENT_STYLE = {
  backgroundColor: 'hsl(var(--popover) / 0.95)',
  border: '1px solid hsl(var(--border))',
  borderRadius: '6px',
  color: 'hsl(var(--popover-foreground))',
  padding: '8px 12px',
  fontSize: '13px',
}

const TOOLTIP_LABEL_STYLE = {
  color: 'hsl(var(--popover-foreground))',
  fontWeight: '500',
  fontSize: '13px',
}

const TOOLTIP_ITEM_STYLE = {
  color: 'hsl(var(--popover-foreground))',
  fontSize: '13px',
}

function buildReleaseLineLabel(compact: boolean, version: string) {
  if (compact) return undefined
  return {value: version, position: 'top' as const, fill: 'hsl(var(--muted-foreground))', fontSize: 11}
}

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
  const compactDomain = computeCompactDomain(chartData)
  const releaseLines = filterReleaseMarkers(releaseMarkers, chartData)
  const tickFormatter = compact
    ? (ts: number) => new Date(ts).toLocaleString('en-US', {hour: 'numeric'})
    : (ts: number) => formatTime(new Date(ts).toISOString())

  const cfg = compact
    ? {headerPy: 'py-1', contentPb: 'pb-1', titleSize: 'text-xs leading-tight', margin: {top: 4, right: 2, left: 0, bottom: 0}, xFontSize: 10, xHeight: 16, xMinTickGap: 48, xInterval: 'preserveStartEnd' as const, yDomain: compactDomain, strokeWidth: 2.25, fillOpacity: 0.35}
    : {headerPy: 'py-2', contentPb: 'pb-3', titleSize: 'text-sm', margin: undefined, xFontSize: 12, xHeight: 30, xMinTickGap: 16, xInterval: 'preserveEnd' as const, yDomain: undefined, strokeWidth: 2, fillOpacity: 0.15}

  const cardClass = cn('border-t-4 border-t-blue-500/50', fillHeight ? 'h-full flex flex-col' : 'h-full')
  const contentClass = cn('px-4 pt-0', cfg.contentPb, fillHeight && 'flex-1 min-h-0')

  return (
    <Card className={cardClass}>
      <CardHeader className={cn('px-4 shrink-0', cfg.headerPy)}>
        <CardTitle className={cn(cfg.titleSize)}>{title}</CardTitle>
      </CardHeader>
      <CardContent className={contentClass}>
        <ResponsiveContainer width="100%" height={fillHeight ? '100%' : height}>
          <AreaChart data={chartData} margin={cfg.margin}>
            {!compact && <CartesianGrid strokeDasharray="3 3" className="stroke-muted" />}
            <XAxis
              dataKey="timestamp"
              type="number"
              domain={['dataMin', 'dataMax']}
              tickFormatter={tickFormatter}
              fontSize={cfg.xFontSize}
              height={cfg.xHeight}
              minTickGap={cfg.xMinTickGap}
              interval={cfg.xInterval}
              tickLine={false}
              axisLine={false}
              className="fill-muted-foreground"
            />
            <YAxis
              hide={compact}
              domain={cfg.yDomain}
              fontSize={12}
              tickLine={false}
              axisLine={false}
              className="fill-muted-foreground"
            />
            <Tooltip
              contentStyle={TOOLTIP_CONTENT_STYLE}
              labelStyle={TOOLTIP_LABEL_STYLE}
              itemStyle={TOOLTIP_ITEM_STYLE}
            />
            <Area
              type="monotone"
              dataKey="count"
              stroke="hsl(var(--chart-1))"
              fill="hsl(var(--chart-1))"
              strokeWidth={cfg.strokeWidth}
              fillOpacity={cfg.fillOpacity}
            />
            {releaseLines.map((marker) => (
              <ReferenceLine
                key={marker.version}
                x={marker.timestamp}
                stroke="hsl(var(--muted-foreground))"
                strokeDasharray="4 4"
                label={buildReleaseLineLabel(compact, marker.version)}
              />
            ))}
          </AreaChart>
        </ResponsiveContainer>
      </CardContent>
    </Card>
  )
}
