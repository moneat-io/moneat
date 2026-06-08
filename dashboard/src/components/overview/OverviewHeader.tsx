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

import {Button} from '@/components/ui/button'
import {PageHeader} from '@/components/ui/page-header'
import {Check, Pencil, Plus, RotateCcw} from 'lucide-react'

interface OverviewHeaderProps {
  isEditing: boolean
  onToggleEdit: () => void
  onAddWidget: () => void
  onReset: () => void
}

export function OverviewHeader({isEditing, onToggleEdit, onAddWidget, onReset}: OverviewHeaderProps) {
  return (
    <PageHeader
      className="mb-3"
      eyebrow="Workspace overview"
      title="Overview"
      actions={
        isEditing ? (
          <>
            <Button variant="outline" size="sm" className="h-7 gap-1.5 text-xs" onClick={onReset}>
              <RotateCcw className="h-3 w-3" />
              Reset
            </Button>
            <Button variant="outline" size="sm" className="h-7 gap-1.5 text-xs" onClick={onAddWidget}>
              <Plus className="h-3 w-3" />
              Add widget
            </Button>
            <Button size="sm" className="h-7 gap-1.5 text-xs" onClick={onToggleEdit}>
              <Check className="h-3 w-3" />
              Done
            </Button>
          </>
        ) : (
          <Button variant="outline" size="sm" className="h-7 gap-1.5 text-xs" onClick={onToggleEdit}>
            <Pencil className="h-3 w-3" />
            Customize
          </Button>
        )
      }
    />
  )
}
