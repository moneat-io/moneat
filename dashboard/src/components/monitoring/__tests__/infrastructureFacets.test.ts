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

import {describe, expect, it} from 'vitest'
import type {DdHostResponse} from '@/lib/api/types/hosts'
import {createInfrastructureMapResources} from '../infrastructureMapModel'
import {
  applyInfrastructureFacetFilters,
  buildInfrastructureFacetSections,
  resolveInfraFacetValue,
} from '../infrastructureFacets'

function makeHost({id, ...overrides}: Partial<DdHostResponse> & {id: number}): DdHostResponse {
  return {
    id,
    hostname: `host-${id}`,
    os: 'linux',
    platform: 'ubuntu',
    processor: 'arm64',
    cpuCores: 8,
    memoryTotalKb: 16_000_000,
    agentVersion: '7.0.0',
    tags: {},
    firstSeenAt: '2026-06-04T15:00:00Z',
    lastSeenAt: '2026-06-04T16:00:00Z',
    isOnline: true,
    ...overrides,
  }
}

function hostResources(hosts: DdHostResponse[]) {
  return createInfrastructureMapResources({resourceKind: 'hosts', hosts, containers: []})
}

describe('infrastructureFacets', () => {
  const hosts = [
    makeHost({id: 1, platform: 'ubuntu', isOnline: true, tags: {service: 'web', region: 'us-east-1a'}}),
    makeHost({id: 2, platform: 'ubuntu', isOnline: false, tags: {service: 'web', region: 'us-east-1b'}}),
    makeHost({id: 3, platform: 'amazon-linux', isOnline: true, tags: {service: 'orders'}}),
  ]

  it('builds sections with counts and drops empty facets', () => {
    const sections = buildInfrastructureFacetSections(hostResources(hosts), 'hosts')
    const byKey = Object.fromEntries(sections.map((section) => [section.key, section]))

    expect(byKey.status.label).toBe('Status')
    expect(byKey.status.options).toEqual([
      {value: 'up', count: 2},
      {value: 'down', count: 1},
    ])
    // Hosts expose Platform (not Image); the type facet counts platforms.
    expect(byKey.platform.label).toBe('Platform')
    expect(byKey.platform.options).toEqual([
      {value: 'ubuntu', count: 2},
      {value: 'amazon-linux', count: 1},
    ])
    expect(byKey['tag:service'].options).toEqual([
      {value: 'web', count: 2},
      {value: 'orders', count: 1},
    ])
    // Only two hosts carry a region tag; the third is simply not counted.
    expect(byKey['tag:region'].options).toHaveLength(2)
    // No image facet for hosts, and no env tag anywhere.
    expect(byKey.image).toBeUndefined()
    expect(byKey['tag:env']).toBeUndefined()
  })

  it('resolves status, dimension, and tag values', () => {
    const [up] = hostResources([hosts[0]])
    expect(resolveInfraFacetValue(up, 'status')).toBe('up')
    expect(resolveInfraFacetValue(up, 'platform')).toBe('ubuntu')
    expect(resolveInfraFacetValue(up, 'tag:service')).toBe('web')
    expect(resolveInfraFacetValue(up, 'tag:missing')).toBeUndefined()
    expect(resolveInfraFacetValue(up, 'image')).toBeUndefined()
  })

  it('includes only matching values (OR within a key)', () => {
    const resources = hostResources(hosts)
    const filtered = applyInfrastructureFacetFilters(resources, [{key: 'tag:service', value: 'web'}])
    expect(filtered.map((r) => r.hostId)).toEqual([1, 2])
  })

  it('excludes values and ANDs across keys', () => {
    const resources = hostResources(hosts)
    const filtered = applyInfrastructureFacetFilters(resources, [
      {key: 'tag:service', value: 'web'},
      {key: 'status', value: 'down', exclude: true},
    ])
    // web hosts (1,2) minus the down one (2) -> just host 1.
    expect(filtered.map((r) => r.hostId)).toEqual([1])
  })

  it('returns the input unchanged when there are no filters', () => {
    const resources = hostResources(hosts)
    expect(applyInfrastructureFacetFilters(resources, [])).toBe(resources)
  })
})
