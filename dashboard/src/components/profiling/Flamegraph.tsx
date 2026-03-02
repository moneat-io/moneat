// Moneat - observability platform
// Copyright (C) 2026 Moneat
//
// This program is free software: you can redistribute it and/or modify
// it under the terms of the GNU Affero General Public License as published by
// the Free Software Foundation, either version 3 of the License, or
// (at your option) any later version.

import {useMemo, useState, useCallback, useRef} from 'react'
import {Button} from '@/components/ui/button'
import {Input} from '@/components/ui/input'
import {RotateCcw, Search, ChevronRight} from 'lucide-react'

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

const PACKAGE_COLORS: [RegExp, string][] = [
  [/^java\./,            'hsl(220, 45%, 48%)'],
  [/^javax\./,           'hsl(220, 40%, 52%)'],
  [/^jdk\./,             'hsl(210, 40%, 46%)'],
  [/^sun\./,             'hsl(210, 35%, 50%)'],
  [/^com\.sun\./,        'hsl(210, 35%, 50%)'],
  [/^kotlin\./,          'hsl(275, 45%, 52%)'],
  [/^kotlinx\./,         'hsl(275, 40%, 56%)'],
  [/^io\.ktor\./,        'hsl(160, 50%, 40%)'],
  [/^io\.netty\./,       'hsl(175, 45%, 42%)'],
  [/^org\.jetbrains\./,  'hsl(290, 35%, 50%)'],
  [/^org\.apache\./,     'hsl(28, 70%, 48%)'],
  [/^org\.slf4j\./,      'hsl(42, 60%, 46%)'],
  [/^com\.zaxxer\./,     'hsl(130, 40%, 44%)'],
  [/^org\.postgresql\./, 'hsl(200, 55%, 44%)'],
  [/^com\.clickhouse\./, 'hsl(14, 60%, 50%)'],
  [/^redis\./,           'hsl(5, 65%, 48%)'],
  [/^io\.lettuce\./,     'hsl(5, 55%, 52%)'],
  [/^com\.moneat\./,     'hsl(142, 55%, 42%)'],
]

const FALLBACK_COLORS = [
  'hsl(14, 65%, 50%)',
  'hsl(28, 70%, 48%)',
  'hsl(42, 60%, 46%)',
  'hsl(55, 55%, 42%)',
  'hsl(130, 40%, 44%)',
  'hsl(160, 45%, 42%)',
  'hsl(200, 50%, 48%)',
  'hsl(240, 38%, 52%)',
  'hsl(260, 40%, 50%)',
  'hsl(340, 45%, 48%)',
]

function frameColor(name: string): string {
  for (const [pattern, color] of PACKAGE_COLORS) {
    if (pattern.test(name)) return color
  }
  let hash = 0
  for (let i = 0; i < name.length; i++) {
    hash = (hash * 31 + name.charCodeAt(i)) | 0
  }
  return FALLBACK_COLORS[Math.abs(hash) % FALLBACK_COLORS.length]
}

function packageOf(name: string): string {
  const lastDot = name.lastIndexOf('.')
  if (lastDot === -1) return name
  return name.slice(0, lastDot)
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
  if (totalValue <= 0) return []
  const result: FlatFrame[] = []
  let currentX = x

  for (const frame of frames) {
    const width = (frame.value / totalValue) * 100
    if (width < 0.05) {
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

interface TopFunction {
  name: string
  selfValue: number
  totalValue: number
  selfPercent: number
  totalPercent: number
}

function computeTopFunctions(
  frames: FlamegraphFrame[],
  totalSamples: number,
): TopFunction[] {
  const map = new Map<string, {self: number; total: number}>()

  function walk(f: FlamegraphFrame) {
    const existing = map.get(f.name) ?? {self: 0, total: 0}
    existing.total += f.value
    existing.self += f.self ?? 0
    map.set(f.name, existing)
    for (const child of f.children) walk(child)
  }
  for (const f of frames) walk(f)

  const entries = Array.from(map.entries()).map(([name, {self: selfValue, total: totalValue}]) => ({
    name,
    selfValue,
    totalValue,
    selfPercent: totalSamples > 0 ? (selfValue / totalSamples) * 100 : 0,
    totalPercent: totalSamples > 0 ? (totalValue / totalSamples) * 100 : 0,
  }))

  const hasSelfData = entries.some((f) => f.selfValue > 0)

  return entries
    .filter((f) => (hasSelfData ? f.selfPercent > 0.1 : f.totalPercent > 0.1))
    .sort((a, b) => (hasSelfData ? b.selfValue - a.selfValue : b.totalValue - a.totalValue))
    .slice(0, 20)
}

const ROW_HEIGHT = 22

export function Flamegraph({frames, emptyMessage}: Props) {
  const [focusStack, setFocusStack] = useState<FlamegraphFrame[]>([])
  const [searchQuery, setSearchQuery] = useState('')
  const [hoveredFrame, setHoveredFrame] = useState<FlatFrame | null>(null)
  const [showTopFunctions, setShowTopFunctions] = useState(false)
  const tooltipRef = useRef<HTMLDivElement>(null)
  const containerRef = useRef<HTMLDivElement>(null)

  const focusFrame = focusStack.length > 0 ? focusStack[focusStack.length - 1] : null

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

  const topFunctions = useMemo(() => {
    if (!frames?.length) return []
    return computeTopFunctions(frames, totalValue)
  }, [frames, totalValue])

  const searchLower = searchQuery.toLowerCase()
  const hasSearch = searchQuery.length > 1

  const matchCount = useMemo(() => {
    if (!hasSearch) return 0
    return flatFrames.filter((ff) =>
      ff.frame.name.toLowerCase().includes(searchLower),
    ).length
  }, [flatFrames, searchLower, hasSearch])

  const handleZoomIn = useCallback(
    (frame: FlamegraphFrame) => {
      if (frame.children.length > 0 && frame !== focusFrame) {
        setFocusStack((prev) => [...prev, frame])
      }
    },
    [focusFrame],
  )

  const handleZoomBack = useCallback((toIndex: number) => {
    setFocusStack((prev) => prev.slice(0, toIndex))
  }, [])

  const handleReset = useCallback(() => {
    setFocusStack([])
  }, [])

  const handleMouseMove = useCallback(
    (e: React.MouseEvent) => {
      if (!tooltipRef.current || !hoveredFrame) return
      const el = tooltipRef.current
      const vw = window.innerWidth
      const vh = window.innerHeight

      let left = e.clientX + 14
      let top = e.clientY - 10

      if (left + 320 > vw) left = e.clientX - 320
      if (top + 100 > vh) top = e.clientY - 100

      el.style.left = `${left}px`
      el.style.top = `${top}px`
    },
    [hoveredFrame],
  )

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

  const chartHeight = (maxDepth + 1) * ROW_HEIGHT + 4

  return (
    <div className="flex flex-col h-full gap-y-3">
      {/* Toolbar */}
      <div className="flex items-center gap-2 flex-wrap">
        <div className="relative flex-1 min-w-[200px] max-w-sm">
          <Search className="absolute left-2.5 top-1/2 -translate-y-1/2 h-3.5 w-3.5 text-muted-foreground" />
          <Input
            placeholder="Search functions…"
            value={searchQuery}
            onChange={(e) => setSearchQuery(e.target.value)}
            className="pl-8 h-8 text-xs"
          />
          {hasSearch && (
            <span className="absolute right-2.5 top-1/2 -translate-y-1/2 text-[10px] text-muted-foreground">
              {matchCount} match{matchCount !== 1 ? 'es' : ''}
            </span>
          )}
        </div>
        <Button
          variant={showTopFunctions ? 'secondary' : 'outline'}
          size="sm"
          className="h-8 text-xs"
          onClick={() => setShowTopFunctions((v) => !v)}
        >
          Top Functions
        </Button>
        {focusStack.length > 0 && (
          <Button variant="ghost" size="sm" className="h-8 text-xs" onClick={handleReset}>
            <RotateCcw className="h-3 w-3 mr-1" />
            Reset Zoom
          </Button>
        )}
      </div>

      {/* Zoom breadcrumbs */}
      {focusStack.length > 0 && (
        <div className="flex items-center gap-1 text-xs text-muted-foreground overflow-x-auto">
          <button
            onClick={handleReset}
            className="hover:text-foreground transition-colors shrink-0"
          >
            root
          </button>
          {focusStack.map((f, i) => (
            <span key={i} className="flex items-center gap-1 shrink-0">
              <ChevronRight className="h-3 w-3" />
              <button
                onClick={() => handleZoomBack(i + 1)}
                className="hover:text-foreground transition-colors truncate max-w-[200px]"
                title={f.name}
              >
                {shortName(f.name)}
              </button>
            </span>
          ))}
        </div>
      )}

      {/* Top functions table */}
      {showTopFunctions && topFunctions.length > 0 && (
        <div className="border rounded-lg overflow-hidden">
          <div className="max-h-[260px] overflow-y-auto">
            <table className="w-full text-xs">
              <thead className="bg-muted/50 sticky top-0">
                <tr className="border-b">
                  <th className="text-left py-1.5 px-3 font-medium text-muted-foreground">Function</th>
                  <th className="text-right py-1.5 px-3 font-medium text-muted-foreground w-24">Self</th>
                  <th className="text-right py-1.5 px-3 font-medium text-muted-foreground w-24">Total</th>
                </tr>
              </thead>
              <tbody>
                {topFunctions.map((fn) => (
                  <tr
                    key={fn.name}
                    className="border-b last:border-0 hover:bg-muted/30 cursor-default"
                  >
                    <td className="py-1 px-3">
                      <div className="flex items-center gap-2">
                        <div
                          className="w-2 h-2 rounded-sm shrink-0"
                          style={{backgroundColor: frameColor(fn.name)}}
                        />
                        <span className="font-mono truncate" title={fn.name}>
                          {fn.name}
                        </span>
                      </div>
                    </td>
                    <td className="py-1 px-3 text-right font-mono tabular-nums">
                      <span className="text-foreground">{fn.selfPercent.toFixed(1)}%</span>
                      <span className="text-muted-foreground ml-1">({fn.selfValue.toLocaleString()})</span>
                    </td>
                    <td className="py-1 px-3 text-right font-mono tabular-nums">
                      <span className="text-foreground">{fn.totalPercent.toFixed(1)}%</span>
                      <span className="text-muted-foreground ml-1">({fn.totalValue.toLocaleString()})</span>
                    </td>
                  </tr>
                ))}
              </tbody>
            </table>
          </div>
        </div>
      )}

      {/* Flamegraph (icicle – root at top) */}
      <div
        ref={containerRef}
        className="border rounded-lg overflow-x-auto overflow-y-auto relative flex-1 min-h-0"
        onMouseMove={handleMouseMove}
        onMouseLeave={() => setHoveredFrame(null)}
      >
        <div
          className="relative"
          style={{height: chartHeight, minWidth: '100%'}}
        >
          {flatFrames.map((ff, i) => {
            const top = ff.depth * ROW_HEIGHT + 2
            const isMatch = hasSearch && ff.frame.name.toLowerCase().includes(searchLower)
            const isDimmed = hasSearch && !isMatch
            const color = frameColor(ff.frame.name)

            return (
              <div
                key={`${ff.depth}-${ff.x}-${i}`}
                className="absolute cursor-pointer group"
                style={{
                  left: `${ff.x}%`,
                  top,
                  width: `${Math.max(ff.width - 0.04, 0.04)}%`,
                  height: ROW_HEIGHT - 1,
                }}
                onMouseEnter={() => setHoveredFrame(ff)}
                onClick={() => handleZoomIn(ff.frame)}
              >
                <div
                  className="w-full h-full rounded-[2px] overflow-hidden flex items-center transition-opacity group-hover:brightness-110"
                  style={{
                    backgroundColor: color,
                    opacity: isDimmed ? 0.25 : 1,
                    outline: isMatch ? '1.5px solid white' : undefined,
                  }}
                >
                  {ff.width > 2.5 && (
                    <span
                      className="text-[11px] leading-none text-white px-1 truncate pointer-events-none select-none"
                      style={{textShadow: '0 1px 2px rgba(0,0,0,0.5)'}}
                    >
                      {shortName(ff.frame.name)}
                    </span>
                  )}
                </div>
              </div>
            )
          })}
        </div>
      </div>

      {/* Color legend */}
      <div className="flex flex-wrap gap-x-3 gap-y-1 text-[10px] text-muted-foreground pt-1">
        {[
          ['com.moneat', 'hsl(142, 55%, 42%)'],
          ['io.ktor', 'hsl(160, 50%, 40%)'],
          ['kotlin/kotlinx', 'hsl(275, 45%, 52%)'],
          ['java/jdk', 'hsl(220, 45%, 48%)'],
          ['io.netty', 'hsl(175, 45%, 42%)'],
          ['other', 'hsl(14, 65%, 50%)'],
        ].map(([label, color]) => (
          <span key={label} className="flex items-center gap-1">
            <span
              className="inline-block w-2 h-2 rounded-sm"
              style={{backgroundColor: color}}
            />
            {label}
          </span>
        ))}
      </div>

      {/* Tooltip */}
      {hoveredFrame && (
        <div
          ref={tooltipRef}
          className="fixed z-50 pointer-events-none max-w-sm"
          style={{
            left: -9999,
            top: -9999,
          }}
        >
          <div className="bg-popover border rounded-lg px-3 py-2 shadow-lg text-xs space-y-1.5">
            <p className="font-semibold text-popover-foreground break-all leading-snug">
              {hoveredFrame.frame.name}
            </p>
            <div className="text-muted-foreground space-y-0.5">
              <p>
                <span className="text-popover-foreground font-medium">
                  {hoveredFrame.frame.value.toLocaleString()}
                </span>{' '}
                samples ({hoveredFrame.width.toFixed(1)}% of total)
              </p>
              {hoveredFrame.frame.self != null && hoveredFrame.frame.self > 0 && (
                <p>
                  Self:{' '}
                  <span className="text-popover-foreground font-medium">
                    {hoveredFrame.frame.self.toLocaleString()}
                  </span>{' '}
                  samples
                </p>
              )}
              <p className="text-[10px] text-muted-foreground/70 pt-0.5">
                {packageOf(hoveredFrame.frame.name)}
              </p>
            </div>
            <div
              className="h-1 rounded-full mt-1"
              style={{
                width: `${Math.max(hoveredFrame.width, 4)}%`,
                backgroundColor: frameColor(hoveredFrame.frame.name),
              }}
            />
          </div>
        </div>
      )}
    </div>
  )
}

function shortName(fullName: string): string {
  const parenIdx = fullName.indexOf('(')
  const base = parenIdx > -1 ? fullName.slice(0, parenIdx) : fullName
  const parts = base.split('.')
  if (parts.length <= 2) return fullName
  const className = parts[parts.length - 2]
  const method = parts[parts.length - 1]
  return `${className}.${method}${parenIdx > -1 ? '()' : ''}`
}
