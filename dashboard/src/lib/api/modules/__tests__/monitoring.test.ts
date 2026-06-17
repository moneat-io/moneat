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
const HOST_ID_WEB = '11111111-1111-4111-8111-111111111111'
const HOST_ID_DB = '22222222-2222-4222-8222-222222222222'
const HOST_ALERT_ID_CPU = 'aaaaaaaa-aaaa-4aaa-8aaa-aaaaaaaaaaaa'
const HOST_ALERT_ID_MEMORY = 'bbbbbbbb-bbbb-4bbb-8bbb-bbbbbbbbbbbb'
const HOST_ALERT_ID_DISK = 'cccccccc-cccc-4ccc-8ccc-cccccccccccc'
const HOST_ALERT_ID_NETWORK = 'dddddddd-dddd-4ddd-8ddd-dddddddddddd'
const HOST_ALERT_ID_CREATED = 'eeeeeeee-eeee-4eee-8eee-eeeeeeeeeeee'
const ALERT_EPISODE_ID = '77777777-7777-4777-8777-777777777777'
const ORGANIZATION_ID = '99999999-9999-4999-8999-999999999999'
const CLOUD_SOURCE_ID = '55555555-5555-4555-8555-555555555555'
const INFRA_SAVED_VIEW_ID = '66666666-6666-4666-8666-666666666666'
const INFRA_SAVED_VIEW_DEFAULT_ID = '77777777-7777-4777-8777-777777777778'
const AGENT_API_KEY_ID = '88888888-8888-4888-8888-888888888888'
const AGENT_API_KEY_STAGING_ID = '88888888-8888-4888-8888-888888888889'
const AGENT_API_KEY_CREATED_ID = '88888888-8888-4888-8888-888888888890'

describe('Monitoring API module', () => {
  beforeEach(() => {
    localStorage.clear()
    sessionStorage.clear()
    sessionStorage.setItem('authenticated', 'true')
  })

  // ──── Events ────

  describe('getEvents', () => {
    it('fetches events without params', async () => {
      const mock = { events: [{ id: 1, title: 'high cpu' }], total: 1 }
      server.use(
        http.get(`${API_BASE}/v1/infra/events`, ({ request }) => {
          const url = new URL(request.url)
          expect(url.searchParams.has('alert_type')).toBe(false)
          return HttpResponse.json(mock)
        })
      )
      const result = await api.getEvents()
      expect(result).toEqual(mock)
    })

    it('passes query params when provided', async () => {
      server.use(
        http.get(`${API_BASE}/v1/infra/events`, ({ request }) => {
          const url = new URL(request.url)
          expect(url.searchParams.get('alert_type')).toBe('error')
          expect(url.searchParams.get('host')).toBe('web-1')
          expect(url.searchParams.get('limit')).toBe('10')
          expect(url.searchParams.get('offset')).toBe('5')
          return HttpResponse.json({ events: [], total: 0 })
        })
      )
      await api.getEvents({ alertType: 'error', host: 'web-1', limit: 10, offset: 5 })
    })
  })

  // ──── Service Checks ────

  describe('getServiceChecks', () => {
    it('fetches service checks without params', async () => {
      const mock = { checks: [{ id: 1 }], total: 1 }
      server.use(
        http.get(`${API_BASE}/v1/infra/service-checks`, () => HttpResponse.json(mock))
      )
      expect(await api.getServiceChecks()).toEqual(mock)
    })

    it('passes query params when provided', async () => {
      server.use(
        http.get(`${API_BASE}/v1/infra/service-checks`, ({ request }) => {
          const url = new URL(request.url)
          expect(url.searchParams.get('check_name')).toBe('cpu')
          expect(url.searchParams.get('host')).toBe('db-1')
          expect(url.searchParams.get('limit')).toBe('20')
          expect(url.searchParams.get('offset')).toBe('0')
          return HttpResponse.json({ checks: [], total: 0 })
        })
      )
      await api.getServiceChecks({ checkName: 'cpu', host: 'db-1', limit: 20, offset: 0 })
    })
  })

  // ──── Hosts ────

  describe('getHosts', () => {
    it('fetches all hosts', async () => {
      const mock = { hosts: [{ id: HOST_ID_WEB, name: 'web-1' }] }
      server.use(http.get(`${API_BASE}/v1/hosts`, () => HttpResponse.json(mock)))
      expect(await api.getHosts()).toEqual(mock)
    })
  })

  describe('getHost', () => {
    it('fetches a single host', async () => {
      const mock = { id: HOST_ID_WEB, name: 'web-1' }
      server.use(http.get(`${API_BASE}/v1/hosts/${HOST_ID_WEB}`, () => HttpResponse.json(mock)))
      expect(await api.getHost(HOST_ID_WEB)).toEqual(mock)
    })
  })

  describe('deleteHost', () => {
    it('deletes a host', async () => {
      server.use(
        http.delete(`${API_BASE}/v1/hosts/${HOST_ID_WEB}`, () => new HttpResponse(null, { status: 204 }))
      )
      await api.deleteHost(HOST_ID_WEB)
    })
  })

  describe('getHostMetrics', () => {
    it('fetches metrics without time range', async () => {
      const mock = { timestamps: [], cpu: [] }
      server.use(
        http.get(`${API_BASE}/v1/hosts/${HOST_ID_WEB}/metrics`, ({ request }) => {
          const url = new URL(request.url)
          expect(url.searchParams.has('from')).toBe(false)
          expect(url.searchParams.has('to')).toBe(false)
          return HttpResponse.json(mock)
        })
      )
      expect(await api.getHostMetrics(HOST_ID_WEB)).toEqual(mock)
    })

    it('passes from/to when provided', async () => {
      server.use(
        http.get(`${API_BASE}/v1/hosts/${HOST_ID_WEB}/metrics`, ({ request }) => {
          const url = new URL(request.url)
          expect(url.searchParams.get('from')).toBe('2024-01-01')
          expect(url.searchParams.get('to')).toBe('2024-01-02')
          return HttpResponse.json({ timestamps: [], cpu: [] })
        })
      )
      await api.getHostMetrics(HOST_ID_WEB, '2024-01-01', '2024-01-02')
    })
  })

  describe('getHostContainers', () => {
    it('fetches containers for a host', async () => {
      const mock = { containers: [{ name: 'nginx' }] }
      server.use(
        http.get(`${API_BASE}/v1/hosts/${HOST_ID_WEB}/containers`, () => HttpResponse.json(mock))
      )
      expect(await api.getHostContainers(HOST_ID_WEB)).toEqual(mock)
    })
  })

  // ──── Processes ────

  describe('getProcesses', () => {
    it('fetches processes without params', async () => {
      const mock = { processes: [], total: 0 }
      server.use(
        http.get(`${API_BASE}/v1/infra/processes`, () => HttpResponse.json(mock))
      )
      expect(await api.getProcesses()).toEqual(mock)
    })

    it('passes query params', async () => {
      server.use(
        http.get(`${API_BASE}/v1/infra/processes`, ({ request }) => {
          const url = new URL(request.url)
          expect(url.searchParams.get('host')).toBe('web-1')
          expect(url.searchParams.get('limit')).toBe('50')
          expect(url.searchParams.get('offset')).toBe('10')
          return HttpResponse.json({ processes: [], total: 0 })
        })
      )
      await api.getProcesses({ host: 'web-1', limit: 50, offset: 10 })
    })
  })

  // ──── Containers (infra) ────

  describe('getContainers', () => {
    it('fetches containers without params', async () => {
      const mock = { containers: [], total: 0 }
      server.use(
        http.get(`${API_BASE}/v1/infra/containers`, () => HttpResponse.json(mock))
      )
      expect(await api.getContainers()).toEqual(mock)
    })

    it('passes query params', async () => {
      server.use(
        http.get(`${API_BASE}/v1/infra/containers`, ({ request }) => {
          const url = new URL(request.url)
          expect(url.searchParams.get('host')).toBe('db-1')
          expect(url.searchParams.get('limit')).toBe('25')
          return HttpResponse.json({ containers: [], total: 0 })
        })
      )
      await api.getContainers({ host: 'db-1', limit: 25 })
    })
  })

  // ──── Infrastructure Map Saved Views ────

  describe('getInfrastructureMapSavedViews', () => {
    it('maps saved map views from backend response', async () => {
      server.use(
        http.get(`${API_BASE}/v1/infra/map/saved-views`, () =>
          HttpResponse.json({
            views: [
              {
                id: INFRA_SAVED_VIEW_ID,
                name: 'Production hosts',
                resource_kind: 'hosts',
                group_by: 'tag:env',
                fill_by: 'health',
                size_by: 'memory',
                search_query: 'prod',
                schema_version: 1,
                created_at: '2026-06-04T16:00:00Z',
                updated_at: '2026-06-04T16:05:00Z',
              },
            ],
          })
        )
      )

      const result = await api.getInfrastructureMapSavedViews()

      expect(result.views).toEqual([
        {
          id: INFRA_SAVED_VIEW_ID,
          name: 'Production hosts',
          resourceKind: 'hosts',
          groupBy: 'tag:env',
          fillBy: 'health',
          sizeBy: 'memory',
          searchQuery: 'prod',
          schemaVersion: 1,
          createdAt: '2026-06-04T16:00:00Z',
          updatedAt: '2026-06-04T16:05:00Z',
        },
      ])
    })

    it('maps camelCase saved map views and default state fallbacks', async () => {
      server.use(
        http.get(`${API_BASE}/v1/infra/map/saved-views`, () =>
          HttpResponse.json({
            views: [
              {
                id: 'camel-view',
                name: 'Container images',
                resourceKind: 'containers',
                groupBy: 'image',
                fillBy: 'lastSeen',
                sizeBy: 'network',
                searchQuery: 'redis',
                schemaVersion: 2,
                createdAt: '2026-06-04T16:15:00Z',
                updatedAt: '2026-06-04T16:20:00Z',
              },
              {
                id: INFRA_SAVED_VIEW_DEFAULT_ID,
                name: 'Defaults',
              },
            ],
          })
        )
      )

      const result = await api.getInfrastructureMapSavedViews()

      expect(result.views).toEqual([
        {
          id: 'camel-view',
          name: 'Container images',
          resourceKind: 'containers',
          groupBy: 'image',
          fillBy: 'lastSeen',
          sizeBy: 'network',
          searchQuery: 'redis',
          schemaVersion: 2,
          createdAt: '2026-06-04T16:15:00Z',
          updatedAt: '2026-06-04T16:20:00Z',
        },
        {
          id: INFRA_SAVED_VIEW_DEFAULT_ID,
          name: 'Defaults',
          resourceKind: 'hosts',
          groupBy: 'status',
          fillBy: 'health',
          sizeBy: 'uniform',
          searchQuery: '',
          schemaVersion: 1,
          createdAt: '',
          updatedAt: '',
        },
      ])
    })

    it('returns an empty saved map view list when response omits views', async () => {
      server.use(
        http.get(`${API_BASE}/v1/infra/map/saved-views`, () => HttpResponse.json({}))
      )

      await expect(api.getInfrastructureMapSavedViews()).resolves.toEqual({views: []})
    })
  })

  describe('saveInfrastructureMapView', () => {
    it('posts map view state to backend and maps response', async () => {
      server.use(
        http.post(`${API_BASE}/v1/infra/map/saved-views`, async ({ request }) => {
          const body = (await request.json()) as Record<string, unknown>
          expect(body).toEqual({
            name: 'Container load',
            resource_kind: 'containers',
            group_by: 'host',
            fill_by: 'cpu',
            size_by: 'cpu',
            search_query: 'api',
          })
          return HttpResponse.json({
            id: INFRA_SAVED_VIEW_ID,
            name: 'Container load',
            resource_kind: 'containers',
            group_by: 'host',
            fill_by: 'cpu',
            size_by: 'cpu',
            search_query: 'api',
            schema_version: 1,
            created_at: '2026-06-04T16:00:00Z',
            updated_at: '2026-06-04T16:10:00Z',
          })
        })
      )

      const result = await api.saveInfrastructureMapView({
        name: 'Container load',
        resourceKind: 'containers',
        groupBy: 'host',
        fillBy: 'cpu',
        sizeBy: 'cpu',
        searchQuery: 'api',
      })

      expect(result).toMatchObject({
        id: INFRA_SAVED_VIEW_ID,
        name: 'Container load',
        resourceKind: 'containers',
      })
    })
  })

  describe('deleteInfrastructureMapSavedView', () => {
    it('deletes a saved map view', async () => {
      server.use(
        http.delete(`${API_BASE}/v1/infra/map/saved-views/${INFRA_SAVED_VIEW_ID}`, () =>
          new HttpResponse(null, { status: 204 })
        )
      )
      await expect(api.deleteInfrastructureMapSavedView(INFRA_SAVED_VIEW_ID)).resolves.toBeUndefined()
    })
  })

  // ──── Connections ────

  describe('getConnections', () => {
    it('fetches connections without params', async () => {
      const mock = { connections: [], total: 0 }
      server.use(
        http.get(`${API_BASE}/v1/infra/connections`, () => HttpResponse.json(mock))
      )
      expect(await api.getConnections()).toEqual(mock)
    })

    it('passes query params', async () => {
      server.use(
        http.get(`${API_BASE}/v1/infra/connections`, ({ request }) => {
          const url = new URL(request.url)
          expect(url.searchParams.get('host')).toBe('app-1')
          expect(url.searchParams.get('limit')).toBe('100')
          expect(url.searchParams.get('offset')).toBe('0')
          return HttpResponse.json({ connections: [], total: 0 })
        })
      )
      await api.getConnections({ host: 'app-1', limit: 100, offset: 0 })
    })
  })

  // ──── Agent API Keys ────

  describe('getAgentApiKeys', () => {
    it('maps snake_case fields from server response', async () => {
      server.use(
        http.get(`${API_BASE}/v1/agent-api-keys`, () =>
          HttpResponse.json({
            keys: [
              {
                id: AGENT_API_KEY_ID,
                name: 'prod-key',
                key_prefix: 'mk_abc',
                created_at: '2024-01-01T00:00:00Z',
                last_used_at: '2024-06-01T00:00:00Z',
              },
            ],
          })
        )
      )
      const result = await api.getAgentApiKeys()
      expect(result.keys).toHaveLength(1)
      expect(result.keys[0]).toEqual({
        id: AGENT_API_KEY_ID,
        name: 'prod-key',
        keyPrefix: 'mk_abc',
        createdAt: '2024-01-01T00:00:00Z',
        lastUsedAt: '2024-06-01T00:00:00Z',
      })
    })

    it('maps camelCase fields from server response', async () => {
      server.use(
        http.get(`${API_BASE}/v1/agent-api-keys`, () =>
          HttpResponse.json({
            keys: [
              {
                id: AGENT_API_KEY_STAGING_ID,
                name: 'staging-key',
                keyPrefix: 'mk_def',
                createdAt: '2024-02-01T00:00:00Z',
              },
            ],
          })
        )
      )
      const result = await api.getAgentApiKeys()
      expect(result.keys[0].keyPrefix).toBe('mk_def')
      expect(result.keys[0].createdAt).toBe('2024-02-01T00:00:00Z')
      expect(result.keys[0].lastUsedAt).toBeUndefined()
    })
  })

  describe('createAgentApiKey', () => {
    it('posts with name in body', async () => {
      const mock = { id: AGENT_API_KEY_CREATED_ID, key: 'mk_full_key_value' }
      server.use(
        http.post(`${API_BASE}/v1/agent-api-keys`, async ({ request }) => {
          const body = (await request.json()) as Record<string, unknown>
          expect(body.name).toBe('new-key')
          return HttpResponse.json(mock)
        })
      )
      expect(await api.createAgentApiKey('new-key')).toEqual(mock)
    })
  })

  describe('deleteAgentApiKey', () => {
    it('deletes an agent API key', async () => {
      server.use(
        http.delete(`${API_BASE}/v1/agent-api-keys/${AGENT_API_KEY_ID}`, () =>
          new HttpResponse(null, { status: 204 })
        )
      )
      await api.deleteAgentApiKey(AGENT_API_KEY_ID)
    })
  })

  // ──── Cloud Sources ────

  describe('cloud source setup', () => {
    it('fetches setup preview by provider', async () => {
      server.use(
        http.get(`${API_BASE}/v1/cloud-sources/setup-preview`, ({ request }) => {
          const url = new URL(request.url)
          expect(url.searchParams.get('provider')).toBe('aws')
          return HttpResponse.json({
            provider: 'aws',
            externalId: 'mnt-ext-test',
            principal: 'arn:aws:iam::499432741914:role/MoneatCloudSource',
            snippetLabel: 'Trust policy',
            snippetLanguage: 'json',
            snippet: '{}',
          })
        })
      )

      const result = await api.getCloudSourceSetupPreview('aws')

      expect(result.externalId).toBe('mnt-ext-test')
    })

    it('creates a cloud source', async () => {
      server.use(
        http.post(`${API_BASE}/v1/cloud-sources`, async ({ request }) => {
          const body = (await request.json()) as Record<string, unknown>
          expect(body.provider).toBe('aws')
          expect(body.collectLogs).toBe(false)
          return HttpResponse.json({
            id: CLOUD_SOURCE_ID,
            provider: 'aws',
            displayName: 'Production AWS',
            status: 'healthy',
            config: body.config,
            collectMetrics: true,
            collectInventory: true,
            collectCost: true,
            collectLogs: false,
            externalId: 'mnt-ext-test',
            lastSyncAt: '2026-06-07T12:00:00Z',
            lastError: null,
            createdAt: '2026-06-07T12:00:00Z',
            updatedAt: '2026-06-07T12:00:00Z',
          }, {status: 201})
        })
      )

      const result = await api.createCloudSource({
        provider: 'aws',
        displayName: 'Production AWS',
        config: {accountId: '123456789012', roleName: 'MoneatIntegrationRole'},
        collectMetrics: true,
        collectInventory: true,
        collectCost: true,
        collectLogs: false,
      })

      expect(result.status).toBe('healthy')
    })

    it('lists, syncs, and deletes cloud sources', async () => {
      const source = {
        id: CLOUD_SOURCE_ID,
        provider: 'aws',
        displayName: 'Production AWS',
        status: 'healthy',
        config: {accountId: '123456789012'},
        collectMetrics: true,
        collectInventory: true,
        collectCost: false,
        collectLogs: false,
        externalId: 'mnt-ext-test',
        lastSyncAt: null,
        lastError: null,
        createdAt: '2026-06-07T12:00:00Z',
        updatedAt: '2026-06-07T12:00:00Z',
      }
      server.use(
        http.get(`${API_BASE}/v1/cloud-sources`, () => HttpResponse.json([source])),
        http.post(`${API_BASE}/v1/cloud-sources/${CLOUD_SOURCE_ID}/sync`, () => HttpResponse.json(source)),
        http.delete(`${API_BASE}/v1/cloud-sources/${CLOUD_SOURCE_ID}`, () => new HttpResponse(null, {status: 204}))
      )

      await expect(api.getCloudSources()).resolves.toEqual([source])
      await expect(api.syncCloudSource(CLOUD_SOURCE_ID)).resolves.toEqual(source)
      await expect(api.deleteCloudSource(CLOUD_SOURCE_ID)).resolves.toBeUndefined()
    })
  })

  // ──── Monitor Hosts ────

  describe('getMonitorHosts', () => {
    it('fetches all monitor hosts', async () => {
      const mock = [{ id: HOST_ID_WEB, hostname: 'web-1' }]
      server.use(
        http.get(`${API_BASE}/v1/monitor/hosts`, () => HttpResponse.json(mock))
      )
      expect(await api.getMonitorHosts()).toEqual(mock)
    })
  })

  describe('getMonitorHost', () => {
    it('fetches a single monitor host', async () => {
      const mock = { id: HOST_ID_DB, hostname: 'db-1' }
      server.use(
        http.get(`${API_BASE}/v1/monitor/hosts/${HOST_ID_DB}`, () => HttpResponse.json(mock))
      )
      expect(await api.getMonitorHost(HOST_ID_DB)).toEqual(mock)
    })
  })

  describe('getMonitorHostMetrics', () => {
    it('fetches metrics without optional params', async () => {
      const mock = { timestamps: [], cpu: [] }
      server.use(
        http.get(`${API_BASE}/v1/monitor/hosts/${HOST_ID_WEB}/metrics`, ({ request }) => {
          const url = new URL(request.url)
          expect(url.searchParams.has('from')).toBe(false)
          return HttpResponse.json(mock)
        })
      )
      expect(await api.getMonitorHostMetrics(HOST_ID_WEB)).toEqual(mock)
    })

    it('passes from, to, and interval', async () => {
      server.use(
        http.get(`${API_BASE}/v1/monitor/hosts/${HOST_ID_WEB}/metrics`, ({ request }) => {
          const url = new URL(request.url)
          expect(url.searchParams.get('from')).toBe('2024-01-01')
          expect(url.searchParams.get('to')).toBe('2024-01-02')
          expect(url.searchParams.get('interval')).toBe('5m')
          return HttpResponse.json({ timestamps: [], cpu: [] })
        })
      )
      await api.getMonitorHostMetrics(HOST_ID_WEB, '2024-01-01', '2024-01-02', '5m')
    })
  })

  // ──── Monitor Host Containers (field aliasing) ────

  describe('getMonitorHostContainers', () => {
    it('maps snake_case container fields', async () => {
      server.use(
        http.get(`${API_BASE}/v1/monitor/hosts/${HOST_ID_WEB}/containers`, () =>
          HttpResponse.json({
            containers: [
              {
                name: 'nginx',
                id: 'abc123',
                image: 'nginx:latest',
                status: 'running',
                cpu_percent: 12.5,
                mem_used: 1024,
                mem_limit: 4096,
                net_recv_bytes: 5000,
                net_sent_bytes: 3000,
              },
            ],
          })
        )
      )
      const result = await api.getMonitorHostContainers(HOST_ID_WEB)
      expect(result).toHaveLength(1)
      expect(result[0]).toEqual({
        name: 'nginx',
        id: 'abc123',
        image: 'nginx:latest',
        status: 'running',
        cpuPercent: 12.5,
        memUsed: 1024,
        memLimit: 4096,
        netRecvBytes: 5000,
        netSentBytes: 3000,
      })
    })

    it('maps camelCase container fields', async () => {
      server.use(
        http.get(`${API_BASE}/v1/monitor/hosts/${HOST_ID_DB}/containers`, () =>
          HttpResponse.json({
            containers: [
              {
                name: 'redis',
                id: 'def456',
                image: 'redis:7',
                status: 'running',
                cpuPercent: 5,
                memUsed: 512,
                memLimit: 2048,
                netRecvBytes: 100,
                netSentBytes: 200,
              },
            ],
          })
        )
      )
      const result = await api.getMonitorHostContainers(HOST_ID_DB)
      expect(result[0].cpuPercent).toBe(5)
      expect(result[0].memUsed).toBe(512)
    })
  })

  // ──── Container Metrics ────

  describe('getContainerMetrics', () => {
    it('fetches without optional params', async () => {
      const mock = { timestamps: [], cpu: [] }
      server.use(
        http.get(
          `${API_BASE}/v1/monitor/systems/sys-1/containers/nginx/metrics`,
          ({ request }) => {
            const url = new URL(request.url)
            expect(url.searchParams.has('from')).toBe(false)
            return HttpResponse.json(mock)
          }
        )
      )
      expect(await api.getContainerMetrics('sys-1', 'nginx')).toEqual(mock)
    })

    it('passes from, to, and interval', async () => {
      server.use(
        http.get(
          `${API_BASE}/v1/monitor/systems/sys-1/containers/nginx/metrics`,
          ({ request }) => {
            const url = new URL(request.url)
            expect(url.searchParams.get('from')).toBe('2024-01-01')
            expect(url.searchParams.get('to')).toBe('2024-01-02')
            expect(url.searchParams.get('interval')).toBe('1m')
            return HttpResponse.json({ timestamps: [], cpu: [] })
          }
        )
      )
      await api.getContainerMetrics('sys-1', 'nginx', '2024-01-01', '2024-01-02', '1m')
    })
  })

  // ──── Host Alert Config (complex field aliasing) ────

  describe('getHostAlertConfig', () => {
    it('maps snake_case alert config fields', async () => {
      server.use(
        http.get(`${API_BASE}/v1/monitor/hosts/${HOST_ID_WEB}/alerts/config`, () =>
          HttpResponse.json({
            scope: 'global',
            global_alerts: [
              {
                id: HOST_ALERT_ID_CPU,
                host_id: HOST_ID_WEB,
                scope: 'global',
                metric: 'cpu',
                condition: 'above',
                threshold: 90,
                duration_seconds: 300,
                enabled: true,
                alert_priority: 'P0',
                last_triggered_at: 1700000000,
                created_at: 1690000000,
              },
            ],
            host_alerts: [
              {
                id: HOST_ALERT_ID_MEMORY,
                host_id: HOST_ID_WEB,
                scope: 'host',
                metric: 'memory',
                condition: 'above',
                threshold: 80,
                duration_seconds: 60,
                enabled: false,
                alert_priority: null,
                created_at: 1690000000,
              },
            ],
            effective_alerts: [
              {
                id: HOST_ALERT_ID_CPU,
                scope: 'global',
                metric: 'cpu',
                condition: 'above',
                threshold: 90,
                duration_seconds: 300,
                enabled: true,
                alert_priority: 'P0',
                created_at: 1690000000,
              },
            ],
          })
        )
      )

      const config = await api.getHostAlertConfig(HOST_ID_WEB)
      expect(config.scope).toBe('global')
      expect(config.globalAlerts).toHaveLength(1)
      expect(config.globalAlerts[0]).toMatchObject({
        id: HOST_ALERT_ID_CPU,
        scope: 'global',
        metric: 'cpu',
        threshold: 90,
        durationSeconds: 300,
        enabled: true,
        alertPriority: 'P0',
        createdAt: 1690000000,
      })
      expect(config.hostAlerts).toHaveLength(1)
      expect(config.hostAlerts[0]).toMatchObject({
        id: HOST_ALERT_ID_MEMORY,
        scope: 'host',
        metric: 'memory',
        durationSeconds: 60,
        enabled: false,
      })
      expect(config.effectiveAlerts).toHaveLength(1)
      expect(config.effectiveAlerts[0].id).toBe(HOST_ALERT_ID_CPU)
    })

    it('maps camelCase alert config fields', async () => {
      server.use(
        http.get(`${API_BASE}/v1/monitor/hosts/${HOST_ID_DB}/alerts/config`, () =>
          HttpResponse.json({
            scope: 'host',
            globalAlerts: [],
            hostAlerts: [
              {
                id: HOST_ALERT_ID_DISK,
                hostId: HOST_ID_DB,
                scope: 'host',
                metric: 'disk',
                condition: 'above',
                threshold: 95,
                durationSeconds: 120,
                enabled: true,
                alertPriority: 'P1',
                createdAt: 1695000000,
              },
            ],
            effectiveAlerts: [],
          })
        )
      )

      const config = await api.getHostAlertConfig(HOST_ID_DB)
      expect(config.scope).toBe('host')
      expect(config.globalAlerts).toHaveLength(0)
      expect(config.hostAlerts[0]).toMatchObject({
        id: HOST_ALERT_ID_DISK,
        hostId: HOST_ID_DB,
        metric: 'disk',
        durationSeconds: 120,
        alertPriority: 'P1',
      })
    })

    it('falls back to system_alerts when host_alerts is absent', async () => {
      server.use(
        http.get(`${API_BASE}/v1/monitor/hosts/${HOST_ID_DB}/alerts/config`, () =>
          HttpResponse.json({
            scope: 'host',
            global_alerts: [],
            system_alerts: [
              {
                id: HOST_ALERT_ID_NETWORK,
                scope: 'host',
                metric: 'network',
                condition: 'above',
                threshold: 1000,
                duration_seconds: 0,
                enabled: true,
                alert_priority: null,
                created_at: 1690000000,
              },
            ],
            effective_alerts: [],
          })
        )
      )

      const config = await api.getHostAlertConfig(HOST_ID_DB)
      expect(config.hostAlerts).toHaveLength(1)
      expect(config.hostAlerts[0].metric).toBe('network')
    })
  })

  // ──── Host Alert CRUD ────

  describe('updateHostAlertScope', () => {
    it('puts the new scope', async () => {
      server.use(
        http.put(`${API_BASE}/v1/monitor/hosts/${HOST_ID_WEB}/alerts/scope`, async ({ request }) => {
          const body = (await request.json()) as Record<string, unknown>
          expect(body.scope).toBe('global')
          return new HttpResponse(null, { status: 204 })
        })
      )
      await api.updateHostAlertScope(HOST_ID_WEB, 'global')
    })
  })

  describe('createHostAlert', () => {
    it('posts alert and returns mapped result', async () => {
      server.use(
        http.post(`${API_BASE}/v1/monitor/hosts/${HOST_ID_WEB}/alerts`, async ({ request }) => {
          const url = new URL(request.url)
          expect(url.searchParams.get('scope')).toBe('host')
          const body = (await request.json()) as Record<string, unknown>
          expect(body.metric).toBe('cpu')
          return HttpResponse.json({
            id: HOST_ALERT_ID_CREATED,
            host_id: HOST_ID_WEB,
            scope: 'host',
            metric: 'cpu',
            condition: 'above',
            threshold: 90,
            duration_seconds: 300,
            enabled: true,
            alert_priority: null,
            created_at: 1700000000,
          })
        })
      )

      const alert = await api.createHostAlert(HOST_ID_WEB, {
        metric: 'cpu',
        condition: 'above',
        threshold: 90,
        durationSeconds: 300,
      })
      expect(alert.id).toBe(HOST_ALERT_ID_CREATED)
      expect(alert.durationSeconds).toBe(300)
      expect(alert.scope).toBe('host')
    })

    it('uses custom scope when provided', async () => {
      server.use(
        http.post(`${API_BASE}/v1/monitor/hosts/${HOST_ID_WEB}/alerts`, ({ request }) => {
          const url = new URL(request.url)
          expect(url.searchParams.get('scope')).toBe('global')
          return HttpResponse.json({
            id: HOST_ALERT_ID_MEMORY,
            scope: 'global',
            metric: 'memory',
            condition: 'above',
            threshold: 80,
            enabled: true,
            created_at: 1700000000,
          })
        })
      )
      const alert = await api.createHostAlert(
        HOST_ID_WEB,
        { metric: 'memory', condition: 'above', threshold: 80 },
        'global'
      )
      expect(alert.scope).toBe('global')
    })
  })

  describe('updateHostAlert', () => {
    it('puts updates and returns mapped result', async () => {
      server.use(
        http.put(`${API_BASE}/v1/monitor/hosts/${HOST_ID_WEB}/alerts/${HOST_ALERT_ID_CREATED}`, async ({ request }) => {
          const url = new URL(request.url)
          expect(url.searchParams.get('scope')).toBe('host')
          const body = (await request.json()) as Record<string, unknown>
          expect(body.threshold).toBe(95)
          return HttpResponse.json({
            id: HOST_ALERT_ID_CREATED,
            scope: 'host',
            metric: 'cpu',
            condition: 'above',
            threshold: 95,
            duration_seconds: 300,
            enabled: true,
            alert_priority: null,
            created_at: 1700000000,
          })
        })
      )
      const alert = await api.updateHostAlert(HOST_ID_WEB, HOST_ALERT_ID_CREATED, { threshold: 95 })
      expect(alert?.threshold).toBe(95)
    })

    it('returns undefined for a no-content update response', async () => {
      server.use(
        http.put(`${API_BASE}/v1/monitor/hosts/${HOST_ID_WEB}/alerts/${HOST_ALERT_ID_CREATED}`, async ({ request }) => {
          const body = (await request.json()) as Record<string, unknown>
          expect(body.enabled).toBe(false)
          return new HttpResponse(null, { status: 204 })
        })
      )

      const alert = await api.updateHostAlert(HOST_ID_WEB, HOST_ALERT_ID_CREATED, { enabled: false })
      expect(alert).toBeUndefined()
    })
  })

  describe('deleteHostAlert', () => {
    it('deletes with default scope', async () => {
      server.use(
        http.delete(`${API_BASE}/v1/monitor/hosts/${HOST_ID_WEB}/alerts/${HOST_ALERT_ID_CREATED}`, ({ request }) => {
          const url = new URL(request.url)
          expect(url.searchParams.get('scope')).toBe('host')
          return new HttpResponse(null, { status: 204 })
        })
      )
      await api.deleteHostAlert(HOST_ID_WEB, HOST_ALERT_ID_CREATED)
    })

    it('deletes with explicit global scope', async () => {
      server.use(
        http.delete(`${API_BASE}/v1/monitor/hosts/${HOST_ID_WEB}/alerts/${HOST_ALERT_ID_CREATED}`, ({ request }) => {
          const url = new URL(request.url)
          expect(url.searchParams.get('scope')).toBe('global')
          return new HttpResponse(null, { status: 204 })
        })
      )
      await api.deleteHostAlert(HOST_ID_WEB, HOST_ALERT_ID_CREATED, 'global')
    })
  })

  // ──── Alert Lifecycles ────

  describe('getAlertLifecycles', () => {
    it('passes lifecycle filters and returns episodes', async () => {
      server.use(
        http.get(`${API_BASE}/v1/alerts/lifecycles`, ({ request }) => {
          const url = new URL(request.url)
          expect(url.searchParams.get('status')).toBe('FIRING')
          expect(url.searchParams.get('limit')).toBe('25')
          return HttpResponse.json([
            {
              id: ALERT_EPISODE_ID,
              organization_id: ORGANIZATION_ID,
              source: 'HOST_ALERT',
              deduplication_key: 'host-1',
              episode_seq: 2,
              episode_key: 'host-1#2',
              status: 'FIRING',
              opened_at: '2026-06-02T12:00:00Z',
              last_seen_at: '2026-06-02T12:05:00Z',
              notification_count: 1,
              created_at: '2026-06-02T12:00:00Z',
              updated_at: '2026-06-02T12:05:00Z',
            },
          ])
        })
      )

      const result = await api.getAlertLifecycles({status: 'FIRING', limit: 25})
      expect(result[0].episode_key).toBe('host-1#2')
      expect(result[0].notification_count).toBe(1)
    })
  })

  describe('ignoreAlertLifecycle', () => {
    it('posts an ignore reason', async () => {
      server.use(
        http.post(`${API_BASE}/v1/alerts/lifecycles/${ALERT_EPISODE_ID}/ignore`, async ({ request }) => {
          const body = (await request.json()) as Record<string, unknown>
          expect(body.reason).toBe('Investigating')
          return HttpResponse.json({
            id: ALERT_EPISODE_ID,
            organization_id: ORGANIZATION_ID,
            source: 'HOST_ALERT',
            deduplication_key: 'host-1',
            episode_seq: 1,
            episode_key: 'host-1#1',
            status: 'FIRING',
            opened_at: '2026-06-02T12:00:00Z',
            last_seen_at: '2026-06-02T12:00:00Z',
            notification_count: 1,
            suppressed_at: '2026-06-02T12:10:00Z',
            suppress_reason: 'Investigating',
            created_at: '2026-06-02T12:00:00Z',
            updated_at: '2026-06-02T12:10:00Z',
          })
        })
      )

      const result = await api.ignoreAlertLifecycle(ALERT_EPISODE_ID, 'Investigating')
      expect(result.suppress_reason).toBe('Investigating')
    })
  })

  describe('unignoreAlertLifecycle', () => {
    it('posts to unignore an episode', async () => {
      server.use(
        http.post(`${API_BASE}/v1/alerts/lifecycles/${ALERT_EPISODE_ID}/unignore`, () =>
          HttpResponse.json({
            id: ALERT_EPISODE_ID,
            organization_id: ORGANIZATION_ID,
            source: 'HOST_ALERT',
            deduplication_key: 'host-1',
            episode_seq: 1,
            episode_key: 'host-1#1',
            status: 'FIRING',
            opened_at: '2026-06-02T12:00:00Z',
            last_seen_at: '2026-06-02T12:00:00Z',
            notification_count: 1,
            suppressed_at: null,
            suppress_reason: null,
            created_at: '2026-06-02T12:00:00Z',
            updated_at: '2026-06-02T12:11:00Z',
          })
        )
      )

      const result = await api.unignoreAlertLifecycle(ALERT_EPISODE_ID)
      expect(result.suppressed_at).toBeNull()
    })
  })

  // ──── Silence Periods (field aliasing) ────

  describe('getSilencePeriods', () => {
    it('maps snake_case silence period fields', async () => {
      server.use(
        http.get(`${API_BASE}/v1/monitor/silence-periods`, () =>
          HttpResponse.json([
            {
              id: '11111111-1111-4111-8111-111111111111',
              organization_id: '22222222-2222-4222-8222-222222222222',
              reason: 'maintenance',
              starts_at: 1700000000,
              ends_at: 1700003600,
              created_by: '33333333-3333-4333-8333-333333333333',
              created_at: 1699000000,
            },
          ])
        )
      )
      const result = await api.getSilencePeriods()
      expect(result).toHaveLength(1)
      expect(result[0]).toEqual({
        id: '11111111-1111-4111-8111-111111111111',
        organizationId: '22222222-2222-4222-8222-222222222222',
        reason: 'maintenance',
        startsAt: 1700000000,
        endsAt: 1700003600,
        createdBy: '33333333-3333-4333-8333-333333333333',
        createdAt: 1699000000,
      })
    })

    it('maps camelCase silence period fields', async () => {
      server.use(
        http.get(`${API_BASE}/v1/monitor/silence-periods`, () =>
          HttpResponse.json([
            {
              id: '44444444-4444-4444-8444-444444444444',
              organizationId: '22222222-2222-4222-8222-222222222222',
              reason: null,
              startsAt: 1700100000,
              endsAt: 1700103600,
              createdBy: '55555555-5555-4555-8555-555555555555',
              createdAt: 1699100000,
            },
          ])
        )
      )
      const result = await api.getSilencePeriods()
      expect(result[0].organizationId).toBe('22222222-2222-4222-8222-222222222222')
      expect(result[0].reason).toBeNull()
      expect(result[0].startsAt).toBe(1700100000)
    })
  })

  describe('createSilencePeriod', () => {
    it('posts data and returns mapped result', async () => {
      server.use(
        http.post(`${API_BASE}/v1/monitor/silence-periods`, async ({ request }) => {
          const body = (await request.json()) as Record<string, unknown>
          expect(body.reason).toBe('deploy')
          return HttpResponse.json({
            id: '66666666-6666-4666-8666-666666666666',
            organization_id: '22222222-2222-4222-8222-222222222222',
            reason: 'deploy',
            starts_at: 1700200000,
            ends_at: 1700203600,
            created_by: '77777777-7777-4777-8777-777777777777',
            created_at: 1700200000,
          })
        })
      )
      const result = await api.createSilencePeriod({
        reason: 'deploy',
        starts_at: 1700200000,
        ends_at: 1700203600,
      })
      expect(result.id).toBe('66666666-6666-4666-8666-666666666666')
      expect(result.organizationId).toBe('22222222-2222-4222-8222-222222222222')
      expect(result.startsAt).toBe(1700200000)
    })
  })

  describe('deleteSilencePeriod', () => {
    it('deletes a silence period', async () => {
      const silencePeriodId = '66666666-6666-4666-8666-666666666666'
      server.use(
        http.delete(`${API_BASE}/v1/monitor/silence-periods/${silencePeriodId}`, () =>
          new HttpResponse(null, { status: 204 })
        )
      )
      await api.deleteSilencePeriod(silencePeriodId)
    })
  })
})
