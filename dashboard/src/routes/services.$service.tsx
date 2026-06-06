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

import {createFileRoute, Link, Outlet, useRouterState} from '@tanstack/react-router'
import {useQuery} from '@tanstack/react-query'
import {useState} from 'react'
import {ExternalLink} from 'lucide-react'

import {Badge} from '@/components/ui/badge'
import {Button} from '@/components/ui/button'
import {SectionCard} from '@/components/ui/section-card'
import {StatusDot} from '@/components/ui/status-dot'
import {Tabs, TabsContent, TabsList, TabsTrigger} from '@/components/ui/tabs'
import {Table, TableBody, TableCell, TableHead, TableHeader, TableRow} from '@/components/ui/table'
import {
  api,
  type ApmDependencyRow as DependencyRow,
  type ApmServiceDetail as ServiceDetail,
  type ApmTimeRange,
} from '@/lib/api'
import {
  ApmKpiRow,
  ApmTimeRangeSelect,
  BarGauge,
  ErrorBars,
  ErrorList,
  HttpStatusBadge,
  InfoCallout,
  LatencyLegendTable,
  LatencyPercentilesChart,
  MeterBar,
  MethodChip,
  ServiceStatusBadge,
  ThroughputChart,
  TypeChip,
} from '@/components/apm/ServiceVisuals'
import {
  apmRangeLabel,
  formatMs,
  statusTone,
  type Severity,
} from '@/lib/apm-view'
import {cn} from '@/lib/utils'

export const Route = createFileRoute('/services/$service')({
  component: ServiceDetailPage,
})

function ServiceDetailPage() {
  const {service} = Route.useParams()
  const pathname = useRouterState({select: (state) => state.location.pathname})

  // A resource child route renders in place of the service detail.
  if (/^\/services\/[^/]+\/resources\//.test(pathname)) {
    return <Outlet />
  }

  return <ServiceDetailContent service={service} />
}

function ServiceDetailContent({service}: Readonly<{service: string}>) {
  const [timeRange, setTimeRange] = useState<ApmTimeRange>('1h')
  const detailQuery = useQuery({
    queryKey: ['apm-service-detail', service, timeRange],
    queryFn: () => api.getApmServiceDetail(service, {timeRange}),
  })
  const detail = detailQuery.data
  const rangeLabel = apmRangeLabel(timeRange)

  if (detailQuery.isLoading) {
    return (
      <div className="mx-auto max-w-3xl p-8 text-center">
        <p className="text-sm text-muted-foreground">Loading service telemetry...</p>
      </div>
    )
  }

  if (detailQuery.isError && !isNotFoundError(detailQuery.error)) {
    return (
      <div className="mx-auto max-w-3xl p-8 text-center">
        <p className="text-sm font-medium text-foreground">Service telemetry failed to load.</p>
        <p className="mt-1 text-sm text-muted-foreground">
          The service exists, but Moneat could not load the detail query for{' '}
          <span className="font-mono">{service}</span>.
        </p>
        <Button asChild variant="outline" size="sm" className="mt-3">
          <Link to="/services">Back to services</Link>
        </Button>
      </div>
    )
  }

  if (!detail) {
    return (
      <div className="mx-auto max-w-3xl p-8 text-center">
        <p className="text-sm text-muted-foreground">
          Service <span className="font-mono">{service}</span> was not found.
        </p>
        <Button asChild variant="outline" size="sm" className="mt-3">
          <Link to="/services">Back to services</Link>
        </Button>
      </div>
    )
  }

  const hasInfra = detail.pods.length > 0 || detail.podMemory.length > 0

  return (
    <div className="mx-auto max-w-[1400px] space-y-4 p-4">
      <nav className="flex items-center gap-1.5 text-sm text-muted-foreground">
        <Link to="/services" className="transition-colors hover:text-foreground">
          Services
        </Link>
        <span className="text-muted-foreground/50">/</span>
        <span className="font-medium text-foreground">{detail.name}</span>
      </nav>

      <ServiceHeader detail={detail} timeRange={timeRange} onTimeRangeChange={setTimeRange} />

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
        <SectionCard
          title="Throughput · req/s"
          actions={<Badge variant="neutral" shape="pill" size="sm">{rangeLabel}</Badge>}
        >
          <ThroughputChart points={detail.throughput} deployAt={detail.deployAt} />
          {detail.deployAt && (
            <div className="mt-2 flex items-center gap-2 text-xs text-muted-foreground">
              <span className="inline-flex items-center gap-1.5">
                <span className="h-2 w-2 rounded-[2px] bg-primary" /> deploy {detail.version}
              </span>
            </div>
          )}
        </SectionCard>
      </div>

      <div className="grid gap-3 lg:grid-cols-[3fr_2fr]">
        <SectionCard
          title="Errors over time"
          actions={
            detail.errorBars.some((bar) => bar.level === 'bad') ? (
              <Badge variant="danger" shape="pill" size="sm">elevated</Badge>
            ) : undefined
          }
        >
          <ErrorBars bars={detail.errorBars} />
          <div className="mt-2 flex flex-wrap items-center gap-3 text-xs text-muted-foreground">
            <span className="inline-flex items-center gap-1.5">
              <span className="h-2 w-2 rounded-[2px] bg-danger-solid" /> errors present
            </span>
            <span className="inline-flex items-center gap-1.5">
              <span className="h-2 w-2 rounded-[2px] bg-warning-solid" /> no errors
            </span>
            <span className="ml-auto">{rangeLabel}</span>
          </div>
        </SectionCard>
        <SectionCard title="p95 by resource · top 5">
          <BarGauge rows={detail.p95ByResource} />
        </SectionCard>
      </div>

      <Tabs defaultValue="resources" className="mt-1">
        <TabsList className="h-9 w-full justify-start gap-1 overflow-x-auto rounded-none border-b bg-transparent p-0">
          <ServiceTab value="resources" label="Resources" count={detail.resources.length} />
          <ServiceTab value="deps" label="Dependencies" count={detail.upstream.length + detail.downstream.length} />
          <ServiceTab value="deploys" label="Deployments" count={detail.deployments.length} />
          {hasInfra && <ServiceTab value="infra" label="Infrastructure" count={detail.pods.length} />}
          <ServiceTab value="errors" label="Errors" count={detail.errors.length} />
          <ServiceTab value="traces" label="Traces" />
        </TabsList>

        <TabsContent value="resources" className="pt-4">
          <ResourcesTab detail={detail} />
        </TabsContent>
        <TabsContent value="deps" className="pt-4">
          <DependenciesTab detail={detail} />
        </TabsContent>
        <TabsContent value="deploys" className="pt-4">
          <DeploymentsTab detail={detail} />
        </TabsContent>
        {hasInfra && (
          <TabsContent value="infra" className="pt-4">
            <InfrastructureTab detail={detail} />
          </TabsContent>
        )}
        <TabsContent value="errors" className="pt-4">
          <div className="overflow-hidden rounded-lg border">
            <ErrorList errors={detail.errors} showUsers />
          </div>
        </TabsContent>
        <TabsContent value="traces" className="pt-4">
          <TracesTab detail={detail} rangeLabel={rangeLabel} />
        </TabsContent>
      </Tabs>
    </div>
  )
}

function timePctSeverity(timePct: number): Severity {
  if (timePct >= 30) return 'bad'
  if (timePct >= 18) return 'warn'
  return 'good'
}

function ServiceHeader({
  detail,
  timeRange,
  onTimeRangeChange,
}: Readonly<{
  detail: ServiceDetail
  timeRange: ApmTimeRange
  onTimeRangeChange: (value: ApmTimeRange) => void
}>) {
  return (
    <header className="flex flex-col gap-3 border-b pb-4 sm:flex-row sm:items-start sm:justify-between">
      <div className="min-w-0">
        <h1 className="flex flex-wrap items-center gap-2.5 text-2xl font-semibold tracking-tight">
          <StatusDot tone={statusTone(detail.status)} pulse={detail.status === 'alerting'} size="lg" />
          {detail.name}
          <TypeChip type={detail.type} />
          <ServiceStatusBadge status={detail.status} />
        </h1>
        <div className="mt-2 flex flex-wrap items-center gap-2 text-sm text-muted-foreground">
          <span>{detail.runtime}</span>
          <span className="text-border">·</span>
          <span>
            team <strong className="font-medium text-foreground">{detail.team}</strong>
          </span>
          <span className="text-border">·</span>
          <span>
            <span className="font-mono">{detail.version}</span> deployed {detail.deployedAgo}
          </span>
          <span className="text-border">·</span>
          <span className="inline-flex items-center gap-1">
            sources
            {detail.sources.map((source) => (
              <Badge key={source} variant="neutral" shape="pill" size="sm">
                {source}
              </Badge>
            ))}
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

function ServiceTab({value, label, count}: Readonly<{value: string; label: string; count?: number}>) {
  return (
    <TabsTrigger
      value={value}
      className="shrink-0 rounded-none border-b-2 border-transparent px-3 data-[state=active]:border-primary data-[state=active]:bg-transparent"
    >
      {label}
      {count !== undefined && (
        <span className="ml-1.5 rounded-full bg-muted px-1.5 text-[11px] tabular-nums text-muted-foreground">
          {count}
        </span>
      )}
    </TabsTrigger>
  )
}

function ResourcesTab({detail}: Readonly<{detail: ServiceDetail}>) {
  return (
    <div className="overflow-hidden rounded-lg border">
      <Table>
        <TableHeader>
          <TableRow className="hover:bg-transparent">
            <TableHead className="pl-3">Resource</TableHead>
            <TableHead className="text-right">Requests/s</TableHead>
            <TableHead className="text-right">p50</TableHead>
            <TableHead className="text-right">p95</TableHead>
            <TableHead className="text-right">p99</TableHead>
            <TableHead className="text-right">Error rate</TableHead>
            <TableHead>% of time</TableHead>
            <TableHead className="pr-3">Status</TableHead>
          </TableRow>
        </TableHeader>
        <TableBody>
          {detail.resources.map((resource) => (
            <TableRow key={resource.slug} className="hover:bg-muted/50">
              <TableCell className="pl-3">
                <Link
                  to="/services/$service/resources/$resource"
                  params={{service: detail.name, resource: resource.slug}}
                  className="inline-flex items-center gap-2"
                >
                  <MethodChip method={resource.method} />
                  <span className="font-medium hover:text-primary hover:underline">{resource.name}</span>
                </Link>
              </TableCell>
              <TableCell className="text-right font-mono text-xs tabular-nums">{resource.rps.toLocaleString()}</TableCell>
              <TableCell className="text-right font-mono text-xs tabular-nums">{formatMs(resource.p50Ms)}</TableCell>
              <TableCell className="text-right font-mono text-xs tabular-nums">{formatMs(resource.p95Ms)}</TableCell>
              <TableCell className="text-right font-mono text-xs tabular-nums">{formatMs(resource.p99Ms)}</TableCell>
              <TableCell>
                <MeterBar pct={resource.errorBarPct} level={resource.errorLevel} value={resource.errorRateLabel} />
              </TableCell>
              <TableCell>
                <MeterBar
                  pct={resource.timePct}
                  level={timePctSeverity(resource.timePct)}
                  value={`${resource.timePct}%`}
                />
              </TableCell>
              <TableCell className="pr-3">
                <ServiceStatusBadge status={resource.status} />
              </TableCell>
            </TableRow>
          ))}
        </TableBody>
      </Table>
    </div>
  )
}

function DependenciesTab({detail}: Readonly<{detail: ServiceDetail}>) {
  return (
    <div className="space-y-4">
      <div className="grid gap-4 lg:grid-cols-2">
        <SectionCard title="Upstream · callers" flushBody>
          <div className="divide-y">
            {detail.upstream.map((dep) => (
              <DepRow key={dep.name} dep={dep} />
            ))}
          </div>
        </SectionCard>
        <SectionCard title="Downstream · dependencies" flushBody>
          <div className="divide-y">
            {detail.downstream.map((dep) => (
              <DepRow key={dep.name} dep={dep} />
            ))}
          </div>
        </SectionCard>
      </div>
      <InfoCallout>{detail.depInsight}</InfoCallout>
    </div>
  )
}

function DepRow({dep}: Readonly<{dep: DependencyRow}>) {
  return (
    <div className="grid grid-cols-[16px_1fr_auto] items-center gap-2.5 px-3 py-2">
      <StatusDot tone={dep.tone} />
      <div className="flex min-w-0 items-center gap-2">
        <Link
          to="/services/$service"
          params={{service: dep.name}}
          className="truncate font-medium hover:text-primary hover:underline"
        >
          {dep.name}
        </Link>
        <TypeChip type={dep.type} />
      </div>
      <div className="flex items-center gap-3 font-mono text-xs text-muted-foreground">
        <span>
          <span className="text-foreground">{dep.rps}</span>/s
        </span>
        <span>
          p95 <span className="text-foreground">{dep.p95}</span>
        </span>
        <span className={cn(dep.errWarn && 'text-warning-fg')}>
          <span className={cn(!dep.errWarn && 'text-foreground')}>{dep.err}</span> err
        </span>
      </div>
    </div>
  )
}

function DeploymentsTab({detail}: Readonly<{detail: ServiceDetail}>) {
  if (detail.deployments.length === 0) {
    return (
      <p className="rounded-lg border border-dashed py-10 text-center text-sm text-muted-foreground">
        No deployment versions were observed in this range.
      </p>
    )
  }
  const current = detail.deployments.find((deploy) => deploy.current) ?? detail.deployments[0]
  return (
    <div className="space-y-3">
      {current && (
        <div className="flex flex-wrap items-center gap-2 rounded-md border border-primary/30 bg-primary/[0.06] px-3 py-2 text-sm">
          <StatusDot tone={statusTone(detail.status)} />
          <span>
            <strong className="font-mono">{current.version}</strong> deployed {current.when} — error rate{' '}
            <strong className={cn(current.trendBad && 'text-danger-fg')}>{current.errorRate}</strong> and p95{' '}
            <strong className={cn(current.trendBad && 'text-danger-fg')}>{current.p95}</strong> since this release.
          </span>
        </div>
      )}
      <div className="overflow-hidden rounded-lg border">
        <Table>
          <TableHeader>
            <TableRow className="hover:bg-transparent">
              <TableHead className="pl-3">Version</TableHead>
              <TableHead>Deployed</TableHead>
              <TableHead>By</TableHead>
              <TableHead className="text-right">Requests/s</TableHead>
              <TableHead className="text-right">Error rate</TableHead>
              <TableHead className="text-right">p95</TableHead>
              <TableHead className="pr-3">Status</TableHead>
            </TableRow>
          </TableHeader>
          <TableBody>
            {detail.deployments.map((deploy) => (
              <TableRow key={deploy.version} className="hover:bg-muted/50">
                <TableCell className="pl-3">
                  <Badge variant={deploy.current ? 'accent' : 'neutral'} shape="pill" size="sm">
                    {deploy.version}
                  </Badge>
                  {deploy.current && <span className="ml-2 text-xs text-muted-foreground">current</span>}
                </TableCell>
                <TableCell className="text-muted-foreground">{deploy.when}</TableCell>
                <TableCell>
                  <InitialsAvatar initials={deploy.initials} />
                </TableCell>
                <TableCell className="text-right font-mono text-xs tabular-nums">{deploy.rps}</TableCell>
                <TableCell className={cn('text-right font-mono text-xs tabular-nums', deploy.trendBad && 'text-danger-fg')}>
                  {deploy.errorRate}
                </TableCell>
                <TableCell className={cn('text-right font-mono text-xs tabular-nums', deploy.trendBad && 'text-danger-fg')}>
                  {deploy.p95}
                </TableCell>
                <TableCell className="pr-3">
                  {deploy.status === 'retired' ? (
                    <Badge variant="neutral" size="sm">
                      retired
                    </Badge>
                  ) : (
                    <ServiceStatusBadge status={deploy.status} />
                  )}
                </TableCell>
              </TableRow>
            ))}
          </TableBody>
        </Table>
      </div>
    </div>
  )
}

function InfrastructureTab({detail}: Readonly<{detail: ServiceDetail}>) {
  return (
    <div className="grid gap-4 lg:grid-cols-2">
      <SectionCard title="Memory by pod">
        <BarGauge rows={detail.podMemory} />
      </SectionCard>
      <SectionCard title="Pods" count={`${detail.pods.length} running`} flushBody>
        <Table>
          <TableHeader>
            <TableRow className="hover:bg-transparent">
              <TableHead className="pl-3">Pod</TableHead>
              <TableHead>Node</TableHead>
              <TableHead className="text-right">CPU</TableHead>
              <TableHead className="text-right">Mem</TableHead>
              <TableHead className="text-right">Restarts</TableHead>
              <TableHead className="pr-3">Status</TableHead>
            </TableRow>
          </TableHeader>
          <TableBody>
            {detail.pods.map((pod) => (
              <TableRow key={pod.pod} className="hover:bg-muted/50">
                <TableCell className="pl-3 font-mono text-xs">{pod.pod}</TableCell>
                <TableCell className="font-mono text-xs text-muted-foreground">{pod.node}</TableCell>
                <TableCell className={cn('text-right font-mono text-xs tabular-nums', pod.cpu >= 85 && 'text-danger-fg')}>
                  {pod.cpu}%
                </TableCell>
                <TableCell className="text-right font-mono text-xs tabular-nums">{pod.mem}</TableCell>
                <TableCell className="text-right font-mono text-xs tabular-nums">{pod.restarts}</TableCell>
                <TableCell className="pr-3">
                  <span className="inline-flex items-center gap-1.5 text-xs">
                    <StatusDot tone={pod.tone} />
                    {pod.state}
                  </span>
                </TableCell>
              </TableRow>
            ))}
          </TableBody>
        </Table>
      </SectionCard>
    </div>
  )
}

function TracesTab({detail, rangeLabel}: Readonly<{detail: ServiceDetail; rangeLabel: string}>) {
  return (
    <div className="space-y-2">
      <div className="flex items-center gap-2">
        <Badge variant="neutral">sampled · {rangeLabel}</Badge>
        <Button asChild variant="ghost" size="sm" className="ml-auto gap-1.5">
          <Link to="/performance/traces">
            Open in trace explorer
            <ExternalLink className="h-3.5 w-3.5" />
          </Link>
        </Button>
      </div>
      <div className="overflow-hidden rounded-lg border">
        <Table>
          <TableHeader>
            <TableRow className="hover:bg-transparent">
              <TableHead className="pl-3">Time</TableHead>
              <TableHead>Trace ID</TableHead>
              <TableHead>Resource</TableHead>
              <TableHead>Status</TableHead>
              <TableHead className="text-right">Duration</TableHead>
              <TableHead className="pr-3 text-right">Spans</TableHead>
            </TableRow>
          </TableHeader>
          <TableBody>
            {detail.traces.map((trace) => (
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
                <TableCell className="text-xs">
                  {trace.method && <MethodChip method={trace.method} className="mr-1" />}
                  <span className="font-mono text-muted-foreground">{trace.resource}</span>
                </TableCell>
                <TableCell>
                  <HttpStatusBadge status={trace.httpStatus} />
                </TableCell>
                <TableCell className="text-right font-mono text-xs tabular-nums">{formatMs(trace.durationMs)}</TableCell>
                <TableCell className="pr-3 text-right font-mono text-xs tabular-nums">{trace.spans}</TableCell>
              </TableRow>
            ))}
          </TableBody>
        </Table>
      </div>
    </div>
  )
}

function InitialsAvatar({initials}: Readonly<{initials: string}>) {
  return (
    <span className="inline-grid h-[22px] w-[22px] place-items-center rounded-full bg-muted text-[10px] font-medium text-muted-foreground">
      {initials}
    </span>
  )
}
