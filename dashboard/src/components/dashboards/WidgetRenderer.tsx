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

import {useQuery} from '@tanstack/react-query'
import type {DashboardWidget, TimeRangeDef} from '@/lib/api'
import {api} from '@/lib/api'
import {
  LineChart, Line, BarChart, Bar, PieChart, Pie, Cell, XAxis, YAxis,
  CartesianGrid, Tooltip, ResponsiveContainer, Legend,
} from 'recharts'
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

interface WidgetRendererProps {
  widget: DashboardWidget
  dashboardId: number
  projectId?: number
  timeRange: TimeRangeDef
  autoRefresh: boolean
}

export function WidgetRenderer({
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
      return <StatWidget data={chartData} widget={widget} />
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
}

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

function TimeseriesChart({data, timeRange}: {data: Record<string, unknown>[]; timeRange: TimeRangeDef}) {
  const {timeKey, labelKeys, valueKeys} = classifyColumns(data)
  const xKey = timeKey || 'time_bucket'
  const spanMs = getTimeSpanMs(timeRange)

  const hasLabels = labelKeys.length > 0 && valueKeys.length > 0
  const {pivoted, seriesKeys} = hasLabels
    ? pivotData(data, xKey, labelKeys, valueKeys)
    : {pivoted: data, seriesKeys: valueKeys}

  return (
    <ResponsiveContainer width="100%" height="100%">
      <LineChart data={pivoted} margin={{top: 4, right: 4, left: 0, bottom: 0}}>
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
          contentStyle={{
            backgroundColor: 'hsl(var(--popover))',
            border: '1px solid hsl(var(--border))',
            borderRadius: '6px',
            fontSize: '11px',
          }}
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
    </ResponsiveContainer>
  )
}

function BarChartWidget({data, timeRange}: {data: Record<string, unknown>[]; timeRange: TimeRangeDef}) {
  const {timeKey, labelKeys, valueKeys} = classifyColumns(data)
  const spanMs = getTimeSpanMs(timeRange)
  const hasTime = !!timeKey

  if (hasTime && labelKeys.length > 0 && valueKeys.length > 0) {
    const {pivoted, seriesKeys} = pivotData(data, timeKey!, labelKeys, valueKeys)
    return (
      <ResponsiveContainer width="100%" height="100%">
        <BarChart data={pivoted} margin={{top: 4, right: 4, left: 0, bottom: 0}}>
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
            contentStyle={{
              backgroundColor: 'hsl(var(--popover))',
              border: '1px solid hsl(var(--border))',
              borderRadius: '6px',
              fontSize: '11px',
            }}
            labelFormatter={formatTooltipLabel}
            formatter={formatTooltipValue}
          />
          <Legend wrapperStyle={{fontSize: '10px', paddingTop: '4px'}} iconSize={8} />
          {seriesKeys.map((key, i) => (
            <Bar key={key} dataKey={key} fill={COLORS[i % COLORS.length]} stackId="stack" />
          ))}
        </BarChart>
      </ResponsiveContainer>
    )
  }

  const xKey = labelKeys[0] || timeKey || 'category'
  const barKeys = valueKeys.length > 0 ? valueKeys : Object.keys(data[0] || {}).filter(
    k => !isTimeKey(k) && typeof data[0][k] === 'number'
  )

  return (
    <ResponsiveContainer width="100%" height="100%">
      <BarChart data={data} margin={{top: 4, right: 4, left: 0, bottom: 0}}>
        <CartesianGrid strokeDasharray="3 3" className="stroke-muted" />
        <XAxis dataKey={xKey} tick={{fontSize: 10}} />
        <YAxis tick={{fontSize: 10}} width={50} />
        <Tooltip
          contentStyle={{
            backgroundColor: 'hsl(var(--popover))',
            border: '1px solid hsl(var(--border))',
            borderRadius: '6px',
            fontSize: '11px',
          }}
        />
        <Legend wrapperStyle={{fontSize: '10px', paddingTop: '4px'}} iconSize={8} />
        {barKeys.map((key, i) => (
          <Bar key={key} dataKey={key} fill={COLORS[i % COLORS.length]} radius={[2, 2, 0, 0]} />
        ))}
      </BarChart>
    </ResponsiveContainer>
  )
}

function DonutChartWidget({data}: {data: Record<string, unknown>[]}) {
  const {labelKeys, valueKeys} = classifyColumns(data)
  const labelKey = labelKeys[0]
  const valueKey = valueKeys[0]

  if (!labelKey || !valueKey) return <div className="text-xs text-muted-foreground">Invalid data</div>

  return (
    <ResponsiveContainer width="100%" height="100%">
      <PieChart>
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
        <Tooltip />
        <Legend
          wrapperStyle={{fontSize: '11px'}}
          layout="horizontal"
          verticalAlign="bottom"
          iconSize={10}
        />
      </PieChart>
    </ResponsiveContainer>
  )
}

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

function StatWidget({data, widget}: {data: Record<string, unknown>[]; widget: DashboardWidget}) {
  const {labelKeys, valueKeys} = classifyColumns(data)

  if (labelKeys.length > 0 && valueKeys.length > 0 && data.length > 1) {
    const deduped = deduplicateStatData(data, labelKeys, valueKeys)
    const fontSize = deduped.length <= 5 ? 'text-2xl' : deduped.length <= 10 ? 'text-lg' : 'text-sm'
    return (
      <div className="h-full flex flex-wrap items-center justify-center gap-x-6 gap-y-2 p-2 overflow-auto">
        {deduped.map((row, i) => {
          const label = labelKeys.map(k => String(row[k] ?? '')).join(' ')
          const val = row[valueKeys[0]]
          return (
            <div key={i} className="text-center min-w-[48px]">
              <div className="text-xs text-muted-foreground truncate">{label}</div>
              <div className={`${fontSize} font-bold tabular-nums`}>
                {formatStatValue(val)}
              </div>
            </div>
          )
        })}
      </div>
    )
  }

  const row = data[data.length - 1] || data[0] || {}
  const displayKeys = valueKeys.length > 0 ? valueKeys : Object.keys(row).filter(
    k => typeof row[k] === 'number' && !isTimeKey(k)
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
}

function TableWidget({data}: {data: Record<string, unknown>[]}) {
  if (data.length === 0) return null
  const columns = Object.keys(data[0])

  return (
    <div className="h-full overflow-auto">
      <table className="w-full text-xs">
        <thead className="sticky top-0 bg-muted/50">
          <tr>
            {columns.map((col) => (
              <th key={col} className="text-left px-2 py-1.5 font-medium">
                {col.replace(/_/g, ' ')}
              </th>
            ))}
          </tr>
        </thead>
        <tbody>
          {data.map((row, i) => (
            <tr key={i} className="border-t border-muted/30">
              {columns.map((col) => (
                <td key={col} className="px-2 py-1 truncate max-w-[200px]">
                  {String(row[col] ?? '')}
                </td>
              ))}
            </tr>
          ))}
        </tbody>
      </table>
    </div>
  )
}
