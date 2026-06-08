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

export interface ApmTraceListItem {
  traceId: string
  rootService: string
  rootResource: string
  rootName: string
  spanCount: number
  durationNs: number
  startNs: number
  hasError: boolean
  source: string
}

export type ApmTimeRange = '1h' | '6h' | '24h' | '7d' | '30d' | '90d'

export interface ApmTraceListResponse {
  traces: ApmTraceListItem[]
  totalCount: number
}

export type ApmStatusFilter = 'error' | 'ok'

export interface ApmOverviewPreviousStats {
  totalTraces: number
  errorRate: number
  p50DurationNs: number
  p95DurationNs: number
  p99DurationNs: number
  avgSpansPerTrace: number
}

export interface ApmOverviewStats {
  totalTraces: number
  errorTraces: number
  errorRate: number
  serviceCount: number
  sourceCount: number
  p50DurationNs: number
  p95DurationNs: number
  p99DurationNs: number
  avgSpansPerTrace: number
  previous: ApmOverviewPreviousStats
}

export interface ApmLatencyPoint {
  timestamp: string
  p50DurationNs: number
  p95DurationNs: number
  p99DurationNs: number
}

export interface ApmServiceHealthItem {
  service: string
  source: string
  traceCount: number
  errorCount: number
  errorRate: number
  p95DurationNs: number
  avgSpansPerTrace: number
}

export interface ApmResourceHotspotItem {
  service: string
  resource: string
  source: string
  traceCount: number
  errorCount: number
  errorRate: number
  p95DurationNs: number
}

export interface ApmFacetItem {
  value: string
  count: number
}

export interface ApmOverviewFacets {
  services: ApmFacetItem[]
  sources: ApmFacetItem[]
  environments: ApmFacetItem[]
  operations: ApmFacetItem[]
}

export interface ApmOverviewResponse {
  stats: ApmOverviewStats
  latencySeries: ApmLatencyPoint[]
  serviceHealth: ApmServiceHealthItem[]
  resourceHotspots: ApmResourceHotspotItem[]
  errors: ApmErrorGroup[]
  facets: ApmOverviewFacets
}

export interface ApmSpanResponse {
  spanId: string
  traceId: string
  parentId: string
  name: string
  service: string
  resource: string
  type: string
  startNs: number
  durationNs: number
  error: number
  meta: Record<string, string>
  metrics: Record<string, number>
  host: string
  env: string
  version: string
  source: string
  kind?: string
  statusCode?: number
  statusMessage?: string
  events?: string
  links?: string
  resourceAttributes?: Record<string, string>
}

export interface ApmTraceDetailResponse {
  traceId: string
  spans: ApmSpanResponse[]
}

export interface ApmServiceMapEntry {
  service: string
  spanCount: number
  errorCount: number
  avgDurationNs: number
  callsTo: string[]
}

export interface ApmServiceEdge {
  fromService: string
  toService: string
  callCount: number
  errorCount: number
  avgDurationNs: number
}

export interface ApmServiceMapResponse {
  services: ApmServiceMapEntry[]
  edges?: ApmServiceEdge[]
}

export interface ApmServiceLatency {
  service: string
  p50DurationNs: number
  p90DurationNs: number
  p99DurationNs: number
  sampleCount: number
}

export interface ApmErrorGroup {
  id: string
  service: string
  resource: string
  errorMessage: string
  errorType: string
  count: number
  lastSeen: string
  traceId: string
}

export interface ApmServiceFacet {
  service: string
  count: number
}

export interface ApmErrorsResponse {
  errors: ApmErrorGroup[]
  totalCount: number
  serviceFacets: ApmServiceFacet[]
}

export interface ApmResourceStatsItem {
  service: string
  resource: string
  name: string
  type: string
  totalHits: number
  totalErrors: number
  avgDurationNs: number
  errorRate: number
}

export interface ApmResourceStatsResponse {
  resources: ApmResourceStatsItem[]
  totalCount: number
}

export type ApmServiceType = 'web' | 'worker' | 'db' | 'cache'
export type ApmServiceStatus = 'alerting' | 'degraded' | 'healthy'
export type ApmSeverity = 'good' | 'warn' | 'bad'
export type ApmStatusTone = 'success' | 'warning' | 'danger' | 'info' | 'neutral' | 'accent'

export interface ApmEnvPill {
  label: string
  chart: number
}

export interface ApmCatalogSummary {
  total: number
  alerting: number
  degraded: number
}

export interface ApmServiceCatalogRow {
  name: string
  type: ApmServiceType
  status: ApmServiceStatus
  env: ApmEnvPill[]
  rps: number
  spark: number[]
  p95Ms: number
  p99Ms: number
  errorRateLabel: string
  errorBarPct: number
  errorLevel: ApmSeverity
  apdex: string
  apdexTone: ApmStatusTone
  lastDeploy: string
  team: string
  language?: string
  sources: string[]
}

export interface ApmServiceCatalogResponse {
  services: ApmServiceCatalogRow[]
  summary: ApmCatalogSummary
}

export interface ApmStatDeltaSpec {
  value: string
  direction: 'up' | 'down' | 'flat'
  tone?: ApmStatusTone
}

export interface ApmKpiSpec {
  label: string
  value: string
  valueTone?: ApmStatusTone
  delta?: ApmStatDeltaSpec
}

export interface ApmServiceLatencyPoint {
  t: string
  p50: number
  p90: number
  p95: number
  p99: number
}

export interface ApmThroughputPoint {
  t: string
  rps: number
  errors?: number
}

export interface ApmGaugeRow {
  label: string
  valueText: string
  pct: number
  level: ApmSeverity
}

export interface ApmErrorBar {
  h: number
  level: 'warn' | 'bad'
}

export interface ApmServiceResourceRow {
  slug: string
  method: string
  name: string
  rps: number
  p50Ms: number
  p95Ms: number
  p99Ms: number
  errorRateLabel: string
  errorBarPct: number
  errorLevel: ApmSeverity
  timePct: number
  status: ApmServiceStatus
}

export interface ApmDependencyRow {
  name: string
  type: ApmServiceType
  tone: ApmStatusTone
  rps: string
  p95: string
  err: string
  errWarn?: boolean
}

export interface ApmDeploymentRow {
  version: string
  when: string
  initials: string
  rps: string
  errorRate: string
  p95: string
  status: ApmServiceStatus | 'retired'
  current?: boolean
  trendBad?: boolean
}

export interface ApmPodRow {
  pod: string
  node: string
  cpu: number
  mem: string
  restarts: number
  tone: ApmStatusTone
  state: string
}

export type ApmErrorSeverity = 'fatal' | 'error' | 'warn'

export interface ApmServiceErrorRow {
  severity: ApmErrorSeverity
  title: string
  sub: string
  chips: string[]
  events: string
  users?: string
  unhandled?: boolean
}

export interface ApmServiceTraceRow {
  time: string
  traceId: string
  resource?: string | null
  method?: string | null
  httpStatus: number
  durationMs: number
  spans?: number | null
  bucket?: string | null
}

export interface ApmServiceDetail {
  name: string
  type: ApmServiceType
  status: ApmServiceStatus
  runtime: string
  team: string
  version: string
  deployedAgo: string
  sources: string[]
  kpis: ApmKpiSpec[]
  latency: ApmServiceLatencyPoint[]
  latencyThresholdMs: number
  latencyThresholdLabel: string
  deployAt: string
  throughput: ApmThroughputPoint[]
  errorBars: ApmErrorBar[]
  p95ByResource: ApmGaugeRow[]
  resources: ApmServiceResourceRow[]
  upstream: ApmDependencyRow[]
  downstream: ApmDependencyRow[]
  depInsight: string
  deployments: ApmDeploymentRow[]
  podMemory: ApmGaugeRow[]
  pods: ApmPodRow[]
  errors: ApmServiceErrorRow[]
  traces: ApmServiceTraceRow[]
}

export interface ApmDistBar {
  h: number
  band: ApmSeverity
}

export interface ApmDistMarker {
  label: string
  left: number
  p99?: boolean
}

export type ApmWaterfallTone = 'root' | 'app' | 'db' | 'cache' | 'http' | 'error'

export interface ApmWaterfallRow {
  op: string
  desc: string
  left: number
  width: number
  label: string
  tone: ApmWaterfallTone
  indent?: number
  selected?: boolean
}

export interface ApmResourceExemplar {
  traceId: string
  httpStatus: number
  durationLabel: string
  rows: ApmWaterfallRow[]
}

export interface ApmResourceDetail {
  serviceName: string
  method: string
  path: string
  status: ApmServiceStatus
  kind: string
  topDependency: string
  kpis: ApmKpiSpec[]
  latency: ApmServiceLatencyPoint[]
  latencyThresholdMs: number
  latencyThresholdLabel: string
  deployAt: string
  throughput: ApmThroughputPoint[]
  distribution: ApmDistBar[]
  distMarkers: ApmDistMarker[]
  distAxis: string[]
  whereTimeSpent: ApmGaugeRow[]
  whereInsight: string
  exemplar: ApmResourceExemplar
  slowTraces: ApmServiceTraceRow[]
  errors: ApmServiceErrorRow[]
}
