// Moneat - observability platform
// Copyright (C) 2026 Moneat
//
// This program is free software: you can redistribute it and/or modify
// it under the terms of the GNU Affero General Public License as published by
// the Free Software Foundation, either version 3 of the License, or
// (at your option) any later version.
//
// This program is distributed in the hope that it will be useful,
// but WITHOUT ANY WARRANTY; without even the implied warranty of
// MERCHANTABILITY or FITNESS FOR A PARTICULAR PURPOSE. See the
// GNU Affero General Public License for more details.
//
// You should have received a copy of the GNU Affero General Public License
// along with this program. If not, see <https://www.gnu.org/licenses/>.

package com.moneat.workflows

/**
 * Bridge interface for the workflow connection/secret vault.
 *
 * The vault stores third-party credentials (connection secrets) used by workflow
 * actions and premium connectors. Core code defines this seam; the Enterprise
 * module (`workflows_advanced`) provides the real implementation via the
 * [com.moneat.enterprise.FeatureRegistry]. When no license is present the bridge
 * is absent and connection surfaces degrade gracefully to "requires Enterprise".
 *
 * Security contract:
 *  - Secrets are NEVER returned by the management API. Listing/get only expose
 *    type, name, identifier tags and the last four characters ([WorkflowConnectionSummary]).
 *  - Plaintext secrets are only ever produced by [resolveSecret], which must be
 *    called exclusively from the trusted connection-resolution activity and never
 *    from a request handler, log path, or the untrusted egress worker.
 */
interface WorkflowConnectionVault {

    /** List the org's connections without exposing any secret material. */
    suspend fun listConnections(organizationId: Int): List<WorkflowConnectionSummary>

    /** Fetch a single connection (no secret material) or null if not found in the org. */
    suspend fun getConnection(organizationId: Int, connectionId: Int): WorkflowConnectionSummary?

    /**
     * Create a connection. The secret is entered once, immediately encrypted with
     * envelope encryption and never returned. Returns the redacted summary.
     */
    suspend fun createConnection(
        organizationId: Int,
        type: String,
        name: String,
        identifierTags: Map<String, String>,
        secret: String,
        createdBy: Int?
    ): WorkflowConnectionSummary

    /**
     * Replace the secret of an existing connection (rotation). Returns the updated
     * redacted summary, or null if the connection does not exist in the org.
     */
    suspend fun rotateConnection(
        organizationId: Int,
        connectionId: Int,
        secret: String
    ): WorkflowConnectionSummary?

    /** Delete a connection. Returns true if a row was removed. */
    suspend fun deleteConnection(organizationId: Int, connectionId: Int): Boolean

    /** List the org's connection groups. */
    suspend fun listGroups(organizationId: Int): List<WorkflowConnectionGroupSummary>

    /** Create a connection group whose members are resolved by identifier tags at run time. */
    suspend fun createGroup(
        organizationId: Int,
        name: String,
        connectionType: String,
        memberConnectionIds: List<Int>,
        selectionStrategy: String,
        createdBy: Int?
    ): WorkflowConnectionGroupSummary

    /** Delete a connection group. Returns true if a row was removed. */
    suspend fun deleteGroup(organizationId: Int, groupId: Int): Boolean

    /**
     * Resolve a connection reference to its plaintext secret for use INSIDE the
     * trusted connection-resolution activity only. A group reference is resolved to
     * a concrete connection by matching the group members' identifier tags against
     * the run scope. Returns null when nothing matches or the org does not own it.
     *
     * WARNING: the returned [WorkflowResolvedConnection] carries plaintext — never
     * serialize it to an API response, a log, or Temporal workflow history.
     */
    suspend fun resolveSecret(
        organizationId: Int,
        reference: WorkflowConnectionReference,
        runScope: Map<String, String>
    ): WorkflowResolvedConnection?
}

/** Redacted view of a connection — safe to serialize to API responses. */
data class WorkflowConnectionSummary(
    val id: Int,
    val organizationId: Int,
    val type: String,
    val name: String,
    val identifierTags: Map<String, String>,
    val lastFour: String?,
    val createdAt: String,
    val updatedAt: String
)

/** Redacted view of a connection group — safe to serialize to API responses. */
data class WorkflowConnectionGroupSummary(
    val id: Int,
    val organizationId: Int,
    val name: String,
    val connectionType: String,
    val memberConnectionIds: List<Int>,
    val selectionStrategy: String,
    val createdAt: String,
    val updatedAt: String
)

/** A reference from a workflow action to a vaulted connection or connection group. */
sealed interface WorkflowConnectionReference {
    data class Connection(val connectionId: Int) : WorkflowConnectionReference
    data class Group(val groupId: Int) : WorkflowConnectionReference
}

/**
 * A resolved connection carrying plaintext secret material. Produced only by
 * [WorkflowConnectionVault.resolveSecret] inside the trusted activity.
 */
data class WorkflowResolvedConnection(
    val connectionId: Int,
    val type: String,
    val secret: String
)
