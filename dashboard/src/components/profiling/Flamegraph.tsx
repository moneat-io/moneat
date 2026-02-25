// Moneat - observability platform
// Copyright (C) 2026 Moneat
//
// This program is free software: you can redistribute it and/or modify
// it under the terms of the GNU Affero General Public License as published by
// the Free Software Foundation, either version 3 of the License, or
// (at your option) any later version.

import {useMemo, useState, useCallback} from 'react'
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
  /** URL to fetch pprof data, or pre-parsed frame tree */
  frames?: FlamegraphFrame[]
  /** Fallback message if no data */
  emptyMessage?: string
}

// Color palette for flamegraph bars
const COLORS = [
  'hsl(20, 80%, 55%)',   // warm orange
  'hsl(30, 85%, 50%)',   // orange
  'hsl(40, 80%, 50%)',   // amber
  'hsl(50, 75%, 45%)',   // gold
  'hsl(15, 75%, 55%)',   // red-orange
  'hsl(25, 80%, 52%)',   // deep orange
  'hsl(35, 78%, 48%)',   // warm amber
  'hsl(45, 72%, 47%)',   // honey
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

export function Flamegraph({frames, emptyMessage}: Props) {
  const [focusFrame, setFocusFrame] = useState<FlamegraphFrame | null>(null)
  const [hoveredFrame, setHoveredFrame] = useState<FlatFrame | null>(null)

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
      {/* Controls */}
      <div className="flex items-center gap-2">
        {focusFrame && (
          <>
            <Badge variant="secondary" className="text-xs">
              Zoomed: {focusFrame.name}
            </Badge>
            <Button variant="ghost" size="sm" onClick={handleReset}>
              <RotateCcw className="h-3 w-3 mr-1" />
              Reset
            </Button>
          </>
        )}
      </div>

      {/* Hover tooltip */}
      {hoveredFrame && (
        <div className="text-xs bg-popover border rounded-md px-2 py-1 shadow-sm">
          <span className="font-medium">{hoveredFrame.frame.name}</span>
          <span className="text-muted-foreground ml-2">
            {hoveredFrame.frame.value.toLocaleString()} samples
            ({hoveredFrame.width.toFixed(1)}%)
          </span>
        </div>
      )}

      {/* Flamegraph SVG */}
      <div className="border rounded-lg overflow-x-auto">
        <svg
          width="100%"
          height={svgHeight}
          viewBox={`0 0 100 ${svgHeight}`}
          preserveAspectRatio="none"
          className="block"
        >
          {flatFrames.map((ff, i) => {
            const y = svgHeight - (ff.depth + 1) * rowHeight - 2
            return (
              <g
                key={`${ff.depth}-${ff.x}-${i}`}
                onMouseEnter={() => setHoveredFrame(ff)}
                onMouseLeave={() => setHoveredFrame(null)}
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
    </div>
  )
}
