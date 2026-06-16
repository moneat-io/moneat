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

import {useMemo, useState} from 'react'
import {useMutation, useQuery, useQueryClient} from '@tanstack/react-query'
import {
  AlertTriangle,
  Check,
  Copy,
  Inbox,
  KeyRound,
  Link2,
  Loader2,
  RefreshCw,
  RotateCw,
  ShieldCheck,
  Trash2,
  X,
} from 'lucide-react'

import {api} from '@/lib/api'
import type {
  ConnectorBindingInput,
  ConnectorInstallationResponse,
  ConnectorProviderDefinition,
  ConnectorProviderStateDetail,
} from '@/lib/api/types'
import {Badge} from '@/components/ui/badge'
import {Button} from '@/components/ui/button'
import {Input} from '@/components/ui/input'
import {Label} from '@/components/ui/label'
import {StatusDot} from '@/components/ui/status-dot'
import {Select, SelectContent, SelectItem, SelectTrigger, SelectValue} from '@/components/ui/select'
import {Tabs, TabsContent, TabsList, TabsTrigger} from '@/components/ui/tabs'
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
import {ConnectorLogo} from './BrandLogos'
import {
  describeConnectorHealth,
  effectiveConnectorState,
  formatProcessingLag,
  installAuthProfileId,
} from './connectorHealth'

const REVENUECAT_APP_RESOURCE_TYPE = 'revenuecat_app'
const PROJECT_LOCAL_RESOURCE_TYPE = 'project'
const UNMAPPED_VALUE = '__unmapped__'

const ENVIRONMENT_LABELS: Record<string, string> = {
  production_and_sandbox: 'Production & sandbox',
  production: 'Production',
  sandbox: 'Sandbox',
}

function ts(value: string | null | undefined): string {
  if (!value) return '—'
  return formatRelativeTime(value)
}

// RevenueCat project IDs are `proj…`; the setup field renders a fixed `proj` prefix and
// stores only the suffix. Normalize pasted input: pull the id out of a dashboard URL,
// drop stray whitespace, and strip a redundant leading `proj` so it isn't doubled.
function normalizeProjectIdInput(raw: string): string {
  let value = raw.trim()
  const fromUrl = value.match(/projects\/([^/?#\s]+)/i)
  if (fromUrl) value = fromUrl[1]
  return value.replace(/\s+/g, '').replace(/^proj/i, '')
}

// ── small primitives ─────────────────────────────────────────────────────────

function CopyButton({
  value,
  label,
  size = 'icon',
}: {
  readonly value: string
  readonly label: string
  readonly size?: 'icon' | 'sm'
}) {
  const {toast} = useToast()
  const [copied, setCopied] = useState(false)
  const onCopy = () => {
    void navigator.clipboard?.writeText(value)
    setCopied(true)
    toast({title: `${label} copied`})
    globalThis.setTimeout(() => setCopied(false), 1500)
  }
  const Icon = copied ? Check : Copy
  if (size === 'sm') {
    return (
      <Button type="button" variant="outline" size="sm" onClick={onCopy} aria-label={`Copy ${label}`}>
        <Icon className="mr-1.5 h-3.5 w-3.5" />
        {copied ? 'Copied' : 'Copy'}
      </Button>
    )
  }
  return (
    <Button type="button" variant="outline" size="icon" onClick={onCopy} aria-label={`Copy ${label}`}>
      <Icon className="h-4 w-4" />
    </Button>
  )
}

function CopyField({
  label,
  value,
  mono = true,
}: {
  readonly label: string
  readonly value: string
  readonly mono?: boolean
}) {
  return (
    <div className="space-y-1.5">
      <Label className="text-xs text-muted-foreground">{label}</Label>
      <div className="flex gap-2">
        <Input readOnly value={value} className={cn('text-sm', mono && 'font-mono')} />
        <CopyButton value={value} label={label} />
      </div>
    </div>
  )
}

function DetailRow({label, children}: {readonly label: string; readonly children: React.ReactNode}) {
  return (
    <>
      <dt className="text-muted-foreground">{label}</dt>
      <dd className="m-0 text-right font-medium">{children}</dd>
    </>
  )
}

function MetricTile({
  label,
  value,
  tone,
}: {
  readonly label: string
  readonly value: React.ReactNode
  readonly tone?: 'warning' | 'danger'
}) {
  return (
    <div
      className={cn(
        'rounded-md border bg-card px-2.5 py-2',
        tone === 'warning' && 'border-warning-border bg-warning-bg/40',
        tone === 'danger' && 'border-danger-border bg-danger-bg/40'
      )}
    >
      <div className="text-[11px] uppercase tracking-[0.04em] text-muted-foreground">{label}</div>
      <div className="mt-0.5 text-sm font-semibold tabular-nums">{value}</div>
    </div>
  )
}

function HealthBadge({state, enabled}: {readonly state?: string; readonly enabled?: boolean}) {
  const {badge, label, tone} = describeConnectorHealth(state, enabled)
  return (
    <Badge variant={badge} className="gap-1.5">
      <StatusDot tone={tone} size="sm" />
      {label}
    </Badge>
  )
}

// ── setup wizard (generic installation-backed connectors) ─────────────────────

const SETUP_STEPS = ['Connect', 'Webhook', 'Map apps'] as const

function WizardSteps({current}: {readonly current: number}) {
  return (
    <ol className="flex flex-wrap items-center gap-x-1.5 gap-y-1 text-xs" aria-label="Setup progress">
      {SETUP_STEPS.map((label, i) => {
        const n = i + 1
        const done = n < current
        const active = n === current
        return (
          <li key={label} className="flex items-center gap-1.5">
            <span
              className={cn(
                'flex h-5 w-5 items-center justify-center rounded-full border text-[11px] font-semibold',
                active && 'border-primary bg-primary text-primary-foreground',
                done && 'border-success-border bg-success-bg text-success-fg',
                !active && !done && 'border-border text-muted-foreground'
              )}
            >
              {done ? <Check className="h-3 w-3" /> : n}
            </span>
            <span className={cn('font-medium', active ? 'text-foreground' : 'text-muted-foreground')}>
              {label}
            </span>
            {n < SETUP_STEPS.length && <span className="mx-1 h-px w-5 bg-border" aria-hidden />}
          </li>
        )
      })}
    </ol>
  )
}

// Guided 3-step setup: ① connect the API key → ② surface the webhook URL + one-time
// token to paste into RevenueCat → ③ map apps to projects. Steps 2–3 reuse the same
// WebhookTab/MappingTab as Manage so there's one source of truth for that UI.
export function ConnectorSetupDialog({
  provider,
  onClose,
}: Readonly<{provider: ConnectorProviderDefinition; onClose: () => void}>) {
  const queryClient = useQueryClient()
  const {toast} = useToast()
  const [step, setStep] = useState(1)
  const [name, setName] = useState('')
  const [projectId, setProjectId] = useState('')
  const [secret, setSecret] = useState('')
  const [created, setCreated] = useState<ConnectorInstallationResponse | null>(null)

  const authProfileId = installAuthProfileId(provider) ?? 'project_api_key'
  const invalidate = () => {
    queryClient.invalidateQueries({queryKey: ['connectorInstallations']})
    queryClient.invalidateQueries({queryKey: ['connectorState']})
  }

  const create = useMutation({
    mutationFn: () =>
      api.createConnectorInstallation({
        providerId: provider.id,
        authProfileId,
        name: name.trim(),
        externalAccount: {projectId: `proj${projectId.trim()}`},
        secret: secret.trim(),
      }),
    onSuccess: (res) => {
      trackEvent('Connector Installed', {provider: provider.id})
      invalidate()
      setSecret('')
      setCreated(res)
      setStep(2)
    },
    onError: (err: Error) =>
      toast({title: 'Could not connect', description: err.message, variant: 'destructive'}),
  })

  const canSubmit = !!projectId.trim() && !!secret.trim() && !create.isPending

  return (
    <Dialog open onOpenChange={(open) => !open && onClose()}>
      <DialogContent className="flex max-h-[86vh] flex-col gap-0 overflow-hidden p-0 sm:max-w-2xl">
        <DialogHeader className="space-y-3 border-b px-5 py-4">
          <DialogTitle className="flex items-center gap-2">
            <ConnectorLogo providerId={provider.id} className="h-7 w-7" />
            Connect {provider.name}
          </DialogTitle>
          <WizardSteps current={step} />
        </DialogHeader>

        <div className="min-h-0 flex-1 overflow-y-auto px-5 py-4">
          {step === 1 && (
            <div className="space-y-4">
              <p className="text-xs leading-relaxed text-muted-foreground">
                Two connections get set up: Moneat reads your catalog with a {provider.name} API key,
                then {provider.name} pushes events to a Moneat webhook (next step). Data is imported
                from install onward — {provider.name} history isn’t backfilled.
              </p>
              <div className="space-y-1.5">
                <Label htmlFor="connector-name">Connection name</Label>
                <Input
                  id="connector-name"
                  placeholder="e.g. Production RevenueCat"
                  value={name}
                  onChange={(e) => setName(e.target.value)}
                />
                <p className="text-xs text-muted-foreground">
                  Optional — defaults to your {provider.name} project name.
                </p>
              </div>
              <div className="space-y-1.5">
                <Label htmlFor="connector-project">RevenueCat project ID</Label>
                <div className="flex">
                  <span className="inline-flex select-none items-center rounded-l-md border border-r-0 border-input bg-muted px-2.5 font-mono text-sm text-muted-foreground">
                    proj
                  </span>
                  <Input
                    id="connector-project"
                    placeholder="1ab2c3d4"
                    value={projectId}
                    onChange={(e) => setProjectId(normalizeProjectIdInput(e.target.value))}
                    className="rounded-l-none font-mono"
                    required
                  />
                </div>
                <p className="text-xs text-muted-foreground">
                  Paste just the ID — the <span className="font-mono">proj</span> prefix is added
                  for you. Find it in{' '}
                  <a
                    href="https://app.revenuecat.com"
                    target="_blank"
                    rel="noopener noreferrer"
                    className="text-primary underline-offset-2 hover:underline"
                  >
                    RevenueCat → Project settings ↗
                  </a>{' '}
                  or your dashboard URL (fine if it doesn’t show the{' '}
                  <span className="font-mono">proj</span> prefix).
                </p>
              </div>
              <div className="space-y-1.5">
                <Label htmlFor="connector-secret">RevenueCat V2 secret API key</Label>
                <Input
                  id="connector-secret"
                  type="password"
                  placeholder="sk_…"
                  value={secret}
                  onChange={(e) => setSecret(e.target.value)}
                  className="font-mono"
                  autoComplete="off"
                  required
                />
                <p className="text-xs text-muted-foreground">
                  A <span className="font-medium text-foreground">secret</span> key (starts with{' '}
                  <span className="font-mono">sk_</span>), not a public key. Create one in{' '}
                  <a
                    href="https://app.revenuecat.com"
                    target="_blank"
                    rel="noopener noreferrer"
                    className="text-primary underline-offset-2 hover:underline"
                  >
                    Project settings → API keys ↗
                  </a>
                  . Moneat validates it before connecting.
                </p>
              </div>
              <div className="rounded-md border bg-muted/40 p-3">
                <div className="flex items-center gap-2">
                  <ShieldCheck className="h-4 w-4 text-muted-foreground" />
                  <p className="text-xs font-medium">What this key needs</p>
                </div>
                <p className="mt-1.5 text-xs text-muted-foreground">
                  Under <span className="font-medium text-foreground">Project configuration</span>,
                  set these to <span className="font-medium text-foreground">Read only</span> — leave
                  everything else <span className="font-medium text-foreground">No access</span>:
                </p>
                <ul className="mt-2 space-y-1 text-xs">
                  <li className="flex items-baseline gap-2">
                    <span className="font-medium text-foreground">Projects</span>
                    <span className="text-muted-foreground">— validate the project</span>
                  </li>
                  <li className="flex items-baseline gap-2">
                    <span className="font-medium text-foreground">Apps</span>
                    <span className="text-muted-foreground">— list apps to map</span>
                  </li>
                  <li className="flex items-baseline gap-2">
                    <span className="font-medium text-foreground">Integrations</span>
                    <span className="text-muted-foreground">— optional; lets Moneat verify the webhook</span>
                  </li>
                </ul>
                <p className="mt-2 text-xs text-muted-foreground">
                  Moneat only reads — it never writes to {provider.name}. Purchases and subscriptions
                  arrive through the webhook, so no customer-data scopes are needed.
                </p>
              </div>
            </div>
          )}

          {step === 2 && created && (
            <WebhookTab
              installationId={created.id}
              active
              initialToken={created.webhookToken ?? undefined}
              onRotated={invalidate}
            />
          )}

          {step === 3 && created && <MappingTab installationId={created.id} active />}
        </div>

        <DialogFooter className="flex-row items-center justify-between border-t px-5 py-3 sm:justify-between">
          {step === 1 && (
            <>
              <Button type="button" variant="ghost" size="sm" onClick={onClose}>
                Cancel
              </Button>
              <Button type="button" size="sm" onClick={() => create.mutate()} disabled={!canSubmit}>
                {create.isPending && <Loader2 className="mr-1.5 h-4 w-4 animate-spin" />}
                Connect
              </Button>
            </>
          )}
          {step === 2 && (
            <>
              <Button type="button" variant="ghost" size="sm" onClick={onClose}>
                Finish later
              </Button>
              <Button type="button" size="sm" onClick={() => setStep(3)}>
                Next: map apps
              </Button>
            </>
          )}
          {step === 3 && (
            <>
              <Button type="button" variant="ghost" size="sm" onClick={() => setStep(2)}>
                Back
              </Button>
              <Button type="button" size="sm" onClick={onClose}>
                Done
              </Button>
            </>
          )}
        </DialogFooter>
      </DialogContent>
    </Dialog>
  )
}

// One-time webhook token reveal — shown on create and after a rotate. Presents the
// ready-to-paste `Authorization` header value (RevenueCat sends `Bearer <token>`).
function OneTimeToken({token}: {readonly token: string}) {
  const headerValue = `Bearer ${token}`
  return (
    <div className="space-y-3 rounded-md border border-warning-border bg-warning-bg/40 p-3">
      <div className="flex items-start gap-2">
        <AlertTriangle className="mt-0.5 h-4 w-4 shrink-0 text-warning-fg" />
        <p className="text-xs text-muted-foreground">
          <span className="font-medium text-foreground">Copy this now.</span> The webhook token is
          shown only once and can’t be retrieved later. Set it as the{' '}
          <span className="font-mono">Authorization</span> header on your RevenueCat webhook.
        </p>
      </div>
      <CopyField label="Authorization header value" value={headerValue} />
    </div>
  )
}

// ── manage dialog (RevenueCat installation) ──────────────────────────────────

type ManageTab = 'overview' | 'webhook' | 'mapping'

export function RevenueCatManageDialog({
  provider,
  installation,
  detail,
  onClose,
}: Readonly<{
  provider: ConnectorProviderDefinition
  installation: ConnectorInstallationResponse
  detail?: ConnectorProviderStateDetail | null
  onClose: () => void
}>) {
  const queryClient = useQueryClient()
  const {toast} = useToast()
  const id = installation.id
  const [tab, setTab] = useState<ManageTab>('overview')
  const [confirmDisconnect, setConfirmDisconnect] = useState(false)

  const fail = (title: string) => (err: Error) =>
    toast({title, description: err.message, variant: 'destructive'})
  const invalidateInstallation = () => {
    queryClient.invalidateQueries({queryKey: ['connectorInstallation', id]})
    queryClient.invalidateQueries({queryKey: ['connectorInstallations']})
    queryClient.invalidateQueries({queryKey: ['connectorState']})
  }

  // Keep the installation fresh after test / rotate without closing the dialog.
  const {data: inst = installation} = useQuery({
    queryKey: ['connectorInstallation', id],
    queryFn: () => api.getConnectorInstallation(id),
    initialData: installation,
  })

  const test = useMutation({
    mutationFn: () => api.testConnectorInstallation(id),
    onSuccess: (res) => {
      invalidateInstallation()
      toast({
        title: res.lastTestResult === 'failed' ? 'Test failed' : 'Connection healthy',
        description: res.lastTestResult === 'failed' ? (res.lastError ?? undefined) : undefined,
        variant: res.lastTestResult === 'failed' ? 'destructive' : 'default',
      })
    },
    onError: fail('Test failed'),
  })

  const disconnect = useMutation({
    mutationFn: () => api.deleteConnectorInstallation(id),
    onSuccess: () => {
      invalidateInstallation()
      toast({title: `${provider.name} disconnected`})
      onClose()
    },
    onError: fail('Failed to disconnect'),
  })

  const displayState = effectiveConnectorState(detail, inst)

  return (
    <Dialog open onOpenChange={(open) => !open && onClose()}>
      <DialogContent className="flex max-h-[86vh] flex-col gap-0 overflow-hidden p-0 sm:max-w-2xl">
        <DialogHeader className="space-y-1 border-b px-5 py-4">
          <DialogTitle className="flex items-center gap-2">
            <ConnectorLogo providerId={provider.id} className="h-7 w-7" />
            Manage {provider.name}
            <span className="ml-1">
              <HealthBadge state={displayState} enabled={inst.enabled} />
            </span>
          </DialogTitle>
          <DialogDescription className="font-mono text-xs">
            {inst.externalProjectName ?? '—'}
            {inst.externalProjectId ? ` · ${inst.externalProjectId}` : ''}
          </DialogDescription>
        </DialogHeader>

        <Tabs value={tab} onValueChange={(v) => setTab(v as ManageTab)} className="flex min-h-0 flex-1 flex-col">
          <TabsList className="mx-5 mt-3 w-fit">
            <TabsTrigger value="overview">Overview</TabsTrigger>
            <TabsTrigger value="webhook">Webhook</TabsTrigger>
            <TabsTrigger value="mapping">App mapping</TabsTrigger>
          </TabsList>

          <div className="min-h-0 flex-1 overflow-y-auto px-5 py-4">
            <TabsContent value="overview" className="mt-0">
              <OverviewTab
                installation={inst}
                detail={detail}
                displayState={displayState}
                onRotated={invalidateInstallation}
              />
            </TabsContent>
            <TabsContent value="webhook" className="mt-0">
              <WebhookTab installationId={id} active={tab === 'webhook'} onRotated={invalidateInstallation} />
            </TabsContent>
            <TabsContent value="mapping" className="mt-0">
              <MappingTab installationId={id} active={tab === 'mapping'} />
            </TabsContent>
          </div>
        </Tabs>

        <DialogFooter className="flex-row justify-between border-t px-5 py-3 sm:justify-between">
          {confirmDisconnect ? (
            <>
              <span className="self-center text-sm text-muted-foreground">
                Disconnect {provider.name}? Imports stop immediately.
              </span>
              <div className="flex gap-2">
                <Button variant="ghost" size="sm" onClick={() => setConfirmDisconnect(false)}>
                  Cancel
                </Button>
                <Button
                  variant="destructive"
                  size="sm"
                  onClick={() => disconnect.mutate()}
                  disabled={disconnect.isPending}
                >
                  {disconnect.isPending ? 'Disconnecting…' : 'Confirm disconnect'}
                </Button>
              </div>
            </>
          ) : (
            <>
              <Button
                variant="ghost"
                size="sm"
                className="text-destructive hover:text-destructive"
                onClick={() => setConfirmDisconnect(true)}
              >
                <Trash2 className="mr-1.5 h-3.5 w-3.5" />
                Disconnect
              </Button>
              <Button variant="outline" size="sm" onClick={() => test.mutate()} disabled={test.isPending}>
                {test.isPending ? (
                  <Loader2 className="mr-1.5 h-3.5 w-3.5 animate-spin" />
                ) : (
                  <RefreshCw className="mr-1.5 h-3.5 w-3.5" />
                )}
                Test connection
              </Button>
            </>
          )}
        </DialogFooter>
      </DialogContent>
    </Dialog>
  )
}

function OverviewTab({
  installation,
  detail,
  displayState,
  onRotated,
}: Readonly<{
  installation: ConnectorInstallationResponse
  detail?: ConnectorProviderStateDetail | null
  displayState?: string
  onRotated: () => void
}>) {
  const {toast} = useToast()
  const [rotateOpen, setRotateOpen] = useState(false)
  const [newSecret, setNewSecret] = useState('')

  const rotateKey = useMutation({
    mutationFn: () => api.rotateConnectorApiCredential(installation.id, {secret: newSecret.trim()}),
    onSuccess: () => {
      onRotated()
      setNewSecret('')
      setRotateOpen(false)
      toast({title: 'API key rotated'})
    },
    onError: (err: Error) =>
      toast({title: 'Failed to rotate API key', description: err.message, variant: 'destructive'}),
  })

  return (
    <div className="space-y-4">
      <dl className="grid grid-cols-[auto_1fr] gap-x-4 gap-y-2 rounded-md border bg-card px-3.5 py-3 text-xs">
        <DetailRow label="Status">
          <HealthBadge state={displayState} enabled={installation.enabled} />
        </DetailRow>
        <DetailRow label="RevenueCat project">
          <span className="font-mono">{installation.externalProjectName ?? installation.externalProjectId ?? '—'}</span>
        </DetailRow>
        <DetailRow label="API key">
          <span className="font-mono">
            {installation.apiSecretLastFour ? `•••• ${installation.apiSecretLastFour}` : '—'}
          </span>
        </DetailRow>
        <DetailRow label="Webhook token">
          <span className="font-mono">
            {installation.webhookTokenPrefix ? `${installation.webhookTokenPrefix}…` : 'Not set'}
          </span>
        </DetailRow>
        <DetailRow label="Last tested">{ts(installation.lastTestedAt)}</DetailRow>
        <DetailRow label="Last provider call">{ts(installation.lastSuccessfulProviderCallAt)}</DetailRow>
      </dl>

      {installation.lastError && (
        <div className="flex items-start gap-2 rounded-md border border-danger-border bg-danger-bg/40 p-2.5 text-xs text-danger-fg">
          <AlertTriangle className="mt-0.5 h-3.5 w-3.5 shrink-0" />
          <span className="break-words">{installation.lastError}</span>
        </div>
      )}

      <div>
        <div className="mb-1.5 text-[11px] font-bold uppercase tracking-[0.06em] text-muted-foreground/80">
          Import state
        </div>
        <div className="grid grid-cols-2 gap-2 sm:grid-cols-3">
          <MetricTile label="Mapped apps" value={(detail?.mappedResources ?? 0).toLocaleString()} />
          <MetricTile
            label="Unmapped events"
            value={(detail?.unmappedEvents ?? 0).toLocaleString()}
            tone={detail && detail.unmappedEvents > 0 ? 'warning' : undefined}
          />
          <MetricTile
            label="Failed receipts"
            value={(detail?.failedReceipts ?? 0).toLocaleString()}
            tone={detail && detail.failedReceipts > 0 ? 'danger' : undefined}
          />
          <MetricTile label="Production events" value={(detail?.productionEvents ?? 0).toLocaleString()} />
          <MetricTile label="Sandbox events" value={(detail?.sandboxEvents ?? 0).toLocaleString()} />
          <MetricTile label="Processing lag" value={formatProcessingLag(detail?.processingLagSeconds)} />
        </div>
        <dl className="mt-2 grid grid-cols-[auto_1fr] gap-x-4 gap-y-1.5 px-0.5 text-xs">
          <DetailRow label="Last accepted webhook">{ts(detail?.lastAcceptedWebhookAt)}</DetailRow>
          <DetailRow label="Last applied event">{ts(detail?.lastAppliedAt)}</DetailRow>
        </dl>
        {detail?.message && (
          <p className="mt-2 text-xs text-muted-foreground">{detail.message}</p>
        )}
        <p className="mt-2 text-xs text-muted-foreground">
          Imported data is since install — RevenueCat history isn’t backfilled.
        </p>
      </div>

      <div className="rounded-md border p-3">
        <div className="flex items-center justify-between gap-2">
          <div>
            <p className="text-sm font-medium">API key</p>
            <p className="text-xs text-muted-foreground">
              Replace the stored V2 secret key. The current key keeps working until you save.
            </p>
          </div>
          {!rotateOpen && (
            <Button variant="outline" size="sm" onClick={() => setRotateOpen(true)}>
              <KeyRound className="mr-1.5 h-3.5 w-3.5" />
              Rotate
            </Button>
          )}
        </div>
        {rotateOpen && (
          <div className="mt-3 space-y-2">
            <Input
              type="password"
              placeholder="New V2 secret API key (sk_…)"
              value={newSecret}
              onChange={(e) => setNewSecret(e.target.value)}
              className="font-mono"
              autoComplete="off"
            />
            <div className="flex justify-end gap-2">
              <Button
                variant="ghost"
                size="sm"
                onClick={() => {
                  setRotateOpen(false)
                  setNewSecret('')
                }}
              >
                Cancel
              </Button>
              <Button
                size="sm"
                onClick={() => rotateKey.mutate()}
                disabled={!newSecret.trim() || rotateKey.isPending}
              >
                {rotateKey.isPending ? 'Saving…' : 'Save key'}
              </Button>
            </div>
          </div>
        )}
      </div>
    </div>
  )
}

function WebhookTab({
  installationId,
  active,
  onRotated,
  initialToken,
}: Readonly<{
  installationId: string
  active: boolean
  onRotated: () => void
  // One-time token from a just-created installation (setup wizard). When present
  // the full Authorization value is revealed immediately and the rotate control is
  // hidden — there's nothing to rotate yet.
  initialToken?: string
}>) {
  const queryClient = useQueryClient()
  const {toast} = useToast()
  const [rotatedToken, setRotatedToken] = useState<string | null>(initialToken ?? null)
  const [confirmRotate, setConfirmRotate] = useState(false)
  const showRotate = !initialToken

  const {data, isLoading, error} = useQuery({
    queryKey: ['connectorWebhookSetup', installationId],
    queryFn: () => api.getConnectorWebhookSetup(installationId),
    enabled: active,
    retry: false,
  })

  const rotate = useMutation({
    mutationFn: () => api.rotateConnectorWebhookToken(installationId),
    onSuccess: (res) => {
      setRotatedToken(res.webhookToken ?? null)
      setConfirmRotate(false)
      queryClient.invalidateQueries({queryKey: ['connectorWebhookSetup', installationId]})
      onRotated()
      toast({title: 'Webhook token rotated'})
    },
    onError: (err: Error) =>
      toast({title: 'Failed to rotate webhook token', description: err.message, variant: 'destructive'}),
  })

  if (isLoading) {
    return (
      <div className="flex items-center gap-2 py-8 text-sm text-muted-foreground">
        <Loader2 className="h-4 w-4 animate-spin" /> Loading webhook setup…
      </div>
    )
  }
  if (error || !data) {
    return (
      <p className="py-8 text-sm text-muted-foreground">
        Webhook setup is unavailable. {(error as Error | undefined)?.message}
      </p>
    )
  }

  const envLabel = ENVIRONMENT_LABELS[data.recommendedEnvironment] ?? data.recommendedEnvironment
  const hasFullValue = !!data.authorizationHeaderValue || !!rotatedToken
  const effectiveHeaderValue = rotatedToken
    ? `Bearer ${rotatedToken}`
    : data.authorizationHeaderValue ?? null

  return (
    <div className="space-y-4">
      <p className="text-xs text-muted-foreground">
        Add this webhook in RevenueCat → Project settings → Integrations → Webhooks. Use the{' '}
        {envLabel.toLowerCase()} environment.
      </p>

      <CopyField label="Webhook URL" value={data.webhookUrl} />

      <div className="space-y-1.5">
        <Label className="text-xs text-muted-foreground">Authorization header</Label>
        <div className="flex gap-2">
          <Input readOnly value={data.authorizationHeaderName} className="max-w-[140px] font-mono text-sm" />
          {effectiveHeaderValue ? (
            <>
              <Input readOnly value={effectiveHeaderValue} className="font-mono text-sm" />
              <CopyButton value={effectiveHeaderValue} label="authorization header" />
            </>
          ) : (
            <Input
              readOnly
              value={data.authorizationHeaderPrefix ? `Bearer ${data.authorizationHeaderPrefix}…` : 'Not set'}
              className="font-mono text-sm"
            />
          )}
        </div>
        {!hasFullValue && (
          <p className="text-xs text-muted-foreground">
            Only the token prefix is stored. Rotate the webhook token below to reveal a new one-time
            value to paste into RevenueCat.
          </p>
        )}
      </div>

      {rotatedToken && <OneTimeToken token={rotatedToken} />}

      <div className="space-y-1.5">
        <div className="flex items-center justify-between">
          <Label className="text-xs text-muted-foreground">
            Recommended event types ({data.recommendedEventTypes.length})
          </Label>
          {data.recommendedEventTypes.length > 0 && (
            <CopyButton value={data.recommendedEventTypes.join('\n')} label="event types" size="sm" />
          )}
        </div>
        <div className="flex flex-wrap gap-1.5">
          {data.recommendedEventTypes.map((evt) => (
            <span
              key={evt}
              className="inline-flex items-center rounded border bg-muted/60 px-1.5 py-0.5 font-mono text-[11px] text-muted-foreground"
            >
              {evt}
            </span>
          ))}
        </div>
      </div>

      {data.warnings && data.warnings.length > 0 && (
        <div className="space-y-1.5 rounded-md border border-warning-border bg-warning-bg/40 p-2.5">
          {data.warnings.map((warning) => (
            <div key={warning} className="flex items-start gap-2 text-xs text-warning-fg">
              <AlertTriangle className="mt-0.5 h-3.5 w-3.5 shrink-0" />
              <span>{warning}</span>
            </div>
          ))}
        </div>
      )}

      {data.observedIntegrations && data.observedIntegrations.length > 0 && (
        <div className="space-y-1.5">
          <Label className="text-xs text-muted-foreground">Observed RevenueCat webhooks</Label>
          <div className="space-y-1.5">
            {data.observedIntegrations.map((obs) => (
              <div key={obs.id} className="rounded-md border px-2.5 py-2 text-xs">
                <div className="flex items-center justify-between gap-2">
                  <span className="truncate font-medium">{obs.name ?? obs.id}</span>
                  <div className="flex items-center gap-1.5">
                    {obs.matchesExpectedUrl && <Badge variant="success">This URL</Badge>}
                    <Badge variant={obs.enabled === false ? 'secondary' : 'neutral'}>
                      {obs.enabled === false ? 'Disabled' : 'Enabled'}
                    </Badge>
                  </div>
                </div>
                {obs.eventTypes && obs.eventTypes.length > 0 && (
                  <p className="mt-1 truncate font-mono text-[11px] text-muted-foreground">
                    {obs.eventTypes.join(', ')}
                  </p>
                )}
              </div>
            ))}
          </div>
        </div>
      )}

      {showRotate && (
        <div className="rounded-md border p-3">
          <div className="flex items-center justify-between gap-2">
            <div>
              <p className="text-sm font-medium">Webhook token</p>
              <p className="text-xs text-muted-foreground">
                Rotating invalidates the current token immediately and shows a new one once.
              </p>
            </div>
            {!confirmRotate && (
              <Button variant="outline" size="sm" onClick={() => setConfirmRotate(true)}>
                <RotateCw className="mr-1.5 h-3.5 w-3.5" />
                Rotate token
              </Button>
            )}
          </div>
          {confirmRotate && (
            <div className="mt-3 flex items-center justify-end gap-2">
              <Button variant="ghost" size="sm" onClick={() => setConfirmRotate(false)}>
                Cancel
              </Button>
              <Button size="sm" onClick={() => rotate.mutate()} disabled={rotate.isPending}>
                {rotate.isPending ? 'Rotating…' : 'Rotate & reveal'}
              </Button>
            </div>
          )}
        </div>
      )}
    </div>
  )
}

function MappingTab({installationId, active}: Readonly<{installationId: string; active: boolean}>) {
  const queryClient = useQueryClient()
  const {toast} = useToast()
  const [draft, setDraft] = useState<Record<string, string>>({})

  const {data: resourcesData, isLoading: resourcesLoading} = useQuery({
    queryKey: ['connectorResources', installationId],
    queryFn: () => api.getConnectorExternalResources(installationId),
    enabled: active,
  })
  const {data: bindingsData} = useQuery({
    queryKey: ['connectorBindings', installationId],
    queryFn: () => api.getConnectorBindings(installationId),
    enabled: active,
  })
  const {data: projects = []} = useQuery({
    queryKey: ['projects'],
    queryFn: () => api.getProjects(),
    enabled: active,
  })

  const apps = useMemo(
    () =>
      (resourcesData?.resources ?? []).filter(
        (r) => r.externalResourceType === REVENUECAT_APP_RESOURCE_TYPE
      ),
    [resourcesData]
  )

  // Active app→project bindings as they exist on the server.
  const currentMapping = useMemo(() => {
    const map: Record<string, string> = {}
    for (const binding of bindingsData?.bindings ?? []) {
      if (binding.externalResourceType === REVENUECAT_APP_RESOURCE_TYPE && binding.status === 'active') {
        map[binding.externalResourceId] = binding.localResourceId
      }
    }
    return map
  }, [bindingsData])

  const valueFor = (appId: string) => draft[appId] ?? currentMapping[appId] ?? ''
  const isDirty = Object.keys(draft).some((appId) => (draft[appId] ?? '') !== (currentMapping[appId] ?? ''))

  const refresh = useMutation({
    mutationFn: () => api.refreshConnectorExternalResources(installationId),
    onSuccess: () => {
      queryClient.invalidateQueries({queryKey: ['connectorResources', installationId]})
      toast({title: 'Apps refreshed'})
    },
    onError: (err: Error) =>
      toast({title: 'Failed to refresh apps', description: err.message, variant: 'destructive'}),
  })

  const save = useMutation({
    mutationFn: () => {
      // PUT /bindings is full replacement — always send the complete desired active
      // mapping. Apps left unmapped are simply omitted, which deactivates them.
      const bindings: ConnectorBindingInput[] = apps
        .map((app) => ({appId: app.externalResourceId, projectId: valueFor(app.externalResourceId)}))
        .filter((entry) => entry.projectId)
        .map((entry) => ({
          externalResourceType: REVENUECAT_APP_RESOURCE_TYPE,
          externalResourceId: entry.appId,
          localResourceType: PROJECT_LOCAL_RESOURCE_TYPE,
          localResourceId: entry.projectId,
        }))
      return api.updateConnectorBindings(installationId, {bindings})
    },
    onSuccess: () => {
      setDraft({})
      queryClient.invalidateQueries({queryKey: ['connectorBindings', installationId]})
      queryClient.invalidateQueries({queryKey: ['connectorState']})
      toast({title: 'App mapping saved'})
    },
    onError: (err: Error) =>
      toast({title: 'Failed to save mapping', description: err.message, variant: 'destructive'}),
  })

  return (
    <div className="space-y-3">
      <div className="flex items-center justify-between gap-2">
        <p className="text-xs text-muted-foreground">
          Map each RevenueCat app to one Moneat project. Each app maps to a single project; leave an
          app unmapped to stop importing it.
        </p>
        <Button variant="outline" size="sm" onClick={() => refresh.mutate()} disabled={refresh.isPending}>
          {refresh.isPending ? (
            <Loader2 className="mr-1.5 h-3.5 w-3.5 animate-spin" />
          ) : (
            <RefreshCw className="mr-1.5 h-3.5 w-3.5" />
          )}
          Refresh apps
        </Button>
      </div>

      {resourcesLoading ? (
        <div className="flex items-center gap-2 py-8 text-sm text-muted-foreground">
          <Loader2 className="h-4 w-4 animate-spin" /> Loading apps…
        </div>
      ) : apps.length === 0 ? (
        <div className="rounded-md border border-dashed p-8 text-center text-sm text-muted-foreground">
          <Inbox className="mx-auto mb-2 h-8 w-8 opacity-50" />
          <p className="font-medium">No RevenueCat apps yet</p>
          <p className="mt-0.5 text-xs">Refresh apps after creating apps in your RevenueCat project.</p>
        </div>
      ) : (
        <div className="divide-y rounded-md border">
          {apps.map((app) => {
            const mappedTo = valueFor(app.externalResourceId)
            return (
              <div key={app.externalResourceId} className="flex items-center gap-3 px-3 py-2">
                <Link2 className="h-4 w-4 shrink-0 text-muted-foreground" />
                <div className="min-w-0 flex-1">
                  <div className="truncate text-sm font-medium">
                    {app.displayName ?? app.externalResourceId}
                  </div>
                  <div className="truncate font-mono text-[11px] text-muted-foreground">
                    {app.externalResourceId}
                  </div>
                </div>
                <Select
                  value={mappedTo || UNMAPPED_VALUE}
                  onValueChange={(val) =>
                    setDraft((d) => ({
                      ...d,
                      [app.externalResourceId]: val === UNMAPPED_VALUE ? '' : val,
                    }))
                  }
                >
                  <SelectTrigger
                    className="h-8 w-[200px]"
                    aria-label={`Moneat project for ${app.displayName ?? app.externalResourceId}`}
                  >
                    <SelectValue placeholder="Not mapped" />
                  </SelectTrigger>
                  <SelectContent>
                    <SelectItem value={UNMAPPED_VALUE}>Not mapped</SelectItem>
                    {projects.map((project) => (
                      <SelectItem key={project.id} value={project.id}>
                        {project.name}
                      </SelectItem>
                    ))}
                  </SelectContent>
                </Select>
                {mappedTo ? (
                  <Button
                    type="button"
                    variant="ghost"
                    size="icon"
                    className="h-8 w-8 text-muted-foreground"
                    aria-label={`Unmap ${app.displayName ?? app.externalResourceId}`}
                    onClick={() => setDraft((d) => ({...d, [app.externalResourceId]: ''}))}
                  >
                    <X className="h-4 w-4" />
                  </Button>
                ) : (
                  <span className="w-8" />
                )}
              </div>
            )
          })}
        </div>
      )}

      <div className="flex items-center justify-end gap-2">
        {isDirty && (
          <Button variant="ghost" size="sm" onClick={() => setDraft({})}>
            Reset
          </Button>
        )}
        <Button size="sm" onClick={() => save.mutate()} disabled={!isDirty || save.isPending}>
          {save.isPending ? 'Saving…' : 'Save mapping'}
        </Button>
      </div>
    </div>
  )
}
