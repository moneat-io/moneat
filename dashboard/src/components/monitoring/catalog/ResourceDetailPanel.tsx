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
// ResourceDetailPanel — the faceted side panel for one resource.
// Reused by the Resource Catalog and (future) the Monitoring Map node dock, so
// both surfaces present identical ownership / telemetry / relationships /
// security / cost / change views.
// ─────────────────────────────────────────────────────────────────────────────

import {useMemo, useState, type ComponentType, type ReactNode} from 'react'
import {Link} from '@tanstack/react-router'
import {
  Activity,
  AlertTriangle,
  ChevronRight,
  Clock,
  Cpu,
  ExternalLink,
  GitBranch,
  Hash,
  HardDrive,
  LayoutDashboard,
  Lightbulb,
  MapPin,
  MemoryStick,
  Network,
  Plus,
  ScrollText,
  Share2,
  ShieldAlert,
  ShieldCheck,
  TrendingDown,
  TrendingUp,
  Users,
  X,
} from 'lucide-react'

import {cn} from '@/lib/utils'
import {Badge} from '@/components/ui/badge'
import {Button} from '@/components/ui/button'
import {EmptyState} from '@/components/ui/empty-state'
import {StatusDot, type StatusTone} from '@/components/ui/status-dot'
import {Tabs, TabsContent, TabsList, TabsTrigger} from '@/components/ui/tabs'
import {useTimezone} from '@/hooks/useTimezone'
import {HealthBadge, KindIcon, Meter, TagChip, VulnBar} from './CatalogPrimitives'
import {MetricChart, type MetricChartProps} from './CatalogCharts'
import {
  CHANGE_ICON,
  CHANGE_TONE,
  CLOUD_LABEL,
  ENV_BADGE,
  ENV_LABEL,
  HEALTH_TONE,
  KIND_META,
  TONE_TEXT,
  VULN_BADGE,
  VULN_BAR,
  VULN_LABEL,
  VULN_SEVERITIES,
  formatPct,
  formatUsd,
  formatUsdExact,
  hostInfraSample,
  mockFindings,
  relTime,
  sparkline,
  totalVulns,
  utilTone,
  type HostInfraSample,
  type Relationship,
  type Resource,
} from './resourceCatalogData'

type DetailTab = 'overview' | 'telemetry' | 'relationships' | 'ownership' | 'security' | 'cost' | 'changes'

const DETAIL_TABS: readonly {readonly id: DetailTab; readonly label: string}[] = [
  {id: 'overview', label: 'Overview'},
  {id: 'telemetry', label: 'Telemetry'},
  {id: 'relationships', label: 'Relationships'},
  {id: 'ownership', label: 'Ownership & Tags'},
  {id: 'security', label: 'Security'},
  {id: 'cost', label: 'Cost'},
  {id: 'changes', label: 'Changes'},
]

const HOST_RESOURCE_ID_PATTERN = /^host:(?:\d+:)?(\d+)$/
const COST_MONTH_KEYS = ['six-months-ago', 'five-months-ago', 'four-months-ago', 'three-months-ago', 'last-month', 'current'] as const

function getMetricsHostId(resource: Resource): string | null {
  if (resource.kind !== 'host') return null
  const result = HOST_RESOURCE_ID_PATTERN.exec(resource.id)
  return result?.[1] ?? null
}

function metricTileVisual({
  pct,
  tone,
}: {
  readonly pct?: number
  readonly tone: StatusTone
}) {
  if (pct !== undefined) {
    return (
      <div className="mt-1.5">
        <Meter value={pct} tone={tone} />
      </div>
    )
  }
  return null
}

function errorRateTone(rate: number): StatusTone {
  if (rate >= 2) return 'danger'
  if (rate >= 1) return 'warning'
  return 'success'
}

function FieldRow({label, value}: {readonly label: string; readonly value: ReactNode}) {
  return (
    <div className="flex items-baseline justify-between gap-3 py-1 text-xs">
      <span className="shrink-0 text-muted-foreground">{label}</span>
      <span className="min-w-0 truncate text-right font-medium">{value}</span>
    </div>
  )
}

function PanelSection({title, action, children}: {readonly title: string; readonly action?: ReactNode; readonly children: ReactNode}) {
  return (
    <div className="rounded-md border border-border/70 bg-background/40 p-3">
      <div className="mb-2 flex items-center justify-between">
        <h4 className="text-[11px] font-semibold uppercase tracking-wide text-muted-foreground">{title}</h4>
        {action}
      </div>
      {children}
    </div>
  )
}

function MetricTile({
  label,
  value,
  unit,
  icon: Icon,
  pct,
  tone,
}: {
  readonly label: string
  readonly value: string
  readonly unit?: string
  readonly icon: ComponentType<{className?: string}>
  readonly pct?: number
  readonly tone: StatusTone
}) {
  return (
    <div className="rounded-md border border-border/70 bg-background/40 p-2.5">
      <div className="flex items-center gap-1.5 text-[11px] text-muted-foreground">
        <Icon className="h-3 w-3" />
        {label}
      </div>
      <div className="mt-1 flex items-baseline gap-1">
        <span className="text-lg font-semibold tabular-nums">{value}</span>
        {unit && <span className="text-[11px] text-muted-foreground">{unit}</span>}
      </div>
      {metricTileVisual({pct, tone})}
    </div>
  )
}

// ── Over-time charts ─────────────────────────────────────────────────────────
// Each metric defines a reusable chart spec (everything but the time range and
// sizing), so the Overview highlights and the full Telemetry grid stay in sync.

type MetricChartSpec = Pick<
  MetricChartProps,
  'title' | 'subtitle' | 'icon' | 'iconClass' | 'seed' | 'series' | 'yDomain' | 'formatValue' | 'formatYTick' | 'noData'
>

const PCT_DOMAIN = [0, 100] as const
const formatPercentValue = (value: number) => `${value.toFixed(1)}%`
const formatPercentTick = (value: number) => `${Math.round(value)}`
const formatMsValue = (value: number) => `${Math.round(value)} ms`
const formatRoundTick = (value: number) => `${Math.round(value)}`
const formatLoadValue = (value: number) => value.toFixed(2)

function formatBytesShort(bytes: number): string {
  if (bytes <= 0) return '0'
  const units = ['B', 'KB', 'MB', 'GB', 'TB']
  const i = Math.min(units.length - 1, Math.floor(Math.log(bytes) / Math.log(1024)))
  return `${(bytes / 1024 ** i).toFixed(i === 0 ? 0 : 1)}${units[i]}`
}
const formatBytesRate = (value: number) => `${formatBytesShort(value)}/s`

function cpuChartSpec(resource: Resource): MetricChartSpec {
  return {
    title: 'CPU utilization',
    subtitle: 'Percentage',
    icon: Cpu,
    iconClass: 'text-chart-1',
    seed: `${resource.id}-cpu`,
    yDomain: PCT_DOMAIN,
    formatValue: formatPercentValue,
    formatYTick: formatPercentTick,
    noData: resource.telemetry.cpuPct === null,
    series: [{key: 'CPU', current: resource.telemetry.cpuPct ?? 0, max: 100}],
  }
}

function memChartSpec(resource: Resource): MetricChartSpec {
  return {
    title: 'Memory utilization',
    subtitle: 'Percentage',
    icon: MemoryStick,
    iconClass: 'text-chart-2',
    seed: `${resource.id}-mem`,
    yDomain: PCT_DOMAIN,
    formatValue: formatPercentValue,
    formatYTick: formatPercentTick,
    noData: resource.telemetry.memPct === null,
    series: [{key: 'Memory', current: resource.telemetry.memPct ?? 0, max: 100}],
  }
}

function diskChartSpec(resource: Resource, infra: HostInfraSample): MetricChartSpec {
  return {
    title: 'Disk usage',
    subtitle: 'Percentage',
    icon: HardDrive,
    iconClass: 'text-chart-3',
    seed: `${resource.id}-disk`,
    yDomain: PCT_DOMAIN,
    formatValue: formatPercentValue,
    formatYTick: formatPercentTick,
    series: [{key: 'Disk', current: infra.diskPct, max: 100, spread: 0.12}],
  }
}

function loadChartSpec(resource: Resource, infra: HostInfraSample): MetricChartSpec {
  return {
    title: 'Load average',
    subtitle: '1m · 5m · 15m',
    icon: Activity,
    iconClass: 'text-chart-4',
    seed: `${resource.id}-load`,
    formatValue: formatLoadValue,
    series: [
      {key: '1 min', current: infra.load1},
      {key: '5 min', current: infra.load5, spread: 0.25},
      {key: '15 min', current: infra.load15, spread: 0.18},
    ],
  }
}

function networkChartSpec(resource: Resource, infra: HostInfraSample): MetricChartSpec {
  return {
    title: 'Network throughput',
    subtitle: 'Received · sent',
    icon: Network,
    iconClass: 'text-chart-2',
    seed: `${resource.id}-net`,
    formatValue: formatBytesRate,
    formatYTick: formatBytesShort,
    series: [
      {key: 'Received', current: infra.netRecvBytes},
      {key: 'Sent', current: infra.netSentBytes, spread: 0.5},
    ],
  }
}

function latencyChartSpec(resource: Resource): MetricChartSpec {
  return {
    title: 'p99 latency',
    subtitle: 'Milliseconds',
    icon: Activity,
    iconClass: 'text-chart-5',
    seed: `${resource.id}-latency`,
    formatValue: formatMsValue,
    formatYTick: formatRoundTick,
    series: [{key: 'p99', current: resource.telemetry.latencyMs ?? 0}],
  }
}

function errorChartSpec(resource: Resource): MetricChartSpec {
  return {
    title: 'Error rate',
    subtitle: 'Percentage',
    icon: AlertTriangle,
    iconClass: 'text-danger-fg',
    seed: `${resource.id}-error`,
    formatValue: formatPercentValue,
    formatYTick: formatPercentTick,
    series: [{key: 'Errors', current: resource.telemetry.errorRatePct ?? 0}],
  }
}

/** All charts a resource can show — only metrics that actually have a value. */
function buildTelemetryCharts(resource: Resource, infra: HostInfraSample | null): MetricChartSpec[] {
  const t = resource.telemetry
  const specs: MetricChartSpec[] = []
  if (t.cpuPct !== null) specs.push(cpuChartSpec(resource))
  if (t.memPct !== null) specs.push(memChartSpec(resource))
  if (infra) specs.push(diskChartSpec(resource, infra), loadChartSpec(resource, infra), networkChartSpec(resource, infra))
  if (t.latencyMs !== undefined) specs.push(latencyChartSpec(resource))
  if (t.errorRatePct !== undefined) specs.push(errorChartSpec(resource))
  return specs
}

/**
 * Overview trends: CPU and Memory always lead (shown as "No data" when a resource
 * reports no infra metrics), then p99 latency / error rate when present.
 */
function buildOverviewCharts(resource: Resource): MetricChartSpec[] {
  const t = resource.telemetry
  const specs: MetricChartSpec[] = [cpuChartSpec(resource), memChartSpec(resource)]
  if (t.latencyMs !== undefined) specs.push(latencyChartSpec(resource))
  if (t.errorRatePct !== undefined) specs.push(errorChartSpec(resource))
  return specs
}

const TELEMETRY_RANGES = [
  {value: '1h', label: '1h', seconds: 3600},
  {value: '6h', label: '6h', seconds: 21600},
  {value: '24h', label: '24h', seconds: 86400},
  {value: '7d', label: '7d', seconds: 604800},
  {value: '30d', label: '30d', seconds: 2592000},
] as const
type TelemetryRange = (typeof TELEMETRY_RANGES)[number]['value']

function RangeToggle({value, onChange}: {readonly value: TelemetryRange; readonly onChange: (value: TelemetryRange) => void}) {
  return (
    <div className="inline-flex items-center gap-0.5 rounded-md border bg-background p-0.5">
      {TELEMETRY_RANGES.map((range) => (
        <button
          key={range.value}
          type="button"
          onClick={() => onChange(range.value)}
          aria-pressed={value === range.value}
          className={cn(
            'rounded px-2 py-0.5 text-[11px] font-medium transition-colors',
            value === range.value ? 'bg-muted text-foreground' : 'text-muted-foreground hover:text-foreground',
          )}
        >
          {range.label}
        </button>
      ))}
    </div>
  )
}

function OverviewTab({resource, timezone}: {readonly resource: Resource; readonly timezone: string}) {
  const t = resource.telemetry
  const [range, setRange] = useState<TelemetryRange>('24h')
  const infra = hostInfraSample(resource)
  const charts = buildOverviewCharts(resource)
  const rangeSeconds = TELEMETRY_RANGES.find((option) => option.value === range)?.seconds ?? 86_400
  return (
    <div className="space-y-3">
      {(infra || t.latencyMs !== undefined || t.errorRatePct !== undefined) && (
        <div className="grid grid-cols-2 gap-2">
          {infra ? (
            <>
              <MetricTile label="Disk" value={`${infra.diskPct}`} unit="%" icon={HardDrive} pct={infra.diskPct} tone={utilTone(infra.diskPct)} />
              <MetricTile label="Load (1m)" value={infra.load1.toFixed(2)} icon={Activity} tone="info" />
            </>
          ) : (
            <>
              {t.latencyMs !== undefined && (
                <MetricTile label="p99 latency" value={`${t.latencyMs}`} unit="ms" icon={Activity} tone="info" />
              )}
              {t.errorRatePct !== undefined && (
                <MetricTile
                  label="Error rate"
                  value={`${t.errorRatePct}`}
                  unit="%"
                  icon={AlertTriangle}
                  tone={errorRateTone(t.errorRatePct)}
                />
              )}
            </>
          )}
        </div>
      )}
      <div className="space-y-2">
        <div className="flex items-center justify-between gap-2">
          <span className="text-[11px] font-semibold uppercase tracking-wide text-muted-foreground">Performance</span>
          <RangeToggle value={range} onChange={setRange} />
        </div>
        <div className="grid gap-2 sm:grid-cols-2">
          {charts.map((spec) => (
            <MetricChart key={spec.title} {...spec} rangeSeconds={rangeSeconds} timezone={timezone} height={150} />
          ))}
        </div>
      </div>
      <div className={cn('grid gap-3', resource.metadata.length > 0 && 'lg:grid-cols-2')}>
        <PanelSection title="Identity">
          <FieldRow label="Type" value={KIND_META[resource.kind].label} />
          <FieldRow
            label="Environment"
            value={
              <Badge variant={ENV_BADGE[resource.environment]} className="px-1.5 py-0 text-[10px] leading-4">
                {ENV_LABEL[resource.environment]}
              </Badge>
            }
          />
          <FieldRow
            label="Region"
            value={
              <span className="inline-flex items-center gap-1">
                <MapPin className="h-3 w-3 text-muted-foreground" />
                {resource.region}
              </span>
            }
          />
          <FieldRow label="Provider" value={CLOUD_LABEL[resource.cloud]} />
          <FieldRow label="Owner" value={resource.owner ? resource.owner.team : <span className="text-warning-fg">Unowned</span>} />
          <FieldRow label="First seen" value={relTime(resource.firstSeen)} />
          <FieldRow label="Last change" value={relTime(resource.lastChange)} />
        </PanelSection>
        {resource.metadata.length > 0 && (
          <PanelSection title="Metadata">
            {resource.metadata.map((m) => (
              <FieldRow key={m.label} label={m.label} value={m.value} />
            ))}
          </PanelSection>
        )}
      </div>
      {resource.tags.length > 0 && (
        <PanelSection title="Tags">
          <div className="flex flex-wrap gap-1">
            {resource.tags.map((tag) => (
              <TagChip key={tag} label={tag} />
            ))}
          </div>
        </PanelSection>
      )}
    </div>
  )
}

function TelemetryTab({resource, timezone}: {readonly resource: Resource; readonly timezone: string}) {
  const t = resource.telemetry
  const [range, setRange] = useState<TelemetryRange>('24h')
  const rangeSeconds = TELEMETRY_RANGES.find((option) => option.value === range)?.seconds ?? 86_400
  const metricsHostId = getMetricsHostId(resource)
  const infra = hostInfraSample(resource)
  const charts = buildTelemetryCharts(resource, infra)
  const hasAnyTelemetry = charts.length > 0 || t.throughput !== undefined
  return (
    <div className="space-y-3">
      <div className="flex items-center justify-between gap-2">
        <RangeToggle value={range} onChange={setRange} />
        {metricsHostId && (
          <Button type="button" variant="ghost" size="sm" className="h-7 gap-1 text-xs" asChild>
            <Link to="/monitoring/hosts/$hostId" params={{hostId: metricsHostId}}>
              Open in Metrics <ExternalLink className="h-3 w-3" />
            </Link>
          </Button>
        )}
      </div>
      {hasAnyTelemetry ? (
        <>
          {charts.length > 0 && (
            <div className="grid grid-cols-1 gap-2 lg:grid-cols-2">
              {charts.map((spec) => (
                <MetricChart key={spec.title} {...spec} rangeSeconds={rangeSeconds} timezone={timezone} />
              ))}
            </div>
          )}
          {t.throughput !== undefined && (
            <div className="flex items-center justify-between rounded-md border border-border/70 bg-background/40 px-3 py-2.5">
              <span className="inline-flex items-center gap-1.5 text-xs text-muted-foreground">
                <Activity className="h-3.5 w-3.5" /> Throughput
              </span>
              <span className="text-sm font-semibold tabular-nums">{t.throughput}</span>
            </div>
          )}
        </>
      ) : (
        <EmptyState
          icon={Activity}
          title="No telemetry data"
          description="CPU, memory, latency, and throughput samples have not been received for this resource yet."
          className="py-10"
        />
      )}
    </div>
  )
}

function RelationshipsTab({resource, onSelect}: {readonly resource: Resource; readonly onSelect: (id: string) => void}) {
  if (resource.relationships.length === 0) {
    return <EmptyState icon={Share2} title="No mapped relationships" description="This resource has no discovered dependencies or connections yet." />
  }
  const groups = new Map<string, Relationship[]>()
  for (const rel of resource.relationships) {
    const list = groups.get(rel.relation) ?? []
    list.push(rel)
    groups.set(rel.relation, list)
  }
  return (
    <div className="space-y-3">
      <p className="text-xs text-muted-foreground">Click a related resource to pivot the catalog to it.</p>
      {[...groups.entries()].map(([relation, rels]) => (
        <PanelSection key={relation} title={relation}>
          <div className="space-y-0.5">
            {rels.map((rel) => {
              const pivotable = rel.targetId !== undefined
              return (
                <button
                  key={`${relation}-${rel.name}`}
                  type="button"
                  disabled={!pivotable}
                  onClick={() => rel.targetId && onSelect(rel.targetId)}
                  className={cn(
                    'flex w-full items-center gap-2 rounded px-2 py-1.5 text-left text-xs',
                    pivotable ? 'hover:bg-muted/60' : 'cursor-default opacity-80',
                  )}
                >
                  <KindIcon kind={rel.kind} className="h-3.5 w-3.5" />
                  <StatusDot tone={HEALTH_TONE[rel.health]} size="sm" />
                  <span className="min-w-0 flex-1 truncate font-medium">{rel.name}</span>
                  <span className="shrink-0 text-[11px] text-muted-foreground">{KIND_META[rel.kind].label}</span>
                  {pivotable && <ChevronRight className="h-3 w-3 shrink-0 text-muted-foreground" />}
                </button>
              )
            })}
          </div>
        </PanelSection>
      ))}
    </div>
  )
}

function OwnershipTab({resource}: {readonly resource: Resource}) {
  const owner = resource.owner
  return (
    <div className="space-y-3">
      {owner ? (
        <PanelSection title="Ownership">
          <FieldRow label="Team" value={owner.team} />
          <FieldRow
            label="On-call"
            value={
              <span className="inline-flex items-center gap-1">
                <Users className="h-3 w-3 text-muted-foreground" />
                {owner.oncall}
              </span>
            }
          />
          <FieldRow
            label="Slack"
            value={
              <span className="inline-flex items-center gap-1">
                <Hash className="h-3 w-3 text-muted-foreground" />
                {owner.slack.replace(/^#/, '')}
              </span>
            }
          />
          <FieldRow
            label="Repository"
            value={
              <span className="inline-flex items-center gap-1">
                <GitBranch className="h-3 w-3 text-muted-foreground" />
                {owner.repo}
              </span>
            }
          />
        </PanelSection>
      ) : (
        <EmptyState
          icon={Users}
          title="No owner assigned"
          description="This resource has no team, on-call, or escalation path. Unowned resources are a common source of orphaned cost and slow incident response."
          action={
            <Button type="button" size="sm" className="gap-1" asChild>
              <Link to="/settings" search={{tab: 'team'}}>
                <Plus className="h-3.5 w-3.5" /> Claim ownership
              </Link>
            </Button>
          }
        />
      )}
      <PanelSection title={`Tags (${resource.tags.length})`}>
        {resource.tags.length > 0 ? (
          <div className="flex flex-wrap gap-1">
            {resource.tags.map((tag) => (
              <TagChip key={tag} label={tag} />
            ))}
          </div>
        ) : (
          <p className="text-xs text-muted-foreground">No tags.</p>
        )}
      </PanelSection>
    </div>
  )
}

function SecurityTab({resource}: {readonly resource: Resource}) {
  const total = totalVulns(resource.vulns)
  const findings = useMemo(() => mockFindings(resource), [resource])
  return (
    <div className="space-y-3">
      <PanelSection title="Vulnerabilities">
        {total === 0 ? (
          <div className="flex items-center gap-2 text-xs text-success-fg">
            <ShieldCheck className="h-4 w-4" /> No open vulnerabilities
          </div>
        ) : (
          <>
            <div className="mb-2 flex items-baseline gap-2">
              <span className="text-2xl font-semibold tabular-nums">{total}</span>
              <span className="text-xs text-muted-foreground">open findings</span>
            </div>
            <VulnBar vulns={resource.vulns} />
            <div className="mt-2 flex flex-wrap gap-1.5">
              {VULN_SEVERITIES.map((s) => (
                <Badge key={s} variant={VULN_BADGE[s]} className="px-1.5 py-0 text-[10px] leading-4">
                  {resource.vulns[s]} {VULN_LABEL[s]}
                </Badge>
              ))}
            </div>
          </>
        )}
      </PanelSection>
      {findings.length > 0 && (
        <PanelSection title="Top findings">
          <div className="space-y-0.5">
            {findings.map((f) => (
              <div key={f.id} className="flex items-center gap-2 rounded px-1 py-1 text-xs">
                <span className={cn('h-2 w-2 shrink-0 rounded-full', VULN_BAR[f.severity])} />
                <span className="shrink-0 font-mono text-[11px] text-muted-foreground">{f.id}</span>
                <span className="min-w-0 flex-1 truncate">
                  {f.title} in <span className="font-medium">{f.pkg}</span>
                </span>
                <Badge variant={VULN_BADGE[f.severity]} className="shrink-0 px-1.5 py-0 text-[10px] leading-4">
                  {VULN_LABEL[f.severity]}
                </Badge>
              </div>
            ))}
          </div>
        </PanelSection>
      )}
      <div className="grid grid-cols-2 gap-2">
        <PanelSection title="SBOM">
          <div className="flex items-baseline gap-1">
            <span className="text-lg font-semibold tabular-nums">{resource.sbomComponents.toLocaleString('en-US')}</span>
            <span className="text-[11px] text-muted-foreground">components</span>
          </div>
        </PanelSection>
        <PanelSection title="Posture">
          <div className="flex items-baseline gap-1">
            <span className="text-lg font-semibold tabular-nums">
              {resource.posture.filter((p) => p.pass).length}/{resource.posture.length}
            </span>
            <span className="text-[11px] text-muted-foreground">checks pass</span>
          </div>
        </PanelSection>
      </div>
      {resource.posture.length > 0 && (
        <PanelSection title="Posture checks">
          <div className="space-y-0.5">
            {resource.posture.map((p) => (
              <div key={p.label} className="flex items-center gap-2 py-0.5 text-xs">
                {p.pass ? <ShieldCheck className="h-3.5 w-3.5 text-success-fg" /> : <ShieldAlert className="h-3.5 w-3.5 text-danger-fg" />}
                <span className={cn('flex-1', !p.pass && 'text-danger-fg')}>{p.label}</span>
                <span className="text-[11px] text-muted-foreground">{p.pass ? 'Pass' : 'Fail'}</span>
              </div>
            ))}
          </div>
        </PanelSection>
      )}
    </div>
  )
}

function CostTab({resource}: {readonly resource: Resource}) {
  const trendUp = resource.costTrendPct > 0
  const breakdownTotal = resource.costBreakdown.reduce((sum, c) => sum + c.usd, 0)
  const months = useMemo(
    () => sparkline(`${resource.id}-cost6`, 6, resource.monthlyUsd || 50, (resource.monthlyUsd || 50) * 0.22),
    [resource],
  )
  const maxMonth = Math.max(...months, 1)
  const lowUtil =
    resource.telemetry.cpuPct != null &&
    resource.telemetry.cpuPct > 0 &&
    resource.telemetry.cpuPct < 35 &&
    resource.monthlyUsd >= 120
  return (
    <div className="space-y-3">
      <PanelSection title="Estimated monthly cost">
        <div className="flex items-end justify-between">
          <div className="flex items-baseline gap-2">
            <span className="text-3xl font-semibold tabular-nums">{formatUsdExact(resource.monthlyUsd)}</span>
            <span className="text-xs text-muted-foreground">/mo</span>
          </div>
          {resource.costTrendPct !== 0 && (
            <span className={cn('inline-flex items-center gap-1 text-xs font-medium', trendUp ? 'text-danger-fg' : 'text-success-fg')}>
              {trendUp ? <TrendingUp className="h-3.5 w-3.5" /> : <TrendingDown className="h-3.5 w-3.5" />}
              {formatPct(resource.costTrendPct)} vs last month
            </span>
          )}
        </div>
        <div className="mt-3 flex h-14 items-end gap-1">
          {months.map((m, i) => (
            <div key={COST_MONTH_KEYS[i]} className="flex flex-1 flex-col items-center gap-1">
              <div
                className={cn('w-full rounded-sm', i === months.length - 1 ? 'bg-primary' : 'bg-primary/30')}
                style={{height: `${Math.max(6, (m / maxMonth) * 100)}%`}}
              />
            </div>
          ))}
        </div>
        <p className="mt-1 text-center text-[10px] text-muted-foreground">Last 6 months</p>
      </PanelSection>
      {resource.costBreakdown.length > 0 && (
        <PanelSection title="Breakdown">
          <div className="space-y-2">
            {resource.costBreakdown.map((c) => (
              <div key={c.label}>
                <div className="flex items-baseline justify-between text-xs">
                  <span className="text-muted-foreground">{c.label}</span>
                  <span className="font-medium tabular-nums">{formatUsdExact(c.usd)}</span>
                </div>
                <div className="mt-1 h-1.5 w-full overflow-hidden rounded-full bg-muted">
                  <div className="h-full rounded-full bg-primary/70" style={{width: `${breakdownTotal > 0 ? (c.usd / breakdownTotal) * 100 : 0}%`}} />
                </div>
              </div>
            ))}
          </div>
        </PanelSection>
      )}
      {lowUtil && (
        <div className="flex items-start gap-2 rounded-md border border-warning-border bg-warning-bg p-3 text-xs text-warning-fg">
          <Lightbulb className="mt-0.5 h-4 w-4 shrink-0" />
          <span>
            CPU has averaged under 35%. Rightsizing could save an estimated{' '}
            <span className="font-semibold">{formatUsd(Math.round(resource.monthlyUsd * 0.35))}/mo</span>.
          </span>
        </div>
      )}
    </div>
  )
}

function ChangesTab({resource}: {readonly resource: Resource}) {
  if (resource.changes.length === 0) {
    return <EmptyState icon={Clock} title="No recent changes" description="No deploys, config edits, scaling, or incidents recorded in the last 30 days." />
  }
  return (
    <div className="space-y-1">
      {resource.changes.map((c, i) => {
        const Icon = CHANGE_ICON[c.kind]
        return (
          <div key={`${c.ts}-${i}`} className="flex gap-2.5 rounded-md px-1 py-2">
            <div className={cn('mt-0.5 flex h-6 w-6 shrink-0 items-center justify-center rounded-full bg-muted', TONE_TEXT[CHANGE_TONE[c.kind]])}>
              <Icon className="h-3.5 w-3.5" />
            </div>
            <div className="min-w-0 flex-1">
              <p className="text-xs font-medium">{c.summary}</p>
              <p className="mt-0.5 text-[11px] text-muted-foreground">
                {c.actor} · {relTime(c.ts)}
              </p>
            </div>
            <Badge variant="neutral" className="h-fit shrink-0 px-1.5 py-0 text-[10px] capitalize leading-4">
              {c.kind}
            </Badge>
          </div>
        )
      })}
    </div>
  )
}

/** Cross-link out to a specialized view for this resource. */
function QuickLinks() {
  const base =
    'inline-flex h-7 items-center gap-1 rounded-md border px-2 text-[11px] text-muted-foreground transition-colors hover:bg-accent hover:text-foreground'
  return (
    <div className="mt-2 flex flex-wrap gap-1">
      <Link to="/logs" className={base}>
        <ScrollText className="h-3 w-3" /> Logs
      </Link>
      <Link to="/performance/traces" className={base}>
        <GitBranch className="h-3 w-3" /> Traces
      </Link>
      <Link to="/dashboards" className={base}>
        <LayoutDashboard className="h-3 w-3" /> Dashboards
      </Link>
    </div>
  )
}

export interface ResourceDetailPanelProps {
  readonly resource: Resource
  /** Pivot to a related resource (relationship clicks). */
  readonly onSelect: (id: string) => void
  /** Close affordance; omit to hide the close button (e.g. embedded dock). */
  readonly onClose?: () => void
  readonly className?: string
}

/** Tabbed detail panel for a single resource — shared by the catalog and map. */
export function ResourceDetailPanel({resource, onSelect, onClose, className}: ResourceDetailPanelProps) {
  const [tab, setTab] = useState<DetailTab>('overview')
  const {timezone} = useTimezone()
  return (
    <section className={cn('flex h-full min-h-0 flex-col overflow-hidden rounded-lg border bg-card', className)}>
      <header className="border-b px-3 py-2.5">
        <div className="flex items-start justify-between gap-2">
          <div className="flex min-w-0 items-start gap-2.5">
            <span className="mt-0.5 flex h-8 w-8 shrink-0 items-center justify-center rounded-md bg-muted">
              <KindIcon kind={resource.kind} />
            </span>
            <div className="min-w-0">
              <h3 className="truncate text-sm font-semibold">{resource.name}</h3>
              <div className="mt-1 flex flex-wrap items-center gap-1.5">
                <HealthBadge health={resource.health} />
                <Badge variant="neutral" className="px-1.5 py-0 text-[10px] leading-4">
                  {KIND_META[resource.kind].label}
                </Badge>
                <Badge variant={ENV_BADGE[resource.environment]} className="px-1.5 py-0 text-[10px] leading-4">
                  {ENV_LABEL[resource.environment]}
                </Badge>
                <span className="text-[11px] text-muted-foreground">
                  {CLOUD_LABEL[resource.cloud]} · {resource.region}
                </span>
              </div>
            </div>
          </div>
          {onClose && (
            <Button type="button" variant="ghost" size="icon" className="h-7 w-7 shrink-0" onClick={onClose} aria-label="Close detail panel">
              <X className="h-4 w-4" />
            </Button>
          )}
        </div>
        <QuickLinks />
      </header>
      <Tabs value={tab} onValueChange={(v) => setTab(v as DetailTab)} className="flex min-h-0 flex-1 flex-col">
        <div className="border-b px-2">
          <TabsList className="h-auto w-full justify-start gap-0.5 overflow-x-auto bg-transparent p-0">
            {DETAIL_TABS.map((t) => (
              <TabsTrigger
                key={t.id}
                value={t.id}
                className="shrink-0 rounded-none border-b-2 border-transparent px-2.5 py-2 text-xs data-[state=active]:border-primary data-[state=active]:bg-transparent data-[state=active]:text-primary data-[state=active]:shadow-none"
              >
                {t.label}
              </TabsTrigger>
            ))}
          </TabsList>
        </div>
        <div className="min-h-0 flex-1 overflow-y-auto p-3">
          <TabsContent value="overview" className="mt-0">
            <OverviewTab resource={resource} timezone={timezone} />
          </TabsContent>
          <TabsContent value="telemetry" className="mt-0">
            <TelemetryTab resource={resource} timezone={timezone} />
          </TabsContent>
          <TabsContent value="relationships" className="mt-0">
            <RelationshipsTab resource={resource} onSelect={onSelect} />
          </TabsContent>
          <TabsContent value="ownership" className="mt-0">
            <OwnershipTab resource={resource} />
          </TabsContent>
          <TabsContent value="security" className="mt-0">
            <SecurityTab resource={resource} />
          </TabsContent>
          <TabsContent value="cost" className="mt-0">
            <CostTab resource={resource} />
          </TabsContent>
          <TabsContent value="changes" className="mt-0">
            <ChangesTab resource={resource} />
          </TabsContent>
        </div>
      </Tabs>
    </section>
  )
}
