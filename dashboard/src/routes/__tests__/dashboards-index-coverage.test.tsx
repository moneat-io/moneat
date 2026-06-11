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

import React, {type ReactNode} from 'react'
import {beforeEach, describe, expect, it, vi} from 'vitest'
import {fireEvent, screen, waitFor, within} from '@testing-library/react'
import {clearAuthStorage, renderRoute} from '@/test/utils'
import type {
  CustomDashboard,
  CustomDataSourceResponse,
  DashboardFolder,
  DashboardTemplateSummary,
} from '@/lib/api'

const {mockApi, mockNavigate} = vi.hoisted(() => ({
  mockNavigate: vi.fn(),
  mockApi: {
    getDashboards: vi.fn(),
    getDashboard: vi.fn(),
    createDashboard: vi.fn(),
    deleteDashboard: vi.fn(),
    toggleDashboardFavorite: vi.fn(),
    duplicateDashboard: vi.fn(),
    setDefaultDashboard: vi.fn(),
    moveDashboardToFolder: vi.fn(),
    getDashboardFolders: vi.fn(),
    createDashboardFolder: vi.fn(),
    deleteDashboardFolder: vi.fn(),
    updateDashboardFolder: vi.fn(),
    getDashboardTemplates: vi.fn(),
    createDashboardFromTemplate: vi.fn(),
    listCustomDataSources: vi.fn(),
  },
}))

type ChildrenProps = Readonly<{children?: ReactNode}>
type MenuItemProps = ChildrenProps &
  Readonly<{className?: string; onClick?: (event: React.MouseEvent<HTMLButtonElement>) => void}>

vi.mock('@/lib/api', () => ({api: mockApi}))

vi.mock('@tanstack/react-router', () => ({
  createFileRoute: () => (options: Record<string, unknown>) => ({...options, options}),
  Link: ({children, ...props}: ChildrenProps) => React.createElement('a', props, children),
  useNavigate: () => mockNavigate,
}))

vi.mock('@/components/dashboards/ImportExportModal', () => ({
  ImportExportModal: ({open}: Readonly<{open: boolean}>) =>
    open ? <div role="dialog">Dashboard import</div> : null,
}))

vi.mock('@/components/ui/dropdown-menu', () => ({
  DropdownMenu: ({children}: ChildrenProps) => <div>{children}</div>,
  DropdownMenuContent: ({children}: ChildrenProps) => <div>{children}</div>,
  DropdownMenuItem: ({children, className, onClick}: MenuItemProps) => (
    <button type="button" className={className} onClick={onClick}>
      {children}
    </button>
  ),
  DropdownMenuSeparator: () => <hr />,
  DropdownMenuSub: ({children}: ChildrenProps) => <div>{children}</div>,
  DropdownMenuSubContent: ({children}: ChildrenProps) => <div>{children}</div>,
  DropdownMenuSubTrigger: ({children, onClick}: MenuItemProps) => (
    <button type="button" onClick={onClick}>
      {children}
    </button>
  ),
  DropdownMenuTrigger: ({children}: ChildrenProps) => <>{children}</>,
}))

import {Route as DashboardsRoute} from '../dashboards.index'

const NOW_ISO = new Date().toISOString()
const OLD_ISO = new Date(Date.now() - 5 * 86_400_000).toISOString()
const OPS_FOLDER_ID = '00000000-0000-0000-0000-000000000007'
const EMPTY_FOLDER_ID = '00000000-0000-0000-0000-000000000008'
const API_DASHBOARD_ID = '00000000-0000-0000-0000-000000000011'
const QUEUE_DASHBOARD_ID = '00000000-0000-0000-0000-000000000012'
const CREATED_DASHBOARD_ID = '00000000-0000-0000-0000-000000000021'
const TEMPLATE_DASHBOARD_ID = '00000000-0000-0000-0000-000000000022'
const DUPLICATE_DASHBOARD_ID = '00000000-0000-0000-0000-000000000023'
const REQUESTS_WIDGET_ID = '00000000-0000-0000-0000-000000000031'
const PROMETHEUS_SOURCE_ID = '00000000-0000-0000-0000-000000000041'
const POSTGRES_SOURCE_ID = '00000000-0000-0000-0000-000000000042'

const FOLDERS: readonly DashboardFolder[] = [
  {
    id: OPS_FOLDER_ID,
    org_id: 1,
    name: 'Ops',
    color: '#2563eb',
    sort_order: 0,
    created_at: NOW_ISO,
    updated_at: NOW_ISO,
  },
  {
    id: EMPTY_FOLDER_ID,
    org_id: 1,
    name: 'Empty',
    color: null,
    sort_order: 1,
    created_at: NOW_ISO,
    updated_at: NOW_ISO,
  },
]

const DASHBOARDS: readonly CustomDashboard[] = [
  {
    id: API_DASHBOARD_ID,
    org_id: 1,
    project_id: null,
    folder_id: OPS_FOLDER_ID,
    title: 'API Health',
    description: 'Service dashboard',
    layout_type: 'grid',
    is_default: true,
    is_favorited: true,
    variables: [],
    created_by: 1,
    owner_name: 'Sam Lee',
    created_at: NOW_ISO,
    updated_at: NOW_ISO,
    widgets: [
      {
        id: REQUESTS_WIDGET_ID,
        dashboard_id: API_DASHBOARD_ID,
        title: 'Requests',
        widget_type: 'line',
        grid_x: 0,
        grid_y: 0,
        grid_w: 6,
        grid_h: 4,
        query_configs: [
          {dataSource: 'metrics', metrics: [], groupBy: [], filters: [], limit: 1, timeRange: {from: '', to: ''}},
        ],
        display_config: {},
        sort_order: 0,
      },
    ],
  },
  {
    id: QUEUE_DASHBOARD_ID,
    org_id: 1,
    project_id: null,
    folder_id: null,
    title: 'Queue Depth',
    description: null,
    layout_type: 'grid',
    is_default: false,
    is_favorited: false,
    variables: [],
    created_by: 2,
    owner_name: null,
    created_at: OLD_ISO,
    updated_at: OLD_ISO,
    widgets: [],
  },
]

const TEMPLATES: readonly DashboardTemplateSummary[] = [
  {
    id: 'node-exporter-full',
    title: 'Node Exporter Full',
    description: 'Prebuilt Moneat dashboard for host telemetry.',
    category: 'infrastructure',
    tags: ['Infrastructure', 'Prometheus'],
    required_sources: ['Prometheus'],
    widget_count: 140,
    variable_count: 4,
    resource_path: 'dashboard-templates/community/node-exporter-full.json',
  },
  {
    id: 'k8s-cluster',
    title: 'Kubernetes Cluster',
    description: 'Pods, nodes and saturation across namespaces.',
    category: 'kubernetes',
    tags: ['k8s'],
    required_sources: ['metrics', 'logs', 'events'],
    widget_count: 18,
    variable_count: 2,
    resource_path: 'dashboard-templates/community/k8s.json',
  },
]

const DATA_SOURCES: readonly CustomDataSourceResponse[] = [
  {
    id: PROMETHEUS_SOURCE_ID,
    org_id: 1,
    name: 'Prometheus prod',
    source_type: 'prometheus',
    host: 'prom.prod',
    port: 9090,
    extra_config: {},
    enabled: true,
    created_by: 1,
    created_at: NOW_ISO,
    updated_at: NOW_ISO,
    has_credentials: false,
    used_by_dashboard_count: 3,
  },
  {
    id: POSTGRES_SOURCE_ID,
    org_id: 1,
    name: 'Disabled PG',
    source_type: 'postgresql',
    host: 'pg',
    port: 5432,
    database_name: 'app',
    extra_config: {},
    enabled: false,
    created_by: 1,
    created_at: NOW_ISO,
    updated_at: NOW_ISO,
    has_credentials: true,
    used_by_dashboard_count: 0,
  },
]

describe('Dashboards hub route', () => {
  beforeEach(() => {
    vi.clearAllMocks()
    clearAuthStorage()
    mockApi.getDashboards.mockResolvedValue(DASHBOARDS)
    mockApi.getDashboardFolders.mockResolvedValue(FOLDERS)
    mockApi.getDashboardTemplates.mockResolvedValue(TEMPLATES)
    mockApi.listCustomDataSources.mockResolvedValue(DATA_SOURCES)
    mockApi.createDashboard.mockResolvedValue({id: CREATED_DASHBOARD_ID})
    mockApi.createDashboardFromTemplate.mockResolvedValue({id: TEMPLATE_DASHBOARD_ID})
    mockApi.deleteDashboard.mockResolvedValue(undefined)
    mockApi.toggleDashboardFavorite.mockResolvedValue({is_favorited: true})
    mockApi.duplicateDashboard.mockResolvedValue({...DASHBOARDS[0], id: DUPLICATE_DASHBOARD_ID})
    mockApi.setDefaultDashboard.mockResolvedValue({is_default: true})
    mockApi.moveDashboardToFolder.mockResolvedValue({folder_id: OPS_FOLDER_ID})
    mockApi.createDashboardFolder.mockResolvedValue(FOLDERS[0])
    mockApi.deleteDashboardFolder.mockResolvedValue(undefined)
    mockApi.updateDashboardFolder.mockResolvedValue(FOLDERS[0])
    mockApi.getDashboard.mockResolvedValue({...DASHBOARDS[0], widgets: DASHBOARDS[0].widgets})
  })

  it('lands on Templates at first run and instantiates a selected template', async () => {
    mockApi.getDashboards.mockResolvedValue([])

    renderRoute(DashboardsRoute)

    fireEvent.click(await screen.findByRole('button', {name: /Use the Node Exporter Full template/i}))

    await waitFor(() => {
      expect(mockApi.createDashboardFromTemplate).toHaveBeenCalledWith('node-exporter-full', {
        folder_id: undefined,
      })
      expect(mockNavigate).toHaveBeenCalledWith({
        to: '/dashboards/$dashboardId',
        params: {dashboardId: TEMPLATE_DASHBOARD_ID},
        search: {edit: true},
      })
    })

    fireEvent.click(screen.getByRole('button', {name: 'Kubernetes'}))
    expect(screen.queryByRole('button', {name: /Use the Node Exporter Full template/i})).toBeNull()
    expect(screen.getByRole('button', {name: /Use the Kubernetes Cluster template/i})).toBeInTheDocument()

    fireEvent.click(screen.getByRole('button', {name: 'Databases'}))
    expect(screen.getByText('No templates match this category.')).toBeInTheDocument()
  })

  it('shows the boards tab with owner avatars and filters by folder', async () => {
    renderRoute(DashboardsRoute)

    expect(await screen.findByText('API Health')).toBeInTheDocument()
    expect(screen.getByText('Queue Depth')).toBeInTheDocument()
    expect(screen.getByText(/2 yours · 2 templates/)).toBeInTheDocument()
    expect(screen.getByTitle('Sam Lee')).toBeInTheDocument()
    expect(screen.getByTitle('User #2')).toBeInTheDocument()

    fireEvent.click(screen.getByRole('button', {name: /Favorites/}))
    expect(screen.getByText('API Health')).toBeInTheDocument()
    expect(screen.queryByText('Queue Depth')).not.toBeInTheDocument()

    fireEvent.click(screen.getAllByRole('button', {name: 'Empty'})[0])
    expect(await screen.findByText('Empty is empty')).toBeInTheDocument()
    fireEvent.click(screen.getByRole('button', {name: /Create dashboard/}))
    await waitFor(() =>
      expect(mockApi.createDashboard).toHaveBeenCalledWith({
        title: 'New Dashboard',
        folder_id: EMPTY_FOLDER_ID,
        widgets: [],
      }),
    )
  })

  it('drives the New dashboard split button and its menu', async () => {
    renderRoute(DashboardsRoute)
    await screen.findByText('API Health')

    fireEvent.click(screen.getByRole('button', {name: /New dashboard/}))
    await waitFor(() =>
      expect(mockApi.createDashboard).toHaveBeenCalledWith({title: 'New Dashboard', widgets: []}),
    )

    fireEvent.click(screen.getByRole('button', {name: /From a template/}))
    expect(
      await screen.findByRole('button', {name: /Use the Node Exporter Full template/i}),
    ).toBeInTheDocument()

    fireEvent.click(screen.getAllByRole('button', {name: /Import JSON/})[0])
    expect(screen.getByRole('dialog')).toHaveTextContent('Dashboard import')
  })

  it('opens the inline folder input from the New menu and creates a folder', async () => {
    renderRoute(DashboardsRoute)
    await screen.findByText('API Health')

    fireEvent.click(screen.getAllByRole('button', {name: /New folder/})[0])
    const input = await screen.findByPlaceholderText('Folder name')
    fireEvent.change(input, {target: {value: 'Backend'}})
    fireEvent.keyDown(input, {key: 'Enter'})

    await waitFor(() =>
      expect(mockApi.createDashboardFolder).toHaveBeenCalledWith({name: 'Backend'}),
    )
  })

  it('runs row actions: favorite, duplicate, set default, move and delete', async () => {
    renderRoute(DashboardsRoute)
    await screen.findByText('API Health')

    const row = screen.getByText('API Health').closest('a') as HTMLElement

    fireEvent.click(within(row).getByRole('button', {name: 'Remove from favorites'}))
    await waitFor(() => expect(mockApi.toggleDashboardFavorite).toHaveBeenCalledWith(API_DASHBOARD_ID))

    fireEvent.click(within(row).getByRole('button', {name: 'Duplicate'}))
    await waitFor(() => expect(mockApi.duplicateDashboard).toHaveBeenCalledWith(API_DASHBOARD_ID))
    expect(mockApi.getDashboard).not.toHaveBeenCalled()

    const nonDefaultRow = screen.getByText('Queue Depth').closest('a') as HTMLElement
    fireEvent.click(within(nonDefaultRow).getByRole('button', {name: 'Set as default'}))
    await waitFor(() => expect(mockApi.setDefaultDashboard).toHaveBeenCalledWith(QUEUE_DASHBOARD_ID))

    fireEvent.click(within(row).getByRole('button', {name: 'Uncategorized'}))
    fireEvent.click(within(row).getByRole('button', {name: 'Ops'}))
    await waitFor(() => {
      expect(mockApi.moveDashboardToFolder).toHaveBeenCalledWith(API_DASHBOARD_ID, null)
      expect(mockApi.moveDashboardToFolder).toHaveBeenCalledWith(API_DASHBOARD_ID, OPS_FOLDER_ID)
    })

    fireEvent.click(within(row).getByRole('button', {name: 'Delete'}))
    await waitFor(() => expect(mockApi.deleteDashboard).toHaveBeenCalledWith(API_DASHBOARD_ID))
  })

  it('renames and deletes folders from the rail', async () => {
    renderRoute(DashboardsRoute)
    await screen.findByText('API Health')

    fireEvent.click(screen.getAllByRole('button', {name: 'Rename'})[0])
    fireEvent.change(screen.getByDisplayValue('Ops'), {target: {value: 'Platform'}})
    fireEvent.keyDown(screen.getByDisplayValue('Platform'), {key: 'Enter'})
    await waitFor(() =>
      expect(mockApi.updateDashboardFolder).toHaveBeenCalledWith(OPS_FOLDER_ID, {name: 'Platform'}),
    )

    fireEvent.click(screen.getAllByRole('button', {name: 'Delete folder'})[0])
    await waitFor(() => expect(mockApi.deleteDashboardFolder).toHaveBeenCalledWith(OPS_FOLDER_ID))
  })

  it('sorts, toggles to grid and searches the boards list', async () => {
    renderRoute(DashboardsRoute)
    await screen.findByText('API Health')

    fireEvent.click(screen.getByRole('button', {name: /Name \(A/}))
    fireEvent.click(screen.getByRole('button', {name: 'Most widgets'}))
    fireEvent.click(screen.getByRole('button', {name: 'Recently created'}))
    expect(screen.getByText('API Health')).toBeInTheDocument()

    fireEvent.click(screen.getByRole('button', {name: 'Grid view'}))
    expect(screen.getByText('No description')).toBeInTheDocument()

    const search = screen.getByPlaceholderText('Search dashboards…')
    fireEvent.change(search, {target: {value: 'Queue'}})
    expect(screen.queryByText('API Health')).not.toBeInTheDocument()
    expect(screen.getByText('Queue Depth')).toBeInTheDocument()

    fireEvent.change(search, {target: {value: 'zzzz'}})
    expect(screen.getByText('No dashboards match your search')).toBeInTheDocument()
  })

  it('shows the data sources tab and navigates to manage them', async () => {
    renderRoute(DashboardsRoute)
    await screen.findByText('API Health')

    fireEvent.click(screen.getByRole('tab', {name: /Data sources/}))

    expect(await screen.findByText('Moneat telemetry')).toBeInTheDocument()
    expect(screen.getByText('Prometheus prod')).toBeInTheDocument()
    expect(screen.getByText('Disabled PG')).toBeInTheDocument()
    expect(screen.getByText('Disabled')).toBeInTheDocument()
    expect(screen.getByText('Updated')).toBeInTheDocument()
    expect(screen.queryByText('Last sync')).not.toBeInTheDocument()

    const nativeRow = screen.getByText('Moneat telemetry').closest('.grid') as HTMLElement
    expect(within(nativeRow).getByText('Built in')).toBeInTheDocument()
    expect(within(nativeRow).getByText('1')).toBeInTheDocument()

    const prometheusRow = screen.getByText('Prometheus prod').closest('.grid') as HTMLElement
    expect(within(prometheusRow).getByText('3')).toBeInTheDocument()

    fireEvent.click(screen.getByRole('button', {name: /Manage Prometheus prod/}))
    expect(mockNavigate).toHaveBeenCalledWith({
      to: '/dashboards/datasources',
      search: {edit: PROMETHEUS_SOURCE_ID},
    })

    fireEvent.click(screen.getByRole('button', {name: /Add data source/}))
    expect(mockNavigate).toHaveBeenCalledWith({
      to: '/dashboards/datasources',
      search: {new: 1},
    })

    const search = screen.getByPlaceholderText('Search data sources…')
    fireEvent.change(search, {target: {value: 'prom'}})
    expect(screen.queryByText('Moneat telemetry')).not.toBeInTheDocument()
    expect(screen.getByText('Prometheus prod')).toBeInTheDocument()
  })

  it('renders the header and all three tabs while dashboards are pending', () => {
    mockApi.getDashboards.mockReturnValue(new Promise<CustomDashboard[]>(() => {}))

    renderRoute(DashboardsRoute)

    expect(screen.getByRole('tab', {name: /Your dashboards/})).toBeInTheDocument()
    expect(screen.getByRole('tab', {name: /Templates/})).toBeInTheDocument()
    expect(screen.getByRole('tab', {name: /Data sources/})).toBeInTheDocument()
  })
})
