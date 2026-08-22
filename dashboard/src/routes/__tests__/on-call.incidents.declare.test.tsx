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
import {beforeEach, describe, expect, it, vi} from 'vitest'
import {fireEvent, screen, waitFor} from '@testing-library/react'
import {renderRoute} from '@/test/utils'

const NEW_INCIDENT_ID = 'f0000000-0000-4000-8000-000000000001'

const {mockApi, mockNavigate} = vi.hoisted(() => ({
  mockApi: {
    getOnCallIncidents: vi.fn(),
    declareOnCallIncident: vi.fn(),
    getIncidentTypes: vi.fn(),
    getIncidentForms: vi.fn(),
  },
  mockNavigate: vi.fn(),
}))

vi.mock('@/lib/api', () => ({api: mockApi}))
vi.mock('@tanstack/react-router', () => ({
  createFileRoute: () => (options: Record<string, unknown>) => options,
  Link: ({children, ...props}: {children: React.ReactNode}) => React.createElement('a', props, children),
  Outlet: () => <div data-testid="outlet" />,
  useNavigate: () => mockNavigate,
  useRouterState: ({select}: {select: (s: {location: {pathname: string}}) => unknown}) =>
    select({location: {pathname: '/on-call/incidents'}}),
}))

import {Route} from '../on-call.incidents'

describe('standalone incident declaration', () => {
  beforeEach(() => {
    vi.clearAllMocks()
    mockApi.getOnCallIncidents.mockResolvedValue([])
    mockApi.getIncidentTypes.mockResolvedValue([])
    mockApi.getIncidentForms.mockResolvedValue([])
    mockApi.declareOnCallIncident.mockResolvedValue({id: NEW_INCIDENT_ID})
  })

  it('declares a standalone LIVE incident and navigates to it', async () => {
    renderRoute(Route)
    expect(await screen.findByText('Incidents')).toBeInTheDocument()

    fireEvent.click(screen.getByRole('button', {name: /Declare incident/}))
    fireEvent.change(await screen.findByPlaceholderText('Short, specific summary of what is happening'), {
      target: {value: 'Search latency spike'},
    })
    const submitButtons = screen.getAllByRole('button', {name: 'Declare incident'})
    fireEvent.click(submitButtons[submitButtons.length - 1])

    await waitFor(() =>
      expect(mockApi.declareOnCallIncident).toHaveBeenCalledWith(
        expect.objectContaining({
          title: 'Search latency spike',
          mode: 'LIVE',
          visibility: 'ORGANIZATION',
          initialStatus: 'ACTIVE',
        })
      )
    )
    await waitFor(() =>
      expect(mockNavigate).toHaveBeenCalledWith({
        to: '/on-call/incidents/$incidentId',
        params: {incidentId: NEW_INCIDENT_ID},
      })
    )
  })
})
