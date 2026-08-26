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
  OnCallScheduleLayer,
  OnCallResponderResolution,
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
  CreateScheduleLayerRequest,
  UpdateScheduleLayerRequest,
  CreateEscalationPolicyRequest,
  UpdateEscalationPolicyRequest,
  UpdatePrioritiesRequest,
  UpdateBusinessHoursRequest,
  RegisterDeviceRequest,
  OnCallAlertListFilters,
  DeclareIncidentInput,
  IncidentSourceLink,
  LinkIncidentSourceInput,
  IncidentRoleAssignment,
  IncidentParticipant,
  SetIncidentParticipantInput,
  HandoverIncidentRoleInput,
  IncidentTimelineEntry,
  IncidentTimelineFilterInput,
  IncidentTimelineRevision,
  IncidentTimelineExport,
  EditIncidentTimelineInput,
  IncidentTypeDefinition,
  CreateIncidentTypeInput,
  IncidentCustomFieldDefinition,
  CreateIncidentCustomFieldInput,
  IncidentFormDefinition,
  CreateIncidentFormInput,
  IncidentFormStage,
  IncidentRoleDefinition,
  CreateIncidentRoleInput,
  NativeIncidentCapabilities,
  AcceptIncidentInput,
  DeclineIncidentInput,
  MergeIncidentInput,
  IncidentUpdateInput,
  IncidentUpdateRequestInput,
  IncidentUpdateReminderInput,
  OnCallIncidentAction,
  IncidentActionEvent,
  IncidentActionMetrics,
  CreateIncidentActionInput,
  IncidentActionMutationInput,
  ReassignIncidentActionInput,
} from '../types'

/** Normalize the entitlement and quota payload into a strict, fail-closed shape. */
function normalizeNativeIncidentCapabilities(raw: unknown): NativeIncidentCapabilities {
  const value = (raw ?? {}) as Record<string, unknown>
  return {
    enabled: value.enabled === true && value.entitlementEnabled === true,
    entitlementEnabled: value.entitlementEnabled === true,
    plan: typeof value.plan === 'string' ? value.plan : 'UNKNOWN',
    entitlementReason:
      typeof value.entitlementReason === 'string' ? value.entitlementReason : null,
    quotas: normalizeNativeIncidentQuotas(value.quotas),
    externalProviderPassthroughAffected: value.externalProviderPassthroughAffected === true,
  }
}

function normalizeNativeIncidentQuotas(
  raw: unknown
): NativeIncidentCapabilities['quotas'] {
  if (typeof raw !== 'object' || raw === null || Array.isArray(raw)) return {}
  return Object.fromEntries(
    Object.entries(raw).flatMap(([key, candidate]) => {
      if (typeof candidate !== 'object' || candidate === null || Array.isArray(candidate)) return []
      const quota = candidate as Record<string, unknown>
      if (
        typeof quota.limit !== 'number' ||
        typeof quota.used !== 'number' ||
        typeof quota.remaining !== 'number'
      ) {
        return []
      }
      return [[key, {
        limit: quota.limit,
        used: quota.used,
        remaining: quota.remaining,
        exhausted: quota.exhausted === true,
      }]]
    })
  )
}

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

    getOnCallResponders: (scheduleId: string, all = true) =>
      core.request<OnCallResponderResolution[]>(
        `${base}/on-call/schedules/${encodeURIComponent(scheduleId)}/responders?all=${String(all)}`
      ),

    getScheduleLayers: (scheduleId: string) =>
      core.request<OnCallScheduleLayer[]>(
        `${base}/on-call/schedules/${encodeURIComponent(scheduleId)}/layers`
      ),

    createScheduleLayer: (
      scheduleId: string,
      request: CreateScheduleLayerRequest
    ) =>
      core.request<OnCallScheduleLayer>(
        `${base}/on-call/schedules/${encodeURIComponent(scheduleId)}/layers`,
        {
          method: 'POST',
          body: JSON.stringify(request),
        }
      ),

    updateScheduleLayer: (
      scheduleId: string,
      layerId: string,
      request: UpdateScheduleLayerRequest
    ) =>
      core.request<OnCallScheduleLayer>(
        `${base}/on-call/schedules/${encodeURIComponent(scheduleId)}/layers/${encodeURIComponent(layerId)}`,
        {
          method: 'PUT',
          body: JSON.stringify(request),
        }
      ),

    deleteScheduleLayer: (scheduleId: string, layerId: string) =>
      core.request<void>(
        `${base}/on-call/schedules/${encodeURIComponent(scheduleId)}/layers/${encodeURIComponent(layerId)}`,
        {method: 'DELETE'}
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

    // ──── Native incident capabilities ────

    // Server-authoritative entitlement check for native incident dashboard surfaces
    // and actions. The backend independently enforces the same entitlement.
    getNativeIncidentCapabilities: (): Promise<NativeIncidentCapabilities> =>
      core
        .request<unknown>(`${base}/on-call/incident-response/capabilities`)
        .then(normalizeNativeIncidentCapabilities),

    declareIncidentFromAlert: (alertId: string, data: DeclareIncidentInput) =>
      core.request<OnCallIncident>(
        `${base}/on-call/alerts/${encodeURIComponent(alertId)}/declare-incident`,
        {
          method: 'POST',
          body: JSON.stringify(data),
        }
      ),

    declareOnCallIncident: (data: DeclareIncidentInput) =>
      core.request<OnCallIncident>(`${base}/on-call/incidents`, {
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

    getOnCallIncident: (id: string) =>
      core.request<OnCallIncidentDetail>(`${base}/on-call/incidents/${encodeURIComponent(id)}`),

    resolveOnCallIncident: (id: string, note?: string) =>
      core.request<OnCallIncident>(`${base}/on-call/incidents/${encodeURIComponent(id)}/resolve`, {
        method: 'POST',
        body: JSON.stringify({ note }),
      }),

    // Triage lifecycle. Accept classifies a triage incident, Decline dismisses
    // it, and Merge folds it into another incident. All carry an optional
    // expectedVersion for optimistic concurrency; the backend replies 409 on a
    // stale version.
    acceptOnCallIncident: (id: string, input: AcceptIncidentInput = {}) =>
      core.request<OnCallIncident>(`${base}/on-call/incidents/${encodeURIComponent(id)}/accept`, {
        method: 'POST',
        body: JSON.stringify(input),
      }),

    declineOnCallIncident: (id: string, input: DeclineIncidentInput = {}) =>
      core.request<OnCallIncident>(`${base}/on-call/incidents/${encodeURIComponent(id)}/decline`, {
        method: 'POST',
        body: JSON.stringify(input),
      }),

    mergeOnCallIncident: (id: string, input: MergeIncidentInput) =>
      core.request<OnCallIncident>(`${base}/on-call/incidents/${encodeURIComponent(id)}/merge`, {
        method: 'POST',
        body: JSON.stringify(input),
      }),

    publishOnCallIncidentUpdate: (id: string, input: IncidentUpdateInput) =>
      core.request<OnCallIncident>(`${base}/on-call/incidents/${encodeURIComponent(id)}/updates`, {
        method: 'POST',
        body: JSON.stringify(input),
      }),

    requestOnCallIncidentUpdate: (id: string, input: IncidentUpdateRequestInput = {}) =>
      core.request<OnCallIncident>(
        `${base}/on-call/incidents/${encodeURIComponent(id)}/update-requests`,
        {
          method: 'POST',
          body: JSON.stringify(input),
        }
      ),

    pauseOnCallIncidentUpdateReminders: (
      id: string,
      input: IncidentUpdateReminderInput = {}
    ) =>
      core.request<OnCallIncident>(
        `${base}/on-call/incidents/${encodeURIComponent(id)}/update-reminders/pause`,
        {
          method: 'POST',
          body: JSON.stringify(input),
        }
      ),

    resumeOnCallIncidentUpdateReminders: (
      id: string,
      input: IncidentUpdateReminderInput = {}
    ) =>
      core.request<OnCallIncident>(
        `${base}/on-call/incidents/${encodeURIComponent(id)}/update-reminders/resume`,
        {
          method: 'POST',
          body: JSON.stringify(input),
        }
      ),

    getIncidentActions: (id: string) =>
      core.request<OnCallIncidentAction[]>(
        `${base}/on-call/incidents/${encodeURIComponent(id)}/actions`
      ),

    getIncidentAction: (id: string, actionId: string) =>
      core.request<OnCallIncidentAction>(
        `${base}/on-call/incidents/${encodeURIComponent(id)}/actions/${encodeURIComponent(actionId)}`
      ),

    getIncidentActionEvents: (id: string, actionId: string) =>
      core.request<IncidentActionEvent[]>(
        `${base}/on-call/incidents/${encodeURIComponent(id)}/actions/${encodeURIComponent(actionId)}/events`
      ),

    getIncidentActionMetrics: (id: string) =>
      core.request<IncidentActionMetrics>(
        `${base}/on-call/incidents/${encodeURIComponent(id)}/actions/metrics`
      ),

    createIncidentAction: (id: string, input: CreateIncidentActionInput) =>
      core.request<OnCallIncidentAction>(
        `${base}/on-call/incidents/${encodeURIComponent(id)}/actions`,
        {method: 'POST', body: JSON.stringify(input)}
      ),

    claimIncidentAction: (id: string, actionId: string, expectedVersion?: number) =>
      core.request<OnCallIncidentAction>(
        `${base}/on-call/incidents/${encodeURIComponent(id)}/actions/${encodeURIComponent(actionId)}/claim`,
        {method: 'POST', body: JSON.stringify({expectedVersion})}
      ),

    reassignIncidentAction: (
      id: string,
      actionId: string,
      input: ReassignIncidentActionInput
    ) =>
      core.request<OnCallIncidentAction>(
        `${base}/on-call/incidents/${encodeURIComponent(id)}/actions/${encodeURIComponent(actionId)}/reassign`,
        {method: 'POST', body: JSON.stringify(input)}
      ),

    completeIncidentAction: (
      id: string,
      actionId: string,
      input: IncidentActionMutationInput = {}
    ) =>
      core.request<OnCallIncidentAction>(
        `${base}/on-call/incidents/${encodeURIComponent(id)}/actions/${encodeURIComponent(actionId)}/complete`,
        {method: 'POST', body: JSON.stringify(input)}
      ),

    cancelIncidentAction: (
      id: string,
      actionId: string,
      input: IncidentActionMutationInput = {}
    ) =>
      core.request<OnCallIncidentAction>(
        `${base}/on-call/incidents/${encodeURIComponent(id)}/actions/${encodeURIComponent(actionId)}/cancel`,
        {method: 'POST', body: JSON.stringify(input)}
      ),

    convertIncidentActionToFollowUp: (
      id: string,
      actionId: string,
      input: IncidentActionMutationInput = {}
    ) =>
      core.request<OnCallIncidentAction>(
        `${base}/on-call/incidents/${encodeURIComponent(id)}/actions/${encodeURIComponent(actionId)}/convert-to-follow-up`,
        {method: 'POST', body: JSON.stringify(input)}
      ),

    // Canonical, evidence-preserving incident timeline. Supersedes the legacy
    // merged timeline shape at the same path; entries carry provenance,
    // visibility, original/observed time, annotations, and soft-delete state.
    getOnCallIncidentTimeline: (
      id: string,
      filters: IncidentTimelineFilterInput = {}
    ) => {
      const params = new URLSearchParams()
      filters.eventType?.forEach((value) => params.append('eventType', value))
      filters.provenance?.forEach((value) => params.append('provenance', value))
      filters.visibility?.forEach((value) => params.append('visibility', value))
      if (filters.includeDeleted) params.append('includeDeleted', 'true')
      return core.request<IncidentTimelineEntry[]>(
        urlWithQuery(
          `${base}/on-call/incidents/${encodeURIComponent(id)}/timeline`,
          params.toString()
        )
      )
    },

    exportOnCallIncidentTimeline: (id: string) =>
      core.request<IncidentTimelineExport>(
        `${base}/on-call/incidents/${encodeURIComponent(id)}/timeline/export`
      ),

    getIncidentTimelineRevisions: (id: string, eventId: string) =>
      core.request<IncidentTimelineRevision[]>(
        `${base}/on-call/incidents/${encodeURIComponent(id)}/timeline/${encodeURIComponent(eventId)}/revisions`
      ),

    editIncidentTimelineEvent: (
      id: string,
      eventId: string,
      data: EditIncidentTimelineInput
    ) =>
      core.request<IncidentTimelineEntry>(
        `${base}/on-call/incidents/${encodeURIComponent(id)}/timeline/${encodeURIComponent(eventId)}`,
        { method: 'PATCH', body: JSON.stringify(data) }
      ),

    annotateIncidentTimelineEvent: (
      id: string,
      eventId: string,
      data: { annotation?: string; reason?: string }
    ) =>
      core.request<IncidentTimelineEntry>(
        `${base}/on-call/incidents/${encodeURIComponent(id)}/timeline/${encodeURIComponent(eventId)}/annotation`,
        { method: 'POST', body: JSON.stringify(data) }
      ),

    reorderIncidentTimeline: (id: string, eventIds: string[], reason?: string) =>
      core.request<IncidentTimelineEntry[]>(
        `${base}/on-call/incidents/${encodeURIComponent(id)}/timeline/reorder`,
        { method: 'POST', body: JSON.stringify({ eventIds, reason }) }
      ),

    deleteIncidentTimelineEvent: (id: string, eventId: string, reason?: string) =>
      core.request<MessageResponse>(
        `${base}/on-call/incidents/${encodeURIComponent(id)}/timeline/${encodeURIComponent(eventId)}`,
        { method: 'DELETE', body: JSON.stringify({ reason }) }
      ),

    restoreIncidentTimelineEvent: (id: string, eventId: string, reason?: string) =>
      core.request<MessageResponse>(
        `${base}/on-call/incidents/${encodeURIComponent(id)}/timeline/${encodeURIComponent(eventId)}/restore`,
        { method: 'POST', body: JSON.stringify({ reason }) }
      ),

    // ──── Source references ────

    getIncidentSources: (id: string) =>
      core.request<IncidentSourceLink[]>(
        `${base}/on-call/incidents/${encodeURIComponent(id)}/sources`
      ),

    linkIncidentSource: (id: string, data: LinkIncidentSourceInput) =>
      core.request<IncidentSourceLink[]>(
        `${base}/on-call/incidents/${encodeURIComponent(id)}/sources`,
        { method: 'POST', body: JSON.stringify(data) }
      ),

    unlinkIncidentSource: (id: string, sourceId: string) =>
      core.request<MessageResponse>(
        `${base}/on-call/incidents/${encodeURIComponent(id)}/sources/${encodeURIComponent(sourceId)}`,
        { method: 'DELETE' }
      ),

    // ──── Responders: roles ────

    getIncidentRoleAssignments: (id: string) =>
      core.request<IncidentRoleAssignment[]>(
        `${base}/on-call/incidents/${encodeURIComponent(id)}/roles`
      ),

    assignIncidentRole: (
      id: string,
      roleId: string,
      userId: string,
      expectedVersion?: number
    ) =>
      core.request<IncidentRoleAssignment[]>(
        `${base}/on-call/incidents/${encodeURIComponent(id)}/roles/${encodeURIComponent(roleId)}/assign`,
        { method: 'POST', body: JSON.stringify({ userId, expectedVersion }) }
      ),

    claimIncidentRole: (id: string, roleId: string, expectedVersion?: number) =>
      core.request<IncidentRoleAssignment[]>(
        `${base}/on-call/incidents/${encodeURIComponent(id)}/roles/${encodeURIComponent(roleId)}/claim`,
        { method: 'POST', body: JSON.stringify({ expectedVersion }) }
      ),

    unassignIncidentRole: (id: string, roleId: string) =>
      core.request<MessageResponse>(
        `${base}/on-call/incidents/${encodeURIComponent(id)}/roles/${encodeURIComponent(roleId)}`,
        { method: 'DELETE' }
      ),

    handoverIncidentRole: (
      id: string,
      roleId: string,
      data: HandoverIncidentRoleInput
    ) =>
      core.request<IncidentRoleAssignment[]>(
        `${base}/on-call/incidents/${encodeURIComponent(id)}/roles/${encodeURIComponent(roleId)}/handover`,
        { method: 'POST', body: JSON.stringify(data) }
      ),

    // ──── Responders: participants & observers ────

    getIncidentParticipants: (id: string) =>
      core.request<IncidentParticipant[]>(
        `${base}/on-call/incidents/${encodeURIComponent(id)}/participants`
      ),

    joinIncident: (id: string, data: SetIncidentParticipantInput = {}) =>
      core.request<IncidentParticipant[]>(
        `${base}/on-call/incidents/${encodeURIComponent(id)}/participants`,
        { method: 'POST', body: JSON.stringify(data) }
      ),

    observeIncident: (id: string, data: SetIncidentParticipantInput = {}) =>
      core.request<IncidentParticipant[]>(
        `${base}/on-call/incidents/${encodeURIComponent(id)}/observers`,
        { method: 'POST', body: JSON.stringify(data) }
      ),

    leaveIncident: (id: string, userId: string) =>
      core.request<MessageResponse>(
        `${base}/on-call/incidents/${encodeURIComponent(id)}/participants/${encodeURIComponent(userId)}`,
        { method: 'DELETE' }
      ),

    addOnCallIncidentNote: (id: string, note: string) =>
      core.request<{ message: string }>(
        `${base}/on-call/incidents/${encodeURIComponent(id)}/notes`,
        {
          method: 'POST',
          body: JSON.stringify({ note }),
        }
      ),

    // ──── Incident configuration (versioned definitions) ────

    getIncidentTypes: () =>
      core.request<IncidentTypeDefinition[]>(
        `${base}/on-call/incident-configuration/types`
      ),

    createIncidentType: (data: CreateIncidentTypeInput) =>
      core.request<IncidentTypeDefinition>(
        `${base}/on-call/incident-configuration/types`,
        { method: 'POST', body: JSON.stringify(data) }
      ),

    getIncidentCustomFields: () =>
      core.request<IncidentCustomFieldDefinition[]>(
        `${base}/on-call/incident-configuration/fields`
      ),

    createIncidentCustomField: (data: CreateIncidentCustomFieldInput) =>
      core.request<IncidentCustomFieldDefinition>(
        `${base}/on-call/incident-configuration/fields`,
        { method: 'POST', body: JSON.stringify(data) }
      ),

    getIncidentForms: (stage?: IncidentFormStage) => {
      const params = new URLSearchParams()
      if (stage) params.append('stage', stage)
      return core.request<IncidentFormDefinition[]>(
        urlWithQuery(
          `${base}/on-call/incident-configuration/forms`,
          params.toString()
        )
      )
    },

    createIncidentForm: (data: CreateIncidentFormInput) =>
      core.request<IncidentFormDefinition>(
        `${base}/on-call/incident-configuration/forms`,
        { method: 'POST', body: JSON.stringify(data) }
      ),

    getIncidentRoles: () =>
      core.request<IncidentRoleDefinition[]>(
        `${base}/on-call/incident-configuration/roles`
      ),

    createIncidentRole: (data: CreateIncidentRoleInput) =>
      core.request<IncidentRoleDefinition>(
        `${base}/on-call/incident-configuration/roles`,
        { method: 'POST', body: JSON.stringify(data) }
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
