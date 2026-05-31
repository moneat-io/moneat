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
 * AuthZ + behavior for the Sigma import and starter-pack template endpoints. Import + install are
 * ADMIN; template list is MEMBER. Created rules persist disabled. A real [DetectionRuleService] (with a
 * faked query runner) is used so the import/install path genuinely runs the compiler before persisting.
 */
class DetectionImportRoutesTest {
    companion object {
        private var db: Database? = null
    }

    private val json = Json {
        ignoreUnknownKeys = true
        encodeDefaults = true
    }
    private var orgId: Int = 0
    private var adminUserId: Int = 0
    private var memberUserId: Int = 0

    private val service = DetectionRuleService(
        compiler = RuleQueryCompiler({ "moneat" }),
        runner = DetectionQueryRunner(execute = { _ -> """{"g0":"web-01","match_count":3}""" }),
    )

    @BeforeTest
    fun setup() {
        if (db == null) {
            db = Database.connect(
                url = "jdbc:h2:mem:moneat_detection_import;DATABASE_TO_LOWER=TRUE;DB_CLOSE_DELAY=-1",
                driver = "org.h2.Driver",
            )
        }
        TransactionManager.defaultDatabase = db
        DetectionSchemaTestSupport.reset()
        transaction { SchemaUtils.create(Users, Memberships) }
        adminUserId = seedUser("admin@import.test")
        memberUserId = seedUser("member@import.test")
        orgId = seedOrg("acme")
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
    fun teardown() = stopTestKoin()

    private val sampleSigma = """
        title: Suspicious curl download
        level: high
        logsource:
          category: process_creation
        detection:
          selection:
            Image|endswith: /curl
            CommandLine|contains: http
          condition: selection
    """.trimIndent()

    @Test
    fun `a member cannot import sigma`() = testApplication {
        setupApp()
        val response = client.post("/v1/security/detection/import/sigma") {
            withAuth(token(memberUserId))
            contentType(ContentType.Application.Json)
            setBody(json.encodeToString(SigmaImportRequest(documents = listOf(sampleSigma))))
        }
        assertEquals(HttpStatusCode.Forbidden, response.status)
    }

    @Test
    fun `an admin can import a sigma rule and it persists disabled`() = testApplication {
        setupApp()
        val response = client.post("/v1/security/detection/import/sigma") {
            withAuth(token(adminUserId))
            contentType(ContentType.Application.Json)
            setBody(json.encodeToString(SigmaImportRequest(documents = listOf(sampleSigma))))
        }
        assertEquals(HttpStatusCode.OK, response.status)
        val body = response.bodyAsText()
        assertTrue(body.contains("\"created_count\":1"), body)
        assertTrue(body.contains("\"error_count\":0"), body)
        // The created rule exists for the org and is disabled.
        val list = service.list(orgId)
        assertEquals(1, list.totalCount)
        assertFalse(list.rules.single().enabled)
        assertEquals("Suspicious curl download", list.rules.single().name)
    }

    @Test
    fun `a batch reports per-document errors without aborting the good ones`() = testApplication {
        setupApp()
        val badSigma = "title: bad\ndetection:\n  s:\n    CommandLine|re: ev[il]+\n  condition: s"
        val response = client.post("/v1/security/detection/import/sigma") {
            withAuth(token(adminUserId))
            contentType(ContentType.Application.Json)
            setBody(json.encodeToString(SigmaImportRequest(documents = listOf(sampleSigma, badSigma))))
        }
        assertEquals(HttpStatusCode.OK, response.status)
        val body = response.bodyAsText()
        assertTrue(body.contains("\"created_count\":1"), body)
        assertTrue(body.contains("\"error_count\":1"), body)
        // Only the good one persisted.
        assertEquals(1, service.list(orgId).totalCount)
        // No ClickHouse internals leak in the per-item error.
        listOf("Code:", "DB::Exception", "SETTINGS", "FROM `").forEach {
            assertFalse(body.contains(it), "leaked '$it': $body")
        }
    }

    @Test
    fun `a pathologically nested condition yields a clean per-item error not a 500`() = testApplication {
        setupApp()
        val nested = "(".repeat(20_000) + "s" + ")".repeat(20_000)
        val evil = "title: nested\ndetection:\n  s:\n    Image: x\n  condition: $nested"
        val response = client.post("/v1/security/detection/import/sigma") {
            withAuth(token(adminUserId))
            contentType(ContentType.Application.Json)
            setBody(json.encodeToString(SigmaImportRequest(documents = listOf(sampleSigma, evil))))
        }
        // The whole batch still returns 200; the hostile document is a per-item error, the good one persists.
        assertEquals(HttpStatusCode.OK, response.status)
        val body = response.bodyAsText()
        assertTrue(body.contains("\"created_count\":1"), body)
        assertTrue(body.contains("\"error_count\":1"), body)
        assertEquals(1, service.list(orgId).totalCount)
    }

    @Test
    fun `empty document list is a 400`() = testApplication {
        setupApp()
        val response = client.post("/v1/security/detection/import/sigma") {
            withAuth(token(adminUserId))
            contentType(ContentType.Application.Json)
            setBody(json.encodeToString(SigmaImportRequest(documents = emptyList())))
        }
        assertEquals(HttpStatusCode.BadRequest, response.status)
    }

    @Test
    fun `a member can list templates`() = testApplication {
        setupApp()
        val response = client.get("/v1/security/detection/templates") { withAuth(token(memberUserId)) }
        assertEquals(HttpStatusCode.OK, response.status)
        val body = response.bodyAsText()
        assertTrue(body.contains("failed-auth-brute-force"), body)
        assertTrue(body.contains("\"total_count\":6"), body)
    }

    @Test
    fun `template list requires authentication`() = testApplication {
        setupApp()
        assertEquals(
            HttpStatusCode.Unauthorized,
            client.get("/v1/security/detection/templates").status,
        )
    }

    @Test
    fun `a member cannot install a template`() = testApplication {
        setupApp()
        val response = client.post("/v1/security/detection/templates/failed-auth-brute-force/install") {
            withAuth(token(memberUserId))
        }
        assertEquals(HttpStatusCode.Forbidden, response.status)
    }

    @Test
    fun `an admin can install a template as a disabled rule`() = testApplication {
        setupApp()
        val response = client.post("/v1/security/detection/templates/failed-auth-brute-force/install") {
            withAuth(token(adminUserId))
        }
        assertEquals(HttpStatusCode.Created, response.status)
        val list = service.list(orgId)
        assertEquals(1, list.totalCount)
        assertFalse(list.rules.single().enabled)
        assertEquals("Failed authentication brute force", list.rules.single().name)
    }

    @Test
    fun `installing an unknown template is a 404`() = testApplication {
        setupApp()
        val response = client.post("/v1/security/detection/templates/does-not-exist/install") {
            withAuth(token(adminUserId))
        }
        assertEquals(HttpStatusCode.NotFound, response.status)
    }

    private fun ApplicationTestBuilder.setupApp() {
        application {
            installJwtAuth()
            routing { detectionRuleRoutes(service) }
        }
    }

    private fun token(userId: Int): String = RouteTestSupport.createToken(userId = userId, orgId = orgId)

    private fun seedUser(email: String): Int = transaction {
        Users.insert {
            it[Users.email] = email
            it[password_hash] = "hash"
            it[name] = email.substringBefore("@")
            it[email_verified] = true
        } get Users.id
    }

    private fun seedOrg(name: String): Int = transaction {
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
