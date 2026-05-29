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

import type {ReactNode} from 'react'
import {Bell, GitBranch, Hourglass, Mail, MessageSquare, Plus, Slack} from 'lucide-react'
import type {WorkflowCatalogResponse, WorkflowGraphNode} from '@/lib/api'
import {Button} from '@/components/ui/button'
import {defaultParamsForStep} from './workflowGraph'

interface NodePaletteProps {
  catalog?: WorkflowCatalogResponse
  onAddNode: (node: Omit<WorkflowGraphNode, 'id'>, prefix: string) => void
}

type ControlKind = 'sleep' | 'wait_until' | 'for_each' | 'while'

export function NodePalette({catalog, onAddNode}: NodePaletteProps) {
  return (
    <div className="space-y-3 rounded-md border bg-muted/20 p-3">
      <div>
        <h3 className="text-sm font-semibold">Node palette</h3>
        <p className="text-xs text-muted-foreground">Add branches, waits, and notification actions.</p>
      </div>
      <div className="grid gap-2">
        <PaletteButton
          icon={<GitBranch className="h-4 w-4" />}
          label="If / else"
          onClick={() => onAddNode(conditionNode('if'), 'condition')}
        />
        <PaletteButton
          icon={<GitBranch className="h-4 w-4" />}
          label="Switch"
          onClick={() => onAddNode(conditionNode('switch'), 'switch')}
        />
        <PaletteButton
          icon={<Hourglass className="h-4 w-4" />}
          label="Sleep"
          onClick={() => onAddNode(controlNode('sleep'), 'sleep')}
        />
        <PaletteButton
          icon={<Hourglass className="h-4 w-4" />}
          label="Wait until"
          onClick={() => onAddNode(controlNode('wait_until'), 'wait')}
        />
        <PaletteButton
          icon={<Hourglass className="h-4 w-4" />}
          label="For each"
          onClick={() => onAddNode(controlNode('for_each'), 'loop')}
        />
        <PaletteButton
          icon={<Hourglass className="h-4 w-4" />}
          label="While"
          onClick={() => onAddNode(controlNode('while'), 'while')}
        />
      </div>
      <div className="space-y-2">
        <p className="text-xs font-semibold uppercase text-muted-foreground">Actions</p>
        {catalog?.steps.map((step) => (
          <PaletteButton
            key={step.name}
            icon={stepIcon(step.name)}
            label={step.label}
            onClick={() => onAddNode({
              type: 'action',
              action: step.name,
              params: defaultParamsForStep(step),
              conditions: [],
              cases: [],
              continue_on_error: false,
            }, 'action')}
          />
        ))}
      </div>
    </div>
  )
}

function PaletteButton({
  icon,
  label,
  onClick,
}: {
  icon: ReactNode
  label: string
  onClick: () => void
}) {
  return (
    <Button type="button" variant="outline" size="sm" onClick={onClick} className="h-9 justify-start gap-2">
      {icon}
      <span className="truncate">{label}</span>
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
