import { describe, it, expect } from 'vitest'
import {
  buildPricingCardModel,
  type PricingCardTierInput,
} from '@/lib/pricing-display'

const BYTES_PER_GB = 1024 * 1024 * 1024

function makeTier(overrides: Partial<PricingCardTierInput> = {}): PricingCardTierInput {
  return {
    tierName: 'PRO',
    monthlyPriceCents: 2900,
    yearlyPriceCents: 29000,
    trialDays: 14,
    monthlyGbLimit: 10 * BYTES_PER_GB,
    monthlyErrorLimit: 50000,
    monthlyReplayLimit: 5000,
    monthlyLlmEventLimit: 10000,
    retentionDays: 30,
    logRetentionDays: 30,
    replayRetentionDays: 30,
    llmRetentionDays: 30,
    maxProjects: null,
    maxSystems: 10,
    monitorIntervalSeconds: 60,
    sessionReplayEnabled: true,
    statusPagesEnabled: true,
    statusPageCustomDomainEnabled: true,
    slackEnabled: true,
    discordEnabled: false,
    incidentIoEnabled: false,
    samlEnabled: false,
    oidcEnabled: false,
    prioritySupportEnabled: false,
    slaEnabled: false,
    customRetentionEnabled: false,
    overageRateCentsPerGb: 200,
    errorOverageRateCentsPer1k: 50,
    replayOverageRateCentsPerGb: 300,
    llmOverageRateCentsPer1k: 100,
    ...overrides,
  }
}

describe('pricing-display', () => {
  describe('buildPricingCardModel', () => {
    it('builds model with monthly pricing', () => {
      const tier = makeTier()
      const model = buildPricingCardModel(tier, 'monthly')
      expect(model.monthlyPrice).toBe(29)
      expect(model.displayPrice).toBe(29)
      expect(model.tierName).toBe('PRO')
    })

    it('builds model with yearly pricing', () => {
      const tier = makeTier()
      const model = buildPricingCardModel(tier, 'yearly')
      expect(model.yearlyTotalPrice).toBe(290)
      expect(model.displayPrice).toBeCloseTo(290 / 12)
    })

    it('formats tier name properly', () => {
      const model = buildPricingCardModel(makeTier({ tierName: 'FREE' }), 'monthly')
      expect(model.name).toBe('Free')
    })

    it('highlights PRO tier', () => {
      const pro = buildPricingCardModel(makeTier({ tierName: 'PRO' }), 'monthly')
      expect(pro.highlight).toBe(true)
      const free = buildPricingCardModel(makeTier({ tierName: 'FREE' }), 'monthly')
      expect(free.highlight).toBe(false)
    })

    it('includes error limit in included limits', () => {
      const model = buildPricingCardModel(makeTier({ monthlyErrorLimit: 50000 }), 'monthly')
      expect(model.includedLimits.some(l => l.includes('50K') && l.includes('errors'))).toBe(true)
    })

    it('includes log data limit', () => {
      const model = buildPricingCardModel(makeTier({ monthlyGbLimit: 10 * BYTES_PER_GB }), 'monthly')
      expect(model.includedLimits.some(l => l.includes('10 GB') && l.includes('log'))).toBe(true)
    })

    it('includes TB formatting for large data limits', () => {
      const model = buildPricingCardModel(makeTier({ monthlyGbLimit: 2048 * BYTES_PER_GB }), 'monthly')
      expect(model.includedLimits.some(l => l.includes('2 TB'))).toBe(true)
    })

    it('shows unlimited projects when maxProjects is null', () => {
      const model = buildPricingCardModel(makeTier({ maxProjects: null }), 'monthly')
      expect(model.includedLimits.some(l => l.includes('Unlimited projects'))).toBe(true)
    })

    it('shows project count when maxProjects is set', () => {
      const model = buildPricingCardModel(makeTier({ maxProjects: 5 }), 'monthly')
      expect(model.includedLimits.some(l => l.includes('5 projects'))).toBe(true)
    })

    it('shows singular project for 1', () => {
      const model = buildPricingCardModel(makeTier({ maxProjects: 1 }), 'monthly')
      expect(model.includedLimits.some(l => l.includes('1 project') && !l.includes('projects'))).toBe(true)
    })

    it('shows monitor count and interval', () => {
      const model = buildPricingCardModel(makeTier({ maxSystems: 10, monitorIntervalSeconds: 60 }), 'monthly')
      expect(model.includedLimits.some(l => l.includes('10 monitors') && l.includes('60s'))).toBe(true)
    })

    it('includes session replays when enabled', () => {
      const model = buildPricingCardModel(makeTier({ sessionReplayEnabled: true, monthlyReplayLimit: 5000 }), 'monthly')
      expect(model.includedLimits.some(l => l.includes('5K') && l.includes('session replays'))).toBe(true)
    })

    it('excludes session replays when disabled', () => {
      const model = buildPricingCardModel(makeTier({ sessionReplayEnabled: false }), 'monthly')
      expect(model.includedLimits.some(l => l.includes('replay'))).toBe(false)
    })

    it('includes retention info', () => {
      const model = buildPricingCardModel(makeTier({ retentionDays: 30 }), 'monthly')
      expect(model.includedLimits.some(l => l.includes('30-day retention'))).toBe(true)
    })

    it('shows mixed retention when different', () => {
      const model = buildPricingCardModel(makeTier({
        retentionDays: 30,
        logRetentionDays: 7,
        replayRetentionDays: 14,
        llmRetentionDays: 30,
      }), 'monthly')
      expect(model.includedLimits.some(l => l.includes('30d errors'))).toBe(true)
    })
  })

  describe('platform features', () => {
    it('includes status pages with custom domains', () => {
      const model = buildPricingCardModel(makeTier({
        statusPagesEnabled: true,
        statusPageCustomDomainEnabled: true
      }), 'monthly')
      expect(model.platformFeatures).toContain('Status pages with custom domains')
    })

    it('includes Slack integration', () => {
      const model = buildPricingCardModel(makeTier({ slackEnabled: true }), 'monthly')
      expect(model.platformFeatures).toContain('Slack integration')
    })

    it('includes SAML SSO', () => {
      const model = buildPricingCardModel(makeTier({ samlEnabled: true }), 'monthly')
      expect(model.platformFeatures).toContain('SAML SSO')
    })
  })

  describe('overages', () => {
    it('builds overage rates for paid tiers', () => {
      const model = buildPricingCardModel(makeTier({
        errorOverageRateCentsPer1k: 50,
        overageRateCentsPerGb: 200,
      }), 'monthly')
      expect(model.overages.length).toBeGreaterThan(0)
      expect(model.overages.some(o => o.label === 'Errors')).toBe(true)
    })

    it('no overages for free tier', () => {
      const model = buildPricingCardModel(makeTier({ monthlyPriceCents: 0 }), 'monthly')
      expect(model.overages).toHaveLength(0)
    })
  })

  describe('CTA', () => {
    it('shows Start Free for free tier', () => {
      const model = buildPricingCardModel(makeTier({ monthlyPriceCents: 0 }), 'monthly')
      expect(model.cta).toBe('Start Free')
    })

    it('shows trial CTA with trial days', () => {
      const model = buildPricingCardModel(makeTier({ trialDays: 14 }), 'monthly')
      expect(model.cta).toBe('Start 14-Day Trial')
    })

    it('shows generic trial CTA without trial days', () => {
      const model = buildPricingCardModel(makeTier({ trialDays: null }), 'monthly')
      expect(model.cta).toBe('Start Trial')
    })

    it('uses custom CTA link', () => {
      const model = buildPricingCardModel(makeTier(), 'monthly', '/custom')
      expect(model.ctaLink).toBe('/custom')
    })
  })

  describe('tier descriptions', () => {
    it('has description for FREE tier', () => {
      const model = buildPricingCardModel(makeTier({ tierName: 'FREE' }), 'monthly')
      expect(model.description).toContain('side projects')
    })

    it('has description for PRO tier', () => {
      const model = buildPricingCardModel(makeTier({ tierName: 'PRO' }), 'monthly')
      expect(model.description).toContain('growing teams')
    })

    it('has description for TEAM tier', () => {
      const model = buildPricingCardModel(makeTier({ tierName: 'TEAM' }), 'monthly')
      expect(model.description).toContain('scale')
    })

    it('has fallback description', () => {
      const model = buildPricingCardModel(makeTier({ tierName: 'CUSTOM' }), 'monthly')
      expect(model.description.length).toBeGreaterThan(0)
    })
  })
})
