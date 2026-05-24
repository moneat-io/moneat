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

import com.moneat.billing.models.AdminBillingSubscriptionResponse
import com.moneat.billing.models.BillingPlanResponse
import com.moneat.billing.models.CreateTierVersionRequest
import com.moneat.billing.models.PricingTier
import com.moneat.billing.models.PricingTierConfigResponse
import com.moneat.billing.models.PricingTierConfigs
import com.moneat.billing.models.TierMigrationRequest
import com.moneat.billing.models.TierMigrationResponse
import com.moneat.billing.models.UpdateStripePriceIdsRequest
import com.moneat.shared.models.Memberships
import com.moneat.shared.models.Organizations
import com.moneat.shared.models.Subscriptions
import kotlinx.datetime.TimeZone
import kotlinx.datetime.toLocalDateTime
import mu.KotlinLogging
import org.jetbrains.exposed.v1.core.ResultRow
import org.jetbrains.exposed.v1.core.SortOrder
import org.jetbrains.exposed.v1.core.and
import org.jetbrains.exposed.v1.core.eq
import org.jetbrains.exposed.v1.core.inList
import org.jetbrains.exposed.v1.jdbc.insert
import org.jetbrains.exposed.v1.jdbc.selectAll
import org.jetbrains.exposed.v1.jdbc.transactions.transaction
import org.jetbrains.exposed.v1.jdbc.update
import kotlin.time.Instant

private val logger = KotlinLogging.logger {}

data class EffectiveTierContext(
    val tier: PricingTierConfigResponse,
    val subscriptionId: Int?,
    val subscriptionStatus: String,
    val paygBudgetCents: Int,
    val paygUsedUnits: Long,
    val paygUsedMicros: Long,
    val pendingMeterUnits: Long,
    val currentPeriodStart: kotlinx.datetime.LocalDate?,
    val currentPeriodEnd: kotlinx.datetime.LocalDate?
)

private const val DEFAULT_ANALYTICS_RETENTION_DAYS = 1095
private const val MAX_RETENTION_DAYS = 90
private const val MAX_TRIAL_DAYS = 60
private const val MAX_ANALYTICS_RETENTION_DAYS = 3650
private const val PRO_MONTHLY_PRICE_CENTS = 2900
private const val TEAM_MONTHLY_PRICE_CENTS = 7900
private const val BUSINESS_MONTHLY_PRICE_CENTS = 19900
private const val PRO_YEARLY_PRICE_CENTS = 28800
private const val TEAM_YEARLY_PRICE_CENTS = 79200
private const val BUSINESS_YEARLY_PRICE_CENTS = 199200
private const val DEFAULT_TRIAL_DAYS_NON_FREE = 14
private const val DEFAULT_INFRA_METRIC_OVERAGE_RATE_CENTS_PER_100K = 10
private const val FREE_INFRA_METRIC_SERIES_HOUR_LIMIT = 5_000_000L
private const val PRO_INFRA_METRIC_SERIES_HOUR_LIMIT = 50_000_000L
private const val TEAM_INFRA_METRIC_SERIES_HOUR_LIMIT = 250_000_000L

class PricingTierService {
    private data class DefaultFeatureFlags(
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
        val customRetentionEnabled: Boolean
    )

    fun getPrimaryOrganizationIdForUser(userId: Int): Int? {
        return transaction {
            Memberships
                .selectAll()
                .where { Memberships.user_id eq userId }
                .orderBy(Memberships.id to SortOrder.ASC)
                .firstOrNull()
                ?.get(Memberships.organization_id)
        }
    }

    fun getCurrentPlans(): List<BillingPlanResponse> {
        return transaction {
            PricingTierConfigs
                .selectAll()
                .where { PricingTierConfigs.is_current eq true }
                .orderBy(PricingTierConfigs.monthly_price_cents to SortOrder.ASC)
                .map { row ->
                    val tier = rowToResponse(row)
                    BillingPlanResponse(tier = tier, trialDays = tier.trialDays)
                }
        }
    }

    fun getTierVersions(tierName: String): List<PricingTierConfigResponse> {
        return transaction {
            PricingTierConfigs
                .selectAll()
                .where { PricingTierConfigs.tier_name eq tierName.uppercase() }
                .orderBy(PricingTierConfigs.version to SortOrder.DESC)
                .map { rowToResponse(it) }
        }
    }

    fun getCurrentTier(tierName: String): PricingTierConfigResponse? {
        return transaction {
            PricingTierConfigs
                .selectAll()
                .where {
                    (PricingTierConfigs.tier_name eq tierName.uppercase()) and
                        (PricingTierConfigs.is_current eq true)
                }.firstOrNull()
                ?.let { rowToResponse(it) }
        }
    }

    fun getTierById(id: Int): PricingTierConfigResponse? {
        return transaction {
            PricingTierConfigs
                .selectAll()
                .where { PricingTierConfigs.id eq id }
                .firstOrNull()
                ?.let { rowToResponse(it) }
        }
    }

    fun getEffectiveTierForOrganization(organizationId: Int): EffectiveTierContext {
        return transaction {
            val subscription =
                Subscriptions
                    .selectAll()
                    .where {
                        (Subscriptions.organization_id eq organizationId) and
                            (Subscriptions.status inList listOf("active", "trialing", "past_due"))
                    }.orderBy(Subscriptions.id to SortOrder.DESC)
                    .firstOrNull()

            if (subscription == null) {
                val free = currentFallbackTier("FREE")
                return@transaction EffectiveTierContext(
                    tier = free,
                    subscriptionId = null,
                    subscriptionStatus = "active",
                    paygBudgetCents = 0,
                    paygUsedUnits = 0,
                    paygUsedMicros = 0,
                    pendingMeterUnits = 0,
                    currentPeriodStart = null,
                    currentPeriodEnd = null
                )
            }

            val byId =
                subscription[Subscriptions.pricing_tier_config_id]?.let { id ->
                    PricingTierConfigs.selectAll().where { PricingTierConfigs.id eq id }.firstOrNull()
                }

            val tierRow =
                byId
                    ?: PricingTierConfigs
                        .selectAll()
                        .where {
                            (PricingTierConfigs.is_current eq true) and
                                (PricingTierConfigs.tier_name eq subscription[Subscriptions.plan].uppercase())
                        }.firstOrNull()
                    ?: PricingTierConfigs
                        .selectAll()
                        .where {
                            (PricingTierConfigs.is_current eq true) and
                                (PricingTierConfigs.tier_name eq "FREE")
                        }.firstOrNull()

            val tier =
                if (tierRow != null) {
                    rowToResponse(tierRow)
                } else {
                    enumFallbackToResponse(subscription[Subscriptions.plan])
                }

            EffectiveTierContext(
                tier = tier,
                subscriptionId = subscription[Subscriptions.id],
                subscriptionStatus = subscription[Subscriptions.status],
                paygBudgetCents = subscription[Subscriptions.payg_budget_cents],
                paygUsedUnits = subscription[Subscriptions.payg_used_units],
                paygUsedMicros = subscription[Subscriptions.payg_used_micros],
                pendingMeterUnits = subscription[Subscriptions.pending_meter_units],
                currentPeriodStart = subscription[Subscriptions.current_period_start]?.toLocalDateUtc(),
                currentPeriodEnd = subscription[Subscriptions.current_period_end]?.toLocalDateUtc()
            )
        }
    }

    fun createTierVersion(
        tierName: String,
        request: CreateTierVersionRequest
    ): PricingTierConfigResponse {
        val canonicalName = tierName.uppercase()
        validateCreateTierRequest(request)
        val monthlyErrorLimit =
            if (request.monthlyErrorLimit > 0) request.monthlyErrorLimit else request.monthlyUnitLimit
        val monthlyTransactionLimit = request.monthlyTransactionLimit
        val monthlyReplayLimit = request.monthlyReplayLimit
        val monthlyFeedbackLimit = request.monthlyFeedbackLimit
        val monthlyUnitLimit = monthlyErrorLimit + monthlyTransactionLimit + monthlyReplayLimit + monthlyFeedbackLimit
        return transaction {
            val current =
                PricingTierConfigs
                    .selectAll()
                    .where { PricingTierConfigs.tier_name eq canonicalName }
                    .orderBy(PricingTierConfigs.version to SortOrder.DESC)
                    .firstOrNull()
            val currentConfig = current?.let { rowToResponse(it) }
            val defaults = defaultFeatureFlagsForTier(canonicalName)
            val nextVersion = (current?.get(PricingTierConfigs.version) ?: 0) + 1

            val resolvedMonthlyGbLimit = request.monthlyGbLimit ?: currentConfig?.monthlyGbLimit ?: 0
            val resolvedLogRetentionDays =
                request.logRetentionDays ?: currentConfig?.logRetentionDays ?: request.retentionDays
            val resolvedYearlyPriceCents = request.yearlyPriceCents ?: currentConfig?.yearlyPriceCents ?: 0
            val resolvedTrialDays =
                request.trialDays ?: currentConfig?.trialDays ?: defaultTrialDaysForTier(canonicalName)
            val resolvedOverageRateCentsPerGb =
                request.overageRateCentsPerGb ?: currentConfig?.overageRateCentsPerGb ?: 0
            val resolvedReplayRetentionDays =
                request.replayRetentionDays ?: currentConfig?.replayRetentionDays ?: request.retentionDays
            val resolvedLlmRetentionDays =
                request.llmRetentionDays ?: currentConfig?.llmRetentionDays ?: request.retentionDays
            val resolvedErrorOverageRateCentsPer1k =
                request.errorOverageRateCentsPer1k ?: currentConfig?.errorOverageRateCentsPer1k ?: 0
            val resolvedReplayOverageRateCentsPerGb =
                request.replayOverageRateCentsPerGb ?: currentConfig?.replayOverageRateCentsPerGb ?: 0
            val resolvedLlmOverageRateCentsPer1k =
                request.llmOverageRateCentsPer1k ?: currentConfig?.llmOverageRateCentsPer1k ?: 0
            val resolvedOncallPerUserMonthlyCents =
                request.oncallPerUserMonthlyCents ?: currentConfig?.oncallPerUserMonthlyCents ?: 0
            val resolvedOncallPerUserYearlyCents =
                request.oncallPerUserYearlyCents ?: currentConfig?.oncallPerUserYearlyCents ?: 0
            val resolvedOncallEnabled = request.oncallEnabled ?: currentConfig?.oncallEnabled ?: false
            val resolvedMaxAnalyticsSites = request.maxAnalyticsSites
            val resolvedAnalyticsRetentionDays =
                request.analyticsRetentionDays
                    ?: currentConfig?.analyticsRetentionDays
                    ?: DEFAULT_ANALYTICS_RETENTION_DAYS
            val resolvedMonthlyAnalyticsPageviewLimit =
                request.monthlyAnalyticsPageviewLimit ?: currentConfig?.monthlyAnalyticsPageviewLimit ?: 0
            val resolvedAnalyticsPageviewOverageRateCentsPer100k =
                request.analyticsPageviewOverageRateCentsPer100k
                    ?: currentConfig?.analyticsPageviewOverageRateCentsPer100k ?: 0
            val resolvedMonthlyApmSpanLimit = request.monthlyApmSpanLimit
                ?: currentConfig?.monthlyApmSpanLimit ?: 0
            val resolvedApmSpanOverageRateCentsPer1m = request.apmSpanOverageRateCentsPer1m
                ?: currentConfig?.apmSpanOverageRateCentsPer1m ?: 0
            val resolvedMonthlyCustomMetricLimit = request.monthlyCustomMetricLimit
                ?: currentConfig?.monthlyCustomMetricLimit ?: 0
            val resolvedCustomMetricOverageRateCentsPer100k =
                request.customMetricOverageRateCentsPer100k
                    ?: currentConfig?.customMetricOverageRateCentsPer100k ?: 0
            val resolvedMonthlyInfraMetricSeriesHourLimit =
                request.monthlyInfraMetricSeriesHourLimit
                    ?: currentConfig?.monthlyInfraMetricSeriesHourLimit
                    ?: defaultMonthlyInfraMetricSeriesHourLimit(canonicalName)
            val resolvedInfraMetricOverageRateCentsPer100k =
                request.infraMetricOverageRateCentsPer100kSeriesHours
                    ?: currentConfig?.infraMetricOverageRateCentsPer100kSeriesHours
                    ?: defaultInfraMetricOverageRateCentsPer100k(canonicalName)
            val resolvedMaxHosts = request.maxHosts
            val resolvedProfilingEnabled = request.profilingEnabled
                ?: currentConfig?.profilingEnabled ?: true
            val resolvedNetworkMonitoringEnabled = request.networkMonitoringEnabled
                ?: currentConfig?.networkMonitoringEnabled ?: true
            val resolvedDbmEnabled = request.dbmEnabled
                ?: currentConfig?.dbmEnabled ?: true
            val resolvedDebuggerEnabled = request.debuggerEnabled
                ?: currentConfig?.debuggerEnabled ?: true
            val resolvedK8sMonitoringEnabled = request.k8sMonitoringEnabled
                ?: currentConfig?.k8sMonitoringEnabled ?: true
            val resolvedDataStreamsEnabled = request.dataStreamsEnabled
                ?: currentConfig?.dataStreamsEnabled ?: true
            val resolvedSbomEnabled = request.sbomEnabled
                ?: currentConfig?.sbomEnabled ?: true
            val resolvedSyntheticsEnabled = request.syntheticsEnabled
                ?: currentConfig?.syntheticsEnabled ?: true

            val resolvedStatusPagesEnabled =
                request.statusPagesEnabled
                    ?: currentConfig?.statusPagesEnabled
                    ?: defaults.statusPagesEnabled
            val resolvedStatusPageCustomDomainEnabled =
                request.statusPageCustomDomainEnabled
                    ?: currentConfig?.statusPageCustomDomainEnabled
                    ?: defaults.statusPageCustomDomainEnabled
            val resolvedSessionReplayEnabled =
                request.sessionReplayEnabled
                    ?: currentConfig?.sessionReplayEnabled
                    ?: defaults.sessionReplayEnabled
            val resolvedSlackEnabled =
                request.slackEnabled
                    ?: currentConfig?.slackEnabled
                    ?: defaults.slackEnabled
            val resolvedDiscordEnabled =
                request.discordEnabled
                    ?: currentConfig?.discordEnabled
                    ?: defaults.discordEnabled
            val resolvedIncidentIoEnabled =
                request.incidentIoEnabled
                    ?: currentConfig?.incidentIoEnabled
                    ?: defaults.incidentIoEnabled
            val resolvedSamlEnabled =
                request.samlEnabled
                    ?: currentConfig?.samlEnabled
                    ?: defaults.samlEnabled
            val resolvedOidcEnabled =
                request.oidcEnabled
                    ?: currentConfig?.oidcEnabled
                    ?: defaults.oidcEnabled
            val resolvedPrioritySupportEnabled =
                request.prioritySupportEnabled
                    ?: currentConfig?.prioritySupportEnabled
                    ?: defaults.prioritySupportEnabled
            val resolvedSlaEnabled =
                request.slaEnabled
                    ?: currentConfig?.slaEnabled
                    ?: defaults.slaEnabled
            val resolvedCustomRetentionEnabled =
                request.customRetentionEnabled
                    ?: currentConfig?.customRetentionEnabled
                    ?: defaults.customRetentionEnabled

            PricingTierConfigs.update({ PricingTierConfigs.tier_name eq canonicalName }) {
                it[is_current] = false
            }

            val id =
                PricingTierConfigs.insert {
                    it[PricingTierConfigs.tier_name] = canonicalName
                    it[version] = nextVersion
                    it[monthly_unit_limit] = monthlyUnitLimit
                    it[monthly_error_limit] = monthlyErrorLimit
                    it[monthly_transaction_limit] = monthlyTransactionLimit
                    it[monthly_replay_limit] = monthlyReplayLimit
                    it[monthly_feedback_limit] = monthlyFeedbackLimit
                    it[monthly_llm_event_limit] = request.monthlyLlmEventLimit
                    it[monthly_gb_limit] = resolvedMonthlyGbLimit
                    it[retention_days] = request.retentionDays
                    it[log_retention_days] = resolvedLogRetentionDays
                    it[replay_retention_days] = resolvedReplayRetentionDays
                    it[llm_retention_days] = resolvedLlmRetentionDays
                    it[status_pages_enabled] = resolvedStatusPagesEnabled
                    it[status_page_custom_domain_enabled] = resolvedStatusPageCustomDomainEnabled
                    it[session_replay_enabled] = resolvedSessionReplayEnabled
                    it[slack_enabled] = resolvedSlackEnabled
                    it[discord_enabled] = resolvedDiscordEnabled
                    it[incident_io_enabled] = resolvedIncidentIoEnabled
                    it[saml_enabled] = resolvedSamlEnabled
                    it[oidc_enabled] = resolvedOidcEnabled
                    it[priority_support_enabled] = resolvedPrioritySupportEnabled
                    it[sla_enabled] = resolvedSlaEnabled
                    it[custom_retention_enabled] = resolvedCustomRetentionEnabled
                    it[max_projects] = request.maxProjects
                    it[max_systems] = request.maxSystems
                    it[monitor_interval_seconds] = request.monitorIntervalSeconds
                    it[monthly_price_cents] = request.monthlyPriceCents
                    it[yearly_price_cents] = resolvedYearlyPriceCents
                    it[trial_days] = resolvedTrialDays
                    it[payg_enabled] = request.paygEnabled
                    it[payg_rate_micros_per_unit] = request.paygRateMicrosPerUnit
                    it[overage_rate_cents_per_gb] = resolvedOverageRateCentsPerGb
                    it[error_overage_rate_cents_per_1k] = resolvedErrorOverageRateCentsPer1k
                    it[replay_overage_rate_cents_per_gb] = resolvedReplayOverageRateCentsPerGb
                    it[llm_overage_rate_cents_per_1k] = resolvedLlmOverageRateCentsPer1k
                    it[oncall_per_user_monthly_cents] = resolvedOncallPerUserMonthlyCents
                    it[oncall_per_user_yearly_cents] = resolvedOncallPerUserYearlyCents
                    it[oncall_enabled] = resolvedOncallEnabled
                    it[max_analytics_sites] = resolvedMaxAnalyticsSites
                    it[analytics_retention_days] = resolvedAnalyticsRetentionDays
                    it[monthly_analytics_pageview_limit] = resolvedMonthlyAnalyticsPageviewLimit
                    it[analytics_pageview_overage_rate_cents_per_100k] =
                        resolvedAnalyticsPageviewOverageRateCentsPer100k
                    it[monthly_apm_span_limit] = resolvedMonthlyApmSpanLimit
                    it[apm_span_overage_rate_cents_per_1m] = resolvedApmSpanOverageRateCentsPer1m
                    it[monthly_custom_metric_limit] = resolvedMonthlyCustomMetricLimit
                    it[custom_metric_overage_rate_cents_per_100k] = resolvedCustomMetricOverageRateCentsPer100k
                    it[monthly_infra_metric_series_hour_limit] = resolvedMonthlyInfraMetricSeriesHourLimit
                    it[infra_metric_overage_rate_cents_per_100k_series_hours] =
                        resolvedInfraMetricOverageRateCentsPer100k
                    it[max_hosts] = resolvedMaxHosts
                    it[profiling_enabled] = resolvedProfilingEnabled
                    it[network_monitoring_enabled] = resolvedNetworkMonitoringEnabled
                    it[dbm_enabled] = resolvedDbmEnabled
                    it[debugger_enabled] = resolvedDebuggerEnabled
                    it[k8s_monitoring_enabled] = resolvedK8sMonitoringEnabled
                    it[data_streams_enabled] = resolvedDataStreamsEnabled
                    it[sbom_enabled] = resolvedSbomEnabled
                    it[synthetics_enabled] = resolvedSyntheticsEnabled
                    it[stripe_base_price_id] = request.stripeBasePriceId
                    it[stripe_overage_price_id] = request.stripeOveragePriceId
                    it[stripe_yearly_base_price_id] = request.stripeYearlyBasePriceId
                    it[stripe_yearly_overage_price_id] = request.stripeYearlyOveragePriceId
                    it[stripe_oncall_price_id] = request.stripeOncallPriceId
                    it[stripe_oncall_yearly_price_id] = request.stripeOncallYearlyPriceId
                    it[is_current] = true
                } get PricingTierConfigs.id

            rowToResponse(PricingTierConfigs.selectAll().where { PricingTierConfigs.id eq id }.first())
        }
    }

    private fun validateCreateTierRequest(request: CreateTierVersionRequest) {
        require(request.retentionDays in 1..MAX_RETENTION_DAYS) { "Retention days must be between 1 and 90" }
        if (request.logRetentionDays != null) {
            require(request.logRetentionDays in 1..MAX_RETENTION_DAYS) { "Log retention days must be between 1 and 90" }
        }
        require(request.monthlyUnitLimit >= 0) { "Monthly unit limit must be non-negative" }
        require(request.monthlyErrorLimit >= 0) { "Monthly error limit must be non-negative" }
        require(request.monthlyTransactionLimit >= 0) { "Monthly transaction limit must be non-negative" }
        require(request.monthlyReplayLimit >= -1) { "Monthly replay limit must be -1 (unlimited) or non-negative" }
        require(request.monthlyFeedbackLimit >= 0) { "Monthly feedback limit must be non-negative" }
        require(request.monthlyLlmEventLimit >= 0) { "Monthly LLM event limit must be non-negative" }
        if (request.replayRetentionDays != null) {
            require(request.replayRetentionDays in 1..MAX_RETENTION_DAYS) {
                "Replay retention days must be between 1 and 90"
            }
        }
        if (request.llmRetentionDays != null) {
            require(request.llmRetentionDays in 1..MAX_RETENTION_DAYS) { "LLM retention days must be between 1 and 90" }
        }
        if (request.monthlyGbLimit != null) {
            require(request.monthlyGbLimit >= 0) { "Monthly GB limit must be non-negative" }
        }
        if (request.yearlyPriceCents != null) {
            require(request.yearlyPriceCents >= 0) { "Yearly price cannot be negative" }
        }
        if (request.trialDays != null) {
            require(request.trialDays in 0..MAX_TRIAL_DAYS) { "Trial days must be between 0 and 60" }
        }
        if (request.overageRateCentsPerGb != null) {
            require(request.overageRateCentsPerGb >= 0) { "Overage rate cannot be negative" }
        }
        if (request.errorOverageRateCentsPer1k != null) {
            require(request.errorOverageRateCentsPer1k >= 0) { "Error overage rate cannot be negative" }
        }
        if (request.replayOverageRateCentsPerGb != null) {
            require(request.replayOverageRateCentsPerGb >= 0) { "Replay overage rate cannot be negative" }
        }
        if (request.llmOverageRateCentsPer1k != null) {
            require(request.llmOverageRateCentsPer1k >= 0) { "LLM overage rate cannot be negative" }
        }
        if (request.analyticsRetentionDays != null) {
            require(request.analyticsRetentionDays in 1..MAX_ANALYTICS_RETENTION_DAYS) {
                "Analytics retention days must be between 1 and 3650"
            }
        }
        if (request.monthlyAnalyticsPageviewLimit != null) {
            require(
                request.monthlyAnalyticsPageviewLimit >= 0
            ) { "Monthly analytics pageview limit must be non-negative" }
        }
        if (request.analyticsPageviewOverageRateCentsPer100k != null) {
            require(
                request.analyticsPageviewOverageRateCentsPer100k >= 0
            ) { "Analytics pageview overage rate cannot be negative" }
        }
        if (request.monthlyInfraMetricSeriesHourLimit != null) {
            require(request.monthlyInfraMetricSeriesHourLimit >= 0) {
                "Monthly infrastructure metric limit must be non-negative"
            }
        }
        if (request.infraMetricOverageRateCentsPer100kSeriesHours != null) {
            require(request.infraMetricOverageRateCentsPer100kSeriesHours >= 0) {
                "Infrastructure metric overage rate cannot be negative"
            }
        }
    }

    fun migrateSubscribers(
        tierName: String,
        request: TierMigrationRequest
    ): TierMigrationResponse {
        val canonicalName = tierName.uppercase()
        return transaction {
            val targetTierId =
                PricingTierConfigs
                    .selectAll()
                    .where {
                        (PricingTierConfigs.tier_name eq canonicalName) and
                            (PricingTierConfigs.version eq request.targetVersion)
                    }.firstOrNull()
                    ?.get(PricingTierConfigs.id)
                    ?: throw IllegalArgumentException("Target tier version not found")

            val candidateIds =
                Subscriptions
                    .selectAll()
                    .where {
                        Subscriptions.status inList listOf("active", "trialing", "past_due")
                    }.filter { row ->
                        val configId = row[Subscriptions.pricing_tier_config_id]
                        if (configId == null) {
                            row[Subscriptions.plan].equals(canonicalName, ignoreCase = true)
                        } else {
                            PricingTierConfigs
                                .selectAll()
                                .where { PricingTierConfigs.id eq configId }
                                .firstOrNull()
                                ?.get(PricingTierConfigs.tier_name)
                                ?.equals(canonicalName, ignoreCase = true) == true
                        }
                    }.map { it[Subscriptions.id] }

            if (!request.dryRun && candidateIds.isNotEmpty()) {
                Subscriptions.update({ Subscriptions.id inList candidateIds }) {
                    it[pricing_tier_config_id] = targetTierId
                    it[plan] = canonicalName.lowercase()
                }
            }

            TierMigrationResponse(
                tierName = canonicalName,
                targetVersion = request.targetVersion,
                affectedSubscriptions = candidateIds.size,
                dryRun = request.dryRun
            )
        }
    }

    fun updateStripePriceIds(
        tierName: String,
        version: Int,
        request: UpdateStripePriceIdsRequest
    ): PricingTierConfigResponse {
        val canonicalName = tierName.uppercase()
        return transaction {
            val tier =
                PricingTierConfigs
                    .selectAll()
                    .where {
                        (PricingTierConfigs.tier_name eq canonicalName) and
                            (PricingTierConfigs.version eq version)
                    }.firstOrNull()
                    ?: throw IllegalArgumentException("Tier version not found: $canonicalName v$version")

            val tierId = tier[PricingTierConfigs.id]

            PricingTierConfigs.update({ PricingTierConfigs.id eq tierId }) {
                it[stripe_base_price_id] = request.stripeBasePriceId
                it[stripe_overage_price_id] = request.stripeOveragePriceId
                it[stripe_yearly_base_price_id] = request.stripeYearlyBasePriceId
                it[stripe_yearly_overage_price_id] = request.stripeYearlyOveragePriceId
                it[stripe_oncall_price_id] = request.stripeOncallPriceId
                it[stripe_oncall_yearly_price_id] = request.stripeOncallYearlyPriceId
            }

            logger.info { "Updated Stripe price IDs for $canonicalName v$version" }

            rowToResponse(PricingTierConfigs.selectAll().where { PricingTierConfigs.id eq tierId }.first())
        }
    }

    fun listAdminSubscriptions(limit: Int = 500): List<AdminBillingSubscriptionResponse> {
        return transaction {
            (Subscriptions innerJoin Organizations)
                .selectAll()
                .orderBy(Subscriptions.id to SortOrder.DESC)
                .limit(limit)
                .map { row ->
                    AdminBillingSubscriptionResponse(
                        subscriptionId = row[Subscriptions.id],
                        organizationId = row[Subscriptions.organization_id],
                        organizationName = row[Organizations.name],
                        plan = row[Subscriptions.plan],
                        status = row[Subscriptions.status],
                        pricingTierConfigId = row[Subscriptions.pricing_tier_config_id],
                        paygBudgetCents = row[Subscriptions.payg_budget_cents],
                        paygUsedUnits = row[Subscriptions.payg_used_units],
                        paygUsedMicros = row[Subscriptions.payg_used_micros],
                        pendingMeterUnits = row[Subscriptions.pending_meter_units],
                        currentPeriodStart = row[Subscriptions.current_period_start]?.toString(),
                        currentPeriodEnd = row[Subscriptions.current_period_end]?.toString()
                    )
                }.toList()
        }
    }

    private fun currentFallbackTier(tierName: String): PricingTierConfigResponse {
        val row =
            PricingTierConfigs
                .selectAll()
                .where {
                    (PricingTierConfigs.tier_name eq tierName.uppercase()) and
                        (PricingTierConfigs.is_current eq true)
                }.firstOrNull()
        if (row != null) return rowToResponse(row)
        return enumFallbackToResponse(tierName)
    }

    private fun enumFallbackToResponse(tierName: String): PricingTierConfigResponse {
        val tier = PricingTier.entries.find { it.name.equals(tierName, ignoreCase = true) } ?: PricingTier.FREE
        val monthlyPrice =
            when (tier) {
                PricingTier.FREE -> 0
                PricingTier.PRO -> PRO_MONTHLY_PRICE_CENTS
                PricingTier.TEAM -> TEAM_MONTHLY_PRICE_CENTS
                PricingTier.BUSINESS -> BUSINESS_MONTHLY_PRICE_CENTS
            }
        val yearlyPrice =
            when (tier) {
                PricingTier.FREE -> 0

                PricingTier.PRO -> PRO_YEARLY_PRICE_CENTS

                // $288/yr
                PricingTier.TEAM -> TEAM_YEARLY_PRICE_CENTS

                // $792/yr
                PricingTier.BUSINESS -> BUSINESS_YEARLY_PRICE_CENTS // $1992/yr
            }
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
            monthlyPriceCents = monthlyPrice,
            yearlyPriceCents = yearlyPrice,
            trialDays = defaultTrialDaysForTier(tier.name),
            paygEnabled = tier != PricingTier.FREE,
            paygRateMicrosPerUnit = if (tier == PricingTier.FREE) 0 else 400000, // $0.40/GB in micros
            overageRateCentsPerGb = if (tier == PricingTier.FREE) 0 else 40, // $0.40/GB
            errorOverageRateCentsPer1k = if (tier == PricingTier.FREE) 0 else 10,
            replayOverageRateCentsPerGb = if (tier == PricingTier.FREE) 0 else 40,
            llmOverageRateCentsPer1k = if (tier == PricingTier.FREE) 0 else 100,
            oncallPerUserMonthlyCents = 500, // Default $5
            oncallPerUserYearlyCents = 5000, // Default $50
            oncallEnabled = tier != PricingTier.FREE,
            stripeBasePriceId = null,
            stripeOveragePriceId = null,
            stripeYearlyBasePriceId = null,
            stripeYearlyOveragePriceId = null,
            stripeOncallPriceId = null,
            stripeOncallYearlyPriceId = null,
            monthlyInfraMetricSeriesHourLimit = defaultMonthlyInfraMetricSeriesHourLimit(tier.name),
            infraMetricOverageRateCentsPer100kSeriesHours = defaultInfraMetricOverageRateCentsPer100k(tier.name),
            isCurrent = true
        )
    }

    private fun rowToResponse(row: ResultRow): PricingTierConfigResponse {
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
            oncallPerUserMonthlyCents = row[PricingTierConfigs.oncall_per_user_monthly_cents],
            oncallPerUserYearlyCents = row[PricingTierConfigs.oncall_per_user_yearly_cents],
            oncallEnabled = row[PricingTierConfigs.oncall_enabled],
            maxAnalyticsSites = row[PricingTierConfigs.max_analytics_sites],
            analyticsRetentionDays = row[PricingTierConfigs.analytics_retention_days],
            monthlyAnalyticsPageviewLimit = row[PricingTierConfigs.monthly_analytics_pageview_limit],
            analyticsPageviewOverageRateCentsPer100k =
            row[PricingTierConfigs.analytics_pageview_overage_rate_cents_per_100k],
            monthlyApmSpanLimit = row[PricingTierConfigs.monthly_apm_span_limit],
            apmSpanOverageRateCentsPer1m = row[PricingTierConfigs.apm_span_overage_rate_cents_per_1m],
            monthlyCustomMetricLimit = row[PricingTierConfigs.monthly_custom_metric_limit],
            customMetricOverageRateCentsPer100k = row[PricingTierConfigs.custom_metric_overage_rate_cents_per_100k],
            monthlyInfraMetricSeriesHourLimit = row[PricingTierConfigs.monthly_infra_metric_series_hour_limit],
            infraMetricOverageRateCentsPer100kSeriesHours =
            row[PricingTierConfigs.infra_metric_overage_rate_cents_per_100k_series_hours],
            maxHosts = row[PricingTierConfigs.max_hosts],
            profilingEnabled = row[PricingTierConfigs.profiling_enabled],
            networkMonitoringEnabled = row[PricingTierConfigs.network_monitoring_enabled],
            dbmEnabled = row[PricingTierConfigs.dbm_enabled],
            debuggerEnabled = row[PricingTierConfigs.debugger_enabled],
            k8sMonitoringEnabled = row[PricingTierConfigs.k8s_monitoring_enabled],
            dataStreamsEnabled = row[PricingTierConfigs.data_streams_enabled],
            sbomEnabled = row[PricingTierConfigs.sbom_enabled],
            syntheticsEnabled = row[PricingTierConfigs.synthetics_enabled],
            stripeBasePriceId = row[PricingTierConfigs.stripe_base_price_id],
            stripeOveragePriceId = row[PricingTierConfigs.stripe_overage_price_id],
            stripeYearlyBasePriceId = row[PricingTierConfigs.stripe_yearly_base_price_id],
            stripeYearlyOveragePriceId = row[PricingTierConfigs.stripe_yearly_overage_price_id],
            stripeOncallPriceId = row[PricingTierConfigs.stripe_oncall_price_id],
            stripeOncallYearlyPriceId = row[PricingTierConfigs.stripe_oncall_yearly_price_id],
            isCurrent = row[PricingTierConfigs.is_current]
        )
    }

    private fun Instant.toLocalDateUtc(): kotlinx.datetime.LocalDate {
        return toLocalDateTime(TimeZone.UTC).date
    }

    private fun defaultFeatureFlagsForTier(tierName: String): DefaultFeatureFlags {
        val canonicalName = tierName.uppercase()
        val isTeamOrBusiness = canonicalName == "TEAM" || canonicalName == "BUSINESS"
        val isBusiness = canonicalName == "BUSINESS"
        return DefaultFeatureFlags(
            statusPagesEnabled = true,
            statusPageCustomDomainEnabled = true,
            sessionReplayEnabled = true,
            slackEnabled = true,
            discordEnabled = true,
            incidentIoEnabled = true,
            samlEnabled = isTeamOrBusiness,
            oidcEnabled = isTeamOrBusiness,
            prioritySupportEnabled = isBusiness,
            slaEnabled = isBusiness,
            customRetentionEnabled = isBusiness
        )
    }

    private fun defaultTrialDaysForTier(tierName: String): Int {
        return if (tierName.uppercase() == "FREE") 0 else DEFAULT_TRIAL_DAYS_NON_FREE
    }

    private fun defaultMonthlyInfraMetricSeriesHourLimit(tierName: String): Long {
        return when (tierName.uppercase()) {
            "FREE" -> FREE_INFRA_METRIC_SERIES_HOUR_LIMIT
            "PRO" -> PRO_INFRA_METRIC_SERIES_HOUR_LIMIT
            "TEAM" -> TEAM_INFRA_METRIC_SERIES_HOUR_LIMIT
            "BUSINESS" -> Long.MAX_VALUE
            else -> FREE_INFRA_METRIC_SERIES_HOUR_LIMIT
        }
    }

    private fun defaultInfraMetricOverageRateCentsPer100k(tierName: String): Int {
        return if (tierName.uppercase() == "FREE") {
            0
        } else {
            DEFAULT_INFRA_METRIC_OVERAGE_RATE_CENTS_PER_100K
        }
    }
}
