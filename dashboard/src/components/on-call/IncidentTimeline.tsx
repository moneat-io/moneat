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

import { CheckCircle2, Circle, AlertTriangle, Clock, MessageSquare, UserPlus, Bell, Eye, Send } from 'lucide-react'
import { cn } from '@/lib/utils'

export interface TimelineEvent {
  id: number
  eventType: string
  actorUserId?: number
  actorUserName?: string
  details?: Record<string, string | number | undefined>
  createdAt: string
}

interface IncidentTimelineProps {
  events: TimelineEvent[]
}

const EVENT_CONFIG: Record<string, { icon: typeof Circle; label: string; color: string }> = {
  TRIGGERED: {
    icon: AlertTriangle,
    label: 'Incident Triggered',
    color: 'text-danger-fg',
  },
  ESCALATED: {
    icon: Bell,
    label: 'Escalated',
    color: 'text-warning-fg',
  },
  ACKNOWLEDGED: {
    icon: CheckCircle2,
    label: 'Acknowledged',
    color: 'text-info-fg',
  },
  RESOLVED: {
    icon: CheckCircle2,
    label: 'Resolved',
    color: 'text-success-fg',
  },
  REASSIGNED: {
    icon: UserPlus,
    label: 'Reassigned',
    color: 'text-chart-5',
  },
  NOTE_ADDED: {
    icon: MessageSquare,
    label: 'Note Added',
    color: 'text-muted-foreground',
  },
  STEP_TIMEOUT: {
    icon: Clock,
    label: 'Step Timeout',
    color: 'text-warning-fg',
  },
  NOTIFICATION_SENT: {
    icon: Send,
    label: 'Notification Sent',
    color: 'text-chart-6',
  },
  VIEWED: {
    icon: Eye,
    label: 'Viewed',
    color: 'text-muted-foreground',
  },
}

export function IncidentTimeline({ events }: IncidentTimelineProps) {
  return (
    <div className="flow-root">
      <ul className="-mb-8">
        {events.map((event, eventIdx) => {
          const config = EVENT_CONFIG[event.eventType] || {
            icon: Circle,
            label: event.eventType,
            color: 'text-gray-500',
          }
          const Icon = config.icon

          return (
            <li key={event.id}>
              <div className="relative pb-8">
                {eventIdx !== events.length - 1 && (
                  <span
                    className="absolute left-4 top-4 -ml-px h-full w-0.5 bg-border"
                    aria-hidden="true"
                  />
                )}
                <div className="relative flex space-x-3">
                  <div>
                    <span
                      className={cn(
                        'h-8 w-8 rounded-full flex items-center justify-center ring-8 ring-background',
                        config.color.replace('text-', 'bg-').replace('-500', '-100').replace('-400', '-100')
                      )}
                    >
                      <Icon className={cn('h-4 w-4', config.color)} aria-hidden="true" />
                    </span>
                  </div>
                  <div className="flex min-w-0 flex-1 justify-between space-x-4 pt-1.5">
                    <div>
                      <p className="text-sm font-medium">{config.label}</p>
                      {event.actorUserName && event.eventType !== 'NOTIFICATION_SENT' && (
                        <p className="mt-0.5 text-sm text-muted-foreground">
                          by {event.actorUserName}
                        </p>
                      )}
                      {event.details && Object.keys(event.details).length > 0 && (
                        <div className="mt-2 text-sm text-muted-foreground">
                          {event.eventType === 'NOTE_ADDED' && event.details.note && (
                            <p className="italic">&quot;{event.details.note}&quot;</p>
                          )}
                          {event.eventType === 'NOTIFICATION_SENT' && (
                            <p>
                              to {event.details.toUserName || event.actorUserName || 'on-call user'}
                              {event.details.channel && ` via ${event.details.channel}`}
                            </p>
                          )}
                          {event.eventType === 'ESCALATED' && event.details.stepNumber !== undefined && (
                            <p>to step {Number(event.details.stepNumber) + 1}</p>
                          )}
                          {event.eventType === 'REASSIGNED' && event.details.toUserName && (
                            <p>to {event.details.toUserName}</p>
                          )}
                        </div>
                      )}
                    </div>
                    <div className="whitespace-nowrap text-right text-sm text-muted-foreground">
                      <time dateTime={event.createdAt}>
                        {new Date(event.createdAt).toLocaleString()}
                      </time>
                    </div>
                  </div>
                </div>
              </div>
            </li>
          )
        })}
      </ul>
    </div>
  )
}
