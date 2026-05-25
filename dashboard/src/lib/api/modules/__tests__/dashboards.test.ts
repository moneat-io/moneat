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

import { describe, it, expect, beforeEach } from 'vitest'
import { http, HttpResponse } from 'msw'
import { server } from '@/test/mocks/server'
import { api } from '@/lib/api'

const API_BASE = 'http://localhost:8080'

describe('dashboardsMethods', () => {
  beforeEach(() => {
    localStorage.clear()
    sessionStorage.clear()
    sessionStorage.setItem('authenticated', 'true')
  })

  // ──── Dashboard CRUD ────

  describe('getDashboards', () => {
    it('fetches all dashboards without projectId', async () => {
      const mock = [{ id: 1, name: 'My Dashboard' }]
      server.use(
        http.get(`${API_BASE}/v1/dashboards`, () => {
          return HttpResponse.json(mock)
        })
      )
      const result = await api.getDashboards()
      expect(result).toEqual(mock)
    })

    it('fetches dashboards filtered by projectId', async () => {
      const mock = [{ id: 2, name: 'Project Dashboard' }]
      server.use(
        http.get(`${API_BASE}/v1/dashboards`, ({ request }) => {
          const url = new URL(request.url)
          expect(url.searchParams.get('projectId')).toBe('5')
          return HttpResponse.json(mock)
        })
      )
      const result = await api.getDashboards(5)
      expect(result).toEqual(mock)
    })
  })

  describe('getDashboard', () => {
    it('fetches a single dashboard by id', async () => {
      const mock = { id: 1, name: 'Dashboard One' }
      server.use(
        http.get(`${API_BASE}/v1/dashboards/1`, () => {
          return HttpResponse.json(mock)
        })
      )
      const result = await api.getDashboard(1)
      expect(result).toEqual(mock)
    })
  })

  describe('createDashboard', () => {
    it('creates a new dashboard', async () => {
      const mock = { id: 3, name: 'New Dashboard' }
      server.use(
        http.post(`${API_BASE}/v1/dashboards`, async ({ request }) => {
          const body = (await request.json()) as Record<string, unknown>
          expect(body.title).toBe('New Dashboard')
          return HttpResponse.json(mock)
        })
      )
      const result = await api.createDashboard({
        title: 'New Dashboard',
        widgets: [],
      })
      expect(result).toEqual(mock)
    })
  })

  describe('updateDashboard', () => {
    it('updates an existing dashboard', async () => {
      const mock = { id: 1, name: 'Updated' }
      server.use(
        http.put(`${API_BASE}/v1/dashboards/1`, async ({ request }) => {
          const body = (await request.json()) as Record<string, unknown>
          expect(body.name).toBe('Updated')
          return HttpResponse.json(mock)
        })
      )
      const result = await api.updateDashboard(1, {
        name: 'Updated',
      } as Parameters<typeof api.updateDashboard>[1])
      expect(result).toEqual(mock)
    })
  })

  describe('deleteDashboard', () => {
    it('deletes a dashboard', async () => {
      server.use(
        http.delete(`${API_BASE}/v1/dashboards/1`, () => {
          return new HttpResponse(null, { status: 204 })
        })
      )
      await api.deleteDashboard(1)
    })
  })

  // ──── Favorites & Folders ────

  describe('toggleDashboardFavorite', () => {
    it('toggles favorite status', async () => {
      const mock = { is_favorited: true }
      server.use(
        http.post(`${API_BASE}/v1/dashboards/1/favorite`, () => {
          return HttpResponse.json(mock)
        })
      )
      const result = await api.toggleDashboardFavorite(1)
      expect(result).toEqual(mock)
    })
  })

  describe('moveDashboardToFolder', () => {
    it('moves dashboard to a folder', async () => {
      const mock = { folder_id: 7 }
      server.use(
        http.put(`${API_BASE}/v1/dashboards/1/folder`, async ({ request }) => {
          const body = (await request.json()) as Record<string, unknown>
          expect(body.folder_id).toBe(7)
          return HttpResponse.json(mock)
        })
      )
      const result = await api.moveDashboardToFolder(1, 7)
      expect(result).toEqual(mock)
    })

    it('moves dashboard out of a folder with null', async () => {
      const mock = { folder_id: null }
      server.use(
        http.put(`${API_BASE}/v1/dashboards/2/folder`, async ({ request }) => {
          const body = (await request.json()) as Record<string, unknown>
          expect(body.folder_id).toBeNull()
          return HttpResponse.json(mock)
        })
      )
      const result = await api.moveDashboardToFolder(2, null)
      expect(result).toEqual(mock)
    })
  })

  // ──── Dashboard Folders ────

  describe('getDashboardFolders', () => {
    it('fetches all folders', async () => {
      const mock = [{ id: 1, name: 'Folder A' }]
      server.use(
        http.get(`${API_BASE}/v1/dashboards/folders`, () => {
          return HttpResponse.json(mock)
        })
      )
      const result = await api.getDashboardFolders()
      expect(result).toEqual(mock)
    })
  })

  describe('createDashboardFolder', () => {
    it('creates a new folder', async () => {
      const mock = { id: 2, name: 'New Folder' }
      server.use(
        http.post(
          `${API_BASE}/v1/dashboards/folders`,
          async ({ request }) => {
            const body = (await request.json()) as Record<string, unknown>
            expect(body.name).toBe('New Folder')
            return HttpResponse.json(mock)
          }
        )
      )
      const result = await api.createDashboardFolder({
        name: 'New Folder',
      } as Parameters<typeof api.createDashboardFolder>[0])
      expect(result).toEqual(mock)
    })
  })

  describe('updateDashboardFolder', () => {
    it('updates a folder', async () => {
      const mock = { id: 1, name: 'Renamed' }
      server.use(
        http.put(
          `${API_BASE}/v1/dashboards/folders/1`,
          async ({ request }) => {
            const body = (await request.json()) as Record<string, unknown>
            expect(body.name).toBe('Renamed')
            return HttpResponse.json(mock)
          }
        )
      )
      const result = await api.updateDashboardFolder(1, {
        name: 'Renamed',
      } as Parameters<typeof api.updateDashboardFolder>[1])
      expect(result).toEqual(mock)
    })
  })

  describe('deleteDashboardFolder', () => {
    it('deletes a folder', async () => {
      server.use(
        http.delete(`${API_BASE}/v1/dashboards/folders/1`, () => {
          return new HttpResponse(null, { status: 204 })
        })
      )
      await api.deleteDashboardFolder(1)
    })
  })

  // ──── Search ────

  describe('search', () => {
    it('searches with a query string', async () => {
      const mock = { dashboards: [], folders: [] }
      server.use(
        http.get(`${API_BASE}/v1/search`, ({ request }) => {
          const url = new URL(request.url)
          expect(url.searchParams.get('q')).toBe('my search')
          return HttpResponse.json(mock)
        })
      )
      const result = await api.search('my search')
      expect(result).toEqual(mock)
    })

    it('searches with empty query (no q param)', async () => {
      const mock = { dashboards: [], folders: [] }
      server.use(
        http.get(`${API_BASE}/v1/search`, ({ request }) => {
          const url = new URL(request.url)
          expect(url.searchParams.has('q')).toBe(false)
          return HttpResponse.json(mock)
        })
      )
      const result = await api.search('')
      expect(result).toEqual(mock)
    })
  })

  // ──── Query Execution ────

  describe('executeWidgetQuery', () => {
    it('posts a query config with projectId', async () => {
      const mockRows = [{ count: 42 }]
      const queryConfig = { source: 'events', columns: ['count(*)'] }
      server.use(
        http.post(
          `${API_BASE}/v1/dashboards/1/query`,
          async ({ request }) => {
            const url = new URL(request.url)
            expect(url.searchParams.get('projectId')).toBe('10')
            const body = (await request.json()) as Record<string, unknown>
            expect(body.query_config).toEqual(queryConfig)
            return HttpResponse.json(mockRows)
          }
        )
      )
      const result = await api.executeWidgetQuery(
        1,
        queryConfig as unknown as Parameters<typeof api.executeWidgetQuery>[1],
        10
      )
      expect(result).toEqual(mockRows)
    })

    it('includes time_range and variables when provided', async () => {
      const queryConfig = { source: 'events', columns: ['count(*)'] }
      const timeRange = { type: 'relative', value: '24h' }
      const variables = { env: 'production' }
      server.use(
        http.post(
          `${API_BASE}/v1/dashboards/1/query`,
          async ({ request }) => {
            const body = (await request.json()) as Record<string, unknown>
            expect(body.time_range).toEqual(timeRange)
            expect(body.variables).toEqual(variables)
            return HttpResponse.json([])
          }
        )
      )
      await api.executeWidgetQuery(
        1,
        queryConfig as unknown as Parameters<typeof api.executeWidgetQuery>[1],
        10,
        timeRange as unknown as Parameters<typeof api.executeWidgetQuery>[3],
        variables
      )
    })
  })

  describe('executeBatchQuery', () => {
    it('posts batch queries with projectId', async () => {
      const mockResult = { results: { q1: [{ count: 1 }] } }
      const queries = [{ id: 'q1', source: 'events', columns: ['count(*)'] }]
      server.use(
        http.post(
          `${API_BASE}/v1/dashboards/2/query/batch`,
          async ({ request }) => {
            const url = new URL(request.url)
            expect(url.searchParams.get('projectId')).toBe('3')
            const body = (await request.json()) as Record<string, unknown>
            expect(body.queries).toEqual(queries)
            return HttpResponse.json(mockResult)
          }
        )
      )
      const result = await api.executeBatchQuery(
        2,
        queries as unknown as Parameters<typeof api.executeBatchQuery>[1],
        3
      )
      expect(result).toEqual(mockResult)
    })

    it('includes time_range and variables when provided', async () => {
      const queries = [{ id: 'q1', source: 'events', columns: ['count(*)'] }]
      const timeRange = { type: 'relative', value: '1h' }
      const variables = { region: 'us-east' }
      server.use(
        http.post(
          `${API_BASE}/v1/dashboards/2/query/batch`,
          async ({ request }) => {
            const body = (await request.json()) as Record<string, unknown>
            expect(body.time_range).toEqual(timeRange)
            expect(body.variables).toEqual(variables)
            return HttpResponse.json({ results: {} })
          }
        )
      )
      await api.executeBatchQuery(
        2,
        queries as unknown as Parameters<typeof api.executeBatchQuery>[1],
        3,
        timeRange as unknown as Parameters<typeof api.executeBatchQuery>[3],
        variables
      )
    })
  })

  describe('resolveVariableOptions', () => {
    it('resolves variable options for a dashboard', async () => {
      const mockOptions = { env: ['prod', 'staging'], region: ['us', 'eu'] }
      server.use(
        http.post(
          `${API_BASE}/v1/dashboards/1/variables/resolve`,
          async ({ request }) => {
            const body = (await request.json()) as Record<string, unknown>
            expect(body.env).toBe('prod')
            return HttpResponse.json(mockOptions)
          }
        )
      )
      const result = await api.resolveVariableOptions(1, { env: 'prod' })
      expect(result).toEqual(mockOptions)
    })
  })

  // ──── Import & Export ────

  describe('importDashboard', () => {
    it('imports a dashboard from JSON', async () => {
      const mock = { id: 10, name: 'Imported', warnings: [] }
      server.use(
        http.post(`${API_BASE}/v1/dashboards/import`, async ({ request }) => {
          const body = (await request.json()) as Record<string, unknown>
          expect(body.format).toBe('grafana')
          expect(body.json).toBe('{"panels":[]}')
          return HttpResponse.json(mock)
        })
      )
      const result = await api.importDashboard('grafana', '{"panels":[]}')
      expect(result).toEqual(mock)
    })
  })

  describe('exportDashboard', () => {
    it('exports a dashboard in the given format', async () => {
      const mock = { panels: [] }
      server.use(
        http.get(`${API_BASE}/v1/dashboards/1/export/json`, () => {
          return HttpResponse.json(mock)
        })
      )
      const result = await api.exportDashboard(1, 'json')
      expect(result).toEqual(mock)
    })
  })

  // ──── Data Sources & Templates ────

  describe('getDataSources', () => {
    it('fetches available data sources', async () => {
      const mock = [{ name: 'clickhouse', label: 'ClickHouse' }]
      server.use(
        http.get(`${API_BASE}/v1/dashboards/datasources`, () => {
          return HttpResponse.json(mock)
        })
      )
      const result = await api.getDataSources()
      expect(result).toEqual(mock)
    })
  })

  describe('getDashboardTemplates', () => {
    it('fetches dashboard templates', async () => {
      const mock = [{ name: 'Error Overview' }]
      server.use(
        http.get(`${API_BASE}/v1/dashboards/templates`, () => {
          return HttpResponse.json(mock)
        })
      )
      const result = await api.getDashboardTemplates()
      expect(result).toEqual(mock)
    })
  })

  // ──── Dashboard Alerts ────

  describe('listDashboardAlerts', () => {
    it('lists alerts for a dashboard', async () => {
      const mock = [{ id: 1, name: 'High Error Rate' }]
      server.use(
        http.get(`${API_BASE}/v1/dashboards/5/alerts`, () => {
          return HttpResponse.json(mock)
        })
      )
      const result = await api.listDashboardAlerts(5)
      expect(result).toEqual(mock)
    })
  })

  describe('createDashboardAlert', () => {
    it('creates an alert on a dashboard', async () => {
      const mock = { id: 2, name: 'Latency Spike' }
      server.use(
        http.post(
          `${API_BASE}/v1/dashboards/5/alerts`,
          async ({ request }) => {
            const body = (await request.json()) as Record<string, unknown>
            expect(body.name).toBe('Latency Spike')
            return HttpResponse.json(mock)
          }
        )
      )
      const result = await api.createDashboardAlert(5, {
        name: 'Latency Spike',
      } as Parameters<typeof api.createDashboardAlert>[1])
      expect(result).toEqual(mock)
    })
  })

  describe('updateDashboardAlert', () => {
    it('updates an alert', async () => {
      const mock = { id: 2, name: 'Updated Alert' }
      server.use(
        http.put(
          `${API_BASE}/v1/dashboards/5/alerts/2`,
          async ({ request }) => {
            const body = (await request.json()) as Record<string, unknown>
            expect(body.name).toBe('Updated Alert')
            return HttpResponse.json(mock)
          }
        )
      )
      const result = await api.updateDashboardAlert(5, 2, {
        name: 'Updated Alert',
      } as Parameters<typeof api.updateDashboardAlert>[2])
      expect(result).toEqual(mock)
    })
  })

  describe('deleteDashboardAlert', () => {
    it('deletes an alert', async () => {
      server.use(
        http.delete(`${API_BASE}/v1/dashboards/5/alerts/2`, () => {
          return new HttpResponse(null, { status: 204 })
        })
      )
      await api.deleteDashboardAlert(5, 2)
    })
  })

  // ──── Custom Data Sources ────

  describe('listCustomDataSources', () => {
    it('lists all custom data sources', async () => {
      const mock = [{ id: 1, name: 'My Postgres' }]
      server.use(
        http.get(`${API_BASE}/v1/datasources`, () => {
          return HttpResponse.json(mock)
        })
      )
      const result = await api.listCustomDataSources()
      expect(result).toEqual(mock)
    })
  })

  describe('getCustomDataSource', () => {
    it('fetches a single custom data source', async () => {
      const mock = { id: 1, name: 'My Postgres' }
      server.use(
        http.get(`${API_BASE}/v1/datasources/1`, () => {
          return HttpResponse.json(mock)
        })
      )
      const result = await api.getCustomDataSource(1)
      expect(result).toEqual(mock)
    })
  })

  describe('createCustomDataSource', () => {
    it('creates a custom data source', async () => {
      const mock = { id: 2, name: 'New DS' }
      server.use(
        http.post(`${API_BASE}/v1/datasources`, async ({ request }) => {
          const body = (await request.json()) as Record<string, unknown>
          expect(body.name).toBe('New DS')
          return HttpResponse.json(mock)
        })
      )
      const result = await api.createCustomDataSource({
        name: 'New DS',
      } as Parameters<typeof api.createCustomDataSource>[0])
      expect(result).toEqual(mock)
    })
  })

  describe('updateCustomDataSource', () => {
    it('updates a custom data source', async () => {
      const mock = { id: 1, name: 'Renamed DS' }
      server.use(
        http.put(`${API_BASE}/v1/datasources/1`, async ({ request }) => {
          const body = (await request.json()) as Record<string, unknown>
          expect(body.name).toBe('Renamed DS')
          return HttpResponse.json(mock)
        })
      )
      const result = await api.updateCustomDataSource(1, {
        name: 'Renamed DS',
      } as Parameters<typeof api.updateCustomDataSource>[1])
      expect(result).toEqual(mock)
    })
  })

  describe('deleteCustomDataSource', () => {
    it('deletes a custom data source', async () => {
      server.use(
        http.delete(`${API_BASE}/v1/datasources/1`, () => {
          return new HttpResponse(null, { status: 204 })
        })
      )
      await api.deleteCustomDataSource(1)
    })
  })

  describe('testDataSourceConnection', () => {
    it('tests a data source connection', async () => {
      const mock = { success: true, message: 'Connected' }
      server.use(
        http.post(`${API_BASE}/v1/datasources/test`, async ({ request }) => {
          const body = (await request.json()) as Record<string, unknown>
          expect(body.host).toBe('db.example.com')
          return HttpResponse.json(mock)
        })
      )
      const result = await api.testDataSourceConnection({
        host: 'db.example.com',
      } as Parameters<typeof api.testDataSourceConnection>[0])
      expect(result).toEqual(mock)
    })
  })

  describe('getDataSourceSchema', () => {
    it('fetches schema for a data source', async () => {
      const mock = [
        { name: 'id', type: 'integer' },
        { name: 'name', type: 'string' },
      ]
      server.use(
        http.get(`${API_BASE}/v1/datasources/1/schema`, () => {
          return HttpResponse.json(mock)
        })
      )
      const result = await api.getDataSourceSchema(1)
      expect(result).toEqual(mock)
    })
  })

  describe('queryCustomDataSource', () => {
    it('queries a custom data source and strips data_source_id', async () => {
      const mockRows = [{ id: 1, value: 'hello' }]
      server.use(
        http.post(
          `${API_BASE}/v1/datasources/3/query`,
          async ({ request }) => {
            const body = (await request.json()) as Record<string, unknown>
            expect(body).not.toHaveProperty('data_source_id')
            expect(body.query).toBe('SELECT 1')
            return HttpResponse.json(mockRows)
          }
        )
      )
      const result = await api.queryCustomDataSource(3, {
        data_source_id: 3,
        query: 'SELECT 1',
      })
      expect(result).toEqual(mockRows)
    })
  })
})
