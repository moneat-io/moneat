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
import com.moneat.datadog.services.ProfileMergeFlamegraphQuery
import com.moneat.datadog.services.ProfileMergeService
import com.moneat.datadog.services.ProfileQueryFilters
import com.moneat.datadog.services.ProfileStorageService
import com.moneat.datadog.services.ProfileTimeWindow
import com.moneat.datadog.services.ProfileTimeseriesQuery
import com.moneat.utils.suspendRunCatching
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
private const val MISSING_PROFILE_ID = "Missing profileId"
private const val PROFILE_NOT_FOUND = "Profile not found"
private const val HOUR_MS = 3_600_000L
private const val DAY_MS = 86_400_000L

private fun ApplicationCall.orgIdOrNull(): Int? =
    principal<JWTPrincipal>()?.payload?.getClaim(ORG_CLAIM)?.asInt()

fun Route.profileDashboardRoutes() {
    authenticate("auth-jwt") {
        route("/v1/profiles") {
            get { call.handleListProfiles() }
            get("/services") { call.handleListServices() }
            get("/timeseries") { call.handleTimeseries() }
            get("/merged-flamegraph") { call.handleMergedFlamegraph() }
            get("/{profileId}") { call.handleGetProfile() }
            get("/{profileId}/download") { call.handleDownloadProfile() }
            get("/{profileId}/flamegraph") { call.handleProfileFlamegraph() }
        }
    }
}

private suspend fun ApplicationCall.handleListProfiles() {
    val orgId = requireOrgId() ?: return
    val limit = (parameters["limit"]?.toIntOrNull() ?: DEFAULT_LIMIT).coerceAtMost(MAX_LIMIT)
    val query = DdProfileListQuery(
        service = parameters["service"],
        profileType = parameters["type"],
        source = parameters["source"],
        env = parameters["env"],
        host = parameters["host"],
        version = parameters["version"],
        fromMs = parameters["from"]?.toLongOrNull(),
        toMs = parameters["to"]?.toLongOrNull(),
        limit = limit,
        offset = parameters["offset"]?.toIntOrNull() ?: 0,
    )

    suspendRunCatching { ProfileIngestionService.listProfiles(orgId, query) }
        .onSuccess { respond(it) }
        .onFailure { respondGenericError(this, "listProfiles", it) }
}

private suspend fun ApplicationCall.handleListServices() {
    val orgId = requireOrgId() ?: return
    val fromMs = parameters["from"]?.toLongOrNull()
    val toMs = parameters["to"]?.toLongOrNull()

    suspendRunCatching { ProfileIngestionService.listServices(orgId, fromMs, toMs) }
        .onSuccess { respond(it) }
        .onFailure { respondGenericError(this, "listServices", it) }
}

private suspend fun ApplicationCall.handleTimeseries() {
    val orgId = requireOrgId() ?: return
    val now = System.currentTimeMillis()
    val fromMs = parameters["from"]?.toLongOrNull() ?: (now - DAY_MS)
    val toMs = parameters["to"]?.toLongOrNull() ?: now
    if (toMs <= fromMs) {
        respond(HttpStatusCode.BadRequest, mapOf(ERROR_KEY to "Invalid time range"))
        return
    }

    val query = ProfileTimeseriesQuery(
        organizationId = orgId,
        filters = profileFilters(),
        window = ProfileTimeWindow(fromMs, toMs),
        buckets = parameters["buckets"]?.toIntOrNull() ?: DEFAULT_TIMESERIES_BUCKETS,
    )

    suspendRunCatching { ProfileIngestionService.timeseries(query) }
        .onSuccess { respond(it) }
        .onFailure { respondGenericError(this, "timeseries", it) }
}

private suspend fun ApplicationCall.handleMergedFlamegraph() {
    val orgId = requireOrgId() ?: return
    val now = System.currentTimeMillis()
    val fromMs = parameters["from"]?.toLongOrNull() ?: (now - HOUR_MS)
    val toMs = parameters["to"]?.toLongOrNull() ?: now
    val query = ProfileMergeFlamegraphQuery(
        organizationId = orgId,
        filters = profileFilters(includeVersion = true),
        window = ProfileTimeWindow(fromMs, toMs),
        sampleType = parameters["sampleType"],
        thread = parameters["thread"],
        maxProfiles = parameters["maxProfiles"]?.toIntOrNull() ?: ProfileMergeService.DEFAULT_MAX_PROFILES,
    )

    suspendRunCatching { ProfileMergeService.mergeFlamegraph(query) }
        .onSuccess { respondText(it.toString(), ContentType.Application.Json) }
        .onFailure { respondGenericError(this, "mergeFlamegraph", it) }
}

private suspend fun ApplicationCall.handleGetProfile() {
    val orgId = requireOrgId() ?: return
    val profileId = requireProfileId() ?: return
    val profile = suspendRunCatching { ProfileIngestionService.getProfile(orgId, profileId) }
        .getOrElse {
            respondGenericError(this, "getProfile", it)
            return
        }

    if (profile == null) {
        respondProfileNotFound()
        return
    }
    respond(profile)
}

private suspend fun ApplicationCall.handleDownloadProfile() {
    val orgId = requireOrgId() ?: return
    val profileId = requireProfileId() ?: return
    val meta = requireProfileMeta(orgId, profileId) ?: return
    val data = ProfileStorageService.read(meta.storageKey)
    if (data == null) {
        respond(HttpStatusCode.NotFound, mapOf(ERROR_KEY to "Profile data not found"))
        return
    }

    val normalizedType = meta.profileType.lowercase()
    val downloadData = if (normalizedType == "jfr") {
        suspendRunCatching { DecompressionService.decompress(data, null) }.getOrElse { data }
    } else {
        data
    }

    val fileName = profileDownloadFilename(profileId, normalizedType, downloadData)
    response.headers.append(HttpHeaders.ContentDisposition, "attachment; filename=\"$fileName\"")
    respondBytes(downloadData, ContentType.Application.OctetStream, HttpStatusCode.OK)
}

private suspend fun ApplicationCall.handleProfileFlamegraph() {
    val orgId = requireOrgId() ?: return
    val profileId = requireProfileId() ?: return
    val meta = requireProfileMeta(orgId, profileId) ?: return
    val data = ProfileStorageService.read(meta.storageKey)
    if (data == null) {
        respondText(ProfileFlamegraphParser.emptyFlamegraph().toString(), ContentType.Application.Json)
        return
    }

    val frames = ProfileFlamegraphParser.parse(
        source = meta.source,
        profileType = meta.profileType,
        data = data,
        sampleType = parameters["sampleType"],
        thread = parameters["thread"],
    )
    respondText(frames.toString(), ContentType.Application.Json)
}

private fun ApplicationCall.profileFilters(includeVersion: Boolean = false): ProfileQueryFilters =
    ProfileQueryFilters(
        service = parameters["service"],
        profileType = parameters["type"],
        env = parameters["env"],
        host = parameters["host"],
        version = if (includeVersion) parameters["version"] else null,
    )

private suspend fun ApplicationCall.requireOrgId(): Int? {
    val orgId = orgIdOrNull()
    if (orgId == null) {
        respond(HttpStatusCode.Unauthorized, mapOf(ERROR_KEY to INVALID_TOKEN))
    }
    return orgId
}

private suspend fun ApplicationCall.requireProfileId(): String? {
    val profileId = parameters["profileId"]
    if (profileId == null) {
        respond(HttpStatusCode.BadRequest, mapOf(ERROR_KEY to MISSING_PROFILE_ID))
    }
    return profileId
}

private suspend fun ApplicationCall.requireProfileMeta(
    orgId: Int,
    profileId: String,
): ProfileIngestionService.ProfileMeta? {
    val meta = ProfileIngestionService.getProfileMeta(orgId, profileId)
    if (meta == null) {
        respondProfileNotFound()
    }
    return meta
}

private suspend fun ApplicationCall.respondProfileNotFound() {
    respond(HttpStatusCode.NotFound, mapOf(ERROR_KEY to PROFILE_NOT_FOUND))
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
