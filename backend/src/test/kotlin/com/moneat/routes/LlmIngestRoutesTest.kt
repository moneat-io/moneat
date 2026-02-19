package com.moneat.routes

import io.ktor.client.request.post
import io.ktor.client.statement.bodyAsText
import io.ktor.http.HttpStatusCode
import io.ktor.server.routing.routing
import io.ktor.server.testing.testApplication
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertTrue

class LlmIngestRoutesTest {
    @Test
    fun `llm ingest returns bad request for invalid project id`() = testApplication {
        application {
            routing { llmIngestRoutes() }
        }

        val response = client.post("/api/not-a-project/llm/")

        assertEquals(HttpStatusCode.BadRequest, response.status)
        assertTrue(response.bodyAsText().contains("Invalid project ID"))
    }

    @Test
    fun `llm ingest returns unauthorized when authentication is missing`() = testApplication {
        application {
            routing { llmIngestRoutes() }
        }

        val response = client.post("/api/123/llm/")

        assertEquals(HttpStatusCode.Unauthorized, response.status)
        assertTrue(response.bodyAsText().contains("Missing or invalid authentication"))
    }
}
