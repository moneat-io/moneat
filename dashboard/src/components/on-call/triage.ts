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

// Pure, source-neutral helpers for incident triage: which lifecycle actions a
// status permits, which incidents are real merge targets, and how to turn action
// inputs into wire payloads. These mirror the backend `IncidentCommandService`
// transition rules so the dashboard only ever offers actions the backend accepts,
// and stay free of JSX/React so the rules can be unit-tested in isolation.

import type {
  AcceptIncidentInput,
  DeclineIncidentInput,
  MergeIncidentInput,
  OnCallIncident,
  OnCallIncidentStatus,
} from '@/lib/api/types'

export const TRIAGE_STATUS: OnCallIncidentStatus = 'TRIAGE'

// A source incident can only be folded away when its status allows a MERGED
// transition. Mirrors the backend `allowedTransitions` entries that include
// MERGED (TRIAGE, ACTIVE).
const MERGE_SOURCE_STATUSES: ReadonlySet<OnCallIncidentStatus> = new Set(['TRIAGE', 'ACTIVE'])

// An incident may survive a merge (be a target) only in these statuses. Mirrors
// the backend `MERGE_TARGET_STATUSES`.
const MERGE_TARGET_STATUSES: ReadonlySet<OnCallIncidentStatus> = new Set([
  'TRIAGE',
  'ACTIVE',
  'RESOLVED',
  'POST_INCIDENT',
  'CLOSED',
])

/** Whether an incident is awaiting triage classification. */
export function isTriageIncident(status: string): boolean {
  return status === TRIAGE_STATUS
}

/** Accept classifies a triage incident into the active response (TRIAGE → ACTIVE). */
export function canAcceptIncident(status: string): boolean {
  return status === 'TRIAGE'
}

/** Decline dismisses a triage incident (TRIAGE → DECLINED). */
export function canDeclineIncident(status: string): boolean {
  return status === 'TRIAGE'
}

/** Merge folds an incident away into another; only permitted from these statuses. */
export function canMergeIncident(status: string): boolean {
  return MERGE_SOURCE_STATUSES.has(status as OnCallIncidentStatus)
}

/**
 * Real, selectable incidents that `source` can be folded into: every other
 * incident whose status the backend accepts as a merge survivor. Never returns
 * the source itself, so the merge target is always a concrete eligible incident
 * rather than a placeholder.
 */
export function eligibleMergeTargets(
  incidents: readonly OnCallIncident[],
  sourceId: string,
): OnCallIncident[] {
  return incidents.filter(
    (incident) =>
      incident.id !== sourceId &&
      MERGE_TARGET_STATUSES.has(incident.status),
  )
}

/** Normalize the accept dialog inputs into the wire payload, dropping blanks. */
export function buildAcceptInput(params: {
  severity?: string
  incidentTypeId?: string
  expectedVersion?: number
}): AcceptIncidentInput {
  const input: AcceptIncidentInput = {}
  const severity = params.severity?.trim()
  if (severity) input.severity = severity
  const incidentTypeId = params.incidentTypeId?.trim()
  if (incidentTypeId) input.incidentTypeId = incidentTypeId
  if (params.expectedVersion !== undefined) input.expectedVersion = params.expectedVersion
  return input
}

/** Normalize the decline dialog inputs into the wire payload, dropping a blank reason. */
export function buildDeclineInput(params: {
  reason?: string
  expectedVersion?: number
}): DeclineIncidentInput {
  const input: DeclineIncidentInput = {}
  const reason = params.reason?.trim()
  if (reason) input.reason = reason
  if (params.expectedVersion !== undefined) input.expectedVersion = params.expectedVersion
  return input
}

/**
 * Build the merge command body for folding `source` into `target`. The command
 * is addressed to the surviving `target` incident, so the optimistic-concurrency
 * `expectedVersion` guards the target; `source` is the incident marked merged.
 */
export function buildMergeInput(
  target: Pick<OnCallIncident, 'version'>,
  source: Pick<OnCallIncident, 'id'>,
): MergeIncidentInput {
  const input: MergeIncidentInput = {sourceIncidentId: source.id}
  if (target.version !== undefined) input.expectedVersion = target.version
  return input
}

export const INCIDENT_CONFLICT_STATUS = 409

/** The HTTP status the API client attached to a thrown request error, if any. */
export function incidentErrorStatus(error: unknown): number | undefined {
  if (typeof error === 'object' && error !== null) {
    const status = (error as {status?: unknown}).status
    if (typeof status === 'number') return status
  }
  return undefined
}

export interface IncidentActionError {
  /** Human, actionable message — the backend's own message is preserved verbatim. */
  message: string
  /** True for a 409, so the caller can refresh state and invite a retry. */
  isConflict: boolean
}

/**
 * Turn a failed triage action into an actionable message. The backend returns
 * specific, source-neutral messages for both validation (400, e.g. "Incident
 * severity is required…") and conflicts (409, e.g. "Incident is already merged
 * into another incident" or a stale-version message), so those are surfaced
 * verbatim; only an opaque/network failure falls back to generic copy.
 */
export function describeIncidentActionError(error: unknown): IncidentActionError {
  const status = incidentErrorStatus(error)
  const isConflict = status === INCIDENT_CONFLICT_STATUS
  const raw = error instanceof Error ? error.message.trim() : ''
  if (raw === '' || raw === 'NETWORK_ERROR') {
    return {
      isConflict,
      message: isConflict
        ? 'This incident changed since you loaded it. It has been refreshed — review and try again.'
        : 'Something went wrong. Please try again.',
    }
  }
  return {isConflict, message: raw}
}
