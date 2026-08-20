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

import type {ReactNode} from 'react'
import {screen, waitFor, within} from '@testing-library/react'
import userEvent from '@testing-library/user-event'
import {beforeEach, describe, expect, it, vi} from 'vitest'
import {http, HttpResponse} from 'msw'
import {server} from '@/test/mocks/server'
import {clearAuthStorage, renderRoute} from '@/test/utils'

const {mockSearch, mockNavigate} = vi.hoisted(() => ({
  mockSearch: {current: {} as {host?: string}},
  mockNavigate: vi.fn(),
}))

vi.mock('@tanstack/react-router', () => ({
  createFileRoute: () => (options: Record<string, unknown>) => ({
    ...options,
    component: options.component,
    fullPath: '/monitoring/alerts',
    useSearch: () => mockSearch.current,
    useNavigate: () => mockNavigate,
  }),
  useNavigate: () => mockNavigate,
  Link: ({children}: {children: ReactNode}) => <a href="/settings">{children}</a>,
}))

import {Route as AlertRulesRoute} from '../monitoring.alerts'

const API_BASE = 'http://localhost:8080/v1'
const HOST_ID = 'host-5'
const OTHER_HOST_ID = 'host-9'
const ALERT_ID = 'alert-10'

function hostRow(id: string, hostname: string, isOnline = true) {
  return {
    id,
    hostname,
    os: 'linux',
    platform: 'ubuntu',
    processor: 'x86_64',
    cpuCores: 4,
    memoryTotalKb: 8_000_000,
    agentVersion: '1.0.0',
    tags: {},
    firstSeenAt: '2026-01-01T00:00:00Z',
    lastSeenAt: '2026-01-02T00:00:00Z',
    isOnline,
  }
}

function alertRow(overrides: Record<string, unknown> = {}) {
  return {
    id: ALERT_ID,
    host_id: HOST_ID,
    scope: 'host',
    metric: 'cpu_percent',
    condition: '>',
    threshold: 90,
    duration_seconds: 60,
    enabled: true,
    alert_priority: null,
    last_triggered_at: null,
    created_at: 1700000000,
    ...overrides,
  }
}

function installResizeObserverMock() {
  if (!('ResizeObserver' in globalThis)) {
    ;(globalThis as {ResizeObserver?: unknown}).ResizeObserver = class {
      observe() {}
      unobserve() {}
      disconnect() {}
    }
  }
}

function installPointerCaptureMocks() {
  if (!HTMLElement.prototype.hasPointerCapture) {
    HTMLElement.prototype.hasPointerCapture = () => false
  }
  if (!HTMLElement.prototype.setPointerCapture) {
    HTMLElement.prototype.setPointerCapture = () => {}
  }
  if (!HTMLElement.prototype.releasePointerCapture) {
    HTMLElement.prototype.releasePointerCapture = () => {}
  }
  if (!HTMLElement.prototype.scrollIntoView) {
    HTMLElement.prototype.scrollIntoView = () => {}
  }
}

function applyToConfig(
  config: Record<string, unknown>,
  patch: Record<string, unknown>
): Record<string, unknown> {
  const patchList = (list: unknown) =>
    (list as Record<string, unknown>[]).map((rule) => ({...rule, ...patch}))
  return {
    ...config,
    global_alerts: patchList(config.global_alerts),
    system_alerts: patchList(config.system_alerts),
    effective_alerts: patchList(config.effective_alerts),
  }
}

/** Registers the hosts list plus an alert config, returning captured writes. */
function installHandlers(
  config: Record<string, unknown>,
  hosts = [hostRow(HOST_ID, 'web-01'), hostRow(OTHER_HOST_ID, 'db-01', false)]
) {
  const captured: {
    createBody?: Record<string, unknown>
    updateBody?: Record<string, unknown>
    scopeBody?: Record<string, unknown>
    deleted?: boolean
  } = {}
  let current = structuredClone(config)
  server.use(
    http.get(`${API_BASE}/hosts`, () =>
      HttpResponse.json({hosts, totalCount: hosts.length})
    ),
    http.get(`${API_BASE}/monitor/hosts/:hostId/alerts/config`, () => HttpResponse.json(current)),
    http.post(`${API_BASE}/monitor/hosts/:hostId/alerts`, async ({request}) => {
      captured.createBody = (await request.json()) as Record<string, unknown>
      return HttpResponse.json(alertRow())
    }),
    http.put(`${API_BASE}/monitor/hosts/:hostId/alerts/scope`, async ({request}) => {
      captured.scopeBody = (await request.json()) as Record<string, unknown>
      return new HttpResponse(null, {status: 204})
    }),
    http.delete(`${API_BASE}/monitor/hosts/:hostId/alerts/:alertId`, () => {
      captured.deleted = true
      return new HttpResponse(null, {status: 204})
    }),
    http.put(`${API_BASE}/monitor/hosts/:hostId/alerts/:alertId`, async ({request}) => {
      const body = (await request.json()) as Record<string, unknown>
      captured.updateBody = body
      // Apply the write so the refetch agrees with the optimistic update.
      current = applyToConfig(current, body)
      return new HttpResponse(null, {status: 204})
    })
  )
  return captured
}

describe('monitoring alert rules page', () => {
  beforeEach(() => {
    clearAuthStorage()
    installPointerCaptureMocks()
    installResizeObserverMock()
    mockSearch.current = {}
    mockNavigate.mockReset()
  })

  it('lists each load-average window as its own rule', async () => {
    installHandlers({
      scope: 'global',
      global_alerts: [
        alertRow({id: 'a1', scope: 'global', metric: 'load_1', threshold: 4}),
        alertRow({id: 'a2', scope: 'global', metric: 'load_5', threshold: 3}),
        alertRow({id: 'a3', scope: 'global', metric: 'load_15', threshold: 2}),
      ],
      system_alerts: [],
      effective_alerts: [],
    })

    renderRoute(AlertRulesRoute)

    expect(await screen.findByText('Load average (1m)')).toBeInTheDocument()
    expect(screen.getByText('Load average (5m)')).toBeInTheDocument()
    expect(screen.getByText('Load average (15m)')).toBeInTheDocument()
  })

  it('sends the sustained duration under the wire key the API declares', async () => {
    const user = userEvent.setup()
    const captured = installHandlers({
      scope: 'global',
      global_alerts: [],
      system_alerts: [],
      effective_alerts: [],
    })

    renderRoute(AlertRulesRoute)

    await user.click(await screen.findByRole('button', {name: /new rule/i}))

    const duration = await screen.findByLabelText(/sustained for/i)
    await user.clear(duration)
    await user.type(duration, '15')

    await user.click(screen.getByRole('button', {name: /create rule/i}))

    await waitFor(() => expect(captured.createBody).toBeDefined())
    // A camelCase `durationSeconds` is silently dropped by the backend, which
    // is what made saved durations appear to reset to zero.
    expect(captured.createBody).toMatchObject({duration_seconds: 900})
    expect(captured.createBody).not.toHaveProperty('durationSeconds')
  })

  it('reflects a rule toggle before the refetch lands', async () => {
    const user = userEvent.setup()
    const captured = installHandlers({
      scope: 'global',
      global_alerts: [alertRow({scope: 'global'})],
      system_alerts: [],
      effective_alerts: [],
    })

    renderRoute(AlertRulesRoute)

    const toggle = await screen.findByRole('switch', {name: /disable cpu usage/i})
    expect(toggle).toBeChecked()

    await user.click(toggle)

    await waitFor(() => expect(toggle).not.toBeChecked())
    expect(captured.updateBody).toEqual({enabled: false})
  })

  it('shows a host following shared defaults as read-only', async () => {
    mockSearch.current = {host: HOST_ID}
    installHandlers({
      scope: 'global',
      global_alerts: [alertRow({scope: 'global'})],
      system_alerts: [],
      effective_alerts: [],
    })

    renderRoute(AlertRulesRoute)

    expect(await screen.findByText(/follows the shared defaults/i)).toBeInTheDocument()
    expect(screen.getByRole('button', {name: /new rule/i})).toBeDisabled()
    expect(screen.getByRole('switch', {name: /disable cpu usage/i})).toBeDisabled()
  })

  it('lets a host with custom rules edit them', async () => {
    mockSearch.current = {host: HOST_ID}
    installHandlers({
      scope: 'host',
      global_alerts: [],
      system_alerts: [alertRow()],
      effective_alerts: [alertRow()],
    })

    renderRoute(AlertRulesRoute)

    expect(await screen.findByText('CPU usage')).toBeInTheDocument()
    expect(screen.queryByText(/follows the shared defaults/i)).not.toBeInTheDocument()
    expect(screen.getByRole('button', {name: /new rule/i})).toBeEnabled()
  })

  it('selects a host profile from the rail', async () => {
    const user = userEvent.setup()
    installHandlers({
      scope: 'global',
      global_alerts: [],
      system_alerts: [],
      effective_alerts: [],
    })

    renderRoute(AlertRulesRoute)

    const rail = await screen.findByRole('navigation')
    await user.click(await within(rail).findByText('db-01'))

    expect(mockNavigate).toHaveBeenCalledWith({search: {host: OTHER_HOST_ID}})
  })

  it('edits an existing rule and sends the changed threshold', async () => {
    const user = userEvent.setup()
    const captured = installHandlers({
      scope: 'global',
      global_alerts: [alertRow({scope: 'global'})],
      system_alerts: [],
      effective_alerts: [],
    })

    renderRoute(AlertRulesRoute)

    await user.click(await screen.findByRole('button', {name: /edit cpu usage rule/i}))

    const threshold = await screen.findByLabelText(/threshold/i)
    await user.clear(threshold)
    await user.type(threshold, '95')
    await user.click(screen.getByRole('button', {name: /save changes/i}))

    await waitFor(() => expect(captured.updateBody).toBeDefined())
    expect(captured.updateBody).toMatchObject({threshold: 95, duration_seconds: 60})
  })

  it('deletes a rule after the confirmation dialog', async () => {
    const user = userEvent.setup()
    const captured = installHandlers({
      scope: 'global',
      global_alerts: [alertRow({scope: 'global'})],
      system_alerts: [],
      effective_alerts: [],
    })

    renderRoute(AlertRulesRoute)

    await user.click(await screen.findByRole('button', {name: /delete cpu usage rule/i}))
    expect(await screen.findByText(/will stop being evaluated/i)).toBeInTheDocument()

    await user.click(screen.getByRole('button', {name: /delete rule/i}))

    await waitFor(() => expect(captured.deleted).toBe(true))
  })

  it('switches a host from shared defaults to its own rules', async () => {
    const user = userEvent.setup()
    mockSearch.current = {host: HOST_ID}
    const captured = installHandlers({
      scope: 'global',
      global_alerts: [alertRow({scope: 'global'})],
      system_alerts: [],
      effective_alerts: [],
    })

    renderRoute(AlertRulesRoute)

    await user.click(await screen.findByRole('button', {name: 'Custom'}))

    await waitFor(() => expect(captured.scopeBody).toEqual({scope: 'host'}))
  })

  it('offers rule creation from the empty state', async () => {
    const user = userEvent.setup()
    installHandlers({
      scope: 'global',
      global_alerts: [],
      system_alerts: [],
      effective_alerts: [],
    })

    renderRoute(AlertRulesRoute)

    expect(await screen.findByText('No rules yet')).toBeInTheDocument()
    const emptyStateButton = screen.getAllByRole('button', {name: /new rule/i})[1]
    await user.click(emptyStateButton)

    expect(await screen.findByRole('button', {name: /create rule/i})).toBeInTheDocument()
  })

  it('tells the user when no hosts are reporting', async () => {
    installHandlers(
      {scope: 'global', global_alerts: [], system_alerts: [], effective_alerts: []},
      []
    )

    renderRoute(AlertRulesRoute)

    expect(await screen.findByText(/no hosts are reporting yet/i)).toBeInTheDocument()
    expect(screen.getByText(/connect a host with the agent/i)).toBeInTheDocument()
  })
})
