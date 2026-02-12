import {createFileRoute, Link, redirect} from '@tanstack/react-router'
import {useQuery} from '@tanstack/react-query'
import {useMemo, useRef, useState} from 'react'
import {api} from '@/lib/api'
import {formatRelativeTime} from '@/lib/utils'
import {ReplayPlayer, type ReplayPlayerHandle} from '@/components/replay-player'
import {MobileReplayViewer, type MobileReplayViewerHandle} from '@/components/mobile-replay-viewer'
import {ReplayTimelinePanel} from '@/components/replay-timeline-panel'
import {Card, CardContent, CardHeader, CardTitle} from '@/components/ui/card'
import {Badge} from '@/components/ui/badge'
import {
  Activity,
  AlertCircle,
  ArrowUpRight,
  ChevronLeft,
  Clock3,
  DatabaseZap,
  Globe,
  Layers,
  Monitor,
  Play,
  Smartphone,
  Tag,
  User,
} from 'lucide-react'

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
    <div>
      <div className="px-3 py-3 lg:px-5 lg:py-4">
        {/* Breadcrumb nav */}
        <nav className="mb-3 flex items-center gap-2 text-sm">
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

        {/* Replay Header - colored border top */}
        <div className={`mb-3 bg-card rounded-lg border border-t-2 px-4 py-3 sm:px-5 sm:py-3.5 ${replay.errorCount > 0 ? 'border-t-red-500' : 'border-t-indigo-500'}`}>
          <div className="flex flex-col sm:flex-row sm:items-start sm:justify-between gap-3">
            <div className="flex-1 min-w-0">
              <div className="flex items-center gap-2 mb-1.5 flex-wrap">
                <Play className="h-4 w-4 text-indigo-500 dark:text-indigo-400" />
                <h2 className="text-lg sm:text-xl font-bold leading-tight">Session Replay</h2>
                {replay.errorCount > 0 && (
                  <Badge variant="destructive" className="flex items-center gap-1">
                    <AlertCircle className="h-3 w-3" />
                    {replay.errorCount} error{replay.errorCount !== 1 ? 's' : ''}
                  </Badge>
                )}
                {platformLabel && (
                  <Badge variant="outline" className="flex items-center gap-1">
                    {(replay.platform === 'android' || replay.platform === 'ios') ? (
                      <Smartphone className="h-3 w-3" />
                    ) : (
                      <Monitor className="h-3 w-3" />
                    )}
                    {platformLabel}
                  </Badge>
                )}
                {replay.environment && (
                  <Badge variant="outline">{replay.environment}</Badge>
                )}
              </div>
              <p className="text-sm text-muted-foreground break-words [overflow-wrap:anywhere]">
                {replay.user?.email || replay.user?.username || replay.user?.id || 'Anonymous user'}
                {replay.release ? ` — ${replay.release}` : ''}
              </p>
            </div>
          </div>

          {/* Compact inline stats */}
          <div className="flex flex-wrap items-center gap-x-4 gap-y-1.5 mt-2.5 pt-2.5 border-t text-sm">
            <span className="inline-flex items-center gap-1.5">
              <Clock3 className="h-3.5 w-3.5 text-muted-foreground" />
              <span className="font-semibold text-indigo-600 dark:text-indigo-400">{formatDuration(durationMs)}</span>
              <span className="text-muted-foreground">duration</span>
            </span>
            {replay.errorCount > 0 && (
              <span className="inline-flex items-center gap-1.5">
                <span className="font-semibold text-red-600 dark:text-red-400">{replay.errorCount}</span>
                <span className="text-muted-foreground">error{replay.errorCount !== 1 ? 's' : ''}</span>
              </span>
            )}
            {replay.urls && replay.urls.length > 0 && (
              <span className="inline-flex items-center gap-1.5">
                <span className="font-semibold text-blue-600 dark:text-blue-400">{replay.urls.length}</span>
                <span className="text-muted-foreground">URL{replay.urls.length !== 1 ? 's' : ''}</span>
              </span>
            )}
            {replay.segmentCount > 0 && (
              <span className="inline-flex items-center gap-1.5">
                <span className="font-semibold text-violet-600 dark:text-violet-400">{replay.segmentCount}</span>
                <span className="text-muted-foreground">segment{replay.segmentCount !== 1 ? 's' : ''}</span>
              </span>
            )}
            <span className="text-muted-foreground/40 hidden sm:inline">|</span>
            <span className="inline-flex items-center gap-1.5 text-muted-foreground">
              Started <span className="text-foreground font-medium">{formatRelativeTime(replay.startedAt)}</span>
            </span>
            <span className="inline-flex items-center gap-1.5 text-muted-foreground">
              Finished <span className="text-foreground font-medium">{formatRelativeTime(replay.finishedAt)}</span>
            </span>
          </div>
        </div>

        <div className="grid grid-cols-1 lg:grid-cols-5 gap-3">
          {/* Main column */}
          <div className="lg:col-span-3 space-y-3">
            <Card>
              <CardHeader className="pb-2 px-3 pt-3">
                <CardTitle className="flex items-center gap-2 text-sm font-medium">
                  <Play className="h-4 w-4 text-indigo-500 dark:text-indigo-400" />
                  Recording
                </CardTitle>
              </CardHeader>
              <CardContent className="px-3 pb-3">
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
                <CardHeader className="pb-2 px-3 pt-3">
                  <CardTitle className="flex items-center gap-2 text-sm font-medium">
                    <Activity className="h-4 w-4 text-amber-500 dark:text-amber-400" />
                    Timeline
                  </CardTitle>
                </CardHeader>
                <CardContent className="px-3 pb-3">
                  <ReplayTimelinePanel
                    items={timelineItems}
                    currentOffsetMs={currentOffsetMs}
                    onSeek={handleSeek}
                  />
                </CardContent>
              </Card>
            ) : null}
          </div>

          {/* Sidebar */}
          <div className="lg:col-span-2 space-y-3">
            {/* Session Details */}
            <Card>
              <CardHeader className="pb-2 px-3 pt-3">
                <CardTitle className="flex items-center gap-2 text-sm font-medium">
                  <Layers className="h-4 w-4 text-slate-500 dark:text-slate-400" />
                  Session Details
                </CardTitle>
              </CardHeader>
              <CardContent className="px-3 pb-3">
                <div className="space-y-1.5 text-sm">
                  <div className="flex justify-between gap-2">
                    <span className="text-muted-foreground flex-shrink-0">Duration</span>
                    <span className="font-medium">{formatDuration(durationMs)}</span>
                  </div>
                  <div className="flex justify-between gap-2">
                    <span className="text-muted-foreground flex-shrink-0">Started</span>
                    <span className="text-xs">{formatDate(replay.startedAt)}</span>
                  </div>
                  <div className="flex justify-between gap-2">
                    <span className="text-muted-foreground flex-shrink-0">Finished</span>
                    <span className="text-xs">{formatDate(replay.finishedAt)}</span>
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
              </CardContent>
            </Card>

            {/* User */}
            <Card>
              <CardHeader className="pb-2 px-3 pt-3">
                <CardTitle className="flex items-center gap-2 text-sm font-medium">
                  <User className="h-4 w-4 text-violet-500 dark:text-violet-400" />
                  User
                </CardTitle>
              </CardHeader>
              <CardContent className="px-3 pb-3">
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

            {/* Browser / OS */}
            <Card>
              <CardHeader className="pb-2 px-3 pt-3">
                <CardTitle className="flex items-center gap-2 text-sm font-medium">
                  <Monitor className="h-4 w-4 text-cyan-500 dark:text-cyan-400" />
                  Browser / OS
                </CardTitle>
              </CardHeader>
              <CardContent className="px-3 pb-3">
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

            {/* Visited URLs */}
            {replay.urls && replay.urls.length > 0 && (
              <Card>
                <CardHeader className="pb-2 px-3 pt-3">
                  <CardTitle className="flex items-center gap-2 text-sm font-medium">
                    <Globe className="h-4 w-4 text-blue-500 dark:text-blue-400" />
                    Visited URLs ({replay.urls.length})
                  </CardTitle>
                </CardHeader>
                <CardContent className="px-3 pb-3">
                  <ul className="space-y-1 text-sm max-h-40 overflow-y-auto">
                    {replay.urls.map((url, i) => (
                      <li key={i} className="truncate text-muted-foreground" title={url}>
                        {url}
                      </li>
                    ))}
                  </ul>
                </CardContent>
              </Card>
            )}

            {/* Error IDs - linked to issues */}
            {replay.errorIds && replay.errorIds.length > 0 && (
              <Card>
                <CardHeader className="pb-2 px-3 pt-3">
                  <CardTitle className="flex items-center gap-2 text-sm font-medium">
                    <AlertCircle className="h-4 w-4 text-red-500 dark:text-red-400" />
                    Errors ({replay.errorIds.length})
                  </CardTitle>
                </CardHeader>
                <CardContent className="px-3 pb-3">
                  <div className="space-y-1.5 max-h-[200px] overflow-auto">
                    {replay.errorIds.map((errorId) => (
                      <Link
                        key={errorId}
                        to="/issues/$issueId"
                        params={{ issueId: errorId }}
                        className="flex items-center justify-between rounded border p-2 transition-colors hover:bg-accent group"
                      >
                        <span className="font-mono text-xs text-muted-foreground truncate min-w-0">{errorId}</span>
                        <ArrowUpRight className="h-3.5 w-3.5 text-muted-foreground flex-shrink-0 ml-2 opacity-0 group-hover:opacity-100 transition-opacity" />
                      </Link>
                    ))}
                  </div>
                </CardContent>
              </Card>
            )}

            {/* Trace IDs */}
            {replay.traceIds && replay.traceIds.length > 0 && (
              <Card>
                <CardHeader className="pb-2 px-3 pt-3">
                  <CardTitle className="flex items-center gap-2 text-sm font-medium">
                    <DatabaseZap className="h-4 w-4 text-cyan-500 dark:text-cyan-400" />
                    Trace IDs ({replay.traceIds.length})
                  </CardTitle>
                </CardHeader>
                <CardContent className="px-3 pb-3">
                  <ul className="space-y-1 font-mono text-xs text-muted-foreground max-h-[200px] overflow-auto">
                    {replay.traceIds.map((traceId) => (
                      <li key={traceId} className="truncate" title={traceId}>
                        {traceId}
                      </li>
                    ))}
                  </ul>
                </CardContent>
              </Card>
            )}

            {/* Tags */}
            {replay.tags && Object.keys(replay.tags).length > 0 && (
              <Card>
                <CardHeader className="pb-2 px-3 pt-3">
                  <CardTitle className="flex items-center gap-2 text-sm font-medium">
                    <Tag className="h-4 w-4 text-slate-500 dark:text-slate-400" />
                    Tags
                    <Badge variant="secondary" className="ml-1 text-[10px] px-1 py-0">{Object.keys(replay.tags).length}</Badge>
                  </CardTitle>
                </CardHeader>
                <CardContent className="px-3 pb-3">
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
                </CardContent>
              </Card>
            )}
          </div>
        </div>
      </div>
    </div>
  )
}
