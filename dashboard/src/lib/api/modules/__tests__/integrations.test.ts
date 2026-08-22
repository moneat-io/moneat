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
const SLACK_INTEGRATION_ID = '11111111-1111-4111-8111-111111111111'
const DISCORD_INTEGRATION_ID = '22222222-2222-4222-8222-222222222222'
const SCHEDULE_ID = '33333333-3333-4333-8333-333333333333'
const INCIDENT_PROVIDER_ID = '44444444-4444-4444-8444-444444444444'
const INCIDENT_PROVIDER_CREATED_ID = '55555555-5555-4555-8555-555555555555'
const INCIDENT_PROVIDER_RULE_ID = '66666666-6666-4666-8666-666666666666'
const INCIDENT_PROVIDER_EVENT_ID = '77777777-7777-4777-8777-777777777777'
const INCIDENT_PROVIDER_FALLBACK_ID = '88888888-8888-4888-8888-888888888888'

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
        {
          id: SLACK_INTEGRATION_ID,
          integrationType: 'slack',
          teamName: null,
          channelId: null,
          channelName: null,
          enabled: true,
          isConfigured: true,
        },
        {
          id: DISCORD_INTEGRATION_ID,
          integrationType: 'discord',
          teamName: null,
          channelId: null,
          channelName: null,
          enabled: false,
          isConfigured: false,
        },
      ]

      server.use(
        http.get(`${API_BASE}/v1/integrations`, () => {
          return HttpResponse.json(mockIntegrations)
        })
      )

      const result = await api.getIntegrations()
      expect(result).toHaveLength(2)
      expect(result[0].integrationType).toBe('slack')
    })
  })

  // ──── Slack Integration ────

  describe('startSlackOAuth', () => {
    it('initiates Slack OAuth flow', async () => {
      server.use(
        http.get(`${API_BASE}/v1/integrations/slack/oauth/start`, () => {
          return HttpResponse.json({ authUrl: 'https://slack.com/oauth/authorize?...' })
        })
      )

      const result = await api.startSlackOAuth()
      expect(result.authUrl).toContain('slack.com')
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
          `${API_BASE}/v1/on-call/schedules/${SCHEDULE_ID}/slack-usergroup`,
          async ({ request }) => {
            const body = (await request.json()) as Record<string, unknown>
            expect(body.usergroupId).toBe('S001')
            expect(body.usergroupHandle).toBe('oncall-team')
            expect(body.slackInstallationId).toBe('installation-1')
            return new HttpResponse(null, { status: 204 })
          }
        )
      )

      await api.setScheduleSlackUsergroup(SCHEDULE_ID, 'S001', 'oncall-team', 'installation-1')
    })
  })

  describe('removeScheduleSlackUsergroup', () => {
    it('removes Slack usergroup from a schedule', async () => {
      server.use(
        http.delete(`${API_BASE}/v1/on-call/schedules/${SCHEDULE_ID}/slack-usergroup`, () => {
          return new HttpResponse(null, { status: 204 })
        })
      )

      await api.removeScheduleSlackUsergroup(SCHEDULE_ID)
    })
  })

  // ──── Discord Integration ────

  describe('startDiscordOAuth', () => {
    it('initiates Discord OAuth flow', async () => {
      server.use(
        http.get(`${API_BASE}/v1/integrations/discord/oauth/start`, () => {
          return HttpResponse.json({ authUrl: 'https://discord.com/oauth2/authorize?...' })
        })
      )

      const result = await api.startDiscordOAuth()
      expect(result.authUrl).toContain('discord.com')
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
        { id: INCIDENT_PROVIDER_ID, name: 'PagerDuty', type: 'pagerduty', enabled: true },
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
          return HttpResponse.json({ id: INCIDENT_PROVIDER_CREATED_ID })
        })
      )

      const result = await api.createIncidentProvider({
        name: 'OpsGenie',
        type: 'opsgenie',
      } as never)
      expect(result.id).toBe(INCIDENT_PROVIDER_CREATED_ID)
    })
  })

  describe('updateIncidentProvider', () => {
    it('updates an incident provider', async () => {
      server.use(
        http.put(`${API_BASE}/api/incident-providers/${INCIDENT_PROVIDER_ID}`, async ({ request }) => {
          const body = (await request.json()) as Record<string, unknown>
          expect(body.name).toBe('Updated PagerDuty')
          return new HttpResponse(null, { status: 204 })
        })
      )

      await api.updateIncidentProvider(INCIDENT_PROVIDER_ID, { name: 'Updated PagerDuty' } as never)
    })
  })

  describe('deleteIncidentProvider', () => {
    it('deletes an incident provider', async () => {
      server.use(
        http.delete(`${API_BASE}/api/incident-providers/${INCIDENT_PROVIDER_ID}`, () => {
          return new HttpResponse(null, { status: 204 })
        })
      )

      await api.deleteIncidentProvider(INCIDENT_PROVIDER_ID)
    })
  })

  describe('testIncidentProvider', () => {
    it('tests an incident provider', async () => {
      server.use(
        http.post(`${API_BASE}/api/incident-providers/${INCIDENT_PROVIDER_ID}/test`, () => {
          return HttpResponse.json({ success: true })
        })
      )

      const result = await api.testIncidentProvider(INCIDENT_PROVIDER_ID)
      expect(result.success).toBe(true)
    })
  })

  describe('getIncidentProviderRules', () => {
    it('fetches routing rules for a provider', async () => {
      const mockRules = [
        { id: INCIDENT_PROVIDER_RULE_ID, alertSource: 'severity == critical', alertPriority: 'P0' },
      ]

      server.use(
        http.get(`${API_BASE}/api/incident-providers/${INCIDENT_PROVIDER_ID}/rules`, () => {
          return HttpResponse.json(mockRules)
        })
      )

      const result = await api.getIncidentProviderRules(INCIDENT_PROVIDER_ID)
      expect(result).toHaveLength(1)
      expect(result[0].alertSource).toBe('severity == critical')
    })
  })

  describe('updateIncidentProviderRules', () => {
    it('updates routing rules for a provider', async () => {
      server.use(
        http.put(
          `${API_BASE}/api/incident-providers/${INCIDENT_PROVIDER_ID}/rules`,
          async ({ request }) => {
            const body = (await request.json()) as unknown[]
            expect(body).toHaveLength(1)
            return new HttpResponse(null, { status: 204 })
          }
        )
      )

      await api.updateIncidentProviderRules(INCIDENT_PROVIDER_ID, [
        { condition: 'severity == high', action: 'notify' } as never,
      ])
    })
  })

  describe('getIncidentProviders with apiBase fallback', () => {
    it('uses fallback when base has no path after /v1', async () => {
      const { integrationsMethods } = await import('../integrations')
      const mockRequest = async <T,>(endpoint: string): Promise<T> => {
        if (endpoint.includes('https://api.moneat.io/api/incident-providers')) {
          return [{ id: INCIDENT_PROVIDER_FALLBACK_ID, name: 'PagerDuty', type: 'pagerduty' }] as T
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
        http.get(`${API_BASE}/api/incident-providers/${INCIDENT_PROVIDER_ID}/events`, ({ request }) => {
          const url = new URL(request.url)
          expect(url.searchParams.get('limit')).toBe('50')
          return HttpResponse.json([
            { id: INCIDENT_PROVIDER_EVENT_ID, type: 'alert_sent', createdAt: '2024-01-01T00:00:00Z' },
          ])
        })
      )

      const result = await api.getIncidentProviderEvents(INCIDENT_PROVIDER_ID)
      expect(result).toHaveLength(1)
    })

    it('passes custom limit', async () => {
      server.use(
        http.get(`${API_BASE}/api/incident-providers/${INCIDENT_PROVIDER_CREATED_ID}/events`, ({ request }) => {
          const url = new URL(request.url)
          expect(url.searchParams.get('limit')).toBe('10')
          return HttpResponse.json([])
        })
      )

      const result = await api.getIncidentProviderEvents(INCIDENT_PROVIDER_CREATED_ID, 10)
      expect(result).toHaveLength(0)
    })
  })
})
