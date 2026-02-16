package com.moneat.routes

import com.auth0.jwt.JWT
import com.auth0.jwt.algorithms.Algorithm
import com.moneat.models.LogQueryRequest
import com.moneat.models.LogTailFilters
import com.moneat.services.BillingQuotaService
import com.moneat.services.DashboardService
import com.moneat.services.EventService
import com.moneat.services.LogService
import io.ktor.http.*
import com.moneat.utils.ErrorResponse
import com.moneat.utils.MessageResponse
import com.moneat.utils.BooleanResponse
import io.ktor.server.application.*
import io.ktor.server.auth.*
import io.ktor.server.auth.jwt.*
import io.ktor.server.request.*
import io.ktor.server.response.*
import io.ktor.server.routing.*
import io.lettuce.core.RedisClient
import io.lettuce.core.RedisURI
import io.lettuce.core.pubsub.RedisPubSubAdapter
import kotlinx.serialization.json.Json
import mu.KotlinLogging
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

                val projectId = call.parameters["projectId"]?.toLongOrNull()
                if (projectId == null) {
                    call.respond(HttpStatusCode.BadRequest, ErrorResponse("Invalid project ID"))
                    return@get
                }

                if (!dashboardService.hasProjectAccess(userId, projectId)) {
                    call.respond(HttpStatusCode.Forbidden)
                    return@get
                }

                val request = LogQueryRequest(
                    limit = call.request.queryParameters["limit"]?.toIntOrNull() ?: 100,
                    cursor = call.request.queryParameters["cursor"],
                    query = call.request.queryParameters["q"] ?: call.request.queryParameters["query"],
                    levels = parseLevelQueryParams(call),
                    service = call.request.queryParameters["service"],
                    environment = call.request.queryParameters["environment"],
                    from = call.request.queryParameters["from"],
                    to = call.request.queryParameters["to"],
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

                val projectId = call.parameters["projectId"]?.toLongOrNull()
                if (projectId == null) {
                    call.respond(HttpStatusCode.BadRequest, ErrorResponse("Invalid project ID"))
                    return@get
                }

                if (!dashboardService.hasProjectAccess(userId, projectId)) {
                    call.respond(HttpStatusCode.Forbidden)
                    return@get
                }

                val result = logService.getFilterOptionsWithCounts(
                    projectId = projectId,
                    from = call.request.queryParameters["from"],
                    to = call.request.queryParameters["to"]
                )
                call.respond(HttpStatusCode.OK, result)
            }

            get("/projects/{projectId}/logs/aggregate") {
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

                val result = logService.aggregateLogs(
                    projectId = projectId,
                    from = call.request.queryParameters["from"],
                    to = call.request.queryParameters["to"],
                    interval = call.request.queryParameters["interval"],
                    query = call.request.queryParameters["q"] ?: call.request.queryParameters["query"],
                    levels = parseLevelQueryParams(call),
                    service = call.request.queryParameters["service"],
                    environment = call.request.queryParameters["environment"],
                    tags = parseTagQueryParams(call),
                    groupBy = call.request.queryParameters["groupBy"]
                )
                call.respond(HttpStatusCode.OK, result)
            }

            get("/projects/{projectId}/logs/top") {
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

                val field = call.request.queryParameters["field"]
                if (field.isNullOrBlank()) {
                    call.respond(HttpStatusCode.BadRequest, ErrorResponse("Missing field parameter"))
                    return@get
                }

                val result = logService.topValues(
                    projectId = projectId,
                    field = field,
                    limit = call.request.queryParameters["limit"]?.toIntOrNull() ?: 10,
                    from = call.request.queryParameters["from"],
                    to = call.request.queryParameters["to"],
                    query = call.request.queryParameters["q"] ?: call.request.queryParameters["query"],
                    levels = parseLevelQueryParams(call),
                    service = call.request.queryParameters["service"],
                    environment = call.request.queryParameters["environment"],
                    tags = parseTagQueryParams(call)
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
