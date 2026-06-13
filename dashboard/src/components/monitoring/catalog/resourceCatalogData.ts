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
// Resource Catalog — data layer
//
// The unified infrastructure inventory model + the query hooks the UI binds to.
// The hooks are written against the eventual backend contract (see endpoint
// comments) but currently resolve an in-repo sample dataset, so the catalog is
// fully interactive today and switches to live data by swapping each fetcher's
// body for the documented request — no UI changes required.
// ─────────────────────────────────────────────────────────────────────────────

import {useQuery, type UseQueryResult} from '@tanstack/react-query'
import {
  AlertTriangle,
  ArrowUpDown,
  Box,
  Cloud,
  Hexagon,
  Rocket,
  Router as RouterIcon,
  Server,
  Share2,
  Wrench,
  type LucideIcon,
} from 'lucide-react'
import type {StatusTone} from '@/components/ui/status-dot'
import {api} from '@/lib/api'
import type {FacetFilter, FacetSchema} from '@/lib/filters/types'

// ── Domain types ─────────────────────────────────────────────────────────────

export type ResourceKind = 'service' | 'host' | 'pod' | 'container' | 'cloud' | 'network-device'
export type Health = 'healthy' | 'warn' | 'critical' | 'unknown'
export type Environment = 'prod' | 'staging' | 'dev'
export type CloudProvider = 'aws' | 'gcp' | 'azure' | 'on-prem'
export type VulnSeverity = 'critical' | 'high' | 'medium' | 'low'
export type ChangeKind = 'deploy' | 'config' | 'scale' | 'incident'

export interface Owner {
  readonly team: string
  readonly oncall: string
  readonly slack: string
  readonly repo: string
}

export type VulnCounts = Readonly<Record<VulnSeverity, number>>

export interface Relationship {
  readonly relation: string
  readonly name: string
  readonly kind: ResourceKind
  readonly health: Health
  readonly targetId?: string
}

export interface ChangeEvent {
  readonly ts: string
  readonly kind: ChangeKind
  readonly summary: string
  readonly actor: string
}

export interface CostItem {
  readonly label: string
  readonly usd: number
}

export interface Telemetry {
  readonly cpuPct: number | null
  readonly memPct: number | null
  readonly latencyMs?: number
  readonly errorRatePct?: number
  readonly throughput?: string
}

export interface MetaItem {
  readonly label: string
  readonly value: string
}

export interface Posture {
  readonly label: string
  readonly pass: boolean
}

export interface Resource {
  readonly id: string
  readonly name: string
  readonly kind: ResourceKind
  readonly health: Health
  readonly environment: Environment
  readonly region: string
  readonly cloud: CloudProvider
  readonly owner: Owner | null
  readonly tags: readonly string[]
  readonly telemetry: Telemetry
  readonly vulns: VulnCounts
  readonly sbomComponents: number
  readonly posture: readonly Posture[]
  readonly monthlyUsd: number
  readonly costTrendPct: number
  readonly costBreakdown: readonly CostItem[]
  readonly relationships: readonly Relationship[]
  readonly changes: readonly ChangeEvent[]
  readonly metadata: readonly MetaItem[]
  readonly firstSeen: string
  readonly lastChange: string
}

// ── Display maps ─────────────────────────────────────────────────────────────

export const HEALTH_TONE: Record<Health, StatusTone> = {
  healthy: 'success',
  warn: 'warning',
  critical: 'danger',
  unknown: 'neutral',
}
export const HEALTH_LABEL: Record<Health, string> = {
  healthy: 'Healthy',
  warn: 'Warning',
  critical: 'Critical',
  unknown: 'No data',
}
export const HEALTH_BADGE: Record<Health, 'success' | 'warning' | 'dangerSolid' | 'neutral'> = {
  healthy: 'success',
  warn: 'warning',
  critical: 'dangerSolid',
  unknown: 'neutral',
}

export const KIND_META: Record<
  ResourceKind,
  {readonly label: string; readonly plural: string; readonly icon: LucideIcon}
> = {
  service: {label: 'Service', plural: 'Services', icon: Share2},
  host: {label: 'Host', plural: 'Hosts', icon: Server},
  pod: {label: 'Pod', plural: 'Pods', icon: Hexagon},
  container: {label: 'Container', plural: 'Containers', icon: Box},
  cloud: {label: 'Cloud resource', plural: 'Cloud', icon: Cloud},
  'network-device': {label: 'Network device', plural: 'Network', icon: RouterIcon},
}

export const ENV_LABEL: Record<Environment, string> = {prod: 'Production', staging: 'Staging', dev: 'Development'}
export const ENV_BADGE: Record<Environment, 'danger' | 'warning' | 'info'> = {prod: 'danger', staging: 'warning', dev: 'info'}
export const CLOUD_LABEL: Record<CloudProvider, string> = {aws: 'AWS', gcp: 'GCP', azure: 'Azure', 'on-prem': 'On-prem'}

export const VULN_SEVERITIES: readonly VulnSeverity[] = ['critical', 'high', 'medium', 'low']
export const VULN_LABEL: Record<VulnSeverity, string> = {critical: 'Critical', high: 'High', medium: 'Medium', low: 'Low'}
export const VULN_BADGE: Record<VulnSeverity, 'dangerSolid' | 'danger' | 'warning' | 'info'> = {
  critical: 'dangerSolid',
  high: 'danger',
  medium: 'warning',
  low: 'info',
}
export const VULN_BAR: Record<VulnSeverity, string> = {
  critical: 'bg-danger-solid',
  high: 'bg-warning-solid',
  medium: 'bg-warning-solid/55',
  low: 'bg-info-solid',
}

export const TONE_TEXT: Record<StatusTone, string> = {
  success: 'text-success-fg',
  warning: 'text-warning-fg',
  danger: 'text-danger-fg',
  info: 'text-info-fg',
  neutral: 'text-muted-foreground',
  accent: 'text-primary',
}
export const TONE_BAR: Record<StatusTone, string> = {
  success: 'bg-success-solid',
  warning: 'bg-warning-solid',
  danger: 'bg-danger-solid',
  info: 'bg-info-solid',
  neutral: 'bg-muted-foreground',
  accent: 'bg-primary',
}

export const CHANGE_ICON: Record<ChangeKind, LucideIcon> = {
  deploy: Rocket,
  config: Wrench,
  scale: ArrowUpDown,
  incident: AlertTriangle,
}
export const CHANGE_TONE: Record<ChangeKind, StatusTone> = {
  deploy: 'info',
  config: 'neutral',
  scale: 'accent',
  incident: 'danger',
}

// ── Pure helpers ─────────────────────────────────────────────────────────────

export function hashSeed(seed: string): number {
  let h = 2166136261
  for (const char of seed) {
    h ^= char.codePointAt(0) ?? 0
    h = Math.imul(h, 16777619)
  }
  return h >>> 0
}

/** Deterministic, stable pseudo-random series so sparklines never flicker on render. */
export function sparkline(seed: string, points = 18, base = 50, amplitude = 26): number[] {
  let state = hashSeed(seed)
  const out: number[] = []
  for (let i = 0; i < points; i += 1) {
    state = (Math.imul(state, 1664525) + 1013904223) >>> 0
    const noise = state / 4294967296 - 0.5
    const wave = Math.sin(i / 2.4) * amplitude * 0.35
    out.push(Math.max(2, Math.round(base + wave + noise * amplitude)))
  }
  return out
}

function clampNumber(value: number, min: number, max: number): number {
  return Math.min(max, Math.max(min, value))
}

/**
 * Deterministic sample history for one metric. Values wander within a band around
 * `current` and the final point is pinned to `current`, so an over-time chart always
 * agrees with the headline number. Stable for a given seed — the catalog renders
 * sample telemetry until live metric series are wired into these surfaces.
 */
export function metricSeriesValues(
  seed: string,
  current: number,
  points: number,
  options?: {readonly spread?: number; readonly min?: number; readonly max?: number},
): number[] {
  const spread = options?.spread ?? 0.4
  const min = options?.min ?? 0
  const max = options?.max ?? Number.POSITIVE_INFINITY
  const shape = sparkline(seed, points, 50, 26)
  const values = shape.map((point) => clampNumber(current * (1 + ((point - 50) / 50) * spread), min, max))
  if (values.length > 0) values[values.length - 1] = clampNumber(current, min, max)
  return values
}

export interface HostInfraSample {
  readonly cores: number
  readonly diskPct: number
  readonly load1: number
  readonly load5: number
  readonly load15: number
  readonly netRecvBytes: number
  readonly netSentBytes: number
}

/**
 * Synthesize current host infrastructure metrics (disk, load average, network
 * throughput) derived deterministically from the host id and its CPU sample. These
 * mirror the graphs the old per-host page exposed. Returns null for non-hosts and
 * for hosts with no CPU signal (nothing to anchor to). Sample data only.
 */
export function hostInfraSample(resource: Resource): HostInfraSample | null {
  const cpu = resource.telemetry.cpuPct
  if (resource.kind !== 'host' || cpu === null) return null
  const frac = (salt: string) => (hashSeed(`${resource.id}:${salt}`) % 1000) / 1000
  const cores = [2, 4, 8, 16][Math.floor(frac('cores') * 4)]
  const load1 = Math.round((cpu / 100) * cores * (0.7 + frac('l1') * 0.7) * 100) / 100
  const load5 = Math.round(load1 * (0.82 + frac('l5') * 0.22) * 100) / 100
  const load15 = Math.round(load5 * (0.8 + frac('l15') * 0.2) * 100) / 100
  const diskPct = Math.round(clampNumber(40 + frac('disk') * 52, 6, 96))
  const netRecvBytes = Math.round((2 + frac('rx') * 30) * 1024 * 1024)
  const netSentBytes = Math.round((1 + frac('tx') * 12) * 1024 * 1024)
  return {cores, diskPct, load1, load5, load15, netRecvBytes, netSentBytes}
}

export function totalVulns(v: VulnCounts): number {
  return v.critical + v.high + v.medium + v.low
}

export function healthRank(h: Health): number {
  if (h === 'critical') return 3
  if (h === 'warn') return 2
  if (h === 'healthy') return 1
  return 0
}

export function vulnWeight(v: VulnCounts): number {
  return v.critical * 1000 + v.high * 100 + v.medium * 10 + v.low
}

export function utilTone(pct: number): StatusTone {
  if (pct >= 90) return 'danger'
  if (pct >= 75) return 'warning'
  return 'success'
}

export function relTime(iso: string): string {
  const diff = Date.now() - new Date(iso).getTime()
  const mins = Math.round(diff / 60000)
  if (mins < 1) return 'just now'
  if (mins < 60) return `${mins}m ago`
  const hrs = Math.round(mins / 60)
  if (hrs < 24) return `${hrs}h ago`
  const days = Math.round(hrs / 24)
  if (days < 30) return `${days}d ago`
  return `${Math.round(days / 30)}mo ago`
}

export function formatUsd(n: number): string {
  if (n >= 1000) return `$${(n / 1000).toFixed(n >= 10000 ? 0 : 1)}k`
  return `$${n.toFixed(0)}`
}

export function formatUsdExact(n: number): string {
  return `$${n.toLocaleString('en-US', {maximumFractionDigits: 0})}`
}

export function formatPct(n: number): string {
  return `${n > 0 ? '+' : ''}${n.toFixed(1)}%`
}

export function teamOf(r: Resource): string {
  return r.owner?.team ?? 'Unowned'
}

// ── Findings (derived mock CVEs for the Security tab) ─────────────────────────

export interface Finding {
  readonly id: string
  readonly severity: VulnSeverity
  readonly pkg: string
  readonly title: string
}

const FINDING_PKGS = ['openssl', 'glibc', 'libcurl', 'lodash', 'jackson-databind', 'urllib3', 'busybox', 'zlib', 'expat', 'log4j-core']
const FINDING_TITLES = [
  'Heap buffer overflow',
  'Improper certificate validation',
  'Prototype pollution',
  'Denial of service via crafted input',
  'Out-of-bounds read',
  'Use-after-free',
]

export function mockFindings(resource: Resource): Finding[] {
  const out: Finding[] = []
  let state = hashSeed(resource.id)
  const next = () => {
    state = (Math.imul(state, 1664525) + 1013904223) >>> 0
    return state / 4294967296
  }
  for (const sev of VULN_SEVERITIES) {
    const shown = Math.min(resource.vulns[sev], sev === 'critical' || sev === 'high' ? 3 : 2)
    for (let i = 0; i < shown; i += 1) {
      const pkg = FINDING_PKGS[Math.floor(next() * FINDING_PKGS.length)]
      const title = FINDING_TITLES[Math.floor(next() * FINDING_TITLES.length)]
      const num = 1000 + Math.floor(next() * 8999)
      out.push({id: `CVE-2026-${num}`, severity: sev, pkg, title})
    }
  }
  return out
}

// ── Facets ───────────────────────────────────────────────────────────────────

export type FacetKey = 'kind' | 'env' | 'health' | 'team' | 'cloud' | 'tag'

export const CATALOG_FACET_SCHEMA: FacetSchema = [
  {
    key: 'kind',
    label: 'Type',
    color: 'bg-info-bg text-info-fg border-info-border',
    suggestions: ['service', 'host', 'pod', 'container', 'cloud', 'network-device'],
  },
  {
    key: 'env',
    label: 'Environment',
    aliases: ['environment'],
    color: 'bg-accent text-primary border-[hsl(var(--primary)/0.3)]',
    suggestions: ['prod', 'staging', 'dev'],
  },
  {
    key: 'health',
    label: 'Health',
    color: 'bg-warning-bg text-warning-fg border-warning-border',
    suggestions: ['healthy', 'warn', 'critical', 'unknown'],
  },
  {key: 'team', label: 'Team', color: 'bg-muted text-muted-foreground border-border'},
  {
    key: 'cloud',
    label: 'Provider',
    aliases: ['provider'],
    color: 'bg-muted text-muted-foreground border-border',
    suggestions: ['aws', 'gcp', 'azure', 'on-prem'],
  },
  {key: 'tag', label: 'Tag', color: 'bg-muted text-muted-foreground border-border'},
]

export interface CatalogRailOption {
  readonly value: string
  readonly label: string
  readonly count: number
}

export interface CatalogRailSection {
  readonly key: FacetKey
  readonly label: string
  readonly options: readonly CatalogRailOption[]
}

const FACET_ORDER: Partial<Record<FacetKey, readonly string[]>> = {
  kind: ['service', 'host', 'pod', 'container', 'cloud', 'network-device'],
  env: ['prod', 'staging', 'dev'],
  health: ['critical', 'warn', 'healthy', 'unknown'],
  cloud: ['aws', 'gcp', 'azure', 'on-prem'],
}

function facetValueLabel(key: FacetKey, value: string): string {
  switch (key) {
    case 'kind':
      return KIND_META[value as ResourceKind].label
    case 'env':
      return ENV_LABEL[value as Environment]
    case 'health':
      return HEALTH_LABEL[value as Health]
    case 'cloud':
      return CLOUD_LABEL[value as CloudProvider]
    case 'team':
    case 'tag':
      return value
  }
}

/** Values a resource exposes for a facet key (tags are multi-valued). */
export function resourceFacetValues(r: Resource, key: FacetKey): readonly string[] {
  switch (key) {
    case 'kind':
      return [r.kind]
    case 'env':
      return [r.environment]
    case 'health':
      return [r.health]
    case 'cloud':
      return [r.cloud]
    case 'team':
      return [teamOf(r)]
    case 'tag':
      return r.tags
  }
}

function buildSection(resources: readonly Resource[], key: FacetKey): CatalogRailSection {
  const counts = new Map<string, number>()
  for (const r of resources) {
    for (const v of resourceFacetValues(r, key)) {
      counts.set(v, (counts.get(v) ?? 0) + 1)
    }
  }
  const ordered = FACET_ORDER[key]
  const values = ordered
    ? ordered.filter((v) => counts.has(v))
    : [...counts.keys()].sort((a, b) => (counts.get(b) ?? 0) - (counts.get(a) ?? 0) || a.localeCompare(b))
  return {
    key,
    label: CATALOG_FACET_SCHEMA.find((d) => d.key === key)?.label ?? key,
    options: values.map((value) => ({value, label: facetValueLabel(key, value), count: counts.get(value) ?? 0})),
  }
}

/** Build the rail's facet sections (with counts) from the current result set. */
export function buildCatalogSections(resources: readonly Resource[]): CatalogRailSection[] {
  const keys: FacetKey[] = ['kind', 'env', 'health', 'team', 'cloud']
  return keys.map((key) => buildSection(resources, key)).filter((s) => s.options.length > 0)
}

/** Free-text + include/exclude facet matching against one resource. */
export function matchesFilters(r: Resource, query: string, facetFilters: readonly FacetFilter[]): boolean {
  const byKey = new Map<string, {includes: string[]; excludes: string[]}>()
  for (const f of facetFilters) {
    const bucket = byKey.get(f.key) ?? {includes: [], excludes: []}
    if (f.exclude) bucket.excludes.push(f.value)
    else bucket.includes.push(f.value)
    byKey.set(f.key, bucket)
  }
  for (const [key, {includes, excludes}] of byKey) {
    const values = resourceFacetValues(r, key as FacetKey)
    if (includes.length > 0 && !values.some((v) => includes.includes(v))) return false
    if (excludes.length > 0 && values.some((v) => excludes.includes(v))) return false
  }

  const q = query.trim().toLowerCase()
  if (q === '') return true
  const hay = [
    r.name,
    KIND_META[r.kind].label,
    ENV_LABEL[r.environment],
    r.region,
    teamOf(r),
    r.owner?.oncall ?? '',
    ...r.tags,
    ...r.metadata.map((m) => `${m.label} ${m.value}`),
  ]
    .join(' ')
    .toLowerCase()
  return q
    .split(/\s+/)
    .filter(Boolean)
    .every((term) => hay.includes(term))
}

// ── Sorting ──────────────────────────────────────────────────────────────────

export type SortKey = 'name' | 'health' | 'vulns' | 'cost' | 'lastChange'
export type SortDir = 'asc' | 'desc'

function compareBy(a: Resource, b: Resource, key: SortKey): number {
  switch (key) {
    case 'name':
      return a.name.localeCompare(b.name)
    case 'health':
      return healthRank(a.health) - healthRank(b.health)
    case 'vulns':
      return vulnWeight(a.vulns) - vulnWeight(b.vulns)
    case 'cost':
      return a.monthlyUsd - b.monthlyUsd
    case 'lastChange':
      return new Date(a.lastChange).getTime() - new Date(b.lastChange).getTime()
  }
}

export function sortResources(list: readonly Resource[], key: SortKey, dir: SortDir): Resource[] {
  const sign = dir === 'asc' ? 1 : -1
  return [...list].sort((a, b) => {
    const primary = compareBy(a, b, key) * sign
    if (primary === 0) return a.name.localeCompare(b.name)
    return primary
  })
}

// ── Mock dataset (stand-in for the future backend) ───────────────────────────

const OWNERS: Record<string, Owner> = {
  payments: {team: 'Payments', oncall: 'Dana Whitfield', slack: '#payments-oncall', repo: 'moneat-io/payments'},
  storefront: {team: 'Storefront', oncall: 'Theo Marsh', slack: '#web-platform', repo: 'moneat-io/storefront'},
  platform: {team: 'Platform', oncall: 'Priya Nair', slack: '#platform-eng', repo: 'moneat-io/platform'},
  data: {team: 'Data', oncall: 'Sam Okoro', slack: '#data-eng', repo: 'moneat-io/data'},
  infra: {team: 'Infrastructure', oncall: 'Lena Fischer', slack: '#infra-oncall', repo: 'moneat-io/infra'},
}

const PASS_POSTURE: readonly Posture[] = [
  {label: 'Encryption at rest', pass: true},
  {label: 'Least-privilege access', pass: true},
  {label: 'No public exposure', pass: true},
  {label: 'Backups configured', pass: true},
]

interface ResourceInput {
  readonly id: string
  readonly name: string
  readonly kind: ResourceKind
  readonly health: Health
  readonly env: Environment
  readonly region: string
  readonly cloud: CloudProvider
  readonly owner?: Owner | null
  readonly tags: readonly string[]
  readonly cpu: number
  readonly mem: number
  readonly latencyMs?: number
  readonly errorRatePct?: number
  readonly throughput?: string
  readonly vulns?: Partial<VulnCounts>
  readonly sbom?: number
  readonly posture?: readonly Posture[]
  readonly monthlyUsd: number
  readonly costTrendPct?: number
  readonly costBreakdown?: readonly CostItem[]
  readonly relationships?: readonly Relationship[]
  readonly changes?: readonly ChangeEvent[]
  readonly metadata?: readonly MetaItem[]
  readonly firstSeen?: string
  readonly lastChange?: string
}

function mk(input: ResourceInput): Resource {
  const vulns: VulnCounts = {critical: 0, high: 0, medium: 0, low: 0, ...input.vulns}
  return {
    id: input.id,
    name: input.name,
    kind: input.kind,
    health: input.health,
    environment: input.env,
    region: input.region,
    cloud: input.cloud,
    owner: input.owner ?? null,
    tags: input.tags,
    telemetry: {
      cpuPct: input.cpu,
      memPct: input.mem,
      latencyMs: input.latencyMs,
      errorRatePct: input.errorRatePct,
      throughput: input.throughput,
    },
    vulns,
    sbomComponents: input.sbom ?? 0,
    posture: input.posture ?? PASS_POSTURE,
    monthlyUsd: input.monthlyUsd,
    costTrendPct: input.costTrendPct ?? 0,
    costBreakdown: input.costBreakdown ?? [],
    relationships: input.relationships ?? [],
    changes: input.changes ?? [],
    metadata: input.metadata ?? [],
    firstSeen: input.firstSeen ?? '2026-01-04T00:00:00Z',
    lastChange: input.lastChange ?? input.changes?.[0]?.ts ?? input.firstSeen ?? '2026-01-04T00:00:00Z',
  }
}

export const MOCK_RESOURCES: readonly Resource[] = [
  // ── Services ──
  mk({
    id: 'svc-checkout',
    name: 'checkout-api',
    kind: 'service',
    health: 'critical',
    env: 'prod',
    region: 'us-east-1',
    cloud: 'aws',
    owner: OWNERS.payments,
    tags: ['team:payments', 'tier:critical', 'lang:go', 'pci'],
    cpu: 71,
    mem: 64,
    latencyMs: 412,
    errorRatePct: 3.8,
    throughput: '1.9k req/s',
    vulns: {critical: 2, high: 4, medium: 9, low: 14},
    sbom: 284,
    posture: [
      {label: 'Encryption in transit', pass: true},
      {label: 'Least-privilege access', pass: true},
      {label: 'Secrets in vault', pass: false},
      {label: 'SBOM up to date', pass: true},
    ],
    monthlyUsd: 540,
    costTrendPct: 12.4,
    costBreakdown: [
      {label: 'Compute (pods)', usd: 380},
      {label: 'Egress', usd: 96},
      {label: 'Telemetry ingest', usd: 64},
    ],
    relationships: [
      {relation: 'Runs on', name: 'pod-checkout-1', kind: 'pod', health: 'healthy', targetId: 'pod-checkout-1'},
      {relation: 'Runs on', name: 'pod-checkout-2', kind: 'pod', health: 'warn', targetId: 'pod-checkout-2'},
      {relation: 'Depends on', name: 'payments-api', kind: 'service', health: 'healthy', targetId: 'svc-payments'},
      {relation: 'Depends on', name: 'orders-api', kind: 'service', health: 'warn', targetId: 'svc-orders'},
      {relation: 'Talks to', name: 'redis-sessions', kind: 'cloud', health: 'healthy', targetId: 'cloud-redis-sessions'},
      {relation: 'Talks to', name: 'rds-orders-prod', kind: 'cloud', health: 'critical', targetId: 'cloud-rds-orders'},
    ],
    changes: [
      {ts: '2026-06-06T13:40:00Z', kind: 'incident', summary: 'Error rate breached 3% SLO — p99 latency spike', actor: 'monitor:checkout-slo'},
      {ts: '2026-06-06T12:05:00Z', kind: 'deploy', summary: 'Deployed v2026.6.41 (checkout-api)', actor: 'theo.marsh'},
      {ts: '2026-06-05T09:20:00Z', kind: 'config', summary: 'Raised pod CPU limit 500m -> 750m', actor: 'dana.whitfield'},
    ],
    metadata: [
      {label: 'Language', value: 'Go 1.23'},
      {label: 'Version', value: 'v2026.6.41'},
      {label: 'Instrumentation', value: 'OpenTelemetry SDK'},
      {label: 'Endpoints', value: '17 routes'},
    ],
    firstSeen: '2025-08-11T00:00:00Z',
  }),
  mk({
    id: 'svc-payments',
    name: 'payments-api',
    kind: 'service',
    health: 'healthy',
    env: 'prod',
    region: 'us-east-1',
    cloud: 'aws',
    owner: OWNERS.payments,
    tags: ['team:payments', 'tier:critical', 'lang:java', 'pci'],
    cpu: 48,
    mem: 57,
    latencyMs: 88,
    errorRatePct: 0.2,
    throughput: '1.2k req/s',
    vulns: {critical: 0, high: 1, medium: 5, low: 8},
    sbom: 412,
    monthlyUsd: 470,
    costTrendPct: 2.1,
    costBreakdown: [
      {label: 'Compute (pods)', usd: 340},
      {label: 'Egress', usd: 70},
      {label: 'Telemetry ingest', usd: 60},
    ],
    relationships: [
      {relation: 'Depended on by', name: 'checkout-api', kind: 'service', health: 'critical', targetId: 'svc-checkout'},
      {relation: 'Talks to', name: 'rds-orders-prod', kind: 'cloud', health: 'critical', targetId: 'cloud-rds-orders'},
      {relation: 'Emits to', name: 'orders-events', kind: 'cloud', health: 'healthy', targetId: 'cloud-sqs-orders'},
    ],
    changes: [{ts: '2026-06-05T16:30:00Z', kind: 'deploy', summary: 'Deployed v2026.6.40 (payments-api)', actor: 'dana.whitfield'}],
    metadata: [
      {label: 'Language', value: 'Java 21'},
      {label: 'Version', value: 'v2026.6.40'},
      {label: 'Instrumentation', value: 'OpenTelemetry agent'},
      {label: 'Endpoints', value: '23 routes'},
    ],
  }),
  mk({
    id: 'svc-orders',
    name: 'orders-api',
    kind: 'service',
    health: 'warn',
    env: 'prod',
    region: 'us-east-1',
    cloud: 'aws',
    owner: OWNERS.platform,
    tags: ['team:platform', 'tier:high', 'lang:go'],
    cpu: 63,
    mem: 81,
    latencyMs: 214,
    errorRatePct: 1.1,
    throughput: '880 req/s',
    vulns: {critical: 0, high: 2, medium: 6, low: 11},
    sbom: 198,
    monthlyUsd: 360,
    costTrendPct: -3.4,
    costBreakdown: [
      {label: 'Compute (pods)', usd: 250},
      {label: 'Egress', usd: 60},
      {label: 'Telemetry ingest', usd: 50},
    ],
    relationships: [
      {relation: 'Depended on by', name: 'checkout-api', kind: 'service', health: 'critical', targetId: 'svc-checkout'},
      {relation: 'Talks to', name: 'rds-orders-prod', kind: 'cloud', health: 'critical', targetId: 'cloud-rds-orders'},
    ],
    changes: [
      {ts: '2026-06-06T08:15:00Z', kind: 'scale', summary: 'Autoscaled 4 -> 6 replicas (memory pressure)', actor: 'hpa:orders-api'},
      {ts: '2026-06-04T11:00:00Z', kind: 'deploy', summary: 'Deployed v2026.6.38 (orders-api)', actor: 'priya.nair'},
    ],
    metadata: [
      {label: 'Language', value: 'Go 1.23'},
      {label: 'Version', value: 'v2026.6.38'},
      {label: 'Instrumentation', value: 'OpenTelemetry SDK'},
      {label: 'Endpoints', value: '12 routes'},
    ],
  }),
  mk({
    id: 'svc-storefront',
    name: 'web-storefront',
    kind: 'service',
    health: 'healthy',
    env: 'prod',
    region: 'us-east-1',
    cloud: 'aws',
    owner: OWNERS.storefront,
    tags: ['team:storefront', 'tier:high', 'lang:node'],
    cpu: 39,
    mem: 52,
    latencyMs: 61,
    errorRatePct: 0.4,
    throughput: '3.4k req/s',
    vulns: {critical: 0, high: 0, medium: 3, low: 7},
    sbom: 530,
    monthlyUsd: 410,
    costTrendPct: 5.6,
    costBreakdown: [
      {label: 'Compute (pods)', usd: 240},
      {label: 'CDN', usd: 120},
      {label: 'Telemetry ingest', usd: 50},
    ],
    relationships: [
      {relation: 'Depends on', name: 'search-api', kind: 'service', health: 'warn', targetId: 'svc-search'},
      {relation: 'Depends on', name: 'checkout-api', kind: 'service', health: 'critical', targetId: 'svc-checkout'},
      {relation: 'Served by', name: 'assets-cdn', kind: 'cloud', health: 'healthy', targetId: 'cloud-cdn'},
    ],
    changes: [{ts: '2026-06-06T07:45:00Z', kind: 'deploy', summary: 'Deployed v2026.6.51 (web-storefront)', actor: 'theo.marsh'}],
    metadata: [
      {label: 'Language', value: 'Node 22'},
      {label: 'Version', value: 'v2026.6.51'},
      {label: 'Instrumentation', value: 'OpenTelemetry SDK'},
      {label: 'Endpoints', value: '34 routes'},
    ],
  }),
  mk({
    id: 'svc-search',
    name: 'search-api',
    kind: 'service',
    health: 'warn',
    env: 'prod',
    region: 'us-west-2',
    cloud: 'gcp',
    owner: OWNERS.data,
    tags: ['team:data', 'tier:medium', 'lang:python'],
    cpu: 77,
    mem: 69,
    latencyMs: 176,
    errorRatePct: 0.9,
    throughput: '640 req/s',
    vulns: {critical: 0, high: 3, medium: 8, low: 5},
    sbom: 221,
    monthlyUsd: 300,
    costTrendPct: 1.2,
    costBreakdown: [
      {label: 'Compute (pods)', usd: 220},
      {label: 'Egress', usd: 40},
      {label: 'Telemetry ingest', usd: 40},
    ],
    relationships: [
      {relation: 'Runs on', name: 'pod-search-1', kind: 'pod', health: 'healthy', targetId: 'pod-search-1'},
      {relation: 'Depended on by', name: 'web-storefront', kind: 'service', health: 'healthy', targetId: 'svc-storefront'},
    ],
    changes: [{ts: '2026-06-03T14:10:00Z', kind: 'config', summary: 'Reindex schedule changed to hourly', actor: 'sam.okoro'}],
    metadata: [
      {label: 'Language', value: 'Python 3.12'},
      {label: 'Version', value: 'v2026.5.19'},
      {label: 'Instrumentation', value: 'OpenTelemetry SDK'},
      {label: 'Endpoints', value: '8 routes'},
    ],
  }),
  mk({
    id: 'svc-notifications',
    name: 'notifications-worker',
    kind: 'service',
    health: 'healthy',
    env: 'prod',
    region: 'us-east-1',
    cloud: 'aws',
    owner: null,
    tags: ['tier:medium', 'lang:node', 'async'],
    cpu: 22,
    mem: 34,
    throughput: '210 msg/s',
    vulns: {critical: 0, high: 0, medium: 2, low: 4},
    sbom: 176,
    monthlyUsd: 140,
    costTrendPct: -0.6,
    costBreakdown: [
      {label: 'Compute (pods)', usd: 90},
      {label: 'Egress', usd: 30},
      {label: 'Telemetry ingest', usd: 20},
    ],
    relationships: [
      {relation: 'Consumes', name: 'orders-events', kind: 'cloud', health: 'healthy', targetId: 'cloud-sqs-orders'},
      {relation: 'Runs on', name: 'pod-notif-1', kind: 'pod', health: 'healthy', targetId: 'pod-notif-1'},
    ],
    changes: [{ts: '2026-05-28T10:00:00Z', kind: 'deploy', summary: 'Deployed v2026.5.12 (notifications-worker)', actor: 'ci-bot'}],
    metadata: [
      {label: 'Language', value: 'Node 22'},
      {label: 'Version', value: 'v2026.5.12'},
      {label: 'Instrumentation', value: 'OpenTelemetry SDK'},
      {label: 'Queue', value: 'orders-events'},
    ],
  }),

  // ── Hosts ──
  mk({
    id: 'host-web-1',
    name: 'i-0a3f8c21 · web-prod-1',
    kind: 'host',
    health: 'healthy',
    env: 'prod',
    region: 'us-east-1',
    cloud: 'aws',
    owner: OWNERS.infra,
    tags: ['role:web', 'az:us-east-1a', 'k8s-node'],
    cpu: 44,
    mem: 61,
    vulns: {critical: 0, high: 1, medium: 4, low: 9},
    sbom: 612,
    monthlyUsd: 224,
    costBreakdown: [
      {label: 'Compute (m6i.xlarge)', usd: 190},
      {label: 'EBS storage', usd: 24},
      {label: 'Data transfer', usd: 10},
    ],
    relationships: [
      {relation: 'Hosts', name: 'pod-checkout-1', kind: 'pod', health: 'healthy', targetId: 'pod-checkout-1'},
      {relation: 'Hosts', name: 'pod-search-1', kind: 'pod', health: 'healthy', targetId: 'pod-search-1'},
      {relation: 'Connected to', name: 'tor-rack-12', kind: 'network-device', health: 'healthy', targetId: 'net-tor-12'},
    ],
    changes: [{ts: '2026-06-01T03:00:00Z', kind: 'config', summary: 'Agent upgraded 7.62.1 -> 7.63.0', actor: 'ansible'}],
    metadata: [
      {label: 'OS', value: 'Ubuntu 24.04 LTS'},
      {label: 'Kernel', value: '6.8.0-41-generic'},
      {label: 'Instance type', value: 'm6i.xlarge'},
      {label: 'Agent', value: 'v7.63.0'},
      {label: 'Private IP', value: '10.2.14.31'},
    ],
  }),
  mk({
    id: 'host-web-2',
    name: 'i-0b7d4e90 · web-prod-2',
    kind: 'host',
    health: 'warn',
    env: 'prod',
    region: 'us-east-1',
    cloud: 'aws',
    owner: OWNERS.infra,
    tags: ['role:web', 'az:us-east-1b', 'k8s-node'],
    cpu: 88,
    mem: 79,
    vulns: {critical: 0, high: 2, medium: 6, low: 10},
    sbom: 612,
    monthlyUsd: 224,
    costBreakdown: [
      {label: 'Compute (m6i.xlarge)', usd: 190},
      {label: 'EBS storage', usd: 24},
      {label: 'Data transfer', usd: 10},
    ],
    relationships: [
      {relation: 'Hosts', name: 'pod-checkout-2', kind: 'pod', health: 'warn', targetId: 'pod-checkout-2'},
      {relation: 'Connected to', name: 'tor-rack-12', kind: 'network-device', health: 'healthy', targetId: 'net-tor-12'},
    ],
    changes: [{ts: '2026-06-06T08:12:00Z', kind: 'scale', summary: 'CPU sustained > 85% for 20m', actor: 'monitor:host-cpu'}],
    metadata: [
      {label: 'OS', value: 'Ubuntu 24.04 LTS'},
      {label: 'Kernel', value: '6.8.0-41-generic'},
      {label: 'Instance type', value: 'm6i.xlarge'},
      {label: 'Agent', value: 'v7.63.0'},
      {label: 'Private IP', value: '10.2.15.7'},
    ],
  }),
  mk({
    id: 'host-db-1',
    name: 'i-0c9a1b22 · db-prod-1',
    kind: 'host',
    health: 'critical',
    env: 'prod',
    region: 'us-east-1',
    cloud: 'aws',
    owner: OWNERS.data,
    tags: ['role:db', 'az:us-east-1a', 'stateful'],
    cpu: 57,
    mem: 92,
    vulns: {critical: 1, high: 3, medium: 5, low: 7},
    sbom: 540,
    posture: [
      {label: 'Encryption at rest', pass: true},
      {label: 'Least-privilege access', pass: true},
      {label: 'No public exposure', pass: true},
      {label: 'Disk free > 15%', pass: false},
    ],
    monthlyUsd: 680,
    costTrendPct: 1,
    costBreakdown: [
      {label: 'Compute (r6i.2xlarge)', usd: 540},
      {label: 'EBS storage (io2)', usd: 120},
      {label: 'Data transfer', usd: 20},
    ],
    relationships: [{relation: 'Connected to', name: 'core-switch-1', kind: 'network-device', health: 'healthy', targetId: 'net-core-sw-1'}],
    changes: [{ts: '2026-06-06T11:30:00Z', kind: 'incident', summary: 'Root volume 94% full', actor: 'monitor:host-disk'}],
    metadata: [
      {label: 'OS', value: 'Amazon Linux 2023'},
      {label: 'Kernel', value: '6.1.97-104'},
      {label: 'Instance type', value: 'r6i.2xlarge'},
      {label: 'Agent', value: 'v7.63.0'},
      {label: 'Private IP', value: '10.2.14.9'},
    ],
  }),
  mk({
    id: 'host-cache-1',
    name: 'i-0d2e7f55 · cache-prod-1',
    kind: 'host',
    health: 'healthy',
    env: 'prod',
    region: 'us-east-1',
    cloud: 'aws',
    owner: OWNERS.infra,
    tags: ['role:cache', 'az:us-east-1c'],
    cpu: 19,
    mem: 41,
    vulns: {critical: 0, high: 0, medium: 2, low: 5},
    sbom: 480,
    monthlyUsd: 190,
    relationships: [{relation: 'Connected to', name: 'core-switch-1', kind: 'network-device', health: 'healthy', targetId: 'net-core-sw-1'}],
    metadata: [
      {label: 'OS', value: 'Ubuntu 24.04 LTS'},
      {label: 'Instance type', value: 'r6g.large'},
      {label: 'Agent', value: 'v7.63.0'},
      {label: 'Private IP', value: '10.2.16.4'},
    ],
  }),
  mk({
    id: 'host-build-1',
    name: 'i-0e1f9a07 · build-runner-1',
    kind: 'host',
    health: 'healthy',
    env: 'dev',
    region: 'us-west-2',
    cloud: 'gcp',
    owner: null,
    tags: ['role:ci', 'ephemeral'],
    cpu: 12,
    mem: 28,
    vulns: {critical: 0, high: 1, medium: 3, low: 6},
    sbom: 720,
    monthlyUsd: 140,
    metadata: [
      {label: 'OS', value: 'Debian 12'},
      {label: 'Instance type', value: 'e2-standard-4'},
      {label: 'Agent', value: 'v7.62.1'},
    ],
  }),
  mk({
    id: 'host-staging-1',
    name: 'i-0f3c2d88 · staging-1',
    kind: 'host',
    health: 'healthy',
    env: 'staging',
    region: 'us-east-1',
    cloud: 'aws',
    owner: OWNERS.infra,
    tags: ['role:mixed', 'staging'],
    cpu: 31,
    mem: 47,
    vulns: {critical: 0, high: 0, medium: 4, low: 8},
    sbom: 560,
    monthlyUsd: 90,
    metadata: [
      {label: 'OS', value: 'Ubuntu 24.04 LTS'},
      {label: 'Instance type', value: 't3.large'},
      {label: 'Agent', value: 'v7.63.0'},
    ],
  }),

  // ── Pods ──
  mk({
    id: 'pod-checkout-1',
    name: 'checkout-api-7d9f4-x2k',
    kind: 'pod',
    health: 'healthy',
    env: 'prod',
    region: 'us-east-1',
    cloud: 'aws',
    owner: OWNERS.payments,
    tags: ['ns:payments', 'app:checkout-api'],
    cpu: 58,
    mem: 60,
    vulns: {critical: 1, high: 2, medium: 4, low: 6},
    sbom: 284,
    monthlyUsd: 90,
    relationships: [
      {relation: 'Part of', name: 'checkout-api', kind: 'service', health: 'critical', targetId: 'svc-checkout'},
      {relation: 'Scheduled on', name: 'web-prod-1', kind: 'host', health: 'healthy', targetId: 'host-web-1'},
      {relation: 'Contains', name: 'checkout', kind: 'container', health: 'healthy', targetId: 'ctr-checkout-1'},
      {relation: 'Contains', name: 'otel-collector', kind: 'container', health: 'healthy', targetId: 'ctr-otel-1'},
    ],
    changes: [{ts: '2026-06-06T12:05:00Z', kind: 'deploy', summary: 'Rolled out by deployment checkout-api', actor: 'kubelet'}],
    metadata: [
      {label: 'Namespace', value: 'payments'},
      {label: 'Node', value: 'web-prod-1'},
      {label: 'Controller', value: 'rs/checkout-api-7d9f4'},
      {label: 'Restarts', value: '0'},
    ],
  }),
  mk({
    id: 'pod-checkout-2',
    name: 'checkout-api-7d9f4-q8m',
    kind: 'pod',
    health: 'warn',
    env: 'prod',
    region: 'us-east-1',
    cloud: 'aws',
    owner: OWNERS.payments,
    tags: ['ns:payments', 'app:checkout-api'],
    cpu: 84,
    mem: 88,
    vulns: {critical: 1, high: 2, medium: 4, low: 6},
    sbom: 284,
    monthlyUsd: 90,
    relationships: [
      {relation: 'Part of', name: 'checkout-api', kind: 'service', health: 'critical', targetId: 'svc-checkout'},
      {relation: 'Scheduled on', name: 'web-prod-2', kind: 'host', health: 'warn', targetId: 'host-web-2'},
    ],
    changes: [{ts: '2026-06-06T12:51:00Z', kind: 'incident', summary: 'OOMKilled once, restarted', actor: 'kubelet'}],
    metadata: [
      {label: 'Namespace', value: 'payments'},
      {label: 'Node', value: 'web-prod-2'},
      {label: 'Controller', value: 'rs/checkout-api-7d9f4'},
      {label: 'Restarts', value: '1'},
    ],
  }),
  mk({
    id: 'pod-search-1',
    name: 'search-api-5c8b2-h4t',
    kind: 'pod',
    health: 'healthy',
    env: 'prod',
    region: 'us-west-2',
    cloud: 'gcp',
    owner: OWNERS.data,
    tags: ['ns:search', 'app:search-api'],
    cpu: 66,
    mem: 58,
    vulns: {critical: 0, high: 1, medium: 3, low: 4},
    sbom: 221,
    monthlyUsd: 70,
    relationships: [
      {relation: 'Part of', name: 'search-api', kind: 'service', health: 'warn', targetId: 'svc-search'},
      {relation: 'Scheduled on', name: 'web-prod-1', kind: 'host', health: 'healthy', targetId: 'host-web-1'},
    ],
    metadata: [
      {label: 'Namespace', value: 'search'},
      {label: 'Node', value: 'web-prod-1'},
      {label: 'Controller', value: 'rs/search-api-5c8b2'},
      {label: 'Restarts', value: '0'},
    ],
  }),
  mk({
    id: 'pod-notif-1',
    name: 'notifications-6f1a9-z9p',
    kind: 'pod',
    health: 'healthy',
    env: 'prod',
    region: 'us-east-1',
    cloud: 'aws',
    owner: null,
    tags: ['ns:platform', 'app:notifications'],
    cpu: 20,
    mem: 30,
    vulns: {critical: 0, high: 0, medium: 2, low: 3},
    sbom: 176,
    monthlyUsd: 45,
    relationships: [{relation: 'Part of', name: 'notifications-worker', kind: 'service', health: 'healthy', targetId: 'svc-notifications'}],
    metadata: [
      {label: 'Namespace', value: 'platform'},
      {label: 'Node', value: 'cache-prod-1'},
      {label: 'Controller', value: 'rs/notifications-6f1a9'},
      {label: 'Restarts', value: '0'},
    ],
  }),

  // ── Containers ──
  mk({
    id: 'ctr-checkout-1',
    name: 'checkout (ghcr.io/moneat/checkout:2026.6.41)',
    kind: 'container',
    health: 'healthy',
    env: 'prod',
    region: 'us-east-1',
    cloud: 'aws',
    owner: OWNERS.payments,
    tags: ['image:checkout', 'app:checkout-api'],
    cpu: 52,
    mem: 55,
    vulns: {critical: 2, high: 3, medium: 5, low: 8},
    sbom: 284,
    posture: [
      {label: 'Non-root user', pass: true},
      {label: 'Read-only root FS', pass: false},
      {label: 'Signed image', pass: true},
      {label: 'No critical CVEs', pass: false},
    ],
    monthlyUsd: 60,
    relationships: [
      {relation: 'Inside', name: 'checkout-api-7d9f4-x2k', kind: 'pod', health: 'healthy', targetId: 'pod-checkout-1'},
      {relation: 'Part of', name: 'checkout-api', kind: 'service', health: 'critical', targetId: 'svc-checkout'},
    ],
    metadata: [
      {label: 'Image', value: 'ghcr.io/moneat/checkout:2026.6.41'},
      {label: 'Digest', value: 'sha256:9f1c…b7'},
      {label: 'Runtime', value: 'containerd 1.7'},
      {label: 'Started', value: relTime('2026-06-06T12:05:00Z')},
    ],
  }),
  mk({
    id: 'ctr-nginx-1',
    name: 'nginx (nginx:1.27-alpine)',
    kind: 'container',
    health: 'healthy',
    env: 'prod',
    region: 'us-east-1',
    cloud: 'aws',
    owner: OWNERS.storefront,
    tags: ['image:nginx', 'role:ingress'],
    cpu: 14,
    mem: 18,
    vulns: {critical: 0, high: 1, medium: 2, low: 3},
    sbom: 96,
    monthlyUsd: 30,
    metadata: [
      {label: 'Image', value: 'nginx:1.27-alpine'},
      {label: 'Runtime', value: 'containerd 1.7'},
      {label: 'Restarts', value: '0'},
    ],
  }),
  mk({
    id: 'ctr-otel-1',
    name: 'otel-collector (otel/collector:0.108)',
    kind: 'container',
    health: 'healthy',
    env: 'prod',
    region: 'us-east-1',
    cloud: 'aws',
    owner: OWNERS.platform,
    tags: ['image:otel-collector', 'role:telemetry'],
    cpu: 26,
    mem: 33,
    vulns: {critical: 0, high: 0, medium: 1, low: 2},
    sbom: 142,
    monthlyUsd: 25,
    relationships: [{relation: 'Inside', name: 'checkout-api-7d9f4-x2k', kind: 'pod', health: 'healthy', targetId: 'pod-checkout-1'}],
    metadata: [
      {label: 'Image', value: 'otel/opentelemetry-collector:0.108.0'},
      {label: 'Runtime', value: 'containerd 1.7'},
      {label: 'Pipelines', value: 'traces, metrics, logs'},
    ],
  }),
  mk({
    id: 'ctr-redis-1',
    name: 'redis (redis:7.4-alpine)',
    kind: 'container',
    health: 'healthy',
    env: 'staging',
    region: 'us-east-1',
    cloud: 'aws',
    owner: OWNERS.infra,
    tags: ['image:redis', 'role:cache'],
    cpu: 9,
    mem: 22,
    vulns: {critical: 0, high: 0, medium: 1, low: 1},
    sbom: 64,
    monthlyUsd: 18,
    metadata: [
      {label: 'Image', value: 'redis:7.4-alpine'},
      {label: 'Runtime', value: 'containerd 1.7'},
    ],
  }),
  mk({
    id: 'ctr-worker-1',
    name: 'thumbnailer (ghcr.io/moneat/thumb:0.9)',
    kind: 'container',
    health: 'warn',
    env: 'prod',
    region: 'us-east-1',
    cloud: 'aws',
    owner: null,
    tags: ['image:thumbnailer', 'async'],
    cpu: 73,
    mem: 64,
    vulns: {critical: 0, high: 2, medium: 4, low: 5},
    sbom: 188,
    monthlyUsd: 22,
    metadata: [
      {label: 'Image', value: 'ghcr.io/moneat/thumb:0.9'},
      {label: 'Runtime', value: 'containerd 1.7'},
      {label: 'Restarts', value: '3'},
    ],
  }),

  // ── Cloud resources ──
  mk({
    id: 'cloud-rds-orders',
    name: 'rds-orders-prod',
    kind: 'cloud',
    health: 'critical',
    env: 'prod',
    region: 'us-east-1',
    cloud: 'aws',
    owner: OWNERS.data,
    tags: ['service:rds', 'engine:postgres', 'stateful', 'pci'],
    cpu: 81,
    mem: 76,
    latencyMs: 36,
    vulns: {critical: 0, high: 1, medium: 2, low: 2},
    sbom: 0,
    posture: [
      {label: 'Encryption at rest', pass: true},
      {label: 'Multi-AZ', pass: true},
      {label: 'No public access', pass: true},
      {label: 'Connections < 80%', pass: false},
    ],
    monthlyUsd: 1840,
    costTrendPct: 8.7,
    costBreakdown: [
      {label: 'Instance (db.r6g.2xlarge)', usd: 1280},
      {label: 'Storage (io2, 1.2TB)', usd: 420},
      {label: 'Backups', usd: 140},
    ],
    relationships: [
      {relation: 'Backs', name: 'orders-api', kind: 'service', health: 'warn', targetId: 'svc-orders'},
      {relation: 'Used by', name: 'checkout-api', kind: 'service', health: 'critical', targetId: 'svc-checkout'},
      {relation: 'Used by', name: 'payments-api', kind: 'service', health: 'healthy', targetId: 'svc-payments'},
    ],
    changes: [
      {ts: '2026-06-06T11:55:00Z', kind: 'incident', summary: 'Connection pool 96% of max', actor: 'monitor:rds-conns'},
      {ts: '2026-05-20T02:00:00Z', kind: 'config', summary: 'Upgraded engine 15.6 -> 15.7', actor: 'sam.okoro'},
    ],
    metadata: [
      {label: 'Service', value: 'Amazon RDS'},
      {label: 'Engine', value: 'PostgreSQL 15.7'},
      {label: 'Class', value: 'db.r6g.2xlarge'},
      {label: 'Identifier', value: 'rds-orders-prod'},
    ],
  }),
  mk({
    id: 'cloud-redis-sessions',
    name: 'redis-sessions',
    kind: 'cloud',
    health: 'healthy',
    env: 'prod',
    region: 'us-east-1',
    cloud: 'aws',
    owner: OWNERS.platform,
    tags: ['service:elasticache', 'engine:redis'],
    cpu: 33,
    mem: 49,
    latencyMs: 2,
    monthlyUsd: 420,
    costTrendPct: 0.4,
    costBreakdown: [
      {label: 'Nodes (cache.r6g.large x2)', usd: 360},
      {label: 'Data transfer', usd: 60},
    ],
    relationships: [{relation: 'Used by', name: 'checkout-api', kind: 'service', health: 'critical', targetId: 'svc-checkout'}],
    metadata: [
      {label: 'Service', value: 'Amazon ElastiCache'},
      {label: 'Engine', value: 'Redis 7.1'},
      {label: 'Nodes', value: '2 (cluster mode off)'},
    ],
  }),
  mk({
    id: 'cloud-s3-assets',
    name: 's3-assets-prod',
    kind: 'cloud',
    health: 'healthy',
    env: 'prod',
    region: 'us-east-1',
    cloud: 'aws',
    owner: null,
    tags: ['service:s3', 'public-read'],
    cpu: 0,
    mem: 0,
    posture: [
      {label: 'Encryption at rest', pass: true},
      {label: 'Versioning enabled', pass: true},
      {label: 'No public write', pass: true},
      {label: 'No public read', pass: false},
    ],
    monthlyUsd: 280,
    costTrendPct: 3,
    costBreakdown: [
      {label: 'Storage (4.1 TB)', usd: 96},
      {label: 'Requests', usd: 44},
      {label: 'Data transfer', usd: 140},
    ],
    relationships: [{relation: 'Origin for', name: 'assets-cdn', kind: 'cloud', health: 'healthy', targetId: 'cloud-cdn'}],
    metadata: [
      {label: 'Service', value: 'Amazon S3'},
      {label: 'Objects', value: '12.4M'},
      {label: 'Size', value: '4.1 TB'},
    ],
  }),
  mk({
    id: 'cloud-sqs-orders',
    name: 'orders-events',
    kind: 'cloud',
    health: 'healthy',
    env: 'prod',
    region: 'us-east-1',
    cloud: 'aws',
    owner: OWNERS.platform,
    tags: ['service:sqs', 'async'],
    cpu: 0,
    mem: 0,
    throughput: '210 msg/s',
    monthlyUsd: 60,
    costTrendPct: -1.2,
    costBreakdown: [{label: 'Requests', usd: 60}],
    relationships: [
      {relation: 'Produced by', name: 'payments-api', kind: 'service', health: 'healthy', targetId: 'svc-payments'},
      {relation: 'Consumed by', name: 'notifications-worker', kind: 'service', health: 'healthy', targetId: 'svc-notifications'},
    ],
    metadata: [
      {label: 'Service', value: 'Amazon SQS'},
      {label: 'Type', value: 'Standard queue'},
      {label: 'In flight', value: '~120 msgs'},
    ],
  }),
  mk({
    id: 'cloud-lambda-thumb',
    name: 'thumbnailer-fn',
    kind: 'cloud',
    health: 'warn',
    env: 'prod',
    region: 'us-east-1',
    cloud: 'aws',
    owner: OWNERS.storefront,
    tags: ['service:lambda', 'runtime:node22'],
    cpu: 0,
    mem: 0,
    latencyMs: 940,
    errorRatePct: 2.4,
    monthlyUsd: 95,
    costTrendPct: 6.1,
    costBreakdown: [
      {label: 'Invocations', usd: 35},
      {label: 'Duration (GB-s)', usd: 60},
    ],
    relationships: [{relation: 'Reads from', name: 's3-assets-prod', kind: 'cloud', health: 'healthy', targetId: 'cloud-s3-assets'}],
    changes: [{ts: '2026-06-04T18:20:00Z', kind: 'deploy', summary: 'Published version 41', actor: 'ci-bot'}],
    metadata: [
      {label: 'Service', value: 'AWS Lambda'},
      {label: 'Runtime', value: 'nodejs22.x'},
      {label: 'Memory', value: '1024 MB'},
      {label: 'Timeout', value: '30 s'},
    ],
  }),
  mk({
    id: 'cloud-cdn',
    name: 'assets-cdn',
    kind: 'cloud',
    health: 'healthy',
    env: 'prod',
    region: 'global',
    cloud: 'aws',
    owner: OWNERS.storefront,
    tags: ['service:cloudfront', 'edge'],
    cpu: 0,
    mem: 0,
    throughput: '8.1k req/s',
    monthlyUsd: 510,
    costTrendPct: 4.3,
    costBreakdown: [
      {label: 'Data transfer out', usd: 410},
      {label: 'Requests', usd: 100},
    ],
    relationships: [
      {relation: 'Origin', name: 's3-assets-prod', kind: 'cloud', health: 'healthy', targetId: 'cloud-s3-assets'},
      {relation: 'Serves', name: 'web-storefront', kind: 'service', health: 'healthy', targetId: 'svc-storefront'},
    ],
    metadata: [
      {label: 'Service', value: 'Amazon CloudFront'},
      {label: 'Edge locations', value: '410'},
      {label: 'Cache hit', value: '92.4%'},
    ],
  }),

  // ── Network devices ──
  mk({
    id: 'net-core-sw-1',
    name: 'core-switch-1',
    kind: 'network-device',
    health: 'healthy',
    env: 'prod',
    region: 'dc-east',
    cloud: 'on-prem',
    owner: OWNERS.infra,
    tags: ['role:core', 'layer:l3'],
    cpu: 27,
    mem: 38,
    throughput: '38 Gbps',
    monthlyUsd: 0,
    metadata: [
      {label: 'Vendor', value: 'Arista'},
      {label: 'Model', value: '7280R3'},
      {label: 'Firmware', value: 'EOS 4.32.1F'},
      {label: 'Ports', value: '48x25G / 8x100G'},
    ],
  }),
  mk({
    id: 'net-edge-fw-1',
    name: 'edge-firewall-1',
    kind: 'network-device',
    health: 'warn',
    env: 'prod',
    region: 'dc-east',
    cloud: 'on-prem',
    owner: OWNERS.infra,
    tags: ['role:firewall', 'perimeter'],
    cpu: 64,
    mem: 71,
    throughput: '11 Gbps',
    monthlyUsd: 0,
    changes: [{ts: '2026-06-06T09:40:00Z', kind: 'incident', summary: 'Session table 82% utilized', actor: 'monitor:fw-sessions'}],
    metadata: [
      {label: 'Vendor', value: 'Palo Alto'},
      {label: 'Model', value: 'PA-5410'},
      {label: 'Firmware', value: 'PAN-OS 11.2'},
      {label: 'Sessions', value: '1.6M / 2M'},
    ],
  }),
  mk({
    id: 'net-tor-12',
    name: 'tor-rack-12',
    kind: 'network-device',
    health: 'healthy',
    env: 'prod',
    region: 'dc-east',
    cloud: 'on-prem',
    owner: OWNERS.infra,
    tags: ['role:tor', 'rack:12'],
    cpu: 18,
    mem: 24,
    throughput: '22 Gbps',
    monthlyUsd: 0,
    relationships: [
      {relation: 'Uplinks to', name: 'core-switch-1', kind: 'network-device', health: 'healthy', targetId: 'net-core-sw-1'},
      {relation: 'Serves', name: 'web-prod-1', kind: 'host', health: 'healthy', targetId: 'host-web-1'},
    ],
    metadata: [
      {label: 'Vendor', value: 'Arista'},
      {label: 'Model', value: '7050X3'},
      {label: 'Firmware', value: 'EOS 4.31.4M'},
      {label: 'Ports', value: '48x10G'},
    ],
  }),
]

// ── Query hooks ──────────────────────────────────────────────────────────────

const CATALOG_KEY = ['monitoring', 'resource-catalog'] as const

async function fetchResources(): Promise<readonly Resource[]> {
  return api.get<Resource[]>('/monitoring/resources')
}

export function useResourceCatalog(): UseQueryResult<readonly Resource[]> {
  return useQuery({queryKey: CATALOG_KEY, queryFn: fetchResources, staleTime: 30_000})
}

/** Look up a single resource by id from the cached catalog result. */
export function findResource(
  resources: readonly Resource[] | undefined,
  id: string | null,
): Resource | null {
  if (!resources || id === null) return null
  return resources.find((r) => r.id === id) ?? null
}
