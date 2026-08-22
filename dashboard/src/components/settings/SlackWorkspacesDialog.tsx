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

import {type ReactNode, useMemo, useState} from 'react'
import {type UseQueryResult, useMutation, useQuery, useQueryClient} from '@tanstack/react-query'
import {
  AlertTriangle,
  ArrowLeft,
  Ban,
  Bot,
  Building2,
  Check,
  Clock,
  KeyRound,
  Loader2,
  Plus,
  RefreshCw,
  RotateCw,
  Send,
  ShieldAlert,
  ShieldCheck,
  Slack,
  Trash2,
  UserCog,
  Users,
  type LucideIcon,
} from 'lucide-react'

import {api} from '@/lib/api'
import type {
  SlackCapabilitiesResponse,
  SlackCapabilityDefinition,
  SlackGrantSummary,
  SlackInstallationSummary,
  SlackWorkspaceBindingSummary,
} from '@/lib/api/types'
import {Badge} from '@/components/ui/badge'
import {Button} from '@/components/ui/button'
import {Checkbox} from '@/components/ui/checkbox'
import {Label} from '@/components/ui/label'
import {Switch} from '@/components/ui/switch'
import {StatusDot} from '@/components/ui/status-dot'
import {
  Select,
  SelectContent,
  SelectItem,
  SelectTrigger,
  SelectValue,
} from '@/components/ui/select'
import {
  Dialog,
  DialogContent,
  DialogDescription,
  DialogFooter,
  DialogHeader,
  DialogTitle,
} from '@/components/ui/dialog'
import {cn, formatRelativeTime} from '@/lib/utils'
import {useToast} from '@/hooks/useToast'
import {trackEvent} from '@/lib/analytics'
import {
  SLACK_ADDITIVE_GRANTS_NOTE,
  SLACK_BINDING_HEALTH_NOTE,
  SLACK_GRANT_PRINCIPAL_DESCRIPTION,
  SLACK_GRANT_PRINCIPAL_LABEL,
  SLACK_LEAST_PRIVILEGE_NOTE,
  SLACK_NO_BINDINGS_NOTE,
  SLACK_ORG_INSTALL_LABEL,
  SLACK_ORG_WORKSPACE_ACTION_NOTE,
  SLACK_PRIVILEGED_ACCESS_NOTE,
  SLACK_REAUTHORIZATION_NOTE,
  SLACK_USER_SCOPE_PICKER_NOTE,
  belongsToEnterpriseGrid,
  bindingDisplayName,
  botScopesForCapabilities,
  capabilityUsesUserScopes,
  isCapabilityHealthy,
  isOrgWideSlackInstall,
  missingScopesByPrincipal,
  optionalCapabilityIds,
  selectedCapabilityIds,
  shouldSurfaceBindings,
  slackExpiryLabel,
  slackHealthDescription,
  slackHealthPresentation,
  slackInstallHasWorkspace,
  sortCapabilities,
  userScopesForCapabilities,
} from './slackScopes'

// Query keys touched by installation mutations. The Connectors catalog derives a
// Slack card from the default installation, so its keys are invalidated too.
const SLACK_INSTALLATIONS_KEY = ['slackInstallations'] as const
const CONNECTOR_KEYS = [['integrations'], ['connectorInstallations'], ['connectorState']] as const

type PickerMode =
  | {readonly kind: 'list'}
  | {readonly kind: 'add'}
  | {readonly kind: 'reauthorize'; readonly installation: SlackInstallationSummary}

function installationName(installation: SlackInstallationSummary): string {
  return (
    installation.teamName ??
    installation.enterpriseName ??
    installation.teamId ??
    installation.enterpriseId ??
    (isOrgWideSlackInstall(installation) ? 'Slack organization' : 'Slack workspace')
  )
}

export function SlackWorkspacesDialog({onClose}: Readonly<{onClose: () => void}>) {
  const [mode, setMode] = useState<PickerMode>({kind: 'list'})

  const capabilitiesQuery = useQuery({
    queryKey: ['slackCapabilities'],
    queryFn: () => api.getSlackCapabilities(),
    staleTime: 5 * 60 * 1000,
  })

  const installationsQuery = useQuery({
    queryKey: SLACK_INSTALLATIONS_KEY,
    queryFn: () => api.getSlackInstallations(),
    enabled: api.isAuthenticated(),
  })

  const showPicker = mode.kind !== 'list'

  return (
    <Dialog open onOpenChange={(open) => !open && onClose()}>
      <DialogContent className="sm:max-w-2xl">
        <DialogHeader>
          <DialogTitle className="flex items-center gap-2">
            <Slack className="h-5 w-5" aria-hidden="true" />
            {showPicker
              ? mode.kind === 'add'
                ? 'Add a Slack workspace'
                : `Reauthorize ${installationName(mode.installation)}`
              : 'Slack workspaces'}
          </DialogTitle>
          <DialogDescription>
            {showPicker
              ? 'Choose the capabilities Slack should grant. Moneat requests only the scopes those capabilities need.'
              : 'Install Moneat into Slack workspaces, or authorize an Enterprise Grid organization for the whole org. Each install keeps its own scopes, health, channel, and delivery state.'}
          </DialogDescription>
        </DialogHeader>

        {showPicker ? (
          <CapabilityPicker
            catalog={capabilitiesQuery.data}
            isLoading={capabilitiesQuery.isLoading}
            isError={capabilitiesQuery.isError}
            onRetry={() => capabilitiesQuery.refetch()}
            mode={mode.kind}
            installation={mode.kind === 'reauthorize' ? mode.installation : undefined}
            onCancel={() => setMode({kind: 'list'})}
          />
        ) : (
          <InstallationList
            installationsQuery={installationsQuery}
            catalog={capabilitiesQuery.data}
            onAdd={() => setMode({kind: 'add'})}
            onReauthorize={(installation) => setMode({kind: 'reauthorize', installation})}
          />
        )}
      </DialogContent>
    </Dialog>
  )
}

// ── Installation list ────────────────────────────────────────────────────────

function InstallationList({
  installationsQuery,
  catalog,
  onAdd,
  onReauthorize,
}: Readonly<{
  installationsQuery: UseQueryResult<SlackInstallationSummary[], Error>
  catalog?: SlackCapabilitiesResponse
  onAdd: () => void
  onReauthorize: (installation: SlackInstallationSummary) => void
}>) {
  const {data: installations, isLoading, isError, refetch, isRefetching} = installationsQuery

  if (isLoading) {
    return (
      <div
        className="flex items-center justify-center gap-2 py-12 text-sm text-muted-foreground"
        role="status"
        aria-live="polite"
      >
        <Loader2 className="h-4 w-4 animate-spin" aria-hidden="true" />
        Loading Slack workspaces…
      </div>
    )
  }

  if (isError) {
    return (
      <div className="py-10 text-center">
        <AlertTriangle className="mx-auto mb-2 h-8 w-8 text-warning-fg" aria-hidden="true" />
        <p className="text-sm font-medium">Couldn’t load Slack workspaces</p>
        <p className="mx-auto mt-1 max-w-sm text-xs text-muted-foreground">
          The workspace list failed to load. Check your connection and try again.
        </p>
        <Button variant="outline" size="sm" className="mt-4" onClick={() => refetch()}>
          <RefreshCw className="mr-1.5 h-3.5 w-3.5" aria-hidden="true" />
          Retry
        </Button>
      </div>
    )
  }

  const list = installations ?? []

  return (
    <div className="space-y-4">
      {list.length === 0 ? (
        <div className="rounded-lg border border-dashed py-10 text-center">
          <Slack className="mx-auto mb-2 h-8 w-8 text-muted-foreground" aria-hidden="true" />
          <p className="text-sm font-medium">No Slack workspaces yet</p>
          <p className="mx-auto mt-1 max-w-sm text-xs text-muted-foreground">
            Add your first workspace to deliver alerts and run incident response in Slack.
          </p>
          <Button size="sm" className="mt-4" onClick={onAdd}>
            <Plus className="mr-1.5 h-3.5 w-3.5" aria-hidden="true" />
            Add Slack workspace
          </Button>
        </div>
      ) : (
        <>
          <div className="flex items-center justify-between gap-2">
            <p className="text-xs text-muted-foreground" aria-live="polite">
              {list.length} Slack install{list.length === 1 ? '' : 's'} connected
            </p>
            <div className="flex items-center gap-2">
              <Button
                variant="ghost"
                size="sm"
                onClick={() => refetch()}
                disabled={isRefetching}
                aria-label="Refresh workspaces"
              >
                <RefreshCw
                  className={cn('mr-1.5 h-3.5 w-3.5', isRefetching && 'animate-spin')}
                  aria-hidden="true"
                />
                Refresh
              </Button>
              <Button size="sm" onClick={onAdd}>
                <Plus className="mr-1.5 h-3.5 w-3.5" aria-hidden="true" />
                Add workspace
              </Button>
            </div>
          </div>

          <ul className="space-y-3">
            {list.map((installation) => (
              <li key={installation.id}>
                <InstallationCard
                  installation={installation}
                  catalog={catalog}
                  installationCount={list.length}
                  onReauthorize={() => onReauthorize(installation)}
                />
              </li>
            ))}
          </ul>
        </>
      )}
    </div>
  )
}

// ── Single installation ──────────────────────────────────────────────────────

function InstallationCard({
  installation,
  catalog,
  installationCount,
  onReauthorize,
}: Readonly<{
  installation: SlackInstallationSummary
  catalog?: SlackCapabilitiesResponse
  installationCount: number
  onReauthorize: () => void
}>) {
  const queryClient = useQueryClient()
  const {toast} = useToast()
  const [showChannelPicker, setShowChannelPicker] = useState(false)
  const [confirmDelete, setConfirmDelete] = useState(false)

  const invalidate = () => {
    queryClient.invalidateQueries({queryKey: SLACK_INSTALLATIONS_KEY})
    for (const key of CONNECTOR_KEYS) queryClient.invalidateQueries({queryKey: key})
  }
  const fail = (title: string) => (err: Error) =>
    toast({title, description: err.message, variant: 'destructive'})

  const health = slackHealthPresentation(installation.health)
  const name = installationName(installation)
  const orgWide = isOrgWideSlackInstall(installation)
  const partOfGrid = belongsToEnterpriseGrid(installation)
  const hasWorkspace = slackInstallHasWorkspace(installation)
  const bindings = installation.workspaceBindings
  const grants = installation.grants
  const missing = missingScopesByPrincipal(installation)
  const showBindings = shouldSurfaceBindings(installation)

  const setDefault = useMutation({
    mutationFn: () => api.setSlackInstallationDefault(installation.id),
    onSuccess: () => {
      invalidate()
      toast({title: 'Default updated', description: `${name} is now the default Slack install.`})
    },
    onError: fail('Failed to set default'),
  })

  const setEnabled = useMutation({
    mutationFn: (enabled: boolean) => api.setSlackInstallationEnabled(installation.id, enabled),
    onSuccess: (updated) => {
      invalidate()
      toast({title: updated.enabled ? 'Delivery enabled' : 'Delivery paused'})
    },
    onError: fail('Failed to update delivery'),
  })

  const runHealthCheck = useMutation({
    mutationFn: () => api.checkSlackInstallationHealth(installation.id),
    onSuccess: (updated) => {
      invalidate()
      const presentation = slackHealthPresentation(updated.health)
      toast({title: `Health: ${presentation.label}`, description: updated.healthDetail ?? presentation.description})
    },
    onError: fail('Health check failed'),
  })

  const sendTest = useMutation({
    mutationFn: () => api.testSlackInstallation(installation.id),
    onSuccess: (result) =>
      toast({
        title: result.success ? 'Test message sent' : 'Test failed',
        description: result.message,
        variant: result.success ? 'default' : 'destructive',
      }),
    onError: fail('Test failed'),
  })

  const remove = useMutation({
    mutationFn: () => api.deleteSlackInstallation(installation.id),
    onSuccess: () => {
      invalidate()
      toast({title: 'Removed', description: `${name} was disconnected from Slack delivery.`})
    },
    onError: fail('Failed to remove'),
  })

  const capabilityLabels = useMemo(
    () => new Map((catalog?.capabilities ?? []).map((c) => [c.id, c.label])),
    [catalog]
  )

  return (
    <div className="rounded-lg border bg-card">
      <div className="flex flex-wrap items-start justify-between gap-x-4 gap-y-2 p-3.5">
        <div className="min-w-0">
          <div className="flex flex-wrap items-center gap-2">
            <span className="truncate text-sm font-semibold">{name}</span>
            {installation.isDefault &&
              (hasWorkspace ? (
                <Badge variant="accent">Default</Badge>
              ) : (
                // Honestly surface an unexpected default flag on an install that
                // has no workspace, without presenting it as usable delivery.
                <Badge variant="warning">Default · needs workspace</Badge>
              ))}
            {orgWide && (
              <Badge variant="info" className="gap-1">
                <Building2 className="h-3 w-3" aria-hidden="true" />
                {SLACK_ORG_INSTALL_LABEL}
              </Badge>
            )}
            {partOfGrid && (
              <Badge variant="outline" className="gap-1 text-muted-foreground">
                <Building2 className="h-3 w-3" aria-hidden="true" />
                On Enterprise Grid
              </Badge>
            )}
          </div>
          <p className="mt-0.5 text-xs text-muted-foreground">
            {orgWide
              ? 'Organization-wide install'
              : partOfGrid
                ? `Workspace on ${installation.enterpriseName ?? 'an Enterprise Grid organization'}`
                : 'Workspace install'}
          </p>
          <p className="mt-1 flex items-center gap-1.5 text-xs text-muted-foreground">
            <StatusDot tone={health.tone} size="sm" />
            <span className="font-medium text-foreground">{health.label}</span>
            <span aria-hidden="true">·</span>
            <span>{slackHealthDescription(installation)}</span>
          </p>
        </div>
        <div className="flex shrink-0 items-center gap-2">
          <Badge variant={health.badge}>{health.label}</Badge>
          <Switch
            checked={installation.enabled}
            onCheckedChange={(checked) => setEnabled.mutate(checked)}
            disabled={setEnabled.isPending}
            aria-label={`Enable Slack delivery to ${name}`}
          />
        </div>
      </div>

      {health.needsReauthorization && (
        <div className="mx-3.5 mb-2 flex items-start gap-2 rounded-md border border-warning-border bg-warning-bg/40 px-3 py-2 text-xs">
          <AlertTriangle className="mt-0.5 h-3.5 w-3.5 shrink-0 text-warning-fg" aria-hidden="true" />
          <div className="space-y-1">
            <p className="font-medium text-foreground">Reauthorization needed</p>
            <ReauthorizationDetail installation={installation} missing={missing} />
          </div>
        </div>
      )}

      {showBindings && <WorkspaceBindings orgWide={orgWide} bindings={bindings} />}

      <dl className="grid grid-cols-[auto_1fr] items-center gap-x-3 gap-y-1.5 border-t px-3.5 py-2.5 text-xs">
        <dt className="text-muted-foreground">Default channel</dt>
        <dd className="m-0 flex items-center justify-end gap-2 text-right">
          <span className={hasWorkspace ? 'font-mono' : 'text-muted-foreground'}>
            {!hasWorkspace
              ? 'Per workspace'
              : installation.defaultChannelName
                ? `#${installation.defaultChannelName}`
                : 'None selected'}
          </span>
          {hasWorkspace && (
            <Button
              variant="ghost"
              size="sm"
              className="h-6 px-2 text-xs"
              onClick={() => setShowChannelPicker((v) => !v)}
              aria-expanded={showChannelPicker}
            >
              {showChannelPicker ? 'Close' : 'Change'}
            </Button>
          )}
        </dd>

        <dt className="text-muted-foreground">Capabilities</dt>
        <dd className="m-0 flex flex-wrap justify-end gap-1">
          {installation.enabledCapabilities.length === 0 ? (
            <span className="text-muted-foreground">—</span>
          ) : (
            installation.enabledCapabilities.map((id) => {
              const healthy = isCapabilityHealthy(installation, id)
              return (
                <span
                  key={id}
                  className={cn(
                    'inline-flex items-center gap-1 rounded border px-1.5 py-0.5 text-[11px] font-medium',
                    healthy
                      ? 'bg-muted/60 text-muted-foreground'
                      : 'border-warning-border bg-warning-bg/50 text-warning-fg'
                  )}
                  title={
                    healthy ? undefined : 'Slack has not granted every scope this capability needs'
                  }
                >
                  {!healthy && <StatusDot tone="warning" size="sm" />}
                  {capabilityLabels.get(id) ?? id}
                </span>
              )
            })
          )}
        </dd>
      </dl>

      {grants.length > 0 && <GrantList grants={grants} />}

      {showChannelPicker && hasWorkspace && (
        <ChannelPicker
          installation={installation}
          onDone={() => setShowChannelPicker(false)}
          onSaved={invalidate}
        />
      )}

      <div className="flex flex-wrap items-center justify-between gap-2 border-t px-3.5 py-2.5">
        <div className="flex flex-wrap items-center gap-2">
          {/* Only a workspace install can act as the default delivery target. */}
          {!installation.isDefault && hasWorkspace && (
            <Button
              variant="outline"
              size="sm"
              onClick={() => setDefault.mutate()}
              disabled={setDefault.isPending}
            >
              {setDefault.isPending ? (
                <Loader2 className="mr-1.5 h-3.5 w-3.5 animate-spin" aria-hidden="true" />
              ) : (
                <Check className="mr-1.5 h-3.5 w-3.5" aria-hidden="true" />
              )}
              Set as default
            </Button>
          )}
          <Button
            variant="outline"
            size="sm"
            onClick={() => runHealthCheck.mutate()}
            disabled={runHealthCheck.isPending}
          >
            {runHealthCheck.isPending ? (
              <Loader2 className="mr-1.5 h-3.5 w-3.5 animate-spin" aria-hidden="true" />
            ) : (
              <ShieldCheck className="mr-1.5 h-3.5 w-3.5" aria-hidden="true" />
            )}
            Run health check
          </Button>
          <Button
            variant="outline"
            size="sm"
            onClick={() => sendTest.mutate()}
            disabled={
              sendTest.isPending ||
              !hasWorkspace ||
              !installation.defaultChannelId ||
              !installation.enabled
            }
            title={
              !hasWorkspace
                ? SLACK_ORG_WORKSPACE_ACTION_NOTE
                : !installation.defaultChannelId
                  ? 'Select a default channel first'
                  : !installation.enabled
                    ? 'Enable delivery first'
                    : undefined
            }
          >
            {sendTest.isPending ? (
              <Loader2 className="mr-1.5 h-3.5 w-3.5 animate-spin" aria-hidden="true" />
            ) : (
              <Send className="mr-1.5 h-3.5 w-3.5" aria-hidden="true" />
            )}
            Send test message
          </Button>
          <Button
            variant={health.needsReauthorization ? 'default' : 'outline'}
            size="sm"
            onClick={onReauthorize}
          >
            <RefreshCw className="mr-1.5 h-3.5 w-3.5" aria-hidden="true" />
            Reauthorize
          </Button>
        </div>

        <Button
          variant="ghost"
          size="sm"
          className="text-destructive hover:text-destructive"
          onClick={() => setConfirmDelete(true)}
          aria-label={`Remove ${name}`}
        >
          <Trash2 className="mr-1.5 h-3.5 w-3.5" aria-hidden="true" />
          Remove
        </Button>
      </div>

      {confirmDelete && (
        <div className="border-t border-danger-border bg-danger-bg/30 px-3.5 py-3">
          <p className="text-sm font-medium">Remove {name}?</p>
          <p className="mt-0.5 text-xs text-muted-foreground">
            Delivery from this install stops immediately.
            {installation.isDefault && installationCount > 1
              ? ' Another install becomes the default.'
              : ''}{' '}
            You can add it again by reinstalling with Slack.
          </p>
          <div className="mt-3 flex justify-end gap-2">
            <Button variant="ghost" size="sm" onClick={() => setConfirmDelete(false)}>
              Cancel
            </Button>
            <Button
              variant="destructive"
              size="sm"
              onClick={() => remove.mutate()}
              disabled={remove.isPending}
            >
              {remove.isPending ? 'Removing…' : 'Remove install'}
            </Button>
          </div>
        </div>
      )}
    </div>
  )
}

// ── Reauthorization detail (missing bot vs. privileged-user scopes) ──────────

function ReauthorizationDetail({
  installation,
  missing,
}: Readonly<{
  installation: SlackInstallationSummary
  missing: {botScopes: string[]; userScopes: string[]}
}>) {
  // capabilityHealth splits the missing scopes by principal. If the backend
  // reported none (older payloads), fall back to the flat missingScopes list.
  if (missing.botScopes.length === 0 && missing.userScopes.length === 0) {
    return (
      <p className="text-muted-foreground">
        {installation.missingScopes.length > 0
          ? `Slack has not granted: ${installation.missingScopes.join(', ')}.`
          : 'Reauthorize with Slack to restore delivery.'}
      </p>
    )
  }
  return (
    <>
      {missing.botScopes.length > 0 && (
        <p className="text-muted-foreground">
          Bot scopes not granted: {missing.botScopes.join(', ')}. Reauthorize to add them.
        </p>
      )}
      {missing.userScopes.length > 0 && (
        <p className="text-muted-foreground">
          Privileged-user scopes not granted: {missing.userScopes.join(', ')}. A Slack admin or
          owner must reauthorize with privileged workspace access.
        </p>
      )}
    </>
  )
}

// ── Workspace bindings ───────────────────────────────────────────────────────
// The explicit workspaces an installation is attached to. An organization-wide
// install can be healthy with none, so the empty state is truthful, not an error.

function WorkspaceBindings({
  orgWide,
  bindings,
}: Readonly<{
  orgWide: boolean
  bindings: SlackWorkspaceBindingSummary[]
}>) {
  return (
    <div className="border-t px-3.5 py-2.5">
      <div className="flex items-center justify-between">
        <span className="flex items-center gap-1.5 text-xs font-medium text-foreground">
          <Users className="h-3.5 w-3.5 text-muted-foreground" aria-hidden="true" />
          Workspaces
        </span>
        {bindings.length > 0 && (
          <span className="text-[11px] text-muted-foreground">{bindings.length} attached</span>
        )}
      </div>
      {bindings.length === 0 ? (
        <p className="mt-1.5 text-xs text-muted-foreground">
          {orgWide ? SLACK_NO_BINDINGS_NOTE : 'No workspaces are attached to this installation.'}
        </p>
      ) : (
        <>
          <ul className="mt-1.5 space-y-1.5">
            {bindings.map((binding) => (
              <BindingRow key={binding.id} binding={binding} />
            ))}
          </ul>
          {bindings.length > 1 && (
            <p className="mt-1.5 text-[11px] text-muted-foreground">{SLACK_BINDING_HEALTH_NOTE}</p>
          )}
        </>
      )}
    </div>
  )
}

function BindingRow({binding}: Readonly<{binding: SlackWorkspaceBindingSummary}>) {
  const presentation = slackHealthPresentation(binding.health)
  const healthy = presentation.tone === 'success'
  return (
    <li className="text-xs">
      <div className="flex flex-wrap items-center gap-x-2 gap-y-0.5">
        <StatusDot tone={presentation.tone} size="sm" />
        <span className="truncate font-medium text-foreground">{bindingDisplayName(binding)}</span>
        {binding.isPrimary && (
          <Badge
            variant="outline"
            className="px-1 py-0 text-[10px] font-medium text-muted-foreground"
          >
            Primary
          </Badge>
        )}
        <span className="text-muted-foreground">· {presentation.label}</span>
        {!binding.enabled && <span className="text-muted-foreground">· Paused</span>}
        {binding.lastVerifiedAt && (
          <span className="text-muted-foreground">
            · Verified {formatRelativeTime(binding.lastVerifiedAt)}
          </span>
        )}
      </div>
      {binding.healthDetail && !healthy && (
        <p className="mt-0.5 text-[11px] text-muted-foreground">{binding.healthDetail}</p>
      )}
    </li>
  )
}

// ── OAuth grants (bot and privileged-user principals) ────────────────────────
// Each installation exposes its bot grant and any privileged-user grants as
// separate principals with their own scopes, health, and token lifecycle. Grant
// summaries never carry the tokens themselves.

function GrantList({grants}: Readonly<{grants: SlackGrantSummary[]}>) {
  const hasPrivilegedUser = grants.some((grant) => grant.grantType === 'USER')
  return (
    <div className="border-t px-3.5 py-2.5">
      <span className="flex items-center gap-1.5 text-xs font-medium text-foreground">
        <KeyRound className="h-3.5 w-3.5 text-muted-foreground" aria-hidden="true" />
        Authorizations
      </span>
      <ul className="mt-1.5 space-y-2">
        {grants.map((grant) => (
          <GrantRow key={grant.id} grant={grant} />
        ))}
      </ul>
      {hasPrivilegedUser && (
        <p className="mt-1.5 text-[11px] text-muted-foreground">{SLACK_PRIVILEGED_ACCESS_NOTE}</p>
      )}
    </div>
  )
}

function GrantRow({grant}: Readonly<{grant: SlackGrantSummary}>) {
  const presentation = slackHealthPresentation(grant.health)
  const healthy = presentation.tone === 'success'
  const Icon = grant.grantType === 'BOT' ? Bot : UserCog
  const expiry = slackExpiryLabel(grant.expiresAt)
  const scopeCount = grant.grantedScopes.length
  return (
    <li className="rounded-md border bg-muted/30 px-2.5 py-2">
      <div className="flex flex-wrap items-center gap-x-2 gap-y-1">
        <Icon className="h-3.5 w-3.5 text-muted-foreground" aria-hidden="true" />
        <span className="text-xs font-medium text-foreground">
          {SLACK_GRANT_PRINCIPAL_LABEL[grant.grantType]}
        </span>
        {grant.slackUserId && (
          <span className="font-mono text-[11px] text-muted-foreground">{grant.slackUserId}</span>
        )}
        <span className="flex items-center gap-1 text-[11px] text-muted-foreground">
          <StatusDot tone={presentation.tone} size="sm" />
          {presentation.label}
        </span>
        <span className="text-[11px] text-muted-foreground">
          · {scopeCount} scope{scopeCount === 1 ? '' : 's'}
        </span>
      </div>
      <p className="mt-0.5 text-[11px] text-muted-foreground">
        {SLACK_GRANT_PRINCIPAL_DESCRIPTION[grant.grantType]}
      </p>
      {(grant.revokedAt || expiry || grant.rotatedAt) && (
        <div className="mt-1 flex flex-wrap gap-1">
          {grant.revokedAt && (
            <GrantMeta icon={Ban} tone="danger">
              Revoked {formatRelativeTime(grant.revokedAt)}
            </GrantMeta>
          )}
          {expiry && (
            <GrantMeta icon={Clock} tone={expiry.expired ? 'danger' : 'muted'}>
              {expiry.text}
            </GrantMeta>
          )}
          {grant.rotatedAt && (
            <GrantMeta icon={RotateCw}>Rotated {formatRelativeTime(grant.rotatedAt)}</GrantMeta>
          )}
        </div>
      )}
      {grant.healthDetail && !healthy && (
        <p className="mt-1 text-[11px] text-warning-fg">{grant.healthDetail}</p>
      )}
    </li>
  )
}

function GrantMeta({
  icon: Icon,
  tone = 'muted',
  children,
}: Readonly<{
  icon: LucideIcon
  tone?: 'muted' | 'danger'
  children: ReactNode
}>) {
  return (
    <span
      className={cn(
        'inline-flex items-center gap-1 rounded border px-1.5 py-0.5 text-[10px]',
        tone === 'danger'
          ? 'border-danger-border bg-danger-bg/40 text-danger-fg'
          : 'border-border bg-background text-muted-foreground'
      )}
    >
      <Icon className="h-3 w-3" aria-hidden="true" />
      {children}
    </span>
  )
}

// ── Channel picker (lazy per installation) ───────────────────────────────────

function ChannelPicker({
  installation,
  onDone,
  onSaved,
}: Readonly<{
  installation: SlackInstallationSummary
  onDone: () => void
  onSaved: () => void
}>) {
  const {toast} = useToast()
  const channelsQuery = useQuery({
    queryKey: ['slackInstallationChannels', installation.id],
    queryFn: () => api.getSlackInstallationChannels(installation.id),
  })

  const setChannel = useMutation({
    mutationFn: ({channelId, channelName}: {channelId: string; channelName: string}) =>
      api.setSlackInstallationChannel(installation.id, channelId, channelName),
    onSuccess: () => {
      onSaved()
      toast({title: 'Channel updated'})
      onDone()
    },
    onError: (err: Error) =>
      toast({title: 'Failed to update channel', description: err.message, variant: 'destructive'}),
  })

  return (
    <div className="border-t bg-muted/30 px-3.5 py-3">
      <Label className="text-xs" htmlFor={`channel-${installation.id}`}>
        Default channel
      </Label>
      {channelsQuery.isError ? (
        <p className="mt-1.5 text-xs text-danger-fg">
          Couldn’t load channels. Reauthorize the workspace if its token was revoked.
        </p>
      ) : (
        <Select
          value={installation.defaultChannelId ?? ''}
          onValueChange={(value) => {
            const channel = channelsQuery.data?.channels.find((c) => c.id === value)
            if (channel) setChannel.mutate({channelId: channel.id, channelName: channel.name})
          }}
          disabled={channelsQuery.isLoading || setChannel.isPending}
        >
          <SelectTrigger id={`channel-${installation.id}`} className="mt-1.5">
            <SelectValue
              placeholder={channelsQuery.isLoading ? 'Loading channels…' : 'Select a channel'}
            />
          </SelectTrigger>
          <SelectContent>
            {channelsQuery.data?.channels.map((channel) => (
              <SelectItem key={channel.id} value={channel.id}>
                #{channel.name}
              </SelectItem>
            ))}
          </SelectContent>
        </Select>
      )}
    </div>
  )
}

// ── Capability picker (install / reauthorize) ────────────────────────────────

function CapabilityPicker({
  catalog,
  isLoading,
  isError,
  onRetry,
  mode,
  installation,
  onCancel,
}: Readonly<{
  catalog?: SlackCapabilitiesResponse
  isLoading: boolean
  isError: boolean
  onRetry: () => void
  mode: 'add' | 'reauthorize'
  installation?: SlackInstallationSummary
  onCancel: () => void
}>) {
  const {toast} = useToast()

  const capabilities = useMemo(() => catalog?.capabilities ?? [], [catalog])
  const sorted = useMemo(() => sortCapabilities(capabilities), [capabilities])
  const optionalIds = useMemo(() => optionalCapabilityIds(capabilities), [capabilities])

  // Optional capabilities (the Slack Assistant) start off, or from the current
  // grant when reauthorizing an existing workspace.
  const [optionalSelection, setOptionalSelection] = useState<Set<string>>(() => {
    const enabled = new Set(installation?.enabledCapabilities ?? [])
    return new Set(optionalIds.filter((id) => enabled.has(id)))
  })

  const selectedIds = useMemo(
    () => selectedCapabilityIds(capabilities, optionalSelection),
    [capabilities, optionalSelection]
  )
  const botScopes = useMemo(
    () => botScopesForCapabilities(capabilities, new Set(selectedIds)),
    [capabilities, selectedIds]
  )
  const userScopes = useMemo(
    () => userScopesForCapabilities(capabilities, new Set(selectedIds)),
    [capabilities, selectedIds]
  )

  const startInstall = useMutation({
    mutationFn: () =>
      mode === 'reauthorize' && installation
        ? api.reauthorizeSlackInstallation(installation.id, selectedIds)
        : api.startSlackInstallation(selectedIds),
    onSuccess: (data) => {
      trackEvent('Integration Connect', {provider: 'slack', mode})
      globalThis.location.href = data.authUrl
    },
    onError: (err: Error) =>
      toast({title: 'Couldn’t start Slack authorization', description: err.message, variant: 'destructive'}),
  })

  if (isLoading) {
    return (
      <div
        className="flex items-center justify-center gap-2 py-12 text-sm text-muted-foreground"
        role="status"
        aria-live="polite"
      >
        <Loader2 className="h-4 w-4 animate-spin" aria-hidden="true" />
        Loading capabilities…
      </div>
    )
  }

  if (isError) {
    return (
      <div className="py-10 text-center">
        <AlertTriangle className="mx-auto mb-2 h-8 w-8 text-warning-fg" aria-hidden="true" />
        <p className="text-sm font-medium">Couldn’t load Slack capabilities</p>
        <Button variant="outline" size="sm" className="mt-4" onClick={onRetry}>
          <RefreshCw className="mr-1.5 h-3.5 w-3.5" aria-hidden="true" />
          Retry
        </Button>
      </div>
    )
  }

  const requiredCaps = sorted.filter((c) => !c.optional)
  const optionalCaps = sorted.filter((c) => c.optional)

  return (
    <div className="space-y-4">
      <div className="space-y-2">
        <div className="flex items-center justify-between">
          <h3 className="text-sm font-semibold">Required capabilities</h3>
          <Badge variant="neutral">Always requested</Badge>
        </div>
        <p className="text-xs text-muted-foreground">{SLACK_LEAST_PRIVILEGE_NOTE}</p>
        <ul className="divide-y rounded-md border">
          {requiredCaps.map((capability) => (
            <CapabilityRow key={capability.id} capability={capability} checked disabled />
          ))}
        </ul>
      </div>

      {optionalCaps.length > 0 && (
        <div className="space-y-2">
          <h3 className="text-sm font-semibold">Optional</h3>
          <ul className="divide-y rounded-md border">
            {optionalCaps.map((capability) => (
              <CapabilityRow
                key={capability.id}
                capability={capability}
                checked={optionalSelection.has(capability.id)}
                onToggle={(next) =>
                  setOptionalSelection((prev) => {
                    const updated = new Set(prev)
                    if (next) updated.add(capability.id)
                    else updated.delete(capability.id)
                    return updated
                  })
                }
              />
            ))}
          </ul>
        </div>
      )}

      <div className="space-y-2 rounded-md border bg-muted/30 p-3">
        <p className="text-xs font-medium">
          Slack will be asked to grant {botScopes.length + userScopes.length} scopes
        </p>
        <ScopeCluster
          title="Bot scopes"
          hint="Granted to Moneat’s Slack app"
          scopes={botScopes}
        />
        {userScopes.length > 0 && (
          <>
            <ScopeCluster
              title="Privileged-user scopes"
              hint="Granted by a Slack admin or owner"
              scopes={userScopes}
            />
            <p className="text-[11px] text-muted-foreground">{SLACK_USER_SCOPE_PICKER_NOTE}</p>
          </>
        )}
        <p className="pt-1 text-[11px] text-muted-foreground">{SLACK_ADDITIVE_GRANTS_NOTE}</p>
        <p className="text-[11px] text-muted-foreground">{SLACK_REAUTHORIZATION_NOTE}</p>
      </div>

      <DialogFooter className="flex-row justify-between sm:justify-between">
        <Button variant="ghost" size="sm" onClick={onCancel} disabled={startInstall.isPending}>
          <ArrowLeft className="mr-1.5 h-3.5 w-3.5" aria-hidden="true" />
          Back
        </Button>
        <Button
          size="sm"
          onClick={() => startInstall.mutate()}
          disabled={startInstall.isPending || selectedIds.length === 0}
        >
          {startInstall.isPending ? (
            <Loader2 className="mr-1.5 h-3.5 w-3.5 animate-spin" aria-hidden="true" />
          ) : (
            <Slack className="mr-1.5 h-3.5 w-3.5" aria-hidden="true" />
          )}
          {mode === 'reauthorize' ? 'Continue to Slack' : 'Authorize with Slack'}
        </Button>
      </DialogFooter>
    </div>
  )
}

function CapabilityRow({
  capability,
  checked,
  disabled,
  onToggle,
}: Readonly<{
  capability: SlackCapabilityDefinition
  checked: boolean
  disabled?: boolean
  onToggle?: (next: boolean) => void
}>) {
  const checkboxId = `slack-capability-${capability.id}`
  const hasUserScopes = capabilityUsesUserScopes(capability)
  return (
    <li className="flex items-start gap-3 p-3">
      <Checkbox
        id={checkboxId}
        checked={checked}
        disabled={disabled}
        onCheckedChange={(value) => onToggle?.(value === true)}
        className="mt-0.5"
      />
      <div className="min-w-0 flex-1">
        <div className="flex flex-wrap items-center gap-2">
          <Label
            htmlFor={checkboxId}
            className={cn('text-sm font-medium', disabled && 'cursor-default')}
          >
            {capability.label}
          </Label>
          {hasUserScopes && (
            <Badge variant="warning" className="gap-1">
              <ShieldAlert className="h-3 w-3" aria-hidden="true" />
              Privileged user
            </Badge>
          )}
        </div>
        <p className="mt-0.5 text-xs text-muted-foreground">{capability.description}</p>
        {hasUserScopes ? (
          // A privileged capability spans two principals: keep the bot and
          // user scopes labelled so the extra authorization is unmistakable.
          <div className="mt-1.5 space-y-1.5">
            <ScopeCluster title="Bot scopes" scopes={capability.botScopes} chipClassName="bg-muted" />
            <ScopeCluster
              title="User scopes"
              scopes={capability.userScopes}
              chipClassName="bg-muted"
            />
            <p className="text-[11px] text-muted-foreground">{SLACK_PRIVILEGED_ACCESS_NOTE}</p>
          </div>
        ) : (
          <div className="mt-1.5 flex flex-wrap gap-1">
            {capability.botScopes.map((scope) => (
              <span
                key={scope}
                className="rounded bg-muted px-1.5 py-0.5 font-mono text-[10px] text-muted-foreground"
              >
                {scope}
              </span>
            ))}
          </div>
        )}
      </div>
    </li>
  )
}

// A labelled cluster of Slack scope chips, reused by the picker summary and the
// privileged capability row to keep bot and user scopes visually distinct.
function ScopeCluster({
  title,
  hint,
  scopes,
  chipClassName = 'bg-background',
}: Readonly<{
  title: string
  hint?: string
  scopes: string[]
  chipClassName?: string
}>) {
  if (scopes.length === 0) return null
  return (
    <div>
      <p className="text-[10px] font-medium uppercase tracking-wide text-muted-foreground">
        {title}
        {hint && <span className="ml-1 font-normal normal-case">· {hint}</span>}
      </p>
      <div className="mt-1 flex flex-wrap gap-1">
        {scopes.map((scope) => (
          <span
            key={scope}
            className={cn(
              'rounded px-1.5 py-0.5 font-mono text-[10px] text-muted-foreground',
              chipClassName
            )}
          >
            {scope}
          </span>
        ))}
      </div>
    </div>
  )
}
