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

describe('Notifications API', () => {
  beforeEach(() => {
    localStorage.clear()
    sessionStorage.clear()
    sessionStorage.setItem('authenticated', 'true')
  })

  // ──── getNotificationPreferences ────

  it('fetches notification preferences', async () => {
    const mockPrefs = {
      global: {
        issueAlerts: true,
        errorAlerts: true,
        weeklySummary: false,
        alertFrequencyMinutes: 15,
      },
      projects: [],
    }

    server.use(
      http.get(`${API_BASE}/v1/notification-preferences`, () => {
        return HttpResponse.json(mockPrefs)
      })
    )

    const result = await api.getNotificationPreferences()
    expect(result).toEqual(mockPrefs)
  })

  // ──── updateNotificationPreferences ────

  it('updates global notification preferences', async () => {
    server.use(
      http.put(`${API_BASE}/v1/notification-preferences`, async ({ request }) => {
        const body = (await request.json()) as Record<string, unknown>
        expect(body.issueAlerts).toBe(false)
        return new HttpResponse(null, { status: 204 })
      })
    )

    await api.updateNotificationPreferences({ issueAlerts: false })
  })

  // ──── updateProjectNotificationPreferences ────

  it('updates project notification preferences', async () => {
    server.use(
      http.put(
        `${API_BASE}/v1/notification-preferences/proj-5`,
        async ({ request }) => {
          const body = (await request.json()) as Record<string, unknown>
          expect(body.weeklySummary).toBe(true)
          return new HttpResponse(null, { status: 204 })
        }
      )
    )

    await api.updateProjectNotificationPreferences('proj-5', { weeklySummary: true })
  })

  // ──── deleteProjectNotificationPreferences ────

  it('deletes project notification preferences', async () => {
    server.use(
      http.delete(`${API_BASE}/v1/notification-preferences/proj-5`, () => {
        return new HttpResponse(null, { status: 204 })
      })
    )

    await api.deleteProjectNotificationPreferences('proj-5')
  })

  // ──── getAlertNotificationPreferences ────

  it('fetches alert notification preferences', async () => {
    const mockPrefs = {
      preferences: [
        {
          alertSource: 'UPTIME_MONITOR',
          emailEnabled: true,
          slackEnabled: false,
          discordEnabled: false,
        },
      ],
    }

    server.use(
      http.get(`${API_BASE}/v1/alert-notification-preferences`, () => {
        return HttpResponse.json(mockPrefs)
      })
    )

    const result = await api.getAlertNotificationPreferences()
    expect(result).toEqual(mockPrefs.preferences)
  })

  it('returns empty array when preferences is null', async () => {
    server.use(
      http.get(`${API_BASE}/v1/alert-notification-preferences`, () => {
        return HttpResponse.json({ preferences: null })
      })
    )

    const result = await api.getAlertNotificationPreferences()
    expect(result).toEqual([])
  })

  // ──── updateAlertNotificationPreference ────

  it('updates alert notification preference', async () => {
    const mockPref = {
      alertSource: 'UPTIME_MONITOR',
      emailEnabled: true,
      slackEnabled: true,
      discordEnabled: false,
    }

    server.use(
      http.put(
        `${API_BASE}/v1/alert-notification-preferences/UPTIME_MONITOR`,
        async ({ request }) => {
          const body = (await request.json()) as Record<string, unknown>
          expect(body.emailEnabled).toBe(true)
          expect(body.slackEnabled).toBe(true)
          expect(body.discordEnabled).toBe(false)
          return HttpResponse.json(mockPref)
        }
      )
    )

    const result = await api.updateAlertNotificationPreference('UPTIME_MONITOR', {
      emailEnabled: true,
      slackEnabled: true,
      discordEnabled: false,
    })
    expect(result).toEqual(mockPref)
  })
})
