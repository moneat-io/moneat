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
 * Bridge interface for fine-grained workflow RBAC.
 *
 * Core ships coarse role gates (read = MEMBER, write/run = ADMIN). When the
 * Enterprise module (`workflows_advanced`) is licensed it provides this bridge to
 * resolve granular per-user permissions that override the coarse checks. Core code
 * consults the bridge optionally and falls back to the coarse role gates when the
 * bridge is absent (no license) or returns null (cannot resolve).
 */
interface WorkflowRbacBridge {

    /**
     * Resolve the set of granular permissions ([WorkflowPermission.key] values)
     * granted to the user in the organization, or null if the bridge cannot
     * resolve them (the caller should then fall back to coarse role gates).
     */
    fun resolvePermissions(organizationId: Int, userId: Int): Set<String>?

    /**
     * Whether the user holds the given permission in the organization. Returns null
     * when the bridge cannot decide and the caller should fall back to role gates.
     */
    fun hasPermission(organizationId: Int, userId: Int, permission: WorkflowPermission): Boolean?
}

/** Granular workflow permissions resolved by the Enterprise RBAC bridge. */
enum class WorkflowPermission(val key: String) {
    WORKFLOWS_READ("workflows_read"),
    WORKFLOWS_WRITE("workflows_write"),
    WORKFLOWS_RUN("workflows_run"),
    CONNECTIONS_READ("connections_read"),
    CONNECTIONS_WRITE("connections_write"),
    CONNECTIONS_RESOLVE("connections_resolve")
}
