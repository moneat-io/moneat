import React from 'react'
import { beforeEach, describe, expect, it, vi } from 'vitest'
import { QueryClient, QueryClientProvider } from '@tanstack/react-query'
import { render, screen } from '@testing-library/react'

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
    getBillingUsage: vi.fn(),
    getTransactions: vi.fn(),
    getPerformanceStats: vi.fn(),
    getLlmOverview: vi.fn(),
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

vi.mock('@/components/charts/StatsCard', () => ({
  StatsCard: ({ title, value }: { title: string; value: string }) => <div>{title}:{value}</div>,
  StatsCardSkeleton: () => <div>stats-skeleton</div>,
}))

vi.mock('@/components/charts/EventsChart', () => ({
  EventsChart: () => <div>events-chart</div>,
  EventsChartSkeleton: () => <div>events-chart-skeleton</div>,
}))

vi.mock('@/components/charts/BarChart', () => ({
  BarChart: () => <div>bar-chart</div>,
}))

vi.mock('@/components/SpanWaterfall', () => ({
  SpanWaterfall: () => <div>span-waterfall</div>,
}))

vi.mock('@/components/logs/EmbeddedLogs', () => ({
  EmbeddedLogs: () => <div>embedded-logs</div>,
}))

vi.mock('@/components/icons/AiProviders', () => ({
  ProviderLogo: () => <div>provider-logo</div>,
}))

vi.mock('@tanstack/react-router', () => ({
  createFileRoute: () => (options: Record<string, unknown>) => ({
    ...options,
    options,
    useParams: () => ({ issueId: 'issue-123' }),
    useSearch: () => ({}),
    useNavigate: () => mockNavigate,
  }),
  Link: ({ children, ...props }: { children: React.ReactNode }) => React.createElement('a', props, children),
  redirect: (opts: Record<string, unknown>) => ({ ...opts, __redirect: true }),
  useNavigate: () => mockNavigate,
  useMatches: () => [],
  Outlet: () => null,
}))

import { Route as IssuesIndexRoute } from '../issues.index'
import { Route as IssueDetailRoute } from '../issues.$issueId'
import { Route as PerformanceRoute } from '../performance.index'
import { Route as AiRoute } from '../ai.index'

function renderRoute(Component: React.ComponentType) {
  const queryClient = new QueryClient({
    defaultOptions: {
      queries: { retry: false },
      mutations: { retry: false },
    },
  })

  return render(
    <QueryClientProvider client={queryClient}>
      <Component />
    </QueryClientProvider>
  )
}

describe('priority route coverage', () => {
  beforeEach(() => {
    vi.clearAllMocks()
    localStorage.clear()

    mockApi.isAuthenticated.mockReturnValue(true)
    mockApi.checkAuth.mockResolvedValue(true)
    mockApi.getProjects.mockResolvedValue([])
    mockApi.getIssues.mockResolvedValue([])
    mockApi.getProjectStats.mockResolvedValue(null)
    mockApi.updateIssue.mockResolvedValue(undefined)
    mockApi.getIssue.mockResolvedValue(null)
    mockApi.getIssueEvents.mockResolvedValue([])
    mockApi.getIssueTransactions.mockResolvedValue([])
    mockApi.getReplaysForIssue.mockResolvedValue([])
    mockApi.getTransactionSpans.mockResolvedValue([])
    mockApi.getBillingUsage.mockResolvedValue({ retentionDays: 30 })
    mockApi.getTransactions.mockResolvedValue([])
    mockApi.getPerformanceStats.mockResolvedValue(null)
    mockApi.getLlmOverview.mockResolvedValue({
      totalGenerations: 0,
      totalTokens: 0,
      totalCost: 0,
      avgDurationMs: 0,
      errorRate: 0,
      timeline: [],
      topModels: [],
    })
  })

  it('issues routes have no beforeLoad guards (auth handled by root)', () => {
    expect((IssuesIndexRoute as { beforeLoad?: unknown }).beforeLoad).toBeUndefined()
    expect((IssueDetailRoute as { beforeLoad?: unknown }).beforeLoad).toBeUndefined()
  })

  it('issues route renders empty service state', async () => {
    const Component = (IssuesIndexRoute as unknown as { component: React.ComponentType }).component
    renderRoute(Component)

    expect(await screen.findByText('No services yet')).toBeInTheDocument()
    expect(mockApi.getProjects).toHaveBeenCalled()
  })

  it('issue detail route renders not found state when issue is missing', async () => {
    const Component = (IssueDetailRoute as unknown as { component: React.ComponentType }).component
    renderRoute(Component)

    expect(await screen.findByText('Issue not found')).toBeInTheDocument()
    expect(mockApi.getIssue).toHaveBeenCalledWith('issue-123', undefined)
  })

  it('performance route redirects to traces', async () => {
    const beforeLoad = (PerformanceRoute as unknown as { beforeLoad: () => Promise<void> }).beforeLoad

    await expect(beforeLoad()).rejects.toMatchObject({
      __redirect: true,
      to: '/performance/traces',
    })
  })

  it('ai route renders empty service state without global service selection', async () => {
    const Component = (AiRoute as unknown as { component: React.ComponentType }).component
    renderRoute(Component)

    expect(await screen.findByText('No services yet')).toBeInTheDocument()
  })
})
