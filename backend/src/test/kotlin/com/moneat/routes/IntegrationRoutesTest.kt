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

package com.moneat.routes

import com.auth0.jwt.JWT
import com.auth0.jwt.algorithms.Algorithm
import com.moneat.billing.models.PricingTierConfigs
import com.moneat.org.routes.integrationCallbackRoutes
import com.moneat.org.routes.integrationRoutes
import com.moneat.shared.models.Memberships
import com.moneat.shared.models.OrganizationIntegrations
import com.moneat.shared.models.Organizations
import com.moneat.shared.models.SlackUserMappings
import com.moneat.shared.models.Subscriptions
import com.moneat.shared.models.Users
import com.moneat.testsupport.TestDatabaseHelper
import com.moneat.testsupport.startTestKoin
import com.moneat.testsupport.stopTestKoin
import io.ktor.client.request.get
import io.ktor.client.request.header
import io.ktor.client.request.post
import io.ktor.client.request.setBody
import io.ktor.client.statement.bodyAsText
import io.ktor.http.ContentType
import io.ktor.http.HttpHeaders
import io.ktor.http.HttpStatusCode
import io.ktor.http.contentType
import io.ktor.serialization.kotlinx.json.json
import io.ktor.server.application.install
import io.ktor.server.auth.Authentication
import io.ktor.server.auth.authenticate
import io.ktor.server.auth.jwt.JWTPrincipal
import io.ktor.server.auth.jwt.jwt
import io.ktor.server.plugins.contentnegotiation.ContentNegotiation
import io.ktor.server.routing.route
import io.ktor.server.routing.routing
import io.ktor.server.testing.testApplication
import org.jetbrains.exposed.v1.jdbc.Database
import org.jetbrains.exposed.v1.jdbc.insert
import org.jetbrains.exposed.v1.jdbc.transactions.transaction
import kotlin.test.AfterTest
import kotlin.test.BeforeTest
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertTrue
import kotlin.time.Clock

class IntegrationRoutesTest {
    private val jwtSecret = "test-secret-for-integration-routes"

    companion object {
        private var dbInitialized = false
    }

    @BeforeTest
    fun setupDatabase() {
        startTestKoin()
        if (!dbInitialized) {
            Database.connect(
                url = "jdbc:h2:mem:moneat_integration_routes;DATABASE_TO_LOWER=TRUE;DB_CLOSE_DELAY=-1",
                driver = "org.h2.Driver"
            )
            dbInitialized = true
        }

        // Ensure schema exists (idempotent in H2) and clean between tests
        TestDatabaseHelper.resetSchema(
            Users,
            Organizations,
            Memberships,
            OrganizationIntegrations,
            SlackUserMappings,
            PricingTierConfigs,
            Subscriptions
        )
    }

    @Test
    fun `integrations list returns 404 when user has no organization membership`() {
        val userId = seedUser("nomember@test.com")

        testApplication {
            application {
                install(ContentNegotiation) { json() }
                installAuth()
                routing {
                    authenticate("auth-jwt") {
                        route("/v1") {
                            integrationRoutes()
                        }
                    }
                }
            }

            val response =
                client.get("/v1/integrations") {
                    header(HttpHeaders.Authorization, "Bearer ${token(userId)}")
                }

            assertEquals(HttpStatusCode.NotFound, response.status)
            assertTrue(response.bodyAsText().contains("No organization found"))
        }
    }

    @Test
    fun `integration org helper returns unauthorized when principal is missing`() {
        testApplication {
            application {
                install(ContentNegotiation) { json() }
                routing {
                    route("/v1") {
                        integrationRoutes()
                    }
                }
            }

            val response = client.get("/v1/integrations/slack/channels")

            assertEquals(HttpStatusCode.Unauthorized, response.status)
            assertTrue(response.bodyAsText().contains("Unauthorized"))
        }
    }

    @Test
    fun `integration org helper returns not found when JWT has no org`() {
        val userId = seedUser("no-org-claim@test.com")

        testApplication {
            application {
                install(ContentNegotiation) { json() }
                installAuth()
                routing {
                    authenticate("auth-jwt") {
                        route("/v1") {
                            integrationRoutes()
                        }
                    }
                }
            }

            val response =
                client.get("/v1/integrations/slack/channels") {
                    header(HttpHeaders.Authorization, "Bearer ${token(userId)}")
                }

            assertEquals(HttpStatusCode.NotFound, response.status)
            assertTrue(response.bodyAsText().contains("No organization found"))
        }
    }

    @Test
    fun `integrations list returns configured integration records`() {
        val orgId = seedOrganization("Integration Org")
        val userId = seedUser("member@test.com")
        seedMembership(orgId, userId, "owner")
        seedIntegration(orgId, "slack", true)

        testApplication {
            application {
                install(ContentNegotiation) { json() }
                installAuth()
                routing {
                    authenticate("auth-jwt") {
                        route("/v1") {
                            integrationRoutes()
                        }
                    }
                }
            }

            val response =
                client.get("/v1/integrations") {
                    header(HttpHeaders.Authorization, "Bearer ${token(userId, orgId)}")
                }

            assertEquals(HttpStatusCode.OK, response.status)
            val body = response.bodyAsText()
            assertTrue(body.contains("\"integrationType\":\"slack\""))
            assertTrue(body.contains("\"enabled\":true"))
        }
    }

    @Test
    fun `slack oauth start returns not found when user is not in an organization`() {
        val userId = seedUser("nomember@test.com")

        testApplication {
            application {
                install(ContentNegotiation) { json() }
                installAuth()
                routing {
                    authenticate("auth-jwt") {
                        route("/v1") {
                            integrationRoutes()
                        }
                    }
                }
            }

            val response =
                client.get("/v1/integrations/slack/oauth/start") {
                    header(HttpHeaders.Authorization, "Bearer ${token(userId)}")
                }

            assertEquals(HttpStatusCode.NotFound, response.status)
            assertTrue(response.bodyAsText().contains("No organization found"))
        }
    }

    @Test
    fun `slack oauth start returns forbidden when plan disables slack`() {
        val orgId = seedOrganization("Slack Disabled Org")
        val userId = seedUser("slack-disabled@test.com")
        seedMembership(orgId, userId, "owner")
        seedDisabledIntegrationTier(orgId)

        testApplication {
            application {
                install(ContentNegotiation) { json() }
                installAuth()
                routing {
                    authenticate("auth-jwt") {
                        route("/v1") {
                            integrationRoutes()
                        }
                    }
                }
            }

            val response =
                client.get("/v1/integrations/slack/oauth/start") {
                    header(HttpHeaders.Authorization, "Bearer ${token(userId, orgId)}")
                }

            assertEquals(HttpStatusCode.Forbidden, response.status)
            assertTrue(response.bodyAsText().contains("Slack integration is not available"))
        }
    }

    @Test
    fun `discord oauth start returns forbidden when plan disables discord`() {
        val orgId = seedOrganization("Discord Disabled Org")
        val userId = seedUser("discord-disabled@test.com")
        seedMembership(orgId, userId, "owner")
        seedDisabledIntegrationTier(orgId)

        testApplication {
            application {
                install(ContentNegotiation) { json() }
                installAuth()
                routing {
                    authenticate("auth-jwt") {
                        route("/v1") {
                            integrationRoutes()
                        }
                    }
                }
            }

            val response =
                client.get("/v1/integrations/discord/oauth/start") {
                    header(HttpHeaders.Authorization, "Bearer ${token(userId, orgId)}")
                }

            assertEquals(HttpStatusCode.Forbidden, response.status)
            assertTrue(response.bodyAsText().contains("Discord integration is not available"))
        }
    }

    @Test
    fun `slack callback requires code parameter`() {
        testApplication {
            application {
                install(ContentNegotiation) { json() }
                installAuth()
                routing {
                    route("/v1") {
                        integrationCallbackRoutes()
                    }
                }
            }

            val response = client.get("/v1/integrations/slack/oauth/callback?state=test")

            assertEquals(HttpStatusCode.BadRequest, response.status)
            assertTrue(response.bodyAsText().contains("Missing code parameter"))
        }
    }

    @Test
    fun `slack callback requires state parameter`() {
        testApplication {
            application {
                install(ContentNegotiation) { json() }
                installAuth()
                routing {
                    route("/v1") {
                        integrationCallbackRoutes()
                    }
                }
            }

            val response = client.get("/v1/integrations/slack/oauth/callback?code=test")

            assertEquals(HttpStatusCode.BadRequest, response.status)
            assertTrue(response.bodyAsText().contains("Missing state parameter"))
        }
    }

    @Test
    fun `slack callback rejects malformed state`() {
        testApplication {
            application {
                install(ContentNegotiation) { json() }
                installAuth()
                routing {
                    route("/v1") {
                        integrationCallbackRoutes()
                    }
                }
            }

            val response = client.get("/v1/integrations/slack/oauth/callback?code=test&state=bad-state")

            assertEquals(HttpStatusCode.BadRequest, response.status)
            assertTrue(response.bodyAsText().contains("Invalid or expired state parameter"))
        }
    }

    @Test
    fun `slack interactions endpoint rejects unsigned payloads`() {
        testApplication {
            application {
                install(ContentNegotiation) { json() }
                installAuth()
                routing {
                    route("/v1") {
                        integrationCallbackRoutes()
                    }
                }
            }

            val response =
                client.post("/v1/integrations/slack/interactions") {
                    contentType(ContentType.Application.FormUrlEncoded)
                    setBody("payload={}")
                }

            assertEquals(HttpStatusCode.Unauthorized, response.status)
            assertTrue(response.bodyAsText().contains("Invalid Slack signature"))
        }
    }

    @Test
    fun `link user endpoint requires authentication`() {
        testApplication {
            application {
                install(ContentNegotiation) { json() }
                installAuth()
                routing {
                    route("/v1") {
                        integrationCallbackRoutes()
                    }
                }
            }

            val response =
                client.post("/v1/integrations/slack/link-user") {
                    contentType(ContentType.Application.Json)
                    setBody("""{"slackUserId":"U1","slackTeamId":"T1"}""")
                }

            assertEquals(HttpStatusCode.Unauthorized, response.status)
        }
    }

    private fun io.ktor.server.application.Application.installAuth() {
        install(Authentication) {
            jwt("auth-jwt") {
                verifier(
                    JWT
                        .require(Algorithm.HMAC256(jwtSecret))
                        .withIssuer("moneat")
                        .withAudience("moneat-users")
                        .build()
                )
                validate { JWTPrincipal(it.payload) }
            }
        }
    }

    private fun token(userId: Int, orgId: Int? = null): String {
        return JWT
            .create()
            .withIssuer("moneat")
            .withAudience("moneat-users")
            .withClaim("userId", userId)
            .apply { if (orgId != null) withClaim("orgId", orgId) }
            .withClaim("email", "user$userId@test.com")
            .sign(Algorithm.HMAC256(jwtSecret))
    }

    private fun seedOrganization(name: String): Int =
        transaction {
            Organizations.insert {
                it[Organizations.name] = name
                it[slug] = name.lowercase().replace(" ", "-")
            } get Organizations.id
        }

    private fun seedUser(email: String): Int =
        transaction {
            Users.insert {
                it[Users.email] = email
                it[password_hash] = "hashed"
                it[Users.name] = email.substringBefore("@")
                it[email_verified] = true
            } get Users.id
        }

    private fun seedMembership(
        orgId: Int,
        userId: Int,
        role: String
    ) = transaction {
        Memberships.insert {
            it[organization_id] = orgId
            it[Memberships.user_id] = userId
            it[Memberships.role] = role
        }
    }

    private fun seedIntegration(
        orgId: Int,
        type: String,
        enabled: Boolean
    ) = transaction {
        OrganizationIntegrations.insert {
            it[organization_id] = orgId
            it[integration_type] = type
            it[access_token] = "access-token"
            it[team_id] = "team-id"
            it[team_name] = "Team Name"
            it[channel_id] = "channel-id"
            it[channel_name] = "channel-name"
            it[OrganizationIntegrations.enabled] = enabled
            it[created_at] = Clock.System.now()
            it[updated_at] = Clock.System.now()
        }
    }

    private fun seedDisabledIntegrationTier(orgId: Int) {
        transaction {
            val tierId = PricingTierConfigs.insert {
                it[tier_name] = "INTEGRATIONS_DISABLED"
                it[monthly_unit_limit] = 1_000
                it[retention_days] = 30
                it[log_retention_days] = 30
                it[max_systems] = 10
                it[monitor_interval_seconds] = 60
                it[monthly_price_cents] = 0
                it[slack_enabled] = false
                it[discord_enabled] = false
            } get PricingTierConfigs.id
            Subscriptions.insert {
                it[organization_id] = orgId
                it[plan] = "INTEGRATIONS_DISABLED"
                it[status] = "active"
                it[pricing_tier_config_id] = tierId
            }
        }
    }

    @AfterTest
    fun teardownKoin() {
        stopTestKoin()
    }
}
