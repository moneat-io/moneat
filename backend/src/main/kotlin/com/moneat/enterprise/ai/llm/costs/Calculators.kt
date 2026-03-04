// Moneat Enterprise - proprietary module
// Copyright (c) 2026 Moneat. All rights reserved.
// See enterprise/LICENSE for license terms.

package com.moneat.enterprise.ai.llm.costs

import com.moneat.enterprise.ai.llm.LlmCost
import com.moneat.enterprise.ai.llm.LlmCostCalculator
import java.math.BigDecimal
import java.math.RoundingMode

/** GPT-4o-mini: $0.15 / 1M input, $0.60 / 1M output */
class Gpt4oMiniCalculator : LlmCostCalculator {
    override fun model() = "gpt-4o-mini"
    override fun provider() = "openai"
    override fun calculateCost(inputTokens: Int, outputTokens: Int): LlmCost {
        val inputCost = BigDecimal(inputTokens).multiply(BigDecimal("0.00000015"))
        val outputCost = BigDecimal(outputTokens).multiply(BigDecimal("0.0000006"))
        return LlmCost(
            inputCost = inputCost.setScale(6, RoundingMode.HALF_UP),
            outputCost = outputCost.setScale(6, RoundingMode.HALF_UP),
            totalCost = inputCost.add(outputCost).setScale(6, RoundingMode.HALF_UP),
        )
    }
}

/** GPT-4o: $2.50 / 1M input, $10.00 / 1M output */
class Gpt4oCalculator : LlmCostCalculator {
    override fun model() = "gpt-4o"
    override fun provider() = "openai"
    override fun calculateCost(inputTokens: Int, outputTokens: Int): LlmCost {
        val inputCost = BigDecimal(inputTokens).multiply(BigDecimal("0.0000025"))
        val outputCost = BigDecimal(outputTokens).multiply(BigDecimal("0.00001"))
        return LlmCost(
            inputCost = inputCost.setScale(6, RoundingMode.HALF_UP),
            outputCost = outputCost.setScale(6, RoundingMode.HALF_UP),
            totalCost = inputCost.add(outputCost).setScale(6, RoundingMode.HALF_UP),
        )
    }
}

/** Claude 4 Sonnet: $3.00 / 1M input, $15.00 / 1M output */
class Claude4SonnetCalculator : LlmCostCalculator {
    override fun model() = "claude-sonnet-4-20250514"
    override fun provider() = "anthropic"
    override fun calculateCost(inputTokens: Int, outputTokens: Int): LlmCost {
        val inputCost = BigDecimal(inputTokens).multiply(BigDecimal("0.000003"))
        val outputCost = BigDecimal(outputTokens).multiply(BigDecimal("0.000015"))
        return LlmCost(
            inputCost = inputCost.setScale(6, RoundingMode.HALF_UP),
            outputCost = outputCost.setScale(6, RoundingMode.HALF_UP),
            totalCost = inputCost.add(outputCost).setScale(6, RoundingMode.HALF_UP),
        )
    }
}

/** Claude 4 Opus: $15.00 / 1M input, $75.00 / 1M output */
class Claude4OpusCalculator : LlmCostCalculator {
    override fun model() = "claude-opus-4-20250514"
    override fun provider() = "anthropic"
    override fun calculateCost(inputTokens: Int, outputTokens: Int): LlmCost {
        val inputCost = BigDecimal(inputTokens).multiply(BigDecimal("0.000015"))
        val outputCost = BigDecimal(outputTokens).multiply(BigDecimal("0.000075"))
        return LlmCost(
            inputCost = inputCost.setScale(6, RoundingMode.HALF_UP),
            outputCost = outputCost.setScale(6, RoundingMode.HALF_UP),
            totalCost = inputCost.add(outputCost).setScale(6, RoundingMode.HALF_UP),
        )
    }
}
