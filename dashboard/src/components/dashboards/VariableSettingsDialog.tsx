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

import {useEffect, useState} from 'react'
import type {DashboardVariable} from '@/lib/api'
import {
  Dialog,
  DialogContent,
  DialogHeader,
  DialogTitle,
  DialogDescription,
  DialogFooter,
} from '@/components/ui/dialog'
import {Button} from '@/components/ui/button'
import {Input} from '@/components/ui/input'
import {Label} from '@/components/ui/label'
import {Plus, Trash2, GripVertical, ChevronDown, ChevronRight} from 'lucide-react'

const VARIABLE_TYPES = [
  {value: 'custom', label: 'Custom (dropdown)'},
  {value: 'textbox', label: 'Text Input'},
  {value: 'constant', label: 'Constant'},
  {value: 'query', label: 'Query'},
]

function emptyVariable(): DashboardVariable {
  return {name: '', type: 'custom', options: []}
}

interface VariableSettingsDialogProps {
  open: boolean
  onOpenChange: (open: boolean) => void
  variables: DashboardVariable[]
  onSave: (variables: DashboardVariable[]) => void
}

export function VariableSettingsDialog({
  open,
  onOpenChange,
  variables,
  onSave,
}: VariableSettingsDialogProps) {
  const [draft, setDraft] = useState<DashboardVariable[]>([])
  const [expandedIndex, setExpandedIndex] = useState<number | null>(null)
  const [optionInput, setOptionInput] = useState('')

  // Initialize draft when dialog opens
  useEffect(() => {
    if (open) {
      setDraft(variables.map((v) => ({...v, options: [...(v.options ?? [])]})))
      setExpandedIndex(variables.length > 0 ? 0 : null)
      setOptionInput('')
    }
  }, [open, variables])

  const updateVar = (index: number, patch: Partial<DashboardVariable>) => {
    setDraft((prev) => prev.map((v, i) => (i === index ? {...v, ...patch} : v)))
  }

  const addVariable = () => {
    setDraft((prev) => [...prev, emptyVariable()])
    setExpandedIndex(draft.length)
  }

  const removeVariable = (index: number) => {
    setDraft((prev) => prev.filter((_, i) => i !== index))
    if (expandedIndex === index) setExpandedIndex(null)
    else if (expandedIndex !== null && expandedIndex > index)
      setExpandedIndex(expandedIndex - 1)
  }

  const addOption = (index: number) => {
    const trimmed = optionInput.trim()
    if (!trimmed) return
    const v = draft[index]
    if (!v.options.includes(trimmed)) {
      updateVar(index, {options: [...v.options, trimmed]})
    }
    setOptionInput('')
  }

  const removeOption = (varIndex: number, optIndex: number) => {
    const v = draft[varIndex]
    updateVar(varIndex, {options: v.options.filter((_, i) => i !== optIndex)})
  }

  const handleSave = () => {
    const cleaned = draft
      .filter((v) => v.name.trim())
      .map((v) => ({...v, name: v.name.trim()}))
    onSave(cleaned)
    onOpenChange(false)
  }

  return (
    <Dialog open={open} onOpenChange={onOpenChange}>
      <DialogContent className="sm:max-w-[560px] max-h-[80vh] flex flex-col">
        <DialogHeader>
          <DialogTitle>Dashboard Variables</DialogTitle>
          <DialogDescription>
            Define variables that can be used in queries as <code className="text-xs bg-muted px-1 rounded">$variable_name</code>.
          </DialogDescription>
        </DialogHeader>

        <div className="flex-1 overflow-y-auto space-y-2 py-2 min-h-0">
          {draft.length === 0 && (
            <div className="text-center text-sm text-muted-foreground py-8">
              No variables defined. Click &quot;Add Variable&quot; to get started.
            </div>
          )}

          {draft.map((v, i) => {
            const isExpanded = expandedIndex === i
            return (
              <div
                key={i}
                className="border rounded-lg overflow-hidden"
              >
                {/* Collapsed header */}
                <button
                  type="button"
                  className="w-full flex items-center gap-2 px-3 py-2 text-left hover:bg-muted/50 transition-colors"
                  onClick={() => setExpandedIndex(isExpanded ? null : i)}
                >
                  <GripVertical className="h-3.5 w-3.5 text-muted-foreground shrink-0" />
                  {isExpanded ? (
                    <ChevronDown className="h-3.5 w-3.5 text-muted-foreground shrink-0" />
                  ) : (
                    <ChevronRight className="h-3.5 w-3.5 text-muted-foreground shrink-0" />
                  )}
                  <span className="text-sm font-medium truncate flex-1">
                    {v.name || <span className="text-muted-foreground italic">Unnamed variable</span>}
                  </span>
                  <span className="text-xs text-muted-foreground">
                    {VARIABLE_TYPES.find((t) => t.value === v.type)?.label ?? v.type}
                  </span>
                  <Button
                    variant="ghost"
                    size="sm"
                    className="h-6 w-6 p-0 shrink-0"
                    onClick={(e) => {
                      e.stopPropagation()
                      removeVariable(i)
                    }}
                  >
                    <Trash2 className="h-3.5 w-3.5 text-destructive" />
                  </Button>
                </button>

                {/* Expanded form */}
                {isExpanded && (
                  <div className="px-3 pb-3 space-y-3 border-t bg-muted/20">
                    <div className="grid grid-cols-2 gap-3 pt-3">
                      <div className="space-y-1">
                        <Label className="text-xs">Name</Label>
                        <Input
                          className="h-8 text-sm"
                          placeholder="e.g. environment"
                          value={v.name}
                          onChange={(e) => updateVar(i, {name: e.target.value.replace(/[^a-zA-Z0-9_]/g, '')})}
                        />
                      </div>
                      <div className="space-y-1">
                        <Label className="text-xs">Label</Label>
                        <Input
                          className="h-8 text-sm"
                          placeholder="Display label"
                          value={v.label ?? ''}
                          onChange={(e) => updateVar(i, {label: e.target.value || null})}
                        />
                      </div>
                    </div>

                    <div className="grid grid-cols-2 gap-3">
                      <div className="space-y-1">
                        <Label className="text-xs">Type</Label>
                        <select
                          className="h-8 w-full px-2 text-sm border rounded-md bg-background"
                          value={v.type}
                          onChange={(e) => updateVar(i, {type: e.target.value})}
                        >
                          {VARIABLE_TYPES.map((t) => (
                            <option key={t.value} value={t.value}>
                              {t.label}
                            </option>
                          ))}
                        </select>
                      </div>
                      <div className="space-y-1">
                        <Label className="text-xs">Default Value</Label>
                        <Input
                          className="h-8 text-sm"
                          placeholder="Default"
                          value={v.default_value ?? v.current ?? ''}
                          onChange={(e) => updateVar(i, {default_value: e.target.value || null, current: e.target.value || null})}
                        />
                      </div>
                    </div>

                    {v.type === 'query' && (
                      <div className="space-y-1">
                        <Label className="text-xs">Query</Label>
                        <Input
                          className="h-8 text-sm font-mono"
                          placeholder="SELECT DISTINCT environment FROM events"
                          value={v.query ?? ''}
                          onChange={(e) => updateVar(i, {query: e.target.value || null})}
                        />
                      </div>
                    )}

                    {(v.type === 'custom' || v.type === 'query') && (
                      <div className="space-y-1">
                        <Label className="text-xs">Options</Label>
                        <div className="flex flex-wrap gap-1 min-h-[28px] p-1.5 border rounded-md bg-background">
                          {v.options.map((opt, oi) => (
                            <span
                              key={oi}
                              className="inline-flex items-center gap-0.5 px-1.5 py-0.5 text-xs bg-muted rounded"
                            >
                              {opt}
                              <button
                                type="button"
                                className="hover:text-destructive"
                                onClick={() => removeOption(i, oi)}
                              >
                                ×
                              </button>
                            </span>
                          ))}
                        </div>
                        <div className="flex gap-1">
                          <Input
                            className="h-7 text-xs flex-1"
                            placeholder="Add option..."
                            value={expandedIndex === i ? optionInput : ''}
                            onChange={(e) => setOptionInput(e.target.value)}
                            onKeyDown={(e) => {
                              if (e.key === 'Enter') {
                                e.preventDefault()
                                addOption(i)
                              }
                            }}
                          />
                          <Button
                            variant="outline"
                            size="sm"
                            className="h-7 text-xs"
                            onClick={() => addOption(i)}
                          >
                            Add
                          </Button>
                        </div>
                      </div>
                    )}
                  </div>
                )}
              </div>
            )
          })}
        </div>

        <DialogFooter className="flex items-center gap-2 pt-2 border-t">
          <Button variant="outline" size="sm" onClick={addVariable} className="mr-auto">
            <Plus className="h-3.5 w-3.5 mr-1" /> Add Variable
          </Button>
          <Button variant="outline" size="sm" onClick={() => onOpenChange(false)}>
            Cancel
          </Button>
          <Button size="sm" onClick={handleSave}>
            Save Variables
          </Button>
        </DialogFooter>
      </DialogContent>
    </Dialog>
  )
}
