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
import type {ArchiveReason, SignalResponse, TriageRequest} from '@/lib/api'
import {Button} from '@/components/ui/button'
import {Textarea} from '@/components/ui/textarea'
import {Label} from '@/components/ui/label'
import {ARCHIVE_REASONS} from './securityChips'
import {availableStatusActions, buildStatusChangeRequest, type StatusAction} from './triage'

interface SignalTriagePanelProps {
  signal: SignalResponse
  // Resolves once the PATCH succeeds (or rejects on error). The parent owns the request.
  onTriage: (request: TriageRequest) => Promise<unknown>
  isSubmitting?: boolean
}

export function SignalTriagePanel({signal, onTriage, isSubmitting}: SignalTriagePanelProps) {
  const status = signal.status as SignalResponse['status']
  const actions = availableStatusActions(status as never)
  const [pending, setPending] = useState<StatusAction | null>(null)
  const [reason, setReason] = useState<ArchiveReason | ''>('')
  const [note, setNote] = useState('')
  const [error, setError] = useState<string | null>(null)

  const submit = (action: StatusAction) => {
    if (action.requiresReason && pending?.to !== action.to) {
      // First click on a reason-requiring action expands the reason picker.
      setPending(action)
      setError(null)
      return
    }
    const built = buildStatusChangeRequest({
      current: status as never,
      target: action.to,
      reason: reason || null,
      note,
    })
    if (!built.ok) {
      setError(built.error)
      return
    }
    setError(null)
    void onTriage(built.request).then(() => {
      setPending(null)
      setReason('')
      setNote('')
    })
  }

  const saveNote = () => {
    const trimmed = note.trim()
    if (!trimmed) return
    void onTriage({note: trimmed}).then(() => setNote(''))
  }

  return (
    <div className="space-y-3">
      <div className="space-y-1.5">
        <Label className="text-[10px] uppercase tracking-wide text-muted-foreground">Triage</Label>
        <div className="flex flex-wrap gap-1.5">
          {actions.map((action) => (
            <Button
              key={action.to}
              size="sm"
              variant={action.to === 'archived' ? 'outline' : 'default'}
              disabled={isSubmitting}
              onClick={() => submit(action)}>
              {action.label}
            </Button>
          ))}
          {actions.length === 0 && (
            <span className="text-xs text-muted-foreground">No further transitions</span>
          )}
        </div>
      </div>

      {pending?.requiresReason && (
        <div className="space-y-1.5 rounded-md border border-border bg-muted/30 p-2">
          <Label htmlFor="archive-reason" className="text-[10px] uppercase tracking-wide text-muted-foreground">
            Archive reason
          </Label>
          <select
            id="archive-reason"
            aria-label="Archive reason"
            value={reason}
            onChange={(e) => setReason(e.target.value as ArchiveReason)}
            className="h-8 w-full rounded-md border border-input bg-background px-2 text-xs">
            <option value="">Select a reason…</option>
            {ARCHIVE_REASONS.map((r) => (
              <option key={r.value} value={r.value}>
                {r.label}
              </option>
            ))}
          </select>
          <Button size="sm" disabled={isSubmitting} onClick={() => submit(pending)}>
            Confirm archive
          </Button>
        </div>
      )}

      <div className="space-y-1.5">
        <Label htmlFor="triage-note" className="text-[10px] uppercase tracking-wide text-muted-foreground">
          Note
        </Label>
        <Textarea
          id="triage-note"
          value={note}
          onChange={(e) => setNote(e.target.value)}
          rows={2}
          placeholder="Add an investigation note…"
          className="text-xs"
        />
        <Button size="sm" variant="outline" disabled={isSubmitting || !note.trim()} onClick={saveNote}>
          Add note
        </Button>
      </div>

      {error && <p className="text-xs text-destructive">{error}</p>}
    </div>
  )
}
