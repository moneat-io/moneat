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
import type {NativeIncidentCapabilities, NativeIncidentRolloutState} from '@/lib/api/types'

export const nativeIncidentRolloutQueryKey = (organizationId: string) =>
  ['native-incident-rollout', API_BASE, organizationId] as const

export interface NativeIncidentRollout {
  /** True once the capability has loaded and native incident response is enabled. */
  readonly enabled: boolean
  /** True while the capability decision is still being resolved. */
  readonly isLoading: boolean
  readonly isError: boolean
  readonly state?: NativeIncidentRolloutState
  readonly environment?: string
  readonly data?: NativeIncidentCapabilities
}

/**
 * Whether native incident dashboard surfaces and actions may be shown for the
 * current organization. The backend independently enforces the rollout; this
 * hook only gates the UI so disabled organizations never invoke native incident
 * controls. Callers should hold their own native incident/config queries with
 * `enabled: rollout.enabled` so nothing fires while loading or disabled.
 *
 * The 30s stale window matches the backend's cached rollout convergence window.
 */
export function useNativeIncidentRollout(): NativeIncidentRollout {
  const currentUser = useQuery({
    queryKey: ['currentUser'],
    queryFn: () => api.getCurrentUser(),
  })
  const organizationId = currentUser.data?.orgId
  const capability = useQuery({
    queryKey: nativeIncidentRolloutQueryKey(organizationId ?? 'unresolved'),
    queryFn: () => api.getNativeIncidentCapabilities(),
    enabled: organizationId !== undefined && !currentUser.isFetching,
    staleTime: 30_000,
  })
  const isLoading = currentUser.isLoading || currentUser.isFetching || capability.isLoading
  const isError = currentUser.isError || capability.isError
  return {
    enabled: capability.data?.enabled === true,
    isLoading,
    isError,
    state: capability.data?.state,
    environment: capability.data?.environment,
    data: capability.data,
  }
}

/**
 * Calm, source-neutral copy for the native incident unavailable state. Transient
 * failures invite a retry; a deliberate disable points at the administrator.
 */
export function nativeIncidentUnavailableCopy(
  rollout: Pick<NativeIncidentRollout, 'state' | 'isError'>
): {title: string; description: string} {
  const transient =
    rollout.isError ||
    rollout.state === 'EVALUATION_ERROR' ||
    rollout.state === 'PROVIDER_UNAVAILABLE'
  if (transient) {
    return {
      title: 'Incident response is temporarily unavailable',
      description:
        'We could not confirm incident response availability for your organization. This is usually temporary — try again in a moment.',
    }
  }
  return {
    title: 'Incident response is not available',
    description:
      'Native incident response is not enabled for your organization in this environment. Contact your administrator to request access.',
  }
}
