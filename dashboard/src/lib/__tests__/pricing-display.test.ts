import { describe, it, expect } from 'vitest'
import { buildPricingCardModel, type PricingCardTierInput } from '../pricing-display'

describe('pricing-display', () => {
  const createBaseTier = (overrides: Partial<PricingCardTierInput> = {}): PricingCardTierInput => ({
    tierName: 'PRO',
    monthlyPriceCents: 2900,
    yearlyPriceCents: 29000,
    trialDays: 14,
    monthlyGbLimit: 10 * 1024 * 1024 * 1024, // 10 GB
    retentionDays: 90,
    maxProjects: 10,
    maxSystems: 50,
    monitorIntervalSeconds: 60,
    sessionReplayEnabled: true,
    statusPagesEnabled: true,
    statusPageCustomDomainEnabled: false,
    slackEnabled: true,
    incidentIoEnabled: false,
    samlEnabled: false,
    oidcEnabled: false,
    prioritySupportEnabled: false,
    slaEnabled: false,
    customRetentionEnabled: false,
    ...overrides,
  })

  describe('Price calculations', () => {
    it('calculates monthly price from cents', () => {
      const tier = createBaseTier({ monthlyPriceCents: 2900 })
      const model = buildPricingCardModel(tier, 'monthly')

      expect(model.monthlyPrice).toBe(29)
      expect(model.displayPrice).toBe(29)
    })

    it('calculates yearly monthly price from yearly cents', () => {
      const tier = createBaseTier({ yearlyPriceCents: 29000 })
      const model = buildPricingCardModel(tier, 'yearly')

      expect(model.yearlyMonthlyPrice).toBeCloseTo(29000 / (100 * 12), 2)
      expect(model.yearlyTotalPrice).toBe(290)
    })

    it('displays monthly price for monthly billing interval', () => {
      const tier = createBaseTier({ monthlyPriceCents: 5000, yearlyPriceCents: 50000 })
      const model = buildPricingCardModel(tier, 'monthly')

      expect(model.displayPrice).toBe(50)
    })

    it('displays yearly monthly price for yearly billing interval', () => {
      const tier = createBaseTier({ monthlyPriceCents: 5000, yearlyPriceCents: 50000 })
      const model = buildPricingCardModel(tier, 'yearly')

      expect(model.displayPrice).toBeCloseTo(50000 / (100 * 12), 2)
    })
  })

  describe('Data limit formatting', () => {
    it('formats GB limit correctly', () => {
      const tier = createBaseTier({ monthlyGbLimit: 10 * 1024 * 1024 * 1024 })
      const model = buildPricingCardModel(tier, 'monthly')

      expect(model.features).toContain('10 GB/mo data')
    })

    it('formats TB limit correctly when >= 1024 GB', () => {
      const tier = createBaseTier({ monthlyGbLimit: 2048 * 1024 * 1024 * 1024 })
      const model = buildPricingCardModel(tier, 'monthly')

      expect(model.features).toContain('2 TB/mo data')
    })

    it('formats fractional GB without trailing zero', () => {
      const tier = createBaseTier({ monthlyGbLimit: Math.floor(2.5 * 1024 * 1024 * 1024) })
      const model = buildPricingCardModel(tier, 'monthly')

      expect(model.features[0]).toMatch(/2\.5 GB\/mo data/)
    })

    it('formats fractional TB without trailing zero', () => {
      const tier = createBaseTier({ monthlyGbLimit: Math.floor(1.5 * 1024 * 1024 * 1024 * 1024) })
      const model = buildPricingCardModel(tier, 'monthly')

      expect(model.features[0]).toMatch(/1\.5 TB\/mo data/)
    })
  })

  describe('Monitor limit formatting', () => {
    it('formats finite monitor count', () => {
      const tier = createBaseTier({ maxSystems: 50, monitorIntervalSeconds: 60 })
      const model = buildPricingCardModel(tier, 'monthly')

      expect(model.features).toContain('50 monitors (60s interval)')
    })

    it('formats unlimited monitors when at sentinel value', () => {
      const tier = createBaseTier({ maxSystems: 2147483647, monitorIntervalSeconds: 30 })
      const model = buildPricingCardModel(tier, 'monthly')

      expect(model.features).toContain('Unlimited monitors (30s interval)')
    })
  })

  describe('Project limit formatting', () => {
    it('formats single project correctly', () => {
      const tier = createBaseTier({ maxProjects: 1 })
      const model = buildPricingCardModel(tier, 'monthly')

      expect(model.features).toContain('1 project')
    })

    it('formats multiple projects correctly', () => {
      const tier = createBaseTier({ maxProjects: 10 })
      const model = buildPricingCardModel(tier, 'monthly')

      expect(model.features).toContain('10 projects')
    })

    it('formats unlimited projects when null', () => {
      const tier = createBaseTier({ maxProjects: null })
      const model = buildPricingCardModel(tier, 'monthly')

      expect(model.features).toContain('Unlimited projects')
    })
  })

  describe('Feature list building', () => {
    it('includes session replays when enabled', () => {
      const tier = createBaseTier({ sessionReplayEnabled: true })
      const model = buildPricingCardModel(tier, 'monthly')

      expect(model.features).toContain('Session replays and events')
    })

    it('excludes session replays when disabled', () => {
      const tier = createBaseTier({ sessionReplayEnabled: false })
      const model = buildPricingCardModel(tier, 'monthly')

      expect(model.features).not.toContain('Session replays and events')
    })

    it('includes custom status pages with custom domains', () => {
      const tier = createBaseTier({
        statusPagesEnabled: true,
        statusPageCustomDomainEnabled: true,
      })
      const model = buildPricingCardModel(tier, 'monthly')

      expect(model.features).toContain('Custom status pages with custom domains')
    })

    it('includes custom status pages without custom domains', () => {
      const tier = createBaseTier({
        statusPagesEnabled: true,
        statusPageCustomDomainEnabled: false,
      })
      const model = buildPricingCardModel(tier, 'monthly')

      expect(model.features).toContain('Custom status pages')
      expect(model.features).not.toContain('custom domains')
    })

    it('includes integrations when enabled', () => {
      const tier = createBaseTier({
        slackEnabled: true,
        incidentIoEnabled: true,
        samlEnabled: true,
        oidcEnabled: true,
      })
      const model = buildPricingCardModel(tier, 'monthly')

      expect(model.features).toContain('Slack integration')
      expect(model.features).toContain('incident.io integration')
      expect(model.features).toContain('SAML SSO integration')
      expect(model.features).toContain('OIDC SSO integration')
    })

    it('includes enterprise features when enabled', () => {
      const tier = createBaseTier({
        prioritySupportEnabled: true,
        slaEnabled: true,
        customRetentionEnabled: true,
      })
      const model = buildPricingCardModel(tier, 'monthly')

      expect(model.features).toContain('Priority support')
      expect(model.features).toContain('SLA guarantee')
      expect(model.features).toContain('Custom retention')
    })

    it('builds feature list in correct order', () => {
      const tier = createBaseTier()
      const model = buildPricingCardModel(tier, 'monthly')

      // Core features come first
      expect(model.features[0]).toMatch(/GB\/mo data/)
      expect(model.features[1]).toMatch(/day retention/)
      expect(model.features[2]).toMatch(/project/)
      expect(model.features[3]).toMatch(/monitor/)
    })
  })

  describe('CTA text generation', () => {
    it('returns "Start Free" for free tier', () => {
      const tier = createBaseTier({ monthlyPriceCents: 0 })
      const model = buildPricingCardModel(tier, 'monthly')

      expect(model.cta).toBe('Start Free')
    })

    it('returns trial CTA when trial days > 0', () => {
      const tier = createBaseTier({ trialDays: 14, monthlyPriceCents: 2900 })
      const model = buildPricingCardModel(tier, 'monthly')

      expect(model.cta).toBe('Start 14-Day Trial')
    })

    it('returns generic trial CTA when no trial days', () => {
      const tier = createBaseTier({ trialDays: null, monthlyPriceCents: 2900 })
      const model = buildPricingCardModel(tier, 'monthly')

      expect(model.cta).toBe('Start Trial')
    })

    it('returns generic trial CTA when trial days is 0', () => {
      const tier = createBaseTier({ trialDays: 0, monthlyPriceCents: 2900 })
      const model = buildPricingCardModel(tier, 'monthly')

      expect(model.cta).toBe('Start Trial')
    })
  })

  describe('Tier metadata', () => {
    it('formats tier name correctly', () => {
      const tier = createBaseTier({ tierName: 'PRO' })
      const model = buildPricingCardModel(tier, 'monthly')

      expect(model.name).toBe('Pro')
    })

    it('generates correct description for FREE tier', () => {
      const tier = createBaseTier({ tierName: 'FREE' })
      const model = buildPricingCardModel(tier, 'monthly')

      expect(model.description).toBe('Perfect for side projects and getting started')
    })

    it('generates correct description for PRO tier', () => {
      const tier = createBaseTier({ tierName: 'PRO' })
      const model = buildPricingCardModel(tier, 'monthly')

      expect(model.description).toBe('For growing teams shipping production apps')
    })

    it('generates correct description for TEAM tier', () => {
      const tier = createBaseTier({ tierName: 'TEAM' })
      const model = buildPricingCardModel(tier, 'monthly')

      expect(model.description).toBe('For teams that need scale and compliance')
    })

    it('generates correct description for BUSINESS tier', () => {
      const tier = createBaseTier({ tierName: 'BUSINESS' })
      const model = buildPricingCardModel(tier, 'monthly')

      expect(model.description).toBe('For enterprises with custom requirements')
    })

    it('highlights PRO tier by default', () => {
      const pro = buildPricingCardModel(createBaseTier({ tierName: 'PRO' }), 'monthly')
      const free = buildPricingCardModel(createBaseTier({ tierName: 'FREE' }), 'monthly')

      expect(pro.highlight).toBe(true)
      expect(free.highlight).toBe(false)
    })

    it('uses custom CTA link when provided', () => {
      const tier = createBaseTier()
      const model = buildPricingCardModel(tier, 'monthly', '/custom-signup')

      expect(model.ctaLink).toBe('/custom-signup')
    })

    it('uses default /signup CTA link', () => {
      const tier = createBaseTier()
      const model = buildPricingCardModel(tier, 'monthly')

      expect(model.ctaLink).toBe('/signup')
    })
  })
})
