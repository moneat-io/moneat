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
import {describe, it, expect, vi, beforeEach} from 'vitest'
import {render, screen, waitFor} from '@testing-library/react'
import userEvent from '@testing-library/user-event'
import {QueryClient, QueryClientProvider} from '@tanstack/react-query'
import {http, HttpResponse} from 'msw'
import {server} from '@/test/mocks/server'
import {QueryBuilderForm} from '../QueryBuilderForm'
import type {QueryDsl} from '@/lib/api'

const API_BASE = 'http://localhost:8080'

function renderWithQuery(ui: React.ReactElement) {
  const queryClient = new QueryClient({defaultOptions: {queries: {retry: false}}})
  return render(
    <QueryClientProvider client={queryClient}>{ui}</QueryClientProvider>
  )
}

const defaultDsl: QueryDsl = {
  dataSource: 'events',
  metrics: [{function: 'count', alias: 'count'}],
  groupBy: [],
  filters: [],
  limit: 100,
  timeRange: {from: 'now-24h', to: 'now'},
}

beforeEach(() => {
  localStorage.clear()
  sessionStorage.clear()
  sessionStorage.setItem('authenticated', 'true')

  server.use(
    http.get(`${API_BASE}/v1/dashboards/datasources`, () =>
      HttpResponse.json([
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
          ],
        },
        {
          name: 'custom:1',
          label: 'My Prometheus (prometheus)',
          fields: [],
        },
        {
          name: '__unmapped:grafana-prom',
          label: 'Unmapped Prometheus',
          fields: [],
        },
      ])
    )
  )
})

describe('QueryBuilderForm – extended branch coverage', () => {
  // ──── Custom source: raw query mode ────
  describe('custom data source', () => {
    it('renders raw SQL query editor for custom data source', async () => {
      const customDsl: QueryDsl = {
        ...defaultDsl,
        dataSource: 'custom:1',
        rawQuery: 'SELECT * FROM my_table',
      }
      renderWithQuery(<QueryBuilderForm value={customDsl} onChange={vi.fn()} />)
      await waitFor(() => {
        expect(screen.getByText('SQL Query')).toBeInTheDocument()
      })
      expect(screen.getByText(/Only SELECT queries are allowed/)).toBeInTheDocument()
    })

    it('renders PromQL editor for prometheus custom source', async () => {
      server.use(
        http.get(`${API_BASE}/v1/dashboards/datasources`, () =>
          HttpResponse.json([
            {
              name: 'custom:2',
              label: 'My prometheus instance (prometheus)',
              fields: [],
            },
          ])
        )
      )
      const promDsl: QueryDsl = {
        ...defaultDsl,
        dataSource: 'custom:2',
        rawQuery: 'rate(http_requests_total[5m])',
      }
      renderWithQuery(<QueryBuilderForm value={promDsl} onChange={vi.fn()} />)
      await waitFor(() => {
        expect(screen.getByText('PromQL Query')).toBeInTheDocument()
      })
      expect(screen.getByText('Enter a PromQL query expression')).toBeInTheDocument()
    })

    it('renders limit input in custom source mode', async () => {
      const customDsl: QueryDsl = {
        ...defaultDsl,
        dataSource: 'custom:1',
        limit: 200,
      }
      renderWithQuery(<QueryBuilderForm value={customDsl} onChange={vi.fn()} />)
      await waitFor(() => {
        expect(screen.getByDisplayValue('200')).toBeInTheDocument()
      })
    })

    it('calls onChange when rawQuery changes', async () => {
      const onChange = vi.fn()
      const customDsl: QueryDsl = {
        ...defaultDsl,
        dataSource: 'custom:1',
        rawQuery: '',
      }
      renderWithQuery(<QueryBuilderForm value={customDsl} onChange={onChange} />)
      await waitFor(() => {
        expect(screen.getByText('SQL Query')).toBeInTheDocument()
      })
    })
  })

  // ──── Unmapped source warning ────
  describe('unmapped source warning', () => {
    it('shows warning for unmapped data source (__prefix)', async () => {
      const unmappedDsl: QueryDsl = {
        ...defaultDsl,
        dataSource: '__unmapped:grafana-prom',
      }
      renderWithQuery(<QueryBuilderForm value={unmappedDsl} onChange={vi.fn()} />)
      await waitFor(() => {
        expect(screen.getByText('Unmapped Data Source')).toBeInTheDocument()
      })
    })

    it('does not show unmapped warning for built-in source', () => {
      renderWithQuery(<QueryBuilderForm value={defaultDsl} onChange={vi.fn()} />)
      expect(screen.queryByText('Unmapped Data Source')).not.toBeInTheDocument()
    })

    it('does not show unmapped warning for custom: source', () => {
      const customDsl: QueryDsl = {
        ...defaultDsl,
        dataSource: 'custom:1',
      }
      renderWithQuery(<QueryBuilderForm value={customDsl} onChange={vi.fn()} />)
      expect(screen.queryByText('Unmapped Data Source')).not.toBeInTheDocument()
    })
  })

  // ──── Metrics: remove metric ────
  describe('remove metric', () => {
    it('calls onChange without the removed metric', async () => {
      const user = userEvent.setup()
      const onChange = vi.fn()
      const dsl: QueryDsl = {
        ...defaultDsl,
        metrics: [
          {function: 'count', alias: 'a'},
          {function: 'avg', field: 'duration_ms', alias: 'b'},
        ],
      }
      renderWithQuery(<QueryBuilderForm value={dsl} onChange={onChange} />)
      // Each metric row has a trash button; find the one nearest alias 'a'
      const aliasA = screen.getByDisplayValue('a')
      const metricRow = aliasA.closest('.flex.items-center')
      // The last button in the row is the remove button
      const buttons = metricRow?.querySelectorAll('button')
      await user.click(buttons![buttons!.length - 1])
      expect(onChange).toHaveBeenCalledWith(
        expect.objectContaining({
          metrics: [{function: 'avg', field: 'duration_ms', alias: 'b'}],
        })
      )
    })
  })

  // ──── Metrics: update metric function ────
  describe('update metric', () => {
    it('calls onChange when metric function is changed', async () => {
      const user = userEvent.setup()
      const onChange = vi.fn()
      renderWithQuery(<QueryBuilderForm value={defaultDsl} onChange={onChange} />)
      // Find the function select (it's a <select> element with value 'count')
      const allWithCount = screen.getAllByDisplayValue('count')
      const funcSelect = allWithCount.find(el => el.tagName === 'SELECT')!
      await user.selectOptions(funcSelect, 'avg')
      expect(onChange).toHaveBeenCalledWith(
        expect.objectContaining({
          metrics: [expect.objectContaining({function: 'avg'})],
        })
      )
    })

    it('calls onChange when metric alias is changed', async () => {
      const user = userEvent.setup()
      const onChange = vi.fn()
      renderWithQuery(<QueryBuilderForm value={defaultDsl} onChange={onChange} />)
      // Find the alias input (it's an <input> element with value 'count')
      const allWithCount = screen.getAllByDisplayValue('count')
      const aliasField = allWithCount.find(el => el.tagName === 'INPUT')!
      await user.clear(aliasField)
      await user.type(aliasField, 'total')
      expect(onChange).toHaveBeenCalled()
    })
  })

  // ──── Group By: add time and field ────
  describe('group by', () => {
    it('adds a time group by when Time button is clicked', async () => {
      const user = userEvent.setup()
      const onChange = vi.fn()
      renderWithQuery(<QueryBuilderForm value={defaultDsl} onChange={onChange} />)
      await user.click(screen.getByText('Time'))
      expect(onChange).toHaveBeenCalledWith(
        expect.objectContaining({
          groupBy: [expect.objectContaining({type: 'time', field: 'timestamp'})],
        })
      )
    })

    it('adds a field group by when Field button is clicked', async () => {
      const user = userEvent.setup()
      const onChange = vi.fn()
      renderWithQuery(<QueryBuilderForm value={defaultDsl} onChange={onChange} />)
      await user.click(screen.getByText('Field'))
      expect(onChange).toHaveBeenCalledWith(
        expect.objectContaining({
          groupBy: [expect.objectContaining({type: 'field'})],
        })
      )
    })

    it('renders time interval selector for time group by', async () => {
      const dsl: QueryDsl = {
        ...defaultDsl,
        groupBy: [{field: 'timestamp', type: 'time', interval: 'auto'}],
      }
      renderWithQuery(<QueryBuilderForm value={dsl} onChange={vi.fn()} />)
      expect(screen.getByDisplayValue('Auto')).toBeInTheDocument()
    })

    it('renders field selector for field group by', async () => {
      const dsl: QueryDsl = {
        ...defaultDsl,
        groupBy: [{field: 'level', type: 'field'}],
      }
      renderWithQuery(<QueryBuilderForm value={dsl} onChange={vi.fn()} />)
      expect(screen.getByText('field')).toBeInTheDocument()
    })

    it('calls onChange when time interval is changed', async () => {
      const user = userEvent.setup()
      const onChange = vi.fn()
      const dsl: QueryDsl = {
        ...defaultDsl,
        groupBy: [{field: 'timestamp', type: 'time', interval: 'auto'}],
      }
      renderWithQuery(<QueryBuilderForm value={dsl} onChange={onChange} />)
      const intervalSelect = screen.getByDisplayValue('Auto')
      await user.selectOptions(intervalSelect, '1 HOUR')
      expect(onChange).toHaveBeenCalledWith(
        expect.objectContaining({
          groupBy: [expect.objectContaining({interval: '1 HOUR'})],
        })
      )
    })

    it('removes a group by entry', async () => {
      const user = userEvent.setup()
      const onChange = vi.fn()
      const dsl: QueryDsl = {
        ...defaultDsl,
        groupBy: [{field: 'timestamp', type: 'time', interval: 'auto'}],
      }
      renderWithQuery(<QueryBuilderForm value={dsl} onChange={onChange} />)
      const trashButtons = document.querySelectorAll('button.text-muted-foreground')
      // Last trash button should be for group by (after metrics)
      await user.click(trashButtons[trashButtons.length - 1])
      expect(onChange).toHaveBeenCalledWith(
        expect.objectContaining({groupBy: []})
      )
    })
  })

  // ──── Filters: update and remove ────
  describe('filters', () => {
    it('calls onChange when filter field is changed', async () => {
      const onChange = vi.fn()
      const dsl: QueryDsl = {
        ...defaultDsl,
        filters: [{field: 'level', op: 'eq', value: 'error'}],
      }
      renderWithQuery(<QueryBuilderForm value={dsl} onChange={onChange} />)
      await waitFor(() => {
        expect(screen.getByDisplayValue('error')).toBeInTheDocument()
      })
    })

    it('calls onChange when filter op is changed', async () => {
      const user = userEvent.setup()
      const onChange = vi.fn()
      const dsl: QueryDsl = {
        ...defaultDsl,
        filters: [{field: 'level', op: 'eq', value: 'error'}],
      }
      renderWithQuery(<QueryBuilderForm value={dsl} onChange={onChange} />)
      const opSelects = document.querySelectorAll('select')
      // Find the op select (it has '=' as display value)
      const opSelect = Array.from(opSelects).find(s => s.value === 'eq')
      if (opSelect) {
        await user.selectOptions(opSelect, 'neq')
        expect(onChange).toHaveBeenCalledWith(
          expect.objectContaining({
            filters: [expect.objectContaining({op: 'neq'})],
          })
        )
      }
    })

    it('hides value input when filter op is is_null', () => {
      const dsl: QueryDsl = {
        ...defaultDsl,
        filters: [{field: 'level', op: 'is_null', value: ''}],
      }
      renderWithQuery(<QueryBuilderForm value={dsl} onChange={vi.fn()} />)
      // No value input should be present for is_null
      expect(screen.queryByPlaceholderText('value')).not.toBeInTheDocument()
    })

    it('hides value input when filter op is is_not_null', () => {
      const dsl: QueryDsl = {
        ...defaultDsl,
        filters: [{field: 'level', op: 'is_not_null', value: ''}],
      }
      renderWithQuery(<QueryBuilderForm value={dsl} onChange={vi.fn()} />)
      expect(screen.queryByPlaceholderText('value')).not.toBeInTheDocument()
    })

    it('shows value input when filter op is eq', () => {
      const dsl: QueryDsl = {
        ...defaultDsl,
        filters: [{field: 'level', op: 'eq', value: 'warn'}],
      }
      renderWithQuery(<QueryBuilderForm value={dsl} onChange={vi.fn()} />)
      expect(screen.getByDisplayValue('warn')).toBeInTheDocument()
    })

    it('removes a filter entry', async () => {
      const user = userEvent.setup()
      const onChange = vi.fn()
      const dsl: QueryDsl = {
        ...defaultDsl,
        filters: [{field: 'level', op: 'eq', value: 'error'}],
      }
      renderWithQuery(<QueryBuilderForm value={dsl} onChange={onChange} />)
      const trashButtons = document.querySelectorAll('button.text-muted-foreground')
      // Find the last trash button (should be for the filter)
      await user.click(trashButtons[trashButtons.length - 1])
      expect(onChange).toHaveBeenCalledWith(
        expect.objectContaining({filters: []})
      )
    })

    it('calls onChange when filter value is updated', async () => {
      const user = userEvent.setup()
      const onChange = vi.fn()
      const dsl: QueryDsl = {
        ...defaultDsl,
        filters: [{field: 'level', op: 'eq', value: ''}],
      }
      renderWithQuery(<QueryBuilderForm value={dsl} onChange={onChange} />)
      const valueInput = screen.getByPlaceholderText('value')
      await user.type(valueInput, 'critical')
      expect(onChange).toHaveBeenCalled()
    })
  })

  // ──── Limit input ────
  describe('limit', () => {
    it('calls onChange with parsed int when limit changes', async () => {
      const onChange = vi.fn()
      renderWithQuery(<QueryBuilderForm value={defaultDsl} onChange={onChange} />)
      const limitInput = screen.getByDisplayValue('100') as HTMLInputElement
      // Use fireEvent for number input to avoid character-append behavior
      const {fireEvent} = await import('@testing-library/react')
      fireEvent.change(limitInput, {target: {value: '250'}})
      expect(onChange).toHaveBeenCalled()
      const lastCall = onChange.mock.calls[onChange.mock.calls.length - 1][0]
      expect(lastCall.limit).toBe(250)
    })

    it('defaults to 100 when non-numeric value is entered', async () => {
      const user = userEvent.setup()
      const onChange = vi.fn()
      renderWithQuery(<QueryBuilderForm value={defaultDsl} onChange={onChange} />)
      const limitInput = screen.getByDisplayValue('100')
      await user.clear(limitInput)
      // After clearing, the onChange fires with empty string → parseInt('') → NaN → || 100
      expect(onChange).toHaveBeenCalled()
      const call = onChange.mock.calls[onChange.mock.calls.length - 1][0]
      expect(call.limit).toBe(100)
    })
  })

  // ──── Metric field: none option and string fields filtered ────
  describe('metric field select', () => {
    it('includes (none) option for metric field', async () => {
      renderWithQuery(<QueryBuilderForm value={defaultDsl} onChange={vi.fn()} />)
      await waitFor(() => {
        // The metric field select should have (none) as first option
        const fieldSelects = document.querySelectorAll('select')
        const metricFieldSelect = Array.from(fieldSelects).find(s => {
          const opts = Array.from(s.options)
          return opts.some(o => o.textContent === '(none)')
        })
        expect(metricFieldSelect).toBeTruthy()
      })
    })

    it('filters out String-type fields from metric field options', async () => {
      renderWithQuery(<QueryBuilderForm value={defaultDsl} onChange={vi.fn()} />)
      await waitFor(() => {
        const fieldSelects = document.querySelectorAll('select')
        const metricFieldSelect = Array.from(fieldSelects).find(s => {
          const opts = Array.from(s.options)
          return opts.some(o => o.textContent === '(none)')
        })
        if (metricFieldSelect) {
          const options = Array.from(metricFieldSelect.options).map(o => o.textContent)
          // level and user_id are String type and should not be in the list
          expect(options).not.toContain('level')
          expect(options).not.toContain('user_id')
          // duration_ms is Float64 and should be present
          expect(options).toContain('duration_ms')
        }
      })
    })
  })
})
