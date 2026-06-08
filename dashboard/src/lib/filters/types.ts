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
 * Shared filter model — the surface-agnostic core behind every result view
 * (logs, APM errors, issues, …). Extracted from the log viewer, whose query is
 * just a free-text string plus a list of include/exclude facet filters.
 */

/** A single faceted constraint: `key:value`, optionally negated (`-key:value`). */
export interface FacetFilter {
  key: string
  value: string
  exclude?: boolean
}

/** A selectable facet value, optionally with an occurrence count + display label. */
export interface FacetValue {
  value: string
  count?: number
  label?: string
}

/**
 * Declares one facet a surface supports — drives the search bar's typeahead and
 * how a typed `key:value` token is routed. The rail uses {@link FacetRailSection}
 * (it additionally needs counts / lazy value loading).
 */
export interface FacetDef {
  key: string
  label?: string
  /** Chip + dot color (tailwind classes). Falls back to a neutral info style. */
  color?: string
  /** Alternate keys that normalize to `key` (e.g. `env` → `environment`). */
  aliases?: readonly string[]
  /** Only one value at a time — adding a value replaces any existing one. */
  singleSelect?: boolean
  /** Allow exclusion (tri-state in the rail, `-key:value` in the bar). */
  allowExclude?: boolean
  /** Known values for typeahead suggestions (when not lazily loaded). */
  suggestions?: readonly string[]
}

export type FacetSchema = readonly FacetDef[]

/** A rail section: eager `options` (with counts) or a lazy `loadOptions`. */
export interface FacetRailSection {
  key: string
  label: string
  color?: string
  singleSelect?: boolean
  allowExclude?: boolean
  /** Eager values (already loaded, typically with counts). */
  options?: readonly FacetValue[]
  /** Lazy loader invoked the first time the section is expanded. */
  loadOptions?: () => Promise<readonly FacetValue[]>
}

/** The portion of view state common to every surface. */
export interface FilterState {
  query: string
  facetFilters: FacetFilter[]
}

/** Resolve a typed key (possibly an alias) to its canonical facet def. */
export function resolveFacetDef(
  schema: FacetSchema,
  rawKey: string
): FacetDef | undefined {
  const key = rawKey.trim().toLowerCase()
  return schema.find(
    (def) =>
      def.key.toLowerCase() === key ||
      def.aliases?.some((alias) => alias.toLowerCase() === key)
  )
}

/**
 * Add/replace a facet filter, honoring `singleSelect` (replaces existing values
 * for the key) and deduping identical entries. Returns a new array.
 */
export function applyFacetFilter(
  filters: readonly FacetFilter[],
  next: FacetFilter,
  opts?: {singleSelect?: boolean}
): FacetFilter[] {
  const withoutDup = filters.filter(
    (f) =>
      !(
        f.key === next.key &&
        (opts?.singleSelect ? true : f.value === next.value)
      )
  )
  return [...withoutDup, next]
}
