// Moneat Enterprise - proprietary module
// Copyright (c) 2026 Moneat. All rights reserved.
// See ee/LICENSE for license terms.

package com.moneat.enterprise.alertroutes.routes

import com.auth0.jwt.JWT
import com.auth0.jwt.algorithms.Algorithm
import com.moneat.enterprise.alertroutes.AlertRouteTestDatabase
import com.moneat.enterprise.alertroutes.SeededAlertRouteOrganization
import com.moneat.enterprise.alertroutes.commands.AlertGroupPolicy
import com.moneat.enterprise.alertroutes.commands.AlertRouteActor
import com.moneat.enterprise.alertroutes.commands.AlertRouteGroupingInput
import com.moneat.enterprise.alertroutes.commands.AlertRoutePolicy
import com.moneat.enterprise.alertroutes.commands.AlertRouteSpecification
import com.moneat.enterprise.alertroutes.commands.CreateAlertRouteCommand
import com.moneat.enterprise.alertroutes.context.AlertRouteContext
import com.moneat.enterprise.alertroutes.context.AlertRouteEpisodeIdentity
import com.moneat.enterprise.alertroutes.evaluation.AlertRouteEvaluator
import com.moneat.enterprise.alertroutes.services.AlertGroupCommandService
import com.moneat.enterprise.alertroutes.services.AlertGroupService
import com.moneat.enterprise.alertroutes.services.AlertRouteCommandService
import com.moneat.enterprise.incidents.commands.IncidentCommandPolicy
import com.moneat.enterprise.incidents.commands.IncidentCommandService
import io.ktor.client.request.HttpRequestBuilder
import io.ktor.client.request.bearerAuth
import io.ktor.client.request.delete
import io.ktor.client.request.get
import io.ktor.client.request.header
import io.ktor.client.request.post
import io.ktor.client.request.setBody
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
import io.ktor.server.testing.testApplication
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.jsonArray
import kotlinx.serialization.json.jsonObject
import kotlinx.serialization.json.jsonPrimitive
import org.junit.jupiter.api.AfterEach
import org.junit.jupiter.api.BeforeEach
import org.junit.jupiter.api.Test
import kotlin.test.assertEquals
import kotlin.test.assertNotNull
import kotlin.time.Instant
import kotlin.uuid.Uuid

private const val GROUP_JWT_SECRET = "alert-group-route-test-secret"
private const val GROUP_ISSUER = "moneat"
private const val GROUP_AUDIENCE = "moneat-dashboard"

class AlertGroupRoutesTest {
    private lateinit var organization: SeededAlertRouteOrganization
    private lateinit var groupService: AlertGroupService

    @BeforeEach
    fun setUp() {
        AlertRouteTestDatabase.reset()
        organization = AlertRouteTestDatabase.seedOrganization("alert-group-routes")
        groupService = AlertGroupService(AlertGroupPolicy.allowForTests())
    }

    @AfterEach
    fun tearDown() = AlertRouteTestDatabase.clearReference()

    @Test
    fun `groups list and resolve only through organization scoped UUIDs`() = testApplication {
        val (groupId, _) = createGroup()
        application { installAlertGroupRoutes() }

        val listed = client.get("/v1/on-call/alert-groups") { authorize() }
        assertEquals(HttpStatusCode.OK, listed.status)
        assertEquals(groupId.toString(), listed.json().jsonArray.single().jsonObject.string("id"))

        val found = client.get("/v1/on-call/alert-groups/$groupId") { authorize() }
        assertEquals(HttpStatusCode.OK, found.status)
        assertEquals("NONE", found.json().jsonObject.string("pagingMode"))

        assertEquals(
            HttpStatusCode.BadRequest,
            client.get("/v1/on-call/alert-groups/17") { authorize() }.status,
        )
        assertEquals(
            HttpStatusCode.NotFound,
            client.get("/v1/on-call/alert-groups/${Uuid.random()}") { authorize() }.status,
        )
    }

    @Test
    fun `responder episode decisions require expected versions and are idempotent`() = testApplication {
        val (groupId, episodeId) = createGroup()
        application { installAlertGroupRoutes() }

        val unrelated = client.post("/v1/on-call/alert-groups/$groupId/episodes/$episodeId/unrelated") {
            authorize()
            jsonBody("""{"expectedVersion":1}""", "unrelated-route")
        }
        assertEquals(HttpStatusCode.OK, unrelated.status)
        val member = unrelated.json().jsonObject["members"]!!.jsonArray.single().jsonObject
        assertEquals("UNRELATED", member.string("state"))

        val replay = client.post("/v1/on-call/alert-groups/$groupId/episodes/$episodeId/unrelated") {
            authorize()
            jsonBody("""{"expectedVersion":1}""", "unrelated-route")
        }
        assertEquals(HttpStatusCode.OK, replay.status)

        val missingVersion = client.delete("/v1/on-call/alert-groups/$groupId/episodes/$episodeId") {
            authorize()
        }
        assertEquals(HttpStatusCode.BadRequest, missingVersion.status)
    }

    @Test
    fun `responder creates a triage incident from an alert only group`() = testApplication {
        val (groupId, _) = createGroup()
        application { installAlertGroupRoutes() }

        val response = client.post("/v1/on-call/alert-groups/$groupId/create-triage") {
            authorize()
            jsonBody(
                """{"expectedVersion":1,"title":"Checkout unavailable"}""",
                "create-triage-route",
            )
        }

        assertEquals(HttpStatusCode.Created, response.status)
        assertNotNull(response.json().jsonObject["incidentId"]?.jsonPrimitive?.content)
    }

    private fun createGroup(): Pair<Uuid, Uuid> {
        val route = AlertRouteCommandService(policy = AlertRoutePolicy.allowForTests()).execute(
            CreateAlertRouteCommand(
                commandKey = "route-${Uuid.random()}",
                actor = AlertRouteActor(organization.organizationId, organization.adminUserId, "TEST"),
                specification = AlertRouteSpecification(
                    key = "group-${Uuid.random()}",
                    name = "Route API grouping",
                    grouping = AlertRouteGroupingInput(keys = listOf("metadata.service")),
                ),
            ),
        ).route!!
        val episodeId = AlertRouteTestDatabase.seedAlertEpisode(
            organization.organizationId,
            "DASHBOARD_ALERT",
            "route-api-${Uuid.random()}",
        )
        val context = AlertRouteContext(
            organizationId = organization.organizationId,
            source = "DASHBOARD_ALERT",
            status = "FIRING",
            priority = "P1",
            title = "Checkout alert",
            description = "Latency above threshold",
            deduplicationKey = episodeId.toString(),
            url = "",
            episode = AlertRouteEpisodeIdentity(episodeId.toString(), "$episodeId#1", 1, "OPEN"),
            metadata = mapOf("service" to "checkout"),
        )
        val decision = AlertRouteEvaluator().evaluate(listOf(route), context).decision!!
        val group = groupService.recordFiring(
            context,
            decision,
            episodeId,
            now = Instant.parse("2026-08-23T04:00:00Z"),
        )
        return group.id to episodeId
    }

    private fun Application.installAlertGroupRoutes() {
        install(ContentNegotiation) { json(Json { ignoreUnknownKeys = true }) }
        install(Authentication) {
            jwt("auth-jwt") {
                verifier(
                    JWT.require(Algorithm.HMAC256(GROUP_JWT_SECRET))
                        .withIssuer(GROUP_ISSUER)
                        .withAudience(GROUP_AUDIENCE)
                        .build(),
                )
                validate { JWTPrincipal(it.payload) }
            }
        }
        val incidentService = IncidentCommandService(policy = IncidentCommandPolicy.allowForTests())
        routing {
            alertGroupRoutes(
                groupService,
                AlertGroupCommandService(AlertGroupPolicy.allowForTests(), incidentService),
            )
        }
    }

    private fun HttpRequestBuilder.authorize() {
        bearerAuth(
            JWT.create()
                .withIssuer(GROUP_ISSUER)
                .withAudience(GROUP_AUDIENCE)
                .withClaim("userId", organization.adminUserId)
                .withClaim("orgId", organization.organizationId)
                .sign(Algorithm.HMAC256(GROUP_JWT_SECRET)),
        )
    }

    private fun HttpRequestBuilder.jsonBody(body: String, idempotencyKey: String) {
        header(HttpHeaders.ContentType, ContentType.Application.Json)
        header("Idempotency-Key", idempotencyKey)
        setBody(body)
    }

    private suspend fun io.ktor.client.statement.HttpResponse.json() =
        Json.parseToJsonElement(bodyAsText())

    private fun kotlinx.serialization.json.JsonObject.string(name: String): String =
        checkNotNull(this[name]).jsonPrimitive.content
}
