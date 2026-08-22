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

import type {SlackCapabilitiesResponse, SlackInstallationSummary} from '@/lib/api/types'
import {SlackWorkspacesDialog} from '@/components/settings/SlackWorkspacesDialog'

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

  it('loads channels lazily when changing a workspace channel', async () => {
    renderDialog(<SlackWorkspacesDialog onClose={vi.fn()} />)
    await screen.findByText('Acme HQ')
    fireEvent.click(within(cardFor('Acme HQ')).getByRole('button', {name: 'Change'}))
    await waitFor(() =>
      expect(apiMock.getSlackInstallationChannels).toHaveBeenCalledWith('inst-default')
    )
  })
})
