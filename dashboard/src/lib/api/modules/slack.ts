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
  SlackCapabilitiesResponse,
  SlackChannelList,
  SlackInstallationSummary,
  SlackOAuthStartResponse,
  SlackUsergroup,
  TestIntegrationResponse,
} from '../types'

// Multi-workspace Slack installation management. These sit alongside
// the legacy single-workspace Slack methods in ./integrations; names are kept
// distinct (…Installation…) so both can coexist and be wired into the central
// api object without collisions.
export function slackMethods(core: ApiClientCore) {
  const base = core.API_BASE
  const installations = `${base}/integrations/slack/installations`
  const installation = (id: string) => `${installations}/${encodeURIComponent(id)}`

  return {
    // Least-privilege catalog: capabilities (with their scopes) and the reason
    // each individual scope is requested. Org-independent, so it stays cacheable.
    getSlackCapabilities: () =>
      core.request<SlackCapabilitiesResponse>(`${base}/integrations/slack/capabilities`),

    getSlackInstallations: () =>
      core.request<SlackInstallationSummary[]>(installations),

    // Begins an OAuth install for a new workspace, requesting only the scopes of
    // the selected capabilities. Returns the Slack authorize URL to redirect to.
    startSlackInstallation: (capabilityIds: string[]) => {
      const query = capabilityIds.length
        ? `?capabilities=${encodeURIComponent(capabilityIds.join(','))}`
        : ''
      return core.request<SlackOAuthStartResponse>(
        `${base}/integrations/slack/oauth/start${query}`
      )
    },

    // Re-runs OAuth for an existing workspace to (re)grant scopes. Passing an
    // expanded capability set is how new scopes get added — Slack grants are
    // additive, so this is required whenever capabilities grow.
    reauthorizeSlackInstallation: (installationId: string, capabilityIds: string[]) =>
      core.request<SlackOAuthStartResponse>(`${installation(installationId)}/reauthorize`, {
        method: 'POST',
        body: JSON.stringify({ capabilities: capabilityIds }),
      }),

    getSlackInstallationChannels: (installationId: string) =>
      core.request<SlackChannelList>(`${installation(installationId)}/channels`),

    getSlackInstallationUsergroups: (installationId: string) =>
      core.request<SlackUsergroup[]>(`${installation(installationId)}/usergroups`),

    setSlackInstallationChannel: (
      installationId: string,
      channelId: string,
      channelName: string
    ) =>
      core.request<SlackInstallationSummary>(`${installation(installationId)}/channel`, {
        method: 'PUT',
        body: JSON.stringify({ channelId, channelName }),
      }),

    setSlackInstallationDefault: (installationId: string) =>
      core.request<SlackInstallationSummary>(`${installation(installationId)}/default`, {
        method: 'PUT',
      }),

    setSlackInstallationEnabled: (installationId: string, enabled: boolean) =>
      core.request<SlackInstallationSummary>(`${installation(installationId)}/enabled`, {
        method: 'PUT',
        body: JSON.stringify({ enabled }),
      }),

    // Live probe against Slack (auth.test) that refreshes the stored health.
    checkSlackInstallationHealth: (installationId: string) =>
      core.request<SlackInstallationSummary>(`${installation(installationId)}/health`, {
        method: 'POST',
      }),

    testSlackInstallation: (installationId: string) =>
      core.request<TestIntegrationResponse>(`${installation(installationId)}/test`, {
        method: 'POST',
      }),

    deleteSlackInstallation: (installationId: string) =>
      core.request<{ message: string }>(installation(installationId), {
        method: 'DELETE',
      }),
  }
}
