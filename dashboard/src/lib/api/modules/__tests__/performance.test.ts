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

describe('Performance API', () => {
  beforeEach(() => {
    localStorage.clear()
    sessionStorage.clear()
    sessionStorage.setItem('authenticated', 'true')
  })

  // ──── getTransactions ────

  describe('getTransactions', () => {
    it('fetches transactions with default period', async () => {
      server.use(
        http.get(`${API_BASE}/v1/projects/1/transactions`, ({ request }) => {
          const url = new URL(request.url)
          expect(url.searchParams.get('period')).toBe('7d')
          return HttpResponse.json([
            {
              transaction: '/api/users',
              count: 100,
              avgDuration: 250,
              p50: 200,
              p95: 500,
              p99: 900,
              latestEventId: 'evt-1',
              failureRate: 0.05,
            },
          ])
        })
      )

      const result = await api.getTransactions(1)
      expect(result).toHaveLength(1)
      expect(result[0].transaction).toBe('/api/users')
      expect(result[0].latestEventId).toBe('evt-1')
      expect(result[0].failureRate).toBe(0.05)
    })

    it('passes period, environment, and operation params', async () => {
      server.use(
        http.get(`${API_BASE}/v1/projects/2/transactions`, ({ request }) => {
          const url = new URL(request.url)
          expect(url.searchParams.get('period')).toBe('24h')
          expect(url.searchParams.get('environment')).toBe('production')
          expect(url.searchParams.get('operation')).toBe('http.server')
          return HttpResponse.json([])
        })
      )

      const result = await api.getTransactions(2, {
        period: '24h',
        environment: 'production',
        operation: 'http.server',
      })
      expect(result).toEqual([])
    })

    it('maps snake_case fields to camelCase', async () => {
      server.use(
        http.get(`${API_BASE}/v1/projects/3/transactions`, () => {
          return HttpResponse.json([
            {
              transaction: '/api/health',
              count: 50,
              latest_event_id: 'evt-snake',
              failure_rate: 0.1,
            },
          ])
        })
      )

      const result = await api.getTransactions(3)
      expect(result[0].latestEventId).toBe('evt-snake')
      expect(result[0].failureRate).toBe(0.1)
    })

    it('defaults failureRate to 0 when missing', async () => {
      server.use(
        http.get(`${API_BASE}/v1/projects/4/transactions`, () => {
          return HttpResponse.json([
            {
              transaction: '/api/data',
              count: 10,
            },
          ])
        })
      )

      const result = await api.getTransactions(4)
      expect(result[0].failureRate).toBe(0)
    })
  })

  // ──── getPerformanceStats ────

  describe('getPerformanceStats', () => {
    it('fetches performance stats with default period', async () => {
      server.use(
        http.get(`${API_BASE}/v1/projects/1/transactions/stats`, ({ request }) => {
          const url = new URL(request.url)
          expect(url.searchParams.get('period')).toBe('7d')
          return HttpResponse.json({
            totalTransactions: 1000,
            avgDuration: 150,
            p50: 120,
            p95: 400,
            p99: 800,
            failureRate: 0.02,
          })
        })
      )

      const result = await api.getPerformanceStats(1)
      expect(result.totalTransactions).toBe(1000)
      expect(result.avgDuration).toBe(150)
    })

    it('passes period, environment, and operation params', async () => {
      server.use(
        http.get(`${API_BASE}/v1/projects/5/transactions/stats`, ({ request }) => {
          const url = new URL(request.url)
          expect(url.searchParams.get('period')).toBe('30d')
          expect(url.searchParams.get('environment')).toBe('staging')
          expect(url.searchParams.get('operation')).toBe('db.query')
          return HttpResponse.json({
            totalTransactions: 500,
            avgDuration: 80,
          })
        })
      )

      const result = await api.getPerformanceStats(5, {
        period: '30d',
        environment: 'staging',
        operation: 'db.query',
      })
      expect(result.totalTransactions).toBe(500)
    })
  })

  // ──── getTransaction ────

  describe('getTransaction', () => {
    it('fetches a single transaction by eventId', async () => {
      server.use(
        http.get(`${API_BASE}/v1/transactions/evt-123`, () => {
          return HttpResponse.json({
            eventId: 'evt-123',
            transaction: '/api/users',
            duration: 350,
            status: 'ok',
            timestamp: '2024-01-01T00:00:00Z',
          })
        })
      )

      const result = await api.getTransaction('evt-123')
      expect(result.eventId).toBe('evt-123')
      expect(result.transaction).toBe('/api/users')
      expect(result.duration).toBe(350)
    })

    it('encodes special characters in eventId', async () => {
      server.use(
        http.get(`${API_BASE}/v1/transactions/:eventId`, ({ params }) => {
          return HttpResponse.json({
            eventId: params.eventId,
            transaction: '/test',
          })
        })
      )

      const result = await api.getTransaction('evt/special&chars')
      expect(result.eventId).toBeDefined()
    })
  })

  // ──── getTransactionSpans ────

  describe('getTransactionSpans', () => {
    it('fetches spans for a transaction', async () => {
      server.use(
        http.get(`${API_BASE}/v1/transactions/evt-456/spans`, () => {
          return HttpResponse.json({
            eventId: 'evt-456',
            transaction: '/api/orders',
            spans: [
              {
                spanId: 'span-1',
                op: 'db.query',
                description: 'SELECT * FROM orders',
                duration: 50,
              },
              {
                spanId: 'span-2',
                op: 'http.client',
                description: 'GET /external-api',
                duration: 200,
              },
            ],
          })
        })
      )

      const result = await api.getTransactionSpans('evt-456')
      expect(result.eventId).toBe('evt-456')
      expect(result.spans).toHaveLength(2)
      expect(result.spans[0].spanId).toBe('span-1')
      expect(result.spans[1].op).toBe('http.client')
    })
  })

  // ──── getTraceDetails ────

  describe('getTraceDetails', () => {
    it('fetches trace details by projectId and traceId', async () => {
      server.use(
        http.get(`${API_BASE}/v1/projects/1/traces/trace-abc`, () => {
          return HttpResponse.json({
            traceId: 'trace-abc',
            rootTransaction: '/api/checkout',
            spans: 15,
            duration: 1200,
            services: ['api', 'payment'],
          })
        })
      )

      const result = await api.getTraceDetails(1, 'trace-abc')
      expect(result.traceId).toBe('trace-abc')
      expect(result.rootTransaction).toBe('/api/checkout')
    })
  })

  // ──── getSpanDetails ────

  describe('getSpanDetails', () => {
    it('fetches span details by projectId and spanId', async () => {
      server.use(
        http.get(`${API_BASE}/v1/projects/2/spans/span-xyz`, () => {
          return HttpResponse.json({
            spanId: 'span-xyz',
            op: 'http.server',
            description: 'POST /api/login',
            duration: 100,
            status: 'ok',
          })
        })
      )

      const result = await api.getSpanDetails(2, 'span-xyz')
      expect(result.spanId).toBe('span-xyz')
      expect(result.op).toBe('http.server')
      expect(result.duration).toBe(100)
    })
  })

  // ──── getRelatedErrors ────

  describe('getRelatedErrors', () => {
    it('fetches related errors with default limit', async () => {
      server.use(
        http.get(`${API_BASE}/v1/transactions/evt-789/related-errors`, ({ request }) => {
          const url = new URL(request.url)
          expect(url.searchParams.get('limit')).toBe('20')
          return HttpResponse.json([
            {
              eventId: 'err-1',
              title: 'NullPointerException',
              timestamp: '2024-01-01T00:00:00Z',
            },
          ])
        })
      )

      const result = await api.getRelatedErrors('evt-789')
      expect(result).toHaveLength(1)
      expect(result[0].eventId).toBe('err-1')
    })

    it('passes custom limit param', async () => {
      server.use(
        http.get(`${API_BASE}/v1/transactions/evt-789/related-errors`, ({ request }) => {
          const url = new URL(request.url)
          expect(url.searchParams.get('limit')).toBe('5')
          return HttpResponse.json([])
        })
      )

      const result = await api.getRelatedErrors('evt-789', 5)
      expect(result).toEqual([])
    })
  })
})
