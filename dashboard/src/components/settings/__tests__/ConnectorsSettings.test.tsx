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
import userEvent from '@testing-library/user-event'
import {beforeAll, beforeEach, describe, expect, it, vi} from 'vitest'

import {ConnectorsSettings} from '../ConnectorsSettings'
import type {
  ConnectorInstallationResponse,
  ConnectorProviderDefinition,
  ConnectorProviderStateDetail,
  OrganizationIntegration,
} from '@/lib/api/types'

const apiMock = vi.hoisted(() => ({
  isAuthenticated: vi.fn(() => true),
  getConnectorProviders: vi.fn(),
  getIntegrations: vi.fn(),
  getConnectorState: vi.fn(),
  getConnectorInstallations: vi.fn(),
  getConnectorInstallation: vi.fn(),
  createConnectorInstallation: vi.fn(),
  deleteConnectorInstallation: vi.fn(),
  testConnectorInstallation: vi.fn(),
  rotateConnectorApiCredential: vi.fn(),
  rotateConnectorWebhookToken: vi.fn(),
  getConnectorExternalResources: vi.fn(),
  refreshConnectorExternalResources: vi.fn(),
  getConnectorBindings: vi.fn(),
  updateConnectorBindings: vi.fn(),
  getConnectorWebhookSetup: vi.fn(),
  getProjects: vi.fn(),
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

// RevenueCat ships as an installation-backed data-import connector (stateSource
// connector_installations), unlike the OAuth notification providers above.
function revenueCatProvider(
  availability: 'available' | 'planned' | 'enterprise' = 'available'
): ConnectorProviderDefinition {
  return {
    id: 'revenuecat',
    name: 'RevenueCat',
    description: 'Connect RevenueCat for subscription lifecycle and revenue data.',
    authProfiles: [
      {
        id: 'project_api_key',
        name: 'Project API key',
        authKind: 'api_key',
        subjectKinds: ['service_account'],
        tokenLifecycle: 'rotatable',
        defaultScopes: ['projects.read'],
        separateReconsentRequired: false,
        secretPurpose: 'data_import',
      },
    ],
    uses: [
      {
        id: 'subscription_import',
        name: 'Subscription import',
        family: 'data_import',
        availability,
        setupMode: 'api_key',
        capabilities: ['subscription_lifecycle_import'],
        stateSource: 'connector_installations',
        secretPurpose: 'data_import',
        statusRoute: '/v1/connectors/state',
        description: 'Import subscription lifecycle and revenue events.',
        direction: 'read',
        allowedAuthProfileIds: ['project_api_key'],
        resourceTypes: ['revenuecat_app'],
        uiGroup: 'import_data',
      },
    ],
  }
}

const rcInstallation: ConnectorInstallationResponse = {
  id: 'inst-1',
  providerId: 'revenuecat',
  name: 'Prod RevenueCat',
  credentialType: 'api_key',
  authProfileId: 'project_api_key',
  externalProjectId: 'proj_abc',
  externalProjectName: 'Acme',
  status: 'healthy',
  statusReason: null,
  enabled: true,
  apiSecretLastFour: '1234',
  webhookTokenPrefix: 'whpref123456',
  webhookToken: null,
  lastTestedAt: '2026-06-15T10:00:00Z',
  lastTestResult: 'success',
  lastSuccessfulProviderCallAt: '2026-06-15T10:00:00Z',
  lastError: null,
  createdAt: '2026-06-15T09:00:00Z',
  updatedAt: '2026-06-15T10:00:00Z',
}

const rcDetail: ConnectorProviderStateDetail = {
  installationId: 'inst-1',
  status: 'awaiting_traffic',
  health: 'healthy',
  message: 'Connected, awaiting RevenueCat webhook traffic',
  mappedResources: 2,
  unmappedEvents: 0,
  failedReceipts: 0,
  sandboxEvents: 0,
  productionEvents: 0,
  lastAcceptedWebhookAt: null,
  lastAppliedAt: null,
  processingLagSeconds: null,
}

// Connects RevenueCat: installation present + connected provider state with detail,
// and no OAuth integrations so RevenueCat is the only "Manage"-able connector.
function connectRevenueCat() {
  apiMock.getIntegrations.mockResolvedValue([])
  apiMock.getConnectorInstallations.mockResolvedValue({installations: [rcInstallation]})
  apiMock.getConnectorInstallation.mockResolvedValue(rcInstallation)
  apiMock.getConnectorState.mockResolvedValue({
    connections: [
      {providerId: 'revenuecat', connected: true, health: 'healthy', detail: rcDetail.message},
    ],
    providers: [
      {
        providerId: 'revenuecat',
        uses: [
          {
            useId: 'subscription_import',
            availability: 'available',
            stateSource: 'connector_installations',
            state: 'connected',
            connected: true,
            enabled: true,
            integrationId: 'inst-1',
            message: rcDetail.message,
            detail: rcDetail,
          },
        ],
      },
    ],
  })
}

function renderWithClient(ui: React.ReactElement) {
  const queryClient = new QueryClient({
    defaultOptions: {queries: {retry: false}, mutations: {retry: false}},
  })
  return render(<QueryClientProvider client={queryClient}>{ui}</QueryClientProvider>)
}

describe('ConnectorsSettings', () => {
  beforeAll(() => {
    // Radix Select/Tabs use pointer-capture + scrollIntoView, absent in jsdom.
    if (!HTMLElement.prototype.hasPointerCapture) HTMLElement.prototype.hasPointerCapture = () => false
    if (!HTMLElement.prototype.setPointerCapture) HTMLElement.prototype.setPointerCapture = () => {}
    if (!HTMLElement.prototype.releasePointerCapture) HTMLElement.prototype.releasePointerCapture = () => {}
    if (!HTMLElement.prototype.scrollIntoView) HTMLElement.prototype.scrollIntoView = () => {}
  })

  beforeEach(() => {
    vi.clearAllMocks()
    apiMock.isAuthenticated.mockReturnValue(true)
    apiMock.getSlackCapabilities.mockResolvedValue({capabilities: [], scopes: []})
    apiMock.getSlackInstallations.mockResolvedValue([
      {
        id: 'inst-obs',
        teamId: 'T1',
        teamName: 'Observability',
        enterpriseId: null,
        enterpriseName: null,
        isEnterpriseInstall: false,
        appId: 'A1',
        botUserId: 'B1',
        grantedScopes: ['chat:write'],
        enabledCapabilities: ['alert_delivery'],
        missingScopes: [],
        defaultChannelId: 'C123',
        defaultChannelName: 'alerts',
        isDefault: true,
        enabled: true,
        health: 'HEALTHY',
        healthDetail: null,
        lastVerifiedAt: null,
        createdAt: '2026-01-01T00:00:00Z',
        updatedAt: '2026-01-01T00:00:00Z',
      },
    ])
    apiMock.getConnectorProviders.mockResolvedValue({
      providers: [
        provider('slack', 'Slack', 'available'),
        provider('github', 'GitHub', 'planned', 'dashboard_query'),
        provider('pagerduty', 'PagerDuty', 'enterprise'),
        revenueCatProvider('available'),
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
    // Installation-backed defaults: nothing connected, no apps/bindings/projects.
    apiMock.getConnectorInstallations.mockResolvedValue({installations: []})
    apiMock.getConnectorInstallation.mockResolvedValue(rcInstallation)
    apiMock.getConnectorExternalResources.mockResolvedValue({resources: []})
    apiMock.getConnectorBindings.mockResolvedValue({bindings: []})
    apiMock.getProjects.mockResolvedValue([])
    apiMock.getConnectorWebhookSetup.mockResolvedValue({
      installationId: 'inst-1',
      providerId: 'revenuecat',
      webhookUrl: 'https://moneat.test/api/connectors/revenuecat/inst-1/webhook',
      authorizationHeaderName: 'Authorization',
      authorizationHeaderValue: null,
      authorizationHeaderPrefix: 'whpref123456',
      recommendedEventTypes: ['INITIAL_PURCHASE', 'RENEWAL'],
      recommendedEnvironment: 'production_and_sandbox',
      setupMode: 'manual',
      observedIntegrations: [],
      warnings: [],
    })
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

  it('opens the Slack workspace manager from the provider id', async () => {
    const user = userEvent.setup()
    renderWithClient(<ConnectorsSettings />)

    await user.click(await screen.findByRole('button', {name: 'Manage'}))

    // Slack now opens the multi-workspace manager instead of the generic dialog.
    expect(await screen.findByText('Slack workspaces')).toBeInTheDocument()
    await waitFor(() => expect(apiMock.getSlackInstallations).toHaveBeenCalled())
  })

  it('shows RevenueCat as an available data-import connector with a connect action', async () => {
    renderWithClient(<ConnectorsSettings />)

    expect(await screen.findByText('RevenueCat')).toBeInTheDocument()
    // Only RevenueCat exposes a Connect action here (Slack is connected, GitHub is
    // planned, PagerDuty is enterprise), and it is not gated behind Coming soon.
    const connect = screen.getByRole('button', {name: 'Connect'})
    expect(connect).toBeEnabled()
    expect(screen.getAllByText('Data import').length).toBeGreaterThan(0)
  })

  it('focuses and filters the connector catalog', async () => {
    const user = userEvent.setup()

    renderWithClient(<ConnectorsSettings />)

    await screen.findByRole('button', {name: 'Add connector'})

    await user.click(screen.getByRole('button', {name: 'Dashboard data'}))
    expect(screen.getByText('GitHub')).toBeInTheDocument()
    expect(screen.queryByText('Slack')).not.toBeInTheDocument()

    fireEvent.change(screen.getByPlaceholderText('Search connectors…'), {
      target: {value: 'missing-provider'},
    })
    expect(screen.getByText('No connectors match your filters.')).toBeInTheDocument()
  })

  it('submits the generic create payload and reveals the one-time webhook token', async () => {
    const user = userEvent.setup()
    apiMock.createConnectorInstallation.mockResolvedValue({
      ...rcInstallation,
      webhookToken: 'test-webhook-token',
    })
    renderWithClient(<ConnectorsSettings />)

    await user.click(await screen.findByRole('button', {name: 'Connect'}))

    const dialog = await screen.findByRole('dialog')
    await user.type(within(dialog).getByLabelText('Connection name'), 'Prod RevenueCat')
    await user.type(within(dialog).getByLabelText('RevenueCat project ID'), 'proj_abc')
    await user.type(within(dialog).getByLabelText('RevenueCat V2 secret API key'), 'sk_secret_value')
    await user.click(within(dialog).getByRole('button', {name: 'Connect'}))

    await waitFor(() =>
      expect(apiMock.createConnectorInstallation).toHaveBeenCalledWith({
        providerId: 'revenuecat',
        authProfileId: 'project_api_key',
        name: 'Prod RevenueCat',
        externalAccount: {projectId: 'proj_abc'},
        secret: 'sk_secret_value',
      })
    )

    // Wizard advances to the Webhook step: URL + one-time token revealed, with the
    // token shown as a ready-to-paste Authorization value (and flagged one-time).
    expect(
      await screen.findByDisplayValue('https://moneat.test/api/connectors/revenuecat/inst-1/webhook')
    ).toBeInTheDocument()
    expect(screen.getByText(/shown only once/i)).toBeInTheDocument()
    expect(screen.getAllByDisplayValue('Bearer test-webhook-token').length).toBeGreaterThanOrEqual(1)
  })

  it('sends full replacement bindings when app mapping changes', async () => {
    const user = userEvent.setup()
    connectRevenueCat()
    apiMock.getConnectorExternalResources.mockResolvedValue({
      resources: [
        {
          id: 'res-a',
          installationId: 'inst-1',
          externalProjectId: 'proj_abc',
          externalResourceType: 'revenuecat_app',
          externalResourceId: 'appA',
          displayName: 'App A',
          lastSeenAt: '2026-06-15T10:00:00Z',
        },
        {
          id: 'res-b',
          installationId: 'inst-1',
          externalProjectId: 'proj_abc',
          externalResourceType: 'revenuecat_app',
          externalResourceId: 'appB',
          displayName: 'App B',
          lastSeenAt: '2026-06-15T10:00:00Z',
        },
      ],
    })
    apiMock.getConnectorBindings.mockResolvedValue({
      bindings: [
        bindingFor('appA', 'proj1'),
        bindingFor('appB', 'proj2'),
      ],
    })
    apiMock.getProjects.mockResolvedValue([
      {id: 'proj1', name: 'Web', slug: 'web', keys: [], dsn: 'dsn1'},
      {id: 'proj2', name: 'iOS', slug: 'ios', keys: [], dsn: 'dsn2'},
    ])
    apiMock.updateConnectorBindings.mockResolvedValue({bindings: []})

    renderWithClient(<ConnectorsSettings />)

    await user.click(await screen.findByRole('button', {name: 'Manage'}))
    await user.click(await screen.findByRole('tab', {name: 'App mapping'}))

    // Unmap App A, confirm the reset control restores the server mapping, then
    // unmap again and save.
    await user.click(await screen.findByRole('button', {name: 'Unmap App A'}))
    await user.click(screen.getByRole('button', {name: 'Reset'}))
    expect(screen.getByRole('button', {name: 'Save mapping'})).toBeDisabled()
    await user.click(await screen.findByRole('button', {name: 'Unmap App A'}))
    await user.click(screen.getByRole('button', {name: 'Save mapping'}))

    // Replacement semantics: the full desired list is sent (App B kept), and the
    // unmapped App A is omitted rather than patched.
    await waitFor(() =>
      expect(apiMock.updateConnectorBindings).toHaveBeenCalledWith('inst-1', {
        bindings: [
          {
            externalResourceType: 'revenuecat_app',
            externalResourceId: 'appB',
            localResourceType: 'project',
            localResourceId: 'proj2',
          },
        ],
      })
    )
  })

  it('refreshes an empty RevenueCat app mapping state', async () => {
    const user = userEvent.setup()
    connectRevenueCat()
    apiMock.refreshConnectorExternalResources
      .mockRejectedValueOnce(new Error('RevenueCat unavailable'))
      .mockResolvedValueOnce({resources: []})

    renderWithClient(<ConnectorsSettings />)

    await user.click(await screen.findByRole('button', {name: 'Manage'}))
    await user.click(await screen.findByRole('tab', {name: 'App mapping'}))

    expect(await screen.findByText('No RevenueCat apps yet')).toBeInTheDocument()
    await user.click(screen.getByRole('button', {name: 'Refresh apps'}))

    await waitFor(() => expect(apiMock.refreshConnectorExternalResources).toHaveBeenCalledWith('inst-1'))
    await user.click(screen.getByRole('button', {name: 'Refresh apps'}))
    await waitFor(() => expect(apiMock.refreshConnectorExternalResources).toHaveBeenCalledTimes(2))
  })

  it('keeps the mapping dialog open when saving bindings fails', async () => {
    const user = userEvent.setup()
    connectRevenueCat()
    apiMock.getConnectorExternalResources.mockResolvedValue({
      resources: [
        {
          id: 'res-a',
          installationId: 'inst-1',
          externalProjectId: 'proj_abc',
          externalResourceType: 'revenuecat_app',
          externalResourceId: 'appA',
          displayName: 'App A',
          lastSeenAt: '2026-06-15T10:00:00Z',
        },
      ],
    })
    apiMock.getConnectorBindings.mockResolvedValue({
      bindings: [bindingFor('appA', 'proj1')],
    })
    apiMock.getProjects.mockResolvedValue([
      {id: 'proj1', name: 'Web', slug: 'web', keys: [], dsn: 'dsn1'},
    ])
    apiMock.updateConnectorBindings.mockRejectedValue(new Error('save failed'))

    renderWithClient(<ConnectorsSettings />)

    await user.click(await screen.findByRole('button', {name: 'Manage'}))
    await user.click(await screen.findByRole('tab', {name: 'App mapping'}))
    await user.click(await screen.findByRole('button', {name: 'Unmap App A'}))
    await user.click(screen.getByRole('button', {name: 'Save mapping'}))

    await waitFor(() => expect(apiMock.updateConnectorBindings).toHaveBeenCalled())
    expect(screen.getByRole('button', {name: 'Save mapping'})).toBeInTheDocument()
  })

  it('shows an unmapped RevenueCat app row without enabling save', async () => {
    const user = userEvent.setup()
    connectRevenueCat()
    apiMock.getConnectorExternalResources.mockResolvedValue({
      resources: [
        {
          id: 'res-c',
          installationId: 'inst-1',
          externalProjectId: 'proj_abc',
          externalResourceType: 'revenuecat_app',
          externalResourceId: 'appC',
          displayName: null,
          lastSeenAt: '2026-06-15T10:00:00Z',
        },
      ],
    })
    apiMock.getProjects.mockResolvedValue([
      {id: 'proj1', name: 'Web', slug: 'web', keys: [], dsn: 'dsn1'},
    ])
    apiMock.updateConnectorBindings.mockResolvedValue({bindings: []})

    renderWithClient(<ConnectorsSettings />)

    await user.click(await screen.findByRole('button', {name: 'Manage'}))
    await user.click(await screen.findByRole('tab', {name: 'App mapping'}))

    expect(await screen.findAllByText('appC')).toHaveLength(2)
    expect(screen.getByLabelText('Moneat project for appC')).toBeInTheDocument()
    expect(screen.queryByRole('button', {name: 'Unmap appC'})).not.toBeInTheDocument()
    expect(screen.getByRole('button', {name: 'Save mapping'})).toBeDisabled()
  })

  it('shows copyable webhook setup and reveals a rotated one-time token', async () => {
    const user = userEvent.setup()
    const writeText = vi.fn().mockResolvedValue(undefined)
    Object.defineProperty(globalThis.navigator, 'clipboard', {
      configurable: true,
      value: {writeText},
    })
    connectRevenueCat()
    apiMock.rotateConnectorWebhookToken.mockResolvedValue({
      ...rcInstallation,
      webhookToken: 'new-one-time-token',
    })

    renderWithClient(<ConnectorsSettings />)

    await user.click(await screen.findByRole('button', {name: 'Manage'}))
    await user.click(await screen.findByRole('tab', {name: 'Webhook'}))

    expect(
      await screen.findByDisplayValue('https://moneat.test/api/connectors/revenuecat/inst-1/webhook')
    ).toBeInTheDocument()
    expect(screen.getByRole('button', {name: 'Copy Webhook URL'})).toBeInTheDocument()
    await user.click(screen.getByRole('button', {name: 'Copy Webhook URL'}))
    expect(writeText).toHaveBeenCalledWith(
      'https://moneat.test/api/connectors/revenuecat/inst-1/webhook'
    )
    writeText.mockRejectedValueOnce(new Error('denied'))
    await user.click(screen.getByRole('button', {name: 'Copy event types'}))
    await waitFor(() => expect(writeText).toHaveBeenCalledTimes(2))
    expect(writeText).toHaveBeenLastCalledWith('INITIAL_PURCHASE\nRENEWAL')
    // Only a token prefix is stored, so the user is told to rotate to reveal one.
    expect(screen.getByText(/Only the token prefix is stored/i)).toBeInTheDocument()

    await user.click(screen.getByRole('button', {name: 'Rotate token'}))
    await user.click(screen.getByRole('button', {name: 'Rotate & reveal'}))

    // The rotated token surfaces (header field + one-time reveal panel) exactly once.
    const revealed = await screen.findAllByDisplayValue('Bearer new-one-time-token')
    expect(revealed.length).toBeGreaterThanOrEqual(1)
    expect(screen.getByText(/shown only once/i)).toBeInTheDocument()
  })
})

function bindingFor(externalResourceId: string, localResourceId: string) {
  return {
    id: `binding-${externalResourceId}`,
    installationId: 'inst-1',
    externalProjectId: 'proj_abc',
    externalResourceType: 'revenuecat_app',
    externalResourceId,
    localResourceType: 'project',
    localResourceId,
    status: 'active',
    bindingVersion: 1,
    effectiveFrom: '2026-06-15T10:00:00Z',
    effectiveTo: null,
  }
}
