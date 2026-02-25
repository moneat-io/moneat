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

import { createFileRoute } from '@tanstack/react-router'
import { useQuery } from '@tanstack/react-query'
import { api } from '@/lib/api'
import { Card, CardContent, CardHeader, CardTitle } from '@/components/ui/card'
import { Badge } from '@/components/ui/badge'
import { Button } from '@/components/ui/button'
import { ArrowLeft, Clock, Activity } from 'lucide-react'
import { Link } from '@tanstack/react-router'

export const Route = createFileRoute('/projects/$projectId/traces/$traceId')({
  component: TraceDetailPage,
})

function TraceDetailPage() {
  const { projectId, traceId } = Route.useParams()
  
  const { data: trace, isLoading, error } = useQuery({
    queryKey: ['trace', projectId, traceId],
    queryFn: () => api.getTraceDetails(parseInt(projectId), traceId),
  })

  if (isLoading) {
    return (
      <div className="p-6">
        <div className="text-muted-foreground">Loading trace...</div>
      </div>
    )
  }

  if (error || !trace) {
    return (
      <div className="p-6 space-y-4">
        <div className="text-destructive font-semibold">Trace not found</div>
        <p className="text-muted-foreground text-sm">
          The trace <span className="font-mono">{traceId}</span> could not be found in project {projectId}.
        </p>
        <p className="text-muted-foreground text-sm">
          This could happen if:
        </p>
        <ul className="list-disc list-inside text-muted-foreground text-sm space-y-1 ml-2">
          <li>The trace data hasn&apos;t been sent to Moneat yet (only the log was ingested)</li>
          <li>The trace has expired based on your retention policy</li>
          <li>The trace ID in the log doesn&apos;t match any actual trace data</li>
        </ul>
        <Button asChild variant="outline" size="sm" className="mt-4">
          <Link to="/logs">
            <ArrowLeft className="h-4 w-4 mr-2" />
            Back to Logs
          </Link>
        </Button>
      </div>
    )
  }

  const formatDuration = (ms: number) => {
    if (ms < 1) return `${(ms * 1000).toFixed(2)}μs`
    if (ms < 1000) return `${ms.toFixed(2)}ms`
    return `${(ms / 1000).toFixed(2)}s`
  }

  const formatTimestamp = (ts: number) => {
    return new Date(ts * 1000).toISOString()
  }

  return (
    <div className="flex-1 space-y-4 p-4 md:p-8 pt-6">
      <div className="flex items-center gap-4 mb-6">
        <Button
          variant="ghost"
          size="sm"
          asChild
        >
          <Link to="/logs">
            <ArrowLeft className="h-4 w-4 mr-2" />
            Back to Logs
          </Link>
        </Button>
      </div>

      <div className="flex items-center justify-between">
        <div>
          <h1 className="text-3xl font-bold tracking-tight">Trace Details</h1>
          <p className="text-muted-foreground font-mono text-sm mt-1">
            {trace.traceId}
          </p>
        </div>
        <div className="flex items-center gap-2">
          <Badge variant="outline" className="gap-1">
            <Clock className="h-3 w-3" />
            {formatDuration(trace.duration)}
          </Badge>
          <Badge variant="outline" className="gap-1">
            <Activity className="h-3 w-3" />
            {trace.spans.length} spans
          </Badge>
        </div>
      </div>

      <Card>
        <CardHeader>
          <CardTitle>Trace Information</CardTitle>
        </CardHeader>
        <CardContent className="space-y-4">
          <div className="grid grid-cols-1 md:grid-cols-2 gap-4">
            <div>
              <div className="text-sm font-medium text-muted-foreground">Start Time</div>
              <div className="font-mono text-sm">{formatTimestamp(trace.startTimestamp)}</div>
            </div>
            <div>
              <div className="text-sm font-medium text-muted-foreground">End Time</div>
              <div className="font-mono text-sm">{formatTimestamp(trace.endTimestamp)}</div>
            </div>
            <div>
              <div className="text-sm font-medium text-muted-foreground">Duration</div>
              <div className="font-mono text-sm">{formatDuration(trace.duration)}</div>
            </div>
            <div>
              <div className="text-sm font-medium text-muted-foreground">Total Spans</div>
              <div className="font-mono text-sm">{trace.spans.length}</div>
            </div>
          </div>
        </CardContent>
      </Card>

      <Card>
        <CardHeader>
          <CardTitle>Spans</CardTitle>
        </CardHeader>
        <CardContent>
          <div className="space-y-2">
            {trace.spans.map((span) => (
              <Link
                key={span.spanId}
                to="/projects/$projectId/spans/$spanId"
                params={{ projectId, spanId: span.spanId }}
                className="block p-3 border rounded-lg hover:bg-accent transition-colors"
              >
                <div className="flex items-start justify-between">
                  <div className="flex-1">
                    <div className="flex items-center gap-2">
                      <span className="font-medium">{span.op}</span>
                      {span.status && (
                        <Badge variant={span.status === 'ok' ? 'default' : 'destructive'} className="text-xs">
                          {span.status}
                        </Badge>
                      )}
                    </div>
                    <div className="text-sm text-muted-foreground mt-1">
                      {span.description}
                    </div>
                    <div className="font-mono text-xs text-muted-foreground mt-1">
                      {span.spanId}
                    </div>
                  </div>
                  <div className="text-right">
                    <div className="text-sm font-medium">{formatDuration(span.duration)}</div>
                    <div className="text-xs text-muted-foreground">
                      {formatTimestamp(span.startTimestamp)}
                    </div>
                  </div>
                </div>
              </Link>
            ))}
          </div>
        </CardContent>
      </Card>
    </div>
  )
}
