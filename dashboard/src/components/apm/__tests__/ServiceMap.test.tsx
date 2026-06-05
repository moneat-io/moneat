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

import type {MouseEvent as ReactMouseEvent, ReactNode} from 'react'
import {fireEvent, screen, within} from '@testing-library/react'
import {beforeEach, describe, expect, it, vi} from 'vitest'
import {http, HttpResponse} from 'msw'
import {server} from '@/test/mocks/server'
import {clearAuthStorage, renderWithQueryClient} from '@/test/utils'
import {ServiceMap} from '../ServiceMap'

type MockMapNode = Readonly<{
  id: string
  data?: {
    label?: string
  }
}>

type MockMapCanvasProps = Readonly<{
  nodes: MockMapNode[]
  isEmpty?: boolean
  emptyTitle?: string
  fallback?: ReactNode
  children?: ReactNode
  onNodeClick?: (event: ReactMouseEvent, node: MockMapNode) => void
}>

vi.mock('@/components/maps/MapCanvas', () => ({
  MapCanvas: ({
    nodes,
    isEmpty = false,
    emptyTitle,
    fallback,
    children,
    onNodeClick,
  }: MockMapCanvasProps) => (
    <section>
      {fallback}
      {isEmpty && !fallback && <p>{emptyTitle}</p>}
      <div data-testid="map-nodes">
        {nodes.map((node) => (
          <button
            key={node.id}
            type="button"
            onClick={(event) => onNodeClick?.(event, node)}
          >
            {node.data?.label ?? node.id}
          </button>
        ))}
      </div>
      {children}
    </section>
  ),
}))

const API_BASE = 'http://localhost:8080'

describe('ServiceMap', () => {
  beforeEach(() => {
    clearAuthStorage()
  })

  it('filters services by node and downstream dependency search', async () => {
    server.use(
      http.get(`${API_BASE}/v1/services/map`, () =>
        HttpResponse.json({
          services: [
            serviceEntry('api-gateway', ['order-service', 'redis'], 120, 0),
            serviceEntry('order-service', ['postgres'], 82, 3),
            serviceEntry('user-service', [], 42, 0),
          ],
        })
      )
    )

    renderWithQueryClient(<ServiceMap searchQuery="order" />)

    await screen.findByText('api-gateway')
    const nodes = await screen.findByTestId('map-nodes')
    expect(within(nodes).getByText('api-gateway')).toBeInTheDocument()
    expect(within(nodes).getByText('order-service')).toBeInTheDocument()
    expect(within(nodes).getByText('redis')).toBeInTheDocument()
    expect(within(nodes).getByText('postgres')).toBeInTheDocument()
    expect(within(nodes).queryByText('user-service')).not.toBeInTheDocument()

    fireEvent.click(within(nodes).getByText('order-service'))

    expect(screen.getByText('82')).toBeInTheDocument()
    expect(screen.getByText('3')).toBeInTheDocument()
    expect(screen.getAllByText('postgres')).toHaveLength(2)
  })

  it('shows an explicit fallback when service map data cannot load', async () => {
    server.use(
      http.get(`${API_BASE}/v1/services/map`, () =>
        HttpResponse.json({error: 'unavailable'}, {status: 500})
      )
    )

    renderWithQueryClient(<ServiceMap />)

    expect(await screen.findByText('Map data unavailable')).toBeInTheDocument()
    expect(screen.getByText(/telemetry API responds/i)).toBeInTheDocument()
  })
})

function serviceEntry(
  service: string,
  callsTo: string[],
  spanCount: number,
  errorCount: number,
) {
  return {
    service,
    callsTo,
    spanCount,
    errorCount,
    avgDurationNs: 12_000_000,
  }
}
