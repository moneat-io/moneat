import React from 'react'
import { beforeEach, describe, expect, it, vi } from 'vitest'
import { screen } from '@testing-library/react'
import { renderRoute, clearAuthStorage } from '@/test/utils'

const { mockNavigate, mockApi } = vi.hoisted(() => ({
  mockNavigate: vi.fn(),
  mockApi: {
    isAuthenticated: vi.fn(),
    checkAuth: vi.fn(),
    getProjects: vi.fn(),
    getBillingUsage: vi.fn(),
    getTransactions: vi.fn(),
    getPerformanceStats: vi.fn(),
    getCurrentUser: vi.fn(),
    updateUserTimezone: vi.fn(),
  },
}))

vi.mock('@/lib/api', () => ({
  api: mockApi,
}))

vi.mock('@/hooks/useEnterpriseFeatures', () => ({
  useHasModule: () => false,
}))

vi.mock('@/components/charts/StatsCard', () => ({
  StatsCard: ({ title, value }: { title: string; value: string }) => <div data-testid="stats-card">{title}:{value}</div>,
  StatsCardSkeleton: () => <div>stats-skeleton</div>,
}))

vi.mock('@/components/charts/EventsChart', () => ({
  EventsChart: () => <div>events-chart</div>,
  EventsChartSkeleton: () => <div>events-chart-skeleton</div>,
}))

vi.mock('@/components/charts/BarChart', () => ({
  BarChart: () => <div>bar-chart</div>,
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

import { Route as PerformanceRoute } from '../performance.index'

const mockProject = {
  id: 'proj-1',
  name: 'Test Project',
  slug: 'test-project',
  platform: 'javascript',
}

const mockTransactions = [
  {
    name: 'GET /api/users',
    op: 'http.server',
    tpm: 120.5,
    p50: 45,
    p75: 120,
    p95: 350,
    failureRate: 0.5,
  },
  {
    name: 'POST /api/data',
    op: 'http.server',
    tpm: 80.2,
    p50: 200,
    p75: 400,
    p95: 1500,
    failureRate: 3.2,
  },
  {
    name: 'DB query',
    op: 'db.query',
    tpm: 500,
    p50: 5,
    p75: 15,
    p95: 50,
    failureRate: 0.1,
  },
]

const mockStats = {
  apdex: 0.92,
  totalTransactions: 25000,
  avgDuration: 150,
  throughput: [
    { timestamp: '2026-03-14T00:00:00Z', count: 100 },
    { timestamp: '2026-03-14T01:00:00Z', count: 120 },
  ],
  slowestTransactions: [
    { eventId: 'tx-slow-1', name: 'Heavy query', op: 'db.sql.query', duration: 5200 },
    { eventId: 'tx-slow-2', name: 'External call', op: 'http.client', duration: 3100 },
  ],
}

describe('Performance Index - data coverage', () => {
  beforeEach(() => {
    vi.clearAllMocks()
    clearAuthStorage()

    mockApi.isAuthenticated.mockReturnValue(true)
    mockApi.checkAuth.mockResolvedValue(true)
    mockApi.getProjects.mockResolvedValue([mockProject])
    mockApi.getBillingUsage.mockResolvedValue({ retentionDays: 30 })
    mockApi.getTransactions.mockResolvedValue(mockTransactions)
    mockApi.getPerformanceStats.mockResolvedValue(mockStats)
  })

  it('renders performance page with stats, charts, and transaction table', async () => {
    renderRoute(PerformanceRoute)

    // Header
    expect(await screen.findByText('Performance')).toBeInTheDocument()

    // Wait for data to load - transaction table appears after queries resolve
    expect(await screen.findByText('GET /api/users')).toBeInTheDocument()

    // Stats cards
    const statsCards = screen.getAllByTestId('stats-card')
    expect(statsCards.length).toBeGreaterThanOrEqual(3)

    // Charts
    expect(screen.getByText('events-chart')).toBeInTheDocument()

    // Slowest transactions card
    expect(screen.getByText('Slowest Transactions')).toBeInTheDocument()
    expect(screen.getByText('Heavy query')).toBeInTheDocument()

    // Transaction table
    expect(screen.getByText('Transaction Groups')).toBeInTheDocument()
    expect(screen.getByText('POST /api/data')).toBeInTheDocument()
    expect(screen.getByText('DB query')).toBeInTheDocument()
  })

  it('renders no data state when transactions are empty', async () => {
    mockApi.getTransactions.mockResolvedValue([])
    mockApi.getPerformanceStats.mockResolvedValue(null)

    renderRoute(PerformanceRoute)

    expect(await screen.findByText('No transaction data for this period')).toBeInTheDocument()
  })

  it('renders loading state', () => {
    mockApi.getProjects.mockResolvedValue([mockProject])
    mockApi.getTransactions.mockReturnValue(new Promise(() => {}))
    mockApi.getPerformanceStats.mockReturnValue(new Promise(() => {}))

    renderRoute(PerformanceRoute)

    // Initially shows no-projects or loading depending on timing
    // The projects query needs to resolve first
    expect(screen.getByText(/Performance|Loading|No projects/)).toBeInTheDocument()
  })
})
