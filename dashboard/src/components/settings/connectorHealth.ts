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

import type {BadgeProps} from '@/components/ui/badge'
import type {StatusTone} from '@/components/ui/status-dot'
import type {
  ConnectorInstallationResponse,
  ConnectorProviderDefinition,
  ConnectorProviderStateDetail,
} from '@/lib/api/types'

// A provider is "installation-backed" when any of its uses keeps live state in
// connector installations (the generic install/credentials/mapping APIs) rather
// than the legacy OAuth integration tables. RevenueCat is the first such provider;
// keeping the check capability-driven means new data-import connectors light up
// without per-provider branching here.
export function isInstallationBacked(provider: ConnectorProviderDefinition): boolean {
  return provider.uses.some((use) => use.stateSource === 'connector_installations')
}

// The auth profile a setup flow should submit — the one allowed by the provider's
// installation-backed use (e.g. RevenueCat's `project_api_key`).
export function installAuthProfileId(provider: ConnectorProviderDefinition): string | null {
  const use = provider.uses.find((u) => u.stateSource === 'connector_installations')
  return use?.allowedAuthProfileIds[0] ?? provider.authProfiles[0]?.id ?? null
}

export interface HealthDescriptor {
  readonly tone: StatusTone
  readonly badge: NonNullable<BadgeProps['variant']>
  readonly label: string
}

// Maps a connector health/status string to one status language. "Awaiting traffic"
// is a healthy waiting state (connected, no webhook events yet) — it reads as
// informational, never as an error. Unknown is neutral, not alarming.
export function describeConnectorHealth(
  state: string | null | undefined,
  enabled = true,
): HealthDescriptor {
  if (!enabled) return {tone: 'neutral', badge: 'secondary', label: 'Disabled'}
  switch (state) {
    case 'healthy':
      return {tone: 'success', badge: 'success', label: 'Healthy'}
    case 'degraded':
      return {tone: 'warning', badge: 'warning', label: 'Degraded'}
    case 'error':
      return {tone: 'danger', badge: 'danger', label: 'Error'}
    case 'needs_mapping':
      return {tone: 'warning', badge: 'warning', label: 'Needs mapping'}
    case 'awaiting_traffic':
      return {tone: 'info', badge: 'info', label: 'Awaiting traffic'}
    default:
      return {tone: 'neutral', badge: 'neutral', label: 'Unknown'}
  }
}

// The backend encodes the "connected but no events yet" case in `status`
// (`awaiting_traffic`) while `health` stays `healthy`, so surface status first to
// keep that distinction. Falls back to the installation status when no live state
// detail is available yet.
export function effectiveConnectorState(
  detail?: ConnectorProviderStateDetail | null,
  installation?: ConnectorInstallationResponse | null,
): string | undefined {
  if (detail) {
    return detail.status === 'awaiting_traffic' ? 'awaiting_traffic' : detail.health
  }
  return installation?.status
}

// "X processed Ns behind" — compact lag readout that stays calm for tiny values.
export function formatProcessingLag(seconds: number | null | undefined): string {
  if (seconds == null) return 'Up to date'
  if (seconds < 60) return seconds <= 1 ? 'Up to date' : `${seconds}s behind`
  if (seconds < 3600) return `${Math.floor(seconds / 60)}m behind`
  if (seconds < 86400) return `${Math.floor(seconds / 3600)}h behind`
  return `${Math.floor(seconds / 86400)}d behind`
}
