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
import { http, HttpResponse } from 'msw'
import { server } from '@/test/mocks/server'
import { api } from '@/lib/api'

const API_BASE = 'http://localhost:8080'

describe('Billing API', () => {
  beforeEach(() => {
    localStorage.clear()
    sessionStorage.clear()
    sessionStorage.setItem('authenticated', 'true')
  })

  // ──── getBillingPlans ────

  it('fetches billing plans', async () => {
    const mockPlans = {
      plans: [{ tier: { tierName: 'PRO' }, trialDays: 14 }],
      stripeEnabled: true,
      publishableKey: 'pk_test_xxx',
    }

    server.use(
      http.get(`${API_BASE}/v1/billing/plans`, () => {
        return HttpResponse.json(mockPlans)
      })
    )

    const result = await api.getBillingPlans()
    expect(result).toEqual(mockPlans)
  })

  // ──── getBillingUsage ────

  it('fetches billing usage', async () => {
    const mockUsage = {
      organizationId: 1,
      plan: 'PRO',
      status: 'active',
      usedUnits: 1000,
      totalLimitUnits: 10000,
      withinQuota: true,
    }

    server.use(
      http.get(`${API_BASE}/v1/billing/usage`, () => {
        return HttpResponse.json(mockUsage)
      })
    )

    const result = await api.getBillingUsage()
    expect(result).toEqual(mockUsage)
  })

  it('fetches billing usage insights', async () => {
    const mockInsights = {
      organizationId: 1,
      periodStart: '2026-01-01',
      periodEnd: '2026-01-31',
      generatedAt: '2026-01-15T12:00:00Z',
      billingMode: 'cloud',
      usage: {
        organizationId: 1,
        plan: 'PRO',
        status: 'active',
        usedUnits: 1000,
        totalLimitUnits: 10000,
        withinQuota: true,
      },
      dimensions: [
        {
          key: 'ingestion',
          label: 'GB-Billed Ingestion',
          unit: 'bytes',
          used: 1073741824,
          baseLimit: 2147483648,
          effectiveLimit: 2147483648,
          percentOfBase: 50,
          percentOfEffective: 50,
          overageCentsEstimate: 0,
          overageRateLabel: '$0.40/GB',
          forecast: {
            window: '7d',
            confidence: 'high',
            dailyRate: 100,
            projectedPeriodEndUsage: 2147483648,
            projectedBaseLimitHitDate: null,
            projectedEffectiveLimitHitDate: null,
            projectedOverageCents: 0,
            riskLevel: 'watch',
            summary: 'Usage is trending below the limit.',
          },
          contributors: [],
          daily: [],
        },
      ],
      apmSpanDebug: null,
    }

    server.use(
      http.get(`${API_BASE}/v1/billing/usage/insights`, () => {
        return HttpResponse.json(mockInsights)
      })
    )

    const result = await api.getBillingUsageInsights()
    expect(result).toEqual(mockInsights)
  })

  it('fetches APM span usage debug groups', async () => {
    const mockDebug = {
      organizationId: 1,
      periodStart: '2026-01-01',
      periodEnd: '2026-01-31',
      totalSpans: 42,
      groups: [
        {
          source: 'otlp',
          service: 'api',
          operation: 'GET /checkout',
          resource: 'GET /checkout',
          spanType: '',
          env: 'prod',
          kind: 'SERVER',
          scopeName: 'opentelemetry.instrumentation.ktor',
          scopeVersion: '1.0.0',
          projectId: null,
          projectName: null,
          projectSlug: null,
          spanCount: 42,
          traceCount: 12,
          errorCount: 1,
          avgDurationMs: 12.5,
          maxDurationMs: 200,
          percentage: 100,
          sampleTraceId: 'trace-1',
          latestSpanAt: '2026-01-15 12:00:00',
        },
      ],
    }

    server.use(
      http.get(`${API_BASE}/v1/billing/usage/apm-spans`, ({ request }) => {
        const url = new URL(request.url)
        expect(url.searchParams.get('limit')).toBe('20')
        return HttpResponse.json(mockDebug)
      })
    )

    const result = await api.getBillingApmSpanUsageDebug()
    expect(result).toEqual(mockDebug)
  })

  it('normalizes APM span usage debug limits', async () => {
    const seenLimits: string[] = []
    const mockDebug = {
      organizationId: 1,
      periodStart: '2026-01-01',
      periodEnd: '2026-01-31',
      totalSpans: 0,
      groups: [],
    }

    server.use(
      http.get(`${API_BASE}/v1/billing/usage/apm-spans`, ({ request }) => {
        const url = new URL(request.url)
        seenLimits.push(url.searchParams.get('limit') ?? '')
        return HttpResponse.json(mockDebug)
      })
    )

    await api.getBillingApmSpanUsageDebug(3.8)
    await api.getBillingApmSpanUsageDebug(0)
    await api.getBillingApmSpanUsageDebug(Number.NaN)

    expect(seenLimits).toEqual(['3', '1', '20'])
  })

  // ──── createBillingCheckoutSession ────

  it('creates a billing checkout session', async () => {
    const mockSession = { sessionId: 'cs_test_123', url: 'https://checkout.stripe.com/123' }

    server.use(
      http.post(`${API_BASE}/v1/billing/checkout`, async ({ request }) => {
        const body = (await request.json()) as Record<string, unknown>
        expect(body.tierName).toBe('pro')
        expect(body.billingInterval).toBe('monthly')
        expect(body.successUrl).toBe('https://app.example.com/settings?checkout=success')
        expect(body.cancelUrl).toBe('https://app.example.com/settings')
        return HttpResponse.json(mockSession)
      })
    )

    const result = await api.createBillingCheckoutSession({
      tierName: 'pro',
      billingInterval: 'monthly',
      successUrl: 'https://app.example.com/settings?checkout=success',
      cancelUrl: 'https://app.example.com/settings',
    })
    expect(result).toEqual(mockSession)
  })

  // ──── getBillingInvoices ────

  it('fetches billing invoices', async () => {
    const mockInvoices = [
      { id: 'inv_1', amountDue: 2900, status: 'paid', created: '2024-01-01' },
    ]

    server.use(
      http.get(`${API_BASE}/v1/billing/invoices`, () => {
        return HttpResponse.json(mockInvoices)
      })
    )

    const result = await api.getBillingInvoices()
    expect(result).toEqual(mockInvoices)
  })

  // ──── getBillingPaymentMethod ────

  it('fetches billing payment method', async () => {
    const mockPayment = { brand: 'visa', last4: '4242', expMonth: 12, expYear: 2025 }

    server.use(
      http.get(`${API_BASE}/v1/billing/payment-method`, () => {
        return HttpResponse.json(mockPayment)
      })
    )

    const result = await api.getBillingPaymentMethod()
    expect(result).toEqual(mockPayment)
  })

  // ──── createBillingSetupIntent ────

  it('creates a billing setup intent', async () => {
    const mockIntent = { clientSecret: 'seti_secret_123' }

    server.use(
      http.post(`${API_BASE}/v1/billing/setup-intent`, () => {
        return HttpResponse.json(mockIntent)
      })
    )

    const result = await api.createBillingSetupIntent()
    expect(result).toEqual(mockIntent)
  })

  // ──── confirmBillingSetupIntent ────

  it('confirms a billing setup intent', async () => {
    server.use(
      http.post(`${API_BASE}/v1/billing/setup-intent/confirm`, async ({ request }) => {
        const body = (await request.json()) as Record<string, unknown>
        expect(body.setupIntentId).toBe('seti_123')
        return HttpResponse.json({ success: true })
      })
    )

    const result = await api.confirmBillingSetupIntent('seti_123')
    expect(result.success).toBe(true)
  })

  // ──── cancelBillingSubscription ────

  it('cancels billing subscription', async () => {
    const mockResponse = { cancelledAt: '2024-03-01T00:00:00Z' }

    server.use(
      http.post(`${API_BASE}/v1/billing/cancel`, () => {
        return HttpResponse.json(mockResponse)
      })
    )

    const result = await api.cancelBillingSubscription()
    expect(result).toEqual(mockResponse)
  })

  // ──── updatePaygBudget ────

  it('updates PAYG budget', async () => {
    server.use(
      http.put(`${API_BASE}/v1/billing/payg-budget`, async ({ request }) => {
        const body = (await request.json()) as Record<string, unknown>
        expect(body.paygBudgetCents).toBe(5000)
        return HttpResponse.json({ paygBudgetCents: 5000 })
      })
    )

    const result = await api.updatePaygBudget(5000)
    expect(result.paygBudgetCents).toBe(5000)
  })

  // ──── updateOnCallSeats ────

  it('updates on-call seats', async () => {
    const mockResponse = { seats: 10, updatedAt: '2024-03-01T00:00:00Z' }

    server.use(
      http.put(`${API_BASE}/v1/billing/oncall-seats`, async ({ request }) => {
        const body = (await request.json()) as Record<string, unknown>
        expect(body.seats).toBe(10)
        return HttpResponse.json(mockResponse)
      })
    )

    const result = await api.updateOnCallSeats(10)
    expect(result).toEqual(mockResponse)
  })
})
