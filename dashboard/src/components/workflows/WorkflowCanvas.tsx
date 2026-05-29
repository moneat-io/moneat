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

import {useMemo} from 'react'
import {Background, Controls, Handle, Position, ReactFlow, type Connection, type Edge, type Node} from '@xyflow/react'
import '@xyflow/react/dist/style.css'
import dagre from '@dagrejs/dagre'
import {GitBranch, Mail, MessageSquare, Timer, Workflow} from 'lucide-react'
import type {WorkflowCatalogResponse, WorkflowGraphConfig, WorkflowGraphEdge, WorkflowGraphNode} from '@/lib/api'
import {Badge} from '@/components/ui/badge'
import {cn} from '@/lib/utils'
import {nodeLabel} from './workflowGraph'

const nodeWidth = 220
const nodeHeight = 78

interface WorkflowCanvasProps {
  graph: WorkflowGraphConfig
  catalog?: WorkflowCatalogResponse
  selectedNodeId: string | null
  onSelectNode: (nodeId: string | null) => void
  onGraphChange: (graph: WorkflowGraphConfig) => void
}

interface CanvasNodeData extends Record<string, unknown> {
  graphNode: WorkflowGraphNode
  catalog?: WorkflowCatalogResponse
  selected: boolean
}

export function WorkflowCanvas({
  graph,
  catalog,
  selectedNodeId,
  onSelectNode,
  onGraphChange,
}: WorkflowCanvasProps) {
  const flow = useMemo(() => graphToFlow(graph, catalog, selectedNodeId), [catalog, graph, selectedNodeId])

  const handleConnect = (connection: Connection) => {
    if (!connection.source || !connection.target) return
    onGraphChange({
      ...graph,
      edges: [
        ...graph.edges,
        {
          from: connection.source,
          to: connection.target,
          branch: connection.sourceHandle ?? undefined,
        },
      ],
    })
  }

  return (
    <div className="h-[560px] min-h-[420px] overflow-hidden rounded-md border bg-background">
      <ReactFlow
        nodes={flow.nodes}
        edges={flow.edges}
        nodeTypes={nodeTypes}
        fitView
        nodesDraggable
        onConnect={handleConnect}
        onNodeClick={(_, node) => onSelectNode(node.id)}
        onPaneClick={() => onSelectNode(null)}
      >
        <Background gap={20} />
        <Controls />
      </ReactFlow>
    </div>
  )
}

function WorkflowCanvasNode({data}: {data: CanvasNodeData}) {
  const node = data.graphNode
  const label = nodeLabel(node, data.catalog)
  const subtitle = nodeSubtitle(node)
  return (
    <div
      className={cn(
        'relative w-[220px] rounded-md border bg-card px-3 py-2 text-card-foreground shadow-sm',
        data.selected && 'border-primary ring-2 ring-primary/20'
      )}
    >
      <Handle className="!h-2 !w-2 !border-border !bg-background" type="target" position={Position.Top} />
      <div className="flex items-start gap-2">
        <div className="mt-0.5 flex h-8 w-8 shrink-0 items-center justify-center rounded-md border bg-muted/40">
          <NodeIcon node={node} />
        </div>
        <div className="min-w-0 flex-1">
          <div className="flex min-w-0 items-center gap-2">
            <p className="truncate text-sm font-semibold">{label}</p>
            {node.type === 'trigger' && <Badge className="shrink-0 text-[10px]">Start</Badge>}
          </div>
          <p className="mt-1 line-clamp-2 text-xs text-muted-foreground">{subtitle}</p>
        </div>
      </div>
      <Handle className="!h-2 !w-2 !border-border !bg-background" type="source" position={Position.Bottom} />
    </div>
  )
}

function NodeIcon({node}: {node: WorkflowGraphNode}) {
  const className = 'h-4 w-4 text-muted-foreground'
  if (node.type === 'trigger') return <Workflow className={className} />
  if (node.type === 'condition') return <GitBranch className={className} />
  if (node.type === 'control') return <Timer className={className} />
  if (node.action?.includes('email')) return <Mail className={className} />
  return <MessageSquare className={className} />
}

function nodeSubtitle(node: WorkflowGraphNode): string {
  if (node.type === 'condition') {
    const count = node.kind === 'switch' ? node.cases?.length ?? 0 : node.conditions?.length ?? 0
    return count === 0 ? 'No condition configured' : `${count} condition${count === 1 ? '' : 's'}`
  }
  if (node.type === 'control') {
    return String(node.params?.duration ?? node.params?.timeout ?? node.kind ?? 'Control flow')
  }
  if (node.type === 'action') {
    const values = Object.values(node.params ?? {}).map((value) => String(value)).filter(Boolean)
    return values[0] ?? 'Configure action parameters'
  }
  return node.trigger ?? 'Telemetry trigger'
}

function graphToFlow(
  graph: WorkflowGraphConfig,
  catalog: WorkflowCatalogResponse | undefined,
  selectedNodeId: string | null
): {nodes: Node<CanvasNodeData>[]; edges: Edge[]} {
  const layout = layoutGraph(graph)
  const nodes = graph.nodes.map((node) => ({
    id: node.id,
    type: 'workflowNode',
    position: layout.get(node.id) ?? {x: 0, y: 0},
    data: {graphNode: node, catalog, selected: node.id === selectedNodeId},
  }))
  const edges = graph.edges.map((edge, index) => ({
    id: edgeId(edge, index),
    source: edge.from,
    target: edge.to,
    label: edge.on === 'error' ? 'error' : edge.branch,
    animated: edge.on === 'error',
    className: edge.on === 'error' ? 'stroke-destructive' : undefined,
  }))
  return {nodes, edges}
}

function layoutGraph(graph: WorkflowGraphConfig): Map<string, {x: number; y: number}> {
  const dagreGraph = new dagre.graphlib.Graph()
  dagreGraph.setDefaultEdgeLabel(() => ({}))
  dagreGraph.setGraph({rankdir: 'TB', ranksep: 80, nodesep: 80})
  graph.nodes.forEach((node) => dagreGraph.setNode(node.id, {width: nodeWidth, height: nodeHeight}))
  graph.edges.forEach((edge) => dagreGraph.setEdge(edge.from, edge.to))
  dagre.layout(dagreGraph)
  return new Map(
    graph.nodes.map((node) => {
      const position = dagreGraph.node(node.id)
      return [node.id, {x: position.x - nodeWidth / 2, y: position.y - nodeHeight / 2}]
    })
  )
}

function edgeId(
  edge: WorkflowGraphEdge,
  index: number
): string {
  return `${edge.from}-${edge.to}-${edge.branch ?? edge.on ?? index}`
}

const nodeTypes = {
  workflowNode: WorkflowCanvasNode,
}
