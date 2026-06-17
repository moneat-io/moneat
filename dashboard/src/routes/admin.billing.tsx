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

import {useMemo, useState} from 'react'
import {createFileRoute} from '@tanstack/react-router'
import {useMutation, useQuery, useQueryClient} from '@tanstack/react-query'
import {
    api,
    type AdminBillingSubscription,
    type BillingPlan,
    type BillingTierConfig,
    type CreateTierVersionRequest,
} from '@/lib/api'
import {Card, CardContent, CardDescription, CardHeader, CardTitle} from '@/components/ui/card'
import {Button} from '@/components/ui/button'
import {Input} from '@/components/ui/input'
import {Label} from '@/components/ui/label'
import {Badge} from '@/components/ui/badge'
import {Switch} from '@/components/ui/switch'
import {Select, SelectContent, SelectItem, SelectTrigger, SelectValue} from '@/components/ui/select'
import {Separator} from '@/components/ui/separator'
import {Table, TableBody, TableCell, TableHead, TableHeader, TableRow} from '@/components/ui/table'
import {
    Dialog,
    DialogContent,
    DialogDescription,
    DialogFooter,
    DialogHeader,
    DialogTitle,
} from '@/components/ui/dialog'
import {Tabs, TabsContent, TabsList, TabsTrigger} from '@/components/ui/tabs'
import {Tooltip, TooltipContent, TooltipProvider, TooltipTrigger} from '@/components/ui/tooltip'
import {useToast} from '@/hooks/useToast'
import {AdminSkeleton, PlanBadge, SectionHeader} from '@/components/AdminComponents'
import {type BillingInterval, buildPricingCardModel, type PricingCardTierInput} from '@/lib/pricing-display'
import {
    formatQuotaLimit,
    formatTotalQuotaLimit,
    normalizeQuotaForForm,
    normalizeQuotaForRequest,
    normalizeReplayQuotaForForm,
    normalizeReplayQuotaForRequest,
    totalQuotaForRequest,
} from '@/lib/admin-billing-limits'
import {AlertTriangle, Check, HelpCircle, Info} from 'lucide-react'
import {useTimezone} from '@/hooks/useTimezone'
import {formatDate} from '@/lib/date-format'

export const Route = createFileRoute('/admin/billing')({
  component: AdminBillingPage,
})

// ─── Helpers ──────────────────────────────────────────────────────────────────

function centsToDollars(cents: number): string {
  return (cents / 100).toFixed(2)
}

function microsToDollars(micros: number): string {
  return (micros / 1_000_000).toFixed(6)
}

function formatInterval(seconds: number): string {
  if (seconds >= 3600) return `${seconds / 3600}h`
  if (seconds >= 60) return `${seconds / 60}m`
  return `${seconds}s`
}

function FieldHint({children}: {children: React.ReactNode}) {
  return <p className="text-xs text-muted-foreground mt-1">{children}</p>
}

function HelpTip({text}: {text: string}) {
  return (
    <Tooltip>
      <TooltipTrigger asChild>
        <HelpCircle className="h-3.5 w-3.5 text-muted-foreground inline-block ml-1 cursor-help" />
      </TooltipTrigger>
      <TooltipContent side="top" className="max-w-xs">
        <p className="text-xs">{text}</p>
      </TooltipContent>
    </Tooltip>
  )
}

// Known tier names for the dropdown
const KNOWN_TIERS = ['FREE', 'PRO', 'TEAM', 'BUSINESS']
const TIER_ORDER: Record<string, number> = {FREE: 0, PRO: 1, TEAM: 2, BUSINESS: 3}
const BYTES_PER_GB = 1024 * 1024 * 1024
const PRICING_PREVIEW_LIMIT_COUNT = 6

function formatRetentionSummary(
  errorDays: number,
  logDays: number,
  replayDays: number,
  llmDays: number,
  apmDays: number
): string {
  const coreSame = errorDays === logDays && errorDays === replayDays && errorDays === llmDays
  if (coreSame && errorDays === apmDays) {
    return `${errorDays}d all`
  }
  if (coreSame) {
    return `${errorDays}d core, ${apmDays}d APM`
  }
  return [
    `${errorDays}d errors`,
    `${logDays}d logs`,
    `${replayDays}d replays`,
    `${llmDays}d LLM`,
    `${apmDays}d APM`,
  ].join(', ')
}

function retentionSummary(tier: BillingTierConfig): string {
  const errorDays = tier.retentionDays
  const logDays = tier.logRetentionDays ?? errorDays
  const replayDays = tier.replayRetentionDays ?? errorDays
  const llmDays = tier.llmRetentionDays ?? errorDays
  const apmDays = tier.apmTraceRetentionDays ?? errorDays
  return formatRetentionSummary(errorDays, logDays, replayDays, llmDays, apmDays)
}

function createFormRetentionSummary(form: CreateFormState): string {
  return formatRetentionSummary(
    form.retentionDays,
    form.logRetentionDays,
    form.replayRetentionDays,
    form.llmRetentionDays,
    form.apmTraceRetentionDays
  )
}

// ─── Validation ───────────────────────────────────────────────────────────────

interface ValidationErrors {
  monthlyErrorLimit?: string
  monthlyTransactionLimit?: string
  monthlyReplayLimit?: string
  monthlyFeedbackLimit?: string
  monthlyLlmEventLimit?: string
  retentionDays?: string
  logRetentionDays?: string
  replayRetentionDays?: string
  llmRetentionDays?: string
  apmTraceRetentionDays?: string
  maxSystems?: string
  monitorIntervalSeconds?: string
  monthlyPriceCents?: string
  yearlyPriceCents?: string
  monthlyGbLimitGb?: string
  trialDays?: string
  paygRateMicrosPerUnit?: string
  errorOverageRateCentsPer1k?: string
  replayOverageRateCentsPerGb?: string
  llmOverageRateCentsPer1k?: string
  oncallPerUserMonthlyCents?: string
  oncallPerUserYearlyCents?: string
}

function validateCreateForm(form: CreateFormState): ValidationErrors {
  const errors: ValidationErrors = {}

  if (form.monthlyErrorLimit < 0) errors.monthlyErrorLimit = 'Cannot be negative'
  if (form.monthlyTransactionLimit < 0) errors.monthlyTransactionLimit = 'Cannot be negative'
  if (form.monthlyReplayLimit < -1) errors.monthlyReplayLimit = 'Cannot be less than -1 (use -1 for unlimited)'
  if (form.monthlyFeedbackLimit < 0) errors.monthlyFeedbackLimit = 'Cannot be negative'
  if (form.monthlyLlmEventLimit < 0) errors.monthlyLlmEventLimit = 'Cannot be negative'
  if (form.retentionDays < 1) {
    errors.retentionDays = 'Must be at least 1 day'
  }
  if (form.retentionDays > 90) {
    errors.retentionDays = 'Cannot exceed 90 days'
  }
  if (form.logRetentionDays < 1 || form.logRetentionDays > 90) {
    errors.logRetentionDays = 'Must be between 1 and 90 days'
  }
  if (form.replayRetentionDays < 1 || form.replayRetentionDays > 90) {
    errors.replayRetentionDays = 'Must be between 1 and 90 days'
  }
  if (form.llmRetentionDays < 1 || form.llmRetentionDays > 90) {
    errors.llmRetentionDays = 'Must be between 1 and 90 days'
  }
  if (form.apmTraceRetentionDays < 1 || form.apmTraceRetentionDays > 90) {
    errors.apmTraceRetentionDays = 'Must be between 1 and 90 days'
  }
  if (form.maxSystems < 1) {
    errors.maxSystems = 'Must be at least 1 system'
  }
  if (form.monitorIntervalSeconds < 5) {
    errors.monitorIntervalSeconds = 'Must be at least 5 seconds'
  }
  if (form.monitorIntervalSeconds > 3600) {
    errors.monitorIntervalSeconds = 'Cannot exceed 3,600 seconds (1 hour)'
  }
  if (form.monthlyPriceCents < 0) {
    errors.monthlyPriceCents = 'Price cannot be negative'
  }
  if (form.yearlyPriceCents < 0) {
    errors.yearlyPriceCents = 'Yearly price cannot be negative'
  }
  if (form.monthlyGbLimitGb < 0) {
    errors.monthlyGbLimitGb = 'Data limit cannot be negative'
  }
  if (form.trialDays < 0) {
    errors.trialDays = 'Trial days cannot be negative'
  }
  if (form.trialDays > 60) {
    errors.trialDays = 'Cannot exceed 60 days'
  }
  if (form.paygEnabled && form.paygRateMicrosPerUnit < 1) {
    errors.paygRateMicrosPerUnit = 'PAYG rate must be at least 1 micro'
  }
  if (form.errorOverageRateCentsPer1k < 0) errors.errorOverageRateCentsPer1k = 'Cannot be negative'
  if (form.replayOverageRateCentsPerGb < 0) errors.replayOverageRateCentsPerGb = 'Cannot be negative'
  if (form.llmOverageRateCentsPer1k < 0) errors.llmOverageRateCentsPer1k = 'Cannot be negative'
  if (form.oncallPerUserMonthlyCents < 0) errors.oncallPerUserMonthlyCents = 'Cannot be negative'
  if (form.oncallPerUserYearlyCents < 0) errors.oncallPerUserYearlyCents = 'Cannot be negative'

  return errors
}

// ─── Types ────────────────────────────────────────────────────────────────────

interface CreateFormState {
  monthlyErrorLimit: number
  monthlyTransactionLimit: number
  monthlyReplayLimit: number
  monthlyFeedbackLimit: number
  monthlyLlmEventLimit: number
  retentionDays: number
  logRetentionDays: number
  replayRetentionDays: number
  llmRetentionDays: number
  apmTraceRetentionDays: number
  maxProjects: string
  maxSystems: number
  monitorIntervalSeconds: number
  monthlyPriceCents: number
  yearlyPriceCents: number
  monthlyGbLimitGb: number
  trialDays: number
  paygEnabled: boolean
  paygRateMicrosPerUnit: number
  overageRateCentsPerGb: number
  errorOverageRateCentsPer1k: number
  replayOverageRateCentsPerGb: number
  llmOverageRateCentsPer1k: number
  oncallEnabled: boolean
  oncallPerUserMonthlyCents: number
  oncallPerUserYearlyCents: number
  maxAnalyticsSites: string
  analyticsRetentionDays: number
  monthlyAnalyticsPageviewLimit: number
  analyticsPageviewOverageRateCentsPer100k: number
  stripeBasePriceId: string
  stripeOveragePriceId: string
  stripeYearlyBasePriceId: string
  stripeYearlyOveragePriceId: string
  stripeOncallPriceId: string
  stripeOncallYearlyPriceId: string
}

const DEFAULT_FORM: CreateFormState = {
  monthlyErrorLimit: 500_000,
  monthlyTransactionLimit: 0,
  monthlyReplayLimit: 300,
  monthlyFeedbackLimit: 0,
  monthlyLlmEventLimit: 10_000,
  retentionDays: 30,
  logRetentionDays: 30,
  replayRetentionDays: 14,
  llmRetentionDays: 30,
  apmTraceRetentionDays: 30,
  maxProjects: '',
  maxSystems: 5,
  monitorIntervalSeconds: 15,
  monthlyPriceCents: 1900,
  yearlyPriceCents: 19_200,
  monthlyGbLimitGb: 50,
  trialDays: 14,
  paygEnabled: true,
  paygRateMicrosPerUnit: 10,
  overageRateCentsPerGb: 40,
  errorOverageRateCentsPer1k: 10,
  replayOverageRateCentsPerGb: 40,
  llmOverageRateCentsPer1k: 100,
  oncallEnabled: true,
  oncallPerUserMonthlyCents: 500,
  oncallPerUserYearlyCents: 5000,
  maxAnalyticsSites: '',
  analyticsRetentionDays: 1095,
  monthlyAnalyticsPageviewLimit: 100_000,
  analyticsPageviewOverageRateCentsPer100k: 1000,
  stripeBasePriceId: '',
  stripeOveragePriceId: '',
  stripeYearlyBasePriceId: '',
  stripeYearlyOveragePriceId: '',
  stripeOncallPriceId: '',
  stripeOncallYearlyPriceId: '',
}

// ─── Standalone helper (used for computed form state) ─────────────────────────

function buildCreateFormFromConfig(config: BillingTierConfig): CreateFormState {
  return {
    monthlyErrorLimit: normalizeQuotaForForm(config.monthlyErrorLimit),
    monthlyTransactionLimit: normalizeQuotaForForm(config.monthlyTransactionLimit),
    monthlyReplayLimit: normalizeReplayQuotaForForm(config.monthlyReplayLimit),
    monthlyFeedbackLimit: normalizeQuotaForForm(config.monthlyFeedbackLimit),
    monthlyLlmEventLimit: normalizeQuotaForForm(config.monthlyLlmEventLimit ?? 0),
    retentionDays: config.retentionDays,
    logRetentionDays: config.logRetentionDays ?? config.retentionDays,
    replayRetentionDays: config.replayRetentionDays ?? config.retentionDays,
    llmRetentionDays: config.llmRetentionDays ?? config.retentionDays,
    apmTraceRetentionDays: config.apmTraceRetentionDays ?? config.retentionDays,
    maxProjects: config.maxProjects != null ? String(config.maxProjects) : '',
    maxSystems: config.maxSystems,
    monitorIntervalSeconds: config.monitorIntervalSeconds,
    monthlyPriceCents: config.monthlyPriceCents,
    yearlyPriceCents: config.yearlyPriceCents,
    monthlyGbLimitGb: Math.max(0, Math.round(config.monthlyGbLimit / BYTES_PER_GB)),
    trialDays: config.trialDays,
    paygEnabled: config.paygEnabled,
    paygRateMicrosPerUnit: config.paygRateMicrosPerUnit,
    overageRateCentsPerGb: config.overageRateCentsPerGb ?? 40,
    errorOverageRateCentsPer1k: config.errorOverageRateCentsPer1k ?? 10,
    replayOverageRateCentsPerGb: config.replayOverageRateCentsPerGb ?? 40,
    llmOverageRateCentsPer1k: config.llmOverageRateCentsPer1k ?? 100,
    oncallEnabled: config.oncallEnabled ?? false,
    oncallPerUserMonthlyCents: config.oncallPerUserMonthlyCents ?? 500,
    oncallPerUserYearlyCents: config.oncallPerUserYearlyCents ?? 5000,
    maxAnalyticsSites: config.maxAnalyticsSites != null ? String(config.maxAnalyticsSites) : '',
    analyticsRetentionDays: config.analyticsRetentionDays ?? 1095,
    monthlyAnalyticsPageviewLimit: normalizeQuotaForForm(config.monthlyAnalyticsPageviewLimit ?? 0),
    analyticsPageviewOverageRateCentsPer100k: config.analyticsPageviewOverageRateCentsPer100k ?? 0,
    stripeBasePriceId: config.stripeBasePriceId ?? '',
    stripeOveragePriceId: config.stripeOveragePriceId ?? '',
    stripeYearlyBasePriceId: config.stripeYearlyBasePriceId ?? '',
    stripeYearlyOveragePriceId: config.stripeYearlyOveragePriceId ?? '',
    stripeOncallPriceId: config.stripeOncallPriceId ?? '',
    stripeOncallYearlyPriceId: config.stripeOncallYearlyPriceId ?? '',
  }
}

function buildCreateTierVersionRequest(form: CreateFormState): CreateTierVersionRequest {
  const monthlyErrorLimit = normalizeQuotaForRequest(form.monthlyErrorLimit)
  const monthlyTransactionLimit = normalizeQuotaForRequest(form.monthlyTransactionLimit)
  const monthlyReplayLimit = normalizeReplayQuotaForRequest(form.monthlyReplayLimit)
  const monthlyFeedbackLimit = normalizeQuotaForRequest(form.monthlyFeedbackLimit)
  const totalLimits = [
    monthlyErrorLimit,
    monthlyTransactionLimit,
    monthlyReplayLimit,
    monthlyFeedbackLimit,
  ]

  return {
    monthlyUnitLimit: totalQuotaForRequest(totalLimits),
    monthlyErrorLimit,
    monthlyTransactionLimit,
    monthlyReplayLimit,
    monthlyFeedbackLimit,
    monthlyLlmEventLimit: normalizeQuotaForRequest(form.monthlyLlmEventLimit),
    retentionDays: Number(form.retentionDays),
    logRetentionDays: Number(form.logRetentionDays),
    replayRetentionDays: Number(form.replayRetentionDays),
    llmRetentionDays: Number(form.llmRetentionDays),
    apmTraceRetentionDays: Number(form.apmTraceRetentionDays),
    maxProjects: form.maxProjects.trim() ? Number(form.maxProjects) : null,
    maxSystems: Number(form.maxSystems),
    monitorIntervalSeconds: Number(form.monitorIntervalSeconds),
    monthlyPriceCents: Number(form.monthlyPriceCents),
    yearlyPriceCents: Number(form.yearlyPriceCents),
    monthlyGbLimit: Math.max(0, Math.round(form.monthlyGbLimitGb * BYTES_PER_GB)),
    trialDays: Number(form.trialDays),
    paygEnabled: Boolean(form.paygEnabled),
    paygRateMicrosPerUnit: Number(form.paygRateMicrosPerUnit),
    overageRateCentsPerGb: Number(form.overageRateCentsPerGb),
    errorOverageRateCentsPer1k: Number(form.errorOverageRateCentsPer1k),
    replayOverageRateCentsPerGb: Number(form.replayOverageRateCentsPerGb),
    llmOverageRateCentsPer1k: Number(form.llmOverageRateCentsPer1k),
    oncallEnabled: Boolean(form.oncallEnabled),
    oncallPerUserMonthlyCents: Number(form.oncallPerUserMonthlyCents),
    oncallPerUserYearlyCents: Number(form.oncallPerUserYearlyCents),
    maxAnalyticsSites: form.maxAnalyticsSites.trim() ? Number(form.maxAnalyticsSites) : null,
    analyticsRetentionDays: Number(form.analyticsRetentionDays),
    monthlyAnalyticsPageviewLimit: normalizeQuotaForRequest(form.monthlyAnalyticsPageviewLimit),
    analyticsPageviewOverageRateCentsPer100k: Number(form.analyticsPageviewOverageRateCentsPer100k),
    stripeBasePriceId: form.stripeBasePriceId.trim() || null,
    stripeOveragePriceId: form.stripeOveragePriceId.trim() || null,
    stripeYearlyBasePriceId: form.stripeYearlyBasePriceId.trim() || null,
    stripeYearlyOveragePriceId: form.stripeYearlyOveragePriceId.trim() || null,
    stripeOncallPriceId: form.stripeOncallPriceId.trim() || null,
    stripeOncallYearlyPriceId: form.stripeOncallYearlyPriceId.trim() || null,
  }
}

type UpdatePriceFormData = {
  stripeBasePriceId: string
  stripeOveragePriceId: string
  stripeYearlyBasePriceId: string
  stripeYearlyOveragePriceId: string
  stripeOncallPriceId: string
  stripeOncallYearlyPriceId: string
}

const EMPTY_UPDATE_PRICE_FORM: UpdatePriceFormData = {
  stripeBasePriceId: '',
  stripeOveragePriceId: '',
  stripeYearlyBasePriceId: '',
  stripeYearlyOveragePriceId: '',
  stripeOncallPriceId: '',
  stripeOncallYearlyPriceId: '',
}

function toBillingPlans(value: unknown): BillingPlan[] {
  if (!Array.isArray(value)) return []
  const first = value[0]
  if (first == null || typeof first !== 'object' || !('tier' in first)) return []
  return value as BillingPlan[]
}

function toBillingTierConfigs(value: unknown): BillingTierConfig[] {
  if (!Array.isArray(value)) return []
  return value as BillingTierConfig[]
}

function tierVersionKey(config: BillingTierConfig | undefined): string | undefined {
  if (!config) return undefined
  return `${config.tierName}-${config.version}`
}

function resolveCreateForm(
  formState: {tierVersion: string | undefined; data: CreateFormState},
  currentTierVersion: string | undefined,
  currentTierConfig: BillingTierConfig | undefined,
): CreateFormState {
  if (formState.tierVersion === currentTierVersion && currentTierVersion !== undefined) return formState.data
  if (currentTierConfig) return buildCreateFormFromConfig(currentTierConfig)
  return DEFAULT_FORM
}

function buildUpdatePriceForm(config: BillingTierConfig | undefined): UpdatePriceFormData {
  if (!config) return EMPTY_UPDATE_PRICE_FORM
  return {
    stripeBasePriceId: config.stripeBasePriceId ?? '',
    stripeOveragePriceId: config.stripeOveragePriceId ?? '',
    stripeYearlyBasePriceId: config.stripeYearlyBasePriceId ?? '',
    stripeYearlyOveragePriceId: config.stripeYearlyOveragePriceId ?? '',
    stripeOncallPriceId: config.stripeOncallPriceId ?? '',
    stripeOncallYearlyPriceId: config.stripeOncallYearlyPriceId ?? '',
  }
}

function resolveUpdatePriceForm(
  formState: {tierVersion: string | undefined; data: UpdatePriceFormData},
  currentTierVersion: string | undefined,
  selectedConfig: BillingTierConfig | undefined,
): UpdatePriceFormData {
  if (formState.tierVersion === currentTierVersion && currentTierVersion !== undefined) return formState.data
  return buildUpdatePriceForm(selectedConfig)
}

function filterSubscriptionsByText(
  subscriptions: AdminBillingSubscription[],
  filter: string,
): AdminBillingSubscription[] {
  if (!filter) return subscriptions
  const lower = filter.toLowerCase()
  return subscriptions.filter(
    (subscription) =>
      subscription.organizationName.toLowerCase().includes(lower) ||
      subscription.plan.toLowerCase().includes(lower) ||
      subscription.status.toLowerCase().includes(lower),
  )
}

function availableBillingTiers(currentPlans: BillingPlan[]): string[] {
  const fromPlans = currentPlans.map((plan) => plan.tier.tierName)
  const combined = new Set([...KNOWN_TIERS, ...fromPlans])
  return Array.from(combined).sort((a, b) => a.localeCompare(b))
}

function numberFromText(value: string): number | null {
  if (!value.trim()) return null
  return Number(value)
}

function buildDraftPricingTier(
  createTier: string,
  createForm: CreateFormState,
  currentTierConfig: BillingTierConfig | undefined,
): PricingCardTierInput | null {
  if (!currentTierConfig) return null
  return {
    tierName: createTier,
    monthlyPriceCents: createForm.monthlyPriceCents,
    yearlyPriceCents: createForm.yearlyPriceCents,
    trialDays: createForm.trialDays,
    monthlyGbLimit: Math.max(0, Math.round(createForm.monthlyGbLimitGb * BYTES_PER_GB)),
    monthlyLlmEventLimit: createForm.monthlyLlmEventLimit,
    retentionDays: createForm.retentionDays,
    apmTraceRetentionDays: createForm.apmTraceRetentionDays,
    maxProjects: numberFromText(createForm.maxProjects),
    maxSystems: createForm.maxSystems,
    monitorIntervalSeconds: createForm.monitorIntervalSeconds,
    sessionReplayEnabled: currentTierConfig.sessionReplayEnabled,
    statusPagesEnabled: currentTierConfig.statusPagesEnabled,
    statusPageCustomDomainEnabled: currentTierConfig.statusPageCustomDomainEnabled,
    slackEnabled: currentTierConfig.slackEnabled,
    discordEnabled: currentTierConfig.discordEnabled,
    incidentIoEnabled: currentTierConfig.incidentIoEnabled,
    samlEnabled: currentTierConfig.samlEnabled,
    oidcEnabled: currentTierConfig.oidcEnabled,
    prioritySupportEnabled: currentTierConfig.prioritySupportEnabled,
    slaEnabled: currentTierConfig.slaEnabled,
    customRetentionEnabled: currentTierConfig.customRetentionEnabled,
    oncallEnabled: createForm.oncallEnabled,
    oncallPerUserMonthlyCents: createForm.oncallPerUserMonthlyCents,
    maxAnalyticsSites: numberFromText(createForm.maxAnalyticsSites),
    analyticsRetentionDays: createForm.analyticsRetentionDays,
    monthlyAnalyticsPageviewLimit: createForm.monthlyAnalyticsPageviewLimit,
    analyticsPageviewOverageRateCentsPer100k: createForm.analyticsPageviewOverageRateCentsPer100k,
  }
}

function mergePreviewTier(
  tier: PricingCardTierInput,
  createTier: string,
  draftTier: PricingCardTierInput | null,
  plansByTier: Map<string, BillingPlan>,
): PricingCardTierInput {
  if (tier.tierName === createTier && draftTier) return draftTier
  return {
    ...tier,
    trialDays: plansByTier.get(tier.tierName)?.trialDays ?? tier.trialDays,
  }
}

function buildPreviewCards(
  currentPlans: BillingPlan[],
  createTier: string,
  createForm: CreateFormState,
  currentTierConfig: BillingTierConfig | undefined,
  previewInterval: BillingInterval,
) {
  const plansByTier = new Map(currentPlans.map((plan) => [plan.tier.tierName, plan]))
  const draftTier = buildDraftPricingTier(createTier, createForm, currentTierConfig)

  return currentPlans
    .map((plan) => mergePreviewTier(plan.tier, createTier, draftTier, plansByTier))
    .sort((a, b) => (TIER_ORDER[a.tierName] ?? 99) - (TIER_ORDER[b.tierName] ?? 99))
    .map((tier) => buildPricingCardModel(tier, previewInterval))
}

export const adminBillingHelperTestHooks = {
  DEFAULT_FORM,
  EMPTY_UPDATE_PRICE_FORM,
  availableBillingTiers,
  buildPreviewCards,
  buildUpdatePriceForm,
  filterSubscriptionsByText,
  numberFromText,
  resolveCreateForm,
  resolveUpdatePriceForm,
  tierVersionKey,
  toBillingPlans,
  toBillingTierConfigs,
}

// ─── Main Page ────────────────────────────────────────────────────────────────

function AdminBillingPage() {
  const queryClient = useQueryClient()
  const {toast} = useToast()
  const {timezone} = useTimezone()

  // Separate tier selectors for create vs. migrate sections
  const [createTier, setCreateTier] = useState('PRO')
  const [migrateTier, setMigrateTier] = useState('PRO')
  const [updateTier, setUpdateTier] = useState('PRO')
  const [updateVersion, setUpdateVersion] = useState<number | ''>('')
  const [targetVersion, setTargetVersion] = useState<number | ''>('')
  const [createFormState, setCreateFormState] = useState<{
    tierVersion: string | undefined
    data: CreateFormState
  }>({tierVersion: undefined, data: DEFAULT_FORM})
  const [previewInterval, setPreviewInterval] = useState<BillingInterval>('monthly')
  const [updatePriceFormState, setUpdatePriceFormState] = useState<{
    tierVersion: string | undefined
    data: UpdatePriceFormData
  }>({
    tierVersion: undefined,
    data: EMPTY_UPDATE_PRICE_FORM,
  })
  const [showCreateConfirm, setShowCreateConfirm] = useState(false)
  const [showMigrateConfirm, setShowMigrateConfirm] = useState(false)
  const [dryRunResult, setDryRunResult] = useState<{affected: number; version: number} | null>(null)
  const [subFilter, setSubFilter] = useState('')

  // ─── Queries ──────────────────────────────────────────────────────────────

  const {data: currentPlansRaw, isLoading: plansLoading} = useQuery({
    queryKey: ['admin-billing-current-plans'],
    queryFn: () => api.getAdminBillingTiers(),
  })

  const {data: createTierVersionsRaw} = useQuery({
    queryKey: ['admin-billing-tier-versions', createTier],
    queryFn: () => api.getAdminBillingTiers(createTier),
  })

  const {data: migrateTierVersionsRaw} = useQuery({
    queryKey: ['admin-billing-tier-versions', migrateTier],
    queryFn: () => api.getAdminBillingTiers(migrateTier),
  })

  const {data: updateTierVersionsRaw} = useQuery({
    queryKey: ['admin-billing-tier-versions', updateTier],
    queryFn: () => api.getAdminBillingTiers(updateTier),
  })

  const {data: subscriptions = [], isLoading: subscriptionsLoading} = useQuery({
    queryKey: ['admin-billing-subscriptions'],
    queryFn: () => api.getAdminBillingSubscriptions(250),
  })

  // ─── Derived Data ─────────────────────────────────────────────────────────

  const currentPlans = useMemo(() => toBillingPlans(currentPlansRaw), [currentPlansRaw])

  const createTierVersions = useMemo(
    () => toBillingTierConfigs(createTierVersionsRaw),
    [createTierVersionsRaw],
  )

  const migrateTierVersions = useMemo(
    () => toBillingTierConfigs(migrateTierVersionsRaw),
    [migrateTierVersionsRaw],
  )

  const currentTierConfig = useMemo(
    () => createTierVersions.find((v) => v.isCurrent),
    [createTierVersions],
  )

  const currentTierVersion = tierVersionKey(currentTierConfig)
  const createForm = resolveCreateForm(createFormState, currentTierVersion, currentTierConfig)
  const setCreateForm = (updater: CreateFormState | ((prev: CreateFormState) => CreateFormState)) => {
    setCreateFormState({
      tierVersion: currentTierVersion,
      data: typeof updater === 'function' ? updater(createForm) : updater,
    })
  }

  const targetTierConfig = useMemo(
    () => migrateTierVersions.find((v) => v.version === Number(targetVersion)),
    [migrateTierVersions, targetVersion],
  )

  const targetTierForm = useMemo(
    () => (targetTierConfig ? buildCreateFormFromConfig(targetTierConfig) : null),
    [targetTierConfig],
  )

  const currentMigrateTierConfig = useMemo(
    () => migrateTierVersions.find((v) => v.isCurrent),
    [migrateTierVersions],
  )

  const updateTierVersions = useMemo(
    () => toBillingTierConfigs(updateTierVersionsRaw),
    [updateTierVersionsRaw],
  )

  const selectedUpdateTierConfig = useMemo(
    () => updateTierVersions.find((v) => v.version === Number(updateVersion)),
    [updateTierVersions, updateVersion],
  )

  const currentUpdateTierVersion = selectedUpdateTierConfig?.version.toString()
  const updatePriceForm = resolveUpdatePriceForm(
    updatePriceFormState,
    currentUpdateTierVersion,
    selectedUpdateTierConfig,
  )
  const setUpdatePriceForm = (updater: UpdatePriceFormData | ((prev: UpdatePriceFormData) => UpdatePriceFormData)) => {
    setUpdatePriceFormState({
      tierVersion: currentUpdateTierVersion,
      data: typeof updater === 'function' ? updater(updatePriceForm) : updater,
    })
  }

  const filteredSubscriptions = useMemo(
    () => filterSubscriptionsByText(subscriptions, subFilter),
    [subscriptions, subFilter],
  )

  const validationErrors = useMemo(() => validateCreateForm(createForm), [createForm])
  const hasValidationErrors = Object.keys(validationErrors).length > 0

  // Unique tier names from current plans for the dropdown
  const availableTiers = useMemo(() => availableBillingTiers(currentPlans), [currentPlans])

  const previewCards = useMemo(() => {
    return buildPreviewCards(currentPlans, createTier, createForm, currentTierConfig, previewInterval)
  }, [createForm, createTier, currentPlans, currentTierConfig, previewInterval])

  // ─── Mutations ────────────────────────────────────────────────────────────

  const createVersionMutation = useMutation({
    mutationFn: () =>
      api.createAdminBillingTierVersion(createTier, buildCreateTierVersionRequest(createForm)),
    onSuccess: (tier) => {
      queryClient.invalidateQueries({queryKey: ['admin-billing-current-plans']})
      queryClient.invalidateQueries({queryKey: ['admin-billing-tier-versions', createTier]})
      setShowCreateConfirm(false)
      toast({title: `${createTier} v${tier.version} created successfully`})
    },
    onError: (err: Error) => {
      toast({title: 'Failed to create version', description: err.message, variant: 'destructive'})
    },
  })

  const dryRunMutation = useMutation({
    mutationFn: () => api.migrateAdminBillingTier(migrateTier, Number(targetVersion), true),
    onSuccess: (res) => {
      setDryRunResult({affected: res.affectedSubscriptions, version: res.targetVersion})
      toast({
        title: 'Dry run complete',
        description: `${res.affectedSubscriptions} subscription(s) would be migrated to v${res.targetVersion}`,
      })
    },
    onError: (err: Error) => {
      setDryRunResult(null)
      toast({title: 'Dry run failed', description: err.message, variant: 'destructive'})
    },
  })

  const executeMigrationMutation = useMutation({
    mutationFn: () => api.migrateAdminBillingTier(migrateTier, Number(targetVersion), false),
    onSuccess: (res) => {
      queryClient.invalidateQueries({queryKey: ['admin-billing-subscriptions']})
      queryClient.invalidateQueries({queryKey: ['admin-billing-tier-versions', migrateTier]})
      setShowMigrateConfirm(false)
      setDryRunResult(null)
      toast({
        title: 'Migration complete',
        description: `${res.affectedSubscriptions} subscription(s) migrated to v${res.targetVersion}`,
      })
    },
    onError: (err: Error) => {
      toast({title: 'Migration failed', description: err.message, variant: 'destructive'})
    },
  })

  const updatePriceIdsMutation = useMutation({
    mutationFn: () =>
      api.updateAdminBillingTierPriceIds(updateTier, Number(updateVersion), {
        stripeBasePriceId: updatePriceForm.stripeBasePriceId.trim() || null,
        stripeOveragePriceId: updatePriceForm.stripeOveragePriceId.trim() || null,
        stripeYearlyBasePriceId: updatePriceForm.stripeYearlyBasePriceId.trim() || null,
        stripeYearlyOveragePriceId: updatePriceForm.stripeYearlyOveragePriceId.trim() || null,
        stripeOncallPriceId: updatePriceForm.stripeOncallPriceId.trim() || null,
        stripeOncallYearlyPriceId: updatePriceForm.stripeOncallYearlyPriceId.trim() || null,
      }),
    onSuccess: () => {
      queryClient.invalidateQueries({queryKey: ['admin-billing-tier-versions', updateTier]})
      queryClient.invalidateQueries({queryKey: ['admin-billing-current-plans']})
      toast({
        title: 'Price IDs updated',
        description: `Successfully updated Stripe price IDs for ${updateTier} v${updateVersion}`,
      })
    },
    onError: (err: Error) => {
      toast({title: 'Update failed', description: err.message, variant: 'destructive'})
    },
  })

  // ─── Loading State ────────────────────────────────────────────────────────

  if (plansLoading || subscriptionsLoading) {
    return <AdminSkeleton />
  }

  // ─── Render ───────────────────────────────────────────────────────────────

  return (
    <TooltipProvider delayDuration={200}>
      <div className="space-y-6">
        <SectionHeader
          title="Billing"
          description="Manage pricing tier versions and subscriber migrations."
        />

        {/* ── Current Plan Configs ─────────────────────────────────────────── */}

        <Card>
          <CardHeader>
            <CardTitle>Current Plan Configs</CardTitle>
            <CardDescription>
              The active pricing configuration for each tier. New subscriptions and renewals use these configs.
            </CardDescription>
          </CardHeader>
          <CardContent>
            {currentPlans.length === 0 ? (
              <p className="text-sm text-muted-foreground">No plans configured yet.</p>
            ) : (
              <Table>
                <TableHeader>
                  <TableRow>
                    <TableHead>Tier</TableHead>
                    <TableHead>Version</TableHead>
                    <TableHead>Price</TableHead>
                    <TableHead>Total Limit</TableHead>
                    <TableHead>Errors</TableHead>
                    <TableHead>Transactions</TableHead>
                    <TableHead>Replays</TableHead>
                    <TableHead>Feedback</TableHead>
                    <TableHead>LLM</TableHead>
                    <TableHead>Retention</TableHead>
                    <TableHead>Max Systems</TableHead>
                    <TableHead>Interval</TableHead>
                    <TableHead>PAYG</TableHead>
                    <TableHead>Status</TableHead>
                  </TableRow>
                </TableHeader>
                <TableBody>
                  {currentPlans.map((plan) => (
                    <TableRow key={plan.tier.id}>
                      <TableCell className="font-medium">
                        <PlanBadge plan={plan.tier.tierName} />
                      </TableCell>
                      <TableCell>v{plan.tier.version}</TableCell>
                      <TableCell>${centsToDollars(plan.tier.monthlyPriceCents)}/mo</TableCell>
                      <TableCell>{formatQuotaLimit(plan.tier.monthlyUnitLimit)}</TableCell>
                      <TableCell>{formatQuotaLimit(plan.tier.monthlyErrorLimit)}</TableCell>
                      <TableCell>{formatQuotaLimit(plan.tier.monthlyTransactionLimit)}</TableCell>
                      <TableCell>{formatQuotaLimit(plan.tier.monthlyReplayLimit)}</TableCell>
                      <TableCell>{formatQuotaLimit(plan.tier.monthlyFeedbackLimit)}</TableCell>
                      <TableCell>{formatQuotaLimit(plan.tier.monthlyLlmEventLimit ?? 0)}</TableCell>
                      <TableCell className="max-w-[240px] text-xs leading-5">
                        {retentionSummary(plan.tier)}
                      </TableCell>
                      <TableCell>{plan.tier.maxSystems}</TableCell>
                      <TableCell>{formatInterval(plan.tier.monitorIntervalSeconds)}</TableCell>
                      <TableCell>
                        {plan.tier.paygEnabled ? (
                          <Badge variant="success">
                            Enabled
                          </Badge>
                        ) : (
                          <Badge variant="secondary">Off</Badge>
                        )}
                      </TableCell>
                      <TableCell>
                        <Badge variant={plan.tier.isCurrent ? 'default' : 'secondary'}>
                          {plan.tier.isCurrent ? 'current' : 'legacy'}
                        </Badge>
                      </TableCell>
                    </TableRow>
                  ))}
                </TableBody>
              </Table>
            )}
          </CardContent>
        </Card>

        {/* ── Create New Tier Version ──────────────────────────────────────── */}

        <Card>
          <CardHeader>
            <CardTitle>Create New Tier Version</CardTitle>
            <CardDescription>
              Creates a new version of a pricing tier and marks it as the current config.
              The previous version becomes &ldquo;legacy&rdquo; but existing subscribers
              keep their config until migrated.
            </CardDescription>
          </CardHeader>
          <CardContent className="space-y-6">
            {/* Tier selector */}
            <div className="space-y-1.5 max-w-xs">
              <Label>
                Tier
                <HelpTip text="Select which pricing tier to create a new version for. The form will pre-fill with the current version's values." />
              </Label>
              <Select value={createTier} onValueChange={setCreateTier}>
                <SelectTrigger>
                  <SelectValue />
                </SelectTrigger>
                <SelectContent>
                  {availableTiers.map((t) => (
                    <SelectItem key={t} value={t}>
                      {t}
                    </SelectItem>
                  ))}
                </SelectContent>
              </Select>
              {currentTierConfig && (
                <p className="text-xs text-muted-foreground">
                  Current version: v{currentTierConfig.version} &mdash; form pre-filled with its values
                </p>
              )}
            </div>

            <Separator />

            {/* Event Limits Section Header */}
            <div className="bg-muted/30 border border-border rounded-md p-3">
              <div className="flex items-start gap-2">
                <Info className="h-4 w-4 text-info-fg mt-0.5 flex-shrink-0" />
                <div className="text-xs text-muted-foreground">
                  <strong>Event limits are for internal abuse prevention only.</strong> Stripe metering currently bills non-LLM/non-log overage in unit-based streams.
                  Keep these limits aligned with your Stripe overage policy (use -1 for unlimited replay sessions).
                </div>
              </div>
            </div>

            {/* Form fields */}
            <Tabs defaultValue="limits" className="w-full">
              <TabsList className="grid w-full grid-cols-4 mb-4">
                <TabsTrigger value="limits">Limits & Retention</TabsTrigger>
                <TabsTrigger value="pricing">Pricing & Specs</TabsTrigger>
                <TabsTrigger value="overage">Add-ons & Overage</TabsTrigger>
                <TabsTrigger value="stripe">Stripe IDs</TabsTrigger>
              </TabsList>

              <TabsContent value="limits" className="space-y-4">
                <div className="grid gap-4 sm:grid-cols-2">
                  {/* Errors */}
                  <div className="space-y-1.5">
                    <Label htmlFor="monthlyErrorLimit">
                      Error Limit (Internal)
                      <HelpTip text="Internal abuse limit. Not advertised. Set high for paid tiers." />
                    </Label>
                    <Input
                      id="monthlyErrorLimit"
                      type="number"
                      min={0}
                      max={100_000_000}
                      value={createForm.monthlyErrorLimit}
                      onChange={(e) =>
                        setCreateForm((p) => ({...p, monthlyErrorLimit: Number(e.target.value)}))
                      }
                    />
                    {validationErrors.monthlyErrorLimit && (
                      <p className="text-xs text-destructive">{validationErrors.monthlyErrorLimit}</p>
                    )}
                  </div>
                  <div className="space-y-1.5">
                    <Label htmlFor="retentionDays">
                      Error Retention (days)
                      <HelpTip text="How long error/event data is stored before automatic deletion." />
                    </Label>
                    <Input
                      id="retentionDays"
                      type="number"
                      min={1}
                      max={90}
                      value={createForm.retentionDays}
                      onChange={(e) =>
                        setCreateForm((p) => ({...p, retentionDays: Number(e.target.value)}))
                      }
                    />
                    {validationErrors.retentionDays && (
                      <p className="text-xs text-destructive">{validationErrors.retentionDays}</p>
                    )}
                  </div>

                  {/* Replays */}
                  <div className="space-y-1.5">
                    <Label htmlFor="monthlyReplayLimit">
                      Replay Limit (Internal)
                      <HelpTip text="Internal abuse limit. Not advertised. Set high for paid tiers." />
                    </Label>
                    <Input
                      id="monthlyReplayLimit"
                      type="number"
                      min={-1}
                      max={100_000_000}
                      value={createForm.monthlyReplayLimit}
                      onChange={(e) =>
                        setCreateForm((p) => ({...p, monthlyReplayLimit: Number(e.target.value)}))
                      }
                    />
                    {validationErrors.monthlyReplayLimit && (
                      <p className="text-xs text-destructive">{validationErrors.monthlyReplayLimit}</p>
                    )}
                  </div>
                  <div className="space-y-1.5">
                    <Label htmlFor="replayRetentionDays">Replay Retention (days)</Label>
                    <Input
                      id="replayRetentionDays"
                      type="number"
                      min={1}
                      max={90}
                      value={createForm.replayRetentionDays}
                      onChange={(e) =>
                        setCreateForm((p) => ({...p, replayRetentionDays: Number(e.target.value)}))
                      }
                    />
                    {validationErrors.replayRetentionDays && (
                      <p className="text-xs text-destructive">{validationErrors.replayRetentionDays}</p>
                    )}
                  </div>

                  {/* LLM */}
                  <div className="space-y-1.5">
                    <Label htmlFor="monthlyLlmEventLimit">
                      LLM Event Limit
                      <HelpTip text="Monthly limit for AI observability events (LLM generations). Customer-facing." />
                    </Label>
                    <Input
                      id="monthlyLlmEventLimit"
                      type="number"
                      min={0}
                      max={1_000_000_000}
                      value={createForm.monthlyLlmEventLimit}
                      onChange={(e) =>
                        setCreateForm((p) => ({...p, monthlyLlmEventLimit: Number(e.target.value)}))
                      }
                    />
                    <FieldHint>
                      {formatQuotaLimit(createForm.monthlyLlmEventLimit)} AI observability events/month
                    </FieldHint>
                    {validationErrors.monthlyLlmEventLimit && (
                      <p className="text-xs text-destructive">{validationErrors.monthlyLlmEventLimit}</p>
                    )}
                  </div>
                  <div className="space-y-1.5">
                    <Label htmlFor="llmRetentionDays">LLM Retention (days)</Label>
                    <Input
                      id="llmRetentionDays"
                      type="number"
                      min={1}
                      max={90}
                      value={createForm.llmRetentionDays}
                      onChange={(e) =>
                        setCreateForm((p) => ({...p, llmRetentionDays: Number(e.target.value)}))
                      }
                    />
                    {validationErrors.llmRetentionDays && (
                      <p className="text-xs text-destructive">{validationErrors.llmRetentionDays}</p>
                    )}
                  </div>

                  <div className="space-y-1.5">
                    <Label htmlFor="apmTraceRetentionDays">
                      APM Trace Retention (days)
                      <HelpTip text="How long stored APM spans and trace resource stats are retained for this tier." />
                    </Label>
                    <Input
                      id="apmTraceRetentionDays"
                      type="number"
                      min={1}
                      max={90}
                      value={createForm.apmTraceRetentionDays}
                      onChange={(e) =>
                        setCreateForm((p) => ({...p, apmTraceRetentionDays: Number(e.target.value)}))
                      }
                    />
                    {validationErrors.apmTraceRetentionDays && (
                      <p className="text-xs text-destructive">{validationErrors.apmTraceRetentionDays}</p>
                    )}
                  </div>

                  {/* Logs / Data */}
                  <div className="space-y-1.5">
                    <Label htmlFor="monthlyGbLimitGb">
                      Monthly Data Limit (GB)
                      <HelpTip text="Customer-facing monthly GB quota used in pricing cards and quota enforcement." />
                    </Label>
                    <Input
                      id="monthlyGbLimitGb"
                      type="number"
                      min={0}
                      step={1}
                      value={createForm.monthlyGbLimitGb}
                      onChange={(e) =>
                        setCreateForm((p) => ({...p, monthlyGbLimitGb: Number(e.target.value)}))
                      }
                    />
                    {validationErrors.monthlyGbLimitGb && (
                      <p className="text-xs text-destructive">{validationErrors.monthlyGbLimitGb}</p>
                    )}
                  </div>
                  <div className="space-y-1.5">
                    <Label htmlFor="logRetentionDays">Log Retention (days)</Label>
                    <Input
                      id="logRetentionDays"
                      type="number"
                      min={1}
                      max={90}
                      value={createForm.logRetentionDays}
                      onChange={(e) =>
                        setCreateForm((p) => ({...p, logRetentionDays: Number(e.target.value)}))
                      }
                    />
                    {validationErrors.logRetentionDays && (
                      <p className="text-xs text-destructive">{validationErrors.logRetentionDays}</p>
                    )}
                  </div>

                  {/* Other limits */}
                  <div className="space-y-1.5">
                    <Label htmlFor="monthlyTransactionLimit">
                      Transaction Limit (Internal)
                      <HelpTip text="Internal abuse limit. Not advertised. Set high for paid tiers." />
                    </Label>
                    <Input
                      id="monthlyTransactionLimit"
                      type="number"
                      min={0}
                      max={100_000_000}
                      value={createForm.monthlyTransactionLimit}
                      onChange={(e) =>
                        setCreateForm((p) => ({...p, monthlyTransactionLimit: Number(e.target.value)}))
                      }
                    />
                    {validationErrors.monthlyTransactionLimit && (
                      <p className="text-xs text-destructive">{validationErrors.monthlyTransactionLimit}</p>
                    )}
                  </div>
                  <div className="space-y-1.5">
                    <Label htmlFor="monthlyFeedbackLimit">
                      Feedback Limit (Internal)
                      <HelpTip text="Internal abuse limit. Not advertised. Set high for paid tiers." />
                    </Label>
                    <Input
                      id="monthlyFeedbackLimit"
                      type="number"
                      min={0}
                      max={100_000_000}
                      value={createForm.monthlyFeedbackLimit}
                      onChange={(e) =>
                        setCreateForm((p) => ({...p, monthlyFeedbackLimit: Number(e.target.value)}))
                      }
                    />
                    <FieldHint>
                      Total base limit:{' '}
                      {formatTotalQuotaLimit([
                        createForm.monthlyErrorLimit,
                        createForm.monthlyTransactionLimit,
                        createForm.monthlyReplayLimit,
                        createForm.monthlyFeedbackLimit,
                      ])}{' '}
                      units/month
                    </FieldHint>
                    {validationErrors.monthlyFeedbackLimit && (
                      <p className="text-xs text-destructive">{validationErrors.monthlyFeedbackLimit}</p>
                    )}
                  </div>
                </div>
              </TabsContent>

              <TabsContent value="pricing" className="space-y-4">
                <div className="grid gap-4 sm:grid-cols-2">

              {/* Pricing */}
              <div className="space-y-1.5">
                <Label htmlFor="monthlyPriceCents">
                  Monthly Price
                  <HelpTip text="The base subscription price in US dollars charged monthly via Stripe." />
                </Label>
                <div className="relative">
                  <span className="absolute left-3 top-1/2 -translate-y-1/2 text-sm text-muted-foreground">$</span>
                  <Input
                    id="monthlyPriceCents"
                    type="number"
                    min={0}
                    step={100}
                    className="pl-7"
                    value={createForm.monthlyPriceCents}
                    onChange={(e) =>
                      setCreateForm((p) => ({...p, monthlyPriceCents: Number(e.target.value)}))
                    }
                  />
                </div>
                <FieldHint>
                  ${centsToDollars(createForm.monthlyPriceCents)} / month ({createForm.monthlyPriceCents} cents)
                </FieldHint>
                {validationErrors.monthlyPriceCents && (
                  <p className="text-xs text-destructive">{validationErrors.monthlyPriceCents}</p>
                )}
              </div>
              <div className="space-y-1.5">
                <Label htmlFor="yearlyPriceCents">
                  Yearly Price
                  <HelpTip text="Total yearly subscription price in US dollars charged once per year." />
                </Label>
                <div className="relative">
                  <span className="absolute left-3 top-1/2 -translate-y-1/2 text-sm text-muted-foreground">$</span>
                  <Input
                    id="yearlyPriceCents"
                    type="number"
                    min={0}
                    step={100}
                    className="pl-7"
                    value={createForm.yearlyPriceCents}
                    onChange={(e) =>
                      setCreateForm((p) => ({...p, yearlyPriceCents: Number(e.target.value)}))
                    }
                  />
                </div>
                <FieldHint>
                  ${centsToDollars(createForm.yearlyPriceCents)} / year (
                  ${(createForm.yearlyPriceCents / (100 * 12)).toFixed(0)} / month effective)
                </FieldHint>
                {validationErrors.yearlyPriceCents && (
                  <p className="text-xs text-destructive">{validationErrors.yearlyPriceCents}</p>
                )}
              </div>

              {/* Specs */}
              <div className="space-y-1.5">
                <Label htmlFor="maxProjects">
                  Max Services
                  <HelpTip text="Maximum number of services an organization on this tier can create. Leave blank for unlimited." />
                </Label>
                <Input
                  id="maxProjects"
                  type="text"
                  placeholder="Unlimited"
                  value={createForm.maxProjects}
                  onChange={(e) => {
                    const val = e.target.value.replace(/[^0-9]/g, '')
                    setCreateForm((p) => ({...p, maxProjects: val}))
                  }}
                />
                <FieldHint>{createForm.maxProjects ? `${createForm.maxProjects} services` : 'Unlimited services'}</FieldHint>
              </div>
              <div className="space-y-1.5">
                <Label htmlFor="maxSystems">
                  Max Systems
                  <HelpTip text="Maximum number of monitored systems (servers, containers, etc.) allowed on this tier." />
                </Label>
                <Input
                  id="maxSystems"
                  type="number"
                  min={1}
                  value={createForm.maxSystems}
                  onChange={(e) =>
                    setCreateForm((p) => ({...p, maxSystems: Number(e.target.value)}))
                  }
                />
                {validationErrors.maxSystems && (
                  <p className="text-xs text-destructive">{validationErrors.maxSystems}</p>
                )}
              </div>

              {/* Intervals & Trials */}
              <div className="space-y-1.5">
                <Label htmlFor="monitorIntervalSeconds">
                  Monitor Interval
                  <HelpTip text="How frequently systems are polled for monitoring data. Lower values = more frequent checks = more unit usage." />
                </Label>
                <Input
                  id="monitorIntervalSeconds"
                  type="number"
                  min={5}
                  max={3600}
                  value={createForm.monitorIntervalSeconds}
                  onChange={(e) =>
                    setCreateForm((p) => ({...p, monitorIntervalSeconds: Number(e.target.value)}))
                  }
                />
                <FieldHint>
                  Checks every {formatInterval(createForm.monitorIntervalSeconds)}
                </FieldHint>
                {validationErrors.monitorIntervalSeconds && (
                  <p className="text-xs text-destructive">{validationErrors.monitorIntervalSeconds}</p>
                )}
              </div>
              <div className="space-y-1.5">
                <Label htmlFor="trialDays">
                  Trial Days
                  <HelpTip text="Days shown in CTA for paid plans and used for checkout trial defaults." />
                </Label>
                <Input
                  id="trialDays"
                  type="number"
                  min={0}
                  max={60}
                  value={createForm.trialDays}
                  onChange={(e) =>
                    setCreateForm((p) => ({...p, trialDays: Number(e.target.value)}))
                  }
                />
                {validationErrors.trialDays && (
                  <p className="text-xs text-destructive">{validationErrors.trialDays}</p>
                )}
              </div>
            </div>
            </TabsContent>

            <TabsContent value="overage" className="space-y-4">
              {/* PAYG Section */}
              <div className="space-y-4">
              <div className="flex items-center gap-3">
                <Switch
                  id="paygEnabled"
                  checked={createForm.paygEnabled}
                  onCheckedChange={(checked) =>
                    setCreateForm((p) => ({...p, paygEnabled: checked}))
                  }
                />
                <Label htmlFor="paygEnabled" className="cursor-pointer">
                  Enable Pay-As-You-Go (PAYG) overage
                  <HelpTip text="When enabled, subscribers can set a budget to continue using units beyond their base limit at a per-unit rate." />
                </Label>
              </div>

              {createForm.paygEnabled && (
                <div className="grid gap-4 sm:grid-cols-2 pl-4 border-l-2 border-muted">
                  <div className="space-y-1.5">
                    <Label htmlFor="paygRate">
                      PAYG Rate
                      <HelpTip text="Cost per overage unit in microdollars (1 micro = $0.000001). For example, 10 micros/unit means $0.00001 per unit." />
                    </Label>
                    <Input
                      id="paygRate"
                      type="number"
                      min={1}
                      value={createForm.paygRateMicrosPerUnit}
                      onChange={(e) =>
                        setCreateForm((p) => ({...p, paygRateMicrosPerUnit: Number(e.target.value)}))
                      }
                    />
                    <FieldHint>
                      {createForm.paygRateMicrosPerUnit} micros/unit = ${microsToDollars(createForm.paygRateMicrosPerUnit)}/unit
                    </FieldHint>
                    {validationErrors.paygRateMicrosPerUnit && (
                      <p className="text-xs text-destructive">{validationErrors.paygRateMicrosPerUnit}</p>
                    )}
                  </div>
                </div>
              )}
            </div>

            {/* Per-Type Overage Rates */}
            <div className="space-y-4">
              <p className="text-sm font-medium">Per-Type Overage Rates</p>
              <div className="grid gap-4 sm:grid-cols-2">
                <div className="space-y-1.5">
                  <Label htmlFor="overageRateCentsPerGb">
                    Log Overage (cents/GB)
                    <HelpTip text="Cost per GB of log data over the included limit." />
                  </Label>
                  <Input
                    id="overageRateCentsPerGb"
                    type="number"
                    min={0}
                    value={createForm.overageRateCentsPerGb}
                    onChange={(e) =>
                      setCreateForm((p) => ({...p, overageRateCentsPerGb: Number(e.target.value)}))
                    }
                  />
                  <FieldHint>${(createForm.overageRateCentsPerGb / 100).toFixed(2)}/GB</FieldHint>
                </div>
                <div className="space-y-1.5">
                  <Label htmlFor="errorOverageRateCentsPer1k">
                    Error Overage (cents/1K)
                    <HelpTip text="Cost per 1,000 errors over the included limit." />
                  </Label>
                  <Input
                    id="errorOverageRateCentsPer1k"
                    type="number"
                    min={0}
                    value={createForm.errorOverageRateCentsPer1k}
                    onChange={(e) =>
                      setCreateForm((p) => ({...p, errorOverageRateCentsPer1k: Number(e.target.value)}))
                    }
                  />
                  <FieldHint>${(createForm.errorOverageRateCentsPer1k / 100).toFixed(2)}/1K errors</FieldHint>
                  {validationErrors.errorOverageRateCentsPer1k && (
                    <p className="text-xs text-destructive">{validationErrors.errorOverageRateCentsPer1k}</p>
                  )}
                </div>
                <div className="space-y-1.5">
                  <Label htmlFor="replayOverageRateCentsPerGb">
                    Replay Overage (cents/GB)
                    <HelpTip text="Cost per GB of replay data over the included limit." />
                  </Label>
                  <Input
                    id="replayOverageRateCentsPerGb"
                    type="number"
                    min={0}
                    value={createForm.replayOverageRateCentsPerGb}
                    onChange={(e) =>
                      setCreateForm((p) => ({...p, replayOverageRateCentsPerGb: Number(e.target.value)}))
                    }
                  />
                  <FieldHint>${(createForm.replayOverageRateCentsPerGb / 100).toFixed(2)}/GB</FieldHint>
                  {validationErrors.replayOverageRateCentsPerGb && (
                    <p className="text-xs text-destructive">{validationErrors.replayOverageRateCentsPerGb}</p>
                  )}
                </div>
                <div className="space-y-1.5">
                  <Label htmlFor="llmOverageRateCentsPer1k">
                    LLM Overage (cents/1K)
                    <HelpTip text="Cost per 1,000 AI observability events over the included limit." />
                  </Label>
                  <Input
                    id="llmOverageRateCentsPer1k"
                    type="number"
                    min={0}
                    value={createForm.llmOverageRateCentsPer1k}
                    onChange={(e) =>
                      setCreateForm((p) => ({...p, llmOverageRateCentsPer1k: Number(e.target.value)}))
                    }
                  />
                  <FieldHint>${(createForm.llmOverageRateCentsPer1k / 100).toFixed(2)}/1K events</FieldHint>
                  {validationErrors.llmOverageRateCentsPer1k && (
                    <p className="text-xs text-destructive">{validationErrors.llmOverageRateCentsPer1k}</p>
                  )}
                </div>
              </div>
            </div>

            {/* On-Call Pricing */}
            <div className="space-y-4">
              <h4 className="text-sm font-medium text-muted-foreground">Analytics</h4>
              <div className="grid gap-4 sm:grid-cols-2">
                <div className="space-y-1.5">
                  <Label htmlFor="maxAnalyticsSites">
                    Max Analytics Sites
                    <HelpTip text="Maximum number of analytics sites. Leave blank for unlimited." />
                  </Label>
                  <Input
                    id="maxAnalyticsSites"
                    type="number"
                    min={1}
                    placeholder="Unlimited"
                    value={createForm.maxAnalyticsSites}
                    onChange={(e) =>
                      setCreateForm((p) => ({...p, maxAnalyticsSites: e.target.value}))
                    }
                  />
                  <FieldHint>{createForm.maxAnalyticsSites.trim() ? `${createForm.maxAnalyticsSites} site(s)` : 'Unlimited'}</FieldHint>
                </div>
                <div className="space-y-1.5">
                  <Label htmlFor="analyticsRetentionDays">
                    Analytics Retention (days)
                    <HelpTip text="How long analytics data is retained. 1095 = 3 years, 1825 = 5 years." />
                  </Label>
                  <Input
                    id="analyticsRetentionDays"
                    type="number"
                    min={1}
                    max={3650}
                    value={createForm.analyticsRetentionDays}
                    onChange={(e) =>
                      setCreateForm((p) => ({...p, analyticsRetentionDays: Number(e.target.value)}))
                    }
                  />
                  <FieldHint>{(createForm.analyticsRetentionDays / 365).toFixed(1)} year(s)</FieldHint>
                </div>
                <div className="space-y-1.5">
                  <Label htmlFor="monthlyAnalyticsPageviewLimit">
                    Monthly Pageview Limit
                    <HelpTip text="Included monthly analytics page views before overage kicks in." />
                  </Label>
                  <Input
                    id="monthlyAnalyticsPageviewLimit"
                    type="number"
                    min={0}
                    value={createForm.monthlyAnalyticsPageviewLimit}
                    onChange={(e) =>
                      setCreateForm((p) => ({...p, monthlyAnalyticsPageviewLimit: Number(e.target.value)}))
                    }
                  />
                  <FieldHint>{formatQuotaLimit(createForm.monthlyAnalyticsPageviewLimit)} pageviews/mo</FieldHint>
                </div>
                <div className="space-y-1.5">
                  <Label htmlFor="analyticsPageviewOverageRateCentsPer100k">
                    Pageview Overage (cents/100K)
                    <HelpTip text="Cost per 100,000 page views over the included limit. 1000 = $10/100K." />
                  </Label>
                  <Input
                    id="analyticsPageviewOverageRateCentsPer100k"
                    type="number"
                    min={0}
                    value={createForm.analyticsPageviewOverageRateCentsPer100k}
                    onChange={(e) =>
                      setCreateForm((p) => ({...p, analyticsPageviewOverageRateCentsPer100k: Number(e.target.value)}))
                    }
                  />
                  <FieldHint>${(createForm.analyticsPageviewOverageRateCentsPer100k / 100).toFixed(2)}/100K pageviews</FieldHint>
                </div>
              </div>
            </div>

            {/* On-Call Pricing */}
            <div className="space-y-4">
              <div className="flex items-center gap-3">
                <Switch
                  id="oncallEnabled"
                  checked={createForm.oncallEnabled}
                  onCheckedChange={(checked) =>
                    setCreateForm((p) => ({...p, oncallEnabled: checked}))
                  }
                />
                <Label htmlFor="oncallEnabled" className="cursor-pointer">
                  Enable On-Call
                  <HelpTip text="When enabled, subscribers can add on-call seats at the per-user rate." />
                </Label>
              </div>
              {createForm.oncallEnabled && (
                <div className="grid gap-4 sm:grid-cols-2 pl-4 border-l-2 border-muted">
                  <div className="space-y-1.5">
                    <Label htmlFor="oncallPerUserMonthlyCents">Per-User Monthly (cents)</Label>
                    <Input
                      id="oncallPerUserMonthlyCents"
                      type="number"
                      min={0}
                      value={createForm.oncallPerUserMonthlyCents}
                      onChange={(e) =>
                        setCreateForm((p) => ({...p, oncallPerUserMonthlyCents: Number(e.target.value)}))
                      }
                    />
                    <FieldHint>${(createForm.oncallPerUserMonthlyCents / 100).toFixed(2)}/user/mo</FieldHint>
                    {validationErrors.oncallPerUserMonthlyCents && (
                      <p className="text-xs text-destructive">{validationErrors.oncallPerUserMonthlyCents}</p>
                    )}
                  </div>
                  <div className="space-y-1.5">
                    <Label htmlFor="oncallPerUserYearlyCents">Per-User Yearly (cents)</Label>
                    <Input
                      id="oncallPerUserYearlyCents"
                      type="number"
                      min={0}
                      value={createForm.oncallPerUserYearlyCents}
                      onChange={(e) =>
                        setCreateForm((p) => ({...p, oncallPerUserYearlyCents: Number(e.target.value)}))
                      }
                    />
                    <FieldHint>${(createForm.oncallPerUserYearlyCents / 100).toFixed(2)}/user/yr</FieldHint>
                    {validationErrors.oncallPerUserYearlyCents && (
                      <p className="text-xs text-destructive">{validationErrors.oncallPerUserYearlyCents}</p>
                    )}
                  </div>
                </div>
              )}
            </div>

            </TabsContent>

            <TabsContent value="stripe" className="space-y-4">
              {/* Stripe IDs */}
              <div className="space-y-3">
                <p className="text-sm font-medium flex items-center gap-1">
                  Stripe Configuration
                  <HelpTip text="These IDs link this tier to Stripe products and prices. Find them in your Stripe dashboard under Products > Prices." />
                </p>
                <div className="grid gap-4 sm:grid-cols-2">
                  <div className="space-y-1.5">
                    <Label htmlFor="stripeBasePriceId">Stripe Base Price ID (Monthly)</Label>
                    <Input
                      id="stripeBasePriceId"
                      placeholder="price_..."
                      value={createForm.stripeBasePriceId}
                      onChange={(e) =>
                        setCreateForm((p) => ({...p, stripeBasePriceId: e.target.value}))
                      }
                    />
                    <FieldHint>The Stripe Price ID for the base monthly subscription</FieldHint>
                  </div>
                  <div className="space-y-1.5">
                    <Label htmlFor="stripeOveragePriceId">Stripe Overage Price ID (Monthly)</Label>
                    <Input
                      id="stripeOveragePriceId"
                      placeholder="price_..."
                      value={createForm.stripeOveragePriceId}
                      onChange={(e) =>
                        setCreateForm((p) => ({...p, stripeOveragePriceId: e.target.value}))
                      }
                    />
                    <FieldHint>The Stripe Price ID for metered PAYG overage charges</FieldHint>
                  </div>
                  <div className="space-y-1.5">
                    <Label htmlFor="stripeYearlyBasePriceId">Stripe Base Price ID (Yearly)</Label>
                    <Input
                      id="stripeYearlyBasePriceId"
                      placeholder="price_..."
                      value={createForm.stripeYearlyBasePriceId}
                      onChange={(e) =>
                        setCreateForm((p) => ({...p, stripeYearlyBasePriceId: e.target.value}))
                      }
                    />
                    <FieldHint>The Stripe Price ID for the base yearly subscription</FieldHint>
                  </div>
                  <div className="space-y-1.5">
                    <Label htmlFor="stripeYearlyOveragePriceId">Stripe Overage Price ID (Yearly)</Label>
                    <Input
                      id="stripeYearlyOveragePriceId"
                      placeholder="price_..."
                      value={createForm.stripeYearlyOveragePriceId}
                      onChange={(e) =>
                        setCreateForm((p) => ({...p, stripeYearlyOveragePriceId: e.target.value}))
                      }
                    />
                    <FieldHint>The Stripe Price ID for yearly metered PAYG overage charges</FieldHint>
                  </div>
                  <div className="space-y-1.5">
                    <Label htmlFor="stripeOncallPriceId">Stripe On-Call Price ID (Monthly)</Label>
                    <Input
                      id="stripeOncallPriceId"
                      placeholder="price_..."
                      value={createForm.stripeOncallPriceId}
                      onChange={(e) =>
                        setCreateForm((p) => ({...p, stripeOncallPriceId: e.target.value}))
                      }
                    />
                    <FieldHint>The Stripe Price ID for monthly on-call seats</FieldHint>
                  </div>
                  <div className="space-y-1.5">
                    <Label htmlFor="stripeOncallYearlyPriceId">Stripe On-Call Price ID (Yearly)</Label>
                    <Input
                      id="stripeOncallYearlyPriceId"
                      placeholder="price_..."
                      value={createForm.stripeOncallYearlyPriceId}
                      onChange={(e) =>
                        setCreateForm((p) => ({...p, stripeOncallYearlyPriceId: e.target.value}))
                      }
                    />
                    <FieldHint>The Stripe Price ID for yearly on-call seats</FieldHint>
                  </div>
                </div>
              </div>
            </TabsContent>
            </Tabs>

            <Separator />

            {/* Changes summary vs current version */}
            {currentTierConfig && (
              <ChangeSummary
                current={currentTierConfig}
                form={createForm}
                title={`Changes from current v${currentTierConfig.version}:`}
              />
            )}

            <Separator />

            <div className="space-y-3">
              <div className="flex items-center justify-between">
                <p className="text-sm font-medium">Pricing Preview</p>
                <div className="inline-flex items-center rounded-lg border border-border/60 bg-muted/30 p-1">
                  <button
                    onClick={() => setPreviewInterval('monthly')}
                    className={`relative rounded-md px-3 py-1.5 text-xs font-medium transition-all ${
                      previewInterval === 'monthly'
                        ? 'bg-background text-foreground'
                        : 'text-muted-foreground hover:text-foreground'
                    }`}
                    type="button"
                  >
                    Monthly
                  </button>
                  <button
                    onClick={() => setPreviewInterval('yearly')}
                    className={`relative rounded-md px-3 py-1.5 text-xs font-medium transition-all ${
                      previewInterval === 'yearly'
                        ? 'bg-background text-foreground'
                        : 'text-muted-foreground hover:text-foreground'
                    }`}
                    type="button"
                  >
                    Yearly
                  </button>
                </div>
              </div>
              {previewCards.length === 0 ? (
                <p className="text-xs text-muted-foreground">
                  No current plans available to preview yet.
                </p>
              ) : (
                <PricingPreviewGrid cards={previewCards} interval={previewInterval} />
              )}
            </div>

            {/* Create button */}
            <div className="flex items-center gap-3">
              <Button
                onClick={() => setShowCreateConfirm(true)}
                disabled={createVersionMutation.isPending || hasValidationErrors}
              >
                Review & Create Version
              </Button>
              {hasValidationErrors && (
                <p className="text-sm text-destructive flex items-center gap-1">
                  <AlertTriangle className="h-4 w-4" />
                  Fix validation errors above before creating
                </p>
              )}
            </div>
          </CardContent>
        </Card>

        {/* ── Create Confirmation Dialog ───────────────────────────────────── */}

        <Dialog open={showCreateConfirm} onOpenChange={setShowCreateConfirm}>
          <DialogContent>
            <DialogHeader>
              <DialogTitle>Confirm New Tier Version</DialogTitle>
              <DialogDescription>
                You are about to create a new version of the <strong>{createTier}</strong> tier.
                This will become the active config for new subscriptions.
                {currentTierConfig && (
                  <> The current version (v{currentTierConfig.version}) will be marked as legacy.</>
                )}
              </DialogDescription>
            </DialogHeader>

            <div className="space-y-3 my-2">
              {currentTierConfig ? (
                <ChangeSummary
                  current={currentTierConfig}
                  form={createForm}
                  title="Selected changes:"
                  emptyMessage={`No changes from current v${currentTierConfig.version}.`}
                />
              ) : (
                <CreateConfigSummary tier={createTier} form={createForm} />
              )}
              <div className="flex items-start gap-2 rounded border border-warning-border bg-warning-bg p-3">
                <Info className="h-4 w-4 text-warning-fg mt-0.5 shrink-0" />
                <p className="text-sm text-warning-fg">
                  Existing subscribers will <strong>not</strong> be affected until you run a migration.
                </p>
              </div>
            </div>

            <DialogFooter>
              <Button variant="outline" onClick={() => setShowCreateConfirm(false)}>
                Cancel
              </Button>
              <Button
                onClick={() => createVersionMutation.mutate()}
                disabled={createVersionMutation.isPending}
              >
                {createVersionMutation.isPending ? 'Creating...' : 'Confirm & Create'}
              </Button>
            </DialogFooter>
          </DialogContent>
        </Dialog>

        {/* ── Update Stripe Price IDs ──────────────────────────────────────── */}

        <Card>
          <CardHeader>
            <CardTitle>Update Stripe Price IDs</CardTitle>
            <CardDescription>
              Update the Stripe price IDs for an existing tier version without creating a new version.
              Useful for fixing mistakes or updating price IDs after creating them in Stripe.
            </CardDescription>
          </CardHeader>
          <CardContent className="space-y-4">
            <div className="grid gap-4 sm:grid-cols-2">
              <div className="space-y-1.5">
                <Label>
                  Tier
                  <HelpTip text="Select which tier to update" />
                </Label>
                <Select value={updateTier} onValueChange={(v) => { setUpdateTier(v); setUpdateVersion('') }}>
                  <SelectTrigger>
                    <SelectValue />
                  </SelectTrigger>
                  <SelectContent>
                    {availableTiers.map((t) => (
                      <SelectItem key={t} value={t}>
                        {t}
                      </SelectItem>
                    ))}
                  </SelectContent>
                </Select>
              </div>

              <div className="space-y-1.5">
                <Label>
                  Version
                  <HelpTip text="Select which version to update" />
                </Label>
                {updateTierVersions.length > 0 ? (
                  <Select
                    value={updateVersion !== '' ? String(updateVersion) : undefined}
                    onValueChange={(v) => setUpdateVersion(Number(v))}
                  >
                    <SelectTrigger>
                      <SelectValue placeholder="Select version..." />
                    </SelectTrigger>
                    <SelectContent>
                      {updateTierVersions.map((v) => (
                        <SelectItem key={v.id} value={String(v.version)}>
                          v{v.version} {v.isCurrent ? '(current)' : '(legacy)'}
                        </SelectItem>
                      ))}
                    </SelectContent>
                  </Select>
                ) : (
                  <p className="text-sm text-muted-foreground py-2">No versions found for {updateTier}</p>
                )}
              </div>
            </div>

            {selectedUpdateTierConfig && (
              <>
                <div className="rounded border bg-muted/50 p-3 text-sm space-y-1">
                  <p className="font-medium mb-1">Current configuration:</p>
                  <p>Tier: {selectedUpdateTierConfig.tierName} v{selectedUpdateTierConfig.version}</p>
                  <p>Monthly Price: ${centsToDollars(selectedUpdateTierConfig.monthlyPriceCents)}/mo</p>
                  <p>Yearly Price: ${centsToDollars(selectedUpdateTierConfig.yearlyPriceCents)}/yr</p>
                  <p>Status: {selectedUpdateTierConfig.isCurrent ? 'Current' : 'Legacy'}</p>
                </div>

                <Separator />

                <div className="space-y-4">
                  <p className="text-sm font-medium">Stripe Price IDs</p>
                  <div className="grid gap-4 sm:grid-cols-2">
                    <div className="space-y-1.5">
                      <Label htmlFor="updateStripeBasePriceId">Monthly Base Price ID</Label>
                      <Input
                        id="updateStripeBasePriceId"
                        placeholder="price_..."
                        value={updatePriceForm.stripeBasePriceId}
                        onChange={(e) =>
                          setUpdatePriceForm((p) => ({ ...p, stripeBasePriceId: e.target.value }))
                        }
                      />
                      <FieldHint>Stripe Price ID for monthly base subscription</FieldHint>
                    </div>

                    <div className="space-y-1.5">
                      <Label htmlFor="updateStripeOveragePriceId">Monthly Overage Price ID</Label>
                      <Input
                        id="updateStripeOveragePriceId"
                        placeholder="price_..."
                        value={updatePriceForm.stripeOveragePriceId}
                        onChange={(e) =>
                          setUpdatePriceForm((p) => ({ ...p, stripeOveragePriceId: e.target.value }))
                        }
                      />
                      <FieldHint>Stripe Price ID for monthly metered overage</FieldHint>
                    </div>

                    <div className="space-y-1.5">
                      <Label htmlFor="updateStripeYearlyBasePriceId">Yearly Base Price ID</Label>
                      <Input
                        id="updateStripeYearlyBasePriceId"
                        placeholder="price_..."
                        value={updatePriceForm.stripeYearlyBasePriceId}
                        onChange={(e) =>
                          setUpdatePriceForm((p) => ({ ...p, stripeYearlyBasePriceId: e.target.value }))
                        }
                      />
                      <FieldHint>Stripe Price ID for yearly base subscription</FieldHint>
                    </div>

                    <div className="space-y-1.5">
                      <Label htmlFor="updateStripeYearlyOveragePriceId">Yearly Overage Price ID</Label>
                      <Input
                        id="updateStripeYearlyOveragePriceId"
                        placeholder="price_..."
                        value={updatePriceForm.stripeYearlyOveragePriceId}
                        onChange={(e) =>
                          setUpdatePriceForm((p) => ({ ...p, stripeYearlyOveragePriceId: e.target.value }))
                        }
                      />
                      <FieldHint>Stripe Price ID for yearly metered overage</FieldHint>
                    </div>

                    <div className="space-y-1.5">
                      <Label htmlFor="updateStripeOncallPriceId">Monthly On-Call Price ID</Label>
                      <Input
                        id="updateStripeOncallPriceId"
                        placeholder="price_..."
                        value={updatePriceForm.stripeOncallPriceId}
                        onChange={(e) =>
                          setUpdatePriceForm((p) => ({ ...p, stripeOncallPriceId: e.target.value }))
                        }
                      />
                      <FieldHint>Stripe Price ID for monthly on-call seat charges</FieldHint>
                    </div>

                    <div className="space-y-1.5">
                      <Label htmlFor="updateStripeOncallYearlyPriceId">Yearly On-Call Price ID</Label>
                      <Input
                        id="updateStripeOncallYearlyPriceId"
                        placeholder="price_..."
                        value={updatePriceForm.stripeOncallYearlyPriceId}
                        onChange={(e) =>
                          setUpdatePriceForm((p) => ({ ...p, stripeOncallYearlyPriceId: e.target.value }))
                        }
                      />
                      <FieldHint>Stripe Price ID for yearly on-call seat charges</FieldHint>
                    </div>
                  </div>

                  <Button
                    onClick={() => updatePriceIdsMutation.mutate()}
                    disabled={updatePriceIdsMutation.isPending || updateVersion === ''}
                  >
                    {updatePriceIdsMutation.isPending ? 'Updating...' : 'Update Price IDs'}
                  </Button>
                </div>
              </>
            )}
          </CardContent>
        </Card>

        {/* ── Migrate Subscribers ──────────────────────────────────────────── */}

        <Card>
          <CardHeader>
            <CardTitle>Migrate Subscribers</CardTitle>
            <CardDescription>
              Move existing subscribers from one tier version to another.
              Always run a dry run first to see how many subscriptions will be affected.
            </CardDescription>
          </CardHeader>
          <CardContent className="space-y-4">
            <div className="grid gap-4 sm:grid-cols-2">
              {/* Migrate tier selector (independent from create) */}
              <div className="space-y-1.5">
                <Label>
                  Tier to Migrate
                  <HelpTip text="Select which tier's subscribers you want to migrate to a new version." />
                </Label>
                <Select value={migrateTier} onValueChange={(v) => { setMigrateTier(v); setDryRunResult(null); setTargetVersion('') }}>
                  <SelectTrigger>
                    <SelectValue />
                  </SelectTrigger>
                  <SelectContent>
                    {availableTiers.map((t) => (
                      <SelectItem key={t} value={t}>
                        {t}
                      </SelectItem>
                    ))}
                  </SelectContent>
                </Select>
                {currentMigrateTierConfig && (
                  <FieldHint>
                    Active config: v{currentMigrateTierConfig.version}
                  </FieldHint>
                )}
              </div>

              {/* Target version selector */}
              <div className="space-y-1.5">
                <Label>
                  Target Version
                  <HelpTip text="The version to migrate subscribers to. Only versions that exist for this tier are shown." />
                </Label>
                {migrateTierVersions.length > 0 ? (
                  <Select
                    value={targetVersion !== '' ? String(targetVersion) : undefined}
                    onValueChange={(v) => { setTargetVersion(Number(v)); setDryRunResult(null) }}
                  >
                    <SelectTrigger>
                      <SelectValue placeholder="Select version..." />
                    </SelectTrigger>
                    <SelectContent>
                      {migrateTierVersions.map((v) => (
                        <SelectItem key={v.id} value={String(v.version)}>
                          v{v.version} {v.isCurrent ? '(current)' : '(legacy)'} &mdash;{' '}
                          {'$' + centsToDollars(v.monthlyPriceCents) + '/mo, '}
                          {formatQuotaLimit(v.monthlyUnitLimit)} total units
                        </SelectItem>
                      ))}
                    </SelectContent>
                  </Select>
                ) : (
                  <p className="text-sm text-muted-foreground py-2">No versions found for {migrateTier}</p>
                )}
              </div>
            </div>

            {/* Target version details */}
            {targetTierConfig && (
              <div className="rounded border bg-muted/50 p-3 text-sm space-y-1">
                <p className="font-medium mb-1">Target v{targetTierConfig.version} details:</p>
                <p>Price: ${centsToDollars(targetTierConfig.monthlyPriceCents)}/mo</p>
                <p>Yearly Price: ${centsToDollars(targetTierConfig.yearlyPriceCents)}/yr</p>
                <p>Monthly Data Limit: {Math.round(targetTierConfig.monthlyGbLimit / BYTES_PER_GB)} GB</p>
                <p>LLM Events: {formatQuotaLimit(targetTierConfig.monthlyLlmEventLimit ?? 0)}</p>
                <p>Retention: {retentionSummary(targetTierConfig)}</p>
                <p>Max Services: {targetTierConfig.maxProjects ?? 'Unlimited'}</p>
                <p>Max Systems: {targetTierConfig.maxSystems}</p>
                <p>PAYG: {targetTierConfig.paygEnabled ? 'Enabled' : 'Disabled'}</p>
              </div>
            )}

            {currentMigrateTierConfig && targetTierForm && (
              <ChangeSummary
                current={currentMigrateTierConfig}
                form={targetTierForm}
                title={`Target changes from active v${currentMigrateTierConfig.version}:`}
                emptyMessage={`Target v${targetVersion} matches the current billing config.`}
              />
            )}

            {/* Dry run result */}
            {dryRunResult && (
              <div className="flex items-start gap-2 rounded border border-info-border bg-info-bg p-3">
                <Info className="h-4 w-4 text-info-fg mt-0.5 shrink-0" />
                <p className="text-sm text-info-fg">
                  Dry run result: <strong>{dryRunResult.affected} subscription(s)</strong> would be
                  migrated to v{dryRunResult.version}.
                </p>
              </div>
            )}

            {/* Action buttons */}
            <div className="flex items-center gap-2">
              <Button
                variant="outline"
                onClick={() => dryRunMutation.mutate()}
                disabled={dryRunMutation.isPending || targetVersion === ''}
              >
                {dryRunMutation.isPending ? 'Running...' : 'Dry Run'}
              </Button>
              <Button
                variant="default"
                onClick={() => setShowMigrateConfirm(true)}
                disabled={
                  executeMigrationMutation.isPending ||
                  targetVersion === '' ||
                  !dryRunResult
                }
              >
                Execute Migration
              </Button>
              {!dryRunResult && targetVersion !== '' && (
                <p className="text-xs text-muted-foreground flex items-center gap-1">
                  <Info className="h-3.5 w-3.5" />
                  Run a dry run first to enable migration
                </p>
              )}
            </div>
          </CardContent>
        </Card>

        {/* ── Migration Confirmation Dialog ────────────────────────────────── */}

        <Dialog open={showMigrateConfirm} onOpenChange={setShowMigrateConfirm}>
          <DialogContent>
            <DialogHeader>
              <DialogTitle className="flex items-center gap-2">
                <AlertTriangle className="h-5 w-5 text-warning-fg" />
                Confirm Migration
              </DialogTitle>
              <DialogDescription>
                This action will migrate <strong>{dryRunResult?.affected ?? 0} subscription(s)</strong> on
                the <strong>{migrateTier}</strong> tier to version <strong>v{targetVersion}</strong>.
              </DialogDescription>
            </DialogHeader>

            <div className="space-y-3 my-2">
              <div className="flex items-start gap-2 rounded border border-destructive/30 bg-destructive/10 p-3">
                <AlertTriangle className="h-4 w-4 text-destructive mt-0.5 shrink-0" />
                <div className="text-sm space-y-1">
                  <p className="font-medium text-destructive">This action cannot be undone easily.</p>
                  <p className="text-muted-foreground">
                    All affected subscribers will immediately use the new tier config.
                    Their limits, retention, and pricing may change.
                  </p>
                </div>
              </div>
            </div>

            <DialogFooter>
              <Button variant="outline" onClick={() => setShowMigrateConfirm(false)}>
                Cancel
              </Button>
              <Button
                variant="destructive"
                onClick={() => executeMigrationMutation.mutate()}
                disabled={executeMigrationMutation.isPending}
              >
                {executeMigrationMutation.isPending ? 'Migrating...' : `Migrate ${dryRunResult?.affected ?? 0} Subscription(s)`}
              </Button>
            </DialogFooter>
          </DialogContent>
        </Dialog>

        {/* ── Subscriptions ────────────────────────────────────────────────── */}

        <Card>
          <CardHeader>
            <div className="flex items-center justify-between flex-wrap gap-3">
              <div>
                <CardTitle>Subscriptions</CardTitle>
                <CardDescription>
                  Current billing status and PAYG counters for all organizations.
                  {subscriptions.length > 0 && ` Showing ${filteredSubscriptions.length} of ${subscriptions.length}.`}
                </CardDescription>
              </div>
              <Input
                placeholder="Filter by org, plan, or status..."
                className="max-w-xs"
                value={subFilter}
                onChange={(e) => setSubFilter(e.target.value)}
              />
            </div>
          </CardHeader>
          <CardContent>
            {filteredSubscriptions.length === 0 ? (
              <p className="text-sm text-muted-foreground py-4 text-center">
                {subFilter ? 'No subscriptions match your filter.' : 'No subscriptions found.'}
              </p>
            ) : (
              <Table>
                <TableHeader>
                  <TableRow>
                    <TableHead>Organization</TableHead>
                    <TableHead>Plan</TableHead>
                    <TableHead>Status</TableHead>
                    <TableHead>PAYG Budget</TableHead>
                    <TableHead>PAYG Used</TableHead>
                    <TableHead>Pending Meter</TableHead>
                    <TableHead>Period</TableHead>
                  </TableRow>
                </TableHeader>
                <TableBody>
                  {filteredSubscriptions.slice(0, 50).map((sub: AdminBillingSubscription) => (
                    <TableRow key={sub.subscriptionId}>
                      <TableCell className="font-medium">{sub.organizationName}</TableCell>
                      <TableCell>
                        <PlanBadge plan={sub.plan} />
                      </TableCell>
                      <TableCell>
                        <Badge
                          variant={
                            sub.status === 'active' || sub.status === 'trialing'
                              ? 'default'
                              : sub.status === 'past_due'
                                ? 'destructive'
                                : 'secondary'
                          }
                        >
                          {sub.status}
                        </Badge>
                      </TableCell>
                      <TableCell>${centsToDollars(sub.paygBudgetCents)}</TableCell>
                      <TableCell>
                        {sub.paygUsedUnits.toLocaleString()} units
                        <span className="text-xs text-muted-foreground ml-1">
                          (${(sub.paygUsedMicros / 1_000_000).toFixed(2)})
                        </span>
                      </TableCell>
                      <TableCell>{sub.pendingMeterUnits.toLocaleString()}</TableCell>
                      <TableCell className="text-xs text-muted-foreground">
                        {sub.currentPeriodStart && sub.currentPeriodEnd ? (
                          <>
                            {formatDate(sub.currentPeriodStart, timezone)} &ndash;{' '}
                            {formatDate(sub.currentPeriodEnd, timezone)}
                          </>
                        ) : (
                          '—'
                        )}
                      </TableCell>
                    </TableRow>
                  ))}
                </TableBody>
              </Table>
            )}
            {filteredSubscriptions.length > 50 && (
              <p className="text-xs text-muted-foreground text-center mt-3">
                Showing first 50 of {filteredSubscriptions.length} results. Use the filter to narrow down.
              </p>
            )}
          </CardContent>
        </Card>
      </div>
    </TooltipProvider>
  )
}

// ─── Review Components ────────────────────────────────────────────────────────

function CreateConfigSummary({tier, form}: {tier: string; form: CreateFormState}) {
  return (
    <div className="rounded border bg-muted/50 p-3 text-sm space-y-1">
      <p><strong>Tier:</strong> {tier}</p>
      <p><strong>Monthly Price:</strong> ${centsToDollars(form.monthlyPriceCents)}/mo</p>
      <p><strong>Yearly Price:</strong> ${centsToDollars(form.yearlyPriceCents)}/yr</p>
      <p><strong>Monthly Data Limit:</strong> {form.monthlyGbLimitGb} GB</p>
      <p><strong>Trial:</strong> {form.trialDays} day(s)</p>
      <p><strong>LLM Events:</strong> {formatQuotaLimit(form.monthlyLlmEventLimit)}</p>
      <p><strong>Retention:</strong> {createFormRetentionSummary(form)}</p>
      <p><strong>Max Services:</strong> {form.maxProjects || 'Unlimited'}</p>
      <p><strong>Max Systems:</strong> {form.maxSystems}</p>
      <p><strong>Monitor Interval:</strong> {formatInterval(form.monitorIntervalSeconds)}</p>
      <p>
        <strong>PAYG:</strong>{' '}
        {form.paygEnabled ? `Enabled (${form.paygRateMicrosPerUnit} micros/unit)` : 'Disabled'}
      </p>
      <p>
        <strong>On-Call:</strong>{' '}
        {form.oncallEnabled ? `$${(form.oncallPerUserMonthlyCents / 100).toFixed(2)}/user/mo` : 'Disabled'}
      </p>
    </div>
  )
}

function ChangeSummary({
  current,
  form,
  title,
  emptyMessage,
}: {
  current: BillingTierConfig
  form: CreateFormState
  title: string
  emptyMessage?: string
}) {
  const changes: Array<{field: string; from: string; to: string}> = []
  const currentErrorLimit = normalizeQuotaForForm(current.monthlyErrorLimit)
  const currentTransactionLimit = normalizeQuotaForForm(current.monthlyTransactionLimit)
  const currentReplayLimit = normalizeReplayQuotaForForm(current.monthlyReplayLimit)
  const currentFeedbackLimit = normalizeQuotaForForm(current.monthlyFeedbackLimit)
  const currentTotalLimit = formatTotalQuotaLimit([
    currentErrorLimit,
    currentTransactionLimit,
    currentReplayLimit,
    currentFeedbackLimit,
  ])
  const formTotalLimit = formatTotalQuotaLimit([
    form.monthlyErrorLimit,
    form.monthlyTransactionLimit,
    form.monthlyReplayLimit,
    form.monthlyFeedbackLimit,
  ])

  if (currentTotalLimit !== formTotalLimit) {
    changes.push({
      field: 'Total Unit Limit',
      from: currentTotalLimit,
      to: formTotalLimit,
    })
  }
  if (currentErrorLimit !== form.monthlyErrorLimit) {
    changes.push({
      field: 'Error Limit',
      from: formatQuotaLimit(currentErrorLimit),
      to: formatQuotaLimit(form.monthlyErrorLimit),
    })
  }
  if (currentTransactionLimit !== form.monthlyTransactionLimit) {
    changes.push({
      field: 'Transaction Limit',
      from: formatQuotaLimit(currentTransactionLimit),
      to: formatQuotaLimit(form.monthlyTransactionLimit),
    })
  }
  if (currentReplayLimit !== form.monthlyReplayLimit) {
    changes.push({
      field: 'Replay Limit',
      from: formatQuotaLimit(currentReplayLimit),
      to: formatQuotaLimit(form.monthlyReplayLimit),
    })
  }
  if (currentFeedbackLimit !== form.monthlyFeedbackLimit) {
    changes.push({
      field: 'Feedback Limit',
      from: formatQuotaLimit(currentFeedbackLimit),
      to: formatQuotaLimit(form.monthlyFeedbackLimit),
    })
  }
  const currentLlm = normalizeQuotaForForm(current.monthlyLlmEventLimit ?? 0)
  if (currentLlm !== form.monthlyLlmEventLimit) {
    changes.push({
      field: 'LLM Event Limit',
      from: formatQuotaLimit(currentLlm),
      to: formatQuotaLimit(form.monthlyLlmEventLimit),
    })
  }
  if (current.retentionDays !== form.retentionDays) {
    changes.push({
      field: 'Error Retention',
      from: `${current.retentionDays}d`,
      to: `${form.retentionDays}d`,
    })
  }
  const currentLogRet = current.logRetentionDays ?? current.retentionDays
  if (currentLogRet !== form.logRetentionDays) {
    changes.push({
      field: 'Log Retention',
      from: `${currentLogRet}d`,
      to: `${form.logRetentionDays}d`,
    })
  }
  const currentReplayRet = current.replayRetentionDays ?? current.retentionDays
  if (currentReplayRet !== form.replayRetentionDays) {
    changes.push({
      field: 'Replay Retention',
      from: `${currentReplayRet}d`,
      to: `${form.replayRetentionDays}d`,
    })
  }
  const currentLlmRet = current.llmRetentionDays ?? current.retentionDays
  if (currentLlmRet !== form.llmRetentionDays) {
    changes.push({
      field: 'LLM Retention',
      from: `${currentLlmRet}d`,
      to: `${form.llmRetentionDays}d`,
    })
  }
  const currentApmTraceRet = current.apmTraceRetentionDays ?? current.retentionDays
  if (currentApmTraceRet !== form.apmTraceRetentionDays) {
    changes.push({
      field: 'APM Trace Retention',
      from: `${currentApmTraceRet}d`,
      to: `${form.apmTraceRetentionDays}d`,
    })
  }
  const formMaxProjects = form.maxProjects.trim() ? Number(form.maxProjects) : null
  if (current.maxProjects !== formMaxProjects) {
    changes.push({
      field: 'Max Services',
      from: current.maxProjects != null ? String(current.maxProjects) : 'Unlimited',
      to: formMaxProjects != null ? String(formMaxProjects) : 'Unlimited',
    })
  }
  if (current.maxSystems !== form.maxSystems) {
    changes.push({
      field: 'Max Systems',
      from: String(current.maxSystems),
      to: String(form.maxSystems),
    })
  }
  if (current.monitorIntervalSeconds !== form.monitorIntervalSeconds) {
    changes.push({
      field: 'Monitor Interval',
      from: formatInterval(current.monitorIntervalSeconds),
      to: formatInterval(form.monitorIntervalSeconds),
    })
  }
  if (current.monthlyPriceCents !== form.monthlyPriceCents) {
    changes.push({
      field: 'Monthly Price',
      from: `$${centsToDollars(current.monthlyPriceCents)}`,
      to: `$${centsToDollars(form.monthlyPriceCents)}`,
    })
  }
  if (current.yearlyPriceCents !== form.yearlyPriceCents) {
    changes.push({
      field: 'Yearly Price',
      from: `$${centsToDollars(current.yearlyPriceCents)}`,
      to: `$${centsToDollars(form.yearlyPriceCents)}`,
    })
  }
  const formMonthlyGbLimit = Math.max(0, Math.round(form.monthlyGbLimitGb * BYTES_PER_GB))
  if (current.monthlyGbLimit !== formMonthlyGbLimit) {
    changes.push({
      field: 'Monthly Data Limit',
      from: `${Math.round(current.monthlyGbLimit / BYTES_PER_GB)} GB`,
      to: `${Math.round(form.monthlyGbLimitGb)} GB`,
    })
  }
  if (current.trialDays !== form.trialDays) {
    changes.push({
      field: 'Trial Days',
      from: `${current.trialDays}`,
      to: `${form.trialDays}`,
    })
  }
  if (current.paygEnabled !== form.paygEnabled) {
    changes.push({
      field: 'PAYG',
      from: current.paygEnabled ? 'Enabled' : 'Disabled',
      to: form.paygEnabled ? 'Enabled' : 'Disabled',
    })
  }
  if (current.paygRateMicrosPerUnit !== form.paygRateMicrosPerUnit) {
    changes.push({
      field: 'PAYG Rate',
      from: `${current.paygRateMicrosPerUnit} micros`,
      to: `${form.paygRateMicrosPerUnit} micros`,
    })
  }
  const currentLogOverage = current.overageRateCentsPerGb ?? 40
  if (currentLogOverage !== form.overageRateCentsPerGb) {
    changes.push({
      field: 'Log Overage',
      from: `${(currentLogOverage / 100).toFixed(2)}/GB`,
      to: `${(form.overageRateCentsPerGb / 100).toFixed(2)}/GB`,
    })
  }
  const currentErrOverage = current.errorOverageRateCentsPer1k ?? 10
  if (currentErrOverage !== form.errorOverageRateCentsPer1k) {
    changes.push({
      field: 'Error Overage',
      from: `${(currentErrOverage / 100).toFixed(2)}/1K`,
      to: `${(form.errorOverageRateCentsPer1k / 100).toFixed(2)}/1K`,
    })
  }
  const currentReplayOverage = current.replayOverageRateCentsPerGb ?? 40
  if (currentReplayOverage !== form.replayOverageRateCentsPerGb) {
    changes.push({
      field: 'Replay Overage',
      from: `${(currentReplayOverage / 100).toFixed(2)}/GB`,
      to: `${(form.replayOverageRateCentsPerGb / 100).toFixed(2)}/GB`,
    })
  }
  const currentLlmOverage = current.llmOverageRateCentsPer1k ?? 100
  if (currentLlmOverage !== form.llmOverageRateCentsPer1k) {
    changes.push({
      field: 'LLM Overage',
      from: `${(currentLlmOverage / 100).toFixed(2)}/1K`,
      to: `${(form.llmOverageRateCentsPer1k / 100).toFixed(2)}/1K`,
    })
  }
  const currentOncall = current.oncallPerUserMonthlyCents ?? 500
  if (current.oncallEnabled !== form.oncallEnabled || currentOncall !== form.oncallPerUserMonthlyCents) {
    changes.push({
      field: 'On-Call',
      from: current.oncallEnabled ? `$${(currentOncall / 100).toFixed(2)}/user` : 'Disabled',
      to: form.oncallEnabled ? `$${(form.oncallPerUserMonthlyCents / 100).toFixed(2)}/user` : 'Disabled',
    })
  }

  if (changes.length === 0) {
    return (
      <div className="flex items-center gap-2 rounded border p-3 text-sm text-muted-foreground">
        <Info className="h-4 w-4 shrink-0" />
        {emptyMessage ?? `No changes from current v${current.version}. Modify the fields above to see a diff.`}
      </div>
    )
  }

  return (
    <div className="rounded border p-3 space-y-2">
      <p className="text-sm font-medium">{title}</p>
      <div className="space-y-1">
        {changes.map((c) => (
          <div key={c.field} className="text-sm flex items-center gap-2">
            <span className="text-muted-foreground w-36 shrink-0">{c.field}:</span>
            <span className="text-danger-fg line-through">{c.from}</span>
            <span className="text-muted-foreground">&rarr;</span>
            <span className="text-success-fg font-medium">{c.to}</span>
          </div>
        ))}
      </div>
    </div>
  )
}

function PricingPreviewGrid({
  cards,
  interval,
}: {
  cards: ReturnType<typeof buildPricingCardModel>[]
  interval: BillingInterval
}) {
  return (
    <div className="grid gap-3 md:grid-cols-2 xl:grid-cols-4">
      {cards.map((tier) => (
        <Card
          key={tier.tierName}
          className={tier.highlight ? 'relative border-primary/50' : 'border-border/60'}
        >
          {tier.highlight && (
            <div className="absolute -top-3 left-1/2 -translate-x-1/2">
              <span className="inline-flex items-center rounded-full bg-primary px-3 py-1 text-[10px] font-semibold text-primary-foreground">
                Most popular
              </span>
            </div>
          )}
          <CardHeader className="pb-3">
            <CardTitle className="text-base">{tier.name}</CardTitle>
            <CardDescription className="text-xs">{tier.description}</CardDescription>
            <div className="mt-2">
              <span className="text-3xl font-bold">${tier.displayPrice === 0 ? '0' : tier.displayPrice.toFixed(0)}</span>
              <span className="text-muted-foreground text-xs">
                /mo
                {interval === 'yearly' && tier.displayPrice > 0 && (
                  <span className="block text-[11px] mt-1">billed ${tier.yearlyTotalPrice.toFixed(0)}/yr</span>
                )}
              </span>
            </div>
          </CardHeader>
          <CardContent className="space-y-2">
            <ul className="space-y-1.5">
              {tier.includedLimits.slice(0, PRICING_PREVIEW_LIMIT_COUNT).map((feature) => (
                <li key={feature} className="flex items-start gap-2">
                  <div className={`mt-0.5 rounded-full p-0.5 ${tier.highlight ? 'bg-[hsl(var(--primary)/0.12)]' : 'bg-success-bg'}`}>
                    <Check className={`h-3 w-3 ${tier.highlight ? 'text-primary' : 'text-success-fg'}`} />
                  </div>
                  <span className="text-xs leading-tight">{feature}</span>
                </li>
              ))}
            </ul>
            <div className="pt-2 text-center">
              <Button
                className="w-full"
                variant={tier.highlight ? 'default' : 'outline'}
                size="sm"
                disabled
              >
                {tier.cta}
              </Button>
            </div>
          </CardContent>
        </Card>
      ))}
    </div>
  )
}
