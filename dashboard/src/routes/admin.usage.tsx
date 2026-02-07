import { createFileRoute } from '@tanstack/react-router'
import { useQuery } from '@tanstack/react-query'
import { api } from '@/lib/api'
import { useState } from 'react'
import { Card, CardContent, CardHeader, CardTitle } from '@/components/ui/card'
import {
  AreaChart,
  Area,
  XAxis,
  YAxis,
  CartesianGrid,
  Tooltip,
  ResponsiveContainer,
  Legend,
} from 'recharts'
import {
  Select,
  SelectContent,
  SelectItem,
  SelectTrigger,
  SelectValue,
} from '@/components/ui/select'

export const Route = createFileRoute('/admin/usage')({
  component: AdminUsagePage,
})

function AdminUsagePage() {
  const [period, setPeriod] = useState<'24h' | '7d' | '30d'>('7d')
  const { data, isLoading } = useQuery({
    queryKey: ['admin-usage', period],
    queryFn: () => api.getAdminUsage(period),
  })

  if (isLoading || !data) {
    return <div className="p-8 text-center">Loading usage data...</div>
  }

  return (
    <div className="space-y-6">
      <div className="flex items-center justify-between">
        <h2 className="text-2xl font-bold">Usage</h2>
        <Select value={period} onValueChange={(v) => setPeriod(v as '24h' | '7d' | '30d')}>
          <SelectTrigger className="w-[180px]">
            <SelectValue />
          </SelectTrigger>
          <SelectContent>
            <SelectItem value="24h">Last 24 Hours</SelectItem>
            <SelectItem value="7d">Last 7 Days</SelectItem>
            <SelectItem value="30d">Last 30 Days</SelectItem>
          </SelectContent>
        </Select>
      </div>

      <Card>
        <CardHeader>
          <CardTitle>Total Bytes Ingested</CardTitle>
        </CardHeader>
        <CardContent>
          <div className="text-3xl font-bold">
            {(data.totalBytes / 1024 / 1024).toFixed(2)} MB
          </div>
        </CardContent>
      </Card>

      <Card>
        <CardHeader>
          <CardTitle>Daily Events by Type</CardTitle>
        </CardHeader>
        <CardContent>
          {data.daily.length > 0 ? (
            <ResponsiveContainer width="100%" height={400}>
              <AreaChart data={data.daily}>
                <CartesianGrid strokeDasharray="3 3" className="stroke-muted" />
                <XAxis dataKey="date" className="text-xs" />
                <YAxis className="text-xs" />
                <Tooltip />
                <Legend />
                <Area type="monotone" dataKey="error" stackId="1" stroke="#ef4444" fill="#ef4444" name="Errors" />
                <Area type="monotone" dataKey="transaction" stackId="1" stroke="#3b82f6" fill="#3b82f6" name="Transactions" />
                <Area type="monotone" dataKey="replay" stackId="1" stroke="#22c55e" fill="#22c55e" name="Replays" />
                <Area type="monotone" dataKey="feedback" stackId="1" stroke="#eab308" fill="#eab308" name="Feedback" />
              </AreaChart>
            </ResponsiveContainer>
          ) : (
            <p className="text-muted-foreground text-center py-8">No usage data yet</p>
          )}
        </CardContent>
      </Card>
    </div>
  )
}
