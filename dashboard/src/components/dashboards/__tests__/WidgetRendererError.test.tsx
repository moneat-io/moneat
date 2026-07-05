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
import {screen} from '@testing-library/react'
import {renderWithQueryClient} from '@/test/utils'
import type {DashboardWidget, QueryDsl, TimeRangeDef} from '@/lib/api'
import {WidgetRenderer} from '../WidgetRenderer'
import {fetchWidgetRows} from '../widgetRows'

vi.mock('../widgetRows', async () => {
  const actual = await vi.importActual<typeof import('../widgetRows')>('../widgetRows')
  return {
    ...actual,
    fetchWidgetRows: vi.fn(),
  }
})

const fetchWidgetRowsMock = vi.mocked(fetchWidgetRows)

const timeRange: TimeRangeDef = {from: 'now-24h', to: 'now'}

beforeEach(() => {
  fetchWidgetRowsMock.mockReset()
})

function query(): QueryDsl {
  return {
    dataSource: 'custom:218f4ce4-3f2a-7a67-a32b-0c1848f62b9d',
    metrics: [],
    groupBy: [],
    filters: [],
    limit: 5000,
    timeRange,
    rawQuery: 'select * from missing_table',
  }
}

function widget(): DashboardWidget {
  return {
    id: 'widget-1',
    dashboard_id: 'dashboard-1',
    title: 'Broken query',
    widget_type: 'timeseries',
    grid_x: 0,
    grid_y: 0,
    grid_w: 8,
    grid_h: 6,
    query_configs: [query()],
    display_config: {},
    sort_order: 0,
  }
}

describe('WidgetRenderer query errors', () => {
  it('shows backend query error detail when a widget query fails', async () => {
    fetchWidgetRowsMock.mockRejectedValue(
      new Error('Data source query failed: ERROR: relation "missing_table" does not exist Position: 15')
    )

    renderWithQueryClient(
      <WidgetRenderer
        widget={widget()}
        dashboardId="dashboard-1"
        projectId="018f4ce4-3f2a-7a67-a32b-0c1848f62b9d"
        timeRange={timeRange}
        autoRefresh={false}
      />,
    )

    expect(await screen.findByText('Query error')).toBeInTheDocument()
    expect(screen.getByText(/relation "missing_table" does not exist/)).toBeInTheDocument()
  })
})
