package com.moneat.routes

import com.moneat.config.EnvConfig
import com.moneat.models.Memberships
import com.moneat.models.OrganizationIntegrations
import com.moneat.models.SlackUserMappings
import com.moneat.services.DiscordService
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
import kotlinx.serialization.json.*
import org.jetbrains.exposed.sql.*
import org.jetbrains.exposed.sql.SqlExpressionBuilder.eq
import org.jetbrains.exposed.sql.transactions.transaction
import org.slf4j.LoggerFactory
import java.net.URLEncoder
import java.security.MessageDigest
import java.security.SecureRandom
import java.util.*
import javax.crypto.Mac
import javax.crypto.spec.SecretKeySpec
import kotlin.math.abs

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
    // Fail fast if the signing secret is not configured.
    return EnvConfig.get("JWT_SECRET")
        ?.takeIf { it.isNotBlank() }
        ?: throw IllegalStateException("JWT_SECRET environment variable is required for integration state signing")
}

private fun getSlackSigningSecret(): String? {
    return EnvConfig.get("SLACK_SIGNING_SECRET")?.takeIf { it.isNotBlank() }
}

private fun signSlackRequest(secret: String, timestamp: String, rawBody: String): String {
    val baseString = "v0:$timestamp:$rawBody"
    val mac = Mac.getInstance("HmacSHA256")
    mac.init(SecretKeySpec(secret.toByteArray(Charsets.UTF_8), "HmacSHA256"))
    val digest = mac.doFinal(baseString.toByteArray(Charsets.UTF_8))
    val hex = digest.joinToString("") { "%02x".format(it) }
    return "v0=$hex"
}

private fun verifySlackRequestSignature(headers: Headers, rawBody: String): Boolean {
    val timestamp = headers["X-Slack-Request-Timestamp"] ?: return false
    val signature = headers["X-Slack-Signature"] ?: return false
    val requestTs = timestamp.toLongOrNull() ?: return false
    val nowTs = System.currentTimeMillis() / 1000

    // Reject stale/replayed payloads.
    if (abs(nowTs - requestTs) > 60 * 5) return false

    val secret = getSlackSigningSecret() ?: run {
        logger.error("SLACK_SIGNING_SECRET is not configured; rejecting Slack interaction")
        return false
    }

    val expected = signSlackRequest(secret, timestamp, rawBody)
    return MessageDigest.isEqual(
        expected.toByteArray(Charsets.UTF_8),
        signature.toByteArray(Charsets.UTF_8)
    )
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
    val discordService = DiscordService()
    
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
            
            // Discord OAuth Start
            get("/discord/oauth/start") {
                try {
                    val principal = call.principal<JWTPrincipal>()
                    val userId = principal!!.payload.getClaim("userId").asInt()
                    
                    val organizationId = transaction {
                        Memberships
                            .selectAll()
                            .where { Memberships.user_id eq userId }
                            .firstOrNull()
                            ?.get(Memberships.organization_id)
                    }
                    
                    if (organizationId == null) {
                        return@get call.respond(HttpStatusCode.NotFound, MessageResponse("No organization found"))
                    }
                    
                    val clientId = EnvConfig.get("DISCORD_CLIENT_ID")
                        ?: return@get call.respond(HttpStatusCode.InternalServerError, MessageResponse("Discord client ID not configured"))
                    
                    val redirectUri = EnvConfig.get("DISCORD_REDIRECT_URI")
                        ?: return@get call.respond(HttpStatusCode.InternalServerError, MessageResponse("Discord redirect URI not configured"))
                    
                    // Discord permissions: Send Messages (0x800), Embed Links (0x4000), Read Message History (0x10000), View Channels (0x400)
                    val permissions = 85504 // 0x14C00
                    val scopes = "bot+guilds"
                    
                    val state = generateSecureState(userId, organizationId)
                    
                    val authUrl = "https://discord.com/oauth2/authorize?" +
                        "client_id=$clientId&" +
                        "permissions=$permissions&" +
                        "scope=$scopes&" +
                        "redirect_uri=${URLEncoder.encode(redirectUri, "UTF-8")}&" +
                        "response_type=code&" +
                        "state=$state"
                    
                    call.respond(SlackOAuthStartResponse(authUrl))
                } catch (e: Exception) {
                    logger.error("Error starting Discord OAuth", e)
                    call.respond(HttpStatusCode.InternalServerError, MessageResponse("Error: ${e.message}"))
                }
            }
            
            // Get Discord channels
            get("/discord/channels") {
                val principal = call.principal<JWTPrincipal>()
                val userId = principal!!.payload.getClaim("userId").asInt()
                
                val organizationId = transaction {
                    Memberships
                        .selectAll()
                        .where { Memberships.user_id eq userId }
                        .firstOrNull()
                        ?.get(Memberships.organization_id)
                } ?: return@get call.respond(HttpStatusCode.NotFound, MessageResponse("No organization found"))
                
                val guildId = transaction {
                    OrganizationIntegrations
                        .selectAll()
                        .where {
                            (OrganizationIntegrations.organization_id eq organizationId) and
                            (OrganizationIntegrations.integration_type eq "discord")
                        }
                        .singleOrNull()
                        ?.get(OrganizationIntegrations.team_id)
                }
                
                if (guildId == null) {
                    return@get call.respond(HttpStatusCode.NotFound, MessageResponse("Discord not configured"))
                }
                
                try {
                    val channels = discordService.listChannels(guildId).map { SlackChannel(it.id, it.name) }
                    call.respond(SlackChannelList(channels))
                } catch (e: Exception) {
                    logger.error("Error fetching Discord channels", e)
                    call.respond(HttpStatusCode.InternalServerError, MessageResponse("Error: ${e.message}"))
                }
            }
            
            // Update Discord channel
            put("/discord/channel") {
                val principal = call.principal<JWTPrincipal>()
                val userId = principal!!.payload.getClaim("userId").asInt()
                val selection = call.receive<SlackChannelSelection>()
                
                val organizationId = transaction {
                    Memberships
                        .selectAll()
                        .where { Memberships.user_id eq userId }
                        .firstOrNull()
                        ?.get(Memberships.organization_id)
                } ?: return@put call.respond(HttpStatusCode.NotFound, MessageResponse("No organization found"))
                
                val updated = transaction {
                    OrganizationIntegrations.update({
                        (OrganizationIntegrations.organization_id eq organizationId) and
                        (OrganizationIntegrations.integration_type eq "discord")
                    }) {
                        it[channel_id] = selection.channelId
                        it[channel_name] = selection.channelName
                        it[updated_at] = Clock.System.now()
                    }
                }
                
                if (updated > 0) {
                    logger.info("Discord channel updated for organization $organizationId")
                    call.respond(HttpStatusCode.OK, MessageResponse("Channel updated successfully"))
                } else {
                    call.respond(HttpStatusCode.NotFound, MessageResponse("Integration not found"))
                }
            }
            
            // Toggle Discord integration
            put("/discord/toggle") {
                val principal = call.principal<JWTPrincipal>()
                val userId = principal!!.payload.getClaim("userId").asInt()
                
                val organizationId = transaction {
                    Memberships
                        .selectAll()
                        .where { Memberships.user_id eq userId }
                        .firstOrNull()
                        ?.get(Memberships.organization_id)
                } ?: return@put call.respond(HttpStatusCode.NotFound, MessageResponse("No organization found"))
                
                val updated = transaction {
                    val current = OrganizationIntegrations
                        .selectAll()
                        .where {
                            (OrganizationIntegrations.organization_id eq organizationId) and
                            (OrganizationIntegrations.integration_type eq "discord")
                        }
                        .singleOrNull()
                        ?.get(OrganizationIntegrations.enabled) ?: false
                    
                    OrganizationIntegrations.update({
                        (OrganizationIntegrations.organization_id eq organizationId) and
                        (OrganizationIntegrations.integration_type eq "discord")
                    }) {
                        it[enabled] = !current
                        it[updated_at] = Clock.System.now()
                    }
                }
                
                if (updated > 0) {
                    logger.info("Discord integration toggled for organization $organizationId")
                    call.respond(HttpStatusCode.OK, MessageResponse("Integration toggled successfully"))
                } else {
                    call.respond(HttpStatusCode.NotFound, MessageResponse("Integration not found"))
                }
            }
            
            // Delete Discord integration
            delete("/discord") {
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
                        (organization_id eq organizationId) and (integration_type eq "discord")
                    }
                }
                
                if (deleted > 0) {
                    logger.info("Discord integration deleted for organization $organizationId")
                    call.respond(HttpStatusCode.OK, MessageResponse("Integration deleted successfully"))
                } else {
                    call.respond(HttpStatusCode.NotFound, MessageResponse("Integration not found"))
                }
            }
            
            // Test Discord integration
            post("/discord/test") {
                val principal = call.principal<JWTPrincipal>()
                val userId = principal!!.payload.getClaim("userId").asInt()
                
                val organizationId = transaction {
                    Memberships
                        .selectAll()
                        .where { Memberships.user_id eq userId }
                        .firstOrNull()
                        ?.get(Memberships.organization_id)
                } ?: return@post call.respond(HttpStatusCode.NotFound, MessageResponse("No organization found"))
                
                val frontendUrl = EnvConfig.get("FRONTEND_URL", "https://moneat.io")
                val (success, message) = discordService.testConnection(organizationId, frontendUrl)
                
                call.respond(
                    if (success) HttpStatusCode.OK else HttpStatusCode.BadRequest,
                    TestIntegrationResponse(success, message ?: "Success")
                )
            }
        }
}

// Unauthenticated routes for OAuth callbacks
fun Route.integrationCallbackRoutes() {
    val slackService = SlackService()
    val discordService = DiscordService()
    
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
                val frontendUrl = EnvConfig.get("FRONTEND_URL", "https://moneat.io")
                call.respondRedirect("$frontendUrl/settings?tab=integrations&slack=connected")
            } else {
                logger.error("Slack OAuth failed: ${oauthResponse.error}")
                val frontendUrl = EnvConfig.get("FRONTEND_URL", "https://moneat.io")
                call.respondRedirect("$frontendUrl/settings?tab=integrations&slack=error&message=${URLEncoder.encode(oauthResponse.error ?: "Unknown error", "UTF-8")}")
            }
        }
        
        // Discord OAuth callback
        get("/discord/oauth/callback") {
            val code = call.request.queryParameters["code"]
                ?: return@get call.respond(HttpStatusCode.BadRequest, MessageResponse("Missing code parameter"))
            
            val state = call.request.queryParameters["state"]
                ?: return@get call.respond(HttpStatusCode.BadRequest, MessageResponse("Missing state parameter"))
            
            val (userId, organizationId) = validateAndDecodeState(state)
                ?: return@get call.respond(HttpStatusCode.BadRequest, MessageResponse("Invalid or expired state parameter"))
            
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
            
            val clientId = EnvConfig.get("DISCORD_CLIENT_ID")
                ?: return@get call.respond(HttpStatusCode.InternalServerError, MessageResponse("Discord client ID not configured"))
            val clientSecret = EnvConfig.get("DISCORD_CLIENT_SECRET")
                ?: return@get call.respond(HttpStatusCode.InternalServerError, MessageResponse("Discord client secret not configured"))
            val redirectUri = EnvConfig.get("DISCORD_REDIRECT_URI")
                ?: return@get call.respond(HttpStatusCode.InternalServerError, MessageResponse("Discord redirect URI not configured"))
            
            // Exchange code for access token
            val oauthResponse = discordService.exchangeOAuthCode(code, clientId, clientSecret, redirectUri)
            
            if (oauthResponse.access_token != null && oauthResponse.guild != null) {
                val now = Clock.System.now()
                
                transaction {
                    val existing = OrganizationIntegrations
                        .selectAll()
                        .where {
                            (OrganizationIntegrations.organization_id eq organizationId) and
                            (OrganizationIntegrations.integration_type eq "discord")
                        }
                        .singleOrNull()
                    
                    if (existing != null) {
                        OrganizationIntegrations.update({
                            (OrganizationIntegrations.organization_id eq organizationId) and
                            (OrganizationIntegrations.integration_type eq "discord")
                        }) {
                            it[access_token] = oauthResponse.access_token
                            it[team_id] = oauthResponse.guild.id
                            it[team_name] = oauthResponse.guild.name
                            it[enabled] = true
                            it[updated_at] = now
                        }
                    } else {
                        OrganizationIntegrations.insert {
                            it[organization_id] = organizationId
                            it[integration_type] = "discord"
                            it[access_token] = oauthResponse.access_token
                            it[team_id] = oauthResponse.guild.id
                            it[team_name] = oauthResponse.guild.name
                            it[enabled] = true
                            it[created_at] = now
                            it[updated_at] = now
                        }
                    }
                }
                
                logger.info("Discord OAuth completed for organization $organizationId")
                
                val frontendUrl = EnvConfig.get("FRONTEND_URL", "https://moneat.io")
                call.respondRedirect("$frontendUrl/settings?tab=integrations&discord=connected")
            } else {
                logger.error("Discord OAuth failed: ${oauthResponse.error}")
                val frontendUrl = EnvConfig.get("FRONTEND_URL", "https://moneat.io")
                call.respondRedirect("$frontendUrl/settings?tab=integrations&discord=error&message=${URLEncoder.encode(oauthResponse.error ?: "Unknown error", "UTF-8")}")
            }
        }
        
        // Slack Interactivity Endpoint (for interactive message buttons)
        post("/slack/interactions") {
            try {
                val rawBody = call.receiveText()
                if (!verifySlackRequestSignature(call.request.headers, rawBody)) {
                    call.respond(HttpStatusCode.Unauthorized, mapOf("error" to "Invalid Slack signature"))
                    return@post
                }

                // Parse URL-encoded form data from Slack
                val formParameters = parseQueryString(rawBody)
                val payload = formParameters["payload"] ?: run {
                    call.respond(HttpStatusCode.BadRequest, mapOf("error" to "Missing payload"))
                    return@post
                }
                
                // Parse the JSON payload
                val payloadJson = kotlinx.serialization.json.Json.parseToJsonElement(payload).jsonObject
                val type = payloadJson["type"]?.jsonPrimitive?.content
                
                // Handle block actions (button clicks)
                if (type == "block_actions") {
                    val actions = payloadJson["actions"]?.jsonArray
                    if (actions != null && actions.isNotEmpty()) {
                        val action = actions[0].jsonObject
                        val actionId = action["action_id"]?.jsonPrimitive?.content ?: ""
                        
                        // Parse action ID format: "incident_{action}_{incidentId}"
                        if (actionId.startsWith("incident_acknowledge_")) {
                            val incidentId = actionId.removePrefix("incident_acknowledge_").toIntOrNull()
                            if (incidentId != null) {
                                // Get user from Slack user ID
                                val slackUserId = payloadJson["user"]?.jsonObject?.get("id")?.jsonPrimitive?.content
                                if (slackUserId != null) {
                                    val userId = getUserIdFromSlackUserId(slackUserId)
                                    if (userId != null) {
                                        val incidentService = com.moneat.plugins.getIncidentManagementService()
                                        val acknowledged = incidentService.acknowledge(incidentId, userId)
                                        
                                        if (acknowledged) {
                                            // Send success response
                                            call.respond(mapOf(
                                                "response_type" to "in_channel",
                                                "text" to "✅ Incident acknowledged by <@$slackUserId>"
                                            ))
                                        } else {
                                            call.respond(mapOf(
                                                "response_type" to "ephemeral",
                                                "text" to "❌ Failed to acknowledge incident"
                                            ))
                                        }
                                        return@post
                                    }
                                }
                            }
                        }
                    }
                }
                
                // Default response for unhandled actions
                call.respond(HttpStatusCode.OK, mapOf("text" to "Action received"))
            } catch (e: Exception) {
                logger.error("Error processing Slack interaction", e)
                call.respond(HttpStatusCode.InternalServerError, mapOf("error" to "Internal error"))
            }
        }
        
        // Slack User Linking Endpoint
        authenticate("auth-jwt") {
            post("/slack/link-user") {
                val principal = call.principal<JWTPrincipal>()
                val userId = principal?.payload?.getClaim("user_id")?.asInt()
                
                if (userId == null) {
                    call.respond(HttpStatusCode.Unauthorized, mapOf("error" to "Invalid token"))
                    return@post
                }
                
                @Serializable
                data class LinkUserRequest(val slackUserId: String, val slackTeamId: String)
                
                val request = call.receive<LinkUserRequest>()
                
                try {
                    transaction {
                        // Check if mapping exists
                        val existing = com.moneat.models.SlackUserMappings
                            .selectAll()
                            .where { com.moneat.models.SlackUserMappings.userId eq userId }
                            .singleOrNull()
                        
                        if (existing != null) {
                            // Update existing mapping
                            com.moneat.models.SlackUserMappings.update({
                                com.moneat.models.SlackUserMappings.userId eq userId
                            }) {
                                it[com.moneat.models.SlackUserMappings.slackUserId] = request.slackUserId
                                it[com.moneat.models.SlackUserMappings.slackTeamId] = request.slackTeamId
                                it[com.moneat.models.SlackUserMappings.updatedAt] = kotlinx.datetime.Clock.System.now()
                            }
                        } else {
                            // Insert new mapping
                            com.moneat.models.SlackUserMappings.insert {
                                it[com.moneat.models.SlackUserMappings.userId] = userId
                                it[com.moneat.models.SlackUserMappings.slackUserId] = request.slackUserId
                                it[com.moneat.models.SlackUserMappings.slackTeamId] = request.slackTeamId
                                it[com.moneat.models.SlackUserMappings.createdAt] = kotlinx.datetime.Clock.System.now()
                                it[com.moneat.models.SlackUserMappings.updatedAt] = kotlinx.datetime.Clock.System.now()
                            }
                        }
                    }
                    
                    call.respond(HttpStatusCode.OK, mapOf("message" to "User linked successfully"))
                } catch (e: Exception) {
                    logger.error("Error linking Slack user", e)
                    call.respond(HttpStatusCode.InternalServerError, mapOf("error" to "Failed to link user"))
                }
            }
        }
    }
}

// Helper function to get user ID from Slack user ID
private fun getUserIdFromSlackUserId(slackUserId: String): Int? = transaction {
    com.moneat.models.SlackUserMappings
        .selectAll()
        .where { com.moneat.models.SlackUserMappings.slackUserId eq slackUserId }
        .singleOrNull()
        ?.get(com.moneat.models.SlackUserMappings.userId)
}
