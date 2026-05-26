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

package com.moneat.featureflags

import com.moneat.featureflags.models.CreateFeatureFlagSdkKeyResponse
import com.moneat.featureflags.models.FeatureFlagAnalyticsResponse
import com.moneat.featureflags.models.FeatureFlagAuditEventResponse
import com.moneat.featureflags.models.FeatureFlagConfigResponse
import com.moneat.featureflags.models.FeatureFlagEnvironmentConfigSnapshot
import com.moneat.featureflags.models.FeatureFlagEnvironmentResponse
import com.moneat.featureflags.models.FeatureFlagEnvironmentSnapshot
import com.moneat.featureflags.models.FeatureFlagListResponse
import com.moneat.featureflags.models.FeatureFlagResponse
import com.moneat.featureflags.models.FeatureFlagSdkKeyPrincipal
import com.moneat.featureflags.models.FeatureFlagSdkKeyResponse
import com.moneat.featureflags.models.FeatureFlagSegmentResponse
import com.moneat.featureflags.models.FeatureFlagValueType
import com.moneat.featureflags.models.FeatureFlagVariantResponse
import com.moneat.featureflags.models.FeatureFlagVariantSnapshot
import com.moneat.featureflags.models.OfrepFlagEvaluationResponse
import com.moneat.featureflags.routes.featureFlagRoutes
import com.moneat.featureflags.services.FeatureFlagEvaluator
import com.moneat.featureflags.services.FeatureFlagEventService
import com.moneat.featureflags.services.FeatureFlagService
import com.moneat.testsupport.RouteTestSupport
import com.moneat.testsupport.RouteTestSupport.installJwtAuth
import com.moneat.testsupport.RouteTestSupport.withAuth
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
import io.ktor.http.contentType
import io.ktor.server.application.Application
import io.ktor.server.routing.routing
import io.ktor.server.testing.testApplication
import io.mockk.coEvery
import io.mockk.every
import io.mockk.mockk
import kotlinx.serialization.json.JsonArray
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.JsonPrimitive
import org.junit.jupiter.api.Test
import kotlin.test.assertEquals
import kotlin.test.assertTrue

private const val ROUTE_TEST_ORG_ID = 10
private const val ROUTE_TEST_USER_ID = 20
private const val SDK_TOKEN = "mffpk_route_test_token"
private const val FLAG_PATH = "/v1/feature-flags/checkout.enabled"

class FeatureFlagRoutesTest {
    @Test
    fun `management routes delegate to feature flag service`() = testApplication {
        val service = mockManagementService()
        application { installFeatureFlagRoutes(service) }
        val token = RouteTestSupport.createToken(ROUTE_TEST_USER_ID, ROUTE_TEST_ORG_ID)

        assertEquals(HttpStatusCode.OK, client.get("/v1/feature-flags/environments") { withAuth(token) }.status)
        assertEquals(
            HttpStatusCode.Created,
            client.post("/v1/feature-flags/environments") {
                withJsonAuth(token)
                setBody("""{"key":"qa","name":"QA"}""")
            }.status
        )
        assertEquals(HttpStatusCode.OK, client.get("/v1/feature-flags/segments") { withAuth(token) }.status)
        assertEquals(
            HttpStatusCode.OK,
            client.post("/v1/feature-flags/segments") {
                withJsonAuth(token)
                setBody("""{"key":"beta","name":"Beta"}""")
            }.status
        )
        assertEquals(
            HttpStatusCode.NoContent,
            client.delete("/v1/feature-flags/segments/beta") { withAuth(token) }.status
        )
        assertEquals(HttpStatusCode.OK, client.get("/v1/feature-flags/sdk-keys") { withAuth(token) }.status)
        assertEquals(
            HttpStatusCode.Created,
            client.post("/v1/feature-flags/sdk-keys") {
                withJsonAuth(token)
                setBody("""{"environmentKey":"production","name":"Browser","keyType":"client"}""")
            }.status
        )
        assertEquals(HttpStatusCode.NoContent, client.delete("/v1/feature-flags/sdk-keys/1") { withAuth(token) }.status)
        assertEquals(HttpStatusCode.OK, client.get("/v1/feature-flags/audit?limit=5") { withAuth(token) }.status)
        assertEquals(
            HttpStatusCode.OK,
            client.get("/v1/feature-flags/analytics?environment=production&hours=12") { withAuth(token) }.status
        )
        assertEquals(
            HttpStatusCode.OK,
            client.get("/v1/feature-flags?environment=production") { withAuth(token) }.status
        )
        assertEquals(
            HttpStatusCode.Created,
            client.post("/v1/feature-flags") {
                withJsonAuth(token)
                setBody(createFlagBody())
            }.status
        )
        assertEquals(HttpStatusCode.OK, client.get("$FLAG_PATH?environment=production") { withAuth(token) }.status)
        assertEquals(
            HttpStatusCode.OK,
            client.put(FLAG_PATH) {
                withJsonAuth(token)
                setBody("""{"name":"Checkout Rollout"}""")
            }.status
        )
        assertEquals(HttpStatusCode.NoContent, client.delete(FLAG_PATH) { withAuth(token) }.status)
        assertEquals(
            HttpStatusCode.OK,
            client.put("$FLAG_PATH/config/production") {
                withJsonAuth(token)
                setBody("""{"enabled":true,"defaultVariantKey":"on","offVariantKey":"off"}""")
            }.status
        )
    }

    @Test
    fun `management routes return validation and not found responses`() = testApplication {
        val service = mockManagementService()
        every { service.deleteSegment(ROUTE_TEST_ORG_ID, ROUTE_TEST_USER_ID, "missing") } returns false
        every { service.revokeSdkKey(ROUTE_TEST_ORG_ID, ROUTE_TEST_USER_ID, 2) } returns false
        every { service.getFlag(ROUTE_TEST_ORG_ID, "missing", null) } returns null
        every { service.updateFlag(ROUTE_TEST_ORG_ID, ROUTE_TEST_USER_ID, "missing", any()) } returns null
        every { service.archiveFlag(ROUTE_TEST_ORG_ID, ROUTE_TEST_USER_ID, "missing") } returns false
        every {
            service.updateConfig(ROUTE_TEST_ORG_ID, ROUTE_TEST_USER_ID, "missing", "production", any())
        } returns null
        application { installFeatureFlagRoutes(service) }
        val token = RouteTestSupport.createToken(ROUTE_TEST_USER_ID, ROUTE_TEST_ORG_ID)

        assertEquals(HttpStatusCode.Unauthorized, client.get("/v1/feature-flags").status)
        assertEquals(
            HttpStatusCode.NotFound,
            client.delete("/v1/feature-flags/segments/missing") { withAuth(token) }.status
        )
        assertEquals(
            HttpStatusCode.BadRequest,
            client.delete("/v1/feature-flags/sdk-keys/not-a-number") {
                withAuth(token)
            }.status
        )
        assertEquals(
            HttpStatusCode.NotFound,
            client.delete("/v1/feature-flags/sdk-keys/2") { withAuth(token) }.status
        )
        assertEquals(HttpStatusCode.NotFound, client.get("/v1/feature-flags/missing") { withAuth(token) }.status)
        assertEquals(
            HttpStatusCode.NotFound,
            client.put("/v1/feature-flags/missing") {
                withJsonAuth(token)
                setBody("""{"name":"Missing"}""")
            }.status
        )
        assertEquals(HttpStatusCode.NotFound, client.delete("/v1/feature-flags/missing") { withAuth(token) }.status)
        assertEquals(
            HttpStatusCode.NotFound,
            client.put("/v1/feature-flags/missing/config/production") {
                withJsonAuth(token)
                setBody("""{"enabled":true}""")
            }.status
        )
    }

    @Test
    fun `ofrep routes evaluate flags and track events`() = testApplication {
        val service = mockk<FeatureFlagService>()
        val evaluator = mockk<FeatureFlagEvaluator>()
        val eventService = mockk<FeatureFlagEventService>(relaxed = true)
        val snapshot = snapshot()
        every { service.validateSdkKey(SDK_TOKEN) } returns sdkPrincipal()
        every { service.getSnapshot(ROUTE_TEST_ORG_ID, "production") } returns snapshot
        every {
            evaluator.evaluate(snapshot, "checkout.enabled", any(), FeatureFlagValueType.BOOLEAN, "client")
        } returns evaluationResponse()
        every { evaluator.evaluateAll(snapshot, any(), "client", listOf("checkout.enabled")) } returns
            listOf(evaluationResponse())
        application { installFeatureFlagRoutes(service, evaluator, eventService) }

        val evaluate = client.post("/ofrep/v1/evaluate/flags/checkout.enabled") {
            withSdkJson()
            setBody("""{"context":{"targetingKey":"user-1"},"type":"BOOLEAN"}""")
        }
        assertEquals(HttpStatusCode.OK, evaluate.status)
        assertEquals(snapshot.etag, evaluate.headers[HttpHeaders.ETag])
        assertTrue(evaluate.bodyAsText().contains("checkout.enabled"))

        val cached = client.post("/ofrep/v1/evaluate/flags/checkout.enabled") {
            withSdkJson()
            header(HttpHeaders.IfNoneMatch, snapshot.etag)
            setBody("""{"context":{"targetingKey":"user-1"},"type":"BOOLEAN"}""")
        }
        assertEquals(HttpStatusCode.NotModified, cached.status)

        val bulk = client.post("/ofrep/v1/evaluate/flags") {
            withSdkJson()
            setBody("""{"context":{"targetingKey":"user-1"},"flagKeys":["checkout.enabled"]}""")
        }
        assertEquals(HttpStatusCode.OK, bulk.status)
        assertTrue(bulk.bodyAsText().contains("checkout.enabled"))

        val track = client.post("/ofrep/v1/track") {
            withSdkJson()
            setBody("""{"eventName":"checkout.started","context":{"targetingKey":"user-1"}}""")
        }
        assertEquals(HttpStatusCode.Accepted, track.status)
    }

    @Test
    fun `ofrep routes return auth snapshot and evaluation errors`() = testApplication {
        val service = mockk<FeatureFlagService>()
        val evaluator = mockk<FeatureFlagEvaluator>()
        val eventService = mockk<FeatureFlagEventService>(relaxed = true)
        val snapshot = snapshot()
        every { service.validateSdkKey("bad-token") } returns null
        every { service.validateSdkKey(SDK_TOKEN) } returns sdkPrincipal()
        every { service.getSnapshot(ROUTE_TEST_ORG_ID, "production") } returnsMany listOf(null, snapshot, snapshot)
        every {
            evaluator.evaluate(snapshot, "checkout.enabled", any(), FeatureFlagValueType.STRING, "client")
        } returns errorResponse("TYPE_MISMATCH")
        every { evaluator.evaluateAll(snapshot, any(), "client", null) } returns listOf(errorResponse("FLAG_NOT_FOUND"))
        application { installFeatureFlagRoutes(service, evaluator, eventService) }

        assertEquals(HttpStatusCode.Unauthorized, client.post("/ofrep/v1/evaluate/flags/checkout.enabled").status)
        assertEquals(
            HttpStatusCode.Unauthorized,
            client.post("/ofrep/v1/evaluate/flags/checkout.enabled") {
                bearerAuth("bad-token")
            }.status
        )
        assertEquals(
            HttpStatusCode.NotFound,
            client.post("/ofrep/v1/evaluate/flags/checkout.enabled") {
                withSdkJson()
                setBody("""{"context":{"targetingKey":"user-1"}}""")
            }.status
        )
        assertEquals(
            HttpStatusCode.BadRequest,
            client.post("/ofrep/v1/evaluate/flags/checkout.enabled") {
                withSdkJson()
                setBody("""{"context":{"targetingKey":"user-1"},"type":"STRING"}""")
            }.status
        )
        assertEquals(
            HttpStatusCode.OK,
            client.post("/ofrep/v1/evaluate/flags") {
                withSdkJson()
                setBody("""{"context":{"targetingKey":"user-1"}}""")
            }.status
        )
        assertEquals(
            HttpStatusCode.BadRequest,
            client.post("/ofrep/v1/track") {
                withSdkJson()
                setBody("""{"eventName":""}""")
            }.status
        )
    }

    private fun Application.installFeatureFlagRoutes(
        service: FeatureFlagService,
        evaluator: FeatureFlagEvaluator = mockk(relaxed = true),
        eventService: FeatureFlagEventService = mockk(relaxed = true),
    ) {
        installJwtAuth()
        routing { featureFlagRoutes(service, evaluator, eventService) }
    }

    private fun mockManagementService(): FeatureFlagService {
        val service = mockk<FeatureFlagService>()
        every { service.listEnvironments(ROUTE_TEST_ORG_ID) } returns listOf(environment())
        every {
            service.createEnvironment(ROUTE_TEST_ORG_ID, ROUTE_TEST_USER_ID, any())
        } returns environment("qa", "QA")
        every { service.listSegments(ROUTE_TEST_ORG_ID) } returns listOf(segment())
        every { service.upsertSegment(ROUTE_TEST_ORG_ID, ROUTE_TEST_USER_ID, any()) } returns segment()
        every { service.deleteSegment(ROUTE_TEST_ORG_ID, ROUTE_TEST_USER_ID, "beta") } returns true
        every { service.listSdkKeys(ROUTE_TEST_ORG_ID) } returns listOf(sdkKey())
        every { service.createSdkKey(ROUTE_TEST_ORG_ID, ROUTE_TEST_USER_ID, any()) } returns createdSdkKey()
        every { service.revokeSdkKey(ROUTE_TEST_ORG_ID, ROUTE_TEST_USER_ID, 1) } returns true
        every { service.listAuditEvents(ROUTE_TEST_ORG_ID, 5) } returns listOf(auditEvent())
        coEvery { service.analytics(ROUTE_TEST_ORG_ID, "production", 12) } returns analytics()
        every { service.listFlags(ROUTE_TEST_ORG_ID, "production") } returns flagList()
        every { service.createFlag(ROUTE_TEST_ORG_ID, ROUTE_TEST_USER_ID, any()) } returns flag()
        every { service.getFlag(ROUTE_TEST_ORG_ID, "checkout.enabled", "production") } returns flag()
        every { service.updateFlag(ROUTE_TEST_ORG_ID, ROUTE_TEST_USER_ID, "checkout.enabled", any()) } returns flag()
        every { service.archiveFlag(ROUTE_TEST_ORG_ID, ROUTE_TEST_USER_ID, "checkout.enabled") } returns true
        every {
            service.updateConfig(ROUTE_TEST_ORG_ID, ROUTE_TEST_USER_ID, "checkout.enabled", "production", any())
        } returns config()
        return service
    }

    private fun io.ktor.client.request.HttpRequestBuilder.withJsonAuth(token: String) {
        withAuth(token)
        contentType(ContentType.Application.Json)
    }

    private fun io.ktor.client.request.HttpRequestBuilder.withSdkJson() {
        bearerAuth(SDK_TOKEN)
        contentType(ContentType.Application.Json)
    }

    private fun createFlagBody(): String =
        """
        {
          "key":"checkout.enabled",
          "name":"Checkout Enabled",
          "valueType":"BOOLEAN",
          "clientVisible":true,
          "variants":[{"key":"off","value":false},{"key":"on","value":true}],
          "defaultVariantKey":"on",
          "offVariantKey":"off"
        }
        """.trimIndent()

    private fun flagList(): FeatureFlagListResponse =
        FeatureFlagListResponse(environments = listOf(environment()), flags = listOf(flag()))

    private fun flag(): FeatureFlagResponse =
        FeatureFlagResponse(
            id = 1,
            key = "checkout.enabled",
            name = "Checkout Enabled",
            valueType = FeatureFlagValueType.BOOLEAN,
            clientVisible = true,
            tags = listOf("checkout"),
            variants = listOf(variant()),
            configs = listOf(config()),
            createdAt = "2026-01-01T00:00:00Z",
            updatedAt = "2026-01-01T00:00:00Z",
        )

    private fun environment(key: String = "production", name: String = "Production"): FeatureFlagEnvironmentResponse =
        FeatureFlagEnvironmentResponse(
            id = 1,
            key = key,
            name = name,
            version = 1,
            createdAt = "2026-01-01T00:00:00Z",
            updatedAt = "2026-01-01T00:00:00Z",
        )

    private fun variant(): FeatureFlagVariantResponse =
        FeatureFlagVariantResponse(id = 1, key = "on", name = "On", value = JsonPrimitive(true), sortOrder = 0)

    private fun config(): FeatureFlagConfigResponse =
        FeatureFlagConfigResponse(
            environmentKey = "production",
            environmentName = "Production",
            enabled = true,
            defaultVariantKey = "on",
            offVariantKey = "off",
            rules = emptyRules(),
            version = 1,
            updatedAt = "2026-01-01T00:00:00Z",
        )

    private fun segment(): FeatureFlagSegmentResponse =
        FeatureFlagSegmentResponse(
            id = 1,
            key = "beta",
            name = "Beta",
            conditions = emptyRules(),
            createdAt = "2026-01-01T00:00:00Z",
            updatedAt = "2026-01-01T00:00:00Z",
        )

    private fun sdkKey(): FeatureFlagSdkKeyResponse =
        FeatureFlagSdkKeyResponse(
            id = 1,
            environmentKey = "production",
            name = "Browser",
            keyType = "client",
            keyPrefix = "mffpk_route_test",
            createdAt = "2026-01-01T00:00:00Z",
        )

    private fun createdSdkKey(): CreateFeatureFlagSdkKeyResponse =
        CreateFeatureFlagSdkKeyResponse(
            id = 1,
            environmentKey = "production",
            name = "Browser",
            keyType = "client",
            keyPrefix = "mffpk_route_test",
            key = SDK_TOKEN,
            createdAt = "2026-01-01T00:00:00Z",
        )

    private fun auditEvent(): FeatureFlagAuditEventResponse =
        FeatureFlagAuditEventResponse(id = 1, eventType = "flag.created", createdAt = "2026-01-01T00:00:00Z")

    private fun analytics(): FeatureFlagAnalyticsResponse =
        FeatureFlagAnalyticsResponse(
            evaluations = 1,
            uniqueTargetingKeys = 1,
            variants = emptyList(),
            trackingEvents = emptyList(),
        )

    private fun snapshot(): FeatureFlagEnvironmentConfigSnapshot =
        FeatureFlagEnvironmentConfigSnapshot(
            organizationId = ROUTE_TEST_ORG_ID,
            environment = FeatureFlagEnvironmentSnapshot(1, "production", "Production", 1),
            etag = "\"ff-route-test\"",
            flags = listOf(
                com.moneat.featureflags.models.FeatureFlagSnapshotFlag(
                    id = 1,
                    key = "checkout.enabled",
                    valueType = FeatureFlagValueType.BOOLEAN,
                    clientVisible = true,
                    variants = listOf(FeatureFlagVariantSnapshot("on", "On", JsonPrimitive(true), 0)),
                    config = com.moneat.featureflags.models.FeatureFlagConfigSnapshot(
                        enabled = true,
                        defaultVariantKey = "on",
                        offVariantKey = "off",
                        rules = emptyRules(),
                        version = 1,
                    ),
                )
            ),
            segments = emptyList(),
        )

    private fun sdkPrincipal(): FeatureFlagSdkKeyPrincipal =
        FeatureFlagSdkKeyPrincipal(
            organizationId = ROUTE_TEST_ORG_ID,
            environmentId = 1,
            environmentKey = "production",
            keyType = "client",
            keyPrefix = "mffpk_route_test",
        )

    private fun evaluationResponse(): OfrepFlagEvaluationResponse =
        OfrepFlagEvaluationResponse(
            key = "checkout.enabled",
            value = JsonPrimitive(true),
            variant = "on",
            reason = "STATIC",
            metadata = mapOf("valueType" to "BOOLEAN"),
        )

    private fun errorResponse(errorCode: String): OfrepFlagEvaluationResponse =
        OfrepFlagEvaluationResponse(
            key = "checkout.enabled",
            value = JsonPrimitive(false),
            reason = "ERROR",
            errorCode = errorCode,
            errorDetails = "No flag",
        )

    private fun emptyRules(): JsonObject = JsonObject(mapOf("rules" to JsonArray(emptyList())))
}
