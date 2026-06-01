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

package com.moneat.security.signals

internal const val INVALID_STATUS_MESSAGE = "Unknown status"
internal const val INVALID_REASON_MESSAGE =
    "archive_reason must be one of true_positive, false_positive, benign"
internal const val ARCHIVE_REQUIRES_REASON_MESSAGE = "Archiving a signal requires an archive_reason"

/**
 * The triage state machine. Allowed status moves:
 * - `open → under_review`
 * - `under_review → archived{reason}` (reason mandatory)
 * - `open → archived{reason}` (skip review; reason mandatory)
 * - `archived → open` (reopen; clears the archive reason)
 * - any status → itself (idempotent no-op move)
 *
 * A request with no `status` is valid and changes only assignee/note. Pure so it is unit-testable in
 * isolation from the database.
 */
fun resolveTransition(current: SignalStatus, request: TriageRequest): TransitionResult {
    if (request.status == null) {
        return TransitionResult.Valid(status = null, archiveReason = null)
    }
    val target = SignalStatus.fromWire(request.status)
        ?: return TransitionResult.Invalid(INVALID_STATUS_MESSAGE)

    if (target == current) {
        return idempotentMove(current, request)
    }

    return when (target) {
        SignalStatus.UNDER_REVIEW -> reviewTransition(current)
        SignalStatus.ARCHIVED -> archiveTransition(current, request)
        SignalStatus.OPEN -> reopenTransition(current)
    }
}

private fun idempotentMove(current: SignalStatus, request: TriageRequest): TransitionResult =
    if (current == SignalStatus.ARCHIVED) {
        // Re-archiving may update the reason; still require a valid one.
        archiveReasonOrInvalid(request) { reason -> TransitionResult.Valid(SignalStatus.ARCHIVED, reason) }
    } else {
        TransitionResult.Valid(status = null, archiveReason = null)
    }

private fun reviewTransition(current: SignalStatus): TransitionResult =
    if (current == SignalStatus.OPEN) {
        TransitionResult.Valid(SignalStatus.UNDER_REVIEW, archiveReason = null)
    } else {
        invalidMove(current, SignalStatus.UNDER_REVIEW)
    }

private fun archiveTransition(current: SignalStatus, request: TriageRequest): TransitionResult =
    if (current == SignalStatus.OPEN || current == SignalStatus.UNDER_REVIEW) {
        archiveReasonOrInvalid(request) { reason -> TransitionResult.Valid(SignalStatus.ARCHIVED, reason) }
    } else {
        invalidMove(current, SignalStatus.ARCHIVED)
    }

private fun reopenTransition(current: SignalStatus): TransitionResult =
    if (current == SignalStatus.ARCHIVED) {
        TransitionResult.Valid(SignalStatus.OPEN, archiveReason = null)
    } else {
        invalidMove(current, SignalStatus.OPEN)
    }

private fun archiveReasonOrInvalid(
    request: TriageRequest,
    onValid: (ArchiveReason) -> TransitionResult,
): TransitionResult {
    val raw = request.reason ?: return TransitionResult.Invalid(ARCHIVE_REQUIRES_REASON_MESSAGE)
    val reason = ArchiveReason.fromWire(raw) ?: return TransitionResult.Invalid(INVALID_REASON_MESSAGE)
    return onValid(reason)
}

private fun invalidMove(from: SignalStatus, to: SignalStatus): TransitionResult =
    TransitionResult.Invalid("Cannot move signal from ${from.wire} to ${to.wire}")
