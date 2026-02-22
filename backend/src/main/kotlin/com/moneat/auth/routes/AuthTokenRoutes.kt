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

package com.moneat.auth.routes

import com.moneat.auth.services.AuthTokenService
import com.moneat.events.models.CreateAuthTokenRequest
import com.moneat.events.models.UpdateAuthTokenRequest
import com.moneat.utils.ErrorResponse
import io.ktor.http.HttpStatusCode
import io.ktor.server.auth.authenticate
import io.ktor.server.auth.jwt.JWTPrincipal
import io.ktor.server.auth.principal
import io.ktor.server.request.receive
import io.ktor.server.response.respond
import io.ktor.server.routing.Route
import io.ktor.server.routing.delete
import io.ktor.server.routing.get
import io.ktor.server.routing.post
import io.ktor.server.routing.put
import io.ktor.server.routing.route

fun Route.authTokenRoutes() {
    val authTokenService = AuthTokenService()

    authenticate("auth-jwt") {
        route("/v1/auth-tokens") {
            // Create a new auth token
            post {
                val principal = call.principal<JWTPrincipal>()
                val userId = principal!!.payload.getClaim("userId").asInt()

                val request = call.receive<CreateAuthTokenRequest>()

                try {
                    val tokenResponse =
                        authTokenService.generateToken(
                            userId = userId,
                            name = request.name,
                            scopes = request.scopes,
                            expiresInDays = request.expiresInDays
                        )
                    call.respond(HttpStatusCode.Created, tokenResponse)
                } catch (e: IllegalArgumentException) {
                    call.respond(HttpStatusCode.BadRequest, ErrorResponse(e.message))
                }
            }

            // List all tokens for the current user
            get {
                val principal = call.principal<JWTPrincipal>()
                val userId = principal!!.payload.getClaim("userId").asInt()

                val tokens = authTokenService.listUserTokens(userId)
                call.respond(tokens)
            }

            // Revoke a token
            delete("/{tokenId}") {
                val principal = call.principal<JWTPrincipal>()
                val userId = principal!!.payload.getClaim("userId").asInt()

                val tokenId = call.parameters["tokenId"]?.toIntOrNull()
                if (tokenId == null) {
                    call.respond(HttpStatusCode.BadRequest, ErrorResponse("Invalid token ID"))
                    return@delete
                }

                val success = authTokenService.revokeToken(userId, tokenId)
                if (success) {
                    call.respond(HttpStatusCode.NoContent)
                } else {
                    call.respond(HttpStatusCode.NotFound, ErrorResponse("Token not found"))
                }
            }

            // Update a token (name and/or scopes)
            put("/{tokenId}") {
                val principal = call.principal<JWTPrincipal>()
                val userId = principal!!.payload.getClaim("userId").asInt()

                val tokenId = call.parameters["tokenId"]?.toIntOrNull()
                if (tokenId == null) {
                    call.respond(HttpStatusCode.BadRequest, ErrorResponse("Invalid token ID"))
                    return@put
                }

                val request = call.receive<UpdateAuthTokenRequest>()

                try {
                    val success =
                        authTokenService.updateToken(
                            userId = userId,
                            tokenId = tokenId,
                            name = request.name,
                            scopes = request.scopes
                        )

                    if (success) {
                        call.respond(HttpStatusCode.OK)
                    } else {
                        call.respond(HttpStatusCode.NotFound, ErrorResponse("Token not found"))
                    }
                } catch (e: IllegalArgumentException) {
                    call.respond(HttpStatusCode.BadRequest, ErrorResponse(e.message))
                }
            }
        }
    }
}
