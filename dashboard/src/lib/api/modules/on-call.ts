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
  Incident,
  IncidentDetail,
  IncidentTimeline,
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
  IncidentListFilters,
} from '../types'

function appendIncidentStatusFilters(
  params: URLSearchParams,
  status?: IncidentListFilters['status']
) {
  if (!status) return

  const statuses = Array.isArray(status) ? status : [status]
  statuses.forEach((value) => params.append('status', value))
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

    getOnCallSchedule: (id: number) =>
      core.request<OnCallSchedule>(`${base}/on-call/schedules/${id}`),

    createOnCallSchedule: (request: CreateOnCallScheduleRequest) =>
      core.request<OnCallSchedule>(`${base}/on-call/schedules`, {
        method: 'POST',
        body: JSON.stringify(request),
      }),

    updateOnCallSchedule: (
      id: number,
      request: UpdateOnCallScheduleRequest
    ) =>
      core.request<OnCallSchedule>(`${base}/on-call/schedules/${id}`, {
        method: 'PUT',
        body: JSON.stringify(request),
      }),

    deleteOnCallSchedule: (id: number) =>
      core.request<void>(`${base}/on-call/schedules/${id}`, {
        method: 'DELETE',
      }),

    getCurrentOnCall: (scheduleId: number) =>
      core.request<{ userId: number; userName: string }>(
        `${base}/on-call/schedules/${scheduleId}/current`
      ),

    createOverride: (
      scheduleId: number,
      request: CreateOverrideRequest
    ) =>
      core.request<OnCallOverride>(
        `${base}/on-call/schedules/${scheduleId}/overrides`,
        {
          method: 'POST',
          body: JSON.stringify(request),
        }
      ),

    deleteOverride: (overrideId: number) =>
      core.request<void>(`${base}/on-call/overrides/${overrideId}`, {
        method: 'DELETE',
      }),

    getEscalationPolicies: () =>
      core.request<EscalationPolicy[]>(`${base}/escalation-policies`),

    getEscalationPolicy: (id: number) =>
      core.request<EscalationPolicy>(`${base}/escalation-policies/${id}`),

    createEscalationPolicy: (
      request: CreateEscalationPolicyRequest
    ) =>
      core.request<EscalationPolicy>(`${base}/escalation-policies`, {
        method: 'POST',
        body: JSON.stringify(request),
      }),

    updateEscalationPolicy: (
      id: number,
      request: UpdateEscalationPolicyRequest
    ) =>
      core.request<EscalationPolicy>(`${base}/escalation-policies/${id}`, {
        method: 'PUT',
        body: JSON.stringify(request),
      }),

    deleteEscalationPolicy: (id: number) =>
      core.request<void>(`${base}/escalation-policies/${id}`, {
        method: 'DELETE',
      }),

    getIncidents: (filters?: IncidentListFilters) => {
      const params = new URLSearchParams()
      appendIncidentStatusFilters(params, filters?.status)
      if (filters?.priority) params.append('priority', filters.priority)
      if (filters?.fromDate) params.append('fromDate', filters.fromDate)
      if (filters?.toDate) params.append('toDate', filters.toDate)
      const query = params.toString()
      return core.request<Incident[]>(
        urlWithQuery(`${base}/on-call/alerts`, query)
      )
    },

    getIncident: (id: number) =>
      core.request<IncidentDetail>(`${base}/on-call/alerts/${id}`),

    getIncidentTimeline: (id: number) =>
      core.request<IncidentTimeline[]>(`${base}/on-call/alerts/${id}/timeline`),

    acknowledgeIncident: (id: number) =>
      core.request<Incident>(`${base}/on-call/alerts/${id}/acknowledge`, {
        method: 'POST',
      }),

    resolveIncident: (id: number) =>
      core.request<Incident>(`${base}/on-call/alerts/${id}/resolve`, {
        method: 'POST',
      }),

    reassignIncident: (id: number, toUserId: number) =>
      core.request<Incident>(`${base}/on-call/alerts/${id}/reassign`, {
        method: 'POST',
        body: JSON.stringify({ toUserId }),
      }),

    addIncidentNote: (id: number, note: string) =>
      core.request<IncidentTimeline>(`${base}/on-call/alerts/${id}/notes`, {
        method: 'POST',
        body: JSON.stringify({ note }),
      }),

    viewIncident: (id: number) =>
      core.request<void>(`${base}/on-call/alerts/${id}/view`, {
        method: 'POST',
      }),

    markUnavailable: (id: number) =>
      core.request<void>(`${base}/on-call/alerts/${id}/unavailable`, {
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

    declareIncident: (
      alertId: number,
      data: { title: string; description: string; severity: string }
    ) =>
      core.request<{ id: number }>(`${base}/on-call/alerts/${alertId}/declare-incident`, {
        method: 'POST',
        body: JSON.stringify(data),
      }),

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

    getOnCallIncident: (id: number) =>
      core.request<OnCallIncidentDetail>(`${base}/on-call/incidents/${id}`),

    resolveOnCallIncident: (id: number, note?: string) =>
      core.request<OnCallIncident>(`${base}/on-call/incidents/${id}/resolve`, {
        method: 'POST',
        body: JSON.stringify({ note }),
      }),

    getOnCallIncidentTimeline: (id: number) =>
      core.request<IncidentTimeline[]>(
        `${base}/on-call/incidents/${id}/timeline`
      ),

    addOnCallIncidentNote: (id: number, note: string) =>
      core.request<{ message: string }>(
        `${base}/on-call/incidents/${id}/notes`,
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
