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

describe('User API', () => {
  beforeEach(() => {
    localStorage.clear()
    sessionStorage.clear()
    sessionStorage.setItem('authenticated', 'true')
  })

  // ──── getCurrentUser ────

  it('fetches the current user', async () => {
    const mock = {
      id: 1,
      email: 'user@example.com',
      name: 'Test User',
      emailVerified: true,
      onboardingCompleted: true,
    }

    server.use(
      http.get(`${API_BASE}/v1/user`, () => {
        return HttpResponse.json(mock)
      })
    )

    const result = await api.getCurrentUser()
    expect(result).toEqual(mock)
  })

  it('clears stale demo epoch when the current user is not demo', async () => {
    sessionStorage.setItem('demoEpochMs', '1700000000000')
    const mock = {
      id: 1,
      email: 'user@example.com',
      name: 'Test User',
      emailVerified: true,
      onboardingCompleted: true,
      demoEpochMs: null,
    }

    server.use(
      http.get(`${API_BASE}/v1/user`, () => {
        return HttpResponse.json(mock)
      })
    )

    await api.getCurrentUser()
    expect(sessionStorage.getItem('demoEpochMs')).toBeNull()
  })

  it('stores demo epoch when the current user is demo', async () => {
    const mock = {
      id: -1,
      email: 'demo@moneat.dev',
      name: 'Demo User',
      emailVerified: true,
      onboardingCompleted: true,
      demoEpochMs: 1700000000000,
    }

    server.use(
      http.get(`${API_BASE}/v1/user`, () => {
        return HttpResponse.json(mock)
      })
    )

    await api.getCurrentUser()
    expect(sessionStorage.getItem('demoEpochMs')).toBe('1700000000000')
  })

  // ──── updateSidebarPreferences ────

  it('updates sidebar preferences', async () => {
    const hiddenItems = ['logs', 'replays']
    const mock = { hiddenItems }

    server.use(
      http.put(`${API_BASE}/v1/user/sidebar-preferences`, async ({ request }) => {
        const body = (await request.json()) as Record<string, unknown>
        expect(body.hiddenItems).toEqual(hiddenItems)
        return HttpResponse.json(mock)
      })
    )

    const result = await api.updateSidebarPreferences(hiddenItems)
    expect(result).toEqual(mock)
  })

  // ──── updateUserTimezone ────

  it('updates user timezone', async () => {
    const mock = { timezone: 'America/New_York' }

    server.use(
      http.put(`${API_BASE}/v1/user/timezone`, async ({ request }) => {
        const body = (await request.json()) as Record<string, unknown>
        expect(body.timezone).toBe('America/New_York')
        return HttpResponse.json(mock)
      })
    )

    const result = await api.updateUserTimezone('America/New_York')
    expect(result).toEqual(mock)
  })

  it('updates user timezone to null', async () => {
    const mock = { timezone: null }

    server.use(
      http.put(`${API_BASE}/v1/user/timezone`, async ({ request }) => {
        const body = (await request.json()) as Record<string, unknown>
        expect(body.timezone).toBeNull()
        return HttpResponse.json(mock)
      })
    )

    const result = await api.updateUserTimezone(null)
    expect(result).toEqual(mock)
  })

  // ──── getOrganizations ────

  it('fetches organizations', async () => {
    const mock = [{ id: 1, name: 'Acme Corp', slug: 'acme-corp' }]

    server.use(
      http.get(`${API_BASE}/v1/organizations`, () => {
        return HttpResponse.json(mock)
      })
    )

    const result = await api.getOrganizations()
    expect(result).toEqual(mock)
  })

  // ──── getOrganizationAccountSettings ────

  it('fetches organization account settings', async () => {
    const mock = { id: 1, name: 'Acme Corp', slug: 'acme-corp', memberCount: 5 }

    server.use(
      http.get(`${API_BASE}/v1/organizations/1`, () => {
        return HttpResponse.json(mock)
      })
    )

    const result = await api.getOrganizationAccountSettings(1)
    expect(result).toEqual(mock)
  })

  // ──── getAccountDeletionValidation ────

  it('fetches account deletion validation', async () => {
    const mock = { canDelete: true, warnings: [] }

    server.use(
      http.get(`${API_BASE}/v1/account/deletion-validation`, () => {
        return HttpResponse.json(mock)
      })
    )

    const result = await api.getAccountDeletionValidation()
    expect(result).toEqual(mock)
  })

  // ──── getOrganizationDeletionValidation ────

  it('fetches organization deletion validation', async () => {
    const mock = { canDelete: true, warnings: [] }

    server.use(
      http.get(`${API_BASE}/v1/organizations/1/deletion-validation`, () => {
        return HttpResponse.json(mock)
      })
    )

    const result = await api.getOrganizationDeletionValidation(1)
    expect(result).toEqual(mock)
  })

  // ──── deleteAccount ────

  it('deletes the account', async () => {
    const mock = { message: 'Account deleted' }

    server.use(
      http.delete(`${API_BASE}/v1/account`, async ({ request }) => {
        const body = (await request.json()) as Record<string, unknown>
        expect(body.confirmation).toBe('DELETE')
        return HttpResponse.json(mock)
      })
    )

    const result = await api.deleteAccount('DELETE')
    expect(result).toEqual(mock)
  })

  // ──── deleteOrganization ────

  it('deletes an organization', async () => {
    const mock = { message: 'Organization deleted' }

    server.use(
      http.delete(`${API_BASE}/v1/organizations/1`, async ({ request }) => {
        const body = (await request.json()) as Record<string, unknown>
        expect(body.confirmation).toBe('DELETE')
        return HttpResponse.json(mock)
      })
    )

    const result = await api.deleteOrganization(1, 'DELETE')
    expect(result).toEqual(mock)
  })

  // ──── getSubscription ────

  it('fetches the subscription', async () => {
    const mock = { tier: { tierName: 'PRO' } }

    server.use(
      http.get(`${API_BASE}/v1/subscription`, () => {
        return HttpResponse.json(mock)
      })
    )

    const result = await api.getSubscription()
    expect(result).toEqual(mock)
  })

  it('returns null when subscription is not found', async () => {
    server.use(
      http.get(`${API_BASE}/v1/subscription`, () => {
        return new HttpResponse(null, { status: 404 })
      })
    )

    const result = await api.getSubscription()
    expect(result).toBeNull()
  })

  it('rethrows non-404 errors from getSubscription', async () => {
    server.use(
      http.get(`${API_BASE}/v1/subscription`, () => {
        return new HttpResponse(
          JSON.stringify({ error: 'Internal server error' }),
          { status: 500 }
        )
      })
    )

    await expect(api.getSubscription()).rejects.toThrow()
  })
})
