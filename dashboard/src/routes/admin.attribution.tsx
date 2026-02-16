// Moneat - Mobile-First Error Monitoring Platform
// Copyright (C) 2026 Moneat
//
// This program is free software: you can redistribute it and/or modify
// it under the terms of the GNU Affero General Public License as published by
// the Free Software Foundation, either version 3 of the License, or
// (at your option) any later version.
//
// This program is distributed in the hope that it will be useful,
// but WITHOUT ANY WARRANTY; without even the implied warranty of
// MERCHANTABILITY or FITNESS FOR A PARTICULAR PURPOSE. See the
// GNU Affero General Public License for more details.
//
// You should have received a copy of the GNU Affero General Public License
// along with this program. If not, see <https://www.gnu.org/licenses/>.

import {createFileRoute} from '@tanstack/react-router'
import {useQuery} from '@tanstack/react-query'
import {api} from '@/lib/api'
import {Card, CardContent, CardDescription, CardHeader, CardTitle} from '@/components/ui/card'
import {
  Table,
  TableBody,
  TableCell,
  TableHead,
  TableHeader,
  TableRow,
} from '@/components/ui/table'
import {Badge} from '@/components/ui/badge'
import {Loader2, TrendingUp, Users, DollarSign, Target} from 'lucide-react'
import {Select, SelectContent, SelectItem, SelectTrigger, SelectValue} from '@/components/ui/select'
import {useState} from 'react'

export const Route = createFileRoute('/admin/attribution')({
  component: AttributionPage,
})

interface AttributionMetrics {
  source: string | null
  medium: string | null
  campaign: string | null
  signups: number
  paidOrganizations: number
  conversionRate: number
  totalMrr: string
  averageMrr: string
  estimatedLtv: string
}

interface AttributionSummary {
  totalSignups: number
  totalPaidOrganizations: number
  overallConversionRate: number
  totalMrr: string
}

interface AttributionAnalyticsResponse {
  metrics: AttributionMetrics[]
  summary: AttributionSummary
}

function AttributionPage() {
  const [groupBy, setGroupBy] = useState<'campaign' | 'source' | 'medium' | 'all'>('campaign')
  
  const {data, isLoading, error} = useQuery<AttributionAnalyticsResponse>({
    queryKey: ['attribution', groupBy],
    queryFn: () => api.getAdminAttribution(groupBy),
  })

  const formatCurrency = (value: string) => {
    const num = parseFloat(value)
    return new Intl.NumberFormat('en-US', {
      style: 'currency',
      currency: 'USD',
      minimumFractionDigits: 2,
    }).format(num)
  }

  const formatPercent = (value: number) => {
    return `${value.toFixed(1)}%`
  }

  const getDisplayName = (metric: AttributionMetrics) => {
    if (groupBy === 'campaign') {
      return metric.campaign || 'No Campaign'
    } else if (groupBy === 'source') {
      return metric.source || 'No Source'
    } else if (groupBy === 'medium') {
      return metric.medium || 'No Medium'
    } else {
      // 'all' - show full attribution
      const parts = []
      if (metric.source) parts.push(metric.source)
      if (metric.medium) parts.push(metric.medium)
      if (metric.campaign) parts.push(metric.campaign)
      return parts.length > 0 ? parts.join(' / ') : 'Direct / None'
    }
  }

  return (
    <div className="container mx-auto py-8 space-y-8">
      <div>
        <h1 className="text-3xl font-bold">Attribution Analytics</h1>
        <p className="text-muted-foreground mt-2">Track ROAS and marketing campaign performance</p>
      </div>

      {/* Summary Cards */}
      {data && (
        <div className="grid gap-4 md:grid-cols-4">
          <Card>
            <CardHeader className="flex flex-row items-center justify-between space-y-0 pb-2">
              <CardTitle className="text-sm font-medium">Total Signups</CardTitle>
              <Users className="h-4 w-4 text-muted-foreground" />
            </CardHeader>
            <CardContent>
              <div className="text-2xl font-bold">{data.summary.totalSignups}</div>
            </CardContent>
          </Card>
          
          <Card>
            <CardHeader className="flex flex-row items-center justify-between space-y-0 pb-2">
              <CardTitle className="text-sm font-medium">Paid Organizations</CardTitle>
              <Target className="h-4 w-4 text-muted-foreground" />
            </CardHeader>
            <CardContent>
              <div className="text-2xl font-bold">{data.summary.totalPaidOrganizations}</div>
            </CardContent>
          </Card>
          
          <Card>
            <CardHeader className="flex flex-row items-center justify-between space-y-0 pb-2">
              <CardTitle className="text-sm font-medium">Conversion Rate</CardTitle>
              <TrendingUp className="h-4 w-4 text-muted-foreground" />
            </CardHeader>
            <CardContent>
              <div className="text-2xl font-bold">{formatPercent(data.summary.overallConversionRate)}</div>
            </CardContent>
          </Card>
          
          <Card>
            <CardHeader className="flex flex-row items-center justify-between space-y-0 pb-2">
              <CardTitle className="text-sm font-medium">Total MRR</CardTitle>
              <DollarSign className="h-4 w-4 text-muted-foreground" />
            </CardHeader>
            <CardContent>
              <div className="text-2xl font-bold">{formatCurrency(data.summary.totalMrr)}</div>
            </CardContent>
          </Card>
        </div>
      )}

      {/* Filters */}
      <Card>
        <CardHeader>
          <CardTitle>Campaign Performance</CardTitle>
          <CardDescription>Revenue and conversion metrics by attribution source</CardDescription>
        </CardHeader>
        <CardContent className="space-y-4">
          <div className="flex items-center gap-4">
            <label className="text-sm font-medium">Group By:</label>
            <Select value={groupBy} onValueChange={(value: any) => setGroupBy(value)}>
              <SelectTrigger className="w-[200px]">
                <SelectValue />
              </SelectTrigger>
              <SelectContent>
                <SelectItem value="campaign">Campaign</SelectItem>
                <SelectItem value="source">Source</SelectItem>
                <SelectItem value="medium">Medium</SelectItem>
                <SelectItem value="all">All (Source/Medium/Campaign)</SelectItem>
              </SelectContent>
            </Select>
          </div>

          {isLoading && (
            <div className="flex items-center justify-center py-12">
              <Loader2 className="h-8 w-8 animate-spin text-muted-foreground" />
            </div>
          )}

          {error && (
            <div className="text-center py-12 text-destructive">
              Failed to load attribution data. Please try again.
            </div>
          )}

          {data && data.metrics.length > 0 && (
            <div className="rounded-md border">
              <Table>
                <TableHeader>
                  <TableRow>
                    <TableHead>Name</TableHead>
                    <TableHead className="text-right">Signups</TableHead>
                    <TableHead className="text-right">Paid</TableHead>
                    <TableHead className="text-right">Conv. Rate</TableHead>
                    <TableHead className="text-right">Total MRR</TableHead>
                    <TableHead className="text-right">Avg MRR</TableHead>
                    <TableHead className="text-right">Est. LTV (12mo)</TableHead>
                  </TableRow>
                </TableHeader>
                <TableBody>
                  {data.metrics.map((metric, idx) => (
                    <TableRow key={idx}>
                      <TableCell className="font-medium">
                        {getDisplayName(metric)}
                      </TableCell>
                      <TableCell className="text-right">{metric.signups}</TableCell>
                      <TableCell className="text-right">
                        {metric.paidOrganizations}
                        {metric.paidOrganizations > 0 && (
                          <Badge variant="secondary" className="ml-2">
                            Active
                          </Badge>
                        )}
                      </TableCell>
                      <TableCell className="text-right">
                        <span className={metric.conversionRate > 10 ? 'text-green-600 font-semibold' : ''}>
                          {formatPercent(metric.conversionRate)}
                        </span>
                      </TableCell>
                      <TableCell className="text-right font-medium">
                        {formatCurrency(metric.totalMrr)}
                      </TableCell>
                      <TableCell className="text-right">
                        {formatCurrency(metric.averageMrr)}
                      </TableCell>
                      <TableCell className="text-right text-green-600 font-semibold">
                        {formatCurrency(metric.estimatedLtv)}
                      </TableCell>
                    </TableRow>
                  ))}
                </TableBody>
              </Table>
            </div>
          )}

          {data && data.metrics.length === 0 && (
            <div className="text-center py-12 text-muted-foreground">
              No attribution data available yet. UTM parameters will be captured starting from new signups.
            </div>
          )}
        </CardContent>
      </Card>

      {/* ROAS Calculation Helper */}
      <Card>
        <CardHeader>
          <CardTitle>ROAS Calculation</CardTitle>
          <CardDescription>
            To calculate Return on Ad Spend (ROAS), divide the Estimated LTV by your ad spend for each campaign.
            <br />
            <span className="text-sm mt-2 block">
              Example: If "reddit-launch" campaign has $2,610 LTV and you spent $1,200 → ROAS = 2.175 (217.5% return)
            </span>
          </CardDescription>
        </CardHeader>
      </Card>
    </div>
  )
}
