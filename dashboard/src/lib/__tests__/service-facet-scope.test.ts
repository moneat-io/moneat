import {describe, expect, it} from 'vitest'
import type {Project} from '@/lib/api'
import type {FacetFilter} from '@/lib/filters/types'
import {
  facetValues,
  serviceNamesForQuery,
  serviceRailSections,
  serviceScopeKey,
} from '../service-facet-scope'

const projects = [
  {
    id: 1,
    resourceId: 'svc-api',
    name: 'API Service',
    slug: 'api-service',
    keys: [],
    dsn: 'https://public@example.com/api/1',
  },
  {
    id: 2,
    resourceId: 'svc-worker',
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
      projects,
      ['Worker Service', 'API Service', 'Worker Service'],
      ['API Service']
    )).toEqual(['Worker Service'])
  })

  it('deduplicates and sorts included services', () => {
    expect(serviceNamesForQuery(
      projects,
      ['Worker Service', 'API Service', 'Worker Service'],
      []
    )).toEqual(['API Service', 'Worker Service'])
  })

  it('uses all projects when only exclusions are selected', () => {
    expect(serviceNamesForQuery(
      projects,
      [],
      ['Worker Service']
    )).toEqual(['API Service'])
  })

  it('returns no query services when no service filters are active', () => {
    expect(serviceNamesForQuery(projects, [], [])).toEqual([])
  })

  it('builds stable scope keys', () => {
    expect(serviceScopeKey([], false)).toBe('all-services')
    expect(serviceScopeKey([], true)).toBe('no-services')
    expect(serviceScopeKey(['API Service', 'Worker|Service'], true)).toBe('["API Service","Worker|Service"]')
  })

  it('builds service rail options from projects', () => {
    expect(serviceRailSections(projects)).toEqual([
      {
        key: 'service',
        label: 'Service',
        color: 'bg-primary',
        options: [{value: 'API Service'}, {value: 'Worker Service'}],
      },
    ])
  })
})
