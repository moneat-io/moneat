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

import {describe, expect, it} from 'vitest'

import {
  buildLogMetricFilters,
  buildLogMetricGroupBy,
  buildLogMetricQuery,
  buildLogMetricWidget,
  defaultLogMetricTitle,
} from './logWidgetSeed'

describe('logWidgetSeed', () => {
  it('maps include/exclude facets and a custom level filter into FilterDefs', () => {
    const filters = buildLogMetricFilters(
      [
        {key: 'service', value: 'api'},
        {key: 'environment', value: 'staging', exclude: true},
      ],
      ['error', 'warn']
    )
    expect(filters).toEqual([
      {field: 'service', op: 'eq', value: 'api'},
      {field: 'environment', op: 'neq', value: 'staging'},
      {field: 'level', op: 'in', values: ['error', 'warn']},
    ])
  })

  it('omits the level filter when all levels are selected', () => {
    const filters = buildLogMetricFilters([], ['trace', 'debug', 'info', 'warn', 'error', 'fatal'])
    expect(filters).toEqual([])
  })

  it('always groups by time and appends the chosen field', () => {
    expect(buildLogMetricGroupBy('')).toEqual([{field: 'timestamp', type: 'time', interval: 'auto'}])
    expect(buildLogMetricGroupBy('service')).toEqual([
      {field: 'timestamp', type: 'time', interval: 'auto'},
      {field: 'service', type: 'field'},
    ])
  })

  it('builds a logs count query, keeping raw text and a custom time window', () => {
    const query = buildLogMetricQuery({
      query: '  timeout  ',
      levels: ['error'],
      facetFilters: [{key: 'service', value: 'api'}],
      groupByField: 'service',
      timeRange: {from: '2026-06-01T00:00:00.000Z', to: '2026-06-02T00:00:00.000Z'},
    })
    expect(query.dataSource).toBe('logs')
    expect(query.metrics).toEqual([{function: 'count', alias: 'count'}])
    expect(query.rawQuery).toBe('timeout')
    expect(query.timeRange).toEqual({from: '2026-06-01T00:00:00.000Z', to: '2026-06-02T00:00:00.000Z'})
    expect(query.ref_id).toBe('A')
  })

  it('falls back to a relative window and drops rawQuery when there is no free text', () => {
    const query = buildLogMetricQuery({
      query: '   ',
      levels: [],
      facetFilters: [],
      groupByField: '',
      timeRange: {from: '2026-06-01T00:00:00.000Z'},
    })
    expect(query.timeRange).toEqual({from: 'now-24h', to: 'now'})
    expect('rawQuery' in query).toBe(false)
  })

  it('derives a default title from the query', () => {
    expect(defaultLogMetricTitle('service:api')).toBe('Logs · service:api')
    expect(defaultLogMetricTitle('   ')).toBe('Log volume')
  })

  it('wraps the query in a timeseries widget with a fallback title', () => {
    const widget = buildLogMetricWidget({
      query: 'service:api',
      levels: [],
      facetFilters: [],
      groupByField: '',
      timeRange: {},
    })
    expect(widget.widget_type).toBe('timeseries')
    expect(widget.title).toBe('Logs · service:api')
    expect(widget.query_configs).toHaveLength(1)
    expect(widget.query_configs[0].dataSource).toBe('logs')
  })
})
