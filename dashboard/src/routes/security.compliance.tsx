// Moneat - observability platform
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
import {api, type ComplianceFrameworkTrend} from '@/lib/api'
import {Badge} from '@/components/ui/badge'
import {Card, CardContent, CardHeader, CardTitle, CardDescription} from '@/components/ui/card'
import {cn} from '@/lib/utils'

export const Route = createFileRoute('/security/compliance')({
  component: ComplianceFindings,
})

const statusColors: Record<string, string> = {
  passed: 'bg-green-500/15 text-green-500 border-green-500/30',
  failed: 'bg-red-500/15 text-red-500 border-red-500/30',
  skipped: 'bg-slate-500/15 text-slate-400 border-slate-500/30',
  error: 'bg-amber-500/15 text-amber-500 border-amber-500/30',
}

interface ComplianceSummary {
  status?: string
  count?: number
}

interface ComplianceFinding {
  findingId?: string
  framework?: string
  ruleName?: string
  status?: string
  resourceType?: string
  resourceName?: string
  evaluatedAt?: string
}

function ComplianceFindings() {
  const {data: summaryData} = useQuery({
    queryKey: ['compliance-summary'],
    queryFn: () => api.get<{summary?: ComplianceSummary[]}>('/v1/security/compliance/summary'),
  })

  const {data, isLoading} = useQuery({
    queryKey: ['compliance-findings'],
    queryFn: () => api.get<{findings?: ComplianceFinding[]; totalCount?: number}>('/v1/security/compliance?limit=50'),
  })
  const {data: trendData} = useQuery({
    queryKey: ['compliance-trends'],
    queryFn: () => api.getComplianceTrends(),
  })

  const findings: ComplianceFinding[] = data?.findings ?? []
  const summary: ComplianceSummary[] = summaryData?.summary ?? []
  const trends = trendData?.frameworks ?? []

  return (
    <div className="space-y-4">
      {/* Summary */}
      {summary.length > 0 && (
        <div className="grid gap-2 md:grid-cols-4">
          {['passed', 'failed', 'skipped', 'error'].map(status => {
            const count = summary
              .filter((s) => s.status === status)
              .reduce((a: number, s) => a + (s.count || 0), 0)
            return (
              <Card key={status}>
                <CardContent className="pt-3 pb-2 px-3">
                  <div className="flex items-center justify-between">
                    <span className="text-xs font-medium capitalize">{status}</span>
                    <Badge variant="outline" className={cn('text-xs', statusColors[status] || '')}>{count}</Badge>
                  </div>
                </CardContent>
              </Card>
            )
          })}
        </div>
      )}

      {trends.length > 0 && <PassRateTrends trends={trends} />}

      {/* Findings table */}
      {isLoading ? (
        <div className="flex justify-center py-8">
          <div className="h-6 w-6 animate-spin rounded-full border-2 border-muted border-t-primary" />
        </div>
      ) : (
        <Card>
          <CardHeader className="py-2 px-3">
            <CardTitle className="text-sm">Compliance Findings ({data?.totalCount || 0})</CardTitle>
            <CardDescription className="text-xs">CIS, PCI, SOC2, HIPAA rule evaluations</CardDescription>
          </CardHeader>
          <CardContent className="p-3 pt-0">
            <div className="overflow-x-auto">
              <table className="w-full text-xs">
                <thead>
                  <tr className="border-b text-left text-muted-foreground">
                    <th className="pb-1.5 pr-2 font-medium">Framework</th>
                    <th className="pb-1.5 pr-2 font-medium">Rule</th>
                    <th className="pb-1.5 pr-2 font-medium">Status</th>
                    <th className="pb-1.5 pr-2 font-medium">Resource</th>
                    <th className="pb-1.5 font-medium">Evaluated</th>
                  </tr>
                </thead>
                <tbody>
                  {findings.map((f) => (
                    <tr key={f.findingId} className="border-b last:border-0 hover:bg-muted/30">
                      <td className="py-1.5 pr-2">
                        <Badge variant="outline" className="text-[10px]">{f.framework}</Badge>
                      </td>
                      <td className="py-1.5 pr-2">{f.ruleName}</td>
                      <td className="py-1.5 pr-2">
                        <Badge variant="outline" className={cn('text-[10px]', statusColors[f.status ?? ''] || '')}>
                          {f.status}
                        </Badge>
                      </td>
                      <td className="py-1.5 pr-2">{f.resourceType}: {f.resourceName}</td>
                      <td className="py-1.5 text-muted-foreground">{f.evaluatedAt}</td>
                    </tr>
                  ))}
                  {findings.length === 0 && (
                    <tr>
                      <td colSpan={5} className="py-6 text-center text-muted-foreground">
                        No compliance findings
                      </td>
                    </tr>
                  )}
                </tbody>
              </table>
            </div>
          </CardContent>
        </Card>
      )}
    </div>
  )
}

function PassRateTrends({trends}: {trends: ComplianceFrameworkTrend[]}) {
  return (
    <Card>
      <CardHeader className="py-2 px-3">
        <CardTitle className="text-sm">Pass-Rate Trend</CardTitle>
        <CardDescription className="text-xs">Daily posture pass rate by framework</CardDescription>
      </CardHeader>
      <CardContent className="grid gap-2 p-3 pt-0 md:grid-cols-2">
        {trends.map((trend) => {
          const buckets = trend.buckets.slice(-14)
          const latest = buckets.at(-1)
          const passRate = Math.round((latest?.passRate ?? 0) * 100)
          return (
            <div key={trend.framework} className="rounded border p-2">
              <div className="mb-2 flex items-center justify-between gap-2">
                <Badge variant="outline" className="text-[10px]">{trend.framework}</Badge>
                <span className="text-xs font-medium">{passRate}%</span>
              </div>
              <div className="flex h-8 items-end gap-1">
                {buckets.map((bucket) => (
                  <div
                    key={bucket.bucketStart}
                    className="min-w-2 flex-1 rounded-sm bg-emerald-500/70"
                    style={{height: `${Math.max(8, Math.round(bucket.passRate * 32))}px`}}
                    title={`${bucket.bucketStart}: ${Math.round(bucket.passRate * 100)}%`}
                  />
                ))}
              </div>
              <div className="mt-1 text-[10px] text-muted-foreground">
                {latest ? `${latest.passed} passed, ${latest.failed + latest.error} failed or errored` : 'No buckets'}
              </div>
            </div>
          )
        })}
      </CardContent>
    </Card>
  )
}
