// Moneat Enterprise - proprietary module
// Copyright (c) 2026 Moneat. All rights reserved.
// See ee/LICENSE for license terms.

package com.moneat.enterprise.oncall.services

import java.time.LocalDate
import java.time.LocalTime
import java.time.ZoneId
import java.time.temporal.ChronoUnit
import kotlin.time.Instant

internal data class RotationDefinition(
    val rotationType: String,
    val handoffTime: LocalTime,
    val timezone: String,
    val explicitGap: Boolean = false,
)

internal object ScheduleRotationResolver {
    private const val WEEKLY_ROTATION_DAYS = 7L

    fun participantIndex(definition: RotationDefinition, participantCount: Int, at: Instant): Int? {
        if (definition.explicitGap || participantCount == 0) return null
        val rotationDays = when (definition.rotationType) {
            "DAILY" -> 1L
            "WEEKLY", "CUSTOM" -> WEEKLY_ROTATION_DAYS
            else -> WEEKLY_ROTATION_DAYS
        }
        val zone = runCatching { ZoneId.of(definition.timezone) }.getOrNull() ?: return null
        val zonedAt = java.time.Instant.ofEpochMilli(at.toEpochMilliseconds()).atZone(zone)
        val rotationDate = if (zonedAt.toLocalTime().isBefore(definition.handoffTime)) {
            zonedAt.toLocalDate().minusDays(1)
        } else {
            zonedAt.toLocalDate()
        }
        val daysSinceEpoch = ChronoUnit.DAYS.between(LocalDate.EPOCH, rotationDate)
        val cycle = Math.floorDiv(daysSinceEpoch, rotationDays)
        return Math.floorMod(cycle, participantCount.toLong()).toInt()
    }
}
