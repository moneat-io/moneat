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

package com.moneat.services

import com.moneat.models.*
import io.ktor.server.config.*
import kotlinx.coroutines.*
import mu.KotlinLogging
import org.jetbrains.exposed.v1.core.*
import org.jetbrains.exposed.v1.core.SortOrder
import org.jetbrains.exposed.v1.core.and
import org.jetbrains.exposed.v1.jdbc.insertIgnore
import org.jetbrains.exposed.v1.jdbc.selectAll
import org.jetbrains.exposed.v1.jdbc.transactions.transaction
import kotlin.time.Clock

private val logger = KotlinLogging.logger {}

class BillingBackgroundService(
    private val stripeService: StripeService = StripeService(),
    private val quotaService: BillingQuotaService = BillingQuotaService(),
    private val emailService: EmailService = EmailService(),
    private val pricingTierService: PricingTierService = PricingTierService()
) {
    private val config = ApplicationConfig("application.conf")
    private val billingEnabled = config.propertyOrNull("billing.backgroundJobsEnabled")?.getString()?.toBooleanStrictOrNull() ?: true

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
                    try {
                        val flushed = stripeService.flushPendingMeteredUsage()
                        if (flushed > 0) logger.info { "Flushed pending metered usage for $flushed subscription(s)" }
                    } catch (e: Exception) {
                        logger.error(e) { "Metered usage flush job failed" }
                    }
                    delay(60_000L)
                }
            }

        dunningDowngradeJob =
            scope.launch(Dispatchers.IO) {
                while (isActive) {
                    try {
                        stripeService.applyDunningDowngrade()
                    } catch (e: Exception) {
                        logger.error(e) { "Dunning downgrade job failed" }
                    }
                    delay(60_000L)
                }
            }

        quotaNotificationJob =
            scope.launch(Dispatchers.IO) {
                while (isActive) {
                    try {
                        processQuotaThresholdNotifications()
                    } catch (e: Exception) {
                        logger.error(e) { "Quota notification job failed" }
                    }
                    delay(60_000L)
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
        val orgIds =
            transaction {
                Subscriptions
                    .selectAll()
                    .where {
                        Subscriptions.status inList listOf("active", "trialing", "past_due")
                    }.orderBy(Subscriptions.id to SortOrder.DESC)
                    .map { it[Subscriptions.organization_id] }
                    .distinct()
            }

        for (orgId in orgIds) {
            val usage = quotaService.getUsageForOrganization(orgId)
            val periodStart = usage.periodStart
            val basePct = if (usage.baseLimitUnits > 0) (usage.usedUnits.toDouble() / usage.baseLimitUnits.toDouble()) else 0.0
            val paygPct =
                if (usage.paygLimitUnits > 0) {
                    val paygUsed = kotlin.math.max(0L, usage.usedUnits - usage.baseLimitUnits)
                    paygUsed.toDouble() / usage.paygLimitUnits.toDouble()
                } else {
                    0.0
                }

            if (basePct >= 0.8) {
                maybeSendNotification(orgId, periodStart, "base_80", usage)
            }
            if (basePct >= 1.0) {
                maybeSendNotification(orgId, periodStart, "base_100", usage)
            }
            if (usage.paygLimitUnits > 0 && paygPct >= 0.8) {
                maybeSendNotification(orgId, periodStart, "payg_80", usage)
            }
        }
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
                        Memberships.selectAll().where { Memberships.organization_id eq organizationId }.map { it[Memberships.user_id] }
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
                "base_80" -> "[$orgName] 80% of monthly quota used"
                "base_100" -> "[$orgName] Base quota reached"
                "payg_80" -> "[$orgName] 80% of PAYG budget consumed"
                else -> "[$orgName] Usage notification"
            }
        val body =
            buildString {
                appendLine("Billing usage alert for $orgName")
                appendLine()
                appendLine("Plan: ${usage.plan}")
                appendLine("Usage: ${usage.usedUnits}/${usage.totalLimitUnits} units")
                appendLine("Base limit: ${usage.baseLimitUnits} units")
                appendLine("PAYG budget: $${"%.2f".format(usage.paygBudgetCents / 100.0)}")
                appendLine("PAYG used estimate: $${"%.2f".format(usage.paygUsedCentsEstimate / 100.0)}")
                appendLine("Billing period: ${usage.periodStart} to ${usage.periodEnd}")
            }

        for (email in recipients) {
            try {
                emailService.sendEmail(
                    to = email,
                    subject = subject,
                    htmlBody = "<pre>$body</pre>",
                    textBody = body,
                    emailType = "quota_notification"
                )
            } catch (e: Exception) {
                logger.error(e) { "Failed to send quota notification to $email" }
            }
        }
    }
}
