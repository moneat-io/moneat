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

import {describe, expect, it} from 'vitest'
import type {WorkflowGraphConfig, WorkflowGraphNode} from '@/lib/api'
import {
  addGraphNode,
  nextAppendedNodePosition,
  parseWorkflowPaletteDragPayload,
  removeGraphNode,
  workflowNodeHeight,
} from '../workflowGraph'

const triggerNode: WorkflowGraphNode = {
  id: 'trigger',
  type: 'trigger',
  trigger: 'alert.triggered',
  params: {},
  conditions: [],
  cases: [],
  position: {x: 20, y: 30},
}

describe('workflow graph positioning', () => {
  it('adds explicit node positions to the graph', () => {
    const graph: WorkflowGraphConfig = {nodes: [triggerNode], edges: []}
    const actionNode: WorkflowGraphNode = {
      id: 'action-2',
      type: 'action',
      action: 'email.send',
      params: {},
      conditions: [],
      cases: [],
    }

    const updatedGraph = addGraphNode(graph, actionNode, {x: 140, y: 220})

    expect(updatedGraph.nodes.find((node) => node.id === actionNode.id)?.position).toEqual({x: 140, y: 220})
  })

  it('places palette-clicked nodes below the lowest current node', () => {
    const graph: WorkflowGraphConfig = {
      nodes: [
        triggerNode,
        {
          id: 'action-2',
          type: 'action',
          action: 'email.send',
          params: {},
          conditions: [],
          cases: [],
          position: {x: 20, y: 180},
        },
      ],
      edges: [],
    }

    expect(nextAppendedNodePosition(graph)).toEqual({x: 20, y: 180 + workflowNodeHeight + 80})
  })

  it('allows selected trigger nodes to be removed from a draft graph', () => {
    const graph: WorkflowGraphConfig = {nodes: [triggerNode], edges: []}

    expect(removeGraphNode(graph, triggerNode.id)).toEqual({nodes: [], edges: []})
  })
})

describe('workflow palette drag payloads', () => {
  it('parses valid palette payloads', () => {
    const result = parseWorkflowPaletteDragPayload(JSON.stringify({node: {type: 'control'}, prefix: 'sleep'}))

    expect(result).toEqual({node: {type: 'control'}, prefix: 'sleep'})
  })

  it('rejects malformed palette payloads', () => {
    expect(parseWorkflowPaletteDragPayload('{bad json')).toBeNull()
    expect(parseWorkflowPaletteDragPayload(JSON.stringify({node: {type: 'bad'}, prefix: 'sleep'}))).toBeNull()
    expect(parseWorkflowPaletteDragPayload(JSON.stringify({node: {type: 'control'}}))).toBeNull()
  })
})
