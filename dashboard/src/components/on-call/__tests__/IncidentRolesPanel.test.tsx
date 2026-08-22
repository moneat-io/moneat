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
vi.mock('@/components/ui/dialog', () => ({
  Dialog: ({open, children}: Readonly<{open: boolean; children: React.ReactNode}>) =>
    open ? <>{children}</> : null,
  DialogContent: ({children}: Readonly<{children: React.ReactNode}>) => <div>{children}</div>,
  DialogDescription: ({children}: Readonly<{children: React.ReactNode}>) => <p>{children}</p>,
  DialogFooter: ({children}: Readonly<{children: React.ReactNode}>) => <div>{children}</div>,
  DialogHeader: ({children}: Readonly<{children: React.ReactNode}>) => <div>{children}</div>,
  DialogTitle: ({children}: Readonly<{children: React.ReactNode}>) => <h2>{children}</h2>,
}))
vi.mock('@/components/ui/select', async () => {
  const React = await import('react')
  interface SelectContextValue {
    open: boolean
    setOpen: (open: boolean) => void
    value: string
    onValueChange: (value: string) => void
  }
  const SelectContext = React.createContext<SelectContextValue | null>(null)
  return {
    Select: ({
      value,
      onValueChange,
      children,
    }: Readonly<{value: string; onValueChange: (value: string) => void; children: React.ReactNode}>) => {
      const [open, setOpen] = React.useState(false)
      return (
        <SelectContext.Provider value={{open, setOpen, value, onValueChange}}>
          <div>{children}</div>
        </SelectContext.Provider>
      )
    },
    SelectTrigger: ({children, ...props}: React.ButtonHTMLAttributes<HTMLButtonElement>) => {
      const context = React.useContext(SelectContext)
      return (
        <button
          {...props}
          type="button"
          role="combobox"
          aria-controls="test-select-options"
          aria-expanded={context?.open ?? false}
          onClick={() => context?.setOpen(!context.open)}
        >
          {children}
        </button>
      )
    },
    SelectValue: () => null,
    SelectContent: ({children}: Readonly<{children: React.ReactNode}>) => {
      const context = React.useContext(SelectContext)
      return context?.open ? <div role="listbox">{children}</div> : null
    },
    SelectItem: ({value, children}: Readonly<{value: string; children: React.ReactNode}>) => {
      const context = React.useContext(SelectContext)
      return (
        <button
          type="button"
          role="option"
          aria-selected={context?.value === value}
          onClick={() => {
            context?.onValueChange(value)
            context?.setOpen(false)
          }}
        >
          {children}
        </button>
      )
    },
  }
})

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
    mockApi.unassignIncidentRole.mockResolvedValue(undefined)
    mockApi.assignIncidentRole.mockResolvedValue([])
    mockApi.handoverIncidentRole.mockResolvedValue([])
    mockApi.joinIncident.mockResolvedValue([])
    mockApi.observeIncident.mockResolvedValue([])
    mockApi.leaveIncident.mockResolvedValue(undefined)
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

  it('assigns and hands over responder roles with optimistic-version data', async () => {
    mockApi.assignIncidentRole.mockResolvedValue([
      {id: 'as1', role: commander, assigneeUserId: GRACE, assignedByUserId: SELF, assignedAt: '2026-06-05T00:00:00Z'},
      {id: 'as2', role: scribe, assigneeUserId: LIN, assignedByUserId: SELF, assignedAt: '2026-06-05T00:01:00Z'},
    ])
    renderWithQueryClient(
      <IncidentRolesPanel incidentId={INCIDENT_ID} incidentVersion={7} onMutated={vi.fn()} />
    )
    await screen.findByText('Incident Commander')

    fireEvent.click(screen.getByRole('button', {name: 'Assign…'}))
    fireEvent.click(screen.getByRole('combobox', {name: 'Responder'}))
    fireEvent.click(screen.getByRole('option', {name: 'Lin'}))
    fireEvent.click(screen.getByRole('button', {name: 'Assign'}))
    await waitFor(() =>
      expect(mockApi.assignIncidentRole).toHaveBeenCalledWith(INCIDENT_ID, 'role-scribe', LIN, 7)
    )

    fireEvent.click(screen.getAllByRole('button', {name: 'Handover'})[0])
    fireEvent.click(screen.getByRole('combobox', {name: 'Responder'}))
    fireEvent.click(screen.getByRole('option', {name: 'Ada'}))
    fireEvent.change(screen.getByLabelText('Handover note'), {target: {value: ' Shift ended '}})
    fireEvent.click(screen.getByRole('button', {name: 'Hand over'}))
    await waitFor(() =>
      expect(mockApi.handoverIncidentRole).toHaveBeenCalledWith(INCIDENT_ID, 'role-cmd', {
        userId: SELF,
        note: 'Shift ended',
        expectedVersion: 7,
      })
    )
  })

  it('unassigns roles and removes incident members', async () => {
    const onMutated = vi.fn()
    renderWithQueryClient(
      <IncidentRolesPanel incidentId={INCIDENT_ID} incidentVersion={3} onMutated={onMutated} />
    )
    fireEvent.click(await screen.findByRole('button', {name: 'Unassign'}))
    await waitFor(() => expect(mockApi.unassignIncidentRole).toHaveBeenCalledWith(INCIDENT_ID, 'role-cmd'))
    await waitFor(() => expect(onMutated).toHaveBeenCalled())

    fireEvent.click(screen.getAllByRole('button', {name: 'Remove'})[0])
    await waitFor(() => expect(mockApi.leaveIncident).toHaveBeenCalledWith(INCIDENT_ID, LIN))
  })

  it('shows self-membership and lets the current responder leave', async () => {
    mockApi.getIncidentParticipants.mockResolvedValue([
      {
        id: 'p-self',
        userId: SELF,
        type: 'PARTICIPANT',
        joinedByUserId: SELF,
        joinedAt: '2026-06-05T00:00:00Z',
      },
    ])
    renderWithQueryClient(
      <IncidentRolesPanel incidentId={INCIDENT_ID} incidentVersion={9} onMutated={vi.fn()} />
    )

    await waitFor(() => expect(screen.getByRole('button', {name: 'Join'})).toBeDisabled())
    fireEvent.click(screen.getByRole('button', {name: 'Leave'}))
    await waitFor(() => expect(mockApi.leaveIncident).toHaveBeenCalledWith(INCIDENT_ID, SELF))
  })
})
