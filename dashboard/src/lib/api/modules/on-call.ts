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

import type { ApiClientCore } from '../client'
import { urlWithQuery } from '../utils'
import type {
  Priority,
  BusinessHours,
  OnCallSchedule,
  OnCallOverride,
  EscalationPolicy,
  OnCallAlert,
  OnCallAlertDetail,
  OnCallTimelineEvent,
  OnCallIncident,
  OnCallIncidentDetail,
  DeviceToken,
  OnCallContactSettings,
  CreateOnCallScheduleRequest,
  UpdateOnCallScheduleRequest,
  CreateOverrideRequest,
  CreateEscalationPolicyRequest,
  UpdateEscalationPolicyRequest,
  UpdatePrioritiesRequest,
  UpdateBusinessHoursRequest,
  RegisterDeviceRequest,
  OnCallAlertListFilters,
} from '../types'

function appendAlertStatusFilters(
  params: URLSearchParams,
  status?: OnCallAlertListFilters['status']
) {
  if (!status) return

  const statuses = Array.isArray(status) ? status : [status]
  statuses.forEach((value) => params.append('status', value))
}

type MessageResponse = {
  message: string
}

export function onCallMethods(core: ApiClientCore) {
  const base = core.API_BASE

  return {
    getPriorities: () =>
      core.request<Priority[]>(`${base}/priorities`),

    updatePriorities: (request: UpdatePrioritiesRequest) =>
      core.request<Priority[]>(`${base}/priorities`, {
        method: 'PUT',
        body: JSON.stringify(request),
      }),

    getBusinessHours: () =>
      core.request<BusinessHours>(`${base}/business-hours`),

    updateBusinessHours: (request: UpdateBusinessHoursRequest) =>
      core.request<BusinessHours>(`${base}/business-hours`, {
        method: 'PUT',
        body: JSON.stringify(request),
      }),

    getOnCallSchedules: () =>
      core.request<OnCallSchedule[]>(`${base}/on-call/schedules`),

    getOnCallSchedule: (id: string) =>
      core.request<OnCallSchedule>(`${base}/on-call/schedules/${encodeURIComponent(id)}`),

    createOnCallSchedule: (request: CreateOnCallScheduleRequest) =>
      core.request<OnCallSchedule>(`${base}/on-call/schedules`, {
        method: 'POST',
        body: JSON.stringify(request),
      }),

    updateOnCallSchedule: (
      id: string,
      request: UpdateOnCallScheduleRequest
    ) =>
      core.request<OnCallSchedule>(`${base}/on-call/schedules/${encodeURIComponent(id)}`, {
        method: 'PUT',
        body: JSON.stringify(request),
      }),

    deleteOnCallSchedule: (id: string) =>
      core.request<void>(`${base}/on-call/schedules/${encodeURIComponent(id)}`, {
        method: 'DELETE',
      }),

    getCurrentOnCall: (scheduleId: string) =>
      core.request<{ userId: string; userName: string }>(
        `${base}/on-call/schedules/${encodeURIComponent(scheduleId)}/current`
      ),

    createOverride: (
      scheduleId: string,
      request: CreateOverrideRequest
    ) =>
      core.request<OnCallOverride>(
        `${base}/on-call/schedules/${encodeURIComponent(scheduleId)}/overrides`,
        {
          method: 'POST',
          body: JSON.stringify(request),
        }
      ),

    deleteOverride: (overrideId: string) =>
      core.request<void>(`${base}/on-call/overrides/${encodeURIComponent(overrideId)}`, {
        method: 'DELETE',
      }),

    getEscalationPolicies: () =>
      core.request<EscalationPolicy[]>(`${base}/escalation-policies`),

    getEscalationPolicy: (id: string) =>
      core.request<EscalationPolicy>(`${base}/escalation-policies/${encodeURIComponent(id)}`),

    createEscalationPolicy: (
      request: CreateEscalationPolicyRequest
    ) =>
      core.request<EscalationPolicy>(`${base}/escalation-policies`, {
        method: 'POST',
        body: JSON.stringify(request),
      }),

    updateEscalationPolicy: (
      id: string,
      request: UpdateEscalationPolicyRequest
    ) =>
      core.request<EscalationPolicy>(`${base}/escalation-policies/${encodeURIComponent(id)}`, {
        method: 'PUT',
        body: JSON.stringify(request),
      }),

    deleteEscalationPolicy: (id: string) =>
      core.request<void>(`${base}/escalation-policies/${encodeURIComponent(id)}`, {
        method: 'DELETE',
      }),

    getAlerts: (filters?: OnCallAlertListFilters) => {
      const params = new URLSearchParams()
      appendAlertStatusFilters(params, filters?.status)
      if (filters?.priority) params.append('priority', filters.priority)
      if (filters?.fromDate) params.append('fromDate', filters.fromDate)
      if (filters?.toDate) params.append('toDate', filters.toDate)
      const query = params.toString()
      return core.request<OnCallAlert[]>(
        urlWithQuery(`${base}/on-call/alerts`, query)
      )
    },

    getAlert: (id: string) =>
      core.request<OnCallAlertDetail>(`${base}/on-call/alerts/${encodeURIComponent(id)}`),

    getAlertTimeline: (id: string) =>
      core.request<OnCallTimelineEvent[]>(
        `${base}/on-call/alerts/${encodeURIComponent(id)}/timeline`
      ),

    acknowledgeAlert: (id: string) =>
      core.request<MessageResponse>(`${base}/on-call/alerts/${encodeURIComponent(id)}/acknowledge`, {
        method: 'POST',
      }),

    resolveAlert: (id: string) =>
      core.request<MessageResponse>(`${base}/on-call/alerts/${encodeURIComponent(id)}/resolve`, {
        method: 'POST',
      }),

    reassignAlert: (id: string, toUserId: string) =>
      core.request<OnCallAlert>(`${base}/on-call/alerts/${encodeURIComponent(id)}/reassign`, {
        method: 'POST',
        body: JSON.stringify({ toUserId }),
      }),

    addAlertNote: (id: string, note: string) =>
      core.request<OnCallTimelineEvent>(`${base}/on-call/alerts/${encodeURIComponent(id)}/notes`, {
        method: 'POST',
        body: JSON.stringify({ note }),
      }),

    viewAlert: (id: string) =>
      core.request<void>(`${base}/on-call/alerts/${encodeURIComponent(id)}/view`, {
        method: 'POST',
      }),

    markAlertUnavailable: (id: string) =>
      core.request<void>(`${base}/on-call/alerts/${encodeURIComponent(id)}/unavailable`, {
        method: 'POST',
      }),

    registerDevice: (request: RegisterDeviceRequest) =>
      core.request<DeviceToken>(`${base}/devices`, {
        method: 'POST',
        body: JSON.stringify(request),
      }),

    unregisterDevice: (token: string) =>
      core.request<void>(`${base}/devices/${encodeURIComponent(token)}`, {
        method: 'DELETE',
      }),

    declareIncidentFromAlert: (
      alertId: string,
      data: { title: string; description: string; severity: string }
    ) =>
      core.request<{ id: string }>(
        `${base}/on-call/alerts/${encodeURIComponent(alertId)}/declare-incident`,
        {
          method: 'POST',
          body: JSON.stringify(data),
        }
      ),

    getOnCallIncidents: (
      filters: { status?: string; severity?: string } = {}
    ) => {
      const params = new URLSearchParams()
      if (filters.status) params.append('status', filters.status)
      if (filters.severity) params.append('severity', filters.severity)
      return core.request<OnCallIncident[]>(
        urlWithQuery(`${base}/on-call/incidents`, params.toString())
      )
    },

    getOnCallIncident: (id: string) =>
      core.request<OnCallIncidentDetail>(`${base}/on-call/incidents/${encodeURIComponent(id)}`),

    resolveOnCallIncident: (id: string, note?: string) =>
      core.request<OnCallIncident>(`${base}/on-call/incidents/${encodeURIComponent(id)}/resolve`, {
        method: 'POST',
        body: JSON.stringify({ note }),
      }),

    getOnCallIncidentTimeline: (id: string) =>
      core.request<OnCallTimelineEvent[]>(
        `${base}/on-call/incidents/${encodeURIComponent(id)}/timeline`
      ),

    addOnCallIncidentNote: (id: string, note: string) =>
      core.request<{ message: string }>(
        `${base}/on-call/incidents/${encodeURIComponent(id)}/notes`,
        {
          method: 'POST',
          body: JSON.stringify({ note }),
        }
      ),

    updatePhoneNumber: (phoneNumber: string) =>
      core.request<{ message: string }>(`${base}/user/phone-number`, {
        method: 'PUT',
        body: JSON.stringify({ phoneNumber }),
      }),

    deletePhoneNumber: () =>
      core.request<{ message: string }>(`${base}/user/phone-number`, {
        method: 'DELETE',
      }),

    getCallerNumber: () =>
      core.request<{ phoneNumber: string | null }>(`${base}/on-call/caller-number`),

    getOnCallContact: () =>
      core.request<OnCallContactSettings>(`${base}/user/on-call-contact`),

    updateOnCallContact: (req: {
      phoneNumber: string
      consentAccepted: boolean
      consentVersion: string
    }) =>
      core.request<{ message: string }>(`${base}/user/on-call-contact`, {
        method: 'PUT',
        body: JSON.stringify(req),
      }),

    deleteOnCallContact: () =>
      core.request<{ message: string }>(`${base}/user/on-call-contact`, {
        method: 'DELETE',
      }),
  }
}
