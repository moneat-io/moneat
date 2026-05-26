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

import {memo, type ReactNode} from 'react'
import ReactMarkdown from 'react-markdown'
import type {DashboardWidget} from '@/lib/api'
import {formatValue} from './formatValue'
import {extendedWidgetTestId} from './extendedWidgetTypes'

const COLORS = [
  'hsl(var(--chart-1))',
  'hsl(var(--chart-2))',
  'hsl(var(--chart-3))',
  'hsl(var(--chart-4))',
  'hsl(var(--chart-5))',
  '#8884d8',
  '#82ca9d',
  '#ffc658',
  '#ff7300',
  '#00C49F',
]
const TIME_KEYS = new Set(['time_bucket', 'timestamp', 'time', 'Time', 'day', 'Day'])
const NUMBER_FORMATTER = new Intl.NumberFormat(undefined, {maximumFractionDigits: 2})
const MARKDOWN_LINK_URL_REGEX = /\((https?:\/\/[^)]+)\)/

type DisplayConfig = Record<string, string>
type DataRow = Record<string, unknown>

interface ExtendedWidgetRendererProps {
  widget: DashboardWidget
  widgetType: string
  data: DataRow[]
  displayConfig: DisplayConfig
}

interface LabelValue {
  label: string
  value: number
  secondary?: string
}

interface FlowEdge {
  source: string
  target: string
  value: number
}

export const ExtendedWidgetRenderer = memo(function ExtendedWidgetRenderer({
  widget,
  widgetType,
  data,
  displayConfig,
}: ExtendedWidgetRendererProps) {
  switch (widgetType) {
    case 'stream':
      return <StreamWidget data={data} />
    case 'timeline':
      return <TimelineWidget data={data} />
    case 'geo_map':
      return <GeoMapWidget data={data} />
    case 'host_map':
      return <HostMapWidget data={data} />
    case 'topology_map':
      return <TopologyMapWidget data={data} />
    case 'sankey':
      return <SankeyWidget data={data} />
    case 'treemap':
      return <TreemapWidget data={data} />
    case 'scatter':
      return <ScatterWidget data={data} displayConfig={displayConfig} />
    case 'status':
      return <StatusWidget data={data} />
    case 'change':
      return <ChangeWidget data={data} widget={widget} displayConfig={displayConfig} />
    case 'custom':
      return <CustomWidget data={data} widget={widget} displayConfig={displayConfig} />
    case 'flame_graph':
      return <FlameGraphWidget data={data} />
    case 'cost_summary':
      return <CostSummaryWidget data={data} displayConfig={displayConfig} />
    case 'iframe':
      return <IframeWidget widget={widget} />
    default:
      return null
  }
})

function isTimeKey(key: string): boolean {
  return TIME_KEYS.has(key)
}

function compactNumber(value: number): string {
  return NUMBER_FORMATTER.format(value)
}

function toFiniteNumber(value: unknown): number | null {
  if (typeof value === 'number' && Number.isFinite(value)) return value
  if (typeof value !== 'string' || value.trim() === '') return null
  const parsed = Number(value)
  return Number.isFinite(parsed) ? parsed : null
}

function getFirstValueByKeys(row: DataRow, keys: string[]): unknown {
  for (const key of keys) {
    if (row[key] != null) return row[key]
  }
  return undefined
}

function getStringByKeys(row: DataRow, keys: string[]): string | null {
  const value = getFirstValueByKeys(row, keys)
  if (value == null || value === '') return null
  return String(value)
}

function getNumberByKeys(row: DataRow, keys: string[]): number | null {
  return toFiniteNumber(getFirstValueByKeys(row, keys))
}

function getStringEntries(row: DataRow): [string, string][] {
  return Object.entries(row)
    .filter(([, value]) => typeof value === 'string' && value.trim() !== '')
    .map(([key, value]) => [key, value as string])
}

function getNumberEntries(row: DataRow): [string, number][] {
  return Object.entries(row)
    .map(([key, value]) => [key, toFiniteNumber(value)] as [string, number | null])
    .filter((entry): entry is [string, number] => entry[1] != null && !isTimeKey(entry[0]))
}

function getPrimaryNumber(row: DataRow): number {
  return getNumberEntries(row)[0]?.[1] ?? 0
}

function getRowLabel(row: DataRow, index: number): string {
  return getStringByKeys(row, ['name', 'host', 'service', 'resource', 'title', 'message', 'path']) ??
    getStringEntries(row)[0]?.[1] ??
    `item ${index + 1}`
}

function getRowSecondary(row: DataRow): string | undefined {
  const fields = [
    getStringByKeys(row, ['service', 'env', 'environment']),
    getStringByKeys(row, ['host', 'pod_name', 'container_name']),
    getStringByKeys(row, ['status', 'level', 'state']),
  ].filter((value): value is string => value != null)
  return fields.length > 0 ? fields.join(' | ') : undefined
}

function parseUtcTimestamp(value: string): number {
  if (value.endsWith('Z') || /[+-]\d{2}:\d{2}$/.test(value)) return Date.parse(value)
  const iso = value.includes('T') ? value + 'Z' : value.replace(' ', 'T') + 'Z'
  const ms = Date.parse(iso)
  return Number.isNaN(ms) ? Date.parse(value) : ms
}

function formatTimeLabel(value: string | number): string {
  const timestamp = typeof value === 'number' ? value : parseUtcTimestamp(value)
  if (Number.isNaN(timestamp)) return String(value)
  const date = new Date(timestamp)
  const month = String(date.getUTCMonth() + 1).padStart(2, '0')
  const day = String(date.getUTCDate()).padStart(2, '0')
  const hours = String(date.getUTCHours()).padStart(2, '0')
  const mins = String(date.getUTCMinutes()).padStart(2, '0')
  return `${month}/${day} ${hours}:${mins}`
}

function getTimeLabel(row: DataRow): string | null {
  const entry = Object.entries(row).find(([key]) => isTimeKey(key))
  if (!entry) return null
  if (typeof entry[1] === 'number' || typeof entry[1] === 'string') return formatTimeLabel(entry[1])
  return String(entry[1])
}

function getTopLabelValues(data: DataRow[], limit: number): LabelValue[] {
  return data.slice(0, limit).map((row, index) => ({
    label: getRowLabel(row, index),
    value: getPrimaryNumber(row),
    secondary: getRowSecondary(row),
  }))
}

function maxAbs(values: number[]): number {
  let max = 0
  for (const value of values) {
    max = Math.max(max, Math.abs(value))
  }
  return max || 1
}

function ExtendedEmptyWidget({widgetType}: {widgetType: string}) {
  return (
    <div
      data-testid={extendedWidgetTestId(widgetType)}
      className="h-full flex items-center justify-center text-xs text-muted-foreground"
    >
      No data
    </div>
  )
}

function ExtendedWidgetShell({
  widgetType,
  children,
  className = '',
}: {
  widgetType: string
  children: ReactNode
  className?: string
}) {
  return (
    <div data-testid={extendedWidgetTestId(widgetType)} className={`h-full w-full overflow-hidden ${className}`}>
      {children}
    </div>
  )
}

const StreamWidget = memo(function StreamWidget({
  data,
}: {
  data: DataRow[]
}) {
  if (data.length === 0) return <ExtendedEmptyWidget widgetType="stream" />

  return (
    <ExtendedWidgetShell widgetType="stream" className="overflow-auto p-2">
      <div className="space-y-1">
        {data.slice(0, 80).map((row, index) => {
          const message = getStringByKeys(row, ['message', 'content', 'title', 'event', 'error', 'name']) ??
            getRowLabel(row, index)
          const status = getStringByKeys(row, ['level', 'status', 'severity', 'priority', 'state'])
          const secondary = getRowSecondary(row)
          const timeLabel = getTimeLabel(row)
          return (
            <div key={index} className="rounded border border-border/70 bg-background/70 px-2 py-1.5">
              <div className="flex items-center gap-2 text-[11px] text-muted-foreground">
                {status && <span className="rounded bg-muted px-1.5 py-0.5 font-medium uppercase">{status}</span>}
                {timeLabel && <span className="tabular-nums">{timeLabel}</span>}
                {secondary && <span className="min-w-0 truncate">{secondary}</span>}
              </div>
              <div className="mt-1 truncate text-xs font-medium">{message}</div>
            </div>
          )
        })}
      </div>
    </ExtendedWidgetShell>
  )
})

const TimelineWidget = memo(function TimelineWidget({data}: {data: DataRow[]}) {
  if (data.length === 0) return <ExtendedEmptyWidget widgetType="timeline" />

  return (
    <ExtendedWidgetShell widgetType="timeline" className="overflow-auto p-3">
      <div className="relative ml-2 space-y-3 border-l border-border">
        {data.slice(0, 60).map((row, index) => {
          const label = getRowLabel(row, index)
          const value = getPrimaryNumber(row)
          const timeLabel = getTimeLabel(row)
          return (
            <div key={index} className="relative pl-4">
              <span className="absolute -left-[5px] top-1 h-2.5 w-2.5 rounded-full bg-primary" />
              <div className="flex items-center justify-between gap-2 text-xs">
                <span className="min-w-0 truncate font-medium">{label}</span>
                {value !== 0 && <span className="shrink-0 tabular-nums">{compactNumber(value)}</span>}
              </div>
              {timeLabel && <div className="mt-0.5 text-[11px] text-muted-foreground">{timeLabel}</div>}
            </div>
          )
        })}
      </div>
    </ExtendedWidgetShell>
  )
})

function geoPoint(row: DataRow, index: number, total: number) {
  const lat = getNumberByKeys(row, ['lat', 'latitude', 'geo_latitude', 'location_latitude'])
  const lon = getNumberByKeys(row, ['lon', 'lng', 'longitude', 'geo_longitude', 'location_longitude'])
  if (lat != null && lon != null) {
    return {
      x: ((lon + 180) / 360) * 100,
      y: ((90 - lat) / 180) * 100,
    }
  }
  const angle = (index / Math.max(total, 1)) * Math.PI * 2
  const radius = 34 + (index % 3) * 8
  return {
    x: 50 + Math.cos(angle) * radius,
    y: 50 + Math.sin(angle) * radius * 0.55,
  }
}

const GeoMapWidget = memo(function GeoMapWidget({data}: {data: DataRow[]}) {
  if (data.length === 0) return <ExtendedEmptyWidget widgetType="geo_map" />

  const points = data.slice(0, 80).map((row, index) => ({
    ...geoPoint(row, index, data.length),
    label: getRowLabel(row, index),
    value: getPrimaryNumber(row),
  }))
  const maxValue = maxAbs(points.map((point) => point.value))

  return (
    <ExtendedWidgetShell widgetType="geo_map" className="p-2">
      <svg viewBox="0 0 100 100" className="h-full w-full rounded bg-muted/20" preserveAspectRatio="none">
        {[20, 40, 60, 80].map((line) => (
          <line key={line} x1={line} x2={line} y1="0" y2="100" stroke="currentColor" opacity="0.08" />
        ))}
        {[25, 50, 75].map((line) => (
          <line key={line} x1="0" x2="100" y1={line} y2={line} stroke="currentColor" opacity="0.08" />
        ))}
        {points.map((point, index) => (
          <circle
            key={index}
            cx={point.x}
            cy={point.y}
            r={1.8 + Math.min(7, Math.abs(point.value) / maxValue * 7)}
            fill={COLORS[index % COLORS.length]}
            opacity="0.78"
          >
            <title>{`${point.label}: ${compactNumber(point.value)}`}</title>
          </circle>
        ))}
      </svg>
    </ExtendedWidgetShell>
  )
})

const HostMapWidget = memo(function HostMapWidget({data}: {data: DataRow[]}) {
  if (data.length === 0) return <ExtendedEmptyWidget widgetType="host_map" />
  const hosts = getTopLabelValues(data, 120)
  const maxValue = maxAbs(hosts.map((host) => host.value))

  return (
    <ExtendedWidgetShell widgetType="host_map" className="overflow-auto p-2">
      <div className="grid grid-cols-[repeat(auto-fill,minmax(72px,1fr))] gap-1.5">
        {hosts.map((host, index) => (
          <div
            key={`${host.label}-${index}`}
            className="min-h-14 rounded border border-border/70 p-1.5"
            style={{
              backgroundColor: COLORS[index % COLORS.length],
              opacity: 0.22 + Math.min(1, Math.abs(host.value) / maxValue) * 0.68,
            }}
            title={`${host.label}: ${compactNumber(host.value)}`}
          >
            <div className="truncate text-[11px] font-medium text-foreground">{host.label}</div>
            <div className="mt-1 text-xs font-semibold tabular-nums text-foreground">
              {compactNumber(host.value)}
            </div>
          </div>
        ))}
      </div>
    </ExtendedWidgetShell>
  )
})

const TopologyMapWidget = memo(function TopologyMapWidget({data}: {data: DataRow[]}) {
  if (data.length === 0) return <ExtendedEmptyWidget widgetType="topology_map" />
  const nodes = getTopLabelValues(data, 18)
  const points = nodes.map((node, index) => {
    const angle = (index / Math.max(nodes.length, 1)) * Math.PI * 2
    return {...node, x: 50 + Math.cos(angle) * 34, y: 50 + Math.sin(angle) * 28}
  })

  return (
    <ExtendedWidgetShell widgetType="topology_map" className="p-2">
      <svg viewBox="0 0 100 100" className="h-full w-full">
        {points.map((point, index) => {
          const next = points[(index + 1) % points.length]
          if (!next || points.length < 2) return null
          return <line key={`${point.label}-${next.label}`} x1={point.x} y1={point.y} x2={next.x} y2={next.y} stroke="currentColor" strokeOpacity="0.18" />
        })}
        {points.map((point, index) => (
          <g key={`${point.label}-${index}`}>
            <circle cx={point.x} cy={point.y} r="7" fill={COLORS[index % COLORS.length]} opacity="0.82" />
            <text x={point.x} y={point.y + 12} textAnchor="middle" className="fill-muted-foreground text-[4px]">
              {point.label.slice(0, 14)}
            </text>
          </g>
        ))}
      </svg>
    </ExtendedWidgetShell>
  )
})

function buildFlowEdges(data: DataRow[], limit: number): FlowEdge[] {
  return data.slice(0, limit).map((row, index) => {
    const stringEntries = getStringEntries(row)
    const source = getStringByKeys(row, ['source', 'from', 'parent', 'service']) ??
      stringEntries[0]?.[1] ??
      `source ${index + 1}`
    const target = getStringByKeys(row, ['target', 'to', 'child', 'resource', 'operation']) ??
      stringEntries.find(([, value]) => value !== source)?.[1] ??
      `target ${index + 1}`
    return {source, target, value: getPrimaryNumber(row)}
  })
}

const SankeyWidget = memo(function SankeyWidget({data}: {data: DataRow[]}) {
  if (data.length === 0) return <ExtendedEmptyWidget widgetType="sankey" />
  const edges = buildFlowEdges(data, 40)
  const maxValue = maxAbs(edges.map((edge) => edge.value))

  return (
    <ExtendedWidgetShell widgetType="sankey" className="overflow-auto p-2">
      <div className="space-y-2">
        {edges.map((edge, index) => (
          <div key={`${edge.source}-${edge.target}-${index}`} className="grid grid-cols-[1fr_2fr_1fr] items-center gap-2">
            <div className="truncate text-right text-[11px] text-muted-foreground">{edge.source}</div>
            <div className="relative h-5 rounded bg-muted/30">
              <div
                className="h-full rounded"
                style={{
                  width: `${Math.max(8, Math.abs(edge.value) / maxValue * 100)}%`,
                  backgroundColor: COLORS[index % COLORS.length],
                }}
              />
              <div className="absolute inset-0 flex items-center justify-center text-[11px] font-medium tabular-nums">
                {compactNumber(edge.value)}
              </div>
            </div>
            <div className="truncate text-[11px] text-muted-foreground">{edge.target}</div>
          </div>
        ))}
      </div>
    </ExtendedWidgetShell>
  )
})

const TreemapWidget = memo(function TreemapWidget({data}: {data: DataRow[]}) {
  if (data.length === 0) return <ExtendedEmptyWidget widgetType="treemap" />
  const items = getTopLabelValues(data, 60)
  const total = items.reduce((sum, item) => sum + Math.abs(item.value), 0) || 1

  return (
    <ExtendedWidgetShell widgetType="treemap" className="overflow-auto p-2">
      <div className="flex min-h-full flex-wrap content-stretch gap-1">
        {items.map((item, index) => (
          <div
            key={`${item.label}-${index}`}
            className="flex min-h-14 flex-col justify-between rounded p-2 text-primary-foreground"
            style={{
              flexBasis: `${Math.max(18, Math.abs(item.value) / total * 100)}%`,
              flexGrow: Math.max(1, Math.abs(item.value)),
              backgroundColor: COLORS[index % COLORS.length],
            }}
          >
            <div className="truncate text-xs font-medium">{item.label}</div>
            <div className="text-sm font-semibold tabular-nums">{compactNumber(item.value)}</div>
          </div>
        ))}
      </div>
    </ExtendedWidgetShell>
  )
})

const ScatterWidget = memo(function ScatterWidget({
  data,
  displayConfig,
}: {
  data: DataRow[]
  displayConfig: DisplayConfig
}) {
  if (data.length === 0) return <ExtendedEmptyWidget widgetType="scatter" />
  const firstRow = data[0] ?? {}
  const numericKeys = getNumberEntries(firstRow).map(([key]) => key)
  const maxValue = maxAbs(data.map(getPrimaryNumber))

  return (
    <ExtendedWidgetShell widgetType="scatter" className="p-2">
      <svg viewBox="0 0 100 100" className="h-full w-full">
        <line x1="8" x2="8" y1="6" y2="92" stroke="currentColor" opacity="0.22" />
        <line x1="8" x2="96" y1="92" y2="92" stroke="currentColor" opacity="0.22" />
        {data.slice(0, 120).map((row, index) => {
          const x = numericKeys[0] ? toFiniteNumber(row[numericKeys[0]]) ?? index : index
          const y = numericKeys[1] ? toFiniteNumber(row[numericKeys[1]]) ?? getPrimaryNumber(row) : getPrimaryNumber(row)
          const cx = 10 + Math.min(86, Math.abs(x) / maxValue * 86)
          const cy = 90 - Math.min(82, Math.abs(y) / maxValue * 82)
          return (
            <circle key={index} cx={cx} cy={cy} r="2.5" fill={COLORS[index % COLORS.length]}>
              <title>{`${getRowLabel(row, index)}: ${formatValue(y, displayConfig.unit, displayConfig.decimals)}`}</title>
            </circle>
          )
        })}
      </svg>
    </ExtendedWidgetShell>
  )
})

function statusColor(status: string | null, value: number): string {
  const normalized = status?.toLowerCase() ?? ''
  if (normalized.includes('ok') || normalized.includes('success') || normalized.includes('up')) return '#22c55e'
  if (normalized.includes('warn')) return '#f59e0b'
  if (normalized.includes('error') || normalized.includes('critical') || normalized.includes('down')) return '#ef4444'
  if (value > 0) return '#22c55e'
  return 'hsl(var(--muted-foreground))'
}

const StatusWidget = memo(function StatusWidget({
  data,
}: {
  data: DataRow[]
}) {
  if (data.length === 0) return <ExtendedEmptyWidget widgetType="status" />
  const statuses = data.slice(0, 48).map((row, index) => {
    const status = getStringByKeys(row, ['status', 'state', 'level'])
    return {label: getRowLabel(row, index), value: getPrimaryNumber(row), status, secondary: getRowSecondary(row)}
  })

  return (
    <ExtendedWidgetShell widgetType="status" className="overflow-auto p-2">
      <div className="grid grid-cols-[repeat(auto-fill,minmax(140px,1fr))] gap-2">
        {statuses.map((item, index) => {
          const color = statusColor(item.status, item.value)
          return (
            <div key={`${item.label}-${index}`} className="rounded border border-border/70 p-2">
              <div className="flex items-center gap-2">
                <span className="h-2.5 w-2.5 shrink-0 rounded-full" style={{backgroundColor: color}} />
                <span className="min-w-0 truncate text-xs font-medium">{item.label}</span>
              </div>
              <div className="mt-1 flex items-center justify-between gap-2">
                <span className="truncate text-[11px] text-muted-foreground">
                  {item.status ?? item.secondary ?? 'status'}
                </span>
                <span className="shrink-0 text-xs font-semibold tabular-nums" style={{color}}>
                  {compactNumber(item.value)}
                </span>
              </div>
            </div>
          )
        })}
      </div>
    </ExtendedWidgetShell>
  )
})

function getTimeSortValue(row: DataRow): number {
  const entry = Object.entries(row).find(([key]) => isTimeKey(key))
  if (!entry) return 0
  if (typeof entry[1] === 'number') return entry[1]
  if (typeof entry[1] === 'string') {
    const parsed = parseUtcTimestamp(entry[1])
    return Number.isNaN(parsed) ? 0 : parsed
  }
  return 0
}

const ChangeWidget = memo(function ChangeWidget({
  data,
  widget,
  displayConfig,
}: {
  data: DataRow[]
  widget: DashboardWidget
  displayConfig: DisplayConfig
}) {
  if (data.length === 0) return <ExtendedEmptyWidget widgetType="change" />
  const sorted = [...data].sort((a, b) => getTimeSortValue(a) - getTimeSortValue(b))
  const first = getPrimaryNumber(sorted[0] ?? {})
  const last = getPrimaryNumber(sorted[sorted.length - 1] ?? {})
  const delta = last - first
  const pct = first === 0 ? null : delta / Math.abs(first) * 100
  const formattedLast = formatValue(last, displayConfig.unit, displayConfig.decimals)
  const formattedDelta = formatValue(delta, displayConfig.unit, displayConfig.decimals)

  return (
    <ExtendedWidgetShell widgetType="change" className="flex flex-col items-center justify-center gap-2 p-3">
      <div className="max-w-full truncate text-xs text-muted-foreground">{widget.title ?? 'change'}</div>
      <div className="text-3xl font-bold tabular-nums">{formattedLast}</div>
      <div className="flex items-center gap-2 text-sm font-medium tabular-nums" style={{color: delta >= 0 ? '#22c55e' : '#ef4444'}}>
        <span>{delta >= 0 ? '+' : ''}{formattedDelta}</span>
        {pct != null && <span>({delta >= 0 ? '+' : ''}{pct.toFixed(1)}%)</span>}
      </div>
    </ExtendedWidgetShell>
  )
})

function extractIframeUrl(displayConfig: DisplayConfig): string | null {
  const configuredUrl = displayConfig.iframe_url || displayConfig.image_url
  if (configuredUrl) return configuredUrl
  const content = displayConfig.content
  if (!content) return null
  return MARKDOWN_LINK_URL_REGEX.exec(content)?.[1] ?? null
}

function isSafeHttpUrl(value: string): boolean {
  try {
    const url = new URL(value)
    return url.protocol === 'http:' || url.protocol === 'https:'
  } catch {
    return false
  }
}

const IframeWidget = memo(function IframeWidget({widget}: {widget: DashboardWidget}) {
  const url = extractIframeUrl(widget.display_config || {})
  if (!url || !isSafeHttpUrl(url)) return <ExtendedEmptyWidget widgetType="iframe" />

  return (
    <ExtendedWidgetShell widgetType="iframe">
      <iframe
        title={widget.title ?? 'Embedded content'}
        src={url}
        className="h-full w-full border-0"
        sandbox="allow-forms allow-popups allow-same-origin allow-scripts"
      />
    </ExtendedWidgetShell>
  )
})

const CustomWidget = memo(function CustomWidget({
  data,
  widget,
  displayConfig,
}: {
  data: DataRow[]
  widget: DashboardWidget
  displayConfig: DisplayConfig
}) {
  const content = displayConfig.content || widget.title || ''
  if (content) {
    return (
      <ExtendedWidgetShell widgetType="custom" className="prose prose-sm dark:prose-invert max-w-none overflow-auto p-2">
        <ReactMarkdown>{content}</ReactMarkdown>
      </ExtendedWidgetShell>
    )
  }
  if (data.length === 0) return <ExtendedEmptyWidget widgetType="custom" />
  return (
    <ExtendedWidgetShell widgetType="custom">
      <TablePreview data={data} displayConfig={displayConfig} />
    </ExtendedWidgetShell>
  )
})

function TablePreview({data, displayConfig}: {data: DataRow[]; displayConfig: DisplayConfig}) {
  const columns = Object.keys(data[0] ?? {}).slice(0, 8)
  return (
    <div className="h-full overflow-auto">
      <table className="w-full text-xs">
        <thead className="sticky top-0 bg-background">
          <tr>{columns.map((column) => <th key={column} className="px-2 py-1 text-left">{column}</th>)}</tr>
        </thead>
        <tbody>
          {data.slice(0, 100).map((row, index) => (
            <tr key={index} className="border-t border-muted/30">
              {columns.map((column) => {
                const value = row[column]
                return (
                  <td key={column} className="max-w-[240px] truncate px-2 py-1">
                    {typeof value === 'number' ? formatValue(value, displayConfig.unit, displayConfig.decimals) : String(value ?? '')}
                  </td>
                )
              })}
            </tr>
          ))}
        </tbody>
      </table>
    </div>
  )
}

const FlameGraphWidget = memo(function FlameGraphWidget({data}: {data: DataRow[]}) {
  if (data.length === 0) return <ExtendedEmptyWidget widgetType="flame_graph" />
  const frames = data.slice(0, 80).map((row, index) => ({
    label: getStringByKeys(row, ['function', 'frame', 'method', 'resource', 'name']) ?? getRowLabel(row, index),
    value: Math.abs(getPrimaryNumber(row)) || 1,
    depth: Math.max(0, getNumberByKeys(row, ['depth', 'level']) ?? index % 5),
  }))
  const maxValue = maxAbs(frames.map((frame) => frame.value))

  return (
    <ExtendedWidgetShell widgetType="flame_graph" className="overflow-auto p-2">
      <div className="space-y-1">
        {frames.map((frame, index) => (
          <div key={`${frame.label}-${index}`} className="flex items-center gap-2">
            <div style={{width: `${frame.depth * 14}px`}} className="shrink-0" />
            <div className="relative h-5 min-w-0 flex-1 rounded bg-muted/30">
              <div
                className="h-full rounded"
                style={{
                  width: `${Math.max(6, frame.value / maxValue * 100)}%`,
                  backgroundColor: COLORS[index % COLORS.length],
                }}
              />
              <div className="absolute inset-0 truncate px-2 text-[11px] leading-5">{frame.label}</div>
            </div>
            <div className="w-16 shrink-0 text-right text-[11px] tabular-nums text-muted-foreground">
              {compactNumber(frame.value)}
            </div>
          </div>
        ))}
      </div>
    </ExtendedWidgetShell>
  )
})

const CostSummaryWidget = memo(function CostSummaryWidget({
  data,
  displayConfig,
}: {
  data: DataRow[]
  displayConfig: DisplayConfig
}) {
  if (data.length === 0) return <ExtendedEmptyWidget widgetType="cost_summary" />
  const items = getTopLabelValues(data, 12)
  const total = items.reduce((sum, item) => sum + item.value, 0)
  const formatCost = (value: number) => formatValue(value, displayConfig.unit, displayConfig.decimals)

  return (
    <ExtendedWidgetShell widgetType="cost_summary" className="overflow-auto p-3">
      <div className="mb-3">
        <div className="text-[11px] text-muted-foreground">Total</div>
        <div className="text-2xl font-bold tabular-nums">{formatCost(total)}</div>
      </div>
      <div className="grid grid-cols-[repeat(auto-fill,minmax(140px,1fr))] gap-2">
        {items.map((item, index) => (
          <div key={`${item.label}-${index}`} className="rounded border border-border/70 p-2">
            <div className="truncate text-xs font-medium">{item.label}</div>
            <div className="mt-1 text-sm font-semibold tabular-nums">{formatCost(item.value)}</div>
            {item.secondary && <div className="mt-1 truncate text-[11px] text-muted-foreground">{item.secondary}</div>}
          </div>
        ))}
      </div>
    </ExtendedWidgetShell>
  )
})
