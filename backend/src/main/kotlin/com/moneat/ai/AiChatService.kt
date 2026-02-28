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

/**
 * Legacy core AI service stub.
 *
 * Enterprise builds provide the actual AI implementation in moneat-enterprise.
 */
class AiChatService {

    suspend fun chat(userId: Int, orgId: Int, request: ChatRequest): ChatApiResponse {
        return unavailable(
            operation = "chat",
            userId = userId,
            orgId = orgId,
            context = request.message.take(32),
        )
    }

    fun getConversations(userId: Int, orgId: Int): List<ConversationSummary> {
        return unavailable(operation = "getConversations", userId = userId, orgId = orgId)
    }

    fun getConversation(conversationId: Int, userId: Int, orgId: Int): ConversationDetail? {
        return unavailable(
            operation = "getConversation",
            userId = userId,
            orgId = orgId,
            context = "conversationId=$conversationId",
        )
    }

    fun deleteConversation(conversationId: Int, userId: Int, orgId: Int): Boolean {
        return unavailable(
            operation = "deleteConversation",
            userId = userId,
            orgId = orgId,
            context = "conversationId=$conversationId",
        )
    }

    private fun unavailable(
        operation: String,
        userId: Int,
        orgId: Int,
        context: String = "",
    ): Nothing {
        throw UnsupportedOperationException(
            "Core AI operation '$operation' is unavailable for user=$userId org=$orgId $context. " +
                "Enable moneat-enterprise AI module.",
        )
    }
}
