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

import type { ApiClientCore } from '../client'
import type {
  AdminAttributionResponse,
  BillingPlan,
  BillingTierConfig,
  CreateTierVersionRequest,
  TierMigrationResponse,
  UpdateStripePriceIdsRequest,
  AdminBillingSubscription,
  AdminQuotaUsageResetRequest,
  AdminQuotaUsageResetResponse,
  BillingUsage,
} from '../types'

export type TriggerIncidentResponse = {
  success: boolean
  incidentTriggered: boolean
  routeState: string
  routeReason?: string | null
  matchedRouteId?: string | null
  matchedRouteRevision?: number | null
  groupId?: string | null
  incidentId?: string | null
  grouping: { state: string; reason?: string | null }
  paging: { state: string; reason?: string | null }
  incident: { state: string; reason?: string | null }
}

export function adminMethods(core: ApiClientCore) {
  const base = core.API_BASE

  return {
    getAdminOverview: () =>
      core.request<{
        totalOrganizations: number
        totalUsers: number
        totalEventsAllTime: number
        totalEventsLast30Days: number
        mrr: number
        subscriptionsByPlan: Record<string, number>
        eventsLast30Days: { date: string; count: number }[]
      }>(`${base}/admin/overview`),

    getAdminOrganizations: (page = 1, limit = 25) =>
      core.request<
        Array<{
          id: string
          name: string
          slug: string
          plan: string
          eventCountThisMonth: number
          bytesIngestedThisMonth: number
          projectCount: number
          memberCount: number
          quotaUsedPercent: number | null
        }>
      >(`${base}/admin/organizations?page=${page}&limit=${limit}`),

    getAdminOrgDetail: (orgId: string) =>
      core.request<{
        id: string
        name: string
        slug: string
        companySize: string | null
        plan: string
        subscriptionStatus: string | null
        memberCount: number
        projectCount: number
        eventCountThisMonth: number
        bytesIngestedThisMonth: number
        quotaUsedPercent: number | null
        members: Array<{ userId: string; email: string; name: string | null; role: string }>
        projects: Array<{ id: string; name: string; slug: string; platform: string | null }>
      }>(`${base}/admin/organizations/${encodeURIComponent(orgId)}`),

    getAdminOrgUsage: (orgId: string, period = '7d') =>
      core.request<
        Array<{
          date: string
          eventType: string
          eventCount: number
          bytesIngested: number
        }>
      >(`${base}/admin/organizations/${encodeURIComponent(orgId)}/usage?period=${period}`),

    getAdminOrgQuotaUsage: (orgId: string) =>
      core.request<BillingUsage>(`${base}/admin/organizations/${encodeURIComponent(orgId)}/quota-usage`),

    resetAdminOrgQuotaUsage: (
      orgId: string,
      body: AdminQuotaUsageResetRequest
    ) =>
      core.request<AdminQuotaUsageResetResponse>(
        `${base}/admin/organizations/${encodeURIComponent(orgId)}/quota-usage/reset`,
        {
          method: 'POST',
          body: JSON.stringify(body),
        }
      ),

    getAdminUsage: (period = '7d') =>
      core.request<{
        daily: Array<{
          date: string
          error: number
          transaction: number
          replay: number
          feedback: number
          log: number
          total: number
        }>
        totalBytes: number
      }>(`${base}/admin/usage?period=${period}`),

    getAdminRevenue: () =>
      core.request<{
        mrr: number
        subscriptionsByPlan: Record<string, number>
        estimatedCostPerOrg: Record<string, number>
        churnLast30Days: number
      }>(`${base}/admin/revenue`),

    getAdminInfrastructure: () =>
      core.request<{
        clickhouseTables: Array<{
          table: string
          rows: number
          bytesOnDisk: number
          bytesOnDiskFormatted: string
        }>
        totalDiskBytes: number
        totalRows: number
        storageUsedPercent: number
        scalingTriggerAlerts: string[]
      }>(`${base}/admin/infrastructure`),

    getAdminTopConsumers: (limit = 10) =>
      core.request<
        Array<{
          orgId: string
          orgName: string
          orgSlug: string
          plan: string
          eventCount: number
          bytesIngested: number
        }>
      >(`${base}/admin/top-consumers?limit=${limit}`),

    getAdminEmailStats: (period = '30d') =>
      core.request<{
        totalSent: number
        byType: Record<string, number>
        last7Days: Array<{ date: string; count: number }>
        last30Days: Array<{ date: string; count: number }>
        estimatedCost: number
      }>(`${base}/admin/emails?period=${period}`),

    getAdminUsers: (page = 1, limit = 25, search?: string) => {
      const params = new URLSearchParams({ page: String(page), limit: String(limit) })
      if (search) params.append('search', search)
      return core.request<{
        users: Array<{
          id: string
          email: string
          name: string | null
          emailVerified: boolean
          isAdmin: boolean
          onboardingCompleted: boolean
          oauthProvider: string | null
          organizationCount: number
          createdAt: string | null
        }>
        total: number
        page: number
        limit: number
      }>(`${base}/admin/users?${params}`)
    },

    updateAdminUser: (
      userId: string,
      updates: { isAdmin?: boolean; emailVerified?: boolean }
    ) =>
      core.request<{ success: boolean }>(`${base}/admin/users/${encodeURIComponent(userId)}`, {
        method: 'PATCH',
        body: JSON.stringify(updates),
      }),

    deleteAdminUsers: (userIds: string[]) =>
      core.request<{ success: boolean; deletedCount: number; errors: string[] }>(
        `${base}/admin/users`,
        {
          method: 'DELETE',
          body: JSON.stringify({ userIds }),
        }
      ),

    testNotification: (type: string, channel: string, testEmail?: string) =>
      core.request<{
        success: boolean
        emailSent: boolean
        slackSent: boolean
        discordSent?: boolean
        errors?: string[]
      }>(`${base}/admin/test-notification`, {
        method: 'POST',
        body: JSON.stringify({ type, channel, testEmail }),
      }),

    triggerIncident: (data: {
      source: string
      severity: string
      title: string
      description: string
    }) =>
      core.request<TriggerIncidentResponse>(`${base}/admin/incidents/trigger`, {
        method: 'POST',
        body: JSON.stringify(data),
      }),

    getAdminAttribution: (
      groupBy: 'campaign' | 'source' | 'medium' | 'all' = 'campaign'
    ) =>
      core.request<AdminAttributionResponse>(
        `${base}/admin/attribution?groupBy=${encodeURIComponent(groupBy)}`
      ),

    getAdminBillingTiers: (tier?: string) => {
      const query = tier ? `?tier=${encodeURIComponent(tier)}` : ''
      return core.request<BillingPlan[] | BillingTierConfig[]>(
        `${base}/admin/billing/tiers${query}`
      )
    },

    createAdminBillingTierVersion: (
      tierName: string,
      body: CreateTierVersionRequest
    ) =>
      core.request<BillingTierConfig>(
        `${base}/admin/billing/tiers/${encodeURIComponent(tierName)}/versions`,
        {
          method: 'POST',
          body: JSON.stringify(body),
        }
      ),

    migrateAdminBillingTier: (
      tierName: string,
      targetVersion: number,
      dryRun = true
    ) =>
      core.request<TierMigrationResponse>(
        `${base}/admin/billing/tiers/${encodeURIComponent(tierName)}/migrate`,
        {
          method: 'POST',
          body: JSON.stringify({ targetVersion, dryRun }),
        }
      ),

    updateAdminBillingTierPriceIds: (
      tierName: string,
      version: number,
      body: UpdateStripePriceIdsRequest
    ) =>
      core.request<BillingTierConfig>(
        `${base}/admin/billing/tiers/${encodeURIComponent(tierName)}/versions/${version}`,
        {
          method: 'PATCH',
          body: JSON.stringify(body),
        }
      ),

    getAdminBillingSubscriptions: (limit = 500) =>
      core.request<AdminBillingSubscription[]>(
        `${base}/admin/billing/subscriptions?limit=${limit}`
      ),

    getAdminTelemetry: () =>
      core.request<{
        deploymentCount: number
        lastSeenAt: string | null
        deployments: {
          deploymentId: string
          receivedAt: string
          version: string
          cpuCount: number
          memTotalBytes: number
          memUsedBytes: number
          osName: string
          osArch: string
          jvmVersion: string
          projectCount: number
          userCount: number
          eventCount: number
          issueCount: number
          sslEnabled: boolean
        }[]
      }>(`${base}/admin/telemetry`),

    testSmsCall: (channel: 'sms' | 'call') =>
      core.request<{ success: boolean }>(`${base}/admin/test-sms-call`, {
        method: 'POST',
        body: JSON.stringify({ channel }),
      }),
  }
}
