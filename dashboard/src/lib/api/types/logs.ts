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

export interface LogEntry {
  logId: string
  timestamp: string
  level: string
  message: string
  body: string
  service: string
  environment: string
  host: string
  source: string
  containerName: string
  containerId: string
  containerImage: string
  traceId: string
  spanId: string
  tags: Record<string, string>
  resourceAttributes: Record<string, string>
  hostId?: string | null
}

export interface LogQueryResponse {
  logs: LogEntry[]
  nextCursor?: string | null
  hasMore: boolean
  totalCount?: number | null
}

export interface LogFilterOptions {
  services: string[]
  environments: string[]
  levels: string[]
  tagKeys: string[]
}

export interface LogFilterOptionWithCount {
  value: string
  count: number
}

export interface LogFilterOptionsWithCounts {
  services: LogFilterOptionWithCount[]
  environments: LogFilterOptionWithCount[]
  levels: string[]
  tagKeys: string[]
}

export interface LogAggregateBucket {
  timestamp: string
  count: number
  groups: Record<string, number>
}

export interface LogAggregateResponse {
  buckets: LogAggregateBucket[]
  totalCount: number
  interval: string
}

export interface LogTopValue {
  value: string
  count: number
}

export interface LogTopResponse {
  field: string
  values: LogTopValue[]
  totalCount: number
}

/** A single key/count breakdown row (e.g. top service or host for a pattern). */
export interface LogPatternBreakdown {
  value: string
  count: number
}

/**
 * Structural pattern that the selected log belongs to, plus rollups across the
 * window. Backs the Patterns tab of the log context viewer.
 */
export interface LogPatternResponse {
  /** Pattern string with variable tokens collapsed to placeholders (e.g. `<int>`). */
  pattern: string
  /** Dominant severity for the cluster. */
  level: string
  /** Match count within the rollup window. */
  count: number
  /** Human label for the rollup window (e.g. "24h"). */
  windowLabel: string
  /** First/last time the pattern was seen (ISO timestamps). */
  firstSeen: string
  lastSeen: string
  /** Percent change versus the previous comparable window; null when unknown. */
  trendPct: number | null
  /** Per-bucket counts for the trend sparkline. */
  sparkline: number[]
  topServices: LogPatternBreakdown[]
  topHosts: LogPatternBreakdown[]
}

export interface RawLogPatternResponse {
  pattern?: string
  level?: string
  count?: number
  windowLabel?: string
  window_label?: string
  firstSeen?: string
  first_seen?: string
  lastSeen?: string
  last_seen?: string
  trendPct?: number | null
  trend_pct?: number | null
  sparkline?: number[]
  topServices?: { value: string; count?: number }[]
  top_services?: { value: string; count?: number }[]
  topHosts?: { value: string; count?: number }[]
  top_hosts?: { value: string; count?: number }[]
}

export interface OtlpApiKey {
  id: string
  name: string
  keyPrefix: string
  createdAt: string
  lastUsedAt?: string
}

/** @deprecated Use OtlpApiKey instead */
export type LogApiKey = OtlpApiKey

export interface CreateOtlpApiKeyResponse {
  id: string
  name: string
  keyPrefix: string
  key: string
  createdAt: string
}

export interface OtlpObservedService {
  id: string
  mappingId?: string | null
  serviceNamespace: string
  serviceName: string
  projectId?: string | null
  projectName?: string | null
  seenLogs: boolean
  seenTraces: boolean
  seenMetrics: boolean
  seenFeedback: boolean
  lastEnvironment?: string | null
  firstSeenAt: string
  lastSeenAt: string
}

export interface OtlpServiceMapping {
  id: string
  serviceNamespace: string
  serviceName: string
  projectId: string
  projectName: string
  updatedAt: string
}

/** @deprecated Use CreateOtlpApiKeyResponse instead */
export type CreateLogApiKeyResponse = CreateOtlpApiKeyResponse

export interface LogIndex {
  id: string
  name: string
  filter_query: string
  retention_days: number
  sampling_rate: number
  priority: number
  is_active: boolean
  daily_quota_gb: number | null
  created_at: string
  updated_at: string
}

export interface CreateLogIndexRequest {
  name: string
  filter_query?: string
  retention_days?: number
  sampling_rate?: number
  priority?: number
  daily_quota_gb?: number | null
}

export interface UpdateLogIndexRequest {
  name?: string
  filter_query?: string
  retention_days?: number
  sampling_rate?: number
  priority?: number
  is_active?: boolean
  daily_quota_gb?: number | null
}

export interface LogIndexTestResult {
  match_count: number
  total_count: number
}

export interface LogIndexUsage {
  index_name: string
  bytes_today: number
  count_today: number
  quota_gb?: number | null
  retention_days?: number | null
}

export interface LogPipelineStep {
  type: 'drop' | 'redact' | 'remap' | 'enrich' | 'parse'
  enabled: boolean
  condition?: string
  source_field?: string
  target_field?: string
  pattern?: string
  replacement?: string
  value?: string
  tags?: Record<string, string>
}

export interface LogPipeline {
  id: string
  name: string
  description: string
  steps: LogPipelineStep[]
  priority: number
  is_active: boolean
  created_at: string
  updated_at: string
}

export interface CreateLogPipelineRequest {
  name: string
  description?: string
  steps?: LogPipelineStep[]
  priority?: number
  is_active?: boolean
}

export type UpdateLogPipelineRequest = Partial<CreateLogPipelineRequest>

export interface LogPipelinePreviewEntry {
  level?: string
  message?: string
  body?: string
  service?: string
  environment?: string
  host?: string
  tags?: Record<string, string>
  resource_attributes?: Record<string, string>
}

export interface LogPipelinePreviewResult {
  before: LogPipelinePreviewEntry
  after?: LogPipelinePreviewEntry | null
  dropped: boolean
}

export interface LogSavedViewState {
  query: string
  levels: string[]
  facets: Record<string, string>
  time_preset: string
  from?: string | null
  to?: string | null
  visualization: string
  group_by?: string | null
  top_field?: string | null
}

export interface LogSavedView {
  id: string
  name: string
  state: LogSavedViewState
  is_shared: boolean
  created_at: string
  updated_at: string
}

export interface CreateLogSavedViewRequest {
  name: string
  state: LogSavedViewState
  is_shared?: boolean
}

export interface UpdateLogSavedViewRequest {
  name?: string
  state?: LogSavedViewState
  is_shared?: boolean
}

export interface LogMetricRule {
  id: string
  name: string
  query: string
  levels: string[]
  group_by?: string | null
  interval: string
  is_active: boolean
  created_at: string
  updated_at: string
}

export interface CreateLogMetricRuleRequest {
  name: string
  query?: string
  levels?: string[]
  group_by?: string | null
  interval?: string
  is_active?: boolean
}

export type UpdateLogMetricRuleRequest = Partial<CreateLogMetricRuleRequest>

export interface LogMonitorDraftRequest {
  name: string
  query?: string
  levels?: string[]
  group_by?: string | null
  condition?: '>' | '<' | '>=' | '<=' | '=='
  threshold: number
  warning_threshold?: number | null
  duration_seconds?: number
  dashboard_id?: string | null
  widget_id?: string | null
}

export interface LogMonitorDraft {
  name: string
  query: string
  levels: string[]
  group_by?: string | null
  condition: string
  threshold: number
  warning_threshold?: number | null
  dashboard_alert_created: boolean
  dashboard_alert_id?: string | null
}

export type LogMonitorCondition = '>' | '>=' | '<' | '<=' | '=='

/** A standalone log monitor: a saved query + threshold the backend evaluates on
 * a schedule and raises alerts from, independent of any dashboard widget. */
export interface LogMonitor {
  id: string
  name: string
  query: string
  levels: string[]
  group_by?: string | null
  condition: LogMonitorCondition
  threshold: number
  warning_threshold?: number | null
  /** Evaluation window in minutes: counts matching logs over this trailing span. */
  window_minutes: number
  is_active: boolean
  created_at: string
  updated_at: string
}

export interface CreateLogMonitorRequest {
  name: string
  query?: string
  levels?: string[]
  group_by?: string | null
  condition?: LogMonitorCondition
  threshold: number
  warning_threshold?: number | null
  window_minutes?: number
}

export type UpdateLogMonitorRequest = Partial<CreateLogMonitorRequest> & {
  is_active?: boolean
}

export interface LogPermissions {
  can_manage: boolean
  can_live_tail: boolean
  can_create_metrics: boolean
  can_create_monitors: boolean
}

export interface RawLogResponse {
  logs?: Record<string, unknown>[]
  nextCursor?: string | null
  next_cursor?: string | null
  hasMore?: boolean
  has_more?: boolean
  totalCount?: number | null
  total_count?: number | null
}

export interface RawLogFilterResponse {
  services?: (string | { value: string; count: number })[]
  environments?: (string | { value: string; count: number })[]
  levels?: string[]
  tagKeys?: string[]
  tag_keys?: string[]
}

export interface RawLogAggregateResponse {
  buckets?: { timestamp: string; count?: number; groups?: Record<string, number> }[]
  total_count?: number
  totalCount?: number
  interval?: string
}

export interface RawLogTopResponse {
  field?: string
  values?: { value: string; count?: number }[]
  total_count?: number
  totalCount?: number
}
