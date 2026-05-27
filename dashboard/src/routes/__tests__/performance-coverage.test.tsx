import React from 'react'
import {beforeEach, describe, expect, it, vi} from 'vitest'
import {QueryClient, QueryClientProvider} from '@tanstack/react-query'
import {fireEvent, render, screen, waitFor} from '@testing-library/react'

const {mockApi} = vi.hoisted(() => ({
  mockApi: {
    isAuthenticated: vi.fn(),
    getApmOverview: vi.fn(),
    getApmTraces: vi.fn(),
    getApmResourceStats: vi.fn(),
  },
}))

vi.mock('@/lib/api', () => ({
  api: mockApi,
}))

vi.mock('@tanstack/react-router', () => ({
  createFileRoute: () => (options: Record<string, unknown>) => ({
    ...options,
    options,
    useParams: () => ({}),
  }),
  Link: ({children, ...props}: {children: React.ReactNode}) => React.createElement('a', props, children),
  Outlet: () => <div data-testid="performance-outlet" />,
  redirect: (opts: Record<string, unknown>) => ({...opts, __redirect: true}),
  useRouterState: () => ({location: {pathname: '/performance/traces'}}),
}))

vi.mock('recharts', () => ({
  CartesianGrid: () => <div data-testid="cartesian-grid" />,
  Line: () => <div data-testid="chart-line" />,
  LineChart: ({children}: {children: React.ReactNode}) => <div data-testid="line-chart">{children}</div>,
  ResponsiveContainer: ({children}: {children: React.ReactNode}) => <div>{children}</div>,
  Tooltip: () => <div data-testid="chart-tooltip" />,
  XAxis: () => <div data-testid="x-axis" />,
  YAxis: () => <div data-testid="y-axis" />,
}))

import {Route as PerformanceIndexRoute} from '../performance.index'
import {Route as PerformanceLayoutRoute} from '../performance'
import {Route as PerformanceTracesRoute} from '../performance.traces.index'

const mockOverview = {
  stats: {
    totalTraces: 24732,
    errorTraces: 580,
    errorRate: 0.0235,
    serviceCount: 5,
    sourceCount: 2,
    p50DurationNs: 42100000,
    p95DurationNs: 312000000,
    p99DurationNs: 842000000,
    avgSpansPerTrace: 18.7,
    previous: {
      totalTraces: 20852,
      errorRate: 0.0277,
      p50DurationNs: 44900000,
      p95DurationNs: 268000000,
      p99DurationNs: 730000000,
      avgSpansPerTrace: 15.6,
    },
  },
  latencySeries: [
    {
      timestamp: '2026-05-26T10:00:00.000Z',
      p50DurationNs: 42100000,
      p95DurationNs: 312000000,
      p99DurationNs: 842000000,
    },
  ],
  serviceHealth: [
    {
      service: 'checkout-service',
      source: 'otlp',
      traceCount: 5642,
      errorCount: 326,
      errorRate: 0.0578,
      p95DurationNs: 612000000,
      avgSpansPerTrace: 22.4,
    },
  ],
  resourceHotspots: [
    {
      service: 'checkout-service',
      resource: 'POST /checkout',
      source: 'otlp',
      traceCount: 1234,
      errorCount: 154,
      errorRate: 0.1245,
      p95DurationNs: 612000000,
    },
  ],
  errors: [],
  facets: {
    services: [{value: 'checkout-service', count: 5642}],
    sources: [{value: 'otlp', count: 5000}],
    environments: [{value: 'production', count: 5000}],
  },
}

const mockTrace = {
  traceId: '4f8a2c9b8e7f4a1a8c1d2e3f4b5c6d7e',
  rootService: 'checkout-service',
  rootResource: 'POST /checkout',
  rootName: 'web.request',
  spanCount: 42,
  durationNs: 842000000,
  startNs: Date.now() * 1_000_000,
  hasError: true,
  source: 'otlp',
}

const scrollIntoViewMock = vi.fn()

function renderWithQueryClient(Component: React.ComponentType) {
  const queryClient = new QueryClient({
    defaultOptions: {queries: {retry: false}},
  })
  return render(
    <QueryClientProvider client={queryClient}>
      <Component />
    </QueryClientProvider>
  )
}

describe('Performance routes', () => {
  beforeEach(() => {
    vi.clearAllMocks()
    Object.defineProperty(globalThis.HTMLElement.prototype, 'scrollIntoView', {
      configurable: true,
      value: scrollIntoViewMock,
    })
    mockApi.isAuthenticated.mockReturnValue(true)
    mockApi.getApmOverview.mockResolvedValue(mockOverview)
    mockApi.getApmTraces.mockResolvedValue({
      traces: [mockTrace],
      totalCount: 1,
    })
    mockApi.getApmResourceStats.mockResolvedValue({
      resources: [{
        service: 'checkout-service',
        resource: 'POST /checkout',
        name: 'web.request',
        type: '',
        totalHits: 42,
        totalErrors: 7,
        avgDurationNs: 321000000,
        errorRate: 0.1667,
      }],
      totalCount: 1,
    })
  })

  it('redirects the performance index to traces', async () => {
    const beforeLoad = (PerformanceIndexRoute as unknown as {beforeLoad: () => Promise<void>}).beforeLoad

    await expect(beforeLoad()).rejects.toMatchObject({
      __redirect: true,
      to: '/performance/traces',
    })
  })

  it('renders traces as the primary performance tab without transactions', () => {
    const Component = (PerformanceLayoutRoute as unknown as {component: React.ComponentType}).component

    render(<Component />)

    expect(screen.getByText('Traces')).toBeInTheDocument()
    expect(screen.getByText('Service Map')).toBeInTheDocument()
    expect(screen.queryByText('Transactions')).not.toBeInTheDocument()
    expect(screen.getByTestId('performance-outlet')).toBeInTheDocument()
  })

  it('renders the traces dashboard analysis surface', async () => {
    const Component = (PerformanceTracesRoute as unknown as {component: React.ComponentType}).component
    renderWithQueryClient(Component)

    expect(await screen.findByText('Health overview')).toBeInTheDocument()
    expect(screen.getByText('Service health')).toBeInTheDocument()
    expect(screen.getByText('Latency distribution (ms)')).toBeInTheDocument()
    expect(screen.getByText('Errors & top resources')).toBeInTheDocument()
    expect(screen.getByText('Recent traces')).toBeInTheDocument()
    expect(screen.getAllByText('Source').length).toBeGreaterThan(0)
    expect((await screen.findAllByText('POST /checkout')).length).toBeGreaterThan(0)
    await waitFor(() => {
      expect(mockApi.getApmTraces).toHaveBeenCalledWith(expect.objectContaining({
        limit: 25,
        offset: 0,
      }))
    })
  })

  it('opens a real paged erroring resources view', async () => {
    const Component = (PerformanceTracesRoute as unknown as {component: React.ComponentType}).component
    renderWithQueryClient(Component)

    const viewAllButton = await screen.findByRole('button', {name: /View all erroring resources/i})
    expect(viewAllButton).toHaveClass('cursor-pointer')
    fireEvent.click(viewAllButton)

    expect(await screen.findByText('All erroring resources')).toBeInTheDocument()
    await waitFor(() => expect(scrollIntoViewMock).toHaveBeenCalledTimes(1))
    await waitFor(() => {
      expect(mockApi.getApmResourceStats).toHaveBeenCalledWith(expect.objectContaining({
        status: 'error',
        limit: 25,
        offset: 0,
      }))
    })

    fireEvent.click(viewAllButton)
    await waitFor(() => expect(scrollIntoViewMock).toHaveBeenCalledTimes(2))
  })
})
