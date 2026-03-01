package com.moneat.notifications.services

import com.moneat.events.models.NotificationPreferencesData
import com.moneat.events.models.NotificationPreferencesResponse
import com.moneat.events.models.ProjectNotificationPreferences
import com.moneat.shared.models.NotificationPreferences
import com.moneat.shared.models.Projects
import kotlinx.datetime.Clock
import org.jetbrains.exposed.v1.core.and
import org.jetbrains.exposed.v1.jdbc.insert
import org.jetbrains.exposed.v1.jdbc.selectAll
import org.jetbrains.exposed.v1.jdbc.transactions.transaction
import org.jetbrains.exposed.v1.jdbc.update

class NotificationPreferencesService {

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
                alertFrequencyMinutes = 30
            )
        }

        val projects = NotificationPreferences
            .selectAll()
            .where {
                (NotificationPreferences.user_id eq userId) and
                    NotificationPreferences.project_id.isNotNull()
            }.map { pref ->
                val projectId = pref[NotificationPreferences.project_id]!!
                val projectName = Projects
                    .selectAll()
                    .where { Projects.id eq projectId }
                    .firstOrNull()
                    ?.get(Projects.name) ?: "Unknown"
                ProjectNotificationPreferences(
                    projectId = projectId,
                    projectName = projectName,
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
    ): NotificationPreferencesData = transaction {
        val now = Clock.System.now()
        val existing = NotificationPreferences
            .selectAll()
            .where {
                (NotificationPreferences.user_id eq userId) and
                    NotificationPreferences.project_id.isNull()
            }.firstOrNull()

        if (existing != null) {
            NotificationPreferences.update({
                (NotificationPreferences.user_id eq userId) and
                    NotificationPreferences.project_id.isNull()
            }) {
                it[NotificationPreferences.issue_alerts] = issueAlerts
                it[NotificationPreferences.error_alerts] = errorAlerts
                it[NotificationPreferences.weekly_summary] = weeklySummary
                it[NotificationPreferences.alert_frequency_minutes] = alertFrequencyMinutes
                it[updated_at] = now
            }
        } else {
            NotificationPreferences.insert {
                it[user_id] = userId
                it[project_id] = null
                it[NotificationPreferences.issue_alerts] = issueAlerts
                it[NotificationPreferences.error_alerts] = errorAlerts
                it[NotificationPreferences.weekly_summary] = weeklySummary
                it[NotificationPreferences.alert_frequency_minutes] = alertFrequencyMinutes
                it[created_at] = now
                it[updated_at] = now
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
