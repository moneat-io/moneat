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

import {fireEvent, render, screen, waitFor} from '@testing-library/react'
import {afterEach, describe, expect, it, vi} from 'vitest'
import type {WorkflowExportResponse} from '@/lib/api'
import {WorkflowExportImport} from '../WorkflowExportImport'

const originalClipboard = Object.getOwnPropertyDescriptor(globalThis.navigator, 'clipboard')

function stubClipboard(writeText: () => Promise<void>) {
  Object.defineProperty(globalThis.navigator, 'clipboard', {
    configurable: true,
    value: {writeText},
  })
}

const exportData: WorkflowExportResponse = {
  schema_version: 1,
  resource: {
    name: 'Pager',
    trigger_name: 'issue.created',
    enabled: true,
    graph: {nodes: [], edges: []},
    once_for_template: ['{{issue.id}}'],
  },
  terraform: 'resource "moneat_workflow" "pager" {}',
}

describe('WorkflowExportImport', () => {
  afterEach(() => {
    if (originalClipboard) {
      Object.defineProperty(globalThis.navigator, 'clipboard', originalClipboard)
    } else {
      delete (globalThis.navigator as {clipboard?: unknown}).clipboard
    }
  })

  it('prompts to export when no data is loaded and triggers onExport', () => {
    const onExport = vi.fn()
    render(<WorkflowExportImport onExport={onExport} onImport={vi.fn()} />)
    expect(screen.getByText(/Export this workflow as Terraform/i)).toBeInTheDocument()
    fireEvent.click(screen.getByRole('button', {name: 'Export'}))
    expect(onExport).toHaveBeenCalledTimes(1)
  })

  it('surfaces an export failure message', () => {
    render(<WorkflowExportImport exportFailed onExport={vi.fn()} onImport={vi.fn()} />)
    expect(screen.getByText(/Couldn't load the export/i)).toBeInTheDocument()
  })

  it('shows the Terraform output and copies it to the clipboard', async () => {
    const writeText = vi.fn().mockResolvedValue(undefined)
    stubClipboard(writeText)
    render(<WorkflowExportImport exportData={exportData} onExport={vi.fn()} onImport={vi.fn()} />)
    expect(screen.getByText(/moneat_workflow/)).toBeInTheDocument()

    fireEvent.click(screen.getByRole('button', {name: /Copy export/i}))
    await waitFor(() => {
      expect(writeText).toHaveBeenCalledWith(exportData.terraform)
    })
  })

  it('switches to the JSON format and copies the JSON resource', async () => {
    const writeText = vi.fn().mockResolvedValue(undefined)
    stubClipboard(writeText)
    render(<WorkflowExportImport exportData={exportData} onExport={vi.fn()} onImport={vi.fn()} />)

    fireEvent.click(screen.getByRole('button', {name: 'JSON'}))
    fireEvent.click(screen.getByRole('button', {name: /Copy export/i}))
    await waitFor(() => {
      expect(writeText).toHaveBeenCalledWith(JSON.stringify(exportData.resource, null, 2))
    })
  })

  it('validates pasted JSON before importing', () => {
    const onImport = vi.fn()
    render(<WorkflowExportImport onExport={vi.fn()} onImport={onImport} />)
    const textarea = screen.getByLabelText('Workflow JSON')

    fireEvent.change(textarea, {target: {value: '{bad'}})
    fireEvent.click(screen.getByRole('button', {name: 'Import'}))
    expect(screen.getByText('Invalid JSON.')).toBeInTheDocument()
    expect(onImport).not.toHaveBeenCalled()

    fireEvent.change(textarea, {
      target: {
        value: JSON.stringify({
          name: 'Imported',
          trigger_name: 'issue.created',
          graph: {nodes: [], edges: []},
        }),
      },
    })
    expect(screen.queryByText('Invalid JSON.')).not.toBeInTheDocument()
    fireEvent.click(screen.getByRole('button', {name: 'Import'}))
    expect(onImport).toHaveBeenCalledWith({
      name: 'Imported',
      trigger_name: 'issue.created',
      graph: {nodes: [], edges: []},
    })
  })

  it('shows pending states while exporting and importing', () => {
    render(
      <WorkflowExportImport
        exportData={null}
        exporting
        importing
        onExport={vi.fn()}
        onImport={vi.fn()}
      />
    )
    expect(screen.getByRole('button', {name: 'Export'})).toBeDisabled()
    // Import stays disabled until text is entered, even when importing.
    expect(screen.getByRole('button', {name: 'Import'})).toBeDisabled()
  })
})
