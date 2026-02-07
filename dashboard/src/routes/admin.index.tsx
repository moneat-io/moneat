import { createFileRoute, Link } from '@tanstack/react-router'
import { useQuery } from '@tanstack/react-query'
import { api } from '@/lib/api'
import { Card, CardContent, CardHeader, CardTitle } from '@/components/ui/card'
import { Building2, Users, Activity, DollarSign, TrendingUp } from 'lucide-react'
import {
  LineChart,
  Line,
  XAxis,
  YAxis,
  CartesianGrid,
  Tooltip,
  ResponsiveContainer,
} from 'recharts'

export const Route = createFileRoute('/admin/')({
  component: AdminOverviewPage,
})

function AdminOverviewPage() {
  const { data: stats, isLoading } = useQuery({
    queryKey: ['admin-overview'],
    queryFn: () => api.getAdminOverview(),
  })

  const { data: topConsumers } = useQuery({
    queryKey: ['admin-top-consumers'],
    queryFn: () => api.getAdminTopConsumers(5),
  })

  if (isLoading || !stats) {
    return <div className="p-8 text-center">Loading admin overview...</div>
  }

  return (
    <div className="space-y-6">
      <h2 className="text-2xl font-bold">Overview</h2>

      {/* Metric Cards */}
      <div className="grid grid-cols-1 md:grid-cols-2 lg:grid-cols-5 gap-4">
        <Card>
          <CardHeader className="flex flex-row items-center justify-between pb-2">
            <CardTitle className="text-sm font-medium text-muted-foreground">Organizations</CardTitle>
            <Building2 className="h-4 w-4 text-muted-foreground" />
          </CardHeader>
          <CardContent>
            <div className="text-2xl font-bold">{stats.totalOrganizations.toLocaleString()}</div>
          </CardContent>
        </Card>
        <Card>
          <CardHeader className="flex flex-row items-center justify-between pb-2">
            <CardTitle className="text-sm font-medium text-muted-foreground">Users</CardTitle>
            <Users className="h-4 w-4 text-muted-foreground" />
          </CardHeader>
          <CardContent>
            <div className="text-2xl font-bold">{stats.totalUsers.toLocaleString()}</div>
          </CardContent>
        </Card>
        <Card>
          <CardHeader className="flex flex-row items-center justify-between pb-2">
            <CardTitle className="text-sm font-medium text-muted-foreground">Events (30d)</CardTitle>
            <Activity className="h-4 w-4 text-muted-foreground" />
          </CardHeader>
          <CardContent>
            <div className="text-2xl font-bold">{stats.totalEventsLast30Days.toLocaleString()}</div>
          </CardContent>
        </Card>
        <Card>
          <CardHeader className="flex flex-row items-center justify-between pb-2">
            <CardTitle className="text-sm font-medium text-muted-foreground">MRR</CardTitle>
            <DollarSign className="h-4 w-4 text-muted-foreground" />
          </CardHeader>
          <CardContent>
            <div className="text-2xl font-bold">${stats.mrr.toFixed(0)}</div>
          </CardContent>
        </Card>
        <Card>
          <CardHeader className="flex flex-row items-center justify-between pb-2">
            <CardTitle className="text-sm font-medium text-muted-foreground">Subscriptions</CardTitle>
            <TrendingUp className="h-4 w-4 text-muted-foreground" />
          </CardHeader>
          <CardContent>
            <div className="text-sm space-y-1">
              {Object.entries(stats.subscriptionsByPlan).map(([plan, count]) => (
                <div key={plan} className="flex justify-between">
                  <span className="capitalize">{plan}</span>
                  <span className="font-medium">{count}</span>
                </div>
              ))}
              {Object.keys(stats.subscriptionsByPlan).length === 0 && (
                <span className="text-muted-foreground">No active subscriptions</span>
              )}
            </div>
          </CardContent>
        </Card>
      </div>

      {/* Events Chart */}
      <Card>
        <CardHeader>
          <CardTitle>Events (Last 30 Days)</CardTitle>
        </CardHeader>
        <CardContent>
          {stats.eventsLast30Days.length > 0 ? (
            <ResponsiveContainer width="100%" height={300}>
              <LineChart data={stats.eventsLast30Days}>
                <CartesianGrid strokeDasharray="3 3" className="stroke-muted" />
                <XAxis dataKey="date" className="text-xs" />
                <YAxis className="text-xs" />
                <Tooltip />
                <Line type="monotone" dataKey="count" stroke="hsl(var(--primary))" strokeWidth={2} dot={false} />
              </LineChart>
            </ResponsiveContainer>
          ) : (
            <p className="text-muted-foreground text-center py-8">No event data yet</p>
          )}
        </CardContent>
      </Card>

      {/* Top Consumers */}
      <Card>
        <CardHeader>
          <CardTitle>Top 5 Consumers (This Month)</CardTitle>
        </CardHeader>
        <CardContent>
          {topConsumers && topConsumers.length > 0 ? (
            <div className="overflow-x-auto">
              <table className="w-full text-sm">
                <thead>
                  <tr className="border-b">
                    <th className="text-left py-2 font-medium">Organization</th>
                    <th className="text-left py-2 font-medium">Plan</th>
                    <th className="text-right py-2 font-medium">Events</th>
                    <th className="text-right py-2 font-medium">Bytes</th>
                  </tr>
                </thead>
                <tbody>
                  {topConsumers.map((c) => (
                    <tr key={c.orgId} className="border-b last:border-0">
                      <td className="py-2">
                        <Link
                          to="/admin/organizations/$orgId"
                          params={{ orgId: String(c.orgId) }}
                          className="text-primary hover:underline"
                        >
                          {c.orgName}
                        </Link>
                      </td>
                      <td className="py-2 capitalize">{c.plan}</td>
                      <td className="py-2 text-right">{c.eventCount.toLocaleString()}</td>
                      <td className="py-2 text-right">
                        {(c.bytesIngested / 1024 / 1024).toFixed(2)} MB
                      </td>
                    </tr>
                  ))}
                </tbody>
              </table>
            </div>
          ) : (
            <p className="text-muted-foreground text-center py-8">No usage data yet</p>
          )}
        </CardContent>
      </Card>
    </div>
  )
}
