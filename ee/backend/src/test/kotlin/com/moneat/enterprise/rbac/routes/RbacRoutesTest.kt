// Moneat Enterprise - proprietary module
// Copyright (c) 2026 Moneat. All rights reserved.
// See ee/LICENSE for license terms.

package com.moneat.enterprise.rbac.routes

import com.auth0.jwt.JWT
import com.auth0.jwt.algorithms.Algorithm
import com.moneat.enterprise.rbac.models.RbacRoleAssignments
import com.moneat.enterprise.rbac.models.RbacRoles
import com.moneat.enterprise.rbac.services.RbacService
import com.moneat.enterprise.sso.support.EnterpriseTestDatabaseHelper
import com.moneat.shared.models.Memberships
import com.moneat.shared.models.Organizations
import com.moneat.shared.models.Users
import io.ktor.client.request.bearerAuth
import io.ktor.client.request.get
import io.ktor.client.request.header
import io.ktor.client.request.post
import io.ktor.client.request.setBody
import io.ktor.client.statement.bodyAsText
import io.ktor.http.ContentType
import io.ktor.http.HttpHeaders
import io.ktor.http.HttpStatusCode
import io.ktor.server.application.Application
import io.ktor.server.application.install
import io.ktor.server.auth.Authentication
import io.ktor.server.auth.jwt.JWTPrincipal
import io.ktor.server.auth.jwt.jwt
import io.ktor.server.plugins.contentnegotiation.ContentNegotiation
import io.ktor.server.routing.routing
import io.ktor.server.testing.testApplication
import io.ktor.serialization.kotlinx.json.json
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

class RbacRoutesTest {

    companion object {
        private var db: Database? = null
        private const val JWT_SECRET = "test-secret-for-rbac-routes"
        private const val ISSUER = "moneat"
        private const val AUDIENCE = "moneat-users"
    }

    private val service = RbacService()

    @BeforeEach
    fun setup() {
        if (db == null) {
            db = Database.connect(
                url = "jdbc:h2:mem:moneat_ee_rbac_routes;DATABASE_TO_LOWER=TRUE;DB_CLOSE_DELAY=-1",
                driver = "org.h2.Driver"
            )
        }
        TransactionManager.defaultDatabase = db
        EnterpriseTestDatabaseHelper.resetSchema(Users, Organizations, Memberships, RbacRoles, RbacRoleAssignments)
    }

    @AfterEach
    fun clearDbRef() {
        TransactionManager.defaultDatabase = null
    }

    @Test
    fun `an admin creates a role`() = testApplication {
        application { installAuthJsonAndRoutes() }
        val (orgId, userId) = seedMember(role = "admin")

        val response = client.post("/v1/rbac/roles") {
            bearerAuth(bearerForUser(userId, orgId))
            header(HttpHeaders.ContentType, ContentType.Application.Json)
            setBody("""{"name":"runner","permissions":["workflows:run"]}""")
        }

        assertEquals(HttpStatusCode.Created, response.status)
        assertTrue(response.bodyAsText().contains("\"name\":\"runner\""))
    }

    @Test
    fun `a member cannot create a role`() = testApplication {
        application { installAuthJsonAndRoutes() }
        val (orgId, userId) = seedMember(role = "member")

        val response = client.post("/v1/rbac/roles") {
            bearerAuth(bearerForUser(userId, orgId))
            header(HttpHeaders.ContentType, ContentType.Application.Json)
            setBody("""{"name":"runner","permissions":["workflows:run"]}""")
        }

        assertEquals(HttpStatusCode.Forbidden, response.status)
    }

    @Test
    fun `a member can list roles`() = testApplication {
        application { installAuthJsonAndRoutes() }
        val (orgId, userId) = seedMember(role = "member")

        val response = client.get("/v1/rbac/roles") { bearerAuth(bearerForUser(userId, orgId)) }

        assertEquals(HttpStatusCode.OK, response.status)
    }

    @Test
    fun `an admin assigns a role to a user`() = testApplication {
        application { installAuthJsonAndRoutes() }
        val (orgId, userId) = seedMember(role = "admin")
        val role = service.createRole(orgId, "runner", listOf("workflows:run"), userId)

        val response = client.post("/v1/rbac/roles/${role.id}/assignments") {
            bearerAuth(bearerForUser(userId, orgId))
            header(HttpHeaders.ContentType, ContentType.Application.Json)
            setBody("""{"user_id":4242}""")
        }

        assertEquals(HttpStatusCode.Created, response.status)
        assertEquals(setOf("workflows:run"), service.resolvePermissions(orgId, 4242))
    }

    @Test
    fun `assigning a role requires an admin`() = testApplication {
        application { installAuthJsonAndRoutes() }
        val (orgId, userId) = seedMember(role = "member")
        val role = service.createRole(orgId, "runner", listOf("workflows:run"), null)

        val response = client.post("/v1/rbac/roles/${role.id}/assignments") {
            bearerAuth(bearerForUser(userId, orgId))
            header(HttpHeaders.ContentType, ContentType.Application.Json)
            setBody("""{"user_id":4242}""")
        }

        assertEquals(HttpStatusCode.Forbidden, response.status)
    }

    @Test
    fun `assigning to an unknown role returns not found`() = testApplication {
        application { installAuthJsonAndRoutes() }
        val (orgId, userId) = seedMember(role = "admin")

        val response = client.post("/v1/rbac/roles/9999/assignments") {
            bearerAuth(bearerForUser(userId, orgId))
            header(HttpHeaders.ContentType, ContentType.Application.Json)
            setBody("""{"user_id":4242}""")
        }

        assertEquals(HttpStatusCode.NotFound, response.status)
    }

    @Test
    fun `listing requires current organization membership`() = testApplication {
        application { installAuthJsonAndRoutes() }
        val (orgId, userId) = seedUserAndOrganization()

        val response = client.get("/v1/rbac/roles") { bearerAuth(bearerForUser(userId, orgId)) }

        assertEquals(HttpStatusCode.Unauthorized, response.status)
    }

    private fun Application.installAuthJsonAndRoutes() {
        install(ContentNegotiation) { json(Json { ignoreUnknownKeys = true }) }
        install(Authentication) {
            jwt("auth-jwt") {
                verifier(
                    JWT
                        .require(Algorithm.HMAC256(JWT_SECRET))
                        .withIssuer(ISSUER)
                        .withAudience(AUDIENCE)
                        .build()
                )
                validate { JWTPrincipal(it.payload) }
            }
        }
        routing { rbacRoutes(service) }
    }

    private fun seedMember(role: String): Pair<Int, Int> {
        val (orgId, userId) = seedUserAndOrganization()
        transaction {
            Memberships.insert {
                it[user_id] = userId
                it[organization_id] = orgId
                it[Memberships.role] = role
            }
        }
        return orgId to userId
    }

    private fun seedUserAndOrganization(): Pair<Int, Int> {
        val userId =
            transaction {
                Users.insert {
                    it[email] = "rbac-routes@example.test"
                    it[password_hash] = "x"
                }[Users.id]
            }
        val orgId =
            transaction {
                Organizations.insert {
                    it[name] = "RBAC Routes"
                    it[slug] = "rbac-routes"
                }[Organizations.id]
            }
        return orgId to userId
    }

    private fun bearerForUser(userId: Int, orgId: Int): String =
        JWT
            .create()
            .withIssuer(ISSUER)
            .withAudience(AUDIENCE)
            .withClaim("userId", userId)
            .withClaim("orgId", orgId)
            .sign(Algorithm.HMAC256(JWT_SECRET))
}
