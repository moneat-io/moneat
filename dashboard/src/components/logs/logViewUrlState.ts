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
 * Parse facet filters from a JSON string. Delegates to the shared explorer
 * helper so logs and other ExplorerShell surfaces share one facet URL encoding.
 */
export {parseFacetFiltersParam as parseFacetFiltersFromUrl} from '@/lib/filters/urlState'

function nonEmptyString(value: unknown): string | undefined {
  return typeof value === 'string' && value ? value : undefined
}

function trimmedString(value: unknown): string | undefined {
  if (typeof value !== 'string') return undefined
  const trimmed = value.trim()
  return trimmed || undefined
}

function parseLevelsSearchValue(value: unknown): string | undefined {
  if (typeof value !== 'string' || !value) return undefined
  const parsed = value.split(',').filter(Boolean)
  return parsed.length > 0 ? parsed.join(',') : undefined
}

function parseDateSearchValue(value: unknown): string | undefined {
  if (typeof value !== 'string') return undefined
  const date = new Date(value)
  return Number.isNaN(date.getTime()) ? undefined : value
}

function parseVizSearchValue(value: unknown): LogVizMode | undefined {
  if (typeof value !== 'string') return undefined
  const vizMode = value === 'list' ? 'timeseries' : value
  return VALID_VIZ_MODES.includes(vizMode as LogVizMode) ? (vizMode as LogVizMode) : undefined
}

function parseGroupBySearchValue(value: unknown): string | undefined {
  const groupBy = trimmedString(value)
  return groupBy && VALID_GROUP_BY_VALUES.includes(groupBy) ? groupBy : undefined
}

function createEmptyLogViewSearch(): Partial<LogViewSearch> {
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
  return result
}

function serializeLevelsValue(levels: string[] | undefined): string | undefined {
  return levels && levels.length > 0 && levels.length < 6 ? levels.join(',') : undefined
}

function serializeDateValue(value: string | undefined): string | undefined {
  if (!value) return undefined
  const date = new Date(value)
  return Number.isNaN(date.getTime()) ? undefined : date.toISOString()
}

function serializeTimeRangeSearch(state: {
  timePreset?: string
  customFrom?: string
  customTo?: string
}): Partial<LogViewSearch> {
  const result: Partial<LogViewSearch> = {}
  if (state.timePreset && state.timePreset !== '15m') {
    result.timePreset = state.timePreset
  }
  if (state.timePreset !== 'custom') return result

  const from = serializeDateValue(state.customFrom)
  const to = serializeDateValue(state.customTo)
  if (from) result.from = from
  if (to) result.to = to
  return result
}

function serializeGroupByValue(groupBy: string | undefined): string | undefined {
  if (groupBy === '') return NO_LOG_GROUP_BY_URL_VALUE
  const trimmedGroupBy = groupBy?.trim()
  return trimmedGroupBy && trimmedGroupBy !== DEFAULT_LOG_GROUP_BY ? trimmedGroupBy : undefined
}

/**
 * Parse URL search params into normalized log view state.
 * Defensively handles malformed or invalid values.
 */
export function parseLogViewSearch(search: Record<string, unknown>): LogViewSearch {
  const result: LogViewSearch = {}

  const query = trimmedString(search.q)
  if (query) result.q = query

  const levels = parseLevelsSearchValue(search.levels)
  if (levels) result.levels = levels

  const facetFilters = parseLogViewFacetFilters(search)
  if (facetFilters) {
    Object.assign(result, serializeReadableFacetFilters(facetFilters, LOG_FACET_URL_KEYS))
  }

  if (typeof search.timePreset === 'string' && VALID_TIME_PRESETS.includes(search.timePreset)) {
    result.timePreset = search.timePreset
  }

  const from = parseDateSearchValue(search.from)
  const to = parseDateSearchValue(search.to)
  if (from) result.from = from
  if (to) result.to = to

  const viz = parseVizSearchValue(search.viz)
  const groupBy = parseGroupBySearchValue(search.groupBy)
  const topField = trimmedString(search.topField)
  if (viz) result.viz = viz
  if (groupBy) result.groupBy = groupBy
  if (topField) result.topField = topField

  const cursor = nonEmptyString(search.cursor)
  const logId = nonEmptyString(search.logId)
  if (cursor) result.cursor = cursor
  if (logId) result.logId = logId

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
  const result = createEmptyLogViewSearch()

  const query = trimmedString(state.query)
  const levels = serializeLevelsValue(state.levels)
  const groupBy = serializeGroupByValue(state.groupBy)
  const topField = trimmedString(state.topField)
  if (query) result.q = query
  if (levels) result.levels = levels

  if (state.facetFilters?.length) {
    Object.assign(result, serializeReadableFacetFilters(state.facetFilters, LOG_FACET_URL_KEYS))
  }

  Object.assign(result, serializeTimeRangeSearch(state))

  if (state.vizMode && state.vizMode !== 'timeseries') {
    result.viz = state.vizMode
  }
  if (groupBy) result.groupBy = groupBy
  if (topField && topField !== 'service') result.topField = topField

  if (state.cursor) {
    result.cursor = state.cursor
  }
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
