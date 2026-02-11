import {useCallback, useEffect, useMemo, useState} from 'react'
import {createFileRoute} from '@tanstack/react-router'
import {useMutation, useQuery, useQueryClient} from '@tanstack/react-query'
import {type AdminBillingSubscription, api, type BillingPlan, type BillingTierConfig} from '@/lib/api'
import {Card, CardContent, CardDescription, CardHeader, CardTitle} from '@/components/ui/card'
import {Button} from '@/components/ui/button'
import {Input} from '@/components/ui/input'
import {Label} from '@/components/ui/label'
import {Badge} from '@/components/ui/badge'
import {Switch} from '@/components/ui/switch'
import {Select, SelectContent, SelectItem, SelectTrigger, SelectValue} from '@/components/ui/select'
import {Separator} from '@/components/ui/separator'
import {Table, TableBody, TableCell, TableHead, TableHeader, TableRow} from '@/components/ui/table'
import {
  Dialog,
  DialogContent,
  DialogDescription,
  DialogFooter,
  DialogHeader,
  DialogTitle,
} from '@/components/ui/dialog'
import {Tooltip, TooltipContent, TooltipProvider, TooltipTrigger} from '@/components/ui/tooltip'
import {useToast} from '@/hooks/use-toast'
import {AdminSkeleton, PlanBadge, SectionHeader} from '@/components/admin-components'
import {buildPricingCardModel, type BillingInterval, type PricingCardTierInput} from '@/lib/pricing-display'
import {AlertTriangle, Check, HelpCircle, Info} from 'lucide-react'

export const Route = createFileRoute('/admin/billing')({
  component: AdminBillingPage,
})

// ─── Helpers ──────────────────────────────────────────────────────────────────

function centsToDollars(cents: number): string {
  return (cents / 100).toFixed(2)
}

function microsToDollars(micros: number): string {
  return (micros / 1_000_000).toFixed(6)
}

function formatInterval(seconds: number): string {
  if (seconds >= 3600) return `${seconds / 3600}h`
  if (seconds >= 60) return `${seconds / 60}m`
  return `${seconds}s`
}

function FieldHint({children}: {children: React.ReactNode}) {
  return <p className="text-xs text-muted-foreground mt-1">{children}</p>
}

function HelpTip({text}: {text: string}) {
  return (
    <Tooltip>
      <TooltipTrigger asChild>
        <HelpCircle className="h-3.5 w-3.5 text-muted-foreground inline-block ml-1 cursor-help" />
      </TooltipTrigger>
      <TooltipContent side="top" className="max-w-xs">
        <p className="text-xs">{text}</p>
      </TooltipContent>
    </Tooltip>
  )
}

// Known tier names for the dropdown
const KNOWN_TIERS = ['FREE', 'PRO', 'TEAM', 'BUSINESS']
const TIER_ORDER: Record<string, number> = {FREE: 0, PRO: 1, TEAM: 2, BUSINESS: 3}
const BYTES_PER_GB = 1024 * 1024 * 1024

// ─── Validation ───────────────────────────────────────────────────────────────

interface ValidationErrors {
  monthlyErrorLimit?: string
  monthlyTransactionLimit?: string
  monthlyReplayLimit?: string
  monthlyFeedbackLimit?: string
  retentionDays?: string
  maxSystems?: string
  monitorIntervalSeconds?: string
  monthlyPriceCents?: string
  yearlyPriceCents?: string
  monthlyGbLimitGb?: string
  trialDays?: string
  paygRateMicrosPerUnit?: string
}

function validateCreateForm(form: CreateFormState): ValidationErrors {
  const errors: ValidationErrors = {}

  if (form.monthlyErrorLimit < 0) errors.monthlyErrorLimit = 'Cannot be negative'
  if (form.monthlyTransactionLimit < 0) errors.monthlyTransactionLimit = 'Cannot be negative'
  if (form.monthlyReplayLimit < -1) errors.monthlyReplayLimit = 'Cannot be less than -1 (use -1 for unlimited)'
  if (form.monthlyFeedbackLimit < 0) errors.monthlyFeedbackLimit = 'Cannot be negative'
  if (form.retentionDays < 1) {
    errors.retentionDays = 'Must be at least 1 day'
  }
  if (form.retentionDays > 90) {
    errors.retentionDays = 'Cannot exceed 90 days'
  }
  if (form.maxSystems < 1) {
    errors.maxSystems = 'Must be at least 1 system'
  }
  if (form.monitorIntervalSeconds < 5) {
    errors.monitorIntervalSeconds = 'Must be at least 5 seconds'
  }
  if (form.monitorIntervalSeconds > 3600) {
    errors.monitorIntervalSeconds = 'Cannot exceed 3,600 seconds (1 hour)'
  }
  if (form.monthlyPriceCents < 0) {
    errors.monthlyPriceCents = 'Price cannot be negative'
  }
  if (form.yearlyPriceCents < 0) {
    errors.yearlyPriceCents = 'Yearly price cannot be negative'
  }
  if (form.monthlyGbLimitGb < 0) {
    errors.monthlyGbLimitGb = 'Data limit cannot be negative'
  }
  if (form.trialDays < 0) {
    errors.trialDays = 'Trial days cannot be negative'
  }
  if (form.trialDays > 60) {
    errors.trialDays = 'Cannot exceed 60 days'
  }
  if (form.paygEnabled && form.paygRateMicrosPerUnit < 1) {
    errors.paygRateMicrosPerUnit = 'PAYG rate must be at least 1 micro'
  }

  return errors
}

// ─── Types ────────────────────────────────────────────────────────────────────

interface CreateFormState {
  monthlyErrorLimit: number
  monthlyTransactionLimit: number
  monthlyReplayLimit: number
  monthlyFeedbackLimit: number
  retentionDays: number
  maxProjects: string
  maxSystems: number
  monitorIntervalSeconds: number
  monthlyPriceCents: number
  yearlyPriceCents: number
  monthlyGbLimitGb: number
  trialDays: number
  paygEnabled: boolean
  paygRateMicrosPerUnit: number
  stripeBasePriceId: string
  stripeOveragePriceId: string
  stripeYearlyBasePriceId: string
  stripeYearlyOveragePriceId: string
}

const DEFAULT_FORM: CreateFormState = {
  monthlyErrorLimit: 500_000,
  monthlyTransactionLimit: 0,
  monthlyReplayLimit: 0,
  monthlyFeedbackLimit: 0,
  retentionDays: 30,
  maxProjects: '',
  maxSystems: 5,
  monitorIntervalSeconds: 15,
  monthlyPriceCents: 1900,
  yearlyPriceCents: 19_200,
  monthlyGbLimitGb: 50,
  trialDays: 14,
  paygEnabled: true,
  paygRateMicrosPerUnit: 10,
  stripeBasePriceId: '',
  stripeOveragePriceId: '',
  stripeYearlyBasePriceId: '',
  stripeYearlyOveragePriceId: '',
}

// ─── Main Page ────────────────────────────────────────────────────────────────

function AdminBillingPage() {
  const queryClient = useQueryClient()
  const {toast} = useToast()

  // Separate tier selectors for create vs. migrate sections
  const [createTier, setCreateTier] = useState('PRO')
  const [migrateTier, setMigrateTier] = useState('PRO')
  const [updateTier, setUpdateTier] = useState('PRO')
  const [updateVersion, setUpdateVersion] = useState<number | ''>('')
  const [targetVersion, setTargetVersion] = useState<number | ''>('')
  const [createForm, setCreateForm] = useState<CreateFormState>(DEFAULT_FORM)
  const [previewInterval, setPreviewInterval] = useState<BillingInterval>('monthly')
  const [updatePriceForm, setUpdatePriceForm] = useState({
    stripeBasePriceId: '',
    stripeOveragePriceId: '',
    stripeYearlyBasePriceId: '',
    stripeYearlyOveragePriceId: '',
  })
  const [showCreateConfirm, setShowCreateConfirm] = useState(false)
  const [showMigrateConfirm, setShowMigrateConfirm] = useState(false)
  const [dryRunResult, setDryRunResult] = useState<{affected: number; version: number} | null>(null)
  const [subFilter, setSubFilter] = useState('')

  // ─── Queries ──────────────────────────────────────────────────────────────

  const {data: currentPlansRaw, isLoading: plansLoading} = useQuery({
    queryKey: ['admin-billing-current-plans'],
    queryFn: () => api.getAdminBillingTiers(),
  })

  const {data: createTierVersionsRaw} = useQuery({
    queryKey: ['admin-billing-tier-versions', createTier],
    queryFn: () => api.getAdminBillingTiers(createTier),
  })

  const {data: migrateTierVersionsRaw} = useQuery({
    queryKey: ['admin-billing-tier-versions', migrateTier],
    queryFn: () => api.getAdminBillingTiers(migrateTier),
  })

  const {data: updateTierVersionsRaw} = useQuery({
    queryKey: ['admin-billing-tier-versions', updateTier],
    queryFn: () => api.getAdminBillingTiers(updateTier),
  })

  const {data: subscriptions = [], isLoading: subscriptionsLoading} = useQuery({
    queryKey: ['admin-billing-subscriptions'],
    queryFn: () => api.getAdminBillingSubscriptions(250),
  })

  // ─── Derived Data ─────────────────────────────────────────────────────────

  const currentPlans = useMemo(
    () =>
      (Array.isArray(currentPlansRaw) && currentPlansRaw.length > 0 && 'tier' in currentPlansRaw[0]
        ? currentPlansRaw
        : []) as BillingPlan[],
    [currentPlansRaw],
  )

  const createTierVersions = useMemo(
    () => (Array.isArray(createTierVersionsRaw) ? createTierVersionsRaw : []) as BillingTierConfig[],
    [createTierVersionsRaw],
  )

  const migrateTierVersions = useMemo(
    () => (Array.isArray(migrateTierVersionsRaw) ? migrateTierVersionsRaw : []) as BillingTierConfig[],
    [migrateTierVersionsRaw],
  )

  const currentTierConfig = useMemo(
    () => createTierVersions.find((v) => v.isCurrent),
    [createTierVersions],
  )

  const targetTierConfig = useMemo(
    () => migrateTierVersions.find((v) => v.version === Number(targetVersion)),
    [migrateTierVersions, targetVersion],
  )

  const currentMigrateTierConfig = useMemo(
    () => migrateTierVersions.find((v) => v.isCurrent),
    [migrateTierVersions],
  )

  const updateTierVersions = useMemo(
    () => (Array.isArray(updateTierVersionsRaw) ? updateTierVersionsRaw : []) as BillingTierConfig[],
    [updateTierVersionsRaw],
  )

  const selectedUpdateTierConfig = useMemo(
    () => updateTierVersions.find((v) => v.version === Number(updateVersion)),
    [updateTierVersions, updateVersion],
  )

  const filteredSubscriptions = useMemo(() => {
    if (!subFilter) return subscriptions
    const lower = subFilter.toLowerCase()
    return subscriptions.filter(
      (s: AdminBillingSubscription) =>
        s.organizationName.toLowerCase().includes(lower) ||
        s.plan.toLowerCase().includes(lower) ||
        s.status.toLowerCase().includes(lower),
    )
  }, [subscriptions, subFilter])

  const validationErrors = useMemo(() => validateCreateForm(createForm), [createForm])
  const hasValidationErrors = Object.keys(validationErrors).length > 0

  // Unique tier names from current plans for the dropdown
  const availableTiers = useMemo(() => {
    const fromPlans = currentPlans.map((p) => p.tier.tierName)
    const combined = new Set([...KNOWN_TIERS, ...fromPlans])
    return Array.from(combined).sort()
  }, [currentPlans])

  const previewCards = useMemo(() => {
    const plansByTier = new Map(currentPlans.map((plan) => [plan.tier.tierName, plan]))
    const sourceTiers = currentPlans.map((plan) => plan.tier)
    const draftTier: PricingCardTierInput | null = currentTierConfig
      ? {
          tierName: createTier,
          monthlyPriceCents: createForm.monthlyPriceCents,
          yearlyPriceCents: createForm.yearlyPriceCents,
          trialDays: createForm.trialDays,
          monthlyGbLimit: Math.max(0, Math.round(createForm.monthlyGbLimitGb * BYTES_PER_GB)),
          retentionDays: createForm.retentionDays,
          maxProjects: createForm.maxProjects.trim() ? Number(createForm.maxProjects) : null,
          maxSystems: createForm.maxSystems,
          monitorIntervalSeconds: createForm.monitorIntervalSeconds,
          sessionReplayEnabled: currentTierConfig.sessionReplayEnabled,
          statusPagesEnabled: currentTierConfig.statusPagesEnabled,
          statusPageCustomDomainEnabled: currentTierConfig.statusPageCustomDomainEnabled,
          slackEnabled: currentTierConfig.slackEnabled,
          discordEnabled: currentTierConfig.discordEnabled,
          incidentIoEnabled: currentTierConfig.incidentIoEnabled,
          samlEnabled: currentTierConfig.samlEnabled,
          oidcEnabled: currentTierConfig.oidcEnabled,
          prioritySupportEnabled: currentTierConfig.prioritySupportEnabled,
          slaEnabled: currentTierConfig.slaEnabled,
          customRetentionEnabled: currentTierConfig.customRetentionEnabled,
        }
      : null

    const merged = sourceTiers.map((tier) => {
      if (tier.tierName === createTier && draftTier) return draftTier
      return {
        ...tier,
        trialDays: plansByTier.get(tier.tierName)?.trialDays ?? tier.trialDays,
      }
    })

    return merged
      .sort((a, b) => (TIER_ORDER[a.tierName] ?? 99) - (TIER_ORDER[b.tierName] ?? 99))
      .map((tier) => buildPricingCardModel(tier, previewInterval))
  }, [createForm, createTier, currentPlans, currentTierConfig, previewInterval])

  // ─── Pre-fill form from current tier config ───────────────────────────────

  const prefillFromConfig = useCallback(
    (config: BillingTierConfig) => {
      setCreateForm({
        monthlyErrorLimit: config.monthlyErrorLimit,
        monthlyTransactionLimit: config.monthlyTransactionLimit,
        monthlyReplayLimit: config.monthlyReplayLimit,
        monthlyFeedbackLimit: config.monthlyFeedbackLimit,
        retentionDays: config.retentionDays,
        maxProjects: config.maxProjects != null ? String(config.maxProjects) : '',
        maxSystems: config.maxSystems,
        monitorIntervalSeconds: config.monitorIntervalSeconds,
        monthlyPriceCents: config.monthlyPriceCents,
        yearlyPriceCents: config.yearlyPriceCents,
        monthlyGbLimitGb: Math.max(0, Math.round(config.monthlyGbLimit / BYTES_PER_GB)),
        trialDays: config.trialDays,
        paygEnabled: config.paygEnabled,
        paygRateMicrosPerUnit: config.paygRateMicrosPerUnit,
        stripeBasePriceId: config.stripeBasePriceId ?? '',
        stripeOveragePriceId: config.stripeOveragePriceId ?? '',
        stripeYearlyBasePriceId: config.stripeYearlyBasePriceId ?? '',
        stripeYearlyOveragePriceId: config.stripeYearlyOveragePriceId ?? '',
      })
    },
    [],
  )

  // Pre-fill when the selected tier changes
  useEffect(() => {
    if (currentTierConfig) {
      prefillFromConfig(currentTierConfig)
    }
  }, [currentTierConfig, prefillFromConfig])

  // Pre-fill update form when version is selected
  useEffect(() => {
    if (selectedUpdateTierConfig) {
      setUpdatePriceForm({
        stripeBasePriceId: selectedUpdateTierConfig.stripeBasePriceId ?? '',
        stripeOveragePriceId: selectedUpdateTierConfig.stripeOveragePriceId ?? '',
        stripeYearlyBasePriceId: selectedUpdateTierConfig.stripeYearlyBasePriceId ?? '',
        stripeYearlyOveragePriceId: selectedUpdateTierConfig.stripeYearlyOveragePriceId ?? '',
      })
    }
  }, [selectedUpdateTierConfig])

  // ─── Mutations ────────────────────────────────────────────────────────────

  const createVersionMutation = useMutation({
    mutationFn: () =>
      api.createAdminBillingTierVersion(createTier, {
        monthlyUnitLimit:
          Number(createForm.monthlyErrorLimit) +
          Number(createForm.monthlyTransactionLimit) +
          Number(createForm.monthlyReplayLimit) +
          Number(createForm.monthlyFeedbackLimit),
        monthlyErrorLimit: Number(createForm.monthlyErrorLimit),
        monthlyTransactionLimit: Number(createForm.monthlyTransactionLimit),
        monthlyReplayLimit: Number(createForm.monthlyReplayLimit),
        monthlyFeedbackLimit: Number(createForm.monthlyFeedbackLimit),
        retentionDays: Number(createForm.retentionDays),
        maxProjects: createForm.maxProjects.trim() ? Number(createForm.maxProjects) : null,
        maxSystems: Number(createForm.maxSystems),
        monitorIntervalSeconds: Number(createForm.monitorIntervalSeconds),
        monthlyPriceCents: Number(createForm.monthlyPriceCents),
        yearlyPriceCents: Number(createForm.yearlyPriceCents),
        monthlyGbLimit: Math.max(0, Math.round(createForm.monthlyGbLimitGb * BYTES_PER_GB)),
        trialDays: Number(createForm.trialDays),
        paygEnabled: Boolean(createForm.paygEnabled),
        paygRateMicrosPerUnit: Number(createForm.paygRateMicrosPerUnit),
        stripeBasePriceId: createForm.stripeBasePriceId.trim() || null,
        stripeOveragePriceId: createForm.stripeOveragePriceId.trim() || null,
        stripeYearlyBasePriceId: createForm.stripeYearlyBasePriceId.trim() || null,
        stripeYearlyOveragePriceId: createForm.stripeYearlyOveragePriceId.trim() || null,
      }),
    onSuccess: (tier) => {
      queryClient.invalidateQueries({queryKey: ['admin-billing-current-plans']})
      queryClient.invalidateQueries({queryKey: ['admin-billing-tier-versions', createTier]})
      setShowCreateConfirm(false)
      toast({title: `${createTier} v${tier.version} created successfully`})
    },
    onError: (err: Error) => {
      toast({title: 'Failed to create version', description: err.message, variant: 'destructive'})
    },
  })

  const dryRunMutation = useMutation({
    mutationFn: () => api.migrateAdminBillingTier(migrateTier, Number(targetVersion), true),
    onSuccess: (res) => {
      setDryRunResult({affected: res.affectedSubscriptions, version: res.targetVersion})
      toast({
        title: 'Dry run complete',
        description: `${res.affectedSubscriptions} subscription(s) would be migrated to v${res.targetVersion}`,
      })
    },
    onError: (err: Error) => {
      setDryRunResult(null)
      toast({title: 'Dry run failed', description: err.message, variant: 'destructive'})
    },
  })

  const executeMigrationMutation = useMutation({
    mutationFn: () => api.migrateAdminBillingTier(migrateTier, Number(targetVersion), false),
    onSuccess: (res) => {
      queryClient.invalidateQueries({queryKey: ['admin-billing-subscriptions']})
      queryClient.invalidateQueries({queryKey: ['admin-billing-tier-versions', migrateTier]})
      setShowMigrateConfirm(false)
      setDryRunResult(null)
      toast({
        title: 'Migration complete',
        description: `${res.affectedSubscriptions} subscription(s) migrated to v${res.targetVersion}`,
      })
    },
    onError: (err: Error) => {
      toast({title: 'Migration failed', description: err.message, variant: 'destructive'})
    },
  })

  const updatePriceIdsMutation = useMutation({
    mutationFn: () =>
      api.updateAdminBillingTierPriceIds(updateTier, Number(updateVersion), {
        stripeBasePriceId: updatePriceForm.stripeBasePriceId.trim() || null,
        stripeOveragePriceId: updatePriceForm.stripeOveragePriceId.trim() || null,
        stripeYearlyBasePriceId: updatePriceForm.stripeYearlyBasePriceId.trim() || null,
        stripeYearlyOveragePriceId: updatePriceForm.stripeYearlyOveragePriceId.trim() || null,
      }),
    onSuccess: () => {
      queryClient.invalidateQueries({queryKey: ['admin-billing-tier-versions', updateTier]})
      queryClient.invalidateQueries({queryKey: ['admin-billing-current-plans']})
      toast({
        title: 'Price IDs updated',
        description: `Successfully updated Stripe price IDs for ${updateTier} v${updateVersion}`,
      })
    },
    onError: (err: Error) => {
      toast({title: 'Update failed', description: err.message, variant: 'destructive'})
    },
  })

  // ─── Loading State ────────────────────────────────────────────────────────

  if (plansLoading || subscriptionsLoading) {
    return <AdminSkeleton />
  }

  // ─── Render ───────────────────────────────────────────────────────────────

  return (
    <TooltipProvider delayDuration={200}>
      <div className="space-y-6">
        <SectionHeader
          title="Billing"
          description="Manage pricing tier versions and subscriber migrations."
        />

        {/* ── Current Plan Configs ─────────────────────────────────────────── */}

        <Card>
          <CardHeader>
            <CardTitle>Current Plan Configs</CardTitle>
            <CardDescription>
              The active pricing configuration for each tier. New subscriptions and renewals use these configs.
            </CardDescription>
          </CardHeader>
          <CardContent>
            {currentPlans.length === 0 ? (
              <p className="text-sm text-muted-foreground">No plans configured yet.</p>
            ) : (
              <Table>
                <TableHeader>
                  <TableRow>
                    <TableHead>Tier</TableHead>
                    <TableHead>Version</TableHead>
                    <TableHead>Price</TableHead>
                    <TableHead>Total Limit</TableHead>
                    <TableHead>Errors</TableHead>
                    <TableHead>Transactions</TableHead>
                    <TableHead>Replays</TableHead>
                    <TableHead>Feedback</TableHead>
                    <TableHead>Retention</TableHead>
                    <TableHead>Max Systems</TableHead>
                    <TableHead>Interval</TableHead>
                    <TableHead>PAYG</TableHead>
                    <TableHead>Status</TableHead>
                  </TableRow>
                </TableHeader>
                <TableBody>
                  {currentPlans.map((plan) => (
                    <TableRow key={plan.tier.id}>
                      <TableCell className="font-medium">
                        <PlanBadge plan={plan.tier.tierName} />
                      </TableCell>
                      <TableCell>v{plan.tier.version}</TableCell>
                      <TableCell>${centsToDollars(plan.tier.monthlyPriceCents)}/mo</TableCell>
                      <TableCell>{plan.tier.monthlyUnitLimit.toLocaleString()}</TableCell>
                      <TableCell>{plan.tier.monthlyErrorLimit.toLocaleString()}</TableCell>
                      <TableCell>{plan.tier.monthlyTransactionLimit.toLocaleString()}</TableCell>
                      <TableCell>{plan.tier.monthlyReplayLimit.toLocaleString()}</TableCell>
                      <TableCell>{plan.tier.monthlyFeedbackLimit.toLocaleString()}</TableCell>
                      <TableCell>{plan.tier.retentionDays}d</TableCell>
                      <TableCell>{plan.tier.maxSystems}</TableCell>
                      <TableCell>{formatInterval(plan.tier.monitorIntervalSeconds)}</TableCell>
                      <TableCell>
                        {plan.tier.paygEnabled ? (
                          <Badge variant="outline" className="text-emerald-600 border-emerald-300">
                            Enabled
                          </Badge>
                        ) : (
                          <Badge variant="secondary">Off</Badge>
                        )}
                      </TableCell>
                      <TableCell>
                        <Badge variant={plan.tier.isCurrent ? 'default' : 'secondary'}>
                          {plan.tier.isCurrent ? 'current' : 'legacy'}
                        </Badge>
                      </TableCell>
                    </TableRow>
                  ))}
                </TableBody>
              </Table>
            )}
          </CardContent>
        </Card>

        {/* ── Create New Tier Version ──────────────────────────────────────── */}

        <Card>
          <CardHeader>
            <CardTitle>Create New Tier Version</CardTitle>
            <CardDescription>
              Creates a new version of a pricing tier and marks it as the current config.
              The previous version becomes &ldquo;legacy&rdquo; but existing subscribers
              keep their config until migrated.
            </CardDescription>
          </CardHeader>
          <CardContent className="space-y-6">
            {/* Tier selector */}
            <div className="space-y-1.5 max-w-xs">
              <Label>
                Tier
                <HelpTip text="Select which pricing tier to create a new version for. The form will pre-fill with the current version's values." />
              </Label>
              <Select value={createTier} onValueChange={setCreateTier}>
                <SelectTrigger>
                  <SelectValue />
                </SelectTrigger>
                <SelectContent>
                  {availableTiers.map((t) => (
                    <SelectItem key={t} value={t}>
                      {t}
                    </SelectItem>
                  ))}
                </SelectContent>
              </Select>
              {currentTierConfig && (
                <p className="text-xs text-muted-foreground">
                  Current version: v{currentTierConfig.version} &mdash; form pre-filled with its values
                </p>
              )}
            </div>

            <Separator />

            {/* Event Limits Section Header */}
            <div className="bg-muted/30 border border-border rounded-md p-3">
              <div className="flex items-start gap-2">
                <Info className="h-4 w-4 text-blue-500 mt-0.5 flex-shrink-0" />
                <div className="text-xs text-muted-foreground">
                  <strong>Event limits are for internal abuse prevention only.</strong> Public billing is based on GB only. 
                  Set generous values (e.g., 100K-500K for Free, very high for paid tiers). These are not advertised to customers.
                </div>
              </div>
            </div>

            {/* Form fields */}
            <div className="grid gap-4 sm:grid-cols-2">
              {/* Per-type limits */}
              <div className="space-y-1.5">
                <Label htmlFor="monthlyErrorLimit">
                  Error Limit (Internal)
                  <HelpTip text="Internal abuse limit. Not advertised. Set high for paid tiers." />
                </Label>
                <Input
                  id="monthlyErrorLimit"
                  type="number"
                  min={0}
                  max={100_000_000}
                  value={createForm.monthlyErrorLimit}
                  onChange={(e) =>
                    setCreateForm((p) => ({...p, monthlyErrorLimit: Number(e.target.value)}))
                  }
                />
                {validationErrors.monthlyErrorLimit && (
                  <p className="text-xs text-destructive">{validationErrors.monthlyErrorLimit}</p>
                )}
              </div>

              <div className="space-y-1.5">
                <Label htmlFor="monthlyTransactionLimit">
                  Transaction Limit (Internal)
                  <HelpTip text="Internal abuse limit. Not advertised. Set high for paid tiers." />
                </Label>
                <Input
                  id="monthlyTransactionLimit"
                  type="number"
                  min={0}
                  max={100_000_000}
                  value={createForm.monthlyTransactionLimit}
                  onChange={(e) =>
                    setCreateForm((p) => ({...p, monthlyTransactionLimit: Number(e.target.value)}))
                  }
                />
                {validationErrors.monthlyTransactionLimit && (
                  <p className="text-xs text-destructive">{validationErrors.monthlyTransactionLimit}</p>
                )}
              </div>

              <div className="space-y-1.5">
                <Label htmlFor="monthlyReplayLimit">
                  Replay Limit (Internal)
                  <HelpTip text="Internal abuse limit. Not advertised. Set high for paid tiers." />
                </Label>
                <Input
                  id="monthlyReplayLimit"
                  type="number"
                  min={0}
                  max={100_000_000}
                  value={createForm.monthlyReplayLimit}
                  onChange={(e) =>
                    setCreateForm((p) => ({...p, monthlyReplayLimit: Number(e.target.value)}))
                  }
                />
                {validationErrors.monthlyReplayLimit && (
                  <p className="text-xs text-destructive">{validationErrors.monthlyReplayLimit}</p>
                )}
              </div>

              <div className="space-y-1.5">
                <Label htmlFor="monthlyFeedbackLimit">
                  Feedback Limit (Internal)
                  <HelpTip text="Internal abuse limit. Not advertised. Set high for paid tiers." />
                </Label>
                <Input
                  id="monthlyFeedbackLimit"
                  type="number"
                  min={0}
                  max={100_000_000}
                  value={createForm.monthlyFeedbackLimit}
                  onChange={(e) =>
                    setCreateForm((p) => ({...p, monthlyFeedbackLimit: Number(e.target.value)}))
                  }
                />
                <FieldHint>
                  Total base limit:{' '}
                  {(
                    createForm.monthlyErrorLimit +
                    createForm.monthlyTransactionLimit +
                    createForm.monthlyReplayLimit +
                    createForm.monthlyFeedbackLimit
                  ).toLocaleString()}{' '}
                  units/month
                </FieldHint>
                {validationErrors.monthlyFeedbackLimit && (
                  <p className="text-xs text-destructive">{validationErrors.monthlyFeedbackLimit}</p>
                )}
              </div>

              {/* Retention Days */}
              <div className="space-y-1.5">
                <Label htmlFor="retentionDays">
                  Retention Days
                  <HelpTip text="How long event data is stored before automatic deletion. Retention is capped at 90 days." />
                </Label>
                <Input
                  id="retentionDays"
                  type="number"
                  min={1}
                  max={90}
                  value={createForm.retentionDays}
                  onChange={(e) =>
                    setCreateForm((p) => ({...p, retentionDays: Number(e.target.value)}))
                  }
                />
                <FieldHint>
                  {createForm.retentionDays} {createForm.retentionDays === 1 ? 'day' : 'days'} of data retention
                </FieldHint>
                {validationErrors.retentionDays && (
                  <p className="text-xs text-destructive">{validationErrors.retentionDays}</p>
                )}
              </div>

              {/* Max Projects */}
              <div className="space-y-1.5">
                <Label htmlFor="maxProjects">
                  Max Projects
                  <HelpTip text="Maximum number of projects an organization on this tier can create. Leave blank for unlimited." />
                </Label>
                <Input
                  id="maxProjects"
                  type="text"
                  placeholder="Unlimited"
                  value={createForm.maxProjects}
                  onChange={(e) => {
                    const val = e.target.value.replace(/[^0-9]/g, '')
                    setCreateForm((p) => ({...p, maxProjects: val}))
                  }}
                />
                <FieldHint>{createForm.maxProjects ? `${createForm.maxProjects} projects` : 'Unlimited projects'}</FieldHint>
              </div>

              {/* Max Systems */}
              <div className="space-y-1.5">
                <Label htmlFor="maxSystems">
                  Max Systems
                  <HelpTip text="Maximum number of monitored systems (servers, containers, etc.) allowed on this tier." />
                </Label>
                <Input
                  id="maxSystems"
                  type="number"
                  min={1}
                  value={createForm.maxSystems}
                  onChange={(e) =>
                    setCreateForm((p) => ({...p, maxSystems: Number(e.target.value)}))
                  }
                />
                {validationErrors.maxSystems && (
                  <p className="text-xs text-destructive">{validationErrors.maxSystems}</p>
                )}
              </div>

              {/* Monitor Interval */}
              <div className="space-y-1.5">
                <Label htmlFor="monitorIntervalSeconds">
                  Monitor Interval
                  <HelpTip text="How frequently systems are polled for monitoring data. Lower values = more frequent checks = more unit usage." />
                </Label>
                <Input
                  id="monitorIntervalSeconds"
                  type="number"
                  min={5}
                  max={3600}
                  value={createForm.monitorIntervalSeconds}
                  onChange={(e) =>
                    setCreateForm((p) => ({...p, monitorIntervalSeconds: Number(e.target.value)}))
                  }
                />
                <FieldHint>
                  Checks every {formatInterval(createForm.monitorIntervalSeconds)}
                </FieldHint>
                {validationErrors.monitorIntervalSeconds && (
                  <p className="text-xs text-destructive">{validationErrors.monitorIntervalSeconds}</p>
                )}
              </div>

              {/* Monthly Price */}
              <div className="space-y-1.5">
                <Label htmlFor="monthlyPriceCents">
                  Monthly Price
                  <HelpTip text="The base subscription price in US dollars charged monthly via Stripe." />
                </Label>
                <div className="relative">
                  <span className="absolute left-3 top-1/2 -translate-y-1/2 text-sm text-muted-foreground">$</span>
                  <Input
                    id="monthlyPriceCents"
                    type="number"
                    min={0}
                    step={100}
                    className="pl-7"
                    value={createForm.monthlyPriceCents}
                    onChange={(e) =>
                      setCreateForm((p) => ({...p, monthlyPriceCents: Number(e.target.value)}))
                    }
                  />
                </div>
                <FieldHint>
                  ${centsToDollars(createForm.monthlyPriceCents)} / month ({createForm.monthlyPriceCents} cents)
                </FieldHint>
                {validationErrors.monthlyPriceCents && (
                  <p className="text-xs text-destructive">{validationErrors.monthlyPriceCents}</p>
                )}
              </div>

              {/* Yearly Price */}
              <div className="space-y-1.5">
                <Label htmlFor="yearlyPriceCents">
                  Yearly Price
                  <HelpTip text="Total yearly subscription price in US dollars charged once per year." />
                </Label>
                <div className="relative">
                  <span className="absolute left-3 top-1/2 -translate-y-1/2 text-sm text-muted-foreground">$</span>
                  <Input
                    id="yearlyPriceCents"
                    type="number"
                    min={0}
                    step={100}
                    className="pl-7"
                    value={createForm.yearlyPriceCents}
                    onChange={(e) =>
                      setCreateForm((p) => ({...p, yearlyPriceCents: Number(e.target.value)}))
                    }
                  />
                </div>
                <FieldHint>
                  ${centsToDollars(createForm.yearlyPriceCents)} / year (
                  ${(createForm.yearlyPriceCents / (100 * 12)).toFixed(0)} / month effective)
                </FieldHint>
                {validationErrors.yearlyPriceCents && (
                  <p className="text-xs text-destructive">{validationErrors.yearlyPriceCents}</p>
                )}
              </div>

              {/* Monthly Data Limit */}
              <div className="space-y-1.5">
                <Label htmlFor="monthlyGbLimitGb">
                  Monthly Data Limit (GB)
                  <HelpTip text="Customer-facing monthly GB quota used in pricing cards and quota enforcement." />
                </Label>
                <Input
                  id="monthlyGbLimitGb"
                  type="number"
                  min={0}
                  step={1}
                  value={createForm.monthlyGbLimitGb}
                  onChange={(e) =>
                    setCreateForm((p) => ({...p, monthlyGbLimitGb: Number(e.target.value)}))
                  }
                />
                {validationErrors.monthlyGbLimitGb && (
                  <p className="text-xs text-destructive">{validationErrors.monthlyGbLimitGb}</p>
                )}
              </div>

              {/* Trial Days */}
              <div className="space-y-1.5">
                <Label htmlFor="trialDays">
                  Trial Days
                  <HelpTip text="Days shown in CTA for paid plans and used for checkout trial defaults." />
                </Label>
                <Input
                  id="trialDays"
                  type="number"
                  min={0}
                  max={60}
                  value={createForm.trialDays}
                  onChange={(e) =>
                    setCreateForm((p) => ({...p, trialDays: Number(e.target.value)}))
                  }
                />
                {validationErrors.trialDays && (
                  <p className="text-xs text-destructive">{validationErrors.trialDays}</p>
                )}
              </div>
            </div>

            <Separator />

            {/* PAYG Section */}
            <div className="space-y-4">
              <div className="flex items-center gap-3">
                <Switch
                  id="paygEnabled"
                  checked={createForm.paygEnabled}
                  onCheckedChange={(checked) =>
                    setCreateForm((p) => ({...p, paygEnabled: checked}))
                  }
                />
                <Label htmlFor="paygEnabled" className="cursor-pointer">
                  Enable Pay-As-You-Go (PAYG) overage
                  <HelpTip text="When enabled, subscribers can set a budget to continue using units beyond their base limit at a per-unit rate." />
                </Label>
              </div>

              {createForm.paygEnabled && (
                <div className="grid gap-4 sm:grid-cols-2 pl-4 border-l-2 border-muted">
                  <div className="space-y-1.5">
                    <Label htmlFor="paygRate">
                      PAYG Rate
                      <HelpTip text="Cost per overage unit in microdollars (1 micro = $0.000001). For example, 10 micros/unit means $0.00001 per unit." />
                    </Label>
                    <Input
                      id="paygRate"
                      type="number"
                      min={1}
                      value={createForm.paygRateMicrosPerUnit}
                      onChange={(e) =>
                        setCreateForm((p) => ({...p, paygRateMicrosPerUnit: Number(e.target.value)}))
                      }
                    />
                    <FieldHint>
                      {createForm.paygRateMicrosPerUnit} micros/unit = ${microsToDollars(createForm.paygRateMicrosPerUnit)}/unit
                    </FieldHint>
                    {validationErrors.paygRateMicrosPerUnit && (
                      <p className="text-xs text-destructive">{validationErrors.paygRateMicrosPerUnit}</p>
                    )}
                  </div>
                </div>
              )}
            </div>

            <Separator />

            {/* Stripe IDs */}
            <div className="space-y-3">
              <p className="text-sm font-medium flex items-center gap-1">
                Stripe Configuration
                <HelpTip text="These IDs link this tier to Stripe products and prices. Find them in your Stripe dashboard under Products > Prices." />
              </p>
              <div className="grid gap-4 sm:grid-cols-2">
                <div className="space-y-1.5">
                  <Label htmlFor="stripeBasePriceId">Stripe Base Price ID (Monthly)</Label>
                  <Input
                    id="stripeBasePriceId"
                    placeholder="price_..."
                    value={createForm.stripeBasePriceId}
                    onChange={(e) =>
                      setCreateForm((p) => ({...p, stripeBasePriceId: e.target.value}))
                    }
                  />
                  <FieldHint>The Stripe Price ID for the base monthly subscription</FieldHint>
                </div>
                <div className="space-y-1.5">
                  <Label htmlFor="stripeOveragePriceId">Stripe Overage Price ID (Monthly)</Label>
                  <Input
                    id="stripeOveragePriceId"
                    placeholder="price_..."
                    value={createForm.stripeOveragePriceId}
                    onChange={(e) =>
                      setCreateForm((p) => ({...p, stripeOveragePriceId: e.target.value}))
                    }
                  />
                  <FieldHint>The Stripe Price ID for metered PAYG overage charges</FieldHint>
                </div>
                <div className="space-y-1.5">
                  <Label htmlFor="stripeYearlyBasePriceId">Stripe Base Price ID (Yearly)</Label>
                  <Input
                    id="stripeYearlyBasePriceId"
                    placeholder="price_..."
                    value={createForm.stripeYearlyBasePriceId}
                    onChange={(e) =>
                      setCreateForm((p) => ({...p, stripeYearlyBasePriceId: e.target.value}))
                    }
                  />
                  <FieldHint>The Stripe Price ID for the base yearly subscription</FieldHint>
                </div>
                <div className="space-y-1.5">
                  <Label htmlFor="stripeYearlyOveragePriceId">Stripe Overage Price ID (Yearly)</Label>
                  <Input
                    id="stripeYearlyOveragePriceId"
                    placeholder="price_..."
                    value={createForm.stripeYearlyOveragePriceId}
                    onChange={(e) =>
                      setCreateForm((p) => ({...p, stripeYearlyOveragePriceId: e.target.value}))
                    }
                  />
                  <FieldHint>The Stripe Price ID for yearly metered PAYG overage charges</FieldHint>
                </div>
              </div>
            </div>

            <Separator />

            {/* Changes summary vs current version */}
            {currentTierConfig && (
              <ChangeSummary current={currentTierConfig} form={createForm} />
            )}

            <Separator />

            <div className="space-y-3">
              <div className="flex items-center justify-between">
                <p className="text-sm font-medium">Pricing Preview</p>
                <div className="inline-flex items-center rounded-lg border border-border/60 bg-muted/30 p-1">
                  <button
                    onClick={() => setPreviewInterval('monthly')}
                    className={`relative rounded-md px-3 py-1.5 text-xs font-medium transition-all ${
                      previewInterval === 'monthly'
                        ? 'bg-background text-foreground shadow-sm'
                        : 'text-muted-foreground hover:text-foreground'
                    }`}
                    type="button"
                  >
                    Monthly
                  </button>
                  <button
                    onClick={() => setPreviewInterval('yearly')}
                    className={`relative rounded-md px-3 py-1.5 text-xs font-medium transition-all ${
                      previewInterval === 'yearly'
                        ? 'bg-background text-foreground shadow-sm'
                        : 'text-muted-foreground hover:text-foreground'
                    }`}
                    type="button"
                  >
                    Yearly
                  </button>
                </div>
              </div>
              {previewCards.length === 0 ? (
                <p className="text-xs text-muted-foreground">
                  No current plans available to preview yet.
                </p>
              ) : (
                <PricingPreviewGrid cards={previewCards} interval={previewInterval} />
              )}
            </div>

            {/* Create button */}
            <div className="flex items-center gap-3">
              <Button
                onClick={() => setShowCreateConfirm(true)}
                disabled={createVersionMutation.isPending || hasValidationErrors}
              >
                Review & Create Version
              </Button>
              {hasValidationErrors && (
                <p className="text-sm text-destructive flex items-center gap-1">
                  <AlertTriangle className="h-4 w-4" />
                  Fix validation errors above before creating
                </p>
              )}
            </div>
          </CardContent>
        </Card>

        {/* ── Create Confirmation Dialog ───────────────────────────────────── */}

        <Dialog open={showCreateConfirm} onOpenChange={setShowCreateConfirm}>
          <DialogContent>
            <DialogHeader>
              <DialogTitle>Confirm New Tier Version</DialogTitle>
              <DialogDescription>
                You are about to create a new version of the <strong>{createTier}</strong> tier.
                This will become the active config for new subscriptions.
                {currentTierConfig && (
                  <> The current version (v{currentTierConfig.version}) will be marked as legacy.</>
                )}
              </DialogDescription>
            </DialogHeader>

            <div className="space-y-3 my-2">
              <h4 className="text-sm font-medium">New configuration:</h4>
              <div className="rounded border bg-muted/50 p-3 text-sm space-y-1">
                <p><strong>Tier:</strong> {createTier}</p>
                <p><strong>Monthly Price:</strong> ${centsToDollars(createForm.monthlyPriceCents)}/mo</p>
                <p><strong>Yearly Price:</strong> ${centsToDollars(createForm.yearlyPriceCents)}/yr</p>
                <p><strong>Monthly Data Limit:</strong> {createForm.monthlyGbLimitGb} GB</p>
                <p><strong>Trial:</strong> {createForm.trialDays} day(s)</p>
                <p><strong>Total Limit:</strong> {(
                  createForm.monthlyErrorLimit +
                  createForm.monthlyTransactionLimit +
                  createForm.monthlyReplayLimit +
                  createForm.monthlyFeedbackLimit
                ).toLocaleString()}</p>
                <p><strong>Errors:</strong> {createForm.monthlyErrorLimit.toLocaleString()}</p>
                <p><strong>Transactions:</strong> {createForm.monthlyTransactionLimit.toLocaleString()}</p>
                <p><strong>Replays:</strong> {createForm.monthlyReplayLimit.toLocaleString()}</p>
                <p><strong>Feedback:</strong> {createForm.monthlyFeedbackLimit.toLocaleString()}</p>
                <p><strong>Retention:</strong> {createForm.retentionDays} days</p>
                <p><strong>Max Projects:</strong> {createForm.maxProjects || 'Unlimited'}</p>
                <p><strong>Max Systems:</strong> {createForm.maxSystems}</p>
                <p><strong>Monitor Interval:</strong> {formatInterval(createForm.monitorIntervalSeconds)}</p>
                <p><strong>PAYG:</strong> {createForm.paygEnabled ? `Enabled (${createForm.paygRateMicrosPerUnit} micros/unit)` : 'Disabled'}</p>
              </div>
              <div className="flex items-start gap-2 rounded border border-amber-200 bg-amber-50 dark:border-amber-800 dark:bg-amber-950 p-3">
                <Info className="h-4 w-4 text-amber-600 mt-0.5 shrink-0" />
                <p className="text-sm text-amber-800 dark:text-amber-200">
                  Existing subscribers will <strong>not</strong> be affected until you run a migration.
                </p>
              </div>
            </div>

            <DialogFooter>
              <Button variant="outline" onClick={() => setShowCreateConfirm(false)}>
                Cancel
              </Button>
              <Button
                onClick={() => createVersionMutation.mutate()}
                disabled={createVersionMutation.isPending}
              >
                {createVersionMutation.isPending ? 'Creating...' : 'Confirm & Create'}
              </Button>
            </DialogFooter>
          </DialogContent>
        </Dialog>

        {/* ── Update Stripe Price IDs ──────────────────────────────────────── */}

        <Card>
          <CardHeader>
            <CardTitle>Update Stripe Price IDs</CardTitle>
            <CardDescription>
              Update the Stripe price IDs for an existing tier version without creating a new version.
              Useful for fixing mistakes or updating price IDs after creating them in Stripe.
            </CardDescription>
          </CardHeader>
          <CardContent className="space-y-4">
            <div className="grid gap-4 sm:grid-cols-2">
              <div className="space-y-1.5">
                <Label>
                  Tier
                  <HelpTip text="Select which tier to update" />
                </Label>
                <Select value={updateTier} onValueChange={(v) => { setUpdateTier(v); setUpdateVersion('') }}>
                  <SelectTrigger>
                    <SelectValue />
                  </SelectTrigger>
                  <SelectContent>
                    {availableTiers.map((t) => (
                      <SelectItem key={t} value={t}>
                        {t}
                      </SelectItem>
                    ))}
                  </SelectContent>
                </Select>
              </div>

              <div className="space-y-1.5">
                <Label>
                  Version
                  <HelpTip text="Select which version to update" />
                </Label>
                {updateTierVersions.length > 0 ? (
                  <Select
                    value={updateVersion !== '' ? String(updateVersion) : undefined}
                    onValueChange={(v) => setUpdateVersion(Number(v))}
                  >
                    <SelectTrigger>
                      <SelectValue placeholder="Select version..." />
                    </SelectTrigger>
                    <SelectContent>
                      {updateTierVersions.map((v) => (
                        <SelectItem key={v.id} value={String(v.version)}>
                          v{v.version} {v.isCurrent ? '(current)' : '(legacy)'}
                        </SelectItem>
                      ))}
                    </SelectContent>
                  </Select>
                ) : (
                  <p className="text-sm text-muted-foreground py-2">No versions found for {updateTier}</p>
                )}
              </div>
            </div>

            {selectedUpdateTierConfig && (
              <>
                <div className="rounded border bg-muted/50 p-3 text-sm space-y-1">
                  <p className="font-medium mb-1">Current configuration:</p>
                  <p>Tier: {selectedUpdateTierConfig.tierName} v{selectedUpdateTierConfig.version}</p>
                  <p>Monthly Price: ${centsToDollars(selectedUpdateTierConfig.monthlyPriceCents)}/mo</p>
                  <p>Yearly Price: ${centsToDollars(selectedUpdateTierConfig.yearlyPriceCents)}/yr</p>
                  <p>Status: {selectedUpdateTierConfig.isCurrent ? 'Current' : 'Legacy'}</p>
                </div>

                <Separator />

                <div className="space-y-4">
                  <p className="text-sm font-medium">Stripe Price IDs</p>
                  <div className="grid gap-4 sm:grid-cols-2">
                    <div className="space-y-1.5">
                      <Label htmlFor="updateStripeBasePriceId">Monthly Base Price ID</Label>
                      <Input
                        id="updateStripeBasePriceId"
                        placeholder="price_..."
                        value={updatePriceForm.stripeBasePriceId}
                        onChange={(e) =>
                          setUpdatePriceForm((p) => ({ ...p, stripeBasePriceId: e.target.value }))
                        }
                      />
                      <FieldHint>Stripe Price ID for monthly base subscription</FieldHint>
                    </div>

                    <div className="space-y-1.5">
                      <Label htmlFor="updateStripeOveragePriceId">Monthly Overage Price ID</Label>
                      <Input
                        id="updateStripeOveragePriceId"
                        placeholder="price_..."
                        value={updatePriceForm.stripeOveragePriceId}
                        onChange={(e) =>
                          setUpdatePriceForm((p) => ({ ...p, stripeOveragePriceId: e.target.value }))
                        }
                      />
                      <FieldHint>Stripe Price ID for monthly metered overage</FieldHint>
                    </div>

                    <div className="space-y-1.5">
                      <Label htmlFor="updateStripeYearlyBasePriceId">Yearly Base Price ID</Label>
                      <Input
                        id="updateStripeYearlyBasePriceId"
                        placeholder="price_..."
                        value={updatePriceForm.stripeYearlyBasePriceId}
                        onChange={(e) =>
                          setUpdatePriceForm((p) => ({ ...p, stripeYearlyBasePriceId: e.target.value }))
                        }
                      />
                      <FieldHint>Stripe Price ID for yearly base subscription</FieldHint>
                    </div>

                    <div className="space-y-1.5">
                      <Label htmlFor="updateStripeYearlyOveragePriceId">Yearly Overage Price ID</Label>
                      <Input
                        id="updateStripeYearlyOveragePriceId"
                        placeholder="price_..."
                        value={updatePriceForm.stripeYearlyOveragePriceId}
                        onChange={(e) =>
                          setUpdatePriceForm((p) => ({ ...p, stripeYearlyOveragePriceId: e.target.value }))
                        }
                      />
                      <FieldHint>Stripe Price ID for yearly metered overage</FieldHint>
                    </div>
                  </div>

                  <Button
                    onClick={() => updatePriceIdsMutation.mutate()}
                    disabled={updatePriceIdsMutation.isPending || updateVersion === ''}
                  >
                    {updatePriceIdsMutation.isPending ? 'Updating...' : 'Update Price IDs'}
                  </Button>
                </div>
              </>
            )}
          </CardContent>
        </Card>

        {/* ── Migrate Subscribers ──────────────────────────────────────────── */}

        <Card>
          <CardHeader>
            <CardTitle>Migrate Subscribers</CardTitle>
            <CardDescription>
              Move existing subscribers from one tier version to another.
              Always run a dry run first to see how many subscriptions will be affected.
            </CardDescription>
          </CardHeader>
          <CardContent className="space-y-4">
            <div className="grid gap-4 sm:grid-cols-2">
              {/* Migrate tier selector (independent from create) */}
              <div className="space-y-1.5">
                <Label>
                  Tier to Migrate
                  <HelpTip text="Select which tier's subscribers you want to migrate to a new version." />
                </Label>
                <Select value={migrateTier} onValueChange={(v) => { setMigrateTier(v); setDryRunResult(null); setTargetVersion('') }}>
                  <SelectTrigger>
                    <SelectValue />
                  </SelectTrigger>
                  <SelectContent>
                    {availableTiers.map((t) => (
                      <SelectItem key={t} value={t}>
                        {t}
                      </SelectItem>
                    ))}
                  </SelectContent>
                </Select>
                {currentMigrateTierConfig && (
                  <FieldHint>
                    Currently on v{currentMigrateTierConfig.version}
                  </FieldHint>
                )}
              </div>

              {/* Target version selector */}
              <div className="space-y-1.5">
                <Label>
                  Target Version
                  <HelpTip text="The version to migrate subscribers to. Only versions that exist for this tier are shown." />
                </Label>
                {migrateTierVersions.length > 0 ? (
                  <Select
                    value={targetVersion !== '' ? String(targetVersion) : undefined}
                    onValueChange={(v) => { setTargetVersion(Number(v)); setDryRunResult(null) }}
                  >
                    <SelectTrigger>
                      <SelectValue placeholder="Select version..." />
                    </SelectTrigger>
                    <SelectContent>
                      {migrateTierVersions.map((v) => (
                        <SelectItem key={v.id} value={String(v.version)}>
                          v{v.version} {v.isCurrent ? '(current)' : '(legacy)'} &mdash; ${centsToDollars(v.monthlyPriceCents)}/mo, {v.monthlyUnitLimit.toLocaleString()} total units
                        </SelectItem>
                      ))}
                    </SelectContent>
                  </Select>
                ) : (
                  <p className="text-sm text-muted-foreground py-2">No versions found for {migrateTier}</p>
                )}
              </div>
            </div>

            {/* Target version details */}
            {targetTierConfig && (
              <div className="rounded border bg-muted/50 p-3 text-sm space-y-1">
                <p className="font-medium mb-1">Target v{targetTierConfig.version} details:</p>
                <p>Price: ${centsToDollars(targetTierConfig.monthlyPriceCents)}/mo</p>
                <p>Total Limit: {targetTierConfig.monthlyUnitLimit.toLocaleString()}</p>
                <p>Errors: {targetTierConfig.monthlyErrorLimit.toLocaleString()}</p>
                <p>Transactions: {targetTierConfig.monthlyTransactionLimit.toLocaleString()}</p>
                <p>Replays: {targetTierConfig.monthlyReplayLimit.toLocaleString()}</p>
                <p>Feedback: {targetTierConfig.monthlyFeedbackLimit.toLocaleString()}</p>
                <p>Retention: {targetTierConfig.retentionDays} days</p>
                <p>Max Systems: {targetTierConfig.maxSystems}</p>
                <p>PAYG: {targetTierConfig.paygEnabled ? 'Enabled' : 'Disabled'}</p>
              </div>
            )}

            {/* Dry run result */}
            {dryRunResult && (
              <div className="flex items-start gap-2 rounded border border-blue-200 bg-blue-50 dark:border-blue-800 dark:bg-blue-950 p-3">
                <Info className="h-4 w-4 text-blue-600 mt-0.5 shrink-0" />
                <p className="text-sm text-blue-800 dark:text-blue-200">
                  Dry run result: <strong>{dryRunResult.affected} subscription(s)</strong> would be
                  migrated to v{dryRunResult.version}.
                </p>
              </div>
            )}

            {/* Action buttons */}
            <div className="flex items-center gap-2">
              <Button
                variant="outline"
                onClick={() => dryRunMutation.mutate()}
                disabled={dryRunMutation.isPending || targetVersion === ''}
              >
                {dryRunMutation.isPending ? 'Running...' : 'Dry Run'}
              </Button>
              <Button
                variant="default"
                onClick={() => setShowMigrateConfirm(true)}
                disabled={
                  executeMigrationMutation.isPending ||
                  targetVersion === '' ||
                  !dryRunResult
                }
              >
                Execute Migration
              </Button>
              {!dryRunResult && targetVersion !== '' && (
                <p className="text-xs text-muted-foreground flex items-center gap-1">
                  <Info className="h-3.5 w-3.5" />
                  Run a dry run first to enable migration
                </p>
              )}
            </div>
          </CardContent>
        </Card>

        {/* ── Migration Confirmation Dialog ────────────────────────────────── */}

        <Dialog open={showMigrateConfirm} onOpenChange={setShowMigrateConfirm}>
          <DialogContent>
            <DialogHeader>
              <DialogTitle className="flex items-center gap-2">
                <AlertTriangle className="h-5 w-5 text-amber-500" />
                Confirm Migration
              </DialogTitle>
              <DialogDescription>
                This action will migrate <strong>{dryRunResult?.affected ?? 0} subscription(s)</strong> on
                the <strong>{migrateTier}</strong> tier to version <strong>v{targetVersion}</strong>.
              </DialogDescription>
            </DialogHeader>

            <div className="space-y-3 my-2">
              <div className="flex items-start gap-2 rounded border border-destructive/30 bg-destructive/10 p-3">
                <AlertTriangle className="h-4 w-4 text-destructive mt-0.5 shrink-0" />
                <div className="text-sm space-y-1">
                  <p className="font-medium text-destructive">This action cannot be undone easily.</p>
                  <p className="text-muted-foreground">
                    All affected subscribers will immediately use the new tier config.
                    Their limits, retention, and pricing may change.
                  </p>
                </div>
              </div>
            </div>

            <DialogFooter>
              <Button variant="outline" onClick={() => setShowMigrateConfirm(false)}>
                Cancel
              </Button>
              <Button
                variant="destructive"
                onClick={() => executeMigrationMutation.mutate()}
                disabled={executeMigrationMutation.isPending}
              >
                {executeMigrationMutation.isPending ? 'Migrating...' : `Migrate ${dryRunResult?.affected ?? 0} Subscription(s)`}
              </Button>
            </DialogFooter>
          </DialogContent>
        </Dialog>

        {/* ── Subscriptions ────────────────────────────────────────────────── */}

        <Card>
          <CardHeader>
            <div className="flex items-center justify-between flex-wrap gap-3">
              <div>
                <CardTitle>Subscriptions</CardTitle>
                <CardDescription>
                  Current billing status and PAYG counters for all organizations.
                  {subscriptions.length > 0 && ` Showing ${filteredSubscriptions.length} of ${subscriptions.length}.`}
                </CardDescription>
              </div>
              <Input
                placeholder="Filter by org, plan, or status..."
                className="max-w-xs"
                value={subFilter}
                onChange={(e) => setSubFilter(e.target.value)}
              />
            </div>
          </CardHeader>
          <CardContent>
            {filteredSubscriptions.length === 0 ? (
              <p className="text-sm text-muted-foreground py-4 text-center">
                {subFilter ? 'No subscriptions match your filter.' : 'No subscriptions found.'}
              </p>
            ) : (
              <Table>
                <TableHeader>
                  <TableRow>
                    <TableHead>Organization</TableHead>
                    <TableHead>Plan</TableHead>
                    <TableHead>Status</TableHead>
                    <TableHead>PAYG Budget</TableHead>
                    <TableHead>PAYG Used</TableHead>
                    <TableHead>Pending Meter</TableHead>
                    <TableHead>Period</TableHead>
                  </TableRow>
                </TableHeader>
                <TableBody>
                  {filteredSubscriptions.slice(0, 50).map((sub: AdminBillingSubscription) => (
                    <TableRow key={sub.subscriptionId}>
                      <TableCell className="font-medium">{sub.organizationName}</TableCell>
                      <TableCell>
                        <PlanBadge plan={sub.plan} />
                      </TableCell>
                      <TableCell>
                        <Badge
                          variant={
                            sub.status === 'active' || sub.status === 'trialing'
                              ? 'default'
                              : sub.status === 'past_due'
                                ? 'destructive'
                                : 'secondary'
                          }
                        >
                          {sub.status}
                        </Badge>
                      </TableCell>
                      <TableCell>${centsToDollars(sub.paygBudgetCents)}</TableCell>
                      <TableCell>
                        {sub.paygUsedUnits.toLocaleString()} units
                        <span className="text-xs text-muted-foreground ml-1">
                          (${(sub.paygUsedMicros / 1_000_000).toFixed(2)})
                        </span>
                      </TableCell>
                      <TableCell>{sub.pendingMeterUnits.toLocaleString()}</TableCell>
                      <TableCell className="text-xs text-muted-foreground">
                        {sub.currentPeriodStart && sub.currentPeriodEnd ? (
                          <>
                            {new Date(sub.currentPeriodStart).toLocaleDateString()} &ndash;{' '}
                            {new Date(sub.currentPeriodEnd).toLocaleDateString()}
                          </>
                        ) : (
                          '—'
                        )}
                      </TableCell>
                    </TableRow>
                  ))}
                </TableBody>
              </Table>
            )}
            {filteredSubscriptions.length > 50 && (
              <p className="text-xs text-muted-foreground text-center mt-3">
                Showing first 50 of {filteredSubscriptions.length} results. Use the filter to narrow down.
              </p>
            )}
          </CardContent>
        </Card>
      </div>
    </TooltipProvider>
  )
}

// ─── Change Summary Component ─────────────────────────────────────────────────

function ChangeSummary({current, form}: {current: BillingTierConfig; form: CreateFormState}) {
  const changes: Array<{field: string; from: string; to: string}> = []
  const formTotalLimit =
    form.monthlyErrorLimit +
    form.monthlyTransactionLimit +
    form.monthlyReplayLimit +
    form.monthlyFeedbackLimit

  if (current.monthlyUnitLimit !== formTotalLimit) {
    changes.push({
      field: 'Total Unit Limit',
      from: current.monthlyUnitLimit.toLocaleString(),
      to: Number(formTotalLimit).toLocaleString(),
    })
  }
  if (current.monthlyErrorLimit !== form.monthlyErrorLimit) {
    changes.push({
      field: 'Error Limit',
      from: current.monthlyErrorLimit.toLocaleString(),
      to: form.monthlyErrorLimit.toLocaleString(),
    })
  }
  if (current.monthlyTransactionLimit !== form.monthlyTransactionLimit) {
    changes.push({
      field: 'Transaction Limit',
      from: current.monthlyTransactionLimit.toLocaleString(),
      to: form.monthlyTransactionLimit.toLocaleString(),
    })
  }
  if (current.monthlyReplayLimit !== form.monthlyReplayLimit) {
    changes.push({
      field: 'Replay Limit',
      from: current.monthlyReplayLimit.toLocaleString(),
      to: form.monthlyReplayLimit.toLocaleString(),
    })
  }
  if (current.monthlyFeedbackLimit !== form.monthlyFeedbackLimit) {
    changes.push({
      field: 'Feedback Limit',
      from: current.monthlyFeedbackLimit.toLocaleString(),
      to: form.monthlyFeedbackLimit.toLocaleString(),
    })
  }
  if (current.retentionDays !== form.retentionDays) {
    changes.push({
      field: 'Retention Days',
      from: `${current.retentionDays}d`,
      to: `${form.retentionDays}d`,
    })
  }
  const formMaxProjects = form.maxProjects.trim() ? Number(form.maxProjects) : null
  if (current.maxProjects !== formMaxProjects) {
    changes.push({
      field: 'Max Projects',
      from: current.maxProjects != null ? String(current.maxProjects) : 'Unlimited',
      to: formMaxProjects != null ? String(formMaxProjects) : 'Unlimited',
    })
  }
  if (current.maxSystems !== form.maxSystems) {
    changes.push({
      field: 'Max Systems',
      from: String(current.maxSystems),
      to: String(form.maxSystems),
    })
  }
  if (current.monitorIntervalSeconds !== form.monitorIntervalSeconds) {
    changes.push({
      field: 'Monitor Interval',
      from: formatInterval(current.monitorIntervalSeconds),
      to: formatInterval(form.monitorIntervalSeconds),
    })
  }
  if (current.monthlyPriceCents !== form.monthlyPriceCents) {
    changes.push({
      field: 'Monthly Price',
      from: `$${centsToDollars(current.monthlyPriceCents)}`,
      to: `$${centsToDollars(form.monthlyPriceCents)}`,
    })
  }
  if (current.yearlyPriceCents !== form.yearlyPriceCents) {
    changes.push({
      field: 'Yearly Price',
      from: `$${centsToDollars(current.yearlyPriceCents)}`,
      to: `$${centsToDollars(form.yearlyPriceCents)}`,
    })
  }
  const formMonthlyGbLimit = Math.max(0, Math.round(form.monthlyGbLimitGb * BYTES_PER_GB))
  if (current.monthlyGbLimit !== formMonthlyGbLimit) {
    changes.push({
      field: 'Monthly Data Limit',
      from: `${Math.round(current.monthlyGbLimit / BYTES_PER_GB)} GB`,
      to: `${Math.round(form.monthlyGbLimitGb)} GB`,
    })
  }
  if (current.trialDays !== form.trialDays) {
    changes.push({
      field: 'Trial Days',
      from: `${current.trialDays}`,
      to: `${form.trialDays}`,
    })
  }
  if (current.paygEnabled !== form.paygEnabled) {
    changes.push({
      field: 'PAYG',
      from: current.paygEnabled ? 'Enabled' : 'Disabled',
      to: form.paygEnabled ? 'Enabled' : 'Disabled',
    })
  }
  if (current.paygRateMicrosPerUnit !== form.paygRateMicrosPerUnit) {
    changes.push({
      field: 'PAYG Rate',
      from: `${current.paygRateMicrosPerUnit} micros`,
      to: `${form.paygRateMicrosPerUnit} micros`,
    })
  }

  if (changes.length === 0) {
    return (
      <div className="flex items-center gap-2 rounded border p-3 text-sm text-muted-foreground">
        <Info className="h-4 w-4 shrink-0" />
        No changes from current v{current.version}. Modify the fields above to see a diff.
      </div>
    )
  }

  return (
    <div className="rounded border p-3 space-y-2">
      <p className="text-sm font-medium">
        Changes from current v{current.version}:
      </p>
      <div className="space-y-1">
        {changes.map((c) => (
          <div key={c.field} className="text-sm flex items-center gap-2">
            <span className="text-muted-foreground w-36 shrink-0">{c.field}:</span>
            <span className="text-red-600 dark:text-red-400 line-through">{c.from}</span>
            <span className="text-muted-foreground">&rarr;</span>
            <span className="text-emerald-600 dark:text-emerald-400 font-medium">{c.to}</span>
          </div>
        ))}
      </div>
    </div>
  )
}

function PricingPreviewGrid({
  cards,
  interval,
}: {
  cards: ReturnType<typeof buildPricingCardModel>[]
  interval: BillingInterval
}) {
  return (
    <div className="grid gap-3 md:grid-cols-2 xl:grid-cols-4">
      {cards.map((tier) => (
        <Card
          key={tier.tierName}
          className={tier.highlight ? 'relative border-sky-500/50 shadow-md shadow-sky-500/10' : 'border-border/60'}
        >
          {tier.highlight && (
            <div className="absolute -top-3 left-1/2 -translate-x-1/2">
              <span className="inline-flex items-center rounded-full bg-gradient-to-r from-sky-500 to-cyan-400 px-3 py-1 text-[10px] font-semibold text-white">
                Most popular
              </span>
            </div>
          )}
          <CardHeader className="pb-3">
            <CardTitle className="text-base">{tier.name}</CardTitle>
            <CardDescription className="text-xs">{tier.description}</CardDescription>
            <div className="mt-2">
              <span className="text-3xl font-bold">${tier.displayPrice === 0 ? '0' : tier.displayPrice.toFixed(0)}</span>
              <span className="text-muted-foreground text-xs">
                /mo
                {interval === 'yearly' && tier.displayPrice > 0 && (
                  <span className="block text-[11px] mt-1">billed ${tier.yearlyTotalPrice.toFixed(0)}/yr</span>
                )}
              </span>
            </div>
          </CardHeader>
          <CardContent className="space-y-2">
            <ul className="space-y-1.5">
              {tier.features.slice(0, 6).map((feature) => (
                <li key={feature} className="flex items-start gap-2">
                  <div className={`mt-0.5 rounded-full p-0.5 ${tier.highlight ? 'bg-sky-500/10' : 'bg-emerald-500/10'}`}>
                    <Check className={`h-3 w-3 ${tier.highlight ? 'text-sky-500' : 'text-emerald-500'}`} />
                  </div>
                  <span className="text-xs leading-tight">{feature}</span>
                </li>
              ))}
            </ul>
            <div className="pt-2 text-center">
              <Button
                className={`w-full ${tier.highlight ? 'bg-sky-500 hover:bg-sky-400 text-white shadow-md shadow-sky-500/25' : ''}`}
                variant={tier.highlight ? 'default' : 'outline'}
                size="sm"
                disabled
              >
                {tier.cta}
              </Button>
            </div>
          </CardContent>
        </Card>
      ))}
    </div>
  )
}
