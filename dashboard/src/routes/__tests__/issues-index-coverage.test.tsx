import React from 'react'
import { beforeEach, describe, expect, it, vi } from 'vitest'
import { screen, fireEvent, waitFor } from '@testing-library/react'
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
  Link: ({
    children,
    search,
    ...props
  }: {
    children: React.ReactNode
    search?: Record<string, unknown>
  }) => React.createElement('a', {
    ...props,
    'data-search': search ? JSON.stringify(search) : undefined,
  }, children),
  redirect: (opts: Record<string, unknown>) => ({ ...opts, __redirect: true }),
  useNavigate: () => mockNavigate,
  useMatches: () => [],
  Outlet: () => null,
}))

import { Route as IssuesIndexRoute } from '../issues.index'

const mockProject = {
  id: 1,
  resourceId: 'proj-1',
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
    mockApi.getApmErrors.mockResolvedValue({ errors: [], totalCount: 0, serviceFacets: [] })
  })

  it('renders issues list with projects and issues data', async () => {
    renderRoute(IssuesIndexRoute)

    // Should show the search bar
    expect(await screen.findByRole('textbox')).toBeInTheDocument()

    // Issues should be displayed (wait for async data)
    expect(await screen.findByText(/app.main: TypeError: null ref/)).toBeInTheDocument()

    // Status badges (the row labels also appear as rail facet options, so the
    // shared-cased ones can occur more than once).
    expect(screen.getAllByText('Resolved').length).toBeGreaterThan(0)
    expect(screen.getAllByText('Ignored').length).toBeGreaterThan(0)
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
    expect(screen.getByRole('textbox')).toBeInTheDocument()
    expect(screen.getByText('Select all')).toBeInTheDocument()
  })

  it('filters issues by search query', async () => {
    renderRoute(IssuesIndexRoute)

    await screen.findByText(/app.main: TypeError: null ref/)
    const searchInput = screen.getByRole('textbox')
    fireEvent.change(searchInput, { target: { value: 'TypeError' } })
    fireEvent.keyDown(searchInput, { key: 'Enter' })

    expect(screen.getByText('1 result')).toBeInTheDocument()
  })

  it('filters by status from the rail and clears it again (removable, multi-select capable)', async () => {
    renderRoute(IssuesIndexRoute)

    await screen.findByText(/app.main: TypeError: null ref/)
    expect(screen.getByText('5 results')).toBeInTheDocument()

    // Include a Status facet from the rail → client-side filter to that status.
    fireEvent.click(screen.getByRole('button', { name: 'Resolved' }))
    expect(screen.getByText('1 result')).toBeInTheDocument()

    // Toggling it off clears the filter — the last selection is removable.
    fireEvent.click(screen.getByRole('button', { name: 'Resolved' }))
    expect(screen.getByText('5 results')).toBeInTheDocument()
  })

  it('keeps row service context for merged issue links and bulk updates', async () => {
    const workerProject = {
      ...mockProject,
      id: 2,
      resourceId: 'proj-2',
      name: 'Worker Service',
      slug: 'worker-service',
    }
    const workerIssue = {
      ...mockIssues[0],
      id: 'issue-worker',
      title: 'Worker panic',
      culprit: 'worker.handler',
    }
    mockApi.getProjects.mockResolvedValue([mockProject, workerProject])
    mockApi.getIssues.mockImplementation((projectId: string) =>
      Promise.resolve(projectId === 'proj-2' ? [workerIssue] : [mockIssues[0]])
    )

    renderRoute(IssuesIndexRoute)

    fireEvent.click(await screen.findByText('Clear all'))
    const workerTitle = await screen.findByText(/worker.handler: Worker panic/)
    expect(workerTitle.closest('a')).toHaveAttribute('data-search', '{"projectId":"proj-2"}')

    fireEvent.click(screen.getByLabelText('Select Worker panic'))
    fireEvent.pointerDown(screen.getByRole('button', { name: /Actions/ }))
    fireEvent.click(await screen.findByText('Resolve'))

    await waitFor(() => {
      expect(mockApi.updateIssue).toHaveBeenCalledWith(
        'issue-worker',
        { status: 'resolved' },
        'proj-2'
      )
    })
  })

  it('shows no issues match filters when search has no results', async () => {
    renderRoute(IssuesIndexRoute)

    await screen.findByText(/app.main: TypeError: null ref/)
    const searchInput = screen.getByRole('textbox')
    fireEvent.change(searchInput, { target: { value: 'nonexistent-query' } })
    fireEvent.keyDown(searchInput, { key: 'Enter' })

    expect(screen.getByText('No issues match your filters')).toBeInTheDocument()
  })

  it('shows empty state when issues list is empty for default filter', async () => {
    mockApi.getIssues.mockResolvedValue([])

    renderRoute(IssuesIndexRoute)

    expect(await screen.findByText('No issues yet')).toBeInTheDocument()
  })

  it('switches to APM Errors tab', async () => {
    renderRoute(IssuesIndexRoute)

    await screen.findByRole('textbox')
    const apmTab = screen.getByText('APM Errors')
    fireEvent.click(apmTab)

    expect(await screen.findByText('No APM errors found')).toBeInTheDocument()
    expect(mockApi.getApmErrors).toHaveBeenCalledTimes(1)
    expect(mockApi.getApmErrors).toHaveBeenCalledWith({
      services: undefined,
      limit: 0,
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
      serviceFacets: [
        { service: 'api-gateway', count: 35 },
        { service: 'worker', count: 5 },
      ],
    })

    localStorage.clear()
    localStorage.setItem('apmErrors.facetFilters', JSON.stringify([{ key: 'service', value: 'api-gateway' }]))
    renderRoute(IssuesIndexRoute)

    await screen.findByRole('textbox')
    fireEvent.click(screen.getByText('APM Errors'))

    // Seed the selected service via its persisted slice (the rail/bar selection),
    // which loads its errors.
    expect(await screen.findByText('Connection refused')).toBeInTheDocument()
    expect(screen.getByText('NetworkError')).toBeInTheDocument()
    expect(screen.getByText('View trace')).toBeInTheDocument()
  })

  it('does not show create or settings affordances in the header', async () => {
    renderRoute(IssuesIndexRoute)

    await screen.findByRole('textbox')
    // Creation/config moved to the sidebar + Configuration page.
    expect(screen.queryByText('New Project')).not.toBeInTheDocument()
    expect(screen.queryByText('New Service')).not.toBeInTheDocument()
    expect(screen.queryByLabelText('Project settings')).not.toBeInTheDocument()
  })
})
