import {type FormEvent, Fragment, useEffect, useMemo, useState} from 'react'
import {createFileRoute, redirect, useSearch} from '@tanstack/react-router'
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
import {
    AlertTriangle,
    Bell,
    CheckCircle2,
    Copy,
    CreditCard,
    Key,
    Loader2,
    Minus,
    Plus,
    Trash2,
    Zap,
    Activity,
    MessageSquare,
    AlertCircle,
    Download,
    Receipt,
    Check,
    Clock,
    Wallet,
    Layers
} from 'lucide-react'
import {Elements, PaymentElement, useElements, useStripe} from '@stripe/react-stripe-js'
import {loadStripe} from '@stripe/stripe-js'

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
  validateSearch: (search: Record<string, unknown>) => {
    return {
      tab: (search.tab as string) || 'auth-tokens',
    }
  },
  beforeLoad: async () => {
    if (!api.isAuthenticated()) {
      throw redirect({ to: '/login' })
    }
  },
  component: SettingsPage,
})

function SettingsPage() {
  const search = useSearch({ from: '/settings' })
  return (
    <div className="min-h-screen bg-background">
      <div className="p-6 max-w-7xl mx-auto">
        <h1 className="text-2xl font-bold mb-4">Settings</h1>
        <Tabs defaultValue={search.tab || 'auth-tokens'} className="space-y-4">
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
  const [showPaymentForm, setShowPaymentForm] = useState(false)
  const [setupClientSecret, setSetupClientSecret] = useState<string | null>(null)
  const [showCancelDialog, setShowCancelDialog] = useState(false)

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

  const stripePromise = useMemo(() => {
    if (!plansData?.publishableKey) return null
    return loadStripe(plansData.publishableKey)
  }, [plansData?.publishableKey])

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
  const paygAvailable = isPaidTier && (currentPlan?.tier.paygEnabled ?? false)
  const billablePlans = plansData?.plans?.filter((p) => p.tier.tierName !== 'FREE') ?? []
  const periodLabel = `${formatDate(usage.periodStart)} – ${formatDate(usage.periodEnd)}`

  const usageRows = [
    { key: 'error', label: 'Errors', used: usage.usedErrors, limit: usage.errorLimit, icon: AlertCircle, color: 'text-red-500' },
    { key: 'transaction', label: 'Transactions', used: usage.usedTransactions, limit: usage.transactionLimit, icon: Activity, color: 'text-blue-500' },
    { key: 'replay', label: 'Replays', used: usage.usedReplays, limit: usage.replayLimit, icon: Zap, color: 'text-yellow-500' },
    { key: 'feedback', label: 'Feedback', used: usage.usedFeedback, limit: usage.feedbackLimit, icon: MessageSquare, color: 'text-purple-500' },
  ] as const

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

  const incrementBudget = (deltaDollars: number) => {
    const current = Number(budgetDollars)
    const next = Number.isFinite(current) ? Math.max(0, current + deltaDollars) : Math.max(0, deltaDollars)
    setBudgetDollars(next.toString())
  }

  const onPaymentMethodUpdated = () => {
    setShowPaymentForm(false)
    setSetupClientSecret(null)
    queryClient.invalidateQueries({ queryKey: ['billingPaymentMethod'] })
    queryClient.invalidateQueries({ queryKey: ['billingInvoices'] })
  }

  const formatCurrency = (cents: number) => `$${(cents / 100).toFixed(2)}`
  const statusBadgeVariant = usage.status === 'active' || usage.status === 'trialing' ? 'default' : 'secondary'

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

      <Card>
        <CardHeader>
          <div className="flex items-center gap-2">
            <div className="p-2 bg-blue-500/10 rounded-full">
              <Activity className="h-5 w-5 text-blue-500" />
            </div>
            <div>
              <CardTitle>Usage Breakdown</CardTitle>
              <CardDescription>
                Quota usage by event type for the current billing period.
              </CardDescription>
            </div>
          </div>
        </CardHeader>
        <CardContent className="space-y-6">
          {usageRows.map((row) => {
            const isUnlimited = row.limit < 0
            const percent = row.limit > 0
              ? Math.min(100, (row.used / row.limit) * 100)
              : row.limit === 0
                ? (row.used > 0 ? 100 : 0)
                : 0
            const barClass = percent >= 100
              ? 'bg-red-500'
              : percent >= 80
                ? 'bg-amber-500'
                : 'bg-emerald-500'
            const overageUnits = row.limit > 0 ? Math.max(0, row.used - row.limit) : 0
            const Icon = row.icon

            return (
              <div key={row.key} className="space-y-3">
                <div className="flex items-center justify-between text-sm">
                  <div className="flex items-center gap-2">
                    <Icon className={`h-4 w-4 ${row.color}`} />
                    <span className="font-medium">{row.label}</span>
                  </div>
                  <span className="text-muted-foreground">
                    <span className="font-medium text-foreground">{row.used.toLocaleString()}</span>
                    {' / '}
                    {isUnlimited ? 'Unlimited' : row.limit.toLocaleString()}
                  </span>
                </div>
                <div className="h-2.5 w-full rounded-full bg-secondary overflow-hidden">
                  <div className={`h-full rounded-full transition-all ${barClass}`} style={{ width: `${percent}%` }} />
                </div>
                {overageUnits > 0 && (
                  <div className="flex items-center gap-1.5 text-xs text-amber-600 bg-amber-50 px-2 py-1 rounded w-fit">
                    <AlertTriangle className="h-3 w-3" />
                    <span>{overageUnits.toLocaleString()} over base limit (PAYG overage).</span>
                  </div>
                )}
              </div>
            )
          })}

          <div className="mt-6 pt-6 border-t">
            <div className="flex items-center justify-between rounded-lg bg-muted/50 p-4">
              <div className="flex items-center gap-2">
                <Activity className="h-4 w-4 text-muted-foreground" />
                <span className="font-medium">Total usage</span>
              </div>
              <span className="font-mono font-medium">
                {usage.usedUnits.toLocaleString()} / {usage.totalLimitUnits.toLocaleString()} units
              </span>
            </div>
          </div>
        </CardContent>
      </Card>

      {paygAvailable && (
        <Card>
          <CardHeader>
            <div className="flex items-center gap-2">
              <div className="p-2 bg-emerald-500/10 rounded-full">
                <Wallet className="h-5 w-5 text-emerald-600" />
              </div>
              <div>
                <CardTitle>PAYG Budget</CardTitle>
                <CardDescription>
                  Set a monthly overage budget in $5 increments.
                </CardDescription>
              </div>
            </div>
          </CardHeader>
          <CardContent className="space-y-4">
            <div>
              <Label htmlFor="payg-budget">PAYG budget (USD, $5 increments)</Label>
              <Input
                id="payg-budget"
                value={budgetDollars}
                onChange={(e) => setBudgetDollars(e.target.value)}
              />
            </div>
            <div className="flex flex-wrap items-center gap-2">
              <Button
                type="button"
                variant="outline"
                size="sm"
                onClick={() => incrementBudget(-5)}
                disabled={updateBudgetMutation.isPending}
              >
                <Minus className="h-4 w-4 mr-1" />
                $5
              </Button>
              <Button
                type="button"
                variant="outline"
                size="sm"
                onClick={() => incrementBudget(5)}
                disabled={updateBudgetMutation.isPending}
              >
                <Plus className="h-4 w-4 mr-1" />
                $5
              </Button>
              <Button onClick={saveBudget} disabled={updateBudgetMutation.isPending}>
                {updateBudgetMutation.isPending ? 'Saving...' : 'Save budget'}
              </Button>
            </div>
            <p className="text-sm text-muted-foreground">
              Current PAYG spend: {formatCurrency(usage.paygUsedCentsEstimate)}
            </p>
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
                  <Elements stripe={stripePromise} options={{ clientSecret: setupClientSecret }}>
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
                            <TableCell className="font-medium">{formatDate(invoice.date)}</TableCell>
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
        </CardHeader>
        <CardContent className="space-y-3">
          {billablePlans.length === 0 ? (
            <p className="text-sm text-muted-foreground">No paid plans configured yet.</p>
          ) : (
            billablePlans.map((plan) => {
              const isCurrentPlan = plan.tier.tierName.toLowerCase() === usage.plan.toLowerCase()
              return (
                <div key={plan.tier.id} className="flex items-center justify-between rounded border p-3">
                  <div>
                    <p className="font-medium">{plan.tier.tierName}</p>
                    <p className="text-xs text-muted-foreground">
                      {formatCurrency(plan.tier.monthlyPriceCents)}/mo · {plan.tier.monthlyUnitLimit.toLocaleString()} total units
                    </p>
                  </div>
                  <Button
                    size="sm"
                    variant={isCurrentPlan ? 'secondary' : 'default'}
                    disabled={checkoutMutation.isPending || isCurrentPlan}
                    onClick={() => checkoutMutation.mutate(plan.tier.tierName)}
                  >
                    {isCurrentPlan ? 'Current plan' : 'Select'}
                  </Button>
                </div>
              )
            })
          )}

          {isPaidTier && (
            <div className="pt-2">
              <Button
                variant="destructive"
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
    const result = await stripe.confirmSetup({
      elements,
      redirect: 'if_required',
    })
    setIsSubmitting(false)

    if (result.error) {
      toast({
        title: 'Payment method update failed',
        description: result.error.message || 'Stripe could not confirm this payment method.',
        variant: 'destructive',
      })
      return
    }

    toast({
      title: 'Payment method saved',
      description: 'Your default payment method has been updated.',
    })
    onSuccess()
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
    </div>
  )
}
