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

package com.moneat.billing.services

import com.moneat.billing.models.APM_SPAN_USAGE_DEBUG_DEFAULT_LIMIT
import com.moneat.billing.models.ApmSpanUsageDebugResponse
import com.moneat.billing.models.BillingContributor
import com.moneat.billing.models.BillingForecast
import com.moneat.billing.models.BillingInsightDailyPoint
import com.moneat.billing.models.BillingInsightDimension
import com.moneat.billing.models.BillingUsageInsightsResponse
import com.moneat.billing.models.BillingUsageResponse
import com.moneat.config.EnvConfig
import com.moneat.shared.models.Projects
import com.moneat.shared.models.UsageRecords
import com.moneat.shared.services.UsageTrackingService
import com.moneat.utils.suspendRunCatching
import kotlinx.datetime.LocalDate
import kotlinx.datetime.TimeZone
import kotlinx.datetime.todayIn
import mu.KotlinLogging
import org.jetbrains.exposed.v1.core.and
import org.jetbrains.exposed.v1.core.eq
import org.jetbrains.exposed.v1.core.greaterEq
import org.jetbrains.exposed.v1.core.inList
import org.jetbrains.exposed.v1.core.lessEq
import org.jetbrains.exposed.v1.jdbc.selectAll
import org.jetbrains.exposed.v1.jdbc.transactions.transaction
import java.util.Locale
import kotlin.math.ceil
import kotlin.math.max
import kotlin.math.roundToLong
import kotlin.time.Clock

private val insightsLogger = KotlinLogging.logger {}

class BillingUsageInsightsService(
    private val quotaService: BillingQuotaService = BillingQuotaService(),
    private val usageTrackingService: UsageTrackingService = UsageTrackingService.instance
) {
    companion object {
        private const val BYTES_PER_GB = 1_073_741_824L
        private const val PERCENT_MULTIPLIER = 100.0
        private const val SEVEN_DAY_WINDOW = 7
        private const val THIRTY_DAY_WINDOW = 30
        private const val MIN_HIGH_CONFIDENCE_DAYS = 5
        private const val MIN_RECENT_DAYS = 3
        private const val CONTRIBUTOR_LIMIT = 6
        private const val UNLIMITED_SENTINEL = 9_007_199_254_740_000L
        private const val UNITS_PER_MILLION = 1_000_000L
        private const val UNITS_PER_MILLION_DOUBLE = 1_000_000.0
        private const val UNITS_PER_THOUSAND = 1_000.0
        private const val CENTS_PER_DOLLAR = 100.0
        private const val UNITS_PER_HUNDRED_THOUSAND = 100_000L
        private val GB_EXCLUDED_BYTE_TYPES = setOf("apm_span", "infra_metric")
    }

    suspend fun getUsageInsights(organizationId: Int): BillingUsageInsightsResponse {
        val usage = quotaService.getUsageForOrganization(organizationId)
        val periodStart = LocalDate.parse(usage.periodStart)
        val periodEnd = LocalDate.parse(usage.periodEnd)
        val today = Clock.System.todayIn(TimeZone.UTC)
        val forecastStart = minDate(periodStart, addDays(today, -(THIRTY_DAY_WINDOW - 1)))
        usageTrackingService.flushBuffer()

        val history = loadUsageHistory(organizationId, forecastStart, maxDate(today, periodEnd))
        val periodHistory = history.filter { it.date >= periodStart && it.date <= minDate(today, periodEnd) }
        val apmSpanDebug = loadApmSpanDebug(organizationId, periodStart, periodEnd)

        return BillingUsageInsightsResponse(
            organizationId = organizationId,
            periodStart = usage.periodStart,
            periodEnd = usage.periodEnd,
            generatedAt = Clock.System.now().toString(),
            billingMode = if (EnvConfig.SelfHost.enabled) "self_hosted" else "cloud",
            usage = usage,
            dimensions = buildDimensions(usage, history, periodHistory, today, periodStart, periodEnd),
            apmSpanDebug = apmSpanDebug
        )
    }

    private suspend fun loadApmSpanDebug(
        organizationId: Int,
        periodStart: LocalDate,
        periodEnd: LocalDate
    ): ApmSpanUsageDebugResponse {
        return quotaService.getApmSpanUsageDebug(
            organizationId = organizationId,
            periodStart = periodStart,
            periodEnd = periodEnd,
            limit = APM_SPAN_USAGE_DEBUG_DEFAULT_LIMIT
        )
    }

    private fun buildDimensions(
        usage: BillingUsageResponse,
        history: List<UsageHistoryRow>,
        periodHistory: List<UsageHistoryRow>,
        today: LocalDate,
        periodStart: LocalDate,
        periodEnd: LocalDate
    ): List<BillingInsightDimension> {
        return listOf(
            buildIngestionDimension(usage, history, periodHistory, today, periodStart, periodEnd),
            buildCountDimension(
                spec = DimensionSpec(
                    key = "apm_span",
                    label = "APM Spans",
                    unit = "spans",
                    normalizedTypes = setOf("apm_span"),
                    baseLimit = usage.apmSpanLimit,
                    used = usage.usedApmSpans,
                    overageRateCents = usage.apmSpanOverageRateCentsPer1m,
                    overageDivisor = UNITS_PER_MILLION,
                    overageRateLabel = rateLabel(usage.apmSpanOverageRateCentsPer1m, "1M spans"),
                    overageCentsEstimate = usage.apmSpanOverageCentsEstimate
                ),
                usage = usage,
                history = history,
                periodHistory = periodHistory,
                today = today,
                periodStart = periodStart,
                periodEnd = periodEnd
            ),
            buildCountDimension(
                spec = DimensionSpec(
                    key = "custom_metric",
                    label = "Custom Metrics",
                    unit = "metrics",
                    normalizedTypes = setOf("custom_metric"),
                    baseLimit = usage.customMetricLimit,
                    used = usage.usedCustomMetrics,
                    overageRateCents = usage.customMetricOverageRateCentsPer100k,
                    overageDivisor = UNITS_PER_HUNDRED_THOUSAND,
                    overageRateLabel = rateLabel(usage.customMetricOverageRateCentsPer100k, "100K metrics"),
                    overageCentsEstimate = usage.customMetricOverageCentsEstimate
                ),
                usage = usage,
                history = history,
                periodHistory = periodHistory,
                today = today,
                periodStart = periodStart,
                periodEnd = periodEnd
            ),
            buildCountDimension(
                spec = DimensionSpec(
                    key = "infra_metric",
                    label = "Infrastructure Metrics",
                    unit = "series-hours",
                    normalizedTypes = setOf("infra_metric"),
                    baseLimit = usage.infraMetricSeriesHourLimit,
                    used = usage.usedInfraMetricSeriesHours,
                    overageRateCents = usage.infraMetricOverageRateCentsPer100kSeriesHours,
                    overageDivisor = UNITS_PER_HUNDRED_THOUSAND,
                    overageRateLabel = rateLabel(
                        usage.infraMetricOverageRateCentsPer100kSeriesHours,
                        "100K series-hours"
                    ),
                    overageCentsEstimate = usage.infraMetricOverageCentsEstimate
                ),
                usage = usage,
                history = history,
                periodHistory = periodHistory,
                today = today,
                periodStart = periodStart,
                periodEnd = periodEnd
            ),
            buildCountDimension(
                spec = DimensionSpec(
                    key = "analytics_pageview",
                    label = "Analytics Pageviews",
                    unit = "pageviews",
                    normalizedTypes = setOf("analytics_pageview"),
                    baseLimit = usage.analyticsPageviewLimit,
                    used = usage.usedAnalyticsPageviews,
                    overageRateCents = usage.analyticsPageviewOverageRateCentsPer100k,
                    overageDivisor = UNITS_PER_HUNDRED_THOUSAND,
                    overageRateLabel = rateLabel(usage.analyticsPageviewOverageRateCentsPer100k, "100K pageviews"),
                    overageCentsEstimate = usage.analyticsPageviewOverageCentsEstimate
                ),
                usage = usage,
                history = history,
                periodHistory = periodHistory,
                today = today,
                periodStart = periodStart,
                periodEnd = periodEnd
            )
        )
    }

    private fun buildIngestionDimension(
        usage: BillingUsageResponse,
        history: List<UsageHistoryRow>,
        periodHistory: List<UsageHistoryRow>,
        today: LocalDate,
        periodStart: LocalDate,
        periodEnd: LocalDate
    ): BillingInsightDimension {
        val used = gbBilledBytes(usage)
        val baseLimit = usage.bytesLimit
        val effectiveLimit = finiteEffectiveLimit(baseLimit, usage.bonusGbBytes + usage.paygLimitBytes)
        val historyFilter: (UsageHistoryRow) -> Boolean = { row -> row.normalizedType !in GB_EXCLUDED_BYTE_TYPES }
        val daily = dailyPoints(periodHistory, periodStart, minDate(today, periodEnd), historyFilter, useBytes = true)
        val forecast = buildForecast(
            spec = ForecastSpec(
                label = "GB-billed ingestion",
                unit = "bytes",
                used = used,
                baseLimit = baseLimit,
                effectiveLimit = effectiveLimit,
                overageRateCents = usage.ingestionOverageRateCentsPerGb,
                overageDivisor = BYTES_PER_GB
            ),
            history = history,
            today = today,
            periodStart = periodStart,
            periodEnd = periodEnd,
            historyFilter = historyFilter,
            useBytes = true
        )

        return BillingInsightDimension(
            key = "ingestion",
            label = "GB-Billed Ingestion",
            unit = "bytes",
            used = used,
            baseLimit = baseLimit,
            effectiveLimit = effectiveLimit,
            percentOfBase = percentage(used, baseLimit),
            percentOfEffective = effectiveLimit?.let { percentage(used, it) },
            overageCentsEstimate = usage.ingestionOverageCentsEstimate,
            overageRateLabel = rateLabel(usage.ingestionOverageRateCentsPerGb, "GB"),
            forecast = forecast,
            contributors = ingestionContributors(usage, periodHistory, used),
            daily = daily
        )
    }

    private fun buildCountDimension(
        spec: DimensionSpec,
        usage: BillingUsageResponse,
        history: List<UsageHistoryRow>,
        periodHistory: List<UsageHistoryRow>,
        today: LocalDate,
        periodStart: LocalDate,
        periodEnd: LocalDate
    ): BillingInsightDimension {
        val effectiveLimit = if (spec.overageRateCents > 0) {
            null
        } else {
            finiteEffectiveLimit(spec.baseLimit, usage.bonusUnits)
        }
        val historyFilter: (UsageHistoryRow) -> Boolean = { row -> row.normalizedType in spec.normalizedTypes }
        val daily = dailyPoints(periodHistory, periodStart, minDate(today, periodEnd), historyFilter, useBytes = false)
        val forecast = buildForecast(
            spec = ForecastSpec(
                label = spec.label,
                unit = spec.unit,
                used = spec.used,
                baseLimit = spec.baseLimit,
                effectiveLimit = effectiveLimit,
                overageRateCents = spec.overageRateCents,
                overageDivisor = spec.overageDivisor
            ),
            history = history,
            today = today,
            periodStart = periodStart,
            periodEnd = periodEnd,
            historyFilter = historyFilter,
            useBytes = false
        )

        return BillingInsightDimension(
            key = spec.key,
            label = spec.label,
            unit = spec.unit,
            used = spec.used,
            baseLimit = spec.baseLimit,
            effectiveLimit = effectiveLimit,
            percentOfBase = percentage(spec.used, spec.baseLimit),
            percentOfEffective = effectiveLimit?.let { percentage(spec.used, it) },
            overageCentsEstimate = spec.overageCentsEstimate,
            overageRateLabel = spec.overageRateLabel,
            forecast = forecast,
            contributors = usageRecordContributors(periodHistory, historyFilter, spec.used),
            daily = daily
        )
    }

    private fun buildForecast(
        spec: ForecastSpec,
        history: List<UsageHistoryRow>,
        today: LocalDate,
        periodStart: LocalDate,
        periodEnd: LocalDate,
        historyFilter: (UsageHistoryRow) -> Boolean,
        useBytes: Boolean
    ): BillingForecast {
        val selected = selectForecastWindow(history, today, periodStart, historyFilter, useBytes)
        val remainingDays = max(0, daysBetween(today, periodEnd))
        val projected = spec.used + (selected.dailyRate * remainingDays).roundToLong()
        val baseHitDate = projectedHitDate(spec.used, selected.dailyRate, spec.baseLimit, today, periodEnd)
        val effectiveHitDate = spec.effectiveLimit?.let {
            projectedHitDate(spec.used, selected.dailyRate, it, today, periodEnd)
        }
        val projectedOverageCents = projectedOverageCents(
            projected = projected,
            limit = spec.baseLimit,
            rateCents = spec.overageRateCents,
            divisor = spec.overageDivisor
        )
        val riskLevel = riskLevel(spec.used, spec.baseLimit, spec.effectiveLimit, baseHitDate, effectiveHitDate)

        return BillingForecast(
            window = selected.window,
            confidence = selected.confidence,
            dailyRate = selected.dailyRate,
            projectedPeriodEndUsage = projected.coerceAtLeast(spec.used),
            projectedBaseLimitHitDate = baseHitDate?.toString(),
            projectedEffectiveLimitHitDate = effectiveHitDate?.toString(),
            projectedOverageCents = projectedOverageCents,
            riskLevel = riskLevel,
            summary = forecastSummary(spec.label, spec.unit, selected.dailyRate, baseHitDate, effectiveHitDate)
        )
    }

    private fun selectForecastWindow(
        history: List<UsageHistoryRow>,
        today: LocalDate,
        periodStart: LocalDate,
        historyFilter: (UsageHistoryRow) -> Boolean,
        useBytes: Boolean
    ): ForecastWindow {
        val recentStart = addDays(today, -(SEVEN_DAY_WINDOW - 1))
        val recent = windowRate(history, recentStart, today, historyFilter, useBytes, SEVEN_DAY_WINDOW)
        if (recent.activeDays >= MIN_RECENT_DAYS) {
            return ForecastWindow(
                window = "7d",
                confidence = if (recent.activeDays >= MIN_HIGH_CONFIDENCE_DAYS) "high" else "medium",
                dailyRate = recent.dailyRate
            )
        }

        val periodDays = max(1, daysBetween(periodStart, today) + 1)
        val period = windowRate(history, periodStart, today, historyFilter, useBytes, periodDays)
        if (period.total > 0) {
            return ForecastWindow(window = "period_to_date", confidence = "medium", dailyRate = period.dailyRate)
        }

        val thirtyStart = addDays(today, -(THIRTY_DAY_WINDOW - 1))
        val thirty = windowRate(history, thirtyStart, today, historyFilter, useBytes, THIRTY_DAY_WINDOW)
        if (thirty.total > 0) {
            return ForecastWindow(window = "30d", confidence = "low", dailyRate = thirty.dailyRate)
        }

        return ForecastWindow(window = "insufficient_data", confidence = "low", dailyRate = 0.0)
    }

    private fun windowRate(
        history: List<UsageHistoryRow>,
        start: LocalDate,
        end: LocalDate,
        historyFilter: (UsageHistoryRow) -> Boolean,
        useBytes: Boolean,
        dayCount: Int
    ): WindowRate {
        val rows = history.filter { it.date >= start && it.date <= end && historyFilter(it) }
        val total = rows.sumOf { if (useBytes) it.bytes else it.count }
        val activeDays = rows.groupBy { it.date }
            .count { (_, dayRows) -> dayRows.sumOf { if (useBytes) it.bytes else it.count } > 0 }
        return WindowRate(
            total = total,
            activeDays = activeDays,
            dailyRate = if (dayCount > 0) total.toDouble() / dayCount.toDouble() else 0.0
        )
    }

    private fun ingestionContributors(
        usage: BillingUsageResponse,
        periodHistory: List<UsageHistoryRow>,
        used: Long
    ): List<BillingContributor> {
        val known = listOf(
            usageContributor("error", "Errors", "event_type", usage.usedErrors, usage.usedErrorBytes, used),
            usageContributor("replay", "Replays", "event_type", usage.usedReplays, usage.usedReplayBytes, used),
            usageContributor("log", "Logs", "event_type", usage.usedLogs, usage.usedLogBytes, used),
            usageContributor("llm", "LLM", "event_type", usage.usedLlmEvents, usage.usedLlmBytes, used),
            usageContributor("profile", "Profiling", "event_type", 0, usage.usedProfilerBytes, used)
        )
        val knownBytes = known.sumOf { it.bytes }
        val otherBytes = (used - knownBytes).coerceAtLeast(0)
        val eventContributors = (known + usageContributor("other", "Other", "event_type", 0, otherBytes, used))
            .filter { it.bytes > 0 || it.units > 0 }
        val projectContributors = usageRecordContributors(
            history = periodHistory,
            historyFilter = { row -> row.normalizedType !in GB_EXCLUDED_BYTE_TYPES },
            used = used,
            useBytes = true
        )
        return (eventContributors + projectContributors).take(CONTRIBUTOR_LIMIT)
    }

    private fun usageRecordContributors(
        history: List<UsageHistoryRow>,
        historyFilter: (UsageHistoryRow) -> Boolean,
        used: Long,
        useBytes: Boolean = false
    ): List<BillingContributor> {
        return history
            .filter(historyFilter)
            .groupBy { it.projectId to it.normalizedType }
            .map { (key, rows) ->
                val bytes = rows.sumOf { it.bytes }
                val units = rows.sumOf { it.count }
                val projectId = key.first
                val normalizedType = key.second
                BillingContributor(
                    key = listOf(projectId ?: "org", normalizedType).joinToString(":"),
                    label = contributorLabel(projectId, rows.firstOrNull(), normalizedType),
                    kind = if (projectId == null) "source" else "project",
                    eventType = normalizedType,
                    projectId = projectId,
                    projectName = rows.firstOrNull()?.projectName,
                    projectSlug = rows.firstOrNull()?.projectSlug,
                    units = units,
                    bytes = bytes,
                    percentage = percentage(if (useBytes) bytes else units, used)
                )
            }
            .filter { it.units > 0 || it.bytes > 0 }
            .sortedByDescending { if (useBytes) it.bytes else it.units }
            .take(CONTRIBUTOR_LIMIT)
    }

    private fun dailyPoints(
        history: List<UsageHistoryRow>,
        start: LocalDate,
        end: LocalDate,
        historyFilter: (UsageHistoryRow) -> Boolean,
        useBytes: Boolean
    ): List<BillingInsightDailyPoint> {
        if (end < start) return emptyList()
        val valuesByDate = history
            .filter(historyFilter)
            .groupBy { it.date }
            .mapValues { (_, rows) ->
                DailyValue(
                    value = rows.sumOf { if (useBytes) it.bytes else it.count },
                    bytes = rows.sumOf { it.bytes }
                )
            }

        return dateRange(start, end).map { date ->
            val value = valuesByDate[date] ?: DailyValue(value = 0, bytes = 0)
            BillingInsightDailyPoint(
                date = date.toString(),
                value = value.value,
                bytes = value.bytes
            )
        }
    }

    private fun loadUsageHistory(
        organizationId: Int,
        start: LocalDate,
        end: LocalDate
    ): List<UsageHistoryRow> {
        val rows = transaction {
            UsageRecords
                .selectAll()
                .where {
                    (UsageRecords.organization_id eq organizationId) and
                        (UsageRecords.recordDate greaterEq start) and
                        (UsageRecords.recordDate lessEq end)
                }
                .map { row ->
                    RawUsageHistoryRow(
                        date = row[UsageRecords.recordDate],
                        projectId = row[UsageRecords.project_id],
                        eventType = row[UsageRecords.event_type],
                        count = row[UsageRecords.event_count].toLong(),
                        bytes = row[UsageRecords.bytes_ingested]
                    )
                }
        }
        val projectLabels = loadProjectLabels(organizationId, rows.mapNotNull { it.projectId }.toSet())
        return rows.map { row ->
            val label = row.projectId?.let { projectLabels[it] }
            UsageHistoryRow(
                date = row.date,
                projectId = row.projectId,
                projectName = label?.name,
                projectSlug = label?.slug,
                normalizedType = normalizeEventType(row.eventType),
                count = row.count,
                bytes = row.bytes
            )
        }
    }

    private fun loadProjectLabels(
        organizationId: Int,
        projectIds: Set<Int>
    ): Map<Int, ProjectLabel> {
        if (projectIds.isEmpty()) return emptyMap()
        return transaction {
            Projects
                .selectAll()
                .where {
                    (Projects.organization_id eq organizationId) and
                        (Projects.id inList projectIds.map { it.toLong() })
                }
                .associate { row ->
                    row[Projects.id].toInt() to ProjectLabel(
                        name = row[Projects.name],
                        slug = row[Projects.slug]
                    )
                }
        }
    }

    private fun usageContributor(
        key: String,
        label: String,
        kind: String,
        units: Long,
        bytes: Long,
        used: Long
    ): BillingContributor {
        return BillingContributor(
            key = key,
            label = label,
            kind = kind,
            eventType = key.takeIf { it != "other" },
            units = units,
            bytes = bytes,
            percentage = percentage(bytes, used)
        )
    }

    private fun contributorLabel(
        projectId: Int?,
        row: UsageHistoryRow?,
        normalizedType: String
    ): String {
        val typeLabel = eventTypeLabel(normalizedType)
        return if (projectId == null) {
            typeLabel
        } else {
            "${row?.projectName ?: row?.projectSlug ?: "Project $projectId"} - $typeLabel"
        }
    }

    private fun projectedHitDate(
        used: Long,
        dailyRate: Double,
        limit: Long,
        today: LocalDate,
        periodEnd: LocalDate
    ): LocalDate? {
        if (!isFinitePositiveLimit(limit)) return null
        if (used >= limit) return today
        if (dailyRate <= 0.0) return null
        val daysUntil = ceil((limit - used).toDouble() / dailyRate).toLong()
        val hitDate = addDays(today, daysUntil)
        return hitDate.takeIf { it <= periodEnd }
    }

    private fun projectedOverageCents(
        projected: Long,
        limit: Long,
        rateCents: Int,
        divisor: Long
    ): Int {
        if (!isFinitePositiveLimit(limit) || rateCents <= 0 || projected <= limit) return 0
        return (((projected - limit) * rateCents) / divisor).toInt().coerceAtLeast(0)
    }

    private fun riskLevel(
        used: Long,
        baseLimit: Long,
        effectiveLimit: Long?,
        baseHitDate: LocalDate?,
        effectiveHitDate: LocalDate?
    ): String {
        return when {
            effectiveLimit != null && used >= effectiveLimit -> "critical"
            isFinitePositiveLimit(baseLimit) && used >= baseLimit -> "warning"
            effectiveHitDate != null -> "warning"
            baseHitDate != null -> "watch"
            else -> "ok"
        }
    }

    private fun forecastSummary(
        label: String,
        unit: String,
        dailyRate: Double,
        baseHitDate: LocalDate?,
        effectiveHitDate: LocalDate?
    ): String {
        return when {
            effectiveHitDate != null -> "$label is projected to hit effective capacity on $effectiveHitDate."
            baseHitDate != null -> "$label is projected to hit the included limit on $baseHitDate."
            dailyRate > 0.0 -> "$label is averaging ${formatRate(dailyRate, unit)} per day."
            else -> "$label does not have enough recent usage for a forecast yet."
        }
    }

    private fun formatRate(value: Double, unit: String): String {
        if (unit == "bytes") return "${"%.2f".format(Locale.US, value / BYTES_PER_GB.toDouble())} GB"
        return when {
            value >= UNITS_PER_MILLION_DOUBLE ->
                "${"%.2f".format(Locale.US, value / UNITS_PER_MILLION_DOUBLE)}M $unit"
            value >= UNITS_PER_THOUSAND -> "${"%.1f".format(Locale.US, value / UNITS_PER_THOUSAND)}K $unit"
            else -> "${"%.1f".format(Locale.US, value)} $unit"
        }
    }

    private fun percentage(value: Long, limit: Long): Double {
        if (!isFinitePositiveLimit(limit)) return 0.0
        return (value.toDouble() / limit.toDouble() * PERCENT_MULTIPLIER)
            .coerceAtLeast(0.0)
    }

    private fun finiteEffectiveLimit(baseLimit: Long, extra: Long): Long? {
        if (!isFinitePositiveLimit(baseLimit)) return null
        return (baseLimit + extra.coerceAtLeast(0)).coerceAtLeast(baseLimit)
    }

    private fun isFinitePositiveLimit(limit: Long): Boolean {
        return limit > 0 && limit < UNLIMITED_SENTINEL
    }

    private fun gbBilledBytes(usage: BillingUsageResponse): Long {
        return (usage.usedBytes - usage.usedApmSpanBytes - usage.usedInfraMetricBytes).coerceAtLeast(0)
    }

    private fun rateLabel(rateCents: Int, unit: String): String? {
        if (rateCents <= 0) return null
        return "$${"%.2f".format(Locale.US, rateCents / CENTS_PER_DOLLAR)}/$unit"
    }

    private fun normalizeEventType(eventType: String): String {
        return when (eventType.lowercase()) {
            "error" -> "error"
            "transaction" -> "transaction"
            "replay" -> "replay"
            "feedback" -> "feedback"
            "llm" -> "llm"
            "log", "logs", "dd_log" -> "log"
            "apm_span", "apm", "otlp_trace", "dd_trace", "sentry_trace" -> "apm_span"
            "custom_metric", "metric", "otlp_metric", "dd_metric" -> "custom_metric"
            "infra_metric", "dd_infra_metric" -> "infra_metric"
            "analytics_pageview" -> "analytics_pageview"
            "dd_profile", "profile" -> "profile"
            else -> eventType.lowercase()
        }
    }

    private fun eventTypeLabel(eventType: String): String {
        return when (eventType) {
            "error" -> "Errors"
            "transaction" -> "Transactions"
            "replay" -> "Replays"
            "feedback" -> "Feedback"
            "llm" -> "LLM"
            "log" -> "Logs"
            "apm_span" -> "APM spans"
            "custom_metric" -> "Custom metrics"
            "infra_metric" -> "Infrastructure metrics"
            "analytics_pageview" -> "Analytics pageviews"
            "profile" -> "Profiling"
            else -> eventType.replace('_', ' ').replaceFirstChar { it.uppercase() }
        }
    }

    private fun dateRange(start: LocalDate, end: LocalDate): List<LocalDate> {
        val dates = mutableListOf<LocalDate>()
        var current = start
        while (current <= end) {
            dates.add(current)
            current = addDays(current, 1)
        }
        return dates
    }

    private fun addDays(date: LocalDate, days: Int): LocalDate {
        return LocalDate.parse(java.time.LocalDate.parse(date.toString()).plusDays(days.toLong()).toString())
    }

    private fun addDays(date: LocalDate, days: Long): LocalDate {
        return LocalDate.parse(java.time.LocalDate.parse(date.toString()).plusDays(days).toString())
    }

    private fun daysBetween(start: LocalDate, end: LocalDate): Int {
        return java.time.temporal.ChronoUnit.DAYS.between(
            java.time.LocalDate.parse(start.toString()),
            java.time.LocalDate.parse(end.toString())
        ).toInt()
    }

    private fun minDate(a: LocalDate, b: LocalDate): LocalDate = if (a <= b) a else b

    private fun maxDate(a: LocalDate, b: LocalDate): LocalDate = if (a >= b) a else b

    private data class UsageHistoryRow(
        val date: LocalDate,
        val projectId: Int?,
        val projectName: String?,
        val projectSlug: String?,
        val normalizedType: String,
        val count: Long,
        val bytes: Long
    )

    private data class RawUsageHistoryRow(
        val date: LocalDate,
        val projectId: Int?,
        val eventType: String,
        val count: Long,
        val bytes: Long
    )

    private data class ProjectLabel(
        val name: String,
        val slug: String
    )

    private data class DimensionSpec(
        val key: String,
        val label: String,
        val unit: String,
        val normalizedTypes: Set<String>,
        val baseLimit: Long,
        val used: Long,
        val overageRateCents: Int,
        val overageDivisor: Long,
        val overageRateLabel: String?,
        val overageCentsEstimate: Int
    )

    private data class ForecastSpec(
        val label: String,
        val unit: String,
        val used: Long,
        val baseLimit: Long,
        val effectiveLimit: Long?,
        val overageRateCents: Int,
        val overageDivisor: Long
    )

    private data class ForecastWindow(
        val window: String,
        val confidence: String,
        val dailyRate: Double
    )

    private data class WindowRate(
        val total: Long,
        val activeDays: Int,
        val dailyRate: Double
    )

    private data class DailyValue(
        val value: Long,
        val bytes: Long
    )
}

suspend fun BillingUsageInsightsService.getUsageInsightsSafely(
    organizationId: Int
): BillingUsageInsightsResponse? {
    return suspendRunCatching {
        getUsageInsights(organizationId)
    }.getOrElse { e ->
        insightsLogger.warn(e) { "Failed to build billing usage insights for org $organizationId" }
        null
    }
}
