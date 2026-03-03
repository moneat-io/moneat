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

import {memo, type ReactNode, useEffect, useId, useMemo, useRef, useState} from 'react'
import {useQuery} from '@tanstack/react-query'
import type {DashboardWidget, TimeRangeDef} from '@/lib/api'
import {api} from '@/lib/api'
import {isDemo} from '@/lib/demo'
import {
    Area,
    AreaChart,
    Bar,
    BarChart,
    CartesianGrid,
    Cell,
    Legend,
    Line,
    LineChart,
    Pie,
    PieChart,
    ReferenceLine,
    Tooltip,
    XAxis,
    YAxis,
} from 'recharts'
import {useVirtualizer} from '@tanstack/react-virtual'
import {TopListWidget} from './TopListWidget'
import {HeatmapWidget} from './HeatmapWidget'
import ReactMarkdown from 'react-markdown'
import type {ValueMapping} from './formatValue'
import {formatValue} from './formatValue'

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
 * Parse a ClickHouse datetime string as UTC epoch ms.
 * ClickHouse returns DateTime64 as "2026-02-24 19:00:00.000" (space, no tz suffix).
 * Date.parse on that format is implementation-defined and may use local time.
 * We normalize to ISO-8601 UTC before parsing.
 */
function parseUtcTimestamp(v: string): number {
  // Already has timezone info — parse as-is
  if (v.endsWith('Z') || /[+-]\d{2}:\d{2}$/.test(v)) return Date.parse(v)
  // Normalize space separator to 'T' then append 'Z' for UTC
  const iso = v.includes('T') ? v + 'Z' : v.replace(' ', 'T') + 'Z'
  const ms = Date.parse(iso)
  return isNaN(ms) ? Date.parse(v) : ms
}

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
  variables?: Record<string, string>
}

export const WidgetRenderer = memo(function WidgetRenderer({
  widget,
  dashboardId,
  projectId,
  timeRange,
  autoRefresh,
  variables,
}: WidgetRendererProps) {
  const queries = widget.query_configs?.length > 0 ? widget.query_configs : []
  const isBatch = queries.length > 1
  // Include query config fingerprint so datasource/query changes trigger refetch
  const queryFingerprint = JSON.stringify(queries.map(q => ({d: q.dataSource, r: q.rawQuery || ''})))

  const {data, isLoading, error} = useQuery({
    queryKey: ['widget-data', widget.id, dashboardId, projectId, timeRange, queryFingerprint, variables],
    queryFn: async () => {
      if (!projectId && !isDemo()) return []
      const effectiveProjectId = projectId ?? -1
      if (isBatch) {
        const result = await api.executeBatchQuery(dashboardId, queries, effectiveProjectId, timeRange, variables)
        // Merge batch results: use legendFormat alias as series name, group by timestamp
        const mergedByTime = new Map<unknown, Record<string, unknown>>()
        for (const [refId, rows] of Object.entries(result.results)) {
          if (queries.length === 1) {
            for (const row of rows) {
              const timeVal = Object.entries(row).find(([k]) => TIME_KEYS.has(k))
              const key = timeVal ? timeVal[1] : rows.indexOf(row)
              if (!mergedByTime.has(key)) mergedByTime.set(key, {})
              Object.assign(mergedByTime.get(key)!, row)
            }
          } else {
            const queryIdx = queries.findIndex(q => q.ref_id === refId)
            const query = queryIdx >= 0 ? queries[queryIdx] : queries[refId.charCodeAt(0) - 65]
            const alias = query?.metrics?.[0]?.alias
            for (const row of rows) {
              let timeKey: string | undefined
              let timeVal: unknown
              const values: Record<string, unknown> = {}
              for (const [k, v] of Object.entries(row)) {
                if (TIME_KEYS.has(k)) {
                  timeKey = k
                  timeVal = v
                } else if (typeof v === 'number') {
                  values[alias || `${refId}: ${k}`] = v
                }
              }
              if (timeKey != null) {
                if (!mergedByTime.has(timeVal)) mergedByTime.set(timeVal, {[timeKey]: timeVal})
                Object.assign(mergedByTime.get(timeVal)!, values)
              }
            }
          }
        }
        return Array.from(mergedByTime.values())
      }
      return queries[0]
        ? api.executeWidgetQuery(dashboardId, queries[0], effectiveProjectId, timeRange, variables)
        : []
    },
    enabled: (!!projectId || isDemo()) && widget.widget_type !== 'text' && widget.widget_type !== 'section' && queries.length > 0,
    refetchInterval: autoRefresh ? 30000 : false,
  })

  if (widget.widget_type === 'section') {
    return null
  }

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

  const rawData = data as Record<string, unknown>[]
  const dc = widget.display_config || {}
  const chartData = applyFieldTransforms(rawData, dc)

  switch (widget.widget_type) {
    case 'timeseries':
      return <TimeseriesChart data={chartData} timeRange={timeRange} displayConfig={dc} />
    case 'bar':
      return <BarChartWidget data={chartData} timeRange={timeRange} displayConfig={dc} />
    case 'donut':
      return <DonutChartWidget data={chartData} displayConfig={dc} />
    case 'stat':
      return <StatWidget data={chartData} widget={widget} displayConfig={dc} />
    case 'gauge':
      return <GaugeWidget data={chartData} widget={widget} displayConfig={dc} />
    case 'bargauge':
      return <BarGaugeWidget data={chartData} displayConfig={dc} />
    case 'table':
      return <TableWidget data={chartData} displayConfig={dc} />
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
  const ts = typeof v === 'number' ? v : parseUtcTimestamp(v)
  if (isNaN(ts)) return String(v)
  const d = new Date(ts)
  const month = String(d.getUTCMonth() + 1).padStart(2, '0')
  const day = String(d.getUTCDate()).padStart(2, '0')
  if (spanMs >= 86400000) return `${month}/${day}`
  const hours = String(d.getUTCHours()).padStart(2, '0')
  const mins = String(d.getUTCMinutes()).padStart(2, '0')
  return `${hours}:${mins}`
}

function formatTooltipLabel(v?: string | number) {
  if (v === undefined) return ''
  const ts = typeof v === 'number' ? v : parseUtcTimestamp(v as string)
  if (isNaN(ts)) return String(v)
  const d = new Date(ts)
  const month = String(d.getUTCMonth() + 1).padStart(2, '0')
  const day = String(d.getUTCDate()).padStart(2, '0')
  const hours = String(d.getUTCHours()).padStart(2, '0')
  const mins = String(d.getUTCMinutes()).padStart(2, '0')
  return `${month}/${day} ${hours}:${mins}`
}

function formatTooltipValue(value?: number | string) {
  if (value === undefined) return ''
  if (typeof value !== 'number') return value
  if (Number.isInteger(value)) return value.toLocaleString()
  return value.toLocaleString(undefined, {maximumFractionDigits: 2})
}

type DisplayConfig = Record<string, string>

const GRAFANA_COLORS: Record<string, string> = {
  green: '#73BF69', 'semi-dark-green': '#56A64B', 'dark-green': '#37872D', 'light-green': '#96D98D', 'super-light-green': '#C8F2C2',
  red: '#F2495C', 'semi-dark-red': '#E02F44', 'dark-red': '#C4162A', 'light-red': '#FF7383', 'super-light-red': '#FFA6B0',
  orange: '#FF9830', 'semi-dark-orange': '#FA6400', 'dark-orange': '#E55400',
  yellow: '#FADE2A', 'semi-dark-yellow': '#F2CC0C', 'dark-yellow': '#CC9D00',
  blue: '#5794F2', 'semi-dark-blue': '#3274D9', 'dark-blue': '#1F60C4', 'light-blue': '#8AB8FF', 'super-light-blue': '#C0D8FF',
  purple: '#B877D9', 'semi-dark-purple': '#8F3BB8', 'dark-purple': '#6C2796',
}

function resolveColor(color: string): string {
  return GRAFANA_COLORS[color] || color
}

function parseThresholds(dc: DisplayConfig): {value: number; color: string; label?: string}[] {
  try {
    const raw: {value: number; color: string; label?: string}[] = dc.thresholds ? JSON.parse(dc.thresholds) : []
    return raw.map(t => ({...t, color: resolveColor(t.color)}))
  }
  catch { return [] }
}

function parseValueMappings(dc: DisplayConfig): ValueMapping[] {
  try { return dc.valueMappings ? JSON.parse(dc.valueMappings) : [] }
  catch { return [] }
}

/** Applies visibleFields filtering and fieldRenames from display config (e.g. from Grafana transformations). */
function applyFieldTransforms(data: Record<string, unknown>[], dc: DisplayConfig): Record<string, unknown>[] {
  const visible = dc.visibleFields ? dc.visibleFields.split(',').map(f => f.trim()).filter(Boolean) : null
  let renames: Record<string, string> | null = null
  try { renames = dc.fieldRenames ? JSON.parse(dc.fieldRenames) : null } catch { renames = null }

  if (!visible && !renames) return data

  // Build reverse rename map (renamed -> original) so visibleFields can match renamed names
  const reverseRenames: Record<string, string> = {}
  if (renames) {
    for (const [orig, renamed] of Object.entries(renames)) {
      reverseRenames[renamed] = orig
    }
  }

  return data.map(row => {
    const out: Record<string, unknown> = {}
    if (visible) {
      for (const field of visible) {
        // Try original key first, then check if it matches a renamed name
        const origKey = (field in row) ? field : reverseRenames[field]
        if (origKey && origKey in row) {
          const newKey = renames?.[origKey] || origKey
          out[newKey] = row[origKey]
        }
      }
    } else {
      for (const k of Object.keys(row)) {
        const newKey = renames?.[k] || k
        out[newKey] = row[k]
      }
    }
    return out
  })
}

function getYAxisDomain(dc: DisplayConfig): [string | number, string | number] {
  const min = dc.yAxisMin && dc.yAxisMin !== 'auto' ? parseFloat(dc.yAxisMin) : 'auto'
  const max = dc.yAxisMax && dc.yAxisMax !== 'auto' ? parseFloat(dc.yAxisMax) : 'auto'
  return [
    typeof min === 'number' && !isNaN(min) ? min : 'auto',
    typeof max === 'number' && !isNaN(max) ? max : 'auto',
  ]
}

function getLegendProps(dc: DisplayConfig) {
  const mode = dc.legendMode || 'list'
  if (mode === 'hidden') return null
  const placement = dc.legendPlacement || 'bottom'
  return {
    wrapperStyle: {fontSize: '10px', paddingTop: '4px'},
    iconType: 'line' as const,
    iconSize: 8,
    layout: (placement === 'right' ? 'vertical' : 'horizontal') as 'vertical' | 'horizontal',
    verticalAlign: (placement === 'right' ? 'middle' : 'bottom') as 'top' | 'middle' | 'bottom',
    align: (placement === 'right' ? 'right' : 'center') as 'left' | 'center' | 'right',
  }
}

function getDotProp(showPoints: string | undefined) {
  if (showPoints === 'always') return {r: 2}
  if (showPoints === 'auto') return {r: 0}
  return false
}

function getThresholdColor(value: number, thresholds: {value: number; color: string}[]): string | undefined {
  if (thresholds.length === 0) return undefined
  const sorted = [...thresholds].sort((a, b) => b.value - a.value)
  for (const t of sorted) {
    if (value >= t.value) return t.color
  }
  return undefined
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

const CHART_MARGIN = {top: 5, right: 5, left: 20, bottom: 20}
const TOOLTIP_STYLE = {
  backgroundColor: 'hsl(var(--popover))',
  border: '1px solid hsl(var(--border))',
  borderRadius: '6px',
  fontSize: '11px',
}
const TOOLTIP_WRAPPER_STYLE = {zIndex: 1000}

const TimeseriesChart = memo(function TimeseriesChart({data, timeRange, displayConfig: dc}: {data: Record<string, unknown>[]; timeRange: TimeRangeDef; displayConfig: DisplayConfig}) {
  const {timeKey, labelKeys, valueKeys} = useMemo(() => classifyColumns(data), [data])
  const xKey = timeKey || 'time_bucket'
  const spanMs = getTimeSpanMs(timeRange)

  const hasLabels = labelKeys.length > 0 && valueKeys.length > 0
  const {pivoted, seriesKeys} = useMemo(
    () => hasLabels ? pivotData(data, xKey, labelKeys, valueKeys) : {pivoted: data, seriesKeys: valueKeys},
    [data, xKey, labelKeys, valueKeys, hasLabels]
  )

  // Recharts type="number" scale="time" requires numeric epoch ms values.
  // ClickHouse returns time_bucket as a string ("2026-02-24 19:00:00.000"), so
  // we normalize the time key to epoch ms for correct chart positioning.
  const chartData = useMemo(() =>
    pivoted.map(row => {
      const v = row[xKey]
      if (typeof v === 'string') {
        const ms = parseUtcTimestamp(v)
        return isNaN(ms) ? row : {...row, [xKey]: ms}
      }
      return row
    }),
    [pivoted, xKey]
  )

  const thresholds = parseThresholds(dc)
  const legendProps = getLegendProps(dc)
  const yDomain = getYAxisDomain(dc)
  const lineWidth = parseFloat(dc.lineWidth || '1.5')
  const fillOpacity = parseFloat(dc.fillOpacity || '0')
  const interpolation = (dc.lineInterpolation || 'monotone') as 'linear' | 'monotone' | 'step'
  const dotProp = getDotProp(dc.showPoints)
  const stackMode = dc.stackMode || 'none'
  const showGrid = dc.showGrid !== 'false'
  const unit = dc.unit
  const decimals = dc.decimals
  const tickFormatter = unit && unit !== 'none'
    ? (v: number) => formatValue(v, unit, decimals)
    : undefined
  const tooltipFormatter = unit && unit !== 'none'
    ? (v?: number | string) => (v !== undefined && typeof v === 'number' ? formatValue(v, unit, decimals) : (v ?? ''))
    : formatTooltipValue

  const useArea = fillOpacity > 0 || stackMode !== 'none'

  return (
    <DebouncedChartContainer>
      {(w, h) => useArea ? (
        <AreaChart width={w} height={h} data={chartData} margin={CHART_MARGIN}>
          {showGrid && <CartesianGrid strokeDasharray="3 3" className="stroke-muted" />}
          <XAxis
            dataKey={xKey}
            tick={{fontSize: 10}}
            tickFormatter={(v) => formatXAxisTick(v, spanMs)}
            type="number"
            domain={['dataMin', 'dataMax']}
            scale="time"
          />
          <YAxis
            tick={{fontSize: 10}}
            width={50}
            domain={yDomain}
            scale={dc.yAxisScale === 'log' ? 'log' : 'auto'}
            label={dc.yAxisLabel ? {value: dc.yAxisLabel, angle: -90, position: 'insideLeft', style: {fontSize: 10}} : undefined}
            tickFormatter={tickFormatter}
          />
          <Tooltip
            contentStyle={TOOLTIP_STYLE}
            wrapperStyle={TOOLTIP_WRAPPER_STYLE}
            labelFormatter={(label) => formatTooltipLabel(label as string | number)}
            formatter={tooltipFormatter as (value: string | number | undefined, name: string | undefined, props: unknown) => ReactNode}
          />
          {legendProps && <Legend {...legendProps} />}
          {thresholds.map((t, i) => (
            <ReferenceLine key={`t-${i}`} y={t.value} stroke={t.color} strokeDasharray="4 4" label={t.label} />
          ))}
          {seriesKeys.map((key, i) => (
            <Area
              key={key}
              type={interpolation}
              dataKey={key}
              stroke={COLORS[i % COLORS.length]}
              strokeWidth={lineWidth}
              fill={COLORS[i % COLORS.length]}
              fillOpacity={fillOpacity || 0.3}
              dot={dotProp}
              activeDot={{r: 3}}
              connectNulls
              stackId={stackMode !== 'none' ? 'stack' : undefined}
            />
          ))}
        </AreaChart>
      ) : (
        <LineChart width={w} height={h} data={chartData} margin={CHART_MARGIN}>
          {showGrid && <CartesianGrid strokeDasharray="3 3" className="stroke-muted" />}
          <XAxis
            dataKey={xKey}
            tick={{fontSize: 10}}
            tickFormatter={(v) => formatXAxisTick(v, spanMs)}
            type="number"
            domain={['dataMin', 'dataMax']}
            scale="time"
          />
          <YAxis
            tick={{fontSize: 10}}
            width={50}
            domain={yDomain}
            scale={dc.yAxisScale === 'log' ? 'log' : 'auto'}
            label={dc.yAxisLabel ? {value: dc.yAxisLabel, angle: -90, position: 'insideLeft', style: {fontSize: 10}} : undefined}
            tickFormatter={tickFormatter}
          />
          <Tooltip
            contentStyle={TOOLTIP_STYLE}
            wrapperStyle={TOOLTIP_WRAPPER_STYLE}
            labelFormatter={(label) => formatTooltipLabel(label as string | number)}
            formatter={tooltipFormatter as (value: string | number | undefined, name: string | undefined, props: unknown) => ReactNode}
          />
          {legendProps && <Legend {...legendProps} />}
          {thresholds.map((t, i) => (
            <ReferenceLine key={`t-${i}`} y={t.value} stroke={t.color} strokeDasharray="4 4" label={t.label} />
          ))}
          {seriesKeys.map((key, i) => (
            <Line
              key={key}
              type={interpolation}
              dataKey={key}
              stroke={COLORS[i % COLORS.length]}
              strokeWidth={lineWidth}
              dot={dotProp}
              activeDot={{r: 3}}
              connectNulls
            />
          ))}
        </LineChart>
      )}
    </DebouncedChartContainer>
  )
})

const BarChartWidget = memo(function BarChartWidget({data, timeRange, displayConfig: dc}: {data: Record<string, unknown>[]; timeRange: TimeRangeDef; displayConfig: DisplayConfig}) {
  const {timeKey, labelKeys, valueKeys} = useMemo(() => classifyColumns(data), [data])
  const spanMs = getTimeSpanMs(timeRange)
  const hasTime = !!timeKey

  const thresholds = parseThresholds(dc)
  const legendProps = getLegendProps(dc)
  const yDomain = getYAxisDomain(dc)
  const showGrid = dc.showGrid !== 'false'
  const barMode = dc.barMode || 'grouped'
  const unit = dc.unit
  const decimals = dc.decimals
  const tickFormatter = unit && unit !== 'none'
    ? (v: number) => formatValue(v, unit, decimals)
    : undefined
  const tooltipFormatter = unit && unit !== 'none'
    ? (v?: number | string) => (v !== undefined && typeof v === 'number' ? formatValue(v, unit, decimals) : (v ?? ''))
    : formatTooltipValue

  if (hasTime && labelKeys.length > 0 && valueKeys.length > 0) {
    const {pivoted: rawPivoted, seriesKeys} = pivotData(data, timeKey!, labelKeys, valueKeys)
    const pivoted = rawPivoted.map(row => {
      const v = row[timeKey!]
      if (typeof v === 'string') { const ms = parseUtcTimestamp(v); return isNaN(ms) ? row : {...row, [timeKey!]: ms} }
      return row
    })
    return (
      <DebouncedChartContainer>
        {(w, h) => (
          <BarChart width={w} height={h} data={pivoted} margin={CHART_MARGIN}>
            {showGrid && <CartesianGrid strokeDasharray="3 3" className="stroke-muted" />}
            <XAxis
              dataKey={timeKey}
              tick={{fontSize: 10}}
              tickFormatter={(v) => formatXAxisTick(v, spanMs)}
              type="number"
              domain={['dataMin', 'dataMax']}
              scale="time"
            />
            <YAxis
              tick={{fontSize: 10}}
              width={50}
              domain={yDomain}
              scale={dc.yAxisScale === 'log' ? 'log' : 'auto'}
              label={dc.yAxisLabel ? {value: dc.yAxisLabel, angle: -90, position: 'insideLeft', style: {fontSize: 10}} : undefined}
              tickFormatter={tickFormatter}
            />
            <Tooltip
              cursor={{fill: 'transparent'}}
              contentStyle={TOOLTIP_STYLE}
              wrapperStyle={TOOLTIP_WRAPPER_STYLE}
              labelFormatter={(label) => formatTooltipLabel(label as string | number)}
              formatter={tooltipFormatter as (value: string | number | undefined, name: string | undefined, props: unknown) => ReactNode}
            />
            {legendProps && <Legend {...legendProps} iconSize={8} />}
            {thresholds.map((t, i) => (
              <ReferenceLine key={`t-${i}`} y={t.value} stroke={t.color} strokeDasharray="4 4" label={t.label} />
            ))}
            {seriesKeys.map((key, i) => (
              <Bar key={key} dataKey={key} fill={COLORS[i % COLORS.length]} stackId={barMode === 'stacked' ? 'stack' : undefined} />
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
          {showGrid && <CartesianGrid strokeDasharray="3 3" className="stroke-muted" />}
          <XAxis dataKey={xKey} tick={{fontSize: 10}} />
          <YAxis
            tick={{fontSize: 10}}
            width={50}
            domain={yDomain}
            label={dc.yAxisLabel ? {value: dc.yAxisLabel, angle: -90, position: 'insideLeft', style: {fontSize: 10}} : undefined}
            tickFormatter={tickFormatter}
          />
          <Tooltip cursor={{fill: 'transparent'}} contentStyle={TOOLTIP_STYLE} formatter={tooltipFormatter} />
          {legendProps && <Legend {...legendProps} iconSize={8} />}
          {thresholds.map((t, i) => (
            <ReferenceLine key={`t-${i}`} y={t.value} stroke={t.color} strokeDasharray="4 4" label={t.label} />
          ))}
          {barKeys.map((key, i) => (
            <Bar key={key} dataKey={key} fill={COLORS[i % COLORS.length]} radius={[2, 2, 0, 0]} stackId={barMode === 'stacked' ? 'stack' : undefined} />
          ))}
        </BarChart>
      )}
    </DebouncedChartContainer>
  )
})

const DonutChartWidget = memo(function DonutChartWidget({data, displayConfig: dc}: {data: Record<string, unknown>[]; displayConfig: DisplayConfig}) {
  const {labelKeys, valueKeys} = useMemo(() => classifyColumns(data), [data])
  const labelKey = labelKeys[0]
  const valueKey = valueKeys[0]
  const legendProps = getLegendProps(dc)

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
            label={({percent}) => `${((percent ?? 0) * 100).toFixed(0)}%`}
            labelLine={false}
          >
            {data.map((_, i) => (
              <Cell key={i} fill={COLORS[i % COLORS.length]} />
            ))}
          </Pie>
          <Tooltip wrapperStyle={TOOLTIP_WRAPPER_STYLE} />
          {legendProps && (
            <Legend
              {...legendProps}
              wrapperStyle={{fontSize: '11px'}}
              iconSize={10}
            />
          )}
        </PieChart>
      )}
    </DebouncedChartContainer>
  )
})

function deduplicateStatData(
  data: Record<string, unknown>[],
  labelKeys: string[],
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
  displayConfig: dc,
}: {
  data: Record<string, unknown>[]
  widget: DashboardWidget
  displayConfig: DisplayConfig
}) {
  const {timeKey, labelKeys, valueKeys} = useMemo(() => classifyColumns(data), [data])
  const gradientId = `stat-${useId().replace(/:/g, '-')}`
  const thresholds = parseThresholds(dc)
  const valueMappings = parseValueMappings(dc)
  const unit = dc.unit
  const decimals = dc.decimals

  const fmtStat = (v: unknown) => {
    if (unit && unit !== 'none') return formatValue(v, unit, decimals, valueMappings)
    if (valueMappings.length > 0) return formatValue(v, undefined, undefined, valueMappings)
    return formatStatValue(v)
  }

  // Categorical data (e.g. App Rating 1–5 with counts): show horizontal bars like Grafana
  const isCategorical = labelKeys.length > 0 && valueKeys.length > 0 && data.length > 1
  if (isCategorical) {
    const deduped = deduplicateStatData(data, labelKeys)
    const valueKey = valueKeys[0]

    if (deduped.length <= 3) {
      return (
        <div className="h-full flex flex-col items-center justify-center gap-2">
          {deduped.map((row, i) => {
            const val = row[valueKey]
            const label = labelKeys.map((k) => String(row[k] ?? '')).join(' ')
            const tColor = typeof val === 'number' ? getThresholdColor(val as number, thresholds) : undefined
            return (
              <div key={i} className="text-center max-w-full px-2">
                <div
                  className="text-3xl font-bold tabular-nums truncate"
                  style={tColor ? {color: tColor} : undefined}
                >
                  {fmtStat(val)}
                </div>
                {deduped.length > 1 && (
                  <div className="text-[11px] text-muted-foreground mt-0.5 truncate">{label}</div>
                )}
              </div>
            )
          })}
        </div>
      )
    }

    const maxValue = Math.max(...deduped.map((r) => Number(r[valueKey]) || 0), 1)
    return (
      <div className="h-full overflow-auto space-y-1.5 p-2">
        {deduped.slice(0, 20).map((row, i) => {
          const label = labelKeys.map((k) => String(row[k] ?? '')).join(' ')
          const value = Number(row[valueKey]) || 0
          const pctWidth = (value / maxValue) * 100
          const barColor = getThresholdColor(value, thresholds) || COLORS[i % COLORS.length]
          return (
            <div key={i} className="relative">
              <div
                className="absolute inset-0 rounded"
                style={{width: `${pctWidth}%`, backgroundColor: barColor, opacity: 0.15}}
              />
              <div className="relative flex items-center justify-between px-2 py-1 text-xs">
                <span className="truncate font-medium">{label}</span>
                <span className="tabular-nums ml-2 shrink-0 font-semibold" style={{color: barColor}}>
                  {fmtStat(value)}
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
    const thresholdColor = typeof mainValue === 'number' ? getThresholdColor(mainValue, thresholds) : undefined
    const sparkColor = thresholdColor || 'hsl(var(--primary))'
    const sparklineData = sorted.map((r) => ({
      t: r[timeKey!],
      v: Number(r[valueKey] ?? 0),
    }))

    return (
      <div className="h-full flex flex-col">
        <div className="flex-1 flex flex-col items-center justify-center gap-0.5 shrink-0">
          <div className="text-3xl font-bold tabular-nums" style={thresholdColor ? {color: thresholdColor} : undefined}>
            {fmtStat(mainValue)}
          </div>
          <div className="text-xs text-muted-foreground">{widget.title || valueKey?.replace(/_/g, ' ')}</div>
        </div>
        <div className="h-12 shrink-0 mt-1 -mb-1">
          <DebouncedChartContainer>
            {(w, h) => (
              <AreaChart width={w} height={h} data={sparklineData} margin={{top: 2, right: 2, left: 2, bottom: 2}}>
                <defs>
                  <linearGradient id={gradientId} x1="0" y1="0" x2="0" y2="1">
                    <stop offset="0%" stopColor={sparkColor} stopOpacity={0.4} />
                    <stop offset="100%" stopColor={sparkColor} stopOpacity={0} />
                  </linearGradient>
                </defs>
                <Area
                  type="monotone"
                  dataKey="v"
                  stroke={sparkColor}
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
  const allNonTimeKeys = Object.keys(row).filter((k) => !isTimeKey(k))
  // Include string-only columns when there's a single row with no numeric data
  // (e.g. Redis INFO fields like redis_version, maxmemory_policy)
  const displayKeys = valueKeys.length > 0
    ? valueKeys
    : labelKeys.length > 0 && valueKeys.length === 0 && data.length === 1
      ? labelKeys
      : allNonTimeKeys.filter((k) => typeof row[k] === 'number')

  if (displayKeys.length === 0) {
    return (
      <div className="h-full flex items-center justify-center text-xs text-muted-foreground">
        No data
      </div>
    )
  }

  return (
    <div className="h-full flex flex-col items-center justify-center gap-1">
      {displayKeys.map((key) => {
        const val = row[key]
        const tColor = typeof val === 'number' ? getThresholdColor(val, thresholds) : undefined
        return (
          <div key={key} className="text-center">
            <div className="text-3xl font-bold tabular-nums" style={tColor ? {color: tColor} : undefined}>
              {fmtStat(val)}
            </div>
            <div className="text-xs text-muted-foreground">{widget.title || key.replace(/_/g, ' ')}</div>
          </div>
        )
      })}
    </div>
  )
})

const GAUGE_ARC_START = Math.PI
const GAUGE_ARC_END = 0

const DEFAULT_GAUGE_STOPS = [
  {pct: 0, color: '#73BF69'},
  {pct: 0.7, color: '#FF9830'},
  {pct: 0.9, color: '#F2495C'},
]

const GaugeWidget = memo(function GaugeWidget({
  data,
  widget,
  displayConfig: dc,
}: {
  data: Record<string, unknown>[]
  widget: DashboardWidget
  displayConfig: DisplayConfig
}) {
  const {valueKeys} = useMemo(() => classifyColumns(data), [data])
  const thresholds = parseThresholds(dc)
  const valueMappings = parseValueMappings(dc)
  const unit = dc.unit
  const decimals = dc.decimals

  const row = data[data.length - 1] || data[0] || {}
  const numericKeys = valueKeys.length > 0
    ? valueKeys
    : Object.keys(row).filter(k => typeof row[k] === 'number' && !isTimeKey(k))

  if (numericKeys.length === 0) return null

  const min = parseFloat(dc.gaugeMin || '0')
  const max = parseFloat(dc.gaugeMax || (unit === 'percent' ? '100' : '100'))

  const fmtGauge = (v: number) =>
    (unit && unit !== 'none') || valueMappings.length > 0
      ? formatValue(v, unit, decimals, valueMappings)
      : formatStatValue(v)

  return (
    <div className="h-full w-full flex items-center justify-center p-1 gap-1">
      {numericKeys.map((key) => {
        const rawValue = typeof row[key] === 'number' ? (row[key] as number) : 0
        return (
          <SingleGauge
            key={key}
            value={rawValue}
            label={numericKeys.length > 1 ? key.replace(/_/g, ' ') : (widget.title || key.replace(/_/g, ' '))}
            min={min}
            max={max}
            thresholds={thresholds}
            fmtVal={fmtGauge}
          />
        )
      })}
    </div>
  )
})

function SingleGauge({value, label, min, max, thresholds, fmtVal}: {
  value: number; label: string; min: number; max: number
  thresholds: {value: number; color: string}[]
  fmtVal: (v: number) => string
}) {
  const denom = max - min
  const pct = denom <= 0
    ? (value <= min ? 0 : 1)
    : Math.max(0, Math.min(1, (value - min) / denom))
  const cx = 100, cy = 94, r = 84, strokeW = 26
  const describeArc = (startAngle: number, endAngle: number) => {
    const x1 = cx + r * Math.cos(startAngle)
    const y1 = cy - r * Math.sin(startAngle)
    const x2 = cx + r * Math.cos(endAngle)
    const y2 = cy - r * Math.sin(endAngle)
    const sweep = startAngle > endAngle ? 1 : 0
    return `M ${x1} ${y1} A ${r} ${r} 0 0 ${sweep} ${x2} ${y2}`
  }

  const valueAngle = GAUGE_ARC_START - pct * (GAUGE_ARC_START - GAUGE_ARC_END)

  const gaugeArcs = useMemo(() => {
    if (thresholds.length > 0) {
      const sorted = [...thresholds].sort((a, b) => a.value - b.value)
      const arcs: {startAngle: number; endAngle: number; color: string}[] = []
      for (let i = 0; i <= sorted.length; i++) {
        const fromVal = i === 0 ? min : sorted[i - 1].value
        const toVal = i === sorted.length ? max : sorted[i].value
        const color = i === 0 ? '#73BF69' : sorted[i - 1].color
        if (toVal <= fromVal) continue
        const fromPct = denom <= 0 ? 0 : (fromVal - min) / denom
        const toPct = denom <= 0 ? 1 : (toVal - min) / denom
        arcs.push({
          startAngle: GAUGE_ARC_START - fromPct * Math.PI,
          endAngle: GAUGE_ARC_START - toPct * Math.PI,
          color,
        })
      }
      return arcs
    }
    return DEFAULT_GAUGE_STOPS.map((stop, i, arr) => {
      const nextPct = i < arr.length - 1 ? arr[i + 1].pct : 1
      return {
        startAngle: GAUGE_ARC_START - stop.pct * Math.PI,
        endAngle: GAUGE_ARC_START - nextPct * Math.PI,
        color: stop.color,
      }
    })
  }, [thresholds, min, max, denom])

  const activeColor = useMemo(() => {
    if (thresholds.length > 0) {
      return getThresholdColor(value, thresholds) || '#73BF69'
    }
    if (pct >= 0.9) return '#F2495C'
    if (pct >= 0.7) return '#FF9830'
    return '#73BF69'
  }, [value, pct, thresholds])

  return (
    <svg viewBox="0 -4 200 128" className="w-full h-full" preserveAspectRatio="xMidYMid meet">
      {gaugeArcs.map((arc, i) => (
        <path
          key={`bg-${i}`}
          d={describeArc(arc.startAngle, arc.endAngle)}
          fill="none"
          stroke={arc.color}
          strokeWidth={strokeW}
          strokeLinecap="butt"
          opacity={0.3}
        />
      ))}
      {pct > 0 && gaugeArcs.map((arc, i) => {
        if (valueAngle >= arc.startAngle) return null
        const fillEnd = Math.max(arc.endAngle, valueAngle)
        return (
          <path
            key={`fill-${i}`}
            d={describeArc(arc.startAngle, fillEnd)}
            fill="none"
            stroke={arc.color}
            strokeWidth={strokeW}
            strokeLinecap="butt"
          />
        )
      })}
      <text x={cx} y={cy + 2} textAnchor="middle" className="font-bold" style={{fontSize: '22px', fill: activeColor}}>
        {fmtVal(value)}
      </text>
      <text x={cx} y={cy + 20} textAnchor="middle" className="fill-muted-foreground" style={{fontSize: '11px'}}>
        {label}
      </text>
    </svg>
  )
}

const BarGaugeWidget = memo(function BarGaugeWidget({
  data,
  displayConfig: dc,
}: {
  data: Record<string, unknown>[]
  displayConfig: DisplayConfig
}) {
  const thresholds = parseThresholds(dc)
  const valueMappings = parseValueMappings(dc)
  const unit = dc.unit
  const decimals = dc.decimals
  const isVertical = dc.orientation === 'vertical'

  const fmtVal = (v: unknown) => {
    if (unit && unit !== 'none') return formatValue(v, unit, decimals, valueMappings)
    if (valueMappings.length > 0) return formatValue(v, undefined, undefined, valueMappings)
    return formatStatValue(v)
  }

  const row = data[data.length - 1] || data[0] || {}
  const entries = Object.entries(row)
    .filter(([k, v]) => typeof v === 'number' && !isTimeKey(k))
    .map(([k, v]) => ({label: k.replace(/_/g, ' '), value: v as number}))

  if (entries.length === 0) return null

  const maxVal = Math.max(...entries.map(e => Math.abs(e.value)), 1)
  const gaugeMax = Math.max(parseFloat(dc.gaugeMax || '0') || maxVal, maxVal, 1)

  if (isVertical) {
    return (
      <div className="h-full flex items-end justify-center gap-3 px-2 pb-6 pt-2">
        {entries.map(({label, value}) => {
          const pct = Math.min(100, (Math.abs(value) / gaugeMax) * 100)
          const color = getThresholdColor(value, thresholds) || '#73BF69'
          return (
            <div key={label} className="flex flex-col items-center gap-1 flex-1 h-full justify-end">
              <div className="text-xs font-medium tabular-nums" style={{color}}>
                {fmtVal(value)}
              </div>
              <div className="w-full max-w-[40px] flex-1 relative rounded-t overflow-hidden bg-muted/30">
                <div
                  className="absolute bottom-0 w-full rounded-t"
                  style={{height: `${pct}%`, backgroundColor: color, opacity: 0.85}}
                />
              </div>
              <div className="text-[10px] text-muted-foreground text-center truncate w-full max-w-[80px]">
                {label}
              </div>
            </div>
          )
        })}
      </div>
    )
  }

  return (
    <div className="h-full flex flex-col justify-center gap-2 px-3 py-2 overflow-auto">
      {entries.map(({label, value}) => {
        const pct = Math.min(100, (Math.abs(value) / gaugeMax) * 100)
        const color = getThresholdColor(value, thresholds) || '#73BF69'
        return (
          <div key={label} className="flex items-center gap-2 min-h-[24px]">
            <div className="text-[11px] text-muted-foreground w-[120px] truncate text-right shrink-0">{label}</div>
            <div className="flex-1 h-[18px] bg-muted/30 rounded overflow-hidden relative">
              <div
                className="h-full rounded"
                style={{width: `${pct}%`, backgroundColor: color, opacity: 0.85}}
              />
            </div>
            <div className="text-xs font-medium tabular-nums w-[80px] shrink-0" style={{color}}>
              {fmtVal(value)}
            </div>
          </div>
        )
      })}
    </div>
  )
})

const ROW_HEIGHT = 28

const TableWidget = memo(function TableWidget({data, displayConfig: dc}: {data: Record<string, unknown>[]; displayConfig: DisplayConfig}) {
  const columns = data.length > 0 ? Object.keys(data[0]) : []
  const valueMappings = parseValueMappings(dc)
  const thresholds = parseThresholds(dc)
  const unit = dc.unit
  const decimals = dc.decimals

  const fmtCell = (v: unknown) => {
    if (valueMappings.length > 0) {
      const mapped = formatValue(v, unit, decimals, valueMappings)
      if (mapped !== String(v)) return mapped
    }
    if (unit && unit !== 'none' && typeof v === 'number') return formatValue(v, unit, decimals)
    return String(v ?? '')
  }

  const baseThresholdColor = thresholds.length > 0
    ? [...thresholds].sort((a, b) => a.value - b.value)[0]?.color
    : undefined

  const cellColor = (v: unknown): string | undefined => {
    if (thresholds.length === 0) return undefined
    if (typeof v === 'number') return getThresholdColor(v, thresholds)
    // String cells get the base (lowest) threshold color
    return baseThresholdColor
  }

  const parentRef = useRef<HTMLDivElement>(null)
  // eslint-disable-next-line react-hooks/incompatible-library
  const rowVirtualizer = useVirtualizer({
    count: data.length,
    getScrollElement: () => parentRef.current,
    estimateSize: () => ROW_HEIGHT,
    overscan: 10,
  })

  if (data.length === 0) return null

  const virtualItems = rowVirtualizer.getVirtualItems()
  const totalSize = rowVirtualizer.getTotalSize()
  const paddingTop = virtualItems.length > 0 ? virtualItems[0].start : 0
  const paddingBottom = virtualItems.length > 0 ? totalSize - virtualItems[virtualItems.length - 1].end : 0

  return (
    <div ref={parentRef} className="h-full overflow-auto">
      <table className="w-full text-xs">
        <thead className="sticky top-0 bg-muted/90 z-10 shadow-sm">
          <tr>
            {columns.map((col) => (
              <th key={col} className="text-left px-2 py-1.5 font-medium whitespace-nowrap bg-background/95 backdrop-blur">
                {col.replace(/_/g, ' ')}
              </th>
            ))}
          </tr>
        </thead>
        <tbody>
          {paddingTop > 0 && (
            <tr>
              <td colSpan={columns.length} style={{height: `${paddingTop}px`}} />
            </tr>
          )}
          {virtualItems.map((virtualRow) => {
            const row = data[virtualRow.index]
            return (
              <tr
                key={virtualRow.index}
                className="border-t border-muted/30 hover:bg-muted/20 transition-colors"
                style={{
                  height: `${virtualRow.size}px`,
                }}
              >
                {columns.map((col) => {
                    const v = row[col]
                    const color = cellColor(v)
                    return (
                      <td
                        key={col}
                        className="px-2 py-1 truncate max-w-[300px]"
                        title={String(v)}
                        style={color ? {color} : undefined}
                      >
                        {fmtCell(v)}
                      </td>
                    )
                  })}
              </tr>
            )
          })}
          {paddingBottom > 0 && (
            <tr>
              <td colSpan={columns.length} style={{height: `${paddingBottom}px`}} />
            </tr>
          )}
        </tbody>
      </table>
    </div>
  )
})
