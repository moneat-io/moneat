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

import type {DragEvent, ReactNode} from 'react'
import {Bell, GitBranch, Hourglass, Mail, MessageSquare, Plus, Slack} from 'lucide-react'
import type {WorkflowCatalogResponse, WorkflowGraphNode} from '@/lib/api'
import {Button} from '@/components/ui/button'
import {
  defaultParamsForStep,
  workflowPaletteDragDataType,
  type WorkflowPaletteDragPayload,
} from './workflowGraph'

interface NodePaletteProps {
  catalog?: WorkflowCatalogResponse
  onAddNode: (node: Omit<WorkflowGraphNode, 'id'>, prefix: string) => void
}

type ControlKind = 'sleep' | 'wait_until' | 'for_each' | 'while'

export function NodePalette({catalog, onAddNode}: NodePaletteProps) {
  return (
    <div className="flex h-[560px] min-w-0 flex-col gap-3 overflow-hidden rounded-md border bg-muted/20 p-3 xl:h-[680px]">
      <div className="shrink-0">
        <h3 className="text-sm font-semibold">Node palette</h3>
        <p className="text-xs text-muted-foreground">Add branches, waits, and workflow actions.</p>
      </div>
      <div className="grid shrink-0 gap-2">
        <PaletteNodeButton
          icon={<GitBranch className="h-4 w-4" />}
          label="If / else"
          node={conditionNode('if')}
          prefix="condition"
          onAddNode={onAddNode}
        />
        <PaletteNodeButton
          icon={<GitBranch className="h-4 w-4" />}
          label="Switch"
          node={conditionNode('switch')}
          prefix="switch"
          onAddNode={onAddNode}
        />
        <PaletteNodeButton
          icon={<Hourglass className="h-4 w-4" />}
          label="Sleep"
          node={controlNode('sleep')}
          prefix="sleep"
          onAddNode={onAddNode}
        />
        <PaletteNodeButton
          icon={<Hourglass className="h-4 w-4" />}
          label="Wait until"
          node={controlNode('wait_until')}
          prefix="wait"
          onAddNode={onAddNode}
        />
        <PaletteNodeButton
          icon={<Hourglass className="h-4 w-4" />}
          label="For each"
          node={controlNode('for_each')}
          prefix="loop"
          onAddNode={onAddNode}
        />
        <PaletteNodeButton
          icon={<Hourglass className="h-4 w-4" />}
          label="While"
          node={controlNode('while')}
          prefix="while"
          onAddNode={onAddNode}
        />
      </div>
      <div className="flex min-h-0 flex-1 flex-col gap-2">
        <p className="text-xs font-semibold uppercase text-muted-foreground">Actions</p>
        <div className="grid min-h-0 flex-1 gap-2 overflow-y-auto pr-1">
          {catalog?.steps.map((step) => {
            const node: Omit<WorkflowGraphNode, 'id'> = {
              type: 'action',
              action: step.name,
              params: defaultParamsForStep(step),
              conditions: [],
              cases: [],
              continue_on_error: false,
            }
            return (
              <PaletteNodeButton
                key={step.name}
                icon={stepIcon(step.name)}
                label={step.label}
                node={node}
                prefix="action"
                onAddNode={onAddNode}
              />
            )
          })}
        </div>
      </div>
    </div>
  )
}

function PaletteNodeButton({
  icon,
  label,
  node,
  prefix,
  onAddNode,
}: {
  icon: ReactNode
  label: string
  node: Omit<WorkflowGraphNode, 'id'>
  prefix: string
  onAddNode: (node: Omit<WorkflowGraphNode, 'id'>, prefix: string) => void
}) {
  return (
    <PaletteButton
      icon={icon}
      label={label}
      dragPayload={{node, prefix}}
      onClick={() => onAddNode(node, prefix)}
    />
  )
}

function PaletteButton({
  icon,
  label,
  dragPayload,
  onClick,
}: {
  icon: ReactNode
  label: string
  dragPayload: WorkflowPaletteDragPayload
  onClick: () => void
}) {
  const handleDragStart = (event: DragEvent<HTMLButtonElement>) => {
    event.dataTransfer.effectAllowed = 'copy'
    event.dataTransfer.setData(workflowPaletteDragDataType, JSON.stringify(dragPayload))
  }

  return (
    <Button
      type="button"
      variant="outline"
      size="sm"
      draggable
      onClick={onClick}
      onDragStart={handleDragStart}
      className="h-9 w-full min-w-0 cursor-grab justify-start gap-2 active:cursor-grabbing"
    >
      <span className="shrink-0">{icon}</span>
      <span className="min-w-0 truncate">{label}</span>
      <Plus className="ml-auto h-3.5 w-3.5" />
    </Button>
  )
}

function conditionNode(kind: 'if' | 'switch'): Omit<WorkflowGraphNode, 'id'> {
  return {
    type: 'condition',
    kind,
    params: {},
    conditions: [],
    cases: kind === 'switch' ? [{name: 'case-1', label: 'Case 1', conditions: []}] : [],
  }
}

function controlNode(kind: ControlKind): Omit<WorkflowGraphNode, 'id'> {
  return {
    type: 'control',
    kind,
    params: controlParams(kind),
    conditions: [],
    cases: [],
  }
}

function controlParams(kind: ControlKind): Record<string, string> {
  if (kind === 'sleep') return {duration: 'PT5M'}
  if (kind === 'wait_until') return {timeout: 'PT30M'}
  if (kind === 'for_each') return {items_reference: '', item_variable: 'item', max_items: '100'}
  return {max_iterations: '100'}
}

function stepIcon(stepName: string) {
  const className = 'h-4 w-4'
  if (stepName.includes('email')) return <Mail className={className} />
  if (stepName.includes('slack')) return <Slack className={className} />
  if (stepName.includes('discord')) return <Bell className={className} />
  return <MessageSquare className={className} />
}
