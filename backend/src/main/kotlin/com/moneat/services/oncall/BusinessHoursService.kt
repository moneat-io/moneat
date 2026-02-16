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

package com.moneat.services.oncall

import com.moneat.models.*
import kotlinx.datetime.Clock
import kotlinx.datetime.TimeZone
import kotlinx.datetime.toLocalDateTime
import org.jetbrains.exposed.sql.*
import org.jetbrains.exposed.sql.SqlExpressionBuilder.eq
import org.jetbrains.exposed.sql.transactions.transaction
import org.jetbrains.exposed.sql.selectAll
import org.jetbrains.exposed.sql.insert
import org.jetbrains.exposed.sql.and
import java.time.DayOfWeek
import java.time.LocalTime

class BusinessHoursService {
    
    fun getBusinessHours(organizationId: Int): BusinessHoursConfig? = transaction {
        val bhRow = BusinessHours
            .selectAll()
            .where { BusinessHours.organizationId eq organizationId }
            .singleOrNull() ?: return@transaction null
        
        val windows = BusinessHoursWindows
            .selectAll()
            .where { BusinessHoursWindows.businessHoursId eq bhRow[BusinessHours.id].value }
            .map { row ->
                BusinessHoursWindow(
                    dayOfWeek = row[BusinessHoursWindows.dayOfWeek],
                    startTime = row[BusinessHoursWindows.startTime],
                    endTime = row[BusinessHoursWindows.endTime]
                )
            }
        
        BusinessHoursConfig(
            id = bhRow[BusinessHours.id].value,
            organizationId = bhRow[BusinessHours.organizationId],
            timezone = bhRow[BusinessHours.timezone],
            enabled = bhRow[BusinessHours.enabled],
            windows = windows,
            createdAt = bhRow[BusinessHours.createdAt].toString(),
            updatedAt = bhRow[BusinessHours.updatedAt].toString()
        )
    }
    
    fun isWithinBusinessHours(organizationId: Int): Boolean = transaction {
        val config = getBusinessHours(organizationId) ?: return@transaction true // Default: always escalate
        
        if (!config.enabled) return@transaction true // Business hours disabled, always escalate
        
        val tz = try {
            TimeZone.of(config.timezone)
        } catch (e: Exception) {
            TimeZone.UTC
        }
        
        val now = Clock.System.now().toLocalDateTime(tz)
        val currentDayOfWeek = when (now.dayOfWeek) {
            kotlinx.datetime.DayOfWeek.SUNDAY -> 0
            kotlinx.datetime.DayOfWeek.MONDAY -> 1
            kotlinx.datetime.DayOfWeek.TUESDAY -> 2
            kotlinx.datetime.DayOfWeek.WEDNESDAY -> 3
            kotlinx.datetime.DayOfWeek.THURSDAY -> 4
            kotlinx.datetime.DayOfWeek.FRIDAY -> 5
            kotlinx.datetime.DayOfWeek.SATURDAY -> 6
            else -> 0
        }
        
        val currentTime = java.time.LocalTime.of(now.hour, now.minute, now.second)
        
        // Check if current time falls within any window for current day
        config.windows.any { window ->
            window.dayOfWeek == currentDayOfWeek &&
            !currentTime.isBefore(window.startTime) &&
            !currentTime.isAfter(window.endTime)
        }
    }
    
    fun shouldEscalate(organizationId: Int, priorityLevel: String): Boolean {
        // P0-P2 always escalate, P3+ only during business hours
        return when (priorityLevel) {
            "P0", "P1", "P2" -> true
            else -> isWithinBusinessHours(organizationId)
        }
    }
    
    fun updateBusinessHours(
        organizationId: Int,
        timezone: String,
        enabled: Boolean,
        windows: List<BusinessHoursWindow>
    ): BusinessHoursConfig = transaction {
        val now = Clock.System.now()
        
        // Update or insert business_hours
        val bhId = BusinessHours
            .selectAll()
            .where { BusinessHours.organizationId eq organizationId }
            .singleOrNull()
            ?.let { it[BusinessHours.id].value }
            ?: BusinessHours.insertAndGetId {
                it[BusinessHours.organizationId] = organizationId
                it[BusinessHours.timezone] = timezone
                it[BusinessHours.enabled] = enabled
                it[BusinessHours.createdAt] = now
                it[BusinessHours.updatedAt] = now
            }.value
        
        // Update existing record
        BusinessHours.update({ BusinessHours.id eq bhId }) {
            it[BusinessHours.timezone] = timezone
            it[BusinessHours.enabled] = enabled
            it[BusinessHours.updatedAt] = now
        }
        
        // Delete old windows
        BusinessHoursWindows.deleteWhere { businessHoursId eq bhId }
        
        // Insert new windows
        windows.forEach { window ->
            BusinessHoursWindows.insert {
                it[businessHoursId] = bhId
                it[dayOfWeek] = window.dayOfWeek
                it[startTime] = window.startTime
                it[endTime] = window.endTime
                it[createdAt] = now
            }
        }
        
        getBusinessHours(organizationId)!!
    }
}
