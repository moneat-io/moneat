// Moneat Enterprise - proprietary module
// Copyright (c) 2026 Moneat. All rights reserved.
// See ee/LICENSE for license terms.

package com.moneat.enterprise.ai.llm

import org.junit.jupiter.api.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertFailsWith
import kotlin.test.assertIs

class LlmProviderSettingsTest {
    @Test
    fun `loads a fully configurable OpenAI-compatible endpoint`() {
        val environment = mapOf(
            "AI_PROVIDER" to "openai-compatible",
            "AI_BASE_URL" to "http://llm.internal/v1/",
            "AI_MODEL" to "local-model",
            "AI_API_KEY" to "local-secret",
            "AI_AUTH_TYPE" to "header",
            "AI_AUTH_HEADER" to "X-Api-Key",
            "AI_REQUEST_TIMEOUT_MS" to "45000",
            "AI_MAX_RETRIES" to "3",
            "AI_CAPABILITIES" to "tool-calling,streaming",
        )

        val settings = LlmProviderSettingsLoader.load(environment::get)

        assertEquals(LlmProviderKind.OPENAI_COMPATIBLE, settings.kind)
        assertEquals("http://llm.internal/v1", settings.baseUrl)
        assertEquals("local-model", settings.model)
        assertEquals("http://llm.internal/v1/chat/completions", settings.endpoint("chat/completions"))
        assertEquals(45_000, settings.requestTimeoutMillis)
        assertEquals(3, settings.maxRetries)
        assertEquals(setOf(LlmCapability.TOOL_CALLING, LlmCapability.STREAMING), settings.capabilities)
        val authentication = assertIs<LlmAuthentication.Header>(settings.authentication)
        assertEquals("X-Api-Key", authentication.name)
        assertEquals("local-secret", authentication.value)
    }

    @Test
    fun `legacy provider keys remain compatible`() {
        val settings = LlmProviderSettingsLoader.load(
            mapOf(
                "AI_PROVIDER" to "anthropic",
                "AI_API_KEY" to "",
                "ANTHROPIC_API_KEY" to "legacy-key",
                "ANTHROPIC_MODEL" to "legacy-model",
            )::get,
        )

        assertEquals("legacy-model", settings.model)
        assertEquals("https://api.anthropic.com", settings.baseUrl)
        assertEquals("legacy-key", assertIs<LlmAuthentication.Header>(settings.authentication).value)
    }

    @Test
    fun `unknown providers fail closed`() {
        assertFailsWith<IllegalArgumentException> {
            LlmProviderSettingsLoader.load(mapOf("AI_PROVIDER" to "mystery")::get)
        }
    }

    @Test
    fun `malformed numeric settings fail closed`() {
        assertFailsWith<IllegalArgumentException> {
            LlmProviderSettingsLoader.load(mapOf("AI_REQUEST_TIMEOUT_MS" to "eventually")::get)
        }
        assertFailsWith<IllegalArgumentException> {
            LlmProviderSettingsLoader.load(mapOf("AI_MAX_RETRIES" to "often")::get)
        }
    }

    @Test
    fun `blank optional settings use provider defaults`() {
        val settings = LlmProviderSettingsLoader.load(
            mapOf(
                "AI_PROVIDER" to "  ",
                "AI_REQUEST_TIMEOUT_MS" to " ",
                "AI_MAX_RETRIES" to "\t",
                "AI_CAPABILITIES" to "\n",
            )::get,
        )

        assertEquals(LlmProviderKind.OPENAI, settings.kind)
        assertEquals(120_000, settings.requestTimeoutMillis)
        assertEquals(2, settings.maxRetries)
        assertEquals(LlmCapability.entries.toSet(), settings.capabilities)
    }

    @Test
    fun `no-auth mode requires an explicit endpoint`() {
        assertFailsWith<IllegalArgumentException> {
            LlmProviderSettingsLoader.load(mapOf("AI_AUTH_TYPE" to "none")::get)
        }

        val settings = LlmProviderSettingsLoader.load(
            mapOf(
                "AI_PROVIDER" to "openai-compatible",
                "AI_AUTH_TYPE" to "none",
                "AI_BASE_URL" to "http://llm.internal",
            )::get,
        )
        assertIs<LlmAuthentication.None>(settings.authentication)
    }

    @Test
    fun `auth prefix cannot enable an empty custom header key`() {
        val settings = LlmProviderSettingsLoader.load(
            mapOf(
                "AI_AUTH_TYPE" to "header",
                "AI_AUTH_PREFIX" to "Bearer",
            )::get,
        )

        assertFalse(settings.isEnabled)
        assertEquals("", assertIs<LlmAuthentication.Header>(settings.authentication).value)
    }
}
