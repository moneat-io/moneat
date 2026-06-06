// Moneat - observability platform
// Copyright (C) 2026 Moneat
//
// This program is free software: you can redistribute it and/or modify
// it under the terms of the GNU Affero General Public License as published by
// the Free Software Foundation, either version 3 of the License, or
// (at your option) any later version.

import {type MouseEvent as ReactMouseEvent, type ReactNode} from 'react'
import {fireEvent, screen, within} from '@testing-library/react'
import {beforeEach, describe, expect, it, vi} from 'vitest'
import {http, HttpResponse} from 'msw'
import {server} from '@/test/mocks/server'
import {clearAuthStorage, renderWithQueryClient} from '@/test/utils'
import {ServiceMap} from '../ServiceMap'
import {
  layoutPositions,
  toFlowEdges,
  toFlowNodes,
  timeRangeSecondsForNode,
} from '../serviceMapFlow'
import type {ServiceMapEdgeModel, ServiceMapNodeModel} from '../serviceMapModel'

vi.mock('@tanstack/react-router', async (importOriginal) => {
  const actual = await importOriginal<typeof import('@tanstack/react-router')>()
  return {
    ...actual,
    Link: ({to, children}: {to?: unknown; children: ReactNode}) => (
      <a href={typeof to === 'string' ? to : '#'}>{children}</a>
    ),
  }
})

type MockMapNode = Readonly<{
  id: string
  data?: {dimmed?: boolean; label?: string; selected?: boolean}
}>

type MockMapCanvasProps = Readonly<{
  nodes: MockMapNode[]
  isEmpty?: boolean
  isLoading?: boolean
  emptyTitle?: string
  fallback?: ReactNode
  children?: ReactNode
  onNodeClick?: (event: ReactMouseEvent, node: MockMapNode) => void
}>

// Mirrors the real MapCanvas branching: while the query is loading it shows a
// loading frame and does not render the flow-node container, so findByTestId
// blocks until data arrives instead of resolving against an empty graph.
vi.mock('@/components/maps/MapCanvas', () => ({
  MapCanvas: ({nodes, isEmpty = false, isLoading = false, emptyTitle, fallback, children, onNodeClick}: MockMapCanvasProps) => (
    <section>
      {isLoading && <p>Loading map...</p>}
      {fallback}
      {isEmpty && !fallback && <p>{emptyTitle}</p>}
      {!isLoading && (
        <div data-testid="map-nodes">
          {nodes.map((node) => (
            <button
              key={node.id}
              type="button"
              data-dimmed={String(node.data?.dimmed ?? false)}
              data-selected={String(node.data?.selected ?? false)}
              onClick={(event) => onNodeClick?.(event, node)}
            >
              {node.data?.label ?? node.id}
            </button>
          ))}
        </div>
      )}
      {children}
    </section>
  ),
}))

const API_BASE = 'http://localhost:8080'

function serviceEntry(service: string, spanCount: number, errorCount: number, avgDurationNs = 12_000_000) {
  return {service, spanCount, errorCount, avgDurationNs, callsTo: []}
}

function edgeEntry(fromService: string, toService: string, callCount: number, errorCount = 0, avgDurationNs = 3_000_000) {
  return {fromService, toService, callCount, errorCount, avgDurationNs}
}

const DEMO_MAP = {
  services: [serviceEntry('web', 1_000, 60), serviceEntry('api', 500, 2)],
  edges: [edgeEntry('web', 'api', 400, 40, 3_000_000), edgeEntry('api', 'postgres', 300, 0, 1_000_000)],
}

function mapHandler(body: Record<string, unknown>) {
  return http.get(`${API_BASE}/v1/services/map`, () => HttpResponse.json(body))
}

function latencyHandler(p50: number, p90: number, p99: number) {
  return http.get(`${API_BASE}/v1/services/:service/latency`, ({params}) =>
    HttpResponse.json({service: String(params.service), p50DurationNs: p50, p90DurationNs: p90, p99DurationNs: p99, sampleCount: 25}),
  )
}

describe('ServiceMap', () => {
  beforeEach(() => {
    clearAuthStorage()
  })

  it('renders instrumented services and inferred peers from edges', async () => {
    server.use(mapHandler(DEMO_MAP))
    renderWithQueryClient(<ServiceMap />)

    const nodes = await screen.findByTestId('map-nodes')
    expect(within(nodes).getByText('web')).toBeInTheDocument()
    expect(within(nodes).getByText('api')).toBeInTheDocument()
    // postgres never emits its own spans — it appears as an inferred dependency.
    expect(within(nodes).getByText('postgres')).toBeInTheDocument()
  })

  it('keeps matched services and their neighbors when searching', async () => {
    server.use(
      mapHandler({
        services: [serviceEntry('web', 100, 0), serviceEntry('api', 100, 0), serviceEntry('billing', 100, 0)],
        edges: [edgeEntry('web', 'api', 50)],
      }),
    )
    renderWithQueryClient(<ServiceMap searchQuery="web" />)

    const nodes = await screen.findByTestId('map-nodes')
    expect(within(nodes).getByText('web')).toBeInTheDocument()
    expect(within(nodes).getByText('api')).toBeInTheDocument()
    expect(within(nodes).queryByText('billing')).not.toBeInTheDocument()
  })

  it('focuses a service and shows metrics, percentiles, neighbors, and pivots', async () => {
    server.use(mapHandler(DEMO_MAP), latencyHandler(1_000_000, 5_000_000, 9_000_000))
    renderWithQueryClient(<ServiceMap />)

    const nodes = await screen.findByTestId('map-nodes')
    fireEvent.click(within(nodes).getByText('api'))

    expect(screen.getByText('Throughput')).toBeInTheDocument()
    expect(screen.getByText('Error rate')).toBeInTheDocument()
    expect(screen.getByText('Upstream')).toBeInTheDocument()
    expect(screen.getByText('Downstream')).toBeInTheDocument()

    // Percentiles arrive from the focused-service latency endpoint.
    expect(await screen.findByText('1.0ms')).toBeInTheDocument()
    expect(screen.getByText('5.0ms')).toBeInTheDocument()
    expect(screen.getByText('9.0ms')).toBeInTheDocument()

    // Upstream (web) and downstream (postgres) both appear in the panel.
    expect(within(nodes).getByText('web')).toBeInTheDocument()
    expect(screen.getAllByText('web').length).toBeGreaterThan(1)
    expect(screen.getAllByText('postgres').length).toBeGreaterThan(1)

    expect(screen.getByText('Traces').closest('a')).toHaveAttribute('href', '/performance/traces')
    expect(screen.getByText('Logs').closest('a')).toHaveAttribute('href', '/logs')
    expect(screen.getByText('Profiles').closest('a')).toHaveAttribute('href', '/profiles/service/$service')
  })

  it('omits percentiles and pivots for an inferred dependency', async () => {
    server.use(mapHandler(DEMO_MAP))
    renderWithQueryClient(<ServiceMap />)

    const nodes = await screen.findByTestId('map-nodes')
    fireEvent.click(within(nodes).getByText('postgres'))

    expect(screen.getByText('Inferred dependency')).toBeInTheDocument()
    expect(screen.queryByText('p50')).not.toBeInTheDocument()
    expect(screen.queryByText('Traces')).not.toBeInTheDocument()
  })

  it('passes time range, env, and source as query params', async () => {
    let captured: URL | null = null
    server.use(
      http.get(`${API_BASE}/v1/services/map`, ({request}) => {
        captured = new URL(request.url)
        return HttpResponse.json(DEMO_MAP)
      }),
    )

    renderWithQueryClient(<ServiceMap timeRange="6h" env="prod" source="otel" />)
    await screen.findByText('web')

    expect(captured!.searchParams.get('timeRange')).toBe('6h')
    expect(captured!.searchParams.get('env')).toBe('prod')
    expect(captured!.searchParams.get('source')).toBe('otel')
  })

  it('shows an explicit fallback when service map data cannot load', async () => {
    server.use(http.get(`${API_BASE}/v1/services/map`, () => HttpResponse.json({error: 'unavailable'}, {status: 500})))
    renderWithQueryClient(<ServiceMap />)

    expect(await screen.findByText('Map data unavailable')).toBeInTheDocument()
    expect(screen.getByText(/telemetry API responds/i)).toBeInTheDocument()
  })
})

describe('ServiceMap flow mappers', () => {
  function nodeModel(id: string, overrides: Partial<ServiceMapNodeModel> = {}): ServiceMapNodeModel {
    return {
      id,
      label: id,
      serviceType: 'service',
      isInferred: false,
      spanCount: 100,
      errorCount: 0,
      errorRate: 0,
      throughputPerMin: 10,
      avgDurationNs: 1_000_000,
      healthTone: 'success',
      selected: false,
      dimmed: false,
      ...overrides,
    }
  }

  function edgeModel(overrides: Partial<ServiceMapEdgeModel> = {}): ServiceMapEdgeModel {
    return {
      id: 'a->b',
      source: 'a',
      target: 'b',
      callCount: 100,
      errorCount: 0,
      errorRate: 0,
      avgDurationNs: 1_000_000,
      tone: 'neutral',
      widthPx: 2,
      connected: true,
      dimmed: false,
      animated: true,
      label: '100 · 1.0ms',
      ...overrides,
    }
  }

  it('lays out every node and maps them to flow nodes', () => {
    const nodes = [nodeModel('a'), nodeModel('b', {isInferred: true})]
    const positions = layoutPositions(nodes, [edgeModel()])
    expect(positions.has('a')).toBe(true)
    expect(positions.has('b')).toBe(true)
    expect(typeof positions.get('a')!.x).toBe('number')

    const flowNodes = toFlowNodes(nodes, positions)
    expect(flowNodes).toHaveLength(2)
    expect(flowNodes[0]).toMatchObject({id: 'a', type: 'service'})
    expect((flowNodes[0].data as {label: string}).label).toBe('a')
  })

  it('maps edge models to styled flow edges', () => {
    const [flowEdge] = toFlowEdges([edgeModel({tone: 'danger', widthPx: 4})])
    expect(flowEdge).toMatchObject({id: 'a->b', source: 'a', target: 'b', animated: true})
    expect((flowEdge.style as {strokeWidth: number}).strokeWidth).toBe(4)
    expect(flowEdge.label).toBe('100 · 1.0ms')
  })

  it('reads window seconds off node data with a default', () => {
    expect(timeRangeSecondsForNode({...nodeModel('a'), windowSeconds: 120})).toBe(120)
    expect(timeRangeSecondsForNode({...nodeModel('a')})).toBe(86_400)
  })
})
