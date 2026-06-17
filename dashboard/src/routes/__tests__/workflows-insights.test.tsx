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
import {fireEvent, render, screen, waitFor} from '@testing-library/react'

const {mockApi, mockToast} = vi.hoisted(() => ({
  mockApi: {
    isAuthenticated: vi.fn(() => true),
    checkAuth: vi.fn(),
    getWorkflowOverview: vi.fn(),
    getWorkflowUsage: vi.fn(),
    getWorkflowBlueprints: vi.fn(),
    getWorkflowAudit: vi.fn(),
    instantiateBlueprint: vi.fn(),
  },
  mockToast: vi.fn(),
}))

vi.mock('@/lib/api', () => ({api: mockApi}))

vi.mock('@/hooks/useToast', () => ({
  useToast: () => ({toast: mockToast}),
}))

vi.mock('@tanstack/react-router', () => ({
  createFileRoute: () => (options: Record<string, unknown>) => ({...options, options}),
  Link: ({children, ...props}: {children: React.ReactNode}) =>
    React.createElement('a', props, children),
  redirect: (opts: Record<string, unknown>) => ({...opts, __redirect: true}),
}))

import {Route as InsightsRouteImport} from '../workflows.insights'

const InsightsRoute = InsightsRouteImport as unknown as {
  component: React.ComponentType
  beforeLoad: () => Promise<unknown>
}

const overview = {
  total_workflows: 4,
  enabled_workflows: 3,
  published_workflows: 2,
  runs_last_30d: 80,
  success_rate: 0.9,
  failed_last_30d: 8,
  top_workflows: [{workflow_id: '11111111-1111-4111-8111-111111111111', name: 'Pager', run_count: 12}],
}

const usage = {period: '2026-05', used: 80, limit: 1000, remaining: 920, unlimited: false}

const blueprints = [
  {
    key: 'error-spike',
    name: 'Error spike alert',
    description: 'Notify on error spikes',
    category: 'alerting',
    trigger_name: 'issue.created',
    tags: ['errors'],
  },
]

const audit = [
  {id: 'a1', action: 'workflow.created', detail: {}, created_at: '2026-05-01T00:00:00Z'},
]
const WORKFLOW_RESOURCE_ID = '123e4567-e89b-12d3-a456-426614174307'

function renderInsights() {
  const Component = InsightsRoute.component
  const queryClient = new QueryClient({
    defaultOptions: {queries: {retry: false}, mutations: {retry: false}},
  })
  return render(
    <QueryClientProvider client={queryClient}>
      <Component />
    </QueryClientProvider>
  )
}

describe('workflows insights route', () => {
  beforeEach(() => {
    vi.clearAllMocks()
    mockApi.isAuthenticated.mockReturnValue(true)
    mockApi.getWorkflowOverview.mockResolvedValue(overview)
    mockApi.getWorkflowUsage.mockResolvedValue(usage)
    mockApi.getWorkflowBlueprints.mockResolvedValue(blueprints)
    mockApi.getWorkflowAudit.mockResolvedValue(audit)
    mockApi.instantiateBlueprint.mockResolvedValue({id: WORKFLOW_RESOURCE_ID, name: 'Error spike alert'})
  })

  it('guards the route via beforeLoad when unauthenticated', async () => {
    mockApi.isAuthenticated.mockReturnValue(false)
    mockApi.checkAuth.mockResolvedValue(false)
    await expect(InsightsRoute.beforeLoad()).rejects.toMatchObject({
      to: '/login',
      __redirect: true,
    })
  })

  it('passes the guard when authenticated', async () => {
    mockApi.isAuthenticated.mockReturnValue(true)
    await expect(InsightsRoute.beforeLoad()).resolves.toBeUndefined()
  })

  it('renders overview, usage, blueprints, and audit', async () => {
    renderInsights()
    expect(await screen.findByText('Success rate')).toBeInTheDocument()
    expect(screen.getByText('90%')).toBeInTheDocument()
    expect(await screen.findByText('Error spike alert')).toBeInTheDocument()
    expect(screen.getByText('2026-05')).toBeInTheDocument()
    expect(await screen.findByText('workflow.created')).toBeInTheDocument()
  })

  it('instantiates a blueprint and shows a success toast', async () => {
    renderInsights()
    const useButton = await screen.findByRole('button', {name: /Use blueprint/i})
    fireEvent.click(useButton)
    await waitFor(() => {
      expect(mockApi.instantiateBlueprint).toHaveBeenCalledWith('error-spike', {})
    })
    await waitFor(() => {
      expect(mockToast).toHaveBeenCalledWith(
        expect.objectContaining({title: 'Automation created from blueprint'})
      )
    })
  })

  it('surfaces section errors', async () => {
    mockApi.getWorkflowOverview.mockRejectedValue(new Error('overview boom'))
    mockApi.getWorkflowUsage.mockRejectedValue(new Error('usage boom'))
    mockApi.getWorkflowBlueprints.mockRejectedValue(new Error('blueprints boom'))
    mockApi.getWorkflowAudit.mockRejectedValue(new Error('audit boom'))
    renderInsights()
    expect(await screen.findByText('overview boom')).toBeInTheDocument()
    expect(await screen.findByText('usage boom')).toBeInTheDocument()
    expect(await screen.findByText('blueprints boom')).toBeInTheDocument()
    expect(await screen.findByText('audit boom')).toBeInTheDocument()
  })

  it('shows a toast when instantiation fails', async () => {
    mockApi.instantiateBlueprint.mockRejectedValue(new Error('limit reached'))
    renderInsights()
    const useButton = await screen.findByRole('button', {name: /Use blueprint/i})
    fireEvent.click(useButton)
    await waitFor(() => {
      expect(mockToast).toHaveBeenCalledWith(
        expect.objectContaining({title: 'Failed to use blueprint', variant: 'destructive'})
      )
    })
  })
})
