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

import com.moneat.billing.models.BillingUsageResponse
import com.moneat.billing.models.OrgUsageCounters
import com.moneat.billing.models.PricingTier
import com.moneat.billing.models.PricingTierConfigResponse
import com.moneat.billing.models.PricingTierConfigs
import com.moneat.config.EnvConfig
import com.moneat.shared.models.Subscriptions
import com.moneat.utils.SentryUtils
import kotlinx.datetime.DatePeriod
import kotlinx.datetime.LocalDate
import kotlinx.datetime.TimeZone
import kotlinx.datetime.plus
import kotlinx.datetime.toLocalDateTime
import kotlinx.datetime.todayIn
import mu.KotlinLogging
import org.jetbrains.exposed.v1.core.ResultRow
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
import kotlin.time.Clock

private val logger = KotlinLogging.logger {}

data class QuotaReservationResult(
    val allowed: Boolean,
    val reason: String? = null,
    val eventType: String? = null,
    val usage: BillingUsageResponse
)

class BillingQuotaService(
    private val pricingTierService: PricingTierService = PricingTierService()
) {
    companion object {
        private const val BYTES_PER_GB = 1_073_741_824L
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
            val requestedLlm = normalizedRequests["llm"] ?: 0L
            val requestedLogUnits = normalizedRequests["log"] ?: 0L
            val requestedAggregate =
                normalizedRequests
                    .filterKeys { it !in listOf("llm", "log", "apm_span", "custom_metric") }
                    .values
                    .sum()
            val totalAfter = state.usedUnits + requestedAggregate
            val requestedTotalBytes = normalizedBytes.values.sum()
            val bytesAfter = state.usedBytes + requestedTotalBytes

            for ((eventType, requestedUnits) in normalizedRequests) {
                // Unified ingestion model: only custom_metric is count-gated.
                // All other types are gated by the unified GB limit below.
                if (eventType != "custom_metric") continue

                val usedForType = usedUnitsForType(state, eventType)
                val typeLimit = baseLimitForType(state, eventType)
                val typeAfter = usedForType + requestedUnits
                val hasOwnOverageBilling =
                    (eventType == "custom_metric" && state.customMetricOverageRateCentsPer100k > 0)
                val effectiveTypeLimit = when {
                    typeLimit < 0 -> Long.MAX_VALUE
                    hasOwnOverageBilling -> Long.MAX_VALUE
                    else -> typeLimit + state.bonusUnits
                }

                if (typeLimit >= 0 && typeAfter > effectiveTypeLimit) {
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

                    return@transaction QuotaReservationResult(
                        allowed = false,
                        reason = "event_type_quota_exceeded",
                        eventType = eventType,
                        usage = toUsageResponse(state)
                    )
                }
            }

            // Unified ingestion model: GB/byte limit is the primary gate for all data types
            // (replaces old per-type count limits and aggregate unit limit)
            val effectiveBytesLimit =
                if (state.bytesLimit > 0) {
                    state.bytesLimit + state.bonusGbBytes + state.paygLimitBytes
                } else {
                    Long.MAX_VALUE
                }
            if (state.bytesLimit > 0 && bytesAfter > effectiveBytesLimit) {
                SentryUtils.breadcrumb(
                    "billing",
                    "GB quota exceeded",
                    mapOf(
                        "organization_id" to organizationId,
                        "requested_bytes" to requestedTotalBytes,
                        "used_bytes" to state.usedBytes,
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

            val requestedErrors = normalizedRequests["error"] ?: 0L
            val requestedTransactions = normalizedRequests["transaction"] ?: 0L
            val requestedReplays = normalizedRequests["replay"] ?: 0L
            val requestedFeedback = normalizedRequests["feedback"] ?: 0L
            val requestedApmSpans = normalizedRequests["apm_span"] ?: 0L
            val requestedCustomMetrics = normalizedRequests["custom_metric"] ?: 0L

            val errorBytes = normalizedBytes["error"] ?: 0L
            val replayBytes = normalizedBytes["replay"] ?: 0L
            val logBytes = normalizedBytes["log"] ?: 0L
            val llmBytes = normalizedBytes["llm"] ?: 0L

            OrgUsageCounters.update({
                (OrgUsageCounters.organization_id eq organizationId) and
                    (OrgUsageCounters.period_start eq state.periodStart)
            }) {
                it[used_units] = totalAfter
                it[used_errors] = state.usedErrors + requestedErrors
                it[used_transactions] = state.usedTransactions + requestedTransactions
                it[used_replays] = state.usedReplays + requestedReplays
                it[used_feedback] = state.usedFeedback + requestedFeedback
                it[used_llm_events] = state.usedLlmEvents + requestedLlm
                it[used_logs] = state.usedLogs + requestedLogUnits
                it[used_apm_spans] = state.usedApmSpans + requestedApmSpans
                it[used_custom_metrics] = state.usedCustomMetrics + requestedCustomMetrics
                it[used_bytes] = bytesAfter
                it[used_error_bytes] = state.usedErrorBytes + errorBytes
                it[used_replay_bytes] = state.usedReplayBytes + replayBytes
                it[used_log_bytes] = state.usedLogBytes + logBytes
                it[used_llm_bytes] = state.usedLlmBytes + llmBytes
                it[updated_at] = Clock.System.now()
            }

            // Track unified ingestion GB overage for Stripe metering
            val ingestionOverageByteBefore = max(0, state.usedBytes - state.bytesLimit)
            val ingestionOverageByteAfter = if (state.bytesLimit > 0) {
                max(0, bytesAfter - state.bytesLimit)
            } else {
                0L
            }
            val ingestionOverageByteDelta = ingestionOverageByteAfter - ingestionOverageByteBefore

            if (state.subscriptionId != null && ingestionOverageByteDelta > 0 &&
                state.overageRateCentsPerGb > 0
            ) {
                SentryUtils.breadcrumb(
                    "billing",
                    "Ingestion overage incurred",
                    mapOf(
                        "organization_id" to organizationId,
                        "overage_byte_delta" to ingestionOverageByteDelta,
                        "subscription_id" to state.subscriptionId
                    )
                )

                // Accumulate raw byte overage in pending_overage_bytes for precision.
                // Conversion to GB*100 meter units happens at flush time so that
                // sub-10MB increments are never silently dropped by integer division.
                Subscriptions.update({ Subscriptions.id eq state.subscriptionId }) {
                    it[pending_overage_bytes] = state.pendingOverageBytes + ingestionOverageByteDelta
                }
            }

            // Track custom metric overages for Stripe metering
            if (state.subscriptionId != null) {
                val customMetricOverageBefore = if (state.customMetricLimit >= 0) {
                    max(0, state.usedCustomMetrics - state.customMetricLimit)
                } else {
                    0L
                }
                val customMetricOverageAfter = if (state.customMetricLimit >= 0) {
                    max(0, state.usedCustomMetrics + requestedCustomMetrics - state.customMetricLimit)
                } else {
                    0L
                }
                val customMetricOverageDelta = customMetricOverageAfter - customMetricOverageBefore

                if (customMetricOverageDelta > 0) {
                    SentryUtils.breadcrumb(
                        "billing",
                        "Custom metric overage incurred",
                        mapOf(
                            "organization_id" to organizationId,
                            "custom_metric_overage_delta" to customMetricOverageDelta,
                            "subscription_id" to state.subscriptionId
                        )
                    )
                    Subscriptions.update({ Subscriptions.id eq state.subscriptionId }) {
                        it[pending_custom_metric_overage_units] =
                            state.pendingCustomMetricOverageUnits + customMetricOverageDelta
                    }
                }
            }

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
            val totalAfter = (state.usedUnits - requestedUnits).coerceAtLeast(0)
            val usedCustomMetricsAfter = if (normalizedType == "custom_metric") {
                (state.usedCustomMetrics - requestedUnits).coerceAtLeast(0)
            } else {
                state.usedCustomMetrics
            }
            val usedApmSpansAfter = if (normalizedType == "apm_span") {
                (state.usedApmSpans - requestedUnits).coerceAtLeast(0)
            } else {
                state.usedApmSpans
            }
            val usedErrorsAfter = if (normalizedType == "error") {
                (state.usedErrors - requestedUnits).coerceAtLeast(0)
            } else {
                state.usedErrors
            }
            val usedTransactionsAfter = if (normalizedType == "transaction") {
                (state.usedTransactions - requestedUnits).coerceAtLeast(0)
            } else {
                state.usedTransactions
            }
            val usedReplaysAfter = if (normalizedType == "replay") {
                (state.usedReplays - requestedUnits).coerceAtLeast(0)
            } else {
                state.usedReplays
            }
            val usedFeedbackAfter = if (normalizedType == "feedback") {
                (state.usedFeedback - requestedUnits).coerceAtLeast(0)
            } else {
                state.usedFeedback
            }
            val usedLlmAfter = if (normalizedType == "llm") {
                (state.usedLlmEvents - requestedUnits).coerceAtLeast(0)
            } else {
                state.usedLlmEvents
            }
            val usedLogsAfter = if (normalizedType == "log") {
                (state.usedLogs - requestedUnits).coerceAtLeast(0)
            } else {
                state.usedLogs
            }
            val bytesAfter = (state.usedBytes - requestedBytes).coerceAtLeast(0)
            val usedErrorBytesAfter = if (normalizedType == "error") {
                (state.usedErrorBytes - requestedBytes).coerceAtLeast(0)
            } else {
                state.usedErrorBytes
            }
            val usedReplayBytesAfter = if (normalizedType == "replay") {
                (state.usedReplayBytes - requestedBytes).coerceAtLeast(0)
            } else {
                state.usedReplayBytes
            }
            val usedLogBytesAfter = if (normalizedType == "log") {
                (state.usedLogBytes - requestedBytes).coerceAtLeast(0)
            } else {
                state.usedLogBytes
            }
            val usedLlmBytesAfter = if (normalizedType == "llm") {
                (state.usedLlmBytes - requestedBytes).coerceAtLeast(0)
            } else {
                state.usedLlmBytes
            }

            OrgUsageCounters.update({
                (OrgUsageCounters.organization_id eq organizationId) and
                    (OrgUsageCounters.period_start eq state.periodStart)
            }) {
                it[used_units] = totalAfter
                it[used_errors] = usedErrorsAfter
                it[used_transactions] = usedTransactionsAfter
                it[used_replays] = usedReplaysAfter
                it[used_feedback] = usedFeedbackAfter
                it[used_llm_events] = usedLlmAfter
                it[used_logs] = usedLogsAfter
                it[used_apm_spans] = usedApmSpansAfter
                it[used_custom_metrics] = usedCustomMetricsAfter
                it[used_bytes] = bytesAfter
                it[used_error_bytes] = usedErrorBytesAfter
                it[used_replay_bytes] = usedReplayBytesAfter
                it[used_log_bytes] = usedLogBytesAfter
                it[used_llm_bytes] = usedLlmBytesAfter
                it[updated_at] = Clock.System.now()
            }

            if (state.subscriptionId != null) {
                // PAYG refund: unit-count-based, for legacy PAYG subscriptions
                val overageBefore = max(0, totalBefore - state.baseLimitUnits)
                val overageAfter = max(0, totalAfter - state.baseLimitUnits)
                val overageRefundDelta = (overageBefore - overageAfter).coerceAtLeast(0)
                if (overageRefundDelta > 0 && state.paygRateMicrosPerUnit > 0) {
                    val overageMicrosRefund = overageRefundDelta * state.paygRateMicrosPerUnit
                    Subscriptions.update({ Subscriptions.id eq state.subscriptionId }) {
                        it[payg_used_units] = (state.paygUsedUnits - overageRefundDelta).coerceAtLeast(0)
                        it[payg_used_micros] = (state.paygUsedMicros - overageMicrosRefund).coerceAtLeast(0)
                    }
                }
                // Byte-based meter refund: mirrors the byte accumulation in reserveUnits
                if (requestedBytes > 0 && state.bytesLimit > 0) {
                    val byteOverageBefore = max(0, state.usedBytes - state.bytesLimit)
                    val byteOverageAfter = max(0, bytesAfter - state.bytesLimit)
                    val byteOverageRefundDelta = (byteOverageBefore - byteOverageAfter).coerceAtLeast(0)
                    if (byteOverageRefundDelta > 0) {
                        Subscriptions.update({ Subscriptions.id eq state.subscriptionId }) {
                            it[pending_overage_bytes] =
                                (state.pendingOverageBytes - byteOverageRefundDelta).coerceAtLeast(0)
                        }
                    }
                }
                if (normalizedType == "custom_metric" && state.customMetricLimit >= 0) {
                    val overageBefore = max(0, state.usedCustomMetrics - state.customMetricLimit)
                    val overageAfter = max(0, usedCustomMetricsAfter - state.customMetricLimit)
                    val customMetricRefundDelta = (overageBefore - overageAfter).coerceAtLeast(0)
                    if (customMetricRefundDelta > 0) {
                        Subscriptions.update({ Subscriptions.id eq state.subscriptionId }) {
                            it[pending_custom_metric_overage_units] =
                                (state.pendingCustomMetricOverageUnits - customMetricRefundDelta).coerceAtLeast(0)
                        }
                    }
                }
            }
        }
    }

    private data class QuotaState(
        val organizationId: Int,
        val plan: String,
        val status: String,
        val retentionDays: Int,
        val logRetentionDays: Int,
        val replayRetentionDays: Int,
        val llmRetentionDays: Int,
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
        val usedErrorBytes: Long,
        val usedReplayBytes: Long,
        val usedLogBytes: Long,
        val usedLlmBytes: Long,
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
        val pendingApmSpanOverageUnits: Long,
        val pendingCustomMetricOverageUnits: Long,
    )

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
                    byId != null -> tierFromRow(byId)
                    byPlan != null -> tierFromRow(byPlan)
                    free != null -> tierFromRow(free)
                    else -> tierFromEnum(sub?.get(Subscriptions.plan) ?: "FREE")
                }
            }
        val periodStart =
            sub?.get(Subscriptions.current_period_start)?.toLocalDateTime(TimeZone.UTC)?.date
                ?: LocalDate(now.year, now.month, 1)
        val periodEnd =
            sub?.get(Subscriptions.current_period_end)?.toLocalDateTime(TimeZone.UTC)?.date
                ?: periodStart.plus(DatePeriod(months = 1, days = -1))

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
        val usedErrorBytes = usageRow[OrgUsageCounters.used_error_bytes]
        val usedReplayBytes = usageRow[OrgUsageCounters.used_replay_bytes]
        val usedLogBytes = usageRow[OrgUsageCounters.used_log_bytes]
        val usedLlmBytes = usageRow[OrgUsageCounters.used_llm_bytes]
        val usedAnalyticsPageviews = usageRow[OrgUsageCounters.used_analytics_pageviews]
        val usedApmSpans = usageRow[OrgUsageCounters.used_apm_spans]
        val usedCustomMetrics = usageRow[OrgUsageCounters.used_custom_metrics]
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
                (paygBudgetCents.toLong() * 10_000L) / paygRateMicros
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

        return QuotaState(
            organizationId = organizationId,
            plan = tier.tierName.lowercase(),
            status = sub?.get(Subscriptions.status) ?: "active",
            retentionDays = tier.retentionDays,
            logRetentionDays = tier.logRetentionDays,
            replayRetentionDays = tier.replayRetentionDays,
            llmRetentionDays = tier.llmRetentionDays,
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
            usedErrorBytes = usedErrorBytes,
            usedReplayBytes = usedReplayBytes,
            usedLogBytes = usedLogBytes,
            usedLlmBytes = usedLlmBytes,
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
            pendingApmSpanOverageUnits = sub?.get(Subscriptions.pending_apm_span_overage_units) ?: 0,
            pendingCustomMetricOverageUnits = sub?.get(Subscriptions.pending_custom_metric_overage_units) ?: 0,
        )
    }

    private fun toUsageResponse(state: QuotaState): BillingUsageResponse {
        // Unified ingestion model: GB is the primary gate
        val bytesWithinBudget = state.bytesLimit <= 0 ||
            state.usedBytes <= (state.bytesLimit + state.bonusGbBytes + state.paygLimitBytes)

        // Custom metrics check (count-based, separate from GB)
        val customMetricsWithinBudget = state.customMetricLimit < 0 ||
            state.customMetricOverageRateCentsPer100k > 0 ||
            state.usedCustomMetrics <= (state.customMetricLimit + state.bonusUnits)

        // Unified ingestion overage estimate (all bytes over GB limit)
        val ingestionOverageBytes = if (state.bytesLimit > 0) {
            max(0, state.usedBytes - state.bytesLimit)
        } else {
            0L
        }
        val ingestionOverageCents =
            if (state.overageRateCentsPerGb > 0 && ingestionOverageBytes > 0) {
                ((ingestionOverageBytes * state.overageRateCentsPerGb) / BYTES_PER_GB).toInt()
            } else {
                0
            }

        // Legacy per-type overage estimates (kept for backward compat)
        val errorOverageUnits = max(0, state.usedErrors - state.errorLimit)
        val errorOverageCents =
            if (state.errorOverageRateCentsPer1k > 0 && errorOverageUnits > 0) {
                ((errorOverageUnits * state.errorOverageRateCentsPer1k) / 1000).toInt()
            } else {
                0
            }

        val replayOverageCents = when {
            state.replayLimit < 0 -> 0
            state.replayOverageRateCentsPerGb <= 0 -> 0
            state.usedReplayBytes <= 0 || state.usedReplays <= 0 -> 0
            else -> {
                val overageReplays = max(0, state.usedReplays - state.replayLimit)
                if (overageReplays <= 0) {
                    0
                } else {
                    val replayOverageBytes =
                        (state.usedReplayBytes * overageReplays) / state.usedReplays
                    ((replayOverageBytes * state.replayOverageRateCentsPerGb) / BYTES_PER_GB).toInt()
                }
            }
        }

        val logOverageBytes = max(0, state.usedLogBytes - state.bytesLimit)
        val logOverageCents =
            if (state.logOverageRateCentsPerGb > 0 && logOverageBytes > 0) {
                ((logOverageBytes * state.logOverageRateCentsPerGb) / BYTES_PER_GB).toInt()
            } else {
                0
            }

        val llmOverageUnits = max(0, state.usedLlmEvents - state.llmEventLimit)
        val llmOverageCents =
            if (state.llmOverageRateCentsPer1k > 0 && llmOverageUnits > 0) {
                ((llmOverageUnits * state.llmOverageRateCentsPer1k) / 1000).toInt()
            } else {
                0
            }

        val analyticsPageviewOverageUnits =
            max(0, state.usedAnalyticsPageviews - state.analyticsPageviewLimit)
        val analyticsPageviewOverageCents =
            if (state.analyticsPageviewOverageRateCentsPer100k > 0 &&
                analyticsPageviewOverageUnits > 0
            ) {
                ((analyticsPageviewOverageUnits *
                    state.analyticsPageviewOverageRateCentsPer100k) / 100_000).toInt()
            } else {
                0
            }

        val apmSpanOverageUnits = if (state.apmSpanLimit >= 0) {
            max(0, state.usedApmSpans - state.apmSpanLimit)
        } else {
            0L
        }
        val apmSpanOverageCents =
            if (state.apmSpanOverageRateCentsPer1m > 0 && apmSpanOverageUnits > 0) {
                ((apmSpanOverageUnits * state.apmSpanOverageRateCentsPer1m) / 1_000_000).toInt()
            } else {
                0
            }

        val customMetricOverageUnits = if (state.customMetricLimit >= 0) {
            max(0, state.usedCustomMetrics - state.customMetricLimit)
        } else {
            0L
        }
        val customMetricOverageCents =
            if (state.customMetricOverageRateCentsPer100k > 0 && customMetricOverageUnits > 0) {
                ((customMetricOverageUnits *
                    state.customMetricOverageRateCentsPer100k) / 100_000).toInt()
            } else {
                0
            }

        val totalOverageCents = ingestionOverageCents +
            analyticsPageviewOverageCents + customMetricOverageCents

        return BillingUsageResponse(
            organizationId = state.organizationId,
            periodStart = state.periodStart.toString(),
            periodEnd = state.periodEnd.toString(),
            retentionDays = state.retentionDays,
            logRetentionDays = state.logRetentionDays,
            replayRetentionDays = state.replayRetentionDays,
            llmRetentionDays = state.llmRetentionDays,
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
            bytesLimit = state.bytesLimit,
            ingestionOverageCentsEstimate = ingestionOverageCents,
            ingestionOverageRateCentsPerGb = state.overageRateCentsPerGb,
            baseLimitUnits = state.baseLimitUnits,
            paygLimitUnits = state.paygLimitUnits,
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
            totalOverageCentsEstimate = totalOverageCents,
            errorOverageRateCentsPer1k = state.errorOverageRateCentsPer1k,
            replayOverageRateCentsPerGb = state.replayOverageRateCentsPerGb,
            logOverageRateCentsPerGb = state.logOverageRateCentsPerGb,
            llmOverageRateCentsPer1k = state.llmOverageRateCentsPer1k,
            apmSpanOverageRateCentsPer1m = state.apmSpanOverageRateCentsPer1m,
            customMetricOverageRateCentsPer100k = state.customMetricOverageRateCentsPer100k,
            oncallSeats = state.oncallSeats,
            oncallUsedSeats = state.oncallUsedSeats,
            oncallPerUserMonthlyCents = state.oncallPerUserMonthlyCents,
            oncallEnabled = state.oncallEnabled,
            usedAnalyticsPageviews = state.usedAnalyticsPageviews,
            analyticsPageviewLimit = state.analyticsPageviewLimit,
            analyticsPageviewOverageCentsEstimate = analyticsPageviewOverageCents,
            analyticsPageviewOverageRateCentsPer100k =
                state.analyticsPageviewOverageRateCentsPer100k,
            usedApmSpans = state.usedApmSpans,
            apmSpanLimit = state.apmSpanLimit,
            usedCustomMetrics = state.usedCustomMetrics,
            customMetricLimit = state.customMetricLimit,
            plan = state.plan,
            status = state.status,
            withinQuota = bytesWithinBudget && customMetricsWithinBudget,
            bonusGbBytes = state.bonusGbBytes,
            bonusUnits = state.bonusUnits,
            bonusReason = state.bonusReason
        )
    }

    private fun tierFromRow(row: ResultRow): PricingTierConfigResponse {
        return PricingTierConfigResponse(
            id = row[PricingTierConfigs.id],
            tierName = row[PricingTierConfigs.tier_name],
            version = row[PricingTierConfigs.version],
            monthlyUnitLimit = row[PricingTierConfigs.monthly_unit_limit],
            monthlyErrorLimit = row[PricingTierConfigs.monthly_error_limit],
            monthlyTransactionLimit = row[PricingTierConfigs.monthly_transaction_limit],
            monthlyReplayLimit = row[PricingTierConfigs.monthly_replay_limit],
            monthlyFeedbackLimit = row[PricingTierConfigs.monthly_feedback_limit],
            monthlyLlmEventLimit = row[PricingTierConfigs.monthly_llm_event_limit],
            monthlyGbLimit = row[PricingTierConfigs.monthly_gb_limit],
            retentionDays = row[PricingTierConfigs.retention_days],
            logRetentionDays = row[PricingTierConfigs.log_retention_days],
            replayRetentionDays = row[PricingTierConfigs.replay_retention_days],
            llmRetentionDays = row[PricingTierConfigs.llm_retention_days],
            statusPagesEnabled = row[PricingTierConfigs.status_pages_enabled],
            statusPageCustomDomainEnabled = row[PricingTierConfigs.status_page_custom_domain_enabled],
            sessionReplayEnabled = row[PricingTierConfigs.session_replay_enabled],
            slackEnabled = row[PricingTierConfigs.slack_enabled],
            discordEnabled = row[PricingTierConfigs.discord_enabled],
            incidentIoEnabled = row[PricingTierConfigs.incident_io_enabled],
            samlEnabled = row[PricingTierConfigs.saml_enabled],
            oidcEnabled = row[PricingTierConfigs.oidc_enabled],
            prioritySupportEnabled = row[PricingTierConfigs.priority_support_enabled],
            slaEnabled = row[PricingTierConfigs.sla_enabled],
            customRetentionEnabled = row[PricingTierConfigs.custom_retention_enabled],
            maxProjects = row[PricingTierConfigs.max_projects],
            maxSystems = row[PricingTierConfigs.max_systems],
            monitorIntervalSeconds = row[PricingTierConfigs.monitor_interval_seconds],
            monthlyPriceCents = row[PricingTierConfigs.monthly_price_cents],
            yearlyPriceCents = row[PricingTierConfigs.yearly_price_cents],
            trialDays = row[PricingTierConfigs.trial_days],
            paygEnabled = row[PricingTierConfigs.payg_enabled],
            paygRateMicrosPerUnit = row[PricingTierConfigs.payg_rate_micros_per_unit],
            overageRateCentsPerGb = row[PricingTierConfigs.overage_rate_cents_per_gb],
            errorOverageRateCentsPer1k = row[PricingTierConfigs.error_overage_rate_cents_per_1k],
            replayOverageRateCentsPerGb = row[PricingTierConfigs.replay_overage_rate_cents_per_gb],
            llmOverageRateCentsPer1k = row[PricingTierConfigs.llm_overage_rate_cents_per_1k],
            stripeBasePriceId = row[PricingTierConfigs.stripe_base_price_id],
            stripeOveragePriceId = row[PricingTierConfigs.stripe_overage_price_id],
            stripeYearlyBasePriceId = row[PricingTierConfigs.stripe_yearly_base_price_id],
            stripeYearlyOveragePriceId = row[PricingTierConfigs.stripe_yearly_overage_price_id],
            stripeOncallPriceId = row[PricingTierConfigs.stripe_oncall_price_id],
            stripeOncallYearlyPriceId = row[PricingTierConfigs.stripe_oncall_yearly_price_id],
            oncallPerUserMonthlyCents = row[PricingTierConfigs.oncall_per_user_monthly_cents],
            oncallPerUserYearlyCents = row[PricingTierConfigs.oncall_per_user_yearly_cents],
            oncallEnabled = row[PricingTierConfigs.oncall_enabled],
            maxAnalyticsSites = row[PricingTierConfigs.max_analytics_sites],
            analyticsRetentionDays = row[PricingTierConfigs.analytics_retention_days],
            monthlyAnalyticsPageviewLimit = row[PricingTierConfigs.monthly_analytics_pageview_limit],
            analyticsPageviewOverageRateCentsPer100k = row[PricingTierConfigs.analytics_pageview_overage_rate_cents_per_100k],
            monthlyApmSpanLimit = row[PricingTierConfigs.monthly_apm_span_limit],
            apmSpanOverageRateCentsPer1m = row[PricingTierConfigs.apm_span_overage_rate_cents_per_1m],
            monthlyCustomMetricLimit = row[PricingTierConfigs.monthly_custom_metric_limit],
            customMetricOverageRateCentsPer100k = row[PricingTierConfigs.custom_metric_overage_rate_cents_per_100k],
            maxHosts = row[PricingTierConfigs.max_hosts],
            profilingEnabled = row[PricingTierConfigs.profiling_enabled],
            networkMonitoringEnabled = row[PricingTierConfigs.network_monitoring_enabled],
            dbmEnabled = row[PricingTierConfigs.dbm_enabled],
            debuggerEnabled = row[PricingTierConfigs.debugger_enabled],
            k8sMonitoringEnabled = row[PricingTierConfigs.k8s_monitoring_enabled],
            dataStreamsEnabled = row[PricingTierConfigs.data_streams_enabled],
            sbomEnabled = row[PricingTierConfigs.sbom_enabled],
            syntheticsEnabled = row[PricingTierConfigs.synthetics_enabled],
            isCurrent = row[PricingTierConfigs.is_current]
        )
    }

    private fun tierFromEnum(tierName: String): PricingTierConfigResponse {
        val tier = PricingTier.entries.find { it.name.equals(tierName, ignoreCase = true) } ?: PricingTier.FREE
        return PricingTierConfigResponse(
            id = 0,
            tierName = tier.name,
            version = 1,
            monthlyUnitLimit = tier.monthlyErrorLimit,
            monthlyErrorLimit = tier.monthlyErrorLimit,
            monthlyTransactionLimit = 0,
            monthlyReplayLimit = tier.monthlyReplayLimit,
            monthlyFeedbackLimit = 0,
            monthlyLlmEventLimit = tier.monthlyLlmEventLimit,
            monthlyGbLimit = tier.monthlyGbBytes,
            retentionDays = tier.retentionDays,
            logRetentionDays = tier.retentionDays,
            replayRetentionDays = tier.retentionDays,
            llmRetentionDays = tier.retentionDays,
            statusPagesEnabled = true,
            statusPageCustomDomainEnabled = true,
            sessionReplayEnabled = true,
            slackEnabled = true,
            discordEnabled = true,
            incidentIoEnabled = true,
            samlEnabled = tier == PricingTier.TEAM || tier == PricingTier.BUSINESS,
            oidcEnabled = tier == PricingTier.TEAM || tier == PricingTier.BUSINESS,
            prioritySupportEnabled = tier == PricingTier.BUSINESS,
            slaEnabled = tier == PricingTier.BUSINESS,
            customRetentionEnabled = tier == PricingTier.BUSINESS,
            maxProjects = tier.maxProjects,
            maxSystems = tier.maxSystems,
            monitorIntervalSeconds = tier.monitorIntervalSeconds,
            monthlyPriceCents =
            when (tier) {
                PricingTier.FREE -> 0
                PricingTier.PRO -> 2900
                PricingTier.TEAM -> 7900
                PricingTier.BUSINESS -> 19900
            },
            yearlyPriceCents =
            when (tier) {
                PricingTier.FREE -> 0
                PricingTier.PRO -> 28800
                PricingTier.TEAM -> 79200
                PricingTier.BUSINESS -> 199200
            },
            trialDays = if (tier == PricingTier.FREE) 0 else 14,
            paygEnabled = tier != PricingTier.FREE,
            paygRateMicrosPerUnit = if (tier == PricingTier.FREE) 0 else 400000,
            overageRateCentsPerGb = if (tier == PricingTier.FREE) 0 else 40,
            errorOverageRateCentsPer1k = if (tier == PricingTier.FREE) 0 else 10,
            replayOverageRateCentsPerGb = if (tier == PricingTier.FREE) 0 else 40,
            llmOverageRateCentsPer1k = if (tier == PricingTier.FREE) 0 else 100,
            stripeBasePriceId = null,
            stripeOveragePriceId = null,
            stripeYearlyBasePriceId = null,
            stripeYearlyOveragePriceId = null,
            stripeOncallPriceId = null,
            stripeOncallYearlyPriceId = null,
            oncallPerUserMonthlyCents = 500, // Default $5
            oncallPerUserYearlyCents = 5000, // Default $50
            oncallEnabled = tier != PricingTier.FREE,
            maxAnalyticsSites = when (tier) {
                PricingTier.FREE -> 1
                PricingTier.PRO -> 5
                PricingTier.TEAM -> 10
                PricingTier.BUSINESS -> null
            },
            analyticsRetentionDays = when (tier) {
                PricingTier.FREE, PricingTier.PRO -> 1095
                PricingTier.TEAM, PricingTier.BUSINESS -> 1825
            },
            monthlyAnalyticsPageviewLimit = when (tier) {
                PricingTier.FREE -> 10_000
                PricingTier.PRO -> 100_000
                PricingTier.TEAM -> 1_000_000
                PricingTier.BUSINESS -> 10_000_000
            },
            analyticsPageviewOverageRateCentsPer100k = if (tier == PricingTier.FREE) 0 else 1000,
            monthlyApmSpanLimit = when (tier) {
                PricingTier.FREE -> 500_000
                PricingTier.PRO -> 10_000_000
                PricingTier.TEAM -> 100_000_000
                PricingTier.BUSINESS -> Long.MAX_VALUE
            },
            apmSpanOverageRateCentsPer1m = if (tier == PricingTier.FREE) 0 else 30,
            monthlyCustomMetricLimit = when (tier) {
                PricingTier.FREE -> 100_000
                PricingTier.PRO -> 1_000_000
                PricingTier.TEAM -> 10_000_000
                PricingTier.BUSINESS -> Long.MAX_VALUE
            },
            customMetricOverageRateCentsPer100k = if (tier == PricingTier.FREE) 0 else 50,
            maxHosts = if (tier == PricingTier.FREE) 3 else null,
            profilingEnabled = true,
            networkMonitoringEnabled = true,
            dbmEnabled = true,
            debuggerEnabled = true,
            k8sMonitoringEnabled = true,
            dataStreamsEnabled = true,
            sbomEnabled = true,
            syntheticsEnabled = true,
            isCurrent = true
        )
    }

    private fun normalizeEventType(eventType: String): String {
        return when (eventType.lowercase()) {
            "error" -> "error"
            "transaction" -> "transaction"
            "replay" -> "replay"
            "feedback" -> "feedback"
            "llm" -> "llm"
            "log", "logs" -> "log"
            "apm_span", "apm" -> "apm_span"
            "custom_metric", "metric" -> "custom_metric"
            else -> "error"
        }
    }

    private fun isStripeMeteredUnitType(eventType: String): Boolean {
        return eventType == "error" || eventType == "transaction" || eventType == "replay" || eventType == "feedback"
    }

    private fun usedUnitsForType(state: QuotaState, eventType: String): Long {
        return when (eventType) {
            "error" -> state.usedErrors
            "apm_span" -> state.usedApmSpans
            "custom_metric" -> state.usedCustomMetrics
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
        // Unified ingestion model: only custom_metric has count-based limits.
        // All other types are gated by the unified GB limit.
        return when (eventType) {
            "custom_metric" -> state.customMetricLimit
            else -> -1L // No count-based limit; GB is the gate
        }
    }

    /**
     * Returns on-call used seats from the enterprise module if available, otherwise 0.
     */
    private fun getOnCallUsedSeatsIfAvailable(organizationId: Int): Int {
        return try {
            val clazz = Class.forName("com.moneat.enterprise.services.oncall.OnCallScheduleService")
            val instance = clazz.getDeclaredConstructor().newInstance()
            val method = clazz.getMethod("getOnCallUsedSeats", Int::class.java)
            method.invoke(instance, organizationId) as? Int ?: 0
        } catch (_: ClassNotFoundException) {
            0
        } catch (_: Exception) {
            0
        }
    }
}
