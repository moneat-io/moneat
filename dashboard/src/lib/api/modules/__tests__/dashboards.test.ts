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
const DASHBOARD_ID = 'dashboard-1'
const SECOND_DASHBOARD_ID = 'dashboard-2'
const NEW_DASHBOARD_ID = 'dashboard-3'
const ALERT_DASHBOARD_ID = 'dashboard-5'
const IMPORTED_DASHBOARD_ID = 'dashboard-10'
const TEMPLATE_CREATED_DASHBOARD_ID = 'dashboard-20'
const FOLDER_ID = 'folder-7'
const FOLDER_ONE_ID = 'folder-1'
const FOLDER_TWO_ID = 'folder-2'
const ALERT_ID = 'alert-2'
const DATA_SOURCE_ID = 'datasource-1'
const DATA_SOURCE_TWO_ID = 'datasource-2'
const DATA_SOURCE_THREE_ID = 'datasource-3'

describe('dashboardsMethods', () => {
  beforeEach(() => {
    localStorage.clear()
    sessionStorage.clear()
    sessionStorage.setItem('authenticated', 'true')
  })

  // ──── Dashboard CRUD ────

  describe('getDashboards', () => {
    it('fetches all dashboards without projectId', async () => {
      const mock = [{ id: DASHBOARD_ID, name: 'My Dashboard' }]
      server.use(
        http.get(`${API_BASE}/v1/dashboards`, () => {
          return HttpResponse.json(mock)
        })
      )
      const result = await api.getDashboards()
      expect(result).toEqual(mock)
    })

    it('fetches dashboards filtered by projectId', async () => {
      const mock = [{ id: SECOND_DASHBOARD_ID, name: 'Project Dashboard' }]
      server.use(
        http.get(`${API_BASE}/v1/dashboards`, ({ request }) => {
          const url = new URL(request.url)
          expect(url.searchParams.get('projectId')).toBe('proj-5')
          return HttpResponse.json(mock)
        })
      )
      const result = await api.getDashboards('proj-5')
      expect(result).toEqual(mock)
    })
  })

  describe('getDashboard', () => {
    it('fetches a single dashboard by id', async () => {
      const mock = { id: DASHBOARD_ID, name: 'Dashboard One' }
      server.use(
        http.get(`${API_BASE}/v1/dashboards/${DASHBOARD_ID}`, () => {
          return HttpResponse.json(mock)
        })
      )
      const result = await api.getDashboard(DASHBOARD_ID)
      expect(result).toEqual(mock)
    })
  })

  describe('createDashboard', () => {
    it('creates a new dashboard', async () => {
      const mock = { id: NEW_DASHBOARD_ID, name: 'New Dashboard' }
      server.use(
        http.post(`${API_BASE}/v1/dashboards`, async ({ request }) => {
          const body = (await request.json()) as Record<string, unknown>
          expect(body.title).toBe('New Dashboard')
          expect(body.widgets).toEqual([])
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
      const mock = { id: DASHBOARD_ID, name: 'Updated' }
      server.use(
        http.put(`${API_BASE}/v1/dashboards/${DASHBOARD_ID}`, async ({ request }) => {
          const body = (await request.json()) as Record<string, unknown>
          expect(body.name).toBe('Updated')
          return HttpResponse.json(mock)
        })
      )
      const result = await api.updateDashboard(DASHBOARD_ID, {
        name: 'Updated',
      } as Parameters<typeof api.updateDashboard>[1])
      expect(result).toEqual(mock)
    })
  })

  describe('deleteDashboard', () => {
    it('deletes a dashboard', async () => {
      server.use(
        http.delete(`${API_BASE}/v1/dashboards/${DASHBOARD_ID}`, () => {
          return new HttpResponse(null, { status: 204 })
        })
      )
      await api.deleteDashboard(DASHBOARD_ID)
    })
  })

  // ──── Favorites & Folders ────

  describe('toggleDashboardFavorite', () => {
    it('toggles favorite status', async () => {
      const mock = { is_favorited: true }
      server.use(
        http.post(`${API_BASE}/v1/dashboards/${DASHBOARD_ID}/favorite`, () => {
          return HttpResponse.json(mock)
        })
      )
      const result = await api.toggleDashboardFavorite(DASHBOARD_ID)
      expect(result).toEqual(mock)
    })
  })

  describe('duplicateDashboard', () => {
    it('duplicates a dashboard', async () => {
      const mock = { id: SECOND_DASHBOARD_ID, title: 'Dashboard (Copy)' }
      server.use(
        http.post(`${API_BASE}/v1/dashboards/${DASHBOARD_ID}/duplicate`, () => {
          return HttpResponse.json(mock, { status: 201 })
        })
      )
      const result = await api.duplicateDashboard(DASHBOARD_ID)
      expect(result).toEqual(mock)
    })
  })

  describe('setDefaultDashboard', () => {
    it('marks a dashboard as default', async () => {
      const mock = { is_default: true }
      server.use(
        http.post(`${API_BASE}/v1/dashboards/${DASHBOARD_ID}/default`, () => {
          return HttpResponse.json(mock)
        })
      )
      const result = await api.setDefaultDashboard(DASHBOARD_ID)
      expect(result).toEqual(mock)
    })
  })

  describe('moveDashboardToFolder', () => {
    it('moves dashboard to a folder', async () => {
      const mock = { folder_id: FOLDER_ID }
      server.use(
        http.put(`${API_BASE}/v1/dashboards/${DASHBOARD_ID}/folder`, async ({ request }) => {
          const body = (await request.json()) as Record<string, unknown>
          expect(body.folder_id).toBe(FOLDER_ID)
          return HttpResponse.json(mock)
        })
      )
      const result = await api.moveDashboardToFolder(DASHBOARD_ID, FOLDER_ID)
      expect(result).toEqual(mock)
    })

    it('moves dashboard out of a folder with null', async () => {
      const mock = { folder_id: null }
      server.use(
        http.put(`${API_BASE}/v1/dashboards/${SECOND_DASHBOARD_ID}/folder`, async ({ request }) => {
          const body = (await request.json()) as Record<string, unknown>
          expect(body.folder_id).toBeNull()
          return HttpResponse.json(mock)
        })
      )
      const result = await api.moveDashboardToFolder(SECOND_DASHBOARD_ID, null)
      expect(result).toEqual(mock)
    })
  })

  // ──── Dashboard Folders ────

  describe('getDashboardFolders', () => {
    it('fetches all folders', async () => {
      const mock = [{ id: FOLDER_ONE_ID, name: 'Folder A' }]
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
      const mock = { id: FOLDER_TWO_ID, name: 'New Folder' }
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
      const mock = { id: FOLDER_ONE_ID, name: 'Renamed' }
      server.use(
        http.put(
          `${API_BASE}/v1/dashboards/folders/${FOLDER_ONE_ID}`,
          async ({ request }) => {
            const body = (await request.json()) as Record<string, unknown>
            expect(body.name).toBe('Renamed')
            return HttpResponse.json(mock)
          }
        )
      )
      const result = await api.updateDashboardFolder(FOLDER_ONE_ID, {
        name: 'Renamed',
      } as Parameters<typeof api.updateDashboardFolder>[1])
      expect(result).toEqual(mock)
    })
  })

  describe('deleteDashboardFolder', () => {
    it('deletes a folder', async () => {
      server.use(
        http.delete(`${API_BASE}/v1/dashboards/folders/${FOLDER_ONE_ID}`, () => {
          return new HttpResponse(null, { status: 204 })
        })
      )
      await api.deleteDashboardFolder(FOLDER_ONE_ID)
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
      const queryConfig = {
        dataSource: 'events',
        metrics: [{function: 'count', alias: 'count'}],
        groupBy: [],
        filters: [],
        limit: 100,
        timeRange: {from: 'now-24h', to: 'now'},
      } satisfies Parameters<typeof api.executeWidgetQuery>[1]
      server.use(
        http.post(
          `${API_BASE}/v1/dashboards/${DASHBOARD_ID}/query`,
          async ({ request }) => {
            const url = new URL(request.url)
            expect(url.searchParams.get('projectId')).toBe('proj-10')
            const body = (await request.json()) as Record<string, unknown>
            expect(body.query_config).toEqual(queryConfig)
            return HttpResponse.json(mockRows)
          }
        )
      )
      const result = await api.executeWidgetQuery(
        DASHBOARD_ID,
        queryConfig,
        'proj-10'
      )
      expect(result).toEqual(mockRows)
    })

    it('includes time_range and variables when provided', async () => {
      const queryConfig = {
        dataSource: 'events',
        metrics: [{function: 'count', alias: 'count'}],
        groupBy: [],
        filters: [],
        limit: 100,
        timeRange: {from: 'now-24h', to: 'now'},
      } satisfies Parameters<typeof api.executeWidgetQuery>[1]
      const timeRange = {from: 'now-24h', to: 'now'} satisfies NonNullable<Parameters<typeof api.executeWidgetQuery>[3]>
      const variables = { env: 'production' }
      server.use(
        http.post(
          `${API_BASE}/v1/dashboards/${DASHBOARD_ID}/query`,
          async ({ request }) => {
            const body = (await request.json()) as Record<string, unknown>
            expect(body.time_range).toEqual(timeRange)
            expect(body.variables).toEqual(variables)
            return HttpResponse.json([])
          }
        )
      )
      await api.executeWidgetQuery(
        DASHBOARD_ID,
        queryConfig,
        'proj-10',
        timeRange,
        variables
      )
    })
  })

  describe('executeBatchQuery', () => {
    it('posts batch queries with projectId', async () => {
      const mockResult = { results: { q1: [{ count: 1 }] } }
      const queries = [{
        dataSource: 'events',
        metrics: [{function: 'count', alias: 'count'}],
        groupBy: [],
        filters: [],
        limit: 100,
        timeRange: {from: 'now-24h', to: 'now'},
        ref_id: 'q1',
      }] satisfies Parameters<typeof api.executeBatchQuery>[1]
      server.use(
        http.post(
          `${API_BASE}/v1/dashboards/${SECOND_DASHBOARD_ID}/query/batch`,
          async ({ request }) => {
            const url = new URL(request.url)
            expect(url.searchParams.get('projectId')).toBe('proj-3')
            const body = (await request.json()) as Record<string, unknown>
            expect(body.queries).toEqual(queries)
            return HttpResponse.json(mockResult)
          }
        )
      )
      const result = await api.executeBatchQuery(
        SECOND_DASHBOARD_ID,
        queries,
        'proj-3'
      )
      expect(result).toEqual(mockResult)
    })

    it('includes time_range and variables when provided', async () => {
      const queries = [{
        dataSource: 'events',
        metrics: [{function: 'count', alias: 'count'}],
        groupBy: [],
        filters: [],
        limit: 100,
        timeRange: {from: 'now-1h', to: 'now'},
        ref_id: 'q1',
      }] satisfies Parameters<typeof api.executeBatchQuery>[1]
      const timeRange = {from: 'now-1h', to: 'now'} satisfies NonNullable<Parameters<typeof api.executeBatchQuery>[3]>
      const variables = { region: 'us-east' }
      server.use(
        http.post(
          `${API_BASE}/v1/dashboards/${SECOND_DASHBOARD_ID}/query/batch`,
          async ({ request }) => {
            const body = (await request.json()) as Record<string, unknown>
            expect(body.time_range).toEqual(timeRange)
            expect(body.variables).toEqual(variables)
            return HttpResponse.json({ results: {} })
          }
        )
      )
      await api.executeBatchQuery(
        SECOND_DASHBOARD_ID,
        queries,
        'proj-3',
        timeRange,
        variables
      )
    })
  })

  describe('resolveVariableOptions', () => {
    it('resolves variable options for a dashboard', async () => {
      const mockOptions = { env: ['prod', 'staging'], region: ['us', 'eu'] }
      server.use(
        http.post(
          `${API_BASE}/v1/dashboards/${DASHBOARD_ID}/variables/resolve`,
          async ({ request }) => {
            const body = (await request.json()) as Record<string, unknown>
            expect(body.env).toBe('prod')
            return HttpResponse.json(mockOptions)
          }
        )
      )
      const result = await api.resolveVariableOptions(DASHBOARD_ID, { env: 'prod' })
      expect(result).toEqual(mockOptions)
    })
  })

  // ──── Import & Export ────

  describe('importDashboard', () => {
    it('imports a dashboard from JSON', async () => {
      const mock = { id: IMPORTED_DASHBOARD_ID, name: 'Imported', warnings: [] }
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
        http.get(`${API_BASE}/v1/dashboards/${DASHBOARD_ID}/export/json`, () => {
          return HttpResponse.json(mock)
        })
      )
      const result = await api.exportDashboard(DASHBOARD_ID, 'json')
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
    it('fetches dashboard template summaries', async () => {
      const mock = [{
        id: 'node-exporter-full',
        title: 'Node Exporter Full',
        description: 'Prebuilt Moneat dashboard for host telemetry.',
        category: 'infrastructure',
        tags: ['Infrastructure', 'Prometheus'],
        required_sources: ['Prometheus'],
        widget_count: 140,
        variable_count: 4,
        resource_path: 'dashboard-templates/community/node-exporter-full.json',
      }]
      server.use(
        http.get(`${API_BASE}/v1/dashboards/templates`, () => {
          return HttpResponse.json(mock)
        })
      )
      const result = await api.getDashboardTemplates()
      expect(result).toEqual(mock)
    })
  })

  describe('getDashboardTemplate', () => {
    it('fetches a dashboard template detail by id', async () => {
      const mock = {
        id: 'node-exporter-full',
        title: 'Node Exporter Full',
        description: 'Prebuilt Moneat dashboard for host telemetry.',
        category: 'infrastructure',
        tags: ['Infrastructure', 'Prometheus'],
        required_sources: ['Prometheus'],
        widget_count: 140,
        variable_count: 4,
        warnings: [],
        dashboard: {
          title: 'Node Exporter Full',
          widgets: [],
        },
      }
      server.use(
        http.get(`${API_BASE}/v1/dashboards/templates/node-exporter-full`, () => {
          return HttpResponse.json(mock)
        })
      )
      const result = await api.getDashboardTemplate('node-exporter-full')
      expect(result).toEqual(mock)
    })
  })

  describe('createDashboardFromTemplate', () => {
    it('creates a dashboard from a template', async () => {
      const mock = { id: TEMPLATE_CREATED_DASHBOARD_ID, title: 'Node Exporter Full' }
      server.use(
        http.post(
          `${API_BASE}/v1/dashboards/templates/node-exporter-full`,
          async ({ request }) => {
            const body = (await request.json()) as Record<string, unknown>
            expect(body.folder_id).toBe(FOLDER_ID)
            return HttpResponse.json(mock)
          }
        )
      )
      const result = await api.createDashboardFromTemplate(
        'node-exporter-full',
        {folder_id: FOLDER_ID}
      )
      expect(result).toEqual(mock)
    })
  })

  // ──── Dashboard Alerts ────

  describe('listDashboardAlerts', () => {
    it('lists alerts for a dashboard', async () => {
      const mock = [{ id: ALERT_ID, name: 'High Error Rate' }]
      server.use(
        http.get(`${API_BASE}/v1/dashboards/${ALERT_DASHBOARD_ID}/alerts`, () => {
          return HttpResponse.json(mock)
        })
      )
      const result = await api.listDashboardAlerts(ALERT_DASHBOARD_ID)
      expect(result).toEqual(mock)
    })
  })

  describe('createDashboardAlert', () => {
    it('creates an alert on a dashboard', async () => {
      const mock = { id: ALERT_ID, name: 'Latency Spike' }
      server.use(
        http.post(
          `${API_BASE}/v1/dashboards/${ALERT_DASHBOARD_ID}/alerts`,
          async ({ request }) => {
            const body = (await request.json()) as Record<string, unknown>
            expect(body.name).toBe('Latency Spike')
            return HttpResponse.json(mock)
          }
        )
      )
      const result = await api.createDashboardAlert(ALERT_DASHBOARD_ID, {
        name: 'Latency Spike',
      } as Parameters<typeof api.createDashboardAlert>[1])
      expect(result).toEqual(mock)
    })
  })

  describe('updateDashboardAlert', () => {
    it('updates an alert', async () => {
      const mock = { id: ALERT_ID, name: 'Updated Alert' }
      server.use(
        http.put(
          `${API_BASE}/v1/dashboards/${ALERT_DASHBOARD_ID}/alerts/${ALERT_ID}`,
          async ({ request }) => {
            const body = (await request.json()) as Record<string, unknown>
            expect(body.name).toBe('Updated Alert')
            return HttpResponse.json(mock)
          }
        )
      )
      const result = await api.updateDashboardAlert(ALERT_DASHBOARD_ID, ALERT_ID, {
        name: 'Updated Alert',
      } as Parameters<typeof api.updateDashboardAlert>[2])
      expect(result).toEqual(mock)
    })
  })

  describe('deleteDashboardAlert', () => {
    it('deletes an alert', async () => {
      server.use(
        http.delete(`${API_BASE}/v1/dashboards/${ALERT_DASHBOARD_ID}/alerts/${ALERT_ID}`, () => {
          return new HttpResponse(null, { status: 204 })
        })
      )
      await api.deleteDashboardAlert(ALERT_DASHBOARD_ID, ALERT_ID)
    })
  })

  // ──── Custom Data Sources ────

  describe('listCustomDataSources', () => {
    it('lists all custom data sources', async () => {
      const mock = [{ id: DATA_SOURCE_ID, name: 'My Postgres' }]
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
      const mock = { id: DATA_SOURCE_ID, name: 'My Postgres' }
      server.use(
        http.get(`${API_BASE}/v1/datasources/${DATA_SOURCE_ID}`, () => {
          return HttpResponse.json(mock)
        })
      )
      const result = await api.getCustomDataSource(DATA_SOURCE_ID)
      expect(result).toEqual(mock)
    })
  })

  describe('createCustomDataSource', () => {
    it('creates a custom data source', async () => {
      const mock = { id: DATA_SOURCE_TWO_ID, name: 'New DS' }
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
      const mock = { id: DATA_SOURCE_ID, name: 'Renamed DS' }
      server.use(
        http.put(`${API_BASE}/v1/datasources/${DATA_SOURCE_ID}`, async ({ request }) => {
          const body = (await request.json()) as Record<string, unknown>
          expect(body.name).toBe('Renamed DS')
          return HttpResponse.json(mock)
        })
      )
      const result = await api.updateCustomDataSource(DATA_SOURCE_ID, {
        name: 'Renamed DS',
      } as Parameters<typeof api.updateCustomDataSource>[1])
      expect(result).toEqual(mock)
    })
  })

  describe('deleteCustomDataSource', () => {
    it('deletes a custom data source', async () => {
      server.use(
        http.delete(`${API_BASE}/v1/datasources/${DATA_SOURCE_ID}`, () => {
          return new HttpResponse(null, { status: 204 })
        })
      )
      await api.deleteCustomDataSource(DATA_SOURCE_ID)
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
        http.get(`${API_BASE}/v1/datasources/${DATA_SOURCE_ID}/schema`, () => {
          return HttpResponse.json(mock)
        })
      )
      const result = await api.getDataSourceSchema(DATA_SOURCE_ID)
      expect(result).toEqual(mock)
    })
  })

  describe('queryCustomDataSource', () => {
    it('queries a custom data source and strips data_source_id', async () => {
      const mockRows = [{ id: 1, value: 'hello' }]
      server.use(
        http.post(
          `${API_BASE}/v1/datasources/${DATA_SOURCE_THREE_ID}/query`,
          async ({ request }) => {
            const body = (await request.json()) as Record<string, unknown>
            expect(body).not.toHaveProperty('data_source_id')
            expect(body.query).toBe('SELECT 1')
            return HttpResponse.json(mockRows)
          }
        )
      )
      const result = await api.queryCustomDataSource(DATA_SOURCE_THREE_ID, {
        data_source_id: DATA_SOURCE_THREE_ID,
        query: 'SELECT 1',
      })
      expect(result).toEqual(mockRows)
    })
  })
})
