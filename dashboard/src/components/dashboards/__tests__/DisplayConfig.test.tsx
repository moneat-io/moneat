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

import React from 'react'
import {describe, it, expect, vi} from 'vitest'
import {render, screen} from '@testing-library/react'
import userEvent from '@testing-library/user-event'
import {QueryClient, QueryClientProvider} from '@tanstack/react-query'
import {WidgetConfigPanel} from '../WidgetConfigPanel'
import type {DashboardWidget} from '@/lib/api'

vi.mock('@/lib/api', () => ({
  api: {
    getDataSources: vi.fn().mockResolvedValue([]),
    executeWidgetQuery: vi.fn().mockResolvedValue([]),
    executeBatchQuery: vi.fn().mockResolvedValue({results: {}}),
  },
}))

vi.mock('../WidgetRenderer', () => ({
  WidgetRenderer: () => <div data-testid="widget-renderer">Preview</div>,
}))

function renderWithQuery(ui: React.ReactElement) {
  const queryClient = new QueryClient({defaultOptions: {queries: {retry: false}}})
  return render(
    <QueryClientProvider client={queryClient}>
      {ui}
    </QueryClientProvider>
  )
}

function makeWidget(overrides: Partial<DashboardWidget> = {}): DashboardWidget {
  return {
    id: "widget-1",
    dashboard_id: "widget-1",
    title: 'Test',
    widget_type: 'timeseries',
    grid_x: 0,
    grid_y: 0,
    grid_w: 6,
    grid_h: 4,
    query_configs: [{
      dataSource: 'events',
      metrics: [{function: 'count', alias: 'count'}],
      groupBy: [],
      filters: [],
      limit: 100,
      timeRange: {from: 'now-24h', to: 'now'},
      ref_id: 'A',
    }],
    display_config: {},
    sort_order: 0,
    ...overrides,
  }
}

function switchToDisplayTab() {
  return userEvent.setup().click(screen.getByText('Display'))
}

describe('DisplayConfigForm - Legend', () => {
  it('shows legend config for timeseries widget', async () => {
    renderWithQuery(
      <WidgetConfigPanel
        widget={makeWidget({widget_type: 'timeseries'})}
        onSave={vi.fn()}
        onClose={vi.fn()}
        dashboardId="dashboard-1"
        projectId="proj-1"
      />
    )
    await switchToDisplayTab()

    expect(screen.getByText('Legend')).toBeInTheDocument()
    expect(screen.getByText('Mode')).toBeInTheDocument()
  })

  it('shows legend config for bar chart', async () => {
    renderWithQuery(
      <WidgetConfigPanel
        widget={makeWidget({widget_type: 'bar'})}
        onSave={vi.fn()}
        onClose={vi.fn()}
        dashboardId="dashboard-1"
        projectId="proj-1"
      />
    )
    await switchToDisplayTab()
    expect(screen.getByText('Legend')).toBeInTheDocument()
  })

  it('shows legend config for donut chart', async () => {
    renderWithQuery(
      <WidgetConfigPanel
        widget={makeWidget({widget_type: 'donut'})}
        onSave={vi.fn()}
        onClose={vi.fn()}
        dashboardId="dashboard-1"
        projectId="proj-1"
      />
    )
    await switchToDisplayTab()
    expect(screen.getByText('Legend')).toBeInTheDocument()
  })

  it('does not show legend config for stat widget', async () => {
    renderWithQuery(
      <WidgetConfigPanel
        widget={makeWidget({widget_type: 'stat'})}
        onSave={vi.fn()}
        onClose={vi.fn()}
        dashboardId="dashboard-1"
        projectId="proj-1"
      />
    )
    await switchToDisplayTab()
    expect(screen.queryByText('Legend')).not.toBeInTheDocument()
  })

  it('hides placement and values when legend mode is hidden', async () => {
    const user = userEvent.setup()
    renderWithQuery(
      <WidgetConfigPanel
        widget={makeWidget({widget_type: 'timeseries'})}
        onSave={vi.fn()}
        onClose={vi.fn()}
        dashboardId="dashboard-1"
        projectId="proj-1"
      />
    )
    await switchToDisplayTab()

    // Change mode to hidden
    const modeSelect = screen.getByDisplayValue('List')
    await user.selectOptions(modeSelect, 'hidden')

    expect(screen.queryByText('Placement')).not.toBeInTheDocument()
    expect(screen.queryByText('Values')).not.toBeInTheDocument()
  })
})

describe('DisplayConfigForm - Axes', () => {
  it('shows axis controls for timeseries', async () => {
    renderWithQuery(
      <WidgetConfigPanel
        widget={makeWidget({widget_type: 'timeseries'})}
        onSave={vi.fn()}
        onClose={vi.fn()}
        dashboardId="dashboard-1"
        projectId="proj-1"
      />
    )
    await switchToDisplayTab()
    expect(screen.getByText('Axes')).toBeInTheDocument()
    expect(screen.getByText('Y-Axis Label')).toBeInTheDocument()
    expect(screen.getByText('Scale')).toBeInTheDocument()
  })

  it('does not show axis controls for stat', async () => {
    renderWithQuery(
      <WidgetConfigPanel
        widget={makeWidget({widget_type: 'stat'})}
        onSave={vi.fn()}
        onClose={vi.fn()}
        dashboardId="dashboard-1"
        projectId="proj-1"
      />
    )
    await switchToDisplayTab()
    expect(screen.queryByText('Axes')).not.toBeInTheDocument()
  })
})

describe('DisplayConfigForm - Unit', () => {
  it('shows unit config for timeseries', async () => {
    renderWithQuery(
      <WidgetConfigPanel
        widget={makeWidget({widget_type: 'timeseries'})}
        onSave={vi.fn()}
        onClose={vi.fn()}
        dashboardId="dashboard-1"
        projectId="proj-1"
      />
    )
    await switchToDisplayTab()
    expect(screen.getByText('Unit')).toBeInTheDocument()
    expect(screen.getByText('Decimals')).toBeInTheDocument()
  })

  it('shows unit config for stat', async () => {
    renderWithQuery(
      <WidgetConfigPanel
        widget={makeWidget({widget_type: 'stat'})}
        onSave={vi.fn()}
        onClose={vi.fn()}
        dashboardId="dashboard-1"
        projectId="proj-1"
      />
    )
    await switchToDisplayTab()
    expect(screen.getByText('Unit')).toBeInTheDocument()
  })
})

describe('DisplayConfigForm - Style', () => {
  it('shows line styling options for timeseries', async () => {
    renderWithQuery(
      <WidgetConfigPanel
        widget={makeWidget({widget_type: 'timeseries'})}
        onSave={vi.fn()}
        onClose={vi.fn()}
        dashboardId="dashboard-1"
        projectId="proj-1"
      />
    )
    await switchToDisplayTab()
    expect(screen.getByText('Style')).toBeInTheDocument()
    expect(screen.getByText('Line Width')).toBeInTheDocument()
    expect(screen.getByText('Fill Opacity')).toBeInTheDocument()
    expect(screen.getByText('Interpolation')).toBeInTheDocument()
    expect(screen.getByText('Show Points')).toBeInTheDocument()
  })

  it('shows bar mode for bar charts', async () => {
    renderWithQuery(
      <WidgetConfigPanel
        widget={makeWidget({widget_type: 'bar'})}
        onSave={vi.fn()}
        onClose={vi.fn()}
        dashboardId="dashboard-1"
        projectId="proj-1"
      />
    )
    await switchToDisplayTab()
    expect(screen.getByText('Style')).toBeInTheDocument()
    expect(screen.getByText('Bar Mode')).toBeInTheDocument()
    expect(screen.queryByText('Line Width')).not.toBeInTheDocument()
  })
})

describe('DisplayConfigForm - Thresholds', () => {
  it('shows threshold controls for timeseries', async () => {
    renderWithQuery(
      <WidgetConfigPanel
        widget={makeWidget({widget_type: 'timeseries'})}
        onSave={vi.fn()}
        onClose={vi.fn()}
        dashboardId="dashboard-1"
        projectId="proj-1"
      />
    )
    await switchToDisplayTab()
    expect(screen.getByText('Thresholds')).toBeInTheDocument()
    expect(screen.getByText('Add threshold')).toBeInTheDocument()
  })

  it('adds a threshold row', async () => {
    const user = userEvent.setup()
    renderWithQuery(
      <WidgetConfigPanel
        widget={makeWidget({widget_type: 'timeseries'})}
        onSave={vi.fn()}
        onClose={vi.fn()}
        dashboardId="dashboard-1"
        projectId="proj-1"
      />
    )
    await switchToDisplayTab()

    await user.click(screen.getByText('Add threshold'))
    // A threshold row should appear with a number input
    const numberInputs = screen.getAllByPlaceholderText('Value')
    expect(numberInputs.length).toBeGreaterThanOrEqual(1)
  })

  it('saves thresholds in display_config', async () => {
    const user = userEvent.setup()
    const onSave = vi.fn()
    renderWithQuery(
      <WidgetConfigPanel
        widget={makeWidget({
          widget_type: 'stat',
          display_config: {thresholds: JSON.stringify([{value: 100, color: '#ef4444'}])},
        })}
        onSave={onSave}
        onClose={vi.fn()}
        dashboardId="dashboard-1"
        projectId="proj-1"
      />
    )
    await switchToDisplayTab()

    // A threshold row should already be present
    const numberInputs = screen.getAllByDisplayValue('100')
    expect(numberInputs.length).toBeGreaterThanOrEqual(1)

    // Save and check
    await user.click(screen.getByText('Save Widget'))
    expect(onSave).toHaveBeenCalledWith(
      expect.objectContaining({
        display_config: expect.objectContaining({
          thresholds: expect.stringContaining('"value":100'),
        }),
      })
    )
  })
})

describe('DisplayConfigForm - Value Mappings', () => {
  it('shows value mapping controls for stat widget', async () => {
    renderWithQuery(
      <WidgetConfigPanel
        widget={makeWidget({widget_type: 'stat'})}
        onSave={vi.fn()}
        onClose={vi.fn()}
        dashboardId="dashboard-1"
        projectId="proj-1"
      />
    )
    await switchToDisplayTab()
    expect(screen.getByText('Value Mappings')).toBeInTheDocument()
    expect(screen.getByText('Add mapping')).toBeInTheDocument()
  })

  it('shows value mapping controls for table widget', async () => {
    renderWithQuery(
      <WidgetConfigPanel
        widget={makeWidget({widget_type: 'table'})}
        onSave={vi.fn()}
        onClose={vi.fn()}
        dashboardId="dashboard-1"
        projectId="proj-1"
      />
    )
    await switchToDisplayTab()
    expect(screen.getByText('Value Mappings')).toBeInTheDocument()
  })

  it('does not show value mappings for timeseries', async () => {
    renderWithQuery(
      <WidgetConfigPanel
        widget={makeWidget({widget_type: 'timeseries'})}
        onSave={vi.fn()}
        onClose={vi.fn()}
        dashboardId="dashboard-1"
        projectId="proj-1"
      />
    )
    await switchToDisplayTab()
    expect(screen.queryByText('Value Mappings')).not.toBeInTheDocument()
  })

  it('adds a mapping row', async () => {
    const user = userEvent.setup()
    renderWithQuery(
      <WidgetConfigPanel
        widget={makeWidget({widget_type: 'stat'})}
        onSave={vi.fn()}
        onClose={vi.fn()}
        dashboardId="dashboard-1"
        projectId="proj-1"
      />
    )
    await switchToDisplayTab()

    await user.click(screen.getByText('Add mapping'))
    const displayTextInputs = screen.getAllByPlaceholderText('Display text')
    expect(displayTextInputs.length).toBeGreaterThanOrEqual(1)
  })
})
