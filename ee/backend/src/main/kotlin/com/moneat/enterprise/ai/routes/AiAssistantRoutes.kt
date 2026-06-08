// Moneat Enterprise - proprietary module
// Copyright (c) 2026 Moneat. All rights reserved.
// See ee/LICENSE for license terms.

package com.moneat.enterprise.ai.routes

import com.moneat.auth.requireCurrentOrg
import com.moneat.enterprise.ai.models.AiAssistantConfirmRequest
import com.moneat.enterprise.ai.models.AiAssistantStreamRequest
import com.moneat.enterprise.ai.models.AssistantDoneEvent
import com.moneat.enterprise.ai.models.AssistantErrorEvent
import com.moneat.enterprise.ai.services.AiAssistantService
import com.moneat.shared.models.Projects
import com.moneat.shared.models.Users
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
import kotlinx.serialization.encodeToString
import kotlinx.serialization.json.Json
import mu.KotlinLogging
import org.jetbrains.exposed.v1.core.SortOrder
import org.jetbrains.exposed.v1.core.and
import org.jetbrains.exposed.v1.core.eq
import org.jetbrains.exposed.v1.jdbc.selectAll
import org.jetbrains.exposed.v1.jdbc.transactions.transaction

private val logger = KotlinLogging.logger {}
private val json = Json {
    ignoreUnknownKeys = true
    encodeDefaults = true
}

fun Route.aiAssistantRoutes(service: AiAssistantService) {
    authenticate("auth-jwt") {
        route("/v1/ai/assistant") {
            post("/stream") {
                val context = call.requireCurrentOrg() ?: return@post
                val userId = context.userId
                val orgId = context.orgId

                val userDetails = getUserDetails(userId)
                if (!userDetails.isAdmin) {
                    call.respond(
                        HttpStatusCode.Forbidden,
                        mapOf("error" to "AI assistant is only available for admins")
                    )
                    return@post
                }

                val request = call.receive<AiAssistantStreamRequest>()
                if (request.message.isBlank()) {
                    call.respond(HttpStatusCode.BadRequest, mapOf("error" to "Message cannot be empty"))
                    return@post
                }
                val projectId = resolveAssistantProjectId(orgId, request.projectId)

                call.response.headers.append(HttpHeaders.CacheControl, "no-cache")
                call.response.headers.append(HttpHeaders.Connection, "keep-alive")

                call.respondTextWriter(contentType = ContentType.Text.EventStream) {
                    try {
                        service.streamAssistant(
                            writer = this,
                            userId = userId,
                            orgId = orgId,
                            message = request.message,
                            conversationId = request.conversationId,
                            projectId = projectId,
                            userTimezone = userDetails.timezone,
                        )
                    } catch (e: Exception) {
                        logger.error(e) { "Assistant stream failed for user $userId" }
                        AiAssistantService.sendSse(
                            this,
                            json.encodeToString(
                                AssistantErrorEvent.serializer(),
                                AssistantErrorEvent(error = "Assistant stream failed: ${e.message}"),
                            ),
                        )
                        AiAssistantService.sendSse(
                            this,
                            json.encodeToString(
                                AssistantDoneEvent.serializer(),
                                AssistantDoneEvent(conversationId = request.conversationId.orEmpty()),
                            ),
                        )
                    }
                }
            }

            post("/confirm") {
                val context = call.requireCurrentOrg() ?: return@post
                val userId = context.userId
                val orgId = context.orgId

                if (!getUserDetails(userId).isAdmin) {
                    call.respond(
                        HttpStatusCode.Forbidden,
                        mapOf("error" to "AI assistant is only available for admins")
                    )
                    return@post
                }

                val request = call.receive<AiAssistantConfirmRequest>()
                if (request.requestId.isBlank()) {
                    call.respond(HttpStatusCode.BadRequest, mapOf("error" to "requestId is required"))
                    return@post
                }

                try {
                    val response = service.confirmPendingAction(
                        userId = userId,
                        orgId = orgId,
                        request = request,
                    )
                    call.respond(response)
                } catch (e: IllegalArgumentException) {
                    call.respond(HttpStatusCode.NotFound, mapOf("error" to e.message))
                } catch (e: IllegalAccessException) {
                    call.respond(HttpStatusCode.Forbidden, mapOf("error" to e.message))
                } catch (e: Exception) {
                    logger.error(e) { "Assistant confirmation failed for user $userId" }
                    call.respond(
                        HttpStatusCode.InternalServerError,
                        mapOf("error" to "Failed to confirm assistant action"),
                    )
                }
            }
        }
    }
}

private fun resolveAssistantProjectId(orgId: Int, requestedProjectId: Long?): Long? {
    return transaction {
        val requestedValid = requestedProjectId?.let { projectId ->
            Projects
                .selectAll()
                .where { (Projects.id eq projectId) and (Projects.organization_id eq orgId) }
                .firstOrNull()
                ?.get(Projects.id)
        }

        requestedValid ?: Projects
            .selectAll()
            .where { Projects.organization_id eq orgId }
            .orderBy(Projects.id to SortOrder.ASC)
            .firstOrNull()
            ?.get(Projects.id)
    }
}

private data class AssistantUserDetails(val isAdmin: Boolean, val timezone: String?)

private fun getUserDetails(userId: Int): AssistantUserDetails {
    return transaction {
        val row = Users
            .selectAll()
            .where { Users.id eq userId }
            .firstOrNull()
        AssistantUserDetails(
            isAdmin = row?.get(Users.is_admin) ?: false,
            timezone = row?.get(Users.timezone),
        )
    }
}
