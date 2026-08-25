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
import com.moneat.billing.models.QuotaNotificationsSent
import com.moneat.billing.repositories.SubscriptionRepositoryImpl
import com.moneat.config.EnvConfig
import com.moneat.monitoring.OperationalMetrics
import com.moneat.notifications.services.EmailService
import com.moneat.shared.models.Memberships
import com.moneat.shared.models.Organizations
import com.moneat.shared.models.Subscriptions
import com.moneat.shared.models.Users
import com.moneat.shared.repositories.OrganizationRepositoryImpl
import com.moneat.shared.services.TaskLock
import com.moneat.utils.suspendRunCatching
import io.ktor.server.config.ApplicationConfig
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.delay
import kotlinx.coroutines.isActive
import kotlinx.coroutines.launch
import kotlinx.datetime.LocalDate
import mu.KotlinLogging
import org.jetbrains.exposed.v1.core.SortOrder
import org.jetbrains.exposed.v1.core.and
import org.jetbrains.exposed.v1.core.eq
import org.jetbrains.exposed.v1.core.inList
import org.jetbrains.exposed.v1.jdbc.deleteWhere
import org.jetbrains.exposed.v1.jdbc.insertIgnore
import org.jetbrains.exposed.v1.jdbc.selectAll
import org.jetbrains.exposed.v1.jdbc.transactions.transaction
import java.time.DayOfWeek
import java.time.ZoneOffset
import java.time.temporal.WeekFields
import java.util.Locale
import kotlin.time.Clock
import kotlin.time.Duration.Companion.minutes
import kotlin.time.Duration.Companion.seconds

private val logger = KotlinLogging.logger {}

private const val BILLING_JOB_INTERVAL_MS = 60_000L
private const val QUOTA_WARNING_THRESHOLD = 0.8
private const val QUOTA_CRITICAL_THRESHOLD = 0.9
private const val QUOTA_EXCEEDED_THRESHOLD = 1.0
private const val CENTS_PER_DOLLAR = 100.0
private const val BYTES_PER_GB = 1_073_741_824.0
private const val UNLIMITED_UNIT_SENTINEL = 9_000_000_000_000_000L
private const val BASE_WARNING_NOTIFICATION = "base_80"
private const val BASE_CRITICAL_NOTIFICATION = "base_90"
private const val BASE_EXCEEDED_NOTIFICATION = "base_100"
private const val PAYG_WARNING_NOTIFICATION = "payg_80"
private const val INSIGHTS_WEEKLY_NOTIFICATION_PREFIX = "insights_weekly_"

class BillingBackgroundService(
    private val stripeService: StripeService = StripeService(
        SubscriptionRepositoryImpl(),
        OrganizationRepositoryImpl()
    ),
    private val quotaService: BillingQuotaService = BillingQuotaService(),
    private val emailService: EmailService = EmailService(),
    private val pricingTierService: PricingTierService = PricingTierService()
) {
    private val config = ApplicationConfig("application.conf")
    private val frontendUrl = config.property("email.frontendUrl").getString()
    private val billingEnabled =
        config.propertyOrNull("billing.backgroundJobsEnabled")?.getString()?.toBooleanStrictOrNull() ?: true

    private var meteredUsageJob: Job? = null
    private var dunningDowngradeJob: Job? = null
    private var quotaNotificationJob: Job? = null

    fun start(scope: CoroutineScope) {
        if (!billingEnabled) {
            logger.info { "Billing background jobs are disabled by config" }
            return
        }

        meteredUsageJob =
            scope.launch(Dispatchers.IO) {
                while (isActive) {
                    TaskLock.tryWithLock(
                        "billing-metered-flush",
                        lockAtMostFor = 5.minutes,
                        lockAtLeastFor = 55.seconds
                    ) {
                        OperationalMetrics.recordTimedBackgroundJobRun("billing-metered-flush") {
                            val flushed = stripeService.flushPendingMeteredUsage()
                            if (flushed > 0) {
                                logger.info { "Flushed pending metered usage for $flushed subscription(s)" }
                            }
                        }
                    }
                    delay(BILLING_JOB_INTERVAL_MS)
                }
            }

        dunningDowngradeJob =
            scope.launch(Dispatchers.IO) {
                while (isActive) {
                    TaskLock.tryWithLock(
                        "billing-dunning-downgrade",
                        lockAtMostFor = 5.minutes,
                        lockAtLeastFor = 55.seconds
                    ) {
                        OperationalMetrics.recordTimedBackgroundJobRun("billing-dunning-downgrade") {
                            stripeService.applyDunningDowngrade()
                        }
                    }
                    delay(BILLING_JOB_INTERVAL_MS)
                }
            }

        quotaNotificationJob =
            scope.launch(Dispatchers.IO) {
                while (isActive) {
                    TaskLock.tryWithLock(
                        "billing-quota-notifications",
                        lockAtMostFor = 5.minutes,
                        lockAtLeastFor = 55.seconds
                    ) {
                        OperationalMetrics.recordTimedBackgroundJobRun("billing-quota-notifications") {
                            processQuotaThresholdNotifications()
                            processWeeklyInsightDigests()
                        }
                    }
                    delay(BILLING_JOB_INTERVAL_MS)
                }
            }
    }

    fun stop() {
        logger.info { "Stopping BillingBackgroundService background jobs" }
        meteredUsageJob?.cancel()
        dunningDowngradeJob?.cancel()
        quotaNotificationJob?.cancel()
    }

    private fun processQuotaThresholdNotifications() {
        if (EnvConfig.SelfHost.enabled) return
        for (orgId in activeBillingOrganizationIds()) {
            val usage = quotaService.getUsageForOrganization(orgId)
            for (notificationType in quotaNotificationTypesFor(usage)) {
                maybeSendNotification(orgId, usage.periodStart, notificationType, usage)
            }
        }
    }

    private fun processWeeklyInsightDigests() {
        if (EnvConfig.SelfHost.enabled) return
        val notificationType = currentWeeklyInsightsNotificationType() ?: return
        for (orgId in activeBillingOrganizationIds()) {
            val usage = quotaService.getUsageForOrganization(orgId)
            maybeSendWeeklyInsightsDigest(orgId, usage.periodStart, notificationType, usage)
        }
    }

    private fun activeBillingOrganizationIds(): List<Int> {
        return transaction {
            Subscriptions
                .selectAll()
                .where {
                    Subscriptions.status inList listOf("active", "trialing", "past_due")
                }.orderBy(Subscriptions.id to SortOrder.DESC)
                .map { it[Subscriptions.organization_id] }
                .distinct()
        }
    }

    private fun quotaNotificationTypesFor(usage: BillingUsageResponse): List<String> {
        val percentages = quotaUsagePercentages(usage)
        return listOfNotNull(
            baseNotificationTypeFor(percentages.base),
            PAYG_WARNING_NOTIFICATION.takeIf {
                hasPaygLimit(usage) && percentages.payg >= QUOTA_WARNING_THRESHOLD
            },
        )
    }

    private fun baseNotificationTypeFor(basePercentage: Double): String? {
        return when {
            basePercentage >= QUOTA_EXCEEDED_THRESHOLD -> BASE_EXCEEDED_NOTIFICATION
            basePercentage >= QUOTA_CRITICAL_THRESHOLD -> BASE_CRITICAL_NOTIFICATION
            basePercentage >= QUOTA_WARNING_THRESHOLD -> BASE_WARNING_NOTIFICATION
            else -> null
        }
    }

    private fun quotaUsagePercentages(usage: BillingUsageResponse): QuotaUsagePercentages {
        val gbEligibleBytes = gbEligibleBytes(usage)
        return QuotaUsagePercentages(
            base = maxOf(baseUnitPercentage(usage), baseBytesPercentage(usage, gbEligibleBytes)),
            payg = maxOf(paygUnitPercentage(usage), paygBytesPercentage(usage, gbEligibleBytes)),
        )
    }

    private fun baseUnitPercentage(usage: BillingUsageResponse): Double {
        return if (usage.baseLimitUnits in 1L until UNLIMITED_UNIT_SENTINEL) {
            usage.usedUnits.toDouble() / usage.baseLimitUnits.toDouble()
        } else {
            0.0
        }
    }

    private fun baseBytesPercentage(usage: BillingUsageResponse, gbEligibleBytes: Long): Double {
        return percentageOrZero(gbEligibleBytes, usage.bytesLimit)
    }

    private fun paygUnitPercentage(usage: BillingUsageResponse): Double {
        val paygUsed = kotlin.math.max(0L, usage.usedUnits - usage.baseLimitUnits)
        return percentageOrZero(paygUsed, usage.paygLimitUnits)
    }

    private fun paygBytesPercentage(usage: BillingUsageResponse, gbEligibleBytes: Long): Double {
        val paygBytes = kotlin.math.max(0L, gbEligibleBytes - usage.bytesLimit)
        return percentageOrZero(paygBytes, usage.paygLimitBytes)
    }

    private fun gbEligibleBytes(usage: BillingUsageResponse): Long {
        return kotlin.math.max(
            0L,
            usage.usedBytes - usage.usedApmSpanBytes - usage.usedInfraMetricBytes
        )
    }

    private fun percentageOrZero(value: Long, limit: Long): Double {
        return if (limit > 0) value.toDouble() / limit.toDouble() else 0.0
    }

    private fun hasPaygLimit(usage: BillingUsageResponse): Boolean {
        return usage.paygLimitUnits > 0 || usage.paygLimitBytes > 0
    }

    private fun maybeSendNotification(
        organizationId: Int,
        periodStart: String,
        notificationType: String,
        usage: BillingUsageResponse
    ) {
        val recipients =
            notificationRecipients(organizationId)
        if (recipients.isEmpty()) return
        if (!reserveNotification(organizationId, periodStart, notificationType)) return

        val orgName = organizationName(organizationId)

        val subject =
            when (notificationType) {
                BASE_WARNING_NOTIFICATION -> "[$orgName] 80% of monthly quota used"
                BASE_CRITICAL_NOTIFICATION -> "[$orgName] 90% of monthly quota used"
                BASE_EXCEEDED_NOTIFICATION -> "[$orgName] Monthly quota exceeded - ingestion may be blocked"
                PAYG_WARNING_NOTIFICATION -> "[$orgName] 80% of PAYG budget consumed"
                else -> "[$orgName] Usage notification"
            }
        val emailData = billingInsightEmailData(
            orgName = orgName,
            usage = usage,
            headline = thresholdHeadline(notificationType),
            summary = thresholdSummary(notificationType, usage)
        )

        var successfulSends = 0
        for (email in recipients) {
            val sendSucceeded = suspendRunCatching {
                emailService.sendBillingThresholdAlertEmail(
                    to = email,
                    subject = subject,
                    data = emailData
                )
            }.onFailure { e ->
                logger.error(e) { "Failed to send quota notification to $email" }
            }.isSuccess
            if (sendSucceeded) {
                successfulSends += 1
            }
        }
        if (successfulSends == 0) {
            releaseNotificationReservation(organizationId, periodStart, notificationType)
        }
    }

    private fun maybeSendWeeklyInsightsDigest(
        organizationId: Int,
        periodStart: String,
        notificationType: String,
        usage: BillingUsageResponse
    ) {
        val recipients = notificationRecipients(organizationId)
        if (recipients.isEmpty()) return
        if (!reserveNotification(organizationId, periodStart, notificationType)) return

        val orgName = organizationName(organizationId)
        val data = billingInsightEmailData(
            orgName = orgName,
            usage = usage,
            headline = "Your Usage Insights digest",
            summary = "A weekly view of billable volume, current limits, and estimated overage for $orgName."
        )

        var successfulSends = 0
        for (email in recipients) {
            val sendSucceeded = suspendRunCatching {
                emailService.sendBillingInsightsEmail(email, data)
            }.onFailure { e ->
                logger.error(e) { "Failed to send billing insights digest to $email" }
            }.isSuccess
            if (sendSucceeded) {
                successfulSends += 1
            }
        }
        if (successfulSends == 0) {
            releaseNotificationReservation(organizationId, periodStart, notificationType)
        }
    }

    private fun reserveNotification(
        organizationId: Int,
        periodStart: String,
        notificationType: String
    ): Boolean {
        val parsedPeriodStart = LocalDate.parse(periodStart)
        return transaction {
            QuotaNotificationsSent
                .insertIgnore {
                    it[QuotaNotificationsSent.organization_id] = organizationId
                    it[this.period_start] = parsedPeriodStart
                    it[this.notification_type] = notificationType
                    it[sent_at] = Clock.System.now()
                }.insertedCount > 0
        }
    }

    private fun releaseNotificationReservation(
        organizationId: Int,
        periodStart: String,
        notificationType: String
    ) {
        val parsedPeriodStart = LocalDate.parse(periodStart)
        transaction {
            QuotaNotificationsSent.deleteWhere {
                (QuotaNotificationsSent.organization_id eq organizationId) and
                    (QuotaNotificationsSent.period_start eq parsedPeriodStart) and
                    (QuotaNotificationsSent.notification_type eq notificationType)
            }
        }
    }

    private fun notificationRecipients(organizationId: Int): List<String> {
        return transaction {
            val ownerIds =
                Memberships
                    .selectAll()
                    .where {
                        (Memberships.organization_id eq organizationId) and (Memberships.role eq "owner")
                    }.map { it[Memberships.user_id] }
            val userIds =
                if (ownerIds.isNotEmpty()) {
                    ownerIds
                } else {
                    Memberships
                        .selectAll()
                        .where { Memberships.organization_id eq organizationId }
                        .map { it[Memberships.user_id] }
                }
            Users
                .selectAll()
                .where { Users.id inList userIds }
                .map { it[Users.email] }
                .distinct()
        }
    }

    private fun organizationName(organizationId: Int): String {
        return transaction {
            Organizations
                .selectAll()
                .where { Organizations.id eq organizationId }
                .firstOrNull()
                ?.get(Organizations.name)
        } ?: "Organization $organizationId"
    }

    private fun currentWeeklyInsightsNotificationType(): String? {
        val today = java.time.LocalDate.now(ZoneOffset.UTC)
        if (today.dayOfWeek != DayOfWeek.MONDAY) return null
        val weekFields = WeekFields.of(Locale.US)
        val week = today[weekFields.weekOfWeekBasedYear()]
        val year = today[weekFields.weekBasedYear()]
        return "${INSIGHTS_WEEKLY_NOTIFICATION_PREFIX}${year}_$week"
    }

    private fun thresholdHeadline(notificationType: String): String {
        return when (notificationType) {
            BASE_WARNING_NOTIFICATION -> "80% of monthly quota used"
            BASE_CRITICAL_NOTIFICATION -> "90% of monthly quota used"
            BASE_EXCEEDED_NOTIFICATION -> "Monthly quota exceeded"
            PAYG_WARNING_NOTIFICATION -> "80% of PAYG budget consumed"
            else -> "Usage notification"
        }
    }

    private fun thresholdSummary(notificationType: String, usage: BillingUsageResponse): String {
        return when (notificationType) {
            BASE_EXCEEDED_NOTIFICATION ->
                "Ingestion may be blocked until capacity is added or the billing period resets."
            PAYG_WARNING_NOTIFICATION ->
                "Your pay-as-you-go budget is approaching its configured cap."
            else ->
                "Usage is approaching the included quota for the current billing period."
        } + " Estimated overage is ${formatCurrency(usage.totalOverageCentsEstimate)}."
    }

    private fun billingInsightEmailData(
        orgName: String,
        usage: BillingUsageResponse,
        headline: String,
        summary: String
    ): EmailService.BillingInsightEmailData {
        return EmailService.BillingInsightEmailData(
            organizationName = orgName,
            plan = usage.plan.uppercase(),
            periodStart = usage.periodStart,
            periodEnd = usage.periodEnd,
            headline = headline,
            summary = summary,
            dashboardUrl = "$frontendUrl/usage-insights",
            settingsUrl = "$frontendUrl/settings?tab=billing",
            rows = billingInsightRows(usage),
            totalOverage = formatCurrency(usage.totalOverageCentsEstimate)
        )
    }

    private fun billingInsightRows(usage: BillingUsageResponse): List<EmailService.BillingInsightRow> {
        return listOf(
            billingInsightRow("GB-billed ingestion", gbEligibleBytes(usage), usage.bytesLimit, ::formatGb),
            billingInsightRow("APM spans", usage.usedApmSpans, usage.apmSpanLimit, ::formatCount),
            billingInsightRow("Custom metrics", usage.usedCustomMetrics, usage.customMetricLimit, ::formatCount),
            billingInsightRow(
                "Infrastructure metrics",
                usage.usedInfraMetricSeriesHours,
                usage.infraMetricSeriesHourLimit,
                ::formatCount
            ),
            billingInsightRow(
                "Analytics pageviews",
                usage.usedAnalyticsPageviews,
                usage.analyticsPageviewLimit,
                ::formatCount
            )
        ).filter { it.used != "0" || it.limit != "Unlimited" }
    }

    private fun billingInsightRow(
        label: String,
        used: Long,
        limit: Long,
        formatValue: (Long) -> String
    ): EmailService.BillingInsightRow {
        val percent = percentageOrZero(used, limit)
        return EmailService.BillingInsightRow(
            label = label,
            used = formatValue(used),
            limit = if (limit > 0 && limit < UNLIMITED_UNIT_SENTINEL) formatValue(limit) else "Unlimited",
            percent = if (limit > 0 && limit < UNLIMITED_UNIT_SENTINEL) {
                "${(percent * 100.0).formatOneDecimal()}%"
            } else {
                "Unlimited"
            },
            status = usageStatus(percent)
        )
    }

    private fun usageStatus(percent: Double): String {
        return when {
            percent >= QUOTA_EXCEEDED_THRESHOLD -> "Over limit"
            percent >= QUOTA_CRITICAL_THRESHOLD -> "Critical"
            percent >= QUOTA_WARNING_THRESHOLD -> "Approaching"
            else -> "On track"
        }
    }

    private fun formatGb(bytes: Long): String {
        return "${(bytes / BYTES_PER_GB).formatTwoDecimals()} GB"
    }

    private fun formatCount(value: Long): String {
        return "%,d".format(Locale.US, value)
    }

    private fun formatCurrency(cents: Int): String {
        return "$${"%.2f".format(Locale.US, cents / CENTS_PER_DOLLAR)}"
    }

    private fun Double.formatOneDecimal(): String = "%.1f".format(Locale.US, this)

    private fun Double.formatTwoDecimals(): String = "%.2f".format(Locale.US, this)

    private data class QuotaUsagePercentages(
        val base: Double,
        val payg: Double,
    )
}
