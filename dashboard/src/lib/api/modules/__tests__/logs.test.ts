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
import { clearAuthStorage } from '@/test/utils'

const API_BASE = 'http://localhost:8080'

describe('Logs API', () => {
  beforeEach(() => {
    clearAuthStorage()
  })

  // ──── getLogs ────

  describe('getLogs', () => {
    it('fetches logs with default options', async () => {
      server.use(
        http.get(`${API_BASE}/v1/logs`, ({ request }) => {
          const url = new URL(request.url)
          expect(url.searchParams.get('limit')).toBe('100')
          return HttpResponse.json({
            logs: [
              {
                log_id: 'log-1',
                timestamp: '2024-01-01T00:00:00Z',
                level: 'error',
                message: 'Something failed',
                body: '',
                service: 'api',
                environment: 'production',
                host: 'host-1',
                source: 'sdk',
                container_name: '',
                container_id: '',
                container_image: '',
                trace_id: '',
                span_id: '',
                tags: {},
                resource_attributes: {},
              },
            ],
            has_more: false,
            next_cursor: null,
            total_count: 1,
          })
        })
      )

      const result = await api.getLogs()
      expect(result.logs).toHaveLength(1)
      expect(result.logs[0].logId).toBe('log-1')
      expect(result.logs[0].level).toBe('error')
      expect(result.hasMore).toBe(false)
    })

    it('passes query params correctly', async () => {
      server.use(
        http.get(`${API_BASE}/v1/logs`, ({ request }) => {
          const url = new URL(request.url)
          expect(url.searchParams.get('q')).toBe('error')
          expect(url.searchParams.get('service')).toBe('api')
          expect(url.searchParams.get('environment')).toBe('prod')
          expect(url.searchParams.get('limit')).toBe('50')
          expect(url.searchParams.get('cursor')).toBe('abc')
          return HttpResponse.json({ logs: [], has_more: false })
        })
      )

      await api.getLogs({
        query: 'error',
        service: 'api',
        environment: 'prod',
        limit: 50,
        cursor: 'abc',
      })
    })

    it('passes level and tag filters', async () => {
      server.use(
        http.get(`${API_BASE}/v1/logs`, ({ request }) => {
          const url = new URL(request.url)
          expect(url.searchParams.getAll('level')).toEqual(['error', 'warn'])
          expect(url.searchParams.getAll('tag')).toEqual(['env:prod'])
          return HttpResponse.json({ logs: [], has_more: false })
        })
      )

      await api.getLogs({
        levels: ['error', 'warn'],
        tags: { env: 'prod' },
      })
    })
  })

  // ──── getSystemLogs ────

  describe('getSystemLogs', () => {
    it('fetches system logs by systemId', async () => {
      server.use(
        http.get(`${API_BASE}/v1/monitor/systems/sys-1/logs`, ({ request }) => {
          const url = new URL(request.url)
          expect(url.searchParams.get('limit')).toBe('100')
          return HttpResponse.json({
            logs: [
              {
                log_id: 'syslog-1',
                timestamp: '2024-01-01T00:00:00Z',
                level: 'info',
                message: 'System OK',
                body: '',
                service: '',
                environment: '',
                host: '',
                source: 'sdk',
                tags: {},
              },
            ],
            has_more: false,
          })
        })
      )

      const result = await api.getSystemLogs('sys-1')
      expect(result.logs).toHaveLength(1)
      expect(result.logs[0].logId).toBe('syslog-1')
    })

    it('passes query and filter options', async () => {
      server.use(
        http.get(`${API_BASE}/v1/monitor/systems/sys-2/logs`, ({ request }) => {
          const url = new URL(request.url)
          expect(url.searchParams.get('query')).toBe('timeout')
          expect(url.searchParams.get('service')).toBe('web')
          expect(url.searchParams.get('from')).toBe('2024-01-01')
          expect(url.searchParams.get('to')).toBe('2024-01-02')
          return HttpResponse.json({ logs: [], has_more: false })
        })
      )

      await api.getSystemLogs('sys-2', {
        query: 'timeout',
        service: 'web',
        from: '2024-01-01',
        to: '2024-01-02',
      })
    })
  })

  // ──── getLogFilters ────

  describe('getLogFilters', () => {
    it('fetches log filter options', async () => {
      server.use(
        http.get(`${API_BASE}/v1/logs/filters`, () => {
          return HttpResponse.json({
            services: [{ value: 'api', count: 10 }],
            environments: ['production'],
            levels: ['error', 'warn', 'info'],
            tag_keys: ['env', 'version'],
          })
        })
      )

      const result = await api.getLogFilters()
      expect(result.services).toEqual([{ value: 'api', count: 10 }])
      expect(result.environments).toEqual([{ value: 'production', count: 0 }])
      expect(result.levels).toEqual(['error', 'warn', 'info'])
      expect(result.tagKeys).toEqual(['env', 'version'])
    })

    it('passes from/to params', async () => {
      server.use(
        http.get(`${API_BASE}/v1/logs/filters`, ({ request }) => {
          const url = new URL(request.url)
          expect(url.searchParams.get('from')).toBe('2024-01-01')
          expect(url.searchParams.get('to')).toBe('2024-01-31')
          return HttpResponse.json({
            services: [],
            environments: [],
            levels: [],
            tag_keys: [],
          })
        })
      )

      await api.getLogFilters({ from: '2024-01-01', to: '2024-01-31' })
    })
  })

  // ──── getLogApiKeys ────

  describe('getLogApiKeys', () => {
    it('fetches log API keys', async () => {
      server.use(
        http.get(`${API_BASE}/v1/logs/api-keys`, () => {
          return HttpResponse.json({
            keys: [
              {
                id: 1,
                name: 'my-key',
                key_prefix: 'mk_abc',
                created_at: '2024-01-01T00:00:00Z',
                last_used_at: null,
              },
            ],
          })
        })
      )

      const result = await api.getLogApiKeys()
      expect(result.keys).toHaveLength(1)
      expect(result.keys[0].name).toBe('my-key')
      expect(result.keys[0].keyPrefix).toBe('mk_abc')
    })
  })

  // ──── createLogApiKey ────

  describe('createLogApiKey', () => {
    it('creates a new log API key', async () => {
      server.use(
        http.post(`${API_BASE}/v1/logs/api-keys`, async ({ request }) => {
          const body = (await request.json()) as Record<string, unknown>
          expect(body.name).toBe('new-key')
          return HttpResponse.json({
            id: 2,
            name: 'new-key',
            key: 'mk_full_secret_key',
            keyPrefix: 'mk_ful',
          })
        })
      )

      const result = await api.createLogApiKey('new-key')
      expect(result.key).toBe('mk_full_secret_key')
    })
  })

  // ──── deleteLogApiKey ────

  describe('deleteLogApiKey', () => {
    it('deletes a log API key by id', async () => {
      server.use(
        http.delete(`${API_BASE}/v1/logs/api-keys/5`, () => {
          return new HttpResponse(null, { status: 204 })
        })
      )

      await api.deleteLogApiKey(5)
    })
  })

  // ──── getLogTagValues ────

  describe('getLogTagValues', () => {
    it('fetches tag values for a given key', async () => {
      server.use(
        http.get(`${API_BASE}/v1/logs/tag-values`, ({ request }) => {
          const url = new URL(request.url)
          expect(url.searchParams.get('key')).toBe('env')
          return HttpResponse.json({
            key: 'env',
            values: ['production', 'staging'],
          })
        })
      )

      const result = await api.getLogTagValues('env')
      expect(result.key).toBe('env')
      expect(result.values).toEqual(['production', 'staging'])
    })

    it('passes optional from/to/limit params', async () => {
      server.use(
        http.get(`${API_BASE}/v1/logs/tag-values`, ({ request }) => {
          const url = new URL(request.url)
          expect(url.searchParams.get('key')).toBe('host')
          expect(url.searchParams.get('from')).toBe('2024-01-01')
          expect(url.searchParams.get('to')).toBe('2024-01-31')
          expect(url.searchParams.get('limit')).toBe('10')
          return HttpResponse.json({ key: 'host', values: ['h1'] })
        })
      )

      await api.getLogTagValues('host', {
        from: '2024-01-01',
        to: '2024-01-31',
        limit: 10,
      })
    })
  })

  // ──── getLogAggregate ────

  describe('getLogAggregate', () => {
    it('fetches aggregated log data', async () => {
      server.use(
        http.get(`${API_BASE}/v1/logs/aggregate`, () => {
          return HttpResponse.json({
            buckets: [
              { timestamp: '2024-01-01T00:00:00Z', count: 42, groups: {} },
            ],
            total_count: 42,
            interval: '1h',
          })
        })
      )

      const result = await api.getLogAggregate()
      expect(result.buckets).toHaveLength(1)
      expect(result.buckets[0].count).toBe(42)
      expect(result.totalCount).toBe(42)
      expect(result.interval).toBe('1h')
    })

    it('passes interval and groupBy params', async () => {
      server.use(
        http.get(`${API_BASE}/v1/logs/aggregate`, ({ request }) => {
          const url = new URL(request.url)
          expect(url.searchParams.get('interval')).toBe('5m')
          expect(url.searchParams.get('groupBy')).toBe('service')
          return HttpResponse.json({
            buckets: [],
            total_count: 0,
            interval: '5m',
          })
        })
      )

      await api.getLogAggregate({ interval: '5m', groupBy: 'service' })
    })
  })

  // ──── getLogTop ────

  describe('getLogTop', () => {
    it('fetches top log values for a field', async () => {
      server.use(
        http.get(`${API_BASE}/v1/logs/top`, ({ request }) => {
          const url = new URL(request.url)
          expect(url.searchParams.get('field')).toBe('service')
          return HttpResponse.json({
            field: 'service',
            values: [
              { value: 'api', count: 100 },
              { value: 'web', count: 50 },
            ],
            total_count: 150,
          })
        })
      )

      const result = await api.getLogTop({ field: 'service' })
      expect(result.field).toBe('service')
      expect(result.values).toHaveLength(2)
      expect(result.values[0].value).toBe('api')
      expect(result.totalCount).toBe(150)
    })

    it('passes optional limit param', async () => {
      server.use(
        http.get(`${API_BASE}/v1/logs/top`, ({ request }) => {
          const url = new URL(request.url)
          expect(url.searchParams.get('field')).toBe('host')
          expect(url.searchParams.get('limit')).toBe('5')
          return HttpResponse.json({
            field: 'host',
            values: [],
            total_count: 0,
          })
        })
      )

      await api.getLogTop({ field: 'host', limit: 5 })
    })
  })
})
