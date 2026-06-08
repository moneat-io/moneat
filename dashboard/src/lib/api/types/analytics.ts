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

export type AnalyticsPeriod =
  | 'today'
  | '7d'
  | '30d'
  | 'month'
  | '6mo'
  | '12mo'
  | 'custom'
export type AnalyticsComparison =
  | 'previous_period'
  | 'year_over_year'
  | 'none'

export interface AnalyticsFilter {
  property: string
  operator: 'is' | 'is_not' | 'contains' | 'not_contains'
  value: string
}

export interface AnalyticsParams {
  period?: AnalyticsPeriod
  from?: string
  to?: string
  filters?: AnalyticsFilter[]
  comparison?: AnalyticsComparison
  services?: string[]
  serviceIds?: Array<string | number>
}

export type AnalyticsScopeId = string | number | null | undefined

export type AnalyticsEventSource = 'web' | 'server'
export type AnalyticsGroupBy = 'session_id' | 'user_id'

export interface AnalyticsEventQueryOptions {
  source?: AnalyticsEventSource
  groupBy?: AnalyticsGroupBy
  limit?: number
}

export interface AnalyticsRetentionQuery extends AnalyticsParams {
  startEvent: string
  returnEvent: string
  periods?: number[]
}

export interface AnalyticsOverview {
  uniqueVisitors: number
  totalPageviews: number
  bounceRate: number
  avgVisitDuration: number
  viewsPerVisit: number
  comparison?: {
    uniqueVisitors: number
    totalPageviews: number
    bounceRate: number
    avgVisitDuration: number
    viewsPerVisit: number
  }
}

export interface AnalyticsTimeseriesPoint {
  timestamp: string
  visitors: number
  pageviews: number
}

export interface AnalyticsBreakdownItem {
  name: string
  visitors: number
  pageviews: number
  bounceRate?: number
  avgDuration?: number
  percentage?: number
}

export interface AnalyticsRealtimeResponse {
  currentVisitors: number
}

export interface AnalyticsFunnelStep {
  name: string
  visitors: number
  dropoff: number
  conversionRate: number
}

export interface AnalyticsFunnelResponse {
  steps: AnalyticsFunnelStep[]
  overallConversion: number
}

export interface AnalyticsRetentionPeriod {
  days: number
  retainedUsers: number
  eligibleUsers?: number
  retentionRate: number
}

export interface AnalyticsRetentionCohort {
  cohortWeek: string
  users: number
  periods: AnalyticsRetentionPeriod[]
}

export interface AnalyticsRetentionResponse {
  startEvent: string
  returnEvent: string
  cohorts: AnalyticsRetentionCohort[]
}

export interface AnalyticsOverviewApiResponse {
  uniqueVisitors?: number
  totalPageviews?: number
  bounceRate?: number
  avgVisitDuration?: number
  viewsPerVisit?: number
  visitors?: number
  pageviews?: number
  compVisitors?: number
  compPageviews?: number
  compBounceRate?: number
  compAvgVisitDuration?: number
  compViewsPerVisit?: number
}

export interface AnalyticsTimeseriesApiPoint {
  timestamp?: string
  date?: string
  visitors?: number
  pageviews?: number
}

export interface AnalyticsBreakdownApiResponse {
  results?: AnalyticsBreakdownItem[]
}

export interface AnalyticsRealtimeApiResponse {
  currentVisitors?: number
  visitors?: number
}
