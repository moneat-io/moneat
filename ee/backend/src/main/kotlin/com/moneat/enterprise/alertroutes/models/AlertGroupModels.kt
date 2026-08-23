// Moneat Enterprise - proprietary module
// Copyright (c) 2026 Moneat. All rights reserved.
// See ee/LICENSE for license terms.

package com.moneat.enterprise.alertroutes.models

import com.moneat.alerts.models.AlertEpisodes
import com.moneat.enterprise.oncall.models.OnCallAlerts
import com.moneat.enterprise.oncall.models.OnCallIncidents
import com.moneat.enterprise.oncall.models.requiredJsonb
import com.moneat.shared.models.EscalationPolicies
import com.moneat.shared.models.Organizations
import com.moneat.shared.models.Users
import org.jetbrains.exposed.v1.core.ReferenceOption
import org.jetbrains.exposed.v1.core.dao.id.IntIdTable
import org.jetbrains.exposed.v1.datetime.timestamp
import kotlin.uuid.Uuid

enum class AlertGroupState(val wire: String) { OPEN("OPEN"), CLOSED("CLOSED") }

enum class AlertGroupMemberState(val wire: String) {
    ACTIVE("ACTIVE"),
    REMOVED("REMOVED"),
    UNRELATED("UNRELATED"),
}

enum class AlertGroupPagingState(val wire: String) {
    READY("READY"),
    CLAIMED("CLAIMED"),
    DELIVERED("DELIVERED"),
    FAILED("FAILED"),
    NOT_REQUIRED("NOT_REQUIRED"),
}

enum class AlertGroupEscalationState(val wire: String) {
    PENDING("PENDING"),
    TRIGGERED("TRIGGERED"),
    RESOLVED("RESOLVED"),
    CANCELLED("CANCELLED"),
    FAILED("FAILED"),
}

enum class AlertGroupDecisionType(val wire: String) {
    GROUP_CREATED("GROUP_CREATED"),
    SUGGESTED("SUGGESTED"),
    MARKED_UNRELATED("MARKED_UNRELATED"),
    REMOVED("REMOVED"),
    ATTACHED("ATTACHED"),
    AUTOMATICALLY_ATTACHED("AUTOMATICALLY_ATTACHED"),
    TRIAGE_CREATED("TRIAGE_CREATED"),
    RECOVERY_COMPLETED("RECOVERY_COMPLETED"),
}

object EnterpriseAlertGroups : IntIdTable("enterprise_alert_groups") {
    val resourceId = uuid("resource_id").clientDefault { Uuid.random() }
    val organizationId = integer("organization_id").references(Organizations.id, onDelete = ReferenceOption.CASCADE)
    val routeId =
        integer("route_id").references(EnterpriseAlertRoutes.id, onDelete = ReferenceOption.SET_NULL).nullable()
    val routeResourceId = uuid("route_resource_id")
    val routeRevision = integer("route_revision")
    val identityHash = varchar("identity_hash", 64)
    val groupingTuple = requiredJsonb("grouping_tuple")
    val singletonEpisodeId =
        integer("singleton_episode_id").references(AlertEpisodes.id, onDelete = ReferenceOption.RESTRICT).nullable()
    val groupingBehavior = varchar("grouping_behavior", 24)
    val windowKind = varchar("window_kind", 16)
    val windowSeconds = integer("window_seconds")
    val openedAt = timestamp("opened_at")
    val lastAlertAt = timestamp("last_alert_at")
    val closesAt = timestamp("closes_at")
    val state = varchar("state", 16)
    val version = integer("version")
    val incidentId =
        integer("incident_id").references(OnCallIncidents.id, onDelete = ReferenceOption.RESTRICT).nullable()
    val candidateIncidentId =
        integer("candidate_incident_id").references(OnCallIncidents.id, onDelete = ReferenceOption.SET_NULL).nullable()
    val routeSnapshot = requiredJsonb("route_snapshot")
    val incidentTemplateSnapshot = requiredJsonb("incident_template_snapshot")
    val pagingMode = varchar("paging_mode", 32)
    val pagingState = varchar("paging_state", 24)
    val pagingCommandKey = varchar("paging_command_key", 200).nullable()
    val createdAt = timestamp("created_at")
    val updatedAt = timestamp("updated_at")

    init {
        uniqueIndex(organizationId, resourceId)
        index(false, organizationId, state, closesAt)
    }
}

object EnterpriseAlertGroupMembers : IntIdTable("enterprise_alert_group_members") {
    val resourceId = uuid("resource_id").clientDefault { Uuid.random() }
    val organizationId = integer("organization_id").references(Organizations.id, onDelete = ReferenceOption.CASCADE)
    val groupId = integer("group_id").references(EnterpriseAlertGroups.id, onDelete = ReferenceOption.CASCADE)
    val alertEpisodeId = integer("alert_episode_id").references(AlertEpisodes.id, onDelete = ReferenceOption.RESTRICT)
    val state = varchar("state", 16)
    val version = integer("version")
    val pagingState = varchar("paging_state", 24)
    val pagingCommandKey = varchar("paging_command_key", 200).nullable()
    val firstJoinedAt = timestamp("first_joined_at")
    val lastSeenAt = timestamp("last_seen_at")
    val resolvedAt = timestamp("resolved_at").nullable()
    val updatedAt = timestamp("updated_at")

    init {
        uniqueIndex(organizationId, resourceId)
        uniqueIndex(organizationId, alertEpisodeId)
        index(false, organizationId, groupId, state)
    }
}

object EnterpriseAlertGroupDecisions : IntIdTable("enterprise_alert_group_decisions") {
    val resourceId = uuid("resource_id").clientDefault { Uuid.random() }
    val organizationId = integer("organization_id").references(Organizations.id, onDelete = ReferenceOption.CASCADE)
    val groupId = integer("group_id").references(EnterpriseAlertGroups.id, onDelete = ReferenceOption.CASCADE)
    val decisionType = varchar("decision_type", 32)
    val alertEpisodeId =
        integer("alert_episode_id").references(AlertEpisodes.id, onDelete = ReferenceOption.RESTRICT).nullable()
    val incidentId =
        integer("incident_id").references(OnCallIncidents.id, onDelete = ReferenceOption.SET_NULL).nullable()
    val actorUserId = integer("actor_user_id").references(Users.id, onDelete = ReferenceOption.SET_NULL).nullable()
    val commandKey = varchar("command_key", 160)
    val details = requiredJsonb("details")
    val createdAt = timestamp("created_at")

    init {
        uniqueIndex(organizationId, resourceId)
        uniqueIndex(organizationId, commandKey)
        index(false, organizationId, groupId, createdAt, id)
    }
}

object EnterpriseAlertGroupEscalations : IntIdTable("enterprise_alert_group_escalations") {
    val resourceId = uuid("resource_id").clientDefault { Uuid.random() }
    val organizationId = integer("organization_id").references(Organizations.id, onDelete = ReferenceOption.CASCADE)
    val groupId = integer("group_id").references(EnterpriseAlertGroups.id, onDelete = ReferenceOption.CASCADE)
    val escalationKey = varchar("escalation_key", 200)
    val escalationPolicyId =
        integer("escalation_policy_id").references(EscalationPolicies.id, onDelete = ReferenceOption.RESTRICT)
    val onCallAlertId =
        integer("on_call_alert_id").references(OnCallAlerts.id, onDelete = ReferenceOption.SET_NULL).nullable()
    val state = varchar("state", 24)
    val createdAt = timestamp("created_at")
    val updatedAt = timestamp("updated_at")

    init {
        uniqueIndex(organizationId, resourceId)
        uniqueIndex(groupId, escalationKey)
    }
}

object EnterpriseAlertGroupCommands : IntIdTable("enterprise_alert_group_commands") {
    val organizationId = integer("organization_id").references(Organizations.id, onDelete = ReferenceOption.CASCADE)
    val groupId =
        integer("group_id").references(EnterpriseAlertGroups.id, onDelete = ReferenceOption.SET_NULL).nullable()
    val commandKey = varchar("command_key", 160)
    val commandType = varchar("command_type", 32)
    val requestFingerprint = varchar("request_fingerprint", 64)
    val resultVersion = integer("result_version")
    val createdAt = timestamp("created_at")

    init {
        uniqueIndex(organizationId, commandKey)
    }
}
