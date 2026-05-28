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

import {useQuery} from '@tanstack/react-query'
import {useMemo, useState} from 'react'
import {api, type LogEntry} from '@/lib/api'
import {useTimezone} from '@/hooks/useTimezone'
import {formatDateTime} from '@/lib/date-format'
import {LogTable} from '@/components/logs/LogTable'
import {LogDetail} from '@/components/logs/LogDetail'
import {ContainerLogSetupGuide} from '@/components/logs/ContainerLogSetupGuide'
import {Button} from '@/components/ui/button'
import {cn} from '@/lib/utils'
import {ChevronLeft, ChevronRight, Loader2, TerminalSquare} from 'lucide-react'

interface EmbeddedLogsProps {
  systemId?: string
  /** Optional project ID for trace/span links in log detail (e.g. when embedded in issue view) */
  projectId?: string | number
  /**
   * Filter logs by time range around a specific timestamp
   * Shows logs from [centerTimestamp - contextMinutes] to [centerTimestamp + contextMinutes]
   */
  centerTimestamp?: string
  /**
   * How many minutes before and after centerTimestamp to show (default: 5)
   */
  contextMinutes?: number
  /**
   * Additional filters
   */
  query?: string
  levels?: string[]
  service?: string
  environment?: string
  containerName?: string
  tags?: Record<string, string>
  /**
   * Maximum height of the logs container (default: '400px')
   */
  maxHeight?: string
  /**
   * Show/hide the header (default: true)
   */
  showHeader?: boolean
  /**
   * Compact mode - smaller text and padding (default: false)
   */
  compact?: boolean
  className?: string
}

export function EmbeddedLogs({
  systemId,
  projectId,
  centerTimestamp,
  contextMinutes = 5,
  query,
  levels,
  service,
  environment,
  containerName,
  tags,
  maxHeight = '400px',
  showHeader = true,
  compact = false,
  className,
}: EmbeddedLogsProps) {
  const { timezone } = useTimezone()
  const [cursor, setCursor] = useState<string | null>(null)
  const [cursorHistory, setCursorHistory] = useState<Array<string | null>>([])
  const [selectedLog, setSelectedLog] = useState<LogEntry | null>(null)
  const [detailOpen, setDetailOpen] = useState(false)

  // Calculate time range based on centerTimestamp and contextMinutes
  const timeRange = useMemo(() => {
    if (!centerTimestamp) {
      return {}
    }

    const center = new Date(centerTimestamp)
    if (Number.isNaN(center.getTime())) {
      return {}
    }

    const contextMs = contextMinutes * 60 * 1000
    const from = new Date(center.getTime() - contextMs)
    const to = new Date(center.getTime() + contextMs)

    return {
      from: from.toISOString(),
      to: to.toISOString(),
    }
  }, [centerTimestamp, contextMinutes])

  // Fetch logs - org-scoped (getLogs) when no systemId; getSystemLogs when systemId (monitor)
  const {
    data: logPage,
    isLoading,
  } = useQuery({
    queryKey: [
      'embedded-logs',
      systemId,
      cursor,
      query,
      levels?.join(','),
      timeRange.from,
      timeRange.to,
      service,
      environment,
      containerName,
      JSON.stringify(tags),
    ],
    queryFn: () => {
      if (systemId) {
        return api.getSystemLogs(systemId, {
          cursor: cursor || undefined,
          limit: 100,
          query: query || undefined,
          levels,
          service,
          environment,
          containerName,
          from: timeRange.from,
          to: timeRange.to,
          tags: tags && Object.keys(tags).length > 0 ? tags : undefined,
        })
      }
      return api.getLogs({
        cursor: cursor || undefined,
        limit: 100,
        query: query || undefined,
        levels,
        service,
        environment,
        containerName,
        from: timeRange.from,
        to: timeRange.to,
        tags: tags && Object.keys(tags).length > 0 ? tags : undefined,
      })
    },
    enabled: true,
  })

  const logs = logPage?.logs ?? []
  const showContainerLogSetupGuide =
    compact && Boolean(systemId && containerName) && !centerTimestamp

  const handleSelectLog = (log: LogEntry) => {
    setSelectedLog(log)
    setDetailOpen(true)
  }

  const handleNextPage = () => {
    if (!logPage?.hasMore) return
    setCursorHistory((current) => [...current, cursor])
    setCursor(logPage.nextCursor || null)
  }

  const handlePreviousPage = () => {
    if (cursorHistory.length === 0) return
    const previous = cursorHistory[cursorHistory.length - 1] ?? null
    setCursorHistory((current) => current.slice(0, -1))
    setCursor(previous)
  }

  return (
    <div className={cn('flex min-w-0 max-w-full flex-col overflow-hidden rounded-lg border bg-card', className)}>
      {/* Header */}
      {showHeader && (
        <div className="flex items-center justify-between gap-4 border-b bg-muted/30 px-3 py-2">
          <div className="flex items-center gap-2">
            <TerminalSquare className="h-4 w-4 text-muted-foreground" />
            <span className="text-sm font-medium">
              {centerTimestamp ? (
                <>
                  Logs ±{contextMinutes}min around{' '}
                  {formatDateTime(new Date(centerTimestamp), timezone)}
                </>
              ) : (
                'Logs'
              )}
            </span>
          </div>

          <div className="flex items-center gap-2">
            <div className="text-xs text-muted-foreground">
              {isLoading ? (
                <span className="flex items-center gap-1">
                  <Loader2 className="h-3 w-3 animate-spin" />
                  Loading...
                </span>
              ) : (
                <span>
                  {logs.length} log{logs.length !== 1 ? 's' : ''}
                </span>
              )}
            </div>

            {/* Pagination */}
            <div className="flex items-center gap-0.5">
              <Button
                variant="ghost"
                size="sm"
                onClick={handlePreviousPage}
                disabled={cursorHistory.length === 0}
                className="h-6 w-6 p-0"
              >
                <ChevronLeft className="h-3.5 w-3.5" />
              </Button>
              <Button
                variant="ghost"
                size="sm"
                onClick={handleNextPage}
                disabled={!logPage?.hasMore}
                className="h-6 w-6 p-0"
              >
                <ChevronRight className="h-3.5 w-3.5" />
              </Button>
            </div>
          </div>
        </div>
      )}

      {/* Log content */}
      <div
        className="min-w-0 overflow-auto"
        style={{maxHeight}}
      >
        {isLoading ? (
          <div className="flex items-center justify-center py-12">
            <div className="text-center">
              <Loader2 className="mx-auto h-6 w-6 animate-spin text-muted-foreground" />
              <p className="mt-2 text-xs text-muted-foreground">Loading logs...</p>
            </div>
          </div>
        ) : logs.length === 0 ? (
          <div
            className={cn('py-12', showContainerLogSetupGuide ? 'px-4' : 'flex items-center justify-center')}
          >
            {showContainerLogSetupGuide ? (
              <ContainerLogSetupGuide compact />
            ) : (
              <div className="text-center">
                <TerminalSquare className="mx-auto h-8 w-8 text-muted-foreground/30" />
                <p className="mt-2 text-sm font-medium text-muted-foreground">No logs found</p>
                {centerTimestamp && (
                  <p className="mt-1 text-xs text-muted-foreground/70">
                    No logs in the ±{contextMinutes} minute window
                  </p>
                )}
              </div>
            )}
          </div>
        ) : (
          <LogTable
            logs={logs}
            selectedLogId={selectedLog?.logId}
            onSelectLog={handleSelectLog}
          />
        )}
      </div>

      {/* Log detail sheet */}
      <LogDetail log={selectedLog} open={detailOpen} onClose={() => setDetailOpen(false)} projectId={projectId} />
    </div>
  )
}
