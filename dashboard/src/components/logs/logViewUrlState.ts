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

import {type FacetFilter} from '@/components/logs/LogSearchBar'
import {type LogVizMode} from '@/components/logs/LogVizTabs'

/**
 * URL search params schema for shareable log viewer state.
 * This enables deep linking and context-based navigation.
 */
export interface LogViewSearch {
  // Search & filters
  q?: string // query
  levels?: string // comma-separated levels
  facets?: string // JSON-encoded facet filters
  
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

const VALID_VIZ_MODES: LogVizMode[] = ['list', 'timeseries', 'table', 'toplist', 'pie']
const VALID_TIME_PRESETS = ['5m', '15m', '30m', '1h', '4h', '12h', '24h', '3d', '7d', 'custom']

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
  
  // Facet filters
  if (typeof search.facets === 'string' && search.facets) {
    try {
      const parsed = JSON.parse(search.facets)
      if (Array.isArray(parsed) && parsed.every(isFacetFilter)) {
        result.facets = JSON.stringify(parsed)
      }
    } catch {
      // Invalid JSON, ignore
    }
  }
  
  // Time preset
  if (typeof search.timePreset === 'string' && VALID_TIME_PRESETS.includes(search.timePreset)) {
    result.timePreset = search.timePreset
  }
  
  // Custom time range
  if (typeof search.from === 'string') {
    const date = new Date(search.from)
    if (!Number.isNaN(date.getTime())) {
      result.from = date.toISOString()
    }
  }
  if (typeof search.to === 'string') {
    const date = new Date(search.to)
    if (!Number.isNaN(date.getTime())) {
      result.to = date.toISOString()
    }
  }
  
  // Viz mode
  if (typeof search.viz === 'string' && VALID_VIZ_MODES.includes(search.viz as LogVizMode)) {
    result.viz = search.viz as LogVizMode
  }
  
  // Group by / top field
  if (typeof search.groupBy === 'string' && search.groupBy.trim()) {
    result.groupBy = search.groupBy.trim()
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
  const result: Partial<LogViewSearch> = {}
  
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
    result.facets = JSON.stringify(state.facetFilters)
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
  
  // Viz mode (omit default "list")
  if (state.vizMode && state.vizMode !== 'list') {
    result.viz = state.vizMode
  }
  
  // Group by / top field
  if (state.groupBy && state.groupBy.trim()) {
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

/**
 * Type guard for FacetFilter
 */
function isFacetFilter(value: unknown): value is FacetFilter {
  if (typeof value !== 'object' || value === null) return false
  const obj = value as Record<string, unknown>
  return (
    typeof obj.key === 'string' &&
    typeof obj.value === 'string' &&
    (obj.exclude === undefined || typeof obj.exclude === 'boolean')
  )
}

/**
 * Parse facet filters from JSON string
 */
export function parseFacetFiltersFromUrl(facetsJson: string | undefined): FacetFilter[] {
  if (!facetsJson) return []
  try {
    const parsed = JSON.parse(facetsJson)
    if (Array.isArray(parsed) && parsed.every(isFacetFilter)) {
      return parsed
    }
  } catch {
    // Invalid JSON
  }
  return []
}

/**
 * Parse levels from comma-separated string
 */
export function parseLevelsFromUrl(levelsStr: string | undefined): string[] {
  if (!levelsStr) return []
  return levelsStr.split(',').filter(Boolean)
}
