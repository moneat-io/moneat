// Moneat Enterprise - proprietary module
// Copyright (c) 2026 Moneat. All rights reserved.
// See ee/LICENSE for license terms.

package com.moneat.enterprise.workflows.services

import com.moneat.enterprise.workflows.crypto.ConnectionCredentialCipher
import com.moneat.enterprise.workflows.models.ConnectionSelectionRule
import com.moneat.enterprise.workflows.models.WorkflowConnectionGroups
import com.moneat.enterprise.workflows.models.WorkflowConnections
import com.moneat.workflows.WorkflowConnectionGroupSummary
import com.moneat.workflows.WorkflowConnectionReference
import com.moneat.workflows.WorkflowConnectionSummary
import com.moneat.workflows.WorkflowConnectionVault
import com.moneat.workflows.WorkflowResolvedConnection
import kotlinx.serialization.decodeFromString
import kotlinx.serialization.encodeToString
import kotlinx.serialization.json.Json
import org.jetbrains.exposed.v1.core.ResultRow
import org.jetbrains.exposed.v1.core.and
import org.jetbrains.exposed.v1.core.eq
import org.jetbrains.exposed.v1.core.inList
import org.jetbrains.exposed.v1.exceptions.ExposedSQLException
import org.jetbrains.exposed.v1.jdbc.deleteWhere
import org.jetbrains.exposed.v1.jdbc.insertAndGetId
import org.jetbrains.exposed.v1.jdbc.selectAll
import org.jetbrains.exposed.v1.jdbc.transactions.transaction
import org.jetbrains.exposed.v1.jdbc.update
import kotlin.time.Clock

private const val UNIQUE_VIOLATION_SQL_STATE = "23505"
private const val LAST_FOUR_LENGTH = 4
private const val MAX_CONNECTION_TYPE_LENGTH = 64
private const val MAX_CONNECTION_NAME_LENGTH = 255
private const val DEFAULT_SELECTION_STRATEGY = "first_match"

/**
 * Postgres-backed implementation of the connection vault. Secrets are encrypted via
 * [ConnectionCredentialCipher] before they touch the database and are only decrypted
 * inside [resolveSecret] (invoked from the trusted connection-resolution activity).
 * Every query is scoped by `organizationId` taken from the authenticated caller / run.
 */
class ConnectionVaultService(
    private val cipher: ConnectionCredentialCipher
) : WorkflowConnectionVault {

    private val json = Json { ignoreUnknownKeys = true }

    override suspend fun listConnections(organizationId: Int): List<WorkflowConnectionSummary> =
        transaction {
            WorkflowConnections
                .selectAll()
                .where { WorkflowConnections.organizationId eq organizationId }
                .map { it.toConnectionSummary() }
        }

    override suspend fun getConnection(organizationId: Int, connectionId: Int): WorkflowConnectionSummary? =
        transaction { findConnectionRow(organizationId, connectionId)?.toConnectionSummary() }

    override suspend fun createConnection(
        organizationId: Int,
        type: String,
        name: String,
        identifierTags: Map<String, String>,
        secret: String,
        createdBy: Int?
    ): WorkflowConnectionSummary {
        val normalizedType = normalizeConnectionType(type)
        val normalizedName = normalizeConnectionName(name)
        require(secret.isNotEmpty()) { "Connection secret is required" }
        val now = Clock.System.now()
        val envelope = cipher.encrypt(secret, organizationId)
        val newId =
            try {
                transaction {
                    WorkflowConnections.insertAndGetId {
                        it[WorkflowConnections.organizationId] = organizationId
                        it[WorkflowConnections.type] = normalizedType
                        it[WorkflowConnections.name] = normalizedName
                        it[WorkflowConnections.identifierTags] = json.encodeToString(identifierTags)
                        it[WorkflowConnections.encryptedCredentials] = envelope
                        it[WorkflowConnections.keyId] = cipher.activeKeyId
                        it[WorkflowConnections.lastFour] = secret.lastFourOrNull()
                        it[WorkflowConnections.createdBy] = createdBy
                        it[WorkflowConnections.createdAt] = now
                        it[WorkflowConnections.updatedAt] = now
                    }.value
                }
            } catch (e: ExposedSQLException) {
                if (e.sqlState == UNIQUE_VIOLATION_SQL_STATE) {
                    throw IllegalArgumentException("A connection named '$normalizedName' already exists")
                }
                throw e
            }
        return checkNotNull(getConnection(organizationId, newId))
    }

    override suspend fun rotateConnection(
        organizationId: Int,
        connectionId: Int,
        secret: String
    ): WorkflowConnectionSummary? {
        require(secret.isNotEmpty()) { "Connection secret is required" }
        val now = Clock.System.now()
        val envelope = cipher.encrypt(secret, organizationId)
        val updated =
            transaction {
                WorkflowConnections.update({
                    (WorkflowConnections.id eq connectionId) and
                        (WorkflowConnections.organizationId eq organizationId)
                }) {
                    it[WorkflowConnections.encryptedCredentials] = envelope
                    it[WorkflowConnections.keyId] = cipher.activeKeyId
                    it[WorkflowConnections.lastFour] = secret.lastFourOrNull()
                    it[WorkflowConnections.updatedAt] = now
                }
            }
        return if (updated > 0) getConnection(organizationId, connectionId) else null
    }

    override suspend fun deleteConnection(organizationId: Int, connectionId: Int): Boolean =
        transaction {
            WorkflowConnections.deleteWhere {
                (WorkflowConnections.id eq connectionId) and
                    (WorkflowConnections.organizationId eq organizationId)
            } > 0
        }

    override suspend fun listGroups(organizationId: Int): List<WorkflowConnectionGroupSummary> =
        transaction {
            WorkflowConnectionGroups
                .selectAll()
                .where { WorkflowConnectionGroups.organizationId eq organizationId }
                .map { it.toGroupSummary() }
        }

    override suspend fun createGroup(
        organizationId: Int,
        name: String,
        connectionType: String,
        memberConnectionIds: List<Int>,
        selectionStrategy: String,
        createdBy: Int?
    ): WorkflowConnectionGroupSummary {
        val normalizedName = normalizeGroupName(name)
        val normalizedConnectionType = normalizeConnectionType(connectionType)
        val normalizedSelectionStrategy = normalizeSelectionStrategy(selectionStrategy)
        val now = Clock.System.now()
        val newId =
            try {
                transaction {
                    // Keep only members that belong to the org and share the group's connection type.
                    val validMembers =
                        if (memberConnectionIds.isEmpty()) {
                            emptyList()
                        } else {
                            val requestedMemberIds = memberConnectionIds.distinct()
                            val validMemberIds = WorkflowConnections
                                .selectAll()
                                .where {
                                    (WorkflowConnections.organizationId eq organizationId) and
                                        (WorkflowConnections.type eq normalizedConnectionType) and
                                        (WorkflowConnections.id inList requestedMemberIds)
                                }
                                .map { it[WorkflowConnections.id].value }
                                .toSet()
                            requestedMemberIds.filter { it in validMemberIds }
                        }
                    WorkflowConnectionGroups.insertAndGetId {
                        it[WorkflowConnectionGroups.organizationId] = organizationId
                        it[WorkflowConnectionGroups.name] = normalizedName
                        it[WorkflowConnectionGroups.connectionType] = normalizedConnectionType
                        it[WorkflowConnectionGroups.memberConnectionIds] = json.encodeToString(validMembers)
                        it[WorkflowConnectionGroups.selectionRule] =
                            json.encodeToString(ConnectionSelectionRule(normalizedSelectionStrategy))
                        it[WorkflowConnectionGroups.createdBy] = createdBy
                        it[WorkflowConnectionGroups.createdAt] = now
                        it[WorkflowConnectionGroups.updatedAt] = now
                    }.value
                }
            } catch (e: ExposedSQLException) {
                if (e.sqlState == UNIQUE_VIOLATION_SQL_STATE) {
                    throw IllegalArgumentException("A connection group named '$normalizedName' already exists")
                }
                throw e
            }
        return checkNotNull(
            transaction { findGroupRow(organizationId, newId)?.toGroupSummary() }
        )
    }

    override suspend fun deleteGroup(organizationId: Int, groupId: Int): Boolean =
        transaction {
            WorkflowConnectionGroups.deleteWhere {
                (WorkflowConnectionGroups.id eq groupId) and
                    (WorkflowConnectionGroups.organizationId eq organizationId)
            } > 0
        }

    override suspend fun resolveSecret(
        organizationId: Int,
        reference: WorkflowConnectionReference,
        runScope: Map<String, String>
    ): WorkflowResolvedConnection? =
        transaction {
            val connectionId =
                when (reference) {
                    is WorkflowConnectionReference.Connection -> reference.connectionId
                    is WorkflowConnectionReference.Group ->
                        resolveGroupMember(organizationId, reference.groupId, runScope)
                } ?: return@transaction null

            findConnectionRow(organizationId, connectionId)?.let { row ->
                WorkflowResolvedConnection(
                    connectionId = row[WorkflowConnections.id].value,
                    type = row[WorkflowConnections.type],
                    secret = cipher.decrypt(row[WorkflowConnections.encryptedCredentials], organizationId)
                )
            }
        }

    private fun resolveGroupMember(
        organizationId: Int,
        groupId: Int,
        runScope: Map<String, String>
    ): Int? {
        val group = findGroupRow(organizationId, groupId) ?: return null
        val memberIds =
            json.decodeFromString<List<Int>>(group[WorkflowConnectionGroups.memberConnectionIds])
        if (memberIds.isEmpty()) return null

        val identifierTagsById =
            WorkflowConnections
                .selectAll()
                .where {
                    (WorkflowConnections.organizationId eq organizationId) and
                        (WorkflowConnections.id inList memberIds)
                }
                .associate { row ->
                    row[WorkflowConnections.id].value to
                        json.decodeFromString<Map<String, String>>(row[WorkflowConnections.identifierTags])
                }
        return ConnectionGroupResolver.firstMatch(memberIds, identifierTagsById, runScope)
    }

    private fun findConnectionRow(organizationId: Int, connectionId: Int): ResultRow? =
        WorkflowConnections
            .selectAll()
            .where {
                (WorkflowConnections.id eq connectionId) and
                    (WorkflowConnections.organizationId eq organizationId)
            }
            .firstOrNull()

    private fun findGroupRow(organizationId: Int, groupId: Int): ResultRow? =
        WorkflowConnectionGroups
            .selectAll()
            .where {
                (WorkflowConnectionGroups.id eq groupId) and
                    (WorkflowConnectionGroups.organizationId eq organizationId)
            }
            .firstOrNull()

    private fun ResultRow.toConnectionSummary(): WorkflowConnectionSummary =
        WorkflowConnectionSummary(
            id = this[WorkflowConnections.id].value,
            organizationId = this[WorkflowConnections.organizationId],
            type = this[WorkflowConnections.type],
            name = this[WorkflowConnections.name],
            identifierTags = json.decodeFromString(this[WorkflowConnections.identifierTags]),
            lastFour = this[WorkflowConnections.lastFour],
            createdAt = this[WorkflowConnections.createdAt].toString(),
            updatedAt = this[WorkflowConnections.updatedAt].toString()
        )

    private fun ResultRow.toGroupSummary(): WorkflowConnectionGroupSummary =
        WorkflowConnectionGroupSummary(
            id = this[WorkflowConnectionGroups.id].value,
            organizationId = this[WorkflowConnectionGroups.organizationId],
            name = this[WorkflowConnectionGroups.name],
            connectionType = this[WorkflowConnectionGroups.connectionType],
            memberConnectionIds = json.decodeFromString(this[WorkflowConnectionGroups.memberConnectionIds]),
            selectionStrategy = json.decodeFromString<ConnectionSelectionRule>(
                this[WorkflowConnectionGroups.selectionRule]
            ).strategy,
            createdAt = this[WorkflowConnectionGroups.createdAt].toString(),
            updatedAt = this[WorkflowConnectionGroups.updatedAt].toString()
        )

    private fun String.lastFourOrNull(): String? =
        if (length >= LAST_FOUR_LENGTH) takeLast(LAST_FOUR_LENGTH) else null

    private fun normalizeConnectionType(type: String): String {
        val normalizedType = type.trim()
        require(normalizedType.isNotBlank()) { "Connection type is required" }
        require(normalizedType.length <= MAX_CONNECTION_TYPE_LENGTH) { "Connection type is too long" }
        return normalizedType
    }

    private fun normalizeConnectionName(name: String): String {
        val normalizedName = name.trim()
        require(normalizedName.isNotBlank()) { "Connection name is required" }
        require(normalizedName.length <= MAX_CONNECTION_NAME_LENGTH) { "Connection name is too long" }
        return normalizedName
    }

    private fun normalizeGroupName(name: String): String {
        val normalizedName = name.trim()
        require(normalizedName.isNotBlank()) { "Group name is required" }
        require(normalizedName.length <= MAX_CONNECTION_NAME_LENGTH) { "Group name is too long" }
        return normalizedName
    }

    private fun normalizeSelectionStrategy(selectionStrategy: String): String {
        val normalizedSelectionStrategy = selectionStrategy.trim()
        require(normalizedSelectionStrategy == DEFAULT_SELECTION_STRATEGY) {
            "Unsupported connection group selection strategy"
        }
        return normalizedSelectionStrategy
    }
}
