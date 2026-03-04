// Moneat Enterprise - proprietary module
// Copyright (c) 2026 Moneat. All rights reserved.
// See enterprise/LICENSE for license terms.

package com.moneat.enterprise.mcp.services

import com.moneat.events.models.NotificationPreferencesData
import com.moneat.events.models.NotificationPreferencesResponse
import com.moneat.events.models.ProjectNotificationPreferences
import com.moneat.shared.models.NotificationPreferences
import com.moneat.shared.models.Projects
import kotlin.time.Clock
import org.jetbrains.exposed.v1.core.and
import org.jetbrains.exposed.v1.core.eq
import org.jetbrains.exposed.v1.core.inList
import org.jetbrains.exposed.v1.core.isNotNull
import org.jetbrains.exposed.v1.core.isNull
import org.jetbrains.exposed.v1.jdbc.insertIgnore
import org.jetbrains.exposed.v1.jdbc.selectAll
import org.jetbrains.exposed.v1.jdbc.transactions.transaction
import org.jetbrains.exposed.v1.jdbc.update

class NotificationPreferencesService {

    companion object {
        private const val DEFAULT_ALERT_FREQUENCY_MINUTES = 30
    }

    fun getPreferences(userId: Int): NotificationPreferencesResponse = transaction {
        val global = NotificationPreferences
            .selectAll()
            .where {
                (NotificationPreferences.user_id eq userId) and
                    NotificationPreferences.project_id.isNull()
            }.firstOrNull()

        val globalPrefs = if (global != null) {
            NotificationPreferencesData(
                issueAlerts = global[NotificationPreferences.issue_alerts],
                errorAlerts = global[NotificationPreferences.error_alerts],
                weeklySummary = global[NotificationPreferences.weekly_summary],
                alertFrequencyMinutes = global[NotificationPreferences.alert_frequency_minutes]
            )
        } else {
            NotificationPreferencesData(
                issueAlerts = true,
                errorAlerts = true,
                weeklySummary = true,
                alertFrequencyMinutes = DEFAULT_ALERT_FREQUENCY_MINUTES
            )
        }

        // Batch-load all project names in one query to avoid N+1
        val projectPrefs = NotificationPreferences
            .selectAll()
            .where {
                (NotificationPreferences.user_id eq userId) and
                    NotificationPreferences.project_id.isNotNull()
            }.toList()

        val projectIds = projectPrefs.map { it[NotificationPreferences.project_id]!! }
        val projectNames: Map<Long, String> = if (projectIds.isEmpty()) {
            emptyMap()
        } else {
            Projects
                .selectAll()
                .where { Projects.id inList projectIds }
                .associate { it[Projects.id] to it[Projects.name] }
        }

        val projects = projectPrefs.map { pref ->
            val projectId = pref[NotificationPreferences.project_id]!!
            ProjectNotificationPreferences(
                projectId = projectId,
                projectName = projectNames[projectId] ?: "Unknown",
                issueAlerts = pref[NotificationPreferences.issue_alerts],
                errorAlerts = pref[NotificationPreferences.error_alerts],
                weeklySummary = pref[NotificationPreferences.weekly_summary],
                alertFrequencyMinutes = pref[NotificationPreferences.alert_frequency_minutes]
            )
        }

        NotificationPreferencesResponse(global = globalPrefs, projects = projects)
    }

    fun updatePreferences(
        userId: Int,
        issueAlerts: Boolean,
        errorAlerts: Boolean,
        weeklySummary: Boolean,
        alertFrequencyMinutes: Int,
    ): NotificationPreferencesData {
        require(alertFrequencyMinutes >= 1) {
            "alertFrequencyMinutes must be >= 1, got $alertFrequencyMinutes"
        }

        val now = Clock.System.now()
        return transaction {
            // Update first; if no row existed, insert (avoids read-then-write race)
            val updated = NotificationPreferences.update({
                (NotificationPreferences.user_id eq userId) and
                    NotificationPreferences.project_id.isNull()
            }) {
                it[NotificationPreferences.issue_alerts] = issueAlerts
                it[NotificationPreferences.error_alerts] = errorAlerts
                it[NotificationPreferences.weekly_summary] = weeklySummary
                it[NotificationPreferences.alert_frequency_minutes] = alertFrequencyMinutes
                it[updated_at] = now
            }

            if (updated == 0) {
                NotificationPreferences.insertIgnore {
                    it[user_id] = userId
                    it[project_id] = null
                    it[NotificationPreferences.issue_alerts] = issueAlerts
                    it[NotificationPreferences.error_alerts] = errorAlerts
                    it[NotificationPreferences.weekly_summary] = weeklySummary
                    it[NotificationPreferences.alert_frequency_minutes] = alertFrequencyMinutes
                    it[created_at] = now
                    it[updated_at] = now
                }
                // Re-read the persisted row in case a concurrent transaction beat the insertIgnore
                val persisted = NotificationPreferences
                    .selectAll()
                    .where {
                        (NotificationPreferences.user_id eq userId) and
                            NotificationPreferences.project_id.isNull()
                    }.firstOrNull()
                if (persisted != null) {
                    return@transaction NotificationPreferencesData(
                        issueAlerts = persisted[NotificationPreferences.issue_alerts],
                        errorAlerts = persisted[NotificationPreferences.error_alerts],
                        weeklySummary = persisted[NotificationPreferences.weekly_summary],
                        alertFrequencyMinutes = persisted[NotificationPreferences.alert_frequency_minutes],
                    )
                }
            }

            NotificationPreferencesData(
                issueAlerts = issueAlerts,
                errorAlerts = errorAlerts,
                weeklySummary = weeklySummary,
                alertFrequencyMinutes = alertFrequencyMinutes
            )
        }
    }
}

