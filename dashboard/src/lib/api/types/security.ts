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

// Wire shapes mirror the backend DTOs in
// backend/src/main/kotlin/com/moneat/security/{signals,detection}; field names are snake_case
// to match the Kotlin @SerialName values exactly.

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

export type DetectionRuleType = 'threshold' | 'new_value'

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
