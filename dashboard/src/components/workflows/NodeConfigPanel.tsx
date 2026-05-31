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

import {useState} from 'react'
import {useQuery} from '@tanstack/react-query'
import {Check, ChevronsUpDown, Loader2, Trash2} from 'lucide-react'
import type {
  EscalationPolicy,
  WorkflowCatalogResponse,
  WorkflowConditionConfig,
  WorkflowConnection,
  WorkflowConnectionGroup,
  WorkflowGraphNode,
  WorkflowOperationDefinition,
  WorkflowScopeReferenceDefinition,
  WorkflowStepParamDefinition,
  WorkflowSwitchCaseConfig,
} from '@/lib/api'
import {api} from '@/lib/api'
import {Button} from '@/components/ui/button'
import {
  Command,
  CommandEmpty,
  CommandGroup,
  CommandInput,
  CommandItem,
  CommandList,
} from '@/components/ui/command'
import {Input} from '@/components/ui/input'
import {Label} from '@/components/ui/label'
import {Popover, PopoverContent, PopoverTrigger} from '@/components/ui/popover'
import {Select, SelectContent, SelectItem, SelectTrigger, SelectValue} from '@/components/ui/select'
import {Switch} from '@/components/ui/switch'
import {Textarea} from '@/components/ui/textarea'
import {cn} from '@/lib/utils'
import {nodeLabel, stepDefinition, triggerNodeId} from './workflowGraph'

interface NodeConfigPanelProps {
  node?: WorkflowGraphNode
  catalog?: WorkflowCatalogResponse
  triggerName: string
  onChange: (node: WorkflowGraphNode) => void
  onRemove: (nodeId: string) => void
}

type ConditionKind = 'if' | 'switch'
type ControlKind = 'sleep' | 'wait_until' | 'for_each' | 'while'

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
        <ParamField key={param.name} node={node} param={param} onChange={onChange} />
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

function ParamField({
  node,
  param,
  onChange,
}: {
  node: WorkflowGraphNode
  param: WorkflowStepParamDefinition
  onChange: (node: WorkflowGraphNode) => void
}) {
  const value = node.params?.[param.name]
  if (node.action === 'oncall.page' && param.name === 'escalation_policy_id') {
    return (
      <EscalationPolicyParamField
        value={value}
        onChange={(policyId) => updateParamValue(node, param.name, policyId, onChange)}
      />
    )
  }
  if (node.type === 'action' && param.name === 'connection_id') {
    return (
      <ConnectionParamField
        value={value}
        actionName={node.action ?? undefined}
        onChange={(connectionId) => updateParamValue(node, param.name, connectionId, onChange)}
      />
    )
  }
  if (node.type === 'action' && param.name === 'connection_group_id') {
    return (
      <ConnectionGroupParamField
        value={value}
        actionName={node.action ?? undefined}
        onChange={(groupId) => updateParamValue(node, param.name, groupId, onChange)}
      />
    )
  }

  return (
    <div className="space-y-1.5">
      <Label>{param.label}</Label>
      {param.type === 'Text' && (
        <Textarea
          value={String(value ?? '')}
          onChange={(event) => updateParam(node, param.name, event.target.value, onChange)}
          className="min-h-24"
        />
      )}
      {param.type === 'Number' && (
        <Input
          type="number"
          value={String(value ?? '')}
          onChange={(event) => updateParamValue(node, param.name, event.target.value, onChange)}
          onBlur={(event) => commitNumberParam(node, param.name, event.target.value, onChange)}
        />
      )}
      {param.type === 'Boolean' && (
        <div className="flex h-10 items-center gap-2">
          <Switch
            checked={booleanParamChecked(value)}
            onCheckedChange={(checked) => updateParamValue(node, param.name, checked, onChange)}
          />
          <span className="text-sm text-muted-foreground">{booleanParamChecked(value) ? 'True' : 'False'}</span>
        </div>
      )}
      {param.type !== 'Text' && param.type !== 'Number' && param.type !== 'Boolean' && (
        <Input
          value={String(value ?? '')}
          onChange={(event) => updateParam(node, param.name, event.target.value, onChange)}
        />
      )}
    </div>
  )
}

function ConnectionParamField({
  value,
  actionName,
  onChange,
}: {
  value: unknown
  actionName?: string
  onChange: (connectionId: number | '') => void
}) {
  const [open, setOpen] = useState(false)
  const selectedConnectionId = numberParamValue(value)
  const connectionType = connectionTypeForAction(actionName)
  const {data: connections = [], isLoading} = useQuery({
    queryKey: ['workflow-connections'],
    queryFn: () => api.listWorkflowConnections(),
  })
  const visibleConnections = connections.filter((connection) => connectionMatchesAction(connection, connectionType))
  const selectedConnection = connections.find((connection) => connection.id === selectedConnectionId)
  const emptyLabel = connections.length === 0 ? 'No connections found.' : 'No matching connections found.'

  return (
    <div className="space-y-1.5">
      <Label>Connection</Label>
      <Popover open={open} onOpenChange={setOpen}>
        <PopoverTrigger asChild>
          <Button
            type="button"
            variant="outline"
            role="combobox"
            aria-expanded={open}
            className={cn('w-full justify-between font-normal', !selectedConnection && 'text-muted-foreground')}
          >
            <span className="min-w-0 truncate">
              {selectedConnection?.name ?? selectedConnectionFallbackLabel(selectedConnectionId)}
            </span>
            {isLoading ? (
              <Loader2 className="ml-2 h-4 w-4 shrink-0 animate-spin opacity-60" />
            ) : (
              <ChevronsUpDown className="ml-2 h-4 w-4 shrink-0 opacity-50" />
            )}
          </Button>
        </PopoverTrigger>
        <PopoverContent className="w-[var(--radix-popover-trigger-width)] p-0" align="start">
          <Command>
            <CommandInput placeholder="Search connections..." />
            <CommandList>
              <CommandEmpty>{emptyLabel}</CommandEmpty>
              <CommandGroup>
                {selectedConnectionId !== null && (
                  <CommandItem
                    value="no connection"
                    onSelect={() => {
                      onChange('')
                      setOpen(false)
                    }}
                    className="flex items-center gap-2"
                  >
                    <Check className="h-4 w-4 shrink-0 opacity-0" />
                    <span className="text-muted-foreground">No connection</span>
                  </CommandItem>
                )}
                {visibleConnections.map((connection) => (
                  <ConnectionItem
                    key={connection.id}
                    connection={connection}
                    selected={connection.id === selectedConnectionId}
                    onSelect={() => {
                      onChange(connection.id)
                      setOpen(false)
                    }}
                  />
                ))}
              </CommandGroup>
            </CommandList>
          </Command>
        </PopoverContent>
      </Popover>
    </div>
  )
}

function ConnectionItem({
  connection,
  selected,
  onSelect,
}: {
  connection: WorkflowConnection
  selected: boolean
  onSelect: () => void
}) {
  return (
    <CommandItem
      value={`${connection.name} ${connection.type} ${connectionTagSearchText(connection)}`}
      onSelect={onSelect}
      className="flex items-start gap-2"
    >
      <Check className={cn('mt-0.5 h-4 w-4 shrink-0', selected ? 'opacity-100' : 'opacity-0')} />
      <span className="min-w-0">
        <span className="block truncate font-medium">{connection.name}</span>
        <span className="block truncate text-xs text-muted-foreground">
          {connectionTypeLabel(connection.type)} - {connectionDetailLabel(connection)}
        </span>
      </span>
    </CommandItem>
  )
}

function ConnectionGroupParamField({
  value,
  actionName,
  onChange,
}: {
  value: unknown
  actionName?: string
  onChange: (groupId: number | '') => void
}) {
  const [open, setOpen] = useState(false)
  const selectedGroupId = numberParamValue(value)
  const connectionType = connectionTypeForAction(actionName)
  const {data: groups = [], isLoading} = useQuery({
    queryKey: ['workflow-connection-groups'],
    queryFn: () => api.listWorkflowConnectionGroups(),
  })
  const visibleGroups = groups.filter((group) => connectionGroupMatchesAction(group, connectionType))
  const selectedGroup = groups.find((group) => group.id === selectedGroupId)
  const emptyLabel = groups.length === 0 ? 'No connection groups found.' : 'No matching connection groups found.'

  return (
    <div className="space-y-1.5">
      <Label>Connection group</Label>
      <Popover open={open} onOpenChange={setOpen}>
        <PopoverTrigger asChild>
          <Button
            type="button"
            variant="outline"
            role="combobox"
            aria-expanded={open}
            className={cn('w-full justify-between font-normal', !selectedGroup && 'text-muted-foreground')}
          >
            <span className="min-w-0 truncate">
              {selectedGroup?.name ?? selectedGroupFallbackLabel(selectedGroupId)}
            </span>
            {isLoading ? (
              <Loader2 className="ml-2 h-4 w-4 shrink-0 animate-spin opacity-60" />
            ) : (
              <ChevronsUpDown className="ml-2 h-4 w-4 shrink-0 opacity-50" />
            )}
          </Button>
        </PopoverTrigger>
        <PopoverContent className="w-[var(--radix-popover-trigger-width)] p-0" align="start">
          <Command>
            <CommandInput placeholder="Search connection groups..." />
            <CommandList>
              <CommandEmpty>{emptyLabel}</CommandEmpty>
              <CommandGroup>
                {selectedGroupId !== null && (
                  <CommandItem
                    value="no connection group"
                    onSelect={() => {
                      onChange('')
                      setOpen(false)
                    }}
                    className="flex items-center gap-2"
                  >
                    <Check className="h-4 w-4 shrink-0 opacity-0" />
                    <span className="text-muted-foreground">No connection group</span>
                  </CommandItem>
                )}
                {visibleGroups.map((group) => (
                  <ConnectionGroupItem
                    key={group.id}
                    group={group}
                    selected={group.id === selectedGroupId}
                    onSelect={() => {
                      onChange(group.id)
                      setOpen(false)
                    }}
                  />
                ))}
              </CommandGroup>
            </CommandList>
          </Command>
        </PopoverContent>
      </Popover>
    </div>
  )
}

function ConnectionGroupItem({
  group,
  selected,
  onSelect,
}: {
  group: WorkflowConnectionGroup
  selected: boolean
  onSelect: () => void
}) {
  const memberCount = group.member_connection_ids.length
  return (
    <CommandItem
      value={`${group.name} ${group.connection_type} ${group.selection_strategy} ${group.id}`}
      onSelect={onSelect}
      className="flex items-start gap-2"
    >
      <Check className={cn('mt-0.5 h-4 w-4 shrink-0', selected ? 'opacity-100' : 'opacity-0')} />
      <span className="min-w-0">
        <span className="block truncate font-medium">{group.name}</span>
        <span className="block truncate text-xs text-muted-foreground">
          {connectionTypeLabel(group.connection_type)} - {memberCount} member{memberCount === 1 ? '' : 's'} -{' '}
          {selectionStrategyLabel(group.selection_strategy)}
        </span>
      </span>
    </CommandItem>
  )
}

function EscalationPolicyParamField({
  value,
  onChange,
}: {
  value: unknown
  onChange: (policyId: number) => void
}) {
  const [open, setOpen] = useState(false)
  const selectedPolicyId = numberParamValue(value)
  const {data: policies = [], isLoading} = useQuery({
    queryKey: ['escalation-policies'],
    queryFn: () => api.getEscalationPolicies(),
  })
  const selectedPolicy = policies.find((policy) => policy.id === selectedPolicyId)

  return (
    <div className="space-y-1.5">
      <Label>Escalation policy</Label>
      <Popover open={open} onOpenChange={setOpen}>
        <PopoverTrigger asChild>
          <Button
            type="button"
            variant="outline"
            role="combobox"
            aria-expanded={open}
            className={cn('w-full justify-between font-normal', !selectedPolicy && 'text-muted-foreground')}
          >
            <span className="min-w-0 truncate">
              {selectedPolicy?.name ?? selectedPolicyFallbackLabel(selectedPolicyId)}
            </span>
            {isLoading ? (
              <Loader2 className="ml-2 h-4 w-4 shrink-0 animate-spin opacity-60" />
            ) : (
              <ChevronsUpDown className="ml-2 h-4 w-4 shrink-0 opacity-50" />
            )}
          </Button>
        </PopoverTrigger>
        <PopoverContent className="w-[var(--radix-popover-trigger-width)] p-0" align="start">
          <Command>
            <CommandInput placeholder="Search escalation policies..." />
            <CommandList>
              <CommandEmpty>No escalation policy found.</CommandEmpty>
              <CommandGroup>
                {policies.map((policy) => (
                  <EscalationPolicyItem
                    key={policy.id}
                    policy={policy}
                    selected={policy.id === selectedPolicyId}
                    onSelect={() => {
                      onChange(policy.id)
                      setOpen(false)
                    }}
                  />
                ))}
              </CommandGroup>
            </CommandList>
          </Command>
        </PopoverContent>
      </Popover>
    </div>
  )
}

function EscalationPolicyItem({
  policy,
  selected,
  onSelect,
}: {
  policy: EscalationPolicy
  selected: boolean
  onSelect: () => void
}) {
  return (
    <CommandItem
      value={`${policy.name} ${policy.description ?? ''}`}
      onSelect={onSelect}
      className="flex items-start gap-2"
    >
      <Check className={cn('mt-0.5 h-4 w-4 shrink-0', selected ? 'opacity-100' : 'opacity-0')} />
      <span className="min-w-0">
        <span className="block truncate font-medium">{policy.name}</span>
        <span className="block truncate text-xs text-muted-foreground">
          {policy.description || `${policy.steps.length} step${policy.steps.length === 1 ? '' : 's'}`}
        </span>
      </span>
    </CommandItem>
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
  const conditionKind = node.kind === 'switch' ? 'switch' : 'if'
  return (
    <div className="space-y-3">
      <Select value={conditionKind} onValueChange={(kind) => onChange(nodeForConditionKind(node, kind))}>
        <SelectTrigger>
          <SelectValue />
        </SelectTrigger>
        <SelectContent>
          <SelectItem value="if">If / else</SelectItem>
          <SelectItem value="switch">Switch</SelectItem>
        </SelectContent>
      </Select>
      {conditionKind === 'switch' ? (
        <SwitchCaseList
          cases={node.cases ?? []}
          catalog={catalog}
          triggerName={triggerName}
          onChange={(cases) => onChange({...node, cases})}
        />
      ) : (
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
  const controlKind = controlKindFor(node.kind)
  return (
    <div className="space-y-3">
      <div className="space-y-1.5">
        <Label>Control</Label>
        <Select value={controlKind} onValueChange={(kind) => onChange(nodeForControlKind(node, kind))}>
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
      {node.kind === 'for_each' && (
        <div className="grid gap-2">
          <div className="space-y-1.5">
            <Label>Items reference</Label>
            <Input
              value={String(node.params?.items_reference ?? '')}
              placeholder="alert.items"
              onChange={(event) => updateParam(node, 'items_reference', event.target.value, onChange)}
            />
          </div>
          <div className="space-y-1.5">
            <Label>Item variable</Label>
            <Input
              value={String(node.params?.item_variable ?? 'item')}
              onChange={(event) => updateParam(node, 'item_variable', event.target.value, onChange)}
            />
          </div>
          <div className="space-y-1.5">
            <Label>Max items</Label>
            <Input
              type="number"
              min={1}
              value={String(node.params?.max_items ?? '100')}
              onChange={(event) => updateParam(node, 'max_items', event.target.value, onChange)}
            />
          </div>
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

function SwitchCaseList({
  cases,
  catalog,
  triggerName,
  onChange,
}: {
  cases: WorkflowSwitchCaseConfig[]
  catalog?: WorkflowCatalogResponse
  triggerName: string
  onChange: (cases: WorkflowSwitchCaseConfig[]) => void
}) {
  const addCase = () => {
    const nextNumber = cases.length + 1
    onChange([...cases, defaultSwitchCase(nextNumber)])
  }
  const updateCase = (index: number, nextCase: WorkflowSwitchCaseConfig) => {
    onChange(cases.map((item, itemIndex) => (itemIndex === index ? nextCase : item)))
  }
  return (
    <div className="space-y-2">
      <div className="flex items-center justify-between gap-2">
        <Label>Cases</Label>
        <Button type="button" variant="outline" size="sm" onClick={addCase}>
          Add
        </Button>
      </div>
      {cases.length === 0 ? (
        <p className="rounded-md border bg-background px-3 py-2 text-sm text-muted-foreground">
          No switch cases configured.
        </p>
      ) : (
        cases.map((switchCase, index) => (
          <div key={`${switchCase.name}-${index}`} className="grid gap-2 rounded-md border bg-background p-2">
            <div className="grid gap-2 sm:grid-cols-2">
              <div className="space-y-1.5">
                <Label>Branch key</Label>
                <Input
                  value={switchCase.name}
                  onChange={(event) => updateCase(index, {...switchCase, name: event.target.value})}
                />
              </div>
              <div className="space-y-1.5">
                <Label>Label</Label>
                <Input
                  value={switchCase.label ?? ''}
                  onChange={(event) => updateCase(index, {...switchCase, label: event.target.value})}
                />
              </div>
            </div>
            <ConditionList
              conditions={switchCase.conditions ?? []}
              catalog={catalog}
              triggerName={triggerName}
              onChange={(conditions) => updateCase(index, {...switchCase, conditions})}
            />
            <Button
              type="button"
              variant="ghost"
              size="sm"
              onClick={() => onChange(cases.filter((_, itemIndex) => itemIndex !== index))}
              className="justify-start text-destructive"
            >
              <Trash2 className="mr-2 h-4 w-4" />
              Remove case
            </Button>
          </div>
        ))
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
      <Select
        value={condition.reference}
        onValueChange={(value) => {
          const nextReference = trigger?.scope.find((item) => item.name === value)
          const operation = operationsForReference(catalog, nextReference)[0]?.name ?? 'is_set'
          onChange({...condition, reference: value, operation, value: ''})
        }}
      >
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
        <Input
          value={condition.value ?? ''}
          onChange={(event) => onChange({...condition, value: event.target.value})}
        />
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
  updateParamValue(node, name, value, onChange)
}

function commitNumberParam(
  node: WorkflowGraphNode,
  name: string,
  value: string,
  onChange: (node: WorkflowGraphNode) => void
) {
  const trimmed = value.trim()
  if (trimmed === '') {
    updateParamValue(node, name, '', onChange)
    return
  }
  const parsed = Number(trimmed)
  updateParamValue(node, name, Number.isFinite(parsed) ? parsed : trimmed, onChange)
}

function updateParamValue(
  node: WorkflowGraphNode,
  name: string,
  value: string | number | boolean,
  onChange: (node: WorkflowGraphNode) => void
) {
  onChange({...node, params: {...node.params, [name]: value}})
}

function numberParamValue(value: unknown): number | null {
  if (typeof value === 'number' && Number.isFinite(value)) return value
  if (typeof value !== 'string') return null
  const trimmed = value.trim()
  if (trimmed === '') return null
  const parsed = Number(trimmed)
  return Number.isFinite(parsed) ? parsed : null
}

function selectedPolicyFallbackLabel(policyId: number | null): string {
  return policyId === null ? 'Select escalation policy...' : `Policy #${policyId}`
}

function selectedConnectionFallbackLabel(connectionId: number | null): string {
  return connectionId === null ? 'Select connection...' : 'Unknown connection'
}

function selectedGroupFallbackLabel(groupId: number | null): string {
  return groupId === null ? 'Select connection group...' : 'Unknown connection group'
}

function connectionMatchesAction(connection: WorkflowConnection, connectionType: string | null): boolean {
  return connectionType === null || normalizeConnectionType(connection.type) === connectionType
}

function connectionGroupMatchesAction(group: WorkflowConnectionGroup, connectionType: string | null): boolean {
  return connectionType === null || normalizeConnectionType(group.connection_type) === connectionType
}

function connectionTypeForAction(actionName: string | undefined): string | null {
  const normalizedAction = normalizeConnectionType(actionName ?? '')
  if (normalizedAction.includes('pagerduty')) return 'pagerduty'
  if (normalizedAction.includes('servicenow')) return 'servicenow'
  if (normalizedAction.includes('github')) return 'github'
  if (normalizedAction.includes('jira')) return 'jira'
  return null
}

function normalizeConnectionType(value: string): string {
  return value.toLowerCase().replace(/[\s_.-]/g, '')
}

function connectionTypeLabel(connectionType: string): string {
  if (normalizeConnectionType(connectionType) === 'pagerduty') return 'PagerDuty'
  if (normalizeConnectionType(connectionType) === 'servicenow') return 'ServiceNow'
  if (normalizeConnectionType(connectionType) === 'github') return 'GitHub'
  if (normalizeConnectionType(connectionType) === 'jira') return 'Jira'
  return connectionType
}

function selectionStrategyLabel(selectionStrategy: string): string {
  return selectionStrategy
    .split(/[\s_-]+/)
    .filter(Boolean)
    .map((word) => word.charAt(0).toUpperCase() + word.slice(1).toLowerCase())
    .join(' ')
}

function connectionDetailLabel(connection: WorkflowConnection): string {
  const tagEntries = Object.entries(connection.identifier_tags)
  if (tagEntries.length > 0) {
    return tagEntries
      .slice(0, 2)
      .map(([key, value]) => `${key}: ${value}`)
      .join(', ')
  }
  return connection.last_four ? `Secret ending ${connection.last_four}` : 'No tags'
}

function connectionTagSearchText(connection: WorkflowConnection): string {
  return Object.entries(connection.identifier_tags)
    .map(([key, value]) => `${key} ${value}`)
    .join(' ')
}

function booleanParamChecked(value: unknown): boolean {
  return value === true || value === 'true'
}

function nodeForConditionKind(
  node: WorkflowGraphNode,
  kind: string
): WorkflowGraphNode {
  const nextKind: ConditionKind = kind === 'switch' ? 'switch' : 'if'
  if (nextKind === 'switch') {
    const cases = node.cases?.length ? node.cases : [defaultSwitchCase(1, node.conditions ?? [])]
    return {...node, kind: nextKind, conditions: [], cases}
  }
  return {...node, kind: nextKind, cases: []}
}

function nodeForControlKind(
  node: WorkflowGraphNode,
  kind: string
): WorkflowGraphNode {
  const nextKind = controlKindFor(kind)
  if (nextKind === 'sleep') {
    return {...node, kind: nextKind, params: {...node.params, duration: String(node.params?.duration ?? 'PT5M')}}
  }
  if (nextKind === 'wait_until') {
    return {...node, kind: nextKind, params: {...node.params, timeout: String(node.params?.timeout ?? 'PT30M')}}
  }
  if (nextKind === 'for_each') {
    return {
      ...node,
      kind: nextKind,
      params: {
        ...node.params,
        items_reference: String(node.params?.items_reference ?? ''),
        item_variable: String(node.params?.item_variable ?? 'item'),
        max_items: String(node.params?.max_items ?? '100'),
      },
      conditions: [],
    }
  }
  return {
    ...node,
    kind: nextKind,
    params: {...node.params, max_iterations: String(node.params?.max_iterations ?? '100')},
  }
}

function controlKindFor(kind: string | null | undefined): ControlKind {
  if (kind === 'wait_until' || kind === 'for_each' || kind === 'while') return kind
  return 'sleep'
}

function defaultSwitchCase(
  nextNumber: number,
  conditions: WorkflowConditionConfig[] = []
): WorkflowSwitchCaseConfig {
  return {name: `case-${nextNumber}`, label: `Case ${nextNumber}`, conditions}
}

function operationsForReference(
  catalog: WorkflowCatalogResponse | undefined,
  reference: WorkflowScopeReferenceDefinition | undefined
): WorkflowOperationDefinition[] {
  const resource = catalog?.resources.find((item) => item.type === reference?.type)
  return resource?.operations ?? []
}
