// Moneat Enterprise - proprietary module
// Copyright (c) 2026 Moneat. All rights reserved.
// See ee/LICENSE for license terms.

package com.moneat.enterprise.incidents.commands

import com.moneat.enterprise.FeatureRegistry
import com.moneat.enterprise.NativeIncidentQuotaKey
import com.moneat.enterprise.incidents.models.NativeIncidentStatus

fun interface IncidentEntitlement {
    fun isEnabled(organizationId: Int): Boolean
}

/**
 * Capability groups an incident command belongs to. Triage incidents are unclassified, so they
 * expose investigation and staffing capabilities but withhold the commitments that only make
 * sense once the incident has been accepted.
 */
enum class IncidentCapability(val wire: String) {
    LIFECYCLE("LIFECYCLE"),
    INVESTIGATION("INVESTIGATION"),
    ROLES("ROLES"),
    PARTICIPATION("PARTICIPATION"),
    ESCALATION("ESCALATION"),
    ACTIONS("ACTIONS"),
    FOLLOW_UPS("FOLLOW_UPS"),
    STATUS_PAGE_COMMUNICATION("STATUS_PAGE_COMMUNICATION"),
}

/** Central answer to "may this capability be used while the incident is still in triage?". */
object IncidentTriageCapabilityPolicy {
    private val TRIAGE_CAPABILITIES =
        setOf(
            IncidentCapability.LIFECYCLE,
            IncidentCapability.INVESTIGATION,
            IncidentCapability.ROLES,
            IncidentCapability.PARTICIPATION,
            IncidentCapability.ESCALATION,
        )

    fun capabilityOf(commandType: IncidentCommandType): IncidentCapability =
        when (commandType) {
            IncidentCommandType.DECLARE,
            IncidentCommandType.ACCEPT,
            IncidentCommandType.DECLINE,
            IncidentCommandType.MERGE,
            IncidentCommandType.UPDATE,
            IncidentCommandType.REQUEST_UPDATE,
            IncidentCommandType.PAUSE_UPDATE_REMINDERS,
            IncidentCommandType.TRANSITION,
            IncidentCommandType.RESOLVE,
            IncidentCommandType.CANCEL,
            IncidentCommandType.REOPEN,
            -> IncidentCapability.LIFECYCLE
            IncidentCommandType.ASSIGN_ROLE,
            IncidentCommandType.CLAIM_ROLE,
            IncidentCommandType.UNASSIGN_ROLE,
            IncidentCommandType.HANDOVER_ROLE,
            -> IncidentCapability.ROLES
            IncidentCommandType.JOIN,
            IncidentCommandType.OBSERVE,
            IncidentCommandType.LEAVE,
            -> IncidentCapability.PARTICIPATION
            IncidentCommandType.ADD_TIMELINE_EVENT,
            IncidentCommandType.LINK_ON_CALL_ALERT,
            IncidentCommandType.LINK_SOURCE,
            IncidentCommandType.UNLINK_SOURCE,
            -> IncidentCapability.INVESTIGATION
            IncidentCommandType.ADD_ACTION -> IncidentCapability.ACTIONS
            IncidentCommandType.CLAIM_ACTION,
            IncidentCommandType.REASSIGN_ACTION,
            IncidentCommandType.COMPLETE_ACTION,
            IncidentCommandType.CANCEL_ACTION,
            -> IncidentCapability.ACTIONS
            IncidentCommandType.CONVERT_ACTION -> IncidentCapability.FOLLOW_UPS
        }

    fun permits(capability: IncidentCapability): Boolean = capability in TRIAGE_CAPABILITIES

    fun requireAllowed(status: NativeIncidentStatus, commandType: IncidentCommandType) {
        if (status != NativeIncidentStatus.TRIAGE) return
        val capability = capabilityOf(commandType)
        if (permits(capability)) return
        throw IncidentCommandDeniedException(
            "${capability.wire} is unavailable until the incident is accepted out of triage",
        )
    }
}

fun interface IncidentCommandAuthorizer {
    fun isAllowed(actor: IncidentCommandActor, commandType: IncidentCommandType): Boolean
}

fun interface IncidentQuotaAdmission {
    fun consume(command: IncidentCommand): com.moneat.enterprise.NativeIncidentQuotaDecision
}

class IncidentCommandPolicy(
    private val entitlement: IncidentEntitlement = IncidentEntitlement {
        FeatureRegistry.isNativeIncidentResponseEntitled(it)
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

    /** Applied once the incident's current status is known, so triage restrictions stay central. */
    fun requireCapabilityAllowed(command: ExistingIncidentCommand, status: NativeIncidentStatus) {
        IncidentTriageCapabilityPolicy.requireAllowed(status, command.type)
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
