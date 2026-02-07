import { createFileRoute } from '@tanstack/react-router'
import { useQuery } from '@tanstack/react-query'
import { api } from '@/lib/api'
import { Card, CardContent, CardHeader, CardTitle } from '@/components/ui/card'
import { Server, AlertTriangle } from 'lucide-react'

export const Route = createFileRoute('/admin/infrastructure')({
  component: AdminInfrastructurePage,
})

function AdminInfrastructurePage() {
  const { data, isLoading } = useQuery({
    queryKey: ['admin-infrastructure'],
    queryFn: () => api.getAdminInfrastructure(),
  })

  if (isLoading || !data) {
    return <div className="p-8 text-center">Loading infrastructure data...</div>
  }

  return (
    <div className="space-y-6">
      <h2 className="text-2xl font-bold">Infrastructure</h2>

      {data.scalingTriggerAlerts.length > 0 && (
        <Card className="border-destructive bg-destructive/10">
          <CardContent className="pt-6">
            <div className="flex gap-2">
              <AlertTriangle className="h-5 w-5 text-destructive flex-shrink-0" />
              <ul className="list-disc list-inside text-sm text-destructive">
                {data.scalingTriggerAlerts.map((msg, i) => (
                  <li key={i}>{msg}</li>
                ))}
              </ul>
            </div>
          </CardContent>
        </Card>
      )}

      <div className="grid grid-cols-1 md:grid-cols-3 gap-4">
        <Card>
          <CardHeader className="flex flex-row items-center justify-between pb-2">
            <CardTitle className="text-sm font-medium text-muted-foreground">Storage Used</CardTitle>
            <Server className="h-4 w-4 text-muted-foreground" />
          </CardHeader>
          <CardContent>
            <div className="text-2xl font-bold">{data.storageUsedPercent.toFixed(1)}%</div>
            <p className="text-xs text-muted-foreground mt-1">
              {(data.totalDiskBytes / 1024 / 1024 / 1024).toFixed(2)} GB
            </p>
          </CardContent>
        </Card>
        <Card>
          <CardHeader className="flex flex-row items-center justify-between pb-2">
            <CardTitle className="text-sm font-medium text-muted-foreground">Total Rows</CardTitle>
          </CardHeader>
          <CardContent>
            <div className="text-2xl font-bold">{data.totalRows.toLocaleString()}</div>
          </CardContent>
        </Card>
        <Card>
          <CardHeader className="flex flex-row items-center justify-between pb-2">
            <CardTitle className="text-sm font-medium text-muted-foreground">Tables</CardTitle>
          </CardHeader>
          <CardContent>
            <div className="text-2xl font-bold">{data.clickhouseTables.length}</div>
          </CardContent>
        </Card>
      </div>

      <Card>
        <CardHeader>
          <CardTitle>ClickHouse Table Sizes</CardTitle>
        </CardHeader>
        <CardContent>
          {data.clickhouseTables.length > 0 ? (
            <div className="overflow-x-auto">
              <table className="w-full text-sm">
                <thead>
                  <tr className="border-b">
                    <th className="text-left py-2 font-medium">Table</th>
                    <th className="text-right py-2 font-medium">Rows</th>
                    <th className="text-right py-2 font-medium">Size on Disk</th>
                  </tr>
                </thead>
                <tbody>
                  {data.clickhouseTables.map((t) => (
                    <tr key={t.table} className="border-b last:border-0">
                      <td className="py-2 font-mono">{t.table}</td>
                      <td className="py-2 text-right">{t.rows.toLocaleString()}</td>
                      <td className="py-2 text-right">{t.bytesOnDiskFormatted}</td>
                    </tr>
                  ))}
                </tbody>
              </table>
            </div>
          ) : (
            <p className="text-muted-foreground text-center py-8">No table data</p>
          )}
        </CardContent>
      </Card>
    </div>
  )
}
