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
import type {AlertRoute} from '@/lib/api/types'
import {AlertRouteEditorSheet} from '../AlertRouteEditorSheet'

const toast = vi.hoisted(() => vi.fn())
const api = vi.hoisted(() => ({
  createAlertRoute: vi.fn(),
  updateAlertRoute: vi.fn(),
  getAlertRoute: vi.fn(),
  getAlertRouteRevisions: vi.fn(),
  previewAlertRoutes: vi.fn(),
}))
vi.mock('@/lib/api', () => ({api}))
vi.mock('@/hooks/useToast', () => ({useToast: () => ({toast})}))

const route: AlertRoute = {
  id: '11111111-1111-4111-8111-111111111111',
  key: 'checkout',
  name: 'Checkout',
  description: 'Checkout alerts',
  enabled: true,
  position: 0,
  revision: 2,
  conditionGroups: [{conditions: [{field: 'priority', operator: 'AT_LEAST_AS_SEVERE_AS', values: ['P2']}]}],
  paging: {mode: 'NONE', targets: []},
  grouping: {behavior: 'SUGGESTED', keys: [], windowKind: 'ROLLING', windowSeconds: 300},
  incident: {create: false, mode: 'TRIAGE'},
  recovery: {cancelEscalations: false, declineUnacceptedTriage: false, graceSeconds: 0},
  createdAt: '2026-01-01T00:00:00Z',
  updatedAt: '2026-01-01T00:00:00Z',
}

function renderEditor(props: {route?: AlertRoute; open?: boolean} = {}) {
  const client = new QueryClient({defaultOptions: {queries: {retry: false}, mutations: {retry: false}}})
  return render(
    <QueryClientProvider client={client}>
      <AlertRouteEditorSheet
        open={props.open ?? true}
        onOpenChange={vi.fn()}
        route={props.route}
        escalationPolicies={[{id: 'policy', name: 'Primary'}]}
        teams={[{id: 'team', name: 'Platform'}]}
        incidentTypeOptions={[{value: 'operational', label: 'Operational'}]}
        onSaved={vi.fn()}
      />
    </QueryClientProvider>
  )
}

describe('AlertRouteEditorSheet', () => {
  beforeEach(() => {
    vi.clearAllMocks()
    api.createAlertRoute.mockResolvedValue(route)
    api.updateAlertRoute.mockResolvedValue({...route, revision: 3})
    api.getAlertRoute.mockResolvedValue({...route, revision: 3, name: 'Latest'})
    api.getAlertRouteRevisions.mockResolvedValue([
      {id: 'revision', routeId: route.id, revision: 2, changeType: 'UPDATED', commandKey: 'update', snapshot: {}, createdAt: route.updatedAt},
    ])
  })

  it('creates a valid route without mock success behavior', async () => {
    renderEditor()
    fireEvent.change(screen.getByLabelText('Name'), {target: {value: 'Payments'}})
    fireEvent.change(screen.getByLabelText('Key'), {target: {value: 'payments'}})
    fireEvent.change(screen.getByLabelText('Description (optional)'), {target: {value: 'Critical payments'}})
    fireEvent.click(screen.getByRole('button', {name: 'Create route'}))
    await waitFor(() => expect(api.createAlertRoute).toHaveBeenCalledWith(expect.objectContaining({
      key: 'payments', name: 'Payments', description: 'Critical payments',
    })))
  })

  it('renders saved configuration, revision history, and preserves edits on conflict', async () => {
    const conflict = Object.assign(new Error('stale'), {status: 409})
    api.updateAlertRoute.mockRejectedValueOnce(conflict).mockResolvedValueOnce({...route, revision: 4})
    renderEditor({route})
    expect(await screen.findByText('rev 2')).toBeInTheDocument()
    fireEvent.change(screen.getByLabelText('Name'), {target: {value: 'My unsaved edit'}})
    fireEvent.click(screen.getByRole('button', {name: 'Save changes'}))
    expect(await screen.findByText(/changed since you opened it/i)).toBeInTheDocument()
    expect(screen.getByLabelText('Name')).toHaveValue('My unsaved edit')
    fireEvent.click(screen.getByText('Save over their changes'))
    await waitFor(() => expect(api.updateAlertRoute).toHaveBeenLastCalledWith(
      route.id, expect.objectContaining({name: 'My unsaved edit', expectedRevision: 3})
    ))
  })

  it('can reload the latest revision and remains inert while closed', async () => {
    const conflict = Object.assign(new Error('stale'), {status: 409})
    api.updateAlertRoute.mockRejectedValueOnce(conflict)
    const view = renderEditor({route})
    fireEvent.click(screen.getByRole('button', {name: 'Save changes'}))
    fireEvent.click(await screen.findByText('Reload latest'))
    await waitFor(() => expect(screen.getByLabelText('Name')).toHaveValue('Latest'))
    view.unmount()

    const closed = renderEditor({open: false})
    expect(closed.container.querySelector('input')).toBeNull()
  })
})
