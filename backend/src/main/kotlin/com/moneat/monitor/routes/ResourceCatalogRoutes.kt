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

import com.moneat.monitor.services.ResourceCatalogService
import com.moneat.plugins.getDemoEpochMs
import com.moneat.utils.OrganizationContextMissingException
import io.ktor.http.HttpStatusCode
import io.ktor.server.application.ApplicationCall
import io.ktor.server.auth.authenticate
import io.ktor.server.auth.jwt.JWTPrincipal
import io.ktor.server.auth.principal
import io.ktor.server.plugins.BadRequestException
import io.ktor.server.response.respond
import io.ktor.server.routing.Route
import io.ktor.server.routing.get
import io.ktor.server.routing.route
import org.koin.core.context.GlobalContext

private const val MAX_RESOURCE_CATALOG_LIMIT = 500

fun Route.resourceCatalogRoutes(
    resourceCatalogService: ResourceCatalogService = GlobalContext.get().get(),
) {
    route("/v1/monitoring") {
        authenticate("auth-jwt") {
            get("/resources") {
                val organizationId = call.resolveResourceCatalogOrganizationId()
                val limit = call.resolveResourceCatalogLimit()
                val demoEpochMs = call.getDemoEpochMs()
                val resources = if (limit == null) {
                    resourceCatalogService.listResources(listOf(organizationId), demoEpochMs = demoEpochMs)
                } else {
                    resourceCatalogService.listResources(
                        listOf(organizationId),
                        limit,
                        demoEpochMs
                    )
                }

                call.respond(HttpStatusCode.OK, resources)
            }
        }
    }
}

private fun ApplicationCall.resolveResourceCatalogOrganizationId(): Int {
    val principal = principal<JWTPrincipal>()
    return principal?.payload?.getClaim("orgId")?.asInt()
        ?: throw OrganizationContextMissingException()
}

private fun ApplicationCall.resolveResourceCatalogLimit(): Int? {
    val rawLimit = request.queryParameters["limit"] ?: return null
    val parsedLimit = rawLimit.toIntOrNull()
        ?: throw BadRequestException("limit must be an integer")
    return parsedLimit.coerceIn(1, MAX_RESOURCE_CATALOG_LIMIT)
}
