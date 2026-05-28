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
import {api} from '@/lib/api'
import type {LogEntry} from '@/lib/api'
import {LogTable} from '@/components/logs/LogTable'
import {Loader2} from 'lucide-react'

const CONTEXT_WINDOW_MINUTES = 5

interface LogContextTabProps {
  log: LogEntry
  active: boolean
}

export function LogContextTab({log, active}: LogContextTabProps) {
  const logTime = new Date(log.timestamp).getTime()
  const from = new Date(logTime - CONTEXT_WINDOW_MINUTES * 60_000).toISOString()
  const to = new Date(logTime + CONTEXT_WINDOW_MINUTES * 60_000).toISOString()

  const {data, error, isError, isLoading} = useQuery({
    queryKey: ['logDetailContext', log.logId, log.service, log.timestamp],
    queryFn: () => api.getLogs({
      from,
      to,
      service: log.service || undefined,
      limit: 100,
    }),
    enabled: active,
    staleTime: 30 * 1000,
    retry: false,
  })

  if (isLoading) {
    return (
      <div className="flex items-center justify-center py-12">
        <Loader2 className="h-5 w-5 animate-spin text-muted-foreground" />
      </div>
    )
  }

  if (isError) {
    const message = error instanceof Error ? error.message : 'Could not load surrounding logs'

    return (
      <div className="flex flex-col items-center justify-center gap-2 py-12 text-muted-foreground">
        <p className="text-sm">Could not load surrounding logs</p>
        <p className="max-w-md text-center text-xs">{message}</p>
      </div>
    )
  }

  const logs = data?.logs ?? []

  if (logs.length === 0) {
    return (
      <div className="flex flex-col items-center justify-center py-12 text-muted-foreground gap-2">
        <p className="text-sm">No surrounding logs found</p>
      </div>
    )
  }

  return (
    <div className="space-y-2">
      <p className="text-xs text-muted-foreground">
        {logs.length} log{logs.length !== 1 ? 's' : ''} within {CONTEXT_WINDOW_MINUTES} minutes
        {log.service ? ` from ${log.service}` : ''}
      </p>
      <div className="max-h-[500px] overflow-y-auto rounded-lg border">
        <LogTable
          logs={logs}
          selectedLogId={log.logId}
          onSelectLog={() => {}}
          compact
          groupExceptions={false}
        />
      </div>
    </div>
  )
}
