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
import com.moneat.shared.models.OrganizationIntegrations
import com.moneat.shared.models.Organizations
import com.moneat.testsupport.TestDatabaseHelper
import io.ktor.client.request.get
import io.ktor.client.request.header
import io.ktor.client.statement.bodyAsText
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
        TestDatabaseHelper.resetSchema(Organizations, OrganizationIntegrations)
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

    private fun token(): String =
        JWT.create()
            .withClaim("userId", 1)
            .withClaim("orgId", ORGANIZATION_ID)
            .sign(Algorithm.HMAC256(jwtSecret))

    private fun io.ktor.server.application.Application.installAuth() {
        install(Authentication) {
            jwt("auth-jwt") {
                verifier(JWT.require(Algorithm.HMAC256(jwtSecret)).build())
                validate { credential -> JWTPrincipal(credential.payload) }
            }
        }
    }
}
