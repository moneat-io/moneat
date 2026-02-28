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

import io.ktor.http.HttpStatusCode
import io.ktor.server.auth.authenticate
import io.ktor.server.response.respond
import io.ktor.server.routing.Route
import io.ktor.server.routing.delete
import io.ktor.server.routing.get
import io.ktor.server.routing.post
import io.ktor.server.routing.route

private val aiUnavailableResponse = mapOf(
    "error" to (
        "AI endpoints are provided by moneat-enterprise. " +
            "Install enterprise module to enable AI."
        ),
)

fun Route.aiChatRoutes() {
    authenticate("auth-jwt") {
        route("/v1/ai") {
            post("/chat") { call.respond(HttpStatusCode.NotImplemented, aiUnavailableResponse) }
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
