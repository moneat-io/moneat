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

describe('Analytics API – branch coverage', () => {
  beforeEach(() => {
    localStorage.clear()
    sessionStorage.clear()
    sessionStorage.setItem('authenticated', 'true')
  })

  // ──── buildAnalyticsQuery – param combinations ────

  describe('buildAnalyticsQuery – param combinations', () => {
    it('passes date_from and date_to params', async () => {
      server.use(
        http.get(`${API_BASE}/v1/analytics/1/overview`, ({ request }) => {
          const url = new URL(request.url)
          expect(url.searchParams.get('date_from')).toBe('2024-01-01')
          expect(url.searchParams.get('date_to')).toBe('2024-01-31')
          return HttpResponse.json({
            uniqueVisitors: 0,
            totalPageviews: 0,
            bounceRate: 0,
            avgVisitDuration: 0,
            viewsPerVisit: 0,
          })
        })
      )

      await api.getAnalyticsOverview(1, {
        from: '2024-01-01',
        to: '2024-01-31',
      })
    })

    it('omits comparison param when value is "none"', async () => {
      server.use(
        http.get(`${API_BASE}/v1/analytics/1/overview`, ({ request }) => {
          const url = new URL(request.url)
          expect(url.searchParams.has('comparison')).toBe(false)
          return HttpResponse.json({
            uniqueVisitors: 0,
            totalPageviews: 0,
            bounceRate: 0,
            avgVisitDuration: 0,
            viewsPerVisit: 0,
          })
        })
      )

      await api.getAnalyticsOverview(1, { comparison: 'none' })
    })

    it('passes filter array params', async () => {
      server.use(
        http.get(`${API_BASE}/v1/analytics/1/overview`, ({ request }) => {
          const url = new URL(request.url)
          const filters = url.searchParams.getAll('filters[]')
          expect(filters).toEqual(['country:is:US', 'browser:is:Chrome'])
          return HttpResponse.json({
            uniqueVisitors: 0,
            totalPageviews: 0,
            bounceRate: 0,
            avgVisitDuration: 0,
            viewsPerVisit: 0,
          })
        })
      )

      await api.getAnalyticsOverview(1, {
        filters: [
          { property: 'country', operator: 'is', value: 'US' },
          { property: 'browser', operator: 'is', value: 'Chrome' },
        ],
      })
    })

    it('produces empty query string when no params given', async () => {
      server.use(
        http.get(`${API_BASE}/v1/analytics/1/overview`, ({ request }) => {
          const url = new URL(request.url)
          expect(url.search).toBe('')
          return HttpResponse.json({
            uniqueVisitors: 10,
            totalPageviews: 20,
            bounceRate: 5,
            avgVisitDuration: 30,
            viewsPerVisit: 2,
          })
        })
      )

      await api.getAnalyticsOverview(1)
    })
  })

  // ──── normalizeAnalyticsOverview – defaults ────

  describe('normalizeAnalyticsOverview – defaults', () => {
    it('defaults missing overview fields to 0', async () => {
      server.use(
        http.get(`${API_BASE}/v1/analytics/1/overview`, () => {
          return HttpResponse.json({})
        })
      )

      const result = await api.getAnalyticsOverview(1)
      expect(result.uniqueVisitors).toBe(0)
      expect(result.totalPageviews).toBe(0)
      expect(result.bounceRate).toBe(0)
      expect(result.avgVisitDuration).toBe(0)
      expect(result.viewsPerVisit).toBe(0)
      expect(result.comparison).toBeUndefined()
    })

    it('includes comparison when any comp field is present', async () => {
      server.use(
        http.get(`${API_BASE}/v1/analytics/1/overview`, () => {
          return HttpResponse.json({
            uniqueVisitors: 10,
            totalPageviews: 20,
            bounceRate: 5,
            avgVisitDuration: 30,
            viewsPerVisit: 2,
            compBounceRate: 8,
          })
        })
      )

      const result = await api.getAnalyticsOverview(1)
      expect(result.comparison).toBeDefined()
      expect(result.comparison!.bounceRate).toBe(8)
      expect(result.comparison!.uniqueVisitors).toBe(0)
      expect(result.comparison!.totalPageviews).toBe(0)
      expect(result.comparison!.avgVisitDuration).toBe(0)
      expect(result.comparison!.viewsPerVisit).toBe(0)
    })

    it('detects comparison via compPageviews', async () => {
      server.use(
        http.get(`${API_BASE}/v1/analytics/1/overview`, () => {
          return HttpResponse.json({
            visitors: 5,
            pageviews: 10,
            compPageviews: 15,
          })
        })
      )

      const result = await api.getAnalyticsOverview(1)
      expect(result.comparison).toBeDefined()
      expect(result.comparison!.totalPageviews).toBe(15)
    })

    it('detects comparison via compAvgVisitDuration', async () => {
      server.use(
        http.get(`${API_BASE}/v1/analytics/1/overview`, () => {
          return HttpResponse.json({
            uniqueVisitors: 1,
            totalPageviews: 1,
            compAvgVisitDuration: 99,
          })
        })
      )

      const result = await api.getAnalyticsOverview(1)
      expect(result.comparison).toBeDefined()
      expect(result.comparison!.avgVisitDuration).toBe(99)
    })

    it('detects comparison via compViewsPerVisit', async () => {
      server.use(
        http.get(`${API_BASE}/v1/analytics/1/overview`, () => {
          return HttpResponse.json({
            uniqueVisitors: 1,
            totalPageviews: 1,
            compViewsPerVisit: 3.5,
          })
        })
      )

      const result = await api.getAnalyticsOverview(1)
      expect(result.comparison).toBeDefined()
      expect(result.comparison!.viewsPerVisit).toBe(3.5)
    })
  })

  // ──── normalizeAnalyticsTimeseries – defaults ────

  describe('normalizeAnalyticsTimeseries – defaults', () => {
    it('defaults missing visitors and pageviews to 0', async () => {
      server.use(
        http.get(`${API_BASE}/v1/analytics/1/timeseries`, () => {
          return HttpResponse.json([{ timestamp: '2024-01-01' }])
        })
      )

      const result = await api.getAnalyticsTimeseries(1)
      expect(result[0].visitors).toBe(0)
      expect(result[0].pageviews).toBe(0)
    })

    it('uses empty string for missing timestamp and date', async () => {
      server.use(
        http.get(`${API_BASE}/v1/analytics/1/timeseries`, () => {
          return HttpResponse.json([{ visitors: 5, pageviews: 10 }])
        })
      )

      const result = await api.getAnalyticsTimeseries(1)
      expect(result[0].timestamp).toBe('')
    })
  })

  // ──── normalizeAnalyticsBreakdown – non-array responses ────

  describe('normalizeAnalyticsBreakdown – non-array', () => {
    it('handles wrapped response with no results array', async () => {
      server.use(
        http.get(`${API_BASE}/v1/analytics/1/pages`, () => {
          return HttpResponse.json({ total: 0 })
        })
      )

      const result = await api.getAnalyticsPages(1)
      expect(result).toEqual([])
    })

    it('returns results array from wrapped response', async () => {
      server.use(
        http.get(`${API_BASE}/v1/analytics/1/sources`, () => {
          return HttpResponse.json({
            results: [{ name: 'Google', visitors: 100, pageviews: 200 }],
          })
        })
      )

      const result = await api.getAnalyticsSources(1)
      expect(result).toHaveLength(1)
    })
  })

  // ──── getAnalyticsDevices – query string separator ────

  describe('getAnalyticsDevices – query string separator', () => {
    it('uses ? when no other params exist', async () => {
      server.use(
        http.get(`${API_BASE}/v1/analytics/1/devices`, ({ request }) => {
          const url = new URL(request.url)
          expect(url.searchParams.get('type')).toBe('device')
          return HttpResponse.json([])
        })
      )

      await api.getAnalyticsDevices(1, 'device')
    })

    it('uses & when other params exist', async () => {
      server.use(
        http.get(`${API_BASE}/v1/analytics/1/devices`, ({ request }) => {
          const url = new URL(request.url)
          expect(url.searchParams.get('period')).toBe('30d')
          expect(url.searchParams.get('type')).toBe('browser')
          return HttpResponse.json([])
        })
      )

      await api.getAnalyticsDevices(1, 'browser', { period: '30d' })
    })
  })

  // ──── getAnalyticsFunnel – query string separator ────

  describe('getAnalyticsFunnel – query string separator', () => {
    it('uses ? separator when no analytics params', async () => {
      server.use(
        http.get(`${API_BASE}/v1/analytics/1/funnel`, ({ request }) => {
          const url = new URL(request.url)
          expect(url.searchParams.getAll('steps[]')).toEqual(['/a', '/b'])
          return HttpResponse.json({ steps: [] })
        })
      )

      await api.getAnalyticsFunnel(1, ['/a', '/b'])
    })

    it('uses & separator when analytics params exist', async () => {
      server.use(
        http.get(`${API_BASE}/v1/analytics/1/funnel`, ({ request }) => {
          const url = new URL(request.url)
          expect(url.searchParams.get('period')).toBe('7d')
          expect(url.searchParams.getAll('steps[]')).toEqual(['/x'])
          return HttpResponse.json({ steps: [] })
        })
      )

      await api.getAnalyticsFunnel(1, ['/x'], { period: '7d' })
    })
  })

  // ──── getAnalyticsRealtime – defaults ────

  describe('getAnalyticsRealtime – defaults', () => {
    it('defaults to 0 when both fields are missing', async () => {
      server.use(
        http.get(`${API_BASE}/v1/analytics/1/realtime`, () => {
          return HttpResponse.json({})
        })
      )

      const result = await api.getAnalyticsRealtime(1)
      expect(result.currentVisitors).toBe(0)
    })
  })
})
