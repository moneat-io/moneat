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
import {render, screen, waitFor} from '@testing-library/react'
import userEvent from '@testing-library/user-event'
import {beforeEach, describe, expect, it, vi} from 'vitest'

import {ConnectorsSettings} from '../ConnectorsSettings'
import type {ConnectorProviderDefinition, OrganizationIntegration} from '@/lib/api/types'

const apiMock = vi.hoisted(() => ({
  isAuthenticated: vi.fn(() => true),
  getConnectorProviders: vi.fn(),
  getIntegrations: vi.fn(),
  getConnectorState: vi.fn(),
  getSlackChannels: vi.fn(),
  getDiscordChannels: vi.fn(),
  startSlackOAuth: vi.fn(),
  startDiscordOAuth: vi.fn(),
  updateSlackChannel: vi.fn(),
  updateDiscordChannel: vi.fn(),
  toggleSlackIntegration: vi.fn(),
  toggleDiscordIntegration: vi.fn(),
  testSlackIntegration: vi.fn(),
  testDiscordIntegration: vi.fn(),
  deleteSlackIntegration: vi.fn(),
  deleteDiscordIntegration: vi.fn(),
}))

vi.mock('@/lib/api', () => ({api: apiMock}))
vi.mock('@/lib/analytics', () => ({trackEvent: vi.fn()}))
vi.mock('@/hooks/useToast', () => ({useToast: () => ({toast: vi.fn()})}))

function provider(
  id: string,
  name: string,
  availability: 'available' | 'planned' | 'enterprise',
  family: ConnectorProviderDefinition['uses'][number]['family'] = 'notification'
): ConnectorProviderDefinition {
  return {
    id,
    name,
    description: `${name} connector`,
    authProfiles: [],
    uses: [
      {
        id: `${id}-use`,
        name: `${name} use`,
        family,
        availability,
        setupMode: 'oauth',
        capabilities: [],
        stateSource: 'integration',
        secretPurpose: null,
        description: `${name} use`,
        direction: 'read_write',
        allowedAuthProfileIds: [],
        uiGroup: 'send_notifications',
      },
    ],
  }
}

function renderWithClient(ui: React.ReactElement) {
  const queryClient = new QueryClient({
    defaultOptions: {queries: {retry: false}, mutations: {retry: false}},
  })
  return render(<QueryClientProvider client={queryClient}>{ui}</QueryClientProvider>)
}

describe('ConnectorsSettings', () => {
  beforeEach(() => {
    vi.clearAllMocks()
    apiMock.getConnectorProviders.mockResolvedValue({
      providers: [
        provider('slack', 'Slack', 'available'),
        provider('github', 'GitHub', 'planned', 'dashboard_query'),
        provider('pagerduty', 'PagerDuty', 'enterprise'),
      ],
    })
    apiMock.getIntegrations.mockResolvedValue([
      {
        id: 'integration-slack',
        integrationType: 'slack',
        teamName: 'Observability',
        channelId: 'C123',
        channelName: 'alerts',
        enabled: true,
        isConfigured: true,
      } satisfies OrganizationIntegration,
    ])
    apiMock.getConnectorState.mockResolvedValue({
      connections: [],
      providers: [
        {
          providerId: 'slack',
          uses: [
            {
              useId: 'slack-use',
              availability: 'available',
              stateSource: 'integration',
              state: 'connected',
              connected: true,
              enabled: true,
              integrationId: 'integration-slack',
              message: 'Healthy',
            },
          ],
        },
      ],
    })
    apiMock.getSlackChannels.mockResolvedValue({channels: [{id: 'C123', name: 'alerts'}]})
  })

  it('groups connected and unavailable providers with clear state labels', async () => {
    renderWithClient(<ConnectorsSettings />)

    expect(await screen.findByText('Slack')).toBeInTheDocument()
    expect(screen.getByText('GitHub')).toBeInTheDocument()
    expect(screen.getByText('PagerDuty')).toBeInTheDocument()
    expect(screen.getAllByText('Connected')).toHaveLength(2)
    expect(screen.getByText('Planned')).toBeInTheDocument()
    expect(screen.getAllByText('Enterprise')).toHaveLength(2)
    expect(screen.getByText('Active')).toBeInTheDocument()
    expect(screen.getByRole('button', {name: 'Coming soon'})).toBeDisabled()
  })

  it('opens the live manage dialog from the provider id', async () => {
    const user = userEvent.setup()
    renderWithClient(<ConnectorsSettings />)

    await user.click(await screen.findByRole('button', {name: 'Manage'}))

    expect(screen.getByText('Manage Slack')).toBeInTheDocument()
    expect(screen.getByText(/Connected to Observability/)).toBeInTheDocument()
    await waitFor(() => expect(apiMock.getSlackChannels).toHaveBeenCalled())
  })
})
