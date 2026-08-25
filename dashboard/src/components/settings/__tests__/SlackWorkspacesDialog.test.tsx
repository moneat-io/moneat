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

import {QueryClient, QueryClientProvider} from '@tanstack/react-query'
import {fireEvent, render, screen, waitFor, within} from '@testing-library/react'
import type {ReactElement} from 'react'
import {afterEach, beforeEach, describe, expect, it, vi} from 'vitest'

import type {
  SlackCapabilitiesResponse,
  SlackGrantSummary,
  SlackInstallationSummary,
  SlackWorkspaceBindingSummary,
} from '@/lib/api/types'
import {SlackWorkspacesDialog} from '@/components/settings/SlackWorkspacesDialog'
import {
  SLACK_PRIVILEGED_ACCESS_NOTE,
  SLACK_USER_SCOPE_PICKER_NOTE,
} from '@/components/settings/slackScopes'

const {apiMock, toastMock} = vi.hoisted(() => ({
  apiMock: {
    isAuthenticated: vi.fn(() => true),
    getSlackCapabilities: vi.fn(),
    getSlackInstallations: vi.fn(),
    startSlackInstallation: vi.fn(),
    reauthorizeSlackInstallation: vi.fn(),
    getSlackInstallationChannels: vi.fn(),
    setSlackInstallationChannel: vi.fn(),
    setSlackInstallationDefault: vi.fn(),
    setSlackInstallationEnabled: vi.fn(),
    checkSlackInstallationHealth: vi.fn(),
    testSlackInstallation: vi.fn(),
    deleteSlackInstallation: vi.fn(),
  },
  toastMock: vi.fn(),
}))

vi.mock('@/lib/api', () => ({api: apiMock}))
vi.mock('@/hooks/useToast', () => ({useToast: () => ({toast: toastMock})}))
vi.mock('@/lib/analytics', () => ({trackEvent: vi.fn()}))

const CAPABILITIES: SlackCapabilitiesResponse = {
  capabilities: [
    {
      id: 'alert_delivery',
      label: 'Alert delivery',
      description: 'Send alert and incident updates to Slack.',
      scopes: ['channels:read', 'chat:write'],
      botScopes: ['channels:read', 'chat:write'],
      userScopes: [],
      optional: false,
    },
    {
      id: 'on_call_usergroups',
      label: 'On-call user groups',
      description: 'Mirror on-call schedules to Slack user groups.',
      scopes: ['usergroups:read', 'usergroups:write'],
      botScopes: ['usergroups:read', 'usergroups:write'],
      userScopes: [],
      optional: false,
    },
    {
      id: 'assistant',
      label: 'Slack Assistant',
      description: 'Offer the optional AI assistant in Slack DMs.',
      scopes: ['assistant:write', 'im:write'],
      botScopes: ['assistant:write', 'im:write'],
      userScopes: [],
      optional: true,
    },
  ],
  scopes: [],
}

function installation(overrides: Partial<SlackInstallationSummary> = {}): SlackInstallationSummary {
  return {
    id: 'inst-default',
    teamId: 'T1',
    teamName: 'Acme HQ',
    enterpriseId: null,
    enterpriseName: null,
    isEnterpriseInstall: false,
    appId: 'A1',
    botUserId: 'B1',
    grantedScopes: ['channels:read', 'chat:write'],
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

// An Enterprise Grid organization-wide install: enterprise context, no team.
const GRID = installation({
  id: 'inst-grid',
  teamId: null,
  teamName: null,
  enterpriseId: 'E1',
  enterpriseName: 'Globex Grid',
  isEnterpriseInstall: true,
  botUserId: 'B2',
  grantedScopes: ['chat:write'],
  enabledCapabilities: ['alert_delivery', 'assistant'],
  missingScopes: ['usergroups:read'],
  defaultChannelId: null,
  defaultChannelName: null,
  isDefault: false,
  enabled: false,
  health: 'MISSING_SCOPES',
  healthDetail: 'Missing Slack scopes: usergroups:read',
})

// A healthy org-wide install that has authorized cleanly but still has no
// workspace to deliver to.
const GRID_HEALTHY = installation({
  id: 'inst-grid-ok',
  teamId: null,
  teamName: null,
  enterpriseId: 'E2',
  enterpriseName: 'Umbrella Grid',
  isEnterpriseInstall: true,
  botUserId: 'B3',
  grantedScopes: ['chat:write', 'channels:read', 'usergroups:read', 'usergroups:write'],
  enabledCapabilities: ['alert_delivery', 'on_call_usergroups'],
  missingScopes: [],
  defaultChannelId: null,
  defaultChannelName: null,
  isDefault: false,
  enabled: true,
  health: 'HEALTHY',
  healthDetail: null,
})

// A workspace install that belongs to a Grid: it has a team, so it is not org-wide.
const GRID_MEMBER = installation({
  id: 'inst-grid-member',
  teamId: 'T9',
  teamName: 'Initech Eng',
  enterpriseId: 'E3',
  enterpriseName: 'Initech Grid',
  isEnterpriseInstall: false,
  isDefault: false,
})

// An org-wide install the backend unexpectedly flagged as default. It still has
// no workspace, so the UI must not present it as a usable default delivery target.
const GRID_DEFAULT = installation({
  id: 'inst-grid-default',
  teamId: null,
  teamName: null,
  enterpriseId: 'E4',
  enterpriseName: 'Wayne Grid',
  isEnterpriseInstall: true,
  botUserId: 'B4',
  grantedScopes: ['chat:write', 'channels:read'],
  enabledCapabilities: ['alert_delivery'],
  missingScopes: [],
  defaultChannelId: null,
  defaultChannelName: null,
  isDefault: true,
  enabled: true,
  health: 'HEALTHY',
  healthDetail: null,
})

// A catalog whose second capability can only be granted by a Slack admin or
// owner, so the picker has to show the two OAuth principals apart.
const PRIVILEGED_CAPABILITIES: SlackCapabilitiesResponse = {
  capabilities: [
    CAPABILITIES.capabilities[0],
    {
      id: 'restricted_channels',
      label: 'Restricted channel access',
      description: 'Post into channels only a workspace admin can open.',
      scopes: ['admin.conversations:write'],
      botScopes: [],
      userScopes: ['admin.conversations:write'],
      optional: false,
    },
  ],
  scopes: [],
}

function binding(
  overrides: Partial<SlackWorkspaceBindingSummary> = {}
): SlackWorkspaceBindingSummary {
  return {
    id: 'bind-1',
    teamId: 'T100',
    teamName: 'Sales Workspace',
    enterpriseId: 'E1',
    isPrimary: true,
    enabled: true,
    health: 'HEALTHY',
    healthDetail: null,
    lastVerifiedAt: '2026-08-01T00:00:00Z',
    ...overrides,
  }
}

function grant(overrides: Partial<SlackGrantSummary> = {}): SlackGrantSummary {
  return {
    id: 'grant-1',
    grantType: 'BOT',
    slackUserId: null,
    tokenType: 'bot',
    grantedScopes: ['chat:write', 'channels:read'],
    expiresAt: null,
    rotatedAt: null,
    revokedAt: null,
    health: 'HEALTHY',
    healthDetail: null,
    lastVerifiedAt: '2026-08-01T00:00:00Z',
    ...overrides,
  }
}

let originalLocation: Location

function renderDialog(ui: ReactElement) {
  const queryClient = new QueryClient({defaultOptions: {queries: {retry: false}}})
  return render(<QueryClientProvider client={queryClient}>{ui}</QueryClientProvider>)
}

function cardFor(name: string): HTMLElement {
  const node = screen.getByText(name).closest('li')
  if (!node) throw new Error(`No workspace card for ${name}`)
  return node as HTMLElement
}

beforeEach(() => {
  vi.clearAllMocks()
  apiMock.isAuthenticated.mockReturnValue(true)
  apiMock.getSlackCapabilities.mockResolvedValue(CAPABILITIES)
  apiMock.getSlackInstallations.mockResolvedValue([installation(), GRID])
  apiMock.setSlackInstallationEnabled.mockResolvedValue(installation({enabled: false}))
  apiMock.setSlackInstallationDefault.mockResolvedValue(installation({id: 'inst-grid'}))
  apiMock.checkSlackInstallationHealth.mockResolvedValue(installation())
  apiMock.testSlackInstallation.mockResolvedValue({success: true, message: 'Sent'})
  apiMock.deleteSlackInstallation.mockResolvedValue({message: 'deleted'})
  apiMock.startSlackInstallation.mockResolvedValue({authUrl: 'https://slack.test/oauth'})
  apiMock.reauthorizeSlackInstallation.mockResolvedValue({authUrl: 'https://slack.test/reauth'})
  apiMock.getSlackInstallationChannels.mockResolvedValue({
    channels: [{id: 'C1', name: 'alerts'}],
  })

  originalLocation = window.location
  Object.defineProperty(window, 'location', {
    configurable: true,
    writable: true,
    value: {href: ''},
  })
  if (!HTMLElement.prototype.hasPointerCapture) {
    HTMLElement.prototype.hasPointerCapture = () => false
  }
  if (!HTMLElement.prototype.releasePointerCapture) {
    HTMLElement.prototype.releasePointerCapture = () => {}
  }
  if (!HTMLElement.prototype.scrollIntoView) {
    HTMLElement.prototype.scrollIntoView = () => {}
  }
})

afterEach(() => {
  Object.defineProperty(window, 'location', {configurable: true, value: originalLocation})
})

describe('SlackWorkspacesDialog', () => {
  it('shows a loading state while workspaces load', () => {
    apiMock.getSlackInstallations.mockReturnValue(new Promise(() => {}))
    renderDialog(<SlackWorkspacesDialog onClose={vi.fn()} />)
    expect(screen.getByText('Loading Slack workspaces…')).toBeInTheDocument()
  })

  it('renders an empty state with an add action', async () => {
    apiMock.getSlackInstallations.mockResolvedValue([])
    renderDialog(<SlackWorkspacesDialog onClose={vi.fn()} />)
    expect(await screen.findByText('No Slack workspaces yet')).toBeInTheDocument()
    expect(screen.getByRole('button', {name: 'Add Slack workspace'})).toBeInTheDocument()
  })

  it('renders health, default, and organization context per install', async () => {
    renderDialog(<SlackWorkspacesDialog onClose={vi.fn()} />)
    expect(await screen.findByText('Acme HQ')).toBeInTheDocument()
    expect(screen.getByText('Globex Grid')).toBeInTheDocument()

    // The Grid org install reads as an organization, not a workspace.
    const grid = cardFor('Globex Grid')
    expect(within(grid).getByText('Grid organization')).toBeInTheDocument()
    expect(within(grid).getByText('Organization-wide install')).toBeInTheDocument()
    expect(within(grid).getByText(/No workspaces are attached yet/i)).toBeInTheDocument()
    // An install missing scopes surfaces a reauthorization prompt naming the scope.
    expect(within(grid).getByText('Reauthorization needed')).toBeInTheDocument()
    expect(within(grid).getByText('Slack has not granted: usergroups:read.')).toBeInTheDocument()
    expect(within(grid).getAllByText('Missing scopes').length).toBeGreaterThan(0)

    const acme = cardFor('Acme HQ')
    expect(within(acme).getByText('Default')).toBeInTheDocument()
    expect(within(acme).getByText('Workspace install')).toBeInTheDocument()
  })

  it('marks a workspace that belongs to an Enterprise Grid without treating it as org-wide', async () => {
    apiMock.getSlackInstallations.mockResolvedValue([GRID_MEMBER])
    renderDialog(<SlackWorkspacesDialog onClose={vi.fn()} />)
    await screen.findByText('Initech Eng')
    const card = cardFor('Initech Eng')
    expect(within(card).getByText('On Enterprise Grid')).toBeInTheDocument()
    expect(within(card).getByText('Workspace on Initech Grid')).toBeInTheDocument()
    // It has a workspace, so workspace-scoped actions stay available.
    expect(within(card).queryByText('Organization-wide install')).not.toBeInTheDocument()
    expect(within(card).getByRole('button', {name: 'Send test message'})).toBeEnabled()
  })

  it('toggles delivery for a workspace', async () => {
    renderDialog(<SlackWorkspacesDialog onClose={vi.fn()} />)
    const toggle = await screen.findByRole('switch', {name: 'Enable Slack delivery to Acme HQ'})
    fireEvent.click(toggle)
    await waitFor(() =>
      expect(apiMock.setSlackInstallationEnabled).toHaveBeenCalledWith('inst-default', false)
    )
  })

  it('lets a non-default workspace install be set as the default', async () => {
    apiMock.getSlackInstallations.mockResolvedValue([
      installation(),
      installation({id: 'inst-second', teamId: 'T2', teamName: 'Second Team', isDefault: false}),
    ])
    renderDialog(<SlackWorkspacesDialog onClose={vi.fn()} />)
    await screen.findByText('Second Team')
    fireEvent.click(within(cardFor('Second Team')).getByRole('button', {name: 'Set as default'}))
    await waitFor(() =>
      expect(apiMock.setSlackInstallationDefault).toHaveBeenCalledWith('inst-second')
    )
  })

  it('does not offer Set as default for an organization-wide install', async () => {
    // GRID_HEALTHY is a non-default org install; a workspace install here would
    // show the action, so its absence proves the gate, not just an empty list.
    apiMock.getSlackInstallations.mockResolvedValue([GRID_HEALTHY])
    renderDialog(<SlackWorkspacesDialog onClose={vi.fn()} />)
    await screen.findByText('Umbrella Grid')
    expect(screen.queryByRole('button', {name: 'Set as default'})).not.toBeInTheDocument()
  })

  it('labels an unexpected org-wide default as needing workspace setup, not usable delivery', async () => {
    apiMock.getSlackInstallations.mockResolvedValue([GRID_DEFAULT])
    renderDialog(<SlackWorkspacesDialog onClose={vi.fn()} />)
    await screen.findByText('Wayne Grid')
    const card = cardFor('Wayne Grid')

    // It must not read as a usable default delivery target...
    expect(within(card).queryByText('Default')).not.toBeInTheDocument()
    // ...yet the backend flag stays visible, qualified as needing a workspace.
    expect(within(card).getByText(/Default.*needs workspace/i)).toBeInTheDocument()
    // And it still cannot deliver.
    expect(within(card).getByRole('button', {name: 'Send test message'})).toBeDisabled()
  })

  it('sends a test message from a healthy workspace', async () => {
    renderDialog(<SlackWorkspacesDialog onClose={vi.fn()} />)
    await screen.findByText('Acme HQ')
    fireEvent.click(within(cardFor('Acme HQ')).getByRole('button', {name: 'Send test message'}))
    await waitFor(() =>
      expect(apiMock.testSlackInstallation).toHaveBeenCalledWith('inst-default')
    )
    await waitFor(() =>
      expect(toastMock).toHaveBeenCalledWith(expect.objectContaining({title: 'Test message sent'}))
    )
  })

  it('gates workspace-scoped actions on a healthy organization-wide install', async () => {
    apiMock.getSlackInstallations.mockResolvedValue([GRID_HEALTHY])
    renderDialog(<SlackWorkspacesDialog onClose={vi.fn()} />)
    await screen.findByText('Umbrella Grid')
    const card = cardFor('Umbrella Grid')

    // Healthy, yet it has no workspace: the test and channel picker stay disabled.
    expect(within(card).getByRole('button', {name: 'Send test message'})).toBeDisabled()
    expect(within(card).queryByRole('button', {name: 'Change'})).not.toBeInTheDocument()
    expect(within(card).getByText('Per workspace')).toBeInTheDocument()
    // The inline health line does not claim delivery for an org install.
    expect(within(card).queryByText(/Alerts can be delivered/i)).not.toBeInTheDocument()
    // Org-level actions remain available.
    expect(within(card).getByRole('button', {name: 'Run health check'})).toBeEnabled()
  })

  it('disables the test action when a workspace has no default channel', async () => {
    apiMock.getSlackInstallations.mockResolvedValue([
      installation({defaultChannelId: null, defaultChannelName: null}),
    ])
    renderDialog(<SlackWorkspacesDialog onClose={vi.fn()} />)
    await screen.findByText('Acme HQ')
    expect(
      within(cardFor('Acme HQ')).getByRole('button', {name: 'Send test message'})
    ).toBeDisabled()
    // A workspace install still lets you open the channel picker to fix that.
    expect(within(cardFor('Acme HQ')).getByRole('button', {name: 'Change'})).toBeInTheDocument()
  })

  it('runs a health check', async () => {
    renderDialog(<SlackWorkspacesDialog onClose={vi.fn()} />)
    await screen.findByText('Acme HQ')
    fireEvent.click(within(cardFor('Acme HQ')).getByRole('button', {name: 'Run health check'}))
    await waitFor(() =>
      expect(apiMock.checkSlackInstallationHealth).toHaveBeenCalledWith('inst-default')
    )
  })

  it('removes a workspace after confirmation', async () => {
    renderDialog(<SlackWorkspacesDialog onClose={vi.fn()} />)
    fireEvent.click(await screen.findByRole('button', {name: 'Remove Acme HQ'}))
    fireEvent.click(await screen.findByRole('button', {name: 'Remove install'}))
    await waitFor(() =>
      expect(apiMock.deleteSlackInstallation).toHaveBeenCalledWith('inst-default')
    )
  })

  it('adds a workspace with an optional capability and redirects to Slack', async () => {
    renderDialog(<SlackWorkspacesDialog onClose={vi.fn()} />)
    fireEvent.click(await screen.findByRole('button', {name: 'Add workspace'}))

    // Required capabilities are checked and locked; the Assistant is optional.
    const assistant = await screen.findByRole('checkbox', {name: /Slack Assistant/})
    expect(screen.getByRole('checkbox', {name: /Alert delivery/})).toBeDisabled()
    fireEvent.click(assistant)

    fireEvent.click(screen.getByRole('button', {name: 'Authorize with Slack'}))
    await waitFor(() =>
      expect(apiMock.startSlackInstallation).toHaveBeenCalledWith(
        expect.arrayContaining(['alert_delivery', 'on_call_usergroups', 'assistant'])
      )
    )
    await waitFor(() => expect(window.location.href).toBe('https://slack.test/oauth'))
  })

  it('reauthorizes an existing workspace seeded from its enabled capabilities', async () => {
    renderDialog(<SlackWorkspacesDialog onClose={vi.fn()} />)
    await screen.findByText('Globex Grid')

    fireEvent.click(within(cardFor('Globex Grid')).getByRole('button', {name: 'Reauthorize'}))
    expect(await screen.findByText('Reauthorize Globex Grid')).toBeInTheDocument()
    // The Assistant was already enabled on this workspace, so it starts checked.
    expect(screen.getByRole('checkbox', {name: /Slack Assistant/})).toBeChecked()

    fireEvent.click(screen.getByRole('button', {name: 'Continue to Slack'}))
    await waitFor(() =>
      expect(apiMock.reauthorizeSlackInstallation).toHaveBeenCalledWith(
        'inst-grid',
        expect.arrayContaining(['alert_delivery', 'on_call_usergroups', 'assistant'])
      )
    )
    await waitFor(() => expect(window.location.href).toBe('https://slack.test/reauth'))
  })

  it('seeds optional capabilities on reauthorize even when the catalog resolves after the picker opens', async () => {
    // Hold the capability catalog so the picker mounts before it arrives; the
    // initial state then seeds from an empty catalog and must be re-seeded once
    // the catalog resolves.
    let resolveCapabilities: (value: SlackCapabilitiesResponse) => void = () => {}
    apiMock.getSlackCapabilities.mockReturnValue(
      new Promise<SlackCapabilitiesResponse>((resolve) => {
        resolveCapabilities = resolve
      })
    )

    renderDialog(<SlackWorkspacesDialog onClose={vi.fn()} />)
    await screen.findByText('Globex Grid')

    // Open the reauthorize picker while the catalog is still loading.
    fireEvent.click(within(cardFor('Globex Grid')).getByRole('button', {name: 'Reauthorize'}))
    expect(await screen.findByText('Reauthorize Globex Grid')).toBeInTheDocument()
    expect(screen.getByText('Loading capabilities…')).toBeInTheDocument()

    // The catalog arrives after the picker mounted.
    resolveCapabilities(CAPABILITIES)

    // The Assistant, enabled on this install, is seeded checked once the catalog
    // resolves and flows through to the reauthorization request.
    const assistant = await screen.findByRole('checkbox', {name: /Slack Assistant/})
    await waitFor(() => expect(assistant).toBeChecked())

    fireEvent.click(screen.getByRole('button', {name: 'Continue to Slack'}))
    await waitFor(() =>
      expect(apiMock.reauthorizeSlackInstallation).toHaveBeenCalledWith(
        'inst-grid',
        expect.arrayContaining(['alert_delivery', 'on_call_usergroups', 'assistant'])
      )
    )
  })

  it('loads channels lazily when changing a workspace channel', async () => {
    renderDialog(<SlackWorkspacesDialog onClose={vi.fn()} />)
    await screen.findByText('Acme HQ')
    fireEvent.click(within(cardFor('Acme HQ')).getByRole('button', {name: 'Change'}))
    await waitFor(() =>
      expect(apiMock.getSlackInstallationChannels).toHaveBeenCalledWith('inst-default')
    )
  })

  it('offers a retry when the workspace list fails to load', async () => {
    apiMock.getSlackInstallations.mockRejectedValueOnce(new Error('network down'))
    renderDialog(<SlackWorkspacesDialog onClose={vi.fn()} />)

    expect(await screen.findByText('Couldn’t load Slack workspaces')).toBeInTheDocument()

    apiMock.getSlackInstallations.mockResolvedValue([installation()])
    fireEvent.click(screen.getByRole('button', {name: /Retry/}))

    expect(await screen.findByText('Acme HQ')).toBeInTheDocument()
    expect(screen.queryByText('Couldn’t load Slack workspaces')).not.toBeInTheDocument()
  })

  it('refetches the workspace list on demand', async () => {
    renderDialog(<SlackWorkspacesDialog onClose={vi.fn()} />)
    await screen.findByText('Acme HQ')
    expect(screen.getByText('2 Slack installs connected')).toBeInTheDocument()

    fireEvent.click(screen.getByRole('button', {name: 'Refresh workspaces'}))
    await waitFor(() => expect(apiMock.getSlackInstallations).toHaveBeenCalledTimes(2))
  })

  it('counts a single install in the singular', async () => {
    apiMock.getSlackInstallations.mockResolvedValue([installation()])
    renderDialog(<SlackWorkspacesDialog onClose={vi.fn()} />)
    expect(await screen.findByText('1 Slack install connected')).toBeInTheDocument()
  })

  it('identifies an install by whatever Slack did send', async () => {
    apiMock.getSlackInstallations.mockResolvedValue([
      // Names have not been backfilled, so the team id is all there is to show.
      installation({id: 'inst-ids', teamName: null, teamId: 'T77'}),
      installation({
        id: 'inst-bare',
        teamId: null,
        teamName: null,
        enterpriseId: null,
        enterpriseName: null,
        isEnterpriseInstall: true,
        isDefault: false,
      }),
    ])
    renderDialog(<SlackWorkspacesDialog onClose={vi.fn()} />)

    expect(await screen.findByText('T77')).toBeInTheDocument()
    expect(screen.getByText('Slack organization')).toBeInTheDocument()
  })

  it('names the principal that must grant each missing scope', async () => {
    apiMock.getSlackInstallations.mockResolvedValue([
      installation({
        health: 'MISSING_SCOPES',
        healthDetail: null,
        enabledCapabilities: ['alert_delivery', 'on_call_usergroups'],
        capabilityHealth: [
          {
            capabilityId: 'alert_delivery',
            missingBotScopes: ['chat:write'],
            missingUserScopes: [],
            healthy: false,
          },
          {
            capabilityId: 'on_call_usergroups',
            missingBotScopes: [],
            missingUserScopes: ['admin.usergroups:write'],
            healthy: false,
          },
        ],
      }),
    ])
    renderDialog(<SlackWorkspacesDialog onClose={vi.fn()} />)
    await screen.findByText('Acme HQ')
    const card = cardFor('Acme HQ')

    expect(within(card).getByText(/Bot scopes not granted: chat:write/)).toBeInTheDocument()
    expect(
      within(card).getByText(/Privileged-user scopes not granted: admin.usergroups:write/)
    ).toBeInTheDocument()
    // The capability chip carries the same warning inline.
    expect(
      within(card).getAllByTitle('Slack has not granted every scope this capability needs')
    ).toHaveLength(2)
  })

  it('still prompts a reauthorization when the backend named no missing scopes', async () => {
    apiMock.getSlackInstallations.mockResolvedValue([
      installation({health: 'TOKEN_REVOKED', healthDetail: null, missingScopes: []}),
    ])
    renderDialog(<SlackWorkspacesDialog onClose={vi.fn()} />)

    expect(
      await screen.findByText('Reauthorize with Slack to restore delivery.')
    ).toBeInTheDocument()
  })

  it('lists each attached workspace with its own health', async () => {
    apiMock.getSlackInstallations.mockResolvedValue([
      installation({
        workspaceBindings: [
          binding(),
          binding({
            id: 'bind-2',
            teamId: 'T200',
            teamName: null,
            isPrimary: false,
            enabled: false,
            health: 'TOKEN_REVOKED',
            healthDetail: 'Slack revoked the bot token.',
            lastVerifiedAt: null,
          }),
        ],
      }),
    ])
    renderDialog(<SlackWorkspacesDialog onClose={vi.fn()} />)
    await screen.findByText('Acme HQ')
    const card = cardFor('Acme HQ')

    expect(within(card).getByText('2 attached')).toBeInTheDocument()
    expect(within(card).getByText('Sales Workspace')).toBeInTheDocument()
    expect(within(card).getByText('Primary')).toBeInTheDocument()
    expect(within(card).getByText(/Verified/)).toBeInTheDocument()
    // An unnamed workspace still identifies itself, and its own trouble shows.
    expect(within(card).getByText('T200')).toBeInTheDocument()
    expect(within(card).getByText('· Paused')).toBeInTheDocument()
    expect(within(card).getByText('Slack revoked the bot token.')).toBeInTheDocument()
    expect(
      within(card).getByText(/one can need attention while the others stay healthy/)
    ).toBeInTheDocument()
  })

  it('shows the bot and privileged-user authorizations with their token lifecycle', async () => {
    apiMock.getSlackInstallations.mockResolvedValue([
      installation({
        grants: [
          grant({expiresAt: '2099-01-01T00:00:00Z', rotatedAt: '2026-08-01T00:00:00Z'}),
          grant({
            id: 'grant-2',
            grantType: 'USER',
            slackUserId: 'U42',
            grantedScopes: ['admin.conversations:write'],
            revokedAt: '2026-08-10T00:00:00Z',
            health: 'TOKEN_REVOKED',
            healthDetail: 'The admin who authorized this left the workspace.',
          }),
        ],
      }),
    ])
    renderDialog(<SlackWorkspacesDialog onClose={vi.fn()} />)
    await screen.findByText('Acme HQ')
    const card = cardFor('Acme HQ')

    expect(within(card).getByText('Bot user')).toBeInTheDocument()
    expect(within(card).getByText('· 2 scopes')).toBeInTheDocument()
    expect(within(card).getByText(/Expires in/)).toBeInTheDocument()
    expect(within(card).getByText(/Rotated/)).toBeInTheDocument()

    expect(within(card).getByText('Privileged user')).toBeInTheDocument()
    expect(within(card).getByText('U42')).toBeInTheDocument()
    // A single scope reads in the singular.
    expect(within(card).getByText('· 1 scope')).toBeInTheDocument()
    expect(within(card).getByText(/Revoked/)).toBeInTheDocument()
    expect(
      within(card).getByText('The admin who authorized this left the workspace.')
    ).toBeInTheDocument()
    // The privileged-access explanation only appears once a user grant exists.
    expect(within(card).getByText(SLACK_PRIVILEGED_ACCESS_NOTE)).toBeInTheDocument()
  })

  it('flags a grant whose token has already expired', async () => {
    apiMock.getSlackInstallations.mockResolvedValue([
      installation({grants: [grant({expiresAt: '2020-01-01T00:00:00Z'})]}),
    ])
    renderDialog(<SlackWorkspacesDialog onClose={vi.fn()} />)
    await screen.findByText('Acme HQ')
    expect(within(cardFor('Acme HQ')).getByText(/Expired .* ago/)).toBeInTheDocument()
    // A bot-only install does not claim a privileged user authorized anything.
    expect(screen.queryByText(SLACK_PRIVILEGED_ACCESS_NOTE)).not.toBeInTheDocument()
  })

  it('saves a chosen channel and closes the picker', async () => {
    apiMock.getSlackInstallations.mockResolvedValue([installation()])
    apiMock.getSlackInstallationChannels.mockResolvedValue({
      channels: [
        {id: 'C1', name: 'alerts'},
        {id: 'C2', name: 'incidents'},
      ],
    })
    apiMock.setSlackInstallationChannel.mockResolvedValue(
      installation({defaultChannelId: 'C2', defaultChannelName: 'incidents'})
    )
    renderDialog(<SlackWorkspacesDialog onClose={vi.fn()} />)
    await screen.findByText('Acme HQ')

    fireEvent.click(within(cardFor('Acme HQ')).getByRole('button', {name: 'Change'}))
    const trigger = await screen.findByRole('combobox', {name: 'Default channel'})
    await waitFor(() => expect(trigger).toBeEnabled())
    fireEvent.click(trigger)
    fireEvent.click(await screen.findByRole('option', {name: '#incidents'}))

    await waitFor(() =>
      expect(apiMock.setSlackInstallationChannel).toHaveBeenCalledWith(
        'inst-default',
        'C2',
        'incidents'
      )
    )
    await waitFor(() =>
      expect(toastMock).toHaveBeenCalledWith(expect.objectContaining({title: 'Channel updated'}))
    )
    // Saving closes the picker, so the row offers to change again.
    expect(await screen.findByRole('button', {name: 'Change'})).toBeInTheDocument()
  })

  it('surfaces a channel save failure without closing the picker', async () => {
    apiMock.getSlackInstallations.mockResolvedValue([installation()])
    apiMock.getSlackInstallationChannels.mockResolvedValue({
      channels: [
        {id: 'C1', name: 'alerts'},
        {id: 'C2', name: 'incidents'},
      ],
    })
    apiMock.setSlackInstallationChannel.mockRejectedValue(new Error('channel_not_found'))
    renderDialog(<SlackWorkspacesDialog onClose={vi.fn()} />)
    await screen.findByText('Acme HQ')

    fireEvent.click(within(cardFor('Acme HQ')).getByRole('button', {name: 'Change'}))
    const trigger = await screen.findByRole('combobox', {name: 'Default channel'})
    await waitFor(() => expect(trigger).toBeEnabled())
    fireEvent.click(trigger)
    fireEvent.click(await screen.findByRole('option', {name: '#incidents'}))

    await waitFor(() =>
      expect(toastMock).toHaveBeenCalledWith(
        expect.objectContaining({
          title: 'Failed to update channel',
          description: 'channel_not_found',
          variant: 'destructive',
        })
      )
    )
    expect(screen.getByRole('combobox', {name: 'Default channel'})).toBeInTheDocument()
  })

  it('explains a channel list Slack would not return', async () => {
    apiMock.getSlackInstallations.mockResolvedValue([installation()])
    apiMock.getSlackInstallationChannels.mockRejectedValue(new Error('token_revoked'))
    renderDialog(<SlackWorkspacesDialog onClose={vi.fn()} />)
    await screen.findByText('Acme HQ')

    fireEvent.click(within(cardFor('Acme HQ')).getByRole('button', {name: 'Change'}))
    expect(
      await screen.findByText(
        'Couldn’t load channels. Reauthorize the workspace if its token was revoked.'
      )
    ).toBeInTheDocument()
    expect(screen.queryByRole('combobox', {name: 'Default channel'})).not.toBeInTheDocument()
  })

  it('reads honestly when a workspace has no channel and no capabilities', async () => {
    apiMock.getSlackInstallations.mockResolvedValue([
      installation({defaultChannelId: null, defaultChannelName: null, enabledCapabilities: []}),
    ])
    renderDialog(<SlackWorkspacesDialog onClose={vi.fn()} />)
    await screen.findByText('Acme HQ')
    const card = cardFor('Acme HQ')

    expect(within(card).getByText('None selected')).toBeInTheDocument()
    expect(within(card).getByText('—')).toBeInTheDocument()
  })

  it('offers a retry when the capability catalog fails to load', async () => {
    apiMock.getSlackCapabilities.mockRejectedValueOnce(new Error('catalog down'))
    renderDialog(<SlackWorkspacesDialog onClose={vi.fn()} />)
    fireEvent.click(await screen.findByRole('button', {name: 'Add workspace'}))

    expect(await screen.findByText('Couldn’t load Slack capabilities')).toBeInTheDocument()

    apiMock.getSlackCapabilities.mockResolvedValue(CAPABILITIES)
    fireEvent.click(screen.getByRole('button', {name: /Retry/}))

    expect(await screen.findByText('Required capabilities')).toBeInTheDocument()
  })

  it('returns to the workspace list from the picker without authorizing', async () => {
    renderDialog(<SlackWorkspacesDialog onClose={vi.fn()} />)
    fireEvent.click(await screen.findByRole('button', {name: 'Add workspace'}))
    await screen.findByText('Required capabilities')

    fireEvent.click(screen.getByRole('button', {name: /Back/}))

    expect(await screen.findByText('Acme HQ')).toBeInTheDocument()
    expect(apiMock.startSlackInstallation).not.toHaveBeenCalled()
  })

  it('surfaces a failure to start Slack authorization', async () => {
    apiMock.startSlackInstallation.mockRejectedValue(new Error('slack unreachable'))
    renderDialog(<SlackWorkspacesDialog onClose={vi.fn()} />)
    fireEvent.click(await screen.findByRole('button', {name: 'Add workspace'}))
    await screen.findByText('Required capabilities')

    fireEvent.click(screen.getByRole('button', {name: 'Authorize with Slack'}))

    await waitFor(() =>
      expect(toastMock).toHaveBeenCalledWith(
        expect.objectContaining({
          title: 'Couldn’t start Slack authorization',
          description: 'slack unreachable',
          variant: 'destructive',
        })
      )
    )
    // The browser stays put rather than navigating to an unknown URL.
    expect(window.location.href).toBe('')
  })

  it('withholds an optional capability that is toggled back off', async () => {
    renderDialog(<SlackWorkspacesDialog onClose={vi.fn()} />)
    fireEvent.click(await screen.findByRole('button', {name: 'Add workspace'}))

    const assistant = await screen.findByRole('checkbox', {name: /Slack Assistant/})
    fireEvent.click(assistant)
    expect(assistant).toBeChecked()
    fireEvent.click(assistant)
    expect(assistant).not.toBeChecked()

    fireEvent.click(screen.getByRole('button', {name: 'Authorize with Slack'}))
    await waitFor(() =>
      expect(apiMock.startSlackInstallation).toHaveBeenCalledWith([
        'alert_delivery',
        'on_call_usergroups',
      ])
    )
  })

  it('keeps privileged-user scopes visibly apart from the bot grant', async () => {
    apiMock.getSlackCapabilities.mockResolvedValue(PRIVILEGED_CAPABILITIES)
    renderDialog(<SlackWorkspacesDialog onClose={vi.fn()} />)
    fireEvent.click(await screen.findByRole('button', {name: 'Add workspace'}))
    await screen.findByText('Required capabilities')

    // Two bot scopes from alert delivery plus the one admin-granted user scope.
    expect(screen.getByText('Slack will be asked to grant 3 scopes')).toBeInTheDocument()
    expect(screen.getByText('Privileged user')).toBeInTheDocument()
    expect(screen.getByText(/Privileged-user scopes/)).toBeInTheDocument()
    expect(screen.getByText(SLACK_USER_SCOPE_PICKER_NOTE)).toBeInTheDocument()
    expect(screen.getByText(SLACK_PRIVILEGED_ACCESS_NOTE)).toBeInTheDocument()
    // The capability grants no bot scopes, so it shows no bot-scope cluster of its own.
    expect(screen.getAllByText(/Bot scopes/)).toHaveLength(1)
  })

  it('reports the result of a health check', async () => {
    apiMock.getSlackInstallations.mockResolvedValue([installation()])
    apiMock.checkSlackInstallationHealth.mockResolvedValue(
      installation({health: 'DEGRADED', healthDetail: 'Slack returned a 503.'})
    )
    renderDialog(<SlackWorkspacesDialog onClose={vi.fn()} />)
    await screen.findByText('Acme HQ')

    fireEvent.click(screen.getByRole('button', {name: 'Run health check'}))
    await waitFor(() =>
      expect(toastMock).toHaveBeenCalledWith({
        title: 'Health: Degraded',
        description: 'Slack returned a 503.',
      })
    )
  })

  it('reports a test message Slack refused to deliver', async () => {
    apiMock.getSlackInstallations.mockResolvedValue([installation()])
    apiMock.testSlackInstallation.mockResolvedValue({success: false, message: 'not_in_channel'})
    renderDialog(<SlackWorkspacesDialog onClose={vi.fn()} />)
    await screen.findByText('Acme HQ')

    fireEvent.click(screen.getByRole('button', {name: 'Send test message'}))
    await waitFor(() =>
      expect(toastMock).toHaveBeenCalledWith({
        title: 'Test failed',
        description: 'not_in_channel',
        variant: 'destructive',
      })
    )
  })

  it.each([
    {
      action: 'Run health check',
      mock: 'checkSlackInstallationHealth',
      title: 'Health check failed',
    },
    {action: 'Send test message', mock: 'testSlackInstallation', title: 'Test failed'},
  ] as const)('surfaces a $title', async ({action, mock, title}) => {
    apiMock.getSlackInstallations.mockResolvedValue([installation()])
    apiMock[mock].mockRejectedValue(new Error('slack_error'))
    renderDialog(<SlackWorkspacesDialog onClose={vi.fn()} />)
    await screen.findByText('Acme HQ')

    fireEvent.click(screen.getByRole('button', {name: action}))
    await waitFor(() =>
      expect(toastMock).toHaveBeenCalledWith({
        title,
        description: 'slack_error',
        variant: 'destructive',
      })
    )
  })

  it('surfaces a delivery toggle failure', async () => {
    apiMock.getSlackInstallations.mockResolvedValue([installation()])
    apiMock.setSlackInstallationEnabled.mockRejectedValue(new Error('installation_disabled'))
    renderDialog(<SlackWorkspacesDialog onClose={vi.fn()} />)

    fireEvent.click(await screen.findByRole('switch', {name: 'Enable Slack delivery to Acme HQ'}))
    await waitFor(() =>
      expect(toastMock).toHaveBeenCalledWith({
        title: 'Failed to update delivery',
        description: 'installation_disabled',
        variant: 'destructive',
      })
    )
  })

  it('confirms a new default install, and surfaces a failure to set one', async () => {
    apiMock.getSlackInstallations.mockResolvedValue([
      installation(),
      installation({id: 'inst-second', teamId: 'T2', teamName: 'Second Team', isDefault: false}),
    ])
    renderDialog(<SlackWorkspacesDialog onClose={vi.fn()} />)
    await screen.findByText('Second Team')

    fireEvent.click(within(cardFor('Second Team')).getByRole('button', {name: 'Set as default'}))
    await waitFor(() =>
      expect(toastMock).toHaveBeenCalledWith({
        title: 'Default updated',
        description: 'Second Team is now the default Slack install.',
      })
    )

    apiMock.setSlackInstallationDefault.mockRejectedValue(new Error('not_permitted'))
    fireEvent.click(within(cardFor('Second Team')).getByRole('button', {name: 'Set as default'}))
    await waitFor(() =>
      expect(toastMock).toHaveBeenCalledWith({
        title: 'Failed to set default',
        description: 'not_permitted',
        variant: 'destructive',
      })
    )
  })

  it('warns that removing the default reassigns it, and can be called off', async () => {
    apiMock.getSlackInstallations.mockResolvedValue([
      installation(),
      installation({id: 'inst-second', teamId: 'T2', teamName: 'Second Team', isDefault: false}),
    ])
    renderDialog(<SlackWorkspacesDialog onClose={vi.fn()} />)

    fireEvent.click(await screen.findByRole('button', {name: 'Remove Acme HQ'}))
    expect(await screen.findByText('Remove Acme HQ?')).toBeInTheDocument()
    expect(screen.getByText(/Another install becomes the default/)).toBeInTheDocument()

    fireEvent.click(screen.getByRole('button', {name: 'Cancel'}))
    await waitFor(() => expect(screen.queryByText('Remove Acme HQ?')).not.toBeInTheDocument())
    expect(apiMock.deleteSlackInstallation).not.toHaveBeenCalled()
  })

  it('surfaces a failure to remove a workspace', async () => {
    apiMock.getSlackInstallations.mockResolvedValue([installation()])
    apiMock.deleteSlackInstallation.mockRejectedValue(new Error('still_referenced'))
    renderDialog(<SlackWorkspacesDialog onClose={vi.fn()} />)

    fireEvent.click(await screen.findByRole('button', {name: 'Remove Acme HQ'}))
    // A sole install carries no reassignment warning.
    expect(screen.queryByText(/Another install becomes the default/)).not.toBeInTheDocument()

    fireEvent.click(screen.getByRole('button', {name: 'Remove install'}))
    await waitFor(() =>
      expect(toastMock).toHaveBeenCalledWith({
        title: 'Failed to remove',
        description: 'still_referenced',
        variant: 'destructive',
      })
    )
  })

  it('closes the dialog when dismissed', async () => {
    const onClose = vi.fn()
    renderDialog(<SlackWorkspacesDialog onClose={onClose} />)
    await screen.findByText('Acme HQ')

    fireEvent.keyDown(document.activeElement ?? document.body, {key: 'Escape'})
    await waitFor(() => expect(onClose).toHaveBeenCalled())
  })
})
