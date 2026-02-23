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
import {describe, expect, it, vi} from 'vitest'
import {render, screen} from '@testing-library/react'
import userEvent from '@testing-library/user-event'
import {QueryClient, QueryClientProvider} from '@tanstack/react-query'
import {WidgetConfigPanel} from '../WidgetConfigPanel'
import type {DashboardWidget} from '@/lib/api'

vi.mock('@/lib/api', () => ({
  api: {
    getDataSources: vi.fn().mockResolvedValue([
      {
        name: 'events',
        label: 'Error Events',
        fields: [
          {name: 'timestamp', type: 'DateTime64', description: 'Event timestamp'},
          {name: 'level', type: 'String', description: 'Error level'},
          {name: 'duration_ms', type: 'Float64', description: 'Duration'},
        ],
      },
      {
        name: 'spans',
        label: 'Trace Spans',
        fields: [
          {name: 'timestamp', type: 'DateTime64', description: 'Span timestamp'},
          {name: 'duration_ms', type: 'Float64', description: 'Duration'},
        ],
      },
    ]),
    executeWidgetQuery: vi.fn().mockResolvedValue([]),
    executeBatchQuery: vi.fn().mockResolvedValue({results: {}}),
  },
}))

vi.mock('../WidgetRenderer', () => ({
  WidgetRenderer: () => <div data-testid="widget-renderer">Preview</div>,
}))

function renderWithQuery(ui: React.ReactElement) {
  const queryClient = new QueryClient({
    defaultOptions: {
      queries: {retry: false},
    },
  })
  return render(
    <QueryClientProvider client={queryClient}>
      {ui}
    </QueryClientProvider>
  )
}

const baseWidget: DashboardWidget = {
  id: 1,
  dashboard_id: 1,
  title: 'Test Widget',
  widget_type: 'timeseries',
  grid_x: 0,
  grid_y: 0,
  grid_w: 6,
  grid_h: 4,
  query_configs: [
    {
      dataSource: 'events',
      metrics: [{function: 'count', alias: 'errors'}],
      groupBy: [],
      filters: [],
      limit: 100,
      timeRange: {from: 'now-24h', to: 'now'},
      ref_id: 'A',
    },
  ],
  display_config: {},
  sort_order: 0,
}

describe('MultiQuery', () => {
  it('renders query tab A by default', () => {
    renderWithQuery(
      <WidgetConfigPanel
        widget={baseWidget}
        onSave={vi.fn()}
        onClose={vi.fn()}
        dashboardId={1}
        projectId={1}
      />
    )
    expect(screen.getByText('A')).toBeInTheDocument()
  })

  it('shows add query button', () => {
    renderWithQuery(
      <WidgetConfigPanel
        widget={baseWidget}
        onSave={vi.fn()}
        onClose={vi.fn()}
        dashboardId={1}
        projectId={1}
      />
    )
    // The + button for adding queries
    const addButtons = screen.getAllByTitle('Add query')
    expect(addButtons.length).toBeGreaterThan(0)
  })

  it('adds query B when + is clicked', async () => {
    const user = userEvent.setup()
    renderWithQuery(
      <WidgetConfigPanel
        widget={baseWidget}
        onSave={vi.fn()}
        onClose={vi.fn()}
        dashboardId={1}
        projectId={1}
      />
    )

    const addButton = screen.getByTitle('Add query')
    await user.click(addButton)

    expect(screen.getByText('B')).toBeInTheDocument()
  })

  it('removes query when trash icon is clicked', async () => {
    const user = userEvent.setup()
    const multiQueryWidget: DashboardWidget = {
      ...baseWidget,
      query_configs: [
        {...baseWidget.query_configs[0], ref_id: 'A'},
        {
          dataSource: 'spans',
          metrics: [{function: 'avg', field: 'duration_ms', alias: 'avg_dur'}],
          groupBy: [],
          filters: [],
          limit: 100,
          timeRange: {from: 'now-24h', to: 'now'},
          ref_id: 'B',
        },
      ],
    }

    renderWithQuery(
      <WidgetConfigPanel
        widget={multiQueryWidget}
        onSave={vi.fn()}
        onClose={vi.fn()}
        dashboardId={1}
        projectId={1}
      />
    )

    expect(screen.getByText('A')).toBeInTheDocument()
    expect(screen.getByText('B')).toBeInTheDocument()

    // Click on B tab to select it, then remove
    await user.click(screen.getByText('B'))
    const removeButton = screen.getByTitle('Remove query')
    await user.click(removeButton)

    expect(screen.queryByText('B')).not.toBeInTheDocument()
  })

  it('saves widget with multiple query configs', async () => {
    const user = userEvent.setup()
    const onSave = vi.fn()

    renderWithQuery(
      <WidgetConfigPanel
        widget={baseWidget}
        onSave={onSave}
        onClose={vi.fn()}
        dashboardId={1}
        projectId={1}
      />
    )

    // Add a second query
    await user.click(screen.getByTitle('Add query'))
    expect(screen.getByText('B')).toBeInTheDocument()

    // Save
    await user.click(screen.getByText('Save Widget'))

    expect(onSave).toHaveBeenCalledWith(
      expect.objectContaining({
        query_configs: expect.arrayContaining([
          expect.objectContaining({ref_id: 'A'}),
          expect.objectContaining({ref_id: 'B'}),
        ]),
      })
    )
  })

  it('limits to 10 queries maximum', async () => {
    const manyQueries = Array.from({length: 10}, (_, i) => ({
      ...baseWidget.query_configs[0],
      ref_id: String.fromCharCode(65 + i),
    }))

    renderWithQuery(
      <WidgetConfigPanel
        widget={{...baseWidget, query_configs: manyQueries}}
        onSave={vi.fn()}
        onClose={vi.fn()}
        dashboardId={1}
        projectId={1}
      />
    )

    // At max queries, add button should not exist
    expect(screen.queryByTitle('Add query')).not.toBeInTheDocument()
  })
})
