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
import com.moneat.shared.models.SlackInstallations
import com.moneat.shared.services.toUuidOrNull
import kotlinx.serialization.Serializable
import org.jetbrains.exposed.v1.core.ResultRow
import org.jetbrains.exposed.v1.core.SortOrder
import org.jetbrains.exposed.v1.core.and
import org.jetbrains.exposed.v1.core.eq
import org.jetbrains.exposed.v1.core.isNotNull
import org.jetbrains.exposed.v1.jdbc.deleteWhere
import org.jetbrains.exposed.v1.jdbc.insert
import org.jetbrains.exposed.v1.jdbc.selectAll
import org.jetbrains.exposed.v1.jdbc.transactions.transaction
import org.jetbrains.exposed.v1.jdbc.update
import kotlin.time.Clock

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
    val scopes: Set<String>,
) {
    ALERT_DELIVERY(
        id = "alert_delivery",
        label = "Alert delivery",
        description = "Send alert and incident updates to selected Slack conversations.",
        scopes = setOf("chat:write", "channels:read", "channels:join", "groups:read"),
    ),
    INCIDENT_COMMANDS(
        id = "incident_commands",
        label = "Incident commands and mentions",
        description = "Handle slash commands, shortcuts, and app mentions during response.",
        scopes = setOf("commands", "app_mentions:read", "chat:write"),
    ),
    INCIDENT_CHANNELS(
        id = "incident_channels",
        label = "Incident channels",
        description = "Create, join, and manage public or private incident channels.",
        scopes = setOf(
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
        scopes = setOf("bookmarks:write", "pins:write", "reactions:write", "files:write"),
    ),
    INCIDENT_HISTORY(
        id = "incident_history",
        label = "Incident history",
        description = "Read incident-channel and direct-message history for timelines and summaries.",
        scopes = setOf("channels:history", "groups:history", "im:history", "mpim:history"),
    ),
    IDENTITY(
        id = "identity",
        label = "Slack identity matching",
        description = "Match Slack users to Moneat responders and organization members.",
        scopes = setOf("users:read", "users:read.email"),
    ),
    ON_CALL_USERGROUPS(
        id = "on_call_usergroups",
        label = "On-call user groups",
        description = "Read and update Slack user groups that mirror on-call schedules.",
        scopes = setOf("usergroups:read", "usergroups:write", "users:read"),
    ),
    ASSISTANT(
        id = "assistant",
        label = "Slack Assistant",
        description = "Offer the optional AI assistant in Slack direct messages.",
        scopes = setOf("assistant:write", "im:history", "im:write", "chat:write"),
    );

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
            capabilities.flatMapTo(sortedSetOf(), SlackCapability::scopes)
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
    val optional: Boolean,
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
    val enabledCapabilities: List<String>,
    val missingScopes: List<String>,
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
                optional = capability == SlackCapability.ASSISTANT,
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
        require(grant.teamId != null || grant.enterpriseId != null) {
            "Slack OAuth response did not identify a workspace or enterprise"
        }
        require(!grant.isEnterpriseInstall || grant.enterpriseId != null) {
            "Enterprise Slack installations require enterprise context"
        }
        val capabilities = SlackCapability.fromIds(capabilityIds)
        val requestedScopes = SlackCapability.requiredScopes(capabilities)
        require(grant.grantedScopes.all(String::isNotBlank)) { "Slack returned an invalid scope grant" }
        val cipher = cipherFactory()
        val ciphertext = cipher.encrypt(grant.accessToken, organizationId)
        val now = Clock.System.now()

        return transaction {
            val existing = reauthorizeInstallationId?.let { requireInstallation(organizationId, it) }
                ?: findBySlackIdentity(organizationId, grant.teamId, grant.enterpriseId, grant.isEnterpriseInstall)
            if (existing != null && !sameSlackIdentity(existing, grant)) {
                SlackInstallations.update({ SlackInstallations.id eq existing[SlackInstallations.id] }) {
                    it[healthStatus] = SlackInstallationHealthStatus.WORKSPACE_MISMATCH.name
                    it[healthDetail] = "Slack returned a different workspace or enterprise during reauthorization."
                    it[updatedAt] = now
                }
                return@transaction summary(requireInstallationById(organizationId, existing[SlackInstallations.id]))
            }

            val isFirst = SlackInstallations
                .selectAll()
                .where { SlackInstallations.organizationId eq organizationId }
                .limit(1)
                .empty()
            val missingScopes = requestedScopes - grant.grantedScopes
            val health = if (missingScopes.isEmpty()) {
                SlackInstallationHealthStatus.HEALTHY
            } else {
                SlackInstallationHealthStatus.MISSING_SCOPES
            }
            val detail = missingScopes.takeIf(Set<String>::isNotEmpty)?.let {
                "Missing Slack scopes: ${it.sorted().joinToString(", ")}"
            }

            val installationId = if (existing == null) {
                SlackInstallations.insert {
                    it[SlackInstallations.organizationId] = organizationId
                    it[teamId] = grant.teamId
                    it[teamName] = grant.teamName
                    it[enterpriseId] = grant.enterpriseId
                    it[enterpriseName] = grant.enterpriseName
                    it[isEnterpriseInstall] = grant.isEnterpriseInstall
                    it[appId] = grant.appId
                    it[botUserId] = grant.botUserId
                    it[accessTokenCiphertext] = ciphertext
                    it[accessTokenKeyId] = cipher.activeKeyId
                    it[grantedScopes] = grant.grantedScopes.toCsv()
                    it[enabledCapabilities] = capabilities.map(SlackCapability::id).toCsv()
                    it[isDefault] = isFirst
                    it[enabled] = true
                    it[healthStatus] = health.name
                    it[healthDetail] = detail
                    it[lastVerifiedAt] = now
                    it[createdAt] = now
                    it[updatedAt] = now
                } get SlackInstallations.id
            } else {
                existing[SlackInstallations.id].also { id ->
                    SlackInstallations.update({ SlackInstallations.id eq id }) {
                        it[teamName] = grant.teamName
                        it[enterpriseId] = grant.enterpriseId
                        it[enterpriseName] = grant.enterpriseName
                        it[isEnterpriseInstall] = grant.isEnterpriseInstall
                        it[appId] = grant.appId
                        it[botUserId] = grant.botUserId
                        it[accessTokenCiphertext] = ciphertext
                        it[accessTokenKeyId] = cipher.activeKeyId
                        it[grantedScopes] = grant.grantedScopes.toCsv()
                        it[enabledCapabilities] = capabilities.map(SlackCapability::id).toCsv()
                        it[enabled] = true
                        it[healthStatus] = health.name
                        it[healthDetail] = detail
                        it[lastVerifiedAt] = now
                        it[updatedAt] = now
                    }
                    existing[SlackInstallations.legacyIntegrationId]?.let { legacyId ->
                        OrganizationIntegrations.update({ OrganizationIntegrations.id eq legacyId }) {
                            it[access_token] = null
                            it[updated_at] = now
                        }
                    }
                }
            }
            summary(requireInstallationById(organizationId, installationId))
        }
    }

    fun setDefault(organizationId: Int, installationId: String): SlackInstallationSummary = transaction {
        val installation = requireInstallation(organizationId, installationId)
        SlackInstallations.update({ SlackInstallations.organizationId eq organizationId }) {
            it[isDefault] = false
            it[updatedAt] = Clock.System.now()
        }
        SlackInstallations.update({ SlackInstallations.id eq installation[SlackInstallations.id] }) {
            it[isDefault] = true
            it[updatedAt] = Clock.System.now()
        }
        summary(requireInstallation(organizationId, installationId))
    }

    fun updateChannel(
        organizationId: Int,
        installationId: String,
        channelId: String,
        channelName: String,
    ): SlackInstallationSummary = transaction {
        val installation = requireInstallation(organizationId, installationId)
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
        summary(requireInstallation(organizationId, installationId))
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
        summary(requireInstallation(organizationId, installationId))
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
                .where { SlackInstallations.organizationId eq organizationId }
                .orderBy(SlackInstallations.createdAt to SortOrder.ASC)
                .limit(1)
                .singleOrNull()
                ?.let { replacement ->
                    SlackInstallations.update({ SlackInstallations.id eq replacement[SlackInstallations.id] }) {
                        it[isDefault] = true
                        it[updatedAt] = Clock.System.now()
                    }
                }
        }
        deleted
    }

    suspend fun verifyInstallation(
        organizationId: Int,
        installationId: String,
        probe: suspend (String) -> SlackAuthenticationProbe,
    ): SlackInstallationSummary {
        val installation = installationWithToken(organizationId, installationId)
        val result = probe(installation.accessToken)
        val statusAndDetail = healthFor(installation.row, result)
        return transaction {
            SlackInstallations.update({ SlackInstallations.id eq installation.row[SlackInstallations.id] }) {
                it[healthStatus] = statusAndDetail.first.name
                it[healthDetail] = statusAndDetail.second
                it[lastVerifiedAt] = Clock.System.now()
                it[updatedAt] = Clock.System.now()
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
            botUserId = installation.row[SlackInstallations.botUserId],
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
        SlackInstallations
            .selectAll()
            .where {
                (SlackInstallations.organizationId eq organizationId) and
                    (SlackInstallations.teamId eq teamId)
            }
            .singleOrNull()
            ?.get(SlackInstallations.id)
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
                        (SlackInstallations.enabled eq true) and
                        SlackInstallations.accessTokenCiphertext.isNotNull()
                }
                .singleOrNull()
        } ?: return null
        val channelId = row[SlackInstallations.defaultChannelId] ?: return null
        val ciphertext = requireNotNull(row[SlackInstallations.accessTokenCiphertext])
        return SlackDeliveryConfig(
            installationId = row[SlackInstallations.resourceId].toString(),
            accessToken = cipherFactory().decrypt(ciphertext, organizationId),
            teamId = row[SlackInstallations.teamId],
            botUserId = row[SlackInstallations.botUserId],
            channelId = channelId,
        )
    }

    private fun migrateLegacyInstallations(organizationId: Int) {
        val candidates = transaction {
            val modeled = SlackInstallations
                .selectAll()
                .where {
                    (SlackInstallations.organizationId eq organizationId) and
                        (SlackInstallations.accessTokenCiphertext eq null)
                }
                .toList()
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
                it[botUserId] = legacy[OrganizationIntegrations.bot_user_id]
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
            val legacyToken = transaction {
                OrganizationIntegrations
                    .selectAll()
                    .where { OrganizationIntegrations.id eq legacyId }
                    .singleOrNull()
                    ?.get(OrganizationIntegrations.access_token)
            } ?: return@forEach
            val cipher = cipherFactory()
            val ciphertext = cipher.encrypt(legacyToken, organizationId)
            transaction {
                SlackInstallations.update({ SlackInstallations.id eq installation[SlackInstallations.id] }) {
                    it[accessTokenCiphertext] = ciphertext
                    it[accessTokenKeyId] = cipher.activeKeyId
                    it[grantedScopes] = SlackCapability.requiredScopes(SlackCapability.defaults).toCsv()
                    it[enabledCapabilities] = SlackCapability.defaults.map(SlackCapability::id).toCsv()
                    it[healthStatus] = SlackInstallationHealthStatus.REAUTHORIZATION_REQUIRED.name
                    it[healthDetail] = "Reauthorize Slack to verify the installation and granted scopes."
                    it[updatedAt] = Clock.System.now()
                }
                OrganizationIntegrations.update({ OrganizationIntegrations.id eq legacyId }) {
                    it[access_token] = null
                    it[updated_at] = Clock.System.now()
                }
            }
        }
    }

    private fun installationWithToken(organizationId: Int, installationId: String): InstallationWithToken {
        migrateLegacyInstallations(organizationId)
        val row = transaction { requireInstallation(organizationId, installationId) }
        val ciphertext = requireNotNull(row[SlackInstallations.accessTokenCiphertext]) {
            "Slack installation must be reauthorized"
        }
        return InstallationWithToken(row, cipherFactory().decrypt(ciphertext, organizationId))
    }

    private fun healthFor(
        installation: ResultRow,
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
        val storedBot = installation[SlackInstallations.botUserId]
        if (storedBot != null && probe.botUserId != null && storedBot != probe.botUserId) {
            return SlackInstallationHealthStatus.BOT_REMOVED to "The installed Slack bot identity changed."
        }
        val capabilities = SlackCapability.fromIds(installation[SlackInstallations.enabledCapabilities].fromCsv())
        val missingScopes = SlackCapability.requiredScopes(capabilities) -
            installation[SlackInstallations.grantedScopes].fromCsv().toSet()
        if (missingScopes.isNotEmpty()) {
            return SlackInstallationHealthStatus.MISSING_SCOPES to
                "Missing Slack scopes: ${missingScopes.sorted().joinToString(", ")}"
        }
        return SlackInstallationHealthStatus.HEALTHY to null
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
        val grantedScopes = row[SlackInstallations.grantedScopes].fromCsv()
        val missingScopes = SlackCapability.requiredScopes(capabilities) - grantedScopes.toSet()
        return SlackInstallationSummary(
            id = row[SlackInstallations.resourceId].toString(),
            teamId = row[SlackInstallations.teamId],
            teamName = row[SlackInstallations.teamName],
            enterpriseId = row[SlackInstallations.enterpriseId],
            enterpriseName = row[SlackInstallations.enterpriseName],
            isEnterpriseInstall = row[SlackInstallations.isEnterpriseInstall],
            appId = row[SlackInstallations.appId],
            botUserId = row[SlackInstallations.botUserId],
            grantedScopes = grantedScopes,
            enabledCapabilities = capabilities.map(SlackCapability::id).sorted(),
            missingScopes = missingScopes.sorted(),
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

    private data class InstallationWithToken(
        val row: ResultRow,
        val accessToken: String,
    )

    companion object {
        private const val MAX_SLACK_CHANNEL_FIELD_LENGTH = 255

        private fun Collection<String>.toCsv(): String = map(String::trim).filter(String::isNotEmpty).distinct()
            .sorted().joinToString(",")

        private fun String.fromCsv(): List<String> = split(',').map(String::trim).filter(String::isNotEmpty).distinct()
    }
}
