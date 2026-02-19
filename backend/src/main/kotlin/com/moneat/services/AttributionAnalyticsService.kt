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
import kotlinx.serialization.Serializable
import org.jetbrains.exposed.v1.core.*
import org.jetbrains.exposed.v1.jdbc.*
import org.jetbrains.exposed.v1.jdbc.selectAll
import org.jetbrains.exposed.v1.jdbc.transactions.transaction
import java.math.BigDecimal
import java.math.RoundingMode

@Serializable
data class AttributionMetrics(
    val source: String?,
    val medium: String?,
    val campaign: String?,
    val signups: Int,
    val paidOrganizations: Int,
    val conversionRate: Double,
    val totalMrr: String, // Serialized as string to avoid BigDecimal serialization issues
    val averageMrr: String,
    val estimatedLtv: String
)

@Serializable
data class AttributionAnalyticsResponse(
    val metrics: List<AttributionMetrics>,
    val summary: AttributionSummary
)

@Serializable
data class AttributionSummary(
    val totalSignups: Int,
    val totalPaidOrganizations: Int,
    val overallConversionRate: Double,
    val totalMrr: String
)

class AttributionAnalyticsService {

    fun getAttributionMetrics(
        groupBy: String = "campaign" // "source", "medium", "campaign", "all"
    ): AttributionAnalyticsResponse {
        return transaction {
            // Get all organizations with their subscription data
            val query = Organizations
                .leftJoin(Subscriptions, { Organizations.id }, { Subscriptions.organization_id })
                .selectAll()

            val results = query.toList()

            // Group by UTM parameters based on groupBy parameter
            val grouped = results.groupBy { row ->
                when (groupBy) {
                    "source" -> Triple(row[Organizations.utm_source], null, null)
                    "medium" -> Triple(null, row[Organizations.utm_medium], null)
                    "campaign" -> Triple(
                        row[Organizations.utm_source],
                        row[Organizations.utm_medium],
                        row[Organizations.utm_campaign]
                    )
                    else -> Triple(
                        row[Organizations.utm_source],
                        row[Organizations.utm_medium],
                        row[Organizations.utm_campaign]
                    )
                }
            }

            val metrics = grouped.map { (key, rows) ->
                val (source, medium, campaign) = key
                val signups = rows.distinctBy { it[Organizations.id] }.size

                // Count unique organizations with active paid subscriptions
                val paidOrgs = rows.filter { row ->
                    row.getOrNull(Subscriptions.id) != null &&
                        row.getOrNull(Subscriptions.status) in listOf("active", "trialing") &&
                        row.getOrNull(Subscriptions.plan) != "FREE"
                }.distinctBy { it[Organizations.id] }.size

                // Calculate total MRR
                val totalMrr = rows
                    .filter { row ->
                        row.getOrNull(Subscriptions.id) != null &&
                            row.getOrNull(Subscriptions.status) in listOf("active", "trialing") &&
                            row.getOrNull(Subscriptions.plan) != "FREE"
                    }
                    .mapNotNull { row ->
                        val pricingTierId = row.getOrNull(Subscriptions.pricing_tier_config_id)
                        if (pricingTierId != null) {
                            // Get pricing from PricingTierConfigs
                            PricingTierConfigs.selectAll()
                                .where { PricingTierConfigs.id eq pricingTierId }
                                .firstOrNull()
                                ?.let { pricing ->
                                    val interval = row.getOrNull(Subscriptions.billing_interval) ?: "monthly"
                                    val basePriceCents = if (interval == "yearly") {
                                        pricing[PricingTierConfigs.yearly_price_cents]
                                    } else {
                                        pricing[PricingTierConfigs.monthly_price_cents]
                                    }

                                    // Convert to monthly MRR
                                    if (interval == "yearly") {
                                        BigDecimal(basePriceCents).divide(BigDecimal(12), 2, RoundingMode.HALF_UP)
                                    } else {
                                        BigDecimal(basePriceCents)
                                    }
                                }
                        } else {
                            null
                        }
                    }
                    .fold(BigDecimal.ZERO) { acc, value -> acc + value }
                    .divide(BigDecimal(100), 2, RoundingMode.HALF_UP) // Convert cents to dollars

                val conversionRate = if (signups > 0) {
                    (paidOrgs.toDouble() / signups.toDouble()) * 100
                } else {
                    0.0
                }

                val averageMrr = if (paidOrgs > 0) {
                    totalMrr.divide(BigDecimal(paidOrgs), 2, RoundingMode.HALF_UP)
                } else {
                    BigDecimal.ZERO
                }

                // Estimated LTV (assuming 12-month retention for simplicity)
                val estimatedLtv = totalMrr.multiply(BigDecimal(12))

                AttributionMetrics(
                    source = source,
                    medium = medium,
                    campaign = campaign,
                    signups = signups,
                    paidOrganizations = paidOrgs,
                    conversionRate = conversionRate,
                    totalMrr = totalMrr.toString(),
                    averageMrr = averageMrr.toString(),
                    estimatedLtv = estimatedLtv.toString()
                )
            }.sortedByDescending { it.signups }

            // Calculate summary
            val totalSignups = results.distinctBy { it[Organizations.id] }.size
            val totalPaid = results.filter { row ->
                row.getOrNull(Subscriptions.id) != null &&
                    row.getOrNull(Subscriptions.status) in listOf("active", "trialing") &&
                    row.getOrNull(Subscriptions.plan) != "FREE"
            }.distinctBy { it[Organizations.id] }.size

            val totalMrrValue = metrics.fold(BigDecimal.ZERO) { acc, m -> acc + BigDecimal(m.totalMrr) }

            val summary = AttributionSummary(
                totalSignups = totalSignups,
                totalPaidOrganizations = totalPaid,
                overallConversionRate = if (totalSignups > 0) (totalPaid.toDouble() / totalSignups.toDouble()) * 100 else 0.0,
                totalMrr = totalMrrValue.toString()
            )

            AttributionAnalyticsResponse(
                metrics = metrics,
                summary = summary
            )
        }
    }
}
