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

import type {FacetFilter, FacetRailSection, FacetValue} from '@/lib/filters/types'
import type {InfrastructureMapResource, InfrastructureResourceKind} from './infrastructureMapModel'

const TAG_PREFIX = 'tag:'

// One facet section the infrastructure rail can offer. `key` is what a
// FacetFilter carries; `resolve` reads the matching value off a resource so
// counting (sections) and filtering stay in lockstep.
interface InfraFacetDef {
  key: string
  label: string
  color: string
}

const HOST_TYPE_FACET: InfraFacetDef = {key: 'platform', label: 'Platform', color: 'bg-chart-2'}
const CONTAINER_TYPE_FACET: InfraFacetDef = {key: 'image', label: 'Image', color: 'bg-chart-2'}

const SHARED_TAG_FACETS: readonly InfraFacetDef[] = [
  {key: 'tag:service', label: 'tag: service', color: 'bg-chart-4'},
  {key: 'tag:env', label: 'tag: env', color: 'bg-chart-1'},
  {key: 'tag:region', label: 'tag: region', color: 'bg-chart-6'},
]

const STATUS_FACET: InfraFacetDef = {key: 'status', label: 'Status', color: 'bg-success-solid'}

/** Read the value a given facet key resolves to for one resource. */
export function resolveInfraFacetValue(
  resource: InfrastructureMapResource,
  key: string,
): string | undefined {
  if (key === 'status') return resource.statusLabel
  if (key === 'platform') return resource.dimensions.platform
  if (key === 'image') return resource.dimensions.image
  if (key.startsWith(TAG_PREFIX)) return resource.tags[key.slice(TAG_PREFIX.length)]
  return undefined
}

/**
 * Build the rail's facet sections from the (unfiltered) resource set, so each
 * value shows its total occurrence count. Empty sections are dropped — a host
 * fleet with no region tag simply has no "tag: region" facet.
 */
export function buildInfrastructureFacetSections(
  resources: InfrastructureMapResource[],
  kind: InfrastructureResourceKind,
): FacetRailSection[] {
  const typeFacet = kind === 'containers' ? CONTAINER_TYPE_FACET : HOST_TYPE_FACET
  const defs: InfraFacetDef[] = [STATUS_FACET, typeFacet, ...SHARED_TAG_FACETS]

  return defs
    .map((def) => ({
      key: def.key,
      label: def.label,
      color: def.color,
      options: countFacetValues(resources, def.key),
    }))
    .filter((section) => section.options.length > 0)
}

/**
 * Keep only resources that satisfy every active facet: within a key, an include
 * set is OR (any listed value passes) and excludes always reject; across keys
 * the constraints AND together. Mirrors the rail's include/exclude tri-state.
 */
export function applyInfrastructureFacetFilters(
  resources: InfrastructureMapResource[],
  filters: FacetFilter[],
): InfrastructureMapResource[] {
  if (filters.length === 0) return resources

  const byKey = groupFiltersByKey(filters)
  return resources.filter((resource) => {
    for (const [key, group] of byKey) {
      const value = resolveInfraFacetValue(resource, key)
      if (value != null && group.exclude.has(value)) return false
      if (group.include.size > 0 && (value == null || !group.include.has(value))) return false
    }
    return true
  })
}

interface FilterGroup {
  include: Set<string>
  exclude: Set<string>
}

function groupFiltersByKey(filters: FacetFilter[]): Map<string, FilterGroup> {
  const byKey = new Map<string, FilterGroup>()
  for (const filter of filters) {
    const group = byKey.get(filter.key) ?? {include: new Set<string>(), exclude: new Set<string>()}
    if (filter.exclude) group.exclude.add(filter.value)
    else group.include.add(filter.value)
    byKey.set(filter.key, group)
  }
  return byKey
}

function countFacetValues(resources: InfrastructureMapResource[], key: string): FacetValue[] {
  const counts = new Map<string, number>()
  for (const resource of resources) {
    const value = resolveInfraFacetValue(resource, key)
    if (!value) continue
    counts.set(value, (counts.get(value) ?? 0) + 1)
  }
  return Array.from(counts.entries())
    .map(([value, count]) => ({value, count}))
    .sort((a, b) => b.count - a.count || a.value.localeCompare(b.value))
}
