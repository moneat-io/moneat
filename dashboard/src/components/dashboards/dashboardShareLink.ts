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

import type {TimeRangeDef} from '@/lib/api'

/** Raw deep-link search params carried in a dashboard URL. */
export interface DashboardLinkSearch {
  from?: string
  to?: string
  /** JSON-encoded Record<string, string> of variable selections. */
  vars?: string
}

export interface ParsedDashboardLink {
  timeRange?: TimeRangeDef
  variableValues?: Record<string, string>
}

function timeRangeFromSearch(search: DashboardLinkSearch): TimeRangeDef | undefined {
  return search.from && search.to ? {from: search.from, to: search.to} : undefined
}

function stringRecordFromUnknown(parsed: unknown): Record<string, string> | undefined {
  if (!parsed || typeof parsed !== 'object' || Array.isArray(parsed)) return undefined

  const values: Record<string, string> = {}
  for (const [key, value] of Object.entries(parsed as Record<string, unknown>)) {
    if (typeof value === 'string') values[key] = value
  }
  return Object.keys(values).length > 0 ? values : undefined
}

function variablesFromSearch(vars?: string): Record<string, string> | undefined {
  if (!vars) return undefined
  try {
    return stringRecordFromUnknown(JSON.parse(vars))
  } catch {
    // Malformed link — ignore the variable portion.
    return undefined
  }
}

/** Reads time range + variable selections out of a dashboard deep link. */
export function parseDashboardLink(search: DashboardLinkSearch): ParsedDashboardLink {
  const result: ParsedDashboardLink = {}
  const timeRange = timeRangeFromSearch(search)
  const variableValues = variablesFromSearch(search.vars)
  if (timeRange) result.timeRange = timeRange
  if (variableValues) result.variableValues = variableValues
  return result
}

/**
 * Builds a shareable dashboard URL that restores the current time range and
 * variable selections. `base` is the origin + pathname (no query string).
 */
export function buildDashboardShareUrl(
  base: string,
  timeRange: TimeRangeDef,
  variableValues: Record<string, string>,
): string {
  const params = new URLSearchParams()
  if (timeRange.from) params.set('from', timeRange.from)
  if (timeRange.to) params.set('to', timeRange.to)
  const entries = Object.entries(variableValues).filter(
    ([, value]) => value !== '' && value != null,
  )
  if (entries.length > 0) {
    params.set('vars', JSON.stringify(Object.fromEntries(entries)))
  }
  const query = params.toString()
  return query ? `${base}?${query}` : base
}
