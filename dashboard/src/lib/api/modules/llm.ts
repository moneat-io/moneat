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
import type {
  LlmGenerationsParams,
  LlmOverviewResponse,
  LlmGenerationsListResponse,
  LlmGenerationDetail,
  LlmRangeParams,
  LlmScopeParams,
  LlmTraceResponse,
  LlmModelStats,
  LlmCostsResponse,
} from '../types'
import { urlWithQuery } from '../utils'

function appendListParam(searchParams: URLSearchParams, key: string, values?: Array<string | number>) {
  values?.forEach((value) => searchParams.append(key, String(value)))
}

function appendScopeParams(searchParams: URLSearchParams, params: LlmScopeParams) {
  appendListParam(searchParams, 'services', params.services)
  appendListParam(searchParams, 'serviceIds', params.serviceIds)
}

function llmQuery(params: LlmGenerationsParams = {}): string {
  const searchParams = new URLSearchParams()
  if (params.range) searchParams.set('range', params.range)
  if (params.model) searchParams.set('model', params.model)
  if (params.provider) searchParams.set('provider', params.provider)
  if (params.type) searchParams.set('type', params.type)
  if (params.status) searchParams.set('status', params.status)
  if (params.page !== undefined) searchParams.set('page', String(params.page))
  if (params.pageSize !== undefined) searchParams.set('pageSize', String(params.pageSize))
  appendScopeParams(searchParams, params)
  return searchParams.toString()
}

function rangeQuery(params: LlmRangeParams = {}, defaultRange = '24h'): string {
  return llmQuery({...params, range: params.range ?? defaultRange})
}

export function llmMethods(core: ApiClientCore) {
  const base = core.API_BASE

  return {
    getLlmOverview: (params: LlmRangeParams = {}) =>
      core.request<LlmOverviewResponse>(
        urlWithQuery(`${base}/llm/overview`, rangeQuery(params))
      ),

    getLlmGenerations: (
      params: LlmGenerationsParams = {}
    ) => {
      const query = llmQuery(params)
      return core.request<LlmGenerationsListResponse>(
        urlWithQuery(`${base}/llm/generations`, query)
      )
    },

    getLlmGenerationDetail: (generationId: string, params: LlmScopeParams = {}) =>
      core.request<LlmGenerationDetail>(
        urlWithQuery(`${base}/llm/generations/${encodeURIComponent(generationId)}`, llmQuery(params))
      ),

    getLlmTrace: (traceId: string, params: LlmScopeParams = {}) =>
      core.request<LlmTraceResponse>(
        urlWithQuery(`${base}/llm/traces/${encodeURIComponent(traceId)}`, llmQuery(params))
      ),

    getLlmModels: (params: LlmRangeParams = {}) =>
      core.request<LlmModelStats[]>(
        urlWithQuery(`${base}/llm/models`, rangeQuery(params))
      ),

    getLlmCosts: (params: LlmRangeParams = {}) =>
      core.request<LlmCostsResponse>(
        urlWithQuery(`${base}/llm/costs`, rangeQuery(params))
      ),
  }
}
