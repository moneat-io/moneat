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
  Database,
  Fingerprint,
  FolderKanban,
  HardDrive,
  Lock,
  Radio,
  ShieldCheck,
  Users,
} from 'lucide-react'
import {AdminSkeleton, formatBytes, formatNumber, MetricCard, SectionHeader} from '@/components/admin-components'

export const Route = createFileRoute('/admin/telemetry')({
  component: AdminTelemetryPage,
})

function AdminTelemetryPage() {
  const {data, isLoading} = useQuery({
    queryKey: ['admin-telemetry'],
    queryFn: () => api.getAdminTelemetry(),
  })

  if (isLoading || !data) {
    return <AdminSkeleton />
  }

  const m = data.metrics

  return (
    <div className="space-y-8">
      <SectionHeader
        title="Telemetry"
        description="Anonymous usage diagnostics for self-hosted deployments."
      />

      {/* Status Card */}
      <Card>
        <CardHeader className="pb-3">
          <div className="flex items-center justify-between">
            <CardTitle className="text-base">Pulse Status</CardTitle>
            <Badge variant={data.enabled ? 'default' : 'secondary'} className="text-xs">
              {data.enabled ? 'Active' : 'Inactive'}
            </Badge>
          </div>
          <CardDescription>
            {data.enabled
              ? 'Anonymous metrics are periodically sent to help improve Moneat for self-hosted users.'
              : !data.selfHostMode
                ? 'Telemetry is only active on self-hosted deployments (SELF_HOST=true).'
                : 'Telemetry is disabled. Set TELEMETRY_ENABLED=true to opt in.'}
          </CardDescription>
        </CardHeader>
        <CardContent>
          <div className="grid grid-cols-1 sm:grid-cols-3 gap-4 text-sm">
            <div className="flex items-center gap-2">
              <Radio className="h-4 w-4 text-muted-foreground" />
              <span className="text-muted-foreground">Self-host mode:</span>
              <span className="font-medium">{data.selfHostMode ? 'Yes' : 'No'}</span>
            </div>
            <div className="flex items-center gap-2">
              <Activity className="h-4 w-4 text-muted-foreground" />
              <span className="text-muted-foreground">Config enabled:</span>
              <span className="font-medium">{data.telemetryConfigEnabled ? 'Yes' : 'No'}</span>
            </div>
            <div className="flex items-center gap-2 min-w-0">
              <Database className="h-4 w-4 text-muted-foreground shrink-0" />
              <span className="text-muted-foreground shrink-0">Endpoint:</span>
              <span className="font-mono text-xs truncate">{data.endpoint}</span>
            </div>
          </div>
        </CardContent>
      </Card>

      {/* Metrics Snapshot */}
      {m ? (
        <>
          {/* Deployment Info */}
          <div>
            <h3 className="text-sm font-semibold text-muted-foreground mb-3 uppercase tracking-wider">
              Deployment
            </h3>
            <div className="grid grid-cols-1 sm:grid-cols-2 lg:grid-cols-4 gap-4">
              <MetricCard
                title="Deployment ID"
                value={m.deploymentId.length > 12 ? `${m.deploymentId.slice(0, 8)}…` : m.deploymentId}
                subtitle={m.deploymentId}
                icon={Fingerprint}
                iconColor="text-violet-600 dark:text-violet-400"
                iconBg="bg-violet-100 dark:bg-violet-950"
              />
              <MetricCard
                title="OS"
                value={m.osName}
                subtitle={m.osArch}
                icon={HardDrive}
                iconColor="text-slate-600 dark:text-slate-400"
                iconBg="bg-slate-100 dark:bg-slate-900"
              />
              <MetricCard
                title="JVM Version"
                value={m.jvmVersion}
                icon={ShieldCheck}
                iconColor="text-sky-600 dark:text-sky-400"
                iconBg="bg-sky-100 dark:bg-sky-950"
              />
              <MetricCard
                title="SSL"
                value={m.sslEnabled ? 'Enabled' : 'Disabled'}
                icon={Lock}
                iconColor={
                  m.sslEnabled
                    ? 'text-emerald-600 dark:text-emerald-400'
                    : 'text-amber-600 dark:text-amber-400'
                }
                iconBg={
                  m.sslEnabled
                    ? 'bg-emerald-100 dark:bg-emerald-950'
                    : 'bg-amber-100 dark:bg-amber-950'
                }
              />
            </div>
          </div>

          {/* System Resources */}
          <div>
            <h3 className="text-sm font-semibold text-muted-foreground mb-3 uppercase tracking-wider">
              System Resources
            </h3>
            <div className="grid grid-cols-1 sm:grid-cols-3 gap-4">
              <MetricCard
                title="CPU Cores"
                value={m.cpuCount}
                icon={Cpu}
                iconColor="text-blue-600 dark:text-blue-400"
                iconBg="bg-blue-100 dark:bg-blue-950"
              />
              <MetricCard
                title="Memory Used"
                value={formatBytes(m.memUsedBytes)}
                subtitle={`of ${formatBytes(m.memTotalBytes)} total`}
                icon={HardDrive}
                iconColor="text-orange-600 dark:text-orange-400"
                iconBg="bg-orange-100 dark:bg-orange-950"
              />
              <MetricCard
                title="Memory Usage"
                value={`${m.memTotalBytes > 0 ? ((m.memUsedBytes / m.memTotalBytes) * 100).toFixed(1) : 0}%`}
                icon={Activity}
                iconColor="text-rose-600 dark:text-rose-400"
                iconBg="bg-rose-100 dark:bg-rose-950"
              />
            </div>
          </div>

          {/* Usage Counts */}
          <div>
            <h3 className="text-sm font-semibold text-muted-foreground mb-3 uppercase tracking-wider">
              Usage Counts
            </h3>
            <div className="grid grid-cols-2 sm:grid-cols-4 gap-4">
              <MetricCard
                title="Projects"
                value={formatNumber(m.projectCount)}
                icon={FolderKanban}
                iconColor="text-indigo-600 dark:text-indigo-400"
                iconBg="bg-indigo-100 dark:bg-indigo-950"
              />
              <MetricCard
                title="Users"
                value={formatNumber(m.userCount)}
                icon={Users}
                iconColor="text-teal-600 dark:text-teal-400"
                iconBg="bg-teal-100 dark:bg-teal-950"
              />
              <MetricCard
                title="Events"
                value={formatNumber(m.eventCount)}
                icon={Activity}
                iconColor="text-amber-600 dark:text-amber-400"
                iconBg="bg-amber-100 dark:bg-amber-950"
              />
              <MetricCard
                title="Issues"
                value={formatNumber(m.issueCount)}
                icon={AlertTriangle}
                iconColor="text-red-600 dark:text-red-400"
                iconBg="bg-red-100 dark:bg-red-950"
              />
            </div>
          </div>
        </>
      ) : (
        <Card>
          <CardContent className="py-12 text-center">
            <Radio className="h-10 w-10 text-muted-foreground/40 mx-auto mb-3" />
            <p className="text-sm text-muted-foreground">
              {data.selfHostMode
                ? 'Enable telemetry to see collected metrics here.'
                : 'Telemetry is only available in self-hosted mode.'}
            </p>
          </CardContent>
        </Card>
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
            To disable telemetry, set <code className="font-mono bg-muted px-1 py-0.5 rounded">TELEMETRY_ENABLED=false</code> in
            your <code className="font-mono bg-muted px-1 py-0.5 rounded">.env</code> file and restart the backend.
          </div>
        </CardContent>
      </Card>
    </div>
  )
}
