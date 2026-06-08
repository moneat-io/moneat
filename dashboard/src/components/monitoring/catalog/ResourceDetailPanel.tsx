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
  LayoutDashboard,
  Lightbulb,
  MapPin,
  MemoryStick,
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
import {StatusDot} from '@/components/ui/status-dot'
import {Tabs, TabsContent, TabsList, TabsTrigger} from '@/components/ui/tabs'
import {HealthBadge, KindIcon, Meter, Sparkline, TagChip, VulnBar} from './CatalogPrimitives'
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
  mockFindings,
  relTime,
  sparkline,
  totalVulns,
  utilTone,
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
  seed,
  tone,
}: {
  readonly pct?: number
  readonly seed?: string
  readonly tone: 'success' | 'warning' | 'danger' | 'info' | 'neutral' | 'accent'
}) {
  if (pct !== undefined) {
    return (
      <div className="mt-1.5">
        <Meter value={pct} tone={tone} />
      </div>
    )
  }
  if (seed) {
    return <Sparkline seed={seed} tone={tone} className="mt-1 h-6 w-full" />
  }
  return null
}

function errorRateTone(rate: number): 'success' | 'warning' | 'danger' {
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
  seed,
}: {
  readonly label: string
  readonly value: string
  readonly unit?: string
  readonly icon: ComponentType<{className?: string}>
  readonly pct?: number
  readonly tone: 'success' | 'warning' | 'danger' | 'info' | 'neutral' | 'accent'
  readonly seed?: string
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
      {metricTileVisual({pct, seed, tone})}
    </div>
  )
}

function OverviewTab({resource}: {readonly resource: Resource}) {
  const t = resource.telemetry
  return (
    <div className="space-y-3">
      <div className="grid grid-cols-2 gap-2">
        <MetricTile label="CPU" value={`${t.cpuPct}`} unit="%" icon={Cpu} pct={t.cpuPct} tone={utilTone(t.cpuPct)} />
        <MetricTile label="Memory" value={`${t.memPct}`} unit="%" icon={MemoryStick} pct={t.memPct} tone={utilTone(t.memPct)} />
        {t.latencyMs !== undefined && (
          <MetricTile label="p99 latency" value={`${t.latencyMs}`} unit="ms" icon={Activity} tone="info" seed={`${resource.id}-lat`} />
        )}
        {t.errorRatePct !== undefined && (
          <MetricTile
            label="Error rate"
            value={`${t.errorRatePct}`}
            unit="%"
            icon={AlertTriangle}
            tone={errorRateTone(t.errorRatePct)}
            seed={`${resource.id}-err`}
          />
        )}
      </div>
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

function TelemetryChart({
  label,
  suffix,
  current,
  tone,
  seed,
}: {
  readonly label: string
  readonly suffix: string
  readonly current: number | string
  readonly tone: 'success' | 'warning' | 'danger' | 'info' | 'neutral' | 'accent'
  readonly seed: string
}) {
  return (
    <div className="rounded-md border border-border/70 bg-background/40 p-3">
      <div className="flex items-center justify-between">
        <span className="text-xs text-muted-foreground">{label}</span>
        <span className={cn('text-sm font-semibold tabular-nums', TONE_TEXT[tone])}>
          {current}
          {suffix}
        </span>
      </div>
      <Sparkline seed={seed} tone={tone} className="mt-2 h-12 w-full" />
    </div>
  )
}

function TelemetryTab({resource}: {readonly resource: Resource}) {
  const t = resource.telemetry
  const metricsHostId = getMetricsHostId(resource)
  return (
    <div className="space-y-3">
      <div className="flex items-center justify-between">
        <p className="text-xs text-muted-foreground">Last 1 hour · 1m resolution</p>
        {metricsHostId && (
          <Button type="button" variant="ghost" size="sm" className="h-7 gap-1 text-xs" asChild>
            <Link to="/monitoring/hosts/$hostId" params={{hostId: metricsHostId}}>
              Open in Metrics <ExternalLink className="h-3 w-3" />
            </Link>
          </Button>
        )}
      </div>
      <div className="grid grid-cols-1 gap-2">
        <TelemetryChart label="CPU utilization" suffix="%" current={t.cpuPct} tone={utilTone(t.cpuPct)} seed={`${resource.id}-cpu`} />
        <TelemetryChart label="Memory utilization" suffix="%" current={t.memPct} tone={utilTone(t.memPct)} seed={`${resource.id}-mem`} />
        {t.latencyMs !== undefined && (
          <TelemetryChart label="p99 latency" suffix=" ms" current={t.latencyMs} tone="info" seed={`${resource.id}-lat`} />
        )}
        {t.errorRatePct !== undefined && (
          <TelemetryChart
            label="Error rate"
            suffix="%"
            current={t.errorRatePct}
            tone={t.errorRatePct >= 2 ? 'danger' : 'warning'}
            seed={`${resource.id}-err`}
          />
        )}
        {t.throughput !== undefined && (
          <TelemetryChart label="Throughput" suffix="" current={t.throughput} tone="accent" seed={`${resource.id}-tput`} />
        )}
      </div>
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
            <Button type="button" size="sm" className="gap-1">
              <Plus className="h-3.5 w-3.5" /> Claim ownership
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
  const lowUtil = resource.telemetry.cpuPct > 0 && resource.telemetry.cpuPct < 35 && resource.monthlyUsd >= 120
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
            <OverviewTab resource={resource} />
          </TabsContent>
          <TabsContent value="telemetry" className="mt-0">
            <TelemetryTab resource={resource} />
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
