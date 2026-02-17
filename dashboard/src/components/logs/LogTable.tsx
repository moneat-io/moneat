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
import type {LogEntry} from '@/lib/api'
import {cn} from '@/lib/utils'
import {stripAnsi} from '@/lib/ansi'
import {groupExceptionLogs, type LogGroup} from '@/components/logs/groupExceptionLogs'
import { getNow } from '@/lib/demo'

interface LogTableProps {
  logs: LogEntry[]
  selectedLogId?: string | null
  onSelectLog: (log: LogEntry) => void
  emptyMessage?: string
  compact?: boolean
  /** Group exception stack traces into single rows (default: true) */
  groupExceptions?: boolean
}

const levelStyles: Record<string, string> = {
  trace: 'bg-zinc-500/15 text-zinc-700 dark:text-zinc-300 border-zinc-500/25',
  debug: 'bg-teal-500/15 text-teal-700 dark:text-teal-300 border-teal-500/25',
  info: 'bg-indigo-500/15 text-indigo-700 dark:text-indigo-300 border-indigo-500/25',
  warn: 'bg-amber-500/15 text-amber-700 dark:text-amber-300 border-amber-500/25',
  error: 'bg-red-500/15 text-red-700 dark:text-red-300 border-red-500/25',
  fatal: 'bg-rose-500/20 text-rose-700 dark:text-rose-300 border-rose-500/30',
}

const levelBorderColors: Record<string, string> = {
  trace: 'border-l-zinc-400/60',
  debug: 'border-l-teal-400/60',
  info: 'border-l-indigo-400/60',
  warn: 'border-l-amber-400/70',
  error: 'border-l-red-500/80',
  fatal: 'border-l-rose-500/90',
}

function formatTimestamp(value: string): string {
  const date = new Date(value)
  if (Number.isNaN(date.getTime())) return value
  const base = date.toLocaleString(undefined, {
    month: 'short',
    day: 'numeric',
    hour: '2-digit',
    minute: '2-digit',
    second: '2-digit',
  })
  const ms = String(date.getMilliseconds()).padStart(3, '0')
  return `${base}.${ms}`
}

function formatMobileTimestamp(value: string): string {
  const date = new Date(value)
  if (Number.isNaN(date.getTime())) return value
  const time = date.toLocaleTimeString(undefined, {
    hour12: false,
    hour: '2-digit',
    minute: '2-digit',
    second: '2-digit',
  })
  const ms = String(date.getMilliseconds()).padStart(3, '0')
  return `${time}.${ms}`
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

function toDisplayLog(group: LogGroup): LogEntry {
  const first = group.logs[0]!
  if (group.logs.length === 1) return first
  return {
    ...first,
    message: group.mergedMessage,
    body: '',
  }
}

export function LogTable({logs, selectedLogId, onSelectLog, compact = true, groupExceptions = true}: LogTableProps) {
  if (logs.length === 0) {
    return null // Empty state is handled by parent
  }

  const groups = groupExceptions ? groupExceptionLogs(logs) : logs.map((log) => ({logs: [log], mergedMessage: (log.message || log.body || '').trim()}))

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
              <th className={cn("w-[1%] whitespace-nowrap px-2 text-[11px] font-semibold uppercase tracking-wider text-muted-foreground", compact ? "py-1.5" : "py-2")}>Date</th>
              <th className={cn("hidden sm:table-cell w-[1%] whitespace-nowrap px-2 text-[11px] font-semibold uppercase tracking-wider text-muted-foreground", compact ? "py-1.5" : "py-2")}>Level</th>
              <th className={cn("hidden lg:table-cell w-[1%] whitespace-nowrap px-2 text-[11px] font-semibold uppercase tracking-wider text-muted-foreground", compact ? "py-1.5" : "py-2")}>Host</th>
              <th className={cn("hidden md:table-cell w-[1%] whitespace-nowrap px-2 text-[11px] font-semibold uppercase tracking-wider text-muted-foreground", compact ? "py-1.5" : "py-2")}>Service</th>
              <th className={cn("w-full px-2 text-[11px] font-semibold uppercase tracking-wider text-muted-foreground", compact ? "py-1.5" : "py-2")}>Content</th>
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
                    normalizedLevel === 'error' && !isSelected && 'bg-red-500/[0.02] dark:bg-red-500/[0.03]',
                    normalizedLevel === 'fatal' && !isSelected && 'bg-rose-500/[0.03] dark:bg-rose-500/[0.04]',
                    normalizedLevel === 'warn' && !isSelected && 'bg-amber-500/[0.02] dark:bg-amber-500/[0.02]'
                  )}
                  onClick={() => onSelectLog(log)}
                >
                  <td className={cn('w-[3px] p-0 border-l-[3px]', levelBorderColors[normalizedLevel] || 'border-l-transparent')} />
                  <td className={cn("whitespace-nowrap px-2", compact ? "py-1" : "py-1.5")}>
                    {compact ? (
                      <>
                        <span className="hidden sm:inline font-mono text-[11px] text-foreground/80">{formatTimestamp(log.timestamp)}</span>
                        <span className="sm:hidden font-mono text-[11px] text-foreground/80">{formatMobileTimestamp(log.timestamp)}</span>
                      </>
                    ) : (
                      <div className="flex flex-col">
                        <span className="hidden sm:inline font-mono text-xs text-foreground/80">{formatTimestamp(log.timestamp)}</span>
                        <span className="sm:hidden font-mono text-xs text-foreground/80">{formatMobileTimestamp(log.timestamp)}</span>
                        <span className="font-mono text-[10px] text-muted-foreground/60">{formatRelativeTime(log.timestamp)}</span>
                      </div>
                    )}
                  </td>
                  <td className={cn("hidden sm:table-cell whitespace-nowrap px-2", compact ? "py-1" : "py-1.5")}>
                    <Badge
                      variant="outline"
                      className={cn(
                        'font-mono uppercase px-1.5 py-0',
                        compact ? 'text-[9px]' : 'text-[10px]',
                        levelStyles[normalizedLevel] || levelStyles.info
                      )}
                    >
                      {normalizedLevel}
                    </Badge>
                  </td>
                  <td className={cn("hidden lg:table-cell whitespace-nowrap px-2", compact ? "py-1" : "py-1.5")}>
                    {log.host ? (
                      <span className={cn("rounded bg-muted/80 px-1.5 py-0.5 font-mono text-muted-foreground", compact ? "text-[11px]" : "text-xs")}>{log.host}</span>
                    ) : (
                      <span className="text-xs text-muted-foreground/40">-</span>
                    )}
                  </td>
                  <td className={cn("hidden md:table-cell whitespace-nowrap px-2", compact ? "py-1" : "py-1.5")}>
                    {log.service ? (
                      <span className={cn("rounded bg-muted/80 px-1.5 py-0.5 font-mono text-muted-foreground", compact ? "text-[11px]" : "text-xs")}>{log.service}</span>
                    ) : (
                      <span className="text-xs text-muted-foreground/40">-</span>
                    )}
                  </td>
                  <td className={cn("min-w-0 px-2", compact ? "py-1" : "py-1.5")}>
                    <span className={cn(
                      'break-all font-mono leading-snug',
                      compact ? 'text-[11px] line-clamp-1' : 'text-xs line-clamp-2',
                      normalizedLevel === 'error' || normalizedLevel === 'fatal'
                        ? 'text-foreground'
                        : 'text-foreground/80'
                    )}>
                      {/* Show colored dot on mobile to indicate level since column is hidden */}
                      <span className={cn(
                        "sm:hidden inline-block w-1.5 h-1.5 rounded-full mr-1.5 mb-0.5",
                        normalizedLevel === 'trace' && "bg-zinc-500",
                        normalizedLevel === 'debug' && "bg-teal-500",
                        normalizedLevel === 'info' && "bg-indigo-500",
                        normalizedLevel === 'warn' && "bg-amber-500",
                        normalizedLevel === 'error' && "bg-red-500",
                        normalizedLevel === 'fatal' && "bg-rose-500"
                      )} />
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
