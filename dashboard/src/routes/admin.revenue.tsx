import { createFileRoute } from '@tanstack/react-router'
import { useQuery } from '@tanstack/react-query'
import { api } from '@/lib/api'
import { Card, CardContent, CardHeader, CardTitle } from '@/components/ui/card'
import { DollarSign, Users, TrendingDown } from 'lucide-react'

export const Route = createFileRoute('/admin/revenue')({
  component: AdminRevenuePage,
})

function AdminRevenuePage() {
  const { data, isLoading } = useQuery({
    queryKey: ['admin-revenue'],
    queryFn: () => api.getAdminRevenue(),
  })

  if (isLoading || !data) {
    return <div className="p-8 text-center">Loading revenue data...</div>
  }

  const planOrder = ['free', 'pro', 'team']
  const sortedPlans = Object.entries(data.subscriptionsByPlan).sort(
    ([a], [b]) => planOrder.indexOf(a.toLowerCase()) - planOrder.indexOf(b.toLowerCase())
  )

  return (
    <div className="space-y-6">
      <h2 className="text-2xl font-bold">Revenue</h2>
      <div className="grid grid-cols-1 md:grid-cols-4 gap-4">
        <Card>
          <CardHeader className="flex flex-row items-center justify-between pb-2">
            <CardTitle className="text-sm font-medium text-muted-foreground">MRR</CardTitle>
            <DollarSign className="h-4 w-4 text-muted-foreground" />
          </CardHeader>
          <CardContent>
            <div className="text-2xl font-bold">${data.mrr.toFixed(2)}</div>
          </CardContent>
        </Card>
        <Card>
          <CardHeader className="flex flex-row items-center justify-between pb-2">
            <CardTitle className="text-sm font-medium text-muted-foreground">Total Subscribers</CardTitle>
            <Users className="h-4 w-4 text-muted-foreground" />
          </CardHeader>
          <CardContent>
            <div className="text-2xl font-bold">
              {Object.values(data.subscriptionsByPlan).reduce((a, b) => a + b, 0)}
            </div>
          </CardContent>
        </Card>
        <Card>
          <CardHeader className="flex flex-row items-center justify-between pb-2">
            <CardTitle className="text-sm font-medium text-muted-foreground">Churn (30d)</CardTitle>
            <TrendingDown className="h-4 w-4 text-muted-foreground" />
          </CardHeader>
          <CardContent>
            <div className="text-2xl font-bold">{data.churnLast30Days}</div>
          </CardContent>
        </Card>
      </div>
      <div className="grid grid-cols-1 lg:grid-cols-2 gap-6">
        <Card>
          <CardHeader>
            <CardTitle>Subscriptions by Plan</CardTitle>
          </CardHeader>
          <CardContent>
            {sortedPlans.length > 0 ? (
              <div className="space-y-3">
                {sortedPlans.map(([plan, count]) => (
                  <div key={plan} className="flex justify-between text-sm">
                    <span className="capitalize font-medium">{plan}</span>
                    <span>{count}</span>
                  </div>
                ))}
              </div>
            ) : (
              <p className="text-muted-foreground">No subscriptions</p>
            )}
          </CardContent>
        </Card>
        <Card>
          <CardHeader>
            <CardTitle>Estimated Cost per Org (by plan)</CardTitle>
          </CardHeader>
          <CardContent>
            {Object.keys(data.estimatedCostPerOrg).length > 0 ? (
              <div className="space-y-3">
                {Object.entries(data.estimatedCostPerOrg).map(([plan, cost]) => (
                  <div key={plan} className="flex justify-between text-sm">
                    <span className="capitalize font-medium">{plan}</span>
                    <span>${cost.toFixed(2)}/mo</span>
                  </div>
                ))}
              </div>
            ) : (
              <p className="text-muted-foreground">No cost data</p>
            )}
          </CardContent>
        </Card>
      </div>
    </div>
  )
}
