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

import {afterAll, beforeAll, beforeEach, describe, expect, it, vi} from 'vitest'
import {screen, waitFor} from '@testing-library/react'
import userEvent from '@testing-library/user-event'
import type {OnCallSchedule, SlackInstallationSummary} from '@/lib/api/types'
import {clearAuthStorage, renderRoute} from '@/test/utils'

const {mockApi, mockToast} = vi.hoisted(() => ({
  mockApi: {
    getOnCallSchedules: vi.fn(),
    getOrgMembers: vi.fn(),
    getSlackInstallations: vi.fn(),
    getSlackInstallationUsergroups: vi.fn(),
    setScheduleSlackUsergroup: vi.fn(),
    removeScheduleSlackUsergroup: vi.fn(),
    createOnCallSchedule: vi.fn(),
    updateOnCallSchedule: vi.fn(),
    deleteOnCallSchedule: vi.fn(),
  },
  mockToast: vi.fn(),
}))

vi.mock('@/lib/api', () => ({api: mockApi}))
vi.mock('@/hooks/useToast', () => ({useToast: () => ({toast: mockToast})}))
vi.mock('@tanstack/react-router', () => ({
  createFileRoute: () => (options: Record<string, unknown>) => options,
}))

import {Route} from '../on-call.schedules'

const SCHEDULE_ID = '22222222-2222-4222-8222-222222222222'

function seedSchedule(overrides: Partial<OnCallSchedule> = {}): OnCallSchedule {
  return {
    id: SCHEDULE_ID,
    organizationId: 'org-1',
    name: 'Payments Primary',
    rotationType: 'WEEKLY',
    handoffTime: '09:00:00',
    timezone: 'UTC',
    createdAt: '2026-08-01T00:00:00Z',
    updatedAt: '2026-08-01T00:00:00Z',
    participants: [],
    overrides: [],
    ...overrides,
  }
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
    grantedScopes: [],
    enabledCapabilities: ['on_call_usergroups'],
    missingScopes: [],
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

const USERGROUPS = [
  {id: 'ug1', handle: 'payments-oncall', name: 'Payments On-Call'},
  {id: 'ug2', handle: 'payments-secondary', name: 'Payments Secondary'},
]

async function expandSchedule(user: ReturnType<typeof userEvent.setup>) {
  await user.click(await screen.findByRole('button', {name: 'Expand Payments Primary details'}))
}

describe('On-call schedules — Slack workspace context', () => {
  const originalResizeObserver = globalThis.ResizeObserver
  const originalScrollIntoView = globalThis.HTMLElement.prototype.scrollIntoView
  const originalReleasePointerCapture = globalThis.HTMLElement.prototype.releasePointerCapture
  const originalHasPointerCapture = globalThis.HTMLElement.prototype.hasPointerCapture

  beforeAll(() => {
    globalThis.ResizeObserver = class ResizeObserver {
      observe() {}
      unobserve() {}
      disconnect() {}
    }
    globalThis.HTMLElement.prototype.scrollIntoView = vi.fn()
    globalThis.HTMLElement.prototype.releasePointerCapture = vi.fn()
    globalThis.HTMLElement.prototype.hasPointerCapture = vi.fn(() => false)
  })

  afterAll(() => {
    globalThis.ResizeObserver = originalResizeObserver
    globalThis.HTMLElement.prototype.scrollIntoView = originalScrollIntoView
    globalThis.HTMLElement.prototype.releasePointerCapture = originalReleasePointerCapture
    globalThis.HTMLElement.prototype.hasPointerCapture = originalHasPointerCapture
  })

  beforeEach(() => {
    vi.clearAllMocks()
    clearAuthStorage()
    mockApi.getOrgMembers.mockResolvedValue({members: [], pendingInvitations: []})
    mockApi.getSlackInstallations.mockResolvedValue([])
    mockApi.getSlackInstallationUsergroups.mockResolvedValue(USERGROUPS)
    mockApi.setScheduleSlackUsergroup.mockResolvedValue(undefined)
    mockApi.removeScheduleSlackUsergroup.mockResolvedValue(undefined)
  })

  it('auto-selects the only enabled workspace and loads its user groups', async () => {
    const user = userEvent.setup()
    mockApi.getOnCallSchedules.mockResolvedValue([seedSchedule()])
    mockApi.getSlackInstallations.mockResolvedValue([installation()])

    renderRoute(Route)
    await expandSchedule(user)

    // The scoped user-group query runs for the single enabled workspace…
    await waitFor(() =>
      expect(mockApi.getSlackInstallationUsergroups).toHaveBeenCalledWith('inst-default')
    )
    // …and that workspace stays visible in its selector.
    expect(screen.getByRole('combobox', {name: 'Slack workspace'})).toHaveTextContent('Acme HQ')
  })

  it('sends the workspace installation id when linking a user group', async () => {
    const user = userEvent.setup()
    mockApi.getOnCallSchedules.mockResolvedValue([seedSchedule()])
    mockApi.getSlackInstallations.mockResolvedValue([installation()])

    renderRoute(Route)
    await expandSchedule(user)

    const usergroupTrigger = screen.getByRole('combobox', {name: 'Slack user group'})
    await waitFor(() => expect(usergroupTrigger).toBeEnabled())
    await user.click(usergroupTrigger)
    await user.click(await screen.findByRole('option', {name: /payments-oncall/}))

    await waitFor(() =>
      expect(mockApi.setScheduleSlackUsergroup).toHaveBeenCalledWith(
        SCHEDULE_ID,
        'ug1',
        'payments-oncall',
        'inst-default'
      )
    )
  })

  it('requires choosing a workspace first when several are enabled', async () => {
    const user = userEvent.setup()
    mockApi.getOnCallSchedules.mockResolvedValue([seedSchedule()])
    mockApi.getSlackInstallations.mockResolvedValue([
      installation(),
      installation({id: 'inst-grid', teamId: 'T2', teamName: 'Globex', isDefault: false}),
    ])

    renderRoute(Route)
    await expandSchedule(user)

    // Nothing is scoped until the user picks a workspace.
    expect(mockApi.getSlackInstallationUsergroups).not.toHaveBeenCalled()
    expect(screen.getByRole('combobox', {name: 'Slack user group'})).toBeDisabled()

    await user.click(screen.getByRole('combobox', {name: 'Slack workspace'}))
    await user.click(await screen.findByRole('option', {name: 'Globex'}))

    await waitFor(() =>
      expect(mockApi.getSlackInstallationUsergroups).toHaveBeenCalledWith('inst-grid')
    )
  })

  it('shows the workspace name alongside an existing mapping', async () => {
    const user = userEvent.setup()
    mockApi.getOnCallSchedules.mockResolvedValue([
      seedSchedule({
        slackUsergroupId: 'ug1',
        slackUsergroupHandle: 'payments-oncall',
        slackInstallationId: 'inst-default',
      }),
    ])
    mockApi.getSlackInstallations.mockResolvedValue([installation()])

    renderRoute(Route)
    await expandSchedule(user)

    expect(await screen.findByText('@payments-oncall')).toBeInTheDocument()
    expect(screen.getByText(/Acme HQ/)).toBeInTheDocument()
    expect(screen.getByText('Auto-syncing the current on-call user to this group.')).toBeInTheDocument()
    // An existing mapping is not re-fetched as a picker.
    expect(mockApi.getSlackInstallationUsergroups).not.toHaveBeenCalled()
  })

  it('flags a mapping whose workspace was removed', async () => {
    const user = userEvent.setup()
    mockApi.getOnCallSchedules.mockResolvedValue([
      seedSchedule({
        slackUsergroupId: 'ug1',
        slackUsergroupHandle: 'payments-oncall',
        slackInstallationId: 'inst-gone',
      }),
    ])
    mockApi.getSlackInstallations.mockResolvedValue([])

    renderRoute(Route)
    await expandSchedule(user)

    expect(await screen.findByText('Workspace no longer connected')).toBeInTheDocument()
    expect(screen.getByText('@payments-oncall')).toBeInTheDocument()
  })

  it('flags a mapping whose workspace has delivery disabled', async () => {
    const user = userEvent.setup()
    mockApi.getOnCallSchedules.mockResolvedValue([
      seedSchedule({
        slackUsergroupId: 'ug1',
        slackUsergroupHandle: 'payments-oncall',
        slackInstallationId: 'inst-default',
      }),
    ])
    mockApi.getSlackInstallations.mockResolvedValue([installation({enabled: false})])

    renderRoute(Route)
    await expandSchedule(user)

    expect(await screen.findByText(/delivery disabled/)).toBeInTheDocument()
    expect(screen.getByText(/Acme HQ/)).toBeInTheDocument()
  })

  it('removes an existing mapping', async () => {
    const user = userEvent.setup()
    mockApi.getOnCallSchedules.mockResolvedValue([
      seedSchedule({
        slackUsergroupId: 'ug1',
        slackUsergroupHandle: 'payments-oncall',
        slackInstallationId: 'inst-default',
      }),
    ])
    mockApi.getSlackInstallations.mockResolvedValue([installation()])

    renderRoute(Route)
    await expandSchedule(user)

    await user.click(
      await screen.findByRole('button', {name: 'Remove Slack user group from Payments Primary'})
    )
    await waitFor(() =>
      expect(mockApi.removeScheduleSlackUsergroup).toHaveBeenCalledWith(SCHEDULE_ID)
    )
  })

  it('explains when every connected workspace is disabled', async () => {
    const user = userEvent.setup()
    mockApi.getOnCallSchedules.mockResolvedValue([seedSchedule()])
    mockApi.getSlackInstallations.mockResolvedValue([installation({enabled: false})])

    renderRoute(Route)
    await expandSchedule(user)

    expect(
      await screen.findByText(
        'All connected Slack workspaces are disabled. Enable one in Settings to sync on-call.'
      )
    ).toBeInTheDocument()
    expect(mockApi.getSlackInstallationUsergroups).not.toHaveBeenCalled()
  })

  it('shows an accessible loading state while workspaces load', async () => {
    const user = userEvent.setup()
    mockApi.getOnCallSchedules.mockResolvedValue([seedSchedule()])
    mockApi.getSlackInstallations.mockReturnValue(new Promise(() => {}))

    renderRoute(Route)
    await expandSchedule(user)

    expect(await screen.findByText('Loading Slack workspaces…')).toBeInTheDocument()
  })

  it('hides the Slack section for a schedule with no workspaces and no mapping', async () => {
    const user = userEvent.setup()
    mockApi.getOnCallSchedules.mockResolvedValue([seedSchedule()])
    mockApi.getSlackInstallations.mockResolvedValue([])

    renderRoute(Route)
    await expandSchedule(user)

    // Rotation details render, but the Slack sub-section is absent.
    expect(await screen.findByText('Rotation Order')).toBeInTheDocument()
    expect(screen.queryByText('Slack user group')).not.toBeInTheDocument()
  })

  it('preserves schedule deletion behavior', async () => {
    const user = userEvent.setup()
    vi.spyOn(globalThis.window, 'confirm').mockReturnValue(true)
    mockApi.getOnCallSchedules.mockResolvedValue([seedSchedule()])
    mockApi.deleteOnCallSchedule.mockResolvedValue(undefined)

    renderRoute(Route)

    await user.click(await screen.findByRole('button', {name: 'Delete Payments Primary'}))
    await waitFor(() => expect(mockApi.deleteOnCallSchedule).toHaveBeenCalledWith(SCHEDULE_ID))
  })
})
