// Moneat - observability platform
// Copyright (C) 2026 Moneat
//
// This program is free software: you can redistribute it and/or modify
// it under the terms of the GNU Affero General Public License as published by
// the Free Software Foundation, either version 3 of the License, or
// (at your option) any later version.

import {useMemo, useState, useCallback, memo} from 'react'
import {useQuery} from '@tanstack/react-query'
import {
  ReactFlow,
  Controls,
  Background,
  BackgroundVariant,
  Handle,
  Position,
  MarkerType,
  type Node,
  type Edge,
  type NodeProps,
} from '@xyflow/react'
import '@xyflow/react/dist/style.css'
import Dagre from '@dagrejs/dagre'
import {api, type ApmServiceMapEntry} from '@/lib/api'
import {Badge} from '@/components/ui/badge'
import {Loader2, Database, Globe, Server, Layers, X} from 'lucide-react'

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

const SERVICE_ICONS: Record<ServiceType, React.ComponentType<{className?: string}>> = {
  database: Database,
  cache: Layers,
  queue: Layers,
  web: Globe,
  service: Server,
}

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
  const known = new Set(services.map((s) => s.service))
  const external = new Set<string>()
  for (const s of services)
    for (const t of s.callsTo) if (!known.has(t)) external.add(t)

  const connected = new Set<string>()
  const connEdges = new Set<string>()
  if (selectedId) {
    connected.add(selectedId)
    for (const s of services) {
      for (const t of s.callsTo) {
        if (s.service === selectedId || t === selectedId) {
          connected.add(s.service)
          connected.add(t)
          connEdges.add(`${s.service}->${t}`)
        }
      }
    }
  }

  const g = new Dagre.graphlib.Graph().setDefaultEdgeLabel(() => ({}))
  g.setGraph({rankdir: 'LR', nodesep: 50, ranksep: 160, marginx: 40, marginy: 40})

  for (const s of services) g.setNode(s.service, {width: NODE_W, height: NODE_H})
  for (const name of external) g.setNode(name, {width: EXT_W, height: EXT_H})
  for (const s of services) for (const t of s.callsTo) g.setEdge(s.service, t)

  Dagre.layout(g)

  const dim = (id: string) => selectedId !== null && !connected.has(id)

  const nodes: Node[] = services.map((s) => {
    const pos = g.node(s.service)
    return {
      id: s.service,
      type: 'service',
      position: {x: pos.x - NODE_W / 2, y: pos.y - NODE_H / 2},
      data: {
        label: s.service,
        spanCount: s.spanCount,
        errorCount: s.errorCount,
        avgDurationNs: s.avgDurationNs,
        serviceType: inferServiceType(s.service),
        isExternal: false,
        dimmed: dim(s.service),
        selected: selectedId === s.service,
      } satisfies ServiceNodeData,
    }
  })

  for (const name of external) {
    const pos = g.node(name)
    nodes.push({
      id: name,
      type: 'service',
      position: {x: pos.x - EXT_W / 2, y: pos.y - EXT_H / 2},
      data: {
        label: name,
        spanCount: 0,
        errorCount: 0,
        avgDurationNs: 0,
        serviceType: inferServiceType(name),
        isExternal: true,
        dimmed: dim(name),
        selected: selectedId === name,
      } satisfies ServiceNodeData,
    })
  }

  const edges: Edge[] = []
  for (const s of services) {
    for (const t of s.callsTo) {
      const id = `${s.service}->${t}`
      const isDimmed = selectedId !== null && !connEdges.has(id)
      edges.push({
        id,
        source: s.service,
        target: t,
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

const handleStyle: React.CSSProperties = {
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
    <div
      className={[
        'rounded-lg border-2 bg-card text-card-foreground',
        'transition-all duration-200 cursor-pointer',
        data.selected ? 'ring-2 ring-primary scale-[1.02]' : '',
        data.dimmed ? 'opacity-25' : '',
        data.isExternal ? 'border-dashed' : '',
      ].join(' ')}
      style={{
        borderColor: hasErrors && !data.selected
          ? 'hsl(var(--destructive))'
          : data.selected
            ? 'hsl(var(--primary))'
            : color,
        minWidth: data.isExternal ? 140 : 180,
      }}
    >
      <Handle type="target" position={Position.Left} style={handleStyle} />

      <div className="px-3 py-2.5">
        <div className="flex items-center gap-2">
          <div
            className="shrink-0 w-7 h-7 rounded-md flex items-center justify-center"
            style={{backgroundColor: `${color}22`, color}}
          >
            <Icon className="w-4 h-4" />
          </div>
          <span className="font-semibold text-sm truncate flex-1">{data.label}</span>
          {hasErrors && (
            <span className="shrink-0 w-2.5 h-2.5 rounded-full bg-destructive animate-pulse" />
          )}
        </div>

        {!data.isExternal && (
          <div className="flex items-center gap-3 mt-1.5 text-[11px] text-muted-foreground font-mono">
            <span>{data.spanCount.toLocaleString()} spans</span>
            <span>{formatDuration(data.avgDurationNs)}</span>
            {hasErrors && (
              <span className="text-destructive font-semibold">
                {data.errorCount} err
              </span>
            )}
          </div>
        )}

        {data.isExternal && (
          <p className="text-[11px] text-muted-foreground mt-1">External dependency</p>
        )}
      </div>

      <Handle type="source" position={Position.Right} style={handleStyle} />
    </div>
  )
})

const nodeTypes = {service: ServiceNode}

// --- Main component ---

export function ServiceMap() {
  const [selectedId, setSelectedId] = useState<string | null>(null)

  const {data, isLoading} = useQuery({
    queryKey: ['apmServiceMap'],
    queryFn: () => api.getApmServiceMap(),
    enabled: api.isAuthenticated(),
    refetchInterval: 30000,
  })

  const services = data?.services ?? []

  const {nodes, edges} = useMemo(
    () => buildGraph(services, selectedId),
    [services, selectedId],
  )

  const onNodeClick = useCallback((_e: React.MouseEvent, node: Node) => {
    setSelectedId((prev) => (prev === node.id ? null : node.id))
  }, [])

  const onPaneClick = useCallback(() => setSelectedId(null), [])

  if (isLoading) {
    return (
      <div className="flex items-center justify-center py-12">
        <Loader2 className="h-6 w-6 animate-spin text-muted-foreground" />
      </div>
    )
  }

  if (services.length === 0) {
    return (
      <div className="text-center py-12 text-muted-foreground">
        <p className="font-medium">No services found</p>
        <p className="text-sm mt-1">
          Service map populates automatically as traces are ingested.
        </p>
      </div>
    )
  }

  const selected = selectedId
    ? services.find((s) => s.service === selectedId)
    : null

  return (
    <div className="service-map relative h-[calc(100vh-260px)] min-h-[420px] rounded-lg border bg-card/50 overflow-hidden">
      <ReactFlow
        nodes={nodes}
        edges={edges}
        nodeTypes={nodeTypes}
        onNodeClick={onNodeClick}
        onPaneClick={onPaneClick}
        nodesDraggable={false}
        nodesConnectable={false}
        fitView
        fitViewOptions={{padding: 0.25}}
        minZoom={0.2}
        maxZoom={2}
        proOptions={{hideAttribution: true}}
      >
        <Background
          variant={BackgroundVariant.Dots}
          gap={24}
          size={1}
          className="!bg-background"
        />
        <Controls showInteractive={false} />
      </ReactFlow>

      {/* Legend */}
      <div className="absolute bottom-3 left-3 flex items-center gap-3 bg-card/90 backdrop-blur-sm border rounded-md px-3 py-1.5 text-[11px] text-muted-foreground pointer-events-none">
        {Object.entries(SERVICE_COLORS).map(([type, c]) => (
          <span key={type} className="flex items-center gap-1.5">
            <span className="w-2 h-2 rounded-sm inline-block" style={{backgroundColor: c}} />
            <span className="capitalize">{type}</span>
          </span>
        ))}
        <span className="flex items-center gap-1.5 ml-1">
          <span className="w-4 border-t-2 border-dashed border-muted-foreground/40 inline-block" />
          <span>External</span>
        </span>
      </div>

      {/* Detail panel */}
      {selected && (
        <div className="absolute top-3 right-3 w-60 bg-card border rounded-lg overflow-hidden animate-in fade-in slide-in-from-right-2 duration-200">
          <div className="flex items-center justify-between px-3 py-2 border-b bg-muted/30">
            <h3 className="font-semibold text-sm truncate">{selected.service}</h3>
            <button
              onClick={() => setSelectedId(null)}
              className="text-muted-foreground hover:text-foreground transition-colors"
            >
              <X className="w-3.5 h-3.5" />
            </button>
          </div>
          <div className="px-3 py-2.5 space-y-2 text-sm">
            <div className="flex justify-between">
              <span className="text-muted-foreground">Spans</span>
              <span className="font-mono text-xs">{selected.spanCount.toLocaleString()}</span>
            </div>
            <div className="flex justify-between">
              <span className="text-muted-foreground">Avg Duration</span>
              <span className="font-mono text-xs">{formatDuration(selected.avgDurationNs)}</span>
            </div>
            {selected.errorCount > 0 && (
              <div className="flex justify-between items-center">
                <span className="text-muted-foreground">Errors</span>
                <Badge variant="destructive" className="text-[10px] h-5">
                  {selected.errorCount}
                </Badge>
              </div>
            )}
            {selected.callsTo.length > 0 && (
              <div className="pt-1 border-t">
                <span className="text-[11px] text-muted-foreground">Downstream</span>
                <div className="flex flex-wrap gap-1 mt-1">
                  {selected.callsTo.map((t) => (
                    <Badge key={t} variant="secondary" className="text-[10px] h-5">
                      {t}
                    </Badge>
                  ))}
                </div>
              </div>
            )}
          </div>
        </div>
      )}
    </div>
  )
}
