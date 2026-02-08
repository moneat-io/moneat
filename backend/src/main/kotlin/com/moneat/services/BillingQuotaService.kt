package com.moneat.services

import com.moneat.models.*
import io.ktor.server.config.*
import kotlinx.datetime.*
import mu.KotlinLogging
import org.jetbrains.exposed.sql.*
import org.jetbrains.exposed.sql.transactions.TransactionManager
import org.jetbrains.exposed.sql.transactions.transaction
import kotlin.math.max

private val logger = KotlinLogging.logger {}

data class QuotaReservationResult(
    val allowed: Boolean,
    val reason: String? = null,
    val usage: BillingUsageResponse
)

class BillingQuotaService(
    private val pricingTierService: PricingTierService = PricingTierService()
) {
    private val config = ApplicationConfig("application.conf")

    fun isEnforcementEnabled(): Boolean {
        return config.propertyOrNull("billing.enforcementEnabled")?.getString()?.toBooleanStrictOrNull() ?: false
    }

    fun getUsageForOrganization(organizationId: Int): BillingUsageResponse {
        return transaction {
            val state = loadQuotaState(organizationId, lockRows = false)
            toUsageResponse(state)
        }
    }

    fun reserveUnits(organizationId: Int, requestedUnits: Int): QuotaReservationResult {
        if (requestedUnits <= 0) {
            return QuotaReservationResult(
                allowed = true,
                usage = getUsageForOrganization(organizationId)
            )
        }

        if (!isEnforcementEnabled()) {
            return QuotaReservationResult(
                allowed = true,
                usage = getUsageForOrganization(organizationId)
            )
        }

        return transaction {
            val state = loadQuotaState(organizationId, lockRows = true)
            val totalAfter = state.usedUnits + requestedUnits

            if (totalAfter > state.totalLimitUnits) {
                return@transaction QuotaReservationResult(
                    allowed = false,
                    reason = "quota_exceeded",
                    usage = toUsageResponse(state)
                )
            }

            OrgUsageCounters.update({
                (OrgUsageCounters.organization_id eq organizationId) and
                    (OrgUsageCounters.period_start eq state.periodStart)
            }) {
                it[used_units] = totalAfter
                it[updated_at] = Clock.System.now()
            }

            val overageBefore = max(0, state.usedUnits - state.baseLimitUnits)
            val overageAfter = max(0, totalAfter - state.baseLimitUnits)
            val overageDelta = overageAfter - overageBefore

            if (state.subscriptionId != null && overageDelta > 0 && state.paygRateMicrosPerUnit > 0) {
                val overageMicros = overageDelta * state.paygRateMicrosPerUnit
                Subscriptions.update({ Subscriptions.id eq state.subscriptionId }) {
                    it[payg_used_units] = state.paygUsedUnits + overageDelta
                    it[payg_used_micros] = state.paygUsedMicros + overageMicros
                    it[pending_meter_units] = state.pendingMeterUnits + overageDelta
                }
            }

            val refreshed = loadQuotaState(organizationId, lockRows = false)
            QuotaReservationResult(
                allowed = true,
                usage = toUsageResponse(refreshed)
            )
        }
    }

    private data class QuotaState(
        val organizationId: Int,
        val plan: String,
        val status: String,
        val retentionDays: Int,
        val subscriptionId: Int?,
        val periodStart: LocalDate,
        val periodEnd: LocalDate,
        val usedUnits: Long,
        val baseLimitUnits: Long,
        val paygLimitUnits: Long,
        val totalLimitUnits: Long,
        val paygBudgetCents: Int,
        val paygUsedUnits: Long,
        val paygUsedMicros: Long,
        val pendingMeterUnits: Long,
        val paygRateMicrosPerUnit: Long
    )

    private fun loadQuotaState(organizationId: Int, lockRows: Boolean): QuotaState {
        val now = Clock.System.todayIn(TimeZone.UTC)
        val sub = Subscriptions.select {
            (Subscriptions.organization_id eq organizationId) and
                (Subscriptions.status inList listOf("active", "trialing", "past_due"))
        }
            .orderBy(Subscriptions.id to SortOrder.DESC)
            .firstOrNull()

        if (lockRows && sub != null) {
            val subId = sub[Subscriptions.id]
            TransactionManager.current().exec(
                "SELECT id FROM subscriptions WHERE id = ? FOR UPDATE",
                listOf(Subscriptions.id.columnType to subId)
            )
        }

        val tier = run {
            val byId = sub?.get(Subscriptions.pricing_tier_config_id)?.let { tierId ->
                PricingTierConfigs.select { PricingTierConfigs.id eq tierId }.firstOrNull()
            }

            val byPlan = if (byId == null && sub != null) {
                PricingTierConfigs.select {
                    (PricingTierConfigs.tier_name eq sub[Subscriptions.plan].uppercase()) and
                        (PricingTierConfigs.is_current eq true)
                }.firstOrNull()
            } else {
                null
            }

            val free = if (byId == null && byPlan == null) {
                PricingTierConfigs.select {
                    (PricingTierConfigs.tier_name eq "FREE") and
                        (PricingTierConfigs.is_current eq true)
                }.firstOrNull()
            } else {
                null
            }

            when {
                byId != null -> tierFromRow(byId)
                byPlan != null -> tierFromRow(byPlan)
                free != null -> tierFromRow(free)
                else -> tierFromEnum(sub?.get(Subscriptions.plan) ?: "FREE")
            }
        }
        val periodStart = sub?.get(Subscriptions.current_period_start)?.toLocalDateTime(TimeZone.UTC)?.date
            ?: LocalDate(now.year, now.month, 1)
        val periodEnd = sub?.get(Subscriptions.current_period_end)?.toLocalDateTime(TimeZone.UTC)?.date
            ?: periodStart.plus(DatePeriod(months = 1, days = -1))

        val existingCounter = OrgUsageCounters.select {
            (OrgUsageCounters.organization_id eq organizationId) and
                (OrgUsageCounters.period_start eq periodStart)
        }.firstOrNull()

        if (existingCounter == null) {
            OrgUsageCounters.insert {
                it[OrgUsageCounters.organization_id] = organizationId
                it[OrgUsageCounters.period_start] = periodStart
                it[OrgUsageCounters.period_end] = periodEnd
                it[used_units] = 0
                it[updated_at] = Clock.System.now()
            }
        }

        if (lockRows) {
            TransactionManager.current().exec(
                "SELECT id FROM org_usage_counters WHERE organization_id = ? AND period_start = ? FOR UPDATE",
                listOf(
                    OrgUsageCounters.organization_id.columnType to organizationId,
                    OrgUsageCounters.period_start.columnType to periodStart
                )
            )
        }

        val usageRow = OrgUsageCounters.select {
            (OrgUsageCounters.organization_id eq organizationId) and
                (OrgUsageCounters.period_start eq periodStart)
        }.first()

        val usedUnits = usageRow[OrgUsageCounters.used_units]
        val paygBudgetCents = sub?.get(Subscriptions.payg_budget_cents) ?: 0
        val paygRateMicros = tier.paygRateMicrosPerUnit
        val paygEnabled = tier.paygEnabled
        val paygLimitUnits = if (paygEnabled && paygBudgetCents > 0 && paygRateMicros > 0) {
            (paygBudgetCents.toLong() * 10_000L) / paygRateMicros
        } else {
            0
        }
        val baseLimit = tier.monthlyUnitLimit
        val totalLimit = baseLimit + paygLimitUnits

        return QuotaState(
            organizationId = organizationId,
            plan = tier.tierName.lowercase(),
            status = sub?.get(Subscriptions.status) ?: "active",
            retentionDays = tier.retentionDays,
            subscriptionId = sub?.get(Subscriptions.id),
            periodStart = periodStart,
            periodEnd = periodEnd,
            usedUnits = usedUnits,
            baseLimitUnits = baseLimit,
            paygLimitUnits = paygLimitUnits,
            totalLimitUnits = totalLimit,
            paygBudgetCents = paygBudgetCents,
            paygUsedUnits = sub?.get(Subscriptions.payg_used_units) ?: 0,
            paygUsedMicros = sub?.get(Subscriptions.payg_used_micros) ?: 0,
            pendingMeterUnits = sub?.get(Subscriptions.pending_meter_units) ?: 0,
            paygRateMicrosPerUnit = paygRateMicros
        )
    }

    private fun toUsageResponse(state: QuotaState): BillingUsageResponse {
        return BillingUsageResponse(
            organizationId = state.organizationId,
            periodStart = state.periodStart.toString(),
            periodEnd = state.periodEnd.toString(),
            retentionDays = state.retentionDays,
            usedUnits = state.usedUnits,
            baseLimitUnits = state.baseLimitUnits,
            paygLimitUnits = state.paygLimitUnits,
            totalLimitUnits = state.totalLimitUnits,
            paygBudgetCents = state.paygBudgetCents,
            paygUsedUnits = state.paygUsedUnits,
            paygUsedCentsEstimate = (state.paygUsedMicros / 10_000L).toInt(),
            plan = state.plan,
            status = state.status,
            withinQuota = state.usedUnits <= state.totalLimitUnits
        )
    }

    private fun tierFromRow(row: ResultRow): PricingTierConfigResponse {
        return PricingTierConfigResponse(
            id = row[PricingTierConfigs.id],
            tierName = row[PricingTierConfigs.tier_name],
            version = row[PricingTierConfigs.version],
            monthlyUnitLimit = row[PricingTierConfigs.monthly_unit_limit],
            retentionDays = row[PricingTierConfigs.retention_days],
            maxProjects = row[PricingTierConfigs.max_projects],
            maxSystems = row[PricingTierConfigs.max_systems],
            monitorIntervalSeconds = row[PricingTierConfigs.monitor_interval_seconds],
            monthlyPriceCents = row[PricingTierConfigs.monthly_price_cents],
            paygEnabled = row[PricingTierConfigs.payg_enabled],
            paygRateMicrosPerUnit = row[PricingTierConfigs.payg_rate_micros_per_unit],
            stripeBasePriceId = row[PricingTierConfigs.stripe_base_price_id],
            stripeOveragePriceId = row[PricingTierConfigs.stripe_overage_price_id],
            isCurrent = row[PricingTierConfigs.is_current]
        )
    }

    private fun tierFromEnum(tierName: String): PricingTierConfigResponse {
        val tier = PricingTier.entries.find { it.name.equals(tierName, ignoreCase = true) } ?: PricingTier.FREE
        return PricingTierConfigResponse(
            id = 0,
            tierName = tier.name,
            version = 1,
            monthlyUnitLimit = tier.monthlyErrorLimit,
            retentionDays = tier.retentionDays,
            maxProjects = tier.maxProjects,
            maxSystems = tier.maxSystems,
            monitorIntervalSeconds = tier.monitorIntervalSeconds,
            monthlyPriceCents = when (tier) {
                PricingTier.FREE -> 0
                PricingTier.PRO -> 1900
                PricingTier.TEAM -> 4900
            },
            paygEnabled = tier != PricingTier.FREE,
            paygRateMicrosPerUnit = if (tier == PricingTier.FREE) 0 else 10,
            stripeBasePriceId = null,
            stripeOveragePriceId = null,
            isCurrent = true
        )
    }
}
