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

import com.moneat.datadog.auth.DatadogAuthMiddleware
import io.ktor.http.HttpStatusCode
import io.ktor.server.response.respond
import io.ktor.server.routing.Route
import io.ktor.server.routing.get
import io.ktor.server.routing.head
import io.ktor.server.routing.route
import kotlinx.serialization.SerialName
import kotlinx.serialization.Serializable

fun Route.datadogValidateRoutes() {
    route("/api/v1") {
        datadogValidateHandlers()
    }

    route("/dd") {
        route("/api/v1") {
            datadogValidateHandlers()
        }
    }
}

private fun Route.datadogValidateHandlers() {
    get("/validate") { handleValidateApiKey() }
    get("/validate/") { handleValidateApiKey() }
    head("/validate") { handleValidateApiKeyHead() }
    head("/validate/") { handleValidateApiKeyHead() }
}

private suspend fun io.ktor.server.routing.RoutingContext.handleValidateApiKey() {
    val orgId = DatadogAuthMiddleware.authenticate(call) ?: return
    call.respond(
        HttpStatusCode.OK,
        DatadogValidationResponse(valid = true, orgId = orgId.toString())
    )
}

private suspend fun io.ktor.server.routing.RoutingContext.handleValidateApiKeyHead() {
    DatadogAuthMiddleware.authenticate(call) ?: return
    call.respond(HttpStatusCode.OK)
}

@Serializable
private data class DatadogValidationResponse(
    val valid: Boolean,
    @SerialName("org_id") val orgId: String,
)
