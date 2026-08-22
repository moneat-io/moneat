// Moneat Enterprise - proprietary module
// Copyright (c) 2026 Moneat. All rights reserved.
// See ee/LICENSE for license terms.

package com.moneat.enterprise.alertroutes.models

import com.moneat.enterprise.oncall.models.requiredJsonb
import com.moneat.shared.models.EscalationPolicies
import com.moneat.shared.models.OrganizationTeams
import com.moneat.shared.models.Organizations
import com.moneat.shared.models.Users
import kotlinx.serialization.json.Json
import org.jetbrains.exposed.v1.core.Column
import org.jetbrains.exposed.v1.core.ColumnType
import org.jetbrains.exposed.v1.core.ReferenceOption
import org.jetbrains.exposed.v1.core.Table
import org.jetbrains.exposed.v1.core.dao.id.IntIdTable
import org.jetbrains.exposed.v1.datetime.timestamp
import org.jetbrains.exposed.v1.jdbc.transactions.TransactionManager
import org.postgresql.util.PGobject
import kotlin.uuid.Uuid

/** Source-neutral alert context fields available to route conditions, grouping keys, and templates. */
enum class AlertRouteContextField(val wire: String, val supportsMetadataKey: Boolean = false) {
    SOURCE("source"),
    STATUS("status"),
    PRIORITY("priority"),
    TITLE("title"),
    DESCRIPTION("description"),
    DEDUPLICATION_KEY("deduplication_key"),
    URL("url"),
    EPISODE_KEY("episode_key"),
    EPISODE_SEQUENCE("episode_sequence"),
    EPISODE_STATUS("episode_status"),
    METADATA("metadata", supportsMetadataKey = true),
    SERVICE("service"),
    TEAM("team"),
    CATALOG_RESOURCE("catalog_resource"),
    ;

    companion object {
        fun fromWire(value: String): AlertRouteContextField? {
            val normalized = value.trim()
            return entries.firstOrNull {
                it.wire.equals(normalized, ignoreCase = true) || it.name == normalized.uppercase()
            }
        }
    }
}

/** Comparison operators supported by an individual alert-route condition. */
enum class AlertRouteConditionOperator(
    val wire: String,
    val requiresValues: Boolean = true,
    val priorityOnly: Boolean = false,
) {
    EQUALS("EQUALS"),
    NOT_EQUALS("NOT_EQUALS"),
    ONE_OF("ONE_OF"),
    NOT_ONE_OF("NOT_ONE_OF"),
    CONTAINS("CONTAINS"),
    NOT_CONTAINS("NOT_CONTAINS"),
    STARTS_WITH("STARTS_WITH"),
    ENDS_WITH("ENDS_WITH"),
    IS_SET("IS_SET", requiresValues = false),
    IS_NOT_SET("IS_NOT_SET", requiresValues = false),
    AT_LEAST_AS_SEVERE_AS("AT_LEAST_AS_SEVERE_AS", priorityOnly = true),
    ;

    companion object {
        fun fromWire(value: String): AlertRouteConditionOperator? =
            entries.firstOrNull { it.wire == value.trim().uppercase() }
    }
}

/** How a matched route pages responders. */
enum class AlertRoutePagingMode(val wire: String) {
    NONE("NONE"),
    FIRST_EPISODE_PER_GROUP("FIRST_EPISODE_PER_GROUP"),
    EVERY_EPISODE("EVERY_EPISODE"),
    ;

    companion object {
        fun fromWire(value: String): AlertRoutePagingMode? =
            entries.firstOrNull { it.wire == value.trim().uppercase() }
    }
}

/** Whether an escalation target is stated directly or derived from ownership. */
enum class AlertRouteTargetKind(val wire: String) {
    ESCALATION_POLICY("ESCALATION_POLICY"),
    TEAM("TEAM"),
    OWNERSHIP_DERIVED("OWNERSHIP_DERIVED"),
    ;

    companion object {
        fun fromWire(value: String): AlertRouteTargetKind? =
            entries.firstOrNull { it.wire == value.trim().uppercase() }
    }
}

/** Ownership dimension an OWNERSHIP_DERIVED target follows. */
enum class AlertRouteOwnershipSource(val wire: String) {
    SERVICE("SERVICE"),
    TEAM("TEAM"),
    CATALOG("CATALOG"),
    ;

    companion object {
        fun fromWire(value: String): AlertRouteOwnershipSource? =
            entries.firstOrNull { it.wire == value.trim().uppercase() }
    }
}

/** Suggested grouping is the default; automatic grouping requires an explicit per-route opt-in. */
enum class AlertRouteGroupingBehavior(val wire: String) {
    SUGGESTED("SUGGESTED"),
    AUTOMATIC("AUTOMATIC"),
    ;

    companion object {
        val DEFAULT = SUGGESTED

        fun fromWire(value: String): AlertRouteGroupingBehavior? =
            entries.firstOrNull { it.wire == value.trim().uppercase() }
    }
}

/** Fixed windows are anchored on the first alert in a group; rolling windows extend on every alert. */
enum class AlertRouteGroupingWindowKind(val wire: String) {
    FIXED("FIXED"),
    ROLLING("ROLLING"),
    ;

    companion object {
        fun fromWire(value: String): AlertRouteGroupingWindowKind? =
            entries.firstOrNull { it.wire == value.trim().uppercase() }
    }
}

/** Initial lifecycle status for incidents created by a matched route. */
enum class AlertRouteIncidentMode(val wire: String) {
    TRIAGE("TRIAGE"),
    ACTIVE("ACTIVE"),
    ;

    companion object {
        fun fromWire(value: String): AlertRouteIncidentMode? =
            entries.firstOrNull { it.wire == value.trim().uppercase() }
    }
}

/** Audited change kinds recorded against an alert route. */
enum class AlertRouteChangeType(val wire: String) {
    CREATED("CREATED"),
    UPDATED("UPDATED"),
    REORDERED("REORDERED"),
    DELETED("DELETED"),
}

private class JsonbStringListColumnType : ColumnType<List<String>>() {
    private fun isH2(): Boolean =
        TransactionManager
            .currentOrNull()
            ?.db
            ?.url
            ?.contains("h2", ignoreCase = true) == true

    override fun sqlType(): String = if (isH2()) "TEXT" else "JSONB"

    override fun valueFromDB(value: Any): List<String> =
        when (value) {
            is PGobject -> value.value?.let { Json.decodeFromString<List<String>>(it) } ?: emptyList()
            is String -> Json.decodeFromString(value)
            else -> emptyList()
        }

    override fun notNullValueToDB(value: List<String>): Any {
        val encoded = Json.encodeToString(kotlinx.serialization.serializer<List<String>>(), value)
        if (isH2()) return encoded
        return PGobject().apply {
            type = "jsonb"
            this.value = encoded
        }
    }
}

private fun Table.stringListJsonb(name: String): Column<List<String>> =
    registerColumn(name, JsonbStringListColumnType())

/** Ordered, versioned alert routes owned by one organization. */
object EnterpriseAlertRoutes : IntIdTable("enterprise_alert_routes") {
    val resourceId = uuid("resource_id").clientDefault { Uuid.random() }
    val organizationId = integer("organization_id").references(Organizations.id, onDelete = ReferenceOption.CASCADE)
    val routeKey = varchar("route_key", 160)
    val name = varchar("name", 200)
    val description = text("description").nullable()
    val enabled = bool("enabled").default(true)
    val position = integer("position")
    val revision = integer("revision").default(1)
    val createdBy = integer("created_by").references(Users.id, onDelete = ReferenceOption.SET_NULL).nullable()
    val updatedBy = integer("updated_by").references(Users.id, onDelete = ReferenceOption.SET_NULL).nullable()
    val createdAt = timestamp("created_at")
    val updatedAt = timestamp("updated_at")

    init {
        uniqueIndex(organizationId, resourceId)
        uniqueIndex(organizationId, routeKey)
        uniqueIndex(organizationId, position)
        index(false, organizationId, position, id)
    }
}

/** Condition groups are ORed together when a route is evaluated. */
object EnterpriseAlertRouteConditionGroups : IntIdTable("enterprise_alert_route_condition_groups") {
    val resourceId = uuid("resource_id").clientDefault { Uuid.random() }
    val organizationId = integer("organization_id").references(Organizations.id, onDelete = ReferenceOption.CASCADE)
    val routeId = integer("route_id").references(EnterpriseAlertRoutes.id, onDelete = ReferenceOption.CASCADE)
    val position = integer("position")
    val createdAt = timestamp("created_at")

    init {
        uniqueIndex(organizationId, resourceId)
        uniqueIndex(routeId, position)
    }
}

/** Conditions inside one group are ANDed together. */
object EnterpriseAlertRouteConditions : IntIdTable("enterprise_alert_route_conditions") {
    val resourceId = uuid("resource_id").clientDefault { Uuid.random() }
    val organizationId = integer("organization_id").references(Organizations.id, onDelete = ReferenceOption.CASCADE)
    val groupId =
        integer("group_id").references(EnterpriseAlertRouteConditionGroups.id, onDelete = ReferenceOption.CASCADE)
    val position = integer("position")
    val contextField = varchar("context_field", 48)
    val metadataKey = varchar("metadata_key", 160).nullable()
    val operator = varchar("operator", 32)
    val comparisonValues = stringListJsonb("comparison_values")
    val createdAt = timestamp("created_at")

    init {
        uniqueIndex(organizationId, resourceId)
        uniqueIndex(groupId, position)
    }
}

/** Paging, grouping, incident-creation, and recovery configuration for one route. */
object EnterpriseAlertRouteActions : IntIdTable("enterprise_alert_route_actions") {
    val resourceId = uuid("resource_id").clientDefault { Uuid.random() }
    val organizationId = integer("organization_id").references(Organizations.id, onDelete = ReferenceOption.CASCADE)
    val routeId = integer("route_id").references(EnterpriseAlertRoutes.id, onDelete = ReferenceOption.CASCADE)
    val pagingMode = varchar("paging_cadence", 32)
    val groupingBehavior = varchar("grouping_behavior", 24)
    val groupingWindowKind = varchar("grouping_window_kind", 16)
    val groupingWindowSeconds = integer("grouping_window_seconds")
    val groupingKeys = stringListJsonb("grouping_keys")
    val incidentCreationEnabled = bool("incident_creation_enabled")
    val incidentCreationMode = varchar("incident_initial_status", 24)
    val incidentTitleTemplate = text("incident_title_template").nullable()
    val incidentSummaryTemplate = text("incident_summary_template").nullable()
    val incidentSeverity = varchar("incident_severity", 20).nullable()
    val incidentType = varchar("incident_type", 120).nullable()
    val recoveryCancelEscalations = bool("recovery_cancel_escalations")
    val recoveryDeclineUnacceptedTriage = bool("recovery_decline_unaccepted_triage")
    val recoveryGraceSeconds = integer("recovery_grace_seconds")
    val createdAt = timestamp("created_at")
    val updatedAt = timestamp("updated_at")

    init {
        uniqueIndex(organizationId, resourceId)
        uniqueIndex(routeId)
    }
}

/** Static or ownership-derived escalation targets resolved when a route is selected. */
object EnterpriseAlertRouteTargets : IntIdTable("enterprise_alert_route_targets") {
    val resourceId = uuid("resource_id").clientDefault { Uuid.random() }
    val organizationId = integer("organization_id").references(Organizations.id, onDelete = ReferenceOption.CASCADE)
    val routeId = integer("route_id").references(EnterpriseAlertRoutes.id, onDelete = ReferenceOption.CASCADE)
    val position = integer("position")
    val targetKind = varchar("target_kind", 32)
    val escalationPolicyId =
        integer("escalation_policy_id")
            .references(EscalationPolicies.id, onDelete = ReferenceOption.RESTRICT)
            .nullable()
    val teamId =
        integer("team_id").references(OrganizationTeams.id, onDelete = ReferenceOption.RESTRICT).nullable()
    val ownershipSource = varchar("ownership_source", 24).nullable()
    val createdAt = timestamp("created_at")

    init {
        uniqueIndex(organizationId, resourceId)
        uniqueIndex(routeId, position)
    }
}

/** Organization-global idempotency record for one accepted alert-route mutation. */
object EnterpriseAlertRouteCommands : IntIdTable("enterprise_alert_route_commands") {
    val organizationId = integer("organization_id").references(Organizations.id, onDelete = ReferenceOption.CASCADE)
    val commandKey = varchar("command_key", 160)
    val commandType = varchar("command_type", 24)
    val requestFingerprint = varchar("request_fingerprint", 64)
    val resultRouteIds = stringListJsonb("result_route_ids")
    val createdAt = timestamp("created_at")

    init {
        uniqueIndex(organizationId, commandKey)
    }
}

/** Append-only audit trail: one row per route changed by an accepted mutation. */
object EnterpriseAlertRouteRevisions : IntIdTable("enterprise_alert_route_revisions") {
    val resourceId = uuid("resource_id").clientDefault { Uuid.random() }
    val organizationId = integer("organization_id").references(Organizations.id, onDelete = ReferenceOption.CASCADE)
    val routeId =
        integer("route_id").references(EnterpriseAlertRoutes.id, onDelete = ReferenceOption.SET_NULL).nullable()
    val routeResourceId = uuid("route_resource_id")
    val revision = integer("revision")
    val changeType = varchar("change_type", 24)
    val actorUserId = integer("actor_user_id").references(Users.id, onDelete = ReferenceOption.SET_NULL).nullable()
    val commandKey = varchar("command_key", 160)
    val requestFingerprint = varchar("request_fingerprint", 64)
    val snapshot = requiredJsonb("snapshot")
    val createdAt = timestamp("created_at")

    init {
        uniqueIndex(organizationId, resourceId)
        uniqueIndex(organizationId, routeResourceId, revision)
        index(false, organizationId, commandKey)
    }
}
