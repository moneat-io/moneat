// Moneat Enterprise - proprietary module
// Copyright (c) 2026 Moneat. All rights reserved.
// See ee/LICENSE for license terms.

package com.moneat.enterprise.oncall.routes

import com.auth0.jwt.JWT
import com.auth0.jwt.algorithms.Algorithm
import com.moneat.enterprise.incidents.IncidentTestDatabase
import com.moneat.enterprise.incidents.SeededMember
import com.moneat.enterprise.incidents.commands.IncidentCommandPolicy
import com.moneat.enterprise.incidents.commands.IncidentCommandService
import com.moneat.enterprise.oncall.services.OnCallIncidentService
import io.ktor.client.request.bearerAuth
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
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.jsonArray
import kotlinx.serialization.json.jsonObject
import kotlinx.serialization.json.jsonPrimitive
import org.junit.jupiter.api.AfterEach
import org.junit.jupiter.api.BeforeEach
import org.junit.jupiter.api.Test
import kotlin.test.assertEquals
import kotlin.test.assertNotEquals
import kotlin.test.assertTrue
import kotlin.uuid.Uuid

class IncidentRoutesTest {
    private lateinit var member: SeededMember

    @BeforeEach
    fun setUp() {
        IncidentTestDatabase.reset()
        member = IncidentTestDatabase.seedMember("incident-routes")
    }

    @AfterEach
    fun tearDown() {
        IncidentTestDatabase.clearReference()
    }

    @Test
    fun `standalone declaration supports live retrospective test and private incidents`() = testApplication {
        application { installIncidentRoutes() }

        val declarations =
            listOf(
                DeclarationCase("live", "LIVE", "ORGANIZATION"),
                DeclarationCase("retrospective", "RETROSPECTIVE", "ORGANIZATION"),
                DeclarationCase("test", "TEST", "ORGANIZATION"),
                DeclarationCase("private", "LIVE", "PRIVATE"),
            )

        declarations.forEach { declaration ->
            val response = client.post("/v1/on-call/incidents") {
                authorize()
                header(HttpHeaders.ContentType, ContentType.Application.Json)
                header(IDEMPOTENCY_HEADER, "declare-${declaration.name}")
                setBody(
                    """
                    {
                      "title": "${declaration.name}",
                      "severity": "SEV-2",
                      "mode": "${declaration.mode}",
                      "visibility": "${declaration.visibility}"
                    }
                    """.trimIndent(),
                )
            }

            assertEquals(HttpStatusCode.Created, response.status)
            val body = Json.parseToJsonElement(response.bodyAsText()).jsonObject
            assertEquals(declaration.mode, body.stringOrDefault("mode", "LIVE"))
            assertEquals(declaration.visibility, body.stringOrDefault("visibility", "ORGANIZATION"))
            assertEquals(declaration.name, body.requiredString("title"))
            Uuid.parse(body.requiredString("id"))
        }
    }

    @Test
    fun `source references link idempotently after standalone declaration`() = testApplication {
        application { installIncidentRoutes() }
        val incidentId = declareIncident("sources")

        val first = linkSource(
            incidentId = incidentId,
            key = "runbook",
            body = RUNBOOK_SOURCE_JSON,
        )
        val replay = linkSource(
            incidentId = incidentId,
            key = "runbook",
            body = RUNBOOK_SOURCE_JSON,
        )
        val second = linkSource(
            incidentId = incidentId,
            key = "source-message",
            body = """{"sourceType":"SOURCE_MESSAGE","sourceKey":"message-42","label":"Investigation thread"}""",
        )

        assertEquals(1, first.size)
        assertEquals(1, replay.size)
        assertEquals(2, second.size)
        assertNotEquals(first.single().requiredString("id"), second.last().requiredString("id"))

        val listed = client.get("/v1/on-call/incidents/$incidentId/sources") { authorize() }
        assertEquals(HttpStatusCode.OK, listed.status)
        assertEquals(2, Json.parseToJsonElement(listed.bodyAsText()).jsonArray.size)
    }

    @Test
    fun `incident routes reject numeric public IDs`() = testApplication {
        application { installIncidentRoutes() }

        val response = client.get("/v1/on-call/incidents/123") { authorize() }

        assertEquals(HttpStatusCode.BadRequest, response.status)
        assertTrue(response.bodyAsText().contains("Invalid incident ID"))
    }

    private fun Application.installIncidentRoutes() {
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
            incidentRoutes(
                alertServiceProvider = { error("Alert service is not used by standalone incident route tests") },
                onCallIncidentService = OnCallIncidentService(
                    IncidentCommandService(policy = IncidentCommandPolicy.allowForTests()),
                ),
            )
        }
    }

    private suspend fun io.ktor.server.testing.ApplicationTestBuilder.declareIncident(name: String): String {
        val response = client.post("/v1/on-call/incidents") {
            authorize()
            header(HttpHeaders.ContentType, ContentType.Application.Json)
            header(IDEMPOTENCY_HEADER, "declare-$name")
            setBody("""{"title":"$name","severity":"SEV-2"}""")
        }
        assertEquals(HttpStatusCode.Created, response.status)
        return Json.parseToJsonElement(response.bodyAsText()).jsonObject.requiredString("id")
    }

    private suspend fun io.ktor.server.testing.ApplicationTestBuilder.linkSource(
        incidentId: String,
        key: String,
        body: String,
    ): List<JsonObject> {
        val response = client.post("/v1/on-call/incidents/$incidentId/sources") {
            authorize()
            header(HttpHeaders.ContentType, ContentType.Application.Json)
            header(IDEMPOTENCY_HEADER, key)
            setBody(body)
        }
        assertEquals(HttpStatusCode.Created, response.status)
        return Json.parseToJsonElement(response.bodyAsText()).jsonArray.map { it.jsonObject }
    }

    private fun io.ktor.client.request.HttpRequestBuilder.authorize() {
        bearerAuth(
            JWT
                .create()
                .withIssuer(ISSUER)
                .withAudience(AUDIENCE)
                .withClaim("userId", member.userId)
                .withClaim("orgId", member.organizationId)
                .sign(Algorithm.HMAC256(JWT_SECRET)),
        )
    }

    private fun JsonObject.requiredString(name: String): String =
        checkNotNull(this[name]).jsonPrimitive.content

    private fun JsonObject.stringOrDefault(name: String, default: String): String =
        this[name]?.jsonPrimitive?.content ?: default

    private data class DeclarationCase(
        val name: String,
        val mode: String,
        val visibility: String,
    )

    companion object {
        private const val JWT_SECRET = "test-secret-for-incident-routes"
        private const val ISSUER = "moneat"
        private const val AUDIENCE = "moneat-users"
        private const val IDEMPOTENCY_HEADER = "Idempotency-Key"
        private val RUNBOOK_SOURCE_JSON =
            """
            {
              "sourceType": "URL",
              "sourceKey": "runbook",
              "label": "Runbook",
              "sourceUrl": "https://example.test/runbook"
            }
            """.trimIndent()
    }
}
