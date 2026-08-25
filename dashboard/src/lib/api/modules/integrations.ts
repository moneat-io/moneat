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

import type { ApiClientCore } from '../client'
import type {
  OrganizationIntegration,
  SlackOAuthStartResponse,
  SlackChannelList,
  SlackUsergroup,
  TestIntegrationResponse,
  IncidentProviderConfig,
  CreateIncidentProviderRequest,
  UpdateIncidentProviderRequest,
  IncidentRoutingRule,
  UpsertRoutingRuleRequest,
  IncidentEventLogEntry,
} from '../types'

export function integrationsMethods(core: ApiClientCore) {
  const base = core.API_BASE
  const apiBase = base.replace('/v1', '') || 'https://api.moneat.io'

  return {
    getIntegrations: () =>
      core.request<OrganizationIntegration[]>(`${base}/integrations`),

    startSlackOAuth: () =>
      core.request<SlackOAuthStartResponse>(`${base}/integrations/slack/oauth/start`),

    getSlackChannels: () =>
      core.request<SlackChannelList>(`${base}/integrations/slack/channels`),

    updateSlackChannel: (channelId: string, channelName: string) =>
      core.request<void>(`${base}/integrations/slack/channel`, {
        method: 'PUT',
        body: JSON.stringify({ channelId, channelName }),
      }),

    toggleSlackIntegration: () =>
      core.request<void>(`${base}/integrations/slack/toggle`, {
        method: 'PUT',
      }),

    deleteSlackIntegration: () =>
      core.request<void>(`${base}/integrations/slack`, {
        method: 'DELETE',
      }),

    testSlackIntegration: () =>
      core.request<TestIntegrationResponse>(`${base}/integrations/slack/test`, {
        method: 'POST',
      }),

    getSlackUsergroups: () =>
      core.request<SlackUsergroup[]>(`${base}/integrations/slack/usergroups`),

    setScheduleSlackUsergroup: (
      scheduleId: string,
      usergroupId: string,
      usergroupHandle: string,
      slackInstallationId?: string
    ) =>
      core.request<void>(`${base}/on-call/schedules/${encodeURIComponent(scheduleId)}/slack-usergroup`, {
        method: 'PUT',
        body: JSON.stringify({ usergroupId, usergroupHandle, slackInstallationId }),
      }),

    removeScheduleSlackUsergroup: (scheduleId: string) =>
      core.request<void>(
        `${base}/on-call/schedules/${encodeURIComponent(scheduleId)}/slack-usergroup`,
        {
          method: 'DELETE',
        }
      ),

    startDiscordOAuth: () =>
      core.request<SlackOAuthStartResponse>(`${base}/integrations/discord/oauth/start`),

    getDiscordChannels: () =>
      core.request<SlackChannelList>(`${base}/integrations/discord/channels`),

    updateDiscordChannel: (channelId: string, channelName: string) =>
      core.request<void>(`${base}/integrations/discord/channel`, {
        method: 'PUT',
        body: JSON.stringify({ channelId, channelName }),
      }),

    toggleDiscordIntegration: () =>
      core.request<void>(`${base}/integrations/discord/toggle`, {
        method: 'PUT',
      }),

    deleteDiscordIntegration: () =>
      core.request<void>(`${base}/integrations/discord`, {
        method: 'DELETE',
      }),

    testDiscordIntegration: () =>
      core.request<TestIntegrationResponse>(`${base}/integrations/discord/test`, {
        method: 'POST',
      }),

    getIncidentProviders: () =>
      core.request<IncidentProviderConfig[]>(`${apiBase}/api/incident-providers`),

    createIncidentProvider: (data: CreateIncidentProviderRequest) =>
      core.request<{ id: string }>(`${apiBase}/api/incident-providers`, {
        method: 'POST',
        body: JSON.stringify(data),
      }),

    updateIncidentProvider: (
      id: string,
      data: UpdateIncidentProviderRequest
    ) =>
      core.request<void>(`${apiBase}/api/incident-providers/${encodeURIComponent(id)}`, {
        method: 'PUT',
        body: JSON.stringify(data),
      }),

    deleteIncidentProvider: (id: string) =>
      core.request<void>(`${apiBase}/api/incident-providers/${encodeURIComponent(id)}`, {
        method: 'DELETE',
      }),

    testIncidentProvider: (id: string) =>
      core.request<{ success: boolean; error?: string }>(
        `${apiBase}/api/incident-providers/${encodeURIComponent(id)}/test`,
        {
          method: 'POST',
        }
      ),

    getIncidentProviderRules: (id: string) =>
      core.request<IncidentRoutingRule[]>(
        `${apiBase}/api/incident-providers/${encodeURIComponent(id)}/rules`
      ),

    updateIncidentProviderRules: (
      id: string,
      rules: UpsertRoutingRuleRequest[]
    ) =>
      core.request<void>(`${apiBase}/api/incident-providers/${encodeURIComponent(id)}/rules`, {
        method: 'PUT',
        body: JSON.stringify(rules),
      }),

    getIncidentProviderEvents: (id: string, limit = 50) =>
      core.request<IncidentEventLogEntry[]>(
        `${apiBase}/api/incident-providers/${encodeURIComponent(id)}/events?limit=${limit}`
      ),
  }
}
