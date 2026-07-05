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
import kotlinx.serialization.SerialName

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
    val userId: String = "",
    val eventName: String,
    val source: String = "web",
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

@Serializable
data class ServerAnalyticsEventPayload(
    val events: List<ServerAnalyticsEvent> = emptyList(),
)

@Serializable
data class ServerAnalyticsEvent(
    val name: String = "",
    @SerialName("user_id")
    val userId: String = "",
    @SerialName("session_id")
    val sessionId: String = "",
    val props: Map<String, String> = emptyMap(),
    val timestamp: Long? = null,
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
    val conversionRate: Double,
)

@Serializable
data class FunnelResponse(
    val steps: List<FunnelStep>,
    val overallConversion: Double,
)

@Serializable
data class RetentionPeriod(
    val days: Int,
    val retainedUsers: Long,
    val eligibleUsers: Long,
    val retentionRate: Double,
)

@Serializable
data class RetentionCohort(
    val cohortWeek: String,
    val users: Long,
    val periods: List<RetentionPeriod>,
)

@Serializable
data class RetentionResponse(
    val startEvent: String,
    val returnEvent: String,
    val cohorts: List<RetentionCohort>,
)

@Serializable
data class AnalyticsFilter(
    val property: String,
    val operator: String,
    val value: String,
)

@Serializable
data class EventPropertyFilter(
    val key: String,
    val operator: String,
    val value: String,
)

@Serializable
data class ProductKpiMetric(
    val value: Double,
    val previous: Double? = null,
    val spark: List<Double> = emptyList(),
)

@Serializable
data class ProductAnalyticsSummary(
    val weeklyActiveUsers: ProductKpiMetric,
    val dailyActiveUsers: Long? = null,
    val newUsers: ProductKpiMetric,
    val activationRate: ProductKpiMetric,
    val stickiness: ProductKpiMetric,
    val week1Retention: ProductKpiMetric,
    val powerUsers: ProductKpiMetric,
)

@Serializable
data class ProductActivityPoint(
    val timestamp: String,
    val value: Long,
    val previous: Long? = null,
)

@Serializable
data class ProductActivitySeries(
    val metric: String,
    val points: List<ProductActivityPoint>,
)

@Serializable
data class ProductActivityAnnotation(
    val date: String,
    val label: String,
    val kind: String? = null,
)

@Serializable
data class ProductActivityResponse(
    val series: List<ProductActivitySeries>,
    val annotations: List<ProductActivityAnnotation> = emptyList(),
)

@Serializable
data class ProductMover(
    val name: String,
    val category: String,
    val detail: String? = null,
    val change: String,
    val tone: String,
)

@Serializable
data class ProductFeatureAdoptionItem(
    val name: String,
    val adoptionRate: Double,
)

@Serializable
data class ProductSegmentRow(
    val name: String,
    val users: Long,
    val activationRate: Double,
    val week1Retention: Double,
    val stickiness: Double,
)

@Serializable
data class ProductSegmentation(
    val plan: List<ProductSegmentRow>,
    val platform: List<ProductSegmentRow>,
    val country: List<ProductSegmentRow>,
)

@Serializable
data class ProductRetentionCohortRow(
    val cohort: String,
    val users: Long,
    val values: List<Double?>,
)

@Serializable
data class ProductRetentionGrid(
    val mode: String,
    val periods: List<Int>,
    val cohorts: List<ProductRetentionCohortRow>,
)
