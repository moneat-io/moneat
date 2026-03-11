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
import {Card, CardContent, CardDescription, CardHeader, CardTitle} from '@/components/ui/card'
import {Badge} from '@/components/ui/badge'
import {
    Activity,
    AlertTriangle,
    Cpu,
    Fingerprint,
    FolderKanban,
    HardDrive,
    Lock,
    Radio,
    Server,
    ShieldCheck,
    Users,
} from 'lucide-react'
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
        description="Anonymous usage diagnostics received from self-hosted deployments."
      />

      {/* Summary */}
      <div className="grid grid-cols-1 sm:grid-cols-3 gap-4">
        <Card>
          <CardContent className="pt-6">
            <div className="flex items-center gap-3">
              <div className="rounded-lg bg-violet-100 dark:bg-violet-950 p-2">
                <Server className="h-5 w-5 text-violet-600 dark:text-violet-400" />
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
              <div className="rounded-lg bg-emerald-100 dark:bg-emerald-950 p-2">
                <Activity className="h-5 w-5 text-emerald-600 dark:text-emerald-400" />
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
              <div className="rounded-lg bg-sky-100 dark:bg-sky-950 p-2">
                <Radio className="h-5 w-5 text-sky-600 dark:text-sky-400" />
              </div>
              <div>
                <p className="text-sm font-medium">{lastSeenAt ? formatDateTime(new Date(lastSeenAt), timezone) : '—'}</p>
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
              No telemetry pulses received yet. Self-hosted instances send a pulse every 4 hours when <code className="font-mono text-xs bg-muted px-1 py-0.5 rounded">TELEMETRY_ENABLED=true</code>.
            </p>
          </CardContent>
        </Card>
      ) : (
        <div className="space-y-3">
          <h3 className="text-sm font-semibold text-muted-foreground uppercase tracking-wider">
            Deployments ({deploymentCount})
          </h3>
          {deployments.map((d) => (
            <Card key={d.deploymentId}>
              <CardHeader className="pb-3">
                <div className="flex items-center justify-between flex-wrap gap-2">
                  <div className="flex items-center gap-2">
                    <Fingerprint className="h-4 w-4 text-muted-foreground" />
                    <CardTitle className="text-sm font-mono">{d.deploymentId}</CardTitle>
                  </div>
                  <div className="flex items-center gap-2">
                    <Badge variant={d.sslEnabled ? 'default' : 'secondary'} className="text-xs">
                      {d.sslEnabled ? <><Lock className="h-3 w-3 mr-1" />SSL</> : 'No SSL'}
                    </Badge>
                    <span className="text-xs text-muted-foreground">
                      {formatDateTime(new Date(d.receivedAt), timezone)}
                    </span>
                  </div>
                </div>
                <CardDescription className="flex items-center gap-1">
                  <ShieldCheck className="h-3 w-3" />
                  {d.osName} {d.osArch} · JVM {d.jvmVersion}
                </CardDescription>
              </CardHeader>
              <CardContent>
                <div className="grid grid-cols-2 sm:grid-cols-4 lg:grid-cols-6 gap-3 text-sm">
                  <div className="flex items-center gap-1.5">
                    <Cpu className="h-3.5 w-3.5 text-blue-500 shrink-0" />
                    <span className="text-muted-foreground">CPU:</span>
                    <span className="font-medium">{d.cpuCount}</span>
                  </div>
                  <div className="flex items-center gap-1.5">
                    <HardDrive className="h-3.5 w-3.5 text-orange-500 shrink-0" />
                    <span className="text-muted-foreground">Mem:</span>
                    <span className="font-medium">{formatBytes(d.memUsedBytes)}</span>
                    <span className="text-muted-foreground">/ {formatBytes(d.memTotalBytes)}</span>
                  </div>
                  <div className="flex items-center gap-1.5">
                    <FolderKanban className="h-3.5 w-3.5 text-indigo-500 shrink-0" />
                    <span className="text-muted-foreground">Projects:</span>
                    <span className="font-medium">{formatNumber(d.projectCount)}</span>
                  </div>
                  <div className="flex items-center gap-1.5">
                    <Users className="h-3.5 w-3.5 text-teal-500 shrink-0" />
                    <span className="text-muted-foreground">Users:</span>
                    <span className="font-medium">{formatNumber(d.userCount)}</span>
                  </div>
                  <div className="flex items-center gap-1.5">
                    <Activity className="h-3.5 w-3.5 text-amber-500 shrink-0" />
                    <span className="text-muted-foreground">Events:</span>
                    <span className="font-medium">{formatNumber(d.eventCount)}</span>
                  </div>
                  <div className="flex items-center gap-1.5">
                    <AlertTriangle className="h-3.5 w-3.5 text-red-500 shrink-0" />
                    <span className="text-muted-foreground">Issues:</span>
                    <span className="font-medium">{formatNumber(d.issueCount)}</span>
                  </div>
                </div>
              </CardContent>
            </Card>
          ))}
        </div>
      )}

      {/* What We Collect */}
      <Card>
        <CardHeader>
          <CardTitle className="text-base">What We Collect</CardTitle>
          <CardDescription>
            All data is anonymous and tied only to a randomly-generated deployment ID. No personal
            information, event contents, or secrets are ever transmitted.
          </CardDescription>
        </CardHeader>
        <CardContent>
          <div className="grid grid-cols-1 sm:grid-cols-2 gap-6 text-sm">
            <div>
              <p className="font-medium mb-2 text-emerald-700 dark:text-emerald-400">✓ Collected</p>
              <ul className="space-y-1 text-muted-foreground">
                <li>• CPU count, memory usage, OS name &amp; architecture</li>
                <li>• JVM version</li>
                <li>• Aggregate counts: projects, users, events, issues</li>
                <li>• SSL enabled status</li>
                <li>• Randomly-generated deployment identifier</li>
              </ul>
            </div>
            <div>
              <p className="font-medium mb-2 text-red-700 dark:text-red-400">✗ Never Collected</p>
              <ul className="space-y-1 text-muted-foreground">
                <li>• User emails, names, or personal data</li>
                <li>• Event contents, stack traces, or session replays</li>
                <li>• API keys, DSNs, or secrets</li>
                <li>• IP addresses or geolocation</li>
              </ul>
            </div>
          </div>
          <div className="mt-6 p-3 rounded-lg bg-muted/50 text-xs text-muted-foreground">
            Self-hosted instances opt out by setting <code className="font-mono bg-muted px-1 py-0.5 rounded">TELEMETRY_ENABLED=false</code> in
            their <code className="font-mono bg-muted px-1 py-0.5 rounded">.env</code> file.
          </div>
        </CardContent>
      </Card>
    </div>
  )
}
