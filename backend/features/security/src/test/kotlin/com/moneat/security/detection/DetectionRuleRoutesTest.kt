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

package com.moneat.security.detection

import com.moneat.org.repositories.OrgMembershipRepository
import com.moneat.org.repositories.OrgMembershipRepositoryImpl
import com.moneat.org.services.OrgMembershipService
import com.moneat.shared.models.Memberships
import com.moneat.shared.models.Organizations
import com.moneat.shared.models.Users
import com.moneat.testsupport.RouteTestSupport
import com.moneat.testsupport.RouteTestSupport.installJwtAuth
import com.moneat.testsupport.RouteTestSupport.withAuth
import com.moneat.testsupport.stopTestKoin
import io.ktor.client.request.delete
import io.ktor.client.request.get
import io.ktor.client.request.post
import io.ktor.client.request.setBody
import io.ktor.client.statement.bodyAsText
import io.ktor.http.ContentType
import io.ktor.http.HttpStatusCode
import io.ktor.http.contentType
import io.ktor.server.routing.routing
import io.ktor.server.testing.ApplicationTestBuilder
import io.ktor.server.testing.testApplication
import kotlinx.serialization.json.Json
import org.jetbrains.exposed.v1.jdbc.Database
import org.jetbrains.exposed.v1.jdbc.SchemaUtils
import org.jetbrains.exposed.v1.jdbc.insert
import org.jetbrains.exposed.v1.jdbc.transactions.TransactionManager
import org.jetbrains.exposed.v1.jdbc.transactions.transaction
import org.koin.core.context.startKoin
import org.koin.dsl.module
import kotlin.test.AfterTest
import kotlin.test.BeforeTest
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertTrue

/**
 * Route-level authZ + isolation: reads = MEMBER, mutations + preview = ADMIN (via
 * [OrgMembershipService.requireRole]); cross-org access is 404; DSL compile errors map to 400 so no
 * ClickHouse text leaks. The query runner is faked so create/preview never hit a real cluster.
 */
class DetectionRuleRoutesTest {
    companion object {
        private var db: Database? = null
    }

    private val json = Json {
        ignoreUnknownKeys = true
        encodeDefaults = true
    }
    private var orgId: Int = 0
    private var otherOrgId: Int = 0
    private var adminUserId: Int = 0
    private var memberUserId: Int = 0

    private val service = DetectionRuleService(
        compiler = RuleQueryCompiler({ "moneat" }),
        runner = DetectionQueryRunner(execute = { _ -> """{"g0":"web-01","match_count":12}""" }),
    )

    @BeforeTest
    fun setup() {
        if (db == null) {
            db = Database.connect(
                url = "jdbc:h2:mem:moneat_detection_routes;DATABASE_TO_LOWER=TRUE;DB_CLOSE_DELAY=-1",
                driver = "org.h2.Driver",
            )
        }
        TransactionManager.defaultDatabase = db
        DetectionSchemaTestSupport.reset()
        transaction { SchemaUtils.create(Users, Memberships) }
        adminUserId = seedUser("admin@detection.test")
        memberUserId = seedUser("member@detection.test")
        orgId = seedOrg("acme")
        otherOrgId = seedOrg("globex")
        seedMembership(orgId, adminUserId, "admin")
        seedMembership(orgId, memberUserId, "member")

        stopTestKoin()
        startKoin {
            modules(
                module {
                    single<OrgMembershipRepository> { OrgMembershipRepositoryImpl() }
                    single { OrgMembershipService(get()) }
                }
            )
        }
    }

    @AfterTest
    fun teardown() {
        stopTestKoin()
    }

    private fun body() = json.encodeToString(
        CreateDetectionRuleRequest(
            name = "Burst",
            filter = "status:error",
            groupBy = listOf("host"),
            windowSeconds = 300,
            type = "threshold",
            thresholdCount = 5,
            severity = "high",
            enabled = true,
        )
    )

    @Test
    fun `list requires authentication`() = testApplication {
        setupApp()
        assertEquals(HttpStatusCode.Unauthorized, client.get("/v1/security/detection/rules").status)
    }

    @Test
    fun `a member can list rules`() = testApplication {
        setupApp()
        service.create(orgId, request())
        val response = client.get("/v1/security/detection/rules") { withAuth(token(memberUserId)) }
        assertEquals(HttpStatusCode.OK, response.status)
        assertTrue(response.bodyAsText().contains("\"name\":\"Rule\""))
    }

    @Test
    fun `a member cannot create a rule`() = testApplication {
        setupApp()
        val response = client.post("/v1/security/detection/rules") {
            withAuth(token(memberUserId))
            contentType(ContentType.Application.Json)
            setBody(body())
        }
        assertEquals(HttpStatusCode.Forbidden, response.status)
    }

    @Test
    fun `an admin can create a rule`() = testApplication {
        setupApp()
        val response = client.post("/v1/security/detection/rules") {
            withAuth(token(adminUserId))
            contentType(ContentType.Application.Json)
            setBody(body())
        }
        assertEquals(HttpStatusCode.Created, response.status)
        assertTrue(response.bodyAsText().contains("\"name\":\"Burst\""))
    }

    @Test
    fun `a non-member is forbidden from creating`() = testApplication {
        setupApp()
        val outsider = seedUser("outsider@detection.test")
        val response = client.post("/v1/security/detection/rules") {
            withAuth(token(outsider))
            contentType(ContentType.Application.Json)
            setBody(body())
        }
        assertEquals(HttpStatusCode.Forbidden, response.status)
    }

    @Test
    fun `a DSL compile error maps to 400 and leaks no ClickHouse text`() = testApplication {
        setupApp()
        val bad = json.encodeToString(request(groupBy = listOf("password")))
        val response = client.post("/v1/security/detection/rules") {
            withAuth(token(adminUserId))
            contentType(ContentType.Application.Json)
            setBody(bad)
        }
        assertEquals(HttpStatusCode.BadRequest, response.status)
        val text = response.bodyAsText()
        assertTrue(text.contains("Unknown group-by column"))
        listOf("Code:", "DB::Exception", "SETTINGS", "FROM `").forEach {
            assertFalse(text.contains(it), "leaked DB internal '$it': $text")
        }
    }

    @Test
    fun `another org cannot read a rule`() = testApplication {
        setupApp()
        val created = service.create(orgId, request())
        val response = client.get("/v1/security/detection/rules/${created.id}") {
            withAuth(tokenForOrg(adminUserId, otherOrgId))
        }
        assertEquals(HttpStatusCode.NotFound, response.status)
    }

    @Test
    fun `coverage reports enabled rule MITRE tags for the caller org`() = testApplication {
        setupApp()
        service.create(orgId, request().copy(tags = listOf("mitre:T1110")))
        service.create(otherOrgId, request().copy(name = "Other", tags = listOf("mitre:T1059")))

        val response = client.get("/v1/security/detection/coverage") { withAuth(token(memberUserId)) }

        assertEquals(HttpStatusCode.OK, response.status)
        val body = response.bodyAsText()
        assertTrue(body.contains("\"technique_id\":\"T1110\""))
        assertFalse(body.contains("\"technique_id\":\"T1059\""))
    }

    @Test
    fun `delete requires admin and removes the rule`() = testApplication {
        setupApp()
        val created = service.create(orgId, request())
        // member is forbidden
        assertEquals(
            HttpStatusCode.Forbidden,
            client.delete("/v1/security/detection/rules/${created.id}") { withAuth(token(memberUserId)) }.status,
        )
        // admin succeeds
        assertEquals(
            HttpStatusCode.NoContent,
            client.delete("/v1/security/detection/rules/${created.id}") { withAuth(token(adminUserId)) }.status,
        )
    }

    private fun request(
        name: String = "Rule",
        groupBy: List<String> = listOf("host"),
    ) = CreateDetectionRuleRequest(
        name = name,
        filter = "status:error",
        groupBy = groupBy,
        windowSeconds = 300,
        type = "threshold",
        thresholdCount = 5,
        severity = "high",
        enabled = true,
    )

    private fun ApplicationTestBuilder.setupApp() {
        application {
            installJwtAuth()
            routing { detectionRuleRoutes(service) }
        }
    }

    private fun token(userId: Int): String = RouteTestSupport.createToken(userId = userId, orgId = orgId)

    private fun tokenForOrg(userId: Int, org: Int): String =
        RouteTestSupport.createToken(userId = userId, orgId = org)

    private fun seedUser(email: String): Int =
        transaction {
            Users.insert {
                it[Users.email] = email
                it[password_hash] = "hash"
                it[name] = email.substringBefore("@")
                it[email_verified] = true
            } get Users.id
        }

    private fun seedOrg(name: String): Int =
        transaction {
            Organizations.insert {
                it[Organizations.name] = name
                it[slug] = name
            } get Organizations.id
        }

    private fun seedMembership(orgId: Int, userId: Int, role: String) {
        transaction {
            Memberships.insert {
                it[organization_id] = orgId
                it[user_id] = userId
                it[Memberships.role] = role
            }
        }
    }
}
