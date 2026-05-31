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

package com.moneat.security.detection

import com.moneat.org.services.OrgMembershipService
import com.moneat.org.services.OrgRole
import com.moneat.utils.ErrorResponse
import com.moneat.utils.suspendRunCatching
import io.ktor.http.HttpStatusCode
import io.ktor.server.auth.authenticate
import io.ktor.server.auth.jwt.JWTPrincipal
import io.ktor.server.auth.principal
import io.ktor.server.request.receive
import io.ktor.server.response.respond
import io.ktor.server.routing.Route
import io.ktor.server.routing.RoutingContext
import io.ktor.server.routing.delete
import io.ktor.server.routing.get
import io.ktor.server.routing.post
import io.ktor.server.routing.put
import io.ktor.server.routing.route
import org.koin.core.context.GlobalContext

private const val RULE_ID_ROUTE = "/{ruleId}"
private const val INVALID_RULE_ID_MESSAGE = "Invalid rule ID"
private const val RULE_NOT_FOUND_MESSAGE = "Detection rule not found"
private const val MISSING_ORG_MESSAGE = "No active organization"
private const val FORBIDDEN_MESSAGE = "Insufficient permissions"
private const val TEMPLATE_NOT_FOUND_MESSAGE = "Detection template not found"
private const val NO_DOCUMENTS_MESSAGE = "No Sigma documents supplied"
private const val MAX_SIGMA_DOCUMENTS = 200

private const val BYTES_PER_MB = 1024 * 1024

/** Aggregate cap on the combined size of all Sigma documents in one request (~8MB), bounding total work. */
private const val MAX_SIGMA_TOTAL_MB = 8
private const val MAX_SIGMA_TOTAL_CHARS = MAX_SIGMA_TOTAL_MB.toLong() * BYTES_PER_MB

/**
 * OSS core detection-rule CRUD + preview. Reads require org MEMBER; mutations and preview require org
 * ADMIN via [OrgMembershipService.requireRole] — because a rule executes server-side ClickHouse
 * queries, exactly like workflow mutations. Org scope comes from the signed `orgId` JWT claim and is
 * applied in [DetectionRuleService] on every query; the rule author never supplies the query's org.
 * DSL-level compile errors map to 400 so no ClickHouse/schema text ever reaches the client.
 */
fun Route.detectionRuleRoutes(
    service: DetectionRuleService = DetectionRuleService(),
    membershipService: OrgMembershipService = GlobalContext.get().get(),
    importService: DetectionImportService = DetectionImportService(service),
) {
    route("/v1/security/detection/rules") {
        authenticate("auth-jwt") {
            get { handleList(service) }
            get(RULE_ID_ROUTE) { handleGet(service) }
            post { handleCreate(service, membershipService) }
            put(RULE_ID_ROUTE) { handleUpdate(service, membershipService) }
            delete(RULE_ID_ROUTE) { handleDelete(service, membershipService) }
            post("$RULE_ID_ROUTE/preview") { handlePreview(service, membershipService) }
        }
    }
    detectionImportRoutes(importService, membershipService)
    detectionTemplateRoutes(importService, membershipService)
}

/**
 * Sigma import (ADMIN). Each mapped rule is created through the compiler-gated path, so an imported rule
 * gets no more power than a hand-authored one; unmappable/non-compiling documents return a per-item,
 * DSL-level error rather than aborting the batch. Created rules are always disabled.
 */
private fun Route.detectionImportRoutes(
    importService: DetectionImportService,
    membershipService: OrgMembershipService,
) {
    route("/v1/security/detection/import/sigma") {
        authenticate("auth-jwt") {
            post { handleSigmaImport(importService, membershipService) }
        }
    }
}

/**
 * Starter-pack templates: list (MEMBER) and install (ADMIN). Install creates a disabled rule via the
 * same compiler-gated path, so a template can never bypass org injection or the column whitelist.
 */
private fun Route.detectionTemplateRoutes(
    importService: DetectionImportService,
    membershipService: OrgMembershipService,
) {
    route("/v1/security/detection/templates") {
        authenticate("auth-jwt") {
            get { handleTemplateList(importService) }
            post("/{templateId}/install") { handleTemplateInstall(importService, membershipService) }
        }
    }
}

private suspend fun RoutingContext.handleList(service: DetectionRuleService) {
    val orgId = currentOrganizationId()
        ?: return call.respond(HttpStatusCode.Forbidden, ErrorResponse(MISSING_ORG_MESSAGE))
    call.respond(service.list(orgId))
}

private suspend fun RoutingContext.handleGet(service: DetectionRuleService) {
    val orgId = currentOrganizationId()
        ?: return call.respond(HttpStatusCode.Forbidden, ErrorResponse(MISSING_ORG_MESSAGE))
    val ruleId = ruleIdFromPath()
        ?: return call.respond(HttpStatusCode.BadRequest, ErrorResponse(INVALID_RULE_ID_MESSAGE))
    val rule = service.get(orgId, ruleId)
        ?: return call.respond(HttpStatusCode.NotFound, ErrorResponse(RULE_NOT_FOUND_MESSAGE))
    call.respond(rule)
}

private suspend fun RoutingContext.handleCreate(
    service: DetectionRuleService,
    membershipService: OrgMembershipService,
) {
    val orgId = requireAdmin(membershipService) ?: return
    val request = call.receive<CreateDetectionRuleRequest>()
    suspendRunCatching { service.create(orgId, request) }.fold(
        onSuccess = { call.respond(HttpStatusCode.Created, it) },
        onFailure = { respondRuleError(it) },
    )
}

private suspend fun RoutingContext.handleUpdate(
    service: DetectionRuleService,
    membershipService: OrgMembershipService,
) {
    val orgId = requireAdmin(membershipService) ?: return
    val ruleId = ruleIdFromPath()
        ?: return call.respond(HttpStatusCode.BadRequest, ErrorResponse(INVALID_RULE_ID_MESSAGE))
    val request = call.receive<UpdateDetectionRuleRequest>()
    suspendRunCatching { service.update(orgId, ruleId, request) }.fold(
        onSuccess = { updated ->
            if (updated == null) {
                call.respond(HttpStatusCode.NotFound, ErrorResponse(RULE_NOT_FOUND_MESSAGE))
            } else {
                call.respond(updated)
            }
        },
        onFailure = { respondRuleError(it) },
    )
}

private suspend fun RoutingContext.handleDelete(
    service: DetectionRuleService,
    membershipService: OrgMembershipService,
) {
    val orgId = requireAdmin(membershipService) ?: return
    val ruleId = ruleIdFromPath()
        ?: return call.respond(HttpStatusCode.BadRequest, ErrorResponse(INVALID_RULE_ID_MESSAGE))
    if (service.delete(orgId, ruleId)) {
        call.respond(HttpStatusCode.NoContent)
    } else {
        call.respond(HttpStatusCode.NotFound, ErrorResponse(RULE_NOT_FOUND_MESSAGE))
    }
}

private suspend fun RoutingContext.handlePreview(
    service: DetectionRuleService,
    membershipService: OrgMembershipService,
) {
    val orgId = requireAdmin(membershipService) ?: return
    val ruleId = ruleIdFromPath()
        ?: return call.respond(HttpStatusCode.BadRequest, ErrorResponse(INVALID_RULE_ID_MESSAGE))
    suspendRunCatching { service.preview(orgId, ruleId) }.fold(
        onSuccess = { preview ->
            if (preview == null) {
                call.respond(HttpStatusCode.NotFound, ErrorResponse(RULE_NOT_FOUND_MESSAGE))
            } else {
                call.respond(preview)
            }
        },
        onFailure = { respondRuleError(it) },
    )
}

private suspend fun RoutingContext.handleSigmaImport(
    importService: DetectionImportService,
    membershipService: OrgMembershipService,
) {
    val orgId = requireAdmin(membershipService) ?: return
    val request = call.receive<SigmaImportRequest>()
    val documents = request.documents.filter { it.isNotBlank() }
    if (documents.isEmpty()) {
        return call.respond(HttpStatusCode.BadRequest, ErrorResponse(NO_DOCUMENTS_MESSAGE))
    }
    if (documents.size > MAX_SIGMA_DOCUMENTS) {
        return call.respond(
            HttpStatusCode.BadRequest,
            ErrorResponse("At most $MAX_SIGMA_DOCUMENTS Sigma documents may be imported at once"),
        )
    }
    if (documents.sumOf { it.length.toLong() } > MAX_SIGMA_TOTAL_CHARS) {
        return call.respond(
            HttpStatusCode.BadRequest,
            ErrorResponse("Sigma import payload is too large (limit ${MAX_SIGMA_TOTAL_MB}MB)"),
        )
    }
    // Per-document mapping/compile failures are returned as item errors; only an unexpected (non-DSL)
    // failure rethrows to the status-pages plugin so no ClickHouse internals leak.
    suspendRunCatching { importService.importSigma(orgId, documents) }.fold(
        onSuccess = { call.respond(HttpStatusCode.OK, it) },
        onFailure = { respondRuleError(it) },
    )
}

private suspend fun RoutingContext.handleTemplateList(importService: DetectionImportService) {
    currentOrganizationId()
        ?: return call.respond(HttpStatusCode.Forbidden, ErrorResponse(MISSING_ORG_MESSAGE))
    call.respond(importService.listTemplates())
}

private suspend fun RoutingContext.handleTemplateInstall(
    importService: DetectionImportService,
    membershipService: OrgMembershipService,
) {
    val orgId = requireAdmin(membershipService) ?: return
    val templateId = call.parameters["templateId"].orEmpty()
    suspendRunCatching { importService.installTemplate(orgId, templateId) }.fold(
        onSuccess = { created ->
            if (created == null) {
                call.respond(HttpStatusCode.NotFound, ErrorResponse(TEMPLATE_NOT_FOUND_MESSAGE))
            } else {
                call.respond(HttpStatusCode.Created, created)
            }
        },
        onFailure = { respondRuleError(it) },
    )
}

/**
 * Resolves the org and enforces ADMIN. Returns the org id when allowed, or responds 403 and returns
 * null. Mirrors the workflow mutation gate; ADMIN is required because rules run server-side queries.
 */
private suspend fun RoutingContext.requireAdmin(membershipService: OrgMembershipService): Int? {
    val orgId = currentOrganizationId() ?: run {
        call.respond(HttpStatusCode.Forbidden, ErrorResponse(MISSING_ORG_MESSAGE))
        return null
    }
    val userId = currentUserId() ?: run {
        call.respond(HttpStatusCode.Forbidden, ErrorResponse(FORBIDDEN_MESSAGE))
        return null
    }
    val allowed = suspendRunCatching {
        membershipService.requireRole(orgId, userId, OrgRole.ADMIN)
        true
    }.getOrElse { false }
    if (!allowed) {
        call.respond(HttpStatusCode.Forbidden, ErrorResponse(FORBIDDEN_MESSAGE))
        return null
    }
    return orgId
}

/**
 * Maps a failure to a client response. [DetectionCompileException] (and any other
 * [IllegalArgumentException]) is a DSL-level validation error → 400 with its safe message. Everything
 * else (incl. [com.moneat.config.ClickHouseQueryException]) rethrows to the status-pages plugin, which
 * returns a generic error so no DB internals leak.
 */
private suspend fun RoutingContext.respondRuleError(error: Throwable) {
    if (error is IllegalArgumentException) {
        call.respond(HttpStatusCode.BadRequest, ErrorResponse(error.message ?: "Invalid detection rule"))
    } else {
        throw error
    }
}

private fun RoutingContext.ruleIdFromPath(): Int? = call.parameters["ruleId"]?.toIntOrNull()

private fun RoutingContext.currentOrganizationId(): Int? =
    call.principal<JWTPrincipal>()?.payload?.getClaim("orgId")?.asInt()

private fun RoutingContext.currentUserId(): Int? =
    call.principal<JWTPrincipal>()?.payload?.getClaim("userId")?.asInt()
