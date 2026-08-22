// Moneat Enterprise - proprietary module
// Copyright (c) 2026 Moneat. All rights reserved.
// See ee/LICENSE for license terms.

package com.moneat.enterprise.ai.llm.providers

import com.moneat.enterprise.ai.llm.KtorLlmHttpTransport
import com.moneat.enterprise.ai.llm.LlmAuthentication
import com.moneat.enterprise.ai.llm.LlmCapability
import com.moneat.enterprise.ai.llm.LlmConfig
import com.moneat.enterprise.ai.llm.LlmHttpRequest
import com.moneat.enterprise.ai.llm.LlmHttpTransport
import com.moneat.enterprise.ai.llm.LlmMessage
import com.moneat.enterprise.ai.llm.LlmProvider
import com.moneat.enterprise.ai.llm.LlmProviderKind
import com.moneat.enterprise.ai.llm.LlmProviderSettings
import com.moneat.enterprise.ai.llm.LlmProviderSettingsLoader
import com.moneat.enterprise.ai.llm.LlmResponse
import com.moneat.enterprise.ai.llm.LlmTool
import com.moneat.enterprise.ai.llm.LlmToolCall
import com.moneat.enterprise.ai.llm.LlmToolChoice
import com.moneat.enterprise.ai.llm.executeLlmRequest
import kotlinx.serialization.encodeToString
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.JsonArray
import kotlinx.serialization.json.JsonElement
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.buildJsonArray
import kotlinx.serialization.json.buildJsonObject
import kotlinx.serialization.json.contentOrNull
import kotlinx.serialization.json.intOrNull
import kotlinx.serialization.json.jsonArray
import kotlinx.serialization.json.jsonObject
import kotlinx.serialization.json.jsonPrimitive
import kotlinx.serialization.json.put
import mu.KotlinLogging

private val logger = KotlinLogging.logger {}

class AnthropicProvider(
    private val settings: LlmProviderSettings = LlmProviderSettingsLoader.load(),
    private val transport: LlmHttpTransport = KtorLlmHttpTransport(settings.requestTimeoutMillis),
) : LlmProvider {
    private val json = Json { ignoreUnknownKeys = true }

    init {
        require(settings.kind == LlmProviderKind.ANTHROPIC) {
            "AnthropicProvider requires anthropic settings"
        }
    }

    override fun provider(): String = settings.kind.configValue

    override fun model(): String = settings.model

    override fun isEnabled(): Boolean = settings.isEnabled

    override fun capabilities(): Set<LlmCapability> = settings.capabilities

    override suspend fun chatCompletion(
        messages: List<LlmMessage>,
        config: LlmConfig,
        tools: List<LlmTool>,
    ): LlmResponse {
        requireCapabilityForTools(tools)
        val requestBody = buildRequest(messages, config, tools)
        val response = executeLlmRequest(
            transport = transport,
            request = LlmHttpRequest(
                url = settings.endpoint("messages"),
                headers = requestHeaders(settings.authentication),
                body = json.encodeToString(JsonObject.serializer(), requestBody),
            ),
            maxRetries = settings.maxRetries,
        )
        if (response.status !in HTTP_SUCCESS_RANGE) {
            logger.error { "Anthropic completion failed with HTTP ${response.status}" }
            throw IllegalStateException("Anthropic completion failed with HTTP ${response.status}")
        }

        return parseResponse(json.parseToJsonElement(response.body).jsonObject)
    }

    private fun buildRequest(
        messages: List<LlmMessage>,
        config: LlmConfig,
        tools: List<LlmTool>,
    ): JsonObject = buildJsonObject {
        put("model", settings.model)
        put("max_tokens", config.maxTokens)
        put("temperature", config.temperature)
        val system = messages
            .filter { message -> message.role == "system" }
            .mapNotNull(LlmMessage::content)
            .joinToString("\n\n")
        if (system.isNotBlank()) put("system", system)
        put("messages", buildMessages(messages.filterNot { message -> message.role == "system" }))
        if (tools.isNotEmpty() && config.toolChoice != LlmToolChoice.NONE) {
            put("tools", JsonArray(tools.map(::toAnthropicTool)))
            put("tool_choice", buildJsonObject {
                put("type", if (config.toolChoice == LlmToolChoice.REQUIRED) "any" else "auto")
            })
        }
    }

    private fun buildMessages(messages: List<LlmMessage>): JsonArray {
        val result = mutableListOf<JsonElement>()
        val toolResults = mutableListOf<JsonElement>()

        fun flushToolResults() {
            if (toolResults.isEmpty()) return
            result += buildJsonObject {
                put("role", "user")
                put("content", JsonArray(toolResults.toList()))
            }
            toolResults.clear()
        }

        messages.forEach { message ->
            if (message.role == "tool") {
                toolResults += buildJsonObject {
                    put("type", "tool_result")
                    put("tool_use_id", message.toolCallId.orEmpty())
                    put("content", message.content.orEmpty())
                }
                return@forEach
            }

            flushToolResults()
            when (message.role) {
                "assistant" -> assistantMessage(message)?.let { result += it }
                else -> message.content
                    ?.takeIf(String::isNotBlank)
                    ?.let { content -> result += textMessage("user", content) }
            }
        }
        flushToolResults()
        return JsonArray(result)
    }

    private fun assistantMessage(message: LlmMessage): JsonObject? {
        if (message.content.isNullOrBlank() && message.toolCalls.isEmpty()) return null
        return buildJsonObject {
            put("role", "assistant")
            put("content", buildJsonArray {
                message.content?.takeIf(String::isNotBlank)?.let { content ->
                    add(buildJsonObject {
                        put("type", "text")
                        put("text", content)
                    })
                }
                message.toolCalls.forEach { call ->
                    add(buildJsonObject {
                        put("type", "tool_use")
                        put("id", call.id)
                        put("name", call.name)
                        put("input", call.arguments ?: JsonObject(emptyMap()))
                    })
                }
            })
        }
    }

    private fun textMessage(role: String, content: String): JsonObject = buildJsonObject {
        put("role", role)
        put("content", content)
    }

    private fun toAnthropicTool(tool: LlmTool): JsonObject = buildJsonObject {
        put("name", tool.name)
        put("description", tool.description)
        put("input_schema", tool.parameters)
    }

    private fun parseResponse(response: JsonObject): LlmResponse {
        val blocks = response["content"]?.jsonArray.orEmpty()
        val text = blocks.mapNotNull { block ->
            block.jsonObject.takeIf { it["type"]?.jsonPrimitive?.contentOrNull == "text" }
                ?.get("text")
                ?.jsonPrimitive
                ?.contentOrNull
        }.joinToString("")
        val toolCalls = blocks.mapNotNull { block ->
            val value = block.jsonObject
            if (value["type"]?.jsonPrimitive?.contentOrNull != "tool_use") return@mapNotNull null
            val id = value["id"]?.jsonPrimitive?.contentOrNull ?: return@mapNotNull null
            val name = value["name"]?.jsonPrimitive?.contentOrNull ?: return@mapNotNull null
            LlmToolCall(
                id = id,
                name = name,
                arguments = value["input"] as? JsonObject,
            )
        }
        val usage = response["usage"]?.jsonObject
        return LlmResponse(
            content = text,
            toolCalls = toolCalls,
            inputTokens = usage?.get("input_tokens")?.jsonPrimitive?.intOrNull ?: 0,
            outputTokens = usage?.get("output_tokens")?.jsonPrimitive?.intOrNull ?: 0,
            model = response["model"]?.jsonPrimitive?.contentOrNull ?: settings.model,
            provider = provider(),
        )
    }

    private fun requestHeaders(authentication: LlmAuthentication): Map<String, String> {
        val authHeaders = when (authentication) {
            is LlmAuthentication.Bearer -> mapOf("Authorization" to "Bearer ${authentication.token}")
            is LlmAuthentication.Header -> mapOf(authentication.name to authentication.value)
            LlmAuthentication.None -> emptyMap()
        }
        return authHeaders + ("anthropic-version" to ANTHROPIC_VERSION)
    }

    private fun requireCapabilityForTools(tools: List<LlmTool>) {
        check(!(tools.isNotEmpty() && LlmCapability.TOOL_CALLING !in capabilities())) {
            "Anthropic is not configured for tool calling"
        }
    }

    private companion object {
        const val ANTHROPIC_VERSION = "2023-06-01"
        val HTTP_SUCCESS_RANGE = 200..299
    }
}
