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
  DdEventListResponse,
  DdServiceCheckListResponse,
  DdHostListResponse,
  DdHostResponse,
  DdContainerListResponse,
  DdProcessListResponse,
  DdConnectionListResponse,
  HostAlert,
  HostAlertConfig,
  AlertEpisode,
  AlertLifecycleListParams,
  SilencePeriod,
  CreateSilencePeriodRequest,
  SystemMetricsHistory,
  MonitorHostResponse,
  RawContainerStats,
  ContainerMetricsHistory,
  CreateDdApiKeyResponse,
  CloudSourceCreateRequest,
  CloudSourceProvider,
  CloudSourceResponse,
  CloudSourceSetupPreview,
  InfrastructureMapSavedViewsResponse,
  InfrastructureResourceKind,
  InfrastructureGroupBy,
  InfrastructureFillBy,
  InfrastructureSizeBy,
  SaveInfrastructureMapViewRequest,
  SavedInfrastructureMapView,
  ResourceOwner,
  ResourceOwnershipClaim,
} from '../types'

interface RawInfrastructureMapSavedView {
  id: string
  name: string
  resourceKind?: InfrastructureResourceKind
  resource_kind?: InfrastructureResourceKind
  groupBy?: InfrastructureGroupBy
  group_by?: InfrastructureGroupBy
  fillBy?: InfrastructureFillBy
  fill_by?: InfrastructureFillBy
  sizeBy?: InfrastructureSizeBy
  size_by?: InfrastructureSizeBy
  searchQuery?: string
  search_query?: string
  schemaVersion?: number
  schema_version?: number
  createdAt?: string
  created_at?: string
  updatedAt?: string
  updated_at?: string
}

function mapHostAlert(row: Record<string, unknown>): HostAlert {
  const rawScope = ((row.scope as string | undefined) ?? '').toLowerCase()
  return {
    id: row.id as string,
    hostId: (row.hostId ?? row.host_id) as string | undefined,
    scope: rawScope === 'global' ? 'global' : 'host',
    metric: row.metric as string,
    condition: row.condition as string,
    threshold: row.threshold as number,
    durationSeconds: (row.durationSeconds ?? row.duration_seconds ?? 0) as number,
    enabled: row.enabled === true,
    alertPriority: (row.alertPriority ?? row.alert_priority ?? row.incidentSeverity ?? row.incident_severity ?? null) as string | null,
    lastTriggeredAt: (row.lastTriggeredAt ?? row.last_triggered_at) as number | undefined,
    createdAt: (row.createdAt ?? row.created_at) as number,
  }
}

/**
 * Host alert writes go out under the snake_case keys the API declares. The
 * response mapper above is tolerant of either casing, but the request side is
 * not: a camelCase `durationSeconds` is dropped on the floor and the rule
 * silently saves with no duration at all.
 */
function hostAlertWriteBody(
  alert: Partial<HostAlert> & {durationSeconds?: number}
): Record<string, unknown> {
  const body: Record<string, unknown> = {}
  if (alert.metric !== undefined) body.metric = alert.metric
  if (alert.condition !== undefined) body.condition = alert.condition
  if (alert.threshold !== undefined) body.threshold = alert.threshold
  if (alert.durationSeconds !== undefined) body.duration_seconds = alert.durationSeconds
  if (alert.enabled !== undefined) body.enabled = alert.enabled
  // Explicit null clears the override, so only `undefined` is omitted.
  if (alert.alertPriority !== undefined) body.alert_priority = alert.alertPriority
  return body
}

function mapSilencePeriod(row: Record<string, unknown>): SilencePeriod {
  return {
    id: row.id as string,
    organizationId: (row.organization_id ?? row.organizationId) as string,
    reason: (row.reason ?? null) as string | null,
    startsAt: (row.starts_at ?? row.startsAt) as number,
    endsAt: (row.ends_at ?? row.endsAt) as number,
    createdBy: (row.created_by ?? row.createdBy) as string,
    createdAt: (row.created_at ?? row.createdAt) as number,
  }
}

function mapInfrastructureMapSavedView(
  row: RawInfrastructureMapSavedView
): SavedInfrastructureMapView {
  return {
    id: String(row.id),
    name: row.name,
    resourceKind: row.resourceKind ?? row.resource_kind ?? 'hosts',
    groupBy: row.groupBy ?? row.group_by ?? 'status',
    fillBy: row.fillBy ?? row.fill_by ?? 'health',
    sizeBy: row.sizeBy ?? row.size_by ?? 'uniform',
    searchQuery: row.searchQuery ?? row.search_query ?? '',
    schemaVersion: row.schemaVersion ?? row.schema_version ?? 1,
    createdAt: row.createdAt ?? row.created_at ?? '',
    updatedAt: row.updatedAt ?? row.updated_at ?? '',
  }
}

function savedViewRequestPayload(view: SaveInfrastructureMapViewRequest) {
  return {
    name: view.name,
    resource_kind: view.resourceKind,
    group_by: view.groupBy,
    fill_by: view.fillBy,
    size_by: view.sizeBy,
    search_query: view.searchQuery,
  }
}

export function monitoringMethods(core: ApiClientCore) {
  const base = core.API_BASE

  return {
    // Events
    getEvents: (
      params: {
        alertType?: string
        host?: string
        limit?: number
        offset?: number
      } = {}
    ) => {
      const searchParams = new URLSearchParams()
      if (params.alertType) searchParams.set('alert_type', params.alertType)
      if (params.host) searchParams.set('host', params.host)
      if (params.limit != null) searchParams.set('limit', String(params.limit))
      if (params.offset != null) searchParams.set('offset', String(params.offset))
      const qs = searchParams.toString()
      return core.request<DdEventListResponse>(urlWithQuery(`${base}/infra/events`, qs))
    },

    // Service checks
    getServiceChecks: (
      params: {
        checkName?: string
        host?: string
        limit?: number
        offset?: number
      } = {}
    ) => {
      const searchParams = new URLSearchParams()
      if (params.checkName) searchParams.set('check_name', params.checkName)
      if (params.host) searchParams.set('host', params.host)
      if (params.limit != null) searchParams.set('limit', String(params.limit))
      if (params.offset != null) searchParams.set('offset', String(params.offset))
      const qs = searchParams.toString()
      return core.request<DdServiceCheckListResponse>(
        urlWithQuery(`${base}/infra/service-checks`, qs)
      )
    },

    // Hosts
    getHosts: () => core.request<DdHostListResponse>(`${base}/hosts`),

    getHost: (hostId: string) =>
      core.request<DdHostResponse>(`${base}/hosts/${encodeURIComponent(hostId)}`),

    deleteHost: (hostId: string) =>
      core.request<void>(`${base}/hosts/${encodeURIComponent(hostId)}`, { method: 'DELETE' }),

    getHostMetrics: (hostId: string, from?: string, to?: string) => {
      const params = new URLSearchParams()
      if (from) params.append('from', from)
      if (to) params.append('to', to)
      const qs = params.toString()
      return core.request<SystemMetricsHistory>(
        urlWithQuery(`${base}/hosts/${encodeURIComponent(hostId)}/metrics`, qs)
      )
    },

    getHostContainers: (hostId: string) =>
      core.request<DdContainerListResponse>(
        `${base}/hosts/${encodeURIComponent(hostId)}/containers`
      ),

    // Processes
    getProcesses: (
      params: {
        host?: string
        limit?: number
        offset?: number
      } = {}
    ) => {
      const searchParams = new URLSearchParams()
      if (params.host) searchParams.set('host', params.host)
      if (params.limit != null) searchParams.set('limit', String(params.limit))
      if (params.offset != null) searchParams.set('offset', String(params.offset))
      const qs = searchParams.toString()
      return core.request<DdProcessListResponse>(
        urlWithQuery(`${base}/infra/processes`, qs)
      )
    },

    // Containers
    getContainers: (
      params: {
        host?: string
        limit?: number
        offset?: number
      } = {}
    ) => {
      const searchParams = new URLSearchParams()
      if (params.host) searchParams.set('host', params.host)
      if (params.limit != null) searchParams.set('limit', String(params.limit))
      if (params.offset != null) searchParams.set('offset', String(params.offset))
      const qs = searchParams.toString()
      return core.request<DdContainerListResponse>(
        urlWithQuery(`${base}/infra/containers`, qs)
      )
    },

    // Infrastructure map saved views
    getInfrastructureMapSavedViews: async (): Promise<InfrastructureMapSavedViewsResponse> => {
      const response = await core.request<{views?: RawInfrastructureMapSavedView[]}>(
        `${base}/infra/map/saved-views`
      )
      return {
        views: (response.views ?? []).map(mapInfrastructureMapSavedView),
      }
    },

    saveInfrastructureMapView: async (
      view: SaveInfrastructureMapViewRequest
    ): Promise<SavedInfrastructureMapView> => {
      const response = await core.request<RawInfrastructureMapSavedView>(
        `${base}/infra/map/saved-views`,
        {
          method: 'POST',
          body: JSON.stringify(savedViewRequestPayload(view)),
        }
      )
      return mapInfrastructureMapSavedView(response)
    },

    deleteInfrastructureMapSavedView: (viewId: string) =>
      core.request<void>(
        `${base}/infra/map/saved-views/${encodeURIComponent(viewId)}`,
        {method: 'DELETE'}
      ),

    // Resource catalog ownership. The owner is an organization team; Slack, repo,
    // and the current on-call person are resolved from that team server-side.
    // Both endpoints are paid-plan gated server-side (403 when Teams is off).
    claimResourceOwnership: (claim: ResourceOwnershipClaim): Promise<ResourceOwner> =>
      core.request<ResourceOwner>(`${base}/monitoring/resources/ownership`, {
        method: 'PUT',
        body: JSON.stringify(claim),
      }),

    deleteResourceOwnership: (resourceId: string) =>
      core.request<void>(
        `${base}/monitoring/resources/ownership/${encodeURIComponent(resourceId)}`,
        {method: 'DELETE'}
      ),

    // Connections
    getConnections: (
      params: {
        host?: string
        limit?: number
        offset?: number
      } = {}
    ) => {
      const searchParams = new URLSearchParams()
      if (params.host) searchParams.set('host', params.host)
      if (params.limit != null) searchParams.set('limit', String(params.limit))
      if (params.offset != null) searchParams.set('offset', String(params.offset))
      const qs = searchParams.toString()
      return core.request<DdConnectionListResponse>(
        urlWithQuery(`${base}/infra/connections`, qs)
      )
    },

    // Agent API keys
    getAgentApiKeys: async () => {
      const response = await core.request<{ keys: Record<string, unknown>[] }>(
        `${base}/agent-api-keys`
      )
      const keys = (response.keys ?? []).map((k) => ({
        id: k.id as string,
        name: k.name as string,
        keyPrefix: (k.keyPrefix ?? k.key_prefix) as string,
        createdAt: (k.createdAt ?? k.created_at) as string,
        lastUsedAt: (k.lastUsedAt ?? k.last_used_at) as string | undefined,
      }))
      return { keys }
    },

    createAgentApiKey: (name: string) =>
      core.request<CreateDdApiKeyResponse>(`${base}/agent-api-keys`, {
        method: 'POST',
        body: JSON.stringify({ name }),
      }),

    deleteAgentApiKey: (id: string) =>
      core.request<void>(`${base}/agent-api-keys/${encodeURIComponent(id)}`, { method: 'DELETE' }),

    // Cloud sources
    getCloudSources: () =>
      core.request<CloudSourceResponse[]>(`${base}/cloud-sources`),

    getCloudSourceSetupPreview: (provider: CloudSourceProvider) => {
      const query = new URLSearchParams({provider}).toString()
      return core.request<CloudSourceSetupPreview>(
        urlWithQuery(`${base}/cloud-sources/setup-preview`, query)
      )
    },

    createCloudSource: (request: CloudSourceCreateRequest) =>
      core.request<CloudSourceResponse>(`${base}/cloud-sources`, {
        method: 'POST',
        body: JSON.stringify(request),
      }),

    syncCloudSource: (id: string) =>
      core.request<CloudSourceResponse>(`${base}/cloud-sources/${encodeURIComponent(id)}/sync`, {
        method: 'POST',
      }),

    deleteCloudSource: (id: string) =>
      core.request<void>(`${base}/cloud-sources/${encodeURIComponent(id)}`, { method: 'DELETE' }),

    // Monitor hosts
    getMonitorHosts: () =>
      core.request<MonitorHostResponse[]>(`${base}/monitor/hosts`),

    getMonitorHost: (hostId: string) =>
      core.request<MonitorHostResponse>(`${base}/monitor/hosts/${encodeURIComponent(hostId)}`),

    getMonitorHostMetrics: (
      hostId: string,
      from?: string,
      to?: string,
      interval?: string
    ) => {
      const params = new URLSearchParams()
      if (from) params.append('from', from)
      if (to) params.append('to', to)
      if (interval) params.append('interval', interval)
      const query = params.toString()
      return core.request<SystemMetricsHistory>(
        urlWithQuery(`${base}/monitor/hosts/${encodeURIComponent(hostId)}/metrics`, query)
      )
    },

    getMonitorHostContainers: async (hostId: string) => {
      const response = await core.request<{ containers: RawContainerStats[] }>(
        `${base}/monitor/hosts/${encodeURIComponent(hostId)}/containers`
      )
      return response.containers.map((row) => ({
        name: row.name,
        id: row.id,
        image: row.image,
        status: row.status,
        cpuPercent: row.cpuPercent ?? row.cpu_percent,
        memUsed: row.memUsed ?? row.mem_used,
        memLimit: row.memLimit ?? row.mem_limit,
        netRecvBytes: row.netRecvBytes ?? row.net_recv_bytes,
        netSentBytes: row.netSentBytes ?? row.net_sent_bytes,
      }))
    },

    getContainerMetrics: (
      systemId: string,
      containerName: string,
      from?: string,
      to?: string,
      interval?: string
    ) => {
      const params = new URLSearchParams()
      if (from) params.append('from', from)
      if (to) params.append('to', to)
      if (interval) params.append('interval', interval)
      const query = params.toString()
      return core.request<ContainerMetricsHistory>(
        urlWithQuery(
          `${base}/monitor/systems/${encodeURIComponent(systemId)}/containers/${encodeURIComponent(containerName)}/metrics`,
          query
        )
      )
    },

    // Host alerts
    getHostAlertConfig: async (hostId: string) => {
      const response = await core.request<{
        scope?: string
        globalAlerts?: Record<string, unknown>[]
        global_alerts?: Record<string, unknown>[]
        hostAlerts?: Record<string, unknown>[]
        host_alerts?: Record<string, unknown>[]
        systemAlerts?: Record<string, unknown>[]
        system_alerts?: Record<string, unknown>[]
        effectiveAlerts?: Record<string, unknown>[]
        effective_alerts?: Record<string, unknown>[]
      }>(`${base}/monitor/hosts/${encodeURIComponent(hostId)}/alerts/config`)
      return {
        scope: response.scope === 'global' ? 'global' : 'host',
        globalAlerts: (response.globalAlerts ?? response.global_alerts ?? []).map(
          (row) => ({ ...mapHostAlert(row), scope: 'global' as const })
        ),
        hostAlerts: (
          response.hostAlerts ??
          response.host_alerts ??
          response.systemAlerts ??
          response.system_alerts ??
          []
        ).map((row) => mapHostAlert(row)),
        effectiveAlerts: (response.effectiveAlerts ?? response.effective_alerts ?? []).map(
          (row) => mapHostAlert(row)
        ),
      } as HostAlertConfig
    },

    updateHostAlertScope: (hostId: string, scope: 'global' | 'host') =>
      core.request<void>(`${base}/monitor/hosts/${encodeURIComponent(hostId)}/alerts/scope`, {
        method: 'PUT',
        body: JSON.stringify({ scope }),
      }),

    createHostAlert: async (
      hostId: string,
      alert: {
        metric: string
        condition: string
        threshold: number
        durationSeconds?: number
        enabled?: boolean
        alertPriority?: string
      },
      scope: 'global' | 'host' = 'host'
    ) => {
      const payload = await core.request<Record<string, unknown>>(
        `${base}/monitor/hosts/${encodeURIComponent(hostId)}/alerts?scope=${scope}`,
        { method: 'POST', body: JSON.stringify(hostAlertWriteBody(alert)) }
      )
      return mapHostAlert(payload)
    },

    updateHostAlert: async (
      hostId: string,
      alertId: string,
      updates: Partial<HostAlert>,
      scope: 'global' | 'host' = 'host'
    ) => {
      const payload = await core.request<Record<string, unknown> | undefined>(
        `${base}/monitor/hosts/${encodeURIComponent(hostId)}/alerts/${encodeURIComponent(alertId)}?scope=${scope}`,
        { method: 'PUT', body: JSON.stringify(hostAlertWriteBody(updates)) }
      )
      return payload === undefined ? undefined : mapHostAlert(payload)
    },

    deleteHostAlert: (
      hostId: string,
      alertId: string,
      scope: 'global' | 'host' = 'host'
    ) =>
      core.request<void>(
        `${base}/monitor/hosts/${encodeURIComponent(hostId)}/alerts/${encodeURIComponent(alertId)}?scope=${scope}`,
        { method: 'DELETE' }
      ),

    // Alert lifecycles
    getAlertLifecycles: (params: AlertLifecycleListParams = {}) => {
      const searchParams = new URLSearchParams()
      if (params.status) searchParams.set('status', params.status)
      if (params.limit != null) searchParams.set('limit', String(params.limit))
      const query = searchParams.toString()
      return core.request<AlertEpisode[]>(urlWithQuery(`${base}/alerts/lifecycles`, query))
    },

    ignoreAlertLifecycle: (episodeId: string, reason?: string) =>
      core.request<AlertEpisode>(`${base}/alerts/lifecycles/${encodeURIComponent(episodeId)}/ignore`, {
        method: 'POST',
        body: JSON.stringify(reason === undefined ? {} : {reason}),
      }),

    unignoreAlertLifecycle: (episodeId: string) =>
      core.request<AlertEpisode>(
        `${base}/alerts/lifecycles/${encodeURIComponent(episodeId)}/unignore`,
        {
          method: 'POST',
        }
      ),

    // Silence periods
    getSilencePeriods: async () => {
      const response = await core.request<Record<string, unknown>[]>(
        `${base}/monitor/silence-periods`
      )
      return response.map((row) => mapSilencePeriod(row))
    },

    createSilencePeriod: async (data: CreateSilencePeriodRequest) => {
      const response = await core.request<Record<string, unknown>>(
        `${base}/monitor/silence-periods`,
        {
          method: 'POST',
          body: JSON.stringify(data),
        }
      )
      return mapSilencePeriod(response)
    },

    deleteSilencePeriod: (id: string) =>
      core.request<void>(`${base}/monitor/silence-periods/${encodeURIComponent(id)}`, {
        method: 'DELETE',
      }),
  }
}
