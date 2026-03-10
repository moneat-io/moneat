// Moneat - observability platform
// Copyright (C) 2026 Moneat
//
// This program is free software: you can redistribute it and/or modify
// it under the terms of the GNU Affero General Public License as published by
// the Free Software Foundation, either version 3 of the License, or
// (at your option) any later version.
//
// This program is distributed in the hope that it will be useful,
// but WITHOUT ANY WARRANTY; without even the implied warranty of
// MERCHANTABILITY or FITNESS FOR A PARTICULAR PURPOSE. See the
// GNU Affero General Public License for more details.
//
// You should have received a copy of the GNU Affero General Public License
// along with this program. If not, see <https://www.gnu.org/licenses/>.

import {createFileRoute, Link, redirect} from '@tanstack/react-router'
import {useQuery} from '@tanstack/react-query'
import {useCallback, useMemo, useRef, useState} from 'react'
import {api} from '@/lib/api'
import {formatRelativeTime} from '@/lib/utils'
import {useTimezone} from '@/hooks/useTimezone'
import {formatDateTime as formatDateTimeUtil} from '@/lib/date-format'
import {ReplayPlayer, type ReplayPlayerHandle} from '@/components/ReplayPlayer'
import {MobileReplayViewer, type MobileReplayViewerHandle, type ReplayStatusBarContext} from '@/components/MobileReplayViewer'
import {ReplayTimelinePanel} from '@/components/ReplayTimelinePanel'
import {ReplayTimelineScrubber} from '@/components/ReplayTimelineScrubber'
import {BrowserWindowContainer} from '@/components/replay-containers/BrowserWindowContainer'
import {MobileDeviceContainer} from '@/components/replay-containers/MobileDeviceContainer'
import {Badge} from '@/components/ui/badge'
import {
    AlertCircle,
    ArrowUpRight,
    ChevronDown,
    ChevronLeft,
    ChevronUp,
    Clock3,
    DatabaseZap,
    Globe,
    Layers,
    Loader2,
    Monitor,
    Play,
    Smartphone,
    Tag,
    User,
} from 'lucide-react'

export const Route = createFileRoute('/replays/$replayId')({
  beforeLoad: ({ location }) => {
    if (!api.isAuthenticated()) {
      throw redirect({ to: '/login', search: { redirect: location.href } })
    }
  },
  component: ReplayDetailPage,
})

function formatDuration(ms: number) {
  if (ms >= 1000) return `${(ms / 1000).toFixed(2)}s`
  return `${ms.toFixed(0)}ms`
}

function formatDate(isoString: string, timezone: string) {
  if (!isoString) return 'N/A'
  const date = new Date(isoString)
  if (isNaN(date.getTime())) return 'Invalid Date'
  return formatDateTimeUtil(date, timezone)
}

/** Compute actual recording duration from events. Backend replay.durationMs uses session span and can be wrong. */
function getRecordingDurationMs(events: unknown[], isMobileReplay: boolean): number {
  if (!Array.isArray(events) || events.length === 0) return 0

  if (isMobileReplay) {
    // Mobile: sum video segment durations (same logic as MobileReplayViewer)
    const replayEvents = events.filter(
      (e): e is { type: number; timestamp: number; segment_id?: number; data?: { tag?: string; payload?: { duration?: number } } } =>
        typeof e === 'object' &&
        e !== null &&
        'type' in e &&
        'timestamp' in e &&
        typeof (e as { type: unknown }).type === 'number' &&
        typeof (e as { timestamp: unknown }).timestamp === 'number'
    )
    const segmentDurations = new Map<number, number>()
    for (const e of replayEvents) {
      if (e.type !== 5 || e.data?.tag !== 'video') continue
      const segmentId = typeof e.segment_id === 'number' ? e.segment_id : undefined
      const duration = e.data?.payload?.duration
      const durMs =
        typeof duration === 'number' && Number.isFinite(duration) && duration > 0
          ? duration
          : typeof duration === 'string'
            ? Number(duration)
            : NaN
      if (segmentId !== undefined && Number.isFinite(durMs) && durMs > 0) {
        segmentDurations.set(segmentId, durMs)
      }
    }
    const videoSegments = events
      .filter((e): e is { type: string; segment_id?: number } => typeof e === 'object' && e !== null && (e as { type?: string }).type === 'mobile_replay_video')
      .sort((a, b) => (a.segment_id ?? 0) - (b.segment_id ?? 0))
    if (videoSegments.length === 0) return 0
    let total = 0
    for (let i = 0; i < videoSegments.length; i++) {
      const segment = videoSegments[i]
      if (!segment) continue
      const segId = typeof segment.segment_id === 'number' ? segment.segment_id : i
      total += segmentDurations.get(segId) ?? 5000
    }
    return total
  }

  // rrweb: duration = max(timestamp) - min(timestamp)
  let minTs = Infinity
  let maxTs = -Infinity
  for (const e of events) {
    if (e && typeof e === 'object' && 'timestamp' in e && typeof (e as { timestamp: unknown }).timestamp === 'number') {
      const ts = (e as { timestamp: number }).timestamp
      minTs = Math.min(minTs, ts)
      maxTs = Math.max(maxTs, ts)
    }
  }
  return minTs < maxTs ? maxTs - minTs : 0
}

function getRecordingStartMs(events: unknown[]): number | null {
  let minTs = Infinity
  for (const e of events) {
    if (e && typeof e === 'object' && 'timestamp' in e && typeof (e as { timestamp: unknown }).timestamp === 'number') {
      const ts = (e as { timestamp: number }).timestamp
      minTs = Math.min(minTs, ts)
    }
  }
  return Number.isFinite(minTs) ? minTs : null
}

function isLikelyEpochMs(timestampMs: number | null): boolean {
  if (timestampMs == null) return false
  // 2000-01-01 UTC to 2100-01-01 UTC
  return timestampMs >= 946684800000 && timestampMs <= 4102444800000
}

interface MobileReplayEventForTiming {
  type: number
  timestamp: number
  segment_id?: number
  data?: {
    tag?: string
    payload?: Record<string, unknown> & { duration?: number | string }
  }
}

function createMobileCompressedTimeMapper(events: unknown[]): ((absoluteTimestampMs: number) => number) | null {
  const replayEvents = events
    .filter(
      (e): e is MobileReplayEventForTiming =>
        typeof e === 'object' &&
        e !== null &&
        'type' in e &&
        'timestamp' in e &&
        typeof (e as { type: unknown }).type === 'number' &&
        typeof (e as { timestamp: unknown }).timestamp === 'number'
    )
    .sort((a, b) => a.timestamp - b.timestamp)

  const getEventSegmentId = (event: MobileReplayEventForTiming): number | undefined => {
    if (typeof event.segment_id === 'number') return event.segment_id
    const payloadSegmentId = event.data?.payload?.segmentId
    if (typeof payloadSegmentId === 'number') return payloadSegmentId
    if (typeof payloadSegmentId === 'string') {
      const parsed = Number(payloadSegmentId)
      if (Number.isFinite(parsed)) return parsed
    }
    return undefined
  }

  const getVideoDurationMs = (event: MobileReplayEventForTiming): number | undefined => {
    if (event.type !== 5 || event.data?.tag !== 'video') return undefined
    const duration = event.data?.payload?.duration
    if (typeof duration === 'number' && Number.isFinite(duration) && duration > 0) return duration
    if (typeof duration === 'string') {
      const parsed = Number(duration)
      if (Number.isFinite(parsed) && parsed > 0) return parsed
    }
    return undefined
  }

  const isMeaningfulEvent = (event: MobileReplayEventForTiming): boolean => {
    if (event.type === 4) return false
    if (event.type === 5) {
      const tag = event.data?.tag
      if (tag === 'video') return false
      if (tag === 'breadcrumb') {
        const payload = event.data?.payload ?? {}
        const category = payload.category
        const action = payload.action
        if (typeof category === 'string' && category.startsWith('device.')) return false
        if (
          typeof action === 'string' &&
          ['SCREEN_OFF', 'SCREEN_ON', 'DREAMING_STARTED', 'DREAMING_STOPPED'].includes(action)
        ) {
          return false
        }
      }
    }
    return true
  }

  const videoSegments = events
    .filter((e): e is { type: string; segment_id?: number } => typeof e === 'object' && e !== null && (e as { type?: string }).type === 'mobile_replay_video')
    .sort((a, b) => (a.segment_id ?? 0) - (b.segment_id ?? 0))

  if (videoSegments.length === 0) return null

  const meaningfulCountsBySegment = new Map<number, number>()
  for (const event of replayEvents) {
    if (!isMeaningfulEvent(event)) continue
    const segmentId = getEventSegmentId(event)
    if (segmentId === undefined) continue
    meaningfulCountsBySegment.set(segmentId, (meaningfulCountsBySegment.get(segmentId) ?? 0) + 1)
  }

  const filteredVideoSegments =
    meaningfulCountsBySegment.size > 0
      ? videoSegments.filter((segment) => {
          const segmentId = typeof segment.segment_id === 'number' ? segment.segment_id : undefined
          return segmentId !== undefined && (meaningfulCountsBySegment.get(segmentId) ?? 0) > 0
        })
      : videoSegments
  const effectiveVideoSegments = filteredVideoSegments.length > 0 ? filteredVideoSegments : videoSegments

  const segmentDurations = new Map<number, number>()
  for (const e of replayEvents) {
    const segmentId = getEventSegmentId(e)
    const durationMs = getVideoDurationMs(e)
    if (segmentId !== undefined && durationMs !== undefined) {
      segmentDurations.set(segmentId, durationMs)
    }
  }

  const segmentStarts = new Map<number, number>()
  for (const e of replayEvents) {
    const segmentId = getEventSegmentId(e)
    if (segmentId === undefined) continue
    const existing = segmentStarts.get(segmentId)
    if (existing === undefined || e.timestamp < existing) {
      segmentStarts.set(segmentId, e.timestamp)
    }
  }

  const segments = effectiveVideoSegments.map((seg, i) => {
    const segmentId = typeof seg.segment_id === 'number' ? seg.segment_id : i
    return {
      segmentId,
      startTimestamp: segmentStarts.get(segmentId),
      durationMs: segmentDurations.get(segmentId) ?? 5000,
    }
  })

  let runningOffset = 0
  const withOffsets = segments.map((seg) => {
    const mapped = { ...seg, globalOffsetMs: runningOffset }
    runningOffset += seg.durationMs
    return mapped
  })

  const byStart = withOffsets
    .filter((seg): seg is (typeof withOffsets)[number] & { startTimestamp: number } => typeof seg.startTimestamp === 'number')
    .sort((a, b) => a.startTimestamp - b.startTimestamp)

  if (byStart.length === 0) return null

  return (absoluteTimestampMs: number) => {
    if (!Number.isFinite(absoluteTimestampMs)) return 0

    let target = byStart[0]
    for (let i = 0; i < byStart.length; i++) {
      const current = byStart[i]
      const next = byStart[i + 1]
      if (absoluteTimestampMs < current.startTimestamp) {
        target = byStart[0]
        break
      }
      if (!next || absoluteTimestampMs < next.startTimestamp) {
        target = current
        break
      }
    }

    const localMs = Math.max(0, Math.min(absoluteTimestampMs - target.startTimestamp, target.durationMs))
    return target.globalOffsetMs + localMs
  }
}

function ReplayDetailPage() {
  const { replayId } = Route.useParams()
  const { timezone } = useTimezone()

  const { data: replay, isLoading } = useQuery({
    queryKey: ['replay', replayId],
    queryFn: () => api.getReplay(replayId),
    enabled: !!replayId,
  })

  const { data: recording, isLoading: recordingLoading } = useQuery({
    queryKey: ['replay-recording', replayId],
    queryFn: () => api.getReplayRecording(replayId),
    enabled: !!replayId,
  })

  const { data: timeline } = useQuery({
    queryKey: ['replay-timeline', replayId],
    queryFn: () => api.getReplayTimeline(replayId),
    enabled: !!replayId,
  })

  const [currentOffsetMs, setCurrentOffsetMs] = useState(0)
  const [recordingDurationMs, setRecordingDurationMs] = useState(0)
  const [isPlaying, setIsPlaying] = useState(false)
  const [playbackSpeed, setPlaybackSpeed] = useState(1)
  const [detailsExpanded, setDetailsExpanded] = useState(false)
  const [mobileReplayOrientation, setMobileReplayOrientation] = useState<'portrait' | 'landscape'>('portrait')
  const [mobileStatusBarContext, setMobileStatusBarContext] = useState<ReplayStatusBarContext>({})
  const replayPlayerRef = useRef<ReplayPlayerHandle>(null)
  const mobileReplayViewerRef = useRef<MobileReplayViewerHandle>(null)

  const events = useMemo(() => recording?.events ?? [], [recording?.events])
  const hasMobileVideoSegments = events.some((event) => {
    if (typeof event !== 'object' || event === null || !('type' in event)) return false
    return (event as { type: unknown }).type === 'mobile_replay_video'
  })
  const isMobileReplay =
    replay?.platform === 'android' ||
    replay?.platform === 'ios' ||
    hasMobileVideoSegments ||
    (events.length === 1 &&
      typeof events[0] === 'object' &&
      events[0] !== null &&
      'type' in events[0] &&
      (events[0] as { type: unknown }).type === 'mobile_replay_not_supported')

  // Prefer player-reported duration (onDurationReady); fallback to computed or backend
  const computedDurationMs = useMemo(
    () => getRecordingDurationMs(events, isMobileReplay),
    [events, isMobileReplay]
  )
  const recordingStartMs = useMemo(
    () => getRecordingStartMs(events),
    [events]
  )
  const durationMs =
    recordingDurationMs > 0 ? recordingDurationMs : computedDurationMs > 0 ? computedDurationMs : (replay?.durationMs ?? 0)
  const mobileCompressedTimeMapper = useMemo(
    () => (isMobileReplay ? createMobileCompressedTimeMapper(events) : null),
    [events, isMobileReplay]
  )

  const handleSeek = useCallback((offsetMs: number) => {
    // Clamp to recording length so Jump to always seeks to a valid position
    const clampedMs = Math.max(0, Math.min(offsetMs, durationMs))
    replayPlayerRef.current?.seekTo(clampedMs)
    mobileReplayViewerRef.current?.seekTo(clampedMs)
  }, [durationMs])

  const handlePlayPause = useCallback(() => {
    if (isPlaying) {
      replayPlayerRef.current?.pause()
      mobileReplayViewerRef.current?.pause()
      setIsPlaying(false)
    } else {
      replayPlayerRef.current?.play()
      mobileReplayViewerRef.current?.play()
      setIsPlaying(true)
    }
  }, [isPlaying])

  const handleSpeedChange = useCallback((speed: number) => {
    setPlaybackSpeed(speed)
    replayPlayerRef.current?.setSpeed(speed)
  }, [])

  // Derive breadcrumb timeline items from recording events for mobile replays when backend timeline is empty.
  const breadcrumbsFromEvents = useMemo(() => {
    if (!isMobileReplay || !Array.isArray(events) || events.length === 0) return []
    const startMs = recordingStartMs ?? 0

    const breadcrumbEvents = events
      .filter(
        (e): e is { type: number; timestamp: number; data?: { tag?: string; payload?: Record<string, unknown> } } =>
          typeof e === 'object' &&
          e !== null &&
          'type' in e &&
          'timestamp' in e &&
          (e as { type: unknown }).type === 5 &&
          (e as { data?: { tag?: string } }).data?.tag === 'breadcrumb'
      )
      .filter((e) => {
        const p = e.data?.payload ?? {}
        const cat = p.category
        const action = p.action
        if (typeof cat === 'string' && cat.startsWith('device.')) return false
        if (
          typeof action === 'string' &&
          ['SCREEN_OFF', 'SCREEN_ON', 'DREAMING_STARTED', 'DREAMING_STOPPED'].includes(action)
        )
          return false
        return true
      })
      .sort((a, b) => a.timestamp - b.timestamp)

    const formatBreadcrumbTitle = (p: Record<string, unknown>): string => {
      const cat = (p.category ?? p.type ?? '') as string
      if (typeof cat === 'string' && cat.length > 0) {
        return cat
          .split(/[._-]/)
          .map((part) => part.charAt(0).toUpperCase() + part.slice(1).toLowerCase())
          .join(' ')
      }
      const msg = p.message
      if (typeof msg === 'string' && msg.length > 0) return msg
      return 'Breadcrumb'
    }

    const formatBreadcrumbDetail = (p: Record<string, unknown>, category: string): string | undefined => {
      const cat = category.toLowerCase()
      if (p.message && typeof p.message === 'string') return p.message
      if (cat.includes('ui.lifecycle'))
        return `${p.screen ?? 'Screen'}: ${p.state ?? ''}`
      if (cat.includes('ui.click')) {
        const viewClass = (p['view.class'] as string)?.split('.').pop() ?? ''
        const viewId = p['view.id'] ?? ''
        return viewId ? `Clicked ${viewClass} (${viewId})` : `Clicked ${viewClass}`
      }
      if (cat.includes('navigation')) return `${p.from ?? ''} → ${p.to ?? ''}`
      if (cat.includes('http') || cat.includes('network')) {
        const parts: string[] = []
        if (p.method) parts.push(String(p.method))
        if (p.url) parts.push(String(p.url))
        if (p.status_code != null) parts.push(String(p.status_code))
        return parts.length > 0 ? parts.join(' ') : undefined
      }
      if (p.action && typeof p.action === 'string') return p.action
      if (p.type && typeof p.type === 'string') return p.type
      return undefined
    }

    return breadcrumbEvents.map((e, idx) => {
      const p = e.data?.payload ?? {}
      const cat = (p.category ?? p.type ?? '') as string
      const title = formatBreadcrumbTitle(p)
      const description = formatBreadcrumbDetail(p, cat)
      let offsetMs = e.timestamp - startMs
      if (mobileCompressedTimeMapper && Number.isFinite(e.timestamp)) {
        offsetMs = mobileCompressedTimeMapper(e.timestamp)
      }
      offsetMs = Math.max(0, durationMs > 0 ? Math.min(offsetMs, durationMs) : offsetMs)
      return {
        id: `breadcrumb-${idx}-${e.timestamp}`,
        type: 'span' as const,
        timestamp: new Date(e.timestamp).toISOString(),
        offsetMs,
        title,
        description,
        category: cat || undefined,
        data: Object.keys(p).length > 0 ? p : undefined,
      }
    })
  }, [
    isMobileReplay,
    events,
    recordingStartMs,
    durationMs,
    mobileCompressedTimeMapper,
  ])

  // Align backend offsets (based on replay_start_timestamp) with player offsets (based on recording event start).
  // For mobile replays with no backend timeline, use breadcrumbs derived from recording events.
  const timelineItems = useMemo(() => {
    const items = timeline?.items ?? []
    if (items.length === 0) {
      return breadcrumbsFromEvents
    }

    const replayStartMs = timeline?.replayStartMs ?? 0
    const shouldAdjust =
      replayStartMs > 0 &&
      isLikelyEpochMs(replayStartMs) &&
      isLikelyEpochMs(recordingStartMs)
    const shiftMs = shouldAdjust ? (recordingStartMs as number) - replayStartMs : 0
    const out: typeof items = []

    for (const item of items) {
      const rawOffset = item.offsetMs - shiftMs
      if (!Number.isFinite(rawOffset)) continue

      let normalizedOffset = Math.max(0, rawOffset)
      if (mobileCompressedTimeMapper) {
        const absoluteMs = Date.parse(item.timestamp)
        if (Number.isFinite(absoluteMs)) {
          normalizedOffset = mobileCompressedTimeMapper(absoluteMs)
        }
      }
      if (durationMs > 0) {
        normalizedOffset = Math.max(0, Math.min(normalizedOffset, durationMs))
      }

      out.push({ ...item, offsetMs: normalizedOffset })
    }

    return out
  }, [
    timeline?.items,
    timeline?.replayStartMs,
    recordingStartMs,
    durationMs,
    mobileCompressedTimeMapper,
    breadcrumbsFromEvents,
  ])

  if (isLoading || !replay) {
    return (
      <div className="p-6">
        <div className="px-3 py-3 lg:px-5 lg:py-4">Loading replay...</div>
      </div>
    )
  }

  const hasRecording = events.length > 0 && !isMobileReplay
  const platformLabel = replay.platform
    ? replay.platform.charAt(0).toUpperCase() + replay.platform.slice(1)
    : null

  return (
    <div className="flex flex-col">
      {/* ───── Header ───── */}
      <div className="px-3 py-3 lg:px-5 lg:py-3">
        {/* Breadcrumb nav */}
        <nav className="mb-2 flex items-center gap-2 text-sm">
          <Link
            to="/replays"
            className="inline-flex items-center gap-1 text-muted-foreground hover:text-foreground transition-colors"
          >
            <ChevronLeft className="h-3.5 w-3.5" />
            Replays
          </Link>
          <span className="text-muted-foreground/50">/</span>
          <span className="text-foreground font-medium truncate max-w-[200px] sm:max-w-none font-mono text-xs" title={replayId}>
            {replayId.slice(0, 8)}...
          </span>
        </nav>

        {/* Compact header bar */}
        <div className="flex flex-wrap items-center gap-x-3 gap-y-1.5 text-sm">
          <div className="flex items-center gap-2">
            <Play className="h-4 w-4 text-indigo-500 dark:text-indigo-400" />
            <span className="font-semibold text-base">
              {replay.user?.email || replay.user?.username || replay.user?.id || 'Anonymous'}
            </span>
          </div>
          {replay.errorCount > 0 && (
            <Badge variant="destructive" className="flex items-center gap-1 text-[11px]">
              <AlertCircle className="h-3 w-3" />
              {replay.errorCount} error{replay.errorCount !== 1 ? 's' : ''}
            </Badge>
          )}
          {platformLabel && (
            <Badge variant="outline" className="flex items-center gap-1 text-[11px]">
              {(replay.platform === 'android' || replay.platform === 'ios') ? (
                <Smartphone className="h-3 w-3" />
              ) : (
                <Monitor className="h-3 w-3" />
              )}
              {platformLabel}
            </Badge>
          )}
          {replay.environment && (
            <Badge variant="outline" className="text-[11px]">{replay.environment}</Badge>
          )}
          <span className="hidden sm:inline text-muted-foreground/40">|</span>
          <span className="inline-flex items-center gap-1.5 text-muted-foreground text-xs">
            <Clock3 className="h-3 w-3" />
            {formatRelativeTime(replay.startedAt)}
          </span>
          <span className="inline-flex items-center gap-1.5 text-xs">
            <span className="font-semibold text-indigo-600 dark:text-indigo-400">{formatDuration(durationMs)}</span>
            <span className="text-muted-foreground">duration</span>
          </span>
        </div>
      </div>

      {/* ───── Two-column main content ───── */}
      <div className="flex-1 px-3 lg:px-5 grid grid-cols-1 lg:grid-cols-12 gap-3 min-h-0">
        {/* Left column: Device container + replay */}
        <div className="lg:col-span-5 flex flex-col min-h-0">
          {recordingLoading ? (
            replay.platform === 'android' || replay.platform === 'ios' ? (
              <MobileDeviceContainer
                platform={replay.platform === 'ios' ? 'ios' : 'android'}
                orientation="portrait"
                className="py-2"
              >
                <div className="w-full h-full flex flex-col items-center justify-center gap-3 bg-black">
                  <Loader2 className="h-8 w-8 animate-spin text-muted-foreground" />
                  <p className="text-sm text-muted-foreground">Loading recording...</p>
                </div>
              </MobileDeviceContainer>
            ) : (
              <BrowserWindowContainer
                url={replay.urls?.[0]}
              >
                <div className="w-full h-[450px] flex flex-col items-center justify-center gap-3 bg-black">
                  <Loader2 className="h-8 w-8 animate-spin text-muted-foreground" />
                  <p className="text-sm text-muted-foreground">Loading recording...</p>
                </div>
              </BrowserWindowContainer>
            )
          ) : isMobileReplay ? (
            <MobileDeviceContainer
              platform={replay.platform === 'ios' ? 'ios' : 'android'}
              orientation={mobileReplayOrientation}
              className="py-2"
              statusBarContext={mobileStatusBarContext}
            >
              <MobileReplayViewer
                ref={mobileReplayViewerRef}
                events={events}
                platform={replay.platform || 'mobile'}
                hideControls
                onTimeUpdate={setCurrentOffsetMs}
                onDurationReady={setRecordingDurationMs}
                onPlayingChange={setIsPlaying}
                onOrientationChange={setMobileReplayOrientation}
                onStatusBarContextChange={setMobileStatusBarContext}
              />
            </MobileDeviceContainer>
          ) : hasRecording ? (
            <BrowserWindowContainer
              url={replay.urls?.[0]}
            >
              <ReplayPlayer
                ref={replayPlayerRef}
                events={events}
                width={800}
                height={450}
                autoPlay={false}
                showController={false}
                className="w-full"
                onTimeUpdate={setCurrentOffsetMs}
                onDurationReady={setRecordingDurationMs}
                onPlayingChange={setIsPlaying}
              />
            </BrowserWindowContainer>
          ) : (
            <div className="flex items-center justify-center h-[400px] rounded-lg border bg-muted">
              <p className="text-muted-foreground">No recording data available for this replay</p>
            </div>
          )}
        </div>

        {/* Right column: replay timeline */}
        <div className="lg:col-span-7 flex flex-col min-h-0">
          <div className="flex flex-col min-h-[400px] max-h-[400px] lg:min-h-[calc(100vh-220px)] lg:max-h-[calc(100vh-220px)]">
            {timelineItems.length > 0 ? (
              <ReplayTimelinePanel
                items={timelineItems}
                currentOffsetMs={currentOffsetMs}
                projectId={replay.projectId}
                onSeek={handleSeek}
              />
            ) : (
              <div className="flex-1 rounded-lg border bg-card p-6 text-center text-sm text-muted-foreground">
                No breadcrumb events for this replay.
              </div>
            )}
          </div>
        </div>
      </div>

      {/* ───── Full-width timeline scrubber ───── */}
      <div className="mt-3">
        <ReplayTimelineScrubber
          currentOffsetMs={currentOffsetMs}
          durationMs={durationMs}
          isPlaying={isPlaying}
          items={timelineItems}
          onSeek={handleSeek}
          onPlayPause={handlePlayPause}
          onSpeedChange={handleSpeedChange}
          speed={playbackSpeed}
        />
      </div>

      {/* ───── Collapsible session details ───── */}
      <div className="px-3 lg:px-5 py-3">
        <button
          type="button"
          onClick={() => setDetailsExpanded(!detailsExpanded)}
          className="flex items-center gap-2 text-sm font-medium text-muted-foreground hover:text-foreground transition-colors w-full"
        >
          {detailsExpanded ? <ChevronUp className="h-4 w-4" /> : <ChevronDown className="h-4 w-4" />}
          Session Details
          <span className="flex-1 border-b border-border ml-2" />
        </button>

        {detailsExpanded && (
          <div className="mt-3 grid grid-cols-1 sm:grid-cols-2 lg:grid-cols-3 xl:grid-cols-4 gap-3">
            {/* Session info */}
            <div className="rounded-lg border bg-card p-3">
              <div className="text-xs font-medium text-muted-foreground mb-2 flex items-center gap-1.5">
                <Layers className="h-3.5 w-3.5" />
                Session
              </div>
              <div className="space-y-1.5 text-sm">
                <div className="flex justify-between gap-2">
                  <span className="text-muted-foreground">Duration</span>
                  <span className="font-medium">{formatDuration(durationMs)}</span>
                </div>
                <div className="flex justify-between gap-2">
                  <span className="text-muted-foreground">Started</span>
                  <span className="text-xs">{formatDate(replay.startedAt, timezone)}</span>
                </div>
                <div className="flex justify-between gap-2">
                  <span className="text-muted-foreground">Finished</span>
                  <span className="text-xs">{formatDate(replay.finishedAt, timezone)}</span>
                </div>
                {replay.environment && (
                  <div className="flex justify-between gap-2">
                    <span className="text-muted-foreground">Environment</span>
                    <Badge variant="outline" className="text-xs">{replay.environment}</Badge>
                  </div>
                )}
                {replay.release && (
                  <div className="flex items-start justify-between gap-2">
                    <span className="text-muted-foreground">Release</span>
                    <span className="text-right min-w-0 max-w-[65%] break-words [overflow-wrap:anywhere] text-xs">{replay.release}</span>
                  </div>
                )}
                {platformLabel && (
                  <div className="flex justify-between gap-2">
                    <span className="text-muted-foreground">Platform</span>
                    <span>{platformLabel}</span>
                  </div>
                )}
                {replay.segmentCount > 0 && (
                  <div className="flex justify-between gap-2">
                    <span className="text-muted-foreground">Segments</span>
                    <span>{replay.segmentCount}</span>
                  </div>
                )}
              </div>
            </div>

            {/* User */}
            <div className="rounded-lg border bg-card p-3">
              <div className="text-xs font-medium text-muted-foreground mb-2 flex items-center gap-1.5">
                <User className="h-3.5 w-3.5" />
                User
              </div>
              {replay.user?.email || replay.user?.username || replay.user?.id ? (
                <div className="space-y-1 text-sm">
                  {replay.user.email && <div>{replay.user.email}</div>}
                  {replay.user.username && <div className="text-muted-foreground">{replay.user.username}</div>}
                  {replay.user.id && (
                    <div className="text-muted-foreground font-mono text-xs">{replay.user.id}</div>
                  )}
                </div>
              ) : (
                <p className="text-sm text-muted-foreground">Anonymous</p>
              )}
            </div>

            {/* Browser / OS */}
            <div className="rounded-lg border bg-card p-3">
              <div className="text-xs font-medium text-muted-foreground mb-2 flex items-center gap-1.5">
                <Monitor className="h-3.5 w-3.5" />
                Browser / OS
              </div>
              <div className="space-y-1 text-sm">
                {replay.browserName && (
                  <div>
                    {replay.browserName}
                    {replay.browserVersion && ` ${replay.browserVersion}`}
                  </div>
                )}
                {replay.osName && (
                  <div className="text-muted-foreground">
                    {replay.osName}
                    {replay.osVersion && ` ${replay.osVersion}`}
                  </div>
                )}
                {!replay.browserName && !replay.osName && (
                  <p className="text-muted-foreground">Not recorded</p>
                )}
              </div>
            </div>

            {/* Visited URLs */}
            {replay.urls && replay.urls.length > 0 && (
              <div className="rounded-lg border bg-card p-3">
                <div className="text-xs font-medium text-muted-foreground mb-2 flex items-center gap-1.5">
                  <Globe className="h-3.5 w-3.5" />
                  URLs ({replay.urls.length})
                </div>
                <ul className="space-y-1 text-sm max-h-32 overflow-y-auto">
                  {replay.urls.map((url, i) => (
                    <li key={i} className="truncate text-muted-foreground text-xs" title={url}>
                      {url}
                    </li>
                  ))}
                </ul>
              </div>
            )}

            {/* Error IDs */}
            {replay.errorIds && replay.errorIds.length > 0 && (
              <div className="rounded-lg border bg-card p-3">
                <div className="text-xs font-medium text-muted-foreground mb-2 flex items-center gap-1.5">
                  <AlertCircle className="h-3.5 w-3.5 text-red-500" />
                  Errors ({replay.errorIds.length})
                </div>
                <div className="space-y-1.5 max-h-[160px] overflow-auto">
                  {replay.errorIds.map((errorId) => (
                    <Link
                      key={errorId}
                      to="/issues/$issueId"
                      params={{ issueId: errorId }}
                      className="flex items-center justify-between rounded border p-1.5 transition-colors hover:bg-accent group"
                    >
                      <span className="font-mono text-xs text-muted-foreground truncate min-w-0">{errorId}</span>
                      <ArrowUpRight className="h-3 w-3 text-muted-foreground flex-shrink-0 ml-2 opacity-0 group-hover:opacity-100 transition-opacity" />
                    </Link>
                  ))}
                </div>
              </div>
            )}

            {/* Trace IDs */}
            {replay.traceIds && replay.traceIds.length > 0 && (
              <div className="rounded-lg border bg-card p-3">
                <div className="text-xs font-medium text-muted-foreground mb-2 flex items-center gap-1.5">
                  <DatabaseZap className="h-3.5 w-3.5" />
                  Traces ({replay.traceIds.length})
                </div>
                <ul className="space-y-1 font-mono text-xs text-muted-foreground max-h-[160px] overflow-auto">
                  {replay.traceIds.map((traceId) => (
                    <li key={traceId} className="truncate" title={traceId}>
                      {traceId}
                    </li>
                  ))}
                </ul>
              </div>
            )}

            {/* Tags */}
            {replay.tags && Object.keys(replay.tags).length > 0 && (
              <div className="rounded-lg border bg-card p-3">
                <div className="text-xs font-medium text-muted-foreground mb-2 flex items-center gap-1.5">
                  <Tag className="h-3.5 w-3.5" />
                  Tags ({Object.keys(replay.tags).length})
                </div>
                <div className="flex flex-wrap gap-1.5">
                  {Object.entries(replay.tags).map(([key, value]) => (
                    <div
                      key={key}
                      className="inline-flex items-center gap-1 rounded-md border bg-muted/30 px-2 py-0.5 text-[11px]"
                    >
                      <span className="text-muted-foreground">{key}:</span>
                      <span className="font-mono font-medium">{value}</span>
                    </div>
                  ))}
                </div>
              </div>
            )}
          </div>
        )}
      </div>
    </div>
  )
}
