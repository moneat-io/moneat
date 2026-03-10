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

import {forwardRef, useEffect, useImperativeHandle, useLayoutEffect, useRef} from 'react'
import rrwebPlayer from 'rrweb-player'
import 'rrweb-player/dist/style.css'
import {formatErrorForLogging} from '@/lib/api'

type RrwebReplayer = { getCurrentTime?: () => number; timer?: { isActive?: () => boolean } }

function reportCurrentTime(player: InstanceType<typeof rrwebPlayer>, cb: (ms: number) => void): void {
  try {
    const replayer = player.getReplayer?.() as RrwebReplayer | undefined
    if (replayer && typeof replayer.getCurrentTime === 'function') {
      cb(replayer.getCurrentTime())
    }
  } catch {
    // ignore
  }
}

function checkPlayingState(
  player: InstanceType<typeof rrwebPlayer>,
  lastKnown: boolean,
  onChange: (playing: boolean) => void,
): boolean {
  try {
    const replayer = player.getReplayer?.() as RrwebReplayer | undefined
    if (replayer) {
      const timer = replayer.timer
      const isActive = typeof timer?.isActive === 'function' ? timer.isActive() : lastKnown
      if (isActive !== lastKnown) {
        onChange(isActive)
        return isActive
      }
    }
  } catch {
    // ignore
  }
  return lastKnown
}

interface ReplayPlayerProps {
  events: unknown[]
  width?: number
  height?: number
  autoPlay?: boolean
  showController?: boolean
  className?: string
  onTimeUpdate?: (offsetMs: number) => void
  onDurationReady?: (durationMs: number) => void
  onPlayingChange?: (playing: boolean) => void
}

export interface ReplayPlayerHandle {
  seekTo: (offsetMs: number) => void
  play: () => void
  pause: () => void
  setSpeed: (speed: number) => void
}

const rrwebPlayerRef = forwardRef<ReplayPlayerHandle, ReplayPlayerProps>(function ReplayPlayer(
  {
    events,
    width = 1024,
    height = 576,
    autoPlay = true,
    showController = true,
    className = '',
    onTimeUpdate,
    onDurationReady,
    onPlayingChange,
  },
  ref
) {
  const containerRef = useRef<HTMLDivElement>(null)
  const playerRef = useRef<InstanceType<typeof rrwebPlayer> | null>(null)
  const onTimeUpdateRef = useRef(onTimeUpdate)
  const onDurationReadyRef = useRef(onDurationReady)
  const onPlayingChangeRef = useRef(onPlayingChange)
  useLayoutEffect(() => {
    onTimeUpdateRef.current = onTimeUpdate
    onDurationReadyRef.current = onDurationReady
    onPlayingChangeRef.current = onPlayingChange
  })

  useImperativeHandle(ref, () => ({
    seekTo(offsetMs: number) {
      const p = playerRef.current
      if (p && typeof p.goto === 'function') {
        p.goto(offsetMs, false)
      }
    },
    play() {
      const p = playerRef.current
      if (p && typeof p.play === 'function') {
        p.play()
      }
    },
    pause() {
      const p = playerRef.current
      if (p && typeof p.pause === 'function') {
        p.pause()
      }
    },
    setSpeed(speed: number) {
      const p = playerRef.current
      if (p && typeof p.setSpeed === 'function') {
        p.setSpeed(speed)
      }
    },
  }), [])

  useEffect(() => {
    if (!containerRef.current || !events || events.length === 0) return

    const container = containerRef.current
    const target = document.createElement('div')
    target.className = 'rrweb-player-wrapper'
    while (container.firstChild) {
      container.firstChild.remove()
    }
    container.appendChild(target)

    let player: InstanceType<typeof rrwebPlayer> | null = null
    try {
      player = new rrwebPlayer({
        target,
        props: {
          events: events as never[],
          width,
          height,
          autoPlay,
          showController,
        },
      })
      playerRef.current = player

      const replayer = player.getReplayer?.()
      if (replayer && typeof replayer.getMetaData === 'function') {
        try {
          const meta = replayer.getMetaData()
          const startTime = meta?.startTime ?? 0
          const endTime = meta?.endTime ?? 0
          if (typeof startTime === 'number' && typeof endTime === 'number' && endTime > startTime) {
            onDurationReadyRef.current?.(endTime - startTime)
          }
        } catch {
          // ignore
        }
      }
    } catch (error) {
      console.error('Failed to initialize replay player:', formatErrorForLogging(error))
      while (target.firstChild) {
        target.firstChild.remove()
      }
      const errorContainer = document.createElement('div')
      errorContainer.style.cssText = 'display: flex; align-items: center; justify-content: center; height: 400px; background: #f5f5f5; border-radius: 8px; padding: 2rem;'
      const errorContent = document.createElement('div')
      errorContent.style.cssText = 'text-align: center; max-width: 400px;'
      const errorTitle = document.createElement('p')
      errorTitle.style.cssText = 'color: #666; margin-bottom: 0.5rem; font-weight: 500;'
      errorTitle.textContent = 'Unable to load replay'
      const errorDesc = document.createElement('p')
      errorDesc.style.cssText = 'color: #999; font-size: 0.875rem;'
      errorDesc.textContent = 'The replay data format is not supported by the web player.'
      errorContent.appendChild(errorTitle)
      errorContent.appendChild(errorDesc)
      errorContainer.appendChild(errorContent)
      target.appendChild(errorContainer)
      return
    }

    // Track play/pause state for external scrubber
    let lastKnownPlaying = autoPlay
    let rafId = 0
    const tick = () => {
      const cb = onTimeUpdateRef.current
      if (cb && player) reportCurrentTime(player, cb)

      if (player && onPlayingChangeRef.current) {
        lastKnownPlaying = checkPlayingState(player, lastKnownPlaying, onPlayingChangeRef.current)
      }
      rafId = requestAnimationFrame(tick)
    }
    rafId = requestAnimationFrame(tick)

    return () => {
      cancelAnimationFrame(rafId)
      playerRef.current = null
      try {
        const p = player as { destroy?: () => void }
        if (p && typeof p.destroy === 'function') {
          p.destroy()
        }
      } catch {
        // ignore
      }
      if (container && target.parentNode === container) {
        target.remove()
      }
    }
  }, [events, width, height, autoPlay, showController])

  if (!events || events.length === 0) {
    return (
      <div className={`flex items-center justify-center rounded border bg-muted p-8 ${className}`}>
        <p className="text-sm text-muted-foreground">No replay data available</p>
      </div>
    )
  }

  return <div ref={containerRef} className={className} />
})

export const ReplayPlayer = rrwebPlayerRef
