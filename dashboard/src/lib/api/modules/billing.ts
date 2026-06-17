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
  ApmSpanUsageDebugResponse,
  BillingPlansResponse,
  BillingUsageInsightsResponse,
  BillingUsage,
  CheckoutSessionRequest,
  CheckoutSessionResponse,
  Invoice,
  PaymentMethod,
  SetupIntentResponse,
  CancelSubscriptionResponse,
  UpdateOnCallSeatsResponse,
} from '../types'
import { urlWithQuery } from '../utils'

export function billingMethods(core: ApiClientCore) {
  const base = core.API_BASE

  return {
    getBillingPlans: () =>
      core.request<BillingPlansResponse>(`${base}/billing/plans`),

    getBillingUsage: () =>
      core.request<BillingUsage>(`${base}/billing/usage`),

    getBillingUsageInsights: () =>
      core.request<BillingUsageInsightsResponse>(`${base}/billing/usage/insights`),

    getBillingApmSpanUsageDebug: (limit = 20) => {
      const numericLimit = Number(limit)
      const safeLimit = Number.isFinite(numericLimit) ? Math.max(1, Math.floor(numericLimit)) : 20
      const params = new URLSearchParams({ limit: String(safeLimit) })
      return core.request<ApmSpanUsageDebugResponse>(
        urlWithQuery(`${base}/billing/usage/apm-spans`, params.toString())
      )
    },

    createBillingCheckoutSession: (body: CheckoutSessionRequest) =>
      core.request<CheckoutSessionResponse>(`${base}/billing/checkout`, {
        method: 'POST',
        body: JSON.stringify(body),
      }),

    getBillingInvoices: () =>
      core.request<Invoice[]>(`${base}/billing/invoices`),

    getBillingPaymentMethod: () =>
      core.request<PaymentMethod>(`${base}/billing/payment-method`),

    createBillingSetupIntent: () =>
      core.request<SetupIntentResponse>(`${base}/billing/setup-intent`, {
        method: 'POST',
      }),

    confirmBillingSetupIntent: (setupIntentId: string) =>
      core.request<{ success: boolean }>(`${base}/billing/setup-intent/confirm`, {
        method: 'POST',
        headers: { 'Content-Type': 'application/json' },
        body: JSON.stringify({ setupIntentId }),
      }),

    cancelBillingSubscription: () =>
      core.request<CancelSubscriptionResponse>(`${base}/billing/cancel`, {
        method: 'POST',
      }),

    updatePaygBudget: (paygBudgetCents: number) =>
      core.request<{ paygBudgetCents: number }>(`${base}/billing/payg-budget`, {
        method: 'PUT',
        body: JSON.stringify({ paygBudgetCents }),
      }),

    updateOnCallSeats: (seats: number) =>
      core.request<UpdateOnCallSeatsResponse>(`${base}/billing/oncall-seats`, {
        method: 'PUT',
        body: JSON.stringify({ seats }),
      }),
  }
}
