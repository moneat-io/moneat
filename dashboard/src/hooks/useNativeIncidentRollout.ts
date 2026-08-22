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
import type {NativeIncidentCapabilities, NativeIncidentRolloutState} from '@/lib/api/types'

export const NATIVE_INCIDENT_ROLLOUT_QUERY_KEY = ['native-incident-rollout'] as const

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
  const {data, isLoading, isError} = useQuery({
    queryKey: NATIVE_INCIDENT_ROLLOUT_QUERY_KEY,
    queryFn: () => api.getNativeIncidentCapabilities(),
    staleTime: 30_000,
  })
  return {
    enabled: data?.enabled === true,
    isLoading,
    isError,
    state: data?.state,
    environment: data?.environment,
    data,
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
