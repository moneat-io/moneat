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

import {
  Dialog,
  DialogContent,
  DialogDescription,
  DialogHeader,
  DialogTitle,
} from '@/components/ui/dialog'
import {OVERVIEW_WIDGETS} from './overviewWidgetTypes'

type AddOverviewWidgetDialogProps = Readonly<{
  open: boolean
  onOpenChange: (open: boolean) => void
  onAdd: (widgetType: string) => void
}>

export function AddOverviewWidgetDialog({open, onOpenChange, onAdd}: AddOverviewWidgetDialogProps) {
  return (
    <Dialog open={open} onOpenChange={onOpenChange}>
      <DialogContent className="sm:max-w-lg">
        <DialogHeader>
          <DialogTitle>Add widget</DialogTitle>
          <DialogDescription>Choose a widget to add to your overview.</DialogDescription>
        </DialogHeader>
        <div className="grid grid-cols-2 gap-2 pt-2">
          {Object.entries(OVERVIEW_WIDGETS).map(([type, def]) => {
            const Icon = def.icon
            return (
              <button
                key={type}
                type="button"
                onClick={() => {
                  onAdd(type)
                  onOpenChange(false)
                }}
                className="flex items-start gap-3 rounded-md border p-3 text-left transition hover:bg-muted/50"
              >
                <span className="mt-0.5 flex h-7 w-7 shrink-0 items-center justify-center rounded-md bg-muted">
                  <Icon className="h-3.5 w-3.5 text-muted-foreground" />
                </span>
                <div className="min-w-0">
                  <p className="text-sm font-medium">{def.label}</p>
                  <p className="text-xs text-muted-foreground leading-snug">{def.description}</p>
                </div>
              </button>
            )
          })}
        </div>
      </DialogContent>
    </Dialog>
  )
}
