import { createFileRoute, Link, redirect } from '@tanstack/react-router'
import { useQuery } from '@tanstack/react-query'
import { useMemo, useRef, useState } from 'react'
import { api } from '@/lib/api'
import { ReplayPlayer, type ReplayPlayerHandle } from '@/components/replay-player'
import { MobileReplayViewer, type MobileReplayViewerHandle } from '@/components/mobile-replay-viewer'
import { ReplayTimelinePanel } from '@/components/replay-timeline-panel'
import { Card, CardContent, CardHeader, CardTitle } from '@/components/ui/card'
import { Badge } from '@/components/ui/badge'
import { ChevronLeft, User, Monitor, Globe, AlertCircle, DatabaseZap, Tag } from 'lucide-react'

export const Route = createFileRoute('/replays/$replayId')({
  beforeLoad: () => {
    if (!api.isAuthenticated()) {
      throw redirect({ to: '/login' })
    }
  },
  component: ReplayDetailPage,
})

function formatDuration(ms: number) {
  if (ms >= 1000) return `${(ms / 1000).toFixed(2)}s`
  return `${ms.toFixed(0)}ms`
}

function formatDate(isoString: string) {
  if (!isoString) return 'N/A'
  const date = new Date(isoString)
  if (isNaN(date.getTime())) return 'Invalid Date'
  return date.toLocaleDateString('en-US', {
    month: 'short',
    day: 'numeric',
    year: 'numeric',
    hour: '2-digit',
    minute: '2-digit',
    second: '2-digit',
  })
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
  const replayPlayerRef = useRef<ReplayPlayerHandle>(null)
  const mobileReplayViewerRef = useRef<MobileReplayViewerHandle>(null)

  const events = recording?.events ?? []
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

  const handleSeek = (offsetMs: number) => {
    // Clamp to recording length so Jump to always seeks to a valid position
    const clampedMs = Math.max(0, Math.min(offsetMs, durationMs))
    replayPlayerRef.current?.seekTo(clampedMs)
    mobileReplayViewerRef.current?.seekTo(clampedMs)
  }

  // Align backend offsets (based on replay_start_timestamp) with player offsets (based on recording event start).
  const timelineItems = useMemo(() => {
    const items = timeline?.items ?? []
    if (items.length === 0) return items

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
  }, [timeline?.items, timeline?.replayStartMs, recordingStartMs, durationMs, mobileCompressedTimeMapper])

  if (isLoading || !replay) {
    return (
      <div className="min-h-screen bg-background p-6">
        <div className="max-w-7xl mx-auto">Loading replay...</div>
      </div>
    )
  }

  const hasRecording = events.length > 0 && !isMobileReplay

  return (
    <div className="min-h-screen bg-background">
      <div className="max-w-7xl mx-auto p-6">
        <Link
          to="/replays"
          className="inline-flex items-center gap-2 text-sm text-muted-foreground hover:text-foreground mb-4"
        >
          <ChevronLeft className="h-4 w-4" />
          Back to Replays
        </Link>

        <div className="grid grid-cols-1 lg:grid-cols-3 gap-6">
          <div className="lg:col-span-2 space-y-4">
            <Card>
              <CardHeader>
                <CardTitle className="flex items-center gap-2">
                  <span>Session Replay</span>
                  {replay.errorCount > 0 && (
                    <Badge variant="destructive" className="flex items-center gap-1">
                      <AlertCircle className="h-3 w-3" />
                      {replay.errorCount} error{replay.errorCount !== 1 ? 's' : ''}
                    </Badge>
                  )}
                </CardTitle>
              </CardHeader>
              <CardContent>
                {recordingLoading ? (
                  <div className="flex items-center justify-center h-[400px] rounded border bg-muted">
                    <p className="text-muted-foreground">Loading recording...</p>
                  </div>
                ) : isMobileReplay ? (
                  <MobileReplayViewer
                    ref={mobileReplayViewerRef}
                    events={events}
                    platform={replay.platform || 'mobile'}
                    className="min-h-[400px]"
                    onTimeUpdate={setCurrentOffsetMs}
                    onDurationReady={setRecordingDurationMs}
                  />
                ) : hasRecording ? (
                  <ReplayPlayer
                    ref={replayPlayerRef}
                    events={events}
                    width={800}
                    height={450}
                    autoPlay={false}
                    className="rounded border overflow-hidden"
                    onTimeUpdate={setCurrentOffsetMs}
                    onDurationReady={setRecordingDurationMs}
                  />
                ) : (
                  <div className="flex items-center justify-center h-[400px] rounded border bg-muted">
                    <p className="text-muted-foreground">No recording data available for this replay</p>
                  </div>
                )}
              </CardContent>
            </Card>

            {timeline ? (
              <Card>
                <CardHeader>
                  <CardTitle className="text-base">Timeline</CardTitle>
                </CardHeader>
                <CardContent>
                  <ReplayTimelinePanel
                    items={timelineItems}
                    currentOffsetMs={currentOffsetMs}
                    onSeek={handleSeek}
                  />
                </CardContent>
              </Card>
            ) : null}
          </div>

          <div className="space-y-4">
            <Card>
              <CardHeader>
                <CardTitle className="text-base">Details</CardTitle>
              </CardHeader>
              <CardContent className="space-y-4">
                <div>
                  <div className="text-xs font-medium text-muted-foreground mb-1">Duration</div>
                  <div>{formatDuration(replay.durationMs)}</div>
                </div>
                <div>
                  <div className="text-xs font-medium text-muted-foreground mb-1">Started</div>
                  <div className="text-sm">{formatDate(replay.startedAt)}</div>
                </div>
                <div>
                  <div className="text-xs font-medium text-muted-foreground mb-1">Finished</div>
                  <div className="text-sm">{formatDate(replay.finishedAt)}</div>
                </div>
                {replay.environment && (
                  <div>
                    <div className="text-xs font-medium text-muted-foreground mb-1">Environment</div>
                    <Badge variant="outline">{replay.environment}</Badge>
                  </div>
                )}
                {replay.release && (
                  <div>
                    <div className="text-xs font-medium text-muted-foreground mb-1">Release</div>
                    <div className="text-sm">{replay.release}</div>
                  </div>
                )}
              </CardContent>
            </Card>

            <Card>
              <CardHeader>
                <CardTitle className="text-base flex items-center gap-2">
                  <User className="h-4 w-4" />
                  User
                </CardTitle>
              </CardHeader>
              <CardContent>
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
              </CardContent>
            </Card>

            <Card>
              <CardHeader>
                <CardTitle className="text-base flex items-center gap-2">
                  <Monitor className="h-4 w-4" />
                  Browser / OS
                </CardTitle>
              </CardHeader>
              <CardContent>
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
              </CardContent>
            </Card>

            {replay.urls && replay.urls.length > 0 && (
              <Card>
                <CardHeader>
                  <CardTitle className="text-base flex items-center gap-2">
                    <Globe className="h-4 w-4" />
                    Visited URLs
                  </CardTitle>
                </CardHeader>
                <CardContent>
                  <ul className="space-y-1 text-sm max-h-32 overflow-y-auto">
                    {replay.urls.map((url, i) => (
                      <li key={i} className="truncate text-muted-foreground" title={url}>
                        {url}
                      </li>
                    ))}
                  </ul>
                </CardContent>
              </Card>
            )}

            {replay.traceIds && replay.traceIds.length > 0 && (
              <Card>
                <CardHeader>
                  <CardTitle className="text-base flex items-center gap-2">
                    <DatabaseZap className="h-4 w-4" />
                    Trace IDs
                  </CardTitle>
                </CardHeader>
                <CardContent>
                  <ul className="space-y-1 font-mono text-xs text-muted-foreground">
                    {replay.traceIds.map((traceId) => (
                      <li key={traceId} className="truncate" title={traceId}>
                        {traceId}
                      </li>
                    ))}
                  </ul>
                </CardContent>
              </Card>
            )}

            {replay.tags && Object.keys(replay.tags).length > 0 && (
              <Card>
                <CardHeader>
                  <CardTitle className="text-base flex items-center gap-2">
                    <Tag className="h-4 w-4" />
                    Tags
                  </CardTitle>
                </CardHeader>
                <CardContent>
                  <div className="flex flex-wrap gap-2">
                    {Object.entries(replay.tags).map(([key, value]) => (
                      <Badge key={key} variant="secondary" className="font-mono text-xs">
                        {key}: {value}
                      </Badge>
                    ))}
                  </div>
                </CardContent>
              </Card>
            )}
          </div>
        </div>
      </div>
    </div>
  )
}
