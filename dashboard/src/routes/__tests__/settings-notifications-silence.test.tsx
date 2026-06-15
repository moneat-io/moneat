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
import {afterEach, beforeEach, describe, expect, it, vi} from 'vitest'

import {NotificationsTab, SilencePeriodsTab} from '../settings'

const apiMock = vi.hoisted(() => ({
  isAuthenticated: vi.fn(() => true),
  getNotificationPreferences: vi.fn(),
  updateNotificationPreferences: vi.fn(),
  updateProjectNotificationPreferences: vi.fn(),
  deleteProjectNotificationPreferences: vi.fn(),
  getPushDevices: vi.fn(),
  getOnCallContact: vi.fn(),
  updateOnCallContact: vi.fn(),
  deleteOnCallContact: vi.fn(),
  getSilencePeriods: vi.fn(),
  createSilencePeriod: vi.fn(),
  deleteSilencePeriod: vi.fn(),
}))

vi.mock('@/lib/api', () => ({api: apiMock}))
vi.mock('@/hooks/useAuth', () => ({
  useAuth: () => ({
    user: {
      email: 'ada@example.com',
      emailVerified: true,
      orgId: 'org-1',
    },
  }),
}))
vi.mock('@/hooks/useToast', () => ({useToast: () => ({toast: vi.fn()})}))
vi.mock('@/hooks/useTimezone', () => ({
  useTimezone: () => ({
    timezone: 'UTC',
    updateTimezone: vi.fn(),
  }),
}))
vi.mock('@tanstack/react-router', () => ({
  createFileRoute: () => (options: unknown) => ({options, useSearch: () => ({tab: 'general'})}),
  Link: ({to, children, ...props}: {to: string; children: React.ReactNode}) => (
    <a href={to} {...props}>
      {children}
    </a>
  ),
  redirect: vi.fn(),
  useNavigate: () => vi.fn(),
  useSearch: () => ({}),
}))

function renderWithClient(ui: React.ReactElement) {
  const queryClient = new QueryClient({
    defaultOptions: {queries: {retry: false}, mutations: {retry: false}},
  })
  return render(<QueryClientProvider client={queryClient}>{ui}</QueryClientProvider>)
}

describe('settings notification and silence tabs', () => {
  let dateNowSpy: ReturnType<typeof vi.spyOn>

  beforeEach(() => {
    vi.clearAllMocks()
    dateNowSpy = vi.spyOn(Date, 'now').mockReturnValue(new Date('2026-06-15T13:00:00Z').getTime())

    apiMock.getNotificationPreferences.mockResolvedValue({
      global: {
        weeklySummary: true,
        alertFrequencyMinutes: 30,
        emailEnabled: true,
        pushEnabled: false,
      },
      projects: [
        {
          projectId: 'project-1',
          projectName: 'Checkout',
          issueAlerts: true,
          errorAlerts: false,
          weeklySummary: true,
          alertFrequencyMinutes: 60,
        },
      ],
    })
    apiMock.getPushDevices.mockResolvedValue([
      {id: 'device-1', label: 'iPhone', platform: 'IOS', lastActiveAt: '2026-06-15T12:00:00Z'},
      {id: 'device-2', label: 'iPad', platform: 'IOS', lastActiveAt: '2026-06-15T12:10:00Z'},
    ])
    apiMock.getOnCallContact.mockResolvedValue({
      phoneNumber: '+15551234567',
      onCallPhoneOptIn: false,
      onCallPhoneConsentedAt: null,
      onCallPhoneConsentVersion: null,
    })
    apiMock.updateNotificationPreferences.mockResolvedValue(undefined)
    apiMock.updateProjectNotificationPreferences.mockResolvedValue(undefined)
    apiMock.deleteProjectNotificationPreferences.mockResolvedValue(undefined)
    apiMock.updateOnCallContact.mockResolvedValue(undefined)
    apiMock.deleteOnCallContact.mockResolvedValue(undefined)

    const now = Date.now()
    apiMock.getSilencePeriods.mockResolvedValue([
      {
        id: 'active-1',
        organizationId: 'org-1',
        reason: 'Deploy',
        startsAt: now - 5 * 60 * 1000,
        endsAt: now + 25 * 60 * 1000,
        createdBy: 'user-1',
        createdAt: now - 5 * 60 * 1000,
      },
      {
        id: 'scheduled-1',
        organizationId: 'org-1',
        reason: 'Maintenance',
        startsAt: now + 60 * 60 * 1000,
        endsAt: now + 120 * 60 * 1000,
        createdBy: 'user-1',
        createdAt: now,
      },
    ])
    apiMock.createSilencePeriod.mockResolvedValue({})
    apiMock.deleteSilencePeriod.mockResolvedValue(undefined)
  })

  afterEach(() => {
    dateNowSpy.mockRestore()
  })

  it('renders delivery channel state and per-service overrides', async () => {
    renderWithClient(<NotificationsTab />)

    expect(await screen.findByText('Notifications')).toBeInTheDocument()
    expect(screen.getByText('ada@example.com · verified')).toBeInTheDocument()
    expect(screen.getByText(/2 registered devices/)).toBeInTheDocument()
    expect(screen.getByText('Checkout')).toBeInTheDocument()
    expect(screen.getByText('1h')).toBeInTheDocument()
    expect(screen.getByText(/Phone number saved but not opted in yet/)).toBeInTheDocument()
  })

  it('renders active and scheduled silence periods and can end active periods', async () => {
    const user = userEvent.setup()
    renderWithClient(<SilencePeriodsTab />)

    expect(await screen.findByText(/Alerts are currently silenced/)).toBeInTheDocument()
    expect(screen.getByText('Deploy')).toBeInTheDocument()
    expect(screen.getByText('Maintenance')).toBeInTheDocument()

    await user.click(screen.getByRole('button', {name: 'End now'}))

    await waitFor(() => expect(apiMock.deleteSilencePeriod).toHaveBeenCalledWith('active-1'))
  })
})
