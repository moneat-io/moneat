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
import { ArrowLeft, Clock, ExternalLink } from 'lucide-react'
import { Link } from '@tanstack/react-router'

export const Route = createFileRoute('/projects/$projectId/spans/$spanId')({
  component: SpanDetailPage,
})

function SpanDetailPage() {
  const { projectId, spanId } = Route.useParams()
  
  const { data: spanDetail, isLoading, error } = useQuery({
    queryKey: ['span', projectId, spanId],
    queryFn: () => api.getSpanDetails(parseInt(projectId), spanId),
  })

  if (isLoading) {
    return (
      <div className="p-6">
        <div className="text-muted-foreground">Loading span...</div>
      </div>
    )
  }

  if (error || !spanDetail) {
    return (
      <div className="p-6 space-y-4">
        <div className="text-destructive font-semibold">Span not found</div>
        <p className="text-muted-foreground text-sm">
          The span <span className="font-mono">{spanId}</span> could not be found in project {projectId}.
        </p>
        <p className="text-muted-foreground text-sm">
          This could happen if:
        </p>
        <ul className="list-disc list-inside text-muted-foreground text-sm space-y-1 ml-2">
          <li>The span data hasn&apos;t been sent to Moneat yet (only the log was ingested)</li>
          <li>The span has expired based on your retention policy</li>
          <li>The span ID in the log doesn&apos;t match any actual span data</li>
        </ul>
        <Button asChild variant="outline" size="sm" className="mt-4">
          <Link to="/projects/$projectId/logs" params={{ projectId }}>
            <ArrowLeft className="h-4 w-4 mr-2" />
            Back to Logs
          </Link>
        </Button>
      </div>
    )
  }

  const { span, transaction } = spanDetail

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
          <Link to="/projects/$projectId/logs" params={{ projectId }}>
            <ArrowLeft className="h-4 w-4 mr-2" />
            Back to Logs
          </Link>
        </Button>
      </div>

      <div className="flex items-center justify-between">
        <div>
          <h1 className="text-3xl font-bold tracking-tight">Span Details</h1>
          <p className="text-muted-foreground font-mono text-sm mt-1">
            {span.spanId}
          </p>
        </div>
        <div className="flex items-center gap-2">
          <Badge variant={span.status === 'ok' ? 'default' : 'destructive'}>
            {span.status || 'unknown'}
          </Badge>
          <Badge variant="outline" className="gap-1">
            <Clock className="h-3 w-3" />
            {formatDuration(span.duration)}
          </Badge>
        </div>
      </div>

      <Card>
        <CardHeader>
          <CardTitle>Span Information</CardTitle>
        </CardHeader>
        <CardContent className="space-y-4">
          <div className="grid grid-cols-1 md:grid-cols-2 gap-4">
            <div>
              <div className="text-sm font-medium text-muted-foreground">Operation</div>
              <div className="font-medium">{span.op}</div>
            </div>
            <div>
              <div className="text-sm font-medium text-muted-foreground">Description</div>
              <div className="font-medium">{span.description}</div>
            </div>
            <div>
              <div className="text-sm font-medium text-muted-foreground">Start Time</div>
              <div className="font-mono text-sm">{formatTimestamp(span.startTimestamp)}</div>
            </div>
            <div>
              <div className="text-sm font-medium text-muted-foreground">End Time</div>
              <div className="font-mono text-sm">{formatTimestamp(span.endTimestamp)}</div>
            </div>
            <div>
              <div className="text-sm font-medium text-muted-foreground">Duration</div>
              <div className="font-mono text-sm">{formatDuration(span.duration)}</div>
            </div>
            {span.parentSpanId && (
              <div>
                <div className="text-sm font-medium text-muted-foreground">Parent Span ID</div>
                <div className="font-mono text-sm">
                  <Link
                    to="/projects/$projectId/spans/$spanId"
                    params={{ projectId, spanId: span.parentSpanId }}
                    className="text-primary hover:underline inline-flex items-center gap-1"
                  >
                    {span.parentSpanId}
                    <ExternalLink className="h-3 w-3" />
                  </Link>
                </div>
              </div>
            )}
            {span.traceId && (
              <div>
                <div className="text-sm font-medium text-muted-foreground">Trace ID</div>
                <div className="font-mono text-sm">
                  <Link
                    to="/projects/$projectId/traces/$traceId"
                    params={{ projectId, traceId: span.traceId }}
                    className="text-primary hover:underline inline-flex items-center gap-1"
                  >
                    {span.traceId}
                    <ExternalLink className="h-3 w-3" />
                  </Link>
                </div>
              </div>
            )}
            {span.transactionId && (
              <div>
                <div className="text-sm font-medium text-muted-foreground">Transaction ID</div>
                <div className="font-mono text-sm">{span.transactionId}</div>
              </div>
            )}
          </div>
        </CardContent>
      </Card>

      {Object.keys(span.tags).length > 0 && (
        <Card>
          <CardHeader>
            <CardTitle>Tags</CardTitle>
          </CardHeader>
          <CardContent>
            <div className="grid grid-cols-1 md:grid-cols-2 gap-4">
              {Object.entries(span.tags).map(([key, value]) => (
                <div key={key}>
                  <div className="text-sm font-medium text-muted-foreground">{key}</div>
                  <div className="font-mono text-sm">{value}</div>
                </div>
              ))}
            </div>
          </CardContent>
        </Card>
      )}

      {span.data && (
        <Card>
          <CardHeader>
            <CardTitle>Additional Data</CardTitle>
          </CardHeader>
          <CardContent>
            <pre className="bg-muted p-4 rounded-md overflow-x-auto text-sm font-mono">
              {span.data}
            </pre>
          </CardContent>
        </Card>
      )}

      {transaction && (
        <Card>
          <CardHeader>
            <CardTitle>Related Transaction</CardTitle>
          </CardHeader>
          <CardContent>
            <div className="p-3 border rounded-lg">
              <div className="flex items-start justify-between">
                <div className="flex-1">
                  <div className="flex items-center gap-2">
                    <span className="font-medium">{transaction.name}</span>
                    <Badge variant={transaction.status === 'ok' ? 'default' : 'destructive'} className="text-xs">
                      {transaction.status}
                    </Badge>
                  </div>
                  <div className="text-sm text-muted-foreground mt-1">
                    {transaction.op}
                  </div>
                  <div className="font-mono text-xs text-muted-foreground mt-1">
                    {transaction.eventId}
                  </div>
                </div>
                <div className="text-right">
                  <div className="text-sm font-medium">{formatDuration(transaction.duration)}</div>
                  <div className="text-xs text-muted-foreground">
                    {formatTimestamp(transaction.startTimestamp)}
                  </div>
                </div>
              </div>
            </div>
          </CardContent>
        </Card>
      )}
    </div>
  )
}
