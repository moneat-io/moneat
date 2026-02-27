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
    profilingEnabled: false,
    networkMonitoringEnabled: false,
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

    it('ingestion limit replaces error limit in unified model', () => {
      const model = buildPricingCardModel(makeTier({ monthlyGbLimit: 50 * BYTES_PER_GB }), 'monthly')
      expect(model.includedLimits.some(l => l.includes('50 GB') && l.includes('ingestion'))).toBe(true)
      // Per-type error limits are no longer displayed
      expect(model.includedLimits.some(l => l.includes('errors'))).toBe(false)
    })

    it('includes ingestion data limit', () => {
      const model = buildPricingCardModel(makeTier({ monthlyGbLimit: 10 * BYTES_PER_GB }), 'monthly')
      expect(model.includedLimits.some(l => l.includes('10 GB') && l.includes('ingestion'))).toBe(true)
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

    it('session replays are in platform features unconditionally', () => {
      const model = buildPricingCardModel(makeTier({ sessionReplayEnabled: false }), 'monthly')
      // In unified model, replays are not count-limited in includedLimits
      expect(model.includedLimits.some(l => l.includes('replay'))).toBe(false)
      // But they appear in platform features
      expect(model.platformFeatures.some(f => f.includes('session replays'))).toBe(true)
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

    it('includes continuous profiling unconditionally', () => {
      const model = buildPricingCardModel(makeTier({ profilingEnabled: false }), 'monthly')
      expect(model.platformFeatures).toContain('Continuous profiling')
    })

    it('includes network monitoring unconditionally', () => {
      const model = buildPricingCardModel(makeTier({ networkMonitoringEnabled: false }), 'monthly')
      expect(model.platformFeatures).toContain('Network monitoring')
    })
  })

  describe('APM and metrics limits', () => {
    it('APM spans no longer shown as separate limit in unified model', () => {
      const model = buildPricingCardModel(makeTier({ monthlyApmSpanLimit: 10_000_000 }), 'monthly')
      // APM spans are folded into unified ingestion GB
      expect(model.includedLimits.some(l => l.includes('APM spans'))).toBe(false)
    })

    it('includes custom metric limit in included limits', () => {
      const model = buildPricingCardModel(makeTier({ monthlyCustomMetricLimit: 1_000_000 }), 'monthly')
      expect(model.includedLimits.some(l => l.includes('1M') && l.includes('custom metrics'))).toBe(true)
    })

    it('includes hosts limit when maxHosts is set', () => {
      const model = buildPricingCardModel(makeTier({ maxHosts: 3 }), 'monthly')
      expect(model.includedLimits.some(l => l.includes('Up to 3 hosts'))).toBe(true)
    })

    it('includes Unlimited hosts when maxHosts is null', () => {
      const model = buildPricingCardModel(makeTier({ maxHosts: null, monthlyPriceCents: 2900 }), 'monthly')
      expect(model.includedLimits.some(l => l.includes('Unlimited hosts'))).toBe(true)
    })
  })

  describe('overages', () => {
    it('builds unified ingestion overage for paid tiers', () => {
      const model = buildPricingCardModel(makeTier({
        overageRateCentsPerGb: 40,
      }), 'monthly')
      expect(model.overages.length).toBeGreaterThan(0)
      expect(model.overages.some(o => o.label === 'Ingestion')).toBe(true)
      // Per-type error overages are no longer shown
      expect(model.overages.some(o => o.label === 'Errors')).toBe(false)
    })

    it('includes custom metric overage when set', () => {
      const model = buildPricingCardModel(makeTier({
        customMetricOverageRateCentsPer100k: 50,
      }), 'monthly')
      expect(model.overages.some(o => o.label === 'Custom metrics')).toBe(true)
      // APM span overages are no longer shown separately
      expect(model.overages.some(o => o.label === 'APM spans')).toBe(false)
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
