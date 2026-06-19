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

package com.moneat.ai

import com.moneat.shared.models.Organizations
import com.moneat.shared.models.Users
import org.jetbrains.exposed.v1.core.ReferenceOption
import org.jetbrains.exposed.v1.core.Table
import org.jetbrains.exposed.v1.datetime.timestamp
import kotlin.uuid.Uuid

object AiConversations : Table("ai_conversations") {
    val id = integer("id").autoIncrement()
    val resource_id = uuid("resource_id").clientDefault { Uuid.random() }
    val organization_id = integer("organization_id").references(Organizations.id)
    val user_id = integer("user_id").references(Users.id)
    val title = varchar("title", 255).nullable()
    val created_at = timestamp("created_at")
    val updated_at = timestamp("updated_at")
    override val primaryKey = PrimaryKey(id)
}

object AiMessages : Table("ai_messages") {
    val id = integer("id").autoIncrement()
    val resource_id = uuid("resource_id").clientDefault { Uuid.random() }
    val conversation_id = integer("conversation_id").references(
        AiConversations.id,
        onDelete = ReferenceOption.CASCADE
    )
    val role = varchar("role", 20)
    val content = text("content")
    val page_context = varchar("page_context", 255).nullable()
    val model = varchar("model", 50).nullable()
    val tokens_used = integer("tokens_used").nullable()
    val input_tokens = integer("input_tokens").nullable()
    val output_tokens = integer("output_tokens").nullable()
    val cost_usd = decimal("cost_usd", 10, 6).nullable()
    val provider = varchar("provider", 20).nullable()
    val created_at = timestamp("created_at")
    override val primaryKey = PrimaryKey(id)
}
