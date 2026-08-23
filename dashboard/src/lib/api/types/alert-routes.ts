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

// Types for enterprise Alert Routes and Alert Groups. These mirror the backend
// DTOs under `com.moneat.enterprise.alertroutes.routes`. All public identifiers
// are opaque UUID strings and the wire enum values match the backend exactly.

export type AlertRouteConditionField =
  | 'source'
  | 'status'
  | 'priority'
  | 'title'
  | 'description'
  | 'deduplication_key'
  | 'url'
  | 'episode_key'
  | 'episode_sequence'
  | 'episode_status'
  | 'metadata'
  | 'service'
  | 'team'
  | 'catalog_resource'

export type AlertRouteConditionOperator =
  | 'EQUALS'
  | 'NOT_EQUALS'
  | 'ONE_OF'
  | 'NOT_ONE_OF'
  | 'CONTAINS'
  | 'NOT_CONTAINS'
  | 'STARTS_WITH'
  | 'ENDS_WITH'
  | 'IS_SET'
  | 'IS_NOT_SET'
  | 'AT_LEAST_AS_SEVERE_AS'

export type AlertRoutePagingMode = 'NONE' | 'FIRST_EPISODE_PER_GROUP' | 'EVERY_EPISODE'

export type AlertRouteTargetKind = 'ESCALATION_POLICY' | 'TEAM' | 'OWNERSHIP_DERIVED'

export type AlertRouteOwnershipSource = 'SERVICE' | 'TEAM' | 'CATALOG'

export type AlertRouteGroupingBehavior = 'SUGGESTED' | 'AUTOMATIC'

export type AlertRouteGroupingWindowKind = 'FIXED' | 'ROLLING'

export type AlertRouteIncidentMode = 'TRIAGE' | 'ACTIVE'

export interface AlertRouteCondition {
  field: AlertRouteConditionField
  operator: AlertRouteConditionOperator
  values: string[]
  metadataKey?: string | null
}

export interface AlertRouteConditionGroup {
  conditions: AlertRouteCondition[]
}

export interface AlertRouteTarget {
  kind: AlertRouteTargetKind
  escalationPolicyId?: string | null
  teamId?: string | null
  ownershipSource?: AlertRouteOwnershipSource | null
}

export interface AlertRoutePaging {
  mode: AlertRoutePagingMode
  targets: AlertRouteTarget[]
}

export interface AlertRouteGrouping {
  behavior: AlertRouteGroupingBehavior
  keys: string[]
  windowKind: AlertRouteGroupingWindowKind
  windowSeconds: number
}

export interface AlertRouteIncidentConfig {
  create: boolean
  mode: AlertRouteIncidentMode
  titleTemplate?: string | null
  summaryTemplate?: string | null
  severity?: string | null
  incidentType?: string | null
}

export interface AlertRouteRecovery {
  cancelEscalations: boolean
  declineUnacceptedTriage: boolean
  graceSeconds: number
}

export interface AlertRoute {
  id: string
  key: string
  name: string
  description?: string | null
  enabled: boolean
  position: number
  revision: number
  conditionGroups: AlertRouteConditionGroup[]
  paging: AlertRoutePaging
  grouping: AlertRouteGrouping
  incident: AlertRouteIncidentConfig
  recovery: AlertRouteRecovery
  createdAt: string
  updatedAt: string
}

// Mutation payload. `expectedRevision` is required on update/delete for
// optimistic concurrency; it is omitted on create.
export interface AlertRouteRequest {
  key: string
  name: string
  description?: string | null
  enabled: boolean
  expectedRevision?: number
  conditionGroups: AlertRouteConditionGroup[]
  paging: AlertRoutePaging
  grouping: AlertRouteGrouping
  incident: AlertRouteIncidentConfig
  recovery: AlertRouteRecovery
}

export interface AlertRouteOrderEntry {
  id: string
  expectedRevision: number
}

export interface ReorderAlertRoutesRequest {
  routes: AlertRouteOrderEntry[]
}

export interface AlertRouteSample {
  source: string
  status: string
  priority: string
  title: string
  description: string
  deduplicationKey: string
  url: string
  metadata: Record<string, string>
}

export interface AlertRoutePreviewRequest {
  episodeId?: string
  sample?: AlertRouteSample
  metadata?: Record<string, string>
}

export interface AlertRouteConditionExplanation {
  field: AlertRouteConditionField
  operator: AlertRouteConditionOperator
  values: string[]
  metadataKey?: string | null
  actualValue?: string | null
  matched: boolean
}

export interface AlertRouteGroupExplanation {
  position: number
  matched: boolean
  conditions: AlertRouteConditionExplanation[]
}

export type AlertRouteEvaluationReason =
  | 'SELECTED'
  | 'NO_GROUP_MATCHED'
  | 'ROUTE_DISABLED'
  | 'EARLIER_ROUTE_SELECTED'

export interface AlertRouteEvaluation {
  routeId: string
  key: string
  name: string
  position: number
  enabled: boolean
  matched: boolean
  selected: boolean
  reason: AlertRouteEvaluationReason
  groups: AlertRouteGroupExplanation[]
}

export interface AlertRoutePagingResolution {
  mode: AlertRoutePagingMode
  escalationPolicyIds: string[]
  teamIds: string[]
  unresolvedTargets: string[]
}

export interface AlertRouteGroupingResolution {
  behavior: AlertRouteGroupingBehavior
  automatic: boolean
  groupKey: string
  keys: string[]
  tuple: Record<string, string>
  singleton: boolean
  unresolvedKeys: string[]
  windowKind: AlertRouteGroupingWindowKind
  windowSeconds: number
}

export interface AlertRouteIncidentResolution {
  create: boolean
  mode: AlertRouteIncidentMode
  title?: string | null
  summary?: string | null
  severity?: string | null
  incidentType?: string | null
  unresolvedReferences: string[]
}

export interface AlertRouteDecision {
  routeId: string
  key: string
  name: string
  position: number
  revision: number
  paging: AlertRoutePagingResolution
  grouping: AlertRouteGroupingResolution
  incident: AlertRouteIncidentResolution
  recovery: AlertRouteRecovery
}

export interface AlertRoutePreviewResponse {
  matchedRouteId?: string | null
  decision?: AlertRouteDecision | null
  evaluations: AlertRouteEvaluation[]
  droppedMetadataKeys: string[]
}

export type AlertRouteChangeType = 'CREATED' | 'UPDATED' | 'REORDERED' | 'DELETED'

export interface AlertRouteRevision {
  id: string
  routeId: string
  revision: number
  changeType: AlertRouteChangeType
  actorId?: string | null
  commandKey: string
  snapshot: Record<string, unknown>
  createdAt: string
}

// ──── Alert Groups ────

export type AlertGroupState = 'OPEN' | 'CLOSED'

export type AlertGroupMemberState = 'ACTIVE' | 'REMOVED' | 'UNRELATED'

export type AlertGroupPagingState = 'READY' | 'CLAIMED' | 'DELIVERED' | 'FAILED' | 'NOT_REQUIRED'

export type AlertGroupDecisionType =
  | 'GROUP_CREATED'
  | 'SUGGESTED'
  | 'MARKED_UNRELATED'
  | 'REMOVED'
  | 'ATTACHED'
  | 'AUTOMATICALLY_ATTACHED'
  | 'TRIAGE_CREATED'
  | 'RECOVERY_COMPLETED'

export interface AlertGroupMember {
  id: string
  episodeId: string
  state: AlertGroupMemberState
  version: number
  pagingState: AlertGroupPagingState
  firstJoinedAt: string
  lastSeenAt: string
  resolvedAt?: string | null
}

export interface AlertGroupDecision {
  id: string
  type: AlertGroupDecisionType
  episodeId?: string | null
  incidentId?: string | null
  actorId?: string | null
  commandKey: string
  details: Record<string, unknown>
  createdAt: string
}

export interface AlertGroup {
  id: string
  routeId: string
  routeRevision: number
  identityHash: string
  groupingTuple: Record<string, unknown>
  singleton: boolean
  behavior: AlertRouteGroupingBehavior
  windowKind: AlertRouteGroupingWindowKind
  windowSeconds: number
  openedAt: string
  lastAlertAt: string
  closesAt: string
  state: AlertGroupState
  version: number
  incidentId?: string | null
  candidateIncidentId?: string | null
  routeSnapshot: Record<string, unknown>
  incidentTemplateSnapshot: Record<string, unknown>
  pagingMode: AlertRoutePagingMode
  pagingState: AlertGroupPagingState
  members: AlertGroupMember[]
  decisions: AlertGroupDecision[]
  createdAt: string
  updatedAt: string
}

export interface AlertGroupListFilters {
  limit?: number
  offset?: number
}

export interface AttachAlertGroupInput {
  expectedVersion: number
  incidentId: string
}

export interface CreateAlertGroupTriageInput {
  expectedVersion: number
  title?: string
  summary?: string
}

// ──── Incident triage commands (accept / decline / merge) ────

export interface AcceptIncidentInput {
  severity?: string
  incidentTypeId?: string
  fields?: Record<string, unknown>
  expectedVersion?: number
}

export interface DeclineIncidentInput {
  reason?: string
  expectedVersion?: number
}

export interface MergeIncidentInput {
  sourceIncidentId: string
  expectedVersion?: number
}
