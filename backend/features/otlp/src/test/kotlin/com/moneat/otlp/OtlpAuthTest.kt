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

package com.moneat.otlp

import com.moneat.events.repositories.models.ProjectKeyVerification
import com.moneat.events.services.EventService
import com.moneat.otlp.services.OtlpApiKeyService
import com.moneat.shared.services.ProjectIdResolver
import io.ktor.client.request.get
import io.ktor.client.request.header
import io.ktor.client.statement.bodyAsText
import io.ktor.http.HttpHeaders
import io.ktor.server.response.respondText
import io.ktor.server.routing.get
import io.ktor.server.routing.routing
import io.ktor.server.testing.ApplicationTestBuilder
import io.ktor.server.testing.testApplication
import io.mockk.every
import io.mockk.mockk
import io.mockk.verify
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.uuid.Uuid

class OtlpAuthTest {

    @Test
    fun `resolveOtlpIngestOrganizationId returns org from bearer API key`() = testApplication {
        val apiKeyService = mockk<OtlpApiKeyService>()
        val eventService = mockk<EventService>(relaxed = true)

        every { apiKeyService.validateKey("valid-otlp-key") } returns ORG_ID

        installAuthRoute(apiKeyService, eventService, ProjectIdResolver())

        val response = client.get("/auth") {
            header(HttpHeaders.Authorization, "Bearer valid-otlp-key")
        }

        assertEquals(ORG_ID.toString(), response.bodyAsText())
        verify(exactly = 0) { eventService.verifyProjectKey(any(), any()) }
    }

    @Test
    fun `resolveOtlpIngestOrganizationId accepts UUID project DSN header`() = testApplication {
        val resourceId = Uuid.parse("818f4ce4-3f2a-7a67-a32b-0c1848f62b9d")
        val apiKeyService = mockk<OtlpApiKeyService>()
        val eventService = verifiedProjectKeyService()
        val resolver = resourceIdResolver(resourceId)

        every { apiKeyService.validateKey(any()) } returns null

        installAuthRoute(apiKeyService, eventService, resolver)

        val response = client.get("/auth") {
            header("x-moneat-dsn", "https://$PUBLIC_KEY@example.test/$resourceId")
            header("X-Sentry-Auth", "Sentry sentry_key=$PUBLIC_KEY, sentry_version=7")
        }

        assertEquals(ORG_ID.toString(), response.bodyAsText())
        verify { eventService.verifyProjectKey(PROJECT_ID, PUBLIC_KEY) }
    }

    @Test
    fun `resolveOtlpIngestOrganizationId accepts UUID project query parameter`() = testApplication {
        val resourceId = Uuid.parse("918f4ce4-3f2a-7a67-a32b-0c1848f62b9d")
        val apiKeyService = mockk<OtlpApiKeyService>()
        val eventService = verifiedProjectKeyService()
        val resolver = resourceIdResolver(resourceId)

        every { apiKeyService.validateKey(any()) } returns null

        installAuthRoute(apiKeyService, eventService, resolver)

        val response = client.get("/auth?projectId=$resourceId&sentry_key=$PUBLIC_KEY")

        assertEquals(ORG_ID.toString(), response.bodyAsText())
        verify { eventService.verifyProjectKey(PROJECT_ID, PUBLIC_KEY) }
    }

    @Test
    fun `resolveOtlpIngestOrganizationId accepts DSN authorization header`() = testApplication {
        val apiKeyService = mockk<OtlpApiKeyService>()
        val eventService = verifiedProjectKeyService()

        installAuthRoute(apiKeyService, eventService, ProjectIdResolver())

        val response = client.get("/auth") {
            header(HttpHeaders.Authorization, "DSN https://$PUBLIC_KEY@example.test/$PROJECT_ID")
        }

        assertEquals(ORG_ID.toString(), response.bodyAsText())
        verify { eventService.verifyProjectKey(PROJECT_ID, PUBLIC_KEY) }
    }

    private fun ApplicationTestBuilder.installAuthRoute(
        apiKeyService: OtlpApiKeyService,
        eventService: EventService,
        projectIdResolver: ProjectIdResolver,
    ) {
        application {
            routing {
                get("/auth") {
                    val orgId = OtlpAuth.resolveOtlpIngestOrganizationId(
                        call,
                        apiKeyService,
                        eventService,
                        projectIdResolver,
                    )
                    call.respondText(orgId?.toString() ?: "none")
                }
            }
        }
    }

    private fun verifiedProjectKeyService(): EventService {
        val eventService = mockk<EventService>()
        every { eventService.verifyProjectKey(PROJECT_ID, PUBLIC_KEY) } returns ProjectKeyVerification(true, "jvm")
        every { eventService.getOrganizationIdForProject(PROJECT_ID) } returns ORG_ID
        return eventService
    }

    private fun resourceIdResolver(resourceId: Uuid): ProjectIdResolver =
        ProjectIdResolver(
            lookupProjectIdByResourceId = { candidate ->
                if (candidate == resourceId) PROJECT_ID else null
            }
        )

    private companion object {
        private const val ORG_ID = 7
        private const val PROJECT_ID = 42L
        private const val PUBLIC_KEY = "pubkey"
    }
}
