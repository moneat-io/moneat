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
import com.moneat.llm.services.LlmIngestionWorker
import com.moneat.testsupport.MockHttpServer
import com.moneat.testsupport.requestBodyText
import com.moneat.testsupport.respond
import kotlinx.coroutines.runBlocking
import kotlinx.serialization.json.buildJsonObject
import kotlinx.serialization.json.put
import kotlin.test.AfterTest
import kotlin.test.BeforeTest
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFailsWith
import kotlin.test.assertTrue

class LlmIngestionWorkerTest {
    @BeforeTest
    fun setup() {
        ClickHouseClient.close()
    }

    @AfterTest
    fun teardown() {
        ClickHouseClient.close()
    }

    @Test
    fun `encode and decode message roundtrip preserves project and payload`() {
        val payload = """{"generations":[]}""".toByteArray()
        val encoded = LlmIngestionWorker.encodeMessage(77L, payload)

        val (projectId, decodedPayload) = LlmIngestionWorker.decodeMessage(encoded)
        assertEquals(77L, projectId)
        assertTrue(payload.contentEquals(decodedPayload))
    }

    @Test
    fun `decode message throws for too-short payload`() {
        val tooShort =
            java.util.Base64
                .getEncoder()
                .encodeToString(byteArrayOf(1, 2, 3, 4, 5, 6, 7))
        assertFailsWith<IllegalArgumentException> {
            LlmIngestionWorker.decodeMessage(tooShort)
        }
    }

    @Test
    fun `processMessageForTest sends bad payload to DLQ`() =
        runBlocking {
            val worker = LlmIngestionWorker("llm:q", "llm:dlq", 1)
            val dlq = mutableListOf<String>()
            val bad = "not-base64"

            worker.processMessageForTest(workerId = 3, value = bad) { dlq.add(it) }

            assertEquals(listOf(bad), dlq)
        }

    @Test
    fun `insertGenerations builds clickhouse rows and maps unknown type to chat`() =
        runBlocking {
            val queries = mutableListOf<String>()
            MockHttpServer { exchange ->
                queries += exchange.requestBodyText()
                exchange.respond(200, "", contentType = "text/plain")
            }.use { server ->
                ClickHouseClient.init(server.baseUrl, "test", "default", "")

                val generation =
                    LlmGenerationIngest(
                        traceId = "trace-1",
                        spanId = "span-1",
                        name = "gen",
                        model = "gpt-4o-mini",
                        provider = "openai",
                        type = "something_new",
                        status = "ok",
                        inputTokens = 10,
                        outputTokens = 5,
                        tags = mapOf("author" to "O'Reilly"),
                        metadata = buildJsonObject { put("key", "value") }
                    )

                LlmIngestionWorker("llm:q", "llm:dlq", 1).insertGenerations(9, listOf(generation))

                val insertQuery = queries.single()
                assertTrue(insertQuery.contains("INSERT INTO `test`.llm_generations"))
                assertTrue(insertQuery.contains("'chat'"), insertQuery)
                assertTrue(insertQuery.contains("'success'"), insertQuery)
                assertTrue(insertQuery.contains("O\\'Reilly"), insertQuery)
            }
        }

    @Test
    fun `insertGenerations throws when clickhouse insert fails`(): Unit =
        runBlocking {
            MockHttpServer { exchange ->
                exchange.requestBodyText()
                exchange.respond(500, "insert failed", contentType = "text/plain")
            }.use { server ->
                ClickHouseClient.init(server.baseUrl, "test", "default", "")

                val generation = LlmGenerationIngest(model = "gpt-4o-mini")

                assertFailsWith<IllegalStateException> {
                    LlmIngestionWorker("llm:q", "llm:dlq", 1).insertGenerations(9, listOf(generation))
                }
            }
        }

    @Test
    fun `processMessageForTest routes invalid json payload to DLQ`() =
        runBlocking {
            val worker = LlmIngestionWorker("llm:q", "llm:dlq", 1)
            val dlq = mutableListOf<String>()
            val invalidJsonPayload = "not-json".toByteArray()
            val encoded = LlmIngestionWorker.encodeMessage(7, invalidJsonPayload)

            worker.processMessageForTest(workerId = 4, value = encoded) { dlq.add(it) }

            assertEquals(listOf(encoded), dlq)
        }
}
