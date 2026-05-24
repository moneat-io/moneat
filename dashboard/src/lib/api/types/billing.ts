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

export interface BillingTierConfig {
  id: number
  tierName: string
  version: number
  monthlyUnitLimit: number
  monthlyErrorLimit: number
  monthlyTransactionLimit: number
  monthlyReplayLimit: number
  monthlyFeedbackLimit: number
  monthlyLlmEventLimit: number
  logRetentionDays: number
  retentionDays: number
  replayRetentionDays: number
  llmRetentionDays: number
  statusPagesEnabled: boolean
  statusPageCustomDomainEnabled: boolean
  sessionReplayEnabled: boolean
  slackEnabled: boolean
  discordEnabled: boolean
  incidentIoEnabled: boolean
  samlEnabled: boolean
  oidcEnabled: boolean
  prioritySupportEnabled: boolean
  slaEnabled: boolean
  customRetentionEnabled: boolean
  maxProjects: number | null
  maxSystems: number
  monitorIntervalSeconds: number
  monthlyPriceCents: number
  yearlyPriceCents: number
  trialDays: number
  monthlyGbLimit: number
  paygEnabled: boolean
  paygRateMicrosPerUnit: number
  overageRateCentsPerGb: number
  errorOverageRateCentsPer1k?: number
  replayOverageRateCentsPerGb?: number
  llmOverageRateCentsPer1k?: number
  stripeBasePriceId?: string | null
  stripeOveragePriceId?: string | null
  stripeYearlyBasePriceId?: string | null
  stripeYearlyOveragePriceId?: string | null
  stripeOncallPriceId?: string | null
  stripeOncallYearlyPriceId?: string | null
  oncallPerUserMonthlyCents?: number
  oncallPerUserYearlyCents?: number
  oncallEnabled?: boolean
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
  isCurrent: boolean
}

export interface BillingPlan {
  tier: BillingTierConfig
  trialDays: number
}

export interface BillingPlansResponse {
  plans: BillingPlan[]
  stripeEnabled: boolean
  publishableKey?: string | null
}

export interface BillingUsage {
  organizationId: number
  periodStart: string
  periodEnd: string
  retentionDays: number
  logRetentionDays?: number
  replayRetentionDays?: number
  llmRetentionDays?: number
  usedUnits: number
  usedErrors: number
  errorLimit: number
  usedTransactions: number
  transactionLimit: number
  usedReplays: number
  replayLimit: number
  usedFeedback: number
  feedbackLimit: number
  usedLlmEvents?: number
  llmEventLimit?: number
  usedLogs?: number
  usedBytes: number
  usedErrorBytes?: number
  usedReplayBytes?: number
  usedLogBytes?: number
  usedLlmBytes?: number
  usedProfilerBytes?: number
  usedApmSpanBytes?: number
  usedInfraMetricBytes?: number
  bytesLimit: number
  baseLimitUnits: number
  paygLimitUnits: number
  paygLimitBytes?: number
  totalLimitUnits: number
  paygBudgetCents: number
  paygUsedUnits: number
  paygUsedCentsEstimate: number
  errorOverageCentsEstimate?: number
  replayOverageCentsEstimate?: number
  logOverageCentsEstimate?: number
  llmOverageCentsEstimate?: number
  totalOverageCentsEstimate?: number
  errorOverageRateCentsPer1k?: number
  replayOverageRateCentsPerGb?: number
  logOverageRateCentsPerGb?: number
  llmOverageRateCentsPer1k?: number
  oncallSeats?: number
  oncallUsedSeats?: number
  oncallPerUserMonthlyCents?: number
  oncallEnabled?: boolean
  usedAnalyticsPageviews?: number
  analyticsPageviewLimit?: number
  analyticsPageviewOverageCentsEstimate?: number
  analyticsPageviewOverageRateCentsPer100k?: number
  usedApmSpans?: number
  apmSpanLimit?: number
  apmSpanOverageCentsEstimate?: number
  apmSpanOverageRateCentsPer1m?: number
  usedCustomMetrics?: number
  customMetricLimit?: number
  customMetricOverageCentsEstimate?: number
  customMetricOverageRateCentsPer100k?: number
  usedInfraMetricSeriesHours?: number
  infraMetricSeriesHourLimit?: number
  infraMetricOverageCentsEstimate?: number
  infraMetricOverageRateCentsPer100kSeriesHours?: number
  ingestionOverageCentsEstimate?: number
  ingestionOverageRateCentsPerGb?: number
  plan: string
  status: string
  withinQuota: boolean
  bonusGbBytes?: number
  bonusUnits?: number
  bonusReason?: string
}

export interface ApmSpanUsageDebugGroup {
  source: string
  service: string
  operation: string
  resource: string
  spanType: string
  env: string
  kind: string
  scopeName: string
  scopeVersion: string
  projectId?: number | null
  projectName?: string | null
  projectSlug?: string | null
  spanCount: number
  traceCount: number
  errorCount: number
  avgDurationMs: number
  maxDurationMs: number
  percentage: number
  sampleTraceId: string
  latestSpanAt: string
}

export interface ApmSpanUsageDebugResponse {
  organizationId: number
  periodStart: string
  periodEnd: string
  totalSpans: number
  groups: ApmSpanUsageDebugGroup[]
}

export interface CheckoutSessionRequest {
  tierName: string
  billingInterval?: string
  successUrl: string
  cancelUrl: string
  oncallSeats?: number
}

export interface CheckoutSessionResponse {
  sessionId: string
  url: string
}

export interface Invoice {
  id: string
  date: string
  amountCents: number
  status: string
  pdfUrl?: string | null
}

export interface PaymentMethod {
  brand?: string | null
  last4?: string | null
  expMonth?: number | null
  expYear?: number | null
}

export interface SetupIntentResponse {
  clientSecret: string
}

export interface CancelSubscriptionResponse {
  status: string
  cancelAtPeriodEnd: boolean
  currentPeriodEnd?: string | null
}

export interface CreateTierVersionRequest {
  monthlyUnitLimit: number
  monthlyErrorLimit: number
  monthlyTransactionLimit: number
  monthlyReplayLimit: number
  monthlyFeedbackLimit: number
  monthlyLlmEventLimit?: number
  monthlyGbLimit?: number | null
  retentionDays: number
  logRetentionDays?: number | null
  replayRetentionDays?: number | null
  llmRetentionDays?: number | null
  statusPagesEnabled?: boolean | null
  statusPageCustomDomainEnabled?: boolean | null
  sessionReplayEnabled?: boolean | null
  slackEnabled?: boolean | null
  discordEnabled?: boolean | null
  incidentIoEnabled?: boolean | null
  samlEnabled?: boolean | null
  oidcEnabled?: boolean | null
  prioritySupportEnabled?: boolean | null
  slaEnabled?: boolean | null
  customRetentionEnabled?: boolean | null
  maxProjects?: number | null
  maxSystems: number
  monitorIntervalSeconds: number
  monthlyPriceCents: number
  yearlyPriceCents?: number | null
  trialDays?: number | null
  paygEnabled: boolean
  paygRateMicrosPerUnit: number
  overageRateCentsPerGb?: number | null
  errorOverageRateCentsPer1k?: number | null
  replayOverageRateCentsPerGb?: number | null
  llmOverageRateCentsPer1k?: number | null
  oncallPerUserMonthlyCents?: number | null
  oncallPerUserYearlyCents?: number | null
  oncallEnabled?: boolean | null
  maxAnalyticsSites?: number | null
  analyticsRetentionDays?: number | null
  monthlyAnalyticsPageviewLimit?: number | null
  analyticsPageviewOverageRateCentsPer100k?: number | null
  monthlyInfraMetricSeriesHourLimit?: number | null
  infraMetricOverageRateCentsPer100kSeriesHours?: number | null
  stripeBasePriceId?: string | null
  stripeOveragePriceId?: string | null
  stripeYearlyBasePriceId?: string | null
  stripeYearlyOveragePriceId?: string | null
  stripeOncallPriceId?: string | null
  stripeOncallYearlyPriceId?: string | null
}

export interface TierMigrationResponse {
  tierName: string
  targetVersion: number
  affectedSubscriptions: number
  dryRun: boolean
}

export interface UpdateStripePriceIdsRequest {
  stripeBasePriceId?: string | null
  stripeOveragePriceId?: string | null
  stripeYearlyBasePriceId?: string | null
  stripeYearlyOveragePriceId?: string | null
  stripeOncallPriceId?: string | null
  stripeOncallYearlyPriceId?: string | null
}

export interface UpdateOnCallSeatsResponse {
  seats: number
  proratedAmountCents?: number
}
