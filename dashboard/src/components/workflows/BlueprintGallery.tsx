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

import {Loader2, Sparkles} from 'lucide-react'
import type {WorkflowBlueprintSummary} from '@/lib/api'
import {Badge} from '@/components/ui/badge'
import {Button} from '@/components/ui/button'

interface BlueprintGalleryProps {
  blueprints: WorkflowBlueprintSummary[]
  onUseBlueprint: (blueprint: WorkflowBlueprintSummary) => void
  pendingKey?: string | null
  disabled?: boolean
}

const UNCATEGORIZED = 'Other'

function groupByCategory(
  blueprints: WorkflowBlueprintSummary[]
): Array<[string, WorkflowBlueprintSummary[]]> {
  const groups = new Map<string, WorkflowBlueprintSummary[]>()
  for (const blueprint of blueprints) {
    const category = blueprint.category || UNCATEGORIZED
    const existing = groups.get(category)
    if (existing) {
      existing.push(blueprint)
    } else {
      groups.set(category, [blueprint])
    }
  }
  return Array.from(groups.entries()).sort((a, b) => a[0].localeCompare(b[0]))
}

export function BlueprintGallery({
  blueprints,
  onUseBlueprint,
  pendingKey = null,
  disabled = false,
}: BlueprintGalleryProps) {
  if (blueprints.length === 0) {
    return (
      <div className="rounded-md border border-dashed bg-background p-6 text-sm text-muted-foreground">
        No blueprints available yet.
      </div>
    )
  }
  const grouped = groupByCategory(blueprints)
  return (
    <div className="space-y-5">
      {grouped.map(([category, items]) => (
        <section key={category} className="space-y-2">
          <h3 className="text-sm font-semibold capitalize">{category}</h3>
          <div className="grid gap-3 sm:grid-cols-2 xl:grid-cols-3">
            {items.map((blueprint) => (
              <BlueprintCard
                key={blueprint.key}
                blueprint={blueprint}
                pending={pendingKey === blueprint.key}
                disabled={disabled}
                onUse={() => onUseBlueprint(blueprint)}
              />
            ))}
          </div>
        </section>
      ))}
    </div>
  )
}

function BlueprintCard({
  blueprint,
  pending,
  disabled,
  onUse,
}: {
  blueprint: WorkflowBlueprintSummary
  pending: boolean
  disabled: boolean
  onUse: () => void
}) {
  return (
    <div className="flex h-full flex-col rounded-md border bg-background p-3">
      <div className="flex items-start gap-2">
        <div className="flex h-7 w-7 shrink-0 items-center justify-center rounded-md bg-primary/10 text-primary">
          <Sparkles className="h-3.5 w-3.5" />
        </div>
        <div className="min-w-0">
          <p className="truncate text-sm font-semibold">{blueprint.name}</p>
          <p className="mt-0.5 line-clamp-2 text-xs text-muted-foreground">{blueprint.description}</p>
        </div>
      </div>
      {blueprint.tags.length > 0 && (
        <div className="mt-2 flex flex-wrap gap-1">
          {blueprint.tags.map((tag) => (
            <Badge key={tag} variant="outline" className="text-[11px]">
              {tag}
            </Badge>
          ))}
        </div>
      )}
      <Button
        size="sm"
        className="mt-3 gap-1.5 self-start"
        disabled={pending || disabled}
        onClick={onUse}
      >
        {pending ? <Loader2 className="h-3.5 w-3.5 animate-spin" /> : null}
        Use blueprint
      </Button>
    </div>
  )
}
