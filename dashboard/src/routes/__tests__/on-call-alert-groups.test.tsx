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

import type {ComponentType, ReactNode} from 'react'
import {QueryClient, QueryClientProvider} from '@tanstack/react-query'
import {fireEvent, render, screen} from '@testing-library/react'
import {beforeEach, describe, expect, it, vi} from 'vitest'
import type {AlertGroup} from '@/lib/api/types'

const state = vi.hoisted(() => ({enabled: true, isLoading: false, pathname: '/on-call/alert-groups'}))
const api = vi.hoisted(() => ({getAlertGroups: vi.fn()}))
vi.mock('@tanstack/react-router', () => ({
  createFileRoute: () => (options: unknown) => ({options}),
  useRouterState: ({select}: {select: (value: unknown) => unknown}) =>
    select({location: {pathname: state.pathname}}),
  Link: ({children, params, to}: {children: ReactNode; params: {groupId: string}; to: string}) => (
    <a href={to} data-group-id={params.groupId}>{children}</a>
  ),
  Outlet: () => <div>Group detail outlet</div>,
}))
vi.mock('@/lib/api', () => ({api}))
vi.mock('@/hooks/useNativeIncidentCapabilities', () => ({
  useNativeIncidentCapabilities: () => ({...state, isError: false}),
  nativeIncidentUnavailableCopy: () => ({title: 'Incidents unavailable', description: 'Enable incidents first.'}),
}))

import {Route} from '../on-call.alert-groups'

const baseGroup: AlertGroup = {
  id: '11111111-1111-4111-8111-111111111111',
  routeId: '22222222-2222-4222-8222-222222222222',
  routeRevision: 1,
  identityHash: 'hash',
  groupingTuple: {service: 'checkout'},
  singleton: false,
  behavior: 'AUTOMATIC',
  windowKind: 'ROLLING',
  windowSeconds: 300,
  openedAt: '2026-01-01T00:00:00Z',
  lastAlertAt: '2026-01-01T00:01:00Z',
  closesAt: '2099-01-01T00:05:00Z',
  state: 'OPEN',
  version: 1,
  routeSnapshot: {name: 'Checkout'},
  incidentTemplateSnapshot: {},
  pagingMode: 'FIRST_EPISODE_PER_GROUP',
  pagingState: 'DELIVERED',
  members: [{
    id: 'member', episodeId: 'episode', state: 'ACTIVE', version: 1, pagingState: 'DELIVERED',
    firstJoinedAt: '2026-01-01T00:00:00Z', lastSeenAt: '2026-01-01T00:01:00Z',
  }],
  decisions: [],
  createdAt: '2026-01-01T00:00:00Z',
  updatedAt: '2026-01-01T00:01:00Z',
}

function renderPage() {
  const client = new QueryClient({defaultOptions: {queries: {retry: false}}})
  const Page = Route.options.component as ComponentType
  return render(<QueryClientProvider client={client}><Page /></QueryClientProvider>)
}

describe('alert groups page', () => {
  beforeEach(() => {
    vi.clearAllMocks()
    Object.assign(state, {enabled: true, isLoading: false, pathname: '/on-call/alert-groups'})
    api.getAlertGroups.mockResolvedValue([
      {...baseGroup, incidentId: '33333333-3333-4333-8333-333333333333'},
      {
        ...baseGroup,
        id: '44444444-4444-4444-8444-444444444444',
        state: 'CLOSED',
        behavior: 'SUGGESTED',
        pagingState: 'NOT_REQUESTED',
        routeSnapshot: {key: 'fallback'},
        incidentId: undefined,
        candidateIncidentId: '55555555-5555-4555-8555-555555555555',
      },
      {
        ...baseGroup,
        id: '66666666-6666-4666-8666-666666666666',
        state: 'CLOSED',
        routeSnapshot: {},
        incidentId: undefined,
      },
    ])
  })

  it('filters open and historical groups and renders linkage details', async () => {
    renderPage()
    expect(await screen.findByText('Checkout')).toBeInTheDocument()
    expect(screen.getByRole('link', {name: /Checkout/})).toHaveAttribute(
      'href', '/on-call/alert-groups/$groupId'
    )
    expect(screen.getByRole('link', {name: /Checkout/})).toHaveAttribute('data-group-id', baseGroup.id)
    expect(screen.getByText('Linked incident')).toBeInTheDocument()
    expect(screen.queryByText('fallback')).not.toBeInTheDocument()
    expect(api.getAlertGroups).toHaveBeenCalledWith({limit: 200, offset: 0})
    fireEvent.click(screen.getByText('3 All'))
    expect(screen.getByText('fallback')).toBeInTheDocument()
    expect(screen.getByText('Candidate incident')).toBeInTheDocument()
    expect(screen.getByText(/Route 22222222/)).toBeInTheDocument()
    fireEvent.click(screen.getByText('1 Open'))
    expect(screen.queryByText('fallback')).not.toBeInTheDocument()
  })

  it('shows empty, loading, disabled, and nested detail states', async () => {
    api.getAlertGroups.mockResolvedValue([])
    const empty = renderPage()
    expect(await screen.findByText('No open alert groups')).toBeInTheDocument()
    empty.unmount()

    state.isLoading = true
    const loading = renderPage()
    expect(loading.container.querySelector('.animate-spin')).not.toBeNull()
    loading.unmount()

    Object.assign(state, {isLoading: false, enabled: false})
    const disabled = renderPage()
    expect(screen.getByText('Incidents unavailable')).toBeInTheDocument()
    disabled.unmount()

    Object.assign(state, {enabled: true, pathname: '/on-call/alert-groups/group-id'})
    renderPage()
    expect(screen.getByText('Group detail outlet')).toBeInTheDocument()
  })

  it('renders a retryable list failure', async () => {
    api.getAlertGroups.mockRejectedValueOnce(new Error('offline')).mockResolvedValueOnce([])
    renderPage()
    expect(await screen.findByText('Unable to load alert groups')).toBeInTheDocument()
    fireEvent.click(screen.getByText('Try again'))
    expect(await screen.findByText('No open alert groups')).toBeInTheDocument()
  })

  it('loads subsequent pages before presenting the complete list', async () => {
    const firstPage = Array.from({length: 200}, (_, index) => ({
      ...baseGroup,
      id: `00000000-0000-4000-8000-${String(index).padStart(12, '0')}`,
    }))
    api.getAlertGroups.mockResolvedValueOnce(firstPage).mockResolvedValueOnce([])
    renderPage()
    await screen.findAllByText('Checkout')
    expect(api.getAlertGroups).toHaveBeenNthCalledWith(2, {limit: 200, offset: 200})
  })
})
