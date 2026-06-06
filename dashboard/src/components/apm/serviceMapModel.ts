// Moneat - observability platform
// Copyright (C) 2026 Moneat
//
// This program is free software: you can redistribute it and/or modify
// it under the terms of the GNU Affero General Public License as published by
// the Free Software Foundation, either version 3 of the License, or
// (at your option) any later version.

import type {ApmServiceEdge, ApmServiceMapEntry, ApmTimeRange} from '@/lib/api/types/apm'
import type {FacetFilter, FacetValue} from '@/lib/filters/types'

export type ServiceType = 'database' | 'cache' | 'queue' | 'web' | 'service'
export type HealthTone = 'success' | 'warning' | 'danger' | 'neutral'

// Health-facet labels shown in the rail (and matched by the graph filter).
// Ordered worst-first to match the mockup: errors, elevated, healthy, no data.
export const HEALTH_TONE_LABEL: Record<HealthTone, string> = {
  danger: 'errors',
  warning: 'elevated',
  success: 'healthy',
  neutral: 'no data',
}

const HEALTH_FACET_ORDER: HealthTone[] = ['danger', 'warning', 'success', 'neutral']
const TYPE_FACET_ORDER: ServiceType[] = ['web', 'service', 'database', 'cache', 'queue']

const HEALTH_FACET_KEY = 'health'
const TYPE_FACET_KEY = 'type'

// Error-rate thresholds shared by node health borders and edge coloring. The map
// has no monitor/SLO state to read, so error rate is the honest health proxy.
const WARNING_ERROR_RATE = 0.01
const DANGER_ERROR_RATE = 0.05

const MIN_EDGE_WIDTH = 1.5
const MAX_EDGE_WIDTH = 5
const SECONDS_PER_MINUTE = 60

const NS_PER_MICRO = 1_000
const NS_PER_MILLI = 1_000_000
const NS_PER_SECOND = 1_000_000_000

const TIME_RANGE_SECONDS: Record<ApmTimeRange, number> = {
  '1h': 3_600,
  '6h': 21_600,
  '24h': 86_400,
  '7d': 604_800,
  '30d': 2_592_000,
  '90d': 7_776_000,
}

export interface ServiceMapNodeModel {
  id: string
  label: string
  serviceType: ServiceType
  isInferred: boolean
  spanCount: number
  errorCount: number
  errorRate: number
  throughputPerMin: number
  avgDurationNs: number
  healthTone: HealthTone
  selected: boolean
  dimmed: boolean
}

export interface ServiceMapEdgeModel {
  id: string
  source: string
  target: string
  callCount: number
  errorCount: number
  errorRate: number
  avgDurationNs: number
  tone: HealthTone
  widthPx: number
  connected: boolean
  dimmed: boolean
  animated: boolean
  label?: string
}

export interface ServiceMapNeighbors {
  upstream: ServiceMapEdgeModel[]
  downstream: ServiceMapEdgeModel[]
}

export interface ServiceMapGraph {
  nodes: ServiceMapNodeModel[]
  edges: ServiceMapEdgeModel[]
  neighbors: ServiceMapNeighbors | null
  visibleServiceCount: number
  totalServiceCount: number
  inferredCount: number
}

interface BuildServiceMapGraphOptions {
  services: ApmServiceMapEntry[]
  edges: ApmServiceEdge[]
  windowSeconds: number
  search?: string
  selectedId?: string | null
  facetFilters?: FacetFilter[]
}

export function timeRangeSeconds(range: ApmTimeRange): number {
  return TIME_RANGE_SECONDS[range] ?? TIME_RANGE_SECONDS['24h']
}

export function inferServiceType(name: string, peerKind?: string): ServiceType {
  const hint = `${peerKind ?? ''} ${name}`.toLowerCase()
  if (/postgres|mysql|mongo|clickhouse|sqlite|mariadb|cockroach|\bdb\b|sql|dynamo|cassandra|database/.test(hint)) {
    return 'database'
  }
  if (/redis|cache|memcached|varnish/.test(hint)) return 'cache'
  if (/kafka|rabbitmq|queue|sqs|sns|nats|pulsar|amqp|messaging/.test(hint)) return 'queue'
  if (/web|api|gateway|nginx|envoy|proxy|frontend|http|grpc/.test(hint)) return 'web'
  return 'service'
}

export function healthTone(errorRate: number, spanCount: number): HealthTone {
  if (spanCount <= 0) return 'neutral'
  if (errorRate >= DANGER_ERROR_RATE) return 'danger'
  if (errorRate >= WARNING_ERROR_RATE) return 'warning'
  return 'success'
}

function serviceTone(service: ApmServiceMapEntry): HealthTone {
  return healthTone(safeRate(service.errorCount, service.spanCount), service.spanCount)
}

/**
 * Tally the Health and Type facet values (with counts) over the known services,
 * for the service-map rail. Counts reflect the full fetched set so a value never
 * vanishes just because it is currently filtered out.
 */
export function buildServiceFacetSections(
  services: ApmServiceMapEntry[],
): {health: FacetValue[]; type: FacetValue[]} {
  const healthCounts = new Map<HealthTone, number>()
  const typeCounts = new Map<ServiceType, number>()
  for (const service of services) {
    const tone = serviceTone(service)
    healthCounts.set(tone, (healthCounts.get(tone) ?? 0) + 1)
    const type = inferServiceType(service.service)
    typeCounts.set(type, (typeCounts.get(type) ?? 0) + 1)
  }
  return {
    health: HEALTH_FACET_ORDER.filter((tone) => healthCounts.has(tone)).map((tone) => ({
      value: HEALTH_TONE_LABEL[tone],
      count: healthCounts.get(tone) ?? 0,
    })),
    type: TYPE_FACET_ORDER.filter((type) => typeCounts.has(type)).map((type) => ({
      value: type,
      count: typeCounts.get(type) ?? 0,
    })),
  }
}

interface FacetMatchGroup {
  include: Set<string>
  exclude: Set<string>
}

function collectFacetGroup(filters: FacetFilter[], key: string): FacetMatchGroup | null {
  let group: FacetMatchGroup | null = null
  for (const filter of filters) {
    if (filter.key !== key) continue
    group ??= {include: new Set<string>(), exclude: new Set<string>()}
    if (filter.exclude) group.exclude.add(filter.value)
    else group.include.add(filter.value)
  }
  return group
}

function matchesFacetGroup(group: FacetMatchGroup | null, value: string): boolean {
  if (!group) return true
  if (group.exclude.has(value)) return false
  return group.include.size === 0 || group.include.has(value)
}

// Returns a predicate over known services, or null when no Health/Type facet is
// active (so the caller can skip the pass entirely).
function buildServiceFacetMatcher(
  filters: FacetFilter[],
): ServiceFacetMatcher | null {
  const health = collectFacetGroup(filters, HEALTH_FACET_KEY)
  const type = collectFacetGroup(filters, TYPE_FACET_KEY)
  if (!health && !type) return null
  return (service) =>
    matchesFacetGroup(health, HEALTH_TONE_LABEL[serviceTone(service)]) &&
    matchesFacetGroup(type, inferServiceType(service.service))
}

export function edgeTone(errorRate: number, callCount: number): HealthTone {
  if (callCount <= 0) return 'neutral'
  if (errorRate >= DANGER_ERROR_RATE) return 'danger'
  if (errorRate >= WARNING_ERROR_RATE) return 'warning'
  return 'neutral'
}

export function formatDurationNs(ns: number): string {
  if (ns <= 0) return '0µs'
  if (ns < NS_PER_MILLI) return `${(ns / NS_PER_MICRO).toFixed(0)}µs`
  if (ns < NS_PER_SECOND) return `${(ns / NS_PER_MILLI).toFixed(1)}ms`
  return `${(ns / NS_PER_SECOND).toFixed(2)}s`
}

export function formatCount(value: number): string {
  if (value < 1_000) return `${Math.round(value)}`
  if (value < 1_000_000) return `${(value / 1_000).toFixed(1)}k`
  return `${(value / 1_000_000).toFixed(1)}M`
}

export function formatRatePerMin(count: number, windowSeconds: number): string {
  if (windowSeconds <= 0 || count <= 0) return '0/min'
  const perMinute = count / (windowSeconds / SECONDS_PER_MINUTE)
  if (perMinute >= 1) return `${formatCount(perMinute)}/min`
  return `${perMinute.toFixed(2)}/min`
}

export function formatPercent(rate: number): string {
  return `${(rate * 100).toFixed(rate >= 0.1 ? 0 : 1)}%`
}

function safeRate(numerator: number, denominator: number): number {
  if (denominator <= 0) return 0
  return numerator / denominator
}

function edgeId(source: string, target: string): string {
  return `${source}->${target}`
}

function buildEdgeLabel(callCount: number, avgDurationNs: number, errorRate: number): string {
  const parts = [formatCount(callCount), formatDurationNs(avgDurationNs)]
  if (errorRate > 0) parts.push(formatPercent(errorRate))
  return parts.join(' · ')
}

interface InferredAccumulator {
  callCount: number
  errorCount: number
  durationWeighted: number
}

type ServiceFacetMatcher = (service: ApmServiceMapEntry) => boolean

interface NodeBuildContext {
  windowSeconds: number
  selectedId: string | null
  connectedServices: Set<string>
}

interface EdgeBuildContext {
  maxCall: number
  selectedId: string | null
  connectedEdges: Set<string>
}

/**
 * Pure transform from the raw service-map payload into render-ready nodes and
 * edges: derives inferred peer nodes from edges whose target never emitted its
 * own spans, computes per-service throughput/error-rate/health, weights each
 * edge by throughput, colors it by error rate, and resolves the focused
 * neighborhood (dim/animate/label) when a node is selected.
 */
export function buildServiceMapGraph({
  services,
  edges,
  windowSeconds,
  search = '',
  selectedId = null,
  facetFilters = [],
}: BuildServiceMapGraphOptions): ServiceMapGraph {
  const query = search.trim().toLowerCase()
  const knownServices = new Set(services.map((service) => service.service))

  const visibleServices = buildVisibleServices({services, edges, knownServices, query})
  applyServiceFacetFilters(visibleServices, services, facetFilters)

  const includedEdges = filterRenderableEdges(edges, visibleServices, knownServices)
  const inferredAccumulators = collectInferredAccumulators(includedEdges, knownServices)

  const connected = resolveConnected(selectedId, includedEdges)
  const maxCall = includedEdges.reduce((max, edge) => Math.max(max, edge.callCount), 0)
  const nodeContext = {windowSeconds, selectedId, connectedServices: connected.services}
  const edgeContext = {maxCall, selectedId, connectedEdges: connected.edges}

  const serviceNodes = buildKnownServiceNodes(services, visibleServices, nodeContext)
  const inferredNodes = buildInferredServiceNodes(inferredAccumulators, nodeContext)
  const edgeModels = includedEdges.map((edge) => buildServiceEdgeModel(edge, edgeContext))
  const neighbors = buildNeighbors(edgeModels, selectedId)

  return {
    nodes: [...serviceNodes, ...inferredNodes],
    edges: edgeModels,
    neighbors,
    visibleServiceCount: serviceNodes.length,
    totalServiceCount: services.length,
    inferredCount: inferredNodes.length,
  }
}

function buildVisibleServices({
  services,
  edges,
  knownServices,
  query,
}: Readonly<{
  services: ApmServiceMapEntry[]
  edges: ApmServiceEdge[]
  knownServices: Set<string>
  query: string
}>): Set<string> {
  if (query === '') return new Set(services.map((service) => service.service))

  const directlyVisible = new Set(
    services.filter((service) => service.service.toLowerCase().includes(query)).map((service) => service.service),
  )
  const visibleServices = new Set(directlyVisible)
  addQueryNeighborServices(edges, knownServices, directlyVisible, visibleServices)
  return visibleServices
}

function addQueryNeighborServices(
  edges: ApmServiceEdge[],
  knownServices: Set<string>,
  directlyVisible: Set<string>,
  visibleServices: Set<string>,
) {
  for (const edge of edges) {
    if (directlyVisible.has(edge.fromService) && knownServices.has(edge.toService)) {
      visibleServices.add(edge.toService)
    }
    if (directlyVisible.has(edge.toService) && knownServices.has(edge.fromService)) {
      visibleServices.add(edge.fromService)
    }
  }
}

function applyServiceFacetFilters(
  visibleServices: Set<string>,
  services: ApmServiceMapEntry[],
  facetFilters: FacetFilter[],
) {
  const facetMatch = buildServiceFacetMatcher(facetFilters)
  if (facetMatch === null) return

  for (const service of services) {
    if (facetMatch(service)) continue
    visibleServices.delete(service.service)
  }
}

function filterRenderableEdges(
  edges: ApmServiceEdge[],
  visibleServices: Set<string>,
  knownServices: Set<string>,
): ApmServiceEdge[] {
  return edges.filter(
    (edge) => visibleServices.has(edge.fromService) && targetIsRenderable(edge.toService, visibleServices, knownServices),
  )
}

function targetIsRenderable(
  name: string,
  visibleServices: Set<string>,
  knownServices: Set<string>,
): boolean {
  return visibleServices.has(name) || !knownServices.has(name)
}

function collectInferredAccumulators(
  edges: ApmServiceEdge[],
  knownServices: Set<string>,
): Map<string, InferredAccumulator> {
  const inferredAccumulators = new Map<string, InferredAccumulator>()
  for (const edge of edges) {
    if (knownServices.has(edge.toService)) continue
    const accumulator = inferredAccumulators.get(edge.toService) ?? newInferredAccumulator()
    accumulator.callCount += edge.callCount
    accumulator.errorCount += edge.errorCount
    accumulator.durationWeighted += edge.avgDurationNs * edge.callCount
    inferredAccumulators.set(edge.toService, accumulator)
  }
  return inferredAccumulators
}

function newInferredAccumulator(): InferredAccumulator {
  return {callCount: 0, errorCount: 0, durationWeighted: 0}
}

function buildKnownServiceNodes(
  services: ApmServiceMapEntry[],
  visibleServices: Set<string>,
  context: NodeBuildContext,
): ServiceMapNodeModel[] {
  return services
    .filter((service) => visibleServices.has(service.service))
    .map((service) => buildKnownServiceNode(service, context))
}

function buildKnownServiceNode(
  service: ApmServiceMapEntry,
  context: NodeBuildContext,
): ServiceMapNodeModel {
  const errorRate = safeRate(service.errorCount, service.spanCount)
  return {
    id: service.service,
    label: service.service,
    serviceType: inferServiceType(service.service),
    isInferred: false,
    spanCount: service.spanCount,
    errorCount: service.errorCount,
    errorRate,
    throughputPerMin: ratePerMin(service.spanCount, context.windowSeconds),
    avgDurationNs: service.avgDurationNs,
    healthTone: healthTone(errorRate, service.spanCount),
    selected: context.selectedId === service.service,
    dimmed: isDimmedNode(service.service, context.selectedId, context.connectedServices),
  }
}

function buildInferredServiceNodes(
  inferredAccumulators: Map<string, InferredAccumulator>,
  context: NodeBuildContext,
): ServiceMapNodeModel[] {
  return Array.from(inferredAccumulators.entries()).map(([name, accumulator]) =>
    buildInferredServiceNode(name, accumulator, context),
  )
}

function buildInferredServiceNode(
  name: string,
  accumulator: InferredAccumulator,
  context: NodeBuildContext,
): ServiceMapNodeModel {
  const errorRate = safeRate(accumulator.errorCount, accumulator.callCount)
  return {
    id: name,
    label: name,
    serviceType: inferServiceType(name),
    isInferred: true,
    spanCount: accumulator.callCount,
    errorCount: accumulator.errorCount,
    errorRate,
    throughputPerMin: ratePerMin(accumulator.callCount, context.windowSeconds),
    avgDurationNs: inferredAvgDurationNs(accumulator),
    healthTone: 'neutral',
    selected: context.selectedId === name,
    dimmed: isDimmedNode(name, context.selectedId, context.connectedServices),
  }
}

function inferredAvgDurationNs(accumulator: InferredAccumulator): number {
  if (accumulator.callCount <= 0) return 0
  return accumulator.durationWeighted / accumulator.callCount
}

function buildServiceEdgeModel(
  edge: ApmServiceEdge,
  context: EdgeBuildContext,
): ServiceMapEdgeModel {
  const id = edgeId(edge.fromService, edge.toService)
  const errorRate = safeRate(edge.errorCount, edge.callCount)
  const isConnected = context.connectedEdges.has(id)
  const isSelectedPath = context.selectedId !== null && isConnected
  return {
    id,
    source: edge.fromService,
    target: edge.toService,
    callCount: edge.callCount,
    errorCount: edge.errorCount,
    errorRate,
    avgDurationNs: edge.avgDurationNs,
    tone: edgeTone(errorRate, edge.callCount),
    widthPx: edgeWidth(edge.callCount, context.maxCall),
    connected: isConnected,
    dimmed: context.selectedId !== null && !isConnected,
    animated: isSelectedPath,
    label: buildSelectedEdgeLabel(edge, errorRate, isSelectedPath),
  }
}

function buildSelectedEdgeLabel(
  edge: ApmServiceEdge,
  errorRate: number,
  isSelectedPath: boolean,
): string | undefined {
  if (!isSelectedPath) return undefined
  return buildEdgeLabel(edge.callCount, edge.avgDurationNs, errorRate)
}

function buildNeighbors(
  edgeModels: ServiceMapEdgeModel[],
  selectedId: string | null,
): ServiceMapNeighbors | null {
  if (selectedId === null) return null
  return {
    upstream: edgeModels.filter((edge) => edge.target === selectedId),
    downstream: edgeModels.filter((edge) => edge.source === selectedId),
  }
}

function ratePerMin(count: number, windowSeconds: number): number {
  if (windowSeconds <= 0) return 0
  return count / (windowSeconds / SECONDS_PER_MINUTE)
}

function edgeWidth(callCount: number, maxCall: number): number {
  if (maxCall <= 0 || callCount <= 0) return MIN_EDGE_WIDTH
  const normalized = Math.sqrt(Math.min(callCount / maxCall, 1))
  return MIN_EDGE_WIDTH + normalized * (MAX_EDGE_WIDTH - MIN_EDGE_WIDTH)
}

function isDimmedNode(
  nodeId: string,
  selectedId: string | null,
  connectedServices: Set<string>,
): boolean {
  return selectedId !== null && !connectedServices.has(nodeId)
}

interface ConnectedSets {
  services: Set<string>
  edges: Set<string>
}

function resolveConnected(selectedId: string | null, edges: ApmServiceEdge[]): ConnectedSets {
  const services = new Set<string>()
  const edgeIds = new Set<string>()
  if (selectedId === null) return {services, edges: edgeIds}

  services.add(selectedId)
  for (const edge of edges) {
    if (edge.fromService === selectedId || edge.toService === selectedId) {
      services.add(edge.fromService)
      services.add(edge.toService)
      edgeIds.add(edgeId(edge.fromService, edge.toService))
    }
  }
  return {services, edges: edgeIds}
}
