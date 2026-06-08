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

import {fireEvent, render, screen} from '@testing-library/react'
import type {ReactNode} from 'react'
import {beforeAll, beforeEach, describe, expect, it, vi} from 'vitest'
import {DashboardGrid} from '../DashboardGrid'
import type {DashboardWidget} from '@/lib/api'

type DashboardAlert = {
  widget_id: number
  enabled: boolean
  last_triggered_at: string | null
  alert_priority: string | null
}

type MockLayoutItem = {
  i: string
  x: number
  y: number
  w: number
  h: number
}

const queryState = vi.hoisted(() => ({
  alerts: [] as DashboardAlert[],
}))

vi.mock('@tanstack/react-query', () => ({
  useQuery: () => ({data: queryState.alerts}),
}))

vi.mock('react-grid-layout/legacy', () => ({
  Responsive: ({
    children,
    className,
    layouts,
    onLayoutChange,
  }: {
    children: ReactNode
    className?: string
    layouts?: {lg?: MockLayoutItem[]}
    onLayoutChange?: (layout: MockLayoutItem[]) => void
  }) => (
    <div data-testid="responsive-grid-layout" className={className}>
      <output data-testid="grid-layout">{JSON.stringify(layouts?.lg ?? [])}</output>
      <button
        type="button"
        onClick={() => onLayoutChange?.([
          {i: '1', x: 1, y: 4, w: 5, h: 8},
          {i: '2', x: 0, y: 0, w: 12, h: 1},
        ])}
      >
        move layout
      </button>
      {children}
    </div>
  ),
}))

vi.mock('../WidgetRenderer', () => ({
  WidgetRenderer: () => <div data-testid="widget-renderer" />,
}))

const widget: DashboardWidget = {
  id: 1,
  dashboard_id: 1,
  title: 'Dependency p95 check latency',
  widget_type: 'timeseries',
  grid_x: 0,
  grid_y: 0,
  grid_w: 6,
  grid_h: 6,
  query_configs: [],
  display_config: {},
  sort_order: 0,
}

const sectionWidget: DashboardWidget = {
  ...widget,
  id: 2,
  title: 'Ops Section',
  widget_type: 'section',
  grid_w: 12,
  grid_h: 1,
  display_config: {},
}

beforeAll(() => {
  globalThis.ResizeObserver = class ResizeObserver {
    disconnect() {}
    observe() {}
    unobserve() {}
  }
})

beforeEach(() => {
  queryState.alerts = []
})

describe('DashboardGrid', () => {
  it('keeps widget stacking inside the grid container', () => {
    const onLayoutChange = vi.fn()
    const {container} = render(
      <DashboardGrid
        widgets={[widget]}
        isEditing={false}
        dashboardId={1}
        timeRange={{from: 'now-24h', to: 'now'}}
        autoRefresh={false}
        onLayoutChange={onLayoutChange}
        onWidgetClick={vi.fn()}
        onWidgetDelete={vi.fn()}
      />
    )

    expect(container.firstElementChild).toHaveClass('relative', 'z-0', 'isolate', 'w-full')
    expect(screen.getByTestId('responsive-grid-layout')).toHaveClass('layout')
    expect(screen.getByText('Dependency p95 check latency')).toBeInTheDocument()
    fireEvent.click(screen.getByRole('button', {name: 'move layout'}))
    expect(onLayoutChange).not.toHaveBeenCalled()
  })

  it('shows the empty state outside edit mode', () => {
    render(
      <DashboardGrid
        widgets={[]}
        isEditing={false}
        dashboardId={1}
        timeRange={{from: 'now-24h', to: 'now'}}
        autoRefresh={false}
        onLayoutChange={vi.fn()}
        onWidgetClick={vi.fn()}
        onWidgetDelete={vi.fn()}
      />
    )

    expect(screen.getByText('This dashboard has no widgets yet. Click Edit to add some.')).toBeInTheDocument()
    expect(screen.queryByTestId('responsive-grid-layout')).not.toBeInTheDocument()
  })

  it('handles editable widget actions, alerts, and variable titles', () => {
    queryState.alerts = [{
      widget_id: 1,
      enabled: true,
      last_triggered_at: '2026-05-24T18:00:00Z',
      alert_priority: 'P0',
    }]
    const onWidgetClick = vi.fn()
    const onWidgetDelete = vi.fn()

    render(
      <DashboardGrid
        widgets={[{...widget, title: 'Latency $service ${env}'}]}
        isEditing={true}
        dashboardId={1}
        timeRange={{from: 'now-24h', to: 'now'}}
        autoRefresh={true}
        variableValues={{service: '$__all', env: 'prod'}}
        onLayoutChange={vi.fn()}
        onWidgetClick={onWidgetClick}
        onWidgetDelete={onWidgetDelete}
      />
    )

    expect(screen.getByText('Latency All prod')).toBeInTheDocument()
    expect(screen.getByTitle('Alert firing')).toBeInTheDocument()

    fireEvent.click(screen.getByTestId('widget-renderer').parentElement as HTMLElement)
    expect(onWidgetClick).toHaveBeenCalledWith(expect.objectContaining({id: 1}))

    fireEvent.click(screen.getByText('Edit'))
    expect(onWidgetClick).toHaveBeenCalledTimes(2)

    const buttons = screen.getAllByRole('button')
    fireEvent.click(buttons[buttons.length - 1])
    expect(onWidgetDelete).toHaveBeenCalledWith(1)
  })

  it('toggles collapsed sections and keeps section deletes separate', () => {
    const onWidgetDelete = vi.fn()
    render(
      <DashboardGrid
        widgets={[
          {...sectionWidget, display_config: {collapsed: 'true'}, sort_order: 0},
          {...widget, grid_y: 1, sort_order: 1},
        ]}
        isEditing={true}
        dashboardId={1}
        timeRange={{from: 'now-24h', to: 'now'}}
        autoRefresh={false}
        onLayoutChange={vi.fn()}
        onWidgetClick={vi.fn()}
        onWidgetDelete={onWidgetDelete}
      />
    )

    expect(screen.getByText('Ops Section')).toBeInTheDocument()
    expect(screen.queryByText('Dependency p95 check latency')).not.toBeInTheDocument()

    fireEvent.click(screen.getByRole('button', {name: 'Ops Section'}))
    expect(screen.getByText('Dependency p95 check latency')).toBeInTheDocument()

    const buttons = screen.getAllByRole('button')
    fireEvent.click(buttons[2])
    expect(onWidgetDelete).toHaveBeenCalledWith(2)
  })

  it('persists scaled layout changes from edit mode', () => {
    const onLayoutChange = vi.fn()
    render(
      <DashboardGrid
        widgets={[
          {...sectionWidget, sort_order: 0},
          {...widget, grid_y: 2, grid_h: 4, sort_order: 1},
        ]}
        isEditing={true}
        dashboardId={1}
        timeRange={{from: 'now-24h', to: 'now'}}
        autoRefresh={false}
        onLayoutChange={onLayoutChange}
        onWidgetClick={vi.fn()}
        onWidgetDelete={vi.fn()}
      />
    )

    const renderedLayout = JSON.parse(screen.getByTestId('grid-layout').textContent ?? '[]') as MockLayoutItem[]
    expect(renderedLayout).toEqual(expect.arrayContaining([
      expect.objectContaining({i: '2', h: 1}),
      expect.objectContaining({i: '1', y: 4, h: 8}),
    ]))

    fireEvent.click(screen.getByRole('button', {name: 'move layout'}))
    expect(onLayoutChange).toHaveBeenCalledWith([
      expect.objectContaining({id: 2, grid_y: 0, grid_h: 1}),
      expect.objectContaining({id: 1, grid_x: 1, grid_y: 2, grid_w: 5, grid_h: 4}),
    ])
  })
})
