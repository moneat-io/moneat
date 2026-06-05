import React from 'react'
import {beforeEach, describe, expect, it, vi} from 'vitest'
import {fireEvent, screen, waitFor} from '@testing-library/react'
import {clearAuthStorage, renderRoute} from '@/test/utils'

const {
  mockApi,
  mockEnterpriseFeatures,
  mockRouteSearch,
  mockRouteParams,
  mockRoutePathname,
  mockNavigate,
} = vi.hoisted(() => ({
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
    getIncident: vi.fn(),
    getIncidentTimeline: vi.fn(),
    viewIncident: vi.fn(),
    acknowledgeIncident: vi.fn(),
    resolveIncident: vi.fn(),
    markUnavailable: vi.fn(),
    addIncidentNote: vi.fn(),
    declareIncident: vi.fn(),
    getOnCallIncidents: vi.fn(),
    getOnCallIncident: vi.fn(),
    getOnCallIncidentTimeline: vi.fn(),
    resolveOnCallIncident: vi.fn(),
    addOnCallIncidentNote: vi.fn(),
  },
  mockEnterpriseFeatures: {
    current: {enterprise: true, modules: ['oncall'], selfHost: false},
  },
  mockRouteSearch: {
    current: {view: 'overview'},
  },
  mockRouteParams: {
    current: {} as Record<string, string>,
  },
  mockRoutePathname: {
    current: '/',
  },
  mockNavigate: vi.fn(),
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
    useParams: () => mockRouteParams.current,
  }),
  Link: ({children, ...props}: {children: React.ReactNode}) => React.createElement('a', props, children),
  Navigate: ({to}: {to: string}) => <div data-testid="navigate">{to}</div>,
  Outlet: () => <div data-testid="outlet" />,
  useNavigate: () => mockNavigate,
  useRouterState: ({select}: {select: (state: {location: {pathname: string}}) => unknown}) =>
    select({location: {pathname: mockRoutePathname.current}}),
}))

vi.mock('@/components/charts/EventsChart', () => ({
  EventsChart: ({title}: {title: string}) => <div>{title}</div>,
  EventsChartSkeleton: () => <div>events-chart-skeleton</div>,
}))

vi.mock('@/components/uptime/HeartbeatBar', () => ({
  default: () => <div data-testid="heartbeat-bar" />,
}))

import {Route as OverviewRoute} from '../index'
import {Route as AlertDetailRoute} from '../on-call.alerts.$alertId'
import {Route as AlertsRoute} from '../on-call.alerts'
import {Route as IncidentDetailRoute} from '../on-call.incidents.$incidentId'
import {Route as IncidentsRoute} from '../on-call.incidents'
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

const alertTimeline = [
  {
    id: 1,
    incidentId: 101,
    eventType: 'NOTIFICATION_SENT',
    actorUserName: 'Moneat',
    details: {toUserName: 'Ada Lovelace', channel: 'email'},
    createdAt: '2026-06-05T12:01:00.000Z',
  },
  {
    id: 2,
    incidentId: 101,
    eventType: 'NOTE_ADDED',
    actorUserName: 'Ada Lovelace',
    details: {note: 'Investigating payment failures'},
    createdAt: '2026-06-05T12:02:00.000Z',
  },
]

const declaredIncident = {
  id: 501,
  organizationId: 1,
  title: 'Checkout incident',
  description: 'Customer checkout is unavailable',
  severity: 'SEV-1',
  status: 'OPEN',
  declaredBy: 5,
  declaredByName: 'Ada Lovelace',
  declaredAt: '2026-06-05T12:03:00.000Z',
  alertCount: 1,
  alerts: [{id: 101, title: 'Payments outage', status: 'TRIGGERED', priority: 'P0'}],
  createdAt: '2026-06-05T12:03:00.000Z',
  updatedAt: '2026-06-05T12:03:00.000Z',
}

const declaredIncidentTimeline = [
  {
    id: 11,
    incidentId: 501,
    eventType: 'DECLARED',
    source: 'incident',
    actorName: 'Ada Lovelace',
    details: {},
    createdAt: '2026-06-05T12:03:00.000Z',
  },
  {
    id: 12,
    incidentId: 501,
    eventType: 'ALERT_LINKED',
    source: 'alert',
    alertTitle: 'Payments outage',
    details: {alertTitle: 'Payments outage'},
    createdAt: '2026-06-05T12:04:00.000Z',
  },
]

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

function clickLastText(text: string) {
  const matches = screen.getAllByText(text)
  fireEvent.click(matches[matches.length - 1])
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
    mockRouteParams.current = {}
    mockRoutePathname.current = '/'
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
    mockApi.getIncident.mockResolvedValue({...alert, description: 'Payments API is down'})
    mockApi.getIncidentTimeline.mockResolvedValue(alertTimeline)
    mockApi.viewIncident.mockResolvedValue(undefined)
    mockApi.acknowledgeIncident.mockResolvedValue({...alert, status: 'ACKNOWLEDGED'})
    mockApi.resolveIncident.mockResolvedValue({...alert, status: 'RESOLVED'})
    mockApi.markUnavailable.mockResolvedValue(undefined)
    mockApi.addIncidentNote.mockResolvedValue(alertTimeline[1])
    mockApi.declareIncident.mockResolvedValue(declaredIncident)
    mockApi.getOnCallIncidents.mockResolvedValue([declaredIncident])
    mockApi.getOnCallIncident.mockResolvedValue(declaredIncident)
    mockApi.getOnCallIncidentTimeline.mockResolvedValue(declaredIncidentTimeline)
    mockApi.resolveOnCallIncident.mockResolvedValue({...declaredIncident, status: 'RESOLVED'})
    mockApi.addOnCallIncidentNote.mockResolvedValue(declaredIncidentTimeline[0])
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

  it('renders the alert list route with P-priority alerts', async () => {
    mockRoutePathname.current = '/on-call/alerts'
    mockApi.getIncidents.mockResolvedValue([
      makeAlert({
        id: 401,
        title: 'Escalating payment alert',
        description: 'Payment latency is rising',
        priority: 'P2',
        status: 'TRIGGERED',
        alertSource: 'uptime',
        nextEscalationAt: new Date(Date.now() + 60_000).toISOString(),
        viewedByCurrentUser: true,
      }),
      makeAlert({
        id: 402,
        title: 'Resolved analytics alert',
        priority: 'P5',
        status: 'RESOLVED',
        resolvedByName: 'Grace Hopper',
      }),
    ])

    renderRoute(AlertsRoute)

    expect(await screen.findByText('Alerts')).toBeInTheDocument()
    expect(await screen.findByText('Escalating payment alert')).toBeInTheDocument()
    expect(await screen.findByText('P2')).toBeInTheDocument()
    expect(await screen.findByText('Escalating soon')).toBeInTheDocument()
    expect(await screen.findByText('uptime')).toBeInTheDocument()
    expect(await screen.findByText('Resolved analytics alert')).toBeInTheDocument()
    expect(await screen.findByText('Resolved by Grace Hopper')).toBeInTheDocument()

    fireEvent.click(await screen.findByText('1 Triggered'))
    expect(await screen.findByText('Clear filters')).toBeInTheDocument()
    fireEvent.click(await screen.findByText('1 Triggered'))
    fireEvent.click(await screen.findByText('1 Resolved'))
    expect(await screen.findByText('Clear filters')).toBeInTheDocument()
  })

  it('renders alert detail with timeline and incident declaration controls', async () => {
    mockRouteParams.current = {alertId: '101'}

    renderRoute(AlertDetailRoute)

    expect(await screen.findByText('Payments outage')).toBeInTheDocument()
    expect(await screen.findByText('Payments API is down')).toBeInTheDocument()
    expect(await screen.findByText('Priority')).toBeInTheDocument()
    expect((await screen.findAllByText('P0')).length).toBeGreaterThan(0)
    expect(await screen.findByText('Alert history and updates')).toBeInTheDocument()
    expect(await screen.findByText('to Ada Lovelace via email')).toBeInTheDocument()
    expect(await screen.findByText('"Investigating payment failures"')).toBeInTheDocument()
    expect(await screen.findByText('Declare Incident')).toBeInTheDocument()
    expect(mockApi.viewIncident).toHaveBeenCalledWith(101)

    fireEvent.click(await screen.findByText('Acknowledge'))
    await waitFor(() => expect(mockApi.acknowledgeIncident).toHaveBeenCalledWith(101))
    fireEvent.click(await screen.findByText('Resolve'))
    await waitFor(() => expect(mockApi.resolveIncident).toHaveBeenCalledWith(101))
    fireEvent.click(await screen.findByText("I'm not available"))
    await waitFor(() => expect(mockApi.markUnavailable).toHaveBeenCalledWith(101))
    fireEvent.change(screen.getByPlaceholderText('Enter your note...'), {target: {value: 'Adding context'}})
    clickLastText('Add note')
    await waitFor(() => expect(mockApi.addIncidentNote).toHaveBeenCalledWith(101, 'Adding context'))
  })

  it('renders invalid alert identifiers without fetching alert data', async () => {
    mockRouteParams.current = {alertId: 'not-a-number'}

    renderRoute(AlertDetailRoute)

    expect(await screen.findByText('Invalid alert ID')).toBeInTheDocument()
    expect(mockApi.getIncident).not.toHaveBeenCalled()
  })

  it('renders declared incident list route with SEV severity', async () => {
    mockRoutePathname.current = '/on-call/incidents'
    mockApi.getOnCallIncidents.mockResolvedValue([
      declaredIncident,
      {
        ...declaredIncident,
        id: 502,
        title: 'Resolved deploy incident',
        severity: 'SEV-3',
        status: 'RESOLVED',
        resolvedByName: 'Grace Hopper',
        resolvedAt: '2026-06-05T13:00:00.000Z',
      },
    ])

    renderRoute(IncidentsRoute)

    expect(await screen.findByText('Incidents')).toBeInTheDocument()
    expect(await screen.findByText('Checkout incident')).toBeInTheDocument()
    expect(await screen.findByText('SEV-1')).toBeInTheDocument()
    expect(await screen.findByText('Resolved deploy incident')).toBeInTheDocument()
    expect(await screen.findByText('Resolved by Grace Hopper')).toBeInTheDocument()

    fireEvent.click(await screen.findByText('1 Open'))
    fireEvent.click(await screen.findByText('1 Open'))
    fireEvent.click(await screen.findByText('1 Resolved'))
    expect(await screen.findByText('Clear filters')).toBeInTheDocument()
  })

  it('renders declared incident detail with linked alerts and timeline', async () => {
    mockRouteParams.current = {incidentId: '501'}

    renderRoute(IncidentDetailRoute)

    expect(await screen.findByText('Checkout incident')).toBeInTheDocument()
    expect(await screen.findByText('Customer checkout is unavailable')).toBeInTheDocument()
    expect(await screen.findByText('Severity')).toBeInTheDocument()
    expect((await screen.findAllByText('SEV-1')).length).toBeGreaterThan(0)
    expect(await screen.findByText('Linked alerts')).toBeInTheDocument()
    expect(await screen.findByText('Alert #101 · TRIGGERED')).toBeInTheDocument()
    expect(await screen.findByText('from Payments outage')).toBeInTheDocument()
    expect(await screen.findByText('Resolve incident')).toBeInTheDocument()

    fireEvent.change(screen.getByPlaceholderText('Enter your note...'), {target: {value: 'Incident update'}})
    clickLastText('Add note')
    await waitFor(() => expect(mockApi.addOnCallIncidentNote).toHaveBeenCalledWith(501, 'Incident update'))
  })

  it('renders nested route outlets for alert and incident list shells', async () => {
    mockRoutePathname.current = '/on-call/alerts/101'
    renderRoute(AlertsRoute)
    expect(await screen.findByTestId('outlet')).toBeInTheDocument()

    mockRoutePathname.current = '/on-call/incidents/501'
    renderRoute(IncidentsRoute)
    expect((await screen.findAllByTestId('outlet')).length).toBeGreaterThan(1)
  })

  it('renders empty alert and declared incident list states', async () => {
    mockRoutePathname.current = '/on-call/alerts'
    mockApi.getIncidents.mockResolvedValue([])
    renderRoute(AlertsRoute)
    expect(await screen.findByText('No alerts found')).toBeInTheDocument()
    expect(await screen.findByText('No alerts match your current filters. Try adjusting or clearing filters.'))
      .toBeInTheDocument()

    mockRoutePathname.current = '/on-call/incidents'
    mockApi.getOnCallIncidents.mockResolvedValue([])
    renderRoute(IncidentsRoute)
    expect(await screen.findByText('No incidents found')).toBeInTheDocument()
    expect(await screen.findByText('No incidents match your current filters. Try adjusting or clearing filters.'))
      .toBeInTheDocument()
  })

  it('renders alert detail not-found, acknowledged, and resolved states', async () => {
    mockRouteParams.current = {alertId: '404'}
    mockApi.getIncident.mockResolvedValueOnce(null)
    renderRoute(AlertDetailRoute)
    expect(await screen.findByText('Alert not found')).toBeInTheDocument()

    mockRouteParams.current = {alertId: '102'}
    mockApi.getIncident.mockResolvedValueOnce({
      ...alert,
      id: 102,
      status: 'ACKNOWLEDGED',
      priority: 'P2',
      acknowledgedBy: 5,
      acknowledgedByName: 'Ada Lovelace',
      acknowledgedAt: '2026-06-05T12:05:00.000Z',
      description: '',
    })
    mockApi.getIncidentTimeline.mockResolvedValueOnce([
      {
        id: 21,
        incidentId: 102,
        eventType: 'ESCALATED',
        details: {stepNumber: 1},
        createdAt: '2026-06-05T12:06:00.000Z',
      },
      {
        id: 22,
        incidentId: 102,
        eventType: 'REASSIGNED',
        details: {reason: 'unavailable'},
        createdAt: '2026-06-05T12:07:00.000Z',
      },
      {
        id: 23,
        incidentId: 102,
        eventType: 'UNKNOWN_EVENT',
        details: {},
        createdAt: '2026-06-05T12:08:00.000Z',
      },
    ])
    renderRoute(AlertDetailRoute)
    expect(await screen.findByText('Alert acknowledged')).toBeInTheDocument()
    expect(await screen.findByText('Acknowledged By')).toBeInTheDocument()
    expect(await screen.findByText('to step 2')).toBeInTheDocument()
    expect(await screen.findByText('user marked as unavailable')).toBeInTheDocument()
    expect(await screen.findByText('UNKNOWN EVENT')).toBeInTheDocument()

    mockRouteParams.current = {alertId: '103'}
    mockApi.getIncident.mockResolvedValueOnce({
      ...alert,
      id: 103,
      status: 'RESOLVED',
      priority: 'P4',
      resolvedBy: 7,
      resolvedByName: 'Grace Hopper',
      resolvedAt: '2026-06-05T12:09:00.000Z',
    })
    mockApi.getIncidentTimeline.mockResolvedValueOnce([])
    renderRoute(AlertDetailRoute)
    expect(await screen.findByText('Resolved By')).toBeInTheDocument()
    expect(await screen.findByText('Grace Hopper')).toBeInTheDocument()
  })

  it('renders declared incident not-found and resolved detail states', async () => {
    mockRouteParams.current = {incidentId: '404'}
    mockApi.getOnCallIncident.mockResolvedValueOnce(null)
    renderRoute(IncidentDetailRoute)
    expect(await screen.findByText('Incident not found')).toBeInTheDocument()

    mockRouteParams.current = {incidentId: '502'}
    mockApi.getOnCallIncident.mockResolvedValueOnce({
      ...declaredIncident,
      id: 502,
      title: 'Resolved deploy incident',
      description: '',
      severity: 'SEV3',
      status: 'RESOLVED',
      alerts: [],
      resolvedBy: 7,
      resolvedByName: 'Grace Hopper',
      resolvedAt: '2026-06-05T13:00:00.000Z',
    })
    mockApi.getOnCallIncidentTimeline.mockResolvedValueOnce([
      {
        id: 31,
        incidentId: 502,
        eventType: 'NOTIFICATION_SENT',
        actorUserName: 'Moneat',
        actorName: 'Moneat',
        details: {channel: 'sms'},
        createdAt: '2026-06-05T12:10:00.000Z',
      },
      {
        id: 32,
        incidentId: 502,
        eventType: 'REASSIGNED',
        actorName: 'Ada Lovelace',
        details: {toUserName: 'Grace Hopper'},
        createdAt: '2026-06-05T12:11:00.000Z',
      },
      {
        id: 33,
        incidentId: 502,
        eventType: 'NOTE_ADDED',
        actorName: 'Grace Hopper',
        details: {note: 'Deployment rolled back'},
        createdAt: '2026-06-05T12:12:00.000Z',
      },
      {
        id: 34,
        incidentId: 502,
        eventType: 'CUSTOM_EVENT',
        source: 'alert',
        alertTitle: 'Deploy alert',
        details: {},
        createdAt: '2026-06-05T12:13:00.000Z',
      },
    ])
    renderRoute(IncidentDetailRoute)
    expect(await screen.findByText('Resolved deploy incident')).toBeInTheDocument()
    expect((await screen.findAllByText('SEV3')).length).toBeGreaterThan(0)
    expect(await screen.findByText('Resolved By')).toBeInTheDocument()
    expect(await screen.findByText('to Moneat via sms')).toBeInTheDocument()
    expect(await screen.findByText('to Grace Hopper')).toBeInTheDocument()
    expect(await screen.findByText('"Deployment rolled back"')).toBeInTheDocument()
    expect(await screen.findByText('from Deploy alert')).toBeInTheDocument()
  })
})
