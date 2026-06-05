import React from 'react'
import {beforeEach, describe, expect, it, vi} from 'vitest'
import {screen} from '@testing-library/react'
import {clearAuthStorage, renderRoute} from '@/test/utils'

const {mockApi, mockEnterpriseFeatures, mockRouteSearch} = vi.hoisted(() => ({
  mockApi: {
    isAuthenticated: vi.fn(),
    checkAuth: vi.fn(),
    getProjects: vi.fn(),
    getProjectStats: vi.fn(),
    getOrganizationIssues: vi.fn(),
    getPerformanceStats: vi.fn(),
    getOrganizationReleases: vi.fn(),
    getOrganizationReplays: vi.fn(),
    getOrganizationFeedback: vi.fn(),
    getUptimeMonitors: vi.fn(),
    getUptimeHeartbeats: vi.fn(),
    getMonitorHosts: vi.fn(),
    getIncidents: vi.fn(),
    getOnCallSchedules: vi.fn(),
    getEscalationPolicies: vi.fn(),
    getPriorities: vi.fn(),
    getBusinessHours: vi.fn(),
    getStatusPages: vi.fn(),
    getStatusPage: vi.fn(),
  },
  mockEnterpriseFeatures: {
    current: {enterprise: true, modules: ['oncall'], selfHost: false},
  },
  mockRouteSearch: {
    current: {view: 'overview'},
  },
}))

vi.mock('@/lib/api', () => ({
  api: mockApi,
}))

vi.mock('@/hooks/useEnterpriseFeatures', () => ({
  hasEnterpriseModule: (features: {modules?: string[]} | undefined, module: string) => (
    Boolean(features?.modules?.includes(module))
  ),
  useEnterpriseFeatures: () => ({
    data: mockEnterpriseFeatures.current,
    isLoading: false,
  }),
}))

vi.mock('@/hooks/useAuth', () => ({
  useAuth: () => ({user: {id: 5}}),
}))

vi.mock('@tanstack/react-router', () => ({
  createFileRoute: () => (options: Record<string, unknown>) => ({
    ...options,
    options,
    useSearch: () => mockRouteSearch.current,
  }),
  Link: ({children, ...props}: {children: React.ReactNode}) => React.createElement('a', props, children),
  Navigate: ({to}: {to: string}) => <div data-testid="navigate">{to}</div>,
}))

vi.mock('@/components/charts/EventsChart', () => ({
  EventsChart: ({title}: {title: string}) => <div>{title}</div>,
  EventsChartSkeleton: () => <div>events-chart-skeleton</div>,
}))

vi.mock('@/components/uptime/HeartbeatBar', () => ({
  default: () => <div data-testid="heartbeat-bar" />,
}))

import {Route as OverviewRoute} from '../index'
import {Route as OnCallOverviewRoute} from '../on-call.index'

const project = {
  id: 1,
  resourceId: 'svc-api',
  name: 'API Service',
  slug: 'api-service',
  keys: [],
}

const projectStats = {
  totalEvents: 42,
  totalIssues: 2,
  unresolvedIssues: 1,
  affectedUsers: 12,
  eventsTimeline: [{timestamp: '2026-06-05T12:00:00.000Z', count: 42}],
  eventsByLevel: {},
  eventsByPlatform: {},
  eventsByBrowser: {},
  eventsByEnvironment: {},
  issuesByStatus: {},
  topIssues: [],
  usersTimeline: [],
}

const issue = {
  id: 'issue-1',
  projectId: 1,
  projectResourceId: 'svc-api',
  title: 'Checkout failed',
  culprit: 'CheckoutController',
  level: 'error',
  platform: 'javascript',
  firstSeen: '2026-06-05T11:00:00.000Z',
  lastSeen: '2026-06-05T12:00:00.000Z',
  eventCount: 7,
  userCount: 3,
  status: 'unresolved',
}

const alert = {
  id: 101,
  organizationId: 1,
  escalationPolicyId: 1,
  title: 'Payments outage',
  priority: 'P0',
  status: 'TRIGGERED',
  alertSource: 'monitor',
  triggeredAt: '2026-06-05T12:00:00.000Z',
}

const schedule = {
  id: 7,
  organizationId: 1,
  name: 'API Rotation',
  rotationType: 'WEEKLY',
  handoffTime: '09:00',
  timezone: 'America/New_York',
  createdAt: '2026-06-01T00:00:00.000Z',
  updatedAt: '2026-06-01T00:00:00.000Z',
  participants: [],
  overrides: [],
  currentOnCall: {userId: 5, userName: 'Ada Lovelace'},
}

const uptimeMonitor = {
  id: 'uptime-1',
  organizationId: 1,
  name: 'API availability',
  type: 'http',
  active: true,
  intervalSeconds: 60,
  timeoutSeconds: 5,
  retries: 2,
  retryIntervalSeconds: 10,
  status: 'up',
  lastCheckAt: Date.now(),
  consecutiveFailures: 0,
  uptime24h: 99.95,
  avgResponseTime: 123,
  createdAt: Date.now(),
  updatedAt: Date.now(),
}

const host = {
  id: 77,
  name: 'api-host-1',
  hostname: 'api-host-1',
  status: 'online',
  created_at: Date.now(),
  latest_metrics: {
    cpu_percent: 35,
    mem_total: 1024,
    mem_used: 512,
    mem_percent: 50,
    disk_total: 2048,
    disk_used: 1024,
    disk_percent: 50,
    net_recv_bytes: 1,
    net_sent_bytes: 2,
    load_1: 0.5,
  },
  os: 'Linux',
}

const statusPage = {
  id: 'status-page-1',
  organizationId: '1',
  name: 'Public Status',
  slug: 'public-status',
  description: 'Customer-facing status',
  primaryColor: '#16a34a',
  darkMode: false,
  showUptimeHistory: true,
  historyDays: 30,
  isPublic: true,
  createdAt: '2026-06-01T00:00:00.000Z',
  updatedAt: '2026-06-01T00:00:00.000Z',
}

const release = {
  version: '1.2.3',
  firstSeen: '2026-06-01T00:00:00.000Z',
  lastSeen: '2026-06-05T00:00:00.000Z',
  eventCount: 8,
  newIssueCount: 1,
  crashFreeRate: 99.7,
  userCount: 6,
}

const replay = {
  replayId: 'replay-1',
  projectId: 1,
  projectResourceId: 'svc-api',
  startedAt: '2026-06-05T12:00:00.000Z',
  finishedAt: '2026-06-05T12:01:00.000Z',
  durationMs: 60_000,
  urls: ['https://app.example.com/checkout'],
  errorCount: 1,
  user: {email: 'user@example.com'},
  browserName: 'Chrome',
  activity: 80,
}

const feedback = {
  feedbackId: 'feedback-1',
  message: 'Checkout is confusing',
  contactEmail: 'user@example.com',
  name: 'User Example',
  url: 'https://app.example.com/checkout',
  status: 'new',
  timestamp: '2026-06-05T12:00:00.000Z',
  environment: 'production',
  release: '1.2.3',
  platform: 'javascript',
}

function makeAlert(overrides: Record<string, unknown> = {}) {
  return {...alert, ...overrides}
}

function makeIssue(overrides: Record<string, unknown> = {}) {
  return {...issue, ...overrides}
}

function makeUptimeMonitor(overrides: Record<string, unknown> = {}) {
  return {...uptimeMonitor, ...overrides}
}

function makeHost(overrides: Record<string, unknown> = {}) {
  return {...host, ...overrides}
}

function makeStatusPage(overrides: Record<string, unknown> = {}) {
  return {...statusPage, ...overrides}
}

function makeReplay(overrides: Record<string, unknown> = {}) {
  return {...replay, ...overrides}
}

function makeFeedback(overrides: Record<string, unknown> = {}) {
  return {...feedback, ...overrides}
}

describe('overview alert and incident dashboard', () => {
  beforeEach(() => {
    vi.clearAllMocks()
    clearAuthStorage()
    mockEnterpriseFeatures.current = {enterprise: true, modules: ['oncall'], selfHost: false}
    mockRouteSearch.current = {view: 'overview'}
    mockApi.isAuthenticated.mockReturnValue(true)
    mockApi.checkAuth.mockResolvedValue(true)
    mockApi.getProjects.mockResolvedValue([project])
    mockApi.getProjectStats.mockResolvedValue(projectStats)
    mockApi.getOrganizationIssues.mockResolvedValue([issue])
    mockApi.getPerformanceStats.mockResolvedValue({
      apdex: 0.97,
      throughput: [],
      slowestTransactions: [{eventId: 'event-1', name: 'POST /checkout', op: 'http', duration: 321, timestamp: ''}],
      totalTransactions: 12,
      avgDuration: 89,
    })
    mockApi.getOrganizationReleases.mockResolvedValue([release])
    mockApi.getOrganizationReplays.mockResolvedValue([replay])
    mockApi.getOrganizationFeedback.mockResolvedValue([feedback])
    mockApi.getUptimeMonitors.mockResolvedValue([uptimeMonitor])
    mockApi.getUptimeHeartbeats.mockResolvedValue([
      {timestamp: Date.now(), status: 1, responseTimeMs: 123, statusCode: 200, message: 'ok'},
    ])
    mockApi.getMonitorHosts.mockResolvedValue([host])
    mockApi.getIncidents.mockResolvedValue([alert])
    mockApi.getOnCallSchedules.mockResolvedValue([schedule])
    mockApi.getEscalationPolicies.mockResolvedValue([
      {
        id: 1,
        organizationId: 1,
        name: 'Primary escalation',
        repeatCount: 1,
        createdAt: '2026-06-01T00:00:00.000Z',
        updatedAt: '2026-06-01T00:00:00.000Z',
        steps: [],
      },
    ])
    mockApi.getPriorities.mockResolvedValue([])
    mockApi.getBusinessHours.mockResolvedValue({
      id: 1,
      organizationId: 1,
      enabled: true,
      timezone: 'America/New_York',
      windows: [{id: 1, businessHoursId: 1, dayOfWeek: 5, startTime: '00:00', endTime: '23:59'}],
    })
    mockApi.getStatusPages.mockResolvedValue([statusPage])
    mockApi.getStatusPage.mockResolvedValue({
      ...statusPage,
      monitors: [{id: 1, monitorId: 'uptime-1', monitorName: 'API availability', sortOrder: 0}],
      customDomains: [],
    })
  })

  it('renders alert priority and incident overview sections', async () => {
    renderRoute(OverviewRoute)

    expect(await screen.findByText('Overview')).toBeInTheDocument()
    expect(await screen.findByText('On-Call')).toBeInTheDocument()
    expect(screen.getAllByText('Payments outage').length).toBeGreaterThan(0)
    expect(screen.getAllByText('P0').length).toBeGreaterThan(0)
    expect(screen.getByText('Ada Lovelace')).toBeInTheDocument()
    expect(await screen.findByText('Checkout failed')).toBeInTheDocument()
    expect(await screen.findByText('API availability')).toBeInTheDocument()
    expect(await screen.findByText('api-host-1')).toBeInTheDocument()
    expect(await screen.findByText('POST /checkout')).toBeInTheDocument()
    expect(await screen.findByText('Public Status')).toBeInTheDocument()
    expect((await screen.findAllByText('1.2.3')).length).toBeGreaterThan(0)
    expect((await screen.findAllByText('user@example.com')).length).toBeGreaterThan(0)
    expect(await screen.findByText('Checkout is confusing')).toBeInTheDocument()
  })

  it('renders the on-call overview with alert priorities', async () => {
    renderRoute(OnCallOverviewRoute)

    expect(await screen.findByText('Your alert summary')).toBeInTheDocument()
    expect(await screen.findByText("You're on call for 1 schedule")).toBeInTheDocument()
    expect((await screen.findAllByText('Payments outage')).length).toBeGreaterThan(0)
    expect((await screen.findAllByText('P0')).length).toBeGreaterThan(0)
    expect(screen.getByText('Pages 24/7 — P0 · P1 · P2')).toBeInTheDocument()
    expect(screen.getByText('Business hours only — P3+')).toBeInTheDocument()
    expect(screen.getAllByText('API Rotation').length).toBeGreaterThan(0)
    expect(screen.getByText('Escalation policies')).toBeInTheDocument()
  })

  it('renders empty overview states without promoting alerts to incidents', async () => {
    mockApi.getProjectStats.mockResolvedValue({...projectStats, eventsTimeline: [], totalEvents: 0})
    mockApi.getOrganizationIssues.mockResolvedValue([])
    mockApi.getPerformanceStats.mockResolvedValue(null)
    mockApi.getOrganizationReleases.mockResolvedValue([])
    mockApi.getOrganizationReplays.mockResolvedValue([])
    mockApi.getOrganizationFeedback.mockResolvedValue([])
    mockApi.getUptimeMonitors.mockResolvedValue([])
    mockApi.getMonitorHosts.mockResolvedValue([])
    mockApi.getIncidents.mockResolvedValue([])
    mockApi.getOnCallSchedules.mockResolvedValue([])
    mockApi.getStatusPages.mockResolvedValue([])

    renderRoute(OverviewRoute)

    expect(await screen.findByText('Overview')).toBeInTheDocument()
    expect(await screen.findByText('No alerts')).toBeInTheDocument()
    expect(await screen.findByText('No unresolved issues')).toBeInTheDocument()
    expect(await screen.findByText('No uptime monitors configured')).toBeInTheDocument()
    expect(await screen.findByText('No hosts being monitored')).toBeInTheDocument()
    expect(await screen.findByText('No performance data')).toBeInTheDocument()
    expect(await screen.findByText('No status pages configured')).toBeInTheDocument()
    expect(await screen.findByText('No releases')).toBeInTheDocument()
    expect(await screen.findByText('No recent replays')).toBeInTheDocument()
    expect(await screen.findByText('No feedback received')).toBeInTheDocument()
  })

  it('renders degraded overview states and fallback labels', async () => {
    const staleCheckAt = Date.now() - 60 * 60 * 1000
    const downMonitor = makeUptimeMonitor({
      id: 'uptime-down',
      name: 'Database availability',
      status: 'down',
      type: 'smtp',
      lastCheckAt: staleCheckAt,
      uptime24h: null,
      avgResponseTime: 0.5,
    })
    const degradedMonitor = makeUptimeMonitor({
      id: 'uptime-degraded',
      name: 'Cache availability',
      status: 'degraded',
      type: 'tcp',
      active: false,
      lastCheckAt: staleCheckAt,
      uptime24h: 65.432,
      avgResponseTime: 1234,
    })
    const privateStatusPage = makeStatusPage({
      id: 'status-private',
      name: 'Private Ops',
      slug: 'private-ops',
      description: '',
      isPublic: false,
    })
    const pendingStatusPage = makeStatusPage({
      id: 'status-pending',
      name: 'Pending Status',
      slug: 'pending-status',
      description: '',
    })
    const emptyStatusPage = makeStatusPage({
      id: 'status-empty',
      name: 'Empty Status',
      slug: 'empty-status',
      description: '',
    })

    mockApi.getProjectStats.mockResolvedValue({
      ...projectStats,
      totalEvents: 123_456,
      unresolvedIssues: 12_345,
      affectedUsers: 1_234,
    })
    mockApi.getOrganizationIssues.mockResolvedValue([
      makeIssue({id: 'issue-ios', title: 'Fatal checkout', level: 'fatal', platform: 'ios', eventCount: 123_456}),
      makeIssue({id: 'issue-android', title: 'Android crash', level: 'error', platform: 'android', eventCount: 12_345}),
      makeIssue({id: 'issue-python', title: '', culprit: 'Worker failed', level: 'warning', platform: 'python', eventCount: 1_234}),
      makeIssue({id: 'issue-unknown', title: 'Unknown platform', level: 'info', platform: 'rust', lastSeen: null}),
    ])
    mockApi.getPerformanceStats.mockResolvedValue({
      apdex: 0.82,
      throughput: [],
      slowestTransactions: [
        {eventId: 'event-fast', name: 'GET /fast', op: 'http', duration: 0.5, timestamp: ''},
        {eventId: 'event-slow', name: 'POST /slow', op: 'http', duration: 1234, timestamp: ''},
      ],
      totalTransactions: 2,
      avgDuration: 1234,
    })
    mockApi.getIncidents.mockResolvedValue([
      makeAlert({id: 102, title: 'Database acknowledged', priority: 'P1', status: 'ACKNOWLEDGED'}),
      makeAlert({id: 103, title: 'Cache warning', priority: 'P2', status: 'TRIGGERED'}),
      makeAlert({id: 104, title: 'Analytics delayed', priority: 'P3', status: 'TRIGGERED'}),
      makeAlert({id: 105, title: 'Low signal alert', priority: 'P5', status: 'SUPPRESSED'}),
    ])
    mockApi.getOnCallSchedules.mockResolvedValue([{...schedule, id: 8, name: 'Empty Rotation', currentOnCall: null}])
    mockApi.getOrganizationReleases.mockResolvedValue([{...release, version: '2.0.0', crashFreeRate: null}])
    mockApi.getOrganizationReplays.mockResolvedValue([
      makeReplay({replayId: 'replay-user', user: {username: 'anonymous-user'}, errorCount: 0, browserName: null}),
      makeReplay({replayId: 'replay-url', user: null, urls: ['https://app.example.com/cart'], errorCount: 0}),
      makeReplay({replayId: 'replay-session', user: null, urls: [], errorCount: 0, browserName: null}),
    ])
    mockApi.getOrganizationFeedback.mockResolvedValue([
      makeFeedback({feedbackId: 'feedback-no-contact', contactEmail: null, status: 'unresolved'}),
    ])
    mockApi.getUptimeMonitors.mockResolvedValue([downMonitor, degradedMonitor])
    mockApi.getUptimeHeartbeats.mockImplementation((monitorId: string) => {
      if (monitorId === 'uptime-down') return Promise.resolve([])
      return Promise.resolve([
        {timestamp: Date.now() - 2_000, status: 1, responseTimeMs: 111, statusCode: 200, message: 'ok'},
        {timestamp: Date.now() - 3_000, status: 0, responseTimeMs: 1300, statusCode: 503, message: 'older'},
        {timestamp: Date.now(), status: 0, responseTimeMs: 1234, statusCode: 503, message: 'degraded'},
      ])
    })
    mockApi.getMonitorHosts.mockResolvedValue([
      makeHost({id: 88, name: null, hostname: 'offline-host', status: 'offline', os: null, latest_metrics: null}),
      makeHost({
        id: 89,
        name: 'degraded-host',
        hostname: 'degraded-host',
        status: 'degraded',
        latest_metrics: {...host.latest_metrics, cpu_percent: 90, mem_percent: 75, disk_percent: null},
      }),
      makeHost({id: 90, name: 'unknown-host', hostname: 'unknown-host', status: 'mystery'}),
    ])
    mockApi.getStatusPages.mockResolvedValue([privateStatusPage, pendingStatusPage, emptyStatusPage])
    mockApi.getStatusPage.mockImplementation((pageId: string) => {
      if (pageId === 'status-private') {
        return Promise.resolve({
          ...privateStatusPage,
          monitors: [{id: 1, monitorId: 'uptime-down', monitorName: 'Database availability', sortOrder: 0}],
          customDomains: [],
        })
      }
      if (pageId === 'status-pending') {
        return Promise.resolve({
          ...pendingStatusPage,
          monitors: [{id: 2, monitorId: 'missing-monitor', monitorName: 'Missing monitor', sortOrder: 0}],
          customDomains: [],
        })
      }
      return Promise.resolve({...emptyStatusPage, monitors: [], customDomains: []})
    })

    renderRoute(OverviewRoute)

    expect(await screen.findByText('Database acknowledged')).toBeInTheDocument()
    expect(await screen.findByText('ACKNOWLEDGED')).toBeInTheDocument()
    expect(await screen.findByText('P1')).toBeInTheDocument()
    expect(await screen.findByText('P2')).toBeInTheDocument()
    expect(await screen.findByText('P3')).toBeInTheDocument()
    expect(await screen.findByText('P5')).toBeInTheDocument()
    expect(await screen.findByText('Fatal checkout')).toBeInTheDocument()
    expect(await screen.findByText('Android')).toBeInTheDocument()
    expect(await screen.findByText('Backend')).toBeInTheDocument()
    expect(await screen.findByText('Database availability')).toBeInTheDocument()
    expect(await screen.findByText('stale')).toBeInTheDocument()
    expect(await screen.findByText('No heartbeat data')).toBeInTheDocument()
    expect(await screen.findByText('SMTP')).toBeInTheDocument()
    expect(await screen.findByText('offline-host')).toBeInTheDocument()
    expect(await screen.findByText('degraded-host')).toBeInTheDocument()
    expect((await screen.findAllByText('1 down')).length).toBeGreaterThan(0)
    expect(await screen.findByText('1 pending')).toBeInTheDocument()
    expect(await screen.findByText('No monitors')).toBeInTheDocument()
    expect((await screen.findAllByText('No description provided')).length).toBeGreaterThan(0)
    expect(await screen.findByText('Private')).toBeInTheDocument()
    expect(await screen.findByText('anonymous-user')).toBeInTheDocument()
    expect(await screen.findByText('https://app.example.com/cart')).toBeInTheDocument()
    expect(await screen.findByText('Session')).toBeInTheDocument()
  })

  it('renders non-page and empty on-call priority states', async () => {
    mockApi.getOnCallSchedules.mockResolvedValue([{...schedule, id: 9, name: 'Backup Rotation', currentOnCall: null}])
    mockApi.getIncidents.mockResolvedValue([
      makeAlert({id: 201, title: 'Primary P0', priority: 'P0', status: 'TRIGGERED'}),
      makeAlert({id: 202, title: 'Primary P1', priority: 'P1', status: 'ACKNOWLEDGED'}),
      makeAlert({id: 203, title: 'Primary P2', priority: 'P2', status: 'TRIGGERED'}),
      makeAlert({id: 204, title: 'Secondary P2', priority: 'P2', status: 'ACKNOWLEDGED'}),
      makeAlert({id: 205, title: 'Tertiary P1', priority: 'P1', status: 'TRIGGERED'}),
      makeAlert({id: 206, title: 'Overflow P0', priority: 'P0', status: 'TRIGGERED'}),
      makeAlert({id: 207, title: 'Low P3', priority: 'P3', status: 'TRIGGERED'}),
      makeAlert({id: 208, title: 'Low P4', priority: 'P4', status: 'ACKNOWLEDGED'}),
      makeAlert({id: 209, title: 'Low P5', priority: 'P5', status: 'TRIGGERED'}),
      makeAlert({id: 210, title: 'Low Overflow P5', priority: 'P5', status: 'ACKNOWLEDGED'}),
    ])
    mockApi.getBusinessHours.mockResolvedValue({
      id: 2,
      organizationId: 1,
      enabled: true,
      timezone: 'America/New_York',
      windows: [{id: 2, businessHoursId: 2, dayOfWeek: 0, startTime: '00:00', endTime: '00:01'}],
    })

    renderRoute(OnCallOverviewRoute)

    expect(await screen.findByText("You're not currently on call")).toBeInTheDocument()
    expect(await screen.findByText('Outside business hours')).toBeInTheDocument()
    expect(await screen.findByText('No one assigned')).toBeInTheDocument()
    expect((await screen.findAllByText('Primary P1')).length).toBeGreaterThan(0)
    expect((await screen.findAllByText('Acknowledged')).length).toBeGreaterThan(0)
    expect(await screen.findByText('Low P3')).toBeInTheDocument()
    expect(await screen.findByText('P4')).toBeInTheDocument()
    expect((await screen.findAllByText('+1 more')).length).toBeGreaterThan(0)

    mockApi.getOnCallSchedules.mockResolvedValue([])
    mockApi.getIncidents.mockResolvedValue([])
    mockApi.getBusinessHours.mockResolvedValue({
      id: 3,
      organizationId: 1,
      enabled: false,
      timezone: 'America/New_York',
      windows: [],
    })

    renderRoute(OnCallOverviewRoute)

    expect(await screen.findByText('No active high-priority alerts')).toBeInTheDocument()
    expect(await screen.findByText('No low-priority alerts')).toBeInTheDocument()
    expect(await screen.findByText('No schedules configured')).toBeInTheDocument()
  })

  it('checks auth and renders resolved alerts without primary service data', async () => {
    mockApi.isAuthenticated.mockReturnValueOnce(false).mockReturnValueOnce(false).mockReturnValue(true)
    mockApi.getProjects.mockResolvedValue([])
    mockApi.getProjectStats.mockResolvedValue(null)
    mockApi.getOrganizationIssues.mockResolvedValue([])
    mockApi.getPerformanceStats.mockResolvedValue(null)
    mockApi.getOrganizationReleases.mockResolvedValue([])
    mockApi.getOrganizationReplays.mockResolvedValue([])
    mockApi.getOrganizationFeedback.mockResolvedValue([])
    mockApi.getIncidents.mockResolvedValue([
      makeAlert({id: 301, title: 'Resolved low-priority alert', priority: 'P4', status: 'RESOLVED'}),
    ])
    mockApi.getOnCallSchedules.mockResolvedValue([])
    mockApi.getUptimeMonitors.mockResolvedValue([
      makeUptimeMonitor({
        id: 'uptime-push',
        name: 'Push heartbeat',
        type: 'push',
        status: 'paused',
        active: true,
        lastCheckAt: undefined,
        intervalSeconds: undefined,
        uptime24h: undefined,
        avgResponseTime: undefined,
      }),
      makeUptimeMonitor({
        id: 'uptime-default-interval',
        name: 'Default interval heartbeat',
        type: 'http',
        status: 'up',
        active: false,
        lastCheckAt: Date.now() - 10_000,
        intervalSeconds: undefined,
      }),
    ])
    mockApi.getUptimeHeartbeats.mockResolvedValue([])
    mockApi.getMonitorHosts.mockResolvedValue([
      makeHost({
        id: 91,
        name: undefined,
        hostname: 'fallback-host',
        status: undefined,
        latest_metrics: {
          ...host.latest_metrics,
          cpu_percent: undefined,
          mem_percent: undefined,
          disk_percent: undefined,
        },
      }),
    ])
    mockApi.getStatusPages.mockResolvedValue([])

    renderRoute(OverviewRoute)

    expect(await screen.findByText('Overview')).toBeInTheDocument()
    expect(mockApi.checkAuth).toHaveBeenCalled()
    expect(await screen.findByText('Resolved low-priority alert')).toBeInTheDocument()
    expect(await screen.findByText('RESOLVED')).toBeInTheDocument()
    expect(await screen.findByText('P4')).toBeInTheDocument()
    expect(await screen.findByText('Push heartbeat')).toBeInTheDocument()
    expect(await screen.findByText('Push')).toBeInTheDocument()
    expect(await screen.findByText('Default interval heartbeat')).toBeInTheDocument()
    expect(await screen.findByText('fallback-host')).toBeInTheDocument()
  })

  it('renders plural on-call assignments and assignee fallbacks', async () => {
    mockApi.getOnCallSchedules.mockResolvedValue([
      {...schedule, id: 11, name: 'Primary Rotation', currentOnCall: {userId: 5, userName: 'Ada Lovelace'}},
      {...schedule, id: 12, name: 'Secondary Rotation', currentOnCall: {userId: 5, userName: 'Grace Hopper'}},
      {...schedule, id: 13, name: 'Fallback Rotation', currentOnCall: {userName: 'Fallback User'}},
    ])
    mockApi.getIncidents.mockResolvedValue([])
    mockApi.getBusinessHours.mockResolvedValue({
      id: 4,
      organizationId: 1,
      enabled: false,
      timezone: 'America/New_York',
      windows: [],
    })

    renderRoute(OnCallOverviewRoute)

    expect(await screen.findByText("You're on call for 2 schedules")).toBeInTheDocument()
    expect(await screen.findByText('Grace Hopper')).toBeInTheDocument()
    expect(await screen.findByText('Fallback User')).toBeInTheDocument()
  })

  it('routes self-host non-overview traffic without rendering the public landing', async () => {
    mockRouteSearch.current = {view: 'marketing'}
    mockEnterpriseFeatures.current = {enterprise: true, modules: ['oncall'], selfHost: true}

    mockApi.isAuthenticated.mockReturnValue(false)
    renderRoute(OverviewRoute)
    expect(await screen.findByTestId('navigate')).toHaveTextContent('/login')

    mockApi.isAuthenticated.mockReturnValue(true)
    renderRoute(OverviewRoute)
    expect(screen.getAllByTestId('navigate').some((element) => element.textContent === '/')).toBe(true)
  })

  it('omits the on-call overview when the module is unavailable', async () => {
    mockEnterpriseFeatures.current = {enterprise: true, modules: [], selfHost: false}

    renderRoute(OverviewRoute)

    expect(await screen.findByText('Overview')).toBeInTheDocument()
    expect(await screen.findByText('Checkout failed')).toBeInTheDocument()
    expect(screen.queryByText('On-Call')).not.toBeInTheDocument()
  })
})
