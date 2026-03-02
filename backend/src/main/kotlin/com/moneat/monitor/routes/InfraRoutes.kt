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

package com.moneat.monitor.routes

import com.moneat.monitor.services.MonitorService
import com.moneat.shared.models.Memberships
import com.moneat.utils.ErrorResponse
import io.ktor.http.HttpStatusCode
import io.ktor.server.auth.authenticate
import io.ktor.server.auth.jwt.JWTPrincipal
import io.ktor.server.auth.principal
import io.ktor.server.response.respond
import io.ktor.server.routing.Route
import io.ktor.server.routing.get
import io.ktor.server.routing.route
import org.jetbrains.exposed.v1.core.eq
import org.jetbrains.exposed.v1.jdbc.selectAll
import org.jetbrains.exposed.v1.jdbc.transactions.transaction

/**
 * Returns distinct containers (latest per host+container_id) for infra/MCP API.
 * Deduplicates time-series rows so each container appears once.
 */
private const val DEFAULT_LIMIT = 100
private const val MIN_LIMIT = 1
private const val MAX_LIMIT = 500

fun Route.infraRoutes(monitorService: MonitorService = MonitorService()) {
    authenticate("auth-jwt") {
        route("/v1/infra") {
            get("containers") {
                val principal = call.principal<JWTPrincipal>()
                val userId = principal!!.payload.getClaim("userId").asInt()

                val organizationIds =
                    transaction {
                        Memberships
                            .selectAll()
                            .where { Memberships.user_id eq userId }
                            .map { it[Memberships.organization_id] }
                            .distinct()
                    }
                if (organizationIds.isEmpty()) {
                    call.respond(HttpStatusCode.Forbidden, ErrorResponse("No organization access"))
                    return@get
                }

                val params = call.request.queryParameters
                val hostFilter = params["host"]?.takeIf { it.isNotBlank() }
                val limit = (params["limit"]?.toIntOrNull() ?: DEFAULT_LIMIT).coerceIn(MIN_LIMIT, MAX_LIMIT)

                val containers = monitorService.getLatestInfraContainers(organizationIds, hostFilter, limit)
                call.respond(
                    mapOf(
                        "containers" to containers,
                        "totalCount" to containers.size
                    )
                )
            }
        }
    }
}
