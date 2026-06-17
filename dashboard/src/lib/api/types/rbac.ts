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

export interface RbacRole {
  id: string
  name: string
  permissions: string[]
  created_at: string
  updated_at: string
}

export interface CreateRbacRoleRequest {
  name: string
  permissions: string[]
}

export interface UpdateRbacRoleRequest {
  name?: string
  permissions?: string[]
}

export interface RbacRoleAssignment {
  id: string
  role_id: string
  user_id: string
  created_at: string
}
