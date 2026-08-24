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
import {API_BASE} from '@/lib/api/client'
import type {NativeIncidentCapabilities} from '@/lib/api/types'

export const nativeIncidentCapabilitiesQueryKey = (organizationId: string) =>
  ['native-incident-capabilities', API_BASE, organizationId] as const

export interface NativeIncidentCapabilitiesState {
  readonly enabled: boolean
  readonly isLoading: boolean
  readonly isError: boolean
  readonly data?: NativeIncidentCapabilities
}

/** Resolve the current organization's native incident entitlement and quotas. */
export function useNativeIncidentCapabilities(): NativeIncidentCapabilitiesState {
  const currentUser = useQuery({
    queryKey: ['currentUser'],
    queryFn: () => api.getCurrentUser(),
  })
  const organizationId = currentUser.data?.orgId
  const capability = useQuery({
    queryKey: nativeIncidentCapabilitiesQueryKey(organizationId ?? 'unresolved'),
    queryFn: () => api.getNativeIncidentCapabilities(),
    enabled: organizationId !== undefined && !currentUser.isFetching,
  })
  return {
    enabled: capability.data?.enabled === true,
    isLoading: currentUser.isLoading || currentUser.isFetching || capability.isLoading,
    isError: currentUser.isError || capability.isError,
    data: capability.data,
  }
}

export function nativeIncidentUnavailableCopy(
  capabilities: Pick<NativeIncidentCapabilitiesState, 'isError' | 'data'>,
): {title: string; description: string} {
  if (capabilities.isError) {
    return {
      title: 'Incident response is temporarily unavailable',
      description:
        'We could not confirm incident response availability for your organization. This is usually temporary — try again in a moment.',
    }
  }
  return {
    title: 'Incident response is not available',
    description:
      capabilities.data?.entitlementReason ??
      'Your organization plan or license does not include native incident response.',
  }
}
