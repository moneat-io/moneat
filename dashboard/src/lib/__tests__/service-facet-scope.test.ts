import {describe, expect, it} from 'vitest'
import type {Project} from '@/lib/api'
import type {FacetFilter} from '@/lib/filters/types'
import {
  facetValues,
  hasAccessibleServices,
  HOME_OVERVIEW_FEEDBACK_OPTIONS,
  HOME_OVERVIEW_ISSUES_QUERY,
  HOME_OVERVIEW_REPLAYS_OPTIONS,
  issueDetailSearch,
  primaryServiceResourceId,
  serviceNamesForQuery,
  serviceRailSections,
  serviceScopeKey,
} from '../service-facet-scope'

const services = [
  {
    id: 'svc-api',
    name: 'API Service',
    slug: 'api-service',
    keys: [],
    dsn: 'https://public@example.com/api/1',
  },
  {
    id: 'svc-worker',
    name: 'Worker Service',
    slug: 'worker-service',
    keys: [],
    dsn: 'https://worker@example.com/api/2',
  },
] satisfies Project[]

describe('service facet scope', () => {
  it('splits included and excluded facet values', () => {
    const filters = [
      {key: 'service', value: 'API Service'},
      {key: 'service', value: 'Worker Service', exclude: true},
      {key: 'status', value: 'success'},
    ] satisfies FacetFilter[]

    expect(facetValues(filters, 'service', false)).toEqual(['API Service'])
    expect(facetValues(filters, 'service', true)).toEqual(['Worker Service'])
  })

  it('uses explicit included services minus exclusions', () => {
    expect(serviceNamesForQuery(
      services,
      ['Worker Service', 'API Service', 'Worker Service'],
      ['API Service']
    )).toEqual(['Worker Service'])
  })

  it('deduplicates and sorts included services', () => {
    expect(serviceNamesForQuery(
      services,
      ['Worker Service', 'API Service', 'Worker Service'],
      []
    )).toEqual(['API Service', 'Worker Service'])
  })

  it('uses all services when only exclusions are selected', () => {
    expect(serviceNamesForQuery(
      services,
      [],
      ['Worker Service']
    )).toEqual(['API Service'])
  })

  it('returns no query services when no service filters are active', () => {
    expect(serviceNamesForQuery(services, [], [])).toEqual([])
  })

  it('builds stable scope keys', () => {
    expect(serviceScopeKey([], false)).toBe('all-services')
    expect(serviceScopeKey([], true)).toBe('no-services')
    expect(serviceScopeKey(['API Service', 'Worker|Service'], true)).toBe('["API Service","Worker|Service"]')
  })

  it('builds service rail options from services', () => {
    expect(serviceRailSections(services)).toEqual([
      {
        key: 'service',
        label: 'Service',
        color: 'bg-primary',
        options: [{value: 'API Service'}, {value: 'Worker Service'}],
      },
    ])
  })

  it('resolves the first accessible service for legacy home queries', () => {
    expect(primaryServiceResourceId(services)).toBe('svc-api')
    expect(hasAccessibleServices(services)).toBe(true)
    expect(primaryServiceResourceId([])).toBeUndefined()
    expect(hasAccessibleServices([])).toBe(false)
  })

  it('defines organization-wide home overview query options', () => {
    expect(HOME_OVERVIEW_ISSUES_QUERY).toEqual({
      page: 1,
      limit: 100,
      status: 'unresolved',
    })
    expect(HOME_OVERVIEW_REPLAYS_OPTIONS).toEqual({limit: 5, period: '24h'})
    expect(HOME_OVERVIEW_FEEDBACK_OPTIONS).toEqual({limit: 5})
  })

  it('links organization issues back through their owning service', () => {
    expect(issueDetailSearch({projectId: 'svc-worker'})).toEqual({projectId: 'svc-worker'})
  })
})
