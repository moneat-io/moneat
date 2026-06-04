import type {Issue, IssueListParams, Project} from '@/lib/api'
import type {FacetFilter, FacetRailSection} from '@/lib/filters/types'

export const HOME_OVERVIEW_ISSUES_QUERY = {
  page: 1,
  limit: 100,
  status: 'unresolved',
} as const satisfies IssueListParams

export const HOME_OVERVIEW_REPLAYS_OPTIONS = {
  limit: 5,
  period: '24h',
} as const

export const HOME_OVERVIEW_FEEDBACK_OPTIONS = {
  limit: 5,
} as const

export function facetValues(filters: readonly FacetFilter[], key: string, exclude: boolean): string[] {
  return filters
    .filter((filter) => filter.key === key && Boolean(filter.exclude) === exclude)
    .map((filter) => filter.value)
}

export function hasAccessibleServices(projects: readonly Project[] | null | undefined): boolean {
  return (projects?.length ?? 0) > 0
}

export function primaryServiceResourceId(projects: readonly Project[] | null | undefined): string | undefined {
  return projects?.[0]?.resourceId
}

export function issueDetailSearch(issue: Pick<Issue, 'projectResourceId'>): {projectId: string} {
  return {projectId: issue.projectResourceId}
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
