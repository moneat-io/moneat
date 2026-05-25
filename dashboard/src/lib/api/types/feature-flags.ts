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

export type FeatureFlagValueType = 'BOOLEAN' | 'STRING' | 'INTEGER' | 'DOUBLE' | 'OBJECT'
export type FeatureFlagSdkKeyType = 'server' | 'client'
export type FeatureFlagJsonValue = boolean | string | number | Record<string, unknown> | null

export interface FeatureFlagEnvironment {
  id: number
  key: string
  name: string
  description?: string | null
  version: number
  createdAt: string
  updatedAt: string
}

export interface FeatureFlagVariant {
  id: number
  key: string
  name: string
  value: FeatureFlagJsonValue
  sortOrder: number
}

export interface FeatureFlagConfig {
  environmentKey: string
  environmentName: string
  enabled: boolean
  defaultVariantKey?: string | null
  offVariantKey?: string | null
  rules: unknown
  version: number
  updatedAt: string
}

export interface FeatureFlag {
  id: number
  key: string
  name: string
  description?: string | null
  valueType: FeatureFlagValueType
  clientVisible: boolean
  tags: string[]
  variants: FeatureFlagVariant[]
  configs: FeatureFlagConfig[]
  createdAt: string
  updatedAt: string
}

export interface FeatureFlagListResponse {
  environments: FeatureFlagEnvironment[]
  flags: FeatureFlag[]
}

export interface FeatureFlagVariantRequest {
  key: string
  name?: string | null
  value: FeatureFlagJsonValue
}

export interface CreateFeatureFlagRequest {
  key: string
  name: string
  description?: string | null
  valueType: FeatureFlagValueType
  clientVisible: boolean
  tags: string[]
  variants: FeatureFlagVariantRequest[]
  defaultVariantKey?: string | null
  offVariantKey?: string | null
}

export interface UpdateFeatureFlagRequest {
  name?: string
  description?: string | null
  clientVisible?: boolean
  tags?: string[]
  variants?: FeatureFlagVariantRequest[]
}

export interface UpdateFeatureFlagConfigRequest {
  enabled?: boolean
  defaultVariantKey?: string | null
  offVariantKey?: string | null
  rules?: unknown
}

export interface FeatureFlagSegment {
  id: number
  key: string
  name: string
  description?: string | null
  conditions: unknown
  createdAt: string
  updatedAt: string
}

export interface FeatureFlagSegmentRequest {
  key: string
  name: string
  description?: string | null
  conditions: unknown
}

export interface FeatureFlagSdkKey {
  id: number
  environmentKey: string
  name: string
  keyType: FeatureFlagSdkKeyType
  keyPrefix: string
  createdAt: string
  lastUsedAt?: string | null
}

export interface CreateFeatureFlagSdkKeyRequest {
  environmentKey: string
  name: string
  keyType: FeatureFlagSdkKeyType
}

export interface CreatedFeatureFlagSdkKey extends FeatureFlagSdkKey {
  key: string
}

export interface FeatureFlagAuditEvent {
  id: number
  environmentKey?: string | null
  flagKey?: string | null
  actorUserId?: number | null
  eventType: string
  before?: unknown
  after?: unknown
  createdAt: string
}

export interface FeatureFlagAnalytics {
  evaluations: number
  uniqueTargetingKeys: number
  variants: FeatureFlagVariantAnalytics[]
  trackingEvents: FeatureFlagTrackingAnalytics[]
}

export interface FeatureFlagVariantAnalytics {
  flagKey: string
  variantKey: string
  evaluations: number
  uniqueTargetingKeys: number
}

export interface FeatureFlagTrackingAnalytics {
  eventName: string
  flagKey?: string | null
  variantKey?: string | null
  events: number
  uniqueTargetingKeys: number
  totalValue: number
}
