// Moneat Enterprise - proprietary module
// Copyright (c) 2026 Moneat. All rights reserved.
// See ee/LICENSE for license terms.

package com.moneat.enterprise.rbac

import com.moneat.authz.PermissionBridge
import com.moneat.enterprise.EnterpriseModule
import com.moneat.enterprise.rbac.routes.rbacRoutes
import com.moneat.enterprise.rbac.services.RbacService
import io.ktor.server.application.Application
import io.ktor.server.routing.Route

/**
 * Enterprise module for cross-cutting, fine-grained RBAC. Requires the "advanced_rbac"
 * license feature.
 *
 * Implements [PermissionBridge] once for the whole product, so any surface (workflows,
 * incidents, dashboards, …) can resolve granular permissions through
 * `FeatureRegistry.getPermissionBridge()`. RBAC is deliberately its own module and license
 * feature — independent of `workflows_advanced` — so it lights up every consuming surface
 * at once. Without a valid license the module is never loaded and core falls back to the
 * coarse ADMIN/MEMBER org-role gates.
 */
class RbacEnterpriseModule :
    EnterpriseModule,
    PermissionBridge {

    override val name: String = "Advanced RBAC"
    override val licenseFeature: String = "advanced_rbac"

    private val service = RbacService()

    override fun registerRoutes(route: Route) {
        route.rbacRoutes(service)
    }

    override fun startBackgroundJobs(application: Application) {
        // No background jobs in this phase.
    }

    override fun stopBackgroundJobs() {
        // No-op.
    }

    // --- PermissionBridge ---
    // Resolution is additive: a user with at least one role assignment is governed by the
    // union of those roles' permission keys; a user with none resolves to null so the caller
    // falls back to the coarse ADMIN/MEMBER role gates.

    override fun resolvePermissions(organizationId: Int, userId: Int): Set<String>? =
        service.resolvePermissions(organizationId, userId)

    override fun hasPermission(organizationId: Int, userId: Int, permission: String): Boolean? =
        service.hasPermission(organizationId, userId, permission)
}
