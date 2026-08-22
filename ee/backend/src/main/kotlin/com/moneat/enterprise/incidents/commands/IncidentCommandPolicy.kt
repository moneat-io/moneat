// Moneat Enterprise - proprietary module
// Copyright (c) 2026 Moneat. All rights reserved.
// See ee/LICENSE for license terms.

package com.moneat.enterprise.incidents.commands

import com.moneat.enterprise.FeatureRegistry

fun interface IncidentEntitlement {
    fun isEnabled(organizationId: Int): Boolean
}

fun interface IncidentCommandAuthorizer {
    fun isAllowed(actor: IncidentCommandActor, commandType: IncidentCommandType): Boolean
}

class IncidentCommandPolicy(
    private val entitlement: IncidentEntitlement = IncidentEntitlement {
        FeatureRegistry.hasModule(ON_CALL_MODULE_NAME)
    },
    private val authorizer: IncidentCommandAuthorizer = IncidentCommandAuthorizer { actor, _ ->
        actor.organizationId > 0 && actor.userId > 0
    },
) {
    fun requireAllowed(command: IncidentCommand) {
        val actor = command.actor
        if (!entitlement.isEnabled(actor.organizationId)) {
            throw IncidentCommandDeniedException("Native incident response is not enabled for this organization")
        }
        if (!authorizer.isAllowed(actor, command.type)) {
            throw IncidentCommandDeniedException("Actor is not authorized to ${command.type.wire.lowercase()}")
        }
        require(command.commandKey.isNotBlank()) { "Incident command key is required" }
        require(command.commandKey.length <= MAX_COMMAND_KEY_LENGTH) { "Incident command key is too long" }
        require(actor.origin.isNotBlank()) { "Incident command origin is required" }
    }

    companion object {
        private const val ON_CALL_MODULE_NAME = "On-Call"
        private const val MAX_COMMAND_KEY_LENGTH = 160

        fun allowForTests(): IncidentCommandPolicy =
            IncidentCommandPolicy(
                entitlement = IncidentEntitlement { true },
                authorizer = IncidentCommandAuthorizer { _, _ -> true },
            )
    }
}
