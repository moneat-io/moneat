// Moneat - Mobile-First Error Monitoring Platform
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

import kotlinx.serialization.Serializable
import org.jetbrains.exposed.sql.Table
import org.jetbrains.exposed.sql.kotlin.datetime.date
import org.jetbrains.exposed.sql.kotlin.datetime.timestamp

object PricingTierConfigs : Table("pricing_tier_configs") {
    val id = integer("id").autoIncrement()
    val tier_name = varchar("tier_name", 50)
    val version = integer("version").default(1)
    val monthly_unit_limit = long("monthly_unit_limit")
    val monthly_error_limit = long("monthly_error_limit").default(0)
    val monthly_transaction_limit = long("monthly_transaction_limit").default(0)
    val monthly_replay_limit = long("monthly_replay_limit").default(0)
    val monthly_feedback_limit = long("monthly_feedback_limit").default(0)
    val monthly_gb_limit = long("monthly_gb_limit").default(0)
    val retention_days = integer("retention_days")
    val log_retention_days = integer("log_retention_days")
    val status_pages_enabled = bool("status_pages_enabled").default(true)
    val status_page_custom_domain_enabled = bool("status_page_custom_domain_enabled").default(true)
    val session_replay_enabled = bool("session_replay_enabled").default(true)
    val slack_enabled = bool("slack_enabled").default(false)
    val discord_enabled = bool("discord_enabled").default(false)
    val incident_io_enabled = bool("incident_io_enabled").default(false)
    val saml_enabled = bool("saml_enabled").default(false)
    val oidc_enabled = bool("oidc_enabled").default(false)
    val priority_support_enabled = bool("priority_support_enabled").default(false)
    val sla_enabled = bool("sla_enabled").default(false)
    val custom_retention_enabled = bool("custom_retention_enabled").default(false)
    val max_projects = integer("max_projects").nullable()
    val max_systems = integer("max_systems")
    val monitor_interval_seconds = integer("monitor_interval_seconds")
    val monthly_price_cents = integer("monthly_price_cents")
    val yearly_price_cents = integer("yearly_price_cents").default(0)
    val trial_days = integer("trial_days").default(14)
    val payg_enabled = bool("payg_enabled").default(false)
    val payg_rate_micros_per_unit = long("payg_rate_micros_per_unit").default(0)
    val overage_rate_cents_per_gb = integer("overage_rate_cents_per_gb").default(0)
    val oncall_per_user_monthly_cents = integer("oncall_per_user_monthly_cents").default(0)
    val oncall_per_user_yearly_cents = integer("oncall_per_user_yearly_cents").default(0)
    val oncall_enabled = bool("oncall_enabled").default(false)
    val stripe_base_price_id = varchar("stripe_base_price_id", 255).nullable()
    val stripe_overage_price_id = varchar("stripe_overage_price_id", 255).nullable()
    val stripe_yearly_base_price_id = varchar("stripe_yearly_base_price_id", 255).nullable()
    val stripe_yearly_overage_price_id = varchar("stripe_yearly_overage_price_id", 255).nullable()
    val stripe_oncall_price_id = varchar("stripe_oncall_price_id", 255).nullable()
    val stripe_oncall_yearly_price_id = varchar("stripe_oncall_yearly_price_id", 255).nullable()
    val is_current = bool("is_current").default(true)
    val created_at = timestamp("created_at").nullable()
    override val primaryKey = PrimaryKey(id)
}

object OrgUsageCounters : Table("org_usage_counters") {
    val id = integer("id").autoIncrement()
    val organization_id = integer("organization_id").references(Organizations.id)
    val period_start = date("period_start")
    val period_end = date("period_end")
    val used_units = long("used_units").default(0)
    val used_errors = long("used_errors").default(0)
    val used_transactions = long("used_transactions").default(0)
    val used_replays = long("used_replays").default(0)
    val used_feedback = long("used_feedback").default(0)
    val used_bytes = long("used_bytes").default(0)
    val updated_at = timestamp("updated_at")
    override val primaryKey = PrimaryKey(id)
}

object QuotaNotificationsSent : Table("quota_notifications_sent") {
    val id = integer("id").autoIncrement()
    val organization_id = integer("organization_id").references(Organizations.id)
    val period_start = date("period_start")
    val notification_type = varchar("notification_type", 50)
    val sent_at = timestamp("sent_at")
    override val primaryKey = PrimaryKey(id)
}

object StripeWebhookEvents : Table("stripe_webhook_events") {
    val id = integer("id").autoIncrement()
    val event_id = varchar("event_id", 255).uniqueIndex()
    val event_type = varchar("event_type", 255)
    val processed_at = timestamp("processed_at")
    val status = varchar("status", 50)
    val error_message = text("error_message").nullable()
    val created_at = timestamp("created_at")
    override val primaryKey = PrimaryKey(id)
}

@Serializable
data class PricingTierConfigResponse(
    val id: Int,
    val tierName: String,
    val version: Int,
    val monthlyUnitLimit: Long,
    val monthlyErrorLimit: Long,
    val monthlyTransactionLimit: Long,
    val monthlyReplayLimit: Long,
    val monthlyFeedbackLimit: Long,
    val monthlyGbLimit: Long,
    val retentionDays: Int,
    val logRetentionDays: Int,
    val statusPagesEnabled: Boolean,
    val statusPageCustomDomainEnabled: Boolean,
    val sessionReplayEnabled: Boolean,
    val slackEnabled: Boolean,
    val discordEnabled: Boolean,
    val incidentIoEnabled: Boolean,
    val samlEnabled: Boolean,
    val oidcEnabled: Boolean,
    val prioritySupportEnabled: Boolean,
    val slaEnabled: Boolean,
    val customRetentionEnabled: Boolean,
    val maxProjects: Int?,
    val maxSystems: Int,
    val monitorIntervalSeconds: Int,
    val monthlyPriceCents: Int,
    val yearlyPriceCents: Int,
    val trialDays: Int,
    val paygEnabled: Boolean,
    val paygRateMicrosPerUnit: Long,
    val overageRateCentsPerGb: Int,
    val oncallPerUserMonthlyCents: Int = 0,
    val oncallPerUserYearlyCents: Int = 0,
    val oncallEnabled: Boolean = false,
    val stripeBasePriceId: String? = null,
    val stripeOveragePriceId: String? = null,
    val stripeYearlyBasePriceId: String? = null,
    val stripeYearlyOveragePriceId: String? = null,
    val stripeOncallPriceId: String? = null,
    val stripeOncallYearlyPriceId: String? = null,
    val isCurrent: Boolean
)

@Serializable
data class BillingPlanResponse(
    val tier: PricingTierConfigResponse,
    val trialDays: Int
)

@Serializable
data class BillingPlansListResponse(
    val plans: List<BillingPlanResponse>,
    val stripeEnabled: Boolean,
    val publishableKey: String?
)

@Serializable
data class BillingUsageResponse(
    val organizationId: Int,
    val periodStart: String,
    val periodEnd: String,
    val retentionDays: Int,
    val usedUnits: Long,
    val usedErrors: Long,
    val errorLimit: Long,
    val usedTransactions: Long,
    val transactionLimit: Long,
    val usedReplays: Long,
    val replayLimit: Long,
    val usedFeedback: Long,
    val feedbackLimit: Long,
    val usedLogs: Long = 0,
    val usedBytes: Long,
    val bytesLimit: Long,
    val baseLimitUnits: Long,
    val paygLimitUnits: Long,
    val totalLimitUnits: Long,
    val paygBudgetCents: Int,
    val paygUsedUnits: Long,
    val paygUsedCentsEstimate: Int,
    val oncallSeats: Int = 0,
    val oncallUsedSeats: Int = 0,
    val oncallPerUserMonthlyCents: Int = 0,
    val oncallEnabled: Boolean = false,
    val plan: String,
    val status: String,
    val withinQuota: Boolean,
    val bonusGbBytes: Long = 0,
    val bonusUnits: Long = 0,
    val bonusReason: String? = null
)

@Serializable
data class CheckoutSessionRequest(
    val tierName: String,
    val billingInterval: String = "monthly",  // "monthly" or "yearly"
    val successUrl: String,
    val cancelUrl: String,
    val oncallSeats: Int = 0
)

@Serializable
data class CheckoutSessionResponse(
    val sessionId: String,
    val url: String
)

@Serializable
data class UpdatePaygBudgetRequest(
    val paygBudgetCents: Int
)

@Serializable
data class UpdatePaygBudgetResponse(
    val paygBudgetCents: Int
)

@Serializable
data class InvoiceResponse(
    val id: String,
    val date: String,
    val amountCents: Int,
    val status: String,
    val pdfUrl: String?
)

@Serializable
data class PaymentMethodResponse(
    val brand: String?,
    val last4: String?,
    val expMonth: Int?,
    val expYear: Int?
)

@Serializable
data class SetupIntentResponse(
    val clientSecret: String
)

@Serializable
data class CancelSubscriptionResponse(
    val status: String,
    val cancelAtPeriodEnd: Boolean,
    val currentPeriodEnd: String?
)

@Serializable
data class CreateTierVersionRequest(
    val monthlyUnitLimit: Long,
    val monthlyErrorLimit: Long = 0,
    val monthlyTransactionLimit: Long = 0,
    val monthlyReplayLimit: Long = 0,
    val monthlyFeedbackLimit: Long = 0,
    val monthlyGbLimit: Long? = null,
    val retentionDays: Int,
    val logRetentionDays: Int? = null,
    val statusPagesEnabled: Boolean? = null,
    val statusPageCustomDomainEnabled: Boolean? = null,
    val sessionReplayEnabled: Boolean? = null,
    val slackEnabled: Boolean? = null,
    val discordEnabled: Boolean? = null,
    val incidentIoEnabled: Boolean? = null,
    val samlEnabled: Boolean? = null,
    val oidcEnabled: Boolean? = null,
    val prioritySupportEnabled: Boolean? = null,
    val slaEnabled: Boolean? = null,
    val customRetentionEnabled: Boolean? = null,
    val maxProjects: Int? = null,
    val maxSystems: Int,
    val monitorIntervalSeconds: Int,
    val monthlyPriceCents: Int,
    val yearlyPriceCents: Int? = null,
    val trialDays: Int? = null,
    val paygEnabled: Boolean,
    val paygRateMicrosPerUnit: Long,
    val overageRateCentsPerGb: Int? = null,
    val oncallPerUserMonthlyCents: Int? = null,
    val oncallPerUserYearlyCents: Int? = null,
    val oncallEnabled: Boolean? = null,
    val stripeBasePriceId: String? = null,
    val stripeOveragePriceId: String? = null,
    val stripeYearlyBasePriceId: String? = null,
    val stripeYearlyOveragePriceId: String? = null,
    val stripeOncallPriceId: String? = null,
    val stripeOncallYearlyPriceId: String? = null
)

@Serializable
data class UpdateStripePriceIdsRequest(
    val stripeBasePriceId: String? = null,
    val stripeOveragePriceId: String? = null,
    val stripeYearlyBasePriceId: String? = null,
    val stripeYearlyOveragePriceId: String? = null,
    val stripeOncallPriceId: String? = null,
    val stripeOncallYearlyPriceId: String? = null
)

@Serializable
data class UpdateOnCallSeatsRequest(
    val seats: Int
)

@Serializable
data class UpdateOnCallSeatsResponse(
    val seats: Int,
    val proratedAmountCents: Int?
)

@Serializable
data class TierMigrationRequest(
    val targetVersion: Int,
    val dryRun: Boolean = true
)

@Serializable
data class TierMigrationResponse(
    val tierName: String,
    val targetVersion: Int,
    val affectedSubscriptions: Int,
    val dryRun: Boolean
)

@Serializable
data class AdminBillingSubscriptionResponse(
    val subscriptionId: Int,
    val organizationId: Int,
    val organizationName: String,
    val plan: String,
    val status: String,
    val pricingTierConfigId: Int?,
    val paygBudgetCents: Int,
    val paygUsedUnits: Long,
    val paygUsedMicros: Long,
    val pendingMeterUnits: Long,
    val currentPeriodStart: String? = null,
    val currentPeriodEnd: String? = null
)

@Serializable
data class GrantPromotionalCreditRequest(
    val bonusGb: Double? = null,  // Bonus in GB (e.g., 5.0 for 5GB)
    val bonusUnits: Long? = null,  // Bonus in event units
    val reason: String
)

@Serializable
data class GrantPromotionalCreditResponse(
    val organizationId: Int,
    val bonusGbBytes: Long,
    val bonusUnits: Long,
    val bonusGb: Double,  // Human-readable GB value
    val reason: String,
    val grantedAt: String
)

@Serializable
data class PromotionalCreditHistoryItem(
    val id: Int,
    val organizationId: Int,
    val organizationName: String,
    val grantedBy: Int,
    val grantedByEmail: String,
    val bonusGb: Double,
    val bonusUnits: Long,
    val reason: String?,
    val grantedAt: String
)

