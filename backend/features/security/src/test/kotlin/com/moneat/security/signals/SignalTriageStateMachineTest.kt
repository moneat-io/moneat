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

import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertIs
import kotlin.test.assertNull

class SignalTriageStateMachineTest {
    private companion object {
        private const val INVALID_STATUS_MESSAGE = "Unknown status"
        private const val INVALID_REASON_MESSAGE =
            "archive_reason must be one of true_positive, false_positive, benign"
        private const val ARCHIVE_REQUIRES_REASON_MESSAGE = "Archiving a signal requires an archive_reason"
    }

    @Test
    fun `open advances to under_review`() {
        val result = resolveTransition(SignalStatus.OPEN, TriageRequest(status = "under_review"))
        val valid = assertIs<TransitionResult.Valid>(result)
        assertEquals(SignalStatus.UNDER_REVIEW, valid.status)
        assertNull(valid.archiveReason)
    }

    @Test
    fun `under_review archives with a reason`() {
        val result = resolveTransition(
            SignalStatus.UNDER_REVIEW,
            TriageRequest(status = "archived", reason = "false_positive")
        )
        val valid = assertIs<TransitionResult.Valid>(result)
        assertEquals(SignalStatus.ARCHIVED, valid.status)
        assertEquals(ArchiveReason.FALSE_POSITIVE, valid.archiveReason)
    }

    @Test
    fun `open may archive directly with a reason`() {
        val result = resolveTransition(
            SignalStatus.OPEN,
            TriageRequest(status = "archived", reason = "true_positive")
        )
        val valid = assertIs<TransitionResult.Valid>(result)
        assertEquals(SignalStatus.ARCHIVED, valid.status)
        assertEquals(ArchiveReason.TRUE_POSITIVE, valid.archiveReason)
    }

    @Test
    fun `archiving without a reason is rejected`() {
        val result = resolveTransition(SignalStatus.OPEN, TriageRequest(status = "archived"))
        val invalid = assertIs<TransitionResult.Invalid>(result)
        assertEquals(ARCHIVE_REQUIRES_REASON_MESSAGE, invalid.message)
    }

    @Test
    fun `archiving with an unknown reason is rejected`() {
        val result = resolveTransition(
            SignalStatus.OPEN,
            TriageRequest(status = "archived", reason = "not_a_reason")
        )
        val invalid = assertIs<TransitionResult.Invalid>(result)
        assertEquals(INVALID_REASON_MESSAGE, invalid.message)
    }

    @Test
    fun `archived reopens to open and clears reason`() {
        val result = resolveTransition(SignalStatus.ARCHIVED, TriageRequest(status = "open"))
        val valid = assertIs<TransitionResult.Valid>(result)
        assertEquals(SignalStatus.OPEN, valid.status)
        assertNull(valid.archiveReason)
    }

    @Test
    fun `under_review cannot jump back to open`() {
        val result = resolveTransition(SignalStatus.UNDER_REVIEW, TriageRequest(status = "open"))
        assertIs<TransitionResult.Invalid>(result)
    }

    @Test
    fun `archived cannot move straight to under_review`() {
        val result = resolveTransition(SignalStatus.ARCHIVED, TriageRequest(status = "under_review"))
        assertIs<TransitionResult.Invalid>(result)
    }

    @Test
    fun `unknown status is rejected`() {
        val result = resolveTransition(SignalStatus.OPEN, TriageRequest(status = "bogus"))
        val invalid = assertIs<TransitionResult.Invalid>(result)
        assertEquals(INVALID_STATUS_MESSAGE, invalid.message)
    }

    @Test
    fun `a request with no status is a valid no-op move`() {
        val result = resolveTransition(SignalStatus.UNDER_REVIEW, TriageRequest(note = "looking into it"))
        val valid = assertIs<TransitionResult.Valid>(result)
        assertNull(valid.status)
    }

    @Test
    fun `re-archiving updates the reason`() {
        val result = resolveTransition(
            SignalStatus.ARCHIVED,
            TriageRequest(status = "archived", reason = "benign")
        )
        val valid = assertIs<TransitionResult.Valid>(result)
        assertEquals(SignalStatus.ARCHIVED, valid.status)
        assertEquals(ArchiveReason.BENIGN, valid.archiveReason)
    }
}
