// Moneat Enterprise - proprietary module
// Copyright (c) 2026 Moneat. All rights reserved.
// See ee/LICENSE for license terms.

package com.moneat.enterprise.oncall.services

import com.moneat.enterprise.oncall.organizationResourceId
import com.moneat.enterprise.oncall.requireValue
import com.moneat.enterprise.oncall.scheduleResourceIds
import com.moneat.enterprise.oncall.userResourceIds
import com.moneat.enterprise.oncall.models.EscalationPolicy
import com.moneat.enterprise.oncall.models.EscalationStep
import com.moneat.enterprise.oncall.models.EscalationStepTarget
import com.moneat.enterprise.oncall.models.EscalationStepTargets
import com.moneat.enterprise.oncall.models.EscalationSteps
import com.moneat.shared.models.EscalationPolicies
import com.moneat.shared.models.OnCallSchedules
import com.moneat.shared.models.Users
import com.moneat.shared.services.resolveScopedIntResourceId
import com.moneat.shared.services.toUuidOrNull
import org.jetbrains.exposed.v1.core.ResultRow
import org.jetbrains.exposed.v1.core.SortOrder
import org.jetbrains.exposed.v1.core.eq
import org.jetbrains.exposed.v1.core.inList
import org.jetbrains.exposed.v1.jdbc.deleteWhere
import org.jetbrains.exposed.v1.jdbc.insert
import org.jetbrains.exposed.v1.jdbc.insertAndGetId
import org.jetbrains.exposed.v1.jdbc.selectAll
import org.jetbrains.exposed.v1.jdbc.transactions.transaction
import org.jetbrains.exposed.v1.jdbc.update
import kotlin.time.Clock
import kotlin.uuid.Uuid

private const val TARGET_TYPE_USER = "USER"
private const val TARGET_TYPE_ON_CALL_SCHEDULE = "ON_CALL_SCHEDULE"

private data class EscalationTargetLookups(
    val userTargetResourceIds: Map<Int, String>,
    val scheduleTargetResourceIds: Map<Int, String>,
    val users: Map<Int, String?>,
    val schedules: Map<Int, String>,
)

class EscalationPolicyService {
    fun resolveEscalationPolicyId(
        organizationId: Int,
        policyResourceId: String
    ): Int? =
        parseResourceId(policyResourceId)?.let { resourceId ->
            resolveScopedIntResourceId(
                table = EscalationPolicies,
                resourceIdColumn = EscalationPolicies.resourceId,
                scopeColumn = EscalationPolicies.organizationId,
                scopeId = organizationId,
                resourceId = resourceId,
            )
        }

    fun getPolicy(policyId: Int): EscalationPolicy? =
        transaction {
            val policyRow =
                EscalationPolicies
                    .selectAll()
                    .where { EscalationPolicies.id eq policyId }
                    .singleOrNull() ?: return@transaction null

            val steps =
                EscalationSteps
                    .selectAll()
                    .where { EscalationSteps.escalationPolicyId eq policyId }
                    .orderBy(EscalationSteps.stepOrder to SortOrder.ASC)
                    .map { stepRow ->
                        val stepId = stepRow[EscalationSteps.id].value
                        val targets = getStepTargets(stepId)

                        EscalationStep(
                            id = stepRow[EscalationSteps.resourceId].toString(),
                            stepOrder = stepRow[EscalationSteps.stepOrder],
                            timeoutMinutes = stepRow[EscalationSteps.timeoutMinutes],
                            smsFallbackDelayMinutes = stepRow[EscalationSteps.smsFallbackDelayMinutes],
                            targets = targets,
                            createdAt = stepRow[EscalationSteps.createdAt].toString(),
                            internalId = stepId,
                        )
                    }

            EscalationPolicy(
                id = policyRow[EscalationPolicies.resourceId].toString(),
                organizationResourceId = organizationResourceId(policyRow[EscalationPolicies.organizationId]),
                name = policyRow[EscalationPolicies.name],
                description = policyRow[EscalationPolicies.description],
                repeatCount = policyRow[EscalationPolicies.repeatCount],
                steps = steps,
                createdAt = policyRow[EscalationPolicies.createdAt].toString(),
                updatedAt = policyRow[EscalationPolicies.updatedAt].toString(),
                internalId = policyRow[EscalationPolicies.id].value,
                organizationId = policyRow[EscalationPolicies.organizationId],
            )
        }

    private fun parseResourceId(resourceId: String): Uuid? =
        resourceId.toUuidOrNull()

    private fun getStepTargets(stepId: Int): List<EscalationStepTarget> =
        transaction {
            val rows = EscalationStepTargets
                .selectAll()
                .where { EscalationStepTargets.escalationStepId eq stepId }
                .toList()

            val userIds = rows
                .filter { it[EscalationStepTargets.targetType] == TARGET_TYPE_USER }
                .map { it[EscalationStepTargets.targetId] }
                .distinct()
            val scheduleIds = rows
                .filter { it[EscalationStepTargets.targetType] == TARGET_TYPE_ON_CALL_SCHEDULE }
                .map { it[EscalationStepTargets.targetId] }
                .distinct()
            val userTargetResourceIds = userResourceIds(userIds)
            val scheduleTargetResourceIds = scheduleResourceIds(scheduleIds)
            val userNames =
                if (userIds.isEmpty()) {
                    emptyMap()
                } else {
                    Users
                        .selectAll()
                        .where { Users.id inList userIds }
                        .associate { it[Users.id] to it[Users.name] }
                }
            val scheduleNames =
                if (scheduleIds.isEmpty()) {
                    emptyMap()
                } else {
                    OnCallSchedules
                        .selectAll()
                        .where { OnCallSchedules.id inList scheduleIds }
                        .associate { it[OnCallSchedules.id].value to it[OnCallSchedules.name] }
                }

            rows.map { row ->
                val targetType = row[EscalationStepTargets.targetType]
                val targetId = row[EscalationStepTargets.targetId]

                val targetName =
                    when (targetType) {
                        TARGET_TYPE_USER -> userNames[targetId]
                        TARGET_TYPE_ON_CALL_SCHEDULE -> scheduleNames[targetId]
                        else -> null
                    }
                val targetResourceId =
                    when (targetType) {
                        TARGET_TYPE_USER -> userTargetResourceIds.requireValue(targetId, "user")
                        TARGET_TYPE_ON_CALL_SCHEDULE -> {
                            scheduleTargetResourceIds.requireValue(targetId, "on-call schedule")
                        }

                        else -> error("Unsupported escalation target type $targetType for internal id $targetId")
                    }

                EscalationStepTarget(
                    id = row[EscalationStepTargets.resourceId].toString(),
                    targetType = targetType,
                    targetResourceId = targetResourceId,
                    targetName = targetName,
                    internalId = row[EscalationStepTargets.id].value,
                    targetId = targetId,
                )
            }
        }

    fun listPolicies(organizationId: Int): List<EscalationPolicy> =
        transaction {
            val policies =
                EscalationPolicies
                    .selectAll()
                    .where { EscalationPolicies.organizationId eq organizationId }
                    .orderBy(EscalationPolicies.name to SortOrder.ASC)
                    .toList()

            if (policies.isEmpty()) return@transaction emptyList()

            val policyIds = policies.map { it[EscalationPolicies.id].value }
            val allSteps =
                EscalationSteps
                    .selectAll()
                    .where { EscalationSteps.escalationPolicyId inList policyIds }
                    .orderBy(EscalationSteps.stepOrder to SortOrder.ASC)
                    .toList()

            val stepIds = allSteps.map { it[EscalationSteps.id].value }
            val allTargets =
                if (stepIds.isNotEmpty()) {
                    EscalationStepTargets
                        .selectAll()
                        .where { EscalationStepTargets.escalationStepId inList stepIds }
                        .toList()
                } else {
                    emptyList()
                }

            val orgResourceId = organizationResourceId(organizationId)
            val targetLookups = loadTargetLookups(allTargets)
            val targetsByStepId = allTargets.groupBy { it[EscalationStepTargets.escalationStepId] }
            val stepsByPolicyId = allSteps.groupBy { it[EscalationSteps.escalationPolicyId] }

            policies.map { policyRow ->
                policyRow.toEscalationPolicy(orgResourceId, stepsByPolicyId, targetsByStepId, targetLookups)
            }
        }

    private fun loadTargetLookups(targets: List<ResultRow>): EscalationTargetLookups {
        val userIds = targetIdsByType(targets, TARGET_TYPE_USER)
        val scheduleIds = targetIdsByType(targets, TARGET_TYPE_ON_CALL_SCHEDULE)
        return EscalationTargetLookups(
            userTargetResourceIds = userResourceIds(userIds),
            scheduleTargetResourceIds = scheduleResourceIds(scheduleIds),
            users = namesByUserId(userIds),
            schedules = namesByScheduleId(scheduleIds),
        )
    }

    private fun targetIdsByType(targets: List<ResultRow>, targetType: String): List<Int> =
        targets
            .filter { it[EscalationStepTargets.targetType] == targetType }
            .map { it[EscalationStepTargets.targetId] }
            .distinct()

    private fun namesByUserId(userIds: List<Int>): Map<Int, String?> =
        if (userIds.isEmpty()) {
            emptyMap()
        } else {
            Users
                .selectAll()
                .where { Users.id inList userIds }
                .associate { it[Users.id] to it[Users.name] }
        }

    private fun namesByScheduleId(scheduleIds: List<Int>): Map<Int, String> =
        if (scheduleIds.isEmpty()) {
            emptyMap()
        } else {
            OnCallSchedules
                .selectAll()
                .where { OnCallSchedules.id inList scheduleIds }
                .associate { it[OnCallSchedules.id].value to it[OnCallSchedules.name] }
        }

    private fun ResultRow.toEscalationPolicy(
        orgResourceId: String,
        stepsByPolicyId: Map<Int, List<ResultRow>>,
        targetsByStepId: Map<Int, List<ResultRow>>,
        targetLookups: EscalationTargetLookups,
    ): EscalationPolicy {
        val policyId = this[EscalationPolicies.id].value
        return EscalationPolicy(
            id = this[EscalationPolicies.resourceId].toString(),
            organizationResourceId = orgResourceId,
            name = this[EscalationPolicies.name],
            description = this[EscalationPolicies.description],
            repeatCount = this[EscalationPolicies.repeatCount],
            steps = stepsByPolicyId[policyId].toEscalationSteps(targetsByStepId, targetLookups),
            createdAt = this[EscalationPolicies.createdAt].toString(),
            updatedAt = this[EscalationPolicies.updatedAt].toString(),
            internalId = policyId,
            organizationId = this[EscalationPolicies.organizationId],
        )
    }

    private fun List<ResultRow>?.toEscalationSteps(
        targetsByStepId: Map<Int, List<ResultRow>>,
        targetLookups: EscalationTargetLookups,
    ): List<EscalationStep> =
        this?.map { stepRow ->
            val stepId = stepRow[EscalationSteps.id].value
            EscalationStep(
                id = stepRow[EscalationSteps.resourceId].toString(),
                stepOrder = stepRow[EscalationSteps.stepOrder],
                timeoutMinutes = stepRow[EscalationSteps.timeoutMinutes],
                smsFallbackDelayMinutes = stepRow[EscalationSteps.smsFallbackDelayMinutes],
                targets = targetsByStepId[stepId].toEscalationStepTargets(targetLookups),
                createdAt = stepRow[EscalationSteps.createdAt].toString(),
                internalId = stepId,
            )
        } ?: emptyList()

    private fun List<ResultRow>?.toEscalationStepTargets(
        targetLookups: EscalationTargetLookups,
    ): List<EscalationStepTarget> =
        this?.map { it.toEscalationStepTarget(targetLookups) } ?: emptyList()

    private fun ResultRow.toEscalationStepTarget(lookups: EscalationTargetLookups): EscalationStepTarget {
        val targetType = this[EscalationStepTargets.targetType]
        val targetId = this[EscalationStepTargets.targetId]
        return EscalationStepTarget(
            id = this[EscalationStepTargets.resourceId].toString(),
            targetType = targetType,
            targetResourceId = targetResourceId(targetType, targetId, lookups),
            targetName = targetName(targetType, targetId, lookups),
            internalId = this[EscalationStepTargets.id].value,
            targetId = targetId,
        )
    }

    private fun targetName(targetType: String, targetId: Int, lookups: EscalationTargetLookups): String? =
        when (targetType) {
            TARGET_TYPE_USER -> lookups.users[targetId]
            TARGET_TYPE_ON_CALL_SCHEDULE -> lookups.schedules[targetId]
            else -> null
        }

    private fun targetResourceId(targetType: String, targetId: Int, lookups: EscalationTargetLookups): String =
        when (targetType) {
            TARGET_TYPE_USER -> lookups.userTargetResourceIds.requireValue(targetId, "user")
            TARGET_TYPE_ON_CALL_SCHEDULE -> lookups.scheduleTargetResourceIds.requireValue(targetId, "on-call schedule")
            else -> error("Unsupported escalation target type $targetType for internal id $targetId")
        }

    data class CreateStepData(
        val stepOrder: Int,
        val timeoutMinutes: Int,
        val smsFallbackDelayMinutes: Int = 2,
        val targets: List<CreateTargetData>,
    )

    data class CreateTargetData(
        val targetType: String,
        val targetId: Int,
    )

    fun createPolicy(
        organizationId: Int,
        name: String,
        description: String?,
        repeatCount: Int,
        steps: List<CreateStepData>,
    ): EscalationPolicy =
        transaction {
            val now = Clock.System.now()

            val policyId =
                EscalationPolicies
                    .insertAndGetId {
                        it[EscalationPolicies.organizationId] = organizationId
                        it[EscalationPolicies.name] = name
                        it[EscalationPolicies.description] = description
                        it[EscalationPolicies.repeatCount] = repeatCount
                        it[createdAt] = now
                        it[updatedAt] = now
                    }.value

            steps.forEach { step ->
                val stepId =
                    EscalationSteps
                        .insertAndGetId {
                            it[escalationPolicyId] = policyId
                            it[stepOrder] = step.stepOrder
                            it[timeoutMinutes] = step.timeoutMinutes
                            it[smsFallbackDelayMinutes] = step.smsFallbackDelayMinutes
                            it[createdAt] = now
                        }.value

                step.targets.forEach { target ->
                    EscalationStepTargets.insert {
                        it[escalationStepId] = stepId
                        it[targetType] = target.targetType
                        it[targetId] = target.targetId
                        it[createdAt] = now
                    }
                }
            }

            getPolicy(policyId)!!
        }

    fun updatePolicy(
        policyId: Int,
        name: String? = null,
        description: String? = null,
        repeatCount: Int? = null,
        steps: List<CreateStepData>? = null,
    ): EscalationPolicy? =
        transaction {
            val now = Clock.System.now()

            EscalationPolicies.update({ EscalationPolicies.id eq policyId }) {
                if (name != null) it[EscalationPolicies.name] = name
                if (description != null) it[EscalationPolicies.description] = description
                if (repeatCount != null) it[EscalationPolicies.repeatCount] = repeatCount
                it[updatedAt] = now
            }

            if (steps != null) {
                // Delete existing steps and targets
                val existingStepIds =
                    EscalationSteps
                        .selectAll()
                        .where { EscalationSteps.escalationPolicyId eq policyId }
                        .map { it[EscalationSteps.id].value }

                if (existingStepIds.isNotEmpty()) {
                    EscalationStepTargets.deleteWhere { EscalationStepTargets.escalationStepId inList existingStepIds }
                }
                EscalationSteps.deleteWhere { escalationPolicyId eq policyId }

                // Insert new steps
                steps.forEach { step ->
                    val stepId =
                        EscalationSteps
                            .insertAndGetId {
                                it[escalationPolicyId] = policyId
                                it[stepOrder] = step.stepOrder
                                it[timeoutMinutes] = step.timeoutMinutes
                                it[smsFallbackDelayMinutes] = step.smsFallbackDelayMinutes
                                it[createdAt] = now
                            }.value

                    step.targets.forEach { target ->
                        EscalationStepTargets.insert {
                            it[escalationStepId] = stepId
                            it[targetType] = target.targetType
                            it[targetId] = target.targetId
                            it[createdAt] = now
                        }
                    }
                }
            }

            getPolicy(policyId)
        }

    fun deletePolicy(policyId: Int): Boolean =
        transaction {
            EscalationPolicies.deleteWhere { id eq policyId } > 0
        }
}
