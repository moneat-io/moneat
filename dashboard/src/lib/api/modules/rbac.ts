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

import type {ApiClientCore} from '../client'
import type {
  CreateRbacRoleRequest,
  RbacRole,
  RbacRoleAssignment,
  UpdateRbacRoleRequest,
} from '../types'

export function rbacMethods(core: ApiClientCore) {
  const base = core.API_BASE

  return {
    getRbacRoles: () =>
      core.request<RbacRole[]>(`${base}/rbac/roles`),

    createRbacRole: (request: CreateRbacRoleRequest) =>
      core.request<RbacRole>(`${base}/rbac/roles`, {
        method: 'POST',
        body: JSON.stringify(request),
      }),

    updateRbacRole: (roleId: string, request: UpdateRbacRoleRequest) =>
      core.request<RbacRole>(`${base}/rbac/roles/${encodeURIComponent(roleId)}`, {
        method: 'PUT',
        body: JSON.stringify(request),
      }),

    deleteRbacRole: (roleId: string) =>
      core.request<void>(`${base}/rbac/roles/${encodeURIComponent(roleId)}`, {
        method: 'DELETE',
      }),

    getRbacRoleAssignments: (roleId: string) =>
      core.request<RbacRoleAssignment[]>(
        `${base}/rbac/roles/${encodeURIComponent(roleId)}/assignments`
      ),

    assignRbacRole: (roleId: string, userId: string) =>
      core.request<RbacRoleAssignment>(`${base}/rbac/roles/${encodeURIComponent(roleId)}/assignments`, {
        method: 'POST',
        body: JSON.stringify({user_id: userId}),
      }),

    unassignRbacRole: (roleId: string, userId: string) =>
      core.request<void>(
        `${base}/rbac/roles/${encodeURIComponent(roleId)}/assignments/${encodeURIComponent(userId)}`,
        {
        method: 'DELETE',
        }
      ),
  }
}
