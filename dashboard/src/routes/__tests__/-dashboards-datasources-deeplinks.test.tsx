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
import {clearAuthStorage, renderRoute} from '@/test/utils'
import type {CustomDataSourceResponse} from '@/lib/api'

const {mockApi, mockSearch} = vi.hoisted(() => ({
  mockSearch: {current: {} as {new?: number; edit?: string}},
  mockApi: {
    listCustomDataSources: vi.fn(),
    createCustomDataSource: vi.fn(),
    updateCustomDataSource: vi.fn(),
    deleteCustomDataSource: vi.fn(),
    testDataSourceConnection: vi.fn(),
  },
}))

vi.mock('@/lib/api', () => ({api: mockApi}))

vi.mock('@tanstack/react-router', () => ({
  createFileRoute: () => (options: Record<string, unknown>) => ({
    ...options,
    component: options.component,
    useSearch: () => mockSearch.current,
    useNavigate: () => () => Promise.resolve(),
  }),
}))

import {Route as DataSourcesRoute} from '../dashboards.datasources'

const DATA_SOURCE_ID = '00000000-0000-0000-0000-000000000001'

const DATA_SOURCES: readonly CustomDataSourceResponse[] = [
  {
    id: DATA_SOURCE_ID,
    org_id: '11111111-1111-4111-8111-111111111111',
    name: 'Prometheus prod',
    description: 'Primary metrics',
    source_type: 'prometheus',
    host: 'prom.prod',
    port: 9090,
    database_name: undefined,
    extra_config: {},
    enabled: true,
    created_by: '22222222-2222-4222-8222-222222222222',
    created_at: '2026-06-01T12:00:00Z',
    updated_at: '2026-06-02T12:00:00Z',
    has_credentials: false,
  },
]

describe('dashboard data source deep links', () => {
  beforeEach(() => {
    vi.clearAllMocks()
    clearAuthStorage()
    mockSearch.current = {}
    mockApi.listCustomDataSources.mockResolvedValue(DATA_SOURCES)
    mockApi.createCustomDataSource.mockResolvedValue(DATA_SOURCES[0])
    mockApi.updateCustomDataSource.mockResolvedValue(DATA_SOURCES[0])
    mockApi.deleteCustomDataSource.mockResolvedValue(undefined)
    mockApi.testDataSourceConnection.mockResolvedValue({success: true, message: 'OK'})
  })

  it('opens the connect dialog on the picker for new=1', async () => {
    mockSearch.current = {new: 1}

    renderRoute(DataSourcesRoute)

    expect(await screen.findByPlaceholderText(/Search 17 data sources/)).toBeInTheDocument()
    expect(screen.getByRole('button', {name: /PostgreSQL/})).toBeInTheDocument()
  })

  it('opens the selected source for edit deep links', async () => {
    mockSearch.current = {edit: DATA_SOURCE_ID}

    renderRoute(DataSourcesRoute)

    expect(await screen.findByDisplayValue('prom.prod')).toBeInTheDocument()
    expect(screen.getByDisplayValue('Prometheus prod')).toBeInTheDocument()
  })
})
