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

import type {DdHostResponse} from '@/lib/api/types/hosts'
import type {
  DdContainerResponse,
  InfrastructureFillBy,
  InfrastructureGroupBy,
  InfrastructureMapViewState,
  InfrastructureResourceKind,
  InfrastructureSizeBy,
} from '@/lib/api/types/infrastructure'

export type {
  InfrastructureFillBy,
  InfrastructureGroupBy,
  InfrastructureMapViewState,
  InfrastructureResourceKind,
  InfrastructureSizeBy,
  SavedInfrastructureMapView,
} from '@/lib/api/types/infrastructure'

const BYTES_PER_KILOBYTE = 1024
const BYTES_PER_MEGABYTE = BYTES_PER_KILOBYTE * 1024
const BYTES_PER_GIGABYTE = BYTES_PER_MEGABYTE * 1024
const MILLIS_PER_MINUTE = 60_000
const MINUTES_PER_HOUR = 60
const MINUTES_PER_DAY = 1_440
const RECENT_SEEN_MINUTES = 15
const STALE_SEEN_MINUTES = 60
const HIGH_UTILIZATION_PERCENT = 80
const MEDIUM_UTILIZATION_PERCENT = 50
const MIN_TILE_PERCENT = 36
const MAX_TILE_PERCENT = 92
const DEFAULT_TILE_PERCENT = 58
const TAG_GROUP_PREFIX = 'tag:'

export type InfrastructureMapTone = 'success' | 'danger' | 'warning' | 'info' | 'neutral'
type InfrastructureMetricKey =
  Exclude<InfrastructureFillBy, 'health'> | Exclude<InfrastructureSizeBy, 'uniform'>
type InfrastructureToneMetricKey = Exclude<InfrastructureFillBy, 'health' | 'lastSeen'>

export interface InfrastructureMapResource {
  id: string
  resourceKind: InfrastructureResourceKind
  label: string
  subtitle: string
  statusLabel: string
  isHealthy: boolean
  hostId?: number
  tags: Record<string, string>
  dimensions: Partial<Record<InfrastructureGroupBy, string>>
  metrics: Record<InfrastructureMetricKey, number>
  toneMetrics: Partial<Record<InfrastructureToneMetricKey, number>>
  details: Array<{label: string; value: string}>
  searchText: string
}

export interface InfrastructureMapNode {
  id: string
  label: string
  subtitle: string
  statusLabel: string
  hostId?: number
  fillTone: InfrastructureMapTone
  metricLabel: string
  sizeLabel: string
  sizePercent: number
  details: Array<{label: string; value: string}>
}

export interface InfrastructureMapGroup {
  key: string
  label: string
  nodes: InfrastructureMapNode[]
  healthyCount: number
}

export interface InfrastructureMapSummary {
  totalResources: number
  visibleResources: number
  healthyResources: number
  groupCount: number
}

export interface InfrastructureMapResult {
  groups: InfrastructureMapGroup[]
  summary: InfrastructureMapSummary
}

interface CreateResourcesOptions {
  resourceKind: InfrastructureResourceKind
  hosts: DdHostResponse[]
  containers: DdContainerResponse[]
  nowMs?: number
}

export function createInfrastructureMapResources({
  resourceKind,
  hosts,
  containers,
  nowMs = Date.now(),
}: CreateResourcesOptions): InfrastructureMapResource[] {
  if (resourceKind === 'containers') {
    return createContainerResources(containers, hosts, nowMs)
  }
  return createHostResources(hosts, nowMs)
}

export function buildInfrastructureMapGroups(
  resources: InfrastructureMapResource[],
  view: InfrastructureMapViewState,
): InfrastructureMapResult {
  const query = view.searchQuery.trim().toLowerCase()
  const visibleResources = resources.filter((resource) => {
    const groupLabel = getGroupLabel(resource, view.groupBy)
    return query === '' || `${resource.searchText} ${groupLabel}`.toLowerCase().includes(query)
  })
  const maxSizeValue = getMaxMetricValue(visibleResources, view.sizeBy)
  const maxFillValue = getMaxMetricValue(visibleResources, view.fillBy)
  const groupsByLabel = new Map<string, InfrastructureMapGroup>()

  for (const resource of visibleResources) {
    const groupLabel = getGroupLabel(resource, view.groupBy)
    const group = getOrCreateGroup(groupsByLabel, groupLabel)
    group.nodes.push(createMapNode(resource, view, maxSizeValue, maxFillValue))
    if (resource.isHealthy) group.healthyCount += 1
  }

  const groups = Array.from(groupsByLabel.values())
    .map((group) => ({
      ...group,
      nodes: group.nodes.sort(sortNodes),
    }))
    .sort(sortGroups)

  return {
    groups,
    summary: {
      totalResources: resources.length,
      visibleResources: visibleResources.length,
      healthyResources: visibleResources.filter((resource) => resource.isHealthy).length,
      groupCount: groups.length,
    },
  }
}

function createHostResources(hosts: DdHostResponse[], nowMs: number): InfrastructureMapResource[] {
  return hosts.map((host) => {
    const statusLabel = host.isOnline ? 'up' : 'down'
    const lastSeenMinutes = minutesSince(host.lastSeenAt, nowMs)
    const tags = host.tags ?? {}
    const dimensions: Partial<Record<InfrastructureGroupBy, string>> = {
      status: statusLabel,
      host: host.hostname || 'Unknown host',
      platform: host.platform || 'Unknown platform',
      os: host.os || 'Unknown OS',
      agent: host.agentVersion ? `v${host.agentVersion}` : 'Unknown agent',
    }
    const metrics = {
      cpu: host.cpuCores ?? 0,
      memory: host.memoryTotalKb ?? 0,
      network: 0,
      lastSeen: lastSeenMinutes ?? STALE_SEEN_MINUTES,
    }

    return {
      id: `host-${host.id}`,
      resourceKind: 'hosts',
      label: host.hostname || `Host ${host.id}`,
      subtitle: host.platform || host.os || 'host',
      statusLabel,
      isHealthy: host.isOnline,
      hostId: host.id,
      tags,
      dimensions,
      metrics,
      toneMetrics: {},
      details: [
        {label: 'CPU', value: `${metrics.cpu} cores`},
        {label: 'Memory', value: formatKilobytes(metrics.memory)},
        {label: 'Last seen', value: formatMinutesSince(lastSeenMinutes)},
      ],
      searchText: buildSearchText([host.hostname, host.os, host.platform, host.processor, host.agentVersion], tags),
    }
  })
}

function createContainerResources(
  containers: DdContainerResponse[],
  hosts: DdHostResponse[],
  nowMs: number,
): InfrastructureMapResource[] {
  const hostIdsByName = new Map(hosts.map((host) => [host.hostname, host.id]))

  return dedupeContainers(containers).map((container) => {
    const isRunning = container.state === 'running'
    const tags = container.tags ?? {}
    const imageName = trimImageTag(container.image || 'Unknown image')
    const lastSeenMinutes = minutesSince(container.timestamp, nowMs)
    const metrics = {
      cpu: container.cpuPercent ?? 0,
      memory: container.memUsage ?? 0,
      network: (container.netRxBytes ?? 0) + (container.netTxBytes ?? 0),
      lastSeen: lastSeenMinutes ?? STALE_SEEN_MINUTES,
    }
    const toneMetrics = {
      cpu: metrics.cpu,
      memory: getPercent(metrics.memory, container.memLimit ?? 0),
    }
    const dimensions: Partial<Record<InfrastructureGroupBy, string>> = {
      status: isRunning ? 'running' : 'stopped',
      host: container.host || 'Unknown host',
      image: imageName,
    }

    return {
      id: `container-${container.host}-${container.containerId}`,
      resourceKind: 'containers',
      label: container.name || shortContainerId(container.containerId),
      subtitle: container.host || imageName,
      statusLabel: isRunning ? 'running' : 'stopped',
      isHealthy: isRunning,
      hostId: hostIdsByName.get(container.host),
      tags,
      dimensions,
      metrics,
      toneMetrics,
      details: [
        {label: 'Host', value: container.host || 'Unknown host'},
        {label: 'CPU', value: `${metrics.cpu.toFixed(1)}%`},
        {label: 'Memory', value: formatUsageWithLimit(metrics.memory, container.memLimit ?? 0)},
        {label: 'Network', value: formatBytes(metrics.network)},
      ],
      searchText: buildSearchText(
        [container.name, container.host, container.image, container.containerId, container.state],
        tags,
      ),
    }
  })
}

function dedupeContainers(containers: DdContainerResponse[]): DdContainerResponse[] {
  const containersByIdentity = new Map<string, DdContainerResponse>()

  for (const container of containers) {
    const key = `${container.host}::${container.containerId}`
    const existing = containersByIdentity.get(key)
    if (!existing || parseTimestampMs(container.timestamp) > parseTimestampMs(existing.timestamp)) {
      containersByIdentity.set(key, container)
    }
  }

  return Array.from(containersByIdentity.values())
}

function createMapNode(
  resource: InfrastructureMapResource,
  view: InfrastructureMapViewState,
  maxSizeValue: number,
  maxFillValue: number,
): InfrastructureMapNode {
  return {
    id: resource.id,
    label: resource.label,
    subtitle: resource.subtitle,
    statusLabel: resource.statusLabel,
    hostId: resource.hostId,
    fillTone: getFillTone(resource, view.fillBy, maxFillValue),
    metricLabel: formatMetricValue(resource, view.fillBy),
    sizeLabel: formatSizeValue(resource, view.sizeBy),
    sizePercent: getSizePercent(getMetricValue(resource, view.sizeBy), maxSizeValue, view.sizeBy),
    details: resource.details,
  }
}

function getOrCreateGroup(
  groupsByLabel: Map<string, InfrastructureMapGroup>,
  label: string,
): InfrastructureMapGroup {
  const existing = groupsByLabel.get(label)
  if (existing) return existing

  const group = {key: label.toLowerCase(), label, nodes: [], healthyCount: 0}
  groupsByLabel.set(label, group)
  return group
}

function getGroupLabel(resource: InfrastructureMapResource, groupBy: InfrastructureGroupBy): string {
  if (groupBy.startsWith(TAG_GROUP_PREFIX)) {
    const tagName = groupBy.slice(TAG_GROUP_PREFIX.length)
    return resource.tags[tagName] || `No ${tagName}`
  }
  return resource.dimensions[groupBy] || 'Unknown'
}

function getMaxMetricValue(
  resources: InfrastructureMapResource[],
  metric: InfrastructureFillBy | InfrastructureSizeBy,
): number {
  if (metric === 'uniform' || metric === 'health') return 1
  return resources.reduce((maxValue, resource) => Math.max(maxValue, getMetricValue(resource, metric)), 0)
}

function getMetricValue(
  resource: InfrastructureMapResource,
  metric: InfrastructureFillBy | InfrastructureSizeBy,
): number {
  if (metric === 'health') return resource.isHealthy ? 1 : 0
  if (metric === 'uniform') return 1
  return resource.metrics[metric]
}

function getFillTone(
  resource: InfrastructureMapResource,
  fillBy: InfrastructureFillBy,
  maxFillValue: number,
): InfrastructureMapTone {
  if (fillBy === 'health') return resource.isHealthy ? 'success' : 'danger'
  if (fillBy === 'lastSeen') return getRecencyTone(resource.metrics.lastSeen)
  if (fillBy === 'cpu' && resource.resourceKind === 'hosts') return 'info'
  if (fillBy === 'memory' && resource.resourceKind === 'hosts') return 'info'
  if (fillBy === 'network') return getRelativeMetricTone(getMetricValue(resource, fillBy), maxFillValue)
  return getUtilizationTone(resource.toneMetrics[fillBy] ?? getMetricValue(resource, fillBy))
}

function getRecencyTone(minutes: number): InfrastructureMapTone {
  if (minutes <= RECENT_SEEN_MINUTES) return 'success'
  if (minutes <= STALE_SEEN_MINUTES) return 'warning'
  return 'danger'
}

function getUtilizationTone(value: number): InfrastructureMapTone {
  if (value >= HIGH_UTILIZATION_PERCENT) return 'danger'
  if (value >= MEDIUM_UTILIZATION_PERCENT) return 'warning'
  if (value > 0) return 'success'
  return 'neutral'
}

function getRelativeMetricTone(value: number, maxValue: number): InfrastructureMapTone {
  if (value <= 0 || maxValue <= 0) return 'neutral'
  return getUtilizationTone((value / maxValue) * 100)
}

function getSizePercent(value: number, maxValue: number, sizeBy: InfrastructureSizeBy): number {
  if (sizeBy === 'uniform' || maxValue <= 0) return DEFAULT_TILE_PERCENT
  const normalized = Math.sqrt(Math.min(value / maxValue, 1))
  return Math.round(MIN_TILE_PERCENT + normalized * (MAX_TILE_PERCENT - MIN_TILE_PERCENT))
}

function formatMetricValue(resource: InfrastructureMapResource, metric: InfrastructureFillBy): string {
  if (metric === 'health') return resource.statusLabel
  if (metric === 'lastSeen') return formatMinutesSince(resource.metrics.lastSeen)
  if (metric === 'cpu' && resource.resourceKind === 'containers') {
    return `${resource.metrics.cpu.toFixed(1)}% CPU`
  }
  if (metric === 'cpu') return `${resource.metrics.cpu} cores`
  if (metric === 'memory' && resource.resourceKind === 'hosts') return formatKilobytes(resource.metrics.memory)
  if (metric === 'memory') return formatBytes(resource.metrics.memory)
  return formatBytes(resource.metrics.network)
}

function formatSizeValue(resource: InfrastructureMapResource, metric: InfrastructureSizeBy): string {
  if (metric === 'uniform') return 'uniform'
  if (metric === 'cpu' && resource.resourceKind === 'containers') {
    return `${resource.metrics.cpu.toFixed(1)}% CPU`
  }
  if (metric === 'cpu') return `${resource.metrics.cpu} cores`
  if (metric === 'memory' && resource.resourceKind === 'hosts') return formatKilobytes(resource.metrics.memory)
  if (metric === 'memory') return formatBytes(resource.metrics.memory)
  return formatBytes(resource.metrics.network)
}

function formatKilobytes(value: number): string {
  return formatBytes(value * BYTES_PER_KILOBYTE)
}

function formatBytes(value: number): string {
  if (value < BYTES_PER_KILOBYTE) return `${Math.round(value)} B`
  if (value < BYTES_PER_MEGABYTE) return `${(value / BYTES_PER_KILOBYTE).toFixed(1)} KB`
  if (value < BYTES_PER_GIGABYTE) return `${(value / BYTES_PER_MEGABYTE).toFixed(1)} MB`
  return `${(value / BYTES_PER_GIGABYTE).toFixed(1)} GB`
}

function formatUsageWithLimit(value: number, limit: number): string {
  if (limit <= 0) return formatBytes(value)
  return `${formatBytes(value)} / ${formatBytes(limit)}`
}

function getPercent(value: number, total: number): number {
  if (total <= 0) return 0
  return (value / total) * 100
}

function formatMinutesSince(minutes: number | null): string {
  if (minutes == null) return 'never seen'
  if (minutes < 1) return 'just now'
  if (minutes < MINUTES_PER_HOUR) return `${Math.floor(minutes)}m ago`
  if (minutes < MINUTES_PER_DAY) return `${Math.floor(minutes / MINUTES_PER_HOUR)}h ago`
  return `${Math.floor(minutes / MINUTES_PER_DAY)}d ago`
}

function minutesSince(value: string | undefined, nowMs: number): number | null {
  const timestamp = parseTimestampMs(value)
  if (timestamp === 0) return null
  return Math.max(0, Math.floor((nowMs - timestamp) / MILLIS_PER_MINUTE))
}

function parseTimestampMs(value: string | undefined): number {
  if (!value) return 0
  const hasTimezone = value.endsWith('Z') || /[+-]\d{2}:\d{2}$/.test(value)
  const normalized = hasTimezone ? value : value.replace(' ', 'T') + 'Z'
  const parsed = Date.parse(normalized)
  return Number.isNaN(parsed) ? 0 : parsed
}

function trimImageTag(image: string): string {
  const slashIndex = image.lastIndexOf('/')
  const name = slashIndex >= 0 ? image.slice(slashIndex + 1) : image
  const tagIndex = name.lastIndexOf(':')
  return tagIndex > 0 ? name.slice(0, tagIndex) : name
}

function shortContainerId(containerId: string): string {
  return containerId ? containerId.slice(0, 12) : 'container'
}

function buildSearchText(values: Array<string | undefined>, tags: Record<string, string>): string {
  const tagValues = Object.entries(tags).flatMap(([key, value]) => [key, value])
  return [...values, ...tagValues].filter((value): value is string => Boolean(value)).join(' ')
}

function sortGroups(a: InfrastructureMapGroup, b: InfrastructureMapGroup): number {
  const rankDifference = getGroupRank(a.label) - getGroupRank(b.label)
  return rankDifference !== 0 ? rankDifference : a.label.localeCompare(b.label)
}

function sortNodes(a: InfrastructureMapNode, b: InfrastructureMapNode): number {
  const toneDifference = getToneRank(a.fillTone) - getToneRank(b.fillTone)
  return toneDifference !== 0 ? toneDifference : a.label.localeCompare(b.label)
}

function getGroupRank(label: string): number {
  switch (label) {
    case 'down':
    case 'stopped':
      return 0
    case 'Unknown':
      return 1
    case 'up':
    case 'running':
      return 2
    default:
      return 3
  }
}

function getToneRank(tone: InfrastructureMapTone): number {
  switch (tone) {
    case 'danger':
      return 0
    case 'warning':
      return 1
    case 'neutral':
      return 2
    case 'info':
      return 3
    case 'success':
      return 4
  }
}
