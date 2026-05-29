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

import {Trash2} from 'lucide-react'
import type {
  WorkflowCatalogResponse,
  WorkflowConditionConfig,
  WorkflowGraphNode,
  WorkflowOperationDefinition,
  WorkflowScopeReferenceDefinition,
} from '@/lib/api'
import {Button} from '@/components/ui/button'
import {Input} from '@/components/ui/input'
import {Label} from '@/components/ui/label'
import {Select, SelectContent, SelectItem, SelectTrigger, SelectValue} from '@/components/ui/select'
import {Switch} from '@/components/ui/switch'
import {Textarea} from '@/components/ui/textarea'
import {nodeLabel, stepDefinition, triggerNodeId} from './workflowGraph'

interface NodeConfigPanelProps {
  node?: WorkflowGraphNode
  catalog?: WorkflowCatalogResponse
  triggerName: string
  onChange: (node: WorkflowGraphNode) => void
  onRemove: (nodeId: string) => void
}

export function NodeConfigPanel({
  node,
  catalog,
  triggerName,
  onChange,
  onRemove,
}: NodeConfigPanelProps) {
  if (!node) {
    return (
      <div className="rounded-md border bg-muted/20 p-4 text-sm text-muted-foreground">
        Select a node on the canvas to configure it.
      </div>
    )
  }
  return (
    <div className="space-y-4 rounded-md border bg-muted/20 p-3">
      <div className="flex items-start justify-between gap-3">
        <div>
          <h3 className="text-sm font-semibold">{nodeLabel(node, catalog)}</h3>
          <p className="text-xs text-muted-foreground">{node.type}</p>
        </div>
        {node.id !== triggerNodeId && (
          <Button type="button" variant="ghost" size="icon" onClick={() => onRemove(node.id)}>
            <Trash2 className="h-4 w-4" />
          </Button>
        )}
      </div>
      {node.type === 'trigger' && <TriggerFields node={node} catalog={catalog} onChange={onChange} />}
      {node.type === 'action' && <ActionFields node={node} catalog={catalog} onChange={onChange} />}
      {node.type === 'condition' && (
        <ConditionFields node={node} catalog={catalog} triggerName={triggerName} onChange={onChange} />
      )}
      {node.type === 'control' && (
        <ControlFields node={node} catalog={catalog} triggerName={triggerName} onChange={onChange} />
      )}
    </div>
  )
}

function TriggerFields({
  node,
  catalog,
  onChange,
}: {
  node: WorkflowGraphNode
  catalog?: WorkflowCatalogResponse
  onChange: (node: WorkflowGraphNode) => void
}) {
  return (
    <div className="space-y-1.5">
      <Label>Trigger</Label>
      <Select value={node.trigger ?? ''} onValueChange={(trigger) => onChange({...node, trigger})}>
        <SelectTrigger>
          <SelectValue />
        </SelectTrigger>
        <SelectContent>
          {catalog?.triggers.map((trigger) => (
            <SelectItem key={trigger.name} value={trigger.name}>
              {trigger.label}
            </SelectItem>
          ))}
        </SelectContent>
      </Select>
    </div>
  )
}

function ActionFields({
  node,
  catalog,
  onChange,
}: {
  node: WorkflowGraphNode
  catalog?: WorkflowCatalogResponse
  onChange: (node: WorkflowGraphNode) => void
}) {
  const definition = stepDefinition(catalog, node.action)
  return (
    <div className="space-y-3">
      <div className="space-y-1.5">
        <Label>Action</Label>
        <Select value={node.action ?? ''} onValueChange={(action) => onChange({...node, action, params: {}})}>
          <SelectTrigger>
            <SelectValue />
          </SelectTrigger>
          <SelectContent>
            {catalog?.steps.map((step) => (
              <SelectItem key={step.name} value={step.name}>
                {step.label}
              </SelectItem>
            ))}
          </SelectContent>
        </Select>
      </div>
      {definition?.params.map((param) => (
        <div key={param.name} className="space-y-1.5">
          <Label>{param.label}</Label>
          {param.type === 'Text' ? (
            <Textarea
              value={String(node.params?.[param.name] ?? '')}
              onChange={(event) => updateParam(node, param.name, event.target.value, onChange)}
              className="min-h-24"
            />
          ) : (
            <Input
              value={String(node.params?.[param.name] ?? '')}
              onChange={(event) => updateParam(node, param.name, event.target.value, onChange)}
            />
          )}
        </div>
      ))}
      <div className="flex items-center gap-2">
        <Switch
          checked={node.continue_on_error ?? false}
          onCheckedChange={(value) => onChange({...node, continue_on_error: value})}
        />
        <Label>Continue on error</Label>
      </div>
      <RetryFields node={node} onChange={onChange} />
    </div>
  )
}

function ConditionFields({
  node,
  catalog,
  triggerName,
  onChange,
}: {
  node: WorkflowGraphNode
  catalog?: WorkflowCatalogResponse
  triggerName: string
  onChange: (node: WorkflowGraphNode) => void
}) {
  return (
    <div className="space-y-3">
      <Select value={node.kind ?? 'if'} onValueChange={(kind) => onChange({...node, kind})}>
        <SelectTrigger>
          <SelectValue />
        </SelectTrigger>
        <SelectContent>
          <SelectItem value="if">If / else</SelectItem>
          <SelectItem value="switch">Switch</SelectItem>
        </SelectContent>
      </Select>
      <ConditionList
        conditions={node.conditions ?? []}
        catalog={catalog}
        triggerName={triggerName}
        onChange={(conditions) => onChange({...node, conditions})}
      />
    </div>
  )
}

function ControlFields({
  node,
  catalog,
  triggerName,
  onChange,
}: {
  node: WorkflowGraphNode
  catalog?: WorkflowCatalogResponse
  triggerName: string
  onChange: (node: WorkflowGraphNode) => void
}) {
  const paramName = node.kind === 'sleep' ? 'duration' : 'timeout'
  return (
    <div className="space-y-3">
      <div className="space-y-1.5">
        <Label>Control</Label>
        <Select value={node.kind ?? 'sleep'} onValueChange={(kind) => onChange({...node, kind})}>
          <SelectTrigger>
            <SelectValue />
          </SelectTrigger>
          <SelectContent>
            <SelectItem value="sleep">Sleep</SelectItem>
            <SelectItem value="wait_until">Wait until</SelectItem>
            <SelectItem value="for_each">For each</SelectItem>
            <SelectItem value="while">While</SelectItem>
          </SelectContent>
        </Select>
      </div>
      {(node.kind === 'sleep' || node.kind === 'wait_until') && (
        <div className="space-y-1.5">
          <Label>{node.kind === 'sleep' ? 'Duration' : 'Timeout'}</Label>
          <Input
            value={String(node.params?.[paramName] ?? '')}
            placeholder={node.kind === 'sleep' ? 'PT5M' : 'PT30M'}
            onChange={(event) => updateParam(node, paramName, event.target.value, onChange)}
          />
        </div>
      )}
      {(node.kind === 'wait_until' || node.kind === 'while') && (
        <ConditionList
          conditions={node.conditions ?? []}
          catalog={catalog}
          triggerName={triggerName}
          onChange={(conditions) => onChange({...node, conditions})}
        />
      )}
    </div>
  )
}

function RetryFields({
  node,
  onChange,
}: {
  node: WorkflowGraphNode
  onChange: (node: WorkflowGraphNode) => void
}) {
  const retry = node.retry ?? {
    max_attempts: 1,
    initial_interval: 'PT1S',
    backoff_coefficient: 2,
    non_retryable_error_types: [],
  }
  return (
    <div className="grid gap-2 sm:grid-cols-2">
      <div className="space-y-1.5">
        <Label>Attempts</Label>
        <Input
          type="number"
          min={1}
          value={retry.max_attempts}
          onChange={(event) => onChange({
            ...node,
            retry: {...retry, max_attempts: Number(event.target.value) || 1},
          })}
        />
      </div>
      <div className="space-y-1.5">
        <Label>Backoff</Label>
        <Input
          value={retry.initial_interval}
          onChange={(event) => onChange({...node, retry: {...retry, initial_interval: event.target.value}})}
        />
      </div>
    </div>
  )
}

function ConditionList({
  conditions,
  catalog,
  triggerName,
  onChange,
}: {
  conditions: WorkflowConditionConfig[]
  catalog?: WorkflowCatalogResponse
  triggerName: string
  onChange: (conditions: WorkflowConditionConfig[]) => void
}) {
  const trigger = catalog?.triggers.find((item) => item.name === triggerName)
  const firstReference = trigger?.scope[0]
  const addCondition = () => {
    if (!firstReference) return
    const operation = operationsForReference(catalog, firstReference)[0]
    onChange([...conditions, {reference: firstReference.name, operation: operation?.name ?? 'is_set', value: ''}])
  }
  return (
    <div className="space-y-2">
      <div className="flex items-center justify-between gap-2">
        <Label>Conditions</Label>
        <Button type="button" variant="outline" size="sm" onClick={addCondition}>
          Add
        </Button>
      </div>
      {conditions.length === 0 ? (
        <p className="rounded-md border bg-background px-3 py-2 text-sm text-muted-foreground">
          No conditions configured.
        </p>
      ) : (
        conditions.map((condition, index) => (
          <ConditionRow
            key={`${condition.reference}-${index}`}
            condition={condition}
            catalog={catalog}
            triggerName={triggerName}
            onChange={(next) => onChange(conditions.map((item, itemIndex) => (itemIndex === index ? next : item)))}
            onRemove={() => onChange(conditions.filter((_, itemIndex) => itemIndex !== index))}
          />
        ))
      )}
    </div>
  )
}

function ConditionRow({
  condition,
  catalog,
  triggerName,
  onChange,
  onRemove,
}: {
  condition: WorkflowConditionConfig
  catalog?: WorkflowCatalogResponse
  triggerName: string
  onChange: (condition: WorkflowConditionConfig) => void
  onRemove: () => void
}) {
  const trigger = catalog?.triggers.find((item) => item.name === triggerName)
  const reference = trigger?.scope.find((item) => item.name === condition.reference)
  const operations = operationsForReference(catalog, reference)
  return (
    <div className="grid gap-2 rounded-md border bg-background p-2">
      <Select value={condition.reference} onValueChange={(value) => onChange({...condition, reference: value})}>
        <SelectTrigger>
          <SelectValue />
        </SelectTrigger>
        <SelectContent>
          {trigger?.scope.map((scopeReference) => (
            <SelectItem key={scopeReference.name} value={scopeReference.name}>
              {scopeReference.label}
            </SelectItem>
          ))}
        </SelectContent>
      </Select>
      <Select value={condition.operation} onValueChange={(operation) => onChange({...condition, operation})}>
        <SelectTrigger>
          <SelectValue />
        </SelectTrigger>
        <SelectContent>
          {operations.map((operation) => (
            <SelectItem key={operation.name} value={operation.name}>
              {operation.label}
            </SelectItem>
          ))}
        </SelectContent>
      </Select>
      {condition.operation !== 'is_set' && condition.operation !== 'is_not_set' && (
        <Input value={condition.value ?? ''} onChange={(event) => onChange({...condition, value: event.target.value})} />
      )}
      <Button type="button" variant="ghost" size="sm" onClick={onRemove} className="justify-start text-destructive">
        <Trash2 className="mr-2 h-4 w-4" />
        Remove
      </Button>
    </div>
  )
}

function updateParam(
  node: WorkflowGraphNode,
  name: string,
  value: string,
  onChange: (node: WorkflowGraphNode) => void
) {
  onChange({...node, params: {...node.params, [name]: value}})
}

function operationsForReference(
  catalog: WorkflowCatalogResponse | undefined,
  reference: WorkflowScopeReferenceDefinition | undefined
): WorkflowOperationDefinition[] {
  const resource = catalog?.resources.find((item) => item.type === reference?.type)
  return resource?.operations ?? []
}
