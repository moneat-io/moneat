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

import {describe, it, expect, vi, beforeEach} from 'vitest'
import {screen, fireEvent, waitFor} from '@testing-library/react'
import userEvent from '@testing-library/user-event'
import {api} from '@/lib/api'
import type {CustomDashboard, CustomDataSourceResponse} from '@/lib/api'
import {readDatadogPendingImportSignal} from '@/lib/datadogImportFunnel'
import {ImportExportModal} from '../ImportExportModal'
import {renderWithQueryClient, clearAuthStorage} from '@/test/utils'

const mockNavigate = vi.fn()
const mockTrackEvent = vi.hoisted(() => vi.fn())

vi.mock('@tanstack/react-router', () => ({
  useNavigate: () => mockNavigate,
}))

vi.mock('@/lib/analytics', () => ({
  trackEvent: mockTrackEvent,
}))

vi.mock('@/lib/api', () => ({
  api: {
    importDashboard: vi.fn(),
    exportDashboard: vi.fn(),
    listCustomDataSources: vi.fn().mockResolvedValue([]),
  },
}))

const mockedApi = vi.mocked(api)

const IMPORT_PLACEHOLDER = '{"title": "My Dashboard", "widgets": [...]}'
const DATADOG_IMPORT_PLACEHOLDER = '{"title": "My Dashboard", "layout_type": "ordered", "widgets": [...]}'

const makeDashboard = (id: number): CustomDashboard => ({
  id,
  org_id: 1,
  title: 'Imported dashboard',
  description: null,
  layout_type: 'grid',
  is_default: false,
  created_by: 1,
  created_at: '2026-01-01T00:00:00Z',
  updated_at: '2026-01-01T00:00:00Z',
  widgets: [],
})

const makeDataSource = (
  overrides: Partial<CustomDataSourceResponse> = {}
): CustomDataSourceResponse => ({
  id: 7,
  org_id: 1,
  name: 'My Redis',
  source_type: 'redis',
  description: '',
  host: '',
  port: 6379,
  extra_config: {},
  enabled: true,
  created_by: 1,
  created_at: '',
  updated_at: '',
  has_credentials: false,
  ...overrides,
})

function setImportJson(json: string) {
  const textarea = screen.getByPlaceholderText(IMPORT_PLACEHOLDER)
  fireEvent.change(textarea, {target: {value: json}})
}

function renderImportModal(props: {onOpenChange?: (open: boolean) => void} = {}) {
  const onOpenChange = props.onOpenChange ?? vi.fn()
  return renderWithQueryClient(
    <ImportExportModal open={true} onOpenChange={onOpenChange} mode="import" />
  )
}

function renderExportModal(props: {dashboardId?: number} = {}) {
  return renderWithQueryClient(
    <ImportExportModal open={true} onOpenChange={vi.fn()} mode="export" dashboardId={props.dashboardId} />
  )
}

async function submitImport(json: string) {
  setImportJson(json)
  await userEvent.setup().click(screen.getByText('Import'))
}

beforeEach(() => {
  globalThis.localStorage.clear()
  globalThis.sessionStorage.clear()
  clearAuthStorage()
  vi.clearAllMocks()
  mockedApi.importDashboard.mockResolvedValue({
    dashboard: makeDashboard(42),
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
      renderImportModal({onOpenChange})
      await submitImport('{"title":"test"}')
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
        dashboard: makeDashboard(99),
        warnings: ['Unsupported panel type: heatmap', 'Unknown variable: $region'],
      })
      renderImportModal()
      await submitImport('{"title":"test"}')
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
        dashboard: makeDashboard(99),
        warnings: ['Some warning'],
      })
      const onOpenChange = vi.fn()
      renderImportModal({onOpenChange})
      await submitImport('{"title":"test"}')

      await waitFor(() => {
        expect(screen.getByText('View Dashboard')).toBeInTheDocument()
      })
      await userEvent.setup().click(screen.getByText('View Dashboard'))
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
        dashboard: makeDashboard(10),
        warnings: ['warn'],
      })
      renderImportModal()
      await submitImport('{"title":"test"}')
      await waitFor(() => {
        expect(screen.getByText('Close')).toBeInTheDocument()
      })
      expect(screen.queryByText('Import')).not.toBeInTheDocument()
    })
  })

  // ──── Import: invalid JSON – falls through to mutation ────
  describe('import – invalid JSON input', () => {
    it('falls through to importMutation when JSON is unparseable', async () => {
      renderImportModal()
      await submitImport('not-json-at-all')

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
      renderImportModal()
      await submitImport(json)

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
      renderImportModal()
      await submitImport(json)

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
      renderImportModal()
      await submitImport(json)

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
      renderImportModal()
      await submitImport(json)

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
      renderImportModal()
      await submitImport(json)

      await waitFor(() => {
        expect(mockedApi.importDashboard).toHaveBeenCalledWith('grafana', json)
      })
    })
  })

  // ──── Import: Datadog format skips Grafana datasource mapper ────
  describe('import – Datadog format', () => {
    it('imports Datadog JSON directly with datadog format', async () => {
      const user = userEvent.setup()
      const json = JSON.stringify({
        title: 'Datadog dashboard',
        layout_type: 'ordered',
        widgets: [
          {
            definition: {
              type: 'timeseries',
              requests: [{q: 'avg:system.cpu.user{host:web01}'}],
            },
          },
        ],
      })
      renderImportModal()

      await user.click(screen.getByText('Datadog'))
      fireEvent.change(screen.getByPlaceholderText(DATADOG_IMPORT_PLACEHOLDER), {
        target: {value: json},
      })
      await user.click(screen.getByText('Import'))

      await waitFor(() => {
        expect(mockedApi.importDashboard).toHaveBeenCalledWith('datadog', json)
      })
      expect(mockTrackEvent).toHaveBeenCalledWith('datadog_import_started', {
        source: 'dashboard_import_modal',
        input_method: 'paste',
      })
      expect(mockTrackEvent).toHaveBeenCalledWith('datadog_import_succeeded', {
        warning_count: '0',
      })
      expect(mockTrackEvent).toHaveBeenCalledWith('datadog_import_warning_count', {
        count: '0',
      })
      expect(readDatadogPendingImportSignal()).toMatchObject({
        dashboardId: 42,
        warningCount: 0,
      })
      expect(screen.queryByText('Map Data Sources')).not.toBeInTheDocument()
    })
  })

  // ──── Import: auto-mapping with single matching custom datasource ────
  describe('import – auto-mapping', () => {
    it('auto-maps when exactly one custom datasource matches the expected type', async () => {
      mockedApi.listCustomDataSources.mockResolvedValue([
        makeDataSource(),
      ])
      const json = JSON.stringify({
        __inputs: [{name: 'DS_REDIS', type: 'datasource', pluginId: 'redis-datasource'}],
        panels: [{type: 'stat', datasource: '${DS_REDIS}', targets: []}],
      })
      renderImportModal()

      await waitFor(() => {
        // Wait for custom data sources query to resolve
      })

      await submitImport(json)

      // Mapper should appear since there are unmapped sources
      await waitFor(() => {
        expect(screen.getByText('Map Data Sources')).toBeInTheDocument()
      })
    })
  })

  // ──── Import: no file selected in file upload ────
  describe('import – file upload empty', () => {
    it('does nothing when file input fires without a file', () => {
      renderImportModal()
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
      renderImportModal()
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

      renderExportModal({dashboardId: 5})

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

      renderExportModal({dashboardId: 3})

      await userEvent.setup().click(screen.getByText('Export as Grafana JSON'))

      await waitFor(() => {
        expect(mockedApi.exportDashboard).toHaveBeenCalledWith(3, 'grafana')
      })
    })

    it('calls exportDashboard with datadog format', async () => {
      mockedApi.exportDashboard.mockResolvedValue({title: 'Datadog Export'})

      globalThis.URL.createObjectURL = vi.fn().mockReturnValue('blob:mock')
      globalThis.URL.revokeObjectURL = vi.fn()

      renderExportModal({dashboardId: 4})

      await userEvent.setup().click(screen.getByText('Export as Datadog JSON'))

      await waitFor(() => {
        expect(mockedApi.exportDashboard).toHaveBeenCalledWith(4, 'datadog')
      })
    })
  })

  // ──── Export: no dashboardId returns early ────
  describe('export – no dashboardId', () => {
    it('does not call exportDashboard when dashboardId is undefined', async () => {
      renderExportModal()

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

      renderExportModal({dashboardId: 1})

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
      renderImportModal()
      expect(screen.getByText('Experimental')).toBeInTheDocument()
    })

    it('does not show Experimental badge in export mode', () => {
      renderExportModal({dashboardId: 1})
      expect(screen.queryByText('Experimental')).not.toBeInTheDocument()
    })

    it('shows correct description text for import mode', () => {
      renderImportModal()
      expect(screen.getByText('Import a dashboard from Grafana JSON format')).toBeInTheDocument()
    })

    it('shows correct description text for export mode', () => {
      renderExportModal({dashboardId: 1})
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
      renderImportModal()
      await submitImport(json)

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
      renderImportModal()
      await submitImport(json)

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
      renderImportModal()
      await submitImport(json)

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
      renderImportModal()
      await submitImport(json)

      await waitFor(() => {
        expect(mockedApi.importDashboard).toHaveBeenCalled()
      })
    })
  })
})
