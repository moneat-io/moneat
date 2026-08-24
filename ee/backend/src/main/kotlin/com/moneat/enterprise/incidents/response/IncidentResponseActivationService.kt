// Moneat Enterprise - proprietary module
// Copyright (c) 2026 Moneat. All rights reserved.
// See ee/LICENSE for license terms.

package com.moneat.enterprise.incidents.response

import com.moneat.enterprise.incidents.events.NativeIncidentDomainEvent
import com.moneat.enterprise.incidents.models.IncidentParticipationType
import com.moneat.enterprise.incidents.models.NativeIncidentMode
import com.moneat.enterprise.incidents.models.NativeIncidentParticipants
import com.moneat.enterprise.incidents.models.NativeIncidentRoleAssignments
import com.moneat.enterprise.incidents.models.NativeIncidentRoleDefinitions
import com.moneat.enterprise.incidents.models.NativeIncidentStatus
import com.moneat.enterprise.alertroutes.models.EnterpriseAlertGroupEscalations
import com.moneat.enterprise.alertroutes.models.EnterpriseAlertGroups
import com.moneat.enterprise.oncall.models.OnCallAlerts
import com.moneat.enterprise.oncall.models.OnCallIncidentAlerts
import com.moneat.enterprise.oncall.models.OnCallIncidents
import com.moneat.shared.models.Memberships
import com.moneat.shared.services.toUuidOrNull
import kotlinx.serialization.Serializable
import kotlinx.serialization.json.JsonElement
import kotlinx.serialization.json.JsonPrimitive
import kotlinx.serialization.json.contentOrNull
import kotlinx.serialization.json.jsonPrimitive
import org.jetbrains.exposed.v1.core.and
import org.jetbrains.exposed.v1.core.eq
import org.jetbrains.exposed.v1.core.inList
import org.jetbrains.exposed.v1.core.isNotNull
import org.jetbrains.exposed.v1.core.isNull
import org.jetbrains.exposed.v1.jdbc.insert
import org.jetbrains.exposed.v1.jdbc.insertAndGetId
import org.jetbrains.exposed.v1.jdbc.insertIgnore
import org.jetbrains.exposed.v1.jdbc.selectAll
import org.jetbrains.exposed.v1.jdbc.transactions.transaction
import org.jetbrains.exposed.v1.jdbc.update
import kotlin.time.Clock
import kotlin.time.Instant

private const val INCIDENT_COMMANDER_ROLE_KEY = "incident-commander"
private const val TARGET_COMMANDER = "COMMANDER"
private const val TARGET_OWNERSHIP = "OWNERSHIP"

data class IncidentResponsePageRequest(
    val organizationId: Int,
    val escalationPolicyId: Int,
    val title: String,
    val severity: String,
    val deduplicationKey: String,
    val metadata: Map<String, JsonElement>,
)

private data class IncidentResponseActivationPlan(
    val status: IncidentResponseActivationStatus,
    val error: String?,
)

fun interface IncidentResponsePager {
    fun page(request: IncidentResponsePageRequest): Int?
}

@Serializable
data class IncidentResponsePolicyResponse(
    val id: String?,
    val commanderPolicyId: String? = null,
    val ownershipPolicyId: String? = null,
    val pageOwnership: Boolean = true,
    val pageTestIncidents: Boolean = false,
    val pageRetrospectiveIncidents: Boolean = false,
)

data class IncidentResponsePolicyInput(
    val commanderPolicyResourceId: String?,
    val ownershipPolicyResourceId: String?,
    val pageOwnership: Boolean,
    val pageTestIncidents: Boolean,
    val pageRetrospectiveIncidents: Boolean,
)

@Serializable
data class IncidentResponseTargetResponse(
    val id: String,
    val targetKey: String,
    val targetType: String,
    val status: String,
    val attemptCount: Int,
    val onCallAlertId: String? = null,
    val error: String? = null,
)

@Serializable
data class IncidentResponseActivationResponse(
    val id: String,
    val revision: Int,
    val trigger: String,
    val status: String,
    val desiredCount: Int,
    val attemptedCount: Int,
    val acknowledgedCount: Int,
    val lastError: String? = null,
    val targets: List<IncidentResponseTargetResponse>,
)

class IncidentResponsePolicyService {
    fun get(organizationId: Int): IncidentResponsePolicyResponse = transaction {
        NativeIncidentResponsePolicies
            .selectAll()
            .where { NativeIncidentResponsePolicies.organizationId eq organizationId }
            .singleOrNull()
            ?.let(::toResponse)
            ?: IncidentResponsePolicyResponse(id = null)
    }

    fun update(
        organizationId: Int,
        actorUserId: Int,
        input: IncidentResponsePolicyInput,
    ): IncidentResponsePolicyResponse = transaction {
        requireMember(organizationId, actorUserId)
        val commanderPolicyId = resolvePolicy(organizationId, input.commanderPolicyResourceId)
        val ownershipPolicyId = resolvePolicy(organizationId, input.ownershipPolicyResourceId)
        val now = Clock.System.now()
        val current = NativeIncidentResponsePolicies.selectAll()
            .where { NativeIncidentResponsePolicies.organizationId eq organizationId }
            .singleOrNull()
        if (current == null) {
            NativeIncidentResponsePolicies.insert {
                it[resourceId] = kotlin.uuid.Uuid.random()
                it[NativeIncidentResponsePolicies.organizationId] = organizationId
                it[NativeIncidentResponsePolicies.commanderPolicyId] = commanderPolicyId
                it[NativeIncidentResponsePolicies.ownershipPolicyId] = ownershipPolicyId
                it[pageOwnership] = input.pageOwnership
                it[pageTestIncidents] = input.pageTestIncidents
                it[pageRetrospectiveIncidents] = input.pageRetrospectiveIncidents
                it[createdBy] = actorUserId
                it[createdAt] = now
                it[updatedAt] = now
            }
        } else {
            NativeIncidentResponsePolicies.update({ NativeIncidentResponsePolicies.organizationId eq organizationId }) {
                it[NativeIncidentResponsePolicies.commanderPolicyId] = commanderPolicyId
                it[NativeIncidentResponsePolicies.ownershipPolicyId] = ownershipPolicyId
                it[pageOwnership] = input.pageOwnership
                it[pageTestIncidents] = input.pageTestIncidents
                it[pageRetrospectiveIncidents] = input.pageRetrospectiveIncidents
                it[updatedAt] = now
            }
        }
        get(organizationId)
    }

    fun resolvePolicyId(organizationId: Int, resourceId: String?): Int? =
        resolvePolicy(organizationId, resourceId)

    private fun resolvePolicy(organizationId: Int, resourceId: String?): Int? {
        val uuid = resourceId?.trim()?.takeIf(String::isNotEmpty)?.toUuidOrNull() ?: return null
        return com.moneat.shared.models.EscalationPolicies
            .selectAll()
            .where {
                (com.moneat.shared.models.EscalationPolicies.organizationId eq organizationId) and
                    (com.moneat.shared.models.EscalationPolicies.resourceId eq uuid)
            }
            .singleOrNull()
            ?.get(com.moneat.shared.models.EscalationPolicies.id)
            ?.value
            ?: throw IllegalArgumentException("Escalation policy not found")
    }

    private fun requireMember(organizationId: Int, userId: Int) {
        require(
            Memberships.selectAll().where {
                (Memberships.organization_id eq organizationId) and (Memberships.user_id eq userId)
            }.limit(1).any(),
        ) { "Actor is not a member of the incident organization" }
    }

    private fun toResponse(row: org.jetbrains.exposed.v1.core.ResultRow): IncidentResponsePolicyResponse =
        IncidentResponsePolicyResponse(
            id = row[NativeIncidentResponsePolicies.resourceId].toString(),
            commanderPolicyId = row[NativeIncidentResponsePolicies.commanderPolicyId]?.let { policyResourceId(it) },
            ownershipPolicyId = row[NativeIncidentResponsePolicies.ownershipPolicyId]?.let { policyResourceId(it) },
            pageOwnership = row[NativeIncidentResponsePolicies.pageOwnership],
            pageTestIncidents = row[NativeIncidentResponsePolicies.pageTestIncidents],
            pageRetrospectiveIncidents = row[NativeIncidentResponsePolicies.pageRetrospectiveIncidents],
        )

    private fun policyResourceId(policyId: Int): String =
        com.moneat.shared.models.EscalationPolicies.selectAll()
            .where { com.moneat.shared.models.EscalationPolicies.id eq policyId }
            .single()[com.moneat.shared.models.EscalationPolicies.resourceId]
            .toString()
}

class IncidentResponseActivationService(
    private val policyService: IncidentResponsePolicyService = IncidentResponsePolicyService(),
    private val pager: IncidentResponsePager = IncidentResponsePager { null },
    private val now: () -> Instant = { Clock.System.now() },
) {
    fun policy(organizationId: Int): IncidentResponsePolicyResponse = policyService.get(organizationId)

    fun updatePolicy(
        organizationId: Int,
        actorUserId: Int,
        input: IncidentResponsePolicyInput,
    ): IncidentResponsePolicyResponse = policyService.update(organizationId, actorUserId, input)

    fun activate(event: NativeIncidentDomainEvent) {
        if (event.eventType == "INCIDENT_DECLARE" && event.status() != NativeIncidentStatus.ACTIVE.wire) return
        if (event.eventType != "INCIDENT_DECLARE" && event.eventType != "INCIDENT_ACCEPT") return
        val activationId = ensureActivation(event)
        dispatch(activationId)
    }

    fun retry(
        organizationId: Int,
        activationResourceId: String,
        actorUserId: Int,
        incidentId: Int? = null,
    ): IncidentResponseActivationResponse =
        transaction {
            requireMember(organizationId, actorUserId)
            val resourceId = activationResourceId.toUuidOrNull()
                ?: throw IllegalArgumentException("Invalid activation ID")
            val activation = NativeIncidentResponseActivations.selectAll().where {
                (NativeIncidentResponseActivations.organizationId eq organizationId) and
                    (NativeIncidentResponseActivations.resourceId eq resourceId)
            }.singleOrNull()?.also {
                if (incidentId != null && it[NativeIncidentResponseActivations.incidentId] != incidentId) {
                    throw NoSuchElementException("Incident response activation not found")
                }
            } ?: throw NoSuchElementException("Incident response activation not found")
            NativeIncidentResponseTargets.update({
                (NativeIncidentResponseTargets.organizationId eq organizationId) and
                    (NativeIncidentResponseTargets.activationId eq
                        activation[NativeIncidentResponseActivations.id].value) and
                    (NativeIncidentResponseTargets.status inList listOf(
                        IncidentResponseTargetStatus.FAILED.wire,
                        IncidentResponseTargetStatus.RETRYING.wire,
                    ))
            }) {
                it[status] = IncidentResponseTargetStatus.RETRYING.wire
                it[lastError] = null
                it[updatedAt] = now()
            }
            NativeIncidentResponseActivations.update({
                NativeIncidentResponseActivations.id eq activation[NativeIncidentResponseActivations.id].value
            }) {
                it[status] = IncidentResponseActivationStatus.ACTIVE.wire
                it[lastError] = null
                it[updatedAt] = now()
            }
            activation[NativeIncidentResponseActivations.id].value
        }.also(::dispatch).let { activationResponse(organizationId, activationResourceId) }

    fun markAcknowledged(alertId: Int, userId: Int): Boolean {
        val target = transaction {
            NativeIncidentResponseTargets.selectAll().where {
                (NativeIncidentResponseTargets.onCallAlertId eq alertId) and
                    (NativeIncidentResponseTargets.status inList listOf(
                        IncidentResponseTargetStatus.ATTEMPTED.wire,
                        IncidentResponseTargetStatus.RETRYING.wire,
                    ))
            }.singleOrNull()
        } ?: return false
        val updated = transaction {
            NativeIncidentResponseTargets.update({
                NativeIncidentResponseTargets.id eq target[NativeIncidentResponseTargets.id].value
            }) {
                it[status] = IncidentResponseTargetStatus.ACKNOWLEDGED.wire
                it[acknowledgedAt] = now()
                it[updatedAt] = now()
            }
        }
        if (updated == 0) return false
        claimCommander(target[NativeIncidentResponseTargets.activationId], userId)
        addParticipant(target[NativeIncidentResponseTargets.activationId], userId)
        return true
    }

    fun activationResponse(organizationId: Int, activationResourceId: String): IncidentResponseActivationResponse =
        transaction {
            val resourceId = activationResourceId.toUuidOrNull()
                ?: throw IllegalArgumentException("Invalid activation ID")
            val activation = NativeIncidentResponseActivations.selectAll().where {
                (NativeIncidentResponseActivations.organizationId eq organizationId) and
                    (NativeIncidentResponseActivations.resourceId eq resourceId)
            }.singleOrNull() ?: throw NoSuchElementException("Incident response activation not found")
            activationResponse(activation)
        }

    fun incidentResponse(organizationId: Int, incidentResourceId: String): List<IncidentResponseActivationResponse> =
        transaction {
            val incidentUuid = incidentResourceId.toUuidOrNull()
                ?: throw IllegalArgumentException("Invalid incident ID")
            val incidentId = OnCallIncidents.selectAll().where {
                (OnCallIncidents.organizationId eq organizationId) and (OnCallIncidents.resourceId eq incidentUuid)
            }.singleOrNull()?.get(OnCallIncidents.id)?.value
                ?: throw NoSuchElementException("Incident not found")
            NativeIncidentResponseActivations.selectAll().where {
                (NativeIncidentResponseActivations.organizationId eq organizationId) and
                    (NativeIncidentResponseActivations.incidentId eq incidentId)
            }.orderBy(NativeIncidentResponseActivations.createdAt).map(::activationResponse)
        }

    fun incidentResponseById(organizationId: Int, incidentId: Int): List<IncidentResponseActivationResponse> =
        transaction {
            val incident = OnCallIncidents.selectAll().where {
                (OnCallIncidents.organizationId eq organizationId) and (OnCallIncidents.id eq incidentId)
            }.singleOrNull() ?: throw NoSuchElementException("Incident not found")
            NativeIncidentResponseActivations.selectAll().where {
                (NativeIncidentResponseActivations.organizationId eq organizationId) and
                (NativeIncidentResponseActivations.incidentId eq incident[OnCallIncidents.id].value)
            }.orderBy(NativeIncidentResponseActivations.createdAt).map(::activationResponse)
        }

    private fun ensureActivation(event: NativeIncidentDomainEvent): Int = transaction {
        val existing = NativeIncidentResponseActivations.selectAll().where {
            (NativeIncidentResponseActivations.organizationId eq event.organizationId) and
                (NativeIncidentResponseActivations.incidentId eq event.incidentId) and
                (NativeIncidentResponseActivations.activationRevision eq event.aggregateVersion) and
                (NativeIncidentResponseActivations.trigger eq event.eventType)
        }.singleOrNull()
        if (existing != null) return@transaction existing[NativeIncidentResponseActivations.id].value

        val incident = OnCallIncidents.selectAll().where { OnCallIncidents.id eq event.incidentId }.single()
        val config = policyService.get(event.organizationId)
        val status = NativeIncidentStatus.fromWire(incident[OnCallIncidents.status])
        val mode = NativeIncidentMode.entries.firstOrNull { it.wire == incident[OnCallIncidents.mode] }
        val plan = activationPlan(status, mode, config)
        val activationId = createActivation(event, plan)
        if (plan.status == IncidentResponseActivationStatus.PENDING) {
            prepareTargets(event, config, activationId)
        }
        activationId
    }

    private fun activationPlan(
        status: NativeIncidentStatus?,
        mode: NativeIncidentMode?,
        config: IncidentResponsePolicyResponse,
    ): IncidentResponseActivationPlan {
        val pagingDisabled = when (mode) {
            NativeIncidentMode.TEST -> !config.pageTestIncidents
            NativeIncidentMode.RETROSPECTIVE -> !config.pageRetrospectiveIncidents
            else -> false
        }
        return when {
            status != NativeIncidentStatus.ACTIVE ->
                IncidentResponseActivationPlan(IncidentResponseActivationStatus.SKIPPED, "Incident is not active")
            pagingDisabled -> IncidentResponseActivationPlan(
                IncidentResponseActivationStatus.SKIPPED,
                "Paging is disabled for ${mode?.wire?.lowercase()} incidents",
            )
            else -> IncidentResponseActivationPlan(IncidentResponseActivationStatus.PENDING, null)
        }
    }

    private fun createActivation(
        event: NativeIncidentDomainEvent,
        plan: IncidentResponseActivationPlan,
    ): Int = NativeIncidentResponseActivations.insertAndGetId {
        it[resourceId] = kotlin.uuid.Uuid.random()
        it[organizationId] = event.organizationId
        it[incidentId] = event.incidentId
        it[activationRevision] = event.aggregateVersion
        it[trigger] = event.eventType
        it[NativeIncidentResponseActivations.status] = plan.status.wire
        it[desiredCount] = 0
        it[attemptedCount] = 0
        it[acknowledgedCount] = 0
        it[lastError] = plan.error
        it[startedAt] = now()
        it[completedAt] = now().takeIf { plan.status != IncidentResponseActivationStatus.PENDING }
        it[createdAt] = now()
        it[updatedAt] = now()
    }.value

    private fun prepareTargets(
        event: NativeIncidentDomainEvent,
        config: IncidentResponsePolicyResponse,
        activationId: Int,
    ) {
        val commanderPolicyId = policyService.resolvePolicyId(event.organizationId, config.commanderPolicyId)
        val ownershipPolicyId = policyService.resolvePolicyId(event.organizationId, config.ownershipPolicyId)
        val policies: List<Pair<String, Int>> = listOfNotNull(
            commanderPolicyId?.let { policyId -> TARGET_COMMANDER to policyId },
            ownershipPolicyId
                ?.takeIf { config.pageOwnership }
                ?.let { policyId -> TARGET_OWNERSHIP to policyId },
        ).distinctBy { it.second }
        val routePolicies = routePagedPolicies(event.organizationId, event.incidentId)
        policies.forEach { (type, policyId) ->
            val skipped = policyId in routePolicies
            NativeIncidentResponseTargets.insert {
                it[resourceId] = kotlin.uuid.Uuid.random()
                it[organizationId] = event.organizationId
                it[NativeIncidentResponseTargets.activationId] = activationId
                it[NativeIncidentResponseTargets.targetKey] = "$type:$policyId"
                it[NativeIncidentResponseTargets.targetType] = type
                it[NativeIncidentResponseTargets.escalationPolicyId] = policyId
                it[NativeIncidentResponseTargets.userId] = null
                it[NativeIncidentResponseTargets.onCallAlertId] = null
                it[NativeIncidentResponseTargets.status] = targetStatus(skipped)
                it[NativeIncidentResponseTargets.attemptCount] = 0
                it[NativeIncidentResponseTargets.desiredAt] = now()
                it[NativeIncidentResponseTargets.attemptedAt] = null
                it[NativeIncidentResponseTargets.acknowledgedAt] = null
                it[NativeIncidentResponseTargets.failedAt] = null
                it[NativeIncidentResponseTargets.lastError] = "Already paged by Alert Route".takeIf { skipped }
                it[NativeIncidentResponseTargets.createdAt] = now()
                it[NativeIncidentResponseTargets.updatedAt] = now()
            }
        }
        NativeIncidentResponseActivations.update({ NativeIncidentResponseActivations.id eq activationId }) {
            it[desiredCount] = NativeIncidentResponseTargets.selectAll().where {
                NativeIncidentResponseTargets.activationId eq activationId
            }.count().toInt()
            it[NativeIncidentResponseActivations.status] = if (policies.isEmpty()) {
                IncidentResponseActivationStatus.FAILED.wire
            } else {
                IncidentResponseActivationStatus.PENDING.wire
            }
            it[lastError] = "No incident response policy is configured".takeIf { policies.isEmpty() }
            it[updatedAt] = now()
        }
    }

    private fun targetStatus(skipped: Boolean): String = if (skipped) {
        IncidentResponseTargetStatus.SKIPPED.wire
    } else {
        IncidentResponseTargetStatus.DESIRED.wire
    }

    private fun dispatch(activationId: Int) {
        val activation = transaction {
            NativeIncidentResponseActivations.selectAll().where {
                NativeIncidentResponseActivations.id eq activationId
            }.single()
        }
        if (activation[NativeIncidentResponseActivations.status] != IncidentResponseActivationStatus.PENDING.wire &&
            activation[NativeIncidentResponseActivations.status] != IncidentResponseActivationStatus.ACTIVE.wire
        ) {
            return
        }
        val incident = transaction {
            OnCallIncidents.selectAll().where {
                OnCallIncidents.id eq activation[NativeIncidentResponseActivations.incidentId]
            }.single()
        }
        val pending = transaction {
            NativeIncidentResponseTargets.selectAll().where {
                (NativeIncidentResponseTargets.activationId eq activationId) and
                    (NativeIncidentResponseTargets.status inList listOf(
                        IncidentResponseTargetStatus.DESIRED.wire,
                        IncidentResponseTargetStatus.RETRYING.wire,
                    ))
            }.toList()
        }
        pending.forEach { target ->
            val claimed = transaction {
                NativeIncidentResponseTargets.update({
                    (NativeIncidentResponseTargets.id eq target[NativeIncidentResponseTargets.id].value) and
                        (NativeIncidentResponseTargets.status inList listOf(
                            IncidentResponseTargetStatus.DESIRED.wire,
                            IncidentResponseTargetStatus.RETRYING.wire,
                        ))
                }) {
                    it[status] = IncidentResponseTargetStatus.ATTEMPTED.wire
                    it[attemptCount] = target[NativeIncidentResponseTargets.attemptCount] + 1
                    it[attemptedAt] = now()
                    it[updatedAt] = now()
                }
            }
            if (claimed == 0) return@forEach
            try {
                val alertId = pager.page(
                    IncidentResponsePageRequest(
                        organizationId = activation[NativeIncidentResponseActivations.organizationId],
                        escalationPolicyId = target[NativeIncidentResponseTargets.escalationPolicyId]
                            ?: error("Response target has no escalation policy"),
                        title = incident[OnCallIncidents.title],
                        severity = incident[OnCallIncidents.severity] ?: "SEV-1",
                        deduplicationKey =
                            "incident:${activation[NativeIncidentResponseActivations.resourceId]}:" +
                                target[NativeIncidentResponseTargets.targetKey],
                        metadata = mapOf(
                            "incidentId" to JsonPrimitive(incident[OnCallIncidents.resourceId].toString()),
                        ),
                    ),
                )
                if (alertId == null) error("Paging provider did not return an alert")
                linkAlert(activation, target, alertId)
            } catch (error: Exception) {
                transaction {
                    NativeIncidentResponseTargets.update({
                        NativeIncidentResponseTargets.id eq target[NativeIncidentResponseTargets.id].value
                    }) {
                        it[status] = IncidentResponseTargetStatus.FAILED.wire
                        it[failedAt] = now()
                        it[lastError] = error.message ?: "Paging failed"
                        it[updatedAt] = now()
                    }
                }
            }
        }
        finalize(activationId)
    }

    private fun linkAlert(
        activation: org.jetbrains.exposed.v1.core.ResultRow,
        target: org.jetbrains.exposed.v1.core.ResultRow,
        alertId: Int,
    ) = transaction {
        OnCallAlerts.update({ OnCallAlerts.id eq alertId }) {
            it[declaredIncidentId] = activation[NativeIncidentResponseActivations.incidentId]
            it[updatedAt] = now()
        }
        OnCallIncidentAlerts.insertIgnore {
            it[incidentId] = activation[NativeIncidentResponseActivations.incidentId]
            it[OnCallIncidentAlerts.alertId] = alertId
        }
        NativeIncidentResponseTargets.update({
            NativeIncidentResponseTargets.id eq target[NativeIncidentResponseTargets.id].value
        }) {
            it[onCallAlertId] = alertId
            it[status] = IncidentResponseTargetStatus.ATTEMPTED.wire
            it[updatedAt] = now()
        }
    }

    private fun finalize(activationId: Int) = transaction {
        val targets = NativeIncidentResponseTargets.selectAll().where {
            NativeIncidentResponseTargets.activationId eq activationId
        }.toList()
        val failed = targets.any {
            it[NativeIncidentResponseTargets.status] == IncidentResponseTargetStatus.FAILED.wire
        }
        val pending = targets.any {
            it[NativeIncidentResponseTargets.status] in listOf(
                IncidentResponseTargetStatus.DESIRED.wire,
                IncidentResponseTargetStatus.RETRYING.wire,
            )
        }
        val skipped = targets.isNotEmpty() && targets.all {
            it[NativeIncidentResponseTargets.status] == IncidentResponseTargetStatus.SKIPPED.wire
        }
        val active = when {
            failed -> IncidentResponseActivationStatus.FAILED
            pending -> IncidentResponseActivationStatus.ACTIVE
            skipped -> IncidentResponseActivationStatus.SKIPPED
            else -> IncidentResponseActivationStatus.COMPLETED
        }
        NativeIncidentResponseActivations.update({ NativeIncidentResponseActivations.id eq activationId }) {
            it[status] = active.wire
            it[attemptedCount] = targets.count { row ->
                row[NativeIncidentResponseTargets.status] in listOf(
                    IncidentResponseTargetStatus.ATTEMPTED.wire,
                    IncidentResponseTargetStatus.ACKNOWLEDGED.wire,
                )
            }
            it[acknowledgedCount] = targets.count { row ->
                row[NativeIncidentResponseTargets.status] == IncidentResponseTargetStatus.ACKNOWLEDGED.wire
            }
            it[lastError] = targets.firstOrNull { row ->
                row[NativeIncidentResponseTargets.status] == IncidentResponseTargetStatus.FAILED.wire
            }?.get(NativeIncidentResponseTargets.lastError)
            it[completedAt] = now().takeIf { active != IncidentResponseActivationStatus.ACTIVE }
            it[updatedAt] = now()
        }
    }

    private fun claimCommander(activationId: Int, userId: Int) = transaction {
        val target = NativeIncidentResponseTargets.selectAll().where {
            (NativeIncidentResponseTargets.activationId eq activationId) and
                NativeIncidentResponseTargets.onCallAlertId.isNotNull()
        }.singleOrNull() ?: return@transaction
        val activation = NativeIncidentResponseActivations.selectAll().where {
            NativeIncidentResponseActivations.id eq activationId
        }.single()
        OnCallIncidents.selectAll().where {
            OnCallIncidents.id eq activation[NativeIncidentResponseActivations.incidentId]
        }
            .forUpdate()
            .single()
        val role = NativeIncidentRoleDefinitions.selectAll().where {
            (NativeIncidentRoleDefinitions.organizationId eq
                activation[NativeIncidentResponseActivations.organizationId]) and
                (NativeIncidentRoleDefinitions.stableKey eq INCIDENT_COMMANDER_ROLE_KEY) and
                (NativeIncidentRoleDefinitions.isCurrent eq true)
        }.singleOrNull() ?: return@transaction
        val existing = NativeIncidentRoleAssignments.selectAll().where {
            (NativeIncidentRoleAssignments.incidentId eq activation[NativeIncidentResponseActivations.incidentId]) and
                (NativeIncidentRoleAssignments.roleDefinitionId eq role[NativeIncidentRoleDefinitions.id].value) and
                NativeIncidentRoleAssignments.endedAt.isNull()
        }.singleOrNull()
        if (existing == null) {
            NativeIncidentRoleAssignments.insertIgnore {
                it[resourceId] = kotlin.uuid.Uuid.random()
                it[organizationId] = activation[NativeIncidentResponseActivations.organizationId]
                it[incidentId] = activation[NativeIncidentResponseActivations.incidentId]
                it[roleDefinitionId] = role[NativeIncidentRoleDefinitions.id].value
                it[assigneeUserId] = userId
                it[assignedBy] = userId
                it[assignedAt] = now()
                it[endedBy] = null
                it[endedAt] = null
                it[endReason] = null
            }
        }
        NativeIncidentResponseTargets.update({
            NativeIncidentResponseTargets.id eq target[NativeIncidentResponseTargets.id].value
        }) {
            it[NativeIncidentResponseTargets.userId] = userId
            it[updatedAt] = now()
        }
    }

    private fun addParticipant(activationId: Int, userId: Int) = transaction {
        val activation = NativeIncidentResponseActivations.selectAll().where {
            NativeIncidentResponseActivations.id eq activationId
        }.single()
        val organizationId = activation[NativeIncidentResponseActivations.organizationId]
        val incidentId = activation[NativeIncidentResponseActivations.incidentId]
        val member = Memberships.selectAll().where {
            (Memberships.organization_id eq organizationId) and (Memberships.user_id eq userId)
        }.limit(1).any()
        if (!member) return@transaction
        val existing = NativeIncidentParticipants.selectAll().where {
            (NativeIncidentParticipants.organizationId eq organizationId) and
                (NativeIncidentParticipants.incidentId eq incidentId) and
                (NativeIncidentParticipants.userId eq userId) and
                NativeIncidentParticipants.leftAt.isNull()
        }.singleOrNull()
        if (existing == null) {
            NativeIncidentParticipants.insertIgnore {
                it[resourceId] = kotlin.uuid.Uuid.random()
                it[NativeIncidentParticipants.organizationId] = organizationId
                it[NativeIncidentParticipants.incidentId] = incidentId
                it[NativeIncidentParticipants.userId] = userId
                it[participationType] = IncidentParticipationType.PARTICIPANT.wire
                it[joinedBy] = userId
                it[joinedAt] = now()
                it[leftBy] = null
                it[leftAt] = null
            }
        }
    }

    private fun routePagedPolicies(organizationId: Int, incidentId: Int): Set<Int> = transaction {
        val direct = OnCallIncidentAlerts
            .innerJoin(OnCallAlerts)
            .selectAll()
            .where {
                (OnCallIncidentAlerts.incidentId eq incidentId) and
                    (OnCallAlerts.organizationId eq organizationId) and
                    (OnCallAlerts.alertSource eq "alert-route")
            }
            .mapNotNull { it[OnCallAlerts.escalationPolicyId] }
        val groups = EnterpriseAlertGroupEscalations
            .innerJoin(EnterpriseAlertGroups)
            .selectAll()
            .where {
                (EnterpriseAlertGroupEscalations.organizationId eq organizationId) and
                    (EnterpriseAlertGroups.organizationId eq organizationId) and
                    (EnterpriseAlertGroups.incidentId eq incidentId)
            }
            .map { it[EnterpriseAlertGroupEscalations.escalationPolicyId] }
        (direct + groups).toSet()
    }

    private fun activationResponse(row: org.jetbrains.exposed.v1.core.ResultRow): IncidentResponseActivationResponse {
        val targets = transaction {
            NativeIncidentResponseTargets.selectAll().where {
                NativeIncidentResponseTargets.activationId eq row[NativeIncidentResponseActivations.id].value
            }.orderBy(NativeIncidentResponseTargets.targetKey).map {
                IncidentResponseTargetResponse(
                    id = it[NativeIncidentResponseTargets.resourceId].toString(),
                    targetKey = it[NativeIncidentResponseTargets.targetKey],
                    targetType = it[NativeIncidentResponseTargets.targetType],
                    status = it[NativeIncidentResponseTargets.status],
                    attemptCount = it[NativeIncidentResponseTargets.attemptCount],
                    onCallAlertId = it[NativeIncidentResponseTargets.onCallAlertId]?.let { id ->
                        OnCallAlerts.selectAll().where { OnCallAlerts.id eq id }.single()[OnCallAlerts.resourceId]
                            .toString()
                    },
                    error = it[NativeIncidentResponseTargets.lastError],
                )
            }
        }
        return IncidentResponseActivationResponse(
            id = row[NativeIncidentResponseActivations.resourceId].toString(),
            revision = row[NativeIncidentResponseActivations.activationRevision],
            trigger = row[NativeIncidentResponseActivations.trigger],
            status = row[NativeIncidentResponseActivations.status],
            desiredCount = row[NativeIncidentResponseActivations.desiredCount],
            attemptedCount = row[NativeIncidentResponseActivations.attemptedCount],
            acknowledgedCount = row[NativeIncidentResponseActivations.acknowledgedCount],
            lastError = row[NativeIncidentResponseActivations.lastError],
            targets = targets,
        )
    }

    private fun requireMember(organizationId: Int, userId: Int) {
        require(
            Memberships.selectAll().where {
                (Memberships.organization_id eq organizationId) and (Memberships.user_id eq userId)
            }.limit(1).any(),
        ) { "Actor is not a member of the incident organization" }
    }

    private fun NativeIncidentDomainEvent.status(): String? = payload["status"]?.jsonPrimitive?.contentOrNull
}
