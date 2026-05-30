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
import type {WorkflowResponse} from '@/lib/api'

const {mockApi, mockToast} = vi.hoisted(() => ({
  mockApi: {
    exportWorkflow: vi.fn(),
    getWorkflowAuditForWorkflow: vi.fn(),
    importWorkflow: vi.fn(),
  },
  mockToast: vi.fn(),
}))

vi.mock('@/lib/api', () => ({api: mockApi}))
vi.mock('@/hooks/useToast', () => ({useToast: () => ({toast: mockToast})}))

import {WorkflowActivityDrawer} from '../WorkflowActivityDrawer'

const workflow = {id: 3, name: 'Pager'} as WorkflowResponse

const exportData = {
  schema_version: 1,
  resource: {
    name: 'Pager',
    trigger_name: 'issue.created',
    enabled: true,
    graph: {nodes: [], edges: []},
    once_for_template: [],
  },
  terraform: 'resource "moneat_workflow" "pager" {}',
}

const audit = [
  {id: 'a1', action: 'workflow.run', detail: {}, created_at: '2026-05-01T00:00:00Z'},
]

function renderDrawer(props: Partial<React.ComponentProps<typeof WorkflowActivityDrawer>> = {}) {
  const queryClient = new QueryClient({
    defaultOptions: {queries: {retry: false}, mutations: {retry: false}},
  })
  return render(
    <QueryClientProvider client={queryClient}>
      <WorkflowActivityDrawer
        workflow={workflow}
        open
        onOpenChange={vi.fn()}
        {...props}
      />
    </QueryClientProvider>
  )
}

describe('WorkflowActivityDrawer', () => {
  beforeEach(() => {
    vi.clearAllMocks()
    mockApi.exportWorkflow.mockResolvedValue(exportData)
    mockApi.getWorkflowAuditForWorkflow.mockResolvedValue(audit)
    mockApi.importWorkflow.mockResolvedValue({id: 4, name: 'Imported'})
  })

  it('does not fetch while closed', () => {
    renderDrawer({open: false})
    expect(mockApi.exportWorkflow).not.toHaveBeenCalled()
    expect(mockApi.getWorkflowAuditForWorkflow).not.toHaveBeenCalled()
  })

  it('renders export output and the activity timeline when open', async () => {
    renderDrawer()
    await waitFor(() => {
      expect(mockApi.getWorkflowAuditForWorkflow).toHaveBeenCalledWith(3, 20)
    })
    expect(await screen.findByText(/moneat_workflow/)).toBeInTheDocument()
    expect(await screen.findByText('workflow.run')).toBeInTheDocument()
  })

  it('imports pasted JSON and shows a success toast', async () => {
    renderDrawer()
    const textarea = await screen.findByLabelText('Workflow JSON')
    fireEvent.change(textarea, {
      target: {
        value: JSON.stringify({
          name: 'Imported',
          trigger_name: 'issue.created',
          graph: {nodes: [], edges: []},
        }),
      },
    })
    fireEvent.click(screen.getByRole('button', {name: 'Import'}))
    await waitFor(() => {
      expect(mockApi.importWorkflow).toHaveBeenCalled()
    })
    await waitFor(() => {
      expect(mockToast).toHaveBeenCalledWith(
        expect.objectContaining({title: 'Workflow imported'})
      )
    })
  })

  it('shows a destructive toast when import fails', async () => {
    mockApi.importWorkflow.mockRejectedValue(new Error('bad import'))
    renderDrawer()
    const textarea = await screen.findByLabelText('Workflow JSON')
    fireEvent.change(textarea, {
      target: {
        value: JSON.stringify({
          name: 'Imported',
          trigger_name: 'issue.created',
          graph: {nodes: [], edges: []},
        }),
      },
    })
    fireEvent.click(screen.getByRole('button', {name: 'Import'}))
    await waitFor(() => {
      expect(mockToast).toHaveBeenCalledWith(
        expect.objectContaining({title: 'Import failed', variant: 'destructive'})
      )
    })
  })

  it('prompts to select a workflow when none is provided', () => {
    renderDrawer({workflow: null})
    expect(screen.getByText(/Select a workflow first/i)).toBeInTheDocument()
    expect(mockApi.exportWorkflow).not.toHaveBeenCalled()
  })
})
