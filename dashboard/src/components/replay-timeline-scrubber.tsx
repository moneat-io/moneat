import {useRef, useState, useCallback, useMemo} from 'react'
import type {ReplayTimelineItem} from '@/lib/api'
import {cn} from '@/lib/utils'
import {
  Pause,
  Play,
  RotateCcw,
  SkipForward,
  Maximize2,
  Minimize2,
} from 'lucide-react'

export interface ReplayTimelineScrubberProps {
  currentOffsetMs: number
  durationMs: number
  isPlaying: boolean
  items: ReplayTimelineItem[]
  onSeek: (offsetMs: number) => void
  onPlayPause: () => void
  onSpeedChange?: (speed: number) => void
  speed?: number
  className?: string
  onFullscreenToggle?: () => void
  isFullscreen?: boolean
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

function markerColor(type: ReplayTimelineItem['type']): string {
  switch (type) {
    case 'error':
      return '#ef4444'
    case 'transaction':
      return '#3b82f6'
    case 'span':
      return '#14b8a6'
    default:
      return '#94a3b8'
  }
}

const SPEEDS = [1, 1.5, 2, 4]

export function ReplayTimelineScrubber({
  currentOffsetMs,
  durationMs,
  isPlaying,
  items,
  onSeek,
  onPlayPause,
  onSpeedChange,
  speed = 1,
  className,
  onFullscreenToggle,
  isFullscreen,
}: ReplayTimelineScrubberProps) {
  const trackRef = useRef<HTMLDivElement>(null)
  const [isDragging, setIsDragging] = useState(false)
  const [hoveredMarker, setHoveredMarker] = useState<string | null>(null)

  const playheadPercent = durationMs > 0
    ? Math.max(0, Math.min((currentOffsetMs / durationMs) * 100, 100))
    : 0

  const markers = useMemo(() => {
    if (durationMs <= 0) return []
    return items.map((item) => ({
      ...item,
      percent: Math.max(0, Math.min((item.offsetMs / durationMs) * 100, 100)),
    }))
  }, [items, durationMs])

  const seekFromPointer = useCallback(
    (clientX: number) => {
      const track = trackRef.current
      if (!track || durationMs <= 0) return
      const rect = track.getBoundingClientRect()
      if (rect.width <= 0) return
      const pct = Math.max(0, Math.min((clientX - rect.left) / rect.width, 1))
      onSeek(pct * durationMs)
    },
    [durationMs, onSeek]
  )

  const handleTrackMouseDown = useCallback(
    (e: React.MouseEvent) => {
      e.preventDefault()
      setIsDragging(true)
      seekFromPointer(e.clientX)

      const onMove = (ev: MouseEvent) => seekFromPointer(ev.clientX)
      const onUp = () => {
        setIsDragging(false)
        window.removeEventListener('mousemove', onMove)
        window.removeEventListener('mouseup', onUp)
      }
      window.addEventListener('mousemove', onMove)
      window.addEventListener('mouseup', onUp)
    },
    [seekFromPointer]
  )

  const handleRewind10 = useCallback(() => {
    onSeek(Math.max(0, currentOffsetMs - 10_000))
  }, [currentOffsetMs, onSeek])

  const handleSkipForward = useCallback(() => {
    // Jump to next timeline event after current time
    const next = items.find((item) => item.offsetMs > currentOffsetMs + 500)
    if (next) {
      onSeek(next.offsetMs)
    } else {
      onSeek(Math.min(durationMs, currentOffsetMs + 10_000))
    }
  }, [items, currentOffsetMs, durationMs, onSeek])

  const handleCycleSpeed = useCallback(() => {
    if (!onSpeedChange) return
    const idx = SPEEDS.indexOf(speed)
    const nextIdx = (idx + 1) % SPEEDS.length
    onSpeedChange(SPEEDS[nextIdx])
  }, [speed, onSpeedChange])

  return (
    <div
      className={cn(
        'w-full border-t bg-card/95 backdrop-blur-sm px-3 py-2',
        className
      )}
    >
      {/* Scrubber track */}
      <div
        ref={trackRef}
        className={cn(
          'relative h-6 w-full cursor-pointer group/scrubber mb-2',
          isDragging && 'select-none'
        )}
        onMouseDown={handleTrackMouseDown}
      >
        {/* Track background */}
        <div className="absolute top-1/2 left-0 right-0 h-1.5 -translate-y-1/2 rounded-full bg-muted border border-border" />

        {/* Progress fill */}
        <div
          className="absolute top-1/2 left-0 h-1.5 -translate-y-1/2 rounded-full bg-primary/60 transition-[width] duration-75"
          style={{ width: `${playheadPercent}%` }}
        />

        {/* Event markers */}
        {markers.map((marker) => {
          const isHovered = hoveredMarker === marker.id
          return (
            <button
              key={marker.id}
              type="button"
              onClick={(e) => {
                e.stopPropagation()
                onSeek(marker.offsetMs)
              }}
              onMouseEnter={() => setHoveredMarker(marker.id)}
              onMouseLeave={() => setHoveredMarker((cur) => (cur === marker.id ? null : cur))}
              className="absolute top-1/2 z-20 -translate-x-1/2 -translate-y-1/2 rounded-full hover:scale-150 transition-transform appearance-none p-0 focus:outline-none focus-visible:ring-2 focus-visible:ring-primary"
              style={{
                left: `${marker.percent}%`,
                width: 10,
                height: 10,
                backgroundColor: markerColor(marker.type),
                border: '1.5px solid hsl(var(--background))',
                boxShadow: isHovered
                  ? `0 0 6px 2px ${markerColor(marker.type)}`
                  : '0 0 0 0.5px rgba(0,0,0,0.2)',
              }}
              aria-label={`${marker.title} at ${formatClock(marker.offsetMs)}`}
            >
              {isHovered && (
                <div
                  className="pointer-events-none absolute left-1/2 z-50 w-52 -translate-x-1/2 rounded-lg border bg-popover p-2 text-left shadow-lg"
                  style={{ bottom: 'calc(100% + 10px)' }}
                >
                  <div className="text-xs font-semibold" style={{ color: markerColor(marker.type) }}>
                    {marker.title}
                  </div>
                  <div className="text-[10px] text-muted-foreground mt-0.5 font-mono">
                    {formatClock(marker.offsetMs)}
                    {marker.durationMs != null && marker.durationMs > 0 && (
                      <span className="ml-1.5 opacity-70">
                        ({marker.durationMs >= 1000
                          ? `${(marker.durationMs / 1000).toFixed(2)}s`
                          : `${Math.round(marker.durationMs)}ms`})
                      </span>
                    )}
                  </div>
                  {marker.description && (
                    <div className="text-[10px] text-muted-foreground mt-0.5 truncate">
                      {marker.description}
                    </div>
                  )}
                </div>
              )}
            </button>
          )
        })}

        {/* Playhead */}
        <div
          className="absolute top-1/2 z-30 -translate-x-1/2 -translate-y-1/2 pointer-events-none"
          style={{ left: `${playheadPercent}%` }}
        >
          <div className="w-3 h-3 rounded-full bg-primary border-2 border-background shadow-md" />
        </div>
      </div>

      {/* Controls row */}
      <div className="flex items-center gap-2">
        {/* Play controls */}
        <div className="flex items-center gap-1">
          <button
            type="button"
            onClick={handleRewind10}
            className="h-7 w-7 inline-flex items-center justify-center rounded hover:bg-muted transition-colors"
            aria-label="Rewind 10 seconds"
            title="Rewind 10s"
          >
            <RotateCcw className="h-3.5 w-3.5" />
          </button>
          <button
            type="button"
            onClick={onPlayPause}
            className="h-8 w-8 inline-flex items-center justify-center rounded-full bg-primary text-primary-foreground hover:bg-primary/90 transition-colors"
            aria-label={isPlaying ? 'Pause' : 'Play'}
            title={isPlaying ? 'Pause' : 'Play'}
          >
            {isPlaying ? (
              <Pause className="h-3.5 w-3.5" />
            ) : (
              <Play className="h-3.5 w-3.5 ml-0.5" />
            )}
          </button>
          <button
            type="button"
            onClick={handleSkipForward}
            className="h-7 w-7 inline-flex items-center justify-center rounded hover:bg-muted transition-colors"
            aria-label="Skip to next event"
            title="Next event"
          >
            <SkipForward className="h-3.5 w-3.5" />
          </button>
        </div>

        {/* Time display */}
        <div className="text-xs font-mono text-muted-foreground select-none">
          <span className="text-foreground font-medium">{formatClock(currentOffsetMs)}</span>
          <span className="mx-1">/</span>
          <span>{formatClock(durationMs)}</span>
        </div>

        {/* Spacer */}
        <div className="flex-1" />

        {/* Speed */}
        {onSpeedChange && (
          <button
            type="button"
            onClick={handleCycleSpeed}
            className="text-xs font-mono px-2 py-0.5 rounded border hover:bg-muted transition-colors select-none"
            title="Playback speed"
          >
            {speed}x
          </button>
        )}

        {/* Fullscreen */}
        {onFullscreenToggle && (
          <button
            type="button"
            onClick={onFullscreenToggle}
            className="h-7 w-7 inline-flex items-center justify-center rounded hover:bg-muted transition-colors"
            aria-label={isFullscreen ? 'Exit fullscreen' : 'Fullscreen'}
            title={isFullscreen ? 'Exit fullscreen' : 'Fullscreen'}
          >
            {isFullscreen ? (
              <Minimize2 className="h-3.5 w-3.5" />
            ) : (
              <Maximize2 className="h-3.5 w-3.5" />
            )}
          </button>
        )}
      </div>
    </div>
  )
}
