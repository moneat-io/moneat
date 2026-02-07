import { createFileRoute } from '@tanstack/react-router'
import { useQuery } from '@tanstack/react-query'
import { api } from '@/lib/api'
import { Card, CardContent, CardHeader, CardTitle } from '@/components/ui/card'
import { BarChart, Bar, XAxis, YAxis, CartesianGrid, Tooltip, ResponsiveContainer } from 'recharts'

export const Route = createFileRoute('/admin/organizations/$orgId')({
  component: AdminOrgDetailPage,
})

function AdminOrgDetailPage() {
  const { orgId } = Route.useParams()
  const { data: org, isLoading } = useQuery({
    queryKey: ['admin-org', orgId],
    queryFn: () => api.getAdminOrgDetail(Number(orgId)),
    enabled: !!orgId,
  })

  const { data: usage } = useQuery({
    queryKey: ['admin-org-usage', orgId],
    queryFn: () => api.getAdminOrgUsage(Number(orgId), '30d'),
    enabled: !!orgId,
  })

  const usageByDate = (usage || []).reduce(
    (acc: Record<string, { date: string; error: number; transaction: number; replay: number; feedback: number; total: number }>, u) => {
      if (!acc[u.date]) {
        acc[u.date] = { date: u.date, error: 0, transaction: 0, replay: 0, feedback: 0, total: 0 }
      }
      const key = u.eventType as 'error' | 'transaction' | 'replay' | 'feedback'
      if (key in acc[u.date]) {
        acc[u.date][key] += u.eventCount
      }
      acc[u.date].total += u.eventCount
      return acc
    },
    {}
  )
  const usageArray = Object.values(usageByDate).sort((a, b) => a.date.localeCompare(b.date))

  if (isLoading || !org) {
    return <div className="p-8 text-center">Loading organization...</div>
  }

  return (
    <div className="space-y-6">
      <h2 className="text-2xl font-bold">{org.name}</h2>
      <div className="grid grid-cols-1 md:grid-cols-4 gap-4">
        <Card>
          <CardHeader className="pb-2">
            <CardTitle className="text-sm font-medium text-muted-foreground">Plan</CardTitle>
          </CardHeader>
          <CardContent>
            <div className="text-xl font-bold capitalize">{org.plan}</div>
          </CardContent>
        </Card>
        <Card>
          <CardHeader className="pb-2">
            <CardTitle className="text-sm font-medium text-muted-foreground">Events (This Month)</CardTitle>
          </CardHeader>
          <CardContent>
            <div className="text-xl font-bold">{org.eventCountThisMonth.toLocaleString()}</div>
          </CardContent>
        </Card>
        <Card>
          <CardHeader className="pb-2">
            <CardTitle className="text-sm font-medium text-muted-foreground">Quota Used</CardTitle>
          </CardHeader>
          <CardContent>
            <div className="text-xl font-bold">
              {org.quotaUsedPercent != null ? `${org.quotaUsedPercent.toFixed(1)}%` : '-'}
            </div>
          </CardContent>
        </Card>
        <Card>
          <CardHeader className="pb-2">
            <CardTitle className="text-sm font-medium text-muted-foreground">Bytes Ingested</CardTitle>
          </CardHeader>
          <CardContent>
            <div className="text-xl font-bold">{(org.bytesIngestedThisMonth / 1024 / 1024).toFixed(2)} MB</div>
          </CardContent>
        </Card>
      </div>
      <div className="grid grid-cols-1 lg:grid-cols-2 gap-6">
        <Card>
          <CardHeader>
            <CardTitle>Members ({org.memberCount})</CardTitle>
          </CardHeader>
          <CardContent>
            <ul className="space-y-2">
              {org.members.map((m) => (
                <li key={m.userId} className="flex justify-between text-sm">
                  <span>{m.email}</span>
                  <span className="text-muted-foreground capitalize">{m.role}</span>
                </li>
              ))}
              {org.members.length === 0 && <p className="text-muted-foreground">No members</p>}
            </ul>
          </CardContent>
        </Card>
        <Card>
          <CardHeader>
            <CardTitle>Projects ({org.projectCount})</CardTitle>
          </CardHeader>
          <CardContent>
            <ul className="space-y-2">
              {org.projects.map((p) => (
                <li key={p.id} className="text-sm">
                  {p.name} <span className="text-muted-foreground">({p.slug})</span>
                </li>
              ))}
              {org.projects.length === 0 && <p className="text-muted-foreground">No projects</p>}
            </ul>
          </CardContent>
        </Card>
      </div>
      {usageArray.length > 0 && (
        <Card>
          <CardHeader>
            <CardTitle>Usage Over Time (30d)</CardTitle>
          </CardHeader>
          <CardContent>
            <ResponsiveContainer width="100%" height={300}>
              <BarChart data={usageArray}>
                <CartesianGrid strokeDasharray="3 3" className="stroke-muted" />
                <XAxis dataKey="date" className="text-xs" />
                <YAxis className="text-xs" />
                <Tooltip />
                <Bar dataKey="error" stackId="a" fill="#ef4444" name="Errors" />
                <Bar dataKey="transaction" stackId="a" fill="#3b82f6" name="Transactions" />
                <Bar dataKey="replay" stackId="a" fill="#22c55e" name="Replays" />
                <Bar dataKey="feedback" stackId="a" fill="#eab308" name="Feedback" />
              </BarChart>
            </ResponsiveContainer>
          </CardContent>
        </Card>
      )}
    </div>
  )
}
