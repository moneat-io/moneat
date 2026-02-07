import {forwardRef, useEffect, useImperativeHandle, useRef} from 'react'
import rrwebPlayer from 'rrweb-player'
import 'rrweb-player/dist/style.css'

interface ReplayPlayerProps {
  events: unknown[]
  width?: number
  height?: number
  autoPlay?: boolean
  className?: string
  onTimeUpdate?: (offsetMs: number) => void
  onDurationReady?: (durationMs: number) => void
}

export interface ReplayPlayerHandle {
  seekTo: (offsetMs: number) => void
}

const rrwebPlayerRef = forwardRef<ReplayPlayerHandle, ReplayPlayerProps>(function ReplayPlayer(
  {
    events,
    width = 1024,
    height = 576,
    autoPlay = true,
    className = '',
    onTimeUpdate,
    onDurationReady,
  },
  ref
) {
  const containerRef = useRef<HTMLDivElement>(null)
  const playerRef = useRef<InstanceType<typeof rrwebPlayer> | null>(null)
  const onTimeUpdateRef = useRef(onTimeUpdate)
  const onDurationReadyRef = useRef(onDurationReady)
  onTimeUpdateRef.current = onTimeUpdate
  onDurationReadyRef.current = onDurationReady

  useImperativeHandle(ref, () => ({
    seekTo(offsetMs: number) {
      const p = playerRef.current
      if (p && typeof p.goto === 'function') {
        p.goto(offsetMs, false)
      }
    },
  }), [])

  useEffect(() => {
    if (!containerRef.current || !events || events.length === 0) return

    const target = document.createElement('div')
    target.className = 'rrweb-player-wrapper'
    containerRef.current.innerHTML = ''
    containerRef.current.appendChild(target)

    let player: InstanceType<typeof rrwebPlayer> | null = null
    try {
      player = new rrwebPlayer({
        target,
        props: {
          events: events as never[],
          width,
          height,
          autoPlay,
          showController: true,
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
      console.error('Failed to initialize replay player:', error)
      target.innerHTML = `
        <div style="display: flex; align-items: center; justify-content: center; height: 400px; background: #f5f5f5; border-radius: 8px; padding: 2rem;">
          <div style="text-align: center; max-width: 400px;">
            <p style="color: #666; margin-bottom: 0.5rem; font-weight: 500;">Unable to load replay</p>
            <p style="color: #999; font-size: 0.875rem;">The replay data format is not supported by the web player.</p>
          </div>
        </div>
      `
      return
    }

    let rafId = 0
    const tick = () => {
      const cb = onTimeUpdateRef.current
      if (cb && player) {
        try {
          const replayer = player.getReplayer?.()
          if (replayer && typeof replayer.getCurrentTime === 'function') {
            const ms = replayer.getCurrentTime()
            cb(ms)
          }
        } catch {
          // ignore
        }
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
      if (containerRef.current && target.parentNode === containerRef.current) {
        containerRef.current.removeChild(target)
      }
    }
  }, [events, width, height, autoPlay])

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
