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
  ApmTraceListResponse,
  ApmTraceDetailResponse,
  ApmServiceMapResponse,
  ApmErrorsResponse,
  ApmResourceStatsResponse,
} from '../types'

export function apmMethods(core: ApiClientCore) {
  const base = core.API_BASE

  return {
    getApmTraces: (
      params: {
        service?: string
        env?: string
        limit?: number
        offset?: number
      } = {}
    ) => {
      const searchParams = new URLSearchParams()
      if (params.service) searchParams.set('service', params.service)
      if (params.env) searchParams.set('env', params.env)
      if (params.limit != null) searchParams.set('limit', String(params.limit))
      if (params.offset != null) searchParams.set('offset', String(params.offset))
      const qs = searchParams.toString()
      return core.request<ApmTraceListResponse>(urlWithQuery(`${base}/traces`, qs))
    },

    getApmTraceDetail: (traceId: string) =>
      core.request<ApmTraceDetailResponse>(`${base}/traces/${traceId}`),

    getApmServiceMap: () =>
      core.request<ApmServiceMapResponse>(`${base}/services/map`),

    getApmErrors: (
      params: {
        service?: string
        limit?: number
        offset?: number
      } = {}
    ) => {
      const searchParams = new URLSearchParams()
      if (params.service) searchParams.set('service', params.service)
      if (params.limit != null) searchParams.set('limit', String(params.limit))
      if (params.offset != null) searchParams.set('offset', String(params.offset))
      const qs = searchParams.toString()
      return core.request<ApmErrorsResponse>(urlWithQuery(`${base}/apm-errors`, qs))
    },

    getApmResourceStats: (
      params: {
        service?: string
        limit?: number
        offset?: number
      } = {}
    ) => {
      const searchParams = new URLSearchParams()
      if (params.service) searchParams.set('service', params.service)
      if (params.limit != null) searchParams.set('limit', String(params.limit))
      if (params.offset != null) searchParams.set('offset', String(params.offset))
      const qs = searchParams.toString()
      return core.request<ApmResourceStatsResponse>(
        urlWithQuery(`${base}/traces/resources`, qs)
      )
    },
  }
}
