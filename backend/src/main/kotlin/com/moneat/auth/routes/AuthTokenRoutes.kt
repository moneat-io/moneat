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

import com.moneat.auth.requireCurrentOrg
import com.moneat.auth.services.AuthTokenService
import com.moneat.events.models.CreateAuthTokenRequest
import com.moneat.events.models.UpdateAuthTokenRequest
import com.moneat.shared.services.toUuidOrNull
import com.moneat.utils.ErrorResponse
import io.ktor.http.HttpStatusCode
import io.ktor.server.auth.authenticate
import io.ktor.server.request.receive
import io.ktor.server.response.respond
import io.ktor.server.routing.Route
import io.ktor.server.routing.delete
import io.ktor.server.routing.get
import io.ktor.server.routing.post
import io.ktor.server.routing.put
import io.ktor.server.routing.route
import org.koin.core.context.GlobalContext
import kotlin.uuid.Uuid

fun Route.authTokenRoutes(
    authTokenService: AuthTokenService = GlobalContext.get().get(),
) {
    authenticate("auth-jwt") {
        route("/v1/auth-tokens") {
            post { handleCreateAuthToken(authTokenService) }
            get { handleListAuthTokens(authTokenService) }
            delete("/{tokenId}") { handleRevokeAuthToken(authTokenService) }
            put("/{tokenId}") { handleUpdateAuthToken(authTokenService) }
        }
    }
}

private suspend fun io.ktor.server.routing.RoutingContext.handleCreateAuthToken(
    authTokenService: AuthTokenService,
) {
    val context = call.requireCurrentOrg() ?: return
    val request = call.receive<CreateAuthTokenRequest>()
    try {
        val tokenResponse =
            authTokenService.generateToken(
                userId = context.userId,
                orgId = context.orgId,
                name = request.name,
                scopes = request.scopes,
                expiresInDays = request.expiresInDays
            )
        call.respond(HttpStatusCode.Created, tokenResponse)
    } catch (e: IllegalArgumentException) {
        call.respond(HttpStatusCode.BadRequest, ErrorResponse(e.message))
    }
}

private suspend fun io.ktor.server.routing.RoutingContext.handleListAuthTokens(
    authTokenService: AuthTokenService,
) {
    val context = call.requireCurrentOrg() ?: return
    val tokens = authTokenService.listUserTokens(context.userId)
    call.respond(tokens)
}

private suspend fun io.ktor.server.routing.RoutingContext.handleRevokeAuthToken(
    authTokenService: AuthTokenService,
) {
    val context = call.requireCurrentOrg() ?: return
    val tokenId = call.parameters["tokenId"]?.let(::parseAuthTokenResourceId)
    if (tokenId == null) {
        call.respond(HttpStatusCode.BadRequest, ErrorResponse("Invalid token ID"))
        return
    }
    val success = authTokenService.revokeToken(context.userId, tokenId)
    if (success) {
        call.respond(HttpStatusCode.NoContent)
    } else {
        call.respond(HttpStatusCode.NotFound, ErrorResponse("Token not found"))
    }
}

private suspend fun io.ktor.server.routing.RoutingContext.handleUpdateAuthToken(
    authTokenService: AuthTokenService,
) {
    val context = call.requireCurrentOrg() ?: return
    val tokenId = call.parameters["tokenId"]?.let(::parseAuthTokenResourceId)
    if (tokenId == null) {
        call.respond(HttpStatusCode.BadRequest, ErrorResponse("Invalid token ID"))
        return
    }
    val request = call.receive<UpdateAuthTokenRequest>()
    try {
        val success =
            authTokenService.updateToken(
                userId = context.userId,
                tokenResourceId = tokenId,
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

private fun parseAuthTokenResourceId(value: String): Uuid? =
    value.toUuidOrNull()
