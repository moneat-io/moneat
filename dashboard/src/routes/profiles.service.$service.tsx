// Moneat - observability platform
// Copyright (C) 2026 Moneat
//
// This program is free software: you can redistribute it and/or modify
// it under the terms of the GNU Affero General Public License as published by
// the Free Software Foundation, either version 3 of the License, or
// (at your option) any later version.

import {createFileRoute} from '@tanstack/react-router'
import {useQuery} from '@tanstack/react-query'
import {useCallback, useMemo, useState} from 'react'
import {Flame} from 'lucide-react'
import {ExplorerShell} from '@/components/filters/ExplorerShell'
import {FacetRail} from '@/components/filters/FacetRail'
import {SearchFilterBar} from '@/components/filters/SearchFilterBar'
import {TimeRangePicker} from '@/components/filters/TimeRangePicker'
import {ServiceExplorer} from '@/components/profiling/ServiceExplorer'
import {api, type ProfileServiceSummary} from '@/lib/api'
import type {FacetFilter, FacetRailSection, FacetSchema} from '@/lib/filters/types'

export const Route = createFileRoute('/profiles/service/$service')({
  component: ServiceExplorerPage,
})

type ProfileRangeKey = '1h' | '6h' | '24h' | '7d'

const DEFAULT_PROFILE_RANGE: ProfileRangeKey = '24h'
const PROFILE_TIME_PRESETS: Array<{
  label: string
  value: ProfileRangeKey
  minutes: number
}> = [
  {label: '1h', value: '1h', minutes: 60},
  {label: '6h', value: '6h', minutes: 360},
  {label: '24h', value: '24h', minutes: 1440},
  {label: '7d', value: '7d', minutes: 10080},
]
const PROFILE_RANGE_VALUES = new Set<string>(PROFILE_TIME_PRESETS.map((preset) => preset.value))

function ServiceExplorerPage() {
  const {service: routeService} = Route.useParams()
  return <SeededServiceExplorerPage key={routeService} routeService={routeService} />
}

function SeededServiceExplorerPage({routeService}: Readonly<{routeService: string}>) {
  const [query, setQuery] = useState('')
  const [facetFilters, setFacetFilters] = useState<FacetFilter[]>(() => [
    serviceFacet(routeService),
  ])
  const [timeRange, setTimeRange] = useState<ProfileRangeKey>(DEFAULT_PROFILE_RANGE)
  const handleFacetFiltersChange = useCallback(
    (filters: FacetFilter[]) => {
      setFacetFilters(lockServiceFacet(filters, routeService))
    },
    [routeService],
  )

  const {data: servicesData} = useQuery({
    queryKey: ['profileServices'],
    queryFn: () => api.getProfileServices(),
    enabled: api.isAuthenticated(),
  })
  const serviceSummaries = useMemo(() => servicesData?.services ?? [], [servicesData?.services])
  const selectedService = routeService
  const selectedType = firstFacetValue(facetFilters, 'type')
  const selectedEnv = firstFacetValue(facetFilters, 'env')
  const schema = useMemo(
    () => buildProfileServiceFacetSchema(serviceSummaries, selectedService),
    [serviceSummaries, selectedService],
  )
  const railSections = useMemo(
    () => buildProfileServiceFacetSections(serviceSummaries, selectedService),
    [serviceSummaries, selectedService],
  )
  const filters = useMemo(
    () => ({
      service: selectedService,
      env: selectedEnv,
      type: selectedType,
      rangeKey: timeRange,
    }),
    [selectedService, selectedEnv, selectedType, timeRange],
  )

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
          onFacetFiltersChange={handleFacetFiltersChange}
          schema={schema}
          placeholder="Filter by service, environment, or profile type"
          trailing={(
            <TimeRangePicker
              timePreset={timeRange}
              onTimePresetChange={(value) => setTimeRange(toProfileRangeKey(value))}
              customFrom=""
              customTo=""
              onCustomFromChange={() => {}}
              onCustomToChange={() => {}}
              presets={PROFILE_TIME_PRESETS}
              allowCustom={false}
            />
          )}
        />
      )}
      rail={(
        <FacetRail
          sections={railSections}
          facetFilters={facetFilters}
          onFacetFiltersChange={handleFacetFiltersChange}
          title="Profile facets"
        />
      )}
      toolbar={(
        <span className="text-xs text-muted-foreground">
          {selectedService ?? 'All services'}
        </span>
      )}
    >
      <ServiceExplorer filters={filters} />
    </ExplorerShell>
  )
}

function serviceFacet(service: string): FacetFilter {
  return {key: 'service', value: service, exclude: false}
}

function lockServiceFacet(filters: readonly FacetFilter[], routeService: string): FacetFilter[] {
  return [
    serviceFacet(routeService),
    ...filters.filter((filter) => filter.key !== 'service'),
  ]
}

function buildProfileServiceFacetSchema(
  services: readonly ProfileServiceSummary[],
  selectedService: string | undefined,
): FacetSchema {
  const relevantServices = relevantProfileServices(services, selectedService)
  return [
    {
      key: 'service',
      label: 'Service',
      aliases: ['svc'],
      color: 'bg-chart-1',
      singleSelect: true,
      allowExclude: false,
      suggestions: uniqueSorted(services.map((service) => service.service)),
    },
    {
      key: 'env',
      label: 'Environment',
      aliases: ['environment'],
      color: 'bg-chart-3',
      singleSelect: true,
      allowExclude: false,
      suggestions: uniqueSorted(relevantServices.flatMap((service) => service.environments)),
    },
    {
      key: 'type',
      label: 'Profile Type',
      aliases: ['profileType', 'profile_type'],
      color: 'bg-chart-2',
      singleSelect: true,
      allowExclude: false,
      suggestions: profileTypeValues(relevantServices),
    },
  ]
}

function buildProfileServiceFacetSections(
  services: readonly ProfileServiceSummary[],
  selectedService: string | undefined,
): FacetRailSection[] {
  const relevantServices = relevantProfileServices(services, selectedService)
  const profileTypeCounts = new Map<string, number>()
  for (const service of relevantServices) {
    for (const type of service.types) {
      profileTypeCounts.set(type.profileType, (profileTypeCounts.get(type.profileType) ?? 0) + type.count)
    }
  }

  return [
    {
      key: 'service',
      label: 'Service',
      color: 'bg-chart-1',
      singleSelect: true,
      allowExclude: false,
      options: services.map((service) => ({
        value: service.service,
        count: service.profileCount,
      })),
    },
    {
      key: 'env',
      label: 'Environment',
      color: 'bg-chart-3',
      singleSelect: true,
      allowExclude: false,
      options: uniqueSorted(relevantServices.flatMap((service) => service.environments))
        .map((value) => ({value})),
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

function relevantProfileServices(
  services: readonly ProfileServiceSummary[],
  selectedService: string | undefined,
): ProfileServiceSummary[] {
  return selectedService
    ? services.filter((service) => service.service === selectedService)
    : [...services]
}

function profileTypeValues(services: readonly ProfileServiceSummary[]): string[] {
  return uniqueSorted(services.flatMap((service) => service.types.map((type) => type.profileType)))
}

function uniqueSorted(values: readonly string[]): string[] {
  return Array.from(new Set(values.filter((value) => value.trim() !== ''))).sort((a, b) => a.localeCompare(b))
}

function firstFacetValue(filters: readonly FacetFilter[], key: string): string | undefined {
  return filters.find((filter) => filter.key === key && !filter.exclude)?.value
}

function toProfileRangeKey(value: string): ProfileRangeKey {
  return PROFILE_RANGE_VALUES.has(value) ? (value as ProfileRangeKey) : DEFAULT_PROFILE_RANGE
}
