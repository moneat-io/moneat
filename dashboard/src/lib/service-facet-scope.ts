import type {Project} from '@/lib/api'
import type {FacetFilter, FacetRailSection} from '@/lib/filters/types'

export function facetValues(filters: readonly FacetFilter[], key: string, exclude: boolean): string[] {
  return filters
    .filter((filter) => filter.key === key && Boolean(filter.exclude) === exclude)
    .map((filter) => filter.value)
}

export function serviceNamesForQuery(
  projects: readonly Project[],
  includedServices: readonly string[],
  excludedServices: readonly string[]
): string[] {
  if (includedServices.length === 0 && excludedServices.length === 0) return []

  const excluded = new Set(excludedServices)
  const candidates = includedServices.length > 0
    ? includedServices
    : projects.map((project) => project.name)

  return [...new Set(candidates)]
    .filter((service) => !excluded.has(service))
    .sort((left, right) => left.localeCompare(right))
}

export function serviceScopeKey(serviceNames: readonly string[], hasServiceFilters: boolean): string {
  if (!hasServiceFilters) return 'all-services'
  if (serviceNames.length === 0) return 'no-services'
  return JSON.stringify(serviceNames)
}

export function serviceRailSections(projects: readonly Project[]): FacetRailSection[] {
  return [
    {
      key: 'service',
      label: 'Service',
      color: 'bg-primary',
      options: projects.map((project) => ({value: project.name})),
    },
  ]
}
