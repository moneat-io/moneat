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

import {createFileRoute, Link, Outlet, useRouterState} from '@tanstack/react-router'
import {useQuery} from '@tanstack/react-query'
import {useState} from 'react'
import {Bell, Boxes, ChevronRight, Layers, ShieldAlert, Ticket} from 'lucide-react'
import {api} from '@/lib/api'
import {Badge} from '@/components/ui/badge'
import {Button} from '@/components/ui/button'
import {Card, CardContent} from '@/components/ui/card'
import {EmptyState} from '@/components/ui/empty-state'
import {PageHeader} from '@/components/ui/page-header'
import {cn} from '@/lib/utils'
import {
  nativeIncidentUnavailableCopy,
  useNativeIncidentCapabilities,
} from '@/hooks/useNativeIncidentCapabilities'
import type {AlertGroup} from '@/lib/api/types'
import {shortResourceId, timeAgo} from '@/components/on-call/incident-modeling'
import {
  activeMembers,
  behaviorLabel,
  groupIncidentLinkage,
  groupStateMeta,
  pagingStateMeta,
  relativeWindow,
} from '@/components/on-call/alert-groups/alertGroupFormat'
import {ALERT_GROUPS_QUERY_KEY} from '@/components/on-call/alert-groups/alertGroupQueries'

export const Route = createFileRoute('/on-call/alert-groups')({
  component: AlertGroupsPage,
})

type StateFilter = 'open' | 'all'
const ALERT_GROUP_PAGE_SIZE = 200

async function loadAllAlertGroups(): Promise<AlertGroup[]> {
  const groups: AlertGroup[] = []
  let offset = 0
  let page: AlertGroup[]
  do {
    page = await api.getAlertGroups({limit: ALERT_GROUP_PAGE_SIZE, offset})
    groups.push(...page)
    offset += page.length
  } while (page.length === ALERT_GROUP_PAGE_SIZE)
  return groups
}

function AlertGroupsPage() {
  const pathname = useRouterState({select: (state) => state.location.pathname})
  const capabilities = useNativeIncidentCapabilities()
  const [stateFilter, setStateFilter] = useState<StateFilter>('open')

  const isDetailRoute = pathname.startsWith('/on-call/alert-groups/')

  const {data, isError, isLoading, refetch} = useQuery({
    queryKey: ALERT_GROUPS_QUERY_KEY,
    queryFn: loadAllAlertGroups,
    enabled: capabilities.enabled && !isDetailRoute,
    refetchInterval: 30_000,
  })

  if (isDetailRoute) {
    return <Outlet />
  }

  if (capabilities.isLoading) {
    return (
      <div className="flex items-center justify-center py-16">
        <div className="h-8 w-8 animate-spin rounded-full border-2 border-muted border-t-primary" />
      </div>
    )
  }

  if (!capabilities.enabled) {
    const copy = nativeIncidentUnavailableCopy(capabilities)
    return (
      <div className="space-y-4">
        <PageHeader icon={Boxes} title="Alert groups" description="Related alerts folded together for response" />
        <EmptyState icon={ShieldAlert} title={copy.title} description={copy.description} />
      </div>
    )
  }

  const groups = data ?? []
  const openGroups = groups.filter((group) => group.state === 'OPEN')
  const visibleGroups = stateFilter === 'open' ? openGroups : groups

  return (
    <div className="space-y-4">
      <PageHeader icon={Boxes} title="Alert groups" description="Related alerts folded together for response" />

      {groups.length > 0 && (
        <div className="flex gap-1.5">
          <FilterChip active={stateFilter === 'open'} onClick={() => setStateFilter('open')}>
            {openGroups.length} Open
          </FilterChip>
          <FilterChip active={stateFilter === 'all'} onClick={() => setStateFilter('all')}>
            {groups.length} All
          </FilterChip>
        </div>
      )}

      {isLoading && (
        <div className="flex items-center justify-center py-10">
          <div className="h-8 w-8 animate-spin rounded-full border-2 border-muted border-t-primary" />
        </div>
      )}

      {isError && (
        <EmptyState
          icon={ShieldAlert}
          title="Unable to load alert groups"
          description="Alert groups could not be loaded. Check your connection and try again."
          action={<Button size="sm" onClick={() => refetch()}>Try again</Button>}
        />
      )}

      {!isLoading && !isError && visibleGroups.length === 0 && (
        <EmptyState
          icon={Boxes}
          title={stateFilter === 'open' ? 'No open alert groups' : 'No alert groups yet'}
          description="When routes group related alerts, those groups show up here for triage."
        />
      )}

      {visibleGroups.length > 0 && (
        <div className="space-y-2">
          {visibleGroups.map((group) => (
            <AlertGroupRow key={group.id} group={group} />
          ))}
        </div>
      )}
    </div>
  )
}

function FilterChip({
  active,
  onClick,
  children,
}: Readonly<{active: boolean; onClick: () => void; children: React.ReactNode}>) {
  return (
    <button
      onClick={onClick}
      className={cn(
        'rounded-lg border px-2.5 py-1.5 text-xs font-medium transition-colors',
        active ? 'border-primary bg-accent/50 text-foreground' : 'hover:bg-muted/60'
      )}
    >
      {children}
    </button>
  )
}

function AlertGroupRow({group}: Readonly<{group: AlertGroup}>) {
  const stateMeta = groupStateMeta(group.state)
  const pagingMeta = pagingStateMeta(group.pagingState)
  const linkage = groupIncidentLinkage(group)
  const active = activeMembers(group).length

  return (
    <Link
      to="/on-call/alert-groups/$groupId"
      params={{groupId: group.id}}
      className="block group"
    >
      <Card className="transition-colors hover:border-muted-foreground/30">
        <CardContent className="flex items-center justify-between gap-4 p-3">
          <div className="min-w-0">
            <div className="flex flex-wrap items-center gap-2">
              <Badge variant={stateMeta.variant} size="sm">
                {stateMeta.label}
              </Badge>
              <span className="truncate text-sm font-semibold">{routeLabel(group)}</span>
              <span className="font-mono text-[11px] text-muted-foreground">{shortResourceId(group.id)}</span>
            </div>
            <div className="mt-1 flex flex-wrap items-center gap-x-3 gap-y-1 text-[11px] text-muted-foreground">
              <span className="inline-flex items-center gap-1">
                <Layers className="h-3 w-3" />
                {active} active · {behaviorLabel(group.behavior)}
              </span>
              <span className="inline-flex items-center gap-1">
                <Bell className="h-3 w-3" />
                {pagingMeta.label}
              </span>
              <span>Opened {timeAgo(group.openedAt)}</span>
              <span>{relativeWindow(group.closesAt)}</span>
              {linkage.kind !== 'none' && (
                <span className="inline-flex items-center gap-1 text-info-fg">
                  <Ticket className="h-3 w-3" />
                  {linkage.kind === 'incident' ? 'Linked incident' : 'Candidate incident'}
                </span>
              )}
            </div>
          </div>
          <ChevronRight className="h-4 w-4 shrink-0 text-muted-foreground opacity-0 transition-opacity group-hover:opacity-100" />
        </CardContent>
      </Card>
    </Link>
  )
}

function routeLabel(group: AlertGroup): string {
  const snapshot = group.routeSnapshot
  const name = typeof snapshot.name === 'string' ? snapshot.name : undefined
  const key = typeof snapshot.key === 'string' ? snapshot.key : undefined
  return name ?? key ?? `Route ${shortResourceId(group.routeId)}`
}
