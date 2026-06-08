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

import {createFileRoute, Link, Outlet, useMatches} from '@tanstack/react-router'
import {useQuery} from '@tanstack/react-query'
import {api} from '@/lib/api'
import {Card, CardContent} from '@/components/ui/card'
import {Table, TableBody, TableCell, TableHead, TableHeader, TableRow} from '@/components/ui/table'
import {Input} from '@/components/ui/input'
import {Building2, Search} from 'lucide-react'
import {useMemo, useState} from 'react'
import {
    AdminSkeleton,
    EmptyState,
    formatBytes,
    formatNumber,
    PlanBadge,
    QuotaBar,
    SectionHeader,
} from '@/components/AdminComponents'

export const Route = createFileRoute('/admin/organizations')({
  component: AdminOrganizationsLayout,
})

function AdminOrganizationsLayout() {
  const matches = useMatches()
  const showingChildRoute = matches.some((match) => match.id.includes('/admin/organizations/$orgId'))

  if (showingChildRoute) {
    return <Outlet />
  }

  return <AdminOrganizationsPage />
}

type OrgSortBy = 'events' | 'bytes' | 'quota' | 'name'

function SortIndicator({column, sortBy, sortDir}: {column: OrgSortBy, sortBy: OrgSortBy, sortDir: 'asc' | 'desc'}) {
  if (sortBy !== column) return null
  return <span className="ml-1 text-xs">{sortDir === 'desc' ? '↓' : '↑'}</span>
}

function AdminOrganizationsPage() {
  const [search, setSearch] = useState('')
  const [sortBy, setSortBy] = useState<OrgSortBy>('events')
  const [sortDir, setSortDir] = useState<'asc' | 'desc'>('desc')

  const {data: orgs, isLoading} = useQuery({
    queryKey: ['admin-organizations', 1],
    queryFn: () => api.getAdminOrganizations(1, 100),
  })

  const filteredOrgs = useMemo(() => {
    if (!orgs) return []
    let result = orgs
    if (search) {
      const lower = search.toLowerCase()
      result = result.filter(
        (o) =>
          o.name.toLowerCase().includes(lower) ||
          o.slug.toLowerCase().includes(lower) ||
          o.plan.toLowerCase().includes(lower)
      )
    }
    result = [...result].sort((a, b) => {
      let cmp = 0
      switch (sortBy) {
        case 'events':
          cmp = a.eventCountThisMonth - b.eventCountThisMonth
          break
        case 'bytes':
          cmp = a.bytesIngestedThisMonth - b.bytesIngestedThisMonth
          break
        case 'quota':
          cmp = (a.quotaUsedPercent ?? -1) - (b.quotaUsedPercent ?? -1)
          break
        case 'name':
          cmp = a.name.localeCompare(b.name)
          break
      }
      return sortDir === 'desc' ? -cmp : cmp
    })
    return result
  }, [orgs, search, sortBy, sortDir])

  const handleSort = (column: typeof sortBy) => {
    if (sortBy === column) {
      setSortDir((d) => (d === 'asc' ? 'desc' : 'asc'))
    } else {
      setSortBy(column)
      setSortDir('desc')
    }
  }

  if (isLoading || !orgs) {
    return <AdminSkeleton />
  }

  // Aggregate stats
  const totalEvents = orgs.reduce((sum, o) => sum + o.eventCountThisMonth, 0)
  const totalBytes = orgs.reduce((sum, o) => sum + o.bytesIngestedThisMonth, 0)
  const orgsOverQuota = orgs.filter((o) => o.quotaUsedPercent != null && o.quotaUsedPercent >= 90).length

  return (
    <div className="space-y-6">
      <SectionHeader
        title="Organizations"
        description={`${orgs.length} organization${orgs.length !== 1 ? 's' : ''} registered on the platform.`}
      >
        <div className="relative">
          <Search className="absolute left-3 top-1/2 -translate-y-1/2 h-4 w-4 text-muted-foreground" />
          <Input
            placeholder="Search orgs..."
            className="pl-9 w-64"
            value={search}
            onChange={(e) => setSearch(e.target.value)}
          />
        </div>
      </SectionHeader>

      {/* Summary Stats */}
      <div className="grid grid-cols-2 lg:grid-cols-4 gap-4">
        <Card className="px-4 py-3">
          <div className="text-xs text-muted-foreground">Total Organizations</div>
          <div className="text-xl font-bold tabular-nums">{orgs.length}</div>
        </Card>
        <Card className="px-4 py-3">
          <div className="text-xs text-muted-foreground">Total Events (Month)</div>
          <div className="text-xl font-bold tabular-nums">{formatNumber(totalEvents)}</div>
        </Card>
        <Card className="px-4 py-3">
          <div className="text-xs text-muted-foreground">Total Data Ingested</div>
          <div className="text-xl font-bold tabular-nums">{formatBytes(totalBytes)}</div>
        </Card>
        <Card className="px-4 py-3">
          <div className="text-xs text-muted-foreground">Over 90% Quota</div>
          <div className={`text-xl font-bold tabular-nums ${orgsOverQuota > 0 ? 'text-danger-fg' : ''}`}>
            {orgsOverQuota}
          </div>
        </Card>
      </div>

      <Card>
        <CardContent className="p-0">
          {filteredOrgs.length > 0 ? (
            <Table>
              <TableHeader>
                <TableRow className="hover:bg-transparent">
                  <TableHead
                    className="w-[220px] cursor-pointer select-none"
                    onClick={() => handleSort('name')}
                  >
                    Organization
                    <SortIndicator column="name" sortBy={sortBy} sortDir={sortDir} />
                  </TableHead>
                  <TableHead>Plan</TableHead>
                  <TableHead
                    className="text-right cursor-pointer select-none"
                    onClick={() => handleSort('events')}
                  >
                    Events (mo)
                    <SortIndicator column="events" sortBy={sortBy} sortDir={sortDir} />
                  </TableHead>
                  <TableHead
                    className="text-right cursor-pointer select-none"
                    onClick={() => handleSort('bytes')}
                  >
                    Data Ingested
                    <SortIndicator column="bytes" sortBy={sortBy} sortDir={sortDir} />
                  </TableHead>
                  <TableHead
                    className="w-[180px] cursor-pointer select-none"
                    onClick={() => handleSort('quota')}
                  >
                    Quota Usage
                    <SortIndicator column="quota" sortBy={sortBy} sortDir={sortDir} />
                  </TableHead>
                  <TableHead className="text-right">Members</TableHead>
                  <TableHead className="text-right">Services</TableHead>
                </TableRow>
              </TableHeader>
              <TableBody>
                {filteredOrgs.map((org) => (
                  <TableRow key={org.id}>
                    <TableCell>
                      <Link
                        to="/admin/organizations/$orgId"
                        params={{orgId: String(org.id)}}
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
                    <TableCell className="text-right tabular-nums text-muted-foreground">
                      {formatBytes(org.bytesIngestedThisMonth)}
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
            <EmptyState
              message={search ? 'No organizations match your search' : 'No organizations yet'}
              icon={Building2}
            />
          )}
        </CardContent>
      </Card>
    </div>
  )
}
