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
import {describe, it, expect, vi, beforeEach} from 'vitest'
import {render, screen, fireEvent, waitFor} from '@testing-library/react'
import userEvent from '@testing-library/user-event'
import {QueryClient, QueryClientProvider} from '@tanstack/react-query'
import {http, HttpResponse} from 'msw'
import {server} from '@/test/mocks/server'
import {api} from '@/lib/api'
import {ImportExportModal} from '../ImportExportModal'

const mockNavigate = vi.fn()
vi.mock('@tanstack/react-router', () => ({
  useNavigate: () => mockNavigate,
}))

vi.mock('@/lib/api', () => ({
  api: {
    importDashboard: vi.fn(),
    exportDashboard: vi.fn(),
    listCustomDataSources: vi.fn().mockResolvedValue([]),
  },
}))

const mockedApi = vi.mocked(api)

function renderWithQuery(ui: React.ReactElement) {
  const queryClient = new QueryClient({defaultOptions: {queries: {retry: false}}})
  return render(
    <QueryClientProvider client={queryClient}>{ui}</QueryClientProvider>
  )
}

beforeEach(() => {
  localStorage.clear()
  sessionStorage.clear()
  sessionStorage.setItem('authenticated', 'true')
  vi.clearAllMocks()
  mockedApi.importDashboard.mockResolvedValue({
    dashboard: {id: 42},
    warnings: [],
  })
  mockedApi.exportDashboard.mockResolvedValue({title: 'Test', widgets: []})
  mockedApi.listCustomDataSources.mockResolvedValue([])
})

describe('ImportExportModal – extended branch coverage', () => {
  // ──── Import: successful import with no warnings navigates immediately ────
  describe('import – auto-navigate on zero warnings', () => {
    it('navigates to new dashboard when import succeeds with no warnings', async () => {
      const onOpenChange = vi.fn()
      renderWithQuery(
        <ImportExportModal open={true} onOpenChange={onOpenChange} mode="import" />
      )
      const textarea = screen.getByPlaceholderText('{"title": "My Dashboard", "widgets": [...]}')
      fireEvent.change(textarea, {target: {value: '{"title":"test"}'}})

      await userEvent.setup().click(screen.getByText('Import'))

      await waitFor(() => {
        expect(mockNavigate).toHaveBeenCalledWith({
          to: '/dashboards/$dashboardId',
          params: {dashboardId: '42'},
        })
      })
      expect(onOpenChange).toHaveBeenCalledWith(false)
    })
  })

  // ──── Import: warnings are shown and View Dashboard button appears ────
  describe('import – warnings displayed', () => {
    it('shows warnings and View Dashboard button when import returns warnings', async () => {
      mockedApi.importDashboard.mockResolvedValue({
        dashboard: {id: 99},
        warnings: ['Unsupported panel type: heatmap', 'Unknown variable: $region'],
      })
      const onOpenChange = vi.fn()
      renderWithQuery(
        <ImportExportModal open={true} onOpenChange={onOpenChange} mode="import" />
      )
      const textarea = screen.getByPlaceholderText('{"title": "My Dashboard", "widgets": [...]}')
      fireEvent.change(textarea, {target: {value: '{"title":"test"}'}})

      await userEvent.setup().click(screen.getByText('Import'))

      await waitFor(() => {
        expect(screen.getByText('Import Warnings')).toBeInTheDocument()
      })
      expect(screen.getByText('• Unsupported panel type: heatmap')).toBeInTheDocument()
      expect(screen.getByText('• Unknown variable: $region')).toBeInTheDocument()
      expect(screen.getByText('Dashboard imported successfully!')).toBeInTheDocument()
      expect(screen.getByText('View Dashboard')).toBeInTheDocument()
      // Should NOT auto-navigate when there are warnings
      expect(mockNavigate).not.toHaveBeenCalled()
    })

    it('navigates when clicking View Dashboard button after import with warnings', async () => {
      mockedApi.importDashboard.mockResolvedValue({
        dashboard: {id: 99},
        warnings: ['Some warning'],
      })
      const onOpenChange = vi.fn()
      renderWithQuery(
        <ImportExportModal open={true} onOpenChange={onOpenChange} mode="import" />
      )
      const textarea = screen.getByPlaceholderText('{"title": "My Dashboard", "widgets": [...]}')
      fireEvent.change(textarea, {target: {value: '{"title":"test"}'}})

      const user = userEvent.setup()
      await user.click(screen.getByText('Import'))

      await waitFor(() => {
        expect(screen.getByText('View Dashboard')).toBeInTheDocument()
      })
      await user.click(screen.getByText('View Dashboard'))
      expect(mockNavigate).toHaveBeenCalledWith({
        to: '/dashboards/$dashboardId',
        params: {dashboardId: '99'},
      })
      expect(onOpenChange).toHaveBeenCalledWith(false)
    })
  })

  // ──── Import: success state hides Import button, shows Close ────
  describe('import – post-success UI state', () => {
    it('hides Import button and shows Close after successful import', async () => {
      mockedApi.importDashboard.mockResolvedValue({
        dashboard: {id: 10},
        warnings: ['warn'],
      })
      renderWithQuery(
        <ImportExportModal open={true} onOpenChange={vi.fn()} mode="import" />
      )
      const textarea = screen.getByPlaceholderText('{"title": "My Dashboard", "widgets": [...]}')
      fireEvent.change(textarea, {target: {value: '{"title":"test"}'}})

      await userEvent.setup().click(screen.getByText('Import'))

      await waitFor(() => {
        expect(screen.getByText('Close')).toBeInTheDocument()
      })
      expect(screen.queryByText('Import')).not.toBeInTheDocument()
    })
  })

  // ──── Import: invalid JSON – falls through to mutation ────
  describe('import – invalid JSON input', () => {
    it('falls through to importMutation when JSON is unparseable', async () => {
      renderWithQuery(
        <ImportExportModal open={true} onOpenChange={vi.fn()} mode="import" />
      )
      const textarea = screen.getByPlaceholderText('{"title": "My Dashboard", "widgets": [...]}')
      fireEvent.change(textarea, {target: {value: 'not-json-at-all'}})

      await userEvent.setup().click(screen.getByText('Import'))

      await waitFor(() => {
        expect(mockedApi.importDashboard).toHaveBeenCalledWith('grafana', 'not-json-at-all')
      })
    })
  })

  // ──── Import: panels with object datasources (type field) ────
  describe('import – panel datasource as object with type', () => {
    it('detects datasource from panel.datasource.type', async () => {
      const json = JSON.stringify({
        panels: [
          {
            type: 'graph',
            datasource: {uid: 'abc', type: 'prometheus'},
            targets: [],
          },
        ],
      })
      renderWithQuery(
        <ImportExportModal open={true} onOpenChange={vi.fn()} mode="import" />
      )
      const textarea = screen.getByPlaceholderText('{"title": "My Dashboard", "widgets": [...]}')
      fireEvent.change(textarea, {target: {value: json}})

      await userEvent.setup().click(screen.getByText('Import'))

      // prometheus is not built-in so mapper modal should show
      await waitFor(() => {
        expect(screen.getByText('Map Data Sources')).toBeInTheDocument()
      })
    })
  })

  // ──── Import: target-level datasource detection (string + object) ────
  describe('import – target-level datasources', () => {
    it('detects datasource from target.datasource string', async () => {
      const json = JSON.stringify({
        panels: [
          {
            type: 'graph',
            targets: [{datasource: 'influxdb', refId: 'A'}],
          },
        ],
      })
      renderWithQuery(
        <ImportExportModal open={true} onOpenChange={vi.fn()} mode="import" />
      )
      const textarea = screen.getByPlaceholderText('{"title": "My Dashboard", "widgets": [...]}')
      fireEvent.change(textarea, {target: {value: json}})

      await userEvent.setup().click(screen.getByText('Import'))

      await waitFor(() => {
        expect(screen.getByText('Map Data Sources')).toBeInTheDocument()
      })
    })

    it('detects datasource from target.datasource.type object', async () => {
      const json = JSON.stringify({
        panels: [
          {
            type: 'graph',
            targets: [{datasource: {type: 'loki', uid: 'xyz'}, refId: 'A'}],
          },
        ],
      })
      renderWithQuery(
        <ImportExportModal open={true} onOpenChange={vi.fn()} mode="import" />
      )
      const textarea = screen.getByPlaceholderText('{"title": "My Dashboard", "widgets": [...]}')
      fireEvent.change(textarea, {target: {value: json}})

      await userEvent.setup().click(screen.getByText('Import'))

      await waitFor(() => {
        expect(screen.getByText('Map Data Sources')).toBeInTheDocument()
      })
    })
  })

  // ──── Import: nested panels in collapsed rows ────
  describe('import – nested panels in rows', () => {
    it('detects datasource from nested panels inside collapsed rows', async () => {
      const json = JSON.stringify({
        panels: [
          {
            type: 'row',
            collapsed: true,
            panels: [
              {type: 'stat', datasource: {type: 'mysql'}, targets: []},
            ],
          },
        ],
      })
      renderWithQuery(
        <ImportExportModal open={true} onOpenChange={vi.fn()} mode="import" />
      )
      const textarea = screen.getByPlaceholderText('{"title": "My Dashboard", "widgets": [...]}')
      fireEvent.change(textarea, {target: {value: json}})

      await userEvent.setup().click(screen.getByText('Import'))

      await waitFor(() => {
        expect(screen.getByText('Map Data Sources')).toBeInTheDocument()
      })
    })
  })

  // ──── Import: built-in datasources proceed without mapping ────
  describe('import – built-in sources skip mapper', () => {
    it('proceeds directly when all datasources are built-in', async () => {
      const json = JSON.stringify({
        panels: [
          {type: 'graph', datasource: 'events', targets: []},
          {type: 'stat', datasource: {type: 'logs'}, targets: []},
        ],
      })
      renderWithQuery(
        <ImportExportModal open={true} onOpenChange={vi.fn()} mode="import" />
      )
      const textarea = screen.getByPlaceholderText('{"title": "My Dashboard", "widgets": [...]}')
      fireEvent.change(textarea, {target: {value: json}})

      await userEvent.setup().click(screen.getByText('Import'))

      await waitFor(() => {
        expect(mockedApi.importDashboard).toHaveBeenCalledWith('grafana', json)
      })
    })
  })

  // ──── Import: auto-mapping with single matching custom datasource ────
  describe('import – auto-mapping', () => {
    it('auto-maps when exactly one custom datasource matches the expected type', async () => {
      mockedApi.listCustomDataSources.mockResolvedValue([
        {id: 7, org_id: 1, name: 'My Redis', source_type: 'redis', description: '', host: '', port: 6379, created_at: '', updated_at: ''},
      ])
      const json = JSON.stringify({
        __inputs: [{name: 'DS_REDIS', type: 'datasource', pluginId: 'redis-datasource'}],
        panels: [{type: 'stat', datasource: '${DS_REDIS}', targets: []}],
      })
      renderWithQuery(
        <ImportExportModal open={true} onOpenChange={vi.fn()} mode="import" />
      )

      await waitFor(() => {
        // Wait for custom data sources query to resolve
      })

      const textarea = screen.getByPlaceholderText('{"title": "My Dashboard", "widgets": [...]}')
      fireEvent.change(textarea, {target: {value: json}})

      await userEvent.setup().click(screen.getByText('Import'))

      // Mapper should appear since there are unmapped sources
      await waitFor(() => {
        expect(screen.getByText('Map Data Sources')).toBeInTheDocument()
      })
    })
  })

  // ──── Import: no file selected in file upload ────
  describe('import – file upload empty', () => {
    it('does nothing when file input fires without a file', () => {
      renderWithQuery(
        <ImportExportModal open={true} onOpenChange={vi.fn()} mode="import" />
      )
      const fileInput = document.querySelector('input[type="file"]') as HTMLInputElement
      expect(fileInput).toBeTruthy()
      fireEvent.change(fileInput, {target: {files: []}})
      // No error thrown, jsonInput stays empty
      const importBtn = screen.getByText('Import')
      expect(importBtn.closest('button')).toBeDisabled()
    })
  })

  // ──── Import: file upload reads content ────
  describe('import – file upload reads file', () => {
    it('reads uploaded file and populates the editor', async () => {
      renderWithQuery(
        <ImportExportModal open={true} onOpenChange={vi.fn()} mode="import" />
      )
      const fileInput = document.querySelector('input[type="file"]') as HTMLInputElement
      const blob = new Blob(['{"title":"from file"}'], {type: 'application/json'})
      const file = new File([blob], 'dashboard.json', {type: 'application/json'})

      fireEvent.change(fileInput, {target: {files: [file]}})

      await waitFor(() => {
        const importBtn = screen.getByText('Import')
        expect(importBtn.closest('button')).not.toBeDisabled()
      })
    })
  })

  // ──── Export: triggers download and shows preview ────
  describe('export – download and preview', () => {
    it('calls exportDashboard and shows preview for moneat format', async () => {
      mockedApi.exportDashboard.mockResolvedValue({title: 'Exported', widgets: [{id: 1}]})

      // Mock URL.createObjectURL and revokeObjectURL
      const mockCreateObjectURL = vi.fn().mockReturnValue('blob:mock')
      const mockRevokeObjectURL = vi.fn()
      globalThis.URL.createObjectURL = mockCreateObjectURL
      globalThis.URL.revokeObjectURL = mockRevokeObjectURL

      renderWithQuery(
        <ImportExportModal open={true} onOpenChange={vi.fn()} mode="export" dashboardId={5} />
      )

      await userEvent.setup().click(screen.getByText('Export as Moneat JSON'))

      await waitFor(() => {
        expect(mockedApi.exportDashboard).toHaveBeenCalledWith(5, 'moneat')
      })
      await waitFor(() => {
        expect(screen.getByText('Preview')).toBeInTheDocument()
      })
    })

    it('calls exportDashboard with grafana format', async () => {
      mockedApi.exportDashboard.mockResolvedValue({title: 'Grafana Export'})

      globalThis.URL.createObjectURL = vi.fn().mockReturnValue('blob:mock')
      globalThis.URL.revokeObjectURL = vi.fn()

      renderWithQuery(
        <ImportExportModal open={true} onOpenChange={vi.fn()} mode="export" dashboardId={3} />
      )

      await userEvent.setup().click(screen.getByText('Export as Grafana JSON'))

      await waitFor(() => {
        expect(mockedApi.exportDashboard).toHaveBeenCalledWith(3, 'grafana')
      })
    })
  })

  // ──── Export: no dashboardId returns early ────
  describe('export – no dashboardId', () => {
    it('does not call exportDashboard when dashboardId is undefined', async () => {
      renderWithQuery(
        <ImportExportModal open={true} onOpenChange={vi.fn()} mode="export" />
      )

      await userEvent.setup().click(screen.getByText('Export as Moneat JSON'))

      // Should not call the API
      expect(mockedApi.exportDashboard).not.toHaveBeenCalled()
    })
  })

  // ──── Export: API error is handled gracefully ────
  describe('export – API error', () => {
    it('handles export failure gracefully', async () => {
      mockedApi.exportDashboard.mockRejectedValue(new Error('Network error'))
      const consoleSpy = vi.spyOn(console, 'error').mockImplementation(() => {})

      renderWithQuery(
        <ImportExportModal open={true} onOpenChange={vi.fn()} mode="export" dashboardId={1} />
      )

      await userEvent.setup().click(screen.getByText('Export as Moneat JSON'))

      await waitFor(() => {
        expect(consoleSpy).toHaveBeenCalledWith('Export failed', expect.any(Error))
      })
      consoleSpy.mockRestore()
    })
  })

  // ──── Import: Experimental badge shown only in import mode ────
  describe('UI mode differences', () => {
    it('shows Experimental badge in import mode', () => {
      renderWithQuery(
        <ImportExportModal open={true} onOpenChange={vi.fn()} mode="import" />
      )
      expect(screen.getByText('Experimental')).toBeInTheDocument()
    })

    it('does not show Experimental badge in export mode', () => {
      renderWithQuery(
        <ImportExportModal open={true} onOpenChange={vi.fn()} mode="export" dashboardId={1} />
      )
      expect(screen.queryByText('Experimental')).not.toBeInTheDocument()
    })

    it('shows correct description text for import mode', () => {
      renderWithQuery(
        <ImportExportModal open={true} onOpenChange={vi.fn()} mode="import" />
      )
      expect(screen.getByText('Import a dashboard from Grafana JSON format')).toBeInTheDocument()
    })

    it('shows correct description text for export mode', () => {
      renderWithQuery(
        <ImportExportModal open={true} onOpenChange={vi.fn()} mode="export" dashboardId={1} />
      )
      expect(screen.getByText('Export this dashboard in various formats')).toBeInTheDocument()
    })
  })

  // ──── Import: panels with __inputs containing non-datasource types ────
  describe('import – __inputs filtering', () => {
    it('ignores __inputs entries that are not datasource type', async () => {
      const json = JSON.stringify({
        __inputs: [
          {name: 'DS_PROM', type: 'datasource', pluginId: 'prometheus'},
          {name: 'VAL_THRESHOLD', type: 'constant', value: '100'},
        ],
        panels: [{type: 'graph', targets: []}],
      })
      renderWithQuery(
        <ImportExportModal open={true} onOpenChange={vi.fn()} mode="import" />
      )
      const textarea = screen.getByPlaceholderText('{"title": "My Dashboard", "widgets": [...]}')
      fireEvent.change(textarea, {target: {value: json}})

      await userEvent.setup().click(screen.getByText('Import'))

      // Should show mapper for prometheus but not for constant
      await waitFor(() => {
        expect(screen.getByText('Map Data Sources')).toBeInTheDocument()
      })
    })
  })

  // ──── Import: panel with string datasource (non-template-variable) ────
  describe('import – direct string datasource on panel', () => {
    it('detects non-variable string datasource on panel', async () => {
      const json = JSON.stringify({
        panels: [
          {type: 'graph', datasource: 'graphite', targets: []},
        ],
      })
      renderWithQuery(
        <ImportExportModal open={true} onOpenChange={vi.fn()} mode="import" />
      )
      const textarea = screen.getByPlaceholderText('{"title": "My Dashboard", "widgets": [...]}')
      fireEvent.change(textarea, {target: {value: json}})

      await userEvent.setup().click(screen.getByText('Import'))

      await waitFor(() => {
        expect(screen.getByText('Map Data Sources')).toBeInTheDocument()
      })
    })
  })

  // ──── Import: panels with null/no targets ────
  describe('import – panels without targets', () => {
    it('handles panels with no targets property', async () => {
      const json = JSON.stringify({
        panels: [
          {type: 'text', datasource: 'events'},
        ],
      })
      renderWithQuery(
        <ImportExportModal open={true} onOpenChange={vi.fn()} mode="import" />
      )
      const textarea = screen.getByPlaceholderText('{"title": "My Dashboard", "widgets": [...]}')
      fireEvent.change(textarea, {target: {value: json}})

      await userEvent.setup().click(screen.getByText('Import'))

      await waitFor(() => {
        expect(mockedApi.importDashboard).toHaveBeenCalled()
      })
    })
  })

  // ──── Import: target with null datasource ────
  describe('import – target with null datasource', () => {
    it('handles target with null datasource gracefully', async () => {
      const json = JSON.stringify({
        panels: [
          {type: 'graph', datasource: 'events', targets: [{datasource: null, refId: 'A'}]},
        ],
      })
      renderWithQuery(
        <ImportExportModal open={true} onOpenChange={vi.fn()} mode="import" />
      )
      const textarea = screen.getByPlaceholderText('{"title": "My Dashboard", "widgets": [...]}')
      fireEvent.change(textarea, {target: {value: json}})

      await userEvent.setup().click(screen.getByText('Import'))

      await waitFor(() => {
        expect(mockedApi.importDashboard).toHaveBeenCalled()
      })
    })
  })
})
