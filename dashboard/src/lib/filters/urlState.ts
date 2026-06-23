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

/**
 * Surface-agnostic URL serialization for the explorer shell's filter state.
 * Mirrors the log viewer's `?q=&facets=` deep-link encoding (see
 * `@/components/logs/logViewUrlState`) so every ExplorerShell surface — issues,
 * APM errors, … — shares one shareable-link format instead of inventing its
 * own. TanStack Router serializes arrays/objects into URL params, so route
 * search state keeps facets as a real {@link FacetFilter}[] instead of a
 * pre-stringified JSON value.
 */

import {type FacetFilter} from '@/lib/filters/types'

export const LEGACY_FACETS_PARAM = 'facets'
export const EXCLUDE_FACET_PARAM_PREFIX = 'exclude_'
export type ReadableFacetSearchValue = string | string[]

/** The portion of a route's search params that the explorer shell owns. */
export interface ExplorerSearch {
  /** Free-text query. */
  q?: string
  /** Structured facet filters. `[]` means "explicitly no facets". */
  facets?: FacetFilter[]
}

/** Type guard for a single URL-deserialized facet filter. */
export function isFacetFilter(value: unknown): value is FacetFilter {
  if (typeof value !== 'object' || value === null) return false
  const obj = value as Record<string, unknown>
  return (
    typeof obj.key === 'string' &&
    typeof obj.value === 'string' &&
    (obj.exclude === undefined || typeof obj.exclude === 'boolean')
  )
}

/** Drop the redundant `exclude: false` so URLs stay compact and round-trip stably. */
function canonicalFacetFilter(filter: FacetFilter): FacetFilter {
  return filter.exclude
    ? {key: filter.key, value: filter.value, exclude: true}
    : {key: filter.key, value: filter.value}
}

function canonicalFacetFilters(filters: readonly FacetFilter[]): FacetFilter[] {
  return filters.map(canonicalFacetFilter)
}

function searchParamValues(value: unknown): string[] {
  if (Array.isArray(value)) {
    return value.flatMap(searchParamValues)
  }
  if (typeof value !== 'string' && typeof value !== 'number' && typeof value !== 'boolean') return []
  return String(value)
    .split(',')
    .map((part) => part.trim())
    .filter(Boolean)
}

function toSearchParamValue(values: readonly string[]): ReadableFacetSearchValue | undefined {
  if (values.length === 0) return undefined
  return values.length === 1 ? values[0] : [...values]
}

function parseFacetFiltersValue(value: unknown): FacetFilter[] | undefined {
  if (value === undefined || value === null || value === '') return undefined
  if (Array.isArray(value) && value.every(isFacetFilter)) {
    return value
  }
  if (typeof value !== 'string') return undefined

  let parsed: unknown = value
  for (let i = 0; i < 2; i += 1) {
    try {
      parsed = JSON.parse(parsed as string)
    } catch {
      return undefined
    }
    if (Array.isArray(parsed) && parsed.every(isFacetFilter)) {
      return parsed
    }
    if (typeof parsed !== 'string') return undefined
  }
  return undefined
}

/**
 * Parse a `facets` search value into a validated {@link FacetFilter}[].
 * Supports TanStack's structured array value plus legacy JSON-string params,
 * including the accidentally double-stringified shape that produced escaped
 * quotes in shared links.
 */
export function parseFacetFiltersParam(facetsValue: unknown): FacetFilter[] {
  return parseFacetFiltersValue(facetsValue) ?? []
}

/** Parse facet filters when absence/malformed input must stay distinguishable from an explicit empty list. */
export function parseFacetFiltersOptionalParam(facetsValue: unknown): FacetFilter[] | undefined {
  return parseFacetFiltersValue(facetsValue)
}

/**
 * Parse readable facet params such as `service=api&exclude_environment=dev`.
 * Legacy `facets=` JSON remains accepted as input-only compatibility when no
 * readable facet params are present.
 */
export function parseReadableFacetFilters(
  search: Record<string, unknown>,
  facetKeys: readonly string[]
): FacetFilter[] | undefined {
  const filters: FacetFilter[] = []
  let hasReadableFacetParam = false

  for (const key of facetKeys) {
    const includeValues = searchParamValues(search[key])
    if (includeValues.length > 0) {
      hasReadableFacetParam = true
      filters.push(...includeValues.map((value) => ({key, value})))
    }

    const excludeValues = searchParamValues(search[`${EXCLUDE_FACET_PARAM_PREFIX}${key}`])
    if (excludeValues.length > 0) {
      hasReadableFacetParam = true
      filters.push(...excludeValues.map((value) => ({key, value, exclude: true})))
    }
  }

  if (hasReadableFacetParam) {
    return filters
  }

  return parseFacetFiltersOptionalParam(search[LEGACY_FACETS_PARAM])
}

/** Serialize facet filters into readable route params, one param per facet key. */
export function serializeReadableFacetFilters(
  filters: readonly FacetFilter[],
  facetKeys: readonly string[]
): Record<string, ReadableFacetSearchValue> {
  const search: Record<string, ReadableFacetSearchValue> = {}

  for (const key of facetKeys) {
    const includeValues = filters.filter((filter) => filter.key === key && !filter.exclude).map((filter) => filter.value)
    const excludeValues = filters.filter((filter) => filter.key === key && filter.exclude).map((filter) => filter.value)
    const includeParam = toSearchParamValue(includeValues)
    if (includeParam) search[key] = includeParam
    const excludeParam = toSearchParamValue(excludeValues)
    if (excludeParam) search[`${EXCLUDE_FACET_PARAM_PREFIX}${key}`] = excludeParam
  }

  return search
}

/** Clear readable and legacy facet params from an existing route search object. */
export function clearReadableFacetSearch(
  search: Record<string, unknown>,
  facetKeys: readonly string[]
): Record<string, unknown> {
  const next = {...search}
  for (const key of facetKeys) {
    if (key in next) next[key] = undefined
    const excludeKey = `${EXCLUDE_FACET_PARAM_PREFIX}${key}`
    if (excludeKey in next) next[excludeKey] = undefined
  }
  if (LEGACY_FACETS_PARAM in next) next[LEGACY_FACETS_PARAM] = undefined
  return next
}

/** Serialize a {@link FacetFilter}[] into the route search value TanStack will encode once. */
export function serializeFacetFiltersParam(filters: readonly FacetFilter[]): FacetFilter[] {
  return canonicalFacetFilters(filters)
}

/**
 * Validate raw route search into the explorer's `{q, facets}` shape (use from a
 * route's `validateSearch`). `q` is trimmed and dropped when empty; `facets`
 * is kept only when it is a valid facet-filter array (including `[]`),
 * canonicalized for a stable URL. An absent/invalid `facets` stays
 * `undefined`, which lets a surface tell "first visit" from "explicitly
 * cleared".
 */
export function parseExplorerSearch(search: Record<string, unknown>): ExplorerSearch {
  const result: ExplorerSearch = {}

  if (typeof search.q === 'string' && search.q.trim()) {
    result.q = search.q.trim()
  }

  const facets = parseFacetFiltersValue(search.facets)
  if (facets) {
    result.facets = serializeFacetFiltersParam(facets)
  }

  return result
}

/**
 * Serialize live explorer state into `{q, facets}`. `q` is omitted when empty;
 * `facets` is ALWAYS emitted (even `[]`) so a surface with a default facet can
 * distinguish a fresh visit (param absent) from an explicit clear (`"[]"`).
 */
export function serializeExplorerSearch(state: {
  query?: string
  facetFilters?: readonly FacetFilter[]
}): ExplorerSearch {
  const query = state.query?.trim()
  return {
    q: query || undefined,
    facets: serializeFacetFiltersParam(state.facetFilters ?? []),
  }
}
