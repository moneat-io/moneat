// Moneat Enterprise - proprietary module
// Copyright (c) 2026 Moneat. All rights reserved.
// See enterprise/LICENSE for license terms.

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
    color: 'text-red-500',
  },
  ESCALATED: {
    icon: Bell,
    label: 'Escalated',
    color: 'text-orange-500',
  },
  ACKNOWLEDGED: {
    icon: CheckCircle2,
    label: 'Acknowledged',
    color: 'text-blue-500',
  },
  RESOLVED: {
    icon: CheckCircle2,
    label: 'Resolved',
    color: 'text-green-500',
  },
  REASSIGNED: {
    icon: UserPlus,
    label: 'Reassigned',
    color: 'text-purple-500',
  },
  NOTE_ADDED: {
    icon: MessageSquare,
    label: 'Note Added',
    color: 'text-gray-500',
  },
  STEP_TIMEOUT: {
    icon: Clock,
    label: 'Step Timeout',
    color: 'text-orange-500',
  },
  NOTIFICATION_SENT: {
    icon: Send,
    label: 'Notification Sent',
    color: 'text-blue-400',
  },
  VIEWED: {
    icon: Eye,
    label: 'Viewed',
    color: 'text-gray-400',
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
