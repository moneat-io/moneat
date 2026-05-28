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
import {SpanWaterfall} from '@/components/apm/SpanWaterfall'
import {Loader2, Unplug} from 'lucide-react'

interface LogTraceTabProps {
  log: LogEntry
  active: boolean
}

export function LogTraceTab({log, active}: LogTraceTabProps) {
  const {data, isLoading, error} = useQuery({
    queryKey: ['logDetailTrace', log.traceId],
    queryFn: () => api.getApmTraceDetail(log.traceId),
    enabled: !!log.traceId && active,
    staleTime: 5 * 60 * 1000,
    retry: false,
  })

  if (!log.traceId) {
    return (
      <div className="flex flex-col items-center justify-center py-12 text-muted-foreground gap-2">
        <Unplug className="h-8 w-8 opacity-40" />
        <p className="text-sm">No trace ID associated with this log</p>
      </div>
    )
  }

  if (isLoading) {
    return (
      <div className="flex items-center justify-center py-12">
        <Loader2 className="h-5 w-5 animate-spin text-muted-foreground" />
      </div>
    )
  }

  if (error || !data) {
    return (
      <div className="flex flex-col items-center justify-center py-12 text-muted-foreground gap-2">
        <Unplug className="h-8 w-8 opacity-40" />
        <p className="text-sm">Could not load trace data</p>
        <p className="text-xs opacity-60">{log.traceId}</p>
      </div>
    )
  }

  if (data.spans.length === 0) {
    return (
      <div className="flex flex-col items-center justify-center py-12 text-muted-foreground gap-2">
        <p className="text-sm">No spans found for this trace</p>
        <p className="text-xs opacity-60">{log.traceId}</p>
      </div>
    )
  }

  return <SpanWaterfall spans={data.spans} highlightSpanId={log.spanId || undefined} />
}
