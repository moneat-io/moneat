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

import {render, waitFor} from '@testing-library/react'
import {QueryClient, QueryClientProvider} from '@tanstack/react-query'
import {act, type ComponentType, type ReactNode} from 'react'
import {afterEach, beforeEach, describe, expect, it, vi} from 'vitest'
import type {CreateWidgetRequest, CustomDashboard, DashboardVariable, DashboardWidget} from '@/lib/api'

const routerMocks = vi.hoisted(() => ({
  navigate: vi.fn(),
  useParams: vi.fn(() => ({dashboardId: 'dashboard-1'})),
  useSearch: vi.fn(() => ({edit: true})),
}))

const apiMocks = vi.hoisted(() => ({
  getDashboard: vi.fn(),
  getProjects: vi.fn(),
  getDataSources: vi.fn(),
  updateDashboard: vi.fn(),
  toggleDashboardFavorite: vi.fn(),
  duplicateDashboard: vi.fn(),
  deleteDashboard: vi.fn(),
  setDefaultDashboard: vi.fn(),
  resolveVariableOptions: vi.fn(),
}))

const capturedProps = vi.hoisted(() => ({
  toolbar: undefined as Record<string, unknown> | undefined,
  grid: undefined as Record<string, unknown> | undefined,
  widgetPanel: undefined as Record<string, unknown> | undefined,
  variableDialog: undefined as Record<string, unknown> | undefined,
  clipboard: undefined as Record<string, unknown> | undefined,
}))

const mockedModuleIds = [
  '@tanstack/react-router',
  '@/lib/api',
  '@/components/dashboards/DashboardToolbar',
  '@/components/dashboards/DashboardGrid',
  '@/components/dashboards/WidgetConfigPanel',
  '@/components/dashboards/ImportExportModal',
  '@/components/dashboards/DataSourceMapperModal',
  '@/components/dashboards/VariableSettingsDialog',
  '@/components/dashboards/useWidgetClipboard',
  '@/components/ui/alert-dialog',
] as const

async function loadDashboardViewPage(): Promise<ComponentType> {
  vi.resetModules()
  vi.doMock('@tanstack/react-router', () => ({
    createFileRoute: () => (config: Record<string, unknown>) => ({
      ...config,
      useParams: routerMocks.useParams,
      useSearch: routerMocks.useSearch,
    }),
    useNavigate: () => routerMocks.navigate,
  }))

  vi.doMock('@/lib/api', () => ({api: apiMocks}))

  vi.doMock('@/components/dashboards/DashboardToolbar', () => ({
    DashboardToolbar: (props: Record<string, unknown>) => {
      capturedProps.toolbar = props
      return null
    },
  }))

  vi.doMock('@/components/dashboards/DashboardGrid', () => ({
    DashboardGrid: (props: Record<string, unknown>) => {
      capturedProps.grid = props
      return null
    },
  }))

  vi.doMock('@/components/dashboards/WidgetConfigPanel', () => ({
    WidgetConfigPanel: (props: Record<string, unknown>) => {
      capturedProps.widgetPanel = props
      return null
    },
  }))

  vi.doMock('@/components/dashboards/ImportExportModal', () => ({
    ImportExportModal: () => null,
  }))

  vi.doMock('@/components/dashboards/DataSourceMapperModal', () => ({
    DataSourceMapperModal: () => null,
  }))

  vi.doMock('@/components/dashboards/VariableSettingsDialog', () => ({
    VariableSettingsDialog: (props: Record<string, unknown>) => {
      capturedProps.variableDialog = props
      return null
    },
  }))

  vi.doMock('@/components/dashboards/useWidgetClipboard', () => ({
    useWidgetClipboard: (props: Record<string, unknown>) => {
      capturedProps.clipboard = props
    },
  }))

  vi.doMock('@/components/ui/alert-dialog', () => ({
    AlertDialog: ({children}: {children: ReactNode}) => children,
    AlertDialogAction: ({children}: {children: ReactNode}) => children,
    AlertDialogCancel: ({children}: {children: ReactNode}) => children,
    AlertDialogContent: ({children}: {children: ReactNode}) => children,
    AlertDialogDescription: ({children}: {children: ReactNode}) => children,
    AlertDialogFooter: ({children}: {children: ReactNode}) => children,
    AlertDialogHeader: ({children}: {children: ReactNode}) => children,
    AlertDialogTitle: ({children}: {children: ReactNode}) => children,
  }))

  const routeModule = await import('@/routes/dashboards.$dashboardId')
  return routeModule.DashboardViewPage
}

function makeWidget(overrides: Partial<DashboardWidget> = {}): DashboardWidget {
  return {
    id: 'widget-1',
    dashboard_id: 'dashboard-1',
    title: 'Errors',
    widget_type: 'timeseries',
    grid_x: 0,
    grid_y: 0,
    grid_w: 6,
    grid_h: 4,
    query_configs: [{
      dataSource: 'events',
      metrics: [{function: 'count', alias: 'count'}],
      groupBy: [{field: 'timestamp', type: 'time', interval: 'auto'}],
      filters: [],
      limit: 100,
      timeRange: {from: 'now-24h', to: 'now'},
    }],
    display_config: {},
    sort_order: 0,
    ...overrides,
  }
}

function makeDashboard(overrides: Partial<CustomDashboard> = {}): CustomDashboard {
  return {
    id: 'dashboard-1',
    org_id: 'org-1',
    project_id: null,
    folder_id: null,
    title: 'Operations',
    description: null,
    layout_type: 'grid',
    is_default: false,
    is_favorited: false,
    variables: [],
    owner_name: null,
    created_by: 'user-1',
    created_at: '2026-01-01T00:00:00Z',
    updated_at: '2026-01-01T00:00:00Z',
    widgets: [makeWidget()],
    ...overrides,
  }
}

function makeUpdateWidget(overrides: Partial<CreateWidgetRequest> = {}): CreateWidgetRequest {
  return {
    title: 'Errors',
    widget_type: 'timeseries',
    grid_x: 0,
    grid_y: 0,
    grid_w: 6,
    grid_h: 4,
    query_configs: [makeWidget().query_configs[0]],
    display_config: {},
    sort_order: 0,
    ...overrides,
  }
}

function renderPage(DashboardViewPage: ComponentType) {
  const queryClient = new QueryClient({
    defaultOptions: {
      queries: {retry: false},
      mutations: {retry: false},
    },
  })
  render(
    <QueryClientProvider client={queryClient}>
      <DashboardViewPage />
    </QueryClientProvider>,
  )
  return queryClient
}

describe('DashboardViewPage', () => {
  afterEach(() => {
    mockedModuleIds.forEach((moduleId) => {
      vi.doUnmock(moduleId)
    })
    vi.resetModules()
  })

  beforeEach(() => {
    vi.clearAllMocks()
    capturedProps.toolbar = undefined
    capturedProps.grid = undefined
    capturedProps.widgetPanel = undefined
    capturedProps.variableDialog = undefined
    capturedProps.clipboard = undefined
    routerMocks.useParams.mockReturnValue({dashboardId: 'dashboard-1'})
    routerMocks.useSearch.mockReturnValue({edit: true})
    apiMocks.getDashboard.mockResolvedValue(makeDashboard())
    apiMocks.getProjects.mockResolvedValue([])
    apiMocks.getDataSources.mockResolvedValue([])
    apiMocks.updateDashboard.mockResolvedValue(makeDashboard())
    apiMocks.toggleDashboardFavorite.mockResolvedValue(makeDashboard())
    apiMocks.duplicateDashboard.mockResolvedValue(makeDashboard({id: 'dashboard-2'}))
    apiMocks.deleteDashboard.mockResolvedValue(undefined)
    apiMocks.setDefaultDashboard.mockResolvedValue(makeDashboard({is_default: true}))
    apiMocks.resolveVariableOptions.mockResolvedValue({})
  })

  it('routes dashboard edit callbacks through the stable update mutation', async () => {
    const DashboardViewPage = await loadDashboardViewPage()
    const queryClient = renderPage(DashboardViewPage)

    await waitFor(() => {
      expect(capturedProps.toolbar).toBeDefined()
      expect(capturedProps.grid).toBeDefined()
      expect(capturedProps.clipboard).toBeDefined()
      expect(capturedProps.variableDialog).toBeDefined()
    })

    act(() => {
      ;(capturedProps.toolbar?.onTitleChange as (title: string) => void)('Renamed')
      ;(capturedProps.grid?.onLayoutChange as (widgets: CreateWidgetRequest[]) => void)([
        makeUpdateWidget({grid_w: 8}),
      ])
      ;(capturedProps.variableDialog?.onSave as (variables: DashboardVariable[]) => void)([
        {name: 'service', type: 'query', options: ['api']},
      ])
      ;(capturedProps.grid?.onWidgetDelete as (widgetId: string) => void)('widget-1')
      ;(capturedProps.clipboard?.onPasteWidget as (widget: CreateWidgetRequest) => void)(
        makeUpdateWidget({title: 'Pasted widget'}),
      )
      ;(capturedProps.clipboard?.onUndo as () => void)()
      ;(capturedProps.toolbar?.onAddWidget as () => void)()
    })

    await waitFor(() => {
      expect(capturedProps.widgetPanel).toBeDefined()
    })

    act(() => {
      ;(capturedProps.widgetPanel?.onSave as (widget: DashboardWidget) => void)({
        ...(capturedProps.widgetPanel?.widget as DashboardWidget),
        title: 'Saved widget',
      })
    })

    await waitFor(() => {
      expect(apiMocks.updateDashboard).toHaveBeenCalledWith('dashboard-1', {title: 'Renamed'})
      expect(apiMocks.updateDashboard).toHaveBeenCalledWith('dashboard-1', {
        widgets: [expect.objectContaining({grid_w: 8})],
      })
      expect(apiMocks.updateDashboard).toHaveBeenCalledWith('dashboard-1', {
        variables: [{name: 'service', type: 'query', options: ['api']}],
      })
      expect(apiMocks.updateDashboard).toHaveBeenCalledWith('dashboard-1', {widgets: []})
      expect(apiMocks.updateDashboard).toHaveBeenCalledWith('dashboard-1', {
        widgets: [expect.objectContaining({id: 'widget-1'})],
      })
      expect(apiMocks.updateDashboard).toHaveBeenCalledWith('dashboard-1', {
        widgets: expect.arrayContaining([expect.objectContaining({title: 'Saved widget'})]),
      })
    })

    queryClient.clear()
  })
})
