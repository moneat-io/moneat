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

import com.moneat.config.ClickHouseClient
import com.moneat.testsupport.MockHttpServer
import com.moneat.testsupport.requestBodyText
import com.moneat.testsupport.respond
import kotlinx.coroutines.runBlocking
import kotlin.test.AfterTest
import kotlin.test.BeforeTest
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertNotNull
import kotlin.test.assertNull
import kotlin.test.assertTrue

class LlmDashboardServiceTest {
    companion object {
        @Suppress("MaximumLineLength")
        private const val STATS_JSON =
            """{"total_generations":12,"total_tokens":2400,"total_cost":1.2,"avg_duration_ms":350.5,"error_rate":8.33}"""

        @Suppress("MaximumLineLength")
        private const val MODEL_STATS_JSON =
            """{"model":"gpt-4o-mini","provider":"openai","call_count":12,"total_tokens":2400,"total_cost":1.2,"avg_duration_ms":350.5,"error_rate":8.33}"""
    }

    @BeforeTest
    fun setup() {
        ClickHouseClient.close()
    }

    @AfterTest
    fun teardown() {
        ClickHouseClient.close()
    }

    @Test
    fun `getOverview parses stats timeline and top models`() = runBlocking {
        MockHttpServer { exchange ->
            val query = exchange.requestBodyText()
            when {
                query.contains("count() as total_generations") -> {
                    exchange.respond(200, STATS_JSON, contentType = "text/plain")
                }

                query.contains("GROUP BY ts") -> {
                    exchange.respond(
                        200,
                        """
                        {"ts":"2026-02-01T10:00:00.000Z","cnt":7,"tokens":1400,"cost":0.7,"errors":1}
                        {"ts":"2026-02-01T11:00:00.000Z","cnt":5,"tokens":1000,"cost":0.5,"errors":0}
                        """.trimIndent(),
                        contentType = "text/plain"
                    )
                }

                query.contains("GROUP BY model, provider") && query.contains("LIMIT 20") -> {
                    exchange.respond(200, MODEL_STATS_JSON, contentType = "text/plain")
                }

                else -> exchange.respond(500, "unexpected query", contentType = "text/plain")
            }
        }.use { server ->
            ClickHouseClient.init(server.baseUrl, "test", "default", "")

            val result = LlmDashboardService().getOverview(projectId = 10, range = "24h")

            assertEquals(12L, result.totalGenerations)
            assertEquals(2400L, result.totalTokens)
            assertEquals(1.2, result.totalCost)
            assertEquals(2, result.timeline.size)
            assertEquals(1L, result.timeline.first().errors)
            assertEquals(1, result.topModels.size)
            assertEquals("gpt-4o-mini", result.topModels.first().model)
        }
    }

    @Test
    fun `getGenerationDetail returns null when clickhouse returns exception body`() = runBlocking {
        MockHttpServer { exchange ->
            exchange.requestBodyText()
            exchange.respond(200, "Code: 62. DB::Exception: syntax error", contentType = "text/plain")
        }.use { server ->
            ClickHouseClient.init(server.baseUrl, "test", "default", "")

            val detail = LlmDashboardService().getGenerationDetail(projectId = 10, generationId = "gen-1")
            assertNull(detail)
        }
    }

    @Test
    fun `getTrace parses generations and computes totals`() = runBlocking {
        MockHttpServer { exchange ->
            val query = exchange.requestBodyText()
            if (query.contains("trace_id = 'trace-123'")) {
                exchange.respond(
                    200,
                    """
                    {"generation_id":"gen-1","trace_id":"trace-123","span_id":"span-1","parent_span_id":"","ts":"2026-02-01T10:00:00.000Z","duration_ms":100.0,"name":"n1","model":"gpt-4o-mini","provider":"openai","type":"chat","input":"in1","output":"out1","input_tokens":10,"output_tokens":20,"total_tokens":30,"cost_usd":0.01,"temperature":0.2,"max_tokens":100,"top_p":1.0,"status":"success","error_message":"","status_code":200,"user_id":"u1","session_id":"s1","environment":"prod","release":"r1","tags":{"k":"v"},"metadata":"{}"}
                    {"generation_id":"gen-2","trace_id":"trace-123","span_id":"span-2","parent_span_id":"span-1","ts":"2026-02-01T10:00:01.000Z","duration_ms":250.5,"name":"n2","model":"gpt-4o-mini","provider":"openai","type":"chat","input":"in2","output":"out2","input_tokens":5,"output_tokens":15,"total_tokens":20,"cost_usd":0.02,"temperature":0.2,"max_tokens":100,"top_p":1.0,"status":"error","error_message":"timeout","status_code":500,"user_id":"u1","session_id":"s1","environment":"prod","release":"r1","tags":{"k2":"v2"},"metadata":"{}"}
                    """.trimIndent(),
                    contentType = "text/plain"
                )
            } else {
                exchange.respond(500, "unexpected query", contentType = "text/plain")
            }
        }.use { server ->
            ClickHouseClient.init(server.baseUrl, "test", "default", "")

            val trace = LlmDashboardService().getTrace(projectId = 10, traceId = "trace-123")
            assertNotNull(trace)
            assertEquals(2, trace.generations.size)
            assertEquals(350.5, trace.totalDurationMs)
            assertEquals(50L, trace.totalTokens)
            assertEquals(0.03, trace.totalCost)
            assertTrue(trace.generations.first().tags.isNotEmpty())
        }
    }
}
