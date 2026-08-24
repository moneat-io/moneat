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

import type {ComponentType} from 'react'
import {QueryClient, QueryClientProvider} from '@tanstack/react-query'
import {fireEvent, render, screen, waitFor} from '@testing-library/react'
import {beforeEach, describe, expect, it, vi} from 'vitest'
import type {AlertRoute} from '@/lib/api/types'

const state = vi.hoisted(() => ({enabled: true, isLoading: false, isAdmin: true}))
const toast = vi.hoisted(() => vi.fn())
const api = vi.hoisted(() => ({
  getAlertRoutes: vi.fn(),
  getEscalationPolicies: vi.fn(),
  getOrganizationTeams: vi.fn(),
  getIncidentTypes: vi.fn(),
  updateAlertRoute: vi.fn(),
  reorderAlertRoutes: vi.fn(),
  deleteAlertRoute: vi.fn(),
}))

vi.mock('@tanstack/react-router', () => ({
  createFileRoute: () => (options: unknown) => ({options}),
}))
vi.mock('@/lib/api', () => ({api}))
vi.mock('@/hooks/useToast', () => ({useToast: () => ({toast})}))
vi.mock('@/hooks/useNativeIncidentCapabilities', () => ({
  useNativeIncidentCapabilities: () => ({...state, isError: false}),
  nativeIncidentUnavailableCopy: () => ({title: 'Incidents unavailable', description: 'Enable incidents first.'}),
}))
vi.mock('@/hooks/useOnCallAdmin', () => ({useOnCallAdmin: () => ({isAdmin: state.isAdmin})}))
vi.mock('@/components/on-call/alert-routes/AlertRouteEditorSheet', () => ({
  AlertRouteEditorSheet: ({open, route}: {open: boolean; route?: AlertRoute}) =>
    open ? <div data-testid="route-editor">{route?.name ?? 'New route editor'}</div> : null,
}))

import {Route} from '../on-call.alert-routes'

const baseRoute: AlertRoute = {
  id: '11111111-1111-4111-8111-111111111111',
  key: 'checkout',
  name: 'Checkout',
  description: 'Checkout failures',
  enabled: true,
  position: 0,
  revision: 2,
  conditionGroups: [
    {conditions: [{field: 'priority', operator: 'EQUALS', values: ['P1']}]},
    {conditions: [{field: 'status', operator: 'EQUALS', values: ['FIRING']}]},
  ],
  paging: {
    mode: 'EVERY_EPISODE',
    targets: [
      {kind: 'ESCALATION_POLICY', escalationPolicyId: 'policy'},
      {kind: 'TEAM', teamId: 'team'},
    ],
  },
  grouping: {behavior: 'AUTOMATIC', keys: ['ownership.service'], windowKind: 'ROLLING', windowSeconds: 300},
  incident: {create: true, mode: 'ACTIVE', severity: 'SEV-1'},
  recovery: {cancelEscalations: true, declineUnacceptedTriage: false, graceSeconds: 0},
  createdAt: '2026-01-01T00:00:00Z',
  updatedAt: '2026-01-01T00:00:00Z',
}

const secondRoute: AlertRoute = {
  ...baseRoute,
  id: '22222222-2222-4222-8222-222222222222',
  key: 'fallback',
  name: 'Fallback',
  description: undefined,
  enabled: false,
  position: 1,
  revision: 4,
  conditionGroups: [],
  paging: {mode: 'NONE', targets: []},
  grouping: {behavior: 'SUGGESTED', keys: [], windowKind: 'FIXED', windowSeconds: 3600},
  incident: {create: false, mode: 'TRIAGE'},
}

function renderPage() {
  const client = new QueryClient({defaultOptions: {queries: {retry: false}, mutations: {retry: false}}})
  const Page = Route.options.component as ComponentType
  return render(<QueryClientProvider client={client}><Page /></QueryClientProvider>)
}

describe('alert routes page', () => {
  beforeEach(() => {
    vi.clearAllMocks()
    Object.assign(state, {enabled: true, isLoading: false, isAdmin: true})
    api.getAlertRoutes.mockResolvedValue([secondRoute, baseRoute])
    api.getEscalationPolicies.mockResolvedValue([{id: 'policy', name: 'Primary'}])
    api.getOrganizationTeams.mockResolvedValue([{id: 'team', name: 'Platform'}])
    api.getIncidentTypes.mockResolvedValue([
      {id: 'type', key: 'operational', name: 'Operational', enabled: true},
      {id: 'retired', key: 'retired', name: 'Retired', enabled: false},
    ])
    api.updateAlertRoute.mockResolvedValue({...baseRoute, enabled: false})
    api.reorderAlertRoutes.mockResolvedValue([secondRoute, baseRoute])
    api.deleteAlertRoute.mockResolvedValue(undefined)
  })

  it('renders route summaries and executes administrator controls', async () => {
    renderPage()
    expect(await screen.findByText('Checkout')).toBeInTheDocument()
    expect(screen.getByText(/\(\+1 more group\)/)).toBeInTheDocument()
    expect(screen.getByText(/2 targets/)).toBeInTheDocument()
    expect(screen.getByText('Incident (active)')).toBeInTheDocument()
    expect(screen.getByText('No conditions')).toBeInTheDocument()

    fireEvent.click(screen.getByLabelText('Toggle Checkout'))
    await waitFor(() => expect(api.updateAlertRoute).toHaveBeenCalled())
    fireEvent.click(screen.getByLabelText('Move Checkout down'))
    await waitFor(() => expect(api.reorderAlertRoutes).toHaveBeenCalledWith({
      routes: [
        {id: secondRoute.id, expectedRevision: secondRoute.revision},
        {id: baseRoute.id, expectedRevision: baseRoute.revision},
      ],
    }))

    fireEvent.click(screen.getByLabelText('Edit Checkout'))
    expect(screen.getByTestId('route-editor')).toHaveTextContent('Checkout')
    fireEvent.click(screen.getByLabelText('Delete Checkout'))
    expect(await screen.findByText('Delete this alert route?')).toBeInTheDocument()
    fireEvent.click(screen.getByRole('button', {name: 'Delete route'}))
    await waitFor(() => expect(api.deleteAlertRoute).toHaveBeenCalledWith(baseRoute.id, 2))
  })

  it('supports empty administrator and read-only responder states', async () => {
    api.getAlertRoutes.mockResolvedValue([])
    const admin = renderPage()
    expect(await screen.findByText('No alert routes yet')).toBeInTheDocument()
    fireEvent.click(screen.getAllByText('New route')[0])
    expect(screen.getByTestId('route-editor')).toHaveTextContent('New route editor')
    admin.unmount()

    state.isAdmin = false
    renderPage()
    expect(await screen.findByText(/Editing routes requires an administrator/)).toBeInTheDocument()
    expect(screen.queryByText('New route')).not.toBeInTheDocument()
  })

  it('reports stale and ordinary mutation failures', async () => {
    const conflict = Object.assign(new Error('stale'), {status: 409})
    api.updateAlertRoute.mockRejectedValueOnce(conflict).mockRejectedValueOnce(new Error('network failed'))
    renderPage()
    await screen.findByText('Checkout')
    fireEvent.click(screen.getByLabelText('Toggle Checkout'))
    await waitFor(() => expect(toast).toHaveBeenCalledWith(expect.objectContaining({title: 'Routes changed'})))
    fireEvent.click(screen.getByLabelText('Toggle Checkout'))
    await waitFor(() => expect(toast).toHaveBeenCalledWith(expect.objectContaining({description: 'network failed'})))
  })

  it('honors loading and rollout-disabled gates', () => {
    state.isLoading = true
    const loading = renderPage()
    expect(loading.container.querySelector('.animate-spin')).not.toBeNull()
    loading.unmount()

    api.getAlertRoutes.mockClear()
    Object.assign(state, {isLoading: false, enabled: false})
    renderPage()
    expect(screen.getByText('Incidents unavailable')).toBeInTheDocument()
    expect(api.getAlertRoutes).not.toHaveBeenCalled()
  })
})
