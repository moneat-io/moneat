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
import type {OnCallIncident, OnCallIncidentStatus} from '@/lib/api/types'
import {IncidentTriageActions, TriageSeverityBadge} from '../IncidentTriageActions'

const api = vi.hoisted(() => ({
  acceptOnCallIncident: vi.fn(),
  declineOnCallIncident: vi.fn(),
  mergeOnCallIncident: vi.fn(),
  getIncidentTypes: vi.fn(),
  getOnCallIncidents: vi.fn(),
}))
const toast = vi.hoisted(() => vi.fn())
vi.mock('@/lib/api', () => ({api}))
vi.mock('@/hooks/useToast', () => ({useToast: () => ({toast})}))

const incident = (id: string, status: OnCallIncidentStatus, version = 2): OnCallIncident => ({
  id,
  organizationId: 'org',
  title: id,
  status,
  version,
  declaredBy: 'user',
  declaredAt: '2026-01-01T00:00:00Z',
  alertCount: 1,
  createdAt: '2026-01-01T00:00:00Z',
  updatedAt: '2026-01-01T00:00:00Z',
})

function renderActions(value = incident('source', 'TRIAGE')) {
  const client = new QueryClient({defaultOptions: {queries: {retry: false}, mutations: {retry: false}}})
  return render(
    <QueryClientProvider client={client}>
      <IncidentTriageActions incident={value} onMutated={vi.fn()} layout="banner" />
    </QueryClientProvider>
  )
}

describe('IncidentTriageActions', () => {
  beforeEach(() => {
    vi.clearAllMocks()
    api.getIncidentTypes.mockResolvedValue([{id: 'type', key: 'type', name: 'Operational', enabled: true}])
    api.getOnCallIncidents.mockResolvedValue([incident('source', 'TRIAGE'), incident('target', 'ACTIVE', 4)])
    api.acceptOnCallIncident.mockResolvedValue(incident('source', 'ACTIVE', 3))
    api.declineOnCallIncident.mockResolvedValue(incident('source', 'DECLINED', 3))
    api.mergeOnCallIncident.mockResolvedValue(incident('target', 'ACTIVE', 5))
  })

  it('accepts triage with classification and expected version', async () => {
    renderActions()
    fireEvent.click(screen.getByText('Accept'))
    expect(await screen.findByText('Classify this triage incident and move it into active response. A severity is required.'))
      .toBeInTheDocument()
    fireEvent.click(screen.getByText('SEV-1'))
    fireEvent.click(screen.getByRole('button', {name: 'Accept incident'}))
    await waitFor(() => expect(api.acceptOnCallIncident).toHaveBeenCalledWith(
      'source', {severity: 'SEV-1', expectedVersion: 2}
    ))
  })

  it('declines triage with a reason and expected version', async () => {
    renderActions()
    fireEvent.click(screen.getByText('Decline'))
    fireEvent.change(await screen.findByLabelText('Reason (optional)'), {target: {value: 'not real'}})
    fireEvent.click(screen.getByRole('button', {name: 'Decline incident'}))
    await waitFor(() => expect(api.declineOnCallIncident).toHaveBeenCalledWith(
      'source', {reason: 'not real', expectedVersion: 2}
    ))
  })

  it('shows eligible merge targets and reports the empty-target state', async () => {
    const view = renderActions()
    fireEvent.click(screen.getByText('Merge'))
    expect(await screen.findByLabelText('Merge into')).toBeInTheDocument()
    view.unmount()

    api.getOnCallIncidents.mockResolvedValue([incident('source', 'TRIAGE')])
    renderActions()
    fireEvent.click(screen.getByText('Merge'))
    expect(await screen.findByText(/no other incidents/i)).toBeInTheDocument()
  })

  it('renders lifecycle-specific actions, severity fallbacks, and actionable failures', async () => {
    const error = Object.assign(new Error('stale version'), {status: 409})
    api.acceptOnCallIncident.mockRejectedValue(error)
    const actions = renderActions()
    fireEvent.click(screen.getByText('Accept'))
    fireEvent.click(await screen.findByRole('button', {name: 'Accept incident'}))
    await waitFor(() => expect(toast).toHaveBeenCalledWith(expect.objectContaining({title: 'Action failed'})))
    actions.unmount()

    const {rerender} = render(<TriageSeverityBadge />)
    expect(screen.getByText('Unclassified')).toBeInTheDocument()
    rerender(<TriageSeverityBadge severity="SEV-0" />)
    expect(screen.getByText('SEV-0')).toBeInTheDocument()

    renderActions(incident('closed', 'CLOSED'))
    expect(screen.queryByText('Accept')).not.toBeInTheDocument()
  })
})
