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
import {ShieldAlert} from 'lucide-react'
import {api} from '@/lib/api'
import {Badge, type BadgeProps} from '@/components/ui/badge'
import {SectionCard} from '@/components/ui/section-card'
import {EmptyState} from '@/components/ui/empty-state'
import {SecurityError} from '@/components/security/SecurityError'

export const Route = createFileRoute('/security/events')({
  component: SecurityEvents,
})

// Map a security-event severity to the shared status language so chips read the
// same as the rest of the app instead of carrying a private color palette.
function severityBadgeVariant(severity?: string): BadgeProps['variant'] {
  switch ((severity ?? '').toLowerCase()) {
    case 'critical':
      return 'dangerSolid'
    case 'high':
      return 'danger'
    case 'medium':
      return 'warning'
    case 'low':
      return 'info'
    default:
      return 'neutral'
  }
}

interface SecurityEvent {
  eventId?: string
  severity?: string
  ruleName?: string
  eventType?: string
  processName?: string
  host?: string
  timestamp?: string
}

function SecurityEvents() {
  const {data, isLoading, isError, error} = useQuery({
    queryKey: ['security-events'],
    queryFn: () => api.get<{events?: SecurityEvent[]; totalCount?: number}>('/v1/security/events?limit=50'),
  })

  const events: SecurityEvent[] = data?.events ?? []

  if (isLoading) return <div className="flex justify-center py-8"><div className="animate-spin rounded-full h-6 w-6 border-2 border-muted border-t-primary" /></div>
  if (isError) return <SecurityError title="Couldn’t load security events" error={error} />

  return (
    <SectionCard
      title="Security events"
      icon={ShieldAlert}
      iconTone="danger"
      count={data?.totalCount || 0}
      flushBody
    >
      {events.length === 0 ? (
        <div className="p-4">
          <EmptyState
            icon={ShieldAlert}
            title="No security events"
            description="Runtime security events from your agents will appear here as they are detected."
          />
        </div>
      ) : (
        <div className="overflow-x-auto">
          <table className="w-full text-sm">
            <thead>
              <tr className="border-b bg-muted/40 text-left text-[11px] uppercase tracking-wider text-muted-foreground">
                <th className="px-4 py-2 font-medium">Severity</th>
                <th className="px-4 py-2 font-medium">Rule</th>
                <th className="px-4 py-2 font-medium">Type</th>
                <th className="px-4 py-2 font-medium">Process</th>
                <th className="px-4 py-2 font-medium">Host</th>
                <th className="px-4 py-2 font-medium">Time</th>
              </tr>
            </thead>
            <tbody className="divide-y divide-border/40">
              {events.map((e) => (
                <tr key={e.eventId} className="transition-colors hover:bg-accent/40">
                  <td className="px-4 py-2">
                    <Badge variant={severityBadgeVariant(e.severity)} className="text-[10px]">
                      {e.severity}
                    </Badge>
                  </td>
                  <td className="px-4 py-2">{e.ruleName}</td>
                  <td className="px-4 py-2 text-muted-foreground">{e.eventType}</td>
                  <td className="px-4 py-2 font-mono text-xs">{e.processName}</td>
                  <td className="px-4 py-2 font-mono text-xs">{e.host}</td>
                  <td className="px-4 py-2 tabular-nums text-muted-foreground">{e.timestamp}</td>
                </tr>
              ))}
            </tbody>
          </table>
        </div>
      )}
    </SectionCard>
  )
}
