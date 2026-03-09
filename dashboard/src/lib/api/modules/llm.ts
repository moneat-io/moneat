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
  LlmOverviewResponse,
  LlmGenerationsListResponse,
  LlmGenerationDetail,
  LlmTraceResponse,
  LlmModelStats,
  LlmCostsResponse,
} from '../types'

export function llmMethods(core: ApiClientCore) {
  const base = core.API_BASE

  return {
    getLlmOverview: (projectId: number, range = '24h') =>
      core.request<LlmOverviewResponse>(
        `${base}/llm/overview?projectId=${projectId}&range=${encodeURIComponent(range)}`
      ),

    getLlmGenerations: (
      projectId: number,
      params: {
        range?: string
        model?: string
        provider?: string
        type?: string
        status?: string
        page?: number
        pageSize?: number
      } = {}
    ) => {
      const searchParams = new URLSearchParams({ projectId: String(projectId) })
      if (params.range) searchParams.set('range', params.range)
      if (params.model) searchParams.set('model', params.model)
      if (params.provider) searchParams.set('provider', params.provider)
      if (params.type) searchParams.set('type', params.type)
      if (params.status) searchParams.set('status', params.status)
      if (params.page !== undefined) searchParams.set('page', String(params.page))
      if (params.pageSize !== undefined) searchParams.set('pageSize', String(params.pageSize))
      return core.request<LlmGenerationsListResponse>(
        `${base}/llm/generations?${searchParams}`
      )
    },

    getLlmGenerationDetail: (projectId: number, generationId: string) =>
      core.request<LlmGenerationDetail>(
        `${base}/llm/generations/${encodeURIComponent(generationId)}?projectId=${projectId}`
      ),

    getLlmTrace: (projectId: number, traceId: string) =>
      core.request<LlmTraceResponse>(
        `${base}/llm/traces/${encodeURIComponent(traceId)}?projectId=${projectId}`
      ),

    getLlmModels: (projectId: number, range = '24h') =>
      core.request<LlmModelStats[]>(
        `${base}/llm/models?projectId=${projectId}&range=${encodeURIComponent(range)}`
      ),

    getLlmCosts: (projectId: number, range = '24h') =>
      core.request<LlmCostsResponse>(
        `${base}/llm/costs?projectId=${projectId}&range=${encodeURIComponent(range)}`
      ),
  }
}
