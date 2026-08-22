// Moneat Enterprise - proprietary module
// Copyright (c) 2026 Moneat. All rights reserved.
// See ee/LICENSE for license terms.

package com.moneat.enterprise.ai.llm

import com.moneat.config.EnvConfig

enum class LlmProviderKind(val configValue: String) {
    OPENAI("openai"),
    OPENAI_COMPATIBLE("openai-compatible"),
    ANTHROPIC("anthropic"),
    ;

    companion object {
        fun fromConfig(value: String): LlmProviderKind = entries.firstOrNull {
            it.configValue == value.trim().lowercase()
        } ?: throw IllegalArgumentException(
            "Unsupported AI_PROVIDER '$value'. Expected openai, openai-compatible, or anthropic.",
        )
    }
}

sealed interface LlmAuthentication {
    data class Bearer(val token: String) : LlmAuthentication
    data class Header(val name: String, val value: String) : LlmAuthentication
    data object None : LlmAuthentication
}

data class LlmProviderSettings(
    val kind: LlmProviderKind,
    val baseUrl: String,
    val model: String,
    val authentication: LlmAuthentication,
    val requestTimeoutMillis: Long,
    val maxRetries: Int,
    val capabilities: Set<LlmCapability>,
) {
    init {
        require(baseUrl.isNotBlank()) { "AI provider base URL cannot be blank" }
        require(model.isNotBlank()) { "AI provider model cannot be blank" }
        require(requestTimeoutMillis > 0) { "AI_REQUEST_TIMEOUT_MS must be positive" }
        require(maxRetries in 0..MAX_RETRIES_LIMIT) {
            "AI_MAX_RETRIES must be between 0 and $MAX_RETRIES_LIMIT"
        }
    }

    val isEnabled: Boolean
        get() = when (val auth = authentication) {
            is LlmAuthentication.Bearer -> auth.token.isNotBlank()
            is LlmAuthentication.Header -> auth.name.isNotBlank() && auth.value.isNotBlank()
            LlmAuthentication.None -> true
        }

    fun endpoint(path: String): String {
        val normalizedBaseUrl = baseUrl.trimEnd('/')
        val apiRoot = if (normalizedBaseUrl.endsWith("/v1")) normalizedBaseUrl else "$normalizedBaseUrl/v1"
        return "$apiRoot/${path.trimStart('/')}"
    }

    companion object {
        const val MAX_RETRIES_LIMIT = 5
    }
}

object LlmProviderSettingsLoader {
    private const val DEFAULT_TIMEOUT_MILLIS = 120_000L
    private const val DEFAULT_MAX_RETRIES = 2

    fun load(get: (String) -> String? = EnvConfig::get): LlmProviderSettings {
        val kind = LlmProviderKind.fromConfig(get("AI_PROVIDER") ?: LlmProviderKind.OPENAI.configValue)
        val baseUrl = get("AI_BASE_URL")?.trimEnd('/') ?: defaultBaseUrl(kind)
        val model = get("AI_MODEL")?.takeIf(String::isNotBlank) ?: legacyModel(kind, get)
        val timeout = parseLongOrDefault("AI_REQUEST_TIMEOUT_MS", get, DEFAULT_TIMEOUT_MILLIS)
        val maxRetries = parseIntOrDefault("AI_MAX_RETRIES", get, DEFAULT_MAX_RETRIES)
        val capabilities = get("AI_CAPABILITIES")
            ?.let(::parseCapabilities)
            ?: defaultCapabilities(kind)

        return LlmProviderSettings(
            kind = kind,
            baseUrl = baseUrl,
            model = model,
            authentication = loadAuthentication(kind, get),
            requestTimeoutMillis = timeout,
            maxRetries = maxRetries,
            capabilities = capabilities,
        )
    }

    private fun loadAuthentication(
        kind: LlmProviderKind,
        get: (String) -> String?,
    ): LlmAuthentication {
        val sharedKey = get("AI_API_KEY")?.takeIf(String::isNotBlank)
        val providerKey = when (kind) {
            LlmProviderKind.ANTHROPIC -> get("ANTHROPIC_API_KEY")?.takeIf(String::isNotBlank)
            LlmProviderKind.OPENAI,
            LlmProviderKind.OPENAI_COMPATIBLE,
            -> get("OPENAI_API_KEY")?.takeIf(String::isNotBlank)
        }
        val apiKey = sharedKey ?: providerKey.orEmpty()
        return when (get("AI_AUTH_TYPE")?.trim()?.lowercase()) {
            null, "", "default" -> defaultAuthentication(kind, apiKey)
            "bearer" -> LlmAuthentication.Bearer(apiKey)
            "header" -> LlmAuthentication.Header(
                name = get("AI_AUTH_HEADER")?.takeIf(String::isNotBlank) ?: "Authorization",
                value = get("AI_AUTH_PREFIX")
                    ?.takeIf(String::isNotBlank)
                    ?.let { prefix -> "$prefix $apiKey" }
                    ?: apiKey,
            )
            "none" -> LlmAuthentication.None
            else -> throw IllegalArgumentException(
                "Unsupported AI_AUTH_TYPE. Expected default, bearer, header, or none.",
            )
        }
    }

    private fun defaultAuthentication(kind: LlmProviderKind, apiKey: String): LlmAuthentication = when (kind) {
        LlmProviderKind.ANTHROPIC -> LlmAuthentication.Header("x-api-key", apiKey)
        LlmProviderKind.OPENAI,
        LlmProviderKind.OPENAI_COMPATIBLE,
        -> LlmAuthentication.Bearer(apiKey)
    }

    private fun defaultBaseUrl(kind: LlmProviderKind): String = when (kind) {
        LlmProviderKind.ANTHROPIC -> "https://api.anthropic.com"
        LlmProviderKind.OPENAI,
        LlmProviderKind.OPENAI_COMPATIBLE,
        -> "https://api.openai.com"
    }

    private fun legacyModel(kind: LlmProviderKind, get: (String) -> String?): String = when (kind) {
        LlmProviderKind.ANTHROPIC -> get("ANTHROPIC_MODEL") ?: "claude-sonnet-4-20250514"
        LlmProviderKind.OPENAI,
        LlmProviderKind.OPENAI_COMPATIBLE,
        -> get("OPENAI_MODEL") ?: "gpt-4o-mini"
    }

    private fun defaultCapabilities(kind: LlmProviderKind): Set<LlmCapability> = when (kind) {
        LlmProviderKind.ANTHROPIC -> setOf(LlmCapability.STREAMING, LlmCapability.TOOL_CALLING)
        LlmProviderKind.OPENAI,
        LlmProviderKind.OPENAI_COMPATIBLE,
        -> LlmCapability.entries.toSet()
    }

    private fun parseCapabilities(value: String): Set<LlmCapability> = value
        .split(',')
        .map(String::trim)
        .filter(String::isNotBlank)
        .map { raw ->
            LlmCapability.entries.firstOrNull { capability ->
                capability.name.equals(raw.replace('-', '_'), ignoreCase = true)
            } ?: throw IllegalArgumentException("Unsupported AI capability '$raw'")
        }
        .toSet()

    private fun parseLongOrDefault(
        key: String,
        get: (String) -> String?,
        default: Long,
    ): Long = get(key)?.let { raw ->
        raw.toLongOrNull() ?: throw IllegalArgumentException("$key must be an integer")
    } ?: default

    private fun parseIntOrDefault(
        key: String,
        get: (String) -> String?,
        default: Int,
    ): Int = get(key)?.let { raw ->
        raw.toIntOrNull() ?: throw IllegalArgumentException("$key must be an integer")
    } ?: default
}
