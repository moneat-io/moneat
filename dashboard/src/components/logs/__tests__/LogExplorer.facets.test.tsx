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
import userEvent from '@testing-library/user-event'
import type {ComponentProps} from 'react'
import {afterAll, beforeAll, beforeEach, describe, expect, it, vi} from 'vitest'

import {LogExplorer} from '@/components/logs/LogExplorer'

const originalScrollIntoView = globalThis.HTMLElement.prototype.scrollIntoView
const originalReleasePointerCapture = globalThis.HTMLElement.prototype.releasePointerCapture
const originalHasPointerCapture = globalThis.HTMLElement.prototype.hasPointerCapture
const LOG_METRIC_RULE_RESOURCE_ID = '123e4567-e89b-12d3-a456-426614174303'
const LOG_MONITOR_RESOURCE_ID = '123e4567-e89b-12d3-a456-426614174304'
const DEFAULT_DASHBOARD_RESOURCE_ID = '123e4567-e89b-12d3-a456-426614174305'
const CREATED_DASHBOARD_RESOURCE_ID = '123e4567-e89b-12d3-a456-426614174306'

beforeAll(() => {
  // The toolbar's "Export" actions live in a Radix dropdown; jsdom needs these.
  globalThis.HTMLElement.prototype.scrollIntoView = vi.fn()
  globalThis.HTMLElement.prototype.releasePointerCapture = vi.fn()
  globalThis.HTMLElement.prototype.hasPointerCapture = vi.fn(() => false)
})

afterAll(() => {
  globalThis.HTMLElement.prototype.scrollIntoView = originalScrollIntoView
  globalThis.HTMLElement.prototype.releasePointerCapture = originalReleasePointerCapture
  globalThis.HTMLElement.prototype.hasPointerCapture = originalHasPointerCapture
})

const {mockApi, mockNavigate} = vi.hoisted(() => ({
  mockApi: {
    downloadLogExport: vi.fn(),
    getLogAggregate: vi.fn(),
    getLogFilters: vi.fn(),
    getLogTagValues: vi.fn(),
    getLogTop: vi.fn(),
    getLogs: vi.fn(),
    getLogMetricRules: vi.fn(),
    getLogIndexes: vi.fn(),
    getLogIndexUsage: vi.fn(),
    getLogMonitors: vi.fn(),
    createLogMetricRule: vi.fn(),
    createLogMonitor: vi.fn(),
    getDashboards: vi.fn(),
    getProjects: vi.fn(),
    createDashboard: vi.fn(),
    getDashboard: vi.fn(),
    updateDashboard: vi.fn(),
    deleteDashboard: vi.fn(),
    getApmTraceDetail: vi.fn(),
    createLogTailStream: vi.fn(),
    getCurrentUser: vi.fn(),
    getSystemLogs: vi.fn(),
    isAuthenticated: vi.fn(() => false),
  },
  mockNavigate: vi.fn(),
}))

vi.mock('@tanstack/react-router', () => ({
  useNavigate: () => mockNavigate,
}))

vi.mock('@/lib/api', () => ({
  api: mockApi,
  formatErrorForLogging: (error: unknown) => String(error),
}))

vi.mock('@/lib/demo', () => ({
  getNow: () => Date.parse('2026-06-03T12:00:00.000Z'),
  getNowDate: () => new Date('2026-06-03T12:00:00.000Z'),
}))

// "Create metric" hands off to the real widget editor; stand in for it so we can
// assert the seeded logs widget without rendering the heavy editor.
vi.mock('@/components/dashboards/WidgetConfigPanel', () => ({
  WidgetConfigPanel: ({
    widget,
    onSave,
    onClose,
  }: {
    widget: {query_configs: {dataSource: string; filters: unknown; rawQuery?: string | null}[]}
    onSave: (widget: unknown) => void
    onClose: () => void
  }) => (
    <div role="dialog" aria-label="widget-editor">
      <span data-testid="editor-datasource">{widget.query_configs[0].dataSource}</span>
      <span data-testid="editor-rawquery">{widget.query_configs[0].rawQuery ?? ''}</span>
      <span data-testid="editor-filters">{JSON.stringify(widget.query_configs[0].filters)}</span>
      <button onClick={() => onSave(widget)}>Save Widget</button>
      <button onClick={onClose}>Editor Cancel</button>
    </div>
  ),
}))

vi.mock('@/components/logs/LogSetupGuide', () => ({
  LogSetupGuide: () => <div>log setup guide</div>,
}))

function renderLogExplorer(props: Partial<ComponentProps<typeof LogExplorer>> = {}) {
  const queryClient = new QueryClient({
    defaultOptions: {
      queries: {
        retry: false,
      },
    },
  })

  return render(
    <QueryClientProvider client={queryClient}>
      <LogExplorer enableAutoRefresh={false} {...props} />
    </QueryClientProvider>
  )
}

function sampleLog(overrides: Partial<{
  logId: string
  timestamp: string
  level: string
  message: string
  service: string
  environment: string
  host: string
}> = {}) {
  return {
    logId: overrides.logId ?? 'log-1',
    timestamp: overrides.timestamp ?? '2026-06-03T11:58:00.000Z',
    level: overrides.level ?? 'error',
    message: overrides.message ?? 'payment failed',
    body: overrides.message ?? 'payment failed',
    service: overrides.service ?? 'api',
    environment: overrides.environment ?? 'prod',
    host: overrides.host ?? 'host-1',
    source: 'otlp',
    containerName: 'worker',
    containerId: 'container-1',
    containerImage: 'api:latest',
    traceId: 'trace-1',
    spanId: 'span-1',
    tags: {},
    resourceAttributes: {},
  }
}

interface MockLogTailStream {
  close: ReturnType<typeof vi.fn>
  onerror: ((event: Event) => void) | null
  onmessage: ((event: MessageEvent) => void) | null
  onopen: ((event: Event) => void) | null
}

function createMockLogTailStream(): MockLogTailStream {
  return {
    close: vi.fn(),
    onerror: null,
    onmessage: null,
    onopen: null,
  }
}

describe('LogExplorer facets', () => {
  beforeEach(() => {
    vi.clearAllMocks()
    Object.defineProperty(globalThis.window, 'innerWidth', {
      configurable: true,
      value: 1280,
    })
    Object.defineProperty(globalThis, 'IntersectionObserver', {
      configurable: true,
      value: class {
        observe() {}
        disconnect() {}
      },
    })
    mockApi.getLogFilters.mockResolvedValue({
      services: [{value: 'api', count: 12}],
      environments: [{value: 'prod', count: 7}],
      levels: [],
      tagKeys: ['team'],
    })
    mockApi.getLogs.mockResolvedValue({
      logs: [],
      nextCursor: null,
      hasMore: false,
      totalCount: 0,
    })
    mockApi.getLogAggregate.mockResolvedValue({
      buckets: [],
      totalCount: 0,
      interval: 'auto',
    })
    mockApi.getLogTop.mockResolvedValue({
      field: 'service',
      values: [{value: 'api', count: 9}],
      totalCount: 9,
    })
    mockApi.getSystemLogs.mockResolvedValue({
      logs: [],
      nextCursor: null,
      hasMore: false,
      totalCount: 0,
    })
    mockApi.getApmTraceDetail.mockResolvedValue({spans: []})
    mockApi.getLogMetricRules.mockResolvedValue({rules: []})
    mockApi.getLogIndexes.mockResolvedValue({indexes: []})
    mockApi.getLogIndexUsage.mockResolvedValue({usage: []})
    mockApi.getLogMonitors.mockResolvedValue({monitors: []})
    mockApi.createLogMetricRule.mockResolvedValue({
      id: LOG_METRIC_RULE_RESOURCE_ID,
      name: 'api_errors',
      query: 'service:api',
      levels: [],
      group_by: 'service',
      interval: '5m',
      is_active: true,
      created_at: '2026-06-03T00:00:00.000Z',
      updated_at: '2026-06-03T00:00:00.000Z',
    })
    mockApi.createLogMonitor.mockResolvedValue({
      id: LOG_MONITOR_RESOURCE_ID,
      name: 'api errors',
      query: 'service:api',
      levels: [],
      group_by: null,
      condition: '>',
      threshold: 10,
      warning_threshold: null,
      window_minutes: 5,
      is_active: true,
      created_at: '2026-06-03T00:00:00.000Z',
      updated_at: '2026-06-03T00:00:00.000Z',
    })
    mockApi.downloadLogExport.mockResolvedValue(undefined)
    mockApi.getProjects.mockResolvedValue([])
    mockApi.getDashboards.mockResolvedValue([{id: DEFAULT_DASHBOARD_RESOURCE_ID, title: 'Default', widgets: []}])
    mockApi.getDashboard.mockResolvedValue({id: DEFAULT_DASHBOARD_RESOURCE_ID, title: 'Default', widgets: []})
    mockApi.createDashboard.mockResolvedValue({id: CREATED_DASHBOARD_RESOURCE_ID, title: 'Logs', widgets: []})
    mockApi.updateDashboard.mockResolvedValue({})
    mockApi.deleteDashboard.mockResolvedValue(undefined)
    mockApi.createLogTailStream.mockImplementation(createMockLogTailStream)

    if (!HTMLElement.prototype.hasPointerCapture) {
      HTMLElement.prototype.hasPointerCapture = () => false
    }
    if (!HTMLElement.prototype.scrollIntoView) {
      HTMLElement.prototype.scrollIntoView = () => {}
    }
  })

  it('forwards selected service environment and level filters to log queries', async () => {
    renderLogExplorer()

    fireEvent.click(await screen.findByLabelText('api'))
    await waitFor(() => {
      expect(mockApi.getLogs).toHaveBeenLastCalledWith(expect.objectContaining({
        service: 'api',
        environment: undefined,
        levels: undefined,
      }))
    })

    fireEvent.click(screen.getByLabelText('prod'))
    await waitFor(() => {
      expect(mockApi.getLogs).toHaveBeenLastCalledWith(expect.objectContaining({
        service: 'api',
        environment: 'prod',
      }))
    })

    fireEvent.click(screen.getByRole('button', {name: /All Levels/i}))
    fireEvent.click(screen.getByRole('button', {name: /error/i}))

    await waitFor(() => {
      expect(mockApi.getLogs).toHaveBeenLastCalledWith(expect.objectContaining({
        service: 'api',
        environment: 'prod',
        levels: ['trace', 'debug', 'info', 'warn', 'fatal'],
      }))
    })

    fireEvent.click(screen.getByLabelText('Clear all filters'))
    await waitFor(() => {
      expect(mockApi.getLogs).toHaveBeenLastCalledWith(expect.objectContaining({
        service: undefined,
        environment: undefined,
        levels: undefined,
      }))
    })
  })

  it('applies level tokens from the default state and keeps one level selected', async () => {
    renderLogExplorer()

    const input = screen.getByPlaceholderText('Search...')
    fireEvent.change(input, {target: {value: 'level:error'}})
    fireEvent.keyDown(input, {key: 'Enter'})

    await waitFor(() => {
      expect(mockApi.getLogs).toHaveBeenLastCalledWith(expect.objectContaining({
        levels: ['error'],
      }))
    })

    fireEvent.click(screen.getByRole('button', {name: /Error/i}))
    fireEvent.click(screen.getByRole('button', {name: 'error'}))

    expect(screen.getByRole('button', {name: 'Error'})).toBeInTheDocument()
    expect(mockApi.getLogs).toHaveBeenLastCalledWith(expect.objectContaining({
      levels: ['error'],
    }))
  })

  it('keeps excluded level tokens as search text', async () => {
    renderLogExplorer()

    const input = screen.getByPlaceholderText('Search...')
    fireEvent.change(input, {target: {value: '-level:error'}})
    fireEvent.keyDown(input, {key: 'Enter'})

    await waitFor(() => {
      expect(mockApi.getLogs).toHaveBeenLastCalledWith(expect.objectContaining({
        query: '-level:error',
        levels: undefined,
      }))
    })
  })

  it('uses the filtered empty state after changing only the time range', async () => {
    renderLogExplorer()

    expect(await screen.findByText('log setup guide')).toBeInTheDocument()

    fireEvent.click(screen.getByRole('button', {name: /Last 7 days/}))
    fireEvent.click(screen.getByRole('button', {name: 'Last 24 hours'}))

    expect(await screen.findByText('No logs match your filters')).toBeInTheDocument()
    expect(screen.queryByText('log setup guide')).toBeNull()
  })

  it('uses the system log API without org-scoped facet or aggregate queries', async () => {
    renderLogExplorer({systemId: 'system-1'})

    await waitFor(() => {
      expect(mockApi.getSystemLogs).toHaveBeenCalledWith('system-1', expect.objectContaining({
        from: '2026-05-27T12:00:00.000Z',
        to: undefined,
      }))
    })
    expect(mockApi.getLogFilters).not.toHaveBeenCalled()
    expect(mockApi.getLogAggregate).not.toHaveBeenCalled()
    expect(mockApi.getLogTop).not.toHaveBeenCalled()
    expect(screen.queryByRole('button', {name: 'CSV'})).toBeNull()
  })

  it('loads top values and applies a selected top-list value as a facet', async () => {
    renderLogExplorer()

    fireEvent.click(await screen.findByRole('button', {name: /Top List/i}))

    await waitFor(() => {
      expect(mockApi.getLogTop).toHaveBeenCalledWith(expect.objectContaining({
        field: 'service',
        from: '2026-05-27T12:00:00.000Z',
        to: undefined,
      }))
    })
    expect(await screen.findByText('Top values for service')).toBeTruthy()
    const topList = screen.getByText('Top values for service').parentElement
    expect(topList).not.toBeNull()
    fireEvent.click(within(topList as HTMLElement).getByRole('button', {name: /api/i}))

    await waitFor(() => {
      expect(mockApi.getLogs).toHaveBeenLastCalledWith(expect.objectContaining({
        service: 'api',
      }))
    })
    expect(screen.getByText('service:api')).toBeTruthy()

    fireEvent.click(screen.getByRole('button', {name: /Pie Chart/i}))
    expect(await screen.findByText('api')).toBeTruthy()

    fireEvent.click(screen.getByRole('button', {name: 'Table'}))
    expect(await screen.findByText('Count')).toBeTruthy()
  })

  it('exports csv with the current org-scoped filters', async () => {
    const user = userEvent.setup()
    renderLogExplorer()

    fireEvent.click(await screen.findByLabelText('api'))
    fireEvent.click(screen.getByLabelText('prod'))
    await user.click(await screen.findByRole('button', {name: 'Export'}))
    await user.click(await screen.findByRole('menuitem', {name: /Export to CSV/}))

    await waitFor(() => {
      expect(mockApi.downloadLogExport).toHaveBeenCalledWith(expect.objectContaining({
        service: 'api',
        environment: 'prod',
        from: '2026-05-27T12:00:00.000Z',
        to: undefined,
      }))
    })
  })

  it('seeds the metric widget with the current query from the toolbar', async () => {
    const user = userEvent.setup()
    renderLogExplorer({initialQuery: 'service:api'})

    // The Export menu hosts "Create metric"; it opens the dashboard chooser,
    // then hands a seeded logs widget to the widget editor.
    await user.click(await screen.findByRole('button', {name: 'Export'}))
    await user.click(await screen.findByRole('menuitem', {name: /Create metric/}))

    const continueButton = await screen.findByRole('button', {name: 'Continue'})
    await waitFor(() => expect(continueButton).toBeEnabled())
    fireEvent.click(continueButton)

    const editor = await screen.findByRole('dialog', {name: 'widget-editor'})
    expect(within(editor).getByTestId('editor-datasource')).toHaveTextContent('logs')
    expect(within(editor).getByTestId('editor-rawquery')).toHaveTextContent('service:api')
  })

  it('seeds the metric widget filters from an active facet when the search box is empty', async () => {
    const user = userEvent.setup()
    renderLogExplorer()

    // Facet-only context: nothing typed in the search box, just a service facet.
    fireEvent.click(await screen.findByLabelText('api'))
    await waitFor(() => {
      expect(mockApi.getLogs).toHaveBeenLastCalledWith(expect.objectContaining({service: 'api'}))
    })

    await user.click(await screen.findByRole('button', {name: 'Export'}))
    await user.click(await screen.findByRole('menuitem', {name: /Create metric/}))

    const continueButton = await screen.findByRole('button', {name: 'Continue'})
    await waitFor(() => expect(continueButton).toBeEnabled())
    fireEvent.click(continueButton)

    // The facet becomes a structured widget filter, not raw query text.
    const editor = await screen.findByRole('dialog', {name: 'widget-editor'})
    expect(within(editor).getByTestId('editor-filters')).toHaveTextContent('"field":"service"')
    expect(within(editor).getByTestId('editor-rawquery')).toBeEmptyDOMElement()
  })

  it('creates a monitor from the contextual query when facets are active', async () => {
    const user = userEvent.setup()
    renderLogExplorer()

    fireEvent.click(await screen.findByLabelText('api'))
    await waitFor(() => {
      expect(mockApi.getLogs).toHaveBeenLastCalledWith(expect.objectContaining({service: 'api'}))
    })

    await user.click(await screen.findByRole('button', {name: 'Export'}))
    await user.click(await screen.findByRole('menuitem', {name: /Create monitor/}))
    const dialog = await screen.findByRole('dialog')
    fireEvent.change(within(dialog).getByPlaceholderText('High error log volume'), {target: {value: 'api errors'}})
    fireEvent.click(within(dialog).getByRole('button', {name: 'Create monitor'}))

    await waitFor(() => {
      expect(mockApi.createLogMonitor).toHaveBeenCalledWith(expect.objectContaining({
        name: 'api errors',
        query: 'service:api',
        levels: [],
      }))
    })
  })

  it('hydrates url filters and forwards include and exclude filters to every org query', async () => {
    const facetFilters = [
      {key: 'service', value: 'api', exclude: false},
      {key: 'environment', value: 'prod', exclude: false},
      {key: 'container_name', value: 'worker', exclude: false},
      {key: 'team', value: 'payments', exclude: false},
      {key: 'service', value: 'legacy', exclude: true},
      {key: 'environment', value: 'dev', exclude: true},
      {key: 'container_name', value: 'old-worker', exclude: true},
      {key: 'owner', value: 'platform', exclude: true},
    ]

    renderLogExplorer({
      enableUrlSync: true,
      urlSearch: {
        q: 'timeout',
        levels: 'error,warn',
        facets: facetFilters,
        timePreset: 'custom',
        from: '2026-06-03T11:00:00.000Z',
        to: '2026-06-03T12:00:00.000Z',
        viz: 'table',
        groupBy: 'service',
        topField: 'host',
        cursor: 'cursor-1',
      },
    })

    const expectedFiltersWithoutCursor = {
      query: 'timeout',
      levels: ['error', 'warn'],
      service: 'api',
      environment: 'prod',
      from: '2026-06-03T11:00:00.000Z',
      to: '2026-06-03T12:00:00.000Z',
      tags: {team: 'payments'},
      excludeService: 'legacy',
      excludeEnvironment: 'dev',
      excludeContainerName: 'old-worker',
      excludeTags: {owner: 'platform'},
    }
    const expectedLogFilters = {
      cursor: 'cursor-1',
      containerName: 'worker',
      ...expectedFiltersWithoutCursor,
    }

    await waitFor(() => {
      expect(mockApi.getLogs).toHaveBeenCalledWith(expect.objectContaining(expectedLogFilters))
    })
    await waitFor(() => {
      expect(mockApi.getLogAggregate).toHaveBeenCalledWith(expect.objectContaining({
        ...expectedFiltersWithoutCursor,
        groupBy: 'service',
      }))
    })
    await waitFor(() => {
      expect(mockApi.getLogTop).toHaveBeenCalledWith(expect.objectContaining({
        ...expectedFiltersWithoutCursor,
        field: 'host',
        limit: 20,
      }))
    })
  })

  it('renders log rows and loads context for a selected log', async () => {
    mockApi.getLogs.mockResolvedValue({
      logs: [sampleLog()],
      nextCursor: null,
      hasMore: false,
      totalCount: 1,
    })
    mockApi.getLogAggregate.mockResolvedValue({
      buckets: [
        {timestamp: '2026-06-03T11:00:00.000Z', count: 1, groups: {error: 1}},
      ],
      totalCount: 1,
      interval: '1h',
    })

    renderLogExplorer({enableUrlSync: true})

    fireEvent.click(await screen.findByText('payment failed'))

    const viewer = await screen.findByLabelText('Log context viewer')
    expect(within(viewer).getByText('Event 1')).toBeInTheDocument()
    fireEvent.click(within(viewer).getByRole('button', {name: /Context/i}))

    await waitFor(() => {
      expect(mockApi.getLogs).toHaveBeenLastCalledWith(expect.objectContaining({
        from: '2026-06-03T11:57:45.000Z',
        to: '2026-06-03T11:58:15.000Z',
        limit: 200,
        host: 'host-1',
        service: undefined,
        traceId: undefined,
      }))
    })
  })

  it('reloads lazy tag facet values when the log time range changes', async () => {
    mockApi.getLogTagValues
      .mockResolvedValueOnce({key: 'team', values: ['payments']})
      .mockResolvedValueOnce({key: 'team', values: ['checkout']})

    renderLogExplorer()

    fireEvent.click(await screen.findByRole('button', {name: /team/i}))

    await waitFor(() => {
      expect(mockApi.getLogTagValues).toHaveBeenCalledWith('team', {
        from: '2026-05-27T12:00:00.000Z',
        to: undefined,
        limit: 30,
      })
    })
    expect(await screen.findByRole('button', {name: 'payments'})).toBeTruthy()

    fireEvent.click(screen.getByRole('button', {name: /Last 7 days/}))
    fireEvent.click(screen.getByRole('button', {name: 'Last 24 hours'}))

    await waitFor(() => {
      expect(mockApi.getLogTagValues).toHaveBeenCalledWith('team', {
        from: '2026-06-02T12:00:00.000Z',
        to: undefined,
        limit: 30,
      })
    })
    expect(await screen.findByRole('button', {name: 'checkout'})).toBeTruthy()
    expect(screen.queryByRole('button', {name: 'payments'})).toBeNull()
  })

  it('streams live-tail logs, closes on pause, and reconnects when filters change', async () => {
    const streams: MockLogTailStream[] = []
    mockApi.createLogTailStream.mockImplementation(() => {
      const stream = createMockLogTailStream()
      streams.push(stream)
      return stream
    })

    renderLogExplorer()

    fireEvent.click(await screen.findByRole('button', {name: 'Live tail'}))
    await waitFor(() => {
      expect(mockApi.createLogTailStream).toHaveBeenCalledWith(expect.objectContaining({
        query: undefined,
        levels: undefined,
      }))
    })

    streams[0].onopen?.({} as Event)
    streams[0].onmessage?.({
      data: JSON.stringify({
        log_id: 'live-1',
        timestamp: '2026-06-03T12:00:01.000Z',
        level: 'error',
        message: 'live payment failed',
        service: 'api',
        environment: 'prod',
        trace_id: 'trace-live',
      }),
    } as MessageEvent)

    expect(await screen.findByText('live payment failed')).toBeInTheDocument()

    streams[0].onmessage?.({
      data: JSON.stringify({
        log_id: 'live-1',
        timestamp: '2026-06-03T12:00:01.000Z',
        level: 'error',
        message: 'live payment failed',
        service: 'api',
        environment: 'prod',
      }),
    } as MessageEvent)
    expect(screen.getAllByText('live payment failed')).toHaveLength(1)

    const consoleError = vi.spyOn(console, 'error').mockImplementation(() => {})
    streams[0].onmessage?.({data: 'not-json'} as MessageEvent)
    expect(consoleError).toHaveBeenCalledWith('Live tail event parse failed:', expect.any(String))
    consoleError.mockRestore()

    const input = screen.getByPlaceholderText('Search...')
    fireEvent.change(input, {target: {value: 'level:error'}})
    fireEvent.keyDown(input, {key: 'Enter'})

    await waitFor(() => {
      expect(streams[0].close).toHaveBeenCalled()
      expect(mockApi.createLogTailStream).toHaveBeenLastCalledWith(expect.objectContaining({
        levels: ['error'],
      }))
    })

    streams.at(-1)?.onerror?.({} as Event)
    fireEvent.click(screen.getByRole('button', {name: 'Pause live'}))

    await waitFor(() => {
      expect(streams.at(-1)?.close).toHaveBeenCalled()
    })
  })
})
