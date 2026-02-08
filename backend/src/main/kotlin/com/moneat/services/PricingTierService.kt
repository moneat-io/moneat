package com.moneat.services

import com.moneat.models.*
import kotlinx.datetime.Instant
import kotlinx.datetime.TimeZone
import kotlinx.datetime.toLocalDateTime
import mu.KotlinLogging
import org.jetbrains.exposed.sql.*
import org.jetbrains.exposed.sql.transactions.transaction

private val logger = KotlinLogging.logger {}

data class EffectiveTierContext(
    val tier: PricingTierConfigResponse,
    val subscriptionId: Int?,
    val subscriptionStatus: String,
    val paygBudgetCents: Int,
    val paygUsedUnits: Long,
    val paygUsedMicros: Long,
    val pendingMeterUnits: Long,
    val currentPeriodStart: kotlinx.datetime.LocalDate?,
    val currentPeriodEnd: kotlinx.datetime.LocalDate?
)

class PricingTierService {
    fun getPrimaryOrganizationIdForUser(userId: Int): Int? {
        return transaction {
            Memberships.select { Memberships.user_id eq userId }
                .orderBy(Memberships.id to SortOrder.ASC)
                .firstOrNull()
                ?.get(Memberships.organization_id)
        }
    }

    fun getCurrentPlans(): List<BillingPlanResponse> {
        return transaction {
            PricingTierConfigs.select { PricingTierConfigs.is_current eq true }
                .orderBy(PricingTierConfigs.monthly_price_cents to SortOrder.ASC)
                .map { BillingPlanResponse(rowToResponse(it)) }
        }
    }

    fun getTierVersions(tierName: String): List<PricingTierConfigResponse> {
        return transaction {
            PricingTierConfigs.select { PricingTierConfigs.tier_name eq tierName.uppercase() }
                .orderBy(PricingTierConfigs.version to SortOrder.DESC)
                .map { rowToResponse(it) }
        }
    }

    fun getCurrentTier(tierName: String): PricingTierConfigResponse? {
        return transaction {
            PricingTierConfigs.select {
                (PricingTierConfigs.tier_name eq tierName.uppercase()) and
                    (PricingTierConfigs.is_current eq true)
            }
                .firstOrNull()
                ?.let { rowToResponse(it) }
        }
    }

    fun getTierById(id: Int): PricingTierConfigResponse? {
        return transaction {
            PricingTierConfigs.select { PricingTierConfigs.id eq id }
                .firstOrNull()
                ?.let { rowToResponse(it) }
        }
    }

    fun getEffectiveTierForOrganization(organizationId: Int): EffectiveTierContext {
        return transaction {
            val subscription = Subscriptions
                .select {
                    (Subscriptions.organization_id eq organizationId) and
                        (Subscriptions.status inList listOf("active", "trialing", "past_due"))
                }
                .orderBy(Subscriptions.id to SortOrder.DESC)
                .firstOrNull()

            if (subscription == null) {
                val free = currentFallbackTier("FREE")
                return@transaction EffectiveTierContext(
                    tier = free,
                    subscriptionId = null,
                    subscriptionStatus = "active",
                    paygBudgetCents = 0,
                    paygUsedUnits = 0,
                    paygUsedMicros = 0,
                    pendingMeterUnits = 0,
                    currentPeriodStart = null,
                    currentPeriodEnd = null
                )
            }

            val byId = subscription[Subscriptions.pricing_tier_config_id]?.let { id ->
                PricingTierConfigs.select { PricingTierConfigs.id eq id }.firstOrNull()
            }

            val tierRow = byId
                ?: PricingTierConfigs.select {
                    (PricingTierConfigs.is_current eq true) and
                        (PricingTierConfigs.tier_name eq subscription[Subscriptions.plan].uppercase())
                }.firstOrNull()
                ?: PricingTierConfigs.select {
                    (PricingTierConfigs.is_current eq true) and
                        (PricingTierConfigs.tier_name eq "FREE")
                }.firstOrNull()

            val tier = if (tierRow != null) rowToResponse(tierRow) else enumFallbackToResponse(subscription[Subscriptions.plan])

            EffectiveTierContext(
                tier = tier,
                subscriptionId = subscription[Subscriptions.id],
                subscriptionStatus = subscription[Subscriptions.status],
                paygBudgetCents = subscription[Subscriptions.payg_budget_cents],
                paygUsedUnits = subscription[Subscriptions.payg_used_units],
                paygUsedMicros = subscription[Subscriptions.payg_used_micros],
                pendingMeterUnits = subscription[Subscriptions.pending_meter_units],
                currentPeriodStart = subscription[Subscriptions.current_period_start]?.toLocalDateUtc(),
                currentPeriodEnd = subscription[Subscriptions.current_period_end]?.toLocalDateUtc()
            )
        }
    }

    fun createTierVersion(tierName: String, request: CreateTierVersionRequest): PricingTierConfigResponse {
        val canonicalName = tierName.uppercase()
        validateCreateTierRequest(request)
        val monthlyErrorLimit = if (request.monthlyErrorLimit > 0) request.monthlyErrorLimit else request.monthlyUnitLimit
        val monthlyTransactionLimit = request.monthlyTransactionLimit
        val monthlyReplayLimit = request.monthlyReplayLimit
        val monthlyFeedbackLimit = request.monthlyFeedbackLimit
        val monthlyUnitLimit = monthlyErrorLimit + monthlyTransactionLimit + monthlyReplayLimit + monthlyFeedbackLimit
        return transaction {
            val current = PricingTierConfigs.select { PricingTierConfigs.tier_name eq canonicalName }
                .orderBy(PricingTierConfigs.version to SortOrder.DESC)
                .firstOrNull()
            val nextVersion = (current?.get(PricingTierConfigs.version) ?: 0) + 1

            PricingTierConfigs.update({ PricingTierConfigs.tier_name eq canonicalName }) {
                it[is_current] = false
            }

            val id = PricingTierConfigs.insert {
                it[PricingTierConfigs.tier_name] = canonicalName
                it[version] = nextVersion
                it[monthly_unit_limit] = monthlyUnitLimit
                it[monthly_error_limit] = monthlyErrorLimit
                it[monthly_transaction_limit] = monthlyTransactionLimit
                it[monthly_replay_limit] = monthlyReplayLimit
                it[monthly_feedback_limit] = monthlyFeedbackLimit
                it[retention_days] = request.retentionDays
                it[max_projects] = request.maxProjects
                it[max_systems] = request.maxSystems
                it[monitor_interval_seconds] = request.monitorIntervalSeconds
                it[monthly_price_cents] = request.monthlyPriceCents
                it[payg_enabled] = request.paygEnabled
                it[payg_rate_micros_per_unit] = request.paygRateMicrosPerUnit
                it[stripe_base_price_id] = request.stripeBasePriceId
                it[stripe_overage_price_id] = request.stripeOveragePriceId
                it[is_current] = true
            } get PricingTierConfigs.id

            rowToResponse(PricingTierConfigs.select { PricingTierConfigs.id eq id }.first())
        }
    }

    private fun validateCreateTierRequest(request: CreateTierVersionRequest) {
        require(request.retentionDays in 1..90) { "Retention days must be between 1 and 90" }
        require(request.monthlyUnitLimit >= 0) { "Monthly unit limit must be non-negative" }
        require(request.monthlyErrorLimit >= 0) { "Monthly error limit must be non-negative" }
        require(request.monthlyTransactionLimit >= 0) { "Monthly transaction limit must be non-negative" }
        require(request.monthlyReplayLimit >= 0) { "Monthly replay limit must be non-negative" }
        require(request.monthlyFeedbackLimit >= 0) { "Monthly feedback limit must be non-negative" }
    }

    fun migrateSubscribers(tierName: String, request: TierMigrationRequest): TierMigrationResponse {
        val canonicalName = tierName.uppercase()
        return transaction {
            val targetTierId = PricingTierConfigs.select {
                (PricingTierConfigs.tier_name eq canonicalName) and
                    (PricingTierConfigs.version eq request.targetVersion)
            }.firstOrNull()?.get(PricingTierConfigs.id)
                ?: throw IllegalArgumentException("Target tier version not found")

            val candidateIds = Subscriptions.select {
                Subscriptions.status inList listOf("active", "trialing", "past_due")
            }
                .filter { row ->
                    val configId = row[Subscriptions.pricing_tier_config_id]
                    if (configId == null) {
                        row[Subscriptions.plan].equals(canonicalName, ignoreCase = true)
                    } else {
                        PricingTierConfigs.select { PricingTierConfigs.id eq configId }
                            .firstOrNull()
                            ?.get(PricingTierConfigs.tier_name)
                            ?.equals(canonicalName, ignoreCase = true) == true
                    }
                }
                .map { it[Subscriptions.id] }

            if (!request.dryRun && candidateIds.isNotEmpty()) {
                Subscriptions.update({ Subscriptions.id inList candidateIds }) {
                    it[pricing_tier_config_id] = targetTierId
                    it[plan] = canonicalName.lowercase()
                }
            }

            TierMigrationResponse(
                tierName = canonicalName,
                targetVersion = request.targetVersion,
                affectedSubscriptions = candidateIds.size,
                dryRun = request.dryRun
            )
        }
    }

    fun listAdminSubscriptions(limit: Int = 500): List<AdminBillingSubscriptionResponse> {
        return transaction {
            (Subscriptions innerJoin Organizations)
                .selectAll()
                .orderBy(Subscriptions.id to SortOrder.DESC)
                .limit(limit)
                .map { row ->
                    AdminBillingSubscriptionResponse(
                        subscriptionId = row[Subscriptions.id],
                        organizationId = row[Subscriptions.organization_id],
                        organizationName = row[Organizations.name],
                        plan = row[Subscriptions.plan],
                        status = row[Subscriptions.status],
                        pricingTierConfigId = row[Subscriptions.pricing_tier_config_id],
                        paygBudgetCents = row[Subscriptions.payg_budget_cents],
                        paygUsedUnits = row[Subscriptions.payg_used_units],
                        paygUsedMicros = row[Subscriptions.payg_used_micros],
                        pendingMeterUnits = row[Subscriptions.pending_meter_units],
                        currentPeriodStart = row[Subscriptions.current_period_start]?.toString(),
                        currentPeriodEnd = row[Subscriptions.current_period_end]?.toString()
                    )
                }
        }
    }

    private fun currentFallbackTier(tierName: String): PricingTierConfigResponse {
        val row = PricingTierConfigs.select {
            (PricingTierConfigs.tier_name eq tierName.uppercase()) and
                (PricingTierConfigs.is_current eq true)
        }.firstOrNull()
        if (row != null) return rowToResponse(row)
        return enumFallbackToResponse(tierName)
    }

    private fun enumFallbackToResponse(tierName: String): PricingTierConfigResponse {
        val tier = PricingTier.entries.find { it.name.equals(tierName, ignoreCase = true) } ?: PricingTier.FREE
        val monthlyPrice = when (tier) {
            PricingTier.FREE -> 0
            PricingTier.PRO -> 1900
            PricingTier.TEAM -> 4900
        }
        return PricingTierConfigResponse(
            id = 0,
            tierName = tier.name,
            version = 1,
            monthlyUnitLimit = tier.monthlyErrorLimit,
            monthlyErrorLimit = tier.monthlyErrorLimit,
            monthlyTransactionLimit = 0,
            monthlyReplayLimit = tier.monthlyReplayLimit,
            monthlyFeedbackLimit = 0,
            retentionDays = tier.retentionDays,
            maxProjects = tier.maxProjects,
            maxSystems = tier.maxSystems,
            monitorIntervalSeconds = tier.monitorIntervalSeconds,
            monthlyPriceCents = monthlyPrice,
            paygEnabled = tier != PricingTier.FREE,
            paygRateMicrosPerUnit = if (tier == PricingTier.FREE) 0 else 10,
            stripeBasePriceId = null,
            stripeOveragePriceId = null,
            isCurrent = true
        )
    }

    private fun rowToResponse(row: ResultRow): PricingTierConfigResponse {
        return PricingTierConfigResponse(
            id = row[PricingTierConfigs.id],
            tierName = row[PricingTierConfigs.tier_name],
            version = row[PricingTierConfigs.version],
            monthlyUnitLimit = row[PricingTierConfigs.monthly_unit_limit],
            monthlyErrorLimit = row[PricingTierConfigs.monthly_error_limit],
            monthlyTransactionLimit = row[PricingTierConfigs.monthly_transaction_limit],
            monthlyReplayLimit = row[PricingTierConfigs.monthly_replay_limit],
            monthlyFeedbackLimit = row[PricingTierConfigs.monthly_feedback_limit],
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

    private fun Instant.toLocalDateUtc(): kotlinx.datetime.LocalDate {
        return toLocalDateTime(TimeZone.UTC).date
    }
}
