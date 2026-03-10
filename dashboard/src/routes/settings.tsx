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
import {type AlertNotificationPreference, type AlertSource, api, type AuthToken} from '@/lib/api'
import {LogApiKeysTab} from '@/components/settings/LogApiKeysTab'
import {AgentApiKeysTab} from '@/components/settings/AgentApiKeysTab'
import {trackEvent} from '@/lib/analytics'
import {buildPricingCardModel} from '@/lib/pricing-display'
import {Button} from '@/components/ui/button'
import {Input} from '@/components/ui/input'
import {Label} from '@/components/ui/label'
import {Card, CardContent, CardDescription, CardHeader, CardTitle} from '@/components/ui/card'
import {Tabs, TabsContent, TabsList, TabsTrigger} from '@/components/ui/tabs'
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
import {Select, SelectContent, SelectItem, SelectTrigger, SelectValue} from '@/components/ui/select'
import {Switch} from '@/components/ui/switch'
import {useToast} from '@/hooks/useToast'
import {
  Activity,
  AlertCircle,
  AlertTriangle,
  Bell,
  BellOff,
  Calendar,
  Check,
  CheckCircle2,
  Clock,
  Copy,
  CreditCard,
  Database,
  Download,
  Info,
  Key,
  Layers,
  LayoutDashboard,
  Loader2,
  Minus,
  Phone,
  Plug,
  Plus,
  Receipt,
  Server,
  Settings,
  Shield,
  SlidersHorizontal,
  Trash2,
  TrendingUp,
  Users,
  Zap,
} from 'lucide-react'
import {SsoTab} from '@/components/SsoSettings'
import {TeamSettings} from '@/components/settings/TeamSettings'
import {LogIndexesTab} from '@/components/settings/LogIndexesTab'
import {useAuth} from '@/hooks/useAuth'
import {useEnterpriseFeatures, useIsSelfHosted, hasEnterpriseModule} from '@/hooks/useEnterpriseFeatures'
import {CONFIGURABLE_SIDEBAR_ITEMS, getAllSidebarItemKeys} from '@/lib/sidebar-config'
import {useTimezone} from '@/hooks/useTimezone'
import {TIMEZONES} from '@/lib/timezones'
import {formatDate as formatDateUtil, formatDateTime as formatDateTimeUtil} from '@/lib/date-format'

const AUTH_TOKEN_SCOPES = [
  { group: 'Project', scopes: ['project:read', 'project:write'] },
  { group: 'Releases', scopes: ['releases:read', 'releases:write'] },
  { group: 'Source Maps', scopes: ['sourcemaps:read', 'sourcemaps:write'] },
  { group: 'Events', scopes: ['event:read'] },
  { group: 'Organization', scopes: ['org:read'] },
] as const

const SCOPE_DESCRIPTIONS: Record<string, string> = {
  'project:read': 'List and view project details.',
  'project:write': 'Create, update, and delete projects.',
  'releases:read': 'List releases and view release metadata.',
  'releases:write': 'Create and manage releases (e.g. for version tracking).',
  'sourcemaps:read': 'List and download source map files.',
  'sourcemaps:write': 'Upload source maps and symbol files for symbolication.',
  'event:read': 'Read error and transaction event data.',
  'org:read': 'View organization information.',
}

const EXPIRATION_OPTIONS = [
  { label: 'No expiration', value: 'none' },
  { label: '7 days', value: '7' },
  { label: '30 days', value: '30' },
  { label: '60 days', value: '60' },
  { label: '90 days', value: '90' },
  { label: 'Custom', value: 'custom' },
] as const

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

export const Route = createFileRoute('/settings')({
  validateSearch: (search: Record<string, unknown>) => {
    return {
      tab: (search.tab as string) || 'api-keys',
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
  
  // SSO available if: (self-hosted with enterprise SSO/OnCall module) OR (SaaS with Team/Business tier)
  const { data: features } = useEnterpriseFeatures()
  const hasSsoModule = hasEnterpriseModule(features, 'oncall') || hasEnterpriseModule(features, 'sso')
  const canUseSso = (isSelfHosted && hasSsoModule) || (!isSelfHosted && (tier === 'TEAM' || tier === 'BUSINESS'))
  const canManageTeam = user?.orgRole === 'admin' || user?.orgRole === 'owner'
  
  return (
    <div>
      <div className="container mx-auto px-4 py-6">
        <h1 className="text-2xl font-bold mb-6 flex items-center gap-2">
          <Settings className="h-6 w-6 text-muted-foreground" />
          Settings
        </h1>
        <Tabs 
          value={search.tab || 'api-keys'} 
          onValueChange={(tab) => navigate({ search: { tab } })}
          orientation="vertical"
          className="flex flex-col md:flex-row gap-8 items-start"
        >
          <aside className="w-full md:w-64 flex-shrink-0">
            <TabsList className="flex flex-col h-auto bg-transparent p-0 gap-1 items-stretch">
              <div className="px-3 py-2 text-xs font-semibold text-muted-foreground uppercase tracking-wider mb-1">
                General
              </div>
              <TabsTrigger 
                value="general" 
                className="w-full justify-start px-3 py-2 h-9 text-sm font-medium rounded-md hover:bg-muted/50 data-[state=active]:bg-muted data-[state=active]:text-foreground data-[state=active]:shadow-none"
              >
                <SlidersHorizontal className="h-4 w-4 mr-2" />
                General
              </TabsTrigger>
              <TabsTrigger 
                value="api-keys" 
                className="w-full justify-start px-3 py-2 h-9 text-sm font-medium rounded-md hover:bg-muted/50 data-[state=active]:bg-muted data-[state=active]:text-foreground data-[state=active]:shadow-none"
              >
                <Key className="h-4 w-4 mr-2" />
                API Keys
              </TabsTrigger>

              <div className="px-3 py-2 text-xs font-semibold text-muted-foreground uppercase tracking-wider mb-1 mt-4">
                Monitoring
              </div>
              <TabsTrigger 
                value="integrations" 
                className="w-full justify-start px-3 py-2 h-9 text-sm font-medium rounded-md hover:bg-muted/50 data-[state=active]:bg-muted data-[state=active]:text-foreground data-[state=active]:shadow-none"
              >
                <Plug className="h-4 w-4 mr-2" />
                Integrations
              </TabsTrigger>
              <TabsTrigger 
                value="notifications" 
                className="w-full justify-start px-3 py-2 h-9 text-sm font-medium rounded-md hover:bg-muted/50 data-[state=active]:bg-muted data-[state=active]:text-foreground data-[state=active]:shadow-none"
              >
                <Bell className="h-4 w-4 mr-2" />
                Notifications
              </TabsTrigger>
              <TabsTrigger 
                value="silence" 
                className="w-full justify-start px-3 py-2 h-9 text-sm font-medium rounded-md hover:bg-muted/50 data-[state=active]:bg-muted data-[state=active]:text-foreground data-[state=active]:shadow-none"
              >
                <BellOff className="h-4 w-4 mr-2" />
                Silence Periods
              </TabsTrigger>
              <TabsTrigger 
                value="log-indexes" 
                className="w-full justify-start px-3 py-2 h-9 text-sm font-medium rounded-md hover:bg-muted/50 data-[state=active]:bg-muted data-[state=active]:text-foreground data-[state=active]:shadow-none"
              >
                <Database className="h-4 w-4 mr-2" />
                Log Indexes
              </TabsTrigger>
              {(canManageTeam || canUseSso) && (
                <div className="px-3 py-2 text-xs font-semibold text-muted-foreground uppercase tracking-wider mb-1 mt-4">
                  Organization
                </div>
              )}
              {canManageTeam && (
                <TabsTrigger 
                  value="team" 
                  className="w-full justify-start px-3 py-2 h-9 text-sm font-medium rounded-md hover:bg-muted/50 data-[state=active]:bg-muted data-[state=active]:text-foreground data-[state=active]:shadow-none"
                >
                  <Users className="h-4 w-4 mr-2" />
                  Team
                </TabsTrigger>
              )}
              {!isSelfHosted && (
              <TabsTrigger 
                value="billing" 
                className="w-full justify-start px-3 py-2 h-9 text-sm font-medium rounded-md hover:bg-muted/50 data-[state=active]:bg-muted data-[state=active]:text-foreground data-[state=active]:shadow-none"
              >
                <CreditCard className="h-4 w-4 mr-2" />
                Billing
              </TabsTrigger>
              )}
              <TabsTrigger 
                value="usage" 
                className="w-full justify-start px-3 py-2 h-9 text-sm font-medium rounded-md hover:bg-muted/50 data-[state=active]:bg-muted data-[state=active]:text-foreground data-[state=active]:shadow-none"
              >
                <Layers className="h-4 w-4 mr-2" />
                Usage
              </TabsTrigger>
              {canUseSso && (
                <TabsTrigger 
                  value="sso" 
                  className="w-full justify-start px-3 py-2 h-9 text-sm font-medium rounded-md hover:bg-muted/50 data-[state=active]:bg-muted data-[state=active]:text-foreground data-[state=active]:shadow-none"
                >
                  <Shield className="h-4 w-4 mr-2" />
                  SSO
                  {isSelfHosted && hasSsoModule && (
                    <Badge variant="secondary" className="ml-2 h-4 px-1.5 text-[10px]">Enterprise</Badge>
                  )}
                </TabsTrigger>
              )}

              <div className="px-3 py-2 text-xs font-semibold text-muted-foreground uppercase tracking-wider mb-1 mt-4">
                User
              </div>
              <TabsTrigger 
                value="account" 
                className="w-full justify-start px-3 py-2 h-9 text-sm font-medium rounded-md hover:bg-muted/50 data-[state=active]:bg-muted data-[state=active]:text-foreground data-[state=active]:shadow-none"
              >
                <Trash2 className="h-4 w-4 mr-2" />
                Account
              </TabsTrigger>
            </TabsList>
          </aside>
          
          <div className="flex-1 w-full min-w-0">
            <TabsContent value="api-keys" className="space-y-4 mt-0">
              <ApiKeysTab />
            </TabsContent>
            <TabsContent value="integrations" className="space-y-4 mt-0">
              <IntegrationsTab />
            </TabsContent>
            <TabsContent value="notifications" className="space-y-4 mt-0">
              <NotificationsTab />
            </TabsContent>
            <TabsContent value="silence" className="space-y-4 mt-0">
              <SilencePeriodsTab />
            </TabsContent>
            <TabsContent value="log-indexes" className="space-y-4 mt-0">
              <LogIndexesTab />
            </TabsContent>
            {canManageTeam && (
              <TabsContent value="team" className="space-y-4 mt-0">
                <TeamSettings />
              </TabsContent>
            )}
            <TabsContent value="general" className="space-y-4 mt-0">
              <GeneralTab />
            </TabsContent>
            {!isSelfHosted && (
            <TabsContent value="billing" className="space-y-4 mt-0">
              <BillingTab />
            </TabsContent>
            )}
            <TabsContent value="usage" className="space-y-4 mt-0">
              <UsageTab />
            </TabsContent>
            {canUseSso && (
              <TabsContent value="sso" className="space-y-4 mt-0">
                <SsoTab />
              </TabsContent>
            )}
            <TabsContent value="account" className="space-y-4 mt-0">
              <AccountTab />
            </TabsContent>
          </div>
        </Tabs>
      </div>
    </div>
  )
}

function ApiKeysTab() {
  const hasDatadog = true // Datadog module is always available (OSS)
  return (
    <Tabs defaultValue="sentry">
      <TabsList className="mb-4">
        <TabsTrigger value="sentry">Sentry</TabsTrigger>
        <TabsTrigger value="datadog" disabled={!hasDatadog}>Datadog</TabsTrigger>
        <TabsTrigger value="logs">Logs</TabsTrigger>
      </TabsList>
      <TabsContent value="sentry" className="space-y-8 mt-0">
        <AuthTokensSection />
      </TabsContent>
      <TabsContent value="datadog" className="space-y-8 mt-0">
        {hasDatadog && <AgentApiKeysTab />}
      </TabsContent>
      <TabsContent value="logs" className="space-y-8 mt-0">
        <LogApiKeysTab />
      </TabsContent>
    </Tabs>
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
    mutationFn: (tokenId: number) => api.deleteAuthToken(tokenId),
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
        <CardHeader className="flex flex-row items-start justify-between space-y-0 gap-4">
          <div>
            <CardTitle className="flex items-center gap-2">
              <Key className="h-5 w-5" />
              Auth Tokens
            </CardTitle>
            <CardDescription>
              For CLI tools and CI/CD: upload source maps, manage releases, read events.
            </CardDescription>
          </div>
          <Button onClick={() => setCreateOpen(true)} disabled={!!createdTokenValue}>
            <Plus className="h-4 w-4 mr-2" />
            New Token
          </Button>
        </CardHeader>
        <CardContent>
          {isLoading ? (
            <p className="text-muted-foreground text-sm py-8">Loading tokens...</p>
          ) : tokens.length === 0 ? (
            <div className="border rounded-lg p-8 text-center text-muted-foreground">
              <Key className="h-10 w-10 mx-auto mb-2 opacity-50" />
              <p className="font-medium">No tokens yet</p>
              <p className="text-sm mt-1">Create a token to use with the API or upload tools.</p>
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
                <AlertTriangle className="h-5 w-5 shrink-0 text-amber-500" />
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
  const { data: usage, isLoading } = useQuery({
    queryKey: ['billingUsage'],
    queryFn: () => api.getBillingUsage(),
    enabled: api.isAuthenticated(),
  })

  if (isLoading || !usage) {
    return <p className="text-sm text-muted-foreground">Loading usage data...</p>
  }

  const periodLabel = `${formatDate(usage.periodStart, timezone)} – ${formatDate(usage.periodEnd, timezone)}`

  const usageRows = [
    {
      key: 'ingestion',
      label: 'Ingestion',
      used: usage.usedBytes ?? 0,
      limit: usage.bytesLimit,
      icon: AlertCircle,
      color: 'text-blue-500',
      bgColor: 'bg-blue-500',
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
      color: 'text-orange-500',
      bgColor: 'bg-orange-500',
      retentionDays: usage.retentionDays,
      overageCents: usage.customMetricOverageCentsEstimate ?? 0,
      overageRate: usage.customMetricOverageRateCentsPer100k
        ? `$${(usage.customMetricOverageRateCentsPer100k / 100).toFixed(2)}/100K`
        : null,
      unit: 'events',
    },
    {
      key: 'apm_span',
      label: 'APM Spans',
      used: usage.usedApmSpans ?? 0,
      limit: usage.apmSpanLimit ?? 0,
      icon: Activity,
      color: 'text-purple-500',
      bgColor: 'bg-purple-500',
      retentionDays: usage.retentionDays,
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
      color: 'text-indigo-500',
      bgColor: 'bg-indigo-500',
      retentionDays: usage.retentionDays,
      overageCents: usage.analyticsPageviewOverageCentsEstimate ?? 0,
      overageRate: usage.analyticsPageviewOverageRateCentsPer100k
        ? `$${(usage.analyticsPageviewOverageRateCentsPer100k / 100).toFixed(2)}/100K`
        : null,
      unit: 'events',
    },
  ] as const

  const ingestionBreakdown = [
    { label: 'Errors', bytes: usage.usedErrorBytes ?? 0, color: 'bg-red-400' },
    { label: 'Replays', bytes: usage.usedReplayBytes ?? 0, color: 'bg-purple-400' },
    { label: 'Logs', bytes: usage.usedLogBytes ?? 0, color: 'bg-emerald-400' },
    { label: 'LLM', bytes: usage.usedLlmBytes ?? 0, color: 'bg-amber-400' },
    { label: 'Profiling', bytes: usage.usedProfilerBytes ?? 0, color: 'bg-pink-400' },
  ]
  const ingestionKnownBytes = ingestionBreakdown.reduce((sum, s) => sum + s.bytes, 0)
  const ingestionOtherBytes = Math.max(0, (usage.usedBytes ?? 0) - ingestionKnownBytes)
  const ingestionSegments = [
    ...ingestionBreakdown,
    { label: 'Other', bytes: ingestionOtherBytes, color: 'bg-blue-300' },
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
    percent >= 100 ? 'bg-red-500' : percent >= 80 ? 'bg-amber-500' : 'bg-emerald-500'

  const totalOverageCents = usage.totalOverageCentsEstimate ?? 0

  const formatCurrency = (cents: number) => `$${(cents / 100).toFixed(2)}`
  const BYTES_PER_GB = 1024 * 1024 * 1024

  // GB conversion: 1 GB = 1,073,741,824 bytes (binary)
  const formatGB = (bytes: number) => (bytes / BYTES_PER_GB).toFixed(2)
  const usedGB = formatGB(usage.usedBytes)
  const limitGB = usage.bytesLimit > 0 ? formatGB(usage.bytesLimit) : null
  const isUnlimitedGB = !usage.bytesLimit || usage.bytesLimit <= 0
  const overageGB = usage.bytesLimit > 0 && usage.usedBytes > usage.bytesLimit

  return (
    <div className="space-y-6">
      <Card>
        <CardHeader>
          <div className="flex items-center justify-between">
            <div className="flex items-center gap-2">
              <div className="p-2 bg-blue-500/10 rounded-full">
                <Layers className="h-5 w-5 text-blue-500" />
              </div>
              <div>
                <CardTitle>Usage & Limits</CardTitle>
                <CardDescription>
                  Per-type usage for this billing period ({periodLabel}).
                </CardDescription>
              </div>
            </div>
            {totalOverageCents > 0 && (
              <div className="flex items-center gap-2 rounded-lg border border-amber-500/30 bg-amber-500/5 px-3 py-2">
                <TrendingUp className="h-4 w-4 text-amber-500" />
                <div className="text-right">
                  <p className="text-xs text-muted-foreground">Est. overage</p>
                  <p className="text-sm font-bold text-amber-600">{formatCurrency(totalOverageCents)}</p>
                </div>
              </div>
            )}
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
                          <div className="h-1.5 w-1.5 rounded-full bg-blue-300" />
                          <span className="text-xs text-muted-foreground">Other · {formatGB(ingestionOtherBytes)} GB</span>
                        </div>
                      )}
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
                      <div className="flex items-center gap-1 text-amber-600 bg-amber-50 dark:bg-amber-500/10 px-2 py-0.5 rounded">
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
              </div>
            )
          })}

          {/* Total data volume */}
          <div className="rounded-lg border border-dashed p-4">
            <div className="flex items-center justify-between">
              <div className="flex items-center gap-2">
                <Database className="h-4 w-4 text-muted-foreground" />
                <span className="text-sm font-medium text-muted-foreground">Total data ingested</span>
              </div>
              <span className="text-sm font-semibold">
                {usedGB} GB
                {limitGB && !isUnlimitedGB && <span className="text-xs text-muted-foreground font-normal"> / {limitGB} GB</span>}
              </span>
            </div>
            {overageGB && (
              <div className="mt-2 flex items-center gap-1.5 text-xs text-amber-600">
                <AlertTriangle className="h-3 w-3" />
                <span>Total data exceeds base GB limit.</span>
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
        <Card className="border-orange-500/20 overflow-hidden relative">
          <div className="absolute top-0 right-0 p-4 opacity-5 pointer-events-none">
            <Phone className="w-32 h-32 text-orange-500" />
          </div>
          <CardHeader>
            <div className="flex items-center gap-2">
              <div className="p-2 bg-orange-500/10 rounded-full">
                <Phone className="h-5 w-5 text-orange-600" />
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
                  <p className="text-xs text-red-500 font-medium flex items-center gap-1">
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
            <div className="p-2 bg-green-500/10 rounded-full">
              <Receipt className="h-5 w-5 text-green-600" />
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
                              <Badge 
                                variant={invoice.status === 'paid' ? 'outline' : 'secondary'}
                                className={invoice.status === 'paid' 
                                  ? 'bg-green-50 text-green-700 border-green-200 hover:bg-green-100 hover:text-green-800' 
                                  : ''}
                              >
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
              <div className="p-2 bg-purple-500/10 rounded-full">
                <Layers className="h-5 w-5 text-purple-600" />
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
                    ? 'bg-background text-foreground shadow-sm'
                    : 'text-muted-foreground hover:text-foreground'
                }`}
              >
                Monthly
              </button>
              <button
                onClick={() => setBillingInterval('yearly')}
                className={`relative rounded-md px-3 py-1.5 text-xs font-medium transition-all ${
                  billingInterval === 'yearly'
                    ? 'bg-background text-foreground shadow-sm'
                    : 'text-muted-foreground hover:text-foreground'
                }`}
              >
                Yearly
                <span className="ml-1.5 text-[10px] text-sky-600 dark:text-sky-400 font-bold">
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
                        ? 'border-primary bg-primary/5 shadow-md relative'
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
                              <CheckCircle2 className={`h-4 w-4 flex-shrink-0 mt-0.5 ${isCurrentPlan ? 'text-primary' : 'text-emerald-500'}`} />
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
                                <CheckCircle2 className={`h-4 w-4 flex-shrink-0 mt-0.5 ${isCurrentPlan ? 'text-primary' : 'text-emerald-500'}`} />
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

const SlackLogo = ({ className }: { className?: string }) => (
  <svg xmlns="http://www.w3.org/2000/svg" viewBox="0 0 24 24" className={className}>
    <path fill="#E01E5A" d="M5.042 15.165a2.528 2.528 0 0 1-2.52 2.523A2.52 2.52 0 0 1 0 15.165a2.527 2.527 0 0 1 2.522-2.52h2.52v2.52zM6.313 15.165a2.527 2.527 0 0 1 2.521-2.52 2.522 2.522 0 0 1 2.521 2.52v6.313A2.52 2.52 0 0 1 8.834 24a2.528 2.528 0 0 1-2.521-2.522v-6.313z"/>
    <path fill="#36C5F0" d="M8.834 5.042a2.528 2.528 0 0 1-2.521-2.52A2.52 2.52 0 0 1 8.834 0a2.528 2.528 0 0 1 2.521 2.522v2.52h-2.521zM8.834 6.313a2.528 2.528 0 0 1 2.521 2.521 2.522 2.522 0 0 1-2.521 2.521H2.522A2.52 2.52 0 0 1 0 8.834a2.528 2.528 0 0 1 2.522-2.521h6.312z"/>
    <path fill="#2EB67D" d="M18.956 8.834a2.528 2.528 0 0 1 2.522-2.521A2.52 2.52 0 0 1 24 8.834a2.528 2.528 0 0 1-2.522 2.521h-2.522V8.834zM17.688 8.834a2.528 2.528 0 0 1-2.523 2.521 2.522 2.522 0 0 1-2.52-2.521V2.522A2.52 2.52 0 0 1 15.165 0a2.528 2.528 0 0 1 2.523 2.522v6.312z"/>
    <path fill="#ECB22E" d="M15.165 18.956a2.528 2.528 0 0 1 2.523 2.522A2.52 2.52 0 0 1 15.165 24a2.527 2.527 0 0 1-2.52-2.522v-2.522h2.52zM15.165 17.688a2.527 2.527 0 0 1-2.52-2.523 2.52 2.52 0 0 1 2.52-2.52h6.313A2.52 2.52 0 0 1 24 15.165a2.528 2.528 0 0 1-2.522 2.523h-6.313z"/>
  </svg>
)

const DiscordLogo = ({ className }: { className?: string }) => (
  <svg xmlns="http://www.w3.org/2000/svg" viewBox="0 0 24 24" className={className} fill="#5865F2">
    <path d="M20.317 4.37a19.791 19.791 0 0 0-4.885-1.515a.074.074 0 0 0-.079.037c-.21.375-.444.864-.608 1.25a18.27 18.27 0 0 0-5.487 0a12.64 12.64 0 0 0-.617-1.25a.077.077 0 0 0-.079-.037A19.736 19.736 0 0 0 3.677 4.37a.07.07 0 0 0-.032.027C.533 9.046-.32 13.58.099 18.057a.082.082 0 0 0 .031.057a19.9 19.9 0 0 0 5.993 3.03a.078.078 0 0 0 .084-.028a14.09 14.09 0 0 0 1.226-1.994a.076.076 0 0 0-.041-.106a13.107 13.107 0 0 1-1.872-.892a.077.077 0 0 1-.008-.128a10.2 10.2 0 0 0 .372-.292a.074.074 0 0 1 .077-.01c3.928 1.793 8.18 1.793 12.062 0a.074.074 0 0 1 .078.01c.12.098.246.198.373.292a.077.077 0 0 1-.006.127a12.299 12.299 0 0 1-1.873.892a.077.077 0 0 0-.041.107c.36.698.772 1.362 1.225 1.993a.076.076 0 0 0 .084.028a19.839 19.839 0 0 0 6.002-3.03a.077.077 0 0 0 .032-.054c.5-5.177-.838-9.674-3.549-13.66a.061.061 0 0 0-.031-.03zM8.02 15.33c-1.183 0-2.157-1.085-2.157-2.419c0-1.333.956-2.419 2.157-2.419c1.21 0 2.176 1.096 2.157 2.42c0 1.333-.956 2.418-2.157 2.418zm7.975 0c-1.183 0-2.157-1.085-2.157-2.419c0-1.333.955-2.419 2.157-2.419c1.21 0 2.176 1.096 2.157 2.42c0 1.333-.946 2.418-2.157 2.418z"/>
  </svg>
)

function IntegrationsTab() {
  const queryClient = useQueryClient()
  const { toast } = useToast()
  const [showDeleteDialog, setShowDeleteDialog] = useState(false)
  const [showDiscordDeleteDialog, setShowDiscordDeleteDialog] = useState(false)

  const { data: integrations = [], isLoading } = useQuery({
    queryKey: ['integrations'],
    queryFn: () => api.getIntegrations(),
    enabled: api.isAuthenticated(),
  })

  const slackIntegration = integrations.find(i => i.integrationType === 'slack')
  const discordIntegration = integrations.find(i => i.integrationType === 'discord')

  const { data: channelsData, isLoading: channelsLoading } = useQuery({
    queryKey: ['slackChannels'],
    queryFn: () => api.getSlackChannels(),
    enabled: !!slackIntegration?.isConfigured,
  })

  const { data: discordChannelsData, isLoading: discordChannelsLoading } = useQuery({
    queryKey: ['discordChannels'],
    queryFn: () => api.getDiscordChannels(),
    enabled: !!discordIntegration?.isConfigured,
  })

  const oauthMutation = useMutation({
    mutationFn: () => api.startSlackOAuth(),
    onSuccess: (data) => {
      trackEvent('Integration Connect', { provider: 'slack' })
      window.location.href = data.authUrl
    },
    onError: (err: Error) => {
      toast({
        title: 'Failed to start Slack OAuth',
        description: err.message,
        variant: 'destructive',
      })
    },
  })

  const updateChannelMutation = useMutation({
    mutationFn: ({ channelId, channelName }: { channelId: string, channelName: string }) => 
      api.updateSlackChannel(channelId, channelName),
    onSuccess: () => {
      queryClient.invalidateQueries({ queryKey: ['integrations'] })
      toast({ title: 'Slack channel updated' })
    },
    onError: (err: Error) => {
      toast({
        title: 'Failed to update channel',
        description: err.message,
        variant: 'destructive',
      })
    },
  })

  const toggleMutation = useMutation({
    mutationFn: () => api.toggleSlackIntegration(),
    onSuccess: () => {
      queryClient.invalidateQueries({ queryKey: ['integrations'] })
      toast({ title: 'Slack integration updated' })
    },
    onError: (err: Error) => {
      toast({
        title: 'Failed to update integration',
        description: err.message,
        variant: 'destructive',
      })
    },
  })

  const deleteMutation = useMutation({
    mutationFn: () => api.deleteSlackIntegration(),
    onSuccess: () => {
      queryClient.invalidateQueries({ queryKey: ['integrations'] })
      setShowDeleteDialog(false)
      toast({ title: 'Slack integration removed' })
    },
    onError: (err: Error) => {
      toast({
        title: 'Failed to remove integration',
        description: err.message,
        variant: 'destructive',
      })
    },
  })

  const testMutation = useMutation({
    mutationFn: () => api.testSlackIntegration(),
    onSuccess: (response) => {
      toast({
        title: response.success ? 'Test successful' : 'Test failed',
        description: response.message,
        variant: response.success ? 'default' : 'destructive',
      })
    },
    onError: (err: Error) => {
      toast({
        title: 'Test failed',
        description: err.message,
        variant: 'destructive',
      })
    },
  })

  // Discord mutations
  const discordOauthMutation = useMutation({
    mutationFn: () => api.startDiscordOAuth(),
    onSuccess: (data) => {
      trackEvent('Integration Connect', { provider: 'discord' })
      window.location.href = data.authUrl
    },
    onError: (err: Error) => {
      toast({
        title: 'Failed to start Discord OAuth',
        description: err.message,
        variant: 'destructive',
      })
    },
  })

  const discordUpdateChannelMutation = useMutation({
    mutationFn: ({ channelId, channelName }: { channelId: string, channelName: string }) => 
      api.updateDiscordChannel(channelId, channelName),
    onSuccess: () => {
      queryClient.invalidateQueries({ queryKey: ['integrations'] })
      toast({ title: 'Discord channel updated' })
    },
    onError: (err: Error) => {
      toast({
        title: 'Failed to update channel',
        description: err.message,
        variant: 'destructive',
      })
    },
  })

  const discordToggleMutation = useMutation({
    mutationFn: () => api.toggleDiscordIntegration(),
    onSuccess: () => {
      queryClient.invalidateQueries({ queryKey: ['integrations'] })
      toast({ title: 'Discord integration updated' })
    },
    onError: (err: Error) => {
      toast({
        title: 'Failed to update integration',
        description: err.message,
        variant: 'destructive',
      })
    },
  })

  const discordDeleteMutation = useMutation({
    mutationFn: () => api.deleteDiscordIntegration(),
    onSuccess: () => {
      queryClient.invalidateQueries({ queryKey: ['integrations'] })
      setShowDiscordDeleteDialog(false)
      toast({ title: 'Discord integration removed' })
    },
    onError: (err: Error) => {
      toast({
        title: 'Failed to remove integration',
        description: err.message,
        variant: 'destructive',
      })
    },
  })

  const discordTestMutation = useMutation({
    mutationFn: () => api.testDiscordIntegration(),
    onSuccess: (response) => {
      toast({
        title: response.success ? 'Test successful' : 'Test failed',
        description: response.message,
        variant: response.success ? 'default' : 'destructive',
      })
    },
    onError: (err: Error) => {
      toast({
        title: 'Test failed',
        description: err.message,
        variant: 'destructive',
      })
    },
  })

  if (isLoading) {
    return <div className="text-sm text-muted-foreground">Loading integrations...</div>
  }

  return (
    <div className="grid gap-6 md:grid-cols-2">
      <Card className="border-l-4 border-l-[#4A154B] overflow-hidden">
        <CardHeader className="bg-muted/10 pb-4">
          <div className="flex items-center justify-between">
            <div className="flex items-center gap-4">
              <div className="h-12 w-12 rounded-xl bg-white shadow-sm border flex items-center justify-center p-2">
                <SlackLogo className="h-full w-full" />
              </div>
              <div>
                <CardTitle className="text-xl">Slack</CardTitle>
                <CardDescription className="mt-1">
                  Receive real-time alerts and notifications directly in your Slack workspace.
                </CardDescription>
              </div>
            </div>
            {slackIntegration?.isConfigured && (
              <div className="flex items-center gap-2">
                 <Badge variant={slackIntegration.enabled ? 'default' : 'secondary'} className={slackIntegration.enabled ? 'bg-[#4A154B] hover:bg-[#4A154B]/90' : ''}>
                  {slackIntegration.enabled ? 'Active' : 'Disabled'}
                </Badge>
              </div>
            )}
          </div>
        </CardHeader>
        <CardContent className="space-y-6 pt-6">
          {!slackIntegration?.isConfigured ? (
             <div className="flex flex-col items-center justify-center py-8 text-center space-y-4">
                <div className="p-4 bg-muted/30 rounded-full">
                   <SlackLogo className="h-12 w-12 opacity-80" />
                </div>
                <div className="max-w-md space-y-2">
                   <h3 className="font-semibold text-lg">Connect your Slack Workspace</h3>
                   <p className="text-muted-foreground text-sm">
                      Install the Moneat app to your Slack workspace to start receiving critical alerts and notifications where your team works.
                   </p>
                </div>
                <Button 
                   size="lg" 
                   variant="outline"
                   className="border-[#4A154B] text-[#4A154B] hover:bg-[#4A154B]/5 mt-4 font-semibold"
                   onClick={() => oauthMutation.mutate()}
                   disabled={oauthMutation.isPending}
                >
                   {oauthMutation.isPending ? (
                      <>
                        <Loader2 className="h-4 w-4 mr-2 animate-spin" />
                        Connecting...
                      </>
                   ) : (
                      <>
                        <SlackLogo className="h-4 w-4 mr-2" />
                        Add to Slack
                      </>
                   )}
                </Button>
             </div>
          ) : (
             <div className="space-y-6">
                <div className="flex items-center justify-between p-4 border rounded-lg bg-card">
                   <div className="space-y-1">
                      <p className="font-medium">Connected Workspace</p>
                      <p className="text-sm text-muted-foreground">
                         Connected to <strong>{slackIntegration.teamName || 'Slack'}</strong>
                      </p>
                   </div>
                   <Button
                      variant="outline"
                      size="sm"
                      onClick={() => setShowDeleteDialog(true)}
                   >
                      Disconnect
                   </Button>
                </div>

                <div className="grid gap-6 md:grid-cols-2">
                   <div className="space-y-2">
                      <Label>Notification Channel</Label>
                      <Select 
                         value={slackIntegration.channelId || ''} 
                         onValueChange={(val) => {
                            const channel = channelsData?.channels.find(c => c.id === val)
                            if (channel) {
                               updateChannelMutation.mutate({ 
                                  channelId: channel.id, 
                                  channelName: channel.name 
                               })
                            }
                         }}
                         disabled={channelsLoading || updateChannelMutation.isPending}
                      >
                         <SelectTrigger>
                            <SelectValue placeholder="Select a channel" />
                         </SelectTrigger>
                         <SelectContent>
                            {channelsLoading ? (
                               <div className="p-2 text-center text-xs text-muted-foreground">Loading channels...</div>
                            ) : (
                               channelsData?.channels.map(channel => (
                                  <SelectItem key={channel.id} value={channel.id}>
                                     #{channel.name}
                                  </SelectItem>
                               ))
                            )}
                         </SelectContent>
                      </Select>
                      <p className="text-xs text-muted-foreground">
                         Select the channel where Moneat should post alerts.
                      </p>
                   </div>

                   <div className="space-y-2">
                      <Label className="block">Status</Label>
                      <div className="flex items-center space-x-2 border rounded-md p-2.5 bg-muted/10 h-10">
                          <Checkbox
                            id="slack-enabled"
                            checked={slackIntegration.enabled}
                            onCheckedChange={() => toggleMutation.mutate()}
                            disabled={toggleMutation.isPending}
                          />
                          <Label htmlFor="slack-enabled" className="font-normal cursor-pointer">
                              Enable Slack notifications
                          </Label>
                      </div>
                   </div>
                </div>

                <div className="flex items-center gap-2 pt-4 border-t">
                   <Button
                     variant="outline"
                     onClick={() => testMutation.mutate()}
                     disabled={testMutation.isPending}
                   >
                     {testMutation.isPending ? (
                       <>
                         <Loader2 className="h-4 w-4 mr-2 animate-spin" />
                         Testing...
                       </>
                     ) : (
                       'Test Connection'
                     )}
                   </Button>
                </div>
             </div>
          )}
        </CardContent>
      </Card>

      {/* Discord Integration Card */}
      <Card className="border-l-4 border-l-[#5865F2] overflow-hidden">
        <CardHeader className="bg-muted/10 pb-4">
          <div className="flex items-center justify-between">
            <div className="flex items-center gap-4">
              <div className="h-12 w-12 rounded-xl bg-white shadow-sm border flex items-center justify-center p-2">
                <DiscordLogo className="h-full w-full" />
              </div>
              <div>
                <CardTitle className="text-xl">Discord</CardTitle>
                <CardDescription className="mt-1">
                  Receive real-time alerts and notifications in your Discord server.
                </CardDescription>
              </div>
            </div>
            {discordIntegration?.isConfigured && (
              <div className="flex items-center gap-2">
                 <Badge variant={discordIntegration.enabled ? 'default' : 'secondary'} className={discordIntegration.enabled ? 'bg-[#5865F2] hover:bg-[#5865F2]/90' : ''}>
                  {discordIntegration.enabled ? 'Active' : 'Disabled'}
                </Badge>
              </div>
            )}
          </div>
        </CardHeader>
        <CardContent className="space-y-6 pt-6">
          {!discordIntegration?.isConfigured ? (
             <div className="flex flex-col items-center justify-center py-8 text-center space-y-4">
                <div className="p-4 bg-muted/30 rounded-full">
                   <DiscordLogo className="h-12 w-12 opacity-80" />
                </div>
                <div className="max-w-md space-y-2">
                   <h3 className="font-semibold text-lg">Connect your Discord Server</h3>
                   <p className="text-muted-foreground text-sm">
                      Add the Moneat bot to your Discord server to receive critical alerts and notifications.
                   </p>
                </div>
                <Button 
                   size="lg" 
                   variant="outline"
                   className="border-[#5865F2] text-[#5865F2] hover:bg-[#5865F2]/5 mt-4 font-semibold"
                   onClick={() => discordOauthMutation.mutate()}
                   disabled={discordOauthMutation.isPending}
                >
                   {discordOauthMutation.isPending ? (
                      <>
                        <Loader2 className="h-4 w-4 mr-2 animate-spin" />
                        Connecting...
                      </>
                   ) : (
                      <>
                        <DiscordLogo className="h-4 w-4 mr-2" />
                        Add to Discord
                      </>
                   )}
                </Button>
             </div>
          ) : (
             <div className="space-y-6">
                <div className="flex items-center justify-between p-4 border rounded-lg bg-card">
                   <div className="space-y-1">
                      <p className="font-medium">Connected Server</p>
                      <p className="text-sm text-muted-foreground">
                         Connected to <strong>{discordIntegration.teamName || 'Discord'}</strong>
                      </p>
                   </div>
                   <Button
                      variant="outline"
                      size="sm"
                      onClick={() => setShowDiscordDeleteDialog(true)}
                   >
                      Disconnect
                   </Button>
                </div>

                <div className="grid gap-6 md:grid-cols-2">
                   <div className="space-y-2">
                      <Label>Notification Channel</Label>
                      <Select 
                         value={discordIntegration.channelId || ''} 
                         onValueChange={(val) => {
                            const channel = discordChannelsData?.channels.find(c => c.id === val)
                            if (channel) {
                               discordUpdateChannelMutation.mutate({ channelId: val, channelName: channel.name })
                            }
                         }}
                      >
                         <SelectTrigger>
                            <SelectValue placeholder={discordChannelsLoading ? "Loading..." : "Select channel"} />
                         </SelectTrigger>
                         <SelectContent>
                            {discordChannelsData?.channels.map(channel => (
                               <SelectItem key={channel.id} value={channel.id}>
                                  # {channel.name}
                               </SelectItem>
                            ))}
                         </SelectContent>
                      </Select>
                      <p className="text-xs text-muted-foreground">
                         Choose which channel receives Moneat notifications
                      </p>
                   </div>
                   
                   <div className="space-y-2">
                      <Label>Status</Label>
                      <div className="flex items-center gap-2 h-10">
                         <Switch 
                            checked={discordIntegration.enabled}
                            onCheckedChange={() => discordToggleMutation.mutate()}
                         />
                         <span className="text-sm">
                            {discordIntegration.enabled ? 'Enabled' : 'Disabled'}
                         </span>
                      </div>
                      <p className="text-xs text-muted-foreground">
                         Enable or disable Discord notifications
                      </p>
                   </div>
                </div>

                <div className="flex items-center gap-3">
                   <Button
                      variant="outline"
                      size="sm"
                      onClick={() => discordTestMutation.mutate()}
                      disabled={discordTestMutation.isPending || !discordIntegration.enabled}
                      className="border-[#5865F2] text-[#5865F2] hover:bg-[#5865F2]/5"
                   >
                      {discordTestMutation.isPending ? (
                        <>
                          <Loader2 className="h-3 w-3 mr-2 animate-spin" />
                          Testing...
                        </>
                      ) : (
                        'Test Connection'
                      )}
                   </Button>
                </div>
             </div>
          )}
        </CardContent>
      </Card>

      {/* Delete confirmation dialog */}
      <Dialog open={showDeleteDialog} onOpenChange={setShowDeleteDialog}>
        <DialogContent>
          <DialogHeader>
            <DialogTitle>Remove Slack integration?</DialogTitle>
            <DialogDescription>
              This will disconnect your Slack workspace and stop sending notifications.
              You can reconnect at any time.
            </DialogDescription>
          </DialogHeader>
          <DialogFooter>
            <Button variant="outline" onClick={() => setShowDeleteDialog(false)}>
              Cancel
            </Button>
            <Button
              variant="destructive"
              onClick={() => deleteMutation.mutate()}
              disabled={deleteMutation.isPending}
            >
              {deleteMutation.isPending ? 'Disconnecting...' : 'Disconnect'}
            </Button>
          </DialogFooter>
        </DialogContent>
      </Dialog>

      {/* Discord Delete confirmation dialog */}
      <Dialog open={showDiscordDeleteDialog} onOpenChange={setShowDiscordDeleteDialog}>
        <DialogContent>
          <DialogHeader>
            <DialogTitle>Remove Discord integration?</DialogTitle>
            <DialogDescription>
              This will disconnect your Discord server and stop sending notifications.
              You can reconnect at any time.
            </DialogDescription>
          </DialogHeader>
          <DialogFooter>
            <Button variant="outline" onClick={() => setShowDiscordDeleteDialog(false)}>
              Cancel
            </Button>
            <Button
              variant="destructive"
              onClick={() => discordDeleteMutation.mutate()}
              disabled={discordDeleteMutation.isPending}
            >
              {discordDeleteMutation.isPending ? 'Disconnecting...' : 'Disconnect'}
            </Button>
          </DialogFooter>
        </DialogContent>
      </Dialog>
    </div>
  )
}

function NotificationsTab() {
  const queryClient = useQueryClient()
  const { toast } = useToast()
  const { timezone } = useTimezone()

  const { data: alertPrefs, isLoading: isLoadingAlertPrefs } = useQuery({
    queryKey: ['alertNotificationPreferences'],
    queryFn: () => api.getAlertNotificationPreferences(),
    enabled: api.isAuthenticated(),
  })

  const { data: preferences, isLoading: isLoadingPrefs } = useQuery({
    queryKey: ['notificationPreferences'],
    queryFn: () => api.getNotificationPreferences(),
    enabled: api.isAuthenticated(),
  })

  const { data: integrations = [] } = useQuery({
    queryKey: ['integrations'],
    queryFn: () => api.getIntegrations(),
    enabled: api.isAuthenticated(),
  })

  const slackConfigured = useMemo(() => integrations.some(i => i.integrationType === 'slack' && i.enabled), [integrations])
  const discordConfigured = useMemo(() => integrations.some(i => i.integrationType === 'discord' && i.enabled), [integrations])

  const updateAlertPrefMutation = useMutation({
    mutationFn: ({
      source,
      prefs,
    }: {
      source: AlertSource
      prefs: Pick<AlertNotificationPreference, 'emailEnabled' | 'slackEnabled' | 'discordEnabled'>
    }) =>
      api.updateAlertNotificationPreference(source, prefs),
    onSuccess: () => {
      queryClient.invalidateQueries({ queryKey: ['alertNotificationPreferences'] })
      toast({ title: 'Preferences updated' })
    },
    onError: (err: Error) => {
      toast({
        title: 'Failed to update preferences',
        description: err.message,
        variant: 'destructive',
      })
    },
  })

  const updateGlobalMutation = useMutation({
    mutationFn: (prefs: Partial<{
      weeklySummary: boolean
      alertFrequencyMinutes: number
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

  const updateProjectMutation = useMutation({
    mutationFn: ({ projectId, prefs }: {
      projectId: number
      prefs: Partial<{
        issueAlerts: boolean
        errorAlerts: boolean
        weeklySummary: boolean
        alertFrequencyMinutes: number
      }>
    }) => api.updateProjectNotificationPreferences(projectId, prefs),
    onSuccess: () => {
      queryClient.invalidateQueries({ queryKey: ['notificationPreferences'] })
      toast({ title: 'Project preferences updated' })
    },
    onError: (err: Error) => {
      toast({
        title: 'Failed to update project preferences',
        description: err.message,
        variant: 'destructive',
      })
    },
  })

  const deleteProjectMutation = useMutation({
    mutationFn: (projectId: number) => api.deleteProjectNotificationPreferences(projectId),
    onSuccess: () => {
      queryClient.invalidateQueries({ queryKey: ['notificationPreferences'] })
      toast({ title: 'Project override removed', description: 'Using global preferences for this project.' })
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

  if (isLoadingAlertPrefs || isLoadingPrefs) {
    return (
      <div className="flex items-center justify-center py-12">
        <Loader2 className="h-8 w-8 animate-spin text-muted-foreground" />
      </div>
    )
  }

  const global = preferences?.global || {
    weeklySummary: true,
    alertFrequencyMinutes: 30,
  }

  const projects = preferences?.projects || []

  const getSourceLabel = (source: AlertSource) => {
    switch (source) {
      case 'HOST_ALERT':
        return { 
          label: 'Host Alerts', 
          desc: 'Metric threshold breaches (CPU, memory, disk)', 
          icon: Zap,
          color: 'text-yellow-600',
          bgColor: 'bg-yellow-500/10'
        }
      case 'HOST_DOWN':
        return { 
          label: 'Host Down', 
          desc: 'Server stops reporting', 
          icon: Server,
          color: 'text-red-600',
          bgColor: 'bg-red-500/10'
        }
      case 'UPTIME_MONITOR':
        return { 
          label: 'Uptime Monitors', 
          desc: 'Website or service goes down', 
          icon: Activity,
          color: 'text-green-600',
          bgColor: 'bg-green-500/10'
        }
      case 'ERROR_ALERT':
        return { 
          label: 'Error Alerts', 
          desc: 'New errors and exceptions in your projects', 
          icon: Shield,
          color: 'text-blue-600',
          bgColor: 'bg-blue-500/10'
        }
      case 'DASHBOARD_ALERT':
        return { 
          label: 'Dashboard Alerts', 
          desc: 'Widget threshold alerts on custom dashboards', 
          icon: LayoutDashboard,
          color: 'text-purple-600',
          bgColor: 'bg-purple-500/10'
        }
    }
  }

  const renderRow = (source: AlertSource) => {
    // If not found, default to all enabled (safe default)
    const pref = alertPrefs?.find((p) => p.alertSource === source) || {
      emailEnabled: true,
      slackEnabled: true,
      discordEnabled: true,
    }
    const info = getSourceLabel(source)
    const Icon = info.icon

    return (
      <div className="flex items-center justify-between py-4 border-b last:border-0" key={source}>
        <div className="flex items-start gap-3">
          <div className={`mt-1 p-2 rounded-full hidden sm:block ${info.bgColor}`}>
            <Icon className={`h-4 w-4 ${info.color}`} />
          </div>
          <div>
            <p className="font-medium">{info.label}</p>
            <p className="text-sm text-muted-foreground max-w-xs sm:max-w-none">{info.desc}</p>
          </div>
        </div>
        <div className="flex items-center gap-4 sm:gap-8 mr-2 sm:mr-4">
          <div className="flex flex-col items-center gap-2 w-[50px]">
            <Switch
              checked={pref.emailEnabled}
              onCheckedChange={(c) =>
                updateAlertPrefMutation.mutate({
                  source,
                  prefs: {
                    emailEnabled: c,
                    slackEnabled: pref.slackEnabled,
                    discordEnabled: pref.discordEnabled,
                  },
                })
              }
              disabled={updateAlertPrefMutation.isPending}
            />
          </div>
          <div className="flex flex-col items-center gap-2 w-[50px]">
            <Switch
              checked={pref.slackEnabled}
              disabled={!slackConfigured || updateAlertPrefMutation.isPending}
              onCheckedChange={(c) =>
                updateAlertPrefMutation.mutate({
                  source,
                  prefs: {
                    emailEnabled: pref.emailEnabled,
                    slackEnabled: c,
                    discordEnabled: pref.discordEnabled,
                  },
                })
              }
            />
          </div>
          <div className="flex flex-col items-center gap-2 w-[50px]">
            <Switch
              checked={pref.discordEnabled}
              disabled={!discordConfigured || updateAlertPrefMutation.isPending}
              onCheckedChange={(c) =>
                updateAlertPrefMutation.mutate({
                  source,
                  prefs: {
                    emailEnabled: pref.emailEnabled,
                    slackEnabled: pref.slackEnabled,
                    discordEnabled: c,
                  },
                })
              }
            />
          </div>
        </div>
      </div>
    )
  }

  return (
    <div className="space-y-6">
      <Card>
        <CardHeader>
          <div className="flex items-center gap-2">
            <div className="p-2 bg-amber-500/10 rounded-full">
              <Bell className="h-5 w-5 text-amber-600" />
            </div>
            <div>
              <CardTitle>Notification Channels</CardTitle>
              <CardDescription>
                Configure how you want to be notified for different types of alerts.
              </CardDescription>
            </div>
          </div>
        </CardHeader>
        <CardContent>
          <div className="grid grid-cols-[1fr,auto] gap-4 mb-2 border-b pb-2">
            <div className="font-medium text-sm text-muted-foreground uppercase tracking-wider pl-2">Alert Source</div>
            <div className="flex items-center gap-4 sm:gap-8 mr-2 sm:mr-4">
              <div className="w-[50px] text-center font-medium text-sm text-muted-foreground">Email</div>
              <div className="w-[50px] text-center font-medium text-sm text-muted-foreground">Slack</div>
              <div className="w-[50px] text-center font-medium text-sm text-muted-foreground">Discord</div>
            </div>
          </div>
          
          {['HOST_ALERT', 'HOST_DOWN', 'UPTIME_MONITOR', 'ERROR_ALERT', 'DASHBOARD_ALERT'].map((s) => renderRow(s as AlertSource))}

          {(!slackConfigured || !discordConfigured) && (
            <div className="mt-6 p-3 bg-muted/50 rounded-md text-sm text-muted-foreground flex items-center gap-2">
              <Info className="h-4 w-4 shrink-0" />
              <span>
                Some channels are disabled because integrations are not configured.{' '}
                <Link to="/settings" search={{ tab: 'integrations' }} className="text-primary hover:underline font-medium">
                  Configure Integrations
                </Link>
              </span>
            </div>
          )}
        </CardContent>
      </Card>

      <Card>
        <CardHeader>
          <div className="flex items-center gap-2">
            <div className="p-2 bg-amber-500/10 rounded-full">
              <Settings className="h-5 w-5 text-amber-600" />
            </div>
            <CardTitle>Additional Settings</CardTitle>
          </div>
        </CardHeader>
        <CardContent className="space-y-6">
          <div className="flex items-center justify-between">
            <div className="space-y-0.5">
              <Label htmlFor="global-weekly-summary" className="text-base">
                Weekly Summary
              </Label>
              <p className="text-sm text-muted-foreground">Receive a weekly summary email every Monday</p>
            </div>
            <Switch
              id="global-weekly-summary"
              checked={global.weeklySummary}
              onCheckedChange={(checked) => updateGlobalMutation.mutate({ weeklySummary: checked })}
            />
          </div>

          <div className="space-y-2">
            <Label htmlFor="global-frequency" className="text-base">
              Error Alert Frequency
            </Label>
            <p className="text-sm text-muted-foreground mb-2">
              Minimum time between alerts for the same project
            </p>
            <Select
              value={global.alertFrequencyMinutes.toString()}
              onValueChange={(value) => updateGlobalMutation.mutate({ alertFrequencyMinutes: parseInt(value) })}
            >
              <SelectTrigger id="global-frequency" className="w-[200px]">
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
          </div>
        </CardContent>
      </Card>

      <Card>
        <CardHeader>
          <div className="flex items-center gap-2">
            <div className="p-2 bg-amber-500/10 rounded-full">
              <Layers className="h-5 w-5 text-amber-600" />
            </div>
            <div>
              <CardTitle>Per-Project Overrides</CardTitle>
              <CardDescription>
                Customize notification settings for specific projects
              </CardDescription>
            </div>
          </div>
        </CardHeader>
        <CardContent>
          {projects.length === 0 ? (
            <p className="text-sm text-muted-foreground">
              No project-specific overrides configured. All projects use the global preferences above.
            </p>
          ) : (
            <Table>
              <TableHeader>
                <TableRow>
                  <TableHead>Project</TableHead>
                  <TableHead className="text-center">Issues</TableHead>
                  <TableHead className="text-center">Errors</TableHead>
                  <TableHead className="text-center">Weekly</TableHead>
                  <TableHead className="text-center">Frequency</TableHead>
                  <TableHead className="w-[100px]"></TableHead>
                </TableRow>
              </TableHeader>
              <TableBody>
                {projects.map((project) => (
                  <TableRow key={project.projectId}>
                    <TableCell className="font-medium">{project.projectName}</TableCell>
                    <TableCell className="text-center">
                      <Checkbox
                        checked={project.issueAlerts}
                        onCheckedChange={(checked) =>
                          updateProjectMutation.mutate({
                            projectId: project.projectId,
                            prefs: { issueAlerts: checked === true },
                          })
                        }
                      />
                    </TableCell>
                    <TableCell className="text-center">
                      <Checkbox
                        checked={project.errorAlerts}
                        onCheckedChange={(checked) =>
                          updateProjectMutation.mutate({
                            projectId: project.projectId,
                            prefs: { errorAlerts: checked === true },
                          })
                        }
                      />
                    </TableCell>
                    <TableCell className="text-center">
                      <Checkbox
                        checked={project.weeklySummary}
                        onCheckedChange={(checked) =>
                          updateProjectMutation.mutate({
                            projectId: project.projectId,
                            prefs: { weeklySummary: checked === true },
                          })
                        }
                      />
                    </TableCell>
                    <TableCell className="text-center text-sm text-muted-foreground">
                      {project.alertFrequencyMinutes >= 60
                        ? `${project.alertFrequencyMinutes / 60}h`
                        : `${project.alertFrequencyMinutes}m`}
                    </TableCell>
                    <TableCell>
                      <Button
                        variant="ghost"
                        size="sm"
                        onClick={() => deleteProjectMutation.mutate(project.projectId)}
                      >
                        Reset
                      </Button>
                    </TableCell>
                  </TableRow>
                ))}
              </TableBody>
            </Table>
          )}
        </CardContent>
      </Card>

      {/* On-Call SMS & Voice Fallback */}
      <Card>
        <CardHeader>
          <CardTitle className="flex items-center gap-2">
            <Phone className="h-5 w-5" />
            On-Call SMS &amp; Voice Fallback
          </CardTitle>
          <CardDescription>
            Receive SMS messages and voice calls when you don't acknowledge a push or Slack on-call alert within the configured delay.
          </CardDescription>
        </CardHeader>
        <CardContent className="space-y-4">
          {isLoadingOnCallContact ? (
            <div className="flex items-center gap-2 text-muted-foreground text-sm">
              <Loader2 className="h-4 w-4 animate-spin" /> Loading…
            </div>
          ) : onCallContact?.onCallPhoneOptIn ? (
            <div className="space-y-3">
              <div className="flex items-center gap-2">
                <CheckCircle2 className="h-4 w-4 text-green-600" />
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
                <div className="flex items-center gap-2 text-amber-600 text-sm">
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
        </CardContent>
      </Card>
    </div>
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
    mutationFn: (id: number) => api.deleteSilencePeriod(id),
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
    <div className="space-y-6">
      {isCurrentlySilenced && (
        <div className="rounded-lg border border-orange-500/30 bg-orange-500/10 p-4 flex items-center gap-3">
          <div className="flex items-center justify-center h-10 w-10 rounded-lg bg-orange-500/20 shrink-0">
            <BellOff className="h-5 w-5 text-orange-500" />
          </div>
          <div className="flex-1">
            <p className="font-medium text-orange-600 dark:text-orange-400">Alerts are currently silenced</p>
            <p className="text-sm text-orange-600/80 dark:text-orange-400/80">
              {activePeriods.length === 1
                ? `${formatTimeRemaining(activePeriods[0].endsAt)} — ${activePeriods[0].reason || 'No reason specified'}`
                : `${activePeriods.length} active silence periods`}
            </p>
          </div>
        </div>
      )}

      <Card>
        <CardHeader>
          <div className="flex items-center gap-3">
            <div className="flex items-center justify-center h-10 w-10 rounded-lg bg-orange-500/10">
              <BellOff className="h-5 w-5 text-orange-500" />
            </div>
            <div>
              <CardTitle>Quick Silence</CardTitle>
              <CardDescription>
                Instantly silence all alert notifications for a preset duration. Alerts will still be evaluated but notifications will be suppressed.
              </CardDescription>
            </div>
          </div>
        </CardHeader>
        <CardContent>
          <div className="flex flex-wrap gap-2">
            {quickOptions.map((opt) => (
              <Button
                key={opt.minutes}
                variant="outline"
                className="gap-2"
                onClick={() => handleQuickSilence(opt.minutes, opt.label)}
                disabled={createMutation.isPending}
              >
                <BellOff className="h-4 w-4" />
                {opt.label}
              </Button>
            ))}
          </div>
        </CardContent>
      </Card>

      <Card>
        <CardHeader>
          <div className="flex items-center justify-between">
            <div className="flex items-center gap-3">
              <div className="flex items-center justify-center h-10 w-10 rounded-lg bg-blue-500/10">
                <Calendar className="h-5 w-5 text-blue-500" />
              </div>
              <div>
                <CardTitle>Silence Periods</CardTitle>
                <CardDescription>
                  Schedule maintenance windows or manage active silence periods. All alert notifications across the organization will be suppressed during these windows.
                </CardDescription>
              </div>
            </div>
            <Dialog open={isCustomDialogOpen} onOpenChange={setIsCustomDialogOpen}>
              <Button className="gap-2" onClick={() => setIsCustomDialogOpen(true)}>
                <Plus className="h-4 w-4" />
                Schedule Window
              </Button>
              <DialogContent>
                <DialogHeader>
                  <DialogTitle className="flex items-center gap-2">
                    <Calendar className="h-5 w-5 text-blue-500" />
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
          </div>
        </CardHeader>
        <CardContent>
          {isLoading ? (
            <div className="flex items-center justify-center py-12">
              <div className="flex flex-col items-center gap-3">
                <Loader2 className="h-6 w-6 animate-spin text-muted-foreground" />
                <p className="text-muted-foreground text-sm">Loading silence periods...</p>
              </div>
            </div>
          ) : silencePeriods.length > 0 ? (
            <div className="space-y-3">
              {activePeriods.map((period) => (
                <div
                  key={period.id}
                  className="group flex items-center gap-4 rounded-lg border border-orange-500/30 bg-orange-500/5 p-4"
                >
                  <div className="flex items-center justify-center h-9 w-9 rounded-lg bg-orange-500/15 shrink-0">
                    <BellOff className="h-4 w-4 text-orange-500" />
                  </div>
                  <div className="flex-1 min-w-0 space-y-1">
                    <div className="flex items-center gap-2 flex-wrap">
                      <span className="text-sm font-medium">{period.reason || 'Silence period'}</span>
                      <Badge className="bg-orange-500/20 text-orange-600 dark:text-orange-400 border-orange-500/30 text-xs">
                        Active
                      </Badge>
                    </div>
                    <div className="flex items-center gap-3 text-xs text-muted-foreground">
                      <span className="flex items-center gap-1">
                        <Clock className="h-3 w-3" />
                        {formatDateTime(period.startsAt)} — {formatDateTime(period.endsAt)}
                      </span>
                      <span className="text-orange-500 font-medium">{formatTimeRemaining(period.endsAt)}</span>
                    </div>
                  </div>
                  <Button
                    size="sm"
                    variant="ghost"
                    className="h-8 w-8 p-0 opacity-0 group-hover:opacity-100 transition-opacity"
                    onClick={() => deleteMutation.mutate(period.id)}
                    disabled={deleteMutation.isPending}
                  >
                    <Trash2 className="h-3.5 w-3.5 text-destructive" />
                  </Button>
                </div>
              ))}
              {scheduledPeriods.map((period) => (
                <div
                  key={period.id}
                  className="group flex items-center gap-4 rounded-lg border p-4 bg-card hover:bg-muted/30 transition-colors"
                >
                  <div className="flex items-center justify-center h-9 w-9 rounded-lg bg-blue-500/15 shrink-0">
                    <Calendar className="h-4 w-4 text-blue-500" />
                  </div>
                  <div className="flex-1 min-w-0 space-y-1">
                    <div className="flex items-center gap-2 flex-wrap">
                      <span className="text-sm font-medium">{period.reason || 'Scheduled silence'}</span>
                      <Badge variant="secondary" className="text-xs">Scheduled</Badge>
                    </div>
                    <div className="flex items-center gap-3 text-xs text-muted-foreground">
                      <span className="flex items-center gap-1">
                        <Clock className="h-3 w-3" />
                        {formatDateTime(period.startsAt)} — {formatDateTime(period.endsAt)}
                      </span>
                    </div>
                  </div>
                  <Button
                    size="sm"
                    variant="ghost"
                    className="h-8 w-8 p-0 opacity-0 group-hover:opacity-100 transition-opacity"
                    onClick={() => deleteMutation.mutate(period.id)}
                    disabled={deleteMutation.isPending}
                  >
                    <Trash2 className="h-3.5 w-3.5 text-destructive" />
                  </Button>
                </div>
              ))}
            </div>
          ) : (
            <div className="text-center py-16">
              <div className="mx-auto mb-4 flex h-16 w-16 items-center justify-center rounded-2xl bg-muted/50">
                <BellOff className="h-8 w-8 text-muted-foreground" />
              </div>
              <h3 className="text-lg font-medium mb-1">No silence periods</h3>
              <p className="text-muted-foreground text-sm mb-6 max-w-sm mx-auto">
                Use the quick silence buttons above or schedule a maintenance window to suppress alert notifications.
              </p>
            </div>
          )}
        </CardContent>
      </Card>

      <Card className="bg-gradient-to-br from-blue-500/5 to-indigo-500/5 border-blue-500/10">
        <CardContent className="pt-5 pb-4">
          <div className="flex items-start gap-3">
            <div className="flex items-center justify-center h-9 w-9 rounded-lg bg-blue-500/15 shrink-0">
              <Info className="h-4 w-4 text-blue-500" />
            </div>
            <div className="space-y-1">
              <h4 className="text-sm font-medium">How Silence Periods Work</h4>
              <p className="text-xs text-muted-foreground leading-relaxed">
                During a silence period, all alert notifications (metric alerts, system up/down, and uptime alerts) are suppressed organization-wide.
                Alerts are still evaluated and trigger timestamps are recorded, but no emails, Slack, or Discord notifications are sent.
                Expired silence periods are automatically cleaned up.
              </p>
            </div>
          </div>
        </CardContent>
      </Card>
    </div>
  )
}

function GeneralTab() {
  const { toast } = useToast()
  const queryClient = useQueryClient()
  const { timezone, updateTimezone } = useTimezone()
  const browserTz = Intl.DateTimeFormat().resolvedOptions().timeZone
  const [saving, setSaving] = useState(false)

  const { data: user } = useQuery({
    queryKey: ['currentUser'],
    queryFn: () => api.getCurrentUser(),
  })

  const [hiddenItems, setHiddenItems] = useState<string[]>(() => user?.sidebarHiddenItems || [])

  const savedTimezone = user?.timezone ?? null
  const hasCurrentTimezoneOption = TIMEZONES.some((tz) => tz.value === (savedTimezone ?? browserTz))

  const handleTimezoneChange = async (value: string) => {
    setSaving(true)
    try {
      await updateTimezone(value === '__browser__' ? null : value)
      toast({ title: 'Display preferences saved', description: 'Your timezone has been updated.' })
    } catch {
      toast({ title: 'Error', description: 'Failed to save timezone.', variant: 'destructive' })
    } finally {
      setSaving(false)
    }
  }

  const saveMutation = useMutation({
    mutationFn: (items: string[]) => api.updateSidebarPreferences(items),
    onSuccess: () => {
      queryClient.invalidateQueries({ queryKey: ['currentUser'] })
      toast({
        title: 'Preferences saved',
        description: 'Your sidebar preferences have been updated.',
      })
    },
    onError: () => {
      toast({
        title: 'Error',
        description: 'Failed to save sidebar preferences.',
        variant: 'destructive',
      })
    },
  })

  const toggleItem = (itemKey: string) => {
    setHiddenItems(prev => {
      if (prev.includes(itemKey)) {
        return prev.filter(k => k !== itemKey)
      } else {
        return [...prev, itemKey]
      }
    })
  }

  const checkAll = () => {
    setHiddenItems([])
  }

  const uncheckAll = () => {
    setHiddenItems(getAllSidebarItemKeys())
  }

  const hasChanges = JSON.stringify(hiddenItems.sort()) !== JSON.stringify((user?.sidebarHiddenItems || []).sort())
  const selectValue = savedTimezone ?? '__browser__'

  return (
    <div className="space-y-4">
      <Card>
        <CardHeader>
          <CardTitle>Display Settings</CardTitle>
          <CardDescription>
            Choose how dates and times are displayed throughout the dashboard.
          </CardDescription>
        </CardHeader>
        <CardContent className="space-y-6">
          <div className="space-y-2">
            <Label htmlFor="timezone-select">Timezone</Label>
            <Select
              value={selectValue}
              onValueChange={handleTimezoneChange}
            >
              <SelectTrigger id="timezone-select" className="max-w-md">
                <SelectValue placeholder="Select timezone..." />
              </SelectTrigger>
              <SelectContent className="max-h-[300px]">
                <SelectItem value="__browser__">Browser default ({browserTz})</SelectItem>
                {!hasCurrentTimezoneOption && savedTimezone && (
                  <SelectItem value={savedTimezone}>{savedTimezone}</SelectItem>
                )}
                {TIMEZONES.map((tz) => (
                  <SelectItem key={tz.value} value={tz.value}>
                    {tz.label}
                  </SelectItem>
                ))}
              </SelectContent>
            </Select>
            <p className="text-sm text-muted-foreground">
              Preview: {formatDateTimeUtil(new Date(), timezone)}
            </p>
          </div>
          {saving && (
            <p className="text-sm text-muted-foreground flex items-center gap-2">
              <Loader2 className="h-4 w-4 animate-spin" />
              Saving…
            </p>
          )}
        </CardContent>
      </Card>

      <Card>
        <CardHeader>
          <CardTitle>Sidebar Navigation</CardTitle>
          <CardDescription>
            Customize which features appear in your sidebar. Settings and Admin are always visible.
          </CardDescription>
        </CardHeader>
        <CardContent className="space-y-4">
          <div className="flex gap-2 mb-4">
            <Button
              variant="outline"
              size="sm"
              onClick={checkAll}
            >
              <Check className="h-4 w-4 mr-2" />
              Show All
            </Button>
            <Button
              variant="outline"
              size="sm"
              onClick={uncheckAll}
            >
              <Minus className="h-4 w-4 mr-2" />
              Hide All
            </Button>
          </div>

          <div className="grid grid-cols-1 md:grid-cols-2 gap-3">
            {CONFIGURABLE_SIDEBAR_ITEMS.map(item => {
              const ItemIcon = item.icon
              return (
                <div key={item.key} className="flex items-center justify-between space-x-2 rounded-lg border p-3">
                  <div className="flex items-center space-x-3">
                    {ItemIcon && <ItemIcon className="h-4 w-4 text-muted-foreground" />}
                    <Label
                      htmlFor={`sidebar-${item.key}`}
                      className="text-sm font-medium leading-none cursor-pointer"
                    >
                      {item.label}
                    </Label>
                  </div>
                  <Switch
                    id={`sidebar-${item.key}`}
                    checked={!hiddenItems.includes(item.key)}
                    onCheckedChange={() => toggleItem(item.key)}
                  />
                </div>
              )
            })}
          </div>

          {hasChanges && (
            <div className="flex justify-end pt-4 border-t">
              <Button
                onClick={() => saveMutation.mutate(hiddenItems)}
                disabled={saveMutation.isPending}
              >
                {saveMutation.isPending && <Loader2 className="h-4 w-4 mr-2 animate-spin" />}
                Save Changes
              </Button>
            </div>
          )}
        </CardContent>
      </Card>
    </div>
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
    <div className="space-y-6">
      {/* Organization Deletion - Owner Only */}
      {isOwner && (
        <Card className="border-red-200 dark:border-red-900/20">
          <CardHeader>
            <CardTitle className="text-red-600 dark:text-red-400 flex items-center gap-2">
              <AlertTriangle className="h-5 w-5" />
              Delete Organization
            </CardTitle>
            <CardDescription>
              Permanently delete your organization and all associated data. This action cannot be undone.
            </CardDescription>
          </CardHeader>
          <CardContent className="space-y-4">
            <div className="rounded-lg bg-red-50 dark:bg-red-950/20 border border-red-200 dark:border-red-900/30 p-4">
              <h4 className="text-sm font-semibold text-red-900 dark:text-red-200 mb-2">
                What will be deleted:
              </h4>
              <ul className="text-sm text-red-700 dark:text-red-300 space-y-1 list-disc list-inside">
                <li>All projects and their events</li>
                <li>All error data, transactions, sessions, and replays</li>
                <li>All monitoring data and uptime checks</li>
                <li>All team members will be removed</li>
                <li>All integrations and alert configurations</li>
                <li>Billing subscription (will be cancelled)</li>
              </ul>
            </div>
            
            {!orgValidation?.canDelete && (
              <div className="rounded-lg bg-amber-50 dark:bg-amber-950/20 border border-amber-200 dark:border-amber-900/30 p-3">
                <p className="text-sm text-amber-800 dark:text-amber-300">
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
      <Card className="border-orange-200 dark:border-orange-900/20">
        <CardHeader>
          <CardTitle className="text-orange-600 dark:text-orange-400 flex items-center gap-2">
            <AlertTriangle className="h-5 w-5" />
            Delete Account
          </CardTitle>
          <CardDescription>
            Permanently delete your personal account. You will be removed from all organizations.
          </CardDescription>
        </CardHeader>
        <CardContent className="space-y-4">
          <div className="rounded-lg bg-orange-50 dark:bg-orange-950/20 border border-orange-200 dark:border-orange-900/30 p-4">
            <h4 className="text-sm font-semibold text-orange-900 dark:text-orange-200 mb-2">
              What will happen:
            </h4>
            <ul className="text-sm text-orange-700 dark:text-orange-300 space-y-1 list-disc list-inside">
              <li>Your account will be permanently deleted</li>
              <li>You will be removed from all organizations</li>
              <li>All your personal data will be erased</li>
              <li>Any auth tokens you created will be revoked</li>
            </ul>
          </div>
          
          {!accountValidation?.canDelete && accountValidation?.organizationsAsLastOwner && accountValidation.organizationsAsLastOwner.length > 0 && (
            <div className="rounded-lg bg-amber-50 dark:bg-amber-950/20 border border-amber-200 dark:border-amber-900/30 p-3">
              <p className="text-sm text-amber-800 dark:text-amber-300 mb-2">
                <AlertCircle className="h-4 w-4 inline mr-1" />
                You cannot delete your account because you are the last owner of:
              </p>
              <ul className="text-sm text-amber-700 dark:text-amber-400 list-disc list-inside ml-4">
                {accountValidation.organizationsAsLastOwner.map((org: string) => (
                  <li key={org}>{org}</li>
                ))}
              </ul>
              <p className="text-xs text-amber-600 dark:text-amber-400 mt-2">
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
            <DialogTitle className="text-red-600">Delete Organization</DialogTitle>
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
            <DialogTitle className="text-red-600">Delete Account</DialogTitle>
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
  )
}
