package com.moneat.ai

import com.moneat.models.Memberships
import io.ktor.http.*
import io.ktor.server.application.*
import io.ktor.server.auth.*
import io.ktor.server.auth.jwt.*
import io.ktor.server.request.*
import io.ktor.server.response.*
import io.ktor.server.routing.*
import mu.KotlinLogging
import org.jetbrains.exposed.sql.selectAll
import org.jetbrains.exposed.sql.transactions.transaction

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
