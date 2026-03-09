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
import com.moneat.shared.models.Subscriptions
import org.jetbrains.exposed.v1.core.ResultRow
import org.jetbrains.exposed.v1.core.SortOrder
import org.jetbrains.exposed.v1.core.and
import org.jetbrains.exposed.v1.core.eq
import org.jetbrains.exposed.v1.core.inList
import org.jetbrains.exposed.v1.core.isNotNull
import org.jetbrains.exposed.v1.jdbc.insert
import org.jetbrains.exposed.v1.jdbc.selectAll
import org.jetbrains.exposed.v1.jdbc.transactions.transaction
import org.jetbrains.exposed.v1.jdbc.update
import kotlin.time.Clock

private val ACTIVE_STATUSES = listOf("active", "trialing")
private val CURRENT_STATUSES = listOf("active", "trialing", "past_due")

class SubscriptionRepositoryImpl : SubscriptionRepository {

    override fun findActiveByOrganizationId(orgId: Int): SubscriptionRow? =
        transaction {
            Subscriptions
                .selectAll()
                .where {
                    (Subscriptions.organization_id eq orgId) and
                        (Subscriptions.status inList ACTIVE_STATUSES)
                }
                .orderBy(Subscriptions.id to SortOrder.ASC)
                .firstOrNull()
                ?.toRow()
        }

    override fun findCurrentByOrganizationId(orgId: Int): SubscriptionRow? =
        transaction {
            Subscriptions
                .selectAll()
                .where {
                    (Subscriptions.organization_id eq orgId) and
                        (Subscriptions.status inList CURRENT_STATUSES)
                }
                .orderBy(Subscriptions.id to SortOrder.DESC)
                .firstOrNull()
                ?.toRow()
        }

    override fun findByStripeCustomerId(customerId: String): SubscriptionRow? =
        transaction {
            Subscriptions
                .selectAll()
                .where { Subscriptions.stripe_customer_id eq customerId }
                .firstOrNull()
                ?.toRow()
        }

    override fun findByOrganizationAndStripeSubscriptionId(orgId: Int, stripeSubId: String): SubscriptionRow? =
        transaction {
            Subscriptions
                .selectAll()
                .where {
                    (Subscriptions.organization_id eq orgId) and
                        (Subscriptions.stripe_subscription_id eq stripeSubId)
                }
                .orderBy(Subscriptions.id to SortOrder.DESC)
                .firstOrNull()
                ?.toRow()
        }

    override fun findStripeCustomerIdByOrganizationId(orgId: Int): String? =
        transaction {
            Subscriptions
                .selectAll()
                .where {
                    (Subscriptions.organization_id eq orgId) and
                        (Subscriptions.stripe_customer_id.isNotNull())
                }
                .orderBy(Subscriptions.id to SortOrder.DESC)
                .firstOrNull()
                ?.get(Subscriptions.stripe_customer_id)
        }

    override fun updateStatusAndPeriodEnd(id: Int, status: String, periodEnd: kotlin.time.Instant?) {
        transaction {
            Subscriptions.update({ Subscriptions.id eq id }) {
                it[Subscriptions.status] = status
                it[current_period_end] = periodEnd
            }
        }
    }

    override fun updateStripeCustomerId(id: Int, customerId: String) {
        transaction {
            Subscriptions.update({ Subscriptions.id eq id }) {
                it[stripe_customer_id] = customerId
            }
        }
    }

    override fun activateByOrgAndStripeId(orgId: Int, stripeSubId: String, customerId: String) {
        transaction {
            Subscriptions.update({
                (Subscriptions.organization_id eq orgId) and
                    (Subscriptions.stripe_subscription_id eq stripeSubId)
            }) {
                it[status] = "active"
                it[stripe_customer_id] = customerId
            }
        }
    }

    override fun updateAfterInvoicePaid(
        orgId: Int,
        periodStart: kotlin.time.Instant?,
        periodEnd: kotlin.time.Instant?,
        isCycleRollover: Boolean,
    ) {
        transaction {
            val row =
                Subscriptions
                    .selectAll()
                    .where {
                        (Subscriptions.organization_id eq orgId) and
                            (Subscriptions.status inList CURRENT_STATUSES)
                    }
                    .orderBy(Subscriptions.id to SortOrder.DESC)
                    .firstOrNull() ?: return@transaction

            Subscriptions.update({ Subscriptions.id eq row[Subscriptions.id] }) {
                it[status] = "active"
                it[current_period_start] = periodStart
                it[current_period_end] = periodEnd
                if (isCycleRollover) {
                    it[payg_used_units] = 0
                    it[payg_used_micros] = 0
                    it[pending_meter_units] = 0
                    it[pending_meter_batch_id] = null
                    it[pending_meter_batch_units] = 0
                    it[pending_apm_span_overage_units] = 0
                    it[pending_apm_span_batch_id] = null
                    it[pending_apm_span_batch_units] = 0
                    it[pending_custom_metric_overage_units] = 0
                    it[pending_custom_metric_batch_id] = null
                    it[pending_custom_metric_batch_units] = 0
                }
                it[billing_grace_until] = null
            }
        }
    }

    override fun setPastDueByOrganizationId(orgId: Int, graceUntil: kotlin.time.Instant) {
        transaction {
            Subscriptions.update({
                (Subscriptions.organization_id eq orgId) and
                    (Subscriptions.status inList CURRENT_STATUSES)
            }) {
                it[status] = "past_due"
                it[billing_grace_until] = graceUntil
            }
        }
    }

    override fun updateFromStripe(id: Int, data: StripeSubscriptionData) {
        transaction {
            Subscriptions.update({ Subscriptions.id eq id }) {
                it[plan] = data.plan
                it[status] = data.status
                it[current_period_start] = data.periodStart
                it[current_period_end] = data.periodEnd
                it[stripe_customer_id] = data.stripeCustomerId
                it[pricing_tier_config_id] = data.pricingTierConfigId
                it[stripe_base_item_id] = data.stripeBaseItemId
                it[stripe_overage_item_id] = data.stripeOverageItemId
                it[stripe_oncall_item_id] = data.stripeOncallItemId
                it[oncall_seats] = data.oncallSeats
                it[billing_interval] = data.billingInterval
            }
        }
    }

    override fun insertFromStripe(orgId: Int, stripeSubId: String, data: StripeSubscriptionData) {
        transaction {
            Subscriptions.insert {
                it[organization_id] = orgId
                it[stripe_subscription_id] = stripeSubId
                it[stripe_customer_id] = data.stripeCustomerId
                it[plan] = data.plan
                it[status] = data.status
                it[current_period_start] = data.periodStart
                it[current_period_end] = data.periodEnd
                it[pricing_tier_config_id] = data.pricingTierConfigId
                it[payg_budget_cents] = 0
                it[payg_used_units] = 0
                it[payg_used_micros] = 0
                it[pending_meter_units] = 0
                it[pending_meter_batch_id] = null
                it[pending_meter_batch_units] = 0
                it[pending_apm_span_overage_units] = 0
                it[pending_apm_span_batch_id] = null
                it[pending_apm_span_batch_units] = 0
                it[pending_custom_metric_overage_units] = 0
                it[pending_custom_metric_batch_id] = null
                it[pending_custom_metric_batch_units] = 0
                it[stripe_base_item_id] = data.stripeBaseItemId
                it[stripe_overage_item_id] = data.stripeOverageItemId
                it[stripe_oncall_item_id] = data.stripeOncallItemId
                it[oncall_seats] = data.oncallSeats
                it[billing_interval] = data.billingInterval
            }
        }
    }

    override fun resetPromotionalCredits(orgId: Int, adminUserId: Int): Boolean =
        transaction {
            val subscription =
                Subscriptions
                    .selectAll()
                    .where {
                        (Subscriptions.organization_id eq orgId) and
                            (Subscriptions.status inList CURRENT_STATUSES)
                    }
                    .orderBy(Subscriptions.id to SortOrder.DESC)
                    .firstOrNull() ?: return@transaction false

            Subscriptions.update({ Subscriptions.id eq subscription[Subscriptions.id] }) {
                it[bonus_gb_bytes] = 0L
                it[bonus_units] = 0L
                it[bonus_granted_at] = Clock.System.now()
                it[bonus_granted_by] = adminUserId
                it[bonus_reason] = "Reset by admin"
            }
            true
        }

    private fun ResultRow.toRow() =
        SubscriptionRow(
            id = this[Subscriptions.id],
            organizationId = this[Subscriptions.organization_id],
            plan = this[Subscriptions.plan],
            status = this[Subscriptions.status],
            pricingTierConfigId = this[Subscriptions.pricing_tier_config_id],
            stripeCustomerId = this[Subscriptions.stripe_customer_id],
            stripeSubscriptionId = this[Subscriptions.stripe_subscription_id],
            billingInterval = this[Subscriptions.billing_interval],
            stripeOncallItemId = this[Subscriptions.stripe_oncall_item_id],
        )
}
