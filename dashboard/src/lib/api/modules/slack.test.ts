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

import {beforeEach, describe, expect, it, vi} from 'vitest'

import type {ApiClientCore} from '../client'
import type {SlackCapabilityDefinition} from '@/lib/api/types'
import {slackMethods} from './slack'
import {
  optionalCapabilityIds,
  requiredCapabilityIds,
  scopesForCapabilities,
  selectedCapabilityIds,
  slackHealthPresentation,
  sortCapabilities,
} from '@/components/settings/slackScopes'

function makeCore() {
  const request = vi.fn().mockResolvedValue({})
  const core = {
    API_BASE: '/v1',
    request,
    get: vi.fn(),
    fetchWithAuth: vi.fn(),
    logout: vi.fn(),
    checkAuth: vi.fn(),
    isAuthenticated: () => true,
  } as unknown as ApiClientCore
  return {core, request}
}

describe('slackMethods', () => {
  let request: ReturnType<typeof vi.fn>
  let slack: ReturnType<typeof slackMethods>

  beforeEach(() => {
    const made = makeCore()
    request = made.request
    slack = slackMethods(made.core)
  })

  it('reads the capability catalog', async () => {
    await slack.getSlackCapabilities()
    expect(request).toHaveBeenCalledWith('/v1/integrations/slack/capabilities')
  })

  it('lists installations', async () => {
    await slack.getSlackInstallations()
    expect(request).toHaveBeenCalledWith('/v1/integrations/slack/installations')
  })

  it('starts an install with comma-joined capability ids', async () => {
    await slack.startSlackInstallation(['alert_delivery', 'assistant'])
    expect(request).toHaveBeenCalledWith(
      '/v1/integrations/slack/oauth/start?capabilities=alert_delivery%2Cassistant'
    )
  })

  it('starts an install with no capabilities (backend defaults)', async () => {
    await slack.startSlackInstallation([])
    expect(request).toHaveBeenCalledWith('/v1/integrations/slack/oauth/start')
  })

  it('reauthorizes with the selected capabilities in the body', async () => {
    await slack.reauthorizeSlackInstallation('res-1', ['alert_delivery'])
    expect(request).toHaveBeenCalledWith('/v1/integrations/slack/installations/res-1/reauthorize', {
      method: 'POST',
      body: JSON.stringify({capabilities: ['alert_delivery']}),
    })
  })

  it('encodes the installation id in the path', async () => {
    await slack.getSlackInstallationChannels('res 1/x')
    expect(request).toHaveBeenCalledWith('/v1/integrations/slack/installations/res%201%2Fx/channels')
  })

  it('lists user groups for one installation', async () => {
    await slack.getSlackInstallationUsergroups('res 1/x')
    expect(request).toHaveBeenCalledWith(
      '/v1/integrations/slack/installations/res%201%2Fx/usergroups'
    )
  })

  it('sets a default channel', async () => {
    await slack.setSlackInstallationChannel('res-1', 'C123', 'alerts')
    expect(request).toHaveBeenCalledWith('/v1/integrations/slack/installations/res-1/channel', {
      method: 'PUT',
      body: JSON.stringify({channelId: 'C123', channelName: 'alerts'}),
    })
  })

  it('marks an installation as the default workspace', async () => {
    await slack.setSlackInstallationDefault('res-1')
    expect(request).toHaveBeenCalledWith('/v1/integrations/slack/installations/res-1/default', {
      method: 'PUT',
    })
  })

  it('toggles enabled state', async () => {
    await slack.setSlackInstallationEnabled('res-1', false)
    expect(request).toHaveBeenCalledWith('/v1/integrations/slack/installations/res-1/enabled', {
      method: 'PUT',
      body: JSON.stringify({enabled: false}),
    })
  })

  it('runs a health check', async () => {
    await slack.checkSlackInstallationHealth('res-1')
    expect(request).toHaveBeenCalledWith('/v1/integrations/slack/installations/res-1/health', {
      method: 'POST',
    })
  })

  it('sends a test message', async () => {
    await slack.testSlackInstallation('res-1')
    expect(request).toHaveBeenCalledWith('/v1/integrations/slack/installations/res-1/test', {
      method: 'POST',
    })
  })

  it('deletes an installation', async () => {
    await slack.deleteSlackInstallation('res-1')
    expect(request).toHaveBeenCalledWith('/v1/integrations/slack/installations/res-1', {
      method: 'DELETE',
    })
  })
})

const CAPABILITIES: SlackCapabilityDefinition[] = [
  {
    id: 'alert_delivery',
    label: 'Alert delivery',
    description: 'Send alerts.',
    scopes: ['channels:read', 'chat:write'],
    botScopes: ['channels:read', 'chat:write'],
    userScopes: [],
    optional: false,
  },
  {
    id: 'assistant',
    label: 'Slack Assistant',
    description: 'Optional AI assistant.',
    scopes: ['assistant:write', 'chat:write'],
    botScopes: ['assistant:write', 'chat:write'],
    userScopes: [],
    optional: true,
  },
  {
    id: 'on_call_usergroups',
    label: 'On-call user groups',
    description: 'Sync groups.',
    scopes: ['usergroups:read', 'usergroups:write'],
    botScopes: ['usergroups:read', 'usergroups:write'],
    userScopes: [],
    optional: false,
  },
]

describe('slackScopes helpers', () => {
  it('sorts required capabilities before optional ones', () => {
    const sorted = sortCapabilities(CAPABILITIES).map((c) => c.id)
    expect(sorted).toEqual(['alert_delivery', 'on_call_usergroups', 'assistant'])
  })

  it('separates required and optional capability ids', () => {
    expect(requiredCapabilityIds(CAPABILITIES)).toEqual(['alert_delivery', 'on_call_usergroups'])
    expect(optionalCapabilityIds(CAPABILITIES)).toEqual(['assistant'])
  })

  it('selects every required capability plus chosen optional ones', () => {
    expect(selectedCapabilityIds(CAPABILITIES, new Set())).toEqual([
      'alert_delivery',
      'on_call_usergroups',
    ])
    expect(selectedCapabilityIds(CAPABILITIES, new Set(['assistant']))).toEqual([
      'alert_delivery',
      'assistant',
      'on_call_usergroups',
    ])
  })

  it('unions and de-duplicates scopes for the selected capabilities', () => {
    expect(
      scopesForCapabilities(CAPABILITIES, new Set(['alert_delivery', 'assistant']))
    ).toEqual(['assistant:write', 'channels:read', 'chat:write'])
  })

  it('maps every health status to reauthorization guidance', () => {
    expect(slackHealthPresentation('HEALTHY').needsReauthorization).toBe(false)
    expect(slackHealthPresentation('DISABLED').needsReauthorization).toBe(false)
    expect(slackHealthPresentation('MISSING_SCOPES').needsReauthorization).toBe(true)
    expect(slackHealthPresentation('TOKEN_REVOKED').needsReauthorization).toBe(true)
    expect(slackHealthPresentation('HEALTHY').badge).toBe('success')
  })
})
