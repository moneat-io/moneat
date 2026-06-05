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

import {type MouseEvent as ReactMouseEvent, type ReactNode} from 'react'
import {screen, waitFor, within} from '@testing-library/react'
import userEvent from '@testing-library/user-event'
import {beforeEach, describe, expect, it, vi} from 'vitest'
import {http, HttpResponse} from 'msw'
import {server} from '@/test/mocks/server'
import {clearAuthStorage, renderWithQueryClient} from '@/test/utils'
import {InfrastructureMap} from '../InfrastructureMap'

type MockServiceMapProps = Readonly<{
  searchQuery?: string
}>

type MockMapNode = Readonly<{
  id: string
  type?: string
  data?: {
    label?: string
    metricLabel?: string
    statusLabel?: string
  }
}>

type MockMapCanvasProps = Readonly<{
  nodes: MockMapNode[]
  isEmpty?: boolean
  isLoading?: boolean
  emptyTitle?: string
  fallback?: ReactNode
  children?: ReactNode
  onNodeClick?: (event: ReactMouseEvent, node: MockMapNode) => void
  onPaneClick?: () => void
}>

vi.mock('@/components/apm/ServiceMap', () => ({
  ServiceMap: ({searchQuery = ''}: MockServiceMapProps) => (
    <div data-testid="service-map">Service map query: {searchQuery}</div>
  ),
}))

vi.mock('@/components/maps/MapCanvas', () => ({
  MapCanvas: ({
    nodes,
    isEmpty = false,
    isLoading = false,
    emptyTitle,
    fallback,
    children,
    onNodeClick,
    onPaneClick,
  }: MockMapCanvasProps) => (
    <section data-testid="map-canvas">
      {isLoading && <p>Loading map...</p>}
      {fallback}
      {isEmpty && !fallback && <p>{emptyTitle}</p>}
      <button type="button" onClick={onPaneClick}>Pane</button>
      <div data-testid="map-nodes">
        {nodes.map((node) => (
          <button
            key={node.id}
            type="button"
            onClick={(event) => onNodeClick?.(event, node)}
          >
            {node.data?.label ?? node.id}
            {node.data?.metricLabel ? ` ${node.data.metricLabel}` : ''}
            {node.data?.statusLabel ? ` ${node.data.statusLabel}` : ''}
          </button>
        ))}
      </div>
      {children}
    </section>
  ),
}))

const API_BASE = 'http://localhost:8080'

describe('InfrastructureMap', () => {
  beforeEach(() => {
    clearAuthStorage()
  })

  it('defaults to the service map scope and passes through service search', async () => {
    const user = userEvent.setup()

    renderWithQueryClient(<InfrastructureMap />)

    expect(screen.getByText('Map Explorer')).toBeInTheDocument()
    expect(screen.getByText('Services')).toBeInTheDocument()
    expect(screen.getByText('Hosts')).toBeInTheDocument()
    expect(screen.getByText('Containers')).toBeInTheDocument()
    expect(screen.queryByText('Views')).not.toBeInTheDocument()

    await user.type(
      screen.getByPlaceholderText('Search services and dependencies...'),
      'checkout{Enter}',
    )

    expect(screen.getByTestId('service-map')).toHaveTextContent('Service map query: checkout')
  })

  it('switches into the host map scope and renders host inventory controls', async () => {
    const user = userEvent.setup()
    installInfrastructureHandlers()

    renderWithQueryClient(<InfrastructureMap />)

    await user.click(screen.getByRole('button', {name: 'Hosts map'}))

    expect(screen.getByPlaceholderText('Search hosts, images, tags...')).toBeInTheDocument()
    expect(screen.getByText('Views')).toBeInTheDocument()
    expect(await screen.findByText(/web-1/)).toBeInTheDocument()

    const canvas = screen.getByTestId('map-canvas')
    expect(within(canvas).getAllByText(/up/).length).toBeGreaterThan(0)
    expect(screen.getByText('1 groups')).toBeInTheDocument()
    expect(screen.getByText('1 shown')).toBeInTheDocument()
    expect(screen.getByText('1 healthy')).toBeInTheDocument()

    await user.type(screen.getByPlaceholderText('Search hosts, images, tags...'), 'missing{Enter}')

    expect(await screen.findByText('No resources match your filters')).toBeInTheDocument()
    expect(screen.getByText('0 shown')).toBeInTheDocument()

    await user.click(screen.getByRole('button', {name: 'Services map'}))

    expect(screen.getByPlaceholderText('Search services and dependencies...')).toBeInTheDocument()
    expect(screen.queryByText('Views')).not.toBeInTheDocument()
    expect(screen.getByTestId('service-map')).toBeInTheDocument()
  })

  it('renders container scope details and limit notice', async () => {
    const user = userEvent.setup()
    installInfrastructureHandlers({containerTotalCount: 750})

    renderWithQueryClient(<InfrastructureMap initialScope="containers" />)

    expect(await screen.findByText('Showing first 500 of 750')).toBeInTheDocument()

    const nodes = screen.getByTestId('map-nodes')
    await user.click(within(nodes).getByText(/api/))

    expect(screen.getAllByText('api').length).toBeGreaterThan(0)
    expect(screen.getAllByText('12.5% CPU').length).toBeGreaterThan(0)
    expect(screen.getByText(/128.0 MB/)).toBeInTheDocument()

    await user.click(screen.getByText('Pane'))

    expect(screen.queryByText('Details')).not.toBeInTheDocument()
  })

  it('shows the infrastructure fallback when resource data fails', async () => {
    installInfrastructureHandlers({failContainers: true})

    renderWithQueryClient(<InfrastructureMap initialScope="containers" />)

    expect(await screen.findByText('Map data unavailable')).toBeInTheDocument()
    expect(screen.getByText(/telemetry API responds/i)).toBeInTheDocument()
  })

  it('saves the current host map view through the backend API', async () => {
    const user = userEvent.setup()
    let savedPayload: Record<string, unknown> | undefined
    installInfrastructureHandlers({
      onSave: async (request) => {
        savedPayload = await request.json() as Record<string, unknown>
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

    renderWithQueryClient(<InfrastructureMap initialScope="hosts" />)

    expect(await screen.findByText(/web-1/)).toBeInTheDocument()

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
})

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
      })
    ),
    http.get(`${API_BASE}/v1/infra/map/saved-views`, () =>
      HttpResponse.json({views: []})
    ),
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
