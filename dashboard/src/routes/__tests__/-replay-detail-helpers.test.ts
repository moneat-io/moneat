import {describe, expect, it, vi} from 'vitest'
import type {ReplayDetail} from '@/lib/api'

vi.mock('@tanstack/react-router', () => ({
  createFileRoute: () => (options: Record<string, unknown>) => options,
  Link: ({children}: {children: unknown}) => children,
}))

vi.mock('@/components/ReplayPlayer', () => ({
  ReplayPlayer: () => null,
}))

vi.mock('@/components/MobileReplayViewer', () => ({
  MobileReplayViewer: () => null,
}))

vi.mock('@/components/ReplayTimelinePanel', () => ({
  ReplayTimelinePanel: () => null,
}))

vi.mock('@/components/ReplayTimelineScrubber', () => ({
  ReplayTimelineScrubber: () => null,
}))

vi.mock('@/components/replay-containers/BrowserWindowContainer', () => ({
  BrowserWindowContainer: ({children}: {children: unknown}) => children,
}))

vi.mock('@/components/replay-containers/MobileDeviceContainer', () => ({
  MobileDeviceContainer: ({children}: {children: unknown}) => children,
}))

import {replayDetailHelperTestHooks as helpers} from '../replays.$replayId'

function replay(overrides: Partial<ReplayDetail> = {}): ReplayDetail {
  return {
    replayId: 'replay-1',
    projectId: 'svc-api',
    startedAt: '2026-06-01T00:00:00.000Z',
    finishedAt: '2026-06-01T00:01:00.000Z',
    durationMs: 60_000,
    urls: ['https://app.example.com'],
    errorCount: 0,
    activity: 100,
    errorIds: [],
    traceIds: [],
    segmentCount: 1,
    platform: 'javascript',
    tags: {},
    ...overrides,
  }
}

function stageProps(
  overrides: Partial<Parameters<typeof helpers.ReplayRecordingStage>[0]> = {}
): Parameters<typeof helpers.ReplayRecordingStage>[0] {
  return {
    recordingLoading: false,
    replay: replay(),
    events: [],
    isMobileReplay: false,
    hasRecording: false,
    replayPlayerRef: {current: null},
    mobileReplayViewerRef: {current: null},
    mobileReplayOrientation: 'portrait',
    mobileStatusBarContext: {},
    onTimeUpdate: vi.fn(),
    onDurationReady: vi.fn(),
    onPlayingChange: vi.fn(),
    onOrientationChange: vi.fn(),
    onStatusBarContextChange: vi.fn(),
    ...overrides,
  }
}

function elementProps(value: unknown): Record<string, unknown> {
  return (value as {props: Record<string, unknown>}).props
}

describe('replay detail helper coverage', () => {
  it('formats replay labels, dates, and identifiers', () => {
    expect(helpers.formatDuration(999)).toBe('999ms')
    expect(helpers.formatDuration(1500)).toBe('1.50s')
    expect(helpers.formatDate('', 'UTC')).toBe('N/A')
    expect(helpers.formatDate('not-a-date', 'UTC')).toBe('Invalid Date')
    expect(helpers.formatPlatformLabel(undefined)).toBeNull()
    expect(helpers.formatPlatformLabel('android')).toBe('Android')
    expect(helpers.mobileContainerPlatform('ios')).toBe('ios')
    expect(helpers.mobileContainerPlatform('android')).toBe('android')
    expect(helpers.replayProjectId(undefined)).toBeUndefined()
    expect(helpers.replayProjectId(replay())).toBe('svc-api')
    expect(helpers.replayProjectId(replay({projectId: 'svc-worker'}))).toBe('svc-worker')
  })

  it('detects mobile replay inputs and chooses durations', () => {
    const mobileVideo = {type: 'mobile_replay_video', segment_id: 1}
    const placeholder = {type: 'mobile_replay_not_supported'}

    expect(helpers.replayEventType(null)).toBeUndefined()
    expect(helpers.replayEventType(mobileVideo)).toBe('mobile_replay_video')
    expect(helpers.hasMobileVideoSegments([mobileVideo])).toBe(true)
    expect(helpers.isMobileReplayPlaceholder([placeholder])).toBe(true)
    expect(helpers.isMobileReplayPlaceholder([placeholder, mobileVideo])).toBe(false)
    expect(helpers.isMobileReplayPlatform('ios')).toBe(true)
    expect(helpers.isMobileReplayPlatform('android')).toBe(true)
    expect(helpers.isMobileReplayPlatform('javascript')).toBe(false)
    expect(helpers.isReplayMobile(replay({platform: 'android'}), [])).toBe(true)
    expect(helpers.isReplayMobile(replay(), [mobileVideo])).toBe(true)
    expect(helpers.isReplayMobile(replay(), [placeholder])).toBe(true)
    expect(helpers.resolveReplayDurationMs(10, 20, 30)).toBe(10)
    expect(helpers.resolveReplayDurationMs(0, 20, 30)).toBe(20)
    expect(helpers.resolveReplayDurationMs(0, 0, 30)).toBe(30)
    expect(helpers.resolveReplayDurationMs(0, 0, undefined)).toBe(0)
  })

  it('computes rrweb and mobile recording timing', () => {
    const rrwebEvents = [{timestamp: 1000}, {timestamp: 1750}, {timestamp: 'bad'}]

    expect(helpers.getRecordingDurationMs([], false)).toBe(0)
    expect(helpers.getRecordingDurationMs(rrwebEvents, false)).toBe(750)
    expect(helpers.getRecordingStartMs(rrwebEvents)).toBe(1000)
    expect(helpers.getRecordingStartMs([{timestamp: 'bad'}])).toBeNull()
    expect(helpers.isLikelyEpochMs(null)).toBe(false)
    expect(helpers.isLikelyEpochMs(946684800000)).toBe(true)
    expect(helpers.isLikelyEpochMs(1)).toBe(false)

    const mobileEvents = [
      {type: 5, timestamp: 1000, segment_id: 1, data: {tag: 'video', payload: {duration: '1200'}}},
      {type: 5, timestamp: 1100, segment_id: 1, data: {tag: 'breadcrumb', payload: {category: 'ui.click'}}},
      {type: 'mobile_replay_video', segment_id: 1},
      {type: 'mobile_replay_video', segment_id: 2},
    ]
    expect(helpers.getRecordingDurationMs(mobileEvents, true)).toBe(6200)
  })

  it('maps mobile timestamps into compressed video offsets', () => {
    expect(helpers.createMobileCompressedTimeMapper([])).toBeNull()

    const events = [
      {type: 5, timestamp: 1000, segment_id: 1, data: {tag: 'video', payload: {duration: 1000}}},
      {type: 5, timestamp: 1100, segment_id: 1, data: {tag: 'breadcrumb', payload: {category: 'ui.click'}}},
      {type: 5, timestamp: 2000, data: {tag: 'video', payload: {segmentId: '2', duration: '500'}}},
      {type: 4, timestamp: 2050, segment_id: 2},
      {type: 5, timestamp: 2100, segment_id: 2, data: {tag: 'breadcrumb', payload: {action: 'SCREEN_ON'}}},
      {type: 5, timestamp: 2200, segment_id: 2, data: {tag: 'breadcrumb', payload: {category: 'navigation'}}},
      {type: 5, timestamp: 2300, data: {tag: 'breadcrumb', payload: {category: 'ui.click'}}},
      {type: 'mobile_replay_video', segment_id: 1},
      {type: 'mobile_replay_video', segment_id: 2},
    ]

    const mapTime = helpers.createMobileCompressedTimeMapper(events)

    expect(mapTime).not.toBeNull()
    expect(mapTime?.(Number.NaN)).toBe(0)
    expect(mapTime?.(900)).toBe(0)
    expect(mapTime?.(1250)).toBe(250)
    expect(mapTime?.(2600)).toBe(1500)
  })

  it('builds mobile breadcrumb timeline items from replay events', () => {
    const start = Date.UTC(2026, 5, 1, 12, 0, 0)
    const items = helpers.buildBreadcrumbTimelineItems({
      events: [
        {type: 5, timestamp: start + 40, data: {tag: 'breadcrumb', payload: {category: 'device.state'}}},
        {type: 5, timestamp: start + 60, data: {tag: 'breadcrumb', payload: {action: 'SCREEN_ON'}}},
        {type: 5, timestamp: 'bad', data: {tag: 'breadcrumb', payload: {message: 'ignored'}}},
        {
          type: 5,
          timestamp: start + 100,
          data: {tag: 'breadcrumb', payload: {category: 'ui.lifecycle', screen: 'Checkout', state: 'resumed'}},
        },
        {
          type: 5,
          timestamp: start + 200,
          data: {
            tag: 'breadcrumb',
            payload: {category: 'ui.click', 'view.class': 'android.widget.Button', 'view.id': 'save'},
          },
        },
        {
          type: 5,
          timestamp: start + 300,
          data: {tag: 'breadcrumb', payload: {category: 'navigation', from: '/cart', to: '/checkout'}},
        },
        {
          type: 5,
          timestamp: start + 400,
          data: {
            tag: 'breadcrumb',
            payload: {category: 'http.request', method: 'POST', url: '/pay', status_code: 201},
          },
        },
        {type: 5, timestamp: start + 500, data: {tag: 'breadcrumb', payload: {message: 'Manual note'}}},
        {type: 5, timestamp: start + 600, data: {tag: 'breadcrumb', payload: {action: 'CUSTOM_ACTION'}}},
        {type: 5, timestamp: start + 700, data: {tag: 'breadcrumb', payload: {type: 'custom_event'}}},
        {type: 5, timestamp: start + 800, data: {tag: 'breadcrumb', payload: {}}},
      ],
      isMobileReplay: true,
      recordingStartMs: start,
      durationMs: 750,
      mobileCompressedTimeMapper: (timestamp) => timestamp - start + 25,
    })

    expect(items).toHaveLength(8)
    expect(items[0]).toMatchObject({
      offsetMs: 125,
      title: 'Ui Lifecycle',
      description: 'Checkout: resumed',
      category: 'ui.lifecycle',
    })
    expect(items[1]).toMatchObject({
      title: 'Ui Click',
      description: 'Clicked Button (save)',
    })
    expect(items[2].description).toContain('/cart')
    expect(items[2].description).toContain('/checkout')
    expect(items[3]).toMatchObject({
      title: 'Http Request',
      description: 'POST /pay 201',
    })
    expect(items[4]).toMatchObject({title: 'Manual note', description: 'Manual note'})
    expect(items[5]).toMatchObject({title: 'Breadcrumb', description: 'CUSTOM_ACTION'})
    expect(items[6]).toMatchObject({
      offsetMs: 725,
      title: 'Custom Event',
      description: 'custom_event',
    })
    expect(items[7]).toMatchObject({
      offsetMs: 750,
      title: 'Breadcrumb',
      description: undefined,
      category: undefined,
      data: undefined,
    })
  })

  it('returns no derived breadcrumbs for non-mobile or empty recordings', () => {
    const event = {type: 5, timestamp: 1000, data: {tag: 'breadcrumb', payload: {message: 'tap'}}}

    expect(helpers.buildBreadcrumbTimelineItems({
      events: [event],
      isMobileReplay: false,
      recordingStartMs: 0,
      durationMs: 0,
      mobileCompressedTimeMapper: null,
    })).toEqual([])
    expect(helpers.buildBreadcrumbTimelineItems({
      events: [],
      isMobileReplay: true,
      recordingStartMs: 0,
      durationMs: 0,
      mobileCompressedTimeMapper: null,
    })).toEqual([])
  })

  it('normalizes replay timeline offsets against recording timestamps', () => {
    const replayStart = Date.UTC(2026, 5, 1, 12, 0, 0)
    const recordingStart = replayStart + 500
    const fallbackItems = [
      {
        id: 'fallback',
        type: 'span' as const,
        timestamp: new Date(recordingStart).toISOString(),
        offsetMs: 0,
        title: 'Fallback',
      },
    ]

    expect(helpers.normalizeTimelineItems({
      items: [],
      fallbackItems,
      replayStartMs: replayStart,
      recordingStartMs: recordingStart,
      durationMs: 1000,
      mobileCompressedTimeMapper: null,
    })).toBe(fallbackItems)

    const normalized = helpers.normalizeTimelineItems({
      items: [
        {
          id: 'one',
          type: 'span',
          timestamp: new Date(recordingStart + 200).toISOString(),
          offsetMs: 800,
          title: 'One',
        },
        {
          id: 'two',
          type: 'error',
          timestamp: new Date(recordingStart + 1500).toISOString(),
          offsetMs: 2500,
          title: 'Two',
        },
        {
          id: 'bad',
          type: 'transaction',
          timestamp: 'not-a-date',
          offsetMs: Number.POSITIVE_INFINITY,
          title: 'Bad',
        },
      ],
      fallbackItems,
      replayStartMs: replayStart,
      recordingStartMs: recordingStart,
      durationMs: 1000,
      mobileCompressedTimeMapper: null,
    })

    expect(normalized.map((item) => [item.id, item.offsetMs])).toEqual([
      ['one', 300],
      ['two', 1000],
    ])

    const mobileNormalized = helpers.normalizeTimelineItems({
      items: [
        {
          id: 'mobile',
          type: 'span',
          timestamp: new Date(recordingStart + 300).toISOString(),
          offsetMs: 25,
          title: 'Mobile',
        },
      ],
      fallbackItems,
      replayStartMs: 0,
      recordingStartMs: null,
      durationMs: 1000,
      mobileCompressedTimeMapper: () => 375,
    })

    expect(mobileNormalized[0].offsetMs).toBe(375)
  })

  it('selects the replay recording stage container for loading and playback states', () => {
    const mobileLoading = helpers.ReplayRecordingStage(stageProps({
      recordingLoading: true,
      replay: replay({platform: 'ios'}),
    }))
    expect(elementProps(mobileLoading).platform).toBe('ios')
    expect(elementProps(mobileLoading).orientation).toBe('portrait')

    const browserLoading = helpers.ReplayRecordingStage(stageProps({
      recordingLoading: true,
      replay: replay({urls: ['https://example.com/session']}),
    }))
    expect(elementProps(browserLoading).url).toBe('https://example.com/session')

    const mobilePlayback = helpers.ReplayRecordingStage(stageProps({
      replay: replay({platform: 'android'}),
      events: [{type: 'mobile_replay_video'}],
      isMobileReplay: true,
      mobileReplayOrientation: 'landscape',
      mobileStatusBarContext: {batteryLevel: 0.75},
    }))
    expect(elementProps(mobilePlayback).platform).toBe('android')
    expect(elementProps(mobilePlayback).orientation).toBe('landscape')

    const browserPlayback = helpers.ReplayRecordingStage(stageProps({
      events: [{timestamp: 1000}],
      hasRecording: true,
    }))
    expect(elementProps(browserPlayback).url).toBe('https://app.example.com')

    const emptyState = helpers.ReplayRecordingStage(stageProps())
    expect((emptyState as {type: unknown}).type).toBe('p')
  })
})
