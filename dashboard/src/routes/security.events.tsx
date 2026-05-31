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
import {Badge} from '@/components/ui/badge'
import {Card, CardContent, CardHeader, CardTitle} from '@/components/ui/card'
import {cn} from '@/lib/utils'
import {colorFor, severityColors} from '@/components/security/securityChips'
import {SecurityError} from '@/components/security/SecurityError'

export const Route = createFileRoute('/security/events')({
  component: SecurityEvents,
})

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
    <Card>
      <CardHeader className="py-1.5 px-2.5"><CardTitle className="text-xs">Security Events ({data?.totalCount || 0})</CardTitle></CardHeader>
      <CardContent className="p-2.5 pt-0">
        <div className="overflow-x-auto">
          <table className="w-full text-xs">
            <thead>
              <tr className="border-b text-left text-muted-foreground">
                <th className="pb-1.5 pr-2 font-medium">Severity</th>
                <th className="pb-1.5 pr-2 font-medium">Rule</th>
                <th className="pb-1.5 pr-2 font-medium">Type</th>
                <th className="pb-1.5 pr-2 font-medium">Process</th>
                <th className="pb-1.5 pr-2 font-medium">Host</th>
                <th className="pb-1.5 font-medium">Time</th>
              </tr>
            </thead>
            <tbody>
              {events.map((e) => (
                <tr key={e.eventId} className="border-b last:border-0 hover:bg-muted/30">
                  <td className="py-1.5 pr-2">
                    <Badge variant="outline" className={cn('text-[10px]', colorFor(severityColors, e.severity))}>
                      {e.severity}
                    </Badge>
                  </td>
                  <td className="py-1.5 pr-2">{e.ruleName}</td>
                  <td className="py-1.5 pr-2">{e.eventType}</td>
                  <td className="py-1.5 pr-2 font-mono">{e.processName}</td>
                  <td className="py-1.5 pr-2">{e.host}</td>
                  <td className="py-1.5 text-muted-foreground">{e.timestamp}</td>
                </tr>
              ))}
              {events.length === 0 && (
                <tr><td colSpan={6} className="py-6 text-center text-muted-foreground">No security events</td></tr>
              )}
            </tbody>
          </table>
        </div>
      </CardContent>
    </Card>
  )
}
