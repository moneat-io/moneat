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

package com.moneat.summary

import com.moneat.summary.routes.summaryRoutes
import com.moneat.summary.services.SummaryService
import com.moneat.testsupport.RouteTestSupport
import com.moneat.testsupport.RouteTestSupport.installJwtAuth
import com.moneat.testsupport.RouteTestSupport.withAuth
import com.moneat.testsupport.startTestKoin
import com.moneat.testsupport.stopTestKoin
import io.ktor.client.request.get
import io.ktor.http.HttpStatusCode
import io.ktor.server.routing.routing
import io.ktor.server.testing.testApplication
import io.mockk.mockk
import kotlin.test.AfterTest
import kotlin.test.BeforeTest
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertTrue

class SummaryRoutesTest {

    private val mockSummaryService = mockk<SummaryService>(relaxed = true)

    @BeforeTest
    fun setup() {
        startTestKoin()
    }

    @AfterTest
    fun teardown() {
        stopTestKoin()
    }

    @Test
    fun `infrastructure returns 401 without JWT`() =
        testApplication {
            application {
                installJwtAuth()
                routing { summaryRoutes(mockSummaryService) }
            }
            val response = client.get("/v1/summary/infrastructure")
            assertEquals(HttpStatusCode.Unauthorized, response.status)
        }

    @Test
    fun `infrastructure with auth returns non-401`() =
        testApplication {
            application {
                installJwtAuth()
                routing { summaryRoutes(mockSummaryService) }
            }
            val token = RouteTestSupport.createToken(userId = 1, orgId = 1)
            val response = client.get("/v1/summary/infrastructure") { withAuth(token) }
            assertTrue(response.status != HttpStatusCode.Unauthorized)
        }

    @Test
    fun `overnight returns 401 without JWT`() =
        testApplication {
            application {
                installJwtAuth()
                routing { summaryRoutes(mockSummaryService) }
            }
            val response = client.get("/v1/summary/overnight")
            assertEquals(HttpStatusCode.Unauthorized, response.status)
        }

    @Test
    fun `overnight with auth returns non-401`() =
        testApplication {
            application {
                installJwtAuth()
                routing { summaryRoutes(mockSummaryService) }
            }
            val token = RouteTestSupport.createToken(userId = 1, orgId = 1)
            val response = client.get("/v1/summary/overnight") { withAuth(token) }
            assertTrue(response.status != HttpStatusCode.Unauthorized)
        }

    @Test
    fun `weekly returns 401 without JWT`() =
        testApplication {
            application {
                installJwtAuth()
                routing { summaryRoutes(mockSummaryService) }
            }
            val response = client.get("/v1/summary/weekly")
            assertEquals(HttpStatusCode.Unauthorized, response.status)
        }

    @Test
    fun `weekly with auth returns non-401`() =
        testApplication {
            application {
                installJwtAuth()
                routing { summaryRoutes(mockSummaryService) }
            }
            val token = RouteTestSupport.createToken(userId = 1, orgId = 1)
            val response = client.get("/v1/summary/weekly") { withAuth(token) }
            assertTrue(response.status != HttpStatusCode.Unauthorized)
        }

    @Test
    fun `incident context returns 401 without JWT`() =
        testApplication {
            application {
                installJwtAuth()
                routing { summaryRoutes(mockSummaryService) }
            }
            val response = client.get("/v1/summary/incident/1")
            assertEquals(HttpStatusCode.Unauthorized, response.status)
        }

    @Test
    fun `incident context with auth returns non-401`() =
        testApplication {
            application {
                installJwtAuth()
                routing { summaryRoutes(mockSummaryService) }
            }
            val token = RouteTestSupport.createToken(userId = 1, orgId = 1)
            val response = client.get("/v1/summary/incident/42") { withAuth(token) }
            assertTrue(response.status != HttpStatusCode.Unauthorized)
        }

    @Test
    fun `incident context with non-numeric id and auth returns non-401`() =
        testApplication {
            application {
                installJwtAuth()
                routing { summaryRoutes(mockSummaryService) }
            }
            val token = RouteTestSupport.createToken(userId = 1, orgId = 1)
            val response = client.get("/v1/summary/incident/not-a-number") { withAuth(token) }
            assertTrue(response.status != HttpStatusCode.Unauthorized)
        }
}
