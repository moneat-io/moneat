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

package com.moneat.services

import com.moneat.models.*
import mu.KotlinLogging
import org.jetbrains.exposed.v1.core.*
import org.jetbrains.exposed.v1.core.and
import org.jetbrains.exposed.v1.jdbc.*
import org.jetbrains.exposed.v1.jdbc.insert
import org.jetbrains.exposed.v1.jdbc.selectAll
import org.jetbrains.exposed.v1.jdbc.transactions.transaction
import kotlin.time.Clock

private val logger = KotlinLogging.logger {}

class AlertNotificationPreferencesService {

    enum class AlertSource(val value: String) {
        SYSTEM_ALERT("SYSTEM_ALERT"),
        SYSTEM_DOWN("SYSTEM_DOWN"),
        UPTIME_MONITOR("UPTIME_MONITOR"),
        ERROR_ALERT("ERROR_ALERT");

        companion object {
            fun fromString(value: String): AlertSource? {
                return values().find { it.value == value }
            }
        }
    }

    /**
     * Get alert notification preferences for a user in an organization.
     * If preferences don't exist, returns defaults (all enabled).
     */
    fun getPreferences(
        userId: Int,
        organizationId: Int
    ): List<AlertNotificationPreference> {
        return transaction {
            val existing =
                AlertNotificationPreferences
                    .selectAll()
                    .where {
                        (AlertNotificationPreferences.user_id eq userId) and
                            (AlertNotificationPreferences.organization_id eq organizationId)
                    }.associate {
                        it[AlertNotificationPreferences.alert_source] to
                            AlertNotificationPreference(
                                alertSource = it[AlertNotificationPreferences.alert_source],
                                emailEnabled = it[AlertNotificationPreferences.email_enabled],
                                slackEnabled = it[AlertNotificationPreferences.slack_enabled],
                                discordEnabled = it[AlertNotificationPreferences.discord_enabled]
                            )
                    }

            // Return all sources, using defaults for missing ones
            AlertSource.values().map { source ->
                existing[source.value] ?: AlertNotificationPreference(
                    alertSource = source.value,
                    emailEnabled = true,
                    slackEnabled = true,
                    discordEnabled = true
                )
            }
        }
    }

    /**
     * Update alert notification preferences for a specific alert source.
     */
    fun updatePreference(
        userId: Int,
        organizationId: Int,
        alertSource: String,
        emailEnabled: Boolean,
        slackEnabled: Boolean,
        discordEnabled: Boolean
    ): AlertNotificationPreference {
        // Validate alert source
        if (AlertSource.fromString(alertSource) == null) {
            throw IllegalArgumentException("Invalid alert source: $alertSource")
        }

        return transaction {
            val now = Clock.System.now()

            val existing =
                AlertNotificationPreferences
                    .selectAll()
                    .where {
                        (AlertNotificationPreferences.user_id eq userId) and
                            (AlertNotificationPreferences.organization_id eq organizationId) and
                            (AlertNotificationPreferences.alert_source eq alertSource)
                    }.firstOrNull()

            if (existing != null) {
                // Update existing
                AlertNotificationPreferences.update({
                    (AlertNotificationPreferences.user_id eq userId) and
                        (AlertNotificationPreferences.organization_id eq organizationId) and
                        (AlertNotificationPreferences.alert_source eq alertSource)
                }) {
                    it[AlertNotificationPreferences.email_enabled] = emailEnabled
                    it[AlertNotificationPreferences.slack_enabled] = slackEnabled
                    it[AlertNotificationPreferences.discord_enabled] = discordEnabled
                    it[AlertNotificationPreferences.updated_at] = now
                }
            } else {
                // Insert new
                AlertNotificationPreferences.insert {
                    it[AlertNotificationPreferences.user_id] = userId
                    it[AlertNotificationPreferences.organization_id] = organizationId
                    it[AlertNotificationPreferences.alert_source] = alertSource
                    it[AlertNotificationPreferences.email_enabled] = emailEnabled
                    it[AlertNotificationPreferences.slack_enabled] = slackEnabled
                    it[AlertNotificationPreferences.discord_enabled] = discordEnabled
                    it[AlertNotificationPreferences.created_at] = now
                    it[AlertNotificationPreferences.updated_at] = now
                }
            }

            AlertNotificationPreference(
                alertSource = alertSource,
                emailEnabled = emailEnabled,
                slackEnabled = slackEnabled,
                discordEnabled = discordEnabled
            )
        }
    }

    /**
     * Get all users in an organization who have a specific channel enabled for an alert source.
     * Returns user IDs and emails.
     */
    fun getUsersWithChannelEnabled(
        organizationId: Int,
        alertSource: String,
        channel: String // "email", "slack", or "discord"
    ): List<Pair<Int, String>> {
        return transaction {
            val orgUsers =
                Memberships
                    .innerJoin(Users)
                    .selectAll()
                    .where {
                        (Memberships.organization_id eq organizationId) and
                            (Users.email_verified eq true)
                    }.map { Pair(it[Users.id], it[Users.email]) }

            // Filter by channel preference
            orgUsers.filter { (userId, _) ->
                val prefs =
                    AlertNotificationPreferences
                        .selectAll()
                        .where {
                            (AlertNotificationPreferences.user_id eq userId) and
                                (AlertNotificationPreferences.organization_id eq organizationId) and
                                (AlertNotificationPreferences.alert_source eq alertSource)
                        }.firstOrNull()

                if (prefs != null) {
                    when (channel.lowercase()) {
                        "email" -> prefs[AlertNotificationPreferences.email_enabled]
                        "slack" -> prefs[AlertNotificationPreferences.slack_enabled]
                        "discord" -> prefs[AlertNotificationPreferences.discord_enabled]
                        else -> false
                    }
                } else {
                    // Default: all channels enabled
                    true
                }
            }
        }
    }

    /**
     * Check if a specific user has a channel enabled for an alert source.
     */
    fun isChannelEnabled(
        userId: Int,
        organizationId: Int,
        alertSource: String,
        channel: String
    ): Boolean {
        return transaction {
            val prefs =
                AlertNotificationPreferences
                    .selectAll()
                    .where {
                        (AlertNotificationPreferences.user_id eq userId) and
                            (AlertNotificationPreferences.organization_id eq organizationId) and
                            (AlertNotificationPreferences.alert_source eq alertSource)
                    }.firstOrNull()

            if (prefs != null) {
                when (channel.lowercase()) {
                    "email" -> prefs[AlertNotificationPreferences.email_enabled]
                    "slack" -> prefs[AlertNotificationPreferences.slack_enabled]
                    "discord" -> prefs[AlertNotificationPreferences.discord_enabled]
                    else -> false
                }
            } else {
                // Default: all channels enabled
                true
            }
        }
    }
}
