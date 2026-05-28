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

export type TelemetrySourceId = 'sentry-sdk' | 'opentelemetry' | 'datadog-agent'

export type TelemetrySourceStatusState = 'waiting' | 'configured' | 'receiving'

export interface TelemetrySource {
  id: TelemetrySourceId
  label: string
  shortLabel: string
  description: string
  setupTitle: string
  setupDescription: string
  productHref: string
  productLabel: string
}

export interface KeyUsage {
  lastUsedAt?: string | null
}

export interface MonitorHostUsage {
  last_seen_at?: number | string | null
}

export interface TelemetrySourceStatusInput {
  projectEventCount?: number
  otlpKeys?: KeyUsage[]
  agentKeys?: KeyUsage[]
  monitorHosts?: MonitorHostUsage[]
}

export interface TelemetrySourceStatus {
  state: TelemetrySourceStatusState
  label: string
  detail: string
}

export const TELEMETRY_SOURCES: TelemetrySource[] = [
  {
    id: 'opentelemetry',
    label: 'OpenTelemetry',
    shortLabel: 'OpenTelemetry',
    description: 'Send logs, traces, and metrics from OpenTelemetry SDKs, direct OTLP HTTP, or a Collector.',
    setupTitle: 'Connect OpenTelemetry',
    setupDescription: 'Create an OTLP API key and point SDKs, direct HTTP clients, or a Collector at Moneat.',
    productHref: '/performance/traces',
    productLabel: 'View traces',
  },
  {
    id: 'sentry-sdk',
    label: 'Sentry-compatible SDK',
    shortLabel: 'Sentry SDK',
    description: 'Keep an existing Sentry SDK and send error, replay, and performance events to Moneat.',
    setupTitle: 'Connect a Sentry-compatible SDK',
    setupDescription: 'Use the project DSN with the SDK for your selected platform.',
    productHref: '/issues',
    productLabel: 'View issues',
  },
  {
    id: 'datadog-agent',
    label: 'Datadog Agent',
    shortLabel: 'Datadog Agent',
    description: 'Point a compatible agent at Moneat for infrastructure, logs, APM, and profiling data.',
    setupTitle: 'Connect a Datadog Agent',
    setupDescription: 'Create an agent key and configure the agent intake URLs.',
    productHref: '/monitoring',
    productLabel: 'View monitoring',
  },
]

export const DEFAULT_SELECTED_TELEMETRY_SOURCE_IDS: TelemetrySourceId[] = ['opentelemetry']

export const ALL_TELEMETRY_SOURCE_IDS: TelemetrySourceId[] = TELEMETRY_SOURCES.map((source) => source.id)

const telemetrySourceIdSet = new Set<string>(ALL_TELEMETRY_SOURCE_IDS)
const legacyTelemetrySourceAliases: Record<string, TelemetrySourceId> = {
  'http-logs': 'opentelemetry',
}

function hasUsedKey(keys?: KeyUsage[]): boolean {
  return keys?.some((key) => Boolean(key.lastUsedAt)) ?? false
}

function hasAnyKey(keys?: KeyUsage[]): boolean {
  return (keys?.length ?? 0) > 0
}

function hasSeenHost(hosts?: MonitorHostUsage[]): boolean {
  return hosts?.some((host) => Boolean(host.last_seen_at)) ?? false
}

export function isTelemetrySourceId(value: string): value is TelemetrySourceId {
  return telemetrySourceIdSet.has(value)
}

export function getTelemetrySource(sourceId: TelemetrySourceId): TelemetrySource {
  return TELEMETRY_SOURCES.find((source) => source.id === sourceId) ?? TELEMETRY_SOURCES[0]
}

export function parseTelemetrySourceIds(value: unknown): TelemetrySourceId[] {
  if (typeof value !== 'string') return []

  const sourceIds: TelemetrySourceId[] = []
  value.split(',').forEach((rawSourceId) => {
    const trimmedSourceId = rawSourceId.trim()
    const sourceId = legacyTelemetrySourceAliases[trimmedSourceId] ?? trimmedSourceId
    if (isTelemetrySourceId(sourceId) && !sourceIds.includes(sourceId)) {
      sourceIds.push(sourceId)
    }
  })
  return sourceIds
}

export function serializeTelemetrySourceIds(sourceIds: TelemetrySourceId[]): string {
  return sourceIds.filter((sourceId, index) => sourceIds.indexOf(sourceId) === index).join(',')
}

export function toggleTelemetrySourceId(
  selectedSourceIds: TelemetrySourceId[],
  sourceId: TelemetrySourceId
): TelemetrySourceId[] {
  if (selectedSourceIds.includes(sourceId)) {
    const nextSourceIds = selectedSourceIds.filter((selectedSourceId) => selectedSourceId !== sourceId)
    return nextSourceIds.length > 0 ? nextSourceIds : selectedSourceIds
  }
  return [...selectedSourceIds, sourceId]
}

export function telemetrySourcesStorageKey(projectId: string | number): string {
  return `moneat:project:${projectId}:telemetry-sources`
}

export function loadTelemetrySourceIdsForProject(projectId: string | number): TelemetrySourceId[] {
  const rawValue = globalThis.localStorage?.getItem(telemetrySourcesStorageKey(projectId))
  return parseTelemetrySourceIds(rawValue)
}

export function storeTelemetrySourceIdsForProject(
  projectId: string | number,
  sourceIds: TelemetrySourceId[]
): void {
  globalThis.localStorage?.setItem(telemetrySourcesStorageKey(projectId), serializeTelemetrySourceIds(sourceIds))
}

export function getTelemetrySourceStatus(
  sourceId: TelemetrySourceId,
  input: TelemetrySourceStatusInput
): TelemetrySourceStatus {
  if (sourceId === 'sentry-sdk') {
    if ((input.projectEventCount ?? 0) > 0) {
      return {
        state: 'receiving',
        label: 'Receiving',
        detail: 'Moneat has received project events.',
      }
    }
    return {
      state: 'waiting',
      label: 'Waiting',
      detail: 'The project DSN is ready. Send a test event to verify setup.',
    }
  }

  if (sourceId === 'datadog-agent') {
    if (hasUsedKey(input.agentKeys) || hasSeenHost(input.monitorHosts)) {
      return {
        state: 'receiving',
        label: 'Receiving',
        detail: 'Agent telemetry has reached Moneat.',
      }
    }
    if (hasAnyKey(input.agentKeys)) {
      return {
        state: 'configured',
        label: 'Key created',
        detail: 'An agent key exists. Start the agent to verify telemetry.',
      }
    }
    return {
      state: 'waiting',
      label: 'Needs key',
      detail: 'Create an agent key to configure this source.',
    }
  }

  if (hasUsedKey(input.otlpKeys)) {
    return {
      state: 'receiving',
      label: 'Receiving',
      detail: 'OTLP telemetry has used an API key.',
    }
  }

  if (hasAnyKey(input.otlpKeys)) {
    return {
      state: 'configured',
      label: 'Key created',
      detail: 'An OTLP API key exists. Send telemetry to verify setup.',
    }
  }

  return {
    state: 'waiting',
    label: 'Needs key',
    detail: 'Create an OTLP API key to configure this source.',
  }
}
