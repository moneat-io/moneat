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

import {beforeEach, describe, expect, it, vi} from 'vitest'
import type {BatchQueryResult, QueryDsl, TimeRangeDef} from '@/lib/api'
import {api} from '@/lib/api'
import {fetchWidgetRows, mergeBatchQueryRows} from '../widgetRows'

vi.mock('@/lib/api', () => ({
  api: {
    executeWidgetQuery: vi.fn(),
    executeBatchQuery: vi.fn(),
  },
}))

const DASHBOARD_ID = '218f4ce4-3f2a-7a67-a32b-0c1848f62b9d'
const PROJECT_ID = '018f4ce4-3f2a-7a67-a32b-0c1848f62b9d'
const timeRange: TimeRangeDef = {from: 'now-1h', to: 'now'}
const variables = {service: 'checkout'}

const apiMock = vi.mocked(api)

beforeEach(() => {
  apiMock.executeWidgetQuery.mockReset()
  apiMock.executeBatchQuery.mockReset()
})

function query(refId: string, alias?: string | null): QueryDsl {
  return {
    dataSource: 'logs',
    metrics: [{function: 'count', alias}],
    groupBy: [],
    filters: [],
    limit: 100,
    timeRange,
    ref_id: refId,
  }
}

describe('WidgetRenderer row fetching', () => {
  it('skips API calls when the project or query list is missing', async () => {
    await expect(fetchWidgetRows({
      dashboardId: DASHBOARD_ID,
      queries: [query('A')],
      isBatch: false,
      timeRange,
    })).resolves.toEqual([])
    await expect(fetchWidgetRows({
      dashboardId: DASHBOARD_ID,
      projectId: PROJECT_ID,
      queries: [],
      isBatch: false,
      timeRange,
    })).resolves.toEqual([])

    expect(apiMock.executeWidgetQuery).not.toHaveBeenCalled()
    expect(apiMock.executeBatchQuery).not.toHaveBeenCalled()
  })

  it('delegates non-batch widgets to the single-query API', async () => {
    const rows = [{timestamp: '2026-06-10 12:00:00.000', count: 4}]
    apiMock.executeWidgetQuery.mockResolvedValue(rows)

    await expect(fetchWidgetRows({
      dashboardId: DASHBOARD_ID,
      projectId: PROJECT_ID,
      queries: [query('A', 'Errors')],
      isBatch: false,
      timeRange,
      variables,
    })).resolves.toEqual(rows)

    expect(apiMock.executeWidgetQuery).toHaveBeenCalledWith(
      DASHBOARD_ID,
      query('A', 'Errors'),
      PROJECT_ID,
      timeRange,
      variables
    )
  })

  it('merges single-query batch rows without renaming fields', async () => {
    apiMock.executeBatchQuery.mockResolvedValue({
      results: {
        A: [
          {timestamp: '2026-06-10 12:00:00.000', count: 4},
          {message: 'no timestamp', count: 2},
        ],
      },
    })

    await expect(fetchWidgetRows({
      dashboardId: DASHBOARD_ID,
      projectId: PROJECT_ID,
      queries: [query('A', 'Errors')],
      isBatch: true,
      timeRange,
      variables,
    })).resolves.toEqual([
      {timestamp: '2026-06-10 12:00:00.000', count: 4},
      {message: 'no timestamp', count: 2},
    ])

    expect(apiMock.executeBatchQuery).toHaveBeenCalledWith(
      DASHBOARD_ID,
      [query('A', 'Errors')],
      PROJECT_ID,
      timeRange,
      variables
    )
  })

  it('merges multi-query batch rows by timestamp and labels numeric series', () => {
    const result: BatchQueryResult = {
      results: {
        generated: [
          {timestamp: '2026-06-10 12:00:00.000', value: 4, status: 'critical'},
        ],
        B: [
          {timestamp: '2026-06-10 12:00:00.000', p95: 320},
          {value: 99},
        ],
      },
      metadata: {
        generated: {original_ref_id: 'A', query_index: 0},
        B: {query_index: 1},
      },
    }

    expect(mergeBatchQueryRows(result, [query('A', 'Errors'), query('B')])).toEqual([
      {
        timestamp: '2026-06-10 12:00:00.000',
        Errors: 4,
        'B: p95': 320,
      },
    ])
  })
})
