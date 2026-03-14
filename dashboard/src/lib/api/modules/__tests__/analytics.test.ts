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

describe('Analytics API', () => {
  beforeEach(() => {
    localStorage.clear()
    sessionStorage.clear()
    sessionStorage.setItem('authenticated', 'true')
  })

  // ──── getAnalyticsOverview ────

  describe('getAnalyticsOverview', () => {
    it('fetches analytics overview for a project', async () => {
      server.use(
        http.get(`${API_BASE}/v1/analytics/1/overview`, () => {
          return HttpResponse.json({
            uniqueVisitors: 500,
            totalPageviews: 1200,
            bounceRate: 45.5,
            avgVisitDuration: 120,
            viewsPerVisit: 2.4,
          })
        })
      )

      const result = await api.getAnalyticsOverview(1)
      expect(result.uniqueVisitors).toBe(500)
      expect(result.totalPageviews).toBe(1200)
      expect(result.bounceRate).toBe(45.5)
      expect(result.avgVisitDuration).toBe(120)
      expect(result.viewsPerVisit).toBe(2.4)
    })

    it('normalizes snake_case response fields', async () => {
      server.use(
        http.get(`${API_BASE}/v1/analytics/1/overview`, () => {
          return HttpResponse.json({
            visitors: 100,
            pageviews: 300,
            bounceRate: 30,
            avgVisitDuration: 60,
            viewsPerVisit: 3,
          })
        })
      )

      const result = await api.getAnalyticsOverview(1)
      expect(result.uniqueVisitors).toBe(100)
      expect(result.totalPageviews).toBe(300)
    })

    it('passes analytics query params', async () => {
      server.use(
        http.get(`${API_BASE}/v1/analytics/2/overview`, ({ request }) => {
          const url = new URL(request.url)
          expect(url.searchParams.get('period')).toBe('7d')
          expect(url.searchParams.get('comparison')).toBe('previous_period')
          return HttpResponse.json({
            uniqueVisitors: 50,
            totalPageviews: 100,
            bounceRate: 0,
            avgVisitDuration: 0,
            viewsPerVisit: 0,
            compVisitors: 40,
            compPageviews: 80,
            compBounceRate: 10,
            compAvgVisitDuration: 55,
            compViewsPerVisit: 2,
          })
        })
      )

      const result = await api.getAnalyticsOverview(2, {
        period: '7d',
        comparison: 'previous_period',
      })
      expect(result.comparison).toBeDefined()
      expect(result.comparison!.uniqueVisitors).toBe(40)
    })
  })

  // ──── getAnalyticsTimeseries ────

  describe('getAnalyticsTimeseries', () => {
    it('fetches timeseries data', async () => {
      server.use(
        http.get(`${API_BASE}/v1/analytics/1/timeseries`, () => {
          return HttpResponse.json([
            { timestamp: '2024-01-01', visitors: 10, pageviews: 20 },
            { timestamp: '2024-01-02', visitors: 15, pageviews: 30 },
          ])
        })
      )

      const result = await api.getAnalyticsTimeseries(1)
      expect(result).toHaveLength(2)
      expect(result[0].timestamp).toBe('2024-01-01')
      expect(result[0].visitors).toBe(10)
      expect(result[1].pageviews).toBe(30)
    })

    it('normalizes date field to timestamp', async () => {
      server.use(
        http.get(`${API_BASE}/v1/analytics/1/timeseries`, () => {
          return HttpResponse.json([
            { date: '2024-06-01', visitors: 5, pageviews: 8 },
          ])
        })
      )

      const result = await api.getAnalyticsTimeseries(1)
      expect(result[0].timestamp).toBe('2024-06-01')
    })
  })

  // ──── getAnalyticsPages ────

  describe('getAnalyticsPages', () => {
    it('fetches page breakdown', async () => {
      const mockData = [
        { name: '/home', visitors: 100, pageviews: 200 },
        { name: '/about', visitors: 50, pageviews: 80 },
      ]

      server.use(
        http.get(`${API_BASE}/v1/analytics/1/pages`, () => {
          return HttpResponse.json(mockData)
        })
      )

      const result = await api.getAnalyticsPages(1)
      expect(result).toEqual(mockData)
    })

    it('handles wrapped response format', async () => {
      server.use(
        http.get(`${API_BASE}/v1/analytics/1/pages`, () => {
          return HttpResponse.json({
            results: [{ name: '/home', visitors: 10, pageviews: 20 }],
          })
        })
      )

      const result = await api.getAnalyticsPages(1)
      expect(result).toHaveLength(1)
      expect(result[0].name).toBe('/home')
    })
  })

  // ──── getAnalyticsEntryPages ────

  describe('getAnalyticsEntryPages', () => {
    it('fetches entry pages', async () => {
      const mockData = [{ name: '/landing', visitors: 80, pageviews: 80 }]

      server.use(
        http.get(`${API_BASE}/v1/analytics/1/entry-pages`, () => {
          return HttpResponse.json(mockData)
        })
      )

      const result = await api.getAnalyticsEntryPages(1)
      expect(result).toEqual(mockData)
    })
  })

  // ──── getAnalyticsExitPages ────

  describe('getAnalyticsExitPages', () => {
    it('fetches exit pages', async () => {
      const mockData = [{ name: '/checkout', visitors: 30, pageviews: 30 }]

      server.use(
        http.get(`${API_BASE}/v1/analytics/1/exit-pages`, () => {
          return HttpResponse.json(mockData)
        })
      )

      const result = await api.getAnalyticsExitPages(1)
      expect(result).toEqual(mockData)
    })
  })

  // ──── getAnalyticsSources ────

  describe('getAnalyticsSources', () => {
    it('fetches traffic sources', async () => {
      const mockData = [
        { name: 'Google', visitors: 200, pageviews: 400 },
        { name: 'Direct', visitors: 150, pageviews: 300 },
      ]

      server.use(
        http.get(`${API_BASE}/v1/analytics/1/sources`, () => {
          return HttpResponse.json(mockData)
        })
      )

      const result = await api.getAnalyticsSources(1)
      expect(result).toEqual(mockData)
    })
  })

  // ──── getAnalyticsUtm ────

  describe('getAnalyticsUtm', () => {
    it('fetches UTM breakdown for a param', async () => {
      const mockData = [
        { name: 'summer-sale', visitors: 50, pageviews: 100 },
      ]

      server.use(
        http.get(`${API_BASE}/v1/analytics/1/utm/campaign`, () => {
          return HttpResponse.json(mockData)
        })
      )

      const result = await api.getAnalyticsUtm(1, 'campaign')
      expect(result).toEqual(mockData)
    })
  })

  // ──── getAnalyticsLocations ────

  describe('getAnalyticsLocations', () => {
    it('fetches location breakdown', async () => {
      const mockData = [
        { name: 'United States', visitors: 300, pageviews: 600 },
      ]

      server.use(
        http.get(`${API_BASE}/v1/analytics/1/locations`, () => {
          return HttpResponse.json(mockData)
        })
      )

      const result = await api.getAnalyticsLocations(1)
      expect(result).toEqual(mockData)
    })
  })

  // ──── getAnalyticsDevices ────

  describe('getAnalyticsDevices', () => {
    it('fetches device breakdown by type', async () => {
      const mockData = [
        { name: 'Chrome', visitors: 200, pageviews: 400 },
        { name: 'Firefox', visitors: 100, pageviews: 200 },
      ]

      server.use(
        http.get(`${API_BASE}/v1/analytics/1/devices`, ({ request }) => {
          const url = new URL(request.url)
          expect(url.searchParams.get('type')).toBe('browser')
          return HttpResponse.json(mockData)
        })
      )

      const result = await api.getAnalyticsDevices(1, 'browser')
      expect(result).toEqual(mockData)
    })

    it('fetches OS device breakdown', async () => {
      server.use(
        http.get(`${API_BASE}/v1/analytics/1/devices`, ({ request }) => {
          const url = new URL(request.url)
          expect(url.searchParams.get('type')).toBe('os')
          return HttpResponse.json([{ name: 'macOS', visitors: 150, pageviews: 300 }])
        })
      )

      const result = await api.getAnalyticsDevices(1, 'os')
      expect(result).toHaveLength(1)
      expect(result[0].name).toBe('macOS')
    })
  })

  // ──── getAnalyticsEvents ────

  describe('getAnalyticsEvents', () => {
    it('fetches custom events', async () => {
      const mockData = [
        { name: 'signup', visitors: 20, pageviews: 20 },
      ]

      server.use(
        http.get(`${API_BASE}/v1/analytics/1/events`, () => {
          return HttpResponse.json(mockData)
        })
      )

      const result = await api.getAnalyticsEvents(1)
      expect(result).toEqual(mockData)
    })
  })

  // ──── getAnalyticsRealtime ────

  describe('getAnalyticsRealtime', () => {
    it('fetches realtime visitor count', async () => {
      server.use(
        http.get(`${API_BASE}/v1/analytics/1/realtime`, () => {
          return HttpResponse.json({ currentVisitors: 42 })
        })
      )

      const result = await api.getAnalyticsRealtime(1)
      expect(result.currentVisitors).toBe(42)
    })

    it('normalizes visitors field to currentVisitors', async () => {
      server.use(
        http.get(`${API_BASE}/v1/analytics/1/realtime`, () => {
          return HttpResponse.json({ visitors: 7 })
        })
      )

      const result = await api.getAnalyticsRealtime(1)
      expect(result.currentVisitors).toBe(7)
    })
  })

  // ──── getAnalyticsFunnel ────

  describe('getAnalyticsFunnel', () => {
    it('fetches funnel data with steps', async () => {
      const mockFunnel = {
        steps: [
          { name: '/home', visitors: 100, dropoff: 0 },
          { name: '/signup', visitors: 60, dropoff: 40 },
        ],
      }

      server.use(
        http.get(`${API_BASE}/v1/analytics/1/funnel`, ({ request }) => {
          const url = new URL(request.url)
          const steps = url.searchParams.getAll('steps[]')
          expect(steps).toEqual(['/home', '/signup'])
          return HttpResponse.json(mockFunnel)
        })
      )

      const result = await api.getAnalyticsFunnel(1, ['/home', '/signup'])
      expect(result.steps).toHaveLength(2)
    })
  })
})
