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

import { useState } from 'react'
import {
  DndContext,
  closestCenter,
  KeyboardSensor,
  PointerSensor,
  useSensor,
  useSensors,
  DragEndEvent,
} from '@dnd-kit/core'
import {
  arrayMove,
  SortableContext,
  sortableKeyboardCoordinates,
  verticalListSortingStrategy,
  useSortable,
} from '@dnd-kit/sortable'
import { CSS } from '@dnd-kit/utilities'
import { Button } from '@/components/ui/button'
import { Card, CardContent, CardHeader, CardTitle } from '@/components/ui/card'
import { Input } from '@/components/ui/input'
import { Label } from '@/components/ui/label'
import {
  Select,
  SelectContent,
  SelectItem,
  SelectTrigger,
  SelectValue,
} from '@/components/ui/select'
import { Badge } from '@/components/ui/badge'
import { GripVertical, Trash2, Plus, Clock, User, Users } from 'lucide-react'
import { cn } from '@/lib/utils'

export interface EscalationStep {
  id: string
  stepOrder: number
  timeoutMinutes: number
  smsFallbackDelayMinutes: number
  targets: EscalationTarget[]
}

export interface EscalationTarget {
  id: string
  targetType: 'USER' | 'ON_CALL_SCHEDULE'
  targetId: number
  targetName: string
}

export interface EscalationPolicyData {
  name: string
  description: string
  repeatCount: number
  steps: EscalationStep[]
}

interface EscalationUser {
  id: number
  name: string
}

interface Schedule {
  id: number
  name: string
}

interface EscalationPolicyEditorProps {
  initialData?: EscalationPolicyData
  users: EscalationUser[]
  schedules: Schedule[]
  onSave: (data: EscalationPolicyData) => void
  onCancel: () => void
}

interface SortableStepProps {
  step: EscalationStep
  index: number
  users: EscalationUser[]
  schedules: Schedule[]
  onUpdate: (step: EscalationStep) => void
  onRemove: () => void
}

function SortableStep({ step, index, users, schedules, onUpdate, onRemove }: SortableStepProps) {
  const { attributes, listeners, setNodeRef, transform, transition, isDragging } = useSortable({
    id: step.id,
  })

  const style = {
    transform: CSS.Transform.toString(transform),
    transition,
  }

  const addTarget = (type: 'USER' | 'ON_CALL_SCHEDULE', id: number) => {
    const source = type === 'USER' ? users : schedules
    const target = source.find((item) => item.id === id)
    if (!target) return

    const newTarget: EscalationTarget = {
      id: `${type}_${id}_${Date.now()}`,
      targetType: type,
      targetId: id,
      targetName: target.name,
    }

    onUpdate({
      ...step,
      targets: [...step.targets, newTarget],
    })
  }

  const removeTarget = (targetId: string) => {
    onUpdate({
      ...step,
      targets: step.targets.filter((t) => t.id !== targetId),
    })
  }

  return (
    <div
      ref={setNodeRef}
      style={style}
      className={cn(
        'relative',
        isDragging && 'opacity-50 z-50'
      )}
    >
      <Card>
        <CardHeader className="pb-3">
          <div className="flex items-center justify-between">
            <CardTitle className="text-base flex items-center gap-2">
              <button
                className="cursor-grab active:cursor-grabbing text-muted-foreground hover:text-foreground"
                {...attributes}
                {...listeners}
              >
                <GripVertical className="h-5 w-5" />
              </button>
              <span>Step {index + 1}</span>
              <Badge variant="outline" className="ml-2">
                <Clock className="h-3 w-3 mr-1" />
                {step.timeoutMinutes}min
              </Badge>
            </CardTitle>
            <Button variant="ghost" size="sm" onClick={onRemove}>
              <Trash2 className="h-4 w-4" />
            </Button>
          </div>
        </CardHeader>
        <CardContent className="space-y-4">
          <div className="space-y-2">
            <Label>Notify</Label>
            <div className="flex flex-wrap gap-2">
              {step.targets.map((target) => (
                <Badge key={target.id} variant="secondary" className="gap-1">
                  {target.targetType === 'USER' ? (
                    <User className="h-3 w-3" />
                  ) : (
                    <Users className="h-3 w-3" />
                  )}
                  {target.targetName}
                  <button
                    onClick={() => removeTarget(target.id)}
                    className="ml-1 hover:text-destructive"
                  >
                    ×
                  </button>
                </Badge>
              ))}
              {step.targets.length === 0 && (
                <span className="text-sm text-muted-foreground">No targets added</span>
              )}
            </div>
          </div>

          <div className="grid grid-cols-2 gap-2">
            <Select onValueChange={(value) => addTarget('USER', parseInt(value))}>
              <SelectTrigger>
                <SelectValue placeholder="Add user" />
              </SelectTrigger>
              <SelectContent>
                {users.map((user) => (
                  <SelectItem key={user.id} value={user.id.toString()}>
                    {user.name}
                  </SelectItem>
                ))}
              </SelectContent>
            </Select>

            <Select onValueChange={(value) => addTarget('ON_CALL_SCHEDULE', parseInt(value))}>
              <SelectTrigger>
                <SelectValue placeholder="Add schedule" />
              </SelectTrigger>
              <SelectContent>
                {schedules.map((schedule) => (
                  <SelectItem key={schedule.id} value={schedule.id.toString()}>
                    {schedule.name}
                  </SelectItem>
                ))}
              </SelectContent>
            </Select>
          </div>

          <div className="space-y-2">
            <Label>Wait time before next step</Label>
            <div className="flex items-center gap-2">
              <Input
                type="number"
                min={1}
                max={1440}
                value={step.timeoutMinutes}
                onChange={(e) => onUpdate({ ...step, timeoutMinutes: parseInt(e.target.value) || 5 })}
                className="w-24"
              />
              <span className="text-sm text-muted-foreground">minutes</span>
            </div>
          </div>

          <div className="space-y-2">
            <Label>SMS/Call fallback delay</Label>
            <div className="flex items-center gap-2">
              <Input
                type="number"
                min={0}
                max={1440}
                value={step.smsFallbackDelayMinutes}
                onChange={(e) => onUpdate({ ...step, smsFallbackDelayMinutes: parseInt(e.target.value) || 0 })}
                className="w-24"
              />
              <span className="text-sm text-muted-foreground">minutes (0 = disabled)</span>
            </div>
            <p className="text-xs text-muted-foreground">
              If unacknowledged after this delay, Moneat will call and text the on-call user.
            </p>
          </div>
        </CardContent>
      </Card>
    </div>
  )
}

export function EscalationPolicyEditor({
  initialData,
  users,
  schedules,
  onSave,
  onCancel,
}: EscalationPolicyEditorProps) {
  const [policy, setPolicy] = useState<EscalationPolicyData>(
    initialData || {
      name: '',
      description: '',
      repeatCount: 1,
      steps: [],
    }
  )

  const sensors = useSensors(
    useSensor(PointerSensor),
    useSensor(KeyboardSensor, {
      coordinateGetter: sortableKeyboardCoordinates,
    })
  )

  const handleDragEnd = (event: DragEndEvent) => {
    const { active, over } = event

    if (over && active.id !== over.id) {
      setPolicy((prev) => {
        const oldIndex = prev.steps.findIndex((s) => s.id === active.id)
        const newIndex = prev.steps.findIndex((s) => s.id === over.id)
        const reordered = arrayMove(prev.steps, oldIndex, newIndex)
        return {
          ...prev,
          steps: reordered.map((step, idx) => ({ ...step, stepOrder: idx })),
        }
      })
    }
  }

  const addStep = () => {
    const newStep: EscalationStep = {
      id: `step_${Date.now()}`,
      stepOrder: policy.steps.length,
      timeoutMinutes: 5,
      smsFallbackDelayMinutes: 2,
      targets: [],
    }
    setPolicy((prev) => ({
      ...prev,
      steps: [...prev.steps, newStep],
    }))
  }

  const updateStep = (index: number, updatedStep: EscalationStep) => {
    setPolicy((prev) => ({
      ...prev,
      steps: prev.steps.map((step, idx) => (idx === index ? updatedStep : step)),
    }))
  }

  const removeStep = (index: number) => {
    setPolicy((prev) => ({
      ...prev,
      steps: prev.steps.filter((_, idx) => idx !== index).map((step, idx) => ({ ...step, stepOrder: idx })),
    }))
  }

  const handleSave = () => {
    if (!policy.name.trim()) {
      alert('Please enter a policy name')
      return
    }
    if (policy.steps.length === 0) {
      alert('Please add at least one escalation step')
      return
    }
    if (policy.steps.some((step) => step.targets.length === 0)) {
      alert('All steps must have at least one target')
      return
    }
    onSave(policy)
  }

  return (
    <div className="space-y-6">
      <div className="space-y-4">
        <div className="space-y-2">
          <Label htmlFor="policy-name">Policy Name</Label>
          <Input
            id="policy-name"
            value={policy.name}
            onChange={(e) => setPolicy({ ...policy, name: e.target.value })}
            placeholder="e.g., Production Incidents"
          />
        </div>

        <div className="space-y-2">
          <Label htmlFor="policy-description">Description (optional)</Label>
          <Input
            id="policy-description"
            value={policy.description}
            onChange={(e) => setPolicy({ ...policy, description: e.target.value })}
            placeholder="When and how this policy is used"
          />
        </div>

        <div className="space-y-2">
          <Label htmlFor="repeat-count">Repeat Count</Label>
          <div className="flex items-center gap-2">
            <Input
              id="repeat-count"
              type="number"
              min={0}
              max={10}
              value={policy.repeatCount}
              onChange={(e) => setPolicy({ ...policy, repeatCount: parseInt(e.target.value) || 1 })}
              className="w-24"
            />
            <span className="text-sm text-muted-foreground">
              (how many times to loop through all steps)
            </span>
          </div>
        </div>
      </div>

      <div className="space-y-4">
        <div className="flex items-center justify-between">
          <h3 className="text-lg font-medium">Escalation Steps</h3>
          <Button onClick={addStep} size="sm">
            <Plus className="h-4 w-4 mr-2" />
            Add Step
          </Button>
        </div>

        <DndContext sensors={sensors} collisionDetection={closestCenter} onDragEnd={handleDragEnd}>
          <SortableContext items={policy.steps.map((s) => s.id)} strategy={verticalListSortingStrategy}>
            <div className="space-y-4">
              {policy.steps.map((step, index) => (
                <SortableStep
                  key={step.id}
                  step={step}
                  index={index}
                  users={users}
                  schedules={schedules}
                  onUpdate={(updatedStep) => updateStep(index, updatedStep)}
                  onRemove={() => removeStep(index)}
                />
              ))}
            </div>
          </SortableContext>
        </DndContext>

        {policy.steps.length === 0 && (
          <Card className="border-dashed">
            <CardContent className="flex flex-col items-center justify-center py-12">
              <Clock className="h-12 w-12 text-muted-foreground mb-4" />
              <p className="text-sm text-muted-foreground text-center mb-4">
                No escalation steps yet. Add steps to define who gets notified and when.
              </p>
              <Button onClick={addStep} variant="outline">
                <Plus className="h-4 w-4 mr-2" />
                Add First Step
              </Button>
            </CardContent>
          </Card>
        )}
      </div>

      <div className="flex justify-end gap-2 border-t pt-4">
        <Button variant="outline" onClick={onCancel}>
          Cancel
        </Button>
        <Button onClick={handleSave}>Save Policy</Button>
      </div>
    </div>
  )
}
