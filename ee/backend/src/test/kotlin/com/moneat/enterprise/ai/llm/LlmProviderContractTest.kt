// Moneat Enterprise - proprietary module
// Copyright (c) 2026 Moneat. All rights reserved.
// See ee/LICENSE for license terms.

package com.moneat.enterprise.ai.llm

import com.moneat.enterprise.ai.llm.providers.AnthropicProvider
import com.moneat.enterprise.ai.llm.providers.OpenAiProvider
import kotlinx.coroutines.runBlocking
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.JsonPrimitive
import org.junit.jupiter.api.Test
import kotlin.test.assertEquals
import kotlin.test.assertFailsWith
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
        assertEquals("inc-1", (response.toolCalls.single().arguments["id"] as? JsonPrimitive)?.content)
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
    }
}
