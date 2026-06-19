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

import {useState, type ComponentType, type ReactNode} from 'react'
import {Link} from '@tanstack/react-router'
import {
  Activity,
  AlertTriangle,
  ChevronRight,
  Clock,
  Coins,
  Cpu,
  ExternalLink,
  GitBranch,
  Hash,
  HardDrive,
  LayoutDashboard,
  MapPin,
  MemoryStick,
  Network,
  Plus,
  ScrollText,
  Share2,
  ShieldAlert,
  ShieldCheck,
  Trash2,
  TrendingDown,
  TrendingUp,
  Users,
  X,
} from 'lucide-react'

import {cn} from '@/lib/utils'
import type {OrganizationTeam} from '@/lib/api'
import {Badge} from '@/components/ui/badge'
import {Button} from '@/components/ui/button'
import {EmptyState} from '@/components/ui/empty-state'
import {Select, SelectContent, SelectItem, SelectTrigger, SelectValue} from '@/components/ui/select'
import {StatusDot} from '@/components/ui/status-dot'
import {Tabs, TabsContent, TabsList, TabsTrigger} from '@/components/ui/tabs'
import {useOrganizationTeams} from '@/hooks/useOrganizationTeams'
import {useTimezone} from '@/hooks/useTimezone'
import {HealthBadge, KindIcon, TagChip, VulnBar} from './CatalogPrimitives'
import {MetricChart, type MetricChartProps} from './CatalogCharts'
import {
  CHANGE_ICON,
  CHANGE_TONE,
  CLOUD_LABEL,
  ENV_BADGE,
  ENV_LABEL,
  HEALTH_TONE,
  KIND_META,
  TELEMETRY_KINDS,
  TONE_TEXT,
  VULN_BADGE,
  VULN_BAR,
  VULN_LABEL,
  VULN_SEVERITIES,
  formatPct,
  formatUsdExact,
  isOwnershipForbidden,
  relTime,
  totalVulns,
  useClaimOwnership,
  useDeleteOwnership,
  useResourceTelemetry,
  type Relationship,
  type Resource,
  type TelemetryMetric,
} from './resourceCatalogData'

type DetailTab = 'overview' | 'relationships' | 'ownership' | 'security' | 'cost' | 'changes'

type OwnershipClaimError = {
  readonly className: string
  readonly message: string
}

const DETAIL_TABS: readonly {readonly id: DetailTab; readonly label: string}[] = [
  {id: 'overview', label: 'Overview'},
  {id: 'relationships', label: 'Relationships'},
  {id: 'ownership', label: 'Ownership & Tags'},
  {id: 'security', label: 'Security'},
  {id: 'cost', label: 'Cost'},
  {id: 'changes', label: 'Changes'},
]

const HOST_RESOURCE_ID_PATTERN = /^host:(?:\d+:)?(\d+)$/

function getMetricsHostId(resource: Resource): string | null {
  if (resource.kind !== 'host') return null
  const result = HOST_RESOURCE_ID_PATTERN.exec(resource.id)
  return result?.[1] ?? null
}

function getOwnershipClaimError(error: unknown, isError: boolean): OwnershipClaimError | null {
  if (isOwnershipForbidden(error)) {
    return {
      className: 'text-[11px] text-warning-fg',
      message: 'Resource ownership is available on paid plans.',
    }
  }
  if (isError) {
    return {
      className: 'text-[11px] text-danger-fg',
      message: 'Could not save ownership. Try again.',
    }
  }
  return null
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

// ── Over-time charts ─────────────────────────────────────────────────────────
// Each telemetry metric from the backend maps to one chart. Series carry real
// sampled points over the selected range — there is no synthesized history.

type MetricChartSpec = Pick<
  MetricChartProps,
  'title' | 'subtitle' | 'icon' | 'iconClass' | 'lines' | 'yDomain' | 'formatValue' | 'formatYTick'
>

const PCT_DOMAIN = [0, 100] as const

const METRIC_ICON: Record<string, ComponentType<{className?: string}>> = {
  cpu: Cpu,
  mem: MemoryStick,
  disk: HardDrive,
  load: Activity,
  network: Network,
  latency: Activity,
  throughput: Activity,
  errorRate: AlertTriangle,
}

const METRIC_ICON_CLASS: Record<string, string> = {
  cpu: 'text-chart-1',
  mem: 'text-chart-2',
  disk: 'text-chart-3',
  load: 'text-chart-4',
  network: 'text-chart-2',
  latency: 'text-chart-5',
  throughput: 'text-chart-1',
  errorRate: 'text-danger-fg',
}

function formatBytesShort(bytes: number): string {
  if (bytes <= 0) return '0'
  const units = ['B', 'KB', 'MB', 'GB', 'TB']
  const i = Math.min(units.length - 1, Math.floor(Math.log(bytes) / Math.log(1024)))
  return `${(bytes / 1024 ** i).toFixed(i === 0 ? 0 : 1)}${units[i]}`
}

function unitFormatters(unit: string): {value: (v: number) => string; tick?: (v: number) => string} {
  switch (unit) {
    case '%':
      return {value: (v) => `${v.toFixed(1)}%`, tick: (v) => `${Math.round(v)}`}
    case 'ms':
      return {value: (v) => `${Math.round(v)} ms`, tick: (v) => `${Math.round(v)}`}
    case 'req/s':
      return {value: (v) => `${v.toFixed(2)}/s`, tick: (v) => v.toFixed(1)}
    case 'bytes/s':
      return {value: (v) => `${formatBytesShort(v)}/s`, tick: formatBytesShort}
    default:
      return {value: (v) => v.toFixed(2)}
  }
}

/** Map one backend telemetry metric to MetricChart props. */
function metricChartProps(metric: TelemetryMetric): MetricChartSpec {
  const fmt = unitFormatters(metric.unit)
  return {
    title: metric.label,
    subtitle: metric.unit || undefined,
    icon: METRIC_ICON[metric.key] ?? Activity,
    iconClass: METRIC_ICON_CLASS[metric.key],
    lines: metric.lines,
    yDomain: metric.unit === '%' ? PCT_DOMAIN : undefined,
    formatValue: fmt.value,
    formatYTick: fmt.tick,
  }
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

function OverviewTab({
  resource,
  timezone,
  range,
  onRangeChange,
}: {
  readonly resource: Resource
  readonly timezone: string
  readonly range: TelemetryRange
  readonly onRangeChange: (value: TelemetryRange) => void
}) {
  const rangeSeconds = TELEMETRY_RANGES.find((option) => option.value === range)?.seconds ?? 86_400
  const telemetryQuery = useResourceTelemetry(resource, rangeSeconds)
  const metrics = telemetryQuery.data?.metrics ?? []
  const metricsHostId = getMetricsHostId(resource)
  const supportsTelemetry = TELEMETRY_KINDS.has(resource.kind)
  let telemetryContent: ReactNode
  if (telemetryQuery.isLoading) {
    telemetryContent = (
      <div className="grid gap-2 sm:grid-cols-2">
        {['a', 'b'].map((key) => (
          <div key={key} className="h-[150px] animate-pulse rounded-md border border-border/70 bg-muted/20" />
        ))}
      </div>
    )
  } else if (telemetryQuery.isError) {
    telemetryContent = (
      <EmptyState
        icon={AlertTriangle}
        title="Could not load telemetry"
        description="Telemetry is temporarily unavailable. Try again shortly."
        className="py-8"
      />
    )
  } else if (metrics.length > 0) {
    telemetryContent = (
      <div className="grid gap-2 sm:grid-cols-2">
        {metrics.map((metric) => (
          <MetricChart
            key={metric.key}
            {...metricChartProps(metric)}
            rangeSeconds={rangeSeconds}
            timezone={timezone}
            height={150}
          />
        ))}
      </div>
    )
  } else {
    telemetryContent = (
      <EmptyState
        icon={Activity}
        title="No telemetry data"
        description="No samples have been received for this resource in the selected range."
        className="py-8"
      />
    )
  }

  return (
    <div className="space-y-3">
      {supportsTelemetry && (
        <div className="space-y-2">
          <div className="flex items-center justify-between gap-2">
            <span className="text-[11px] font-semibold uppercase tracking-wide text-muted-foreground">Performance</span>
            <div className="flex items-center gap-2">
              {metricsHostId && (
                <Button type="button" variant="ghost" size="sm" className="h-7 gap-1 text-xs" asChild>
                  <Link to="/monitoring/hosts/$hostId" params={{hostId: metricsHostId}}>
                    Open in Metrics <ExternalLink className="h-3 w-3" />
                  </Link>
                </Button>
              )}
              <RangeToggle value={range} onChange={onRangeChange} />
            </div>
          </div>
          {telemetryContent}
        </div>
      )}
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
          <FieldRow label="Owner" value={resource.owner ? resource.owner.teamName : <span className="text-warning-fg">Unowned</span>} />
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

// ── Ownership ────────────────────────────────────────────────────────────────
// The owner is an organization team. The Slack channel, repo, and current on-call
// person are carried on that team (the on-call person is resolved server-side from
// the team's primary schedule) — there is no per-resource on-call field. Teams are
// created and managed under On-call → Teams. Tags stay independent of ownership.

function OwnerSummary({owner}: {readonly owner: NonNullable<Resource['owner']>}) {
  return (
    <>
      <FieldRow label="Team" value={owner.teamName} />
      {owner.currentOnCall && (
        <FieldRow
          label="On-call now"
          value={
            <span className="inline-flex items-center gap-1.5">
              <StatusDot tone="success" size="sm" />
              {owner.currentOnCall.userName}
            </span>
          }
        />
      )}
      {owner.slack && (
        <FieldRow
          label="Slack"
          value={
            <span className="inline-flex items-center gap-1">
              <Hash className="h-3 w-3 text-muted-foreground" />
              {owner.slack.replace(/^#/, '')}
            </span>
          }
        />
      )}
      {owner.repo && (
        <FieldRow
          label="Repository"
          value={
            <span className="inline-flex items-center gap-1">
              <GitBranch className="h-3 w-3 text-muted-foreground" />
              {owner.repo}
            </span>
          }
        />
      )}
    </>
  )
}

/** Read-only preview of what assigning the selected team implies. */
function TeamPreview({team}: {readonly team: OrganizationTeam}) {
  return (
    <div className="space-y-0.5 rounded-md border border-border/70 bg-muted/20 px-2 py-1.5">
      <FieldRow
        label="On-call now"
        value={
          team.currentOnCall ? (
            <span className="inline-flex items-center gap-1.5">
              <StatusDot tone="success" size="sm" />
              {team.currentOnCall.userName}
            </span>
          ) : (
            <span className="text-muted-foreground">No active schedule</span>
          )
        }
      />
      {team.slack && <FieldRow label="Slack" value={team.slack.replace(/^#/, '')} />}
      {team.repo && <FieldRow label="Repository" value={team.repo} />}
    </div>
  )
}

function OwnershipEditor({
  resource,
  owner,
  onDone,
  onCancel,
}: {
  readonly resource: Resource
  readonly owner: Resource['owner']
  readonly onDone: () => void
  readonly onCancel: () => void
}) {
  const claim = useClaimOwnership()
  const teamsQuery = useOrganizationTeams()
  const teams = teamsQuery.data ?? []
  const [teamId, setTeamId] = useState<string>(owner?.teamId ?? '')
  const selectedTeam = teams.find((team) => team.id === teamId) ?? null

  const submit = () => {
    if (!teamId) return
    claim.mutate({resourceId: resource.id, teamId}, {onSuccess: onDone})
  }

  const claimError = getOwnershipClaimError(claim.error, claim.isError)
  const teamsForbidden = teamsQuery.isError && isOwnershipForbidden(teamsQuery.error)

  let body: ReactNode
  if (teamsQuery.isLoading) {
    body = <p className="text-xs text-muted-foreground">Loading teams…</p>
  } else if (teamsForbidden) {
    body = <p className="text-[11px] text-warning-fg">Team-based ownership is available on the Team plan and above.</p>
  } else if (teams.length === 0) {
    body = (
      <div className="space-y-2 text-xs text-muted-foreground">
        <p>No teams yet. Create a team to assign ownership.</p>
        <Button asChild type="button" size="sm" variant="outline" className="gap-1">
          <Link to="/on-call/teams">
            <Plus className="h-3.5 w-3.5" /> Create a team
          </Link>
        </Button>
      </div>
    )
  } else {
    body = (
      <div className="space-y-2">
        <label className="block space-y-1">
          <span className="text-[11px] text-muted-foreground">
            Team<span className="text-danger-fg"> *</span>
          </span>
          <Select value={teamId} onValueChange={setTeamId}>
            <SelectTrigger className="h-8 text-xs" aria-label="Owning team">
              <SelectValue placeholder="Select a team" />
            </SelectTrigger>
            <SelectContent>
              {teams.map((team) => (
                <SelectItem key={team.id} value={team.id}>
                  {team.name}
                </SelectItem>
              ))}
            </SelectContent>
          </Select>
        </label>
        {selectedTeam && <TeamPreview team={selectedTeam} />}
        {claimError && <p className={claimError.className}>{claimError.message}</p>}
        <div className="flex justify-end gap-2 pt-1">
          <Button type="button" size="sm" variant="ghost" onClick={onCancel}>
            Cancel
          </Button>
          <Button type="button" size="sm" disabled={teamId === '' || claim.isPending} onClick={submit}>
            {claim.isPending ? 'Saving…' : 'Save'}
          </Button>
        </div>
      </div>
    )
  }

  return <PanelSection title={owner ? 'Edit ownership' : 'Assign owner'}>{body}</PanelSection>
}

function OwnershipTab({resource}: {readonly resource: Resource}) {
  const owner = resource.owner
  const [editing, setEditing] = useState(false)
  const removeOwnership = useDeleteOwnership()

  let ownershipContent: ReactNode
  if (editing) {
    ownershipContent = (
      <OwnershipEditor
        resource={resource}
        owner={owner}
        onDone={() => setEditing(false)}
        onCancel={() => setEditing(false)}
      />
    )
  } else if (owner) {
    ownershipContent = (
      <PanelSection
        title="Ownership"
        action={
          <div className="flex items-center gap-1">
            <Button type="button" size="sm" variant="ghost" onClick={() => setEditing(true)}>
              Edit
            </Button>
            <Button
              type="button"
              size="icon"
              variant="ghost"
              className="h-7 w-7 text-muted-foreground hover:text-danger-fg"
              aria-label="Remove owner"
              disabled={removeOwnership.isPending}
              onClick={() => removeOwnership.mutate(resource.id)}
            >
              <Trash2 className="h-3.5 w-3.5" />
            </Button>
          </div>
        }
      >
        <OwnerSummary owner={owner} />
      </PanelSection>
    )
  } else {
    ownershipContent = (
      <EmptyState
        icon={Users}
        title="No owner assigned"
        description="This resource has no owning team, on-call rotation, or escalation path. Unowned resources are a common source of orphaned cost and slow incident response."
        action={
          <Button type="button" size="sm" className="gap-1" onClick={() => setEditing(true)}>
            <Plus className="h-3.5 w-3.5" /> Assign owner
          </Button>
        }
      />
    )
  }

  return (
    <div className="space-y-3">
      {ownershipContent}
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
  const findings = resource.findings
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
              <div key={`${f.id}-${f.pkg}`} className="flex items-center gap-2 rounded px-1 py-1 text-xs">
                <span className={cn('h-2 w-2 shrink-0 rounded-full', VULN_BAR[f.severity])} />
                <span className="shrink-0 font-mono text-[11px] text-muted-foreground">{f.id}</span>
                <span className="min-w-0 flex-1 truncate">
                  <span className="font-medium">{f.pkg}</span>
                  {f.fixedVersion ? <span className="text-muted-foreground"> · fix {f.fixedVersion}</span> : null}
                </span>
                {typeof f.cvss === 'number' ? (
                  <span className="shrink-0 tabular-nums text-[11px] text-muted-foreground">CVSS {f.cvss.toFixed(1)}</span>
                ) : null}
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
  // Cost is reported only for connected cloud resources; everything else is honestly empty.
  if (resource.monthlyUsd <= 0 && resource.costBreakdown.length === 0) {
    return <EmptyState icon={Coins} title="No cost data" description="Cost is reported for connected cloud resources." />
  }
  const trendUp = resource.costTrendPct > 0
  const breakdownTotal = resource.costBreakdown.reduce((sum, c) => sum + c.usd, 0)
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
  const [range, setRange] = useState<TelemetryRange>('24h')
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
            <OverviewTab resource={resource} timezone={timezone} range={range} onRangeChange={setRange} />
          </TabsContent>
          <TabsContent value="relationships" className="mt-0">
            <RelationshipsTab resource={resource} onSelect={onSelect} />
          </TabsContent>
          <TabsContent value="ownership" className="mt-0">
            <OwnershipTab key={resource.id} resource={resource} />
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
