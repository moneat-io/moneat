// Moneat - observability platform
// Copyright (C) 2026 Moneat
//
// This program is free software: you can redistribute it and/or modify
// it under the terms of the GNU Affero General Public License as published by
// the Free Software Foundation, either version 3 of the License, or
// (at your option) any later version.

import {
  useMemo,
  useState,
  useCallback,
  memo,
  type ComponentType,
  type CSSProperties,
  type MouseEvent as ReactMouseEvent,
} from 'react'
import {useQuery} from '@tanstack/react-query'
import {Link} from '@tanstack/react-router'
import {
  Handle,
  Position,
  type Node,
  type NodeProps,
} from '@xyflow/react'
import {api, type ApmServiceEdge, type ApmTimeRange} from '@/lib/api'
import type {FacetFilter} from '@/lib/filters/types'
import {Badge} from '@/components/ui/badge'
import {Button} from '@/components/ui/button'
import {Database, Globe, Server, Layers, FileText, Flame, ListTree} from 'lucide-react'
import {MapCanvas} from '@/components/maps/MapCanvas'
import {MapDetailDock} from '@/components/maps/MapDetailDock'
import {MapLegend} from '@/components/maps/MapLegend'
import {MapNodeCard} from '@/components/maps/MapNodeCard'
import {cn} from '@/lib/utils'
import {
  buildServiceMapGraph,
  formatCount,
  formatDurationNs,
  formatPercent,
  formatRatePerMin,
  timeRangeSeconds,
  type HealthTone,
  type ServiceMapEdgeModel,
  type ServiceMapNodeModel,
  type ServiceType,
} from './serviceMapModel'
import {
  layoutPositions,
  timeRangeSecondsForNode,
  toFlowEdges,
  toFlowNodes,
  DEFAULT_TIME_RANGE,
  TONE_COLOR,
  type ServiceNodeData,
} from './serviceMapFlow'

const SERVICE_CHART_VAR: Record<ServiceType, string> = {
  database: '--chart-2',
  cache: '--chart-8',
  queue: '--chart-6',
  web: '--chart-4',
  service: '--chart-1',
}

const SERVICE_ICONS: Record<ServiceType, ComponentType<{className?: string}>> = {
  database: Database,
  cache: Layers,
  queue: Layers,
  web: Globe,
  service: Server,
}

// Node health borders use the soft status-border tokens (matching the sibling
// infrastructure map), so healthy nodes stay calm and warning/error nodes draw
// the eye. Selection is conveyed by the primary ring, not the border color.
const TONE_BORDER_CLASS: Record<HealthTone, string> = {
  success: 'border-success-border hover:border-success-fg',
  warning: 'border-warning-border hover:border-warning-fg',
  danger: 'border-danger-border hover:border-danger-fg',
  neutral: 'border-border hover:border-foreground/30',
}

const PIVOT_BUTTON_CLASS = 'h-auto flex-col gap-1 px-1 py-1.5 text-[10px]'

function serviceColor(type: ServiceType): string {
  return `hsl(var(${SERVICE_CHART_VAR[type]}))`
}

function serviceTint(type: ServiceType): string {
  return `hsl(var(${SERVICE_CHART_VAR[type]}) / 0.15)`
}

// --- Custom node ---

const handleStyle: CSSProperties = {
  width: 6,
  height: 6,
  border: 'none',
  background: 'hsl(var(--muted-foreground) / 0.4)',
}

const ServiceNode = memo(function ServiceNode({data}: Readonly<NodeProps<Node<ServiceNodeData>>>) {
  const accent = serviceColor(data.serviceType)
  const Icon = SERVICE_ICONS[data.serviceType]
  const borderClassName = data.isInferred ? 'border-border' : TONE_BORDER_CLASS[data.healthTone]

  const metrics = data.isInferred ? (
    <>
      <span>{formatRatePerMin(data.spanCount, timeRangeSecondsForNode(data))} in</span>
      <span>{formatDurationNs(data.avgDurationNs)}</span>
    </>
  ) : (
    <>
      <span>{formatRatePerMin(data.spanCount, timeRangeSecondsForNode(data))}</span>
      <span>{formatDurationNs(data.avgDurationNs)}</span>
      {data.errorRate > 0 && (
        <span className={data.healthTone === 'danger' ? 'font-semibold text-danger-fg' : 'text-warning-fg'}>
          {formatPercent(data.errorRate)} err
        </span>
      )}
    </>
  )

  return (
    <>
      <Handle type="target" position={Position.Left} style={handleStyle} />
      <MapNodeCard
        title={data.label}
        subtitle={data.isInferred ? 'Inferred dependency' : undefined}
        icon={<Icon className="h-4 w-4" />}
        iconContainerStyle={{backgroundColor: serviceTint(data.serviceType), color: accent}}
        borderClassName={borderClassName}
        minWidth={data.isInferred ? 148 : 188}
        selected={data.selected}
        dimmed={data.dimmed}
        dashed={data.isInferred}
        statusIndicator={data.healthTone === 'danger' ? (
          <span
            className="h-2.5 w-2.5 shrink-0 animate-pulse rounded-full"
            style={{backgroundColor: TONE_COLOR.danger}}
          />
        ) : null}
        metrics={metrics}
      />
      <Handle type="source" position={Position.Right} style={handleStyle} />
    </>
  )
})

const nodeTypes = {service: ServiceNode}

// --- Main component ---

const EMPTY_FACET_FILTERS: FacetFilter[] = []

type ServiceMapProps = Readonly<{
  className?: string
  searchQuery?: string
  timeRange?: ApmTimeRange
  env?: string
  source?: string
  facetFilters?: FacetFilter[]
}>

export function ServiceMap({
  className,
  searchQuery = '',
  timeRange = DEFAULT_TIME_RANGE,
  env,
  source,
  facetFilters = EMPTY_FACET_FILTERS,
}: ServiceMapProps = {}) {
  const [selectedId, setSelectedId] = useState<string | null>(null)

  const {data, isError, isLoading} = useQuery({
    queryKey: ['apmServiceMap', timeRange, env ?? '', source ?? ''],
    queryFn: () => api.getApmServiceMap({timeRange, env, source}),
    enabled: api.isAuthenticated(),
    refetchInterval: 30000,
  })

  const services = useMemo(() => data?.services ?? [], [data])
  const edges = useMemo<ApmServiceEdge[]>(() => data?.edges ?? [], [data])
  const windowSeconds = timeRangeSeconds(timeRange)

  const graph = useMemo(
    () => buildServiceMapGraph({services, edges, windowSeconds, search: searchQuery, selectedId, facetFilters}),
    [services, edges, windowSeconds, searchQuery, selectedId, facetFilters],
  )

  const flowNodes = useMemo(() => {
    const positions = layoutPositions(graph.nodes, graph.edges)
    return toFlowNodes(graph.nodes, positions).map((node) => ({
      ...node,
      data: {...node.data, windowSeconds},
    }))
  }, [graph.nodes, graph.edges, windowSeconds])

  const flowEdges = useMemo(() => toFlowEdges(graph.edges), [graph.edges])

  const selectedNode = useMemo(
    () => graph.nodes.find((node) => node.id === selectedId) ?? null,
    [graph.nodes, selectedId],
  )

  const onNodeClick = useCallback((_event: ReactMouseEvent, node: Node) => {
    setSelectedId((previousId) => (previousId === node.id ? null : node.id))
  }, [])

  const onPaneClick = useCallback(() => setSelectedId(null), [])

  const isFilteredEmpty = services.length > 0 && graph.visibleServiceCount === 0

  return (
    <div className={cn('relative flex min-h-0', className ?? 'service-map h-[calc(100vh-260px)] min-h-[420px]')}>
      <MapCanvas
        nodes={flowNodes}
        edges={flowEdges}
        nodeTypes={nodeTypes}
        onNodeClick={onNodeClick}
        onPaneClick={onPaneClick}
        isLoading={isLoading}
        isEmpty={!isError && graph.nodes.length === 0}
        emptyTitle={isFilteredEmpty ? 'No services match your filters' : 'No services found'}
        emptyDescription={isFilteredEmpty
          ? 'Adjust search or facets to show more service nodes.'
          : 'Service map populates automatically as trace telemetry is ingested.'}
        fallback={isError ? (
          <div className="max-w-sm text-center">
            <p className="text-sm font-medium text-muted-foreground">Map data unavailable</p>
            <p className="mt-1 text-xs text-muted-foreground/75">
              Refresh the page or try again after the telemetry API responds.
            </p>
          </div>
        ) : undefined}
        className="min-w-0 flex-1 rounded-none border-0"
        fitSignature={`services-${timeRange}-${graph.nodes.map((node) => node.id).join('|')}`}
      >
        <MapLegend
          items={[
            {label: 'Healthy', color: TONE_COLOR.success},
            {label: 'Elevated', color: TONE_COLOR.warning},
            {label: 'Errors', color: TONE_COLOR.danger},
            {label: 'Inferred', color: 'hsl(var(--muted-foreground) / 0.4)', dashed: true},
          ]}
          trailing={
            <span className="flex items-center gap-1.5 border-l border-border/60 pl-3 font-mono">
              <span>{formatCount(graph.visibleServiceCount)} services</span>
              {graph.inferredCount > 0 && (
                <span className="text-muted-foreground/70">· {formatCount(graph.inferredCount)} inferred</span>
              )}
            </span>
          }
        />
      </MapCanvas>

      {selectedNode && (
        <ServiceDetailPanel
          node={selectedNode}
          upstream={graph.neighbors?.upstream ?? []}
          downstream={graph.neighbors?.downstream ?? []}
          windowSeconds={windowSeconds}
          timeRange={timeRange}
          env={env}
          source={source}
          onClose={() => setSelectedId(null)}
        />
      )}
    </div>
  )
}

type ServiceDetailPanelProps = Readonly<{
  node: ServiceMapNodeModel
  upstream: ServiceMapEdgeModel[]
  downstream: ServiceMapEdgeModel[]
  windowSeconds: number
  timeRange: ApmTimeRange
  env?: string
  source?: string
  onClose: () => void
}>

function ServiceDetailPanel({
  node,
  upstream,
  downstream,
  windowSeconds,
  timeRange,
  env,
  source,
  onClose,
}: ServiceDetailPanelProps) {
  const {data: latency, isLoading: latencyLoading} = useQuery({
    queryKey: ['apmServiceLatency', node.id, timeRange, env ?? '', source ?? ''],
    queryFn: () => api.getApmServiceLatency(node.id, {timeRange, env, source}),
    enabled: api.isAuthenticated() && !node.isInferred,
  })

  return (
    <MapDetailDock
      title={node.label}
      subtitle={node.isInferred ? `inferred · ${node.serviceType}` : `service · ${node.serviceType}`}
      onClose={onClose}
    >
      <div className="flex items-center justify-between gap-2">
        <span className="text-muted-foreground">{node.isInferred ? 'Type' : 'Health'}</span>
        <Badge variant={node.isInferred ? 'neutral' : healthBadgeVariant(node.healthTone)} className="h-5 text-[10px] capitalize">
          {node.isInferred ? 'Inferred dependency' : node.healthTone}
        </Badge>
      </div>
      <DetailRow label="Throughput" value={formatRatePerMin(node.spanCount, windowSeconds)} />
      <DetailRow label="Error rate" value={formatPercent(node.errorRate)} />
      <DetailRow label="Avg latency" value={formatDurationNs(node.avgDurationNs)} />
      {!node.isInferred && (
        <div className="grid grid-cols-3 gap-1.5 border-t pt-2">
          <Percentile label="p50" value={latency?.p50DurationNs} loading={latencyLoading} />
          <Percentile label="p90" value={latency?.p90DurationNs} loading={latencyLoading} />
          <Percentile label="p99" value={latency?.p99DurationNs} loading={latencyLoading} />
        </div>
      )}

      <NeighborList title="Upstream" emptyLabel="No callers" edges={upstream} endpoint="source" />
      <NeighborList title="Downstream" emptyLabel="No dependencies" edges={downstream} endpoint="target" />

      {!node.isInferred && (
        <div className="grid grid-cols-3 gap-1.5 border-t pt-2">
          <Button asChild variant="outline" size="sm" className={PIVOT_BUTTON_CLASS}>
            <Link to="/performance/traces">
              <ListTree className="h-3.5 w-3.5" />
              Traces
            </Link>
          </Button>
          <Button asChild variant="outline" size="sm" className={PIVOT_BUTTON_CLASS}>
            <Link to="/logs" search={{q: `service:${node.id}`}}>
              <FileText className="h-3.5 w-3.5" />
              Logs
            </Link>
          </Button>
          <Button asChild variant="outline" size="sm" className={PIVOT_BUTTON_CLASS}>
            <Link to="/profiles/service/$service" params={{service: node.id}}>
              <Flame className="h-3.5 w-3.5" />
              Profiles
            </Link>
          </Button>
        </div>
      )}
    </MapDetailDock>
  )
}

function DetailRow({label, value}: Readonly<{label: string; value: string}>) {
  return (
    <div className="flex justify-between gap-3">
      <span className="text-muted-foreground">{label}</span>
      <span className="truncate font-mono text-xs">{value}</span>
    </div>
  )
}

function Percentile({
  label,
  value,
  loading,
}: Readonly<{label: string; value?: number; loading: boolean}>) {
  const displayValue = percentileDisplayValue(value, loading)

  return (
    <div className="rounded-md border bg-card/40 px-1.5 py-1 text-center">
      <div className="text-[10px] uppercase tracking-wide text-muted-foreground">{label}</div>
      <div className="font-mono text-[11px]">{displayValue}</div>
    </div>
  )
}

function percentileDisplayValue(value: number | undefined, loading: boolean): string {
  if (loading) return '…'
  if (value == null) return '—'
  return formatDurationNs(value)
}

function NeighborList({
  title,
  emptyLabel,
  edges,
  endpoint,
}: Readonly<{
  title: string
  emptyLabel: string
  edges: ServiceMapEdgeModel[]
  endpoint: 'source' | 'target'
}>) {
  return (
    <div className="border-t pt-1.5">
      <div className="mb-1 flex items-center justify-between">
        <span className="text-[11px] font-medium text-muted-foreground">{title}</span>
        <span className="text-[10px] text-muted-foreground/70">{edges.length}</span>
      </div>
      {edges.length === 0 ? (
        <p className="text-[11px] text-muted-foreground/60">{emptyLabel}</p>
      ) : (
        <div className="space-y-1">
          {edges.map((edge) => (
            <div key={edge.id} className="flex items-center justify-between gap-2 text-[11px]">
              <span className="truncate font-medium">{endpoint === 'source' ? edge.source : edge.target}</span>
              <span className="shrink-0 font-mono text-[10px] text-muted-foreground">
                {formatCount(edge.callCount)} · {formatDurationNs(edge.avgDurationNs)}
                {edge.errorRate > 0 && (
                  <span className="ml-1 text-danger-fg">{formatPercent(edge.errorRate)}</span>
                )}
              </span>
            </div>
          ))}
        </div>
      )}
    </div>
  )
}

function healthBadgeVariant(tone: HealthTone): 'success' | 'warning' | 'danger' | 'neutral' {
  return tone
}
