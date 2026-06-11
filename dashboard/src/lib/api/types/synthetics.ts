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

export type SyntheticAuthMethod = 'basic' | 'bearer' | null

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

export interface AlertConfig {
  consecutiveChecks: number
  minLocations: number
  totalLocations: number
  retestCount: number
  renotifyMinutes?: number | null
  notifyOnRecovery: boolean
  slowResponseMs?: number | null
  slowResponseWindowMin: number
}

export interface AlertRecipient {
  type: string
  target: string
  label?: string
}

export interface BrowserStep {
  action: string
  label?: string
  selector?: string
  value?: string
  assertType?: string
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
  authMethod?: SyntheticAuthMethod
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
  service?: string | null
  environment?: string | null
  locations?: string[]
  alertConfig?: AlertConfig | null
  alertRecipients?: AlertRecipient[]
  browserSteps?: BrowserStep[]
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
  authMethod?: SyntheticAuthMethod
  authUser?: string | null
  authPass?: string | null
  assertions?: SyntheticAssertionPayload[]
  steps?: SyntheticStepPayload[]
  service?: string | null
  environment?: string | null
  locations?: string[]
  alertConfig?: AlertConfig | null
  alertRecipients?: AlertRecipient[]
  browserSteps?: BrowserStep[]
}

export interface SyntheticTestResponse {
  id: string
  name: string
  testType: string
  active: boolean
  intervalSeconds: number
  timeoutSeconds: number
  url?: string | null
  method: string
  headers?: Record<string, string> | null
  body?: string | null
  authMethod?: SyntheticAuthMethod
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
  service?: string | null
  environment?: string | null
  locations?: string[]
  alertConfig?: AlertConfig | null
  alertRecipients?: AlertRecipient[]
  browserSteps?: BrowserStep[]
  createdAt: number
  updatedAt: number
}

export interface SyntheticResultResponse {
  resultId: string
  testId: string
  testName: string
  testType: string
  status: string
  probeDc: string
  durationMs: number
  errorMessage: string
  timings: Record<string, number>
  timestamp: string
  locationCode?: string
  statusCode?: number
  attempt?: number
  assertionsTotal?: number
  assertionsFailed?: number
  resolvedIp?: string
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
  id: string
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

export interface SyntheticLocationResponse {
  id: string
  code: string
  name: string
  region: string
  type: string
  active: boolean
  workerCount: number
  lastSeenAt?: number | null
}

export interface CreatePrivateLocationRequest {
  code: string
  name: string
  region?: string
}

export interface CreatePrivateLocationResponse {
  location: SyntheticLocationResponse
  key: string
}

export interface LocationSummary {
  locationCode: string
  uptimePercent: number
  avgResponseMs: number
  p95ResponseMs: number
  totalRuns: number
  failureCount: number
}

export interface AssertionResult {
  label: string
  expected?: string
  actual?: string
  passed: boolean
}

export interface CapturedRequest {
  method?: string
  url?: string
  headers?: Record<string, string>
  body?: string
}

export interface CapturedResponse {
  statusCode?: number
  statusText?: string
  headers?: Record<string, string>
  body?: string
}

export interface BrowserConsoleEntry {
  level: string
  text: string
}

export interface BrowserNetworkEntry {
  status?: number
  method?: string
  url?: string
  durationMs?: number
}

export interface BrowserStepResult {
  action: string
  label?: string
  status: string
  durationMs?: number
  screenshotKey?: string
  errorMessage?: string
}

export interface BrowserRunDetail {
  steps?: BrowserStepResult[]
  console?: BrowserConsoleEntry[]
  network?: BrowserNetworkEntry[]
  viewport?: string
  browser?: string
  failedStep?: number | null
}

export interface SyntheticRunDetail {
  assertions?: AssertionResult[]
  request?: CapturedRequest | null
  response?: CapturedResponse | null
  timings?: Record<string, number>
  resolvedIp?: string
  browser?: BrowserRunDetail | null
}

export interface SyntheticRunResponse {
  resultId: string
  testId: string
  testName: string
  testType: string
  status: string
  locationCode: string
  durationMs: number
  statusCode: number
  attempt: number
  assertionsTotal: number
  assertionsFailed: number
  errorMessage: string
  timestamp: string
  detail?: SyntheticRunDetail | null
}
