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

import {memo, useMemo, useRef, useState, useEffect, useId, type ReactNode} from 'react'
import {useQuery} from '@tanstack/react-query'
import type {DashboardWidget, TimeRangeDef} from '@/lib/api'
import {api} from '@/lib/api'
import {
  LineChart, Line, AreaChart, Area, BarChart, Bar, PieChart, Pie, Cell, XAxis, YAxis,
  CartesianGrid, Tooltip, Legend,
} from 'recharts'
import {useVirtualizer} from '@tanstack/react-virtual'
import {TopListWidget} from './TopListWidget'
import {HeatmapWidget} from './HeatmapWidget'
import ReactMarkdown from 'react-markdown'

const COLORS = [
  'hsl(var(--chart-1))',
  'hsl(var(--chart-2))',
  'hsl(var(--chart-3))',
  'hsl(var(--chart-4))',
  'hsl(var(--chart-5))',
  '#8884d8', '#82ca9d', '#ffc658', '#ff7300', '#00C49F',
  '#FF6B6B', '#4ECDC4', '#45B7D1', '#96CEB4', '#FFEAA7',
]

const TIME_KEYS = new Set(['time_bucket', 'timestamp', 'time', 'Time', 'day', 'Day'])

/**
 * Lightweight replacement for Recharts' ResponsiveContainer.
 * Uses a single ResizeObserver per instance and debounces size updates
 * so charts freeze at their current size during resize and only re-render
 * once after it settles. This avoids the N-independent-observer problem
 * that makes ResponsiveContainer so expensive on dashboard grids.
 */
function DebouncedChartContainer({children, debounceMs = 150}: {
  children: (width: number, height: number) => ReactNode
  debounceMs?: number
}) {
  const ref = useRef<HTMLDivElement>(null)
  const [size, setSize] = useState<{w: number; h: number} | null>(null)

  useEffect(() => {
    const el = ref.current
    if (!el) return

    let timer: ReturnType<typeof setTimeout> | null = null
    let isFirstMeasure = true

    const observer = new ResizeObserver(() => {
      if (isFirstMeasure) {
        isFirstMeasure = false
        setSize({w: el.clientWidth, h: el.clientHeight})
        return
      }
      if (timer != null) clearTimeout(timer)
      timer = setTimeout(() => {
        timer = null
        setSize({w: el.clientWidth, h: el.clientHeight})
      }, debounceMs)
    })

    observer.observe(el)
    return () => {
      observer.disconnect()
      if (timer != null) clearTimeout(timer)
    }
  }, [debounceMs])

  return (
    <div ref={ref} style={{width: '100%', height: '100%'}}>
      {size != null && size.w > 0 && size.h > 0 && children(size.w, size.h)}
    </div>
  )
}

interface WidgetRendererProps {
  widget: DashboardWidget
  dashboardId: number
  projectId?: number
  timeRange: TimeRangeDef
  autoRefresh: boolean
}

export const WidgetRenderer = memo(function WidgetRenderer({
  widget,
  dashboardId,
  projectId,
  timeRange,
  autoRefresh,
}: WidgetRendererProps) {
  const {data, isLoading, error} = useQuery({
    queryKey: ['widget-data', widget.id, dashboardId, projectId, timeRange],
    queryFn: () =>
      projectId
        ? api.executeWidgetQuery(dashboardId, widget.query_config, projectId, timeRange)
        : Promise.resolve([]),
    enabled: !!projectId && widget.widget_type !== 'text',
    refetchInterval: autoRefresh ? 30000 : false,
  })

  if (widget.widget_type === 'text') {
    return (
      <div className="prose prose-sm dark:prose-invert max-w-none p-2 overflow-auto h-full">
        <ReactMarkdown>{widget.display_config?.content || widget.title || ''}</ReactMarkdown>
      </div>
    )
  }

  if (isLoading) {
    return <div className="h-full w-full bg-muted/20 animate-pulse rounded" />
  }

  if (error || !data || data.length === 0) {
    return (
      <div className="h-full flex items-center justify-center text-xs text-muted-foreground">
        {error ? 'Query error' : 'No data'}
      </div>
    )
  }

  const chartData = data as Record<string, unknown>[]

  switch (widget.widget_type) {
    case 'timeseries':
      return <TimeseriesChart data={chartData} timeRange={timeRange} />
    case 'bar':
      return <BarChartWidget data={chartData} timeRange={timeRange} />
    case 'donut':
      return <DonutChartWidget data={chartData} />
    case 'stat':
      return <StatWidget data={chartData} widget={widget} timeRange={timeRange} />
    case 'table':
      return <TableWidget data={chartData} />
    case 'toplist':
      return <TopListWidget data={chartData} />
    case 'heatmap':
      return <HeatmapWidget data={chartData} />
    default:
      return (
        <div className="h-full flex items-center justify-center text-xs text-muted-foreground">
          Unknown widget type: {widget.widget_type}
        </div>
      )
  }
})

function isTimeKey(key: string): boolean {
  return TIME_KEYS.has(key)
}

function getTimeSpanMs(timeRange: TimeRangeDef): number {
  const match = /^now-(\d+)([smhdwMy])$/.exec(timeRange.from)
  if (!match) return 86400000
  const amount = parseInt(match[1])
  const unit = match[2]
  const ms: Record<string, number> = {s: 1000, m: 60000, h: 3600000, d: 86400000, w: 604800000, M: 2592000000, y: 31536000000}
  return amount * (ms[unit] || 86400000)
}

function formatXAxisTick(v: string | number, spanMs: number) {
  const ts = typeof v === 'number' ? v : Date.parse(v)
  if (isNaN(ts)) return String(v)
  const d = new Date(ts)
  const month = String(d.getMonth() + 1).padStart(2, '0')
  const day = String(d.getDate()).padStart(2, '0')
  if (spanMs >= 86400000) return `${month}/${day}`
  const hours = String(d.getHours()).padStart(2, '0')
  const mins = String(d.getMinutes()).padStart(2, '0')
  return `${hours}:${mins}`
}

function formatTooltipLabel(v: string | number) {
  const ts = typeof v === 'number' ? v : Date.parse(v)
  if (isNaN(ts)) return String(v)
  const d = new Date(ts)
  const month = String(d.getMonth() + 1).padStart(2, '0')
  const day = String(d.getDate()).padStart(2, '0')
  const hours = String(d.getHours()).padStart(2, '0')
  const mins = String(d.getMinutes()).padStart(2, '0')
  return `${month}/${day} ${hours}:${mins}`
}

function formatTooltipValue(value: number | string) {
  if (typeof value !== 'number') return value
  if (Number.isInteger(value)) return value.toLocaleString()
  return value.toLocaleString(undefined, {maximumFractionDigits: 2})
}

function formatStatValue(value: unknown): string {
  if (typeof value !== 'number') return String(value ?? 0)
  if (Number.isInteger(value)) return value.toLocaleString()
  return value.toLocaleString(undefined, {maximumFractionDigits: 2})
}

function classifyColumns(data: Record<string, unknown>[]) {
  const sample = data[0] || {}
  const timeKey = Object.keys(sample).find(isTimeKey)
  const labelKeys: string[] = []
  const valueKeys: string[] = []

  for (const key of Object.keys(sample)) {
    if (isTimeKey(key)) continue
    const sampleVal = sample[key]
    if (typeof sampleVal === 'number') {
      valueKeys.push(key)
    } else if (typeof sampleVal === 'string') {
      labelKeys.push(key)
    }
  }

  return {timeKey, labelKeys, valueKeys}
}

/**
 * Pivots flat multi-series data into one-row-per-timestamp format for recharts.
 * Input:  [{time_bucket: 100, platform: "android", value: 5}, {time_bucket: 100, platform: "ios", value: 3}]
 * Output: [{time_bucket: 100, "android": 5, "ios": 3}]
 */
function pivotData(data: Record<string, unknown>[], timeKey: string, labelKeys: string[], valueKeys: string[]) {
  if (labelKeys.length === 0 || valueKeys.length === 0) {
    return {pivoted: data, seriesKeys: valueKeys}
  }

  const grouped = new Map<string | number, Record<string, unknown>>()
  const seriesSet = new Set<string>()

  for (const row of data) {
    const t = row[timeKey] as string | number
    if (!grouped.has(t)) {
      grouped.set(t, {[timeKey]: t})
    }
    const entry = grouped.get(t)!

    const labelParts = labelKeys.map(k => String(row[k] ?? '')).filter(Boolean)
    const seriesLabel = labelParts.join(', ') || 'value'

    for (const vk of valueKeys) {
      const key = valueKeys.length > 1 ? `${seriesLabel} (${vk})` : seriesLabel
      entry[key] = row[vk]
      seriesSet.add(key)
    }
  }

  const pivoted = Array.from(grouped.values()).sort((a, b) => {
    const ta = a[timeKey] as number
    const tb = b[timeKey] as number
    return ta - tb
  })

  return {pivoted, seriesKeys: Array.from(seriesSet)}
}

const CHART_MARGIN = {top: 4, right: 4, left: 0, bottom: 0}
const TOOLTIP_STYLE = {
  backgroundColor: 'hsl(var(--popover))',
  border: '1px solid hsl(var(--border))',
  borderRadius: '6px',
  fontSize: '11px',
}
const TOOLTIP_WRAPPER_STYLE = {zIndex: 1000}

const TimeseriesChart = memo(function TimeseriesChart({data, timeRange}: {data: Record<string, unknown>[]; timeRange: TimeRangeDef}) {
  const {timeKey, labelKeys, valueKeys} = useMemo(() => classifyColumns(data), [data])
  const xKey = timeKey || 'time_bucket'
  const spanMs = getTimeSpanMs(timeRange)

  const hasLabels = labelKeys.length > 0 && valueKeys.length > 0
  const {pivoted, seriesKeys} = useMemo(
    () => hasLabels ? pivotData(data, xKey, labelKeys, valueKeys) : {pivoted: data, seriesKeys: valueKeys},
    [data, xKey, labelKeys, valueKeys, hasLabels]
  )

  return (
    <DebouncedChartContainer>
      {(w, h) => (
        <LineChart width={w} height={h} data={pivoted} margin={CHART_MARGIN}>
          <CartesianGrid strokeDasharray="3 3" className="stroke-muted" />
          <XAxis
            dataKey={xKey}
            tick={{fontSize: 10}}
            tickFormatter={(v) => formatXAxisTick(v, spanMs)}
            type="number"
            domain={['dataMin', 'dataMax']}
            scale="time"
          />
          <YAxis tick={{fontSize: 10}} width={50} />
          <Tooltip
            contentStyle={TOOLTIP_STYLE}
            wrapperStyle={TOOLTIP_WRAPPER_STYLE}
            labelFormatter={formatTooltipLabel}
            formatter={formatTooltipValue}
          />
          <Legend
            wrapperStyle={{fontSize: '10px', paddingTop: '4px'}}
            iconType="line"
            iconSize={8}
          />
          {seriesKeys.map((key, i) => (
            <Line
              key={key}
              type="monotone"
              dataKey={key}
              stroke={COLORS[i % COLORS.length]}
              strokeWidth={1.5}
              dot={false}
              activeDot={{r: 3}}
              connectNulls
            />
          ))}
        </LineChart>
      )}
    </DebouncedChartContainer>
  )
})

const BarChartWidget = memo(function BarChartWidget({data, timeRange}: {data: Record<string, unknown>[]; timeRange: TimeRangeDef}) {
  const {timeKey, labelKeys, valueKeys} = useMemo(() => classifyColumns(data), [data])
  const spanMs = getTimeSpanMs(timeRange)
  const hasTime = !!timeKey

  if (hasTime && labelKeys.length > 0 && valueKeys.length > 0) {
    const {pivoted, seriesKeys} = pivotData(data, timeKey!, labelKeys, valueKeys)
    return (
      <DebouncedChartContainer>
        {(w, h) => (
          <BarChart width={w} height={h} data={pivoted} margin={CHART_MARGIN}>
            <CartesianGrid strokeDasharray="3 3" className="stroke-muted" />
            <XAxis
              dataKey={timeKey}
              tick={{fontSize: 10}}
              tickFormatter={(v) => formatXAxisTick(v, spanMs)}
              type="number"
              domain={['dataMin', 'dataMax']}
              scale="time"
            />
            <YAxis tick={{fontSize: 10}} width={50} />
            <Tooltip
              contentStyle={TOOLTIP_STYLE}
              wrapperStyle={TOOLTIP_WRAPPER_STYLE}
              labelFormatter={formatTooltipLabel}
              formatter={formatTooltipValue}
            />
            <Legend wrapperStyle={{fontSize: '10px', paddingTop: '4px'}} iconSize={8} />
            {seriesKeys.map((key, i) => (
              <Bar key={key} dataKey={key} fill={COLORS[i % COLORS.length]} stackId="stack" />
            ))}
          </BarChart>
        )}
      </DebouncedChartContainer>
    )
  }

  const xKey = labelKeys[0] || timeKey || 'category'
  const barKeys = valueKeys.length > 0 ? valueKeys : Object.keys(data[0] || {}).filter(
    k => !isTimeKey(k) && typeof data[0][k] === 'number'
  )

  return (
    <DebouncedChartContainer>
      {(w, h) => (
        <BarChart width={w} height={h} data={data} margin={CHART_MARGIN}>
          <CartesianGrid strokeDasharray="3 3" className="stroke-muted" />
          <XAxis dataKey={xKey} tick={{fontSize: 10}} />
          <YAxis tick={{fontSize: 10}} width={50} />
          <Tooltip contentStyle={TOOLTIP_STYLE} />
          <Legend wrapperStyle={{fontSize: '10px', paddingTop: '4px'}} iconSize={8} />
          {barKeys.map((key, i) => (
            <Bar key={key} dataKey={key} fill={COLORS[i % COLORS.length]} radius={[2, 2, 0, 0]} />
          ))}
        </BarChart>
      )}
    </DebouncedChartContainer>
  )
})

const DonutChartWidget = memo(function DonutChartWidget({data}: {data: Record<string, unknown>[]}) {
  const {labelKeys, valueKeys} = useMemo(() => classifyColumns(data), [data])
  const labelKey = labelKeys[0]
  const valueKey = valueKeys[0]

  if (!labelKey || !valueKey) return <div className="text-xs text-muted-foreground">Invalid data</div>

  return (
    <DebouncedChartContainer>
      {(w, h) => (
        <PieChart width={w} height={h}>
          <Pie
            data={data}
            dataKey={valueKey}
            nameKey={labelKey}
            cx="50%"
            cy="45%"
            innerRadius="35%"
            outerRadius="65%"
            paddingAngle={2}
            label={({percent}) => `${(percent * 100).toFixed(0)}%`}
            labelLine={false}
          >
            {data.map((_, i) => (
              <Cell key={i} fill={COLORS[i % COLORS.length]} />
            ))}
          </Pie>
          <Tooltip wrapperStyle={TOOLTIP_WRAPPER_STYLE} />
          <Legend
            wrapperStyle={{fontSize: '11px'}}
            layout="horizontal"
            verticalAlign="bottom"
            iconSize={10}
          />
        </PieChart>
      )}
    </DebouncedChartContainer>
  )
})

function deduplicateStatData(
  data: Record<string, unknown>[],
  labelKeys: string[],
  valueKeys: string[],
) {
  if (labelKeys.length === 0) return data

  const latest = new Map<string, Record<string, unknown>>()
  const timeKey = Object.keys(data[0] || {}).find(isTimeKey)

  for (const row of data) {
    const key = labelKeys.map(k => String(row[k] ?? '')).join('|')
    const existing = latest.get(key)
    if (!existing) {
      latest.set(key, row)
    } else if (timeKey) {
      const existingTime = existing[timeKey] as number
      const rowTime = row[timeKey] as number
      if (rowTime > existingTime) latest.set(key, row)
    }
  }

  return Array.from(latest.values())
}

const StatWidget = memo(function StatWidget({
  data,
  widget,
  timeRange,
}: {
  data: Record<string, unknown>[]
  widget: DashboardWidget
  timeRange: TimeRangeDef
}) {
  const {timeKey, labelKeys, valueKeys} = useMemo(() => classifyColumns(data), [data])
  const gradientId = `stat-${useId().replace(/:/g, '-')}`

  // Categorical data (e.g. App Rating 1–5 with counts): show horizontal bars like Grafana
  const isCategorical = labelKeys.length > 0 && valueKeys.length > 0 && data.length > 1
  if (isCategorical) {
    const deduped = deduplicateStatData(data, labelKeys, valueKeys)
    const valueKey = valueKeys[0]
    const maxValue = Math.max(...deduped.map((r) => Number(r[valueKey]) || 0), 1)
    return (
      <div className="h-full overflow-auto space-y-1.5 p-2">
        {deduped.slice(0, 20).map((row, i) => {
          const label = labelKeys.map((k) => String(row[k] ?? '')).join(' ')
          const value = Number(row[valueKey]) || 0
          const pct = (value / maxValue) * 100
          return (
            <div key={i} className="relative">
              <div
                className="absolute inset-0 rounded bg-primary/20"
                style={{width: `${pct}%`}}
              />
              <div className="relative flex items-center justify-between px-2 py-1 text-xs">
                <span className="truncate font-medium">{label}</span>
                <span className="tabular-nums text-muted-foreground ml-2 shrink-0">
                  {formatStatValue(value)}
                </span>
              </div>
            </div>
          )
        })}
      </div>
    )
  }

  // Time series data (single series): show main value + sparkline (Grafana-style stat with graph)
  const hasTimeSeries = !!timeKey && valueKeys.length > 0 && data.length > 1 && labelKeys.length === 0
  if (hasTimeSeries) {
    const valueKey = valueKeys[0]
    const sorted = [...data].sort((a, b) => {
      const ta = (a[timeKey!] as number) ?? 0
      const tb = (b[timeKey!] as number) ?? 0
      return ta - tb
    })
    const lastRow = sorted[sorted.length - 1]
    const mainValue = lastRow?.[valueKey]
    const sparklineData = sorted.map((r) => ({
      t: r[timeKey!],
      v: Number(r[valueKey]) ?? 0,
    }))

    return (
      <div className="h-full flex flex-col">
        <div className="flex-1 flex flex-col items-center justify-center gap-0.5 shrink-0">
          <div className="text-2xl font-bold tabular-nums text-primary">
            {formatStatValue(mainValue)}
          </div>
          <div className="text-xs text-muted-foreground">{widget.title || valueKey?.replace(/_/g, ' ')}</div>
        </div>
        <div className="h-12 shrink-0 mt-1 -mb-1">
          <DebouncedChartContainer>
            {(w, h) => (
              <AreaChart width={w} height={h} data={sparklineData} margin={{top: 2, right: 2, left: 2, bottom: 2}}>
                <defs>
                  <linearGradient id={gradientId} x1="0" y1="0" x2="0" y2="1">
                    <stop offset="0%" stopColor="hsl(var(--primary))" stopOpacity={0.4} />
                    <stop offset="100%" stopColor="hsl(var(--primary))" stopOpacity={0} />
                  </linearGradient>
                </defs>
                <Area
                  type="monotone"
                  dataKey="v"
                  stroke="hsl(var(--primary))"
                  strokeWidth={1.5}
                  fill={`url(#${gradientId})`}
                  isAnimationActive={false}
                />
              </AreaChart>
            )}
          </DebouncedChartContainer>
        </div>
      </div>
    )
  }

  // Single value (no time, no multiple categories)
  const row = data[data.length - 1] || data[0] || {}
  const displayKeys = valueKeys.length > 0 ? valueKeys : Object.keys(row).filter(
    (k) => typeof row[k] === 'number' && !isTimeKey(k)
  )

  if (displayKeys.length === 0) {
    return (
      <div className="h-full flex items-center justify-center text-xs text-muted-foreground">
        No numeric data
      </div>
    )
  }

  return (
    <div className="h-full flex flex-col items-center justify-center gap-1">
      {displayKeys.map((key) => (
        <div key={key} className="text-center">
          <div className="text-2xl font-bold tabular-nums">
            {formatStatValue(row[key])}
          </div>
          <div className="text-xs text-muted-foreground">{widget.title || key.replace(/_/g, ' ')}</div>
        </div>
      ))}
    </div>
  )
})

const ROW_HEIGHT = 28

const TableWidget = memo(function TableWidget({data}: {data: Record<string, unknown>[]}) {
  const columns = data.length > 0 ? Object.keys(data[0]) : []

  const parentRef = useRef<HTMLDivElement>(null)
  // eslint-disable-next-line react-hooks/incompatible-library
  const rowVirtualizer = useVirtualizer({
    count: data.length,
    getScrollElement: () => parentRef.current,
    estimateSize: () => ROW_HEIGHT,
    overscan: 10,
  })

  if (data.length === 0) return null

  return (
    <div ref={parentRef} className="h-full overflow-auto">
      <table className="w-full text-xs">
        <thead className="sticky top-0 bg-muted/50 z-10">
          <tr>
            {columns.map((col) => (
              <th key={col} className="text-left px-2 py-1.5 font-medium">
                {col.replace(/_/g, ' ')}
              </th>
            ))}
          </tr>
        </thead>
        <tbody style={{height: `${rowVirtualizer.getTotalSize()}px`, position: 'relative'}}>
          {rowVirtualizer.getVirtualItems().map((virtualRow) => {
            const row = data[virtualRow.index]
            return (
              <tr
                key={virtualRow.index}
                className="border-t border-muted/30"
                style={{
                  position: 'absolute',
                  top: 0,
                  left: 0,
                  width: '100%',
                  height: `${virtualRow.size}px`,
                  transform: `translateY(${virtualRow.start}px)`,
                  display: 'table-row',
                }}
              >
                {columns.map((col) => (
                  <td key={col} className="px-2 py-1 truncate max-w-[200px]">
                    {String(row[col] ?? '')}
                  </td>
                ))}
              </tr>
            )
          })}
        </tbody>
      </table>
    </div>
  )
})
