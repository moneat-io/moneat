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

package com.moneat.analytics.routes

import com.moneat.analytics.models.EnrichedAnalyticsEvent
import com.moneat.analytics.models.ServerAnalyticsEvent
import com.moneat.analytics.models.ServerAnalyticsEventPayload
import com.moneat.analytics.services.AnalyticsIngestionWorker
import com.moneat.config.RedisConfig
import com.moneat.otlp.OtlpAuth
import com.moneat.otlp.services.OtlpApiKeyService
import com.moneat.shared.models.Projects
import com.moneat.utils.suspendRunCatching
import io.ktor.http.HttpHeaders
import io.ktor.http.HttpStatusCode
import io.ktor.server.application.ApplicationCall
import io.ktor.server.request.header
import io.ktor.server.request.receive
import io.ktor.server.response.respond
import io.ktor.server.routing.Route
import io.ktor.server.routing.post
import io.ktor.server.routing.route
import kotlinx.serialization.json.Json
import mu.KotlinLogging
import org.jetbrains.exposed.v1.core.eq
import org.jetbrains.exposed.v1.jdbc.selectAll
import org.jetbrains.exposed.v1.jdbc.transactions.transaction
import org.koin.core.context.GlobalContext

private val serverIngestLogger = KotlinLogging.logger {}
private val serverIngestJson = Json { ignoreUnknownKeys = true }

private const val MAX_SERVER_ANALYTICS_EVENTS = 500

fun Route.analyticsServerIngestRoutes(
    otlpApiKeyService: OtlpApiKeyService = GlobalContext.get().get(),
    enqueueEvents: (List<String>) -> Unit = { messages ->
        if (messages.isNotEmpty()) {
            RedisConfig.sync().lpush(AnalyticsIngestionWorker.QUEUE_KEY, *messages.toTypedArray())
        }
    },
) {
    route("/v1/analytics/{projectId}") {
        post("/events") {
            val projectId = call.parameters["projectId"]?.toLongOrNull()
            if (projectId == null) {
                call.respond(HttpStatusCode.BadRequest, "Invalid project ID")
                return@post
            }

            val organizationId = authorizeServerAnalyticsIngest(call, projectId, otlpApiKeyService) ?: return@post
            val payload = receiveServerAnalyticsPayload(call) ?: return@post
            val events = validateServerAnalyticsEvents(call, payload.events) ?: return@post

            try {
                val messages = events
                    .map { event -> createServerAnalyticsEvent(projectId, event) }
                    .map { event -> serverIngestJson.encodeToString(event) }
                enqueueEvents(messages)
            } catch (e: RuntimeException) {
                serverIngestLogger.error(e) {
                    "Failed to enqueue server analytics events for project $projectId organization $organizationId"
                }
                call.respond(HttpStatusCode.InternalServerError, "Failed to enqueue events")
                return@post
            }

            call.respond(HttpStatusCode.Accepted, "ok")
        }
    }
}

private suspend fun authorizeServerAnalyticsIngest(
    call: ApplicationCall,
    projectId: Long,
    otlpApiKeyService: OtlpApiKeyService,
): Int? {
    val token = OtlpAuth.extractBearerToken(call.request.header(HttpHeaders.Authorization))
    if (token == null) {
        call.respond(HttpStatusCode.Unauthorized, "Missing Bearer token")
        return null
    }

    val organizationId = otlpApiKeyService.validateKey(token)
    if (organizationId == null) {
        call.respond(HttpStatusCode.Unauthorized, "Invalid API key")
        return null
    }

    val projectOrganizationId = getProjectOrganizationId(projectId)
    if (projectOrganizationId == null || projectOrganizationId != organizationId) {
        call.respond(HttpStatusCode.Forbidden, "Project is not available for this API key")
        return null
    }

    return organizationId
}

private suspend fun receiveServerAnalyticsPayload(
    call: ApplicationCall,
): ServerAnalyticsEventPayload? {
    return suspendRunCatching {
        call.receive<ServerAnalyticsEventPayload>()
    }.onFailure { e ->
        serverIngestLogger.debug { "Invalid server analytics payload: ${e.message}" }
        call.respond(HttpStatusCode.BadRequest, "Invalid payload")
    }.getOrNull()
}

private suspend fun validateServerAnalyticsEvents(
    call: ApplicationCall,
    events: List<ServerAnalyticsEvent>,
): List<ServerAnalyticsEvent>? {
    if (events.isEmpty()) {
        call.respond(HttpStatusCode.BadRequest, "At least one event is required")
        return null
    }
    if (events.size > MAX_SERVER_ANALYTICS_EVENTS) {
        call.respond(HttpStatusCode.BadRequest, "Too many events")
        return null
    }
    if (events.any { it.name.isBlank() || it.userId.isBlank() }) {
        call.respond(HttpStatusCode.BadRequest, "Event name and user_id are required")
        return null
    }
    return events
}

private fun createServerAnalyticsEvent(
    projectId: Long,
    event: ServerAnalyticsEvent,
): EnrichedAnalyticsEvent =
    EnrichedAnalyticsEvent(
        projectId = projectId,
        sessionId = event.sessionId.ifBlank { event.userId },
        userId = event.userId,
        eventName = event.name,
        source = "server",
        hostname = "",
        pathname = "",
        referrer = "",
        referrerSource = "",
        utmSource = "",
        utmMedium = "",
        utmCampaign = "",
        utmTerm = "",
        utmContent = "",
        countryCode = "",
        subdivision = "",
        city = "",
        browser = "",
        browserVersion = "",
        os = "",
        osVersion = "",
        deviceType = "",
        screenWidth = 0,
        props = event.props,
        timestamp = event.timestamp ?: System.currentTimeMillis(),
    )

private fun getProjectOrganizationId(projectId: Long): Int? =
    transaction {
        Projects
            .selectAll()
            .where { Projects.id eq projectId }
            .firstOrNull()
            ?.get(Projects.organization_id)
    }
