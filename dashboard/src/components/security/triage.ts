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

import type {ArchiveReason, SignalStatus, TriageRequest} from '@/lib/api'

// Mirror of the backend triage state machine (SignalTriageStateMachine.kt). Kept pure so the UI
// only offers transitions the server will accept, and so it can be unit-tested in isolation.
//
// Allowed status moves:
//   open          -> under_review
//   open          -> archived{reason}
//   under_review  -> archived{reason}
//   archived      -> open            (reopen; clears the reason)

export const ARCHIVE_REQUIRES_REASON_MESSAGE = 'Select an archive reason'

export interface StatusAction {
  to: SignalStatus
  label: string
  // True when the move into `to` requires an archive reason.
  requiresReason: boolean
}

const STATUS_ACTIONS: Record<SignalStatus, StatusAction[]> = {
  open: [
    {to: 'under_review', label: 'Start review', requiresReason: false},
    {to: 'archived', label: 'Archive', requiresReason: true},
  ],
  under_review: [{to: 'archived', label: 'Archive', requiresReason: true}],
  archived: [{to: 'open', label: 'Reopen', requiresReason: false}],
}

/** Status moves available from `current`, in display order. */
export function availableStatusActions(current: SignalStatus): StatusAction[] {
  return STATUS_ACTIONS[current] ?? []
}

/** Whether moving from `current` into `target` is a permitted status transition. */
export function isTransitionAllowed(current: SignalStatus, target: SignalStatus): boolean {
  if (current === target) return false
  return availableStatusActions(current).some((a) => a.to === target)
}

/** Archive is the only transition that needs a reason. */
export function transitionRequiresReason(target: SignalStatus): boolean {
  return target === 'archived'
}

export type BuildTriageResult =
  | {ok: true; request: TriageRequest}
  | {ok: false; error: string}

export interface StatusChangeInput {
  current: SignalStatus
  target: SignalStatus
  reason?: ArchiveReason | null
  note?: string
}

/**
 * Builds the PATCH body for a status change, enforcing the archive-reason requirement before any
 * request is sent. Returns the minimal body (only the fields the move needs).
 */
export function buildStatusChangeRequest(input: StatusChangeInput): BuildTriageResult {
  const {current, target, reason, note} = input
  if (!isTransitionAllowed(current, target)) {
    return {ok: false, error: `Cannot move signal from ${current} to ${target}`}
  }
  const request: TriageRequest = {status: target}
  if (transitionRequiresReason(target)) {
    if (!reason) return {ok: false, error: ARCHIVE_REQUIRES_REASON_MESSAGE}
    request.reason = reason
  }
  const trimmedNote = note?.trim()
  if (trimmedNote) request.note = trimmedNote
  return {ok: true, request}
}
