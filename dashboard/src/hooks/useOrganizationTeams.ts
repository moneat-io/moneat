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

import {useQuery, type UseQueryResult} from '@tanstack/react-query'
import {api, type OrganizationTeam} from '@/lib/api'

/** Shared cache key so the Teams view and the catalog ownership tab stay in sync. */
export const ORG_TEAMS_QUERY_KEY = ['org-teams'] as const

/**
 * Organization teams (team-based ownership). The list endpoint is gated to plans
 * that include Teams; callers should treat a 403 as "feature unavailable" rather
 * than an error, via the `enabled` flag (e.g. only fetch once entitled).
 */
export function useOrganizationTeams(
  options: {enabled?: boolean} = {}
): UseQueryResult<OrganizationTeam[]> {
  return useQuery({
    queryKey: ORG_TEAMS_QUERY_KEY,
    queryFn: () => api.getOrganizationTeams(),
    staleTime: 30_000,
    enabled: options.enabled ?? true,
  })
}
