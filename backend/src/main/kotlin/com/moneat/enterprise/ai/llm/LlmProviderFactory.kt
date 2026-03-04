// Moneat Enterprise - proprietary module
// Copyright (c) 2026 Moneat. All rights reserved.
// See enterprise/LICENSE for license terms.

package com.moneat.enterprise.ai.llm

import com.moneat.config.EnvConfig
import com.moneat.enterprise.ai.llm.providers.AnthropicProvider
import com.moneat.enterprise.ai.llm.providers.OpenAiProvider
import mu.KotlinLogging

private val logger = KotlinLogging.logger {}

/**
 * Factory that returns the configured LLM provider based on environment variables.
 *
 * Configuration:
 *   AI_PROVIDER = "openai" | "anthropic"  (default: "openai")
 */
object LlmProviderFactory {

    fun create(): LlmProvider {
        val providerName = EnvConfig.get("AI_PROVIDER", "openai").lowercase()
        val provider = when (providerName) {
            "anthropic" -> AnthropicProvider()
            else -> OpenAiProvider()
        }
        logger.info { "LLM provider configured: ${provider.provider()} / ${provider.model()}" }
        return provider
    }

    /** Check whether any AI provider is configured. */
    fun isAnyProviderEnabled(): Boolean {
        return OpenAiProvider().isEnabled() || AnthropicProvider().isEnabled()
    }
}
