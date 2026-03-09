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
  LogApiKey,
  LogEntry,
  LogQueryResponse,
  LogFilterOptionsWithCounts,
  LogAggregateResponse,
  LogTopResponse,
  CreateLogApiKeyResponse,
  RawLogResponse,
  RawLogFilterResponse,
  RawLogAggregateResponse,
  RawLogTopResponse,
} from '../types'

function mapLogRow(row: Record<string, unknown>): LogEntry {
  return {
    logId: (row.logId ?? row.log_id) as string,
    timestamp: row.timestamp as string,
    level: row.level as string,
    message: row.message as string,
    body: (row.body ?? '') as string,
    service: (row.service ?? '') as string,
    environment: (row.environment ?? '') as string,
    host: (row.host ?? '') as string,
    source: (row.source ?? 'sdk') as string,
    containerName: (row.containerName ?? row.container_name ?? '') as string,
    containerId: (row.containerId ?? row.container_id ?? '') as string,
    containerImage: (row.containerImage ?? row.container_image ?? '') as string,
    traceId: (row.traceId ?? row.trace_id ?? '') as string,
    spanId: (row.spanId ?? row.span_id ?? '') as string,
    tags: (row.tags ?? {}) as Record<string, string>,
    resourceAttributes: (row.resourceAttributes ?? row.resource_attributes ?? {}) as Record<string, string>,
  }
}

function mapRawLogResponse(response: RawLogResponse): LogQueryResponse {
  const rows: Record<string, unknown>[] = response.logs ?? []
  const logs = rows.map(mapLogRow)
  return {
    logs,
    nextCursor: response.nextCursor ?? response.next_cursor ?? null,
    hasMore: response.hasMore ?? response.has_more ?? false,
    totalCount: response.totalCount ?? response.total_count ?? null,
  }
}

function buildLogFilterParams(options: {
  query?: string
  levels?: string[]
  service?: string
  environment?: string
  from?: string
  to?: string
  tags?: Record<string, string>
  excludeService?: string
  excludeEnvironment?: string
  excludeContainerName?: string
  excludeTags?: Record<string, string>
}): URLSearchParams {
  const params = new URLSearchParams()
  if (options.query) params.set('q', options.query)
  if (options.levels && options.levels.length > 0) {
    options.levels.forEach((level) => { params.append('level', level) })
  }
  if (options.service) params.set('service', options.service)
  if (options.environment) params.set('environment', options.environment)
  if (options.from) params.set('from', options.from)
  if (options.to) params.set('to', options.to)
  if (options.tags) {
    Object.entries(options.tags).forEach(([key, value]) => {
      if (key) { params.append('tag', `${key}:${value}`) }
    })
  }
  if (options.excludeService) params.set('excludeService', options.excludeService)
  if (options.excludeEnvironment) params.set('excludeEnvironment', options.excludeEnvironment)
  if (options.excludeContainerName) params.set('excludeContainerName', options.excludeContainerName)
  if (options.excludeTags) {
    Object.entries(options.excludeTags).forEach(([key, value]) => {
      if (key) { params.append('excludeTag', `${key}:${value}`) }
    })
  }
  return params
}

export function logsMethods(core: ApiClientCore) {
  const base = core.API_BASE

  return {
    getLogs: async (
      options: {
        cursor?: string
        limit?: number
        query?: string
        levels?: string[]
        service?: string
        environment?: string
        containerName?: string
        from?: string
        to?: string
        tags?: Record<string, string>
        excludeService?: string
        excludeEnvironment?: string
        excludeContainerName?: string
        excludeTags?: Record<string, string>
      } = {}
    ): Promise<LogQueryResponse> => {
      const params = buildLogFilterParams(options)
      if (options.cursor) params.set('cursor', options.cursor)
      params.set('limit', String(options.limit ?? 100))
      if (options.containerName) params.set('containerName', options.containerName)
      const response = await core.request<RawLogResponse>(`${base}/logs?${params.toString()}`)
      return mapRawLogResponse(response)
    },

    getSystemLogs: async (
      systemId: string,
      options: {
        cursor?: string
        limit?: number
        query?: string
        levels?: string[]
        service?: string
        environment?: string
        containerName?: string
        from?: string
        to?: string
        tags?: Record<string, string>
      } = {}
    ): Promise<LogQueryResponse> => {
      const params = new URLSearchParams()
      if (options.cursor) params.set('cursor', options.cursor)
      params.set('limit', String(options.limit ?? 100))
      if (options.query) params.set('query', options.query)
      if (options.levels && options.levels.length > 0) {
        options.levels.forEach((level) => { params.append('levels', level) })
      }
      if (options.service) params.set('service', options.service)
      if (options.environment) params.set('environment', options.environment)
      if (options.containerName) params.set('container_name', options.containerName)
      if (options.from) params.set('from', options.from)
      if (options.to) params.set('to', options.to)
      if (options.tags) {
        Object.entries(options.tags).forEach(([key, value]) => {
          if (key) { params.append('tag', `${key}:${value}`) }
        })
      }
      const response = await core.request<RawLogResponse>(
        `${base}/monitor/systems/${systemId}/logs?${params.toString()}`
      )
      return mapRawLogResponse(response)
    },

    getLogFilters: async (
      options: { from?: string; to?: string } = {}
    ): Promise<LogFilterOptionsWithCounts> => {
      const params = new URLSearchParams()
      if (options.from) params.set('from', options.from)
      if (options.to) params.set('to', options.to)
      const query = params.toString()
      const response = await core.request<RawLogFilterResponse>(
        urlWithQuery(`${base}/logs/filters`, query)
      )
      const mapServices = (arr: (string | { value: string; count: number })[]) =>
        arr.map((item: string | { value: string; count: number }) =>
          typeof item === 'string' ? { value: item, count: 0 } : { value: item.value, count: item.count ?? 0 }
        )
      return {
        services: mapServices(response.services ?? []),
        environments: mapServices(response.environments ?? []),
        levels: response.levels ?? [],
        tagKeys: response.tagKeys ?? response.tag_keys ?? [],
      }
    },

    getLogApiKeys: async (): Promise<{ keys: LogApiKey[] }> => {
      const response = await core.request<{ keys: Record<string, unknown>[] }>(`${base}/logs/api-keys`)
      const keys = (response.keys ?? []).map((k) => ({
        id: k.id as number,
        name: k.name as string,
        keyPrefix: (k.keyPrefix ?? k.key_prefix) as string,
        createdAt: (k.createdAt ?? k.created_at) as string,
        lastUsedAt: (k.lastUsedAt ?? k.last_used_at) as string | undefined,
      }))
      return { keys }
    },

    createLogApiKey: (name: string) =>
      core.request<CreateLogApiKeyResponse>(`${base}/logs/api-keys`, {
        method: 'POST',
        body: JSON.stringify({ name }),
      }),

    deleteLogApiKey: (id: number) =>
      core.request<void>(`${base}/logs/api-keys/${id}`, { method: 'DELETE' }),

    getLogTagValues: async (
      key: string,
      options: { from?: string; to?: string; limit?: number } = {}
    ) => {
      const params = new URLSearchParams()
      params.set('key', key)
      if (options.from) params.set('from', options.from)
      if (options.to) params.set('to', options.to)
      if (options.limit) params.set('limit', String(options.limit))
      return core.request<{ key: string; values: string[] }>(
        `${base}/logs/tag-values?${params.toString()}`
      )
    },

    createLogTailStream: (
      options: {
        query?: string
        levels?: string[]
        service?: string
        environment?: string
      } = {}
    ) => {
      const params = new URLSearchParams()
      if (options.query) params.set('q', options.query)
      if (options.levels && options.levels.length > 0) {
        options.levels.forEach((level) => { params.append('level', level) })
      }
      if (options.service) params.set('service', options.service)
      if (options.environment) params.set('environment', options.environment)
      return new EventSource(`${base}/logs/tail?${params.toString()}`, { withCredentials: true })
    },

    getLogAggregate: async (
      options: {
        from?: string
        to?: string
        interval?: string
        query?: string
        levels?: string[]
        service?: string
        environment?: string
        tags?: Record<string, string>
        excludeService?: string
        excludeEnvironment?: string
        excludeContainerName?: string
        excludeTags?: Record<string, string>
        groupBy?: string
      } = {}
    ): Promise<LogAggregateResponse> => {
      const params = buildLogFilterParams(options)
      if (options.interval) params.set('interval', options.interval)
      if (options.groupBy) params.set('groupBy', options.groupBy)
      const response = await core.request<RawLogAggregateResponse>(
        `${base}/logs/aggregate?${params.toString()}`
      )
      return {
        buckets: (response.buckets ?? []).map((b) => ({
          timestamp: b.timestamp,
          count: b.count ?? 0,
          groups: b.groups ?? {},
        })),
        totalCount: response.total_count ?? response.totalCount ?? 0,
        interval: response.interval ?? 'auto',
      }
    },

    getLogTop: async (
      options: {
        field: string
        limit?: number
        from?: string
        to?: string
        query?: string
        levels?: string[]
        service?: string
        environment?: string
        tags?: Record<string, string>
        excludeService?: string
        excludeEnvironment?: string
        excludeContainerName?: string
        excludeTags?: Record<string, string>
      }
    ): Promise<LogTopResponse> => {
      const params = buildLogFilterParams(options)
      params.set('field', options.field)
      if (options.limit) params.set('limit', String(options.limit))
      const response = await core.request<RawLogTopResponse>(
        `${base}/logs/top?${params.toString()}`
      )
      return {
        field: response.field ?? options.field,
        values: (response.values ?? []).map((v) => ({
          value: v.value,
          count: v.count ?? 0,
        })),
        totalCount: response.total_count ?? response.totalCount ?? 0,
      }
    },

    downloadLogExport: async (
      options: {
        from?: string
        to?: string
        query?: string
        levels?: string[]
        service?: string
        environment?: string
        tags?: Record<string, string>
        excludeService?: string
        excludeEnvironment?: string
        excludeContainerName?: string
        excludeTags?: Record<string, string>
        limit?: number
      } = {}
    ) => {
      const params = buildLogFilterParams(options)
      if (options.limit) params.set('limit', String(options.limit))
      const response = await core.fetchWithAuth(`${base}/logs/export?${params.toString()}`)
      if (!response.ok) throw new Error('Export failed')
      const blob = await response.blob()
      const url = URL.createObjectURL(blob)
      const a = document.createElement('a')
      a.href = url
      a.download = 'logs-export.csv'
      document.body.appendChild(a)
      a.click()
      a.remove()
      URL.revokeObjectURL(url)
    },
  }
}
