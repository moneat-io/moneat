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

// ─────────────────────────────────────────────────────────────────────────────
// MetricChart — a compact over-time chart for the resource detail panel. One
// series renders as a gradient area; multiple series render as overlaid lines
// with a legend (load average, network throughput). Every point is a real sample
// fetched from the telemetry endpoint — no history is synthesized. Shared by the
// catalog and the (future) map dock so both surfaces show identical trend graphs.
// ─────────────────────────────────────────────────────────────────────────────

import {useId, useMemo, type ComponentType} from 'react'
import {
  Area,
  AreaChart,
  CartesianGrid,
  Legend,
  Line,
  LineChart,
  ResponsiveContainer,
  Tooltip,
  XAxis,
  YAxis,
} from 'recharts'

import {cn} from '@/lib/utils'
import {formatHostMetricAxisTick, formatHostMetricTooltipLabel} from '@/lib/host-metrics-chart'

// Categorical chart colors from the shared palette (style guide chart-1..6).
const SERIES_COLORS = [
  'hsl(var(--chart-1))',
  'hsl(var(--chart-2))',
  'hsl(var(--chart-3))',
  'hsl(var(--chart-4))',
  'hsl(var(--chart-5))',
  'hsl(var(--chart-6))',
]

export interface MetricChartPoint {
  /** Epoch milliseconds. */
  readonly ts: number
  readonly value: number | null
}

export interface MetricChartLine {
  /** dataKey and legend label. */
  readonly name: string
  readonly points: readonly MetricChartPoint[]
}

export interface MetricChartProps {
  readonly title: string
  readonly subtitle?: string
  readonly icon: ComponentType<{className?: string}>
  readonly iconClass?: string
  readonly lines: readonly MetricChartLine[]
  readonly rangeSeconds: number
  readonly timezone: string
  readonly height?: number
  readonly yDomain?: readonly [number, number]
  readonly formatValue: (value: number) => string
  readonly formatYTick?: (value: number) => string
  /** Force a "No data" placeholder regardless of the series. */
  readonly noData?: boolean
}

interface TooltipEntry {
  readonly color?: string
  readonly name?: string
  readonly value?: number
  readonly dataKey?: string | number
}

function ChartTooltip({
  active,
  payload,
  label,
  timezone,
  formatValue,
  multi,
}: {
  readonly active?: boolean
  readonly payload?: readonly TooltipEntry[]
  readonly label?: string | number
  readonly timezone: string
  readonly formatValue: (value: number) => string
  readonly multi: boolean
}) {
  const rows = payload?.filter(
    (entry): entry is TooltipEntry & {value: number} => typeof entry.value === 'number' && Number.isFinite(entry.value),
  )
  if (!active || !rows || rows.length === 0) return null
  return (
    <div className="rounded-md border bg-popover/95 px-2.5 py-1.5 shadow-md backdrop-blur-sm">
      <p className="mb-1 text-[11px] text-muted-foreground">{formatHostMetricTooltipLabel(label, timezone)}</p>
      {rows.map((entry) => (
        <div key={String(entry.dataKey ?? entry.name)} className="flex items-center gap-1.5 text-xs">
          <span className="h-2 w-2 shrink-0 rounded-full" style={{backgroundColor: entry.color}} />
          {multi && <span className="text-muted-foreground">{entry.name}</span>}
          <span className="ml-auto pl-3 font-medium tabular-nums">{formatValue(entry.value)}</span>
        </div>
      ))}
    </div>
  )
}

type ChartRow = Record<string, number | null>

export function MetricChart({
  title,
  subtitle,
  icon: Icon,
  iconClass,
  lines,
  rangeSeconds,
  timezone,
  height = 150,
  yDomain,
  formatValue,
  formatYTick,
  noData = false,
}: MetricChartProps) {
  const gradientId = `cat-grad-${useId().replaceAll(/[^a-zA-Z0-9]/g, '')}`
  const multi = lines.length > 1

  const data = useMemo<ChartRow[]>(() => {
    const byTs = new Map<number, ChartRow>()
    for (const line of lines) {
      for (const point of line.points) {
        const row = byTs.get(point.ts) ?? {timestamp: point.ts}
        row[line.name] = point.value
        byTs.set(point.ts, row)
      }
    }
    return [...byTs.values()].sort((a, b) => Number(a.timestamp) - Number(b.timestamp))
  }, [lines])

  const hasData = lines.some((line) => line.points.some((point) => point.value !== null))

  const xAxis = {
    dataKey: 'timestamp',
    type: 'number' as const,
    scale: 'time' as const,
    domain: ['dataMin', 'dataMax'] as [string, string],
    tick: {fontSize: 10},
    tickLine: false,
    axisLine: false,
    minTickGap: 28,
    className: 'fill-muted-foreground',
    tickFormatter: (value: string | number) => formatHostMetricAxisTick(value, rangeSeconds, timezone),
  }
  const yAxisDomain: [number | string, number | string] = yDomain ? [yDomain[0], yDomain[1]] : ['auto', 'auto']
  const yAxis = {
    tick: {fontSize: 10},
    tickLine: false,
    axisLine: false,
    width: 40,
    className: 'fill-muted-foreground',
    domain: yAxisDomain,
    tickFormatter: formatYTick ? (value: string | number) => formatYTick(Number(value)) : undefined,
  }
  const grid = {strokeDasharray: '3 3', className: 'stroke-muted/40', vertical: false}
  const tooltip = <ChartTooltip timezone={timezone} formatValue={formatValue} multi={multi} />

  return (
    <div className="rounded-md border border-border/70 bg-background/40 p-3">
      <div className="mb-1.5 flex items-center gap-1.5">
        <span className="flex h-5 w-5 shrink-0 items-center justify-center rounded bg-muted">
          <Icon className={cn('h-3 w-3', iconClass)} />
        </span>
        <div className="min-w-0">
          <p className="truncate text-xs font-medium leading-tight">{title}</p>
          {subtitle && <p className="truncate text-[10px] text-muted-foreground">{subtitle}</p>}
        </div>
      </div>
      {noData || !hasData ? (
        <div
          className="flex flex-col items-center justify-center gap-1 rounded bg-muted/20 text-muted-foreground"
          style={{height}}
        >
          <Icon className="h-5 w-5 opacity-25" />
          <span className="text-[11px]">No data</span>
        </div>
      ) : (
        <ResponsiveContainer width="100%" height={height}>
          {multi ? (
            <LineChart data={data} margin={{top: 4, right: 8, bottom: 0, left: 0}}>
              <CartesianGrid {...grid} />
              <XAxis {...xAxis} />
              <YAxis {...yAxis} />
              <Tooltip content={tooltip} />
              <Legend iconType="circle" iconSize={7} wrapperStyle={{fontSize: '10px'}} />
              {lines.map((line, i) => (
                <Line
                  key={line.name}
                  type="monotone"
                  dataKey={line.name}
                  stroke={SERIES_COLORS[i % SERIES_COLORS.length]}
                  strokeWidth={1.75}
                  dot={false}
                  activeDot={{r: 3, strokeWidth: 0}}
                  connectNulls
                  isAnimationActive={false}
                />
              ))}
            </LineChart>
          ) : (
            <AreaChart data={data} margin={{top: 4, right: 8, bottom: 0, left: 0}}>
              <defs>
                <linearGradient id={gradientId} x1="0" y1="0" x2="0" y2="1">
                  <stop offset="5%" stopColor={SERIES_COLORS[0]} stopOpacity={0.25} />
                  <stop offset="95%" stopColor={SERIES_COLORS[0]} stopOpacity={0} />
                </linearGradient>
              </defs>
              <CartesianGrid {...grid} />
              <XAxis {...xAxis} />
              <YAxis {...yAxis} />
              <Tooltip content={tooltip} />
              <Area
                type="monotone"
                dataKey={lines[0].name}
                stroke={SERIES_COLORS[0]}
                strokeWidth={2}
                fill={`url(#${gradientId})`}
                fillOpacity={1}
                connectNulls
                isAnimationActive={false}
              />
            </AreaChart>
          )}
        </ResponsiveContainer>
      )}
    </div>
  )
}
