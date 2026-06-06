import React from 'react'
import {QueryClient, QueryClientProvider} from '@tanstack/react-query'
import {beforeEach, describe, expect, it, vi} from 'vitest'
import {render, screen, waitFor} from '@testing-library/react'
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

import {Route as ServiceDetailRoute} from '../services.$service'
import {Route as ResourceDetailRoute} from '../services.$service.resources.$resource'

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
})
