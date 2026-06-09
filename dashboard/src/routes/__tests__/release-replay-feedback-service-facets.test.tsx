import React from 'react'
import {beforeEach, describe, expect, it, vi} from 'vitest'
import {fireEvent, screen, waitFor} from '@testing-library/react'
import {clearAuthStorage, renderRoute} from '@/test/utils'

const {mockApi, mockRouteParams, mockNavigate} = vi.hoisted(() => {
  type MockRouteParams = {
    feedbackId?: string
    version?: string
  }

  return {
    mockApi: {
      isAuthenticated: vi.fn(),
      checkAuth: vi.fn(),
      getCurrentUser: vi.fn(),
      updateUserTimezone: vi.fn(),
      getProjects: vi.fn(),
      getOrganizationReleases: vi.fn(),
      getOrganizationReleaseStats: vi.fn(),
      getOrganizationReplays: vi.fn(),
      getBillingUsage: vi.fn(),
      getOrganizationFeedback: vi.fn(),
      getFeedbackDetail: vi.fn(),
      getIssueLinkForEvent: vi.fn(),
      updateFeedback: vi.fn(),
    },
    mockRouteParams: {
      current: {version: '1.0.0'} as MockRouteParams,
    },
    mockNavigate: vi.fn(),
  }
})

vi.mock('@/lib/api', () => ({
  api: mockApi,
  formatErrorForLogging: (error: unknown) => String(error),
}))

vi.mock('@/hooks/useTimezone', () => ({
  useTimezone: () => ({timezone: 'UTC'}),
}))

vi.mock('@/components/charts/StatsCard', () => ({
  StatsCard: ({title, value}: {title: string; value: string | number}) => <div>{title}:{value}</div>,
}))

vi.mock('@/components/charts/EventsChart', () => ({
  EventsChart: ({title}: {title: string}) => <div>{title}</div>,
}))

vi.mock('@/components/charts/BarChart', () => ({
  BarChart: ({title}: {title: string}) => <div>{title}</div>,
}))

vi.mock('@tanstack/react-router', () => ({
  createFileRoute: () => (options: Record<string, unknown>) => ({
    ...options,
    options,
    useParams: () => mockRouteParams.current,
    useSearch: () => ({}),
  }),
  Link: ({children, ...props}: {children: React.ReactNode}) => React.createElement('a', props, children),
  Outlet: () => null,
  redirect: (opts: Record<string, unknown>) => ({...opts, __redirect: true}),
  useMatches: () => [],
  useNavigate: () => mockNavigate,
}))

import {Route as FeedbackRoute} from '../feedback'
import {Route as FeedbackDetailRoute} from '../feedback.$feedbackId'
import {Route as ReleaseDetailRoute} from '../releases.$version'
import {Route as ReleasesRoute} from '../releases'
import {Route as ReplaysRoute} from '../replays'

const mockProjects = [
  {
    id: 1,
    resourceId: 'svc-api',
    name: 'API Service',
    slug: 'api-service',
    keys: [],
    dsn: 'https://public@example.com/api/1',
  },
  {
    id: 2,
    resourceId: 'svc-worker',
    name: 'Worker Service',
    slug: 'worker-service',
    keys: [],
    dsn: 'https://worker@example.com/api/2',
  },
]

const release = {
  version: '1.0.0',
  firstSeen: '2026-06-01T00:00:00.000Z',
  lastSeen: '2026-06-02T00:00:00.000Z',
  eventCount: 42,
  newIssueCount: 1,
  crashFreeRate: 99.5,
  userCount: 12,
}

const releaseWithoutCrashRate = {
  ...release,
  version: '1.1.0',
  firstSeen: '',
  lastSeen: 'not-a-date',
  eventCount: 100,
  newIssueCount: 3,
  crashFreeRate: null,
  userCount: 20,
}

const healthyRelease = {
  ...release,
  version: '1.2.0',
  eventCount: 7,
  newIssueCount: 0,
  crashFreeRate: 99.9,
  userCount: 3,
}

const releaseStats = {
  version: '1.0.0',
  firstSeen: '2026-06-01T00:00:00.000Z',
  lastSeen: '2026-06-02T00:00:00.000Z',
  totalEvents: 42,
  newIssues: 1,
  resolvedIssues: 0,
  crashFreeSessionRate: 99.5,
  crashFreeUserRate: 99.1,
  userCount: 12,
  eventsTimeline: [{timestamp: '2026-06-01T00:00:00.000Z', count: 42}],
  eventsByLevel: {error: 42},
  topIssues: [{issueId: 'issue-1', title: 'Worker failed', count: 4}],
}

const replay = {
  replayId: 'replay-1',
  projectId: 1,
  projectResourceId: 'svc-api',
  startedAt: '2026-06-01T00:00:00.000Z',
  finishedAt: '2026-06-01T00:01:00.000Z',
  durationMs: 60000,
  urls: ['https://app.example.com/dashboard'],
  errorCount: 1,
  user: {email: 'user@example.com'},
  browserName: 'Chrome',
  osName: 'macOS',
  activity: 80,
}

const replayVariants = [
  replay,
  {
    ...replay,
    replayId: 'replay-2',
    durationMs: 500,
    urls: [],
    errorCount: 0,
    user: {username: 'Worker User'},
    browserName: undefined,
    osName: undefined,
    activity: 0,
  },
  {
    ...replay,
    replayId: 'replay-3',
    durationMs: 12000,
    urls: ['https://app.example.com/a', 'https://app.example.com/b'],
    errorCount: 2,
    user: {id: 'anon-1'},
    activity: 20,
  },
  {
    ...replay,
    replayId: 'replay-4',
    durationMs: 300000,
    errorCount: 0,
    user: undefined,
    activity: 50,
  },
]

const feedback = {
  feedbackId: 'feedback-1',
  message: 'Checkout is confusing',
  contactEmail: 'user@example.com',
  name: 'User Example',
  url: 'https://app.example.com/checkout',
  status: 'unresolved',
  timestamp: '2026-06-01T00:00:00.000Z',
  environment: 'production',
  release: '1.0.0',
  platform: 'javascript',
  associatedEventId: null,
  replayId: 'replay-1',
  sourceType: 'otlp',
  sourceName: 'OpenTelemetry',
  sourceEventName: 'moneat.user_feedback',
  traceId: '00000000000000000000000000000001',
  spanId: '0000000000000001',
  resourceAttributes: {'service.name': 'api'},
}

const resolvedFeedback = {
  ...feedback,
  feedbackId: 'feedback-2',
  message: '',
  contactEmail: '',
  name: '',
  status: 'resolved',
  environment: '',
  platform: '',
  url: '',
  associatedEventId: 'event-1',
  replayId: null,
}

describe('release, replay, and feedback service facets', () => {
  beforeEach(() => {
    vi.clearAllMocks()
    clearAuthStorage()
    mockRouteParams.current = {version: '1.0.0'}
    mockApi.isAuthenticated.mockReturnValue(true)
    mockApi.checkAuth.mockResolvedValue(true)
    mockApi.getCurrentUser.mockResolvedValue(null)
    mockApi.updateUserTimezone.mockResolvedValue(undefined)
    mockApi.getProjects.mockResolvedValue(mockProjects)
    mockApi.getOrganizationReleases.mockResolvedValue([release])
    mockApi.getOrganizationReleaseStats.mockResolvedValue(releaseStats)
    mockApi.getOrganizationReplays.mockResolvedValue([replay])
    mockApi.getBillingUsage.mockResolvedValue({retentionDays: 30})
    mockApi.getOrganizationFeedback.mockResolvedValue([feedback])
    mockApi.getFeedbackDetail.mockResolvedValue({...feedback, sdkName: 'otel-js', sdkVersion: '1.0.0', tags: {}})
    mockApi.getIssueLinkForEvent.mockResolvedValue(null)
    mockApi.updateFeedback.mockResolvedValue(feedback)
  })

  it('loads releases organization-wide by default', async () => {
    mockApi.getOrganizationReleases.mockResolvedValue([
      release,
      releaseWithoutCrashRate,
      healthyRelease,
    ])

    renderRoute(ReleasesRoute)

    expect(await screen.findByText('1.0.0')).toBeInTheDocument()
    expect(await screen.findByText('1.1.0')).toBeInTheDocument()
    expect(await screen.findByText('1.2.0')).toBeInTheDocument()
    expect(mockApi.getOrganizationReleases).toHaveBeenCalledWith({})
  })

  it('adds selected services to release calls', async () => {
    renderRoute(ReleasesRoute)

    await screen.findByText('1.0.0')
    fireEvent.click(screen.getByRole('checkbox', {name: 'Worker Service'}))

    await waitFor(() => {
      expect(mockApi.getOrganizationReleases).toHaveBeenLastCalledWith({
        services: ['Worker Service'],
      })
    })
  })

  it('loads release detail stats without project scoping', async () => {
    renderRoute(ReleaseDetailRoute)

    expect(await screen.findByText('Top Issues in this Release')).toBeInTheDocument()
    expect(mockApi.getOrganizationReleaseStats).toHaveBeenCalledWith('1.0.0')
  })

  it('renders release empty, error, and not-found states', async () => {
    mockApi.getOrganizationReleases.mockResolvedValueOnce([])

    const emptyRender = renderRoute(ReleasesRoute)

    expect(await screen.findByText('No releases detected')).toBeInTheDocument()
    emptyRender.unmount()

    mockApi.getProjects.mockRejectedValueOnce(new Error('services down'))

    const errorRender = renderRoute(ReleasesRoute)

    expect(await screen.findByText('Failed to load services: services down')).toBeInTheDocument()
    errorRender.unmount()

    mockApi.getProjects.mockResolvedValue(mockProjects)
    mockApi.getOrganizationReleaseStats.mockResolvedValueOnce(null)

    renderRoute(ReleaseDetailRoute)

    expect(await screen.findByText('Release not found')).toBeInTheDocument()
  })

  it('shows no matching release services when filters cancel out', async () => {
    renderRoute(ReleasesRoute)

    await screen.findByText('1.0.0')
    fireEvent.click(screen.getByRole('checkbox', {name: 'API Service'}))
    fireEvent.click(screen.getAllByTitle('Exclude this value')[0])
    fireEvent.click(screen.getAllByTitle('Exclude this value')[0])

    expect(await screen.findByText('No services match filters')).toBeInTheDocument()
  })

  it('loads replays with service facet filters', async () => {
    mockApi.getOrganizationReplays.mockResolvedValue(replayVariants)

    renderRoute(ReplaysRoute)

    expect(await screen.findByText('user@example.com')).toBeInTheDocument()
    expect(await screen.findByText('Worker User')).toBeInTheDocument()
    expect(await screen.findByText('Anonymous')).toBeInTheDocument()
    expect(mockApi.getOrganizationReplays).toHaveBeenCalledWith({
      page: 1,
      limit: 25,
      period: '7d',
      environment: undefined,
    })

    fireEvent.click(screen.getByRole('checkbox', {name: 'Worker Service'}))

    await waitFor(() => {
      expect(mockApi.getOrganizationReplays).toHaveBeenLastCalledWith({
        page: 1,
        limit: 25,
        period: '7d',
        environment: undefined,
        services: ['Worker Service'],
      })
    })
  })

  it('opens replay rows from keyboard activation', async () => {
    mockApi.getOrganizationReplays.mockResolvedValue(replayVariants)

    renderRoute(ReplaysRoute)

    const replayRow = await screen.findByRole('button', {name: /Open replay replay-1 for user@example.com/})
    fireEvent.keyDown(replayRow, {key: 'Enter'})

    expect(mockNavigate).toHaveBeenCalledWith({
      to: '/replays/$replayId',
      params: {replayId: 'replay-1'},
    })

    mockNavigate.mockClear()
    fireEvent.keyDown(replayRow, {key: ' '})

    expect(mockNavigate).toHaveBeenCalledWith({
      to: '/replays/$replayId',
      params: {replayId: 'replay-1'},
    })
  })

  it('renders replay empty and search-empty states', async () => {
    mockApi.getOrganizationReplays.mockResolvedValueOnce([])

    const emptyRender = renderRoute(ReplaysRoute)

    expect(await screen.findByText('No replays yet')).toBeInTheDocument()
    emptyRender.unmount()

    mockApi.getOrganizationReplays.mockResolvedValue(replayVariants)

    renderRoute(ReplaysRoute)

    await screen.findByText('user@example.com')
    // The shared search bar commits free text on Enter (not on change).
    const searchInput = screen.getByRole('textbox')
    fireEvent.change(searchInput, {target: {value: 'does-not-exist'}})
    fireEvent.keyDown(searchInput, {key: 'Enter'})

    expect(await screen.findByText('No replays match your search')).toBeInTheDocument()
  })

  it('handles replay service and retention edge states', async () => {
    mockApi.getProjects.mockResolvedValueOnce([])

    const noServicesRender = renderRoute(ReplaysRoute)

    expect(await screen.findByText('No services yet')).toBeInTheDocument()
    expect(mockApi.getOrganizationReplays).not.toHaveBeenCalled()
    noServicesRender.unmount()

    mockApi.getProjects.mockRejectedValueOnce(new Error('service load failed'))

    const errorRender = renderRoute(ReplaysRoute)

    expect(await screen.findByText('Failed to load services: service load failed')).toBeInTheDocument()
    errorRender.unmount()

    mockApi.getProjects.mockResolvedValue(mockProjects)
    mockApi.getBillingUsage.mockResolvedValueOnce({retentionDays: 1})

    renderRoute(ReplaysRoute)

    await screen.findByText('user@example.com')
    expect(mockApi.getOrganizationReplays).toHaveBeenLastCalledWith({
      page: 1,
      limit: 25,
      period: '24h',
      environment: undefined,
    })
  })

  it('loads feedback with service facet filters', async () => {
    mockApi.getOrganizationFeedback.mockResolvedValue([feedback, resolvedFeedback])

    renderRoute(FeedbackRoute)

    expect(await screen.findByText('Checkout is confusing')).toBeInTheDocument()
    expect((await screen.findAllByText('OpenTelemetry')).length).toBeGreaterThan(0)
    expect(mockApi.getOrganizationFeedback).toHaveBeenCalledWith({page: 1, limit: 500})
    expect(mockApi.getOrganizationFeedback).toHaveBeenCalledWith({
      page: 1,
      limit: 100,
      status: 'unresolved',
    })

    fireEvent.click(screen.getByRole('checkbox', {name: 'Worker Service'}))

    await waitFor(() => {
      expect(mockApi.getOrganizationFeedback).toHaveBeenLastCalledWith({
        page: 1,
        limit: 100,
        status: 'unresolved',
        services: ['Worker Service'],
      })
    })
  })

  it('filters and resolves selected feedback', async () => {
    mockApi.getOrganizationFeedback.mockResolvedValue([feedback, resolvedFeedback])

    renderRoute(FeedbackRoute)

    expect(await screen.findByText('Checkout is confusing')).toBeInTheDocument()
    fireEvent.click(screen.getByRole('button', {name: /Resolved/}))

    await waitFor(() => {
      expect(mockApi.getOrganizationFeedback).toHaveBeenLastCalledWith({
        page: 1,
        limit: 100,
        status: 'resolved',
      })
    })

    fireEvent.click(await screen.findByRole('checkbox', {name: 'Select all feedback'}))
    fireEvent.click(screen.getByRole('button', {name: 'Resolve'}))

    await waitFor(() => {
      expect(mockApi.updateFeedback).toHaveBeenCalledWith('feedback-1', {status: 'resolved'})
    })
  })

  it('renders feedback empty and search-empty states', async () => {
    mockApi.getOrganizationFeedback.mockResolvedValueOnce([])
    mockApi.getOrganizationFeedback.mockResolvedValueOnce([])

    const emptyRender = renderRoute(FeedbackRoute)

    expect(await screen.findByText('No feedback yet')).toBeInTheDocument()
    emptyRender.unmount()

    mockApi.getOrganizationFeedback.mockResolvedValue([feedback])

    renderRoute(FeedbackRoute)

    await screen.findByText('Checkout is confusing')
    fireEvent.change(screen.getByPlaceholderText('Search by message, name, or email...'), {
      target: {value: 'not-present'},
    })

    expect(await screen.findByText('No feedback match your search')).toBeInTheDocument()
  })

  it('handles feedback no-services and no matching service filters', async () => {
    mockApi.getProjects.mockResolvedValueOnce([])

    const noServicesRender = renderRoute(FeedbackRoute)

    expect(await screen.findByText('No services yet')).toBeInTheDocument()
    expect(mockApi.getOrganizationFeedback).not.toHaveBeenCalled()
    noServicesRender.unmount()

    mockApi.getProjects.mockResolvedValue(mockProjects)

    renderRoute(FeedbackRoute)

    await screen.findByText('Checkout is confusing')
    fireEvent.click(screen.getByRole('checkbox', {name: 'API Service'}))
    fireEvent.click(screen.getAllByTitle('Exclude this value')[0])
    fireEvent.click(screen.getAllByTitle('Exclude this value')[0])

    expect(await screen.findByText('No services match filters')).toBeInTheDocument()
  })

  it('renders feedback detail source metadata', async () => {
    mockRouteParams.current = {feedbackId: 'feedback-1'}

    renderRoute(FeedbackDetailRoute)

    expect(await screen.findByText('Telemetry Source')).toBeInTheDocument()
    expect(screen.getByText('OpenTelemetry')).toBeInTheDocument()
    expect(screen.getByText('moneat.user_feedback')).toBeInTheDocument()
    expect(screen.getByText('00000000000000000000000000000001')).toBeInTheDocument()
    expect(screen.getByText('0000000000000001')).toBeInTheDocument()
  })

  it('does not query scoped release data when there are no services', async () => {
    mockApi.getProjects.mockResolvedValue([])

    renderRoute(ReleasesRoute)

    expect(await screen.findByText('No services yet')).toBeInTheDocument()
    expect(mockApi.getOrganizationReleases).not.toHaveBeenCalled()
  })
})
