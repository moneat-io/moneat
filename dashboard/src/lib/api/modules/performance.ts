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
  TransactionSummary,
  PerformanceStats,
  TransactionDetail,
  TransactionWithSpans,
  TraceDetail,
  SpanDetail,
  Event,
} from '../types'

export function performanceMethods(core: ApiClientCore) {
  const base = core.API_BASE

  return {
    getTransactions: async (
      projectId: string | number,
      options: { period?: '24h' | '7d' | '30d' | '90d'; environment?: string; operation?: string } = {}
    ) => {
      const params = new URLSearchParams()
      params.set('period', options.period || '7d')
      if (options.environment) params.set('environment', options.environment)
      if (options.operation) params.set('operation', options.operation)
      const rows = await core.request<
        Array<TransactionSummary & { latest_event_id?: string; failure_rate?: number }>
      >(`${base}/projects/${projectId}/transactions?${params.toString()}`)
      return rows.map((row) => ({
        ...row,
        latestEventId: row.latestEventId || row.latest_event_id,
        failureRate: row.failureRate ?? row.failure_rate ?? 0,
      }))
    },

    getPerformanceStats: (
      projectId: string | number,
      options: { period?: '24h' | '7d' | '30d' | '90d'; environment?: string; operation?: string } = {}
    ) => {
      const params = new URLSearchParams()
      params.set('period', options.period || '7d')
      if (options.environment) params.set('environment', options.environment)
      if (options.operation) params.set('operation', options.operation)
      return core.request<PerformanceStats>(
        `${base}/projects/${projectId}/transactions/stats?${params.toString()}`
      )
    },

    getTransaction: (eventId: string) =>
      core.request<TransactionDetail>(`${base}/transactions/${encodeURIComponent(eventId)}`),

    getTransactionSpans: (eventId: string) =>
      core.request<TransactionWithSpans>(
        `${base}/transactions/${encodeURIComponent(eventId)}/spans`
      ),

    getTraceDetails: (projectId: string | number, traceId: string) =>
      core.request<TraceDetail>(
        `${base}/projects/${projectId}/traces/${encodeURIComponent(traceId)}`
      ),

    getSpanDetails: (projectId: string | number, spanId: string) =>
      core.request<SpanDetail>(
        `${base}/projects/${projectId}/spans/${encodeURIComponent(spanId)}`
      ),

    getRelatedErrors: (eventId: string, limit = 20) =>
      core.request<Event[]>(
        `${base}/transactions/${encodeURIComponent(eventId)}/related-errors?limit=${limit}`
      ),
  }
}
