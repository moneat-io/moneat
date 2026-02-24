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
import {QueryBuilderForm} from '../QueryBuilderForm'
import type {QueryDsl} from '@/lib/api'

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
          {name: 'user_id', type: 'String', description: 'User ID'},
        ],
      },
      {
        name: 'spans',
        label: 'Spans',
        fields: [
          {name: 'timestamp', type: 'DateTime64', description: 'Span timestamp'},
          {name: 'duration_ms', type: 'Float64', description: 'Duration'},
          {name: 'description', type: 'String', description: 'Span description'},
        ],
      },
    ]),
  },
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

describe('QueryBuilderForm', () => {
  const defaultDsl: QueryDsl = {
    dataSource: 'events',
    metrics: [{function: 'count', alias: 'count'}],
    groupBy: [],
    filters: [],
    limit: 100,
    timeRange: {from: 'now-24h', to: 'now'},
  }

  it('renders data source section', () => {
    renderWithQuery(<QueryBuilderForm value={defaultDsl} onChange={vi.fn()} />)
    expect(screen.getByText('Data Source')).toBeInTheDocument()
  })

  it('renders metrics section', () => {
    renderWithQuery(<QueryBuilderForm value={defaultDsl} onChange={vi.fn()} />)
    expect(screen.getByText('Metrics')).toBeInTheDocument()
  })

  it('renders group by section', () => {
    renderWithQuery(<QueryBuilderForm value={defaultDsl} onChange={vi.fn()} />)
    expect(screen.getByText('Group By')).toBeInTheDocument()
  })

  it('renders filters section', () => {
    renderWithQuery(<QueryBuilderForm value={defaultDsl} onChange={vi.fn()} />)
    expect(screen.getByText('Filters')).toBeInTheDocument()
  })

  it('renders limit input with current value', () => {
    renderWithQuery(<QueryBuilderForm value={defaultDsl} onChange={vi.fn()} />)
    expect(screen.getByText('Limit')).toBeInTheDocument()
    const limitInput = screen.getByDisplayValue('100')
    expect(limitInput).toBeInTheDocument()
  })

  it('calls onChange when add metric button clicked', async () => {
    const user = userEvent.setup()
    const onChange = vi.fn()
    renderWithQuery(<QueryBuilderForm value={defaultDsl} onChange={onChange} />)
    // Find the Add button next to Metrics
    const addButtons = screen.getAllByText('Add')
    await user.click(addButtons[0]) // First add button is for metrics
    expect(onChange).toHaveBeenCalledWith(expect.objectContaining({
      metrics: expect.arrayContaining([
        expect.objectContaining({function: 'count'}),
      ]),
    }))
  })

  it('calls onChange when add filter button clicked', async () => {
    const user = userEvent.setup()
    const onChange = vi.fn()
    renderWithQuery(<QueryBuilderForm value={defaultDsl} onChange={onChange} />)
    const addButtons = screen.getAllByText('Add')
    await user.click(addButtons[1]) // Second add button is for filters
    expect(onChange).toHaveBeenCalledWith(expect.objectContaining({
      filters: expect.arrayContaining([
        expect.objectContaining({op: 'eq'}),
      ]),
    }))
  })

  it('calls onChange when limit value changes', async () => {
    const user = userEvent.setup()
    const onChange = vi.fn()
    renderWithQuery(<QueryBuilderForm value={defaultDsl} onChange={onChange} />)
    const limitInput = screen.getByDisplayValue('100')
    await user.clear(limitInput)
    await user.type(limitInput, '50')
    // onChange called for each keystroke
    expect(onChange).toHaveBeenCalled()
  })

  it('renders existing metrics', () => {
    const dsl: QueryDsl = {
      ...defaultDsl,
      metrics: [
        {function: 'count', alias: 'total'},
        {function: 'avg', field: 'duration_ms', alias: 'avg_dur'},
      ],
    }
    renderWithQuery(<QueryBuilderForm value={dsl} onChange={vi.fn()} />)
    expect(screen.getByDisplayValue('total')).toBeInTheDocument()
    expect(screen.getByDisplayValue('avg_dur')).toBeInTheDocument()
  })

  it('renders existing filters', () => {
    const dsl: QueryDsl = {
      ...defaultDsl,
      filters: [{field: 'level', op: 'eq', value: 'error'}],
    }
    renderWithQuery(<QueryBuilderForm value={dsl} onChange={vi.fn()} />)
    expect(screen.getByDisplayValue('error')).toBeInTheDocument()
  })

  it('renders existing group by entries', () => {
    const dsl: QueryDsl = {
      ...defaultDsl,
      groupBy: [
        {field: 'timestamp', type: 'time', interval: 'auto'},
        {field: 'level', type: 'field'},
      ],
    }
    renderWithQuery(<QueryBuilderForm value={dsl} onChange={vi.fn()} />)
    expect(screen.getByText('time')).toBeInTheDocument()
    expect(screen.getByText('field')).toBeInTheDocument()
  })
})
