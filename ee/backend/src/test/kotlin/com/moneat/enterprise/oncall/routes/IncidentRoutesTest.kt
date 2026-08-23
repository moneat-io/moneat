// Moneat Enterprise - proprietary module
// Copyright (c) 2026 Moneat. All rights reserved.
// See ee/LICENSE for license terms.

package com.moneat.enterprise.oncall.routes

import com.auth0.jwt.JWT
import com.auth0.jwt.algorithms.Algorithm
import com.moneat.enterprise.incidents.IncidentTestDatabase
import com.moneat.enterprise.incidents.SeededMember
import com.moneat.enterprise.NativeIncidentRolloutState
import com.moneat.enterprise.NativeIncidentRolloutStatus
import com.moneat.enterprise.NativeIncidentEntitlementStatus
import com.moneat.enterprise.incidents.commands.IncidentCommandPolicy
import com.moneat.enterprise.incidents.commands.IncidentCommandService
import com.moneat.enterprise.incidents.commands.IncidentEntitlement
import com.moneat.enterprise.oncall.services.OnCallIncidentService
import io.ktor.client.request.bearerAuth
import io.ktor.client.request.delete
import io.ktor.client.request.get
import io.ktor.client.request.header
import io.ktor.client.request.patch
import io.ktor.client.request.post
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
import io.ktor.server.testing.testApplication
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.JsonArray
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
    fun `disabled rollout denies native routes and reports preserved external passthrough`() = testApplication {
        application { installIncidentRoutes(rolloutEnabled = false) }

        val incidents = client.get("/v1/on-call/incidents") { authorize() }
        val configuration = client.get("/v1/on-call/incident-configuration/types") { authorize() }
        val alertDeclaration = client.post("/v1/on-call/alerts/not-a-uuid/declare-incident") {
            authorize()
            header(HttpHeaders.ContentType, ContentType.Application.Json)
            setBody("""{"title":"blocked","severity":"SEV-2"}""")
        }
        val capabilities = client.get("/v1/on-call/incident-response/capabilities") { authorize() }

        assertEquals(HttpStatusCode.Forbidden, incidents.status)
        assertEquals(HttpStatusCode.Forbidden, configuration.status)
        assertEquals(HttpStatusCode.Forbidden, alertDeclaration.status)
        assertEquals(HttpStatusCode.OK, capabilities.status)
        val body = capabilities.jsonObject()
        assertEquals(false, body["enabled"]?.jsonPrimitive?.content?.toBoolean())
        assertEquals("production", body.requiredString("environment"))
        assertEquals("DISABLED", body.requiredString("state"))
        assertEquals(true, body["entitlementEnabled"]?.jsonPrimitive?.content?.toBoolean())
        assertEquals("TEST", body.requiredString("plan"))
        assertEquals(false, body["externalProviderPassthroughAffected"]?.jsonPrimitive?.content?.toBoolean())
    }

    @Test
    fun `plan entitlement independently denies native routes`() = testApplication {
        application { installIncidentRoutes(entitlementEnabled = false) }

        val incidents = client.get("/v1/on-call/incidents") { authorize() }
        val capabilities = client.get("/v1/on-call/incident-response/capabilities") { authorize() }

        assertEquals(HttpStatusCode.Forbidden, incidents.status)
        assertEquals(HttpStatusCode.OK, capabilities.status)
        val body = capabilities.jsonObject()
        assertEquals(false, body["enabled"]?.jsonPrimitive?.content?.toBoolean())
        assertEquals("ENABLED", body["state"]?.jsonPrimitive?.content)
        assertEquals(false, body["entitlementEnabled"]?.jsonPrimitive?.content?.toBoolean())
        assertEquals("Upgrade the plan", body.requiredString("entitlementReason"))
        assertEquals(false, body["externalProviderPassthroughAffected"]?.jsonPrimitive?.content?.toBoolean())
    }

    @Test
    fun `rollout gate fails closed when organization context is missing`() = testApplication {
        application { installIncidentRoutes() }

        val response = client.get("/v1/on-call/incidents") { authorizeWithoutOrganization() }

        assertEquals(HttpStatusCode.Unauthorized, response.status)
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

        val lowercaseStatus = client.post("/v1/on-call/incidents") {
            authorize()
            header(HttpHeaders.ContentType, ContentType.Application.Json)
            setBody("""{"title":"lowercase-status","severity":"SEV-2","initialStatus":"active"}""")
        }
        assertEquals(HttpStatusCode.Created, lowercaseStatus.status)
        assertEquals("ACTIVE", lowercaseStatus.jsonObject().requiredString("status"))
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
    fun `source references reject non-http external URLs`() = testApplication {
        application { installIncidentRoutes() }
        val incidentId = declareIncident("unsafe-source")

        val response = client.post("/v1/on-call/incidents/$incidentId/sources") {
            authorize()
            header(HttpHeaders.ContentType, ContentType.Application.Json)
            setBody(
                """
                {
                  "sourceType": "URL",
                  "sourceKey": "unsafe",
                  "sourceUrl": "javascript:alert(1)"
                }
                """.trimIndent(),
            )
        }

        assertEquals(HttpStatusCode.BadRequest, response.status)
        assertTrue(response.bodyAsText().contains("must use HTTP or HTTPS"))
        val listed = client.get("/v1/on-call/incidents/$incidentId/sources") { authorize() }
        assertEquals(0, Json.parseToJsonElement(listed.bodyAsText()).jsonArray.size)
    }

    @Test
    fun `incident routes reject numeric public IDs`() = testApplication {
        application { installIncidentRoutes() }

        val response = client.get("/v1/on-call/incidents/123") { authorize() }

        assertEquals(HttpStatusCode.BadRequest, response.status)
        assertTrue(response.bodyAsText().contains("Invalid incident ID"))
    }

    @Test
    fun `configuration responder and timeline routes expose operational contracts`() = testApplication {
        application { installIncidentRoutes() }
        val type = postJson(
            "/v1/on-call/incident-configuration/types",
            """{"key":"outage","name":"Outage"}""",
        ).jsonObject()
        val field = postJson(
            "/v1/on-call/incident-configuration/fields",
            """
            {
              "key": "customer_impact",
              "name": "Customer impact",
              "valueType": "SELECT",
              "options": [
                {"value":"none","label":"None","position":0},
                {"value":"degraded","label":"Degraded","position":1}
              ]
            }
            """.trimIndent(),
        ).jsonObject()
        val form = postJson(
            "/v1/on-call/incident-configuration/forms",
            """
            {
              "incidentTypeId": "${type.requiredString("id")}",
              "stage": "DECLARATION",
              "name": "Declare outage",
              "fields": [
                {
                  "fieldId": "${field.requiredString("id")}",
                  "position": 0,
                  "required": true,
                  "defaultValue": "none",
                  "helpText": "Choose the observed impact."
                }
              ]
            }
            """.trimIndent(),
        ).jsonObject()

        assertEquals("Outage", type.requiredString("name"))
        assertEquals("SELECT", field.requiredString("valueType"))
        assertEquals("DECLARATION", form.requiredString("stage"))
        assertEquals(1, getJsonArray("/v1/on-call/incident-configuration/types").size)
        assertEquals(1, getJsonArray("/v1/on-call/incident-configuration/fields").size)
        assertEquals(1, getJsonArray("/v1/on-call/incident-configuration/forms?stage=DECLARATION").size)

        val incidentId = declareIncident("operational-contracts")
        val roles = getJsonArray("/v1/on-call/incident-configuration/roles")
        val commander = roles.single { it.jsonObject.requiredString("key") == "incident-commander" }.jsonObject
        assertTrue("privateInstructions" !in commander)
        val privateRoles = getJsonArray(
            "/v1/on-call/incident-configuration/roles?includePrivateInstructions=true",
        )
        assertTrue(
            "privateInstructions" in
                privateRoles.single { it.jsonObject.requiredString("key") == "incident-commander" }.jsonObject,
        )
        val assignments = postJson(
            "/v1/on-call/incidents/$incidentId/roles/${commander.requiredString("id")}/claim",
            """{"expectedVersion":1}""",
            "claim-commander",
        ).jsonArray()
        assertEquals(1, assignments.size)
        assertTrue("privateInstructions" !in assignments.single().jsonObject.getValue("role").jsonObject)

        val participants = postJson(
            "/v1/on-call/incidents/$incidentId/participants",
            """{"expectedVersion":2}""",
            "join-response",
        ).jsonArray()
        assertEquals("PARTICIPANT", participants.single().jsonObject.requiredString("type"))

        val timelinePath = "/v1/on-call/incidents/$incidentId/timeline"
        val timeline = getJsonArray(timelinePath)
        assertEquals(2, timeline.size)
        val eventId = timeline.last().jsonObject.requiredString("id")
        val annotated = postJson(
            "$timelinePath/$eventId/annotation",
            """{"annotation":"Verified by route test","reason":"Add evidence"}""",
        ).jsonObject()
        assertEquals("Verified by route test", annotated.requiredString("annotation"))

        val editedResponse = client.patch("$timelinePath/$eventId") {
            authorize()
            header(HttpHeaders.ContentType, ContentType.Application.Json)
            setBody("""{"visibility":"PARTICIPANTS","reason":"Limit evidence"}""")
        }
        assertEquals(HttpStatusCode.OK, editedResponse.status)
        assertEquals("PARTICIPANTS", editedResponse.jsonObject().requiredString("visibility"))

        val revisions = getJsonArray("$timelinePath/$eventId/revisions")
        assertEquals(listOf("ANNOTATE", "EDIT"), revisions.map { it.jsonObject.requiredString("action") })

        val deleted = client.delete("$timelinePath/$eventId") {
            authorize()
            header(HttpHeaders.ContentType, ContentType.Application.Json)
            setBody("""{"reason":"Temporary cleanup"}""")
        }
        assertEquals(HttpStatusCode.OK, deleted.status)
        assertEquals(1, getJsonArray(timelinePath).size)
        assertEquals(2, getJsonArray("$timelinePath?includeDeleted=true").size)

        val restored = postJson(
            "$timelinePath/$eventId/restore",
            """{"reason":"Restore evidence"}""",
        )
        assertEquals(HttpStatusCode.OK, restored.status)
        assertEquals(2, restoredTimelineExport(incidentId).getValue("events").jsonArray.size)
    }

    @Test
    fun `configuration and incident mutation routes enforce responder permissions`() = testApplication {
        application { installIncidentRoutes() }
        val regularUserId = IncidentTestDatabase.seedUserInOrganization(member.organizationId, "regular-responder")
        val incidentId = declareIncident("route-permissions")
        val timelinePath = "/v1/on-call/incidents/$incidentId/timeline"
        val eventId = getJsonArray(timelinePath).single().jsonObject.requiredString("id")

        val configurationWrite = client.post("/v1/on-call/incident-configuration/types") {
            authorize(regularUserId)
            header(HttpHeaders.ContentType, ContentType.Application.Json)
            setBody("""{"key":"unauthorized","name":"Unauthorized"}""")
        }
        assertEquals(HttpStatusCode.Forbidden, configurationWrite.status)

        val privateRoleRead = client.get(
            "/v1/on-call/incident-configuration/roles?includePrivateInstructions=true",
        ) { authorize(regularUserId) }
        assertEquals(HttpStatusCode.Forbidden, privateRoleRead.status)

        val forbiddenTimelineEdit = client.patch("$timelinePath/$eventId") {
            authorize(regularUserId)
            header(HttpHeaders.ContentType, ContentType.Application.Json)
            setBody("""{"annotation":"not allowed"}""")
        }
        assertEquals(HttpStatusCode.Forbidden, forbiddenTimelineEdit.status)

        val joined = client.post("/v1/on-call/incidents/$incidentId/participants") {
            authorize(regularUserId)
            header(HttpHeaders.ContentType, ContentType.Application.Json)
            setBody("""{"expectedVersion":1}""")
        }
        assertEquals(HttpStatusCode.OK, joined.status)
        val regularPublicUserId = joined.jsonArray().single().jsonObject.requiredString("userId")

        val allowedTimelineEdit = client.patch("$timelinePath/$eventId") {
            authorize(regularUserId)
            header(HttpHeaders.ContentType, ContentType.Application.Json)
            setBody("""{"visibility":"PARTICIPANTS"}""")
        }
        assertEquals(HttpStatusCode.OK, allowedTimelineEdit.status)

        val ownerJoined = client.post("/v1/on-call/incidents/$incidentId/participants") {
            authorize()
            header(HttpHeaders.ContentType, ContentType.Application.Json)
            setBody("""{"expectedVersion":2}""")
        }
        assertEquals(HttpStatusCode.OK, ownerJoined.status)
        val ownerPublicUserId =
            ownerJoined.jsonArray()
                .map { it.jsonObject.requiredString("userId") }
                .single { it != regularPublicUserId }

        val forbiddenRemoval = client.delete("/v1/on-call/incidents/$incidentId/participants/$ownerPublicUserId") {
            authorize(regularUserId)
        }
        assertEquals(HttpStatusCode.Forbidden, forbiddenRemoval.status)

        val selfRemoval = client.delete("/v1/on-call/incidents/$incidentId/participants/$regularPublicUserId") {
            authorize(regularUserId)
        }
        assertEquals(HttpStatusCode.OK, selfRemoval.status)
    }

    private fun Application.installIncidentRoutes(
        rolloutEnabled: Boolean = true,
        entitlementEnabled: Boolean = true,
    ) {
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
                incidentEntitlement = IncidentEntitlement { rolloutEnabled && entitlementEnabled },
                rolloutStatusProvider = {
                    NativeIncidentRolloutStatus(
                        enabled = rolloutEnabled,
                        environment = "production",
                        state =
                            if (rolloutEnabled) {
                                NativeIncidentRolloutState.ENABLED
                            } else {
                                NativeIncidentRolloutState.DISABLED
                            },
                    )
                },
                entitlementStatusProvider = {
                    NativeIncidentEntitlementStatus(
                        enabled = entitlementEnabled,
                        plan = "TEST",
                        reason = if (entitlementEnabled) null else "Upgrade the plan",
                    )
                },
            )
        }
    }

    private suspend fun io.ktor.server.testing.ApplicationTestBuilder.postJson(
        path: String,
        body: String,
        idempotencyKey: String? = null,
    ): HttpResponse =
        client.post(path) {
            authorize()
            header(HttpHeaders.ContentType, ContentType.Application.Json)
            idempotencyKey?.let { header(IDEMPOTENCY_HEADER, it) }
            setBody(body)
        }.also { response ->
            assertTrue(response.status == HttpStatusCode.OK || response.status == HttpStatusCode.Created)
        }

    private suspend fun io.ktor.server.testing.ApplicationTestBuilder.getJsonArray(path: String): JsonArray {
        val response = client.get(path) { authorize() }
        assertEquals(HttpStatusCode.OK, response.status)
        return response.jsonArray()
    }

    private suspend fun io.ktor.server.testing.ApplicationTestBuilder.restoredTimelineExport(
        incidentId: String,
    ): JsonObject {
        val response = client.get("/v1/on-call/incidents/$incidentId/timeline/export") { authorize() }
        assertEquals(HttpStatusCode.OK, response.status)
        return response.jsonObject()
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

    private fun io.ktor.client.request.HttpRequestBuilder.authorize(userId: Int = member.userId) {
        bearerAuth(
            JWT
                .create()
                .withIssuer(ISSUER)
                .withAudience(AUDIENCE)
                .withClaim("userId", userId)
                .withClaim("orgId", member.organizationId)
                .sign(Algorithm.HMAC256(JWT_SECRET)),
        )
    }

    private fun io.ktor.client.request.HttpRequestBuilder.authorizeWithoutOrganization() {
        bearerAuth(
            JWT
                .create()
                .withIssuer(ISSUER)
                .withAudience(AUDIENCE)
                .withClaim("userId", member.userId)
                .sign(Algorithm.HMAC256(JWT_SECRET)),
        )
    }

    private fun JsonObject.requiredString(name: String): String =
        checkNotNull(this[name]).jsonPrimitive.content

    private fun JsonObject.stringOrDefault(name: String, default: String): String =
        this[name]?.jsonPrimitive?.content ?: default

    private suspend fun HttpResponse.jsonObject(): JsonObject =
        Json.parseToJsonElement(bodyAsText()).jsonObject

    private suspend fun HttpResponse.jsonArray(): JsonArray =
        Json.parseToJsonElement(bodyAsText()).jsonArray

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
