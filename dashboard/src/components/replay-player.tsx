import { useEffect, useRef } from 'react'
import rrwebPlayer from 'rrweb-player'
import 'rrweb-player/dist/style.css'

interface ReplayPlayerProps {
  events: unknown[]
  width?: number
  height?: number
  autoPlay?: boolean
  className?: string
}

export function ReplayPlayer({
  events,
  width = 1024,
  height = 576,
  autoPlay = true,
  className = '',
}: ReplayPlayerProps) {
  const containerRef = useRef<HTMLDivElement>(null)

  useEffect(() => {
    if (!containerRef.current || !events || events.length === 0) return

    const target = document.createElement('div')
    target.className = 'rrweb-player-wrapper'
    containerRef.current.innerHTML = ''
    containerRef.current.appendChild(target)

    let player: unknown
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

    return () => {
      try {
        if (
          player &&
          typeof player === 'object' &&
          'destroy' in player &&
          typeof (player as { destroy: () => void }).destroy === 'function'
        ) {
          (player as { destroy: () => void }).destroy()
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
}
