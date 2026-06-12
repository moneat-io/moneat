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

package com.moneat.incident

import com.moneat.incident.models.IncidentEventLog
import com.moneat.incident.models.IncidentProviderConfigs
import com.moneat.incident.models.IncidentRoutingRules
import com.moneat.incident.routes.incidentProviderRoutes
import com.moneat.shared.models.Memberships
import com.moneat.shared.models.Organizations
import com.moneat.shared.models.Users
import com.moneat.testsupport.RouteTestSupport
import com.moneat.testsupport.RouteTestSupport.installJwtAuth
import com.moneat.testsupport.RouteTestSupport.withAuth
import com.moneat.testsupport.TestDatabaseHelper
import io.ktor.client.request.delete
import io.ktor.client.request.get
import io.ktor.client.request.post
import io.ktor.client.request.put
import io.ktor.client.request.setBody
import io.ktor.client.statement.bodyAsText
import io.ktor.http.ContentType
import io.ktor.http.HttpStatusCode
import io.ktor.http.contentType
import io.ktor.server.routing.routing
import io.ktor.server.testing.testApplication
import org.jetbrains.exposed.v1.jdbc.Database
import org.jetbrains.exposed.v1.jdbc.insert
import org.jetbrains.exposed.v1.jdbc.transactions.TransactionManager
import org.jetbrains.exposed.v1.jdbc.transactions.transaction
import kotlin.test.BeforeTest
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertTrue
import kotlin.time.Clock

class IncidentRoutesTest {

    companion object {
        private const val USER_ID = 1
        private const val ORG_ID = 1
        private const val NO_MEMBERSHIP_USER_ID = 99
        private const val MISSING_PROVIDER_CONFIG_ID = "11111111-1111-4111-8111-111111111111"
    }

    private var db: Database? = null

    @BeforeTest
    fun setup() {
        if (db == null) {
            db = Database.connect(
                url = "jdbc:h2:mem:moneat_incident_routes;DATABASE_TO_LOWER=TRUE;DB_CLOSE_DELAY=-1",
                driver = "org.h2.Driver"
            )
        }
        TransactionManager.defaultDatabase = db
        TestDatabaseHelper.resetSchema(
            Users,
            Organizations,
            Memberships,
            IncidentProviderConfigs,
            IncidentRoutingRules,
            IncidentEventLog
        )
        transaction {
            Users.insert {
                it[id] = USER_ID
                it[email] = "test@moneat.io"
                it[password_hash] = "hashed"
                it[name] = "Test User"
            }
            Organizations.insert {
                it[id] = ORG_ID
                it[name] = "Test Org"
                it[slug] = "test-org"
            }
            Memberships.insert {
                it[user_id] = USER_ID
                it[organization_id] = ORG_ID
                it[role] = "admin"
            }
        }
    }

    private fun installRoutes(): io.ktor.server.testing.ApplicationTestBuilder.() -> Unit = {
        application {
            installJwtAuth()
            routing { incidentProviderRoutes() }
        }
    }

    // ──── Unauthenticated Requests Return 401 ────

    @Test
    fun `GET incident providers returns 401 without jwt`() = testApplication {
        installRoutes()()
        val response = client.get("/api/incident-providers")
        assertEquals(HttpStatusCode.Unauthorized, response.status)
    }

    @Test
    fun `POST incident provider returns 401 without jwt`() = testApplication {
        installRoutes()()
        val response = client.post("/api/incident-providers") {
            contentType(ContentType.Application.Json)
            setBody("""{"providerType":"pagerduty","name":"PD","apiKey":"k","configJson":{}}""")
        }
        assertEquals(HttpStatusCode.Unauthorized, response.status)
    }

    @Test
    fun `PUT incident provider returns 401 without jwt`() = testApplication {
        installRoutes()()
        val response = client.put("/api/incident-providers/1") {
            contentType(ContentType.Application.Json)
            setBody("""{"name":"Updated"}""")
        }
        assertEquals(HttpStatusCode.Unauthorized, response.status)
    }

    @Test
    fun `DELETE incident provider returns 401 without jwt`() = testApplication {
        installRoutes()()
        val response = client.delete("/api/incident-providers/1")
        assertEquals(HttpStatusCode.Unauthorized, response.status)
    }

    @Test
    fun `POST test connection returns 401 without jwt`() = testApplication {
        installRoutes()()
        val response = client.post("/api/incident-providers/1/test")
        assertEquals(HttpStatusCode.Unauthorized, response.status)
    }

    @Test
    fun `GET routing rules returns 401 without jwt`() = testApplication {
        installRoutes()()
        val response = client.get("/api/incident-providers/1/rules")
        assertEquals(HttpStatusCode.Unauthorized, response.status)
    }

    @Test
    fun `PUT routing rules returns 401 without jwt`() = testApplication {
        installRoutes()()
        val response = client.put("/api/incident-providers/1/rules") {
            contentType(ContentType.Application.Json)
            setBody("[]")
        }
        assertEquals(HttpStatusCode.Unauthorized, response.status)
    }

    @Test
    fun `GET event log returns 401 without jwt`() = testApplication {
        installRoutes()()
        val response = client.get("/api/incident-providers/1/events")
        assertEquals(HttpStatusCode.Unauthorized, response.status)
    }

    // ──── Non-Numeric ID Returns 400 ────

    @Test
    fun `PUT with non-numeric id returns 400`() = testApplication {
        installRoutes()()
        val token = RouteTestSupport.createToken(userId = USER_ID, orgId = ORG_ID)
        val response = client.put("/api/incident-providers/abc") {
            withAuth(token)
            contentType(ContentType.Application.Json)
            setBody("""{"name":"Updated"}""")
        }
        assertEquals(HttpStatusCode.BadRequest, response.status)
    }

    @Test
    fun `DELETE with non-numeric id returns 400`() = testApplication {
        installRoutes()()
        val token = RouteTestSupport.createToken(userId = USER_ID, orgId = ORG_ID)
        val response = client.delete("/api/incident-providers/abc") {
            withAuth(token)
        }
        assertEquals(HttpStatusCode.BadRequest, response.status)
    }

    @Test
    fun `GET rules with non-numeric id returns 400`() = testApplication {
        installRoutes()()
        val token = RouteTestSupport.createToken(userId = USER_ID, orgId = ORG_ID)
        val response = client.get("/api/incident-providers/xyz/rules") {
            withAuth(token)
        }
        assertEquals(HttpStatusCode.BadRequest, response.status)
    }

    @Test
    fun `PUT rules with non-numeric id returns 400`() = testApplication {
        installRoutes()()
        val token = RouteTestSupport.createToken(userId = USER_ID, orgId = ORG_ID)
        val response = client.put("/api/incident-providers/xyz/rules") {
            withAuth(token)
            contentType(ContentType.Application.Json)
            setBody("[]")
        }
        assertEquals(HttpStatusCode.BadRequest, response.status)
    }

    @Test
    fun `GET events with non-numeric id returns 400`() = testApplication {
        installRoutes()()
        val token = RouteTestSupport.createToken(userId = USER_ID, orgId = ORG_ID)
        val response = client.get("/api/incident-providers/abc/events") {
            withAuth(token)
        }
        assertEquals(HttpStatusCode.BadRequest, response.status)
    }

    @Test
    fun `POST test with non-numeric id returns 400`() = testApplication {
        installRoutes()()
        val token = RouteTestSupport.createToken(userId = USER_ID, orgId = ORG_ID)
        val response = client.post("/api/incident-providers/abc/test") {
            withAuth(token)
        }
        assertEquals(HttpStatusCode.BadRequest, response.status)
    }

    // ──── Authenticated With Membership ────

    @Test
    fun `GET providers returns empty list when no configs exist`() = testApplication {
        installRoutes()()
        val token = RouteTestSupport.createToken(userId = USER_ID, orgId = ORG_ID)
        val response = client.get("/api/incident-providers") {
            withAuth(token)
        }
        assertEquals(HttpStatusCode.OK, response.status)
        assertEquals("[]", response.bodyAsText().trim())
    }

    @Test
    fun `DELETE returns 404 for non-existent provider config`() = testApplication {
        installRoutes()()
        val token = RouteTestSupport.createToken(userId = USER_ID, orgId = ORG_ID)
        val response = client.delete("/api/incident-providers/$MISSING_PROVIDER_CONFIG_ID") {
            withAuth(token)
        }
        assertEquals(HttpStatusCode.NotFound, response.status)
    }

    @Test
    fun `POST test returns 404 for non-existent config`() = testApplication {
        installRoutes()()
        val token = RouteTestSupport.createToken(userId = USER_ID, orgId = ORG_ID)
        val response = client.post("/api/incident-providers/$MISSING_PROVIDER_CONFIG_ID/test") {
            withAuth(token)
        }
        assertEquals(HttpStatusCode.NotFound, response.status)
    }

    @Test
    fun `GET rules returns 404 for non-existent config`() = testApplication {
        installRoutes()()
        val token = RouteTestSupport.createToken(userId = USER_ID, orgId = ORG_ID)
        val response = client.get("/api/incident-providers/$MISSING_PROVIDER_CONFIG_ID/rules") {
            withAuth(token)
        }
        assertEquals(HttpStatusCode.NotFound, response.status)
    }

    @Test
    fun `PUT rules returns 404 for non-existent config`() = testApplication {
        installRoutes()()
        val token = RouteTestSupport.createToken(userId = USER_ID, orgId = ORG_ID)
        val response = client.put("/api/incident-providers/$MISSING_PROVIDER_CONFIG_ID/rules") {
            withAuth(token)
            contentType(ContentType.Application.Json)
            setBody("[]")
        }
        assertEquals(HttpStatusCode.NotFound, response.status)
    }

    @Test
    fun `GET events returns 404 for non-existent config`() = testApplication {
        installRoutes()()
        val token = RouteTestSupport.createToken(userId = USER_ID, orgId = ORG_ID)
        val response = client.get("/api/incident-providers/$MISSING_PROVIDER_CONFIG_ID/events") {
            withAuth(token)
        }
        assertEquals(HttpStatusCode.NotFound, response.status)
    }

    // ──── Authenticated With Provider Config ────

    private fun insertProviderConfig(): String {
        return transaction {
            val now = Clock.System.now()
            val row = IncidentProviderConfigs.insert {
                it[organizationId] = ORG_ID
                it[providerType] = "pagerduty"
                it[name] = "Test PD"
                it[apiKey] = "test-api-key"
                it[configJson] = """{"routing_key":"test"}"""
                it[enabled] = true
                it[createdAt] = now
                it[updatedAt] = now
            }
            row[IncidentProviderConfigs.resourceId].toString()
        }
    }

    @Test
    fun `GET providers returns configs for authenticated user`() = testApplication {
        installRoutes()()
        insertProviderConfig()
        val token = RouteTestSupport.createToken(userId = USER_ID, orgId = ORG_ID)
        val response = client.get("/api/incident-providers") {
            withAuth(token)
        }
        assertEquals(HttpStatusCode.OK, response.status)
        val body = response.bodyAsText()
        assertTrue(body.contains("pagerduty"))
        assertTrue(body.contains("Test PD"))
    }

    @Test
    fun `DELETE removes existing provider config`() = testApplication {
        installRoutes()()
        val configId = insertProviderConfig()
        val token = RouteTestSupport.createToken(userId = USER_ID, orgId = ORG_ID)
        val response = client.delete("/api/incident-providers/$configId") {
            withAuth(token)
        }
        assertEquals(HttpStatusCode.OK, response.status)
    }

    @Test
    fun `GET rules returns empty list for config with no rules`() = testApplication {
        installRoutes()()
        val configId = insertProviderConfig()
        val token = RouteTestSupport.createToken(userId = USER_ID, orgId = ORG_ID)
        val response = client.get("/api/incident-providers/$configId/rules") {
            withAuth(token)
        }
        assertEquals(HttpStatusCode.OK, response.status)
        assertEquals("[]", response.bodyAsText().trim())
    }

    @Test
    fun `PUT rules upserts routing rules for config`() = testApplication {
        installRoutes()()
        val configId = insertProviderConfig()
        val token = RouteTestSupport.createToken(userId = USER_ID, orgId = ORG_ID)
        val rulesBody =
            """[{"alertSource":"monitor","alertType":"metric","alertPriority":"critical"}]"""
        val response = client.put("/api/incident-providers/$configId/rules") {
            withAuth(token)
            contentType(ContentType.Application.Json)
            setBody(rulesBody)
        }
        assertEquals(HttpStatusCode.OK, response.status)
    }

    @Test
    fun `GET events returns empty list for config with no events`() = testApplication {
        installRoutes()()
        val configId = insertProviderConfig()
        val token = RouteTestSupport.createToken(userId = USER_ID, orgId = ORG_ID)
        val response = client.get("/api/incident-providers/$configId/events") {
            withAuth(token)
        }
        assertEquals(HttpStatusCode.OK, response.status)
        assertEquals("[]", response.bodyAsText().trim())
    }

    @Test
    fun `GET events with custom limit returns empty list`() = testApplication {
        installRoutes()()
        val configId = insertProviderConfig()
        val token = RouteTestSupport.createToken(userId = USER_ID, orgId = ORG_ID)
        val response = client.get("/api/incident-providers/$configId/events?limit=10") {
            withAuth(token)
        }
        assertEquals(HttpStatusCode.OK, response.status)
    }

    // ──── No current org claim returns 401 ────

    @Test
    fun `GET providers returns 401 without current org`() = testApplication {
        installRoutes()()
        val token = RouteTestSupport.createToken(userId = NO_MEMBERSHIP_USER_ID, orgId = null)
        val response = client.get("/api/incident-providers") {
            withAuth(token)
        }
        assertEquals(HttpStatusCode.Unauthorized, response.status)
    }

    @Test
    fun `POST test returns 401 without current org`() = testApplication {
        installRoutes()()
        val token = RouteTestSupport.createToken(userId = NO_MEMBERSHIP_USER_ID, orgId = null)
        val response = client.post("/api/incident-providers/1/test") {
            withAuth(token)
        }
        assertEquals(HttpStatusCode.Unauthorized, response.status)
    }

    @Test
    fun `GET rules returns 401 without current org`() = testApplication {
        installRoutes()()
        val token = RouteTestSupport.createToken(userId = NO_MEMBERSHIP_USER_ID, orgId = null)
        val response = client.get("/api/incident-providers/1/rules") {
            withAuth(token)
        }
        assertEquals(HttpStatusCode.Unauthorized, response.status)
    }

    @Test
    fun `PUT rules returns 401 without current org`() = testApplication {
        installRoutes()()
        val token = RouteTestSupport.createToken(userId = NO_MEMBERSHIP_USER_ID, orgId = null)
        val response = client.put("/api/incident-providers/1/rules") {
            withAuth(token)
            contentType(ContentType.Application.Json)
            setBody("[]")
        }
        assertEquals(HttpStatusCode.Unauthorized, response.status)
    }

    @Test
    fun `GET events returns 401 without current org`() = testApplication {
        installRoutes()()
        val token = RouteTestSupport.createToken(userId = NO_MEMBERSHIP_USER_ID, orgId = null)
        val response = client.get("/api/incident-providers/1/events") {
            withAuth(token)
        }
        assertEquals(HttpStatusCode.Unauthorized, response.status)
    }

    @Test
    fun `DELETE returns 401 without current org`() = testApplication {
        installRoutes()()
        val token = RouteTestSupport.createToken(userId = NO_MEMBERSHIP_USER_ID, orgId = null)
        val response = client.delete("/api/incident-providers/1") {
            withAuth(token)
        }
        assertEquals(HttpStatusCode.Unauthorized, response.status)
    }
}
