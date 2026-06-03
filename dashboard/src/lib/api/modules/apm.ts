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
  ApmOverviewResponse,
  ApmTraceListResponse,
  ApmTraceDetailResponse,
  ApmServiceMapResponse,
  ApmErrorsResponse,
  ApmResourceStatsResponse,
  ApmStatusFilter,
  ApmTimeRange,
} from '../types'

type ApmListParams = {
  service?: string
  services?: string[]
  source?: string
  env?: string
  status?: ApmStatusFilter
  search?: string
  limit?: number
  offset?: number
  timeRange?: ApmTimeRange
}

function appendApmListParams(searchParams: URLSearchParams, params: ApmListParams): void {
  if (params.service) searchParams.set('service', params.service)
  if (params.services?.length) searchParams.set('services', params.services.join(','))
  if (params.source) searchParams.set('source', params.source)
  if (params.env) searchParams.set('env', params.env)
  if (params.status) searchParams.set('status', params.status)
  if (params.search) searchParams.set('search', params.search)
  if (params.limit != null) searchParams.set('limit', String(params.limit))
  if (params.offset != null) searchParams.set('offset', String(params.offset))
  if (params.timeRange) searchParams.set('timeRange', params.timeRange)
}

function normalizeStringMap(raw: unknown): Record<string, string> {
  if (!raw) return {}
  if (Array.isArray(raw)) {
    const result: Record<string, string> = {}
    for (const item of raw) {
      if (item && typeof item === 'object' && 'key' in item && 'value' in item) {
        result[String(item.key)] = String(item.value)
      }
    }
    return result
  }
  if (typeof raw === 'object') {
    const result: Record<string, string> = {}
    for (const [k, v] of Object.entries(raw as Record<string, unknown>)) {
      result[k] = typeof v === 'string' ? v : String(v ?? '')
    }
    return result
  }
  return {}
}

function normalizeNumberMap(raw: unknown): Record<string, number> {
  if (!raw) return {}
  if (Array.isArray(raw)) {
    const result: Record<string, number> = {}
    for (const item of raw) {
      if (item && typeof item === 'object' && 'key' in item && 'value' in item) {
        result[String(item.key)] = toFiniteNumber(item.value)
      }
    }
    return result
  }
  if (typeof raw === 'object') {
    const result: Record<string, number> = {}
    for (const [k, v] of Object.entries(raw as Record<string, unknown>)) {
      result[k] = toFiniteNumber(v)
    }
    return result
  }
  return {}
}

function toFiniteNumber(value: unknown): number {
  const parsed = Number(value ?? 0)
  return Number.isFinite(parsed) ? parsed : 0
}

function normalizeSpan(span: Record<string, unknown>): Record<string, unknown> {
  return {
    ...span,
    meta: normalizeStringMap(span.meta),
    metrics: normalizeNumberMap(span.metrics),
    resourceAttributes: normalizeStringMap(span.resourceAttributes),
  }
}

export function apmMethods(core: ApiClientCore) {
  const base = core.API_BASE

  return {
    getApmTraces: (
      params: ApmListParams = {}
    ) => {
      const searchParams = new URLSearchParams()
      appendApmListParams(searchParams, params)
      const qs = searchParams.toString()
      return core.request<ApmTraceListResponse>(urlWithQuery(`${base}/traces`, qs))
    },

    getApmOverview: (
      params: Omit<ApmListParams, 'limit' | 'offset'> = {}
    ) => {
      const searchParams = new URLSearchParams()
      appendApmListParams(searchParams, params)
      const qs = searchParams.toString()
      return core.request<ApmOverviewResponse>(urlWithQuery(`${base}/traces/overview`, qs))
    },

    getApmTraceDetail: async (traceId: string): Promise<ApmTraceDetailResponse> => {
      const raw = await core.request<ApmTraceDetailResponse & {spans?: Record<string, unknown>[]}>(
        `${base}/traces/${traceId}`
      )
      return {
        ...raw,
        spans: (raw.spans ?? []).map((s) =>
          normalizeSpan(s as unknown as Record<string, unknown>)
        ) as unknown as ApmTraceDetailResponse['spans'],
      }
    },

    getApmServiceMap: () =>
      core.request<ApmServiceMapResponse>(`${base}/services/map`),

    getApmErrors: (
      params: ApmListParams = {}
    ) => {
      const searchParams = new URLSearchParams()
      appendApmListParams(searchParams, params)
      const qs = searchParams.toString()
      return core.request<ApmErrorsResponse>(urlWithQuery(`${base}/apm-errors`, qs))
    },

    getApmResourceStats: (
      params: ApmListParams = {}
    ) => {
      const searchParams = new URLSearchParams()
      appendApmListParams(searchParams, params)
      const qs = searchParams.toString()
      return core.request<ApmResourceStatsResponse>(
        urlWithQuery(`${base}/traces/resources`, qs)
      )
    },
  }
}
