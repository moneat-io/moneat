import React from 'react'
import {beforeEach, describe, expect, it, vi} from 'vitest'
import {fireEvent, screen} from '@testing-library/react'
import {clearAuthStorage, renderRoute} from '@/test/utils'

const {mockApi, mockRouteParams} = vi.hoisted(() => ({
  mockApi: {
    isAuthenticated: vi.fn(),
    getReplay: vi.fn(),
    getReplayRecording: vi.fn(),
    getReplayTimeline: vi.fn(),
  },
  mockRouteParams: {current: {replayId: 'replay-1'}},
}))

vi.mock('@/lib/api', () => ({
  api: mockApi,
  formatErrorForLogging: (error: unknown) => String(error),
}))

vi.mock('@/hooks/useTimezone', () => ({useTimezone: () => ({timezone: 'UTC'})}))

vi.mock('@tanstack/react-router', () => ({
  createFileRoute: () => (options: Record<string, unknown>) => ({
    ...options,
    options,
    useParams: () => mockRouteParams.current,
    useSearch: () => ({}),
  }),
  Link: ({children}: {children: React.ReactNode}) => <a>{children}</a>,
  redirect: (opts: Record<string, unknown>) => ({...opts, __redirect: true}),
}))

// Stub the heavy player/canvas children so the test renders the route's layout
// without pulling in rrweb / the mobile video pipeline.
vi.mock('@/components/ReplayPlayer', () => ({ReplayPlayer: () => <div>player</div>}))
vi.mock('@/components/MobileReplayViewer', () => ({MobileReplayViewer: () => <div>mobile-player</div>}))
vi.mock('@/components/ReplayTimelinePanel', () => ({ReplayTimelinePanel: () => <div>event-rail</div>}))
vi.mock('@/components/ReplayTimelineScrubber', () => ({ReplayTimelineScrubber: () => <div>scrubber</div>}))
vi.mock('@/components/replay-containers/BrowserWindowContainer', () => ({
  BrowserWindowContainer: ({children}: {children: React.ReactNode}) => <div>{children}</div>,
}))
vi.mock('@/components/replay-containers/MobileDeviceContainer', () => ({
  MobileDeviceContainer: ({children}: {children: React.ReactNode}) => <div>{children}</div>,
}))

import {Route as ReplayDetailRoute} from '../replays.$replayId'

const replayDetail = {
  replayId: 'replay-1',
  projectId: 1,
  projectResourceId: 'svc-api',
  startedAt: '2026-06-01T00:00:00.000Z',
  finishedAt: '2026-06-01T00:01:24.000Z',
  durationMs: 84_000,
  urls: ['https://app.example.com/checkout', 'https://app.example.com/cart'],
  errorCount: 2,
  user: {email: 'alice@example.com', id: 'usr_1'},
  browserName: 'Chrome',
  browserVersion: '126',
  osName: 'macOS',
  osVersion: '14',
  activity: 82,
  errorIds: ['issue-1'],
  traceIds: ['7b3e1f9a04c2d8e6'],
  segmentCount: 9,
  environment: 'production',
  release: 'storefront@4.18.2',
  platform: 'web',
  tags: {plan: 'pro'},
  ipAddress: '79.42.0.0',
  geo: 'Milan, IT',
  viewport: '1512 × 856',
  connection: '4g',
  userSessionCount: 14,
}

describe('replay detail (player-hero layout)', () => {
  beforeEach(() => {
    vi.clearAllMocks()
    clearAuthStorage()
    mockRouteParams.current = {replayId: 'replay-1'}
    mockApi.isAuthenticated.mockReturnValue(true)
    mockApi.getReplay.mockResolvedValue(replayDetail)
    mockApi.getReplayRecording.mockResolvedValue({events: []})
    mockApi.getReplayTimeline.mockResolvedValue({
      items: [
        {id: 't1', type: 'error', timestamp: '2026-06-01T00:00:24.000Z', offsetMs: 24_000, title: 'POST /checkout 500'},
      ],
      replayStartMs: 0,
    })
  })

  it('renders header, recording region, scrubber, and event rail', async () => {
    renderRoute(ReplayDetailRoute)

    expect((await screen.findAllByText('alice@example.com')).length).toBeGreaterThan(0)
    expect(screen.getByText('2 errors')).toBeInTheDocument()
    // recording region: no rrweb events seeded → placeholder; scrubber + rail mounted
    expect(screen.getByText('No recording data available for this replay')).toBeInTheDocument()
    expect(screen.getByText('scrubber')).toBeInTheDocument()
    expect(screen.getByText('event-rail')).toBeInTheDocument()
  })

  it('renders the browser replay player when rrweb events are available', async () => {
    mockApi.getReplayRecording.mockResolvedValue({
      events: [
        {type: 2, timestamp: 1_000},
        {type: 3, timestamp: 6_000},
      ],
    })

    renderRoute(ReplayDetailRoute)

    expect(await screen.findByText('player')).toBeInTheDocument()
  })

  it('renders the mobile replay viewer for native mobile sessions', async () => {
    mockApi.getReplay.mockResolvedValue({...replayDetail, platform: 'android'})
    mockApi.getReplayRecording.mockResolvedValue({events: []})

    renderRoute(ReplayDetailRoute)

    expect(await screen.findByText('mobile-player')).toBeInTheDocument()
  })

  it('reveals session detail cards (incl. backend-pending fields) on expand', async () => {
    renderRoute(ReplayDetailRoute)

    fireEvent.click(await screen.findByRole('button', {name: /Session Details/}))

    expect(await screen.findByText('storefront@4.18.2')).toBeInTheDocument()
    expect(screen.getByText('Milan, IT')).toBeInTheDocument()
    expect(screen.getByText('1512 × 856')).toBeInTheDocument()
    expect(screen.getByText('4g')).toBeInTheDocument()
    expect(screen.getByText('14 prior sessions')).toBeInTheDocument()
  })
})
