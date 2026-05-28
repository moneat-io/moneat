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
import {Badge} from '@/components/ui/badge'
import {Separator} from '@/components/ui/separator'
import {StackTraceBlock} from '@/components/logs/StackTraceBlock'
import {CopyButton, LinkableField} from '@/components/logs/LogDetailPanel'

const EXCEPTION_STACKTRACE_KEYS = [
  'exception.stacktrace',
  'exception.stack_trace',
  'exception.stack',
  'error.stack',
  'error.stack_trace',
  'stacktrace',
  'stack_trace',
]
const EXCEPTION_TYPE_KEYS = ['exception.type', 'error.type', 'error.kind']
const EXCEPTION_MESSAGE_KEYS = ['exception.message', 'error.message']

function firstTagValue(tags: Record<string, string> | undefined, keys: readonly string[]): string {
  if (!tags) return ''
  for (const key of keys) {
    const value = tags[key]?.trim()
    if (value) return value
  }
  return ''
}

function tryFormatJson(value: string): {isJson: boolean; formatted: string} {
  try {
    const parsed = JSON.parse(value)
    return {isJson: true, formatted: JSON.stringify(parsed, null, 2)}
  } catch {
    return {isJson: false, formatted: value}
  }
}

interface LogOverviewTabProps {
  log: LogEntry
  projectId?: string | number
}

export function LogOverviewTab({log, projectId}: LogOverviewTabProps) {
  const body = stripAnsi(log.body || '')
  const message = stripAnsi(log.message || '')
  const {isJson, formatted: formattedBody} = body ? tryFormatJson(body) : {isJson: false, formatted: ''}

  const exceptionStacktrace = firstTagValue(log.tags, EXCEPTION_STACKTRACE_KEYS)
  const exceptionType = firstTagValue(log.tags, EXCEPTION_TYPE_KEYS)
  const exceptionMessage = firstTagValue(log.tags, EXCEPTION_MESSAGE_KEYS)

  const hasTracing = log.traceId || log.spanId

  return (
    <div className="space-y-5">
      {/* Message */}
      <div className="space-y-1.5">
        <span className="text-[11px] font-semibold uppercase tracking-wider text-muted-foreground">Message</span>
        <div className="group relative">
          <pre className="overflow-x-auto rounded-lg border bg-muted/40 p-3 font-mono text-xs leading-relaxed whitespace-pre-wrap break-words">
            {message || '-'}
          </pre>
          {message && <div className="absolute right-2 top-2 opacity-0 transition-opacity group-hover:opacity-100"><CopyButton value={message} label="message" /></div>}
        </div>
      </div>

      {/* Body */}
      {body && body !== message && (
        <div className="space-y-1.5">
          <div className="flex items-center gap-2">
            <span className="text-[11px] font-semibold uppercase tracking-wider text-muted-foreground">Body</span>
            {isJson && (
              <Badge variant="outline" className="text-[9px] font-mono py-0 px-1">JSON</Badge>
            )}
          </div>
          <div className="group relative">
            <pre className="max-h-[280px] overflow-auto rounded-lg border bg-muted/40 p-3 font-mono text-xs leading-relaxed whitespace-pre-wrap break-words">
              {formattedBody}
            </pre>
            <div className="absolute right-2 top-2 opacity-0 transition-opacity group-hover:opacity-100"><CopyButton value={body} label="body" /></div>
          </div>
        </div>
      )}

      {/* Exception — single syntax-highlighted block */}
      {exceptionStacktrace ? (
        <div className="space-y-1.5">
          <span className="text-[11px] font-semibold uppercase tracking-wider text-muted-foreground">Exception</span>
          <StackTraceBlock stacktrace={exceptionStacktrace} />
        </div>
      ) : (exceptionType || exceptionMessage) && (
        <div className="space-y-1.5">
          <span className="text-[11px] font-semibold uppercase tracking-wider text-muted-foreground">Exception</span>
          <div className="rounded-lg border border-red-500/20 bg-red-500/5 px-3 py-2 font-mono text-xs">
            {exceptionType && <div className="font-semibold text-red-700 dark:text-red-400">{exceptionType}</div>}
            {exceptionMessage && <div className="mt-0.5 text-foreground/80">{exceptionMessage}</div>}
          </div>
        </div>
      )}

      {/* Tracing */}
      {hasTracing && (
        <>
          <Separator />
          <div className="grid gap-3 sm:grid-cols-2">
            {log.traceId && (
              <LinkableField
                label="Trace ID"
                value={log.traceId}
                to={`/performance/traces/${log.traceId}`}
              />
            )}
            {log.spanId && projectId && (
              <LinkableField
                label="Span ID"
                value={log.spanId}
                to={`/projects/${encodeURIComponent(String(projectId))}/spans/${encodeURIComponent(log.spanId)}`}
              />
            )}
          </div>
        </>
      )}
    </div>
  )
}
