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
      const mock = { hosts: [{ id: 1, name: 'web-1' }] }
      server.use(http.get(`${API_BASE}/v1/hosts`, () => HttpResponse.json(mock)))
      expect(await api.getHosts()).toEqual(mock)
    })
  })

  describe('getHost', () => {
    it('fetches a single host', async () => {
      const mock = { id: 42, name: 'web-42' }
      server.use(http.get(`${API_BASE}/v1/hosts/42`, () => HttpResponse.json(mock)))
      expect(await api.getHost(42)).toEqual(mock)
    })
  })

  describe('deleteHost', () => {
    it('deletes a host', async () => {
      server.use(
        http.delete(`${API_BASE}/v1/hosts/42`, () => new HttpResponse(null, { status: 204 }))
      )
      await api.deleteHost(42)
    })
  })

  describe('getHostMetrics', () => {
    it('fetches metrics without time range', async () => {
      const mock = { timestamps: [], cpu: [] }
      server.use(
        http.get(`${API_BASE}/v1/hosts/1/metrics`, ({ request }) => {
          const url = new URL(request.url)
          expect(url.searchParams.has('from')).toBe(false)
          expect(url.searchParams.has('to')).toBe(false)
          return HttpResponse.json(mock)
        })
      )
      expect(await api.getHostMetrics(1)).toEqual(mock)
    })

    it('passes from/to when provided', async () => {
      server.use(
        http.get(`${API_BASE}/v1/hosts/1/metrics`, ({ request }) => {
          const url = new URL(request.url)
          expect(url.searchParams.get('from')).toBe('2024-01-01')
          expect(url.searchParams.get('to')).toBe('2024-01-02')
          return HttpResponse.json({ timestamps: [], cpu: [] })
        })
      )
      await api.getHostMetrics(1, '2024-01-01', '2024-01-02')
    })
  })

  describe('getHostContainers', () => {
    it('fetches containers for a host', async () => {
      const mock = { containers: [{ name: 'nginx' }] }
      server.use(
        http.get(`${API_BASE}/v1/hosts/1/containers`, () => HttpResponse.json(mock))
      )
      expect(await api.getHostContainers(1)).toEqual(mock)
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
                id: 7,
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
          id: '7',
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
                id: 8,
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
          id: '8',
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
            id: 9,
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
        id: '9',
        name: 'Container load',
        resourceKind: 'containers',
      })
    })
  })

  describe('deleteInfrastructureMapSavedView', () => {
    it('deletes a saved map view', async () => {
      server.use(
        http.delete(`${API_BASE}/v1/infra/map/saved-views/9`, () =>
          new HttpResponse(null, { status: 204 })
        )
      )
      await api.deleteInfrastructureMapSavedView('9')
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
                id: 1,
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
        id: 1,
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
                id: 2,
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
      const mock = { id: 3, key: 'mk_full_key_value' }
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
        http.delete(`${API_BASE}/v1/agent-api-keys/5`, () =>
          new HttpResponse(null, { status: 204 })
        )
      )
      await api.deleteAgentApiKey(5)
    })
  })

  // ──── Monitor Hosts ────

  describe('getMonitorHosts', () => {
    it('fetches all monitor hosts', async () => {
      const mock = [{ id: 1, hostname: 'web-1' }]
      server.use(
        http.get(`${API_BASE}/v1/monitor/hosts`, () => HttpResponse.json(mock))
      )
      expect(await api.getMonitorHosts()).toEqual(mock)
    })
  })

  describe('getMonitorHost', () => {
    it('fetches a single monitor host', async () => {
      const mock = { id: 7, hostname: 'db-1' }
      server.use(
        http.get(`${API_BASE}/v1/monitor/hosts/7`, () => HttpResponse.json(mock))
      )
      expect(await api.getMonitorHost(7)).toEqual(mock)
    })
  })

  describe('getMonitorHostMetrics', () => {
    it('fetches metrics without optional params', async () => {
      const mock = { timestamps: [], cpu: [] }
      server.use(
        http.get(`${API_BASE}/v1/monitor/hosts/1/metrics`, ({ request }) => {
          const url = new URL(request.url)
          expect(url.searchParams.has('from')).toBe(false)
          return HttpResponse.json(mock)
        })
      )
      expect(await api.getMonitorHostMetrics(1)).toEqual(mock)
    })

    it('passes from, to, and interval', async () => {
      server.use(
        http.get(`${API_BASE}/v1/monitor/hosts/1/metrics`, ({ request }) => {
          const url = new URL(request.url)
          expect(url.searchParams.get('from')).toBe('2024-01-01')
          expect(url.searchParams.get('to')).toBe('2024-01-02')
          expect(url.searchParams.get('interval')).toBe('5m')
          return HttpResponse.json({ timestamps: [], cpu: [] })
        })
      )
      await api.getMonitorHostMetrics(1, '2024-01-01', '2024-01-02', '5m')
    })
  })

  // ──── Monitor Host Containers (field aliasing) ────

  describe('getMonitorHostContainers', () => {
    it('maps snake_case container fields', async () => {
      server.use(
        http.get(`${API_BASE}/v1/monitor/hosts/1/containers`, () =>
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
      const result = await api.getMonitorHostContainers(1)
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
        http.get(`${API_BASE}/v1/monitor/hosts/2/containers`, () =>
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
      const result = await api.getMonitorHostContainers(2)
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
        http.get(`${API_BASE}/v1/monitor/hosts/1/alerts/config`, () =>
          HttpResponse.json({
            scope: 'global',
            global_alerts: [
              {
                id: 10,
                host_id: 1,
                scope: 'global',
                metric: 'cpu',
                condition: 'above',
                threshold: 90,
                duration_seconds: 300,
                enabled: true,
                incident_severity: 'CRITICAL',
                last_triggered_at: 1700000000,
                created_at: 1690000000,
              },
            ],
            host_alerts: [
              {
                id: 20,
                host_id: 1,
                scope: 'host',
                metric: 'memory',
                condition: 'above',
                threshold: 80,
                duration_seconds: 60,
                enabled: false,
                incident_severity: null,
                created_at: 1690000000,
              },
            ],
            effective_alerts: [
              {
                id: 10,
                scope: 'global',
                metric: 'cpu',
                condition: 'above',
                threshold: 90,
                duration_seconds: 300,
                enabled: true,
                incident_severity: 'CRITICAL',
                created_at: 1690000000,
              },
            ],
          })
        )
      )

      const config = await api.getHostAlertConfig(1)
      expect(config.scope).toBe('global')
      expect(config.globalAlerts).toHaveLength(1)
      expect(config.globalAlerts[0]).toMatchObject({
        id: 10,
        scope: 'global',
        metric: 'cpu',
        threshold: 90,
        durationSeconds: 300,
        enabled: true,
        incidentSeverity: 'CRITICAL',
        createdAt: 1690000000,
      })
      expect(config.hostAlerts).toHaveLength(1)
      expect(config.hostAlerts[0]).toMatchObject({
        id: 20,
        scope: 'host',
        metric: 'memory',
        durationSeconds: 60,
        enabled: false,
      })
      expect(config.effectiveAlerts).toHaveLength(1)
      expect(config.effectiveAlerts[0].id).toBe(10)
    })

    it('maps camelCase alert config fields', async () => {
      server.use(
        http.get(`${API_BASE}/v1/monitor/hosts/2/alerts/config`, () =>
          HttpResponse.json({
            scope: 'host',
            globalAlerts: [],
            hostAlerts: [
              {
                id: 30,
                hostId: 2,
                scope: 'host',
                metric: 'disk',
                condition: 'above',
                threshold: 95,
                durationSeconds: 120,
                enabled: true,
                incidentSeverity: 'HIGH',
                createdAt: 1695000000,
              },
            ],
            effectiveAlerts: [],
          })
        )
      )

      const config = await api.getHostAlertConfig(2)
      expect(config.scope).toBe('host')
      expect(config.globalAlerts).toHaveLength(0)
      expect(config.hostAlerts[0]).toMatchObject({
        id: 30,
        hostId: 2,
        metric: 'disk',
        durationSeconds: 120,
        incidentSeverity: 'HIGH',
      })
    })

    it('falls back to system_alerts when host_alerts is absent', async () => {
      server.use(
        http.get(`${API_BASE}/v1/monitor/hosts/3/alerts/config`, () =>
          HttpResponse.json({
            scope: 'host',
            global_alerts: [],
            system_alerts: [
              {
                id: 40,
                scope: 'host',
                metric: 'network',
                condition: 'above',
                threshold: 1000,
                duration_seconds: 0,
                enabled: true,
                incident_severity: null,
                created_at: 1690000000,
              },
            ],
            effective_alerts: [],
          })
        )
      )

      const config = await api.getHostAlertConfig(3)
      expect(config.hostAlerts).toHaveLength(1)
      expect(config.hostAlerts[0].metric).toBe('network')
    })
  })

  // ──── Host Alert CRUD ────

  describe('updateHostAlertScope', () => {
    it('puts the new scope', async () => {
      server.use(
        http.put(`${API_BASE}/v1/monitor/hosts/1/alerts/scope`, async ({ request }) => {
          const body = (await request.json()) as Record<string, unknown>
          expect(body.scope).toBe('global')
          return new HttpResponse(null, { status: 204 })
        })
      )
      await api.updateHostAlertScope(1, 'global')
    })
  })

  describe('createHostAlert', () => {
    it('posts alert and returns mapped result', async () => {
      server.use(
        http.post(`${API_BASE}/v1/monitor/hosts/1/alerts`, async ({ request }) => {
          const url = new URL(request.url)
          expect(url.searchParams.get('scope')).toBe('host')
          const body = (await request.json()) as Record<string, unknown>
          expect(body.metric).toBe('cpu')
          return HttpResponse.json({
            id: 50,
            host_id: 1,
            scope: 'host',
            metric: 'cpu',
            condition: 'above',
            threshold: 90,
            duration_seconds: 300,
            enabled: true,
            incident_severity: null,
            created_at: 1700000000,
          })
        })
      )

      const alert = await api.createHostAlert(1, {
        metric: 'cpu',
        condition: 'above',
        threshold: 90,
        durationSeconds: 300,
      })
      expect(alert.id).toBe(50)
      expect(alert.durationSeconds).toBe(300)
      expect(alert.scope).toBe('host')
    })

    it('uses custom scope when provided', async () => {
      server.use(
        http.post(`${API_BASE}/v1/monitor/hosts/1/alerts`, ({ request }) => {
          const url = new URL(request.url)
          expect(url.searchParams.get('scope')).toBe('global')
          return HttpResponse.json({
            id: 51,
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
        1,
        { metric: 'memory', condition: 'above', threshold: 80 },
        'global'
      )
      expect(alert.scope).toBe('global')
    })
  })

  describe('updateHostAlert', () => {
    it('puts updates and returns mapped result', async () => {
      server.use(
        http.put(`${API_BASE}/v1/monitor/hosts/1/alerts/50`, async ({ request }) => {
          const url = new URL(request.url)
          expect(url.searchParams.get('scope')).toBe('host')
          const body = (await request.json()) as Record<string, unknown>
          expect(body.threshold).toBe(95)
          return HttpResponse.json({
            id: 50,
            scope: 'host',
            metric: 'cpu',
            condition: 'above',
            threshold: 95,
            duration_seconds: 300,
            enabled: true,
            incident_severity: null,
            created_at: 1700000000,
          })
        })
      )
      const alert = await api.updateHostAlert(1, 50, { threshold: 95 })
      expect(alert?.threshold).toBe(95)
    })

    it('returns undefined for a no-content update response', async () => {
      server.use(
        http.put(`${API_BASE}/v1/monitor/hosts/1/alerts/50`, async ({ request }) => {
          const body = (await request.json()) as Record<string, unknown>
          expect(body.enabled).toBe(false)
          return new HttpResponse(null, { status: 204 })
        })
      )

      const alert = await api.updateHostAlert(1, 50, { enabled: false })
      expect(alert).toBeUndefined()
    })
  })

  describe('deleteHostAlert', () => {
    it('deletes with default scope', async () => {
      server.use(
        http.delete(`${API_BASE}/v1/monitor/hosts/1/alerts/50`, ({ request }) => {
          const url = new URL(request.url)
          expect(url.searchParams.get('scope')).toBe('host')
          return new HttpResponse(null, { status: 204 })
        })
      )
      await api.deleteHostAlert(1, 50)
    })

    it('deletes with explicit global scope', async () => {
      server.use(
        http.delete(`${API_BASE}/v1/monitor/hosts/1/alerts/50`, ({ request }) => {
          const url = new URL(request.url)
          expect(url.searchParams.get('scope')).toBe('global')
          return new HttpResponse(null, { status: 204 })
        })
      )
      await api.deleteHostAlert(1, 50, 'global')
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
              id: 1,
              organization_id: 10,
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
        http.post(`${API_BASE}/v1/alerts/lifecycles/7/ignore`, async ({ request }) => {
          const body = (await request.json()) as Record<string, unknown>
          expect(body.reason).toBe('Investigating')
          return HttpResponse.json({
            id: 7,
            organization_id: 10,
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

      const result = await api.ignoreAlertLifecycle(7, 'Investigating')
      expect(result.suppress_reason).toBe('Investigating')
    })
  })

  describe('unignoreAlertLifecycle', () => {
    it('posts to unignore an episode', async () => {
      server.use(
        http.post(`${API_BASE}/v1/alerts/lifecycles/7/unignore`, () =>
          HttpResponse.json({
            id: 7,
            organization_id: 10,
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

      const result = await api.unignoreAlertLifecycle(7)
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
              id: 1,
              organization_id: 10,
              reason: 'maintenance',
              starts_at: 1700000000,
              ends_at: 1700003600,
              created_by: 5,
              created_at: 1699000000,
            },
          ])
        )
      )
      const result = await api.getSilencePeriods()
      expect(result).toHaveLength(1)
      expect(result[0]).toEqual({
        id: 1,
        organizationId: 10,
        reason: 'maintenance',
        startsAt: 1700000000,
        endsAt: 1700003600,
        createdBy: 5,
        createdAt: 1699000000,
      })
    })

    it('maps camelCase silence period fields', async () => {
      server.use(
        http.get(`${API_BASE}/v1/monitor/silence-periods`, () =>
          HttpResponse.json([
            {
              id: 2,
              organizationId: 10,
              reason: null,
              startsAt: 1700100000,
              endsAt: 1700103600,
              createdBy: 6,
              createdAt: 1699100000,
            },
          ])
        )
      )
      const result = await api.getSilencePeriods()
      expect(result[0].organizationId).toBe(10)
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
            id: 3,
            organization_id: 10,
            reason: 'deploy',
            starts_at: 1700200000,
            ends_at: 1700203600,
            created_by: 7,
            created_at: 1700200000,
          })
        })
      )
      const result = await api.createSilencePeriod({
        reason: 'deploy',
        starts_at: 1700200000,
        ends_at: 1700203600,
      })
      expect(result.id).toBe(3)
      expect(result.organizationId).toBe(10)
      expect(result.startsAt).toBe(1700200000)
    })
  })

  describe('deleteSilencePeriod', () => {
    it('deletes a silence period', async () => {
      server.use(
        http.delete(`${API_BASE}/v1/monitor/silence-periods/3`, () =>
          new HttpResponse(null, { status: 204 })
        )
      )
      await api.deleteSilencePeriod(3)
    })
  })
})
