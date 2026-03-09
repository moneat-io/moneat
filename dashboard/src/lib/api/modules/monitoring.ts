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
  SilencePeriod,
  CreateSilencePeriodRequest,
  SystemMetricsHistory,
  MonitorHostResponse,
  RawContainerStats,
  ContainerMetricsHistory,
  CreateDdApiKeyResponse,
} from '../types'

function mapHostAlert(row: Record<string, unknown>): HostAlert {
  const rawScope = ((row.scope as string | undefined) ?? '').toLowerCase()
  return {
    id: row.id as number,
    hostId: (row.hostId ?? row.host_id) as number | undefined,
    scope: rawScope === 'global' ? 'global' : 'host',
    metric: row.metric as string,
    condition: row.condition as string,
    threshold: row.threshold as number,
    durationSeconds: (row.durationSeconds ?? row.duration_seconds ?? 0) as number,
    enabled: row.enabled === true,
    incidentSeverity: (row.incidentSeverity ?? row.incident_severity ?? null) as string | null,
    lastTriggeredAt: (row.lastTriggeredAt ?? row.last_triggered_at) as number | undefined,
    createdAt: (row.createdAt ?? row.created_at) as number,
  }
}

function mapSilencePeriod(row: Record<string, unknown>): SilencePeriod {
  return {
    id: row.id as number,
    organizationId: (row.organization_id ?? row.organizationId) as number,
    reason: (row.reason ?? null) as string | null,
    startsAt: (row.starts_at ?? row.startsAt) as number,
    endsAt: (row.ends_at ?? row.endsAt) as number,
    createdBy: (row.created_by ?? row.createdBy) as number,
    createdAt: (row.created_at ?? row.createdAt) as number,
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

    getHost: (hostId: number) => core.request<DdHostResponse>(`${base}/hosts/${hostId}`),

    deleteHost: (hostId: number) =>
      core.request<void>(`${base}/hosts/${hostId}`, { method: 'DELETE' }),

    getHostMetrics: (hostId: number, from?: string, to?: string) => {
      const params = new URLSearchParams()
      if (from) params.append('from', from)
      if (to) params.append('to', to)
      const qs = params.toString()
      return core.request<SystemMetricsHistory>(
        urlWithQuery(`${base}/hosts/${hostId}/metrics`, qs)
      )
    },

    getHostContainers: (hostId: number) =>
      core.request<DdContainerListResponse>(`${base}/hosts/${hostId}/containers`),

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
        id: k.id as number,
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

    deleteAgentApiKey: (id: number) =>
      core.request<void>(`${base}/agent-api-keys/${id}`, { method: 'DELETE' }),

    // Monitor hosts
    getMonitorHosts: () =>
      core.request<MonitorHostResponse[]>(`${base}/monitor/hosts`),

    getMonitorHost: (hostId: number) =>
      core.request<MonitorHostResponse>(`${base}/monitor/hosts/${hostId}`),

    getMonitorHostMetrics: (
      hostId: number,
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
        urlWithQuery(`${base}/monitor/hosts/${hostId}/metrics`, query)
      )
    },

    getMonitorHostContainers: async (hostId: number) => {
      const response = await core.request<{ containers: RawContainerStats[] }>(
        `${base}/monitor/hosts/${hostId}/containers`
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
        urlWithQuery(`${base}/monitor/systems/${systemId}/containers/${encodeURIComponent(containerName)}/metrics`, query)
      )
    },

    // Host alerts
    getHostAlertConfig: async (hostId: number) => {
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
      }>(`${base}/monitor/hosts/${hostId}/alerts/config`)
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

    updateHostAlertScope: (hostId: number, scope: 'global' | 'host') =>
      core.request<void>(`${base}/monitor/hosts/${hostId}/alerts/scope`, {
        method: 'PUT',
        body: JSON.stringify({ scope }),
      }),

    createHostAlert: async (
      hostId: number,
      alert: {
        metric: string
        condition: string
        threshold: number
        durationSeconds?: number
        enabled?: boolean
        incidentSeverity?: string
      },
      scope: 'global' | 'host' = 'host'
    ) => {
      const payload = await core.request<Record<string, unknown>>(
        `${base}/monitor/hosts/${hostId}/alerts?scope=${scope}`,
        { method: 'POST', body: JSON.stringify(alert) }
      )
      return mapHostAlert(payload)
    },

    updateHostAlert: async (
      hostId: number,
      alertId: number,
      updates: Partial<HostAlert>,
      scope: 'global' | 'host' = 'host'
    ) => {
      const payload = await core.request<Record<string, unknown>>(
        `${base}/monitor/hosts/${hostId}/alerts/${alertId}?scope=${scope}`,
        { method: 'PUT', body: JSON.stringify(updates) }
      )
      return mapHostAlert(payload)
    },

    deleteHostAlert: (
      hostId: number,
      alertId: number,
      scope: 'global' | 'host' = 'host'
    ) =>
      core.request<void>(
        `${base}/monitor/hosts/${hostId}/alerts/${alertId}?scope=${scope}`,
        { method: 'DELETE' }
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

    deleteSilencePeriod: (id: number) =>
      core.request<void>(`${base}/monitor/silence-periods/${id}`, {
        method: 'DELETE',
      }),
  }
}
