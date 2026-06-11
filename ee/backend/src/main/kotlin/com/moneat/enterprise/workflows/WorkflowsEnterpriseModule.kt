// Moneat Enterprise - proprietary module
// Copyright (c) 2026 Moneat. All rights reserved.
// See ee/LICENSE for license terms.

package com.moneat.enterprise.workflows

import com.moneat.enterprise.EnterpriseModule
import com.moneat.enterprise.workflows.crypto.ConnectionCredentialCipher
import com.moneat.enterprise.workflows.routes.approvalRoutes
import com.moneat.enterprise.workflows.routes.connectionRoutes
import com.moneat.enterprise.workflows.services.ConnectionVaultService
import com.moneat.enterprise.workflows.services.PremiumConnectorService
import com.moneat.enterprise.workflows.services.WorkflowApprovalService
import com.moneat.workflows.WorkflowApprovalBridge
import com.moneat.workflows.WorkflowConnectionGroupSummary
import com.moneat.workflows.WorkflowConnectionReference
import com.moneat.workflows.WorkflowConnectionSummary
import com.moneat.workflows.WorkflowConnectionVault
import com.moneat.workflows.WorkflowPremiumConnectorBridge
import com.moneat.workflows.WorkflowResolvedConnection
import com.moneat.workflows.engine.temporal.WorkflowApprovalRequestInput
import com.moneat.workflows.engine.temporal.WorkflowApprovalRequestResult
import kotlinx.serialization.json.JsonElement
import io.ktor.server.application.Application
import io.ktor.server.routing.Route

/**
 * Enterprise module for advanced workflow capabilities. This phase ships the
 * connection/secret vault. Requires the "workflows_advanced" license feature.
 *
 * Implements [WorkflowConnectionVault] so core can reach the vault through
 * `FeatureRegistry`. Without a valid license the module is never loaded and core degrades
 * gracefully (connection surfaces show as Enterprise). RBAC is cross-cutting and ships in
 * its own `advanced_rbac` module ([com.moneat.enterprise.rbac.RbacEnterpriseModule]).
 */
class WorkflowsEnterpriseModule :
    EnterpriseModule,
    WorkflowConnectionVault,
    WorkflowApprovalBridge,
    WorkflowPremiumConnectorBridge {

    override val name: String = "Workflows Advanced"
    override val licenseFeature: String = "workflows_advanced"

    // Lazy so module discovery never forces KEK resolution; the cipher is built on
    // first use (a misconfigured KEK then fails the request, not app startup).
    private val vault: WorkflowConnectionVault by lazy {
        ConnectionVaultService(ConnectionCredentialCipher.fromEnv())
    }
    private val approvals: WorkflowApprovalService by lazy { WorkflowApprovalService() }
    private val connectors: WorkflowPremiumConnectorBridge by lazy { PremiumConnectorService(this) }

    override fun registerRoutes(route: Route) {
        route.connectionRoutes(this)
        route.approvalRoutes(approvals)
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

    override suspend fun resolveConnectionId(
        organizationId: Int,
        connectionResourceId: String
    ): Int? =
        vault.resolveConnectionId(organizationId, connectionResourceId)

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

    override suspend fun resolveGroupId(
        organizationId: Int,
        groupResourceId: String
    ): Int? =
        vault.resolveGroupId(organizationId, groupResourceId)

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

    // --- WorkflowApprovalBridge ---

    override suspend fun requestApproval(input: WorkflowApprovalRequestInput): WorkflowApprovalRequestResult =
        approvals.requestApproval(input)

    // --- WorkflowPremiumConnectorBridge ---

    override suspend fun executeConnectorAction(
        organizationId: Int,
        actionName: String,
        params: Map<String, String>,
        actorUserId: Int?
    ): Map<String, JsonElement> =
        connectors.executeConnectorAction(organizationId, actionName, params, actorUserId)
}
