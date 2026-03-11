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

export interface LogEntry {
  logId: string
  timestamp: string
  level: string
  message: string
  body: string
  service: string
  environment: string
  host: string
  source: string
  containerName: string
  containerId: string
  containerImage: string
  traceId: string
  spanId: string
  tags: Record<string, string>
  resourceAttributes: Record<string, string>
}

export interface LogQueryResponse {
  logs: LogEntry[]
  nextCursor?: string | null
  hasMore: boolean
  totalCount?: number | null
}

export interface LogFilterOptions {
  services: string[]
  environments: string[]
  levels: string[]
  tagKeys: string[]
}

export interface LogFilterOptionWithCount {
  value: string
  count: number
}

export interface LogFilterOptionsWithCounts {
  services: LogFilterOptionWithCount[]
  environments: LogFilterOptionWithCount[]
  levels: string[]
  tagKeys: string[]
}

export interface LogAggregateBucket {
  timestamp: string
  count: number
  groups: Record<string, number>
}

export interface LogAggregateResponse {
  buckets: LogAggregateBucket[]
  totalCount: number
  interval: string
}

export interface LogTopValue {
  value: string
  count: number
}

export interface LogTopResponse {
  field: string
  values: LogTopValue[]
  totalCount: number
}

export interface LogApiKey {
  id: number
  name: string
  keyPrefix: string
  createdAt: string
  lastUsedAt?: string
}

export interface CreateLogApiKeyResponse {
  id: number
  name: string
  keyPrefix: string
  key: string
  createdAt: string
}

export interface LogIndex {
  id: number
  name: string
  filter_query: string
  retention_days: number
  sampling_rate: number
  priority: number
  is_active: boolean
  daily_quota_gb: number | null
  created_at: string
  updated_at: string
}

export interface CreateLogIndexRequest {
  name: string
  filter_query?: string
  retention_days?: number
  sampling_rate?: number
  priority?: number
  daily_quota_gb?: number | null
}

export interface UpdateLogIndexRequest {
  name?: string
  filter_query?: string
  retention_days?: number
  sampling_rate?: number
  priority?: number
  is_active?: boolean
  daily_quota_gb?: number | null
}

export interface LogIndexTestResult {
  match_count: number
  total_count: number
}

export interface RawLogResponse {
  logs?: Record<string, unknown>[]
  nextCursor?: string | null
  next_cursor?: string | null
  hasMore?: boolean
  has_more?: boolean
  totalCount?: number | null
  total_count?: number | null
}

export interface RawLogFilterResponse {
  services?: (string | { value: string; count: number })[]
  environments?: (string | { value: string; count: number })[]
  levels?: string[]
  tagKeys?: string[]
  tag_keys?: string[]
}

export interface RawLogAggregateResponse {
  buckets?: { timestamp: string; count?: number; groups?: Record<string, number> }[]
  total_count?: number
  totalCount?: number
  interval?: string
}

export interface RawLogTopResponse {
  field?: string
  values?: { value: string; count?: number }[]
  total_count?: number
  totalCount?: number
}
