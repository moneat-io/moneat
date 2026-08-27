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

export interface Priority {
  id: string
  organizationId: string
  priority: string
  isPageable: boolean
  label: string
  description?: string
}

export interface BusinessHoursWindow {
  id: string
  businessHoursId: string
  dayOfWeek: number
  startTime: string
  endTime: string
}

export interface BusinessHours {
  id: string
  organizationId: string
  timezone: string
  enabled: boolean
  windows: BusinessHoursWindow[]
}

export type OnCallRotationType = 'DAILY' | 'WEEKLY' | 'CUSTOM'

export interface OnCallSchedule {
  id: string
  organizationId: string
  name: string
  rotationType: OnCallRotationType
  handoffTime: string
  timezone: string
  createdAt: string
  updatedAt: string
  participants: OnCallParticipant[]
  overrides: OnCallOverride[]
  layers?: OnCallScheduleLayer[]
  currentOnCall?: {
    userId: string
    userName: string
  }
  slackUsergroupId?: string
  slackUsergroupHandle?: string
  slackInstallationId?: string | null
}

export interface OnCallParticipant {
  id: string
  userId: string
  userName: string
  userEmail: string
  position: number
  scheduleId?: string
}

export interface OnCallOverride {
  id: string
  scheduleId: string
  userId: string
  userName: string
  startAt: string
  endAt: string
  createdBy: string
}

export interface OnCallScheduleLayer {
  id: string
  scheduleId: string
  name: string
  layerOrder: number
  rotationType: OnCallRotationType
  handoffTime: string
  timezone: string
  enabled: boolean
  explicitGap: boolean
  participants: OnCallParticipant[]
  createdAt: string
  updatedAt: string
}

export interface OnCallResponderResolution {
  userId: string
  userName: string
  userEmail: string
  scheduleId: string
  layerId?: string
  source: 'OVERRIDE' | 'LAYER' | 'ROTATION'
  activeUntil?: string
}

export interface EscalationTarget {
  id: string
  escalationStepId: string
  targetType: 'USER' | 'ON_CALL_SCHEDULE'
  targetId: string
  targetName: string
}

export interface EscalationStep {
  id: string
  escalationPolicyId: string
  stepOrder: number
  timeoutMinutes: number
  smsFallbackDelayMinutes: number
  createdAt: string
  targets: EscalationTarget[]
}

export interface EscalationPolicy {
  id: string
  organizationId: string
  name: string
  description?: string
  repeatCount: number
  createdAt: string
  updatedAt: string
  steps: EscalationStep[]
}

export type OnCallAlertStatus = 'TRIGGERED' | 'ACKNOWLEDGED' | 'RESOLVED'

export interface AlertRouteActionSummary {
  state: 'SUCCEEDED' | 'SKIPPED' | 'FAILED'
  reason: string
}

export interface AlertRouteOutcomeSummary {
  matchedRouteId: string
  matchedRouteRevision: number
  groupId: string
  incidentId?: string
  grouping: AlertRouteActionSummary
  paging: AlertRouteActionSummary
  incident: AlertRouteActionSummary
}

export interface OnCallAlert {
  id: string
  organizationId: string
  escalationPolicyId: string
  title: string
  description?: string
  priority: string
  status: OnCallAlertStatus
  alertSource: string
  deduplicationKey?: string
  triggeredAt: string
  acknowledgedAt?: string
  acknowledgedBy?: string
  acknowledgedByName?: string
  resolvedAt?: string
  resolvedBy?: string
  resolvedByName?: string
  metadata?: Record<string, unknown>
  nextEscalationAt?: string
  viewedByCurrentUser?: boolean
  routeOutcome?: AlertRouteOutcomeSummary
}

export interface OnCallTimelineEvent {
  id: string
  targetId: string
  eventType: string
  actorUserId?: string
  actorName?: string
  actorUserName?: string
  details?: Record<string, unknown>
  createdAt: string
  source?: string
  alertId?: string
  alertTitle?: string
}

export interface OnCallAlertDetail extends OnCallAlert {
  timeline?: OnCallTimelineEvent[]
}

// Native declared-incident lifecycle. The wire status OPEN was migrated to
// ACTIVE; keep this union aligned with the enterprise native incident aggregate.
export type OnCallIncidentStatus =
  | 'TRIAGE'
  | 'ACTIVE'
  | 'RESOLVED'
  | 'POST_INCIDENT'
  | 'CLOSED'
  | 'CANCELLED'
  | 'DECLINED'
  | 'MERGED'

export type OnCallIncidentMode = 'LIVE' | 'RETROSPECTIVE' | 'TEST'

export type OnCallIncidentVisibility = 'ORGANIZATION' | 'PRIVATE' | 'PUBLIC'

export interface OnCallIncident {
  id: string
  organizationId: string
  title: string
  description?: string
  summary?: string
  customerImpact?: string
  nextUpdateAt?: string
  updateReminderPaused?: boolean
  lastUpdateAt?: string
  // Triage incidents stay unclassified until they are accepted.
  severity?: string
  status: OnCallIncidentStatus
  mode?: OnCallIncidentMode
  visibility?: OnCallIncidentVisibility
  incidentType?: string
  version?: number
  declaredBy: string
  declaredByName?: string
  declaredAt: string
  triagedAt?: string
  acceptedAt?: string
  resolvedBy?: string
  resolvedByName?: string
  resolvedAt?: string
  postIncidentAt?: string
  closedAt?: string
  cancelledAt?: string
  declinedAt?: string
  mergedAt?: string
  mergedIntoIncidentId?: string
  alertCount: number
  alerts?: Array<{ id: string; title: string; status: string; priority?: string }>
  createdAt: string
  updatedAt: string
}

export interface IncidentUpdateInput {
  message?: string
  title?: string
  description?: string
  summary?: string
  severity?: string
  customerImpact?: string
  nextUpdateAt?: string
  clearNextUpdateAt?: boolean
  pauseUpdateReminders?: boolean
  status?: OnCallIncidentStatus
  expectedVersion?: number
}

export interface IncidentUpdateRequestInput {
  message?: string
  dueAt?: string
  expectedVersion?: number
}

export interface IncidentUpdateReminderInput {
  rescheduleAt?: string
  expectedVersion?: number
}

export type IncidentActionState = 'OPEN' | 'CLAIMED' | 'COMPLETED' | 'CANCELLED' | 'FOLLOW_UP'
export type IncidentActionSource =
  | 'COMMAND'
  | 'MODAL'
  | 'REACTION'
  | 'MESSAGE_SHORTCUT'
  | 'DASHBOARD'
  | 'API'
  | 'WORKFLOW'
  | 'AI_PROPOSAL'
  | 'SLACK'

export interface OnCallIncidentAction {
  id: string
  incidentId: string
  description: string
  assigneeUserId?: string | null
  assigneeName?: string | null
  state: IncidentActionState
  source: IncidentActionSource
  slackChannelId?: string | null
  slackMessageTs?: string | null
  createdBy?: string | null
  claimedAt?: string | null
  completedAt?: string | null
  cancelledAt?: string | null
  convertedToFollowUpAt?: string | null
  createdAt: string
  updatedAt: string
}

export interface IncidentActionEvent {
  id: string
  eventType: string
  fromState?: IncidentActionState | null
  toState?: IncidentActionState | null
  actorUserId?: string | null
  actorName?: string | null
  details?: Record<string, unknown>
  createdAt: string
}

export interface IncidentActionMetrics {
  total: number
  open: number
  claimed: number
  completed: number
  cancelled: number
  followUp: number
}

export interface CreateIncidentActionInput {
  description: string
  assigneeUserId?: string
  source?: IncidentActionSource
  slackChannelId?: string
  slackMessageTs?: string
  expectedVersion?: number
}

export interface IncidentActionMutationInput {
  note?: string
  expectedVersion?: number
}

export interface ReassignIncidentActionInput {
  assigneeUserId: string
  expectedVersion?: number
}

export type IncidentFollowUpStatus = 'OPEN' | 'ACCEPTED' | 'COMPLETED' | 'CANCELLED'
export type IncidentFollowUpPriority = 'P0' | 'P1' | 'P2' | 'P3' | 'P4' | 'P5'

export interface OnCallIncidentFollowUp {
  id: string
  incidentId: string
  title: string
  description: string
  ownerUserId?: string | null
  ownerUserName?: string | null
  ownerTeamId?: string | null
  ownerTeamName?: string | null
  priority: IncidentFollowUpPriority
  labels: string[]
  dueAt?: string | null
  slaMinutes?: number | null
  reminderMinutes?: number | null
  nextReminderAt?: string | null
  escalationLevel: number
  status: IncidentFollowUpStatus
  acceptedBy?: string | null
  acceptedAt?: string | null
  completedBy?: string | null
  completedAt?: string | null
  createdBy?: string | null
  source: IncidentActionSource
  slackChannelId?: string | null
  slackMessageTs?: string | null
  createdAt: string
  updatedAt: string
}

export interface CreateIncidentFollowUpInput {
  title: string
  description: string
  ownerUserId?: string
  ownerTeamId?: string
  priority?: IncidentFollowUpPriority
  labels?: string[]
  dueAt?: string
  slaMinutes?: number
  reminderMinutes?: number
  source?: IncidentActionSource
  slackChannelId?: string
  slackMessageTs?: string
  expectedVersion?: number
}

export interface UpdateIncidentFollowUpInput {
  title?: string
  description?: string
  ownerUserId?: string
  ownerTeamId?: string
  priority?: IncidentFollowUpPriority
  labels?: string[]
  dueAt?: string
  clearDueAt?: boolean
  slaMinutes?: number
  reminderMinutes?: number
  clearReminderAt?: boolean
  expectedVersion?: number
}

export interface IncidentFollowUpStatusInput {
  note?: string
  expectedVersion?: number
}

export interface OnCallIncidentDetail extends OnCallIncident {
  timeline?: OnCallTimelineEvent[]
}

export interface DeviceToken {
  id: string
  userId: string
  deviceToken: string
  platform: 'IOS' | 'ANDROID'
  deviceName?: string
  createdAt: string
  lastUsedAt?: string
}

export interface CreateOnCallScheduleRequest {
  name: string
  rotationType: OnCallRotationType
  handoffTime: string
  timezone: string
  participants: { userId: string; position: number }[]
}

export interface UpdateOnCallScheduleRequest {
  name?: string
  rotationType?: OnCallRotationType
  handoffTime?: string
  timezone?: string
  participants?: { userId: string; position: number }[]
}

export interface CreateOverrideRequest {
  userId: string
  startAt: string
  endAt: string
}

export interface CreateScheduleLayerRequest {
  name: string
  layerOrder: number
  rotationType: OnCallRotationType
  handoffTime: string
  timezone: string
  enabled?: boolean
  explicitGap?: boolean
  participants?: { userId: string; position: number }[]
}

export interface UpdateScheduleLayerRequest {
  name?: string
  layerOrder?: number
  rotationType?: OnCallRotationType
  handoffTime?: string
  timezone?: string
  enabled?: boolean
  explicitGap?: boolean
  participants?: { userId: string; position: number }[]
}

export interface CreateEscalationPolicyRequest {
  name: string
  description?: string
  repeatCount: number
  steps: {
    stepOrder: number
    timeoutMinutes: number
    smsFallbackDelayMinutes?: number
    targets: {
      targetType: 'USER' | 'ON_CALL_SCHEDULE'
      targetId: string
    }[]
  }[]
}

export interface UpdateEscalationPolicyRequest {
  name?: string
  description?: string
  repeatCount?: number
  steps?: {
    stepOrder: number
    timeoutMinutes: number
    smsFallbackDelayMinutes?: number
    targets: {
      targetType: 'USER' | 'ON_CALL_SCHEDULE'
      targetId: string
    }[]
  }[]
}

export interface UpdatePrioritiesRequest {
  priorities: {
    priority: string
    isPageable: boolean
    label: string
    description?: string
  }[]
}

export interface UpdateBusinessHoursRequest {
  timezone: string
  enabled: boolean
  windows: {
    dayOfWeek: number
    startTime: string
    endTime: string
  }[]
}

export interface RegisterDeviceRequest {
  deviceToken: string
  platform: 'IOS' | 'ANDROID'
  deviceName?: string
}

export interface OnCallAlertListFilters {
  status?: OnCallAlertStatus | OnCallAlertStatus[]
  priority?: string
  fromDate?: string
  toDate?: string
}

export interface OnCallContactSettings {
  phoneNumber: string | null
  onCallPhoneOptIn: boolean
  onCallPhoneConsentedAt: string | null
  onCallPhoneConsentVersion: string | null
}

export interface NativeIncidentCapabilities {
  /** Whether native incident dashboard surfaces and actions may be shown. */
  enabled: boolean
  /** Paid-plan entitlement is the authoritative availability decision. */
  entitlementEnabled: boolean
  plan: string
  entitlementReason: string | null
  quotas: Record<string, NativeIncidentQuotaStatus>
  /** Always false: forwarded external-provider incident passthrough is independent. */
  externalProviderPassthroughAffected: boolean
}

export interface NativeIncidentQuotaStatus {
  limit: number
  used: number
  remaining: number
  exhausted: boolean
}
