import React from 'react'
import type {Node} from '@xyflow/react'
import {render, screen} from '@testing-library/react'
import {beforeEach, describe, expect, it, vi} from 'vitest'

const {mockFlow} = vi.hoisted(() => ({
  mockFlow: {
    fitView: vi.fn(),
    mountCount: 0,
    unmountCount: 0,
  },
}))

vi.mock('@xyflow/react', () => ({
  ReactFlow: ({children}: {children: React.ReactNode}) => {
    React.useEffect(() => {
      mockFlow.mountCount += 1
      return () => {
        mockFlow.unmountCount += 1
      }
    }, [])
    return <div data-testid="react-flow">{children}</div>
  },
  Controls: ({showInteractive}: {showInteractive?: boolean}) => (
    <div data-testid="flow-controls" data-show-interactive={String(showInteractive)} />
  ),
  Background: ({color, gap, size, variant}: {color?: string; gap?: number; size?: number; variant?: string}) => (
    <div
      data-testid="flow-background"
      data-color={color}
      data-gap={String(gap)}
      data-size={String(size)}
      data-variant={variant}
    />
  ),
  BackgroundVariant: {
    Dots: 'dots',
  },
  useReactFlow: () => ({fitView: mockFlow.fitView}),
}))

import {MapCanvas} from '../MapCanvas'
import {MapNodeCard} from '../MapNodeCard'

const nodes: Node[] = [
  {
    id: 'api',
    position: {x: 0, y: 0},
    data: {},
  },
]

describe('map primitives', () => {
  beforeEach(() => {
    vi.clearAllMocks()
    mockFlow.mountCount = 0
    mockFlow.unmountCount = 0
    vi.stubGlobal('requestAnimationFrame', (callback: FrameRequestCallback) => {
      callback(0)
      return 1
    })
    vi.stubGlobal('cancelAnimationFrame', vi.fn())
  })

  it('refits the map when the signature changes without remounting the flow', () => {
    const {rerender, container} = render(<MapCanvas nodes={nodes} fitSignature="services:2" />)

    expect(screen.getByTestId('flow-background')).toHaveAttribute('data-color', 'hsl(var(--viz-grid) / 0.1)')
    expect(container.firstElementChild).toHaveClass('bg-[hsl(var(--viz-surface))]')
    expect(mockFlow.mountCount).toBe(1)
    expect(mockFlow.unmountCount).toBe(0)
    expect(mockFlow.fitView).toHaveBeenLastCalledWith({padding: 0.25})

    rerender(<MapCanvas nodes={nodes} fitSignature="services:3" fitViewOptions={{padding: 0.4}} />)

    expect(mockFlow.mountCount).toBe(1)
    expect(mockFlow.unmountCount).toBe(0)
    expect(mockFlow.fitView).toHaveBeenLastCalledWith({padding: 0.4})
  })

  it('uses stable node-card chrome for selected and dimmed nodes', () => {
    const {container} = render(
      <MapNodeCard
        title="api"
        subtitle="checkout"
        selected
        dimmed
        dashed
        borderColor="rgb(1, 2, 3)"
        minWidth={180}
      />
    )
    const card = container.firstElementChild as HTMLElement

    expect(card).toHaveClass('border', 'ring-1', 'ring-primary', 'opacity-25', 'border-dashed')
    expect(card).not.toHaveClass('border-2', 'ring-2', 'scale-[1.02]', 'transition-all')
    expect(card).toHaveStyle({borderColor: 'rgb(1, 2, 3)', minWidth: '180px'})
  })
})
