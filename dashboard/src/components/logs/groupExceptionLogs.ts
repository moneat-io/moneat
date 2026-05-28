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

import type {LogEntry} from '@/lib/api'
import {stripAnsi} from '@/lib/ansi'

/**
 * Patterns that indicate a log line is part of an exception/stack trace
 * (e.g. from Kotlin/SLF4J when logger.error(e) { "msg" } outputs multiple entries)
 */
const EXCEPTION_METADATA_PATTERNS = [
  /^Exception type:/i,
  /^=== FINGERPRINT/i,
  /^Final fingerpr/i,
  /^Selected frame:/i,
  /^Total frames:/i,
  /^Caused by:/i,
  /^Suppressed:/i,
  /^\s*\.\.\.\s+\d+ more$/,
]

const MAX_GROUP_MS = 2000 // Group logs within 2 seconds
const MAX_GROUP_LINES = 50 // Cap exception group size
const STACK_FRAME_PATTERN = /^\s*at\s+/
const EXCEPTION_HEADER_PATTERN =
  /^(?:[A-Za-z_$][\w$]*\.)*[A-Za-z_$][\w$]*(?:Exception|Error|Throwable)(?::|$)/

function getLogText(log: LogEntry): string {
  return stripAnsi(log.message || log.body || '').trim()
}

function isStackFrameText(text: string): boolean {
  return STACK_FRAME_PATTERN.test(text)
}

function isExceptionMetadataText(text: string): boolean {
  return EXCEPTION_METADATA_PATTERNS.some((p) => p.test(text))
}

function isExceptionHeaderText(text: string): boolean {
  return EXCEPTION_HEADER_PATTERN.test(text)
}

function hasStackEvidence(log: LogEntry): boolean {
  const text = getLogText(log)
  return isStackFrameText(text) || isExceptionMetadataText(text)
}

function isExceptionContinuation(logs: LogEntry[], index: number): boolean {
  const text = getLogText(logs[index]!)
  if (!text) return false
  if (isStackFrameText(text) || isExceptionMetadataText(text)) return true

  if (!isExceptionHeaderText(text)) return false

  const previous = index > 0 ? getLogText(logs[index - 1]!) : ''
  const next = index + 1 < logs.length ? getLogText(logs[index + 1]!) : ''
  return isStackFrameText(previous) || isStackFrameText(next)
}

function sameContext(a: LogEntry, b: LogEntry): boolean {
  if ((a.service || '') !== (b.service || '')) return false
  if ((a.host || '') !== (b.host || '')) return false
  const ta = new Date(a.timestamp).getTime()
  const tb = new Date(b.timestamp).getTime()
  return Math.abs(ta - tb) <= MAX_GROUP_MS
}

export interface LogGroup {
  logs: LogEntry[]
  /** Merged message for display (all lines joined) */
  mergedMessage: string
}

function buildGroup(logs: LogEntry[]): LogGroup {
  return {
    logs,
    mergedMessage: logs
      .map((l) => getLogText(l))
      .filter(Boolean)
      .join('\n'),
  }
}

/**
 * Groups consecutive log entries that belong to the same exception.
 * When Kotlin/SLF4J logs an exception with logger.error(e) { "msg" },
 * each stack trace line becomes a separate log entry. This merges them.
 */
export function groupExceptionLogs(logs: LogEntry[]): LogGroup[] {
  if (logs.length === 0) return []

  const groups: LogGroup[] = []
  let current: LogEntry[] = [logs[0]!]
  let currentHasOnlyContinuations = isExceptionContinuation(logs, 0)
  let currentHasStackEvidence = hasStackEvidence(logs[0]!)

  for (let i = 1; i < logs.length; i++) {
    const log = logs[i]!
    const prev = logs[i - 1]!

    const isContinuation = isExceptionContinuation(logs, i)
    const sameCtx = sameContext(log, prev)

    if (isContinuation && sameCtx && current.length < MAX_GROUP_LINES) {
      current.push(log)
      currentHasStackEvidence ||= hasStackEvidence(log)
    } else if (
      !isContinuation &&
      sameCtx &&
      currentHasOnlyContinuations &&
      currentHasStackEvidence &&
      current.length < MAX_GROUP_LINES
    ) {
      current = [log, ...current.slice().reverse()]
      currentHasOnlyContinuations = false
    } else {
      groups.push(buildGroup(current))
      current = [log]
      currentHasOnlyContinuations = isContinuation
      currentHasStackEvidence = hasStackEvidence(log)
    }
  }

  groups.push(buildGroup(current))

  return groups
}
