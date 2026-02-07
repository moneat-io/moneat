import { createFileRoute, Link } from '@tanstack/react-router'
import { useQuery } from '@tanstack/react-query'
import { api } from '@/lib/api'
import { Card, CardContent, CardHeader, CardTitle, CardDescription } from '@/components/ui/card'
import { Badge } from '@/components/ui/badge'
import {
  Table,
  TableBody,
  TableCell,
  TableHead,
  TableHeader,
  TableRow,
} from '@/components/ui/table'
import {
  BarChart,
  Bar,
  XAxis,
  YAxis,
  CartesianGrid,
  Tooltip,
  ResponsiveContainer,
  Legend,
} from 'recharts'
import { ArrowLeft, Users, FolderKanban, Zap, HardDrive } from 'lucide-react'
import {
  MetricCard,
  PlanBadge,
  QuotaBar,
  ChartTooltipContent,
  AdminSkeleton,
  EmptyState,
  formatBytes,
  formatNumber,
  eventTypeColors,
} from '@/components/admin-components'

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
    return <AdminSkeleton />
  }

  return (
    <div className="space-y-8">
      {/* Breadcrumb + Title */}
      <div>
        <Link
          to="/admin/organizations"
          className="text-sm text-muted-foreground hover:text-foreground flex items-center gap-1.5 mb-3 transition-colors w-fit"
        >
          <ArrowLeft className="h-3.5 w-3.5" />
          All Organizations
        </Link>
        <div className="flex items-center gap-3">
          <h2 className="text-2xl font-bold tracking-tight">{org.name}</h2>
          <PlanBadge plan={org.plan} />
          {org.subscriptionStatus && org.subscriptionStatus !== 'active' && (
            <Badge variant="outline" className="text-amber-600 border-amber-300 dark:text-amber-400 dark:border-amber-700">
              {org.subscriptionStatus}
            </Badge>
          )}
        </div>
        {org.companySize && (
          <p className="text-sm text-muted-foreground mt-1">Company size: {org.companySize}</p>
        )}
      </div>

      {/* Metric Cards */}
      <div className="grid grid-cols-1 sm:grid-cols-2 lg:grid-cols-4 gap-4">
        <MetricCard
          title="Events This Month"
          value={formatNumber(org.eventCountThisMonth)}
          icon={Zap}
          iconColor="text-orange-600 dark:text-orange-400"
          iconBg="bg-orange-100 dark:bg-orange-950"
        />
        <MetricCard
          title="Bytes Ingested"
          value={formatBytes(org.bytesIngestedThisMonth)}
          icon={HardDrive}
          iconColor="text-blue-600 dark:text-blue-400"
          iconBg="bg-blue-100 dark:bg-blue-950"
        />
        <MetricCard
          title="Members"
          value={org.memberCount}
          icon={Users}
          iconColor="text-violet-600 dark:text-violet-400"
          iconBg="bg-violet-100 dark:bg-violet-950"
        />
        <Card className="relative overflow-hidden">
          <CardHeader className="flex flex-row items-center justify-between pb-2">
            <CardTitle className="text-sm font-medium text-muted-foreground">Quota Usage</CardTitle>
          </CardHeader>
          <CardContent className="space-y-2">
            <div className="text-2xl font-bold tracking-tight">
              {org.quotaUsedPercent != null ? `${org.quotaUsedPercent.toFixed(1)}%` : 'Unlimited'}
            </div>
            <QuotaBar percent={org.quotaUsedPercent} size="md" showLabel={false} />
          </CardContent>
        </Card>
      </div>

      {/* Members & Projects */}
      <div className="grid grid-cols-1 lg:grid-cols-2 gap-6">
        <Card>
          <CardHeader>
            <CardTitle className="text-base">Members</CardTitle>
            <CardDescription>{org.memberCount} member{org.memberCount !== 1 ? 's' : ''}</CardDescription>
          </CardHeader>
          <CardContent>
            {org.members.length > 0 ? (
              <Table>
                <TableHeader>
                  <TableRow className="hover:bg-transparent">
                    <TableHead>Email</TableHead>
                    <TableHead>Name</TableHead>
                    <TableHead className="text-right">Role</TableHead>
                  </TableRow>
                </TableHeader>
                <TableBody>
                  {org.members.map((m) => (
                    <TableRow key={m.userId}>
                      <TableCell className="font-medium">{m.email}</TableCell>
                      <TableCell className="text-muted-foreground">{m.name || '—'}</TableCell>
                      <TableCell className="text-right">
                        <Badge
                          variant={m.role === 'owner' ? 'default' : 'secondary'}
                          className="capitalize"
                        >
                          {m.role}
                        </Badge>
                      </TableCell>
                    </TableRow>
                  ))}
                </TableBody>
              </Table>
            ) : (
              <EmptyState message="No members" icon={Users} />
            )}
          </CardContent>
        </Card>

        <Card>
          <CardHeader>
            <CardTitle className="text-base">Projects</CardTitle>
            <CardDescription>{org.projectCount} project{org.projectCount !== 1 ? 's' : ''}</CardDescription>
          </CardHeader>
          <CardContent>
            {org.projects.length > 0 ? (
              <Table>
                <TableHeader>
                  <TableRow className="hover:bg-transparent">
                    <TableHead>Name</TableHead>
                    <TableHead>Slug</TableHead>
                    <TableHead className="text-right">Platform</TableHead>
                  </TableRow>
                </TableHeader>
                <TableBody>
                  {org.projects.map((p) => (
                    <TableRow key={p.id}>
                      <TableCell className="font-medium">{p.name}</TableCell>
                      <TableCell className="text-muted-foreground font-mono text-xs">{p.slug}</TableCell>
                      <TableCell className="text-right">
                        {p.platform ? (
                          <Badge variant="outline" className="capitalize">{p.platform}</Badge>
                        ) : (
                          <span className="text-muted-foreground">—</span>
                        )}
                      </TableCell>
                    </TableRow>
                  ))}
                </TableBody>
              </Table>
            ) : (
              <EmptyState message="No projects" icon={FolderKanban} />
            )}
          </CardContent>
        </Card>
      </div>

      {/* Usage Chart */}
      {usageArray.length > 0 && (
        <Card>
          <CardHeader>
            <CardTitle className="text-base">Usage Over Time</CardTitle>
            <CardDescription>Daily event counts by type over the last 30 days</CardDescription>
          </CardHeader>
          <CardContent>
            <ResponsiveContainer width="100%" height={320}>
              <BarChart data={usageArray}>
                <CartesianGrid strokeDasharray="3 3" className="stroke-muted" vertical={false} />
                <XAxis
                  dataKey="date"
                  className="text-xs"
                  tickLine={false}
                  axisLine={false}
                  tickFormatter={(v) => {
                    const d = new Date(v)
                    return `${d.getMonth() + 1}/${d.getDate()}`
                  }}
                />
                <YAxis
                  className="text-xs"
                  tickLine={false}
                  axisLine={false}
                  tickFormatter={(v) => formatNumber(v)}
                />
                <Tooltip content={<ChartTooltipContent />} />
                <Legend
                  iconType="circle"
                  iconSize={8}
                  wrapperStyle={{ paddingTop: '12px' }}
                />
                <Bar dataKey="error" stackId="a" fill={eventTypeColors.error.stroke} name="Errors" radius={[0, 0, 0, 0]} />
                <Bar dataKey="transaction" stackId="a" fill={eventTypeColors.transaction.stroke} name="Transactions" />
                <Bar dataKey="replay" stackId="a" fill={eventTypeColors.replay.stroke} name="Replays" />
                <Bar dataKey="feedback" stackId="a" fill={eventTypeColors.feedback.stroke} name="Feedback" radius={[2, 2, 0, 0]} />
              </BarChart>
            </ResponsiveContainer>
          </CardContent>
        </Card>
      )}
    </div>
  )
}
