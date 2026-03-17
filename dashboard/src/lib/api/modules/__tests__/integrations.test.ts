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

describe('Integrations API', () => {
  beforeEach(() => {
    localStorage.clear()
    sessionStorage.clear()
    sessionStorage.setItem('authenticated', 'true')
  })

  // ──── getIntegrations ────

  describe('getIntegrations', () => {
    it('fetches all integrations', async () => {
      const mockIntegrations = [
        { id: 1, type: 'slack', enabled: true },
        { id: 2, type: 'discord', enabled: false },
      ]

      server.use(
        http.get(`${API_BASE}/v1/integrations`, () => {
          return HttpResponse.json(mockIntegrations)
        })
      )

      const result = await api.getIntegrations()
      expect(result).toHaveLength(2)
      expect(result[0].type).toBe('slack')
    })
  })

  // ──── Slack Integration ────

  describe('startSlackOAuth', () => {
    it('initiates Slack OAuth flow', async () => {
      server.use(
        http.get(`${API_BASE}/v1/integrations/slack/oauth/start`, () => {
          return HttpResponse.json({ url: 'https://slack.com/oauth/authorize?...' })
        })
      )

      const result = await api.startSlackOAuth()
      expect(result.url).toContain('slack.com')
    })
  })

  describe('getSlackChannels', () => {
    it('fetches Slack channels', async () => {
      server.use(
        http.get(`${API_BASE}/v1/integrations/slack/channels`, () => {
          return HttpResponse.json({
            channels: [
              { id: 'C001', name: 'general' },
              { id: 'C002', name: 'alerts' },
            ],
          })
        })
      )

      const result = await api.getSlackChannels()
      expect(result.channels).toHaveLength(2)
    })
  })

  describe('updateSlackChannel', () => {
    it('updates the Slack notification channel', async () => {
      server.use(
        http.put(`${API_BASE}/v1/integrations/slack/channel`, async ({ request }) => {
          const body = (await request.json()) as Record<string, unknown>
          expect(body.channelId).toBe('C001')
          expect(body.channelName).toBe('alerts')
          return new HttpResponse(null, { status: 204 })
        })
      )

      await api.updateSlackChannel('C001', 'alerts')
    })
  })

  describe('toggleSlackIntegration', () => {
    it('toggles Slack integration on/off', async () => {
      server.use(
        http.put(`${API_BASE}/v1/integrations/slack/toggle`, () => {
          return new HttpResponse(null, { status: 204 })
        })
      )

      await api.toggleSlackIntegration()
    })
  })

  describe('deleteSlackIntegration', () => {
    it('deletes Slack integration', async () => {
      server.use(
        http.delete(`${API_BASE}/v1/integrations/slack`, () => {
          return new HttpResponse(null, { status: 204 })
        })
      )

      await api.deleteSlackIntegration()
    })
  })

  describe('testSlackIntegration', () => {
    it('sends a test Slack notification', async () => {
      server.use(
        http.post(`${API_BASE}/v1/integrations/slack/test`, () => {
          return HttpResponse.json({ success: true })
        })
      )

      const result = await api.testSlackIntegration()
      expect(result.success).toBe(true)
    })
  })

  describe('getSlackUsergroups', () => {
    it('fetches Slack usergroups', async () => {
      const mockGroups = [
        { id: 'S001', handle: 'oncall-team', name: 'On-Call Team' },
      ]

      server.use(
        http.get(`${API_BASE}/v1/integrations/slack/usergroups`, () => {
          return HttpResponse.json(mockGroups)
        })
      )

      const result = await api.getSlackUsergroups()
      expect(result).toHaveLength(1)
      expect(result[0].handle).toBe('oncall-team')
    })
  })

  // ──── Schedule Slack Usergroup ────

  describe('setScheduleSlackUsergroup', () => {
    it('sets Slack usergroup for a schedule', async () => {
      server.use(
        http.put(
          `${API_BASE}/v1/on-call/schedules/10/slack-usergroup`,
          async ({ request }) => {
            const body = (await request.json()) as Record<string, unknown>
            expect(body.usergroupId).toBe('S001')
            expect(body.usergroupHandle).toBe('oncall-team')
            return new HttpResponse(null, { status: 204 })
          }
        )
      )

      await api.setScheduleSlackUsergroup(10, 'S001', 'oncall-team')
    })
  })

  describe('removeScheduleSlackUsergroup', () => {
    it('removes Slack usergroup from a schedule', async () => {
      server.use(
        http.delete(`${API_BASE}/v1/on-call/schedules/10/slack-usergroup`, () => {
          return new HttpResponse(null, { status: 204 })
        })
      )

      await api.removeScheduleSlackUsergroup(10)
    })
  })

  // ──── Discord Integration ────

  describe('startDiscordOAuth', () => {
    it('initiates Discord OAuth flow', async () => {
      server.use(
        http.get(`${API_BASE}/v1/integrations/discord/oauth/start`, () => {
          return HttpResponse.json({ url: 'https://discord.com/oauth2/authorize?...' })
        })
      )

      const result = await api.startDiscordOAuth()
      expect(result.url).toContain('discord.com')
    })
  })

  describe('getDiscordChannels', () => {
    it('fetches Discord channels', async () => {
      server.use(
        http.get(`${API_BASE}/v1/integrations/discord/channels`, () => {
          return HttpResponse.json({
            channels: [{ id: 'D001', name: 'alerts' }],
          })
        })
      )

      const result = await api.getDiscordChannels()
      expect(result.channels).toHaveLength(1)
    })
  })

  describe('updateDiscordChannel', () => {
    it('updates the Discord notification channel', async () => {
      server.use(
        http.put(
          `${API_BASE}/v1/integrations/discord/channel`,
          async ({ request }) => {
            const body = (await request.json()) as Record<string, unknown>
            expect(body.channelId).toBe('D001')
            expect(body.channelName).toBe('alerts')
            return new HttpResponse(null, { status: 204 })
          }
        )
      )

      await api.updateDiscordChannel('D001', 'alerts')
    })
  })

  describe('toggleDiscordIntegration', () => {
    it('toggles Discord integration on/off', async () => {
      server.use(
        http.put(`${API_BASE}/v1/integrations/discord/toggle`, () => {
          return new HttpResponse(null, { status: 204 })
        })
      )

      await api.toggleDiscordIntegration()
    })
  })

  describe('deleteDiscordIntegration', () => {
    it('deletes Discord integration', async () => {
      server.use(
        http.delete(`${API_BASE}/v1/integrations/discord`, () => {
          return new HttpResponse(null, { status: 204 })
        })
      )

      await api.deleteDiscordIntegration()
    })
  })

  describe('testDiscordIntegration', () => {
    it('sends a test Discord notification', async () => {
      server.use(
        http.post(`${API_BASE}/v1/integrations/discord/test`, () => {
          return HttpResponse.json({ success: true })
        })
      )

      const result = await api.testDiscordIntegration()
      expect(result.success).toBe(true)
    })
  })

  // ──── Incident Providers ────

  describe('getIncidentProviders', () => {
    it('fetches incident providers', async () => {
      const mockProviders = [
        { id: 1, name: 'PagerDuty', type: 'pagerduty', enabled: true },
      ]

      server.use(
        http.get(`${API_BASE}/api/incident-providers`, () => {
          return HttpResponse.json(mockProviders)
        })
      )

      const result = await api.getIncidentProviders()
      expect(result).toHaveLength(1)
      expect(result[0].name).toBe('PagerDuty')
    })
  })

  describe('createIncidentProvider', () => {
    it('creates a new incident provider', async () => {
      server.use(
        http.post(`${API_BASE}/api/incident-providers`, async ({ request }) => {
          const body = (await request.json()) as Record<string, unknown>
          expect(body.name).toBe('OpsGenie')
          expect(body.type).toBe('opsgenie')
          return HttpResponse.json({ id: 2 })
        })
      )

      const result = await api.createIncidentProvider({
        name: 'OpsGenie',
        type: 'opsgenie',
      } as never)
      expect(result.id).toBe(2)
    })
  })

  describe('updateIncidentProvider', () => {
    it('updates an incident provider', async () => {
      server.use(
        http.put(`${API_BASE}/api/incident-providers/1`, async ({ request }) => {
          const body = (await request.json()) as Record<string, unknown>
          expect(body.name).toBe('Updated PagerDuty')
          return new HttpResponse(null, { status: 204 })
        })
      )

      await api.updateIncidentProvider(1, { name: 'Updated PagerDuty' } as never)
    })
  })

  describe('deleteIncidentProvider', () => {
    it('deletes an incident provider', async () => {
      server.use(
        http.delete(`${API_BASE}/api/incident-providers/1`, () => {
          return new HttpResponse(null, { status: 204 })
        })
      )

      await api.deleteIncidentProvider(1)
    })
  })

  describe('testIncidentProvider', () => {
    it('tests an incident provider', async () => {
      server.use(
        http.post(`${API_BASE}/api/incident-providers/1/test`, () => {
          return HttpResponse.json({ success: true })
        })
      )

      const result = await api.testIncidentProvider(1)
      expect(result.success).toBe(true)
    })
  })

  describe('getIncidentProviderRules', () => {
    it('fetches routing rules for a provider', async () => {
      const mockRules = [
        { id: 1, condition: 'severity == critical', action: 'alert' },
      ]

      server.use(
        http.get(`${API_BASE}/api/incident-providers/1/rules`, () => {
          return HttpResponse.json(mockRules)
        })
      )

      const result = await api.getIncidentProviderRules(1)
      expect(result).toHaveLength(1)
      expect(result[0].condition).toBe('severity == critical')
    })
  })

  describe('updateIncidentProviderRules', () => {
    it('updates routing rules for a provider', async () => {
      server.use(
        http.put(
          `${API_BASE}/api/incident-providers/1/rules`,
          async ({ request }) => {
            const body = (await request.json()) as unknown[]
            expect(body).toHaveLength(1)
            return new HttpResponse(null, { status: 204 })
          }
        )
      )

      await api.updateIncidentProviderRules(1, [
        { condition: 'severity == high', action: 'notify' } as never,
      ])
    })
  })

  describe('getIncidentProviders with apiBase fallback', () => {
    it('uses fallback when base has no path after /v1', async () => {
      const { integrationsMethods } = await import('../integrations')
      const mockRequest = async (endpoint: string) => {
        if (endpoint.includes('https://api.moneat.io/api/incident-providers')) {
          return [{ id: 1, name: 'PagerDuty', type: 'pagerduty' }]
        }
        throw new Error(`Unexpected endpoint: ${endpoint}`)
      }
      const core = {
        API_BASE: '/v1',
        request: mockRequest,
        get: mockRequest,
        fetchWithAuth: async () => new Response(),
        logout: async () => {},
        checkAuth: async () => true,
        isAuthenticated: () => true,
      }
      const methods = integrationsMethods(core)
      const result = await methods.getIncidentProviders()
      expect(result).toHaveLength(1)
      expect(result[0].name).toBe('PagerDuty')
    })
  })

  describe('getIncidentProviderEvents', () => {
    it('fetches event log for a provider with default limit', async () => {
      server.use(
        http.get(`${API_BASE}/api/incident-providers/1/events`, ({ request }) => {
          const url = new URL(request.url)
          expect(url.searchParams.get('limit')).toBe('50')
          return HttpResponse.json([
            { id: 1, type: 'alert_sent', createdAt: '2024-01-01T00:00:00Z' },
          ])
        })
      )

      const result = await api.getIncidentProviderEvents(1)
      expect(result).toHaveLength(1)
    })

    it('passes custom limit', async () => {
      server.use(
        http.get(`${API_BASE}/api/incident-providers/2/events`, ({ request }) => {
          const url = new URL(request.url)
          expect(url.searchParams.get('limit')).toBe('10')
          return HttpResponse.json([])
        })
      )

      const result = await api.getIncidentProviderEvents(2, 10)
      expect(result).toHaveLength(0)
    })
  })
})
