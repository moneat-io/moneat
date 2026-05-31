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

// Wire shapes mirror backend security DTOs. Most API DTOs here use snake_case to match Kotlin
// @SerialName values; compliance trend DTOs intentionally use camelCase.

export type SignalSeverity = 'info' | 'low' | 'medium' | 'high' | 'critical'
export type SignalStatus = 'open' | 'under_review' | 'archived'
export type ArchiveReason = 'true_positive' | 'false_positive' | 'benign'

export interface SignalResponse {
  id: number
  source: string
  rule_id: string
  rule_name: string
  severity: SignalSeverity
  status: SignalStatus
  archive_reason?: ArchiveReason | null
  dedup_key: string
  entities: Record<string, string>
  sample_count: number
  assignee_user_id?: number | null
  tags: string[]
  first_seen: string
  last_seen: string
  created_at: string
  updated_at: string
}

export interface SignalEvidenceResponse {
  id: number
  evidence_type: string
  reference: string
  created_at: string
}

export interface SignalAuditResponse {
  id: number
  actor_user_id?: number | null
  action: string
  from_status?: SignalStatus | null
  to_status?: SignalStatus | null
  reason?: ArchiveReason | null
  note?: string | null
  created_at: string
}

export interface SignalListResponse {
  signals: SignalResponse[]
  total_count: number
}

export interface SignalDetailResponse {
  signal: SignalResponse
  evidence: SignalEvidenceResponse[]
  audit: SignalAuditResponse[]
  // Raw ClickHouse evidence rows; shape varies by source, so kept opaque.
  sample_events: Record<string, unknown>[]
  threat_intel: ThreatIntelEnrichmentResponse[]
}

export interface SignalListParams {
  status?: SignalStatus
  severity?: SignalSeverity
  source?: string
  limit?: number
  offset?: number
}

export interface TriageRequest {
  status?: SignalStatus
  reason?: ArchiveReason
  assignee_user_id?: number | null
  clear_assignee?: boolean
  note?: string
}

export interface ThreatIntelEnrichmentResponse {
  entity_key: string
  entity_value: string
  indicator_type: string
  feed_name: string
  source: string
  threat_type: string
  confidence: number
  reference?: string
  updated_at: string
}

export type DetectionRuleType = 'threshold' | 'new_value' | 'rate_anomaly'

export interface DetectionRuleResponse {
  id: number
  name: string
  description: string
  source: string
  filter: string
  group_by: string[]
  window_seconds: number
  type: DetectionRuleType
  threshold_count?: number | null
  severity: SignalSeverity
  signal_title: string
  signal_message: string
  suppressions: string[]
  enabled: boolean
  tags: string[]
  created_at: string
  updated_at: string
}

export interface DetectionRuleListResponse {
  rules: DetectionRuleResponse[]
  total_count: number
}

export interface DetectionCoverageResponse {
  enabled_rule_count: number
  tactics: MitreTacticCoverage[]
  techniques: MitreTechniqueCoverage[]
}

export interface MitreTacticCoverage {
  tactic: string
  technique_count: number
  rule_count: number
}

export interface MitreTechniqueCoverage {
  technique_id: string
  tactics: string[]
  rule_count: number
  rules: MitreCoveredRule[]
}

export interface MitreCoveredRule {
  id: number
  name: string
  enabled: boolean
}

export interface CreateDetectionRuleRequest {
  name: string
  description?: string
  source?: string
  filter?: string
  group_by?: string[]
  window_seconds?: number
  type?: DetectionRuleType
  threshold_count?: number | null
  severity?: SignalSeverity
  signal_title?: string
  signal_message?: string
  suppressions?: string[]
  enabled?: boolean
  tags?: string[]
}

// Update is a partial — every field is optional and only sent ones are changed.
export type UpdateDetectionRuleRequest = Partial<CreateDetectionRuleRequest>

export interface DetectionMatchSample {
  group_values: Record<string, string>
  count: number
}

export interface DetectionPreviewResponse {
  match_count: number
  samples: DetectionMatchSample[]
  window_seconds: number
}

export interface ComplianceTrendBucket {
  bucketStart: string
  passed: number
  failed: number
  skipped: number
  error: number
  total: number
  passRate: number
}

export interface ComplianceFrameworkTrend {
  framework: string
  buckets: ComplianceTrendBucket[]
}

export interface ComplianceTrendResponse {
  frameworks: ComplianceFrameworkTrend[]
}

export interface VulnerabilitySummaryResponse {
  package_count: number
  finding_count: number
  critical_count: number
  high_count: number
}

export interface VulnerabilityInventoryItem {
  package_name: string
  package_version: string
  package_type: string
  ecosystem: string
  purl: string
  target_type: string
  target_name: string
  host: string
  image_name: string
  container_id: string
  last_seen: string
  finding_count: number
}

export interface VulnerabilityInventoryResponse {
  inventory: VulnerabilityInventoryItem[]
  total_count: number
}

export interface VulnerabilityFindingResponse {
  signal_id: number
  advisory_id: string
  cve_id?: string | null
  package_name: string
  package_version: string
  package_type: string
  ecosystem: string
  target_name: string
  severity: SignalSeverity
  cvss_score?: number | null
  fixed_version?: string | null
  link: string
  status: SignalStatus
  last_seen: string
}

export interface VulnerabilityFindingListResponse {
  findings: VulnerabilityFindingResponse[]
  total_count: number
}

export interface VulnerabilityListParams {
  search?: string
  package?: string
  target?: string
  severity?: SignalSeverity
  status?: SignalStatus
  limit?: number
  offset?: number
}
