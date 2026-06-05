import React from 'react'
import {beforeEach, describe, expect, it, vi} from 'vitest'
import {screen} from '@testing-library/react'
import {clearAuthStorage, renderRoute} from '@/test/utils'

const {mockApi} = vi.hoisted(() => ({
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
}))

vi.mock('@/lib/api', () => ({
  api: mockApi,
}))

vi.mock('@/hooks/useEnterpriseFeatures', () => ({
  hasEnterpriseModule: () => true,
  useEnterpriseFeatures: () => ({
    data: {enterprise: true, modules: ['oncall'], selfHost: false},
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
    useSearch: () => ({view: 'overview'}),
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

describe('overview alert and incident dashboard', () => {
  beforeEach(() => {
    vi.clearAllMocks()
    clearAuthStorage()
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
})
