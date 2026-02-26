// Moneat - observability platform
// Copyright (C) 2026 Moneat
//
// This program is free software: you can redistribute it and/or modify
// it under the terms of the GNU Affero General Public License as published by
// the Free Software Foundation, either version 3 of the License, or
// (at your option) any later version.

import {useMemo, useState, useCallback, useRef} from 'react'
import {Badge} from '@/components/ui/badge'
import {Button} from '@/components/ui/button'
import {RotateCcw} from 'lucide-react'

interface FlamegraphFrame {
  name: string
  value: number
  children: FlamegraphFrame[]
  self?: number
}

interface Props {
  frames?: FlamegraphFrame[]
  emptyMessage?: string
}

const COLORS = [
  'hsl(14, 80%, 52%)',   // red-orange
  'hsl(28, 85%, 50%)',   // orange
  'hsl(42, 80%, 48%)',   // amber
  'hsl(55, 72%, 42%)',   // gold
  'hsl(130, 45%, 42%)',  // green
  'hsl(160, 50%, 40%)',  // teal
  'hsl(200, 60%, 48%)',  // blue
  'hsl(220, 55%, 52%)',  // indigo
  'hsl(260, 45%, 55%)',  // purple
  'hsl(290, 40%, 52%)',  // violet
  'hsl(340, 55%, 50%)',  // rose
  'hsl(5, 70%, 52%)',    // crimson
  'hsl(175, 50%, 38%)',  // cyan
  'hsl(240, 40%, 55%)',  // slate blue
]

function hashColor(name: string): string {
  let hash = 0
  for (let i = 0; i < name.length; i++) {
    hash = (hash * 31 + name.charCodeAt(i)) | 0
  }
  return COLORS[Math.abs(hash) % COLORS.length]
}

interface FlatFrame {
  frame: FlamegraphFrame
  depth: number
  x: number
  width: number
}

function flattenFrames(
  frames: FlamegraphFrame[],
  totalValue: number,
  depth: number = 0,
  x: number = 0,
): FlatFrame[] {
  const result: FlatFrame[] = []
  let currentX = x

  for (const frame of frames) {
    const width = (frame.value / totalValue) * 100
    if (width < 0.1) {
      currentX += width
      continue
    }

    result.push({frame, depth, x: currentX, width})
    result.push(
      ...flattenFrames(frame.children, totalValue, depth + 1, currentX),
    )
    currentX += width
  }
  return result
}

function updateTooltip(
  el: HTMLDivElement | null,
  ff: FlatFrame | null,
  x: number,
  y: number,
) {
  if (!el) return
  if (!ff) {
    el.style.display = 'none'
    return
  }
  el.style.display = 'block'
  el.style.left = `${x + 12}px`
  el.style.top = `${y - 10}px`

  const nameEl = el.querySelector<HTMLElement>('[data-tip="name"]')
  const samplesEl = el.querySelector<HTMLElement>('[data-tip="samples"]')
  const pctEl = el.querySelector<HTMLElement>('[data-tip="pct"]')
  const selfEl = el.querySelector<HTMLElement>('[data-tip="self"]')
  const barEl = el.querySelector<HTMLElement>('[data-tip="bar"]')

  if (nameEl) nameEl.textContent = ff.frame.name
  if (samplesEl) samplesEl.textContent = ff.frame.value.toLocaleString()
  if (pctEl) pctEl.textContent = `${ff.width.toFixed(1)}%`
  if (selfEl) {
    if (ff.frame.self != null && ff.frame.self > 0) {
      selfEl.style.display = ''
      const v = selfEl.querySelector<HTMLElement>('span')
      if (v) v.textContent = ff.frame.self.toLocaleString()
    } else {
      selfEl.style.display = 'none'
    }
  }
  if (barEl) {
    barEl.style.width = `${Math.max(ff.width, 4)}%`
    barEl.style.backgroundColor = hashColor(ff.frame.name)
  }
}

export function Flamegraph({frames, emptyMessage}: Props) {
  const [focusFrame, setFocusFrame] = useState<FlamegraphFrame | null>(null)
  const hoveredRef = useRef<FlatFrame | null>(null)
  const tooltipRef = useRef<HTMLDivElement>(null)

  const totalValue = useMemo(() => {
    if (!frames?.length) return 0
    return frames.reduce((sum, f) => sum + f.value, 0)
  }, [frames])

  const flatFrames = useMemo(() => {
    if (!frames?.length) return []
    const rootFrames = focusFrame ? [focusFrame] : frames
    const rootTotal = focusFrame?.value ?? totalValue
    return flattenFrames(rootFrames, rootTotal)
  }, [frames, focusFrame, totalValue])

  const maxDepth = useMemo(
    () => Math.max(0, ...flatFrames.map((f) => f.depth)),
    [flatFrames],
  )

  const handleZoomIn = useCallback(
    (frame: FlamegraphFrame) => {
      if (frame.children.length > 0) {
        setFocusFrame(frame)
      }
    },
    [],
  )

  const handleReset = useCallback(() => {
    setFocusFrame(null)
  }, [])

  if (!frames?.length) {
    return (
      <div className="text-center py-12 text-muted-foreground">
        <p className="font-medium">
          {emptyMessage || 'No profile data available'}
        </p>
        <p className="text-sm mt-1">
          Upload a pprof file or wait for profile data to be collected.
        </p>
      </div>
    )
  }

  const rowHeight = 20
  const svgHeight = (maxDepth + 1) * rowHeight + 4

  return (
    <div className="space-y-2">
      {focusFrame && (
        <div className="flex items-center gap-2">
          <Badge variant="secondary" className="text-xs">
            Zoomed: {focusFrame.name}
          </Badge>
          <Button variant="ghost" size="sm" onClick={handleReset}>
            <RotateCcw className="h-3 w-3 mr-1" />
            Reset
          </Button>
        </div>
      )}

      <div className="border rounded-lg overflow-x-auto">
        <svg
          width="100%"
          height={svgHeight}
          viewBox={`0 0 100 ${svgHeight}`}
          preserveAspectRatio="none"
          className="block"
          onMouseMove={(e) => {
            updateTooltip(tooltipRef.current, hoveredRef.current, e.clientX, e.clientY)
          }}
          onMouseLeave={() => {
            hoveredRef.current = null
            updateTooltip(tooltipRef.current, null, 0, 0)
          }}
        >
          {flatFrames.map((ff, i) => {
            const y = svgHeight - (ff.depth + 1) * rowHeight - 2
            return (
              <g
                key={`${ff.depth}-${ff.x}-${i}`}
                onMouseEnter={() => { hoveredRef.current = ff }}
                onClick={() => handleZoomIn(ff.frame)}
                className="cursor-pointer"
              >
                <rect
                  x={ff.x}
                  y={y}
                  width={Math.max(ff.width - 0.05, 0.05)}
                  height={rowHeight - 1}
                  fill={hashColor(ff.frame.name)}
                  rx={0.15}
                  className="hover:opacity-80"
                />
                {ff.width > 3 && (
                  <text
                    x={ff.x + 0.2}
                    y={y + rowHeight / 2 + 1}
                    fontSize={0.55}
                    fill="white"
                    dominantBaseline="middle"
                    className="pointer-events-none select-none"
                  >
                    {ff.frame.name.length > ff.width * 2
                      ? ff.frame.name.slice(0, Math.floor(ff.width * 2)) + '…'
                      : ff.frame.name}
                  </text>
                )}
              </g>
            )
          })}
        </svg>
      </div>

      {/* Tooltip — always mounted, updated imperatively to avoid re-renders */}
      <div
        ref={tooltipRef}
        className="fixed z-50 pointer-events-none max-w-sm"
        style={{display: 'none'}}
      >
        <div className="bg-popover border rounded-lg px-3 py-2 shadow-lg text-xs space-y-1">
          <p data-tip="name" className="font-semibold text-popover-foreground truncate" />
          <div className="flex items-center gap-3 text-muted-foreground">
            <span>
              <span data-tip="samples" className="text-popover-foreground font-medium" />{' '}
              samples
            </span>
            <span data-tip="pct" className="text-popover-foreground font-medium" />
            <span data-tip="self" style={{display: 'none'}}>
              self:{' '}
              <span className="text-popover-foreground font-medium" />
            </span>
          </div>
          <div
            data-tip="bar"
            className="h-1 rounded-full mt-1"
          />
        </div>
      </div>
    </div>
  )
}
