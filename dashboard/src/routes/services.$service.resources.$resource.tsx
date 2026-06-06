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

import {createFileRoute, Link} from '@tanstack/react-router'
import {useQuery} from '@tanstack/react-query'
import {useState} from 'react'
import {ExternalLink} from 'lucide-react'

import {api, type ApmResourceDetail as ResourceDetail, type ApmTimeRange} from '@/lib/api'
import {Badge} from '@/components/ui/badge'
import {Button} from '@/components/ui/button'
import {SectionCard} from '@/components/ui/section-card'
import {StatusDot} from '@/components/ui/status-dot'
import {Table, TableBody, TableCell, TableHead, TableHeader, TableRow} from '@/components/ui/table'
import {
  ApmKpiRow,
  ApmTimeRangeSelect,
  BarGauge,
  ErrorList,
  HttpStatusBadge,
  InfoCallout,
  LatencyDistribution,
  LatencyLegendTable,
  LatencyPercentilesChart,
  ServiceStatusBadge,
  ThroughputChart,
  Waterfall,
} from '@/components/apm/ServiceVisuals'
import {apmRangeLabel, formatMs, statusTone} from '@/lib/apm-view'

export const Route = createFileRoute('/services/$service/resources/$resource')({
  component: ResourceDetailPage,
})

function ResourceDetailPage() {
  const {service, resource} = Route.useParams()
  const [timeRange, setTimeRange] = useState<ApmTimeRange>('1h')
  const detailQuery = useQuery({
    queryKey: ['apm-resource-detail', service, resource, timeRange],
    queryFn: () => api.getApmResourceDetail(service, resource, {timeRange}),
  })
  const detail = detailQuery.data
  const rangeLabel = apmRangeLabel(timeRange)

  if (detailQuery.isLoading) {
    return (
      <div className="mx-auto max-w-3xl p-8 text-center">
        <p className="text-sm text-muted-foreground">Loading resource telemetry...</p>
      </div>
    )
  }

  if (detailQuery.isError && !isNotFoundError(detailQuery.error)) {
    return (
      <div className="mx-auto max-w-3xl p-8 text-center">
        <p className="text-sm font-medium text-foreground">Resource telemetry failed to load.</p>
        <p className="mt-1 text-sm text-muted-foreground">
          The resource exists, but Moneat could not load the detail query.
        </p>
        <Button asChild variant="outline" size="sm" className="mt-3">
          <Link to="/services/$service" params={{service}}>
            Back to service
          </Link>
        </Button>
      </div>
    )
  }

  if (!detail) {
    return (
      <div className="mx-auto max-w-3xl p-8 text-center">
        <p className="text-sm text-muted-foreground">This resource was not found.</p>
        <Button asChild variant="outline" size="sm" className="mt-3">
          <Link to="/services/$service" params={{service}}>
            Back to service
          </Link>
        </Button>
      </div>
    )
  }

  return (
    <div className="mx-auto max-w-[1400px] space-y-4 p-4">
      <nav className="flex flex-wrap items-center gap-1.5 text-sm text-muted-foreground">
        <Link to="/services" className="transition-colors hover:text-foreground">
          Services
        </Link>
        <span className="text-muted-foreground/50">/</span>
        <Link to="/services/$service" params={{service}} className="transition-colors hover:text-foreground">
          {detail.serviceName}
        </Link>
        <span className="text-muted-foreground/50">/</span>
        <span className="font-medium text-foreground">
          {detail.method} {detail.path}
        </span>
      </nav>

      <ResourceHeader
        detail={detail}
        service={service}
        timeRange={timeRange}
        onTimeRangeChange={setTimeRange}
      />

      <ApmKpiRow kpis={detail.kpis} />

      <div className="grid gap-3 lg:grid-cols-[3fr_2fr]">
        <SectionCard
          title="Latency percentiles"
          actions={<Badge variant="neutral" shape="pill" size="sm">{rangeLabel}</Badge>}
        >
          <LatencyPercentilesChart
            series={detail.latency}
            thresholdMs={detail.latencyThresholdMs}
            thresholdLabel={detail.latencyThresholdLabel}
            deployAt={detail.deployAt}
          />
          <LatencyLegendTable series={detail.latency} />
        </SectionCard>
        <SectionCard title="Throughput & errors">
          <ThroughputChart points={detail.throughput} deployAt={detail.deployAt} withErrors />
          <div className="mt-2 flex flex-wrap items-center gap-3 text-xs text-muted-foreground">
            <span className="inline-flex items-center gap-1.5">
              <span className="h-2 w-2 rounded-[2px] bg-chart-1" /> req/s
            </span>
            <span className="inline-flex items-center gap-1.5">
              <span className="h-2 w-2 rounded-[2px] bg-danger-solid" /> errors/s
            </span>
            {detail.deployAt && <span className="ml-auto">deploy marker</span>}
          </div>
        </SectionCard>
      </div>

      <div className="grid gap-3 lg:grid-cols-[3fr_2fr]">
        <SectionCard title="Latency distribution">
          <LatencyDistribution bars={detail.distribution} markers={detail.distMarkers} axis={detail.distAxis} />
        </SectionCard>
        <SectionCard title="Where time is spent · p95 path">
          <BarGauge rows={detail.whereTimeSpent} />
          <div className="mt-3">
            <InfoCallout>{detail.whereInsight}</InfoCallout>
          </div>
        </SectionCard>
      </div>

      <SectionCard
        title="Exemplar trace · slowest in range"
        actions={
          <div className="flex items-center gap-2">
            {detail.exemplar.traceId && <HttpStatusBadge status={detail.exemplar.httpStatus} />}
            <span className="hidden font-mono text-xs text-muted-foreground sm:inline">{detail.exemplar.traceId}</span>
            <Badge variant="neutral" shape="pill" size="sm">
              {detail.exemplar.durationLabel}
            </Badge>
            {detail.exemplar.traceId && (
              <Button asChild variant="ghost" size="sm" className="gap-1.5">
                <Link to="/performance/traces/$traceId" params={{traceId: detail.exemplar.traceId}}>
                  Open full trace
                  <ExternalLink className="h-3.5 w-3.5" />
                </Link>
              </Button>
            )}
          </div>
        }
      >
        <Waterfall rows={detail.exemplar.rows} />
      </SectionCard>

      <div className="grid gap-4 lg:grid-cols-2">
        <SectionCard
          title="Slow & failed traces"
          actions={
            <Button asChild variant="ghost" size="sm" className="gap-1.5">
              <Link to="/performance/traces">
                Explore all
                <ExternalLink className="h-3.5 w-3.5" />
              </Link>
            </Button>
          }
          flushBody
        >
          <Table>
            <TableHeader>
              <TableRow className="hover:bg-transparent">
                <TableHead className="pl-3">Time</TableHead>
                <TableHead>Trace ID</TableHead>
                <TableHead>Status</TableHead>
                <TableHead className="text-right">Duration</TableHead>
                <TableHead className="pr-3">Bucket</TableHead>
              </TableRow>
            </TableHeader>
            <TableBody>
              {detail.slowTraces.map((trace) => (
                <TableRow key={trace.traceId} className="hover:bg-muted/50">
                  <TableCell className="pl-3 font-mono text-xs">{trace.time}</TableCell>
                  <TableCell>
                    <Link
                      to="/performance/traces/$traceId"
                      params={{traceId: trace.traceId}}
                      className="font-mono text-xs text-primary hover:underline"
                    >
                      {trace.traceId}
                    </Link>
                  </TableCell>
                  <TableCell>
                    <HttpStatusBadge status={trace.httpStatus} />
                  </TableCell>
                  <TableCell className="text-right font-mono text-xs tabular-nums">{formatMs(trace.durationMs)}</TableCell>
                  <TableCell className="pr-3">
                    {trace.bucket && (
                      <Badge variant="neutral" shape="pill" size="sm">
                        {trace.bucket}
                      </Badge>
                    )}
                  </TableCell>
                </TableRow>
              ))}
            </TableBody>
          </Table>
        </SectionCard>

        <SectionCard
          title="Errors on this resource"
          actions={
            detail.errors.length > 0 ? (
              <Badge variant="danger" shape="pill" size="sm">
                {detail.errors.length} active
              </Badge>
            ) : undefined
          }
          flushBody
        >
          <ErrorList errors={detail.errors} />
        </SectionCard>
      </div>
    </div>
  )
}

function ResourceHeader({
  detail,
  service,
  timeRange,
  onTimeRangeChange,
}: {
  detail: ResourceDetail
  service: string
  timeRange: ApmTimeRange
  onTimeRangeChange: (value: ApmTimeRange) => void
}) {
  return (
    <header className="flex flex-col gap-3 border-b pb-4 sm:flex-row sm:items-start sm:justify-between">
      <div className="min-w-0">
        <h1 className="flex flex-wrap items-center gap-2.5 text-2xl font-semibold tracking-tight">
          <StatusDot tone={statusTone(detail.status)} pulse={detail.status === 'alerting'} size="lg" />
          <span className="font-mono text-lg font-semibold text-foreground">{detail.method}</span>
          <span className="font-mono text-xl">{detail.path}</span>
          <ServiceStatusBadge status={detail.status} />
        </h1>
        <div className="mt-2 flex flex-wrap items-center gap-2 text-sm text-muted-foreground">
          <span>
            resource in{' '}
            <Link to="/services/$service" params={{service}} className="font-medium text-foreground hover:text-primary hover:underline">
              {detail.serviceName}
            </Link>
          </span>
          <span className="text-border">·</span>
          <span className="inline-flex h-5 items-center rounded-sm border bg-muted px-1.5 text-[10px] font-medium uppercase tracking-wide text-muted-foreground">
            {detail.kind}
          </span>
          <span className="text-border">·</span>
          <span>
            top dependency <span className="font-mono text-foreground">{detail.topDependency}</span>
          </span>
        </div>
      </div>
      <div className="flex shrink-0 flex-wrap items-center gap-2">
        <ApmTimeRangeSelect value={timeRange} onChange={onTimeRangeChange} />
      </div>
    </header>
  )
}

function isNotFoundError(error: unknown): boolean {
  if (typeof error !== 'object' || error === null || !('status' in error)) return false
  return (error as {status?: unknown}).status === 404
}
