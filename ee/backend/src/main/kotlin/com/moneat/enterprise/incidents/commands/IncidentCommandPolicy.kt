// Moneat Enterprise - proprietary module
// Copyright (c) 2026 Moneat. All rights reserved.
// See ee/LICENSE for license terms.

package com.moneat.enterprise.incidents.commands

import com.moneat.enterprise.FeatureRegistry
import com.moneat.enterprise.NativeIncidentQuotaKey
import com.moneat.monitoring.OperationalMetrics

fun interface IncidentEntitlement {
    fun isEnabled(organizationId: Int): Boolean
}

fun interface IncidentCommandAuthorizer {
    fun isAllowed(actor: IncidentCommandActor, commandType: IncidentCommandType): Boolean
}

fun interface IncidentQuotaAdmission {
    fun consume(command: IncidentCommand): com.moneat.enterprise.NativeIncidentQuotaDecision
}

class IncidentCommandPolicy(
    private val entitlement: IncidentEntitlement = IncidentEntitlement {
        FeatureRegistry.isNativeIncidentResponseEnabled(it)
    },
    private val authorizer: IncidentCommandAuthorizer = IncidentCommandAuthorizer { actor, _ ->
        actor.organizationId > 0 && actor.userId > 0
    },
    private val quotaAdmission: IncidentQuotaAdmission = IncidentQuotaAdmission { command ->
        FeatureRegistry.consumeNativeIncidentQuota(
            organizationId = command.actor.organizationId,
            quotaKey = NativeIncidentQuotaKey.NATIVE_INCIDENTS,
            quantity = 1,
            idempotencyKey = "incident:${command.commandKey}",
        )
    },
) {
    fun requireAllowed(command: IncidentCommand) {
        val actor = command.actor
        if (!entitlement.isEnabled(actor.organizationId)) {
            OperationalMetrics.recordNativeIncidentRolloutDecision("command", "denied")
            throw IncidentCommandDeniedException("Native incident response is not enabled for this organization")
        }
        if (!authorizer.isAllowed(actor, command.type)) {
            throw IncidentCommandDeniedException("Actor is not authorized to ${command.type.wire.lowercase()}")
        }
        require(command.commandKey.isNotBlank()) { "Incident command key is required" }
        require(command.commandKey.length <= MAX_COMMAND_KEY_LENGTH) { "Incident command key is too long" }
        require(actor.origin.isNotBlank()) { "Incident command origin is required" }
    }

    fun requireQuota(command: IncidentCommand) {
        if (command !is DeclareIncidentCommand) return
        val decision = quotaAdmission.consume(command)
        if (!decision.allowed) {
            throw IncidentCommandQuotaExceededException(
                decision.message ?: "Native incident quota is exhausted; upgrade the plan or reduce usage",
            )
        }
    }

    companion object {
        private const val MAX_COMMAND_KEY_LENGTH = 160

        fun allowForTests(): IncidentCommandPolicy =
            IncidentCommandPolicy(
                entitlement = IncidentEntitlement { true },
                authorizer = IncidentCommandAuthorizer { _, _ -> true },
                quotaAdmission = IncidentQuotaAdmission {
                    com.moneat.enterprise.NativeIncidentQuotaDecision(
                        allowed = true,
                        status = com.moneat.enterprise.NativeIncidentQuotaStatus(
                            NativeIncidentQuotaKey.NATIVE_INCIDENTS,
                            limit = Long.MAX_VALUE,
                            used = 0,
                        ),
                    )
                },
            )
    }
}
