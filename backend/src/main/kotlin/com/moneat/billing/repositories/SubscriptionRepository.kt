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

package com.moneat.billing.repositories

import com.moneat.billing.repositories.models.StripeSubscriptionData
import com.moneat.billing.repositories.models.SubscriptionRow

/**
 * Repository for subscription data access.
 */
interface SubscriptionRepository {
    /** Find active or trialing subscription (used for quota/tier checks). */
    fun findActiveByOrganizationId(orgId: Int): SubscriptionRow?

    /** Find active, trialing, or past_due subscription for an org (ordered by id DESC). */
    fun findCurrentByOrganizationId(orgId: Int): SubscriptionRow?

    /** Find subscription by stripe customer ID. */
    fun findByStripeCustomerId(customerId: String): SubscriptionRow?

    /** Find subscription by org ID and stripe subscription ID. */
    fun findByOrganizationAndStripeSubscriptionId(orgId: Int, stripeSubId: String): SubscriptionRow?

    /** Return the stripe_customer_id for any subscription with one for the org (ordered by id DESC). */
    fun findStripeCustomerIdByOrganizationId(orgId: Int): String?

    /** Update status and current_period_end for a subscription. */
    fun updateStatusAndPeriodEnd(id: Int, status: String, periodEnd: kotlin.time.Instant?)

    /** Set stripe_customer_id for a subscription. */
    fun updateStripeCustomerId(id: Int, customerId: String)

    /** Set status=active and stripe_customer_id by org + stripe subscription ID (checkout complete). */
    fun activateByOrgAndStripeId(orgId: Int, stripeSubId: String, customerId: String)

    /** Update subscription after a Stripe invoice is paid. */
    fun updateAfterInvoicePaid(
        orgId: Int,
        periodStart: kotlin.time.Instant?,
        periodEnd: kotlin.time.Instant?,
        isCycleRollover: Boolean,
    )

    /** Set status=past_due with a billing grace period for all active/trialing/past_due subs of an org. */
    fun setPastDueByOrganizationId(orgId: Int, graceUntil: kotlin.time.Instant)

    /** Upsert subscription data synced from Stripe (update if exists, insert otherwise). */
    fun updateFromStripe(id: Int, data: StripeSubscriptionData)

    /** Insert a new subscription row synced from Stripe. */
    fun insertFromStripe(orgId: Int, stripeSubId: String, data: StripeSubscriptionData)

    /**
     * Reset promotional credits to zero for the current subscription of an org.
     * Returns true if a subscription was found and updated.
     */
    fun resetPromotionalCredits(orgId: Int, adminUserId: Int): Boolean
}
