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

    const player = new rrwebPlayer({
      target,
      props: {
        events: events as never[],
        width,
        height,
        autoPlay,
        showController: true,
      },
    })

    return () => {
      try {
        if ('destroy' in player && typeof (player as { destroy: () => void }).destroy === 'function') {
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
