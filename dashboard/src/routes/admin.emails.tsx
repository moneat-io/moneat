import {createFileRoute} from '@tanstack/react-router'
import {useQuery} from '@tanstack/react-query'
import {api} from '@/lib/api'
import {useState} from 'react'
import {Card, CardContent, CardDescription, CardHeader, CardTitle} from '@/components/ui/card'
import {Area, AreaChart, Bar, BarChart, CartesianGrid, ResponsiveContainer, Tooltip, XAxis, YAxis,} from 'recharts'
import {Select, SelectContent, SelectItem, SelectTrigger, SelectValue,} from '@/components/ui/select'
import {DollarSign, Mail, Send} from 'lucide-react'
import {
    AdminSkeleton,
    ChartTooltipContent,
    EmptyState,
    formatNumber,
    MetricCard,
    SectionHeader,
} from '@/components/admin-components'

export const Route = createFileRoute('/admin/emails')({
  component: AdminEmailsPage,
})

function AdminEmailsPage() {
  const [period, setPeriod] = useState<'7d' | '30d'>('30d')
  const { data, isLoading } = useQuery({
    queryKey: ['admin-emails', period],
    queryFn: () => api.getAdminEmailStats(period),
  })

  if (isLoading || !data) {
    return <AdminSkeleton />
  }

  const periodLabel = period === '7d' ? 'Last 7 Days' : 'Last 30 Days'
  const timelineData = period === '7d' ? data.last7Days : data.last30Days

  // Transform byType into chart data
  const emailTypeData = Object.entries(data.byType).map(([type, count]) => ({
    type: type.replace(/_/g, ' ').replace(/\b\w/g, l => l.toUpperCase()),
    count,
  }))

  return (
    <div className="space-y-8">
      <SectionHeader
        title="Email Tracking"
        description="Monitor email sending volume and estimate costs relative to AWS SES."
      >
        <Select value={period} onValueChange={(v) => setPeriod(v as '7d' | '30d')}>
          <SelectTrigger className="w-[160px]">
            <SelectValue />
          </SelectTrigger>
          <SelectContent>
            <SelectItem value="7d">Last 7 Days</SelectItem>
            <SelectItem value="30d">Last 30 Days</SelectItem>
          </SelectContent>
        </Select>
      </SectionHeader>

      {/* Summary Cards */}
      <div className="grid grid-cols-1 sm:grid-cols-3 gap-4">
        <MetricCard
          title="Total Emails Sent"
          value={formatNumber(data.totalSent)}
          subtitle="All time"
          icon={Send}
          iconColor="text-blue-600 dark:text-blue-400"
          iconBg="bg-blue-100 dark:bg-blue-950"
        />
        <MetricCard
          title="Emails This Period"
          value={formatNumber(timelineData.reduce((sum, d) => sum + d.count, 0))}
          subtitle={periodLabel}
          icon={Mail}
          iconColor="text-purple-600 dark:text-purple-400"
          iconBg="bg-purple-100 dark:bg-purple-950"
        />
        <MetricCard
          title="Estimated SES Cost"
          value={`$${data.estimatedCost.toFixed(2)}`}
          subtitle="At $0.10 per 1,000 emails"
          icon={DollarSign}
          iconColor="text-emerald-600 dark:text-emerald-400"
          iconBg="bg-emerald-100 dark:bg-emerald-950"
        />
      </div>

      {/* Timeline Chart */}
      <Card>
        <CardHeader>
          <CardTitle className="text-base">Email Volume Over Time</CardTitle>
          <CardDescription>Daily emails sent ({periodLabel.toLowerCase()})</CardDescription>
        </CardHeader>
        <CardContent>
          {timelineData.length > 0 ? (
            <ResponsiveContainer width="100%" height={300}>
              <AreaChart data={timelineData}>
                <defs>
                  <linearGradient id="gradient-emails" x1="0" y1="0" x2="0" y2="1">
                    <stop offset="5%" stopColor="#3b82f6" stopOpacity={0.3} />
                    <stop offset="95%" stopColor="#3b82f6" stopOpacity={0.02} />
                  </linearGradient>
                </defs>
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
                <Area
                  type="monotone"
                  dataKey="count"
                  stroke="#3b82f6"
                  fill="url(#gradient-emails)"
                  strokeWidth={2}
                  name="Emails Sent"
                />
              </AreaChart>
            </ResponsiveContainer>
          ) : (
            <EmptyState message="No email data yet" icon={Mail} />
          )}
        </CardContent>
      </Card>

      {/* Email Types Breakdown */}
      <Card>
        <CardHeader>
          <CardTitle className="text-base">Emails by Type</CardTitle>
          <CardDescription>Breakdown of all emails sent by category</CardDescription>
        </CardHeader>
        <CardContent>
          {emailTypeData.length > 0 ? (
            <ResponsiveContainer width="100%" height={300}>
              <BarChart data={emailTypeData}>
                <CartesianGrid strokeDasharray="3 3" className="stroke-muted" vertical={false} />
                <XAxis
                  dataKey="type"
                  className="text-xs"
                  tickLine={false}
                  axisLine={false}
                />
                <YAxis
                  className="text-xs"
                  tickLine={false}
                  axisLine={false}
                  tickFormatter={(v) => formatNumber(v)}
                />
                <Tooltip
                  content={({ active, payload }) => {
                    if (!active || !payload?.[0]) return null
                    return (
                      <div className="bg-background border rounded-lg shadow-lg p-3">
                        <p className="text-sm font-semibold">{payload[0].payload.type}</p>
                        <p className="text-sm text-muted-foreground">
                          {formatNumber(payload[0].value as number)} emails
                        </p>
                      </div>
                    )
                  }}
                />
                <Bar dataKey="count" fill="#3b82f6" radius={[4, 4, 0, 0]} />
              </BarChart>
            </ResponsiveContainer>
          ) : (
            <EmptyState message="No email type data yet" icon={Mail} />
          )}
        </CardContent>
      </Card>

      {/* Cost Estimation Note */}
      <Card className="bg-muted/50 border-dashed">
        <CardContent className="pt-6">
          <div className="flex items-start gap-3">
            <DollarSign className="h-5 w-5 text-muted-foreground mt-0.5" />
            <div className="space-y-1">
              <p className="text-sm font-medium">Cost Estimation</p>
              <p className="text-sm text-muted-foreground">
                Estimates are based on AWS SES pricing of $0.10 per 1,000 emails sent. 
                Actual costs may vary depending on your email provider and volume discounts.
              </p>
            </div>
          </div>
        </CardContent>
      </Card>
    </div>
  )
}
