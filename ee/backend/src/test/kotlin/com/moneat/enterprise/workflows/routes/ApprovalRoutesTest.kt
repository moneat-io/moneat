// Moneat Enterprise - proprietary module
// Copyright (c) 2026 Moneat. All rights reserved.
// See ee/LICENSE for license terms.

package com.moneat.enterprise.workflows.routes

import com.auth0.jwt.JWT
import com.auth0.jwt.algorithms.Algorithm
import com.moneat.enterprise.sso.support.EnterpriseTestDatabaseHelper
import com.moneat.enterprise.workflows.models.ApprovalResponse
import com.moneat.enterprise.workflows.services.WorkflowApprovalService
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
import io.mockk.every
import io.mockk.mockk
import io.mockk.verify
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

class ApprovalRoutesTest {

    companion object {
        private var db: Database? = null
        private const val JWT_SECRET = "test-secret-for-workflow-approval-routes"
        private const val ISSUER = "moneat"
        private const val AUDIENCE = "moneat-users"
        private const val LIST_APPROVAL_ID = 12
        private const val RESPOND_APPROVAL_ID = 18
        private const val RESPONSE_WORKFLOW_ID = 1
        private const val RESPONSE_RUN_ID = 1
    }

    private lateinit var approvalService: WorkflowApprovalService

    @BeforeEach
    fun setup() {
        if (db == null) {
            db =
                Database.connect(
                    url = "jdbc:h2:mem:moneat_ee_workflow_approval_routes;DATABASE_TO_LOWER=TRUE;DB_CLOSE_DELAY=-1",
                    driver = "org.h2.Driver",
                )
        }
        TransactionManager.defaultDatabase = db
        EnterpriseTestDatabaseHelper.resetSchema(
            Users,
            Organizations,
            Memberships
        )
        approvalService = mockk()
    }

    @AfterEach
    fun clearDbRef() {
        TransactionManager.defaultDatabase = null
    }

    @Test
    fun `list accepts the standard orgId JWT claim`() = testApplication {
        application { installAuthJsonAndRoutes() }
        val member = seedMember(role = "member", email = "member@approval-routes.test")
        every { approvalService.listPending(member.orgId) } returns listOf(
            approvalResponse(LIST_APPROVAL_ID, message = "Escalate checkout deployment")
        )

        val response =
            client.get("/v1/workflows/approvals") {
                bearerAuth(bearerForUser(member.userId, member.orgId))
            }

        assertEquals(HttpStatusCode.OK, response.status)
        val body = response.bodyAsText()
        assertTrue(body.contains("\"id\":$LIST_APPROVAL_ID"))
        assertTrue(body.contains("Escalate checkout deployment"))
        verify { approvalService.listPending(member.orgId) }
    }

    @Test
    fun `respond accepts the standard orgId JWT claim`() = testApplication {
        application { installAuthJsonAndRoutes() }
        val admin = seedMember(role = "admin", email = "admin@approval-routes.test")
        every {
            approvalService.respond(
                organizationId = admin.orgId,
                approvalId = RESPOND_APPROVAL_ID,
                approved = true,
                actorUserId = admin.userId,
                comment = "approved from route test"
            )
        } returns approvalResponse(
            RESPOND_APPROVAL_ID,
            message = "Approve rollout",
            status = "approved",
            respondedBy = admin.userId,
            comment = "approved from route test"
        )

        val response =
            client.post("/v1/workflows/approvals/$RESPOND_APPROVAL_ID/respond") {
                bearerAuth(bearerForUser(admin.userId, admin.orgId))
                header(HttpHeaders.ContentType, ContentType.Application.Json)
                setBody("""{"approved":true,"comment":"approved from route test"}""")
            }

        assertEquals(HttpStatusCode.OK, response.status)
        val body = response.bodyAsText()
        assertTrue(body.contains("\"status\":\"approved\""))
        assertTrue(body.contains("\"responded_by\":${admin.userId}"))
        assertTrue(body.contains("approved from route test"))
        verify {
            approvalService.respond(
                organizationId = admin.orgId,
                approvalId = RESPOND_APPROVAL_ID,
                approved = true,
                actorUserId = admin.userId,
                comment = "approved from route test"
            )
        }
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
        routing { approvalRoutes(approvalService) }
    }

    private fun seedMember(role: String, email: String): TestMember {
        val userId =
            transaction {
                Users.insert {
                    it[Users.email] = email
                    it[password_hash] = "x"
                }[Users.id]
            }
        val orgId =
            transaction {
                Organizations.insert {
                    it[name] = "Workflow Approval Routes $userId"
                    it[slug] = "workflow-approval-routes-$userId"
                }[Organizations.id]
            }
        transaction {
            Memberships.insert {
                it[user_id] = userId
                it[organization_id] = orgId
                it[Memberships.role] = role
            }
        }
        return TestMember(userId = userId, orgId = orgId)
    }

    private fun bearerForUser(userId: Int, orgId: Int): String =
        JWT
            .create()
            .withIssuer(ISSUER)
            .withAudience(AUDIENCE)
            .withClaim("userId", userId)
            .withClaim("orgId", orgId)
            .sign(Algorithm.HMAC256(JWT_SECRET))

    private fun approvalResponse(
        id: Int,
        message: String,
        status: String = "pending",
        respondedBy: Int? = null,
        comment: String? = null
    ): ApprovalResponse =
        ApprovalResponse(
            id = id,
            workflowId = RESPONSE_WORKFLOW_ID,
            runId = RESPONSE_RUN_ID,
            nodeId = "approval-$id",
            message = message,
            approverRole = "admin",
            status = status,
            requestedAt = "2026-01-01T00:00:00Z",
            respondedAt = if (respondedBy == null) null else "2026-01-01T00:01:00Z",
            respondedBy = respondedBy,
            comment = comment
        )

    private data class TestMember(
        val userId: Int,
        val orgId: Int
    )
}
