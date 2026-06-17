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

package com.moneat.connectors.routes

import com.auth0.jwt.JWT
import com.auth0.jwt.algorithms.Algorithm
import com.moneat.connectors.ConnectorH2Schema
import com.moneat.connectors.ConnectorInstallations
import com.moneat.shared.models.OrganizationIntegrations
import com.moneat.shared.models.Organizations
import com.moneat.shared.models.Users
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
import io.ktor.server.auth.jwt.JWTPrincipal
import io.ktor.server.auth.jwt.jwt
import io.ktor.server.plugins.contentnegotiation.ContentNegotiation
import io.ktor.server.routing.routing
import io.ktor.server.testing.testApplication
import org.jetbrains.exposed.v1.jdbc.Database
import org.jetbrains.exposed.v1.jdbc.insert
import org.jetbrains.exposed.v1.jdbc.transactions.TransactionManager
import org.jetbrains.exposed.v1.jdbc.transactions.transaction
import java.security.SecureRandom
import java.util.Base64
import kotlin.test.BeforeTest
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertTrue
import kotlin.time.Clock

class ConnectorRoutesTest {
    private val jwtSecret =
        ByteArray(JWT_SECRET_BYTES)
            .also { secureRandom.nextBytes(it) }
            .let { bytes -> Base64.getEncoder().encodeToString(bytes) }

    companion object {
        private var db: Database? = null
        private const val ORGANIZATION_ID = 2
        private const val JWT_SECRET_BYTES = 32
        private val secureRandom = SecureRandom()
    }

    @BeforeTest
    fun setupDatabase() {
        if (db == null) {
            db = Database.connect(
                url = "jdbc:h2:mem:moneat_connector_routes;MODE=MYSQL;DATABASE_TO_LOWER=TRUE;DB_CLOSE_DELAY=-1",
                driver = "org.h2.Driver",
            )
        }
        TransactionManager.defaultDatabase = db
        ConnectorH2Schema.reset(
            Users,
            Organizations,
            OrganizationIntegrations,
        )
    }

    @Test
    fun `providers endpoint returns connector catalog`() {
        testApplication {
            application {
                install(ContentNegotiation) { json() }
                installAuth()
                routing {
                    connectorRoutes()
                }
            }

            val response =
                client.get("/v1/connectors/providers") {
                    header(HttpHeaders.Authorization, "Bearer ${token()}")
                }

            assertEquals(HttpStatusCode.OK, response.status)
            val body = response.bodyAsText()
            assertTrue(body.contains("\"id\":\"slack\""))
            assertTrue(body.contains("\"id\":\"repository_import\""))
            assertTrue(body.contains("\"id\":\"workflow_actions\""))
            assertTrue(body.contains("\"authProfiles\""))
            assertTrue(body.contains("\"allowedAuthProfileIds\""))
            assertTrue(body.contains("\"id\":\"read_app_installation\""))
            assertTrue(body.contains("\"id\":\"workflow_app_installation\""))
            assertTrue(body.contains("\"id\":\"revenuecat\""))
            assertTrue(body.contains("\"family\":\"data_import\""))
        }
    }

    @Test
    fun `state endpoint reports available legacy integrations and gated planned uses`() {
        val integrationId =
            transaction {
                Organizations.insert {
                    it[id] = ORGANIZATION_ID
                    it[name] = "Connector Org"
                    it[slug] = "connector-org"
                }
                OrganizationIntegrations.insert {
                    it[organization_id] = ORGANIZATION_ID
                    it[integration_type] = "slack"
                    it[access_token] = "xoxb-test"
                    it[team_name] = "Moneat"
                    it[channel_name] = "#alerts"
                    it[enabled] = true
                    it[created_at] = Clock.System.now()
                    it[updated_at] = Clock.System.now()
                }[OrganizationIntegrations.resource_id].toString()
            }

        testApplication {
            application {
                install(ContentNegotiation) { json() }
                installAuth()
                routing {
                    connectorRoutes()
                }
            }

            val response =
                client.get("/v1/connectors/state") {
                    header(HttpHeaders.Authorization, "Bearer ${token()}")
                }

            assertEquals(HttpStatusCode.OK, response.status)
            val body = response.bodyAsText()
            assertTrue(body.contains("\"connections\""))
            assertTrue(body.contains("\"providerId\":\"slack\""))
            assertTrue(body.contains("\"state\":\"connected\""))
            assertTrue(body.contains("\"health\":\"healthy\""))
            assertTrue(body.contains("\"integrationId\":\"$integrationId\""))
            assertTrue(body.contains("\"providerId\":\"github\""))
            assertTrue(body.contains("\"state\":\"planned\""))
            assertTrue(body.contains("Coming soon"))
        }
    }

    @Test
    fun `state endpoint reports RevenueCat connector installation detail`() {
        val installationId =
            transaction {
                Organizations.insert {
                    it[id] = ORGANIZATION_ID
                    it[name] = "Connector Org"
                    it[slug] = "connector-org"
                }
                ConnectorInstallations.insert {
                    it[organizationId] = ORGANIZATION_ID
                    it[provider] = "revenuecat"
                    it[name] = "Bandapella RevenueCat"
                    it[credentialType] = "api_key"
                    it[authProfileId] = "project_api_key"
                    it[externalProjectId] = "proj_123"
                    it[externalProjectName] = "Bandapella"
                    it[status] = "healthy"
                    it[apiSecretLastFour] = "1234"
                    it[enabled] = true
                    it[createdAt] = Clock.System.now()
                    it[updatedAt] = Clock.System.now()
                }[ConnectorInstallations.resourceId].toString()
            }

        testApplication {
            application {
                install(ContentNegotiation) { json() }
                installAuth()
                routing {
                    connectorRoutes()
                }
            }

            val response =
                client.get("/v1/connectors/state") {
                    header(HttpHeaders.Authorization, "Bearer ${token()}")
                }

            assertEquals(HttpStatusCode.OK, response.status)
            val body = response.bodyAsText()
            assertTrue(body.contains("\"providerId\":\"revenuecat\""))
            assertTrue(body.contains("\"state\":\"connected\""))
            assertTrue(body.contains("\"integrationId\":\"$installationId\""))
            assertTrue(body.contains("Connect RevenueCat apps to Moneat projects"))
            assertTrue(body.contains("\"status\":\"awaiting_traffic\""))
        }
    }

    @Test
    fun `state endpoint reports Google Ads connector installation detail`() {
        val installationId =
            transaction {
                Organizations.insert {
                    it[id] = ORGANIZATION_ID
                    it[name] = "Connector Org"
                    it[slug] = "connector-org"
                }
                ConnectorInstallations.insert {
                    it[organizationId] = ORGANIZATION_ID
                    it[provider] = "google_ads"
                    it[name] = "Bandapella Google Ads"
                    it[credentialType] = "oauth_refresh_token"
                    it[authProfileId] = "manager_oauth"
                    it[externalProjectId] = "1234567890"
                    it[externalProjectName] = "Bandapella Google Ads"
                    it[status] = "healthy"
                    it[apiSecretLastFour] = "3456"
                    it[enabled] = true
                    it[createdAt] = Clock.System.now()
                    it[updatedAt] = Clock.System.now()
                }[ConnectorInstallations.resourceId].toString()
            }

        testApplication {
            application {
                install(ContentNegotiation) { json() }
                installAuth()
                routing {
                    connectorRoutes()
                }
            }

            val response =
                client.get("/v1/connectors/state") {
                    header(HttpHeaders.Authorization, "Bearer ${token()}")
                }

            assertEquals(HttpStatusCode.OK, response.status)
            val body = response.bodyAsText()
            assertTrue(body.contains("\"providerId\":\"google_ads\""))
            assertTrue(body.contains("\"state\":\"connected\""))
            assertTrue(body.contains("\"integrationId\":\"$installationId\""))
            assertTrue(body.contains("Connected, no Google Ads accounts discovered yet"))
            assertTrue(body.contains("\"status\":\"healthy\""))
        }
    }

    @Test
    fun `installation writes require organization admin role`() {
        testApplication {
            application {
                install(ContentNegotiation) { json() }
                installAuth()
                routing {
                    connectorRoutes()
                }
            }

            val response =
                client.post("/v1/connectors/installations") {
                    header(HttpHeaders.Authorization, "Bearer ${token(orgRole = "viewer")}")
                    contentType(ContentType.Application.Json)
                    setBody("{}")
                }

            assertEquals(HttpStatusCode.Forbidden, response.status)
            assertTrue(response.bodyAsText().contains("Only organization owners and admins can manage connectors"))
        }
    }

    private fun token(orgRole: String? = null): String {
        val builder = JWT.create()
            .withClaim("userId", 1)
            .withClaim("orgId", ORGANIZATION_ID)
        if (orgRole != null) {
            builder.withClaim("orgRole", orgRole)
        }
        return builder.sign(Algorithm.HMAC256(jwtSecret))
    }

    private fun io.ktor.server.application.Application.installAuth() {
        install(Authentication) {
            jwt("auth-jwt") {
                verifier(JWT.require(Algorithm.HMAC256(jwtSecret)).build())
                validate { credential -> JWTPrincipal(credential.payload) }
            }
        }
    }
}
