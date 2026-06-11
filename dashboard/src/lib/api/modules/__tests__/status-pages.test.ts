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
const STATUS_PAGE_ID = '11111111-1111-4111-8111-111111111111'
const STATUS_PAGE_CREATED_ID = '22222222-2222-4222-8222-222222222222'
const STATUS_PAGE_MONITOR_ID = '33333333-3333-4333-8333-333333333333'
const UPTIME_MONITOR_ID = '44444444-4444-4444-8444-444444444444'
const INCIDENT_ID = '55555555-5555-4555-8555-555555555555'
const INCIDENT_CREATED_ID = '66666666-6666-4666-8666-666666666666'
const CUSTOM_DOMAIN_ID = '77777777-7777-4777-8777-777777777777'

describe('Status Pages API', () => {
  beforeEach(() => {
    localStorage.clear()
    sessionStorage.clear()
    sessionStorage.setItem('authenticated', 'true')
  })

  // ──── getStatusPages ────

  it('fetches status pages list', async () => {
    const mock = [{ id: STATUS_PAGE_ID, name: 'Main', slug: 'main' }]

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
    const mock = { id: STATUS_PAGE_ID, name: 'Main', slug: 'main', monitors: [] }

    server.use(
      http.get(`${API_BASE}/v1/status-pages/${STATUS_PAGE_ID}`, () => {
        return HttpResponse.json(mock)
      })
    )

    const result = await api.getStatusPage(STATUS_PAGE_ID)
    expect(result).toEqual(mock)
  })

  // ──── createStatusPage ────

  it('creates a status page', async () => {
    const mock = { id: STATUS_PAGE_CREATED_ID, name: 'New Page', slug: 'new-page' }

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
    const mock = { id: STATUS_PAGE_ID, name: 'Updated', slug: 'main' }

    server.use(
      http.put(`${API_BASE}/v1/status-pages/${STATUS_PAGE_ID}`, async ({ request }) => {
        const body = (await request.json()) as Record<string, unknown>
        expect(body.name).toBe('Updated')
        return HttpResponse.json(mock)
      })
    )

    const result = await api.updateStatusPage(STATUS_PAGE_ID, { name: 'Updated' } as never)
    expect(result).toEqual(mock)
  })

  // ──── deleteStatusPage ────

  it('deletes a status page', async () => {
    server.use(
      http.delete(`${API_BASE}/v1/status-pages/${STATUS_PAGE_ID}`, () => {
        return new HttpResponse(null, { status: 204 })
      })
    )

    await api.deleteStatusPage(STATUS_PAGE_ID)
  })

  // ──── addMonitorsToStatusPage ────

  it('adds monitors to a status page', async () => {
    const monitors = [{ monitorId: UPTIME_MONITOR_ID, displayName: 'API' }]
    const mock = [{ id: STATUS_PAGE_MONITOR_ID, monitorId: UPTIME_MONITOR_ID, displayName: 'API' }]

    server.use(
      http.post(`${API_BASE}/v1/status-pages/${STATUS_PAGE_ID}/monitors`, async ({ request }) => {
        const body = (await request.json()) as Record<string, unknown>
        expect(body.monitors).toEqual(monitors)
        return HttpResponse.json(mock)
      })
    )

    const result = await api.addMonitorsToStatusPage(STATUS_PAGE_ID, monitors as never)
    expect(result).toEqual(mock)
  })

  // ──── removeMonitorFromStatusPage ────

  it('removes a monitor from a status page', async () => {
    server.use(
      http.delete(`${API_BASE}/v1/status-pages/${STATUS_PAGE_ID}/monitors/${UPTIME_MONITOR_ID}`, () => {
        return new HttpResponse(null, { status: 204 })
      })
    )

    await api.removeMonitorFromStatusPage(STATUS_PAGE_ID, UPTIME_MONITOR_ID)
  })

  // ──── getStatusPageIncidents ────

  it('fetches status page incidents', async () => {
    const mock = [{ id: INCIDENT_ID, title: 'Outage', status: 'investigating' }]

    server.use(
      http.get(`${API_BASE}/v1/status-pages/${STATUS_PAGE_ID}/incidents`, () => {
        return HttpResponse.json(mock)
      })
    )

    const result = await api.getStatusPageIncidents(STATUS_PAGE_ID)
    expect(result).toEqual(mock)
  })

  // ──── createIncident ────

  it('creates an incident', async () => {
    const mock = { id: INCIDENT_CREATED_ID, title: 'New Incident', status: 'investigating' }

    server.use(
      http.post(`${API_BASE}/v1/status-pages/${STATUS_PAGE_ID}/incidents`, async ({ request }) => {
        const body = (await request.json()) as Record<string, unknown>
        expect(body.title).toBe('New Incident')
        return HttpResponse.json(mock)
      })
    )

    const result = await api.createIncident(STATUS_PAGE_ID, { title: 'New Incident' } as never)
    expect(result).toEqual(mock)
  })

  // ──── updateIncident ────

  it('updates an incident', async () => {
    const mock = { id: INCIDENT_ID, title: 'Updated', status: 'resolved' }

    server.use(
      http.put(`${API_BASE}/v1/status-pages/${STATUS_PAGE_ID}/incidents/${INCIDENT_ID}`, async ({ request }) => {
        const body = (await request.json()) as Record<string, unknown>
        expect(body.status).toBe('resolved')
        return HttpResponse.json(mock)
      })
    )

    const result = await api.updateIncident(
      STATUS_PAGE_ID,
      INCIDENT_ID,
      { status: 'resolved' } as never
    )
    expect(result).toEqual(mock)
  })

  // ──── createIncidentUpdate ────

  it('creates an incident update', async () => {
    const mock = { id: INCIDENT_ID, title: 'Outage', status: 'monitoring' }

    server.use(
      http.post(
        `${API_BASE}/v1/status-pages/${STATUS_PAGE_ID}/incidents/${INCIDENT_ID}/updates`,
        async ({ request }) => {
          const body = (await request.json()) as Record<string, unknown>
          expect(body.message).toBe('Monitoring')
          return HttpResponse.json(mock)
        }
      )
    )

    const result = await api.createIncidentUpdate(
      STATUS_PAGE_ID,
      INCIDENT_ID,
      { message: 'Monitoring' } as never
    )
    expect(result).toEqual(mock)
  })

  // ──── addCustomDomain ────

  it('adds a custom domain', async () => {
    const mock = { id: CUSTOM_DOMAIN_ID, domain: 'status.example.com', verified: false }

    server.use(
      http.post(`${API_BASE}/v1/status-pages/${STATUS_PAGE_ID}/domains`, async ({ request }) => {
        const body = (await request.json()) as Record<string, unknown>
        expect(body.domain).toBe('status.example.com')
        return HttpResponse.json(mock)
      })
    )

    const result = await api.addCustomDomain(STATUS_PAGE_ID, 'status.example.com')
    expect(result).toEqual(mock)
  })

  // ──── verifyCustomDomain ────

  it('verifies a custom domain', async () => {
    const mock = { id: CUSTOM_DOMAIN_ID, domain: 'status.example.com', verified: true }

    server.use(
      http.post(`${API_BASE}/v1/status-pages/${STATUS_PAGE_ID}/domains/${CUSTOM_DOMAIN_ID}/verify`, () => {
        return HttpResponse.json(mock)
      })
    )

    const result = await api.verifyCustomDomain(STATUS_PAGE_ID, CUSTOM_DOMAIN_ID)
    expect(result).toEqual(mock)
  })

  // ──── removeCustomDomain ────

  it('removes a custom domain', async () => {
    server.use(
      http.delete(`${API_BASE}/v1/status-pages/${STATUS_PAGE_ID}/domains/${CUSTOM_DOMAIN_ID}`, () => {
        return new HttpResponse(null, { status: 204 })
      })
    )

    await api.removeCustomDomain(STATUS_PAGE_ID, CUSTOM_DOMAIN_ID)
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
