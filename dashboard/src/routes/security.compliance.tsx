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
import {ShieldCheck, TrendingUp} from 'lucide-react'
import {api, type ComplianceFrameworkTrend} from '@/lib/api'
import {Badge, type BadgeProps} from '@/components/ui/badge'
import {SectionCard} from '@/components/ui/section-card'
import {StatCard} from '@/components/ui/stat-card'
import {EmptyState} from '@/components/ui/empty-state'
import {SecurityError} from '@/components/security/SecurityError'

export const Route = createFileRoute('/security/compliance')({
  component: ComplianceFindings,
})

// Compliance status mapped onto the shared status language.
function statusBadgeVariant(status?: string): BadgeProps['variant'] {
  switch ((status ?? '').toLowerCase()) {
    case 'passed':
      return 'success'
    case 'failed':
      return 'danger'
    case 'error':
      return 'warning'
    default:
      return 'neutral'
  }
}

const SUMMARY_TONE: Record<string, 'success' | 'danger' | 'warning' | 'neutral'> = {
  passed: 'success',
  failed: 'danger',
  error: 'warning',
  skipped: 'neutral',
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
  const summaryQuery = useQuery({
    queryKey: ['compliance-summary'],
    queryFn: () => api.get<{summary?: ComplianceSummary[]}>('/v1/security/compliance/summary'),
  })

  const findingsQuery = useQuery({
    queryKey: ['compliance-findings'],
    queryFn: () => api.get<{findings?: ComplianceFinding[]; totalCount?: number}>('/v1/security/compliance?limit=50'),
  })
  const trendQuery = useQuery({
    queryKey: ['compliance-trends'],
    queryFn: () => api.getComplianceTrends(),
  })

  const findings: ComplianceFinding[] = findingsQuery.data?.findings ?? []
  const summary: ComplianceSummary[] = summaryQuery.data?.summary ?? []
  const trends = trendQuery.data?.frameworks ?? []

  return (
    <div className="space-y-4">
      {summaryQuery.isError ? (
        <SecurityError title="Couldn’t load compliance summary" error={summaryQuery.error} />
      ) : summary.length > 0 ? (
        <div className="grid grid-cols-2 gap-4 lg:grid-cols-4">
          {['passed', 'failed', 'skipped', 'error'].map(status => {
            const count = summary
              .filter((s) => s.status === status)
              .reduce((a: number, s) => a + (s.count || 0), 0)
            return (
              <StatCard
                key={status}
                label={status}
                value={count.toLocaleString()}
                tone={SUMMARY_TONE[status] ?? 'neutral'}
              />
            )
          })}
        </div>
      ) : null}

      {trendQuery.isError ? (
        <SecurityError title="Couldn’t load compliance trends" error={trendQuery.error} />
      ) : (
        trends.length > 0 && <PassRateTrends trends={trends} />
      )}

      {findingsQuery.isError ? (
        <SecurityError title="Couldn’t load compliance findings" error={findingsQuery.error} />
      ) : findingsQuery.isLoading ? (
        <div className="flex justify-center py-8">
          <div className="h-6 w-6 animate-spin rounded-full border-2 border-muted border-t-primary" />
        </div>
      ) : (
        <SectionCard
          title="Compliance findings"
          icon={ShieldCheck}
          iconTone="info"
          count={findingsQuery.data?.totalCount || 0}
          flushBody
        >
          <p className="border-b px-4 py-2 text-xs text-muted-foreground">
            CIS, PCI, SOC2, HIPAA rule evaluations
          </p>
          {findings.length === 0 ? (
            <div className="p-4">
              <EmptyState
                icon={ShieldCheck}
                title="No compliance findings"
                description="Posture evaluations from your frameworks will appear here once checks run."
              />
            </div>
          ) : (
            <div className="overflow-x-auto">
              <table className="w-full text-sm">
                <thead>
                  <tr className="border-b bg-muted/40 text-left text-[11px] uppercase tracking-wider text-muted-foreground">
                    <th className="px-4 py-2 font-medium">Framework</th>
                    <th className="px-4 py-2 font-medium">Rule</th>
                    <th className="px-4 py-2 font-medium">Status</th>
                    <th className="px-4 py-2 font-medium">Resource</th>
                    <th className="px-4 py-2 font-medium">Evaluated</th>
                  </tr>
                </thead>
                <tbody className="divide-y divide-border/40">
                  {findings.map((f) => (
                    <tr key={f.findingId} className="transition-colors hover:bg-accent/40">
                      <td className="px-4 py-2">
                        <Badge variant="neutral" size="sm">{f.framework}</Badge>
                      </td>
                      <td className="px-4 py-2">{f.ruleName}</td>
                      <td className="px-4 py-2">
                        <Badge variant={statusBadgeVariant(f.status)} size="sm" className="capitalize">
                          {f.status}
                        </Badge>
                      </td>
                      <td className="px-4 py-2 text-muted-foreground">{f.resourceType}: {f.resourceName}</td>
                      <td className="px-4 py-2 tabular-nums text-muted-foreground">{f.evaluatedAt}</td>
                    </tr>
                  ))}
                </tbody>
              </table>
            </div>
          )}
        </SectionCard>
      )}
    </div>
  )
}

function PassRateTrends({trends}: {trends: ComplianceFrameworkTrend[]}) {
  return (
    <SectionCard
      title="Pass-rate trend"
      icon={TrendingUp}
      iconTone="success"
      bodyClassName="grid gap-2 md:grid-cols-2"
    >
      <p className="text-xs text-muted-foreground md:col-span-2">
        Daily posture pass rate by framework
      </p>
      {trends.map((trend) => {
        const buckets = trend.buckets.slice(-14)
        const latest = buckets.at(-1)
        const passRate = Math.round((latest?.passRate ?? 0) * 100)
        return (
          <div key={trend.framework} className="rounded-md border p-2">
            <div className="mb-2 flex items-center justify-between gap-2">
              <Badge variant="neutral" size="sm">{trend.framework}</Badge>
              <span className="text-xs font-medium tabular-nums">{passRate}%</span>
            </div>
            <div className="flex h-8 items-end gap-1">
              {buckets.map((bucket) => (
                <div
                  key={bucket.bucketStart}
                  className="min-w-2 flex-1 rounded-sm bg-success-solid/70"
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
    </SectionCard>
  )
}
