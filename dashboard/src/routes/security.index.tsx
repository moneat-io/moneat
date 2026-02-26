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

export const Route = createFileRoute('/security/')({
  component: SecurityEvents,
})

const severityColors: Record<string, string> = {
  critical: 'bg-red-500/15 text-red-500 border-red-500/30',
  high: 'bg-orange-500/15 text-orange-500 border-orange-500/30',
  medium: 'bg-amber-500/15 text-amber-500 border-amber-500/30',
  low: 'bg-blue-500/15 text-blue-500 border-blue-500/30',
  info: 'bg-slate-500/15 text-slate-400 border-slate-500/30',
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
  const {data, isLoading} = useQuery({
    queryKey: ['security-events'],
    queryFn: () => api.get('/v1/security/events?limit=50'),
  })

  const events: SecurityEvent[] = (data?.events as SecurityEvent[] | undefined) ?? []

  if (isLoading) return <div className="flex justify-center py-12"><div className="animate-spin rounded-full h-8 w-8 border-2 border-muted border-t-primary" /></div>

  return (
    <Card>
      <CardHeader><CardTitle>Security Events ({data?.totalCount || 0})</CardTitle></CardHeader>
      <CardContent>
        <div className="overflow-x-auto">
          <table className="w-full text-sm">
            <thead>
              <tr className="border-b text-left text-muted-foreground">
                <th className="pb-2 pr-4 font-medium">Severity</th>
                <th className="pb-2 pr-4 font-medium">Rule</th>
                <th className="pb-2 pr-4 font-medium">Type</th>
                <th className="pb-2 pr-4 font-medium">Process</th>
                <th className="pb-2 pr-4 font-medium">Host</th>
                <th className="pb-2 font-medium">Time</th>
              </tr>
            </thead>
            <tbody>
              {events.map((e) => (
                <tr key={e.eventId} className="border-b last:border-0 hover:bg-muted/30">
                  <td className="py-2 pr-4">
                    <Badge variant="outline" className={cn('text-xs', severityColors[e.severity ?? ''] || '')}>
                      {e.severity}
                    </Badge>
                  </td>
                  <td className="py-2 pr-4">{e.ruleName}</td>
                  <td className="py-2 pr-4">{e.eventType}</td>
                  <td className="py-2 pr-4 font-mono text-xs">{e.processName}</td>
                  <td className="py-2 pr-4">{e.host}</td>
                  <td className="py-2 text-muted-foreground text-xs">{e.timestamp}</td>
                </tr>
              ))}
              {events.length === 0 && (
                <tr><td colSpan={6} className="py-8 text-center text-muted-foreground">No security events</td></tr>
              )}
            </tbody>
          </table>
        </div>
      </CardContent>
    </Card>
  )
}
