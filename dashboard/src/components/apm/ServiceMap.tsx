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
import {
  Handle,
  Position,
  MarkerType,
  type Node,
  type Edge,
  type NodeProps,
} from '@xyflow/react'
import Dagre from '@dagrejs/dagre'
import {api, type ApmServiceMapEntry} from '@/lib/api'
import {Badge} from '@/components/ui/badge'
import {Database, Globe, Server, Layers} from 'lucide-react'
import {MapCanvas} from '@/components/maps/MapCanvas'
import {MapDetailPanel} from '@/components/maps/MapDetailPanel'
import {MapLegend} from '@/components/maps/MapLegend'
import {MapNodeCard} from '@/components/maps/MapNodeCard'

type ServiceType = 'database' | 'cache' | 'queue' | 'web' | 'service'

type ServiceNodeData = {
  label: string
  spanCount: number
  errorCount: number
  avgDurationNs: number
  serviceType: ServiceType
  isExternal: boolean
  dimmed: boolean
  selected: boolean
}

const SERVICE_COLORS: Record<ServiceType, string> = {
  database: '#7B61FF',
  cache: '#FF6B6B',
  queue: '#FFA94D',
  web: '#51CF66',
  service: '#339AF0',
}

const SERVICE_ICONS: Record<ServiceType, ComponentType<{className?: string}>> = {
  database: Database,
  cache: Layers,
  queue: Layers,
  web: Globe,
  service: Server,
}

const SERVICE_LEGEND_ITEMS = Object.entries(SERVICE_COLORS).map(([type, color]) => ({
  label: type.charAt(0).toUpperCase() + type.slice(1),
  color,
}))

function formatDuration(ns: number): string {
  if (ns < 1_000_000) return `${(ns / 1000).toFixed(0)}µs`
  if (ns < 1_000_000_000) return `${(ns / 1_000_000).toFixed(1)}ms`
  return `${(ns / 1_000_000_000).toFixed(2)}s`
}

function inferServiceType(name: string): ServiceType {
  const lower = name.toLowerCase()
  if (/postgres|mysql|mongo|clickhouse|sqlite|mariadb|cockroach|\bdb\b/.test(lower)) return 'database'
  if (/redis|cache|memcached|varnish/.test(lower)) return 'cache'
  if (/kafka|rabbitmq|queue|sqs|sns|nats|pulsar/.test(lower)) return 'queue'
  if (/web|api|gateway|nginx|envoy|proxy|frontend/.test(lower)) return 'web'
  return 'service'
}

// --- Layout ---

const NODE_W = 200
const NODE_H = 80
const EXT_W = 160
const EXT_H = 56

function buildGraph(
  services: ApmServiceMapEntry[],
  selectedId: string | null,
): {nodes: Node[]; edges: Edge[]} {
  const known = new Set(services.map((service) => service.service))
  const external = new Set<string>()
  for (const service of services) {
    for (const target of service.callsTo) {
      if (!known.has(target)) external.add(target)
    }
  }

  const connected = new Set<string>()
  const connectedEdges = new Set<string>()
  if (selectedId) {
    connected.add(selectedId)
    for (const service of services) {
      for (const target of service.callsTo) {
        if (service.service === selectedId || target === selectedId) {
          connected.add(service.service)
          connected.add(target)
          connectedEdges.add(`${service.service}->${target}`)
        }
      }
    }
  }

  const graph = new Dagre.graphlib.Graph().setDefaultEdgeLabel(() => ({}))
  graph.setGraph({rankdir: 'LR', nodesep: 50, ranksep: 160, marginx: 40, marginy: 40})

  for (const service of services) graph.setNode(service.service, {width: NODE_W, height: NODE_H})
  for (const name of external) graph.setNode(name, {width: EXT_W, height: EXT_H})
  for (const service of services) {
    for (const target of service.callsTo) graph.setEdge(service.service, target)
  }

  Dagre.layout(graph)

  const shouldDim = (id: string) => selectedId !== null && !connected.has(id)

  const nodes: Node[] = services.map((service) => {
    const position = graph.node(service.service)
    return {
      id: service.service,
      type: 'service',
      position: {x: position.x - NODE_W / 2, y: position.y - NODE_H / 2},
      data: {
        label: service.service,
        spanCount: service.spanCount,
        errorCount: service.errorCount,
        avgDurationNs: service.avgDurationNs,
        serviceType: inferServiceType(service.service),
        isExternal: false,
        dimmed: shouldDim(service.service),
        selected: selectedId === service.service,
      } satisfies ServiceNodeData,
    }
  })

  for (const name of external) {
    const position = graph.node(name)
    nodes.push({
      id: name,
      type: 'service',
      position: {x: position.x - EXT_W / 2, y: position.y - EXT_H / 2},
      data: {
        label: name,
        spanCount: 0,
        errorCount: 0,
        avgDurationNs: 0,
        serviceType: inferServiceType(name),
        isExternal: true,
        dimmed: shouldDim(name),
        selected: selectedId === name,
      } satisfies ServiceNodeData,
    })
  }

  const edges: Edge[] = []
  for (const service of services) {
    for (const target of service.callsTo) {
      const id = `${service.service}->${target}`
      const isDimmed = selectedId !== null && !connectedEdges.has(id)
      edges.push({
        id,
        source: service.service,
        target,
        type: 'smoothstep',
        animated: !isDimmed && selectedId !== null,
        style: {
          stroke: isDimmed
            ? 'hsl(var(--muted-foreground) / 0.2)'
            : 'hsl(var(--muted-foreground) / 0.6)',
          strokeWidth: isDimmed ? 1 : 2,
        },
        markerEnd: {
          type: MarkerType.ArrowClosed,
          color: isDimmed
            ? 'hsl(var(--muted-foreground) / 0.2)'
            : 'hsl(var(--muted-foreground) / 0.6)',
          width: 14,
          height: 14,
        },
      })
    }
  }

  return {nodes, edges}
}

// --- Custom node ---

const handleStyle: CSSProperties = {
  width: 6,
  height: 6,
  border: 'none',
  background: 'hsl(var(--muted-foreground) / 0.4)',
}

const ServiceNode = memo(function ServiceNode({data}: NodeProps<Node<ServiceNodeData>>) {
  const color = SERVICE_COLORS[data.serviceType]
  const Icon = SERVICE_ICONS[data.serviceType]
  const hasErrors = data.errorCount > 0

  return (
    <>
      <Handle type="target" position={Position.Left} style={handleStyle} />
      <MapNodeCard
        title={data.label}
        subtitle={data.isExternal ? 'External dependency' : undefined}
        icon={<Icon className="h-4 w-4" />}
        iconContainerStyle={{backgroundColor: `${color}22`, color}}
        borderColor={getServiceBorderColor({color, hasErrors, selected: data.selected})}
        minWidth={data.isExternal ? 140 : 180}
        selected={data.selected}
        dimmed={data.dimmed}
        dashed={data.isExternal}
        statusIndicator={hasErrors ? (
          <span className="h-2.5 w-2.5 shrink-0 animate-pulse rounded-full bg-destructive" />
        ) : null}
        metrics={!data.isExternal ? (
          <>
            <span>{data.spanCount.toLocaleString()} spans</span>
            <span>{formatDuration(data.avgDurationNs)}</span>
            {hasErrors && (
              <span className="font-semibold text-destructive">
                {data.errorCount} err
              </span>
            )}
          </>
        ) : undefined}
      />
      <Handle type="source" position={Position.Right} style={handleStyle} />
    </>
  )
})

const nodeTypes = {service: ServiceNode}

// --- Main component ---

interface ServiceMapProps {
  className?: string
  searchQuery?: string
}

export function ServiceMap({className, searchQuery = ''}: ServiceMapProps = {}) {
  const [selectedId, setSelectedId] = useState<string | null>(null)

  const {data, isLoading} = useQuery({
    queryKey: ['apmServiceMap'],
    queryFn: () => api.getApmServiceMap(),
    enabled: api.isAuthenticated(),
    refetchInterval: 30000,
  })

  const services = data?.services ?? []
  const normalizedSearch = searchQuery.trim().toLowerCase()
  const visibleServices = useMemo(() => {
    if (!normalizedSearch) return services
    return services.filter((service) => {
      const calls = service.callsTo.join(' ').toLowerCase()
      return service.service.toLowerCase().includes(normalizedSearch) || calls.includes(normalizedSearch)
    })
  }, [normalizedSearch, services])

  const {nodes, edges} = useMemo(
    () => buildGraph(visibleServices, selectedId),
    [visibleServices, selectedId],
  )

  const onNodeClick = useCallback((_event: ReactMouseEvent, node: Node) => {
    setSelectedId((previousId) => (previousId === node.id ? null : node.id))
  }, [setSelectedId])

  const onPaneClick = useCallback(() => setSelectedId(null), [setSelectedId])

  const selected = selectedId
    ? visibleServices.find((service) => service.service === selectedId)
    : null
  const isFilteredEmpty = services.length > 0 && visibleServices.length === 0

  return (
    <MapCanvas
      nodes={nodes}
      edges={edges}
      nodeTypes={nodeTypes}
      onNodeClick={onNodeClick}
      onPaneClick={onPaneClick}
      isLoading={isLoading}
      isEmpty={visibleServices.length === 0}
      emptyTitle={isFilteredEmpty ? 'No services match your search' : 'No services found'}
      emptyDescription={isFilteredEmpty
        ? 'Adjust search to show more service nodes.'
        : 'Service map populates automatically as trace telemetry is ingested.'}
      className={className ?? 'service-map h-[calc(100vh-260px)] min-h-[420px]'}
      fitSignature={`services-${visibleServices.map((service) => service.service).join('|')}`}
    >
      <MapLegend
        items={[
          ...SERVICE_LEGEND_ITEMS,
          {
            label: 'External',
            color: 'hsl(var(--muted-foreground) / 0.4)',
            dashed: true,
          },
        ]}
      />

      {selected && (
        <MapDetailPanel title={selected.service} onClose={() => setSelectedId(null)}>
          <div className="flex justify-between">
            <span className="text-muted-foreground">Spans</span>
            <span className="font-mono text-xs">{selected.spanCount.toLocaleString()}</span>
          </div>
          <div className="flex justify-between">
            <span className="text-muted-foreground">Avg Duration</span>
            <span className="font-mono text-xs">{formatDuration(selected.avgDurationNs)}</span>
          </div>
          {selected.errorCount > 0 && (
            <div className="flex items-center justify-between">
              <span className="text-muted-foreground">Errors</span>
              <Badge variant="destructive" className="h-5 text-[10px]">
                {selected.errorCount}
              </Badge>
            </div>
          )}
          {selected.callsTo.length > 0 && (
            <div className="border-t pt-1">
              <span className="text-[11px] text-muted-foreground">Downstream</span>
              <div className="mt-1 flex flex-wrap gap-1">
                {selected.callsTo.map((target) => (
                  <Badge key={target} variant="secondary" className="h-5 text-[10px]">
                    {target}
                  </Badge>
                ))}
              </div>
            </div>
          )}
        </MapDetailPanel>
      )}
    </MapCanvas>
  )
}

function getServiceBorderColor({
  color,
  hasErrors,
  selected,
}: {
  color: string
  hasErrors: boolean
  selected: boolean
}): string {
  if (hasErrors && !selected) return 'hsl(var(--destructive))'
  if (selected) return 'hsl(var(--primary))'
  return color
}
