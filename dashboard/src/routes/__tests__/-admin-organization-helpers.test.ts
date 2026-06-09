import {describe, expect, it, vi} from 'vitest'
import type {BillingUsage} from '@/lib/api'

vi.mock('@tanstack/react-router', () => ({
  Link: ({children}: {children: unknown}) => children,
  createFileRoute: () => (options: Record<string, unknown>) => options,
}))

vi.mock('@/hooks/useToast', () => ({
  useToast: () => ({toast: vi.fn()}),
}))

import {adminOrganizationHelperTestHooks as helpers} from '../admin.organizations.$orgId'

function billingUsage(overrides: Partial<BillingUsage> = {}): BillingUsage {
  return {
    organizationId: 1,
    periodStart: '2026-06-01',
    periodEnd: '2026-06-30',
    retentionDays: 30,
    usedUnits: 100,
    usedErrors: 10,
    errorLimit: 100,
    usedTransactions: 20,
    transactionLimit: 200,
    usedReplays: 3,
    replayLimit: 30,
    usedFeedback: 4,
    feedbackLimit: 40,
    usedBytes: 1000,
    usedApmSpanBytes: 200,
    usedInfraMetricBytes: 300,
    bytesLimit: 10_000,
    baseLimitUnits: 100,
    paygLimitUnits: 0,
    totalLimitUnits: 100,
    paygBudgetCents: 0,
    paygUsedUnits: 0,
    paygUsedCentsEstimate: 0,
    usedLlmEvents: 5,
    llmEventLimit: 50,
    usedAnalyticsPageviews: 600,
    analyticsPageviewLimit: 1000,
    usedApmSpans: 700,
    apmSpanLimit: 1000,
    usedCustomMetrics: 800,
    customMetricLimit: 1000,
    usedInfraMetricSeriesHours: 900,
    infraMetricSeriesHourLimit: 1000,
    plan: 'PRO',
    status: 'active',
    withinQuota: true,
    ...overrides,
  }
}

describe('admin organization helper coverage', () => {
  it('aggregates usage rows by date and event type', () => {
    const usage = [
      {date: '2026-06-02', eventType: 'logs', eventCount: 3, bytesIngested: 30},
      {date: '2026-06-01', eventType: 'error', eventCount: 2, bytesIngested: 20},
      {date: '2026-06-01', eventType: 'unknown', eventCount: 5, bytesIngested: 50},
    ]

    expect(helpers.buildUsageByDate(undefined)).toEqual([])
    expect(helpers.buildUsageByDate(usage)).toEqual([
      {date: '2026-06-01', error: 2, transaction: 0, replay: 0, feedback: 0, log: 0, total: 7},
      {date: '2026-06-02', error: 0, transaction: 0, replay: 0, feedback: 0, log: 3, total: 3},
    ])
    expect(helpers.buildUsageByType(usage)).toMatchObject({
      error: 2,
      log: 3,
      totalBytes: 100,
      totalEvents: 10,
    })
    expect(helpers.buildUsageByType(undefined)).toMatchObject({totalBytes: 0, totalEvents: 0})
  })

  it('calculates quota snapshots and target usage', () => {
    const usage = billingUsage()

    expect(helpers.getQuotaUsageSnapshot(usage, 'ingestion_bytes')).toEqual({used: 500, limit: 10_000})
    expect(helpers.getQuotaUsageSnapshot(usage, 'apm_spans')).toEqual({used: 700, limit: 1000})
    expect(helpers.getQuotaUsageSnapshot(usage, 'custom_metrics')).toEqual({used: 800, limit: 1000})
    expect(helpers.getQuotaUsageSnapshot(usage, 'infra_metrics')).toEqual({used: 900, limit: 1000})
    expect(helpers.getQuotaUsageSnapshot(usage, 'analytics_pageviews')).toEqual({used: 600, limit: 1000})
    expect(helpers.getQuotaUsageSnapshot(usage, 'errors')).toEqual({used: 10, limit: 100})
    expect(helpers.getQuotaUsageSnapshot(usage, 'transactions')).toEqual({used: 20, limit: 200})
    expect(helpers.getQuotaUsageSnapshot(usage, 'replays')).toEqual({used: 3, limit: 30})
    expect(helpers.getQuotaUsageSnapshot(usage, 'feedback')).toEqual({used: 4, limit: 40})
    expect(helpers.getQuotaUsageSnapshot(usage, 'llm_events')).toEqual({used: 5, limit: 50})

    expect(helpers.calculateTargetUsage({used: 10, limit: 250}, 80)).toBe(200)
    expect(helpers.calculateTargetUsage(null, 80)).toBeNull()
    expect(helpers.calculateTargetUsage({used: 10, limit: 250}, null)).toBeNull()
  })

  it('parses quota target percentages and service labels', () => {
    expect(helpers.parseTargetPercent('')).toBeNull()
    expect(helpers.parseTargetPercent('abc')).toBeNull()
    expect(helpers.parseTargetPercent('-1')).toBeNull()
    expect(helpers.parseTargetPercent('501')).toBeNull()
    expect(helpers.parseTargetPercent('80')).toBe(80)
    expect(helpers.serviceCountLabel(1)).toBe('1 service')
    expect(helpers.serviceCountLabel(2)).toBe('2 services')
  })
})
