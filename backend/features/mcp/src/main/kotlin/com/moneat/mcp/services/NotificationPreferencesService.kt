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

package com.moneat.mcp.services

import com.moneat.events.models.NotificationPreferencesData
import com.moneat.events.models.NotificationPreferencesResponse
import com.moneat.events.models.ProjectNotificationPreferences
import com.moneat.shared.models.NotificationPreferences
import com.moneat.shared.models.Projects
import kotlin.time.Clock
import org.jetbrains.exposed.v1.core.ResultRow
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
        private const val DEFAULT_EMAIL_ENABLED = true
        private const val DEFAULT_PUSH_ENABLED = false
    }

    fun getPreferences(userId: Int): NotificationPreferencesResponse = transaction {
        val global = NotificationPreferences
            .selectAll()
            .where {
                (NotificationPreferences.user_id eq userId) and
                    NotificationPreferences.project_id.isNull()
            }.firstOrNull()

        val globalPrefs = global?.let(::notificationPreferencesData) ?: defaultNotificationPreferencesData()

        // Batch-load all project names in one query to avoid N+1
        val projectPrefs = NotificationPreferences
            .selectAll()
            .where {
                (NotificationPreferences.user_id eq userId) and
                    NotificationPreferences.project_id.isNotNull()
            }.toList()

        val projectIds = projectPrefs.map { it[NotificationPreferences.project_id]!! }
        val projectRows = if (projectIds.isEmpty()) {
            emptyMap()
        } else {
            Projects
                .selectAll()
                .where { Projects.id inList projectIds }
                .associate { row ->
                    row[Projects.id] to (row[Projects.name] to projectResourceId(row))
                }
        }

        val projects = projectPrefs.map { pref ->
            val projectId = pref[NotificationPreferences.project_id]!!
            val projectRow = projectRows[projectId]
            ProjectNotificationPreferences(
                projectId = projectRow?.second ?: "",
                projectName = projectRow?.first ?: "Unknown",
                issueAlerts = pref[NotificationPreferences.issue_alerts],
                errorAlerts = pref[NotificationPreferences.error_alerts],
                weeklySummary = pref[NotificationPreferences.weekly_summary],
                alertFrequencyMinutes = pref[NotificationPreferences.alert_frequency_minutes],
                emailEnabled = pref[NotificationPreferences.email_enabled],
                pushEnabled = pref[NotificationPreferences.push_enabled],
            )
        }

        NotificationPreferencesResponse(global = globalPrefs, projects = projects)
    }

    private fun projectResourceId(row: ResultRow): String {
        return row[Projects.resource_id].toString()
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
                    it[email_enabled] = DEFAULT_EMAIL_ENABLED
                    it[push_enabled] = DEFAULT_PUSH_ENABLED
                    it[created_at] = now
                    it[updated_at] = now
                }
            }

            NotificationPreferences
                .selectAll()
                .where {
                    (NotificationPreferences.user_id eq userId) and
                        NotificationPreferences.project_id.isNull()
                }
                .firstOrNull()
                ?.let(::notificationPreferencesData)
                ?: NotificationPreferencesData(
                    issueAlerts = issueAlerts,
                    errorAlerts = errorAlerts,
                    weeklySummary = weeklySummary,
                    alertFrequencyMinutes = alertFrequencyMinutes,
                    emailEnabled = DEFAULT_EMAIL_ENABLED,
                    pushEnabled = DEFAULT_PUSH_ENABLED,
                )
        }
    }

    private fun defaultNotificationPreferencesData(): NotificationPreferencesData =
        NotificationPreferencesData(
            issueAlerts = true,
            errorAlerts = true,
            weeklySummary = true,
            alertFrequencyMinutes = DEFAULT_ALERT_FREQUENCY_MINUTES,
            emailEnabled = DEFAULT_EMAIL_ENABLED,
            pushEnabled = DEFAULT_PUSH_ENABLED,
        )

    private fun notificationPreferencesData(row: ResultRow): NotificationPreferencesData =
        NotificationPreferencesData(
            issueAlerts = row[NotificationPreferences.issue_alerts],
            errorAlerts = row[NotificationPreferences.error_alerts],
            weeklySummary = row[NotificationPreferences.weekly_summary],
            alertFrequencyMinutes = row[NotificationPreferences.alert_frequency_minutes],
            emailEnabled = row[NotificationPreferences.email_enabled],
            pushEnabled = row[NotificationPreferences.push_enabled],
        )
}
