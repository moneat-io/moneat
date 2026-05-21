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

describe('adminMethods', () => {
  beforeEach(() => {
    localStorage.clear()
    sessionStorage.clear()
    sessionStorage.setItem('authenticated', 'true')
  })

  // ──── Overview ────

  describe('getAdminOverview', () => {
    it('fetches admin overview', async () => {
      const data = {
        totalOrganizations: 5,
        totalUsers: 20,
        totalEventsAllTime: 100000,
        totalEventsLast30Days: 5000,
        mrr: 499.99,
        subscriptionsByPlan: { free: 3, pro: 2 },
        eventsLast30Days: [{ date: '2026-01-01', count: 100 }],
      }
      server.use(
        http.get(`${API_BASE}/v1/admin/overview`, () => HttpResponse.json(data))
      )

      const result = await api.getAdminOverview()
      expect(result).toEqual(data)
    })
  })

  // ──── Organizations ────

  describe('getAdminOrganizations', () => {
    it('fetches organizations with default pagination', async () => {
      const orgs = [
        {
          id: 1,
          name: 'Org A',
          slug: 'org-a',
          plan: 'pro',
          eventCountThisMonth: 1000,
          bytesIngestedThisMonth: 50000,
          projectCount: 3,
          memberCount: 5,
          quotaUsedPercent: 40,
        },
      ]
      server.use(
        http.get(`${API_BASE}/v1/admin/organizations`, ({ request }) => {
          const url = new URL(request.url)
          expect(url.searchParams.get('page')).toBe('1')
          expect(url.searchParams.get('limit')).toBe('25')
          return HttpResponse.json(orgs)
        })
      )

      const result = await api.getAdminOrganizations()
      expect(result).toEqual(orgs)
    })

    it('passes custom page and limit', async () => {
      server.use(
        http.get(`${API_BASE}/v1/admin/organizations`, ({ request }) => {
          const url = new URL(request.url)
          expect(url.searchParams.get('page')).toBe('3')
          expect(url.searchParams.get('limit')).toBe('10')
          return HttpResponse.json([])
        })
      )

      await api.getAdminOrganizations(3, 10)
    })
  })

  describe('getAdminOrgDetail', () => {
    it('fetches organization detail by id', async () => {
      const detail = {
        id: 42,
        name: 'Acme',
        slug: 'acme',
        companySize: '10-50',
        plan: 'pro',
        subscriptionStatus: 'active',
        memberCount: 8,
        projectCount: 4,
        eventCountThisMonth: 2500,
        bytesIngestedThisMonth: 120000,
        quotaUsedPercent: 65,
        members: [{ userId: 1, email: 'a@b.com', name: 'Alice', role: 'owner' }],
        projects: [{ id: 1, name: 'Web', slug: 'web', platform: 'javascript' }],
      }
      server.use(
        http.get(`${API_BASE}/v1/admin/organizations/42`, () =>
          HttpResponse.json(detail)
        )
      )

      const result = await api.getAdminOrgDetail(42)
      expect(result).toEqual(detail)
    })
  })

  describe('getAdminOrgUsage', () => {
    it('fetches org usage with default period', async () => {
      const usage = [
        { date: '2026-01-01', eventType: 'error', eventCount: 100, bytesIngested: 5000 },
      ]
      server.use(
        http.get(`${API_BASE}/v1/admin/organizations/1/usage`, ({ request }) => {
          const url = new URL(request.url)
          expect(url.searchParams.get('period')).toBe('7d')
          return HttpResponse.json(usage)
        })
      )

      const result = await api.getAdminOrgUsage(1)
      expect(result).toEqual(usage)
    })

    it('passes custom period', async () => {
      server.use(
        http.get(`${API_BASE}/v1/admin/organizations/1/usage`, ({ request }) => {
          const url = new URL(request.url)
          expect(url.searchParams.get('period')).toBe('30d')
          return HttpResponse.json([])
        })
      )

      await api.getAdminOrgUsage(1, '30d')
    })
  })

  describe('getAdminOrgQuotaUsage', () => {
    it('fetches quota usage for an organization', async () => {
      const quotaUsage = {
        organizationId: 1,
        periodStart: '2026-01-01',
        periodEnd: '2026-01-31',
        retentionDays: 30,
        usedUnits: 10,
        usedErrors: 10,
        errorLimit: 100,
        usedTransactions: 0,
        transactionLimit: 100,
        usedReplays: 0,
        replayLimit: 100,
        usedFeedback: 0,
        feedbackLimit: 100,
        usedBytes: 1073741824,
        bytesLimit: 10737418240,
        baseLimitUnits: 400,
        paygLimitUnits: 0,
        totalLimitUnits: 400,
        paygBudgetCents: 0,
        paygUsedUnits: 0,
        paygUsedCentsEstimate: 0,
        plan: 'pro',
        status: 'active',
        withinQuota: true,
      }
      server.use(
        http.get(`${API_BASE}/v1/admin/organizations/1/quota-usage`, () =>
          HttpResponse.json(quotaUsage)
        )
      )

      const result = await api.getAdminOrgQuotaUsage(1)
      expect(result).toEqual(quotaUsage)
    })
  })

  describe('resetAdminOrgQuotaUsage', () => {
    it('posts quota reset payload', async () => {
      const response = {
        organizationId: 1,
        quotaType: 'apm_spans',
        periodStart: '2026-01-01',
        periodEnd: '2026-01-31',
        previousUsed: 100,
        updatedUsed: 800,
        limit: 1000,
        targetPercent: 80,
        usage: {
          organizationId: 1,
          periodStart: '2026-01-01',
          periodEnd: '2026-01-31',
          retentionDays: 30,
          usedUnits: 0,
          usedErrors: 0,
          errorLimit: 100,
          usedTransactions: 0,
          transactionLimit: 100,
          usedReplays: 0,
          replayLimit: 100,
          usedFeedback: 0,
          feedbackLimit: 100,
          usedBytes: 0,
          bytesLimit: 10737418240,
          baseLimitUnits: 400,
          paygLimitUnits: 0,
          totalLimitUnits: 400,
          paygBudgetCents: 0,
          paygUsedUnits: 0,
          paygUsedCentsEstimate: 0,
          plan: 'pro',
          status: 'active',
          withinQuota: true,
        },
      }
      server.use(
        http.post(`${API_BASE}/v1/admin/organizations/1/quota-usage/reset`, async ({ request }) => {
          await expect(request.json()).resolves.toEqual({
            quotaType: 'apm_spans',
            targetPercent: 80,
          })
          return HttpResponse.json(response)
        })
      )

      const result = await api.resetAdminOrgQuotaUsage(1, {
        quotaType: 'apm_spans',
        targetPercent: 80,
      })
      expect(result).toEqual(response)
    })
  })

  // ──── Usage & Revenue ────

  describe('getAdminUsage', () => {
    it('fetches usage with default period', async () => {
      const data = {
        daily: [{ date: '2026-01-01', error: 10, transaction: 20, replay: 0, feedback: 1, log: 5, total: 36 }],
        totalBytes: 999999,
      }
      server.use(
        http.get(`${API_BASE}/v1/admin/usage`, ({ request }) => {
          const url = new URL(request.url)
          expect(url.searchParams.get('period')).toBe('7d')
          return HttpResponse.json(data)
        })
      )

      const result = await api.getAdminUsage()
      expect(result).toEqual(data)
    })

    it('passes custom period', async () => {
      server.use(
        http.get(`${API_BASE}/v1/admin/usage`, ({ request }) => {
          const url = new URL(request.url)
          expect(url.searchParams.get('period')).toBe('90d')
          return HttpResponse.json({ daily: [], totalBytes: 0 })
        })
      )

      await api.getAdminUsage('90d')
    })
  })

  describe('getAdminRevenue', () => {
    it('fetches revenue data', async () => {
      const data = {
        mrr: 1200,
        subscriptionsByPlan: { free: 5, pro: 3 },
        estimatedCostPerOrg: { 'org-a': 50 },
        churnLast30Days: 1,
      }
      server.use(
        http.get(`${API_BASE}/v1/admin/revenue`, () => HttpResponse.json(data))
      )

      const result = await api.getAdminRevenue()
      expect(result).toEqual(data)
    })
  })

  // ──── Infrastructure ────

  describe('getAdminInfrastructure', () => {
    it('fetches infrastructure stats', async () => {
      const data = {
        clickhouseTables: [
          { table: 'events', rows: 50000, bytesOnDisk: 1000000, bytesOnDiskFormatted: '1 MB' },
        ],
        totalDiskBytes: 1000000,
        totalRows: 50000,
        storageUsedPercent: 12.5,
        scalingTriggerAlerts: [],
      }
      server.use(
        http.get(`${API_BASE}/v1/admin/infrastructure`, () =>
          HttpResponse.json(data)
        )
      )

      const result = await api.getAdminInfrastructure()
      expect(result).toEqual(data)
    })
  })

  // ──── Top Consumers ────

  describe('getAdminTopConsumers', () => {
    it('fetches top consumers with default limit', async () => {
      const consumers = [
        { orgId: 1, orgName: 'BigCo', orgSlug: 'bigco', plan: 'pro', eventCount: 9999, bytesIngested: 500000 },
      ]
      server.use(
        http.get(`${API_BASE}/v1/admin/top-consumers`, ({ request }) => {
          const url = new URL(request.url)
          expect(url.searchParams.get('limit')).toBe('10')
          return HttpResponse.json(consumers)
        })
      )

      const result = await api.getAdminTopConsumers()
      expect(result).toEqual(consumers)
    })

    it('passes custom limit', async () => {
      server.use(
        http.get(`${API_BASE}/v1/admin/top-consumers`, ({ request }) => {
          const url = new URL(request.url)
          expect(url.searchParams.get('limit')).toBe('5')
          return HttpResponse.json([])
        })
      )

      await api.getAdminTopConsumers(5)
    })
  })

  // ──── Email Stats ────

  describe('getAdminEmailStats', () => {
    it('fetches email stats with default period', async () => {
      const data = {
        totalSent: 150,
        byType: { welcome: 50, alert: 100 },
        last7Days: [{ date: '2026-01-01', count: 10 }],
        last30Days: [{ date: '2026-01-01', count: 50 }],
        estimatedCost: 1.5,
      }
      server.use(
        http.get(`${API_BASE}/v1/admin/emails`, ({ request }) => {
          const url = new URL(request.url)
          expect(url.searchParams.get('period')).toBe('30d')
          return HttpResponse.json(data)
        })
      )

      const result = await api.getAdminEmailStats()
      expect(result).toEqual(data)
    })

    it('passes custom period', async () => {
      server.use(
        http.get(`${API_BASE}/v1/admin/emails`, ({ request }) => {
          const url = new URL(request.url)
          expect(url.searchParams.get('period')).toBe('7d')
          return HttpResponse.json({
            totalSent: 0,
            byType: {},
            last7Days: [],
            last30Days: [],
            estimatedCost: 0,
          })
        })
      )

      await api.getAdminEmailStats('7d')
    })
  })

  // ──── Users ────

  describe('getAdminUsers', () => {
    it('fetches users with default pagination', async () => {
      const data = {
        users: [
          {
            id: 1,
            email: 'user@test.com',
            name: 'Test',
            emailVerified: true,
            isAdmin: false,
            onboardingCompleted: true,
            oauthProvider: null,
            organizationCount: 2,
            createdAt: '2026-01-01T00:00:00Z',
          },
        ],
        total: 1,
        page: 1,
        limit: 25,
      }
      server.use(
        http.get(`${API_BASE}/v1/admin/users`, ({ request }) => {
          const url = new URL(request.url)
          expect(url.searchParams.get('page')).toBe('1')
          expect(url.searchParams.get('limit')).toBe('25')
          expect(url.searchParams.has('search')).toBe(false)
          return HttpResponse.json(data)
        })
      )

      const result = await api.getAdminUsers()
      expect(result).toEqual(data)
    })

    it('passes search parameter', async () => {
      server.use(
        http.get(`${API_BASE}/v1/admin/users`, ({ request }) => {
          const url = new URL(request.url)
          expect(url.searchParams.get('search')).toBe('alice')
          return HttpResponse.json({ users: [], total: 0, page: 1, limit: 25 })
        })
      )

      await api.getAdminUsers(1, 25, 'alice')
    })
  })

  describe('updateAdminUser', () => {
    it('sends PATCH with user updates', async () => {
      server.use(
        http.patch(`${API_BASE}/v1/admin/users/7`, async ({ request }) => {
          const body = await request.json()
          expect(body).toEqual({ isAdmin: true })
          return HttpResponse.json({ success: true })
        })
      )

      const result = await api.updateAdminUser(7, { isAdmin: true })
      expect(result).toEqual({ success: true })
    })
  })

  describe('deleteAdminUsers', () => {
    it('sends DELETE with user ids', async () => {
      server.use(
        http.delete(`${API_BASE}/v1/admin/users`, async ({ request }) => {
          const body = await request.json()
          expect(body).toEqual({ userIds: [1, 2, 3] })
          return HttpResponse.json({ success: true, deletedCount: 3, errors: [] })
        })
      )

      const result = await api.deleteAdminUsers([1, 2, 3])
      expect(result).toEqual({ success: true, deletedCount: 3, errors: [] })
    })
  })

  // ──── Notifications & Incidents ────

  describe('testNotification', () => {
    it('sends test notification request', async () => {
      server.use(
        http.post(`${API_BASE}/v1/admin/test-notification`, async ({ request }) => {
          const body = await request.json()
          expect(body).toEqual({ type: 'alert', channel: 'slack', testEmail: undefined })
          return HttpResponse.json({ success: true, emailSent: false, slackSent: true })
        })
      )

      const result = await api.testNotification('alert', 'slack')
      expect(result).toEqual({ success: true, emailSent: false, slackSent: true })
    })

    it('includes optional testEmail', async () => {
      server.use(
        http.post(`${API_BASE}/v1/admin/test-notification`, async ({ request }) => {
          const body = await request.json()
          expect(body).toEqual({ type: 'alert', channel: 'email', testEmail: 'a@b.com' })
          return HttpResponse.json({ success: true, emailSent: true, slackSent: false })
        })
      )

      await api.testNotification('alert', 'email', 'a@b.com')
    })
  })

  describe('triggerIncident', () => {
    it('sends incident trigger request', async () => {
      const incident = {
        source: 'manual',
        severity: 'critical',
        title: 'DB Down',
        description: 'Primary database is unreachable',
      }
      server.use(
        http.post(`${API_BASE}/v1/admin/incidents/trigger`, async ({ request }) => {
          const body = await request.json()
          expect(body).toEqual(incident)
          return HttpResponse.json({ success: true })
        })
      )

      const result = await api.triggerIncident(incident)
      expect(result).toEqual({ success: true })
    })
  })

  // ──── Attribution ────

  describe('getAdminAttribution', () => {
    it('fetches attribution with default groupBy', async () => {
      server.use(
        http.get(`${API_BASE}/v1/admin/attribution`, ({ request }) => {
          const url = new URL(request.url)
          expect(url.searchParams.get('groupBy')).toBe('campaign')
          return HttpResponse.json({ rows: [] })
        })
      )

      const result = await api.getAdminAttribution()
      expect(result).toEqual({ rows: [] })
    })

    it('passes custom groupBy', async () => {
      server.use(
        http.get(`${API_BASE}/v1/admin/attribution`, ({ request }) => {
          const url = new URL(request.url)
          expect(url.searchParams.get('groupBy')).toBe('source')
          return HttpResponse.json({ rows: [] })
        })
      )

      await api.getAdminAttribution('source')
    })
  })

  // ──── Billing Tiers ────

  describe('getAdminBillingTiers', () => {
    it('fetches all billing tiers when no tier specified', async () => {
      const tiers = [{ name: 'free', version: 1 }]
      server.use(
        http.get(`${API_BASE}/v1/admin/billing/tiers`, ({ request }) => {
          const url = new URL(request.url)
          expect(url.searchParams.has('tier')).toBe(false)
          return HttpResponse.json(tiers)
        })
      )

      const result = await api.getAdminBillingTiers()
      expect(result).toEqual(tiers)
    })

    it('passes tier query param', async () => {
      server.use(
        http.get(`${API_BASE}/v1/admin/billing/tiers`, ({ request }) => {
          const url = new URL(request.url)
          expect(url.searchParams.get('tier')).toBe('pro')
          return HttpResponse.json([])
        })
      )

      await api.getAdminBillingTiers('pro')
    })
  })

  describe('createAdminBillingTierVersion', () => {
    it('creates a new tier version', async () => {
      const body = { eventQuota: 100000, priceMonthly: 29 }
      const response = { name: 'pro', version: 2, eventQuota: 100000, priceMonthly: 29 }
      server.use(
        http.post(
          `${API_BASE}/v1/admin/billing/tiers/pro/versions`,
          async ({ request }) => {
            const reqBody = await request.json()
            expect(reqBody).toEqual(body)
            return HttpResponse.json(response)
          }
        )
      )

      const result = await api.createAdminBillingTierVersion('pro', body as never)
      expect(result).toEqual(response)
    })
  })

  describe('migrateAdminBillingTier', () => {
    it('sends migration request with default dryRun', async () => {
      server.use(
        http.post(
          `${API_BASE}/v1/admin/billing/tiers/pro/migrate`,
          async ({ request }) => {
            const body = await request.json()
            expect(body).toEqual({ targetVersion: 3, dryRun: true })
            return HttpResponse.json({ migrated: 0, dryRun: true })
          }
        )
      )

      const result = await api.migrateAdminBillingTier('pro', 3)
      expect(result).toEqual({ migrated: 0, dryRun: true })
    })

    it('sends migration with dryRun false', async () => {
      server.use(
        http.post(
          `${API_BASE}/v1/admin/billing/tiers/pro/migrate`,
          async ({ request }) => {
            const body = await request.json()
            expect(body).toEqual({ targetVersion: 2, dryRun: false })
            return HttpResponse.json({ migrated: 5, dryRun: false })
          }
        )
      )

      await api.migrateAdminBillingTier('pro', 2, false)
    })
  })

  describe('updateAdminBillingTierPriceIds', () => {
    it('sends PATCH with Stripe price IDs', async () => {
      const body = { monthlyPriceId: 'price_abc', yearlyPriceId: 'price_xyz' }
      server.use(
        http.patch(
          `${API_BASE}/v1/admin/billing/tiers/pro/versions/2`,
          async ({ request }) => {
            const reqBody = await request.json()
            expect(reqBody).toEqual(body)
            return HttpResponse.json({ name: 'pro', version: 2 })
          }
        )
      )

      const result = await api.updateAdminBillingTierPriceIds('pro', 2, body as never)
      expect(result).toEqual({ name: 'pro', version: 2 })
    })
  })

  // ──── Billing Subscriptions ────

  describe('getAdminBillingSubscriptions', () => {
    it('fetches subscriptions with default limit', async () => {
      const subs = [{ id: 'sub_1', status: 'active' }]
      server.use(
        http.get(`${API_BASE}/v1/admin/billing/subscriptions`, ({ request }) => {
          const url = new URL(request.url)
          expect(url.searchParams.get('limit')).toBe('500')
          return HttpResponse.json(subs)
        })
      )

      const result = await api.getAdminBillingSubscriptions()
      expect(result).toEqual(subs)
    })

    it('passes custom limit', async () => {
      server.use(
        http.get(`${API_BASE}/v1/admin/billing/subscriptions`, ({ request }) => {
          const url = new URL(request.url)
          expect(url.searchParams.get('limit')).toBe('50')
          return HttpResponse.json([])
        })
      )

      await api.getAdminBillingSubscriptions(50)
    })
  })

  // ──── Telemetry ────

  describe('getAdminTelemetry', () => {
    it('fetches telemetry data', async () => {
      const data = {
        deploymentCount: 1,
        lastSeenAt: '2026-01-01T00:00:00Z',
        deployments: [
          {
            deploymentId: 'dep-1',
            receivedAt: '2026-01-01T00:00:00Z',
            cpuCount: 4,
            memTotalBytes: 8000000000,
            memUsedBytes: 4000000000,
            osName: 'Linux',
            osArch: 'amd64',
            jvmVersion: '21',
            projectCount: 3,
            userCount: 10,
            eventCount: 5000,
            issueCount: 200,
            sslEnabled: true,
          },
        ],
      }
      server.use(
        http.get(`${API_BASE}/v1/admin/telemetry`, () => HttpResponse.json(data))
      )

      const result = await api.getAdminTelemetry()
      expect(result).toEqual(data)
    })
  })

  // ──── SMS / Call ────

  describe('testSmsCall', () => {
    it('sends test SMS request', async () => {
      server.use(
        http.post(`${API_BASE}/v1/admin/test-sms-call`, async ({ request }) => {
          const body = await request.json()
          expect(body).toEqual({ channel: 'sms' })
          return HttpResponse.json({ success: true })
        })
      )

      const result = await api.testSmsCall('sms')
      expect(result).toEqual({ success: true })
    })

    it('sends test call request', async () => {
      server.use(
        http.post(`${API_BASE}/v1/admin/test-sms-call`, async ({ request }) => {
          const body = await request.json()
          expect(body).toEqual({ channel: 'call' })
          return HttpResponse.json({ success: true })
        })
      )

      const result = await api.testSmsCall('call')
      expect(result).toEqual({ success: true })
    })
  })
})
