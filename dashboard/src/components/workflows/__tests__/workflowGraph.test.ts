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
import type {
  WorkflowCatalogResponse,
  WorkflowConditionConfig,
  WorkflowGraphConfig,
  WorkflowGraphNode,
  WorkflowResponse,
  WorkflowStepDefinition,
} from '@/lib/api'
import {
  addGraphNode,
  defaultParamsForStep,
  draftFromWorkflow,
  draftToRequest,
  emptyWorkflowDraft,
  graphFromLegacy,
  nextAppendedNodePosition,
  nextNodeId,
  nodeLabel,
  normalizeGraph,
  parseWorkflowPaletteDragPayload,
  removeGraphNode,
  resolveWorkflowNodePositions,
  splitReferences,
  statusClass,
  stepDefinition,
  stepsFromGraph,
  updateGraphNode,
  updateGraphNodePosition,
  validateDraft,
  workflowNodeHeight,
} from '../workflowGraph'

const alertPriorityCondition: WorkflowConditionConfig = {
  reference: 'alert.priority',
  operation: 'is_equal',
  value: 'P1',
}

const triggerNode: WorkflowGraphNode = {
  id: 'trigger',
  type: 'trigger',
  trigger: 'alert.triggered',
  params: {},
  conditions: [],
  cases: [],
  position: {x: 20, y: 30},
}

const emailStep: WorkflowStepDefinition = {
  name: 'email.send',
  label: 'Email organization members',
  description: 'Send an email.',
  params: [
    {name: 'subject', label: 'Subject', type: 'String', required: true},
    {name: 'body', label: 'Body', type: 'Text', required: true},
    {name: 'retries', label: 'Retries', type: 'Number', required: false},
    {name: 'enabled', label: 'Enabled', type: 'Boolean', required: false},
  ],
}

const catalog: WorkflowCatalogResponse = {
  resources: [],
  triggers: [{
    name: 'alert.triggered',
    label: 'When an alert triggers',
    description: 'Alert trigger.',
    scope: [],
    default_once_for_template: ['alert.deduplication_key'],
  }],
  steps: [emailStep],
  node_types: [],
}

function workflowResponse(overrides: Partial<WorkflowResponse> = {}): WorkflowResponse {
  return {
    id: 1,
    name: 'Workflow',
    trigger_name: 'alert.triggered',
    enabled: true,
    version: 1,
    published: false,
    conditions: [],
    steps: [],
    graph: {nodes: [], edges: []},
    once_for_template: ['alert.deduplication_key'],
    created_at: '2026-01-01T00:00:00Z',
    updated_at: '2026-01-01T00:00:00Z',
    run_count: 0,
    ...overrides,
  }
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

  it('adds default node fields when no explicit position is supplied', () => {
    const graph: WorkflowGraphConfig = {nodes: [triggerNode], edges: []}

    const updatedGraph = addGraphNode(graph, {
      id: 'action-2',
      type: 'action',
      action: 'email.send',
    })

    expect(updatedGraph.nodes[1]).toMatchObject({
      params: {},
      conditions: [],
      cases: [],
      continue_on_error: false,
    })
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

  it('falls back to auto-layout when a saved position is invalid', () => {
    const graph: WorkflowGraphConfig = {
      nodes: [
        {...triggerNode, position: {x: Number.POSITIVE_INFINITY, y: 0}},
        {
          id: 'action-2',
          type: 'action',
          action: 'email.send',
          params: {},
          conditions: [],
          cases: [],
          position: {x: 400, y: 500},
        },
      ],
      edges: [{from: triggerNode.id, to: 'action-2'}],
    }

    const positions = resolveWorkflowNodePositions(graph)

    expect(positions.get('action-2')).toEqual({x: 400, y: 500})
    expect(positions.get(triggerNode.id)?.x).not.toBe(Number.POSITIVE_INFINITY)
  })

  it('updates saved node positions', () => {
    const graph: WorkflowGraphConfig = {nodes: [triggerNode], edges: []}

    expect(updateGraphNodePosition(graph, triggerNode.id, {x: 10, y: 15}).nodes[0].position).toEqual({x: 10, y: 15})
  })

  it('allows selected trigger nodes to be removed from a draft graph', () => {
    const graph: WorkflowGraphConfig = {nodes: [triggerNode], edges: []}

    expect(removeGraphNode(graph, triggerNode.id)).toEqual({nodes: [], edges: []})
  })

  it('removes edges attached to removed nodes', () => {
    const graph: WorkflowGraphConfig = {
      nodes: [triggerNode, {id: 'action-2', type: 'action', action: 'email.send'}],
      edges: [{from: triggerNode.id, to: 'action-2'}],
    }

    expect(removeGraphNode(graph, 'action-2')).toEqual({nodes: [triggerNode], edges: []})
  })
})

describe('workflow graph conversion', () => {
  it('creates legacy graph edges with condition and action nodes', () => {
    const graph = graphFromLegacy(
      'alert.triggered',
      [alertPriorityCondition],
      [
        {name: 'email.send', params: {subject: 'One'}},
        {name: 'slack.send', params: {channel: 'alerts'}},
      ]
    )

    expect(graph.nodes.map((node) => node.id)).toEqual(['trigger', 'conditions', 'step-1', 'step-2'])
    expect(graph.edges).toEqual([
      {from: 'trigger', to: 'conditions'},
      {from: 'conditions', to: 'step-1', branch: 'true'},
      {from: 'step-1', to: 'step-2'},
    ])
  })

  it('normalizes saved graph nodes with defaults', () => {
    const response = workflowResponse({
      graph: {
        nodes: [{id: 'action-1', type: 'action', action: 'email.send'}],
        edges: [],
      },
    })

    expect(normalizeGraph(response).nodes[0]).toMatchObject({
      params: {},
      conditions: [],
      cases: [],
      continue_on_error: false,
    })
  })

  it('falls back to legacy fields when no graph is stored', () => {
    const response = workflowResponse({
      conditions: [alertPriorityCondition],
      steps: [{name: 'email.send', params: {subject: 'Legacy'}}],
    })

    expect(normalizeGraph(response).nodes.map((node) => node.id)).toEqual(['trigger', 'conditions', 'step-1'])
  })

  it('round-trips draft data to workflow requests', () => {
    const graph = graphFromLegacy('alert.triggered', [alertPriorityCondition], [
      {name: 'email.send', params: {subject: 'Hello', body: 'World'}},
    ])

    expect(draftToRequest({
      name: '  Notify ',
      triggerName: 'alert.triggered',
      enabled: true,
      published: false,
      graph,
      onceForTemplate: ' alert.id, alert.source ,, ',
    })).toMatchObject({
      name: 'Notify',
      conditions: [alertPriorityCondition],
      steps: [{name: 'email.send', params: {subject: 'Hello', body: 'World'}}],
      once_for_template: ['alert.id', 'alert.source'],
    })
  })

  it('builds drafts from catalog defaults and existing workflows', () => {
    expect(emptyWorkflowDraft(catalog)).toMatchObject({
      triggerName: 'alert.triggered',
      onceForTemplate: 'alert.deduplication_key',
    })

    expect(draftFromWorkflow(workflowResponse({name: 'Existing'}))).toMatchObject({
      id: 1,
      name: 'Existing',
      triggerName: 'alert.triggered',
    })
  })
})

describe('workflow graph labels and defaults', () => {
  it('generates labels for triggers, actions, conditions, and controls', () => {
    expect(nodeLabel(triggerNode, catalog)).toBe('When an alert triggers')
    expect(nodeLabel({id: 'action-1', type: 'action', action: 'email.send'}, catalog)).toBe(
      'Email organization members'
    )
    expect(nodeLabel({id: 'condition-1', type: 'condition', kind: 'switch'})).toBe('Switch')
    expect(nodeLabel({id: 'condition-2', type: 'condition', kind: 'if'})).toBe('If / else')
    expect(nodeLabel({id: 'wait-1', type: 'control', kind: 'wait_until'})).toBe('Wait until')
    expect(nodeLabel({id: 'loop-1', type: 'control', kind: 'for_each'})).toBe('For each')
    expect(nodeLabel({id: 'while-1', type: 'control', kind: 'while'})).toBe('While')
    expect(nodeLabel({id: 'sleep-1', type: 'control', kind: 'sleep'})).toBe('Sleep')
  })

  it('falls back when catalog definitions are missing', () => {
    expect(nodeLabel({id: 'trigger-1', type: 'trigger'})).toBe('Trigger')
    expect(nodeLabel({id: 'action-1', type: 'action'})).toBe('Action')
    expect(stepDefinition(catalog, 'missing')).toBeUndefined()
  })

  it('creates default params by param name and type', () => {
    expect(defaultParamsForStep(emailStep)).toEqual({
      subject: 'Moneat workflow: {{alert.title}}',
      body: '{{alert.display_title}}\n\nPriority: {{alert.priority}}\nSource: {{alert.source}}\nView: {{alert.url}}',
      retries: 100,
      enabled: false,
    })
  })

  it('finds next IDs and converts graph actions to legacy steps', () => {
    const graph: WorkflowGraphConfig = {
      nodes: [
        triggerNode,
        {id: 'action-2', type: 'action', action: 'email.send', params: {count: 2, enabled: true, blank: null}},
      ],
      edges: [],
    }

    expect(nextNodeId(graph, 'action')).toBe('action-3')
    expect(stepsFromGraph(graph)).toEqual([{name: 'email.send', params: {count: '2', enabled: 'true', blank: ''}}])
  })

  it('formats references and statuses', () => {
    expect(splitReferences('alert.id, , alert.source')).toEqual(['alert.id', 'alert.source'])
    expect(statusClass('complete')).toContain('emerald')
    expect(statusClass('failed')).toContain('red')
    expect(statusClass('running')).toContain('blue')
    expect(statusClass('pending')).toContain('amber')
  })
})

describe('workflow palette drag payloads', () => {
  it('parses valid palette payloads', () => {
    const result = parseWorkflowPaletteDragPayload(JSON.stringify({node: {type: 'control'}, prefix: 'sleep'}))

    expect(result).toEqual({node: {type: 'control'}, prefix: 'sleep'})
  })

  it('rejects malformed palette payloads', () => {
    expect(parseWorkflowPaletteDragPayload('{bad json')).toBeNull()
    expect(parseWorkflowPaletteDragPayload(JSON.stringify(null))).toBeNull()
    expect(parseWorkflowPaletteDragPayload(JSON.stringify([]))).toBeNull()
    expect(parseWorkflowPaletteDragPayload(JSON.stringify({node: [], prefix: 'sleep'}))).toBeNull()
    expect(parseWorkflowPaletteDragPayload(JSON.stringify({node: {type: 'bad'}, prefix: 'sleep'}))).toBeNull()
    expect(parseWorkflowPaletteDragPayload(JSON.stringify({node: {type: 'control'}}))).toBeNull()
  })
})

describe('workflow validation', () => {
  it('reports invalid draft structure and missing required params', () => {
    const issues = validateDraft({
      name: ' ',
      triggerName: 'missing.trigger',
      enabled: true,
      published: false,
      graph: {
        nodes: [
          triggerNode,
          {...triggerNode, id: 'trigger-2'},
          {id: 'action-1', type: 'action', action: 'email.send', params: {subject: ''}},
          {id: 'action-2', type: 'action', action: 'missing.action'},
        ],
        edges: [{from: 'missing', to: 'action-1'}],
      },
      onceForTemplate: '',
    }, catalog)

    expect(issues.map((issue) => issue.message)).toEqual([
      'Workflow name is required.',
      'Graph must contain exactly one trigger.',
      'Select a valid trigger.',
      'Remove edges connected to missing nodes.',
      'Email organization members is missing Subject.',
      'Email organization members is missing Body.',
      'Unknown action missing.action.',
    ])
  })

  it('warns when a valid draft has no actions', () => {
    const issues = validateDraft({
      name: 'Notify',
      triggerName: 'alert.triggered',
      enabled: true,
      published: false,
      graph: {nodes: [triggerNode], edges: []},
      onceForTemplate: 'alert.id',
    }, catalog)

    expect(issues).toEqual([{level: 'warning', message: 'Add an action before publishing.'}])
  })

  it('updates matching nodes with default fields', () => {
    const graph: WorkflowGraphConfig = {nodes: [{id: 'action-1', type: 'action'}], edges: []}

    expect(updateGraphNode(graph, {id: 'action-1', type: 'action', action: 'email.send'}).nodes[0]).toMatchObject({
      action: 'email.send',
      params: {},
      conditions: [],
      cases: [],
      continue_on_error: false,
    })
  })
})
