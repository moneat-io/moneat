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
import {fireEvent, screen, waitFor} from '@testing-library/react'
import {clearAuthStorage, renderRoute} from '@/test/utils'
import type {CustomDashboard, DashboardFolder, DashboardTemplateSummary} from '@/lib/api'

const {mockApi, mockNavigate} = vi.hoisted(() => ({
  mockNavigate: vi.fn(),
  mockApi: {
    getDashboards: vi.fn(),
    getDashboard: vi.fn(),
    createDashboard: vi.fn(),
    deleteDashboard: vi.fn(),
    toggleDashboardFavorite: vi.fn(),
    moveDashboardToFolder: vi.fn(),
    getDashboardFolders: vi.fn(),
    createDashboardFolder: vi.fn(),
    deleteDashboardFolder: vi.fn(),
    updateDashboardFolder: vi.fn(),
    getDashboardTemplates: vi.fn(),
    createDashboardFromTemplate: vi.fn(),
  },
}))

type ChildrenProps = Readonly<{
  children?: ReactNode
}>

type MenuItemProps = ChildrenProps & Readonly<{
  className?: string
  onClick?: (event: React.MouseEvent<HTMLButtonElement>) => void
}>

vi.mock('@/lib/api', () => ({api: mockApi}))

vi.mock('@tanstack/react-router', () => ({
  createFileRoute: () => (options: Record<string, unknown>) => ({
    ...options,
    options,
  }),
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
const OLD_ISO = new Date(Date.now() - 172_800_000).toISOString()

const FOLDERS: readonly DashboardFolder[] = [
  {
    id: 7,
    org_id: 1,
    name: 'Ops',
    color: '#2563eb',
    sort_order: 0,
    created_at: NOW_ISO,
    updated_at: NOW_ISO,
  },
  {
    id: 8,
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
    id: 11,
    org_id: 1,
    project_id: null,
    folder_id: 7,
    title: 'API Health',
    description: 'Service dashboard',
    layout_type: 'grid',
    is_default: false,
    is_favorited: true,
    variables: [],
    created_by: 1,
    created_at: NOW_ISO,
    updated_at: NOW_ISO,
    widgets: [{
      id: 1,
      dashboard_id: 11,
      title: 'Requests',
      widget_type: 'line',
      grid_x: 0,
      grid_y: 0,
      grid_w: 6,
      grid_h: 4,
      query_configs: [],
      display_config: {},
      sort_order: 0,
    }],
  },
  {
    id: 12,
    org_id: 1,
    project_id: null,
    folder_id: null,
    title: 'Queue Depth',
    description: null,
    layout_type: 'grid',
    is_default: false,
    is_favorited: false,
    variables: [],
    created_by: 1,
    created_at: NOW_ISO,
    updated_at: OLD_ISO,
    widgets: [],
  },
]

const TEMPLATE: DashboardTemplateSummary = {
  id: 'node-exporter-full',
  title: 'Node Exporter Full',
  description: 'Prebuilt Moneat dashboard for host telemetry.',
  category: 'infrastructure',
  tags: ['Infrastructure', 'Prometheus'],
  required_sources: ['Prometheus'],
  widget_count: 140,
  variable_count: 4,
  quality: 'needs-review',
  resource_path: 'dashboard-templates/community/node-exporter-full.json',
}

function setupDashboardRoute() {
  renderRoute(DashboardsRoute)
}

describe('Dashboards index route', () => {
  beforeEach(() => {
    vi.clearAllMocks()
    clearAuthStorage()
    mockApi.getDashboards.mockResolvedValue(DASHBOARDS)
    mockApi.getDashboardFolders.mockResolvedValue(FOLDERS)
    mockApi.getDashboardTemplates.mockResolvedValue([TEMPLATE])
    mockApi.createDashboard.mockResolvedValue({id: 21})
    mockApi.createDashboardFromTemplate.mockResolvedValue({id: 22})
    mockApi.deleteDashboard.mockResolvedValue(undefined)
    mockApi.toggleDashboardFavorite.mockResolvedValue({is_favorited: true})
    mockApi.moveDashboardToFolder.mockResolvedValue({folder_id: 7})
    mockApi.createDashboardFolder.mockResolvedValue(FOLDERS[0])
    mockApi.deleteDashboardFolder.mockResolvedValue(undefined)
    mockApi.updateDashboardFolder.mockResolvedValue(FOLDERS[0])
    mockApi.getDashboard.mockResolvedValue({
      ...DASHBOARDS[0],
      widgets: DASHBOARDS[0].widgets,
    })
  })

  it('renders first-run templates and instantiates a selected template', async () => {
    mockApi.getDashboards.mockResolvedValue([])

    setupDashboardRoute()

    fireEvent.click(await screen.findByRole('button', {name: /Use the Node Exporter Full template/i}))

    await waitFor(() => {
      expect(mockApi.createDashboardFromTemplate).toHaveBeenCalledWith(
        'node-exporter-full',
        {folder_id: undefined},
      )
      expect(mockNavigate).toHaveBeenCalledWith({
        to: '/dashboards/$dashboardId',
        params: {dashboardId: '22'},
        search: {edit: true},
      })
    })

    fireEvent.click(screen.getByRole('button', {name: 'Import'}))
    expect(screen.getByRole('dialog')).toHaveTextContent('Dashboard import')
  })

  it('renders the loading grid while dashboards are pending', () => {
    mockApi.getDashboards.mockReturnValue(new Promise<CustomDashboard[]>(() => {}))

    const {container} = renderRoute(DashboardsRoute)

    expect(container.querySelectorAll('.animate-pulse').length).toBeGreaterThan(0)
    expect(screen.getByText('Build custom dashboards with drag-and-drop widgets')).toBeInTheDocument()
  })

  it('filters folders and creates a dashboard inside an empty folder', async () => {
    setupDashboardRoute()

    expect(await screen.findByText('API Health')).toBeInTheDocument()
    fireEvent.click(screen.getByRole('button', {name: /Favorites/}))

    expect(screen.getByText('API Health')).toBeInTheDocument()
    expect(screen.queryByText('Queue Depth')).not.toBeInTheDocument()

    fireEvent.click(screen.getAllByRole('button', {name: /Empty/})[0])
    expect(await screen.findByText('Empty is empty')).toBeInTheDocument()

    fireEvent.click(screen.getByRole('button', {name: /Create Dashboard/}))

    await waitFor(() => {
      expect(mockApi.createDashboard).toHaveBeenCalledWith({
        title: 'New Dashboard',
        widgets: [],
        folder_id: 8,
      })
    })
  })

  it('creates folders and runs dashboard card actions', async () => {
    setupDashboardRoute()

    expect(await screen.findByText('Queue Depth')).toBeInTheDocument()

    fireEvent.click(screen.getByRole('button', {name: /New folder/}))
    fireEvent.change(screen.getByPlaceholderText('Folder name'), {target: {value: 'Backend'}})
    await waitFor(() => expect(screen.getByPlaceholderText('Folder name')).toHaveValue('Backend'))
    fireEvent.keyDown(screen.getByPlaceholderText('Folder name'), {key: 'Enter'})

    await waitFor(() => expect(mockApi.createDashboardFolder).toHaveBeenCalledWith({name: 'Backend'}))

    fireEvent.click(screen.getAllByRole('button', {name: /Rename/})[0])
    fireEvent.change(screen.getByDisplayValue('Ops'), {target: {value: 'Platform'}})
    fireEvent.keyDown(screen.getByDisplayValue('Platform'), {key: 'Enter'})

    await waitFor(() => expect(mockApi.updateDashboardFolder).toHaveBeenCalledWith(7, {name: 'Platform'}))

    fireEvent.click(screen.getByTitle('Add to favorites'))
    await waitFor(() => expect(mockApi.toggleDashboardFavorite).toHaveBeenCalledWith(12))

    fireEvent.click(screen.getAllByRole('button', {name: /Duplicate/})[0])
    await waitFor(() => {
      expect(mockApi.getDashboard).toHaveBeenCalledWith(11)
      expect(mockApi.createDashboard).toHaveBeenCalledWith(expect.objectContaining({
        title: 'API Health (Copy)',
        folder_id: 7,
      }))
    })

    fireEvent.click(screen.getAllByRole('button', {name: /Move to folder/})[0])
    fireEvent.click(screen.getAllByRole('button', {name: /^Uncategorized$/})[0])
    const opsButtons = screen.getAllByRole('button', {name: /^Ops$/})
    fireEvent.click(opsButtons[opsButtons.length - 1])

    await waitFor(() => {
      expect(mockApi.moveDashboardToFolder).toHaveBeenCalledWith(expect.any(Number), null)
      expect(mockApi.moveDashboardToFolder).toHaveBeenCalledWith(expect.any(Number), 7)
    })

    fireEvent.click(screen.getAllByRole('button', {name: /^Delete$/})[0])
    await waitFor(() => expect(mockApi.deleteDashboard).toHaveBeenCalledWith(11))
  })
})
