// Moneat Enterprise - proprietary module
// Copyright (c) 2026 Moneat. All rights reserved.
// See ee/LICENSE for license terms.

package com.moneat.enterprise.oncall.services

import java.time.LocalTime
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertNull
import kotlin.time.Instant

class ScheduleRotationResolverTest {
    @Test
    fun `daily rotation respects timezone handoff`() {
        val definition = RotationDefinition("DAILY", LocalTime.of(9, 0), "America/New_York")

        assertEquals(
            0,
            ScheduleRotationResolver.participantIndex(
                definition,
                participantCount = 2,
                at = Instant.parse("2026-01-02T13:59:00Z"),
            ),
        )
        assertEquals(
            1,
            ScheduleRotationResolver.participantIndex(
                definition,
                participantCount = 2,
                at = Instant.parse("2026-01-02T14:00:00Z"),
            ),
        )
    }

    @Test
    fun `weekly rotation remains deterministic across daylight saving transition`() {
        val definition = RotationDefinition("WEEKLY", LocalTime.of(9, 0), "America/New_York")

        val beforeHandoff =
            ScheduleRotationResolver.participantIndex(
                definition,
                participantCount = 3,
                at = Instant.parse("2026-03-08T12:59:59Z"),
            )
        val afterHandoff =
            ScheduleRotationResolver.participantIndex(
                definition,
                participantCount = 3,
                at = Instant.parse("2026-03-08T13:00:00Z"),
            )

        assertEquals(0, beforeHandoff)
        assertEquals(0, afterHandoff)
    }

    @Test
    fun `empty gaps and invalid zones resolve to no responder`() {
        val at = Instant.parse("2026-03-08T13:00:00Z")

        assertNull(
            ScheduleRotationResolver.participantIndex(
                RotationDefinition("DAILY", LocalTime.NOON, "UTC", explicitGap = true),
                participantCount = 2,
                at = at,
            ),
        )
        assertNull(
            ScheduleRotationResolver.participantIndex(
                RotationDefinition("DAILY", LocalTime.NOON, "Not/AZone"),
                participantCount = 2,
                at = at,
            ),
        )
        assertNull(
            ScheduleRotationResolver.participantIndex(
                RotationDefinition("DAILY", LocalTime.NOON, "UTC"),
                participantCount = 0,
                at = at,
            ),
        )
    }
}
