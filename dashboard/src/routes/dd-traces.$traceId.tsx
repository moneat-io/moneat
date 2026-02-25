// Moneat - observability platform
// Copyright (C) 2026 Moneat
//
// This program is free software: you can redistribute it and/or modify
// it under the terms of the GNU Affero General Public License as published by
// the Free Software Foundation, either version 3 of the License, or
// (at your option) any later version.

import {createFileRoute, Link} from '@tanstack/react-router'
import {useQuery} from '@tanstack/react-query'
import {api} from '@/lib/api'
import {DdSpanWaterfall} from '@/components/datadog/DdSpanWaterfall'
import {Card, CardContent, CardHeader, CardTitle} from '@/components/ui/card'
import {Badge} from '@/components/ui/badge'
import {Button} from '@/components/ui/button'
import {ArrowLeft, Loader2} from 'lucide-react'

export const Route = createFileRoute('/dd-traces/$traceId')({
  component: DdTraceDetailPage,
})

function formatDuration(ns: number): string {
  if (ns < 1000) return `${ns}ns`
  if (ns < 1_000_000) return `${(ns / 1000).toFixed(1)}µs`
  if (ns < 1_000_000_000) return `${(ns / 1_000_000).toFixed(1)}ms`
  return `${(ns / 1_000_000_000).toFixed(2)}s`
}

function DdTraceDetailPage() {
  const {traceId} = Route.useParams()

  const {data, isLoading} = useQuery({
    queryKey: ['ddTrace', traceId],
    queryFn: () => api.getDdTraceDetail(traceId),
    enabled: api.isAuthenticated(),
  })

  if (isLoading) {
    return (
      <div className="p-6 flex items-center justify-center py-24">
        <Loader2 className="h-8 w-8 animate-spin text-muted-foreground" />
      </div>
    )
  }

  if (!data) {
    return (
      <div className="p-6">
        <p className="text-muted-foreground">Trace not found.</p>
        <Button variant="outline" className="mt-4" asChild>
          <Link to="/dd-traces">
            <ArrowLeft className="h-4 w-4 mr-2" />
            Back to traces
          </Link>
        </Button>
      </div>
    )
  }

  const spans = data.spans
  const rootSpan = spans.find((s) => s.parentId === '0') ?? spans[0]
  const totalDuration = rootSpan
    ? rootSpan.durationNs
    : Math.max(...spans.map((s) => s.durationNs))
  const services = [...new Set(spans.map((s) => s.service))]
  const errorCount = spans.filter((s) => s.error > 0).length

  return (
    <div className="p-6 space-y-6">
      {/* Header */}
      <div className="flex items-center gap-4">
        <Button variant="ghost" size="sm" asChild>
          <Link to="/dd-traces">
            <ArrowLeft className="h-4 w-4" />
          </Link>
        </Button>
        <div>
          <h1 className="text-xl font-bold">
            {rootSpan?.service}.{rootSpan?.name}
          </h1>
          <p className="text-sm text-muted-foreground font-mono">
            Trace {traceId}
          </p>
        </div>
      </div>

      {/* Summary cards */}
      <div className="grid grid-cols-2 md:grid-cols-4 gap-4">
        <Card>
          <CardHeader className="pb-1 pt-3">
            <CardTitle className="text-xs text-muted-foreground font-normal">
              Duration
            </CardTitle>
          </CardHeader>
          <CardContent className="pt-0">
            <span className="text-lg font-bold font-mono">
              {formatDuration(totalDuration)}
            </span>
          </CardContent>
        </Card>
        <Card>
          <CardHeader className="pb-1 pt-3">
            <CardTitle className="text-xs text-muted-foreground font-normal">
              Spans
            </CardTitle>
          </CardHeader>
          <CardContent className="pt-0">
            <span className="text-lg font-bold">{spans.length}</span>
          </CardContent>
        </Card>
        <Card>
          <CardHeader className="pb-1 pt-3">
            <CardTitle className="text-xs text-muted-foreground font-normal">
              Services
            </CardTitle>
          </CardHeader>
          <CardContent className="pt-0">
            <div className="flex flex-wrap gap-1">
              {services.map((svc) => (
                <Badge key={svc} variant="secondary" className="text-xs">
                  {svc}
                </Badge>
              ))}
            </div>
          </CardContent>
        </Card>
        <Card>
          <CardHeader className="pb-1 pt-3">
            <CardTitle className="text-xs text-muted-foreground font-normal">
              Errors
            </CardTitle>
          </CardHeader>
          <CardContent className="pt-0">
            {errorCount > 0 ? (
              <Badge variant="destructive">{errorCount}</Badge>
            ) : (
              <span className="text-lg font-bold text-green-600">0</span>
            )}
          </CardContent>
        </Card>
      </div>

      {/* Span waterfall */}
      <div>
        <h2 className="text-lg font-semibold mb-3">Span Waterfall</h2>
        <DdSpanWaterfall spans={spans} />
      </div>

      {/* Span details table */}
      {rootSpan?.meta && Object.keys(rootSpan.meta).length > 0 && (
        <Card>
          <CardHeader>
            <CardTitle className="text-base">Root Span Metadata</CardTitle>
          </CardHeader>
          <CardContent>
            <div className="grid grid-cols-2 gap-2 text-sm">
              {Object.entries(rootSpan.meta).map(([k, v]) => (
                <div key={k} className="flex gap-2">
                  <span className="text-muted-foreground font-mono text-xs">
                    {k}:
                  </span>
                  <span className="text-xs break-all">{v}</span>
                </div>
              ))}
            </div>
          </CardContent>
        </Card>
      )}
    </div>
  )
}
