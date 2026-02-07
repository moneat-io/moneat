import {forwardRef, useEffect, useImperativeHandle, useMemo, useRef, useState} from 'react'
import {Pause, Play} from 'lucide-react'

interface MobileReplayViewerProps {
  events: unknown[]
  platform: string
  className?: string
  onTimeUpdate?: (offsetMs: number) => void
  onDurationReady?: (durationMs: number) => void
}

export interface MobileReplayViewerHandle {
  seekTo: (offsetMs: number) => void
}

interface ReplayEvent {
  type: number
  timestamp: number
  segment_id?: number | string
  data?: {
    tag?: string
    payload?: Record<string, unknown>
  }
}

interface MobileVideoEvent {
  type: 'mobile_replay_video'
  segment_id?: number
  mime_type?: string
  data?: string
  size?: number
}

interface MobileVideoSegment extends MobileVideoEvent {
  resolvedSegmentId: number
}

interface GlobalSeekTarget {
  segmentIndex: number
  localTimeMs: number
}

interface TimelineMarker {
  id: string
  title: string
  detail: string | undefined
  globalTimeMs: number
  percent: number
  markerBackgroundColor: string
  tooltipAccentColor: string
}

function isReplayEvent(value: unknown): value is ReplayEvent {
  if (typeof value !== 'object' || value === null) return false
  if (!('type' in value) || !('timestamp' in value)) return false

  const record = value as Record<string, unknown>
  return typeof record.type === 'number' && typeof record.timestamp === 'number'
}

function isMobileVideoEvent(value: unknown): value is MobileVideoEvent {
  if (typeof value !== 'object' || value === null) return false

  const record = value as Record<string, unknown>
  return record.type === 'mobile_replay_video' && typeof record.data === 'string' && record.data.length > 0
}

function formatClock(ms: number): string {
  const totalSeconds = Math.max(0, Math.floor(ms / 1000))
  const hours = Math.floor(totalSeconds / 3600)
  const minutes = Math.floor((totalSeconds % 3600) / 60)
  const seconds = totalSeconds % 60

  if (hours > 0) {
    return `${hours}:${String(minutes).padStart(2, '0')}:${String(seconds).padStart(2, '0')}`
  }

  return `${minutes}:${String(seconds).padStart(2, '0')}`
}

function formatBreadcrumbDetail(payload: Record<string, unknown>, category: string): string | undefined {
  const cat = category.toLowerCase()

  if (payload.message && typeof payload.message === 'string') {
    return payload.message
  }

  // UI Lifecycle
  if (cat.includes('ui.lifecycle')) {
    const screen = payload.screen ?? 'Screen'
    const state = payload.state ?? ''
    return `${screen}: ${state}`
  }

  // UI Click
  if (cat.includes('ui.click')) {
    const viewClass = (payload['view.class'] as string)?.split('.').pop() ?? ''
    const viewId = payload['view.id'] ?? ''
    return viewId ? `Clicked ${viewClass} (${viewId})` : `Clicked ${viewClass}`
  }

  // Navigation
  if (cat.includes('navigation')) {
    const from = payload.from ?? ''
    const to = payload.to ?? ''
    return `${from} → ${to}`
  }

  // HTTP
  if (cat.includes('http') || cat.includes('network')) {
    const parts: string[] = []
    if (payload.method) parts.push(String(payload.method))
    if (payload.url) parts.push(String(payload.url))
    if (payload.status_code != null) parts.push(String(payload.status_code))
    return parts.length > 0 ? parts.join(' ') : undefined
  }

  // Device
  if (cat.includes('device')) {
    if (payload.action && String(payload.action).includes('BATTERY')) {
      const level = payload.level ?? ''
      const charging = payload.charging ? ' (charging)' : ''
      return `Battery ${level}%${charging}`
    }
    return payload.action ? String(payload.action) : undefined
  }

  if (payload.action && typeof payload.action === 'string') return payload.action
  if (payload.type && typeof payload.type === 'string') return payload.type

  return undefined
}

function getEventDetail(event: ReplayEvent): string | undefined {
  if (event.type !== 5 || !event.data?.payload) return undefined
  const payload = event.data.payload as Record<string, unknown>

  if (event.data.tag === 'breadcrumb') {
    const category = (payload.category ?? payload.type ?? '') as string
    return formatBreadcrumbDetail(payload, category)
  }

  const action = payload.action
  if (typeof action === 'string' && action.length > 0) return action

  const category = payload.category
  if (typeof category === 'string' && category.length > 0) return category

  const type = payload.type
  if (typeof type === 'string' && type.length > 0) return type

  return undefined
}

function getEventCategory(event: ReplayEvent): string {
  if (event.type === 3) return 'ui.touch'
  if (event.type === 4) return 'ui.lifecycle'

  if (event.type === 5) {
    const tag = event.data?.tag
    if (tag === 'breadcrumb') {
      const category = event.data?.payload?.category
      if (typeof category === 'string' && category.length > 0) {
        return category.toLowerCase()
      }
    }
    if (typeof tag === 'string' && tag.length > 0) return tag.toLowerCase()
  }

  return 'event'
}

function getCategoryMarkerColors(category: string): {
  markerBackgroundColor: string
  tooltipAccentColor: string
} {
  const cat = category.toLowerCase()
  if (cat.includes('lifecycle')) {
    return { markerBackgroundColor: '#34d399', tooltipAccentColor: '#34d399' }
  }
  if (cat.includes('click') || cat.includes('touch')) {
    return { markerBackgroundColor: '#a78bfa', tooltipAccentColor: '#a78bfa' }
  }
  if (cat.includes('navigation')) {
    return { markerBackgroundColor: '#60a5fa', tooltipAccentColor: '#60a5fa' }
  }
  if (cat.includes('action')) {
    return { markerBackgroundColor: '#fbbf24', tooltipAccentColor: '#fbbf24' }
  }
  if (cat.includes('http') || cat.includes('network')) {
    return { markerBackgroundColor: '#2dd4bf', tooltipAccentColor: '#2dd4bf' }
  }
  if (cat.includes('device')) {
    return { markerBackgroundColor: '#fb923c', tooltipAccentColor: '#fb923c' }
  }
  if (cat.includes('message') || cat.includes('log')) {
    return { markerBackgroundColor: '#f472b6', tooltipAccentColor: '#f472b6' }
  }
  return { markerBackgroundColor: '#94a3b8', tooltipAccentColor: '#94a3b8' }
}

function formatCategoryAsTitle(category: string): string {
  if (!category) return 'Breadcrumb'
  // user_navigation → User Navigation, ui.click → UI Click, etc.
  return category
    .split(/[._-]/)
    .map((part) => part.charAt(0).toUpperCase() + part.slice(1).toLowerCase())
    .join(' ')
}

function getEventTitle(event: ReplayEvent): string {
  if (event.type === 3) return 'Touch/Input'
  if (event.type === 4) return 'Viewport Metadata'

  if (event.type === 5) {
    const tag = event.data?.tag
    if (tag === 'breadcrumb') {
      const payload = event.data?.payload
      const category = payload?.category ?? payload?.type
      if (typeof category === 'string' && category.length > 0) {
        return formatCategoryAsTitle(category)
      }
      const message = payload?.message
      if (typeof message === 'string' && message.length > 0) return message
      return 'Breadcrumb'
    }
    if (tag === 'video') return 'Video Metadata'
    if (typeof tag === 'string' && tag.length > 0) return tag
    return 'Custom Event'
  }

  return `Event Type ${event.type}`
}

function getEventSegmentId(event: ReplayEvent): number | undefined {
  const segmentId = event.segment_id
  if (typeof segmentId === 'number') return segmentId
  if (typeof segmentId === 'string') {
    const parsed = Number(segmentId)
    if (Number.isFinite(parsed)) return parsed
  }

  const payloadSegment = event.data?.payload?.segmentId
  if (typeof payloadSegment === 'number') return payloadSegment
  if (typeof payloadSegment === 'string') {
    const parsed = Number(payloadSegment)
    if (Number.isFinite(parsed)) return parsed
  }

  return undefined
}

function getVideoDurationMs(event: ReplayEvent): number | undefined {
  if (event.type !== 5 || event.data?.tag !== 'video') return undefined
  const duration = event.data?.payload?.duration
  if (typeof duration === 'number' && Number.isFinite(duration) && duration > 0) return duration
  if (typeof duration === 'string') {
    const parsed = Number(duration)
    if (Number.isFinite(parsed) && parsed > 0) return parsed
  }
  return undefined
}

function isMeaningfulEvent(event: ReplayEvent): boolean {
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
      ) return false
    }
  }

  return true
}

function clamp(value: number, min: number, max: number): number {
  return Math.min(Math.max(value, min), max)
}

function findSeekTarget(globalMs: number, durationsMs: number[]): GlobalSeekTarget {
  if (durationsMs.length === 0) return { segmentIndex: 0, localTimeMs: 0 }

  let offset = 0
  for (let i = 0; i < durationsMs.length; i++) {
    const segmentDuration = durationsMs[i]
    if (globalMs < offset + segmentDuration || i === durationsMs.length - 1) {
      return { segmentIndex: i, localTimeMs: Math.max(0, globalMs - offset) }
    }
    offset += segmentDuration
  }

  return { segmentIndex: durationsMs.length - 1, localTimeMs: durationsMs[durationsMs.length - 1] }
}

export const MobileReplayViewer = forwardRef<MobileReplayViewerHandle, MobileReplayViewerProps>(function MobileReplayViewer(
  { events, platform, className = '', onTimeUpdate, onDurationReady },
  ref
) {
  const videoSegments = useMemo(
    (): MobileVideoSegment[] =>
      events
        .map((event, index) => ({ event, index }))
        .filter((entry): entry is { event: MobileVideoEvent; index: number } => isMobileVideoEvent(entry.event))
        .sort((a, b) => {
          const aSegment = typeof a.event.segment_id === 'number' ? a.event.segment_id : Number.MAX_SAFE_INTEGER
          const bSegment = typeof b.event.segment_id === 'number' ? b.event.segment_id : Number.MAX_SAFE_INTEGER
          if (aSegment !== bSegment) return aSegment - bSegment
          return a.index - b.index
        })
        .map((entry) => entry.event)
        .map((segment, index) => ({
          ...segment,
          resolvedSegmentId: typeof segment.segment_id === 'number' ? segment.segment_id : index,
        })),
    [events]
  )

  const replayEvents = useMemo(
    () =>
      events
        .filter((event): event is ReplayEvent => isReplayEvent(event))
        .sort((a, b) => a.timestamp - b.timestamp),
    [events]
  )

  const meaningfulEvents = useMemo(
    () => replayEvents.filter((event) => isMeaningfulEvent(event)),
    [replayEvents]
  )

  const knownSegmentDurationsMs = useMemo(() => {
    const map = new Map<number, number>()
    replayEvents.forEach((event) => {
      const segmentId = getEventSegmentId(event)
      const duration = getVideoDurationMs(event)
      if (segmentId === undefined || duration === undefined) return
      map.set(segmentId, duration)
    })
    return map
  }, [replayEvents])

  const meaningfulEventCountsBySegment = useMemo(() => {
    const counts = new Map<number, number>()
    meaningfulEvents.forEach((event) => {
      const segmentId = getEventSegmentId(event)
      if (segmentId === undefined) return
      counts.set(segmentId, (counts.get(segmentId) ?? 0) + 1)
    })
    return counts
  }, [meaningfulEvents])

  const hideIdleSegments = true
  const autoStitchEnabled = true
  const [selectedSegmentIdx, setSelectedSegmentIdx] = useState(0)
  const [isPlaying, setIsPlaying] = useState(false)
  const [currentSegmentTimeMs, setCurrentSegmentTimeMs] = useState(0)
  const [hoveredTimelineMarkerId, setHoveredTimelineMarkerId] = useState<string | null>(null)
  const [loadedSegmentDurationsMs, setLoadedSegmentDurationsMs] = useState<Record<number, number>>({})

  const videoRef = useRef<HTMLVideoElement>(null)
  const timelineTrackRef = useRef<HTMLDivElement>(null)
  const shouldResumeAfterSwitchRef = useRef(false)
  const pendingSeekRef = useRef<{ segmentId: number; localTimeMs: number } | null>(null)
  const onTimeUpdateRef = useRef(onTimeUpdate)
  onTimeUpdateRef.current = onTimeUpdate
  const seekToGlobalTimeRef = useRef<(ms: number) => void>(() => {})

  const filteredVideoSegments = useMemo(() => {
    if (!hideIdleSegments) return videoSegments
    if (meaningfulEventCountsBySegment.size === 0) return videoSegments

    const activeSegments = videoSegments.filter(
      (segment) => (meaningfulEventCountsBySegment.get(segment.resolvedSegmentId) ?? 0) > 0
    )

    return activeSegments.length > 0 ? activeSegments : videoSegments
  }, [hideIdleSegments, meaningfulEventCountsBySegment, videoSegments])

  const segmentDurationsMs = useMemo(
    () =>
      filteredVideoSegments.map((segment) => {
        const loaded = loadedSegmentDurationsMs[segment.resolvedSegmentId]
        if (typeof loaded === 'number' && Number.isFinite(loaded) && loaded > 0) return loaded

        const fromEvent = knownSegmentDurationsMs.get(segment.resolvedSegmentId)
        if (typeof fromEvent === 'number' && Number.isFinite(fromEvent) && fromEvent > 0) return fromEvent

        return 5000
      }),
    [filteredVideoSegments, loadedSegmentDurationsMs, knownSegmentDurationsMs]
  )

  const cumulativeOffsetsMs = useMemo(() => {
    const offsets: number[] = []
    let running = 0
    segmentDurationsMs.forEach((duration) => {
      offsets.push(running)
      running += duration
    })
    return offsets
  }, [segmentDurationsMs])

  const totalDurationMs = useMemo(
    () => segmentDurationsMs.reduce((sum, duration) => sum + duration, 0),
    [segmentDurationsMs]
  )

  useEffect(() => {
    if (totalDurationMs > 0) {
      onDurationReady?.(totalDurationMs)
    }
  }, [totalDurationMs, onDurationReady])

  const selectedVideo = filteredVideoSegments[selectedSegmentIdx]
  const videoSrc = selectedVideo
    ? `data:${selectedVideo.mime_type || 'video/mp4'};base64,${selectedVideo.data}`
    : null

  const currentGlobalTimeMs = useMemo(() => {
    if (filteredVideoSegments.length === 0 || totalDurationMs <= 0) return 0
    const offset = cumulativeOffsetsMs[selectedSegmentIdx] ?? 0
    return clamp(offset + currentSegmentTimeMs, 0, totalDurationMs)
  }, [cumulativeOffsetsMs, currentSegmentTimeMs, filteredVideoSegments.length, selectedSegmentIdx, totalDurationMs])

  const playheadPercent = useMemo(() => {
    if (totalDurationMs <= 0) return 0
    return clamp((currentGlobalTimeMs / totalDurationMs) * 100, 0, 100)
  }, [currentGlobalTimeMs, totalDurationMs])

  const filteredSegmentIndexById = useMemo(() => {
    const map = new Map<number, number>()
    filteredVideoSegments.forEach((segment, index) => {
      map.set(segment.resolvedSegmentId, index)
    })
    return map
  }, [filteredVideoSegments])

  const segmentStartTimestampById = useMemo(() => {
    const starts = new Map<number, number>()
    replayEvents.forEach((event) => {
      const segmentId = getEventSegmentId(event)
      if (segmentId === undefined) return
      const existing = starts.get(segmentId)
      if (existing === undefined || event.timestamp < existing) {
        starts.set(segmentId, event.timestamp)
      }
    })
    return starts
  }, [replayEvents])

  const timelineMarkers = useMemo((): TimelineMarker[] => {
    if (totalDurationMs <= 0) return []
    const markers: TimelineMarker[] = []

    meaningfulEvents.forEach((event, index) => {
      const segmentId = getEventSegmentId(event)
      if (segmentId === undefined) return

      const segmentIndex = filteredSegmentIndexById.get(segmentId)
      if (segmentIndex === undefined) return

      const segmentOffset = cumulativeOffsetsMs[segmentIndex] ?? 0
      const segmentDuration = segmentDurationsMs[segmentIndex] ?? 0
      const segmentStartTimestamp = segmentStartTimestampById.get(segmentId)

      const localTimeMs =
        segmentStartTimestamp !== undefined
          ? clamp(event.timestamp - segmentStartTimestamp, 0, segmentDuration)
          : 0

      const globalTimeMs = clamp(segmentOffset + localTimeMs, 0, totalDurationMs)
      const percent = clamp((globalTimeMs / totalDurationMs) * 100, 0, 100)
      const colors = getCategoryMarkerColors(getEventCategory(event))

      markers.push({
        id: `${segmentId}-${event.timestamp}-${index}`,
        title: getEventTitle(event),
        detail: getEventDetail(event),
        globalTimeMs,
        percent,
        markerBackgroundColor: colors.markerBackgroundColor,
        tooltipAccentColor: colors.tooltipAccentColor,
      })
    })

    return markers.sort((a, b) => a.globalTimeMs - b.globalTimeMs)
  }, [
    cumulativeOffsetsMs,
    filteredSegmentIndexById,
    meaningfulEvents,
    segmentDurationsMs,
    segmentStartTimestampById,
    totalDurationMs,
  ])

  useEffect(() => {
    if (selectedSegmentIdx >= filteredVideoSegments.length) {
      setSelectedSegmentIdx(0)
      setCurrentSegmentTimeMs(0)
    }
  }, [selectedSegmentIdx, filteredVideoSegments.length])

  const seekToGlobalTime = (requestedMs: number) => {
    if (filteredVideoSegments.length === 0 || totalDurationMs <= 0) return

    const targetMs = clamp(requestedMs, 0, totalDurationMs)
    const target = findSeekTarget(targetMs, segmentDurationsMs)
    const targetSegment = filteredVideoSegments[target.segmentIndex]
    if (!targetSegment) return

    if (target.segmentIndex === selectedSegmentIdx) {
      const video = videoRef.current
      if (video) {
        video.currentTime = target.localTimeMs / 1000
      }
      setCurrentSegmentTimeMs(target.localTimeMs)
      return
    }

    pendingSeekRef.current = {
      segmentId: targetSegment.resolvedSegmentId,
      localTimeMs: target.localTimeMs,
    }
    shouldResumeAfterSwitchRef.current = isPlaying
    setSelectedSegmentIdx(target.segmentIndex)
    setCurrentSegmentTimeMs(target.localTimeMs)
  }
  seekToGlobalTimeRef.current = seekToGlobalTime

  useImperativeHandle(ref, () => ({
    seekTo(offsetMs: number) {
      seekToGlobalTimeRef.current(offsetMs)
    },
  }), [])

  useEffect(() => {
    onTimeUpdateRef.current?.(currentGlobalTimeMs)
  }, [currentGlobalTimeMs])

  const seekFromTimelinePointer = (clientX: number) => {
    const track = timelineTrackRef.current
    if (!track || totalDurationMs <= 0) return

    const rect = track.getBoundingClientRect()
    if (rect.width <= 0) return
    const percent = clamp((clientX - rect.left) / rect.width, 0, 1)
    seekToGlobalTime(percent * totalDurationMs)
  }

  return (
    <div className={`space-y-4 ${className}`}>
      {videoSrc ? (
        <div className="space-y-3">
            <div className="rounded border bg-black overflow-hidden">
              <video
                ref={videoRef}
                key={`${selectedVideo.segment_id ?? selectedVideo.resolvedSegmentId}-${selectedSegmentIdx}`}
                src={videoSrc}
                preload="metadata"
                className="w-full max-h-[520px] bg-black"
                aria-label={`${platform} replay video`}
                onLoadedMetadata={() => {
                  const video = videoRef.current
                  if (!video || !selectedVideo) return

                  const loadedDurationMs = video.duration * 1000
                  if (Number.isFinite(loadedDurationMs) && loadedDurationMs > 0) {
                    setLoadedSegmentDurationsMs((prev) => ({
                      ...prev,
                      [selectedVideo.resolvedSegmentId]: loadedDurationMs,
                    }))
                  }

                  const pending = pendingSeekRef.current
                  if (pending && pending.segmentId === selectedVideo.resolvedSegmentId) {
                    video.currentTime = pending.localTimeMs / 1000
                    setCurrentSegmentTimeMs(pending.localTimeMs)
                    pendingSeekRef.current = null
                  } else {
                    setCurrentSegmentTimeMs(video.currentTime * 1000)
                  }

                  if (shouldResumeAfterSwitchRef.current) {
                    void video.play().catch(() => {
                      // Browser may block autoplay without user gesture.
                    })
                    shouldResumeAfterSwitchRef.current = false
                  }
                }}
                onTimeUpdate={() => {
                  const video = videoRef.current
                  if (!video) return
                  setCurrentSegmentTimeMs(video.currentTime * 1000)
                }}
                onPlay={() => setIsPlaying(true)}
                onPause={() => setIsPlaying(false)}
                onEnded={() => {
                  if (!autoStitchEnabled || selectedSegmentIdx >= filteredVideoSegments.length - 1) {
                    setIsPlaying(false)
                    return
                  }

                  shouldResumeAfterSwitchRef.current = true
                  setSelectedSegmentIdx((idx) => idx + 1)
                  setCurrentSegmentTimeMs(0)
                }}
              />
            </div>

            <div className="flex items-center gap-3">
              <button
                type="button"
                onClick={() => {
                  const video = videoRef.current
                  if (!video) return
                  if (video.paused) {
                    void video.play().catch(() => {
                      // Browser may block autoplay without user gesture.
                    })
                  } else {
                    video.pause()
                  }
                }}
                className="h-9 w-9 shrink-0 inline-flex items-center justify-center rounded border bg-background hover:bg-muted"
                aria-label={isPlaying ? 'Pause replay' : 'Play replay'}
                title={isPlaying ? 'Pause' : 'Play'}
              >
                {isPlaying ? <Pause className="h-4 w-4" /> : <Play className="h-4 w-4 fill-current" />}
              </button>

              <div className="shrink-0 text-xs text-muted-foreground font-mono">
                {formatClock(currentGlobalTimeMs)} / {formatClock(totalDurationMs)}
              </div>

              <div
                ref={timelineTrackRef}
                className="relative h-9 flex-1 min-w-0 cursor-pointer group/timeline"
                onClick={(event) => seekFromTimelinePointer(event.clientX)}
                title="Timeline (click to seek)"
              >
                {/* Track background */}
                <div
                  className="absolute top-1/2 left-0 right-0 z-0 h-2 -translate-y-1/2 rounded-full border border-border"
                  style={{ backgroundColor: 'hsl(var(--muted))' }}
                />

                {/* Progress fill */}
                <div
                  className="absolute top-1/2 left-0 z-[5] h-2 -translate-y-1/2 rounded-full"
                  style={{ width: `${playheadPercent}%`, backgroundColor: 'hsl(var(--muted-foreground))' }}
                />

                {/* Playhead line */}
                <div
                  className="absolute top-1/2 z-30 h-5 w-[2px] -translate-x-1/2 -translate-y-1/2 rounded-full"
                  style={{ left: `${playheadPercent}%`, backgroundColor: 'hsl(var(--foreground))' }}
                />

                {timelineMarkers.map((marker) => {
                  const isHovered = hoveredTimelineMarkerId === marker.id
                  return (
                    <button
                      key={marker.id}
                      type="button"
                      onClick={(event) => {
                        event.stopPropagation()
                        seekToGlobalTime(marker.globalTimeMs)
                      }}
                      onMouseEnter={() => setHoveredTimelineMarkerId(marker.id)}
                      onMouseLeave={() => setHoveredTimelineMarkerId((current) => (current === marker.id ? null : current))}
                      onFocus={() => setHoveredTimelineMarkerId(marker.id)}
                      onBlur={() => setHoveredTimelineMarkerId((current) => (current === marker.id ? null : current))}
                      className="absolute top-1/2 z-20 -translate-x-1/2 -translate-y-1/2 rounded-full hover:scale-125 transition-transform appearance-none p-0 focus:outline-none"
                      style={{
                        left: `${marker.percent}%`,
                        width: 16,
                        height: 16,
                        backgroundColor: marker.markerBackgroundColor,
                        border: '1.5px solid hsl(var(--background))',
                        boxShadow: isHovered ? `0 0 6px 2px ${marker.markerBackgroundColor}` : `0 0 0 0.5px rgba(0,0,0,0.3)`,
                      }}
                      aria-label={`${marker.title} at ${formatClock(marker.globalTimeMs)}`}
                    >
                      {isHovered && (
                        <div className="pointer-events-none absolute left-1/2 z-40 w-56 -translate-x-1/2 rounded-lg border bg-popover p-2.5 text-left shadow-lg" style={{ bottom: 'calc(100% + 10px)' }}>
                          <div className="text-xs font-semibold" style={{ color: marker.tooltipAccentColor }}>
                            {marker.title}
                          </div>
                          <div className="text-[11px] text-muted-foreground mt-1 font-mono">{formatClock(marker.globalTimeMs)}</div>
                          {marker.detail && (
                            <div className="text-[11px] text-muted-foreground mt-1 break-words">{marker.detail}</div>
                          )}
                        </div>
                      )}
                    </button>
                  )
                })}
              </div>
            </div>

            <div className="text-xs text-muted-foreground">
              {timelineMarkers.length} important event{timelineMarkers.length === 1 ? '' : 's'}
            </div>
        </div>
      ) : (
        <div className="rounded border bg-muted/30 p-4 text-sm text-muted-foreground">
          Replay video data was not present in this segment. Showing only meaningful interaction events.
        </div>
      )}
      </div>
  )
})
