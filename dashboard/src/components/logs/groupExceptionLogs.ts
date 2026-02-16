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
const EXCEPTION_CONTINUATION_PATTERNS = [
  /^Exception type:/i,
  /^=== FINGERPRINT/i,
  /^Final fingerpr/i,
  /^Selected frame:/i,
  /^Total frames:/i,
  /^\tat\s+(com\.|org\.|kotlin\.|java\.|io\.|net\.|sun\.)/,
  /^\s+at\s+(com\.|org\.|kotlin\.|java\.|io\.|net\.|sun\.)/,
  /^Caused by:/i,
  /^Suppressed:/i,
  /^\t\.\.\.\s+\d+ more$/,
]

const MAX_GROUP_MS = 2000 // Group logs within 2 seconds
const MAX_GROUP_LINES = 50 // Cap exception group size

function getLogText(log: LogEntry): string {
  return stripAnsi(log.message || log.body || '').trim()
}

function isExceptionContinuation(log: LogEntry): boolean {
  const text = getLogText(log)
  if (!text) return false
  return EXCEPTION_CONTINUATION_PATTERNS.some((p) => p.test(text))
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

/**
 * Groups consecutive log entries that belong to the same exception.
 * When Kotlin/SLF4J logs an exception with logger.error(e) { "msg" },
 * each stack trace line becomes a separate log entry. This merges them.
 */
export function groupExceptionLogs(logs: LogEntry[]): LogGroup[] {
  if (logs.length === 0) return []

  const groups: LogGroup[] = []
  let current: LogEntry[] = [logs[0]!]

  for (let i = 1; i < logs.length; i++) {
    const log = logs[i]!
    const prev = logs[i - 1]!

    const isContinuation = isExceptionContinuation(log)
    const sameCtx = sameContext(log, prev)

    if (isContinuation && sameCtx && current.length < MAX_GROUP_LINES) {
      current.push(log)
    } else {
      groups.push({
        logs: current,
        mergedMessage: current
          .map((l) => getLogText(l))
          .filter(Boolean)
          .join('\n'),
      })
      current = [log]
    }
  }

  groups.push({
    logs: current,
    mergedMessage: current
      .map((l) => getLogText(l))
      .filter(Boolean)
      .join('\n'),
  })

  return groups
}
