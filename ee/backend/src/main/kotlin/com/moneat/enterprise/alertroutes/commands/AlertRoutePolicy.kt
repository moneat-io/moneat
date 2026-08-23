// Moneat Enterprise - proprietary module
// Copyright (c) 2026 Moneat. All rights reserved.
// See ee/LICENSE for license terms.

package com.moneat.enterprise.alertroutes.commands

import com.moneat.enterprise.FeatureRegistry
import com.moneat.enterprise.NativeIncidentQuotaDecision
import com.moneat.enterprise.NativeIncidentQuotaKey
import com.moneat.enterprise.NativeIncidentQuotaStatus
import com.moneat.enterprise.incidents.commands.IncidentCommandDeniedException
import com.moneat.enterprise.incidents.commands.IncidentEntitlement
import com.moneat.enterprise.incidents.commands.IncidentCommandQuotaExceededException

fun interface AlertRouteQuotaAdmission {
    fun admit(organizationId: Int, enabledRouteCount: Long, idempotencyKey: String): NativeIncidentQuotaDecision
}

/**
 * Rollout and authorization gate for alert-route mutations.
 *
 * The default entitlement combines the organization-and-environment rollout decision with the paid
 * native incident-response entitlement. Enabled-route capacity is reconciled through the canonical
 * billing quota bridge in the same transaction as the protected mutation.
 */
class AlertRoutePolicy(
    private val entitlement: IncidentEntitlement = IncidentEntitlement {
        FeatureRegistry.isNativeIncidentResponseEnabled(it)
    },
    private val quotaAdmission: AlertRouteQuotaAdmission = AlertRouteQuotaAdmission { organizationId, count, key ->
        val status = FeatureRegistry.nativeIncidentEntitlementStatus(organizationId)
        val quota = status.quotas[NativeIncidentQuotaKey.ENABLED_ALERT_ROUTES]
            ?: return@AlertRouteQuotaAdmission unavailableQuotaDecision()
        if (count > quota.limit) {
            return@AlertRouteQuotaAdmission NativeIncidentQuotaDecision(
                allowed = false,
                status = quota.copy(used = count),
                message = "Enabled alert route quota is exhausted; disable a route or upgrade the plan",
            )
        }
        FeatureRegistry.reconcileNativeIncidentQuota(
            organizationId = organizationId,
            quotaKey = NativeIncidentQuotaKey.ENABLED_ALERT_ROUTES,
            authoritativeUsage = count,
            idempotencyKey = key,
        )
    },
) {
    fun requireAllowed(command: AlertRouteCommand) {
        val actor = command.actor
        if (!entitlement.isEnabled(actor.organizationId)) {
            throw IncidentCommandDeniedException("Native incident response is not enabled for this organization")
        }
        requireReadAllowed(actor.organizationId)
        require(command.commandKey.isNotBlank()) { "Alert route command key is required" }
        require(command.commandKey.length <= MAX_COMMAND_KEY_LENGTH) { "Alert route command key is too long" }
        require(actor.origin.isNotBlank()) { "Alert route command origin is required" }
        require(actor.userId > 0) { "Alert route command actor is required" }
    }

    fun requireReadAllowed(organizationId: Int) {
        if (!entitlement.isEnabled(organizationId)) {
            throw IncidentCommandDeniedException("Native incident response is not enabled for this organization")
        }
    }

    fun isEnabled(organizationId: Int): Boolean = entitlement.isEnabled(organizationId)

    fun requireQuota(command: AlertRouteCommand, enabledRouteCount: Long) {
        val decision = quotaAdmission.admit(
            organizationId = command.actor.organizationId,
            enabledRouteCount = enabledRouteCount,
            idempotencyKey = "alert-route:${command.commandKey}",
        )
        if (!decision.allowed) {
            throw IncidentCommandQuotaExceededException(
                decision.message ?: "Enabled alert route quota is exhausted",
            )
        }
    }

    companion object {
        const val MAX_COMMAND_KEY_LENGTH = 160
        private fun unavailableQuotaDecision() =
            NativeIncidentQuotaDecision(
                allowed = false,
                status = NativeIncidentQuotaStatus(
                    key = NativeIncidentQuotaKey.ENABLED_ALERT_ROUTES,
                    limit = 0,
                    used = 0,
                ),
                message = "Enabled alert route quota provider is unavailable",
            )

        fun allowForTests(): AlertRoutePolicy =
            AlertRoutePolicy(
                entitlement = IncidentEntitlement { true },
                quotaAdmission = AlertRouteQuotaAdmission { _, count, _ ->
                    NativeIncidentQuotaDecision(
                        allowed = true,
                        status = NativeIncidentQuotaStatus(
                            NativeIncidentQuotaKey.ENABLED_ALERT_ROUTES,
                            limit = Long.MAX_VALUE,
                            used = count,
                        ),
                    )
                },
            )

        fun denyForTests(): AlertRoutePolicy = AlertRoutePolicy(entitlement = IncidentEntitlement { false })
    }
}
