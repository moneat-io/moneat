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
import {urlWithQuery} from '../utils'
import type {
  CreateFeatureFlagRequest,
  CreateFeatureFlagSdkKeyRequest,
  CreatedFeatureFlagSdkKey,
  FeatureFlag,
  FeatureFlagAnalytics,
  FeatureFlagAuditEvent,
  FeatureFlagConfig,
  FeatureFlagEnvironment,
  FeatureFlagListResponse,
  FeatureFlagSdkKey,
  FeatureFlagSegment,
  FeatureFlagSegmentRequest,
  UpdateFeatureFlagConfigRequest,
  UpdateFeatureFlagRequest,
} from '../types'

export function featureFlagsMethods(core: ApiClientCore) {
  const base = core.API_BASE

  return {
    listFeatureFlagEnvironments: () =>
      core.request<{environments: FeatureFlagEnvironment[]}>(`${base}/feature-flags/environments`),

    createFeatureFlagEnvironment: (request: {key: string; name: string; description?: string | null}) =>
      core.request<FeatureFlagEnvironment>(`${base}/feature-flags/environments`, {
        method: 'POST',
        body: JSON.stringify(request),
      }),

    listFeatureFlags: (environment?: string) => {
      const qs = environment ? new URLSearchParams({environment}).toString() : ''
      return core.request<FeatureFlagListResponse>(urlWithQuery(`${base}/feature-flags`, qs))
    },

    createFeatureFlag: (request: CreateFeatureFlagRequest) =>
      core.request<FeatureFlag>(`${base}/feature-flags`, {
        method: 'POST',
        body: JSON.stringify(request),
      }),

    getFeatureFlag: (key: string, environment?: string) => {
      const qs = environment ? new URLSearchParams({environment}).toString() : ''
      return core.request<FeatureFlag>(
        urlWithQuery(`${base}/feature-flags/${encodeURIComponent(key)}`, qs)
      )
    },

    updateFeatureFlag: (key: string, request: UpdateFeatureFlagRequest) =>
      core.request<FeatureFlag>(`${base}/feature-flags/${encodeURIComponent(key)}`, {
        method: 'PUT',
        body: JSON.stringify(request),
      }),

    deleteFeatureFlag: (key: string) =>
      core.request<void>(`${base}/feature-flags/${encodeURIComponent(key)}`, {
        method: 'DELETE',
      }),

    updateFeatureFlagConfig: (
      key: string,
      environment: string,
      request: UpdateFeatureFlagConfigRequest
    ) =>
      core.request<FeatureFlagConfig>(
        `${base}/feature-flags/${encodeURIComponent(key)}/config/${encodeURIComponent(environment)}`,
        {
          method: 'PUT',
          body: JSON.stringify(request),
        }
      ),

    listFeatureFlagSegments: () =>
      core.request<{segments: FeatureFlagSegment[]}>(`${base}/feature-flags/segments`),

    upsertFeatureFlagSegment: (request: FeatureFlagSegmentRequest) =>
      core.request<FeatureFlagSegment>(`${base}/feature-flags/segments`, {
        method: 'POST',
        body: JSON.stringify(request),
      }),

    deleteFeatureFlagSegment: (key: string) =>
      core.request<void>(`${base}/feature-flags/segments/${encodeURIComponent(key)}`, {
        method: 'DELETE',
      }),

    listFeatureFlagSdkKeys: () =>
      core.request<{keys: FeatureFlagSdkKey[]}>(`${base}/feature-flags/sdk-keys`),

    createFeatureFlagSdkKey: (request: CreateFeatureFlagSdkKeyRequest) =>
      core.request<CreatedFeatureFlagSdkKey>(`${base}/feature-flags/sdk-keys`, {
        method: 'POST',
        body: JSON.stringify(request),
      }),

    revokeFeatureFlagSdkKey: (id: string) =>
      core.request<void>(`${base}/feature-flags/sdk-keys/${encodeURIComponent(id)}`, {
        method: 'DELETE',
      }),

    listFeatureFlagAuditEvents: (limit = 50) =>
      core.request<{events: FeatureFlagAuditEvent[]}>(`${base}/feature-flags/audit?limit=${limit}`),

    getFeatureFlagAnalytics: (environment?: string, hours = 24) => {
      const params = new URLSearchParams({hours: String(hours)})
      if (environment) params.set('environment', environment)
      return core.request<FeatureFlagAnalytics>(
        urlWithQuery(`${base}/feature-flags/analytics`, params.toString())
      )
    },
  }
}
