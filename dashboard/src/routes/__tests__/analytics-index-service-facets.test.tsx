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
    getProductAnalyticsSummary: vi.fn(),
    getProductActivity: vi.fn(),
    getProductMovers: vi.fn(),
    getProductFeatureAdoption: vi.fn(),
    getProductSegmentation: vi.fn(),
    getProductRetention: vi.fn(),
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
    id: 'svc-web',
    name: 'Web Service',
    slug: 'web-service',
    platform: 'javascript',
    keys: [],
    dsn: 'https://public@example.com/api/1',
  },
  {
    id: 'svc-worker',
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
  const productTab = screen.getByRole('button', {name: 'Product'})
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
    mockApi.getProductAnalyticsSummary.mockResolvedValue({
      weeklyActiveUsers: {value: 24600, previous: 23000, spark: [10, 12, 14]},
      dailyActiveUsers: 9400,
      newUsers: {value: 9200, previous: 8200},
      activationRate: {value: 41, previous: 38.6},
      stickiness: {value: 28, previous: 27},
      week1Retention: {value: 44, previous: 42.5},
      powerUsers: {value: 12, previous: 11.4},
    })
    mockApi.getProductActivity.mockResolvedValue({series: [], annotations: []})
    mockApi.getProductMovers.mockResolvedValue([])
    mockApi.getProductFeatureAdoption.mockResolvedValue([])
    mockApi.getProductSegmentation.mockResolvedValue({plan: [], platform: [], country: []})
    mockApi.getProductRetention.mockResolvedValue({
      mode: 'key_action',
      periods: [0, 1, 2, 3, 4, 5, 6],
      cohorts: [],
    })
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

  it('renders the web setup state with fallback service identifiers', async () => {
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
    mockApi.getAnalyticsEvents.mockResolvedValue([{name: 'signup_completed', visitors: 3, pageviews: 3}])

    renderRoute(AnalyticsIndexRoute)

    await screen.findByText('Unique Visitors')
    selectProductTab()

    expect(await screen.findByText('Activation funnel')).toBeInTheDocument()
    expect(screen.getByText('Key event trends')).toBeInTheDocument()
    expect(screen.getByText('Weekly active users')).toBeInTheDocument()
    await waitFor(() => {
      expect(mockApi.getProductAnalyticsSummary).toHaveBeenCalledWith(
        null,
        expect.objectContaining({period: '7d'})
      )
      expect(mockApi.getAnalyticsFunnel).toHaveBeenCalledWith(
        null,
        ['signup_completed', 'onboarding_completed', 'first_key_action', 'activated'],
        expect.objectContaining({period: '7d'}),
        {source: 'server', groupBy: 'user_id'}
      )
    })
  })

  it('renders populated product funnel results', async () => {
    mockApi.getAnalyticsEvents.mockResolvedValue([{name: 'signup_completed', visitors: 3, pageviews: 3}])
    mockApi.getAnalyticsFunnel.mockResolvedValue({
      overallConversion: 50,
      steps: [
        {name: 'signup_completed', visitors: 4, dropoff: 0, conversionRate: 100},
        {name: 'activated', visitors: 2, dropoff: 2, conversionRate: 50},
      ],
    })

    renderRoute(AnalyticsIndexRoute)

    await screen.findByText('Unique Visitors')
    selectProductTab()

    expect(await screen.findByText('Activation funnel')).toBeInTheDocument()
    // Overall conversion is shown to one decimal in the funnel header once it loads.
    expect(await screen.findByText('50.0%')).toBeInTheDocument()
    expect(screen.getAllByText('signup_completed').length).toBeGreaterThan(0)
  })

  it('renders populated product analytics panels and custom retention', async () => {
    mockApi.getAnalyticsEvents.mockResolvedValue([
      {name: 'signup_completed', visitors: 18, pageviews: 24},
      {name: 'playlist.saved', visitors: 9, pageviews: 16},
    ])
    mockApi.getAnalyticsFunnel.mockResolvedValue({
      overallConversion: 120,
      steps: [
        {name: 'signup_completed', visitors: 10, dropoff: 0, conversionRate: 100},
        {name: 'activated', visitors: 12, dropoff: -2, conversionRate: 120},
      ],
    })
    mockApi.getProductActivity.mockResolvedValue({
      series: [
        {
          metric: 'active',
          points: [
            {timestamp: '2026-06-01', value: 8, previous: 6},
            {timestamp: '2026-06-02', value: 14, previous: 11},
          ],
        },
        {
          metric: 'new',
          points: [
            {timestamp: '2026-06-01', value: 3, previous: 2},
            {timestamp: '2026-06-02', value: 5, previous: 4},
          ],
        },
        {
          metric: 'key_action',
          points: [
            {timestamp: '2026-06-01', value: 2, previous: 1},
            {timestamp: '2026-06-02', value: 7, previous: 4},
          ],
        },
      ],
      annotations: [{date: '2026-06-02', label: 'v2 launch', kind: 'release'}],
    })
    mockApi.getProductMovers.mockResolvedValue([
      {name: 'Onboarding completed', category: 'activation', detail: 'new flow', change: '+7.0pp', tone: 'good'},
      {name: 'Invite sent', category: 'collab', change: '-3.0pp', tone: 'bad'},
    ])
    mockApi.getProductFeatureAdoption.mockResolvedValue([
      {name: 'Saved views', adoptionRate: 140},
      {name: 'Invite collaborators', adoptionRate: 26},
    ])
    mockApi.getProductSegmentation.mockResolvedValue({
      plan: [{name: 'Free', users: 42, activationRate: 38, week1Retention: 24, stickiness: 18}],
      platform: [{name: 'Web', users: 31, activationRate: 45, week1Retention: 28, stickiness: 22}],
      country: [{name: 'US', users: 19, activationRate: 52, week1Retention: 34, stickiness: 26}],
    })
    mockApi.getProductRetention.mockResolvedValue({
      mode: 'key_action',
      periods: [0, 1, 2],
      cohorts: [{cohort: 'May 4', users: 20, values: [100, 48, null]}],
    })

    renderRoute(AnalyticsIndexRoute)

    await screen.findByText('Unique Visitors')
    selectProductTab()

    expect(await screen.findByText('Activation funnel')).toBeInTheDocument()
    expect(await screen.findByText('120.0%')).toBeInTheDocument()
    expect(screen.getByText('+20%')).toBeInTheDocument()
    expect(screen.getByText('v2 launch')).toBeInTheDocument()
    expect(screen.getByText('Onboarding completed')).toBeInTheDocument()
    expect(screen.getByText('Saved views')).toBeInTheDocument()
    expect(screen.getByText('May 4')).toBeInTheDocument()

    fireEvent.click(screen.getByRole('button', {name: 'Platform'}))
    expect(screen.getAllByText('Web').length).toBeGreaterThan(1)

    fireEvent.click(screen.getByRole('button', {name: 'Custom event'}))
    fireEvent.change(screen.getByLabelText('Custom retention event'), {target: {value: 'playlist.saved'}})
    fireEvent.blur(screen.getByLabelText('Custom retention event'))

    await waitFor(() => {
      expect(mockApi.getProductRetention).toHaveBeenLastCalledWith(
        null,
        expect.objectContaining({mode: 'custom', customEvent: 'playlist.saved'})
      )
    })
  })

  it('updates product funnel steps from the panel controls', async () => {
    mockApi.getAnalyticsEvents.mockResolvedValue([{name: 'signup_completed', visitors: 3, pageviews: 3}])

    renderRoute(AnalyticsIndexRoute)

    await screen.findByText('Unique Visitors')
    selectProductTab()
    expect(await screen.findByText('Activation funnel')).toBeInTheDocument()

    // The funnel ships with four general default steps.
    let stepInputs = screen.getAllByPlaceholderText('event.name')
    expect(stepInputs).toHaveLength(4)

    fireEvent.click(screen.getByRole('button', {name: 'Step'}))
    stepInputs = screen.getAllByPlaceholderText('event.name')
    expect(stepInputs).toHaveLength(5)

    fireEvent.click(screen.getAllByRole('button', {name: 'Remove step'})[4])
    expect(screen.getAllByPlaceholderText('event.name')).toHaveLength(4)

    stepInputs = screen.getAllByPlaceholderText('event.name')
    stepInputs.forEach((input) => fireEvent.change(input, {target: {value: ''}}))

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
