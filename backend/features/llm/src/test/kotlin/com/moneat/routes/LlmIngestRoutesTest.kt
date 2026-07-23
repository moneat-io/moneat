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

package com.moneat.routes

import com.moneat.billing.services.BillingQuotaService
import com.moneat.billing.services.QuotaReservationResult
import com.moneat.events.repositories.models.ProjectKeyVerification
import com.moneat.events.services.EventService
import com.moneat.ingestion.queue.IngestionPipeline
import com.moneat.ingestion.queue.IngestionQueueCapacityException
import com.moneat.ingestion.queue.IngestionQueueClient
import com.moneat.llm.routes.llmIngestRoutes
import com.moneat.plugins.installErrorHandling
import com.moneat.shared.services.ProjectIdResolver
import com.moneat.testsupport.startTestKoin
import com.moneat.testsupport.stopTestKoin
import io.ktor.client.request.post
import io.ktor.client.request.setBody
import io.ktor.client.statement.bodyAsText
import io.ktor.http.ContentType
import io.ktor.http.HttpHeaders
import io.ktor.http.HttpStatusCode
import io.ktor.http.contentType
import io.ktor.server.application.install
import io.ktor.server.plugins.contentnegotiation.ContentNegotiation
import io.ktor.server.routing.routing
import io.ktor.server.testing.ApplicationTestBuilder
import io.ktor.server.testing.testApplication
import io.ktor.serialization.kotlinx.json.json
import io.mockk.every
import io.mockk.just
import io.mockk.mockk
import io.mockk.mockkObject
import io.mockk.runs
import io.mockk.unmockkObject
import io.mockk.verify
import kotlin.test.AfterTest
import kotlin.test.BeforeTest
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertTrue
import org.koin.core.context.loadKoinModules
import org.koin.dsl.module

class LlmIngestRoutesTest {
    private val eventService = mockk<EventService>(relaxed = true)
    private val quotaService = mockk<BillingQuotaService>(relaxed = true)
    private val projectIdResolver = mockk<ProjectIdResolver>()

    @BeforeTest
    fun setupKoin() {
        startTestKoin()
        loadKoinModules(
            module {
                single<EventService> { eventService }
                single<BillingQuotaService> { quotaService }
                single<ProjectIdResolver> { projectIdResolver }
            }
        )
        every { projectIdResolver.resolveProtocolId(any()) } answers {
            firstArg<String>().toLongOrNull()?.takeIf { it > 0 }
        }
        mockkObject(IngestionQueueClient)
    }

    @Test
    fun `llm ingest returns bad request for invalid project id`() =
        testApplication {
            setupApp()

            val response = client.post("/api/not-a-project/llm/")

            assertEquals(HttpStatusCode.BadRequest, response.status)
            assertTrue(response.bodyAsText().contains("Invalid project ID"))
        }

    @Test
    fun `llm ingest returns unauthorized when authentication is missing`() =
        testApplication {
            setupApp()

            val response = client.post("/api/123/llm/")

            assertEquals(HttpStatusCode.Unauthorized, response.status)
            assertTrue(response.bodyAsText().contains("Missing or invalid authentication"))
        }

    @Test
    fun `llm ingest rejects malformed payload after authentication`() =
        testApplication {
            allowProject(enforceQuota = false)
            setupApp()

            val response = client.post("/api/123/llm/?sentry_key=public-key") {
                contentType(ContentType.Application.Json)
                setBody("{")
            }

            assertEquals(HttpStatusCode.BadRequest, response.status)
            assertTrue(response.bodyAsText().contains("Invalid LLM payload"))
            verify(exactly = 0) { IngestionQueueClient.enqueue(any(), any(), any()) }
        }

    @Test
    fun `llm ingest accepts empty generation batches without reserving quota`() =
        testApplication {
            allowProject(enforceQuota = true)
            setupApp()

            val response = client.post("/api/123/llm/?sentry_key=public-key") {
                contentType(ContentType.Application.Json)
                setBody("""{"generations":[]}""")
            }

            assertEquals(HttpStatusCode.Accepted, response.status)
            assertTrue(response.bodyAsText().contains("\"accepted\":0"))
            verify(exactly = 0) { quotaService.reserveUnits(any(), any(), any(), any()) }
            verify(exactly = 0) { IngestionQueueClient.enqueue(any(), any(), any()) }
        }

    @Test
    fun `llm ingest refunds reserved quota when queue capacity rejects`() =
        testApplication {
            val body = """{"generations":[{"model":"gpt-4o-mini"}]}"""
            allowProject(enforceQuota = true)
            every { eventService.getOrganizationIdForProject(123L) } returns 7
            every {
                quotaService.reserveUnits(7, 1, "llm", body.toByteArray().size.toLong())
            } returns QuotaReservationResult(allowed = true, usage = mockk())
            every { quotaService.refundUnits(7, 1, "llm", body.toByteArray().size.toLong()) } just runs
            every {
                IngestionQueueClient.enqueue(IngestionPipeline.LLM, any(), any())
            } throws IngestionQueueCapacityException(IngestionPipeline.LLM, 100)
            setupApp()

            val response = client.post("/api/123/llm/?sentry_key=public-key") {
                contentType(ContentType.Application.Json)
                setBody(body)
            }

            assertEquals(HttpStatusCode.TooManyRequests, response.status)
            assertEquals("5", response.headers[HttpHeaders.RetryAfter])
            verify { quotaService.refundUnits(7, 1, "llm", body.toByteArray().size.toLong()) }
        }

    @Test
    fun `llm ingest returns accepted after queue admission`() =
        testApplication {
            val body = """{"generations":[{"model":"gpt-4o-mini"}]}"""
            allowProject(enforceQuota = false)
            every { IngestionQueueClient.enqueue(IngestionPipeline.LLM, any(), any()) } returns "1-0"
            setupApp()

            val response = client.post("/api/123/llm/?sentry_key=public-key") {
                contentType(ContentType.Application.Json)
                setBody(body)
            }

            assertEquals(HttpStatusCode.Accepted, response.status)
            assertTrue(response.bodyAsText().contains("\"accepted\":1"))
            verify { IngestionQueueClient.enqueue(IngestionPipeline.LLM, any(), any()) }
        }

    @Test
    fun `llm ingest rejects denied quota before queue admission`() =
        testApplication {
            val body = """{"generations":[{"model":"gpt-4o-mini"}]}"""
            allowProject(enforceQuota = true)
            every { eventService.getOrganizationIdForProject(123L) } returns 7
            every {
                quotaService.reserveUnits(7, 1, "llm", body.toByteArray().size.toLong())
            } returns QuotaReservationResult(allowed = false, reason = "monthly_limit", usage = mockk())
            setupApp()

            val response = client.post("/api/123/llm/?sentry_key=public-key") {
                contentType(ContentType.Application.Json)
                setBody(body)
            }

            assertEquals(HttpStatusCode.TooManyRequests, response.status)
            assertTrue(response.bodyAsText().contains("monthly_limit"))
            verify(exactly = 0) { IngestionQueueClient.enqueue(any(), any(), any()) }
        }

    @Test
    fun `llm ingest rejects quota reservation when project organization is missing`() =
        testApplication {
            allowProject(enforceQuota = true)
            every { eventService.getOrganizationIdForProject(123L) } returns null
            setupApp()

            val response = client.post("/api/123/llm/?sentry_key=public-key") {
                contentType(ContentType.Application.Json)
                setBody("""{"generations":[{"model":"gpt-4o-mini"}]}""")
            }

            assertEquals(HttpStatusCode.NotFound, response.status)
            assertTrue(response.bodyAsText().contains("Project organization not found"))
            verify(exactly = 0) { IngestionQueueClient.enqueue(any(), any(), any()) }
        }

    @AfterTest
    fun teardownKoin() {
        unmockkObject(IngestionQueueClient)
        stopTestKoin()
    }

    private fun allowProject(enforceQuota: Boolean) {
        every { eventService.verifyProjectKey(123L, "public-key") } returns
            ProjectKeyVerification(isValid = true, platformTarget = "jvm")
        every { quotaService.isEnforcementEnabled() } returns enforceQuota
    }

    private fun ApplicationTestBuilder.setupApp() {
        application {
            install(ContentNegotiation) { json() }
            installErrorHandling()
            routing { llmIngestRoutes() }
        }
    }
}
