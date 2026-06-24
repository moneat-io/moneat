// Moneat - observability platform
// Copyright (C) 2026 Moneat
//
// This program is free software: you can redistribute it and/or modify
// it under the terms of the GNU Affero General Public License as published by
// the Free Software Foundation, either version 3 of the License, or
// (at your option) any later version.

import {createFileRoute} from '@tanstack/react-router'
import {useQuery} from '@tanstack/react-query'
import {useMemo, useState, type ReactNode} from 'react'
import {Flame} from 'lucide-react'
import {ProfileServiceList} from '@/components/profiling/ProfileServiceList'
import {ProfileList} from '@/components/profiling/ProfileList'
import {ExplorerShell} from '@/components/filters/ExplorerShell'
import {FacetRail} from '@/components/filters/FacetRail'
import {SearchFilterBar} from '@/components/filters/SearchFilterBar'
import {api, type ProfileServiceSummary} from '@/lib/api'
import type {FacetFilter, FacetRailSection, FacetSchema} from '@/lib/filters/types'
import {useReadableFacetUrlState} from '@/lib/filters/useReadableFacetUrlState'
import {cn} from '@/lib/utils'

export const Route = createFileRoute('/profiles/')({
  component: ProfilesIndexPage,
})

type ProfilesView = 'services' | 'all'
const PROFILE_FACET_URL_KEYS = ['service', 'type'] as const

const PROFILE_FACET_SCHEMA = [
  {
    key: 'service',
    label: 'Service',
    aliases: ['svc'],
    color: 'bg-chart-1',
    allowExclude: false,
  },
  {
    key: 'type',
    label: 'Profile Type',
    aliases: ['profileType', 'profile_type'],
    color: 'bg-chart-2',
    singleSelect: true,
    allowExclude: false,
  },
] satisfies FacetSchema

function ProfilesIndexPage() {
  const [view, setView] = useState<ProfilesView>('services')
  const {query, setQuery, facetFilters, setFacetFilters} = useReadableFacetUrlState({
    facetKeys: PROFILE_FACET_URL_KEYS,
  })

  const {data: servicesData} = useQuery({
    queryKey: ['profileServices'],
    queryFn: () => api.getProfileServices(),
    enabled: api.isAuthenticated(),
  })
  const serviceSummaries = useMemo(() => servicesData?.services ?? [], [servicesData?.services])
  const railSections = useMemo(
    () => buildProfileFacetSections(serviceSummaries),
    [serviceSummaries],
  )
  const selectedServices = useMemo(
    () => facetValues(facetFilters, 'service'),
    [facetFilters],
  )
  const selectedType = firstFacetValue(facetFilters, 'type') ?? ''

  return (
    <ExplorerShell
      className="min-h-[calc(100vh-4rem)]"
      icon={<Flame className="h-4 w-4 text-muted-foreground" />}
      title="Profiles"
      searchBar={(
        <SearchFilterBar
          query={query}
          onQueryChange={setQuery}
          facetFilters={facetFilters}
          onFacetFiltersChange={setFacetFilters}
          schema={PROFILE_FACET_SCHEMA}
          placeholder="Search services, hosts, environments, or profile types"
        />
      )}
      actions={(
        <div className="inline-flex shrink-0 rounded-md border p-0.5 text-xs">
          <ViewToggle active={view === 'services'} onClick={() => setView('services')}>
            Services
          </ViewToggle>
          <ViewToggle active={view === 'all'} onClick={() => setView('all')}>
            All profiles
          </ViewToggle>
        </div>
      )}
      rail={(
        <FacetRail
          sections={railSections}
          facetFilters={facetFilters}
          onFacetFiltersChange={setFacetFilters}
          title="Profile facets"
        />
      )}
      toolbar={(
        <span className="text-xs text-muted-foreground">
          {(servicesData?.totalProfiles ?? 0).toLocaleString()} profiles
        </span>
      )}
    >
      <div className="min-w-0 p-3">
        {view === 'services' ? (
          <ProfileServiceList
            serviceFilters={selectedServices}
            typeFilter={selectedType}
            query={query}
          />
        ) : (
          <ProfileList
            serviceFilters={selectedServices}
            typeFilter={selectedType}
            query={query}
          />
        )}
      </div>
    </ExplorerShell>
  )
}

function ViewToggle({
  active,
  onClick,
  children,
}: {
  readonly active: boolean
  readonly onClick: () => void
  readonly children: ReactNode
}) {
  return (
    <button
      type="button"
      onClick={onClick}
      className={cn(
        'px-2.5 py-1 rounded font-medium transition-colors',
        active
          ? 'bg-secondary text-secondary-foreground'
          : 'text-muted-foreground hover:text-foreground',
      )}
    >
      {children}
    </button>
  )
}

function buildProfileFacetSections(services: readonly ProfileServiceSummary[]): FacetRailSection[] {
  const profileTypeCounts = new Map<string, number>()
  for (const service of services) {
    for (const type of service.types) {
      profileTypeCounts.set(type.profileType, (profileTypeCounts.get(type.profileType) ?? 0) + type.count)
    }
  }

  return [
    {
      key: 'service',
      label: 'Service',
      color: 'bg-chart-1',
      allowExclude: false,
      options: services.map((service) => ({
        value: service.service,
        count: service.profileCount,
      })),
    },
    {
      key: 'type',
      label: 'Profile Type',
      color: 'bg-chart-2',
      singleSelect: true,
      allowExclude: false,
      options: Array.from(profileTypeCounts.entries())
        .sort(([, leftCount], [, rightCount]) => rightCount - leftCount)
        .map(([value, count]) => ({value, count})),
    },
  ]
}

function facetValues(filters: readonly FacetFilter[], key: string): string[] {
  return filters
    .filter((filter) => filter.key === key && !filter.exclude)
    .map((filter) => filter.value)
}

function firstFacetValue(filters: readonly FacetFilter[], key: string): string | undefined {
  return facetValues(filters, key)[0]
}
