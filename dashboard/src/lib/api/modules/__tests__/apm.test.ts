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

describe('APM API', () => {
  beforeEach(() => {
    localStorage.clear()
    sessionStorage.clear()
    sessionStorage.setItem('authenticated', 'true')
  })

  // ──── getApmTraces ────

  it('fetches APM traces without params', async () => {
    const mockResponse = { traces: [], total: 0 }

    server.use(
      http.get(`${API_BASE}/v1/traces`, () => {
        return HttpResponse.json(mockResponse)
      })
    )

    const result = await api.getApmTraces()
    expect(result).toEqual(mockResponse)
  })

  it('fetches APM traces with filter params', async () => {
    const mockResponse = { traces: [{ traceId: 't1' }], total: 1 }

    server.use(
      http.get(`${API_BASE}/v1/traces`, ({ request }) => {
        const url = new URL(request.url)
        expect(url.searchParams.get('service')).toBe('api-gateway')
        expect(url.searchParams.get('services')).toBe('api-gateway,worker')
        expect(url.searchParams.get('source')).toBe('otlp')
        expect(url.searchParams.get('status')).toBe('error')
        expect(url.searchParams.get('env')).toBe('production')
        expect(url.searchParams.get('operation')).toBe('POST /checkout')
        expect(url.searchParams.get('search')).toBe('checkout')
        expect(url.searchParams.get('limit')).toBe('20')
        expect(url.searchParams.get('offset')).toBe('10')
        expect(url.searchParams.get('timeRange')).toBe('7d')
        return HttpResponse.json(mockResponse)
      })
    )

    const result = await api.getApmTraces({
      service: 'api-gateway',
      services: ['api-gateway', 'worker'],
      source: 'otlp',
      status: 'error',
      env: 'production',
      operation: 'POST /checkout',
      search: 'checkout',
      limit: 20,
      offset: 10,
      timeRange: '7d',
    })
    expect(result).toEqual(mockResponse)
  })

  // ──── getApmOverview ────

  it('fetches APM overview with filter params', async () => {
    const mockResponse = {
      stats: {
        totalTraces: 10,
        errorTraces: 1,
        errorRate: 0.1,
        serviceCount: 2,
        sourceCount: 1,
        p50DurationNs: 42000000,
        p95DurationNs: 312000000,
        p99DurationNs: 842000000,
        avgSpansPerTrace: 18.7,
        previous: {
          totalTraces: 8,
          errorRate: 0,
          p50DurationNs: 40000000,
          p95DurationNs: 300000000,
          p99DurationNs: 800000000,
          avgSpansPerTrace: 15.2,
        },
      },
      latencySeries: [],
      serviceHealth: [],
      resourceHotspots: [],
      errors: [],
      facets: { services: [], sources: [], environments: [], operations: [] },
    }

    server.use(
      http.get(`${API_BASE}/v1/traces/overview`, ({ request }) => {
        const url = new URL(request.url)
        expect(url.searchParams.get('service')).toBe('api-gateway')
        expect(url.searchParams.get('services')).toBe('api-gateway,worker')
        expect(url.searchParams.get('source')).toBe('sentry')
        expect(url.searchParams.get('status')).toBe('ok')
        expect(url.searchParams.get('env')).toBe('production')
        expect(url.searchParams.get('operation')).toBe('GET /orders')
        expect(url.searchParams.get('search')).toBe('checkout')
        expect(url.searchParams.get('timeRange')).toBe('24h')
        return HttpResponse.json(mockResponse)
      })
    )

    const result = await api.getApmOverview({
      service: 'api-gateway',
      services: ['api-gateway', 'worker'],
      source: 'sentry',
      status: 'ok',
      env: 'production',
      operation: 'GET /orders',
      search: 'checkout',
      timeRange: '24h',
    })
    expect(result).toEqual(mockResponse)
  })

  // ──── getApmTraceDetail ────

  it('fetches APM trace detail', async () => {
    const mockDetail = { traceId: 'trace-abc', spans: [], duration: 150 }

    server.use(
      http.get(`${API_BASE}/v1/traces/trace-abc`, () => {
        return HttpResponse.json(mockDetail)
      })
    )

    const result = await api.getApmTraceDetail('trace-abc')
    expect(result).toEqual(mockDetail)
  })

  it('normalizes trace detail span attributes from array payloads', async () => {
    const mockDetail = {
      traceId: 'trace-arrays',
      spans: [
        {
          spanId: 'span-1',
          traceId: 'trace-arrays',
          parentId: '',
          name: 'GET /checkout',
          service: 'checkout-api',
          resource: 'GET /checkout',
          type: 'web',
          startNs: 1,
          durationNs: 2,
          error: 0,
          meta: [
            {key: 'http.method', value: 'GET'},
            {key: 'http.status_code', value: 200},
            {other: 'ignored'},
          ],
          metrics: [
            {key: 'db.rows', value: '12'},
            {key: 'cache.hit', value: true},
            {key: 'bad.metric', value: 'NaN'},
            {other: 'ignored'},
          ],
          resourceAttributes: [
            {key: 'service.namespace', value: 'payments'},
          ],
          host: 'localhost',
          env: 'dev',
          version: '1.0.0',
          source: 'otlp',
        },
      ],
      duration: 150,
    }

    server.use(
      http.get(`${API_BASE}/v1/traces/trace-arrays`, () => {
        return HttpResponse.json(mockDetail)
      })
    )

    const result = await api.getApmTraceDetail('trace-arrays')
    expect(result).toMatchObject({
      traceId: 'trace-arrays',
      duration: 150,
      spans: [
        {
          meta: {'http.method': 'GET', 'http.status_code': '200'},
          metrics: {'db.rows': 12, 'cache.hit': 1, 'bad.metric': 0},
          resourceAttributes: {'service.namespace': 'payments'},
        },
      ],
    })
  })

  it('normalizes trace detail span attributes from object and empty payloads', async () => {
    const mockDetail = {
      traceId: 'trace-objects',
      spans: [
        {
          spanId: 'span-1',
          traceId: 'trace-objects',
          parentId: '',
          name: 'POST /orders',
          service: 'orders-api',
          resource: 'POST /orders',
          type: 'web',
          startNs: 1,
          durationNs: 2,
          error: 1,
          meta: {'error.type': 'Error', retry: 2, nullable: null},
          metrics: {attempts: '3', failed: true, empty: null, infinite: 'Infinity'},
          resourceAttributes: undefined,
          host: 'localhost',
          env: 'dev',
          version: '1.0.0',
          source: 'otlp',
        },
        {
          spanId: 'span-2',
          traceId: 'trace-objects',
          parentId: 'span-1',
          name: 'queue.publish',
          service: 'orders-api',
          resource: 'queue.publish',
          type: 'queue',
          startNs: 3,
          durationNs: 4,
          error: 0,
          meta: 'invalid',
          metrics: 'invalid',
          resourceAttributes: 'invalid',
          host: 'localhost',
          env: 'dev',
          version: '1.0.0',
          source: 'otlp',
        },
      ],
    }

    server.use(
      http.get(`${API_BASE}/v1/traces/trace-objects`, () => {
        return HttpResponse.json(mockDetail)
      })
    )

    const result = await api.getApmTraceDetail('trace-objects')
    expect(result.spans[0]).toMatchObject({
      meta: {'error.type': 'Error', retry: '2', nullable: ''},
      metrics: {attempts: 3, failed: 1, empty: 0, infinite: 0},
      resourceAttributes: {},
    })
    expect(result.spans[1]).toMatchObject({
      meta: {},
      metrics: {},
      resourceAttributes: {},
    })
  })

  // ──── getApmServiceMap ────

  it('fetches APM service map', async () => {
    const mockMap = { nodes: [], edges: [] }

    server.use(
      http.get(`${API_BASE}/v1/services/map`, () => {
        return HttpResponse.json(mockMap)
      })
    )

    const result = await api.getApmServiceMap()
    expect(result).toEqual(mockMap)
  })

  // ──── getApmErrors ────

  it('fetches APM errors without params', async () => {
    const mockResponse = { errors: [], totalCount: 0, serviceFacets: [] }

    server.use(
      http.get(`${API_BASE}/v1/apm-errors`, () => {
        return HttpResponse.json(mockResponse)
      })
    )

    const result = await api.getApmErrors()
    expect(result).toEqual(mockResponse)
  })

  it('fetches APM errors with filter params', async () => {
    const mockResponse = {
      errors: [{ id: 'err-1' }],
      totalCount: 1,
      serviceFacets: [{ service: 'payment-svc', count: 1 }],
    }

    server.use(
      http.get(`${API_BASE}/v1/apm-errors`, ({ request }) => {
        const url = new URL(request.url)
        expect(url.searchParams.get('service')).toBe('payment-svc')
        expect(url.searchParams.get('services')).toBe('payment-svc,worker')
        expect(url.searchParams.get('limit')).toBe('50')
        expect(url.searchParams.get('offset')).toBe('0')
        expect(url.searchParams.get('timeRange')).toBe('90d')
        return HttpResponse.json(mockResponse)
      })
    )

    const result = await api.getApmErrors({
      service: 'payment-svc',
      services: ['payment-svc', 'worker'],
      limit: 50,
      offset: 0,
      timeRange: '90d',
    })
    expect(result).toEqual(mockResponse)
  })

  // ──── getApmResourceStats ────

  it('fetches APM resource stats without params', async () => {
    const mockResponse = { resources: [], total: 0 }

    server.use(
      http.get(`${API_BASE}/v1/traces/resources`, () => {
        return HttpResponse.json(mockResponse)
      })
    )

    const result = await api.getApmResourceStats()
    expect(result).toEqual(mockResponse)
  })

  it('fetches APM resource stats with filter params', async () => {
    const mockResponse = { resources: [{ name: '/api/v1/users' }], total: 1 }

    server.use(
      http.get(`${API_BASE}/v1/traces/resources`, ({ request }) => {
        const url = new URL(request.url)
        expect(url.searchParams.get('service')).toBe('user-svc')
        expect(url.searchParams.get('services')).toBe('user-svc,worker')
        expect(url.searchParams.get('source')).toBe('otlp')
        expect(url.searchParams.get('status')).toBe('error')
        expect(url.searchParams.get('env')).toBe('production')
        expect(url.searchParams.get('operation')).toBe('POST /users')
        expect(url.searchParams.get('search')).toBe('checkout')
        expect(url.searchParams.get('limit')).toBe('10')
        expect(url.searchParams.get('offset')).toBe('20')
        expect(url.searchParams.get('timeRange')).toBe('30d')
        return HttpResponse.json(mockResponse)
      })
    )

    const result = await api.getApmResourceStats({
      service: 'user-svc',
      services: ['user-svc', 'worker'],
      source: 'otlp',
      status: 'error',
      env: 'production',
      operation: 'POST /users',
      search: 'checkout',
      limit: 10,
      offset: 20,
      timeRange: '30d',
    })
    expect(result).toEqual(mockResponse)
  })
})
