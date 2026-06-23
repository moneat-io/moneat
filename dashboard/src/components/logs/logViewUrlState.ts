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

import {type FacetFilter} from '@/lib/filters/types'
import {
  parseFacetFiltersParam,
  parseReadableFacetFilters,
  type ReadableFacetSearchValue,
  serializeReadableFacetFilters,
} from '@/lib/filters/urlState'
import {type LogVizMode} from '@/components/logs/LogVizTabs'

/**
 * URL search params schema for shareable log viewer state.
 * This enables deep linking and context-based navigation.
 */
export interface LogViewSearch {
  // Search & filters
  q?: string // query
  levels?: string // comma-separated levels
  facets?: FacetFilter[] // component-internal structured filters; omitted from canonical route search
  service?: ReadableFacetSearchValue
  environment?: ReadableFacetSearchValue
  host?: ReadableFacetSearchValue
  level?: ReadableFacetSearchValue
  container_name?: ReadableFacetSearchValue
  'k8s.pod.name'?: ReadableFacetSearchValue
  team?: ReadableFacetSearchValue
  owner?: ReadableFacetSearchValue
  trace_id?: ReadableFacetSearchValue
  message_pattern?: ReadableFacetSearchValue
  source?: ReadableFacetSearchValue
  exclude_service?: ReadableFacetSearchValue
  exclude_environment?: ReadableFacetSearchValue
  exclude_host?: ReadableFacetSearchValue
  exclude_level?: ReadableFacetSearchValue
  exclude_container_name?: ReadableFacetSearchValue
  'exclude_k8s.pod.name'?: ReadableFacetSearchValue
  exclude_team?: ReadableFacetSearchValue
  exclude_owner?: ReadableFacetSearchValue
  exclude_trace_id?: ReadableFacetSearchValue
  exclude_message_pattern?: ReadableFacetSearchValue
  exclude_source?: ReadableFacetSearchValue
  
  // Time range
  timePreset?: string // e.g., "15m", "1h", "custom"
  from?: string // ISO timestamp for custom range
  to?: string // ISO timestamp for custom range
  
  // Visualization
  viz?: LogVizMode // "timeseries" | "table" | "toplist" | "pie"
  groupBy?: string // for table viz
  topField?: string // for toplist/pie viz
  
  // Pagination
  cursor?: string
  
  // Selected log (for highlight/detail)
  logId?: string
}

export const DEFAULT_LOG_GROUP_BY = 'level'
export const NO_LOG_GROUP_BY_URL_VALUE = 'none'

const VALID_VIZ_MODES: LogVizMode[] = ['timeseries', 'table', 'toplist', 'pie']
const VALID_TIME_PRESETS = ['5m', '15m', '30m', '1h', '4h', '12h', '24h', '3d', '7d', '14d', '30d', 'custom']
const VALID_GROUP_BY_VALUES = [DEFAULT_LOG_GROUP_BY, 'service', 'environment', NO_LOG_GROUP_BY_URL_VALUE]
export const LOG_FACET_URL_KEYS = [
  'service',
  'environment',
  'host',
  'level',
  'container_name',
  'k8s.pod.name',
  'team',
  'owner',
  'trace_id',
  'message_pattern',
  'source',
] as const

export function parseLogViewFacetFilters(
  search: Partial<LogViewSearch> | Record<string, unknown>
): FacetFilter[] | undefined {
  return parseReadableFacetFilters(search as Record<string, unknown>, LOG_FACET_URL_KEYS)
}

/**
 * Parse URL search params into normalized log view state.
 * Defensively handles malformed or invalid values.
 */
export function parseLogViewSearch(search: Record<string, unknown>): LogViewSearch {
  const result: LogViewSearch = {}
  
  // Query
  if (typeof search.q === 'string' && search.q.trim()) {
    result.q = search.q.trim()
  }
  
  // Levels
  if (typeof search.levels === 'string' && search.levels) {
    const parsed = search.levels.split(',').filter(Boolean)
    if (parsed.length > 0) {
      result.levels = parsed.join(',')
    }
  }
  
  // Facet filters. Prefer readable params (`service=api`); keep legacy
  // `facets=` JSON as input-only compatibility.
  const facetFilters = parseLogViewFacetFilters(search)
  if (facetFilters) {
    Object.assign(result, serializeReadableFacetFilters(facetFilters, LOG_FACET_URL_KEYS))
  }
  
  // Time preset
  if (typeof search.timePreset === 'string' && VALID_TIME_PRESETS.includes(search.timePreset)) {
    result.timePreset = search.timePreset
  }
  
  // Custom time range
  if (typeof search.from === 'string') {
    const date = new Date(search.from)
    if (!Number.isNaN(date.getTime())) {
      result.from = search.from
    }
  }
  if (typeof search.to === 'string') {
    const date = new Date(search.to)
    if (!Number.isNaN(date.getTime())) {
      result.to = search.to
    }
  }
  
  // Viz mode - migrate 'list' to 'timeseries' for backwards compatibility
  if (typeof search.viz === 'string') {
    const vizMode = search.viz === 'list' ? 'timeseries' : search.viz
    if (VALID_VIZ_MODES.includes(vizMode as LogVizMode)) {
      result.viz = vizMode as LogVizMode
    }
  }
  
  // Group by / top field
  if (typeof search.groupBy === 'string') {
    const groupBy = search.groupBy.trim()
    if (VALID_GROUP_BY_VALUES.includes(groupBy)) {
      result.groupBy = groupBy
    }
  }
  if (typeof search.topField === 'string' && search.topField.trim()) {
    result.topField = search.topField.trim()
  }
  
  // Cursor
  if (typeof search.cursor === 'string' && search.cursor) {
    result.cursor = search.cursor
  }
  
  // Selected log ID
  if (typeof search.logId === 'string' && search.logId) {
    result.logId = search.logId
  }
  
  return result
}

/**
 * Serialize log view state to URL search params.
 * Omits default/empty values to keep URLs clean.
 */
export function serializeLogViewState(state: {
  query?: string
  levels?: string[]
  facetFilters?: FacetFilter[]
  timePreset?: string
  customFrom?: string
  customTo?: string
  vizMode?: LogVizMode
  groupBy?: string
  topField?: string
  cursor?: string | null
  selectedLogId?: string | null
}): Partial<LogViewSearch> {
  const result: Partial<LogViewSearch> = {
    q: undefined,
    levels: undefined,
    facets: undefined,
    timePreset: undefined,
    from: undefined,
    to: undefined,
    viz: undefined,
    groupBy: undefined,
    topField: undefined,
    cursor: undefined,
    logId: undefined,
  }
  const clearableSearch = result as Record<string, unknown>
  for (const key of LOG_FACET_URL_KEYS) {
    clearableSearch[key] = undefined
    clearableSearch[`exclude_${key}`] = undefined
  }
  
  // Query
  if (state.query && state.query.trim()) {
    result.q = state.query.trim()
  }
  
  // Levels (only if custom selection)
  if (state.levels && state.levels.length > 0 && state.levels.length < 6) {
    result.levels = state.levels.join(',')
  }
  
  // Facet filters
  if (state.facetFilters && state.facetFilters.length > 0) {
    Object.assign(result, serializeReadableFacetFilters(state.facetFilters, LOG_FACET_URL_KEYS))
  }
  
  // Time range
  if (state.timePreset && state.timePreset !== '15m') {
    result.timePreset = state.timePreset
  }
  if (state.timePreset === 'custom') {
    if (state.customFrom) {
      const date = new Date(state.customFrom)
      if (!Number.isNaN(date.getTime())) {
        result.from = date.toISOString()
      }
    }
    if (state.customTo) {
      const date = new Date(state.customTo)
      if (!Number.isNaN(date.getTime())) {
        result.to = date.toISOString()
      }
    }
  }
  
  // Viz mode (omit default "timeseries")
  if (state.vizMode && state.vizMode !== 'timeseries') {
    result.viz = state.vizMode
  }
  
  // Group by / top field
  if (state.groupBy === '') {
    result.groupBy = NO_LOG_GROUP_BY_URL_VALUE
  } else if (state.groupBy && state.groupBy.trim() && state.groupBy !== DEFAULT_LOG_GROUP_BY) {
    result.groupBy = state.groupBy.trim()
  }
  if (state.topField && state.topField.trim() && state.topField !== 'service') {
    result.topField = state.topField.trim()
  }
  
  // Cursor
  if (state.cursor) {
    result.cursor = state.cursor
  }
  
  // Selected log ID
  if (state.selectedLogId) {
    result.logId = state.selectedLogId
  }
  
  return result
}

export function resolveLogGroupBy(groupBy: string | undefined): string {
  if (groupBy === NO_LOG_GROUP_BY_URL_VALUE) return ''
  if (groupBy && VALID_GROUP_BY_VALUES.includes(groupBy)) return groupBy
  return DEFAULT_LOG_GROUP_BY
}

/**
 * Parse facet filters from a JSON string. Delegates to the shared explorer
 * helper ({@link parseFacetFiltersParam}) so logs and other ExplorerShell
 * surfaces share one facet URL encoding.
 */
export const parseFacetFiltersFromUrl = parseFacetFiltersParam

/**
 * Parse levels from comma-separated string
 */
export function parseLevelsFromUrl(levelsStr: string | undefined): string[] {
  if (!levelsStr) return []
  return levelsStr.split(',').filter(Boolean)
}

/**
 * Flatten the live Explorer context (raw search text plus active facet filters)
 * into a single log query expression, e.g. `error service:api -environment:dev`.
 *
 * Used when handing the current context to log metric / monitor / index /
 * pipeline creation, which accept only a query string rather than structured
 * facets. Include facets render as `key:value`; excluded facets keep the search
 * bar's `-key:value` form so the flattened query matches what the Explorer shows.
 *
 * Saved views are intentionally NOT flattened: they keep facets structured in
 * `state.facets` so they round-trip back into the facet rail.
 */
export function buildLogContextQuery(query: string, facetFilters: FacetFilter[]): string {
  const tokens: string[] = []
  const trimmedQuery = query.trim()
  if (trimmedQuery) tokens.push(trimmedQuery)

  for (const filter of facetFilters) {
    if (!filter.key || !filter.value) continue
    const prefix = filter.exclude ? '-' : ''
    tokens.push(`${prefix}${filter.key}:${filter.value}`)
  }

  return tokens.join(' ')
}
