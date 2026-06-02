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

import {Badge} from '@/components/ui/badge'
import {StatusDot} from '@/components/ui/status-dot'
import type {LogEntry} from '@/lib/api'
import {cn} from '@/lib/utils'
import {levelBorderClass, levelTone, logLevelBadgeClass} from '@/lib/severity'
import {stripAnsi} from '@/lib/ansi'
import {groupExceptionLogs, type LogGroup} from '@/components/logs/groupExceptionLogs'
import {getNow} from '@/lib/demo'
import {useTimezone} from '@/hooks/useTimezone'
import {formatDateTimeWithMs, formatTimeWithMs} from '@/lib/date-format'

interface LogTableProps {
  logs: LogEntry[]
  selectedLogId?: string | null
  onSelectLog: (log: LogEntry) => void
  emptyMessage?: string
  compact?: boolean
  /** Group exception stack traces into single rows (default: true) */
  groupExceptions?: boolean
}

// Level badge / border / dot tones all come from the shared severity helper
// (@/lib/severity) so log levels read the same everywhere.
const STACK_TRACE_TAG_KEYS = ['exception.stacktrace', 'exception.stack_trace']

function formatTimestamp(value: string, timezone: string): string {
  const date = new Date(value)
  if (Number.isNaN(date.getTime())) return value
  return formatDateTimeWithMs(date, timezone)
}

function formatMobileTimestamp(value: string, timezone: string): string {
  const date = new Date(value)
  if (Number.isNaN(date.getTime())) return value
  return formatTimeWithMs(date, timezone)
}

function formatRelativeTime(value: string): string {
  const now = getNow()
  const date = new Date(value)
  if (Number.isNaN(date.getTime())) return value
  const diffMs = now - date.getTime()
  if (diffMs < 0) return 'just now'
  if (diffMs < 1000) return 'just now'
  if (diffMs < 60_000) return `${Math.floor(diffMs / 1000)}s ago`
  if (diffMs < 3_600_000) return `${Math.floor(diffMs / 60_000)}m ago`
  if (diffMs < 86_400_000) return `${Math.floor(diffMs / 3_600_000)}h ago`
  return `${Math.floor(diffMs / 86_400_000)}d ago`
}

function hasStackTraceTag(tags: Record<string, string>): boolean {
  return STACK_TRACE_TAG_KEYS.some((key) => Boolean(tags[key]?.trim()))
}

function getLogText(log: LogEntry): string {
  return stripAnsi(log.message || log.body || '').trim()
}

function getStackTraceLine(log: LogEntry): string {
  return stripAnsi(log.message || log.body || '').trimEnd()
}

function getGroupedStackTrace(group: LogGroup): string {
  return group.logs
    .slice(1)
    .map((log) => getStackTraceLine(log))
    .filter((line) => line.trim())
    .join('\n')
}

function toDisplayLog(group: LogGroup): LogEntry {
  const first = group.logs[0]!
  if (group.logs.length === 1) return first
  const stackTrace = getGroupedStackTrace(group)
  if (!stackTrace || hasStackTraceTag(first.tags)) return first

  return {
    ...first,
    message: first.message || first.body,
    tags: {
      ...first.tags,
      'exception.stacktrace': stackTrace,
    },
  }
}

export function LogTable({logs, selectedLogId, onSelectLog, compact = true, groupExceptions = true}: LogTableProps) {
  const { timezone } = useTimezone()
  if (logs.length === 0) {
    return null // Empty state is handled by parent
  }

  const groups = groupExceptions
    ? groupExceptionLogs(logs)
    : logs.map((log) => ({logs: [log], mergedMessage: getLogText(log)}))

  return (
    <div className="min-w-0 max-w-full bg-card/80">
      <table className="w-full min-w-0 table-auto text-sm">
        <colgroup>
          <col className="w-[3px]" />
          <col className="w-[1%]" />
          <col className="hidden sm:table-column w-[1%]" />
          <col className="hidden lg:table-column w-[1%]" />
          <col className="hidden md:table-column w-[1%]" />
          <col />
        </colgroup>
          <thead className="sticky top-0 z-10 bg-card/95 backdrop-blur-sm">
            <tr className="border-b text-left">
              <th className="w-[3px] p-0" />
              <th className={cn("w-[1%] whitespace-nowrap px-1.5 text-[10px] font-semibold uppercase tracking-wider text-muted-foreground", compact ? "py-1" : "py-2")}>Date</th>
              <th className={cn("hidden sm:table-cell w-[1%] whitespace-nowrap px-1.5 text-[10px] font-semibold uppercase tracking-wider text-muted-foreground", compact ? "py-1" : "py-2")}>Level</th>
              <th className={cn("hidden lg:table-cell w-[1%] whitespace-nowrap px-1.5 text-[10px] font-semibold uppercase tracking-wider text-muted-foreground", compact ? "py-1" : "py-2")}>Host</th>
              <th className={cn("hidden md:table-cell w-[1%] whitespace-nowrap px-1.5 text-[10px] font-semibold uppercase tracking-wider text-muted-foreground", compact ? "py-1" : "py-2")}>Service</th>
              <th className={cn("w-full px-1.5 text-[10px] font-semibold uppercase tracking-wider text-muted-foreground", compact ? "py-1" : "py-2")}>Content</th>
            </tr>
          </thead>
          <tbody className="divide-y divide-border/50">
            {groups.map((group) => {
              const log = toDisplayLog(group)
              const normalizedLevel = (log.level || 'info').toLowerCase()
              const isSelected = group.logs.some((l) => l.logId === selectedLogId)
              return (
                <tr
                  key={log.logId}
                  className={cn(
                    'group cursor-pointer transition-colors',
                    isSelected
                      ? 'bg-primary/[0.06] dark:bg-primary/[0.08]'
                      : 'hover:bg-accent/40',
                    normalizedLevel === 'error' && !isSelected && 'bg-danger-bg/40',
                    normalizedLevel === 'fatal' && !isSelected && 'bg-danger-bg/60',
                    normalizedLevel === 'warn' && !isSelected && 'bg-warning-bg/40'
                  )}
                  onClick={() => onSelectLog(log)}
                >
                  <td className={cn('w-[3px] p-0 border-l-[3px]', levelBorderClass(normalizedLevel))} />
                  <td className={cn("whitespace-nowrap px-1.5", compact ? "py-0.5" : "py-1.5")}>
                    {compact ? (
                      <>
                        <span className="hidden sm:inline font-mono text-[10px] text-foreground/80">{formatTimestamp(log.timestamp, timezone)}</span>
                        <span className="sm:hidden font-mono text-[10px] text-foreground/80">{formatMobileTimestamp(log.timestamp, timezone)}</span>
                      </>
                    ) : (
                      <div className="flex flex-col">
                        <span className="hidden sm:inline font-mono text-xs text-foreground/80">{formatTimestamp(log.timestamp, timezone)}</span>
                        <span className="sm:hidden font-mono text-xs text-foreground/80">{formatMobileTimestamp(log.timestamp, timezone)}</span>
                        <span className="font-mono text-[10px] text-muted-foreground/60">{formatRelativeTime(log.timestamp)}</span>
                      </div>
                    )}
                  </td>
                  <td className={cn("hidden sm:table-cell whitespace-nowrap px-1.5", compact ? "py-0.5" : "py-1.5")}>
                    <Badge
                      variant="outline"
                      className={cn(
                        'font-mono uppercase px-1 py-0',
                        compact ? 'text-[9px]' : 'text-[10px]',
                        logLevelBadgeClass(normalizedLevel)
                      )}
                    >
                      {normalizedLevel}
                    </Badge>
                  </td>
                  <td className={cn("hidden lg:table-cell whitespace-nowrap px-1.5", compact ? "py-0.5" : "py-1.5")}>
                    {log.host ? (
                      <span className={cn("rounded bg-muted/80 px-1 py-0.5 font-mono text-muted-foreground", compact ? "text-[10px]" : "text-xs")}>{log.host}</span>
                    ) : (
                      <span className="text-xs text-muted-foreground/40">-</span>
                    )}
                  </td>
                  <td className={cn("hidden md:table-cell whitespace-nowrap px-1.5", compact ? "py-0.5" : "py-1.5")}>
                    {log.service ? (
                      <span className={cn("rounded bg-muted/80 px-1 py-0.5 font-mono text-muted-foreground", compact ? "text-[10px]" : "text-xs")}>{log.service}</span>
                    ) : (
                      <span className="text-xs text-muted-foreground/40">-</span>
                    )}
                  </td>
                  <td className={cn("min-w-0 px-1.5", compact ? "py-0.5" : "py-1.5")}>
                    <span className={cn(
                      'break-all font-mono leading-tight',
                      compact ? 'text-[10px] line-clamp-1' : 'text-xs line-clamp-2',
                      normalizedLevel === 'error' || normalizedLevel === 'fatal'
                        ? 'text-foreground'
                        : 'text-foreground/80'
                    )}>
                      {/* Show colored dot on mobile to indicate level since column is hidden */}
                      <StatusDot
                        tone={levelTone(normalizedLevel)}
                        size="sm"
                        className="sm:hidden mr-1 align-middle"
                      />
                      {stripAnsi(log.message || log.body) || '-'}
                    </span>
                  </td>
                </tr>
              )
            })}
          </tbody>
        </table>
      </div>
    )
  }
