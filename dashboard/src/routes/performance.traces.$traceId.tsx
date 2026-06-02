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
import {StatCard} from '@/components/ui/stat-card'
import {SectionCard} from '@/components/ui/section-card'
import {EmptyState} from '@/components/ui/empty-state'
import {
  ArrowLeft,
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
        <Check className="h-3 w-3 text-success-fg" />
      ) : (
        <Copy className="h-3 w-3" />
      )}
    </button>
  )
}

function TraceNotFound({title}: {title: string}) {
  return (
    <div className="p-6">
      <EmptyState
        icon={AlertTriangle}
        title={title}
        description="The trace may have expired out of the retention window, or the ID may be incorrect."
        action={
          <Button asChild variant="secondary" size="sm">
            <Link to="/performance/traces">
              <ArrowLeft className="h-4 w-4" />
              Back to traces
            </Link>
          </Button>
        }
      />
    </div>
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
      <div className="p-6 space-y-5">
        <div className="h-4 w-40 animate-pulse rounded bg-muted" />
        <div className="h-7 w-72 animate-pulse rounded bg-muted" />
        <div className="grid grid-cols-2 gap-3 md:grid-cols-4">
          {[0, 1, 2, 3].map((i) => (
            <div key={i} className="h-[88px] animate-pulse rounded-lg border bg-card" />
          ))}
        </div>
        <div className="h-96 animate-pulse rounded-lg border bg-card" />
      </div>
    )
  }

  if (!data) return <TraceNotFound title="Trace not found" />

  const spans = data.spans ?? []
  if (spans.length === 0) return <TraceNotFound title="Trace has no spans" />

  const rootSpan = spans.find((s) => s.parentId === '0') ?? spans[0]
  const totalDuration = rootSpan?.durationNs ?? 0
  const services = [...new Set(spans.map((s) => s.service))]
  const errorCount = spans.filter((s) => s.error > 0).length
  const serviceSummary =
    services.slice(0, 3).join(', ') + (services.length > 3 ? ` +${services.length - 3}` : '')

  return (
    <div className="p-6 space-y-5">
      {/* Breadcrumb + header */}
      <div className="flex flex-col gap-3">
        <nav className="flex items-center gap-2 text-sm">
          <Link
            to="/performance/traces"
            className="inline-flex items-center gap-1 text-muted-foreground transition-colors hover:text-foreground"
          >
            <ArrowLeft className="h-3.5 w-3.5" />
            Traces
          </Link>
          <span className="text-muted-foreground/50">/</span>
          <span className="truncate font-mono text-xs text-foreground" title={traceId}>
            {traceId}
          </span>
        </nav>
        <div className="min-w-0">
          <div className="flex flex-wrap items-center gap-2">
            <h1 className="text-xl font-semibold tracking-tight">
              {rootSpan?.service}
              <span className="mx-1 font-normal text-muted-foreground">·</span>
              {rootSpan?.name}
            </h1>
            <SourceBadge source={rootSpan?.source ?? ''} />
            {rootSpan?.env && <Badge variant="neutral">{rootSpan.env}</Badge>}
            {errorCount > 0 && (
              <Badge variant="danger" className="gap-1">
                <AlertTriangle className="h-3 w-3" />
                {errorCount} {errorCount === 1 ? 'error' : 'errors'}
              </Badge>
            )}
          </div>
          <div className="mt-1.5 flex items-center gap-3">
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
      <div className="grid grid-cols-2 gap-3 md:grid-cols-4">
        <StatCard label="Duration" value={formatDuration(totalDuration)} icon={Clock} tone="accent" />
        <StatCard label="Spans" value={spans.length} icon={Layers} tone="info" />
        <StatCard
          label="Services"
          value={services.length}
          icon={Server}
          tone="neutral"
          subtitle={serviceSummary}
        />
        <StatCard
          label="Errors"
          value={
            <span className={errorCount > 0 ? 'text-danger-fg' : 'text-success-fg'}>{errorCount}</span>
          }
          icon={AlertTriangle}
          tone={errorCount > 0 ? 'danger' : 'success'}
        />
      </div>

      {/* Resource */}
      {rootSpan?.resource && (
        <div className="rounded-lg border bg-card px-4 py-3">
          <span className="text-xs font-medium uppercase tracking-wide text-muted-foreground">
            Resource
          </span>
          <p className="mt-1 break-all font-mono text-sm">{rootSpan.resource}</p>
        </div>
      )}

      {/* Span waterfall */}
      <SectionCard
        title="Span waterfall"
        icon={Layers}
        iconTone="accent"
        count={spans.length}
        actions={
          <span className="text-xs text-muted-foreground">
            {services.length} {services.length === 1 ? 'service' : 'services'}
          </span>
        }
      >
        <SpanWaterfall spans={spans} />
      </SectionCard>
    </div>
  )
}
