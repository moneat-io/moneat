import {useState} from 'react'
import {createFileRoute} from '@tanstack/react-router'
import {useMutation, useQuery, useQueryClient} from '@tanstack/react-query'
import {api, type BillingPlan, type BillingTierConfig} from '@/lib/api'
import {Card, CardContent, CardDescription, CardHeader, CardTitle} from '@/components/ui/card'
import {Button} from '@/components/ui/button'
import {Input} from '@/components/ui/input'
import {Label} from '@/components/ui/label'
import {Badge} from '@/components/ui/badge'
import {useToast} from '@/hooks/use-toast'
import {AdminSkeleton, SectionHeader} from '@/components/admin-components'

export const Route = createFileRoute('/admin/billing')({
  component: AdminBillingPage,
})

function AdminBillingPage() {
  const queryClient = useQueryClient()
  const { toast } = useToast()
  const [selectedTier, setSelectedTier] = useState('PRO')
  const [targetVersion, setTargetVersion] = useState<number>(2)
  const [createForm, setCreateForm] = useState({
    monthlyUnitLimit: 500_000,
    retentionDays: 30,
    maxProjects: '',
    maxSystems: 5,
    monitorIntervalSeconds: 15,
    monthlyPriceCents: 1900,
    paygEnabled: true,
    paygRateMicrosPerUnit: 10,
    stripeBasePriceId: '',
    stripeOveragePriceId: '',
  })

  const { data: currentPlansRaw, isLoading: plansLoading } = useQuery({
    queryKey: ['admin-billing-current-plans'],
    queryFn: () => api.getAdminBillingTiers(),
  })

  const { data: tierVersionsRaw, isLoading: versionsLoading } = useQuery({
    queryKey: ['admin-billing-tier-versions', selectedTier],
    queryFn: () => api.getAdminBillingTiers(selectedTier),
  })

  const { data: subscriptions = [], isLoading: subscriptionsLoading } = useQuery({
    queryKey: ['admin-billing-subscriptions'],
    queryFn: () => api.getAdminBillingSubscriptions(250),
  })

  const createVersionMutation = useMutation({
    mutationFn: () =>
      api.createAdminBillingTierVersion(selectedTier, {
        monthlyUnitLimit: Number(createForm.monthlyUnitLimit),
        retentionDays: Number(createForm.retentionDays),
        maxProjects: createForm.maxProjects.trim() ? Number(createForm.maxProjects) : null,
        maxSystems: Number(createForm.maxSystems),
        monitorIntervalSeconds: Number(createForm.monitorIntervalSeconds),
        monthlyPriceCents: Number(createForm.monthlyPriceCents),
        paygEnabled: Boolean(createForm.paygEnabled),
        paygRateMicrosPerUnit: Number(createForm.paygRateMicrosPerUnit),
        stripeBasePriceId: createForm.stripeBasePriceId.trim() || null,
        stripeOveragePriceId: createForm.stripeOveragePriceId.trim() || null,
      }),
    onSuccess: (tier) => {
      queryClient.invalidateQueries({ queryKey: ['admin-billing-current-plans'] })
      queryClient.invalidateQueries({ queryKey: ['admin-billing-tier-versions', selectedTier] })
      setTargetVersion(tier.version)
      toast({ title: `${selectedTier} v${tier.version} created` })
    },
    onError: (err: Error) => {
      toast({
        title: 'Failed to create version',
        description: err.message,
        variant: 'destructive',
      })
    },
  })

  const dryRunMutation = useMutation({
    mutationFn: () => api.migrateAdminBillingTier(selectedTier, Number(targetVersion), true),
    onSuccess: (res) => {
      toast({
        title: 'Dry run complete',
        description: `${res.affectedSubscriptions} subscription(s) would be migrated`,
      })
    },
    onError: (err: Error) => {
      toast({
        title: 'Dry run failed',
        description: err.message,
        variant: 'destructive',
      })
    },
  })

  const executeMigrationMutation = useMutation({
    mutationFn: () => api.migrateAdminBillingTier(selectedTier, Number(targetVersion), false),
    onSuccess: (res) => {
      queryClient.invalidateQueries({ queryKey: ['admin-billing-subscriptions'] })
      toast({
        title: 'Migration complete',
        description: `${res.affectedSubscriptions} subscription(s) migrated`,
      })
    },
    onError: (err: Error) => {
      toast({
        title: 'Migration failed',
        description: err.message,
        variant: 'destructive',
      })
    },
  })

  if (plansLoading || versionsLoading || subscriptionsLoading) {
    return <AdminSkeleton />
  }

  const currentPlans = (Array.isArray(currentPlansRaw) && currentPlansRaw.length > 0 && 'tier' in currentPlansRaw[0]
    ? currentPlansRaw
    : []) as BillingPlan[]
  const tierVersions = (Array.isArray(tierVersionsRaw) ? tierVersionsRaw : []) as BillingTierConfig[]

  return (
    <div className="space-y-6">
      <SectionHeader
        title="Billing"
        description="Manage pricing tier versions and subscriber migrations."
      />

      <Card>
        <CardHeader>
          <CardTitle>Current Plan Configs</CardTitle>
          <CardDescription>Current active config per tier</CardDescription>
        </CardHeader>
        <CardContent className="space-y-2">
          {currentPlans.length === 0 ? (
            <p className="text-sm text-muted-foreground">No plans configured.</p>
          ) : (
            currentPlans.map((plan) => (
              <div key={plan.tier.id} className="flex items-center justify-between rounded border p-3">
                <div>
                  <p className="font-medium">{plan.tier.tierName}</p>
                  <p className="text-xs text-muted-foreground">
                    v{plan.tier.version} · ${(plan.tier.monthlyPriceCents / 100).toFixed(2)} / mo · {plan.tier.monthlyUnitLimit.toLocaleString()} units
                  </p>
                </div>
                <Badge>{plan.tier.isCurrent ? 'current' : 'legacy'}</Badge>
              </div>
            ))
          )}
        </CardContent>
      </Card>

      <Card>
        <CardHeader>
          <CardTitle>Create New Tier Version</CardTitle>
          <CardDescription>Creates a new config version and marks it current.</CardDescription>
        </CardHeader>
        <CardContent className="grid gap-3 sm:grid-cols-2">
          <div className="space-y-1">
            <Label>Tier</Label>
            <Input value={selectedTier} onChange={(e) => setSelectedTier(e.target.value.toUpperCase())} />
          </div>
          <div className="space-y-1">
            <Label>Monthly Unit Limit</Label>
            <Input type="number" value={createForm.monthlyUnitLimit} onChange={(e) => setCreateForm((p) => ({...p, monthlyUnitLimit: Number(e.target.value)}))} />
          </div>
          <div className="space-y-1">
            <Label>Retention Days</Label>
            <Input type="number" value={createForm.retentionDays} onChange={(e) => setCreateForm((p) => ({...p, retentionDays: Number(e.target.value)}))} />
          </div>
          <div className="space-y-1">
            <Label>Max Projects (blank = unlimited)</Label>
            <Input value={createForm.maxProjects} onChange={(e) => setCreateForm((p) => ({...p, maxProjects: e.target.value}))} />
          </div>
          <div className="space-y-1">
            <Label>Max Systems</Label>
            <Input type="number" value={createForm.maxSystems} onChange={(e) => setCreateForm((p) => ({...p, maxSystems: Number(e.target.value)}))} />
          </div>
          <div className="space-y-1">
            <Label>Monitor Interval Seconds</Label>
            <Input type="number" value={createForm.monitorIntervalSeconds} onChange={(e) => setCreateForm((p) => ({...p, monitorIntervalSeconds: Number(e.target.value)}))} />
          </div>
          <div className="space-y-1">
            <Label>Monthly Price (cents)</Label>
            <Input type="number" value={createForm.monthlyPriceCents} onChange={(e) => setCreateForm((p) => ({...p, monthlyPriceCents: Number(e.target.value)}))} />
          </div>
          <div className="space-y-1">
            <Label>PAYG Rate (micros per unit)</Label>
            <Input type="number" value={createForm.paygRateMicrosPerUnit} onChange={(e) => setCreateForm((p) => ({...p, paygRateMicrosPerUnit: Number(e.target.value)}))} />
          </div>
          <div className="space-y-1">
            <Label>Stripe Base Price ID</Label>
            <Input value={createForm.stripeBasePriceId} onChange={(e) => setCreateForm((p) => ({...p, stripeBasePriceId: e.target.value}))} />
          </div>
          <div className="space-y-1">
            <Label>Stripe Overage Price ID</Label>
            <Input value={createForm.stripeOveragePriceId} onChange={(e) => setCreateForm((p) => ({...p, stripeOveragePriceId: e.target.value}))} />
          </div>
          <div className="col-span-full">
            <Button onClick={() => createVersionMutation.mutate()} disabled={createVersionMutation.isPending}>
              Create version
            </Button>
          </div>
        </CardContent>
      </Card>

      <Card>
        <CardHeader>
          <CardTitle>Migrate Subscribers</CardTitle>
          <CardDescription>Dry-run or execute migration to a specific version.</CardDescription>
        </CardHeader>
        <CardContent className="space-y-3">
          <div className="space-y-1">
            <Label>Target version for {selectedTier}</Label>
            <Input type="number" value={targetVersion} onChange={(e) => setTargetVersion(Number(e.target.value))} />
          </div>
          <div className="flex gap-2">
            <Button variant="outline" onClick={() => dryRunMutation.mutate()} disabled={dryRunMutation.isPending}>
              Dry run
            </Button>
            <Button onClick={() => executeMigrationMutation.mutate()} disabled={executeMigrationMutation.isPending}>
              Execute migration
            </Button>
          </div>
          {tierVersions.length > 0 && (
            <div className="rounded border p-3">
              <p className="text-sm font-medium mb-2">Known versions for {selectedTier}</p>
              <div className="flex flex-wrap gap-2">
                {tierVersions.map((v) => (
                  <Badge key={v.id} variant={v.isCurrent ? 'default' : 'secondary'}>
                    v{v.version}
                  </Badge>
                ))}
              </div>
            </div>
          )}
        </CardContent>
      </Card>

      <Card>
        <CardHeader>
          <CardTitle>Recent Subscriptions</CardTitle>
          <CardDescription>Snapshot of billing status and PAYG counters.</CardDescription>
        </CardHeader>
        <CardContent className="space-y-2">
          {subscriptions.slice(0, 25).map((sub) => (
            <div key={sub.subscriptionId} className="flex items-center justify-between rounded border p-3">
              <div>
                <p className="font-medium">{sub.organizationName}</p>
                <p className="text-xs text-muted-foreground">
                  {sub.plan} · budget ${(sub.paygBudgetCents / 100).toFixed(2)} · pending meter {sub.pendingMeterUnits}
                </p>
              </div>
              <Badge variant={sub.status === 'active' ? 'default' : 'secondary'}>{sub.status}</Badge>
            </div>
          ))}
        </CardContent>
      </Card>
    </div>
  )
}
