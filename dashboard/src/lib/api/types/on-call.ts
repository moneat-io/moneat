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
  currentOnCall?: {
    userId: string
    userName: string
  }
  slackUsergroupId?: string
  slackUsergroupHandle?: string
}

export interface OnCallParticipant {
  id: string
  scheduleId: string
  userId: string
  userName: string
  position: number
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

/**
 * Server-authoritative rollout state for native incident response. The backend
 * decides `enabled`; the remaining states carry the reason so the dashboard can
 * show a calm, accurate unavailable message. Mirrors the backend enum returned
 * by `GET /v1/on-call/incident-response/capabilities`.
 */
export type NativeIncidentRolloutState =
  | 'ENABLED'
  | 'DISABLED'
  | 'ENVIRONMENT_NOT_FOUND'
  | 'FLAG_NOT_FOUND'
  | 'EVALUATION_ERROR'
  | 'PROVIDER_UNAVAILABLE'
  | 'MODULE_UNAVAILABLE'

export interface NativeIncidentCapabilities {
  /** Whether native incident dashboard surfaces and actions may be shown. */
  enabled: boolean
  /** Feature-flag environment the decision was evaluated in. */
  environment: string
  state: NativeIncidentRolloutState
  /** Paid-plan entitlement is evaluated independently from rollout state. */
  entitlementEnabled: boolean
  plan: string
  entitlementReason: string | null
  quotas: Record<string, NativeIncidentQuotaStatus>
  /** Always false: forwarded external-provider incident passthrough is never gated by this rollout. */
  externalProviderPassthroughAffected: boolean
}

export interface NativeIncidentQuotaStatus {
  limit: number
  used: number
  remaining: number
  exhausted: boolean
}
