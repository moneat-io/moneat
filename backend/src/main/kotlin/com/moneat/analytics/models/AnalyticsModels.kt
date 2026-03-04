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

package com.moneat.analytics.models

import kotlinx.serialization.Serializable

/** Inbound payload from the tracking script. */
@Serializable
data class AnalyticsEventPayload(
    val n: String = "pageview",
    val u: String,
    val d: String,
    val r: String? = null,
    val w: Int = 0,
    val p: Map<String, String>? = null,
)

/** Internal representation after enrichment, before ClickHouse insert. */
@Serializable
data class EnrichedAnalyticsEvent(
    val projectId: Long,
    val sessionId: String,
    val eventName: String,
    val hostname: String,
    val pathname: String,
    val referrer: String,
    val referrerSource: String,
    val utmSource: String,
    val utmMedium: String,
    val utmCampaign: String,
    val utmTerm: String,
    val utmContent: String,
    val countryCode: String,
    val subdivision: String,
    val city: String,
    val browser: String,
    val browserVersion: String,
    val os: String,
    val osVersion: String,
    val deviceType: String,
    val screenWidth: Int,
    val props: Map<String, String>,
    val timestamp: Long,
)

// --- Dashboard API response models ---

@Serializable
data class AnalyticsOverviewResponse(
    val visitors: Long,
    val pageviews: Long,
    val bounceRate: Double,
    val avgVisitDuration: Double,
    val viewsPerVisit: Double,
    val compVisitors: Long? = null,
    val compPageviews: Long? = null,
    val compBounceRate: Double? = null,
    val compAvgVisitDuration: Double? = null,
    val compViewsPerVisit: Double? = null,
)

@Serializable
data class TimeseriesPoint(
    val date: String,
    val visitors: Long,
    val pageviews: Long,
)

@Serializable
data class BreakdownRow(
    val name: String,
    val visitors: Long,
    val pageviews: Long,
    val bounceRate: Double? = null,
    val avgDuration: Double? = null,
)

@Serializable
data class BreakdownResponse(
    val results: List<BreakdownRow>,
)

@Serializable
data class RealtimeResponse(
    val visitors: Long,
)

@Serializable
data class FunnelStep(
    val name: String,
    val visitors: Long,
    val dropoff: Double,
)

@Serializable
data class FunnelResponse(
    val steps: List<FunnelStep>,
)

@Serializable
data class AnalyticsFilter(
    val property: String,
    val operator: String,
    val value: String,
)
