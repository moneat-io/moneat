// Moneat Enterprise - proprietary module
// Copyright (c) 2026 Moneat. All rights reserved.
// See enterprise/LICENSE for license terms.

package com.moneat.enterprise.services.oncall

import com.moneat.models.*
import com.moneat.enterprise.models.*
import kotlinx.datetime.Clock
import org.jetbrains.exposed.sql.*
import org.jetbrains.exposed.sql.SqlExpressionBuilder.eq
import org.jetbrains.exposed.sql.SqlExpressionBuilder.inList
import org.jetbrains.exposed.sql.transactions.transaction
import org.jetbrains.exposed.sql.selectAll
import org.jetbrains.exposed.sql.insert
import org.jetbrains.exposed.sql.and

class EscalationPolicyService {
    
    fun getPolicy(policyId: Int): EscalationPolicy? = transaction {
        val policyRow = EscalationPolicies
            .selectAll()
            .where { EscalationPolicies.id eq policyId }
            .singleOrNull() ?: return@transaction null
        
        val steps = EscalationSteps
            .selectAll()
            .where { EscalationSteps.escalationPolicyId eq policyId }
            .orderBy(EscalationSteps.stepOrder to SortOrder.ASC)
            .map { stepRow ->
                val stepId = stepRow[EscalationSteps.id].value
                val targets = getStepTargets(stepId)
                
                EscalationStep(
                    id = stepId,
                    stepOrder = stepRow[EscalationSteps.stepOrder],
                    timeoutMinutes = stepRow[EscalationSteps.timeoutMinutes],
                    smsFallbackDelayMinutes = stepRow[EscalationSteps.smsFallbackDelayMinutes],
                    targets = targets,
                    createdAt = stepRow[EscalationSteps.createdAt].toString()
                )
            }
        
        EscalationPolicy(
            id = policyRow[EscalationPolicies.id].value,
            organizationId = policyRow[EscalationPolicies.organizationId],
            name = policyRow[EscalationPolicies.name],
            description = policyRow[EscalationPolicies.description],
            repeatCount = policyRow[EscalationPolicies.repeatCount],
            steps = steps,
            createdAt = policyRow[EscalationPolicies.createdAt].toString(),
            updatedAt = policyRow[EscalationPolicies.updatedAt].toString()
        )
    }
    
    private fun getStepTargets(stepId: Int): List<EscalationStepTarget> = transaction {
        EscalationStepTargets
            .selectAll()
            .where { EscalationStepTargets.escalationStepId eq stepId }
            .map { row ->
                val targetType = row[EscalationStepTargets.targetType]
                val targetId = row[EscalationStepTargets.targetId]
                
                val targetName = when (targetType) {
                    "USER" -> {
                        Users.selectAll()
                            .where { Users.id eq targetId }
                            .singleOrNull()
                            ?.get(Users.name)
                    }
                    "ON_CALL_SCHEDULE" -> {
                        OnCallSchedules.selectAll()
                            .where { OnCallSchedules.id eq targetId }
                            .singleOrNull()
                            ?.get(OnCallSchedules.name)
                    }
                    else -> null
                }
                
                EscalationStepTarget(
                    id = row[EscalationStepTargets.id].value,
                    targetType = targetType,
                    targetId = targetId,
                    targetName = targetName
                )
            }
    }
    
    fun listPolicies(organizationId: Int): List<EscalationPolicy> = transaction {
        // Fetch all policies for this organization
        val policies = EscalationPolicies
            .selectAll()
            .where { EscalationPolicies.organizationId eq organizationId }
            .orderBy(EscalationPolicies.name to SortOrder.ASC)
            .toList()
        
        if (policies.isEmpty()) return@transaction emptyList()
        
        val policyIds = policies.map { it[EscalationPolicies.id].value }
        
        // Fetch all steps for these policies in one query
        val allSteps = EscalationSteps
            .selectAll()
            .where { EscalationSteps.escalationPolicyId inList policyIds }
            .orderBy(EscalationSteps.stepOrder to SortOrder.ASC)
            .toList()
        
        val stepIds = allSteps.map { it[EscalationSteps.id].value }
        
        // Fetch all targets for these steps in one query
        val allTargets = if (stepIds.isNotEmpty()) {
            EscalationStepTargets
                .selectAll()
                .where { EscalationStepTargets.escalationStepId inList stepIds }
                .toList()
        } else emptyList()
        
        // Get all unique user and schedule IDs from targets
        val userIds = allTargets
            .filter { it[EscalationStepTargets.targetType] == "USER" }
            .map { it[EscalationStepTargets.targetId] }
            .distinct()
        
        val scheduleIds = allTargets
            .filter { it[EscalationStepTargets.targetType] == "ON_CALL_SCHEDULE" }
            .map { it[EscalationStepTargets.targetId] }
            .distinct()
        
        // Fetch all users and schedules in bulk
        val users: Map<Int, String?> = if (userIds.isNotEmpty()) {
            Users.selectAll()
                .where { Users.id inList userIds }
                .associate { it[Users.id] to it[Users.name] }
        } else emptyMap()
        
        val schedules: Map<Int, String> = if (scheduleIds.isNotEmpty()) {
            OnCallSchedules.selectAll()
                .where { OnCallSchedules.id inList scheduleIds }
                .associate { it[OnCallSchedules.id].value to it[OnCallSchedules.name] }
        } else emptyMap()
        
        // Group targets by step ID
        val targetsByStepId = allTargets.groupBy { it[EscalationStepTargets.escalationStepId] }
        
        // Group steps by policy ID
        val stepsByPolicyId = allSteps.groupBy { it[EscalationSteps.escalationPolicyId] }
        
        // Build the result
        policies.map { policyRow ->
            val policyId = policyRow[EscalationPolicies.id].value
            val steps = stepsByPolicyId[policyId]?.map { stepRow ->
                val stepId = stepRow[EscalationSteps.id].value
                val targets = targetsByStepId[stepId]?.map { targetRow ->
                    val targetType = targetRow[EscalationStepTargets.targetType]
                    val targetId = targetRow[EscalationStepTargets.targetId]
                    val targetName = when (targetType) {
                        "USER" -> users[targetId]
                        "ON_CALL_SCHEDULE" -> schedules[targetId]
                        else -> null
                    }
                    EscalationStepTarget(
                        id = targetRow[EscalationStepTargets.id].value,
                        targetType = targetType,
                        targetId = targetId,
                        targetName = targetName
                    )
                } ?: emptyList()
                
                EscalationStep(
                    id = stepId,
                    stepOrder = stepRow[EscalationSteps.stepOrder],
                    timeoutMinutes = stepRow[EscalationSteps.timeoutMinutes],
                    smsFallbackDelayMinutes = stepRow[EscalationSteps.smsFallbackDelayMinutes],
                    targets = targets,
                    createdAt = stepRow[EscalationSteps.createdAt].toString()
                )
            } ?: emptyList()
            
            EscalationPolicy(
                id = policyId,
                organizationId = policyRow[EscalationPolicies.organizationId],
                name = policyRow[EscalationPolicies.name],
                description = policyRow[EscalationPolicies.description],
                repeatCount = policyRow[EscalationPolicies.repeatCount],
                steps = steps,
                createdAt = policyRow[EscalationPolicies.createdAt].toString(),
                updatedAt = policyRow[EscalationPolicies.updatedAt].toString()
            )
        }
    }
    
    data class CreateStepData(
        val stepOrder: Int,
        val timeoutMinutes: Int,
        val smsFallbackDelayMinutes: Int = 2,
        val targets: List<CreateTargetData>
    )
    
    data class CreateTargetData(
        val targetType: String,
        val targetId: Int
    )
    
    fun createPolicy(
        organizationId: Int,
        name: String,
        description: String?,
        repeatCount: Int,
        steps: List<CreateStepData>
    ): EscalationPolicy = transaction {
        val now = Clock.System.now()
        
        val policyId = EscalationPolicies.insertAndGetId {
            it[EscalationPolicies.organizationId] = organizationId
            it[EscalationPolicies.name] = name
            it[EscalationPolicies.description] = description
            it[EscalationPolicies.repeatCount] = repeatCount
            it[createdAt] = now
            it[updatedAt] = now
        }.value
        
        steps.forEach { step ->
            val stepId = EscalationSteps.insertAndGetId {
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
        steps: List<CreateStepData>? = null
    ): EscalationPolicy? = transaction {
        val now = Clock.System.now()
        
        EscalationPolicies.update({ EscalationPolicies.id eq policyId }) {
            if (name != null) it[EscalationPolicies.name] = name
            if (description != null) it[EscalationPolicies.description] = description
            if (repeatCount != null) it[EscalationPolicies.repeatCount] = repeatCount
            it[updatedAt] = now
        }
        
        if (steps != null) {
            // Delete existing steps and targets
            val existingStepIds = EscalationSteps
                .select(EscalationSteps.id)
                .where { EscalationSteps.escalationPolicyId eq policyId }
                .map { it[EscalationSteps.id].value }
            
            if (existingStepIds.isNotEmpty()) {
                EscalationStepTargets.deleteWhere { EscalationStepTargets.escalationStepId inList existingStepIds }
            }
            EscalationSteps.deleteWhere { escalationPolicyId eq policyId }
            
            // Insert new steps
            steps.forEach { step ->
                val stepId = EscalationSteps.insertAndGetId {
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
    
    fun deletePolicy(policyId: Int): Boolean = transaction {
        EscalationPolicies.deleteWhere { id eq policyId } > 0
    }
}
