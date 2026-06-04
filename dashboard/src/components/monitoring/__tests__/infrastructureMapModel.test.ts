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

  it('uses host fallbacks, recency tones, and metric sizing', () => {
    const resources = createInfrastructureMapResources({
      resourceKind: 'hosts',
      hosts: [
        createHost({
          id: 1,
          hostname: '',
          os: '',
          platform: '',
          cpuCores: undefined,
          memoryTotalKb: undefined,
          agentVersion: undefined,
          tags: undefined,
          lastSeenAt: undefined,
        }),
        createHost({
          id: 2,
          hostname: 'old-host',
          cpuCores: 16,
          memoryTotalKb: 16_777_216,
          lastSeenAt: '2026-06-04T12:00:00Z',
        }),
        createHost({
          id: 3,
          hostname: 'fresh-host',
          cpuCores: 4,
          memoryTotalKb: 1_048_576,
          lastSeenAt: '2026-06-04 16:00:00',
        }),
      ],
      containers: [],
      nowMs: NOW_MS,
    })

    const result = buildInfrastructureMapGroups(resources, {
      ...HOST_VIEW,
      groupBy: 'agent',
      fillBy: 'lastSeen',
      sizeBy: 'cpu',
    })

    expect(result.groups.map((group) => group.label)).toEqual(['Unknown agent', 'v7.0.0'])
    expect(getNode(result, 'Host 1')).toMatchObject({
      subtitle: 'host',
      fillTone: 'warning',
      metricLabel: '1h ago',
      sizeLabel: '0 cores',
      sizePercent: 36,
    })
    expect(getNode(result, 'Host 1')?.details).toContainEqual({label: 'Last seen', value: 'never seen'})
    expect(getNode(result, 'old-host')).toMatchObject({
      fillTone: 'danger',
      metricLabel: '4h ago',
      sizePercent: 92,
    })
    expect(getNode(result, 'fresh-host')).toMatchObject({
      fillTone: 'success',
      metricLabel: 'just now',
      sizeLabel: '4 cores',
    })
  })

  it('groups hosts by tags and matches tag search text', () => {
    const resources = createInfrastructureMapResources({
      resourceKind: 'hosts',
      hosts: [
        createHost({
          id: 1,
          hostname: 'api-1',
          tags: {service: 'api', region: 'us-east-1'},
        }),
        createHost({
          id: 2,
          hostname: 'db-1',
          tags: {region: 'eu-west-1'},
        }),
      ],
      containers: [],
      nowMs: NOW_MS,
    })

    const cpuResult = buildInfrastructureMapGroups(resources, {
      ...HOST_VIEW,
      groupBy: 'tag:service',
      fillBy: 'cpu',
      sizeBy: 'memory',
    })
    const memoryResult = buildInfrastructureMapGroups(resources, {
      ...HOST_VIEW,
      groupBy: 'tag:service',
      fillBy: 'memory',
      sizeBy: 'uniform',
    })
    const searchResult = buildInfrastructureMapGroups(resources, {
      ...HOST_VIEW,
      groupBy: 'tag:service',
      searchQuery: 'US-EAST-1',
    })
    const unknownDimensionResult = buildInfrastructureMapGroups(resources, {
      ...HOST_VIEW,
      groupBy: 'image',
    })

    expect(cpuResult.groups.map((group) => group.label)).toEqual(['api', 'No service'])
    expect(getNode(cpuResult, 'api-1')).toMatchObject({
      fillTone: 'info',
      metricLabel: '4 cores',
      sizeLabel: '8.0 GB',
      sizePercent: 92,
    })
    expect(getNode(memoryResult, 'api-1')).toMatchObject({
      fillTone: 'info',
      metricLabel: '8.0 GB',
      sizeLabel: 'uniform',
    })
    expect(searchResult.summary).toMatchObject({
      visibleResources: 1,
      healthyResources: 1,
      groupCount: 1,
    })
    expect(searchResult.groups[0].label).toBe('api')
    expect(unknownDimensionResult.groups.map((group) => group.label)).toEqual(['Unknown'])
  })

  it('applies container fallbacks for stopped and unnamed resources', () => {
    const resources = createInfrastructureMapResources({
      resourceKind: 'containers',
      hosts: [createHost({id: 9, hostname: 'worker-2'})],
      containers: [
        createContainer({
          host: '',
          containerId: '',
          name: '',
          image: '',
          state: 'exited',
          cpuPercent: 0,
          memUsage: 512,
          memLimit: 0,
          netRxBytes: 0,
          netTxBytes: 0,
          tags: {},
          timestamp: 'not-a-date',
        }),
        createContainer({
          host: 'worker-2',
          containerId: 'abcdef1234567890',
          name: '',
          image: 'redis',
          state: 'running',
          cpuPercent: 55,
          memUsage: 1_073_741_824,
          memLimit: 0,
          netRxBytes: 1,
          netTxBytes: 2,
          tags: {env: 'dev'},
          timestamp: '2026-06-04T15:59:00+00:00',
        }),
      ],
      nowMs: NOW_MS,
    })

    const healthResult = buildInfrastructureMapGroups(resources, {
      ...CONTAINER_VIEW,
      groupBy: 'host',
      fillBy: 'health',
      sizeBy: 'network',
    })
    const stoppedResult = buildInfrastructureMapGroups(resources, {
      ...CONTAINER_VIEW,
      groupBy: 'host',
      fillBy: 'health',
      sizeBy: 'network',
      searchQuery: 'exited',
    })
    const memoryResult = buildInfrastructureMapGroups(resources, {
      ...CONTAINER_VIEW,
      groupBy: 'image',
      fillBy: 'memory',
      sizeBy: 'memory',
      searchQuery: 'redis',
    })

    expect(healthResult.groups.map((group) => group.label)).toEqual(['Unknown host', 'worker-2'])
    expect(getNode(healthResult, 'container')).toMatchObject({
      subtitle: 'Unknown image',
      statusLabel: 'stopped',
      fillTone: 'danger',
      metricLabel: 'stopped',
      sizeLabel: '0 B',
    })
    expect(getNode(healthResult, 'container')?.details).toContainEqual({label: 'Memory', value: '512 B'})
    expect(getNode(healthResult, 'abcdef123456')).toMatchObject({
      hostId: 9,
      fillTone: 'success',
      metricLabel: 'running',
      sizeLabel: '3 B',
    })
    expect(getNode(stoppedResult, 'container')).toMatchObject({
      sizePercent: 58,
    })
    expect(getNode(memoryResult, 'abcdef123456')).toMatchObject({
      fillTone: 'neutral',
      metricLabel: '1.0 GB',
      sizeLabel: '1.0 GB',
    })
  })

  it('shows warning tones for medium container utilization and relative network volume', () => {
    const resources = createInfrastructureMapResources({
      resourceKind: 'containers',
      hosts: [],
      containers: [
        createContainer({
          containerId: 'medium',
          name: 'medium-worker',
          memUsage: 300_000_000,
          memLimit: 536_870_912,
          netRxBytes: 60,
          netTxBytes: 0,
        }),
        createContainer({
          containerId: 'busy',
          name: 'busy-worker',
          memUsage: 10_000_000,
          memLimit: 536_870_912,
          netRxBytes: 100,
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

    expect(getNode(memoryResult, 'medium-worker')).toMatchObject({
      fillTone: 'warning',
      metricLabel: '286.1 MB',
    })
    expect(getNode(networkResult, 'medium-worker')).toMatchObject({
      fillTone: 'warning',
      metricLabel: '60 B',
    })
    expect(getNode(networkResult, 'busy-worker')).toMatchObject({
      fillTone: 'danger',
      metricLabel: '100 B',
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
