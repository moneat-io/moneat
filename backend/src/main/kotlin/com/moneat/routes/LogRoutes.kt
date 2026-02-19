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

package com.moneat.routes

import com.auth0.jwt.JWT
import com.auth0.jwt.algorithms.Algorithm
import com.moneat.models.LogQueryRequest
import com.moneat.models.LogTailFilters
import com.moneat.plugins.getDemoEpochMs
import com.moneat.plugins.isDemoUser
import com.moneat.services.BillingQuotaService
import com.moneat.services.DashboardService
import com.moneat.services.EventService
import com.moneat.services.LogService
import com.moneat.utils.ErrorResponse
import io.ktor.http.ContentType
import io.ktor.http.HttpHeaders
import io.ktor.http.HttpStatusCode
import io.ktor.server.application.*
import io.ktor.server.application.ApplicationCall
import io.ktor.server.application.call
import io.ktor.server.auth.authenticate
import io.ktor.server.auth.jwt.JWTPrincipal
import io.ktor.server.auth.principal
import io.ktor.server.request.*
import io.ktor.server.request.header
import io.ktor.server.response.*
import io.ktor.server.response.respondText
import io.ktor.server.response.respondTextWriter
import io.ktor.server.routing.Route
import io.ktor.server.routing.get
import io.ktor.server.routing.post
import io.ktor.server.routing.route
import io.lettuce.core.RedisClient
import io.lettuce.core.RedisURI
import io.lettuce.core.pubsub.RedisPubSubAdapter
import kotlinx.serialization.json.Json
import mu.KotlinLogging
import java.time.Instant
import java.util.concurrent.LinkedBlockingQueue
import java.util.concurrent.TimeUnit

private val logger = KotlinLogging.logger {}
private val json = Json { ignoreUnknownKeys = true }

fun Route.logRoutes() {
    val eventService = EventService()
    val quotaService = BillingQuotaService()
    val dashboardService = DashboardService()
    val logService = LogService()

    route("/v1") {
        post("/logs/otlp") {
            val bodyBytes = call.receive<ByteArray>()
            val encoding = call.request.header(HttpHeaders.ContentEncoding)
            val payloadBytes = if (encoding == "gzip") {
                java.util.zip.GZIPInputStream(bodyBytes.inputStream()).readBytes()
            } else {
                bodyBytes
            }

            val payload = payloadBytes.decodeToString()
            val parsedEntries = logService.parseOtlpJson(payload)
            if (parsedEntries.isEmpty()) {
                call.respond(HttpStatusCode.Accepted, mapOf("accepted" to 0))
                return@post
            }

            val dsnLikeHeader = call.request.header("x-moneat-dsn")
                ?: call.request.header("X-Moneat-Dsn")
                ?: call.request.header(HttpHeaders.Authorization)

            val projectId = extractProjectIdFromDsn(dsnLikeHeader)
                ?: call.request.queryParameters["projectId"]?.toLongOrNull()

            if (projectId == null) {
                call.respond(HttpStatusCode.BadRequest, ErrorResponse("Missing project ID in DSN or query parameter"))
                return@post
            }

            val publicKey = extractPublicKey(call.request.header("X-Sentry-Auth"), call.request.queryParameters["sentry_key"])
                ?: extractPublicKeyFromDsn(dsnLikeHeader)

            if (publicKey == null) {
                call.respond(HttpStatusCode.Unauthorized, ErrorResponse("Missing DSN authentication"))
                return@post
            }

            val verification = eventService.verifyProjectKey(projectId, publicKey)
            if (!verification.isValid) {
                call.respond(HttpStatusCode.Unauthorized, ErrorResponse("Invalid DSN"))
                return@post
            }

            val organizationId = eventService.getOrganizationIdForProject(projectId)
            if (organizationId == null) {
                call.respond(HttpStatusCode.NotFound, ErrorResponse("Project organization not found"))
                return@post
            }

            if (quotaService.isEnforcementEnabled()) {
                val billableBytes = logService.estimateBillableBytes(parsedEntries)
                val reservation = quotaService.reserveUnits(
                    organizationId = organizationId,
                    requestedUnits = parsedEntries.size,
                    eventType = "log",
                    requestedBytes = billableBytes
                )
                if (!reservation.allowed) {
                    call.respond(
                        HttpStatusCode.TooManyRequests,
                        mapOf(
                            "error" to "Quota exceeded",
                            "reason" to reservation.reason,
                            "usage" to reservation.usage
                        )
                    )
                    return@post
                }
            }

            val queueKey = call.application.environment.config.propertyOrNull("logs.queueKey")?.getString()
                ?: "moneat:logs:queue"
            val accepted = logService.enqueueSdkLogs(projectId, parsedEntries, queueKey)
            call.respond(HttpStatusCode.Accepted, mapOf("accepted" to accepted))
        }

        authenticate("auth-jwt") {
            get("/projects/{projectId}/logs") {
                val principal = call.principal<JWTPrincipal>()
                val userId = principal!!.payload.getClaim("userId").asInt()
                val isDemo = call.isDemoUser()
                val demoEpochMs = call.getDemoEpochMs()

                val projectId = call.parameters["projectId"]?.toLongOrNull()
                if (projectId == null) {
                    call.respond(HttpStatusCode.BadRequest, ErrorResponse("Invalid project ID"))
                    return@get
                }

                if (!isDemo && !dashboardService.hasProjectAccess(userId, projectId)) {
                    call.respond(HttpStatusCode.Forbidden)
                    return@get
                }

                // For demo mode, if no time range specified, default to last 24 hours from demo epoch
                val defaultFrom = if (isDemo && demoEpochMs != null && call.request.queryParameters["from"] == null) {
                    val twentyFourHoursAgo = demoEpochMs - (24 * 60 * 60 * 1000)
                    Instant.ofEpochMilli(twentyFourHoursAgo).toString()
                } else {
                    call.request.queryParameters["from"]
                }

                val defaultTo = if (isDemo && demoEpochMs != null && call.request.queryParameters["to"] == null) {
                    Instant.ofEpochMilli(demoEpochMs).toString()
                } else {
                    call.request.queryParameters["to"]
                }

                val request = LogQueryRequest(
                    limit = call.request.queryParameters["limit"]?.toIntOrNull() ?: 100,
                    cursor = call.request.queryParameters["cursor"],
                    query = call.request.queryParameters["q"] ?: call.request.queryParameters["query"],
                    levels = parseLevelQueryParams(call),
                    service = call.request.queryParameters["service"],
                    environment = call.request.queryParameters["environment"],
                    from = defaultFrom,
                    to = defaultTo,
                    tags = parseTagQueryParams(call),
                    excludeService = call.request.queryParameters["excludeService"],
                    excludeEnvironment = call.request.queryParameters["excludeEnvironment"],
                    excludeContainerName = call.request.queryParameters["excludeContainerName"],
                    excludeTags = parseExcludeTagQueryParams(call)
                )

                val result = logService.queryLogs(projectId, request)
                call.respond(HttpStatusCode.OK, result)
            }

            get("/projects/{projectId}/logs/tag-values") {
                val principal = call.principal<JWTPrincipal>()
                val userId = principal!!.payload.getClaim("userId").asInt()

                val projectId = call.parameters["projectId"]?.toLongOrNull()
                if (projectId == null) {
                    call.respond(HttpStatusCode.BadRequest, ErrorResponse("Invalid project ID"))
                    return@get
                }

                if (!dashboardService.hasProjectAccess(userId, projectId)) {
                    call.respond(HttpStatusCode.Forbidden)
                    return@get
                }

                val key = call.request.queryParameters["key"]
                if (key.isNullOrBlank()) {
                    call.respond(HttpStatusCode.BadRequest, ErrorResponse("Missing tag key parameter"))
                    return@get
                }

                val result = logService.getTagValues(
                    projectId = projectId,
                    key = key,
                    from = call.request.queryParameters["from"],
                    to = call.request.queryParameters["to"],
                    limit = call.request.queryParameters["limit"]?.toIntOrNull() ?: 50
                )
                call.respond(HttpStatusCode.OK, result)
            }

            get("/projects/{projectId}/logs/filters") {
                val principal = call.principal<JWTPrincipal>()
                val userId = principal!!.payload.getClaim("userId").asInt()
                val isDemo = call.isDemoUser()
                val demoEpochMs = call.getDemoEpochMs()

                val projectId = call.parameters["projectId"]?.toLongOrNull()
                if (projectId == null) {
                    call.respond(HttpStatusCode.BadRequest, ErrorResponse("Invalid project ID"))
                    return@get
                }

                if (!isDemo && !dashboardService.hasProjectAccess(userId, projectId)) {
                    call.respond(HttpStatusCode.Forbidden)
                    return@get
                }

                // For demo mode, if no time range specified, default to last 24 hours from demo epoch
                val defaultFrom = if (isDemo && demoEpochMs != null && call.request.queryParameters["from"] == null) {
                    val twentyFourHoursAgo = demoEpochMs - (24 * 60 * 60 * 1000)
                    Instant.ofEpochMilli(twentyFourHoursAgo).toString()
                } else {
                    call.request.queryParameters["from"]
                }

                val defaultTo = if (isDemo && demoEpochMs != null && call.request.queryParameters["to"] == null) {
                    Instant.ofEpochMilli(demoEpochMs).toString()
                } else {
                    call.request.queryParameters["to"]
                }

                val result = logService.getFilterOptionsWithCounts(
                    projectId = projectId,
                    from = defaultFrom,
                    to = defaultTo
                )
                call.respond(HttpStatusCode.OK, result)
            }

            get("/projects/{projectId}/logs/aggregate") {
                val principal = call.principal<JWTPrincipal>()
                val userId = principal!!.payload.getClaim("userId").asInt()
                val isDemo = call.isDemoUser()
                val demoEpochMs = call.getDemoEpochMs()

                val projectId = call.parameters["projectId"]?.toLongOrNull()
                if (projectId == null) {
                    call.respond(HttpStatusCode.BadRequest, ErrorResponse("Invalid project ID"))
                    return@get
                }

                if (!isDemo && !dashboardService.hasProjectAccess(userId, projectId)) {
                    call.respond(HttpStatusCode.Forbidden)
                    return@get
                }

                // For demo mode, if no time range specified, default to last 24 hours from demo epoch
                val defaultFrom = if (isDemo && demoEpochMs != null && call.request.queryParameters["from"] == null) {
                    val twentyFourHoursAgo = demoEpochMs - (24 * 60 * 60 * 1000)
                    Instant.ofEpochMilli(twentyFourHoursAgo).toString()
                } else {
                    call.request.queryParameters["from"]
                }

                val defaultTo = if (isDemo && demoEpochMs != null && call.request.queryParameters["to"] == null) {
                    Instant.ofEpochMilli(demoEpochMs).toString()
                } else {
                    call.request.queryParameters["to"]
                }

                val result = logService.aggregateLogs(
                    projectId = projectId,
                    from = defaultFrom,
                    to = defaultTo,
                    interval = call.request.queryParameters["interval"],
                    query = call.request.queryParameters["q"] ?: call.request.queryParameters["query"],
                    levels = parseLevelQueryParams(call),
                    service = call.request.queryParameters["service"],
                    environment = call.request.queryParameters["environment"],
                    tags = parseTagQueryParams(call),
                    excludeService = call.request.queryParameters["excludeService"],
                    excludeEnvironment = call.request.queryParameters["excludeEnvironment"],
                    excludeContainerName = call.request.queryParameters["excludeContainerName"],
                    excludeTags = parseExcludeTagQueryParams(call),
                    groupBy = call.request.queryParameters["groupBy"]
                )
                logger.debug {
                    "Aggregate logs response for project $projectId: ${result.buckets.size} buckets, totalCount=${result.totalCount}, interval=${result.interval}, from=$defaultFrom, to=$defaultTo, isDemo=$isDemo"
                }
                call.respond(HttpStatusCode.OK, result)
            }

            get("/projects/{projectId}/logs/top") {
                val principal = call.principal<JWTPrincipal>()
                val userId = principal!!.payload.getClaim("userId").asInt()
                val isDemo = call.isDemoUser()
                val demoEpochMs = call.getDemoEpochMs()

                val projectId = call.parameters["projectId"]?.toLongOrNull()
                if (projectId == null) {
                    call.respond(HttpStatusCode.BadRequest, ErrorResponse("Invalid project ID"))
                    return@get
                }

                if (!isDemo && !dashboardService.hasProjectAccess(userId, projectId)) {
                    call.respond(HttpStatusCode.Forbidden)
                    return@get
                }

                val field = call.request.queryParameters["field"]
                if (field.isNullOrBlank()) {
                    call.respond(HttpStatusCode.BadRequest, ErrorResponse("Missing field parameter"))
                    return@get
                }

                // For demo mode, if no time range specified, default to last 24 hours from demo epoch
                val defaultFrom = if (isDemo && demoEpochMs != null && call.request.queryParameters["from"] == null) {
                    val twentyFourHoursAgo = demoEpochMs - (24 * 60 * 60 * 1000)
                    Instant.ofEpochMilli(twentyFourHoursAgo).toString()
                } else {
                    call.request.queryParameters["from"]
                }

                val defaultTo = if (isDemo && demoEpochMs != null && call.request.queryParameters["to"] == null) {
                    Instant.ofEpochMilli(demoEpochMs).toString()
                } else {
                    call.request.queryParameters["to"]
                }

                val result = logService.topValues(
                    projectId = projectId,
                    field = field,
                    limit = call.request.queryParameters["limit"]?.toIntOrNull() ?: 10,
                    from = defaultFrom,
                    to = defaultTo,
                    query = call.request.queryParameters["q"] ?: call.request.queryParameters["query"],
                    levels = parseLevelQueryParams(call),
                    service = call.request.queryParameters["service"],
                    environment = call.request.queryParameters["environment"],
                    tags = parseTagQueryParams(call),
                    excludeService = call.request.queryParameters["excludeService"],
                    excludeEnvironment = call.request.queryParameters["excludeEnvironment"],
                    excludeContainerName = call.request.queryParameters["excludeContainerName"],
                    excludeTags = parseExcludeTagQueryParams(call)
                )
                call.respond(HttpStatusCode.OK, result)
            }

            get("/projects/{projectId}/logs/export") {
                val principal = call.principal<JWTPrincipal>()
                val userId = principal!!.payload.getClaim("userId").asInt()

                val projectId = call.parameters["projectId"]?.toLongOrNull()
                if (projectId == null) {
                    call.respond(HttpStatusCode.BadRequest, ErrorResponse("Invalid project ID"))
                    return@get
                }

                if (!dashboardService.hasProjectAccess(userId, projectId)) {
                    call.respond(HttpStatusCode.Forbidden)
                    return@get
                }

                val csv = logService.exportCsv(
                    projectId = projectId,
                    from = call.request.queryParameters["from"],
                    to = call.request.queryParameters["to"],
                    query = call.request.queryParameters["q"] ?: call.request.queryParameters["query"],
                    levels = parseLevelQueryParams(call),
                    service = call.request.queryParameters["service"],
                    environment = call.request.queryParameters["environment"],
                    tags = parseTagQueryParams(call),
                    excludeService = call.request.queryParameters["excludeService"],
                    excludeEnvironment = call.request.queryParameters["excludeEnvironment"],
                    excludeContainerName = call.request.queryParameters["excludeContainerName"],
                    excludeTags = parseExcludeTagQueryParams(call),
                    limit = call.request.queryParameters["limit"]?.toIntOrNull() ?: 5000
                )

                call.response.headers.append(HttpHeaders.ContentDisposition, "attachment; filename=\"logs-export.csv\"")
                call.respondText(csv, ContentType.Text.CSV)
            }
        }

        get("/projects/{projectId}/logs/tail") {
            val userId = authenticateTailRequest(call)
            if (userId == null) {
                call.respond(HttpStatusCode.Unauthorized, ErrorResponse("Unauthorized"))
                return@get
            }

            val projectId = call.parameters["projectId"]?.toLongOrNull()
            if (projectId == null) {
                call.respond(HttpStatusCode.BadRequest, ErrorResponse("Invalid project ID"))
                return@get
            }

            if (!dashboardService.hasProjectAccess(userId, projectId)) {
                call.respond(HttpStatusCode.Forbidden)
                return@get
            }

            val filters = LogTailFilters(
                query = call.request.queryParameters["q"] ?: call.request.queryParameters["query"],
                levels = parseLevelQueryParams(call).map { it.lowercase() }.toSet(),
                service = call.request.queryParameters["service"],
                environment = call.request.queryParameters["environment"]
            )

            val redisUrl = call.application.environment.config.property("redis.url").getString()
            val channel = logService.liveChannel(projectId)
            val queue = LinkedBlockingQueue<String>()

            val client = RedisClient.create(RedisURI.create(redisUrl))
            val connection = client.connectPubSub()
            val listener = object : RedisPubSubAdapter<String, String>() {
                override fun message(ch: String, message: String) {
                    if (ch == channel) {
                        queue.offer(message)
                    }
                }
            }

            connection.addListener(listener)
            connection.sync().subscribe(channel)

            call.response.headers.append(HttpHeaders.CacheControl, "no-cache")
            call.response.headers.append(HttpHeaders.Connection, "keep-alive")

            call.respondTextWriter(contentType = ContentType.Text.EventStream) {
                try {
                    write(": connected\n\n")
                    flush()

                    while (true) {
                        val next = queue.poll(15, TimeUnit.SECONDS)
                        if (next == null) {
                            write(": heartbeat\n\n")
                            flush()
                            continue
                        }

                        val parsed = logService.parseLiveLog(next) ?: continue
                        if (!logService.matchesTailFilters(parsed, filters)) continue

                        write("data: $next\n\n")
                        flush()
                    }
                } catch (e: Exception) {
                    logger.debug { "SSE log tail disconnected for project $projectId: ${e.message}" }
                } finally {
                    try {
                        connection.sync().unsubscribe(channel)
                    } catch (_: Exception) {
                    }
                    connection.removeListener(listener)
                    connection.close()
                    client.shutdown()
                }
            }
        }
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
        }
        .toMap()
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
        }
        .toMap()
}

private fun extractProjectIdFromDsn(dsnLike: String?): Long? {
    if (dsnLike.isNullOrBlank()) return null
    val cleaned = dsnLike.removePrefix("DSN ").trim()
    val regex = "https?://[^@]+@[^/]+/([0-9]+)".toRegex(RegexOption.IGNORE_CASE)
    return regex.find(cleaned)?.groupValues?.getOrNull(1)?.toLongOrNull()
}

private fun authenticateTailRequest(call: ApplicationCall): Int? {
    val authHeader = call.request.header(HttpHeaders.Authorization)
    val bearerToken = authHeader
        ?.takeIf { it.startsWith("Bearer ", ignoreCase = true) }
        ?.removePrefix("Bearer ")
        ?.trim()
    // Try: Authorization header → cookie → query param (legacy fallback)
    val token = bearerToken
        ?: call.request.cookies["auth_token"]
        ?: call.request.queryParameters["token"]

    if (token.isNullOrBlank()) return null

    return try {
        val config = call.application.environment.config
        val secret = config.property("jwt.secret").getString()
        val issuer = config.property("jwt.issuer").getString()
        val audience = config.property("jwt.audience").getString()

        val verifier = JWT
            .require(Algorithm.HMAC256(secret))
            .withIssuer(issuer)
            .withAudience(audience)
            .build()

        verifier.verify(token).getClaim("userId").asInt()
    } catch (_: Exception) {
        null
    }
}
