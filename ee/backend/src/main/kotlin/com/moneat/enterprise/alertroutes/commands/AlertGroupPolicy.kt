// Moneat Enterprise - proprietary module
// Copyright (c) 2026 Moneat. All rights reserved.
// See ee/LICENSE for license terms.

package com.moneat.enterprise.alertroutes.commands

import com.moneat.enterprise.FeatureRegistry
import com.moneat.enterprise.NativeIncidentQuotaDecision
import com.moneat.enterprise.NativeIncidentQuotaKey
import com.moneat.enterprise.NativeIncidentQuotaStatus
import com.moneat.enterprise.incidents.commands.IncidentCommandDeniedException
import com.moneat.enterprise.incidents.commands.IncidentCommandQuotaExceededException

fun interface AlertGroupQuotaAdmission {
    fun admit(organizationId: Int, key: NativeIncidentQuotaKey, usage: Long, idempotencyKey: String):
        NativeIncidentQuotaDecision
}

class AlertGroupPolicy(
    private val enabled: (Int) -> Boolean = FeatureRegistry::isNativeIncidentResponseEnabled,
    private val capacityAdmission: AlertGroupQuotaAdmission =
        AlertGroupQuotaAdmission { organizationId, key, usage, id ->
            FeatureRegistry.reconcileNativeIncidentQuota(organizationId, key, usage, id)
        },
    private val usageAdmission: AlertGroupQuotaAdmission =
        AlertGroupQuotaAdmission { organizationId, key, usage, id ->
            FeatureRegistry.consumeNativeIncidentQuota(organizationId, key, usage, id)
        },
) {
    fun requireEnabled(organizationId: Int) {
        if (!enabled(organizationId)) {
            throw IncidentCommandDeniedException("Native alert grouping is not enabled for this organization")
        }
    }

    fun requireActiveGroupCapacity(organizationId: Int, nextUsage: Long, idempotencyKey: String) {
        requireAllowed(
            capacityAdmission.admit(
                organizationId,
                NativeIncidentQuotaKey.ACTIVE_ALERT_GROUPS,
                nextUsage,
                idempotencyKey,
            ),
        )
    }

    fun requireAutomaticCorrelation(organizationId: Int, idempotencyKey: String) {
        requireAllowed(
            usageAdmission.admit(
                organizationId,
                NativeIncidentQuotaKey.AUTOMATIC_CORRELATIONS,
                1,
                idempotencyKey,
            ),
        )
    }

    private fun requireAllowed(decision: NativeIncidentQuotaDecision) {
        if (!decision.allowed) {
            throw IncidentCommandQuotaExceededException(
                decision.message ?: "Native alert grouping quota is exhausted",
            )
        }
    }

    companion object {
        fun allowForTests(): AlertGroupPolicy {
            val allow = AlertGroupQuotaAdmission { _, key, usage, _ ->
                NativeIncidentQuotaDecision(
                    allowed = true,
                    status = NativeIncidentQuotaStatus(key, Long.MAX_VALUE, usage),
                )
            }
            return AlertGroupPolicy(enabled = { true }, capacityAdmission = allow, usageAdmission = allow)
        }
    }
}
