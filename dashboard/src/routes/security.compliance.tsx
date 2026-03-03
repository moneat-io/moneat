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
import {api} from '@/lib/api'
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

  const findings: ComplianceFinding[] = data?.findings ?? []
  const summary: ComplianceSummary[] = summaryData?.summary ?? []

  return (
    <div className="space-y-6">
      {/* Summary */}
      {summary.length > 0 && (
        <div className="grid gap-4 md:grid-cols-4">
          {['passed', 'failed', 'skipped', 'error'].map(status => {
            const count = summary
              .filter((s) => s.status === status)
              .reduce((a: number, s) => a + (s.count || 0), 0)
            return (
              <Card key={status}>
                <CardContent className="pt-4">
                  <div className="flex items-center justify-between">
                    <span className="text-sm font-medium capitalize">{status}</span>
                    <Badge variant="outline" className={cn('text-xs', statusColors[status] || '')}>{count}</Badge>
                  </div>
                </CardContent>
              </Card>
            )
          })}
        </div>
      )}

      {/* Findings table */}
      {isLoading ? (
        <div className="flex justify-center py-12"><div className="animate-spin rounded-full h-8 w-8 border-2 border-muted border-t-primary" /></div>
      ) : (
        <Card>
          <CardHeader>
            <CardTitle>Compliance Findings ({data?.totalCount || 0})</CardTitle>
            <CardDescription>CIS, PCI, SOC2, HIPAA rule evaluations</CardDescription>
          </CardHeader>
          <CardContent>
            <div className="overflow-x-auto">
              <table className="w-full text-sm">
                <thead>
                  <tr className="border-b text-left text-muted-foreground">
                    <th className="pb-2 pr-4 font-medium">Framework</th>
                    <th className="pb-2 pr-4 font-medium">Rule</th>
                    <th className="pb-2 pr-4 font-medium">Status</th>
                    <th className="pb-2 pr-4 font-medium">Resource</th>
                    <th className="pb-2 font-medium">Evaluated</th>
                  </tr>
                </thead>
                <tbody>
                  {findings.map((f) => (
                    <tr key={f.findingId} className="border-b last:border-0 hover:bg-muted/30">
                      <td className="py-2 pr-4"><Badge variant="outline" className="text-xs">{f.framework}</Badge></td>
                      <td className="py-2 pr-4">{f.ruleName}</td>
                      <td className="py-2 pr-4">
                        <Badge variant="outline" className={cn('text-xs', statusColors[f.status ?? ''] || '')}>
                          {f.status}
                        </Badge>
                      </td>
                      <td className="py-2 pr-4 text-xs">{f.resourceType}: {f.resourceName}</td>
                      <td className="py-2 text-muted-foreground text-xs">{f.evaluatedAt}</td>
                    </tr>
                  ))}
                  {findings.length === 0 && (
                    <tr><td colSpan={5} className="py-8 text-center text-muted-foreground">No compliance findings</td></tr>
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
