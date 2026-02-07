import { createFileRoute } from '@tanstack/react-router'
import { useQuery } from '@tanstack/react-query'
import { api } from '@/lib/api'
import { Card, CardContent, CardHeader, CardTitle, CardDescription } from '@/components/ui/card'
import { DollarSign, Users, TrendingDown, TrendingUp, Wallet } from 'lucide-react'
import {
  MetricCard,
  PlanBadge,
  SectionHeader,
  AdminSkeleton,
} from '@/components/admin-components'

export const Route = createFileRoute('/admin/revenue')({
  component: AdminRevenuePage,
})

function AdminRevenuePage() {
  const { data, isLoading } = useQuery({
    queryKey: ['admin-revenue'],
    queryFn: () => api.getAdminRevenue(),
  })

  if (isLoading || !data) {
    return <AdminSkeleton />
  }

  const totalSubscribers = Object.values(data.subscriptionsByPlan).reduce((a, b) => a + b, 0)
  const planOrder = ['free', 'pro', 'team', 'business']
  const sortedPlans = Object.entries(data.subscriptionsByPlan).sort(
    ([a], [b]) => planOrder.indexOf(a.toLowerCase()) - planOrder.indexOf(b.toLowerCase())
  )

  const planBarColors: Record<string, string> = {
    free: 'bg-zinc-400 dark:bg-zinc-500',
    pro: 'bg-blue-500',
    team: 'bg-violet-500',
    business: 'bg-amber-500',
  }

  const avgRevenuePerPaidUser = totalSubscribers > 0
    ? data.mrr / Math.max(totalSubscribers - (data.subscriptionsByPlan['free'] || 0), 1)
    : 0

  return (
    <div className="space-y-8">
      <SectionHeader
        title="Revenue"
        description="Financial metrics and subscription analytics."
      />

      {/* KPI Cards */}
      <div className="grid grid-cols-1 sm:grid-cols-2 lg:grid-cols-4 gap-4">
        <MetricCard
          title="Monthly Recurring Revenue"
          value={`$${data.mrr.toLocaleString(undefined, { minimumFractionDigits: 2, maximumFractionDigits: 2 })}`}
          subtitle={`$${(data.mrr * 12).toLocaleString(undefined, { maximumFractionDigits: 0 })} ARR`}
          icon={DollarSign}
          iconColor="text-emerald-600 dark:text-emerald-400"
          iconBg="bg-emerald-100 dark:bg-emerald-950"
        />
        <MetricCard
          title="Total Subscribers"
          value={totalSubscribers}
          subtitle={`${totalSubscribers - (data.subscriptionsByPlan['free'] || 0)} paid`}
          icon={Users}
          iconColor="text-blue-600 dark:text-blue-400"
          iconBg="bg-blue-100 dark:bg-blue-950"
        />
        <MetricCard
          title="Avg Revenue / Paid User"
          value={`$${avgRevenuePerPaidUser.toFixed(2)}`}
          subtitle="per month"
          icon={Wallet}
          iconColor="text-violet-600 dark:text-violet-400"
          iconBg="bg-violet-100 dark:bg-violet-950"
        />
        <MetricCard
          title="Churn (30 days)"
          value={data.churnLast30Days}
          subtitle={totalSubscribers > 0 ? `${((data.churnLast30Days / totalSubscribers) * 100).toFixed(1)}% churn rate` : undefined}
          icon={TrendingDown}
          iconColor="text-red-600 dark:text-red-400"
          iconBg="bg-red-100 dark:bg-red-950"
        />
      </div>

      <div className="grid grid-cols-1 lg:grid-cols-2 gap-6">
        {/* Subscriptions by Plan - Visual */}
        <Card>
          <CardHeader>
            <CardTitle className="text-base">Subscriptions by Plan</CardTitle>
            <CardDescription>Distribution across pricing tiers</CardDescription>
          </CardHeader>
          <CardContent>
            {sortedPlans.length > 0 ? (
              <div className="space-y-4">
                {sortedPlans.map(([plan, count]) => {
                  const pct = totalSubscribers > 0 ? (count / totalSubscribers) * 100 : 0
                  return (
                    <div key={plan} className="space-y-1.5">
                      <div className="flex items-center justify-between">
                        <div className="flex items-center gap-2">
                          <PlanBadge plan={plan} />
                        </div>
                        <div className="flex items-baseline gap-1.5">
                          <span className="text-sm font-semibold tabular-nums">{count}</span>
                          <span className="text-xs text-muted-foreground">({pct.toFixed(0)}%)</span>
                        </div>
                      </div>
                      <div className="h-2 rounded-full bg-muted overflow-hidden">
                        <div
                          className={`h-full rounded-full transition-all duration-500 ${planBarColors[plan.toLowerCase()] ?? 'bg-primary'}`}
                          style={{ width: `${pct}%` }}
                        />
                      </div>
                    </div>
                  )
                })}
              </div>
            ) : (
              <p className="text-muted-foreground text-sm text-center py-8">No subscriptions</p>
            )}
          </CardContent>
        </Card>

        {/* Cost / Revenue per Plan */}
        <Card>
          <CardHeader>
            <CardTitle className="text-base">Infrastructure Cost per Org</CardTitle>
            <CardDescription>Estimated hosting cost by plan tier</CardDescription>
          </CardHeader>
          <CardContent>
            {Object.keys(data.estimatedCostPerOrg).length > 0 ? (
              <div className="space-y-4">
                {Object.entries(data.estimatedCostPerOrg)
                  .sort(([a], [b]) => planOrder.indexOf(a.toLowerCase()) - planOrder.indexOf(b.toLowerCase()))
                  .map(([plan, cost]) => {
                    const planPrices: Record<string, number> = { free: 0, pro: 19, team: 49, business: 99 }
                    const price = planPrices[plan.toLowerCase()] ?? 0
                    const margin = price > 0 ? ((price - cost) / price) * 100 : 0

                    return (
                      <div key={plan} className="flex items-center justify-between py-2 border-b last:border-0">
                        <div className="flex items-center gap-3">
                          <PlanBadge plan={plan} />
                        </div>
                        <div className="flex items-center gap-4">
                          <div className="text-right">
                            <div className="text-sm font-semibold tabular-nums">${cost.toFixed(2)}/mo</div>
                            <div className="text-xs text-muted-foreground">cost per org</div>
                          </div>
                          {price > 0 && (
                            <div className={`text-right ${margin >= 0 ? 'text-emerald-600 dark:text-emerald-400' : 'text-red-600 dark:text-red-400'}`}>
                              <div className="text-xs font-medium flex items-center gap-0.5">
                                {margin >= 0 ? <TrendingUp className="h-3 w-3" /> : <TrendingDown className="h-3 w-3" />}
                                {Math.abs(margin).toFixed(0)}% margin
                              </div>
                            </div>
                          )}
                        </div>
                      </div>
                    )
                  })}
              </div>
            ) : (
              <p className="text-muted-foreground text-sm text-center py-8">No cost data</p>
            )}
          </CardContent>
        </Card>
      </div>
    </div>
  )
}
