import {describe, expect, it, vi} from 'vitest'
import type {AdminBillingSubscription, BillingPlan, BillingTierConfig} from '@/lib/api'

vi.mock('@tanstack/react-router', () => ({
  createFileRoute: () => (options: Record<string, unknown>) => options,
}))

vi.mock('@/hooks/useToast', () => ({
  useToast: () => ({toast: vi.fn()}),
}))

vi.mock('@/hooks/useTimezone', () => ({
  useTimezone: () => ({timezone: 'UTC'}),
}))

import {adminBillingHelperTestHooks as helpers} from '../admin.billing'

const BYTES_PER_GB = 1024 * 1024 * 1024

function billingTier(overrides: Partial<BillingTierConfig> = {}): BillingTierConfig {
  return {
    id: 'tier-config-1',
    tierName: 'PRO',
    version: 2,
    monthlyUnitLimit: 500_000,
    monthlyErrorLimit: 100_000,
    monthlyTransactionLimit: 50_000,
    monthlyReplayLimit: 300,
    monthlyFeedbackLimit: 25_000,
    monthlyLlmEventLimit: 10_000,
    logRetentionDays: 30,
    retentionDays: 30,
    replayRetentionDays: 14,
    llmRetentionDays: 30,
    apmTraceRetentionDays: 30,
    statusPagesEnabled: true,
    statusPageCustomDomainEnabled: false,
    sessionReplayEnabled: true,
    slackEnabled: true,
    discordEnabled: false,
    incidentIoEnabled: true,
    samlEnabled: false,
    oidcEnabled: true,
    prioritySupportEnabled: true,
    slaEnabled: false,
    customRetentionEnabled: true,
    maxProjects: 7,
    maxSystems: 4,
    monitorIntervalSeconds: 30,
    monthlyPriceCents: 1900,
    yearlyPriceCents: 19_200,
    trialDays: 14,
    monthlyGbLimit: 50 * BYTES_PER_GB,
    paygEnabled: true,
    paygRateMicrosPerUnit: 10,
    overageRateCentsPerGb: 40,
    stripeBasePriceId: 'price_monthly_base',
    stripeOveragePriceId: null,
    stripeYearlyBasePriceId: 'price_yearly_base',
    stripeYearlyOveragePriceId: undefined,
    stripeOncallPriceId: 'price_oncall_monthly',
    stripeOncallYearlyPriceId: null,
    oncallEnabled: true,
    oncallPerUserMonthlyCents: 500,
    maxAnalyticsSites: 2,
    analyticsRetentionDays: 365,
    monthlyAnalyticsPageviewLimit: 100_000,
    analyticsPageviewOverageRateCentsPer100k: 1000,
    isCurrent: true,
    ...overrides,
  }
}

function billingSubscription(
  overrides: Partial<AdminBillingSubscription> = {}
): AdminBillingSubscription {
  return {
    subscriptionId: 'sub-1',
    organizationId: 'org-1',
    organizationName: 'Acme',
    plan: 'PRO',
    status: 'active',
    pricingTierConfigId: null,
    paygBudgetCents: 0,
    paygUsedUnits: 0,
    paygUsedMicros: 0,
    pendingMeterUnits: 0,
    currentPeriodStart: null,
    currentPeriodEnd: null,
    ...overrides,
  }
}

describe('admin billing helper coverage', () => {
  it('normalizes raw billing responses and version keys', () => {
    const plan: BillingPlan = {tier: billingTier(), trialDays: 21}

    expect(helpers.toBillingPlans([plan])).toEqual([plan])
    expect(helpers.toBillingPlans([{trialDays: 21}])).toEqual([])
    expect(helpers.toBillingPlans(undefined)).toEqual([])
    expect(helpers.toBillingTierConfigs([plan.tier])).toEqual([plan.tier])
    expect(helpers.toBillingTierConfigs(null)).toEqual([])
    expect(helpers.tierVersionKey(plan.tier)).toBe('PRO-2')
    expect(helpers.tierVersionKey(undefined)).toBeUndefined()
  })

  it('resolves create and price forms from active tier state', () => {
    const config = billingTier()
    const editedCreateForm = {...helpers.DEFAULT_FORM, monthlyPriceCents: 2500}
    const editedPriceForm = {...helpers.EMPTY_UPDATE_PRICE_FORM, stripeBasePriceId: 'edited'}

    expect(
      helpers.resolveCreateForm({tierVersion: 'PRO-2', data: editedCreateForm}, 'PRO-2', config)
    ).toBe(editedCreateForm)
    expect(
      helpers.resolveCreateForm({tierVersion: 'PRO-1', data: editedCreateForm}, 'PRO-2', config)
    ).toMatchObject({
      monthlyPriceCents: 1900,
      maxProjects: '7',
      monthlyGbLimitGb: 50,
    })
    expect(
      helpers.resolveCreateForm({tierVersion: undefined, data: editedCreateForm}, undefined, undefined)
    ).toBe(helpers.DEFAULT_FORM)

    expect(helpers.buildUpdatePriceForm(undefined)).toBe(helpers.EMPTY_UPDATE_PRICE_FORM)
    expect(helpers.buildUpdatePriceForm(config)).toEqual({
      stripeBasePriceId: 'price_monthly_base',
      stripeOveragePriceId: '',
      stripeYearlyBasePriceId: 'price_yearly_base',
      stripeYearlyOveragePriceId: '',
      stripeOncallPriceId: 'price_oncall_monthly',
      stripeOncallYearlyPriceId: '',
    })
    expect(
      helpers.resolveUpdatePriceForm({tierVersion: '2', data: editedPriceForm}, '2', config)
    ).toBe(editedPriceForm)
    expect(
      helpers.resolveUpdatePriceForm({tierVersion: '1', data: editedPriceForm}, '2', config).stripeBasePriceId
    ).toBe('price_monthly_base')
  })

  it('filters subscriptions and builds sorted tier choices', () => {
    const subscriptions: AdminBillingSubscription[] = [
      billingSubscription(),
      billingSubscription({
        subscriptionId: 'sub-2',
        organizationId: 'org-2',
        organizationName: 'Beta',
        plan: 'ENTERPRISE',
        status: 'past_due',
      }),
    ]
    const plans: BillingPlan[] = [
      {tier: billingTier({tierName: 'ENTERPRISE'}), trialDays: 0},
      {tier: billingTier({tierName: 'FREE'}), trialDays: 0},
    ]

    expect(helpers.filterSubscriptionsByText(subscriptions, '')).toBe(subscriptions)
    expect(helpers.filterSubscriptionsByText(subscriptions, 'past')).toHaveLength(1)
    expect(helpers.filterSubscriptionsByText(subscriptions, 'enterprise')[0]?.organizationName).toBe('Beta')
    expect(helpers.availableBillingTiers(plans)).toEqual(['BUSINESS', 'ENTERPRISE', 'FREE', 'PRO', 'TEAM'])
  })

  it('builds preview cards from edited numeric form fields', () => {
    const config = billingTier({tierName: 'TEAM'})
    const createForm = {
      ...helpers.DEFAULT_FORM,
      maxProjects: '9',
      maxAnalyticsSites: '3',
      monthlyGbLimitGb: 25,
      monthlyPriceCents: 4900,
    }

    expect(helpers.numberFromText('')).toBeNull()
    expect(helpers.numberFromText('  ')).toBeNull()
    expect(helpers.numberFromText('12')).toBe(12)

    const cards = helpers.buildPreviewCards(
      [{tier: config, trialDays: 7}],
      'TEAM',
      createForm,
      config,
      'monthly'
    )

    expect(cards).toHaveLength(1)
    expect(cards[0]?.tierName).toBe('TEAM')
    expect(cards[0]?.displayPrice).toBe(49)
  })
})
