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

describe('Logs API – extended coverage', () => {
  beforeEach(() => {
    localStorage.clear()
    sessionStorage.clear()
    sessionStorage.setItem('authenticated', 'true')
  })

  // ──── getLogs – exclude filters ────

  describe('getLogs – exclude filters', () => {
    it('passes excludeService, excludeEnvironment, excludeContainerName', async () => {
      server.use(
        http.get(`${API_BASE}/v1/logs`, ({ request }) => {
          const url = new URL(request.url)
          expect(url.searchParams.get('excludeService')).toBe('noisy-svc')
          expect(url.searchParams.get('excludeEnvironment')).toBe('dev')
          expect(url.searchParams.get('excludeContainerName')).toBe('sidecar')
          return HttpResponse.json({ logs: [], has_more: false })
        })
      )

      await api.getLogs({
        excludeService: 'noisy-svc',
        excludeEnvironment: 'dev',
        excludeContainerName: 'sidecar',
      })
    })

    it('passes excludeTags as excludeTag params', async () => {
      server.use(
        http.get(`${API_BASE}/v1/logs`, ({ request }) => {
          const url = new URL(request.url)
          expect(url.searchParams.getAll('excludeTag')).toEqual(['team:infra'])
          return HttpResponse.json({ logs: [], has_more: false })
        })
      )

      await api.getLogs({
        excludeTags: { team: 'infra' },
      })
    })

    it('passes containerName param', async () => {
      server.use(
        http.get(`${API_BASE}/v1/logs`, ({ request }) => {
          const url = new URL(request.url)
          expect(url.searchParams.get('containerName')).toBe('web-app')
          return HttpResponse.json({ logs: [], has_more: false })
        })
      )

      await api.getLogs({ containerName: 'web-app' })
    })

    it('passes from/to time range params', async () => {
      server.use(
        http.get(`${API_BASE}/v1/logs`, ({ request }) => {
          const url = new URL(request.url)
          expect(url.searchParams.get('from')).toBe('2024-06-01T00:00:00Z')
          expect(url.searchParams.get('to')).toBe('2024-06-02T00:00:00Z')
          return HttpResponse.json({ logs: [], has_more: false })
        })
      )

      await api.getLogs({
        from: '2024-06-01T00:00:00Z',
        to: '2024-06-02T00:00:00Z',
      })
    })
  })

  // ──── getSystemLogs – extended params ────

  describe('getSystemLogs – extended params', () => {
    it('passes cursor, limit, environment, containerName, levels, tags', async () => {
      server.use(
        http.get(`${API_BASE}/v1/monitor/systems/sys-3/logs`, ({ request }) => {
          const url = new URL(request.url)
          expect(url.searchParams.get('cursor')).toBe('cur123')
          expect(url.searchParams.get('limit')).toBe('25')
          expect(url.searchParams.get('environment')).toBe('staging')
          expect(url.searchParams.get('container_name')).toBe('worker')
          expect(url.searchParams.getAll('levels')).toEqual(['error', 'warn'])
          expect(url.searchParams.getAll('tag')).toEqual(['region:us-east'])
          return HttpResponse.json({
            logs: [],
            has_more: true,
            next_cursor: 'cur456',
            total_count: 50,
          })
        })
      )

      const result = await api.getSystemLogs('sys-3', {
        cursor: 'cur123',
        limit: 25,
        environment: 'staging',
        containerName: 'worker',
        levels: ['error', 'warn'],
        tags: { region: 'us-east' },
      })
      expect(result.hasMore).toBe(true)
      expect(result.nextCursor).toBe('cur456')
      expect(result.totalCount).toBe(50)
    })
  })

  // ──── getLogAggregate – filter params ────

  describe('getLogAggregate – filter params', () => {
    it('passes exclude filters to aggregate endpoint', async () => {
      server.use(
        http.get(`${API_BASE}/v1/logs/aggregate`, ({ request }) => {
          const url = new URL(request.url)
          expect(url.searchParams.get('excludeService')).toBe('healthcheck')
          expect(url.searchParams.get('excludeEnvironment')).toBe('test')
          expect(url.searchParams.get('excludeContainerName')).toBe('init')
          expect(url.searchParams.getAll('excludeTag')).toEqual(['internal:true'])
          return HttpResponse.json({
            buckets: [],
            total_count: 0,
            interval: 'auto',
          })
        })
      )

      const result = await api.getLogAggregate({
        excludeService: 'healthcheck',
        excludeEnvironment: 'test',
        excludeContainerName: 'init',
        excludeTags: { internal: 'true' },
      })
      expect(result.totalCount).toBe(0)
      expect(result.interval).toBe('auto')
    })

    it('passes query, levels, service, environment, tags', async () => {
      server.use(
        http.get(`${API_BASE}/v1/logs/aggregate`, ({ request }) => {
          const url = new URL(request.url)
          expect(url.searchParams.get('q')).toBe('timeout')
          expect(url.searchParams.getAll('level')).toEqual(['error'])
          expect(url.searchParams.get('service')).toBe('api')
          expect(url.searchParams.get('environment')).toBe('production')
          expect(url.searchParams.getAll('tag')).toEqual(['version:2.0'])
          return HttpResponse.json({ buckets: [], total_count: 0, interval: '1h' })
        })
      )

      await api.getLogAggregate({
        query: 'timeout',
        levels: ['error'],
        service: 'api',
        environment: 'production',
        tags: { version: '2.0' },
      })
    })

    it('handles missing bucket fields with defaults', async () => {
      server.use(
        http.get(`${API_BASE}/v1/logs/aggregate`, () => {
          return HttpResponse.json({
            buckets: [{ timestamp: '2024-01-01T00:00:00Z' }],
          })
        })
      )

      const result = await api.getLogAggregate()
      expect(result.buckets[0].count).toBe(0)
      expect(result.buckets[0].groups).toEqual({})
      expect(result.totalCount).toBe(0)
      expect(result.interval).toBe('auto')
    })
  })

  // ──── getLogTop – filter params ────

  describe('getLogTop – filter params', () => {
    it('passes exclude filters to top endpoint', async () => {
      server.use(
        http.get(`${API_BASE}/v1/logs/top`, ({ request }) => {
          const url = new URL(request.url)
          expect(url.searchParams.get('field')).toBe('service')
          expect(url.searchParams.get('excludeService')).toBe('internal')
          expect(url.searchParams.get('from')).toBe('2024-01-01')
          expect(url.searchParams.get('to')).toBe('2024-01-31')
          return HttpResponse.json({
            field: 'service',
            values: [{ value: 'api', count: 10 }],
            total_count: 10,
          })
        })
      )

      const result = await api.getLogTop({
        field: 'service',
        excludeService: 'internal',
        from: '2024-01-01',
        to: '2024-01-31',
      })
      expect(result.values).toHaveLength(1)
    })

    it('passes query, levels, service, environment, tags', async () => {
      server.use(
        http.get(`${API_BASE}/v1/logs/top`, ({ request }) => {
          const url = new URL(request.url)
          expect(url.searchParams.get('q')).toBe('error')
          expect(url.searchParams.getAll('level')).toEqual(['error', 'fatal'])
          expect(url.searchParams.get('service')).toBe('web')
          expect(url.searchParams.get('environment')).toBe('prod')
          expect(url.searchParams.getAll('tag')).toEqual(['dc:us-1'])
          return HttpResponse.json({
            field: 'host',
            values: [],
            total_count: 0,
          })
        })
      )

      await api.getLogTop({
        field: 'host',
        query: 'error',
        levels: ['error', 'fatal'],
        service: 'web',
        environment: 'prod',
        tags: { dc: 'us-1' },
      })
    })

    it('handles response using totalCount (camelCase) fallback', async () => {
      server.use(
        http.get(`${API_BASE}/v1/logs/top`, () => {
          return HttpResponse.json({
            values: [{ value: 'x' }],
            totalCount: 99,
          })
        })
      )

      const result = await api.getLogTop({ field: 'host' })
      expect(result.field).toBe('host')
      expect(result.totalCount).toBe(99)
      expect(result.values[0].count).toBe(0)
    })
  })

  // ──── downloadLogExport ────

  describe('downloadLogExport', () => {
    it('downloads CSV blob and triggers link click', async () => {
      const csvContent = 'timestamp,level,message\n2024-01-01,error,fail'
      server.use(
        http.get(`${API_BASE}/v1/logs/export`, ({ request }) => {
          const url = new URL(request.url)
          expect(url.searchParams.get('limit')).toBe('500')
          expect(url.searchParams.get('q')).toBe('error')
          return new HttpResponse(csvContent, {
            headers: { 'Content-Type': 'text/csv' },
          })
        })
      )

      let clickCalled = false
      const origCreateElement = document.createElement.bind(document)
      const origCreateObjectURL = URL.createObjectURL
      const origRevokeObjectURL = URL.revokeObjectURL

      URL.createObjectURL = () => 'blob:mock-url'
      URL.revokeObjectURL = () => {}
      document.createElement = ((tag: string) => {
        const el = origCreateElement(tag)
        if (tag === 'a') {
          el.click = () => { clickCalled = true }
        }
        return el
      }) as typeof document.createElement

      try {
        await api.downloadLogExport({
          query: 'error',
          limit: 500,
        })
        expect(clickCalled).toBe(true)
      } finally {
        URL.createObjectURL = origCreateObjectURL
        URL.revokeObjectURL = origRevokeObjectURL
        document.createElement = origCreateElement
      }
    })

    it('throws on non-ok response', async () => {
      server.use(
        http.get(`${API_BASE}/v1/logs/export`, () => {
          return new HttpResponse(null, { status: 500 })
        })
      )

      await expect(api.downloadLogExport()).rejects.toThrow()
    })
  })

  // ──── getLogs – mapLogRow field mapping ────

  describe('getLogs – field mapping', () => {
    it('maps camelCase response fields correctly', async () => {
      server.use(
        http.get(`${API_BASE}/v1/logs`, () => {
          return HttpResponse.json({
            logs: [
              {
                logId: 'log-cc',
                timestamp: '2024-01-01T00:00:00Z',
                level: 'info',
                message: 'camelCase',
                body: 'body-text',
                service: 'svc',
                environment: 'prod',
                host: 'h1',
                source: 'agent',
                containerName: 'web',
                containerId: 'cid-1',
                containerImage: 'img:latest',
                traceId: 'trace-1',
                spanId: 'span-1',
                tags: { k: 'v' },
                resourceAttributes: { rk: 'rv' },
              },
            ],
            hasMore: true,
            nextCursor: 'next-cc',
            totalCount: 5,
          })
        })
      )

      const result = await api.getLogs()
      const log = result.logs[0]
      expect(log.logId).toBe('log-cc')
      expect(log.containerName).toBe('web')
      expect(log.containerId).toBe('cid-1')
      expect(log.containerImage).toBe('img:latest')
      expect(log.traceId).toBe('trace-1')
      expect(log.spanId).toBe('span-1')
      expect(log.tags).toEqual({ k: 'v' })
      expect(log.resourceAttributes).toEqual({ rk: 'rv' })
      expect(result.hasMore).toBe(true)
      expect(result.nextCursor).toBe('next-cc')
      expect(result.totalCount).toBe(5)
    })

    it('defaults missing optional fields', async () => {
      server.use(
        http.get(`${API_BASE}/v1/logs`, () => {
          return HttpResponse.json({
            logs: [
              {
                log_id: 'log-sparse',
                timestamp: '2024-01-01T00:00:00Z',
                level: 'debug',
                message: 'sparse',
              },
            ],
          })
        })
      )

      const result = await api.getLogs()
      const log = result.logs[0]
      expect(log.body).toBe('')
      expect(log.service).toBe('')
      expect(log.environment).toBe('')
      expect(log.host).toBe('')
      expect(log.source).toBe('sdk')
      expect(log.containerName).toBe('')
      expect(log.containerId).toBe('')
      expect(log.containerImage).toBe('')
      expect(log.traceId).toBe('')
      expect(log.spanId).toBe('')
      expect(log.tags).toEqual({})
      expect(log.resourceAttributes).toEqual({})
    })
  })
})
