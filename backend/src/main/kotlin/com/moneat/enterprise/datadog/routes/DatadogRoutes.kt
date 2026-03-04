// Moneat Enterprise - proprietary module
// Copyright (c) 2026 Moneat. All rights reserved.
// See enterprise/LICENSE for license terms.

package com.moneat.enterprise.datadog.routes

import com.moneat.enterprise.datadog.models.CreateDdApiKeyRequest
import com.moneat.enterprise.datadog.services.DatadogService
import io.ktor.http.HttpStatusCode
import io.ktor.server.application.Application
import io.ktor.server.auth.authenticate
import io.ktor.server.auth.jwt.JWTPrincipal
import io.ktor.server.auth.principal
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
                    val principal = call.principal<JWTPrincipal>()
                    val organizationId = principal?.payload
                        ?.getClaim("orgId")?.asInt()
                        ?: return@get call.respond(
                            HttpStatusCode.Unauthorized,
                            mapOf("error" to "Invalid token")
                        )
                    val keys = DatadogService.listApiKeys(organizationId)
                    call.respond(mapOf("keys" to keys))
                }

                post {
                    val principal = call.principal<JWTPrincipal>()
                    val organizationId = principal?.payload
                        ?.getClaim("orgId")?.asInt()
                        ?: return@post call.respond(
                            HttpStatusCode.Unauthorized,
                            mapOf("error" to "Invalid token")
                        )
                    val userId = principal.payload
                        .getClaim("userId").asInt()
                    val request = call.receive<CreateDdApiKeyRequest>()

                    val response = DatadogService.createApiKey(
                        organizationId = organizationId,
                        name = request.name,
                        userId = userId,
                        projectId = request.projectId,
                    )
                    call.respond(HttpStatusCode.Created, response)
                }

                delete("/{id}") {
                    val principal = call.principal<JWTPrincipal>()
                    val organizationId = principal?.payload
                        ?.getClaim("orgId")?.asInt()
                        ?: return@delete call.respond(
                            HttpStatusCode.Unauthorized,
                            mapOf("error" to "Invalid token")
                        )
                    val keyId = call.parameters["id"]?.toIntOrNull()
                        ?: return@delete call.respond(
                            HttpStatusCode.BadRequest,
                            mapOf("error" to "Invalid key ID")
                        )

                    val deleted = DatadogService.deleteApiKey(
                        keyId, organizationId
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
