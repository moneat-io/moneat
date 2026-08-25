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

package com.moneat.shared.models

import org.jetbrains.exposed.v1.core.ReferenceOption
import org.jetbrains.exposed.v1.core.dao.id.IntIdTable
import org.jetbrains.exposed.v1.datetime.timestamp
import kotlin.time.Clock
import kotlin.uuid.Uuid

enum class SlackOutboundOperation(val wire: String, val endpoint: String) {
    MESSAGE("MESSAGE", "chat.postMessage"),
    MESSAGE_UPDATE("MESSAGE_UPDATE", "chat.update"),
    CHANNEL_CREATE("CHANNEL_CREATE", "conversations.create"),
    CHANNEL_UPDATE("CHANNEL_UPDATE", "conversations.rename"),
    INVITE("INVITE", "conversations.invite"),
    BOOKMARK("BOOKMARK", "bookmarks.add"),
    ARCHIVE("ARCHIVE", "conversations.archive"),
}

enum class SlackOutboundDeliveryStatus(val wire: String) {
    PENDING("PENDING"),
    PROCESSING("PROCESSING"),
    DELIVERED("DELIVERED"),
    RETRY("RETRY"),
    DEAD_LETTER("DEAD_LETTER"),
    SUPERSEDED("SUPERSEDED"),
}

object SlackOutboundDeliveries : IntIdTable("slack_outbound_deliveries") {
    val resourceId = uuid("resource_id").clientDefault { Uuid.random() }
    val organizationId = integer("organization_id").references(Organizations.id, onDelete = ReferenceOption.CASCADE)
    val teamId = varchar("team_id", 255).nullable()
    val channelId = varchar("channel_id", 255).nullable()
    val operation = varchar("operation", 32)
    val idempotencyKey = varchar("idempotency_key", 384)
    val payload = text("payload")
    val desiredVersion = integer("desired_version").default(1)
    val deliveredVersion = integer("delivered_version").nullable()
    val providerMessageId = varchar("provider_message_id", 255).nullable()
    val providerMessageTs = varchar("provider_message_ts", 64).nullable()
    val status = varchar("status", 24).default(SlackOutboundDeliveryStatus.PENDING.wire)
    val attemptCount = integer("attempt_count").default(0)
    val availableAt = timestamp("available_at")
    val rateLimitResetAt = timestamp("rate_limit_reset_at").nullable()
    val leasedAt = timestamp("leased_at").nullable()
    val leaseOwner = varchar("lease_owner", 120).nullable()
    val lastError = text("last_error").nullable()
    val supersededAt = timestamp("superseded_at").nullable()
    val deliveredAt = timestamp("delivered_at").nullable()
    val createdAt = timestamp("created_at").clientDefault { Clock.System.now() }
    val updatedAt = timestamp("updated_at").clientDefault { Clock.System.now() }

    init {
        uniqueIndex(organizationId, resourceId)
        uniqueIndex(organizationId, idempotencyKey)
        index(false, status, availableAt, id)
        index(false, organizationId, teamId, status)
    }
}
