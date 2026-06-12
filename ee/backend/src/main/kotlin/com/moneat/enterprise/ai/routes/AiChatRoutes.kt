// Moneat Enterprise - proprietary module
// Copyright (c) 2026 Moneat. All rights reserved.
// See ee/LICENSE for license terms.

package com.moneat.enterprise.ai.routes

import com.moneat.ai.AiConversations
import com.moneat.auth.requireCurrentOrg
import com.moneat.enterprise.ai.models.AiChatStreamRequest
import com.moneat.enterprise.ai.models.AiConfirmRequest
import com.moneat.enterprise.ai.models.SseError
import com.moneat.enterprise.ai.services.EnterpriseAiChatService
import com.moneat.shared.models.Users
import com.moneat.shared.services.toUuidOrNull
import io.ktor.http.ContentType
import io.ktor.http.HttpHeaders
import io.ktor.http.HttpStatusCode
import io.ktor.server.auth.authenticate
import io.ktor.server.request.receive
import io.ktor.server.response.respond
import io.ktor.server.response.respondTextWriter
import io.ktor.server.routing.Route
import io.ktor.server.routing.post
import io.ktor.server.routing.route
import kotlinx.serialization.json.Json
import mu.KotlinLogging
import org.jetbrains.exposed.v1.core.and
import org.jetbrains.exposed.v1.core.eq
import org.jetbrains.exposed.v1.jdbc.selectAll
import org.jetbrains.exposed.v1.jdbc.transactions.transaction
import kotlin.uuid.Uuid

private val logger = KotlinLogging.logger {}
private val json = Json {
    ignoreUnknownKeys = true
    encodeDefaults = true
}

fun Route.aiEnterpriseRoutes(
    chatService: EnterpriseAiChatService,
) {
    authenticate("auth-jwt") {
        route("/v1/ai") {
            /**
             * POST /v1/ai/chat/stream
             * Phase 1: Search observability data and prepare context snapshot.
             * Returns SSE stream with search progress and context_ready event.
             */
            post("/chat/stream") {
                val context = call.requireCurrentOrg() ?: return@post
                val userId = context.userId

                if (!isAdmin(userId)) {
                    call.respond(
                        HttpStatusCode.Forbidden,
                        mapOf("error" to "AI chat is only available for admin users")
                    )
                    return@post
                }

                val orgId = context.orgId

                val request = call.receive<AiChatStreamRequest>()
                if (request.message.isBlank()) {
                    call.respond(HttpStatusCode.BadRequest, mapOf("error" to "Message cannot be empty"))
                    return@post
                }
                val conversationResolution = resolveConversationId(orgId, userId, request.conversationId)
                if (conversationResolution.errorStatus != null) {
                    call.respond(
                        conversationResolution.errorStatus,
                        mapOf("error" to conversationResolution.errorMessage)
                    )
                    return@post
                }

                call.response.headers.append(HttpHeaders.CacheControl, "no-cache")
                call.response.headers.append(HttpHeaders.Connection, "keep-alive")

                call.respondTextWriter(contentType = ContentType.Text.EventStream) {
                    try {
                        chatService.searchAndPrepareContext(
                            writer = this,
                            userId = userId,
                            orgId = orgId,
                            message = request.message,
                            conversationId = conversationResolution.id,
                            currentPage = request.currentPage,
                            timeRange = request.timeRange,
                        )
                    } catch (e: Exception) {
                        logger.error(e) { "AI stream error for user $userId" }
                        EnterpriseAiChatService.sendSse(
                            this,
                            json.encodeToString(SseError.serializer(), SseError(error = "Search failed: ${e.message}"))
                        )
                    }
                }
            }

            /**
             * POST /v1/ai/chat/confirm
             * Phase 2: User confirmed — send snapshot context to LLM.
             * Returns SSE stream with LLM response and cost info.
             */
            post("/chat/confirm") {
                val context = call.requireCurrentOrg() ?: return@post
                val userId = context.userId

                if (!isAdmin(userId)) {
                    call.respond(
                        HttpStatusCode.Forbidden,
                        mapOf("error" to "AI chat is only available for admin users")
                    )
                    return@post
                }

                val request = call.receive<AiConfirmRequest>()
                val snapshotResolution = resolveSnapshotId(chatService, userId, request.snapshotId)
                if (snapshotResolution.errorStatus != null) {
                    call.respond(snapshotResolution.errorStatus, mapOf("error" to snapshotResolution.errorMessage))
                    return@post
                }
                val snapshotId = snapshotResolution.id ?: return@post

                call.response.headers.append(HttpHeaders.CacheControl, "no-cache")
                call.response.headers.append(HttpHeaders.Connection, "keep-alive")

                call.respondTextWriter(contentType = ContentType.Text.EventStream) {
                    try {
                        chatService.confirmAndGenerate(
                            writer = this,
                            userId = userId,
                            snapshotId = snapshotId,
                        )
                    } catch (e: Exception) {
                        logger.error(e) { "AI confirm error for user $userId" }
                        EnterpriseAiChatService.sendSse(
                            this,
                            json.encodeToString(
                                SseError.serializer(),
                                SseError(error = "Generation failed: ${e.message}")
                            )
                        )
                    }
                }
            }
        }
    }
}

private fun resolveConversationId(
    orgId: Int,
    userId: Int,
    rawConversationId: String?
): InternalIdResolution {
    val normalized = rawConversationId?.trim()?.takeIf { it.isNotBlank() } ?: return InternalIdResolution()
    val resourceId =
        parseUuid(normalized)
            ?: return InternalIdResolution.badRequest("conversationId must be a UUID")

    val conversationId = transaction {
        AiConversations
            .selectAll()
            .where {
                (AiConversations.resource_id eq resourceId) and
                    (AiConversations.organization_id eq orgId) and
                    (AiConversations.user_id eq userId)
            }
            .firstOrNull()
            ?.get(AiConversations.id)
    }

    return conversationId?.let { InternalIdResolution(id = it) }
        ?: InternalIdResolution.notFound("Conversation not found")
}

private fun resolveSnapshotId(
    chatService: EnterpriseAiChatService,
    userId: Int,
    rawSnapshotId: String
): InternalIdResolution {
    val normalized = rawSnapshotId.trim()
    if (normalized.isBlank()) {
        return InternalIdResolution.badRequest("snapshotId is required")
    }

    val resourceId =
        parseUuid(normalized)
            ?: return InternalIdResolution.badRequest("snapshotId must be a UUID")

    return chatService.resolveSnapshotId(resourceId, userId)?.let { InternalIdResolution(id = it) }
        ?: InternalIdResolution.notFound("Context snapshot not found or expired")
}

private fun isAdmin(userId: Int): Boolean {
    return transaction {
        Users
            .selectAll()
            .where { Users.id eq userId }
            .firstOrNull()
            ?.get(Users.is_admin) ?: false
    }
}

private fun parseUuid(raw: String): Uuid? =
    raw.toUuidOrNull()

private data class InternalIdResolution(
    val id: Int? = null,
    val errorStatus: HttpStatusCode? = null,
    val errorMessage: String = ""
) {
    companion object {
        fun badRequest(message: String): InternalIdResolution =
            InternalIdResolution(errorStatus = HttpStatusCode.BadRequest, errorMessage = message)

        fun notFound(message: String): InternalIdResolution =
            InternalIdResolution(errorStatus = HttpStatusCode.NotFound, errorMessage = message)
    }
}
