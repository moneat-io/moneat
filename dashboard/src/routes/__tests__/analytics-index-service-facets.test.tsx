import React from 'react'
import {beforeEach, describe, expect, it, vi} from 'vitest'
import {fireEvent, screen, waitFor} from '@testing-library/react'
import {clearAuthStorage, renderRoute} from '@/test/utils'

type AnalyticsRouteParams = {
  period: string
  customFrom?: string
  customTo?: string
}

const {mockApi, mockAnalyticsParams} = vi.hoisted(() => ({
  mockApi: {
    isAuthenticated: vi.fn(),
    checkAuth: vi.fn(),
    getCurrentUser: vi.fn(),
    updateUserTimezone: vi.fn(),
    getProjects: vi.fn(),
    getAnalyticsOverview: vi.fn(),
    getAnalyticsTimeseries: vi.fn(),
    getAnalyticsPages: vi.fn(),
    getAnalyticsEntryPages: vi.fn(),
    getAnalyticsExitPages: vi.fn(),
    getAnalyticsSources: vi.fn(),
    getAnalyticsLocations: vi.fn(),
    getAnalyticsDevices: vi.fn(),
    getAnalyticsUtm: vi.fn(),
    getAnalyticsEvents: vi.fn(),
    getAnalyticsRetention: vi.fn(),
    getAnalyticsFunnel: vi.fn(),
  },
  mockAnalyticsParams: {
    current: {
      period: '7d',
      customFrom: undefined,
      customTo: undefined,
    } as AnalyticsRouteParams,
  },
}))

vi.mock('@/lib/api', () => ({
  api: mockApi,
}))

vi.mock('@/contexts/UseAnalyticsParams', () => ({
  useAnalyticsParams: () => mockAnalyticsParams.current,
}))

vi.mock('@tanstack/react-router', () => ({
  createFileRoute: () => (options: Record<string, unknown>) => ({
    ...options,
    options,
    useParams: () => ({}),
  }),
  Link: ({children, ...props}: {children: React.ReactNode}) => React.createElement('a', props, children),
  Outlet: () => null,
  redirect: (opts: Record<string, unknown>) => ({...opts, __redirect: true}),
  useMatches: () => [],
}))

import {Route as AnalyticsIndexRoute} from '../analytics.index'

const mockProjects = [
  {
    id: 1,
    resourceId: 'svc-web',
    name: 'Web Service',
    slug: 'web-service',
    platform: 'javascript',
    keys: [],
    dsn: 'https://public@example.com/api/1',
  },
  {
    id: 2,
    resourceId: 'svc-worker',
    name: 'Worker Service',
    slug: 'worker-service',
    platform: 'node',
    keys: [],
    dsn: 'https://worker@example.com/api/2',
  },
]

const overview = {
  uniqueVisitors: 12,
  totalPageviews: 34,
  bounceRate: 25,
  avgVisitDuration: 42,
  viewsPerVisit: 2.8,
}

function selectProductTab() {
  const productTab = screen.getByRole('tab', {name: 'Product'})
  fireEvent.pointerDown(productTab, {button: 0, ctrlKey: false})
  fireEvent.mouseDown(productTab, {button: 0, ctrlKey: false})
  fireEvent.click(productTab)
}

function lastOverviewParams() {
  const calls = mockApi.getAnalyticsOverview.mock.calls
  expect(calls.length, 'expected getAnalyticsOverview to have been called').toBeGreaterThan(0)
  return calls[calls.length - 1][1]
}

describe('Analytics index service facets', () => {
  beforeEach(() => {
    vi.clearAllMocks()
    clearAuthStorage()
    mockAnalyticsParams.current = {
      period: '7d',
      customFrom: undefined,
      customTo: undefined,
    }
    mockApi.isAuthenticated.mockReturnValue(true)
    mockApi.checkAuth.mockResolvedValue(true)
    mockApi.getCurrentUser.mockResolvedValue(null)
    mockApi.updateUserTimezone.mockResolvedValue(undefined)
    mockApi.getProjects.mockResolvedValue(mockProjects)
    mockApi.getAnalyticsOverview.mockResolvedValue(overview)
    mockApi.getAnalyticsTimeseries.mockResolvedValue([
      {timestamp: '2026-06-01', visitors: 12, pageviews: 34},
    ])
    mockApi.getAnalyticsPages.mockResolvedValue([{name: '/', visitors: 12, pageviews: 34}])
    mockApi.getAnalyticsEntryPages.mockResolvedValue([])
    mockApi.getAnalyticsExitPages.mockResolvedValue([])
    mockApi.getAnalyticsSources.mockResolvedValue([])
    mockApi.getAnalyticsLocations.mockResolvedValue([])
    mockApi.getAnalyticsDevices.mockResolvedValue([])
    mockApi.getAnalyticsUtm.mockResolvedValue([])
    mockApi.getAnalyticsEvents.mockResolvedValue([])
    mockApi.getAnalyticsRetention.mockResolvedValue({
      startEvent: 'signup.completed',
      returnEvent: 'recording.started',
      cohorts: [],
    })
    mockApi.getAnalyticsFunnel.mockResolvedValue({steps: [], overallConversion: 0})
  })

  it('loads organization analytics by default', async () => {
    renderRoute(AnalyticsIndexRoute)

    expect(await screen.findByText('Unique Visitors')).toBeInTheDocument()
    expect(mockApi.getAnalyticsOverview).toHaveBeenCalledWith(null, expect.any(Object))

    const params = mockApi.getAnalyticsOverview.mock.calls[0][1]
    expect(params.period).toBe('7d')
    expect(params.services).toBeUndefined()
    expect(params.comparison).toBe('previous_period')
  })

  it('adds selected service facets to organization analytics calls', async () => {
    renderRoute(AnalyticsIndexRoute)

    await screen.findByText('Unique Visitors')
    fireEvent.click(screen.getByRole('checkbox', {name: 'Worker Service'}))

    await waitFor(() => {
      expect(mockApi.getAnalyticsOverview).toHaveBeenLastCalledWith(
        null,
        expect.objectContaining({services: ['Worker Service']})
      )
    })
  })

  it('passes custom date ranges to organization analytics calls', async () => {
    mockAnalyticsParams.current = {
      period: 'custom',
      customFrom: '2026-05-01',
      customTo: '2026-05-31',
    }

    renderRoute(AnalyticsIndexRoute)

    await screen.findByText('Unique Visitors')
    expect(mockApi.getAnalyticsOverview).toHaveBeenCalledWith(
      null,
      expect.objectContaining({
        period: 'custom',
        from: '2026-05-01',
        to: '2026-05-31',
      })
    )
  })

  it('adds breakdown row filters once', async () => {
    renderRoute(AnalyticsIndexRoute)

    await screen.findByText('Top Pages')
    const pageRow = await screen.findByText('/')
    fireEvent.click(pageRow)

    await waitFor(() => {
      expect(lastOverviewParams()).toEqual(expect.objectContaining({
        filters: [{property: 'pathname', operator: 'is', value: '/'}],
      }))
    })

    const callsAfterFirstFilter = mockApi.getAnalyticsOverview.mock.calls.length
    fireEvent.click(pageRow)

    expect(mockApi.getAnalyticsOverview).toHaveBeenCalledTimes(callsAfterFirstFilter)
  })

  it('renders the web analytics setup state when no web data exists', async () => {
    mockApi.getAnalyticsOverview.mockResolvedValue({
      uniqueVisitors: 0,
      totalPageviews: 0,
      bounceRate: 0,
      avgVisitDuration: 0,
      viewsPerVisit: 0,
    })

    renderRoute(AnalyticsIndexRoute)

    expect(await screen.findByText('Get Started with Analytics')).toBeInTheDocument()
    expect(screen.getByText('View Full Documentation')).toBeInTheDocument()
  })

  it('renders the web setup state with fallback project identifiers', async () => {
    mockApi.getProjects.mockResolvedValue([{...mockProjects[0], dsn: 'not a valid url'}])
    mockApi.getAnalyticsOverview.mockResolvedValue({
      uniqueVisitors: 0,
      totalPageviews: 0,
      bounceRate: 0,
      avgVisitDuration: 0,
      viewsPerVisit: 0,
    })

    renderRoute(AnalyticsIndexRoute)

    expect(await screen.findByText('Get Started with Analytics')).toBeInTheDocument()
    expect(screen.getByText('View Full Documentation')).toBeInTheDocument()
  })

  it('renders product analytics setup when no product events exist', async () => {
    renderRoute(AnalyticsIndexRoute)

    await screen.findByText('Unique Visitors')
    selectProductTab()

    expect(await screen.findByText('Get Started with Product Analytics')).toBeInTheDocument()
    expect(screen.getByText(/Server-side product events require an OTLP API key/)).toBeInTheDocument()
  })

  it('renders product analytics setup with fallback endpoint host', async () => {
    mockApi.getProjects.mockResolvedValue([{...mockProjects[0], dsn: 'not a valid url'}])

    renderRoute(AnalyticsIndexRoute)

    await screen.findByText('Unique Visitors')
    selectProductTab()

    expect(await screen.findByText('Get Started with Product Analytics')).toBeInTheDocument()
    expect(screen.getByText(/Server-side product events require an OTLP API key/)).toBeInTheDocument()
  })

  it('renders product analytics data with organization scope', async () => {
    mockApi.getAnalyticsEvents.mockResolvedValue([{name: 'signup.completed', visitors: 3, pageviews: 3}])

    renderRoute(AnalyticsIndexRoute)

    await screen.findByText('Unique Visitors')
    selectProductTab()

    expect(await screen.findByText('Product Events')).toBeInTheDocument()
    expect(screen.getByText('Activation Funnel')).toBeInTheDocument()
    await waitFor(() => {
      expect(mockApi.getAnalyticsRetention).toHaveBeenCalledWith(
        null,
        expect.objectContaining({
          startEvent: 'signup.completed',
          returnEvent: 'recording.started',
          period: '7d',
        })
      )
      expect(mockApi.getAnalyticsFunnel).toHaveBeenCalledWith(
        null,
        ['signup.completed', 'recording.started', 'export.completed'],
        expect.objectContaining({period: '7d'}),
        {source: 'server', groupBy: 'user_id'}
      )
    })
  })

  it('renders populated product funnel results', async () => {
    mockApi.getAnalyticsEvents.mockResolvedValue([{name: 'signup.completed', visitors: 3, pageviews: 3}])
    mockApi.getAnalyticsFunnel.mockResolvedValue({
      overallConversion: 50,
      steps: [
        {name: 'signup.completed', visitors: 4, dropoff: 0, conversionRate: 100},
        {name: 'recording.started', visitors: 2, dropoff: 2, conversionRate: 50},
      ],
    })

    renderRoute(AnalyticsIndexRoute)

    await screen.findByText('Unique Visitors')
    selectProductTab()

    expect(await screen.findByText('Overall conversion')).toBeInTheDocument()
    expect(screen.getByText('50.0%')).toBeInTheDocument()
    expect(screen.getAllByText('signup.completed').length).toBeGreaterThan(0)
    expect(screen.getByText('4 / 100.0%')).toBeInTheDocument()
  })

  it('updates product funnel steps from the panel controls', async () => {
    mockApi.getAnalyticsEvents.mockResolvedValue([{name: 'signup.completed', visitors: 3, pageviews: 3}])

    renderRoute(AnalyticsIndexRoute)

    await screen.findByText('Unique Visitors')
    selectProductTab()
    expect(await screen.findByText('Activation Funnel')).toBeInTheDocument()

    fireEvent.click(screen.getByRole('button', {name: 'Step'}))
    let stepInputs = screen.getAllByPlaceholderText('event.name')
    expect(stepInputs).toHaveLength(4)

    fireEvent.change(stepInputs[3], {target: {value: 'purchase.completed'}})
    fireEvent.click(screen.getAllByRole('button', {name: 'Remove funnel step'})[3])
    expect(screen.getAllByPlaceholderText('event.name')).toHaveLength(3)

    stepInputs = screen.getAllByPlaceholderText('event.name')
    fireEvent.change(stepInputs[0], {target: {value: ''}})
    fireEvent.change(stepInputs[1], {target: {value: ''}})

    expect(await screen.findByText('Add at least two steps')).toBeInTheDocument()
  })

  it('shows no matching services when service filters exclude every service', async () => {
    renderRoute(AnalyticsIndexRoute)

    await screen.findByText('Unique Visitors')
    const excludeButtons = screen.getAllByRole('button', {name: 'Exclude this value'})
    excludeButtons.forEach((button) => fireEvent.click(button))

    expect(await screen.findByText('No services match filters')).toBeInTheDocument()
  })

  it('renders service loading and error states', async () => {
    mockApi.getProjects.mockRejectedValue(new Error('network down'))

    renderRoute(AnalyticsIndexRoute)

    expect(screen.getByText('Loading analytics...')).toBeInTheDocument()
    expect(await screen.findByText(/Failed to load services: network down/)).toBeInTheDocument()
  })

  it('renders the no-services state without analytics queries', async () => {
    mockApi.getProjects.mockResolvedValue([])

    renderRoute(AnalyticsIndexRoute)

    expect(await screen.findByText('No services yet')).toBeInTheDocument()
    expect(mockApi.getAnalyticsOverview).not.toHaveBeenCalled()
  })
})
