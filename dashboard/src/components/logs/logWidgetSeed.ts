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

import type {DashboardWidget, FilterDef, GroupByDef, QueryDsl} from '@/lib/api'
import type {FacetFilter} from '@/lib/filters/types'

/**
 * Translates the Log Explorer's current query state into a dashboard `logs`
 * widget so "Create metric" can reuse the real widget editor (live preview,
 * type picker, save-to-dashboard) instead of a one-off metric form. The seed is
 * a starting point — the user refines it in the widget editor before saving.
 */

const ALL_LOG_LEVELS = 6
const LOG_METRIC_WIDGET_DASHBOARD_ID = 'log-metric-draft-dashboard'
const LOG_METRIC_WIDGET_ID = 'log-metric-draft-widget'

export interface LogMetricWidgetSeed {
  /** Raw free-text search (the non-facet portion of the query). */
  query: string
  /** Active level filter; empty means "all levels" (no filter emitted). */
  levels: string[]
  /** Structured facet filters (service, environment, tags, …). */
  facetFilters: FacetFilter[]
  /** Visualization group-by field ('' | level | service | environment | …). */
  groupByField: string
  /** Current explorer time window (ISO strings, both set for custom ranges). */
  timeRange: {from?: string; to?: string}
  /** Optional widget title; falls back to a query-derived default. */
  title?: string
}

export function buildLogMetricFilters(facetFilters: FacetFilter[], levels: string[]): FilterDef[] {
  const filters: FilterDef[] = facetFilters.map((filter) => ({
    field: filter.key,
    op: filter.exclude ? 'neq' : 'eq',
    value: filter.value,
  }))
  if (levels.length > 0 && levels.length < ALL_LOG_LEVELS) {
    filters.push({field: 'level', op: 'in', values: [...levels]})
  }
  return filters
}

export function buildLogMetricGroupBy(groupByField: string): GroupByDef[] {
  const groupBy: GroupByDef[] = [{field: 'timestamp', type: 'time', interval: 'auto'}]
  if (groupByField) {
    groupBy.push({field: groupByField, type: 'field'})
  }
  return groupBy
}

export function buildLogMetricQuery(seed: LogMetricWidgetSeed): QueryDsl {
  const raw = seed.query.trim()
  // Use the explorer's absolute window only for fully-specified custom ranges;
  // otherwise default to a clean relative window the user can adjust.
  const timeRange =
    seed.timeRange.from && seed.timeRange.to
      ? {from: seed.timeRange.from, to: seed.timeRange.to}
      : {from: 'now-24h', to: 'now'}
  return {
    dataSource: 'logs',
    metrics: [{function: 'count', alias: 'count'}],
    groupBy: buildLogMetricGroupBy(seed.groupByField),
    filters: buildLogMetricFilters(seed.facetFilters, seed.levels),
    limit: 100,
    timeRange,
    ...(raw ? {rawQuery: raw} : {}),
    ref_id: 'A',
  }
}

export function defaultLogMetricTitle(query: string): string {
  const raw = query.trim()
  return raw ? `Logs · ${raw}` : 'Log volume'
}

export function buildLogMetricWidget(seed: LogMetricWidgetSeed): DashboardWidget {
  return {
    id: LOG_METRIC_WIDGET_ID,
    dashboard_id: LOG_METRIC_WIDGET_DASHBOARD_ID,
    title: seed.title?.trim() || defaultLogMetricTitle(seed.query),
    widget_type: 'timeseries',
    grid_x: 0,
    grid_y: 0,
    grid_w: 6,
    grid_h: 8,
    query_configs: [buildLogMetricQuery(seed)],
    display_config: {},
    sort_order: 0,
  }
}
