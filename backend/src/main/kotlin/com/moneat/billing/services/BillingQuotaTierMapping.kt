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

import com.moneat.billing.models.PricingTier
import com.moneat.billing.models.PricingTierConfigResponse
import com.moneat.billing.models.PricingTierConfigs
import org.jetbrains.exposed.v1.core.ResultRow

private const val PRO_MONTHLY_PRICE_CENTS = 2_900
private const val TEAM_MONTHLY_PRICE_CENTS = 7_900
private const val BUSINESS_MONTHLY_PRICE_CENTS = 19_900
private const val PRO_YEARLY_PRICE_CENTS = 28_800
private const val TEAM_YEARLY_PRICE_CENTS = 79_200
private const val BUSINESS_YEARLY_PRICE_CENTS = 199_200
private const val NON_FREE_TRIAL_DAYS = 14
private const val PAYG_RATE_MICROS_PER_UNIT = 400_000L
private const val GB_OVERAGE_RATE_CENTS = 40
private const val ERROR_OVERAGE_RATE_CENTS_PER_1K = 10
private const val APM_SPAN_OVERAGE_RATE_CENTS_PER_1M = 30
private const val CUSTOM_METRIC_OVERAGE_RATE_CENTS_PER_100K = 50
private const val INFRA_METRIC_OVERAGE_RATE_CENTS_PER_100K = 10
private const val LLM_OVERAGE_RATE_CENTS_PER_1K = 100
private const val ANALYTICS_PAGEVIEW_OVERAGE_RATE_CENTS_PER_100K = 1_000
private const val ONCALL_PER_USER_MONTHLY_CENTS = 500
private const val ONCALL_PER_USER_YEARLY_CENTS = 5_000
private const val FREE_ANALYTICS_SITE_LIMIT = 1
private const val PRO_ANALYTICS_SITE_LIMIT = 5
private const val TEAM_ANALYTICS_SITE_LIMIT = 10
private const val BASIC_ANALYTICS_RETENTION_DAYS = 1_095
private const val EXTENDED_ANALYTICS_RETENTION_DAYS = 1_825
private const val FREE_ANALYTICS_PAGEVIEW_LIMIT = 10_000L
private const val PRO_ANALYTICS_PAGEVIEW_LIMIT = 100_000L
private const val TEAM_ANALYTICS_PAGEVIEW_LIMIT = 1_000_000L
private const val BUSINESS_ANALYTICS_PAGEVIEW_LIMIT = 10_000_000L
private const val FREE_APM_SPAN_LIMIT = 500_000L
private const val PRO_APM_SPAN_LIMIT = 10_000_000L
private const val TEAM_APM_SPAN_LIMIT = 50_000_000L
private const val BUSINESS_APM_SPAN_LIMIT = 200_000_000L
private const val FREE_CUSTOM_METRIC_LIMIT = 100_000L
private const val PRO_CUSTOM_METRIC_LIMIT = 1_000_000L
private const val TEAM_CUSTOM_METRIC_LIMIT = 10_000_000L
private const val FREE_INFRA_METRIC_SERIES_HOUR_LIMIT = 5_000_000L
private const val PRO_INFRA_METRIC_SERIES_HOUR_LIMIT = 50_000_000L
private const val TEAM_INFRA_METRIC_SERIES_HOUR_LIMIT = 250_000_000L
private const val FREE_TIER_MAX_HOSTS = 3

internal fun quotaTierFromRow(row: ResultRow): PricingTierConfigResponse {
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
        apmTraceRetentionDays = row[PricingTierConfigs.apm_trace_retention_days],
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
        isCurrent = row[PricingTierConfigs.is_current]
    )
}

internal fun quotaTierFromEnum(tierName: String): PricingTierConfigResponse {
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
        apmTraceRetentionDays = tier.retentionDays,
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
        monthlyPriceCents = monthlyPriceCents(tier),
        yearlyPriceCents = yearlyPriceCents(tier),
        trialDays = if (tier == PricingTier.FREE) 0 else NON_FREE_TRIAL_DAYS,
        paygEnabled = tier != PricingTier.FREE,
        paygRateMicrosPerUnit = if (tier == PricingTier.FREE) 0 else PAYG_RATE_MICROS_PER_UNIT,
        overageRateCentsPerGb = if (tier == PricingTier.FREE) 0 else GB_OVERAGE_RATE_CENTS,
        errorOverageRateCentsPer1k = if (tier == PricingTier.FREE) 0 else ERROR_OVERAGE_RATE_CENTS_PER_1K,
        replayOverageRateCentsPerGb = if (tier == PricingTier.FREE) 0 else GB_OVERAGE_RATE_CENTS,
        llmOverageRateCentsPer1k = if (tier == PricingTier.FREE) 0 else LLM_OVERAGE_RATE_CENTS_PER_1K,
        stripeBasePriceId = null,
        stripeOveragePriceId = null,
        stripeYearlyBasePriceId = null,
        stripeYearlyOveragePriceId = null,
        stripeOncallPriceId = null,
        stripeOncallYearlyPriceId = null,
        oncallPerUserMonthlyCents = ONCALL_PER_USER_MONTHLY_CENTS,
        oncallPerUserYearlyCents = ONCALL_PER_USER_YEARLY_CENTS,
        oncallEnabled = tier != PricingTier.FREE,
        maxAnalyticsSites = maxAnalyticsSites(tier),
        analyticsRetentionDays = analyticsRetentionDays(tier),
        monthlyAnalyticsPageviewLimit = monthlyAnalyticsPageviewLimit(tier),
        analyticsPageviewOverageRateCentsPer100k =
        if (tier == PricingTier.FREE) 0 else ANALYTICS_PAGEVIEW_OVERAGE_RATE_CENTS_PER_100K,
        monthlyApmSpanLimit = monthlyApmSpanLimit(tier),
        apmSpanOverageRateCentsPer1m = if (tier == PricingTier.FREE) 0 else APM_SPAN_OVERAGE_RATE_CENTS_PER_1M,
        monthlyCustomMetricLimit = monthlyCustomMetricLimit(tier),
        customMetricOverageRateCentsPer100k =
        if (tier == PricingTier.FREE) 0 else CUSTOM_METRIC_OVERAGE_RATE_CENTS_PER_100K,
        monthlyInfraMetricSeriesHourLimit = monthlyInfraMetricSeriesHourLimit(tier),
        infraMetricOverageRateCentsPer100kSeriesHours =
        if (tier == PricingTier.FREE) 0 else INFRA_METRIC_OVERAGE_RATE_CENTS_PER_100K,
        maxHosts = if (tier == PricingTier.FREE) FREE_TIER_MAX_HOSTS else null,
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

private fun monthlyPriceCents(tier: PricingTier): Int {
    return when (tier) {
        PricingTier.FREE -> 0
        PricingTier.PRO -> PRO_MONTHLY_PRICE_CENTS
        PricingTier.TEAM -> TEAM_MONTHLY_PRICE_CENTS
        PricingTier.BUSINESS -> BUSINESS_MONTHLY_PRICE_CENTS
    }
}

private fun yearlyPriceCents(tier: PricingTier): Int {
    return when (tier) {
        PricingTier.FREE -> 0
        PricingTier.PRO -> PRO_YEARLY_PRICE_CENTS
        PricingTier.TEAM -> TEAM_YEARLY_PRICE_CENTS
        PricingTier.BUSINESS -> BUSINESS_YEARLY_PRICE_CENTS
    }
}

private fun maxAnalyticsSites(tier: PricingTier): Int? {
    return when (tier) {
        PricingTier.FREE -> FREE_ANALYTICS_SITE_LIMIT
        PricingTier.PRO -> PRO_ANALYTICS_SITE_LIMIT
        PricingTier.TEAM -> TEAM_ANALYTICS_SITE_LIMIT
        PricingTier.BUSINESS -> null
    }
}

private fun analyticsRetentionDays(tier: PricingTier): Int {
    return when (tier) {
        PricingTier.FREE, PricingTier.PRO -> BASIC_ANALYTICS_RETENTION_DAYS
        PricingTier.TEAM, PricingTier.BUSINESS -> EXTENDED_ANALYTICS_RETENTION_DAYS
    }
}

private fun monthlyAnalyticsPageviewLimit(tier: PricingTier): Long {
    return when (tier) {
        PricingTier.FREE -> FREE_ANALYTICS_PAGEVIEW_LIMIT
        PricingTier.PRO -> PRO_ANALYTICS_PAGEVIEW_LIMIT
        PricingTier.TEAM -> TEAM_ANALYTICS_PAGEVIEW_LIMIT
        PricingTier.BUSINESS -> BUSINESS_ANALYTICS_PAGEVIEW_LIMIT
    }
}

private fun monthlyApmSpanLimit(tier: PricingTier): Long {
    return when (tier) {
        PricingTier.FREE -> FREE_APM_SPAN_LIMIT
        PricingTier.PRO -> PRO_APM_SPAN_LIMIT
        PricingTier.TEAM -> TEAM_APM_SPAN_LIMIT
        PricingTier.BUSINESS -> BUSINESS_APM_SPAN_LIMIT
    }
}

private fun monthlyCustomMetricLimit(tier: PricingTier): Long {
    return when (tier) {
        PricingTier.FREE -> FREE_CUSTOM_METRIC_LIMIT
        PricingTier.PRO -> PRO_CUSTOM_METRIC_LIMIT
        PricingTier.TEAM -> TEAM_CUSTOM_METRIC_LIMIT
        PricingTier.BUSINESS -> Long.MAX_VALUE
    }
}

private fun monthlyInfraMetricSeriesHourLimit(tier: PricingTier): Long {
    return when (tier) {
        PricingTier.FREE -> FREE_INFRA_METRIC_SERIES_HOUR_LIMIT
        PricingTier.PRO -> PRO_INFRA_METRIC_SERIES_HOUR_LIMIT
        PricingTier.TEAM -> TEAM_INFRA_METRIC_SERIES_HOUR_LIMIT
        PricingTier.BUSINESS -> Long.MAX_VALUE
    }
}
