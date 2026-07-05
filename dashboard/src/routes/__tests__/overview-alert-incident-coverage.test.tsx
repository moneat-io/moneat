import React from 'react'
import {beforeAll, beforeEach, describe, expect, it, vi} from 'vitest'
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
    getAlerts: vi.fn(),
    getOnCallSchedules: vi.fn(),
    getEscalationPolicies: vi.fn(),
    getPriorities: vi.fn(),
    getBusinessHours: vi.fn(),
    getStatusPages: vi.fn(),
    getStatusPage: vi.fn(),
    getAlert: vi.fn(),
    getAlertTimeline: vi.fn(),
    viewAlert: vi.fn(),
    acknowledgeAlert: vi.fn(),
    resolveAlert: vi.fn(),
    markAlertUnavailable: vi.fn(),
    addAlertNote: vi.fn(),
    declareIncidentFromAlert: vi.fn(),
    getOnCallIncidents: vi.fn(),
    getOnCallIncident: vi.fn(),
    getOnCallIncidentTimeline: vi.fn(),
    resolveOnCallIncident: vi.fn(),
    addOnCallIncidentNote: vi.fn(),
    getOverview: vi.fn(),
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
  useAuth: () => ({user: {id: '123e4567-e89b-12d3-a456-000000000005'}}),
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

vi.mock('react-grid-layout/legacy', () => ({
  Responsive: ({children, className}: {children: React.ReactNode; className?: string}) => (
    <div data-testid="responsive-grid-layout" className={className}>
      {children}
    </div>
  ),
}))

beforeAll(() => {
  globalThis.ResizeObserver = class {
    disconnect() {}
    observe() {}
    unobserve() {}
  }
})

import {Route as OverviewRoute} from '../index'
import {Route as AlertDetailRoute} from '../on-call.alerts.$alertId'
import {Route as AlertsRoute} from '../on-call.alerts'
import {Route as IncidentDetailRoute} from '../on-call.incidents.$incidentId'
import {Route as IncidentsRoute} from '../on-call.incidents'
import {Route as OnCallOverviewRoute} from '../on-call.index'
import {overviewTestData} from '../../components/overview/__tests__/overviewTestData'
import type {OverviewResponse} from '@/lib/api/types'

function resourceId(id: number): string {
  return `123e4567-e89b-12d3-a456-${String(id).padStart(12, '0')}`
}

const USER_RESOURCE_ID = resourceId(5)
const OTHER_USER_RESOURCE_ID = resourceId(7)
const ORGANIZATION_RESOURCE_ID = resourceId(1)
const ESCALATION_POLICY_RESOURCE_ID = resourceId(301)
const ALERT_RESOURCE_ID = resourceId(101)
const ACKNOWLEDGED_ALERT_RESOURCE_ID = resourceId(102)
const RESOLVED_ALERT_RESOURCE_ID = resourceId(103)
const DECLARED_INCIDENT_RESOURCE_ID = resourceId(501)
const RESOLVED_DECLARED_INCIDENT_RESOURCE_ID = resourceId(502)
const UNKNOWN_RESOURCE_ID = resourceId(404)
const SCHEDULE_RESOURCE_ID = resourceId(701)
const UPTIME_MONITOR_RESOURCE_ID = resourceId(801)
const HOST_RESOURCE_ID = resourceId(901)
const STATUS_PAGE_RESOURCE_ID = resourceId(1001)

const project = {
  id: 'svc-api',
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
  projectId: 'svc-api',
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
  id: ALERT_RESOURCE_ID,
  organizationId: ORGANIZATION_RESOURCE_ID,
  escalationPolicyId: ESCALATION_POLICY_RESOURCE_ID,
  title: 'Payments outage',
  priority: 'P0',
  status: 'TRIGGERED',
  alertSource: 'monitor',
  triggeredAt: '2026-06-05T12:00:00.000Z',
}

const alertTimeline = [
  {
    id: resourceId(111),
    incidentId: ALERT_RESOURCE_ID,
    eventType: 'NOTIFICATION_SENT',
    actorUserName: 'Moneat',
    details: {toUserName: 'Ada Lovelace', channel: 'email'},
    createdAt: '2026-06-05T12:01:00.000Z',
  },
  {
    id: resourceId(112),
    incidentId: ALERT_RESOURCE_ID,
    eventType: 'NOTE_ADDED',
    actorUserName: 'Ada Lovelace',
    details: {note: 'Investigating payment failures'},
    createdAt: '2026-06-05T12:02:00.000Z',
  },
]

const declaredIncident = {
  id: DECLARED_INCIDENT_RESOURCE_ID,
  organizationId: ORGANIZATION_RESOURCE_ID,
  title: 'Checkout incident',
  description: 'Customer checkout is unavailable',
  severity: 'SEV-1',
  status: 'OPEN',
  declaredBy: USER_RESOURCE_ID,
  declaredByName: 'Ada Lovelace',
  declaredAt: '2026-06-05T12:03:00.000Z',
  alertCount: 1,
  alerts: [{id: ALERT_RESOURCE_ID, title: 'Payments outage', status: 'TRIGGERED', priority: 'P0'}],
  createdAt: '2026-06-05T12:03:00.000Z',
  updatedAt: '2026-06-05T12:03:00.000Z',
}

const declaredIncidentTimeline = [
  {
    id: resourceId(121),
    incidentId: DECLARED_INCIDENT_RESOURCE_ID,
    eventType: 'DECLARED',
    source: 'incident',
    actorName: 'Ada Lovelace',
    details: {},
    createdAt: '2026-06-05T12:03:00.000Z',
  },
  {
    id: resourceId(122),
    incidentId: DECLARED_INCIDENT_RESOURCE_ID,
    eventType: 'ALERT_LINKED',
    source: 'alert',
    alertTitle: 'Payments outage',
    details: {alertTitle: 'Payments outage'},
    createdAt: '2026-06-05T12:04:00.000Z',
  },
]

const schedule = {
  id: SCHEDULE_RESOURCE_ID,
  organizationId: ORGANIZATION_RESOURCE_ID,
  name: 'API Rotation',
  rotationType: 'WEEKLY',
  handoffTime: '09:00',
  timezone: 'America/New_York',
  createdAt: '2026-06-01T00:00:00.000Z',
  updatedAt: '2026-06-01T00:00:00.000Z',
  participants: [],
  overrides: [],
  currentOnCall: {userId: USER_RESOURCE_ID, userName: 'Ada Lovelace'},
}

const uptimeMonitor = {
  id: UPTIME_MONITOR_RESOURCE_ID,
  organizationId: ORGANIZATION_RESOURCE_ID,
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
  id: HOST_RESOURCE_ID,
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
  id: STATUS_PAGE_RESOURCE_ID,
  organizationId: ORGANIZATION_RESOURCE_ID,
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
  projectId: 'svc-api',
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

const emptyOverviewData: OverviewResponse = {
  systemStatus: {
    state: 'Healthy',
    severity: 'good',
    counts: {incidents: 0, alerts: 0, degraded: 0, hostsOffline: 0},
    ai: {summary: 'No active incidents, firing alerts, degraded services, or offline hosts.'},
  },
  kpis: [],
  serviceHealth: [],
  telemetry: {
    errors: [],
    latency: [],
    throughput: [],
    logs: [],
    deployAtPct: 0,
    deployLabel: 'No deploys',
  },
  triage: {incidents: [], alerts: [], issues: [], security: []},
  infra: {gauges: [], containers: 0, pods: 0, upLabel: '0/0 up'},
  uptime: {monitors: [], upLabel: '0/0 up', statusPages: '0 status pages'},
  deploys: [],
  activity: [],
}

function clickLastText(text: string) {
  const matches = screen.getAllByText(text)
  fireEvent.click(matches[matches.length - 1])
}

function makeAlert(overrides: Record<string, unknown> = {}) {
  return {...alert, ...overrides}
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
    mockApi.getAlerts.mockResolvedValue([alert])
    mockApi.getOnCallSchedules.mockResolvedValue([schedule])
    mockApi.getEscalationPolicies.mockResolvedValue([
      {
        id: ESCALATION_POLICY_RESOURCE_ID,
        organizationId: ORGANIZATION_RESOURCE_ID,
        name: 'Primary escalation',
        repeatCount: 1,
        createdAt: '2026-06-01T00:00:00.000Z',
        updatedAt: '2026-06-01T00:00:00.000Z',
        steps: [],
      },
    ])
    mockApi.getPriorities.mockResolvedValue([])
    mockApi.getBusinessHours.mockResolvedValue({
      id: resourceId(130),
      organizationId: ORGANIZATION_RESOURCE_ID,
      enabled: true,
      timezone: 'America/New_York',
      windows: [
        {
          id: resourceId(131),
          businessHoursId: resourceId(130),
          dayOfWeek: 5,
          startTime: '00:00',
          endTime: '23:59',
        },
      ],
    })
    mockApi.getStatusPages.mockResolvedValue([statusPage])
    mockApi.getStatusPage.mockResolvedValue({
      ...statusPage,
      monitors: [
        {
          id: resourceId(132),
          monitorId: UPTIME_MONITOR_RESOURCE_ID,
          monitorName: 'API availability',
          sortOrder: 0,
        },
      ],
      customDomains: [],
    })
    mockApi.getAlert.mockResolvedValue({...alert, description: 'Payments API is down'})
    mockApi.getAlertTimeline.mockResolvedValue(alertTimeline)
    mockApi.viewAlert.mockResolvedValue(undefined)
    mockApi.acknowledgeAlert.mockResolvedValue({message: 'Alert acknowledged'})
    mockApi.resolveAlert.mockResolvedValue({message: 'Alert resolved'})
    mockApi.markAlertUnavailable.mockResolvedValue(undefined)
    mockApi.addAlertNote.mockResolvedValue(alertTimeline[1])
    mockApi.declareIncidentFromAlert.mockResolvedValue(declaredIncident)
    mockApi.getOnCallIncidents.mockResolvedValue([declaredIncident])
    mockApi.getOnCallIncident.mockResolvedValue(declaredIncident)
    mockApi.getOnCallIncidentTimeline.mockResolvedValue(declaredIncidentTimeline)
    mockApi.resolveOnCallIncident.mockResolvedValue({...declaredIncident, status: 'RESOLVED'})
    mockApi.addOnCallIncidentNote.mockResolvedValue(declaredIncidentTimeline[0])
    mockApi.getOverview.mockResolvedValue(overviewTestData)
  })

  it('renders the overview dashboard with widget content', async () => {
    renderRoute(OverviewRoute)

    expect(await screen.findByText('Overview')).toBeInTheDocument()
    expect(await screen.findByText('Action needed')).toBeInTheDocument()
    expect(screen.getByRole('button', {name: /Customize overview/})).toBeInTheDocument()
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

  it('renders the overview dashboard regardless of empty API state', async () => {
    mockApi.getOverview.mockResolvedValue(emptyOverviewData)

    renderRoute(OverviewRoute)

    expect(await screen.findByText('Overview')).toBeInTheDocument()
    await waitFor(() => expect(mockApi.getOverview).toHaveBeenCalled())
    expect(screen.getByText('Healthy')).toBeInTheDocument()
  })

  it('renders service health and triage content from overview API data', async () => {
    renderRoute(OverviewRoute)

    expect(await screen.findByText('Overview')).toBeInTheDocument()
    expect(await screen.findByText('Elevated 5xx on checkout-api')).toBeInTheDocument()
    expect(screen.getByText('Service health')).toBeInTheDocument()
  })

  it('renders non-page and empty on-call priority states', async () => {
    mockApi.getOnCallSchedules.mockResolvedValue([
      {...schedule, id: resourceId(702), name: 'Backup Rotation', currentOnCall: null},
    ])
    mockApi.getAlerts.mockResolvedValue([
      makeAlert({id: resourceId(201), title: 'Primary P0', priority: 'P0', status: 'TRIGGERED'}),
      makeAlert({id: resourceId(202), title: 'Primary P1', priority: 'P1', status: 'ACKNOWLEDGED'}),
      makeAlert({id: resourceId(203), title: 'Primary P2', priority: 'P2', status: 'TRIGGERED'}),
      makeAlert({id: resourceId(204), title: 'Secondary P2', priority: 'P2', status: 'ACKNOWLEDGED'}),
      makeAlert({id: resourceId(205), title: 'Tertiary P1', priority: 'P1', status: 'TRIGGERED'}),
      makeAlert({id: resourceId(206), title: 'Overflow P0', priority: 'P0', status: 'TRIGGERED'}),
      makeAlert({id: resourceId(207), title: 'Low P3', priority: 'P3', status: 'TRIGGERED'}),
      makeAlert({id: resourceId(208), title: 'Low P4', priority: 'P4', status: 'ACKNOWLEDGED'}),
      makeAlert({id: resourceId(209), title: 'Low P5', priority: 'P5', status: 'TRIGGERED'}),
      makeAlert({id: resourceId(210), title: 'Low Overflow P5', priority: 'P5', status: 'ACKNOWLEDGED'}),
    ])
    mockApi.getBusinessHours.mockResolvedValue({
      id: resourceId(133),
      organizationId: ORGANIZATION_RESOURCE_ID,
      enabled: true,
      timezone: 'America/New_York',
      windows: [
        {
          id: resourceId(134),
          businessHoursId: resourceId(133),
          dayOfWeek: 0,
          startTime: '00:00',
          endTime: '00:01',
        },
      ],
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
    mockApi.getAlerts.mockResolvedValue([])
    mockApi.getBusinessHours.mockResolvedValue({
      id: resourceId(135),
      organizationId: ORGANIZATION_RESOURCE_ID,
      enabled: false,
      timezone: 'America/New_York',
      windows: [],
    })

    renderRoute(OnCallOverviewRoute)

    expect(await screen.findByText('No active high-priority alerts')).toBeInTheDocument()
    expect(await screen.findByText('No low-priority alerts')).toBeInTheDocument()
    expect(await screen.findByText('No schedules configured')).toBeInTheDocument()
  })

  it('checks auth before rendering the overview', async () => {
    mockApi.isAuthenticated.mockReturnValueOnce(false).mockReturnValueOnce(false).mockReturnValue(true)

    renderRoute(OverviewRoute)

    expect(await screen.findByText('Overview')).toBeInTheDocument()
    expect(mockApi.checkAuth).toHaveBeenCalled()
  })

  it('renders plural on-call assignments and assignee fallbacks', async () => {
    mockApi.getOnCallSchedules.mockResolvedValue([
      {
        ...schedule,
        id: resourceId(711),
        name: 'Primary Rotation',
        currentOnCall: {userId: USER_RESOURCE_ID, userName: 'Ada Lovelace'},
      },
      {
        ...schedule,
        id: resourceId(712),
        name: 'Secondary Rotation',
        currentOnCall: {userId: USER_RESOURCE_ID, userName: 'Grace Hopper'},
      },
      {...schedule, id: resourceId(713), name: 'Fallback Rotation', currentOnCall: {userName: 'Fallback User'}},
    ])
    mockApi.getAlerts.mockResolvedValue([])
    mockApi.getBusinessHours.mockResolvedValue({
      id: resourceId(136),
      organizationId: ORGANIZATION_RESOURCE_ID,
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

  it('renders overview dashboard when enterprise modules are unavailable', async () => {
    mockEnterpriseFeatures.current = {enterprise: true, modules: [], selfHost: false}

    renderRoute(OverviewRoute)

    expect(await screen.findByText('Overview')).toBeInTheDocument()
    expect(await screen.findByText('Action needed')).toBeInTheDocument()
  })

  it('renders the alert list route with P-priority alerts', async () => {
    mockRoutePathname.current = '/on-call/alerts'
    mockApi.getAlerts.mockResolvedValue([
      makeAlert({
        id: resourceId(401),
        title: 'Escalating payment alert',
        description: 'Payment latency is rising',
        priority: 'P2',
        status: 'TRIGGERED',
        alertSource: 'uptime',
        nextEscalationAt: new Date(Date.now() + 60_000).toISOString(),
        viewedByCurrentUser: true,
      }),
      makeAlert({
        id: resourceId(402),
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
    mockRouteParams.current = {alertId: ALERT_RESOURCE_ID}

    renderRoute(AlertDetailRoute)

    expect(await screen.findByText('Payments outage')).toBeInTheDocument()
    expect(await screen.findByText('Payments API is down')).toBeInTheDocument()
    expect(await screen.findByText('Priority')).toBeInTheDocument()
    expect((await screen.findAllByText('P0')).length).toBeGreaterThan(0)
    expect(await screen.findByText('Alert history and updates')).toBeInTheDocument()
    expect(await screen.findByText('to Ada Lovelace via email')).toBeInTheDocument()
    expect(await screen.findByText('"Investigating payment failures"')).toBeInTheDocument()
    expect(await screen.findByText('Declare Incident')).toBeInTheDocument()
    expect(mockApi.viewAlert).toHaveBeenCalledWith(ALERT_RESOURCE_ID)

    fireEvent.click(await screen.findByText('Acknowledge'))
    await waitFor(() => expect(mockApi.acknowledgeAlert).toHaveBeenCalledWith(ALERT_RESOURCE_ID))
    fireEvent.click(await screen.findByText('Resolve'))
    await waitFor(() => expect(mockApi.resolveAlert).toHaveBeenCalledWith(ALERT_RESOURCE_ID))
    fireEvent.click(await screen.findByText("I'm not available"))
    await waitFor(() => expect(mockApi.markAlertUnavailable).toHaveBeenCalledWith(ALERT_RESOURCE_ID))
    fireEvent.change(screen.getByPlaceholderText('Enter your note...'), {target: {value: 'Adding context'}})
    clickLastText('Add note')
    await waitFor(() => expect(mockApi.addAlertNote).toHaveBeenCalledWith(ALERT_RESOURCE_ID, 'Adding context'))
  })

  it('rejects numeric alert identifiers without fetching alert data', async () => {
    mockRouteParams.current = {alertId: '101'}

    renderRoute(AlertDetailRoute)

    expect(await screen.findByText('Invalid alert ID')).toBeInTheDocument()
    expect(mockApi.getAlert).not.toHaveBeenCalled()
  })

  it('renders declared incident list route with SEV severity', async () => {
    mockRoutePathname.current = '/on-call/incidents'
    mockApi.getOnCallIncidents.mockResolvedValue([
      declaredIncident,
      {
        ...declaredIncident,
        id: RESOLVED_DECLARED_INCIDENT_RESOURCE_ID,
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
    mockRouteParams.current = {incidentId: DECLARED_INCIDENT_RESOURCE_ID}

    renderRoute(IncidentDetailRoute)

    expect(await screen.findByText('Checkout incident')).toBeInTheDocument()
    expect(await screen.findByText('Customer checkout is unavailable')).toBeInTheDocument()
    expect(await screen.findByText('Severity')).toBeInTheDocument()
    expect((await screen.findAllByText('SEV-1')).length).toBeGreaterThan(0)
    expect(await screen.findByText('Linked alerts')).toBeInTheDocument()
    expect(await screen.findByText(`Alert #${ALERT_RESOURCE_ID} · TRIGGERED`)).toBeInTheDocument()
    expect(await screen.findByText('from Payments outage')).toBeInTheDocument()
    expect(await screen.findByText('Resolve incident')).toBeInTheDocument()

    fireEvent.change(screen.getByPlaceholderText('Enter your note...'), {target: {value: 'Incident update'}})
    clickLastText('Add note')
    await waitFor(() => {
      expect(mockApi.addOnCallIncidentNote).toHaveBeenCalledWith(DECLARED_INCIDENT_RESOURCE_ID, 'Incident update')
    })
  })

  it('renders nested route outlets for alert and incident list shells', async () => {
    mockRoutePathname.current = `/on-call/alerts/${ALERT_RESOURCE_ID}`
    renderRoute(AlertsRoute)
    expect(await screen.findByTestId('outlet')).toBeInTheDocument()

    mockRoutePathname.current = `/on-call/incidents/${DECLARED_INCIDENT_RESOURCE_ID}`
    renderRoute(IncidentsRoute)
    expect((await screen.findAllByTestId('outlet')).length).toBeGreaterThan(1)
  })

  it('renders empty alert and declared incident list states', async () => {
    mockRoutePathname.current = '/on-call/alerts'
    mockApi.getAlerts.mockResolvedValue([])
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
    mockRouteParams.current = {alertId: UNKNOWN_RESOURCE_ID}
    mockApi.getAlert.mockResolvedValueOnce(null)
    renderRoute(AlertDetailRoute)
    expect(await screen.findByText('Alert not found')).toBeInTheDocument()

    mockRouteParams.current = {alertId: ACKNOWLEDGED_ALERT_RESOURCE_ID}
    mockApi.getAlert.mockResolvedValueOnce({
      ...alert,
      id: ACKNOWLEDGED_ALERT_RESOURCE_ID,
      status: 'ACKNOWLEDGED',
      priority: 'P2',
      acknowledgedBy: USER_RESOURCE_ID,
      acknowledgedByName: 'Ada Lovelace',
      acknowledgedAt: '2026-06-05T12:05:00.000Z',
      description: '',
    })
    mockApi.getAlertTimeline.mockResolvedValueOnce([
      {
        id: resourceId(141),
        incidentId: ACKNOWLEDGED_ALERT_RESOURCE_ID,
        eventType: 'ESCALATED',
        details: {stepNumber: 1},
        createdAt: '2026-06-05T12:06:00.000Z',
      },
      {
        id: resourceId(142),
        incidentId: ACKNOWLEDGED_ALERT_RESOURCE_ID,
        eventType: 'REASSIGNED',
        details: {reason: 'unavailable'},
        createdAt: '2026-06-05T12:07:00.000Z',
      },
      {
        id: resourceId(143),
        incidentId: ACKNOWLEDGED_ALERT_RESOURCE_ID,
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

    mockRouteParams.current = {alertId: RESOLVED_ALERT_RESOURCE_ID}
    mockApi.getAlert.mockResolvedValueOnce({
      ...alert,
      id: RESOLVED_ALERT_RESOURCE_ID,
      status: 'RESOLVED',
      priority: 'P4',
      resolvedBy: OTHER_USER_RESOURCE_ID,
      resolvedByName: 'Grace Hopper',
      resolvedAt: '2026-06-05T12:09:00.000Z',
    })
    mockApi.getAlertTimeline.mockResolvedValueOnce([])
    renderRoute(AlertDetailRoute)
    expect(await screen.findByText('Resolved By')).toBeInTheDocument()
    expect(await screen.findByText('Grace Hopper')).toBeInTheDocument()
  })

  it('renders declared incident not-found and resolved detail states', async () => {
    mockRouteParams.current = {incidentId: '501'}
    renderRoute(IncidentDetailRoute)
    expect(await screen.findByText('Invalid incident ID')).toBeInTheDocument()
    expect(mockApi.getOnCallIncident).not.toHaveBeenCalled()

    mockRouteParams.current = {incidentId: UNKNOWN_RESOURCE_ID}
    mockApi.getOnCallIncident.mockResolvedValueOnce(null)
    renderRoute(IncidentDetailRoute)
    expect(await screen.findByText('Incident not found')).toBeInTheDocument()

    mockRouteParams.current = {incidentId: RESOLVED_DECLARED_INCIDENT_RESOURCE_ID}
    mockApi.getOnCallIncident.mockResolvedValueOnce({
      ...declaredIncident,
      id: RESOLVED_DECLARED_INCIDENT_RESOURCE_ID,
      title: 'Resolved deploy incident',
      description: '',
      severity: 'SEV3',
      status: 'RESOLVED',
      alerts: [],
      resolvedBy: OTHER_USER_RESOURCE_ID,
      resolvedByName: 'Grace Hopper',
      resolvedAt: '2026-06-05T13:00:00.000Z',
    })
    mockApi.getOnCallIncidentTimeline.mockResolvedValueOnce([
      {
        id: resourceId(151),
        incidentId: RESOLVED_DECLARED_INCIDENT_RESOURCE_ID,
        eventType: 'NOTIFICATION_SENT',
        actorUserName: 'Moneat',
        actorName: 'Moneat',
        details: {channel: 'sms'},
        createdAt: '2026-06-05T12:10:00.000Z',
      },
      {
        id: resourceId(152),
        incidentId: RESOLVED_DECLARED_INCIDENT_RESOURCE_ID,
        eventType: 'REASSIGNED',
        actorName: 'Ada Lovelace',
        details: {toUserName: 'Grace Hopper'},
        createdAt: '2026-06-05T12:11:00.000Z',
      },
      {
        id: resourceId(153),
        incidentId: RESOLVED_DECLARED_INCIDENT_RESOURCE_ID,
        eventType: 'NOTE_ADDED',
        actorName: 'Grace Hopper',
        details: {note: 'Deployment rolled back'},
        createdAt: '2026-06-05T12:12:00.000Z',
      },
      {
        id: resourceId(154),
        incidentId: RESOLVED_DECLARED_INCIDENT_RESOURCE_ID,
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
