import {Fragment, useEffect, useState} from 'react'
import {createFileRoute, redirect} from '@tanstack/react-router'
import {useMutation, useQuery, useQueryClient} from '@tanstack/react-query'
import {api, type AuthToken} from '@/lib/api'
import {Button} from '@/components/ui/button'
import {Input} from '@/components/ui/input'
import {Label} from '@/components/ui/label'
import {Card, CardContent, CardDescription, CardHeader, CardTitle} from '@/components/ui/card'
import {Tabs, TabsContent, TabsList, TabsTrigger} from '@/components/ui/tabs'
import {Table, TableBody, TableCell, TableHead, TableHeader, TableRow,} from '@/components/ui/table'
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
import {Select, SelectContent, SelectItem, SelectTrigger, SelectValue,} from '@/components/ui/select'
import {useToast} from '@/hooks/use-toast'
import {AlertTriangle, Bell, Copy, CreditCard, ExternalLink, Key, Plus, Trash2} from 'lucide-react'

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

function formatDate(iso: string | null | undefined): string {
  if (!iso) return '—'
  try {
    return new Date(iso).toLocaleDateString(undefined, {
      year: 'numeric',
      month: 'short',
      day: 'numeric',
    })
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
  beforeLoad: async () => {
    if (!api.isAuthenticated()) {
      throw redirect({ to: '/login' })
    }
  },
  component: SettingsPage,
})

function SettingsPage() {
  return (
    <div className="min-h-screen bg-background">
      <div className="p-6 max-w-7xl mx-auto">
        <h1 className="text-2xl font-bold mb-4">Settings</h1>
        <Tabs defaultValue="auth-tokens" className="space-y-4">
          <TabsList>
            <TabsTrigger value="auth-tokens">Auth Tokens</TabsTrigger>
            <TabsTrigger value="notifications">Notifications</TabsTrigger>
            <TabsTrigger value="billing">Billing</TabsTrigger>
          </TabsList>
          <TabsContent value="auth-tokens" className="space-y-4">
            <AuthTokensTab />
          </TabsContent>
          <TabsContent value="notifications" className="space-y-4">
            <NotificationsTab />
          </TabsContent>
          <TabsContent value="billing" className="space-y-4">
            <BillingTab />
          </TabsContent>
        </Tabs>
      </div>
    </div>
  )
}

function AuthTokensTab() {
  const queryClient = useQueryClient()
  const { toast } = useToast()
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
              Generate tokens for CLI tools and CI/CD pipelines to upload source maps, manage
              releases, and more.
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
                        {formatDate(token.lastUsedAt)}
                      </TableCell>
                      <TableCell className="text-muted-foreground text-sm">
                        {token.expiresAt ? formatDate(token.expiresAt) : 'Never'}
                      </TableCell>
                      <TableCell className="text-muted-foreground text-sm">
                        {formatDate(token.createdAt)}
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
                className="flex items-center gap-2 rounded-md border bg-muted/50 p-3 font-mono text-sm break-all"
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

function BillingTab() {
  const queryClient = useQueryClient()
  const { toast } = useToast()
  const [budgetDollars, setBudgetDollars] = useState('0')

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

  useEffect(() => {
    if (usage) {
      setBudgetDollars((usage.paygBudgetCents / 100).toString())
    }
  }, [usage?.paygBudgetCents])

  const updateBudgetMutation = useMutation({
    mutationFn: (paygBudgetCents: number) => api.updatePaygBudget(paygBudgetCents),
    onSuccess: () => {
      queryClient.invalidateQueries({ queryKey: ['billingUsage'] })
      toast({ title: 'PAYG budget updated' })
    },
    onError: (err: Error) => {
      toast({
        title: 'Failed to update PAYG budget',
        description: err.message,
        variant: 'destructive',
      })
    },
  })

  const portalMutation = useMutation({
    mutationFn: () => api.createBillingPortalSession(window.location.href),
    onSuccess: (response) => {
      if (response.url) {
        window.location.href = response.url
      }
    },
    onError: (err: Error) => {
      toast({
        title: 'Unable to open billing portal',
        description: err.message,
        variant: 'destructive',
      })
    },
  })

  const checkoutMutation = useMutation({
    mutationFn: (tierName: string) =>
      api.createBillingCheckoutSession({
        tierName,
        successUrl: `${window.location.origin}/settings`,
        cancelUrl: `${window.location.origin}/settings`,
      }),
    onSuccess: (session) => {
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

  if (isLoading || !usage) {
    return <p className="text-sm text-muted-foreground">Loading billing details...</p>
  }

  const usagePercent = usage.totalLimitUnits > 0
    ? Math.min(100, (usage.usedUnits / usage.totalLimitUnits) * 100)
    : 0
  const isPaidTier = usage.plan !== 'free'
  const billablePlans = plansData?.plans?.filter((p) => p.tier.tierName !== 'FREE') ?? []

  const saveBudget = () => {
    const cents = Math.round(Number(budgetDollars) * 100)
    if (!Number.isFinite(cents) || cents < 0 || cents % 500 !== 0) {
      toast({
        title: 'Invalid budget',
        description: 'Budget must be in $5 increments.',
        variant: 'destructive',
      })
      return
    }
    updateBudgetMutation.mutate(cents)
  }

  return (
    <div className="space-y-4">
      <Card>
        <CardHeader>
          <div className="flex items-center gap-2">
            <CreditCard className="h-5 w-5" />
            <CardTitle>Subscription</CardTitle>
          </div>
          <CardDescription>
            Manage your plan, monthly usage, and PAYG budget.
          </CardDescription>
        </CardHeader>
        <CardContent className="space-y-4">
          <div className="flex items-center justify-between">
            <div>
              <p className="text-sm text-muted-foreground">Current plan</p>
              <p className="text-lg font-semibold capitalize">{usage.plan}</p>
            </div>
            <Badge variant={usage.status === 'active' ? 'default' : 'secondary'}>
              {usage.status}
            </Badge>
          </div>

          <div className="space-y-2">
            <div className="flex items-center justify-between text-sm">
              <span>Usage</span>
              <span>{usage.usedUnits.toLocaleString()} / {usage.totalLimitUnits.toLocaleString()} units</span>
            </div>
            <div className="h-2 w-full rounded-full bg-muted">
              <div
                className={`h-2 rounded-full ${usagePercent >= 80 ? 'bg-amber-500' : 'bg-emerald-500'}`}
                style={{ width: `${usagePercent}%` }}
              />
            </div>
          </div>

          <div className="grid gap-2 sm:grid-cols-[1fr_auto] sm:items-end">
            <div>
              <Label htmlFor="payg-budget">PAYG budget (USD, $5 increments)</Label>
              <Input
                id="payg-budget"
                value={budgetDollars}
                onChange={(e) => setBudgetDollars(e.target.value)}
                disabled={!isPaidTier}
              />
            </div>
            <Button onClick={saveBudget} disabled={!isPaidTier || updateBudgetMutation.isPending}>
              Save budget
            </Button>
          </div>

          <div className="flex flex-wrap gap-2">
            <Button variant="outline" onClick={() => portalMutation.mutate()} disabled={portalMutation.isPending}>
              <ExternalLink className="h-4 w-4 mr-2" />
              Open Customer Portal
            </Button>
          </div>
        </CardContent>
      </Card>

      <Card>
        <CardHeader>
          <CardTitle>Upgrade or Change Plan</CardTitle>
          <CardDescription>
            Paid plans include a 14-day trial.
          </CardDescription>
        </CardHeader>
        <CardContent className="space-y-2">
          {billablePlans.length === 0 ? (
            <p className="text-sm text-muted-foreground">No paid plans configured yet.</p>
          ) : (
            billablePlans.map((plan) => (
              <div key={plan.tier.id} className="flex items-center justify-between rounded border p-3">
                <div>
                  <p className="font-medium">{plan.tier.tierName}</p>
                  <p className="text-xs text-muted-foreground">
                    ${(plan.tier.monthlyPriceCents / 100).toFixed(2)}/mo · {plan.tier.monthlyUnitLimit.toLocaleString()} units
                  </p>
                </div>
                <Button
                  size="sm"
                  disabled={checkoutMutation.isPending}
                  onClick={() => checkoutMutation.mutate(plan.tier.tierName)}
                >
                  Select
                </Button>
              </div>
            ))
          )}
        </CardContent>
      </Card>
    </div>
  )
}

function NotificationsTab() {
  const queryClient = useQueryClient()
  const { toast } = useToast()

  const { data: preferences, isLoading } = useQuery({
    queryKey: ['notificationPreferences'],
    queryFn: () => api.getNotificationPreferences(),
    enabled: api.isAuthenticated(),
  })

  const updateGlobalMutation = useMutation({
    mutationFn: (prefs: Partial<{
      issueAlerts: boolean
      errorAlerts: boolean
      weeklySummary: boolean
      alertFrequencyMinutes: number
    }>) => api.updateNotificationPreferences(prefs),
    onSuccess: () => {
      queryClient.invalidateQueries({ queryKey: ['notificationPreferences'] })
      toast({ title: 'Preferences updated', description: 'Your notification preferences have been saved.' })
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

  if (isLoading) {
    return <div>Loading...</div>
  }

  const global = preferences?.global || {
    issueAlerts: true,
    errorAlerts: true,
    weeklySummary: true,
    alertFrequencyMinutes: 30,
  }

  const projects = preferences?.projects || []

  return (
    <div className="space-y-6">
      {/* Global Preferences */}
      <Card>
        <CardHeader>
          <div className="flex items-center gap-2">
            <Bell className="h-5 w-5" />
            <CardTitle>Global Notification Preferences</CardTitle>
          </div>
          <CardDescription>
            Default notification settings for all projects. You can override these settings per project below.
          </CardDescription>
        </CardHeader>
        <CardContent className="space-y-4">
          <div className="flex items-center justify-between space-x-2">
            <div className="space-y-0.5">
              <Label htmlFor="global-issue-alerts" className="text-base font-medium">
                Issue Alerts
              </Label>
              <p className="text-sm text-muted-foreground">
                Get notified when new issues are detected
              </p>
            </div>
            <Checkbox
              id="global-issue-alerts"
              checked={global.issueAlerts}
              onCheckedChange={(checked) => updateGlobalMutation.mutate({ issueAlerts: checked === true })}
            />
          </div>

          <div className="flex items-center justify-between space-x-2">
            <div className="space-y-0.5">
              <Label htmlFor="global-error-alerts" className="text-base font-medium">
                Error Alerts
              </Label>
              <p className="text-sm text-muted-foreground">
                Get notified about errors and exceptions
              </p>
            </div>
            <Checkbox
              id="global-error-alerts"
              checked={global.errorAlerts}
              onCheckedChange={(checked) => updateGlobalMutation.mutate({ errorAlerts: checked === true })}
            />
          </div>

          <div className="flex items-center justify-between space-x-2">
            <div className="space-y-0.5">
              <Label htmlFor="global-weekly-summary" className="text-base font-medium">
                Weekly Summary
              </Label>
              <p className="text-sm text-muted-foreground">
                Receive a weekly summary email every Monday
              </p>
            </div>
            <Checkbox
              id="global-weekly-summary"
              checked={global.weeklySummary}
              onCheckedChange={(checked) => updateGlobalMutation.mutate({ weeklySummary: checked === true })}
            />
          </div>

          <div className="space-y-2">
            <Label htmlFor="global-frequency" className="text-base font-medium">
              Alert Frequency
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

      {/* Per-Project Overrides */}
      <Card>
        <CardHeader>
          <CardTitle>Per-Project Overrides</CardTitle>
          <CardDescription>
            Customize notification settings for specific projects
          </CardDescription>
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
                        <Trash2 className="h-4 w-4" />
                      </Button>
                    </TableCell>
                  </TableRow>
                ))}
              </TableBody>
            </Table>
          )}
        </CardContent>
      </Card>
    </div>
  )
}
