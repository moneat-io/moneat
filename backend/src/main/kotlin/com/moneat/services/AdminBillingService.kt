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

package com.moneat.services

import com.moneat.models.*
import kotlinx.datetime.Clock
import kotlinx.datetime.TimeZone
import kotlinx.datetime.toLocalDateTime
import mu.KotlinLogging
import org.jetbrains.exposed.sql.*
import org.jetbrains.exposed.sql.SqlExpressionBuilder.plus
import org.jetbrains.exposed.sql.transactions.transaction
import org.jetbrains.exposed.sql.selectAll
import org.jetbrains.exposed.sql.insert
import org.jetbrains.exposed.sql.and

private val logger = KotlinLogging.logger {}

class AdminBillingService {
    companion object {
        private const val BYTES_PER_GB = 1_073_741_824L
    }

    /**
     * Grant promotional credits (bonus GB or units) to an organization
     */
    fun grantPromotionalCredit(
        organizationId: Int,
        grantedByUserId: Int,
        bonusGb: Double? = null,
        bonusUnits: Long? = null,
        reason: String
    ): GrantPromotionalCreditResponse {
        require(bonusGb != null || bonusUnits != null) {
            "At least one of bonusGb or bonusUnits must be provided"
        }
        require(bonusGb == null || bonusGb > 0) {
            "bonusGb must be positive"
        }
        require(bonusUnits == null || bonusUnits > 0) {
            "bonusUnits must be positive"
        }

        val bonusGbBytes = bonusGb?.let { (it * BYTES_PER_GB).toLong() } ?: 0L
        val bonusUnitsValue = bonusUnits ?: 0L
        val now = Clock.System.now()

        return transaction {
            // Get active subscription for the organization
            val subscription = Subscriptions.selectAll().where {
                (Subscriptions.organization_id eq organizationId) and
                    (Subscriptions.status inList listOf("active", "trialing", "past_due"))
            }
                .orderBy(Subscriptions.id to SortOrder.DESC)
                .firstOrNull()
                ?: throw IllegalStateException("No active subscription found for organization $organizationId")

            val subscriptionId = subscription[Subscriptions.id]

            // Apply additive increment in SQL to avoid lost updates under concurrent grants.
            Subscriptions.update({ Subscriptions.id eq subscriptionId }) {
                it[bonus_gb_bytes] = Subscriptions.bonus_gb_bytes + bonusGbBytes
                it[bonus_units] = Subscriptions.bonus_units + bonusUnitsValue
                it[bonus_granted_at] = now
                it[bonus_granted_by] = grantedByUserId
                it[bonus_reason] = reason
            }

            val updatedSubscription = Subscriptions
                .select(Subscriptions.bonus_gb_bytes, Subscriptions.bonus_units)
                .where { Subscriptions.id eq subscriptionId }
                .first()
            val updatedBonusGbBytes = updatedSubscription[Subscriptions.bonus_gb_bytes]
            val updatedBonusUnits = updatedSubscription[Subscriptions.bonus_units]

            // Record grant in audit trail
            PromotionalCreditGrants.insert {
                it[PromotionalCreditGrants.organization_id] = organizationId
                it[PromotionalCreditGrants.subscription_id] = subscriptionId
                it[granted_by] = grantedByUserId
                it[PromotionalCreditGrants.bonus_gb_bytes] = bonusGbBytes
                it[PromotionalCreditGrants.bonus_units] = bonusUnitsValue
                it[PromotionalCreditGrants.reason] = reason
                it[granted_at] = now
            }

            logger.info {
                "Granted promotional credit to org $organizationId: " +
                    "${bonusGb ?: 0.0} GB (${bonusGbBytes} bytes), " +
                    "${bonusUnitsValue} units by user $grantedByUserId"
            }

            GrantPromotionalCreditResponse(
                organizationId = organizationId,
                bonusGbBytes = updatedBonusGbBytes,
                bonusUnits = updatedBonusUnits,
                bonusGb = updatedBonusGbBytes / BYTES_PER_GB.toDouble(),
                reason = reason,
                grantedAt = now.toLocalDateTime(TimeZone.UTC).toString()
            )
        }
    }

    /**
     * Get promotional credit grant history for an organization
     */
    fun getPromotionalCreditHistory(organizationId: Int): List<PromotionalCreditHistoryItem> {
        return transaction {
            (PromotionalCreditGrants innerJoin Organizations innerJoin Users)
                .select(
                    PromotionalCreditGrants.id,
                    PromotionalCreditGrants.organization_id,
                    Organizations.name,
                    PromotionalCreditGrants.granted_by,
                    Users.email,
                    PromotionalCreditGrants.bonus_gb_bytes,
                    PromotionalCreditGrants.bonus_units,
                    PromotionalCreditGrants.reason,
                    PromotionalCreditGrants.granted_at
                )
                .where {
                    (PromotionalCreditGrants.organization_id eq organizationId) and
                        (PromotionalCreditGrants.granted_by eq Users.id)
                }
                .orderBy(PromotionalCreditGrants.granted_at to SortOrder.DESC)
                .map { row ->
                    PromotionalCreditHistoryItem(
                        id = row[PromotionalCreditGrants.id],
                        organizationId = row[PromotionalCreditGrants.organization_id],
                        organizationName = row[Organizations.name],
                        grantedBy = row[PromotionalCreditGrants.granted_by],
                        grantedByEmail = row[Users.email],
                        bonusGb = row[PromotionalCreditGrants.bonus_gb_bytes] / BYTES_PER_GB.toDouble(),
                        bonusUnits = row[PromotionalCreditGrants.bonus_units],
                        reason = row[PromotionalCreditGrants.reason],
                        grantedAt = row[PromotionalCreditGrants.granted_at]
                            .toLocalDateTime(TimeZone.UTC)
                            .toString()
                    )
                }
        }
    }

    /**
     * Get all promotional credit grants across all organizations (admin view)
     */
    fun getAllPromotionalCreditGrants(limit: Int = 100): List<PromotionalCreditHistoryItem> {
        return transaction {
            (PromotionalCreditGrants innerJoin Organizations innerJoin Users)
                .select(
                    PromotionalCreditGrants.id,
                    PromotionalCreditGrants.organization_id,
                    Organizations.name,
                    PromotionalCreditGrants.granted_by,
                    Users.email,
                    PromotionalCreditGrants.bonus_gb_bytes,
                    PromotionalCreditGrants.bonus_units,
                    PromotionalCreditGrants.reason,
                    PromotionalCreditGrants.granted_at
                )
                .where { PromotionalCreditGrants.granted_by eq Users.id }
                .orderBy(PromotionalCreditGrants.granted_at to SortOrder.DESC)
                .limit(limit)
                .map { row ->
                    PromotionalCreditHistoryItem(
                        id = row[PromotionalCreditGrants.id],
                        organizationId = row[PromotionalCreditGrants.organization_id],
                        organizationName = row[Organizations.name],
                        grantedBy = row[PromotionalCreditGrants.granted_by],
                        grantedByEmail = row[Users.email],
                        bonusGb = row[PromotionalCreditGrants.bonus_gb_bytes] / BYTES_PER_GB.toDouble(),
                        bonusUnits = row[PromotionalCreditGrants.bonus_units],
                        reason = row[PromotionalCreditGrants.reason],
                        grantedAt = row[PromotionalCreditGrants.granted_at]
                            .toLocalDateTime(TimeZone.UTC)
                            .toString()
                    )
                }
        }
    }

    /**
     * Reset promotional credits for an organization (set to zero)
     */
    fun resetPromotionalCredits(organizationId: Int, adminUserId: Int): Boolean {
        return transaction {
            val subscription = Subscriptions.selectAll().where {
                (Subscriptions.organization_id eq organizationId) and
                    (Subscriptions.status inList listOf("active", "trialing", "past_due"))
            }
                .orderBy(Subscriptions.id to SortOrder.DESC)
                .firstOrNull()
                ?: return@transaction false

            val subscriptionId = subscription[Subscriptions.id]
            
            Subscriptions.update({ Subscriptions.id eq subscriptionId }) {
                it[bonus_gb_bytes] = 0L
                it[bonus_units] = 0L
                it[bonus_granted_at] = Clock.System.now()
                it[bonus_granted_by] = adminUserId
                it[bonus_reason] = "Reset by admin"
            }

            logger.info { "Reset promotional credits for org $organizationId by admin $adminUserId" }
            true
        }
    }
}
