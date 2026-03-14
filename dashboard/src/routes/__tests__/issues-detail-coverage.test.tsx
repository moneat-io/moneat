import React from 'react'
import { beforeEach, describe, expect, it, vi } from 'vitest'
import { screen } from '@testing-library/react'
import { renderRouteWithProviders, clearAuthStorage } from '@/test/utils'

const { mockNavigate, mockToast, mockApi } = vi.hoisted(() => ({
  mockNavigate: vi.fn(),
  mockToast: vi.fn(),
  mockApi: {
    isAuthenticated: vi.fn(),
    checkAuth: vi.fn(),
    getProjects: vi.fn(),
    getIssues: vi.fn(),
    getProjectStats: vi.fn(),
    updateIssue: vi.fn(),
    getIssue: vi.fn(),
    getIssueEvents: vi.fn(),
    getIssueTransactions: vi.fn(),
    getReplaysForIssue: vi.fn(),
    getTransactionSpans: vi.fn(),
    getCurrentUser: vi.fn(),
    updateUserTimezone: vi.fn(),
  },
}))

vi.mock('@/lib/api', () => ({
  api: mockApi,
}))

vi.mock('@/hooks/useToast', () => ({
  useToast: () => ({ toast: mockToast }),
}))

vi.mock('@/hooks/useTimezone', () => ({
  useTimezone: () => ({ timezone: 'UTC' }),
}))

vi.mock('@/hooks/useEnterpriseFeatures', () => ({
  useHasModule: () => false,
}))

vi.mock('@/components/SpanWaterfall', () => ({
  SpanWaterfall: () => <div>span-waterfall</div>,
}))

vi.mock('@/components/logs/EmbeddedLogs', () => ({
  EmbeddedLogs: () => <div>embedded-logs</div>,
}))

vi.mock('@tanstack/react-router', () => ({
  createFileRoute: () => (options: Record<string, unknown>) => ({
    ...options,
    options,
    useParams: () => ({ issueId: 'issue-123' }),
  }),
  Link: ({ children, ...props }: { children: React.ReactNode }) => React.createElement('a', props, children),
  redirect: (opts: Record<string, unknown>) => ({ ...opts, __redirect: true }),
  useNavigate: () => mockNavigate,
  useMatches: () => [],
  Outlet: () => null,
}))

import { Route as IssueDetailRoute } from '../issues.$issueId'

const mockIssue = {
  id: 'issue-123',
  title: 'TypeError: Cannot read property',
  culprit: 'app.main',
  level: 'error',
  platform: 'javascript',
  status: 'unresolved',
  eventCount: 42,
  userCount: 7,
  firstSeen: '2026-03-13T10:00:00Z',
  lastSeen: '2026-03-14T15:00:00Z',
  projectId: 'proj-1',
  projectName: 'My Project',
  latestEvent: null,
}

const mockEvent = {
  eventId: 'evt-1',
  timestamp: '2026-03-14T15:00:00Z',
  environment: 'production',
  release: 'v1.2.3',
  message: 'TypeError: Cannot read property',
  user: { id: 'u1', email: 'test@example.com' },
  tags: { browser: 'Chrome', os: 'Linux', service: 'web' },
  contexts: JSON.stringify({
    browser: { name: 'Chrome', version: '120' },
    os: { name: 'Linux' },
    trace: { trace_id: '12345' },
    nested: { deep: { key: 'value' } },
  }),
  exception: JSON.stringify({
    values: [
      {
        type: 'TypeError',
        value: 'Cannot read property of undefined',
        stacktrace: {
          frames: [
            {
              function: 'handleClick',
              filename: 'src/app.js',
              lineno: 42,
              colno: 10,
              module: 'app',
              in_app: true,
              context_line: '  const x = obj.prop;',
              pre_context: ['function handleClick() {'],
              post_context: ['  return x;'],
              vars: { obj: 'null' },
            },
          ],
        },
      },
    ],
  }),
  breadcrumbs: JSON.stringify([
    { timestamp: 1710421200, category: 'ui.lifecycle', message: 'App started', level: 'info' },
    { timestamp: 1710421210, category: 'ui.click', data: { 'view.class': 'com.example.Button', 'view.id': 'btn-submit' }, level: 'info' },
    { timestamp: 1710421220, category: 'navigation', data: { from: '/home', to: '/dashboard' }, level: 'info' },
    { timestamp: 1710421230, category: 'http.client', data: { url: 'https://api.example.com/data', status_code: 500, method: 'GET' }, level: 'error' },
    { timestamp: 1710421240, category: 'device.event', data: { action: 'BATTERY_LOW', level: 15, charging: false }, level: 'warning' },
    { timestamp: 1710421245, category: 'device.event', data: { action: 'BATTERY_CHARGING', level: 20, charging: true }, level: 'info' },
    { timestamp: 1710421250, category: 'app.lifecycle', data: { state: 'foreground' }, level: 'info' },
    { timestamp: 1710421260, category: 'message.log', message: 'Debug log entry', level: 'debug' },
    { timestamp: 1710421270, category: 'action', data: { type: 'redux' }, level: 'info' },
    { timestamp: 0.001, category: 'event', level: 'info' },
  ]),
}

const mockTransaction = {
  eventId: 'tx-1',
  name: 'GET /api/data',
  op: 'http.server',
  duration: 1500,
  timestamp: '2026-03-14T14:00:00Z',
}

const mockReplay = {
  replayId: 'replay-1',
  user: { email: 'user@example.com' },
  durationMs: 45000,
  startedAt: '2026-03-14T13:00:00Z',
  errorCount: 2,
}

const mockReplayAnon = {
  replayId: 'replay-2',
  user: null,
  durationMs: 12000,
  startedAt: '2026-03-14T12:00:00Z',
  errorCount: 0,
}

describe('Issue Detail - full data coverage', () => {
  beforeEach(() => {
    vi.clearAllMocks()
    clearAuthStorage()
    mockApi.isAuthenticated.mockReturnValue(true)
    mockApi.checkAuth.mockResolvedValue(true)
    mockApi.getProjects.mockResolvedValue([])
    mockApi.updateIssue.mockResolvedValue(undefined)
    mockApi.getIssue.mockResolvedValue(null)
    mockApi.getIssueEvents.mockResolvedValue([])
    mockApi.getIssueTransactions.mockResolvedValue([])
    mockApi.getReplaysForIssue.mockResolvedValue([])
    mockApi.getTransactionSpans.mockResolvedValue(undefined)
  })

  it('renders issue with full event data, tags, contexts, breadcrumbs, transactions, and replays', async () => {
    mockApi.getIssue.mockResolvedValue({ ...mockIssue, status: 'unresolved' })
    mockApi.getIssueEvents.mockResolvedValue([mockEvent, { ...mockEvent, eventId: 'evt-2', message: 'Second event' }])
    mockApi.getIssueTransactions.mockResolvedValue([mockTransaction])
    mockApi.getReplaysForIssue.mockResolvedValue([mockReplay, mockReplayAnon])
    mockApi.getTransactionSpans.mockResolvedValue({
      transaction: { eventId: 'tx-1' },
      spans: [{ spanId: 's1' }],
    })

    const Component = (IssueDetailRoute as unknown as { component: React.ComponentType }).component
    renderRouteWithProviders(Component)

    // Issue header (appears multiple times in title + breadcrumb)
    expect((await screen.findAllByText('TypeError: Cannot read property')).length).toBeGreaterThan(0)
    expect(screen.getByText('ERROR')).toBeInTheDocument()
    expect(screen.getByText('javascript')).toBeInTheDocument()
    expect(screen.getByText('app.main')).toBeInTheDocument()

    // Event stats
    expect(screen.getByText('42')).toBeInTheDocument()
    expect(screen.getByText('events')).toBeInTheDocument()
    expect(screen.getByText('7')).toBeInTheDocument()
    expect(screen.getByText('users')).toBeInTheDocument()

    // Resolve button for unresolved
    expect(screen.getByText('Resolve')).toBeInTheDocument()

    // Exception / stack trace
    expect(screen.getByText('Exception')).toBeInTheDocument()
    expect(screen.getByText('TypeError: Cannot read property of undefined')).toBeInTheDocument()

    // Breadcrumbs
    expect(screen.getByText('Breadcrumbs')).toBeInTheDocument()

    // Tags
    expect(screen.getByText('browser:')).toBeInTheDocument()
    expect(screen.getAllByText('Chrome').length).toBeGreaterThan(0)

    // Event details
    expect(screen.getByText('Event Details')).toBeInTheDocument()
    expect(screen.getByText('production')).toBeInTheDocument()
    expect(screen.getByText('v1.2.3')).toBeInTheDocument()
    expect(screen.getByText('test@example.com')).toBeInTheDocument()

    // Transactions
    expect(screen.getByText('Transactions (1)')).toBeInTheDocument()
    expect(screen.getByText('GET /api/data')).toBeInTheDocument()
    expect(screen.getByText('1.50s')).toBeInTheDocument()

    // Replays
    expect(screen.getByText('Replays (2)')).toBeInTheDocument()
    expect(screen.getByText('user@example.com')).toBeInTheDocument()
    expect(screen.getByText('Anonymous')).toBeInTheDocument()

    // Recent events (2 events triggers this section)
    expect(screen.getByText('Recent Events (2)')).toBeInTheDocument()

    // Logs Context
    expect(screen.getByText('Logs Context')).toBeInTheDocument()

    // Spans (loaded via secondary query after transactions)
    expect(await screen.findByText(/Spans Preview/)).toBeInTheDocument()
    expect(screen.getByText('span-waterfall')).toBeInTheDocument()

    // Context sections
    expect(screen.getByText('Tags & Context')).toBeInTheDocument()
    expect(screen.getByText('This error has an associated APM trace')).toBeInTheDocument()
    expect(screen.getByText('View trace')).toBeInTheDocument()
  })

  it('renders resolved issue with Unresolve button', async () => {
    mockApi.getIssue.mockResolvedValue({ ...mockIssue, status: 'resolved' })

    const Component = (IssueDetailRoute as unknown as { component: React.ComponentType }).component
    renderRouteWithProviders(Component)

    expect(await screen.findByText('Resolved')).toBeInTheDocument()
    expect(screen.getByText('Unresolve')).toBeInTheDocument()
  })

  it('renders ignored issue with Unignore button', async () => {
    mockApi.getIssue.mockResolvedValue({ ...mockIssue, status: 'ignored' })

    const Component = (IssueDetailRoute as unknown as { component: React.ComponentType }).component
    renderRouteWithProviders(Component)

    expect(await screen.findByText('Ignored')).toBeInTheDocument()
    expect(screen.getByText('Unignore')).toBeInTheDocument()
  })

  it('renders resolvedInNextRelease status', async () => {
    mockApi.getIssue.mockResolvedValue({ ...mockIssue, status: 'resolvedInNextRelease' })

    const Component = (IssueDetailRoute as unknown as { component: React.ComponentType }).component
    renderRouteWithProviders(Component)

    expect(await screen.findByText('Resolves in Next Release')).toBeInTheDocument()
  })

  it('renders event with no exception as "No stack trace available"', async () => {
    const eventNoException = { ...mockEvent, exception: null }
    mockApi.getIssue.mockResolvedValue(mockIssue)
    mockApi.getIssueEvents.mockResolvedValue([eventNoException])

    const Component = (IssueDetailRoute as unknown as { component: React.ComponentType }).component
    renderRouteWithProviders(Component)

    expect(await screen.findByText('No stack trace available')).toBeInTheDocument()
  })

  it('renders issue with fatal level styling', async () => {
    mockApi.getIssue.mockResolvedValue({ ...mockIssue, level: 'fatal' })

    const Component = (IssueDetailRoute as unknown as { component: React.ComponentType }).component
    renderRouteWithProviders(Component)

    expect(await screen.findByText('FATAL')).toBeInTheDocument()
  })

  it('renders issue with warning level', async () => {
    mockApi.getIssue.mockResolvedValue({ ...mockIssue, level: 'warning' })

    const Component = (IssueDetailRoute as unknown as { component: React.ComponentType }).component
    renderRouteWithProviders(Component)

    expect(await screen.findByText('WARNING')).toBeInTheDocument()
  })

  it('renders issue with info level', async () => {
    mockApi.getIssue.mockResolvedValue({ ...mockIssue, level: 'info' })

    const Component = (IssueDetailRoute as unknown as { component: React.ComponentType }).component
    renderRouteWithProviders(Component)

    expect(await screen.findByText('INFO')).toBeInTheDocument()
  })

  it('renders issue with debug level', async () => {
    mockApi.getIssue.mockResolvedValue({ ...mockIssue, level: 'debug' })

    const Component = (IssueDetailRoute as unknown as { component: React.ComponentType }).component
    renderRouteWithProviders(Component)

    expect(await screen.findByText('DEBUG')).toBeInTheDocument()
  })

  it('renders event with tags but no contexts shows "No context entries"', async () => {
    const eventNoContexts = { ...mockEvent, tags: { env: 'prod' }, contexts: '{}' }
    mockApi.getIssue.mockResolvedValue(mockIssue)
    mockApi.getIssueEvents.mockResolvedValue([eventNoContexts])

    const Component = (IssueDetailRoute as unknown as { component: React.ComponentType }).component
    renderRouteWithProviders(Component)

    expect(await screen.findByText('No context entries')).toBeInTheDocument()
    expect(screen.getByText('env:')).toBeInTheDocument()
  })

  it('renders event with invalid breadcrumbs JSON as raw text', async () => {
    const eventBadBreadcrumbs = { ...mockEvent, breadcrumbs: 'not valid json{{{' }
    mockApi.getIssue.mockResolvedValue(mockIssue)
    mockApi.getIssueEvents.mockResolvedValue([eventBadBreadcrumbs])

    const Component = (IssueDetailRoute as unknown as { component: React.ComponentType }).component
    renderRouteWithProviders(Component)

    expect(await screen.findByText('not valid json{{{')).toBeInTheDocument()
  })

  it('renders event with invalid exception JSON as raw text', async () => {
    const eventBadException = { ...mockEvent, exception: 'raw stack trace text' }
    mockApi.getIssue.mockResolvedValue(mockIssue)
    mockApi.getIssueEvents.mockResolvedValue([eventBadException])

    const Component = (IssueDetailRoute as unknown as { component: React.ComponentType }).component
    renderRouteWithProviders(Component)

    expect(await screen.findByText(/Raw stack trace/)).toBeInTheDocument()
  })

  it('renders loading state', () => {
    mockApi.getIssue.mockReturnValue(new Promise(() => {}))

    const Component = (IssueDetailRoute as unknown as { component: React.ComponentType }).component
    renderRouteWithProviders(Component)

    expect(screen.getByText('Loading...')).toBeInTheDocument()
  })
})
