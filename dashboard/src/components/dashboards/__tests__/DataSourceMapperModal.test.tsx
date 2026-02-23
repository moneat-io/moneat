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

import {describe, it, expect, vi} from 'vitest'
import {render, screen, fireEvent} from '@testing-library/react'
import {DataSourceMapperModal} from '../DataSourceMapperModal'
import type {CreateWidgetRequest, DataSourceInfo} from '@/lib/api'

const mockDataSources: DataSourceInfo[] = [
  {name: 'events', label: 'Events', fields: []},
  {name: 'logs', label: 'Logs', fields: []},
  {name: 'custom:my-postgres', label: 'My PostgreSQL', fields: []},
]

const mockWidget: CreateWidgetRequest = {
  title: 'Pasted Panel',
  widget_type: 'stat',
  grid_x: 0,
  grid_y: 0,
  grid_w: 6,
  grid_h: 4,
  query_config: {
    dataSource: '__unmapped:mysql',
    metrics: [{function: 'count', alias: 'count'}],
    groupBy: [],
    filters: [],
    limit: 100,
    timeRange: {from: 'now-24h', to: 'now'},
  },
  display_config: {},
}

describe('DataSourceMapperModal', () => {
  it('renders nothing when closed', () => {
    const {container} = render(
      <DataSourceMapperModal
        open={false}
        widget={mockWidget}
        unknownSources={['mysql']}
        dataSources={mockDataSources}
        onConfirm={vi.fn()}
        onCancel={vi.fn()}
      />
    )
    expect(container.firstChild).toBeNull()
  })

  it('shows unknown datasource names when open', () => {
    render(
      <DataSourceMapperModal
        open={true}
        widget={mockWidget}
        unknownSources={['mysql']}
        dataSources={mockDataSources}
        onConfirm={vi.fn()}
        onCancel={vi.fn()}
      />
    )

    expect(screen.getByText('Unknown Data Source')).toBeTruthy()
    expect(screen.getByText('mysql')).toBeTruthy()
  })

  it('shows widget info', () => {
    render(
      <DataSourceMapperModal
        open={true}
        widget={mockWidget}
        unknownSources={['mysql']}
        dataSources={mockDataSources}
        onConfirm={vi.fn()}
        onCancel={vi.fn()}
      />
    )

    expect(screen.getByText('Pasted Panel')).toBeTruthy()
    expect(screen.getByText('(stat)')).toBeTruthy()
  })

  it('calls onCancel when cancel button clicked', () => {
    const onCancel = vi.fn()
    render(
      <DataSourceMapperModal
        open={true}
        widget={mockWidget}
        unknownSources={['mysql']}
        dataSources={mockDataSources}
        onConfirm={vi.fn()}
        onCancel={onCancel}
      />
    )

    fireEvent.click(screen.getByText('Cancel'))
    expect(onCancel).toHaveBeenCalledTimes(1)
  })

  it('calls onConfirm with unmapped datasource when skipped', () => {
    const onConfirm = vi.fn()
    render(
      <DataSourceMapperModal
        open={true}
        widget={mockWidget}
        unknownSources={['mysql']}
        dataSources={mockDataSources}
        onConfirm={onConfirm}
        onCancel={vi.fn()}
      />
    )

    // Default is to skip, so datasource should remain unmapped
    fireEvent.click(screen.getByText('Paste Widget'))
    expect(onConfirm).toHaveBeenCalledWith(
      expect.objectContaining({
        query_config: expect.objectContaining({
          dataSource: '__unmapped:mysql',  // Should remain unmapped when skipped
        }),
      })
    )
  })
})
