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
import mu.KotlinLogging
import org.jetbrains.exposed.v1.core.SortOrder
import org.jetbrains.exposed.v1.core.and
import org.jetbrains.exposed.v1.core.eq
import org.jetbrains.exposed.v1.core.inList
import org.jetbrains.exposed.v1.jdbc.insertIgnore
import org.jetbrains.exposed.v1.jdbc.selectAll
import org.jetbrains.exposed.v1.jdbc.transactions.transaction
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
private const val NANOS_PER_SECOND = 1_000_000_000.0

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
                        recordBackgroundJobRun("billing-metered-flush") {
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
                        recordBackgroundJobRun("billing-dunning-downgrade") {
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
                        recordBackgroundJobRun("billing-quota-notifications") {
                            processQuotaThresholdNotifications()
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
        for (orgId in activeBillingOrganizationIds()) {
            val usage = quotaService.getUsageForOrganization(orgId)
            for (notificationType in quotaNotificationTypesFor(usage)) {
                maybeSendNotification(orgId, usage.periodStart, notificationType, usage)
            }
        }
    }

    private suspend fun recordBackgroundJobRun(jobName: String, block: suspend () -> Unit) {
        val startedAt = System.nanoTime()
        suspendRunCatching {
            block()
        }.fold(
            onSuccess = {
                OperationalMetrics.recordBackgroundJobRun(
                    jobName,
                    success = true,
                    elapsedSecondsSince(startedAt)
                )
            },
            onFailure = { cause ->
                OperationalMetrics.recordBackgroundJobRun(
                    jobName,
                    success = false,
                    elapsedSecondsSince(startedAt),
                    cause
                )
                throw cause
            }
        )
    }

    private fun elapsedSecondsSince(startedAt: Long): Double =
        (System.nanoTime() - startedAt).toDouble() / NANOS_PER_SECOND

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
        val inserted =
            transaction {
                QuotaNotificationsSent
                    .insertIgnore {
                        it[QuotaNotificationsSent.organization_id] = organizationId
                        it[this.period_start] = kotlinx.datetime.LocalDate.parse(periodStart)
                        it[this.notification_type] = notificationType
                        it[sent_at] = Clock.System.now()
                    }.insertedCount
            }
        if (inserted == 0) return

        val recipients =
            transaction {
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
        if (recipients.isEmpty()) return

        val orgName =
            transaction {
                Organizations
                    .selectAll()
                    .where { Organizations.id eq organizationId }
                    .firstOrNull()
                    ?.get(Organizations.name)
            } ?: "Organization $organizationId"

        val subject =
            when (notificationType) {
                BASE_WARNING_NOTIFICATION -> "[$orgName] 80% of monthly quota used"
                BASE_CRITICAL_NOTIFICATION -> "[$orgName] 90% of monthly quota used"
                BASE_EXCEEDED_NOTIFICATION -> "[$orgName] Monthly quota exceeded — ingestion may be blocked"
                PAYG_WARNING_NOTIFICATION -> "[$orgName] 80% of PAYG budget consumed"
                else -> "[$orgName] Usage notification"
            }
        val body =
            buildString {
                appendLine("Billing usage alert for $orgName")
                appendLine()
                appendLine("Plan: ${usage.plan}")
                if (usage.baseLimitUnits in 1L until UNLIMITED_UNIT_SENTINEL) {
                    appendLine("Usage: ${usage.usedUnits}/${usage.totalLimitUnits} units")
                    appendLine("Base limit: ${usage.baseLimitUnits} units")
                }
                if (usage.bytesLimit > 0) {
                    val eligible = gbEligibleBytes(usage)
                    val usedGb = "%.2f".format(eligible / BYTES_PER_GB)
                    val limitGb = "%.2f".format(usage.bytesLimit / BYTES_PER_GB)
                    appendLine("Ingestion: $usedGb / $limitGb GB")
                }
                appendLine("PAYG budget: $${"%.2f".format(usage.paygBudgetCents / CENTS_PER_DOLLAR)}")
                appendLine("PAYG used estimate: $${"%.2f".format(usage.paygUsedCentsEstimate / CENTS_PER_DOLLAR)}")
                appendLine("Billing period: ${usage.periodStart} to ${usage.periodEnd}")
            }

        for (email in recipients) {
            suspendRunCatching {
                emailService.sendEmail(
                    to = email,
                    subject = subject,
                    htmlBody = "<pre>$body</pre>",
                    textBody = body,
                    emailType = "quota_notification"
                )
            }.getOrElse { e ->
                logger.error(e) { "Failed to send quota notification to $email" }
            }
        }
    }

    private data class QuotaUsagePercentages(
        val base: Double,
        val payg: Double,
    )
}
