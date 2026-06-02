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

import type {
  WorkflowCatalogResponse,
  WorkflowConditionConfig,
  WorkflowGraphConfig,
  WorkflowGraphEdge,
  WorkflowGraphNode,
  WorkflowGraphPosition,
  WorkflowJsonValue,
  WorkflowRequest,
  WorkflowResponse,
  WorkflowStepConfig,
  WorkflowStepDefinition,
} from '@/lib/api'
import dagre from '@dagrejs/dagre'

export const triggerNodeId = 'trigger'
export const workflowNodeWidth = 220
export const workflowNodeHeight = 78
export const workflowPaletteDragDataType = 'application/x-moneat-workflow-node'

const legacyConditionNodeId = 'conditions'
const workflowNodeVerticalGap = 80
const workflowNodeHorizontalGap = 80
const defaultAlertOnceForTemplate = 'alert.episode_key, alert.notification_sequence'

export interface WorkflowPaletteDragPayload {
  node: Omit<WorkflowGraphNode, 'id'>
  prefix: string
}

export interface WorkflowDraft {
  id?: number
  name: string
  triggerName: string
  enabled: boolean
  published: boolean
  graph: WorkflowGraphConfig
  onceForTemplate: string
}

export interface WorkflowValidationIssue {
  level: 'error' | 'warning'
  message: string
}

export function emptyWorkflowDraft(catalog?: WorkflowCatalogResponse): WorkflowDraft {
  const trigger = catalog?.triggers[0]
  return {
    name: '',
    triggerName: trigger?.name ?? 'alert.triggered',
    enabled: true,
    published: false,
    graph: graphFromLegacy(trigger?.name ?? 'alert.triggered', [], []),
    onceForTemplate: trigger?.default_once_for_template.join(', ') ?? defaultAlertOnceForTemplate,
  }
}

export function draftFromWorkflow(workflow: WorkflowResponse): WorkflowDraft {
  return {
    id: workflow.id,
    name: workflow.name,
    triggerName: workflow.trigger_name,
    enabled: workflow.enabled,
    published: workflow.published,
    graph: normalizeGraph(workflow),
    onceForTemplate: workflow.once_for_template.join(', '),
  }
}

export function draftToRequest(draft: WorkflowDraft): WorkflowRequest {
  return {
    name: draft.name.trim(),
    trigger_name: draft.triggerName,
    enabled: draft.enabled,
    conditions: legacyConditionsFromGraph(draft.graph),
    steps: stepsFromGraph(draft.graph),
    graph: draft.graph,
    once_for_template: splitReferences(draft.onceForTemplate),
  }
}

export function normalizeGraph(workflow: WorkflowResponse): WorkflowGraphConfig {
  if (workflow.graph.nodes.length > 0) return withDefaults(workflow.graph)
  return graphFromLegacy(workflow.trigger_name, workflow.conditions, workflow.steps)
}

export function graphFromLegacy(
  triggerName: string,
  conditions: WorkflowConditionConfig[],
  steps: WorkflowStepConfig[]
): WorkflowGraphConfig {
  const nodes: WorkflowGraphNode[] = [{
    id: triggerNodeId,
    type: 'trigger',
    trigger: triggerName,
    params: {},
    conditions: [],
    cases: [],
  }]
  if (conditions.length > 0) {
    nodes.push({
      id: legacyConditionNodeId,
      type: 'condition',
      kind: 'if',
      params: {},
      conditions,
      cases: [],
    })
  }
  steps.forEach((step, index) => {
    nodes.push({
      id: `step-${index + 1}`,
      type: 'action',
      action: step.name,
      params: step.params,
      conditions: [],
      cases: [],
      continue_on_error: true,
    })
  })
  return {nodes, edges: legacyEdges(conditions.length > 0, steps.length)}
}

export function addGraphNode(
  graph: WorkflowGraphConfig,
  node: WorkflowGraphNode,
  position?: WorkflowGraphPosition
): WorkflowGraphConfig {
  return {...graph, nodes: [...graph.nodes, withNodeDefaults(position === undefined ? node : {...node, position})]}
}

export function updateGraphNode(
  graph: WorkflowGraphConfig,
  node: WorkflowGraphNode
): WorkflowGraphConfig {
  return {
    ...graph,
    nodes: graph.nodes.map((item) => (item.id === node.id ? withNodeDefaults(node) : item)),
  }
}

export function removeGraphNode(
  graph: WorkflowGraphConfig,
  nodeId: string
): WorkflowGraphConfig {
  return {
    nodes: graph.nodes.filter((node) => node.id !== nodeId),
    edges: graph.edges.filter((edge) => edge.from !== nodeId && edge.to !== nodeId),
  }
}

export function updateGraphNodePosition(
  graph: WorkflowGraphConfig,
  nodeId: string,
  position: WorkflowGraphPosition
): WorkflowGraphConfig {
  return {
    ...graph,
    nodes: graph.nodes.map((node) => (node.id === nodeId ? {...node, position} : node)),
  }
}

export function nextNodeId(graph: WorkflowGraphConfig, prefix: string): string {
  const used = new Set(graph.nodes.map((node) => node.id))
  let index = graph.nodes.length + 1
  while (used.has(`${prefix}-${index}`)) index += 1
  return `${prefix}-${index}`
}

export function nextAppendedNodePosition(graph: WorkflowGraphConfig): WorkflowGraphPosition {
  const positions = resolveWorkflowNodePositions(graph)
  let appendedPosition: WorkflowGraphPosition | null = null
  let lowestBottom = Number.NEGATIVE_INFINITY

  graph.nodes.forEach((node) => {
    const position = positions.get(node.id)
    if (!position) return
    const bottom = position.y + workflowNodeHeight
    if (bottom >= lowestBottom) {
      lowestBottom = bottom
      appendedPosition = {x: position.x, y: bottom + workflowNodeVerticalGap}
    }
  })

  return appendedPosition ?? {x: 0, y: 0}
}

export function resolveWorkflowNodePositions(graph: WorkflowGraphConfig): Map<string, WorkflowGraphPosition> {
  const layout = layoutWorkflowGraph(graph)
  return new Map(
    graph.nodes.map((node) => [
      node.id,
      validNodePosition(node) ?? layout.get(node.id) ?? {x: 0, y: 0},
    ])
  )
}

export function parseWorkflowPaletteDragPayload(raw: string): WorkflowPaletteDragPayload | null {
  let parsed: unknown
  try {
    parsed = JSON.parse(raw)
  } catch {
    return null
  }
  if (!isRecord(parsed) || !isRecord(parsed.node) || typeof parsed.prefix !== 'string') return null
  if (!isWorkflowNodeType(parsed.node.type)) return null
  return {
    node: parsed.node as Omit<WorkflowGraphNode, 'id'>,
    prefix: parsed.prefix,
  }
}

export function nodeLabel(
  node: WorkflowGraphNode,
  catalog?: WorkflowCatalogResponse
): string {
  if (node.type === 'trigger') {
    return catalog?.triggers.find((trigger) => trigger.name === node.trigger)?.label ?? node.trigger ?? 'Trigger'
  }
  if (node.type === 'action') {
    return stepDefinition(catalog, node.action)?.label ?? node.action ?? 'Action'
  }
  if (node.type === 'condition') return node.kind === 'switch' ? 'Switch' : 'If / else'
  if (node.kind === 'wait_until') return 'Wait until'
  if (node.kind === 'for_each') return 'For each'
  if (node.kind === 'while') return 'While'
  return 'Sleep'
}

export function stepDefinition(
  catalog: WorkflowCatalogResponse | undefined,
  stepName: string | null | undefined
): WorkflowStepDefinition | undefined {
  return catalog?.steps.find((step) => step.name === stepName)
}

export function validateDraft(
  draft: WorkflowDraft,
  catalog?: WorkflowCatalogResponse
): WorkflowValidationIssue[] {
  const issues: WorkflowValidationIssue[] = []
  if (draft.name.trim().length === 0) issues.push({level: 'error', message: 'Workflow name is required.'})
  const triggerNodes = draft.graph.nodes.filter((node) => node.type === 'trigger')
  if (triggerNodes.length !== 1) issues.push({level: 'error', message: 'Graph must contain exactly one trigger.'})
  const trigger = catalog?.triggers.find((item) => item.name === draft.triggerName)
  if (!trigger) issues.push({level: 'error', message: 'Select a valid trigger.'})
  const actionNodes = draft.graph.nodes.filter((node) => node.type === 'action')
  if (actionNodes.length === 0) issues.push({level: 'warning', message: 'Add an action before publishing.'})
  const nodeIds = new Set(draft.graph.nodes.map((node) => node.id))
  draft.graph.edges.forEach((edge) => {
    if (!nodeIds.has(edge.from) || !nodeIds.has(edge.to)) {
      issues.push({level: 'error', message: 'Remove edges connected to missing nodes.'})
    }
  })
  actionNodes.forEach((node) => {
    const definition = stepDefinition(catalog, node.action)
    if (!definition) {
      issues.push({level: 'error', message: `Unknown action ${node.action ?? node.id}.`})
      return
    }
    definition.params.filter((param) => param.required).forEach((param) => {
      const value = String(node.params?.[param.name] ?? '').trim()
      if (value.length === 0) {
        issues.push({level: 'error', message: `${definition.label} is missing ${param.label}.`})
      }
    })
  })
  return issues
}

export function splitReferences(value: string): string[] {
  return value
    .split(',')
    .map((item) => item.trim())
    .filter(Boolean)
}

export function formatDate(value?: string | null): string {
  if (!value) return 'Never'
  return new Date(value).toLocaleString()
}

export function statusClass(status: string): string {
  if (status === 'complete') return 'border-success-border bg-success-bg text-success-fg'
  if (status === 'failed') return 'border-danger-border bg-danger-bg text-danger-fg'
  if (status === 'running') return 'border-info-border bg-info-bg text-info-fg'
  return 'border-warning-border bg-warning-bg text-warning-fg'
}

export function defaultParamsForStep(step: WorkflowStepDefinition): Record<string, WorkflowJsonValue> {
  return Object.fromEntries(step.params.map((param) => [param.name, defaultParamValue(param.name, param.type)]))
}

export function stepsFromGraph(graph: WorkflowGraphConfig): WorkflowStepConfig[] {
  return graph.nodes
    .filter((node) => node.type === 'action' && node.action)
    .map((node) => ({
      name: node.action ?? '',
      params: Object.fromEntries(
        Object.entries(node.params ?? {}).map(([key, value]) => [key, String(value ?? '')])
      ),
    }))
}

function legacyConditionsFromGraph(graph: WorkflowGraphConfig): WorkflowConditionConfig[] {
  return graph.nodes.find((node) => node.type === 'condition' && node.kind === 'if')?.conditions ?? []
}

function layoutWorkflowGraph(graph: WorkflowGraphConfig): Map<string, WorkflowGraphPosition> {
  const dagreGraph = new dagre.graphlib.Graph()
  dagreGraph.setDefaultEdgeLabel(() => ({}))
  dagreGraph.setGraph({
    rankdir: 'TB',
    ranksep: workflowNodeVerticalGap,
    nodesep: workflowNodeHorizontalGap,
  })
  graph.nodes.forEach((node) => {
    dagreGraph.setNode(node.id, {width: workflowNodeWidth, height: workflowNodeHeight})
  })
  graph.edges.forEach((edge) => dagreGraph.setEdge(edge.from, edge.to))
  dagre.layout(dagreGraph)
  return new Map(
    graph.nodes.map((node) => {
      const position = dagreGraph.node(node.id)
      return [node.id, {x: position.x - workflowNodeWidth / 2, y: position.y - workflowNodeHeight / 2}]
    })
  )
}

function validNodePosition(node: WorkflowGraphNode): WorkflowGraphPosition | null {
  if (!node.position) return null
  if (!Number.isFinite(node.position.x) || !Number.isFinite(node.position.y)) return null
  return node.position
}

function isRecord(value: unknown): value is Record<string, unknown> {
  return typeof value === 'object' && value !== null && !Array.isArray(value)
}

function isWorkflowNodeType(value: unknown): value is WorkflowGraphNode['type'] {
  return value === 'trigger' || value === 'condition' || value === 'action' || value === 'control'
}

function legacyEdges(
  hasConditions: boolean,
  stepCount: number
): WorkflowGraphEdge[] {
  const edges: WorkflowGraphEdge[] = []
  if (hasConditions) edges.push({from: triggerNodeId, to: legacyConditionNodeId})
  if (stepCount > 0) {
    edges.push({
      from: hasConditions ? legacyConditionNodeId : triggerNodeId,
      to: 'step-1',
      branch: hasConditions ? 'true' : undefined,
    })
  }
  for (let index = 1; index < stepCount; index += 1) {
    edges.push({from: `step-${index}`, to: `step-${index + 1}`})
  }
  return edges
}

function withDefaults(graph: WorkflowGraphConfig): WorkflowGraphConfig {
  return {
    nodes: graph.nodes.map(withNodeDefaults),
    edges: graph.edges,
  }
}

function withNodeDefaults(node: WorkflowGraphNode): WorkflowGraphNode {
  return {
    ...node,
    params: node.params ?? {},
    conditions: node.conditions ?? [],
    cases: node.cases ?? [],
    continue_on_error: node.continue_on_error ?? false,
  }
}

function defaultParamValue(
  name: string,
  type: string
): WorkflowJsonValue {
  if (name === 'subject') return 'Moneat workflow: {{alert.title}}'
  if (name === 'title') return 'Moneat workflow'
  if (type === 'Number') return 100
  if (type === 'Boolean') return false
  return [
    '{{alert.display_title}}',
    '',
    'Priority: {{alert.priority}}',
    'Source: {{alert.source}}',
    'View: {{alert.url}}',
  ].join('\n')
}
