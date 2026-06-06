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

import {type ReactNode} from 'react'
import {screen, waitFor} from '@testing-library/react'
import userEvent from '@testing-library/user-event'
import {beforeEach, describe, expect, it, vi} from 'vitest'
import {http, HttpResponse} from 'msw'
import {server} from '@/test/mocks/server'
import {clearAuthStorage, renderWithQueryClient} from '@/test/utils'
import {MonitoringMap} from '../MonitoringMap'

type MockFacetFilter = {key: string; value: string; exclude?: boolean}
type MockServiceMapProps = Readonly<{
  searchQuery?: string
  timeRange?: string
  env?: string
  source?: string
  facetFilters?: MockFacetFilter[]
}>

// Service-map topology has its own coverage; here it's a marker so we can assert
// the chrome wires search/range/env/source/facets through to it.
vi.mock('@/components/apm/ServiceMap', () => ({
  ServiceMap: ({searchQuery = '', timeRange = '', env, source, facetFilters = []}: MockServiceMapProps) => (
    <div data-testid="service-map">
      Service map query: {searchQuery} · range: {timeRange} · env: {env ?? 'all'} · source: {source ?? 'all'} ·
      facets: {facetFilters.map((f) => `${f.exclude ? '-' : ''}${f.key}:${f.value}`).join(',') || 'none'}
    </div>
  ),
}))

vi.mock('@tanstack/react-router', async (importOriginal) => {
  const actual = await importOriginal<typeof import('@tanstack/react-router')>()
  return {
    ...actual,
    Link: ({to, children}: {to?: unknown; children: ReactNode}) => (
      <a href={typeof to === 'string' ? to : '#'}>{children}</a>
    ),
  }
})

const API_BASE = 'http://localhost:8080'

describe('MonitoringMap', () => {
  beforeEach(() => {
    clearAuthStorage()
    installServiceFacetsHandler()
  })

  it('defaults to the service-map mode and passes through service search', async () => {
    const user = userEvent.setup()

    renderWithQueryClient(<MonitoringMap />)

    expect(screen.getByText('Service Map')).toBeInTheDocument()
    expect(screen.getByRole('button', {name: 'Service map'})).toBeInTheDocument()
    expect(screen.getByRole('button', {name: 'Infrastructure'})).toBeInTheDocument()
    // Views is an infrastructure-only action.
    expect(screen.queryByText('Views')).not.toBeInTheDocument()

    await user.type(
      screen.getByPlaceholderText('Search services and dependencies...'),
      'checkout',
    )

    expect(screen.getByTestId('service-map')).toHaveTextContent('Service map query: checkout')
  })

  it('exposes the compact range/env/source controls and wires their defaults', async () => {
    renderWithQueryClient(<MonitoringMap />)

    const serviceMap = screen.getByTestId('service-map')
    expect(serviceMap).toHaveTextContent('range: 24h')
    expect(serviceMap).toHaveTextContent('env: all')
    expect(serviceMap).toHaveTextContent('source: all')

    // The header controls render inline (mockup `.ctl`) rather than in a second
    // toolbar row. Source appears once the overview facets resolve.
    expect(screen.getByText('Range')).toBeInTheDocument()
    expect(screen.getByText('env')).toBeInTheDocument()
    expect(await screen.findByText('source')).toBeInTheDocument()
    expect(screen.getByRole('combobox', {name: 'Range'})).toBeInTheDocument()
  })

  it('switches into the infrastructure hostmap and back', async () => {
    const user = userEvent.setup()
    installInfrastructureHandlers()

    renderWithQueryClient(<MonitoringMap />)

    await user.click(screen.getByRole('button', {name: 'Infrastructure'}))

    expect(screen.getByPlaceholderText('Search hosts, images, tags...')).toBeInTheDocument()
    expect(screen.getByText('Views')).toBeInTheDocument()
    // Each host renders as a tile whose accessible name carries its status.
    expect(await screen.findByRole('button', {name: /web-1/})).toBeInTheDocument()
    expect(screen.getByTestId('hostmap-toolbar')).toHaveTextContent('1 hosts')
    expect(screen.getByTestId('hostmap-toolbar')).toHaveTextContent('1 healthy')

    await user.type(screen.getByPlaceholderText('Search hosts, images, tags...'), 'missing')

    expect(await screen.findByText('No resources match your filters')).toBeInTheDocument()
    expect(screen.getByTestId('hostmap-toolbar')).toHaveTextContent('0 hosts')

    await user.click(screen.getByRole('button', {name: 'Service map'}))

    expect(screen.getByPlaceholderText('Search services and dependencies...')).toBeInTheDocument()
    expect(screen.queryByText('Views')).not.toBeInTheDocument()
    expect(screen.getByTestId('service-map')).toBeInTheDocument()
  })

  it('opens a docked detail panel for a selected container', async () => {
    const user = userEvent.setup()
    installInfrastructureHandlers({containerTotalCount: 750})

    renderWithQueryClient(<MonitoringMap initialScope="containers" />)

    expect(await screen.findByText('Showing first 500 of 750')).toBeInTheDocument()

    // Scope to the tile's full accessible name ("api, running") so it is not
    // confused with the rail's "api" image facet.
    await user.click(await screen.findByRole('button', {name: /api, running/}))

    // Docked detail shows the container and its utilization details.
    expect(screen.getAllByText('api').length).toBeGreaterThan(0)
    expect(screen.getByText('12.5%')).toBeInTheDocument()
    expect(screen.getByText(/128.0 MB/)).toBeInTheDocument()

    await user.click(screen.getByRole('button', {name: 'Close detail'}))

    expect(screen.queryByText(/128.0 MB/)).not.toBeInTheDocument()
  })

  it('shows the infrastructure fallback when resource data fails', async () => {
    installInfrastructureHandlers({failContainers: true})

    renderWithQueryClient(<MonitoringMap initialScope="containers" />)

    expect(await screen.findByText('Map data unavailable')).toBeInTheDocument()
    expect(screen.getByText(/telemetry API responds/i)).toBeInTheDocument()
  })

  it('saves the current host map view through the backend API', async () => {
    const user = userEvent.setup()
    let savedPayload: Record<string, unknown> | undefined
    installInfrastructureHandlers({
      onSave: async (request) => {
        savedPayload = (await request.json()) as Record<string, unknown>
        return HttpResponse.json({
          id: 22,
          name: 'Prod hosts',
          resource_kind: 'hosts',
          group_by: 'status',
          fill_by: 'health',
          size_by: 'memory',
          search_query: '',
          schema_version: 1,
          created_at: '2026-06-04T16:00:00Z',
          updated_at: '2026-06-04T16:00:00Z',
        })
      },
    })

    renderWithQueryClient(<MonitoringMap initialScope="hosts" />)

    expect(await screen.findByRole('button', {name: /web-1/})).toBeInTheDocument()

    await user.click(screen.getByRole('button', {name: 'Views'}))
    await user.type(screen.getByLabelText('Save as'), 'Prod hosts')
    await user.click(screen.getByRole('button', {name: 'Save'}))

    await waitFor(() => {
      expect(savedPayload).toMatchObject({
        name: 'Prod hosts',
        resource_kind: 'hosts',
        group_by: 'status',
        fill_by: 'health',
        size_by: 'memory',
        search_query: '',
      })
    })
  })

  it('renders the service rail and routes a Health facet to the map', async () => {
    const user = userEvent.setup()
    installServiceFacetsHandler({
      services: [serviceEntry('web-gateway', 1_000, 0), serviceEntry('orders', 1_000, 60)],
      edges: [],
    })

    renderWithQueryClient(<MonitoringMap />)

    // Health values are tallied from the fetched topology (danger + success).
    expect(await screen.findByRole('checkbox', {name: 'errors'})).toBeInTheDocument()
    expect(screen.getByText('Environment')).toBeInTheDocument()
    expect(screen.getByText('Health')).toBeInTheDocument()
    expect(screen.getByText('Type')).toBeInTheDocument()
    expect(screen.getByRole('checkbox', {name: 'healthy'})).toBeInTheDocument()

    await user.click(screen.getByRole('checkbox', {name: 'errors'}))

    expect(screen.getByTestId('service-map')).toHaveTextContent('facets: health:errors')
  })

  it('routes an Environment facet from the rail to the service map env', async () => {
    const user = userEvent.setup()

    renderWithQueryClient(<MonitoringMap />)

    // Environment values come from the overview facets (prod/staging).
    await user.click(await screen.findByRole('checkbox', {name: 'prod'}))

    expect(screen.getByTestId('service-map')).toHaveTextContent('env: prod')
    // Environment is single-select and rides the env channel, not the generic
    // facet channel, so the map stays unfiltered by facets.
    expect(screen.getByTestId('service-map')).toHaveTextContent('facets: none')
  })

  it('filters the hostmap when an infrastructure facet is applied', async () => {
    const user = userEvent.setup()
    server.use(
      http.get(`${API_BASE}/v1/hosts`, () =>
        HttpResponse.json({
          hosts: [
            {...hostFixture(1, 'web-1'), isOnline: true},
            {...hostFixture(2, 'db-1'), isOnline: false},
          ],
          totalCount: 2,
        }),
      ),
      http.get(`${API_BASE}/v1/infra/map/saved-views`, () => HttpResponse.json({views: []})),
    )

    renderWithQueryClient(<MonitoringMap initialScope="hosts" />)

    expect(await screen.findByRole('button', {name: /web-1, up/})).toBeInTheDocument()
    expect(screen.getByRole('button', {name: /db-1, down/})).toBeInTheDocument()
    expect(screen.getByTestId('hostmap-toolbar')).toHaveTextContent('2 hosts')

    // Status facet: keep only down hosts.
    await user.click(screen.getByRole('checkbox', {name: 'down'}))

    expect(screen.queryByRole('button', {name: /web-1, up/})).not.toBeInTheDocument()
    expect(screen.getByRole('button', {name: /db-1, down/})).toBeInTheDocument()
    expect(screen.getByTestId('hostmap-toolbar')).toHaveTextContent('1 hosts')
  })

  it('enriches the host detail with gauges, tags, and top processes', async () => {
    const user = userEvent.setup()
    server.use(
      http.get(`${API_BASE}/v1/hosts`, () =>
        HttpResponse.json({
          hosts: [{...hostFixture(1, 'web-1'), isOnline: true, tags: {env: 'prod', service: 'checkout'}}],
          totalCount: 1,
        }),
      ),
      http.get(`${API_BASE}/v1/infra/map/saved-views`, () => HttpResponse.json({views: []})),
      http.get(`${API_BASE}/v1/hosts/1/metrics`, () =>
        HttpResponse.json({
          data_points: [
            {timestamp: 1, cpu_percent: 42, mem_percent: 55, disk_percent: 20, net_recv_bytes: 1000, net_sent_bytes: 2000},
          ],
        }),
      ),
      http.get(`${API_BASE}/v1/infra/processes`, () =>
        HttpResponse.json({
          processes: [processFixture('nginx', 5), processFixture('java-app', 42)],
          totalCount: 2,
        }),
      ),
    )

    renderWithQueryClient(<MonitoringMap initialScope="hosts" />)

    await user.click(await screen.findByRole('button', {name: /web-1, up/}))

    // Live system gauges (latest data point).
    expect(await screen.findByText('42%')).toBeInTheDocument()
    // Top processes, highest CPU first.
    expect(screen.getByText('Top processes')).toBeInTheDocument()
    expect(await screen.findByText('java-app')).toBeInTheDocument()
    expect(screen.getByText('42.0%')).toBeInTheDocument()
    // Tags render as key:value chips.
    expect(screen.getByText('Tags')).toBeInTheDocument()
    expect(screen.getByText('env:prod')).toBeInTheDocument()
  })
})

function processFixture(name: string, cpuPercent: number) {
  return {
    processId: `proc-${name}`,
    host: 'web-1',
    pid: 1,
    name,
    command: `${name} --serve`,
    user: 'root',
    cpuPercent,
    memRss: 0,
    memVms: 0,
    state: 'S',
    threadCount: 1,
    openFdCount: 1,
    tags: {},
    timestamp: '2026-06-04T16:00:00Z',
  }
}

function hostFixture(id: number, hostname: string) {
  return {
    id,
    hostname,
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
  }
}

function installServiceFacetsHandler(serviceMap: {services: unknown[]; edges: unknown[]} = {services: [], edges: []}) {
  server.use(
    http.get(`${API_BASE}/v1/traces/overview`, () =>
      HttpResponse.json({
        facets: {
          services: [],
          operations: [],
          environments: [
            {value: 'prod', count: 3},
            {value: 'staging', count: 1},
          ],
          sources: [{value: 'otel', count: 2}],
        },
      }),
    ),
    // The chrome reads the service map (shared key with <ServiceMap>) only to
    // tally Health/Type facet counts for the rail.
    http.get(`${API_BASE}/v1/services/map`, () => HttpResponse.json(serviceMap)),
  )
}

function serviceEntry(service: string, spanCount: number, errorCount: number) {
  return {service, spanCount, errorCount, avgDurationNs: 5_000_000, callsTo: []}
}

interface InfrastructureHandlerOptions {
  containerTotalCount?: number
  failContainers?: boolean
  onSave?: (request: Request) => Promise<Response>
}

function installInfrastructureHandlers({
  containerTotalCount = 1,
  failContainers = false,
  onSave,
}: InfrastructureHandlerOptions = {}) {
  server.use(
    http.get(`${API_BASE}/v1/hosts`, () =>
      HttpResponse.json({
        hosts: [
          {
            id: 10,
            hostname: 'web-1',
            os: 'linux',
            platform: 'ubuntu',
            processor: 'arm64',
            cpuCores: 8,
            memoryTotalKb: 16_777_216,
            agentVersion: '7.0.0',
            tags: {env: 'prod', service: 'checkout'},
            firstSeenAt: '2026-06-04T15:00:00Z',
            lastSeenAt: '2026-06-04T16:00:00Z',
            isOnline: true,
          },
        ],
        totalCount: 1,
      }),
    ),
    http.get(`${API_BASE}/v1/infra/map/saved-views`, () => HttpResponse.json({views: []})),
    http.get(`${API_BASE}/v1/infra/containers`, ({request}) => {
      const url = new URL(request.url)
      expect(url.searchParams.get('limit')).toBe('500')
      if (failContainers) {
        return HttpResponse.json({error: 'unavailable'}, {status: 500})
      }
      return HttpResponse.json({
        containers: [
          {
            id: 'row-1',
            host: 'worker-1',
            containerId: 'abcdef1234567890',
            name: 'api',
            image: 'registry.local/api:latest',
            state: 'running',
            cpuPercent: 12.5,
            memUsage: 134_217_728,
            memLimit: 536_870_912,
            netRxBytes: 1024,
            netTxBytes: 2048,
            tags: {env: 'prod'},
            timestamp: '2026-06-04T16:00:00Z',
          },
        ],
        totalCount: containerTotalCount,
      })
    }),
    http.post(`${API_BASE}/v1/infra/map/saved-views`, ({request}) => {
      if (onSave) return onSave(request)
      return HttpResponse.json({error: 'not implemented'}, {status: 500})
    }),
  )
}
