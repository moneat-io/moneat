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
import {fireEvent, render, screen, waitFor} from '@testing-library/react'
import {afterAll, beforeAll, beforeEach, describe, expect, it, vi} from 'vitest'

import {SsoTab} from '@/components/SsoSettings'

const {mockApi, mockToast} = vi.hoisted(() => ({
  mockApi: {
    configureSso: vi.fn(),
    deleteSsoConfig: vi.fn(),
    getSsoConfig: vi.fn(),
    verifySsoDomain: vi.fn(),
  },
  mockToast: vi.fn(),
}))

vi.mock('@/lib/api', () => ({api: mockApi}))
vi.mock('@/hooks/useToast', () => ({useToast: () => ({toast: mockToast})}))

beforeAll(() => {
  vi.stubGlobal(
    'ResizeObserver',
    class ResizeObserver {
      observe() {}
      unobserve() {}
      disconnect() {}
    },
  )
})

afterAll(() => {
  vi.unstubAllGlobals()
})

const baseConfig = {
  id: 'sso-config-1',
  organizationId: 'org-7',
  providerType: 'oidc',
  isEnabled: true,
  idpEntityId: null,
  idpSsoUrl: null,
  idpCertificate: null,
  spEntityId: null,
  spAcsUrl: null,
  oidcIssuerUrl: 'https://idp.example.com',
  oidcClientId: 'client-id',
  hasClientSecret: true,
  emailDomain: 'example.com',
  emailDomainVerified: false,
  emailDomainVerificationRecordName: '_moneat-sso.example.com',
  emailDomainVerificationToken: 'verify-token',
  requireSso: false,
  createdAt: '2026-01-01T00:00:00Z',
  updatedAt: '2026-01-01T00:00:00Z',
}

function renderSsoTab(options: {canConfigure?: boolean; hasSamlModule?: boolean} = {}) {
  const queryClient = new QueryClient({
    defaultOptions: {queries: {retry: false}, mutations: {retry: false}},
  })

  return render(
    <QueryClientProvider client={queryClient}>
      <SsoTab
        organizationId="org-7"
        canConfigure={options.canConfigure ?? true}
        hasSamlModule={options.hasSamlModule ?? true}
      />
    </QueryClientProvider>,
  )
}

describe('SsoSettings', () => {
  beforeEach(() => {
    vi.clearAllMocks()
    mockApi.configureSso.mockResolvedValue(baseConfig)
    mockApi.deleteSsoConfig.mockResolvedValue({message: 'deleted'})
    mockApi.verifySsoDomain.mockResolvedValue({verified: true})
  })

  it('renders pending domain verification details and verifies the domain', async () => {
    mockApi.getSsoConfig.mockResolvedValue(baseConfig)
    renderSsoTab()

    expect(await screen.findByText('Domain Verification')).toBeInTheDocument()
    expect(screen.getByText('Pending')).toBeInTheDocument()
    expect(screen.getByDisplayValue('_moneat-sso.example.com')).toBeInTheDocument()
    expect(screen.getByDisplayValue('moneat-sso=verify-token')).toBeInTheDocument()

    fireEvent.click(screen.getByRole('button', {name: /verify/i}))

    await waitFor(() => expect(mockApi.verifySsoDomain).toHaveBeenCalledWith('org-7'))
    expect(mockToast).toHaveBeenCalledWith(expect.objectContaining({title: 'SSO domain verified'}))
  })

  it('saves oidc configuration changes', async () => {
    mockApi.getSsoConfig.mockResolvedValue(null)
    renderSsoTab()

    fireEvent.change(await screen.findByLabelText(/issuer url/i), {
      target: {value: 'https://idp.example.com'},
    })
    fireEvent.change(screen.getByLabelText(/client id/i), {target: {value: 'client-id'}})
    fireEvent.change(screen.getByLabelText(/client secret/i), {target: {value: 'secret'}})
    fireEvent.change(screen.getByLabelText(/email domain/i), {target: {value: 'example.com'}})
    fireEvent.click(screen.getByRole('button', {name: /save configuration/i}))

    await waitFor(() =>
      expect(mockApi.configureSso).toHaveBeenCalledWith(
        'org-7',
        expect.objectContaining({
          providerType: 'oidc',
          oidcIssuerUrl: 'https://idp.example.com',
          oidcClientId: 'client-id',
          oidcClientSecret: 'secret',
          emailDomain: 'example.com',
        }),
      ),
    )
  })

  it('deletes an existing configuration and resets local form state', async () => {
    mockApi.getSsoConfig.mockResolvedValue(baseConfig)
    renderSsoTab()

    fireEvent.click(await screen.findByRole('button', {name: /delete sso configuration/i}))

    await waitFor(() => expect(mockApi.deleteSsoConfig).toHaveBeenCalledWith('org-7'))
    expect(mockToast).toHaveBeenCalledWith(expect.objectContaining({title: 'SSO configuration deleted'}))
  })

  it('shows a destructive toast when domain verification fails', async () => {
    mockApi.getSsoConfig.mockResolvedValue(baseConfig)
    mockApi.verifySsoDomain.mockRejectedValue(new Error('TXT record missing'))
    renderSsoTab()

    fireEvent.click(await screen.findByRole('button', {name: /verify/i}))

    await waitFor(() =>
      expect(mockToast).toHaveBeenCalledWith(
        expect.objectContaining({
          title: 'Failed to verify SSO domain',
          variant: 'destructive',
        }),
      ),
    )
  })
})
