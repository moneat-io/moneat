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

package com.moneat.security

import com.moneat.config.ClickHouseClient
import com.moneat.security.routes.securityRoutes
import com.moneat.shared.models.Memberships
import com.moneat.shared.models.Organizations
import com.moneat.shared.models.Users
import com.moneat.testsupport.RouteTestSupport
import com.moneat.testsupport.RouteTestSupport.installJwtAuth
import com.moneat.testsupport.RouteTestSupport.withAuth
import com.moneat.testsupport.TestDatabaseHelper
import com.moneat.testsupport.startTestKoin
import com.moneat.testsupport.stopTestKoin
import io.ktor.client.request.get
import io.ktor.client.statement.bodyAsText
import io.ktor.client.statement.HttpResponse
import io.ktor.http.HttpStatusCode
import io.ktor.server.routing.routing
import io.ktor.server.testing.testApplication
import io.mockk.coEvery
import io.mockk.every
import io.mockk.mockk
import io.mockk.mockkObject
import io.mockk.mockkStatic
import io.mockk.unmockkObject
import io.mockk.unmockkStatic
import org.jetbrains.exposed.v1.jdbc.Database
import org.jetbrains.exposed.v1.jdbc.insert
import org.jetbrains.exposed.v1.jdbc.transactions.TransactionManager
import org.jetbrains.exposed.v1.jdbc.transactions.transaction
import kotlin.test.AfterTest
import kotlin.test.BeforeTest
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertTrue

class SecurityRoutesTest {
    companion object {
        private const val EVENTS_PATH = "/v1/security/events"
        private const val COMPLIANCE_PATH = "/v1/security/compliance"
        private var db: Database? = null
    }

    @BeforeTest
    fun setupKoin() {
        startTestKoin()
        if (db == null) {
            db = Database.connect(
                url = "jdbc:h2:mem:moneat_security_routes;" +
                    "DATABASE_TO_LOWER=TRUE;DB_CLOSE_DELAY=-1",
                driver = "org.h2.Driver"
            )
        }
        TransactionManager.defaultDatabase = db
        TestDatabaseHelper.resetSchema(Users, Organizations, Memberships)
        mockkObject(ClickHouseClient)
        mockkStatic(HttpResponse::bodyAsText)
    }

    @AfterTest
    fun teardownKoin() {
        unmockkObject(ClickHouseClient)
        unmockkStatic(HttpResponse::bodyAsText)
        stopTestKoin()
    }

    private fun token(userId: Int): String = RouteTestSupport.createToken(userId)

    private fun seedUser(): Int = transaction {
        Users.insert {
            it[email] = "security-routes-${System.nanoTime()}@test.com"
            it[password_hash] = "hash"
            it[email_verified] = true
        } get Users.id
    }

    private fun seedUserAndOrg(): Pair<Int, Int> {
        val orgId = transaction {
            Organizations.insert {
                it[name] = "Security Routes Org"
                it[slug] = "sec-org-${System.nanoTime()}"
            } get Organizations.id
        }
        val userId = transaction {
            Users.insert {
                it[email] = "security-routes-m-${System.nanoTime()}@test.com"
                it[password_hash] = "hash"
                it[email_verified] = true
            } get Users.id
        }
        transaction {
            Memberships.insert {
                it[user_id] = userId
                it[organization_id] = orgId
                it[role] = "owner"
            }
        }
        return Pair(userId, orgId)
    }

    private fun stubClickHouseForSecurityRoutes() {
        every { ClickHouseClient.getDatabase() } returns "testdb"
        coEvery { ClickHouseClient.execute(any(), any()) } coAnswers {
            val sql = args[0] as String
            val mockResponse = mockk<HttpResponse>()
            every { mockResponse.status } returns HttpStatusCode.OK
            coEvery { mockResponse.bodyAsText(any()) } returns when {
                "count()" in sql -> "0"
                else -> ""
            }
            mockResponse
        }
    }

    @Test
    fun `security events returns 401 without auth`() = testApplication {
        application {
            with(RouteTestSupport) { installJwtAuth() }
            routing { securityRoutes() }
        }
        val response = client.get(EVENTS_PATH)
        assertEquals(HttpStatusCode.Unauthorized, response.status)
    }

    @Test
    fun `compliance returns 401 without auth`() = testApplication {
        application {
            with(RouteTestSupport) { installJwtAuth() }
            routing { securityRoutes() }
        }
        val response = client.get(COMPLIANCE_PATH)
        assertEquals(HttpStatusCode.Unauthorized, response.status)
    }

    @Test
    fun `security events with jwt but no org membership returns empty payload`() =
        testApplication {
            val userId = seedUser()
            application {
                with(RouteTestSupport) { installJwtAuth() }
                routing { securityRoutes() }
            }
            val response = client.get(EVENTS_PATH) {
                withAuth(token(userId))
            }
            assertEquals(HttpStatusCode.OK, response.status)
            assertEquals("""{"events":[],"totalCount":0}""", response.bodyAsText())
        }

    @Test
    fun `compliance with jwt but no org membership returns empty payload`() =
        testApplication {
            val userId = seedUser()
            application {
                with(RouteTestSupport) { installJwtAuth() }
                routing { securityRoutes() }
            }
            val response = client.get(COMPLIANCE_PATH) {
                withAuth(token(userId))
            }
            assertEquals(HttpStatusCode.OK, response.status)
            assertEquals("""{"findings":[],"totalCount":0}""", response.bodyAsText())
        }

    @Test
    fun `security events with membership returns 200 when clickhouse succeeds`() =
        testApplication {
            val (userId, _) = seedUserAndOrg()
            stubClickHouseForSecurityRoutes()
            application {
                with(RouteTestSupport) { installJwtAuth() }
                routing { securityRoutes() }
            }
            val response = client.get(EVENTS_PATH) {
                withAuth(token(userId))
            }
            assertEquals(HttpStatusCode.OK, response.status)
            assertTrue(response.bodyAsText().contains("\"events\""))
            assertTrue(response.bodyAsText().contains("\"totalCount\""))
        }

    @Test
    fun `compliance with membership returns 200 when clickhouse succeeds`() =
        testApplication {
            val (userId, _) = seedUserAndOrg()
            stubClickHouseForSecurityRoutes()
            application {
                with(RouteTestSupport) { installJwtAuth() }
                routing { securityRoutes() }
            }
            val response = client.get(COMPLIANCE_PATH) {
                withAuth(token(userId))
            }
            assertEquals(HttpStatusCode.OK, response.status)
            assertTrue(response.bodyAsText().contains("\"findings\""))
            assertTrue(response.bodyAsText().contains("\"totalCount\""))
        }

    @Test
    fun `security events coerces limit parameter to max 500 in clickhouse query`() =
        testApplication {
            val (userId, _) = seedUserAndOrg()
            every { ClickHouseClient.getDatabase() } returns "testdb"
            coEvery { ClickHouseClient.execute(any(), any()) } coAnswers {
                val sql = args[0] as String
                if ("SELECT *" in sql && "security_events" in sql) {
                    assertTrue("LIMIT 500" in sql, "expected coerced limit in query: $sql")
                }
                val mockResponse = mockk<HttpResponse>()
                every { mockResponse.status } returns HttpStatusCode.OK
                coEvery { mockResponse.bodyAsText(any()) } returns when {
                    "count()" in sql -> "0"
                    else -> ""
                }
                mockResponse
            }
            application {
                with(RouteTestSupport) { installJwtAuth() }
                routing { securityRoutes() }
            }
            val response = client.get("$EVENTS_PATH?limit=999") {
                withAuth(token(userId))
            }
            assertEquals(HttpStatusCode.OK, response.status)
        }

    @Test
    fun `security events omits invalid severity from clickhouse where clause`() =
        testApplication {
            val (userId, _) = seedUserAndOrg()
            every { ClickHouseClient.getDatabase() } returns "testdb"
            coEvery { ClickHouseClient.execute(any(), any()) } coAnswers {
                val sql = args[0] as String
                if ("security_events" in sql && "SELECT *" in sql) {
                    assertTrue("severity = 'bogus'" !in sql)
                }
                val mockResponse = mockk<HttpResponse>()
                every { mockResponse.status } returns HttpStatusCode.OK
                coEvery { mockResponse.bodyAsText(any()) } returns when {
                    "count()" in sql -> "0"
                    else -> ""
                }
                mockResponse
            }
            application {
                with(RouteTestSupport) { installJwtAuth() }
                routing { securityRoutes() }
            }
            val response = client.get("$EVENTS_PATH?severity=bogus") {
                withAuth(token(userId))
            }
            assertEquals(HttpStatusCode.OK, response.status)
        }

    @Test
    fun `security events omits invalid host from clickhouse where clause`() =
        testApplication {
            val (userId, _) = seedUserAndOrg()
            every { ClickHouseClient.getDatabase() } returns "testdb"
            coEvery { ClickHouseClient.execute(any(), any()) } coAnswers {
                val sql = args[0] as String
                if ("security_events" in sql && "SELECT *" in sql) {
                    assertTrue("host = '../../../'" !in sql)
                }
                val mockResponse = mockk<HttpResponse>()
                every { mockResponse.status } returns HttpStatusCode.OK
                coEvery { mockResponse.bodyAsText(any()) } returns when {
                    "count()" in sql -> "0"
                    else -> ""
                }
                mockResponse
            }
            application {
                with(RouteTestSupport) { installJwtAuth() }
                routing { securityRoutes() }
            }
            val response = client.get("$EVENTS_PATH?host=../../../") {
                withAuth(token(userId))
            }
            assertEquals(HttpStatusCode.OK, response.status)
        }

    @Test
    fun `compliance omits invalid framework from clickhouse where clause`() =
        testApplication {
            val (userId, _) = seedUserAndOrg()
            every { ClickHouseClient.getDatabase() } returns "testdb"
            coEvery { ClickHouseClient.execute(any(), any()) } coAnswers {
                val sql = args[0] as String
                if ("compliance_findings" in sql && "SELECT *" in sql) {
                    assertTrue("framework = '../../../'" !in sql)
                }
                val mockResponse = mockk<HttpResponse>()
                every { mockResponse.status } returns HttpStatusCode.OK
                coEvery { mockResponse.bodyAsText(any()) } returns when {
                    "count()" in sql -> "0"
                    else -> ""
                }
                mockResponse
            }
            application {
                with(RouteTestSupport) { installJwtAuth() }
                routing { securityRoutes() }
            }
            val response = client.get("$COMPLIANCE_PATH?framework=../../../") {
                withAuth(token(userId))
            }
            assertEquals(HttpStatusCode.OK, response.status)
        }
}
