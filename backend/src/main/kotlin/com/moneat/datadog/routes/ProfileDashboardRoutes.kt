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

package com.moneat.datadog.routes

import com.moneat.datadog.decompression.DecompressionService
import com.moneat.datadog.services.DdProfileListQuery
import com.moneat.datadog.services.ProfileFlamegraphParser
import com.moneat.datadog.services.ProfileIngestionService
import com.moneat.datadog.services.ProfileMergeService
import com.moneat.datadog.services.ProfileStorageService
import io.ktor.http.ContentType
import io.ktor.http.HttpHeaders
import io.ktor.http.HttpStatusCode
import io.ktor.server.application.ApplicationCall
import io.ktor.server.auth.authenticate
import io.ktor.server.auth.jwt.JWTPrincipal
import io.ktor.server.auth.principal
import io.ktor.server.response.respond
import io.ktor.server.response.respondBytes
import io.ktor.server.response.respondText
import io.ktor.server.routing.Route
import io.ktor.server.routing.get
import io.ktor.server.routing.route
import mu.KotlinLogging

private val logger = KotlinLogging.logger {}

private const val DEFAULT_LIMIT = 50
private const val MAX_LIMIT = 200
private const val ORG_CLAIM = "orgId"
private const val ERROR_KEY = "error"
private const val INVALID_TOKEN = "Invalid token"
private const val GENERIC_ERROR = "Failed to load profiling data"
private const val DEFAULT_TIMESERIES_BUCKETS = 48
private const val HOUR_MS = 3_600_000L
private const val DAY_MS = 86_400_000L

private fun ApplicationCall.orgIdOrNull(): Int? =
    principal<JWTPrincipal>()?.payload?.getClaim(ORG_CLAIM)?.asInt()

fun Route.profileDashboardRoutes() {
    authenticate("auth-jwt") {
        route("/v1/profiles") {
            // GET /v1/profiles - list raw profiles
            get {
                val orgId = call.orgIdOrNull() ?: return@get call.respond(
                    HttpStatusCode.Unauthorized,
                    mapOf(ERROR_KEY to INVALID_TOKEN),
                )
                val limit = (call.parameters["limit"]?.toIntOrNull() ?: DEFAULT_LIMIT)
                    .coerceAtMost(MAX_LIMIT)
                val query = DdProfileListQuery(
                    service = call.parameters["service"],
                    profileType = call.parameters["type"],
                    source = call.parameters["source"],
                    env = call.parameters["env"],
                    host = call.parameters["host"],
                    version = call.parameters["version"],
                    fromMs = call.parameters["from"]?.toLongOrNull(),
                    toMs = call.parameters["to"]?.toLongOrNull(),
                    limit = limit,
                    offset = call.parameters["offset"]?.toIntOrNull() ?: 0,
                )

                runCatching { ProfileIngestionService.listProfiles(orgId, query) }
                    .onSuccess { call.respond(it) }
                    .onFailure { respondGenericError(call, "listProfiles", it) }
            }

            // GET /v1/profiles/services - per-service rollup for the overview
            get("/services") {
                val orgId = call.orgIdOrNull() ?: return@get call.respond(
                    HttpStatusCode.Unauthorized,
                    mapOf(ERROR_KEY to INVALID_TOKEN),
                )
                val fromMs = call.parameters["from"]?.toLongOrNull()
                val toMs = call.parameters["to"]?.toLongOrNull()

                runCatching { ProfileIngestionService.listServices(orgId, fromMs, toMs) }
                    .onSuccess { call.respond(it) }
                    .onFailure { respondGenericError(call, "listServices", it) }
            }

            // GET /v1/profiles/timeseries - profile-volume buckets for a window
            get("/timeseries") {
                val orgId = call.orgIdOrNull() ?: return@get call.respond(
                    HttpStatusCode.Unauthorized,
                    mapOf(ERROR_KEY to INVALID_TOKEN),
                )
                val now = System.currentTimeMillis()
                val fromMs = call.parameters["from"]?.toLongOrNull() ?: (now - DAY_MS)
                val toMs = call.parameters["to"]?.toLongOrNull() ?: now
                if (toMs <= fromMs) {
                    return@get call.respond(
                        HttpStatusCode.BadRequest,
                        mapOf(ERROR_KEY to "Invalid time range"),
                    )
                }
                val buckets = call.parameters["buckets"]?.toIntOrNull()
                    ?: DEFAULT_TIMESERIES_BUCKETS

                runCatching {
                    ProfileIngestionService.timeseries(
                        organizationId = orgId,
                        service = call.parameters["service"],
                        profileType = call.parameters["type"],
                        env = call.parameters["env"],
                        host = call.parameters["host"],
                        fromMs = fromMs,
                        toMs = toMs,
                        buckets = buckets,
                    )
                }
                    .onSuccess { call.respond(it) }
                    .onFailure { respondGenericError(call, "timeseries", it) }
            }

            // GET /v1/profiles/merged-flamegraph - aggregate over a window
            get("/merged-flamegraph") {
                val orgId = call.orgIdOrNull() ?: return@get call.respond(
                    HttpStatusCode.Unauthorized,
                    mapOf(ERROR_KEY to INVALID_TOKEN),
                )
                val now = System.currentTimeMillis()
                val fromMs = call.parameters["from"]?.toLongOrNull() ?: (now - HOUR_MS)
                val toMs = call.parameters["to"]?.toLongOrNull() ?: now
                val maxProfiles = call.parameters["maxProfiles"]?.toIntOrNull()
                    ?: ProfileMergeService.DEFAULT_MAX_PROFILES

                runCatching {
                    ProfileMergeService.mergeFlamegraph(
                        organizationId = orgId,
                        service = call.parameters["service"],
                        profileType = call.parameters["type"],
                        env = call.parameters["env"],
                        host = call.parameters["host"],
                        version = call.parameters["version"],
                        fromMs = fromMs,
                        toMs = toMs,
                        sampleType = call.parameters["sampleType"],
                        thread = call.parameters["thread"],
                        maxProfiles = maxProfiles,
                    )
                }
                    .onSuccess {
                        call.respondText(it.toString(), ContentType.Application.Json)
                    }
                    .onFailure { respondGenericError(call, "mergeFlamegraph", it) }
            }

            // GET /v1/profiles/{profileId} - single profile metadata
            get("/{profileId}") {
                val orgId = call.orgIdOrNull() ?: return@get call.respond(
                    HttpStatusCode.Unauthorized,
                    mapOf(ERROR_KEY to INVALID_TOKEN),
                )
                val profileId = call.parameters["profileId"] ?: return@get call.respond(
                    HttpStatusCode.BadRequest,
                    mapOf(ERROR_KEY to "Missing profileId"),
                )

                val profile = runCatching {
                    ProfileIngestionService.getProfile(orgId, profileId)
                }.getOrElse {
                    return@get respondGenericError(call, "getProfile", it)
                }
                if (profile == null) {
                    call.respond(HttpStatusCode.NotFound, mapOf(ERROR_KEY to "Profile not found"))
                } else {
                    call.respond(profile)
                }
            }

            // GET /v1/profiles/{profileId}/download - raw profile bytes
            get("/{profileId}/download") {
                val orgId = call.orgIdOrNull() ?: return@get call.respond(
                    HttpStatusCode.Unauthorized,
                    mapOf(ERROR_KEY to INVALID_TOKEN),
                )
                val profileId = call.parameters["profileId"] ?: return@get call.respond(
                    HttpStatusCode.BadRequest,
                    mapOf(ERROR_KEY to "Missing profileId"),
                )

                val meta = ProfileIngestionService.getProfileMeta(orgId, profileId)
                if (meta == null) {
                    call.respond(HttpStatusCode.NotFound, mapOf(ERROR_KEY to "Profile not found"))
                    return@get
                }

                val data = ProfileStorageService.read(meta.storageKey)
                if (data == null) {
                    call.respond(
                        HttpStatusCode.NotFound,
                        mapOf(ERROR_KEY to "Profile data not found"),
                    )
                    return@get
                }

                val normalizedType = meta.profileType.lowercase()
                val downloadData = if (normalizedType == "jfr") {
                    runCatching { DecompressionService.decompress(data, null) }.getOrElse { data }
                } else {
                    data
                }

                val fileName = profileDownloadFilename(profileId, normalizedType, downloadData)
                call.response.headers.append(
                    HttpHeaders.ContentDisposition,
                    "attachment; filename=\"$fileName\"",
                )
                call.respondBytes(
                    downloadData,
                    ContentType.Application.OctetStream,
                    HttpStatusCode.OK,
                )
            }

            // GET /v1/profiles/{profileId}/flamegraph - single-profile flamegraph
            get("/{profileId}/flamegraph") {
                val orgId = call.orgIdOrNull() ?: return@get call.respond(
                    HttpStatusCode.Unauthorized,
                    mapOf(ERROR_KEY to INVALID_TOKEN),
                )
                val profileId = call.parameters["profileId"] ?: return@get call.respond(
                    HttpStatusCode.BadRequest,
                    mapOf(ERROR_KEY to "Missing profileId"),
                )

                val meta = ProfileIngestionService.getProfileMeta(orgId, profileId)
                if (meta == null) {
                    call.respond(HttpStatusCode.NotFound, mapOf(ERROR_KEY to "Profile not found"))
                    return@get
                }

                val data = ProfileStorageService.read(meta.storageKey)
                if (data == null) {
                    call.respondText(
                        ProfileFlamegraphParser.emptyFlamegraph().toString(),
                        ContentType.Application.Json,
                    )
                    return@get
                }

                val frames = ProfileFlamegraphParser.parse(
                    source = meta.source,
                    profileType = meta.profileType,
                    data = data,
                    sampleType = call.parameters["sampleType"],
                    thread = call.parameters["thread"],
                )
                call.respondText(frames.toString(), ContentType.Application.Json)
            }
        }
    }
}

private suspend fun respondGenericError(call: ApplicationCall, op: String, error: Throwable) {
    // Never surface ClickHouse/query internals to the client.
    logger.error(error) { "Profile dashboard query failed: $op" }
    call.respond(HttpStatusCode.InternalServerError, mapOf(ERROR_KEY to GENERIC_ERROR))
}

private fun profileDownloadFilename(
    profileId: String,
    profileType: String,
    data: ByteArray,
): String {
    val ext = when {
        profileType == "jfr" -> "jfr"
        isGzip(data) -> "pprof.gz"
        else -> "pprof"
    }
    return "profile-$profileId.$ext"
}

private fun isGzip(data: ByteArray): Boolean {
    return data.size >= 2 &&
        data[0] == 0x1f.toByte() &&
        data[1] == 0x8b.toByte()
}
