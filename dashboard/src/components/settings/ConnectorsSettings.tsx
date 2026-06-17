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

import {useMemo, useRef, useState} from 'react'
import {useMutation, useQuery, useQueryClient} from '@tanstack/react-query'
import {
  Bell,
  Download,
  LayoutDashboard,
  Link2,
  Loader2,
  Plus,
  Route,
  Search,
} from 'lucide-react'

import {api} from '@/lib/api'
import type {
  ConnectorConnectionState,
  ConnectorFamily,
  ConnectorHealth,
  ConnectorInstallationResponse,
  ConnectorProviderDefinition,
  ConnectorProviderStateDetail,
  ConnectorStateResponse,
  OrganizationIntegration,
} from '@/lib/api/types'
import {Badge} from '@/components/ui/badge'
import {Button} from '@/components/ui/button'
import {Label} from '@/components/ui/label'
import {Switch} from '@/components/ui/switch'
import {StatusDot} from '@/components/ui/status-dot'
import {Select, SelectContent, SelectItem, SelectTrigger, SelectValue} from '@/components/ui/select'
import {
  Dialog,
  DialogContent,
  DialogDescription,
  DialogFooter,
  DialogHeader,
  DialogTitle,
} from '@/components/ui/dialog'
import {cn} from '@/lib/utils'
import {useToast} from '@/hooks/useToast'
import {trackEvent} from '@/lib/analytics'
import {ConnectorLogo} from './BrandLogos'
import {SettingsSection} from './SettingsPrimitives'
import {ConnectorSetupDialog, RevenueCatManageDialog} from './ConnectorInstallationDialogs'
import {
  type HealthDescriptor,
  describeConnectorHealth,
  effectiveConnectorState,
  isInstallationBacked,
} from './connectorHealth'

// ── capability facets ───────────────────────────────────────────────────────
type Facet = 'all' | 'notifications' | 'workflows' | 'dataimport' | 'dashboard' | 'webhooks'

const FAMILY_TO_FACET: Record<ConnectorFamily, Exclude<Facet, 'all'>> = {
  notification: 'notifications',
  workflow_egress: 'workflows',
  data_import: 'dataimport',
  dashboard_query: 'dashboard',
  webhook_ingress: 'webhooks',
}

const FACET_META: Record<Exclude<Facet, 'all'>, {label: string; usedBy: string; icon: typeof Bell}> = {
  notifications: {label: 'Notifications', usedBy: 'Alerts', icon: Bell},
  workflows: {label: 'Workflows', usedBy: 'Workflows', icon: Route},
  dataimport: {label: 'Data import', usedBy: 'Data import', icon: Download},
  dashboard: {label: 'Dashboard data', usedBy: 'Dashboards', icon: LayoutDashboard},
  webhooks: {label: 'Webhooks', usedBy: 'Webhooks', icon: Link2},
}

const FACET_CHIPS: ReadonlyArray<{value: Facet; label: string; icon?: typeof Bell}> = [
  {value: 'all', label: 'All'},
  {value: 'notifications', label: 'Notifications', icon: Bell},
  {value: 'workflows', label: 'Workflows', icon: Route},
  {value: 'dataimport', label: 'Data import', icon: Download},
  {value: 'dashboard', label: 'Dashboard data', icon: LayoutDashboard},
  {value: 'webhooks', label: 'Webhooks', icon: Link2},
]

// OAuth-backed providers the dashboard can connect today.
const OAUTH_PROVIDERS = new Set(['slack', 'discord'])

interface ConnectorView {
  readonly provider: ConnectorProviderDefinition
  readonly facets: Exclude<Facet, 'all'>[]
  readonly availability: 'available' | 'planned' | 'enterprise'
  readonly integration?: OrganizationIntegration
  readonly state?: ConnectorConnectionState
  readonly installation?: ConnectorInstallationResponse
  readonly detail?: ConnectorProviderStateDetail | null
  readonly connected: boolean
}

type ConnectorAvailability = ConnectorView['availability']

function getProviderAvailability(provider: ConnectorProviderDefinition): ConnectorAvailability {
  const availabilities = new Set(provider.uses.map((use) => use.availability))
  if (availabilities.has('available')) return 'available'
  if (availabilities.has('planned')) return 'planned'
  return 'enterprise'
}

function getProviderHealth(connected: boolean, enabled?: boolean): ConnectorHealth {
  if (!connected) return 'unknown'
  return enabled ? 'healthy' : 'degraded'
}

function buildViews(
  providers: ConnectorProviderDefinition[],
  integrations: OrganizationIntegration[],
  states: Map<string, ConnectorConnectionState>,
  installations: Map<string, ConnectorInstallationResponse>,
  details: Map<string, ConnectorProviderStateDetail>
): ConnectorView[] {
  return providers.map((provider) => {
    const facets = [...new Set(provider.uses.map((u) => FAMILY_TO_FACET[u.family]))]
    const availability = getProviderAvailability(provider)
    const integration = integrations.find(
      (i) => i.integrationType === provider.id && i.isConfigured
    )
    // Connection comes from a configured OAuth integration (Slack/Discord) or an
    // installation-backed connector (RevenueCat); `state`/`detail` enrich it with
    // live health. Setup for unconnected providers still gates on availability.
    const state = states.get(provider.id)
    const installation = installations.get(provider.id)
    const detail = details.get(provider.id)
    return {
      provider,
      facets,
      availability,
      integration,
      state,
      installation,
      detail,
      connected: !!integration || !!installation,
    }
  })
}

// Notification connectors (Slack/Discord) read as "Active" when healthy. Shares
// the {tone, badge, label} shape with describeConnectorHealth so cards can render
// either uniformly.
function describeHealth(
  enabled: boolean,
  health: ConnectorHealth | null | undefined
): HealthDescriptor {
  if (!enabled) return {tone: 'neutral', badge: 'secondary', label: 'Disabled'}
  if (health === 'error') return {tone: 'danger', badge: 'danger', label: 'Error'}
  if (health === 'degraded') return {tone: 'warning', badge: 'warning', label: 'Degraded'}
  return {tone: 'success', badge: 'success', label: 'Active'}
}

function buildStateMap(state?: ConnectorStateResponse): Map<string, ConnectorConnectionState> {
  const byProvider = new Map<string, ConnectorConnectionState>(
    (state?.connections ?? []).map((connection) => [connection.providerId, connection])
  )

  for (const provider of state?.providers ?? []) {
    if (byProvider.has(provider.providerId)) continue
    const connectedUse = provider.uses.find((use) => use.connected)
    const connected = connectedUse !== undefined
    byProvider.set(provider.providerId, {
      providerId: provider.providerId,
      connected,
      health: getProviderHealth(connected, connectedUse?.enabled),
      detail: connectedUse?.message ?? null,
      lastCheckedAt: null,
    })
  }

  return byProvider
}

// Rich per-provider state detail (mapped/unmapped/failed counts, lag, etc.) for
// installation-backed connectors, keyed by provider id.
function buildDetailMap(
  state?: ConnectorStateResponse
): Map<string, ConnectorProviderStateDetail> {
  const byProvider = new Map<string, ConnectorProviderStateDetail>()
  for (const provider of state?.providers ?? []) {
    const withDetail = provider.uses.find((use) => use.detail)
    if (withDetail?.detail) byProvider.set(provider.providerId, withDetail.detail)
  }
  return byProvider
}

export function ConnectorsSettings() {
  const searchRef = useRef<HTMLInputElement>(null)
  const [query, setQuery] = useState('')
  const [facet, setFacet] = useState<Facet>('all')
  const [manageProviderId, setManageProviderId] = useState<string | null>(null)
  const [setupProviderId, setSetupProviderId] = useState<string | null>(null)

  const {data: catalog, isLoading: catalogLoading} = useQuery({
    queryKey: ['connectorProviders'],
    queryFn: () => api.getConnectorProviders(),
    enabled: api.isAuthenticated(),
  })
  const {data: integrations = []} = useQuery({
    queryKey: ['integrations'],
    queryFn: () => api.getIntegrations(),
    enabled: api.isAuthenticated(),
  })
  // Optional live health for configured connectors; failures here must not break
  // the catalog, so it degrades to an empty map.
  const {data: connectorState} = useQuery({
    queryKey: ['connectorState'],
    queryFn: () => api.getConnectorState(),
    enabled: api.isAuthenticated(),
    retry: false,
  })
  // Installations back the generic connectors (e.g. RevenueCat); presence here
  // marks a provider connected. Degrades to empty so OAuth connectors still work.
  const {data: installationsData} = useQuery({
    queryKey: ['connectorInstallations'],
    queryFn: () => api.getConnectorInstallations(),
    enabled: api.isAuthenticated(),
    retry: false,
  })

  const stateMap = useMemo(() => buildStateMap(connectorState), [connectorState])
  const detailMap = useMemo(() => buildDetailMap(connectorState), [connectorState])
  const installationMap = useMemo(() => {
    // listInstallations is newest-first and excludes deleted rows, so the first
    // installation per provider is the active one to manage.
    const map = new Map<string, ConnectorInstallationResponse>()
    for (const installation of installationsData?.installations ?? []) {
      if (!map.has(installation.providerId)) map.set(installation.providerId, installation)
    }
    return map
  }, [installationsData])

  const views = useMemo(
    () => buildViews(catalog?.providers ?? [], integrations, stateMap, installationMap, detailMap),
    [catalog, integrations, stateMap, installationMap, detailMap]
  )
  const manage = useMemo(
    () => views.find((v) => v.provider.id === manageProviderId) ?? null,
    [views, manageProviderId]
  )
  const setup = useMemo(
    () => views.find((v) => v.provider.id === setupProviderId) ?? null,
    [views, setupProviderId]
  )

  const filtered = useMemo(() => {
    const q = query.trim().toLowerCase()
    return views.filter((v) => {
      const okFacet = facet === 'all' || v.facets.includes(facet)
      const okQuery = !q || v.provider.name.toLowerCase().includes(q)
      return okFacet && okQuery
    })
  }, [views, facet, query])

  const connected = filtered.filter((v) => v.connected)
  const available = filtered.filter((v) => !v.connected)

  return (
    <section>
      <SettingsSection
        title="Connectors"
        description="Install and manage the external systems your organization connects to — for notifications, data import, dashboard sources, and automation. Connectors are owned here; feature pages just launch their setup."
        actions={
          <Button onClick={() => searchRef.current?.focus()}>
            <Plus className="mr-1.5 h-4 w-4" />
            Add connector
          </Button>
        }
      />

      {/* filter bar */}
      <div className="mb-5 flex flex-wrap items-center gap-3">
        <div className="flex h-[30px] min-w-[200px] items-center gap-2 rounded-md border bg-background px-2.5">
          <Search className="h-3.5 w-3.5 text-muted-foreground" />
          <input
            ref={searchRef}
            value={query}
            onChange={(e) => setQuery(e.target.value)}
            placeholder="Search connectors…"
            className="flex-1 bg-transparent text-sm outline-none placeholder:text-muted-foreground"
          />
        </div>
        <div className="flex flex-wrap gap-1.5">
          {FACET_CHIPS.map((chip) => {
            const Icon = chip.icon
            const on = facet === chip.value
            return (
              <button
                key={chip.value}
                type="button"
                onClick={() => setFacet(chip.value)}
                className={cn(
                  'inline-flex h-7 items-center gap-1.5 rounded-full border px-2.5 text-xs font-medium transition-colors',
                  on
                    ? 'border-primary/40 bg-primary/10 text-primary'
                    : 'bg-background text-muted-foreground hover:text-foreground'
                )}
              >
                {Icon && <Icon className="h-3 w-3" />}
                {chip.label}
              </button>
            )
          })}
        </div>
      </div>

      {catalogLoading ? (
        <div className="flex items-center gap-2 py-12 text-sm text-muted-foreground">
          <Loader2 className="h-4 w-4 animate-spin" /> Loading connectors…
        </div>
      ) : (
        <>
          {connected.length > 0 && (
            <ConnectorGroup title="Connected" count={connected.length}>
              {connected.map((v) => (
                <ConnectorCard
                  key={v.provider.id}
                  view={v}
                  onManage={() => setManageProviderId(v.provider.id)}
                  onSetup={() => setSetupProviderId(v.provider.id)}
                />
              ))}
            </ConnectorGroup>
          )}
          {available.length > 0 && (
            <ConnectorGroup title="Available">
              {available.map((v) => (
                <ConnectorCard
                  key={v.provider.id}
                  view={v}
                  onManage={() => setManageProviderId(v.provider.id)}
                  onSetup={() => setSetupProviderId(v.provider.id)}
                />
              ))}
            </ConnectorGroup>
          )}
          {filtered.length === 0 && (
            <p className="py-12 text-center text-sm text-muted-foreground">
              No connectors match your filters.
            </p>
          )}
        </>
      )}

      <ConnectorManageDialogSwitch view={manage} onClose={() => setManageProviderId(null)} />

      {setup && (
        <ConnectorSetupDialog provider={setup.provider} onClose={() => setSetupProviderId(null)} />
      )}
    </section>
  )
}

function ConnectorGroup({
  title,
  count,
  children,
}: {
  readonly title: string
  readonly count?: number
  readonly children: React.ReactNode
}) {
  return (
    <div className="mb-6">
      <div className="mb-2.5 flex items-center gap-2 text-[11px] font-bold uppercase tracking-[0.06em] text-muted-foreground/80">
        {title}
        {count !== undefined && (
          <span className="rounded bg-muted px-1.5 py-0.5 text-[11px] tabular-nums text-muted-foreground">
            {count}
          </span>
        )}
      </div>
      <div className="grid grid-cols-1 gap-3 lg:grid-cols-2">{children}</div>
    </div>
  )
}

function ConnectorManageDialogSwitch({
  view,
  onClose,
}: Readonly<{view?: ConnectorView | null; onClose: () => void}>) {
  if (!view) return null
  if (view.installation) {
    return (
      <RevenueCatManageDialog
        provider={view.provider}
        installation={view.installation}
        detail={view.detail}
        onClose={onClose}
      />
    )
  }
  return <ManageConnectorDialog view={view} onClose={onClose} />
}

function ConnectorCard({
  view,
  onManage,
  onSetup,
}: Readonly<{view: ConnectorView; onManage: () => void; onSetup: () => void}>) {
  const {toast} = useToast()
  const {provider, facets, availability, integration, installation, detail, connected, state} = view

  const installationBacked = isInstallationBacked(provider)
  const enabled = installationBacked ? (installation?.enabled ?? true) : (integration?.enabled ?? false)
  const installState = installationBacked ? effectiveConnectorState(detail, installation) : undefined
  const footerHealth = installationBacked
    ? describeConnectorHealth(installState, enabled)
    : describeHealth(enabled, state?.health)
  const footerTitle = installationBacked ? (detail?.message ?? undefined) : (state?.detail ?? undefined)

  const slackOAuth = useMutation({
    mutationFn: () =>
      provider.id === 'discord' ? api.startDiscordOAuth() : api.startSlackOAuth(),
    onSuccess: (data) => {
      trackEvent('Integration Connect', {provider: provider.id})
      globalThis.location.href = data.authUrl
    },
    onError: (err: Error) =>
      toast({title: 'Failed to start connection', description: err.message, variant: 'destructive'}),
  })

  const canOAuthConnect = OAUTH_PROVIDERS.has(provider.id) && availability === 'available'
  const canInstall = installationBacked && availability === 'available'
  const usedBy = [...new Set(facets.map((f) => FACET_META[f].usedBy))].slice(0, 2).join(', ') || '—'

  return (
    <div className={cn('flex flex-col overflow-hidden rounded-lg border bg-card', !connected && availability !== 'available' && 'opacity-70')}>
      <ConnectorCardHeader
        provider={provider}
        connected={connected}
        installationBacked={installationBacked}
        enabled={enabled}
        availability={availability}
        state={state}
        footerHealth={footerHealth}
      />

      {!connected && (
        <p className="px-3.5 pt-2 text-xs leading-relaxed text-muted-foreground">
          {provider.description}
        </p>
      )}

      <div className="flex flex-wrap gap-1.5 px-3.5 py-3">
        {facets.map((f) => {
          const Icon = FACET_META[f].icon
          return (
            <span
              key={f}
              className="inline-flex h-[19px] items-center gap-1 rounded border bg-muted/60 px-1.5 text-[11px] font-medium text-muted-foreground"
            >
              <Icon className="h-3 w-3" />
              {FACET_META[f].label}
            </span>
          )
        })}
      </div>

      <ConnectorCardDetails
        connected={connected}
        usedBy={usedBy}
        installation={installationBacked ? installation : undefined}
        integration={integration}
      />

      <div className="mt-auto flex items-center justify-between gap-2 border-t px-3.5 py-2.5">
        <ConnectorCardFooter
          connected={connected}
          footerTitle={footerTitle}
          footerHealth={footerHealth}
          canOAuthConnect={canOAuthConnect}
          canInstall={canInstall}
          oauthPending={slackOAuth.isPending}
          availability={availability}
          onManage={onManage}
          onOAuthConnect={() => slackOAuth.mutate()}
          onSetup={onSetup}
        />
      </div>
    </div>
  )
}

function ConnectorCardHeader({
  provider,
  connected,
  installationBacked,
  enabled,
  availability,
  state,
  footerHealth,
}: {
  readonly provider: ConnectorProviderDefinition
  readonly connected: boolean
  readonly installationBacked: boolean
  readonly enabled: boolean
  readonly availability: ConnectorAvailability
  readonly state?: ConnectorConnectionState
  readonly footerHealth: HealthDescriptor
}) {
  return (
    <div className="flex items-start gap-3 p-3.5 pb-0">
      <ConnectorLogo providerId={provider.id} />
      <div className="min-w-0 flex-1">
        <div className="flex items-center justify-between gap-2">
          <span className="truncate text-sm font-semibold">{provider.name}</span>
          <ConnectorCardStatus
            connected={connected}
            installationBacked={installationBacked}
            enabled={enabled}
            availability={availability}
            health={state?.health}
            footerHealth={footerHealth}
          />
        </div>
      </div>
    </div>
  )
}

function ConnectorCardStatus({
  connected,
  installationBacked,
  enabled,
  availability,
  health,
  footerHealth,
}: {
  readonly connected: boolean
  readonly installationBacked: boolean
  readonly enabled: boolean
  readonly availability: ConnectorAvailability
  readonly health?: ConnectorHealth | null
  readonly footerHealth: HealthDescriptor
}) {
  if (connected && installationBacked) {
    return <Badge variant={footerHealth.badge}>{footerHealth.label}</Badge>
  }
  return <StatusBadge connected={connected} enabled={enabled} availability={availability} health={health} />
}

function ConnectorCardDetails({
  connected,
  usedBy,
  installation,
  integration,
}: {
  readonly connected: boolean
  readonly usedBy: string
  readonly installation?: ConnectorInstallationResponse
  readonly integration?: OrganizationIntegration
}) {
  if (!connected) return null
  return (
    <dl className="grid grid-cols-[auto_1fr] gap-x-3 gap-y-1.5 border-t px-3.5 py-2.5 text-xs">
      <dt className="text-muted-foreground">Used by</dt>
      <dd className="m-0 text-right font-medium">{usedBy}</dd>
      <ConnectorCardConnectionFields installation={installation} integration={integration} />
    </dl>
  )
}

function ConnectorCardConnectionFields({
  installation,
  integration,
}: {
  readonly installation?: ConnectorInstallationResponse
  readonly integration?: OrganizationIntegration
}) {
  if (installation) {
    return (
      <>
        <dt className="text-muted-foreground">Project</dt>
        <dd className="m-0 truncate text-right font-mono">
          {installation.externalProjectName ?? installation.externalProjectId ?? '—'}
        </dd>
        {installation.apiSecretLastFour && (
          <>
            <dt className="text-muted-foreground">API key</dt>
            <dd className="m-0 text-right font-mono">•••• {installation.apiSecretLastFour}</dd>
          </>
        )}
      </>
    )
  }
  return (
    <>
      {integration?.channelName && (
        <>
          <dt className="text-muted-foreground">Channel</dt>
          <dd className="m-0 text-right font-mono">#{integration.channelName}</dd>
        </>
      )}
      {integration?.teamName && (
        <>
          <dt className="text-muted-foreground">Workspace</dt>
          <dd className="m-0 truncate text-right font-medium">{integration.teamName}</dd>
        </>
      )}
    </>
  )
}

function ConnectorCardFooter({
  connected,
  footerTitle,
  footerHealth,
  canOAuthConnect,
  canInstall,
  oauthPending,
  availability,
  onManage,
  onOAuthConnect,
  onSetup,
}: {
  readonly connected: boolean
  readonly footerTitle?: string
  readonly footerHealth: HealthDescriptor
  readonly canOAuthConnect: boolean
  readonly canInstall: boolean
  readonly oauthPending: boolean
  readonly availability: ConnectorAvailability
  readonly onManage: () => void
  readonly onOAuthConnect: () => void
  readonly onSetup: () => void
}) {
  if (connected) {
    return (
      <>
        <span className="inline-flex items-center gap-1.5 text-xs text-muted-foreground" title={footerTitle}>
          <StatusDot tone={footerHealth.tone} size="sm" />
          {footerHealth.label}
        </span>
        <Button variant="outline" size="sm" onClick={onManage}>
          Manage
        </Button>
      </>
    )
  }

  if (canOAuthConnect) {
    return (
      <>
        <span />
        <Button size="sm" onClick={onOAuthConnect} disabled={oauthPending}>
          <ConnectorConnectButtonIcon pending={oauthPending} />
          Connect
        </Button>
      </>
    )
  }

  if (canInstall) {
    return (
      <>
        <span />
        <Button size="sm" onClick={onSetup}>
          <Plus className="mr-1.5 h-3.5 w-3.5" />
          Connect
        </Button>
      </>
    )
  }

  return (
    <>
      <span />
      <UnavailableConnectorButton availability={availability} />
    </>
  )
}

function ConnectorConnectButtonIcon({pending}: Readonly<{pending: boolean}>) {
  if (pending) return <Loader2 className="mr-1.5 h-3.5 w-3.5 animate-spin" />
  return <Plus className="mr-1.5 h-3.5 w-3.5" />
}

function UnavailableConnectorButton({
  availability,
}: Readonly<{availability: ConnectorAvailability}>) {
  if (availability === 'enterprise') {
    return (
      <Button size="sm" variant="outline" disabled title="Enterprise plan">
        Enterprise
      </Button>
    )
  }
  return (
    <Button size="sm" variant="outline" disabled title="Coming soon">
      Coming soon
    </Button>
  )
}

function StatusBadge({
  connected,
  enabled,
  availability,
  health,
}: {
  readonly connected: boolean
  readonly enabled: boolean
  readonly availability: ConnectorAvailability
  readonly health?: ConnectorHealth | null
}) {
  if (connected) {
    if (!enabled) return <Badge variant="secondary">Disabled</Badge>
    if (health === 'error') return <Badge variant="destructive">Error</Badge>
    if (health === 'degraded') return <Badge variant="warning">Degraded</Badge>
    return <Badge variant="success">Connected</Badge>
  }
  if (availability === 'planned') return <Badge variant="secondary">Planned</Badge>
  if (availability === 'enterprise') return <Badge variant="info">Enterprise</Badge>
  return <Badge variant="outline">Not connected</Badge>
}

// ── Manage dialog (Slack / Discord live controls) ───────────────────────────
function ManageConnectorDialog({view, onClose}: Readonly<{view: ConnectorView; onClose: () => void}>) {
  const {provider, integration} = view
  const isDiscord = provider.id === 'discord'
  const queryClient = useQueryClient()
  const {toast} = useToast()
  const [confirmDisconnect, setConfirmDisconnect] = useState(false)

  const invalidate = () => queryClient.invalidateQueries({queryKey: ['integrations']})
  const fail = (title: string) => (err: Error) =>
    toast({title, description: err.message, variant: 'destructive'})

  const {data: channelsData, isLoading: channelsLoading} = useQuery({
    queryKey: [isDiscord ? 'discordChannels' : 'slackChannels'],
    queryFn: () => (isDiscord ? api.getDiscordChannels() : api.getSlackChannels()),
    enabled: !!integration?.isConfigured,
  })

  const updateChannel = useMutation({
    mutationFn: ({channelId, channelName}: {channelId: string; channelName: string}) =>
      isDiscord
        ? api.updateDiscordChannel(channelId, channelName)
        : api.updateSlackChannel(channelId, channelName),
    onSuccess: () => {
      invalidate()
      toast({title: 'Channel updated'})
    },
    onError: fail('Failed to update channel'),
  })

  const toggle = useMutation({
    mutationFn: () =>
      isDiscord ? api.toggleDiscordIntegration() : api.toggleSlackIntegration(),
    onSuccess: () => {
      invalidate()
      toast({title: `${provider.name} updated`})
    },
    onError: fail('Failed to update connector'),
  })

  const test = useMutation({
    mutationFn: () =>
      isDiscord ? api.testDiscordIntegration() : api.testSlackIntegration(),
    onSuccess: (res) =>
      toast({
        title: res.success ? 'Test successful' : 'Test failed',
        description: res.message,
        variant: res.success ? 'default' : 'destructive',
      }),
    onError: fail('Test failed'),
  })

  const disconnect = useMutation({
    mutationFn: () =>
      isDiscord ? api.deleteDiscordIntegration() : api.deleteSlackIntegration(),
    onSuccess: () => {
      invalidate()
      toast({title: `${provider.name} disconnected`})
      onClose()
    },
    onError: fail('Failed to disconnect'),
  })

  return (
    <Dialog open onOpenChange={(open) => !open && onClose()}>
      <DialogContent className="sm:max-w-md">
        <DialogHeader>
          <DialogTitle className="flex items-center gap-2">
            <ConnectorLogo providerId={provider.id} className="h-7 w-7" />
            Manage {provider.name}
          </DialogTitle>
          <DialogDescription>
            {integration?.teamName ? `Connected to ${integration.teamName}.` : 'Connected.'} Channel
            delivery rules live in your workflows.
          </DialogDescription>
        </DialogHeader>

        <div className="space-y-5 py-1">
          <div className="space-y-2">
            <Label>Notification channel</Label>
            <Select
              value={integration?.channelId || ''}
              onValueChange={(val) => {
                const channel = channelsData?.channels.find((c) => c.id === val)
                if (channel) updateChannel.mutate({channelId: channel.id, channelName: channel.name})
              }}
              disabled={channelsLoading || updateChannel.isPending}
            >
              <SelectTrigger>
                <SelectValue placeholder={channelsLoading ? 'Loading channels…' : 'Select a channel'} />
              </SelectTrigger>
              <SelectContent>
                {channelsData?.channels.map((channel) => (
                  <SelectItem key={channel.id} value={channel.id}>
                    #{channel.name}
                  </SelectItem>
                ))}
              </SelectContent>
            </Select>
          </div>

          <div className="flex items-center justify-between rounded-md border px-3 py-2.5">
            <div>
              <p className="text-sm font-medium">Enable notifications</p>
              <p className="text-xs text-muted-foreground">Pause delivery without disconnecting.</p>
            </div>
            <Switch
              checked={integration?.enabled ?? false}
              onCheckedChange={() => toggle.mutate()}
              disabled={toggle.isPending}
            />
          </div>

          {confirmDisconnect ? (
            <div className="rounded-md border border-danger-border bg-danger-bg/40 p-3">
              <p className="text-sm font-medium">Disconnect {provider.name}?</p>
              <p className="mt-0.5 text-xs text-muted-foreground">
                Notifications stop immediately. You can reconnect anytime.
              </p>
              <div className="mt-3 flex justify-end gap-2">
                <Button variant="ghost" size="sm" onClick={() => setConfirmDisconnect(false)}>
                  Cancel
                </Button>
                <Button
                  variant="destructive"
                  size="sm"
                  onClick={() => disconnect.mutate()}
                  disabled={disconnect.isPending}
                >
                  {disconnect.isPending ? 'Disconnecting…' : 'Disconnect'}
                </Button>
              </div>
            </div>
          ) : null}
        </div>

        <DialogFooter className="flex-row justify-between sm:justify-between">
          <Button
            variant="ghost"
            size="sm"
            className="text-destructive hover:text-destructive"
            onClick={() => setConfirmDisconnect(true)}
          >
            Disconnect
          </Button>
          <Button
            variant="outline"
            size="sm"
            onClick={() => test.mutate()}
            disabled={test.isPending}
          >
            {test.isPending ? <Loader2 className="mr-1.5 h-3.5 w-3.5 animate-spin" /> : null}
            Test connection
          </Button>
        </DialogFooter>
      </DialogContent>
    </Dialog>
  )
}
