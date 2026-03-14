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

import { describe, it, expect, beforeEach, afterEach } from 'vitest'
import { http, HttpResponse } from 'msw'
import { server } from '@/test/mocks/server'
import { api } from '@/lib/api'

const API_BASE = 'http://localhost:8080'

describe('Logs API – branch coverage', () => {
  beforeEach(() => {
    localStorage.clear()
    sessionStorage.clear()
    sessionStorage.setItem('authenticated', 'true')
  })

  // ──── buildLogFilterParams – tag key filtering ────

  describe('buildLogFilterParams – empty tag keys', () => {
    it('skips tags with empty keys', async () => {
      server.use(
        http.get(`${API_BASE}/v1/logs`, ({ request }) => {
          const url = new URL(request.url)
          expect(url.searchParams.getAll('tag')).toEqual(['valid:val'])
          return HttpResponse.json({ logs: [], has_more: false })
        })
      )

      await api.getLogs({ tags: { '': 'skip', valid: 'val' } })
    })

    it('skips excludeTags with empty keys', async () => {
      server.use(
        http.get(`${API_BASE}/v1/logs`, ({ request }) => {
          const url = new URL(request.url)
          expect(url.searchParams.getAll('excludeTag')).toEqual(['ok:yes'])
          return HttpResponse.json({ logs: [], has_more: false })
        })
      )

      await api.getLogs({ excludeTags: { '': 'nope', ok: 'yes' } })
    })
  })

  // ──── getLogFilters – mapServices branches ────

  describe('getLogFilters – mapServices branches', () => {
    it('normalizes string items to {value, count: 0}', async () => {
      server.use(
        http.get(`${API_BASE}/v1/logs/filters`, () => {
          return HttpResponse.json({
            services: ['svc-a', 'svc-b'],
            environments: [{ value: 'prod', count: 5 }],
            levels: ['error'],
            tagKeys: ['env'],
          })
        })
      )

      const result = await api.getLogFilters()
      expect(result.services).toEqual([
        { value: 'svc-a', count: 0 },
        { value: 'svc-b', count: 0 },
      ])
      expect(result.environments).toEqual([{ value: 'prod', count: 5 }])
    })

    it('handles object items with missing count', async () => {
      server.use(
        http.get(`${API_BASE}/v1/logs/filters`, () => {
          return HttpResponse.json({
            services: [{ value: 'api' }],
            environments: [],
            levels: [],
            tag_keys: ['host'],
          })
        })
      )

      const result = await api.getLogFilters()
      expect(result.services).toEqual([{ value: 'api', count: 0 }])
      expect(result.tagKeys).toEqual(['host'])
    })

    it('defaults missing arrays to empty', async () => {
      server.use(
        http.get(`${API_BASE}/v1/logs/filters`, () => {
          return HttpResponse.json({})
        })
      )

      const result = await api.getLogFilters()
      expect(result.services).toEqual([])
      expect(result.environments).toEqual([])
      expect(result.levels).toEqual([])
      expect(result.tagKeys).toEqual([])
    })
  })

  // ──── getLogApiKeys – field name fallbacks ────

  describe('getLogApiKeys – field fallbacks', () => {
    it('maps camelCase key fields', async () => {
      server.use(
        http.get(`${API_BASE}/v1/logs/api-keys`, () => {
          return HttpResponse.json({
            keys: [
              {
                id: 1,
                name: 'key1',
                keyPrefix: 'kp_cc',
                createdAt: '2024-01-01T00:00:00Z',
                lastUsedAt: '2024-06-01T00:00:00Z',
              },
            ],
          })
        })
      )

      const result = await api.getLogApiKeys()
      expect(result.keys[0].keyPrefix).toBe('kp_cc')
      expect(result.keys[0].createdAt).toBe('2024-01-01T00:00:00Z')
      expect(result.keys[0].lastUsedAt).toBe('2024-06-01T00:00:00Z')
    })

    it('handles empty keys array', async () => {
      server.use(
        http.get(`${API_BASE}/v1/logs/api-keys`, () => {
          return HttpResponse.json({})
        })
      )

      const result = await api.getLogApiKeys()
      expect(result.keys).toEqual([])
    })
  })

  // ──── createLogTailStream – optional params ────

  describe('createLogTailStream – all optional params', () => {
    const origEventSource = globalThis.EventSource

    beforeEach(() => {
      globalThis.EventSource = class MockEventSource {
        url: string
        withCredentials: boolean
        close() {
          /* mock no-op */
        }
        constructor(url: string, opts?: { withCredentials?: boolean }) {
          this.url = url
          this.withCredentials = opts?.withCredentials ?? false
        }
      } as unknown as typeof EventSource
    })

    afterEach(() => {
      globalThis.EventSource = origEventSource
    })

    it('creates EventSource with query, levels, service, environment', () => {
      const es = api.createLogTailStream({
        query: 'search',
        levels: ['error', 'warn'],
        service: 'api',
        environment: 'prod',
      })
      expect(es.url).toContain('q=search')
      expect(es.url).toContain('level=error')
      expect(es.url).toContain('level=warn')
      expect(es.url).toContain('service=api')
      expect(es.url).toContain('environment=prod')
      es.close()
    })

    it('creates EventSource with no params', () => {
      const es = api.createLogTailStream()
      expect(es.url).toContain('/logs/tail?')
      es.close()
    })

    it('creates EventSource with empty levels array', () => {
      const es = api.createLogTailStream({ levels: [] })
      expect(es.url).not.toContain('level=')
      es.close()
    })
  })

  // ──── getSystemLogs – tags with empty keys ────

  describe('getSystemLogs – tags with empty keys', () => {
    it('skips empty tag keys in system logs', async () => {
      server.use(
        http.get(`${API_BASE}/v1/monitor/systems/s1/logs`, ({ request }) => {
          const url = new URL(request.url)
          expect(url.searchParams.getAll('tag')).toEqual(['k:v'])
          return HttpResponse.json({ logs: [], has_more: false })
        })
      )

      await api.getSystemLogs('s1', { tags: { '': 'skip', k: 'v' } })
    })
  })

  // ──── getLogAggregate – camelCase totalCount fallback ────

  describe('getLogAggregate – totalCount fallbacks', () => {
    it('uses camelCase totalCount when total_count is missing', async () => {
      server.use(
        http.get(`${API_BASE}/v1/logs/aggregate`, () => {
          return HttpResponse.json({
            buckets: [],
            totalCount: 77,
            interval: '10m',
          })
        })
      )

      const result = await api.getLogAggregate()
      expect(result.totalCount).toBe(77)
    })

    it('passes from/to time range to aggregate', async () => {
      server.use(
        http.get(`${API_BASE}/v1/logs/aggregate`, ({ request }) => {
          const url = new URL(request.url)
          expect(url.searchParams.get('from')).toBe('2024-01-01')
          expect(url.searchParams.get('to')).toBe('2024-12-31')
          return HttpResponse.json({ buckets: [], total_count: 0 })
        })
      )

      await api.getLogAggregate({ from: '2024-01-01', to: '2024-12-31' })
    })
  })

  // ──── getLogTop – field fallback and missing values ────

  describe('getLogTop – field fallback', () => {
    it('uses options.field when response.field is missing', async () => {
      server.use(
        http.get(`${API_BASE}/v1/logs/top`, () => {
          return HttpResponse.json({
            values: [],
            total_count: 0,
          })
        })
      )

      const result = await api.getLogTop({ field: 'myField' })
      expect(result.field).toBe('myField')
    })

    it('passes all filter params to top endpoint', async () => {
      server.use(
        http.get(`${API_BASE}/v1/logs/top`, ({ request }) => {
          const url = new URL(request.url)
          expect(url.searchParams.get('excludeEnvironment')).toBe('dev')
          expect(url.searchParams.get('excludeContainerName')).toBe('init')
          expect(url.searchParams.getAll('excludeTag')).toEqual(['x:y'])
          return HttpResponse.json({
            field: 'svc',
            values: [],
            total_count: 0,
          })
        })
      )

      await api.getLogTop({
        field: 'svc',
        excludeEnvironment: 'dev',
        excludeContainerName: 'init',
        excludeTags: { x: 'y' },
      })
    })
  })

  // ──── downloadLogExport – filter params ────

  describe('downloadLogExport – filter params', () => {
    it('passes all filter options to export endpoint', async () => {
      server.use(
        http.get(`${API_BASE}/v1/logs/export`, ({ request }) => {
          const url = new URL(request.url)
          expect(url.searchParams.get('service')).toBe('web')
          expect(url.searchParams.get('environment')).toBe('prod')
          expect(url.searchParams.get('from')).toBe('2024-01-01')
          expect(url.searchParams.get('to')).toBe('2024-12-31')
          expect(url.searchParams.getAll('level')).toEqual(['error'])
          return new HttpResponse('csv', {
            headers: { 'Content-Type': 'text/csv' },
          })
        })
      )

      const origCreate = document.createElement.bind(document)
      URL.createObjectURL = () => 'blob:url'
      URL.revokeObjectURL = () => {}
      document.createElement = ((tag: string) => {
        const el = origCreate(tag)
        if (tag === 'a') el.click = () => {}
        return el
      }) as typeof document.createElement

      try {
        await api.downloadLogExport({
          service: 'web',
          environment: 'prod',
          from: '2024-01-01',
          to: '2024-12-31',
          levels: ['error'],
        })
      } finally {
        document.createElement = origCreate
      }
    })
  })

  // ──── mapRawLogResponse – mixed field formats ────

  describe('mapRawLogResponse – mixed field formats', () => {
    it('maps both camelCase and snake_case pagination fields', async () => {
      server.use(
        http.get(`${API_BASE}/v1/logs`, () => {
          return HttpResponse.json({
            logs: [],
            nextCursor: 'nc1',
            hasMore: true,
            totalCount: 42,
          })
        })
      )

      const result = await api.getLogs()
      expect(result.nextCursor).toBe('nc1')
      expect(result.hasMore).toBe(true)
      expect(result.totalCount).toBe(42)
    })

    it('defaults pagination fields when missing', async () => {
      server.use(
        http.get(`${API_BASE}/v1/logs`, () => {
          return HttpResponse.json({ logs: [] })
        })
      )

      const result = await api.getLogs()
      expect(result.nextCursor).toBeNull()
      expect(result.hasMore).toBe(false)
      expect(result.totalCount).toBeNull()
    })
  })
})
