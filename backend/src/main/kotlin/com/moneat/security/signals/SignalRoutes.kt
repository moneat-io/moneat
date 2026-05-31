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

package com.moneat.security.signals

import com.moneat.utils.ErrorResponse
import io.ktor.http.HttpStatusCode
import io.ktor.server.auth.authenticate
import io.ktor.server.auth.jwt.JWTPrincipal
import io.ktor.server.auth.principal
import io.ktor.server.request.receive
import io.ktor.server.response.respond
import io.ktor.server.routing.Route
import io.ktor.server.routing.RoutingContext
import io.ktor.server.routing.get
import io.ktor.server.routing.patch
import io.ktor.server.routing.route

private const val DEFAULT_LIMIT = 50
private const val MAX_LIMIT = 200
private const val SIGNAL_ID_ROUTE = "/{signalId}"
private const val INVALID_SIGNAL_ID_MESSAGE = "Invalid signal ID"
private const val SIGNAL_NOT_FOUND_MESSAGE = "Signal not found"
private const val MISSING_ORG_MESSAGE = "No active organization"

/**
 * OSS core signal triage API. Tenant isolation is enforced by the signed `orgId` JWT claim combined
 * with org-scoping in [SignalService] on every query, matching the sibling security read routes; the
 * `userId` claim attributes triage actions in the audit trail.
 */
fun Route.signalRoutes(service: SignalService = SignalService()) {
    route("/v1/security/signals") {
        authenticate("auth-jwt") {
            get { handleList(service) }
            get(SIGNAL_ID_ROUTE) { handleDetail(service) }
            patch(SIGNAL_ID_ROUTE) { handleTriage(service) }
        }
    }
}

private suspend fun RoutingContext.handleList(service: SignalService) {
    val orgId = currentOrganizationId()
        ?: return call.respond(HttpStatusCode.Forbidden, ErrorResponse(MISSING_ORG_MESSAGE))
    val filters = SignalFilters(
        status = call.parameters["status"],
        severity = call.parameters["severity"],
        source = call.parameters["source"],
        from = call.parameters["from"],
        to = call.parameters["to"],
    )
    call.respond(service.list(orgId, filters, paramLimit(), paramOffset()))
}

private suspend fun RoutingContext.handleDetail(service: SignalService) {
    val orgId = currentOrganizationId()
        ?: return call.respond(HttpStatusCode.Forbidden, ErrorResponse(MISSING_ORG_MESSAGE))
    val signalId = signalIdFromPath()
        ?: return call.respond(HttpStatusCode.BadRequest, ErrorResponse(INVALID_SIGNAL_ID_MESSAGE))
    val detail = service.get(orgId, signalId)
        ?: return call.respond(HttpStatusCode.NotFound, ErrorResponse(SIGNAL_NOT_FOUND_MESSAGE))
    call.respond(detail)
}

private suspend fun RoutingContext.handleTriage(service: SignalService) {
    val orgId = currentOrganizationId()
        ?: return call.respond(HttpStatusCode.Forbidden, ErrorResponse(MISSING_ORG_MESSAGE))
    val signalId = signalIdFromPath()
        ?: return call.respond(HttpStatusCode.BadRequest, ErrorResponse(INVALID_SIGNAL_ID_MESSAGE))
    val request = call.receive<TriageRequest>()
    when (val result = service.triage(orgId, signalId, currentUserId(), request)) {
        is TriageResult.Ok -> call.respond(result.signal)
        is TriageResult.NotFound -> call.respond(HttpStatusCode.NotFound, ErrorResponse(SIGNAL_NOT_FOUND_MESSAGE))
        is TriageResult.Invalid -> call.respond(HttpStatusCode.BadRequest, ErrorResponse(result.message))
    }
}

private fun RoutingContext.signalIdFromPath(): Int? = call.parameters["signalId"]?.toIntOrNull()

private fun RoutingContext.currentOrganizationId(): Int? =
    call.principal<JWTPrincipal>()?.payload?.getClaim("orgId")?.asInt()

private fun RoutingContext.currentUserId(): Int? =
    call.principal<JWTPrincipal>()?.payload?.getClaim("userId")?.asInt()

private fun RoutingContext.paramLimit(): Int =
    (call.parameters["limit"]?.toIntOrNull() ?: DEFAULT_LIMIT).coerceIn(0, MAX_LIMIT)

private fun RoutingContext.paramOffset(): Int =
    (call.parameters["offset"]?.toIntOrNull() ?: 0).coerceAtLeast(0)
