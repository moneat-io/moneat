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

package com.moneat.featureflags.routes

import com.moneat.auth.currentOrgIdOrNull
import com.moneat.featureflags.models.CreateFeatureFlagEnvironmentRequest
import com.moneat.featureflags.models.CreateFeatureFlagRequest
import com.moneat.featureflags.models.FeatureFlagSdkKeyRequest
import com.moneat.featureflags.models.FeatureFlagSegmentRequest
import com.moneat.featureflags.models.OfrepBulkEvaluationResponse
import com.moneat.featureflags.models.OfrepEvaluateRequest
import com.moneat.featureflags.models.OfrepFlagEvaluationResponse
import com.moneat.featureflags.models.OfrepTrackRequest
import com.moneat.featureflags.models.UpdateFeatureFlagConfigRequest
import com.moneat.featureflags.models.UpdateFeatureFlagRequest
import com.moneat.featureflags.services.FeatureFlagEvaluator
import com.moneat.featureflags.services.FeatureFlagEventService
import com.moneat.featureflags.services.FeatureFlagService
import com.moneat.org.services.OrgMembershipService
import com.moneat.org.services.OrgRole
import com.moneat.shared.services.toUuidOrNull
import com.moneat.utils.ErrorResponse
import io.ktor.http.HttpHeaders
import io.ktor.http.HttpStatusCode
import io.ktor.server.application.ApplicationCall
import io.ktor.server.auth.authenticate
import io.ktor.server.auth.jwt.JWTPrincipal
import io.ktor.server.auth.principal
import io.ktor.server.request.receive
import io.ktor.server.response.header
import io.ktor.server.response.respond
import io.ktor.server.routing.Route
import io.ktor.server.routing.delete
import io.ktor.server.routing.get
import io.ktor.server.routing.post
import io.ktor.server.routing.put
import io.ktor.server.routing.route
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.buildJsonObject
import kotlinx.serialization.json.put
import org.koin.core.context.GlobalContext
import kotlin.time.TimeSource
import kotlin.uuid.Uuid

private const val ERROR_FLAG_NOT_FOUND = "FLAG_NOT_FOUND"
private const val ERROR_TARGETING_KEY_MISSING = "TARGETING_KEY_MISSING"
private const val ERROR_TYPE_MISMATCH = "TYPE_MISMATCH"
private const val ERROR_INVALID_CONTEXT = "INVALID_CONTEXT"
private const val ERROR_PARSE = "PARSE_ERROR"
private const val DEFAULT_AUDIT_LIMIT = 50
private const val MAX_AUDIT_LIMIT = 100
private const val DEFAULT_ANALYTICS_HOURS = 24
private const val MICROSECONDS_PER_MILLISECOND = 1_000.0
private const val FLAG_KEY_PATH = "/{flagKey}"
private const val FEATURE_FLAG_NOT_FOUND = "Feature flag not found"
private const val MISSING_ORG_MESSAGE = "No active organization"
private const val FORBIDDEN_MESSAGE = "Insufficient permissions"

private data class FeatureFlagManagementContext(
    val organizationId: Int,
    val userId: Int,
)

fun Route.featureFlagRoutes(
    featureFlagService: FeatureFlagService = GlobalContext.get().get(),
    evaluator: FeatureFlagEvaluator = GlobalContext.get().get(),
    eventService: FeatureFlagEventService = GlobalContext.get().get(),
    membershipService: OrgMembershipService = GlobalContext.get().get(),
) {
    managementFeatureFlagRoutes(featureFlagService, membershipService)
    runtimeFeatureFlagRoutes(featureFlagService, evaluator, eventService)
}

private fun Route.managementFeatureFlagRoutes(
    featureFlagService: FeatureFlagService,
    membershipService: OrgMembershipService,
) {
    route("/v1/feature-flags") {
        authenticate("auth-jwt") {
            environmentManagementRoutes(featureFlagService, membershipService)
            segmentManagementRoutes(featureFlagService, membershipService)
            sdkKeyManagementRoutes(featureFlagService, membershipService)
            featureFlagAuditRoutes(featureFlagService, membershipService)
            featureFlagAnalyticsRoutes(featureFlagService, membershipService)
            flagManagementRoutes(featureFlagService, membershipService)
        }
    }
}

private fun Route.environmentManagementRoutes(
    featureFlagService: FeatureFlagService,
    membershipService: OrgMembershipService,
) {
    get("/environments") {
        val context = call.requireFeatureFlagAdmin(membershipService) ?: return@get
        call.respond(mapOf("environments" to featureFlagService.listEnvironments(context.organizationId)))
    }

    post("/environments") {
        call.respondHandlingBadRequest {
            val context = call.requireFeatureFlagAdmin(membershipService) ?: return@respondHandlingBadRequest
            val request = call.receive<CreateFeatureFlagEnvironmentRequest>()
            val response = featureFlagService.createEnvironment(context.organizationId, context.userId, request)
            call.respond(HttpStatusCode.Created, response)
        }
    }
}

private fun Route.segmentManagementRoutes(
    featureFlagService: FeatureFlagService,
    membershipService: OrgMembershipService,
) {
    get("/segments") {
        val context = call.requireFeatureFlagAdmin(membershipService) ?: return@get
        call.respond(mapOf("segments" to featureFlagService.listSegments(context.organizationId)))
    }

    post("/segments") {
        call.respondHandlingBadRequest {
            val context = call.requireFeatureFlagAdmin(membershipService) ?: return@respondHandlingBadRequest
            val request = call.receive<FeatureFlagSegmentRequest>()
            val response = featureFlagService.upsertSegment(context.organizationId, context.userId, request)
            call.respond(HttpStatusCode.OK, response)
        }
    }

    delete("/segments/{key}") {
        val context = call.requireFeatureFlagAdmin(membershipService) ?: return@delete
        val key = call.parameters["key"].orEmpty()
        val deleted = featureFlagService.deleteSegment(context.organizationId, context.userId, key)
        if (deleted) call.respond(HttpStatusCode.NoContent) else call.respondNotFound("Segment not found")
    }
}

private fun Route.sdkKeyManagementRoutes(
    featureFlagService: FeatureFlagService,
    membershipService: OrgMembershipService,
) {
    get("/sdk-keys") {
        val context = call.requireFeatureFlagAdmin(membershipService) ?: return@get
        call.respond(mapOf("keys" to featureFlagService.listSdkKeys(context.organizationId)))
    }

    post("/sdk-keys") {
        call.respondHandlingBadRequest {
            val context = call.requireFeatureFlagAdmin(membershipService) ?: return@respondHandlingBadRequest
            val request = call.receive<FeatureFlagSdkKeyRequest>()
            val response = featureFlagService.createSdkKey(context.organizationId, context.userId, request)
            call.respond(HttpStatusCode.Created, response)
        }
    }

    delete("/sdk-keys/{id}") {
        val context = call.requireFeatureFlagAdmin(membershipService) ?: return@delete
        val id = call.parameters["id"]?.let(::parseFeatureFlagResourceId)
        if (id == null) {
            call.respond(HttpStatusCode.BadRequest, ErrorResponse("Invalid SDK key ID"))
            return@delete
        }
        val revoked = featureFlagService.revokeSdkKey(context.organizationId, context.userId, id)
        if (revoked) call.respond(HttpStatusCode.NoContent) else call.respondNotFound("SDK key not found")
    }
}

private fun Route.featureFlagAuditRoutes(
    featureFlagService: FeatureFlagService,
    membershipService: OrgMembershipService,
) {
    get("/audit") {
        val context = call.requireFeatureFlagAdmin(membershipService) ?: return@get
        val limit = call.request.queryParameters["limit"]?.toIntOrNull()
            ?.coerceIn(1, MAX_AUDIT_LIMIT)
            ?: DEFAULT_AUDIT_LIMIT
        call.respond(mapOf("events" to featureFlagService.listAuditEvents(context.organizationId, limit)))
    }
}

private fun Route.featureFlagAnalyticsRoutes(
    featureFlagService: FeatureFlagService,
    membershipService: OrgMembershipService,
) {
    get("/analytics") {
        val context = call.requireFeatureFlagAdmin(membershipService) ?: return@get
        val environment = call.request.queryParameters["environment"]
        val hours = call.request.queryParameters["hours"]?.toIntOrNull() ?: DEFAULT_ANALYTICS_HOURS
        call.respond(featureFlagService.analytics(context.organizationId, environment, hours))
    }
}

private fun Route.flagManagementRoutes(
    featureFlagService: FeatureFlagService,
    membershipService: OrgMembershipService,
) {
    get {
        val context = call.requireFeatureFlagAdmin(membershipService) ?: return@get
        val environment = call.request.queryParameters["environment"]
        call.respond(featureFlagService.listFlags(context.organizationId, environment))
    }

    post {
        call.respondHandlingBadRequest {
            val context = call.requireFeatureFlagAdmin(membershipService) ?: return@respondHandlingBadRequest
            val request = call.receive<CreateFeatureFlagRequest>()
            val response = featureFlagService.createFlag(context.organizationId, context.userId, request)
            call.respond(HttpStatusCode.Created, response)
        }
    }

    route(FLAG_KEY_PATH) {
        get {
            val context = call.requireFeatureFlagAdmin(membershipService) ?: return@get
            val flagKey = call.parameters["flagKey"].orEmpty()
            val environment = call.request.queryParameters["environment"]
            val flag = featureFlagService.getFlag(context.organizationId, flagKey, environment)
            if (flag == null) call.respondNotFound(FEATURE_FLAG_NOT_FOUND) else call.respond(flag)
        }

        put {
            call.respondHandlingBadRequest {
                val context = call.requireFeatureFlagAdmin(membershipService) ?: return@respondHandlingBadRequest
                val flagKey = call.parameters["flagKey"].orEmpty()
                val request = call.receive<UpdateFeatureFlagRequest>()
                val response = featureFlagService.updateFlag(context.organizationId, context.userId, flagKey, request)
                if (response == null) call.respondNotFound(FEATURE_FLAG_NOT_FOUND) else call.respond(response)
            }
        }

        delete {
            val context = call.requireFeatureFlagAdmin(membershipService) ?: return@delete
            val flagKey = call.parameters["flagKey"].orEmpty()
            val deleted = featureFlagService.archiveFlag(context.organizationId, context.userId, flagKey)
            if (deleted) call.respond(HttpStatusCode.NoContent) else call.respondNotFound(FEATURE_FLAG_NOT_FOUND)
        }

        put("/config/{environmentKey}") {
            call.respondHandlingBadRequest {
                val context = call.requireFeatureFlagAdmin(membershipService) ?: return@respondHandlingBadRequest
                val flagKey = call.parameters["flagKey"].orEmpty()
                val environmentKey = call.parameters["environmentKey"].orEmpty()
                val request = call.receive<UpdateFeatureFlagConfigRequest>()
                val response = featureFlagService.updateConfig(
                    organizationId = context.organizationId,
                    actorUserId = context.userId,
                    flagKey = flagKey,
                    environmentKey = environmentKey,
                    request = request,
                )
                if (response == null) {
                    call.respondNotFound("Feature flag config not found")
                } else {
                    call.respond(response)
                }
            }
        }
    }
}

private fun Route.runtimeFeatureFlagRoutes(
    featureFlagService: FeatureFlagService,
    evaluator: FeatureFlagEvaluator,
    eventService: FeatureFlagEventService,
) {
    route("/ofrep/v1") {
        post("/evaluate/flags/{key}") {
            val principal = call.authenticateSdkKey(featureFlagService) ?: return@post
            val request = call.receive<OfrepEvaluateRequest>()
            val snapshot = featureFlagService.getSnapshot(principal.organizationId, principal.environmentKey)
            if (snapshot == null) {
                call.respond(HttpStatusCode.NotFound, ErrorResponse("Environment not found"))
                return@post
            }
            if (call.ifNoneMatch() == snapshot.etag) {
                call.response.header(HttpHeaders.ETag, snapshot.etag)
                call.respond(HttpStatusCode.NotModified)
                return@post
            }

            val mark = TimeSource.Monotonic.markNow()
            val flagKey = call.parameters["key"].orEmpty()
            val response = evaluator.evaluate(snapshot, flagKey, request.context, request.type, principal.keyType)
            call.response.header(HttpHeaders.ETag, snapshot.etag)
            call.response.header(HttpHeaders.CacheControl, "private, max-age=30")
            eventService.recordEvaluation(
                principal = principal,
                response = response,
                context = request.context,
                durationMs = mark.elapsedNow().inWholeMicroseconds / MICROSECONDS_PER_MILLISECOND,
            )
            call.respond(response.httpStatus(), response.toOfrepJson())
        }

        post("/evaluate/flags") {
            val principal = call.authenticateSdkKey(featureFlagService) ?: return@post
            val request = call.receive<OfrepEvaluateRequest>()
            val snapshot = featureFlagService.getSnapshot(principal.organizationId, principal.environmentKey)
            if (snapshot == null) {
                call.respond(HttpStatusCode.NotFound, ErrorResponse("Environment not found"))
                return@post
            }
            if (call.ifNoneMatch() == snapshot.etag) {
                call.response.header(HttpHeaders.ETag, snapshot.etag)
                call.respond(HttpStatusCode.NotModified)
                return@post
            }

            val mark = TimeSource.Monotonic.markNow()
            val flags = evaluator.evaluateAll(snapshot, request.context, principal.keyType, request.flagKeys)
            call.response.header(HttpHeaders.ETag, snapshot.etag)
            call.response.header(HttpHeaders.CacheControl, "private, max-age=30")
            flags.forEach { response ->
                eventService.recordEvaluation(
                    principal = principal,
                    response = response,
                    context = request.context,
                    durationMs = mark.elapsedNow().inWholeMicroseconds / MICROSECONDS_PER_MILLISECOND,
                )
            }
            call.respond(
                OfrepBulkEvaluationResponse(
                    flags = flags.map { it.toOfrepJson() },
                    metadata = mapOf("etag" to snapshot.etag, "environment" to snapshot.environment.key),
                )
            )
        }

        post("/track") {
            val principal = call.authenticateSdkKey(featureFlagService) ?: return@post
            val request = call.receive<OfrepTrackRequest>()
            if (request.eventName.isBlank()) {
                call.respond(HttpStatusCode.BadRequest, ErrorResponse("eventName is required"))
                return@post
            }
            eventService.recordTrackingEvent(principal, request)
            call.respond(HttpStatusCode.Accepted, mapOf("status" to "accepted"))
        }
    }
}

private suspend fun ApplicationCall.authenticateSdkKey(
    featureFlagService: FeatureFlagService,
): com.moneat.featureflags.models.FeatureFlagSdkKeyPrincipal? {
    val raw = request.headers[HttpHeaders.Authorization]
        ?.removePrefix("Bearer ")
        ?.removePrefix("bearer ")
        ?.trim()
    if (raw.isNullOrBlank()) {
        respond(HttpStatusCode.Unauthorized, ErrorResponse("Missing feature flag SDK key"))
        return null
    }
    val principal = featureFlagService.validateSdkKey(raw)
    if (principal == null) {
        respond(HttpStatusCode.Unauthorized, ErrorResponse("Invalid feature flag SDK key"))
        return null
    }
    return principal
}

private fun ApplicationCall.ifNoneMatch(): String? {
    return request.headers[HttpHeaders.IfNoneMatch]?.trim()
}

private fun parseFeatureFlagResourceId(value: String): Uuid? =
    value.toUuidOrNull()

private fun OfrepFlagEvaluationResponse.httpStatus(): HttpStatusCode {
    return when (errorCode) {
        null -> HttpStatusCode.OK
        ERROR_FLAG_NOT_FOUND -> HttpStatusCode.NotFound
        ERROR_TARGETING_KEY_MISSING,
        ERROR_TYPE_MISMATCH,
        ERROR_INVALID_CONTEXT,
        ERROR_PARSE
        -> HttpStatusCode.BadRequest
        else -> HttpStatusCode.InternalServerError
    }
}

private fun OfrepFlagEvaluationResponse.toOfrepJson(): JsonObject {
    return buildJsonObject {
        put("key", key)
        if (errorCode == null) {
            put("value", value)
            put("reason", reason)
            variant?.let { put("variant", it) }
        } else {
            put("errorCode", errorCode)
            errorDetails?.let { put("errorDetails", it) }
        }
        if (metadata.isNotEmpty()) {
            put("metadata", metadata.toJsonObject())
        }
    }
}

private fun Map<String, String>.toJsonObject(): JsonObject {
    return buildJsonObject {
        this@toJsonObject.forEach { (key, value) -> put(key, value) }
    }
}

private suspend fun ApplicationCall.requireFeatureFlagAdmin(
    membershipService: OrgMembershipService,
): FeatureFlagManagementContext? {
    val principal = principal<JWTPrincipal>()
    val orgId = principal?.currentOrgIdOrNull()
    val userId = principal?.payload?.getClaim("userId")?.asInt()
    if (orgId == null) {
        respond(HttpStatusCode.Forbidden, ErrorResponse(MISSING_ORG_MESSAGE))
        return null
    }
    if (userId == null) {
        respond(HttpStatusCode.Forbidden, ErrorResponse(FORBIDDEN_MESSAGE))
        return null
    }

    val allowed =
        try {
            membershipService.requireRole(orgId, userId, OrgRole.ADMIN)
            true
        } catch (_: IllegalStateException) {
            false
        }
    if (!allowed) {
        respond(HttpStatusCode.Forbidden, ErrorResponse(FORBIDDEN_MESSAGE))
        return null
    }
    return FeatureFlagManagementContext(orgId, userId)
}

private suspend fun ApplicationCall.respondNotFound(message: String) {
    respond(HttpStatusCode.NotFound, ErrorResponse(message))
}

private suspend fun ApplicationCall.respondHandlingBadRequest(block: suspend () -> Unit) {
    try {
        block()
    } catch (e: IllegalArgumentException) {
        respond(HttpStatusCode.BadRequest, ErrorResponse(e.message ?: "Invalid feature flag request"))
    }
}
