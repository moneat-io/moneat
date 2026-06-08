// Moneat - observability platform
// Copyright (C) 2026 Moneat
//
// This program is free software: you can redistribute it and/or modify
// it under the terms of the GNU Affero General Public License as published by
// the Free Software Foundation, either version 3 of the License, or
// (at your option) any later version.

import {useEffect, useRef, useState, useCallback} from 'react'

export interface MiniRect {
  depth: number
  x: number
  width: number
  color: string
}

interface Props {
  rects: MiniRect[]
  rows: number
  chartHeight: number
  scrollTop: number
  viewportHeight: number
  onScrollToFraction: (fraction: number) => void
}

const MINIMAP_HEIGHT = 56

export function FlamegraphMinimap({
  rects,
  rows,
  chartHeight,
  scrollTop,
  viewportHeight,
  onScrollToFraction,
}: Props) {
  const canvasRef = useRef<HTMLCanvasElement>(null)
  const wrapRef = useRef<HTMLDivElement>(null)
  const [width, setWidth] = useState(600)
  const draggingRef = useRef(false)

  useEffect(() => {
    const el = wrapRef.current
    if (!el) return
    const observer = new ResizeObserver((entries) => {
      const w = entries[0]?.contentRect.width
      if (w) setWidth(Math.max(120, Math.floor(w)))
    })
    observer.observe(el)
    return () => observer.disconnect()
  }, [])

  useEffect(() => {
    const canvas = canvasRef.current
    if (!canvas) return
    const dpr = globalThis.window?.devicePixelRatio ?? 1
    canvas.width = width * dpr
    canvas.height = MINIMAP_HEIGHT * dpr
    const ctx = canvas.getContext('2d')
    if (!ctx) return
    ctx.setTransform(dpr, 0, 0, dpr, 0, 0)
    ctx.clearRect(0, 0, width, MINIMAP_HEIGHT)

    const rowH = MINIMAP_HEIGHT / Math.max(rows, 1)
    for (const r of rects) {
      ctx.fillStyle = r.color
      const px = (r.x / 100) * width
      const pw = Math.max((r.width / 100) * width, 0.5)
      ctx.fillRect(px, r.depth * rowH, pw, Math.max(rowH - 0.5, 1))
    }

    // Viewport indicator.
    if (chartHeight > 0 && viewportHeight < chartHeight) {
      const top = (scrollTop / chartHeight) * MINIMAP_HEIGHT
      const h = (viewportHeight / chartHeight) * MINIMAP_HEIGHT
      ctx.fillStyle = 'rgba(255,255,255,0.12)'
      ctx.fillRect(0, top, width, h)
      ctx.strokeStyle = 'rgba(255,255,255,0.6)'
      ctx.lineWidth = 1
      ctx.strokeRect(0.5, top + 0.5, width - 1, Math.max(h - 1, 1))
    }
  }, [rects, rows, width, scrollTop, viewportHeight, chartHeight])

  const scrollFromEvent = useCallback(
    (clientY: number) => {
      const canvas = canvasRef.current
      if (!canvas) return
      const rect = canvas.getBoundingClientRect()
      const fraction = (clientY - rect.top) / rect.height
      onScrollToFraction(Math.min(Math.max(fraction, 0), 1))
    },
    [onScrollToFraction],
  )

  return (
    <div ref={wrapRef} className="w-full">
      <canvas
        ref={canvasRef}
        className="w-full rounded border cursor-pointer block"
        style={{height: MINIMAP_HEIGHT}}
        onPointerDown={(e) => {
          draggingRef.current = true
          e.currentTarget.setPointerCapture(e.pointerId)
          scrollFromEvent(e.clientY)
        }}
        onPointerMove={(e) => {
          if (draggingRef.current) scrollFromEvent(e.clientY)
        }}
        onPointerUp={(e) => {
          draggingRef.current = false
          e.currentTarget.releasePointerCapture(e.pointerId)
        }}
        onPointerCancel={(e) => {
          draggingRef.current = false
          e.currentTarget.releasePointerCapture(e.pointerId)
        }}
      />
    </div>
  )
}
