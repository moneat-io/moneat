// Moneat Enterprise - proprietary module
// Copyright (c) 2026 Moneat. All rights reserved.
// See ee/LICENSE for license terms.

package com.moneat.enterprise.workflows

import com.moneat.enterprise.EnterpriseModule
import com.moneat.enterprise.workflows.crypto.ConnectionCredentialCipher
import com.moneat.enterprise.workflows.routes.connectionRoutes
import com.moneat.enterprise.workflows.services.ConnectionVaultService
import com.moneat.workflows.WorkflowConnectionGroupSummary
import com.moneat.workflows.WorkflowConnectionReference
import com.moneat.workflows.WorkflowConnectionSummary
import com.moneat.workflows.WorkflowConnectionVault
import com.moneat.workflows.WorkflowPermission
import com.moneat.workflows.WorkflowRbacBridge
import com.moneat.workflows.WorkflowResolvedConnection
import io.ktor.server.application.Application
import io.ktor.server.routing.Route

/**
 * Enterprise module for advanced workflow capabilities. This phase ships the
 * connection/secret vault. Requires the "workflows_advanced" license feature.
 *
 * Implements [WorkflowConnectionVault] and [WorkflowRbacBridge] so core can reach
 * these capabilities through `FeatureRegistry`. Without a valid license the module is
 * never loaded and core degrades gracefully (connection surfaces show as Enterprise).
 */
class WorkflowsEnterpriseModule :
    EnterpriseModule,
    WorkflowConnectionVault,
    WorkflowRbacBridge {

    override val name: String = "Workflows Advanced"
    override val licenseFeature: String = "workflows_advanced"

    // Lazy so module discovery never forces KEK resolution; the cipher is built on
    // first use (a misconfigured KEK then fails the request, not app startup).
    private val vault: WorkflowConnectionVault by lazy {
        ConnectionVaultService(ConnectionCredentialCipher.fromEnv())
    }

    override fun registerRoutes(route: Route) {
        route.connectionRoutes(this)
    }

    override fun startBackgroundJobs(application: Application) {
        // No background jobs in this phase.
    }

    override fun stopBackgroundJobs() {
        // No-op.
    }

    // --- WorkflowConnectionVault (delegated to the vault service) ---

    override suspend fun listConnections(organizationId: Int): List<WorkflowConnectionSummary> =
        vault.listConnections(organizationId)

    override suspend fun getConnection(organizationId: Int, connectionId: Int): WorkflowConnectionSummary? =
        vault.getConnection(organizationId, connectionId)

    override suspend fun createConnection(
        organizationId: Int,
        type: String,
        name: String,
        identifierTags: Map<String, String>,
        secret: String,
        createdBy: Int?
    ): WorkflowConnectionSummary =
        vault.createConnection(organizationId, type, name, identifierTags, secret, createdBy)

    override suspend fun rotateConnection(
        organizationId: Int,
        connectionId: Int,
        secret: String
    ): WorkflowConnectionSummary? =
        vault.rotateConnection(organizationId, connectionId, secret)

    override suspend fun deleteConnection(organizationId: Int, connectionId: Int): Boolean =
        vault.deleteConnection(organizationId, connectionId)

    override suspend fun listGroups(organizationId: Int): List<WorkflowConnectionGroupSummary> =
        vault.listGroups(organizationId)

    override suspend fun createGroup(
        organizationId: Int,
        name: String,
        connectionType: String,
        memberConnectionIds: List<Int>,
        selectionStrategy: String,
        createdBy: Int?
    ): WorkflowConnectionGroupSummary =
        vault.createGroup(organizationId, name, connectionType, memberConnectionIds, selectionStrategy, createdBy)

    override suspend fun deleteGroup(organizationId: Int, groupId: Int): Boolean =
        vault.deleteGroup(organizationId, groupId)

    override suspend fun resolveSecret(
        organizationId: Int,
        reference: WorkflowConnectionReference,
        runScope: Map<String, String>
    ): WorkflowResolvedConnection? =
        vault.resolveSecret(organizationId, reference, runScope)

    // --- WorkflowRbacBridge ---
    // Granular permission resolution and service accounts land in a later phase;
    // returning null defers to the coarse ADMIN/MEMBER role gates enforced in core.

    override fun resolvePermissions(organizationId: Int, userId: Int): Set<String>? = null

    override fun hasPermission(organizationId: Int, userId: Int, permission: WorkflowPermission): Boolean? = null
}
