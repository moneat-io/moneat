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

import com.moneat.config.ClickHouseClient
import com.moneat.models.ProjectKeys
import com.moneat.services.AnalyticsSaltService
import com.moneat.utils.ClickHouseSqlUtils
import io.ktor.http.*
import io.ktor.server.request.*
import io.ktor.server.response.*
import io.ktor.server.routing.*
import kotlinx.serialization.json.*
import mu.KotlinLogging
import org.jetbrains.exposed.v1.core.and
import org.jetbrains.exposed.v1.core.eq
import org.jetbrains.exposed.v1.jdbc.selectAll
import org.jetbrains.exposed.v1.jdbc.transactions.transaction
import java.net.URI
import java.security.MessageDigest

private val logger = KotlinLogging.logger {}
private val json = Json { ignoreUnknownKeys = true }

data class AnalyticsEventPayload(
    val projectId: Long,
    val sessionId: String,
    val eventName: String,
    val hostname: String,
    val pathname: String,
    val referrer: String,
    val screenWidth: Int,
    val props: Map<String, String>,
)

fun Route.analyticsIngestRoutes(
    insertEvent: suspend (AnalyticsEventPayload) -> Unit = { payload ->
        val esc = ClickHouseSqlUtils::escapeSql
        val propsMap = if (payload.props.isNotEmpty()) {
            val entries = payload.props.entries.joinToString(", ") { (k, v) ->
                "'${esc(k)}', '${esc(v)}'"
            }
            "map($entries)"
        } else {
            "map()"
        }
        ClickHouseClient.execute(
            """
            INSERT INTO analytics_events (
                event_id, project_id, session_id, event_name,
                hostname, pathname, referrer, screen_width,
                props, timestamp
            ) VALUES (
                generateUUIDv4(),
                ${payload.projectId},
                '${esc(payload.sessionId)}',
                '${esc(payload.eventName)}',
                '${esc(payload.hostname)}',
                '${esc(payload.pathname)}',
                '${esc(payload.referrer)}',
                ${payload.screenWidth},
                $propsMap,
                now64(3)
            )
            """.trimIndent()
        )
    }
) {
    route("/api/{domain}/analytics") {
        post("/event") {
            val sentryKey = call.request.queryParameters["sentry_key"]
            if (sentryKey.isNullOrBlank()) {
                call.respond(HttpStatusCode.Unauthorized, "Missing sentry_key")
                return@post
            }

            // Look up the project by sentry_key alone (domain is for routing only)
            val projectId = transaction {
                ProjectKeys
                    .selectAll()
                    .where {
                        (ProjectKeys.public_key eq sentryKey) and
                            (ProjectKeys.is_active eq true)
                    }
                    .firstOrNull()
                    ?.get(ProjectKeys.project_id)
            }

            if (projectId == null) {
                call.respond(HttpStatusCode.Unauthorized, "Invalid sentry_key")
                return@post
            }

            val body = try {
                json.parseToJsonElement(call.receiveText()).jsonObject
            } catch (e: Exception) {
                logger.warn { "Invalid analytics event payload: ${e.message}" }
                call.respond(HttpStatusCode.BadRequest, "Invalid JSON payload")
                return@post
            }

            val eventName = body["n"]?.jsonPrimitive?.contentOrNull ?: "pageview"
            val pageUrl = body["u"]?.jsonPrimitive?.contentOrNull ?: ""
            val hostname = body["d"]?.jsonPrimitive?.contentOrNull ?: ""
            val referrer = body["r"]?.jsonPrimitive?.contentOrNull ?: ""
            val screenWidth = body["w"]?.jsonPrimitive?.intOrNull ?: 0
            val propsJson = body["p"]?.jsonObject

            val pathname = try {
                URI(pageUrl).path?.takeIf { it.isNotBlank() } ?: "/"
            } catch (_: Exception) {
                "/"
            }

            val props = propsJson?.entries
                ?.associate { (k, v) -> k to (v.jsonPrimitive.contentOrNull ?: "") }
                ?: emptyMap()

            // Session ID: SHA-256(daily_salt | IP | User-Agent).
            // The salt rotates each day via Redis, so hashes are non-reversible and
            // reset daily without embedding PII in the hash input directly.
            val ip = call.request.headers["X-Forwarded-For"]?.split(",")?.firstOrNull()?.trim()
                ?: call.request.local.remoteHost
            val userAgent = call.request.headers["User-Agent"] ?: ""
            val salt = AnalyticsSaltService.getDailySalt()
            val sessionId = MessageDigest.getInstance("SHA-256")
                .digest("$salt|$ip|$userAgent".toByteArray())
                .joinToString("") { "%02x".format(it) }
                .take(32)

            try {
                insertEvent(
                    AnalyticsEventPayload(
                        projectId = projectId,
                        sessionId = sessionId,
                        eventName = eventName,
                        hostname = hostname,
                        pathname = pathname,
                        referrer = referrer,
                        screenWidth = screenWidth,
                        props = props,
                    )
                )
                call.respond(HttpStatusCode.Accepted, mapOf("status" to "ok"))
            } catch (e: Exception) {
                logger.error(e) { "Failed to insert analytics event: ${e.message}" }
                call.respond(HttpStatusCode.InternalServerError, "Failed to record event")
            }
        }
    }
}
