// Moneat Enterprise - proprietary module
// Copyright (c) 2026 Moneat. All rights reserved.
// See ee/LICENSE for license terms.

package com.moneat.enterprise.rbac

import com.moneat.authz.PermissionBridge
import com.moneat.enterprise.EnterpriseModule
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

    override fun registerRoutes(route: Route) {
        // Role and service-account CRUD routes land in a later phase.
    }

    override fun startBackgroundJobs(application: Application) {
        // No background jobs in this phase.
    }

    override fun stopBackgroundJobs() {
        // No-op.
    }

    // --- PermissionBridge ---
    // Granular permission resolution and service accounts land in a later phase;
    // returning null defers to the coarse ADMIN/MEMBER role gates enforced in core.

    override fun resolvePermissions(organizationId: Int, userId: Int): Set<String>? = null

    override fun hasPermission(organizationId: Int, userId: Int, permission: String): Boolean? = null
}
