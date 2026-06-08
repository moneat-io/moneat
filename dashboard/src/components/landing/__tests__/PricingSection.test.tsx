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

import type {ReactNode} from 'react'
import {QueryClient, QueryClientProvider} from '@tanstack/react-query'
import {fireEvent, render, screen} from '@testing-library/react'
import {beforeEach, describe, expect, it, vi} from 'vitest'

import {PricingSection} from '@/components/landing/PricingSection'

const {mockApi} = vi.hoisted(() => ({
  mockApi: {
    getBillingPlans: vi.fn(),
    isAuthenticated: vi.fn(() => false),
    createBillingCheckoutSession: vi.fn(),
    createSalesInquiry: vi.fn(),
  },
}))

vi.mock('@/lib/api', () => ({api: mockApi}))
vi.mock('@/hooks/useToast', () => ({useToast: () => ({toast: vi.fn()})}))
vi.mock('@tanstack/react-router', async (importOriginal) => {
  const actual = await importOriginal<typeof import('@tanstack/react-router')>()
  return {
    ...actual,
    Link: ({to, children}: {to?: unknown; children: ReactNode}) => (
      <a href={typeof to === 'string' ? to : '#'}>{children}</a>
    ),
  }
})

const GB = 1024 * 1024 * 1024

function makeTier(tierName: string, monthlyPriceCents: number) {
  return {
    tier: {
      tierName,
      monthlyPriceCents,
      yearlyPriceCents: monthlyPriceCents * 10,
      monthlyGbLimit: 50 * GB,
      retentionDays: 30,
      maxProjects: null,
      maxSystems: 10,
      monitorIntervalSeconds: 30,
      sessionReplayEnabled: true,
      statusPagesEnabled: true,
      statusPageCustomDomainEnabled: true,
      slackEnabled: true,
      incidentIoEnabled: true,
      samlEnabled: false,
      oidcEnabled: false,
      prioritySupportEnabled: false,
      slaEnabled: false,
      customRetentionEnabled: false,
    },
    trialDays: 14,
  }
}

function renderSection() {
  const queryClient = new QueryClient({
    defaultOptions: {queries: {retry: false}, mutations: {retry: false}},
  })
  render(
    <QueryClientProvider client={queryClient}>
      <PricingSection />
    </QueryClientProvider>,
  )
}

describe('PricingSection', () => {
  beforeEach(() => {
    vi.clearAllMocks()
    mockApi.isAuthenticated.mockReturnValue(false)
    mockApi.getBillingPlans.mockResolvedValue({
      plans: [
        makeTier('FREE', 0),
        makeTier('PRO', 2900),
        makeTier('TEAM', 7900),
        makeTier('BUSINESS', 19900),
      ],
      stripeEnabled: true,
      publishableKey: 'pk_test',
    })
  })

  it('hides the Business tier and shows an Enterprise contact card', async () => {
    renderSection()

    expect(await screen.findByText('Pro')).toBeInTheDocument()
    expect(screen.getByText('Team')).toBeInTheDocument()
    expect(screen.getByText('Enterprise')).toBeInTheDocument()
    expect(screen.queryByText('Business')).not.toBeInTheDocument()
    expect(screen.queryByText('$199')).not.toBeInTheDocument()
  })

  it('opens the sales contact dialog from the Enterprise card', async () => {
    renderSection()

    const contactButton = await screen.findByRole('button', {name: /contact sales/i})
    fireEvent.click(contactButton)

    expect(await screen.findByText(/talk to sales/i)).toBeInTheDocument()
    expect(screen.getByRole('button', {name: /send message/i})).toBeInTheDocument()
  })
})
