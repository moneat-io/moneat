package com.moneat.models

/**
 * Pricing tier limits per MONETIZATION.md.
 * -1 for replay limit means unlimited.
 * null for maxProjects means unlimited.
 */
enum class PricingTier(
    val monthlyErrorLimit: Long,
    val monthlyReplayLimit: Long,
    val retentionDays: Int,
    val maxProjects: Int?
) {
    FREE(10_000, 0, 7, 1),
    PRO(500_000, 50, 30, null),
    TEAM(5_000_000, -1, 90, null)
}
