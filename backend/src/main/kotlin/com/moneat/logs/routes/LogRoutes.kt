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

package com.moneat.logs.routes

import com.moneat.auth.currentOrgIdOrNull
import kotlinx.serialization.SerializationException
import java.io.IOException
import java.io.Writer

import com.auth0.jwt.JWT
import com.auth0.jwt.algorithms.Algorithm
import com.moneat.billing.services.BillingQuotaService
import com.moneat.billing.services.QuotaExceededResponse
import com.moneat.datadog.decompression.DecompressionService
import com.moneat.dashboards.services.DashboardAlertService
import com.moneat.events.services.EventService
import com.moneat.enterprise.FeatureRegistry
import com.moneat.logs.LogPermissions
import com.moneat.logs.models.CreateLogIndexRequest
import com.moneat.logs.models.LogQueryRequest
import com.moneat.logs.models.LogTailFilters
import com.moneat.logs.models.UpdateLogIndexRequest
import com.moneat.logs.services.LogIndexService
import com.moneat.logs.services.LogManagementService
import com.moneat.logs.services.LogService
import com.moneat.otlp.OtlpAuth
import com.moneat.otlp.models.CreateOtlpApiKeyRequest
import com.moneat.otlp.models.CreateOtlpServiceMappingRequest
import com.moneat.otlp.services.OtlpApiKeyService
import com.moneat.otlp.services.OtlpServiceDescriptor
import com.moneat.otlp.services.OtlpServiceRoutingService
import com.moneat.otlp.services.OtlpSignalType
import com.moneat.org.services.OrgMembershipService
import com.moneat.org.services.OrgRole
import com.moneat.plugins.getDemoEpochMs
import com.moneat.plugins.isDemoUser
import com.moneat.shared.services.ProjectIdResolver
import com.moneat.utils.ErrorResponse
import com.moneat.utils.suspendRunCatching
import io.ktor.http.ContentType
import io.ktor.http.HttpHeaders
import io.ktor.http.HttpStatusCode
import io.ktor.server.application.ApplicationCall
import io.ktor.server.auth.authenticate
import io.ktor.server.auth.jwt.JWTPrincipal
import io.ktor.server.auth.principal
import io.ktor.server.request.header
import io.ktor.server.request.receive
import io.ktor.server.response.respond
import io.ktor.server.response.respondText
import io.ktor.server.response.respondTextWriter
import io.ktor.server.routing.Route
import io.ktor.server.routing.delete
import io.ktor.server.routing.get
import io.ktor.server.routing.post
import io.ktor.server.routing.put
import io.ktor.server.routing.route
import io.lettuce.core.RedisClient
import io.lettuce.core.RedisURI
import io.lettuce.core.pubsub.RedisPubSubAdapter
import kotlinx.serialization.json.Json
import mu.KotlinLogging
import org.koin.core.context.GlobalContext
import java.time.Instant
import java.util.concurrent.LinkedBlockingQueue
import java.util.concurrent.TimeUnit

private val logger = KotlinLogging.logger {}
private val json = Json { ignoreUnknownKeys = true }

private const val MILLIS_IN_24_HOURS = 24L * 60 * 60 * 1000
private const val SSE_POLL_TIMEOUT_SECONDS = 15L
private const val INVALID_TOKEN_MESSAGE = "Invalid token"

fun Route.logRoutes(
    logService: LogService = GlobalContext.get().get(),
    otlpApiKeyService: OtlpApiKeyService = GlobalContext.get().get(),
    logIndexService: LogIndexService = GlobalContext.get().get(),
    otlpServiceRoutingService: OtlpServiceRoutingService = GlobalContext.get().get(),
    logManagementService: LogManagementService = GlobalContext.get().get(),
    membershipService: OrgMembershipService = GlobalContext.get().get(),
    dashboardAlertService: DashboardAlertService = GlobalContext.get().get(),
) {
    route("/v1") {
        authenticate("auth-jwt") {
            registerOtlpApiKeyRoutes(otlpApiKeyService)
            registerOtlpServiceRoutingRoutes(otlpServiceRoutingService)
            registerLogIndexRoutes(logIndexService, membershipService)
            registerLogQueryRoutes(logService)
            registerLogManagementRoutes(
                logManagementService = logManagementService,
                logIndexService = logIndexService,
                logService = logService,
                membershipService = membershipService,
                dashboardAlertService = dashboardAlertService
            )
        }
        registerLogTailRoute(logService, membershipService)
    }
}

private data class LogTimeRange(
    val from: String?,
    val to: String?,
)

private fun Route.registerOtlpApiKeyRoutes(otlpApiKeyService: OtlpApiKeyService) {
    post("/logs/api-keys") { call.createOtlpApiKey(otlpApiKeyService) }
    get("/logs/api-keys") { call.listOtlpApiKeys(otlpApiKeyService) }
    delete("/logs/api-keys/{id}") { call.deleteOtlpApiKey(otlpApiKeyService) }
}

private suspend fun ApplicationCall.createOtlpApiKey(otlpApiKeyService: OtlpApiKeyService) {
    val organizationId = requiredOrganizationIdOrRespond() ?: return
    val request = receive<CreateOtlpApiKeyRequest>()
    val name = request.name.trim()
    if (name.isBlank()) {
        respond(HttpStatusCode.BadRequest, ErrorResponse("Name is required"))
        return
    }

    val response =
        otlpApiKeyService.createKey(
            organizationId = organizationId,
            name = name,
            createdBy = requiredUserId()
        )
    respond(HttpStatusCode.Created, response)
}

private suspend fun ApplicationCall.listOtlpApiKeys(otlpApiKeyService: OtlpApiKeyService) {
    val organizationId = requiredOrganizationIdOrRespond() ?: return
    val keys = otlpApiKeyService.listKeys(organizationId)
    respond(HttpStatusCode.OK, mapOf("keys" to keys))
}

private suspend fun ApplicationCall.deleteOtlpApiKey(otlpApiKeyService: OtlpApiKeyService) {
    val id = parameters["id"]?.toIntOrNull()
    if (id == null) {
        respond(HttpStatusCode.BadRequest, ErrorResponse("Invalid key ID"))
        return
    }

    val organizationId = requiredOrganizationIdOrRespond() ?: return
    val deleted = otlpApiKeyService.deleteKey(organizationId = organizationId, keyId = id)
    if (!deleted) {
        respond(HttpStatusCode.NotFound, ErrorResponse("Key not found"))
        return
    }
    respond(HttpStatusCode.NoContent)
}

private fun Route.registerOtlpServiceRoutingRoutes(otlpServiceRoutingService: OtlpServiceRoutingService) {
    get("/otlp/services") { call.listOtlpObservedServices(otlpServiceRoutingService) }
    post("/otlp/service-mappings") { call.upsertOtlpServiceMapping(otlpServiceRoutingService) }
    delete("/otlp/service-mappings/{id}") { call.deleteOtlpServiceMapping(otlpServiceRoutingService) }
}

private suspend fun ApplicationCall.listOtlpObservedServices(
    otlpServiceRoutingService: OtlpServiceRoutingService
) {
    val organizationId = requiredOrganizationIdOrRespond() ?: return
    val services = otlpServiceRoutingService.listObservedServices(organizationId)
    respond(HttpStatusCode.OK, mapOf("services" to services))
}

private suspend fun ApplicationCall.upsertOtlpServiceMapping(
    otlpServiceRoutingService: OtlpServiceRoutingService
) {
    val response = otlpServiceRoutingService.upsertMapping(
        requiredOrganizationIdOrRespond() ?: return,
        receive<CreateOtlpServiceMappingRequest>()
    )
    if (response == null) {
        respond(
            HttpStatusCode.BadRequest,
            ErrorResponse("Valid service name and organization project are required")
        )
        return
    }
    respond(HttpStatusCode.OK, response)
}

private suspend fun ApplicationCall.deleteOtlpServiceMapping(
    otlpServiceRoutingService: OtlpServiceRoutingService
) {
    val id = parameters["id"]?.toIntOrNull()
    if (id == null) {
        respond(HttpStatusCode.BadRequest, ErrorResponse("Invalid mapping ID"))
        return
    }

    val organizationId = requiredOrganizationIdOrRespond() ?: return
    val deleted = otlpServiceRoutingService.deleteMapping(organizationId, id)
    if (!deleted) {
        respond(HttpStatusCode.NotFound, ErrorResponse("Mapping not found"))
        return
    }
    respond(HttpStatusCode.NoContent)
}

private fun Route.registerLogIndexRoutes(
    logIndexService: LogIndexService,
    membershipService: OrgMembershipService
) {
    get("/logs/indexes") { call.listLogIndexes(logIndexService) }
    post("/logs/indexes") {
        if (call.ensureLogIndexAccess(membershipService)) call.createLogIndex(logIndexService)
    }
    put("/logs/indexes/{id}") {
        if (call.ensureLogIndexAccess(membershipService)) call.updateLogIndex(logIndexService)
    }
    delete("/logs/indexes/{id}") {
        if (call.ensureLogIndexAccess(membershipService)) call.deleteLogIndex(logIndexService)
    }
    post("/logs/indexes/test") {
        if (call.ensureLogIndexAccess(membershipService)) call.testLogIndex(logIndexService)
    }
}

private suspend fun ApplicationCall.listLogIndexes(logIndexService: LogIndexService) {
    val organizationId = requiredOrganizationIdOrRespond() ?: return
    val indexes = logIndexService.list(organizationId)
    respond(HttpStatusCode.OK, mapOf("indexes" to indexes))
}

private suspend fun ApplicationCall.createLogIndex(logIndexService: LogIndexService) {
    val organizationId = requiredOrganizationIdOrRespond() ?: return
    val request = receive<CreateLogIndexRequest>()
    if (request.name.isBlank()) {
        respond(HttpStatusCode.BadRequest, ErrorResponse("Name is required"))
        return
    }
    val index = logIndexService.create(organizationId, request)
    respond(HttpStatusCode.Created, index)
}

private suspend fun ApplicationCall.updateLogIndex(logIndexService: LogIndexService) {
    val organizationId = requiredOrganizationIdOrRespond() ?: return
    val id = parameters["id"]?.toIntOrNull()
    if (id == null) {
        respond(HttpStatusCode.BadRequest, ErrorResponse("Invalid index ID"))
        return
    }
    val updated = logIndexService.update(organizationId, id, receive<UpdateLogIndexRequest>())
    if (updated == null) {
        respond(HttpStatusCode.NotFound, ErrorResponse("Index not found"))
        return
    }
    respond(HttpStatusCode.OK, updated)
}

private suspend fun ApplicationCall.deleteLogIndex(logIndexService: LogIndexService) {
    val organizationId = requiredOrganizationIdOrRespond() ?: return
    val id = parameters["id"]?.toIntOrNull()
    if (id == null) {
        respond(HttpStatusCode.BadRequest, ErrorResponse("Invalid index ID"))
        return
    }
    val deleted = logIndexService.delete(organizationId, id)
    if (!deleted) {
        respond(HttpStatusCode.NotFound, ErrorResponse("Index not found"))
        return
    }
    respond(HttpStatusCode.NoContent)
}

private suspend fun ApplicationCall.testLogIndex(logIndexService: LogIndexService) {
    val organizationId = requiredOrganizationIdOrRespond() ?: return
    val body = receive<Map<String, String>>()
    val result = logIndexService.testFilter(organizationId, body["filter_query"] ?: "")
    respond(HttpStatusCode.OK, result)
}

private fun Route.registerLogQueryRoutes(logService: LogService) {
    get("/logs") { call.queryLogs(logService) }
    get("/logs/tag-values") { call.getLogTagValues(logService) }
    get("/logs/filters") { call.getLogFilters(logService) }
    get("/logs/aggregate") { call.aggregateLogs(logService) }
    get("/logs/top") { call.getTopLogValues(logService) }
    get("/logs/export") { call.exportLogs(logService) }
}

private suspend fun ApplicationCall.queryLogs(logService: LogService) {
    val orgId = requiredOrganizationIdOrRespond()?.toLong() ?: return
    val range = demoAwareLogTimeRange()
    val logRequest =
        LogQueryRequest(
            limit = request.queryParameters["limit"]?.toIntOrNull() ?: 100,
            cursor = request.queryParameters["cursor"],
            query = request.queryParameters["q"] ?: request.queryParameters["query"],
            levels = parseLevelQueryParams(this),
            service = request.queryParameters["service"],
            environment = request.queryParameters["environment"],
            from = range.from,
            to = range.to,
            tags = parseTagQueryParams(this),
            traceId = request.queryParameters["traceId"],
            excludeService = request.queryParameters["excludeService"],
            excludeEnvironment = request.queryParameters["excludeEnvironment"],
            excludeContainerName = request.queryParameters["excludeContainerName"],
            excludeTags = parseExcludeTagQueryParams(this)
        )

    val result = logService.queryLogs(orgId, logRequest)
    respond(HttpStatusCode.OK, result)
}

private suspend fun ApplicationCall.getLogTagValues(logService: LogService) {
    val key = request.queryParameters["key"]
    if (key.isNullOrBlank()) {
        respond(HttpStatusCode.BadRequest, ErrorResponse("Missing tag key parameter"))
        return
    }

    val result =
        logService.getTagValues(
            organizationId = requiredOrganizationIdOrRespond()?.toLong() ?: return,
            key = key,
            from = request.queryParameters["from"],
            to = request.queryParameters["to"],
            limit = request.queryParameters["limit"]?.toIntOrNull() ?: 50
        )
    respond(HttpStatusCode.OK, result)
}

private suspend fun ApplicationCall.getLogFilters(logService: LogService) {
    val organizationId = requiredOrganizationIdOrRespond()?.toLong() ?: return
    val range = demoAwareLogTimeRange()
    val result =
        logService.getFilterOptionsWithCounts(
            organizationId = organizationId,
            from = range.from,
            to = range.to
        )
    respond(HttpStatusCode.OK, result)
}

private suspend fun ApplicationCall.aggregateLogs(logService: LogService) {
    val orgId = requiredOrganizationIdOrRespond()?.toLong() ?: return
    val range = demoAwareLogTimeRange()
    val result =
        logService.aggregateLogs(
            organizationId = orgId,
            from = range.from,
            to = range.to,
            interval = request.queryParameters["interval"],
            query = request.queryParameters["q"] ?: request.queryParameters["query"],
            levels = parseLevelQueryParams(this),
            service = request.queryParameters["service"],
            environment = request.queryParameters["environment"],
            tags = parseTagQueryParams(this),
            excludeService = request.queryParameters["excludeService"],
            excludeEnvironment = request.queryParameters["excludeEnvironment"],
            excludeContainerName = request.queryParameters["excludeContainerName"],
            excludeTags = parseExcludeTagQueryParams(this),
            groupBy = request.queryParameters["groupBy"]
        )
    logger.debug {
        "Aggregate logs response for org $orgId: ${result.buckets.size} buckets, " +
            "totalCount=${result.totalCount}, interval=${result.interval}, from=${range.from}, " +
            "to=${range.to}, isDemo=${isDemoUser()}"
    }
    respond(HttpStatusCode.OK, result)
}

private suspend fun ApplicationCall.getTopLogValues(logService: LogService) {
    val field = request.queryParameters["field"]
    if (field.isNullOrBlank()) {
        respond(HttpStatusCode.BadRequest, ErrorResponse("Missing field parameter"))
        return
    }

    val range = demoAwareLogTimeRange()
    val result =
        logService.topValues(
            organizationId = requiredOrganizationIdOrRespond()?.toLong() ?: return,
            field = field,
            limit = request.queryParameters["limit"]?.toIntOrNull() ?: 10,
            from = range.from,
            to = range.to,
            query = request.queryParameters["q"] ?: request.queryParameters["query"],
            levels = parseLevelQueryParams(this),
            service = request.queryParameters["service"],
            environment = request.queryParameters["environment"],
            tags = parseTagQueryParams(this),
            excludeService = request.queryParameters["excludeService"],
            excludeEnvironment = request.queryParameters["excludeEnvironment"],
            excludeContainerName = request.queryParameters["excludeContainerName"],
            excludeTags = parseExcludeTagQueryParams(this)
        )
    respond(HttpStatusCode.OK, result)
}

private suspend fun ApplicationCall.exportLogs(logService: LogService) {
    val organizationId = requiredOrganizationIdOrRespond()?.toLong() ?: return
    val csv =
        logService.exportCsv(
            organizationId = organizationId,
            from = request.queryParameters["from"],
            to = request.queryParameters["to"],
            query = request.queryParameters["q"] ?: request.queryParameters["query"],
            levels = parseLevelQueryParams(this),
            service = request.queryParameters["service"],
            environment = request.queryParameters["environment"],
            tags = parseTagQueryParams(this),
            excludeService = request.queryParameters["excludeService"],
            excludeEnvironment = request.queryParameters["excludeEnvironment"],
            excludeContainerName = request.queryParameters["excludeContainerName"],
            excludeTags = parseExcludeTagQueryParams(this),
            limit = request.queryParameters["limit"]?.toIntOrNull() ?: 5000
        )

    response.headers.append(HttpHeaders.ContentDisposition, "attachment; filename=\"logs-export.csv\"")
    respondText(csv, ContentType.Text.CSV)
}

private fun Route.registerLogTailRoute(
    logService: LogService,
    membershipService: OrgMembershipService
) {
    get("/logs/tail") { call.tailLogs(logService, membershipService) }
}

private suspend fun ApplicationCall.tailLogs(
    logService: LogService,
    membershipService: OrgMembershipService
) {
    val identity = resolveTailIdentity()
    if (identity == null) {
        respond(HttpStatusCode.Unauthorized, ErrorResponse("Unauthorized"))
        return
    }
    val (userId, orgId) = identity
    if (!hasLogAccess(membershipService, orgId.toInt(), userId, LogPermissions.LIVE_TAIL)) {
        respond(HttpStatusCode.Forbidden, ErrorResponse("Insufficient permissions"))
        return
    }

    val filters =
        LogTailFilters(
            query = request.queryParameters["q"] ?: request.queryParameters["query"],
            levels = parseLevelQueryParams(this).map { it.lowercase() }.toSet(),
            service = request.queryParameters["service"],
            environment = request.queryParameters["environment"],
            containerName = request.queryParameters["containerName"] ?: request.queryParameters["container_name"],
            tags = parseTagQueryParams(this),
            excludeService = request.queryParameters["excludeService"],
            excludeEnvironment = request.queryParameters["excludeEnvironment"],
            excludeContainerName = request.queryParameters["excludeContainerName"]
                ?: request.queryParameters["exclude_container_name"],
            excludeTags = parseExcludeTagQueryParams(this)
        )
    val redisUrl = application.environment.config.property("redis.url").getString()
    val channel = logService.liveChannel(orgId)
    val queue = LinkedBlockingQueue<String>()

    val client = RedisClient.create(RedisURI.create(redisUrl))
    val connection = client.connectPubSub()
    val listener = createLogTailListener(channel, queue)
    connection.addListener(listener)
    connection.sync().subscribe(channel)

    response.headers.append(HttpHeaders.CacheControl, "no-cache")
    response.headers.append(HttpHeaders.Connection, "keep-alive")
    respondTextWriter(contentType = ContentType.Text.EventStream) {
        try {
            streamLogTailEvents(logService, queue, filters)
        } catch (e: SerializationException) {
            logger.debug { "SSE log tail disconnected for org $orgId: ${e.message}" }
        } catch (e: IOException) {
            logger.debug { "SSE log tail disconnected for org $orgId: ${e.message}" }
        } catch (e: IllegalStateException) {
            logger.debug { "SSE log tail disconnected for org $orgId: ${e.message}" }
        } catch (e: IllegalArgumentException) {
            logger.debug { "SSE log tail disconnected for org $orgId: ${e.message}" }
        } finally {
            closeLogTailConnection(connection, listener, channel)
            client.shutdown()
        }
    }
}

private fun Writer.streamLogTailEvents(
    logService: LogService,
    queue: LinkedBlockingQueue<String>,
    filters: LogTailFilters
) {
    write(": connected\n\n")
    flush()

    while (true) {
        writeNextLogTailEvent(logService, queue, filters)
    }
}

private fun Writer.writeNextLogTailEvent(
    logService: LogService,
    queue: LinkedBlockingQueue<String>,
    filters: LogTailFilters
) {
    val next = queue.poll(SSE_POLL_TIMEOUT_SECONDS, TimeUnit.SECONDS)
    if (next == null) {
        write(": heartbeat\n\n")
        flush()
        return
    }

    val parsed = logService.parseLiveLog(next) ?: return
    if (!logService.matchesTailFilters(parsed, filters)) return

    write("data: $next\n\n")
    flush()
}

private fun createLogTailListener(
    channel: String,
    queue: LinkedBlockingQueue<String>
): RedisPubSubAdapter<String, String> =
    object : RedisPubSubAdapter<String, String>() {
        override fun message(ch: String, message: String) {
            if (ch == channel) {
                queue.offer(message)
            }
        }
    }

private fun closeLogTailConnection(
    connection: io.lettuce.core.pubsub.StatefulRedisPubSubConnection<String, String>,
    listener: RedisPubSubAdapter<String, String>,
    channel: String,
) {
    try {
        connection.sync().unsubscribe(channel)
    } catch (_: SerializationException) {
        // Ignored: cleanup failure should not mask SSE disconnect
    } catch (_: IOException) {
        // Ignored: cleanup failure should not mask SSE disconnect
    } catch (_: IllegalStateException) {
        // Ignored: cleanup failure should not mask SSE disconnect
    } catch (_: IllegalArgumentException) {
        // Ignored: cleanup failure should not mask SSE disconnect
    }
    connection.removeListener(listener)
    connection.close()
}

private fun ApplicationCall.demoAwareLogTimeRange(): LogTimeRange {
    val requestedFrom = request.queryParameters["from"]
    val requestedTo = request.queryParameters["to"]
    val demoEpochMs = getDemoEpochMs()
    if (!isDemoUser() || demoEpochMs == null) {
        return LogTimeRange(requestedFrom, requestedTo)
    }

    return LogTimeRange(
        from = requestedFrom ?: Instant.ofEpochMilli(demoEpochMs - MILLIS_IN_24_HOURS).toString(),
        to = requestedTo ?: Instant.ofEpochMilli(demoEpochMs).toString()
    )
}

private fun ApplicationCall.requiredUserId(): Int =
    principal<JWTPrincipal>()!!.payload.getClaim("userId").asInt()

private suspend fun ApplicationCall.requiredOrganizationIdOrRespond(): Int? {
    val organizationId = principal<JWTPrincipal>()?.currentOrgIdOrNull()
    if (organizationId == null) {
        respond(HttpStatusCode.Unauthorized, ErrorResponse(INVALID_TOKEN_MESSAGE))
    }
    return organizationId
}

private suspend fun ApplicationCall.ensureLogIndexAccess(membershipService: OrgMembershipService): Boolean {
    val organizationId = requiredOrganizationIdOrRespond() ?: return false
    val allowed = hasLogAccess(
        membershipService = membershipService,
        organizationId = organizationId,
        userId = requiredUserId(),
        permission = LogPermissions.MANAGE
    )
    if (!allowed) {
        respond(HttpStatusCode.Forbidden, ErrorResponse("Insufficient permissions"))
    }
    return allowed
}

private suspend fun hasLogAccess(
    membershipService: OrgMembershipService,
    organizationId: Int,
    userId: Int,
    permission: String
): Boolean {
    val granular = FeatureRegistry.getPermissionBridge()?.hasPermission(organizationId, userId, permission)
    return granular ?: suspendRunCatching {
        membershipService.requireRole(organizationId, userId, OrgRole.ADMIN)
        true
    }.getOrElse { false }
}

private fun ApplicationCall.resolveTailIdentity(): Pair<Int, Long>? {
    val principal = principal<JWTPrincipal>()
    val principalUserId = principal?.payload?.getClaim("userId")?.asInt()
    val principalOrgId = principal?.currentOrgIdOrNull()?.toLong()
    return if (principalUserId != null && principalOrgId != null) {
        principalUserId to principalOrgId
    } else {
        authenticateTailRequest(this)
    }
}

private fun parseLevelQueryParams(call: ApplicationCall): List<String> {
    return call.request.queryParameters
        .getAll("level")
        .orEmpty()
        .flatMap { item -> item.split(",") }
        .map { it.trim().lowercase() }
        .filter { it.isNotBlank() }
        .distinct()
}

private fun parseTagQueryParams(call: ApplicationCall): Map<String, String> {
    return call.request.queryParameters
        .getAll("tag")
        .orEmpty()
        .mapNotNull { token ->
            val idx = token.indexOf(':')
            if (idx <= 0) return@mapNotNull null
            val key = token.substring(0, idx).trim()
            val value = token.substring(idx + 1).trim()
            if (key.isBlank()) return@mapNotNull null
            key to value
        }.toMap()
}

private fun parseExcludeTagQueryParams(call: ApplicationCall): Map<String, String> {
    return call.request.queryParameters
        .getAll("excludeTag")
        .orEmpty()
        .mapNotNull { token ->
            val idx = token.indexOf(':')
            if (idx <= 0) return@mapNotNull null
            val key = token.substring(0, idx).trim()
            val value = token.substring(idx + 1).trim()
            if (key.isBlank()) return@mapNotNull null
            key to value
        }.toMap()
}

private fun authenticateTailRequest(call: ApplicationCall): Pair<Int, Long>? {
    val authHeader = call.request.header(HttpHeaders.Authorization)
    val bearerPrefix = "Bearer "
    val bearerToken =
        authHeader
            ?.takeIf { it.startsWith(bearerPrefix, ignoreCase = true) }
            ?.substring(bearerPrefix.length)
            ?.trim()
    // Authorization header or cookie only (no query param to avoid leaking secrets)
    val token = bearerToken ?: call.request.cookies["auth_token"]

    if (token.isNullOrBlank()) return null

    return suspendRunCatching {
        val config = call.application.environment.config
        val secret = config.property("jwt.secret").getString()
        val issuer = config.property("jwt.issuer").getString()
        val audience = config.property("jwt.audience").getString()

        val verifier =
            JWT
                .require(Algorithm.HMAC256(secret))
                .withIssuer(issuer)
                .withAudience(audience)
                .build()

        val decoded = verifier.verify(token)
        val userId = decoded.getClaim("userId").asInt()
        val orgId = decoded.currentOrgIdOrNull()?.toLong() ?: return@suspendRunCatching null
        Pair(userId, orgId)
    }.getOrElse { _ ->
        null
    }
}

fun Route.logIngestRoutes(
    logService: LogService = GlobalContext.get().get(),
    quotaService: BillingQuotaService = GlobalContext.get().get(),
    otlpApiKeyService: OtlpApiKeyService = GlobalContext.get().get(),
    eventService: EventService = GlobalContext.get().get(),
    otlpServiceRoutingService: OtlpServiceRoutingService = GlobalContext.get().get(),
    projectIdResolver: ProjectIdResolver = ProjectIdResolver(),
) {
    route("/v1") {
        // Standard OTLP/HTTP path alias
        post("/logs") {
            handleOtlpLogIngest(
                call,
                logService,
                quotaService,
                otlpApiKeyService,
                eventService,
                otlpServiceRoutingService,
                projectIdResolver
            )
        }
        // Moneat convention path
        post("/logs/otlp") {
            handleOtlpLogIngest(
                call,
                logService,
                quotaService,
                otlpApiKeyService,
                eventService,
                otlpServiceRoutingService,
                projectIdResolver
            )
        }

        post("/logs/ingest") {
            val organizationId = OtlpAuth.extractOrgId(call, otlpApiKeyService)
            if (organizationId == null) {
                call.respond(HttpStatusCode.Unauthorized, ErrorResponse("Missing or invalid OTLP API key"))
                return@post
            }

            val bodyBytes = call.receive<ByteArray>()
            val encoding = call.request.header(HttpHeaders.ContentEncoding)
            val payloadBytes = suspendRunCatching {
                DecompressionService.decompress(bodyBytes, encoding)
            }.getOrElse { _ ->
                call.respond(HttpStatusCode.BadRequest, ErrorResponse("Failed to decompress request body"))
                return@post
            }

            val entries =
                suspendRunCatching {
                    json.decodeFromString<List<com.moneat.logs.models.LogIngestEntry>>(
                        payloadBytes.decodeToString()
                    )
                }.getOrElse { _ ->
                    call.respond(HttpStatusCode.BadRequest, ErrorResponse("Invalid log payload"))
                    return@post
                }

            if (entries.isEmpty()) {
                call.respond(HttpStatusCode.Accepted, mapOf("accepted" to 0))
                return@post
            }

            if (quotaService.isEnforcementEnabled()) {
                val billableBytes = logService.estimateBillableBytes(entries)
                val reservation =
                    quotaService.reserveUnits(
                        organizationId = organizationId,
                        requestedUnits = entries.size,
                        eventType = "log",
                        requestedBytes = billableBytes
                    )
                if (!reservation.allowed) {
                    call.respond(
                        HttpStatusCode.TooManyRequests,
                        QuotaExceededResponse(reason = reservation.reason, usage = reservation.usage)
                    )
                    return@post
                }
            }

            val queueKey =
                call.application.environment.config
                    .propertyOrNull("logs.queueKey")
                    ?.getString()
                    ?: "moneat:logs:queue"
            val routedEntries = routeLogEntries(organizationId, entries, otlpServiceRoutingService)
            val accepted = logService.enqueueSdkLogs(organizationId.toLong(), routedEntries, queueKey)
            call.respond(HttpStatusCode.Accepted, mapOf("accepted" to accepted))
        }
    }
}

private suspend fun handleOtlpLogIngest(
    call: io.ktor.server.application.ApplicationCall,
    logService: LogService,
    quotaService: BillingQuotaService,
    otlpApiKeyService: OtlpApiKeyService,
    eventService: EventService,
    otlpServiceRoutingService: OtlpServiceRoutingService,
    projectIdResolver: ProjectIdResolver,
) {
    val contentType = call.request.header(HttpHeaders.ContentType) ?: ""
    val isJson = contentType.contains("application/json", ignoreCase = true)
    val isProtobuf = contentType.contains("application/x-protobuf", ignoreCase = true)
    if (!isJson && !isProtobuf) {
        call.respond(
            HttpStatusCode.UnsupportedMediaType,
            ErrorResponse(
                "OTLP logs endpoint requires Content-Type: application/json or application/x-protobuf."
            )
        )
        return
    }

    val organizationId: Int? =
        OtlpAuth.resolveOtlpIngestOrganizationId(call, otlpApiKeyService, eventService, projectIdResolver)

    if (organizationId == null) {
        call.respond(HttpStatusCode.Unauthorized, ErrorResponse("Missing or invalid OTLP API key or DSN"))
        return
    }

    val bodyBytes = call.receive<ByteArray>()
    val encoding = call.request.header(HttpHeaders.ContentEncoding)
    val payloadBytes = suspendRunCatching {
        DecompressionService.decompress(bodyBytes, encoding)
    }.getOrElse { _ ->
        call.respond(HttpStatusCode.BadRequest, ErrorResponse("Failed to decompress request body"))
        return
    }

    val parsedEntries = if (isProtobuf) {
        logService.parseOtlpProtobuf(payloadBytes)
    } else {
        logService.parseOtlpJson(payloadBytes.decodeToString())
    }
    if (parsedEntries.isEmpty()) {
        call.respond(HttpStatusCode.Accepted, mapOf("accepted" to 0))
        return
    }

    if (quotaService.isEnforcementEnabled()) {
        val billableBytes = logService.estimateBillableBytes(parsedEntries)
        val reservation =
            quotaService.reserveUnits(
                organizationId = organizationId,
                requestedUnits = parsedEntries.size,
                eventType = "log",
                requestedBytes = billableBytes
            )
        if (!reservation.allowed) {
            call.respond(
                HttpStatusCode.TooManyRequests,
                QuotaExceededResponse(reason = reservation.reason, usage = reservation.usage)
            )
            return
        }
    }

    val queueKey =
        call.application.environment.config
            .propertyOrNull("logs.queueKey")
            ?.getString()
            ?: "moneat:logs:queue"
    val routedEntries = routeLogEntries(organizationId, parsedEntries, otlpServiceRoutingService)
    val accepted = logService.enqueueSdkLogs(organizationId.toLong(), routedEntries, queueKey)
    call.respond(HttpStatusCode.Accepted, mapOf("accepted" to accepted))
}

private fun routeLogEntries(
    organizationId: Int,
    entries: List<com.moneat.logs.models.LogIngestEntry>,
    routingService: OtlpServiceRoutingService,
): List<com.moneat.logs.models.LogIngestEntry> {
    val descriptors = entries.map { entry ->
        val serviceName = entry.service ?: entry.resourceAttributes?.get("service.name")
        OtlpServiceDescriptor(
            serviceNamespace = entry.serviceNamespace ?: entry.resourceAttributes?.get("service.namespace"),
            serviceName = serviceName,
            environment = entry.environment,
        )
    }
    val projectIds = routingService.resolveProjectIds(organizationId, descriptors, OtlpSignalType.LOGS)
    return entries.map { entry ->
        val serviceName = entry.service ?: entry.resourceAttributes?.get("service.name")
        val identity = routingService.normalizeIdentity(
            entry.serviceNamespace ?: entry.resourceAttributes?.get("service.namespace"),
            serviceName,
        )
        entry.copy(projectId = identity?.let { projectIds[it] })
    }
}
