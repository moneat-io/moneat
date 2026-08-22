// Moneat Enterprise - proprietary module
// Copyright (c) 2026 Moneat. All rights reserved.
// See ee/LICENSE for license terms.

package com.moneat.enterprise.ai.llm

import com.moneat.enterprise.ai.llm.providers.AnthropicProvider
import com.moneat.enterprise.ai.llm.providers.OpenAiProvider
import mu.KotlinLogging

private val logger = KotlinLogging.logger {}

/**
 * Creates the one configured provider used by all enterprise AI surfaces.
 */
object LlmProviderFactory {

    fun create(): LlmProvider {
        val settings = LlmProviderSettingsLoader.load()
        val provider = when (settings.kind) {
            LlmProviderKind.ANTHROPIC -> AnthropicProvider(settings)
            LlmProviderKind.OPENAI,
            LlmProviderKind.OPENAI_COMPATIBLE,
            -> OpenAiProvider(settings)
        }
        logger.info {
            "LLM provider configured: ${provider.provider()} / ${provider.model()} " +
                "(${provider.capabilities().joinToString()})"
        }
        return provider
    }

    /** Check whether the configured AI provider has usable authentication. */
    fun isAnyProviderEnabled(): Boolean = LlmProviderSettingsLoader.load().isEnabled
}
