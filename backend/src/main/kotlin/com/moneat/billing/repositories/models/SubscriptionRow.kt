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

package com.moneat.billing.repositories.models

/**
 * Domain model for a subscription row.
 */
data class SubscriptionRow(
    val id: Int,
    val organizationId: Int,
    val plan: String,
    val status: String,
    val pricingTierConfigId: Int?,
    val stripeCustomerId: String?,
    val stripeSubscriptionId: String?,
    val billingInterval: String,
    val stripeOncallItemId: String?,
)

/**
 * Data used when syncing a subscription from Stripe (upsert).
 */
data class StripeSubscriptionData(
    val plan: String,
    val status: String,
    val periodStart: kotlin.time.Instant?,
    val periodEnd: kotlin.time.Instant?,
    val stripeCustomerId: String,
    val pricingTierConfigId: Int?,
    val stripeBaseItemId: String?,
    val stripeOverageItemId: String?,
    val stripeOncallItemId: String?,
    val oncallSeats: Int,
    val billingInterval: String,
)
