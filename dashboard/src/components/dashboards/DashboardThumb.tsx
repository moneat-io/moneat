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

import {useId, type ReactNode} from 'react'
import {cn} from '@/lib/utils'
import type {ThumbKind} from './dashboardThumbHelpers'

// Shared mini-viz thumbnail family for the Dashboards hub. The same six shapes
// render at three sizes: a full preview on template + grid cards (DashboardThumb)
// and a compact sparkline on dense list rows (SparkThumb). A board or template is
// recognisable by its shape, not just its name.

// The dark viz canvas is intentionally theme-independent (dense viz reads as a
// field in either app theme — style guide), so these decorative thumbnail
// colors are fixed values mirroring the guide's --viz-*/--scale-*/--level-*/
// --heat-* tokens rather than theme-flipping CSS variables. Sparkline strokes
// reuse the shared --chart-* tokens (identical across themes).
const VIZ = {
  panel: '#141922', // --viz-surface-2
  border: 'rgba(255,255,255,0.09)', // hairline on the dark canvas (~ --viz-grid)
  track: 'rgba(255,255,255,0.09)',
  fg: '#d7e1ec', // --viz-fg
  fgMuted: '#8a99a9', // --viz-fg-muted
  good: '#18a07a', // --scale-good
  warn: '#e0a100', // --scale-warn
  bad: '#e5484d', // --scale-bad
  levelInfo: '#7ba2ea', // --level-info
  levelWarn: '#f0c150', // --level-warn
  levelError: '#f2868a', // --level-error
  heat: ['#0e1c2b', '#0d3b54', '#0e6f8f', '#19a7b0', '#6fc98a', '#e0c545', '#f27537'],
} as const

type SparkSpec = Readonly<{stroke: string; line: string; area: string}>

const SPARKS: Record<'service' | 'hostNet' | 'db' | 'vitals', SparkSpec> = {
  service: {
    stroke: 'hsl(var(--chart-1))',
    line: '0,30 20,27 40,29 60,20 80,24 100,15 120,19 140,11 160,16 180,9 200,13 220,7 240,10',
    area: 'M0,30 L20,27 L40,29 L60,20 L80,24 L100,15 L120,19 L140,11 L160,16 L180,9 L200,13 L220,7 L240,10 L240,40 L0,40 Z',
  },
  hostNet: {
    stroke: 'hsl(var(--chart-4))',
    line: '0,24 20,20 40,26 60,17 80,27 100,19 120,29 140,21 160,30 180,22 200,26 220,18 240,22',
    area: 'M0,24 L20,20 L40,26 L60,17 L80,27 L100,19 L120,29 L140,21 L160,30 L180,22 L200,26 L220,18 L240,22 L240,40 L0,40 Z',
  },
  db: {
    stroke: 'hsl(var(--chart-3))',
    line: '0,20 20,18 40,19 60,15 80,17 100,13 120,15 140,12 160,14 180,11 200,13 220,10 240,12',
    area: 'M0,20 L20,18 L40,19 L60,15 L80,17 L100,13 L120,15 L140,12 L160,14 L180,11 L200,13 L220,10 L240,12 L240,40 L0,40 Z',
  },
  vitals: {
    stroke: 'hsl(var(--chart-2))',
    line: '0,30 20,28 40,24 60,18 80,12 100,10 120,14 140,20 160,16 180,22 200,26 220,28 240,30',
    area: 'M0,30 L20,28 L40,24 L60,18 L80,12 L100,10 L120,14 L140,20 L160,16 L180,22 L200,26 L220,28 L240,30 L240,40 L0,40 Z',
  },
}

// Stroke used by the compact list-row sparkline, keyed by thumb kind so a row
// still reads as "its" shape at a glance.
const SPARK_STROKE: Record<ThumbKind, string> = {
  service: 'hsl(var(--chart-1))',
  host: 'hsl(var(--chart-4))',
  k8s: 'hsl(var(--chart-9))',
  db: 'hsl(var(--chart-3))',
  logs: 'hsl(var(--chart-2))',
  vitals: 'hsl(var(--chart-2))',
}

// Pod-saturation heat strip: 16 cols × 4 rows, column-major, biased hotter
// toward the top-right to read like creeping saturation.
type HeatCell = Readonly<{id: string; colorIndex: number}>
const HEAT_CELLS: readonly HeatCell[] = (() => {
  const cols = 16
  const rows = 4
  const out: HeatCell[] = []
  for (let c = 0; c < cols; c++) {
    for (let r = 0; r < rows; r++) {
      const bias = (c / cols) * 4 + (rows - r) * 0.6
      const idx = Math.max(0, Math.min(6, Math.round(bias - 1 + (Math.sin(c * 1.7 + r) + 1))))
      out.push({id: `${c}-${r}`, colorIndex: idx})
    }
  }
  return out
})()

// Stacked log volume: [info, warn, error] pixel heights per column.
type LogColumn = Readonly<{id: string; info: number; warn: number; error: number}>
const LOG_COLUMNS: readonly LogColumn[] = [
  {id: 'log-01', info: 16, warn: 4, error: 0},
  {id: 'log-02', info: 22, warn: 6, error: 2},
  {id: 'log-03', info: 15, warn: 3, error: 0},
  {id: 'log-04', info: 26, warn: 5, error: 1},
  {id: 'log-05', info: 20, warn: 7, error: 0},
  {id: 'log-06', info: 30, warn: 6, error: 3},
  {id: 'log-07', info: 24, warn: 5, error: 1},
  {id: 'log-08', info: 28, warn: 9, error: 5},
  {id: 'log-09', info: 21, warn: 6, error: 2},
  {id: 'log-10', info: 18, warn: 4, error: 1},
  {id: 'log-11', info: 14, warn: 3, error: 0},
  {id: 'log-12', info: 23, warn: 5, error: 1},
]

// ---- Thumbnails -----------------------------------------------------------

type DashboardThumbProps = Readonly<{kind: ThumbKind}>

/** Full mini-viz preview (used on template cards and grid-view dashboard cards). */
export function DashboardThumb({kind}: DashboardThumbProps) {
  const rawId = useId()
  const gid = `sp-${rawId.replaceAll(':', '')}`

  switch (kind) {
    case 'service':
      return (
        <Mini>
          <Row fixed>
            <Tile label="req / min" value="18.2" unit="k" />
            <Tile label="p95" value="248" unit="ms" />
            <Tile label="errors" value="2.1" unit="%" color={VIZ.warn} />
          </Row>
          <Panel caption="latency · p95">
            <Spark spec={SPARKS.service} gid={gid} />
          </Panel>
        </Mini>
      )
    case 'host':
      return (
        <Mini>
          <Panel caption="cpu by host" fixed>
            <Bars>
              <Bar name="web-01" pct={88} color={VIZ.bad} />
              <Bar name="web-02" pct={63} color={VIZ.warn} />
              <Bar name="db-01" pct={41} color={VIZ.good} />
            </Bars>
          </Panel>
          <Panel caption="network i/o">
            <Spark spec={SPARKS.hostNet} gid={gid} />
          </Panel>
        </Mini>
      )
    case 'k8s':
      return (
        <Mini>
          <Row fixed>
            <Tile label="pods" value="142" />
            <Tile label="restarts" value="3" color={VIZ.warn} />
            <Tile label="nodes" value="9" />
          </Row>
          <Panel caption="pod cpu saturation">
            <div
              className="grid min-h-0 flex-1 gap-[2px]"
              style={{gridAutoFlow: 'column', gridTemplateRows: 'repeat(4, 1fr)'}}
            >
              {HEAT_CELLS.map((cell) => (
                <span
                  key={cell.id}
                  className="rounded-[1px]"
                  style={{background: VIZ.heat[cell.colorIndex]}}
                />
              ))}
            </div>
          </Panel>
        </Mini>
      )
    case 'db':
      return (
        <Mini>
          <Panel caption="queries / s">
            <Spark spec={SPARKS.db} gid={gid} />
          </Panel>
          <Panel caption="latency by op · ms" fixed>
            <Bars>
              <Bar name="reads" pct={92} color={VIZ.bad} />
              <Bar name="writes" pct={54} color={VIZ.warn} />
            </Bars>
          </Panel>
        </Mini>
      )
    case 'logs':
      return (
        <Mini>
          <Panel caption="log volume by level">
            <div className="flex min-h-0 flex-1 items-end gap-[3px]">
              {LOG_COLUMNS.map((col) => (
                <span key={col.id} className="flex flex-1 flex-col justify-end gap-px">
                  <Seg h={col.info} color={VIZ.levelInfo} />
                  {col.warn > 0 && <Seg h={col.warn} color={VIZ.levelWarn} />}
                  {col.error > 0 && <Seg h={col.error} color={VIZ.levelError} />}
                </span>
              ))}
            </div>
          </Panel>
          <div className="flex shrink-0 gap-2">
            <LegendChip color={VIZ.levelInfo} label="info" />
            <LegendChip color={VIZ.levelWarn} label="warn" />
            <LegendChip color={VIZ.levelError} label="error" />
          </div>
        </Mini>
      )
    case 'vitals':
      return (
        <Mini>
          <Row fixed>
            <Tile label="LCP" value="1.9" unit="s" color={VIZ.good} />
            <Tile label="INP" value="180" unit="ms" color={VIZ.warn} />
            <Tile label="CLS" value="0.04" color={VIZ.good} />
          </Row>
          <Panel caption="page loads">
            <Spark spec={SPARKS.vitals} gid={gid} />
          </Panel>
        </Mini>
      )
  }
}

/** Compact sparkline thumbnail for dense list rows (56×36 on the viz canvas). */
export function SparkThumb({kind}: DashboardThumbProps) {
  return (
    <svg
      viewBox="0 0 56 36"
      preserveAspectRatio="none"
      className="block h-full w-full"
      aria-hidden="true"
    >
      <polyline
        points="0,28 8,24 16,26 24,18 32,21 40,12 48,16 56,9"
        fill="none"
        stroke={SPARK_STROKE[kind]}
        strokeWidth={2}
      />
    </svg>
  )
}

// ---- Building blocks (dark canvas) ----------------------------------------

type ChildrenProps = Readonly<{children: ReactNode}>

function Mini({children}: ChildrenProps) {
  return <div className="flex h-full flex-col gap-[5px]">{children}</div>
}

function Row({children, fixed}: ChildrenProps & Readonly<{fixed?: boolean}>) {
  return <div className={cn('flex min-h-0 gap-[5px]', fixed && 'shrink-0')}>{children}</div>
}

function Tile({
  label,
  value,
  unit,
  color,
}: Readonly<{label: string; value: string; unit?: string; color?: string}>) {
  return (
    <div
      className="flex min-w-0 flex-1 flex-col justify-center gap-0.5 rounded-sm border px-1.5 py-1"
      style={{background: VIZ.panel, borderColor: VIZ.border}}
    >
      <span className="truncate text-[8px] uppercase tracking-wide" style={{color: VIZ.fgMuted}}>
        {label}
      </span>
      <span
        className="font-mono text-[13px] font-semibold leading-none"
        style={{color: color ?? VIZ.fg}}
      >
        {value}
        {unit && (
          <span className="ml-px text-[9px]" style={{color: VIZ.fgMuted}}>
            {unit}
          </span>
        )}
      </span>
    </div>
  )
}

function Panel({
  caption,
  children,
  fixed,
}: ChildrenProps & Readonly<{caption: string; fixed?: boolean}>) {
  return (
    <div
      className={cn(
        'flex min-h-0 flex-col gap-1 rounded-sm border px-[7px] py-[5px]',
        fixed ? 'shrink-0' : 'flex-1',
      )}
      style={{background: VIZ.panel, borderColor: VIZ.border}}
    >
      <span className="text-[8px] uppercase tracking-wide" style={{color: VIZ.fgMuted}}>
        {caption}
      </span>
      {children}
    </div>
  )
}

function Spark({spec, gid}: Readonly<{spec: SparkSpec; gid: string}>) {
  return (
    <svg
      viewBox="0 0 240 40"
      preserveAspectRatio="none"
      className="block min-h-0 w-full flex-1"
      aria-hidden="true"
    >
      <defs>
        <linearGradient id={gid} x1="0" x2="0" y1="0" y2="1">
          <stop offset="0" stopColor={spec.stroke} stopOpacity={0.3} />
          <stop offset="1" stopColor={spec.stroke} stopOpacity={0} />
        </linearGradient>
      </defs>
      <path d={spec.area} fill={`url(#${gid})`} />
      <polyline points={spec.line} fill="none" stroke={spec.stroke} strokeWidth={2} />
    </svg>
  )
}

function Bars({children}: ChildrenProps) {
  return <div className="flex flex-1 flex-col justify-center gap-1">{children}</div>
}

function Bar({name, pct, color}: Readonly<{name: string; pct: number; color: string}>) {
  return (
    <div className="grid grid-cols-[38px_1fr] items-center gap-[5px]">
      <span className="truncate font-mono text-[8px]" style={{color: VIZ.fgMuted}}>
        {name}
      </span>
      <span className="h-[5px] overflow-hidden rounded-[2px]" style={{background: VIZ.track}}>
        <span className="block h-full rounded-[2px]" style={{width: `${pct}%`, background: color}} />
      </span>
    </div>
  )
}

function Seg({h, color}: Readonly<{h: number; color: string}>) {
  return <span className="w-full rounded-[1px]" style={{height: `${h}px`, background: color}} />
}

function LegendChip({color, label}: Readonly<{color: string; label: string}>) {
  return (
    <span className="inline-flex items-center gap-1 text-[8px]" style={{color: VIZ.fgMuted}}>
      <span className="h-1.5 w-1.5 rounded-[2px]" style={{background: color}} />
      {label}
    </span>
  )
}
