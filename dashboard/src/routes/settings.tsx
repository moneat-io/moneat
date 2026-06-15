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

import {type FormEvent, Fragment, useEffect, useMemo, useState} from 'react'
import {createFileRoute, Link, redirect, useNavigate, useSearch} from '@tanstack/react-router'
import {useMutation, useQuery, useQueryClient} from '@tanstack/react-query'
import {loadStripe} from '@stripe/stripe-js'
import {Elements, PaymentElement, useElements, useStripe} from '@stripe/react-stripe-js'
import {
  api,
  type AuthToken,
} from '@/lib/api'
import {OtlpApiKeysTab} from '@/components/settings/OtlpApiKeysTab'
import {AgentApiKeysTab} from '@/components/settings/AgentApiKeysTab'
import {ApmSpanUsageBreakdown} from '@/components/settings/ApmSpanUsageBreakdown'
import {McpApiKeysTab} from '@/components/settings/McpApiKeysTab'
import {RbacSettings} from '@/components/settings/RbacSettings'
import {GeneralSettings} from '@/components/settings/GeneralSettings'
import {PreferencesSettings} from '@/components/settings/PreferencesSettings'
import {ConnectorsSettings} from '@/components/settings/ConnectorsSettings'
import {SettingRow, SettingsBlock, SettingsSection} from '@/components/settings/SettingsPrimitives'
import {trackEvent} from '@/lib/analytics'
import {buildPricingCardModel} from '@/lib/pricing-display'
import {Button} from '@/components/ui/button'
import {Input} from '@/components/ui/input'
import {Label} from '@/components/ui/label'
import {Card, CardContent, CardDescription, CardHeader, CardTitle} from '@/components/ui/card'
import {Tabs, TabsContent, TabsList, TabsTrigger} from '@/components/ui/tabs'
import {cn} from '@/lib/utils'
import {Table, TableBody, TableCell, TableHead, TableHeader, TableRow} from '@/components/ui/table'
import {Badge} from '@/components/ui/badge'
import {Checkbox} from '@/components/ui/checkbox'
import {
  Dialog,
  DialogContent,
  DialogDescription,
  DialogFooter,
  DialogHeader,
  DialogTitle,
} from '@/components/ui/dialog'
import {Tooltip, TooltipContent, TooltipProvider, TooltipTrigger} from '@/components/ui/tooltip'
import {Select, SelectContent, SelectItem, SelectTrigger, SelectValue} from '@/components/ui/select'
import {Switch} from '@/components/ui/switch'
import {SectionCard} from '@/components/ui/section-card'
import {StatusDot} from '@/components/ui/status-dot'
import {useToast} from '@/hooks/useToast'
import {
  Activity,
  AlertCircle,
  AlertTriangle,
  Bell,
  BellOff,
  BookOpen,
  Building2,
  Calendar,
  Check,
  CheckCircle2,
  Clock,
  Copy,
  CreditCard,
  Database,
  Download,
  Gauge,
  Info,
  Key,
  Layers,
  LayoutDashboard,
  Loader2,
  Lock,
  Mail,
  Minus,
  Phone,
  Plug,
  Plus,
  Receipt,
  Server,
  ShieldCheck,
  SlidersHorizontal,
  Trash2,
  TrendingUp,
  Users,
} from 'lucide-react'
import {SsoTab} from '@/components/SsoSettings'
import {TeamSettings} from '@/components/settings/TeamSettings'
import {useAuth} from '@/hooks/useAuth'
import {useEnterpriseFeatures, useIsSelfHosted, hasEnterpriseModule} from '@/hooks/useEnterpriseFeatures'
import {useTimezone} from '@/hooks/useTimezone'
import {formatDate as formatDateUtil, formatDateTime as formatDateTimeUtil} from '@/lib/date-format'

const AUTH_TOKEN_SCOPES = [
  { group: 'Service', scopes: ['project:read', 'project:write'] },
  { group: 'Releases', scopes: ['releases:read', 'releases:write'] },
  { group: 'Source Maps', scopes: ['sourcemaps:read', 'sourcemaps:write'] },
  { group: 'Events', scopes: ['event:read'] },
  { group: 'Organization', scopes: ['org:read'] },
  { group: 'Workflows', scopes: ['workflow:read', 'workflow:write', 'workflow:run'] },
] as const

const SCOPE_DESCRIPTIONS: Record<string, string> = {
  'project:read': 'List and view service details.',
  'project:write': 'Create, update, and delete services.',
  'releases:read': 'List releases and view release metadata.',
  'releases:write': 'Create and manage releases (e.g. for version tracking).',
  'sourcemaps:read': 'List and download source map files.',
  'sourcemaps:write': 'Upload source maps and symbol files for symbolication.',
  'event:read': 'Read error and transaction event data.',
  'org:read': 'View organization information.',
  'workflow:read': 'List workflows, runs, audit events, catalog entries, and usage.',
  'workflow:write': 'Create, update, publish, unpublish, and delete workflows.',
  'workflow:run': 'Start and cancel workflow runs.',
}

const EXPIRATION_OPTIONS = [
  { label: 'No expiration', value: 'none' },
  { label: '7 days', value: '7' },
  { label: '30 days', value: '30' },
  { label: '60 days', value: '60' },
  { label: '90 days', value: '90' },
  { label: 'Custom', value: 'custom' },
] as const

const APM_SPAN_DEBUG_LIMIT = 20

function formatDate(iso: string | null | undefined, timezone: string): string {
  if (!iso) return '—'
  try {
    return formatDateUtil(new Date(iso), timezone)
  } catch {
    return iso
  }
}

function isExpired(expiresAt: string | null | undefined): boolean {
  if (!expiresAt) return false
  try {
    return new Date(expiresAt) < new Date()
  } catch {
    return false
  }
}

// Back-compat: legacy tab keys redirect to their redesigned equivalents so old
// deep links keep working after the Settings IA was regrouped by scope.
const TAB_ALIASES: Record<string, string> = {
  'log-indexes': 'api-keys',
  integrations: 'connectors',
  sso: 'auth',
  rbac: 'roles',
  account: 'danger',
}
const VALID_TABS = new Set([
  'general',
  'api-keys',
  'connectors',
  'silence',
  'team',
  'roles',
  'auth',
  'billing',
  'usage',
  'preferences',
  'notifications',
  'danger',
])

export const Route = createFileRoute('/settings')({
  validateSearch: (search: Record<string, unknown>) => {
    const raw = search.tab as string | undefined
    const requestedTab = (raw && TAB_ALIASES[raw]) || raw
    return {
      tab: requestedTab && VALID_TABS.has(requestedTab) ? requestedTab : 'general',
      ...(search.checkout ? { checkout: search.checkout as string } : {}),
    }
  },
  beforeLoad: async ({ location }) => {
    if (!api.isAuthenticated()) {
      throw redirect({ to: '/login', search: { redirect: location.href } })
    }
  },
  component: SettingsPage,
})

function SettingsPage() {
  const search = useSearch({ from: '/settings' })
  const navigate = useNavigate({ from: '/settings' })
  const { user } = useAuth()

  useEffect(() => {
    if (search.checkout === 'success') {
      navigate({ search: (prev) => ({ ...prev, checkout: undefined }), replace: true })
    }
  }, [search.checkout, navigate])
  
  const { data: subscription } = useQuery({
    queryKey: ['subscription'],
    queryFn: () => api.getSubscription(),
    enabled: api.isAuthenticated(),
  })
  
  const tier = subscription?.tier?.tierName || 'FREE'
  const isSelfHosted = useIsSelfHosted()
  
  // SSO: self-hosted can view/configure when entitled; SaaS only Team/Business (not FREE via feature flags)
  const { data: features } = useEnterpriseFeatures()
  const hasSamlModule = hasEnterpriseModule(features, 'saml')
  const hasAdvancedRbacModule = hasEnterpriseModule(features, 'advanced_rbac')
  const canManageTeam = user?.orgRole === 'admin' || user?.orgRole === 'owner'
  const canViewRbacTab = canManageTeam && hasAdvancedRbacModule
  const organizationId = user?.orgId
  const canViewSsoTab =
    organizationId !== undefined &&
    (isSelfHosted || (!isSelfHosted && (tier === 'TEAM' || tier === 'BUSINESS')))
  const canConfigureSso = user?.orgRole === 'owner' && canViewSsoTab
  
  return (
    <div className="mx-auto max-w-[1180px] px-4 py-6 md:px-6">
      <Tabs
        value={search.tab}
        onValueChange={(tab) => navigate({ search: { tab } })}
        orientation="vertical"
        className="flex flex-col gap-8 md:flex-row md:items-start"
      >
        <aside className="w-full flex-shrink-0 md:w-56">
          <TabsList className="flex h-auto w-full flex-col items-stretch gap-0.5 bg-transparent p-0">
            <SettingsNavGroup label="Organization" />
            <SettingsNavItem value="general" icon={Building2}>General</SettingsNavItem>
            <SettingsNavItem value="api-keys" icon={Key}>API keys</SettingsNavItem>
            <SettingsNavItem value="connectors" icon={Plug}>Connectors</SettingsNavItem>
            <SettingsNavItem value="silence" icon={BellOff}>Silence periods</SettingsNavItem>
            {canManageTeam && <SettingsNavItem value="team" icon={Users}>Team</SettingsNavItem>}
            {canViewRbacTab && <SettingsNavItem value="roles" icon={ShieldCheck}>Roles</SettingsNavItem>}
            {canViewSsoTab && <SettingsNavItem value="auth" icon={Lock}>Authentication</SettingsNavItem>}
            {!isSelfHosted && <SettingsNavItem value="billing" icon={CreditCard}>Billing</SettingsNavItem>}
            <SettingsNavItem value="usage" icon={Gauge}>Usage</SettingsNavItem>

            <SettingsNavGroup label="Account" className="mt-4" />
            <SettingsNavItem value="preferences" icon={SlidersHorizontal}>Preferences</SettingsNavItem>
            <SettingsNavItem value="notifications" icon={Bell}>Notifications</SettingsNavItem>
            <SettingsNavItem value="danger" icon={AlertTriangle}>Danger zone</SettingsNavItem>
          </TabsList>
        </aside>

        <div className="w-full min-w-0 flex-1">
          <div className="mx-auto max-w-[940px]">
            <TabsContent value="general" className="mt-0"><GeneralSettings /></TabsContent>
            <TabsContent value="api-keys" className="mt-0"><ApiKeysTab /></TabsContent>
            <TabsContent value="connectors" className="mt-0"><ConnectorsSettings /></TabsContent>
            <TabsContent value="silence" className="mt-0"><SilencePeriodsTab /></TabsContent>
            {canManageTeam && (
              <TabsContent value="team" className="mt-0"><TeamSettings /></TabsContent>
            )}
            {canViewRbacTab && (
              <TabsContent value="roles" className="mt-0"><RbacSettings /></TabsContent>
            )}
            {!isSelfHosted && (
              <TabsContent value="billing" className="mt-0"><BillingTab /></TabsContent>
            )}
            <TabsContent value="usage" className="mt-0"><UsageTab /></TabsContent>
            {canViewSsoTab && (
              <TabsContent value="auth" className="mt-0">
                <SsoTab
                  organizationId={organizationId}
                  hasSamlModule={hasSamlModule}
                  canConfigure={canConfigureSso}
                />
              </TabsContent>
            )}
            <TabsContent value="preferences" className="mt-0"><PreferencesSettings /></TabsContent>
            <TabsContent value="notifications" className="mt-0"><NotificationsTab /></TabsContent>
            <TabsContent value="danger" className="mt-0"><AccountTab /></TabsContent>
          </div>
        </div>
      </Tabs>
    </div>
  )
}

function SettingsNavGroup({label, className}: {label: string; className?: string}) {
  return (
    <div
      className={cn(
        'px-2 pb-1.5 pt-1 text-[11px] font-bold uppercase tracking-[0.06em] text-muted-foreground/70',
        className
      )}
    >
      {label}
    </div>
  )
}

function SettingsNavItem({
  value,
  icon: Icon,
  children,
}: {
  value: string
  icon: React.ComponentType<{className?: string}>
  children: React.ReactNode
}) {
  return (
    <TabsTrigger
      value={value}
      className="h-[30px] w-full justify-start gap-2.5 rounded-md px-2.5 text-sm font-medium text-muted-foreground hover:bg-muted/60 hover:text-foreground data-[state=active]:bg-primary/10 data-[state=active]:font-medium data-[state=active]:text-primary data-[state=active]:shadow-[inset_2px_0_0_hsl(var(--primary))]"
    >
      <Icon className="h-4 w-4" />
      {children}
    </TabsTrigger>
  )
}

function ApiKeysTab() {
  const hasDatadog = true // Datadog module is always available (OSS)
  return (
    <section>
      <SettingsSection
        title="API keys"
        description="Credentials for sending telemetry into Moneat. Keys are organization-scoped; routing maps incoming services to Moneat services."
        actions={
          <Button variant="outline" asChild>
            <a href="/docs/api-tokens" target="_blank" rel="noopener noreferrer">
              <BookOpen data-icon="inline-start" />
              Docs
            </a>
          </Button>
        }
      />
      <Tabs defaultValue="opentelemetry">
      <TabsList className="mb-4">
        <TabsTrigger value="opentelemetry">OpenTelemetry</TabsTrigger>
        <TabsTrigger value="datadog" disabled={!hasDatadog}>Datadog</TabsTrigger>
        <TabsTrigger value="sentry">Sentry</TabsTrigger>
        <TabsTrigger value="mcp">MCP</TabsTrigger>
      </TabsList>
      <TabsContent value="opentelemetry" className="space-y-8 mt-0">
        <OtlpApiKeysTab />
      </TabsContent>
      <TabsContent value="datadog" className="space-y-8 mt-0">
        {hasDatadog && <AgentApiKeysTab />}
      </TabsContent>
      <TabsContent value="sentry" className="space-y-8 mt-0">
        <AuthTokensSection />
      </TabsContent>
      <TabsContent value="mcp" className="space-y-8 mt-0">
        <McpApiKeysTab />
      </TabsContent>
    </Tabs>
    </section>
  )
}

function AuthTokensSection() {
  const queryClient = useQueryClient()
  const { toast } = useToast()
  const { timezone } = useTimezone()
  const [createOpen, setCreateOpen] = useState(false)
  const [revokeToken, setRevokeToken] = useState<AuthToken | null>(null)
  const [createdTokenValue, setCreatedTokenValue] = useState<string | null>(null)

  const { data: tokens = [], isLoading } = useQuery({
    queryKey: ['authTokens'],
    queryFn: () => api.getAuthTokens(),
    enabled: api.isAuthenticated(),
  })

  const createMutation = useMutation({
    mutationFn: ({
      name,
      scopes,
      expiresInDays,
    }: {
      name: string
      scopes: string[]
      expiresInDays?: number
    }) => api.createAuthToken(name, scopes, expiresInDays),
    onSuccess: (data) => {
      queryClient.invalidateQueries({ queryKey: ['authTokens'] })
      if (data.token) {
        setCreatedTokenValue(data.token)
      } else {
        setCreateOpen(false)
        toast({ title: 'Token created', description: 'Your new token is ready to use.' })
      }
    },
    onError: (err: Error) => {
      toast({
        title: 'Failed to create token',
        description: err.message,
        variant: 'destructive',
      })
    },
  })

  const deleteMutation = useMutation({
    mutationFn: (tokenId: string) => api.deleteAuthToken(tokenId),
    onSuccess: () => {
      queryClient.invalidateQueries({ queryKey: ['authTokens'] })
      setRevokeToken(null)
      toast({ title: 'Token revoked', description: 'The token has been revoked.' })
    },
    onError: (err: Error) => {
      toast({
        title: 'Failed to revoke token',
        description: err.message,
        variant: 'destructive',
      })
    },
  })

  const handleCopyToken = (value: string) => {
    navigator.clipboard.writeText(value)
    toast({ title: 'Copied', description: 'Token copied to clipboard.' })
  }

  const handleCloseCreateDialog = () => {
    setCreateOpen(false)
    setCreatedTokenValue(null)
    createMutation.reset()
  }

  return (
    <>
      <Card>
        <CardHeader className="flex flex-col gap-4 space-y-0 sm:flex-row sm:items-start sm:justify-between">
          <div>
            <CardTitle className="flex items-center gap-2">
              <Key className="h-5 w-5" />
              API Tokens
            </CardTitle>
            <CardDescription>
              Create tokens for release automation, source maps, and Sentry-compatible API access.
            </CardDescription>
          </div>
          <TooltipProvider>
            <div className="flex shrink-0 items-center gap-2">
              <Tooltip>
                <TooltipTrigger asChild>
                  <Button variant="outline" size="icon" asChild>
                    <a
                      href="/docs/api-tokens"
                      target="_blank"
                      rel="noopener noreferrer"
                      aria-label="API token docs"
                      title="Docs"
                    >
                      <BookOpen className="h-4 w-4" />
                      <span className="sr-only">Docs</span>
                    </a>
                  </Button>
                </TooltipTrigger>
                <TooltipContent>Docs</TooltipContent>
              </Tooltip>
              <Tooltip>
                <TooltipTrigger asChild>
                  <Button
                    size="icon"
                    onClick={() => setCreateOpen(true)}
                    disabled={!!createdTokenValue}
                    aria-label="New token"
                    title="New token"
                  >
                    <Plus className="h-4 w-4" />
                    <span className="sr-only">New Token</span>
                  </Button>
                </TooltipTrigger>
                <TooltipContent>New token</TooltipContent>
              </Tooltip>
            </div>
          </TooltipProvider>
        </CardHeader>
        <CardContent>
          {isLoading ? (
            <p className="text-muted-foreground text-sm py-8">Loading tokens...</p>
          ) : tokens.length === 0 ? (
            <div className="border rounded-lg p-8 text-center text-muted-foreground">
              <Key className="h-10 w-10 mx-auto mb-2 opacity-50" />
              <p className="font-medium">No tokens yet</p>
              <p className="text-sm mt-1">Create a Sentry API token for CLI and upload tools.</p>
              <Button variant="outline" className="mt-4" onClick={() => setCreateOpen(true)}>
                <Plus className="h-4 w-4 mr-2" />
                Create token
              </Button>
            </div>
          ) : (
            <Table>
              <TableHeader>
                <TableRow>
                  <TableHead>Name</TableHead>
                  <TableHead>Scopes</TableHead>
                  <TableHead>Last Used</TableHead>
                  <TableHead>Expires</TableHead>
                  <TableHead>Created</TableHead>
                  <TableHead className="w-[80px]"></TableHead>
                </TableRow>
              </TableHeader>
              <TableBody>
                {tokens.map((token) => {
                  const expired = isExpired(token.expiresAt)
                  return (
                    <TableRow
                      key={token.id}
                      className={expired ? 'opacity-60 bg-muted/30' : undefined}
                    >
                      <TableCell className="font-medium">
                        <div className="flex items-center gap-2">
                          {token.name}
                          {expired && (
                            <Badge variant="secondary" className="text-xs">
                              Expired
                            </Badge>
                          )}
                        </div>
                      </TableCell>
                      <TableCell>
                        <div className="flex flex-wrap gap-1">
                          {token.scopes.slice(0, 3).map((s) => (
                            <Badge key={s} variant="secondary" className="text-xs font-normal">
                              {s}
                            </Badge>
                          ))}
                          {token.scopes.length > 3 && (
                            <Badge variant="outline" className="text-xs font-normal">
                              +{token.scopes.length - 3}
                            </Badge>
                          )}
                        </div>
                      </TableCell>
                      <TableCell className="text-muted-foreground text-sm">
                        {formatDate(token.lastUsedAt, timezone)}
                      </TableCell>
                      <TableCell className="text-muted-foreground text-sm">
                        {token.expiresAt ? formatDate(token.expiresAt, timezone) : 'Never'}
                      </TableCell>
                      <TableCell className="text-muted-foreground text-sm">
                        {formatDate(token.createdAt, timezone)}
                      </TableCell>
                      <TableCell>
                        <Button
                          variant="ghost"
                          size="icon"
                          className="text-destructive hover:text-destructive hover:bg-destructive/10"
                          onClick={() => setRevokeToken(token)}
                          aria-label={`Revoke ${token.name}`}
                        >
                          <Trash2 className="h-4 w-4" />
                        </Button>
                      </TableCell>
                    </TableRow>
                  )
                })}
              </TableBody>
            </Table>
          )}
        </CardContent>
      </Card>

      <CreateTokenDialog
        open={createOpen}
        onOpenChange={(open) => !open && handleCloseCreateDialog()}
        createdTokenValue={createdTokenValue}
        onCopy={handleCopyToken}
        onClose={handleCloseCreateDialog}
        createMutation={createMutation}
      />

      <RevokeTokenDialog
        token={revokeToken}
        onClose={() => setRevokeToken(null)}
        onConfirm={() => revokeToken && deleteMutation.mutate(revokeToken.id)}
        isRevoking={deleteMutation.isPending}
      />
    </>
  )
}

interface CreateTokenDialogProps {
  open: boolean
  onOpenChange: (open: boolean) => void
  createdTokenValue: string | null
  onCopy: (value: string) => void
  onClose: () => void
  createMutation: {
    mutate: (vars: { name: string; scopes: string[]; expiresInDays?: number }) => void
    isPending: boolean
  }
}

function CreateTokenDialog({
  open,
  onOpenChange,
  createdTokenValue,
  onCopy,
  onClose,
  createMutation,
}: CreateTokenDialogProps) {
  const [name, setName] = useState('')
  const [selectedScopes, setSelectedScopes] = useState<Set<string>>(new Set())
  const [expiration, setExpiration] = useState<string>('none')
  const [customDays, setCustomDays] = useState<string>('30')

  const toggleScope = (scope: string) => {
    setSelectedScopes((prev) => {
      const next = new Set(prev)
      if (next.has(scope)) next.delete(scope)
      else next.add(scope)
      return next
    })
  }

  const handleSubmit = (e: React.FormEvent) => {
    e.preventDefault()
    const scopes = Array.from(selectedScopes)
    if (!name.trim()) return
    if (scopes.length === 0) return
    let expiresInDays: number | undefined
    if (expiration === 'none') expiresInDays = undefined
    else if (expiration === 'custom') {
      const n = parseInt(customDays, 10)
      if (!Number.isFinite(n) || n < 1) return
      expiresInDays = n
    } else expiresInDays = parseInt(expiration, 10)
    createMutation.mutate({ name: name.trim(), scopes, expiresInDays })
  }

  const showForm = !createdTokenValue

  return (
    <Dialog open={open} onOpenChange={onOpenChange}>
      <DialogContent className="sm:max-w-md">
        {showForm ? (
          <>
            <DialogHeader>
              <DialogTitle>Create token</DialogTitle>
              <DialogDescription>
                Give the token a name and choose which permissions it should have.
              </DialogDescription>
            </DialogHeader>
            <form onSubmit={handleSubmit} className="space-y-4">
              <div className="space-y-2">
                <Label htmlFor="token-name">Name</Label>
                <Input
                  id="token-name"
                  placeholder="e.g. CI pipeline"
                  value={name}
                  onChange={(e) => setName(e.target.value)}
                  required
                />
              </div>
              <div className="space-y-2">
                <Label>Scopes</Label>
                <p className="text-xs text-muted-foreground">
                  Choose which permissions this token should have. Descriptions are below.
                </p>
                <div className="border rounded-md overflow-auto max-h-56">
                  <Table>
                    <TableHeader>
                      <TableRow>
                        <TableHead className="w-10">Select</TableHead>
                        <TableHead className="text-xs">Permission</TableHead>
                        <TableHead className="text-xs">Description</TableHead>
                      </TableRow>
                    </TableHeader>
                    <TableBody>
                      {AUTH_TOKEN_SCOPES.map(({ group, scopes }) => (
                        <Fragment key={group}>
                          <TableRow className="bg-muted/40">
                            <TableCell colSpan={3} className="text-xs font-medium text-muted-foreground py-1.5">
                              {group}
                            </TableCell>
                          </TableRow>
                          {scopes.map((scope) => (
                            <TableRow
                              key={scope}
                              className="cursor-pointer hover:bg-muted/30"
                              onClick={() => toggleScope(scope)}
                            >
                              <TableCell className="w-10" onClick={(e) => e.stopPropagation()}>
                                <Checkbox
                                  checked={selectedScopes.has(scope)}
                                  onCheckedChange={() => toggleScope(scope)}
                                />
                              </TableCell>
                              <TableCell className="font-mono text-xs">
                                {scope}
                              </TableCell>
                              <TableCell className="text-xs text-muted-foreground">
                                {SCOPE_DESCRIPTIONS[scope] ?? '—'}
                              </TableCell>
                            </TableRow>
                          ))}
                        </Fragment>
                      ))}
                    </TableBody>
                  </Table>
                </div>
              </div>
              <div className="space-y-2">
                <Label>Expiration</Label>
                <Select value={expiration} onValueChange={(v) => setExpiration(v)}>
                  <SelectTrigger>
                    <SelectValue />
                  </SelectTrigger>
                  <SelectContent>
                    {EXPIRATION_OPTIONS.map((opt) => (
                      <SelectItem key={opt.value} value={opt.value}>
                        {opt.label}
                      </SelectItem>
                    ))}
                  </SelectContent>
                </Select>
                {expiration === 'custom' && (
                  <div className="pt-2">
                    <Input
                      type="number"
                      min={1}
                      placeholder="Days"
                      value={customDays}
                      onChange={(e) => setCustomDays(e.target.value)}
                    />
                  </div>
                )}
              </div>
              <DialogFooter>
                <Button type="button" variant="outline" onClick={onClose}>
                  Cancel
                </Button>
                <Button
                  type="submit"
                  disabled={
                    createMutation.isPending ||
                    !name.trim() ||
                    selectedScopes.size === 0 ||
                    (expiration === 'custom' && (!customDays || parseInt(customDays, 10) < 1))
                  }
                >
                  {createMutation.isPending ? 'Creating…' : 'Create token'}
                </Button>
              </DialogFooter>
            </form>
          </>
        ) : (
          <>
            <DialogHeader>
              <DialogTitle>Token created</DialogTitle>
              <DialogDescription>
                Copy the token now. You won’t be able to see it again.
              </DialogDescription>
            </DialogHeader>
            <div className="space-y-4">
              <div
                className="flex items-center gap-2 rounded-md border bg-muted/50 p-3 font-mono text-sm"
                role="alert"
              >
                <AlertTriangle className="h-5 w-5 shrink-0 text-warning-fg" />
                <span className="text-muted-foreground">
                  This is the only time the token will be shown. Store it securely.
                </span>
              </div>
              <div className="flex gap-2">
                <Input
                  readOnly
                  value={createdTokenValue}
                  className="font-mono text-sm"
                />
                <Button
                  type="button"
                  variant="secondary"
                  size="icon"
                  onClick={() => createdTokenValue && onCopy(createdTokenValue)}
                  aria-label="Copy token"
                >
                  <Copy className="h-4 w-4" />
                </Button>
              </div>
              <DialogFooter>
                <Button onClick={onClose}>Done</Button>
              </DialogFooter>
            </div>
          </>
        )}
      </DialogContent>
    </Dialog>
  )
}

interface RevokeTokenDialogProps {
  token: AuthToken | null
  onClose: () => void
  onConfirm: () => void
  isRevoking: boolean
}

function RevokeTokenDialog({ token, onClose, onConfirm, isRevoking }: RevokeTokenDialogProps) {
  if (!token) return null
  return (
    <Dialog open={!!token} onOpenChange={(open) => !open && onClose()}>
      <DialogContent className="sm:max-w-md">
        <DialogHeader>
          <DialogTitle>Revoke token</DialogTitle>
          <DialogDescription>
            Are you sure you want to revoke &quot;{token.name}&quot;? Any applications using this
            token will stop working immediately.
          </DialogDescription>
        </DialogHeader>
        <DialogFooter>
          <Button type="button" variant="outline" onClick={onClose} disabled={isRevoking}>
            Cancel
          </Button>
          <Button
            type="button"
            variant="destructive"
            onClick={onConfirm}
            disabled={isRevoking}
          >
            {isRevoking ? 'Revoking…' : 'Revoke token'}
          </Button>
        </DialogFooter>
      </DialogContent>
    </Dialog>
  )
}

function UsageTab() {
  const { timezone } = useTimezone()
  const isSelfHosted = useIsSelfHosted()
  const [isApmSpanSourceExpanded, setIsApmSpanSourceExpanded] = useState(false)
  const { data: usage, isLoading } = useQuery({
    queryKey: ['billingUsage'],
    queryFn: () => api.getBillingUsage(),
    enabled: api.isAuthenticated(),
  })
  const {
    data: apmSpanDebug,
    error: apmSpanDebugError,
    isLoading: isApmSpanDebugLoading,
  } = useQuery({
    queryKey: ['billingApmSpanUsageDebug', usage?.periodStart, usage?.periodEnd],
    queryFn: () => api.getBillingApmSpanUsageDebug(APM_SPAN_DEBUG_LIMIT),
    enabled: api.isAuthenticated() && usage !== undefined && isApmSpanSourceExpanded,
  })

  if (isLoading || !usage) {
    return <p className="text-sm text-muted-foreground">Loading usage data...</p>
  }

  const periodLabel = `${formatDate(usage.periodStart, timezone)} – ${formatDate(usage.periodEnd, timezone)}`
  const usedApmSpanBytes = usage.usedApmSpanBytes ?? 0
  const usedInfraMetricBytes = usage.usedInfraMetricBytes ?? 0
  const gbBilledIngestionBytes = Math.max(0, (usage.usedBytes ?? 0) - usedApmSpanBytes - usedInfraMetricBytes)

  const usageRows = [
    {
      key: 'ingestion',
      label: 'GB-Billed Ingestion',
      used: gbBilledIngestionBytes,
      limit: usage.bytesLimit,
      icon: AlertCircle,
      color: 'text-chart-1',
      bgColor: 'bg-chart-1',
      retentionDays: usage.retentionDays,
      overageCents: usage.ingestionOverageCentsEstimate ?? 0,
      overageRate: usage.ingestionOverageRateCentsPerGb
        ? `$${(usage.ingestionOverageRateCentsPerGb / 100).toFixed(2)}/GB`
        : null,
      unit: 'bytes',
    },
    {
      key: 'custom_metric',
      label: 'Custom Metrics',
      used: usage.usedCustomMetrics ?? 0,
      limit: usage.customMetricLimit ?? 0,
      icon: Server,
      color: 'text-chart-2',
      bgColor: 'bg-chart-2',
      retentionDays: usage.retentionDays,
      overageCents: usage.customMetricOverageCentsEstimate ?? 0,
      overageRate: usage.customMetricOverageRateCentsPer100k
        ? `$${(usage.customMetricOverageRateCentsPer100k / 100).toFixed(2)}/100K`
        : null,
      unit: 'events',
    },
    {
      key: 'infra_metric',
      label: 'Infrastructure Metrics',
      used: usage.usedInfraMetricSeriesHours ?? 0,
      limit: usage.infraMetricSeriesHourLimit ?? 0,
      icon: Database,
      color: 'text-chart-3',
      bgColor: 'bg-chart-3',
      retentionDays: usage.retentionDays,
      overageCents: usage.infraMetricOverageCentsEstimate ?? 0,
      overageRate: usage.infraMetricOverageRateCentsPer100kSeriesHours
        ? `$${(usage.infraMetricOverageRateCentsPer100kSeriesHours / 100).toFixed(2)}/100K series-hours`
        : null,
      unit: 'series-hours',
    },
    {
      key: 'apm_span',
      label: 'APM Spans',
      used: usage.usedApmSpans ?? 0,
      limit: usage.apmSpanLimit ?? 0,
      icon: Activity,
      color: 'text-chart-4',
      bgColor: 'bg-chart-4',
      retentionDays: usage.apmTraceRetentionDays ?? usage.retentionDays,
      overageCents: usage.apmSpanOverageCentsEstimate ?? 0,
      overageRate: usage.apmSpanOverageRateCentsPer1m
        ? `$${(usage.apmSpanOverageRateCentsPer1m / 100).toFixed(2)}/1M`
        : null,
      unit: 'events',
    },
    {
      key: 'analytics',
      label: 'Analytics Pageviews',
      used: usage.usedAnalyticsPageviews ?? 0,
      limit: usage.analyticsPageviewLimit ?? 0,
      icon: LayoutDashboard,
      color: 'text-chart-5',
      bgColor: 'bg-chart-5',
      retentionDays: usage.retentionDays,
      overageCents: usage.analyticsPageviewOverageCentsEstimate ?? 0,
      overageRate: usage.analyticsPageviewOverageRateCentsPer100k
        ? `$${(usage.analyticsPageviewOverageRateCentsPer100k / 100).toFixed(2)}/100K`
        : null,
      unit: 'events',
    },
  ] as const

  // Categorical ingestion segments from the shared chart palette (literal classes
  // so Tailwind emits them); these encode data type, not status.
  const ingestionBreakdown = [
    { label: 'Errors', bytes: usage.usedErrorBytes ?? 0, color: 'bg-chart-6' },
    { label: 'Replays', bytes: usage.usedReplayBytes ?? 0, color: 'bg-chart-7' },
    { label: 'Logs', bytes: usage.usedLogBytes ?? 0, color: 'bg-chart-8' },
    { label: 'LLM', bytes: usage.usedLlmBytes ?? 0, color: 'bg-chart-9' },
    { label: 'Profiling', bytes: usage.usedProfilerBytes ?? 0, color: 'bg-chart-10' },
  ]
  const ingestionKnownBytes = ingestionBreakdown.reduce((sum, s) => sum + s.bytes, 0)
  const ingestionOtherBytes = Math.max(0, gbBilledIngestionBytes - ingestionKnownBytes)
  const ingestionSegments = [
    ...ingestionBreakdown,
    { label: 'Other', bytes: ingestionOtherBytes, color: 'bg-muted-foreground/50' },
  ].filter((s) => s.bytes > 0)
  const nonGbBilledBreakdown = [
    { label: 'APM Spans', bytes: usedApmSpanBytes, color: 'bg-chart-4' },
    { label: 'Infra Metrics', bytes: usedInfraMetricBytes, color: 'bg-chart-3' },
  ].filter((s) => s.bytes > 0)

  const UNLIMITED_SENTINEL = 9_007_199_254_740_000

  const isUnlimitedValue = (value: number) => value >= UNLIMITED_SENTINEL || value < 0

  const formatLimit = (value: number, unit: string) => {
    if (value <= 0 || isUnlimitedValue(value)) return 'Unlimited'
    if (unit === 'bytes') return `${formatGB(value)} GB`
    if (value >= 1_000_000) return `${(value / 1_000_000).toFixed(1)}M`
    if (value >= 1_000) return `${(value / 1_000).toFixed(0)}K`
    return value.toLocaleString()
  }

  const formatUsed = (value: number, unit: string) => {
    if (unit === 'bytes') return `${formatGB(value)} GB`
    return value.toLocaleString()
  }

  const getPercent = (used: number, limit: number) => {
    if (limit <= 0 || isUnlimitedValue(limit)) return used > 0 ? 5 : 0
    return Math.min(100, (used / limit) * 100)
  }

  const getBarClass = (percent: number) =>
    percent >= 100 ? 'bg-danger-solid' : percent >= 80 ? 'bg-warning-solid' : 'bg-success-solid'

  const totalOverageCents = usage.totalOverageCentsEstimate ?? 0

  const formatCurrency = (cents: number) => `$${(cents / 100).toFixed(2)}`
  const BYTES_PER_GB = 1024 * 1024 * 1024

  // GB conversion: 1 GB = 1,073,741,824 bytes (binary)
  const formatGB = (bytes: number) => (bytes / BYTES_PER_GB).toFixed(2)
  const usedGB = formatGB(gbBilledIngestionBytes)
  const limitGB = usage.bytesLimit > 0 ? formatGB(usage.bytesLimit) : null
  const isUnlimitedGB = !usage.bytesLimit || usage.bytesLimit <= 0
  const overageGB = usage.bytesLimit > 0 && gbBilledIngestionBytes > usage.bytesLimit

  return (
    <div className="space-y-6">
      <Card>
        <CardHeader>
          <div className="flex items-center justify-between">
            <div className="flex items-center gap-2">
              <div className="p-2 bg-[hsl(var(--primary)/0.12)] rounded-full">
                <Layers className="h-5 w-5 text-primary" />
              </div>
              <div>
                <CardTitle>Usage & Limits</CardTitle>
                <CardDescription>
                  Per-type usage for this billing period ({periodLabel}).
                </CardDescription>
              </div>
            </div>
            <div className="flex flex-wrap items-center justify-end gap-2">
              {!isSelfHosted && (
                <Button asChild variant="outline" size="sm">
                  <Link to="/usage-insights">
                    <TrendingUp data-icon="inline-start" />
                    Usage Insights
                  </Link>
                </Button>
              )}
              {totalOverageCents > 0 && (
                <div
                  className="flex items-center gap-2 rounded-lg border border-warning-border bg-warning-bg px-3 py-2"
                >
                  <TrendingUp className="h-4 w-4 text-warning-fg" />
                  <div className="text-right">
                    <p className="text-xs text-muted-foreground">Est. overage</p>
                    <p className="text-sm font-bold text-warning-fg">{formatCurrency(totalOverageCents)}</p>
                  </div>
                </div>
              )}
            </div>
          </div>
        </CardHeader>
        <CardContent className="space-y-2">
          {usageRows.map((row) => {
            const Icon = row.icon
            const percent = getPercent(row.used, row.limit)
            const barClass = getBarClass(percent)
            const isOver = row.limit > 0 && !isUnlimitedValue(row.limit) && row.used > row.limit
            const isUnlimited = row.limit <= 0 || isUnlimitedValue(row.limit)

            return (
              <div key={row.key} className="rounded-lg border p-3 space-y-2">
                <div className="flex items-center justify-between">
                  <div className="flex items-center gap-3">
                    <div className={`p-1.5 rounded-md ${row.bgColor}/10`}>
                      <Icon className={`h-4 w-4 ${row.color}`} />
                    </div>
                    <div className="flex items-baseline gap-2">
                      <span className="text-sm font-medium">{row.label}</span>
                      <span className="text-xs text-muted-foreground">{row.retentionDays}d retention</span>
                    </div>
                  </div>
                  <div className="text-right">
                    <span className="text-sm font-semibold">{formatUsed(row.used, row.unit)}</span>
                    <span className="text-xs text-muted-foreground"> / {formatLimit(row.limit, row.unit)}</span>
                  </div>
                </div>

                {row.key === 'ingestion' ? (
                  <div className="space-y-1.5">
                    <div className="h-1.5 w-full rounded-full bg-secondary overflow-hidden flex">
                      {ingestionSegments.length > 0 ? ingestionSegments.map((seg) => {
                        const w = isUnlimited
                          ? row.used > 0 ? (seg.bytes / row.used) * 5 : 0
                          : row.limit > 0 ? Math.min(100, (seg.bytes / row.limit) * 100) : 0
                        return (
                          <div key={seg.label} className={`h-full ${seg.color} transition-all`} style={{ width: `${w}%` }} />
                        )
                      }) : (
                        <div className={`h-full rounded-full transition-all ${barClass}`} style={{ width: `${Math.max(isUnlimited && row.used > 0 ? 5 : 0, percent)}%` }} />
                      )}
                    </div>
                    <div className="flex flex-wrap gap-x-3 gap-y-0.5">
                      {ingestionBreakdown.map((seg) => (
                        <div key={seg.label} className="flex items-center gap-1">
                          <div className={`h-1.5 w-1.5 rounded-full ${seg.color}`} />
                          <span className="text-xs text-muted-foreground">{seg.label} · {formatGB(seg.bytes)} GB</span>
                        </div>
                      ))}
                      {ingestionOtherBytes > 0 && (
                        <div className="flex items-center gap-1">
                          <div className="h-1.5 w-1.5 rounded-full bg-muted-foreground/50" />
                          <span className="text-xs text-muted-foreground">Other · {formatGB(ingestionOtherBytes)} GB</span>
                        </div>
                      )}
                      {nonGbBilledBreakdown.map((seg) => (
                        <div key={seg.label} className="flex items-center gap-1">
                          <div className={`h-1.5 w-1.5 rounded-full ${seg.color}`} />
                          <span className="text-xs text-muted-foreground">
                            {seg.label} · {formatGB(seg.bytes)} GB tracked
                          </span>
                        </div>
                      ))}
                    </div>
                  </div>
                ) : (
                  <div className="h-1.5 w-full rounded-full bg-secondary overflow-hidden">
                    <div className={`h-full rounded-full transition-all ${barClass}`} style={{ width: `${Math.max(isUnlimited && row.used > 0 ? 5 : 0, percent)}%` }} />
                  </div>
                )}

                {(isOver || row.overageCents > 0) && (
                  <div className="flex items-center justify-between text-xs">
                    {isOver && (
                      <div className="flex items-center gap-1 text-warning-fg bg-warning-bg px-2 py-0.5 rounded">
                        <AlertTriangle className="h-3 w-3" />
                        <span>Over limit</span>
                      </div>
                    )}
                    {row.overageCents > 0 && (
                      <div className="flex items-center gap-2 ml-auto text-muted-foreground">
                        <span>Overage: <span className="font-medium text-foreground">{formatCurrency(row.overageCents)}</span></span>
                        {row.overageRate && <span className="text-muted-foreground/60">({row.overageRate})</span>}
                      </div>
                    )}
                  </div>
                )}

                {row.key === 'apm_span' && (
                  <ApmSpanUsageBreakdown
                    debug={apmSpanDebug}
                    error={apmSpanDebugError}
                    expanded={isApmSpanSourceExpanded}
                    isLoading={isApmSpanDebugLoading}
                    onExpandedChange={setIsApmSpanSourceExpanded}
                    retentionDays={row.retentionDays}
                    timezone={timezone}
                  />
                )}
              </div>
            )
          })}

          {/* GB-billed data volume */}
          <div className="rounded-lg border border-dashed p-4">
            <div className="flex items-center justify-between">
              <div className="flex items-center gap-2">
                <Database className="h-4 w-4 text-muted-foreground" />
                <span className="text-sm font-medium text-muted-foreground">GB-billed ingestion</span>
              </div>
              <span className="text-sm font-semibold">
                {usedGB} GB
                {limitGB && !isUnlimitedGB && <span className="text-xs text-muted-foreground font-normal"> / {limitGB} GB</span>}
              </span>
            </div>
            {overageGB && (
              <div className="mt-2 flex items-center gap-1.5 text-xs text-warning-fg">
                <AlertTriangle className="h-3 w-3" />
                <span>GB-billed ingestion exceeds the base GB limit.</span>
              </div>
            )}
          </div>
        </CardContent>
      </Card>

    </div>
  )
}

function BillingTab() {
  const queryClient = useQueryClient()
  const { toast } = useToast()
  const { timezone } = useTimezone()
  const [showPaymentForm, setShowPaymentForm] = useState(false)
  const [setupClientSecret, setSetupClientSecret] = useState<string | null>(null)
  const [showCancelDialog, setShowCancelDialog] = useState(false)
  const [billingInterval, setBillingInterval] = useState<'monthly' | 'yearly'>('monthly')
  const [pendingOnCallSeats, setPendingOnCallSeats] = useState<number | null>(null)

  const { data: usage, isLoading } = useQuery({
    queryKey: ['billingUsage'],
    queryFn: () => api.getBillingUsage(),
    enabled: api.isAuthenticated(),
  })

  const { data: plansData } = useQuery({
    queryKey: ['billingPlans'],
    queryFn: () => api.getBillingPlans(),
    enabled: api.isAuthenticated(),
  })

  const { data: invoices = [], isLoading: invoicesLoading } = useQuery({
    queryKey: ['billingInvoices'],
    queryFn: () => api.getBillingInvoices(),
    enabled: api.isAuthenticated() && plansData?.stripeEnabled === true,
  })

  const { data: paymentMethod } = useQuery({
    queryKey: ['billingPaymentMethod'],
    queryFn: () => api.getBillingPaymentMethod(),
    enabled: api.isAuthenticated() && plansData?.stripeEnabled === true,
  })

  const publishableKey = plansData?.publishableKey
  const stripePromise = useMemo(() => {
    if (!publishableKey) return null
    return loadStripe(publishableKey)
  }, [publishableKey])

  const updateOnCallSeatsMutation = useMutation({
    mutationFn: (seats: number) => api.updateOnCallSeats(seats),
    onSuccess: (data) => {
      queryClient.invalidateQueries({ queryKey: ['billingUsage'] })
      queryClient.invalidateQueries({ queryKey: ['billingInvoices'] })
      toast({
        title: 'On-call seats updated',
        description: data.proratedAmountCents
           ? `Seats updated. Prorated charge: $${(data.proratedAmountCents / 100).toFixed(2)}`
           : 'Seats updated successfully.',
      })
    },
    onError: (err: Error) => {
      toast({
        title: 'Failed to update seats',
        description: err.message,
        variant: 'destructive',
      })
    },
  })

  const checkoutMutation = useMutation({
    mutationFn: ({ tierName, interval }: { tierName: string; interval: 'monthly' | 'yearly' }) =>
      api.createBillingCheckoutSession({
        tierName,
        billingInterval: interval,
        successUrl: `${window.location.origin}/settings?checkout=success&tab=billing`,
        cancelUrl: `${window.location.origin}/settings?tab=billing`,
      }),
    onSuccess: (session) => {
      trackEvent('Subscription Upgrade')
      if (session.url) {
        window.location.href = session.url
      }
    },
    onError: (err: Error) => {
      toast({
        title: 'Unable to start checkout',
        description: err.message,
        variant: 'destructive',
      })
    },
  })

  const setupIntentMutation = useMutation({
    mutationFn: () => api.createBillingSetupIntent(),
    onSuccess: (response) => {
      setSetupClientSecret(response.clientSecret)
      setShowPaymentForm(true)
    },
    onError: (err: Error) => {
      toast({
        title: 'Unable to start payment method update',
        description: err.message,
        variant: 'destructive',
      })
    },
  })

  const cancelSubscriptionMutation = useMutation({
    mutationFn: () => api.cancelBillingSubscription(),
    onSuccess: () => {
      trackEvent('Subscription Cancel')
      setShowCancelDialog(false)
      queryClient.invalidateQueries({ queryKey: ['billingUsage'] })
      queryClient.invalidateQueries({ queryKey: ['billingInvoices'] })
      toast({
        title: 'Subscription cancellation scheduled',
        description: 'Your subscription is set to end at the close of the current billing period.',
      })
    },
    onError: (err: Error) => {
      toast({
        title: 'Unable to cancel subscription',
        description: err.message,
        variant: 'destructive',
      })
    },
  })

  if (isLoading || !usage) {
    return <p className="text-sm text-muted-foreground">Loading billing details...</p>
  }

  const isPaidTier = usage.plan !== 'free'
  const currentPlan = plansData?.plans?.find((p) => p.tier.tierName.toLowerCase() === usage.plan.toLowerCase())
  const billablePlans = plansData?.plans?.filter((p) => p.tier.tierName !== 'FREE') ?? []
  const periodLabel = `${formatDate(usage.periodStart, timezone)} – ${formatDate(usage.periodEnd, timezone)}`

  const onPaymentMethodUpdated = () => {
    setShowPaymentForm(false)
    setSetupClientSecret(null)
    queryClient.invalidateQueries({ queryKey: ['billingPaymentMethod'] })
    queryClient.invalidateQueries({ queryKey: ['billingInvoices'] })
  }

  const formatCurrency = (cents: number) => `$${(cents / 100).toFixed(2)}`
  const statusBadgeVariant = usage.status === 'active' || usage.status === 'trialing' ? 'default' : 'secondary'
  const effectiveOnCallSeats = pendingOnCallSeats ?? usage.oncallSeats ?? 0
  const hasPendingOnCallChange =
    pendingOnCallSeats !== null &&
    usage.oncallSeats !== undefined &&
    pendingOnCallSeats !== usage.oncallSeats

  const calculateProration = (seatDiff: number) => {
    if (!usage || !usage.oncallPerUserMonthlyCents) return 0
    const now = new Date().getTime()
    const start = new Date(usage.periodStart).getTime()
    const end = new Date(usage.periodEnd).getTime()
    const totalDuration = end - start
    const remainingDuration = Math.max(0, end - now)
    const ratio = remainingDuration / totalDuration
    return Math.round(seatDiff * usage.oncallPerUserMonthlyCents * ratio)
  }

  const handleUpdateOnCallSeats = () => {
    if (pendingOnCallSeats !== null) {
      updateOnCallSeatsMutation.mutate(pendingOnCallSeats)
    }
  }

  return (
    <div className="space-y-6">
      <Card className="border-primary/20 bg-gradient-to-br from-primary/5 via-background to-background overflow-hidden relative">
        <div className="absolute top-0 right-0 p-4 opacity-10">
          <CreditCard className="w-32 h-32" />
        </div>
        <CardHeader>
          <div className="flex items-center gap-2">
            <div className="p-2 bg-primary/10 rounded-full">
              <CreditCard className="h-5 w-5 text-primary" />
            </div>
            <CardTitle>Plan Overview</CardTitle>
          </div>
          <CardDescription>
            Current subscription, billing period, and monthly base price.
          </CardDescription>
        </CardHeader>
        <CardContent className="grid gap-6 md:grid-cols-3 relative z-10">
          <div className="space-y-1">
            <p className="text-sm font-medium text-muted-foreground">Current plan</p>
            <p className="text-2xl font-bold text-primary">{currentPlan?.tier.tierName ?? usage.plan.toUpperCase()}</p>
          </div>
          <div className="space-y-1">
            <p className="text-sm font-medium text-muted-foreground">Billing period</p>
            <div className="flex items-center gap-2">
              <Clock className="h-4 w-4 text-muted-foreground" />
              <p className="text-lg font-medium">{periodLabel}</p>
            </div>
          </div>
          <div className="flex items-center justify-between gap-3 bg-background/50 p-3 rounded-lg border">
            <div className="space-y-1">
              <p className="text-sm font-medium text-muted-foreground">Monthly price</p>
              <p className="text-xl font-bold">{formatCurrency(currentPlan?.tier.monthlyPriceCents ?? 0)}<span className="text-sm font-normal text-muted-foreground">/mo</span></p>
            </div>
            <Badge variant={statusBadgeVariant} className="capitalize px-3 py-1">{usage.status}</Badge>
          </div>
        </CardContent>
      </Card>

      {usage.oncallEnabled && (
        <Card className="border-chart-4/20 overflow-hidden relative">
          <div className="absolute top-0 right-0 p-4 opacity-5 pointer-events-none">
            <Phone className="w-32 h-32 text-chart-4" />
          </div>
          <CardHeader>
            <div className="flex items-center gap-2">
              <div className="p-2 bg-chart-4/10 rounded-full">
                <Phone className="h-5 w-5 text-chart-4" />
              </div>
              <div>
                <CardTitle>On-Call Seats</CardTitle>
                <CardDescription>
                  Manage seats for on-call scheduling and rotations.
                  ${(usage.oncallPerUserMonthlyCents ?? 500) / 100}/user/mo.
                </CardDescription>
              </div>
            </div>
          </CardHeader>
          <CardContent className="space-y-6 relative z-10">
            <div className="flex flex-col md:flex-row gap-6 items-start md:items-center justify-between">
              <div className="space-y-1">
                <p className="text-sm font-medium text-muted-foreground">Seat Utilization</p>
                <div className="flex items-baseline gap-2">
                  <span className="text-2xl font-bold">{usage.oncallUsedSeats ?? 0}</span>
                  <span className="text-muted-foreground">of {usage.oncallSeats ?? 0} seats used</span>
                </div>
                {(usage.oncallUsedSeats ?? 0) > effectiveOnCallSeats && (
                  <p className="text-xs text-danger-fg font-medium flex items-center gap-1">
                    <AlertCircle className="h-3 w-3" />
                    Cannot reduce below currently used seats ({usage.oncallUsedSeats})
                  </p>
                )}
              </div>

              <div className="flex flex-col items-end gap-2">
                <div className="flex items-center gap-3">
                  <Button
                    variant="outline"
                    size="icon"
                    className="h-8 w-8"
                    onClick={() =>
                      setPendingOnCallSeats(
                        Math.max((usage.oncallUsedSeats ?? 0), effectiveOnCallSeats - 1)
                      )
                    }
                    disabled={updateOnCallSeatsMutation.isPending || effectiveOnCallSeats <= (usage.oncallUsedSeats ?? 0)}
                  >
                    <Minus className="h-4 w-4" />
                  </Button>
                  <div className="w-12 text-center font-mono text-lg font-medium">
                    {effectiveOnCallSeats}
                  </div>
                  <Button
                    variant="outline"
                    size="icon"
                    className="h-8 w-8"
                    onClick={() => setPendingOnCallSeats(effectiveOnCallSeats + 1)}
                    disabled={updateOnCallSeatsMutation.isPending}
                  >
                    <Plus className="h-4 w-4" />
                  </Button>
                </div>
              </div>
            </div>

            {hasPendingOnCallChange && (
              <div className="rounded-lg bg-muted/50 p-4 flex flex-col sm:flex-row items-center justify-between gap-4">
                <div className="text-sm">
                  <span className="font-medium">Summary: </span>
                  {pendingOnCallSeats > (usage.oncallSeats ?? 0) ? (
                    <>
                      Adding {pendingOnCallSeats - (usage.oncallSeats ?? 0)} seat{(pendingOnCallSeats - (usage.oncallSeats ?? 0)) > 1 ? 's' : ''}.
                      <span className="text-muted-foreground ml-1">
                        (approx. +{formatCurrency(calculateProration(pendingOnCallSeats - (usage.oncallSeats ?? 0)))} now)
                      </span>
                    </>
                  ) : (
                    <>
                      Removing {(usage.oncallSeats ?? 0) - pendingOnCallSeats} seat{((usage.oncallSeats ?? 0) - pendingOnCallSeats) > 1 ? 's' : ''}.
                      <span className="text-muted-foreground ml-1">
                        (credit applied to next bill)
                      </span>
                    </>
                  )}
                </div>
                <div className="flex items-center gap-2">
                  <Button
                    variant="ghost"
                    size="sm"
                    onClick={() => setPendingOnCallSeats(usage.oncallSeats ?? 0)}
                    disabled={updateOnCallSeatsMutation.isPending}
                  >
                    Cancel
                  </Button>
                  <Button
                    size="sm"
                    onClick={handleUpdateOnCallSeats}
                    disabled={updateOnCallSeatsMutation.isPending}
                  >
                    {updateOnCallSeatsMutation.isPending ? <Loader2 className="h-4 w-4 animate-spin" /> : 'Update Seats'}
                  </Button>
                </div>
              </div>
            )}
          </CardContent>
        </Card>
      )}

      <Card>
        <CardHeader>
          <div className="flex items-center gap-2">
            <div className="p-2 bg-success-bg rounded-full">
              <Receipt className="h-5 w-5 text-success-fg" />
            </div>
            <div>
              <CardTitle>Payment & Invoices</CardTitle>
              <CardDescription>
                Manage payment method and review recent invoices.
              </CardDescription>
            </div>
          </div>
        </CardHeader>
        <CardContent className="space-y-6">
          {!plansData?.stripeEnabled ? (
            <div className="flex items-center gap-2 text-muted-foreground bg-muted/30 p-4 rounded-lg">
              <AlertCircle className="h-5 w-5" />
              <p className="text-sm">
                Stripe billing is currently disabled for this environment.
              </p>
            </div>
          ) : (
            <>
              <div className="flex flex-wrap items-center justify-between gap-4 rounded-lg border p-4 bg-card/50">
                <div className="space-y-1">
                  <p className="text-sm font-medium text-muted-foreground">Default payment method</p>
                  {paymentMethod?.brand && paymentMethod.last4 ? (
                    <div className="flex items-center gap-2">
                      <div className="bg-primary/10 p-1 rounded">
                        <CreditCard className="h-4 w-4 text-primary" />
                      </div>
                      <p className="font-medium">
                        <span className="capitalize">{paymentMethod.brand}</span> ending in {paymentMethod.last4}
                        {paymentMethod.expMonth && paymentMethod.expYear && (
                          <span className="text-muted-foreground font-normal ml-1">
                             (Expires {paymentMethod.expMonth}/{String(paymentMethod.expYear).slice(-2)})
                          </span>
                        )}
                      </p>
                    </div>
                  ) : (
                    <p className="font-medium text-muted-foreground italic">No payment method on file</p>
                  )}
                </div>
                <Button
                  variant="outline"
                  onClick={() => setupIntentMutation.mutate()}
                  disabled={setupIntentMutation.isPending || !plansData.publishableKey}
                >
                  {setupIntentMutation.isPending ? (
                    <>
                      <Loader2 className="h-4 w-4 mr-2 animate-spin" />
                      Loading
                    </>
                  ) : (
                    'Update Payment Method'
                  )}
                </Button>
              </div>

              {showPaymentForm && setupClientSecret && stripePromise && (
                <div className="rounded-lg border p-4 bg-muted/20">
                  <Elements 
                    stripe={stripePromise} 
                    options={{ 
                      clientSecret: setupClientSecret,
                      appearance: {
                        theme: window.document.documentElement.classList.contains('dark') ? 'night' : 'stripe',
                        variables: {
                          colorPrimary: 'hsl(var(--primary))',
                          colorBackground: 'hsl(var(--background))',
                          colorText: 'hsl(var(--foreground))',
                          colorDanger: 'hsl(var(--destructive))',
                          fontFamily: 'system-ui, sans-serif',
                          borderRadius: '0.5rem',
                        },
                      },
                    }}
                  >
                    <PaymentMethodSetupForm
                      onCancel={() => {
                        setShowPaymentForm(false)
                        setSetupClientSecret(null)
                      }}
                      onSuccess={onPaymentMethodUpdated}
                    />
                  </Elements>
                </div>
              )}

              <div className="space-y-3">
                <div className="flex items-center justify-between">
                  <p className="text-sm font-medium">Recent invoices</p>
                </div>
                {invoicesLoading ? (
                  <div className="flex items-center justify-center py-8 text-muted-foreground">
                    <Loader2 className="h-6 w-6 animate-spin mr-2" />
                    <p className="text-sm">Loading invoices...</p>
                  </div>
                ) : invoices.length === 0 ? (
                  <div className="text-center py-8 border rounded-lg border-dashed">
                    <Receipt className="h-8 w-8 mx-auto text-muted-foreground/50 mb-2" />
                    <p className="text-sm text-muted-foreground">No invoices available yet.</p>
                  </div>
                ) : (
                  <div className="rounded-md border overflow-hidden">
                    <Table>
                      <TableHeader className="bg-muted/50">
                        <TableRow>
                          <TableHead>Date</TableHead>
                          <TableHead>Amount</TableHead>
                          <TableHead>Status</TableHead>
                          <TableHead className="text-right">Invoice</TableHead>
                        </TableRow>
                      </TableHeader>
                      <TableBody>
                        {invoices.map((invoice) => (
                          <TableRow key={invoice.id}>
                            <TableCell className="font-medium">{formatDate(invoice.date, timezone)}</TableCell>
                            <TableCell>{formatCurrency(invoice.amountCents)}</TableCell>
                            <TableCell>
                              <Badge variant={invoice.status === 'paid' ? 'success' : 'secondary'}>
                                {invoice.status === 'paid' && <Check className="h-3 w-3 mr-1" />}
                                {invoice.status}
                              </Badge>
                            </TableCell>
                            <TableCell className="text-right">
                              {invoice.pdfUrl ? (
                                <Button variant="ghost" size="sm" asChild className="h-8">
                                  <a
                                    href={invoice.pdfUrl}
                                    target="_blank"
                                    rel="noreferrer"
                                    className="flex items-center gap-1"
                                  >
                                    <Download className="h-3.5 w-3.5" />
                                    Download
                                  </a>
                                </Button>
                              ) : (
                                <span className="text-sm text-muted-foreground">—</span>
                              )}
                            </TableCell>
                          </TableRow>
                        ))}
                      </TableBody>
                    </Table>
                  </div>
                )}
              </div>
            </>
          )}
        </CardContent>
      </Card>

      <Card>
        <CardHeader>
          <div className="flex items-center justify-between">
            <div className="flex items-center gap-2">
              <div className="p-2 bg-primary/10 rounded-full">
                <Layers className="h-5 w-5 text-primary" />
              </div>
              <div>
                <CardTitle>Plan Management</CardTitle>
                <CardDescription>
                  Compare plans, switch tiers, or cancel your subscription.
                </CardDescription>
              </div>
            </div>
            <div className="flex items-center rounded-lg border bg-muted/50 p-1">
              <button
                onClick={() => setBillingInterval('monthly')}
                className={`relative rounded-md px-3 py-1.5 text-xs font-medium transition-all ${
                  billingInterval === 'monthly'
                    ? 'bg-background text-foreground'
                    : 'text-muted-foreground hover:text-foreground'
                }`}
              >
                Monthly
              </button>
              <button
                onClick={() => setBillingInterval('yearly')}
                className={`relative rounded-md px-3 py-1.5 text-xs font-medium transition-all ${
                  billingInterval === 'yearly'
                    ? 'bg-background text-foreground'
                    : 'text-muted-foreground hover:text-foreground'
                }`}
              >
                Yearly
                <span className="ml-1.5 text-[10px] text-success-fg font-bold">
                  -17%
                </span>
              </button>
            </div>
          </div>
        </CardHeader>
        <CardContent>
          {billablePlans.length === 0 ? (
            <p className="text-sm text-muted-foreground p-4 text-center">No paid plans configured yet.</p>
          ) : (
            <div className="grid grid-cols-1 lg:grid-cols-3 gap-6">
              {billablePlans.map((plan) => {
                const isCurrentPlan = plan.tier.tierName.toLowerCase() === usage.plan.toLowerCase()
                
                // Use shared helper to build display model
                const model = buildPricingCardModel(
                  { ...plan.tier, trialDays: plan.trialDays ?? plan.tier.trialDays }, 
                  billingInterval
                )
                
                const isYearly = billingInterval === 'yearly'

                return (
                  <div
                    key={plan.tier.id}
                    className={`flex flex-col rounded-xl border-2 p-6 transition-all ${
                      isCurrentPlan
                        ? 'border-primary bg-primary/5 relative'
                        : 'border-muted hover:border-primary/50'
                    }`}
                  >
                    {isCurrentPlan && (
                      <div className="absolute -top-3 left-1/2 -translate-x-1/2 bg-primary text-primary-foreground text-xs px-3 py-1 rounded-full font-medium">
                        Current Plan
                      </div>
                    )}
                    <div className="mb-4">
                      <h3 className="font-bold text-xl">{model.name}</h3>
                      <div className="flex items-baseline gap-1 mt-2">
                        <span className="text-3xl font-bold">${model.displayPrice.toFixed(0)}</span>
                        <span className="text-sm text-muted-foreground">/mo</span>
                      </div>
                      {isYearly && model.displayPrice > 0 && (
                        <p className="text-xs text-muted-foreground mt-1">
                          billed ${model.yearlyTotalPrice.toFixed(0)}/yr
                        </p>
                      )}
                    </div>

                    <div className="space-y-4 mb-8 flex-1">
                      <div>
                        <p className="text-[10px] font-semibold text-muted-foreground uppercase tracking-wider mb-2">Included</p>
                        <ul className="space-y-2">
                          {model.includedLimits.map((limit, i) => (
                            <li key={i} className="flex items-start gap-2 text-sm">
                              <CheckCircle2 className={`h-4 w-4 flex-shrink-0 mt-0.5 ${isCurrentPlan ? 'text-primary' : 'text-success-fg'}`} />
                              <span className="text-sm leading-tight">{limit}</span>
                            </li>
                          ))}
                        </ul>
                      </div>
                      {model.overages.length > 0 && (
                        <div>
                          <p className="text-[10px] font-semibold text-muted-foreground uppercase tracking-wider mb-2">Overages</p>
                          <ul className="space-y-1.5">
                            {model.overages.map((o, i) => (
                              <li key={i} className="flex items-center gap-2 text-sm">
                                <TrendingUp className="h-3.5 w-3.5 text-muted-foreground/60 shrink-0" />
                                <span className="text-muted-foreground">
                                  {o.label}: <span className="font-medium text-foreground">{o.rate}</span>
                                </span>
                              </li>
                            ))}
                          </ul>
                        </div>
                      )}
                      {model.platformFeatures.length > 0 && (
                        <div>
                          <p className="text-[10px] font-semibold text-muted-foreground uppercase tracking-wider mb-2">Features</p>
                          <ul className="space-y-2">
                            {model.platformFeatures.map((feature, i) => (
                              <li key={i} className="flex items-start gap-2 text-sm">
                                <CheckCircle2 className={`h-4 w-4 flex-shrink-0 mt-0.5 ${isCurrentPlan ? 'text-primary' : 'text-success-fg'}`} />
                                <span className="text-sm leading-tight">{feature}</span>
                              </li>
                            ))}
                          </ul>
                        </div>
                      )}
                    </div>

                    <Button
                      className="w-full"
                      variant={isCurrentPlan ? 'secondary' : 'default'}
                      disabled={checkoutMutation.isPending || isCurrentPlan}
                      onClick={() => checkoutMutation.mutate({
                        tierName: plan.tier.tierName,
                        interval: billingInterval
                      })}
                    >
                      {isCurrentPlan ? 'Current plan' : `Upgrade to ${model.name}`}
                    </Button>
                  </div>
                )
              })}
            </div>
          )}

          {isPaidTier && (
            <div className="mt-8 flex justify-center border-t pt-6">
              <Button
                variant="ghost"
                className="text-destructive hover:text-destructive hover:bg-destructive/10"
                onClick={() => setShowCancelDialog(true)}
                disabled={cancelSubscriptionMutation.isPending}
              >
                Cancel Subscription
              </Button>
            </div>
          )}
        </CardContent>
      </Card>

      <Dialog open={showCancelDialog} onOpenChange={setShowCancelDialog}>
        <DialogContent>
          <DialogHeader>
            <DialogTitle>Cancel subscription</DialogTitle>
            <DialogDescription>
              Your subscription will stay active until the current period ends, then switch to free.
            </DialogDescription>
          </DialogHeader>
          <DialogFooter>
            <Button
              variant="outline"
              onClick={() => setShowCancelDialog(false)}
              disabled={cancelSubscriptionMutation.isPending}
            >
              Keep subscription
            </Button>
            <Button
              variant="destructive"
              onClick={() => cancelSubscriptionMutation.mutate()}
              disabled={cancelSubscriptionMutation.isPending}
            >
              {cancelSubscriptionMutation.isPending ? 'Canceling...' : 'Confirm cancel'}
            </Button>
          </DialogFooter>
        </DialogContent>
      </Dialog>
    </div>
  )
}

function PaymentMethodSetupForm({
  onSuccess,
  onCancel,
}: {
  onSuccess: () => void
  onCancel: () => void
}) {
  const stripe = useStripe()
  const elements = useElements()
  const { toast } = useToast()
  const [isSubmitting, setIsSubmitting] = useState(false)

  const submit = async (event: FormEvent<HTMLFormElement>) => {
    event.preventDefault()
    if (!stripe || !elements) return

    setIsSubmitting(true)
    
    try {
      const result = await stripe.confirmSetup({
        elements,
        redirect: 'if_required',
      })

      if (result.error) {
        toast({
          title: 'Payment method update failed',
          description: result.error.message || 'Stripe could not confirm this payment method.',
          variant: 'destructive',
        })
        setIsSubmitting(false)
        return
      }

      // Setup was confirmed successfully, now update the default payment method on the backend
      if (result.setupIntent?.id) {
        try {
          await api.confirmBillingSetupIntent(result.setupIntent.id)
          toast({
            title: 'Payment method saved',
            description: 'Your default payment method has been updated.',
          })
          onSuccess()
        } catch {
          toast({
            title: 'Payment method partially saved',
            description: 'Card was added but may not be set as default. Please refresh the page.',
            variant: 'destructive',
          })
        }
      } else {
        toast({
          title: 'Payment method saved',
          description: 'Your default payment method has been updated.',
        })
        onSuccess()
      }
    } finally {
      setIsSubmitting(false)
    }
  }

  return (
    <form onSubmit={submit} className="space-y-4">
      <PaymentElement />
      <div className="flex items-center gap-2">
        <Button type="submit" disabled={!stripe || isSubmitting}>
          {isSubmitting ? (
            <>
              <Loader2 className="h-4 w-4 mr-2 animate-spin" />
              Saving
            </>
          ) : (
            <>
              <CheckCircle2 className="h-4 w-4 mr-2" />
              Save payment method
            </>
          )}
        </Button>
        <Button type="button" variant="outline" onClick={onCancel} disabled={isSubmitting}>
          Cancel
        </Button>
      </div>
    </form>
  )
}

function NotificationsTab() {
  const queryClient = useQueryClient()
  const { toast } = useToast()
  const { timezone } = useTimezone()
  const { user } = useAuth()

  const { data: preferences, isLoading: isLoadingPrefs } = useQuery({
    queryKey: ['notificationPreferences'],
    queryFn: () => api.getNotificationPreferences(),
    enabled: api.isAuthenticated(),
  })

  const { data: pushDevices = [] } = useQuery({
    queryKey: ['pushDevices'],
    queryFn: () => api.getPushDevices(),
    enabled: api.isAuthenticated(),
  })

  const updateGlobalMutation = useMutation({
    mutationFn: (prefs: Partial<{
      weeklySummary: boolean
      alertFrequencyMinutes: number
      emailEnabled: boolean
      pushEnabled: boolean
    }>) => api.updateNotificationPreferences(prefs),
    onSuccess: () => {
      queryClient.invalidateQueries({ queryKey: ['notificationPreferences'] })
      toast({ title: 'Global preferences updated' })
    },
    onError: (err: Error) => {
      toast({
        title: 'Failed to update preferences',
        description: err.message,
        variant: 'destructive',
      })
    },
  })

  const updateServiceMutation = useMutation({
    mutationFn: ({ serviceId, prefs }: {
      serviceId: string
      prefs: Partial<{
        issueAlerts: boolean
        errorAlerts: boolean
        weeklySummary: boolean
        alertFrequencyMinutes: number
      }>
    }) => api.updateProjectNotificationPreferences(serviceId, prefs),
    onSuccess: () => {
      queryClient.invalidateQueries({ queryKey: ['notificationPreferences'] })
      toast({ title: 'Service preferences updated' })
    },
    onError: (err: Error) => {
      toast({
        title: 'Failed to update service preferences',
        description: err.message,
        variant: 'destructive',
      })
    },
  })

  const deleteServiceMutation = useMutation({
    mutationFn: (serviceId: string) => api.deleteProjectNotificationPreferences(serviceId),
    onSuccess: () => {
      queryClient.invalidateQueries({ queryKey: ['notificationPreferences'] })
      toast({ title: 'Service override removed', description: 'Using global preferences for this service.' })
    },
    onError: (err: Error) => {
      toast({
        title: 'Failed to remove override',
        description: err.message,
        variant: 'destructive',
      })
    },
  })

  const { data: onCallContact, isLoading: isLoadingOnCallContact } = useQuery({
    queryKey: ['on-call-contact'],
    queryFn: () => api.getOnCallContact(),
    enabled: api.isAuthenticated(),
  })

  const [localOnCallPhone, setOnCallPhone] = useState<string | undefined>(undefined)
  const [onCallConsent, setOnCallConsent] = useState(false)
  const onCallPhone = localOnCallPhone ?? onCallContact?.phoneNumber ?? ''

  const updateOnCallContactMutation = useMutation({
    mutationFn: () => api.updateOnCallContact({
      phoneNumber: onCallPhone.trim(),
      consentAccepted: onCallConsent,
      consentVersion: 'v1',
    }),
    onSuccess: () => {
      queryClient.invalidateQueries({ queryKey: ['on-call-contact'] })
      setOnCallConsent(false)
      toast({ title: 'On-call contact saved', description: onCallConsent ? 'You are now opted in to SMS and voice call alerts.' : 'Phone number saved (not yet opted in).' })
    },
    onError: (err: Error) => {
      toast({ title: 'Failed to save', description: err.message, variant: 'destructive' })
    },
  })

  const deleteOnCallContactMutation = useMutation({
    mutationFn: () => api.deleteOnCallContact(),
    onSuccess: () => {
      queryClient.invalidateQueries({ queryKey: ['on-call-contact'] })
      setOnCallPhone('')
      setOnCallConsent(false)
      toast({ title: 'On-call contact removed' })
    },
    onError: (err: Error) => {
      toast({ title: 'Failed to remove', description: err.message, variant: 'destructive' })
    },
  })

  if (isLoadingPrefs) {
    return (
      <div className="flex items-center justify-center py-12">
        <Loader2 className="h-8 w-8 animate-spin text-muted-foreground" />
      </div>
    )
  }

  const global = preferences?.global || {
    weeklySummary: true,
    alertFrequencyMinutes: 30,
    emailEnabled: true,
    pushEnabled: false,
  }

  const servicePreferences = preferences?.projects || []

  return (
    <section>
      <SettingsSection
        title="Notifications"
        description="How and when Moneat reaches you. Channel delivery rules live in workflows."
      />

      <div className="mb-5 flex flex-col gap-3 rounded-md border border-info-border bg-info-bg/60 p-3 text-sm sm:flex-row sm:items-center">
        <Info className="h-4 w-4 shrink-0 text-info-fg" />
        <span className="flex-1 text-info-fg">
          Email, Slack, and Discord alert delivery is controlled by your alert &amp; recovery{' '}
          <Link to="/workflows" className="underline">workflows</Link>.
        </span>
        <Button asChild size="sm" variant="outline">
          <Link to="/workflows">Open workflows</Link>
        </Button>
      </div>

      <SettingsBlock title="Your delivery channels">
        <SettingRow
          label={
            <span className="inline-flex items-center gap-2">
              <Mail className="h-4 w-4 text-muted-foreground" />
              Email
            </span>
          }
          description={
            user?.email
              ? `${user.email}${user.emailVerified ? ' · verified' : ' · unverified'}`
              : 'Your account email.'
          }
        >
          <Switch
            checked={global.emailEnabled ?? true}
            onCheckedChange={(checked) => updateGlobalMutation.mutate({ emailEnabled: checked })}
            disabled={updateGlobalMutation.isPending}
          />
        </SettingRow>
        <SettingRow
          label={
            <span className="inline-flex items-center gap-2">
              <Phone className="h-4 w-4 text-muted-foreground" />
              Mobile push
            </span>
          }
          description={
            pushDevices.length > 0
              ? `Deliver alert and on-call pushes to your ${pushDevices.length} registered device${pushDevices.length === 1 ? '' : 's'}.`
              : 'No devices registered yet — install the Moneat mobile app to receive pushes.'
          }
        >
          <Switch
            checked={global.pushEnabled ?? false}
            onCheckedChange={(checked) => updateGlobalMutation.mutate({ pushEnabled: checked })}
            disabled={updateGlobalMutation.isPending}
          />
        </SettingRow>
      </SettingsBlock>

      <SettingsBlock title="Email digests">
        <SettingRow label="Weekly summary" description="A digest of errors, alerts, and uptime every Monday.">
          <Switch
            checked={global.weeklySummary}
            onCheckedChange={(checked) => updateGlobalMutation.mutate({ weeklySummary: checked })}
          />
        </SettingRow>
        <SettingRow
          label="Error alert frequency"
          description="Minimum time between repeat alerts for the same service."
        >
          <Select
            value={global.alertFrequencyMinutes.toString()}
            onValueChange={(value) => updateGlobalMutation.mutate({ alertFrequencyMinutes: parseInt(value) })}
          >
            <SelectTrigger className="w-full sm:max-w-[320px]">
              <SelectValue />
            </SelectTrigger>
            <SelectContent>
              <SelectItem value="5">5 minutes</SelectItem>
              <SelectItem value="15">15 minutes</SelectItem>
              <SelectItem value="30">30 minutes</SelectItem>
              <SelectItem value="60">1 hour</SelectItem>
              <SelectItem value="240">4 hours</SelectItem>
            </SelectContent>
          </Select>
        </SettingRow>
      </SettingsBlock>

      <SectionCard
        title="Per-service overrides"
        icon={Layers}
        count={servicePreferences.length || undefined}
        className="mb-4"
        flushBody
      >
          {servicePreferences.length === 0 ? (
            <p className="px-4 py-3 text-sm text-muted-foreground">
              Services not listed here use your defaults above.
            </p>
          ) : (
            <Table>
              <TableHeader>
                <TableRow>
                  <TableHead>Service</TableHead>
                  <TableHead className="text-center">Issues</TableHead>
                  <TableHead className="text-center">Errors</TableHead>
                  <TableHead className="text-center">Weekly</TableHead>
                  <TableHead className="text-center">Frequency</TableHead>
                  <TableHead className="w-[100px]"></TableHead>
                </TableRow>
              </TableHeader>
              <TableBody>
                {servicePreferences.map((servicePreference) => (
                  <TableRow key={servicePreference.projectId}>
                    <TableCell className="font-medium">{servicePreference.projectName}</TableCell>
                    <TableCell className="text-center">
                      <Checkbox
                        checked={servicePreference.issueAlerts}
                        onCheckedChange={(checked) =>
                          updateServiceMutation.mutate({
                            serviceId: servicePreference.projectId,
                            prefs: { issueAlerts: checked === true },
                          })
                        }
                      />
                    </TableCell>
                    <TableCell className="text-center">
                      <Checkbox
                        checked={servicePreference.errorAlerts}
                        onCheckedChange={(checked) =>
                          updateServiceMutation.mutate({
                            serviceId: servicePreference.projectId,
                            prefs: { errorAlerts: checked === true },
                          })
                        }
                      />
                    </TableCell>
                    <TableCell className="text-center">
                      <Checkbox
                        checked={servicePreference.weeklySummary}
                        onCheckedChange={(checked) =>
                          updateServiceMutation.mutate({
                            serviceId: servicePreference.projectId,
                            prefs: { weeklySummary: checked === true },
                          })
                        }
                      />
                    </TableCell>
                    <TableCell className="text-center text-sm text-muted-foreground">
                      {servicePreference.alertFrequencyMinutes >= 60
                        ? `${servicePreference.alertFrequencyMinutes / 60}h`
                        : `${servicePreference.alertFrequencyMinutes}m`}
                    </TableCell>
                    <TableCell>
                      <Button
                        variant="ghost"
                        size="sm"
                        onClick={() => deleteServiceMutation.mutate(servicePreference.projectId)}
                      >
                        Reset
                      </Button>
                    </TableCell>
                  </TableRow>
                ))}
              </TableBody>
            </Table>
          )}
      </SectionCard>

      <SectionCard title="On-call SMS & voice fallback" icon={Phone}>
        <div className="space-y-4">
          {isLoadingOnCallContact ? (
            <div className="flex items-center gap-2 text-muted-foreground text-sm">
              <Loader2 className="h-4 w-4 animate-spin" /> Loading…
            </div>
          ) : onCallContact?.onCallPhoneOptIn ? (
            <div className="space-y-3">
              <div className="flex items-center gap-2">
                <CheckCircle2 className="h-4 w-4 text-success-fg" />
                <span className="text-sm font-medium">Opted in — {onCallContact.phoneNumber}</span>
              </div>
              {onCallContact.onCallPhoneConsentedAt && (
                <p className="text-xs text-muted-foreground">
                  Consented on {formatDateUtil(new Date(onCallContact.onCallPhoneConsentedAt), timezone)}
                </p>
              )}
              <div className="flex gap-2">
                <Button
                  size="sm"
                  variant="destructive"
                  onClick={() => deleteOnCallContactMutation.mutate()}
                  disabled={deleteOnCallContactMutation.isPending}
                >
                  {deleteOnCallContactMutation.isPending ? <Loader2 className="h-4 w-4 animate-spin" /> : 'Remove & opt out'}
                </Button>
              </div>
            </div>
          ) : (
            <div className="space-y-4 max-w-sm">
              {onCallContact?.phoneNumber && !onCallContact.onCallPhoneOptIn && (
                <div className="flex items-center gap-2 text-warning-fg text-sm">
                  <AlertCircle className="h-4 w-4" />
                  Phone number saved but not opted in yet. Check the consent box below to enable alerts.
                </div>
              )}
              <div className="space-y-2">
                <Label htmlFor="oncall-phone">Mobile number (E.164 format)</Label>
                <Input
                  id="oncall-phone"
                  type="tel"
                  placeholder="+15551234567"
                  value={onCallPhone}
                  onChange={(e) => setOnCallPhone(e.target.value)}
                />
              </div>
              <div className="flex items-start gap-2">
                <Checkbox
                  id="oncall-consent"
                  checked={onCallConsent}
                  onCheckedChange={(c) => setOnCallConsent(c === true)}
                  className="mt-0.5"
                />
                <Label htmlFor="oncall-consent" className="text-sm font-normal leading-snug cursor-pointer">
                  I agree to receive on-call alert SMS messages and voice calls from Moneat at the number provided.
                  Message and data rates may apply. Reply STOP to unsubscribe or HELP for help.
                  I understand I can manage this setting anytime in my account.{' '}
                  <Link to="/legal/sms-consent" className="underline text-primary" target="_blank">
                    Learn more
                  </Link>
                </Label>
              </div>
              <Button
                size="sm"
                onClick={() => updateOnCallContactMutation.mutate()}
                disabled={
                  !onCallPhone.trim().match(/^\+[1-9]\d{1,14}$/) ||
                  !onCallConsent ||
                  updateOnCallContactMutation.isPending
                }
              >
                {updateOnCallContactMutation.isPending ? (
                  <><Loader2 className="h-4 w-4 mr-2 animate-spin" />Saving…</>
                ) : (
                  'Save & opt in'
                )}
              </Button>
              {onCallContact?.phoneNumber && (
                <Button
                  size="sm"
                  variant="ghost"
                  onClick={() => deleteOnCallContactMutation.mutate()}
                  disabled={deleteOnCallContactMutation.isPending}
                >
                  Remove number
                </Button>
              )}
            </div>
          )}
        </div>
      </SectionCard>
    </section>
  )
}

function SilencePeriodsTab() {
  const queryClient = useQueryClient()
  const { toast } = useToast()
  const { timezone } = useTimezone()
  const [isCustomDialogOpen, setIsCustomDialogOpen] = useState(false)
  const [now] = useState(Date.now)

  const { data: silencePeriods = [], isLoading } = useQuery({
    queryKey: ['silence-periods'],
    queryFn: () => api.getSilencePeriods(),
    enabled: api.isAuthenticated(),
    refetchInterval: 30000,
  })

  const createMutation = useMutation({
    mutationFn: (data: { reason?: string; starts_at: number; ends_at: number }) =>
      api.createSilencePeriod(data),
    onSuccess: () => {
      queryClient.invalidateQueries({ queryKey: ['silence-periods'] })
      toast({ title: 'Silence period created', description: 'Alert notifications will be suppressed during this period.' })
      setIsCustomDialogOpen(false)
    },
    onError: () => {
      toast({ title: 'Error', description: 'Failed to create silence period.', variant: 'destructive' })
    },
  })

  const deleteMutation = useMutation({
    mutationFn: (id: string) => api.deleteSilencePeriod(id),
    onSuccess: () => {
      queryClient.invalidateQueries({ queryKey: ['silence-periods'] })
      toast({ title: 'Silence period removed' })
    },
  })

  const handleQuickSilence = (minutes: number, label: string) => {
    createMutation.mutate({
      reason: `Quick silence: ${label}`,
      starts_at: now,
      ends_at: now + minutes * 60 * 1000,
    })
  }

  const handleCustomSilence = (e: React.FormEvent<HTMLFormElement>) => {
    e.preventDefault()
    const formData = new FormData(e.currentTarget)
    const startsAt = new Date(formData.get('startsAt') as string).getTime()
    const endsAt = new Date(formData.get('endsAt') as string).getTime()
    const reason = (formData.get('reason') as string) || undefined

    if (endsAt <= startsAt) {
      toast({ title: 'Invalid range', description: 'End time must be after start time.', variant: 'destructive' })
      return
    }

    createMutation.mutate({ reason, starts_at: startsAt, ends_at: endsAt })
  }

  const activePeriods = silencePeriods.filter((p) => p.startsAt <= now && p.endsAt > now)
  const scheduledPeriods = silencePeriods.filter((p) => p.startsAt > now)
  const isCurrentlySilenced = activePeriods.length > 0

  const formatDateTime = (ms: number) => formatDateTimeUtil(new Date(ms), timezone)

  const formatTimeRemaining = (endsAt: number) => {
    const diff = endsAt - now
    if (diff <= 0) return 'Expired'
    const hours = Math.floor(diff / (1000 * 60 * 60))
    const minutes = Math.floor((diff % (1000 * 60 * 60)) / (1000 * 60))
    if (hours > 0) return `${hours}h ${minutes}m remaining`
    return `${minutes}m remaining`
  }

  const quickOptions = [
    { minutes: 30, label: '30 minutes' },
    { minutes: 60, label: '1 hour' },
    { minutes: 4 * 60, label: '4 hours' },
    { minutes: 8 * 60, label: '8 hours' },
    { minutes: 24 * 60, label: '24 hours' },
  ]

  const defaultStart = new Date()
  defaultStart.setMinutes(defaultStart.getMinutes() - defaultStart.getTimezoneOffset())
  const defaultEnd = new Date(defaultStart.getTime() + 2 * 60 * 60 * 1000)
  const toInputFormat = (d: Date) => d.toISOString().slice(0, 16)

  return (
    <section>
      <SettingsSection
        title="Silence periods"
        description="Suppress alert notifications org-wide during deploys and maintenance. Alerts still evaluate — only delivery is muted."
        actions={
          <Button onClick={() => setIsCustomDialogOpen(true)}>
            <Calendar data-icon="inline-start" />
            Schedule window
          </Button>
        }
      />

      {isCurrentlySilenced && (
        <div className="mb-5 flex flex-col gap-3 rounded-md border border-warning-border bg-warning-bg/60 p-3 text-sm sm:flex-row sm:items-center">
          <BellOff className="h-4 w-4 shrink-0 text-warning-fg" />
          <span className="flex-1 text-warning-fg">
            <b className="font-semibold">Alerts are currently silenced.</b>{' '}
            {activePeriods.length === 1
              ? `${formatTimeRemaining(activePeriods[0].endsAt)} · ${activePeriods[0].reason || 'No reason specified'}`
              : `${activePeriods.length} active silence periods`}
          </span>
          <Button
            size="sm"
            variant="ghost"
            onClick={() => activePeriods.forEach((p) => deleteMutation.mutate(p.id))}
            disabled={deleteMutation.isPending}
          >
            End now
          </Button>
        </div>
      )}

      <SettingsBlock title="Quick silence">
        <div className="flex flex-wrap gap-2">
          {quickOptions.map((opt) => (
            <Button
              key={opt.minutes}
              variant="outline"
              size="sm"
              className="gap-2"
              onClick={() => handleQuickSilence(opt.minutes, opt.label)}
              disabled={createMutation.isPending}
            >
              <BellOff className="h-3.5 w-3.5" />
              {opt.label}
            </Button>
          ))}
        </div>
      </SettingsBlock>

      <Dialog open={isCustomDialogOpen} onOpenChange={setIsCustomDialogOpen}>
              <DialogContent>
                <DialogHeader>
                  <DialogTitle className="flex items-center gap-2">
                    <Calendar className="h-5 w-5 text-info-fg" />
                    Schedule Silence Window
                  </DialogTitle>
                  <DialogDescription>
                    All alert notifications will be suppressed during this window.
                  </DialogDescription>
                </DialogHeader>
                <form onSubmit={handleCustomSilence}>
                  <div className="space-y-4 py-2">
                    <div className="space-y-2">
                      <Label htmlFor="reason">Reason (optional)</Label>
                      <Input
                        id="reason"
                        name="reason"
                        placeholder="e.g. Scheduled maintenance, Server migration"
                      />
                    </div>
                    <div className="space-y-2">
                      <Label htmlFor="startsAt">Start Time</Label>
                      <Input
                        id="startsAt"
                        name="startsAt"
                        type="datetime-local"
                        defaultValue={toInputFormat(defaultStart)}
                        required
                      />
                    </div>
                    <div className="space-y-2">
                      <Label htmlFor="endsAt">End Time</Label>
                      <Input
                        id="endsAt"
                        name="endsAt"
                        type="datetime-local"
                        defaultValue={toInputFormat(defaultEnd)}
                        required
                      />
                    </div>
                  </div>
                  <DialogFooter className="mt-4">
                    <Button type="button" variant="outline" onClick={() => setIsCustomDialogOpen(false)}>
                      Cancel
                    </Button>
                    <Button type="submit" disabled={createMutation.isPending}>
                      {createMutation.isPending ? 'Creating...' : 'Create'}
                    </Button>
                  </DialogFooter>
                </form>
              </DialogContent>
      </Dialog>

      <SectionCard
        title="Active & scheduled"
        icon={Calendar}
        count={silencePeriods.length || undefined}
        flushBody
      >
          {isLoading ? (
            <div className="flex items-center gap-2 px-4 py-8 text-sm text-muted-foreground">
              <Loader2 className="h-4 w-4 animate-spin" /> Loading silence periods…
            </div>
          ) : silencePeriods.length > 0 ? (
            <div className="divide-y">
              {activePeriods.map((period) => (
                <div key={period.id} className="group flex items-center gap-3 px-4 py-2.5">
                  <StatusDot tone="warning" />
                  <div className="min-w-0 flex-1">
                    <div className="flex items-center gap-2">
                      <span className="text-sm font-medium">{period.reason || 'Silence period'}</span>
                      <Badge variant="warning">Active · {formatTimeRemaining(period.endsAt).replace(' remaining', '')}</Badge>
                    </div>
                    <div className="mt-0.5 font-mono text-xs text-muted-foreground">
                      {formatDateTime(period.startsAt)} → {formatDateTime(period.endsAt)}
                    </div>
                  </div>
                  <Button
                    size="icon"
                    variant="ghost"
                    className="h-7 w-7 opacity-0 transition-opacity group-hover:opacity-100"
                    onClick={() => deleteMutation.mutate(period.id)}
                    disabled={deleteMutation.isPending}
                  >
                    <Trash2 className="h-3.5 w-3.5 text-destructive" />
                  </Button>
                </div>
              ))}
              {scheduledPeriods.map((period) => (
                <div key={period.id} className="group flex items-center gap-3 px-4 py-2.5">
                  <StatusDot tone="neutral" />
                  <div className="min-w-0 flex-1">
                    <div className="flex items-center gap-2">
                      <span className="text-sm font-medium">{period.reason || 'Scheduled silence'}</span>
                      <Badge variant="secondary">Scheduled</Badge>
                    </div>
                    <div className="mt-0.5 font-mono text-xs text-muted-foreground">
                      {formatDateTime(period.startsAt)} → {formatDateTime(period.endsAt)}
                    </div>
                  </div>
                  <Button
                    size="icon"
                    variant="ghost"
                    className="h-7 w-7 opacity-0 transition-opacity group-hover:opacity-100"
                    onClick={() => deleteMutation.mutate(period.id)}
                    disabled={deleteMutation.isPending}
                  >
                    <Trash2 className="h-3.5 w-3.5 text-destructive" />
                  </Button>
                </div>
              ))}
            </div>
          ) : (
            <div className="px-4 py-12 text-center">
              <p className="text-sm font-medium">No silence periods</p>
              <p className="mx-auto mt-1 max-w-sm text-sm text-muted-foreground">
                Use the quick silence buttons above or schedule a maintenance window to suppress alert notifications.
              </p>
            </div>
          )}
      </SectionCard>

      <p className="mt-3 flex items-start gap-2 text-xs leading-relaxed text-muted-foreground">
        <Info className="mt-0.5 h-3.5 w-3.5 shrink-0" />
        During a silence period, all alert notifications are suppressed organization-wide. Alerts are still
        evaluated and trigger timestamps recorded; expired periods are cleaned up automatically.
      </p>
    </section>
  )
}

function AccountTab() {
  const { toast } = useToast()
  const { user } = useAuth()
  const orgId = user?.orgId
  const [deleteAccountOpen, setDeleteAccountOpen] = useState(false)
  const [deleteOrgOpen, setDeleteOrgOpen] = useState(false)
  const [accountConfirmation, setAccountConfirmation] = useState('')
  const [orgConfirmation, setOrgConfirmation] = useState('')
  
  const { data: orgData } = useQuery({
    queryKey: ['organization', orgId],
    queryFn: () => api.getOrganizationAccountSettings(orgId!),
    enabled: !!orgId,
  })
  
  const { data: accountValidation } = useQuery({
    queryKey: ['account-deletion-validation'],
    queryFn: () => api.getAccountDeletionValidation(),
  })
  
  const { data: orgValidation } = useQuery({
    queryKey: ['org-deletion-validation', orgId],
    queryFn: () => api.getOrganizationDeletionValidation(orgId!),
    enabled: !!orgId,
  })
  
  const deleteAccountMutation = useMutation({
    mutationFn: () => api.deleteAccount(accountConfirmation.trim()),
    onSuccess: async () => {
      toast({
        title: 'Account deleted',
        description: 'Your account has been successfully deleted.',
      })
      localStorage.removeItem('auth_token') // Clean up any legacy token
      await api.logout()
      window.location.href = '/login'
    },
    onError: (error: Error) => {
      toast({
        title: 'Failed to delete account',
        description: error.message,
        variant: 'destructive',
      })
    },
  })
  
  const deleteOrgMutation = useMutation({
    mutationFn: async () => {
      if (!orgId) {
        throw new Error('No organization selected')
      }
      return api.deleteOrganization(orgId, orgConfirmation.trim())
    },
    onSuccess: () => {
      toast({
        title: 'Organization deleted',
        description: 'The organization has been successfully deleted.',
      })
      window.location.href = '/onboarding'
    },
    onError: (error: Error) => {
      toast({
        title: 'Failed to delete organization',
        description: error.message,
        variant: 'destructive',
      })
    },
  })
  
  const isOwner = orgData?.role === 'owner' || user?.orgRole === 'owner'
  const isAccountConfirmationValid =
    accountConfirmation.trim().toLowerCase() === (user?.email || '').trim().toLowerCase()
  
  return (
    <section>
      <SettingsSection
        title="Danger zone"
        description="Irreversible, destructive actions. Please be certain."
      />
      <div className="space-y-4">
      {/* Organization Deletion - Owner Only */}
      {isOwner && (
        <Card className="border-danger-border">
          <CardHeader>
            <CardTitle className="text-danger-fg flex items-center gap-2">
              <AlertTriangle className="h-5 w-5" />
              Delete Organization
            </CardTitle>
            <CardDescription>
              Permanently delete your organization and all associated data. This action cannot be undone.
            </CardDescription>
          </CardHeader>
          <CardContent className="space-y-4">
            <div className="rounded-lg bg-danger-bg border border-danger-border p-4">
              <h4 className="text-sm font-semibold text-danger-fg mb-2">
                What will be deleted:
              </h4>
              <ul className="text-sm text-danger-fg/90 space-y-1 list-disc list-inside">
                <li>All services and their events</li>
                <li>All error data, transactions, sessions, and replays</li>
                <li>All monitoring data and uptime checks</li>
                <li>All team members will be removed</li>
                <li>All integrations and alert configurations</li>
                <li>Billing subscription (will be cancelled)</li>
              </ul>
            </div>
            
            {!orgValidation?.canDelete && (
              <div className="rounded-lg bg-warning-bg border border-warning-border p-3">
                <p className="text-sm text-warning-fg">
                  <AlertCircle className="h-4 w-4 inline mr-1" />
                  {orgValidation?.error || 'Cannot delete organization at this time.'}
                </p>
              </div>
            )}
            
            <Button
              variant="destructive"
              onClick={() => setDeleteOrgOpen(true)}
              disabled={orgValidation?.canDelete === false}
              className="w-full"
            >
              <Trash2 className="h-4 w-4 mr-2" />
              Delete Organization
            </Button>
          </CardContent>
        </Card>
      )}
      
      {/* Account Deletion */}
      <Card className="border-danger-border">
        <CardHeader>
          <CardTitle className="text-danger-fg flex items-center gap-2">
            <AlertTriangle className="h-5 w-5" />
            Delete Account
          </CardTitle>
          <CardDescription>
            Permanently delete your personal account. You will be removed from all organizations.
          </CardDescription>
        </CardHeader>
        <CardContent className="space-y-4">
          <div className="rounded-lg bg-danger-bg border border-danger-border p-4">
            <h4 className="text-sm font-semibold text-danger-fg mb-2">
              What will happen:
            </h4>
            <ul className="text-sm text-danger-fg/90 space-y-1 list-disc list-inside">
              <li>Your account will be permanently deleted</li>
              <li>You will be removed from all organizations</li>
              <li>All your personal data will be erased</li>
              <li>Any auth tokens you created will be revoked</li>
            </ul>
          </div>
          
          {!accountValidation?.canDelete && accountValidation?.organizationsAsLastOwner && accountValidation.organizationsAsLastOwner.length > 0 && (
            <div className="rounded-lg bg-warning-bg border border-warning-border p-3">
              <p className="text-sm text-warning-fg mb-2">
                <AlertCircle className="h-4 w-4 inline mr-1" />
                You cannot delete your account because you are the last owner of:
              </p>
              <ul className="text-sm text-warning-fg/90 list-disc list-inside ml-4">
                {accountValidation.organizationsAsLastOwner.map((org: string) => (
                  <li key={org}>{org}</li>
                ))}
              </ul>
              <p className="text-xs text-warning-fg mt-2">
                Please delete these organizations or transfer ownership before deleting your account.
              </p>
            </div>
          )}
          
          <Button
            variant="destructive"
            onClick={() => setDeleteAccountOpen(true)}
            disabled={accountValidation?.canDelete === false}
            className="w-full"
          >
            <Trash2 className="h-4 w-4 mr-2" />
            Delete My Account
          </Button>
        </CardContent>
      </Card>
      
      {/* Delete Organization Dialog */}
      <Dialog open={deleteOrgOpen} onOpenChange={setDeleteOrgOpen}>
        <DialogContent>
          <DialogHeader>
            <DialogTitle className="text-danger-fg">Delete Organization</DialogTitle>
            <DialogDescription>
              This action cannot be undone. All data associated with <strong>{orgData?.name}</strong> will be permanently deleted.
            </DialogDescription>
          </DialogHeader>
          <div className="space-y-4 py-4">
            <div className="space-y-2">
              <Label htmlFor="org-confirmation">
                Type <strong>{orgData?.name}</strong> to confirm
              </Label>
              <Input
                id="org-confirmation"
                value={orgConfirmation}
                onChange={(e) => setOrgConfirmation(e.target.value)}
                placeholder="Organization name"
                className="font-mono"
              />
            </div>
          </div>
          <DialogFooter>
            <Button
              variant="outline"
              onClick={() => {
                setDeleteOrgOpen(false)
                setOrgConfirmation('')
              }}
            >
              Cancel
            </Button>
            <Button
              variant="destructive"
              onClick={() => deleteOrgMutation.mutate()}
              disabled={orgConfirmation !== orgData?.name || deleteOrgMutation.isPending}
            >
              {deleteOrgMutation.isPending ? (
                <>
                  <Loader2 className="h-4 w-4 mr-2 animate-spin" />
                  Deleting...
                </>
              ) : (
                <>
                  <Trash2 className="h-4 w-4 mr-2" />
                  Delete Organization
                </>
              )}
            </Button>
          </DialogFooter>
        </DialogContent>
      </Dialog>
      
      {/* Delete Account Dialog */}
      <Dialog open={deleteAccountOpen} onOpenChange={setDeleteAccountOpen}>
        <DialogContent>
          <DialogHeader>
            <DialogTitle className="text-danger-fg">Delete Account</DialogTitle>
            <DialogDescription>
              This action cannot be undone. Your account and all personal data will be permanently deleted.
            </DialogDescription>
          </DialogHeader>
          <div className="space-y-4 py-4">
            <div className="space-y-2">
              <Label htmlFor="account-confirmation">
                Type <strong>{user?.email}</strong> to confirm
              </Label>
              <Input
                id="account-confirmation"
                value={accountConfirmation}
                onChange={(e) => setAccountConfirmation(e.target.value)}
                placeholder="Your email address"
                className="font-mono"
              />
            </div>
          </div>
          <DialogFooter>
            <Button
              variant="outline"
              onClick={() => {
                setDeleteAccountOpen(false)
                setAccountConfirmation('')
              }}
            >
              Cancel
            </Button>
            <Button
              variant="destructive"
              onClick={() => deleteAccountMutation.mutate()}
              disabled={!isAccountConfirmationValid || deleteAccountMutation.isPending}
            >
              {deleteAccountMutation.isPending ? (
                <>
                  <Loader2 className="h-4 w-4 mr-2 animate-spin" />
                  Deleting...
                </>
              ) : (
                <>
                  <Trash2 className="h-4 w-4 mr-2" />
                  Delete Account
                </>
              )}
            </Button>
          </DialogFooter>
        </DialogContent>
      </Dialog>
      </div>
    </section>
  )
}
