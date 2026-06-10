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

const UUID_LENGTH = 36
const MIN_HEX_TOKEN_CHARS = 12
const MIN_PREFIXED_ID_SUFFIX_CHARS = 3
const UUID_DASH_OFFSETS = new Set([8, 13, 18, 23])
const UUID_REPLACEMENT = '<uuid>'
const ID_REPLACEMENT = '<id>'
const HEX_REPLACEMENT = '<hex>'
const STRING_REPLACEMENT = '<str>'
const FLOAT_REPLACEMENT = '<float>'
const INT_REPLACEMENT = '<int>'
const PATTERN_PLACEHOLDERS = [
  UUID_REPLACEMENT,
  ID_REPLACEMENT,
  HEX_REPLACEMENT,
  STRING_REPLACEMENT,
  FLOAT_REPLACEMENT,
  INT_REPLACEMENT,
]

/**
 * Collapse a log message into a structural pattern. Order matters: the most
 * specific shapes (uuid, prefixed id, hex) are replaced before bare numbers so
 * their digits are not partially consumed.
 */
export function derivePatternString(message: string): string {
  if (!message) return ''
  let index = 0
  let output = ''
  while (index < message.length) {
    const replacement = patternReplacementAt(message, index)
    if (replacement) {
      output += replacement.value
      index = replacement.nextIndex
    } else {
      output += message[index]
      index += 1
    }
  }
  return output.trim()
}

interface PatternReplacement {
  value: string
  nextIndex: number
}

function patternReplacementAt(value: string, index: number): PatternReplacement | null {
  return (
    quotedReplacementAt(value, index) ??
    uuidReplacementAt(value, index) ??
    prefixedIdReplacementAt(value, index) ??
    hexReplacementAt(value, index) ??
    floatReplacementAt(value, index) ??
    intReplacementAt(value, index)
  )
}

function quotedReplacementAt(value: string, index: number): PatternReplacement | null {
  const quote = value[index]
  if (quote !== '"' && quote !== "'") return null
  let end = index + 1
  while (end < value.length && value[end] !== quote) end += 1
  return {value: STRING_REPLACEMENT, nextIndex: end < value.length ? end + 1 : value.length}
}

function uuidReplacementAt(value: string, index: number): PatternReplacement | null {
  const end = index + UUID_LENGTH
  if (end > value.length || !hasWordBoundaries(value, index, end)) return null
  for (let offset = 0; offset < UUID_LENGTH; offset += 1) {
    const char = value[index + offset]
    if (UUID_DASH_OFFSETS.has(offset)) {
      if (char !== '-') return null
    } else if (!isHexDigit(char)) {
      return null
    }
  }
  return {value: UUID_REPLACEMENT, nextIndex: end}
}

function prefixedIdReplacementAt(value: string, index: number): PatternReplacement | null {
  if (!isLowercaseAsciiLetter(value[index])) return null
  let cursor = index + 1
  while (cursor < value.length && isLowercaseAsciiLetterOrDigit(value[cursor])) cursor += 1
  if (cursor >= value.length || value[cursor] !== '_') return null

  const suffixStart = cursor + 1
  cursor = suffixStart
  while (cursor < value.length && isAsciiLetterOrDigit(value[cursor])) cursor += 1

  if (cursor - suffixStart < MIN_PREFIXED_ID_SUFFIX_CHARS || !hasWordBoundaries(value, index, cursor)) {
    return null
  }
  return {value: ID_REPLACEMENT, nextIndex: cursor}
}

function hexReplacementAt(value: string, index: number): PatternReplacement | null {
  const hexStart = hasHexPrefix(value, index) ? index + 2 : index
  let cursor = hexStart
  while (cursor < value.length && isHexDigit(value[cursor])) cursor += 1
  if (cursor - hexStart < MIN_HEX_TOKEN_CHARS || !hasWordBoundaries(value, index, cursor)) return null
  return {value: HEX_REPLACEMENT, nextIndex: cursor}
}

function floatReplacementAt(value: string, index: number): PatternReplacement | null {
  const firstDigitsEnd = consumeDigits(value, index)
  if (firstDigitsEnd === index || firstDigitsEnd >= value.length || value[firstDigitsEnd] !== '.') return null
  const end = consumeDigits(value, firstDigitsEnd + 1)
  if (end === firstDigitsEnd + 1 || !hasWordBoundaries(value, index, end)) return null
  return {value: FLOAT_REPLACEMENT, nextIndex: end}
}

function intReplacementAt(value: string, index: number): PatternReplacement | null {
  const end = consumeDigits(value, index)
  if (end === index || !hasWordBoundaries(value, index, end)) return null
  return {value: INT_REPLACEMENT, nextIndex: end}
}

function consumeDigits(value: string, index: number): number {
  let cursor = index
  while (cursor < value.length && isDigit(value[cursor])) cursor += 1
  return cursor
}

function hasHexPrefix(value: string, index: number): boolean {
  return index + 2 < value.length && value[index] === '0' && (value[index + 1] === 'x' || value[index + 1] === 'X')
}

function hasWordBoundaries(value: string, start: number, end: number): boolean {
  const previousIsWord = start > 0 && isRegexWordChar(value[start - 1])
  const nextIsWord = end < value.length && isRegexWordChar(value[end])
  return !previousIsWord && !nextIsWord
}

function isRegexWordChar(char: string): boolean {
  return isAsciiLetterOrDigit(char) || char === '_'
}

function isAsciiLetterOrDigit(char: string): boolean {
  return isLowercaseAsciiLetterOrDigit(char) || (char >= 'A' && char <= 'Z')
}

function isLowercaseAsciiLetterOrDigit(char: string): boolean {
  return isLowercaseAsciiLetter(char) || isDigit(char)
}

function isLowercaseAsciiLetter(char: string): boolean {
  return char >= 'a' && char <= 'z'
}

function isHexDigit(char: string): boolean {
  return isDigit(char) || (char >= 'a' && char <= 'f') || (char >= 'A' && char <= 'F')
}

function isDigit(char: string): boolean {
  return char >= '0' && char <= '9'
}

/** Split a pattern string into literal/placeholder segments for rendering. */
export interface PatternSegment {
  text: string
  wildcard: boolean
}

export function splitPatternSegments(pattern: string): PatternSegment[] {
  if (!pattern) return []
  const segments: PatternSegment[] = []
  let index = 0
  while (index < pattern.length) {
    const placeholder = PATTERN_PLACEHOLDERS.find((token) => pattern.startsWith(token, index))
    if (placeholder) {
      segments.push({text: placeholder, wildcard: true})
      index += placeholder.length
      continue
    }
    const nextPlaceholder = nextPatternPlaceholderIndex(pattern, index)
    const end = nextPlaceholder === -1 ? pattern.length : nextPlaceholder
    segments.push({text: pattern.slice(index, end), wildcard: false})
    index = end
  }
  return segments
}

function nextPatternPlaceholderIndex(pattern: string, from: number): number {
  let next = -1
  for (const token of PATTERN_PLACEHOLDERS) {
    const index = pattern.indexOf(token, from)
    if (index !== -1 && (next === -1 || index < next)) {
      next = index
    }
  }
  return next
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

export function guessAttrType(value: string): AttrType {
  if (value === 'true' || value === 'false') return 'bool'
  if (isSignedInteger(value)) return 'int'
  if (isSignedFloat(value)) return 'float'
  if (isIsoTimePrefix(value)) return 'time'
  return 'str'
}

function isSignedInteger(value: string): boolean {
  const start = value.startsWith('-') ? 1 : 0
  return start < value.length && consumeDigits(value, start) === value.length
}

function isSignedFloat(value: string): boolean {
  const start = value.startsWith('-') ? 1 : 0
  const wholeEnd = consumeDigits(value, start)
  if (wholeEnd === start || wholeEnd >= value.length || value[wholeEnd] !== '.') return false
  return consumeDigits(value, wholeEnd + 1) === value.length && wholeEnd + 1 < value.length
}

function isIsoTimePrefix(value: string): boolean {
  return (
    value.length >= 16 &&
    isDigit(value[0]) &&
    isDigit(value[1]) &&
    isDigit(value[2]) &&
    isDigit(value[3]) &&
    value[4] === '-' &&
    isDigit(value[5]) &&
    isDigit(value[6]) &&
    value[7] === '-' &&
    isDigit(value[8]) &&
    isDigit(value[9]) &&
    value[10] === 'T' &&
    isDigit(value[11]) &&
    isDigit(value[12]) &&
    value[13] === ':' &&
    isDigit(value[14]) &&
    isDigit(value[15])
  )
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

export interface VolumeBucket {
  id: string
  count: number
  hot: boolean
}

export interface VolumeStrip {
  buckets: VolumeBucket[]
  /** Horizontal position of the anchor marker, 0–100. */
  markerPct: number
}

function emptyVolumeBuckets(bins: number): VolumeBucket[] {
  return Array.from({length: bins}, (_, index) => ({id: `bucket-${index}`, count: 0, hot: false}))
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
    return {buckets: emptyVolumeBuckets(bins), markerPct: 50}
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
    buckets: counts.map((count, index) => ({id: `bucket-${index}`, count, hot: index === anchorBin})),
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
export interface JsonToken {
  text: string
  kind: JsonKind
}

function nextNonWhitespaceIndex(value: string, index: number): number {
  let cursor = index
  while (cursor < value.length && isJsonWhitespace(value[cursor])) cursor += 1
  return cursor
}

function isJsonWhitespace(char: string): boolean {
  return char === ' ' || char === '\n' || char === '\r' || char === '\t'
}

function pushPlainToken(tokens: JsonToken[], json: string, from: number, to: number) {
  if (to > from) tokens.push({text: json.slice(from, to), kind: 'plain'})
}

/** Split a pretty-printed JSON string into classified segments for rendering. */
export function tokenizeJson(json: string): JsonToken[] {
  const tokens: JsonToken[] = []
  let plainStart = 0
  let index = 0

  while (index < json.length) {
    const stringEnd = jsonStringEnd(json, index)
    if (stringEnd !== -1) {
      const tokenEnd = stringEnd
      const colonIndex = nextNonWhitespaceIndex(json, tokenEnd)
      const isKey = json[colonIndex] === ':'
      const end = isKey ? colonIndex + 1 : tokenEnd
      pushPlainToken(tokens, json, plainStart, index)
      tokens.push({text: json.slice(index, end), kind: isKey ? 'key' : 'string'})
      index = end
      plainStart = index
      continue
    }

    const numberEnd = jsonNumberEnd(json, index)
    if (numberEnd !== -1) {
      pushPlainToken(tokens, json, plainStart, index)
      tokens.push({text: json.slice(index, numberEnd), kind: 'number'})
      index = numberEnd
      plainStart = index
      continue
    }

    const literalEnd = jsonLiteralEnd(json, index)
    if (literalEnd !== -1) {
      pushPlainToken(tokens, json, plainStart, index)
      tokens.push({text: json.slice(index, literalEnd), kind: 'boolean'})
      index = literalEnd
      plainStart = index
      continue
    }

    index += 1
  }

  pushPlainToken(tokens, json, plainStart, json.length)
  return tokens
}

function jsonStringEnd(value: string, index: number): number {
  if (value[index] !== '"') return -1
  let cursor = index + 1
  while (cursor < value.length) {
    if (value[cursor] === '\\') {
      cursor += 2
    } else if (value[cursor] === '"') {
      return cursor + 1
    } else {
      cursor += 1
    }
  }
  return -1
}

function jsonNumberEnd(value: string, index: number): number {
  let cursor = index
  if (value[cursor] === '-') cursor += 1
  const integerStart = cursor
  cursor = consumeDigits(value, cursor)
  if (cursor === integerStart) return -1
  if (value[cursor] === '.') {
    const fractionStart = cursor + 1
    cursor = consumeDigits(value, fractionStart)
    if (cursor === fractionStart) return -1
  }
  if (value[cursor] === 'e' || value[cursor] === 'E') {
    const exponentStart = value[cursor + 1] === '+' || value[cursor + 1] === '-' ? cursor + 2 : cursor + 1
    const exponentEnd = consumeDigits(value, exponentStart)
    if (exponentEnd === exponentStart) return -1
    cursor = exponentEnd
  }
  return cursor
}

function jsonLiteralEnd(value: string, index: number): number {
  if (value.startsWith('true', index)) return index + 4
  if (value.startsWith('false', index)) return index + 5
  if (value.startsWith('null', index)) return index + 4
  return -1
}
