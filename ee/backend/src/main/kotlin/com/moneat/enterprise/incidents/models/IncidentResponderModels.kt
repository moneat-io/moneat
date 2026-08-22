// Moneat Enterprise - proprietary module
// Copyright (c) 2026 Moneat. All rights reserved.
// See ee/LICENSE for license terms.

package com.moneat.enterprise.incidents.models

import com.moneat.enterprise.oncall.models.OnCallIncidents
import com.moneat.shared.models.Organizations
import com.moneat.shared.models.Users
import kotlinx.serialization.Serializable
import org.jetbrains.exposed.v1.core.ReferenceOption
import org.jetbrains.exposed.v1.core.dao.id.IntIdTable
import org.jetbrains.exposed.v1.datetime.timestamp
import kotlin.uuid.Uuid

@Serializable
enum class IncidentParticipationType(val wire: String) {
    PARTICIPANT("PARTICIPANT"),
    OBSERVER("OBSERVER"),
}

@Serializable
enum class IncidentRoleEndReason(val wire: String) {
    REASSIGNED("REASSIGNED"),
    UNASSIGNED("UNASSIGNED"),
    HANDOVER("HANDOVER"),
    INCIDENT_ENDED("INCIDENT_ENDED"),
}

object NativeIncidentRoleDefinitions : IntIdTable("native_incident_role_definitions") {
    val resourceId = uuid("resource_id").clientDefault { Uuid.random() }
    val organizationId = integer("organization_id").references(Organizations.id, onDelete = ReferenceOption.CASCADE)
    val stableKey = varchar("stable_key", 100)
    val version = integer("version")
    val isCurrent = bool("is_current").default(true)
    val name = varchar("name", 120)
    val description = text("description").nullable()
    val responsibilities = text("responsibilities")
    val privateInstructions = text("private_instructions").nullable()
    val isRequired = bool("is_required").default(false)
    val isDefault = bool("is_default").default(false)
    val createdBy = integer("created_by").references(Users.id)
    val createdAt = timestamp("created_at")
    val supersededAt = timestamp("superseded_at").nullable()

    init {
        uniqueIndex(organizationId, resourceId)
        uniqueIndex(organizationId, stableKey, version)
    }
}

object NativeIncidentRoleAssignments : IntIdTable("native_incident_role_assignments") {
    val resourceId = uuid("resource_id").clientDefault { Uuid.random() }
    val organizationId = integer("organization_id").references(Organizations.id, onDelete = ReferenceOption.CASCADE)
    val incidentId = integer("incident_id").references(OnCallIncidents.id, onDelete = ReferenceOption.CASCADE)
    val roleDefinitionId = integer("role_definition_id").references(NativeIncidentRoleDefinitions.id)
    val assigneeUserId = integer("assignee_user_id").references(Users.id)
    val assignedBy = integer("assigned_by").references(Users.id)
    val assignedAt = timestamp("assigned_at")
    val endedBy = integer("ended_by").references(Users.id).nullable()
    val endedAt = timestamp("ended_at").nullable()
    val endReason = varchar("end_reason", 32).nullable()

    init {
        uniqueIndex(organizationId, resourceId)
    }
}

object NativeIncidentParticipants : IntIdTable("native_incident_participants") {
    val resourceId = uuid("resource_id").clientDefault { Uuid.random() }
    val organizationId = integer("organization_id").references(Organizations.id, onDelete = ReferenceOption.CASCADE)
    val incidentId = integer("incident_id").references(OnCallIncidents.id, onDelete = ReferenceOption.CASCADE)
    val userId = integer("user_id").references(Users.id)
    val participationType = varchar("participation_type", 24)
    val joinedBy = integer("joined_by").references(Users.id)
    val joinedAt = timestamp("joined_at")
    val leftBy = integer("left_by").references(Users.id).nullable()
    val leftAt = timestamp("left_at").nullable()

    init {
        uniqueIndex(organizationId, resourceId)
    }
}

object NativeIncidentHandovers : IntIdTable("native_incident_handovers") {
    val resourceId = uuid("resource_id").clientDefault { Uuid.random() }
    val organizationId = integer("organization_id").references(Organizations.id, onDelete = ReferenceOption.CASCADE)
    val incidentId = integer("incident_id").references(OnCallIncidents.id, onDelete = ReferenceOption.CASCADE)
    val roleDefinitionId = integer("role_definition_id").references(NativeIncidentRoleDefinitions.id)
    val fromAssignmentId = integer("from_assignment_id").references(NativeIncidentRoleAssignments.id)
    val toAssignmentId = integer("to_assignment_id").references(NativeIncidentRoleAssignments.id)
    val handedOverBy = integer("handed_over_by").references(Users.id)
    val note = text("note").nullable()
    val createdAt = timestamp("created_at")

    init {
        uniqueIndex(organizationId, resourceId)
    }
}
