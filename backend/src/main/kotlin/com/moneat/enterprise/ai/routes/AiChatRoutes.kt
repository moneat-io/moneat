// Moneat Enterprise - proprietary module
// Copyright (c) 2026 Moneat. All rights reserved.
// See enterprise/LICENSE for license terms.

package com.moneat.enterprise.ai.routes

import com.moneat.enterprise.ai.models.AiChatStreamRequest
import com.moneat.enterprise.ai.models.AiConfirmRequest
import com.moneat.enterprise.ai.models.SseError
import com.moneat.enterprise.ai.services.AiContextSnapshotService
import com.moneat.enterprise.ai.services.EnterpriseAiChatService
import com.moneat.shared.models.Memberships
import com.moneat.shared.models.Users
import io.ktor.http.ContentType
import io.ktor.http.HttpHeaders
import io.ktor.http.HttpStatusCode
import io.ktor.server.auth.authenticate
import io.ktor.server.auth.jwt.JWTPrincipal
import io.ktor.server.auth.principal
import io.ktor.server.request.receive
import io.ktor.server.response.respond
import io.ktor.server.response.respondTextWriter
import io.ktor.server.routing.Route
import io.ktor.server.routing.post
import io.ktor.server.routing.route
import kotlinx.serialization.json.Json
import mu.KotlinLogging
import org.jetbrains.exposed.v1.core.eq
import org.jetbrains.exposed.v1.jdbc.selectAll
import org.jetbrains.exposed.v1.jdbc.transactions.transaction

private val logger = KotlinLogging.logger {}
private val json = Json { ignoreUnknownKeys = true; encodeDefaults = true }

fun Route.aiEnterpriseRoutes(
    chatService: EnterpriseAiChatService,
    snapshotService: AiContextSnapshotService,
) {
    authenticate("auth-jwt") {
        route("/v1/ai") {
            /**
             * POST /v1/ai/chat/stream
             * Phase 1: Search observability data and prepare context snapshot.
             * Returns SSE stream with search progress and context_ready event.
             */
            post("/chat/stream") {
                val principal = call.principal<JWTPrincipal>()
                val userId = principal!!.payload.getClaim("userId").asInt()

                if (!isAdmin(userId)) {
                    call.respond(HttpStatusCode.Forbidden, mapOf("error" to "AI chat is only available for admin users"))
                    return@post
                }

                val orgId = getOrgId(userId)
                if (orgId == null) {
                    call.respond(HttpStatusCode.BadRequest, mapOf("error" to "No organization found"))
                    return@post
                }

                val request = call.receive<AiChatStreamRequest>()
                if (request.message.isBlank()) {
                    call.respond(HttpStatusCode.BadRequest, mapOf("error" to "Message cannot be empty"))
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
                            conversationId = request.conversationId,
                            currentPage = request.currentPage,
                            timeRange = request.timeRange,
                        )
                    } catch (e: Exception) {
                        logger.error(e) { "AI stream error for user $userId" }
                        EnterpriseAiChatService.sendSse(this,
                            json.encodeToString(SseError.serializer(), SseError(error = "Search failed: ${e.message}")))
                    }
                }
            }

            /**
             * POST /v1/ai/chat/confirm
             * Phase 2: User confirmed — send snapshot context to LLM.
             * Returns SSE stream with LLM response and cost info.
             */
            post("/chat/confirm") {
                val principal = call.principal<JWTPrincipal>()
                val userId = principal!!.payload.getClaim("userId").asInt()

                if (!isAdmin(userId)) {
                    call.respond(HttpStatusCode.Forbidden, mapOf("error" to "AI chat is only available for admin users"))
                    return@post
                }

                val orgId = getOrgId(userId)
                if (orgId == null) {
                    call.respond(HttpStatusCode.BadRequest, mapOf("error" to "No organization found"))
                    return@post
                }

                val request = call.receive<AiConfirmRequest>()

                call.response.headers.append(HttpHeaders.CacheControl, "no-cache")
                call.response.headers.append(HttpHeaders.Connection, "keep-alive")

                call.respondTextWriter(contentType = ContentType.Text.EventStream) {
                    try {
                        chatService.confirmAndGenerate(
                            writer = this,
                            userId = userId,
                            orgId = orgId,
                            snapshotId = request.snapshotId,
                        )
                    } catch (e: Exception) {
                        logger.error(e) { "AI confirm error for user $userId" }
                        EnterpriseAiChatService.sendSse(this,
                            json.encodeToString(SseError.serializer(), SseError(error = "Generation failed: ${e.message}")))
                    }
                }
            }
        }
    }
}

private fun getOrgId(userId: Int): Int? {
    return transaction {
        Memberships
            .selectAll()
            .where { Memberships.user_id eq userId }
            .firstOrNull()
            ?.get(Memberships.organization_id)
    }
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
