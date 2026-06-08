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

export interface ProfileResponse {
  profileId: string
  host: string
  service: string
  env: string
  version: string
  runtime: string
  language: string
  profileType: string
  startTime: string
  endTime: string
  durationNs: number
  sizeBytes: number
  tags: Record<string, string>
  source?: string
}

export interface ProfileListResponse {
  profiles: ProfileResponse[]
  totalCount: number
}

export interface FlamegraphFrame {
  name: string
  value: number
  children: FlamegraphFrame[]
  self?: number
}

export interface SampleTypeInfo {
  key: string
  label: string
  unit: string
}

export interface ThreadInfo {
  id: string
  label: string
  samples: number
}

export interface FlamegraphResponse {
  frames: FlamegraphFrame[]
  sampleTypes?: SampleTypeInfo[]
  threads?: ThreadInfo[]
  selectedSampleType?: string
  selectedThread?: string | null
  unit?: string
  totalSamples?: number
}

/** A merged flamegraph aggregated across multiple profiles in a window. */
export interface MergedFlamegraphResponse extends FlamegraphResponse {
  /** Number of profiles actually merged into this flamegraph. */
  mergedCount?: number
  /** Total profiles matching the window (mergedCount may be a sample of this). */
  totalCount?: number
}

export interface ProfileTypeCount {
  profileType: string
  count: number
}

/** A bucket of profile-volume activity (ts = epoch millis). */
export interface ProfileSeriesPoint {
  ts: number
  count: number
}

/** Per-service rollup powering the Profiles overview cards. */
export interface ProfileServiceSummary {
  service: string
  languages: string[]
  runtimes: string[]
  environments: string[]
  types: ProfileTypeCount[]
  hostCount: number
  profileCount: number
  totalSizeBytes: number
  firstSeen: string
  lastSeen: string
  avgDurationNs: number
  series: ProfileSeriesPoint[]
}

export interface ProfileServicesResponse {
  services: ProfileServiceSummary[]
  totalProfiles: number
  totalSizeBytes: number
  serviceCount: number
  hostCount: number
  typeCount: number
}

export interface ProfileTimeseriesPoint {
  ts: number
  count: number
  sizeBytes: number
}

export interface ProfileTimeseriesResponse {
  points: ProfileTimeseriesPoint[]
  bucketSeconds: number
}
