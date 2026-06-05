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

export interface UptimeMonitor {
  id: string
  organizationId: number
  name: string
  type: string
  active: boolean
  url?: string
  hostname?: string
  port?: number
  method?: string
  headers?: Record<string, string>
  body?: string
  authMethod?: string
  authUser?: string
  expectedStatusCodes?: string
  maxRedirects?: number
  ignoreTls?: boolean
  keyword?: string
  keywordInverse?: boolean
  jsonPath?: string
  jsonExpectedValue?: string
  dnsRecordType?: string
  dnsExpectedValue?: string
  dnsServer?: string
  sslExpiryWarnDays?: number
  dbConnectionString?: string
  dbQuery?: string
  dockerContainerName?: string
  dockerHost?: string
  intervalSeconds: number
  timeoutSeconds: number
  retries: number
  retryIntervalSeconds: number
  status: string
  lastCheckAt?: number
  lastStatusChangeAt?: number
  consecutiveFailures: number
  pushToken?: string
  alertPriority?: string | null
  uptime24h?: number
  uptime7d?: number
  uptime30d?: number
  avgResponseTime?: number
  createdAt: number
  updatedAt: number
}

export interface CreateUptimeMonitorRequest {
  name: string
  type: string
  url?: string
  hostname?: string
  port?: number
  method?: string
  headers?: Record<string, string>
  body?: string
  authMethod?: string
  authUser?: string
  authPass?: string
  expectedStatusCodes?: string
  maxRedirects?: number
  ignoreTls?: boolean
  keyword?: string
  keywordInverse?: boolean
  jsonPath?: string
  jsonExpectedValue?: string
  dnsRecordType?: string
  dnsExpectedValue?: string
  dnsServer?: string
  sslExpiryWarnDays?: number
  dbConnectionString?: string
  dbQuery?: string
  dockerContainerName?: string
  dockerHost?: string
  intervalSeconds?: number
  timeoutSeconds?: number
  retries?: number
  retryIntervalSeconds?: number
  alertPriority?: string
}

export interface UpdateUptimeMonitorRequest
  extends Partial<CreateUptimeMonitorRequest> {
  active?: boolean
}

export interface UptimeHeartbeat {
  timestamp: number
  status: number
  responseTimeMs: number
  statusCode: number
  message: string
  pingMs?: number
}
