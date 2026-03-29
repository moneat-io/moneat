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
import com.moneat.llm.models.LlmGenerationIngest
import com.moneat.llm.services.LlmDashboardService
import com.moneat.llm.services.LlmIngestionWorker
import com.moneat.testsupport.queryBasedClickHouseHandler
import com.moneat.testsupport.requestBodyText
import com.moneat.testsupport.respond
import com.moneat.testsupport.withClickHouseMockServer
import kotlinx.coroutines.runBlocking
import kotlinx.serialization.json.buildJsonObject
import kotlinx.serialization.json.put
import kotlin.test.AfterTest
import kotlin.test.BeforeTest
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertNotNull
import kotlin.test.assertNull
import kotlin.test.assertTrue

class LlmServiceExtendedTest {
    companion object {
        private const val EMPTY_STATS_JSON =
            """{"total_generations":0,"total_tokens":0,"total_cost":0,"avg_duration_ms":0,"error_rate":0}"""
        private const val GPT_4O = "gpt-4o"
        private const val TEXT_PLAIN = "text/plain"
        private const val COUNT_AS_TOTAL_GENERATIONS = "count() as total_generations"
    }

    @BeforeTest
    fun setup() {
        ClickHouseClient.close()
    }

    @AfterTest
    fun teardown() {
        ClickHouseClient.close()
    }

    // ──── LlmDashboardService: getGenerations ────

    @Test
    fun `getGenerations parses list with pagination`() =
        runBlocking {
            val handler = queryBasedClickHouseHandler(
                "count() as total" to """{"total":42}""",
                "ORDER BY timestamp DESC" to buildGenerationRow("gen-1", "gpt-4o-mini", "openai")
            )
            withClickHouseMockServer(handler) { _ ->
                val result = LlmDashboardService().getGenerations(
                    projectId = 5,
                    range = "24h",
                    model = null,
                    provider = null,
                    type = null,
                    status = null,
                    page = 1,
                    pageSize = 25
                )
                assertEquals(42L, result.total)
                assertEquals(1, result.page)
                assertEquals(25, result.pageSize)
                assertEquals(1, result.generations.size)
                assertEquals("gen-1", result.generations.first().generationId)
                assertEquals("gpt-4o-mini", result.generations.first().model)
            }
        }

    @Test
    fun `getGenerations includes filter clauses in query`() =
        runBlocking {
            val queries = mutableListOf<String>()
            withClickHouseMockServer(
                queryBasedClickHouseHandler(
                    defaultBody = """{"total":0}""",
                    captureQueries = queries
                )
            ) { _ ->
                LlmDashboardService().getGenerations(
                    projectId = 5,
                    range = "7d",
                    model = GPT_4O,
                    provider = "openai",
                    type = "chat",
                    status = "error",
                    page = 2,
                    pageSize = 10
                )
                val dataQuery = queries.find { it.contains("ORDER BY timestamp DESC") } ?: ""
                assertTrue(dataQuery.contains("model = '$GPT_4O'"), "model filter missing")
                assertTrue(dataQuery.contains("provider = 'openai'"), "provider filter missing")
                assertTrue(dataQuery.contains("type = 'chat'"), "type filter missing")
                assertTrue(dataQuery.contains("status = 'error'"), "status filter missing")
                assertTrue(dataQuery.contains("OFFSET 10"), "offset for page 2 missing")
            }
        }

    @Test
    fun `getGenerations returns empty list when clickhouse has no data`() =
        runBlocking {
            val handler = queryBasedClickHouseHandler(
                "count() as total" to """{"total":0}""",
                defaultBody = "\n"
            )
            withClickHouseMockServer(handler) { _ ->
                val result = LlmDashboardService().getGenerations(
                    projectId = 5,
                    range = "24h",
                    model = null,
                    provider = null,
                    type = null,
                    status = null,
                    page = 1,
                    pageSize = 25
                )
                assertEquals(0L, result.total)
                assertTrue(result.generations.isEmpty())
            }
        }

    // ──── LlmDashboardService: getGenerationDetail (success path) ────

    @Test
    fun `getGenerationDetail parses full detail response`() =
        runBlocking {
            val detailJson =
                """{"generation_id":"gen-99","trace_id":"t1","span_id":"s1","parent_span_id":"ps1",""" +
                    """"ts":"2026-03-01T12:00:00.000Z","duration_ms":200.0,"name":"summarize","model":"claude-3",""" +
                    """"provider":"anthropic","type":"chat","input":"hello","output":"world",""" +
                    """"input_tokens":5,"output_tokens":10,"total_tokens":15,"cost_usd":0.005,""" +
                    """"temperature":0.7,"max_tokens":512,"top_p":0.9,"status":"success",""" +
                    """"error_message":"","status_code":200,"user_id":"user-1","session_id":"sess-1",""" +
                    """"environment":"production","release":"v2.0","tags":{"env":"prod","team":"ml"},"metadata":{}}"""
            withClickHouseMockServer(
                queryBasedClickHouseHandler(defaultBody = detailJson)
            ) { _ ->
                val detail = LlmDashboardService().getGenerationDetail(
                    projectId = 10,
                    generationId = "gen-99"
                )
                assertNotNull(detail)
                assertEquals("gen-99", detail.generationId)
                assertEquals("claude-3", detail.model)
                assertEquals("anthropic", detail.provider)
                assertEquals(0.7f, detail.temperature)
                assertEquals(512, detail.maxTokens)
                assertEquals(0.9f, detail.topP)
                assertEquals(2, detail.tags.size)
                assertEquals("prod", detail.tags["env"])
            }
        }

    @Test
    fun `getGenerationDetail returns null for clickhouse error body`() =
        runBlocking {
            withClickHouseMockServer(
                queryBasedClickHouseHandler(
                    defaultBody = "Code: 60. DB::Exception: Table not found"
                )
            ) { _ ->
                val detail = LlmDashboardService().getGenerationDetail(
                    projectId = 10,
                    generationId = "nonexistent"
                )
                assertNull(detail)
            }
        }

    @Test
    fun `getGenerationDetail returns null for non-OK status`() =
        runBlocking {
            withClickHouseMockServer({ exchange ->
                exchange.requestBodyText()
                exchange.respond(500, "internal error", contentType = TEXT_PLAIN)
            }) { _ ->
                val detail = LlmDashboardService().getGenerationDetail(
                    projectId = 10,
                    generationId = "gen-1"
                )
                assertNull(detail)
            }
        }

    // ──── LlmDashboardService: getCosts ────

    @Test
    fun `getCosts parses breakdown and timeline`() =
        runBlocking {
            withClickHouseMockServer({ exchange ->
                val query = exchange.requestBodyText()
                when {
                    query.contains("GROUP BY model, provider") -> {
                        val costsRow1 =
                            """{"model":"${GPT_4O}","provider":"openai","total_cost":1.5,""" +
                                """"total_tokens":3000,"call_count":10}"""
                        val costsRow2 =
                            """{"model":"claude-3","provider":"anthropic","total_cost":0.8,""" +
                                """"total_tokens":1600,"call_count":5}"""
                        exchange.respond(
                            200,
                            "$costsRow1\n$costsRow2",
                            contentType = TEXT_PLAIN
                        )
                    }
                    query.contains("GROUP BY ts") -> {
                        exchange.respond(
                            200,
                            """{"ts":"2026-03-01T10:00:00.000Z","cnt":15,"tokens":4600,"cost":2.3,"errors":0}""",
                            contentType = TEXT_PLAIN
                        )
                    }
                    else -> exchange.respond(200, "", contentType = TEXT_PLAIN)
                }
            }) { _ ->
                val costs = LlmDashboardService().getCosts(projectId = 10, range = "7d")
                assertEquals(2.3, costs.totalCost)
                assertEquals(2, costs.breakdown.size)
                assertEquals(GPT_4O, costs.breakdown.first().model)
                assertEquals(1, costs.timeline.size)
            }
        }

    // ──── LlmDashboardService: getModels ────

    @Test
    fun `getModels returns model stats list`() =
        runBlocking {
            val modelsRow1 =
                """{"model":"${GPT_4O}","provider":"openai","call_count":100,"total_tokens":20000,""" +
                    """"total_cost":5.0,"avg_duration_ms":400.0,"error_rate":2.0}"""
            val modelsRow2 =
                """{"model":"claude-3","provider":"anthropic","call_count":50,"total_tokens":10000,""" +
                    """"total_cost":2.5,"avg_duration_ms":300.0,"error_rate":0.0}"""
            val modelsJson = "$modelsRow1\n$modelsRow2"
            withClickHouseMockServer({ exchange ->
                exchange.requestBodyText()
                exchange.respond(200, modelsJson, contentType = TEXT_PLAIN)
            }) { _ ->
                val models = LlmDashboardService().getModels(projectId = 10, range = "24h")
                assertEquals(2, models.size)
                assertEquals(GPT_4O, models[0].model)
                assertEquals(100L, models[0].callCount)
                assertEquals("claude-3", models[1].model)
                assertEquals(0.0, models[1].errorRate)
            }
        }

    @Test
    fun `getModels returns empty list for empty response`() =
        runBlocking {
            withClickHouseMockServer({ exchange ->
                exchange.requestBodyText()
                exchange.respond(200, "", contentType = TEXT_PLAIN)
            }) { _ ->
                val models = LlmDashboardService().getModels(projectId = 10, range = "24h")
                assertTrue(models.isEmpty())
            }
        }

    // ──── LlmDashboardService: getTrace empty case ────

    @Test
    fun `getTrace returns null for empty response`() =
        runBlocking {
            withClickHouseMockServer({ exchange ->
                exchange.requestBodyText()
                exchange.respond(200, "", contentType = TEXT_PLAIN)
            }) { _ ->
                val trace = LlmDashboardService().getTrace(
                    projectId = 10,
                    traceId = "no-such-trace"
                )
                assertNull(trace)
            }
        }

    // ──── LlmDashboardService: getOverview with empty data ────

    @Test
    fun `getOverview returns zeros when clickhouse returns empty aggregates`() =
        runBlocking {
            withClickHouseMockServer({ exchange ->
                val query = exchange.requestBodyText()
                when {
                    query.contains(COUNT_AS_TOTAL_GENERATIONS) -> {
                        exchange.respond(
                            200,
                            EMPTY_STATS_JSON,
                            contentType = TEXT_PLAIN
                        )
                    }
                    else -> {
                        exchange.respond(200, "\n", contentType = TEXT_PLAIN)
                    }
                }
            }) { _ ->
                val result = LlmDashboardService().getOverview(projectId = 10, range = "24h")
                assertEquals(0L, result.totalGenerations)
                assertEquals(0L, result.totalTokens)
                assertEquals(0.0, result.totalCost)
                assertTrue(result.timeline.isEmpty())
                assertTrue(result.topModels.isEmpty())
            }
        }

    // ──── LlmDashboardService: negative project ID ────

    @Test
    fun `getOverview uses toInt64 clause for negative project IDs`() =
        runBlocking {
            val queries = mutableListOf<String>()
            withClickHouseMockServer({ exchange ->
                val query = exchange.requestBodyText()
                queries += query
                when {
                    query.contains(COUNT_AS_TOTAL_GENERATIONS) -> {
                        exchange.respond(200, EMPTY_STATS_JSON, contentType = TEXT_PLAIN)
                    }
                    else -> {
                        exchange.respond(200, "\n", contentType = TEXT_PLAIN)
                    }
                }
            }) { _ ->
                LlmDashboardService().getOverview(projectId = -1, range = "24h")
                assertTrue(queries.any { it.contains("toInt64(project_id) = -1") })
            }
        }

    // ──── LlmDashboardService: demoEpochMs ────

    @Test
    fun `getOverview uses demo epoch in time clause`() =
        runBlocking {
            val queries = mutableListOf<String>()
            withClickHouseMockServer({ exchange ->
                val query = exchange.requestBodyText()
                queries += query
                when {
                    query.contains(COUNT_AS_TOTAL_GENERATIONS) -> {
                        exchange.respond(200, EMPTY_STATS_JSON, contentType = TEXT_PLAIN)
                    }
                    else -> {
                        exchange.respond(200, "\n", contentType = TEXT_PLAIN)
                    }
                }
            }) { _ ->
                LlmDashboardService().getOverview(
                    projectId = 10,
                    range = "24h",
                    demoEpochMs = 1709312400000L
                )
                assertTrue(queries.any { it.contains("toDateTime64(") })
            }
        }

    // ──── LlmDashboardService: range-to-interval mapping ────

    @Test
    fun `getOverview maps range 1h to five-minute buckets`() =
        runBlocking {
            val queries = mutableListOf<String>()
            withClickHouseMockServer({ exchange ->
                val query = exchange.requestBodyText()
                queries += query
                when {
                    query.contains(COUNT_AS_TOTAL_GENERATIONS) -> {
                        exchange.respond(200, EMPTY_STATS_JSON, contentType = TEXT_PLAIN)
                    }
                    else -> {
                        exchange.respond(200, "\n", contentType = TEXT_PLAIN)
                    }
                }
            }) { _ ->
                LlmDashboardService().getOverview(projectId = 10, range = "1h")
                assertTrue(queries.any { it.contains("toStartOfFiveMinutes") })
                assertTrue(queries.any { it.contains("1 HOUR") })
            }
        }

    @Test
    fun `getOverview maps range 30d to daily buckets`() =
        runBlocking {
            val queries = mutableListOf<String>()
            withClickHouseMockServer({ exchange ->
                val query = exchange.requestBodyText()
                queries += query
                when {
                    query.contains(COUNT_AS_TOTAL_GENERATIONS) -> {
                        exchange.respond(200, EMPTY_STATS_JSON, contentType = TEXT_PLAIN)
                    }
                    else -> {
                        exchange.respond(200, "\n", contentType = TEXT_PLAIN)
                    }
                }
            }) { _ ->
                LlmDashboardService().getOverview(projectId = 10, range = "30d")
                assertTrue(queries.any { it.contains("toStartOfDay") })
                assertTrue(queries.any { it.contains("30 DAY") })
            }
        }

    @Test
    fun `getOverview maps unknown range to 24h defaults`() =
        runBlocking {
            val queries = mutableListOf<String>()
            withClickHouseMockServer({ exchange ->
                val query = exchange.requestBodyText()
                queries += query
                when {
                    query.contains(COUNT_AS_TOTAL_GENERATIONS) -> {
                        exchange.respond(200, EMPTY_STATS_JSON, contentType = TEXT_PLAIN)
                    }
                    else -> {
                        exchange.respond(200, "\n", contentType = TEXT_PLAIN)
                    }
                }
            }) { _ ->
                LlmDashboardService().getOverview(projectId = 10, range = "unknown")
                assertTrue(queries.any { it.contains("toStartOfHour") })
                assertTrue(queries.any { it.contains("24 HOUR") })
            }
        }

    // ──── LlmIngestionWorker: insertGenerations empty list ────

    @Test
    fun `insertGenerations is no-op for empty list`() =
        runBlocking {
            var called = false
            withClickHouseMockServer({ exchange ->
                called = true
                exchange.requestBodyText()
                exchange.respond(200, "", contentType = TEXT_PLAIN)
            }) { _ ->
                LlmIngestionWorker("q", "dlq", 1).insertGenerations(1, emptyList())
                assertTrue(!called, "ClickHouse should not be called for empty list")
            }
        }

    // ──── LlmIngestionWorker: type mapping via insertGenerations ────

    @Test
    fun `insertGenerations maps known types correctly`() =
        runBlocking {
            val types = listOf("completion", "embedding", "tool_call", "agent", "chain", "retriever")
            for (typeStr in types) {
                val queries = mutableListOf<String>()
                withClickHouseMockServer({ exchange ->
                    queries += exchange.requestBodyText()
                    exchange.respond(200, "", contentType = TEXT_PLAIN)
                }) { _ ->
                    val gen = LlmGenerationIngest(model = "test", type = typeStr)
                    LlmIngestionWorker("q", "dlq", 1).insertGenerations(1, listOf(gen))
                    assertTrue(
                        queries.single().contains("'$typeStr'"),
                        "Type '$typeStr' not found in query"
                    )
                }
                ClickHouseClient.close()
            }
        }

    // ──── LlmIngestionWorker: timestamp parsing via insertGenerations ────

    @Test
    fun `insertGenerations parses ISO timestamp`() =
        runBlocking {
            val queries = mutableListOf<String>()
            withClickHouseMockServer({ exchange ->
                queries += exchange.requestBodyText()
                exchange.respond(200, "", contentType = TEXT_PLAIN)
            }) { _ ->
                val gen = LlmGenerationIngest(
                    model = "test",
                    timestamp = "2026-03-01T12:00:00Z"
                )
                LlmIngestionWorker("q", "dlq", 1).insertGenerations(1, listOf(gen))
                val query = queries.single()
                assertTrue(
                    query.contains("fromUnixTimestamp64Milli(1772366400000)"),
                    "ISO timestamp not parsed to epoch millis"
                )
            }
        }

    @Test
    fun `insertGenerations parses numeric timestamp`() =
        runBlocking {
            val queries = mutableListOf<String>()
            withClickHouseMockServer({ exchange ->
                queries += exchange.requestBodyText()
                exchange.respond(200, "", contentType = TEXT_PLAIN)
            }) { _ ->
                val gen = LlmGenerationIngest(
                    model = "test",
                    timestamp = "1700000000000"
                )
                LlmIngestionWorker("q", "dlq", 1).insertGenerations(1, listOf(gen))
                val query = queries.single()
                assertTrue(
                    query.contains("fromUnixTimestamp64Milli(1700000000000)"),
                    "Numeric timestamp not passed through"
                )
            }
        }

    @Test
    fun `insertGenerations uses current time for blank timestamp`() =
        runBlocking {
            val queries = mutableListOf<String>()
            withClickHouseMockServer({ exchange ->
                queries += exchange.requestBodyText()
                exchange.respond(200, "", contentType = TEXT_PLAIN)
            }) { _ ->
                val gen = LlmGenerationIngest(model = "test", timestamp = "")
                LlmIngestionWorker("q", "dlq", 1).insertGenerations(1, listOf(gen))
                val query = queries.single()
                assertTrue(
                    query.contains("fromUnixTimestamp64Milli("),
                    "Timestamp function not present in query"
                )
            }
        }

    // ──── LlmIngestionWorker: tags formatting ────

    @Test
    fun `insertGenerations formats empty tags as empty map`() =
        runBlocking {
            val queries = mutableListOf<String>()
            withClickHouseMockServer({ exchange ->
                queries += exchange.requestBodyText()
                exchange.respond(200, "", contentType = TEXT_PLAIN)
            }) { _ ->
                val gen = LlmGenerationIngest(model = "test", tags = emptyMap())
                LlmIngestionWorker("q", "dlq", 1).insertGenerations(1, listOf(gen))
                val query = queries.single()
                assertTrue(query.contains("{}"), "Empty tags should produce {}")
            }
        }

    @Test
    fun `insertGenerations formats populated tags as clickhouse map`() =
        runBlocking {
            val queries = mutableListOf<String>()
            withClickHouseMockServer({ exchange ->
                queries += exchange.requestBodyText()
                exchange.respond(200, "", contentType = TEXT_PLAIN)
            }) { _ ->
                val gen = LlmGenerationIngest(
                    model = "test",
                    tags = mapOf("env" to "prod", "team" to "ml")
                )
                LlmIngestionWorker("q", "dlq", 1).insertGenerations(1, listOf(gen))
                val query = queries.single()
                assertTrue(query.contains("'env':'prod'"), "Tag env missing")
                assertTrue(query.contains("'team':'ml'"), "Tag team missing")
            }
        }

    // ──── LlmIngestionWorker: error status mapping ────

    @Test
    fun `insertGenerations maps error status correctly`() =
        runBlocking {
            val queries = mutableListOf<String>()
            withClickHouseMockServer({ exchange ->
                queries += exchange.requestBodyText()
                exchange.respond(200, "", contentType = TEXT_PLAIN)
            }) { _ ->
                val gen = LlmGenerationIngest(model = "test", status = "error")
                LlmIngestionWorker("q", "dlq", 1).insertGenerations(1, listOf(gen))
                val query = queries.single()
                assertTrue(query.contains("'error'"), "Error status not mapped")
            }
        }

    // ──── LlmIngestionWorker: metadata serialization ────

    @Test
    fun `insertGenerations serializes json metadata`() =
        runBlocking {
            val queries = mutableListOf<String>()
            withClickHouseMockServer({ exchange ->
                queries += exchange.requestBodyText()
                exchange.respond(200, "", contentType = TEXT_PLAIN)
            }) { _ ->
                val gen = LlmGenerationIngest(
                    model = "test",
                    metadata = buildJsonObject { put("key", "value") }
                )
                LlmIngestionWorker("q", "dlq", 1).insertGenerations(1, listOf(gen))
                val query = queries.single()
                assertTrue(query.contains("key"), "Metadata key not found")
            }
        }

    // ──── LlmIngestionWorker: encode/decode with large project IDs ────

    @Test
    fun `encode and decode roundtrip with large project ID`() {
        val largeId = Long.MAX_VALUE
        val payload = "test".toByteArray()
        val encoded = LlmIngestionWorker.encodeMessage(largeId, payload)
        val (decoded, decodedPayload) = LlmIngestionWorker.decodeMessage(encoded)
        assertEquals(largeId, decoded)
        assertTrue(payload.contentEquals(decodedPayload))
    }

    @Test
    fun `encode and decode roundtrip with negative project ID`() {
        val negativeId = -42L
        val payload = "negative".toByteArray()
        val encoded = LlmIngestionWorker.encodeMessage(negativeId, payload)
        val (decoded, decodedPayload) = LlmIngestionWorker.decodeMessage(encoded)
        assertEquals(negativeId, decoded)
        assertTrue(payload.contentEquals(decodedPayload))
    }

    // ──── LlmIngestionWorker: total tokens computed ────

    @Test
    fun `insertGenerations computes total tokens from input and output`() =
        runBlocking {
            val queries = mutableListOf<String>()
            withClickHouseMockServer({ exchange ->
                queries += exchange.requestBodyText()
                exchange.respond(200, "", contentType = TEXT_PLAIN)
            }) { _ ->
                val gen = LlmGenerationIngest(
                    model = "test",
                    inputTokens = 100,
                    outputTokens = 50
                )
                LlmIngestionWorker("q", "dlq", 1).insertGenerations(1, listOf(gen))
                val query = queries.single()
                // 100 input + 50 output = 150 total should appear after the two token counts
                assertTrue(query.contains("100"), "Input tokens not found")
                assertTrue(query.contains("50"), "Output tokens not found")
                assertTrue(query.contains("150"), "Total tokens not computed correctly")
            }
        }

    // ──── LlmDashboardService: getCosts with demoEpochMs ────

    @Test
    fun `getCosts uses demo epoch in time clause`() =
        runBlocking {
            val queries = mutableListOf<String>()
            withClickHouseMockServer({ exchange ->
                queries += exchange.requestBodyText()
                exchange.respond(200, "", contentType = TEXT_PLAIN)
            }) { _ ->
                LlmDashboardService().getCosts(
                    projectId = 10,
                    range = "7d",
                    demoEpochMs = 1709312400000L
                )
                assertTrue(queries.any { it.contains("toDateTime64(") })
            }
        }

    // ──── Helper ────

    private fun buildGenerationRow(
        genId: String,
        model: String,
        provider: String
    ): String {
        return """{"generation_id":"$genId","trace_id":"t1","span_id":"s1","parent_span_id":"",""" +
            """"ts":"2026-03-01T10:00:00.000Z","duration_ms":100.0,"name":"test",""" +
            """"model":"$model","provider":"$provider","type":"chat","input_tokens":10,""" +
            """"output_tokens":20,"total_tokens":30,"cost_usd":0.01,"status":"success",""" +
            """"error_message":"","user_id":"u1","environment":"prod","release":"v1"}"""
    }
}
