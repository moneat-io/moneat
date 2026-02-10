package com.moneat.routes

import com.moneat.config.EnvConfig
import com.moneat.models.Memberships
import com.moneat.models.OrganizationIntegrations
import com.moneat.services.SlackService
import io.ktor.http.*
import io.ktor.server.application.*
import io.ktor.server.auth.*
import io.ktor.server.auth.jwt.*
import io.ktor.server.request.*
import io.ktor.server.response.*
import io.ktor.server.routing.*
import kotlinx.datetime.Clock
import kotlinx.serialization.Serializable
import org.jetbrains.exposed.sql.*
import org.jetbrains.exposed.sql.SqlExpressionBuilder.eq
import org.jetbrains.exposed.sql.transactions.transaction
import org.slf4j.LoggerFactory
import java.net.URLEncoder
import java.security.SecureRandom
import java.util.*
import javax.crypto.Mac
import javax.crypto.spec.SecretKeySpec

private val logger = LoggerFactory.getLogger("IntegrationRoutes")

@Serializable
data class OrganizationIntegrationResponse(
    val id: Int,
    val integrationType: String,
    val teamName: String?,
    val channelId: String?,
    val channelName: String?,
    val enabled: Boolean,
    val isConfigured: Boolean
)

@Serializable
data class SlackOAuthStartResponse(
    val authUrl: String
)

@Serializable
data class SlackChannelSelection(
    val channelId: String,
    val channelName: String
)

@Serializable
data class TestIntegrationResponse(
    val success: Boolean,
    val message: String
)

@Serializable
data class MessageResponse(
    val message: String
)

@Serializable
data class SlackChannelList(
    val channels: List<SlackChannel>
)

@Serializable
data class SlackChannel(
    val id: String,
    val name: String
)

// Helper functions for secure state management
private val secureRandom = SecureRandom()

private fun getStateSecret(): String {
    // Use JWT secret or another dedicated secret for state signing
    return System.getenv("JWT_SECRET") ?: "default-secret-change-in-production"
}

private fun generateSecureState(userId: Int, organizationId: Int): String {
    val nonce = ByteArray(16)
    secureRandom.nextBytes(nonce)
    val timestamp = System.currentTimeMillis()
    val payload = "$userId:$organizationId:$timestamp:${Base64.getUrlEncoder().withoutPadding().encodeToString(nonce)}"
    
    // Sign the payload
    val mac = Mac.getInstance("HmacSHA256")
    val secretKey = SecretKeySpec(getStateSecret().toByteArray(), "HmacSHA256")
    mac.init(secretKey)
    val signature = Base64.getUrlEncoder().withoutPadding().encodeToString(mac.doFinal(payload.toByteArray()))
    
    return Base64.getUrlEncoder().withoutPadding().encodeToString("$payload:$signature".toByteArray())
}

private fun validateAndDecodeState(state: String): Pair<Int, Int>? {
    try {
        val decoded = String(Base64.getUrlDecoder().decode(state))
        val parts = decoded.split(":")
        if (parts.size != 5) return null
        
        val userId = parts[0].toInt()
        val organizationId = parts[1].toInt()
        val timestamp = parts[2].toLong()
        val nonce = parts[3]
        val signature = parts[4]
        
        // Check if state is expired (10 minutes)
        if (System.currentTimeMillis() - timestamp > 10 * 60 * 1000) {
            return null
        }
        
        // Verify signature
        val payload = "$userId:$organizationId:$timestamp:$nonce"
        val mac = Mac.getInstance("HmacSHA256")
        val secretKey = SecretKeySpec(getStateSecret().toByteArray(), "HmacSHA256")
        mac.init(secretKey)
        val expectedSignature = Base64.getUrlEncoder().withoutPadding().encodeToString(mac.doFinal(payload.toByteArray()))
        
        if (signature != expectedSignature) {
            return null
        }
        
        return Pair(userId, organizationId)
    } catch (e: Exception) {
        return null
    }
}

fun Route.integrationRoutes() {
    
    val slackService = SlackService()
    
    route("/integrations") {
        
        // List all integrations for the organization
        get {
            try {
                println("DEBUG: Fetching integrations - start")
                val principal = call.principal<JWTPrincipal>()
                println("DEBUG: Principal: $principal")
                if (principal == null) {
                    logger.error("No JWT principal found")
                    return@get call.respond(HttpStatusCode.Unauthorized, MessageResponse("Unauthorized"))
                }
                
                val userId = principal.payload.getClaim("userId").asInt()
                println("DEBUG: UserId: $userId")
                logger.info("Fetching integrations for user $userId")
                
                val organizationId = transaction {
                    Memberships
                        .selectAll()
                        .where { Memberships.user_id eq userId }
                        .firstOrNull()
                        ?.get(Memberships.organization_id)
                }
                println("DEBUG: OrganizationId: $organizationId")
                
                if (organizationId == null) {
                    return@get call.respond(HttpStatusCode.NotFound, MessageResponse("No organization found"))
                }
                
                val integrations = transaction {
                    OrganizationIntegrations
                        .selectAll()
                        .where { OrganizationIntegrations.organization_id eq organizationId }
                        .map { row ->
                            OrganizationIntegrationResponse(
                                id = row[OrganizationIntegrations.id],
                                integrationType = row[OrganizationIntegrations.integration_type],
                                teamName = row[OrganizationIntegrations.team_name],
                                channelId = row[OrganizationIntegrations.channel_id],
                                channelName = row[OrganizationIntegrations.channel_name],
                                enabled = row[OrganizationIntegrations.enabled],
                                isConfigured = row[OrganizationIntegrations.access_token] != null
                            )
                        }
                }
                
                println("DEBUG: Returning ${integrations.size} integrations")
                logger.info("Returning ${integrations.size} integrations")
                call.respond(integrations)
            } catch (e: Exception) {
                println("DEBUG ERROR: ${e.message}")
                e.printStackTrace()
                logger.error("Error fetching integrations", e)
                call.respond(HttpStatusCode.InternalServerError, MessageResponse("Error: ${e.message}"))
            }
        }
        
        // Start Slack OAuth flow
        get("/slack/oauth/start") {
            try {
                println("DEBUG: Starting Slack OAuth")
                val principal = call.principal<JWTPrincipal>()
                val userId = principal!!.payload.getClaim("userId").asInt()
                println("DEBUG: UserId: $userId")
                
                val organizationId = transaction {
                    Memberships
                        .selectAll()
                        .where { Memberships.user_id eq userId }
                        .firstOrNull()
                        ?.get(Memberships.organization_id)
                }
                
                if (organizationId == null) {
                    println("DEBUG: No organization found")
                    return@get call.respond(HttpStatusCode.NotFound, MessageResponse("No organization found"))
                }
                
                println("DEBUG: OrganizationId: $organizationId")
                
                // Debug env loading
                println("DEBUG: EnvConfig.isLoaded(): ${EnvConfig.isLoaded()}")
                println("DEBUG: System.getProperty('user.dir'): ${System.getProperty("user.dir")}")
                println("DEBUG: Checking for SLACK_CLIENT_ID in System.getenv: ${System.getenv("SLACK_CLIENT_ID")}")
                
                val clientId = EnvConfig.get("SLACK_CLIENT_ID")
                println("DEBUG: EnvConfig.get('SLACK_CLIENT_ID'): $clientId")
                if (clientId == null) {
                    println("DEBUG: SLACK_CLIENT_ID not configured")
                    return@get call.respond(HttpStatusCode.InternalServerError, MessageResponse("Slack client ID not configured"))
                }
                
                val redirectUri = EnvConfig.get("SLACK_REDIRECT_URI")
                if (redirectUri == null) {
                    println("DEBUG: SLACK_REDIRECT_URI not configured")
                    return@get call.respond(HttpStatusCode.InternalServerError, MessageResponse("Slack redirect URI not configured"))
                }
                
                println("DEBUG: ClientId: $clientId")
                println("DEBUG: RedirectUri: $redirectUri")
                
                val scopes = "chat:write,channels:read,channels:join,groups:read,groups:write"
                
                // Generate secure state parameter bound to user and org
                val state = generateSecureState(userId, organizationId)
                
                val authUrl = "https://slack.com/oauth/v2/authorize?" +
                    "client_id=$clientId&" +
                    "scope=$scopes&" +
                    "redirect_uri=${URLEncoder.encode(redirectUri, "UTF-8")}&" +
                    "state=$state"
                
                println("DEBUG: Generated authUrl: $authUrl")
                call.respond(SlackOAuthStartResponse(authUrl))
            } catch (e: Exception) {
                println("DEBUG ERROR in OAuth start: ${e.message}")
                e.printStackTrace()
                logger.error("Error starting Slack OAuth", e)
                call.respond(HttpStatusCode.InternalServerError, MessageResponse("Error: ${e.message}"))
            }
        }
            
            // List available Slack channels
            get("/slack/channels") {
                val principal = call.principal<JWTPrincipal>()
                val userId = principal!!.payload.getClaim("userId").asInt()
                
                val organizationId = transaction {
                    Memberships
                        .selectAll()
                        .where { Memberships.user_id eq userId }
                        .firstOrNull()
                        ?.get(Memberships.organization_id)
                } ?: return@get call.respond(HttpStatusCode.NotFound, MessageResponse("No organization found"))
                
                val accessToken = transaction {
                    OrganizationIntegrations
                        .selectAll()
                        .where {
                            (OrganizationIntegrations.organization_id eq organizationId) and
                            (OrganizationIntegrations.integration_type eq "slack")
                        }
                        .singleOrNull()
                        ?.get(OrganizationIntegrations.access_token)
                } ?: return@get call.respond(HttpStatusCode.NotFound, MessageResponse("No Slack integration found"))
                
                val channels = slackService.listChannels(accessToken)
                call.respond(SlackChannelList(channels.map { SlackChannel(it.id, it.name) }))
            }
            
            // Update channel selection
            put("/slack/channel") {
                val principal = call.principal<JWTPrincipal>()
                val userId = principal!!.payload.getClaim("userId").asInt()
                
                val organizationId = transaction {
                    Memberships
                        .selectAll()
                        .where { Memberships.user_id eq userId }
                        .firstOrNull()
                        ?.get(Memberships.organization_id)
                } ?: return@put call.respond(HttpStatusCode.NotFound, MessageResponse("No organization found"))
                
                val request = call.receive<SlackChannelSelection>()
                
                transaction {
                    OrganizationIntegrations.update({
                        (OrganizationIntegrations.organization_id eq organizationId) and
                        (OrganizationIntegrations.integration_type eq "slack")
                    }) {
                        it[channel_id] = request.channelId
                        it[channel_name] = request.channelName
                        it[updated_at] = Clock.System.now()
                    }
                }
                
                call.respond(HttpStatusCode.OK, MessageResponse("Channel updated successfully"))
            }
            
            // Toggle enabled status
            put("/slack/toggle") {
                val principal = call.principal<JWTPrincipal>()
                val userId = principal!!.payload.getClaim("userId").asInt()
                
                val organizationId = transaction {
                    Memberships
                        .selectAll()
                        .where { Memberships.user_id eq userId }
                        .firstOrNull()
                        ?.get(Memberships.organization_id)
                } ?: return@put call.respond(HttpStatusCode.NotFound, MessageResponse("No organization found"))
                
                val currentEnabled = transaction {
                    OrganizationIntegrations
                        .selectAll()
                        .where {
                            (OrganizationIntegrations.organization_id eq organizationId) and
                            (OrganizationIntegrations.integration_type eq "slack")
                        }
                        .singleOrNull()
                        ?.get(OrganizationIntegrations.enabled)
                } ?: false
                
                transaction {
                    OrganizationIntegrations.update({
                        (OrganizationIntegrations.organization_id eq organizationId) and
                        (OrganizationIntegrations.integration_type eq "slack")
                    }) {
                        it[enabled] = !currentEnabled
                        it[updated_at] = Clock.System.now()
                    }
                }
                
                call.respond(HttpStatusCode.OK, MessageResponse("Integration ${if (!currentEnabled) "enabled" else "disabled"}"))
            }
            
            // Delete Slack integration
            delete("/slack") {
                val principal = call.principal<JWTPrincipal>()
                val userId = principal!!.payload.getClaim("userId").asInt()
                
                val organizationId = transaction {
                    Memberships
                        .selectAll()
                        .where { Memberships.user_id eq userId }
                        .firstOrNull()
                        ?.get(Memberships.organization_id)
                } ?: return@delete call.respond(HttpStatusCode.NotFound, MessageResponse("No organization found"))
                
                val deleted = transaction {
                    OrganizationIntegrations.deleteWhere {
                        (organization_id eq organizationId) and (integration_type eq "slack")
                    }
                }
                
                if (deleted > 0) {
                    logger.info("Slack integration deleted for organization $organizationId")
                    call.respond(HttpStatusCode.OK, MessageResponse("Integration deleted successfully"))
                } else {
                    call.respond(HttpStatusCode.NotFound, MessageResponse("Integration not found"))
                }
            }
            
            // Test Slack integration
            post("/slack/test") {
                val principal = call.principal<JWTPrincipal>()
                val userId = principal!!.payload.getClaim("userId").asInt()
                
                val organizationId = transaction {
                    Memberships
                        .selectAll()
                        .where { Memberships.user_id eq userId }
                        .firstOrNull()
                        ?.get(Memberships.organization_id)
                } ?: return@post call.respond(HttpStatusCode.NotFound, MessageResponse("No organization found"))
                
                val (success, message) = slackService.testConnection(organizationId)
                
                call.respond(
                    if (success) HttpStatusCode.OK else HttpStatusCode.BadRequest,
                    TestIntegrationResponse(success, message)
                )
            }
        }
}

// Unauthenticated routes for OAuth callbacks
fun Route.integrationCallbackRoutes() {
    val slackService = SlackService()
    
    route("/integrations") {
        // Slack OAuth callback (no auth required - called by Slack)
        get("/slack/oauth/callback") {
            val code = call.request.queryParameters["code"]
                ?: return@get call.respond(HttpStatusCode.BadRequest, MessageResponse("Missing code parameter"))
            
            val state = call.request.queryParameters["state"]
                ?: return@get call.respond(HttpStatusCode.BadRequest, MessageResponse("Missing state parameter"))
            
            // Validate and decode the signed state
            val (userId, organizationId) = validateAndDecodeState(state)
                ?: return@get call.respond(HttpStatusCode.BadRequest, MessageResponse("Invalid or expired state parameter"))
            
            // Verify user still has access to the organization
            val hasAccess = transaction {
                Memberships.selectAll()
                    .where {
                        (Memberships.user_id eq userId) and
                        (Memberships.organization_id eq organizationId)
                    }
                    .firstOrNull() != null
            }
            
            if (!hasAccess) {
                return@get call.respond(HttpStatusCode.Forbidden, MessageResponse("Access denied to organization"))
            }
            
            val clientId = EnvConfig.get("SLACK_CLIENT_ID")
                ?: return@get call.respond(HttpStatusCode.InternalServerError, MessageResponse("Slack client ID not configured"))
            val clientSecret = EnvConfig.get("SLACK_CLIENT_SECRET")
                ?: return@get call.respond(HttpStatusCode.InternalServerError, MessageResponse("Slack client secret not configured"))
            val redirectUri = EnvConfig.get("SLACK_REDIRECT_URI")
                ?: return@get call.respond(HttpStatusCode.InternalServerError, MessageResponse("Slack redirect URI not configured"))
            
            val oauthResponse = slackService.exchangeOAuthCode(code, clientId, clientSecret, redirectUri)
            
            if (oauthResponse.ok && oauthResponse.access_token != null) {
                val now = Clock.System.now()
                
                transaction {
                    val existing = OrganizationIntegrations
                        .selectAll()
                        .where {
                            (OrganizationIntegrations.organization_id eq organizationId) and
                            (OrganizationIntegrations.integration_type eq "slack")
                        }
                        .singleOrNull()
                    
                    if (existing != null) {
                        // Update existing
                        OrganizationIntegrations.update({
                            (OrganizationIntegrations.organization_id eq organizationId) and
                            (OrganizationIntegrations.integration_type eq "slack")
                        }) {
                            it[access_token] = oauthResponse.access_token
                            it[bot_user_id] = oauthResponse.bot_user_id
                            it[team_id] = oauthResponse.team?.id
                            it[team_name] = oauthResponse.team?.name
                            it[enabled] = true
                            it[updated_at] = now
                        }
                    } else {
                        // Insert new
                        OrganizationIntegrations.insert {
                            it[organization_id] = organizationId
                            it[integration_type] = "slack"
                            it[access_token] = oauthResponse.access_token
                            it[bot_user_id] = oauthResponse.bot_user_id
                            it[team_id] = oauthResponse.team?.id
                            it[team_name] = oauthResponse.team?.name
                            it[enabled] = true
                            it[created_at] = now
                            it[updated_at] = now
                        }
                    }
                }
                
                logger.info("Slack OAuth completed for organization $organizationId")
                
                // Redirect to frontend settings page
                val frontendUrl = EnvConfig.get("FRONTEND_URL", "http://localhost:5173")
                call.respondRedirect("$frontendUrl/settings?tab=integrations&slack=connected")
            } else {
                logger.error("Slack OAuth failed: ${oauthResponse.error}")
                val frontendUrl = EnvConfig.get("FRONTEND_URL", "http://localhost:5173")
                call.respondRedirect("$frontendUrl/settings?tab=integrations&slack=error&message=${URLEncoder.encode(oauthResponse.error ?: "Unknown error", "UTF-8")}")
            }
        }
    }
}
