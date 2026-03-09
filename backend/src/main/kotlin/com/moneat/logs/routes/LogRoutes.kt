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

import com.auth0.jwt.JWT
import com.auth0.jwt.algorithms.Algorithm
import com.moneat.billing.services.BillingQuotaService
import com.moneat.datadog.decompression.DecompressionService
import com.moneat.events.routes.extractPublicKey
import com.moneat.events.routes.extractPublicKeyFromDsn
import com.moneat.events.services.EventService
import com.moneat.logs.models.CreateLogApiKeyRequest
import com.moneat.logs.models.CreateLogIndexRequest
import com.moneat.logs.models.LogQueryRequest
import com.moneat.logs.models.LogTailFilters
import com.moneat.logs.models.UpdateLogIndexRequest
import com.moneat.logs.services.LogApiKeyService
import com.moneat.logs.services.LogIndexService
import com.moneat.logs.services.LogService
import com.moneat.plugins.getDemoEpochMs
import com.moneat.plugins.isDemoUser
import com.moneat.utils.ErrorResponse
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
import java.time.Instant
import java.util.concurrent.LinkedBlockingQueue
import java.util.concurrent.TimeUnit

private val logger = KotlinLogging.logger {}
private val json = Json { ignoreUnknownKeys = true }

fun Route.logRoutes(
    logService: LogService = LogService(),
    logApiKeyService: LogApiKeyService = LogApiKeyService(),
    logIndexService: LogIndexService = LogIndexService(),
) {
    route("/v1") {
        authenticate("auth-jwt") {
            post("/logs/api-keys") {
                val principal = call.principal<JWTPrincipal>()
                val userId = principal!!.payload.getClaim("userId").asInt()
                val orgId = principal.payload.getClaim("orgId").asInt()

                val request = call.receive<CreateLogApiKeyRequest>()
                val name = request.name.trim()
                if (name.isBlank()) {
                    call.respond(HttpStatusCode.BadRequest, ErrorResponse("Name is required"))
                    return@post
                }

                val response = logApiKeyService.createKey(organizationId = orgId, name = name, createdBy = userId)
                call.respond(HttpStatusCode.Created, response)
            }

            get("/logs/api-keys") {
                val principal = call.principal<JWTPrincipal>()
                val orgId = principal!!.payload.getClaim("orgId").asInt()

                val keys = logApiKeyService.listKeys(orgId)
                call.respond(HttpStatusCode.OK, mapOf("keys" to keys))
            }

            delete("/logs/api-keys/{id}") {
                val principal = call.principal<JWTPrincipal>()
                val orgId = principal!!.payload.getClaim("orgId").asInt()

                val id = call.parameters["id"]?.toIntOrNull()
                if (id == null) {
                    call.respond(HttpStatusCode.BadRequest, ErrorResponse("Invalid key ID"))
                    return@delete
                }

                val deleted = logApiKeyService.deleteKey(organizationId = orgId, keyId = id)
                if (!deleted) {
                    call.respond(HttpStatusCode.NotFound, ErrorResponse("Key not found"))
                    return@delete
                }
                call.respond(HttpStatusCode.NoContent)
            }

            // --- Log Indexes CRUD ---

            get("/logs/indexes") {
                val principal = call.principal<JWTPrincipal>()
                val orgId = principal!!.payload.getClaim("orgId").asInt()
                val indexes = logIndexService.list(orgId)
                call.respond(
                    HttpStatusCode.OK,
                    mapOf("indexes" to indexes)
                )
            }

            post("/logs/indexes") {
                val principal = call.principal<JWTPrincipal>()
                val orgId = principal!!.payload.getClaim("orgId").asInt()
                val request =
                    call.receive<CreateLogIndexRequest>()
                if (request.name.isBlank()) {
                    call.respond(
                        HttpStatusCode.BadRequest,
                        ErrorResponse("Name is required")
                    )
                    return@post
                }
                val index = logIndexService.create(orgId, request)
                call.respond(HttpStatusCode.Created, index)
            }

            put("/logs/indexes/{id}") {
                val principal = call.principal<JWTPrincipal>()
                val orgId = principal!!.payload.getClaim("orgId").asInt()
                val id = call.parameters["id"]?.toIntOrNull()
                if (id == null) {
                    call.respond(
                        HttpStatusCode.BadRequest,
                        ErrorResponse("Invalid index ID")
                    )
                    return@put
                }
                val request =
                    call.receive<UpdateLogIndexRequest>()
                val updated =
                    logIndexService.update(orgId, id, request)
                if (updated == null) {
                    call.respond(
                        HttpStatusCode.NotFound,
                        ErrorResponse("Index not found")
                    )
                    return@put
                }
                call.respond(HttpStatusCode.OK, updated)
            }

            delete("/logs/indexes/{id}") {
                val principal = call.principal<JWTPrincipal>()
                val orgId = principal!!.payload.getClaim("orgId").asInt()
                val id = call.parameters["id"]?.toIntOrNull()
                if (id == null) {
                    call.respond(
                        HttpStatusCode.BadRequest,
                        ErrorResponse("Invalid index ID")
                    )
                    return@delete
                }
                val deleted =
                    logIndexService.delete(orgId, id)
                if (!deleted) {
                    call.respond(
                        HttpStatusCode.NotFound,
                        ErrorResponse("Index not found")
                    )
                    return@delete
                }
                call.respond(HttpStatusCode.NoContent)
            }

            post("/logs/indexes/test") {
                val principal = call.principal<JWTPrincipal>()
                val orgId = principal!!.payload.getClaim("orgId").asInt()
                val body = call.receive<Map<String, String>>()
                val filterQuery = body["filter_query"] ?: ""
                val result =
                    logIndexService.testFilter(orgId, filterQuery)
                call.respond(HttpStatusCode.OK, result)
            }
        }

        authenticate("auth-jwt") {
            get("/logs") {
                val principal = call.principal<JWTPrincipal>()
                val orgId = principal!!.payload.getClaim("orgId").asInt().toLong()
                val isDemo = call.isDemoUser()
                val demoEpochMs = call.getDemoEpochMs()

                // For demo mode, if no time range specified, default to last 24 hours from demo epoch
                val defaultFrom =
                    if (isDemo && demoEpochMs != null && call.request.queryParameters["from"] == null) {
                        val twentyFourHoursAgo = demoEpochMs - (24 * 60 * 60 * 1000)
                        Instant.ofEpochMilli(twentyFourHoursAgo).toString()
                    } else {
                        call.request.queryParameters["from"]
                    }

                val defaultTo =
                    if (isDemo && demoEpochMs != null && call.request.queryParameters["to"] == null) {
                        Instant.ofEpochMilli(demoEpochMs).toString()
                    } else {
                        call.request.queryParameters["to"]
                    }

                val request =
                    LogQueryRequest(
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

                val result = logService.queryLogs(orgId, request)
                call.respond(HttpStatusCode.OK, result)
            }

            get("/logs/tag-values") {
                val principal = call.principal<JWTPrincipal>()
                val orgId = principal!!.payload.getClaim("orgId").asInt().toLong()

                val key = call.request.queryParameters["key"]
                if (key.isNullOrBlank()) {
                    call.respond(HttpStatusCode.BadRequest, ErrorResponse("Missing tag key parameter"))
                    return@get
                }

                val result =
                    logService.getTagValues(
                        organizationId = orgId,
                        key = key,
                        from = call.request.queryParameters["from"],
                        to = call.request.queryParameters["to"],
                        limit = call.request.queryParameters["limit"]?.toIntOrNull() ?: 50
                    )
                call.respond(HttpStatusCode.OK, result)
            }

            get("/logs/filters") {
                val principal = call.principal<JWTPrincipal>()
                val orgId = principal!!.payload.getClaim("orgId").asInt().toLong()
                val isDemo = call.isDemoUser()
                val demoEpochMs = call.getDemoEpochMs()

                // For demo mode, if no time range specified, default to last 24 hours from demo epoch
                val defaultFrom =
                    if (isDemo && demoEpochMs != null && call.request.queryParameters["from"] == null) {
                        val twentyFourHoursAgo = demoEpochMs - (24 * 60 * 60 * 1000)
                        Instant.ofEpochMilli(twentyFourHoursAgo).toString()
                    } else {
                        call.request.queryParameters["from"]
                    }

                val defaultTo =
                    if (isDemo && demoEpochMs != null && call.request.queryParameters["to"] == null) {
                        Instant.ofEpochMilli(demoEpochMs).toString()
                    } else {
                        call.request.queryParameters["to"]
                    }

                val result =
                    logService.getFilterOptionsWithCounts(
                        organizationId = orgId,
                        from = defaultFrom,
                        to = defaultTo
                    )
                call.respond(HttpStatusCode.OK, result)
            }

            get("/logs/aggregate") {
                val principal = call.principal<JWTPrincipal>()
                val orgId = principal!!.payload.getClaim("orgId").asInt().toLong()
                val isDemo = call.isDemoUser()
                val demoEpochMs = call.getDemoEpochMs()

                // For demo mode, if no time range specified, default to last 24 hours from demo epoch
                val defaultFrom =
                    if (isDemo && demoEpochMs != null && call.request.queryParameters["from"] == null) {
                        val twentyFourHoursAgo = demoEpochMs - (24 * 60 * 60 * 1000)
                        Instant.ofEpochMilli(twentyFourHoursAgo).toString()
                    } else {
                        call.request.queryParameters["from"]
                    }

                val defaultTo =
                    if (isDemo && demoEpochMs != null && call.request.queryParameters["to"] == null) {
                        Instant.ofEpochMilli(demoEpochMs).toString()
                    } else {
                        call.request.queryParameters["to"]
                    }

                val result =
                    logService.aggregateLogs(
                        organizationId = orgId,
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
                    "Aggregate logs response for org $orgId: ${result.buckets.size} buckets, totalCount=${result.totalCount}, interval=${result.interval}, from=$defaultFrom, to=$defaultTo, isDemo=$isDemo"
                }
                call.respond(HttpStatusCode.OK, result)
            }

            get("/logs/top") {
                val principal = call.principal<JWTPrincipal>()
                val orgId = principal!!.payload.getClaim("orgId").asInt().toLong()
                val isDemo = call.isDemoUser()
                val demoEpochMs = call.getDemoEpochMs()

                val field = call.request.queryParameters["field"]
                if (field.isNullOrBlank()) {
                    call.respond(HttpStatusCode.BadRequest, ErrorResponse("Missing field parameter"))
                    return@get
                }

                // For demo mode, if no time range specified, default to last 24 hours from demo epoch
                val defaultFrom =
                    if (isDemo && demoEpochMs != null && call.request.queryParameters["from"] == null) {
                        val twentyFourHoursAgo = demoEpochMs - (24 * 60 * 60 * 1000)
                        Instant.ofEpochMilli(twentyFourHoursAgo).toString()
                    } else {
                        call.request.queryParameters["from"]
                    }

                val defaultTo =
                    if (isDemo && demoEpochMs != null && call.request.queryParameters["to"] == null) {
                        Instant.ofEpochMilli(demoEpochMs).toString()
                    } else {
                        call.request.queryParameters["to"]
                    }

                val result =
                    logService.topValues(
                        organizationId = orgId,
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

            get("/logs/export") {
                val principal = call.principal<JWTPrincipal>()
                val orgId = principal!!.payload.getClaim("orgId").asInt().toLong()

                val csv =
                    logService.exportCsv(
                        organizationId = orgId,
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

        get("/logs/tail") {
            val principal = call.principal<JWTPrincipal>()
            val orgId =
                if (principal != null) {
                    principal.payload.getClaim("orgId").asInt().toLong()
                } else {
                    val auth = authenticateTailRequest(call)
                    if (auth == null) {
                        call.respond(HttpStatusCode.Unauthorized, ErrorResponse("Unauthorized"))
                        return@get
                    }
                    auth.second
                }

            val filters =
                LogTailFilters(
                    query = call.request.queryParameters["q"] ?: call.request.queryParameters["query"],
                    levels = parseLevelQueryParams(call).map { it.lowercase() }.toSet(),
                    service = call.request.queryParameters["service"],
                    environment = call.request.queryParameters["environment"]
                )

            val redisUrl =
                call.application.environment.config
                    .property("redis.url")
                    .getString()
            val channel = logService.liveChannel(orgId)
            val queue = LinkedBlockingQueue<String>()

            val client = RedisClient.create(RedisURI.create(redisUrl))
            val connection = client.connectPubSub()
            val listener =
                object : RedisPubSubAdapter<String, String>() {
                    override fun message(
                        ch: String,
                        message: String
                    ) {
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
                    logger.debug { "SSE log tail disconnected for org $orgId: ${e.message}" }
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

private fun extractOrgIdFromLogApiKey(
    call: io.ktor.server.application.ApplicationCall,
    logApiKeyService: LogApiKeyService
): Int? {
    val authHeader = call.request.header(HttpHeaders.Authorization)
    val bearerPrefix = "Bearer "
    val key =
        authHeader
            ?.takeIf { it.startsWith(bearerPrefix, ignoreCase = true) }
            ?.substring(bearerPrefix.length)
            ?.trim()
            ?: return null
    return logApiKeyService.validateKey(key)
}

private fun extractOrgIdFromLegacyDsn(
    call: io.ktor.server.application.ApplicationCall,
    eventService: EventService
): Int? {
    val dsnLikeHeader =
        call.request.header("x-moneat-dsn")
            ?: call.request.header("X-Moneat-Dsn")
            ?: call.request.header(HttpHeaders.Authorization)
    val projectId =
        extractProjectIdFromDsn(dsnLikeHeader)
            ?: call.request.queryParameters["projectId"]?.toLongOrNull()
            ?: return null
    val publicKey =
        extractPublicKey(call.request.header("X-Sentry-Auth"), call.request.queryParameters["sentry_key"])
            ?: extractPublicKeyFromDsn(dsnLikeHeader)
            ?: return null
    val verification = eventService.verifyProjectKey(projectId, publicKey)
    if (!verification.isValid) return null
    return eventService.getOrganizationIdForProject(projectId)
}

private fun extractProjectIdFromDsn(dsnLike: String?): Long? {
    if (dsnLike.isNullOrBlank()) return null
    val cleaned = dsnLike.removePrefix("DSN ").trim()
    val regex = "https?://[^@]+@[^/]+/([0-9]+)".toRegex(RegexOption.IGNORE_CASE)
    return regex
        .find(cleaned)
        ?.groupValues
        ?.getOrNull(1)
        ?.toLongOrNull()
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

    return try {
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
        val orgId = decoded.getClaim("orgId").asInt().toLong()
        Pair(userId, orgId)
    } catch (_: Exception) {
        null
    }
}

fun Route.logIngestRoutes(
    logService: LogService = LogService(),
    quotaService: BillingQuotaService = BillingQuotaService(),
    logApiKeyService: LogApiKeyService = LogApiKeyService(),
) {
    val eventService = EventService()

    route("/v1") {
        post("/logs/otlp") {
            val organizationId: Int? =
                extractOrgIdFromLogApiKey(call, logApiKeyService)
                    ?: extractOrgIdFromLegacyDsn(call, eventService)

            if (organizationId == null) {
                call.respond(HttpStatusCode.Unauthorized, ErrorResponse("Missing or invalid log API key or DSN"))
                return@post
            }

            val bodyBytes = call.receive<ByteArray>()
            val encoding = call.request.header(HttpHeaders.ContentEncoding)
            val payloadBytes = try {
                DecompressionService.decompress(bodyBytes, encoding)
            } catch (e: Exception) {
                call.respond(HttpStatusCode.BadRequest, ErrorResponse("Failed to decompress request body"))
                return@post
            }

            val payload = payloadBytes.decodeToString()
            val parsedEntries = logService.parseOtlpJson(payload)
            if (parsedEntries.isEmpty()) {
                call.respond(HttpStatusCode.Accepted, mapOf("accepted" to 0))
                return@post
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
                        mapOf(
                            "error" to "Quota exceeded",
                            "reason" to reservation.reason,
                            "usage" to reservation.usage
                        )
                    )
                    return@post
                }
            }

            val queueKey =
                call.application.environment.config
                    .propertyOrNull("logs.queueKey")
                    ?.getString()
                    ?: "moneat:logs:queue"
            val accepted = logService.enqueueSdkLogs(organizationId.toLong(), parsedEntries, queueKey)
            call.respond(HttpStatusCode.Accepted, mapOf("accepted" to accepted))
        }

        post("/logs/ingest") {
            val organizationId = extractOrgIdFromLogApiKey(call, logApiKeyService)
            if (organizationId == null) {
                call.respond(HttpStatusCode.Unauthorized, ErrorResponse("Missing or invalid log API key"))
                return@post
            }

            val bodyBytes = call.receive<ByteArray>()
            val encoding = call.request.header(HttpHeaders.ContentEncoding)
            val payloadBytes = try {
                DecompressionService.decompress(bodyBytes, encoding)
            } catch (e: Exception) {
                call.respond(HttpStatusCode.BadRequest, ErrorResponse("Failed to decompress request body"))
                return@post
            }

            val entries =
                try {
                    json.decodeFromString<List<com.moneat.logs.models.LogIngestEntry>>(
                        payloadBytes.decodeToString()
                    )
                } catch (e: Exception) {
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
                        mapOf(
                            "error" to "Quota exceeded",
                            "reason" to reservation.reason,
                            "usage" to reservation.usage
                        )
                    )
                    return@post
                }
            }

            val queueKey =
                call.application.environment.config
                    .propertyOrNull("logs.queueKey")
                    ?.getString()
                    ?: "moneat:logs:queue"
            val accepted = logService.enqueueSdkLogs(organizationId.toLong(), entries, queueKey)
            call.respond(HttpStatusCode.Accepted, mapOf("accepted" to accepted))
        }
    }
}
