// Moneat Enterprise - proprietary module
// Copyright (c) 2026 Moneat. All rights reserved.
// See ee/LICENSE for license terms.

package com.moneat.enterprise.workflows.routes

import com.auth0.jwt.JWT
import com.auth0.jwt.algorithms.Algorithm
import com.moneat.enterprise.sso.support.EnterpriseTestDatabaseHelper
import com.moneat.shared.models.Memberships
import com.moneat.shared.models.Organizations
import com.moneat.shared.models.Users
import com.moneat.workflows.WorkflowConnectionGroupSummary
import com.moneat.workflows.WorkflowConnectionReference
import com.moneat.workflows.WorkflowConnectionSummary
import com.moneat.workflows.WorkflowConnectionVault
import com.moneat.workflows.WorkflowResolvedConnection
import io.ktor.client.request.bearerAuth
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
import io.ktor.server.application.Application
import io.ktor.server.application.install
import io.ktor.server.auth.Authentication
import io.ktor.server.auth.jwt.JWTPrincipal
import io.ktor.server.auth.jwt.jwt
import io.ktor.server.plugins.contentnegotiation.ContentNegotiation
import io.ktor.server.routing.routing
import io.ktor.server.testing.testApplication
import io.ktor.serialization.kotlinx.json.json
import kotlin.time.Clock
import kotlinx.serialization.json.Json
import org.jetbrains.exposed.v1.jdbc.Database
import org.jetbrains.exposed.v1.jdbc.insert
import org.jetbrains.exposed.v1.jdbc.transactions.TransactionManager
import org.jetbrains.exposed.v1.jdbc.transactions.transaction
import org.junit.jupiter.api.AfterEach
import org.junit.jupiter.api.BeforeEach
import org.junit.jupiter.api.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertTrue

class ConnectionRoutesTest {

    companion object {
        private var db: Database? = null
        private const val JWT_SECRET = "test-secret-for-workflow-connection-routes"
        private const val ISSUER = "moneat"
        private const val AUDIENCE = "moneat-users"
    }

    private val vault = RecordingConnectionVault()

    @BeforeEach
    fun setup() {
        if (db == null) {
            db =
                Database.connect(
                    url = "jdbc:h2:mem:moneat_ee_workflow_connection_routes;DATABASE_TO_LOWER=TRUE;DB_CLOSE_DELAY=-1",
                    driver = "org.h2.Driver",
                )
        }
        TransactionManager.defaultDatabase = db
        EnterpriseTestDatabaseHelper.resetSchema(Users, Organizations, Memberships)
        vault.reset()
    }

    @AfterEach
    fun clearDbRef() {
        TransactionManager.defaultDatabase = null
    }

    @Test
    fun `connection create list and rotate responses never include the secret`() = testApplication {
        application { installAuthJsonAndRoutes() }
        val (orgId, userId) = seedMember(role = "admin")
        val token = bearerForUser(userId, orgId)

        val createResponse =
            client.post("/v1/workflows/connections") {
                bearerAuth(token)
                header(HttpHeaders.ContentType, ContentType.Application.Json)
                setBody(
                    """
                    {
                      "type": "webhook",
                      "name": "primary-response",
                      "identifier_tags": {"env": "prod"},
                      "secret": "super-sensitive-token-1234"
                    }
                    """.trimIndent()
                )
            }

        assertEquals(HttpStatusCode.Created, createResponse.status)
        assertSecretNotSerialized(createResponse.bodyAsText())

        val listResponse =
            client.get("/v1/workflows/connections") {
                bearerAuth(token)
            }
        assertEquals(HttpStatusCode.OK, listResponse.status)
        assertSecretNotSerialized(listResponse.bodyAsText())

        val rotateResponse =
            client.put("/v1/workflows/connections/1/rotate") {
                bearerAuth(token)
                header(HttpHeaders.ContentType, ContentType.Application.Json)
                setBody("""{"secret":"rotated-sensitive-token-9876"}""")
            }
        assertEquals(HttpStatusCode.OK, rotateResponse.status)
        val rotateBody = rotateResponse.bodyAsText()
        assertFalse(rotateBody.contains("rotated-sensitive-token-9876"))
        assertFalse(rotateBody.contains("\"secret\""))
        assertTrue(rotateBody.contains("\"last_four\":\"9876\""))
    }

    @Test
    fun `delete requires an organization admin`() = testApplication {
        application { installAuthJsonAndRoutes() }
        val (orgId, userId) = seedMember(role = "member")
        val token = bearerForUser(userId, orgId)

        val response =
            client.delete("/v1/workflows/connections/1") {
                bearerAuth(token)
            }

        assertEquals(HttpStatusCode.Forbidden, response.status)
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
        routing { connectionRoutes(vault) }
    }

    private fun seedMember(role: String): Pair<Int, Int> {
        val userId =
            transaction {
                Users.insert {
                    it[email] = "workflow-connections@example.test"
                    it[password_hash] = "x"
                }[Users.id]
            }
        val orgId =
            transaction {
                Organizations.insert {
                    it[name] = "Workflow Routes"
                    it[slug] = "workflow-routes"
                }[Organizations.id]
            }
        transaction {
            Memberships.insert {
                it[user_id] = userId
                it[organization_id] = orgId
                it[Memberships.role] = role
            }
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

    private fun assertSecretNotSerialized(body: String) {
        assertFalse(body.contains("super-sensitive-token-1234"))
        assertFalse(body.contains("\"secret\""))
        assertTrue(body.contains("\"last_four\":\"1234\""))
        assertTrue(body.contains("\"identifier_tags\""))
    }
}

private class RecordingConnectionVault : WorkflowConnectionVault {
    private val connections = mutableMapOf<Int, WorkflowConnectionSummary>()
    private var nextId = 1

    fun reset() {
        connections.clear()
        nextId = 1
    }

    override suspend fun listConnections(organizationId: Int): List<WorkflowConnectionSummary> =
        connections.values.filter { it.organizationId == organizationId }

    override suspend fun getConnection(
        organizationId: Int,
        connectionId: Int
    ): WorkflowConnectionSummary? =
        connections[connectionId]?.takeIf { it.organizationId == organizationId }

    override suspend fun createConnection(
        organizationId: Int,
        type: String,
        name: String,
        identifierTags: Map<String, String>,
        secret: String,
        createdBy: Int?
    ): WorkflowConnectionSummary {
        val now = Clock.System.now().toString()
        val id = nextId++
        val summary =
            WorkflowConnectionSummary(
                id = id,
                organizationId = organizationId,
                type = type,
                name = name,
                identifierTags = identifierTags,
                lastFour = secret.takeLast(LAST_FOUR_LENGTH),
                createdAt = now,
                updatedAt = now
            )
        connections[id] = summary
        return summary
    }

    override suspend fun rotateConnection(
        organizationId: Int,
        connectionId: Int,
        secret: String
    ): WorkflowConnectionSummary? {
        val existing = getConnection(organizationId, connectionId) ?: return null
        val updated =
            existing.copy(
                lastFour = secret.takeLast(LAST_FOUR_LENGTH),
                updatedAt = Clock.System.now().toString()
            )
        connections[connectionId] = updated
        return updated
    }

    override suspend fun deleteConnection(organizationId: Int, connectionId: Int): Boolean {
        val existing = connections[connectionId] ?: return false
        if (existing.organizationId != organizationId) return false
        connections.remove(connectionId)
        return true
    }

    override suspend fun listGroups(organizationId: Int): List<WorkflowConnectionGroupSummary> =
        emptyList()

    override suspend fun createGroup(
        organizationId: Int,
        name: String,
        connectionType: String,
        memberConnectionIds: List<Int>,
        selectionStrategy: String,
        createdBy: Int?
    ): WorkflowConnectionGroupSummary {
        throw UnsupportedOperationException("Groups are covered by service tests")
    }

    override suspend fun deleteGroup(organizationId: Int, groupId: Int): Boolean = false

    override suspend fun resolveSecret(
        organizationId: Int,
        reference: WorkflowConnectionReference,
        runScope: Map<String, String>
    ): WorkflowResolvedConnection? = null

    companion object {
        private const val LAST_FOUR_LENGTH = 4
    }
}
