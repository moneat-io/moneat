import React from 'react'
import { beforeEach, describe, expect, it, vi } from 'vitest'
import { QueryClient, QueryClientProvider } from '@tanstack/react-query'
import { render, screen } from '@testing-library/react'
import { ProjectProvider } from '@/contexts/project-context'

const { mockNavigate, mockToast, mockApi } = vi.hoisted(() => ({
  mockNavigate: vi.fn(),
  mockToast: vi.fn(),
  mockApi: {
    isAuthenticated: vi.fn(),
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

vi.mock('@/hooks/use-toast', () => ({
  useToast: () => ({ toast: mockToast }),
}))

vi.mock('@/components/charts/stats-card', () => ({
  StatsCard: ({ title, value }: { title: string; value: string }) => <div>{title}:{value}</div>,
  StatsCardSkeleton: () => <div>stats-skeleton</div>,
}))

vi.mock('@/components/charts/events-chart', () => ({
  EventsChart: () => <div>events-chart</div>,
  EventsChartSkeleton: () => <div>events-chart-skeleton</div>,
}))

vi.mock('@/components/charts/bar-chart', () => ({
  BarChart: () => <div>bar-chart</div>,
}))

vi.mock('@/components/span-waterfall', () => ({
  SpanWaterfall: () => <div>span-waterfall</div>,
}))

vi.mock('@/components/logs/EmbeddedLogs', () => ({
  EmbeddedLogs: () => <div>embedded-logs</div>,
}))

vi.mock('@/components/icons/ai-providers', () => ({
  ProviderLogo: () => <div>provider-logo</div>,
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
      <ProjectProvider>
        <Component />
      </ProjectProvider>
    </QueryClientProvider>
  )
}

describe('priority route coverage', () => {
  beforeEach(() => {
    vi.clearAllMocks()
    localStorage.clear()

    mockApi.isAuthenticated.mockReturnValue(true)
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

  it('issues and issue detail route guards redirect unauthenticated users', async () => {
    mockApi.isAuthenticated.mockReturnValue(false)

    await expect((IssuesIndexRoute as { beforeLoad: () => Promise<unknown> }).beforeLoad()).rejects.toMatchObject({
      to: '/login',
    })

    expect(() => (IssueDetailRoute as { beforeLoad: () => void }).beforeLoad()).toThrow()
  })

  it('issues route renders empty project state', async () => {
    const Component = (IssuesIndexRoute as { component: React.ComponentType }).component
    renderRoute(Component)

    expect(await screen.findByText('No projects yet')).toBeInTheDocument()
    expect(mockApi.getProjects).toHaveBeenCalled()
  })

  it('issue detail route renders not found state when issue is missing', async () => {
    const Component = (IssueDetailRoute as { component: React.ComponentType }).component
    renderRoute(Component)

    expect(await screen.findByText('Issue not found')).toBeInTheDocument()
    expect(mockApi.getIssue).toHaveBeenCalledWith('issue-123')
  })

  it('performance route renders no projects message when there are no projects', async () => {
    const Component = (PerformanceRoute as { component: React.ComponentType }).component
    renderRoute(Component)

    expect(await screen.findByText('No projects yet. Create a project to view performance data.')).toBeInTheDocument()
  })

  it('ai route prompts for project selection when no project is selected', () => {
    const Component = (AiRoute as { component: React.ComponentType }).component
    renderRoute(Component)

    expect(screen.getByText('Select a project to view AI observability data.')).toBeInTheDocument()
  })
})
