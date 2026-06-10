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
import {fireEvent, render, screen, waitFor} from '@testing-library/react'
import type {ReactElement} from 'react'
import {beforeEach, describe, expect, it, vi} from 'vitest'
import type {ApmSpanResponse, LogEntry} from '@/lib/api'
import {AttributesPanel} from '../AttributesPanel'
import {CodeBox, JsonHighlight} from '../CodeBox'
import {ContentPanel} from '../ContentPanel'
import {CopyButton} from '../CopyButton'
import {PatternsPanel} from '../PatternsPanel'
import {TracePanel} from '../TracePanel'

const {mockApi} = vi.hoisted(() => ({
  mockApi: {
    getLogPattern: vi.fn(),
  },
}))

vi.mock('@/lib/api', () => ({
  api: mockApi,
}))

function renderWithClient(ui: ReactElement) {
  const queryClient = new QueryClient({
    defaultOptions: {
      queries: {
        retry: false,
      },
    },
  })
  return render(<QueryClientProvider client={queryClient}>{ui}</QueryClientProvider>)
}

function makeLog(overrides: Partial<LogEntry> = {}): LogEntry {
  return {
    logId: 'log-1',
    timestamp: '2026-06-01T12:00:00.000Z',
    level: 'error',
    message: 'Order 123 failed for user usr_abc',
    body: '{"orderId":123,"ok":false}',
    service: 'checkout',
    environment: 'prod',
    host: 'host-1',
    source: 'otlp',
    containerName: 'worker',
    containerId: 'container-1',
    containerImage: 'checkout:latest',
    traceId: 'trace-1',
    spanId: 'span-2',
    tags: {
      team: 'payments',
      'http.status_code': '500',
      'exception.type': 'RuntimeException',
      'exception.message': 'database unavailable',
      'exception.stacktrace': 'RuntimeException: database unavailable\n at Checkout.kt:42',
    },
    resourceAttributes: {
      region: 'us-east-1',
      'cloud.provider': 'aws',
    },
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
    durationNs: 20_000_000,
    error: 0,
    meta: {},
    metrics: {},
    host: 'host-1',
    env: 'prod',
    version: '1.2.3',
    source: 'otlp',
    ...overrides,
  }
}

describe('context viewer panels', () => {
  beforeEach(() => {
    vi.clearAllMocks()
  })

  it('renders log content with JSON body and exception details', () => {
    render(<ContentPanel log={makeLog()} />)

    expect(screen.getByText('Message')).toBeInTheDocument()
    expect(screen.getByText('Order 123 failed for user usr_abc')).toBeInTheDocument()
    expect(screen.getByText('JSON')).toBeInTheDocument()
    expect(screen.getByText('RuntimeException')).toBeInTheDocument()
    expect(screen.getByText('database unavailable')).toBeInTheDocument()
    expect(screen.getByText(/Checkout.kt:42/)).toBeInTheDocument()
  })

  it('filters attributes, opens JSON view, and sends facet actions', () => {
    const onAddFacetFilter = vi.fn()
    render(
      <AttributesPanel
        log={makeLog()}
        traceHref="/performance/traces/trace-1"
        spanHref="/performance/traces/trace-1?span=span-2"
        onAddFacetFilter={onAddFacetFilter}
      />
    )

    expect(screen.getByText('service')).toBeInTheDocument()
    expect(screen.getByText('checkout')).toBeInTheDocument()
    expect(screen.getByText('team')).toBeInTheDocument()

    fireEvent.change(screen.getByPlaceholderText(/Filter .* attributes/), {target: {value: 'team'}})
    expect(screen.getByText('payments')).toBeInTheDocument()
    expect(screen.queryByText('cloud.provider')).toBeNull()

    fireEvent.click(screen.getAllByTitle('Filter to')[0])
    expect(onAddFacetFilter).toHaveBeenCalledWith('team', 'payments', false)

    fireEvent.click(screen.getByRole('button', {name: 'json'}))
    expect(screen.getByText('"resource":')).toBeInTheDocument()
    expect(screen.getByText('"cloud.provider":')).toBeInTheDocument()
  })

  it('renders trace empty, loading, error, and populated states', () => {
    const onViewAllTraceLogs = vi.fn()
    const {rerender} = render(
      <TracePanel log={makeLog({traceId: ''})} spans={[]} isLoading={false} isError={false} />
    )

    expect(screen.getByText('This log is not part of a trace.')).toBeInTheDocument()

    rerender(<TracePanel log={makeLog()} spans={[]} isLoading isError={false} />)
    expect(screen.getByText(/Loading trace/)).toBeInTheDocument()

    rerender(<TracePanel log={makeLog()} spans={[]} isLoading={false} isError />)
    expect(screen.getByText('Trace details are unavailable.')).toBeInTheDocument()

    rerender(<TracePanel log={makeLog()} spans={[]} isLoading={false} isError={false} />)
    expect(screen.getByText('No spans found for this trace.')).toBeInTheDocument()

    rerender(
      <TracePanel
        log={makeLog()}
        spans={[
          makeSpan({spanId: 'span-1', parentId: '', service: 'edge', durationNs: 40_000_000}),
          makeSpan({
            spanId: 'span-2',
            parentId: 'span-1',
            service: 'checkout',
            resource: 'SELECT orders',
            type: 'db',
            startNs: 1_780_000_000_010_000_000,
            durationNs: 12_000_000,
            error: 1,
          }),
        ]}
        isLoading={false}
        isError={false}
        traceHref="/performance/traces/trace-1"
        onViewAllTraceLogs={onViewAllTraceLogs}
      />
    )

    expect(screen.getByText('Duration')).toBeInTheDocument()
    expect(screen.getByText('Services')).toBeInTheDocument()
    expect(screen.getByText('checkout')).toBeInTheDocument()
    expect(screen.getByText('SELECT orders')).toBeInTheDocument()
    expect(screen.getByText('ERR')).toBeInTheDocument()

    fireEvent.click(screen.getByRole('button', {name: /All logs in this trace/}))
    expect(onViewAllTraceLogs).toHaveBeenCalled()
    expect(screen.getByRole('link', {name: /View full trace/})).toHaveAttribute(
      'href',
      '/performance/traces/trace-1'
    )
    expect(screen.getByRole('link', {name: /Open span/})).toHaveAttribute(
      'href',
      '/performance/traces/trace-1?span=span-2'
    )
  })

  it('loads pattern rollups and forwards pattern actions', async () => {
    mockApi.getLogPattern.mockResolvedValue({
      pattern: 'Order <int> failed for user <id>',
      level: 'error',
      count: 12,
      windowLabel: '24h',
      firstSeen: '2026-06-01T00:00:00.000Z',
      lastSeen: '2026-06-01T12:00:00.000Z',
      trendPct: 50,
      sparkline: [1, 4, 12],
      topServices: [{value: 'checkout', count: 9}],
      topHosts: [{value: 'host-1', count: 7}],
    })
    const onViewMatching = vi.fn()
    const onCreateMetric = vi.fn()
    const onCreateMonitor = vi.fn()

    renderWithClient(
      <PatternsPanel
        log={makeLog()}
        from="2026-06-01T00:00:00.000Z"
        to="2026-06-02T00:00:00.000Z"
        onViewMatching={onViewMatching}
        onCreateMetric={onCreateMetric}
        onCreateMonitor={onCreateMonitor}
      />
    )

    await waitFor(() => {
      expect(mockApi.getLogPattern).toHaveBeenCalledWith({
        logId: 'log-1',
        message: 'Order 123 failed for user usr_abc',
        service: 'checkout',
        from: '2026-06-01T00:00:00.000Z',
        to: '2026-06-02T00:00:00.000Z',
      })
    })
    expect(await screen.findByText('<int>')).toBeInTheDocument()
    expect(screen.getByText('Top services')).toBeInTheDocument()
    expect(screen.getByText('Top hosts')).toBeInTheDocument()

    fireEvent.click(screen.getByRole('button', {name: /View 12 matching logs/}))
    fireEvent.click(screen.getByRole('button', {name: /Create log metric/}))
    fireEvent.click(screen.getByRole('button', {name: /Create monitor/}))
    expect(onViewMatching).toHaveBeenCalledWith('Order <int> failed for user <id>')
    expect(onCreateMetric).toHaveBeenCalled()
    expect(onCreateMonitor).toHaveBeenCalled()
  })

  it('renders code highlighting and clipboard actions', async () => {
    const writeText = vi.fn().mockResolvedValue(undefined)
    Object.defineProperty(globalThis.navigator, 'clipboard', {
      configurable: true,
      value: {writeText},
    })

    render(
      <div>
        <CodeBox copyValue="abc" copyLabel="sample">
          <JsonHighlight value={{name: 'checkout', count: 3, enabled: true}} />
        </CodeBox>
        <CopyButton value="direct" label="direct value" />
        <CopyButton value="-" label="empty" />
      </div>
    )

    expect(screen.getByText('"name":')).toBeInTheDocument()
    expect(screen.getByText('"checkout"')).toBeInTheDocument()
    expect(screen.getByText('3')).toBeInTheDocument()
    expect(screen.queryByTitle('Copy empty')).toBeNull()

    const directButton = screen.getByTitle('Copy direct value')
    fireEvent.click(directButton)
    await waitFor(() => expect(writeText).toHaveBeenCalledWith('direct'))
  })
})
