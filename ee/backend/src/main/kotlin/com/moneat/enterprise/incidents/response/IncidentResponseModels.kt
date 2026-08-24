// Moneat Enterprise - proprietary module
// Copyright (c) 2026 Moneat. All rights reserved.
// See ee/LICENSE for license terms.

package com.moneat.enterprise.incidents.response

import com.moneat.enterprise.oncall.models.OnCallAlerts
import com.moneat.enterprise.oncall.models.OnCallIncidents
import com.moneat.shared.models.EscalationPolicies
import com.moneat.shared.models.Organizations
import com.moneat.shared.models.Users
import org.jetbrains.exposed.v1.core.ReferenceOption
import org.jetbrains.exposed.v1.core.dao.id.IntIdTable
import org.jetbrains.exposed.v1.datetime.timestamp
import kotlin.uuid.Uuid

enum class IncidentResponseActivationStatus(val wire: String) {
    PENDING("PENDING"),
    ACTIVE("ACTIVE"),
    COMPLETED("COMPLETED"),
    FAILED("FAILED"),
    SKIPPED("SKIPPED"),
}

enum class IncidentResponseTargetStatus(val wire: String) {
    DESIRED("DESIRED"),
    ATTEMPTED("ATTEMPTED"),
    ACKNOWLEDGED("ACKNOWLEDGED"),
    FAILED("FAILED"),
    RETRYING("RETRYING"),
    SKIPPED("SKIPPED"),
}

object NativeIncidentResponsePolicies : IntIdTable("native_incident_response_policies") {
    val resourceId = uuid("resource_id").clientDefault { Uuid.random() }
    val organizationId = integer("organization_id").references(Organizations.id, onDelete = ReferenceOption.CASCADE)
    val commanderPolicyId = integer("commander_policy_id").references(EscalationPolicies.id).nullable()
    val ownershipPolicyId = integer("ownership_policy_id").references(EscalationPolicies.id).nullable()
    val pageOwnership = bool("page_ownership").default(true)
    val pageTestIncidents = bool("page_test_incidents").default(false)
    val pageRetrospectiveIncidents = bool("page_retrospective_incidents").default(false)
    val createdBy = integer("created_by").references(Users.id)
    val createdAt = timestamp("created_at")
    val updatedAt = timestamp("updated_at")

    init {
        uniqueIndex(organizationId)
        uniqueIndex(organizationId, resourceId)
    }
}

object NativeIncidentResponseActivations : IntIdTable("native_incident_response_activations") {
    val resourceId = uuid("resource_id").clientDefault { Uuid.random() }
    val organizationId = integer("organization_id").references(Organizations.id, onDelete = ReferenceOption.CASCADE)
    val incidentId = integer("incident_id").references(OnCallIncidents.id, onDelete = ReferenceOption.CASCADE)
    val activationRevision = integer("activation_revision")
    val trigger = varchar("trigger", 32)
    val status = varchar("status", 24)
    val desiredCount = integer("desired_count").default(0)
    val attemptedCount = integer("attempted_count").default(0)
    val acknowledgedCount = integer("acknowledged_count").default(0)
    val lastError = text("last_error").nullable()
    val startedAt = timestamp("started_at")
    val completedAt = timestamp("completed_at").nullable()
    val createdAt = timestamp("created_at")
    val updatedAt = timestamp("updated_at")

    init {
        uniqueIndex(organizationId, incidentId, activationRevision, trigger)
        uniqueIndex(organizationId, resourceId)
        index(false, organizationId, incidentId, createdAt)
    }
}

object NativeIncidentResponseTargets : IntIdTable("native_incident_response_targets") {
    val resourceId = uuid("resource_id").clientDefault { Uuid.random() }
    val organizationId = integer("organization_id").references(Organizations.id, onDelete = ReferenceOption.CASCADE)
    val activationId = integer("activation_id")
        .references(NativeIncidentResponseActivations.id, onDelete = ReferenceOption.CASCADE)
    val targetKey = varchar("target_key", 180)
    val targetType = varchar("target_type", 32)
    val escalationPolicyId = integer("escalation_policy_id").references(EscalationPolicies.id).nullable()
    val userId = integer("user_id").references(Users.id).nullable()
    val onCallAlertId = integer("on_call_alert_id").references(OnCallAlerts.id).nullable()
    val status = varchar("status", 24)
    val attemptCount = integer("attempt_count").default(0)
    val desiredAt = timestamp("desired_at")
    val attemptedAt = timestamp("attempted_at").nullable()
    val acknowledgedAt = timestamp("acknowledged_at").nullable()
    val failedAt = timestamp("failed_at").nullable()
    val lastError = text("last_error").nullable()
    val createdAt = timestamp("created_at")
    val updatedAt = timestamp("updated_at")

    init {
        uniqueIndex(organizationId, activationId, targetKey)
        uniqueIndex(organizationId, resourceId)
        index(false, organizationId, onCallAlertId)
    }
}
