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

export interface LlmTimelinePoint {
  timestamp: string
  count: number
  tokens: number
  cost: number
  errors: number
}

export type LlmServiceId = string

export interface LlmScopeParams {
  services?: string[]
  serviceIds?: LlmServiceId[]
}

export interface LlmRangeParams extends LlmScopeParams {
  range?: string
}

export interface LlmGenerationsParams extends LlmRangeParams {
  model?: string
  provider?: string
  type?: string
  status?: string
  page?: number
  pageSize?: number
}

export interface LlmModelStats {
  model: string
  provider: string
  callCount: number
  totalTokens: number
  totalCost: number
  avgDurationMs: number
  errorRate: number
}

export interface LlmOverviewResponse {
  totalGenerations: number
  totalTokens: number
  totalCost: number
  avgDurationMs: number
  errorRate: number
  timeline: LlmTimelinePoint[]
  topModels: LlmModelStats[]
}

export interface LlmGeneration {
  generationId: string
  traceId: string
  spanId: string
  parentSpanId: string
  timestamp: string
  durationMs: number
  name: string
  model: string
  provider: string
  type: string
  inputTokens: number
  outputTokens: number
  totalTokens: number
  costUsd: number
  status: string
  errorMessage: string
  userId: string
  environment: string
  release: string
}

export interface LlmGenerationDetail extends LlmGeneration {
  input: string
  output: string
  temperature: number
  maxTokens: number
  topP: number
  statusCode: number
  sessionId: string
  tags: Record<string, string>
  metadata: string
}

export interface LlmGenerationsListResponse {
  generations: LlmGeneration[]
  total: number
  page: number
  pageSize: number
}

export interface LlmTraceResponse {
  traceId: string
  generations: LlmGenerationDetail[]
  totalDurationMs: number
  totalTokens: number
  totalCost: number
}

export interface LlmCostBreakdown {
  model: string
  provider: string
  totalCost: number
  totalTokens: number
  callCount: number
}

export interface LlmCostsResponse {
  totalCost: number
  breakdown: LlmCostBreakdown[]
  timeline: LlmTimelinePoint[]
}
