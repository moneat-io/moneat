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

import com.moneat.shared.models.OrganizationIntegrations
import com.moneat.shared.models.Organizations
import com.moneat.shared.models.OnCallSchedules
import com.moneat.shared.models.OnCallScheduleUsergroups
import com.moneat.shared.models.SlackInstallationGrants
import com.moneat.shared.models.SlackInstallations
import com.moneat.shared.models.SlackWorkspaceBindings
import com.moneat.testsupport.TestDatabaseHelper
import kotlinx.coroutines.runBlocking
import org.jetbrains.exposed.v1.jdbc.Database
import org.jetbrains.exposed.v1.core.eq
import org.jetbrains.exposed.v1.jdbc.insert
import org.jetbrains.exposed.v1.jdbc.selectAll
import org.jetbrains.exposed.v1.jdbc.transactions.TransactionManager
import org.jetbrains.exposed.v1.jdbc.transactions.transaction
import org.junit.jupiter.api.BeforeEach
import org.junit.jupiter.api.Test
import java.util.Base64
import java.time.LocalTime
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertFailsWith
import kotlin.test.assertNotEquals
import kotlin.test.assertNull
import kotlin.test.assertTrue
import kotlin.time.Clock

class SlackInstallationServiceTest {
    private lateinit var service: SlackInstallationService

    @BeforeEach
    fun setUp() {
        TransactionManager.defaultDatabase = database
        TestDatabaseHelper.resetSchema(
            Organizations,
            OrganizationIntegrations,
            SlackInstallations,
            OnCallSchedules,
            OnCallScheduleUsergroups,
            SlackWorkspaceBindings,
            SlackInstallationGrants,
        )
        service = SlackInstallationService { TestSlackTokenCipher() }
    }

    @Test
    fun `capabilities derive only their documented scopes`() {
        val scopes = service.requestedScopes(listOf("alert_delivery", "on_call_usergroups"))

        assertEquals(
            setOf(
                "chat:write",
                "chat:write.public",
                "channels:read",
                "channels:join",
                "groups:read",
                "usergroups:read",
                "usergroups:write",
                "users:read",
            ),
            scopes,
        )
        assertTrue(service.capabilityCatalog().single { it.id == "assistant" }.optional)
        assertEquals(
            setOf("channels:write", "groups:write", "usergroups:write", "admin.conversations:write"),
            service.requestedUserScopes(listOf("privileged_access")),
        )
        assertTrue(service.scopeCatalog().all { it.reason.isNotBlank() && it.capabilities.isNotEmpty() })
    }

    @Test
    fun `stores encrypted grants for multiple workspaces and assigns one default`() {
        val organizationId = seedOrganization("Multi Slack")
        val requiredScopes = service.requestedScopes(emptyList())

        val first = service.storeOAuthGrant(
            organizationId,
            reauthorizeInstallationId = null,
            capabilityIds = emptyList(),
            grant = grant("T-FIRST", "First", requiredScopes),
        )
        val second = service.storeOAuthGrant(
            organizationId,
            reauthorizeInstallationId = null,
            capabilityIds = emptyList(),
            grant = grant("T-SECOND", "Second", requiredScopes),
        )

        assertTrue(first.isDefault)
        assertFalse(second.isDefault)
        assertEquals(listOf("T-FIRST", "T-SECOND"), service.listInstallations(organizationId).map { it.teamId })
        transaction {
            val ciphertexts = SlackInstallationGrants.selectAll()
                .map { it[SlackInstallationGrants.accessTokenCiphertext] }
            assertEquals(2, ciphertexts.size)
            assertTrue(ciphertexts.none { it.contains("xoxb-") })
            assertNotEquals(ciphertexts[0], ciphertexts[1])
            assertEquals(2, SlackWorkspaceBindings.selectAll().count())
        }
    }

    @Test
    fun `records Enterprise Grid context independently from workspace installations`() {
        val organizationId = seedOrganization("Enterprise Slack")
        val scopes = service.requestedScopes(emptyList())
        service.storeOAuthGrant(
            organizationId,
            reauthorizeInstallationId = null,
            capabilityIds = emptyList(),
            grant = grant("T-WORKSPACE", "Workspace", scopes),
        )

        val enterprise = service.storeOAuthGrant(
            organizationId,
            reauthorizeInstallationId = null,
            capabilityIds = emptyList(),
            grant = SlackOAuthGrant(
                accessToken = "xoxb-enterprise",
                teamId = null,
                teamName = null,
                enterpriseId = "E-GRID",
                enterpriseName = "Example Grid",
                isEnterpriseInstall = true,
                appId = "A-MONEAT",
                botUserId = "U-ENTERPRISE-BOT",
                grantedScopes = scopes,
            ),
        )

        assertTrue(enterprise.isEnterpriseInstall)
        assertEquals("E-GRID", enterprise.enterpriseId)
        assertNull(enterprise.teamId)
        assertTrue(enterprise.workspaceBindings.isEmpty())
        assertFalse(enterprise.isDefault)
        service.bindEnterpriseWorkspace(organizationId, enterprise.id, "T-GRID-ONE", "Grid One", "E-GRID")
        val attached = service.bindEnterpriseWorkspace(
            organizationId,
            enterprise.id,
            "T-GRID-TWO",
            "Grid Two",
            "E-GRID",
        )
        assertEquals(setOf("T-GRID-ONE", "T-GRID-TWO"), attached.workspaceBindings.map { it.teamId }.toSet())
        assertTrue(attached.workspaceBindings.none { it.isPrimary })
        assertFailsWith<IllegalArgumentException> { service.setDefault(organizationId, enterprise.id) }
        assertEquals(2, service.listInstallations(organizationId).size)
    }

    @Test
    fun `stores bot and privileged user grants separately with rotation metadata`() {
        val organizationId = seedOrganization("Privileged Slack")
        val botScopes = service.requestedScopes(listOf("alert_delivery", "privileged_access"))
        val userScopes = service.requestedUserScopes(listOf("alert_delivery", "privileged_access"))
        val original = service.storeOAuthGrant(
            organizationId = organizationId,
            reauthorizeInstallationId = null,
            capabilityIds = listOf("alert_delivery", "privileged_access"),
            grant = grant("T-PRIVILEGED", "Privileged", botScopes).copy(
                refreshToken = "xoxe-bot-refresh",
                expiresInSeconds = 3600,
                userGrant = SlackUserOAuthGrant(
                    accessToken = "xoxp-privileged",
                    slackUserId = "U-OWNER",
                    grantedScopes = userScopes,
                    refreshToken = "xoxe-user-refresh",
                    expiresInSeconds = 3600,
                ),
            ),
        )

        assertTrue(original.missingScopes.isEmpty())
        assertEquals(setOf(SlackGrantType.BOT, SlackGrantType.USER), original.grants.map { it.grantType }.toSet())
        assertTrue(original.grants.all { it.expiresAt != null && it.revokedAt == null })
        assertTrue(original.grantedUserScopes.contains("admin.conversations:write"))

        val rotated = service.storeOAuthGrant(
            organizationId = organizationId,
            reauthorizeInstallationId = original.id,
            capabilityIds = listOf("alert_delivery", "privileged_access"),
            grant = grant("T-PRIVILEGED", "Privileged", botScopes, token = "xoxb-rotated").copy(
                userGrant = SlackUserOAuthGrant(
                    accessToken = "xoxp-rotated",
                    slackUserId = "U-OWNER",
                    grantedScopes = userScopes,
                ),
            ),
        )

        assertTrue(rotated.grants.all { it.rotatedAt != null })
        assertEquals("xoxb-rotated", service.accessToken(organizationId, original.id))

        val leastPrivilege = service.storeOAuthGrant(
            organizationId = organizationId,
            reauthorizeInstallationId = original.id,
            capabilityIds = listOf("alert_delivery"),
            grant = grant("T-PRIVILEGED", "Privileged", service.requestedScopes(listOf("alert_delivery"))),
        )
        assertTrue(leastPrivilege.grantedUserScopes.isEmpty())
        assertTrue(leastPrivilege.grants.single { it.grantType == SlackGrantType.USER }.revokedAt != null)
        transaction {
            val revokedGrant = SlackInstallationGrants
                .selectAll()
                .where { SlackInstallationGrants.grantType eq SlackGrantType.USER.name }
                .single()
            val ciphertext = revokedGrant[SlackInstallationGrants.accessTokenCiphertext]
            assertFalse(String(Base64.getUrlDecoder().decode(ciphertext)).contains("xoxp-rotated"))
            assertNull(revokedGrant[SlackInstallationGrants.refreshTokenCiphertext])
            assertNull(revokedGrant[SlackInstallationGrants.refreshTokenKeyId])
        }
    }

    @Test
    fun `manages workspace defaults delivery state and deletion independently`() {
        val organizationId = seedOrganization("Workspace operations")
        val scopes = service.requestedScopes(emptyList())
        val first = service.storeOAuthGrant(
            organizationId,
            reauthorizeInstallationId = null,
            capabilityIds = emptyList(),
            grant = grant("T-FIRST", "First", scopes),
        )
        val second = service.storeOAuthGrant(
            organizationId,
            reauthorizeInstallationId = null,
            capabilityIds = emptyList(),
            grant = grant("T-SECOND", "Second", scopes),
        )

        service.updateChannel(organizationId, second.id, "C-SECOND", "incidents")
        val selected = service.setDefault(organizationId, second.id)
        val delivery = service.defaultDeliveryConfig(organizationId)

        assertTrue(selected.isDefault)
        assertEquals("xoxb-T-SECOND", delivery?.accessToken)
        assertEquals("xoxb-T-SECOND", service.defaultAccessToken(organizationId))
        assertEquals("U-BOT-T-SECOND", delivery?.botUserId)
        assertEquals("C-SECOND", delivery?.channelId)
        assertEquals(
            service.internalInstallationId(organizationId, second.id),
            service.internalInstallationIdForTeam(organizationId, "T-SECOND"),
        )

        assertEquals(
            SlackInstallationHealthStatus.DISABLED,
            service.setEnabled(organizationId, second.id, false).health,
        )
        assertNull(service.deliveryConfig(organizationId, second.id))
        assertNull(
            service.deliveryConfigByInternalId(
                organizationId,
                service.internalInstallationId(organizationId, second.id),
            ),
        )
        assertEquals(
            SlackInstallationHealthStatus.REAUTHORIZATION_REQUIRED,
            service.setEnabled(organizationId, second.id, true).health,
        )
        assertTrue(service.deleteInstallation(organizationId, second.id))
        assertEquals(first.id, service.listInstallations(organizationId).single().id)
        assertTrue(service.listInstallations(organizationId).single().isDefault)
        assertFailsWith<IllegalArgumentException> {
            service.setDefault(organizationId, "123")
        }
    }

    @Test
    fun `reauthorization preserves mappings and rejects a different workspace`() {
        val organizationId = seedOrganization("Reauthorize")
        val scopes = service.requestedScopes(emptyList())
        val original = service.storeOAuthGrant(
            organizationId,
            null,
            emptyList(),
            grant("T-ORIGINAL", "Original", scopes),
        )
        service.updateChannel(organizationId, original.id, "C-INCIDENTS", "incidents")

        val refreshed = service.storeOAuthGrant(
            organizationId,
            original.id,
            emptyList(),
            grant("T-ORIGINAL", "Renamed", scopes, token = "xoxb-refreshed"),
        )
        val mismatch = service.storeOAuthGrant(
            organizationId,
            original.id,
            emptyList(),
            grant("T-WRONG", "Wrong", scopes),
        )

        assertEquals(original.id, refreshed.id)
        assertEquals("C-INCIDENTS", refreshed.defaultChannelId)
        assertEquals("incidents", refreshed.defaultChannelName)
        assertEquals("Renamed", refreshed.teamName)
        assertEquals(SlackInstallationHealthStatus.WORKSPACE_MISMATCH, mismatch.health)
        assertEquals("T-ORIGINAL", mismatch.teamId)
        assertEquals("xoxb-refreshed", service.accessToken(organizationId, original.id))
    }

    @Test
    fun `installation deletion is blocked while on-call usergroup sync references it`() {
        val organizationId = seedOrganization("Referenced Slack")
        val installation = service.storeOAuthGrant(
            organizationId,
            null,
            emptyList(),
            grant("T-REFERENCED", "Referenced", service.requestedScopes(emptyList())),
        )
        val internalInstallationId = service.internalInstallationId(organizationId, installation.id)
        transaction {
            val scheduleId = OnCallSchedules.insert {
                it[OnCallSchedules.organizationId] = organizationId
                it[name] = "Primary"
                it[rotationType] = "DAILY"
                it[handoffTime] = LocalTime.NOON
                it[timezone] = "UTC"
                it[createdAt] = Clock.System.now()
                it[updatedAt] = Clock.System.now()
            } get OnCallSchedules.id
            OnCallScheduleUsergroups.insert {
                it[OnCallScheduleUsergroups.scheduleId] = scheduleId.value
                it[slackUsergroupId] = "S-ONCALL"
                it[slackUsergroupHandle] = "on-call"
                it[slackInstallationId] = internalInstallationId
                it[createdAt] = Clock.System.now()
                it[updatedAt] = Clock.System.now()
            }
        }

        assertFailsWith<IllegalStateException> {
            service.deleteInstallation(organizationId, installation.id)
        }
    }

    @Test
    fun `health distinguishes missing scopes revoked tokens and workspace mismatch`() = runBlocking {
        val organizationId = seedOrganization("Health")
        val installation = service.storeOAuthGrant(
            organizationId,
            null,
            listOf("incident_commands"),
            grant("T-HEALTH", "Health", setOf("chat:write")),
        )
        assertEquals(SlackInstallationHealthStatus.MISSING_SCOPES, installation.health)

        val revoked = service.verifyInstallation(organizationId, installation.id) {
            SlackAuthenticationProbe(ok = false, error = "token_revoked")
        }
        assertEquals(SlackInstallationHealthStatus.TOKEN_REVOKED, revoked.health)

        val mismatch = service.verifyInstallation(organizationId, installation.id) {
            SlackAuthenticationProbe(ok = true, teamId = "T-OTHER")
        }
        assertEquals(SlackInstallationHealthStatus.WORKSPACE_MISMATCH, mismatch.health)

        val removed = service.verifyInstallation(organizationId, installation.id) {
            SlackAuthenticationProbe(ok = false, error = "account_inactive")
        }
        assertEquals(SlackInstallationHealthStatus.BOT_REMOVED, removed.health)

        val degraded = service.verifyInstallation(organizationId, installation.id) {
            SlackAuthenticationProbe(ok = false, error = "rate_limited")
        }
        assertEquals(SlackInstallationHealthStatus.DEGRADED, degraded.health)
    }

    @Test
    fun `legacy installation migrates atomically and clears plaintext token`() {
        val organizationId = seedOrganization("Legacy")
        transaction {
            OrganizationIntegrations.insert {
                it[organization_id] = organizationId
                it[integration_type] = "slack"
                it[access_token] = "xoxb-legacy-plaintext"
                it[team_id] = "T-LEGACY"
                it[team_name] = "Legacy workspace"
                it[channel_id] = "C-LEGACY"
                it[channel_name] = "alerts"
                it[enabled] = true
                it[created_at] = Clock.System.now()
                it[updated_at] = Clock.System.now()
            }
        }

        val migrated = service.listInstallations(organizationId).single()

        assertEquals("T-LEGACY", migrated.teamId)
        assertEquals("C-LEGACY", migrated.defaultChannelId)
        assertEquals(SlackInstallationHealthStatus.REAUTHORIZATION_REQUIRED, migrated.health)
        assertEquals("xoxb-legacy-plaintext", service.accessToken(organizationId, migrated.id))
        assertEquals("xoxb-legacy-plaintext", service.defaultAccessToken(organizationId))
        transaction {
            val legacyToken = OrganizationIntegrations.selectAll().single()[OrganizationIntegrations.access_token]
            assertNull(legacyToken)
        }
    }

    private fun seedOrganization(name: String): Int = transaction {
        Organizations.insert {
            it[Organizations.name] = name
            it[slug] = name.lowercase().replace(" ", "-")
        } get Organizations.id
    }

    private fun grant(
        teamId: String,
        teamName: String,
        scopes: Set<String>,
        token: String = "xoxb-$teamId",
    ) = SlackOAuthGrant(
        accessToken = token,
        teamId = teamId,
        teamName = teamName,
        enterpriseId = null,
        enterpriseName = null,
        isEnterpriseInstall = false,
        appId = "A-MONEAT",
        botUserId = "U-BOT-$teamId",
        grantedScopes = scopes,
    )

    private class TestSlackTokenCipher : SlackTokenCipher {
        override val activeKeyId: String = "test-key"

        override fun encrypt(plaintext: String, organizationId: Int): String =
            Base64.getUrlEncoder().encodeToString("$organizationId:$plaintext".toByteArray())

        override fun decrypt(ciphertext: String, organizationId: Int): String {
            val decoded = String(Base64.getUrlDecoder().decode(ciphertext))
            val prefix = "$organizationId:"
            require(decoded.startsWith(prefix))
            return decoded.removePrefix(prefix)
        }
    }

    companion object {
        private val database: Database by lazy {
            Database.connect(
                url = "jdbc:h2:mem:moneat_slack_installations;DATABASE_TO_LOWER=TRUE;DB_CLOSE_DELAY=-1",
                driver = "org.h2.Driver",
            )
        }
    }
}
