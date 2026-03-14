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

package com.moneat.services

import com.moneat.billing.services.PricingTierService
import com.moneat.config.ClickHouseClient
import com.moneat.events.services.DashboardQueryHelper
import com.moneat.events.services.ReplayService
import com.moneat.shared.services.RetentionPolicyService
import com.moneat.testsupport.MockHttpServer
import com.moneat.testsupport.requestBodyText
import com.moneat.testsupport.respond
import io.mockk.coEvery
import io.mockk.mockk
import kotlinx.coroutines.runBlocking
import kotlin.test.BeforeTest
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertNotNull
import kotlin.test.assertNull
import kotlin.test.assertTrue

class ReplayServiceTest {

    companion object {
        private const val TEXT_PLAIN = "text/plain"
        private const val REPLAY_UUID = "aaaaaaaa-bbbb-cccc-dddd-eeeeeeeeeeee"
    }

    private val retentionPolicyService = mockk<RetentionPolicyService>()
    private val pricingTierService = mockk<PricingTierService>()
    private lateinit var queryHelper: DashboardQueryHelper
    private lateinit var service: ReplayService

    @BeforeTest
    fun setup() {
        coEvery { retentionPolicyService.getRetentionDaysForProject(any()) } returns 30
        queryHelper = DashboardQueryHelper(retentionPolicyService, pricingTierService)
        service = ReplayService(queryHelper)
    }

    @Test
    fun `getProjectIdForReplay returns project id for valid replay`() = runBlocking {
        val replayId = "01234567-89ab-cdef-0123-456789abcdef"
        MockHttpServer { exchange ->
            exchange.respond(200, """{"project_id":42}""", TEXT_PLAIN)
        }.use { server ->
            ClickHouseClient.close()
            ClickHouseClient.init(server.baseUrl, "test", "default", "")
            val result = service.getProjectIdForReplay(replayId)
            assertEquals(42L, result)
        }
    }

    @Test
    fun `getProjectIdForReplay returns null for invalid uuid`() = runBlocking {
        val result = service.getProjectIdForReplay("not-a-uuid")
        assertNull(result)
    }

    @Test
    fun `getProjectIdForReplay normalizes compact uuid`() = runBlocking {
        val queries = java.util.Collections.synchronizedList(mutableListOf<String>())
        MockHttpServer { exchange ->
            queries += exchange.requestBodyText()
            exchange.respond(200, """{"project_id":10}""", TEXT_PLAIN)
        }.use { server ->
            ClickHouseClient.close()
            ClickHouseClient.init(server.baseUrl, "test", "default", "")
            val result = service.getProjectIdForReplay("0123456789abcdef0123456789abcdef")
            assertEquals(10L, result)
            assertTrue(queries.any { it.contains("01234567-89ab-cdef-0123-456789abcdef") })
        }
    }

    @Test
    fun `getReplays returns paginated replay list`() = runBlocking {
        val replayRow = """
{"replay_id":"$REPLAY_UUID","project_id":1,"started_at":"2026-01-01T00:00:00.000Z","finished_at":"2026-01-01T00:05:00.000Z","started_ms":"1767225600000","finished_ms":"1767225900000","duration_ms":"300000","urls":["https://app.example.com"],"error_count":2,"user_id":"u-1","user_email":"test@test.com","user_username":"tester","browser_name":"Chrome","browser_version":"120","os_name":"macOS","os_version":"14","activity":8}
        """.trimIndent()
        MockHttpServer { exchange ->
            exchange.respond(200, replayRow, TEXT_PLAIN)
        }.use { server ->
            ClickHouseClient.close()
            ClickHouseClient.init(server.baseUrl, "test", "default", "")
            val result = service.getReplays(projectId = 1, page = 1, limit = 25, period = "7d")
            assertEquals(1, result.size)
            assertEquals(REPLAY_UUID, result[0].replayId)
            assertEquals(1L, result[0].projectId)
            assertEquals(300000.0, result[0].durationMs)
            assertEquals(2, result[0].errorCount)
            assertEquals("Chrome", result[0].browserName)
            assertEquals("macOS", result[0].osName)
            assertEquals(8, result[0].activity)
        }
    }

    @Test
    fun `getReplays returns empty list on error`() = runBlocking {
        MockHttpServer { exchange ->
            exchange.respond(500, "Internal Server Error", TEXT_PLAIN)
        }.use { server ->
            ClickHouseClient.close()
            ClickHouseClient.init(server.baseUrl, "test", "default", "")
            val result = service.getReplays(projectId = 1)
            assertTrue(result.isEmpty())
        }
    }

    @Test
    fun `getReplay returns replay detail`() = runBlocking {
        val replayId = REPLAY_UUID
        val detailRow = """
{"replay_id":"$replayId","project_id":1,"started_at":"2026-01-01T00:00:00.000Z","finished_at":"2026-01-01T00:05:00.000Z","started_ms":"1767225600000","finished_ms":"1767225900000","duration_ms":"300000","urls":["https://app.example.com"],"error_ids":["err-1"],"trace_ids":["trace-1"],"segment_count":5,"environment":"prod","release":"1.0.0","platform":"javascript","user_id":"u-1","user_email":"test@test.com","user_username":"tester","browser_name":"Chrome","browser_version":"120","os_name":"macOS","os_version":"14","activity":8,"tags":"{}"}
        """.trimIndent()
        MockHttpServer { exchange ->
            val query = exchange.requestBodyText()
            when {
                query.contains("replay_events") && query.contains("project_id") && !query.contains("GROUP BY") ->
                    exchange.respond(200, """{"project_id":1}""", TEXT_PLAIN)
                else ->
                    exchange.respond(200, detailRow, TEXT_PLAIN)
            }
        }.use { server ->
            ClickHouseClient.close()
            ClickHouseClient.init(server.baseUrl, "test", "default", "")
            val result = service.getReplay(replayId)
            assertNotNull(result)
            assertEquals(replayId, result.replayId)
            assertEquals(1L, result.projectId)
            assertEquals(300000.0, result.durationMs)
            assertEquals(5, result.segmentCount)
            assertEquals("prod", result.environment)
            assertEquals("javascript", result.platform)
            assertEquals("Chrome", result.browserName)
            assertEquals("macOS", result.osName)
        }
    }

    @Test
    fun `getReplay returns null for invalid uuid`() = runBlocking {
        assertNull(service.getReplay("bad-id"))
    }

    @Test
    fun `getReplayTimeline returns timeline with events`() = runBlocking {
        val replayId = REPLAY_UUID
        val detailRow = """
{"replay_id":"$replayId","project_id":1,"started_at":"2026-01-01T00:00:00.000Z","finished_at":"2026-01-01T00:05:00.000Z","started_ms":"1767225600000","finished_ms":"1767225900000","duration_ms":"300000","urls":["https://app.example.com"],"error_ids":["11111111-2222-3333-4444-555555555555"],"trace_ids":["trace-aaa"],"segment_count":5,"environment":"prod","release":"1.0.0","platform":"javascript","user_id":"u-1","user_email":"test@test.com","user_username":"tester","browser_name":"Chrome","browser_version":"120","os_name":"macOS","os_version":"14","activity":8,"tags":"{}"}
        """.trimIndent()

        val errorRow = """
{"event_id":"11111111-2222-3333-4444-555555555555","timestamp":"2026-01-01T00:01:00.000Z","ts_ms":"1767225660000","message":"NullPointerException","level":"error","issue_id":"issue-1","exception_type":"NullPointerException","exception_value":"null ref"}
        """.trimIndent()
        val txRow = """
{"event_id":"22222222-3333-4444-5555-666666666666","timestamp":"2026-01-01T00:02:00.000Z","ts_ms":"1767225720000","transaction_name":"GET /api","duration_ms":"150.0","transaction_op":"http.server","contexts":"{}"}
        """.trimIndent()
        val spanRow = """
{"span_id":"span-1","trace_id":"trace-aaa","transaction_id":"22222222-3333-4444-5555-666666666666","description":"SELECT *","op":"db","start_ts_ms":"1767225720500","duration_ms":"50.0"}
        """.trimIndent()

        MockHttpServer { exchange ->
            val query = exchange.requestBodyText()
            when {
                query.contains("replay_events") && !query.contains("GROUP BY") ->
                    exchange.respond(200, """{"project_id":1}""", TEXT_PLAIN)
                query.contains("GROUP BY replay_id") ->
                    exchange.respond(200, detailRow, TEXT_PLAIN)
                query.contains("event_type = 'error'") && query.contains("IN (") ->
                    exchange.respond(200, errorRow, TEXT_PLAIN)
                query.contains("event_type = 'transaction'") && query.contains("trace_id") ->
                    exchange.respond(200, txRow, TEXT_PLAIN)
                query.contains("spans") && query.contains("trace_id IN") ->
                    exchange.respond(200, spanRow, TEXT_PLAIN)
                query.contains("event_type = 'error'") ->
                    exchange.respond(200, "", TEXT_PLAIN)
                query.contains("event_type = 'transaction'") ->
                    exchange.respond(200, "", TEXT_PLAIN)
                query.contains("spans") ->
                    exchange.respond(200, "", TEXT_PLAIN)
                else ->
                    exchange.respond(200, "", TEXT_PLAIN)
            }
        }.use { server ->
            ClickHouseClient.close()
            ClickHouseClient.init(server.baseUrl, "test", "default", "")
            val result = service.getReplayTimeline(replayId)
            assertTrue(result.replayStartMs > 0)
            assertTrue(result.items.isNotEmpty())
            val errorItem = result.items.find { it.type == "error" }
            assertNotNull(errorItem)
            assertEquals("NullPointerException", errorItem.title)
            val txItem = result.items.find { it.type == "transaction" }
            assertNotNull(txItem)
            assertEquals("GET /api", txItem.title)
            val spanItem = result.items.find { it.type == "span" }
            assertNotNull(spanItem)
            assertEquals("SELECT *", spanItem.title)
        }
    }
}
