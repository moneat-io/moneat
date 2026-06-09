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

import type {ApmSpanResponse, LogEntry} from '@/lib/api'

// ============================================================================
// Pattern derivation (client-side fallback for the Patterns tab)
//
// The backend returns a richer, semantically-named pattern. Until that lands —
// or when it is unavailable — we collapse variable-looking tokens to typed
// placeholders so the "Matched pattern" string is still meaningful and honest.
// ============================================================================

const UUID_RE = /\b[0-9a-f]{8}-[0-9a-f]{4}-[0-9a-f]{4}-[0-9a-f]{4}-[0-9a-f]{12}\b/gi
// Prefixed identifiers such as ord_8F2K19, ch_3PqfQ2K, ik_b1c9, req_7c2a.
const PREFIXED_ID_RE = /\b[a-z][a-z0-9]*_[A-Za-z0-9]{3,}\b/g
// Bare hex blobs (>= 12 chars) and 0x-prefixed values.
const HEX_RE = /\b(?:0x)?[0-9a-f]{12,}\b/gi
const QUOTED_RE = /"[^"]*"|'[^']*'/g
const FLOAT_RE = /\b\d+\.\d+\b/g
const INT_RE = /\b\d+\b/g

/**
 * Collapse a log message into a structural pattern. Order matters: the most
 * specific shapes (uuid, prefixed id, hex) are replaced before bare numbers so
 * their digits are not partially consumed.
 */
export function derivePatternString(message: string): string {
  if (!message) return ''
  return message
    .replace(UUID_RE, '<uuid>')
    .replace(PREFIXED_ID_RE, '<id>')
    .replace(HEX_RE, '<hex>')
    .replace(QUOTED_RE, '<str>')
    .replace(FLOAT_RE, '<float>')
    .replace(INT_RE, '<int>')
    .trim()
}

/** Split a pattern string into literal/placeholder segments for rendering. */
export interface PatternSegment {
  text: string
  wildcard: boolean
}

export function splitPatternSegments(pattern: string): PatternSegment[] {
  if (!pattern) return []
  const segments: PatternSegment[] = []
  const re = /<[^>]+>/g
  let lastIndex = 0
  let match: RegExpExecArray | null
  while ((match = re.exec(pattern)) !== null) {
    if (match.index > lastIndex) {
      segments.push({text: pattern.slice(lastIndex, match.index), wildcard: false})
    }
    segments.push({text: match[0], wildcard: true})
    lastIndex = match.index + match[0].length
  }
  if (lastIndex < pattern.length) {
    segments.push({text: pattern.slice(lastIndex), wildcard: false})
  }
  return segments
}

// ============================================================================
// Attribute grouping (Attributes tab)
// ============================================================================

export type AttrType = 'str' | 'int' | 'float' | 'bool' | 'time'
export type AttrPill = 'err' | 'ok' | 'warn' | null

export interface AttrRow {
  key: string
  value: string
  type: AttrType
  /** Renders the value as a status pill (e.g. http status code). */
  pill: AttrPill
  /** Renders the value as a link to the trace/span surface. */
  linkable: boolean
}

export interface AttrGroup {
  name: string
  rows: AttrRow[]
}

const ISO_TIME_RE = /^\d{4}-\d{2}-\d{2}T\d{2}:\d{2}/

export function guessAttrType(value: string): AttrType {
  if (value === 'true' || value === 'false') return 'bool'
  if (/^-?\d+$/.test(value)) return 'int'
  if (/^-?\d+\.\d+$/.test(value)) return 'float'
  if (ISO_TIME_RE.test(value)) return 'time'
  return 'str'
}

function statusCodePill(value: string): AttrPill {
  const code = Number.parseInt(value, 10)
  if (Number.isNaN(code)) return null
  if (code >= 500) return 'err'
  if (code >= 400) return 'warn'
  if (code >= 200 && code < 300) return 'ok'
  return null
}

function levelPill(level: string): AttrPill {
  const l = level.toLowerCase()
  if (l === 'error' || l === 'fatal') return 'err'
  if (l === 'warn' || l === 'warning') return 'warn'
  return null
}

/** Title-case the first dotted segment of a key, used as a group heading. */
function groupNameForKey(key: string): string {
  const dot = key.indexOf('.')
  if (dot <= 0) return 'Custom'
  const prefix = key.slice(0, dot)
  if (prefix.length <= 4) return prefix.toUpperCase() // http, db, k8s, rpc…
  return prefix.charAt(0).toUpperCase() + prefix.slice(1)
}

function pillForKeyValue(key: string, value: string): AttrPill {
  const k = key.toLowerCase()
  if (k.endsWith('status_code') || k.endsWith('status') || k === 'http.status') {
    return statusCodePill(value)
  }
  return null
}

/**
 * Group a log's fields into the Reserved / Trace / per-prefix / Resource
 * sections the viewer renders. Empty values are dropped.
 */
export function groupLogAttributes(log: LogEntry): AttrGroup[] {
  const groups: AttrGroup[] = []

  const reserved: AttrRow[] = []
  const pushReserved = (key: string, value: string | undefined, pill: AttrPill = null) => {
    if (!value) return
    reserved.push({key, value, type: guessAttrType(value), pill, linkable: false})
  }
  pushReserved('status', log.level, levelPill(log.level || ''))
  pushReserved('service', log.service)
  pushReserved('env', log.environment)
  pushReserved('host', log.host)
  pushReserved('source', log.source)
  pushReserved('timestamp', log.timestamp)
  if (reserved.length > 0) groups.push({name: 'Reserved', rows: reserved})

  const trace: AttrRow[] = []
  if (log.traceId) trace.push({key: 'trace_id', value: log.traceId, type: 'str', pill: null, linkable: true})
  if (log.spanId) trace.push({key: 'span_id', value: log.spanId, type: 'str', pill: null, linkable: true})
  if (trace.length > 0) groups.push({name: 'Trace', rows: trace})

  // Tags grouped by dotted prefix; keys reserved above are skipped.
  const reservedTagKeys = new Set(['exception.stacktrace', 'exception.stack_trace'])
  const byPrefix = new Map<string, AttrRow[]>()
  for (const [key, value] of Object.entries(log.tags || {})) {
    if (!value || reservedTagKeys.has(key)) continue
    const groupName = groupNameForKey(key)
    const rows = byPrefix.get(groupName) ?? []
    rows.push({key, value, type: guessAttrType(value), pill: pillForKeyValue(key, value), linkable: false})
    byPrefix.set(groupName, rows)
  }
  // Stable ordering: known prefixes first by name, "Custom" last.
  const prefixNames = [...byPrefix.keys()].sort((a, b) => {
    if (a === 'Custom') return 1
    if (b === 'Custom') return -1
    return a.localeCompare(b)
  })
  for (const name of prefixNames) {
    groups.push({name, rows: byPrefix.get(name)!})
  }

  const resource: AttrRow[] = []
  for (const [key, value] of Object.entries(log.resourceAttributes || {})) {
    if (!value) continue
    resource.push({key, value, type: guessAttrType(value), pill: null, linkable: false})
  }
  if (resource.length > 0) groups.push({name: 'Resource', rows: resource})

  return groups
}

/** Total non-empty attribute count across all groups (for the tab badge). */
export function countLogAttributes(groups: AttrGroup[]): number {
  return groups.reduce((sum, g) => sum + g.rows.length, 0)
}

/** Case-insensitive key/value substring filter over grouped attributes. */
export function filterAttrGroups(groups: AttrGroup[], query: string): AttrGroup[] {
  const q = query.trim().toLowerCase()
  if (!q) return groups
  return groups
    .map((g) => ({
      name: g.name,
      rows: g.rows.filter((r) => r.key.toLowerCase().includes(q) || r.value.toLowerCase().includes(q)),
    }))
    .filter((g) => g.rows.length > 0)
}

// ============================================================================
// Context volume strip (Context tab)
// ============================================================================

export interface VolumeStrip {
  buckets: {count: number; hot: boolean}[]
  /** Horizontal position of the anchor marker, 0–100. */
  markerPct: number
}

/**
 * Bucket surrounding-log timestamps into a fixed number of bins across the
 * window, flag the bin holding the anchor as "hot", and locate the marker.
 */
export function buildContextVolume(timestampsMs: number[], anchorMs: number, bins = 21): VolumeStrip {
  const all = [...timestampsMs, anchorMs]
  let min = Math.min(...all)
  let max = Math.max(...all)
  if (!Number.isFinite(min) || !Number.isFinite(max)) {
    return {buckets: Array.from({length: bins}, () => ({count: 0, hot: false})), markerPct: 50}
  }
  if (min === max) {
    min -= 1
    max += 1
  }
  const range = max - min
  const binOf = (ts: number) => Math.min(bins - 1, Math.max(0, Math.floor(((ts - min) / range) * bins)))
  const counts = new Array<number>(bins).fill(0)
  for (const ts of timestampsMs) counts[binOf(ts)] += 1
  const anchorBin = binOf(anchorMs)
  return {
    buckets: counts.map((count, i) => ({count, hot: i === anchorBin})),
    markerPct: ((anchorMs - min) / range) * 100,
  }
}

// ============================================================================
// Time formatting
// ============================================================================

const MINUS = '−'

/** Format a signed millisecond delta relative to the anchor event. */
export function formatDeltaMs(deltaMs: number): string {
  if (deltaMs === 0) return '0ms'
  const sign = deltaMs > 0 ? '+' : MINUS
  const abs = Math.abs(deltaMs)
  if (abs < 1000) return `${sign}${Math.round(abs)}ms`
  if (abs < 60_000) return `${sign}${(abs / 1000).toFixed(2)}s`
  return `${sign}${(abs / 60_000).toFixed(1)}m`
}

/** Format a nanosecond span duration the way the trace waterfall labels it. */
export function formatTraceDuration(ns: number): string {
  const ms = ns / 1_000_000
  if (ms >= 1000) return `${(ms / 1000).toFixed(2)}s`
  if (ms >= 10) return `${ms.toFixed(0)}ms`
  if (ms >= 1) return `${ms.toFixed(1)}ms`
  return `${(ns / 1000).toFixed(0)}µs`
}

// ============================================================================
// Trace waterfall layout (Trace tab)
// ============================================================================

export interface WaterfallRow {
  span: ApmSpanResponse
  depth: number
  /** Bar start as a percent of the trace window, 0–100. */
  offsetPct: number
  /** Bar width as a percent of the trace window, clamped to a visible minimum. */
  widthPct: number
  hasError: boolean
}

export interface TraceLayout {
  rows: WaterfallRow[]
  traceStartNs: number
  traceDurationNs: number
  serviceCount: number
  errorCount: number
}

function spanHasError(span: ApmSpanResponse): boolean {
  return span.error > 0 || (span.statusCode != null && span.statusCode >= 500)
}

/**
 * Order spans into a depth-first waterfall (parents before children, each
 * sibling set sorted by start) and compute per-bar geometry relative to the
 * full trace window.
 */
export function computeTraceLayout(spans: ApmSpanResponse[]): TraceLayout {
  if (spans.length === 0) {
    return {rows: [], traceStartNs: 0, traceDurationNs: 1, serviceCount: 0, errorCount: 0}
  }

  const traceStartNs = Math.min(...spans.map((s) => s.startNs))
  const traceEndNs = Math.max(...spans.map((s) => s.startNs + s.durationNs))
  const traceDurationNs = Math.max(traceEndNs - traceStartNs, 1)

  const childrenByParent = new Map<string, ApmSpanResponse[]>()
  const ids = new Set(spans.map((s) => s.spanId))
  const roots: ApmSpanResponse[] = []
  for (const span of spans) {
    if (span.parentId && ids.has(span.parentId)) {
      const list = childrenByParent.get(span.parentId) ?? []
      list.push(span)
      childrenByParent.set(span.parentId, list)
    } else {
      roots.push(span)
    }
  }
  const byStart = (a: ApmSpanResponse, b: ApmSpanResponse) => a.startNs - b.startNs
  roots.sort(byStart)

  const rows: WaterfallRow[] = []
  const seen = new Set<string>()
  const walk = (span: ApmSpanResponse, depth: number) => {
    if (seen.has(span.spanId)) return // guard against cycles in malformed data
    seen.add(span.spanId)
    const offsetPct = ((span.startNs - traceStartNs) / traceDurationNs) * 100
    const rawWidth = (span.durationNs / traceDurationNs) * 100
    const widthPct = Math.min(Math.max(rawWidth, 0.5), 100 - offsetPct)
    rows.push({span, depth, offsetPct, widthPct, hasError: spanHasError(span)})
    const kids = childrenByParent.get(span.spanId)
    if (kids) {
      kids.sort(byStart)
      for (const kid of kids) walk(kid, depth + 1)
    }
  }
  for (const root of roots) walk(root, 0)
  // Append any spans left out by cycles so nothing silently disappears.
  for (const span of spans) {
    if (!seen.has(span.spanId)) walk(span, 0)
  }

  return {
    rows,
    traceStartNs,
    traceDurationNs,
    serviceCount: new Set(spans.map((s) => s.service).filter(Boolean)).size,
    errorCount: spans.filter(spanHasError).length,
  }
}

/**
 * Position of the "log emitted" pin on the trace timeline, as a percent of the
 * window. Returns null when the log falls well outside the trace bounds.
 */
export function computeLogPinPct(
  layout: Pick<TraceLayout, 'traceStartNs' | 'traceDurationNs'>,
  logTimestampMs: number
): number | null {
  if (!Number.isFinite(logTimestampMs)) return null
  const logNs = logTimestampMs * 1_000_000
  const pct = ((logNs - layout.traceStartNs) / layout.traceDurationNs) * 100
  if (pct < -10 || pct > 110) return null
  return Math.min(100, Math.max(0, pct))
}

/** Distinct trace span types present, for the waterfall legend. */
export function traceSpanTypes(spans: ApmSpanResponse[]): string[] {
  return [...new Set(spans.map((s) => (s.type || '').toLowerCase()).filter(Boolean))]
}

// ============================================================================
// JSON tokenizer (light syntax highlighting for Body / Attributes JSON)
// ============================================================================

export type JsonKind = 'key' | 'string' | 'number' | 'boolean' | 'plain'

const JSON_TOKEN_RE =
  /("(?:\\.|[^"\\])*"\s*:)|("(?:\\.|[^"\\])*")|(-?\d+(?:\.\d+)?(?:[eE][+-]?\d+)?)|(\btrue\b|\bfalse\b|\bnull\b)/g

/** Split a pretty-printed JSON string into classified segments for rendering. */
export function tokenizeJson(json: string): {text: string; kind: JsonKind}[] {
  const out: {text: string; kind: JsonKind}[] = []
  let lastIndex = 0
  let match: RegExpExecArray | null
  JSON_TOKEN_RE.lastIndex = 0
  while ((match = JSON_TOKEN_RE.exec(json)) !== null) {
    if (match.index > lastIndex) out.push({text: json.slice(lastIndex, match.index), kind: 'plain'})
    if (match[1]) out.push({text: match[1], kind: 'key'})
    else if (match[2]) out.push({text: match[2], kind: 'string'})
    else if (match[3]) out.push({text: match[3], kind: 'number'})
    else if (match[4]) out.push({text: match[4], kind: 'boolean'})
    lastIndex = match.index + match[0].length
  }
  if (lastIndex < json.length) out.push({text: json.slice(lastIndex), kind: 'plain'})
  return out
}
