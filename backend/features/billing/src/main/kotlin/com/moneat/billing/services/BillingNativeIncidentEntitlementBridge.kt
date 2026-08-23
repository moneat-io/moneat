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

package com.moneat.billing.services

import com.moneat.billing.models.NativeIncidentUsageCounters
import com.moneat.billing.models.NativeIncidentUsageEvents
import com.moneat.billing.models.NativeIncidentPlanEntitlements
import com.moneat.config.EnvConfig
import com.moneat.enterprise.FeatureRegistry
import com.moneat.enterprise.NativeIncidentEntitlementBridge
import com.moneat.enterprise.NativeIncidentEntitlementStatus
import com.moneat.enterprise.NativeIncidentQuotaDecision
import com.moneat.enterprise.NativeIncidentQuotaKey
import com.moneat.enterprise.NativeIncidentQuotaStatus
import com.moneat.enterprise.NativeIncidentQuotaWindow
import com.moneat.monitoring.OperationalMetrics
import kotlinx.datetime.TimeZone
import kotlinx.datetime.toLocalDateTime
import kotlinx.serialization.json.Json
import org.jetbrains.exposed.v1.core.and
import org.jetbrains.exposed.v1.core.eq
import org.jetbrains.exposed.v1.jdbc.insert
import org.jetbrains.exposed.v1.jdbc.insertIgnore
import org.jetbrains.exposed.v1.jdbc.selectAll
import org.jetbrains.exposed.v1.jdbc.transactions.transaction
import org.jetbrains.exposed.v1.jdbc.update
import kotlin.time.Clock

class BillingNativeIncidentEntitlementBridge(
    private val pricingTierService: PricingTierService = PricingTierService(),
) : NativeIncidentEntitlementBridge {
    override fun isEnabled(organizationId: Int): Boolean = resolveAccess(organizationId).enabled

    override fun status(organizationId: Int): NativeIncidentEntitlementStatus {
        require(organizationId > 0) { "Organization ID must be positive" }
        val access = resolveAccess(organizationId)
        if (!access.enabled) {
            return NativeIncidentEntitlementStatus(
                enabled = false,
                plan = access.plan,
                reason = access.reason,
            )
        }
        return NativeIncidentEntitlementStatus(
            enabled = true,
            plan = access.plan,
            quotas = transaction { quotaStatuses(organizationId, access.limits) },
        )
    }

    override fun consume(
        organizationId: Int,
        quotaKey: NativeIncidentQuotaKey,
        quantity: Long,
        idempotencyKey: String,
    ): NativeIncidentQuotaDecision {
        require(quantity > 0) { "Quota quantity must be positive" }
        require(idempotencyKey.isNotBlank()) { "Quota idempotency key is required" }
        val access = resolveAccess(organizationId)
        if (!access.enabled) return deniedForEntitlement(quotaKey, access.reason)
        return transaction {
            mutateUsage(
                UsageMutationRequest(
                    organizationId = organizationId,
                    quotaKey = quotaKey,
                    idempotencyKey = idempotencyKey,
                    limit = access.limits[quotaKey] ?: 0,
                    target = UsageTarget.Increment(quantity),
                ),
            )
        }.also { recordDecision(quotaKey, it) }
    }

    override fun reconcile(
        organizationId: Int,
        quotaKey: NativeIncidentQuotaKey,
        authoritativeUsage: Long,
        idempotencyKey: String,
    ): NativeIncidentQuotaDecision {
        require(authoritativeUsage >= 0) { "Authoritative quota usage cannot be negative" }
        require(idempotencyKey.isNotBlank()) { "Quota idempotency key is required" }
        val access = resolveAccess(organizationId)
        if (!access.enabled) return deniedForEntitlement(quotaKey, access.reason)
        return transaction {
            mutateUsage(
                UsageMutationRequest(
                    organizationId = organizationId,
                    quotaKey = quotaKey,
                    idempotencyKey = "reconcile:$idempotencyKey:$authoritativeUsage",
                    limit = access.limits[quotaKey] ?: 0,
                    target = UsageTarget.Authoritative(authoritativeUsage),
                ),
            )
        }.also { recordDecision(quotaKey, it) }
    }

    private fun mutateUsage(request: UsageMutationRequest): NativeIncidentQuotaDecision {
        val organizationId = request.organizationId
        val quotaKey = request.quotaKey
        val idempotencyKey = request.idempotencyKey
        val limit = request.limit
        val windowKey = windowKey(quotaKey)
        existingEvent(organizationId, quotaKey, windowKey, idempotencyKey)?.let {
            return decision(quotaKey, limit, currentUsage(organizationId, quotaKey, windowKey), replayed = true)
        }
        NativeIncidentUsageCounters.insertIgnore {
            it[NativeIncidentUsageCounters.organizationId] = organizationId
            it[NativeIncidentUsageCounters.quotaKey] = quotaKey.wire
            it[NativeIncidentUsageCounters.windowKey] = windowKey
            it[used] = 0
            it[limitSnapshot] = limit
        }
        val counter =
            NativeIncidentUsageCounters
                .selectAll()
                .where {
                    counterIdentity(organizationId, quotaKey, windowKey)
                }.forUpdate()
                .single()
        existingEvent(organizationId, quotaKey, windowKey, idempotencyKey)?.let {
            return decision(
                quotaKey = quotaKey,
                limit = limit,
                used = counter[NativeIncidentUsageCounters.used],
                replayed = true,
            )
        }
        val previousUsage = counter[NativeIncidentUsageCounters.used]
        val nextUsage = request.target.desiredUsage(previousUsage).coerceAtLeast(0)
        if (request.target.rejectOverLimit && nextUsage > limit) {
            return quotaExceededDecision(quotaKey, limit, previousUsage)
        }
        NativeIncidentUsageEvents.insert {
            it[NativeIncidentUsageEvents.organizationId] = organizationId
            it[NativeIncidentUsageEvents.quotaKey] = quotaKey.wire
            it[NativeIncidentUsageEvents.windowKey] = windowKey
            it[NativeIncidentUsageEvents.idempotencyKey] = idempotencyKey
            it[quantity] = nextUsage - previousUsage
        }
        NativeIncidentUsageCounters.update({
            counterIdentity(organizationId, quotaKey, windowKey)
        }) {
            it[used] = nextUsage
            it[limitSnapshot] = limit
            it[updatedAt] = Clock.System.now()
        }
        return decision(quotaKey, limit, nextUsage)
    }

    private fun quotaStatuses(
        organizationId: Int,
        limits: Map<NativeIncidentQuotaKey, Long>,
    ): Map<NativeIncidentQuotaKey, NativeIncidentQuotaStatus> {
        val windows = NativeIncidentQuotaKey.entries.associateWith(::windowKey)
        val counters = NativeIncidentUsageCounters
            .selectAll()
            .where { NativeIncidentUsageCounters.organizationId eq organizationId }
            .associateBy {
                it[NativeIncidentUsageCounters.quotaKey] to it[NativeIncidentUsageCounters.windowKey]
            }
        return NativeIncidentQuotaKey.entries.associateWith { key ->
            val used = counters[key.wire to windows.getValue(key)]?.get(NativeIncidentUsageCounters.used) ?: 0
            NativeIncidentQuotaStatus(
                key = key,
                limit = limits[key] ?: 0,
                used = used,
            )
        }
    }

    private fun currentUsage(
        organizationId: Int,
        quotaKey: NativeIncidentQuotaKey,
        windowKey: String,
    ): Long =
        NativeIncidentUsageCounters
            .selectAll()
            .where { counterIdentity(organizationId, quotaKey, windowKey) }
            .singleOrNull()
            ?.get(NativeIncidentUsageCounters.used)
            ?: 0

    private fun existingEvent(
        organizationId: Int,
        quotaKey: NativeIncidentQuotaKey,
        windowKey: String,
        idempotencyKey: String,
    ) =
        NativeIncidentUsageEvents
            .selectAll()
            .where {
                (NativeIncidentUsageEvents.organizationId eq organizationId) and
                    (NativeIncidentUsageEvents.quotaKey eq quotaKey.wire) and
                    (NativeIncidentUsageEvents.windowKey eq windowKey) and
                    (NativeIncidentUsageEvents.idempotencyKey eq idempotencyKey)
            }.singleOrNull()

    private fun counterIdentity(
        organizationId: Int,
        quotaKey: NativeIncidentQuotaKey,
        windowKey: String,
    ) =
        (NativeIncidentUsageCounters.organizationId eq organizationId) and
            (NativeIncidentUsageCounters.quotaKey eq quotaKey.wire) and
            (NativeIncidentUsageCounters.windowKey eq windowKey)

    private fun resolveAccess(organizationId: Int): NativeIncidentPlanAccess {
        if (EnvConfig.SelfHost.enabled) {
            val licensed = FeatureRegistry.activeLicense?.features?.contains(ON_CALL_LICENSE_FEATURE) == true
            return NativeIncidentPlanAccess(
                enabled = licensed,
                plan = FeatureRegistry.activeLicense?.plan ?: "SELF_HOSTED",
                reason = if (licensed) null else "Native incident response requires the on-call license feature",
                limits = NativeIncidentQuotaKey.entries.associateWith { UNLIMITED_QUOTA },
            )
        }
        val tier = pricingTierService.getEffectiveTierForOrganization(organizationId).tier
        val planEntitlement = transaction {
            NativeIncidentPlanEntitlements
                .selectAll()
                .where { NativeIncidentPlanEntitlements.tierName eq tier.tierName.uppercase() }
                .singleOrNull()
        } ?: return NativeIncidentPlanAccess(
            enabled = false,
            plan = tier.tierName,
            reason = "Native incident response is not configured for the current plan",
            limits = emptyMap(),
        )
        val configuredQuotas = runCatching {
            Json.decodeFromString<Map<String, Long>>(planEntitlement[NativeIncidentPlanEntitlements.quotas])
        }.getOrElse {
            return NativeIncidentPlanAccess(
                enabled = false,
                plan = tier.tierName,
                reason = "Native incident response quotas are invalid for the current plan",
                limits = emptyMap(),
            )
        }
        val enabled = planEntitlement[NativeIncidentPlanEntitlements.enabled]
        return NativeIncidentPlanAccess(
            enabled = enabled,
            plan = tier.tierName,
            reason = if (enabled) null else PLAN_UPGRADE_MESSAGE,
            limits = NativeIncidentQuotaKey.entries.associateWith { key ->
                configuredQuotas[key.wire]?.coerceAtLeast(0) ?: 0
            },
        )
    }

    private fun windowKey(quotaKey: NativeIncidentQuotaKey): String =
        when (quotaKey.window) {
            NativeIncidentQuotaWindow.CAPACITY -> CAPACITY_WINDOW
            NativeIncidentQuotaWindow.MONTHLY -> {
                val date = Clock.System.now().toLocalDateTime(TimeZone.UTC).date
                date.year.toString().padStart(YEAR_WIDTH, '0') +
                    "-" + date.month.ordinal.inc().toString().padStart(MONTH_WIDTH, '0')
            }
        }

    private fun deniedForEntitlement(
        quotaKey: NativeIncidentQuotaKey,
        reason: String?,
    ): NativeIncidentQuotaDecision =
        NativeIncidentQuotaDecision(
            allowed = false,
            status = NativeIncidentQuotaStatus(quotaKey, limit = 0, used = 0),
            message = reason ?: PLAN_UPGRADE_MESSAGE,
        ).also { recordDecision(quotaKey, it) }

    private fun decision(
        quotaKey: NativeIncidentQuotaKey,
        limit: Long,
        used: Long,
        replayed: Boolean = false,
    ): NativeIncidentQuotaDecision =
        NativeIncidentQuotaDecision(
            allowed = true,
            status = NativeIncidentQuotaStatus(quotaKey, limit, used),
            replayed = replayed,
        )

    private fun quotaExceededDecision(
        quotaKey: NativeIncidentQuotaKey,
        limit: Long,
        used: Long,
    ): NativeIncidentQuotaDecision =
        NativeIncidentQuotaDecision(
            allowed = false,
            status = NativeIncidentQuotaStatus(quotaKey, limit, used),
            message = quotaExhaustedMessage(quotaKey),
        )

    private fun quotaExhaustedMessage(quotaKey: NativeIncidentQuotaKey): String =
        "${quotaKey.wire.replace('_', ' ')} quota is exhausted; upgrade the plan or reduce usage"

    private fun recordDecision(quotaKey: NativeIncidentQuotaKey, decision: NativeIncidentQuotaDecision) {
        val outcome = when {
            decision.replayed -> "replayed"
            decision.allowed -> "allowed"
            else -> "denied"
        }
        OperationalMetrics.recordNativeIncidentQuotaDecision(quotaKey.wire, outcome)
    }

    private data class NativeIncidentPlanAccess(
        val enabled: Boolean,
        val plan: String,
        val reason: String?,
        val limits: Map<NativeIncidentQuotaKey, Long>,
    )

    private data class UsageMutationRequest(
        val organizationId: Int,
        val quotaKey: NativeIncidentQuotaKey,
        val idempotencyKey: String,
        val limit: Long,
        val target: UsageTarget,
    )

    private sealed interface UsageTarget {
        val rejectOverLimit: Boolean

        fun desiredUsage(previousUsage: Long): Long

        data class Increment(val quantity: Long) : UsageTarget {
            override val rejectOverLimit: Boolean = true

            override fun desiredUsage(previousUsage: Long): Long = previousUsage + quantity
        }

        data class Authoritative(val usage: Long) : UsageTarget {
            override val rejectOverLimit: Boolean = false

            override fun desiredUsage(previousUsage: Long): Long = usage
        }
    }

    private companion object {
        const val CAPACITY_WINDOW = "capacity"
        const val ON_CALL_LICENSE_FEATURE = "oncall"
        const val PLAN_UPGRADE_MESSAGE = "Native incident response is not available on the current plan"
        const val UNLIMITED_QUOTA = 9_007_199_254_740_000L
        const val YEAR_WIDTH = 4
        const val MONTH_WIDTH = 2
    }
}
