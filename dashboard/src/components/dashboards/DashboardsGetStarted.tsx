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

import {useId, useMemo, useRef, useState, type ReactNode} from 'react'
import {
  ArrowRight,
  FileJson,
  LayoutTemplate,
  SquarePlus,
  type LucideIcon,
} from 'lucide-react'
import {Badge} from '@/components/ui/badge'
import type {DashboardTemplateQuality, DashboardTemplateSummary} from '@/lib/api'
import {cn} from '@/lib/utils'

// First-run Dashboards experience: a template-gallery quickstart shown when no
// dashboards exist yet. Three "start" tiles (blank / template / import) over a
// filterable grid of starter dashboards, each previewed by a real mini-viz
// thumbnail rendered on the dense dark viz canvas. Ports
// /tmp/dashboards-empty-state-mockup.html onto the app's design tokens.

type TemplateCategory =
  | 'infrastructure'
  | 'kubernetes'
  | 'applications'
  | 'databases'
  | 'cloud'
  | 'logs'
type TemplateFilter = 'all' | TemplateCategory
type ThumbKind = 'service' | 'host' | 'k8s' | 'db' | 'logs' | 'vitals'

interface DashboardsGetStartedProps {
  readonly templates: readonly DashboardTemplateSummary[]
  readonly isLoadingTemplates?: boolean
  readonly onCreateBlank: () => void
  readonly onUseTemplate: (templateId: string) => void
  readonly onImport: () => void
}

interface TemplateCardModel {
  readonly template: DashboardTemplateSummary
  readonly category: TemplateCategory | null
  readonly thumb: ThumbKind
}

type TemplateGalleryProps = Readonly<{
  templates: readonly TemplateCardModel[]
  isLoading: boolean
  onUseTemplate: (templateId: string) => void
}>

type BlockHeadProps = Readonly<{
  title: string
  hint: string
  tools?: ReactNode
}>

type StartTileProps = Readonly<{
  icon: LucideIcon
  title: string
  description: string
  onClick: () => void
  primary?: boolean
}>

type TemplateCardProps = Readonly<{
  model: TemplateCardModel
  onUse: () => void
}>

type TemplateThumbProps = Readonly<{
  kind: ThumbKind
}>

type ChildrenProps = Readonly<{
  children: ReactNode
}>

type RowProps = Readonly<{
  children: ReactNode
  fixed?: boolean
}>

type TileProps = Readonly<{
  label: string
  value: string
  unit?: string
  color?: string
}>

type PanelProps = Readonly<{
  caption: string
  children: ReactNode
  fixed?: boolean
}>

type SparkSpec = Readonly<{
  stroke: string
  line: string
  area: string
}>

type SparkProps = Readonly<{
  spec: SparkSpec
  gid: string
}>

type BarProps = Readonly<{
  name: string
  pct: number
  color: string
}>

type SegmentProps = Readonly<{
  h: number
  color: string
}>

type LegendChipProps = Readonly<{
  color: string
  label: string
}>

type HeatCell = Readonly<{
  id: string
  colorIndex: number
}>

type LogColumn = Readonly<{
  id: string
  info: number
  warn: number
  error: number
}>

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

const FILTERS: ReadonlyArray<{key: TemplateFilter; label: string}> = [
  {key: 'all', label: 'All'},
  {key: 'infrastructure', label: 'Infrastructure'},
  {key: 'kubernetes', label: 'Kubernetes'},
  {key: 'applications', label: 'Applications'},
  {key: 'databases', label: 'Databases'},
  {key: 'cloud', label: 'Cloud'},
  {key: 'logs', label: 'Logs'},
]

const QUALITY_LABELS: Record<DashboardTemplateQuality, string> = {
  ready: 'Ready',
  partial: 'Partial',
  'needs-review': 'Review',
}

const QUALITY_VARIANTS: Record<DashboardTemplateQuality, 'success' | 'warning' | 'neutral'> = {
  ready: 'success',
  partial: 'warning',
  'needs-review': 'neutral',
}

const SOURCE_BADGE_LIMIT = 2

export function DashboardsGetStarted({
  templates,
  isLoadingTemplates = false,
  onCreateBlank,
  onUseTemplate,
  onImport,
}: DashboardsGetStartedProps) {
  const [filter, setFilter] = useState<TemplateFilter>('all')
  const galleryRef = useRef<HTMLElement>(null)

  const scrollToGallery = () => {
    galleryRef.current?.scrollIntoView?.({behavior: 'smooth', block: 'start'})
  }

  const galleryTemplates = useMemo(
    () =>
      templates.map((template) => ({
        template,
        category: normalizeCategory(template.category),
        thumb: getTemplateThumb(template),
      })),
    [templates],
  )

  const visible = galleryTemplates.filter(
    (t) => filter === 'all' || t.category === filter,
  )

  return (
    <div className="mx-auto w-full max-w-[1200px]">
      {/* Get started */}
      <section>
        <BlockHead
          title="Get started"
          hint="No dashboards yet — pick a starting point. Everything is editable after you create it."
        />
        <div className="grid grid-cols-1 gap-3 sm:grid-cols-2 lg:grid-cols-3">
          <StartTile
            primary
            icon={SquarePlus}
            title="Blank dashboard"
            description="Start with an empty grid and drag in widgets."
            onClick={onCreateBlank}
          />
          <StartTile
            icon={LayoutTemplate}
            title="Start from a template"
            description="Prebuilt panel sets for common stacks — below."
            onClick={scrollToGallery}
          />
          <StartTile
            icon={FileJson}
            title="Import JSON"
            description="Bring a dashboard from a Grafana-compatible export."
            onClick={onImport}
          />
        </div>
      </section>

      {/* Recommended templates */}
      <section ref={galleryRef} className="mt-6 scroll-mt-4">
        <BlockHead
          title="Recommended templates"
          hint="Curated, fully editable starting points."
          tools={
            <fieldset
              aria-label="Filter templates by category"
              className="inline-flex items-center gap-0.5 rounded-md border bg-muted/40 p-0.5"
            >
              {FILTERS.map((f) => {
                const active = filter === f.key
                return (
                  <button
                    key={f.key}
                    type="button"
                    aria-pressed={active}
                    onClick={() => setFilter(f.key)}
                    className={cn(
                      'rounded-[5px] px-2 py-1 text-xs font-medium transition-colors',
                      active
                        ? 'bg-card text-foreground shadow-sm'
                        : 'text-muted-foreground hover:text-foreground',
                    )}
                  >
                    {f.label}
                  </button>
                )
              })}
            </fieldset>
          }
        />
        <TemplateGallery
          templates={visible}
          isLoading={isLoadingTemplates}
          onUseTemplate={onUseTemplate}
        />
      </section>
    </div>
  )
}

function normalizeCategory(category: string): TemplateCategory | null {
  const normalized = category.toLowerCase()
  switch (normalized) {
    case 'infrastructure':
    case 'kubernetes':
    case 'applications':
    case 'databases':
    case 'cloud':
    case 'logs':
      return normalized
    default:
      return null
  }
}

function getTemplateThumb(template: DashboardTemplateSummary): ThumbKind {
  const category = normalizeCategory(template.category)
  if (category === 'kubernetes') return 'k8s'
  if (category === 'databases') return 'db'
  if (category === 'logs') return 'logs'
  if (category === 'applications') return 'service'

  const tags = new Set(template.tags.map((tag) => tag.toLowerCase()))
  if (tags.has('kubernetes')) return 'k8s'
  if (tags.has('database') || tags.has('databases')) return 'db'
  if (tags.has('logs')) return 'logs'
  if (tags.has('browser') || tags.has('rum')) return 'vitals'
  return 'host'
}

function TemplateGallery({templates, isLoading, onUseTemplate}: TemplateGalleryProps) {
  if (isLoading) {
    return <TemplateGallerySkeleton />
  }

  if (templates.length === 0) {
    return (
      <div className="rounded-lg border bg-card p-6 text-sm text-muted-foreground">
        No templates match this category.
      </div>
    )
  }

  return (
    <div className="grid grid-cols-1 gap-4 sm:grid-cols-2 lg:grid-cols-3">
      {templates.map((template) => (
        <TemplateCard
          key={template.template.id}
          model={template}
          onUse={() => onUseTemplate(template.template.id)}
        />
      ))}
    </div>
  )
}

function BlockHead({
  title,
  hint,
  tools,
}: BlockHeadProps) {
  return (
    <div className="mb-3 flex flex-wrap items-baseline gap-x-3 gap-y-1">
      <h2 className="text-sm font-semibold text-foreground">{title}</h2>
      <span className="text-xs text-muted-foreground">{hint}</span>
      {tools && <div className="ml-auto">{tools}</div>}
    </div>
  )
}

function StartTile({
  icon: Icon,
  title,
  description,
  onClick,
  primary = false,
}: StartTileProps) {
  return (
    <button
      type="button"
      onClick={onClick}
      className={cn(
        'group flex items-center gap-3 rounded-lg border bg-card px-3 py-3 text-left shadow-sm transition-all hover:-translate-y-px hover:shadow-md',
        primary ? 'border-primary/40 hover:border-primary' : 'hover:border-foreground/20',
      )}
    >
      <span
        className={cn(
          'flex h-[34px] w-[34px] shrink-0 items-center justify-center rounded-md border',
          primary
            ? 'border-transparent bg-primary text-primary-foreground'
            : 'border-border bg-muted text-muted-foreground',
        )}
      >
        <Icon className="h-[18px] w-[18px]" />
      </span>
      <span className="flex min-w-0 flex-col gap-px">
        <span className="text-sm font-semibold text-foreground">{title}</span>
        <span className="text-xs leading-snug text-muted-foreground">{description}</span>
      </span>
      <ArrowRight className="ml-auto h-4 w-4 shrink-0 text-muted-foreground/60 transition-all group-hover:translate-x-0.5 group-hover:text-primary" />
    </button>
  )
}

function TemplateCard({model, onUse}: TemplateCardProps) {
  const {template, thumb} = model
  const sources = template.required_sources.slice(0, SOURCE_BADGE_LIMIT)
  const extraSourceCount = template.required_sources.length - sources.length
  const sourceSet = new Set(template.required_sources.map((source) => source.toLowerCase()))
  const tags = template.tags.filter((tag) => !sourceSet.has(tag.toLowerCase()))

  return (
    <button
      type="button"
      onClick={onUse}
      aria-label={`Use the ${template.title} template`}
      className="group flex flex-col overflow-hidden rounded-lg border bg-card text-left shadow-sm transition-all hover:-translate-y-px hover:border-primary hover:shadow-md"
    >
      <div className="relative h-[116px] overflow-hidden border-b bg-[hsl(var(--viz-surface))] p-2">
        <TemplateThumb kind={thumb} />
        <span className="pointer-events-none absolute bottom-2 right-2 inline-flex items-center gap-1 rounded-full bg-primary px-2 py-0.5 text-[10px] font-medium text-primary-foreground opacity-0 transition-opacity group-hover:opacity-100">
          Use template
          <ArrowRight className="h-2.5 w-2.5" />
        </span>
      </div>
      <div className="flex flex-col gap-1.5 px-3 pb-3 pt-2.5">
        <div className="flex items-center gap-2">
          <span className="text-sm font-semibold text-foreground transition-colors group-hover:text-primary">
            {template.title}
          </span>
          <span className="ml-auto font-mono text-[10px] tabular-nums text-muted-foreground/80">
            {template.widget_count} widgets
          </span>
        </div>
        <p className="text-xs leading-snug text-muted-foreground">{template.description}</p>
        <div className="mt-0.5 flex flex-wrap gap-1.5">
          <Badge
            variant={QUALITY_VARIANTS[template.quality]}
            className="rounded-full px-2 py-0 text-[10px] font-medium leading-4"
          >
            {QUALITY_LABELS[template.quality]}
          </Badge>
          {sources.map((source) => (
            <Badge
              key={source}
              variant="neutral"
              className="rounded-full px-2 py-0 text-[10px] font-medium leading-4"
            >
              {source}
            </Badge>
          ))}
          {extraSourceCount > 0 && (
            <Badge
              variant="neutral"
              className="rounded-full px-2 py-0 text-[10px] font-medium leading-4"
            >
              +{extraSourceCount}
            </Badge>
          )}
          {tags.map((tag) => (
            <Badge
              key={tag}
              variant="neutral"
              className="rounded-full px-2 py-0 text-[10px] font-medium leading-4"
            >
              {tag}
            </Badge>
          ))}
        </div>
      </div>
    </button>
  )
}

function TemplateGallerySkeleton() {
  return (
    <div className="grid grid-cols-1 gap-4 sm:grid-cols-2 lg:grid-cols-3">
      {[1, 2, 3, 4, 5, 6].map((i) => (
        <div key={i} className="overflow-hidden rounded-lg border bg-card">
          <div className="h-[116px] animate-pulse border-b bg-muted" />
          <div className="space-y-2 px-3 pb-3 pt-2.5">
            <div className="h-4 w-2/3 animate-pulse rounded bg-muted" />
            <div className="h-3 w-full animate-pulse rounded bg-muted" />
            <div className="flex gap-1.5 pt-1">
              <div className="h-4 w-14 animate-pulse rounded-full bg-muted" />
              <div className="h-4 w-20 animate-pulse rounded-full bg-muted" />
            </div>
          </div>
        </div>
      ))}
    </div>
  )
}

// ---- Mini-viz thumbnails (dark canvas) ------------------------------------

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

// Pod-saturation heat strip: 16 cols × 4 rows, column-major, biased hotter
// toward the top-right to read like creeping saturation.
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

function TemplateThumb({kind}: TemplateThumbProps) {
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

function Mini({children}: ChildrenProps) {
  return <div className="flex h-full flex-col gap-[5px]">{children}</div>
}

function Row({children, fixed}: RowProps) {
  return <div className={cn('flex min-h-0 gap-[5px]', fixed && 'shrink-0')}>{children}</div>
}

function Tile({
  label,
  value,
  unit,
  color,
}: TileProps) {
  return (
    <div
      className="flex min-w-0 flex-1 flex-col justify-center gap-0.5 rounded-sm border px-1.5 py-1"
      style={{background: VIZ.panel, borderColor: VIZ.border}}
    >
      <span
        className="truncate text-[8px] uppercase tracking-wide"
        style={{color: VIZ.fgMuted}}
      >
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
}: PanelProps) {
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

function Spark({spec, gid}: SparkProps) {
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

function Bar({name, pct, color}: BarProps) {
  return (
    <div className="grid grid-cols-[38px_1fr] items-center gap-[5px]">
      <span className="truncate font-mono text-[8px]" style={{color: VIZ.fgMuted}}>
        {name}
      </span>
      <span
        className="h-[5px] overflow-hidden rounded-[2px]"
        style={{background: VIZ.track}}
      >
        <span
          className="block h-full rounded-[2px]"
          style={{width: `${pct}%`, background: color}}
        />
      </span>
    </div>
  )
}

function Seg({h, color}: SegmentProps) {
  return <span className="w-full rounded-[1px]" style={{height: `${h}px`, background: color}} />
}

function LegendChip({color, label}: LegendChipProps) {
  return (
    <span className="inline-flex items-center gap-1 text-[8px]" style={{color: VIZ.fgMuted}}>
      <span className="h-1.5 w-1.5 rounded-[2px]" style={{background: color}} />
      {label}
    </span>
  )
}
