import React from 'react'
import { beforeEach, describe, expect, it, vi } from 'vitest'
import { screen, fireEvent, waitFor } from '@testing-library/react'
import { renderRoute, clearAuthStorage } from '@/test/utils'

const { mockNavigate, mockToast, mockApi, mockSearch } = vi.hoisted(() => ({
  mockNavigate: vi.fn(),
  mockToast: vi.fn(),
  // Mutable container so each test can drive what the route's `useSearch` returns.
  mockSearch: { value: {} as Record<string, unknown> },
  mockApi: {
    isAuthenticated: vi.fn(),
    checkAuth: vi.fn(),
    getProjects: vi.fn(),
    getOrganizationIssues: vi.fn(),
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
    useSearch: () => mockSearch.value,
    useNavigate: () => mockNavigate,
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

import { Route as IssuesIndexRoute, resolveIssueFacetFilters } from '../issues.index'

const UNRESOLVED_FACETS = [{ key: 'status', value: 'unresolved' }]
const STATUS_ALL_SEARCH = { status: 'all' }

/** Pull the last search payload navigate() was called with, resolved to an object. */
function lastNavigatedSearch(): Record<string, unknown> | undefined {
  const call = mockNavigate.mock.calls.at(-1)
  if (!call) return undefined
  const arg = call[0] as { search?: unknown } | undefined
  const search = arg?.search
  return typeof search === 'function'
    ? (search as (prev: Record<string, unknown>) => Record<string, unknown>)({})
    : (search as Record<string, unknown> | undefined)
}

const mockProject = {
  id: 'proj-1',
  name: 'Test Service',
  slug: 'test-project',
  platform: 'javascript',
}

const mockIssues = [
  {
    id: 'issue-1',
    projectId: 'proj-1',
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
    projectId: 'proj-1',
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
    projectId: 'proj-1',
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
    projectId: 'proj-1',
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
    projectId: 'proj-1',
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
    // Default to a fresh visit (no facets param) so the unresolved default applies.
    mockSearch.value = {}
    mockApi.isAuthenticated.mockReturnValue(true)
    mockApi.checkAuth.mockResolvedValue(true)
    mockApi.getProjects.mockResolvedValue([mockProject])
    mockApi.getOrganizationIssues.mockResolvedValue(mockIssues)
    mockApi.getIssues.mockResolvedValue(mockIssues)
    mockApi.getProjectStats.mockResolvedValue(null)
    mockApi.updateIssue.mockResolvedValue(undefined)
    mockApi.getApmErrors.mockResolvedValue({ errors: [], totalCount: 0, serviceFacets: [] })
  })

  it('renders issues list with projects and issues data', async () => {
    // Explicitly-empty facets = the user cleared the default, so all statuses show.
    mockSearch.value = STATUS_ALL_SEARCH
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
    expect(mockApi.getOrganizationIssues).toHaveBeenCalledWith({
      page: 1,
      limit: 500,
      status: undefined,
      services: undefined,
    })
    expect(mockApi.getIssues).not.toHaveBeenCalled()
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
    mockSearch.value = STATUS_ALL_SEARCH
    renderRoute(IssuesIndexRoute)

    await screen.findByText(/app.main: TypeError: null ref/)
    expect(screen.getByText('5 results')).toBeInTheDocument()

    // Include a Status facet from the rail → client-side filter to that status.
    fireEvent.click(screen.getByRole('button', { name: 'Resolved' }))
    await waitFor(() => {
      expect(screen.getByText('1 result')).toBeInTheDocument()
    })
    expect(mockApi.getOrganizationIssues).toHaveBeenLastCalledWith({
      page: 1,
      limit: 500,
      status: 'resolved',
      services: undefined,
    })

    // Toggling it off clears the filter — the last selection is removable.
    fireEvent.click(screen.getByRole('button', { name: 'Resolved' }))
    await waitFor(() => {
      expect(screen.getByText('5 results')).toBeInTheDocument()
    })
  })

  it('keeps row service context for org-wide issue links and bulk updates', async () => {
    const workerProject = {
      ...mockProject,
      id: 'proj-2',
      name: 'Worker Service',
      slug: 'worker-service',
    }
    const workerIssue = {
      ...mockIssues[0],
      id: 'issue-worker',
      projectId: 'proj-2',
      title: 'Worker panic',
      culprit: 'worker.handler',
    }
    mockApi.getProjects.mockResolvedValue([mockProject, workerProject])
    mockApi.getOrganizationIssues.mockResolvedValue([mockIssues[0], workerIssue])

    renderRoute(IssuesIndexRoute)

    const workerTitle = await screen.findByText(/worker.handler: Worker panic/)
    expect(workerTitle.closest('a')).toHaveAttribute('data-search', '{"projectId":"proj-2"}')
    expect(mockApi.getIssues).not.toHaveBeenCalled()

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

  it('shows the no-issues-yet empty state when there are no issues and no active filters', async () => {
    mockSearch.value = STATUS_ALL_SEARCH
    mockApi.getOrganizationIssues.mockResolvedValue([])

    renderRoute(IssuesIndexRoute)

    expect(await screen.findByText('No issues yet')).toBeInTheDocument()
  })

  it('passes selected services to the org-wide issues API', async () => {
    const workerProject = {
      ...mockProject,
      id: 'proj-2',
      name: 'Worker Service',
      slug: 'worker-service',
    }
    mockApi.getProjects.mockResolvedValue([mockProject, workerProject])
    mockSearch.value = STATUS_ALL_SEARCH

    renderRoute(IssuesIndexRoute)

    await screen.findByText(/app.main: TypeError: null ref/)
    fireEvent.click(screen.getByRole('button', { name: 'Worker Service' }))

    await waitFor(() => {
      expect(mockApi.getOrganizationIssues).toHaveBeenLastCalledWith({
        page: 1,
        limit: 500,
        status: undefined,
        services: ['Worker Service'],
      })
    })
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

    // Seed the active tab + APM service facet straight from the URL (APM now
    // persists in readable aq/apm_* params, not localStorage).
    mockSearch.value = {
      view: 'apm-errors',
      apm_service: 'api-gateway',
    }
    renderRoute(IssuesIndexRoute)

    expect(await screen.findByText('Connection refused')).toBeInTheDocument()
    expect(screen.getByText('NetworkError')).toBeInTheDocument()
    expect(screen.getByText('View trace')).toBeInTheDocument()
    expect(mockApi.getApmErrors).toHaveBeenCalledWith(
      expect.objectContaining({ services: ['api-gateway'] })
    )
  })

  it('does not show create or settings affordances in the header', async () => {
    renderRoute(IssuesIndexRoute)

    await screen.findByRole('textbox')
    // Creation/config moved to the sidebar + Setup page.
    expect(screen.queryByText('New Service')).not.toBeInTheDocument()
    expect(screen.queryByText('New Project')).not.toBeInTheDocument()
    expect(screen.queryByLabelText('Service settings')).not.toBeInTheDocument()
  })

  describe('facet URL state', () => {
    it('resolveIssueFacetFilters defaults a fresh visit to unresolved but honours an explicit clear', () => {
      expect(resolveIssueFacetFilters(undefined)).toEqual([{ key: 'status', value: 'unresolved' }])
      expect(resolveIssueFacetFilters(STATUS_ALL_SEARCH)).toEqual([])
      expect(resolveIssueFacetFilters({service: 'api'})).toEqual([
        { key: 'service', value: 'api' },
      ])
    })

    it('normalises legacy facets search params into readable issue params', () => {
      const validateSearch = IssuesIndexRoute.options.validateSearch as (search: Record<string, unknown>) => unknown
      expect(validateSearch({facets: '[{"key":"status","value":"resolved"}]'})).toEqual({status: 'resolved'})
      expect(validateSearch({facets: '[]'})).toEqual({status: 'all'})
      expect(validateSearch({facets: JSON.stringify(JSON.stringify(UNRESOLVED_FACETS))})).toEqual({
        status: 'unresolved',
      })
    })

    it('applies the unresolved default and normalises a fresh URL to carry it', async () => {
      // mockSearch.value defaults to {} (no facets param) in beforeEach.
      renderRoute(IssuesIndexRoute)

      // Only the two unresolved issues survive the default client-side filter.
      expect(await screen.findByText(/app.main: TypeError: null ref/)).toBeInTheDocument()
      expect(screen.queryByText(/api.handler: ValueError: invalid input/)).not.toBeInTheDocument()
      expect(screen.getByText('2 results')).toBeInTheDocument()

      // The unresolved status drives the org issue request...
      expect(mockApi.getOrganizationIssues).toHaveBeenCalledWith({
        page: 1,
        limit: 500,
        status: 'unresolved',
        services: undefined,
      })

      // ...and the URL is normalised so the default is shareable.
      await waitFor(() => {
        expect(mockNavigate).toHaveBeenCalled()
      })
      expect(lastNavigatedSearch()).toEqual({ q: undefined, status: 'unresolved' })
    })

    it('restores facet state from the URL without re-normalising', async () => {
      mockSearch.value = { status: 'resolved' }
      renderRoute(IssuesIndexRoute)

      expect(await screen.findByText(/api.handler: ValueError: invalid input/)).toBeInTheDocument()
      expect(screen.queryByText(/app.main: TypeError: null ref/)).not.toBeInTheDocument()
      expect(screen.getByText('1 result')).toBeInTheDocument()
      expect(mockApi.getOrganizationIssues).toHaveBeenCalledWith({
        page: 1,
        limit: 500,
        status: 'resolved',
        services: undefined,
      })

      // Already in sync with the URL → no redundant navigation.
      expect(mockNavigate).not.toHaveBeenCalled()
    })

    it('writes facet selections back to the URL', async () => {
      mockSearch.value = STATUS_ALL_SEARCH
      renderRoute(IssuesIndexRoute)

      await screen.findByText(/app.main: TypeError: null ref/)
      fireEvent.click(screen.getByRole('button', { name: 'Resolved' }))

      await waitFor(() => {
        expect(lastNavigatedSearch()).toEqual({
          q: undefined,
          status: 'resolved',
        })
      })
    })

    it('records an explicit empty facets param when the default is cleared', async () => {
      // Fresh visit → unresolved default; toggling it off must not silently
      // resurrect on reload, so the URL gets an explicit "[]".
      renderRoute(IssuesIndexRoute)

      await screen.findByText(/app.main: TypeError: null ref/)
      fireEvent.click(screen.getByRole('button', { name: 'Unresolved' }))

      await waitFor(() => {
        expect(mockApi.getOrganizationIssues).toHaveBeenLastCalledWith({
          page: 1,
          limit: 500,
          status: undefined,
          services: undefined,
        })
      })
      expect(await screen.findByText('5 results')).toBeInTheDocument()
      expect(lastNavigatedSearch()).toEqual({ q: undefined, status: 'all' })
    })
  })

  describe('tab URL state', () => {
    it('persists the active tab in the URL', async () => {
      renderRoute(IssuesIndexRoute)

      await screen.findByRole('textbox')
      fireEvent.click(screen.getByText('APM Errors'))

      expect(await screen.findByText('No APM errors found')).toBeInTheDocument()
      expect(lastNavigatedSearch()).toEqual({ view: 'apm-errors' })
    })

    it('restores the active tab from the URL', async () => {
      mockSearch.value = { view: 'apm-errors' }
      renderRoute(IssuesIndexRoute)

      // Lands on APM Errors without a click, and never queries the issues list.
      expect(await screen.findByText('No APM errors found')).toBeInTheDocument()
      expect(mockApi.getOrganizationIssues).not.toHaveBeenCalled()
    })
  })

  describe('APM facet URL state', () => {
    it('writes APM facet selections to its own namespaced URL params', async () => {
      mockApi.getApmErrors.mockResolvedValue({
        errors: [],
        totalCount: 0,
        serviceFacets: [{ service: 'api-gateway', count: 35 }],
      })
      mockSearch.value = { view: 'apm-errors' }
      renderRoute(IssuesIndexRoute)

      // The rail offers services from the facet counts; selecting one writes apm_service.
      fireEvent.click(await screen.findByLabelText('api-gateway'))

      await waitFor(() => {
        expect(lastNavigatedSearch()).toEqual({
          aq: undefined,
          apm_service: 'api-gateway',
        })
      })
    })

    it('keeps Issues and APM facets separate — no cross-tab leakage', async () => {
      mockApi.getApmErrors.mockResolvedValue({ errors: [], totalCount: 0, serviceFacets: [] })
      // Issues default (unresolved) lives in q/facets; APM must ignore it.
      mockSearch.value = { view: 'apm-errors', status: 'unresolved' }
      renderRoute(IssuesIndexRoute)

      await screen.findByText('No APM errors found')
      expect(screen.queryByText('status:unresolved')).not.toBeInTheDocument()
      expect(mockApi.getApmErrors).toHaveBeenCalledWith(
        expect.objectContaining({ services: undefined })
      )
    })
  })
})
