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
        expect(url.searchParams.get('env')).toBe('production')
        expect(url.searchParams.get('limit')).toBe('20')
        expect(url.searchParams.get('offset')).toBe('10')
        expect(url.searchParams.get('timeRange')).toBe('7d')
        return HttpResponse.json(mockResponse)
      })
    )

    const result = await api.getApmTraces({
      service: 'api-gateway',
      env: 'production',
      limit: 20,
      offset: 10,
      timeRange: '7d',
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
    const mockResponse = { errors: [], total: 0 }

    server.use(
      http.get(`${API_BASE}/v1/apm-errors`, () => {
        return HttpResponse.json(mockResponse)
      })
    )

    const result = await api.getApmErrors()
    expect(result).toEqual(mockResponse)
  })

  it('fetches APM errors with filter params', async () => {
    const mockResponse = { errors: [{ id: 'err-1' }], total: 1 }

    server.use(
      http.get(`${API_BASE}/v1/apm-errors`, ({ request }) => {
        const url = new URL(request.url)
        expect(url.searchParams.get('service')).toBe('payment-svc')
        expect(url.searchParams.get('limit')).toBe('50')
        expect(url.searchParams.get('offset')).toBe('0')
        expect(url.searchParams.get('timeRange')).toBe('90d')
        return HttpResponse.json(mockResponse)
      })
    )

    const result = await api.getApmErrors({
      service: 'payment-svc',
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
        expect(url.searchParams.get('limit')).toBe('10')
        expect(url.searchParams.get('timeRange')).toBe('30d')
        return HttpResponse.json(mockResponse)
      })
    )

    const result = await api.getApmResourceStats({
      service: 'user-svc',
      limit: 10,
      timeRange: '30d',
    })
    expect(result).toEqual(mockResponse)
  })
})
