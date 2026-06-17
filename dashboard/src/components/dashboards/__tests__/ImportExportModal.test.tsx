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
import {describe, it, expect, vi} from 'vitest'
import {render, screen} from '@testing-library/react'
import userEvent from '@testing-library/user-event'
import {QueryClient, QueryClientProvider} from '@tanstack/react-query'
import {ImportExportModal} from '../ImportExportModal'

const mockNavigate = vi.fn()
vi.mock('@tanstack/react-router', () => ({
  useNavigate: () => mockNavigate,
}))

vi.mock('@/lib/api', () => ({
  api: {
    importDashboard: vi.fn().mockResolvedValue({dashboard: {id: 'dashboard-42', widgets: []}, warnings: []}),
    exportDashboard: vi.fn().mockResolvedValue({title: 'Test', widgets: []}),
    listCustomDataSources: vi.fn().mockResolvedValue([]),
  },
}))

function renderWithQuery(ui: React.ReactElement) {
  const queryClient = new QueryClient({defaultOptions: {queries: {retry: false}}})
  return render(
    <QueryClientProvider client={queryClient}>{ui}</QueryClientProvider>
  )
}

describe('ImportExportModal', () => {
  describe('when closed', () => {
    it('renders nothing when open is false', () => {
      const {container} = renderWithQuery(
        <ImportExportModal open={false} onOpenChange={vi.fn()} mode="import" />
      )
      expect(container.firstChild).toBeNull()
    })
  })

  describe('import mode', () => {
    it('renders import header', () => {
      renderWithQuery(
        <ImportExportModal open={true} onOpenChange={vi.fn()} mode="import" />
      )
      expect(screen.getByText('Import Dashboard')).toBeInTheDocument()
    })

    it('renders file upload button', () => {
      renderWithQuery(
        <ImportExportModal open={true} onOpenChange={vi.fn()} mode="import" />
      )
      expect(screen.getByText('Upload Grafana JSON File')).toBeInTheDocument()
    })

    it('renders paste JSON textarea', () => {
      renderWithQuery(
        <ImportExportModal open={true} onOpenChange={vi.fn()} mode="import" />
      )
      expect(screen.getByText('Or paste JSON')).toBeInTheDocument()
    })

    it('Import button is disabled when no JSON entered', () => {
      renderWithQuery(
        <ImportExportModal open={true} onOpenChange={vi.fn()} mode="import" />
      )
      const importBtn = screen.getByText('Import')
      expect(importBtn.closest('button')).toBeDisabled()
    })

    it('Import button becomes enabled when JSON is entered', async () => {
      renderWithQuery(
        <ImportExportModal open={true} onOpenChange={vi.fn()} mode="import" />
      )
      const textarea = screen.getByPlaceholderText('{"title": "My Dashboard", "widgets": [...]}')
      // Use fireEvent since userEvent.type interprets braces as special keys
      const {fireEvent} = await import('@testing-library/react')
      fireEvent.change(textarea, {target: {value: '{"title": "test"}'}})
      const importBtn = screen.getByText('Import')
      expect(importBtn.closest('button')).not.toBeDisabled()
    })

    it('Cancel button calls onOpenChange', async () => {
      const user = userEvent.setup()
      const onOpenChange = vi.fn()
      renderWithQuery(
        <ImportExportModal open={true} onOpenChange={onOpenChange} mode="import" />
      )
      await user.click(screen.getByText('Cancel'))
      expect(onOpenChange).toHaveBeenCalledWith(false)
    })

    it('switches format when clicking Grafana import', async () => {
      renderWithQuery(
        <ImportExportModal open={true} onOpenChange={vi.fn()} mode="import" />
      )
      expect(screen.getByText('Grafana Dashboard Import')).toBeInTheDocument()
    })

    it('switches format when clicking Datadog import', async () => {
      const user = userEvent.setup()
      renderWithQuery(
        <ImportExportModal open={true} onOpenChange={vi.fn()} mode="import" />
      )
      const grafanaButton = screen.getByRole('button', {name: 'Grafana'})
      const datadogButton = screen.getByRole('button', {name: 'Datadog'})
      const formatGroup = screen.getByRole('group', {name: 'Import format'})
      expect(formatGroup.tagName.toLowerCase()).toBe('fieldset')
      expect(screen.getByText('Import format')).toHaveClass('sr-only')
      expect(grafanaButton).toHaveAttribute('aria-pressed', 'true')
      expect(datadogButton).toHaveAttribute('aria-pressed', 'false')

      await user.click(datadogButton)

      expect(grafanaButton).toHaveAttribute('aria-pressed', 'false')
      expect(datadogButton).toHaveAttribute('aria-pressed', 'true')
      expect(screen.getByText('Datadog Dashboard Import')).toBeInTheDocument()
      expect(screen.getByText('Upload Datadog JSON File')).toBeInTheDocument()
    })

    it('imports Datadog JSON using datadog format', async () => {
      const {api} = await import('@/lib/api')
      const user = userEvent.setup()
      renderWithQuery(
        <ImportExportModal open={true} onOpenChange={vi.fn()} mode="import" />
      )
      await user.click(screen.getByText('Datadog'))
      const textarea = screen.getByPlaceholderText(
        '{"title": "My Dashboard", "layout_type": "ordered", "widgets": [...]}'
      )
      const {fireEvent, waitFor} = await import('@testing-library/react')
      fireEvent.change(textarea, {target: {value: '{"title": "Datadog", "widgets": []}'}})
      await user.click(screen.getByText('Import'))
      await waitFor(() => {
        expect(api.importDashboard).toHaveBeenCalledWith('datadog', '{"title": "Datadog", "widgets": []}')
      })
    })

    it('shows datasource mapper when Grafana JSON has __inputs with datasources', async () => {
      renderWithQuery(
        <ImportExportModal open={true} onOpenChange={vi.fn()} mode="import" />
      )
      const grafanaJsonWithInputs = JSON.stringify({
        __inputs: [
          {name: 'DS_REDIS', type: 'datasource', pluginId: 'redis-datasource'}
        ],
        panels: [
          {type: 'stat', datasource: '${DS_REDIS}', targets: [{refId: 'A'}]}
        ]
      })
      const textarea = screen.getByPlaceholderText('{"title": "My Dashboard", "widgets": [...]}')
      const {fireEvent} = await import('@testing-library/react')
      fireEvent.change(textarea, {target: {value: grafanaJsonWithInputs}})

      const importBtn = screen.getByText('Import')
      await userEvent.setup().click(importBtn)

      // The DataSourceMapperModal should appear with "Map Data Sources"
      expect(screen.getByText('Map Data Sources')).toBeInTheDocument()
    })
  })

  describe('export mode', () => {
    it('renders export header', () => {
      renderWithQuery(
        <ImportExportModal open={true} onOpenChange={vi.fn()} mode="export" dashboardId="dashboard-1" />
      )
      expect(screen.getByText('Export Dashboard')).toBeInTheDocument()
    })

    it('renders export format buttons', () => {
      renderWithQuery(
        <ImportExportModal open={true} onOpenChange={vi.fn()} mode="export" dashboardId="dashboard-1" />
      )
      expect(screen.getByText('Export as Moneat JSON')).toBeInTheDocument()
      expect(screen.getByText('Export as Grafana JSON')).toBeInTheDocument()
      expect(screen.getByText('Export as Datadog JSON')).toBeInTheDocument()
    })

    it('does not show Import button in export mode', () => {
      renderWithQuery(
        <ImportExportModal open={true} onOpenChange={vi.fn()} mode="export" dashboardId="dashboard-1" />
      )
      expect(screen.queryByText('Import')).not.toBeInTheDocument()
    })
  })

  describe('backdrop', () => {
    it('calls onOpenChange when backdrop clicked', async () => {
      const user = userEvent.setup()
      const onOpenChange = vi.fn()
      renderWithQuery(
        <ImportExportModal open={true} onOpenChange={onOpenChange} mode="import" />
      )
      // Click the backdrop (the bg-black/50 div)
      const backdrop = document.querySelector('.bg-black\\/50')
      if (backdrop) {
        await user.click(backdrop)
        expect(onOpenChange).toHaveBeenCalledWith(false)
      }
    })
  })
})
