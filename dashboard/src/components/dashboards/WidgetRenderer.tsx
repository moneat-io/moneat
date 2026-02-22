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
  AreaChart, Area, BarChart, Bar, PieChart, Pie, Cell, XAxis, YAxis,
  CartesianGrid, Tooltip, ResponsiveContainer,
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
]

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
      return <TimeseriesChart data={chartData} />
    case 'bar':
      return <BarChartWidget data={chartData} />
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

function TimeseriesChart({data}: {data: Record<string, unknown>[]}) {
  const keys = Object.keys(data[0] || {}).filter((k) => k !== 'time_bucket' && k !== 'timestamp')

  return (
    <ResponsiveContainer width="100%" height="100%">
      <AreaChart data={data} margin={{top: 4, right: 4, left: 0, bottom: 0}}>
        <CartesianGrid strokeDasharray="3 3" className="stroke-muted" />
        <XAxis
          dataKey="time_bucket"
          tick={{fontSize: 10}}
          tickFormatter={(v) => {
            const d = new Date(v)
            return `${d.getHours()}:${String(d.getMinutes()).padStart(2, '0')}`
          }}
        />
        <YAxis tick={{fontSize: 10}} width={40} />
        <Tooltip
          contentStyle={{
            backgroundColor: 'hsl(var(--popover))',
            border: '1px solid hsl(var(--border))',
            borderRadius: '6px',
            fontSize: '12px',
          }}
        />
        {keys.map((key, i) => (
          <Area
            key={key}
            type="monotone"
            dataKey={key}
            stroke={COLORS[i % COLORS.length]}
            fill={COLORS[i % COLORS.length]}
            fillOpacity={0.15}
            strokeWidth={1.5}
          />
        ))}
      </AreaChart>
    </ResponsiveContainer>
  )
}

function BarChartWidget({data}: {data: Record<string, unknown>[]}) {
  const keys = Object.keys(data[0] || {}).filter(
    (k) => k !== 'time_bucket' && k !== 'timestamp' && typeof data[0][k] === 'number'
  )
  const labelKey = Object.keys(data[0] || {}).find(
    (k) => typeof data[0][k] === 'string'
  ) || keys[0]

  return (
    <ResponsiveContainer width="100%" height="100%">
      <BarChart data={data} layout="vertical" margin={{top: 4, right: 4, left: 0, bottom: 0}}>
        <CartesianGrid strokeDasharray="3 3" className="stroke-muted" />
        <XAxis type="number" tick={{fontSize: 10}} />
        <YAxis dataKey={labelKey} type="category" tick={{fontSize: 10}} width={80} />
        <Tooltip
          contentStyle={{
            backgroundColor: 'hsl(var(--popover))',
            border: '1px solid hsl(var(--border))',
            borderRadius: '6px',
            fontSize: '12px',
          }}
        />
        {keys.map((key, i) => (
          <Bar key={key} dataKey={key} fill={COLORS[i % COLORS.length]} radius={[0, 4, 4, 0]} />
        ))}
      </BarChart>
    </ResponsiveContainer>
  )
}

function DonutChartWidget({data}: {data: Record<string, unknown>[]}) {
  const labelKey = Object.keys(data[0] || {}).find((k) => typeof data[0][k] === 'string')
  const valueKey = Object.keys(data[0] || {}).find((k) => typeof data[0][k] === 'number')

  if (!labelKey || !valueKey) return <div className="text-xs text-muted-foreground">Invalid data</div>

  return (
    <ResponsiveContainer width="100%" height="100%">
      <PieChart>
        <Pie
          data={data}
          dataKey={valueKey}
          nameKey={labelKey}
          cx="50%"
          cy="50%"
          innerRadius="45%"
          outerRadius="75%"
          paddingAngle={2}
          label={({name, percent}) => `${name} ${(percent * 100).toFixed(0)}%`}
          labelLine={false}
        >
          {data.map((_, i) => (
            <Cell key={i} fill={COLORS[i % COLORS.length]} />
          ))}
        </Pie>
        <Tooltip />
      </PieChart>
    </ResponsiveContainer>
  )
}

function StatWidget({data}: {data: Record<string, unknown>[]; widget: DashboardWidget}) {
  const row = data[0] || {}
  const numericKeys = Object.keys(row).filter((k) => typeof row[k] === 'number')

  return (
    <div className="h-full flex flex-col items-center justify-center gap-1">
      {numericKeys.map((key) => (
        <div key={key} className="text-center">
          <div className="text-2xl font-bold tabular-nums">
            {typeof row[key] === 'number'
              ? Number(row[key]).toLocaleString(undefined, {maximumFractionDigits: 2})
              : String(row[key])}
          </div>
          <div className="text-xs text-muted-foreground">{key.replace(/_/g, ' ')}</div>
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
