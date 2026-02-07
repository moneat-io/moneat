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
    val maxProjects: Int?,
    val maxSystems: Int,
    val monitorIntervalSeconds: Int
) {
    FREE(10_000, 0, 7, 1, 1, 60),
    PRO(500_000, 50, 30, null, 5, 15),
    TEAM(5_000_000, -1, 90, null, 25, 10)
}
