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

package com.moneat.overview.routes

import com.moneat.auth.requireCurrentOrg
import com.moneat.overview.services.OverviewService
import com.moneat.plugins.getDemoEpochMs
import com.moneat.utils.ErrorResponse
import com.moneat.utils.suspendRunCatching
import io.ktor.http.HttpStatusCode
import io.ktor.server.auth.authenticate
import io.ktor.server.response.respond
import io.ktor.server.routing.Route
import io.ktor.server.routing.get
import io.ktor.server.routing.route
import mu.KotlinLogging

private val logger = KotlinLogging.logger {}

fun Route.overviewRoutes(overviewService: OverviewService) {
    route("/v1/overview") {
        authenticate("auth-jwt") {
            get {
                val organizationId = call.requireCurrentOrg()?.orgId ?: return@get
                val demoEpochMs = call.getDemoEpochMs()

                suspendRunCatching {
                    overviewService.getOverview(organizationId, demoEpochMs)
                }.fold(
                    onSuccess = { overview -> call.respond(HttpStatusCode.OK, overview) },
                    onFailure = { error ->
                        logger.error(error) { "Overview aggregation failed for org=$organizationId" }
                        call.respond(HttpStatusCode.InternalServerError, ErrorResponse("Failed to get overview"))
                    },
                )
            }
        }
    }
}
