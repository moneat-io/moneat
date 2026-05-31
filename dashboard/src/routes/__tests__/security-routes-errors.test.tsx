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
import {QueryClient, QueryClientProvider} from '@tanstack/react-query'
import {fireEvent, render, screen, waitFor, within} from '@testing-library/react'
import userEvent from '@testing-library/user-event'
import {makeRule, makeSignal} from '@/components/security/__tests__/fixtures'

const {mockApi, mockToast} = vi.hoisted(() => ({
  mockApi: {
    listDetectionRules: vi.fn(),
    createDetectionRule: vi.fn(),
    updateDetectionRule: vi.fn(),
    deleteDetectionRule: vi.fn(),
    previewDetectionRule: vi.fn(),
    listSignals: vi.fn(),
    getSignal: vi.fn(),
    triageSignal: vi.fn(),
    getVulnerabilitySummary: vi.fn(),
    listVulnerabilityInventory: vi.fn(),
    listVulnerabilityFindings: vi.fn(),
    exportVulnerabilitySbom: vi.fn(),
    get: vi.fn(),
  },
  mockToast: vi.fn(),
}))

vi.mock('@/lib/api', () => ({
  api: mockApi,
  // SecurityError renders this; keep the real passthrough behaviour.
  formatErrorForLogging: (error: unknown) => (error instanceof Error ? error.message : String(error)),
}))

vi.mock('@/hooks/useToast', () => ({useToast: () => ({toast: mockToast})}))

vi.mock('@tanstack/react-router', () => ({
  createFileRoute: () => (options: Record<string, unknown>) => ({...options, options}),
  Link: ({children, ...props}: {children: React.ReactNode}) => React.createElement('a', props, children),
}))

import {Route as DetectionsRouteImport} from '../security.detections'
import {Route as EventsRouteImport} from '../security.events'
import {Route as SignalsRouteImport} from '../security.signals'
import {Route as VulnerabilitiesRouteImport} from '../security.vulnerabilities'

type RouteLike = {component: React.ComponentType}
const DetectionsRoute = DetectionsRouteImport as unknown as RouteLike
const EventsRoute = EventsRouteImport as unknown as RouteLike
const SignalsRoute = SignalsRouteImport as unknown as RouteLike
const VulnerabilitiesRoute = VulnerabilitiesRouteImport as unknown as RouteLike

function renderRoute(route: RouteLike) {
  const Component = route.component
  const queryClient = new QueryClient({
    defaultOptions: {queries: {retry: false}, mutations: {retry: false}},
  })
  return render(
    <QueryClientProvider client={queryClient}>
      <Component />
    </QueryClientProvider>
  )
}

describe('security routes error states', () => {
  beforeEach(() => {
    vi.clearAllMocks()
    mockApi.listDetectionRules.mockResolvedValue({rules: [], total_count: 0})
    mockApi.listSignals.mockResolvedValue({signals: [], total_count: 0})
    mockApi.getVulnerabilitySummary.mockResolvedValue({
      package_count: 0,
      finding_count: 0,
      critical_count: 0,
      high_count: 0,
    })
    mockApi.listVulnerabilityInventory.mockResolvedValue({inventory: [], total_count: 0})
    mockApi.listVulnerabilityFindings.mockResolvedValue({findings: [], total_count: 0})
    mockApi.exportVulnerabilitySbom.mockResolvedValue('{}')
    mockApi.get.mockResolvedValue({events: [], totalCount: 0})
    mockApi.getSignal.mockResolvedValue({signal: makeSignal(), evidence: [], audit: [], sample_events: []})
  })

  it('detections: shows a distinct error state instead of "no rules" on query failure', async () => {
    mockApi.listDetectionRules.mockRejectedValue(new Error('rules boom'))
    renderRoute(DetectionsRoute)
    expect(await screen.findByText('Couldn’t load detection rules')).toBeInTheDocument()
    expect(screen.getByText('rules boom')).toBeInTheDocument()
    expect(screen.queryByText(/Detection Rules \(/)).not.toBeInTheDocument()
  })

  it('events: shows a distinct error state instead of "No security events" on query failure', async () => {
    mockApi.get.mockRejectedValue(new Error('events boom'))
    renderRoute(EventsRoute)
    expect(await screen.findByText('Couldn’t load security events')).toBeInTheDocument()
    expect(screen.getByText('events boom')).toBeInTheDocument()
    expect(screen.queryByText('No security events')).not.toBeInTheDocument()
  })

  it('signals: shows a distinct error state instead of an empty table on query failure', async () => {
    mockApi.listSignals.mockRejectedValue(new Error('signals boom'))
    renderRoute(SignalsRoute)
    expect(await screen.findByText('Couldn’t load signals')).toBeInTheDocument()
    expect(screen.getByText('signals boom')).toBeInTheDocument()
    expect(screen.queryByText(/^Signals \(/)).not.toBeInTheDocument()
  })

  it('vulnerabilities: summary failure shows an error instead of zero metric cards', async () => {
    mockApi.getVulnerabilitySummary.mockRejectedValue(new Error('summary boom'))
    renderRoute(VulnerabilitiesRoute)
    expect(await screen.findByText('Couldn’t load vulnerability summary')).toBeInTheDocument()
    expect(screen.getByText('summary boom')).toBeInTheDocument()
    expect(screen.queryByText('Packages')).not.toBeInTheDocument()
  })

  it('vulnerabilities: paginates inventory and findings with returned totals', async () => {
    mockApi.listVulnerabilityFindings.mockResolvedValue({
      findings: [makeFinding()],
      total_count: 51,
    })
    mockApi.listVulnerabilityInventory.mockResolvedValue({
      inventory: [makeInventory()],
      total_count: 51,
    })

    renderRoute(VulnerabilitiesRoute)

    expect(await screen.findByText('Findings (51)')).toBeInTheDocument()
    expect(screen.getByText('Inventory (51)')).toBeInTheDocument()
    expect(mockApi.listVulnerabilityFindings).toHaveBeenCalledWith({
      search: undefined,
      limit: 50,
      offset: 0,
    })
    expect(mockApi.listVulnerabilityInventory).toHaveBeenCalledWith({
      search: undefined,
      limit: 50,
      offset: 0,
    })

    fireEvent.click(screen.getByRole('button', {name: 'Findings next page'}))
    await waitFor(() =>
      expect(mockApi.listVulnerabilityFindings).toHaveBeenLastCalledWith({
        search: undefined,
        limit: 50,
        offset: 50,
      })
    )

    fireEvent.click(screen.getByRole('button', {name: 'Inventory next page'}))
    await waitFor(() =>
      expect(mockApi.listVulnerabilityInventory).toHaveBeenLastCalledWith({
        search: undefined,
        limit: 50,
        offset: 50,
      })
    )
  })

  it('vulnerabilities: finding rows open from the keyboard', async () => {
    const user = userEvent.setup()
    mockApi.listVulnerabilityFindings.mockResolvedValue({
      findings: [makeFinding()],
      total_count: 1,
    })
    renderRoute(VulnerabilitiesRoute)

    const openFinding = await screen.findByRole('button', {
      name: 'Open vulnerability finding CVE-2019-10744',
    })
    openFinding.focus()
    expect(openFinding).toHaveFocus()
    await user.keyboard('{Enter}')

    expect(await screen.findByText('Fixed in')).toBeInTheDocument()
  })

  it('signals detail drawer: shows an error state when the detail query fails', async () => {
    mockApi.listSignals.mockResolvedValue({signals: [makeSignal({id: 7})], total_count: 1})
    mockApi.getSignal.mockRejectedValue(new Error('detail boom'))
    renderRoute(SignalsRoute)
    // Open the drawer by selecting the row.
    fireEvent.click(await screen.findByText('Repeated failed logins'))
    expect(await screen.findByText('Couldn’t load signal details')).toBeInTheDocument()
    expect(screen.getByText('detail boom')).toBeInTheDocument()
  })

  it('detections: renders the list (not the error) on success', async () => {
    renderRoute(DetectionsRoute)
    expect(await screen.findByText(/Detection Rules \(/)).toBeInTheDocument()
    expect(screen.queryByText('Couldn’t load detection rules')).not.toBeInTheDocument()
  })

  it('detections: creating a rule calls createDetectionRule and toasts success', async () => {
    mockApi.createDetectionRule.mockResolvedValue({id: 5, name: 'Failed logins'})
    renderRoute(DetectionsRoute)
    await screen.findByText(/Detection Rules \(/)
    fireEvent.click(screen.getByRole('button', {name: /New rule/i}))
    fireEvent.change(await screen.findByLabelText('Name'), {target: {value: 'Failed logins'}})
    fireEvent.change(screen.getByPlaceholderText(/status:/), {target: {value: 'status:failed'}})
    fireEvent.click(screen.getByRole('button', {name: 'Create rule'}))
    await waitFor(() =>
      expect(mockApi.createDetectionRule).toHaveBeenCalledWith(
        expect.objectContaining({name: 'Failed logins', filter: 'status:failed'})
      )
    )
    await waitFor(() =>
      expect(mockToast).toHaveBeenCalledWith(expect.objectContaining({title: 'Rule saved'}))
    )
  })

  it('detections: toggling a rule enables it via updateDetectionRule', async () => {
    mockApi.listDetectionRules.mockResolvedValue({rules: [makeRule({id: 3, name: 'Brute force'})], total_count: 1})
    mockApi.updateDetectionRule.mockResolvedValue(makeRule({id: 3, enabled: true}))
    renderRoute(DetectionsRoute)
    fireEvent.click(await screen.findByRole('switch', {name: 'Toggle Brute force'}))
    await waitFor(() => expect(mockApi.updateDetectionRule).toHaveBeenCalledWith(3, {enabled: true}))
  })

  it('detections: confirming delete calls deleteDetectionRule and toasts', async () => {
    mockApi.listDetectionRules.mockResolvedValue({rules: [makeRule({id: 8, name: 'Brute force'})], total_count: 1})
    mockApi.deleteDetectionRule.mockResolvedValue(undefined)
    renderRoute(DetectionsRoute)
    fireEvent.click(await screen.findByRole('button', {name: 'Delete'}))
    // Open dialog adds a second "Delete"; confirm via the alertdialog's action button.
    const dialog = await screen.findByRole('alertdialog')
    fireEvent.click(within(dialog).getByRole('button', {name: 'Delete'}))
    await waitFor(() => expect(mockApi.deleteDetectionRule).toHaveBeenCalledWith(8))
    await waitFor(() =>
      expect(mockToast).toHaveBeenCalledWith(expect.objectContaining({title: 'Rule deleted'}))
    )
  })

  it('detections: editing opens the form prefilled and previews against the saved id', async () => {
    mockApi.listDetectionRules.mockResolvedValue({rules: [makeRule({id: 4, name: 'Brute force'})], total_count: 1})
    mockApi.updateDetectionRule.mockResolvedValue(makeRule({id: 4}))
    mockApi.previewDetectionRule.mockResolvedValue({match_count: 2, samples: [], window_seconds: 300})
    renderRoute(DetectionsRoute)
    fireEvent.click(await screen.findByRole('button', {name: 'Edit'}))
    expect(await screen.findByRole('button', {name: 'Save changes'})).toBeInTheDocument()
    // Preview on an already-saved rule updates then previews against its id.
    fireEvent.click(screen.getByRole('button', {name: 'Preview'}))
    await waitFor(() => expect(mockApi.previewDetectionRule).toHaveBeenCalledWith(4))
  })

  it('signals: triaging an open signal calls triageSignal and toasts success', async () => {
    const signal = makeSignal({id: 7, status: 'open'})
    mockApi.listSignals.mockResolvedValue({signals: [signal], total_count: 1})
    mockApi.getSignal.mockResolvedValue({signal, evidence: [], audit: [], sample_events: []})
    mockApi.triageSignal.mockResolvedValue(makeSignal({id: 7, status: 'under_review'}))
    renderRoute(SignalsRoute)
    fireEvent.click(await screen.findByText('Repeated failed logins'))
    fireEvent.click(await screen.findByRole('button', {name: 'Start review'}))
    await waitFor(() => expect(mockApi.triageSignal).toHaveBeenCalledWith(7, {status: 'under_review'}))
    await waitFor(() =>
      expect(mockToast).toHaveBeenCalledWith(expect.objectContaining({title: 'Signal updated'}))
    )
  })
})

function makeFinding() {
  return {
    signal_id: 42,
    advisory_id: 'GHSA-jf85-cpcp-j695',
    cve_id: 'CVE-2019-10744',
    package_name: 'lodash',
    package_version: '4.17.11',
    package_type: 'npm',
    ecosystem: 'npm',
    target_name: 'checkout',
    severity: 'critical',
    cvss_score: 9.8,
    fixed_version: '4.17.12',
    link: 'https://osv.dev/vulnerability/CVE-2019-10744',
    status: 'open',
    last_seen: '2026-05-31T00:00:00.000Z',
  }
}

function makeInventory() {
  return {
    package_name: 'lodash',
    package_version: '4.17.11',
    package_type: 'npm',
    ecosystem: 'npm',
    purl: 'pkg:npm/lodash@4.17.11',
    target_type: 'service',
    target_name: 'checkout',
    host: '',
    image_name: '',
    container_id: '',
    last_seen: '2026-05-31T00:00:00.000Z',
    finding_count: 1,
  }
}
