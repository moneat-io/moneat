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

import com.moneat.auth.requireCurrentOrg
import com.moneat.datadog.models.CreateDdApiKeyRequest
import com.moneat.datadog.services.DatadogService
import io.ktor.http.HttpStatusCode
import io.ktor.server.application.Application
import io.ktor.server.auth.authenticate
import io.ktor.server.request.receive
import io.ktor.server.response.respond
import io.ktor.server.routing.delete
import io.ktor.server.routing.get
import io.ktor.server.routing.post
import io.ktor.server.routing.route
import io.ktor.server.routing.routing

fun Application.datadogRoutes() {
    routing {
        authenticate("auth-jwt") {
            route("/v1/agent-api-keys") {
                get {
                    val organizationId = call.requireCurrentOrg()?.orgId ?: return@get
                    val keys = DatadogService.listApiKeys(organizationId)
                    call.respond(mapOf("keys" to keys))
                }

                post {
                    val context = call.requireCurrentOrg() ?: return@post
                    val request = call.receive<CreateDdApiKeyRequest>()

                    val response = DatadogService.createApiKey(
                        organizationId = context.orgId,
                        name = request.name,
                        userId = context.userId,
                        projectId = request.projectId,
                    )
                    call.respond(HttpStatusCode.Created, response)
                }

                delete("/{id}") {
                    val organizationId = call.requireCurrentOrg()?.orgId ?: return@delete
                    val keyId = call.parameters["id"]?.toIntOrNull()
                        ?: return@delete call.respond(
                            HttpStatusCode.BadRequest,
                            mapOf("error" to "Invalid key ID")
                        )

                    val deleted = DatadogService.deleteApiKey(
                        keyId,
                        organizationId
                    )
                    if (deleted) {
                        call.respond(HttpStatusCode.NoContent)
                    } else {
                        call.respond(
                            HttpStatusCode.NotFound,
                            mapOf("error" to "Key not found")
                        )
                    }
                }
            }
        }
    }
}
