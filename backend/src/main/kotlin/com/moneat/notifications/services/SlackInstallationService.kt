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

package com.moneat.notifications.services

import com.moneat.secrets.PurposeScopedSecretCipher
import com.moneat.secrets.SecretVaultPurpose
import com.moneat.shared.models.OrganizationIntegrations
import com.moneat.shared.models.SlackInstallationGrants
import com.moneat.shared.models.SlackInstallations
import com.moneat.shared.models.SlackWorkspaceBindings
import com.moneat.shared.services.toUuidOrNull
import kotlinx.serialization.Serializable
import org.jetbrains.exposed.v1.core.ResultRow
import org.jetbrains.exposed.v1.core.SortOrder
import org.jetbrains.exposed.v1.core.and
import org.jetbrains.exposed.v1.core.eq
import org.jetbrains.exposed.v1.core.isNotNull
import org.jetbrains.exposed.v1.core.isNull
import org.jetbrains.exposed.v1.jdbc.deleteWhere
import org.jetbrains.exposed.v1.jdbc.insert
import org.jetbrains.exposed.v1.jdbc.selectAll
import org.jetbrains.exposed.v1.jdbc.transactions.transaction
import org.jetbrains.exposed.v1.jdbc.update
import kotlin.time.Clock
import kotlin.time.Duration.Companion.seconds

enum class SlackGrantType {
    BOT,
    USER,
}

@Serializable
enum class SlackInstallationHealthStatus {
    HEALTHY,
    MISSING_SCOPES,
    TOKEN_REVOKED,
    BOT_REMOVED,
    WORKSPACE_MISMATCH,
    REAUTHORIZATION_REQUIRED,
    DEGRADED,
    DISABLED,
}

@Serializable
enum class SlackCapability(
    val id: String,
    val label: String,
    val description: String,
    val botScopes: Set<String>,
    val userScopes: Set<String> = emptySet(),
) {
    ALERT_DELIVERY(
        id = "alert_delivery",
        label = "Alert delivery",
        description = "Send alert and incident updates to selected Slack conversations.",
        botScopes = setOf(
            "chat:write",
            "chat:write.public",
            "channels:read",
            "channels:join",
            "groups:read",
        ),
    ),
    INCIDENT_COMMANDS(
        id = "incident_commands",
        label = "Incident commands and mentions",
        description = "Handle slash commands, shortcuts, and app mentions during response.",
        botScopes = setOf("commands", "app_mentions:read", "chat:write"),
    ),
    INCIDENT_CHANNELS(
        id = "incident_channels",
        label = "Incident channels",
        description = "Create, join, and manage public or private incident channels.",
        botScopes = setOf(
            "channels:read",
            "channels:manage",
            "channels:join",
            "groups:read",
            "groups:write",
            "chat:write",
        ),
    ),
    INCIDENT_CONTEXT(
        id = "incident_context",
        label = "Incident context",
        description = "Manage bookmarks, pins, reactions, and files attached to an incident.",
        botScopes = setOf(
            "bookmarks:read",
            "bookmarks:write",
            "pins:read",
            "pins:write",
            "reactions:read",
            "reactions:write",
            "files:read",
            "files:write",
        ),
    ),
    INCIDENT_HISTORY(
        id = "incident_history",
        label = "Incident history",
        description = "Read incident-channel and direct-message history for timelines and summaries.",
        botScopes = setOf("channels:history", "groups:history", "im:history", "mpim:history"),
    ),
    IDENTITY(
        id = "identity",
        label = "Slack identity matching",
        description = "Match Slack users to Moneat responders and organization members.",
        botScopes = setOf("users:read", "users:read.email"),
    ),
    ON_CALL_USERGROUPS(
        id = "on_call_usergroups",
        label = "On-call user groups",
        description = "Read and update Slack user groups that mirror on-call schedules.",
        botScopes = setOf("usergroups:read", "usergroups:write", "users:read"),
    ),
    ASSISTANT(
        id = "assistant",
        label = "Slack Assistant",
        description = "Offer the optional AI assistant in Slack direct messages.",
        botScopes = setOf("assistant:write", "im:history", "im:write", "chat:write"),
    ),
    PRIVILEGED_ACCESS(
        id = "privileged_access",
        label = "Privileged workspace access",
        description = "Use a separately authorized Slack admin or owner for restricted channel and user-group actions.",
        botScopes = emptySet(),
        userScopes = setOf("channels:write", "groups:write", "usergroups:write", "admin.conversations:write"),
    );

    val scopes: Set<String>
        get() = botScopes + userScopes

    companion object {
        val defaults: Set<SlackCapability> = setOf(ALERT_DELIVERY, ON_CALL_USERGROUPS)

        fun fromIds(ids: Collection<String>): Set<SlackCapability> {
            if (ids.isEmpty()) return defaults
            val byId = entries.associateBy(SlackCapability::id)
            return ids.mapTo(linkedSetOf()) { id ->
                requireNotNull(byId[id]) { "Unknown Slack capability: $id" }
            }
        }

        fun requiredScopes(capabilities: Collection<SlackCapability>): Set<String> =
            capabilities.flatMapTo(sortedSetOf(), SlackCapability::botScopes)

        fun requiredUserScopes(capabilities: Collection<SlackCapability>): Set<String> =
            capabilities.flatMapTo(sortedSetOf(), SlackCapability::userScopes)
    }
}

@Serializable
data class SlackScopeExplanation(
    val scope: String,
    val reason: String,
    val capabilities: List<String>,
)

@Serializable
data class SlackCapabilityDefinition(
    val id: String,
    val label: String,
    val description: String,
    val scopes: List<String>,
    val botScopes: List<String>,
    val userScopes: List<String>,
    val optional: Boolean,
)

@Serializable
data class SlackWorkspaceBindingSummary(
    val id: String,
    val teamId: String,
    val teamName: String?,
    val enterpriseId: String?,
    val isPrimary: Boolean,
    val enabled: Boolean,
    val health: SlackInstallationHealthStatus,
    val healthDetail: String?,
    val lastVerifiedAt: String?,
)

@Serializable
data class SlackGrantSummary(
    val id: String,
    val grantType: SlackGrantType,
    val slackUserId: String?,
    val tokenType: String?,
    val grantedScopes: List<String>,
    val expiresAt: String?,
    val rotatedAt: String?,
    val revokedAt: String?,
    val health: SlackInstallationHealthStatus,
    val healthDetail: String?,
    val lastVerifiedAt: String?,
)

@Serializable
data class SlackCapabilityHealth(
    val capabilityId: String,
    val missingBotScopes: List<String>,
    val missingUserScopes: List<String>,
    val healthy: Boolean,
)

@Serializable
data class SlackInstallationSummary(
    val id: String,
    val teamId: String?,
    val teamName: String?,
    val enterpriseId: String?,
    val enterpriseName: String?,
    val isEnterpriseInstall: Boolean,
    val appId: String?,
    val botUserId: String?,
    val grantedScopes: List<String>,
    val grantedUserScopes: List<String>,
    val enabledCapabilities: List<String>,
    val missingScopes: List<String>,
    val workspaceBindings: List<SlackWorkspaceBindingSummary>,
    val grants: List<SlackGrantSummary>,
    val capabilityHealth: List<SlackCapabilityHealth>,
    val defaultChannelId: String?,
    val defaultChannelName: String?,
    val isDefault: Boolean,
    val enabled: Boolean,
    val health: SlackInstallationHealthStatus,
    val healthDetail: String?,
    val lastVerifiedAt: String?,
    val createdAt: String,
    val updatedAt: String,
)

data class SlackOAuthGrant(
    val accessToken: String,
    val teamId: String?,
    val teamName: String?,
    val enterpriseId: String?,
    val enterpriseName: String?,
    val isEnterpriseInstall: Boolean,
    val appId: String?,
    val botUserId: String?,
    val grantedScopes: Set<String>,
    val tokenType: String? = "bot",
    val refreshToken: String? = null,
    val expiresInSeconds: Long? = null,
    val userGrant: SlackUserOAuthGrant? = null,
)

data class SlackUserOAuthGrant(
    val accessToken: String,
    val slackUserId: String,
    val grantedScopes: Set<String>,
    val tokenType: String? = "user",
    val refreshToken: String? = null,
    val expiresInSeconds: Long? = null,
)

data class SlackAuthenticationProbe(
    val ok: Boolean,
    val teamId: String? = null,
    val enterpriseId: String? = null,
    val botUserId: String? = null,
    val error: String? = null,
)

data class SlackDeliveryConfig(
    val installationId: String,
    val accessToken: String,
    val teamId: String?,
    val botUserId: String?,
    val channelId: String,
)

internal interface SlackTokenCipher {
    val activeKeyId: String

    fun encrypt(plaintext: String, organizationId: Int): String

    fun decrypt(ciphertext: String, organizationId: Int): String
}

private class NotificationSlackTokenCipher(
    private val delegate: PurposeScopedSecretCipher,
) : SlackTokenCipher {
    override val activeKeyId: String = delegate.activeKeyId

    override fun encrypt(plaintext: String, organizationId: Int): String = delegate.encrypt(plaintext, organizationId)

    override fun decrypt(ciphertext: String, organizationId: Int): String = delegate.decrypt(ciphertext, organizationId)
}

class SlackInstallationService internal constructor(
    private val cipherFactory: () -> SlackTokenCipher,
) {
    constructor() : this(
        cipherFactory = {
            NotificationSlackTokenCipher(
                PurposeScopedSecretCipher.fromEnv(SecretVaultPurpose.NOTIFICATION),
            )
        },
    )

    fun capabilityCatalog(): List<SlackCapabilityDefinition> =
        SlackCapability.entries.map { capability ->
            SlackCapabilityDefinition(
                id = capability.id,
                label = capability.label,
                description = capability.description,
                scopes = capability.scopes.sorted(),
                botScopes = capability.botScopes.sorted(),
                userScopes = capability.userScopes.sorted(),
                optional = capability == SlackCapability.ASSISTANT ||
                    capability == SlackCapability.PRIVILEGED_ACCESS,
            )
        }

    fun scopeCatalog(): List<SlackScopeExplanation> {
        val capabilitiesByScope = linkedMapOf<String, MutableList<SlackCapability>>()
        SlackCapability.entries.forEach { capability ->
            capability.scopes.forEach { scope ->
                capabilitiesByScope.getOrPut(scope, ::mutableListOf).add(capability)
            }
        }
        return capabilitiesByScope.entries.sortedBy(Map.Entry<String, *>::key).map { (scope, capabilities) ->
            SlackScopeExplanation(
                scope = scope,
                reason = capabilities.joinToString(" ") { it.description },
                capabilities = capabilities.map(SlackCapability::id).sorted(),
            )
        }
    }

    fun requestedScopes(capabilityIds: Collection<String>): Set<String> =
        SlackCapability.requiredScopes(SlackCapability.fromIds(capabilityIds))

    fun requestedUserScopes(capabilityIds: Collection<String>): Set<String> =
        SlackCapability.requiredUserScopes(SlackCapability.fromIds(capabilityIds))

    fun listInstallations(organizationId: Int): List<SlackInstallationSummary> {
        migrateLegacyInstallations(organizationId)
        return transaction {
            SlackInstallations
                .selectAll()
                .where { SlackInstallations.organizationId eq organizationId }
                .orderBy(SlackInstallations.isDefault to SortOrder.DESC, SlackInstallations.createdAt to SortOrder.ASC)
                .map(::summary)
        }
    }

    fun storeOAuthGrant(
        organizationId: Int,
        reauthorizeInstallationId: String?,
        capabilityIds: Collection<String>,
        grant: SlackOAuthGrant,
    ): SlackInstallationSummary {
        validateOAuthGrant(grant)
        val scopeState = oauthScopeState(capabilityIds, grant)
        val cipher = cipherFactory()
        val now = Clock.System.now()

        return transaction {
            val existing = reauthorizeInstallationId?.let { requireInstallation(organizationId, it) }
                ?: findBySlackIdentity(organizationId, grant.teamId, grant.enterpriseId, grant.isEnterpriseInstall)
            if (existing != null && !sameSlackIdentity(existing, grant)) {
                return@transaction markWorkspaceMismatch(organizationId, existing, now)
            }

            val persistence = SlackOAuthPersistence(
                organizationId = organizationId,
                existing = existing,
                grant = grant,
                scopeState = scopeState,
                now = now,
            )
            val installationId = upsertOAuthInstallation(persistence)
            persistOAuthGrants(persistence, installationId, cipher)
            persistOAuthWorkspaceBinding(persistence, installationId)
            clearLegacyToken(existing, now)
            summary(requireInstallationById(organizationId, installationId))
        }
    }

    private fun validateOAuthGrant(grant: SlackOAuthGrant) {
        require(grant.teamId != null || grant.enterpriseId != null) {
            "Slack OAuth response did not identify a workspace or enterprise"
        }
        require(!grant.isEnterpriseInstall || grant.enterpriseId != null) {
            "Enterprise Slack installations require enterprise context"
        }
        require(grant.grantedScopes.all(String::isNotBlank)) { "Slack returned an invalid scope grant" }
    }

    private fun oauthScopeState(
        capabilityIds: Collection<String>,
        grant: SlackOAuthGrant,
    ): SlackOAuthScopeState {
        val capabilities = SlackCapability.fromIds(capabilityIds)
        val requestedUserScopes = SlackCapability.requiredUserScopes(capabilities)
        val missingBotScopes = SlackCapability.requiredScopes(capabilities) - grant.grantedScopes
        val missingUserScopes = requestedUserScopes - grant.userGrant?.grantedScopes.orEmpty()
        val health = if (missingBotScopes.isEmpty() && missingUserScopes.isEmpty()) {
            SlackInstallationHealthStatus.HEALTHY
        } else {
            SlackInstallationHealthStatus.MISSING_SCOPES
        }
        val detail = (missingBotScopes + missingUserScopes).takeIf(Set<String>::isNotEmpty)?.let {
            "Missing Slack scopes: ${it.sorted().joinToString(", ")}"
        }
        return SlackOAuthScopeState(
            capabilities = capabilities,
            requestedUserScopes = requestedUserScopes,
            missingBotScopes = missingBotScopes,
            missingUserScopes = missingUserScopes,
            health = health,
            detail = detail,
        )
    }

    private fun markWorkspaceMismatch(
        organizationId: Int,
        existing: ResultRow,
        now: kotlin.time.Instant,
    ): SlackInstallationSummary {
        SlackInstallations.update({ SlackInstallations.id eq existing[SlackInstallations.id] }) {
            it[healthStatus] = SlackInstallationHealthStatus.WORKSPACE_MISMATCH.name
            it[healthDetail] = "Slack returned a different workspace or enterprise during reauthorization."
            it[updatedAt] = now
        }
        return summary(requireInstallationById(organizationId, existing[SlackInstallations.id]))
    }

    private fun upsertOAuthInstallation(request: SlackOAuthPersistence): Int {
        val existing = request.existing
        if (existing == null) return insertOAuthInstallation(request)
        val installationId = existing[SlackInstallations.id]
        SlackInstallations.update({ SlackInstallations.id eq installationId }) {
            it[teamName] = request.grant.teamName
            it[enterpriseId] = request.grant.enterpriseId
            it[enterpriseName] = request.grant.enterpriseName
            it[isEnterpriseInstall] = request.grant.isEnterpriseInstall
            it[appId] = request.grant.appId
            it[enabledCapabilities] = request.scopeState.capabilities.map(SlackCapability::id).toCsv()
            it[enabled] = true
            it[healthStatus] = request.scopeState.health.name
            it[healthDetail] = request.scopeState.detail
            it[lastVerifiedAt] = request.now
            it[updatedAt] = request.now
        }
        return installationId
    }

    private fun insertOAuthInstallation(request: SlackOAuthPersistence): Int {
        val hasDefaultWorkspace = SlackInstallations
            .selectAll()
            .where {
                (SlackInstallations.organizationId eq request.organizationId) and
                    (SlackInstallations.isDefault eq true)
            }
            .limit(1)
            .any()
        return SlackInstallations.insert {
            it[SlackInstallations.organizationId] = request.organizationId
            it[teamId] = request.grant.teamId
            it[teamName] = request.grant.teamName
            it[enterpriseId] = request.grant.enterpriseId
            it[enterpriseName] = request.grant.enterpriseName
            it[isEnterpriseInstall] = request.grant.isEnterpriseInstall
            it[appId] = request.grant.appId
            it[enabledCapabilities] = request.scopeState.capabilities.map(SlackCapability::id).toCsv()
            it[isDefault] = request.grant.teamId != null && !request.grant.isEnterpriseInstall && !hasDefaultWorkspace
            it[enabled] = true
            it[healthStatus] = request.scopeState.health.name
            it[healthDetail] = request.scopeState.detail
            it[lastVerifiedAt] = request.now
            it[createdAt] = request.now
            it[updatedAt] = request.now
        } get SlackInstallations.id
    }

    private fun persistOAuthGrants(
        request: SlackOAuthPersistence,
        installationId: Int,
        cipher: SlackTokenCipher,
    ) {
        upsertGrant(
            request = botGrantUpsert(request, installationId),
            cipher = cipher,
            now = request.now,
        )
        val userGrant = request.grant.userGrant
        if (userGrant == null) {
            revokeMissingUserGrant(request, installationId)
        } else {
            upsertGrant(
                request = userGrantUpsert(request, installationId, userGrant),
                cipher = cipher,
                now = request.now,
            )
        }
    }

    private fun botGrantUpsert(request: SlackOAuthPersistence, installationId: Int): GrantUpsert = GrantUpsert(
        organizationId = request.organizationId,
        installationId = installationId,
        grantType = SlackGrantType.BOT,
        slackUserId = request.grant.botUserId,
        tokenType = request.grant.tokenType,
        accessToken = request.grant.accessToken,
        refreshToken = request.grant.refreshToken,
        scopes = request.grant.grantedScopes,
        expiresInSeconds = request.grant.expiresInSeconds,
        health = request.scopeState.missingBotScopes.toGrantHealth(request.scopeState.health),
        healthDetail = request.scopeState.missingBotScopes.missingScopeDetail("bot"),
    )

    private fun userGrantUpsert(
        request: SlackOAuthPersistence,
        installationId: Int,
        userGrant: SlackUserOAuthGrant,
    ): GrantUpsert = GrantUpsert(
        organizationId = request.organizationId,
        installationId = installationId,
        grantType = SlackGrantType.USER,
        slackUserId = userGrant.slackUserId,
        tokenType = userGrant.tokenType,
        accessToken = userGrant.accessToken,
        refreshToken = userGrant.refreshToken,
        scopes = userGrant.grantedScopes,
        expiresInSeconds = userGrant.expiresInSeconds,
        health = request.scopeState.missingUserScopes.toGrantHealth(request.scopeState.health),
        healthDetail = request.scopeState.missingUserScopes.missingScopeDetail("user"),
    )

    private fun Set<String>.toGrantHealth(
        installationHealth: SlackInstallationHealthStatus,
    ): SlackInstallationHealthStatus = if (isEmpty()) SlackInstallationHealthStatus.HEALTHY else installationHealth

    private fun Set<String>.missingScopeDetail(principal: String): String? =
        takeIf(Set<String>::isNotEmpty)?.let {
            "Missing Slack $principal scopes: ${it.sorted().joinToString(", ")}"
        }

    private fun revokeMissingUserGrant(request: SlackOAuthPersistence, installationId: Int) {
        val privilegedAccessDisabled = request.scopeState.requestedUserScopes.isEmpty()
        SlackInstallationGrants.update({
            (SlackInstallationGrants.organizationId eq request.organizationId) and
                (SlackInstallationGrants.slackInstallationId eq installationId) and
                (SlackInstallationGrants.grantType eq SlackGrantType.USER.name)
        }) {
            it[revokedAt] = request.now
            it[healthStatus] = if (privilegedAccessDisabled) {
                SlackInstallationHealthStatus.DISABLED.name
            } else {
                SlackInstallationHealthStatus.REAUTHORIZATION_REQUIRED.name
            }
            it[healthDetail] = if (privilegedAccessDisabled) {
                "Privileged Slack access is disabled for this installation."
            } else {
                "Reauthorize a Slack admin or owner to grant privileged user scopes."
            }
            it[updatedAt] = request.now
        }
    }

    private fun persistOAuthWorkspaceBinding(request: SlackOAuthPersistence, installationId: Int) {
        val teamId = request.grant.teamId ?: return
        upsertWorkspaceBinding(
            request = WorkspaceBindingUpsert(
                organizationId = request.organizationId,
                installationId = installationId,
                teamId = teamId,
                teamName = request.grant.teamName,
                enterpriseId = request.grant.enterpriseId,
                isPrimary = true,
                health = request.scopeState.health,
                healthDetail = request.scopeState.detail,
            ),
            now = request.now,
        )
    }

    private fun clearLegacyToken(existing: ResultRow?, now: kotlin.time.Instant) {
        val legacyId = existing?.get(SlackInstallations.legacyIntegrationId) ?: return
        OrganizationIntegrations.update({ OrganizationIntegrations.id eq legacyId }) {
            it[access_token] = null
            it[updated_at] = now
        }
    }

    fun setDefault(organizationId: Int, installationId: String): SlackInstallationSummary = transaction {
        val installation = requireInstallation(organizationId, installationId)
        requirePrimaryWorkspaceBinding(organizationId, installation[SlackInstallations.id])
        SlackInstallations.update({ SlackInstallations.organizationId eq organizationId }) {
            it[isDefault] = false
            it[updatedAt] = Clock.System.now()
        }
        SlackInstallations.update({ SlackInstallations.id eq installation[SlackInstallations.id] }) {
            it[isDefault] = true
            it[updatedAt] = Clock.System.now()
        }
        val updated = requireInstallation(organizationId, installationId)
        syncLegacyDefault(updated)
        summary(updated)
    }

    fun updateChannel(
        organizationId: Int,
        installationId: String,
        channelId: String,
        channelName: String,
    ): SlackInstallationSummary = transaction {
        val installation = requireInstallation(organizationId, installationId)
        requirePrimaryWorkspaceBinding(organizationId, installation[SlackInstallations.id])
        require(channelId.isNotBlank() && channelId.length <= MAX_SLACK_CHANNEL_FIELD_LENGTH) {
            "Slack channel ID is invalid"
        }
        require(channelName.isNotBlank() && channelName.length <= MAX_SLACK_CHANNEL_FIELD_LENGTH) {
            "Slack channel name is invalid"
        }
        SlackInstallations.update({ SlackInstallations.id eq installation[SlackInstallations.id] }) {
            it[defaultChannelId] = channelId.trim()
            it[defaultChannelName] = channelName.trim()
            it[updatedAt] = Clock.System.now()
        }
        val updated = requireInstallation(organizationId, installationId)
        if (updated[SlackInstallations.isDefault]) syncLegacyDefault(updated)
        summary(updated)
    }

    fun setEnabled(
        organizationId: Int,
        installationId: String,
        enabled: Boolean,
    ): SlackInstallationSummary = transaction {
        val installation = requireInstallation(organizationId, installationId)
        SlackInstallations.update({ SlackInstallations.id eq installation[SlackInstallations.id] }) {
            it[SlackInstallations.enabled] = enabled
            if (!enabled) it[healthStatus] = SlackInstallationHealthStatus.DISABLED.name
            val wasDisabled = installation[SlackInstallations.healthStatus] ==
                SlackInstallationHealthStatus.DISABLED.name
            if (enabled && wasDisabled) {
                it[healthStatus] = SlackInstallationHealthStatus.REAUTHORIZATION_REQUIRED.name
            }
            it[updatedAt] = Clock.System.now()
        }
        val updated = requireInstallation(organizationId, installationId)
        if (updated[SlackInstallations.isDefault]) syncLegacyDefault(updated)
        summary(updated)
    }

    fun deleteInstallation(organizationId: Int, installationId: String): Boolean = transaction {
        val installation = requireInstallation(organizationId, installationId)
        val wasDefault = installation[SlackInstallations.isDefault]
        val deleted = SlackInstallations.deleteWhere {
            SlackInstallations.id eq installation[SlackInstallations.id]
        } > 0
        if (deleted && wasDefault) {
            SlackInstallations
                .selectAll()
                .where {
                    (SlackInstallations.organizationId eq organizationId) and
                        SlackInstallations.teamId.isNotNull()
                }
                .orderBy(SlackInstallations.createdAt to SortOrder.ASC)
                .limit(1)
                .singleOrNull()
                ?.let { replacement ->
                    SlackInstallations.update({ SlackInstallations.id eq replacement[SlackInstallations.id] }) {
                        it[isDefault] = true
                        it[updatedAt] = Clock.System.now()
                    }
                    syncLegacyDefault(requireInstallationById(organizationId, replacement[SlackInstallations.id]))
                }
        }
        deleted
    }

    suspend fun verifyInstallation(
        organizationId: Int,
        installationId: String,
        probe: suspend (String) -> SlackAuthenticationProbe,
    ): SlackInstallationSummary {
        val installation = installationWithToken(organizationId, installationId, allowRevoked = true)
        val result = probe(installation.accessToken)
        val statusAndDetail = healthFor(installation.row, installation.grant, result)
        return transaction {
            val now = Clock.System.now()
            SlackInstallations.update({ SlackInstallations.id eq installation.row[SlackInstallations.id] }) {
                it[healthStatus] = statusAndDetail.first.name
                it[healthDetail] = statusAndDetail.second
                it[lastVerifiedAt] = now
                it[updatedAt] = now
            }
            SlackInstallationGrants.update({
                SlackInstallationGrants.id eq installation.grant[SlackInstallationGrants.id]
            }) {
                it[healthStatus] = statusAndDetail.first.name
                it[healthDetail] = statusAndDetail.second
                it[lastVerifiedAt] = now
                if (statusAndDetail.first == SlackInstallationHealthStatus.TOKEN_REVOKED) {
                    it[revokedAt] = now
                }
                it[updatedAt] = now
            }
            SlackWorkspaceBindings.update({
                (SlackWorkspaceBindings.slackInstallationId eq installation.row[SlackInstallations.id]) and
                    (SlackWorkspaceBindings.isPrimary eq true)
            }) {
                it[healthStatus] = statusAndDetail.first.name
                it[healthDetail] = statusAndDetail.second
                it[lastVerifiedAt] = now
                it[updatedAt] = now
            }
            summary(requireInstallation(organizationId, installationId))
        }
    }

    fun accessToken(organizationId: Int, installationId: String): String =
        installationWithToken(organizationId, installationId).accessToken

    fun deliveryConfig(
        organizationId: Int,
        installationId: String,
    ): SlackDeliveryConfig? {
        val installation = installationWithToken(organizationId, installationId)
        if (!installation.row[SlackInstallations.enabled]) return null
        val channelId = installation.row[SlackInstallations.defaultChannelId] ?: return null
        return SlackDeliveryConfig(
            installationId = installationId,
            accessToken = installation.accessToken,
            teamId = installation.row[SlackInstallations.teamId],
            botUserId = installation.grant[SlackInstallationGrants.slackUserId],
            channelId = channelId,
        )
    }

    fun internalInstallationId(organizationId: Int, installationId: String): Int = transaction {
        requireInstallation(organizationId, installationId)[SlackInstallations.id]
    }

    fun defaultInternalInstallationId(organizationId: Int): Int? = transaction {
        SlackInstallations
            .selectAll()
            .where {
                (SlackInstallations.organizationId eq organizationId) and
                    (SlackInstallations.isDefault eq true)
            }
            .singleOrNull()
            ?.get(SlackInstallations.id)
    }

    fun internalInstallationIdForTeam(organizationId: Int, teamId: String): Int? = transaction {
        SlackWorkspaceBindings
            .selectAll()
            .where {
                (SlackWorkspaceBindings.organizationId eq organizationId) and
                    (SlackWorkspaceBindings.teamId eq teamId) and
                    (SlackWorkspaceBindings.enabled eq true)
            }
            .singleOrNull()
            ?.get(SlackWorkspaceBindings.slackInstallationId)
    }

    fun bindEnterpriseWorkspace(
        organizationId: Int,
        installationId: String,
        teamId: String,
        teamName: String?,
        enterpriseId: String,
    ): SlackInstallationSummary = transaction {
        val installation = requireInstallation(organizationId, installationId)
        require(installation[SlackInstallations.isEnterpriseInstall]) {
            "Only Enterprise Grid organization installations can add workspace bindings"
        }
        require(installation[SlackInstallations.enterpriseId] == enterpriseId) {
            "Slack workspace enterprise does not match this installation"
        }
        val now = Clock.System.now()
        upsertWorkspaceBinding(
            request = WorkspaceBindingUpsert(
                organizationId = organizationId,
                installationId = installation[SlackInstallations.id],
                teamId = teamId,
                teamName = teamName,
                enterpriseId = enterpriseId,
                isPrimary = false,
                health = SlackInstallationHealthStatus.REAUTHORIZATION_REQUIRED,
                healthDetail = "Verify Slack access after attaching this workspace.",
            ),
            now = now,
        )
        summary(requireInstallation(organizationId, installationId))
    }

    fun deliveryConfigByInternalId(
        organizationId: Int,
        installationId: Int?,
    ): SlackDeliveryConfig? {
        if (installationId == null) return defaultDeliveryConfig(organizationId)
        val publicId = transaction {
            requireInstallationById(organizationId, installationId)[SlackInstallations.resourceId].toString()
        }
        return deliveryConfig(organizationId, publicId)
    }

    fun defaultDeliveryConfig(organizationId: Int): SlackDeliveryConfig? {
        migrateLegacyInstallations(organizationId)
        val row = transaction {
            SlackInstallations
                .selectAll()
                .where {
                    (SlackInstallations.organizationId eq organizationId) and
                        (SlackInstallations.isDefault eq true) and
                        (SlackInstallations.enabled eq true)
                }
                .singleOrNull()
        } ?: return null
        val channelId = row[SlackInstallations.defaultChannelId] ?: return null
        val grant = transaction { requireActiveBotGrant(organizationId, row[SlackInstallations.id]) }
        val ciphertext = grant[SlackInstallationGrants.accessTokenCiphertext]
        return SlackDeliveryConfig(
            installationId = row[SlackInstallations.resourceId].toString(),
            accessToken = cipherFactory().decrypt(ciphertext, organizationId),
            teamId = row[SlackInstallations.teamId],
            botUserId = grant[SlackInstallationGrants.slackUserId],
            channelId = channelId,
        )
    }

    private fun migrateLegacyInstallations(organizationId: Int) {
        val candidates = transaction {
            val modeled = SlackInstallations
                .selectAll()
                .where { SlackInstallations.organizationId eq organizationId }
                .filter { installation ->
                    SlackInstallationGrants
                        .selectAll()
                        .where {
                            (SlackInstallationGrants.slackInstallationId eq installation[SlackInstallations.id]) and
                                (SlackInstallationGrants.grantType eq SlackGrantType.BOT.name)
                        }
                        .limit(1)
                        .empty()
                }
            if (modeled.isNotEmpty()) return@transaction modeled

            val legacy = OrganizationIntegrations
                .selectAll()
                .where {
                    (OrganizationIntegrations.organization_id eq organizationId) and
                        (OrganizationIntegrations.integration_type eq "slack") and
                        OrganizationIntegrations.access_token.isNotNull()
                }
                .singleOrNull() ?: return@transaction emptyList()
            val teamId = legacy[OrganizationIntegrations.team_id] ?: return@transaction emptyList()
            val now = Clock.System.now()
            val id = SlackInstallations.insert {
                it[SlackInstallations.organizationId] = organizationId
                it[legacyIntegrationId] = legacy[OrganizationIntegrations.id]
                it[SlackInstallations.teamId] = teamId
                it[teamName] = legacy[OrganizationIntegrations.team_name]
                it[defaultChannelId] = legacy[OrganizationIntegrations.channel_id]
                it[defaultChannelName] = legacy[OrganizationIntegrations.channel_name]
                it[isDefault] = true
                it[enabled] = legacy[OrganizationIntegrations.enabled]
                it[createdAt] = legacy[OrganizationIntegrations.created_at]
                it[updatedAt] = now
            } get SlackInstallations.id
            listOf(requireInstallationById(organizationId, id))
        }

        candidates.forEach { installation ->
            val legacyId = installation[SlackInstallations.legacyIntegrationId] ?: return@forEach
            val legacyGrant = transaction {
                OrganizationIntegrations
                    .selectAll()
                    .where { OrganizationIntegrations.id eq legacyId }
                    .singleOrNull()
                    ?.let { row ->
                        row[OrganizationIntegrations.access_token]?.let { token ->
                            token to row[OrganizationIntegrations.bot_user_id]
                        }
                    }
            } ?: return@forEach
            val cipher = cipherFactory()
            transaction {
                val now = Clock.System.now()
                upsertGrant(
                    request = GrantUpsert(
                        organizationId = organizationId,
                        installationId = installation[SlackInstallations.id],
                        grantType = SlackGrantType.BOT,
                        slackUserId = legacyGrant.second,
                        tokenType = "bot",
                        accessToken = legacyGrant.first,
                        refreshToken = null,
                        scopes = SlackCapability.requiredScopes(SlackCapability.defaults),
                        expiresInSeconds = null,
                        health = SlackInstallationHealthStatus.REAUTHORIZATION_REQUIRED,
                        healthDetail = "Reauthorize Slack to verify the installation and granted scopes.",
                    ),
                    cipher = cipher,
                    now = now,
                )
                installation[SlackInstallations.teamId]?.let { teamId ->
                    upsertWorkspaceBinding(
                        request = WorkspaceBindingUpsert(
                            organizationId = organizationId,
                            installationId = installation[SlackInstallations.id],
                            teamId = teamId,
                            teamName = installation[SlackInstallations.teamName],
                            enterpriseId = installation[SlackInstallations.enterpriseId],
                            isPrimary = true,
                            health = SlackInstallationHealthStatus.REAUTHORIZATION_REQUIRED,
                            healthDetail = "Reauthorize Slack to verify the workspace binding.",
                        ),
                        now = now,
                    )
                }
                SlackInstallations.update({ SlackInstallations.id eq installation[SlackInstallations.id] }) {
                    it[enabledCapabilities] = SlackCapability.defaults.map(SlackCapability::id).toCsv()
                    it[healthStatus] = SlackInstallationHealthStatus.REAUTHORIZATION_REQUIRED.name
                    it[healthDetail] = "Reauthorize Slack to verify the installation and granted scopes."
                    it[updatedAt] = now
                }
                OrganizationIntegrations.update({ OrganizationIntegrations.id eq legacyId }) {
                    it[access_token] = null
                    it[updated_at] = now
                }
            }
        }
    }

    private fun installationWithToken(
        organizationId: Int,
        installationId: String,
        allowRevoked: Boolean = false,
    ): InstallationWithToken {
        migrateLegacyInstallations(organizationId)
        val (row, grant) = transaction {
            val installation = requireInstallation(organizationId, installationId)
            val botGrant = if (allowRevoked) {
                requireBotGrant(organizationId, installation[SlackInstallations.id])
            } else {
                requireActiveBotGrant(organizationId, installation[SlackInstallations.id])
            }
            installation to botGrant
        }
        val ciphertext = grant[SlackInstallationGrants.accessTokenCiphertext]
        return InstallationWithToken(row, grant, cipherFactory().decrypt(ciphertext, organizationId))
    }

    private fun healthFor(
        installation: ResultRow,
        grant: ResultRow,
        probe: SlackAuthenticationProbe,
    ): Pair<SlackInstallationHealthStatus, String?> {
        if (!probe.ok) {
            return when (probe.error) {
                "token_revoked", "invalid_auth", "not_authed", "token_expired" ->
                    SlackInstallationHealthStatus.TOKEN_REVOKED to "Slack revoked or expired this installation token."
                "account_inactive", "team_access_not_granted" ->
                    SlackInstallationHealthStatus.BOT_REMOVED to "The Moneat bot is no longer active in this workspace."
                else -> SlackInstallationHealthStatus.DEGRADED to "Slack authentication could not be verified."
            }
        }
        if (probe.teamId != installation[SlackInstallations.teamId] ||
            probe.enterpriseId != installation[SlackInstallations.enterpriseId]
        ) {
            return SlackInstallationHealthStatus.WORKSPACE_MISMATCH to
                "Slack authenticated a different workspace or enterprise than this installation."
        }
        val storedBot = grant[SlackInstallationGrants.slackUserId]
        if (storedBot != null && probe.botUserId != null && storedBot != probe.botUserId) {
            return SlackInstallationHealthStatus.BOT_REMOVED to "The installed Slack bot identity changed."
        }
        val capabilities = SlackCapability.fromIds(installation[SlackInstallations.enabledCapabilities].fromCsv())
        val grantedUserScopes = transaction {
            SlackInstallationGrants
                .selectAll()
                .where {
                    (SlackInstallationGrants.organizationId eq installation[SlackInstallations.organizationId]) and
                        (SlackInstallationGrants.slackInstallationId eq installation[SlackInstallations.id]) and
                        (SlackInstallationGrants.grantType eq SlackGrantType.USER.name) and
                        SlackInstallationGrants.revokedAt.isNull()
                }
                .flatMap { it[SlackInstallationGrants.grantedScopes].fromCsv() }
                .toSet()
        }
        val missingScopes = SlackCapability.requiredScopes(capabilities) -
            grant[SlackInstallationGrants.grantedScopes].fromCsv().toSet() +
            (SlackCapability.requiredUserScopes(capabilities) - grantedUserScopes)
        if (missingScopes.isNotEmpty()) {
            return SlackInstallationHealthStatus.MISSING_SCOPES to
                "Missing Slack scopes: ${missingScopes.sorted().joinToString(", ")}"
        }
        return SlackInstallationHealthStatus.HEALTHY to null
    }

    private fun upsertGrant(
        request: GrantUpsert,
        cipher: SlackTokenCipher,
        now: kotlin.time.Instant,
    ) {
        val organizationId = request.organizationId
        val installationId = request.installationId
        val grantType = request.grantType
        val slackUserId = request.slackUserId
        val accessToken = request.accessToken
        require(accessToken.isNotBlank()) { "Slack returned an empty access token" }
        require(grantType != SlackGrantType.USER || !slackUserId.isNullOrBlank()) {
            "Slack user grants require a user identity"
        }
        val existing = SlackInstallationGrants
            .selectAll()
            .where {
                (SlackInstallationGrants.organizationId eq organizationId) and
                    (SlackInstallationGrants.slackInstallationId eq installationId) and
                    (SlackInstallationGrants.grantType eq grantType.name)
            }
            .singleOrNull()
        val accessCiphertext = cipher.encrypt(accessToken, organizationId)
        val refreshCiphertext = request.refreshToken?.let { cipher.encrypt(it, organizationId) }
        val expiresAt = request.expiresInSeconds?.let { now + it.seconds }

        if (existing == null) {
            SlackInstallationGrants.insert {
                it[SlackInstallationGrants.organizationId] = organizationId
                it[slackInstallationId] = installationId
                it[SlackInstallationGrants.grantType] = grantType.name
                it[SlackInstallationGrants.slackUserId] = slackUserId
                it[SlackInstallationGrants.tokenType] = request.tokenType
                it[accessTokenCiphertext] = accessCiphertext
                it[accessTokenKeyId] = cipher.activeKeyId
                it[refreshTokenCiphertext] = refreshCiphertext
                it[refreshTokenKeyId] = refreshCiphertext?.let { cipher.activeKeyId }
                it[grantedScopes] = request.scopes.toCsv()
                it[SlackInstallationGrants.expiresAt] = expiresAt
                it[healthStatus] = request.health.name
                it[SlackInstallationGrants.healthDetail] = request.healthDetail
                it[lastVerifiedAt] = now
                it[createdAt] = now
                it[updatedAt] = now
            }
            return
        }

        SlackInstallationGrants.update({ SlackInstallationGrants.id eq existing[SlackInstallationGrants.id] }) {
            it[SlackInstallationGrants.slackUserId] = slackUserId
            it[SlackInstallationGrants.tokenType] = request.tokenType
            it[accessTokenCiphertext] = accessCiphertext
            it[accessTokenKeyId] = cipher.activeKeyId
            it[refreshTokenCiphertext] = refreshCiphertext
            it[refreshTokenKeyId] = refreshCiphertext?.let { cipher.activeKeyId }
            it[grantedScopes] = request.scopes.toCsv()
            it[SlackInstallationGrants.expiresAt] = expiresAt
            it[rotatedAt] = now
            it[revokedAt] = null
            it[healthStatus] = request.health.name
            it[SlackInstallationGrants.healthDetail] = request.healthDetail
            it[lastVerifiedAt] = now
            it[updatedAt] = now
        }
    }

    private fun upsertWorkspaceBinding(
        request: WorkspaceBindingUpsert,
        now: kotlin.time.Instant,
    ) {
        val organizationId = request.organizationId
        val installationId = request.installationId
        val teamId = request.teamId
        val existing = SlackWorkspaceBindings
            .selectAll()
            .where {
                (SlackWorkspaceBindings.organizationId eq organizationId) and
                    (SlackWorkspaceBindings.teamId eq teamId)
            }
            .singleOrNull()
        require(existing == null || existing[SlackWorkspaceBindings.slackInstallationId] == installationId) {
            "Slack workspace is already bound to another installation"
        }
        if (existing == null) {
            SlackWorkspaceBindings.insert {
                it[SlackWorkspaceBindings.organizationId] = organizationId
                it[slackInstallationId] = installationId
                it[SlackWorkspaceBindings.teamId] = teamId
                it[SlackWorkspaceBindings.teamName] = request.teamName
                it[SlackWorkspaceBindings.enterpriseId] = request.enterpriseId
                it[isPrimary] = request.isPrimary
                it[enabled] = true
                it[healthStatus] = request.health.name
                it[SlackWorkspaceBindings.healthDetail] = request.healthDetail
                it[lastVerifiedAt] = now
                it[createdAt] = now
                it[updatedAt] = now
            }
            return
        }
        SlackWorkspaceBindings.update({ SlackWorkspaceBindings.id eq existing[SlackWorkspaceBindings.id] }) {
            it[SlackWorkspaceBindings.teamName] = request.teamName
            it[SlackWorkspaceBindings.enterpriseId] = request.enterpriseId
            it[isPrimary] = request.isPrimary
            it[enabled] = true
            it[healthStatus] = request.health.name
            it[SlackWorkspaceBindings.healthDetail] = request.healthDetail
            it[lastVerifiedAt] = now
            it[updatedAt] = now
        }
    }

    private fun requireActiveBotGrant(organizationId: Int, installationId: Int): ResultRow =
        requireBotGrant(organizationId, installationId).also { grant ->
            check(grant[SlackInstallationGrants.revokedAt] == null) {
                "Slack installation must be reauthorized"
            }
            val expiresAt = grant[SlackInstallationGrants.expiresAt]
            check(expiresAt == null || expiresAt > Clock.System.now()) {
                "Slack installation token expired; reauthorize the installation"
            }
        }

    private fun requireBotGrant(organizationId: Int, installationId: Int): ResultRow =
        SlackInstallationGrants
            .selectAll()
            .where {
                (SlackInstallationGrants.organizationId eq organizationId) and
                    (SlackInstallationGrants.slackInstallationId eq installationId) and
                    (SlackInstallationGrants.grantType eq SlackGrantType.BOT.name)
            }
            .singleOrNull()
            ?: throw IllegalStateException("Slack installation must be reauthorized")

    private fun requirePrimaryWorkspaceBinding(organizationId: Int, installationId: Int): ResultRow =
        SlackWorkspaceBindings
            .selectAll()
            .where {
                (SlackWorkspaceBindings.organizationId eq organizationId) and
                    (SlackWorkspaceBindings.slackInstallationId eq installationId) and
                    (SlackWorkspaceBindings.isPrimary eq true) and
                    (SlackWorkspaceBindings.enabled eq true)
            }
            .singleOrNull()
            ?: throw IllegalArgumentException("Slack installation is not attached to a workspace")

    private fun syncLegacyDefault(installation: ResultRow) {
        val botGrant = SlackInstallationGrants
            .selectAll()
            .where {
                (SlackInstallationGrants.organizationId eq installation[SlackInstallations.organizationId]) and
                    (SlackInstallationGrants.slackInstallationId eq installation[SlackInstallations.id]) and
                    (SlackInstallationGrants.grantType eq SlackGrantType.BOT.name)
            }
            .singleOrNull()
        OrganizationIntegrations.update({
            (OrganizationIntegrations.organization_id eq installation[SlackInstallations.organizationId]) and
                (OrganizationIntegrations.integration_type eq "slack")
        }) {
            it[access_token] = null
            it[bot_user_id] = botGrant?.get(SlackInstallationGrants.slackUserId)
            it[team_id] = installation[SlackInstallations.teamId]
            it[team_name] = installation[SlackInstallations.teamName]
            it[channel_id] = installation[SlackInstallations.defaultChannelId]
            it[channel_name] = installation[SlackInstallations.defaultChannelName]
            it[enabled] = installation[SlackInstallations.enabled]
            it[updated_at] = Clock.System.now()
        }
    }

    private fun sameSlackIdentity(row: ResultRow, grant: SlackOAuthGrant): Boolean =
        row[SlackInstallations.teamId] == grant.teamId &&
            row[SlackInstallations.enterpriseId] == grant.enterpriseId &&
            row[SlackInstallations.isEnterpriseInstall] == grant.isEnterpriseInstall

    private fun findBySlackIdentity(
        organizationId: Int,
        teamId: String?,
        enterpriseId: String?,
        enterpriseInstall: Boolean,
    ): ResultRow? = SlackInstallations
        .selectAll()
        .where { SlackInstallations.organizationId eq organizationId }
        .firstOrNull { row ->
            if (enterpriseInstall) {
                row[SlackInstallations.isEnterpriseInstall] && row[SlackInstallations.enterpriseId] == enterpriseId
            } else {
                row[SlackInstallations.teamId] == teamId
            }
        }

    private fun requireInstallation(organizationId: Int, installationId: String): ResultRow {
        val resourceId = installationId.toUuidOrNull()
            ?: throw IllegalArgumentException("Slack installation ID must be a UUID")
        return SlackInstallations
            .selectAll()
            .where {
                (SlackInstallations.organizationId eq organizationId) and
                    (SlackInstallations.resourceId eq resourceId)
            }
            .singleOrNull()
            ?: throw NoSuchElementException("Slack installation not found")
    }

    private fun requireInstallationById(organizationId: Int, installationId: Int): ResultRow =
        SlackInstallations
            .selectAll()
            .where {
                (SlackInstallations.organizationId eq organizationId) and
                    (SlackInstallations.id eq installationId)
            }
            .single()

    private fun summary(row: ResultRow): SlackInstallationSummary {
        val capabilityIds = row[SlackInstallations.enabledCapabilities].fromCsv()
        val capabilities = SlackCapability.fromIds(capabilityIds)
        val grantRows = SlackInstallationGrants
            .selectAll()
            .where {
                (SlackInstallationGrants.organizationId eq row[SlackInstallations.organizationId]) and
                    (SlackInstallationGrants.slackInstallationId eq row[SlackInstallations.id])
            }
            .orderBy(SlackInstallationGrants.createdAt to SortOrder.ASC)
            .toList()
        val botGrant = grantRows.firstOrNull { it[SlackInstallationGrants.grantType] == SlackGrantType.BOT.name }
        val userGrants = grantRows.filter {
            it[SlackInstallationGrants.grantType] == SlackGrantType.USER.name &&
                it[SlackInstallationGrants.revokedAt] == null
        }
        val grantedScopes = botGrant?.get(SlackInstallationGrants.grantedScopes)?.fromCsv().orEmpty()
        val grantedUserScopes = userGrants
            .flatMap { it[SlackInstallationGrants.grantedScopes].fromCsv() }
            .distinct()
            .sorted()
        val capabilityHealth = capabilities.map { capability ->
            val missingBotScopes = capability.botScopes - grantedScopes.toSet()
            val missingUserScopes = capability.userScopes - grantedUserScopes.toSet()
            SlackCapabilityHealth(
                capabilityId = capability.id,
                missingBotScopes = missingBotScopes.sorted(),
                missingUserScopes = missingUserScopes.sorted(),
                healthy = missingBotScopes.isEmpty() && missingUserScopes.isEmpty(),
            )
        }
        val missingScopes = capabilityHealth.flatMap { it.missingBotScopes + it.missingUserScopes }.distinct().sorted()
        val bindings = SlackWorkspaceBindings
            .selectAll()
            .where {
                (SlackWorkspaceBindings.organizationId eq row[SlackInstallations.organizationId]) and
                    (SlackWorkspaceBindings.slackInstallationId eq row[SlackInstallations.id])
            }
            .orderBy(
                SlackWorkspaceBindings.isPrimary to SortOrder.DESC,
                SlackWorkspaceBindings.createdAt to SortOrder.ASC,
            )
            .map(::workspaceBindingSummary)
        return SlackInstallationSummary(
            id = row[SlackInstallations.resourceId].toString(),
            teamId = row[SlackInstallations.teamId],
            teamName = row[SlackInstallations.teamName],
            enterpriseId = row[SlackInstallations.enterpriseId],
            enterpriseName = row[SlackInstallations.enterpriseName],
            isEnterpriseInstall = row[SlackInstallations.isEnterpriseInstall],
            appId = row[SlackInstallations.appId],
            botUserId = botGrant?.get(SlackInstallationGrants.slackUserId),
            grantedScopes = grantedScopes,
            grantedUserScopes = grantedUserScopes,
            enabledCapabilities = capabilities.map(SlackCapability::id).sorted(),
            missingScopes = missingScopes,
            workspaceBindings = bindings,
            grants = grantRows.map(::grantSummary),
            capabilityHealth = capabilityHealth,
            defaultChannelId = row[SlackInstallations.defaultChannelId],
            defaultChannelName = row[SlackInstallations.defaultChannelName],
            isDefault = row[SlackInstallations.isDefault],
            enabled = row[SlackInstallations.enabled],
            health = SlackInstallationHealthStatus.valueOf(row[SlackInstallations.healthStatus]),
            healthDetail = row[SlackInstallations.healthDetail],
            lastVerifiedAt = row[SlackInstallations.lastVerifiedAt]?.toString(),
            createdAt = row[SlackInstallations.createdAt].toString(),
            updatedAt = row[SlackInstallations.updatedAt].toString(),
        )
    }

    private fun workspaceBindingSummary(row: ResultRow): SlackWorkspaceBindingSummary = SlackWorkspaceBindingSummary(
        id = row[SlackWorkspaceBindings.resourceId].toString(),
        teamId = row[SlackWorkspaceBindings.teamId],
        teamName = row[SlackWorkspaceBindings.teamName],
        enterpriseId = row[SlackWorkspaceBindings.enterpriseId],
        isPrimary = row[SlackWorkspaceBindings.isPrimary],
        enabled = row[SlackWorkspaceBindings.enabled],
        health = SlackInstallationHealthStatus.valueOf(row[SlackWorkspaceBindings.healthStatus]),
        healthDetail = row[SlackWorkspaceBindings.healthDetail],
        lastVerifiedAt = row[SlackWorkspaceBindings.lastVerifiedAt]?.toString(),
    )

    private fun grantSummary(row: ResultRow): SlackGrantSummary = SlackGrantSummary(
        id = row[SlackInstallationGrants.resourceId].toString(),
        grantType = SlackGrantType.valueOf(row[SlackInstallationGrants.grantType]),
        slackUserId = row[SlackInstallationGrants.slackUserId],
        tokenType = row[SlackInstallationGrants.tokenType],
        grantedScopes = row[SlackInstallationGrants.grantedScopes].fromCsv(),
        expiresAt = row[SlackInstallationGrants.expiresAt]?.toString(),
        rotatedAt = row[SlackInstallationGrants.rotatedAt]?.toString(),
        revokedAt = row[SlackInstallationGrants.revokedAt]?.toString(),
        health = SlackInstallationHealthStatus.valueOf(row[SlackInstallationGrants.healthStatus]),
        healthDetail = row[SlackInstallationGrants.healthDetail],
        lastVerifiedAt = row[SlackInstallationGrants.lastVerifiedAt]?.toString(),
    )

    private data class SlackOAuthScopeState(
        val capabilities: Set<SlackCapability>,
        val requestedUserScopes: Set<String>,
        val missingBotScopes: Set<String>,
        val missingUserScopes: Set<String>,
        val health: SlackInstallationHealthStatus,
        val detail: String?,
    )

    private data class SlackOAuthPersistence(
        val organizationId: Int,
        val existing: ResultRow?,
        val grant: SlackOAuthGrant,
        val scopeState: SlackOAuthScopeState,
        val now: kotlin.time.Instant,
    )

    private data class GrantUpsert(
        val organizationId: Int,
        val installationId: Int,
        val grantType: SlackGrantType,
        val slackUserId: String?,
        val tokenType: String?,
        val accessToken: String,
        val refreshToken: String?,
        val scopes: Set<String>,
        val expiresInSeconds: Long?,
        val health: SlackInstallationHealthStatus,
        val healthDetail: String?,
    )

    private data class WorkspaceBindingUpsert(
        val organizationId: Int,
        val installationId: Int,
        val teamId: String,
        val teamName: String?,
        val enterpriseId: String?,
        val isPrimary: Boolean,
        val health: SlackInstallationHealthStatus,
        val healthDetail: String?,
    )

    private data class InstallationWithToken(
        val row: ResultRow,
        val grant: ResultRow,
        val accessToken: String,
    )

    companion object {
        private const val MAX_SLACK_CHANNEL_FIELD_LENGTH = 255

        private fun Collection<String>.toCsv(): String = map(String::trim).filter(String::isNotEmpty).distinct()
            .sorted().joinToString(",")

        private fun String.fromCsv(): List<String> = split(',').map(String::trim).filter(String::isNotEmpty).distinct()
    }
}
