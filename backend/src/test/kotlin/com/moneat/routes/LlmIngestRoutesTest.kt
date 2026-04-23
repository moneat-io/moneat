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

import com.moneat.llm.routes.llmIngestRoutes
import com.moneat.testsupport.startTestKoin
import com.moneat.testsupport.stopTestKoin
import io.ktor.client.request.post
import io.ktor.client.statement.bodyAsText
import io.ktor.http.HttpStatusCode
import io.ktor.server.routing.routing
import io.ktor.server.testing.testApplication
import kotlin.test.AfterTest
import kotlin.test.BeforeTest
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertTrue

class LlmIngestRoutesTest {
    @BeforeTest
    fun setupKoin() {
        startTestKoin()
    }

    @Test
    fun `llm ingest returns bad request for invalid project id`() =
        testApplication {
            application {
                routing { llmIngestRoutes() }
            }

            val response = client.post("/api/not-a-project/llm/")

            assertEquals(HttpStatusCode.BadRequest, response.status)
            assertTrue(response.bodyAsText().contains("Invalid project ID"))
        }

    @Test
    fun `llm ingest returns unauthorized when authentication is missing`() =
        testApplication {
            application {
                routing { llmIngestRoutes() }
            }

            val response = client.post("/api/123/llm/")

            assertEquals(HttpStatusCode.Unauthorized, response.status)
            assertTrue(response.bodyAsText().contains("Missing or invalid authentication"))
        }

    @AfterTest
    fun teardownKoin() {
        stopTestKoin()
    }
}
