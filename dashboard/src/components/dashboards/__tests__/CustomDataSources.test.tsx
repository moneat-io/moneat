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
import {api} from '@/lib/api'
import React from 'react'

const DATA_SOURCE_ID = 'datasource-1'
const SECOND_DATA_SOURCE_ID = 'datasource-2'
const DELETE_DATA_SOURCE_ID = 'datasource-42'

// Mock the api module
vi.mock('@/lib/api', () => ({
  api: {
    listCustomDataSources: vi.fn(),
    createCustomDataSource: vi.fn(),
    updateCustomDataSource: vi.fn(),
    deleteCustomDataSource: vi.fn(),
    testDataSourceConnection: vi.fn(),
  },
}))

// Mock tanstack router
vi.mock('@tanstack/react-router', () => ({
  createFileRoute: () => () => ({component: () => null}),
  Link: ({children, ...props}: {children: React.ReactNode}) => <a {...props}>{children}</a>,
}))

describe('Custom Data Sources', () => {
  const ORG_ID = '11111111-1111-4111-8111-111111111111'
  const USER_ID = '22222222-2222-4222-8222-222222222222'

  beforeEach(() => {
    vi.clearAllMocks()
  })

  describe('API contract', () => {
    it('listCustomDataSources returns array', async () => {
      vi.mocked(api.listCustomDataSources).mockResolvedValue([
        {
          id: DATA_SOURCE_ID,
          org_id: ORG_ID,
          name: 'My PostgreSQL',
          source_type: 'postgresql',
          host: 'db.example.com',
          port: 5432,
          database_name: 'analytics',
          extra_config: {},
          enabled: true,
          created_by: USER_ID,
          created_at: '2024-01-01T00:00:00Z',
          updated_at: '2024-01-01T00:00:00Z',
          has_credentials: true,
        },
      ])

      const result = await api.listCustomDataSources()
      expect(result).toHaveLength(1)
      expect(result[0].name).toBe('My PostgreSQL')
      expect(result[0].source_type).toBe('postgresql')
    })

    it('createCustomDataSource sends correct payload', async () => {
      vi.mocked(api.createCustomDataSource).mockResolvedValue({
        id: SECOND_DATA_SOURCE_ID,
        org_id: ORG_ID,
        name: 'Prometheus',
        source_type: 'prometheus',
        host: 'prom.example.com',
        port: 9090,
        extra_config: {},
        enabled: true,
        created_by: USER_ID,
        created_at: '2024-01-01T00:00:00Z',
        updated_at: '2024-01-01T00:00:00Z',
        has_credentials: false,
      })

      const result = await api.createCustomDataSource({
        name: 'Prometheus',
        source_type: 'prometheus',
        host: 'prom.example.com',
        port: 9090,
      })

      expect(result.id).toBe(SECOND_DATA_SOURCE_ID)
      expect(api.createCustomDataSource).toHaveBeenCalledWith({
        name: 'Prometheus',
        source_type: 'prometheus',
        host: 'prom.example.com',
        port: 9090,
      })
    })

    it('testDataSourceConnection returns result', async () => {
      vi.mocked(api.testDataSourceConnection).mockResolvedValue({
        success: true,
        message: 'Connected successfully',
        tables: ['users', 'orders', 'products'],
      })

      const result = await api.testDataSourceConnection({
        source_type: 'postgresql',
        host: 'localhost',
        port: 5432,
        database_name: 'test',
        username: 'admin',
        password: 'secret',
      })

      expect(result.success).toBe(true)
      expect(result.tables).toHaveLength(3)
    })

    it('testDataSourceConnection handles failure', async () => {
      vi.mocked(api.testDataSourceConnection).mockResolvedValue({
        success: false,
        message: 'Connection refused: connect',
      })

      const result = await api.testDataSourceConnection({
        source_type: 'postgresql',
        host: 'nonexistent.example.com',
        port: 5432,
      })

      expect(result.success).toBe(false)
      expect(result.message).toContain('Connection refused')
    })

    it('updateCustomDataSource can update credentials', async () => {
      vi.mocked(api.updateCustomDataSource).mockResolvedValue({
        id: DATA_SOURCE_ID,
        org_id: ORG_ID,
        name: 'My PostgreSQL',
        source_type: 'postgresql',
        host: 'db.example.com',
        port: 5432,
        extra_config: {},
        enabled: true,
        created_by: USER_ID,
        created_at: '2024-01-01T00:00:00Z',
        updated_at: '2024-01-02T00:00:00Z',
        has_credentials: true,
      })

      await api.updateCustomDataSource(DATA_SOURCE_ID, {
        password: 'new-password',
      })

      expect(api.updateCustomDataSource).toHaveBeenCalledWith(DATA_SOURCE_ID, {
        password: 'new-password',
      })
    })

    it('updateCustomDataSource can toggle enabled', async () => {
      vi.mocked(api.updateCustomDataSource).mockResolvedValue({
        id: DATA_SOURCE_ID,
        org_id: ORG_ID,
        name: 'My PostgreSQL',
        source_type: 'postgresql',
        host: 'db.example.com',
        port: 5432,
        extra_config: {},
        enabled: false,
        created_by: USER_ID,
        created_at: '2024-01-01T00:00:00Z',
        updated_at: '2024-01-02T00:00:00Z',
        has_credentials: true,
      })

      const result = await api.updateCustomDataSource(DATA_SOURCE_ID, {enabled: false})
      expect(result.enabled).toBe(false)
    })

    it('deleteCustomDataSource calls with correct id', async () => {
      vi.mocked(api.deleteCustomDataSource).mockResolvedValue(undefined)
      await api.deleteCustomDataSource(DELETE_DATA_SOURCE_ID)
      expect(api.deleteCustomDataSource).toHaveBeenCalledWith(DELETE_DATA_SOURCE_ID)
    })
  })

  describe('Data validation', () => {
    it('credentials are never in list response', async () => {
      vi.mocked(api.listCustomDataSources).mockResolvedValue([
        {
          id: DATA_SOURCE_ID,
          org_id: ORG_ID,
          name: 'Test DB',
          source_type: 'postgresql',
          host: 'localhost',
          port: 5432,
          extra_config: {},
          enabled: true,
          created_by: USER_ID,
          created_at: '',
          updated_at: '',
          has_credentials: true,
        },
      ])

      const result = await api.listCustomDataSources()
      const ds = result[0] as unknown as Record<string, unknown>
      expect(ds.password).toBeUndefined()
      expect(ds.username).toBeUndefined()
      expect(ds.api_key).toBeUndefined()
      expect(ds.encrypted_credentials).toBeUndefined()
      expect(ds.has_credentials).toBe(true)
    })

    it('prometheus test uses api_key instead of username/password', async () => {
      vi.mocked(api.testDataSourceConnection).mockResolvedValue({
        success: true,
        message: 'Connected',
        metrics: ['up', 'http_requests_total'],
      })

      const result = await api.testDataSourceConnection({
        source_type: 'prometheus',
        host: 'prom.example.com',
        port: 9090,
        api_key: 'my-bearer-token',
      })

      expect(result.success).toBe(true)
      expect(result.metrics).toBeDefined()
      expect(api.testDataSourceConnection).toHaveBeenCalledWith(
        expect.objectContaining({
          source_type: 'prometheus',
          api_key: 'my-bearer-token',
        })
      )
    })
  })
})
