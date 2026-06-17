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

import {useRef, useState, useCallback, useMemo} from 'react'
import type {ReplayTimelineItem} from '@/lib/api'
import {cn} from '@/lib/utils'
import {
  AlertTriangle,
  Pause,
  Play,
  RotateCcw,
  SkipForward,
  Maximize2,
  Minimize2,
} from 'lucide-react'

export interface ReplayTimelineScrubberProps {
  readonly currentOffsetMs: number
  readonly durationMs: number
  readonly isPlaying: boolean
  readonly items: ReplayTimelineItem[]
  readonly onSeek: (offsetMs: number) => void
  readonly onPlayPause: () => void
  readonly onSpeedChange?: (speed: number) => void
  readonly speed?: number
  readonly className?: string
  readonly onFullscreenToggle?: () => void
  readonly isFullscreen?: boolean
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

// Tokenized marker colors — never raw ramp values (semantic tokens only).
function markerBgClass(type: ReplayTimelineItem['type']): string {
  switch (type) {
    case 'error':
      return 'bg-danger-solid'
    case 'transaction':
      return 'bg-info-solid'
    case 'span':
      return 'bg-chart-4'
    default:
      return 'bg-muted-foreground'
  }
}

function markerTextClass(type: ReplayTimelineItem['type']): string {
  switch (type) {
    case 'error':
      return 'text-danger-fg'
    case 'transaction':
      return 'text-info-fg'
    case 'span':
      return 'text-chart-4'
    default:
      return 'text-muted-foreground'
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

  const hoveredMarkerData = useMemo(
    () => markers.find((m) => m.id === hoveredMarker) ?? null,
    [markers, hoveredMarker]
  )

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
        globalThis.removeEventListener('mousemove', onMove)
        globalThis.removeEventListener('mouseup', onUp)
      }
      globalThis.addEventListener('mousemove', onMove)
      globalThis.addEventListener('mouseup', onUp)
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

  const errorItems = useMemo(() => items.filter((item) => item.type === 'error'), [items])

  const handleNextError = useCallback(() => {
    const next = errorItems.find((item) => item.offsetMs > currentOffsetMs + 200) ?? errorItems[0]
    if (next) onSeek(next.offsetMs)
  }, [errorItems, currentOffsetMs, onSeek])

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
        role="slider"
        tabIndex={0}
        aria-label="Replay progress"
        aria-valuemin={0}
        aria-valuemax={durationMs}
        aria-valuenow={currentOffsetMs}
        aria-valuetext={`${formatClock(currentOffsetMs)} / ${formatClock(durationMs)}`}
        className={cn(
          'relative h-6 w-full cursor-pointer group/scrubber mb-2',
          isDragging && 'select-none'
        )}
        onMouseDown={handleTrackMouseDown}
        onKeyDown={(e) => {
          if (e.target instanceof HTMLElement && e.target.closest('button')) return
          const step = durationMs * 0.05
          if (e.key === 'ArrowLeft') { e.preventDefault(); onSeek(Math.max(0, currentOffsetMs - step)) }
          if (e.key === 'ArrowRight') { e.preventDefault(); onSeek(Math.min(durationMs, currentOffsetMs + step)) }
          if (e.key === 'Home') { e.preventDefault(); onSeek(0) }
          if (e.key === 'End') { e.preventDefault(); onSeek(durationMs) }
        }}
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
              className={cn(
                'absolute top-1/2 z-20 h-2.5 w-2.5 -translate-x-1/2 -translate-y-1/2 appearance-none rounded-full border-[1.5px] border-background p-0 transition-transform hover:scale-150 focus:outline-none focus-visible:ring-2 focus-visible:ring-primary',
                markerBgClass(marker.type),
                isHovered && 'scale-150 ring-2 ring-primary/40'
              )}
              style={{ left: `${marker.percent}%` }}
              aria-label={`${marker.title} at ${formatClock(marker.offsetMs)}`}
            />
          )
        })}

        {/* Hover card — anchored to the track and clamped to the track edges, so it
            stays on-screen for any marker position regardless of the track's width. */}
        {hoveredMarkerData && (
          <div
            className="pointer-events-none absolute z-50 w-52 rounded-lg border bg-popover p-2 text-left overflow-hidden"
            style={{
              bottom: 'calc(100% + 10px)',
              left: `clamp(0px, calc(${hoveredMarkerData.percent}% - 6.5rem), calc(100% - 13rem))`,
            }}
          >
            <div className={cn('text-xs font-semibold truncate min-w-0', markerTextClass(hoveredMarkerData.type))} title={hoveredMarkerData.title}>
              {hoveredMarkerData.title}
            </div>
            <div className="text-[10px] text-muted-foreground mt-0.5 font-mono">
              {formatClock(hoveredMarkerData.offsetMs)}
              {hoveredMarkerData.durationMs != null && hoveredMarkerData.durationMs > 0 && (
                <span className="ml-1.5 opacity-70">
                  ({hoveredMarkerData.durationMs >= 1000
                    ? `${(hoveredMarkerData.durationMs / 1000).toFixed(2)}s`
                    : `${Math.round(hoveredMarkerData.durationMs)}ms`})
                </span>
              )}
            </div>
            {hoveredMarkerData.description && (
              <div className="text-[10px] text-muted-foreground mt-0.5 truncate">
                {hoveredMarkerData.description}
              </div>
            )}
          </div>
        )}

        {/* Playhead */}
        <div
          className="absolute top-1/2 z-30 -translate-x-1/2 -translate-y-1/2 pointer-events-none"
          style={{ left: `${playheadPercent}%` }}
        >
          <div className="w-3 h-3 rounded-full bg-primary border-2 border-background" />
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

        {/* Jump to next error */}
        {errorItems.length > 0 && (
          <button
            type="button"
            onClick={handleNextError}
            className="inline-flex items-center gap-1 rounded border border-danger-border bg-danger-bg px-2 py-0.5 text-xs font-medium text-danger-fg transition-colors hover:bg-danger-bg/70"
            title="Jump to next error"
          >
            <AlertTriangle className="h-3 w-3" />
            Next error
          </button>
        )}

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
