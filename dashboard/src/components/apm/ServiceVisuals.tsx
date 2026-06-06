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

import type {ReactNode} from 'react'
import {Clock, Info} from 'lucide-react'
import {
  Area,
  AreaChart,
  CartesianGrid,
  Line,
  LineChart,
  ReferenceArea,
  ReferenceLine,
  ResponsiveContainer,
  Tooltip,
  XAxis,
  YAxis,
} from 'recharts'

import {Badge} from '@/components/ui/badge'
import {Select, SelectContent, SelectItem, SelectTrigger, SelectValue} from '@/components/ui/select'
import {StatCard} from '@/components/ui/stat-card'
import type {StatusTone} from '@/components/ui/status-dot'
import type {ApmTimeRange} from '@/lib/api'
import {cn} from '@/lib/utils'
import {
  APM_TIME_RANGE_OPTIONS,
  apdexHeatClass,
  errorSeverityBorder,
  errorSeverityTone,
  severityFill,
  serviceTypeLabel,
  statusBadgeVariant,
  statusLabel,
  type DistBar,
  type DistMarker,
  type EnvPill,
  type ErrorBar,
  type ErrorRow,
  type GaugeRow,
  type KpiSpec,
  type LatencyPoint,
  type ServiceStatus,
  type ServiceType,
  type Severity,
  type ThroughputPoint,
  type WaterfallRow,
  type WaterfallTone,
} from '@/lib/apm-view'

// ---------------------------------------------------------------------------
// Time range selector (shared across catalog / service / resource pages)
// ---------------------------------------------------------------------------

/** Controlled APM time-range selector wired to the `timeRange` query param. */
export function ApmTimeRangeSelect({
  value,
  onChange,
  className,
}: Readonly<{
  value: ApmTimeRange
  onChange: (value: ApmTimeRange) => void
  className?: string
}>) {
  return (
    <Select value={value} onValueChange={(next) => onChange(next as ApmTimeRange)}>
      <SelectTrigger className={cn('h-[30px] w-[148px] gap-2 text-xs', className)} aria-label="Time range">
        <Clock className="h-3.5 w-3.5 text-muted-foreground" />
        <SelectValue />
      </SelectTrigger>
      <SelectContent>
        {APM_TIME_RANGE_OPTIONS.map((option) => (
          <SelectItem key={option.value} value={option.value}>
            {option.label}
          </SelectItem>
        ))}
      </SelectContent>
    </Select>
  )
}

// ---------------------------------------------------------------------------
// Small chips / badges
// ---------------------------------------------------------------------------

/** Neutral uppercase glyph chip — keeps status the only color in a row. */
export function TypeChip({type, className}: Readonly<{type: ServiceType; className?: string}>) {
  return (
    <span
      className={cn(
        'inline-flex h-5 items-center rounded-sm border bg-muted px-1.5',
        'text-[10px] font-medium uppercase tracking-wide text-muted-foreground',
        className,
      )}
    >
      {serviceTypeLabel(type)}
    </span>
  )
}

export function ServiceStatusBadge({status}: Readonly<{status: ServiceStatus}>) {
  return (
    <Badge variant={statusBadgeVariant(status)} size="sm" className="capitalize">
      {statusLabel(status)}
    </Badge>
  )
}

const CHART_PILL_BG: Record<number, string> = {
  1: 'bg-chart-1', 2: 'bg-chart-2', 3: 'bg-chart-3', 4: 'bg-chart-4', 5: 'bg-chart-5',
  6: 'bg-chart-6', 7: 'bg-chart-7', 8: 'bg-chart-8', 9: 'bg-chart-9', 10: 'bg-chart-10',
}

export function EnvPills({pills}: Readonly<{pills: readonly EnvPill[]}>) {
  return (
    <span className="flex flex-wrap gap-1">
      {pills.map((pill) => (
        <span
          key={pill.label}
          className={cn(
            'inline-flex h-[18px] items-center rounded px-1.5 text-[10px] font-medium text-white',
            CHART_PILL_BG[pill.chart] ?? 'bg-muted-foreground',
          )}
        >
          {pill.label}
        </span>
      ))}
    </span>
  )
}

export function MetaChip({children}: Readonly<{children: ReactNode}>) {
  return (
    <span className="inline-flex items-center gap-1 rounded border bg-muted px-1.5 py-0.5 text-[10px] text-muted-foreground">
      {children}
    </span>
  )
}

/** Soft info callout used under dependency / span-breakdown panels. */
export function InfoCallout({children}: Readonly<{children: ReactNode}>) {
  return (
    <div className="flex items-start gap-2 rounded-lg border border-info-border bg-info-bg/50 px-3 py-2.5 text-sm">
      <Info className="mt-0.5 h-4 w-4 shrink-0 text-info-fg" />
      <span>{children}</span>
    </div>
  )
}

// ---------------------------------------------------------------------------
// Inline meters
// ---------------------------------------------------------------------------

const METER_FILL: Record<Severity, string> = {
  good: 'bg-primary',
  warn: 'bg-warning-solid',
  bad: 'bg-danger-solid',
}

/** Dense in-cell meter: a thin track + fill with a trailing mono value. */
export function MeterBar({
  pct,
  level,
  value,
  className,
}: Readonly<{
  pct: number
  level: Severity
  value: string
  className?: string
}>) {
  return (
    <div className={cn('flex items-center justify-end gap-2', className)}>
      <span className="relative h-1.5 flex-1 overflow-hidden rounded-full bg-muted">
        <span
          className={cn('absolute inset-y-0 left-0 rounded-full', METER_FILL[level])}
          style={{width: `${Math.max(2, Math.min(100, pct))}%`}}
        />
      </span>
      <span className="w-10 shrink-0 text-right font-mono text-xs tabular-nums">{value}</span>
    </div>
  )
}

export function ApdexHeat({value, tone}: Readonly<{value: string; tone: StatusTone}>) {
  return (
    <span className={cn('inline-block rounded px-2 py-0.5 font-mono text-xs tabular-nums', apdexHeatClass(tone))}>
      {value}
    </span>
  )
}

/** Horizontal label / track / value gauge rows (p95 by resource, pod memory…). */
export function BarGauge({rows}: Readonly<{rows: readonly GaugeRow[]}>) {
  return (
    <div className="flex flex-col gap-2">
      {rows.map((row) => (
        <div
          key={row.label}
          className="grid grid-cols-[minmax(96px,160px)_1fr_56px] items-center gap-3 text-sm"
        >
          <span className="truncate font-mono text-xs text-muted-foreground">{row.label}</span>
          <span className="h-3.5 overflow-hidden rounded-sm bg-muted">
            <span
              className={cn('block h-full rounded-sm', severityFill(row.level))}
              style={{width: `${Math.max(2, Math.min(100, row.pct))}%`}}
            />
          </span>
          <span className="text-right font-mono text-xs tabular-nums">{row.valueText}</span>
        </div>
      ))}
    </div>
  )
}

// ---------------------------------------------------------------------------
// Sparkline
// ---------------------------------------------------------------------------

export function Sparkline({values, className}: Readonly<{values: readonly number[]; className?: string}>) {
  const width = 88
  const height = 22
  const points = values
    .map((value, index) => {
      const x = values.length === 1 ? 0 : (index / (values.length - 1)) * width
      return `${x.toFixed(1)},${value.toFixed(1)}`
    })
    .join(' ')
  return (
    <svg
      viewBox={`0 0 ${width} ${height}`}
      width={width}
      height={height}
      preserveAspectRatio="none"
      className={cn('shrink-0', className)}
      aria-hidden="true"
    >
      <polyline points={points} fill="none" stroke="hsl(var(--chart-1))" strokeWidth={1.5} />
    </svg>
  )
}

// ---------------------------------------------------------------------------
// KPI row
// ---------------------------------------------------------------------------

const TONE_TEXT: Record<StatusTone, string> = {
  success: 'text-success-fg',
  warning: 'text-warning-fg',
  danger: 'text-danger-fg',
  info: 'text-info-fg',
  neutral: 'text-muted-foreground',
  accent: 'text-primary',
}

export function ApmKpiRow({kpis}: Readonly<{kpis: readonly KpiSpec[]}>) {
  return (
    <div className="grid grid-cols-2 gap-3 lg:grid-cols-4">
      {kpis.map((kpi) => (
        <StatCard
          key={kpi.label}
          label={kpi.label}
          value={
            kpi.valueTone ? <span className={TONE_TEXT[kpi.valueTone]}>{kpi.value}</span> : kpi.value
          }
          delta={kpi.delta}
        />
      ))}
    </div>
  )
}

// ---------------------------------------------------------------------------
// Charts (recharts)
// ---------------------------------------------------------------------------

const tooltipContentStyle = {
  fontSize: 12,
  borderRadius: 8,
  border: '1px solid hsl(var(--border))',
  background: 'hsl(var(--popover))',
  color: 'hsl(var(--popover-foreground))',
} as const

const LATENCY_LINES: ReadonlyArray<{key: keyof Omit<LatencyPoint, 't'>; color: string}> = [
  {key: 'p99', color: 'hsl(var(--chart-6))'},
  {key: 'p95', color: 'hsl(var(--chart-1))'},
  {key: 'p90', color: 'hsl(var(--chart-3))'},
  {key: 'p50', color: 'hsl(var(--chart-4))'},
]

export function LatencyPercentilesChart({
  series,
  thresholdMs,
  thresholdLabel,
  deployAt,
}: Readonly<{
  series: readonly LatencyPoint[]
  thresholdMs: number
  thresholdLabel: string
  deployAt: string
}>) {
  const maxY = Math.max(...series.map((point) => point.p99))
  const top = Math.ceil((maxY * 1.05) / 10) * 10
  return (
    <div className="h-[200px] w-full">
      <ResponsiveContainer width="100%" height="100%" minWidth={0}>
        <LineChart data={series as LatencyPoint[]} margin={{top: 12, right: 10, left: 0, bottom: 0}}>
          <CartesianGrid strokeDasharray="3 3" vertical={false} stroke="hsl(var(--border))" />
          <XAxis dataKey="t" tick={{fontSize: 10}} axisLine={false} tickLine={false} interval={2} minTickGap={12} />
          <YAxis hide domain={[0, top]} />
          <ReferenceArea y1={thresholdMs} y2={top} fill="hsl(var(--danger-solid))" fillOpacity={0.07} />
          <ReferenceLine
            y={thresholdMs}
            stroke="hsl(var(--danger-solid))"
            strokeDasharray="4 4"
            strokeOpacity={0.7}
            label={{value: thresholdLabel, position: 'insideTopLeft', fontSize: 10, fill: 'hsl(var(--danger-fg))'}}
          />
          <ReferenceLine
            x={deployAt}
            stroke="hsl(var(--primary))"
            strokeDasharray="3 3"
            strokeOpacity={0.8}
            label={{value: 'deploy', position: 'top', fontSize: 10, fill: 'hsl(var(--primary))'}}
          />
          <Tooltip
            contentStyle={tooltipContentStyle}
            formatter={(value, name) => [`${String(value)}ms`, String(name)]}
          />
          {LATENCY_LINES.map((line) => (
            <Line
              key={line.key}
              type="monotone"
              dataKey={line.key}
              stroke={line.color}
              strokeWidth={2}
              dot={false}
              isAnimationActive={false}
            />
          ))}
        </LineChart>
      </ResponsiveContainer>
    </div>
  )
}

const LEGEND_ROWS: ReadonlyArray<{key: keyof Omit<LatencyPoint, 't'>; color: string}> = [
  {key: 'p99', color: 'hsl(var(--chart-6))'},
  {key: 'p95', color: 'hsl(var(--chart-1))'},
  {key: 'p90', color: 'hsl(var(--chart-3))'},
  {key: 'p50', color: 'hsl(var(--chart-4))'},
]

export function LatencyLegendTable({series}: Readonly<{series: readonly LatencyPoint[]}>) {
  return (
    <table className="mt-3 w-full border-collapse font-mono text-xs tabular-nums">
      <thead>
        <tr className="text-muted-foreground">
          <th className="border-b py-1 pl-0 pr-2 text-left font-sans font-medium">Percentile</th>
          <th className="border-b px-2 py-1 text-right font-sans font-medium">Min</th>
          <th className="border-b px-2 py-1 text-right font-sans font-medium">Max</th>
          <th className="border-b px-2 py-1 text-right font-sans font-medium">Last</th>
        </tr>
      </thead>
      <tbody>
        {LEGEND_ROWS.map((row) => {
          const values = series.map((point) => point[row.key])
          const last = values.at(-1) ?? 0
          return (
            <tr key={row.key}>
              <td className="border-b py-1 pl-0 pr-2 text-left font-sans">
                <span className="mr-2 inline-block h-2.5 w-2.5 rounded-[2px] align-middle" style={{background: row.color}} />
                {row.key}
              </td>
              <td className="border-b px-2 py-1 text-right">{Math.min(...values)}</td>
              <td className="border-b px-2 py-1 text-right">{Math.max(...values)}</td>
              <td className="border-b px-2 py-1 text-right">{last}</td>
            </tr>
          )
        })}
      </tbody>
    </table>
  )
}

export function ThroughputChart({
  points,
  deployAt,
  withErrors = false,
}: Readonly<{
  points: readonly ThroughputPoint[]
  deployAt: string
  withErrors?: boolean
}>) {
  return (
    <div className="h-[200px] w-full">
      <ResponsiveContainer width="100%" height="100%" minWidth={0}>
        <AreaChart data={points as ThroughputPoint[]} margin={{top: 12, right: 10, left: 0, bottom: 0}}>
          <defs>
            <linearGradient id="apm-throughput" x1="0" x2="0" y1="0" y2="1">
              <stop offset="0" stopColor="hsl(var(--chart-1))" stopOpacity={0.28} />
              <stop offset="1" stopColor="hsl(var(--chart-1))" stopOpacity={0} />
            </linearGradient>
          </defs>
          <CartesianGrid strokeDasharray="3 3" vertical={false} stroke="hsl(var(--border))" />
          <XAxis dataKey="t" tick={{fontSize: 10}} axisLine={false} tickLine={false} interval={2} minTickGap={12} />
          <YAxis hide />
          <ReferenceLine
            x={deployAt}
            stroke="hsl(var(--primary))"
            strokeDasharray="3 3"
            strokeOpacity={0.8}
            label={{value: 'deploy', position: 'top', fontSize: 10, fill: 'hsl(var(--primary))'}}
          />
          <Tooltip contentStyle={tooltipContentStyle} />
          <Area
            type="monotone"
            dataKey="rps"
            stroke="hsl(var(--chart-1))"
            strokeWidth={2}
            fill="url(#apm-throughput)"
            isAnimationActive={false}
          />
          {withErrors && (
            <Area
              type="monotone"
              dataKey="errors"
              stroke="hsl(var(--danger-solid))"
              strokeWidth={2}
              fill="transparent"
              isAnimationActive={false}
            />
          )}
        </AreaChart>
      </ResponsiveContainer>
    </div>
  )
}

// ---------------------------------------------------------------------------
// Bar histograms (errors/min, latency distribution)
// ---------------------------------------------------------------------------

export function ErrorBars({bars}: Readonly<{bars: readonly ErrorBar[]}>) {
  return (
    <div className="flex h-[120px] items-end gap-1">
      {bars.map((bar) => (
        <span
          key={`${bar.level}-${bar.h}`}
          className={cn(
            'min-h-[4px] flex-1 rounded-t-sm',
            bar.level === 'bad' ? 'bg-danger-solid' : 'bg-warning-solid',
          )}
          style={{height: `${bar.h}%`}}
        />
      ))}
    </div>
  )
}

const DIST_BAND_BG: Record<Severity, string> = {
  good: 'bg-chart-1',
  warn: 'bg-warning-solid',
  bad: 'bg-danger-solid',
}

export function LatencyDistribution({
  bars,
  markers,
  axis,
}: Readonly<{
  bars: readonly DistBar[]
  markers: readonly DistMarker[]
  axis: readonly string[]
}>) {
  return (
    <div className="relative">
      <div className="pointer-events-none absolute inset-x-0 bottom-[18px] top-0">
        {markers.map((marker) => (
          <div
            key={marker.label}
            className={cn(
              'absolute bottom-0 top-0 border-l border-dashed',
              marker.p99 ? 'border-danger-solid' : 'border-primary',
            )}
            style={{left: `${marker.left}%`}}
          >
            <span
              className={cn(
                'absolute left-1 top-0 bg-card px-1 font-mono text-[10px]',
                marker.p99 ? 'text-danger-fg' : 'text-primary',
              )}
            >
              {marker.label}
            </span>
          </div>
        ))}
      </div>
      <div className="flex h-[130px] items-end gap-1">
        {bars.map((bar) => (
          <span
            key={`${bar.band}-${bar.h}`}
            className={cn('min-h-[4px] flex-1 rounded-t-sm', DIST_BAND_BG[bar.band])}
            style={{height: `${bar.h}%`}}
          />
        ))}
      </div>
      <div className="mt-1.5 flex justify-between font-mono text-[10px] text-muted-foreground">
        {axis.map((label) => (
          <span key={label}>{label}</span>
        ))}
      </div>
    </div>
  )
}

// ---------------------------------------------------------------------------
// Waterfall
// ---------------------------------------------------------------------------

const WATERFALL_BG: Record<WaterfallTone, string> = {
  root: 'bg-chart-1',
  app: 'bg-chart-2',
  db: 'bg-chart-3',
  cache: 'bg-chart-4',
  http: 'bg-chart-6',
  error: 'bg-danger-solid',
}

export function Waterfall({rows}: Readonly<{rows: readonly WaterfallRow[]}>) {
  return (
    <div className="overflow-hidden rounded-lg border text-xs">
      {rows.map((row) => (
        <div
          key={`${row.op}-${row.desc}-${row.left}-${row.width}-${row.label}`}
          className={cn(
            'grid grid-cols-[180px_minmax(0,1fr)] items-center border-b last:border-b-0 sm:grid-cols-[260px_minmax(0,1fr)]',
            row.selected ? 'bg-muted' : 'hover:bg-muted/50',
          )}
        >
          <div className="flex items-center gap-1 border-r px-2 py-1.5">
            {Array.from({length: row.indent ?? 0}, (_, i) => (
              <span key={i} className="inline-block w-3 shrink-0" />
            ))}
            <span className={cn('font-medium', row.tone === 'error' ? 'text-danger-fg' : 'text-foreground')}>
              {row.op}
            </span>
            <span className="truncate font-mono text-muted-foreground">{row.desc}</span>
          </div>
          <div className="relative h-full px-3 py-2">
            <span
              className={cn('absolute top-1/2 h-3 -translate-y-1/2 rounded-sm', WATERFALL_BG[row.tone])}
              style={{left: `${row.left}%`, width: `${row.width}%`}}
            />
            <span
              className="absolute top-1/2 -translate-y-1/2 whitespace-nowrap font-mono text-[10px] text-muted-foreground"
              style={{left: `${Math.min(row.left + row.width + 1, 86)}%`}}
            >
              {row.label}
            </span>
          </div>
        </div>
      ))}
    </div>
  )
}

// ---------------------------------------------------------------------------
// Error list (service Errors tab + resource errors panel)
// ---------------------------------------------------------------------------

export function ErrorList({errors, showUsers = false}: Readonly<{errors: readonly ErrorRow[]; showUsers?: boolean}>) {
  if (errors.length === 0) {
    return <p className="px-1 py-6 text-center text-sm text-muted-foreground">No active errors on this surface.</p>
  }
  return (
    <div className="divide-y">
      {errors.map((error) => (
        <div
          key={`${error.severity}-${error.title}-${error.sub}-${error.events}`}
          className={cn(
            'flex items-center gap-3 border-l-[3px] px-3 py-3 transition-colors hover:bg-muted/50',
            errorSeverityBorder(error.severity),
          )}
        >
          <span
            className={cn(
              'mt-0.5 h-2 w-2 shrink-0 self-start rounded-full',
              errorSeverityTone(error.severity) === 'warning' ? 'bg-warning-solid' : 'bg-danger-solid',
            )}
          />
          <div className="flex min-w-0 flex-1 flex-col gap-1">
            <div className="flex items-center gap-2 text-[13px] font-semibold">
              <span className="truncate">{error.title}</span>
              {error.unhandled && (
                <Badge variant="danger" size="sm" className="h-[18px]">
                  unhandled
                </Badge>
              )}
            </div>
            <div className="truncate font-mono text-xs text-muted-foreground">{error.sub}</div>
            <div className="mt-0.5 flex flex-wrap items-center gap-1.5">
              {error.chips.map((chip) => (
                <MetaChip key={chip}>{chip}</MetaChip>
              ))}
            </div>
          </div>
          <div className="w-16 shrink-0 text-right font-mono text-sm tabular-nums">
            {error.events}
            <span className="block text-[10px] uppercase tracking-wide text-muted-foreground/70">events</span>
          </div>
          {showUsers && (
            <div className="w-16 shrink-0 text-right font-mono text-sm tabular-nums">
              {error.users ?? '—'}
              <span className="block text-[10px] uppercase tracking-wide text-muted-foreground/70">users</span>
            </div>
          )}
        </div>
      ))}
    </div>
  )
}

// ---------------------------------------------------------------------------
// HTTP method chip + status badge (resource tables, traces)
// ---------------------------------------------------------------------------

export function MethodChip({method, className}: Readonly<{method: string; className?: string}>) {
  return <span className={cn('font-mono text-xs font-semibold text-foreground', className)}>{method}</span>
}

export function HttpStatusBadge({status}: Readonly<{status: number}>) {
  const ok = status < 400
  return (
    <Badge variant={ok ? 'success' : 'danger'} size="sm">
      {status}
    </Badge>
  )
}
