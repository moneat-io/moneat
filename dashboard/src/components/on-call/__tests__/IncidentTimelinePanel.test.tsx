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

// Note: Radix menu/dialog portals do not drive reliably under jsdom, so timeline
// mutations (annotate/edit/delete/reorder/restore) are covered end-to-end by the
// typed API client tests. This suite covers the calm read path, JSON export, and
// the real filter wiring, which are reachable without portal interactions.

import {beforeAll, beforeEach, describe, expect, it, vi} from 'vitest'
import {fireEvent, screen, waitFor} from '@testing-library/react'
import {renderWithQueryClient} from '@/test/utils'
import {IncidentTimelinePanel} from '../IncidentTimelinePanel'

const INCIDENT_ID = 'c0000000-0000-4000-8000-000000000009'
const EVENT_A = 'c1000000-0000-4000-8000-000000000001'
const EVENT_B = 'c1000000-0000-4000-8000-000000000002'

const {mockApi} = vi.hoisted(() => ({
  mockApi: {
    getOnCallIncidentTimeline: vi.fn(),
    getOrgMembers: vi.fn(),
    exportOnCallIncidentTimeline: vi.fn(),
    reorderIncidentTimeline: vi.fn(),
    annotateIncidentTimelineEvent: vi.fn(),
    editIncidentTimelineEvent: vi.fn(),
    deleteIncidentTimelineEvent: vi.fn(),
    restoreIncidentTimelineEvent: vi.fn(),
    getIncidentTimelineRevisions: vi.fn(),
  },
}))

vi.mock('@/lib/api', () => ({api: mockApi}))
vi.mock('@/components/ui/dialog', () => ({
  Dialog: ({open, children}: Readonly<{open: boolean; children: React.ReactNode}>) =>
    open ? <>{children}</> : null,
  DialogContent: ({children}: Readonly<{children: React.ReactNode}>) => <div>{children}</div>,
  DialogDescription: ({children}: Readonly<{children: React.ReactNode}>) => <p>{children}</p>,
  DialogFooter: ({children}: Readonly<{children: React.ReactNode}>) => <div>{children}</div>,
  DialogHeader: ({children}: Readonly<{children: React.ReactNode}>) => <div>{children}</div>,
  DialogTitle: ({children}: Readonly<{children: React.ReactNode}>) => <h2>{children}</h2>,
}))
vi.mock('@/components/ui/dropdown-menu', () => ({
  DropdownMenu: ({children}: Readonly<{children: React.ReactNode}>) => <div>{children}</div>,
  DropdownMenuTrigger: ({children}: Readonly<{children: React.ReactNode}>) => <>{children}</>,
  DropdownMenuContent: ({children}: Readonly<{children: React.ReactNode}>) => <div>{children}</div>,
  DropdownMenuLabel: ({children}: Readonly<{children: React.ReactNode}>) => <div>{children}</div>,
  DropdownMenuSeparator: () => <hr />,
  DropdownMenuItem: ({
    children,
    disabled,
    onSelect,
  }: Readonly<{
    children: React.ReactNode
    disabled?: boolean
    onSelect?: () => void
  }>) => (
    <button type="button" disabled={disabled} onClick={onSelect}>
      {children}
    </button>
  ),
  DropdownMenuCheckboxItem: ({
    children,
    checked,
    onCheckedChange,
  }: Readonly<{
    children: React.ReactNode
    checked?: boolean
    onCheckedChange?: (checked: boolean) => void
  }>) => (
    <button
      type="button"
      role="menuitemcheckbox"
      aria-checked={checked}
      onClick={() => onCheckedChange?.(!checked)}
    >
      {children}
    </button>
  ),
}))

const entries = [
  {
    id: EVENT_A,
    eventKey: 'declared',
    eventType: 'DECLARED',
    details: {},
    provenance: 'REST',
    visibility: 'ORGANIZATION',
    originalOccurredAt: '2026-06-05T12:00:00.000Z',
    observedAt: '2026-06-05T12:00:00.000Z',
    displayOrder: 1000000,
    createdAt: '2026-06-05T12:00:00.000Z',
  },
  {
    id: EVENT_B,
    eventKey: 'note',
    eventType: 'NOTE_ADDED',
    details: {note: 'Mitigation applied'},
    provenance: 'REST',
    visibility: 'ORGANIZATION',
    originalOccurredAt: '2026-06-05T12:05:00.000Z',
    observedAt: '2026-06-05T12:05:00.000Z',
    displayOrder: 2000000,
    annotation: 'Verified by responders',
    createdAt: '2026-06-05T12:05:00.000Z',
  },
]

beforeAll(() => {
  Object.assign(URL, {createObjectURL: vi.fn(() => 'blob:mock'), revokeObjectURL: vi.fn()})
})

describe('IncidentTimelinePanel', () => {
  beforeEach(() => {
    vi.clearAllMocks()
    mockApi.getOnCallIncidentTimeline.mockResolvedValue(entries)
    mockApi.getOrgMembers.mockResolvedValue({members: [], pendingInvitations: []})
    mockApi.exportOnCallIncidentTimeline.mockResolvedValue({incidentId: INCIDENT_ID, exportedAt: '', events: entries})
    mockApi.reorderIncidentTimeline.mockResolvedValue(entries)
    mockApi.annotateIncidentTimelineEvent.mockResolvedValue(entries[0])
    mockApi.editIncidentTimelineEvent.mockResolvedValue(entries[0])
    mockApi.deleteIncidentTimelineEvent.mockResolvedValue(entries[0])
    mockApi.restoreIncidentTimelineEvent.mockResolvedValue(entries[0])
    mockApi.getIncidentTimelineRevisions.mockResolvedValue([])
  })

  it('renders canonical entries with provenance, visibility, note, and annotation', async () => {
    renderWithQueryClient(<IncidentTimelinePanel incidentId={INCIDENT_ID} />)
    expect(await screen.findByText('Incident declared')).toBeInTheDocument()
    expect(screen.getByText('Note added')).toBeInTheDocument()
    expect(screen.getAllByText('Dashboard').length).toBeGreaterThan(0)
    expect(screen.getAllByText('Organization').length).toBeGreaterThan(0)
    expect(screen.getByText('"Mitigation applied"')).toBeInTheDocument()
    expect(screen.getByText(/Verified by responders/)).toBeInTheDocument()
  })

  it('exports the timeline as JSON', async () => {
    renderWithQueryClient(<IncidentTimelinePanel incidentId={INCIDENT_ID} />)
    await screen.findByText('Incident declared')
    fireEvent.click(screen.getByRole('button', {name: /Export JSON/}))
    await waitFor(() => expect(mockApi.exportOnCallIncidentTimeline).toHaveBeenCalledWith(INCIDENT_ID))
  })

  it('summarizes known events and discloses unknown-event details without leaking private keys', async () => {
    const USER = 'c2000000-0000-4000-8000-000000000001'
    mockApi.getOrgMembers.mockResolvedValue({
      members: [{userId: USER, email: 'grace@x.io', name: 'Grace'}],
      pendingInvitations: [],
    })
    mockApi.getOnCallIncidentTimeline.mockResolvedValue([
      {
        id: 'ev-role',
        eventKey: 'role',
        eventType: 'ROLE_ASSIGNED',
        details: {role: 'Commander', assigneeUserId: USER},
        provenance: 'REST',
        visibility: 'ORGANIZATION',
        originalOccurredAt: '2026-06-05T12:00:00.000Z',
        observedAt: '2026-06-05T12:00:00.000Z',
        displayOrder: 1000000,
        createdAt: '2026-06-05T12:00:00.000Z',
      },
      {
        id: 'ev-slack',
        eventKey: 'slack',
        eventType: 'SLACK_SELECTION',
        details: {channel: '#incidents', selection: 'rollback', origin: 'SLACK', instructions: 'SECRET PLAYBOOK'},
        provenance: 'SLACK',
        visibility: 'ORGANIZATION',
        originalOccurredAt: '2026-06-05T12:05:00.000Z',
        observedAt: '2026-06-05T12:05:00.000Z',
        displayOrder: 2000000,
        createdAt: '2026-06-05T12:05:00.000Z',
      },
    ])
    renderWithQueryClient(<IncidentTimelinePanel incidentId={INCIDENT_ID} />)
    expect(await screen.findByText('Role assigned')).toBeInTheDocument()
    expect(screen.getByText('Commander → Grace')).toBeInTheDocument()
    // Unknown event: progressive-disclosure key/values, minus private/plumbing keys.
    expect(screen.getByText('Details')).toBeInTheDocument()
    expect(screen.getByText('#incidents')).toBeInTheDocument()
    expect(screen.queryByText('SECRET PLAYBOOK')).not.toBeInTheDocument()
  })

  it('refetches with includeDeleted when the show-removed filter is toggled', async () => {
    renderWithQueryClient(<IncidentTimelinePanel incidentId={INCIDENT_ID} />)
    await screen.findByText('Incident declared')
    fireEvent.click(screen.getByRole('button', {name: /Filters/}))
    fireEvent.click(await screen.findByRole('switch'))
    await waitFor(() =>
      expect(mockApi.getOnCallIncidentTimeline).toHaveBeenCalledWith(
        INCIDENT_ID,
        expect.objectContaining({includeDeleted: true})
      )
    )
  })

  it('filters by event type, provenance, and visibility and clears every filter', async () => {
    renderWithQueryClient(<IncidentTimelinePanel incidentId={INCIDENT_ID} />)
    await screen.findByText('Incident declared')
    fireEvent.click(screen.getByRole('button', {name: /Filters/}))

    fireEvent.click(screen.getByRole('menuitemcheckbox', {name: 'Note added'}))
    await waitFor(() =>
      expect(mockApi.getOnCallIncidentTimeline).toHaveBeenCalledWith(
        INCIDENT_ID,
        expect.objectContaining({eventType: ['NOTE_ADDED']})
      )
    )
    fireEvent.click(screen.getByRole('menuitemcheckbox', {name: 'Note added'}))
    fireEvent.click(screen.getByRole('menuitemcheckbox', {name: 'Dashboard'}))
    fireEvent.click(screen.getByRole('menuitemcheckbox', {name: 'Organization'}))
    await waitFor(() =>
      expect(mockApi.getOnCallIncidentTimeline).toHaveBeenCalledWith(
        INCIDENT_ID,
        expect.objectContaining({provenance: ['REST'], visibility: ['ORGANIZATION']})
      )
    )

    fireEvent.click(screen.getByRole('button', {name: 'Clear'}))
    await waitFor(() =>
      expect(mockApi.getOnCallIncidentTimeline).toHaveBeenCalledWith(
        INCIDENT_ID,
        {eventType: undefined, provenance: undefined, visibility: undefined, includeDeleted: false}
      )
    )
  })

  it('reorders and annotates timeline evidence', async () => {
    renderWithQueryClient(<IncidentTimelinePanel incidentId={INCIDENT_ID} />)
    await screen.findByText('Incident declared')

    fireEvent.click(screen.getAllByRole('button', {name: 'Move down'})[0])
    await waitFor(() =>
      expect(mockApi.reorderIncidentTimeline).toHaveBeenCalledWith(INCIDENT_ID, [EVENT_B, EVENT_A])
    )

    fireEvent.click(screen.getAllByRole('button', {name: 'Annotate'})[0])
    fireEvent.change(screen.getByLabelText('Annotation'), {target: {value: ' Confirmed by logs '}})
    fireEvent.change(screen.getByLabelText('Reason (optional)'), {target: {value: ' Evidence review '}})
    fireEvent.click(screen.getByRole('button', {name: 'Save annotation'}))
    await waitFor(() =>
      expect(mockApi.annotateIncidentTimelineEvent).toHaveBeenCalledWith(INCIDENT_ID, EVENT_A, {
        annotation: 'Confirmed by logs',
        reason: 'Evidence review',
      })
    )
  })

  it('edits timeline evidence through the versioned correction dialog', async () => {
    renderWithQueryClient(<IncidentTimelinePanel incidentId={INCIDENT_ID} />)
    await screen.findByText('Incident declared')

    fireEvent.click(screen.getAllByRole('button', {name: 'Edit'})[0])
    fireEvent.change(screen.getByLabelText('Event type'), {target: {value: 'status_changed'}})
    fireEvent.change(screen.getByLabelText('Details (JSON)'), {
      target: {value: '{"status":"mitigated"}'},
    })
    fireEvent.change(screen.getByLabelText('Reason (optional)'), {
      target: {value: ' Correcting evidence '},
    })
    fireEvent.click(screen.getByRole('button', {name: 'Save changes'}))

    await waitFor(() =>
      expect(mockApi.editIncidentTimelineEvent).toHaveBeenCalledWith(INCIDENT_ID, EVENT_A, {
        eventType: 'STATUS_CHANGED',
        visibility: undefined,
        originalOccurredAt: undefined,
        details: {status: 'mitigated'},
        reason: 'Correcting evidence',
      })
    )
  })

  it('soft-deletes an event with an audit reason', async () => {
    renderWithQueryClient(<IncidentTimelinePanel incidentId={INCIDENT_ID} />)
    await screen.findByText('Incident declared')

    fireEvent.click(screen.getAllByRole('button', {name: 'Remove'})[0])
    fireEvent.change(screen.getByLabelText('Reason (optional)'), {
      target: {value: ' Duplicate evidence '},
    })
    fireEvent.click(screen.getAllByRole('button', {name: 'Remove'}).at(-1)!)

    await waitFor(() =>
      expect(mockApi.deleteIncidentTimelineEvent).toHaveBeenCalledWith(
        INCIDENT_ID,
        EVENT_A,
        'Duplicate evidence'
      )
    )
  })

  it('restores removed evidence and shows its revision history', async () => {
    const deletedEntry = {
      ...entries[0],
      deletedAt: '2026-06-05T12:10:00.000Z',
    }
    mockApi.getOnCallIncidentTimeline.mockResolvedValue([deletedEntry])
    mockApi.getIncidentTimelineRevisions.mockResolvedValue([
      {
        id: 'c3000000-0000-4000-8000-000000000001',
        revision: 1,
        action: 'DELETED',
        previous: {deletedAt: null},
        next: {deletedAt: deletedEntry.deletedAt},
        reason: 'Duplicate evidence',
        editedByUserId: 'c2000000-0000-4000-8000-000000000002',
        createdAt: deletedEntry.deletedAt,
      },
    ])

    renderWithQueryClient(<IncidentTimelinePanel incidentId={INCIDENT_ID} />)
    expect(await screen.findByText('Removed')).toBeInTheDocument()

    fireEvent.click(screen.getByRole('button', {name: 'Revision history'}))
    expect(await screen.findByText('Deleted')).toBeInTheDocument()
    expect(screen.getByText(/Duplicate evidence/)).toBeInTheDocument()
    await waitFor(() =>
      expect(mockApi.getIncidentTimelineRevisions).toHaveBeenCalledWith(INCIDENT_ID, EVENT_A)
    )

    fireEvent.click(screen.getByRole('button', {name: 'Close'}))
    fireEvent.click(screen.getAllByRole('button', {name: 'Restore'}).at(-1)!)
    fireEvent.change(screen.getByLabelText('Reason (optional)'), {
      target: {value: ' Needed for the audit '},
    })
    fireEvent.click(screen.getAllByRole('button', {name: 'Restore'}).at(-1)!)

    await waitFor(() =>
      expect(mockApi.restoreIncidentTimelineEvent).toHaveBeenCalledWith(
        INCIDENT_ID,
        EVENT_A,
        'Needed for the audit'
      )
    )
  })
})
