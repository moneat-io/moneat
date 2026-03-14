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

describe('Uptime API', () => {
  beforeEach(() => {
    localStorage.clear()
    sessionStorage.clear()
    sessionStorage.setItem('authenticated', 'true')
  })

  // ──── getUptimeMonitors ────

  it('fetches uptime monitors list', async () => {
    const mock = [{ id: 'mon-1', name: 'API Monitor', url: 'https://api.example.com' }]

    server.use(
      http.get(`${API_BASE}/v1/uptime/monitors`, () => {
        return HttpResponse.json(mock)
      })
    )

    const result = await api.getUptimeMonitors()
    expect(result).toEqual(mock)
  })

  // ──── getUptimeMonitor ────

  it('fetches a single uptime monitor', async () => {
    const mock = { id: 'mon-1', name: 'API Monitor', url: 'https://api.example.com' }

    server.use(
      http.get(`${API_BASE}/v1/uptime/monitors/mon-1`, () => {
        return HttpResponse.json(mock)
      })
    )

    const result = await api.getUptimeMonitor('mon-1')
    expect(result).toEqual(mock)
  })

  // ──── createUptimeMonitor ────

  it('creates an uptime monitor', async () => {
    const mock = { id: 'mon-2', name: 'Web Monitor', url: 'https://web.example.com' }

    server.use(
      http.post(`${API_BASE}/v1/uptime/monitors`, async ({ request }) => {
        const body = (await request.json()) as Record<string, unknown>
        expect(body.name).toBe('Web Monitor')
        expect(body.url).toBe('https://web.example.com')
        return HttpResponse.json(mock)
      })
    )

    const result = await api.createUptimeMonitor({
      name: 'Web Monitor',
      url: 'https://web.example.com',
    } as never)
    expect(result).toEqual(mock)
  })

  // ──── updateUptimeMonitor ────

  it('updates an uptime monitor', async () => {
    const mock = { id: 'mon-1', name: 'Updated Monitor', url: 'https://api.example.com' }

    server.use(
      http.put(`${API_BASE}/v1/uptime/monitors/mon-1`, async ({ request }) => {
        const body = (await request.json()) as Record<string, unknown>
        expect(body.name).toBe('Updated Monitor')
        return HttpResponse.json(mock)
      })
    )

    const result = await api.updateUptimeMonitor('mon-1', { name: 'Updated Monitor' } as never)
    expect(result).toEqual(mock)
  })

  // ──── deleteUptimeMonitor ────

  it('deletes an uptime monitor', async () => {
    server.use(
      http.delete(`${API_BASE}/v1/uptime/monitors/mon-1`, () => {
        return new HttpResponse(null, { status: 204 })
      })
    )

    await api.deleteUptimeMonitor('mon-1')
  })

  // ──── pauseUptimeMonitor ────

  it('pauses an uptime monitor', async () => {
    server.use(
      http.post(`${API_BASE}/v1/uptime/monitors/mon-1/pause`, () => {
        return new HttpResponse(null, { status: 204 })
      })
    )

    await api.pauseUptimeMonitor('mon-1')
  })

  // ──── resumeUptimeMonitor ────

  it('resumes an uptime monitor', async () => {
    server.use(
      http.post(`${API_BASE}/v1/uptime/monitors/mon-1/resume`, () => {
        return new HttpResponse(null, { status: 204 })
      })
    )

    await api.resumeUptimeMonitor('mon-1')
  })

  // ──── getUptimeHeartbeats ────

  it('fetches heartbeats without date range', async () => {
    const mock = [{ timestamp: '2024-02-11T10:00:00Z', status: 'up', responseTime: 120 }]

    server.use(
      http.get(`${API_BASE}/v1/uptime/monitors/mon-1/heartbeats`, () => {
        return HttpResponse.json(mock)
      })
    )

    const result = await api.getUptimeHeartbeats('mon-1')
    expect(result).toEqual(mock)
  })

  it('fetches heartbeats with from and to params', async () => {
    const mock = [{ timestamp: '2024-02-11T10:00:00Z', status: 'up', responseTime: 120 }]

    server.use(
      http.get(`${API_BASE}/v1/uptime/monitors/mon-1/heartbeats`, ({ request }) => {
        const url = new URL(request.url)
        expect(url.searchParams.get('from')).toBe('1000')
        expect(url.searchParams.get('to')).toBe('2000')
        return HttpResponse.json(mock)
      })
    )

    const result = await api.getUptimeHeartbeats('mon-1', 1000, 2000)
    expect(result).toEqual(mock)
  })

  it('fetches heartbeats with only from param', async () => {
    const mock = [{ timestamp: '2024-02-11T10:00:00Z', status: 'up', responseTime: 100 }]

    server.use(
      http.get(`${API_BASE}/v1/uptime/monitors/mon-1/heartbeats`, ({ request }) => {
        const url = new URL(request.url)
        expect(url.searchParams.get('from')).toBe('1000')
        expect(url.searchParams.has('to')).toBe(false)
        return HttpResponse.json(mock)
      })
    )

    const result = await api.getUptimeHeartbeats('mon-1', 1000)
    expect(result).toEqual(mock)
  })
})
