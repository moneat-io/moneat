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

import {createFileRoute, redirect} from '@tanstack/react-router'
import {api} from '@/lib/api'
import {MonitoringTabBar} from '@/components/monitoring/MonitoringTabBar'
import {ResourceCatalog, type ResourceCatalogUrlState} from '@/components/monitoring/catalog/ResourceCatalog'
import {
  clearReadableFacetSearch,
  parseReadableFacetFilters,
  type ReadableFacetSearchValue,
  serializeReadableFacetFilters,
} from '@/lib/filters/urlState'

const RESOURCE_FACET_URL_KEYS = ['kind', 'env', 'health', 'team', 'cloud', 'tag'] as const

type ResourceFacetUrlKey = (typeof RESOURCE_FACET_URL_KEYS)[number]
type ResourceExcludeFacetUrlKey = `exclude_${ResourceFacetUrlKey}`

type ResourcesSearch = {
  q?: string
} & Partial<Record<ResourceFacetUrlKey | ResourceExcludeFacetUrlKey, ReadableFacetSearchValue>>

function parseResourcesSearch(search: Record<string, unknown>): ResourcesSearch {
  const result: ResourcesSearch = {}
  if (typeof search.q === 'string' && search.q.trim()) {
    result.q = search.q.trim()
  }
  const facetFilters = parseReadableFacetFilters(search, RESOURCE_FACET_URL_KEYS)
  if (facetFilters) {
    Object.assign(result, serializeReadableFacetFilters(facetFilters, RESOURCE_FACET_URL_KEYS))
  }
  return result
}

// /resources sits outside the /monitoring layout, so it renders the shared
// Infrastructure tab strip itself to stay in step with Map, Processes, etc.
// The catalog already reserves the bar's 43px in its viewport height calc.
function ResourcesPage() {
  const search = Route.useSearch()
  const navigate = Route.useNavigate()
  const urlState: ResourceCatalogUrlState = {
    query: search.q ?? '',
    facetFilters: parseReadableFacetFilters(search, RESOURCE_FACET_URL_KEYS) ?? [],
  }
  const handleUrlStateChange = (next: ResourceCatalogUrlState) => {
    navigate({
      replace: true,
      search: (prev) => ({
        ...clearReadableFacetSearch(prev, RESOURCE_FACET_URL_KEYS),
        q: next.query.trim() || undefined,
        ...serializeReadableFacetFilters(next.facetFilters, RESOURCE_FACET_URL_KEYS),
      }),
    })
  }

  return (
    <>
      <MonitoringTabBar />
      <ResourceCatalog urlState={urlState} onUrlStateChange={handleUrlStateChange} />
    </>
  )
}

export const Route = createFileRoute('/resources')({
  validateSearch: parseResourcesSearch,
  beforeLoad: async () => {
    if (!api.isAuthenticated()) {
      const hasSession = await api.checkAuth()
      if (!hasSession) throw redirect({to: '/login'})
    }
  },
  component: ResourcesPage,
})
