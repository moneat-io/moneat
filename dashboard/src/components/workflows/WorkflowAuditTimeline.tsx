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

import {History} from 'lucide-react'
import type {WorkflowAuditEntry} from '@/lib/api'
import {Badge} from '@/components/ui/badge'
import {formatDateTime} from './insightsFormat'

interface WorkflowAuditTimelineProps {
  entries: WorkflowAuditEntry[]
}

function describeActor(entry: WorkflowAuditEntry): string {
  if (entry.actor_user_id === null || entry.actor_user_id === undefined) {
    return 'System'
  }
  return 'User #' + entry.actor_user_id
}

export function WorkflowAuditTimeline({entries}: WorkflowAuditTimelineProps) {
  if (entries.length === 0) {
    return (
      <div className="rounded-md border border-dashed bg-background p-6 text-sm text-muted-foreground">
        No audit activity recorded.
      </div>
    )
  }
  return (
    <ol className="space-y-2">
      {entries.map((entry) => (
        <li key={entry.id} className="rounded-md border bg-background p-3">
          <div className="flex items-start justify-between gap-2">
            <div className="flex min-w-0 items-center gap-2">
              <History className="h-4 w-4 shrink-0 text-muted-foreground" />
              <Badge variant="outline" className="font-mono text-[11px]">
                {entry.action}
              </Badge>
            </div>
            <span className="shrink-0 text-xs text-muted-foreground">
              {formatDateTime(entry.created_at)}
            </span>
          </div>
          <p className="mt-1.5 text-xs text-muted-foreground">{describeActor(entry)}</p>
          <AuditDetail detail={entry.detail} />
        </li>
      ))}
    </ol>
  )
}

function AuditDetail({detail}: {detail: Record<string, string>}) {
  const rows = Object.entries(detail)
  if (rows.length === 0) return null
  return (
    <dl className="mt-2 grid gap-1 text-[11px] sm:grid-cols-2">
      {rows.map(([key, value]) => (
        <div key={key} className="flex gap-1.5">
          <dt className="font-medium text-muted-foreground">{key}</dt>
          <dd className="min-w-0 truncate">{value}</dd>
        </div>
      ))}
    </dl>
  )
}
