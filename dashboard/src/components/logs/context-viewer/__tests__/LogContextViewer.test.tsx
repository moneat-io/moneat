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

import {beforeEach, describe, it, expect, vi} from 'vitest'
import {fireEvent, render, screen, waitFor} from '@testing-library/react'
import {QueryClient, QueryClientProvider} from '@tanstack/react-query'
import type {ApmSpanResponse, LogEntry} from '@/lib/api'
import {LogContextViewer} from '../LogContextViewer'

const {mockApi} = vi.hoisted(() => ({
  mockApi: {
    getLogs: vi.fn(),
    getLogPattern: vi.fn(),
    getApmTraceDetail: vi.fn(),
  },
}))

vi.mock('@/lib/api', async (importOriginal) => {
  const actual = await importOriginal<typeof import('@/lib/api')>()
  return {
    ...actual,
    api: {
      ...actual.api,
      ...mockApi,
    },
  }
})

// traceId is intentionally empty so no trace/pattern/context query fires on the
// default Content tab — this keeps the smoke test free of network.
function makeLog(overrides: Partial<LogEntry> = {}): LogEntry {
  return {
    logId: 'log-1',
    timestamp: '2026-06-08T14:32:08.412Z',
    level: 'error',
    message: 'Payment gateway timeout after 3 retries for order ord_8F2K19',
    body: '',
    service: 'payments-api',
    environment: 'production',
    host: 'ip-10-2-43-118',
    source: 'otlp',
    containerName: '',
    containerId: '',
    containerImage: '',
    traceId: '',
    spanId: '',
    tags: {'http.method': 'POST', 'http.status_code': '504'},
    resourceAttributes: {},
    ...overrides,
  }
}

function makeSpan(overrides: Partial<ApmSpanResponse> = {}): ApmSpanResponse {
  return {
    spanId: 'span-1',
    traceId: 'trace-1',
    parentId: '',
    name: 'GET /checkout',
    service: 'checkout',
    resource: 'GET /checkout',
    type: 'server',
    startNs: 1_780_000_000_000_000_000,
    durationNs: 40_000_000,
    error: 0,
    meta: {},
    metrics: {},
    host: 'host-1',
    env: 'production',
    version: '1.0.0',
    source: 'otlp',
    ...overrides,
  }
}

function createQueryClient() {
  return new QueryClient({
    defaultOptions: {queries: {retry: false}, mutations: {retry: false}},
  })
}

function buildViewerProps(extra: Partial<Parameters<typeof LogContextViewer>[0]> = {}) {
  const log = extra.log ?? makeLog()
  return {
    log,
    logs: [log, makeLog({logId: 'log-2', message: 'next event'})],
    index: 0,
    total: 248,
    onNavigate: vi.fn(),
    onClose: vi.fn(),
    onAddFacetFilter: vi.fn(),
    ...extra,
  }
}

function renderViewerElement(
  props: Parameters<typeof LogContextViewer>[0],
  queryClient: QueryClient
) {
  return (
    <QueryClientProvider client={queryClient}>
      <LogContextViewer {...props} />
    </QueryClientProvider>
  )
}

function renderViewer(extra: Partial<Parameters<typeof LogContextViewer>[0]> = {}) {
  const queryClient = createQueryClient()
  const props = buildViewerProps(extra)
  const view = render(renderViewerElement(props, queryClient))
  return Object.assign(view, props, {queryClient})
}

describe('LogContextViewer', () => {
  beforeEach(() => {
    vi.clearAllMocks()
    mockApi.getLogs.mockResolvedValue({
      logs: [],
      nextCursor: null,
      hasMore: false,
      totalCount: 0,
    })
    mockApi.getApmTraceDetail.mockResolvedValue({
      spans: [
        makeSpan(),
        makeSpan({
          spanId: 'span-2',
          parentId: 'span-1',
          service: 'payments-db',
          resource: 'SELECT payments',
          type: 'db',
          startNs: 1_780_000_000_010_000_000,
          durationNs: 20_000_000,
          error: 1,
        }),
      ],
    })
    mockApi.getLogPattern.mockResolvedValue({
      pattern: 'Payment gateway timeout after <int> retries for order <id>',
      level: 'error',
      count: 1,
      windowLabel: '24h',
      firstSeen: '',
      lastSeen: '',
      trendPct: null,
      sparkline: [1, 0, 0],
      topServices: [],
      topHosts: [],
    })
  })

  it('renders the headline, severity, event counter and all tabs', () => {
    renderViewer()
    // The message shows in both the headline and the Content panel.
    expect(screen.getAllByText(/Payment gateway timeout after 3 retries/).length).toBeGreaterThan(0)
    expect(screen.getByText('error')).toBeInTheDocument()
    expect(screen.getByText('Event 1')).toBeInTheDocument()
    expect(screen.getByText('of 248')).toBeInTheDocument()
    for (const tab of ['Content', 'Context', 'Trace', 'Attributes', 'Patterns']) {
      expect(screen.getByText(tab)).toBeInTheDocument()
    }
  })

  it('shows grouped attributes when the Attributes tab is opened', () => {
    renderViewer()
    fireEvent.click(screen.getByText('Attributes'))
    expect(screen.getByText('http.status_code')).toBeInTheDocument()
    expect(screen.getByText('504')).toBeInTheDocument()
  })

  it('navigates events via the next button and J/K keys', () => {
    const {onNavigate} = renderViewer()
    fireEvent.click(screen.getByTitle('Next event (J)'))
    expect(onNavigate).toHaveBeenCalledWith(1)

    window.dispatchEvent(new KeyboardEvent('keydown', {key: 'j'}))
    expect(onNavigate).toHaveBeenCalledTimes(2)
  })

  it('closes on Escape', () => {
    const {onClose} = renderViewer()
    window.dispatchEvent(new KeyboardEvent('keydown', {key: 'Escape'}))
    expect(onClose).toHaveBeenCalled()
  })

  it('forwards facet filters from a chip action', () => {
    const {onAddFacetFilter} = renderViewer()
    // The service chip exposes a "Filter to" action.
    const filterButtons = screen.getAllByTitle('Filter to')
    fireEvent.click(filterButtons[0])
    expect(onAddFacetFilter).toHaveBeenCalledWith('service', 'payments-api', false)
  })

  it('does not query surrounding context for an invalid timestamp', async () => {
    renderViewer({log: makeLog({timestamp: 'not-a-date'})})

    fireEvent.click(screen.getByText('Context'))

    expect(await screen.findByText(/invalid timestamp/i)).toBeInTheDocument()
    expect(mockApi.getLogs).not.toHaveBeenCalled()
  })

  it('falls back to all logs when the next selected log lacks the active scope field', async () => {
    const firstLog = makeLog({logId: 'log-1', host: 'host-1'})
    const nextLog = makeLog({logId: 'log-2', host: '', timestamp: '2026-06-08T14:33:08.412Z'})
    const view = renderViewer({log: firstLog, logs: [firstLog, nextLog]})

    fireEvent.click(screen.getByText('Context'))
    await waitFor(() => {
      expect(mockApi.getLogs).toHaveBeenLastCalledWith(expect.objectContaining({host: 'host-1'}))
    })

    const nextProps = buildViewerProps({log: nextLog, logs: [nextLog], index: 0})
    view.rerender(renderViewerElement(nextProps, view.queryClient))

    await waitFor(() => {
      expect(mockApi.getLogs).toHaveBeenLastCalledWith(expect.objectContaining({
        host: undefined,
        service: undefined,
        traceId: undefined,
      }))
    })
    expect(screen.getByText(/all logs/)).toBeInTheDocument()
  })

  it('refetches pattern rollups when the time range changes', async () => {
    const firstRange = {from: '2026-06-08T13:00:00.000Z', to: '2026-06-08T14:00:00.000Z'}
    const nextRange = {from: '2026-06-08T14:00:00.000Z', to: '2026-06-08T15:00:00.000Z'}
    const log = makeLog()
    const view = renderViewer({log, timeRange: firstRange})

    fireEvent.click(screen.getByText('Patterns'))
    await waitFor(() => {
      expect(mockApi.getLogPattern).toHaveBeenCalledWith(expect.objectContaining(firstRange))
    })

    const nextProps = buildViewerProps({log, timeRange: nextRange})
    view.rerender(renderViewerElement(nextProps, view.queryClient))

    await waitFor(() => {
      expect(mockApi.getLogPattern).toHaveBeenCalledWith(expect.objectContaining(nextRange))
    })
    expect(mockApi.getLogPattern).toHaveBeenCalledTimes(2)
  })

  it('launches create-metric and create-monitor from the Patterns tab', async () => {
    const onCreateMetric = vi.fn()
    const onCreateMonitor = vi.fn()
    renderViewer({onCreateMetric, onCreateMonitor})

    fireEvent.click(screen.getByText('Patterns'))

    fireEvent.click(await screen.findByRole('button', {name: /Create log metric/}))
    expect(onCreateMetric).toHaveBeenCalled()

    fireEvent.click(screen.getByRole('button', {name: /Create monitor/}))
    expect(onCreateMonitor).toHaveBeenCalled()
  })

  it('wires share, expand, trace, and pattern actions from connected context', async () => {
    const writeText = vi.fn().mockResolvedValue(undefined)
    const share = vi.fn().mockRejectedValue(new Error('cancelled'))
    Object.defineProperty(globalThis.navigator, 'clipboard', {
      configurable: true,
      value: {writeText},
    })
    Object.defineProperty(globalThis.navigator, 'share', {
      configurable: true,
      value: share,
    })
    const onToggleExpand = vi.fn()
    const onAddFacetFilter = vi.fn()
    const log = makeLog({
      traceId: 'trace-1',
      spanId: 'span-2',
      resourceAttributes: {
        'cloud.region': 'us-east-1',
        'k8s.pod.name': 'checkout-pod',
      },
    })
    renderViewer({
      log,
      onToggleExpand,
      onAddFacetFilter,
      timeRange: {from: '2026-06-08T00:00:00.000Z', to: '2026-06-09T00:00:00.000Z'},
    })

    fireEvent.click(screen.getByText('Copy link'))
    await waitFor(() => expect(writeText).toHaveBeenCalledWith(globalThis.window.location.href))

    fireEvent.click(screen.getByTitle('Share'))
    await waitFor(() => expect(share).toHaveBeenCalledWith({
      title: 'Moneat log',
      text: 'Payment gateway timeout after 3 retries for order ord_8F2K19',
      url: globalThis.window.location.href,
    }))

    fireEvent.click(screen.getByTitle('Expand'))
    expect(onToggleExpand).toHaveBeenCalled()

    fireEvent.click(screen.getAllByTitle('Exclude')[0])
    expect(onAddFacetFilter).toHaveBeenCalledWith('service', 'payments-api', true)

    await screen.findByText(/2 spans/)
    fireEvent.click(screen.getAllByRole('button', {name: /Trace/})[0])
    expect(await screen.findByText('SELECT payments')).toBeInTheDocument()
    fireEvent.click(screen.getByRole('button', {name: /All logs in this trace/}))
    expect(onAddFacetFilter).toHaveBeenCalledWith('trace_id', 'trace-1')

    fireEvent.click(screen.getAllByRole('button', {name: /Pattern/})[0])
    fireEvent.click(await screen.findByRole('button', {name: /View 1 matching logs/}))
    expect(onAddFacetFilter).toHaveBeenCalledWith(
      'message_pattern',
      'Payment gateway timeout after <int> retries for order <id>'
    )
    fireEvent.click(screen.getByRole('button', {name: /Create log metric/}))
    fireEvent.click(screen.getByRole('button', {name: /Create monitor/}))
  })
})
