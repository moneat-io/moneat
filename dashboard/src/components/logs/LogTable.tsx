import {Badge} from '@/components/ui/badge'
import type {LogEntry} from '@/lib/api'
import {cn} from '@/lib/utils'

interface LogTableProps {
  logs: LogEntry[]
  selectedLogId?: string | null
  onSelectLog: (log: LogEntry) => void
  emptyMessage?: string
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

function formatRelativeTime(value: string): string {
  const now = Date.now()
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

export function LogTable({logs, selectedLogId, onSelectLog}: LogTableProps) {
  if (logs.length === 0) {
    return null // Empty state is handled by parent
  }

  return (
    <div className="bg-card/80">
      <div className="overflow-auto">
        <table className="w-full table-auto text-sm">
          <colgroup>
            <col className="w-[3px]" />
            <col className="w-[1%]" />
            <col className="w-[1%]" />
            <col className="w-[1%]" />
            <col />
          </colgroup>
          <thead className="sticky top-0 z-10 bg-card/95 backdrop-blur-sm">
            <tr className="border-b text-left">
              <th className="w-[3px] p-0" />
              <th className="w-[1%] whitespace-nowrap px-2 py-2 text-[11px] font-semibold uppercase tracking-wider text-muted-foreground">Timestamp</th>
              <th className="w-[1%] whitespace-nowrap px-2 py-2 text-[11px] font-semibold uppercase tracking-wider text-muted-foreground">Level</th>
              <th className="w-[1%] whitespace-nowrap px-2 py-2 text-[11px] font-semibold uppercase tracking-wider text-muted-foreground">Service</th>
              <th className="w-full px-2 py-2 text-[11px] font-semibold uppercase tracking-wider text-muted-foreground">Message</th>
            </tr>
          </thead>
          <tbody className="divide-y divide-border/50">
            {logs.map((log) => {
              const normalizedLevel = (log.level || 'info').toLowerCase()
              const isSelected = selectedLogId === log.logId
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
                  <td className="whitespace-nowrap px-2 py-1.5">
                    <div className="flex flex-col">
                      <span className="font-mono text-xs text-foreground/80">{formatTimestamp(log.timestamp)}</span>
                      <span className="font-mono text-[10px] text-muted-foreground/60">{formatRelativeTime(log.timestamp)}</span>
                    </div>
                  </td>
                  <td className="whitespace-nowrap px-2 py-1.5">
                    <Badge
                      variant="outline"
                      className={cn('font-mono text-[10px] uppercase px-1.5 py-0', levelStyles[normalizedLevel] || levelStyles.info)}
                    >
                      {normalizedLevel}
                    </Badge>
                  </td>
                  <td className="whitespace-nowrap px-2 py-1.5">
                    {log.service ? (
                      <span className="rounded bg-muted/80 px-1.5 py-0.5 font-mono text-xs text-muted-foreground">{log.service}</span>
                    ) : (
                      <span className="text-xs text-muted-foreground/40">-</span>
                    )}
                  </td>
                  <td className="px-2 py-1.5">
                    <span className={cn(
                      'line-clamp-2 font-mono text-xs leading-snug',
                      normalizedLevel === 'error' || normalizedLevel === 'fatal'
                        ? 'text-foreground'
                        : 'text-foreground/80'
                    )}>
                      {log.message || log.body || '-'}
                    </span>
                  </td>
                </tr>
              )
            })}
          </tbody>
        </table>
      </div>
    </div>
  )
}
