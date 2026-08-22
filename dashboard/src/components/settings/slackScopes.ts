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
  SlackCapabilityHealth,
  SlackGrantType,
  SlackInstallationHealth,
  SlackInstallationSummary,
  SlackScopeExplanation,
  SlackWorkspaceBindingSummary,
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

// Bot vs. privileged-user scopes ─────────────────────────────────────────────
// A capability requests bot scopes (granted to Moneat's Slack app) and, only for
// privileged workspace access, user scopes (granted by a Slack admin or owner as
// themselves). They authorize as separate principals, so the picker keeps the
// two grants apart rather than blending them into one scope list.
function collectScopes(
  capabilities: readonly SlackCapabilityDefinition[],
  selectedIds: ReadonlySet<string>,
  pick: (capability: SlackCapabilityDefinition) => readonly string[]
): string[] {
  const scopes = new Set<string>()
  for (const capability of capabilities) {
    if (!selectedIds.has(capability.id)) continue
    for (const scope of pick(capability)) scopes.add(scope)
  }
  return [...scopes].sort((a, b) => a.localeCompare(b))
}

export function botScopesForCapabilities(
  capabilities: readonly SlackCapabilityDefinition[],
  selectedIds: ReadonlySet<string>
): string[] {
  return collectScopes(capabilities, selectedIds, (c) => c.botScopes)
}

export function userScopesForCapabilities(
  capabilities: readonly SlackCapabilityDefinition[],
  selectedIds: ReadonlySet<string>
): string[] {
  return collectScopes(capabilities, selectedIds, (c) => c.userScopes)
}

/** True when a capability pulls in privileged-user scopes (a second OAuth grant). */
export function capabilityUsesUserScopes(
  capability: Pick<SlackCapabilityDefinition, 'userScopes'>
): boolean {
  return capability.userScopes.length > 0
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

// ── Capability health (missing bot vs. user scopes) ──────────────────────────
// Per-capability health separates the scopes the bot still needs from the ones a
// privileged Slack user still needs, because they are fixed by different people:
// the bot scopes by reauthorizing the app, the user scopes by an admin or owner.

export interface SlackMissingScopes {
  readonly botScopes: string[]
  readonly userScopes: string[]
}

export function capabilityHealthFor(
  installation: Pick<SlackInstallationSummary, 'capabilityHealth'>,
  capabilityId: string
): SlackCapabilityHealth | undefined {
  return installation.capabilityHealth.find((c) => c.capabilityId === capabilityId)
}

/** Healthy unless the backend reported this capability as missing scopes. */
export function isCapabilityHealthy(
  installation: Pick<SlackInstallationSummary, 'capabilityHealth'>,
  capabilityId: string
): boolean {
  return capabilityHealthFor(installation, capabilityId)?.healthy ?? true
}

/** Missing scopes aggregated across enabled capabilities, split by principal. */
export function missingScopesByPrincipal(
  installation: Pick<SlackInstallationSummary, 'capabilityHealth'>
): SlackMissingScopes {
  const botScopes = new Set<string>()
  const userScopes = new Set<string>()
  for (const entry of installation.capabilityHealth) {
    for (const scope of entry.missingBotScopes) botScopes.add(scope)
    for (const scope of entry.missingUserScopes) userScopes.add(scope)
  }
  const sort = (set: Set<string>) => [...set].sort((a, b) => a.localeCompare(b))
  return { botScopes: sort(botScopes), userScopes: sort(userScopes) }
}

// ── OAuth grant principals ───────────────────────────────────────────────────
// An installation has one bot grant and, when privileged access is authorized,
// one or more user grants. They are distinct Slack principals with their own
// scopes and health; the summary never carries their tokens.

export const SLACK_GRANT_PRINCIPAL_LABEL: Record<SlackGrantType, string> = {
  BOT: 'Bot user',
  USER: 'Privileged user',
}

export const SLACK_GRANT_PRINCIPAL_DESCRIPTION: Record<SlackGrantType, string> = {
  BOT: 'Moneat’s Slack app. Posts alerts and manages channels with the app’s bot scopes.',
  USER: 'A Slack admin or owner who separately authorized privileged actions as themselves.',
}

// ── Workspace bindings ───────────────────────────────────────────────────────
// Bindings are the explicit workspaces an installation is attached to. A
// workspace install has one; an Enterprise Grid organization install may have
// zero until a Slack admin attaches Moneat, and can be healthy meanwhile.

export function bindingDisplayName(binding: SlackWorkspaceBindingSummary): string {
  return binding.teamName ?? binding.teamId
}

/**
 * Whether to surface the workspaces list. The header already names the single
 * healthy workspace of an ordinary install, so only show the list when it adds
 * something: an organization install (zero or more attached workspaces), more
 * than one workspace, or any bound workspace that is unhealthy or paused.
 */
export function shouldSurfaceBindings(
  installation: Pick<SlackInstallationSummary, 'isEnterpriseInstall' | 'workspaceBindings'>
): boolean {
  if (installation.isEnterpriseInstall) return true
  const bindings = installation.workspaceBindings
  if (bindings.length > 1) return true
  return bindings.some((b) => !b.enabled || slackHealthPresentation(b.health).tone !== 'success')
}

// ── Token lifecycle ──────────────────────────────────────────────────────────
// Grant summaries expose expiry timestamps, which can be in the future — the
// app's past-only formatRelativeTime can't express that, so format a signed
// distance here.

export interface SlackExpiry {
  readonly text: string
  readonly expired: boolean
}

export function slackExpiryLabel(
  expiresAt: string | null,
  nowMs: number = Date.now()
): SlackExpiry | null {
  if (!expiresAt) return null
  const timestamp = Date.parse(expiresAt)
  if (!Number.isFinite(timestamp)) return null
  const expired = timestamp <= nowMs
  const distance = humanizeDuration(Math.abs(timestamp - nowMs))
  return { expired, text: expired ? `Expired ${distance} ago` : `Expires in ${distance}` }
}

function humanizeDuration(ms: number): string {
  const minutes = Math.floor(ms / 60_000)
  if (minutes < 1) return 'under a minute'
  if (minutes < 60) return `${minutes}m`
  const hours = Math.floor(minutes / 60)
  if (hours < 24) return `${hours}h`
  return `${Math.floor(hours / 24)}d`
}

// ── Privileged access and binding copy ───────────────────────────────────────

export const SLACK_PRIVILEGED_ACCESS_NOTE =
  'Privileged workspace access authorizes a Slack admin or owner separately and acts as that person for restricted channel and user-group changes. It uses their Slack permissions and grants Moneat no additional product access.'

export const SLACK_USER_SCOPE_PICKER_NOTE =
  'A Slack admin or owner grants these user scopes in a separate authorization step. Moneat performs the restricted actions as that Slack user and gains no Moneat product permissions from them.'

export const SLACK_NO_BINDINGS_NOTE =
  'No workspaces are attached yet. A Slack admin adds Moneat to workspaces in the Grid organization; each attached workspace appears here with its own health.'

export const SLACK_BINDING_HEALTH_NOTE =
  'Each attached workspace verifies on its own, so one can need attention while the others stay healthy.'
