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

import org.jetbrains.exposed.v1.core.dao.id.LongIdTable
import org.jetbrains.exposed.v1.datetime.timestamp
import kotlin.time.Clock
import kotlin.uuid.Uuid

enum class SlackInboundDeliveryStatus(val wire: String) {
    PENDING("PENDING"),
    QUEUED("QUEUED"),
    PROCESSING("PROCESSING"),
    PROCESSED("PROCESSED"),
    RETRY("RETRY"),
}

object SlackInboundDeliveries : LongIdTable("slack_inbound_deliveries") {
    val resourceId = uuid("resource_id").clientDefault { Uuid.random() }
    val deliveryKey = varchar("delivery_key", 384)
    val requestType = varchar("request_type", 32)
    val payload = text("payload")
    val teamId = varchar("team_id", 128).nullable()
    val enterpriseId = varchar("enterprise_id", 128).nullable()
    val channelId = varchar("channel_id", 128).nullable()
    val userId = varchar("user_id", 128).nullable()
    val messageTs = varchar("message_ts", 64).nullable()
    val threadTs = varchar("thread_ts", 64).nullable()
    val viewId = varchar("view_id", 256).nullable()
    val status = varchar("status", 24).default(SlackInboundDeliveryStatus.PENDING.wire)
    val attemptCount = integer("attempt_count").default(0)
    val availableAt = timestamp("available_at").clientDefault { Clock.System.now() }
    val leasedAt = timestamp("leased_at").nullable()
    val leaseOwner = varchar("lease_owner", 120).nullable()
    val lastError = text("last_error").nullable()
    val processedAt = timestamp("processed_at").nullable()
    val createdAt = timestamp("created_at").clientDefault { Clock.System.now() }
    val updatedAt = timestamp("updated_at").clientDefault { Clock.System.now() }

    init {
        uniqueIndex(deliveryKey)
        uniqueIndex(resourceId)
        index(false, status, availableAt, id)
        index(false, teamId, channelId, userId, createdAt)
    }
}
