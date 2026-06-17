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

import type {BillingUsage} from './billing'

export interface AdminAttributionMetrics {
  source: string | null
  medium: string | null
  campaign: string | null
  signups: number
  paidOrganizations: number
  conversionRate: number
  totalMrr: string
  averageMrr: string
  estimatedLtv: string
}

export interface AdminAttributionSummary {
  totalSignups: number
  totalPaidOrganizations: number
  overallConversionRate: number
  totalMrr: string
}

export interface AdminAttributionResponse {
  metrics: AdminAttributionMetrics[]
  summary: AdminAttributionSummary
}

export interface AdminBillingSubscription {
  subscriptionId: string
  organizationId: string
  organizationName: string
  plan: string
  status: string
  pricingTierConfigId?: string | null
  paygBudgetCents: number
  paygUsedUnits: number
  paygUsedMicros: number
  pendingMeterUnits: number
  currentPeriodStart?: string | null
  currentPeriodEnd?: string | null
}

export interface AdminQuotaUsageResetRequest {
  quotaType: string
  targetPercent?: number | null
  targetValue?: number | null
}

export interface AdminQuotaUsageResetResponse {
  organizationId: string
  quotaType: string
  periodStart: string
  periodEnd: string
  previousUsed: number
  updatedUsed: number
  limit: number | null
  targetPercent: number | null
  usage: BillingUsage
}
