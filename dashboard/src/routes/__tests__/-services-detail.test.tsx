import React from 'react'
import {QueryClient, QueryClientProvider} from '@tanstack/react-query'
import {beforeEach, describe, expect, it, vi} from 'vitest'
import {render, screen, waitFor} from '@testing-library/react'
import type {ApmResourceDetail, ApmServiceDetail} from '@/lib/api'
import {shouldRetryQuery} from '@/lib/query-retry'
import {clearAuthStorage, renderRouteWithProviders} from '@/test/utils'

const {mockApi, mockRouteParams, mockPathname} = vi.hoisted(() => ({
  mockApi: {
    getApmServiceDetail: vi.fn(),
    getApmResourceDetail: vi.fn(),
  },
  mockRouteParams: {
    current: {service: 'checkout-api', resource: 'post-checkout'},
  },
  mockPathname: {
    current: '/services/checkout-api',
  },
}))

vi.mock('@/lib/api', () => ({
  api: mockApi,
}))

vi.mock('@tanstack/react-router', () => ({
  createFileRoute: () => (options: Record<string, unknown>) => ({
    ...options,
    options,
    useParams: () => mockRouteParams.current,
  }),
  Link: ({children, ...props}: {children: React.ReactNode}) => React.createElement('a', props, children),
  Outlet: () => <div data-testid="services-outlet" />,
  useRouterState: () => mockPathname.current,
}))

vi.mock('@/components/ui/tabs', () => {
  const Panel = ({children, className}: Readonly<{children?: React.ReactNode; className?: string}>) => (
    <div className={className}>{children}</div>
  )
  const Trigger = ({
    children,
    value,
    className,
  }: Readonly<{children?: React.ReactNode; value?: string; className?: string}>) => (
    <button type="button" className={className} data-tab-value={value}>
      {children}
    </button>
  )

  return {
    Tabs: Panel,
    TabsContent: Panel,
    TabsList: Panel,
    TabsTrigger: Trigger,
  }
})

vi.mock('recharts', () => {
  const ChartPart = ({children, ...props}: {children?: React.ReactNode; [key: string]: unknown}) => (
    <g data-testid="chart-part" data-key={String(props.dataKey ?? props.x ?? props.y ?? '')}>
      {children}
    </g>
  )
  const ChartRoot = ({children}: {children?: React.ReactNode}) => <svg data-testid="chart-root">{children}</svg>

  return {
    Area: ChartPart,
    AreaChart: ChartRoot,
    CartesianGrid: ChartPart,
    Line: ChartPart,
    LineChart: ChartRoot,
    ReferenceArea: ChartPart,
    ReferenceLine: ChartPart,
    ResponsiveContainer: ({children}: {children?: React.ReactNode}) => (
      <div data-testid="responsive-container">{children}</div>
    ),
    Tooltip: ChartPart,
    XAxis: ChartPart,
    YAxis: ChartPart,
  }
})

import {Route as ServiceDetailRoute} from '../services.$service'
import {Route as ResourceDetailRoute} from '../services.$service.resources.$resource'

function serviceDetailFixture(): ApmServiceDetail {
  return {
    name: 'checkout-api',
    type: 'web',
    status: 'alerting',
    runtime: 'Kotlin · JVM',
    team: 'Payments',
    version: 'v2.14.0',
    deployedAgo: '18m ago',
    sources: ['otlp'],
    kpis: [
      {label: 'requests', value: '1.2k/s', delta: {value: '+12%', direction: 'up', tone: 'success'}},
      {label: 'p95 latency', value: '318ms', valueTone: 'warning'},
      {label: 'error rate', value: '2.4%', valueTone: 'danger'},
    ],
    latency: [
      {t: '10:00', p50: 42, p90: 110, p95: 180, p99: 420},
      {t: '10:05', p50: 48, p90: 130, p95: 318, p99: 610},
    ],
    latencyThresholdMs: 300,
    latencyThresholdLabel: 'SLO',
    deployAt: '10:05',
    throughput: [
      {t: '10:00', rps: 950, errors: 4},
      {t: '10:05', rps: 1200, errors: 28},
    ],
    errorBars: [
      {h: 28, level: 'warn'},
      {h: 72, level: 'bad'},
    ],
    p95ByResource: [
      {label: 'POST /checkout', valueText: '318ms', pct: 68, level: 'warn'},
      {label: 'GET /cart', valueText: '92ms', pct: 22, level: 'good'},
    ],
    resources: [
      {
        slug: 'post-checkout',
        method: 'POST',
        name: '/checkout',
        rps: 740,
        p50Ms: 41,
        p95Ms: 318,
        p99Ms: 620,
        errorRateLabel: '2.4%',
        errorBarPct: 44,
        errorLevel: 'bad',
        timePct: 42,
        status: 'alerting',
      },
      {
        slug: 'get-cart',
        method: 'GET',
        name: '/cart',
        rps: 460,
        p50Ms: 22,
        p95Ms: 92,
        p99Ms: 130,
        errorRateLabel: '0.1%',
        errorBarPct: 4,
        errorLevel: 'good',
        timePct: 12,
        status: 'healthy',
      },
    ],
    upstream: [
      {name: 'web-checkout', type: 'web', tone: 'success', rps: '740', p95: '88ms', err: '0.1%'},
    ],
    downstream: [
      {name: 'payments-db', type: 'db', tone: 'danger', rps: '610', p95: '420ms', err: '2.1%', errWarn: true},
    ],
    depInsight: 'payments-db owns most of the p95 latency for checkout-api.',
    deployments: [
      {
        version: 'v2.14.0',
        when: '18m ago',
        initials: 'AE',
        rps: '1.2k',
        errorRate: '2.4%',
        p95: '318ms',
        status: 'alerting',
        current: true,
        trendBad: true,
      },
      {
        version: 'v2.13.7',
        when: '2h ago',
        initials: 'PM',
        rps: '980',
        errorRate: '0.3%',
        p95: '180ms',
        status: 'retired',
      },
    ],
    podMemory: [
      {label: 'checkout-api-7f9d', valueText: '820Mi', pct: 76, level: 'warn'},
    ],
    pods: [
      {pod: 'checkout-api-7f9d', node: 'node-a', cpu: 87, mem: '820Mi', restarts: 2, tone: 'warning', state: 'Running'},
    ],
    errors: [
      {
        severity: 'fatal',
        title: 'PaymentTimeoutError',
        sub: 'CheckoutController.submit',
        chips: ['v2.14.0', 'prod'],
        events: '28',
        users: '11',
        unhandled: true,
      },
    ],
    traces: [
      {
        time: '10:06',
        traceId: 'trace-service-1',
        resource: '/checkout',
        method: 'POST',
        httpStatus: 500,
        durationMs: 610,
        spans: 18,
      },
    ],
  }
}

function resourceDetailFixture(): ApmResourceDetail {
  return {
    serviceName: 'checkout-api',
    method: 'POST',
    path: '/checkout',
    status: 'degraded',
    kind: 'http.server',
    topDependency: 'payments-db',
    kpis: [
      {label: 'requests', value: '740/s'},
      {label: 'p95 latency', value: '318ms', valueTone: 'warning'},
      {label: 'errors', value: '2.4%', valueTone: 'danger'},
    ],
    latency: [
      {t: '10:00', p50: 42, p90: 110, p95: 180, p99: 420},
      {t: '10:05', p50: 48, p90: 130, p95: 318, p99: 610},
    ],
    latencyThresholdMs: 300,
    latencyThresholdLabel: 'SLO',
    deployAt: '10:05',
    throughput: [
      {t: '10:00', rps: 650, errors: 2},
      {t: '10:05', rps: 740, errors: 18},
    ],
    distribution: [
      {h: 24, band: 'good'},
      {h: 52, band: 'warn'},
      {h: 88, band: 'bad'},
    ],
    distMarkers: [
      {label: 'p95', left: 58},
      {label: 'p99', left: 84, p99: true},
    ],
    distAxis: ['0ms', '300ms', '600ms'],
    whereTimeSpent: [
      {label: 'payments-db', valueText: '61%', pct: 61, level: 'bad'},
      {label: 'application', valueText: '24%', pct: 24, level: 'warn'},
    ],
    whereInsight: 'The database span is the slowest segment on the p95 path.',
    exemplar: {
      traceId: 'trace-resource-1',
      httpStatus: 500,
      durationLabel: '610ms',
      rows: [
        {op: 'POST', desc: '/checkout', left: 0, width: 100, label: '610ms', tone: 'root', selected: true},
        {op: 'db', desc: 'payments-db', left: 24, width: 61, label: '370ms', tone: 'db', indent: 1},
      ],
    },
    slowTraces: [
      {
        time: '10:06',
        traceId: 'trace-resource-1',
        httpStatus: 500,
        durationMs: 610,
        bucket: 'p99',
      },
    ],
    errors: [
      {
        severity: 'error',
        title: 'PaymentTimeoutError',
        sub: 'CheckoutController.submit',
        chips: ['v2.14.0', 'prod'],
        events: '18',
      },
    ],
  }
}

function apiError(status: number): Error & {status: number} {
  const error = new Error(`API Error: ${status}`) as Error & {status: number}
  error.status = status
  return error
}

describe('Services detail route', () => {
  beforeEach(() => {
    vi.clearAllMocks()
    clearAuthStorage()
    mockRouteParams.current = {service: 'checkout-api', resource: 'post-checkout'}
    mockPathname.current = '/services/checkout-api'
  })

  it('does not report service not found for a failed detail request', async () => {
    mockApi.getApmServiceDetail.mockRejectedValue(apiError(500))
    const Component = (ServiceDetailRoute as unknown as {component: React.ComponentType}).component

    renderRouteWithProviders(Component)

    expect(await screen.findByText('Service telemetry failed to load.')).toBeInTheDocument()
    expect(screen.queryByText(/was not found/)).not.toBeInTheDocument()
  })

  it('renders a populated service detail surface', async () => {
    mockApi.getApmServiceDetail.mockResolvedValue(serviceDetailFixture())
    const Component = (ServiceDetailRoute as unknown as {component: React.ComponentType}).component

    renderRouteWithProviders(Component)

    expect(await screen.findByText('Latency percentiles')).toBeInTheDocument()
    expect(screen.getByText('Throughput · req/s')).toBeInTheDocument()
    expect(screen.getByText('Errors over time')).toBeInTheDocument()
    expect(screen.getByText('p95 by resource · top 5')).toBeInTheDocument()
    expect(screen.getByRole('button', {name: 'Resources2'})).toBeInTheDocument()
    expect(screen.getByRole('button', {name: 'Dependencies2'})).toBeInTheDocument()
    expect(screen.getByText('Upstream · callers')).toBeInTheDocument()
    expect(screen.getByText('Downstream · dependencies')).toBeInTheDocument()
    expect(screen.getByText('Deployments')).toBeInTheDocument()
    expect(screen.getByText('Memory by pod')).toBeInTheDocument()
    expect(screen.getByText('Pods')).toBeInTheDocument()
    expect(screen.getByText('PaymentTimeoutError')).toBeInTheDocument()
    expect(screen.getByText('Open in trace explorer')).toBeInTheDocument()
    expect(screen.getByText('payments-db owns most of the p95 latency for checkout-api.')).toBeInTheDocument()
    expect(screen.getAllByText('checkout-api-7f9d')).not.toHaveLength(0)

    await waitFor(() => {
      expect(mockApi.getApmServiceDetail).toHaveBeenCalledWith('checkout-api', {timeRange: '1h'})
    })
  })

  it('loads service detail by service identity without forcing an environment filter', async () => {
    mockApi.getApmServiceDetail.mockRejectedValue(apiError(404))
    const Component = (ServiceDetailRoute as unknown as {component: React.ComponentType}).component

    renderRouteWithProviders(Component)

    await waitFor(() => {
      expect(mockApi.getApmServiceDetail).toHaveBeenCalledWith('checkout-api', {timeRange: '1h'})
    })
  })

  it('uses the global retry policy to avoid retrying 404 service detail requests', async () => {
    mockApi.getApmServiceDetail.mockRejectedValue(apiError(404))
    const Component = (ServiceDetailRoute as unknown as {component: React.ComponentType}).component
    const queryClient = new QueryClient({
      defaultOptions: {
        queries: {retry: shouldRetryQuery, retryDelay: 1},
      },
    })

    render(
      <QueryClientProvider client={queryClient}>
        <Component />
      </QueryClientProvider>,
    )

    expect(await screen.findByText(/was not found/)).toBeInTheDocument()
    expect(mockApi.getApmServiceDetail).toHaveBeenCalledTimes(1)
  })

  it('uses the global retry policy to avoid retrying 404 resource detail requests', async () => {
    mockPathname.current = '/services/checkout-api/resources/post-checkout'
    mockApi.getApmResourceDetail.mockRejectedValue(apiError(404))
    const Component = (ResourceDetailRoute as unknown as {component: React.ComponentType}).component
    const queryClient = new QueryClient({
      defaultOptions: {
        queries: {retry: shouldRetryQuery, retryDelay: 1},
      },
    })

    render(
      <QueryClientProvider client={queryClient}>
        <Component />
      </QueryClientProvider>,
    )

    expect(await screen.findByText('This resource was not found.')).toBeInTheDocument()
    expect(mockApi.getApmResourceDetail).toHaveBeenCalledTimes(1)
  })

  it('renders a populated resource detail surface', async () => {
    mockPathname.current = '/services/checkout-api/resources/post-checkout'
    mockApi.getApmResourceDetail.mockResolvedValue(resourceDetailFixture())
    const Component = (ResourceDetailRoute as unknown as {component: React.ComponentType}).component

    renderRouteWithProviders(Component)

    expect(await screen.findByText('Latency distribution')).toBeInTheDocument()
    expect(screen.getByText('Where time is spent · p95 path')).toBeInTheDocument()
    expect(screen.getByText('The database span is the slowest segment on the p95 path.')).toBeInTheDocument()
    expect(screen.getByText('Exemplar trace · slowest in range')).toBeInTheDocument()
    expect(screen.getByText('Open full trace')).toBeInTheDocument()
    expect(screen.getByText('Slow & failed traces')).toBeInTheDocument()
    expect(screen.getByText('Errors on this resource')).toBeInTheDocument()
    expect(screen.getAllByText('trace-resource-1')).not.toHaveLength(0)
    expect(screen.getAllByText('payments-db')).not.toHaveLength(0)

    await waitFor(() => {
      expect(mockApi.getApmResourceDetail).toHaveBeenCalledWith('checkout-api', 'post-checkout', {timeRange: '1h'})
    })
  })
})
