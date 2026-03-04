// Moneat Enterprise - proprietary module
// Copyright (c) 2026 Moneat. All rights reserved.
// See ee/LICENSE for license terms.

package com.moneat.enterprise.ai.llm

import com.moneat.enterprise.ai.llm.costs.Claude4OpusCalculator
import com.moneat.enterprise.ai.llm.costs.Claude4SonnetCalculator
import com.moneat.enterprise.ai.llm.costs.Gpt4oCalculator
import com.moneat.enterprise.ai.llm.costs.Gpt4oMiniCalculator
import java.math.BigDecimal

/**
 * Registry that maps model names to their cost calculators.
 */
object CostRegistry {

    private val calculators: Map<String, LlmCostCalculator> = listOf(
        Gpt4oMiniCalculator(),
        Gpt4oCalculator(),
        Claude4SonnetCalculator(),
        Claude4OpusCalculator(),
    ).associateBy { it.model() }

    fun getCalculator(model: String): LlmCostCalculator? = calculators[model]

    fun calculateCost(model: String, inputTokens: Int, outputTokens: Int): LlmCost {
        return getCalculator(model)?.calculateCost(inputTokens, outputTokens)
            ?: LlmCost(BigDecimal.ZERO, BigDecimal.ZERO, BigDecimal.ZERO)
    }
}
