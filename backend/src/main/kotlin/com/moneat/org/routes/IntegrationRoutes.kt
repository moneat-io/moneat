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

package com.moneat.org.routes

import com.moneat.auth.currentOrgIdOrNull
import com.moneat.config.EnvConfig
import com.moneat.notifications.services.DiscordService
import com.moneat.notifications.services.SlackInboundGateway
import com.moneat.notifications.services.SlackInboundRequestException
import com.moneat.notifications.services.SlackInboundRequestRejection
import com.moneat.notifications.services.SlackInboundRequestType
import com.moneat.notifications.services.SlackCapability
import com.moneat.notifications.services.SlackCapabilityDefinition
import com.moneat.notifications.services.SlackInstallationService
import com.moneat.notifications.services.SlackInstallationHealthStatus
import com.moneat.notifications.services.SlackInstallationSummary
import com.moneat.notifications.services.SlackOAuthGrant
import com.moneat.notifications.services.SlackScopeExplanation
import com.moneat.notifications.services.SlackService
import com.moneat.notifications.services.SlackUserOAuthGrant
import com.moneat.shared.models.Memberships
import com.moneat.shared.models.OrganizationIntegrations
import com.moneat.shared.models.SlackUserMappings
import com.moneat.utils.ErrorResponse
import com.moneat.utils.MessageResponse
import io.ktor.http.HttpStatusCode
import io.ktor.server.application.ApplicationCall
import io.ktor.server.auth.authenticate
import io.ktor.server.auth.jwt.JWTPrincipal
import io.ktor.server.auth.principal
import io.ktor.server.plugins.BadRequestException
import io.ktor.server.plugins.CannotTransformContentToTypeException
import io.ktor.server.request.receive
import io.ktor.server.request.receiveText
import io.ktor.server.response.respond
import io.ktor.server.response.respondRedirect
import io.ktor.server.routing.Route
import io.ktor.server.routing.delete
import io.ktor.server.routing.get
import io.ktor.server.routing.post
import io.ktor.server.routing.put
import io.ktor.server.routing.route
import kotlinx.serialization.Serializable
import org.jetbrains.exposed.v1.core.and
import org.jetbrains.exposed.v1.core.eq
import org.jetbrains.exposed.v1.core.isNotNull
import org.jetbrains.exposed.v1.jdbc.deleteWhere
import org.jetbrains.exposed.v1.jdbc.insert
import org.jetbrains.exposed.v1.jdbc.selectAll
import org.jetbrains.exposed.v1.jdbc.transactions.transaction
import org.jetbrains.exposed.v1.jdbc.update
import org.koin.core.context.GlobalContext
import org.slf4j.LoggerFactory
import java.net.URLEncoder
import java.security.SecureRandom
import java.util.Base64
import javax.crypto.Mac
import javax.crypto.spec.SecretKeySpec
import kotlin.time.Clock
import com.moneat.utils.suspendRunCatching

private val logger = LoggerFactory.getLogger("IntegrationRoutes")
private const val NO_ORGANIZATION_FOUND = "No organization found"
private const val UNAUTHORIZED_MESSAGE = "Unauthorized"
private const val SLACK_INTEGRATION_LABEL = "Slack integration"
private const val MISSING_INSTALLATION_ID = "Missing installation ID"
private const val CHANNEL_UPDATED_MESSAGE = "Channel updated successfully"

@Serializable
data class OrganizationIntegrationResponse(
    val id: String,
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
data class SlackCapabilitiesResponse(
    val capabilities: List<SlackCapabilityDefinition>,
    val scopes: List<SlackScopeExplanation>,
)

@Serializable
data class SlackInstallationEnabledRequest(
    val enabled: Boolean,
)

@Serializable
data class SlackReauthorizationRequest(
    val capabilities: List<String> = emptyList(),
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
private const val NONCE_BYTES_SIZE = 16
private const val LEGACY_OAUTH_PARTS_COUNT = 5
private const val SLACK_OAUTH_PARTS_COUNT = 7
private const val SLACK_OAUTH_INSTALLATION_INDEX = 4
private const val SLACK_OAUTH_CAPABILITIES_INDEX = 5
private const val OAUTH_STATE_MAX_AGE_MS = 600_000
private const val DISCORD_BOT_PERMISSIONS = 85504 // 0x14C00
private val secureRandom = SecureRandom()

private data class OAuthStateContext(
    val userId: Int,
    val organizationId: Int,
    val slackInstallationId: String? = null,
    val slackCapabilityIds: List<String> = emptyList(),
)

private fun getStateSecret(): String {
    // Fail fast if the signing secret is not configured.
    return EnvConfig
        .get("JWT_SECRET")
        ?.takeIf { it.isNotBlank() }
        ?: throw IllegalStateException("JWT_SECRET environment variable is required for integration state signing")
}

private fun generateSecureState(
    userId: Int,
    organizationId: Int,
    slackInstallationId: String? = null,
    slackCapabilityIds: Collection<String> = emptyList(),
): String {
    val nonce = ByteArray(NONCE_BYTES_SIZE)
    secureRandom.nextBytes(nonce)
    val timestamp = System.currentTimeMillis()
    val nonceValue = Base64.getUrlEncoder().withoutPadding().encodeToString(nonce)
    val basePayload = "$userId:$organizationId:$timestamp:$nonceValue"
    val payload = if (slackInstallationId != null || slackCapabilityIds.isNotEmpty()) {
        val installation = slackInstallationId ?: "-"
        val capabilities = slackCapabilityIds.sorted().joinToString(",").ifBlank { "-" }
        "$basePayload:$installation:$capabilities"
    } else {
        basePayload
    }

    // Sign the payload
    val mac = Mac.getInstance("HmacSHA256")
    val secretKey = SecretKeySpec(getStateSecret().toByteArray(), "HmacSHA256")
    mac.init(secretKey)
    val signature = Base64.getUrlEncoder().withoutPadding().encodeToString(mac.doFinal(payload.toByteArray()))

    return Base64.getUrlEncoder().withoutPadding().encodeToString("$payload:$signature".toByteArray())
}

private fun validateAndDecodeState(state: String): OAuthStateContext? {
    suspendRunCatching {
        val decoded = String(Base64.getUrlDecoder().decode(state))
        val parts = decoded.split(":")
        if (parts.size != LEGACY_OAUTH_PARTS_COUNT && parts.size != SLACK_OAUTH_PARTS_COUNT) return null

        val userId = parts[0].toInt()
        val organizationId = parts[1].toInt()
        val timestamp = parts[2].toLong()
        val signature = parts.last()

        // Check if state is expired (10 minutes)
        val age = System.currentTimeMillis() - timestamp
        if (age !in 0..OAUTH_STATE_MAX_AGE_MS) {
            return null
        }

        // Verify signature
        val payload = parts.dropLast(1).joinToString(":")
        val mac = Mac.getInstance("HmacSHA256")
        val secretKey = SecretKeySpec(getStateSecret().toByteArray(), "HmacSHA256")
        mac.init(secretKey)
        val expectedSignature =
            Base64.getUrlEncoder().withoutPadding().encodeToString(
                mac.doFinal(payload.toByteArray())
            )

        if (!MessageDigest.isEqual(signature.toByteArray(), expectedSignature.toByteArray())) {
            return null
        }

        val installationId = parts.getOrNull(SLACK_OAUTH_INSTALLATION_INDEX)?.takeUnless { it == "-" }
        val capabilities = parts.getOrNull(SLACK_OAUTH_CAPABILITIES_INDEX)
            ?.takeUnless { it == "-" }
            ?.split(',')
            .orEmpty()
        return OAuthStateContext(userId, organizationId, installationId, capabilities)
    }.getOrElse { _ ->
        return null
    }
}

private suspend fun ApplicationCall.integrationOrgIdOrRespond(): Int? {
    val principal = principal<JWTPrincipal>()
    if (principal == null) {
        respond(HttpStatusCode.Unauthorized, MessageResponse(UNAUTHORIZED_MESSAGE))
        return null
    }

    val orgId = principal.currentOrgIdOrNull()
    if (orgId == null) {
        respond(HttpStatusCode.NotFound, MessageResponse(NO_ORGANIZATION_FOUND))
        return null
    }
    return orgId
}

private suspend fun ApplicationCall.integrationAdminOrgIdOrRespond(): Int? {
    val principal = principal<JWTPrincipal>()
    val userId = principal?.payload?.getClaim("userId")?.asInt()
    val organizationId = principal?.currentOrgIdOrNull()
    if (userId == null) {
        respond(HttpStatusCode.Unauthorized, MessageResponse(UNAUTHORIZED_MESSAGE))
        return null
    }
    if (organizationId == null) {
        respond(HttpStatusCode.NotFound, MessageResponse(NO_ORGANIZATION_FOUND))
        return null
    }
    val authorized = transaction {
        Memberships
            .selectAll()
            .where {
                (Memberships.user_id eq userId) and
                    (Memberships.organization_id eq organizationId)
            }
            .singleOrNull()
            ?.get(Memberships.role)
            ?.lowercase() in setOf("owner", "admin")
    }
    if (!authorized) {
        respond(HttpStatusCode.Forbidden, MessageResponse("Organization admin access required"))
        return null
    }
    return organizationId
}

private fun buildSlackOAuthStartResponse(
    userId: Int,
    organizationId: Int,
    installationId: String?,
    capabilityIds: Collection<String>,
    teamId: String? = null,
): SlackOAuthStartResponse {
    val clientId = EnvConfig.get("SLACK_CLIENT_ID")
        ?: throw IllegalStateException("Slack client ID not configured")
    val redirectUri = EnvConfig.get("SLACK_REDIRECT_URI")
        ?: throw IllegalStateException("Slack redirect URI not configured")
    val capabilities = SlackCapability.fromIds(capabilityIds)
    val normalizedCapabilityIds = capabilities.map(SlackCapability::id)
    val scopes = SlackCapability.requiredScopes(capabilities).joinToString(",")
    val userScopes = SlackCapability.requiredUserScopes(capabilities).joinToString(",")
    val state = generateSecureState(
        userId = userId,
        organizationId = organizationId,
        slackInstallationId = installationId,
        slackCapabilityIds = normalizedCapabilityIds,
    )
    val authUrl = buildString {
        append("https://slack.com/oauth/v2/authorize?")
        append("client_id=").append(URLEncoder.encode(clientId, "UTF-8"))
        append("&scope=").append(URLEncoder.encode(scopes, "UTF-8"))
        if (userScopes.isNotEmpty()) {
            append("&user_scope=").append(URLEncoder.encode(userScopes, "UTF-8"))
        }
        append("&redirect_uri=").append(URLEncoder.encode(redirectUri, "UTF-8"))
        append("&state=").append(URLEncoder.encode(state, "UTF-8"))
        teamId?.let { append("&team=").append(URLEncoder.encode(it, "UTF-8")) }
    }
    return SlackOAuthStartResponse(authUrl)
}

private fun parseSlackCapabilityIds(values: List<String>?): List<String> =
    values.orEmpty()
        .flatMap { it.split(',') }
        .map(String::trim)
        .filter(String::isNotEmpty)
        .distinct()

private suspend fun ApplicationCall.respondSlackRouteFailure(error: Throwable) {
    when (error) {
        is BadRequestException,
        is CannotTransformContentToTypeException,
        -> respond(HttpStatusCode.BadRequest, MessageResponse(error.message ?: "Bad request"))
        is NoSuchElementException -> respond(HttpStatusCode.NotFound, MessageResponse(error.message ?: "Not found"))
        is IllegalArgumentException -> respond(
            HttpStatusCode.BadRequest,
            MessageResponse(error.message ?: "Bad request"),
        )
        is IllegalStateException -> respond(HttpStatusCode.Conflict, MessageResponse(error.message ?: "Conflict"))
        else -> {
            logger.error("Slack integration request failed", error)
            respond(HttpStatusCode.InternalServerError, MessageResponse("Slack integration request failed"))
        }
    }
}

private fun SlackInstallationService.listInstallationsWithLegacyFallback(
    organizationId: Int,
): List<SlackInstallationSummary> = try {
    listInstallations(organizationId)
} catch (error: Exception) {
    val hasLegacyToken = transaction {
        OrganizationIntegrations
            .selectAll()
            .where {
                (OrganizationIntegrations.organization_id eq organizationId) and
                    (OrganizationIntegrations.integration_type eq "slack") and
                    OrganizationIntegrations.access_token.isNotNull()
            }
            .limit(1)
            .any()
    }
    if (!hasLegacyToken) throw error
    logger.warn("Using the legacy Slack integration until its token can be encrypted", error)
    emptyList()
}

private fun Route.slackInstallationReadRoutes(
    slackService: SlackService,
    installationService: SlackInstallationService,
    entitlementService: com.moneat.billing.services.EntitlementService,
) {
    get("/slack/capabilities") {
        call.respond(
            SlackCapabilitiesResponse(
                capabilities = installationService.capabilityCatalog(),
                scopes = installationService.scopeCatalog(),
            )
        )
    }

    get("/slack/installations") {
        val organizationId = call.integrationOrgIdOrRespond() ?: return@get
        suspendRunCatching { installationService.listInstallations(organizationId) }
            .onSuccess { call.respond(it) }
            .onFailure { call.respondSlackRouteFailure(it) }
    }

    post("/slack/installations/{installationId}/reauthorize") {
        val principal = call.principal<JWTPrincipal>()
            ?: return@post call.respond(HttpStatusCode.Unauthorized, MessageResponse(UNAUTHORIZED_MESSAGE))
        val organizationId = call.integrationAdminOrgIdOrRespond() ?: return@post
        entitlementService.unavailableFeatureMessage(
            organizationId,
            { it.slackEnabled },
            SLACK_INTEGRATION_LABEL,
        )?.let { return@post call.respond(HttpStatusCode.Forbidden, MessageResponse(it)) }
        val installationId = call.parameters["installationId"]
            ?: return@post call.respond(HttpStatusCode.BadRequest, MessageResponse(MISSING_INSTALLATION_ID))
        suspendRunCatching {
            val installation = installationService.listInstallations(organizationId)
                .singleOrNull { it.id == installationId }
                ?: throw NoSuchElementException("Slack installation not found")
            val request = call.receive<SlackReauthorizationRequest>()
            val capabilityIds = request.capabilities.ifEmpty { installation.enabledCapabilities }
            buildSlackOAuthStartResponse(
                userId = principal.payload.getClaim("userId").asInt(),
                organizationId = organizationId,
                installationId = installationId,
                capabilityIds = capabilityIds,
                teamId = installation.teamId,
            )
        }.onSuccess { call.respond(it) }
            .onFailure { call.respondSlackRouteFailure(it) }
    }

    get("/slack/installations/{installationId}/channels") {
        val organizationId = call.integrationOrgIdOrRespond() ?: return@get
        val installationId = call.parameters["installationId"]
            ?: return@get call.respond(HttpStatusCode.BadRequest, MessageResponse(MISSING_INSTALLATION_ID))
        suspendRunCatching {
            val token = installationService.accessToken(organizationId, installationId)
            SlackChannelList(slackService.listChannels(token).map { SlackChannel(it.id, it.name) })
        }.onSuccess { call.respond(it) }
            .onFailure { call.respondSlackRouteFailure(it) }
    }

    get("/slack/installations/{installationId}/usergroups") {
        val organizationId = call.integrationOrgIdOrRespond() ?: return@get
        val installationId = call.parameters["installationId"]
            ?: return@get call.respond(HttpStatusCode.BadRequest, MessageResponse(MISSING_INSTALLATION_ID))
        suspendRunCatching {
            val token = installationService.accessToken(organizationId, installationId)
            slackService.listUsergroups(token)
        }.onSuccess { call.respond(it) }
            .onFailure { call.respondSlackRouteFailure(it) }
    }
}

private fun Route.slackInstallationWriteRoutes(
    installationService: SlackInstallationService,
) {
    put("/slack/installations/{installationId}/channel") {
        val organizationId = call.integrationAdminOrgIdOrRespond() ?: return@put
        val installationId = call.parameters["installationId"]
            ?: return@put call.respond(HttpStatusCode.BadRequest, MessageResponse(MISSING_INSTALLATION_ID))
        suspendRunCatching {
            val request = call.receive<SlackChannelSelection>()
            installationService.updateChannel(
                organizationId,
                installationId,
                request.channelId,
                request.channelName,
            )
        }.onSuccess { call.respond(it) }
            .onFailure { call.respondSlackRouteFailure(it) }
    }

    put("/slack/installations/{installationId}/default") {
        val organizationId = call.integrationAdminOrgIdOrRespond() ?: return@put
        val installationId = call.parameters["installationId"]
            ?: return@put call.respond(HttpStatusCode.BadRequest, MessageResponse(MISSING_INSTALLATION_ID))
        suspendRunCatching { installationService.setDefault(organizationId, installationId) }
            .onSuccess { call.respond(it) }
            .onFailure { call.respondSlackRouteFailure(it) }
    }

    put("/slack/installations/{installationId}/enabled") {
        val organizationId = call.integrationAdminOrgIdOrRespond() ?: return@put
        val installationId = call.parameters["installationId"]
            ?: return@put call.respond(HttpStatusCode.BadRequest, MessageResponse(MISSING_INSTALLATION_ID))
        suspendRunCatching {
            val request = call.receive<SlackInstallationEnabledRequest>()
            installationService.setEnabled(organizationId, installationId, request.enabled)
        }.onSuccess { call.respond(it) }
            .onFailure { call.respondSlackRouteFailure(it) }
    }
}

private fun Route.slackInstallationOperationalRoutes(
    slackService: SlackService,
    installationService: SlackInstallationService,
) {
    post("/slack/installations/{installationId}/health") {
        val organizationId = call.integrationAdminOrgIdOrRespond() ?: return@post
        val installationId = call.parameters["installationId"]
            ?: return@post call.respond(HttpStatusCode.BadRequest, MessageResponse(MISSING_INSTALLATION_ID))
        suspendRunCatching {
            installationService.verifyInstallation(organizationId, installationId, slackService::probeAuthentication)
        }.onSuccess { call.respond(it) }
            .onFailure { call.respondSlackRouteFailure(it) }
    }

    post("/slack/installations/{installationId}/test") {
        val organizationId = call.integrationAdminOrgIdOrRespond() ?: return@post
        val installationId = call.parameters["installationId"]
            ?: return@post call.respond(HttpStatusCode.BadRequest, MessageResponse(MISSING_INSTALLATION_ID))
        suspendRunCatching {
            val config = installationService.deliveryConfig(organizationId, installationId)
                ?: throw IllegalStateException("Select a default Slack channel before sending a test")
            slackService.testConnection(config.accessToken, config.channelId)
        }.onSuccess { (success, message) ->
            call.respond(
                if (success) HttpStatusCode.OK else HttpStatusCode.BadRequest,
                TestIntegrationResponse(success, message),
            )
        }.onFailure { call.respondSlackRouteFailure(it) }
    }
}

private fun Route.slackInstallationDeleteRoute(
    installationService: SlackInstallationService,
) {
    delete("/slack/installations/{installationId}") {
        val organizationId = call.integrationAdminOrgIdOrRespond() ?: return@delete
        val installationId = call.parameters["installationId"]
            ?: return@delete call.respond(HttpStatusCode.BadRequest, MessageResponse(MISSING_INSTALLATION_ID))
        suspendRunCatching { installationService.deleteInstallation(organizationId, installationId) }
            .onSuccess { call.respond(MessageResponse("Slack installation deleted")) }
            .onFailure { call.respondSlackRouteFailure(it) }
    }
}

fun Route.integrationRoutes() {
    val slackService = GlobalContext.get().get<SlackService>()
    val slackInstallationService = GlobalContext.get().get<SlackInstallationService>()
    val discordService = GlobalContext.get().get<DiscordService>()
    val entitlementService = GlobalContext.get().get<com.moneat.billing.services.EntitlementService>()

    route("/integrations") {
        slackInstallationReadRoutes(slackService, slackInstallationService, entitlementService)
        slackInstallationWriteRoutes(slackInstallationService)
        slackInstallationOperationalRoutes(slackService, slackInstallationService)
        slackInstallationDeleteRoute(slackInstallationService)
        // List all integrations for the organization
        get {
            suspendRunCatching {
                val principal = call.principal<JWTPrincipal>()
                if (principal == null) {
                    logger.error("No JWT principal found")
                    return@get call.respond(HttpStatusCode.Unauthorized, MessageResponse("Unauthorized"))
                }

                val userId = principal.payload.getClaim("userId").asInt()
                logger.info("Fetching integrations for user $userId")

                val organizationId = call.integrationOrgIdOrRespond() ?: return@get

                val slackInstallations = slackInstallationService.listInstallationsWithLegacyFallback(organizationId)
                val defaultSlackInstallation = slackInstallations.firstOrNull(SlackInstallationSummary::isDefault)
                val integrations =
                    transaction {
                        OrganizationIntegrations
                            .selectAll()
                            .where { OrganizationIntegrations.organization_id eq organizationId }
                            .map { row ->
                                val isSlack = row[OrganizationIntegrations.integration_type] == "slack"
                                OrganizationIntegrationResponse(
                                    id = if (isSlack) {
                                        defaultSlackInstallation?.id
                                            ?: row[OrganizationIntegrations.resource_id].toString()
                                    } else {
                                        row[OrganizationIntegrations.resource_id].toString()
                                    },
                                    integrationType = row[OrganizationIntegrations.integration_type],
                                    teamName = if (isSlack && defaultSlackInstallation != null) {
                                        defaultSlackInstallation.teamName
                                    } else {
                                        row[OrganizationIntegrations.team_name]
                                    },
                                    channelId = if (isSlack && defaultSlackInstallation != null) {
                                        defaultSlackInstallation.defaultChannelId
                                    } else {
                                        row[OrganizationIntegrations.channel_id]
                                    },
                                    channelName = if (isSlack && defaultSlackInstallation != null) {
                                        defaultSlackInstallation.defaultChannelName
                                    } else {
                                        row[OrganizationIntegrations.channel_name]
                                    },
                                    enabled = if (isSlack && defaultSlackInstallation != null) {
                                        defaultSlackInstallation.enabled
                                    } else {
                                        row[OrganizationIntegrations.enabled]
                                    },
                                    isConfigured = if (isSlack) {
                                        slackInstallations.isNotEmpty() ||
                                            row[OrganizationIntegrations.access_token] != null
                                    } else {
                                        row[OrganizationIntegrations.access_token] != null
                                    },
                                )
                            }
                    }

                logger.info("Returning ${integrations.size} integrations")
                call.respond(integrations)
            }.getOrElse { e ->
                logger.error("Error fetching integrations", e)
                call.respond(HttpStatusCode.InternalServerError, MessageResponse("Error: ${e.message}"))
            }
        }

        // Start Slack OAuth flow
        get("/slack/oauth/start") {
            suspendRunCatching {
                val principal = call.principal<JWTPrincipal>()
                    ?: return@get call.respond(HttpStatusCode.Unauthorized, MessageResponse(UNAUTHORIZED_MESSAGE))
                val userId = principal.payload.getClaim("userId").asInt()
                val organizationId = call.integrationAdminOrgIdOrRespond() ?: return@get

                entitlementService.unavailableFeatureMessage(
                    organizationId,
                    { it.slackEnabled },
                    SLACK_INTEGRATION_LABEL
                )?.let { return@get call.respond(HttpStatusCode.Forbidden, MessageResponse(it)) }

                val capabilityIds = parseSlackCapabilityIds(
                    call.request.queryParameters.getAll("capabilities")
                )
                val installationId = call.request.queryParameters["installationId"]
                val teamId = installationId?.let { id ->
                    slackInstallationService.listInstallations(organizationId)
                        .singleOrNull { it.id == id }
                        ?.teamId
                        ?: throw NoSuchElementException("Slack installation not found")
                }
                call.respond(
                    buildSlackOAuthStartResponse(
                        userId = userId,
                        organizationId = organizationId,
                        installationId = installationId,
                        capabilityIds = capabilityIds,
                        teamId = teamId,
                    )
                )
            }.getOrElse { e ->
                logger.error("Error starting Slack OAuth", e)
                call.respond(HttpStatusCode.InternalServerError, MessageResponse("Error: ${e.message}"))
            }
        }

        // List available Slack channels
        get("/slack/channels") {
            val organizationId = call.integrationOrgIdOrRespond() ?: return@get

            val modeledInstallation = slackInstallationService.listInstallationsWithLegacyFallback(organizationId)
                .firstOrNull(SlackInstallationSummary::isDefault)
            val accessToken = modeledInstallation?.let { installation ->
                slackInstallationService.accessToken(organizationId, installation.id)
            } ?: transaction {
                OrganizationIntegrations
                    .selectAll()
                    .where {
                        (OrganizationIntegrations.organization_id eq organizationId) and
                            (OrganizationIntegrations.integration_type eq "slack")
                    }.singleOrNull()
                    ?.get(OrganizationIntegrations.access_token)
            } ?: return@get call.respond(HttpStatusCode.NotFound, MessageResponse("No Slack integration found"))

            val channels = slackService.listChannels(accessToken)
            call.respond(SlackChannelList(channels.map { SlackChannel(it.id, it.name) }))
        }

        // Update channel selection
        put("/slack/channel") {
            val organizationId = call.integrationAdminOrgIdOrRespond() ?: return@put

            val request = call.receive<SlackChannelSelection>()
            val modeledInstallation = slackInstallationService.listInstallationsWithLegacyFallback(organizationId)
                .firstOrNull(SlackInstallationSummary::isDefault)
            if (modeledInstallation != null) {
                slackInstallationService.updateChannel(
                    organizationId,
                    modeledInstallation.id,
                    request.channelId,
                    request.channelName,
                )
                return@put call.respond(HttpStatusCode.OK, MessageResponse(CHANNEL_UPDATED_MESSAGE))
            }

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

            call.respond(HttpStatusCode.OK, MessageResponse(CHANNEL_UPDATED_MESSAGE))
        }

        // List available Slack user groups
        get("/slack/usergroups") {
            val organizationId = call.integrationOrgIdOrRespond() ?: return@get

            val modeledInstallation = slackInstallationService.listInstallationsWithLegacyFallback(organizationId)
                .firstOrNull(SlackInstallationSummary::isDefault)
            val accessToken = modeledInstallation?.let { installation ->
                slackInstallationService.accessToken(organizationId, installation.id)
            } ?: transaction {
                OrganizationIntegrations
                    .selectAll()
                    .where {
                        (OrganizationIntegrations.organization_id eq organizationId) and
                            (OrganizationIntegrations.integration_type eq "slack")
                    }.singleOrNull()
                    ?.get(OrganizationIntegrations.access_token)
            } ?: return@get call.respond(HttpStatusCode.NotFound, MessageResponse("No Slack integration found"))

            val usergroups = slackService.listUsergroups(accessToken)
            call.respond(usergroups)
        }

        // Toggle enabled status
        put("/slack/toggle") {
            val organizationId = call.integrationAdminOrgIdOrRespond() ?: return@put
            val modeledInstallation = slackInstallationService.listInstallationsWithLegacyFallback(organizationId)
                .firstOrNull(SlackInstallationSummary::isDefault)
            if (modeledInstallation != null) {
                val updated = slackInstallationService.setEnabled(
                    organizationId,
                    modeledInstallation.id,
                    !modeledInstallation.enabled,
                )
                return@put call.respond(
                    HttpStatusCode.OK,
                    MessageResponse("Integration ${if (updated.enabled) "enabled" else "disabled"}"),
                )
            }

            val currentEnabled =
                transaction {
                    OrganizationIntegrations
                        .selectAll()
                        .where {
                            (OrganizationIntegrations.organization_id eq organizationId) and
                                (OrganizationIntegrations.integration_type eq "slack")
                        }.singleOrNull()
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

            call.respond(
                HttpStatusCode.OK,
                MessageResponse("Integration ${if (!currentEnabled) "enabled" else "disabled"}")
            )
        }

        // Delete Slack integration
        delete("/slack") {
            val organizationId = call.integrationAdminOrgIdOrRespond() ?: return@delete
            if (slackInstallationService.listInstallationsWithLegacyFallback(organizationId).isNotEmpty()) {
                return@delete call.respond(
                    HttpStatusCode.Conflict,
                    MessageResponse("Remove Slack workspaces individually from the workspace manager"),
                )
            }

            val deleted =
                transaction {
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
            val organizationId = call.integrationAdminOrgIdOrRespond() ?: return@post

            val (success, message) = slackService.testConnection(organizationId)

            call.respond(
                if (success) HttpStatusCode.OK else HttpStatusCode.BadRequest,
                TestIntegrationResponse(success, message)
            )
        }

        // Discord OAuth Start
        get("/discord/oauth/start") {
            suspendRunCatching {
                val principal = call.principal<JWTPrincipal>()
                val userId = principal!!.payload.getClaim("userId").asInt()

                val organizationId = principal.currentOrgIdOrNull()

                if (organizationId == null) {
                    return@get call.respond(HttpStatusCode.NotFound, MessageResponse(NO_ORGANIZATION_FOUND))
                }

                entitlementService.unavailableFeatureMessage(
                    organizationId,
                    { it.discordEnabled },
                    "Discord integration"
                )?.let { return@get call.respond(HttpStatusCode.Forbidden, MessageResponse(it)) }

                val clientId =
                    EnvConfig.get("DISCORD_CLIENT_ID")
                        ?: return@get call.respond(
                            HttpStatusCode.InternalServerError,
                            MessageResponse("Discord client ID not configured")
                        )

                val redirectUri =
                    EnvConfig.get("DISCORD_REDIRECT_URI")
                        ?: return@get call.respond(
                            HttpStatusCode.InternalServerError,
                            MessageResponse("Discord redirect URI not configured")
                        )

                // Discord permissions: Send Messages (0x800), Embed Links (0x4000),
                // Read Message History (0x10000), View Channels (0x400)
                val permissions = DISCORD_BOT_PERMISSIONS
                val scopes = "bot+guilds"

                val state = generateSecureState(userId, organizationId)

                val authUrl =
                    "https://discord.com/oauth2/authorize?" +
                        "client_id=$clientId&" +
                        "permissions=$permissions&" +
                        "scope=$scopes&" +
                        "redirect_uri=${URLEncoder.encode(redirectUri, "UTF-8")}&" +
                        "response_type=code&" +
                        "state=$state"

                call.respond(SlackOAuthStartResponse(authUrl))
            }.getOrElse { e ->
                logger.error("Error starting Discord OAuth", e)
                call.respond(HttpStatusCode.InternalServerError, MessageResponse("Error: ${e.message}"))
            }
        }

        // Get Discord channels
        get("/discord/channels") {
            val organizationId = call.integrationOrgIdOrRespond() ?: return@get

            val guildId =
                transaction {
                    OrganizationIntegrations
                        .selectAll()
                        .where {
                            (OrganizationIntegrations.organization_id eq organizationId) and
                                (OrganizationIntegrations.integration_type eq "discord")
                        }.singleOrNull()
                        ?.get(OrganizationIntegrations.team_id)
                }

            if (guildId == null) {
                return@get call.respond(HttpStatusCode.NotFound, MessageResponse("Discord not configured"))
            }

            suspendRunCatching {
                val channels = discordService.listChannels(guildId).map { SlackChannel(it.id, it.name) }
                call.respond(SlackChannelList(channels))
            }.getOrElse { e ->
                logger.error("Error fetching Discord channels", e)
                call.respond(HttpStatusCode.InternalServerError, MessageResponse("Error: ${e.message}"))
            }
        }

        // Update Discord channel
        put("/discord/channel") {
            val selection = call.receive<SlackChannelSelection>()

            val organizationId = call.integrationOrgIdOrRespond() ?: return@put

            val updated =
                transaction {
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
                call.respond(HttpStatusCode.OK, MessageResponse(CHANNEL_UPDATED_MESSAGE))
            } else {
                call.respond(HttpStatusCode.NotFound, MessageResponse("Integration not found"))
            }
        }

        // Toggle Discord integration
        put("/discord/toggle") {
            val organizationId = call.integrationOrgIdOrRespond() ?: return@put

            val updated =
                transaction {
                    val current =
                        OrganizationIntegrations
                            .selectAll()
                            .where {
                                (OrganizationIntegrations.organization_id eq organizationId) and
                                    (OrganizationIntegrations.integration_type eq "discord")
                            }.singleOrNull()
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
            val organizationId = call.integrationOrgIdOrRespond() ?: return@delete

            val deleted =
                transaction {
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
            val organizationId = call.integrationOrgIdOrRespond() ?: return@post

            val frontendUrl = EnvConfig.get("FRONTEND_URL") ?: "https://moneat.io"
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
    val slackService = GlobalContext.get().get<SlackService>()
    val slackInstallationService = GlobalContext.get().get<SlackInstallationService>()
    val discordService = GlobalContext.get().get<DiscordService>()
    val entitlementService = GlobalContext.get().get<com.moneat.billing.services.EntitlementService>()

    route("/integrations") {
        // Slack OAuth callback (no auth required - called by Slack)
        get("/slack/oauth/callback") {
            val code =
                call.request.queryParameters["code"]
                    ?: return@get call.respond(HttpStatusCode.BadRequest, MessageResponse("Missing code parameter"))

            val state =
                call.request.queryParameters["state"]
                    ?: return@get call.respond(HttpStatusCode.BadRequest, MessageResponse("Missing state parameter"))

            // Validate and decode the signed state
            val stateContext = validateAndDecodeState(state)
                ?: return@get call.respond(
                    HttpStatusCode.BadRequest,
                    MessageResponse("Invalid or expired state parameter")
                )
            val userId = stateContext.userId
            val organizationId = stateContext.organizationId

            // Verify user still has access to the organization
            val hasAccess =
                transaction {
                    Memberships
                        .selectAll()
                        .where {
                            (Memberships.user_id eq userId) and
                                (Memberships.organization_id eq organizationId)
                        }
                        .firstOrNull()
                        ?.get(Memberships.role)
                        ?.lowercase() in setOf("owner", "admin")
                }

            if (!hasAccess) {
                return@get call.respond(
                    HttpStatusCode.Forbidden,
                    MessageResponse("Organization admin access required"),
                )
            }

            entitlementService.unavailableFeatureMessage(
                organizationId,
                { it.slackEnabled },
                SLACK_INTEGRATION_LABEL
            )?.let { return@get call.respond(HttpStatusCode.Forbidden, MessageResponse(it)) }

            val clientId =
                EnvConfig.get("SLACK_CLIENT_ID")
                    ?: return@get call.respond(
                        HttpStatusCode.InternalServerError,
                        MessageResponse("Slack client ID not configured")
                    )
            val clientSecret =
                EnvConfig.get("SLACK_CLIENT_SECRET")
                    ?: return@get call.respond(
                        HttpStatusCode.InternalServerError,
                        MessageResponse("Slack client secret not configured")
                    )
            val redirectUri =
                EnvConfig.get("SLACK_REDIRECT_URI")
                    ?: return@get call.respond(
                        HttpStatusCode.InternalServerError,
                        MessageResponse("Slack redirect URI not configured")
                    )

            val oauthResponse = slackService.exchangeOAuthCode(code, clientId, clientSecret, redirectUri)

            if (oauthResponse.ok && oauthResponse.accessToken != null) {
                val grant = SlackOAuthGrant(
                    accessToken = oauthResponse.accessToken,
                    teamId = oauthResponse.team?.id,
                    teamName = oauthResponse.team?.name,
                    enterpriseId = oauthResponse.enterprise?.id,
                    enterpriseName = oauthResponse.enterprise?.name,
                    isEnterpriseInstall = oauthResponse.isEnterpriseInstall,
                    appId = oauthResponse.appId,
                    botUserId = oauthResponse.botUserId,
                    grantedScopes = oauthResponse.scope.orEmpty()
                        .split(',', ' ')
                        .map(String::trim)
                        .filter(String::isNotEmpty)
                        .toSet(),
                    tokenType = oauthResponse.tokenType,
                    refreshToken = oauthResponse.refreshToken,
                    expiresInSeconds = oauthResponse.expiresIn,
                    userGrant = oauthResponse.authedUser?.let { user ->
                        user.accessToken?.let { accessToken ->
                            SlackUserOAuthGrant(
                                accessToken = accessToken,
                                slackUserId = user.id,
                                grantedScopes = user.scope.orEmpty()
                                    .split(',', ' ')
                                    .map(String::trim)
                                    .filter(String::isNotEmpty)
                                    .toSet(),
                                tokenType = user.tokenType,
                                refreshToken = user.refreshToken,
                                expiresInSeconds = user.expiresIn,
                            )
                        }
                    },
                )
                val installation = suspendRunCatching {
                    slackInstallationService.storeOAuthGrant(
                        organizationId = organizationId,
                        reauthorizeInstallationId = stateContext.slackInstallationId,
                        capabilityIds = stateContext.slackCapabilityIds,
                        grant = grant,
                    )
                }.getOrElse { error ->
                    logger.error("Unable to store Slack OAuth grant", error)
                    val frontendUrl = EnvConfig.get("FRONTEND_URL")!!
                    return@get call.respondRedirect(
                        "$frontendUrl/settings?tab=integrations&slack=error&message=${URLEncoder.encode(
                            error.message ?: "Unable to store Slack installation",
                            "UTF-8",
                        )}"
                    )
                }

                if (installation.health == SlackInstallationHealthStatus.WORKSPACE_MISMATCH) {
                    val frontendUrl = EnvConfig.get("FRONTEND_URL")!!
                    return@get call.respondRedirect(
                        "$frontendUrl/settings?tab=integrations&slack=error&message=workspace_mismatch"
                    )
                }

                if (installation.isDefault) {
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
                            OrganizationIntegrations.update({
                                (OrganizationIntegrations.organization_id eq organizationId) and
                                    (OrganizationIntegrations.integration_type eq "slack")
                            }) {
                                it[access_token] = null
                                it[bot_user_id] = oauthResponse.botUserId
                                it[team_id] = oauthResponse.team?.id
                                it[team_name] = oauthResponse.team?.name
                                it[enabled] = installation.enabled
                                it[updated_at] = now
                            }
                        } else {
                            OrganizationIntegrations.insert {
                                it[organization_id] = organizationId
                                it[integration_type] = "slack"
                                it[bot_user_id] = oauthResponse.botUserId
                                it[team_id] = oauthResponse.team?.id
                                it[team_name] = oauthResponse.team?.name
                                it[enabled] = installation.enabled
                                it[created_at] = now
                                it[updated_at] = now
                            }
                        }
                    }
                }

                logger.info("Slack OAuth completed for organization $organizationId")

                // Redirect to frontend settings page
                val frontendUrl = EnvConfig.get("FRONTEND_URL")!!
                call.respondRedirect("$frontendUrl/settings?tab=integrations&slack=connected")
            } else {
                logger.error("Slack OAuth failed: ${oauthResponse.error}")
                val frontendUrl = EnvConfig.get("FRONTEND_URL")!!
                call.respondRedirect(
                    "$frontendUrl/settings?tab=integrations&slack=error&message=${URLEncoder.encode(
                        oauthResponse.error ?: "Unknown error",
                        "UTF-8"
                    )}"
                )
            }
        }

        // Discord OAuth callback
        get("/discord/oauth/callback") {
            val code =
                call.request.queryParameters["code"]
                    ?: return@get call.respond(HttpStatusCode.BadRequest, MessageResponse("Missing code parameter"))

            val state =
                call.request.queryParameters["state"]
                    ?: return@get call.respond(HttpStatusCode.BadRequest, MessageResponse("Missing state parameter"))

            val (userId, organizationId) =
                validateAndDecodeState(state)
                    ?: return@get call.respond(
                        HttpStatusCode.BadRequest,
                        MessageResponse("Invalid or expired state parameter")
                    )

            val hasAccess =
                transaction {
                    Memberships
                        .selectAll()
                        .where {
                            (Memberships.user_id eq userId) and
                                (Memberships.organization_id eq organizationId)
                        }.firstOrNull() != null
                }

            if (!hasAccess) {
                return@get call.respond(HttpStatusCode.Forbidden, MessageResponse("Access denied to organization"))
            }

            entitlementService.unavailableFeatureMessage(
                organizationId,
                { it.discordEnabled },
                "Discord integration"
            )?.let { return@get call.respond(HttpStatusCode.Forbidden, MessageResponse(it)) }

            val clientId =
                EnvConfig.get("DISCORD_CLIENT_ID")
                    ?: return@get call.respond(
                        HttpStatusCode.InternalServerError,
                        MessageResponse("Discord client ID not configured")
                    )
            val clientSecret =
                EnvConfig.get("DISCORD_CLIENT_SECRET")
                    ?: return@get call.respond(
                        HttpStatusCode.InternalServerError,
                        MessageResponse("Discord client secret not configured")
                    )
            val redirectUri =
                EnvConfig.get("DISCORD_REDIRECT_URI")
                    ?: return@get call.respond(
                        HttpStatusCode.InternalServerError,
                        MessageResponse("Discord redirect URI not configured")
                    )

            // Exchange code for access token
            val oauthResponse = discordService.exchangeOAuthCode(code, clientId, clientSecret, redirectUri)

            if (oauthResponse.accessToken != null && oauthResponse.guild != null) {
                val now = Clock.System.now()

                transaction {
                    val existing =
                        OrganizationIntegrations
                            .selectAll()
                            .where {
                                (OrganizationIntegrations.organization_id eq organizationId) and
                                    (OrganizationIntegrations.integration_type eq "discord")
                            }.singleOrNull()

                    if (existing != null) {
                        OrganizationIntegrations.update({
                            (OrganizationIntegrations.organization_id eq organizationId) and
                                (OrganizationIntegrations.integration_type eq "discord")
                        }) {
                            it[access_token] = oauthResponse.accessToken
                            it[team_id] = oauthResponse.guild.id
                            it[team_name] = oauthResponse.guild.name
                            it[enabled] = true
                            it[updated_at] = now
                        }
                    } else {
                        OrganizationIntegrations.insert {
                            it[organization_id] = organizationId
                            it[integration_type] = "discord"
                            it[access_token] = oauthResponse.accessToken
                            it[team_id] = oauthResponse.guild.id
                            it[team_name] = oauthResponse.guild.name
                            it[enabled] = true
                            it[created_at] = now
                            it[updated_at] = now
                        }
                    }
                }

                logger.info("Discord OAuth completed for organization $organizationId")

                val frontendUrl = EnvConfig.get("FRONTEND_URL")!!
                call.respondRedirect("$frontendUrl/settings?tab=integrations&discord=connected")
            } else {
                logger.error("Discord OAuth failed: ${oauthResponse.error}")
                val frontendUrl = EnvConfig.get("FRONTEND_URL")!!
                call.respondRedirect(
                    "$frontendUrl/settings?tab=integrations&discord=error&message=${URLEncoder.encode(
                        oauthResponse.error ?: "Unknown error",
                        "UTF-8"
                    )}"
                )
            }
        }

        val slackInboundGateway = GlobalContext.get().get<SlackInboundGateway>()
        fun Route.slackInboundEndpoint(
            path: String,
            requestType: SlackInboundRequestType,
        ) {
            post(path) {
                val rawBody = call.receiveText()
                try {
                    val accepted = slackInboundGateway.accept(call.request.headers, rawBody, requestType)
                    if (accepted.challenge != null) {
                        call.respond(mapOf("challenge" to accepted.challenge))
                    } else {
                        call.respond(HttpStatusCode.Accepted, accepted)
                    }
                } catch (error: SlackInboundRequestException) {
                    val status = when (error.reason) {
                        SlackInboundRequestRejection.INVALID_SIGNATURE -> HttpStatusCode.Unauthorized
                        SlackInboundRequestRejection.INVALID_BODY -> HttpStatusCode.BadRequest
                        SlackInboundRequestRejection.QUEUE_UNAVAILABLE -> HttpStatusCode.ServiceUnavailable
                    }
                    call.respond(status, ErrorResponse(error.message))
                } catch (error: Exception) {
                    logger.error("Error accepting Slack inbound delivery", error)
                    call.respond(HttpStatusCode.InternalServerError, ErrorResponse("Internal error"))
                }
            }
        }

        slackInboundEndpoint("/slack/commands", SlackInboundRequestType.COMMAND)
        slackInboundEndpoint("/slack/events", SlackInboundRequestType.EVENT)
        slackInboundEndpoint("/slack/shortcuts", SlackInboundRequestType.SHORTCUT)
        slackInboundEndpoint("/slack/mentions", SlackInboundRequestType.MENTION)
        slackInboundEndpoint("/slack/interactions", SlackInboundRequestType.INTERACTION)

        // Slack User Linking Endpoint
        authenticate("auth-jwt") {
            post("/slack/link-user") {
                val principal = call.principal<JWTPrincipal>()
                val userId = principal?.payload?.getClaim("userId")?.asInt()
                val organizationId = principal?.currentOrgIdOrNull()

                if (userId == null || organizationId == null) {
                    call.respond(HttpStatusCode.Unauthorized, ErrorResponse("Invalid token"))
                    return@post
                }

                @Serializable
                data class LinkUserRequest(val slackUserId: String, val slackTeamId: String)

                val request = call.receive<LinkUserRequest>()

                suspendRunCatching {
                    val installationId = slackInstallationService.internalInstallationIdForTeam(
                        organizationId,
                        request.slackTeamId,
                    ) ?: throw NoSuchElementException("Slack workspace is not installed for this organization")
                    transaction {
                        val existing =
                            com.moneat.shared.models.SlackUserMappings
                                .selectAll()
                                .where {
                                    (com.moneat.shared.models.SlackUserMappings.userId eq userId) and
                                        (
                                            com.moneat.shared.models.SlackUserMappings.slackInstallationId eq
                                                installationId
                                            )
                                }
                                .singleOrNull()

                        if (existing != null) {
                            com.moneat.shared.models.SlackUserMappings.update({
                                com.moneat.shared.models.SlackUserMappings.id eq
                                    existing[com.moneat.shared.models.SlackUserMappings.id]
                            }) {
                                it[com.moneat.shared.models.SlackUserMappings.slackUserId] = request.slackUserId
                                it[com.moneat.shared.models.SlackUserMappings.slackTeamId] = request.slackTeamId
                                it[com.moneat.shared.models.SlackUserMappings.slackInstallationId] = installationId
                                it[com.moneat.shared.models.SlackUserMappings.updatedAt] = Clock.System.now()
                            }
                        } else {
                            com.moneat.shared.models.SlackUserMappings.insert {
                                it[com.moneat.shared.models.SlackUserMappings.userId] = userId
                                it[com.moneat.shared.models.SlackUserMappings.slackUserId] = request.slackUserId
                                it[com.moneat.shared.models.SlackUserMappings.slackTeamId] = request.slackTeamId
                                it[com.moneat.shared.models.SlackUserMappings.slackInstallationId] = installationId
                                it[com.moneat.shared.models.SlackUserMappings.createdAt] = Clock.System.now()
                                it[com.moneat.shared.models.SlackUserMappings.updatedAt] = Clock.System.now()
                            }
                        }
                    }

                    call.respond(HttpStatusCode.OK, MessageResponse("User linked successfully"))
                }.getOrElse { e ->
                    logger.error("Error linking Slack user", e)
                    call.respond(HttpStatusCode.InternalServerError, ErrorResponse("Failed to link user"))
                }
            }
        }
    }
}
