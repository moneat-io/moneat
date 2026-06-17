import React from 'react'
import {beforeEach, describe, expect, it, vi} from 'vitest'
import {fireEvent, screen, waitFor} from '@testing-library/react'
import {clearAuthStorage, renderRoute} from '@/test/utils'

const {mockApi, mockRouteParams} = vi.hoisted(() => ({
  mockApi: {
    isAuthenticated: vi.fn(),
    checkAuth: vi.fn(),
    getCurrentUser: vi.fn(),
    updateUserTimezone: vi.fn(),
    getProjects: vi.fn(),
    getLlmOverview: vi.fn(),
    getLlmGenerations: vi.fn(),
    getLlmGenerationDetail: vi.fn(),
    getLlmTrace: vi.fn(),
  },
  mockRouteParams: {
    current: {traceId: 'trace-1'},
  },
}))

vi.mock('@/lib/api', () => ({
  api: mockApi,
}))

vi.mock('@/hooks/useTimezone', () => ({
  useTimezone: () => ({timezone: 'UTC'}),
}))

vi.mock('@/components/charts/StatsCard', () => ({
  StatsCard: ({title, value}: {title: string; value: string}) => <div>{title}:{value}</div>,
}))

vi.mock('@/components/charts/EventsChart', () => ({
  EventsChart: ({title}: {title: string}) => <div>{title}</div>,
}))

vi.mock('@/components/charts/BarChart', () => ({
  BarChart: ({title}: {title: string}) => <div>{title}</div>,
}))

vi.mock('@/components/icons/AiProviders', () => ({
  ProviderLogo: () => <div>provider-logo</div>,
}))

vi.mock('@tanstack/react-router', () => ({
  createFileRoute: () => (options: Record<string, unknown>) => ({
    ...options,
    options,
    useParams: () => mockRouteParams.current,
    useSearch: () => ({}),
  }),
  Link: ({children, ...props}: {children: React.ReactNode}) => React.createElement('a', props, children),
  Outlet: () => null,
  redirect: (opts: Record<string, unknown>) => ({...opts, __redirect: true}),
  useMatches: () => [],
}))

import {Route as AiIndexRoute} from '../ai.index'
import {Route as AiGenerationsRoute} from '../ai.generations'
import {Route as AiTraceRoute} from '../ai.traces.$traceId'

const mockProjects = [
  {
    id: 'svc-api',
    name: 'API Service',
    slug: 'api-service',
    platform: 'node',
    keys: [],
    dsn: 'https://public@example.com/api/1',
  },
  {
    id: 'svc-worker',
    name: 'Worker Service',
    slug: 'worker-service',
    platform: 'node',
    keys: [],
    dsn: 'https://worker@example.com/api/2',
  },
]

const overview = {
  totalGenerations: 12,
  totalTokens: 3456,
  totalCost: 1.23,
  avgDurationMs: 456,
  errorRate: 2.5,
  timeline: [{timestamp: '2026-06-01T00:00:00.000Z', count: 12, tokens: 3456, cost: 1.23, errors: 1}],
  topModels: [
    {
      model: 'gpt-4',
      provider: 'openai',
      callCount: 12,
      totalTokens: 3456,
      totalCost: 1.23,
      avgDurationMs: 456,
      errorRate: 2.5,
    },
  ],
}

const generation = {
  generationId: 'gen-1',
  traceId: 'trace-1',
  spanId: 'span-1',
  parentSpanId: '',
  timestamp: '2026-06-01T00:00:00.000Z',
  durationMs: 456,
  name: 'chat completion',
  model: 'gpt-4',
  provider: 'openai',
  type: 'chat',
  inputTokens: 10,
  outputTokens: 20,
  totalTokens: 30,
  costUsd: 0.01,
  status: 'success',
  errorMessage: '',
  userId: 'user-1',
  environment: 'prod',
  release: '1.0.0',
}

const generationDetail = {
  ...generation,
  input: '{}',
  output: '{}',
  temperature: 0,
  maxTokens: 100,
  topP: 1,
  statusCode: 200,
  sessionId: 'session-1',
  tags: {},
  metadata: '{}',
}

describe('AI service facets', () => {
  beforeEach(() => {
    vi.clearAllMocks()
    clearAuthStorage()
    mockRouteParams.current = {traceId: 'trace-1'}
    mockApi.isAuthenticated.mockReturnValue(true)
    mockApi.checkAuth.mockResolvedValue(true)
    mockApi.getCurrentUser.mockResolvedValue(null)
    mockApi.updateUserTimezone.mockResolvedValue(undefined)
    mockApi.getProjects.mockResolvedValue(mockProjects)
    mockApi.getLlmOverview.mockResolvedValue(overview)
    mockApi.getLlmGenerations.mockResolvedValue({
      generations: [generation],
      total: 1,
      page: 1,
      pageSize: 25,
    })
    mockApi.getLlmGenerationDetail.mockResolvedValue(generationDetail)
    mockApi.getLlmTrace.mockResolvedValue({
      traceId: 'trace-1',
      generations: [generationDetail],
      totalDurationMs: 456,
      totalTokens: 30,
      totalCost: 0.01,
    })
  })

  it('loads AI overview organization-wide by default', async () => {
    renderRoute(AiIndexRoute)

    expect(await screen.findByText('AI Observability')).toBeInTheDocument()
    expect(mockApi.getLlmOverview).toHaveBeenCalledWith({range: '24h'})
  })

  it('adds selected services to overview calls', async () => {
    renderRoute(AiIndexRoute)

    await screen.findByText('AI Observability')
    fireEvent.click(screen.getByRole('checkbox', {name: 'Worker Service'}))

    await waitFor(() => {
      expect(mockApi.getLlmOverview).toHaveBeenLastCalledWith({
        range: '24h',
        services: ['Worker Service'],
      })
    })
  })

  it('loads generations with service facet filters', async () => {
    renderRoute(AiGenerationsRoute)

    expect(await screen.findByText('chat completion')).toBeInTheDocument()
    expect(mockApi.getLlmGenerations).toHaveBeenCalledWith(expect.objectContaining({
      range: '24h',
      page: 1,
      pageSize: 25,
    }))
    expect(mockApi.getLlmGenerations.mock.calls[0][0].services).toBeUndefined()

    fireEvent.click(screen.getByRole('checkbox', {name: 'Worker Service'}))

    await waitFor(() => {
      expect(mockApi.getLlmGenerations).toHaveBeenLastCalledWith(expect.objectContaining({
        services: ['Worker Service'],
      }))
    })
  })

  it('renders no-services state without AI queries', async () => {
    mockApi.getProjects.mockResolvedValue([])

    renderRoute(AiIndexRoute)

    expect(await screen.findByText('No services yet')).toBeInTheDocument()
    expect(mockApi.getLlmOverview).not.toHaveBeenCalled()
  })

  it('loads traces organization-wide by trace id', async () => {
    renderRoute(AiTraceRoute)

    expect(await screen.findByText('Span Waterfall')).toBeInTheDocument()
    expect(mockApi.getLlmTrace).toHaveBeenCalledWith('trace-1')
  })
})
