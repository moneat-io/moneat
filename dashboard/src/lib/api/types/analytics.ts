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
  serviceIds?: string[]
}

export type AnalyticsScopeId = string | null | undefined

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

// ---------------------------------------------------------------------------
// Product analytics (target-state)
//
// Source-neutral, app-agnostic shapes for the product analytics view: a
// north-star KPI band, an activity trend, biggest movers, a configurable
// activation funnel, return-to-value retention cohorts, feature adoption and
// segmentation. Event names here (signup_completed, first_key_action, …) are
// generic defaults each app overrides.
// ---------------------------------------------------------------------------

export interface ProductKpiMetric {
  /** Current value. Percentage metrics are expressed as 0–100. */
  value: number
  /** Previous-period value, for the delta. */
  previous?: number
  /** Small trend series for the sparkline, oldest → newest. */
  spark?: number[]
}

export interface ProductAnalyticsSummary {
  weeklyActiveUsers: ProductKpiMetric
  dailyActiveUsers?: number
  newUsers: ProductKpiMetric
  /** North-star: share of new users who activate (0–100). */
  activationRate: ProductKpiMetric
  /** DAU / MAU (0–100). */
  stickiness: ProductKpiMetric
  /** Week-1 return to a key action (0–100). */
  week1Retention: ProductKpiMetric
  /** Share of active users with 5+ key actions per week (0–100). */
  powerUsers: ProductKpiMetric
}

export type ProductActivityMetric = 'active' | 'new' | 'key_action'

export interface ProductActivityPoint {
  timestamp: string
  value: number
  /** Same point in the previous period, for the comparison overlay. */
  previous?: number
}

export type ProductAnnotationKind = 'release' | 'feature' | 'campaign'

export interface ProductActivityAnnotation {
  date: string
  label: string
  kind?: ProductAnnotationKind
}

export interface ProductActivitySeries {
  metric: ProductActivityMetric
  points: ProductActivityPoint[]
}

export interface ProductActivityResponse {
  series: ProductActivitySeries[]
  annotations?: ProductActivityAnnotation[]
}

export type ProductMoverTone = 'good' | 'bad'

export interface ProductMover {
  name: string
  /** Short category tag, e.g. "activation", "feature", "platform", "plan", "cohort". */
  category: string
  /** Optional context, e.g. "after v3.1", "new", "regression". */
  detail?: string
  /** Pre-formatted magnitude, e.g. "+7.0pp", "−3.2pp", "+19%". */
  change: string
  tone: ProductMoverTone
}

export interface ProductEventTrend {
  name: string
  count: number
  users: number
  /** Period-over-period change in event count, as a fraction (0.12 = +12%). */
  changeRatio?: number
}

export interface ProductFeatureAdoptionItem {
  name: string
  /** Share of active users (0–100). */
  adoptionRate: number
}

export interface ProductSegmentRow {
  name: string
  users: number
  /** 0–100. */
  activationRate: number
  /** 0–100. */
  week1Retention: number
  /** 0–100. */
  stickiness: number
}

export type ProductSegmentDimension = 'plan' | 'platform' | 'country'

export interface ProductSegmentation {
  plan: ProductSegmentRow[]
  platform: ProductSegmentRow[]
  country: ProductSegmentRow[]
}

export type ProductRetentionMode = 'key_action' | 'any_session' | 'custom'

export interface ProductRetentionCohortRow {
  /** Cohort label, e.g. "May 5". */
  cohort: string
  users: number
  /** Retention per period (W0…Wn) as 0–100; null where the period has not elapsed. */
  values: Array<number | null>
}

export interface ProductRetentionGrid {
  mode: ProductRetentionMode
  /** Period offsets shown as columns, e.g. [0, 1, 2, 3, 4, 5, 6]. */
  periods: number[]
  cohorts: ProductRetentionCohortRow[]
}

export interface ProductRetentionQuery extends AnalyticsParams {
  mode: ProductRetentionMode
  /** Required when mode is "custom". */
  customEvent?: string
  /** Number of period columns to return. */
  periods?: number
}
