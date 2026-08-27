// Moneat Enterprise - proprietary module
// Copyright (c) 2026 Moneat. All rights reserved.
// See ee/LICENSE for license terms.

package com.moneat.enterprise.incidents.followups

import com.moneat.enterprise.oncall.models.OnCallIncidents
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

enum class IncidentFollowUpStatus(val wire: String) {
    OPEN("OPEN"),
    ACCEPTED("ACCEPTED"),
    COMPLETED("COMPLETED"),
    CANCELLED("CANCELLED"),
}

enum class IncidentFollowUpPriority(val wire: String, val rank: Int) {
    P0("P0", 0),
    P1("P1", 1),
    P2("P2", 2),
    P3("P3", 3),
    P4("P4", 4),
    P5("P5", 5),
    ;

    companion object {
        fun parse(value: String): IncidentFollowUpPriority? =
            entries.firstOrNull { it.wire == value.trim().uppercase() }
    }
}

/** Durable post-incident work item. Ownership is exactly one user or team. */
object NativeIncidentFollowUps : IntIdTable("native_incident_follow_ups") {
    val resourceId = uuid("resource_id").clientDefault { Uuid.random() }
    val organizationId = integer("organization_id").references(Organizations.id, onDelete = ReferenceOption.CASCADE)
    val incidentId = integer("incident_id").references(OnCallIncidents.id, onDelete = ReferenceOption.CASCADE)
    val title = varchar("title", 255)
    val description = text("description")
    val ownerUserId = integer("owner_user_id").references(Users.id, onDelete = ReferenceOption.SET_NULL).nullable()
    val ownerTeamId =
        integer("owner_team_id").references(OrganizationTeams.id, onDelete = ReferenceOption.SET_NULL).nullable()
    val priority = varchar("priority", 8)
    val labels = stringListJsonb("labels_json")
    val dueAt = timestamp("due_at").nullable()
    val slaMinutes = integer("sla_minutes").nullable()
    val reminderMinutes = integer("reminder_minutes").nullable()
    val nextReminderAt = timestamp("next_reminder_at").nullable()
    val escalationLevel = integer("escalation_level")
    val slaFiredAt = timestamp("sla_fired_at").nullable()
    val status = varchar("status", 16)
    val acceptedBy = integer("accepted_by").references(Users.id, onDelete = ReferenceOption.SET_NULL).nullable()
    val acceptedAt = timestamp("accepted_at").nullable()
    val completedBy = integer("completed_by").references(Users.id, onDelete = ReferenceOption.SET_NULL).nullable()
    val completedAt = timestamp("completed_at").nullable()
    val createdBy = integer("created_by").references(Users.id, onDelete = ReferenceOption.SET_NULL).nullable()
    val sourceType = varchar("source", 32)
    val slackChannelId = varchar("slack_channel_id", 128).nullable()
    val slackMessageTs = varchar("slack_message_ts", 64).nullable()
    val createdAt = timestamp("created_at")
    val updatedAt = timestamp("updated_at")

    init {
        uniqueIndex(organizationId, resourceId)
        index(false, organizationId, status, priority, dueAt)
        index(false, incidentId, status)
    }
}

private class FollowUpLabelsColumnType : ColumnType<List<String>>() {
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

    private fun isH2(): Boolean =
        TransactionManager.currentOrNull()?.db?.url?.contains("h2", ignoreCase = true) == true
}

private fun Table.stringListJsonb(name: String): Column<List<String>> =
    registerColumn(name, FollowUpLabelsColumnType())
