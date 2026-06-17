// Moneat - observability platform
// Copyright (C) 2026 Moneat
//
// This program is free software: you can redistribute it and/or modify
// it under the terms of the GNU Affero General Public License as published by
// the Free Software Foundation, either version 3 of the License, or
// (at your option) any later version.

import {useMemo, useState, useCallback, useRef, useEffect} from 'react'
import {Button} from '@/components/ui/button'
import {Input} from '@/components/ui/input'
import {
  RotateCcw,
  Search,
  ChevronRight,
  ChevronUp,
  ChevronDown,
  X,
  Regex,
  GitCompare,
  Download,
  SquareFunction,
} from 'lucide-react'
import {
  type FlameNode,
  type FrameKind,
  type ColorMode,
  type TopFunctionScope,
  type TopFunctionSort,
  type ClassifyOptions,
  normalizeLanguage,
  classifyFrame,
  annotateSelf,
  collapseFrames,
  collapseRecursion,
  invertFrames,
  sumValues,
  detectAppNamespaces,
  computeTopFunctions,
  computeDiff,
  colorFor,
  diffColor,
  packageOf,
  shortName,
} from './frameModel'
import {FlamegraphLegend} from './FlamegraphLegend'
import {TopFunctionsPanel} from './TopFunctionsPanel'
import {FlamegraphMinimap, type MiniRect} from './FlamegraphMinimap'
import {CompareBar, type CompareProfile} from './CompareBar'
import {ThreadSampleTypeSelectors} from './ThreadSampleTypeSelectors'
import {AppPackageSelect} from './AppPackageSelect'
import type {SampleTypeInfo, ThreadInfo} from '@/lib/api/types/profiles'
import {
  type ExportFrame,
  framesToSvg,
  svgDimensions,
  downloadSvg,
  downloadPng,
} from './flamegraphExport'

interface Props {
  frames?: FlameNode[]
  emptyMessage?: string
  /** Profile runtime/language (jvm, go, …) used to classify frames. */
  language?: string
  /** Service name; UI preferences are remembered per service. */
  service?: string
  /** Optional content (e.g. profile metadata) rendered atop the sidebar. */
  meta?: React.ReactNode
  /** Candidate baseline profiles for diff/compare. */
  compareProfiles?: CompareProfile[]
  compareId?: string | null
  onCompareChange?: (id: string | null) => void
  /** Baseline flamegraph for the selected compare profile. */
  baselineFrames?: FlameNode[]
  baselineLoading?: boolean
  /** Available profile dimensions (from the flamegraph response). */
  sampleTypes?: SampleTypeInfo[]
  threads?: ThreadInfo[]
  selectedSampleType?: string
  selectedThread?: string | null
  unit?: string
  onSampleTypeChange?: (key: string) => void
  onThreadChange?: (id: string | null) => void
}

type DenoiseMode = 'off' | 'dim' | 'hide'
type Orientation = 'topdown' | 'bottomup'

interface FlamegraphPrefs {
  colorMode: ColorMode
  denoise: DenoiseMode
  appPrefix: string
  orientation: Orientation
  foldRecursion: boolean
  minWidth: number
}

const DEFAULT_PREFS: FlamegraphPrefs = {
  colorMode: 'package',
  denoise: 'off',
  appPrefix: '',
  orientation: 'topdown',
  foldRecursion: false,
  minWidth: 0.05,
}

const ROW_HEIGHT = 22
const DIM_COLOR = 'hsl(220, 8%, 34%)'
const VIRTUAL_BUFFER = 4
const MIN_SEARCH_CHARS = 3

function prefsKey(service?: string): string {
  return `moneat.flamegraph.${service || 'default'}`
}

function loadPrefs(service?: string): FlamegraphPrefs {
  try {
    const raw = globalThis.localStorage.getItem(prefsKey(service))
    if (raw) return {...DEFAULT_PREFS, ...JSON.parse(raw)}
  } catch {
    // ignore malformed/inaccessible storage
  }
  return DEFAULT_PREFS
}

function parsePrefixes(value: string): string[] {
  return value.split(',').map((s) => s.trim()).filter(Boolean)
}

function copyText(text: string) {
  try {
    void navigator.clipboard?.writeText(text)
  } catch {
    // clipboard unavailable
  }
}

interface FlatFrame {
  frame: FlameNode
  depth: number
  x: number
  width: number
  path: string[]
  kind: FrameKind
}

function flattenFrames(
  frames: FlameNode[],
  totalValue: number,
  classifyOpts: ClassifyOptions,
  minWidth: number,
  basePath: string[] = [],
  depth = 0,
  x = 0,
): FlatFrame[] {
  if (totalValue <= 0) return []
  const result: FlatFrame[] = []
  let currentX = x

  for (const frame of frames) {
    const width = (frame.value / totalValue) * 100
    if (width < minWidth) {
      currentX += width
      continue
    }
    const path = [...basePath, frame.name]
    result.push({
      frame,
      depth,
      x: currentX,
      width,
      path,
      kind: classifyFrame(frame.name, classifyOpts),
    })
    result.push(
      ...flattenFrames(frame.children, totalValue, classifyOpts, minWidth, path, depth + 1, currentX),
    )
    currentX += width
  }
  return result
}

function resolveFocus(frames: FlameNode[], path: string[]): FlameNode | null {
  let level = frames
  let node: FlameNode | null = null
  for (const name of path) {
    const found = level.find((f) => f.name === name)
    if (!found) return null
    node = found
    level = found.children
  }
  return node
}

function buildMatcher(
  query: string,
  useRegex: boolean,
): {test: (name: string) => boolean; error: boolean} {
  if (query.length < MIN_SEARCH_CHARS) return {test: () => false, error: false}
  if (useRegex) {
    try {
      const re = new RegExp(query, 'i')
      return {test: (n) => re.test(n), error: false}
    } catch {
      return {test: () => false, error: true}
    }
  }
  const lower = query.toLowerCase()
  return {test: (n) => n.toLowerCase().includes(lower), error: false}
}

export function Flamegraph({
  frames,
  emptyMessage,
  language,
  service,
  meta,
  compareProfiles,
  compareId = null,
  onCompareChange,
  baselineFrames,
  baselineLoading = false,
  sampleTypes = [],
  threads = [],
  selectedSampleType,
  selectedThread = null,
  unit,
  onSampleTypeChange,
  onThreadChange,
}: Props) {
  const [prefs, setPrefs] = useState<FlamegraphPrefs>(() => loadPrefs(service))
  const [activeService, setActiveService] = useState(service)
  const [focusPath, setFocusPath] = useState<string[]>([])
  const [searchQuery, setSearchQuery] = useState('')
  const [useRegex, setUseRegex] = useState(false)
  const [matchState, setMatchState] = useState({key: '', index: 0})
  const [hoveredFrame, setHoveredFrame] = useState<FlatFrame | null>(null)
  const [showTopFunctions, setShowTopFunctions] = useState(true)
  const [showCompare, setShowCompare] = useState(false)
  const [topScope, setTopScope] = useState<TopFunctionScope>('app')
  const [topSort, setTopSort] = useState<TopFunctionSort>('self')
  const [scrollTop, setScrollTop] = useState(0)
  const [viewportH, setViewportH] = useState(0)

  const tooltipRef = useRef<HTMLDivElement>(null)
  const containerRef = useRef<HTMLDivElement>(null)
  const searchInputRef = useRef<HTMLInputElement>(null)
  const hoveredRef = useRef<FlatFrame | null>(null)

  // Adjust state during render when the service prop changes.
  if (service !== activeService) {
    setActiveService(service)
    setPrefs(loadPrefs(service))
    setFocusPath([])
  }

  useEffect(() => {
    try {
      globalThis.localStorage.setItem(prefsKey(service), JSON.stringify(prefs))
    } catch {
      // ignore inaccessible storage
    }
  }, [prefs, service])

  // Track scroll + viewport size for virtualization and the minimap.
  useEffect(() => {
    const el = containerRef.current
    if (!el || typeof ResizeObserver === 'undefined') return
    const ro = new ResizeObserver(() => {
      setScrollTop(el.scrollTop)
      setViewportH(el.clientHeight)
    })
    ro.observe(el)
    return () => ro.disconnect()
  }, [])

  const updatePrefs = useCallback((patch: Partial<FlamegraphPrefs>) => {
    setPrefs((prev) => ({...prev, ...patch}))
  }, [])

  const lang = useMemo(() => normalizeLanguage(language), [language])

  const detected = useMemo(
    () => (frames?.length ? detectAppNamespaces(frames, lang, 12) : []),
    [frames, lang],
  )

  const appPrefixes = useMemo(() => {
    const manual = parsePrefixes(prefs.appPrefix)
    if (manual.length) return manual
    return detected.slice(0, 1).map((d) => d.namespace)
  }, [prefs.appPrefix, detected])

  const classifyOpts = useMemo<ClassifyOptions>(
    () => ({language: lang, appPrefixes}),
    [lang, appPrefixes],
  )

  const profileTotal = useMemo(
    () => (frames?.length ? sumValues(frames) : 0),
    [frames],
  )

  const displayFrames = useMemo(() => {
    if (!frames?.length) return []
    let base = frames
    if (prefs.denoise === 'hide') {
      base = collapseFrames(base, (n) => classifyFrame(n.name, classifyOpts) !== 'app')
    }
    if (prefs.foldRecursion) base = collapseRecursion(base)
    if (prefs.orientation === 'bottomup') base = invertFrames(base)
    return annotateSelf(base)
  }, [frames, prefs.denoise, prefs.foldRecursion, prefs.orientation, classifyOpts])

  const focusFrame = useMemo(
    () => (focusPath.length ? resolveFocus(displayFrames, focusPath) : null),
    [displayFrames, focusPath],
  )

  const flatFrames = useMemo(() => {
    if (!displayFrames.length) return []
    const roots = focusFrame ? [focusFrame] : displayFrames
    const rootTotal = focusFrame?.value ?? sumValues(displayFrames)
    const basePath = focusFrame ? focusPath.slice(0, -1) : []
    return flattenFrames(roots, rootTotal, classifyOpts, prefs.minWidth, basePath)
  }, [displayFrames, focusFrame, focusPath, classifyOpts, prefs.minWidth])

  const maxDepth = useMemo(
    () => Math.max(0, ...flatFrames.map((f) => f.depth)),
    [flatFrames],
  )

  const topFunctions = useMemo(() => {
    if (!frames?.length) return []
    return computeTopFunctions(frames, profileTotal, {
      language: lang,
      appPrefixes,
      scope: topScope,
      sortBy: topSort,
    })
  }, [frames, profileTotal, lang, appPrefixes, topScope, topSort])

  const hasSearch = searchQuery.length >= MIN_SEARCH_CHARS
  const matcher = useMemo(
    () => buildMatcher(searchQuery, useRegex),
    [searchQuery, useRegex],
  )

  const matchIndices = useMemo(() => {
    if (!hasSearch) return []
    const indices: number[] = []
    flatFrames.forEach((ff, i) => {
      if (matcher.test(ff.frame.name)) indices.push(i)
    })
    return indices
  }, [flatFrames, matcher, hasSearch])

  // Reset the active match when the query changes (during render).
  const searchKey = `${searchQuery}|${useRegex}`
  if (matchState.key !== searchKey) setMatchState({key: searchKey, index: 0})
  const currentMatch = Math.min(matchState.index, Math.max(matchIndices.length - 1, 0))

  const colorOf = useCallback(
    (name: string) =>
      colorFor(name, {mode: prefs.colorMode, language: lang, appPrefixes}),
    [prefs.colorMode, lang, appPrefixes],
  )

  const diff = useMemo(
    () => (baselineFrames?.length && frames?.length ? computeDiff(frames, baselineFrames) : null),
    [frames, baselineFrames],
  )

  const frameColorFor = useCallback(
    (ff: FlatFrame) => {
      if (diff) return diffColor(diff.deltaByName.get(ff.frame.name) ?? 0)
      if (prefs.denoise === 'dim' && ff.kind !== 'app') return DIM_COLOR
      return colorOf(ff.frame.name)
    },
    [diff, prefs.denoise, colorOf],
  )

  const matchCount = matchIndices.length
  const gotoMatch = useCallback(
    (dir: number) => {
      if (!matchCount) return
      setMatchState((s) => ({
        key: s.key,
        index: ((s.index + dir) % matchCount + matchCount) % matchCount,
      }))
    },
    [matchCount],
  )

  // Scroll the active match into view.
  useEffect(() => {
    const el = containerRef.current
    if (!el || !matchIndices.length) return
    const ff = flatFrames[matchIndices[currentMatch]]
    if (!ff) return
    el.scrollTo({top: Math.max(ff.depth * ROW_HEIGHT - el.clientHeight / 2, 0), behavior: 'smooth'})
  }, [currentMatch, matchIndices, flatFrames])

  const handleZoomIn = useCallback((ff: FlatFrame) => {
    if (ff.frame.children.length > 0) setFocusPath(ff.path)
  }, [])

  const handleReset = useCallback(() => setFocusPath([]), [])

  const handleScroll = useCallback(() => {
    const el = containerRef.current
    if (!el) return
    setScrollTop(el.scrollTop)
    setViewportH(el.clientHeight)
  }, [])

  const handleMinimapScroll = useCallback((fraction: number) => {
    const el = containerRef.current
    if (!el) return
    el.scrollTop = Math.max(0, fraction * el.scrollHeight - el.clientHeight / 2)
  }, [])

  const handleMouseMove = useCallback(
    (e: React.MouseEvent) => {
      if (!tooltipRef.current || !hoveredFrame) return
      const el = tooltipRef.current
      let left = e.clientX + 14
      let top = e.clientY - 10
      if (left + 320 > globalThis.window.innerWidth) left = e.clientX - 320
      if (top + 120 > globalThis.window.innerHeight) top = e.clientY - 120
      el.style.left = `${left}px`
      el.style.top = `${top}px`
    },
    [hoveredFrame],
  )

  // Keyboard shortcuts.
  useEffect(() => {
    function onKey(e: KeyboardEvent) {
      const tag = (e.target as HTMLElement | null)?.tagName
      const typing = tag === 'INPUT' || tag === 'TEXTAREA'
      if (e.key === '/' && !typing) {
        e.preventDefault()
        searchInputRef.current?.focus()
        return
      }
      if (e.key === 'Escape') {
        setFocusPath([])
        setSearchQuery('')
        return
      }
      if (typing) return
      if (e.key === 'n') gotoMatch(1)
      else if (e.key === 'N') gotoMatch(-1)
      else if (e.key === 'f') updatePrefs({denoise: prefs.denoise === 'hide' ? 'off' : 'hide'})
      else if (e.key === 'i') {
        updatePrefs({orientation: prefs.orientation === 'bottomup' ? 'topdown' : 'bottomup'})
      } else if (e.key === 'c' && hoveredRef.current) {
        copyText(hoveredRef.current.path.join(';'))
      }
    }
    globalThis.window.addEventListener('keydown', onKey)
    return () => globalThis.window.removeEventListener('keydown', onKey)
  }, [gotoMatch, updatePrefs, prefs.denoise, prefs.orientation])

  const visibleFrames = useMemo(() => {
    if (viewportH <= 0) return flatFrames
    const start = Math.floor(scrollTop / ROW_HEIGHT) - VIRTUAL_BUFFER
    const end = Math.ceil((scrollTop + viewportH) / ROW_HEIGHT) + VIRTUAL_BUFFER
    return flatFrames.filter((ff) => ff.depth >= start && ff.depth <= end)
  }, [flatFrames, scrollTop, viewportH])

  const activeMatchFrame = matchIndices.length
    ? (flatFrames[matchIndices[currentMatch]] ?? null)
    : null

  const miniRects = useMemo<MiniRect[]>(
    () => flatFrames.map((ff) => ({depth: ff.depth, x: ff.x, width: ff.width, color: frameColorFor(ff)})),
    [flatFrames, frameColorFor],
  )

  const handleExport = useCallback(
    async (format: 'svg' | 'png') => {
      const exportFrames: ExportFrame[] = flatFrames.map((ff) => ({
        name: ff.frame.name,
        depth: ff.depth,
        x: ff.x,
        width: ff.width,
        color: frameColorFor(ff),
      }))
      if (!exportFrames.length) return
      const opts = {width: 1600, rowHeight: ROW_HEIGHT, title: `${service || 'profile'} — flamegraph`}
      const svg = framesToSvg(exportFrames, opts)
      const base = `flamegraph-${(service || 'profile').replace(/[^a-z0-9_-]+/gi, '_')}`
      try {
        if (format === 'svg') {
          downloadSvg(svg, `${base}.svg`)
        } else {
          const {width, height} = svgDimensions(exportFrames, opts)
          await downloadPng(svg, width, height, `${base}.png`)
        }
      } catch {
        // export failed (e.g., canvas unavailable)
      }
    },
    [flatFrames, frameColorFor, service],
  )

  if (!frames?.length) {
    return (
      <div className="text-center py-12 text-muted-foreground">
        <p className="font-medium">{emptyMessage || 'No profile data available'}</p>
        <p className="text-sm mt-1">
          Upload a pprof/JFR file or wait for profile data to be collected.
        </p>
      </div>
    )
  }

  const chartHeight = (maxDepth + 1) * ROW_HEIGHT + 4

  return (
    <div className="flex flex-col lg:flex-row flex-1 min-h-0 gap-3">
      {/* Controls + meta sidebar (right) */}
      <aside className="flex flex-col gap-2 lg:order-2 lg:w-80 lg:shrink-0 lg:overflow-y-auto lg:pr-1">
        {meta}
        {/* Search */}
        <div className="relative">
          <Search className="absolute left-2.5 top-1/2 -translate-y-1/2 h-3.5 w-3.5 text-muted-foreground" />
          <Input
            ref={searchInputRef}
            placeholder="Search functions… (/)"
            value={searchQuery}
            onChange={(e) => setSearchQuery(e.target.value)}
            className={`pl-8 pr-20 h-8 text-xs ${matcher.error ? 'border-destructive' : ''}`}
          />
          <div className="absolute right-1 top-1/2 -translate-y-1/2 flex items-center gap-0.5">
            {searchQuery.length > 0 && !hasSearch && (
              <span className="text-[10px] text-muted-foreground">3+ chars</span>
            )}
            {hasSearch && (
              <span className="text-[10px] text-muted-foreground tabular-nums">
                {matchIndices.length ? currentMatch + 1 : 0}/{matchIndices.length}
              </span>
            )}
            <IconBtn label="Previous match (N)" onClick={() => gotoMatch(-1)} disabled={!matchIndices.length}>
              <ChevronUp className="h-3.5 w-3.5" />
            </IconBtn>
            <IconBtn label="Next match (n)" onClick={() => gotoMatch(1)} disabled={!matchIndices.length}>
              <ChevronDown className="h-3.5 w-3.5" />
            </IconBtn>
            <IconBtn label="Regex" active={useRegex} onClick={() => setUseRegex((v) => !v)}>
              <Regex className="h-3.5 w-3.5" />
            </IconBtn>
          </div>
        </div>

        {/* Selectors */}
        {(onSampleTypeChange || onThreadChange) && (
          <ThreadSampleTypeSelectors
            sampleTypes={sampleTypes}
            threads={threads}
            selectedSampleType={selectedSampleType}
            selectedThread={selectedThread}
            unit={unit}
            onSampleTypeChange={(k) => onSampleTypeChange?.(k)}
            onThreadChange={(id) => onThreadChange?.(id)}
          />
        )}

        {/* Display */}
        <SidebarField label="Color">
          <Segmented>
            <SegButton active={prefs.colorMode === 'package'} onClick={() => updatePrefs({colorMode: 'package'})}>
              Package
            </SegButton>
            <SegButton active={prefs.colorMode === 'kind'} onClick={() => updatePrefs({colorMode: 'kind'})}>
              Kind
            </SegButton>
          </Segmented>
        </SidebarField>

        <SidebarField label="Frames">
          <Segmented>
            <SegButton active={prefs.denoise === 'off'} onClick={() => updatePrefs({denoise: 'off'})}>
              Full
            </SegButton>
            <SegButton active={prefs.denoise === 'dim'} onClick={() => updatePrefs({denoise: 'dim'})}>
              Dim
            </SegButton>
            <SegButton active={prefs.denoise === 'hide'} onClick={() => updatePrefs({denoise: 'hide'})}>
              Hide
            </SegButton>
          </Segmented>
        </SidebarField>

        <SidebarField label="View">
          <Segmented>
            <SegButton active={prefs.orientation === 'topdown'} onClick={() => updatePrefs({orientation: 'topdown'})}>
              Top-down
            </SegButton>
            <SegButton active={prefs.orientation === 'bottomup'} onClick={() => updatePrefs({orientation: 'bottomup'})}>
              Bottom-up
            </SegButton>
          </Segmented>
        </SidebarField>

        <label className="flex items-center justify-between gap-2 cursor-pointer">
          <span className="text-[11px] text-muted-foreground">Fold recursion</span>
          <input
            type="checkbox"
            checked={prefs.foldRecursion}
            onChange={() => updatePrefs({foldRecursion: !prefs.foldRecursion})}
            className="accent-current h-3.5 w-3.5 cursor-pointer"
          />
        </label>

        <div className="flex items-center justify-between gap-2">
          <span className="text-[11px] text-muted-foreground shrink-0">Min width</span>
          <div className="flex items-center gap-2 w-40">
            <input
              type="range"
              min={0.02}
              max={2}
              step={0.02}
              value={prefs.minWidth}
              onChange={(e) => updatePrefs({minWidth: Number(e.target.value)})}
              className="flex-1 accent-current"
            />
            <span className="text-[10px] text-muted-foreground tabular-nums w-10 text-right">
              {prefs.minWidth.toFixed(2)}%
            </span>
          </div>
        </div>

        {/* Your code */}
        <SidebarField label="Your code">
          <AppPackageSelect
            namespaces={detected}
            value={prefs.appPrefix}
            effective={appPrefixes}
            onChange={(csv) => updatePrefs({appPrefix: csv})}
          />
          <div className="flex items-center gap-1 mt-1.5">
            <Input
              placeholder="custom prefix…"
              value={prefs.appPrefix}
              onChange={(e) => updatePrefs({appPrefix: e.target.value})}
              className="h-7 text-[11px] flex-1 font-mono"
            />
            {prefs.appPrefix && (
              <Button
                variant="ghost"
                size="icon"
                className="h-7 w-7 shrink-0"
                title="Reset to auto-detect"
                onClick={() => updatePrefs({appPrefix: ''})}
              >
                <X className="h-3.5 w-3.5" />
              </Button>
            )}
          </div>
        </SidebarField>

        {/* Export */}
        <div className="flex gap-1.5 pt-1">
          <Button variant="outline" size="sm" className="h-8 text-xs flex-1" onClick={() => handleExport('svg')}>
            <Download className="h-3 w-3 mr-1" />
            SVG
          </Button>
          <Button variant="outline" size="sm" className="h-8 text-xs flex-1" onClick={() => handleExport('png')}>
            <Download className="h-3 w-3 mr-1" />
            PNG
          </Button>
          {focusPath.length > 0 && (
            <Button
              variant="ghost"
              size="icon"
              className="h-8 w-8 shrink-0"
              title="Reset zoom"
              onClick={handleReset}
            >
              <RotateCcw className="h-3.5 w-3.5" />
            </Button>
          )}
        </div>

      </aside>

      {/* Main — flamegraph */}
      <div className="flex flex-col flex-1 min-h-0 gap-2 lg:order-1">
        {/* Zoom breadcrumbs */}
        {focusPath.length > 0 && (
          <div className="flex items-center gap-1 text-xs text-muted-foreground overflow-x-auto shrink-0">
            <button onClick={handleReset} className="hover:text-foreground transition-colors shrink-0">
              root
            </button>
            {focusPath.map((name, i) => (
              <span key={`${name}-${i}`} className="flex items-center gap-1 shrink-0">
                <ChevronRight className="h-3 w-3" />
                <button
                  onClick={() => setFocusPath((prev) => prev.slice(0, i + 1))}
                  className="hover:text-foreground transition-colors truncate max-w-[280px]"
                  title={name}
                >
                  {shortName(name)}
                </button>
              </span>
            ))}
          </div>
        )}

      {/* Minimap overview */}
      {chartHeight > viewportH && viewportH > 0 && (
        <FlamegraphMinimap
          rects={miniRects}
          rows={maxDepth + 1}
          chartHeight={chartHeight}
          scrollTop={scrollTop}
          viewportHeight={viewportH}
          onScrollToFraction={handleMinimapScroll}
        />
      )}

      {/* Flamegraph (icicle – root at top) */}
      <div
        ref={containerRef}
        className="border rounded-lg overflow-auto relative flex-1 min-h-0"
        onScroll={handleScroll}
        onMouseMove={handleMouseMove}
        onMouseLeave={() => {
          setHoveredFrame(null)
          hoveredRef.current = null
        }}
      >
        <div className="relative" style={{height: chartHeight, minWidth: '100%'}}>
          {visibleFrames.map((ff) => {
            const isMatch = hasSearch && matcher.test(ff.frame.name)
            const isActiveMatch = ff === activeMatchFrame
            const dimmed = !diff && prefs.denoise === 'dim' && ff.kind !== 'app'
            let opacity = dimmed ? 0.5 : 1
            if (hasSearch && !isMatch) opacity = Math.min(opacity, 0.2)

            return (
              <div
                key={ff.path.join(';')}
                className="absolute cursor-pointer group"
                style={{
                  left: `${ff.x}%`,
                  top: ff.depth * ROW_HEIGHT + 2,
                  width: `${Math.max(ff.width - 0.04, 0.04)}%`,
                  height: ROW_HEIGHT - 1,
                }}
                onMouseEnter={() => {
                  setHoveredFrame(ff)
                  hoveredRef.current = ff
                }}
                onClick={() => handleZoomIn(ff)}
              >
                <div
                  className="w-full h-full rounded-[2px] overflow-hidden flex items-center transition-opacity group-hover:brightness-110"
                  style={{
                    backgroundColor: frameColorFor(ff),
                    opacity,
                    outline: isActiveMatch
                      ? '2px solid hsl(48 100% 60%)'
                      : isMatch
                        ? '1.5px solid white'
                        : undefined,
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

        {/* Legend + panel toggles on one row */}
        <div className="flex items-center justify-between gap-2 shrink-0">
          <div className="min-w-0 flex-1">
            <FlamegraphLegend mode={prefs.colorMode} namespaces={detected} appPrefixes={appPrefixes} />
          </div>
          <div className="flex items-center gap-1 shrink-0">
            <IconBtn
              label="Top Functions"
              active={showTopFunctions}
              onClick={() => setShowTopFunctions((v) => !v)}
            >
              <SquareFunction className="h-3.5 w-3.5" />
            </IconBtn>
            {compareProfiles && compareProfiles.length > 0 && (
              <IconBtn
                label="Compare profiles"
                active={showCompare || !!compareId}
                onClick={() => setShowCompare((v) => !v)}
              >
                <GitCompare className="h-3.5 w-3.5" />
              </IconBtn>
            )}
          </div>
        </div>

        {/* Top functions */}
        {showTopFunctions && (
          <TopFunctionsPanel
            functions={topFunctions}
            scope={topScope}
            sortBy={topSort}
            hasAppPrefixes={appPrefixes.length > 0}
            onScopeChange={setTopScope}
            onSortChange={setTopSort}
            onSelect={setSearchQuery}
            onCopy={copyText}
            colorOf={colorOf}
          />
        )}

        {/* Compare / diff */}
        {showCompare && compareProfiles && (
          <CompareBar
            profiles={compareProfiles}
            compareId={compareId}
            loading={baselineLoading}
            diff={diff}
            onChange={(id) => onCompareChange?.(id)}
            onSelect={setSearchQuery}
          />
        )}
      </div>

      {/* Tooltip */}
      {hoveredFrame && (
        <div
          ref={tooltipRef}
          className="fixed z-50 pointer-events-none max-w-sm"
          style={{left: -9999, top: -9999}}
        >
          <div className="bg-popover border rounded-lg px-3 py-2 text-xs space-y-1.5">
            <p className="font-semibold text-popover-foreground break-all leading-snug">
              {hoveredFrame.frame.name}
            </p>
            <div className="text-muted-foreground space-y-0.5">
              <p>
                Total:{' '}
                <span className="text-popover-foreground font-medium">
                  {hoveredFrame.frame.value.toLocaleString()}
                </span>{' '}
                ({pctOf(hoveredFrame.frame.value, profileTotal).toFixed(1)}%)
              </p>
              <p>
                Self:{' '}
                <span className="text-popover-foreground font-medium">
                  {(hoveredFrame.frame.self ?? 0).toLocaleString()}
                </span>{' '}
                ({pctOf(hoveredFrame.frame.self ?? 0, profileTotal).toFixed(1)}%)
              </p>
              <p className="text-[10px] text-muted-foreground/70 pt-0.5">
                {KIND_LABEL[hoveredFrame.kind]} · {packageOf(hoveredFrame.frame.name)}
              </p>
              <p className="text-[10px] text-muted-foreground/50 pt-0.5">
                click to zoom · press c to copy stack
              </p>
            </div>
            <div
              className="h-1 rounded-full mt-1"
              style={{
                width: `${Math.max(pctOf(hoveredFrame.frame.value, profileTotal), 4)}%`,
                backgroundColor: colorOf(hoveredFrame.frame.name),
              }}
            />
          </div>
        </div>
      )}
    </div>
  )
}

const KIND_LABEL: Record<FrameKind, string> = {
  app: 'your code',
  library: 'library',
  runtime: 'runtime / system',
}

function pctOf(value: number, total: number): number {
  return total > 0 ? (value / total) * 100 : 0
}

function SidebarField({label, children}: {label: string; children: React.ReactNode}) {
  return (
    <div className="space-y-1">
      <span className="block text-[10px] font-medium uppercase tracking-wide text-muted-foreground">
        {label}
      </span>
      {children}
    </div>
  )
}

function Segmented({children}: {children: React.ReactNode}) {
  return <div className="flex rounded-md border p-0.5 gap-0.5 [&>button]:flex-1">{children}</div>
}

function SegButton({
  active,
  onClick,
  children,
}: {
  active: boolean
  onClick: () => void
  children: React.ReactNode
}) {
  return (
    <button
      type="button"
      onClick={onClick}
      className={`inline-flex items-center justify-center whitespace-nowrap h-7 px-2 rounded text-[11px] transition-colors ${
        active ? 'bg-secondary text-secondary-foreground' : 'text-muted-foreground hover:text-foreground'
      }`}
    >
      {children}
    </button>
  )
}

function IconBtn({
  label,
  active,
  disabled,
  onClick,
  children,
}: {
  label: string
  active?: boolean
  disabled?: boolean
  onClick: () => void
  children: React.ReactNode
}) {
  return (
    <button
      type="button"
      title={label}
      aria-label={label}
      disabled={disabled}
      onClick={onClick}
      className={`h-6 w-6 inline-flex items-center justify-center rounded transition-colors disabled:opacity-30 ${
        active ? 'bg-secondary text-secondary-foreground' : 'text-muted-foreground hover:text-foreground'
      }`}
    >
      {children}
    </button>
  )
}
