package com.moneat.models

/**
 * Pricing tier limits with GB-based pricing model.
 * -1 for replay limit means unlimited.
 * null for maxProjects means unlimited.
 */
enum class PricingTier(
    val monthlyErrorLimit: Long,
    val monthlyReplayLimit: Long,
    val monthlyGbBytes: Long,  // GB limit in bytes
    val retentionDays: Int,
    val maxProjects: Int?,
    val maxSystems: Int,
    val monitorIntervalSeconds: Int
) {
    FREE(
        monthlyErrorLimit = 10_000,
        monthlyReplayLimit = 0,
        monthlyGbBytes = 1_073_741_824L,  // 1 GB
        retentionDays = 7,
        maxProjects = 1,
        maxSystems = 3,
        monitorIntervalSeconds = 60
    ),
    PRO(
        monthlyErrorLimit = 500_000,
        monthlyReplayLimit = 50,
        monthlyGbBytes = 53_687_091_200L,  // 50 GB
        retentionDays = 30,
        maxProjects = null,
        maxSystems = 10,
        monitorIntervalSeconds = 30
    ),
    TEAM(
        monthlyErrorLimit = 5_000_000,
        monthlyReplayLimit = -1,
        monthlyGbBytes = 214_748_364_800L,  // 200 GB
        retentionDays = 90,
        maxProjects = null,
        maxSystems = 25,
        monitorIntervalSeconds = 10
    ),
    BUSINESS(
        monthlyErrorLimit = Long.MAX_VALUE,
        monthlyReplayLimit = -1,
        monthlyGbBytes = 1_099_511_627_776L,  // 1 TB
        retentionDays = 180,
        maxProjects = null,
        maxSystems = Int.MAX_VALUE,
        monitorIntervalSeconds = 10
    )
}
