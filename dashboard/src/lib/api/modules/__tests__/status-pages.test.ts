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

describe('Status Pages API', () => {
  beforeEach(() => {
    localStorage.clear()
    sessionStorage.clear()
    sessionStorage.setItem('authenticated', 'true')
  })

  // ──── getStatusPages ────

  it('fetches status pages list', async () => {
    const mock = [{ id: 'sp-1', name: 'Main', slug: 'main' }]

    server.use(
      http.get(`${API_BASE}/v1/status-pages`, () => {
        return HttpResponse.json(mock)
      })
    )

    const result = await api.getStatusPages()
    expect(result).toEqual(mock)
  })

  // ──── getStatusPage ────

  it('fetches a single status page', async () => {
    const mock = { id: 'sp-1', name: 'Main', slug: 'main', monitors: [] }

    server.use(
      http.get(`${API_BASE}/v1/status-pages/sp-1`, () => {
        return HttpResponse.json(mock)
      })
    )

    const result = await api.getStatusPage('sp-1')
    expect(result).toEqual(mock)
  })

  // ──── createStatusPage ────

  it('creates a status page', async () => {
    const mock = { id: 'sp-2', name: 'New Page', slug: 'new-page' }

    server.use(
      http.post(`${API_BASE}/v1/status-pages`, async ({ request }) => {
        const body = (await request.json()) as Record<string, unknown>
        expect(body.name).toBe('New Page')
        return HttpResponse.json(mock)
      })
    )

    const result = await api.createStatusPage({ name: 'New Page' } as never)
    expect(result).toEqual(mock)
  })

  // ──── updateStatusPage ────

  it('updates a status page', async () => {
    const mock = { id: 'sp-1', name: 'Updated', slug: 'main' }

    server.use(
      http.put(`${API_BASE}/v1/status-pages/sp-1`, async ({ request }) => {
        const body = (await request.json()) as Record<string, unknown>
        expect(body.name).toBe('Updated')
        return HttpResponse.json(mock)
      })
    )

    const result = await api.updateStatusPage('sp-1', { name: 'Updated' } as never)
    expect(result).toEqual(mock)
  })

  // ──── deleteStatusPage ────

  it('deletes a status page', async () => {
    server.use(
      http.delete(`${API_BASE}/v1/status-pages/sp-1`, () => {
        return new HttpResponse(null, { status: 204 })
      })
    )

    await api.deleteStatusPage('sp-1')
  })

  // ──── addMonitorsToStatusPage ────

  it('adds monitors to a status page', async () => {
    const monitors = [{ monitorId: 'm-1', displayName: 'API' }]
    const mock = [{ id: 'sm-1', monitorId: 'm-1', displayName: 'API' }]

    server.use(
      http.post(`${API_BASE}/v1/status-pages/sp-1/monitors`, async ({ request }) => {
        const body = (await request.json()) as Record<string, unknown>
        expect(body.monitors).toEqual(monitors)
        return HttpResponse.json(mock)
      })
    )

    const result = await api.addMonitorsToStatusPage('sp-1', monitors as never)
    expect(result).toEqual(mock)
  })

  // ──── removeMonitorFromStatusPage ────

  it('removes a monitor from a status page', async () => {
    server.use(
      http.delete(`${API_BASE}/v1/status-pages/sp-1/monitors/m-1`, () => {
        return new HttpResponse(null, { status: 204 })
      })
    )

    await api.removeMonitorFromStatusPage('sp-1', 'm-1')
  })

  // ──── getStatusPageIncidents ────

  it('fetches status page incidents', async () => {
    const mock = [{ id: 'inc-1', title: 'Outage', status: 'investigating' }]

    server.use(
      http.get(`${API_BASE}/v1/status-pages/sp-1/incidents`, () => {
        return HttpResponse.json(mock)
      })
    )

    const result = await api.getStatusPageIncidents('sp-1')
    expect(result).toEqual(mock)
  })

  // ──── createIncident ────

  it('creates an incident', async () => {
    const mock = { id: 'inc-2', title: 'New Incident', status: 'investigating' }

    server.use(
      http.post(`${API_BASE}/v1/status-pages/sp-1/incidents`, async ({ request }) => {
        const body = (await request.json()) as Record<string, unknown>
        expect(body.title).toBe('New Incident')
        return HttpResponse.json(mock)
      })
    )

    const result = await api.createIncident('sp-1', { title: 'New Incident' } as never)
    expect(result).toEqual(mock)
  })

  // ──── updateIncident ────

  it('updates an incident', async () => {
    const mock = { id: 'inc-1', title: 'Updated', status: 'resolved' }

    server.use(
      http.put(`${API_BASE}/v1/status-pages/sp-1/incidents/inc-1`, async ({ request }) => {
        const body = (await request.json()) as Record<string, unknown>
        expect(body.status).toBe('resolved')
        return HttpResponse.json(mock)
      })
    )

    const result = await api.updateIncident(
      'sp-1',
      'inc-1',
      { status: 'resolved' } as never
    )
    expect(result).toEqual(mock)
  })

  // ──── createIncidentUpdate ────

  it('creates an incident update', async () => {
    const mock = { id: 'inc-1', title: 'Outage', status: 'monitoring' }

    server.use(
      http.post(
        `${API_BASE}/v1/status-pages/sp-1/incidents/inc-1/updates`,
        async ({ request }) => {
          const body = (await request.json()) as Record<string, unknown>
          expect(body.message).toBe('Monitoring')
          return HttpResponse.json(mock)
        }
      )
    )

    const result = await api.createIncidentUpdate(
      'sp-1',
      'inc-1',
      { message: 'Monitoring' } as never
    )
    expect(result).toEqual(mock)
  })

  // ──── addCustomDomain ────

  it('adds a custom domain', async () => {
    const mock = { id: 1, domain: 'status.example.com', verified: false }

    server.use(
      http.post(`${API_BASE}/v1/status-pages/sp-1/domains`, async ({ request }) => {
        const body = (await request.json()) as Record<string, unknown>
        expect(body.domain).toBe('status.example.com')
        return HttpResponse.json(mock)
      })
    )

    const result = await api.addCustomDomain('sp-1', 'status.example.com')
    expect(result).toEqual(mock)
  })

  // ──── verifyCustomDomain ────

  it('verifies a custom domain', async () => {
    const mock = { id: 1, domain: 'status.example.com', verified: true }

    server.use(
      http.post(`${API_BASE}/v1/status-pages/sp-1/domains/1/verify`, () => {
        return HttpResponse.json(mock)
      })
    )

    const result = await api.verifyCustomDomain('sp-1', 1)
    expect(result).toEqual(mock)
  })

  // ──── removeCustomDomain ────

  it('removes a custom domain', async () => {
    server.use(
      http.delete(`${API_BASE}/v1/status-pages/sp-1/domains/1`, () => {
        return new HttpResponse(null, { status: 204 })
      })
    )

    await api.removeCustomDomain('sp-1', 1)
  })

  // ──── getPublicStatusPage ────

  it('fetches a public status page by slug', async () => {
    const mock = { slug: 'main', name: 'Main Status', monitors: [] }

    server.use(
      http.get(`${API_BASE}/public/status/main`, () => {
        return HttpResponse.json(mock)
      })
    )

    const result = await api.getPublicStatusPage('main')
    expect(result).toEqual(mock)
  })

  // ──── getPublicStatusPageByDomain ────

  it('fetches a public status page by domain', async () => {
    const mock = { slug: 'main', name: 'Main Status', monitors: [] }

    server.use(
      http.get(`${API_BASE}/public/status/domain/status.example.com`, () => {
        return HttpResponse.json(mock)
      })
    )

    const result = await api.getPublicStatusPageByDomain('status.example.com')
    expect(result).toEqual(mock)
  })
})
