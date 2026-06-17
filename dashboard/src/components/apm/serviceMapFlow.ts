// Moneat - observability platform
// Copyright (C) 2026 Moneat
//
// This program is free software: you can redistribute it and/or modify
// it under the terms of the GNU Affero General Public License as published by
// the Free Software Foundation, either version 3 of the License, or
// (at your option) any later version.

import Dagre from '@dagrejs/dagre'
import {MarkerType, type Edge, type Node} from '@xyflow/react'
import type {ApmTimeRange} from '@/lib/api/types/apm'
import {
  timeRangeSeconds,
  type HealthTone,
  type ServiceMapEdgeModel,
  type ServiceMapNodeModel,
} from './serviceMapModel'

// React Flow node payload: the render-ready model plus the active window seconds
// (so the node renderer stays a pure function of its props).
export type ServiceNodeData = ServiceMapNodeModel & {[key: string]: unknown}

export const DEFAULT_TIME_RANGE: ApmTimeRange = '24h'

// Health → color. Borders and edges share one status language with the rest of
// the app; neutral is a muted foreground so no-traffic edges recede.
export const TONE_COLOR: Record<HealthTone, string> = {
  success: 'hsl(var(--success-solid))',
  warning: 'hsl(var(--warning-solid))',
  danger: 'hsl(var(--danger-solid))',
  neutral: 'hsl(var(--muted-foreground) / 0.55)',
}

const NODE_W = 208
const NODE_H = 86
const EXT_W = 168
const EXT_H = 60

export function layoutPositions(
  nodes: ServiceMapNodeModel[],
  edges: ServiceMapEdgeModel[],
): Map<string, {x: number; y: number}> {
  const graph = new Dagre.graphlib.Graph().setDefaultEdgeLabel(() => ({}))
  graph.setGraph({rankdir: 'LR', nodesep: 48, ranksep: 168, marginx: 40, marginy: 40})

  for (const node of nodes) {
    graph.setNode(node.id, {
      width: node.isInferred ? EXT_W : NODE_W,
      height: node.isInferred ? EXT_H : NODE_H,
    })
  }
  for (const edge of edges) {
    if (graph.hasNode(edge.source) && graph.hasNode(edge.target)) {
      graph.setEdge(edge.source, edge.target)
    }
  }

  Dagre.layout(graph)
  const positions = new Map<string, {x: number; y: number}>()
  for (const node of nodes) {
    const laid = graph.node(node.id)
    const width = node.isInferred ? EXT_W : NODE_W
    const height = node.isInferred ? EXT_H : NODE_H
    positions.set(node.id, {x: laid.x - width / 2, y: laid.y - height / 2})
  }
  return positions
}

export function toFlowNodes(
  nodes: ServiceMapNodeModel[],
  positions: Map<string, {x: number; y: number}>,
): Node[] {
  return nodes.map((node) => ({
    id: node.id,
    type: 'service',
    position: positions.get(node.id) ?? {x: 0, y: 0},
    data: {...node} satisfies ServiceNodeData,
  }))
}

// Edge color follows the mockup's flow language rather than node health: a
// connected edge lights up cyan to trace the selected service's flow, error
// edges stay red so problems read at a glance, and the rest recede to a hairline.
function edgeColor(edge: ServiceMapEdgeModel): string {
  if (edge.tone === 'danger') return 'hsl(var(--danger-solid))'
  if (edge.connected) return 'hsl(var(--primary))'
  return 'hsl(var(--muted-foreground) / 0.55)'
}

export function toFlowEdges(edges: ServiceMapEdgeModel[]): Edge[] {
  return edges.map((edge) => {
    const color = edgeColor(edge)
    const opacity = edgeOpacity(edge)
    return {
      id: edge.id,
      source: edge.source,
      target: edge.target,
      type: 'default',
      animated: edge.animated,
      label: edge.label,
      labelShowBg: true,
      labelBgPadding: [6, 3] as [number, number],
      labelBgBorderRadius: 4,
      labelStyle: {
        fill: 'hsl(var(--foreground))',
        fontSize: 10,
        fontWeight: 600,
      },
      labelBgStyle: {
        fill: 'hsl(var(--popover))',
        stroke: 'hsl(var(--border))',
        strokeWidth: 0.5,
      },
      style: {stroke: color, strokeWidth: edge.widthPx, opacity},
      markerEnd: {
        type: MarkerType.ArrowClosed,
        color,
        width: 13,
        height: 13,
      },
    }
  })
}

function edgeOpacity(edge: ServiceMapEdgeModel): number {
  if (edge.dimmed) return 0.12
  if (edge.connected) return 1
  return 0.6
}

// Throughput is computed from the active window; the window seconds ride along on
// node data so the node renderer stays a pure function of its props.
export function timeRangeSecondsForNode(data: ServiceNodeData): number {
  return (data.windowSeconds as number) ?? timeRangeSeconds(DEFAULT_TIME_RANGE)
}
