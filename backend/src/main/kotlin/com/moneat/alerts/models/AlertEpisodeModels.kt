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

package com.moneat.alerts.models

import com.moneat.shared.models.Organizations
import com.moneat.shared.models.Users
import kotlinx.serialization.SerialName
import kotlinx.serialization.Serializable
import org.jetbrains.exposed.v1.core.ReferenceOption
import org.jetbrains.exposed.v1.core.dao.id.IntIdTable
import org.jetbrains.exposed.v1.datetime.timestamp

object AlertEpisodes : IntIdTable("alert_episodes") {
    val organizationId = integer("organization_id").references(Organizations.id, onDelete = ReferenceOption.CASCADE)
    val sourceName = varchar("source", 64)
    val deduplicationKey = text("deduplication_key")
    val episodeSeq = integer("episode_seq")
    val episodeKey = text("episode_key")
    val status = varchar("status", 20)
    val openedAt = timestamp("opened_at")
    val lastSeenAt = timestamp("last_seen_at")
    val resolvedAt = timestamp("resolved_at").nullable()
    val lastNotificationAt = timestamp("last_notification_at").nullable()
    val notificationCount = integer("notification_count").default(0)
    val suppressedAt = timestamp("suppressed_at").nullable()
    val suppressedByUserId = integer("suppressed_by_user_id")
        .references(Users.id, onDelete = ReferenceOption.SET_NULL)
        .nullable()
    val suppressReason = varchar("suppress_reason", 255).nullable()
    val createdAt = timestamp("created_at")
    val updatedAt = timestamp("updated_at")
}

@Serializable
data class AlertEpisodeResponse(
    val id: Int,
    @SerialName("organization_id") val organizationId: Int,
    val source: String,
    @SerialName("deduplication_key") val deduplicationKey: String,
    @SerialName("episode_seq") val episodeSeq: Int,
    @SerialName("episode_key") val episodeKey: String,
    val status: String,
    @SerialName("opened_at") val openedAt: String,
    @SerialName("last_seen_at") val lastSeenAt: String,
    @SerialName("resolved_at") val resolvedAt: String? = null,
    @SerialName("last_notification_at") val lastNotificationAt: String? = null,
    @SerialName("notification_count") val notificationCount: Int,
    @SerialName("suppressed_at") val suppressedAt: String? = null,
    @SerialName("suppressed_by_user_id") val suppressedByUserId: Int? = null,
    @SerialName("suppress_reason") val suppressReason: String? = null,
    @SerialName("created_at") val createdAt: String,
    @SerialName("updated_at") val updatedAt: String
)

@Serializable
data class SuppressAlertEpisodeRequest(
    val reason: String? = null
)
