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
import kotlinx.serialization.SerialName
import kotlinx.serialization.Serializable
import kotlinx.serialization.encodeToString
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.jsonObject
import mu.KotlinLogging

private val logger = KotlinLogging.logger {}

class OpenAiProvider(
    private val settings: LlmProviderSettings = LlmProviderSettingsLoader.load(),
    private val transport: LlmHttpTransport = KtorLlmHttpTransport(settings.requestTimeoutMillis),
) : LlmProvider {
    private val json = Json {
        ignoreUnknownKeys = true
        encodeDefaults = false
    }

    init {
        require(settings.kind == LlmProviderKind.OPENAI || settings.kind == LlmProviderKind.OPENAI_COMPATIBLE) {
            "OpenAiProvider requires openai or openai-compatible settings"
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
        val request = OpenAiRequest(
            model = settings.model,
            messages = messages.map(::toOpenAiMessage),
            tools = tools.takeIf(List<LlmTool>::isNotEmpty)?.map(::toOpenAiTool),
            toolChoice = tools.takeIf(List<LlmTool>::isNotEmpty)?.let { config.toolChoice.toWireValue() },
            maxTokens = config.maxTokens.takeIf { settings.kind == LlmProviderKind.OPENAI_COMPATIBLE },
            maxCompletionTokens = config.maxTokens.takeIf { settings.kind == LlmProviderKind.OPENAI },
            temperature = config.temperature,
            responseFormat = if (config.jsonMode && LlmCapability.JSON_MODE in capabilities()) {
                OpenAiFormat("json_object")
            } else {
                null
            },
        )

        val response = executeLlmRequest(
            transport = transport,
            request = LlmHttpRequest(
                url = settings.endpoint("chat/completions"),
                headers = requestHeaders(settings.authentication),
                body = json.encodeToString(request),
            ),
            maxRetries = settings.maxRetries,
        )
        if (response.status !in HTTP_SUCCESS_RANGE) {
            logger.error { "${provider()} completion failed with HTTP ${response.status}" }
            throw IllegalStateException("${provider()} completion failed with HTTP ${response.status}")
        }

        val parsed = json.decodeFromString(OpenAiResponse.serializer(), response.body)
        val choice = parsed.choices.firstOrNull()
            ?: throw IllegalStateException("${provider()} returned no completion choices")
        return LlmResponse(
            content = choice.message.content.orEmpty(),
            toolCalls = choice.message.toolCalls.orEmpty().map(::toLlmToolCall),
            inputTokens = parsed.usage?.promptTokens ?: 0,
            outputTokens = parsed.usage?.completionTokens ?: 0,
            model = parsed.model.ifBlank { settings.model },
            provider = provider(),
        )
    }

    private fun requireCapabilityForTools(tools: List<LlmTool>) {
        check(!(tools.isNotEmpty() && LlmCapability.TOOL_CALLING !in capabilities())) {
            "${provider()} is not configured for tool calling"
        }
    }

    private fun toOpenAiMessage(message: LlmMessage): OpenAiMessage = OpenAiMessage(
        role = message.role,
        content = message.content,
        toolCallId = message.toolCallId,
        toolCalls = message.toolCalls.takeIf(List<LlmToolCall>::isNotEmpty)?.map { call ->
            OpenAiToolCall(
                id = call.id,
                type = "function",
                function = OpenAiToolCallFunction(
                    name = call.name,
                    arguments = call.arguments
                        ?.let { arguments -> json.encodeToString(JsonObject.serializer(), arguments) }
                        ?: EMPTY_TOOL_ARGUMENTS,
                ),
            )
        },
    )

    private fun toOpenAiTool(tool: LlmTool): OpenAiTool = OpenAiTool(
        type = "function",
        function = OpenAiFunction(tool.name, tool.description, tool.parameters),
    )

    private fun toLlmToolCall(call: OpenAiToolCall): LlmToolCall {
        val arguments = runCatching {
            json.parseToJsonElement(call.function.arguments).jsonObject
        }.onFailure { error ->
            logger.warn(error) {
                "${provider()} returned invalid arguments for tool ${call.function.name}"
            }
        }.getOrNull()
        return LlmToolCall(call.id, call.function.name, arguments)
    }

    private fun requestHeaders(authentication: LlmAuthentication): Map<String, String> = when (authentication) {
        is LlmAuthentication.Bearer -> mapOf("Authorization" to "Bearer ${authentication.token}")
        is LlmAuthentication.Header -> mapOf(authentication.name to authentication.value)
        LlmAuthentication.None -> emptyMap()
    }

    private fun LlmToolChoice.toWireValue(): String = name.lowercase()

    private companion object {
        const val EMPTY_TOOL_ARGUMENTS = "{}"
        val HTTP_SUCCESS_RANGE = 200..299
    }
}

@Serializable
private data class OpenAiMessage(
    val role: String = "",
    val content: String? = null,
    @SerialName("tool_call_id") val toolCallId: String? = null,
    @SerialName("tool_calls") val toolCalls: List<OpenAiToolCall>? = null,
)

@Serializable
private data class OpenAiFormat(val type: String)

@Serializable
private data class OpenAiRequest(
    val model: String,
    val messages: List<OpenAiMessage>,
    val tools: List<OpenAiTool>? = null,
    @SerialName("tool_choice") val toolChoice: String? = null,
    @SerialName("max_tokens") val maxTokens: Int? = null,
    @SerialName("max_completion_tokens") val maxCompletionTokens: Int? = null,
    val temperature: Double,
    @SerialName("response_format") val responseFormat: OpenAiFormat? = null,
)

@Serializable
private data class OpenAiTool(
    val type: String,
    val function: OpenAiFunction,
)

@Serializable
private data class OpenAiFunction(
    val name: String,
    val description: String,
    val parameters: JsonObject,
)

@Serializable
private data class OpenAiResponse(
    val model: String = "",
    val choices: List<OpenAiChoice> = emptyList(),
    val usage: OpenAiUsage? = null,
)

@Serializable
private data class OpenAiChoice(val message: OpenAiMessage)

@Serializable
private data class OpenAiToolCall(
    val id: String,
    val type: String,
    val function: OpenAiToolCallFunction,
)

@Serializable
private data class OpenAiToolCallFunction(
    val name: String,
    val arguments: String,
)

@Serializable
private data class OpenAiUsage(
    @SerialName("prompt_tokens") val promptTokens: Int = 0,
    @SerialName("completion_tokens") val completionTokens: Int = 0,
)
