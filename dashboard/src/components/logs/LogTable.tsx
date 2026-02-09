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
  debug: 'bg-cyan-500/15 text-cyan-700 dark:text-cyan-300 border-cyan-500/25',
  info: 'bg-blue-500/15 text-blue-700 dark:text-blue-300 border-blue-500/25',
  warn: 'bg-amber-500/15 text-amber-700 dark:text-amber-300 border-amber-500/25',
  error: 'bg-red-500/15 text-red-700 dark:text-red-300 border-red-500/25',
  fatal: 'bg-rose-500/20 text-rose-700 dark:text-rose-300 border-rose-500/30',
}

function formatTimestamp(value: string): string {
  const date = new Date(value)
  if (Number.isNaN(date.getTime())) return value
  return date.toLocaleString()
}

export function LogTable({ logs, selectedLogId, onSelectLog, emptyMessage = 'No logs found' }: LogTableProps) {
  if (logs.length === 0) {
    return (
      <div className="rounded-xl border border-dashed bg-card/60 p-12 text-center text-sm text-muted-foreground">
        {emptyMessage}
      </div>
    )
  }

  return (
    <div className="rounded-xl border bg-card">
      <div className="max-h-[540px] overflow-auto">
        <table className="w-full min-w-[920px] text-sm">
          <thead className="sticky top-0 z-10 bg-card/95 backdrop-blur">
            <tr className="border-b text-left text-xs uppercase tracking-wide text-muted-foreground">
              <th className="px-3 py-2 font-medium">Time</th>
              <th className="px-3 py-2 font-medium">Level</th>
              <th className="px-3 py-2 font-medium">Service</th>
              <th className="px-3 py-2 font-medium">Environment</th>
              <th className="px-3 py-2 font-medium">Message</th>
            </tr>
          </thead>
          <tbody>
            {logs.map((log) => {
              const normalizedLevel = (log.level || 'info').toLowerCase()
              return (
                <tr
                  key={log.logId}
                  className={cn(
                    'cursor-pointer border-b transition-colors hover:bg-accent/50',
                    selectedLogId === log.logId && 'bg-primary/10 hover:bg-primary/15'
                  )}
                  onClick={() => onSelectLog(log)}
                >
                  <td className="px-3 py-2 whitespace-nowrap text-xs text-muted-foreground">{formatTimestamp(log.timestamp)}</td>
                  <td className="px-3 py-2 whitespace-nowrap">
                    <Badge
                      variant="outline"
                      className={cn('font-mono text-[11px] uppercase', levelStyles[normalizedLevel] || levelStyles.info)}
                    >
                      {normalizedLevel}
                    </Badge>
                  </td>
                  <td className="px-3 py-2 whitespace-nowrap text-muted-foreground">{log.service || '-'}</td>
                  <td className="px-3 py-2 whitespace-nowrap text-muted-foreground">{log.environment || '-'}</td>
                  <td className="px-3 py-2 font-mono text-xs">
                    <span className="line-clamp-1">{log.message || log.body || '-'}</span>
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
