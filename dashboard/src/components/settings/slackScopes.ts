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

import type { BadgeProps } from '@/components/ui/badge'
import type {
  SlackCapabilityDefinition,
  SlackInstallationHealth,
  SlackInstallationSummary,
  SlackScopeExplanation,
} from '@/lib/api/types'

export type SlackHealthTone = 'success' | 'warning' | 'danger' | 'info' | 'neutral'

export interface SlackHealthPresentation {
  /** Short status label for badges. */
  readonly label: string
  /** Soft badge variant that carries the status language. */
  readonly badge: NonNullable<BadgeProps['variant']>
  /** StatusDot tone for inline indicators. */
  readonly tone: SlackHealthTone
  /** Plain-language explanation of what the status means. */
  readonly description: string
  /** True when reauthorizing (or reinstalling) with Slack is the fix. */
  readonly needsReauthorization: boolean
}

const HEALTH_FALLBACK: SlackHealthPresentation = {
  label: 'Unknown',
  badge: 'neutral',
  tone: 'neutral',
  description: 'Health has not been reported for this workspace yet.',
  needsReauthorization: false,
}

// One status language for every installation health value the backend can return.
export const SLACK_HEALTH_PRESENTATION: Record<SlackInstallationHealth, SlackHealthPresentation> = {
  HEALTHY: {
    label: 'Healthy',
    badge: 'success',
    tone: 'success',
    description: 'Authorized and verified. Alerts can be delivered to this workspace.',
    needsReauthorization: false,
  },
  MISSING_SCOPES: {
    label: 'Missing scopes',
    badge: 'warning',
    tone: 'warning',
    description:
      'An enabled capability needs a scope that Slack has not granted. Reauthorize to add the missing scopes.',
    needsReauthorization: true,
  },
  TOKEN_REVOKED: {
    label: 'Token revoked',
    badge: 'danger',
    tone: 'danger',
    description:
      'Slack revoked or expired this installation’s token. Reauthorize to restore delivery.',
    needsReauthorization: true,
  },
  BOT_REMOVED: {
    label: 'App removed',
    badge: 'danger',
    tone: 'danger',
    description:
      'The Moneat app is no longer active in this workspace. Reauthorize to reinstall it.',
    needsReauthorization: true,
  },
  WORKSPACE_MISMATCH: {
    label: 'Workspace mismatch',
    badge: 'danger',
    tone: 'danger',
    description:
      'Slack authorized a different workspace than this installation. Reauthorize from the intended workspace.',
    needsReauthorization: true,
  },
  REAUTHORIZATION_REQUIRED: {
    label: 'Reauthorization required',
    badge: 'warning',
    tone: 'warning',
    description: 'Confirm the installation and its granted scopes by reauthorizing with Slack.',
    needsReauthorization: true,
  },
  DEGRADED: {
    label: 'Degraded',
    badge: 'warning',
    tone: 'warning',
    description:
      'Slack could not verify this installation on the last check. Run a health check or reauthorize.',
    needsReauthorization: false,
  },
  DISABLED: {
    label: 'Disabled',
    badge: 'neutral',
    tone: 'neutral',
    description: 'Delivery to this workspace is paused. Enable it to resume alerts.',
    needsReauthorization: false,
  },
}

export function slackHealthPresentation(
  health: SlackInstallationHealth
): SlackHealthPresentation {
  return SLACK_HEALTH_PRESENTATION[health] ?? HEALTH_FALLBACK
}

// ── Workspace installs vs. Enterprise Grid organization installs ─────────────
// A summary can take two materially different shapes:
//   • A workspace install has a team (teamId) and may optionally belong to a
//     Grid enterprise (enterpriseId set, isEnterpriseInstall false).
//   • An organization-wide install is authorized once for a Grid enterprise and
//     has no team at OAuth time (isEnterpriseInstall true, teamId null).
// The API does not enumerate the workspaces a Slack admin later attaches an
// org-wide install to, so the UI must not imply per-workspace routing.

/** True for an Enterprise Grid organization-wide install (enterprise, no team). */
export function isOrgWideSlackInstall(
  installation: Pick<SlackInstallationSummary, 'isEnterpriseInstall'>
): boolean {
  return installation.isEnterpriseInstall
}

/** True for an ordinary workspace install that belongs to a Grid enterprise. */
export function belongsToEnterpriseGrid(
  installation: Pick<SlackInstallationSummary, 'isEnterpriseInstall' | 'enterpriseId'>
): boolean {
  return !installation.isEnterpriseInstall && Boolean(installation.enterpriseId)
}

/**
 * Whether the install targets a specific workspace. Workspace-scoped actions —
 * choosing a channel and sending a test — require one, so an org-wide install
 * cannot do them on its own.
 */
export function slackInstallHasWorkspace(
  installation: Pick<SlackInstallationSummary, 'teamId'>
): boolean {
  return Boolean(installation.teamId)
}

export const SLACK_ORG_INSTALL_LABEL = 'Grid organization'

export const SLACK_ORG_INSTALL_NOTE =
  'Authorized once for the whole Enterprise Grid organization. A Slack admin attaches Moneat to selected workspaces — it is not added to every workspace automatically.'

// Delivery is set up per workspace, so an org-wide install has nothing to target
// until Moneat is installed into a workspace.
export const SLACK_ORG_WORKSPACE_ACTION_NOTE =
  'Choosing a channel and sending a test are per-workspace actions. Install Moneat into the workspaces that should receive alerts to set those up.'

const SLACK_ORG_HEALTHY_NOTE =
  'Authorized for the organization. Delivery still happens per workspace — install Moneat into the workspaces that should receive alerts.'

// SLACK_HEALTH_PRESENTATION descriptions read as if every install is a workspace.
// An org-wide install can be HEALTHY yet have no workspace to deliver to, so
// correct the inline explanation for that case. A backend-provided healthDetail
// always wins.
export function slackHealthDescription(installation: SlackInstallationSummary): string {
  if (installation.healthDetail) return installation.healthDetail
  if (isOrgWideSlackInstall(installation) && installation.health === 'HEALTHY') {
    return SLACK_ORG_HEALTHY_NOTE
  }
  return slackHealthPresentation(installation.health).description
}

// Static, source-specific copy. Kept here so the dialog stays presentational and
// this wording is unit-testable.
export const SLACK_LEAST_PRIVILEGE_NOTE =
  'Moneat requests only the scopes each capability needs. Leave a capability off to withhold its scopes.'

export const SLACK_ADDITIVE_GRANTS_NOTE =
  'Slack grants are additive: expanding capabilities adds scopes, and granted scopes remain until you remove and reinstall the app in Slack.'

export const SLACK_REAUTHORIZATION_NOTE =
  'Expanding capabilities may require reauthorizing so Slack can approve the new scopes.'

// Ordering for the capability picker: required capabilities first (in catalog
// order), then optional ones, so the least-privilege core reads as the baseline
// and the optional Slack Assistant is clearly the add-on.
export function sortCapabilities(
  capabilities: readonly SlackCapabilityDefinition[]
): SlackCapabilityDefinition[] {
  return [...capabilities].sort((a, b) => Number(a.optional) - Number(b.optional))
}

export function requiredCapabilityIds(
  capabilities: readonly SlackCapabilityDefinition[]
): string[] {
  return capabilities.filter((c) => !c.optional).map((c) => c.id)
}

export function optionalCapabilityIds(
  capabilities: readonly SlackCapabilityDefinition[]
): string[] {
  return capabilities.filter((c) => c.optional).map((c) => c.id)
}

// Union of scopes for the given capability ids, sorted and de-duplicated, so the
// picker can show exactly what the resulting Slack grant will request.
export function scopesForCapabilities(
  capabilities: readonly SlackCapabilityDefinition[],
  selectedIds: ReadonlySet<string>
): string[] {
  const scopes = new Set<string>()
  for (const capability of capabilities) {
    if (!selectedIds.has(capability.id)) continue
    for (const scope of capability.scopes) scopes.add(scope)
  }
  return [...scopes].sort((a, b) => a.localeCompare(b))
}

// The selected capability set for an install/reauthorize: every required
// capability plus whichever optional capabilities are toggled on.
export function selectedCapabilityIds(
  capabilities: readonly SlackCapabilityDefinition[],
  optionalSelection: ReadonlySet<string>
): string[] {
  return capabilities
    .filter((c) => !c.optional || optionalSelection.has(c.id))
    .map((c) => c.id)
}

// Reasons for a scope, resolved from the scope catalog (for tooltips/expanders).
export function explanationForScope(
  scopes: readonly SlackScopeExplanation[],
  scope: string
): SlackScopeExplanation | undefined {
  return scopes.find((s) => s.scope === scope)
}
