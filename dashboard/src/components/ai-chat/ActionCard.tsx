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
import type {AiAction} from '@/lib/api'
import {Check, Pencil} from 'lucide-react'

interface ActionCardProps {
  action: AiAction
  onConfirm: () => void
}

export function ActionCard({action, onConfirm}: ActionCardProps) {
  const [confirmed, setConfirmed] = useState(false)
  const [loading, setLoading] = useState(false)

  const handleConfirm = async () => {
    setLoading(true)
    try {
      await onConfirm()
      setConfirmed(true)
    } finally {
      setLoading(false)
    }
  }

  return (
    <div className="ml-9 mt-2 border border-border rounded-lg p-3 bg-card text-sm">
      <div className="font-medium mb-1">{action.label}</div>
      <div className="text-xs text-muted-foreground mb-2 space-y-0.5">
        {Object.entries(action.params || {}).map(([key, value]) => (
          <div key={key}>
            <span className="font-mono text-muted-foreground/80">{key}:</span>{' '}
            <span>{String(value)}</span>
          </div>
        ))}
      </div>
      {!confirmed ? (
        <div className="flex gap-2">
          <button
            onClick={handleConfirm}
            disabled={loading}
            className="flex items-center gap-1 px-2.5 py-1 text-xs bg-primary text-primary-foreground rounded-md hover:bg-primary/90 disabled:opacity-50 transition-colors"
          >
            <Check className="h-3 w-3" />
            {loading ? 'Running...' : 'Confirm'}
          </button>
          <button className="flex items-center gap-1 px-2.5 py-1 text-xs border border-border rounded-md hover:bg-muted transition-colors">
            <Pencil className="h-3 w-3" />
            Edit
          </button>
        </div>
      ) : (
        <div className="text-xs text-green-600 flex items-center gap-1">
          <Check className="h-3 w-3" /> Executed
        </div>
      )}
    </div>
  )
}
