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
// Every hook resolves live data from the backend (`/monitoring/resources` plus the
// per-resource telemetry and ownership endpoints); there is no mock or synthesized data.
// ─────────────────────────────────────────────────────────────────────────────

import {useMutation, useQuery, useQueryClient, type UseMutationResult, type UseQueryResult} from '@tanstack/react-query'
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
  readonly findings: readonly Finding[]
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

// ── Findings (real top vulnerability findings, supplied by the backend) ───────

export interface Finding {
  readonly id: string
  readonly severity: VulnSeverity
  readonly pkg: string
  readonly fixedVersion?: string
  readonly cvss?: number
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

// ── Query hooks ──────────────────────────────────────────────────────────────

const CATALOG_KEY = ['monitoring', 'resource-catalog'] as const

async function fetchResources(): Promise<readonly Resource[]> {
  return api.get<Resource[]>('/monitoring/resources')
}

export function useResourceCatalog(): UseQueryResult<readonly Resource[]> {
  return useQuery({queryKey: CATALOG_KEY, queryFn: fetchResources, staleTime: 30_000})
}

// ── Ownership claim ───────────────────────────────────────────────────────────
// Persisting an owner is a paid-plan feature; the backend returns 403 otherwise.

export interface OwnershipClaimInput {
  readonly resourceId: string
  readonly team: string
  readonly oncall?: string
  readonly slack?: string
  readonly repo?: string
}

/** True when an error from the claim endpoint is the paid-plan entitlement gate. */
export function isOwnershipForbidden(error: unknown): boolean {
  return typeof error === 'object' && error !== null && (error as {status?: number}).status === 403
}

export function useClaimOwnership(): UseMutationResult<Owner, Error, OwnershipClaimInput> {
  const queryClient = useQueryClient()
  return useMutation({
    mutationFn: (input: OwnershipClaimInput) => api.claimResourceOwnership(input) as Promise<Owner>,
    onSuccess: () => {
      void queryClient.invalidateQueries({queryKey: CATALOG_KEY})
    },
  })
}

/** Look up a single resource by id from the cached catalog result. */
export function findResource(
  resources: readonly Resource[] | undefined,
  id: string | null,
): Resource | null {
  if (!resources || id === null) return null
  return resources.find((r) => r.id === id) ?? null
}

// ── Telemetry series ─────────────────────────────────────────────────────────
// Real over-time telemetry for a single resource, fetched per resource + range.
// Every point is an actual sample from the backend; there is no synthesized data.

export interface TelemetryPoint {
  readonly ts: number
  readonly value: number | null
}

export interface TelemetryLine {
  readonly name: string
  readonly points: readonly TelemetryPoint[]
}

export interface TelemetryMetric {
  readonly key: string
  readonly label: string
  readonly unit: string
  readonly lines: readonly TelemetryLine[]
}

export interface ResourceTelemetry {
  readonly kind: string
  readonly rangeSeconds: number
  readonly intervalSeconds: number
  readonly metrics: readonly TelemetryMetric[]
}

/** Resource kinds with a real over-time telemetry source wired today. */
export const TELEMETRY_KINDS: ReadonlySet<ResourceKind> = new Set<ResourceKind>(['host', 'container', 'service'])

const HOST_TELEMETRY_ID = /^host:(?:\d+:)?(\d+)$/

function metaValue(resource: Resource, label: string): string | undefined {
  return resource.metadata.find((m) => m.label === label)?.value
}

/** Build telemetry query params for a resource, or null when it has no series source. */
function telemetryParams(resource: Resource, rangeSeconds: number): URLSearchParams | null {
  const params = new URLSearchParams({kind: resource.kind, rangeSeconds: String(rangeSeconds)})
  switch (resource.kind) {
    case 'host': {
      const hostId = HOST_TELEMETRY_ID.exec(resource.id)?.[1]
      if (!hostId) return null
      params.set('hostId', hostId)
      return params
    }
    case 'service':
      params.set('service', resource.name)
      return params
    case 'container': {
      const host = metaValue(resource, 'Host')
      if (!host) return null
      params.set('host', host)
      params.set('container', resource.name)
      return params
    }
    default:
      return null
  }
}

async function fetchResourceTelemetry(resource: Resource, rangeSeconds: number): Promise<ResourceTelemetry> {
  const params = telemetryParams(resource, rangeSeconds)
  if (!params) return {kind: resource.kind, rangeSeconds, intervalSeconds: 0, metrics: []}
  return api.get<ResourceTelemetry>(`/monitoring/resources/telemetry?${params.toString()}`)
}

export function useResourceTelemetry(
  resource: Resource | null,
  rangeSeconds: number,
): UseQueryResult<ResourceTelemetry> {
  return useQuery({
    queryKey: ['monitoring', 'resource-telemetry', resource?.id, rangeSeconds],
    queryFn: () => fetchResourceTelemetry(resource as Resource, rangeSeconds),
    enabled: resource != null && TELEMETRY_KINDS.has(resource.kind),
    staleTime: 30_000,
  })
}
