// Moneat Enterprise - proprietary module
// Copyright (c) 2026 Moneat. All rights reserved.
// See ee/LICENSE for license terms.

package com.moneat.enterprise.sso.routes

import com.auth0.jwt.JWT
import com.auth0.jwt.algorithms.Algorithm
import com.moneat.billing.models.PricingTierConfigs
import com.moneat.enterprise.sso.support.EnterpriseTestDatabaseHelper
import com.moneat.enterprise.sso.support.MockOidcDiscoveryServer
import com.moneat.shared.models.Memberships
import com.moneat.shared.models.Organizations
import com.moneat.shared.models.SsoConfigurations
import com.moneat.shared.models.Subscriptions
import com.moneat.shared.models.Users
import com.moneat.sso.models.SsoConfigRequest
import com.moneat.sso.routes.ssoRoutes
import com.moneat.sso.services.SsoService
import io.ktor.client.request.delete
import io.ktor.client.request.get
import io.ktor.client.request.header
import io.ktor.client.request.post
import io.ktor.client.request.put
import io.ktor.client.request.setBody
import io.ktor.client.statement.bodyAsText
import io.ktor.http.ContentType
import io.ktor.http.HttpHeaders
import io.ktor.http.HttpStatusCode
import io.ktor.serialization.kotlinx.json.json
import io.ktor.server.application.install
import io.ktor.server.auth.Authentication
import io.ktor.server.auth.jwt.JWTPrincipal
import io.ktor.server.auth.jwt.jwt
import io.ktor.server.plugins.contentnegotiation.ContentNegotiation
import io.ktor.server.routing.routing
import io.ktor.server.testing.testApplication
import kotlin.time.Clock
import kotlin.time.Instant
import kotlinx.serialization.Serializable
import kotlinx.serialization.json.Json
import org.jetbrains.exposed.v1.jdbc.Database
import org.jetbrains.exposed.v1.jdbc.insert
import org.jetbrains.exposed.v1.jdbc.transactions.TransactionManager
import org.jetbrains.exposed.v1.jdbc.transactions.transaction
import org.junit.jupiter.api.AfterEach
import org.junit.jupiter.api.BeforeEach
import org.junit.jupiter.api.Test
import kotlin.test.assertEquals
import kotlin.test.assertTrue

class SsoRoutesTest {

    companion object {
        private var db: Database? = null
        private const val JWT_SECRET = "test-secret-for-unit-tests"
        private const val OIDC_ISSUER = "https://example.com/realms/routes"
    }

    private val json = Json { ignoreUnknownKeys = true }

    @BeforeEach
    fun setup() {
        if (db == null) {
            db =
                Database.connect(
                    url = "jdbc:h2:mem:moneat_ee_sso_routes;DATABASE_TO_LOWER=TRUE;DB_CLOSE_DELAY=-1",
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
    fun clearDb() {
        TransactionManager.defaultDatabase = null
    }

    private fun io.ktor.server.application.Application.installAuthAndJson() {
        install(ContentNegotiation) { json() }
        install(Authentication) {
            jwt("auth-jwt") {
                verifier(
                    JWT
                        .require(Algorithm.HMAC256(JWT_SECRET))
                        .withIssuer("moneat")
                        .withAudience("moneat-users")
                        .build(),
                )
                validate { JWTPrincipal(it.payload) }
            }
        }
    }

    private fun bearerForUser(userId: Int): String =
        JWT
            .create()
            .withIssuer("moneat")
            .withAudience("moneat-users")
            .withClaim("userId", userId)
            .sign(Algorithm.HMAC256(JWT_SECRET))

    private fun seedTeamOrgOwner(): Triple<Int, Int, Int> {
        val tierId =
            transaction {
                PricingTierConfigs.insert {
                    it[PricingTierConfigs.tier_name] = "TEAM"
                    it[PricingTierConfigs.version] = 1
                    it[PricingTierConfigs.monthly_unit_limit] = 1_000_000L
                    it[PricingTierConfigs.monthly_error_limit] = 1_000_000L
                    it[PricingTierConfigs.monthly_transaction_limit] = 0L
                    it[PricingTierConfigs.monthly_replay_limit] = 300L
                    it[PricingTierConfigs.monthly_feedback_limit] = 0L
                    it[PricingTierConfigs.monthly_gb_limit] = 10L
                    it[PricingTierConfigs.retention_days] = 30
                    it[PricingTierConfigs.log_retention_days] = 30
                    it[PricingTierConfigs.status_pages_enabled] = true
                    it[PricingTierConfigs.status_page_custom_domain_enabled] = true
                    it[PricingTierConfigs.session_replay_enabled] = true
                    it[PricingTierConfigs.slack_enabled] = false
                    it[PricingTierConfigs.incident_io_enabled] = false
                    it[PricingTierConfigs.saml_enabled] = true
                    it[PricingTierConfigs.oidc_enabled] = true
                    it[PricingTierConfigs.priority_support_enabled] = false
                    it[PricingTierConfigs.sla_enabled] = false
                    it[PricingTierConfigs.custom_retention_enabled] = false
                    it[PricingTierConfigs.max_projects] = null
                    it[PricingTierConfigs.max_systems] = 25
                    it[PricingTierConfigs.monitor_interval_seconds] = 10
                    it[PricingTierConfigs.monthly_price_cents] = 9900
                    it[PricingTierConfigs.yearly_price_cents] = 99000
                    it[PricingTierConfigs.trial_days] = 14
                    it[PricingTierConfigs.payg_enabled] = false
                    it[PricingTierConfigs.payg_rate_micros_per_unit] = 0L
                    it[PricingTierConfigs.overage_rate_cents_per_gb] = 40
                    it[PricingTierConfigs.stripe_base_price_id] = null
                    it[PricingTierConfigs.stripe_overage_price_id] = null
                    it[PricingTierConfigs.stripe_yearly_base_price_id] = null
                    it[PricingTierConfigs.stripe_yearly_overage_price_id] = null
                    it[PricingTierConfigs.is_current] = true
                }[PricingTierConfigs.id]
            }
        val orgId =
            transaction {
                Organizations.insert {
                    it[Organizations.name] = "Route Org"
                    it[Organizations.slug] = "route-org"
                }[Organizations.id]
            }
        val ownerId =
            transaction {
                Users.insert {
                    it[Users.email] = "owner@routes.example"
                    it[Users.password_hash] = "x"
                }[Users.id]
            }
        transaction {
            Memberships.insert {
                it[Memberships.user_id] = ownerId
                it[Memberships.organization_id] = orgId
                it[Memberships.role] = "owner"
            }
        }
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
        return Triple(orgId, ownerId, tierId)
    }

    private fun <T> withFrontendUrl(block: () -> T): T {
        val key = "FRONTEND_URL"
        val previous = System.getProperty(key)
        System.setProperty(key, "https://dashboard.test.local")
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

    @Test
    fun `post auth sso init returns OIDC redirect payload`() =
        withFrontendUrl {
            withOidcDiscoveryServer { issuerUrl ->
                val (orgId, ownerId, _) = seedTeamOrgOwner()
                val service = SsoService()
                service.configureSso(
                    orgId,
                    ownerId,
                    SsoConfigRequest(
                        providerType = "oidc",
                        oidcIssuerUrl = issuerUrl,
                        oidcClientId = "route-client",
                        oidcClientSecret = "route-secret",
                        emailDomain = "routes.example",
                    ),
                )

                testApplication {
                    application {
                        installAuthAndJson()
                    }
                    routing { ssoRoutes() }

                    val response =
                        client.post("/auth/sso/init") {
                            header(HttpHeaders.ContentType, ContentType.Application.Json.toString())
                            setBody("""{"email":"user@routes.example"}""")
                        }
                    assertEquals(HttpStatusCode.OK, response.status)
                    val body = response.bodyAsText()
                    assertTrue(body.contains("protocol/openid-connect/auth"))
                    assertTrue(body.contains("route-client"))
                }
            }
        }

    @Test
    fun `post auth sso init returns 400 when SSO is not configured`() =
        withFrontendUrl {
            seedTeamOrgOwner()
            testApplication {
                application {
                    installAuthAndJson()
                }
                routing { ssoRoutes() }

                val response =
                    client.post("/auth/sso/init") {
                        header(HttpHeaders.ContentType, ContentType.Application.Json.toString())
                        setBody("""{"email":"nobody@elsewhere.com"}""")
                    }
                assertEquals(HttpStatusCode.BadRequest, response.status)
            }
        }

    @Test
    fun `get v1 sso config returns 404 when absent`() =
        withFrontendUrl {
            val (orgId, ownerId, _) = seedTeamOrgOwner()
            testApplication {
                application {
                    installAuthAndJson()
                }
                routing { ssoRoutes() }

                val response =
                    client.get("/v1/sso/config?organizationId=$orgId") {
                        header(HttpHeaders.Authorization, "Bearer ${bearerForUser(ownerId)}")
                    }
                assertEquals(HttpStatusCode.NotFound, response.status)
            }
        }

    @Test
    fun `put v1 sso config persists and get returns configuration`() =
        withFrontendUrl {
            val (orgId, ownerId, _) = seedTeamOrgOwner()
            testApplication {
                application {
                    installAuthAndJson()
                }
                routing { ssoRoutes() }

                val putResponse =
                    client.put("/v1/sso/config?organizationId=$orgId") {
                        header(HttpHeaders.Authorization, "Bearer ${bearerForUser(ownerId)}")
                        header(HttpHeaders.ContentType, ContentType.Application.Json.toString())
                        setBody(
                            """
                            {
                              "providerType": "oidc",
                              "isEnabled": true,
                              "oidcIssuerUrl": "$OIDC_ISSUER",
                              "oidcClientId": "api-client",
                              "oidcClientSecret": "api-secret",
                              "emailDomain": "api.example"
                            }
                            """.trimIndent(),
                        )
                    }
                assertEquals(HttpStatusCode.OK, putResponse.status)

                val getResponse =
                    client.get("/v1/sso/config?organizationId=$orgId") {
                        header(HttpHeaders.Authorization, "Bearer ${bearerForUser(ownerId)}")
                    }
                assertEquals(HttpStatusCode.OK, getResponse.status)
                val cfg = json.decodeFromString<SsoConfigResponseWire>(getResponse.bodyAsText())
                assertEquals("oidc", cfg.providerType)
                assertEquals("api-client", cfg.oidcClientId)
                assertTrue(cfg.hasClientSecret)
            }
        }

    @Test
    fun `delete v1 sso config removes configuration`() =
        withFrontendUrl {
            val (orgId, ownerId, _) = seedTeamOrgOwner()
            testApplication {
                application {
                    installAuthAndJson()
                }
                routing { ssoRoutes() }

                client.put("/v1/sso/config?organizationId=$orgId") {
                    header(HttpHeaders.Authorization, "Bearer ${bearerForUser(ownerId)}")
                    header(HttpHeaders.ContentType, ContentType.Application.Json.toString())
                    setBody(
                        """
                        {
                          "providerType": "oidc",
                          "oidcIssuerUrl": "$OIDC_ISSUER",
                          "oidcClientId": "del-client",
                          "oidcClientSecret": "del-secret"
                        }
                        """.trimIndent(),
                    )
                }

                val del =
                    client.delete("/v1/sso/config?organizationId=$orgId") {
                        header(HttpHeaders.Authorization, "Bearer ${bearerForUser(ownerId)}")
                    }
                assertEquals(HttpStatusCode.OK, del.status)

                val get =
                    client.get("/v1/sso/config?organizationId=$orgId") {
                        header(HttpHeaders.Authorization, "Bearer ${bearerForUser(ownerId)}")
                    }
                assertEquals(HttpStatusCode.NotFound, get.status)
            }
        }

    @Test
    fun `post v1 sso check-required stays false without SAML module`() =
        withFrontendUrl {
            val (orgId, ownerId, _) = seedTeamOrgOwner()
            testApplication {
                application {
                    installAuthAndJson()
                }
                routing { ssoRoutes() }

                val before =
                    client.post("/v1/sso/check-required") {
                        header(HttpHeaders.Authorization, "Bearer ${bearerForUser(ownerId)}")
                        header(HttpHeaders.ContentType, ContentType.Application.Json.toString())
                        setBody("""{"email":"a@check.example"}""")
                    }
                assertEquals(HttpStatusCode.OK, before.status)
                assertTrue(before.bodyAsText().contains("\"required\":false"))

                val deniedRequireSso =
                    client.put("/v1/sso/config?organizationId=$orgId") {
                        header(HttpHeaders.Authorization, "Bearer ${bearerForUser(ownerId)}")
                        header(HttpHeaders.ContentType, ContentType.Application.Json.toString())
                        setBody(
                            """
                            {
                              "providerType": "oidc",
                              "oidcIssuerUrl": "$OIDC_ISSUER",
                              "oidcClientId": "chk",
                              "oidcClientSecret": "chk",
                              "emailDomain": "check.example",
                              "requireSso": true
                            }
                            """.trimIndent(),
                        )
                    }
                assertEquals(HttpStatusCode.Forbidden, deniedRequireSso.status)

                val allowed =
                    client.put("/v1/sso/config?organizationId=$orgId") {
                        header(HttpHeaders.Authorization, "Bearer ${bearerForUser(ownerId)}")
                        header(HttpHeaders.ContentType, ContentType.Application.Json.toString())
                        setBody(
                            """
                            {
                              "providerType": "oidc",
                              "oidcIssuerUrl": "$OIDC_ISSUER",
                              "oidcClientId": "chk",
                              "oidcClientSecret": "chk",
                              "emailDomain": "check.example"
                            }
                            """.trimIndent(),
                        )
                    }
                assertEquals(HttpStatusCode.OK, allowed.status)

                val after =
                    client.post("/v1/sso/check-required") {
                        header(HttpHeaders.Authorization, "Bearer ${bearerForUser(ownerId)}")
                        header(HttpHeaders.ContentType, ContentType.Application.Json.toString())
                        setBody("""{"email":"b@check.example"}""")
                    }
                assertEquals(HttpStatusCode.OK, after.status)
                assertTrue(after.bodyAsText().contains("\"required\":false"))
            }
        }

    @Serializable
    private data class SsoConfigResponseWire(
        val providerType: String,
        val oidcClientId: String? = null,
        val hasClientSecret: Boolean = false,
    )
}
