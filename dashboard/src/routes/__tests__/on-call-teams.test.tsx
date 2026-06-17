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

import React from 'react'
import {afterEach, beforeAll, beforeEach, describe, expect, it, vi} from 'vitest'
import {screen, waitFor, within} from '@testing-library/react'
import userEvent from '@testing-library/user-event'
import {clearAuthStorage, renderRoute} from '@/test/utils'

const {mockApi, mockToast} = vi.hoisted(() => ({
  mockApi: {
    getBillingUsage: vi.fn(),
    getOrganizationTeams: vi.fn(),
    getOrgMembers: vi.fn(),
    getOnCallSchedules: vi.fn(),
    getEscalationPolicies: vi.fn(),
    createOrganizationTeam: vi.fn(),
    updateOrganizationTeam: vi.fn(),
    deleteOrganizationTeam: vi.fn(),
  },
  mockToast: vi.fn(),
}))

vi.mock('@/lib/api', () => ({api: mockApi}))

vi.mock('@/hooks/useToast', () => ({
  useToast: () => ({toast: mockToast}),
}))

vi.mock('@tanstack/react-router', () => ({
  createFileRoute: () => (options: Record<string, unknown>) => ({...options, options}),
  Link: ({children, to, ...props}: {children: React.ReactNode; to?: string}) =>
    React.createElement('a', {href: typeof to === 'string' ? to : '#', ...props}, children),
}))

beforeAll(() => {
  globalThis.ResizeObserver = class {
    disconnect() {}
    observe() {}
    unobserve() {}
  }
})

import {Route as TeamsRoute} from '../on-call.teams'

const SCHEDULE_ID = '22222222-2222-4222-8222-222222222222'
const POLICY_ID = '33333333-3333-4333-8333-333333333333'

function seedSupportingData() {
  mockApi.getOrgMembers.mockResolvedValue({
    members: [
      {userId: 'u1', email: 'dana@example.com', name: 'Dana Whitfield', role: 'admin'},
      {userId: 'u2', email: 'theo@example.com', name: 'Theo Park', role: 'member'},
    ],
    pendingInvitations: [],
  })
  mockApi.getOnCallSchedules.mockResolvedValue([
    {id: SCHEDULE_ID, name: 'Payments Primary', rotationType: 'WEEKLY', timezone: 'UTC', participants: [], overrides: []},
  ])
  mockApi.getEscalationPolicies.mockResolvedValue([
    {id: POLICY_ID, name: 'Sev1 Policy', repeatCount: 0, steps: []},
  ])
}

describe('On-call Teams view', () => {
  beforeEach(() => {
    vi.clearAllMocks()
    clearAuthStorage()
  })

  afterEach(() => {
    vi.restoreAllMocks()
    vi.unstubAllGlobals()
  })

  it('shows the locked upgrade state and skips loading teams when not entitled', async () => {
    mockApi.getBillingUsage.mockResolvedValue({teamsEnabled: false})

    renderRoute(TeamsRoute)

    expect(await screen.findByText('Teams is a Team plan feature')).toBeInTheDocument()
    const upgrade = screen.getByRole('link', {name: 'Upgrade plan'})
    expect(upgrade).toHaveAttribute('href', '/settings')
    expect(mockApi.getOrganizationTeams).not.toHaveBeenCalled()
  })

  it('lists teams with the current on-call person and on-call defaults', async () => {
    mockApi.getBillingUsage.mockResolvedValue({teamsEnabled: true})
    seedSupportingData()
    mockApi.getOrganizationTeams.mockResolvedValue([
      {
        id: 'team-1',
        name: 'Payments',
        slug: 'payments',
        description: 'Owns checkout and billing',
        slack: '#payments-oncall',
        repo: 'moneat-io/payments',
        onCallScheduleId: SCHEDULE_ID,
        escalationPolicyId: POLICY_ID,
        currentOnCall: {userId: 'u1', userName: 'Dana Whitfield'},
        members: [
          {userId: 'u1', email: 'dana@example.com', name: 'Dana Whitfield'},
        ],
      },
    ])

    renderRoute(TeamsRoute)

    expect(await screen.findByText('Payments')).toBeInTheDocument()
    expect(screen.getByText('Owns checkout and billing')).toBeInTheDocument()
    expect(screen.getByText('Dana Whitfield')).toBeInTheDocument()
    expect(screen.getByText('Payments Primary')).toBeInTheDocument()
    expect(screen.getByText('Sev1 Policy')).toBeInTheDocument()
    expect(screen.getByText('payments-oncall')).toBeInTheDocument()
  })

  it('creates a team from the editor dialog', async () => {
    const user = userEvent.setup()
    mockApi.getBillingUsage.mockResolvedValue({teamsEnabled: true})
    seedSupportingData()
    mockApi.getOrganizationTeams.mockResolvedValue([])
    mockApi.createOrganizationTeam.mockResolvedValue({id: 'team-new', name: 'Platform', slug: 'platform', members: []})

    renderRoute(TeamsRoute)

    await user.click(await screen.findByRole('button', {name: 'Create your first team'}))

    const dialog = await screen.findByRole('dialog')
    await user.type(within(dialog).getByLabelText('Team name'), 'Platform')
    await user.click(within(dialog).getByRole('button', {name: 'Create team'}))

    await waitFor(() => expect(mockApi.createOrganizationTeam).toHaveBeenCalledTimes(1))
    expect(mockApi.createOrganizationTeam.mock.calls[0][0]).toMatchObject({name: 'Platform'})
  })

  it('updates an existing team from the editor dialog', async () => {
    const user = userEvent.setup()
    mockApi.getBillingUsage.mockResolvedValue({teamsEnabled: true})
    seedSupportingData()
    mockApi.getOrganizationTeams.mockResolvedValue([
      {
        id: 'team-1',
        name: 'Payments',
        slug: 'payments',
        description: 'Owns checkout',
        slack: '#payments-oncall',
        repo: 'moneat-io/payments',
        onCallScheduleId: SCHEDULE_ID,
        escalationPolicyId: POLICY_ID,
        currentOnCall: null,
        members: [
          {userId: 'u1', email: 'dana@example.com', name: 'Dana Whitfield'},
        ],
      },
    ])
    mockApi.updateOrganizationTeam.mockResolvedValue({
      id: 'team-1',
      name: 'Payments Core',
      slug: 'payments',
      members: [],
    })

    renderRoute(TeamsRoute)

    await user.click(await screen.findByRole('button', {name: 'Edit Payments'}))

    const dialog = await screen.findByRole('dialog')
    await user.clear(within(dialog).getByLabelText('Team name'))
    await user.type(within(dialog).getByLabelText('Team name'), 'Payments Core')
    await user.clear(within(dialog).getByLabelText('Slack channel'))
    await user.click(within(dialog).getByRole('button', {name: 'Save changes'}))

    await waitFor(() => expect(mockApi.updateOrganizationTeam).toHaveBeenCalledTimes(1))
    expect(mockApi.updateOrganizationTeam).toHaveBeenCalledWith('team-1', {
      name: 'Payments Core',
      description: 'Owns checkout',
      slack: null,
      repo: 'moneat-io/payments',
      onCallScheduleId: SCHEDULE_ID,
      escalationPolicyId: POLICY_ID,
      memberIds: ['u1'],
    })
    expect(mockToast).toHaveBeenCalledWith({
      title: 'Team updated',
      description: 'Team changes have been saved.',
    })
  })

  it('keeps a team when delete confirmation is canceled', async () => {
    const user = userEvent.setup()
    vi.spyOn(globalThis.window, 'confirm').mockReturnValue(false)
    mockApi.getBillingUsage.mockResolvedValue({teamsEnabled: true})
    seedSupportingData()
    mockApi.getOrganizationTeams.mockResolvedValue([
      {id: 'team-1', name: 'Payments', slug: 'payments', members: []},
    ])

    renderRoute(TeamsRoute)

    await user.click(await screen.findByRole('button', {name: 'Delete Payments'}))

    expect(mockApi.deleteOrganizationTeam).not.toHaveBeenCalled()
  })

  it('deletes a team after confirmation', async () => {
    const user = userEvent.setup()
    vi.spyOn(globalThis.window, 'confirm').mockReturnValue(true)
    mockApi.getBillingUsage.mockResolvedValue({teamsEnabled: true})
    seedSupportingData()
    mockApi.getOrganizationTeams.mockResolvedValue([
      {id: 'team-1', name: 'Payments', slug: 'payments', members: []},
    ])
    mockApi.deleteOrganizationTeam.mockResolvedValue(undefined)

    renderRoute(TeamsRoute)

    await user.click(await screen.findByRole('button', {name: 'Delete Payments'}))

    await waitFor(() => expect(mockApi.deleteOrganizationTeam).toHaveBeenCalledWith('team-1'))
  })
})
