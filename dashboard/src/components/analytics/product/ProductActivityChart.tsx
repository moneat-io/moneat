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

import {useMemo, useState} from 'react'
import {Flag, Megaphone, Zap} from 'lucide-react'
import type {LucideIcon} from 'lucide-react'

import {Card} from '@/components/ui/card'
import {cn} from '@/lib/utils'
import type {
  ProductActivityAnnotation,
  ProductActivityMetric,
  ProductActivityPoint,
  ProductActivityResponse,
} from '@/lib/api'

import {formatCompact} from './format'

const METRIC_OPTIONS: ReadonlyArray<{value: ProductActivityMetric; label: string}> = [
  {value: 'active', label: 'Active users'},
  {value: 'new', label: 'New users'},
  {value: 'key_action', label: 'Key action'},
]

const ANNOTATION_ICON: Record<NonNullable<ProductActivityAnnotation['kind']>, LucideIcon> = {
  release: Flag,
  feature: Zap,
  campaign: Megaphone,
}

const PLOT = {left: 40, right: 700, top: 30, bottom: 180} as const
const GRID_Y = [30, 80, 130, 180]

function lineX(index: number, count: number): number {
  if (count <= 1) return PLOT.left
  return PLOT.left + (index / (count - 1)) * (PLOT.right - PLOT.left)
}

function lineY(value: number, max: number): number {
  return PLOT.bottom - (value / max) * (PLOT.bottom - PLOT.top)
}

function buildLine(points: ProductActivityPoint[], max: number): string {
  return points.map((point, index) => `${lineX(index, points.length)},${lineY(point.value, max)}`).join(' ')
}

function buildPrevLine(points: ProductActivityPoint[], max: number): string | null {
  if (points.some((point) => point.previous !== undefined)) {
    return points
      .map((point, index) => `${lineX(index, points.length)},${lineY(point.previous ?? 0, max)}`)
      .join(' ')
  }
  return null
}

function buildArea(points: ProductActivityPoint[], max: number): string {
  const line = points.map((point, index) => `${lineX(index, points.length)},${lineY(point.value, max)}`)
  return `${line.join(' ')} ${PLOT.right},${PLOT.bottom} ${PLOT.left},${PLOT.bottom}`
}

function annotationX(annotation: ProductActivityAnnotation, points: ProductActivityPoint[]): number | null {
  if (points.length < 2) return null
  const start = Date.parse(points[0].timestamp)
  const lastPoint = points.at(-1)
  if (lastPoint == null) return null
  const end = Date.parse(lastPoint.timestamp)
  const at = Date.parse(annotation.date)
  if (Number.isNaN(start) || Number.isNaN(end) || Number.isNaN(at) || end === start) return null
  const fraction = Math.min(1, Math.max(0, (at - start) / (end - start)))
  return PLOT.left + fraction * (PLOT.right - PLOT.left)
}

function MetricToggle({
  value,
  onChange,
}: Readonly<{value: ProductActivityMetric; onChange: (value: ProductActivityMetric) => void}>) {
  return (
    <div className="inline-flex gap-0.5 rounded-md border bg-muted/40 p-0.5">
      {METRIC_OPTIONS.map((option) => (
        <button
          key={option.value}
          type="button"
          aria-pressed={option.value === value}
          onClick={() => onChange(option.value)}
          className={cn(
            'rounded-sm px-2.5 py-1 text-xs font-medium transition-colors',
            option.value === value
              ? 'bg-card text-foreground shadow-sm'
              : 'text-muted-foreground hover:text-foreground',
          )}
        >
          {option.label}
        </button>
      ))}
    </div>
  )
}

function ProductActivityChartBody({
  annotations,
  axisLabels,
  isLoading,
  max,
  points,
  prevLine,
}: Readonly<{
  annotations: ProductActivityAnnotation[]
  axisLabels: number[]
  isLoading?: boolean
  max: number
  points: ProductActivityPoint[]
  prevLine: string | null
}>) {
  if (isLoading) {
    return <div className="h-[220px] w-full animate-pulse rounded bg-muted" />
  }
  if (points.length === 0) {
    return (
      <div className="flex h-[220px] items-center justify-center text-xs text-muted-foreground">
        No activity for the selected period
      </div>
    )
  }

  return (
    <svg viewBox="0 0 720 230" preserveAspectRatio="none" className="block h-[220px] w-full">
      <defs>
        <linearGradient id="product-activity-fill" x1="0" y1="0" x2="0" y2="1">
          <stop offset="0" stopColor="hsl(var(--chart-1))" stopOpacity={0.24} />
          <stop offset="1" stopColor="hsl(var(--chart-1))" stopOpacity={0} />
        </linearGradient>
      </defs>
      <g>
        {GRID_Y.map((y) => (
          <line key={y} x1={PLOT.left} y1={y} x2={PLOT.right} y2={y} stroke="hsl(var(--border))" strokeWidth={1} />
        ))}
      </g>
      {axisLabels.map((label, index) => (
        <text
          key={`axis-${label}`}
          x={6}
          y={GRID_Y[index] + 4}
          fill="hsl(var(--muted-foreground))"
          fontSize={9}
          className="font-mono tabular-nums"
        >
          {formatCompact(label)}
        </text>
      ))}
      <polygon fill="url(#product-activity-fill)" points={buildArea(points, max)} />
      {prevLine && (
        <polyline
          fill="none"
          points={prevLine}
          stroke="hsl(var(--muted-foreground))"
          strokeWidth={1.5}
          strokeDasharray="4 4"
          opacity={0.8}
        />
      )}
      <polyline fill="none" points={buildLine(points, max)} stroke="hsl(var(--chart-1))" strokeWidth={2} />
      {annotations.map((annotation) => {
        const x = annotationX(annotation, points)
        if (x == null) return null
        return (
          <g key={`${annotation.kind ?? 'release'}:${annotation.date}:${annotation.label}`}>
            <line
              x1={x}
              y1={PLOT.top - 8}
              x2={x}
              y2={PLOT.bottom}
              stroke="hsl(var(--muted-foreground))"
              strokeWidth={1}
              strokeDasharray="3 3"
              opacity={0.6}
            />
            <circle cx={x} cy={PLOT.top - 8} r={4} fill="hsl(var(--card))" stroke="hsl(var(--warning-solid))" strokeWidth={1.4} />
          </g>
        )
      })}
    </svg>
  )
}

export function ProductActivityChart({
  data,
  isLoading,
}: Readonly<{data?: ProductActivityResponse; isLoading?: boolean}>) {
  const [metric, setMetric] = useState<ProductActivityMetric>('active')

  const points = useMemo(
    () => data?.series.find((series) => series.metric === metric)?.points ?? [],
    [data, metric],
  )
  const annotations = data?.annotations ?? []

  const max = useMemo(() => {
    const values = points.flatMap((point) => [point.value, point.previous ?? 0])
    return Math.max(...values, 1)
  }, [points])

  const prevLine = useMemo(() => buildPrevLine(points, max), [points, max])
  const axisLabels = [max, max * (2 / 3), max / 3, 0]

  return (
    <Card className="overflow-hidden">
      <div className="flex flex-wrap items-center gap-2 border-b px-3 py-2">
        <MetricToggle value={metric} onChange={setMetric} />
        <div className="ml-auto flex items-center gap-3 text-xs text-muted-foreground">
          <span className="inline-flex items-center gap-1.5">
            <span className="h-0.5 w-3.5 bg-chart-1" /> This period
          </span>
          <span className="inline-flex items-center gap-1.5">
            <span className="h-0 w-3.5 border-t border-dashed border-muted-foreground" /> Previous
          </span>
        </div>
      </div>

      <div className="px-2.5 pb-1 pt-2">
        <ProductActivityChartBody
          annotations={annotations}
          axisLabels={axisLabels}
          isLoading={isLoading}
          max={max}
          points={points}
          prevLine={prevLine}
        />
      </div>

      {annotations.length > 0 && (
        <div className="flex flex-wrap gap-1.5 px-3 pb-2.5 pt-1">
          {annotations.map((annotation) => {
            const Icon = ANNOTATION_ICON[annotation.kind ?? 'release']
            return (
              <span
                key={`${annotation.kind ?? 'release'}:${annotation.date}:${annotation.label}`}
                className="inline-flex items-center gap-1.5 rounded-full border bg-muted/50 px-2.5 py-0.5 text-xs text-muted-foreground"
              >
                <Icon className="h-3 w-3 text-warning-fg" />
                <span className="font-medium text-foreground">{annotation.label}</span>
                <span className="font-mono text-[10px] text-muted-foreground/80">{annotation.date}</span>
              </span>
            )
          })}
        </div>
      )}
    </Card>
  )
}
