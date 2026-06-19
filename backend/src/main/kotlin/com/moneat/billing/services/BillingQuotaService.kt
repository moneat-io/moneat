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

import com.moneat.billing.models.APM_SPAN_USAGE_DEBUG_MAX_LIMIT
import com.moneat.billing.models.APM_SPAN_USAGE_DEBUG_MIN_LIMIT
import com.moneat.billing.models.AdminQuotaUsageResetResponse
import com.moneat.billing.models.ApmSpanUsageDebugGroup
import com.moneat.billing.models.ApmSpanUsageDebugResponse
import com.moneat.billing.models.BillingUsageResponse
import com.moneat.billing.models.OrgUsageCounters
import com.moneat.billing.models.PricingTierConfigs
import com.moneat.config.ClickHouseClient
import com.moneat.config.EnvConfig
import com.moneat.shared.models.Organizations
import com.moneat.shared.models.Projects
import com.moneat.shared.models.Subscriptions
import com.moneat.shared.services.organizationResourceId
import com.moneat.utils.ClickHouseQueryUtils
import com.moneat.utils.ClickHouseSqlUtils.escapeSql
import com.moneat.utils.SentryUtils
import com.moneat.utils.suspendRunCatching
import kotlinx.datetime.LocalDate
import kotlinx.datetime.TimeZone
import kotlinx.datetime.toLocalDateTime
import kotlinx.datetime.todayIn
import kotlinx.serialization.Serializable
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.contentOrNull
import kotlinx.serialization.json.doubleOrNull
import kotlinx.serialization.json.jsonObject
import kotlinx.serialization.json.jsonPrimitive
import kotlinx.serialization.json.longOrNull
import mu.KotlinLogging
import org.jetbrains.exposed.v1.core.SortOrder
import org.jetbrains.exposed.v1.core.and
import org.jetbrains.exposed.v1.core.eq
import org.jetbrains.exposed.v1.core.inList
import org.jetbrains.exposed.v1.jdbc.insert
import org.jetbrains.exposed.v1.jdbc.selectAll
import org.jetbrains.exposed.v1.jdbc.transactions.TransactionManager
import org.jetbrains.exposed.v1.jdbc.transactions.transaction
import org.jetbrains.exposed.v1.jdbc.update
import kotlin.math.max
import kotlin.math.roundToLong
import kotlin.time.Clock

private val logger = KotlinLogging.logger {}
private val billingQuotaJson = Json { ignoreUnknownKeys = true }

data class QuotaReservationResult(
    val allowed: Boolean,
    val reason: String? = null,
    val eventType: String? = null,
    val usage: BillingUsageResponse
)

@Serializable
data class QuotaExceededResponse(
    val error: String = "Quota exceeded",
    val reason: String? = null,
    val usage: BillingUsageResponse
)

private data class QuotaState(
    val organizationId: Int,
    val plan: String,
    val status: String,
    val retentionDays: Int,
    val logRetentionDays: Int,
    val replayRetentionDays: Int,
    val llmRetentionDays: Int,
    val apmTraceRetentionDays: Int,
    val subscriptionId: Int?,
    val periodStart: LocalDate,
    val periodEnd: LocalDate,
    val usedUnits: Long,
    val usedErrors: Long,
    val usedTransactions: Long,
    val usedReplays: Long,
    val usedFeedback: Long,
    val usedLlmEvents: Long,
    val usedLogs: Long,
    val usedBytes: Long,
    val usedApmSpanBytes: Long,
    val usedInfraMetricBytes: Long,
    val usedErrorBytes: Long,
    val usedReplayBytes: Long,
    val usedLogBytes: Long,
    val usedLlmBytes: Long,
    val usedProfilerBytes: Long,
    val errorLimit: Long,
    val transactionLimit: Long,
    val replayLimit: Long,
    val feedbackLimit: Long,
    val llmEventLimit: Long,
    val bytesLimit: Long,
    val baseLimitUnits: Long,
    val paygLimitUnits: Long,
    val totalLimitUnits: Long,
    val paygBudgetCents: Int,
    val paygUsedUnits: Long,
    val paygUsedMicros: Long,
    val pendingMeterUnits: Long,
    val pendingOverageBytes: Long,
    val paygRateMicrosPerUnit: Long,
    val paygLimitBytes: Long,
    val oncallSeats: Int,
    val oncallUsedSeats: Int,
    val oncallPerUserMonthlyCents: Int,
    val oncallEnabled: Boolean,
    val teamsEnabled: Boolean,
    val bonusGbBytes: Long,
    val bonusUnits: Long,
    val bonusReason: String?,
    val errorOverageRateCentsPer1k: Int,
    val replayOverageRateCentsPerGb: Int,
    val logOverageRateCentsPerGb: Int,
    val llmOverageRateCentsPer1k: Int,
    val overageRateCentsPerGb: Int,
    val usedAnalyticsPageviews: Long,
    val analyticsPageviewLimit: Long,
    val analyticsPageviewOverageRateCentsPer100k: Int,
    val usedApmSpans: Long,
    val apmSpanLimit: Long,
    val apmSpanOverageRateCentsPer1m: Int,
    val usedCustomMetrics: Long,
    val customMetricLimit: Long,
    val customMetricOverageRateCentsPer100k: Int,
    val usedInfraMetricSeriesHours: Long,
    val infraMetricSeriesHourLimit: Long,
    val infraMetricOverageRateCentsPer100kSeriesHours: Int,
    val pendingApmSpanOverageUnits: Long,
    val pendingCustomMetricOverageUnits: Long,
    val pendingInfraMetricOverageUnits: Long,
    val pendingAnalyticsPageviewOverageUnits: Long,
)

private data class ReservationAmounts(
    val errors: Long,
    val transactions: Long,
    val replays: Long,
    val feedback: Long,
    val llm: Long,
    val logs: Long,
    val apmSpans: Long,
    val customMetrics: Long,
    val infraMetricSeriesHours: Long,
    val analyticsPageviews: Long,
    val errorBytes: Long,
    val replayBytes: Long,
    val logBytes: Long,
    val llmBytes: Long,
    val apmSpanBytes: Long,
    val infraMetricBytes: Long,
    val profilerBytes: Long
)

private data class RefundedUsage(
    val usedUnits: Long,
    val usedErrors: Long,
    val usedTransactions: Long,
    val usedReplays: Long,
    val usedFeedback: Long,
    val usedLlmEvents: Long,
    val usedLogs: Long,
    val usedApmSpans: Long,
    val usedCustomMetrics: Long,
    val usedInfraMetricSeriesHours: Long,
    val usedAnalyticsPageviews: Long,
    val usedBytes: Long,
    val usedApmSpanBytes: Long,
    val usedInfraMetricBytes: Long,
    val usedErrorBytes: Long,
    val usedReplayBytes: Long,
    val usedLogBytes: Long,
    val usedLlmBytes: Long,
    val usedProfilerBytes: Long
)

private data class RawApmSpanDebugGroup(
    val source: String,
    val service: String,
    val operation: String,
    val resource: String,
    val spanType: String,
    val env: String,
    val kind: String,
    val scopeName: String,
    val scopeVersion: String,
    val projectId: Long?,
    val spanCount: Long,
    val traceCount: Long,
    val errorCount: Long,
    val avgDurationMs: Double,
    val maxDurationMs: Double,
    val sampleTraceId: String,
    val latestSpanAt: String
)

private data class ApmSpanProjectLabel(
    val resourceId: String,
    val name: String,
    val slug: String
)

class BillingQuotaService(
    private val pricingTierService: PricingTierService = PricingTierService()
) {
    companion object {
        private const val BYTES_PER_GB = 1_073_741_824L
        private const val MICROS_PER_CENT = 10_000L
        private const val UNITS_PER_THOUSAND = 1_000L
        private const val UNITS_PER_MILLION = 1_000_000L
        private const val UNITS_PER_HUNDRED_THOUSAND = 100_000L
        private const val PERCENT_MULTIPLIER = 100.0
        private const val MIN_QUOTA_TARGET_PERCENT = 0.0
        private const val MAX_QUOTA_TARGET_PERCENT = 500.0
        private const val ORGANIZATION_NOT_FOUND_MESSAGE = "Organization not found"
        private const val NANOS_PER_MILLISECOND = 1_000_000.0
        private const val DURATION_DECIMAL_PLACES = 3
        private const val INFRA_METRIC_TYPE = "infra_metric"
        private val NON_AGGREGATE_UNIT_TYPES = setOf(
            "llm",
            "log",
            "apm_span",
            "custom_metric",
            INFRA_METRIC_TYPE,
            "analytics_pageview"
        )
        private val COUNT_GATED_UNIT_TYPES = setOf(
            "custom_metric",
            "apm_span",
            INFRA_METRIC_TYPE,
            "analytics_pageview"
        )
        private val GB_EXCLUDED_BYTE_TYPES = setOf("apm_span", INFRA_METRIC_TYPE)
    }

    fun isEnforcementEnabled(): Boolean {
        return !EnvConfig.SelfHost.enabled
    }

    fun getUsageForOrganization(organizationId: Int): BillingUsageResponse {
        return transaction {
            val state = loadQuotaState(organizationId, lockRows = false)
            toUsageResponse(state)
        }
    }

    suspend fun getApmSpanUsageDebug(
        organizationId: Int,
        periodStart: LocalDate,
        periodEnd: LocalDate,
        limit: Int
    ): ApmSpanUsageDebugResponse {
        val safeLimit = limit.coerceIn(APM_SPAN_USAGE_DEBUG_MIN_LIMIT, APM_SPAN_USAGE_DEBUG_MAX_LIMIT)
        return suspendRunCatching {
            val whereClause = apmSpanDebugWhereClause(organizationId, periodStart, periodEnd)
            val totalSpans = queryApmSpanDebugTotal(whereClause)
            val rawGroups = if (totalSpans > 0) {
                queryApmSpanDebugGroups(whereClause, safeLimit)
            } else {
                emptyList()
            }
            val projectLabels = loadApmSpanProjectLabels(
                organizationId = organizationId,
                projectIds = rawGroups.mapNotNull { it.projectId }.toSet()
            )

            ApmSpanUsageDebugResponse(
                organizationId = organizationResourceId(organizationId),
                periodStart = periodStart.toString(),
                periodEnd = periodEnd.toString(),
                totalSpans = totalSpans,
                groups = rawGroups.map { group ->
                    val project = group.projectId?.let { projectLabels[it] }
                    ApmSpanUsageDebugGroup(
                        source = group.source,
                        service = group.service,
                        operation = group.operation,
                        resource = group.resource,
                        spanType = group.spanType,
                        env = group.env,
                        kind = group.kind,
                        scopeName = group.scopeName,
                        scopeVersion = group.scopeVersion,
                        projectId = project?.resourceId,
                        projectName = project?.name,
                        projectSlug = project?.slug,
                        spanCount = group.spanCount,
                        traceCount = group.traceCount,
                        errorCount = group.errorCount,
                        avgDurationMs = group.avgDurationMs,
                        maxDurationMs = group.maxDurationMs,
                        percentage = if (totalSpans > 0) {
                            group.spanCount.toDouble() / totalSpans.toDouble() * PERCENT_MULTIPLIER
                        } else {
                            0.0
                        },
                        sampleTraceId = group.sampleTraceId,
                        latestSpanAt = group.latestSpanAt
                    )
                }
            )
        }.getOrElse { e ->
            logger.warn(e) { "Failed to query APM span usage debug for org $organizationId" }
            ApmSpanUsageDebugResponse(
                organizationId = organizationResourceId(organizationId),
                periodStart = periodStart.toString(),
                periodEnd = periodEnd.toString(),
                totalSpans = 0,
                groups = emptyList()
            )
        }
    }

    fun incrementUsageCounters(
        organizationId: Int,
        syntheticRuns: Long = 0,
        uptimeChecks: Long = 0,
        aiTokens: Long = 0
    ) {
        if (syntheticRuns <= 0 && uptimeChecks <= 0 && aiTokens <= 0) return

        transaction {
            val state = loadQuotaState(organizationId, lockRows = true)
            val usageRow = OrgUsageCounters
                .selectAll()
                .where {
                    (OrgUsageCounters.organization_id eq organizationId) and
                        (OrgUsageCounters.period_start eq state.periodStart)
                }.first()
            OrgUsageCounters.update({ OrgUsageCounters.id eq usageRow[OrgUsageCounters.id] }) {
                if (syntheticRuns > 0) {
                    it[used_synthetic_runs] = usageRow[OrgUsageCounters.used_synthetic_runs] + syntheticRuns
                }
                if (uptimeChecks > 0) {
                    it[used_uptime_checks] = usageRow[OrgUsageCounters.used_uptime_checks] + uptimeChecks
                }
                if (aiTokens > 0) {
                    it[used_ai_tokens] = usageRow[OrgUsageCounters.used_ai_tokens] + aiTokens
                }
                it[updated_at] = Clock.System.now()
            }
        }
    }

    fun reserveUnits(
        organizationId: Int,
        requestedUnits: Int,
        eventType: String = "error",
        requestedBytes: Long = 0
    ): QuotaReservationResult {
        return reserveUnitsBatch(
            organizationId = organizationId,
            requestedUnitsByType = mapOf(eventType to requestedUnits),
            requestedBytesByType = mapOf(eventType to requestedBytes)
        )
    }

    fun reserveUnitsBatch(
        organizationId: Int,
        requestedUnitsByType: Map<String, Int>,
        requestedBytesByType: Map<String, Long> = emptyMap()
    ): QuotaReservationResult {
        val normalizedRequests = mutableMapOf<String, Long>()
        for ((eventType, requestedUnits) in requestedUnitsByType) {
            if (requestedUnits <= 0) continue
            val normalizedType = normalizeEventType(eventType)
            normalizedRequests[normalizedType] = (normalizedRequests[normalizedType] ?: 0L) + requestedUnits.toLong()
        }

        val normalizedBytes = mutableMapOf<String, Long>()
        for ((eventType, requestedBytes) in requestedBytesByType) {
            if (requestedBytes <= 0) continue
            val normalizedType = normalizeEventType(eventType)
            normalizedBytes[normalizedType] = (normalizedBytes[normalizedType] ?: 0L) + requestedBytes
        }

        if (normalizedRequests.isEmpty() && normalizedBytes.isEmpty()) {
            return QuotaReservationResult(
                allowed = true,
                usage = getUsageForOrganization(organizationId)
            )
        }

        if (!isEnforcementEnabled()) {
            return QuotaReservationResult(
                allowed = true,
                usage = getUsageForOrganization(organizationId)
            )
        }

        return transaction {
            val state = loadQuotaState(organizationId, lockRows = true)
            val requestedAggregate =
                normalizedRequests
                    .filterKeys { it !in NON_AGGREGATE_UNIT_TYPES }
                    .values
                    .sum()
            val totalAfter = state.usedUnits + requestedAggregate
            val requestedTotalBytes = normalizedBytes.values.sum()
            val requestedGbEligibleBytes = normalizedBytes.filterKeys { it !in GB_EXCLUDED_BYTE_TYPES }.values.sum()
            val gbEligibleBytes = gbEligibleBytes(state)
            val gbEligibleBytesAfter = gbEligibleBytes + requestedGbEligibleBytes
            val usedBytesAfter = state.usedBytes + requestedTotalBytes

            exceededCountQuotaResult(organizationId, state, normalizedRequests)
                ?.let { return@transaction it }

            // Unified ingestion model: GB/byte limit is the primary gate for all data types
            // (replaces old per-type count limits and aggregate unit limit)
            val effectiveBytesLimit =
                if (state.bytesLimit > 0) {
                    state.bytesLimit + state.bonusGbBytes + state.paygLimitBytes
                } else {
                    Long.MAX_VALUE
                }
            if (state.bytesLimit > 0 && gbEligibleBytesAfter > effectiveBytesLimit) {
                SentryUtils.breadcrumb(
                    "billing",
                    "GB quota exceeded",
                    mapOf(
                        "organization_id" to organizationId,
                        "requested_bytes" to requestedGbEligibleBytes,
                        "used_bytes" to gbEligibleBytes,
                        "bytes_limit" to state.bytesLimit,
                        "payg_limit_bytes" to state.paygLimitBytes,
                        "bonus_gb_bytes" to state.bonusGbBytes
                    )
                )

                return@transaction QuotaReservationResult(
                    allowed = false,
                    reason = "gb_quota_exceeded",
                    usage = toUsageResponse(state)
                )
            }

            val requested = reservationAmounts(normalizedRequests, normalizedBytes)
            updateReservedUsageCounters(organizationId, state, requested, totalAfter, usedBytesAfter)
            trackReservedOverages(organizationId, state, requested, gbEligibleBytes, gbEligibleBytesAfter)

            val refreshed = loadQuotaState(organizationId, lockRows = false)
            QuotaReservationResult(
                allowed = true,
                usage = toUsageResponse(refreshed)
            )
        }
    }

    /**
     * Refunds previously reserved units when ingestion fails after reserveUnits
     * was committed. Restores OrgUsageCounters and Subscriptions overage tracking.
     */
    fun refundUnits(
        organizationId: Int,
        units: Int,
        eventType: String = "error",
        requestedBytes: Long = 0
    ) {
        if (units <= 0 && requestedBytes <= 0) return
        val normalizedType = normalizeEventType(eventType)
        val requestedUnits = units.toLong()

        transaction {
            val state = loadQuotaState(organizationId, lockRows = true)
            val totalBefore = state.usedUnits
            val refunded = refundedUsage(state, normalizedType, requestedUnits, requestedBytes)

            OrgUsageCounters.update({
                (OrgUsageCounters.organization_id eq organizationId) and
                    (OrgUsageCounters.period_start eq state.periodStart)
            }) {
                it[used_units] = refunded.usedUnits
                it[used_errors] = refunded.usedErrors
                it[used_transactions] = refunded.usedTransactions
                it[used_replays] = refunded.usedReplays
                it[used_feedback] = refunded.usedFeedback
                it[used_llm_events] = refunded.usedLlmEvents
                it[used_logs] = refunded.usedLogs
                it[used_apm_spans] = refunded.usedApmSpans
                it[used_custom_metrics] = refunded.usedCustomMetrics
                it[used_infra_metric_series_hours] = refunded.usedInfraMetricSeriesHours
                it[used_analytics_pageviews] = refunded.usedAnalyticsPageviews
                it[used_bytes] = refunded.usedBytes
                it[used_apm_span_bytes] = refunded.usedApmSpanBytes
                it[used_infra_metric_bytes] = refunded.usedInfraMetricBytes
                it[used_error_bytes] = refunded.usedErrorBytes
                it[used_replay_bytes] = refunded.usedReplayBytes
                it[used_log_bytes] = refunded.usedLogBytes
                it[used_llm_bytes] = refunded.usedLlmBytes
                it[used_profiler_bytes] = refunded.usedProfilerBytes
                it[updated_at] = Clock.System.now()
            }

            if (state.subscriptionId != null) {
                refundPaygOverage(state, totalBefore, refunded.usedUnits)
                refundByteOverage(state, requestedBytes, refunded)
                refundCountOverages(state, normalizedType, refunded)
            }
        }
    }

    fun resetUsageForQuotaType(
        organizationId: Int,
        quotaType: String,
        targetPercent: Double?,
        targetValue: Long?,
        adminUserId: Int
    ): AdminQuotaUsageResetResponse {
        return transaction {
            val organizationExists =
                Organizations
                    .selectAll()
                    .where { Organizations.id eq organizationId }
                    .firstOrNull() != null
            check(organizationExists) { ORGANIZATION_NOT_FOUND_MESSAGE }

            val state = loadQuotaState(organizationId, lockRows = true)
            val target = resolveQuotaUsageTarget(state, quotaType, targetPercent, targetValue)
            val ingestionBytes = target.type.takeIf { it == AdminQuotaUsageType.INGESTION_BYTES }
                ?.let { buildIngestionByteTarget(state, target.targetUsed) }

            OrgUsageCounters.update({
                (OrgUsageCounters.organization_id eq organizationId) and
                    (OrgUsageCounters.period_start eq state.periodStart)
            }) {
                when (target.type) {
                    AdminQuotaUsageType.INGESTION_BYTES -> {
                        val bytes = checkNotNull(ingestionBytes)
                        it[used_bytes] = bytes.usedBytes
                        it[used_error_bytes] = bytes.usedErrorBytes
                        it[used_replay_bytes] = bytes.usedReplayBytes
                        it[used_log_bytes] = bytes.usedLogBytes
                        it[used_llm_bytes] = bytes.usedLlmBytes
                        it[used_profiler_bytes] = bytes.usedProfilerBytes
                    }
                    AdminQuotaUsageType.APM_SPANS -> it[used_apm_spans] = target.targetUsed
                    AdminQuotaUsageType.CUSTOM_METRICS -> it[used_custom_metrics] = target.targetUsed
                    AdminQuotaUsageType.INFRA_METRICS -> it[used_infra_metric_series_hours] = target.targetUsed
                    AdminQuotaUsageType.ERRORS -> {
                        it[used_errors] = target.targetUsed
                        it[used_units] = adjustedAggregateUnits(state, target.currentUsed, target.targetUsed)
                    }
                    AdminQuotaUsageType.TRANSACTIONS -> {
                        it[used_transactions] = target.targetUsed
                        it[used_units] = adjustedAggregateUnits(state, target.currentUsed, target.targetUsed)
                    }
                    AdminQuotaUsageType.REPLAYS -> {
                        it[used_replays] = target.targetUsed
                        it[used_units] = adjustedAggregateUnits(state, target.currentUsed, target.targetUsed)
                    }
                    AdminQuotaUsageType.FEEDBACK -> {
                        it[used_feedback] = target.targetUsed
                        it[used_units] = adjustedAggregateUnits(state, target.currentUsed, target.targetUsed)
                    }
                    AdminQuotaUsageType.LLM_EVENTS -> it[used_llm_events] = target.targetUsed
                    AdminQuotaUsageType.ANALYTICS_PAGEVIEWS -> it[used_analytics_pageviews] = target.targetUsed
                }
                it[updated_at] = Clock.System.now()
            }

            syncPendingOverageForAdminReset(state, target)

            val updatedUsage = toUsageResponse(loadQuotaState(organizationId, lockRows = false))
            logger.info {
                "Admin $adminUserId reset ${target.type.wireName} quota usage for org $organizationId " +
                    "from ${target.currentUsed} to ${target.targetUsed}"
            }
            AdminQuotaUsageResetResponse(
                organizationId = updatedUsage.organizationId,
                quotaType = target.type.wireName,
                periodStart = state.periodStart.toString(),
                periodEnd = state.periodEnd.toString(),
                previousUsed = target.currentUsed,
                updatedUsed = target.targetUsed,
                limit = target.limit,
                targetPercent = target.resolvedPercent,
                usage = updatedUsage
            )
        }
    }

    private enum class AdminQuotaUsageType(val wireName: String) {
        INGESTION_BYTES("ingestion_bytes"),
        APM_SPANS("apm_spans"),
        CUSTOM_METRICS("custom_metrics"),
        INFRA_METRICS("infra_metrics"),
        ERRORS("errors"),
        TRANSACTIONS("transactions"),
        REPLAYS("replays"),
        FEEDBACK("feedback"),
        LLM_EVENTS("llm_events"),
        ANALYTICS_PAGEVIEWS("analytics_pageviews")
    }

    private data class QuotaUsageTarget(
        val type: AdminQuotaUsageType,
        val currentUsed: Long,
        val targetUsed: Long,
        val limit: Long?,
        val resolvedPercent: Double?
    )

    private data class IngestionByteTarget(
        val usedBytes: Long,
        val usedErrorBytes: Long,
        val usedReplayBytes: Long,
        val usedLogBytes: Long,
        val usedLlmBytes: Long,
        val usedProfilerBytes: Long
    )

    private fun resolveQuotaUsageTarget(
        state: QuotaState,
        rawType: String,
        targetPercent: Double?,
        targetValue: Long?
    ): QuotaUsageTarget {
        val type = parseAdminQuotaUsageType(rawType)
        val currentUsed = usedForAdminQuotaTarget(state, type)
        val limit = limitForAdminQuotaTarget(state, type)
        require(targetValue == null || targetPercent == null) {
            "Provide exactly one of targetPercent or targetValue"
        }
        val targetUsed = when {
            targetValue != null -> {
                require(targetValue >= 0) { "targetValue must be non-negative" }
                targetValue
            }
            targetPercent != null -> usageForTargetPercent(limit, targetPercent)
            else -> throw IllegalArgumentException("targetPercent or targetValue is required")
        }
        val resolvedPercent = if (limit != null && isFinitePositiveLimit(limit)) {
            targetUsed.toDouble() / limit.toDouble() * PERCENT_MULTIPLIER
        } else {
            targetPercent
        }
        return QuotaUsageTarget(
            type = type,
            currentUsed = currentUsed,
            targetUsed = targetUsed,
            limit = limit,
            resolvedPercent = resolvedPercent
        )
    }

    private fun usageForTargetPercent(
        limit: Long?,
        targetPercent: Double
    ): Long {
        require(targetPercent in MIN_QUOTA_TARGET_PERCENT..MAX_QUOTA_TARGET_PERCENT) {
            "targetPercent must be between $MIN_QUOTA_TARGET_PERCENT and $MAX_QUOTA_TARGET_PERCENT"
        }
        require(limit != null && isFinitePositiveLimit(limit)) {
            "Selected quota type does not have a finite positive limit"
        }
        return (limit.toDouble() * targetPercent / PERCENT_MULTIPLIER).roundToLong().coerceAtLeast(0)
    }

    private fun parseAdminQuotaUsageType(rawType: String): AdminQuotaUsageType {
        return when (rawType.trim().lowercase().replace("-", "_")) {
            "ingestion", "ingestion_bytes", "ingestion_gb", "ingestion_db", "gb", "gb_limit" ->
                AdminQuotaUsageType.INGESTION_BYTES
            "apm", "apm_span", "apm_spans", "apn_span", "apn_spans", "span", "spans" ->
                AdminQuotaUsageType.APM_SPANS
            "custom_metric", "custom_metrics", "metric", "metrics" -> AdminQuotaUsageType.CUSTOM_METRICS
            "infra", "infra_metric", "infra_metrics", "infrastructure_metric", "infrastructure_metrics" ->
                AdminQuotaUsageType.INFRA_METRICS
            "error", "errors" -> AdminQuotaUsageType.ERRORS
            "transaction", "transactions" -> AdminQuotaUsageType.TRANSACTIONS
            "replay", "replays" -> AdminQuotaUsageType.REPLAYS
            "feedback" -> AdminQuotaUsageType.FEEDBACK
            "llm", "llm_event", "llm_events" -> AdminQuotaUsageType.LLM_EVENTS
            "analytics", "analytics_pageview", "analytics_pageviews", "pageview", "pageviews" ->
                AdminQuotaUsageType.ANALYTICS_PAGEVIEWS
            else -> throw IllegalArgumentException("Unsupported quota type: $rawType")
        }
    }

    private fun usedForAdminQuotaTarget(
        state: QuotaState,
        type: AdminQuotaUsageType
    ): Long {
        return when (type) {
            AdminQuotaUsageType.INGESTION_BYTES -> gbEligibleBytes(state)
            AdminQuotaUsageType.APM_SPANS -> state.usedApmSpans
            AdminQuotaUsageType.CUSTOM_METRICS -> state.usedCustomMetrics
            AdminQuotaUsageType.INFRA_METRICS -> state.usedInfraMetricSeriesHours
            AdminQuotaUsageType.ERRORS -> state.usedErrors
            AdminQuotaUsageType.TRANSACTIONS -> state.usedTransactions
            AdminQuotaUsageType.REPLAYS -> state.usedReplays
            AdminQuotaUsageType.FEEDBACK -> state.usedFeedback
            AdminQuotaUsageType.LLM_EVENTS -> state.usedLlmEvents
            AdminQuotaUsageType.ANALYTICS_PAGEVIEWS -> state.usedAnalyticsPageviews
        }
    }

    private fun limitForAdminQuotaTarget(
        state: QuotaState,
        type: AdminQuotaUsageType
    ): Long? {
        return when (type) {
            AdminQuotaUsageType.INGESTION_BYTES -> state.bytesLimit
            AdminQuotaUsageType.APM_SPANS -> state.apmSpanLimit
            AdminQuotaUsageType.CUSTOM_METRICS -> state.customMetricLimit
            AdminQuotaUsageType.INFRA_METRICS -> state.infraMetricSeriesHourLimit
            AdminQuotaUsageType.ERRORS -> state.errorLimit
            AdminQuotaUsageType.TRANSACTIONS -> state.transactionLimit
            AdminQuotaUsageType.REPLAYS -> state.replayLimit
            AdminQuotaUsageType.FEEDBACK -> state.feedbackLimit
            AdminQuotaUsageType.LLM_EVENTS -> state.llmEventLimit
            AdminQuotaUsageType.ANALYTICS_PAGEVIEWS -> state.analyticsPageviewLimit
        }
    }

    private fun isFinitePositiveLimit(limit: Long): Boolean {
        return limit > 0 && limit < Long.MAX_VALUE
    }

    private fun adjustedAggregateUnits(
        state: QuotaState,
        currentUsed: Long,
        targetUsed: Long
    ): Long {
        return (state.usedUnits - currentUsed + targetUsed).coerceAtLeast(0)
    }

    private fun buildIngestionByteTarget(
        state: QuotaState,
        targetGbEligibleBytes: Long
    ): IngestionByteTarget {
        val currentKnownBytes =
            state.usedErrorBytes + state.usedReplayBytes + state.usedLogBytes +
                state.usedLlmBytes + state.usedProfilerBytes
        val adjustedKnownBytes =
            if (currentKnownBytes > 0 && targetGbEligibleBytes < currentKnownBytes) {
                scaleIngestionByteColumns(state, currentKnownBytes, targetGbEligibleBytes)
            } else {
                IngestionByteTarget(
                    usedBytes = excludedGbBytes(state) + targetGbEligibleBytes,
                    usedErrorBytes = state.usedErrorBytes,
                    usedReplayBytes = state.usedReplayBytes,
                    usedLogBytes = state.usedLogBytes,
                    usedLlmBytes = state.usedLlmBytes,
                    usedProfilerBytes = state.usedProfilerBytes
                )
            }

        return adjustedKnownBytes.copy(usedBytes = excludedGbBytes(state) + targetGbEligibleBytes)
    }

    private fun scaleIngestionByteColumns(
        state: QuotaState,
        currentKnownBytes: Long,
        targetGbEligibleBytes: Long
    ): IngestionByteTarget {
        val errorBytes = scaleBytes(state.usedErrorBytes, currentKnownBytes, targetGbEligibleBytes)
        val replayBytes = scaleBytes(state.usedReplayBytes, currentKnownBytes, targetGbEligibleBytes)
        val logBytes = scaleBytes(state.usedLogBytes, currentKnownBytes, targetGbEligibleBytes)
        val llmBytes = scaleBytes(state.usedLlmBytes, currentKnownBytes, targetGbEligibleBytes)
        val assignedBytes = errorBytes + replayBytes + logBytes + llmBytes
        val profilerBytes = (targetGbEligibleBytes - assignedBytes).coerceAtLeast(0)

        return IngestionByteTarget(
            usedBytes = excludedGbBytes(state) + targetGbEligibleBytes,
            usedErrorBytes = errorBytes,
            usedReplayBytes = replayBytes,
            usedLogBytes = logBytes,
            usedLlmBytes = llmBytes,
            usedProfilerBytes = profilerBytes
        )
    }

    private fun scaleBytes(
        value: Long,
        currentTotal: Long,
        targetTotal: Long
    ): Long {
        return ((value.toDouble() / currentTotal.toDouble()) * targetTotal.toDouble())
            .toLong()
            .coerceAtLeast(0)
    }

    private fun syncPendingOverageForAdminReset(
        state: QuotaState,
        target: QuotaUsageTarget
    ) {
        val subscriptionId = state.subscriptionId ?: return
        when (target.type) {
            AdminQuotaUsageType.INGESTION_BYTES ->
                Subscriptions.update({ Subscriptions.id eq subscriptionId }) {
                    it[pending_overage_bytes] = pendingIngestionOverageBytes(state, target.targetUsed)
                }
            AdminQuotaUsageType.APM_SPANS ->
                Subscriptions.update({ Subscriptions.id eq subscriptionId }) {
                    it[pending_apm_span_overage_units] = pendingCountOverage(
                        target.targetUsed,
                        state.apmSpanLimit,
                        state.apmSpanOverageRateCentsPer1m
                    )
                }
            AdminQuotaUsageType.CUSTOM_METRICS ->
                Subscriptions.update({ Subscriptions.id eq subscriptionId }) {
                    it[pending_custom_metric_overage_units] = pendingCountOverage(
                        target.targetUsed,
                        state.customMetricLimit,
                        state.customMetricOverageRateCentsPer100k
                    )
                }
            AdminQuotaUsageType.INFRA_METRICS ->
                Subscriptions.update({ Subscriptions.id eq subscriptionId }) {
                    it[pending_infra_metric_overage_units] = pendingCountOverage(
                        target.targetUsed,
                        state.infraMetricSeriesHourLimit,
                        state.infraMetricOverageRateCentsPer100kSeriesHours
                    )
                }
            else -> Unit
        }
    }

    private fun pendingIngestionOverageBytes(
        state: QuotaState,
        targetGbEligibleBytes: Long
    ): Long {
        return if (state.overageRateCentsPerGb > 0 && state.bytesLimit > 0) {
            max(0, targetGbEligibleBytes - state.bytesLimit)
        } else {
            0
        }
    }

    private fun pendingCountOverage(
        targetUsed: Long,
        limit: Long,
        overageRate: Int
    ): Long {
        return if (overageRate > 0 && limit >= 0) {
            max(0, targetUsed - limit)
        } else {
            0
        }
    }

    private fun apmSpanDebugWhereClause(
        organizationId: Int,
        periodStart: LocalDate,
        periodEnd: LocalDate
    ): String {
        val endExclusive = java.time.LocalDate.parse(periodEnd.toString()).plusDays(1).toString()
        return listOf(
            ClickHouseQueryUtils.orgIdClause(organizationId.toLong()),
            "start >= toDateTime64('${escapeSql(periodStart.toString())} 00:00:00', 9, 'UTC')",
            "start < toDateTime64('${escapeSql(endExclusive)} 00:00:00', 9, 'UTC')"
        ).joinToString(" AND\n    ")
    }

    private suspend fun queryApmSpanDebugTotal(whereClause: String): Long {
        val query = """
            SELECT count()
            FROM `${ClickHouseClient.getDatabase()}`.apm_spans
            WHERE $whereClause
        """.trimIndent()
        return ClickHouseClient.executeWithFormat(query, "TabSeparated").trim().toLongOrNull() ?: 0L
    }

    private suspend fun queryApmSpanDebugGroups(
        whereClause: String,
        limit: Int
    ): List<RawApmSpanDebugGroup> {
        val query = """
            SELECT
                if(source = '', 'datadog', source) AS source_value,
                service,
                name AS operation,
                resource,
                type AS span_type,
                env,
                kind,
                scope_name,
                scope_version,
                toUInt64OrNull(meta['sentry.project_id']) AS project_id,
                count() AS span_count,
                uniqExact(
                    if(trace_id_hex != '', trace_id_hex, concat(toString(trace_id_high), ':', toString(trace_id)))
                ) AS trace_count,
                countIf(error != 0) AS error_count,
                round(avg(duration) / $NANOS_PER_MILLISECOND, $DURATION_DECIMAL_PLACES) AS avg_duration_ms,
                round(max(duration) / $NANOS_PER_MILLISECOND, $DURATION_DECIMAL_PLACES) AS max_duration_ms,
                argMax(if(trace_id_hex != '', trace_id_hex, toString(trace_id)), start) AS sample_trace_id,
                toString(max(start)) AS latest_span_at
            FROM `${ClickHouseClient.getDatabase()}`.apm_spans
            WHERE $whereClause
            GROUP BY
                source_value,
                service,
                operation,
                resource,
                span_type,
                env,
                kind,
                scope_name,
                scope_version,
                project_id
            ORDER BY span_count DESC
            LIMIT $limit
            FORMAT JSONEachRow
        """.trimIndent()
        val body = ClickHouseClient.executeWithFormat(query, "")
        return parseApmSpanDebugGroups(body)
    }

    private fun parseApmSpanDebugGroups(body: String): List<RawApmSpanDebugGroup> {
        if (body.isBlank()) return emptyList()
        return body.trim().lines()
            .filter { it.isNotBlank() }
            .mapNotNull { line ->
                suspendRunCatching {
                    val obj = billingQuotaJson.parseToJsonElement(line).jsonObject
                    RawApmSpanDebugGroup(
                        source = obj["source_value"]?.jsonPrimitive?.contentOrNull ?: "datadog",
                        service = obj["service"]?.jsonPrimitive?.contentOrNull ?: "",
                        operation = obj["operation"]?.jsonPrimitive?.contentOrNull ?: "",
                        resource = obj["resource"]?.jsonPrimitive?.contentOrNull ?: "",
                        spanType = obj["span_type"]?.jsonPrimitive?.contentOrNull ?: "",
                        env = obj["env"]?.jsonPrimitive?.contentOrNull ?: "",
                        kind = obj["kind"]?.jsonPrimitive?.contentOrNull ?: "",
                        scopeName = obj["scope_name"]?.jsonPrimitive?.contentOrNull ?: "",
                        scopeVersion = obj["scope_version"]?.jsonPrimitive?.contentOrNull ?: "",
                        projectId = obj["project_id"]?.jsonPrimitive?.longOrNull,
                        spanCount = obj["span_count"]?.jsonPrimitive?.longOrNull ?: 0L,
                        traceCount = obj["trace_count"]?.jsonPrimitive?.longOrNull ?: 0L,
                        errorCount = obj["error_count"]?.jsonPrimitive?.longOrNull ?: 0L,
                        avgDurationMs = obj["avg_duration_ms"]?.jsonPrimitive?.doubleOrNull ?: 0.0,
                        maxDurationMs = obj["max_duration_ms"]?.jsonPrimitive?.doubleOrNull ?: 0.0,
                        sampleTraceId = obj["sample_trace_id"]?.jsonPrimitive?.contentOrNull ?: "",
                        latestSpanAt = obj["latest_span_at"]?.jsonPrimitive?.contentOrNull ?: ""
                    )
                }.getOrElse { e ->
                    logger.warn(e) { "Failed to parse APM span debug row" }
                    null
                }
            }
    }

    private fun loadApmSpanProjectLabels(
        organizationId: Int,
        projectIds: Set<Long>
    ): Map<Long, ApmSpanProjectLabel> {
        if (projectIds.isEmpty()) return emptyMap()
        return transaction {
            Projects
                .selectAll()
                .where {
                    (Projects.organization_id eq organizationId) and
                        (Projects.id inList projectIds.toList())
                }
                .associate { row ->
                    row[Projects.id] to ApmSpanProjectLabel(
                        resourceId = row[Projects.resource_id].toString(),
                        name = row[Projects.name],
                        slug = row[Projects.slug]
                    )
                }
        }
    }

    private fun loadQuotaState(
        organizationId: Int,
        lockRows: Boolean
    ): QuotaState {
        val now = Clock.System.todayIn(TimeZone.UTC)
        val sub =
            Subscriptions
                .selectAll()
                .where {
                    (Subscriptions.organization_id eq organizationId) and
                        (Subscriptions.status inList listOf("active", "trialing", "past_due"))
                }.orderBy(Subscriptions.id to SortOrder.DESC)
                .firstOrNull()

        if (lockRows && sub != null) {
            val subId = sub[Subscriptions.id]
            TransactionManager.current().exec(
                "SELECT id FROM subscriptions WHERE id = ? FOR UPDATE",
                listOf(Subscriptions.id.columnType to subId)
            )
        }

        val tier =
            run {
                val byId =
                    sub?.get(Subscriptions.pricing_tier_config_id)?.let { tierId ->
                        PricingTierConfigs.selectAll().where { PricingTierConfigs.id eq tierId }.firstOrNull()
                    }

                val byPlan =
                    if (byId == null && sub != null) {
                        PricingTierConfigs
                            .selectAll()
                            .where {
                                (PricingTierConfigs.tier_name eq sub[Subscriptions.plan].uppercase()) and
                                    (PricingTierConfigs.is_current eq true)
                            }.firstOrNull()
                    } else {
                        null
                    }

                val free =
                    if (byId == null && byPlan == null) {
                        PricingTierConfigs
                            .selectAll()
                            .where {
                                (PricingTierConfigs.tier_name eq "FREE") and
                                    (PricingTierConfigs.is_current eq true)
                            }.firstOrNull()
                    } else {
                        null
                    }

                when {
                    byId != null -> quotaTierFromRow(byId)
                    byPlan != null -> quotaTierFromRow(byPlan)
                    free != null -> quotaTierFromRow(free)
                    else -> quotaTierFromEnum(sub?.get(Subscriptions.plan) ?: "FREE")
                }
            }
        val billingPeriod =
            resolveCurrentBillingPeriod(
                storedStart = sub?.get(Subscriptions.current_period_start)?.toLocalDateTime(TimeZone.UTC)?.date,
                storedEnd = sub?.get(Subscriptions.current_period_end)?.toLocalDateTime(TimeZone.UTC)?.date,
                billingInterval = sub?.get(Subscriptions.billing_interval),
                today = now,
            )
        val periodStart = billingPeriod.start
        val periodEnd = billingPeriod.end

        val existingCounter =
            OrgUsageCounters
                .selectAll()
                .where {
                    (OrgUsageCounters.organization_id eq organizationId) and
                        (OrgUsageCounters.period_start eq periodStart)
                }.firstOrNull()

        if (existingCounter == null) {
            OrgUsageCounters.insert {
                it[OrgUsageCounters.organization_id] = organizationId
                it[OrgUsageCounters.period_start] = periodStart
                it[OrgUsageCounters.period_end] = periodEnd
                it[used_units] = 0
                it[used_errors] = 0
                it[used_transactions] = 0
                it[used_replays] = 0
                it[used_feedback] = 0
                it[used_llm_events] = 0
                it[updated_at] = Clock.System.now()
            }
        }

        if (lockRows) {
            TransactionManager.current().exec(
                "SELECT id FROM org_usage_counters WHERE organization_id = ? AND period_start = ? FOR UPDATE",
                listOf(
                    OrgUsageCounters.organization_id.columnType to organizationId,
                    OrgUsageCounters.period_start.columnType to periodStart
                )
            )
        }

        val usageRow =
            OrgUsageCounters
                .selectAll()
                .where {
                    (OrgUsageCounters.organization_id eq organizationId) and
                        (OrgUsageCounters.period_start eq periodStart)
                }.first()

        val usedUnits = usageRow[OrgUsageCounters.used_units]
        val usedErrors = usageRow[OrgUsageCounters.used_errors]
        val usedTransactions = usageRow[OrgUsageCounters.used_transactions]
        val usedReplays = usageRow[OrgUsageCounters.used_replays]
        val usedFeedback = usageRow[OrgUsageCounters.used_feedback]
        val usedLlmEvents = usageRow[OrgUsageCounters.used_llm_events]
        val usedLogs = usageRow[OrgUsageCounters.used_logs]
        val usedBytes = usageRow[OrgUsageCounters.used_bytes]
        val usedApmSpanBytes = usageRow[OrgUsageCounters.used_apm_span_bytes]
        val usedInfraMetricBytes = usageRow[OrgUsageCounters.used_infra_metric_bytes]
        val usedErrorBytes = usageRow[OrgUsageCounters.used_error_bytes]
        val usedReplayBytes = usageRow[OrgUsageCounters.used_replay_bytes]
        val usedLogBytes = usageRow[OrgUsageCounters.used_log_bytes]
        val usedLlmBytes = usageRow[OrgUsageCounters.used_llm_bytes]
        val usedProfilerBytes = usageRow[OrgUsageCounters.used_profiler_bytes]
        val usedAnalyticsPageviews = usageRow[OrgUsageCounters.used_analytics_pageviews]
        val usedApmSpans = usageRow[OrgUsageCounters.used_apm_spans]
        val usedCustomMetrics = usageRow[OrgUsageCounters.used_custom_metrics]
        val usedInfraMetricSeriesHours = usageRow[OrgUsageCounters.used_infra_metric_series_hours]
        val errorLimit = tier.monthlyErrorLimit
        val llmEventLimit = tier.monthlyLlmEventLimit
        val transactionLimit = tier.monthlyTransactionLimit
        val replayLimit = tier.monthlyReplayLimit
        val feedbackLimit = tier.monthlyFeedbackLimit
        val bytesLimit = tier.monthlyGbLimit
        val aggregateBaseFromTypes =
            listOf(errorLimit, transactionLimit, replayLimit, feedbackLimit)
                .filter { it >= 0 }
                .sum()
        val paygBudgetCents = sub?.get(Subscriptions.payg_budget_cents) ?: 0
        val paygRateMicros = tier.paygRateMicrosPerUnit
        val overageRateCentsPerGb = tier.overageRateCentsPerGb
        val paygEnabled = tier.paygEnabled
        val paygLimitUnits =
            if (paygEnabled && paygBudgetCents > 0 && paygRateMicros > 0) {
                (paygBudgetCents.toLong() * MICROS_PER_CENT) / paygRateMicros
            } else {
                0
            }
        val paygLimitBytes =
            if (paygEnabled && paygBudgetCents > 0 && overageRateCentsPerGb > 0) {
                (paygBudgetCents.toLong() * BYTES_PER_GB) / overageRateCentsPerGb.toLong()
            } else {
                0
            }
        val baseLimit = if (tier.monthlyUnitLimit > 0) tier.monthlyUnitLimit else aggregateBaseFromTypes
        val totalLimit = baseLimit + paygLimitUnits

        val bonusGbBytes = sub?.get(Subscriptions.bonus_gb_bytes) ?: 0L
        val bonusUnits = sub?.get(Subscriptions.bonus_units) ?: 0L
        val bonusReason = sub?.get(Subscriptions.bonus_reason)
        val pendingAnalyticsPageviewOverageUnits =
            sub?.get(Subscriptions.pending_analytics_pageview_overage_units) ?: 0

        return QuotaState(
            organizationId = organizationId,
            plan = tier.tierName.lowercase(),
            status = sub?.get(Subscriptions.status) ?: "active",
            retentionDays = tier.retentionDays,
            logRetentionDays = tier.logRetentionDays,
            replayRetentionDays = tier.replayRetentionDays,
            llmRetentionDays = tier.llmRetentionDays,
            apmTraceRetentionDays = tier.apmTraceRetentionDays,
            subscriptionId = sub?.get(Subscriptions.id),
            periodStart = periodStart,
            periodEnd = periodEnd,
            usedUnits = usedUnits,
            usedErrors = usedErrors,
            usedTransactions = usedTransactions,
            usedReplays = usedReplays,
            usedFeedback = usedFeedback,
            usedLlmEvents = usedLlmEvents,
            usedLogs = usedLogs,
            usedBytes = usedBytes,
            usedApmSpanBytes = usedApmSpanBytes,
            usedInfraMetricBytes = usedInfraMetricBytes,
            usedErrorBytes = usedErrorBytes,
            usedReplayBytes = usedReplayBytes,
            usedLogBytes = usedLogBytes,
            usedLlmBytes = usedLlmBytes,
            usedProfilerBytes = usedProfilerBytes,
            errorLimit = errorLimit,
            llmEventLimit = llmEventLimit,
            transactionLimit = transactionLimit,
            replayLimit = replayLimit,
            feedbackLimit = feedbackLimit,
            bytesLimit = bytesLimit,
            baseLimitUnits = baseLimit,
            paygLimitUnits = paygLimitUnits,
            totalLimitUnits = totalLimit,
            paygBudgetCents = paygBudgetCents,
            paygUsedUnits = sub?.get(Subscriptions.payg_used_units) ?: 0,
            paygUsedMicros = sub?.get(Subscriptions.payg_used_micros) ?: 0,
            pendingMeterUnits = sub?.get(Subscriptions.pending_meter_units) ?: 0,
            pendingOverageBytes = sub?.get(Subscriptions.pending_overage_bytes) ?: 0,
            paygRateMicrosPerUnit = paygRateMicros,
            paygLimitBytes = paygLimitBytes,
            oncallSeats = sub?.get(Subscriptions.oncall_seats) ?: 0,
            oncallUsedSeats = getOnCallUsedSeatsIfAvailable(organizationId),
            oncallPerUserMonthlyCents = tier.oncallPerUserMonthlyCents,
            oncallEnabled = tier.oncallEnabled,
            teamsEnabled = tier.teamsEnabled,
            bonusGbBytes = bonusGbBytes,
            bonusUnits = bonusUnits,
            bonusReason = bonusReason,
            errorOverageRateCentsPer1k = tier.errorOverageRateCentsPer1k,
            replayOverageRateCentsPerGb = tier.replayOverageRateCentsPerGb,
            logOverageRateCentsPerGb = tier.overageRateCentsPerGb,
            llmOverageRateCentsPer1k = tier.llmOverageRateCentsPer1k,
            overageRateCentsPerGb = tier.overageRateCentsPerGb,
            usedAnalyticsPageviews = usedAnalyticsPageviews,
            analyticsPageviewLimit = tier.monthlyAnalyticsPageviewLimit,
            analyticsPageviewOverageRateCentsPer100k = tier.analyticsPageviewOverageRateCentsPer100k,
            usedApmSpans = usedApmSpans,
            apmSpanLimit = tier.monthlyApmSpanLimit,
            apmSpanOverageRateCentsPer1m = tier.apmSpanOverageRateCentsPer1m,
            usedCustomMetrics = usedCustomMetrics,
            customMetricLimit = tier.monthlyCustomMetricLimit,
            customMetricOverageRateCentsPer100k = tier.customMetricOverageRateCentsPer100k,
            usedInfraMetricSeriesHours = usedInfraMetricSeriesHours,
            infraMetricSeriesHourLimit = tier.monthlyInfraMetricSeriesHourLimit,
            infraMetricOverageRateCentsPer100kSeriesHours =
            tier.infraMetricOverageRateCentsPer100kSeriesHours,
            pendingApmSpanOverageUnits = sub?.get(Subscriptions.pending_apm_span_overage_units) ?: 0,
            pendingCustomMetricOverageUnits = sub?.get(Subscriptions.pending_custom_metric_overage_units) ?: 0,
            pendingInfraMetricOverageUnits = sub?.get(Subscriptions.pending_infra_metric_overage_units) ?: 0,
            pendingAnalyticsPageviewOverageUnits = pendingAnalyticsPageviewOverageUnits,
        )
    }

    private fun toUsageResponse(state: QuotaState): BillingUsageResponse {
        val gbEligibleBytes = gbEligibleBytes(state)
        val withinQuota = isWithinQuota(state, gbEligibleBytes)
        val ingestionOverageCents = byteOverageCents(
            bytes = limitedOverage(state.bytesLimit, gbEligibleBytes),
            rateCentsPerGb = state.overageRateCentsPerGb
        )

        // Legacy per-type overage estimates (kept for backward compat)
        val errorOverageCents = unitOverageCents(
            units = max(0, state.usedErrors - state.errorLimit),
            rateCents = state.errorOverageRateCentsPer1k,
            divisor = UNITS_PER_THOUSAND
        )
        val replayOverageCents = replayOverageCents(state)
        val logOverageCents = byteOverageCents(
            bytes = logOverageBytes(state, gbEligibleBytes),
            rateCentsPerGb = state.logOverageRateCentsPerGb
        )
        val llmOverageCents = unitOverageCents(
            units = max(0, state.usedLlmEvents - state.llmEventLimit),
            rateCents = state.llmOverageRateCentsPer1k,
            divisor = UNITS_PER_THOUSAND
        )
        val analyticsPageviewOverageCents = unitOverageCents(
            units = limitedOverage(state.analyticsPageviewLimit, state.usedAnalyticsPageviews),
            rateCents = state.analyticsPageviewOverageRateCentsPer100k,
            divisor = UNITS_PER_HUNDRED_THOUSAND
        )
        val apmSpanOverageCents = unitOverageCents(
            units = limitedOverage(state.apmSpanLimit, state.usedApmSpans),
            rateCents = state.apmSpanOverageRateCentsPer1m,
            divisor = UNITS_PER_MILLION
        )
        val customMetricOverageCents = unitOverageCents(
            units = limitedOverage(state.customMetricLimit, state.usedCustomMetrics),
            rateCents = state.customMetricOverageRateCentsPer100k,
            divisor = UNITS_PER_HUNDRED_THOUSAND
        )
        val infraMetricOverageCents = unitOverageCents(
            units = limitedOverage(state.infraMetricSeriesHourLimit, state.usedInfraMetricSeriesHours),
            rateCents = state.infraMetricOverageRateCentsPer100kSeriesHours,
            divisor = UNITS_PER_HUNDRED_THOUSAND
        )

        val totalOverageCents = ingestionOverageCents +
            analyticsPageviewOverageCents + customMetricOverageCents + apmSpanOverageCents +
            infraMetricOverageCents

        return BillingUsageResponse(
            organizationId = organizationResourceId(state.organizationId),
            periodStart = state.periodStart.toString(),
            periodEnd = state.periodEnd.toString(),
            retentionDays = state.retentionDays,
            logRetentionDays = state.logRetentionDays,
            replayRetentionDays = state.replayRetentionDays,
            llmRetentionDays = state.llmRetentionDays,
            apmTraceRetentionDays = state.apmTraceRetentionDays,
            usedUnits = state.usedUnits,
            usedErrors = state.usedErrors,
            errorLimit = state.errorLimit,
            usedTransactions = state.usedTransactions,
            transactionLimit = state.transactionLimit,
            usedReplays = state.usedReplays,
            replayLimit = state.replayLimit,
            usedFeedback = state.usedFeedback,
            feedbackLimit = state.feedbackLimit,
            usedLlmEvents = state.usedLlmEvents,
            llmEventLimit = state.llmEventLimit,
            usedLogs = state.usedLogs,
            usedBytes = state.usedBytes,
            usedErrorBytes = state.usedErrorBytes,
            usedReplayBytes = state.usedReplayBytes,
            usedLogBytes = state.usedLogBytes,
            usedLlmBytes = state.usedLlmBytes,
            usedProfilerBytes = state.usedProfilerBytes,
            usedInfraMetricBytes = state.usedInfraMetricBytes,
            bytesLimit = state.bytesLimit,
            ingestionOverageCentsEstimate = ingestionOverageCents,
            ingestionOverageRateCentsPerGb = state.overageRateCentsPerGb,
            baseLimitUnits = state.baseLimitUnits,
            paygLimitUnits = state.paygLimitUnits,
            paygLimitBytes = state.paygLimitBytes,
            totalLimitUnits = state.totalLimitUnits,
            paygBudgetCents = state.paygBudgetCents,
            paygUsedUnits = state.paygUsedUnits,
            paygUsedCentsEstimate = (state.paygUsedMicros / 10_000L).toInt(),
            errorOverageCentsEstimate = errorOverageCents,
            replayOverageCentsEstimate = replayOverageCents,
            logOverageCentsEstimate = logOverageCents,
            llmOverageCentsEstimate = llmOverageCents,
            apmSpanOverageCentsEstimate = apmSpanOverageCents,
            customMetricOverageCentsEstimate = customMetricOverageCents,
            infraMetricOverageCentsEstimate = infraMetricOverageCents,
            totalOverageCentsEstimate = totalOverageCents,
            errorOverageRateCentsPer1k = state.errorOverageRateCentsPer1k,
            replayOverageRateCentsPerGb = state.replayOverageRateCentsPerGb,
            logOverageRateCentsPerGb = state.logOverageRateCentsPerGb,
            llmOverageRateCentsPer1k = state.llmOverageRateCentsPer1k,
            apmSpanOverageRateCentsPer1m = state.apmSpanOverageRateCentsPer1m,
            customMetricOverageRateCentsPer100k = state.customMetricOverageRateCentsPer100k,
            infraMetricOverageRateCentsPer100kSeriesHours =
            state.infraMetricOverageRateCentsPer100kSeriesHours,
            oncallSeats = state.oncallSeats,
            oncallUsedSeats = state.oncallUsedSeats,
            oncallPerUserMonthlyCents = state.oncallPerUserMonthlyCents,
            oncallEnabled = state.oncallEnabled,
            teamsEnabled = state.teamsEnabled,
            usedAnalyticsPageviews = state.usedAnalyticsPageviews,
            analyticsPageviewLimit = state.analyticsPageviewLimit,
            analyticsPageviewOverageCentsEstimate = analyticsPageviewOverageCents,
            analyticsPageviewOverageRateCentsPer100k =
            state.analyticsPageviewOverageRateCentsPer100k,
            usedApmSpans = state.usedApmSpans,
            usedApmSpanBytes = state.usedApmSpanBytes,
            apmSpanLimit = state.apmSpanLimit,
            usedCustomMetrics = state.usedCustomMetrics,
            customMetricLimit = state.customMetricLimit,
            usedInfraMetricSeriesHours = state.usedInfraMetricSeriesHours,
            infraMetricSeriesHourLimit = state.infraMetricSeriesHourLimit,
            plan = state.plan,
            status = state.status,
            withinQuota = withinQuota,
            bonusGbBytes = state.bonusGbBytes,
            bonusUnits = state.bonusUnits,
            bonusReason = state.bonusReason
        )
    }

    private fun exceededCountQuotaResult(
        organizationId: Int,
        state: QuotaState,
        requestedUnitsByType: Map<String, Long>
    ): QuotaReservationResult? {
        for ((eventType, requestedUnits) in requestedUnitsByType) {
            if (eventType !in COUNT_GATED_UNIT_TYPES) continue
            val usedForType = usedUnitsForType(state, eventType)
            val typeLimit = baseLimitForType(state, eventType)
            val effectiveTypeLimit = effectiveCountLimit(state, eventType, typeLimit)

            if (typeLimit >= 0 && usedForType + requestedUnits > effectiveTypeLimit) {
                SentryUtils.breadcrumb(
                    "billing",
                    "Per-type quota exceeded",
                    mapOf(
                        "organization_id" to organizationId,
                        "requested_units" to requestedUnits,
                        "event_type" to eventType,
                        "used_type_units" to usedForType,
                        "type_limit" to typeLimit,
                        "payg_limit_units" to state.paygLimitUnits,
                        "bonus_units" to state.bonusUnits
                    )
                )
                return QuotaReservationResult(
                    allowed = false,
                    reason = "event_type_quota_exceeded",
                    eventType = eventType,
                    usage = toUsageResponse(state)
                )
            }
        }
        return null
    }

    private fun effectiveCountLimit(
        state: QuotaState,
        eventType: String,
        typeLimit: Long
    ): Long {
        return when {
            typeLimit < 0 -> Long.MAX_VALUE
            hasOwnOverageBilling(state, eventType) -> Long.MAX_VALUE
            else -> typeLimit + state.bonusUnits
        }
    }

    private fun hasOwnOverageBilling(state: QuotaState, eventType: String): Boolean {
        return when (eventType) {
            "custom_metric" -> state.customMetricOverageRateCentsPer100k > 0
            "apm_span" -> state.apmSpanOverageRateCentsPer1m > 0
            INFRA_METRIC_TYPE -> state.infraMetricOverageRateCentsPer100kSeriesHours > 0
            "analytics_pageview" -> state.analyticsPageviewOverageRateCentsPer100k > 0
            else -> false
        }
    }

    private fun reservationAmounts(
        units: Map<String, Long>,
        bytes: Map<String, Long>
    ): ReservationAmounts {
        return ReservationAmounts(
            errors = units["error"] ?: 0L,
            transactions = units["transaction"] ?: 0L,
            replays = units["replay"] ?: 0L,
            feedback = units["feedback"] ?: 0L,
            llm = units["llm"] ?: 0L,
            logs = units["log"] ?: 0L,
            apmSpans = units["apm_span"] ?: 0L,
            customMetrics = units["custom_metric"] ?: 0L,
            infraMetricSeriesHours = units[INFRA_METRIC_TYPE] ?: 0L,
            analyticsPageviews = units["analytics_pageview"] ?: 0L,
            errorBytes = bytes["error"] ?: 0L,
            replayBytes = bytes["replay"] ?: 0L,
            logBytes = bytes["log"] ?: 0L,
            llmBytes = bytes["llm"] ?: 0L,
            apmSpanBytes = bytes["apm_span"] ?: 0L,
            infraMetricBytes = bytes[INFRA_METRIC_TYPE] ?: 0L,
            profilerBytes = bytes["profile"] ?: 0L
        )
    }

    private fun updateReservedUsageCounters(
        organizationId: Int,
        state: QuotaState,
        requested: ReservationAmounts,
        totalAfter: Long,
        usedBytesAfter: Long
    ) {
        OrgUsageCounters.update({
            (OrgUsageCounters.organization_id eq organizationId) and
                (OrgUsageCounters.period_start eq state.periodStart)
        }) {
            it[used_units] = totalAfter
            it[used_errors] = state.usedErrors + requested.errors
            it[used_transactions] = state.usedTransactions + requested.transactions
            it[used_replays] = state.usedReplays + requested.replays
            it[used_feedback] = state.usedFeedback + requested.feedback
            it[used_llm_events] = state.usedLlmEvents + requested.llm
            it[used_logs] = state.usedLogs + requested.logs
            it[used_apm_spans] = state.usedApmSpans + requested.apmSpans
            it[used_custom_metrics] = state.usedCustomMetrics + requested.customMetrics
            it[used_infra_metric_series_hours] =
                state.usedInfraMetricSeriesHours + requested.infraMetricSeriesHours
            it[used_analytics_pageviews] = state.usedAnalyticsPageviews + requested.analyticsPageviews
            it[used_bytes] = usedBytesAfter
            it[used_apm_span_bytes] = state.usedApmSpanBytes + requested.apmSpanBytes
            it[used_infra_metric_bytes] = state.usedInfraMetricBytes + requested.infraMetricBytes
            it[used_error_bytes] = state.usedErrorBytes + requested.errorBytes
            it[used_replay_bytes] = state.usedReplayBytes + requested.replayBytes
            it[used_log_bytes] = state.usedLogBytes + requested.logBytes
            it[used_llm_bytes] = state.usedLlmBytes + requested.llmBytes
            it[used_profiler_bytes] = state.usedProfilerBytes + requested.profilerBytes
            it[updated_at] = Clock.System.now()
        }
    }

    private fun trackReservedOverages(
        organizationId: Int,
        state: QuotaState,
        requested: ReservationAmounts,
        gbEligibleBytes: Long,
        gbEligibleBytesAfter: Long
    ) {
        if (state.subscriptionId == null) return
        trackReservedIngestionOverage(organizationId, state, gbEligibleBytes, gbEligibleBytesAfter)
        trackReservedCustomMetricOverage(organizationId, state, requested.customMetrics)
        trackReservedApmSpanOverage(organizationId, state, requested.apmSpans)
        trackReservedInfraMetricOverage(organizationId, state, requested.infraMetricSeriesHours)
        trackReservedAnalyticsPageviewOverage(state, requested.analyticsPageviews)
    }

    private fun trackReservedIngestionOverage(
        organizationId: Int,
        state: QuotaState,
        gbEligibleBytes: Long,
        gbEligibleBytesAfter: Long
    ) {
        val overageDelta = positiveLimitOverage(state.bytesLimit, gbEligibleBytesAfter) -
            positiveLimitOverage(state.bytesLimit, gbEligibleBytes)
        if (overageDelta <= 0 || state.overageRateCentsPerGb <= 0) return
        val subscriptionId = state.subscriptionId ?: return

        SentryUtils.breadcrumb(
            "billing",
            "Ingestion overage incurred",
            mapOf(
                "organization_id" to organizationId,
                "overage_byte_delta" to overageDelta,
                "subscription_id" to subscriptionId
            )
        )

        // Accumulate raw byte overage for precision; conversion happens at flush time.
        Subscriptions.update({ Subscriptions.id eq subscriptionId }) {
            it[pending_overage_bytes] = state.pendingOverageBytes + overageDelta
        }
    }

    private fun trackReservedCustomMetricOverage(
        organizationId: Int,
        state: QuotaState,
        requestedUnits: Long
    ) {
        val overageDelta = overageDelta(state.usedCustomMetrics, requestedUnits, state.customMetricLimit)
        if (overageDelta <= 0 || state.customMetricOverageRateCentsPer100k <= 0) return
        val subscriptionId = state.subscriptionId ?: return

        SentryUtils.breadcrumb(
            "billing",
            "Custom metric overage incurred",
            mapOf(
                "organization_id" to organizationId,
                "custom_metric_overage_delta" to overageDelta,
                "subscription_id" to subscriptionId
            )
        )
        Subscriptions.update({ Subscriptions.id eq subscriptionId }) {
            it[pending_custom_metric_overage_units] = state.pendingCustomMetricOverageUnits + overageDelta
        }
    }

    private fun trackReservedInfraMetricOverage(
        organizationId: Int,
        state: QuotaState,
        requestedUnits: Long
    ) {
        val overageDelta = overageDelta(
            state.usedInfraMetricSeriesHours,
            requestedUnits,
            state.infraMetricSeriesHourLimit
        )
        if (overageDelta <= 0 || state.infraMetricOverageRateCentsPer100kSeriesHours <= 0) return
        val subscriptionId = state.subscriptionId ?: return

        SentryUtils.breadcrumb(
            "billing",
            "Infrastructure metric overage incurred",
            mapOf(
                "organization_id" to organizationId,
                "infra_metric_overage_delta" to overageDelta,
                "subscription_id" to subscriptionId
            )
        )
        Subscriptions.update({ Subscriptions.id eq subscriptionId }) {
            it[pending_infra_metric_overage_units] = state.pendingInfraMetricOverageUnits + overageDelta
        }
    }

    private fun trackReservedApmSpanOverage(
        organizationId: Int,
        state: QuotaState,
        requestedUnits: Long
    ) {
        val overageDelta = overageDelta(state.usedApmSpans, requestedUnits, state.apmSpanLimit)
        if (overageDelta <= 0 || state.apmSpanOverageRateCentsPer1m <= 0) return
        val subscriptionId = state.subscriptionId ?: return

        SentryUtils.breadcrumb(
            "billing",
            "APM span overage incurred",
            mapOf(
                "organization_id" to organizationId,
                "apm_span_overage_delta" to overageDelta,
                "subscription_id" to subscriptionId
            )
        )
        Subscriptions.update({ Subscriptions.id eq subscriptionId }) {
            it[pending_apm_span_overage_units] = state.pendingApmSpanOverageUnits + overageDelta
        }
    }

    private fun trackReservedAnalyticsPageviewOverage(
        state: QuotaState,
        requestedUnits: Long
    ) {
        val overageDelta = overageDelta(state.usedAnalyticsPageviews, requestedUnits, state.analyticsPageviewLimit)
        if (overageDelta <= 0 || state.analyticsPageviewOverageRateCentsPer100k <= 0) return
        val subscriptionId = state.subscriptionId ?: return

        Subscriptions.update({ Subscriptions.id eq subscriptionId }) {
            it[pending_analytics_pageview_overage_units] =
                state.pendingAnalyticsPageviewOverageUnits + overageDelta
        }
    }

    private fun overageDelta(
        usedBefore: Long,
        requestedUnits: Long,
        limit: Long
    ): Long {
        return (limitedOverage(limit, usedBefore + requestedUnits) - limitedOverage(limit, usedBefore))
            .coerceAtLeast(0)
    }

    private fun refundedUsage(
        state: QuotaState,
        normalizedType: String,
        requestedUnits: Long,
        requestedBytes: Long
    ): RefundedUsage {
        val aggregateUnitsToRefund = if (normalizedType in NON_AGGREGATE_UNIT_TYPES) 0 else requestedUnits
        return RefundedUsage(
            usedUnits = (state.usedUnits - aggregateUnitsToRefund).coerceAtLeast(0),
            usedErrors = refundMatchingUsage(state.usedErrors, normalizedType, "error", requestedUnits),
            usedTransactions = refundMatchingUsage(
                state.usedTransactions,
                normalizedType,
                "transaction",
                requestedUnits
            ),
            usedReplays = refundMatchingUsage(state.usedReplays, normalizedType, "replay", requestedUnits),
            usedFeedback = refundMatchingUsage(state.usedFeedback, normalizedType, "feedback", requestedUnits),
            usedLlmEvents = refundMatchingUsage(state.usedLlmEvents, normalizedType, "llm", requestedUnits),
            usedLogs = refundMatchingUsage(state.usedLogs, normalizedType, "log", requestedUnits),
            usedApmSpans = refundMatchingUsage(state.usedApmSpans, normalizedType, "apm_span", requestedUnits),
            usedCustomMetrics = refundMatchingUsage(
                state.usedCustomMetrics,
                normalizedType,
                "custom_metric",
                requestedUnits
            ),
            usedInfraMetricSeriesHours = refundMatchingUsage(
                state.usedInfraMetricSeriesHours,
                normalizedType,
                INFRA_METRIC_TYPE,
                requestedUnits
            ),
            usedAnalyticsPageviews = refundMatchingUsage(
                state.usedAnalyticsPageviews,
                normalizedType,
                "analytics_pageview",
                requestedUnits
            ),
            usedBytes = (state.usedBytes - requestedBytes).coerceAtLeast(0),
            usedApmSpanBytes = refundMatchingUsage(
                state.usedApmSpanBytes,
                normalizedType,
                "apm_span",
                requestedBytes
            ),
            usedInfraMetricBytes = refundMatchingUsage(
                state.usedInfraMetricBytes,
                normalizedType,
                INFRA_METRIC_TYPE,
                requestedBytes
            ),
            usedErrorBytes = refundMatchingUsage(state.usedErrorBytes, normalizedType, "error", requestedBytes),
            usedReplayBytes = refundMatchingUsage(state.usedReplayBytes, normalizedType, "replay", requestedBytes),
            usedLogBytes = refundMatchingUsage(state.usedLogBytes, normalizedType, "log", requestedBytes),
            usedLlmBytes = refundMatchingUsage(state.usedLlmBytes, normalizedType, "llm", requestedBytes),
            usedProfilerBytes = refundMatchingUsage(state.usedProfilerBytes, normalizedType, "profile", requestedBytes)
        )
    }

    private fun refundMatchingUsage(
        currentUsage: Long,
        normalizedType: String,
        targetType: String,
        amount: Long
    ): Long {
        return if (normalizedType == targetType) (currentUsage - amount).coerceAtLeast(0) else currentUsage
    }

    private fun refundPaygOverage(
        state: QuotaState,
        totalBefore: Long,
        totalAfter: Long
    ) {
        val overageRefundDelta = (
            limitedOverage(state.baseLimitUnits, totalBefore) -
                limitedOverage(state.baseLimitUnits, totalAfter)
            ).coerceAtLeast(0)
        if (overageRefundDelta <= 0 || state.paygRateMicrosPerUnit <= 0) return
        val subscriptionId = state.subscriptionId ?: return

        val overageMicrosRefund = overageRefundDelta * state.paygRateMicrosPerUnit
        Subscriptions.update({ Subscriptions.id eq subscriptionId }) {
            it[payg_used_units] = (state.paygUsedUnits - overageRefundDelta).coerceAtLeast(0)
            it[payg_used_micros] = (state.paygUsedMicros - overageMicrosRefund).coerceAtLeast(0)
        }
    }

    private fun refundByteOverage(
        state: QuotaState,
        requestedBytes: Long,
        refunded: RefundedUsage
    ) {
        if (requestedBytes <= 0 || state.bytesLimit <= 0) return

        val gbEligibleBytesBefore = gbEligibleBytes(state)
        val gbEligibleBytesAfter = gbEligibleBytes(
            usedBytes = refunded.usedBytes,
            usedApmSpanBytes = refunded.usedApmSpanBytes,
            usedInfraMetricBytes = refunded.usedInfraMetricBytes
        )
        val byteOverageRefundDelta = (
            positiveLimitOverage(state.bytesLimit, gbEligibleBytesBefore) -
                positiveLimitOverage(state.bytesLimit, gbEligibleBytesAfter)
            ).coerceAtLeast(0)
        if (byteOverageRefundDelta <= 0) return
        val subscriptionId = state.subscriptionId ?: return

        Subscriptions.update({ Subscriptions.id eq subscriptionId }) {
            it[pending_overage_bytes] = (state.pendingOverageBytes - byteOverageRefundDelta).coerceAtLeast(0)
        }
    }

    private fun refundCountOverages(
        state: QuotaState,
        normalizedType: String,
        refunded: RefundedUsage
    ) {
        refundCustomMetricOverage(state, normalizedType, refunded.usedCustomMetrics)
        refundApmSpanOverage(state, normalizedType, refunded.usedApmSpans)
        refundInfraMetricOverage(state, normalizedType, refunded.usedInfraMetricSeriesHours)
        refundAnalyticsPageviewOverage(state, normalizedType, refunded.usedAnalyticsPageviews)
    }

    private fun refundCustomMetricOverage(
        state: QuotaState,
        normalizedType: String,
        usedAfter: Long
    ) {
        if (normalizedType != "custom_metric" || state.customMetricLimit < 0) return
        val refundDelta = refundOverageDelta(state.usedCustomMetrics, usedAfter, state.customMetricLimit)
        if (refundDelta <= 0) return
        val subscriptionId = state.subscriptionId ?: return

        Subscriptions.update({ Subscriptions.id eq subscriptionId }) {
            it[pending_custom_metric_overage_units] =
                (state.pendingCustomMetricOverageUnits - refundDelta).coerceAtLeast(0)
        }
    }

    private fun refundApmSpanOverage(
        state: QuotaState,
        normalizedType: String,
        usedAfter: Long
    ) {
        if (normalizedType != "apm_span" || state.apmSpanLimit < 0) return
        val refundDelta = refundOverageDelta(state.usedApmSpans, usedAfter, state.apmSpanLimit)
        if (refundDelta <= 0) return
        val subscriptionId = state.subscriptionId ?: return

        Subscriptions.update({ Subscriptions.id eq subscriptionId }) {
            it[pending_apm_span_overage_units] =
                (state.pendingApmSpanOverageUnits - refundDelta).coerceAtLeast(0)
        }
    }

    private fun refundInfraMetricOverage(
        state: QuotaState,
        normalizedType: String,
        usedAfter: Long
    ) {
        if (normalizedType != INFRA_METRIC_TYPE || state.infraMetricSeriesHourLimit < 0) return
        val refundDelta = refundOverageDelta(
            state.usedInfraMetricSeriesHours,
            usedAfter,
            state.infraMetricSeriesHourLimit
        )
        if (refundDelta <= 0) return
        val subscriptionId = state.subscriptionId ?: return

        Subscriptions.update({ Subscriptions.id eq subscriptionId }) {
            it[pending_infra_metric_overage_units] =
                (state.pendingInfraMetricOverageUnits - refundDelta).coerceAtLeast(0)
        }
    }

    private fun refundAnalyticsPageviewOverage(
        state: QuotaState,
        normalizedType: String,
        usedAfter: Long
    ) {
        if (normalizedType != "analytics_pageview" || state.analyticsPageviewLimit < 0) return
        val refundDelta = refundOverageDelta(state.usedAnalyticsPageviews, usedAfter, state.analyticsPageviewLimit)
        if (refundDelta <= 0) return
        val subscriptionId = state.subscriptionId ?: return

        Subscriptions.update({ Subscriptions.id eq subscriptionId }) {
            it[pending_analytics_pageview_overage_units] =
                (state.pendingAnalyticsPageviewOverageUnits - refundDelta).coerceAtLeast(0)
        }
    }

    private fun refundOverageDelta(
        usedBefore: Long,
        usedAfter: Long,
        limit: Long
    ): Long {
        return (limitedOverage(limit, usedBefore) - limitedOverage(limit, usedAfter)).coerceAtLeast(0)
    }

    private fun isWithinQuota(state: QuotaState, gbEligibleBytes: Long): Boolean {
        return bytesWithinBudget(state, gbEligibleBytes) &&
            countWithinBudget(state.customMetricLimit, state.usedCustomMetrics, state.bonusUnits) {
                state.customMetricOverageRateCentsPer100k
            } &&
            countWithinBudget(state.apmSpanLimit, state.usedApmSpans, state.bonusUnits) {
                state.apmSpanOverageRateCentsPer1m
            } &&
            countWithinBudget(
                state.infraMetricSeriesHourLimit,
                state.usedInfraMetricSeriesHours,
                state.bonusUnits
            ) {
                state.infraMetricOverageRateCentsPer100kSeriesHours
            } &&
            countWithinBudget(state.analyticsPageviewLimit, state.usedAnalyticsPageviews, state.bonusUnits) {
                state.analyticsPageviewOverageRateCentsPer100k
            }
    }

    private fun bytesWithinBudget(state: QuotaState, gbEligibleBytes: Long): Boolean {
        return state.bytesLimit <= 0 ||
            gbEligibleBytes <= (state.bytesLimit + state.bonusGbBytes + state.paygLimitBytes)
    }

    private fun gbEligibleBytes(state: QuotaState): Long {
        return gbEligibleBytes(
            usedBytes = state.usedBytes,
            usedApmSpanBytes = state.usedApmSpanBytes,
            usedInfraMetricBytes = state.usedInfraMetricBytes
        )
    }

    private fun gbEligibleBytes(
        usedBytes: Long,
        usedApmSpanBytes: Long,
        usedInfraMetricBytes: Long
    ): Long {
        return (usedBytes - usedApmSpanBytes - usedInfraMetricBytes).coerceAtLeast(0)
    }

    private fun excludedGbBytes(state: QuotaState): Long {
        return state.usedApmSpanBytes + state.usedInfraMetricBytes
    }

    private fun countWithinBudget(
        limit: Long,
        used: Long,
        bonusUnits: Long,
        overageRate: () -> Int
    ): Boolean {
        return limit < 0 || overageRate() > 0 || used <= (limit + bonusUnits)
    }

    private fun replayOverageCents(state: QuotaState): Int {
        if (state.replayLimit < 0 || state.replayOverageRateCentsPerGb <= 0) return 0
        if (state.usedReplayBytes <= 0 || state.usedReplays <= 0) return 0

        val overageReplays = limitedOverage(state.replayLimit, state.usedReplays)
        if (overageReplays <= 0) return 0

        val replayOverageBytes = (state.usedReplayBytes * overageReplays) / state.usedReplays
        return byteOverageCents(replayOverageBytes, state.replayOverageRateCentsPerGb)
    }

    private fun logOverageBytes(
        state: QuotaState,
        gbEligibleBytes: Long
    ): Long {
        val totalOverageBytes = positiveLimitOverage(state.bytesLimit, gbEligibleBytes)
        if (gbEligibleBytes <= 0 || totalOverageBytes <= 0) return 0
        return (totalOverageBytes * state.usedLogBytes) / gbEligibleBytes
    }

    private fun unitOverageCents(
        units: Long,
        rateCents: Int,
        divisor: Long
    ): Int {
        if (rateCents <= 0 || units <= 0) return 0
        return ((units * rateCents) / divisor).toInt()
    }

    private fun byteOverageCents(
        bytes: Long,
        rateCentsPerGb: Int
    ): Int {
        if (rateCentsPerGb <= 0 || bytes <= 0) return 0
        return ((bytes * rateCentsPerGb) / BYTES_PER_GB).toInt()
    }

    private fun limitedOverage(limit: Long, used: Long): Long {
        return if (limit >= 0) max(0, used - limit) else 0L
    }

    private fun positiveLimitOverage(limit: Long, used: Long): Long {
        return if (limit > 0) max(0, used - limit) else 0L
    }

    private fun normalizeEventType(eventType: String): String {
        return when (eventType.lowercase()) {
            "error" -> "error"
            "session", "sessions" -> "session"
            "transaction" -> "transaction"
            "replay" -> "replay"
            "feedback" -> "feedback"
            "llm" -> "llm"
            "log", "logs", "dd_log" -> "log"
            "apm_span", "apm", "otlp_trace", "dd_trace", "sentry_trace" -> "apm_span"
            "custom_metric", "metric", "otlp_metric", "dd_metric" -> "custom_metric"
            INFRA_METRIC_TYPE, "dd_infra_metric" -> INFRA_METRIC_TYPE
            "analytics_pageview" -> "analytics_pageview"
            "sourcemap", "artifact" -> "artifact"
            "dd_profile", "profile" -> "profile"
            "dd_infra" -> "dd_infra"
            "dd_event" -> "dd_event"
            "dd_orchestrator" -> "dd_orchestrator"
            "dd_dbm" -> "dd_dbm"
            "dd_debugger" -> "dd_debugger"
            "dd_misc" -> "dd_misc"
            "dd_ndm" -> "dd_ndm"
            "dd_security" -> "dd_security"
            else -> "error"
        }
    }

    private fun usedUnitsForType(state: QuotaState, eventType: String): Long {
        return when (eventType) {
            "error" -> state.usedErrors
            "apm_span" -> state.usedApmSpans
            "custom_metric" -> state.usedCustomMetrics
            INFRA_METRIC_TYPE -> state.usedInfraMetricSeriesHours
            "analytics_pageview" -> state.usedAnalyticsPageviews
            "transaction" -> state.usedTransactions
            "replay" -> state.usedReplays
            "feedback" -> state.usedFeedback
            "llm" -> state.usedLlmEvents
            "log" -> state.usedLogs
            else -> state.usedErrors
        }
    }

    private fun baseLimitForType(
        state: QuotaState,
        eventType: String
    ): Long {
        // Unified ingestion model: custom_metric, apm_span, infra_metric, and analytics_pageview
        // have count-based limits. Other types are gated by the unified GB limit.
        return when (eventType) {
            "custom_metric" -> state.customMetricLimit
            "apm_span" -> state.apmSpanLimit
            INFRA_METRIC_TYPE -> state.infraMetricSeriesHourLimit
            "analytics_pageview" -> state.analyticsPageviewLimit
            else -> -1L // No count-based limit; GB is the gate
        }
    }

    /**
     * Returns on-call used seats from the enterprise module if available, otherwise 0.
     */
    private fun getOnCallUsedSeatsIfAvailable(organizationId: Int): Int {
        return suspendRunCatching {
            val clazz = Class.forName("com.moneat.enterprise.services.oncall.OnCallScheduleService")
            val instance = clazz.getDeclaredConstructor().newInstance()
            val method = clazz.getMethod("getOnCallUsedSeats", Int::class.java)
            method.invoke(instance, organizationId) as? Int ?: 0
        }.getOrElse { _ ->
            0
        }
    }
}
