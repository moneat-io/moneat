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
import {Table, TableBody, TableCell, TableHead, TableHeader, TableRow,} from '@/components/ui/table'
import {AlertTriangle, Database, Rows3} from 'lucide-react'
import {
  AdminSkeleton,
  EmptyState,
  formatBytes,
  formatNumber,
  MetricCard,
  SectionHeader,
  StorageRing,
} from '@/components/admin-components'

export const Route = createFileRoute('/admin/infrastructure')({
  component: AdminInfrastructurePage,
})

function AdminInfrastructurePage() {
  const { data, isLoading } = useQuery({
    queryKey: ['admin-infrastructure'],
    queryFn: () => api.getAdminInfrastructure(),
  })

  if (isLoading || !data) {
    return <AdminSkeleton />
  }

  const storageColor =
    data.storageUsedPercent >= 90
      ? 'text-red-600 dark:text-red-400'
      : data.storageUsedPercent >= 70
        ? 'text-amber-600 dark:text-amber-400'
        : 'text-emerald-600 dark:text-emerald-400'

  // Find the largest table for relative bar sizing
  const maxTableBytes = Math.max(...data.clickhouseTables.map((t) => t.bytesOnDisk), 1)

  return (
    <div className="space-y-8">
      <SectionHeader
        title="Infrastructure"
        description="ClickHouse storage health and table-level metrics."
      />

      {/* Scaling Alerts */}
      {data.scalingTriggerAlerts.length > 0 && (
        <Card className="border-red-200 dark:border-red-900 bg-red-50/50 dark:bg-red-950/30">
          <CardContent className="pt-6">
            <div className="flex gap-3">
              <div className="rounded-lg bg-red-100 dark:bg-red-950 p-2 h-fit">
                <AlertTriangle className="h-4 w-4 text-red-600 dark:text-red-400" />
              </div>
              <div>
                <p className="text-sm font-semibold text-red-700 dark:text-red-400 mb-1">
                  Scaling Alerts
                </p>
                <ul className="space-y-1">
                  {data.scalingTriggerAlerts.map((msg, i) => (
                    <li key={i} className="text-sm text-red-600 dark:text-red-400 flex items-start gap-1.5">
                      <span className="mt-1.5 h-1.5 w-1.5 rounded-full bg-red-500 shrink-0" />
                      {msg}
                    </li>
                  ))}
                </ul>
              </div>
            </div>
          </CardContent>
        </Card>
      )}

      {/* Metric Cards */}
      <div className="grid grid-cols-1 sm:grid-cols-2 lg:grid-cols-3 gap-4">
        {/* Storage - special card with ring */}
        <Card className="relative overflow-hidden">
          <CardHeader className="flex flex-row items-center justify-between pb-2">
            <CardTitle className="text-sm font-medium text-muted-foreground">Storage Used</CardTitle>
          </CardHeader>
          <CardContent>
            <div className="flex items-center gap-4">
              <StorageRing percent={data.storageUsedPercent} size={72} />
              <div>
                <div className={`text-2xl font-bold tracking-tight ${storageColor}`}>
                  {data.storageUsedPercent.toFixed(1)}%
                </div>
                <p className="text-xs text-muted-foreground mt-0.5">
                  {formatBytes(data.totalDiskBytes)} total
                </p>
              </div>
            </div>
          </CardContent>
        </Card>

        <MetricCard
          title="Total Rows"
          value={formatNumber(data.totalRows)}
          subtitle="across all tables"
          icon={Rows3}
          iconColor="text-blue-600 dark:text-blue-400"
          iconBg="bg-blue-100 dark:bg-blue-950"
        />
        <MetricCard
          title="ClickHouse Tables"
          value={data.clickhouseTables.length}
          subtitle="active tables"
          icon={Database}
          iconColor="text-violet-600 dark:text-violet-400"
          iconBg="bg-violet-100 dark:bg-violet-950"
        />
      </div>

      {/* Table Sizes */}
      <Card>
        <CardHeader>
          <CardTitle className="text-base">Table Sizes</CardTitle>
          <CardDescription>ClickHouse table storage breakdown with relative sizing</CardDescription>
        </CardHeader>
        <CardContent>
          {data.clickhouseTables.length > 0 ? (
            <Table>
              <TableHeader>
                <TableRow className="hover:bg-transparent">
                  <TableHead className="w-[280px]">Table</TableHead>
                  <TableHead className="text-right">Rows</TableHead>
                  <TableHead className="text-right w-[120px]">Size on Disk</TableHead>
                  <TableHead className="w-[200px]">Relative Size</TableHead>
                </TableRow>
              </TableHeader>
              <TableBody>
                {[...data.clickhouseTables]
                  .sort((a, b) => b.bytesOnDisk - a.bytesOnDisk)
                  .map((t) => {
                    const pct = (t.bytesOnDisk / maxTableBytes) * 100
                    return (
                      <TableRow key={t.table}>
                        <TableCell className="font-mono text-xs">{t.table}</TableCell>
                        <TableCell className="text-right tabular-nums">{t.rows.toLocaleString()}</TableCell>
                        <TableCell className="text-right tabular-nums font-medium">
                          {t.bytesOnDiskFormatted}
                        </TableCell>
                        <TableCell>
                          <div className="h-2 rounded-full bg-muted overflow-hidden">
                            <div
                              className="h-full rounded-full bg-blue-500/70 transition-all duration-500"
                              style={{ width: `${pct}%` }}
                            />
                          </div>
                        </TableCell>
                      </TableRow>
                    )
                  })}
              </TableBody>
            </Table>
          ) : (
            <EmptyState message="No table data" icon={Database} />
          )}
        </CardContent>
      </Card>
    </div>
  )
}
