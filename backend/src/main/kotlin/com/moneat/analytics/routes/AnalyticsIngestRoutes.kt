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

import com.moneat.config.RedisConfig
import com.moneat.analytics.models.AnalyticsEventPayload
import com.moneat.analytics.models.EnrichedAnalyticsEvent
import com.moneat.analytics.services.AnalyticsIngestionWorker
import com.moneat.analytics.services.GeoIpService
import com.moneat.analytics.services.ReferrerParser
import com.moneat.analytics.services.SessionHashService
import com.moneat.analytics.services.UserAgentService
import com.moneat.events.repositories.EventRepositoryImpl
import com.moneat.events.services.EventService
import com.moneat.notifications.services.EmailService
import com.moneat.notifications.services.NotificationService
import com.moneat.shared.models.ProjectKeys
import io.ktor.http.ContentType
import io.ktor.http.HttpStatusCode
import io.ktor.server.application.ApplicationCall
import io.ktor.server.request.contentType
import io.ktor.server.request.header
import io.ktor.server.request.receive
import io.ktor.server.request.receiveText
import io.ktor.server.response.respond
import io.ktor.server.response.respondText
import io.ktor.server.routing.Route
import io.ktor.server.routing.get
import io.ktor.server.routing.post
import io.ktor.server.routing.route
import kotlinx.serialization.json.Json
import mu.KotlinLogging
import org.jetbrains.exposed.v1.core.and
import org.jetbrains.exposed.v1.core.eq
import org.jetbrains.exposed.v1.jdbc.selectAll
import org.jetbrains.exposed.v1.jdbc.transactions.transaction
import java.net.URI

private val logger = KotlinLogging.logger {}
private val json = Json { ignoreUnknownKeys = true }

/**
 * Public ingestion endpoint for analytics events from the tracking script.
 * Supports /api/{domain}/analytics/event (SDK) and /api/{projectId}/analytics/event (API).
 * Also serves the tracking script at /js/m.js.
 */
fun Route.analyticsIngestRoutes(
    sessionHashService: SessionHashService = SessionHashService(),
    geoIpService: GeoIpService = GeoIpService(),
    eventService: EventService = EventService(NotificationService(EmailService()), EventRepositoryImpl()),
    enqueueEvent: (String) -> Unit = { message ->
        RedisConfig.sync().lpush(AnalyticsIngestionWorker.QUEUE_KEY, message)
    },
) {
    // Domain-based route for SDK / script tag (data-domain in path, sentry_key in query)
    route("/api/{domain}/analytics") {
        post("/event") {
            val sentryKey = call.request.queryParameters["sentry_key"]
            if (sentryKey.isNullOrBlank()) {
                call.respond(HttpStatusCode.Unauthorized, "Missing sentry_key")
                return@post
            }
            val projectId = transaction {
                ProjectKeys
                    .selectAll()
                    .where {
                        (ProjectKeys.public_key eq sentryKey) and (ProjectKeys.is_active eq true)
                    }
                    .firstOrNull()
                    ?.get(ProjectKeys.project_id)
            }
            if (projectId == null) {
                call.respond(HttpStatusCode.Unauthorized, "Invalid sentry_key")
                return@post
            }
            processAndEnqueueEvent(call, projectId, sessionHashService, geoIpService, enqueueEvent)
        }
    }

    // Project-ID route for API clients
    route("/api/{projectId}/analytics") {
        post("/event") {
            val projectId = call.parameters["projectId"]?.toLongOrNull()
            if (projectId == null) {
                call.respond(HttpStatusCode.BadRequest, "Invalid project ID")
                return@post
            }
            val authHeader = call.request.header("X-Sentry-Auth")
            val sentryKey = call.request.queryParameters["sentry_key"]
            val publicKey = extractAnalyticsPublicKey(authHeader, sentryKey)
            if (publicKey == null) {
                call.respond(HttpStatusCode.Unauthorized, "Missing authentication")
                return@post
            }
            val verification = eventService.verifyProjectKey(projectId, publicKey)
            if (!verification.isValid) {
                call.respond(HttpStatusCode.Unauthorized, "Invalid DSN")
                return@post
            }
            processAndEnqueueEvent(call, projectId, sessionHashService, geoIpService, enqueueEvent)
        }
    }

    // Serve the tracking script
    get("/js/m.js") {
        call.respondText(TRACKING_SCRIPT, ContentType.Application.JavaScript)
    }
}

private suspend fun processAndEnqueueEvent(
    call: ApplicationCall,
    projectId: Long,
    sessionHashService: SessionHashService,
    geoIpService: GeoIpService,
    enqueueEvent: (String) -> Unit,
) {
    val payload = try {
        // Accept both application/json (API clients) and text/plain (browser tracking
        // script / sendBeacon). text/plain avoids a CORS preflight, which means the
        // request succeeds even when the browser can't verify the preflight first.
        val contentType = call.request.contentType()
        if (contentType.match(ContentType.Text.Plain)) {
            val text = call.receiveText()
            json.decodeFromString<AnalyticsEventPayload>(text)
        } else {
            call.receive<AnalyticsEventPayload>()
        }
    } catch (e: Exception) {
        logger.debug { "Invalid analytics payload: ${e.message}" }
        call.respond(HttpStatusCode.BadRequest, "Invalid payload")
        return
    }
    val ip = call.request.header("X-Forwarded-For")?.split(",")?.firstOrNull()?.trim()
        ?: call.request.header("X-Real-IP")
        ?: "0.0.0.0"
    val userAgent = call.request.header("User-Agent") ?: ""
    val sessionId = sessionHashService.generateSessionId(payload.d, ip, userAgent)
    val parsedUA = UserAgentService.parse(userAgent)
    val geo = geoIpService.resolve(ip)
    val referrerSource = ReferrerParser.parse(payload.r)
    val utmParams = extractUtmParams(payload.u)
    val enriched = EnrichedAnalyticsEvent(
        projectId = projectId,
        sessionId = sessionId,
        eventName = payload.n,
        hostname = payload.d,
        pathname = extractPathname(payload.u),
        referrer = payload.r ?: "",
        referrerSource = referrerSource,
        utmSource = utmParams["utm_source"] ?: "",
        utmMedium = utmParams["utm_medium"] ?: "",
        utmCampaign = utmParams["utm_campaign"] ?: "",
        utmTerm = utmParams["utm_term"] ?: "",
        utmContent = utmParams["utm_content"] ?: "",
        countryCode = geo.countryCode,
        subdivision = geo.subdivision,
        city = geo.city,
        browser = parsedUA.browser,
        browserVersion = parsedUA.browserVersion,
        os = parsedUA.os,
        osVersion = parsedUA.osVersion,
        deviceType = parsedUA.deviceType,
        screenWidth = payload.w,
        props = payload.p ?: emptyMap(),
        timestamp = System.currentTimeMillis(),
    )
    val message = json.encodeToString(enriched)
    enqueueEvent(message)
    updateRealtimeCounter(projectId, sessionId)
    call.respond(HttpStatusCode.Accepted, "ok")
}

private fun updateRealtimeCounter(projectId: Long, sessionId: String) {
    try {
        val key = "${AnalyticsIngestionWorker.REALTIME_KEY_PREFIX}$projectId"
        RedisConfig.sync().pfadd(key, sessionId)
        RedisConfig.sync().expire(key, REALTIME_TTL_SECONDS)
    } catch (e: Exception) {
        logger.debug { "Failed to update realtime counter: ${e.message}" }
    }
}

internal fun extractAnalyticsPublicKey(authHeader: String?, sentryKeyParam: String?): String? {
    val headerKey = authHeader?.let { header ->
        val keyRegex = "(?i)sentry_key=([a-z0-9]+)".toRegex()
        keyRegex.find(header)?.groupValues?.get(1)
    }
    if (headerKey != null) return headerKey
    val keyRegex = "^[a-zA-Z0-9]+$".toRegex()
    return sentryKeyParam?.takeIf { keyRegex.matches(it) }
}

internal fun extractPathname(url: String): String {
    return try {
        URI(url).path ?: "/"
    } catch (_: Exception) {
        "/"
    }
}

internal fun extractUtmParams(url: String): Map<String, String> {
    return try {
        val query = URI(url).query ?: return emptyMap()
        query.split("&")
            .mapNotNull { param ->
                val parts = param.split("=", limit = 2)
                if (parts.size == 2 && parts[0].startsWith("utm_")) {
                    parts[0] to java.net.URLDecoder.decode(parts[1], "UTF-8")
                } else {
                    null
                }
            }
            .toMap()
    } catch (_: Exception) {
        emptyMap()
    }
}

private const val REALTIME_TTL_SECONDS = 300L

/**
 * Minimal inline tracking script (~800 bytes minified).
 * Served at /js/m.js. Bundled inline to avoid needing the NPM build at runtime.
 */
@Suppress("MaxLineLength")
private val TRACKING_SCRIPT = """
!function(){"use strict";var t=document.currentScript,a=t&&t.getAttribute("data-domain"),o=t&&(t.getAttribute("data-api")||new URL(t.src).origin);function e(e,n){if(!window._phantom&&!window.__nightmare&&!window.navigator.webdriver&&!window.__puppeteer&&("doNotTrack"in navigator&&"1"!==navigator.doNotTrack||!0)){var r={n:e,u:location.href,d:a,r:document.referrer,w:window.innerWidth};n&&(r.p=n);var i=new XMLHttpRequest;i.open("POST",o+"/api/"+a+"/analytics/event?sentry_key="+t.getAttribute("data-key"),!0),i.send(JSON.stringify(r))}}function n(){e("pageview")}var r=history.pushState;history.pushState=function(){r.apply(this,arguments),n()};var i=history.replaceState;history.replaceState=function(){i.apply(this,arguments),n()},window.addEventListener("popstate",n),"prerender"===document.visibilityState?document.addEventListener("visibilitychange",function(){r||"visible"!==document.visibilityState||(r=!0,n())}):n(),window.moneat={track:function(t,a){e(t,a)}}}();
""".trimIndent()
