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

export interface ApmTraceListItem {
  traceId: string
  rootService: string
  rootResource: string
  rootName: string
  spanCount: number
  durationNs: number
  startNs: number
  hasError: boolean
  source: string
}

export type ApmTimeRange = '1h' | '6h' | '24h' | '7d' | '30d' | '90d'

export interface ApmTraceListResponse {
  traces: ApmTraceListItem[]
  totalCount: number
}

export interface ApmSpanResponse {
  spanId: string
  traceId: string
  parentId: string
  name: string
  service: string
  resource: string
  type: string
  startNs: number
  durationNs: number
  error: number
  meta: Record<string, string>
  metrics: Record<string, number>
  host: string
  env: string
  version: string
  source: string
  kind?: string
  statusCode?: number
  statusMessage?: string
  events?: string
  links?: string
  resourceAttributes?: Record<string, string>
}

export interface ApmTraceDetailResponse {
  traceId: string
  spans: ApmSpanResponse[]
}

export interface ApmServiceMapEntry {
  service: string
  spanCount: number
  errorCount: number
  avgDurationNs: number
  callsTo: string[]
}

export interface ApmServiceMapResponse {
  services: ApmServiceMapEntry[]
}

export interface ApmErrorGroup {
  id: string
  service: string
  resource: string
  errorMessage: string
  errorType: string
  count: number
  lastSeen: string
  traceId: string
}

export interface ApmErrorsResponse {
  errors: ApmErrorGroup[]
  totalCount: number
}

export interface ApmResourceStatsItem {
  service: string
  resource: string
  name: string
  type: string
  totalHits: number
  totalErrors: number
  avgDurationNs: number
  errorRate: number
}

export interface ApmResourceStatsResponse {
  resources: ApmResourceStatsItem[]
  totalCount: number
}
