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
// MERCHANTABILITY or FITNESS FOR A PARTICULAR PURPOSE.
// See the GNU Affero General Public License for more details.
//
// You should have received a copy of the GNU Affero General Public License
// along with this program. If not, see <https://www.gnu.org/licenses/>.

import {AlertTriangle} from 'lucide-react'
import type {AiPalettePendingConfirmation} from '@/contexts/CommandPaletteContext'

interface ConfirmationCardProps {
  confirmation: AiPalettePendingConfirmation
  loading: boolean
  onApprove: () => void
  onDeny: () => void
}

export function ConfirmationCard({
  confirmation,
  loading,
  onApprove,
  onDeny,
}: ConfirmationCardProps) {
  const argPreview = JSON.stringify(confirmation.args, null, 2)

  return (
    <div className="rounded-md border border-amber-500/50 bg-amber-500/10 px-3 py-2">
      <div className="flex items-center gap-2 text-xs font-medium">
        <AlertTriangle className="h-3.5 w-3.5 text-amber-700" />
        Confirmation required for `{confirmation.tool}`
      </div>
      <pre className="mt-2 overflow-x-auto rounded bg-background/70 p-2 text-[11px] text-muted-foreground">
        {argPreview}
      </pre>
      <div className="mt-2 flex items-center gap-2">
        <button
          type="button"
          onClick={onApprove}
          disabled={loading}
          className="rounded bg-primary px-2.5 py-1 text-xs text-primary-foreground disabled:opacity-60"
        >
          Approve
        </button>
        <button
          type="button"
          onClick={onDeny}
          disabled={loading}
          className="rounded border border-border px-2.5 py-1 text-xs hover:bg-muted disabled:opacity-60"
        >
          Deny
        </button>
      </div>
    </div>
  )
}
