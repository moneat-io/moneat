// Moneat - observability platform
// Copyright (C) 2026 Moneat
//
// This program is free software: you can redistribute it and/or modify
// it under the terms of the GNU Affero General Public License as published by
// the Free Software Foundation, either version 3 of the License, or
// (at your option) any later version.

import {describe, expect, it} from 'vitest'
import type {ApmServiceEdge, ApmServiceMapEntry, ApmTimeRange} from '@/lib/api/types/apm'
import {
  buildServiceFacetSections,
  buildServiceMapGraph,
  edgeTone,
  formatCount,
  formatDurationNs,
  formatPercent,
  formatRatePerMin,
  healthTone,
  inferServiceType,
  timeRangeSeconds,
} from '../serviceMapModel'

const DAY_SECONDS = 86_400

function service(
  name: string,
  spanCount: number,
  errorCount: number,
  avgDurationNs = 5_000_000,
): ApmServiceMapEntry {
  return {service: name, spanCount, errorCount, avgDurationNs, callsTo: []}
}

function edge(
  fromService: string,
  toService: string,
  callCount: number,
  errorCount = 0,
  avgDurationNs = 1_000_000,
  extra: Partial<ApmServiceEdge> = {},
): ApmServiceEdge {
  return {fromService, toService, callCount, errorCount, avgDurationNs, ...extra}
}

describe('serviceMapModel formatters', () => {
  it('formats durations across unit boundaries', () => {
    expect(formatDurationNs(0)).toBe('0µs')
    expect(formatDurationNs(500_000)).toBe('500µs')
    expect(formatDurationNs(2_500_000)).toBe('2.5ms')
    expect(formatDurationNs(3_000_000_000)).toBe('3.00s')
  })

  it('formats counts with k/M suffixes', () => {
    expect(formatCount(42)).toBe('42')
    expect(formatCount(1_500)).toBe('1.5k')
    expect(formatCount(2_400_000)).toBe('2.4M')
  })

  it('formats per-minute rates', () => {
    expect(formatRatePerMin(0, DAY_SECONDS)).toBe('0/min')
    expect(formatRatePerMin(100, 0)).toBe('0/min')
    expect(formatRatePerMin(6_000, 3_600)).toBe('100/min')
    expect(formatRatePerMin(6, 3_600)).toBe('0.10/min')
  })

  it('formats percentages with adaptive precision', () => {
    expect(formatPercent(0.1)).toBe('10%')
    expect(formatPercent(0.004)).toBe('0.4%')
  })

  it('maps known time ranges to seconds and falls back to 24h', () => {
    expect(timeRangeSeconds('1h')).toBe(3_600)
    expect(timeRangeSeconds('7d')).toBe(604_800)
    expect(timeRangeSeconds('nope' as ApmTimeRange)).toBe(DAY_SECONDS)
  })
})

describe('inferServiceType', () => {
  it('classifies services by name and peer-kind hints', () => {
    expect(inferServiceType('orders-postgres')).toBe('database')
    expect(inferServiceType('session-redis')).toBe('cache')
    expect(inferServiceType('events-kafka')).toBe('queue')
    expect(inferServiceType('edge-gateway')).toBe('web')
    expect(inferServiceType('billing')).toBe('service')
    expect(inferServiceType('pg-main', 'db')).toBe('database')
  })
})

describe('healthTone / edgeTone', () => {
  it('grades node health by error rate with a neutral no-traffic state', () => {
    expect(healthTone(0, 0)).toBe('neutral')
    expect(healthTone(0.06, 100)).toBe('danger')
    expect(healthTone(0.02, 100)).toBe('warning')
    expect(healthTone(0, 100)).toBe('success')
  })

  it('grades edges, staying neutral below the warning threshold', () => {
    expect(edgeTone(0.5, 0)).toBe('neutral')
    expect(edgeTone(0.06, 10)).toBe('danger')
    expect(edgeTone(0.02, 10)).toBe('warning')
    expect(edgeTone(0, 10)).toBe('neutral')
  })
})

describe('buildServiceFacetSections', () => {
  it('tallies health and type values in worst-first / canonical order', () => {
    const services = [
      service('web-gateway', 1_000, 0), // success / web
      service('payments', 1_000, 20), // warning / service
      service('orders', 1_000, 60), // danger / service
      service('sessions-redis', 1_000, 0), // success / cache
      service('idle', 0, 0), // neutral / service
    ]

    const {health, type} = buildServiceFacetSections(services)

    expect(health).toEqual([
      {value: 'errors', count: 1},
      {value: 'elevated', count: 1},
      {value: 'healthy', count: 2},
      {value: 'no data', count: 1},
    ])
    expect(type).toEqual([
      {value: 'web', count: 1},
      {value: 'service', count: 3},
      {value: 'cache', count: 1},
    ])
  })
})

describe('buildServiceMapGraph', () => {
  it('returns an empty graph for no input', () => {
    const graph = buildServiceMapGraph({services: [], edges: [], windowSeconds: DAY_SECONDS})
    expect(graph.nodes).toHaveLength(0)
    expect(graph.edges).toHaveLength(0)
    expect(graph.neighbors).toBeNull()
    expect(graph.totalServiceCount).toBe(0)
    expect(graph.inferredCount).toBe(0)
  })

  it('builds service + inferred nodes with weighted, colored edges', () => {
    const services = [service('web', 1_000, 60), service('api', 500, 2)]
    const edges = [
      edge('web', 'api', 400, 40, 3_000_000),
      edge('api', 'postgres', 300, 0, 1_000_000),
    ]

    const graph = buildServiceMapGraph({services, edges, windowSeconds: DAY_SECONDS})

    expect(graph.nodes.map((n) => n.id).sort()).toEqual(['api', 'postgres', 'web'])
    expect(graph.visibleServiceCount).toBe(2)
    expect(graph.inferredCount).toBe(1)

    const web = graph.nodes.find((n) => n.id === 'web')!
    expect(web.healthTone).toBe('danger')
    expect(web.errorRate).toBeCloseTo(0.06)

    const postgres = graph.nodes.find((n) => n.id === 'postgres')!
    expect(postgres.isInferred).toBe(true)
    expect(postgres.healthTone).toBe('neutral')
    expect(postgres.serviceType).toBe('database')
    expect(postgres.spanCount).toBe(300)
    expect(postgres.avgDurationNs).toBe(1_000_000)

    const webApi = graph.edges.find((e) => e.id === 'web->api')!
    const apiPg = graph.edges.find((e) => e.id === 'api->postgres')!
    expect(webApi.tone).toBe('danger')
    expect(apiPg.tone).toBe('neutral')
    // The highest-throughput edge is the widest.
    expect(webApi.widthPx).toBeGreaterThan(apiPg.widthPx)
    // Unselected: no labels, no animation.
    expect(webApi.label).toBeUndefined()
    expect(webApi.animated).toBe(false)
  })

  it('aggregates repeated edges into a single inferred peer', () => {
    const services = [service('a', 100, 0), service('b', 100, 0)]
    const edges = [
      edge('a', 'stripe', 50, 5, 2_000_000),
      edge('b', 'stripe', 50, 0, 4_000_000),
    ]

    const graph = buildServiceMapGraph({services, edges, windowSeconds: DAY_SECONDS})
    const stripe = graph.nodes.find((n) => n.id === 'stripe')!
    expect(stripe.spanCount).toBe(100)
    expect(stripe.errorCount).toBe(5)
    // Call-weighted average of 2ms and 4ms.
    expect(stripe.avgDurationNs).toBe(3_000_000)
  })

  it('keeps matched services and their immediate neighbors when searching', () => {
    const services = [service('web', 100, 0), service('api', 100, 0), service('lonely', 100, 0)]
    const edges = [edge('web', 'api', 10), edge('api', 'postgres', 10)]

    const graph = buildServiceMapGraph({
      services,
      edges,
      windowSeconds: DAY_SECONDS,
      search: 'web',
    })

    const ids = graph.nodes.map((n) => n.id)
    expect(ids).toContain('web')
    expect(ids).toContain('api')
    expect(ids).not.toContain('lonely')
  })

  it('hard-filters known services by Health and Type facets', () => {
    const services = [
      service('web-gateway', 1_000, 0), // success / web
      service('orders', 1_000, 60), // danger / service
      service('payments', 1_000, 20), // warning / service
      service('sessions-redis', 1_000, 0), // success / cache
    ]
    const edges = [edge('web-gateway', 'orders', 100), edge('orders', 'sessions-redis', 100)]

    const onlyErrors = buildServiceMapGraph({
      services,
      edges,
      windowSeconds: DAY_SECONDS,
      facetFilters: [{key: 'health', value: 'errors'}],
    })
    expect(onlyErrors.nodes.map((n) => n.id)).toEqual(['orders'])
    expect(onlyErrors.visibleServiceCount).toBe(1)

    const onlyCache = buildServiceMapGraph({
      services,
      edges,
      windowSeconds: DAY_SECONDS,
      facetFilters: [{key: 'type', value: 'cache'}],
    })
    expect(onlyCache.nodes.map((n) => n.id)).toEqual(['sessions-redis'])

    const notHealthy = buildServiceMapGraph({
      services,
      edges,
      windowSeconds: DAY_SECONDS,
      facetFilters: [{key: 'health', value: 'healthy', exclude: true}],
    })
    expect(notHealthy.nodes.map((n) => n.id).sort()).toEqual(['orders', 'payments'])
  })

  it('ignores non-client facet keys like env (applied server-side)', () => {
    const services = [service('web-gateway', 1_000, 0), service('orders', 1_000, 60)]
    const graph = buildServiceMapGraph({
      services,
      edges: [],
      windowSeconds: DAY_SECONDS,
      facetFilters: [{key: 'env', value: 'prod'}],
    })
    expect(graph.nodes.map((n) => n.id).sort()).toEqual(['orders', 'web-gateway'])
  })

  it('resolves the focused neighborhood with dimming, labels, and neighbor lists', () => {
    const services = [service('web', 100, 0), service('api', 100, 0), service('lonely', 100, 0)]
    const edges = [edge('web', 'api', 400, 40, 3_000_000), edge('api', 'postgres', 200)]

    const graph = buildServiceMapGraph({
      services,
      edges,
      windowSeconds: DAY_SECONDS,
      selectedId: 'api',
    })

    const lonely = graph.nodes.find((n) => n.id === 'lonely')!
    const web = graph.nodes.find((n) => n.id === 'web')!
    expect(lonely.dimmed).toBe(true)
    expect(web.dimmed).toBe(false)

    const webApi = graph.edges.find((e) => e.id === 'web->api')!
    expect(webApi.connected).toBe(true)
    expect(webApi.animated).toBe(true)
    expect(webApi.label).toContain('40')

    expect(graph.neighbors).not.toBeNull()
    expect(graph.neighbors!.upstream.map((e) => e.source)).toEqual(['web'])
    expect(graph.neighbors!.downstream.map((e) => e.target)).toEqual(['postgres'])
  })
})
