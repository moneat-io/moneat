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
import type {DdContainerResponse} from '@/lib/api/types/infrastructure'
import {
  buildInfrastructureMapGroups,
  createInfrastructureMapResources,
  type InfrastructureMapViewState,
} from '../infrastructureMapModel'

const NOW_MS = Date.parse('2026-06-04T16:00:00Z')
const HOST_VIEW: InfrastructureMapViewState = {
  resourceKind: 'hosts',
  groupBy: 'status',
  fillBy: 'health',
  sizeBy: 'memory',
  searchQuery: '',
}
const CONTAINER_VIEW: InfrastructureMapViewState = {
  resourceKind: 'containers',
  groupBy: 'host',
  fillBy: 'cpu',
  sizeBy: 'cpu',
  searchQuery: '',
}

describe('infrastructure map model', () => {
  it('groups hosts by status and ranks unhealthy hosts first', () => {
    const resources = createInfrastructureMapResources({
      resourceKind: 'hosts',
      hosts: [
        createHost({id: 1, hostname: 'web-1', isOnline: true}),
        createHost({id: 2, hostname: 'db-1', isOnline: false}),
      ],
      containers: [],
      nowMs: NOW_MS,
    })

    const result = buildInfrastructureMapGroups(resources, HOST_VIEW)

    expect(result.summary).toMatchObject({
      totalResources: 2,
      visibleResources: 2,
      healthyResources: 1,
      groupCount: 2,
    })
    expect(result.groups.map((group) => group.label)).toEqual(['down', 'up'])
    expect(result.groups[0].nodes[0]).toMatchObject({
      label: 'db-1',
      fillTone: 'danger',
      hostId: 2,
    })
  })

  it('derives summary health from the searched resource set', () => {
    const resources = createInfrastructureMapResources({
      resourceKind: 'hosts',
      hosts: [
        createHost({id: 1, hostname: 'web-1', isOnline: true}),
        createHost({id: 2, hostname: 'db-1', isOnline: false}),
      ],
      containers: [],
      nowMs: NOW_MS,
    })

    const result = buildInfrastructureMapGroups(resources, {
      ...HOST_VIEW,
      searchQuery: 'db-1',
    })

    expect(result.summary).toMatchObject({
      totalResources: 2,
      visibleResources: 1,
      healthyResources: 0,
      groupCount: 1,
    })
  })

  it('deduplicates containers by host and container id using the newest payload', () => {
    const resources = createInfrastructureMapResources({
      resourceKind: 'containers',
      hosts: [createHost({id: 7, hostname: 'worker-1'})],
      containers: [
        createContainer({
          containerId: 'abc123',
          host: 'worker-1',
          name: 'api',
          cpuPercent: 92,
          timestamp: '2026-06-04T15:30:00Z',
        }),
        createContainer({
          containerId: 'abc123',
          host: 'worker-1',
          name: 'api',
          cpuPercent: 14.5,
          timestamp: '2026-06-04T15:59:00Z',
        }),
      ],
      nowMs: NOW_MS,
    })

    const result = buildInfrastructureMapGroups(resources, CONTAINER_VIEW)

    expect(result.summary.visibleResources).toBe(1)
    expect(result.groups).toHaveLength(1)
    expect(result.groups[0].label).toBe('worker-1')
    expect(result.groups[0].nodes[0]).toMatchObject({
      label: 'api',
      hostId: 7,
      metricLabel: '14.5% CPU',
    })
  })

  it('colors container memory by utilization and network by visible volume', () => {
    const resources = createInfrastructureMapResources({
      resourceKind: 'containers',
      hosts: [createHost({id: 7, hostname: 'worker-1'})],
      containers: [
        createContainer({
          containerId: 'abc123',
          name: 'api',
          memUsage: 134_217_728,
          memLimit: 536_870_912,
          netRxBytes: 4_096,
          netTxBytes: 4_096,
        }),
        createContainer({
          containerId: 'def456',
          name: 'worker',
          memUsage: 500_000_000,
          memLimit: 536_870_912,
          netRxBytes: 0,
          netTxBytes: 0,
        }),
      ],
      nowMs: NOW_MS,
    })

    const memoryResult = buildInfrastructureMapGroups(resources, {
      ...CONTAINER_VIEW,
      fillBy: 'memory',
      sizeBy: 'uniform',
    })
    const networkResult = buildInfrastructureMapGroups(resources, {
      ...CONTAINER_VIEW,
      fillBy: 'network',
      sizeBy: 'uniform',
    })

    expect(getNode(memoryResult, 'api')).toMatchObject({
      fillTone: 'success',
      metricLabel: '128.0 MB',
    })
    expect(getNode(memoryResult, 'worker')).toMatchObject({
      fillTone: 'danger',
      metricLabel: '476.8 MB',
    })
    expect(getNode(networkResult, 'api')).toMatchObject({
      fillTone: 'danger',
      metricLabel: '8.0 KB',
    })
    expect(getNode(networkResult, 'worker')).toMatchObject({
      fillTone: 'neutral',
      metricLabel: '0 B',
    })
  })

})

function getNode(
  result: ReturnType<typeof buildInfrastructureMapGroups>,
  label: string,
) {
  return result.groups.flatMap((group) => group.nodes).find((node) => node.label === label)
}

function createHost(overrides: Partial<DdHostResponse> = {}): DdHostResponse {
  return {
    id: 1,
    hostname: 'web-1',
    os: 'Ubuntu',
    platform: 'linux',
    processor: 'arm64',
    cpuCores: 4,
    memoryTotalKb: 8_388_608,
    agentVersion: '7.0.0',
    tags: {env: 'prod'},
    firstSeenAt: '2026-06-04T14:00:00Z',
    lastSeenAt: '2026-06-04T15:55:00Z',
    isOnline: true,
    ...overrides,
  }
}

function createContainer(overrides: Partial<DdContainerResponse> = {}): DdContainerResponse {
  return {
    id: 'row-1',
    host: 'worker-1',
    containerId: 'abc123',
    name: 'api',
    image: 'registry.example.com/api:latest',
    state: 'running',
    cpuPercent: 12.5,
    memUsage: 134_217_728,
    memLimit: 536_870_912,
    netRxBytes: 1_024,
    netTxBytes: 2_048,
    tags: {service: 'api'},
    timestamp: '2026-06-04T15:59:00Z',
    ...overrides,
  }
}
