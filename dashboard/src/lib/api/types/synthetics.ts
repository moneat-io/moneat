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

export interface SyntheticAssertionPayload {
  type: string
  target?: string
  operator?: string
  value?: string
}

export interface SyntheticVariableExtractionPayload {
  name: string
  source: string
  path?: string
}

export interface SyntheticStepPayload {
  name?: string
  url: string
  method?: string
  headers?: Record<string, string> | null
  body?: string | null
  assertions?: SyntheticAssertionPayload[]
  extractVariables?: SyntheticVariableExtractionPayload[]
}

export interface SyntheticTestConfig {
  dnsServer?: string | null
  port?: number | null
  protocol?: string | null
  hostname?: string | null
}

export interface CreateSyntheticTestPayload {
  name: string
  testType: string
  intervalSeconds: number
  timeoutSeconds: number
  url?: string | null
  method?: string
  headers?: Record<string, string> | null
  body?: string | null
  authMethod?: 'basic' | 'bearer' | null
  authUser?: string | null
  authPass?: string | null
  assertions?: SyntheticAssertionPayload[]
  steps?: SyntheticStepPayload[]
  tags?: string[]
  retryCount?: number
  retryIntervalMs?: number
  alertOnFailure?: boolean
  alertChannels?: string[]
  config?: SyntheticTestConfig | null
}

export interface UpdateSyntheticTestPayload {
  name?: string
  active?: boolean
  intervalSeconds?: number
  timeoutSeconds?: number
  url?: string | null
  method?: string
  headers?: Record<string, string> | null
  body?: string | null
  authMethod?: 'basic' | 'bearer' | null
  authUser?: string | null
  authPass?: string | null
  assertions?: SyntheticAssertionPayload[]
  steps?: SyntheticStepPayload[]
}

export interface SyntheticTestResponse {
  id: string
  organizationId: number
  name: string
  testType: string
  active: boolean
  intervalSeconds: number
  timeoutSeconds: number
  url?: string | null
  method: string
  headers?: Record<string, string> | null
  body?: string | null
  authMethod?: 'basic' | 'bearer' | null
  authUser?: string | null
  assertions: SyntheticAssertionPayload[]
  steps: SyntheticStepPayload[]
  status: string
  lastRunAt?: number | null
  lastStatus?: string | null
  tags?: string[]
  retryCount?: number
  retryIntervalMs?: number
  alertOnFailure?: boolean
  alertChannels?: string[]
  config?: SyntheticTestConfig | null
  createdAt: number
  updatedAt: number
}

export interface SyntheticResultResponse {
  resultId: string
  organizationId: number
  testId: string
  testName: string
  testType: string
  status: string
  probeDc: string
  durationMs: number
  errorMessage: string
  timings: Record<string, number>
  timestamp: string
}

export interface SyntheticResultListResponse {
  results: SyntheticResultResponse[]
  totalCount: number
}

export interface SyntheticTestSummary {
  testId: string
  uptimePercent: number
  avgResponseMs: number
  p95ResponseMs: number
  totalRuns: number
  failureCount: number
}

export interface SyntheticVariableResponse {
  id: number
  organizationId: number
  name: string
  value: string
  isSecret: boolean
  createdAt: number
  updatedAt: number
}

export interface SyntheticVariableRequest {
  name: string
  value: string
  isSecret?: boolean
}
