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

import com.moneat.monitor.models.CatalogResource
import com.moneat.monitor.services.ResourceCatalogService
import com.moneat.shared.models.Memberships
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
import org.koin.core.context.GlobalContext

private const val MAX_RESOURCE_CATALOG_LIMIT = 500

private fun getResourceCatalogOrganizationIds(userId: Int): List<Int> =
    transaction {
        Memberships
            .selectAll()
            .where { Memberships.user_id eq userId }
            .map { it[Memberships.organization_id] }
            .distinct()
    }

fun Route.resourceCatalogRoutes(
    resourceCatalogService: ResourceCatalogService = GlobalContext.get().get(),
) {
    route("/v1/monitoring") {
        authenticate("auth-jwt") {
            get("/resources") {
                val principal = call.principal<JWTPrincipal>()
                val userId = principal!!.payload.getClaim("userId").asInt()
                val organizationIds = getResourceCatalogOrganizationIds(userId)

                if (organizationIds.isEmpty()) {
                    call.respond(HttpStatusCode.OK, emptyList<CatalogResource>())
                    return@get
                }

                val rawLimit = call.request.queryParameters["limit"]?.toIntOrNull()
                val resources = if (rawLimit == null) {
                    resourceCatalogService.listResources(organizationIds)
                } else {
                    resourceCatalogService.listResources(
                        organizationIds,
                        rawLimit.coerceIn(1, MAX_RESOURCE_CATALOG_LIMIT)
                    )
                }

                call.respond(HttpStatusCode.OK, resources)
            }
        }
    }
}
