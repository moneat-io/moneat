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
import {screen, waitFor} from '@testing-library/react'
import {clearAuthStorage, renderRoute} from '@/test/utils'

const ALERT_ID = 'aaaaaaaa-aaaa-4aaa-8aaa-aaaaaaaaaaaa'
const INCIDENT_ID = 'bbbbbbbb-bbbb-4bbb-8bbb-bbbbbbbbbbbb'

const {mockApi, mockCapabilities, mockPathname, mockParams, mockNavigate} = vi.hoisted(() => ({
  mockApi: {
    getCurrentUser: vi.fn(),
    getNativeIncidentCapabilities: vi.fn(),
    getOnCallIncidents: vi.fn(),
    getOnCallIncident: vi.fn(),
    getIncidentTypes: vi.fn(),
    getIncidentCustomFields: vi.fn(),
    getIncidentForms: vi.fn(),
    getIncidentRoles: vi.fn(),
    getAlert: vi.fn(),
    getAlertTimeline: vi.fn(),
    viewAlert: vi.fn(),
    acknowledgeAlert: vi.fn(),
    resolveAlert: vi.fn(),
    markAlertUnavailable: vi.fn(),
    addAlertNote: vi.fn(),
    declareIncidentFromAlert: vi.fn(),
  },
  mockCapabilities: {current: {enabled: false, environment: 'production', state: 'DISABLED', externalProviderPassthroughAffected: false}},
  mockPathname: {current: '/on-call'},
  mockParams: {current: {} as Record<string, string>},
  mockNavigate: vi.fn(),
}))

vi.mock('@/lib/api', () => ({api: mockApi}))
vi.mock('@/hooks/useToast', () => ({useToast: () => ({toast: vi.fn()})}))
vi.mock('@tanstack/react-router', () => ({
  createFileRoute: () => (options: Record<string, unknown>) => ({
    ...options,
    useParams: () => mockParams.current,
  }),
  Link: ({children, to, ...props}: {children: React.ReactNode; to?: string}) =>
    React.createElement('a', {href: typeof to === 'string' ? to : '#', ...props}, children),
  Outlet: () => <div data-testid="outlet" />,
  useNavigate: () => mockNavigate,
  useRouterState: (opts?: {select?: (s: {location: {pathname: string}}) => unknown}) => {
    const state = {location: {pathname: mockPathname.current}}
    return opts?.select ? opts.select(state) : state
  },
}))

import {Route as OnCallShellRoute} from '../on-call'
import {Route as IncidentsRoute} from '../on-call.incidents'
import {Route as IncidentDetailRoute} from '../on-call.incidents.$incidentId'
import {Route as IncidentConfigurationRoute} from '../on-call.incident-configuration'
import {Route as AlertDetailRoute} from '../on-call.alerts.$alertId'

const ENABLED = {enabled: true, environment: 'production', state: 'ENABLED', externalProviderPassthroughAffected: false}
const DISABLED = {enabled: false, environment: 'production', state: 'DISABLED', externalProviderPassthroughAffected: false}

const triggeredAlert = {
  id: ALERT_ID,
  title: 'Payments outage',
  description: 'Payments API is down',
  priority: 'P0',
  status: 'TRIGGERED',
  alertSource: 'monitor',
  triggeredAt: '2026-06-05T12:00:00.000Z',
}

describe('native incident rollout gating', () => {
  beforeEach(() => {
    vi.clearAllMocks()
    clearAuthStorage()
    mockCapabilities.current = DISABLED
    mockPathname.current = '/on-call'
    mockParams.current = {}
    mockApi.getCurrentUser.mockResolvedValue({orgId: 'organization-one'})
    mockApi.getNativeIncidentCapabilities.mockImplementation(() =>
      Promise.resolve(mockCapabilities.current)
    )
  })

  describe('on-call shell tabs', () => {
    it('hides Incidents and Configuration when the rollout is disabled', async () => {
      mockCapabilities.current = DISABLED
      renderRoute(OnCallShellRoute)

      expect(await screen.findByText('Alerts')).toBeInTheDocument()
      await waitFor(() => expect(mockApi.getNativeIncidentCapabilities).toHaveBeenCalled())

      expect(screen.getByText('Overview')).toBeInTheDocument()
      expect(screen.getByText('Teams')).toBeInTheDocument()
      expect(screen.getByText('Schedules')).toBeInTheDocument()
      expect(screen.getByText('Escalation Policies')).toBeInTheDocument()
      expect(screen.queryByText('Incidents')).toBeNull()
      expect(screen.queryByText('Configuration')).toBeNull()
    })

    it('shows Incidents and Configuration when the rollout is enabled', async () => {
      mockCapabilities.current = ENABLED
      renderRoute(OnCallShellRoute)

      expect(await screen.findByText('Incidents')).toBeInTheDocument()
      expect(screen.getByText('Configuration')).toBeInTheDocument()
      expect(screen.getByText('Alerts')).toBeInTheDocument()
    })
  })

  describe('incident list route', () => {
    it('renders an unavailable state and never fetches incidents when disabled', async () => {
      mockCapabilities.current = DISABLED
      mockPathname.current = '/on-call/incidents'
      renderRoute(IncidentsRoute)

      expect(await screen.findByText(/not available/i)).toBeInTheDocument()
      await waitFor(() => expect(mockApi.getNativeIncidentCapabilities).toHaveBeenCalled())
      expect(mockApi.getOnCallIncidents).not.toHaveBeenCalled()
      expect(screen.queryByRole('button', {name: /Declare incident/})).toBeNull()
    })
  })

  describe('incident detail route', () => {
    it('renders an unavailable state and never fetches the incident when disabled', async () => {
      mockCapabilities.current = DISABLED
      mockParams.current = {incidentId: INCIDENT_ID}
      renderRoute(IncidentDetailRoute)

      expect(await screen.findByText(/not available/i)).toBeInTheDocument()
      await waitFor(() => expect(mockApi.getNativeIncidentCapabilities).toHaveBeenCalled())
      expect(mockApi.getOnCallIncident).not.toHaveBeenCalled()
    })
  })

  describe('incident configuration route', () => {
    it('renders an unavailable state and never fetches configuration when disabled', async () => {
      mockCapabilities.current = DISABLED
      renderRoute(IncidentConfigurationRoute)

      expect(await screen.findByText(/not available/i)).toBeInTheDocument()
      await waitFor(() => expect(mockApi.getNativeIncidentCapabilities).toHaveBeenCalled())
      expect(mockApi.getIncidentTypes).not.toHaveBeenCalled()
      expect(mockApi.getIncidentCustomFields).not.toHaveBeenCalled()
      expect(mockApi.getIncidentForms).not.toHaveBeenCalled()
      expect(mockApi.getIncidentRoles).not.toHaveBeenCalled()
    })
  })

  describe('alert detail declare control', () => {
    beforeEach(() => {
      mockParams.current = {alertId: ALERT_ID}
      mockApi.getAlert.mockResolvedValue(triggeredAlert)
      mockApi.getAlertTimeline.mockResolvedValue([])
      mockApi.viewAlert.mockResolvedValue(undefined)
    })

    it('hides Declare Incident when the rollout is disabled', async () => {
      mockCapabilities.current = DISABLED
      renderRoute(AlertDetailRoute)

      expect(await screen.findByText('Payments outage')).toBeInTheDocument()
      await waitFor(() => expect(mockApi.getNativeIncidentCapabilities).toHaveBeenCalled())
      expect(screen.queryByText('Declare Incident')).toBeNull()
    })

    it('shows Declare Incident when the rollout is enabled', async () => {
      mockCapabilities.current = ENABLED
      renderRoute(AlertDetailRoute)

      expect(await screen.findByText('Declare Incident')).toBeInTheDocument()
    })
  })
})
