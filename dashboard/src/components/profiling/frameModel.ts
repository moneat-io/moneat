// Moneat - observability platform
// Copyright (C) 2026 Moneat
//
// This program is free software: you can redistribute it and/or modify
// it under the terms of the GNU Affero General Public License as published by
// the Free Software Foundation, either version 3 of the License, or
// (at your option) any later version.

// Pure, dependency-free helpers for the flamegraph viewer. Everything that
// transforms the frame tree (classification, self-time, collapsing, inversion,
// top-functions, diff, colours) lives here so it can be unit tested without
// rendering. The React component is a thin orchestrator over these functions.

export interface FlameNode {
  name: string
  value: number
  children: FlameNode[]
  /** Samples spent in this frame itself (value minus the sum of children). */
  self?: number
}

export type FrameKind = 'app' | 'library' | 'runtime'

export type ColorMode = 'package' | 'kind'

export type ProfileLanguage =
  | 'jvm'
  | 'go'
  | 'python'
  | 'nodejs'
  | 'ruby'
  | 'dotnet'
  | 'php'
  | 'unknown'

export interface ClassifyOptions {
  language: ProfileLanguage
  /** Namespaces the user considers their own code (detected or manual). */
  appPrefixes: string[]
}

// ─────────────────────────── language ───────────────────────────

export function normalizeLanguage(language?: string | null): ProfileLanguage {
  const l = (language || '').toLowerCase()
  if (/jvm|java|kotlin|scala|groovy/.test(l)) return 'jvm'
  if (/golang|(^|[^a-z])go([^a-z]|$)/.test(l)) return 'go'
  if (/python|cpython|\bpy\b/.test(l)) return 'python'
  if (/node|javascript|typescript|\bjs\b|\bts\b/.test(l)) return 'nodejs'
  if (/ruby|\brb\b/.test(l)) return 'ruby'
  if (/dotnet|\.net|csharp|c#/.test(l)) return 'dotnet'
  if (/php/.test(l)) return 'php'
  return 'unknown'
}

// ─────────────────────────── namespaces ───────────────────────────

/** True when `name` is inside `prefix` respecting a `.`, `/` or `$` boundary. */
export function matchesNamespace(name: string, prefix: string): boolean {
  if (!prefix) return false
  if (name === prefix) return true
  if (!name.startsWith(prefix)) return false
  const next = name.charAt(prefix.length)
  return next === '.' || next === '/' || next === '$'
}

function matchesAny(name: string, prefixes: readonly string[]): boolean {
  return prefixes.some((p) => matchesNamespace(name, p))
}

// ─────────────────────────── classification ───────────────────────────

const JVM_RUNTIME_PREFIXES = [
  'java', 'javax', 'jdk', 'sun', 'com.sun',
  'kotlin', 'kotlinx.coroutines',
  'scala', 'groovy', 'org.codehaus.groovy',
] as const

const JVM_LIBRARY_PREFIXES = [
  'io.ktor', 'io.netty', 'io.lettuce', 'io.micrometer', 'io.grpc',
  'io.opentelemetry', 'kotlinx', 'org.apache', 'org.slf4j', 'ch.qos.logback',
  'org.springframework', 'org.hibernate', 'org.jetbrains', 'reactor',
  'com.zaxxer', 'com.clickhouse', 'org.postgresql', 'redis.clients',
  'com.fasterxml.jackson', 'com.google', 'okhttp3', 'retrofit2',
  'org.eclipse', 'org.junit', 'io.mockk', 'com.squareup',
] as const

const GO_VENDOR_HOSTS = [
  'golang.org', 'google.golang.org', 'gopkg.in', 'go.uber.org',
  'go.opentelemetry.io',
] as const

const PY_RUNTIME_MARKERS = [
  '<built-in>', '<frozen', '<string>', 'importlib', 'threading.',
  'asyncio.', 'concurrent.futures', 'runpy.',
] as const

const NODE_RUNTIME_MARKERS = ['node:', 'internal/'] as const

/** Third-party Go import paths begin with a domain segment (contains a dot). */
function hasGoDomain(name: string): boolean {
  const slash = name.indexOf('/')
  if (slash === -1) return false
  return name.slice(0, slash).includes('.')
}

function includesAny(name: string, markers: readonly string[]): boolean {
  return markers.some((m) => name.includes(m))
}

function classifyJvm(name: string): FrameKind | null {
  if (matchesAny(name, JVM_RUNTIME_PREFIXES)) return 'runtime'
  if (matchesAny(name, JVM_LIBRARY_PREFIXES)) return 'library'
  return null
}

function classifyGo(name: string): FrameKind | null {
  if (!hasGoDomain(name)) return 'runtime'
  if (matchesAny(name, GO_VENDOR_HOSTS)) return 'library'
  return null
}

function classifyPython(name: string): FrameKind | null {
  if (includesAny(name, PY_RUNTIME_MARKERS)) return 'runtime'
  if (name.includes('site-packages') || name.includes('dist-packages')) {
    return 'library'
  }
  return null
}

function classifyNode(name: string): FrameKind | null {
  if (includesAny(name, NODE_RUNTIME_MARKERS)) return 'runtime'
  if (name.includes('node_modules')) return 'library'
  return null
}

function classifyByLanguage(
  name: string,
  language: ProfileLanguage,
): FrameKind | null {
  switch (language) {
    case 'jvm': return classifyJvm(name)
    case 'go': return classifyGo(name)
    case 'python': return classifyPython(name)
    case 'nodejs': return classifyNode(name)
    default: return null
  }
}

/**
 * Classify a frame as the user's own code, a third-party library, or
 * language runtime/system noise.
 *
 * When app prefixes are known, anything not matching them and not recognised
 * as runtime is treated as a library. When no prefixes are known yet, an
 * unrecognised frame is optimistically treated as app code so the user's code
 * is never hidden before detection runs.
 */
export function classifyFrame(name: string, opts: ClassifyOptions): FrameKind {
  if (matchesAny(name, opts.appPrefixes)) return 'app'
  const byLang = classifyByLanguage(name, opts.language)
  if (byLang) return byLang
  return opts.appPrefixes.length > 0 ? 'library' : 'app'
}

// ─────────────────────────── self-time ───────────────────────────

/** Returns a new forest with `self = value − Σ children.value` on every node. */
export function annotateSelf(frames: FlameNode[]): FlameNode[] {
  return frames.map(annotateNode)
}

function annotateNode(node: FlameNode): FlameNode {
  const children = node.children.map(annotateNode)
  const childSum = sumValues(children)
  return {...node, children, self: Math.max(node.value - childSum, 0)}
}

export function sumValues(frames: FlameNode[]): number {
  return frames.reduce((acc, f) => acc + f.value, 0)
}

// ─────────────────────────── collapse / hide ───────────────────────────

/**
 * Remove frames matching `shouldHide`, grafting their kept descendants onto the
 * nearest surviving ancestor and merging same-named siblings. Hidden frames'
 * self-time is reattributed to that ancestor (recompute self via annotateSelf).
 */
export function collapseFrames(
  frames: FlameNode[],
  shouldHide: (node: FlameNode) => boolean,
): FlameNode[] {
  const lifted: FlameNode[] = []
  for (const frame of frames) {
    const children = collapseFrames(frame.children, shouldHide)
    if (shouldHide(frame)) {
      lifted.push(...children)
    } else {
      lifted.push({name: frame.name, value: frame.value, children})
    }
  }
  return mergeSiblings(lifted)
}

function mergeSiblings(nodes: FlameNode[]): FlameNode[] {
  const byName = new Map<string, FlameNode>()
  for (const node of nodes) {
    const existing = byName.get(node.name)
    if (existing) {
      existing.value += node.value
      existing.children.push(...node.children)
    } else {
      byName.set(node.name, {
        name: node.name,
        value: node.value,
        children: [...node.children],
      })
    }
  }
  return Array.from(byName.values())
    .map((n) => ({...n, children: mergeSiblings(n.children)}))
    .sort((a, b) => b.value - a.value)
}

/** Collapse immediate recursion (a frame that directly calls itself). */
export function collapseRecursion(frames: FlameNode[]): FlameNode[] {
  return frames.map(collapseRecursiveNode)
}

function collapseRecursiveNode(node: FlameNode): FlameNode {
  let value = node.value
  let children = node.children
  // Fold a chain of same-named direct descendants into this node.
  while (children.length === 1 && children[0].name === node.name) {
    value = Math.max(value, children[0].value)
    children = children[0].children
  }
  return {
    name: node.name,
    value,
    children: children.map(collapseRecursiveNode),
  }
}

// ─────────────────────────── inversion (bottom-up) ───────────────────────────

interface MutableNode {
  name: string
  value: number
  children: Map<string, MutableNode>
}

/**
 * Build a bottom-up ("hottest leaves") tree: each frame's self-time is pushed
 * up its reversed caller chain, so the widest roots are the functions that burn
 * the most self-time, with their callers stacked beneath.
 */
export function invertFrames(frames: FlameNode[]): FlameNode[] {
  const annotated = annotateSelf(frames)
  const roots = new Map<string, MutableNode>()
  const path: string[] = []

  function visit(node: FlameNode) {
    path.push(node.name)
    const self = node.self ?? 0
    if (self > 0) insertReversedPath(roots, path, self)
    for (const child of node.children) visit(child)
    path.pop()
  }
  for (const frame of annotated) visit(frame)

  return materialize(roots)
}

function insertReversedPath(
  roots: Map<string, MutableNode>,
  path: string[],
  weight: number,
) {
  let level = roots
  for (let i = path.length - 1; i >= 0; i--) {
    const name = path[i]
    let node = level.get(name)
    if (!node) {
      node = {name, value: 0, children: new Map()}
      level.set(name, node)
    }
    node.value += weight
    level = node.children
  }
}

function materialize(nodes: Map<string, MutableNode>): FlameNode[] {
  return Array.from(nodes.values())
    .map((n) => ({name: n.name, value: n.value, children: materialize(n.children)}))
    .sort((a, b) => b.value - a.value)
}

// ─────────────────────────── namespace detection ───────────────────────────

export interface NamespaceStat {
  namespace: string
  self: number
  total: number
}

/** First 2 package segments for JVM, module path for Go, else the package. */
export function topLevelNamespace(
  name: string,
  language: ProfileLanguage,
): string {
  const base = stripSignature(name)
  if (language === 'go') return goModulePath(base)
  if (language === 'jvm') {
    const parts = base.split('.')
    if (parts.length <= 2) return base
    return `${parts[0]}.${parts[1]}`
  }
  return packageOf(base)
}

function goModulePath(name: string): string {
  if (!hasGoDomain(name)) {
    const slash = name.indexOf('/')
    return slash === -1 ? packageOf(name) : name.slice(0, slash)
  }
  const segments = name.split('/')
  return segments.slice(0, 3).join('/')
}

/**
 * Rank candidate "your code" namespaces by self-time, excluding recognised
 * runtime and library frames. The top entry is a good default app prefix.
 */
export function detectAppNamespaces(
  frames: FlameNode[],
  language: ProfileLanguage,
  limit = 5,
): NamespaceStat[] {
  const annotated = annotateSelf(frames)
  const stats = new Map<string, {self: number; total: number}>()
  const opts: ClassifyOptions = {language, appPrefixes: []}

  function walk(node: FlameNode) {
    if (classifyFrame(node.name, opts) === 'app') {
      const ns = topLevelNamespace(node.name, language)
      const entry = stats.get(ns) ?? {self: 0, total: 0}
      entry.self += node.self ?? 0
      entry.total += node.value
      stats.set(ns, entry)
    }
    for (const child of node.children) walk(child)
  }
  for (const frame of annotated) walk(frame)

  return Array.from(stats.entries())
    .map(([namespace, v]) => ({namespace, self: v.self, total: v.total}))
    .filter((s) => s.total > 0)
    .sort((a, b) => b.self - a.self || b.total - a.total)
    .slice(0, limit)
}

// ─────────────────────────── filtering ───────────────────────────

/**
 * Keep only paths that contain at least one frame inside `prefixes`. Frames are
 * retained if they (or any descendant) match, so callers remain as context.
 */
export function filterToNamespaces(
  frames: FlameNode[],
  prefixes: string[],
): FlameNode[] {
  if (prefixes.length === 0) return frames
  const result: FlameNode[] = []
  for (const frame of frames) {
    const children = filterToNamespaces(frame.children, prefixes)
    if (matchesAny(frame.name, prefixes) || children.length > 0) {
      result.push({name: frame.name, value: frame.value, children})
    }
  }
  return result
}

// ─────────────────────────── top functions ───────────────────────────

export type TopFunctionScope = 'all' | 'app'
export type TopFunctionSort = 'self' | 'total'

export interface TopFunction {
  name: string
  kind: FrameKind
  selfValue: number
  totalValue: number
  selfPercent: number
  totalPercent: number
}

export interface TopFunctionsOptions {
  language: ProfileLanguage
  appPrefixes: string[]
  scope: TopFunctionScope
  sortBy: TopFunctionSort
  limit?: number
}

export function computeTopFunctions(
  frames: FlameNode[],
  profileTotal: number,
  opts: TopFunctionsOptions,
): TopFunction[] {
  const annotated = annotateSelf(frames)
  const classifyOpts: ClassifyOptions = {
    language: opts.language,
    appPrefixes: opts.appPrefixes,
  }
  const map = new Map<string, {self: number; total: number; kind: FrameKind}>()

  function walk(node: FlameNode) {
    const kind = classifyFrame(node.name, classifyOpts)
    const entry = map.get(node.name) ?? {self: 0, total: 0, kind}
    entry.self += node.self ?? 0
    entry.total += node.value
    map.set(node.name, entry)
    for (const child of node.children) walk(child)
  }
  for (const frame of annotated) walk(frame)

  const total = profileTotal > 0 ? profileTotal : 1
  const limit = opts.limit ?? 25

  return Array.from(map.entries())
    .map(([name, v]) => ({
      name,
      kind: v.kind,
      selfValue: v.self,
      totalValue: v.total,
      selfPercent: (v.self / total) * 100,
      totalPercent: (v.total / total) * 100,
    }))
    .filter((f) => (opts.scope === 'app' ? f.kind === 'app' : true))
    .filter((f) => f.totalValue > 0)
    .sort((a, b) =>
      opts.sortBy === 'self'
        ? b.selfValue - a.selfValue || b.totalValue - a.totalValue
        : b.totalValue - a.totalValue || b.selfValue - a.selfValue,
    )
    .slice(0, limit)
}

// ─────────────────────────── diff ───────────────────────────

export interface FunctionDelta {
  name: string
  currentPercent: number
  basePercent: number
  deltaPercent: number
}

export interface DiffResult {
  deltaByName: Map<string, number>
  topRegressions: FunctionDelta[]
  topImprovements: FunctionDelta[]
}

/**
 * Compare two profiles by self-time share per function (normalised so profiles
 * with different sample counts are comparable). Positive delta = hotter now.
 */
export function computeDiff(
  current: FlameNode[],
  baseline: FlameNode[],
  limit = 15,
): DiffResult {
  const currentSelf = selfPercentByName(current)
  const baseSelf = selfPercentByName(baseline)

  const names = new Set<string>([...currentSelf.keys(), ...baseSelf.keys()])
  const deltas: FunctionDelta[] = []
  const deltaByName = new Map<string, number>()

  for (const name of names) {
    const cur = currentSelf.get(name) ?? 0
    const base = baseSelf.get(name) ?? 0
    const delta = cur - base
    deltaByName.set(name, delta)
    if (Math.abs(delta) >= 0.05) {
      deltas.push({name, currentPercent: cur, basePercent: base, deltaPercent: delta})
    }
  }

  const byDeltaDesc = [...deltas].sort((a, b) => b.deltaPercent - a.deltaPercent)
  return {
    deltaByName,
    topRegressions: byDeltaDesc.filter((d) => d.deltaPercent > 0).slice(0, limit),
    topImprovements: byDeltaDesc
      .filter((d) => d.deltaPercent < 0)
      .reverse()
      .slice(0, limit),
  }
}

function selfPercentByName(frames: FlameNode[]): Map<string, number> {
  const annotated = annotateSelf(frames)
  const total = sumValues(frames) || 1
  const map = new Map<string, number>()
  function walk(node: FlameNode) {
    map.set(node.name, (map.get(node.name) ?? 0) + (node.self ?? 0))
    for (const child of node.children) walk(child)
  }
  for (const frame of annotated) walk(frame)
  for (const [name, self] of map) map.set(name, (self / total) * 100)
  return map
}

// ─────────────────────────── colours ───────────────────────────

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

const KIND_COLORS: Record<FrameKind, string> = {
  app: 'hsl(142, 60%, 45%)',
  library: 'hsl(205, 32%, 46%)',
  runtime: 'hsl(220, 8%, 42%)',
}

export function kindColor(kind: FrameKind): string {
  return KIND_COLORS[kind]
}

export function packageColor(name: string): string {
  for (const [pattern, color] of PACKAGE_COLORS) {
    if (pattern.test(name)) return color
  }
  let hash = 0
  for (let i = 0; i < name.length; i++) {
    hash = (hash * 31 + name.charCodeAt(i)) | 0
  }
  return FALLBACK_COLORS[Math.abs(hash) % FALLBACK_COLORS.length]
}

export interface ColorContext {
  mode: ColorMode
  language: ProfileLanguage
  appPrefixes: string[]
}

export function colorFor(name: string, ctx: ColorContext): string {
  if (ctx.mode === 'kind') {
    return kindColor(
      classifyFrame(name, {language: ctx.language, appPrefixes: ctx.appPrefixes}),
    )
  }
  return packageColor(name)
}

/** Red for hotter-now, blue for cooler-now, grey for unchanged. */
export function diffColor(deltaPercent: number): string {
  const magnitude = Math.min(Math.abs(deltaPercent) / 5, 1)
  const lightness = 60 - magnitude * 22
  if (deltaPercent > 0.05) return `hsl(0, ${30 + magnitude * 45}%, ${lightness}%)`
  if (deltaPercent < -0.05) return `hsl(210, ${30 + magnitude * 45}%, ${lightness}%)`
  return 'hsl(220, 6%, 40%)'
}

// ─────────────────────────── name formatting ───────────────────────────

function stripSignature(name: string): string {
  const paren = name.indexOf('(')
  return paren > -1 ? name.slice(0, paren) : name
}

export function packageOf(name: string): string {
  const base = stripSignature(name)
  const lastDot = base.lastIndexOf('.')
  if (lastDot === -1) return base
  return base.slice(0, lastDot)
}

/** Compact `Class.method()` label from a fully-qualified frame name. */
export function shortName(fullName: string): string {
  const paren = fullName.indexOf('(')
  const base = paren > -1 ? fullName.slice(0, paren) : fullName
  const slash = base.lastIndexOf('/')
  const afterSlash = slash > -1 ? base.slice(slash + 1) : base
  const parts = afterSlash.split('.')
  if (parts.length <= 2) return fullName
  const className = parts[parts.length - 2]
  const method = parts[parts.length - 1]
  return `${className}.${method}${paren > -1 ? '()' : ''}`
}
