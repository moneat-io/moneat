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

import { describe, it, expect, beforeEach } from 'vitest'
import {
  buildPricingCardModel,
  type PricingCardTierInput,
} from '../pricing-display'

const BYTES_PER_GB = 1024 * 1024 * 1024
const UNLIMITED_SENTINEL = 9_007_199_254_740_000
const UNLIMITED_SYSTEMS_SENTINEL = 2147483647

function makeTier(
  overrides: Partial<PricingCardTierInput> = {}
): PricingCardTierInput {
  return {
    tierName: 'PRO',
    monthlyPriceCents: 2900,
    yearlyPriceCents: 29000,
    trialDays: 14,
    monthlyGbLimit: 10 * BYTES_PER_GB,
    retentionDays: 30,
    maxProjects: null,
    maxSystems: 10,
    monitorIntervalSeconds: 60,
    sessionReplayEnabled: true,
    statusPagesEnabled: false,
    statusPageCustomDomainEnabled: false,
    slackEnabled: false,
    incidentIoEnabled: false,
    samlEnabled: false,
    oidcEnabled: false,
    prioritySupportEnabled: false,
    slaEnabled: false,
    customRetentionEnabled: false,
    ...overrides,
  }
}

describe('pricing-display – branch coverage', () => {
  beforeEach(() => {
    localStorage.clear()
    sessionStorage.clear()
    sessionStorage.setItem('authenticated', 'true')
  })

  // ──── formatEventLimit branches ────

  describe('formatEventLimit branches', () => {
    it('shows "Unlimited" for values at sentinel threshold', () => {
      const model = buildPricingCardModel(
        makeTier({ monthlyCustomMetricLimit: UNLIMITED_SENTINEL }),
        'monthly'
      )
      expect(
        model.includedLimits.some(
          (l) => l.includes('Unlimited') && l.includes('custom metrics')
        )
      ).toBe(true)
    })

    it('shows "Unlimited" for negative APM span values (isUnlimited < 0 branch)', () => {
      const model = buildPricingCardModel(
        makeTier({ monthlyApmSpanLimit: -1 }),
        'monthly'
      )
      // Negative values fail the > 0 guard so no APM spans line is shown
      expect(
        model.includedLimits.some((l) => l.includes('APM spans'))
      ).toBe(false)
    })

    it('formats value in millions (M)', () => {
      const model = buildPricingCardModel(
        makeTier({ monthlyApmSpanLimit: 5_000_000 }),
        'monthly'
      )
      expect(
        model.includedLimits.some(
          (l) => l.includes('5M') && l.includes('APM spans')
        )
      ).toBe(true)
    })

    it('formats value in thousands (K)', () => {
      const model = buildPricingCardModel(
        makeTier({ monthlyCustomMetricLimit: 500_000 }),
        'monthly'
      )
      expect(
        model.includedLimits.some(
          (l) => l.includes('500K') && l.includes('custom metrics')
        )
      ).toBe(true)
    })

    it('formats small values with toLocaleString', () => {
      const model = buildPricingCardModel(
        makeTier({ monthlyCustomMetricLimit: 999 }),
        'monthly'
      )
      expect(
        model.includedLimits.some(
          (l) => l.includes('999') && l.includes('custom metrics')
        )
      ).toBe(true)
    })

    it('does not show limit when value is 0', () => {
      const model = buildPricingCardModel(
        makeTier({ monthlyCustomMetricLimit: 0 }),
        'monthly'
      )
      expect(
        model.includedLimits.some((l) => l.includes('custom metrics'))
      ).toBe(false)
    })

    it('does not show limit when value is undefined', () => {
      const model = buildPricingCardModel(
        makeTier({ monthlyCustomMetricLimit: undefined }),
        'monthly'
      )
      expect(
        model.includedLimits.some((l) => l.includes('custom metrics'))
      ).toBe(false)
    })
  })

  // ──── isUnlimited branches ────

  describe('isUnlimited – APM spans', () => {
    it('shows "Unlimited" for unlimited APM spans', () => {
      const model = buildPricingCardModel(
        makeTier({ monthlyApmSpanLimit: UNLIMITED_SENTINEL }),
        'monthly'
      )
      expect(
        model.includedLimits.some(
          (l) => l.includes('Unlimited') && l.includes('APM spans')
        )
      ).toBe(true)
    })
  })

  // ──── maxHosts branches ────

  describe('maxHosts branches', () => {
    it('shows "Unlimited hosts" for sentinel value', () => {
      const model = buildPricingCardModel(
        makeTier({ maxHosts: UNLIMITED_SYSTEMS_SENTINEL }),
        'monthly'
      )
      expect(
        model.includedLimits.some((l) => l.includes('Unlimited hosts'))
      ).toBe(true)
    })

    it('does not show hosts when maxHosts is undefined', () => {
      const model = buildPricingCardModel(
        makeTier({ maxHosts: undefined }),
        'monthly'
      )
      expect(model.includedLimits.some((l) => l.includes('hosts'))).toBe(false)
    })
  })

  // ──── analytics sites branches ────

  describe('analytics sites branches', () => {
    it('shows site count when maxAnalyticsSites is a number', () => {
      const model = buildPricingCardModel(
        makeTier({ maxAnalyticsSites: 3 }),
        'monthly'
      )
      expect(
        model.includedLimits.some((l) => l.includes('3 analytics sites'))
      ).toBe(true)
    })

    it('shows "Unlimited analytics sites" when maxAnalyticsSites is null', () => {
      const model = buildPricingCardModel(
        makeTier({ maxAnalyticsSites: null }),
        'monthly'
      )
      expect(
        model.includedLimits.some((l) =>
          l.includes('Unlimited analytics sites')
        )
      ).toBe(true)
    })

    it('shows singular "site" for 1 analytics site', () => {
      const model = buildPricingCardModel(
        makeTier({ maxAnalyticsSites: 1 }),
        'monthly'
      )
      expect(
        model.includedLimits.some(
          (l) => l.includes('1 analytics site') && !l.includes('sites')
        )
      ).toBe(true)
    })
  })

  // ──── analytics pageview limit ────

  describe('analytics pageview limit', () => {
    it('shows pageview limit when set', () => {
      const model = buildPricingCardModel(
        makeTier({ monthlyAnalyticsPageviewLimit: 1_000_000 }),
        'monthly'
      )
      expect(
        model.includedLimits.some(
          (l) => l.includes('1M') && l.includes('page views')
        )
      ).toBe(true)
    })

    it('does not show pageview limit when 0', () => {
      const model = buildPricingCardModel(
        makeTier({ monthlyAnalyticsPageviewLimit: 0 }),
        'monthly'
      )
      expect(
        model.includedLimits.some((l) => l.includes('page views'))
      ).toBe(false)
    })
  })

  // ──── analytics retention branches ────

  describe('analytics retention', () => {
    it('shows years for retention >= 365 days', () => {
      const model = buildPricingCardModel(
        makeTier({ analyticsRetentionDays: 730 }),
        'monthly'
      )
      expect(
        model.includedLimits.some(
          (l) => l.includes('2-year') && l.includes('analytics retention')
        )
      ).toBe(true)
    })

    it('shows days for retention < 365 days', () => {
      const model = buildPricingCardModel(
        makeTier({ analyticsRetentionDays: 90 }),
        'monthly'
      )
      expect(
        model.includedLimits.some(
          (l) => l.includes('90-day') && l.includes('analytics retention')
        )
      ).toBe(true)
    })

    it('does not show analytics retention when 0', () => {
      const model = buildPricingCardModel(
        makeTier({ analyticsRetentionDays: 0 }),
        'monthly'
      )
      expect(
        model.includedLimits.some((l) => l.includes('analytics retention'))
      ).toBe(false)
    })
  })

  // ──── discord and on-call features ────

  describe('discord and on-call features', () => {
    it('includes Discord integration when enabled', () => {
      const model = buildPricingCardModel(
        makeTier({ discordEnabled: true }),
        'monthly'
      )
      expect(model.platformFeatures).toContain('Discord integration')
    })

    it('does not include Discord when disabled', () => {
      const model = buildPricingCardModel(
        makeTier({ discordEnabled: false }),
        'monthly'
      )
      expect(model.platformFeatures).not.toContain('Discord integration')
    })

    it('includes on-call with per-user price', () => {
      const model = buildPricingCardModel(
        makeTier({ oncallEnabled: true, oncallPerUserMonthlyCents: 500 }),
        'monthly'
      )
      expect(
        model.platformFeatures.some((f) => f.includes('On-call') && f.includes('$5'))
      ).toBe(true)
    })

    it('does not include on-call when disabled', () => {
      const model = buildPricingCardModel(
        makeTier({ oncallEnabled: false }),
        'monthly'
      )
      expect(
        model.platformFeatures.some((f) => f.includes('On-call'))
      ).toBe(false)
    })

    it('does not include on-call when enabled but no price', () => {
      const model = buildPricingCardModel(
        makeTier({ oncallEnabled: true, oncallPerUserMonthlyCents: 0 }),
        'monthly'
      )
      expect(
        model.platformFeatures.some((f) => f.includes('On-call'))
      ).toBe(false)
    })
  })

  // ──── status pages – no status pages ────

  describe('status pages branches', () => {
    it('does not include status pages when disabled', () => {
      const model = buildPricingCardModel(
        makeTier({ statusPagesEnabled: false, statusPageCustomDomainEnabled: false }),
        'monthly'
      )
      expect(
        model.platformFeatures.some((f) => f.includes('Status pages') || f.includes('status pages'))
      ).toBe(false)
    })
  })

  // ──── buildOverages – APM span and analytics overages ────

  describe('buildOverages – additional overage types', () => {
    it('includes APM span overage when set', () => {
      const model = buildPricingCardModel(
        makeTier({ apmSpanOverageRateCentsPer1m: 100 }),
        'monthly'
      )
      expect(model.overages.some((o) => o.label === 'APM spans')).toBe(true)
    })

    it('includes analytics pageview overage when set', () => {
      const model = buildPricingCardModel(
        makeTier({ analyticsPageviewOverageRateCentsPer100k: 50 }),
        'monthly'
      )
      expect(model.overages.some((o) => o.label === 'Page views')).toBe(true)
    })

    it('does not include overage when rate is 0', () => {
      const model = buildPricingCardModel(
        makeTier({
          overageRateCentsPerGb: 0,
          apmSpanOverageRateCentsPer1m: 0,
          analyticsPageviewOverageRateCentsPer100k: 0,
        }),
        'monthly'
      )
      expect(model.overages).toHaveLength(0)
    })
  })

  // ──── formatCompactNumber branches ────

  describe('formatCompactNumber – via data limit', () => {
    it('formats integer GB values without decimal', () => {
      const model = buildPricingCardModel(
        makeTier({ monthlyGbLimit: 5 * BYTES_PER_GB }),
        'monthly'
      )
      expect(model.includedLimits.includes('5 GB ingestion')).toBe(true)
    })

    it('formats fractional GB values with one decimal', () => {
      const model = buildPricingCardModel(
        makeTier({ monthlyGbLimit: Math.floor(1.5 * BYTES_PER_GB) }),
        'monthly'
      )
      expect(
        model.includedLimits.some((l) => l.includes('1.5 GB'))
      ).toBe(true)
    })
  })

  // ──── ctaForTier – trialDays is undefined ────

  describe('ctaForTier – trialDays undefined', () => {
    it('shows "Start Trial" when trialDays is undefined', () => {
      const model = buildPricingCardModel(
        makeTier({ trialDays: undefined }),
        'monthly'
      )
      expect(model.cta).toBe('Start Trial')
    })
  })

  // ──── formatDataLimit – zero bytes ────

  describe('formatDataLimit – zero bytes', () => {
    it('shows 0 GB for zero-byte limit', () => {
      const model = buildPricingCardModel(
        makeTier({ monthlyGbLimit: 0 }),
        'monthly'
      )
      expect(model.includedLimits.includes('0 GB ingestion')).toBe(true)
    })
  })

  // ──── retention – different per-type values ────

  describe('retention – different per-type values', () => {
    it('shows combined retention for partially different values', () => {
      const model = buildPricingCardModel(
        makeTier({
          retentionDays: 90,
          logRetentionDays: 30,
          replayRetentionDays: 90,
          llmRetentionDays: 90,
        }),
        'monthly'
      )
      expect(
        model.includedLimits.some(
          (l) => l.includes('90d errors') && l.includes('30d logs') && l.includes('90d APM traces')
        )
      ).toBe(true)
    })

    it('shows uniform retention when all match', () => {
      const model = buildPricingCardModel(
        makeTier({
          retentionDays: 30,
          logRetentionDays: 30,
          replayRetentionDays: 30,
          llmRetentionDays: 30,
        }),
        'monthly'
      )
      expect(model.includedLimits.includes('30-day retention')).toBe(true)
    })

    it('defaults sub-retentions to retentionDays when not provided', () => {
      const model = buildPricingCardModel(
        makeTier({ retentionDays: 60 }),
        'monthly'
      )
      expect(model.includedLimits.includes('60-day retention')).toBe(true)
    })
  })
})
