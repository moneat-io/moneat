// Moneat Enterprise - proprietary module
// Copyright (c) 2026 Moneat. All rights reserved.
// See ee/LICENSE for license terms.

package com.moneat.enterprise.incidents.responders

import com.moneat.enterprise.incidents.commands.IncidentCommandNotFoundException
import com.moneat.enterprise.incidents.models.IncidentParticipationType
import com.moneat.enterprise.incidents.models.NativeIncidentParticipants
import com.moneat.enterprise.incidents.models.NativeIncidentRoleAssignments
import com.moneat.enterprise.incidents.models.NativeIncidentRoleDefinitions
import com.moneat.enterprise.oncall.models.OnCallIncidents
import com.moneat.shared.models.Memberships
import com.moneat.shared.models.Users
import com.moneat.shared.services.toUuidOrNull
import kotlinx.serialization.Serializable
import kotlinx.serialization.decodeFromString
import kotlinx.serialization.encodeToString
import kotlinx.serialization.json.Json
import org.jetbrains.exposed.v1.core.ResultRow
import org.jetbrains.exposed.v1.core.SortOrder
import org.jetbrains.exposed.v1.core.and
import org.jetbrains.exposed.v1.core.eq
import org.jetbrains.exposed.v1.core.inList
import org.jetbrains.exposed.v1.core.isNull
import org.jetbrains.exposed.v1.jdbc.insertAndGetId
import org.jetbrains.exposed.v1.jdbc.selectAll
import org.jetbrains.exposed.v1.jdbc.transactions.transaction
import org.jetbrains.exposed.v1.jdbc.update
import kotlin.time.Clock
import kotlin.uuid.Uuid

@Serializable
data class IncidentRoleDefinition(
    val id: String,
    val key: String,
    val version: Int,
    val name: String,
    val description: String? = null,
    val responsibilities: List<String>,
    val privateInstructions: String? = null,
    val required: Boolean,
    val default: Boolean,
)

@Serializable
data class IncidentRoleAssignment(
    val id: String,
    val role: IncidentRoleDefinition,
    val assigneeUserId: String,
    val assignedByUserId: String,
    val assignedAt: String,
)

@Serializable
data class IncidentParticipant(
    val id: String,
    val userId: String,
    val type: IncidentParticipationType,
    val joinedByUserId: String,
    val joinedAt: String,
)

data class CreateIncidentRole(
    val stableKey: String,
    val name: String,
    val description: String?,
    val responsibilities: List<String>,
    val privateInstructions: String?,
    val required: Boolean,
    val default: Boolean,
)

class IncidentResponderService {
    fun listRoles(organizationId: Int, actorUserId: Int): List<IncidentRoleDefinition> =
        transaction {
            requireMember(organizationId, actorUserId)
            ensureDefaultRoles(organizationId, actorUserId)
            NativeIncidentRoleDefinitions
                .selectAll()
                .where {
                    (NativeIncidentRoleDefinitions.organizationId eq organizationId) and
                        (NativeIncidentRoleDefinitions.isCurrent eq true)
                }.orderBy(NativeIncidentRoleDefinitions.name to SortOrder.ASC)
                .map(::roleDefinition)
        }

    fun createRole(
        organizationId: Int,
        actorUserId: Int,
        request: CreateIncidentRole,
    ): IncidentRoleDefinition =
        transaction {
            requireMember(organizationId, actorUserId)
            val key = normalizeKey(request.stableKey)
            val name = request.name.cleaned()
            require(name != null) { "Incident role name is required" }
            require(name.length <= MAX_ROLE_NAME_LENGTH) { "Incident role name is too long" }
            val responsibilities = request.responsibilities.mapNotNull { it.cleaned() }
            require(responsibilities.isNotEmpty()) { "Incident role responsibilities are required" }
            val current = currentRole(organizationId, key)
            val now = Clock.System.now()
            current?.let { row ->
                NativeIncidentRoleDefinitions.update({
                    NativeIncidentRoleDefinitions.id eq row[NativeIncidentRoleDefinitions.id]
                }) {
                    it[isCurrent] = false
                    it[supersededAt] = now
                }
            }
            val id =
                insertRole(
                    RoleInsert(
                    organizationId = organizationId,
                    actorUserId = actorUserId,
                    key = key,
                    version = (current?.get(NativeIncidentRoleDefinitions.version) ?: 0) + 1,
                    name = name,
                    description = request.description.cleaned(),
                    responsibilities = responsibilities,
                    privateInstructions = request.privateInstructions.cleaned(),
                    required = request.required,
                    default = request.default,
                    now = now,
                    ),
                )
            roleDefinition(requireRole(id))
        }

    fun resolveRoleId(organizationId: Int, resourceId: String): Int =
        transaction {
            val uuid = resourceId.toUuidOrNull() ?: throw IllegalArgumentException("Invalid incident role ID")
            NativeIncidentRoleDefinitions
                .selectAll()
                .where {
                    (NativeIncidentRoleDefinitions.organizationId eq organizationId) and
                        (NativeIncidentRoleDefinitions.resourceId eq uuid)
                }.singleOrNull()
                ?.get(NativeIncidentRoleDefinitions.id)
                ?.value
                ?: throw IncidentCommandNotFoundException("Incident role not found")
        }

    fun resolveUserId(organizationId: Int, resourceId: String): Int =
        transaction {
            val uuid = resourceId.toUuidOrNull() ?: throw IllegalArgumentException("Invalid user ID")
            val userId =
                Users
                    .selectAll()
                    .where { Users.resource_id eq uuid }
                    .singleOrNull()
                    ?.get(Users.id)
                    ?: throw IncidentCommandNotFoundException("Incident participant not found")
            requireMember(organizationId, userId)
            userId
        }

    fun listAssignments(organizationId: Int, incidentId: Int): List<IncidentRoleAssignment> =
        transaction {
            requireIncident(organizationId, incidentId)
            val rows =
                NativeIncidentRoleAssignments
                    .selectAll()
                    .where {
                        (NativeIncidentRoleAssignments.organizationId eq organizationId) and
                            (NativeIncidentRoleAssignments.incidentId eq incidentId) and
                            NativeIncidentRoleAssignments.endedAt.isNull()
                    }.orderBy(NativeIncidentRoleAssignments.assignedAt to SortOrder.ASC)
                    .toList()
            val userIds = rows.flatMap { row ->
                listOf(
                    row[NativeIncidentRoleAssignments.assigneeUserId],
                    row[NativeIncidentRoleAssignments.assignedBy],
                )
            }
            val users = publicUserIds(userIds)
            rows.map { row ->
                IncidentRoleAssignment(
                    id = row[NativeIncidentRoleAssignments.resourceId].toString(),
                    role = roleDefinition(
                        requireRole(row[NativeIncidentRoleAssignments.roleDefinitionId]),
                        includePrivateInstructions = false,
                    ),
                    assigneeUserId = checkNotNull(users[row[NativeIncidentRoleAssignments.assigneeUserId]]),
                    assignedByUserId = checkNotNull(users[row[NativeIncidentRoleAssignments.assignedBy]]),
                    assignedAt = row[NativeIncidentRoleAssignments.assignedAt].toString(),
                )
            }
        }

    fun listParticipants(organizationId: Int, incidentId: Int): List<IncidentParticipant> =
        transaction {
            requireIncident(organizationId, incidentId)
            val rows =
                NativeIncidentParticipants
                    .selectAll()
                    .where {
                        (NativeIncidentParticipants.organizationId eq organizationId) and
                            (NativeIncidentParticipants.incidentId eq incidentId) and
                            NativeIncidentParticipants.leftAt.isNull()
                    }.orderBy(NativeIncidentParticipants.joinedAt to SortOrder.ASC)
                    .toList()
            val userIds = rows.flatMap { row ->
                listOf(row[NativeIncidentParticipants.userId], row[NativeIncidentParticipants.joinedBy])
            }
            val users = publicUserIds(userIds)
            rows.map { row ->
                IncidentParticipant(
                    id = row[NativeIncidentParticipants.resourceId].toString(),
                    userId = checkNotNull(users[row[NativeIncidentParticipants.userId]]),
                    type = IncidentParticipationType.valueOf(row[NativeIncidentParticipants.participationType]),
                    joinedByUserId = checkNotNull(users[row[NativeIncidentParticipants.joinedBy]]),
                    joinedAt = row[NativeIncidentParticipants.joinedAt].toString(),
                )
            }
        }

    private fun ensureDefaultRoles(organizationId: Int, actorUserId: Int) {
        val now = Clock.System.now()
        DEFAULT_ROLES.forEach { role ->
            if (currentRole(organizationId, role.key) == null) {
                insertRole(
                    RoleInsert(
                    organizationId = organizationId,
                    actorUserId = actorUserId,
                    key = role.key,
                    version = 1,
                    name = role.name,
                    description = role.description,
                    responsibilities = role.responsibilities,
                    privateInstructions = role.privateInstructions,
                    required = true,
                    default = true,
                    now = now,
                    ),
                )
            }
        }
    }

    private fun insertRole(request: RoleInsert): Int =
        NativeIncidentRoleDefinitions.insertAndGetId {
            it[resourceId] = Uuid.random()
            it[NativeIncidentRoleDefinitions.organizationId] = request.organizationId
            it[stableKey] = request.key
            it[NativeIncidentRoleDefinitions.version] = request.version
            it[isCurrent] = true
            it[NativeIncidentRoleDefinitions.name] = request.name
            it[NativeIncidentRoleDefinitions.description] = request.description
            it[NativeIncidentRoleDefinitions.responsibilities] = json.encodeToString(request.responsibilities)
            it[NativeIncidentRoleDefinitions.privateInstructions] = request.privateInstructions
            it[isRequired] = request.required
            it[isDefault] = request.default
            it[createdBy] = request.actorUserId
            it[createdAt] = request.now
            it[supersededAt] = null
        }.value

    private fun currentRole(organizationId: Int, key: String): ResultRow? =
        NativeIncidentRoleDefinitions
            .selectAll()
            .where {
                (NativeIncidentRoleDefinitions.organizationId eq organizationId) and
                    (NativeIncidentRoleDefinitions.stableKey eq key) and
                    (NativeIncidentRoleDefinitions.isCurrent eq true)
            }.singleOrNull()

    private fun requireRole(id: Int): ResultRow =
        NativeIncidentRoleDefinitions
            .selectAll()
            .where { NativeIncidentRoleDefinitions.id eq id }
            .single()

    private fun requireIncident(organizationId: Int, incidentId: Int) {
        val exists =
            OnCallIncidents
                .selectAll()
                .where {
                    (OnCallIncidents.organizationId eq organizationId) and
                        (OnCallIncidents.id eq incidentId)
                }.limit(1)
                .any()
        if (!exists) throw IncidentCommandNotFoundException("Native incident not found")
    }

    private fun requireMember(organizationId: Int, userId: Int) {
        val exists =
            Memberships
                .selectAll()
                .where {
                    (Memberships.organization_id eq organizationId) and
                        (Memberships.user_id eq userId)
                }.limit(1)
                .any()
        if (!exists) throw IncidentCommandNotFoundException("Incident participant not found")
    }

    private fun publicUserIds(userIds: List<Int>): Map<Int, String> {
        if (userIds.isEmpty()) return emptyMap()
        return Users
            .selectAll()
            .where { Users.id inList userIds.distinct() }
            .associate { row -> row[Users.id] to row[Users.resource_id].toString() }
    }

    private fun roleDefinition(
        row: ResultRow,
        includePrivateInstructions: Boolean = true,
    ): IncidentRoleDefinition =
        IncidentRoleDefinition(
            id = row[NativeIncidentRoleDefinitions.resourceId].toString(),
            key = row[NativeIncidentRoleDefinitions.stableKey],
            version = row[NativeIncidentRoleDefinitions.version],
            name = row[NativeIncidentRoleDefinitions.name],
            description = row[NativeIncidentRoleDefinitions.description],
            responsibilities = json.decodeFromString(row[NativeIncidentRoleDefinitions.responsibilities]),
            privateInstructions = row[NativeIncidentRoleDefinitions.privateInstructions]
                .takeIf { includePrivateInstructions },
            required = row[NativeIncidentRoleDefinitions.isRequired],
            default = row[NativeIncidentRoleDefinitions.isDefault],
        )

    private fun normalizeKey(value: String): String {
        val normalized = value.trim().lowercase().replace(KEY_SEPARATOR, "-").trim('-')
        require(normalized.matches(KEY_PATTERN)) { "Incident role key must use lowercase letters, numbers, or hyphens" }
        return normalized
    }

    private fun String?.cleaned(): String? = this?.trim()?.takeIf(String::isNotEmpty)

    private data class DefaultRole(
        val key: String,
        val name: String,
        val description: String,
        val responsibilities: List<String>,
        val privateInstructions: String,
    )

    private data class RoleInsert(
        val organizationId: Int,
        val actorUserId: Int,
        val key: String,
        val version: Int,
        val name: String,
        val description: String?,
        val responsibilities: List<String>,
        val privateInstructions: String?,
        val required: Boolean,
        val default: Boolean,
        val now: kotlin.time.Instant,
    )

    companion object {
        private const val MAX_ROLE_NAME_LENGTH = 120
        private val KEY_SEPARATOR = Regex("[ _]+")
        private val KEY_PATTERN = Regex("[a-z0-9]+(?:-[a-z0-9]+)*")
        private val json = Json
        private val DEFAULT_ROLES =
            listOf(
                DefaultRole(
                    key = "incident-commander",
                    name = "Incident Commander",
                    description = "Coordinates the response and owns incident-level decisions.",
                    responsibilities =
                        listOf("Set response priorities", "Coordinate responders", "Drive resolution"),
                    privateInstructions =
                        "Keep the response focused, assign owners, and maintain a clear decision log.",
                ),
                DefaultRole(
                    key = "communications-lead",
                    name = "Communications Lead",
                    description = "Owns internal and external incident communications.",
                    responsibilities = listOf("Publish updates", "Track audiences", "Keep messaging consistent"),
                    privateInstructions = "Confirm audience, impact, and approved wording before publishing updates.",
                ),
            )
    }
}
