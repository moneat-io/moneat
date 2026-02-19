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

import com.moneat.models.Memberships
import com.moneat.models.Users
import io.ktor.http.HttpStatusCode
import io.ktor.server.application.call
import io.ktor.server.auth.authenticate
import io.ktor.server.auth.jwt.JWTPrincipal
import io.ktor.server.auth.principal
import io.ktor.server.request.*
import io.ktor.server.response.*
import io.ktor.server.routing.*
import io.ktor.server.routing.route
import mu.KotlinLogging
import org.jetbrains.exposed.v1.core.*
import org.jetbrains.exposed.v1.jdbc.selectAll
import org.jetbrains.exposed.v1.jdbc.transactions.transaction

private val logger = KotlinLogging.logger {}

fun Route.aiChatRoutes() {
    val chatService = AiChatService()
    val actionExecutor = AiActionExecutor()

    authenticate("auth-jwt") {
        route("/v1/ai") {
            post("/chat") {
                if (!OpenAiClient.isEnabled) {
                    call.respond(HttpStatusCode.ServiceUnavailable, mapOf("error" to "AI chat is not enabled"))
                    return@post
                }

                val principal = call.principal<JWTPrincipal>()
                val userId = principal!!.payload.getClaim("userId").asInt()

                // Check if user is admin
                if (!isUserAdmin(userId)) {
                    call.respond(
                        HttpStatusCode.Forbidden,
                        mapOf("error" to "AI chat is only available for admin users")
                    )
                    return@post
                }

                val orgId = getOrgIdForUser(userId)
                if (orgId == null) {
                    call.respond(HttpStatusCode.BadRequest, mapOf("error" to "No organization found"))
                    return@post
                }

                val request = call.receive<ChatRequest>()
                if (request.message.isBlank()) {
                    call.respond(HttpStatusCode.BadRequest, mapOf("error" to "Message cannot be empty"))
                    return@post
                }

                try {
                    val response = chatService.chat(userId, orgId, request)
                    call.respond(response)
                } catch (e: Exception) {
                    logger.error(e) { "AI chat error for user $userId" }
                    call.respond(HttpStatusCode.InternalServerError, mapOf("error" to "Failed to process chat message"))
                }
            }

            post("/execute-action") {
                if (!OpenAiClient.isEnabled) {
                    call.respond(HttpStatusCode.ServiceUnavailable, mapOf("error" to "AI chat is not enabled"))
                    return@post
                }

                val principal = call.principal<JWTPrincipal>()
                val userId = principal!!.payload.getClaim("userId").asInt()

                // Check if user is admin
                if (!isUserAdmin(userId)) {
                    call.respond(
                        HttpStatusCode.Forbidden,
                        mapOf("error" to "AI chat is only available for admin users")
                    )
                    return@post
                }

                val orgId = getOrgIdForUser(userId)
                if (orgId == null) {
                    call.respond(HttpStatusCode.BadRequest, mapOf("error" to "No organization found"))
                    return@post
                }

                val request = call.receive<ExecuteActionRequest>()
                try {
                    val result = actionExecutor.execute(orgId, userId, request.actionId, request.params)
                    call.respond(result)
                } catch (e: Exception) {
                    logger.error(e) { "Action execution error for user $userId" }
                    call.respond(HttpStatusCode.InternalServerError, mapOf("error" to "Failed to execute action"))
                }
            }

            get("/conversations") {
                val principal = call.principal<JWTPrincipal>()
                val userId = principal!!.payload.getClaim("userId").asInt()
                val orgId = getOrgIdForUser(userId)
                if (orgId == null) {
                    call.respond(HttpStatusCode.BadRequest, mapOf("error" to "No organization found"))
                    return@get
                }

                val conversations = chatService.getConversations(userId, orgId)
                call.respond(conversations)
            }

            get("/conversations/{id}") {
                val principal = call.principal<JWTPrincipal>()
                val userId = principal!!.payload.getClaim("userId").asInt()
                val orgId = getOrgIdForUser(userId)
                if (orgId == null) {
                    call.respond(HttpStatusCode.BadRequest, mapOf("error" to "No organization found"))
                    return@get
                }

                val conversationId = call.parameters["id"]?.toIntOrNull()
                if (conversationId == null) {
                    call.respond(HttpStatusCode.BadRequest, mapOf("error" to "Invalid conversation ID"))
                    return@get
                }

                val conversation = chatService.getConversation(conversationId, userId, orgId)
                if (conversation == null) {
                    call.respond(HttpStatusCode.NotFound, mapOf("error" to "Conversation not found"))
                    return@get
                }

                call.respond(conversation)
            }

            delete("/conversations/{id}") {
                val principal = call.principal<JWTPrincipal>()
                val userId = principal!!.payload.getClaim("userId").asInt()
                val orgId = getOrgIdForUser(userId)
                if (orgId == null) {
                    call.respond(HttpStatusCode.BadRequest, mapOf("error" to "No organization found"))
                    return@delete
                }

                val conversationId = call.parameters["id"]?.toIntOrNull()
                if (conversationId == null) {
                    call.respond(HttpStatusCode.BadRequest, mapOf("error" to "Invalid conversation ID"))
                    return@delete
                }

                val deleted = chatService.deleteConversation(conversationId, userId, orgId)
                if (deleted) {
                    call.respond(HttpStatusCode.NoContent)
                } else {
                    call.respond(HttpStatusCode.NotFound, mapOf("error" to "Conversation not found"))
                }
            }
        }
    }
}

private fun getOrgIdForUser(userId: Int): Int? {
    return transaction {
        Memberships.selectAll()
            .where { Memberships.user_id eq userId }
            .firstOrNull()
            ?.get(Memberships.organization_id)
    }
}

private fun isUserAdmin(userId: Int): Boolean {
    return transaction {
        Users.selectAll()
            .where { Users.id eq userId }
            .firstOrNull()
            ?.get(Users.is_admin) ?: false
    }
}
