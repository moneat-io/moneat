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

import {describe, expect, it} from 'vitest'

import type {
  SlackCapabilityDefinition,
  SlackInstallationHealth,
  SlackInstallationSummary,
  SlackScopeExplanation,
  SlackWorkspaceBindingSummary,
} from '@/lib/api/types'
import {
  SLACK_HEALTH_PRESENTATION,
  belongsToEnterpriseGrid,
  bindingDisplayName,
  botScopesForCapabilities,
  capabilityHealthFor,
  capabilityUsesUserScopes,
  explanationForScope,
  isCapabilityHealthy,
  isOrgWideSlackInstall,
  missingScopesByPrincipal,
  optionalCapabilityIds,
  requiredCapabilityIds,
  scopesForCapabilities,
  selectedCapabilityIds,
  shouldSurfaceBindings,
  slackExpiryLabel,
  slackHealthDescription,
  slackHealthPresentation,
  slackInstallHasWorkspace,
  sortCapabilities,
  userScopesForCapabilities,
} from '@/components/settings/slackScopes'

function capability(
  overrides: Partial<SlackCapabilityDefinition> & Pick<SlackCapabilityDefinition, 'id'>
): SlackCapabilityDefinition {
  return {
    label: overrides.id,
    description: `${overrides.id} description`,
    scopes: [],
    botScopes: [],
    userScopes: [],
    optional: false,
    ...overrides,
  }
}

const ALERT_DELIVERY = capability({
  id: 'alert_delivery',
  scopes: ['chat:write', 'channels:read'],
  botScopes: ['chat:write', 'channels:read'],
})
const USERGROUPS = capability({
  id: 'on_call_usergroups',
  // Overlaps alert_delivery on chat:write so the union has something to fold.
  scopes: ['usergroups:write', 'chat:write'],
  botScopes: ['usergroups:write', 'chat:write'],
})
const ADMIN = capability({
  id: 'privileged_channels',
  scopes: ['groups:write', 'admin.conversations:write'],
  botScopes: ['groups:write'],
  userScopes: ['admin.conversations:write'],
})
const ASSISTANT = capability({
  id: 'assistant',
  scopes: ['assistant:write'],
  botScopes: ['assistant:write'],
  optional: true,
})

const CATALOG = [ASSISTANT, ALERT_DELIVERY, USERGROUPS, ADMIN]

function installation(
  overrides: Partial<SlackInstallationSummary> = {}
): SlackInstallationSummary {
  return {
    id: 'inst-1',
    teamId: 'T1',
    teamName: 'Acme HQ',
    enterpriseId: null,
    enterpriseName: null,
    isEnterpriseInstall: false,
    appId: 'A1',
    botUserId: 'B1',
    grantedScopes: ['chat:write'],
    grantedUserScopes: [],
    enabledCapabilities: ['alert_delivery'],
    missingScopes: [],
    workspaceBindings: [],
    grants: [],
    capabilityHealth: [],
    defaultChannelId: 'C1',
    defaultChannelName: 'alerts',
    isDefault: true,
    enabled: true,
    health: 'HEALTHY',
    healthDetail: null,
    lastVerifiedAt: '2026-08-01T00:00:00Z',
    createdAt: '2026-07-01T00:00:00Z',
    updatedAt: '2026-08-01T00:00:00Z',
    ...overrides,
  }
}

function binding(
  overrides: Partial<SlackWorkspaceBindingSummary> = {}
): SlackWorkspaceBindingSummary {
  return {
    id: 'bind-1',
    teamId: 'T1',
    teamName: 'Acme HQ',
    enterpriseId: null,
    isPrimary: true,
    enabled: true,
    health: 'HEALTHY',
    healthDetail: null,
    lastVerifiedAt: '2026-08-01T00:00:00Z',
    ...overrides,
  }
}

describe('slack health presentation', () => {
  it('gives every backend health value its own status language', () => {
    const values = Object.keys(SLACK_HEALTH_PRESENTATION) as SlackInstallationHealth[]
    expect(values).toHaveLength(8)
    for (const value of values) {
      const presentation = slackHealthPresentation(value)
      expect(presentation.label).not.toBe('')
      expect(presentation.description).not.toBe('')
    }
  })

  it('marks only the states a reauthorization actually fixes', () => {
    expect(slackHealthPresentation('MISSING_SCOPES').needsReauthorization).toBe(true)
    expect(slackHealthPresentation('TOKEN_REVOKED').needsReauthorization).toBe(true)
    expect(slackHealthPresentation('BOT_REMOVED').needsReauthorization).toBe(true)
    expect(slackHealthPresentation('WORKSPACE_MISMATCH').needsReauthorization).toBe(true)
    expect(slackHealthPresentation('REAUTHORIZATION_REQUIRED').needsReauthorization).toBe(true)
    // A degraded check or a paused install is not fixed by reauthorizing.
    expect(slackHealthPresentation('DEGRADED').needsReauthorization).toBe(false)
    expect(slackHealthPresentation('DISABLED').needsReauthorization).toBe(false)
    expect(slackHealthPresentation('HEALTHY').needsReauthorization).toBe(false)
  })

  it('falls back to a neutral unknown status for a health value it does not know', () => {
    // A newer backend can return a health value this build has never seen.
    const presentation = slackHealthPresentation('SOMETHING_NEW' as SlackInstallationHealth)
    expect(presentation.label).toBe('Unknown')
    expect(presentation.tone).toBe('neutral')
    expect(presentation.needsReauthorization).toBe(false)
  })
})

describe('install shape', () => {
  it('separates an org-wide Grid install from a workspace that merely belongs to a Grid', () => {
    const orgWide = installation({isEnterpriseInstall: true, teamId: null, enterpriseId: 'E1'})
    const gridMember = installation({isEnterpriseInstall: false, enterpriseId: 'E1'})
    const plain = installation()

    expect(isOrgWideSlackInstall(orgWide)).toBe(true)
    expect(isOrgWideSlackInstall(gridMember)).toBe(false)
    expect(belongsToEnterpriseGrid(gridMember)).toBe(true)
    expect(belongsToEnterpriseGrid(orgWide)).toBe(false)
    expect(belongsToEnterpriseGrid(plain)).toBe(false)
  })

  it('only credits a workspace to an install that has a team', () => {
    expect(slackInstallHasWorkspace(installation())).toBe(true)
    expect(slackInstallHasWorkspace(installation({teamId: null}))).toBe(false)
  })
})

describe('slackHealthDescription', () => {
  it('prefers a backend-provided detail over the generic status copy', () => {
    const detail = 'Missing Slack scopes: usergroups:read'
    expect(slackHealthDescription(installation({health: 'MISSING_SCOPES', healthDetail: detail})))
      .toBe(detail)
  })

  it('does not promise delivery for a healthy organization-wide install', () => {
    const description = slackHealthDescription(
      installation({isEnterpriseInstall: true, teamId: null, health: 'HEALTHY'})
    )
    expect(description).toMatch(/Delivery still happens per workspace/)
    expect(description).not.toBe(slackHealthPresentation('HEALTHY').description)
  })

  it('uses the status copy for an ordinary workspace install', () => {
    expect(slackHealthDescription(installation())).toBe(
      slackHealthPresentation('HEALTHY').description
    )
    // An unhealthy org install keeps its own status copy rather than the org note.
    expect(
      slackHealthDescription(
        installation({isEnterpriseInstall: true, teamId: null, health: 'DEGRADED'})
      )
    ).toBe(slackHealthPresentation('DEGRADED').description)
  })
})

describe('capability catalog', () => {
  it('orders required capabilities ahead of optional ones without reordering the source', () => {
    const sorted = sortCapabilities(CATALOG)
    expect(sorted.map((c) => c.id)).toEqual([
      'alert_delivery',
      'on_call_usergroups',
      'privileged_channels',
      'assistant',
    ])
    // The catalog itself is left untouched.
    expect(CATALOG[0].id).toBe('assistant')
  })

  it('splits required and optional capability ids', () => {
    expect(requiredCapabilityIds(CATALOG)).toEqual([
      'alert_delivery',
      'on_call_usergroups',
      'privileged_channels',
    ])
    expect(optionalCapabilityIds(CATALOG)).toEqual(['assistant'])
  })

  it('reports empty id lists for an empty catalog', () => {
    expect(requiredCapabilityIds([])).toEqual([])
    expect(optionalCapabilityIds([])).toEqual([])
  })

  it('selects every required capability plus the toggled optional ones', () => {
    expect(selectedCapabilityIds(CATALOG, new Set())).toEqual([
      'alert_delivery',
      'on_call_usergroups',
      'privileged_channels',
    ])
    expect(selectedCapabilityIds(CATALOG, new Set(['assistant']))).toContain('assistant')
    // An optional id that is not in the catalog cannot add anything.
    expect(selectedCapabilityIds(CATALOG, new Set(['nope']))).not.toContain('nope')
  })
})

describe('scope resolution', () => {
  it('unions, de-duplicates, and sorts the scopes of the selected capabilities', () => {
    const scopes = scopesForCapabilities(CATALOG, new Set(['alert_delivery', 'on_call_usergroups']))
    expect(scopes).toEqual(['channels:read', 'chat:write', 'usergroups:write'])
  })

  it('requests nothing when no capability is selected, and ignores unknown ids', () => {
    expect(scopesForCapabilities(CATALOG, new Set())).toEqual([])
    expect(scopesForCapabilities(CATALOG, new Set(['not_a_capability']))).toEqual([])
  })

  it('keeps bot scopes and privileged-user scopes in separate grants', () => {
    const selected = new Set(['alert_delivery', 'privileged_channels'])
    expect(botScopesForCapabilities(CATALOG, selected)).toEqual([
      'channels:read',
      'chat:write',
      'groups:write',
    ])
    expect(userScopesForCapabilities(CATALOG, selected)).toEqual(['admin.conversations:write'])
  })

  it('leaves the user grant empty when no selected capability needs one', () => {
    expect(userScopesForCapabilities(CATALOG, new Set(['alert_delivery']))).toEqual([])
    expect(capabilityUsesUserScopes(ALERT_DELIVERY)).toBe(false)
    expect(capabilityUsesUserScopes(ADMIN)).toBe(true)
  })
})

describe('explanationForScope', () => {
  const explanations: SlackScopeExplanation[] = [
    {scope: 'chat:write', reason: 'Post alerts into a channel.', capabilities: ['alert_delivery']},
  ]

  it('resolves the reason a scope is requested', () => {
    expect(explanationForScope(explanations, 'chat:write')?.reason).toBe(
      'Post alerts into a channel.'
    )
  })

  it('returns nothing for a scope the catalog does not explain', () => {
    expect(explanationForScope(explanations, 'channels:read')).toBeUndefined()
    expect(explanationForScope([], 'chat:write')).toBeUndefined()
  })
})

describe('capability health', () => {
  const unhealthy = installation({
    capabilityHealth: [
      {
        capabilityId: 'alert_delivery',
        missingBotScopes: ['chat:write'],
        missingUserScopes: [],
        healthy: false,
      },
      {
        capabilityId: 'privileged_channels',
        // Overlaps the entry above so the aggregate has a duplicate to fold.
        missingBotScopes: ['chat:write', 'groups:write'],
        missingUserScopes: ['admin.conversations:write'],
        healthy: false,
      },
      {
        capabilityId: 'on_call_usergroups',
        missingBotScopes: [],
        missingUserScopes: [],
        healthy: true,
      },
    ],
  })

  it('finds the health entry for a capability', () => {
    expect(capabilityHealthFor(unhealthy, 'alert_delivery')?.missingBotScopes).toEqual([
      'chat:write',
    ])
    expect(capabilityHealthFor(unhealthy, 'assistant')).toBeUndefined()
  })

  it('treats a capability the backend did not report on as healthy', () => {
    expect(isCapabilityHealthy(unhealthy, 'assistant')).toBe(true)
    expect(isCapabilityHealthy(installation(), 'alert_delivery')).toBe(true)
  })

  it('reports a capability the backend flagged as missing scopes', () => {
    expect(isCapabilityHealthy(unhealthy, 'alert_delivery')).toBe(false)
    expect(isCapabilityHealthy(unhealthy, 'on_call_usergroups')).toBe(true)
  })

  it('aggregates missing scopes by principal, de-duplicated and sorted', () => {
    expect(missingScopesByPrincipal(unhealthy)).toEqual({
      botScopes: ['chat:write', 'groups:write'],
      userScopes: ['admin.conversations:write'],
    })
  })

  it('reports nothing missing when the backend sent no capability health', () => {
    expect(missingScopesByPrincipal(installation())).toEqual({botScopes: [], userScopes: []})
  })
})

describe('workspace bindings', () => {
  it('names a binding by its workspace, falling back to the team id', () => {
    expect(bindingDisplayName(binding())).toBe('Acme HQ')
    expect(bindingDisplayName(binding({teamName: null, teamId: 'T404'}))).toBe('T404')
  })

  it('always surfaces the workspaces list for an organization-wide install', () => {
    expect(shouldSurfaceBindings({isEnterpriseInstall: true, workspaceBindings: []})).toBe(true)
  })

  it('stays quiet when a workspace install has one healthy, enabled workspace', () => {
    expect(shouldSurfaceBindings({isEnterpriseInstall: false, workspaceBindings: [binding()]}))
      .toBe(false)
    expect(shouldSurfaceBindings({isEnterpriseInstall: false, workspaceBindings: []})).toBe(false)
  })

  it('surfaces the list when a workspace needs attention or delivery is paused', () => {
    expect(
      shouldSurfaceBindings({
        isEnterpriseInstall: false,
        workspaceBindings: [binding({health: 'TOKEN_REVOKED'})],
      })
    ).toBe(true)
    expect(
      shouldSurfaceBindings({
        isEnterpriseInstall: false,
        workspaceBindings: [binding({enabled: false})],
      })
    ).toBe(true)
  })

  it('surfaces the list once more than one workspace is attached', () => {
    expect(
      shouldSurfaceBindings({
        isEnterpriseInstall: false,
        workspaceBindings: [binding(), binding({id: 'bind-2', teamId: 'T2', isPrimary: false})],
      })
    ).toBe(true)
  })
})

describe('slackExpiryLabel', () => {
  const now = Date.parse('2026-08-22T12:00:00Z')

  it('has nothing to say about a grant with no expiry', () => {
    expect(slackExpiryLabel(null, now)).toBeNull()
  })

  it('ignores an unparseable timestamp rather than rendering NaN', () => {
    expect(slackExpiryLabel('not-a-date', now)).toBeNull()
  })

  it('counts down to a future expiry in the largest useful unit', () => {
    expect(slackExpiryLabel('2026-08-22T12:00:30Z', now)).toEqual({
      expired: false,
      text: 'Expires in under a minute',
    })
    expect(slackExpiryLabel('2026-08-22T12:45:00Z', now)?.text).toBe('Expires in 45m')
    expect(slackExpiryLabel('2026-08-22T20:00:00Z', now)?.text).toBe('Expires in 8h')
    expect(slackExpiryLabel('2026-08-25T12:00:00Z', now)?.text).toBe('Expires in 3d')
  })

  it('reports a past expiry as already expired', () => {
    expect(slackExpiryLabel('2026-08-20T12:00:00Z', now)).toEqual({
      expired: true,
      text: 'Expired 2d ago',
    })
    expect(slackExpiryLabel('2026-08-22T11:30:00Z', now)?.text).toBe('Expired 30m ago')
  })

  it('treats an expiry exactly at the current instant as expired', () => {
    expect(slackExpiryLabel('2026-08-22T12:00:00Z', now)).toEqual({
      expired: true,
      text: 'Expired under a minute ago',
    })
  })
})
