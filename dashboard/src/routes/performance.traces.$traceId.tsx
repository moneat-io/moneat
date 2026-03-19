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
import {SpanWaterfall} from '@/components/apm/SpanWaterfall'
import {SourceBadge} from '@/components/apm/SourceBadge'
import {Badge} from '@/components/ui/badge'
import {Button} from '@/components/ui/button'
import {
  ArrowLeft,
  Loader2,
  Clock,
  Layers,
  Server,
  AlertTriangle,
  Copy,
  Check,
} from 'lucide-react'
import {useState, useCallback} from 'react'

export const Route = createFileRoute('/performance/traces/$traceId')({
  component: PerformanceTraceDetailPage,
})

function formatDuration(ns: number): string {
  if (ns < 1000) return `${ns}ns`
  if (ns < 1_000_000) return `${(ns / 1000).toFixed(1)}µs`
  if (ns < 1_000_000_000) return `${(ns / 1_000_000).toFixed(1)}ms`
  return `${(ns / 1_000_000_000).toFixed(2)}s`
}

function formatTimestamp(ns: number): string {
  if (!ns) return '—'
  const date = new Date(ns / 1_000_000)
  return date.toLocaleString(undefined, {
    year: 'numeric',
    month: 'short',
    day: 'numeric',
    hour: '2-digit',
    minute: '2-digit',
    second: '2-digit',
  })
}

function CopyableId({value}: {value: string}) {
  const [copied, setCopied] = useState(false)

  const handleCopy = useCallback(() => {
    navigator.clipboard.writeText(value).then(() => {
      setCopied(true)
      setTimeout(() => setCopied(false), 2000)
    })
  }, [value])

  return (
    <button
      onClick={handleCopy}
      className="flex items-center gap-1 text-muted-foreground hover:text-foreground transition-colors"
      title="Copy trace ID"
    >
      <span className="font-mono text-xs">{value}</span>
      {copied ? (
        <Check className="h-3 w-3 text-green-500" />
      ) : (
        <Copy className="h-3 w-3" />
      )}
    </button>
  )
}

function PerformanceTraceDetailPage() {
  const {traceId} = Route.useParams()

  const {data, isLoading} = useQuery({
    queryKey: ['apmTrace', traceId],
    queryFn: () => api.getApmTraceDetail(traceId),
    enabled: api.isAuthenticated(),
  })

  if (isLoading) {
    return (
      <div className="p-6 flex flex-col items-center justify-center py-24 gap-3">
        <Loader2 className="h-8 w-8 animate-spin text-muted-foreground" />
        <span className="text-sm text-muted-foreground">Loading trace...</span>
      </div>
    )
  }

  if (!data) {
    return (
      <div className="p-6 flex flex-col items-center justify-center py-24 gap-3">
        <div className="rounded-full bg-muted p-3">
          <AlertTriangle className="h-6 w-6 text-muted-foreground" />
        </div>
        <p className="text-muted-foreground font-medium">Trace not found</p>
        <Button variant="outline" size="sm" asChild>
          <Link to="/performance/traces">
            <ArrowLeft className="h-4 w-4 mr-2" />
            Back to traces
          </Link>
        </Button>
      </div>
    )
  }

  const spans = data.spans ?? []
  if (spans.length === 0) {
    return (
      <div className="p-6 flex flex-col items-center justify-center py-24 gap-3">
        <div className="rounded-full bg-muted p-3">
          <AlertTriangle className="h-6 w-6 text-muted-foreground" />
        </div>
        <p className="text-muted-foreground font-medium">Trace has no spans</p>
        <Button variant="outline" size="sm" asChild>
          <Link to="/performance/traces">
            <ArrowLeft className="h-4 w-4 mr-2" />
            Back to traces
          </Link>
        </Button>
      </div>
    )
  }
  const rootSpan = spans.find((s) => s.parentId === '0') ?? spans[0]
  const totalDuration = rootSpan?.durationNs ?? 0
  const services = [...new Set(spans.map((s) => s.service))]
  const errorCount = spans.filter((s) => s.error > 0).length

  return (
    <div className="p-6 space-y-5">
      {/* Header */}
      <div className="flex items-start gap-3">
        <Button variant="ghost" size="icon" className="mt-0.5 shrink-0" asChild>
          <Link to="/performance/traces">
            <ArrowLeft className="h-4 w-4" />
          </Link>
        </Button>
        <div className="min-w-0 flex-1">
          <div className="flex items-center gap-2 flex-wrap">
            <h1 className="text-xl font-bold tracking-tight">
              {rootSpan?.service}
              <span className="text-muted-foreground font-normal mx-1">.</span>
              {rootSpan?.name}
            </h1>
            <SourceBadge source={rootSpan?.source ?? 'datadog'} />
            {rootSpan?.env && (
              <Badge variant="outline" className="text-xs">
                {rootSpan.env}
              </Badge>
            )}
            {errorCount > 0 && (
              <Badge variant="destructive" className="gap-1 text-xs">
                <AlertTriangle className="h-3 w-3" />
                {errorCount} {errorCount === 1 ? 'error' : 'errors'}
              </Badge>
            )}
          </div>
          <div className="flex items-center gap-3 mt-1">
            <CopyableId value={traceId} />
            {rootSpan && (
              <span className="text-xs text-muted-foreground">
                {formatTimestamp(rootSpan.startNs)}
              </span>
            )}
          </div>
        </div>
      </div>

      {/* Summary stats */}
      <div className="grid grid-cols-2 md:grid-cols-4 gap-3">
        <StatCard
          icon={<Clock className="h-4 w-4" />}
          label="Duration"
          value={formatDuration(totalDuration)}
          mono
        />
        <StatCard
          icon={<Layers className="h-4 w-4" />}
          label="Spans"
          value={String(spans.length)}
        />
        <StatCard
          icon={<Server className="h-4 w-4" />}
          label="Services"
          value={String(services.length)}
          badges={services}
        />
        <StatCard
          icon={<AlertTriangle className="h-4 w-4" />}
          label="Errors"
          value={String(errorCount)}
          variant={errorCount > 0 ? 'error' : 'success'}
        />
      </div>

      {/* Resource */}
      {rootSpan?.resource && (
        <div className="border rounded-lg px-4 py-3">
          <span className="text-[11px] text-muted-foreground uppercase font-medium tracking-wider">
            Resource
          </span>
          <p className="font-mono text-sm mt-1 break-all">
            {rootSpan.resource}
          </p>
        </div>
      )}

      {/* Span waterfall */}
      <div>
        <h2 className="text-base font-semibold mb-3 flex items-center gap-2">
          Span Waterfall
          <span className="text-xs font-normal text-muted-foreground">
            {spans.length} spans across {services.length} services
          </span>
        </h2>
        <SpanWaterfall spans={spans} />
      </div>
    </div>
  )
}

function StatCard({
  icon,
  label,
  value,
  mono,
  variant,
  badges,
}: {
  icon: React.ReactNode
  label: string
  value: string
  mono?: boolean
  variant?: 'error' | 'success'
  badges?: string[]
}) {
  return (
    <div className="rounded-lg border bg-card px-4 py-3">
      <div className="flex items-center gap-1.5 text-muted-foreground mb-1">
        {icon}
        <span className="text-xs font-medium">{label}</span>
      </div>
      {badges ? (
        <div className="flex flex-wrap gap-1 mt-1">
          {badges.map((b) => (
            <Badge key={b} variant="secondary" className="text-xs">
              {b}
            </Badge>
          ))}
        </div>
      ) : (
        <span
          className={`text-xl font-bold tabular-nums tracking-tight ${mono ? 'font-mono' : ''} ${
            variant === 'error'
              ? 'text-red-500'
              : variant === 'success'
                ? 'text-green-600'
                : ''
          }`}
        >
          {value}
        </span>
      )}
    </div>
  )
}
