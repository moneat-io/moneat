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

import {QueryClient, QueryClientProvider} from '@tanstack/react-query'
import {fireEvent, render, screen, waitFor, within} from '@testing-library/react'
import type {ReactElement} from 'react'
import {beforeEach, describe, expect, it, vi} from 'vitest'

import type {DashboardWidget} from '@/lib/api'
import {CreateLogMetricDialog, CreateLogMonitorDialog} from '@/components/logs/LogCreateDialogs'

const {mockApi} = vi.hoisted(() => ({
  mockApi: {
    getDashboards: vi.fn(),
    getProjects: vi.fn(),
    createDashboard: vi.fn(),
    getDashboard: vi.fn(),
    updateDashboard: vi.fn(),
    deleteDashboard: vi.fn(),
    createLogMonitor: vi.fn(),
    getLogAggregate: vi.fn(),
  },
}))

const EMPTY_AGGREGATE = {buckets: [], totalCount: 0, interval: '5m'}
const PAYMENTS_DASHBOARD_RESOURCE_ID = '123e4567-e89b-12d3-a456-426614174201'
const NEW_LOGS_DASHBOARD_RESOURCE_ID = '123e4567-e89b-12d3-a456-426614174202'
const LOG_MONITOR_RESOURCE_ID = '123e4567-e89b-12d3-a456-426614174203'

vi.mock('@/lib/api', () => ({api: mockApi}))

// Stand in for the heavy widget editor: we only need its onSave/onClose wiring
// and to inspect the seeded widget it was handed.
vi.mock('@/components/dashboards/WidgetConfigPanel', () => ({
  WidgetConfigPanel: ({
    widget,
    dashboardId,
    onSave,
    onClose,
  }: {
    widget: DashboardWidget
    dashboardId: string
    projectId?: string
    onSave: (widget: DashboardWidget) => void
    onClose: () => void
  }) => (
    <div role="dialog" aria-label="widget-editor">
      <span data-testid="editor-dashboard">{dashboardId}</span>
      <span data-testid="editor-datasource">{widget.query_configs[0].dataSource}</span>
      <span data-testid="editor-filters">{JSON.stringify(widget.query_configs[0].filters)}</span>
      <button onClick={() => onSave(widget)}>Save Widget</button>
      <button onClick={onClose}>Editor Cancel</button>
    </div>
  ),
}))

function renderWithClient(ui: ReactElement) {
  const queryClient = new QueryClient({defaultOptions: {queries: {retry: false}}})
  return render(<QueryClientProvider client={queryClient}>{ui}</QueryClientProvider>)
}

const metricSeed = {
  query: '',
  levels: ['error'],
  facetFilters: [{key: 'service', value: 'api'}],
  groupByField: 'service',
  timeRange: {},
}

describe('CreateLogMetricDialog', () => {
  beforeEach(() => {
    vi.clearAllMocks()
    mockApi.getLogAggregate.mockResolvedValue(EMPTY_AGGREGATE)
    mockApi.getProjects.mockResolvedValue([])
    mockApi.getDashboards.mockResolvedValue([{id: PAYMENTS_DASHBOARD_RESOURCE_ID, title: 'Payments', widgets: []}])
    mockApi.getDashboard.mockResolvedValue({id: PAYMENTS_DASHBOARD_RESOURCE_ID, title: 'Payments', widgets: []})
    mockApi.createDashboard.mockResolvedValue({id: NEW_LOGS_DASHBOARD_RESOURCE_ID, title: 'My logs', widgets: []})
    mockApi.updateDashboard.mockResolvedValue({})
    mockApi.deleteDashboard.mockResolvedValue(undefined)
    if (!HTMLElement.prototype.hasPointerCapture) {
      HTMLElement.prototype.hasPointerCapture = () => false
    }
    if (!HTMLElement.prototype.scrollIntoView) {
      HTMLElement.prototype.scrollIntoView = () => {}
    }
  })

  it('adds a seeded logs widget to the preselected existing dashboard', async () => {
    const onOpenChange = vi.fn()
    renderWithClient(<CreateLogMetricDialog open onOpenChange={onOpenChange} {...metricSeed} />)

    // Existing dashboards load → first is preselected → Continue enables.
    const continueButton = await screen.findByRole('button', {name: 'Continue'})
    await waitFor(() => expect(continueButton).toBeEnabled())
    fireEvent.click(continueButton)

    const editor = await screen.findByRole('dialog', {name: 'widget-editor'})
    expect(within(editor).getByTestId('editor-dashboard')).toHaveTextContent(PAYMENTS_DASHBOARD_RESOURCE_ID)
    expect(within(editor).getByTestId('editor-datasource')).toHaveTextContent('logs')
    expect(within(editor).getByTestId('editor-filters')).toHaveTextContent('"field":"service"')

    fireEvent.click(within(editor).getByRole('button', {name: 'Save Widget'}))

    await waitFor(() => {
      expect(mockApi.updateDashboard).toHaveBeenCalledWith(
        PAYMENTS_DASHBOARD_RESOURCE_ID,
        expect.objectContaining({
          widgets: expect.arrayContaining([expect.objectContaining({widget_type: 'timeseries'})]),
        })
      )
    })
    await waitFor(() => expect(onOpenChange).toHaveBeenCalledWith(false))
    expect(mockApi.createDashboard).not.toHaveBeenCalled()
  })

  it('creates a new dashboard then saves the widget into it', async () => {
    mockApi.getDashboards.mockResolvedValue([])
    mockApi.getDashboard.mockResolvedValue({id: NEW_LOGS_DASHBOARD_RESOURCE_ID, title: 'My logs', widgets: []})
    const onOpenChange = vi.fn()
    renderWithClient(<CreateLogMetricDialog open onOpenChange={onOpenChange} {...metricSeed} />)

    // No existing dashboards → defaults to "New dashboard" with a name field.
    fireEvent.change(await screen.findByPlaceholderText('Log metrics'), {target: {value: 'My logs'}})
    fireEvent.click(screen.getByRole('button', {name: 'Continue'}))

    await waitFor(() =>
      expect(mockApi.createDashboard).toHaveBeenCalledWith({title: 'My logs', widgets: []})
    )

    const editor = await screen.findByRole('dialog', {name: 'widget-editor'})
    expect(within(editor).getByTestId('editor-dashboard')).toHaveTextContent(NEW_LOGS_DASHBOARD_RESOURCE_ID)
    fireEvent.click(within(editor).getByRole('button', {name: 'Save Widget'}))

    await waitFor(() => {
      expect(mockApi.updateDashboard).toHaveBeenCalledWith(NEW_LOGS_DASHBOARD_RESOURCE_ID, expect.anything())
    })
    expect(mockApi.deleteDashboard).not.toHaveBeenCalled()
  })

  it('discards a freshly created dashboard when the editor is cancelled', async () => {
    mockApi.getDashboards.mockResolvedValue([])
    const onOpenChange = vi.fn()
    renderWithClient(<CreateLogMetricDialog open onOpenChange={onOpenChange} {...metricSeed} />)

    fireEvent.change(await screen.findByPlaceholderText('Log metrics'), {target: {value: 'Scratch'}})
    fireEvent.click(screen.getByRole('button', {name: 'Continue'}))

    const editor = await screen.findByRole('dialog', {name: 'widget-editor'})
    fireEvent.click(within(editor).getByRole('button', {name: 'Editor Cancel'}))

    await waitFor(() => expect(mockApi.deleteDashboard).toHaveBeenCalledWith(NEW_LOGS_DASHBOARD_RESOURCE_ID))
    expect(mockApi.updateDashboard).not.toHaveBeenCalled()
    expect(onOpenChange).toHaveBeenCalledWith(false)
  })
})

describe('CreateLogMonitorDialog', () => {
  beforeEach(() => {
    vi.clearAllMocks()
    mockApi.getLogAggregate.mockResolvedValue(EMPTY_AGGREGATE)
    mockApi.createLogMonitor.mockResolvedValue({id: LOG_MONITOR_RESOURCE_ID})
  })

  it('creates a log monitor from the seeded query with the chosen threshold', async () => {
    const onOpenChange = vi.fn()
    renderWithClient(
      <CreateLogMonitorDialog open onOpenChange={onOpenChange} query="service:api" levels={[]} />
    )

    const dialog = screen.getByRole('dialog')
    expect(within(dialog).getByDisplayValue('service:api')).toBeInTheDocument()

    const createButton = within(dialog).getByRole('button', {name: 'Create monitor'})
    expect(createButton).toBeDisabled()

    fireEvent.change(within(dialog).getByPlaceholderText('High error log volume'), {
      target: {value: 'High errors'},
    })
    fireEvent.change(within(dialog).getByDisplayValue('10'), {target: {value: '25'}})
    fireEvent.click(createButton)

    await waitFor(() => {
      expect(mockApi.createLogMonitor).toHaveBeenCalledWith({
        name: 'High errors',
        query: 'service:api',
        levels: [],
        group_by: null,
        condition: '>',
        threshold: 25,
        window_minutes: 5,
      })
    })
    await waitFor(() => expect(onOpenChange).toHaveBeenCalledWith(false))
  })

  it('pre-fills the name from a single-level seed and edits the level filter', async () => {
    const onOpenChange = vi.fn()
    renderWithClient(
      <CreateLogMonitorDialog open onOpenChange={onOpenChange} query="service:api" levels={['error']} />
    )

    const dialog = screen.getByRole('dialog')
    // A single seeded level suggests a name, so the monitor opens ready to create.
    expect(within(dialog).getByDisplayValue('High error log volume')).toBeInTheDocument()
    const createButton = within(dialog).getByRole('button', {name: 'Create monitor'})
    expect(createButton).toBeEnabled()

    // Levels are editable (not frozen to the seed): add "warn".
    fireEvent.click(within(dialog).getByRole('button', {name: 'warn'}))
    fireEvent.click(createButton)

    await waitFor(() => {
      expect(mockApi.createLogMonitor).toHaveBeenCalledWith(
        expect.objectContaining({
          name: 'High error log volume',
          query: 'service:api',
          levels: ['warn', 'error'],
          condition: '>',
          threshold: 10,
          window_minutes: 5,
          group_by: null,
        })
      )
    })
  })
})
