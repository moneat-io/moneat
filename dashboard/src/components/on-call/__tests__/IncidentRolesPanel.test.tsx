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

import {beforeEach, describe, expect, it, vi} from 'vitest'
import {fireEvent, screen, waitFor} from '@testing-library/react'
import {renderWithQueryClient} from '@/test/utils'
import {IncidentRolesPanel} from '../IncidentRolesPanel'

const SELF = 'a0000000-0000-4000-8000-000000000001'
const GRACE = 'a0000000-0000-4000-8000-000000000002'
const LIN = 'a0000000-0000-4000-8000-000000000003'
const INCIDENT_ID = 'b0000000-0000-4000-8000-000000000009'

const {mockApi} = vi.hoisted(() => ({
  mockApi: {
    getIncidentRoleAssignments: vi.fn(),
    getIncidentParticipants: vi.fn(),
    getIncidentRoles: vi.fn(),
    getOrgMembers: vi.fn(),
    claimIncidentRole: vi.fn(),
    unassignIncidentRole: vi.fn(),
    assignIncidentRole: vi.fn(),
    handoverIncidentRole: vi.fn(),
    joinIncident: vi.fn(),
    observeIncident: vi.fn(),
    leaveIncident: vi.fn(),
  },
}))

vi.mock('@/lib/api', () => ({api: mockApi}))
vi.mock('@/hooks/useAuth', () => ({useAuth: () => ({user: {id: SELF}})}))

const commander = {
  id: 'role-cmd',
  key: 'incident-commander',
  version: 1,
  name: 'Incident Commander',
  responsibilities: ['Coordinate the response'],
  privateInstructions: 'SECRET COMMANDER PLAYBOOK',
  required: true,
  default: true,
}
const scribe = {
  id: 'role-scribe',
  key: 'scribe',
  version: 1,
  name: 'Scribe',
  responsibilities: ['Record the timeline'],
  required: false,
  default: false,
}

describe('IncidentRolesPanel', () => {
  beforeEach(() => {
    vi.clearAllMocks()
    mockApi.getIncidentRoles.mockResolvedValue([commander, scribe])
    mockApi.getIncidentRoleAssignments.mockResolvedValue([
      {id: 'as1', role: commander, assigneeUserId: GRACE, assignedByUserId: SELF, assignedAt: '2026-06-05T00:00:00Z'},
    ])
    mockApi.getIncidentParticipants.mockResolvedValue([
      {id: 'p1', userId: LIN, type: 'PARTICIPANT', joinedByUserId: SELF, joinedAt: '2026-06-05T00:00:00Z'},
      {id: 'p2', userId: GRACE, type: 'OBSERVER', joinedByUserId: SELF, joinedAt: '2026-06-05T00:00:00Z'},
    ])
    mockApi.getOrgMembers.mockResolvedValue({
      members: [
        {userId: SELF, email: 'ada@x.io', name: 'Ada'},
        {userId: GRACE, email: 'grace@x.io', name: 'Grace'},
        {userId: LIN, email: 'lin@x.io', name: 'Lin'},
      ],
      pendingInvitations: [],
    })
    mockApi.claimIncidentRole.mockResolvedValue([])
    mockApi.joinIncident.mockResolvedValue([])
    mockApi.observeIncident.mockResolvedValue([])
  })

  it('shows roles, assignees, and membership without leaking private instructions', async () => {
    renderWithQueryClient(
      <IncidentRolesPanel incidentId={INCIDENT_ID} incidentVersion={3} onMutated={vi.fn()} />
    )

    expect(await screen.findByText('Incident Commander')).toBeInTheDocument()
    expect(screen.getByText('Scribe')).toBeInTheDocument()
    // Assignee and members resolve to display names.
    expect((await screen.findAllByText(/Grace/)).length).toBeGreaterThan(0)
    expect(screen.getByText(/Lin/)).toBeInTheDocument()
    // Guardrail: private responder instructions never render to incident viewers.
    expect(screen.queryByText('SECRET COMMANDER PLAYBOOK')).not.toBeInTheDocument()
  })

  it('claims an unassigned role with the incident version for optimistic concurrency', async () => {
    const onMutated = vi.fn()
    renderWithQueryClient(
      <IncidentRolesPanel incidentId={INCIDENT_ID} incidentVersion={3} onMutated={onMutated} />
    )
    fireEvent.click(await screen.findByRole('button', {name: 'Claim'}))
    await waitFor(() =>
      expect(mockApi.claimIncidentRole).toHaveBeenCalledWith(INCIDENT_ID, 'role-scribe', 3)
    )
    await waitFor(() => expect(onMutated).toHaveBeenCalled())
  })

  it('joins and observes the incident with optimistic-version data', async () => {
    renderWithQueryClient(
      <IncidentRolesPanel incidentId={INCIDENT_ID} incidentVersion={5} onMutated={vi.fn()} />
    )
    fireEvent.click(await screen.findByRole('button', {name: 'Join'}))
    await waitFor(() => expect(mockApi.joinIncident).toHaveBeenCalledWith(INCIDENT_ID, {expectedVersion: 5}))
    fireEvent.click(screen.getByRole('button', {name: 'Observe'}))
    await waitFor(() => expect(mockApi.observeIncident).toHaveBeenCalledWith(INCIDENT_ID, {expectedVersion: 5}))
  })

  it('keeps versioned actions blocked until the incident refetch resolves', async () => {
    let releaseRefetch: () => void = () => {}
    const onMutated = vi.fn(
      () =>
        new Promise<void>((resolve) => {
          releaseRefetch = resolve
        }),
    )
    renderWithQueryClient(
      <IncidentRolesPanel incidentId={INCIDENT_ID} incidentVersion={3} onMutated={onMutated} />
    )

    fireEvent.click(await screen.findByRole('button', {name: 'Claim'}))
    await waitFor(() => expect(mockApi.claimIncidentRole).toHaveBeenCalled())
    // While the incident (and its version) is still refetching, other versioned
    // actions stay disabled so they cannot reuse the stale expectedVersion.
    await waitFor(() => expect(screen.getByRole('button', {name: 'Join'})).toBeDisabled())
    expect(onMutated).toHaveBeenCalled()

    releaseRefetch()
    await waitFor(() => expect(screen.getByRole('button', {name: 'Join'})).not.toBeDisabled())
  })
})
