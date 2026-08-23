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

import {useQuery} from '@tanstack/react-query'
import {api} from '@/lib/api'

const ADMIN_ORG_ROLES: ReadonlySet<string> = new Set(['admin', 'owner'])

export interface OnCallAdmin {
  /** True when the current user may perform admin-only alert route mutations. */
  readonly isAdmin: boolean
  readonly isLoading: boolean
}

/**
 * Whether the current user holds an org role that the backend accepts for
 * admin-only alert route mutations (create, edit, reorder, delete). Read and
 * preview surfaces stay available to every responder; this only gates the write
 * controls. The backend independently enforces the same rule, so this is purely
 * a UI affordance that fails closed while the role is unresolved.
 */
export function useOnCallAdmin(): OnCallAdmin {
  const {data, isLoading} = useQuery({
    queryKey: ['currentUser'],
    queryFn: () => api.getCurrentUser(),
  })
  const role = data?.orgRole?.toLowerCase()
  return {
    isAdmin: role !== undefined && ADMIN_ORG_ROLES.has(role),
    isLoading,
  }
}
