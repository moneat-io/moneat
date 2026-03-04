// Moneat Enterprise - proprietary module
// Copyright (c) 2026 Moneat. All rights reserved.
// See ee/LICENSE for license terms.

package com.moneat.enterprise.oncall.services

import com.moneat.shared.models.UserNotificationChannelPreferences
import kotlin.time.Clock
import org.jetbrains.exposed.v1.core.and
import org.jetbrains.exposed.v1.core.eq
import org.jetbrains.exposed.v1.jdbc.insertIgnore
import org.jetbrains.exposed.v1.jdbc.selectAll
import org.jetbrains.exposed.v1.jdbc.transactions.transaction
import org.jetbrains.exposed.v1.jdbc.update

data class ChannelPreferences(val channels: Map<String, Boolean>) {
    fun isChannelEnabled(channel: String): Boolean = channels[channel] ?: true
    fun enabledChannels(): List<String> = channels.filter { it.value }.keys.toList()
    fun allDisabled(): Boolean = channels.values.none { it }
}

class UserNotificationPreferencesService {

    // Default channels per category when no preference row exists.
    // Matches backward-compatible "always send" behaviour.
    private val categoryDefaults = mapOf(
        "high_urgency" to mapOf("push" to true, "slack" to true, "sms" to true, "phone_call" to true),
        "low_urgency" to mapOf("email" to true, "slack" to true, "discord" to false),
        "shift_change" to mapOf("push" to true, "email" to true),
    )

    fun getChannelPreferences(userId: Int, category: String): ChannelPreferences {
        val rows = transaction {
            UserNotificationChannelPreferences
                .selectAll()
                .where {
                    (UserNotificationChannelPreferences.userId eq userId) and
                        (UserNotificationChannelPreferences.category eq category)
                }
                .associate { row ->
                    row[UserNotificationChannelPreferences.channel] to row[UserNotificationChannelPreferences.enabled]
                }
        }

        val defaults = categoryDefaults[category] ?: emptyMap()
        val merged = defaults.toMutableMap()
        merged.putAll(rows)
        return ChannelPreferences(merged)
    }

    fun updateChannelPreferences(
        userId: Int,
        organizationId: Int,
        category: String,
        channels: Map<String, Boolean>,
    ) {
        val now = Clock.System.now()
        transaction {
            channels.forEach { (channel, enabled) ->
                val updated = UserNotificationChannelPreferences.update({
                    (UserNotificationChannelPreferences.userId eq userId) and
                        (UserNotificationChannelPreferences.category eq category) and
                        (UserNotificationChannelPreferences.channel eq channel)
                }) {
                    it[UserNotificationChannelPreferences.enabled] = enabled
                    it[UserNotificationChannelPreferences.updatedAt] = now
                }

                if (updated == 0) {
                    UserNotificationChannelPreferences.insertIgnore {
                        it[UserNotificationChannelPreferences.userId] = userId
                        it[UserNotificationChannelPreferences.organizationId] = organizationId
                        it[UserNotificationChannelPreferences.category] = category
                        it[UserNotificationChannelPreferences.channel] = channel
                        it[UserNotificationChannelPreferences.enabled] = enabled
                        it[UserNotificationChannelPreferences.createdAt] = now
                        it[UserNotificationChannelPreferences.updatedAt] = now
                    }
                }
            }
        }
    }
}
