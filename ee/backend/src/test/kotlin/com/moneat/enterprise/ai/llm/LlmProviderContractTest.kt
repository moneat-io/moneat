// Moneat Enterprise - proprietary module
// Copyright (c) 2026 Moneat. All rights reserved.
// See ee/LICENSE for license terms.

package com.moneat.enterprise.ai.llm

import com.moneat.enterprise.ai.llm.providers.AnthropicProvider
import com.moneat.enterprise.ai.llm.providers.OpenAiProvider
import kotlinx.coroutines.runBlocking
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.JsonPrimitive
import kotlinx.serialization.json.jsonArray
import kotlinx.serialization.json.jsonObject
import org.junit.jupiter.api.Test
import java.time.Instant
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertFailsWith
import kotlin.test.assertNull
import kotlin.test.assertTrue

class LlmProviderContractTest {
    @Test
    fun `OpenAI adapter satisfies the provider-neutral tool contract`() = runBlocking {
        val transport = RecordingTransport(OPENAI_RESPONSE)
        val provider = OpenAiProvider(
            settings = settings(
                kind = LlmProviderKind.OPENAI_COMPATIBLE,
                baseUrl = "https://compatible.example/api",
                authentication = LlmAuthentication.Bearer("test-key"),
            ),
            transport = transport,
        )

        assertProviderContract(provider, transport, "openai-compatible")
        assertTrue(
            transport.requests.all { request ->
                request.url == "https://compatible.example/api/v1/chat/completions"
            },
        )
        assertTrue(transport.requests.all { request -> request.headers["Authorization"] == "Bearer test-key" })
        assertTrue(transport.requests.all { request -> "\"tools\"" in request.body })
        assertTrue(transport.requests.all { request -> "\"max_tokens\"" in request.body })
        assertTrue(transport.requests.all { request -> "\"max_completion_tokens\"" !in request.body })
    }

    @Test
    fun `Anthropic adapter satisfies the same provider-neutral tool contract`() = runBlocking {
        val transport = RecordingTransport(ANTHROPIC_RESPONSE)
        val provider = AnthropicProvider(
            settings = settings(
                kind = LlmProviderKind.ANTHROPIC,
                baseUrl = "https://anthropic.example/api",
                authentication = LlmAuthentication.Header("x-api-key", "test-key"),
            ),
            transport = transport,
        )

        assertProviderContract(provider, transport, "anthropic")
        assertTrue(transport.requests.all { request -> request.url == "https://anthropic.example/api/v1/messages" })
        assertTrue(transport.requests.all { request -> request.headers["x-api-key"] == "test-key" })
        assertTrue(transport.requests.all { request -> request.headers["anthropic-version"] == "2023-06-01" })
        assertTrue(transport.requests.all { request -> "\"tools\"" in request.body })
    }

    @Test
    fun `retryable responses honor the configured retry budget`() = runBlocking {
        val transport = SequencedTransport(
            mutableListOf(
                LlmHttpResponse(429, "rate limited"),
                LlmHttpResponse(200, OPENAI_RESPONSE),
            ),
        )
        val provider = OpenAiProvider(
            settings = settings(
                kind = LlmProviderKind.OPENAI,
                maxRetries = 1,
            ),
            transport = transport,
        )

        val response = provider.chatCompletion(listOf(LlmMessage("user", "Summarize")))

        assertEquals("Inspecting", response.content)
        assertEquals(2, transport.callCount)
    }

    @Test
    fun `rate limit retries honor Retry-After`() = runBlocking {
        val transport = SequencedTransport(
            mutableListOf(
                LlmHttpResponse(429, "rate limited", mapOf("Retry-After" to "3")),
                LlmHttpResponse(200, OPENAI_RESPONSE),
            ),
        )
        val observedDelays = mutableListOf<Long>()

        val response = executeLlmRequest(
            transport = transport,
            request = LlmHttpRequest("https://provider.example", emptyMap(), "{}"),
            maxRetries = 1,
            retryPolicy = LlmRetryPolicy(
                delayAction = { delayMillis -> observedDelays += delayMillis },
                currentTime = { Instant.parse("2026-08-22T00:00:00Z") },
                jitterSource = { 0 },
            ),
        )

        assertEquals(200, response.status)
        assertEquals(listOf(3_000L), observedDelays)
        assertEquals(2, transport.callCount)
    }

    @Test
    fun `official OpenAI requests use the modern completion token field`() = runBlocking {
        val transport = RecordingTransport(OPENAI_RESPONSE)
        val provider = OpenAiProvider(
            settings = settings(kind = LlmProviderKind.OPENAI),
            transport = transport,
        )

        provider.chatCompletion(listOf(LlmMessage("user", "Summarize")))

        val body = transport.requests.single().body
        assertTrue("\"max_completion_tokens\"" in body)
        assertFalse("\"max_tokens\"" in body)
    }

    @Test
    fun `malformed OpenAI tool arguments remain distinguishable`() = runBlocking {
        val provider = OpenAiProvider(
            settings = settings(kind = LlmProviderKind.OPENAI_COMPATIBLE),
            transport = RecordingTransport(OPENAI_MALFORMED_TOOL_RESPONSE),
        )

        val response = provider.chatCompletion(
            messages = listOf(LlmMessage("user", "Inspect")),
            tools = listOf(incidentTool()),
        )

        assertNull(response.toolCalls.single().arguments)
    }

    @Test
    fun `Anthropic adapter skips messages without text or tool calls`() = runBlocking {
        val transport = RecordingTransport(ANTHROPIC_RESPONSE)
        val provider = AnthropicProvider(
            settings = settings(kind = LlmProviderKind.ANTHROPIC),
            transport = transport,
        )

        provider.chatCompletion(
            messages = listOf(
                LlmMessage("user"),
                LlmMessage("assistant"),
                LlmMessage("user", "Inspect"),
            ),
        )

        val messages = Json.parseToJsonElement(transport.requests.single().body)
            .jsonObject["messages"]
            ?.jsonArray
        assertEquals(1, messages?.size)
    }

    @Test
    fun `tool calls fail before transport when the configured endpoint lacks capability`() = runBlocking {
        val transport = RecordingTransport(OPENAI_RESPONSE)
        val provider = OpenAiProvider(
            settings = settings(
                kind = LlmProviderKind.OPENAI_COMPATIBLE,
                capabilities = setOf(LlmCapability.STREAMING),
            ),
            transport = transport,
        )

        assertFailsWith<IllegalStateException> {
            provider.chatCompletion(
                messages = listOf(LlmMessage("user", "Inspect")),
                tools = listOf(incidentTool()),
            )
        }
        assertTrue(transport.requests.isEmpty())
    }

    private suspend fun assertProviderContract(
        provider: LlmProvider,
        transport: RecordingTransport,
        expectedProvider: String,
    ) {
        val messages = listOf(
            LlmMessage("system", "Use evidence"),
            LlmMessage("user", "Inspect incident inc-1"),
        )
        val response = provider.chatCompletion(
            messages = messages,
            config = LlmConfig(maxTokens = 512, temperature = 0.1, jsonMode = false),
            tools = listOf(incidentTool()),
        )

        assertEquals("Inspecting", response.content)
        assertEquals("call-1", response.toolCalls.single().id)
        assertEquals("get_incident", response.toolCalls.single().name)
        assertEquals("inc-1", (response.toolCalls.single().arguments?.get("id") as? JsonPrimitive)?.content)
        assertEquals(12, response.inputTokens)
        assertEquals(4, response.outputTokens)
        assertEquals(expectedProvider, response.provider)
        assertTrue(LlmCapability.TOOL_CALLING in provider.capabilities())

        val chunks = mutableListOf<String>()
        provider.streamChatCompletion(
            messages = messages,
            config = LlmConfig(jsonMode = false),
            tools = listOf(incidentTool()),
            onTextChunk = chunks::add,
        )
        assertEquals(listOf("Inspecting"), chunks)
        assertEquals(2, transport.requests.size)
    }

    private fun incidentTool(): LlmTool = LlmTool(
        name = "get_incident",
        description = "Get one incident",
        parameters = JsonObject(
            mapOf(
                "type" to JsonPrimitive("object"),
                "properties" to JsonObject(
                    mapOf("id" to JsonObject(mapOf("type" to JsonPrimitive("string")))),
                ),
            ),
        ),
    )

    private fun settings(
        kind: LlmProviderKind,
        baseUrl: String = "https://provider.example",
        authentication: LlmAuthentication = LlmAuthentication.Bearer("test-key"),
        maxRetries: Int = 0,
        capabilities: Set<LlmCapability> = LlmCapability.entries.toSet(),
    ): LlmProviderSettings = LlmProviderSettings(
        kind = kind,
        baseUrl = baseUrl,
        model = "test-model",
        authentication = authentication,
        requestTimeoutMillis = 5_000,
        maxRetries = maxRetries,
        capabilities = capabilities,
    )

    private class RecordingTransport(
        private val responseBody: String,
    ) : LlmHttpTransport {
        val requests = mutableListOf<LlmHttpRequest>()

        override suspend fun post(request: LlmHttpRequest): LlmHttpResponse {
            requests += request
            return LlmHttpResponse(200, responseBody)
        }
    }

    private class SequencedTransport(
        private val responses: MutableList<LlmHttpResponse>,
    ) : LlmHttpTransport {
        var callCount = 0
            private set

        override suspend fun post(request: LlmHttpRequest): LlmHttpResponse {
            callCount += 1
            return responses.removeFirst()
        }
    }

    private companion object {
        val OPENAI_RESPONSE =
            """
            {
              "model": "test-model",
              "choices": [{
                "message": {
                  "content": "Inspecting",
                  "tool_calls": [{
                    "id": "call-1",
                    "type": "function",
                    "function": {
                      "name": "get_incident",
                      "arguments": "{\"id\":\"inc-1\"}"
                    }
                  }]
                }
              }],
              "usage": {"prompt_tokens": 12, "completion_tokens": 4}
            }
            """.trimIndent()

        val ANTHROPIC_RESPONSE =
            """
            {
              "model": "test-model",
              "content": [
                {"type": "text", "text": "Inspecting"},
                {
                  "type": "tool_use",
                  "id": "call-1",
                  "name": "get_incident",
                  "input": {"id": "inc-1"}
                }
              ],
              "usage": {"input_tokens": 12, "output_tokens": 4}
            }
            """.trimIndent()

        val OPENAI_MALFORMED_TOOL_RESPONSE =
            """
            {
              "choices": [{
                "message": {
                  "tool_calls": [{
                    "id": "call-1",
                    "type": "function",
                    "function": {"name": "get_incident", "arguments": "not-json"}
                  }]
                }
              }]
            }
            """.trimIndent()
    }
}
