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

package com.moneat.security.signals

import com.auth0.jwt.JWT
import com.auth0.jwt.algorithms.Algorithm
import com.moneat.shared.models.Organizations
import com.moneat.testsupport.RouteTestSupport.TEST_JWT_SECRET
import com.moneat.testsupport.RouteTestSupport.createToken
import com.moneat.testsupport.RouteTestSupport.installJwtAuth
import com.moneat.testsupport.RouteTestSupport.withAuth
import io.ktor.client.request.get
import io.ktor.client.request.patch
import io.ktor.client.request.setBody
import io.ktor.client.statement.bodyAsText
import io.ktor.http.ContentType
import io.ktor.http.HttpStatusCode
import io.ktor.http.contentType
import io.ktor.server.routing.routing
import io.ktor.server.testing.ApplicationTestBuilder
import io.ktor.server.testing.testApplication
import kotlinx.serialization.json.Json
import org.jetbrains.exposed.v1.core.eq
import org.jetbrains.exposed.v1.jdbc.Database
import org.jetbrains.exposed.v1.jdbc.insert
import org.jetbrains.exposed.v1.jdbc.selectAll
import org.jetbrains.exposed.v1.jdbc.transactions.TransactionManager
import org.jetbrains.exposed.v1.jdbc.transactions.transaction
import kotlin.test.BeforeTest
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertTrue

class SignalRoutesTest {
    companion object {
        private var db: Database? = null
        private const val USER_ID = 11
    }

    private val json = Json {
        ignoreUnknownKeys = true
        encodeDefaults = true
    }
    private var orgId: Int = 0
    private var otherOrgId: Int = 0

    @BeforeTest
    fun setup() {
        if (db == null) {
            db = Database.connect(
                url = "jdbc:h2:mem:moneat_signal_routes;DATABASE_TO_LOWER=TRUE;DB_CLOSE_DELAY=-1",
                driver = "org.h2.Driver"
            )
        }
        TransactionManager.defaultDatabase = db
        SignalSchemaTestSupport.reset()
        orgId = seedOrg("acme")
        otherOrgId = seedOrg("globex")
    }

    @Test
    fun `list requires authentication`() = testApplication {
        setupApp()
        assertEquals(HttpStatusCode.Unauthorized, client.get("/v1/security/signals").status)
    }

    @Test
    fun `list returns the calling org's signals`() = testApplication {
        setupApp()
        SignalWriter.upsert(orgId, spec("a"))
        SignalWriter.upsert(otherOrgId, spec("b"))

        val response = client.get("/v1/security/signals") { withAuth(token(orgId)) }

        assertEquals(HttpStatusCode.OK, response.status)
        val body = response.bodyAsText()
        assertTrue(body.contains("\"rule_id\":\"a\""))
        assertTrue(!body.contains("\"rule_id\":\"b\""))
    }

    @Test
    fun `detail returns not found for another org's signal`() = testApplication {
        setupApp()
        val created = SignalWriter.upsert(otherOrgId, spec("a"))

        val response = client.get("/v1/security/signals/${signalResourceId(created.signalId)}") {
            withAuth(token(orgId))
        }

        assertEquals(HttpStatusCode.NotFound, response.status)
    }

    @Test
    fun `patch triages a signal owned by the caller`() = testApplication {
        setupApp()
        val created = SignalWriter.upsert(orgId, spec("a"))

        val response = client.patch("/v1/security/signals/${signalResourceId(created.signalId)}") {
            withAuth(token(orgId))
            contentType(ContentType.Application.Json)
            setBody(json.encodeToString(TriageRequest(status = "under_review")))
        }

        assertEquals(HttpStatusCode.OK, response.status)
        assertTrue(response.bodyAsText().contains("\"status\":\"under_review\""))
    }

    @Test
    fun `patch cannot triage another org's signal`() = testApplication {
        setupApp()
        val created = SignalWriter.upsert(otherOrgId, spec("a"))

        val response = client.patch("/v1/security/signals/${signalResourceId(created.signalId)}") {
            withAuth(token(orgId))
            contentType(ContentType.Application.Json)
            setBody(json.encodeToString(TriageRequest(status = "under_review")))
        }

        assertEquals(HttpStatusCode.NotFound, response.status)
    }

    @Test
    fun `patch rejects an invalid transition`() = testApplication {
        setupApp()
        val created = SignalWriter.upsert(orgId, spec("a"))

        val response = client.patch("/v1/security/signals/${signalResourceId(created.signalId)}") {
            withAuth(token(orgId))
            contentType(ContentType.Application.Json)
            setBody(json.encodeToString(TriageRequest(status = "archived")))
        }

        assertEquals(HttpStatusCode.BadRequest, response.status)
    }

    @Test
    fun `patch requires a user claim for audit attribution`() = testApplication {
        setupApp()
        val created = SignalWriter.upsert(orgId, spec("a"))

        val response = client.patch("/v1/security/signals/${signalResourceId(created.signalId)}") {
            withAuth(orgOnlyToken(orgId))
            contentType(ContentType.Application.Json)
            setBody(json.encodeToString(TriageRequest(status = "under_review")))
        }

        assertEquals(HttpStatusCode.Forbidden, response.status)
    }

    private fun ApplicationTestBuilder.setupApp() {
        application {
            installJwtAuth()
            routing { signalRoutes() }
        }
    }

    private fun token(org: Int): String = createToken(userId = USER_ID, orgId = org)

    private fun orgOnlyToken(org: Int): String =
        JWT.create()
            .withIssuer("moneat")
            .withAudience("moneat-users")
            .withClaim("orgId", org)
            .sign(Algorithm.HMAC256(TEST_JWT_SECRET))

    private fun spec(ruleId: String) = SignalSpec(
        source = SignalSource.AGENT_RUNTIME,
        ruleId = ruleId,
        ruleName = "Rule $ruleId",
        severity = SignalSeverity.HIGH,
        dedupKey = "$ruleId|host|proc",
        entities = mapOf("host" to "web-01"),
        evidenceType = "clickhouse_query",
        evidenceReference = "table=security_events dedup=$ruleId",
        occurredAtMs = 1_700_000_000_000
    )

    private fun signalResourceId(signalId: Int): String =
        transaction {
            SecuritySignals
                .selectAll()
                .where { SecuritySignals.id eq signalId }
                .single()[SecuritySignals.resourceId]
                .toString()
        }

    private fun seedOrg(name: String): Int =
        transaction {
            Organizations.insert {
                it[Organizations.name] = name
                it[slug] = name
            } get Organizations.id
        }
}
