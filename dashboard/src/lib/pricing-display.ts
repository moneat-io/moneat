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

const BYTES_PER_GB = 1024 * 1024 * 1024
const UNLIMITED_SYSTEMS_SENTINEL = 2147483647
const UNLIMITED_SENTINEL = 9_007_199_254_740_000 // Anything above this is "unlimited" (JS safe threshold for Long.MAX_VALUE)

export type BillingInterval = 'monthly' | 'yearly'

export interface PricingCardTierInput {
  tierName: string
  monthlyPriceCents: number
  yearlyPriceCents: number
  trialDays?: number | null
  monthlyGbLimit: number
  monthlyErrorLimit?: number
  monthlyReplayLimit?: number
  monthlyLlmEventLimit?: number
  retentionDays: number
  logRetentionDays?: number
  replayRetentionDays?: number
  llmRetentionDays?: number
  apmTraceRetentionDays?: number
  maxProjects: number | null
  maxSystems: number
  monitorIntervalSeconds: number
  sessionReplayEnabled: boolean
  statusPagesEnabled: boolean
  statusPageCustomDomainEnabled: boolean
  slackEnabled: boolean
  discordEnabled?: boolean
  incidentIoEnabled: boolean
  samlEnabled: boolean
  oidcEnabled: boolean
  prioritySupportEnabled: boolean
  slaEnabled: boolean
  customRetentionEnabled: boolean
  oncallPerUserMonthlyCents?: number
  oncallEnabled?: boolean
  overageRateCentsPerGb?: number
  errorOverageRateCentsPer1k?: number
  replayOverageRateCentsPerGb?: number
  llmOverageRateCentsPer1k?: number
  maxAnalyticsSites?: number | null
  analyticsRetentionDays?: number
  monthlyAnalyticsPageviewLimit?: number
  analyticsPageviewOverageRateCentsPer100k?: number
  monthlyApmSpanLimit?: number
  apmSpanOverageRateCentsPer1m?: number
  monthlyCustomMetricLimit?: number
  customMetricOverageRateCentsPer100k?: number
  monthlyInfraMetricSeriesHourLimit?: number
  infraMetricOverageRateCentsPer100kSeriesHours?: number
  maxHosts?: number | null
  profilingEnabled?: boolean
  networkMonitoringEnabled?: boolean
  dbmEnabled?: boolean
  debuggerEnabled?: boolean
  k8sMonitoringEnabled?: boolean
  dataStreamsEnabled?: boolean
  sbomEnabled?: boolean
  syntheticsEnabled?: boolean
}

export interface OverageItem {
  label: string
  rate: string
}

export interface PricingCardModel {
  tierName: string
  name: string
  description: string
  monthlyPrice: number
  yearlyMonthlyPrice: number
  yearlyTotalPrice: number
  displayPrice: number
  includedLimits: string[]
  platformFeatures: string[]
  overages: OverageItem[]
  cta: string
  ctaLink: string
  highlight: boolean
  /** @deprecated Use includedLimits + platformFeatures instead */
  features: string[]
}

function formatCompactNumber(value: number): string {
  if (Number.isInteger(value)) return String(value)
  return value.toFixed(1).replace(/\.0$/, '')
}

function formatDataLimit(bytesLimit: number): string {
  const gbLimit = Math.max(0, bytesLimit) / BYTES_PER_GB
  if (gbLimit >= 1024) {
    return `${formatCompactNumber(gbLimit / 1024)} TB`
  }
  return `${formatCompactNumber(gbLimit)} GB`
}

function isUnlimited(value: number | undefined): boolean {
  if (value == null) return false
  return value >= UNLIMITED_SENTINEL || value < 0
}

function formatEventLimit(value: number | undefined): string {
  if (value == null || value === 0) return '0'
  if (isUnlimited(value)) return 'Unlimited'
  if (value >= 1_000_000) return `${formatCompactNumber(value / 1_000_000)}M`
  if (value >= 1_000) return `${formatCompactNumber(value / 1_000)}K`
  return value.toLocaleString()
}

function formatMonitorLimit(maxSystems: number): string {
  if (maxSystems >= UNLIMITED_SYSTEMS_SENTINEL) return 'Unlimited monitors'
  return `${maxSystems} monitors`
}

function tierDescription(tierName: string): string {
  switch (tierName) {
    case 'FREE':
      return 'Perfect for side projects and getting started'
    case 'PRO':
      return 'For growing teams shipping production apps'
    case 'TEAM':
      return 'For teams that need scale and compliance'
    case 'BUSINESS':
      return 'For enterprises with custom requirements'
    default:
      return 'Flexible observability for every team'
  }
}

function formatCentsRate(cents: number, unit: string): string {
  return `$${(cents / 100).toFixed(2)}/${unit}`
}

function customMetricLimitText(tier: PricingCardTierInput): string | null {
  const limit = tier.monthlyCustomMetricLimit
  if (limit == null || limit <= 0) return null
  return `${formatEventLimit(limit)} custom metrics`
}

function infraMetricLimitText(tier: PricingCardTierInput): string | null {
  const limit = tier.monthlyInfraMetricSeriesHourLimit
  if (limit == null || (limit <= 0 && !isUnlimited(limit))) return null
  return `${formatEventLimit(limit)} infra metric series-hours`
}

function spanLimitText(tier: PricingCardTierInput): string | null {
  const limit = tier.monthlyApmSpanLimit
  if (limit == null || limit <= 0) return null
  return `${formatEventLimit(limit)} APM spans`
}

function hostLimitText(tier: PricingCardTierInput): string | null {
  const maxHosts = tier.maxHosts
  if (maxHosts === undefined) return null
  if (maxHosts == null || maxHosts >= UNLIMITED_SYSTEMS_SENTINEL) return 'Unlimited hosts'
  return `Up to ${maxHosts} hosts`
}

function retentionLimitText(tier: PricingCardTierInput): string {
  const errorRetention = tier.retentionDays
  const logRetention = tier.logRetentionDays ?? errorRetention
  const replayRetention = tier.replayRetentionDays ?? errorRetention
  const llmRetention = tier.llmRetentionDays ?? errorRetention
  const apmRetention = tier.apmTraceRetentionDays ?? errorRetention
  const coreRetentionMatches =
    logRetention === errorRetention && replayRetention === errorRetention && llmRetention === errorRetention

  if (coreRetentionMatches && apmRetention === errorRetention) return `${errorRetention}-day retention`
  if (coreRetentionMatches) return `${errorRetention}-day core retention, ${apmRetention}-day APM traces`
  return (
    `${errorRetention}d errors, ${logRetention}d logs, ${replayRetention}d replays, ` +
    `${llmRetention}d AI, ${apmRetention}d APM traces`
  )
}

function pluralSuffix(count: number): string {
  if (count === 1) return ''
  return 's'
}

function serviceLimitText(tier: PricingCardTierInput): string {
  if (tier.maxProjects == null) return 'Unlimited services'
  return `${tier.maxProjects} service${pluralSuffix(tier.maxProjects)}`
}

function analyticsSiteLimitText(tier: PricingCardTierInput): string | null {
  const analyticsSites = tier.maxAnalyticsSites
  if (analyticsSites === undefined) return null
  if (analyticsSites === null) return 'Unlimited analytics sites'
  return `${analyticsSites} analytics site${pluralSuffix(analyticsSites)}`
}

function analyticsPageviewLimitText(tier: PricingCardTierInput): string | null {
  const analyticsPageviews = tier.monthlyAnalyticsPageviewLimit
  if (analyticsPageviews == null || analyticsPageviews <= 0) return null
  return `${formatEventLimit(analyticsPageviews)} page views/mo`
}

function analyticsRetentionText(tier: PricingCardTierInput): string | null {
  const analyticsRet = tier.analyticsRetentionDays
  if (analyticsRet == null || analyticsRet <= 0) return null
  if (analyticsRet >= 365) return `${Math.round(analyticsRet / 365)}-year analytics retention`
  return `${analyticsRet}-day analytics retention`
}

function buildIncludedLimits(tier: PricingCardTierInput): string[] {
  return [
    `${formatDataLimit(tier.monthlyGbLimit)} ingestion`,
    customMetricLimitText(tier),
    infraMetricLimitText(tier),
    spanLimitText(tier),
    hostLimitText(tier),
    retentionLimitText(tier),
    serviceLimitText(tier),
    `${formatMonitorLimit(tier.maxSystems)} (${tier.monitorIntervalSeconds}s interval)`,
    analyticsSiteLimitText(tier),
    analyticsPageviewLimitText(tier),
    analyticsRetentionText(tier),
  ].filter((limit): limit is string => limit != null)
}

function buildPlatformFeatures(tier: PricingCardTierInput): string[] {
  const features: string[] = []

  // All DD Agent features included on all tiers
  features.push('Error tracking & session replays')
  features.push('Log management')
  features.push('AI observability')
  features.push('APM & distributed tracing')
  features.push('Continuous profiling')
  features.push('Infrastructure monitoring')
  features.push('Database monitoring')
  features.push('Network monitoring')
  features.push('Kubernetes monitoring')
  features.push('Dynamic instrumentation')
  features.push('SBOM')
  features.push('Synthetic testing')
  features.push('Data streams')

  if (tier.statusPagesEnabled && tier.statusPageCustomDomainEnabled) {
    features.push('Status pages with custom domains')
  } else if (tier.statusPagesEnabled) {
    features.push('Custom status pages')
  }

  if (tier.slackEnabled) features.push('Slack integration')
  if (tier.discordEnabled) features.push('Discord integration')
  if (tier.oncallEnabled && tier.oncallPerUserMonthlyCents) {
    features.push(`On-call (+$${tier.oncallPerUserMonthlyCents / 100}/user/mo)`)
  }
  if (tier.samlEnabled) features.push('SAML SSO')
  if (tier.oidcEnabled) features.push('OIDC SSO')
  if (tier.prioritySupportEnabled) features.push('Priority support')
  if (tier.slaEnabled) features.push('SLA guarantee')
  if (tier.customRetentionEnabled) features.push('Custom retention')

  return features
}

function buildOverages(tier: PricingCardTierInput): OverageItem[] {
  if (tier.monthlyPriceCents === 0) return []

  const overages: OverageItem[] = []

  // Unified ingestion overage
  const ingestionRate = tier.overageRateCentsPerGb
  if (ingestionRate != null && ingestionRate > 0) {
    overages.push({label: 'Ingestion', rate: formatCentsRate(ingestionRate, 'GB')})
  }

  // Custom metric overage
  const metricRate = tier.customMetricOverageRateCentsPer100k
  if (metricRate != null && metricRate > 0) {
    overages.push({label: 'Custom metrics', rate: formatCentsRate(metricRate, '100K')})
  }

  const infraMetricRate = tier.infraMetricOverageRateCentsPer100kSeriesHours
  if (infraMetricRate != null && infraMetricRate > 0) {
    overages.push({label: 'Infra metrics', rate: formatCentsRate(infraMetricRate, '100K series-hours')})
  }

  const spanRate = tier.apmSpanOverageRateCentsPer1m
  if (spanRate != null && spanRate > 0) {
    overages.push({label: 'APM spans', rate: formatCentsRate(spanRate, '1M')})
  }

  // Analytics pageview overage
  const analyticsRate = tier.analyticsPageviewOverageRateCentsPer100k
  if (analyticsRate != null && analyticsRate > 0) {
    overages.push({label: 'Page views', rate: formatCentsRate(analyticsRate, '100K')})
  }

  return overages
}

function ctaForTier(tier: PricingCardTierInput): string {
  if (tier.monthlyPriceCents === 0) return 'Start Free'
  if (typeof tier.trialDays === 'number' && tier.trialDays > 0) return `Start ${tier.trialDays}-Day Trial`
  return 'Start Trial'
}

export function buildPricingCardModel(
  tier: PricingCardTierInput,
  billingInterval: BillingInterval,
  ctaLink = '/signup',
): PricingCardModel {
  const monthlyPrice = tier.monthlyPriceCents / 100
  const yearlyMonthlyPrice = tier.yearlyPriceCents / (100 * 12)
  const yearlyTotalPrice = tier.yearlyPriceCents / 100
  const includedLimits = buildIncludedLimits(tier)
  const platformFeatures = buildPlatformFeatures(tier)
  const overages = buildOverages(tier)

  return {
    tierName: tier.tierName,
    name: tier.tierName.charAt(0) + tier.tierName.slice(1).toLowerCase(),
    description: tierDescription(tier.tierName),
    monthlyPrice,
    yearlyMonthlyPrice,
    yearlyTotalPrice,
    displayPrice: billingInterval === 'yearly' ? yearlyMonthlyPrice : monthlyPrice,
    includedLimits,
    platformFeatures,
    overages,
    features: [...includedLimits, ...platformFeatures],
    cta: ctaForTier(tier),
    ctaLink,
    highlight: tier.tierName === 'PRO',
  }
}
