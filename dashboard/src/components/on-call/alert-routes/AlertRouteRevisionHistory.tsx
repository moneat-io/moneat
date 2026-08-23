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

import {useQuery} from '@tanstack/react-query'
import {History} from 'lucide-react'
import {api} from '@/lib/api'
import {Badge, type BadgeProps} from '@/components/ui/badge'
import type {AlertRouteChangeType} from '@/lib/api/types'
import {formatDateTime} from '@/components/on-call/incident-modeling'

interface AlertRouteRevisionHistoryProps {
  routeId: string
  /** Bumped by the editor after a successful save so the list refetches. */
  refreshToken?: number
}

const CHANGE_TYPE_META: Record<AlertRouteChangeType, {label: string; variant: BadgeProps['variant']}> = {
  CREATED: {label: 'Created', variant: 'success'},
  UPDATED: {label: 'Updated', variant: 'info'},
  REORDERED: {label: 'Reordered', variant: 'neutral'},
  DELETED: {label: 'Deleted', variant: 'danger'},
}

function changeTypeMeta(changeType: string) {
  return CHANGE_TYPE_META[changeType as AlertRouteChangeType] ?? {label: changeType, variant: 'neutral' as const}
}

// Read-only audit trail for a single route, newest first. Each entry is one
// versioned command the backend applied.
export function AlertRouteRevisionHistory({routeId, refreshToken}: Readonly<AlertRouteRevisionHistoryProps>) {
  const {data, isLoading, isError} = useQuery({
    queryKey: ['alert-route-revisions', routeId, refreshToken],
    queryFn: () => api.getAlertRouteRevisions(routeId),
  })

  if (isLoading) {
    return <p className="text-xs text-muted-foreground">Loading history…</p>
  }
  if (isError) {
    return <p className="text-xs text-muted-foreground">History is unavailable right now.</p>
  }
  const revisions = [...(data ?? [])].sort((a, b) => b.revision - a.revision)
  if (revisions.length === 0) {
    return <p className="text-xs text-muted-foreground">No history yet.</p>
  }

  return (
    <ul className="space-y-2">
      {revisions.map((revision) => {
        const meta = changeTypeMeta(revision.changeType)
        return (
          <li key={revision.id} className="flex items-start gap-2 text-xs">
            <History className="mt-0.5 h-3.5 w-3.5 shrink-0 text-muted-foreground" />
            <div className="min-w-0">
              <div className="flex items-center gap-1.5">
                <Badge variant={meta.variant} size="sm">
                  {meta.label}
                </Badge>
                <span className="text-muted-foreground">rev {revision.revision}</span>
              </div>
              <p className="mt-0.5 text-muted-foreground">{formatDateTime(revision.createdAt)}</p>
            </div>
          </li>
        )
      })}
    </ul>
  )
}
