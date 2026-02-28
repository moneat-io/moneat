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

package com.moneat.ai

import com.moneat.shared.models.Users
import io.ktor.http.HttpStatusCode
import io.ktor.server.auth.authenticate
import io.ktor.server.auth.jwt.JWTPrincipal
import io.ktor.server.auth.principal
import io.ktor.server.response.respond
import io.ktor.server.routing.Route
import io.ktor.server.routing.delete
import io.ktor.server.routing.get
import io.ktor.server.routing.post
import io.ktor.server.routing.route
import org.jetbrains.exposed.v1.core.eq
import org.jetbrains.exposed.v1.jdbc.selectAll
import org.jetbrains.exposed.v1.jdbc.transactions.transaction

private val aiUnavailableResponse = mapOf(
    "error" to (
        "AI endpoints are provided by moneat-enterprise. " +
            "Install enterprise module to enable AI."
        ),
)

private val aiForbiddenResponse = mapOf(
    "error" to "AI chat is restricted to admin users only.",
)

private suspend fun io.ktor.server.application.ApplicationCall.requireAdmin(): Boolean {
    val userId = principal<JWTPrincipal>()?.payload?.getClaim("userId")?.asInt() ?: return false
    val isAdmin = transaction {
        Users.selectAll().where { Users.id eq userId }.firstOrNull()?.get(Users.is_admin) ?: false
    }
    if (!isAdmin) {
        respond(HttpStatusCode.Forbidden, aiForbiddenResponse)
    }
    return isAdmin
}

fun Route.aiChatRoutes() {
    authenticate("auth-jwt") {
        route("/v1/ai") {
            post("/chat") {
                if (!call.requireAdmin()) return@post
                call.respond(HttpStatusCode.NotImplemented, aiUnavailableResponse)
            }
            post("/chat/stream") { call.respond(HttpStatusCode.NotImplemented, aiUnavailableResponse) }
            post("/chat/confirm") { call.respond(HttpStatusCode.NotImplemented, aiUnavailableResponse) }
            post("/assistant/stream") { call.respond(HttpStatusCode.NotImplemented, aiUnavailableResponse) }
            post("/assistant/confirm") { call.respond(HttpStatusCode.NotImplemented, aiUnavailableResponse) }
            post("/execute-action") { call.respond(HttpStatusCode.NotImplemented, aiUnavailableResponse) }
            get("/conversations") { call.respond(HttpStatusCode.NotImplemented, aiUnavailableResponse) }
            get("/conversations/{id}") { call.respond(HttpStatusCode.NotImplemented, aiUnavailableResponse) }
            delete("/conversations/{id}") { call.respond(HttpStatusCode.NotImplemented, aiUnavailableResponse) }
        }
    }
}
