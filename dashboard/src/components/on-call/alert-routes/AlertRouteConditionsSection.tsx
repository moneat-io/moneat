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

import {Plus, Trash2} from 'lucide-react'
import {Button} from '@/components/ui/button'
import {Input} from '@/components/ui/input'
import {
  Select,
  SelectContent,
  SelectItem,
  SelectTrigger,
  SelectValue,
} from '@/components/ui/select'
import type {
  AlertRouteCondition,
  AlertRouteConditionField,
  AlertRouteConditionGroup,
  AlertRouteConditionOperator,
} from '@/lib/api/types'
import {
  APPROVED_METADATA_KEYS,
  CONDITION_FIELD_OPTIONS,
  CONDITION_OPERATOR_OPTIONS,
  PRIORITY_OPTIONS,
  createDefaultCondition,
  fieldSupportsMetadataKey,
  operatorIsPriorityOnly,
  operatorTakesValues,
} from './alertRouteModel'

interface AlertRouteConditionsSectionProps {
  groups: AlertRouteConditionGroup[]
  onChange: (groups: AlertRouteConditionGroup[]) => void
}

// Condition groups are OR-ed together; conditions within a group are AND-ed. This
// mirrors the backend `AlertRouteMatcher` so what a route author builds here reads
// the same way it evaluates.
export function AlertRouteConditionsSection({
  groups,
  onChange,
}: Readonly<AlertRouteConditionsSectionProps>) {
  const updateGroup = (index: number, next: AlertRouteConditionGroup) =>
    onChange(groups.map((group, i) => (i === index ? next : group)))
  const removeGroup = (index: number) => onChange(groups.filter((_, i) => i !== index))
  const addGroup = () =>
    onChange([...groups, {conditions: [createDefaultCondition()]}])

  return (
    <div className="space-y-3">
      {groups.map((group, groupIndex) => (
        <div key={groupIndex} className="space-y-2">
          {groupIndex > 0 && (
            <div className="flex items-center gap-2">
              <div className="h-px flex-1 bg-border" />
              <span className="text-[11px] font-semibold uppercase tracking-wider text-muted-foreground">
                or
              </span>
              <div className="h-px flex-1 bg-border" />
            </div>
          )}
          <ConditionGroupEditor
            group={group}
            groupIndex={groupIndex}
            canRemoveGroup={groups.length > 1}
            onChange={(next) => updateGroup(groupIndex, next)}
            onRemove={() => removeGroup(groupIndex)}
          />
        </div>
      ))}
      <Button type="button" variant="outline" size="sm" className="h-8" onClick={addGroup}>
        <Plus className="mr-1.5 h-3.5 w-3.5" />
        Add condition group
      </Button>
    </div>
  )
}

interface ConditionGroupEditorProps {
  group: AlertRouteConditionGroup
  groupIndex: number
  canRemoveGroup: boolean
  onChange: (group: AlertRouteConditionGroup) => void
  onRemove: () => void
}

function ConditionGroupEditor({
  group,
  groupIndex,
  canRemoveGroup,
  onChange,
  onRemove,
}: Readonly<ConditionGroupEditorProps>) {
  const updateCondition = (index: number, next: AlertRouteCondition) =>
    onChange({conditions: group.conditions.map((condition, i) => (i === index ? next : condition))})
  const removeCondition = (index: number) =>
    onChange({conditions: group.conditions.filter((_, i) => i !== index)})
  const addCondition = () =>
    onChange({conditions: [...group.conditions, createDefaultCondition()]})

  return (
    <div className="rounded-lg border bg-muted/20 p-3">
      <div className="mb-2 flex items-center justify-between">
        <span className="text-xs font-medium text-muted-foreground">
          Match all of the following
        </span>
        {canRemoveGroup && (
          <Button
            type="button"
            variant="ghost"
            size="icon"
            className="h-7 w-7"
            onClick={onRemove}
            aria-label={`Remove condition group ${groupIndex + 1}`}
          >
            <Trash2 className="h-3.5 w-3.5" />
          </Button>
        )}
      </div>
      <div className="space-y-2">
        {group.conditions.map((condition, conditionIndex) => (
          <ConditionRow
            key={conditionIndex}
            condition={condition}
            canRemove={group.conditions.length > 1}
            onChange={(next) => updateCondition(conditionIndex, next)}
            onRemove={() => removeCondition(conditionIndex)}
          />
        ))}
      </div>
      <Button type="button" variant="ghost" size="sm" className="mt-2 h-7" onClick={addCondition}>
        <Plus className="mr-1.5 h-3.5 w-3.5" />
        Add condition
      </Button>
    </div>
  )
}

interface ConditionRowProps {
  condition: AlertRouteCondition
  canRemove: boolean
  onChange: (condition: AlertRouteCondition) => void
  onRemove: () => void
}

function ConditionRow({condition, canRemove, onChange, onRemove}: Readonly<ConditionRowProps>) {
  const showMetadataKey = fieldSupportsMetadataKey(condition.field)
  const showValues = operatorTakesValues(condition.operator)
  const usePriorityValue = operatorIsPriorityOnly(condition.operator) || condition.field === 'priority'

  const handleFieldChange = (field: AlertRouteConditionField) => {
    onChange({
      ...condition,
      field,
      metadataKey: fieldSupportsMetadataKey(field)
        ? condition.metadataKey ?? APPROVED_METADATA_KEYS[0]
        : undefined,
    })
  }

  return (
    <div className="flex flex-wrap items-start gap-2">
      <div className="min-w-[9rem] flex-1">
        <Select value={condition.field} onValueChange={(value) => handleFieldChange(value as AlertRouteConditionField)}>
          <SelectTrigger className="h-8" aria-label="Condition field">
            <SelectValue />
          </SelectTrigger>
          <SelectContent>
            {CONDITION_FIELD_OPTIONS.map((option) => (
              <SelectItem key={option.value} value={option.value}>
                {option.label}
              </SelectItem>
            ))}
          </SelectContent>
        </Select>
      </div>

      {showMetadataKey && (
        <div className="min-w-[9rem] flex-1">
          <Select
            value={condition.metadataKey ?? APPROVED_METADATA_KEYS[0]}
            onValueChange={(value) => onChange({...condition, metadataKey: value})}
          >
            <SelectTrigger className="h-8" aria-label="Metadata attribute">
              <SelectValue />
            </SelectTrigger>
            <SelectContent>
              {APPROVED_METADATA_KEYS.map((key) => (
                <SelectItem key={key} value={key}>
                  {key}
                </SelectItem>
              ))}
            </SelectContent>
          </Select>
        </div>
      )}

      <div className="min-w-[9rem] flex-1">
        <Select
          value={condition.operator}
          onValueChange={(value) => onChange({...condition, operator: value as AlertRouteConditionOperator})}
        >
          <SelectTrigger className="h-8" aria-label="Condition operator">
            <SelectValue />
          </SelectTrigger>
          <SelectContent>
            {CONDITION_OPERATOR_OPTIONS.map((option) => (
              <SelectItem key={option.value} value={option.value}>
                {option.label}
              </SelectItem>
            ))}
          </SelectContent>
        </Select>
      </div>

      {showValues && (
        <div className="min-w-[9rem] flex-[2]">
          {usePriorityValue ? (
            <Select
              value={condition.values[0] ?? PRIORITY_OPTIONS[0]}
              onValueChange={(value) => onChange({...condition, values: [value]})}
            >
              <SelectTrigger className="h-8" aria-label="Condition value">
                <SelectValue />
              </SelectTrigger>
              <SelectContent>
                {PRIORITY_OPTIONS.map((option) => (
                  <SelectItem key={option} value={option}>
                    {option}
                  </SelectItem>
                ))}
              </SelectContent>
            </Select>
          ) : (
            <Input
              className="h-8"
              aria-label="Condition value"
              value={condition.values.join(', ')}
              placeholder="value, value…"
              onChange={(event) =>
                onChange({...condition, values: splitValues(event.target.value)})
              }
            />
          )}
        </div>
      )}

      {canRemove && (
        <Button
          type="button"
          variant="ghost"
          size="icon"
          className="h-8 w-8 shrink-0"
          onClick={onRemove}
          aria-label="Remove condition"
        >
          <Trash2 className="h-3.5 w-3.5" />
        </Button>
      )}
    </div>
  )
}

// Keep the raw split forgiving: split on commas and drop a single empty entry so
// clearing the field yields an empty list rather than [''].
function splitValues(raw: string): string[] {
  const parts = raw.split(',').map((value) => value.trim())
  return parts.length === 1 && parts[0] === '' ? [] : parts
}
