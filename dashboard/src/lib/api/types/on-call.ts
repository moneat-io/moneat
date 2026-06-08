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
  id: number
  organizationId: number
  priority: string
  isPageable: boolean
  label: string
  description?: string
}

export interface BusinessHoursWindow {
  id: number
  businessHoursId: number
  dayOfWeek: number
  startTime: string
  endTime: string
}

export interface BusinessHours {
  id: number
  organizationId: number
  timezone: string
  enabled: boolean
  windows: BusinessHoursWindow[]
}

export type OnCallRotationType = 'DAILY' | 'WEEKLY' | 'CUSTOM'

export interface OnCallSchedule {
  id: number
  organizationId: number
  name: string
  rotationType: OnCallRotationType
  handoffTime: string
  timezone: string
  createdAt: string
  updatedAt: string
  participants: OnCallParticipant[]
  overrides: OnCallOverride[]
  currentOnCall?: {
    userId: number
    userName: string
  }
  slackUsergroupId?: string
  slackUsergroupHandle?: string
}

export interface OnCallParticipant {
  id: number
  scheduleId: number
  userId: number
  userName: string
  position: number
}

export interface OnCallOverride {
  id: number
  scheduleId: number
  userId: number
  userName: string
  startAt: string
  endAt: string
  createdBy: number
}

export interface EscalationTarget {
  id: number
  escalationStepId: number
  targetType: 'USER' | 'ON_CALL_SCHEDULE'
  targetId: number
  targetName: string
}

export interface EscalationStep {
  id: number
  escalationPolicyId: number
  stepOrder: number
  timeoutMinutes: number
  smsFallbackDelayMinutes: number
  createdAt: string
  targets: EscalationTarget[]
}

export interface EscalationPolicy {
  id: number
  organizationId: number
  name: string
  description?: string
  repeatCount: number
  createdAt: string
  updatedAt: string
  steps: EscalationStep[]
}

export type IncidentStatus = 'TRIGGERED' | 'ACKNOWLEDGED' | 'RESOLVED'

export interface Incident {
  id: number
  organizationId: number
  escalationPolicyId: number
  title: string
  description?: string
  priority: string
  status: IncidentStatus
  alertSource: string
  deduplicationKey?: string
  triggeredAt: string
  acknowledgedAt?: string
  acknowledgedBy?: number
  acknowledgedByName?: string
  resolvedAt?: string
  resolvedBy?: number
  resolvedByName?: string
  metadata?: Record<string, unknown>
  nextEscalationAt?: string
  viewedByCurrentUser?: boolean
}

export interface IncidentTimeline {
  id: number
  incidentId: number
  eventType:
    | 'TRIGGERED'
    | 'ESCALATED'
    | 'ACKNOWLEDGED'
    | 'RESOLVED'
    | 'REASSIGNED'
    | 'NOTE_ADDED'
    | 'STEP_TIMEOUT'
    | 'NOTIFICATION_SENT'
    | 'VIEWED'
  actorUserId?: number
  actorUserName?: string
  details?: Record<string, unknown>
  createdAt: string
}

export interface IncidentDetail extends Incident {
  timeline?: IncidentTimeline[]
}

export interface OnCallIncident {
  id: number
  organizationId: number
  title: string
  description?: string
  severity: string
  status: string
  declaredBy: number
  declaredByName?: string
  declaredAt: string
  resolvedBy?: number
  resolvedByName?: string
  resolvedAt?: string
  alertCount: number
  alerts?: Array<{ id: number; title: string; status: string; priority?: string }>
  createdAt: string
  updatedAt: string
}

export interface OnCallIncidentDetail extends OnCallIncident {
  timeline?: IncidentTimeline[]
}

export interface DeviceToken {
  id: number
  userId: number
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
  participants: { userId: number; position: number }[]
}

export interface UpdateOnCallScheduleRequest {
  name?: string
  rotationType?: OnCallRotationType
  handoffTime?: string
  timezone?: string
  participants?: { userId: number; position: number }[]
}

export interface CreateOverrideRequest {
  userId: number
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
      targetId: number
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
      targetId: number
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

export interface IncidentListFilters {
  status?: IncidentStatus | IncidentStatus[]
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
