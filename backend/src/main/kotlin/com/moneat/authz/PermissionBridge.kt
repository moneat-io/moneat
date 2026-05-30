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

package com.moneat.authz

/**
 * Cross-cutting bridge for fine-grained, per-permission RBAC.
 *
 * RBAC is product-wide, not tied to any single feature: workflows, incidents, dashboards
 * and other surfaces all gate through this one bridge. Core ships coarse org-role gates
 * (read = MEMBER, write/run = ADMIN via `OrgMembershipService`); when the Enterprise RBAC
 * module (`advanced_rbac`) is licensed it provides this bridge to resolve granular
 * per-user permissions that override the coarse checks.
 *
 * Permission keys are owned by each domain (e.g. [com.moneat.workflows.WorkflowPermissions])
 * as namespaced `"<resource>:<action>"` strings, so the bridge itself stays generic.
 * Callers consult the bridge optionally and fall back to the coarse role gates when the
 * bridge is absent (no license) or returns null (cannot resolve).
 */
interface PermissionBridge {

    /**
     * Resolve the set of granular permission keys granted to the user in the organization,
     * or null if the bridge cannot resolve them (the caller should then fall back to coarse
     * role gates).
     */
    fun resolvePermissions(organizationId: Int, userId: Int): Set<String>?

    /**
     * Whether the user holds [permission] (a `"<resource>:<action>"` key) in the
     * organization. Returns null when the bridge cannot decide and the caller should fall
     * back to role gates.
     */
    fun hasPermission(organizationId: Int, userId: Int, permission: String): Boolean?
}
