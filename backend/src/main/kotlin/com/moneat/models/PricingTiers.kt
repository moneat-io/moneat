// Moneat - observability platform
// Copyright (C) 2026 Moneat
//
// This program is free software: you can redistribute it and/or modify
// it under the terms of the GNU Affero General Public License as published by
// the Free Software Foundation, either version 3 of the License, or
// (at your option) any later version.
//
// This program is distributed in the hope that it will be useful,
// but WITHOUT ANY WARRANTY; without even the implied warranty of
// MERCHANTABILITY or FITNESS FOR A PARTICULAR PURPOSE. See the
// GNU Affero General Public License for more details.
//
// You should have received a copy of the GNU Affero General Public License
// along with this program. If not, see <https://www.gnu.org/licenses/>.

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
        retentionDays = 3,
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
        retentionDays = 30,
        maxProjects = null,
        maxSystems = 25,
        monitorIntervalSeconds = 10
    ),
    BUSINESS(
        monthlyErrorLimit = Long.MAX_VALUE,
        monthlyReplayLimit = -1,
        monthlyGbBytes = 1_099_511_627_776L,  // 1 TB
        retentionDays = 90,
        maxProjects = null,
        maxSystems = Int.MAX_VALUE,
        monitorIntervalSeconds = 10
    )
}
