// Moneat Enterprise - proprietary module
// Copyright (c) 2026 Moneat. All rights reserved.
// See ee/LICENSE for license terms.

package com.moneat.enterprise.oncall.services

import com.moneat.enterprise.incidents.timeline.IncidentAlertTimelineBridge
import com.moneat.enterprise.oncall.models.EscalationExecutionEvents
import com.moneat.enterprise.oncall.models.EscalationExecutionStates
import com.moneat.enterprise.oncall.models.EscalationPath
import com.moneat.enterprise.oncall.models.EscalationPathTargetType
import com.moneat.enterprise.oncall.models.EscalationPathValidator
import com.moneat.enterprise.oncall.models.EscalationPolicyVersions
import com.moneat.shared.models.EscalationPolicies
import com.moneat.shared.models.Memberships
import com.moneat.shared.models.OrganizationTeams
import com.moneat.shared.models.OrganizationTeamMembers
import com.moneat.shared.models.OnCallSchedules
import com.moneat.shared.models.Users
import com.moneat.shared.services.toUuidOrNull
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.buildJsonObject
import kotlinx.serialization.json.jsonObject
import kotlinx.serialization.json.put
import kotlinx.serialization.SerialName
import org.jetbrains.exposed.v1.core.and
import org.jetbrains.exposed.v1.core.eq
import org.jetbrains.exposed.v1.jdbc.insertAndGetId
import org.jetbrains.exposed.v1.jdbc.selectAll
import org.jetbrains.exposed.v1.jdbc.transactions.transaction
import org.jetbrains.exposed.v1.jdbc.update
import kotlin.time.Clock

data class EscalationPolicyVersion(
    val id: String,
    @SerialName("policyId") val policyResourceId: String,
    val version: Int,
    val status: String,
    val path: EscalationPath,
    val createdAt: String,
    val publishedAt: String?,
    val internalId: Int = 0,
)

data class EscalationExecution(
    val id: Int,
    val resourceId: String,
    val status: String,
    val currentNodeId: String?,
    val transitionCount: Int,
)

data class EscalationExecutionEvent(
    val type: String,
    val actorUserId: Int? = null,
    val nodeId: String? = null,
    val details: Map<String, kotlinx.serialization.json.JsonElement> = emptyMap(),
)

data class EscalationExecutionTransition(
    val status: String,
    val nextNodeId: String?,
    val event: EscalationExecutionEvent,
)

class EscalationPathService {
    private val incidentAlertTimelineBridge = IncidentAlertTimelineBridge()

    companion object {
        const val STATUS_DRAFT = "DRAFT"
        const val STATUS_PUBLISHED = "PUBLISHED"
        const val STATUS_ARCHIVED = "ARCHIVED"
        const val EXECUTION_ACTIVE = "ACTIVE"
        const val EXECUTION_ACKNOWLEDGED = "ACKNOWLEDGED"
        const val EXECUTION_STOPPED = "STOPPED"
        const val EXECUTION_COMPLETED = "COMPLETED"
        const val EVENT_STARTED = "STARTED"
        const val EVENT_NODE_ENTERED = "NODE_ENTERED"
        const val EVENT_ACKNOWLEDGED = "ACKNOWLEDGED"
        const val EVENT_REASSIGNED = "REASSIGNED"
        const val EVENT_NEXT_LEVEL = "NEXT_LEVEL"
        const val EVENT_STOPPED = "STOPPED"
        const val EVENT_UNAVAILABLE = "UNAVAILABLE"
        const val EVENT_RETRYING = "RETRYING"
        const val EVENT_RECOVERED = "RECOVERED"
    }

    fun createDraft(
        organizationId: Int,
        policyId: Int,
        path: EscalationPath,
        createdBy: Int?,
    ): EscalationPolicyVersion = transaction {
        val normalized = EscalationPathValidator.validate(path)
        validateTargets(organizationId, policyId, normalized)
        validateNestedPolicyCycles(organizationId, normalized, setOf(policyId))
        val nextVersion =
            (EscalationPolicyVersions.selectAll()
                .where { EscalationPolicyVersions.escalationPolicyId eq policyId }
                .map { it[EscalationPolicyVersions.version] }
                .maxOrNull() ?: 0) + 1
        val now = Clock.System.now()
        val versionId = EscalationPolicyVersions.insertAndGetId {
            it[EscalationPolicyVersions.organizationId] = organizationId
            it[escalationPolicyId] = policyId
            it[version] = nextVersion
            it[status] = STATUS_DRAFT
            it[EscalationPolicyVersions.path] = encode(normalized)
            it[EscalationPolicyVersions.createdBy] = createdBy
            it[createdAt] = now
        }.value
        getVersionById(versionId)!!
    }

    fun listVersions(organizationId: Int, policyId: Int): List<EscalationPolicyVersion> = transaction {
        EscalationPolicyVersions.selectAll()
            .where {
                (EscalationPolicyVersions.organizationId eq organizationId) and
                    (EscalationPolicyVersions.escalationPolicyId eq policyId)
            }
            .orderBy(EscalationPolicyVersions.version)
            .map(::toVersion)
    }

    fun getVersion(organizationId: Int, resourceId: String): EscalationPolicyVersion? = transaction {
        val uuid = resourceId.toUuidOrNull() ?: return@transaction null
        EscalationPolicyVersions.selectAll()
            .where {
                (EscalationPolicyVersions.organizationId eq organizationId) and
                    (EscalationPolicyVersions.resourceId eq uuid)
            }
            .singleOrNull()
            ?.let(::toVersion)
    }

    fun getPublishedVersion(policyId: Int): EscalationPolicyVersion? = transaction {
        EscalationPolicyVersions.selectAll()
            .where {
                (EscalationPolicyVersions.escalationPolicyId eq policyId) and
                    (EscalationPolicyVersions.status eq STATUS_PUBLISHED)
            }
            .singleOrNull()
            ?.let(::toVersion)
    }

    fun publishVersion(organizationId: Int, versionId: Int): EscalationPolicyVersion? = transaction {
        val row = EscalationPolicyVersions.selectAll()
            .where {
                (EscalationPolicyVersions.id eq versionId) and
                    (EscalationPolicyVersions.organizationId eq organizationId)
            }
            .singleOrNull() ?: return@transaction null
        require(row[EscalationPolicyVersions.status] == STATUS_DRAFT) {
            "Only draft escalation path versions can be published"
        }
        val policyId = row[EscalationPolicyVersions.escalationPolicyId]
        val now = Clock.System.now()
        EscalationPolicyVersions.update({
            (EscalationPolicyVersions.escalationPolicyId eq policyId) and
                (EscalationPolicyVersions.status eq STATUS_PUBLISHED)
        }) {
            it[status] = STATUS_ARCHIVED
        }
        EscalationPolicyVersions.update({ EscalationPolicyVersions.id eq versionId }) {
            it[status] = STATUS_PUBLISHED
            it[publishedAt] = now
        }
        getVersionById(versionId)
    }

    fun publishVersion(organizationId: Int, resourceId: String): EscalationPolicyVersion? = transaction {
        val uuid = resourceId.toUuidOrNull() ?: return@transaction null
        val versionId = EscalationPolicyVersions.selectAll()
            .where {
                (EscalationPolicyVersions.organizationId eq organizationId) and
                    (EscalationPolicyVersions.resourceId eq uuid)
            }
            .singleOrNull()
            ?.get(EscalationPolicyVersions.id)
            ?.value
            ?: return@transaction null
        publishVersion(organizationId, versionId)
    }

    fun createExecution(
        organizationId: Int,
        alertId: Int,
        policyVersionId: Int,
        startNodeId: String?,
    ): EscalationExecution = transaction {
        val now = Clock.System.now()
        val executionId = EscalationExecutionStates.insertAndGetId {
            it[EscalationExecutionStates.organizationId] = organizationId
            it[EscalationExecutionStates.alertId] = alertId
            it[EscalationExecutionStates.policyVersionId] = policyVersionId
            it[currentNodeId] = startNodeId
            it[status] = EXECUTION_ACTIVE
            it[updatedAt] = now
        }.value
        appendEventInTransaction(
            organizationId,
            executionId,
            EscalationExecutionEvent(
                type = EVENT_STARTED,
                nodeId = startNodeId,
                details = buildJsonObject { put("policyVersionId", policyVersionId) },
            ),
        )
        getExecutionInTransaction(executionId)!!
    }

    fun appendEvent(
        organizationId: Int,
        executionId: Int,
        event: EscalationExecutionEvent,
    ) = transaction {
        appendEventInTransaction(organizationId, executionId, event)
    }

    fun appendEventForAlert(alertId: Int, event: EscalationExecutionEvent): Boolean = transaction {
        val execution = EscalationExecutionStates.selectAll()
            .where { EscalationExecutionStates.alertId eq alertId }
            .singleOrNull()
            ?: return@transaction false
        appendEventInTransaction(
            execution[EscalationExecutionStates.organizationId],
            execution[EscalationExecutionStates.id].value,
            event,
        )
        true
    }

    fun transition(
        organizationId: Int,
        executionId: Int,
        transition: EscalationExecutionTransition,
    ): EscalationExecution? = transaction {
        val current = EscalationExecutionStates.selectAll()
            .where {
                (EscalationExecutionStates.id eq executionId) and
                    (EscalationExecutionStates.organizationId eq organizationId)
            }
            .singleOrNull()
            ?: return@transaction null
        val updated = EscalationExecutionStates.update({
            (EscalationExecutionStates.id eq executionId) and
                (EscalationExecutionStates.organizationId eq organizationId)
        }) {
            it[currentNodeId] = transition.nextNodeId
            it[EscalationExecutionStates.transitionCount] = current[EscalationExecutionStates.transitionCount] + 1
            it[EscalationExecutionStates.status] = transition.status
            it[updatedAt] = Clock.System.now()
        }
        if (updated == 0) return@transaction null
        appendEventInTransaction(organizationId, executionId, transition.event)
        getExecutionInTransaction(executionId)
    }

    private fun appendEventInTransaction(
        organizationId: Int,
        executionId: Int,
        event: EscalationExecutionEvent,
    ) {
        val eventId = EscalationExecutionEvents.insertAndGetId {
            it[EscalationExecutionEvents.organizationId] = organizationId
            it[EscalationExecutionEvents.executionId] = executionId
            it[EscalationExecutionEvents.eventType] = event.type
            it[EscalationExecutionEvents.actorUserId] = event.actorUserId
            it[EscalationExecutionEvents.nodeId] = event.nodeId
            it[EscalationExecutionEvents.details] = event.details
            it[createdAt] = Clock.System.now()
        }.value
        incidentAlertTimelineBridge.recordForEscalationEvent(eventId)
    }

    private fun getExecutionInTransaction(executionId: Int): EscalationExecution? =
        EscalationExecutionStates.selectAll()
            .where { EscalationExecutionStates.id eq executionId }
            .singleOrNull()
            ?.let {
                EscalationExecution(
                    id = it[EscalationExecutionStates.id].value,
                    resourceId = it[EscalationExecutionStates.resourceId].toString(),
                    status = it[EscalationExecutionStates.status],
                    currentNodeId = it[EscalationExecutionStates.currentNodeId],
                    transitionCount = it[EscalationExecutionStates.transitionCount],
                )
            }

    private fun getVersionById(versionId: Int): EscalationPolicyVersion? =
        EscalationPolicyVersions.selectAll()
            .where { EscalationPolicyVersions.id eq versionId }
            .singleOrNull()
            ?.let(::toVersion)

    private fun toVersion(row: org.jetbrains.exposed.v1.core.ResultRow): EscalationPolicyVersion {
        val path = Json.decodeFromJsonElement(
            EscalationPath.serializer(),
            JsonObject(row[EscalationPolicyVersions.path]),
        )
        return EscalationPolicyVersion(
            id = row[EscalationPolicyVersions.resourceId].toString(),
            policyResourceId = EscalationPolicies.selectAll()
                .where { EscalationPolicies.id eq row[EscalationPolicyVersions.escalationPolicyId] }
                .single()[EscalationPolicies.resourceId]
                .toString(),
            version = row[EscalationPolicyVersions.version],
            status = row[EscalationPolicyVersions.status],
            path = path,
            createdAt = row[EscalationPolicyVersions.createdAt].toString(),
            publishedAt = row[EscalationPolicyVersions.publishedAt]?.toString(),
            internalId = row[EscalationPolicyVersions.id].value,
        )
    }

    private fun encode(path: EscalationPath): Map<String, kotlinx.serialization.json.JsonElement> =
        Json.encodeToJsonElement(EscalationPath.serializer(), path).jsonObject.toMap()

    private fun validateTargets(organizationId: Int, policyId: Int, path: EscalationPath) {
        path.nodes.flatMap { it.targets }.forEach { target ->
            if (target.targetType == EscalationPathTargetType.SLACK_CHANNEL) {
                requireTarget(
                    OrganizationTeams.selectAll().where {
                        (OrganizationTeams.organizationId eq organizationId) and
                            (OrganizationTeams.slackChannel eq target.targetResourceId)
                    }.count() > 0,
                    "Slack channel target is not authorized for this organization",
                )
                return@forEach
            }
            val resourceId = target.targetResourceId.toUuidOrNull()
                ?: throw IllegalArgumentException("Invalid ${target.targetType} target ID")
            when (target.targetType) {
                EscalationPathTargetType.USER -> requireTarget(
                    Users.innerJoin(Memberships).selectAll().where {
                        (Users.resource_id eq resourceId) and (Memberships.organization_id eq organizationId)
                    }.count() > 0,
                    "User target not found",
                )
                EscalationPathTargetType.ON_CALL_SCHEDULE -> requireTarget(
                    OnCallSchedules.selectAll().where {
                        (OnCallSchedules.organizationId eq organizationId) and
                            (OnCallSchedules.resourceId eq resourceId)
                    }.count() > 0,
                    "On-call schedule target not found",
                )
                EscalationPathTargetType.TEAM -> requireTarget(
                    OrganizationTeams.selectAll().where {
                        (OrganizationTeams.organizationId eq organizationId) and
                            (OrganizationTeams.resourceId eq resourceId)
                    }.count() > 0,
                    "Team target not found",
                ).also {
                    val teamId = OrganizationTeams.selectAll().where {
                        (OrganizationTeams.organizationId eq organizationId) and
                            (OrganizationTeams.resourceId eq resourceId)
                    }.singleOrNull()?.get(OrganizationTeams.id)?.value
                    requireTarget(
                        teamId != null && OrganizationTeamMembers.selectAll()
                            .where { OrganizationTeamMembers.teamId eq teamId }
                            .count() > 0,
                        "Team target has no members",
                    )
                }
                EscalationPathTargetType.ESCALATION_POLICY -> {
                    val nestedPolicyId = EscalationPolicies.selectAll().where {
                        (EscalationPolicies.organizationId eq organizationId) and
                            (EscalationPolicies.resourceId eq resourceId)
                    }.singleOrNull()?.get(EscalationPolicies.id)?.value
                    requireTarget(
                        nestedPolicyId != null && nestedPolicyId != policyId,
                        "Nested policy target is invalid",
                    )
                }
                else -> throw IllegalArgumentException("Unsupported escalation target type: ${target.targetType}")
            }
        }
    }

    private fun requireTarget(condition: Boolean, message: String) {
        require(condition) { message }
    }

    private fun validateNestedPolicyCycles(
        organizationId: Int,
        path: EscalationPath,
        visitedPolicies: Set<Int>,
    ) {
        path.nodes.flatMap { it.targets }
            .filter { it.targetType == EscalationPathTargetType.ESCALATION_POLICY }
            .forEach { target ->
                val resourceId = target.targetResourceId.toUuidOrNull() ?: return@forEach
                val nestedPolicyId = EscalationPolicies.selectAll().where {
                    (EscalationPolicies.organizationId eq organizationId) and
                        (EscalationPolicies.resourceId eq resourceId)
                }.singleOrNull()?.get(EscalationPolicies.id)?.value ?: return@forEach
                require(nestedPolicyId !in visitedPolicies) { "Nested escalation policy cycle detected" }
                val published = EscalationPolicyVersions.selectAll().where {
                    (EscalationPolicyVersions.organizationId eq organizationId) and
                        (EscalationPolicyVersions.escalationPolicyId eq nestedPolicyId) and
                        (EscalationPolicyVersions.status eq STATUS_PUBLISHED)
                }.singleOrNull() ?: return@forEach
                val nestedPath = Json.decodeFromJsonElement(
                    EscalationPath.serializer(),
                    JsonObject(published[EscalationPolicyVersions.path]),
                )
                validateNestedPolicyCycles(
                    organizationId,
                    nestedPath,
                    visitedPolicies + nestedPolicyId,
                )
            }
    }
}
