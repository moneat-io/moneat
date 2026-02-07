import { createFileRoute, Link } from '@tanstack/react-router'
import { useQuery } from '@tanstack/react-query'
import { api } from '@/lib/api'
import { Card, CardContent, CardHeader, CardTitle } from '@/components/ui/card'

export const Route = createFileRoute('/admin/organizations')({
  component: AdminOrganizationsPage,
})

function AdminOrganizationsPage() {
  const { data: orgs, isLoading } = useQuery({
    queryKey: ['admin-organizations', 1],
    queryFn: () => api.getAdminOrganizations(1, 50),
  })

  if (isLoading || !orgs) {
    return <div className="p-8 text-center">Loading organizations...</div>
  }

  return (
    <div className="space-y-6">
      <h2 className="text-2xl font-bold">Organizations</h2>
      <Card>
        <CardHeader>
          <CardTitle>All Organizations</CardTitle>
        </CardHeader>
        <CardContent>
          {orgs.length > 0 ? (
            <div className="overflow-x-auto">
              <table className="w-full text-sm">
                <thead>
                  <tr className="border-b">
                    <th className="text-left py-2 font-medium">Name</th>
                    <th className="text-left py-2 font-medium">Plan</th>
                    <th className="text-right py-2 font-medium">Events (mo)</th>
                    <th className="text-right py-2 font-medium">Quota %</th>
                    <th className="text-right py-2 font-medium">Members</th>
                    <th className="text-right py-2 font-medium">Projects</th>
                  </tr>
                </thead>
                <tbody>
                  {orgs.map((org) => (
                    <tr key={org.id} className="border-b last:border-0">
                      <td className="py-2">
                        <Link
                          to="/admin/organizations/$orgId"
                          params={{ orgId: String(org.id) }}
                          className="text-primary hover:underline font-medium"
                        >
                          {org.name}
                        </Link>
                      </td>
                      <td className="py-2 capitalize">{org.plan}</td>
                      <td className="py-2 text-right">{org.eventCountThisMonth.toLocaleString()}</td>
                      <td className="py-2 text-right">
                        {org.quotaUsedPercent != null
                          ? `${org.quotaUsedPercent.toFixed(1)}%`
                          : '-'}
                      </td>
                      <td className="py-2 text-right">{org.memberCount}</td>
                      <td className="py-2 text-right">{org.projectCount}</td>
                    </tr>
                  ))}
                </tbody>
              </table>
            </div>
          ) : (
            <p className="text-muted-foreground text-center py-8">No organizations yet</p>
          )}
        </CardContent>
      </Card>
    </div>
  )
}
