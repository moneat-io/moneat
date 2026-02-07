import { createFileRoute, Link } from '@tanstack/react-router'
import { useQuery } from '@tanstack/react-query'
import { api } from '@/lib/api'
import { Card, CardContent } from '@/components/ui/card'
import {
  Table,
  TableBody,
  TableCell,
  TableHead,
  TableHeader,
  TableRow,
} from '@/components/ui/table'
import { Building2 } from 'lucide-react'
import {
  PlanBadge,
  QuotaBar,
  SectionHeader,
  AdminSkeleton,
  EmptyState,
  formatNumber,
} from '@/components/admin-components'

export const Route = createFileRoute('/admin/organizations')({
  component: AdminOrganizationsPage,
})

function AdminOrganizationsPage() {
  const { data: orgs, isLoading } = useQuery({
    queryKey: ['admin-organizations', 1],
    queryFn: () => api.getAdminOrganizations(1, 50),
  })

  if (isLoading || !orgs) {
    return <AdminSkeleton />
  }

  return (
    <div className="space-y-6">
      <SectionHeader
        title="Organizations"
        description={`${orgs.length} organization${orgs.length !== 1 ? 's' : ''} registered on the platform.`}
      />

      <Card>
        <CardContent className="p-0">
          {orgs.length > 0 ? (
            <Table>
              <TableHeader>
                <TableRow className="hover:bg-transparent">
                  <TableHead className="w-[240px]">Organization</TableHead>
                  <TableHead>Plan</TableHead>
                  <TableHead className="text-right">Events (mo)</TableHead>
                  <TableHead className="w-[200px]">Quota Usage</TableHead>
                  <TableHead className="text-right">Members</TableHead>
                  <TableHead className="text-right">Projects</TableHead>
                </TableRow>
              </TableHeader>
              <TableBody>
                {orgs.map((org) => (
                  <TableRow key={org.id}>
                    <TableCell>
                      <Link
                        to="/admin/organizations/$orgId"
                        params={{ orgId: String(org.id) }}
                        className="font-medium hover:underline text-foreground"
                      >
                        {org.name}
                      </Link>
                    </TableCell>
                    <TableCell>
                      <PlanBadge plan={org.plan} />
                    </TableCell>
                    <TableCell className="text-right tabular-nums">
                      {formatNumber(org.eventCountThisMonth)}
                    </TableCell>
                    <TableCell>
                      <QuotaBar percent={org.quotaUsedPercent} />
                    </TableCell>
                    <TableCell className="text-right tabular-nums">{org.memberCount}</TableCell>
                    <TableCell className="text-right tabular-nums">{org.projectCount}</TableCell>
                  </TableRow>
                ))}
              </TableBody>
            </Table>
          ) : (
            <EmptyState message="No organizations yet" icon={Building2} />
          )}
        </CardContent>
      </Card>
    </div>
  )
}
