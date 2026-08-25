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
import com.moneat.enterprise.ai.services.AiAssistantStreamCommand
import com.moneat.shared.models.Projects
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
private const val ADMIN_ONLY_ERROR = "AI assistant is only available for admins"

fun Route.aiAssistantRoutes(service: AiAssistantService) {
    authenticate("auth-jwt") {
        route("/v1/ai/assistant") {
            registerAssistantStreamRoute(service)
            registerAssistantConfirmationRoute(service)
            registerAssistantCancellationRoute(service)
        }
    }
}

private fun Route.registerAssistantStreamRoute(service: AiAssistantService) {
    post("/stream") {
        val context = call.requireCurrentOrg() ?: return@post
        val userDetails = getUserDetails(context.userId)
        if (!userDetails.isAdmin) {
            call.respond(HttpStatusCode.Forbidden, mapOf("error" to ADMIN_ONLY_ERROR))
            return@post
        }

        val request = call.receive<AiAssistantStreamRequest>()
        val requestError = validateStreamRequest(request)
        if (requestError != null) {
            call.respond(HttpStatusCode.BadRequest, mapOf("error" to requestError))
            return@post
        }
        val projectResolution = resolveAssistantProjectId(context.orgId, request.projectId)
        if (projectResolution.errorStatus != null) {
            call.respond(projectResolution.errorStatus, mapOf("error" to projectResolution.errorMessage))
            return@post
        }

        call.response.headers.append(HttpHeaders.CacheControl, "no-cache")
        call.response.headers.append(HttpHeaders.Connection, "keep-alive")
        call.respondTextWriter(contentType = ContentType.Text.EventStream) {
            try {
                service.streamAssistant(
                    writer = this,
                    command = AiAssistantStreamCommand(
                        userId = context.userId,
                        organizationId = context.orgId,
                        message = request.message,
                        conversationId = request.conversationId,
                        runId = request.runId,
                        projectId = projectResolution.projectId,
                        userTimezone = userDetails.timezone,
                    ),
                )
            } catch (e: Exception) {
                logger.error(e) { "Assistant stream failed for user ${context.userId}" }
                sendAssistantStreamError(this, request, e)
            }
        }
    }
}

private fun Route.registerAssistantConfirmationRoute(service: AiAssistantService) {
    post("/confirm") {
        val context = call.requireCurrentOrg() ?: return@post
        if (!getUserDetails(context.userId).isAdmin) {
            call.respond(HttpStatusCode.Forbidden, mapOf("error" to ADMIN_ONLY_ERROR))
            return@post
        }

        val request = call.receive<AiAssistantConfirmRequest>()
        if (request.requestId.isBlank()) {
            call.respond(HttpStatusCode.BadRequest, mapOf("error" to "requestId is required"))
            return@post
        }

        try {
            call.respond(service.confirmPendingAction(context.userId, context.orgId, request))
        } catch (e: IllegalArgumentException) {
            call.respond(HttpStatusCode.NotFound, mapOf("error" to e.message))
        } catch (e: IllegalAccessException) {
            call.respond(HttpStatusCode.Forbidden, mapOf("error" to e.message))
        } catch (e: IllegalStateException) {
            call.respond(HttpStatusCode.Conflict, mapOf("error" to e.message))
        } catch (e: Exception) {
            logger.error(e) { "Assistant confirmation failed for user ${context.userId}" }
            call.respond(HttpStatusCode.InternalServerError, mapOf("error" to "Failed to confirm assistant action"))
        }
    }
}

private fun Route.registerAssistantCancellationRoute(service: AiAssistantService) {
    post("/runs/{runId}/cancel") {
        val context = call.requireCurrentOrg() ?: return@post
        if (!getUserDetails(context.userId).isAdmin) {
            call.respond(HttpStatusCode.Forbidden, mapOf("error" to ADMIN_ONLY_ERROR))
            return@post
        }
        val runId = call.parameters["runId"]?.trim().orEmpty()
        if (runId.toUuidOrNull() == null) {
            call.respond(HttpStatusCode.BadRequest, mapOf("error" to "runId must be a UUID"))
            return@post
        }
        if (service.cancelRun(context.userId, context.orgId, runId)) {
            call.respond(HttpStatusCode.Accepted, mapOf("status" to "cancelled", "runId" to runId))
        } else {
            call.respond(
                HttpStatusCode.NotFound,
                mapOf("error" to "Assistant run not found or already finished"),
            )
        }
    }
}

private fun validateStreamRequest(request: AiAssistantStreamRequest): String? = when {
    request.message.isBlank() -> "Message cannot be empty"
    request.conversationId != null && request.conversationId.toUuidOrNull() == null ->
        "conversationId must be a UUID"
    request.runId != null && request.runId.toUuidOrNull() == null -> "runId must be a UUID"
    else -> null
}

private fun sendAssistantStreamError(
    writer: java.io.Writer,
    request: AiAssistantStreamRequest,
    error: Exception,
) {
    AiAssistantService.sendSse(
        writer,
        json.encodeToString(
            AssistantErrorEvent.serializer(),
            AssistantErrorEvent(error = "Assistant stream failed: ${error.message}"),
        ),
    )
    AiAssistantService.sendSse(
        writer,
        json.encodeToString(
            AssistantDoneEvent.serializer(),
            AssistantDoneEvent(conversationId = request.conversationId.orEmpty(), runId = request.runId),
        ),
    )
}

private fun resolveAssistantProjectId(orgId: Int, requestedProjectId: String?): AssistantProjectResolution {
    val normalized = requestedProjectId?.trim()?.takeIf { it.isNotBlank() }
    if (normalized != null) {
        val resourceId =
            normalized.toUuidOrNull()
                ?: return AssistantProjectResolution.badRequest("projectId must be a UUID")

        val projectId = transaction {
            Projects
                .selectAll()
                .where { (Projects.resource_id eq resourceId) and (Projects.organization_id eq orgId) }
                .firstOrNull()
                ?.get(Projects.id)
        }

        return projectId?.let { AssistantProjectResolution(projectId = it) }
            ?: AssistantProjectResolution.notFound("Project not found")
    }

    return AssistantProjectResolution(
        projectId = transaction {
            Projects
                .selectAll()
                .where { Projects.organization_id eq orgId }
                .orderBy(Projects.id to SortOrder.ASC)
                .firstOrNull()
                ?.get(Projects.id)
        }
    )
}

private data class AssistantProjectResolution(
    val projectId: Long? = null,
    val errorStatus: HttpStatusCode? = null,
    val errorMessage: String = ""
) {
    companion object {
        fun badRequest(message: String): AssistantProjectResolution =
            AssistantProjectResolution(errorStatus = HttpStatusCode.BadRequest, errorMessage = message)

        fun notFound(message: String): AssistantProjectResolution =
            AssistantProjectResolution(errorStatus = HttpStatusCode.NotFound, errorMessage = message)
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
