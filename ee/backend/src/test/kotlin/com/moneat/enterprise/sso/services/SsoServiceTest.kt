// Moneat Enterprise - proprietary module
// Copyright (c) 2026 Moneat. All rights reserved.
// See ee/LICENSE for license terms.

package com.moneat.enterprise.sso.services

import com.moneat.billing.models.PricingTierConfigs
import com.moneat.enterprise.sso.support.EnterpriseTestDatabaseHelper
import com.moneat.enterprise.sso.support.MockOidcDiscoveryServer
import com.moneat.shared.models.Memberships
import com.moneat.shared.models.Organizations
import com.moneat.shared.models.SsoConfigurations
import com.moneat.shared.models.Subscriptions
import com.moneat.shared.models.Users
import com.moneat.sso.SsoForbiddenException
import com.moneat.sso.models.SsoConfigRequest
import com.moneat.sso.services.SsoService
import io.ktor.server.plugins.BadRequestException
import kotlinx.coroutines.runBlocking
import kotlin.time.Clock
import kotlin.time.Instant
import org.jetbrains.exposed.v1.core.eq
import org.jetbrains.exposed.v1.jdbc.Database
import org.jetbrains.exposed.v1.jdbc.deleteWhere
import org.jetbrains.exposed.v1.jdbc.insert
import org.jetbrains.exposed.v1.jdbc.update
import org.jetbrains.exposed.v1.jdbc.transactions.TransactionManager
import org.jetbrains.exposed.v1.jdbc.transactions.transaction
import org.junit.jupiter.api.AfterEach
import org.junit.jupiter.api.BeforeEach
import org.junit.jupiter.api.Test
import kotlin.test.assertEquals
import kotlin.test.assertFailsWith
import kotlin.test.assertFalse
import kotlin.test.assertNotNull
import kotlin.test.assertNull
import kotlin.test.assertTrue

class SsoServiceTest {

    companion object {
        private var db: Database? = null

        /** Public host that resolves in CI/sandbox (UrlValidator DNS check). */
        private const val OIDC_ISSUER = "https://example.com/realms/acme"
    }

    private val service = SsoService()
    private val samlService = SamlService()

    @BeforeEach
    fun setup() {
        if (db == null) {
            db =
                Database.connect(
                    url = "jdbc:h2:mem:moneat_ee_sso;DATABASE_TO_LOWER=TRUE;DB_CLOSE_DELAY=-1",
                    driver = "org.h2.Driver",
                )
        }
        TransactionManager.defaultDatabase = db
        EnterpriseTestDatabaseHelper.resetSchema(
            PricingTierConfigs,
            Users,
            Organizations,
            Memberships,
            Subscriptions,
            SsoConfigurations,
        )
    }

    @AfterEach
    fun clearDbRef() {
        TransactionManager.defaultDatabase = null
    }

    private fun insertTeamTier(): Int =
        transaction {
            PricingTierConfigs.insert {
                it[tier_name] = "TEAM"
                it[version] = 1
                it[monthly_unit_limit] = 1_000_000L
                it[monthly_error_limit] = 1_000_000L
                it[monthly_transaction_limit] = 0L
                it[monthly_replay_limit] = 300L
                it[monthly_feedback_limit] = 0L
                it[monthly_gb_limit] = 10L
                it[retention_days] = 30
                it[log_retention_days] = 30
                it[status_pages_enabled] = true
                it[status_page_custom_domain_enabled] = true
                it[session_replay_enabled] = true
                it[slack_enabled] = false
                it[incident_io_enabled] = false
                it[saml_enabled] = true
                it[oidc_enabled] = true
                it[priority_support_enabled] = false
                it[sla_enabled] = false
                it[custom_retention_enabled] = false
                it[max_projects] = null
                it[max_systems] = 25
                it[monitor_interval_seconds] = 10
                it[monthly_price_cents] = 9900
                it[yearly_price_cents] = 99000
                it[trial_days] = 14
                it[payg_enabled] = false
                it[payg_rate_micros_per_unit] = 0L
                it[overage_rate_cents_per_gb] = 40
                it[stripe_base_price_id] = null
                it[stripe_overage_price_id] = null
                it[stripe_yearly_base_price_id] = null
                it[stripe_yearly_overage_price_id] = null
                it[is_current] = true
            }[PricingTierConfigs.id]
        }

    private fun insertOrg(name: String = "Acme", slugValue: String = "acme"): Int =
        transaction {
            Organizations.insert {
                it[Organizations.name] = name
                it[Organizations.slug] = slugValue
            }[Organizations.id]
        }

    private fun insertUser(email: String): Int =
        transaction {
            Users.insert {
                it[Users.email] = email
                it[Users.password_hash] = "x"
                it[Users.name] = "Test User"
            }[Users.id]
        }

    private fun linkOwner(orgId: Int, userId: Int) {
        transaction {
            Memberships.insert {
                it[Memberships.user_id] = userId
                it[Memberships.organization_id] = orgId
                it[Memberships.role] = "owner"
            }
        }
    }

    private fun linkMember(orgId: Int, userId: Int) {
        transaction {
            Memberships.insert {
                it[Memberships.user_id] = userId
                it[Memberships.organization_id] = orgId
                it[Memberships.role] = "member"
            }
        }
    }

    private fun insertActiveTeamSubscription(orgId: Int, tierId: Int) {
        val now = Clock.System.now()
        transaction {
            Subscriptions.insert {
                it[Subscriptions.organization_id] = orgId
                it[Subscriptions.plan] = "TEAM"
                it[Subscriptions.status] = "active"
                it[Subscriptions.billing_interval] = "monthly"
                it[Subscriptions.current_period_start] = now
                it[Subscriptions.current_period_end] =
                    Instant.fromEpochSeconds(now.epochSeconds + 2_592_000)
                it[Subscriptions.pricing_tier_config_id] = tierId
                it[Subscriptions.payg_budget_cents] = 0
            }
        }
    }

    private fun seedOrgReadyForSsoConfigure(): Pair<Int, Int> {
        val tierId = insertTeamTier()
        val orgId = insertOrg()
        val ownerId = insertUser("owner@acme.example")
        linkOwner(orgId, ownerId)
        insertActiveTeamSubscription(orgId, tierId)
        return orgId to ownerId
    }

    private fun initSso(
        email: String?,
        orgSlug: String?,
    ) = runBlocking {
        service.initSso(email, orgSlug)
    }

    private fun handleOidcCallback(
        code: String,
        state: String,
    ) = runBlocking {
        service.handleOidcCallback(code, state)
    }

    private fun <T> withSelfHosted(block: () -> T): T {
        val key = "SELF_HOSTED"
        val previous = System.getProperty(key)
        System.setProperty(key, "true")
        return try {
            block()
        } finally {
            if (previous == null) {
                System.clearProperty(key)
            } else {
                System.setProperty(key, previous)
            }
        }
    }

    private fun <T> withOidcDiscoveryServer(block: (issuerUrl: String) -> T): T =
        MockOidcDiscoveryServer().use { server ->
            withSelfHosted {
                block(server.baseUrl)
            }
        }

    // ──── initSso ────

    @Test
    fun `initSso requires email or org slug`() {
        val ex = assertFailsWith<IllegalArgumentException> {
            initSso(null, null)
        }
        assertEquals("Either email or orgSlug must be provided", ex.message)
    }

    @Test
    fun `initSso rejects unknown organization slug`() {
        val ex = assertFailsWith<IllegalArgumentException> {
            initSso(null, "missing-org")
        }
        assertEquals("Organization not found", ex.message)
    }

    @Test
    fun `initSso rejects when SSO is not enabled for domain`() {
        insertOrg()
        val ex = assertFailsWith<IllegalArgumentException> {
            initSso("nobody@unknown.domain", null)
        }
        assertEquals("SSO is not configured for this email domain or organization", ex.message)
    }

    @Test
    fun `initSso returns OIDC redirect for matching email domain`() = withOidcDiscoveryServer { issuerUrl ->
        val (orgId, ownerId) = seedOrgReadyForSsoConfigure()
        service.configureSso(
            orgId,
            ownerId,
            SsoConfigRequest(
                providerType = "oidc",
                isEnabled = true,
                oidcIssuerUrl = issuerUrl,
                oidcClientId = "client-id",
                oidcClientSecret = "client-secret",
                emailDomain = "corp.example",
            ),
        )
        val response = initSso("alice@corp.example", null)
        assertEquals("oidc", response.providerType)
        assertTrue(response.redirectUrl.contains("/protocol/openid-connect/auth"))
        assertTrue(response.redirectUrl.contains("client_id=client-id"))
        assertNotNull(response.state)
    }

    @Test
    fun `initSso returns OIDC redirect when resolving by organization slug`() = withOidcDiscoveryServer { issuerUrl ->
        val (orgId, ownerId) = seedOrgReadyForSsoConfigure()
        service.configureSso(
            orgId,
            ownerId,
            SsoConfigRequest(
                providerType = "oidc",
                isEnabled = true,
                oidcIssuerUrl = issuerUrl,
                oidcClientId = "cid-slug",
                oidcClientSecret = "sec",
                emailDomain = "slug.example",
            ),
        )
        val response = initSso(null, "acme")
        assertEquals("oidc", response.providerType)
        assertTrue(response.redirectUrl.contains("client_id=cid-slug"))
    }

    // ──── configureSso tier and validation ────

    @Test
    fun `configureSso rejects non Team and Business tiers`() {
        val orgId = insertOrg()
        val ownerId = insertUser("owner@free.example")
        linkOwner(orgId, ownerId)
        val ex = assertFailsWith<SsoForbiddenException> {
            service.configureSso(
                orgId,
                ownerId,
                SsoConfigRequest(
                    providerType = "oidc",
                    oidcIssuerUrl = OIDC_ISSUER,
                    oidcClientId = "c",
                    oidcClientSecret = "s",
                ),
            )
        }
        assertEquals("SSO is only available on Team and Business plans", ex.message)
    }

    @Test
    fun `configureSso rejects non-owner`() {
        val tierId = insertTeamTier()
        val orgId = insertOrg()
        val ownerId = insertUser("owner@x.example")
        val memberId = insertUser("member@x.example")
        linkOwner(orgId, ownerId)
        linkMember(orgId, memberId)
        insertActiveTeamSubscription(orgId, tierId)
        val ex = assertFailsWith<SsoForbiddenException> {
            service.configureSso(
                orgId,
                memberId,
                SsoConfigRequest(
                    providerType = "oidc",
                    oidcIssuerUrl = OIDC_ISSUER,
                    oidcClientId = "c",
                    oidcClientSecret = "s",
                ),
            )
        }
        assertEquals("Only organization owners can configure SSO", ex.message)
    }

    @Test
    fun `configureSso rejects incomplete OIDC configuration`() {
        val (orgId, ownerId) = seedOrgReadyForSsoConfigure()
        val ex = assertFailsWith<BadRequestException> {
            service.configureSso(
                orgId,
                ownerId,
                SsoConfigRequest(
                    providerType = "oidc",
                    oidcIssuerUrl = OIDC_ISSUER,
                    oidcClientId = "c",
                    oidcClientSecret = "",
                ),
            )
        }
        assertTrue(
            ex.message!!.contains("OIDC requires oidcIssuerUrl, oidcClientId, and oidcClientSecret"),
        )
    }

    @Test
    fun `configureSso rejects incomplete SAML configuration`() {
        val (orgId, ownerId) = seedOrgReadyForSsoConfigure()
        val ex = assertFailsWith<BadRequestException> {
            service.configureSso(
                orgId,
                ownerId,
                SsoConfigRequest(
                    providerType = "saml",
                    idpEntityId = "https://idp.example.com/entity",
                    idpSsoUrl = null,
                    idpCertificate = "CERT",
                ),
            )
        }
        assertTrue(
            ex.message!!.contains("SAML requires idpEntityId, idpSsoUrl, and idpCertificate"),
        )
    }

    @Test
    fun `configureSso persists OIDC and getSsoConfig returns sanitized response`() {
        val (orgId, ownerId) = seedOrgReadyForSsoConfigure()
        val saved =
            service.configureSso(
                orgId,
                ownerId,
                SsoConfigRequest(
                    providerType = "oidc",
                    isEnabled = true,
                    oidcIssuerUrl = OIDC_ISSUER,
                    oidcClientId = "persist-client",
                    oidcClientSecret = "super-secret",
                    emailDomain = "persist.example",
                ),
            )
        assertEquals("oidc", saved.providerType)
        assertEquals(OIDC_ISSUER, saved.oidcIssuerUrl)
        assertTrue(saved.hasClientSecret)

        val loaded = service.getSsoConfig(orgId)
        assertNotNull(loaded)
        assertEquals("persist-client", loaded.oidcClientId)
        assertTrue(loaded.hasClientSecret)
        assertFalse(loaded.requireSso)
    }

    @Test
    fun `configureSso rejects require SSO without SAML module`() {
        val (orgId, ownerId) = seedOrgReadyForSsoConfigure()
        val ex = assertFailsWith<SsoForbiddenException> {
            service.configureSso(
                orgId,
                ownerId,
                SsoConfigRequest(
                    providerType = "oidc",
                    isEnabled = true,
                    oidcIssuerUrl = OIDC_ISSUER,
                    oidcClientId = "client",
                    oidcClientSecret = "secret",
                    emailDomain = "required.example",
                    requireSso = true,
                ),
            )
        }
        assertEquals("SSO enforcement (Require SSO) requires an enterprise license", ex.message)
    }

    // ──── deleteSsoConfig ────

    @Test
    fun `deleteSsoConfig returns false when no configuration exists`() {
        val (orgId, ownerId) = seedOrgReadyForSsoConfigure()
        assertFalse(service.deleteSsoConfig(orgId, ownerId))
    }

    @Test
    fun `deleteSsoConfig removes configuration for owner`() {
        val (orgId, ownerId) = seedOrgReadyForSsoConfigure()
        service.configureSso(
            orgId,
            ownerId,
            SsoConfigRequest(
                providerType = "oidc",
                oidcIssuerUrl = OIDC_ISSUER,
                oidcClientId = "c",
                oidcClientSecret = "s",
            ),
        )
        assertTrue(service.deleteSsoConfig(orgId, ownerId))
        assertNull(service.getSsoConfig(orgId))
    }

    @Test
    fun `deleteSsoConfig rejects non-owner`() {
        val (orgId, ownerId) = seedOrgReadyForSsoConfigure()
        service.configureSso(
            orgId,
            ownerId,
            SsoConfigRequest(
                providerType = "oidc",
                oidcIssuerUrl = OIDC_ISSUER,
                oidcClientId = "c",
                oidcClientSecret = "s",
            ),
        )
        val memberId = insertUser("member2@x.example")
        linkMember(orgId, memberId)
        val ex = assertFailsWith<SsoForbiddenException> {
            service.deleteSsoConfig(orgId, memberId)
        }
        assertEquals("Only organization owners can delete SSO configuration", ex.message)
    }

    // ──── checkSsoRequired ────

    @Test
    fun `checkSsoRequired is false when domain not configured`() {
        insertOrg()
        assertFalse(service.checkSsoRequired("a@example.com"))
    }

    @Test
    fun `checkSsoRequired is false when SAML module is not loaded`() {
        val (orgId, ownerId) = seedOrgReadyForSsoConfigure()
        service.configureSso(
            orgId,
            ownerId,
            SsoConfigRequest(
                providerType = "oidc",
                oidcIssuerUrl = OIDC_ISSUER,
                oidcClientId = "c",
                oidcClientSecret = "s",
                emailDomain = "required.example",
            ),
        )
        transaction {
            SsoConfigurations.update({ SsoConfigurations.organizationId eq orgId }) {
                it[SsoConfigurations.requireSso] = true
            }
        }
        assertFalse(service.checkSsoRequired("user@required.example"))
    }

    // ──── SAML and OIDC callback edge cases ────

    @Test
    fun `handleSamlResponse requires RelayState`() {
        val ex = assertFailsWith<IllegalArgumentException> {
            samlService.handleSamlResponse("dGVzdA==", null)
        }
        assertEquals("Missing RelayState parameter", ex.message)
    }

    @Test
    fun `handleOidcCallback rejects malformed state`() {
        val ex = assertFailsWith<IllegalArgumentException> {
            handleOidcCallback("code", "not-valid-base64!!!")
        }
        assertTrue(
            ex.message == "Invalid state parameter" ||
                ex.message!!.contains("base64", ignoreCase = true),
        )
    }

    @Test
    fun `handleOidcCallback rejects when configuration disappears after init`() = withOidcDiscoveryServer { issuerUrl ->
        val (orgId, ownerId) = seedOrgReadyForSsoConfigure()
        service.configureSso(
            orgId,
            ownerId,
            SsoConfigRequest(
                providerType = "oidc",
                oidcIssuerUrl = issuerUrl,
                oidcClientId = "c",
                oidcClientSecret = "s",
                emailDomain = "drop.example",
            ),
        )
        val state = initSso("u@drop.example", null).state!!
        transaction {
            SsoConfigurations.deleteWhere { SsoConfigurations.organizationId eq orgId }
        }
        val ex = assertFailsWith<IllegalArgumentException> {
            handleOidcCallback("dummy-code", state)
        }
        assertEquals("SSO configuration not found", ex.message)
    }

    // ──── getSamlMetadata ────

    @Test
    fun `getSamlMetadata requires organization slug`() {
        val ex = assertFailsWith<IllegalArgumentException> {
            samlService.getSamlMetadata(null)
        }
        assertEquals("Organization slug is required", ex.message)
    }

    @Test
    fun `getSamlMetadata rejects unknown organization`() {
        val ex = assertFailsWith<IllegalArgumentException> {
            samlService.getSamlMetadata("unknown-slug")
        }
        assertEquals("Organization not found", ex.message)
    }

    @Test
    fun `getSamlMetadata rejects when SAML is not configured`() {
        val (orgId, ownerId) = seedOrgReadyForSsoConfigure()
        service.configureSso(
            orgId,
            ownerId,
            SsoConfigRequest(
                providerType = "oidc",
                oidcIssuerUrl = OIDC_ISSUER,
                oidcClientId = "c",
                oidcClientSecret = "s",
            ),
        )
        val ex = assertFailsWith<IllegalArgumentException> {
            samlService.getSamlMetadata("acme")
        }
        assertEquals("SAML SSO is not configured for this organization", ex.message)
    }
}
