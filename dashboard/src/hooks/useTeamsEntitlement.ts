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

export interface TeamsEntitlement {
  /** True once usage has loaded and the plan includes the Teams feature. */
  readonly enabled: boolean
  readonly isLoading: boolean
  readonly isError: boolean
}

export const BILLING_USAGE_QUERY_KEY = ['billingUsage'] as const

/**
 * Whether on-call Teams (team-based ownership) is available on the org's plan.
 * Teams is gated to the TEAM tier and above; the backend also enforces this, so
 * this hook only drives the locked/upgrade affordances in the UI.
 */
export function useTeamsEntitlement(): TeamsEntitlement {
  const {data, isLoading, isError} = useQuery({
    queryKey: BILLING_USAGE_QUERY_KEY,
    queryFn: () => api.getBillingUsage(),
    staleTime: 60_000,
  })
  return {
    enabled: data?.teamsEnabled === true,
    isLoading,
    isError,
  }
}
