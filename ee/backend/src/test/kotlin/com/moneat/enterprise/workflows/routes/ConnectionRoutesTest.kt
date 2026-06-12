// Moneat Enterprise - proprietary module
// Copyright (c) 2026 Moneat. All rights reserved.
// See ee/LICENSE for license terms.

package com.moneat.enterprise.workflows.routes

import com.auth0.jwt.JWT
import com.auth0.jwt.algorithms.Algorithm
import com.moneat.enterprise.sso.support.EnterpriseTestDatabaseHelper
import com.moneat.enterprise.workflows.models.WorkflowConnections
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
import kotlinx.serialization.encodeToString
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.jsonObject
import kotlinx.serialization.json.jsonPrimitive
import org.jetbrains.exposed.v1.jdbc.Database
import org.jetbrains.exposed.v1.jdbc.insert
import org.jetbrains.exposed.v1.jdbc.insertAndGetId
import org.jetbrains.exposed.v1.jdbc.transactions.TransactionManager
import org.jetbrains.exposed.v1.jdbc.transactions.transaction
import org.junit.jupiter.api.AfterEach
import org.junit.jupiter.api.BeforeEach
import org.junit.jupiter.api.Test
import kotlin.time.Clock
import kotlin.uuid.Uuid
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
        EnterpriseTestDatabaseHelper.resetSchema(Users, Organizations, Memberships, WorkflowConnections)
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
        val createBody = createResponse.bodyAsText()
        assertSecretNotSerialized(createBody)
        val connectionResourceId = connectionIdFromBody(createBody)

        val listResponse =
            client.get("/v1/workflows/connections") {
                bearerAuth(token)
            }
        assertEquals(HttpStatusCode.OK, listResponse.status)
        assertSecretNotSerialized(listResponse.bodyAsText())

        val rotateResponse =
            client.put("/v1/workflows/connections/$connectionResourceId/rotate") {
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

    @Test
    fun `list requires current organization membership`() = testApplication {
        application { installAuthJsonAndRoutes() }
        val (orgId, userId) = seedUserAndOrganization()
        val token = bearerForUser(userId, orgId)

        val response =
            client.get("/v1/workflows/connections") {
                bearerAuth(token)
            }

        assertEquals(HttpStatusCode.Unauthorized, response.status)
    }

    @Test
    fun `connection group creation rejects numeric member connection ids`() = testApplication {
        application { installAuthJsonAndRoutes() }
        val (orgId, userId) = seedMember(role = "admin")
        val token = bearerForUser(userId, orgId)

        val response =
            client.post("/v1/workflows/connection-groups") {
                bearerAuth(token)
                header(HttpHeaders.ContentType, ContentType.Application.Json)
                setBody(
                    """
                    {
                      "name": "primary-group",
                      "connection_type": "webhook",
                      "member_connection_ids": ["1"],
                      "selection_strategy": "first_match"
                    }
                    """.trimIndent()
                )
        }

        assertEquals(HttpStatusCode.BadRequest, response.status)
        assertTrue(response.bodyAsText().contains("Invalid connection ID"))
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

    private fun connectionIdFromBody(body: String): String =
        Json.parseToJsonElement(body).jsonObject.getValue("id").jsonPrimitive.content
}

private class RecordingConnectionVault : WorkflowConnectionVault {
    private val connections = mutableMapOf<Int, WorkflowConnectionSummary>()

    fun reset() {
        connections.clear()
    }

    override suspend fun listConnections(organizationId: Int): List<WorkflowConnectionSummary> =
        connections.values.filter { it.organizationId == organizationId }

    override suspend fun getConnection(
        organizationId: Int,
        connectionId: Int
    ): WorkflowConnectionSummary? =
        connections[connectionId]?.takeIf { it.organizationId == organizationId }

    override suspend fun resolveConnectionId(
        organizationId: Int,
        connectionResourceId: String
    ): Int? =
        connections.values
            .firstOrNull { it.organizationId == organizationId && it.resourceId == connectionResourceId }
            ?.id

    override suspend fun createConnection(
        organizationId: Int,
        type: String,
        name: String,
        identifierTags: Map<String, String>,
        secret: String,
        createdBy: Int?
    ): WorkflowConnectionSummary {
        val now = Clock.System.now()
        val resourceId = Uuid.random()
        val id =
            transaction {
                WorkflowConnections.insertAndGetId {
                    it[WorkflowConnections.resourceId] = resourceId
                    it[WorkflowConnections.organizationId] = organizationId
                    it[WorkflowConnections.type] = type
                    it[WorkflowConnections.name] = name
                    it[WorkflowConnections.identifierTags] = Json.encodeToString(identifierTags)
                    it[WorkflowConnections.encryptedCredentials] = "encrypted"
                    it[WorkflowConnections.keyId] = "test-key"
                    it[WorkflowConnections.lastFour] = secret.takeLast(LAST_FOUR_LENGTH)
                    it[WorkflowConnections.createdBy] = createdBy
                    it[WorkflowConnections.createdAt] = now
                    it[WorkflowConnections.updatedAt] = now
                }.value
            }
        val summary =
            WorkflowConnectionSummary(
                id = id,
                resourceId = resourceId.toString(),
                organizationId = organizationId,
                type = type,
                name = name,
                identifierTags = identifierTags,
                lastFour = secret.takeLast(LAST_FOUR_LENGTH),
                createdAt = now.toString(),
                updatedAt = now.toString()
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

    override suspend fun resolveGroupId(
        organizationId: Int,
        groupResourceId: String
    ): Int? = null

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
