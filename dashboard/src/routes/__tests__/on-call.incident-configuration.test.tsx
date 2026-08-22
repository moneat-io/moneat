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
import {renderRoute} from '@/test/utils'

const {mockApi} = vi.hoisted(() => ({
  mockApi: {
    getIncidentTypes: vi.fn(),
    getIncidentCustomFields: vi.fn(),
    getIncidentForms: vi.fn(),
    getIncidentRoles: vi.fn(),
    createIncidentType: vi.fn(),
    createIncidentCustomField: vi.fn(),
    createIncidentForm: vi.fn(),
    createIncidentRole: vi.fn(),
  },
}))

vi.mock('@/lib/api', () => ({api: mockApi}))
vi.mock('@tanstack/react-router', () => ({
  createFileRoute: () => (options: Record<string, unknown>) => options,
}))

import {Route} from '../on-call.incident-configuration'

describe('IncidentConfiguration', () => {
  beforeEach(() => {
    vi.clearAllMocks()
    mockApi.getIncidentTypes.mockResolvedValue([
      {id: 't1', key: 'security', name: 'Security incident', version: 2, enabled: true, description: 'Handle breaches'},
    ])
    mockApi.getIncidentCustomFields.mockResolvedValue([
      {id: 'f1', key: 'region', name: 'Region', version: 1, valueType: 'SELECT', options: [{id: 'o1', value: 'us', label: 'US', position: 0}]},
    ])
    mockApi.getIncidentForms.mockResolvedValue([
      {id: 'fm1', stage: 'DECLARATION', name: 'Declaration form', version: 1, incidentTypeId: undefined, fields: []},
    ])
    mockApi.getIncidentRoles.mockResolvedValue([
      {id: 'r1', key: 'scribe', name: 'Scribe', version: 1, responsibilities: ['Record the timeline'], privateInstructions: 'Private scribe playbook', required: false, default: false},
    ])
    mockApi.createIncidentType.mockResolvedValue({id: 't2', key: 'outage', name: 'Outage', version: 1, enabled: true})
  })

  it('explains versioned snapshots and lists incident types', async () => {
    renderRoute(Route)
    expect(await screen.findByText(/versioned snapshots/)).toBeInTheDocument()
    expect(await screen.findByText('Security incident')).toBeInTheDocument()
    expect(screen.getByText('v2')).toBeInTheDocument()
  })

  it('creates an incident type through the dialog', async () => {
    renderRoute(Route)
    await screen.findByText('Security incident')
    fireEvent.click(screen.getByRole('button', {name: /New type/}))
    fireEvent.change(await screen.findByPlaceholderText('Security incident'), {target: {value: 'Outage'}})
    fireEvent.click(screen.getByRole('button', {name: 'Save type'}))
    await waitFor(() =>
      expect(mockApi.createIncidentType).toHaveBeenCalledWith(
        expect.objectContaining({name: 'Outage', key: 'outage'})
      )
    )
  })

  it('surfaces fields, forms, and roles (including authored private instructions)', async () => {
    renderRoute(Route)
    await screen.findByText('Security incident')

    fireEvent.mouseDown(screen.getByRole('tab', {name: 'Custom fields'}), {button: 0})
    expect(await screen.findByText('Region')).toBeInTheDocument()

    fireEvent.mouseDown(screen.getByRole('tab', {name: 'Forms'}), {button: 0})
    expect(await screen.findByText('Declaration form')).toBeInTheDocument()

    fireEvent.mouseDown(screen.getByRole('tab', {name: 'Roles'}), {button: 0})
    expect(await screen.findByText('Scribe')).toBeInTheDocument()
    // The configuration surface is where private instructions are authored, so
    // they are shown here (unlike the incident detail responders panel).
    expect(screen.getByText('Private scribe playbook')).toBeInTheDocument()
  })
})
