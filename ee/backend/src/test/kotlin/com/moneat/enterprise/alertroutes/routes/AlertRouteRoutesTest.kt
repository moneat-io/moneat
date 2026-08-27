// Moneat Enterprise - proprietary module
// Copyright (c) 2026 Moneat. All rights reserved.
// See ee/LICENSE for license terms.

package com.moneat.enterprise.alertroutes.routes

import com.auth0.jwt.JWT
import com.auth0.jwt.algorithms.Algorithm
import com.moneat.enterprise.alertroutes.AlertRouteTestDatabase
import com.moneat.enterprise.alertroutes.SeededAlertRouteOrganization
import com.moneat.enterprise.alertroutes.commands.AlertRoutePolicy
import com.moneat.enterprise.alertroutes.services.AlertRouteCommandService
import com.moneat.enterprise.alertroutes.services.AlertRouteEvaluationService
import io.ktor.client.request.HttpRequestBuilder
import io.ktor.client.request.bearerAuth
import io.ktor.client.request.delete
import io.ktor.client.request.get
import io.ktor.client.request.header
import io.ktor.client.request.post
import io.ktor.client.request.put
import io.ktor.client.request.setBody
import io.ktor.client.statement.HttpResponse
import io.ktor.client.statement.bodyAsText
import io.ktor.http.ContentType
import io.ktor.http.HttpHeaders
import io.ktor.http.HttpStatusCode
import io.ktor.serialization.kotlinx.json.json
import io.ktor.server.application.Application
import io.ktor.server.application.install
import io.ktor.server.auth.Authentication
import io.ktor.server.auth.jwt.JWTPrincipal
import io.ktor.server.auth.jwt.jwt
import io.ktor.server.plugins.contentnegotiation.ContentNegotiation
import io.ktor.server.routing.routing
import io.ktor.server.testing.ApplicationTestBuilder
import io.ktor.server.testing.testApplication
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.JsonArray
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.int
import kotlinx.serialization.json.jsonArray
import kotlinx.serialization.json.jsonObject
import kotlinx.serialization.json.jsonPrimitive
import org.junit.jupiter.api.AfterEach
import org.junit.jupiter.api.BeforeEach
import org.junit.jupiter.api.Test
import kotlin.test.assertEquals
import kotlin.test.assertTrue
import kotlin.uuid.Uuid

private const val JWT_SECRET = "alert-route-route-test-secret"
private const val ISSUER = "moneat"
private const val AUDIENCE = "moneat-dashboard"
private const val IDEMPOTENCY_HEADER = "Idempotency-Key"

private val CREATE_BODY =
    """
    {
      "key": "dashboard-alerts",
      "name": "Dashboard alerts",
      "conditionGroups": [
        {"conditions": [{"field": "source", "operator": "EQUALS", "values": ["DASHBOARD_ALERT"]}]}
      ],
      "grouping": {"behavior": "AUTOMATIC", "keys": ["alert.deduplication_key"], "windowSeconds": 600},
      "incident": {"create": true, "mode": "ACTIVE", "titleTemplate": "{{ alert.title }}", "severity": "SEV-2"},
      "recovery": {"cancelEscalations": true, "declineUnacceptedTriage": true, "graceSeconds": 120}
    }
    """.trimIndent()

class AlertRouteRoutesTest {
    private lateinit var organization: SeededAlertRouteOrganization

    @BeforeEach
    fun setUp() {
        AlertRouteTestDatabase.reset()
        organization = AlertRouteTestDatabase.seedOrganization("alert-route-routes")
    }

    @AfterEach
    fun tearDown() {
        AlertRouteTestDatabase.clearReference()
    }

    @Test
    fun `administrators create routes idempotently and read them back`() = testApplication {
        application { installAlertRoutes() }

        val created = createRoute("create-1")
        assertEquals(HttpStatusCode.Created, created.status)
        val body = created.jsonObject()
        assertEquals("dashboard-alerts", body.string("key"))
        assertEquals(1, body["revision"]!!.jsonPrimitive.int)
        assertEquals("AUTOMATIC", body["grouping"]!!.jsonObject.string("behavior"))
        Uuid.parse(body.string("id"))

        val replay = createRoute("create-1")
        assertEquals(HttpStatusCode.OK, replay.status)
        assertEquals(body.string("id"), replay.jsonObject().string("id"))

        val listed = client.get("/v1/on-call/alert-routes") { authorize() }
        assertEquals(HttpStatusCode.OK, listed.status)
        assertEquals(1, listed.jsonArray().size)
    }

    @Test
    fun `route ids must be uuids and unknown routes are not found`() = testApplication {
        application { installAlertRoutes() }

        val numeric = client.get("/v1/on-call/alert-routes/17") { authorize() }
        assertEquals(HttpStatusCode.BadRequest, numeric.status)

        val unknown = client.get("/v1/on-call/alert-routes/${Uuid.random()}") { authorize() }
        assertEquals(HttpStatusCode.NotFound, unknown.status)
    }

    @Test
    fun `updates with a stale expected revision are rejected with conflict`() = testApplication {
        application { installAlertRoutes() }
        val routeId = createRoute("create-1").jsonObject().string("id")

        val first = updateRoute(routeId, expectedRevision = 1, idempotencyKey = "update-1")
        assertEquals(HttpStatusCode.OK, first.status)
        assertEquals(2, first.jsonObject()["revision"]!!.jsonPrimitive.int)

        val stale = updateRoute(routeId, expectedRevision = 1, idempotencyKey = "update-2")
        assertEquals(HttpStatusCode.Conflict, stale.status)

        val revisions = client.get("/v1/on-call/alert-routes/$routeId/revisions") { authorize() }
        assertEquals(HttpStatusCode.OK, revisions.status)
        val revisionBody = revisions.jsonArray()
        assertEquals(listOf("UPDATED", "CREATED"), revisionBody.map { it.jsonObject.string("changeType") })
        assertEquals(
            routeId,
            revisionBody.first().jsonObject["snapshot"]!!.jsonObject["route"]!!.jsonObject.string("id"),
        )
        assertTrue("actorUserId" !in revisionBody.first().jsonObject["snapshot"]!!.jsonObject)
    }

    @Test
    fun `update and delete require an expected revision`() = testApplication {
        application { installAlertRoutes() }
        val routeId = createRoute("create-1").jsonObject().string("id")

        val update =
            client.put("/v1/on-call/alert-routes/$routeId") {
                authorize()
                header(HttpHeaders.ContentType, ContentType.Application.Json)
                header(IDEMPOTENCY_HEADER, "update-without-revision")
                setBody(CREATE_BODY)
            }
        assertEquals(HttpStatusCode.BadRequest, update.status)

        val delete =
            client.delete("/v1/on-call/alert-routes/$routeId") {
                authorize()
                header(IDEMPOTENCY_HEADER, "delete-without-revision")
            }
        assertEquals(HttpStatusCode.BadRequest, delete.status)
    }

    @Test
    fun `over length incident types are rejected as bad requests`() = testApplication {
        application { installAlertRoutes() }
        val response =
            client.post("/v1/on-call/alert-routes") {
                authorize()
                header(HttpHeaders.ContentType, ContentType.Application.Json)
                header(IDEMPOTENCY_HEADER, "long-incident-type")
                setBody(
                    """
                    {
                      "key": "long-incident-type",
                      "name": "Long incident type",
                      "incident": {"create": true, "incidentType": "${"x".repeat(121)}"}
                    }
                    """.trimIndent(),
                )
            }

        assertEquals(HttpStatusCode.BadRequest, response.status)
    }

    @Test
    fun `preview explains match and non-match for a supplied sample`() = testApplication {
        application { installAlertRoutes() }
        createRoute("create-1")

        val matched =
            client.post("/v1/on-call/alert-routes/preview") {
                authorize()
                header(HttpHeaders.ContentType, ContentType.Application.Json)
                setBody("""{"sample": {"source": "DASHBOARD_ALERT", "title": "Checkout latency"}}""")
            }
        assertEquals(HttpStatusCode.OK, matched.status)
        val matchedBody = matched.jsonObject()
        assertEquals("SELECTED", matchedBody["evaluations"]!!.jsonArray.single().jsonObject.string("reason"))
        assertEquals("Checkout latency", matchedBody["decision"]!!.jsonObject["incident"]!!.jsonObject.string("title"))

        val unmatched =
            client.post("/v1/on-call/alert-routes/preview") {
                authorize()
                header(HttpHeaders.ContentType, ContentType.Application.Json)
                setBody("""{"sample": {"source": "HOST_ALERT"}}""")
            }
        assertEquals(HttpStatusCode.OK, unmatched.status)
        val unmatchedBody = unmatched.jsonObject()
        assertTrue(unmatchedBody["decision"] == null || unmatchedBody["decision"].toString() == "null")
        assertEquals(
            "NO_GROUP_MATCHED",
            unmatchedBody["evaluations"]!!.jsonArray.single().jsonObject.string("reason"),
        )

        val invalid =
            client.post("/v1/on-call/alert-routes/preview") {
                authorize()
                header(HttpHeaders.ContentType, ContentType.Application.Json)
                setBody("""{}""")
            }
        assertEquals(HttpStatusCode.BadRequest, invalid.status)
    }

    @Test
    fun `non administrators cannot create routes but may read them`() = testApplication {
        application { installAlertRoutes() }
        createRoute("create-1")
        val memberUserId = AlertRouteTestDatabase.seedUser(organization.organizationId, "member")

        val forbidden =
            client.post("/v1/on-call/alert-routes") {
                authorize(memberUserId)
                header(HttpHeaders.ContentType, ContentType.Application.Json)
                setBody(CREATE_BODY)
            }
        assertEquals(HttpStatusCode.Forbidden, forbidden.status)

        val readable = client.get("/v1/on-call/alert-routes") { authorize(memberUserId) }
        assertEquals(HttpStatusCode.OK, readable.status)
    }

    @Test
    fun `organizations without entitlement are refused`() = testApplication {
        application { installAlertRoutes(AlertRoutePolicy.denyForTests()) }

        val listed = client.get("/v1/on-call/alert-routes") { authorize() }
        assertEquals(HttpStatusCode.Forbidden, listed.status)

        val created = createRoute("create-1")
        assertEquals(HttpStatusCode.Forbidden, created.status)
    }

    private suspend fun ApplicationTestBuilder.createRoute(idempotencyKey: String): HttpResponse =
        client.post("/v1/on-call/alert-routes") {
            authorize()
            header(HttpHeaders.ContentType, ContentType.Application.Json)
            header(IDEMPOTENCY_HEADER, idempotencyKey)
            setBody(CREATE_BODY)
        }

    private suspend fun ApplicationTestBuilder.updateRoute(
        routeId: String,
        expectedRevision: Int,
        idempotencyKey: String,
    ): HttpResponse =
        client.put("/v1/on-call/alert-routes/$routeId") {
            authorize()
            header(HttpHeaders.ContentType, ContentType.Application.Json)
            header(IDEMPOTENCY_HEADER, idempotencyKey)
            setBody(
                """
                {
                  "key": "dashboard-alerts",
                  "name": "Dashboard alerts $idempotencyKey",
                  "expectedRevision": $expectedRevision,
                  "conditionGroups": [
                    {"conditions": [{"field": "source", "operator": "EQUALS", "values": ["DASHBOARD_ALERT"]}]}
                  ]
                }
                """.trimIndent(),
            )
        }

    private fun Application.installAlertRoutes(policy: AlertRoutePolicy = AlertRoutePolicy.allowForTests()) {
        install(ContentNegotiation) { json(Json { ignoreUnknownKeys = true }) }
        install(Authentication) {
            jwt("auth-jwt") {
                verifier(
                    JWT
                        .require(Algorithm.HMAC256(JWT_SECRET))
                        .withIssuer(ISSUER)
                        .withAudience(AUDIENCE)
                        .build(),
                )
                validate { JWTPrincipal(it.payload) }
            }
        }
        routing {
            alertRouteRoutes(
                commandService = AlertRouteCommandService(policy = policy),
                evaluationService = AlertRouteEvaluationService(policy = policy),
            )
        }
    }

    private fun HttpRequestBuilder.authorize(userId: Int = organization.adminUserId) {
        bearerAuth(
            JWT
                .create()
                .withIssuer(ISSUER)
                .withAudience(AUDIENCE)
                .withClaim("userId", userId)
                .withClaim("orgId", organization.organizationId)
                .sign(Algorithm.HMAC256(JWT_SECRET)),
        )
    }

    private fun JsonObject.string(name: String): String = checkNotNull(this[name]).jsonPrimitive.content

    private suspend fun HttpResponse.jsonObject(): JsonObject =
        Json.parseToJsonElement(bodyAsText()).jsonObject

    private suspend fun HttpResponse.jsonArray(): JsonArray =
        Json.parseToJsonElement(bodyAsText()).jsonArray
}
