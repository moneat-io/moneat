import React from 'react'
import { beforeEach, describe, expect, it, vi } from 'vitest'
import { screen, fireEvent } from '@testing-library/react'
import { renderRoute, clearAuthStorage } from '@/test/utils'

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
    getApmErrors: vi.fn(),
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

vi.mock('@/hooks/useEnterpriseFeatures', () => ({
  useHasModule: () => false,
}))

vi.mock('@tanstack/react-router', () => ({
  createFileRoute: () => (options: Record<string, unknown>) => ({
    ...options,
    options,
    useParams: () => ({}),
  }),
  Link: ({ children, ...props }: { children: React.ReactNode }) => React.createElement('a', props, children),
  redirect: (opts: Record<string, unknown>) => ({ ...opts, __redirect: true }),
  useNavigate: () => mockNavigate,
  useMatches: () => [],
  Outlet: () => null,
}))

import { Route as IssuesIndexRoute } from '../issues.index'

const mockProject = {
  id: 'proj-1',
  name: 'Test Project',
  slug: 'test-project',
  platform: 'javascript',
}

const mockIssues = [
  {
    id: 'issue-1',
    title: 'TypeError: null ref',
    culprit: 'app.main',
    level: 'error',
    platform: 'javascript',
    status: 'unresolved',
    eventCount: 42,
    userCount: 7,
    firstSeen: new Date().toISOString(),
    lastSeen: new Date().toISOString(),
  },
  {
    id: 'issue-2',
    title: 'ValueError: invalid input',
    culprit: 'api.handler',
    level: 'warning',
    platform: 'python',
    status: 'resolved',
    eventCount: 150000,
    userCount: 3,
    firstSeen: '2026-01-01T00:00:00Z',
    lastSeen: '2026-03-14T12:00:00Z',
  },
  {
    id: 'issue-3',
    title: 'Fatal crash',
    culprit: '',
    level: 'fatal',
    platform: 'android',
    status: 'ignored',
    eventCount: 5,
    userCount: 1,
    firstSeen: '2026-02-01T00:00:00Z',
    lastSeen: '2026-02-15T00:00:00Z',
  },
  {
    id: 'issue-4',
    title: 'Debug trace',
    culprit: 'debug.module',
    level: 'debug',
    platform: 'node',
    status: 'resolvedInNextRelease',
    eventCount: 1,
    userCount: 0,
    firstSeen: '2026-03-14T17:00:00Z',
    lastSeen: '2026-03-14T17:00:00Z',
  },
  {
    id: 'issue-5',
    title: 'Info message',
    culprit: 'info: Info message',
    level: 'info',
    platform: 'react',
    status: 'unresolved',
    eventCount: 10500,
    userCount: 2,
    firstSeen: '2026-03-14T06:00:00Z',
    lastSeen: '2026-03-14T08:00:00Z',
  },
]

describe('Issues Index - data coverage', () => {
  beforeEach(() => {
    vi.clearAllMocks()
    clearAuthStorage()
    mockApi.isAuthenticated.mockReturnValue(true)
    mockApi.checkAuth.mockResolvedValue(true)
    mockApi.getProjects.mockResolvedValue([mockProject])
    mockApi.getIssues.mockResolvedValue(mockIssues)
    mockApi.getProjectStats.mockResolvedValue(null)
    mockApi.updateIssue.mockResolvedValue(undefined)
    mockApi.getApmErrors.mockResolvedValue({ errors: [], totalCount: 0 })
  })

  it('renders issues list with projects and issues data', async () => {
    renderRoute(IssuesIndexRoute)

    // Should show the dashboard header
    expect(await screen.findByText('Dashboard')).toBeInTheDocument()

    // Issues should be displayed (wait for async data)
    expect(await screen.findByText(/app.main: TypeError: null ref/)).toBeInTheDocument()

    // Status badges
    expect(screen.getByText('Resolved')).toBeInTheDocument()
    expect(screen.getByText('Ignored')).toBeInTheDocument()
    expect(screen.getByText('Next Release')).toBeInTheDocument()

    // New badge (multiple issues may be "new" since all were first seen recently)
    expect(screen.getAllByText('New').length).toBeGreaterThan(0)

    // Event count formatting
    expect(screen.getByText('42')).toBeInTheDocument()
    expect(screen.getByText('150k')).toBeInTheDocument()
    expect(screen.getByText('10.5k')).toBeInTheDocument()

    // Result count
    expect(screen.getByText('5 results')).toBeInTheDocument()
  })

  it('renders search and filter controls', async () => {
    renderRoute(IssuesIndexRoute)

    // Wait for issues to load so the select-all appears
    await screen.findByText(/app.main: TypeError: null ref/)
    expect(screen.getByPlaceholderText('Search issues...')).toBeInTheDocument()
    expect(screen.getByText('Select all')).toBeInTheDocument()
  })

  it('filters issues by search query', async () => {
    renderRoute(IssuesIndexRoute)

    await screen.findByText('Dashboard')
    const searchInput = screen.getByPlaceholderText('Search issues...')
    fireEvent.change(searchInput, { target: { value: 'TypeError' } })

    expect(screen.getByText('1 result')).toBeInTheDocument()
  })

  it('shows no issues match filters when search has no results', async () => {
    renderRoute(IssuesIndexRoute)

    await screen.findByText('Dashboard')
    const searchInput = screen.getByPlaceholderText('Search issues...')
    fireEvent.change(searchInput, { target: { value: 'nonexistent-query' } })

    expect(screen.getByText('No issues match your filters')).toBeInTheDocument()
  })

  it('shows empty state when issues list is empty for default filter', async () => {
    mockApi.getIssues.mockResolvedValue([])

    renderRoute(IssuesIndexRoute)

    expect(await screen.findByText('No issues yet')).toBeInTheDocument()
  })

  it('switches to APM Errors tab', async () => {
    renderRoute(IssuesIndexRoute)

    await screen.findByText('Dashboard')
    const apmTab = screen.getByText('APM Errors')
    fireEvent.click(apmTab)

    expect(await screen.findByText('No APM errors found')).toBeInTheDocument()
    expect(mockApi.getApmErrors).toHaveBeenCalledTimes(1)
    expect(mockApi.getApmErrors).toHaveBeenCalledWith({
      service: undefined,
      limit: 50,
      offset: 0,
      timeRange: '24h',
    })
  })

  it('renders APM errors tab with data', async () => {
    mockApi.getApmErrors.mockResolvedValue({
      errors: [
        {
          service: 'api-gateway',
          resource: 'GET /users',
          errorMessage: 'Connection refused',
          errorType: 'NetworkError',
          count: 25,
          lastSeen: '2026-03-14T15:00:00Z',
          traceId: '98765',
        },
        {
          service: 'api-gateway',
          resource: 'POST /data',
          errorMessage: 'Timeout',
          errorType: '',
          count: 10,
          lastSeen: null,
          traceId: null,
        },
      ],
      totalCount: 2,
    })

    renderRoute(IssuesIndexRoute)

    await screen.findByText('Dashboard')
    fireEvent.click(screen.getByText('APM Errors'))

    expect(await screen.findByText('Connection refused')).toBeInTheDocument()
    expect(screen.getByText('NetworkError')).toBeInTheDocument()
    expect(screen.getByText('View trace')).toBeInTheDocument()
  })

  it('renders project settings and new project buttons', async () => {
    renderRoute(IssuesIndexRoute)

    await screen.findByText('Dashboard')
    expect(screen.getByText('New Project')).toBeInTheDocument()
    expect(screen.getByLabelText('Project settings')).toBeInTheDocument()
  })
})
