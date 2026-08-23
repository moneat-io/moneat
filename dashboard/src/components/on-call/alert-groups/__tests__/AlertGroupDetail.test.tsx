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
import {beforeEach, describe, expect, it, vi} from 'vitest'
import type {AlertGroup} from '@/lib/api/types'
import {AlertGroupDetail} from '../AlertGroupDetail'

const navigate = vi.hoisted(() => vi.fn())
const toast = vi.hoisted(() => vi.fn())
const api = vi.hoisted(() => ({
  getAlertGroup: vi.fn(),
  getOnCallIncidents: vi.fn(),
  markAlertGroupEpisodeUnrelated: vi.fn(),
  removeAlertGroupEpisode: vi.fn(),
  attachAlertGroup: vi.fn(),
  createAlertGroupTriage: vi.fn(),
}))
vi.mock('@tanstack/react-router', () => ({useNavigate: () => navigate}))
vi.mock('@/hooks/useToast', () => ({useToast: () => ({toast})}))
vi.mock('@/lib/api', () => ({api}))

const GROUP_ID = '11111111-1111-4111-8111-111111111111'
const EPISODE_ID = '22222222-2222-4222-8222-222222222222'
const INCIDENT_ID = '33333333-3333-4333-8333-333333333333'
const group: AlertGroup = {
  id: GROUP_ID,
  routeId: '44444444-4444-4444-8444-444444444444',
  routeRevision: 2,
  identityHash: 'hash',
  groupingTuple: {service: 'checkout'},
  singleton: false,
  behavior: 'SUGGESTED',
  windowKind: 'ROLLING',
  windowSeconds: 300,
  openedAt: '2026-01-01T00:00:00Z',
  lastAlertAt: '2026-01-01T00:01:00Z',
  closesAt: '2099-01-01T00:05:00Z',
  state: 'OPEN',
  version: 3,
  candidateIncidentId: INCIDENT_ID,
  routeSnapshot: {name: 'Checkout route'},
  incidentTemplateSnapshot: {},
  pagingMode: 'FIRST_EPISODE_PER_GROUP',
  pagingState: 'DELIVERED',
  members: [{
    id: 'member', episodeId: EPISODE_ID, state: 'ACTIVE', version: 1, pagingState: 'DELIVERED',
    firstJoinedAt: '2026-01-01T00:00:00Z', lastSeenAt: '2026-01-01T00:01:00Z',
  }],
  decisions: [{
    id: 'decision', type: 'GROUP_CREATED', episodeId: EPISODE_ID, commandKey: 'create', details: {},
    createdAt: '2026-01-01T00:00:00Z',
  }],
  createdAt: '2026-01-01T00:00:00Z',
  updatedAt: '2026-01-01T00:01:00Z',
}

function renderDetail(id = GROUP_ID) {
  const client = new QueryClient({defaultOptions: {queries: {retry: false}, mutations: {retry: false}}})
  return {client, ...render(
    <QueryClientProvider client={client}><AlertGroupDetail groupId={id} /></QueryClientProvider>
  )}
}

describe('AlertGroupDetail', () => {
  beforeEach(() => {
    vi.clearAllMocks()
    api.getAlertGroup.mockResolvedValue(group)
    api.getOnCallIncidents.mockResolvedValue([])
    api.markAlertGroupEpisodeUnrelated.mockResolvedValue({...group, version: 4})
    api.removeAlertGroupEpisode.mockResolvedValue({...group, version: 4, members: []})
    api.createAlertGroupTriage.mockResolvedValue({...group, version: 4, incidentId: INCIDENT_ID})
  })

  it('renders members, decisions, candidate linkage, and mutation actions', async () => {
    renderDetail()
    expect(await screen.findByText(/Alert group/)).toBeInTheDocument()
    expect(screen.getByText('Checkout route')).toBeInTheDocument()
    expect(screen.getByText('Group opened')).toBeInTheDocument()
    fireEvent.click(screen.getByText('Candidate incident'))
    expect(navigate).toHaveBeenCalled()

    fireEvent.click(screen.getByText('Unrelated'))
    await waitFor(() => expect(api.markAlertGroupEpisodeUnrelated).toHaveBeenCalledWith(GROUP_ID, EPISODE_ID, 3))
    fireEvent.click(screen.getByText('Remove'))
    await waitFor(() => expect(api.removeAlertGroupEpisode).toHaveBeenCalledWith(GROUP_ID, EPISODE_ID, 4))
  })

  it('creates triage with optional overrides and shows attach empty state', async () => {
    const attachView = renderDetail()
    fireEvent.click(await screen.findByText('Attach incident'))
    expect(await screen.findByText('No open incidents')).toBeInTheDocument()
    attachView.unmount()

    renderDetail()
    fireEvent.click(await screen.findByText('Create triage'))
    fireEvent.change(await screen.findByLabelText('Title (optional)'), {target: {value: ' Group incident '}})
    fireEvent.change(screen.getByLabelText('Summary (optional)'), {target: {value: ' Summary '}})
    fireEvent.click(screen.getByRole('button', {name: 'Create triage'}))
    await waitFor(() => expect(api.createAlertGroupTriage).toHaveBeenCalledWith(
      GROUP_ID, {expectedVersion: 3, title: 'Group incident', summary: 'Summary'}
    ))
  })

  it('handles invalid, missing, closed, and stale group states', async () => {
    const invalid = renderDetail('not-a-uuid')
    expect(screen.getByText('Invalid group ID')).toBeInTheDocument()
    invalid.unmount()

    api.getAlertGroup.mockRejectedValueOnce(Object.assign(new Error('missing'), {status: 404}))
    const missing = renderDetail()
    expect(await screen.findByText('Alert group not found')).toBeInTheDocument()
    missing.unmount()

    api.getAlertGroup.mockResolvedValueOnce({...group, state: 'CLOSED', incidentId: INCIDENT_ID})
    const closed = renderDetail()
    expect(await screen.findByText(/This group is closed/)).toBeInTheDocument()
    closed.unmount()

    api.getAlertGroup.mockResolvedValue(group)
    api.removeAlertGroupEpisode.mockRejectedValueOnce(Object.assign(new Error('stale'), {status: 409}))
    renderDetail()
    fireEvent.click(await screen.findByText('Remove'))
    await waitFor(() => expect(toast).toHaveBeenCalledWith(expect.objectContaining({title: 'Group changed'})))
  })

  it('distinguishes service failures from missing groups and can retry', async () => {
    api.getAlertGroup.mockRejectedValueOnce(new Error('offline')).mockResolvedValueOnce(group)
    renderDetail()
    expect(await screen.findByText('Unable to load alert group')).toBeInTheDocument()
    fireEvent.click(screen.getByText('Try again'))
    expect(await screen.findByText('Checkout route')).toBeInTheDocument()
  })
})
