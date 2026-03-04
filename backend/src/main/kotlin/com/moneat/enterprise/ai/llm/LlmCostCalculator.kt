// Moneat Enterprise - proprietary module
// Copyright (c) 2026 Moneat. All rights reserved.
// See enterprise/LICENSE for license terms.

package com.moneat.enterprise.ai.llm

import java.math.BigDecimal

/**
 * Calculates cost for a specific LLM model based on token usage.
 */
interface LlmCostCalculator {
    fun calculateCost(inputTokens: Int, outputTokens: Int): LlmCost
    fun model(): String
    fun provider(): String
}

data class LlmCost(
    val inputCost: BigDecimal,
    val outputCost: BigDecimal,
    val totalCost: BigDecimal,
    val currency: String = "USD",
)
