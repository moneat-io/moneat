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
import {Card, CardContent, CardHeader, CardTitle} from '@/components/ui/card'
import {Badge} from '@/components/ui/badge'
import {Button} from '@/components/ui/button'
import {Table, TableBody, TableCell, TableHead, TableHeader, TableRow} from '@/components/ui/table'
import {Activity, Copy, Radio, Server} from 'lucide-react'
import {AdminSkeleton, formatBytes, formatNumber, SectionHeader} from '@/components/AdminComponents'
import {useTimezone} from '@/hooks/useTimezone'
import {formatDateTime} from '@/lib/date-format'

export const Route = createFileRoute('/admin/telemetry')({
  component: AdminTelemetryPage,
})

function AdminTelemetryPage() {
  const {data, isLoading} = useQuery({
    queryKey: ['admin-telemetry'],
    queryFn: () => api.getAdminTelemetry(),
  })
  const {timezone} = useTimezone()

  if (isLoading || !data) {
    return <AdminSkeleton />
  }

  const {deploymentCount, lastSeenAt, deployments} = data

  return (
    <div className="space-y-8">
      <SectionHeader
        title="Telemetry"
        description="Exact fields received from self-hosted deployments with telemetry enabled."
      />

      {/* Summary */}
      <div className="grid grid-cols-1 sm:grid-cols-3 gap-4">
        <Card>
          <CardContent className="pt-6">
            <div className="flex items-center gap-3">
              <div className="rounded-lg bg-chart-4/15 p-2">
                <Server className="h-5 w-5 text-chart-4" />
              </div>
              <div>
                <p className="text-2xl font-bold">{formatNumber(deploymentCount)}</p>
                <p className="text-sm text-muted-foreground">Self-hosted deployments</p>
              </div>
            </div>
          </CardContent>
        </Card>
        <Card>
          <CardContent className="pt-6">
            <div className="flex items-center gap-3">
              <div className="rounded-lg bg-success-bg p-2">
                <Activity className="h-5 w-5 text-success-fg" />
              </div>
              <div>
                <p className="text-2xl font-bold">{formatNumber(deployments.reduce((s, d) => s + d.eventCount, 0))}</p>
                <p className="text-sm text-muted-foreground">Total events (across all)</p>
              </div>
            </div>
          </CardContent>
        </Card>
        <Card>
          <CardContent className="pt-6">
            <div className="flex items-center gap-3">
              <div className="rounded-lg bg-chart-6/15 p-2">
                <Radio className="h-5 w-5 text-chart-6" />
              </div>
              <div>
                <p className="text-sm font-medium">
                  {lastSeenAt ? formatDateTime(new Date(lastSeenAt), timezone) : '—'}
                </p>
                <p className="text-sm text-muted-foreground">Last pulse received</p>
              </div>
            </div>
          </CardContent>
        </Card>
      </div>

      {/* Deployments table */}
      {deployments.length === 0 ? (
        <Card>
          <CardContent className="py-12 text-center">
            <Radio className="h-10 w-10 text-muted-foreground/40 mx-auto mb-3" />
            <p className="text-sm text-muted-foreground">
              No telemetry pulses received yet. Self-hosted instances send a pulse every 4 hours when{' '}
              <code className="font-mono text-xs bg-muted px-1 py-0.5 rounded">TELEMETRY_ENABLED=true</code>.
            </p>
          </CardContent>
        </Card>
      ) : (
        <Card>
          <CardHeader className="pb-3">
            <CardTitle className="text-sm font-semibold uppercase tracking-wider">
              Deployments ({deploymentCount})
            </CardTitle>
          </CardHeader>
          <CardContent className="p-0">
            <Table>
              <TableHeader>
                <TableRow className="hover:bg-transparent">
                  <TableHead className="text-xs">Deployment</TableHead>
                  <TableHead className="text-xs">Version</TableHead>
                  <TableHead className="text-xs">Environment</TableHead>
                  <TableHead className="text-xs">SSL</TableHead>
                  <TableHead className="text-xs text-right">CPU</TableHead>
                  <TableHead className="text-xs text-right">Memory</TableHead>
                  <TableHead className="text-xs text-right">Projects</TableHead>
                  <TableHead className="text-xs text-right">Users</TableHead>
                  <TableHead className="text-xs text-right">Events</TableHead>
                  <TableHead className="text-xs text-right">Issues</TableHead>
                  <TableHead className="text-xs text-right">Last Seen</TableHead>
                </TableRow>
              </TableHeader>
              <TableBody>
                {deployments.map((d) => (
                  <TableRow key={d.deploymentId}>
                    <TableCell className="font-mono text-xs">
                      <div className="flex items-center gap-1">
                        <span title={d.deploymentId}>
                          {d.deploymentId.length > 12
                            ? `${d.deploymentId.slice(0, 12)}…`
                            : d.deploymentId}
                        </span>
                        <Button
                          type="button"
                          variant="ghost"
                          size="icon"
                          className="h-6 w-6 shrink-0"
                          aria-label="Copy deployment ID"
                          onClick={() => void navigator.clipboard.writeText(d.deploymentId)}
                        >
                          <Copy className="h-3 w-3" />
                        </Button>
                      </div>
                    </TableCell>
                    <TableCell className="font-mono text-xs text-muted-foreground">
                      {d.version || 'unknown'}
                    </TableCell>
                    <TableCell className="text-xs text-muted-foreground">
                      {d.osName} {d.osArch} · JVM {d.jvmVersion}
                    </TableCell>
                    <TableCell>
                      <Badge
                        variant={d.sslEnabled ? 'default' : 'secondary'}
                        className="text-xs"
                      >
                        {d.sslEnabled ? 'SSL' : 'No SSL'}
                      </Badge>
                    </TableCell>
                    <TableCell className="text-xs text-right tabular-nums">
                      {d.cpuCount}
                    </TableCell>
                    <TableCell className="text-xs text-right tabular-nums whitespace-nowrap">
                      {formatBytes(d.memUsedBytes)} / {formatBytes(d.memTotalBytes)}
                    </TableCell>
                    <TableCell className="text-xs text-right tabular-nums">
                      {formatNumber(d.projectCount)}
                    </TableCell>
                    <TableCell className="text-xs text-right tabular-nums">
                      {formatNumber(d.userCount)}
                    </TableCell>
                    <TableCell className="text-xs text-right tabular-nums">
                      {formatNumber(d.eventCount)}
                    </TableCell>
                    <TableCell className="text-xs text-right tabular-nums">
                      {formatNumber(d.issueCount)}
                    </TableCell>
                    <TableCell className="text-xs text-right text-muted-foreground whitespace-nowrap">
                      {formatDateTime(new Date(d.receivedAt), timezone)}
                    </TableCell>
                  </TableRow>
                ))}
              </TableBody>
            </Table>
          </CardContent>
        </Card>
      )}

    </div>
  )
}
