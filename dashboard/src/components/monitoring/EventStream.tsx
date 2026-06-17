// Moneat - observability platform
// Copyright (C) 2026 Moneat
//
// This program is free software: you can redistribute it and/or modify
// it under the terms of the GNU Affero General Public License as published by
// the Free Software Foundation, either version 3 of the License, or
// (at your option) any later version.

import {useQuery} from '@tanstack/react-query'
import {api, type DdEventResponse} from '@/lib/api'
import {Table, TableBody, TableCell, TableHead, TableHeader, TableRow} from '@/components/ui/table'
import {Badge} from '@/components/ui/badge'
import {StatusDot, type StatusTone} from '@/components/ui/status-dot'
import {Card, CardContent} from '@/components/ui/card'
import {Input} from '@/components/ui/input'
import {Tooltip, TooltipContent, TooltipProvider, TooltipTrigger} from '@/components/ui/tooltip'
import {
  AlertCircle,
  AlertTriangle,
  Bell,
  CheckCircle2,
  ChevronDown,
  ChevronRight,
  Info,
  Loader2,
  Search,
  Server,
  Tag,
  Zap,
} from 'lucide-react'
import {useState, useMemo, type ReactNode} from 'react'
import {cn} from '@/lib/utils'
import {formatRelativeTime} from '@/lib/utils'

type AlertFilter = 'all' | 'error' | 'warning' | 'info' | 'success'

function alertIcon(alertType: string) {
  switch (alertType) {
    case 'error':
      return <AlertCircle className="h-3.5 w-3.5" />
    case 'warning':
      return <AlertTriangle className="h-3.5 w-3.5" />
    case 'success':
      return <CheckCircle2 className="h-3.5 w-3.5" />
    case 'info':
      return <Info className="h-3.5 w-3.5" />
    default:
      return <Bell className="h-3.5 w-3.5" />
  }
}

type AlertBadgeVariant = 'danger' | 'warning' | 'success' | 'info' | 'neutral'

function alertBadgeVariant(alertType: string): AlertBadgeVariant {
  switch (alertType) {
    case 'error':
      return 'danger'
    case 'warning':
      return 'warning'
    case 'success':
      return 'success'
    case 'info':
      return 'info'
    default:
      return 'neutral'
  }
}

function alertAccentColor(alertType: string): string {
  switch (alertType) {
    case 'error':
      return 'border-l-danger-solid'
    case 'warning':
      return 'border-l-warning-solid'
    case 'success':
      return 'border-l-success-solid'
    case 'info':
      return 'border-l-info-solid'
    default:
      return 'border-l-muted-foreground'
  }
}

function priorityLabel(priority: string): string {
  switch (priority) {
    case 'low':
      return 'Low'
    case 'normal':
      return 'Normal'
    default:
      return priority || 'Normal'
  }
}

function filterTone(alertType: AlertFilter): StatusTone {
  switch (alertType) {
    case 'error':
      return 'danger'
    case 'warning':
      return 'warning'
    case 'info':
      return 'info'
    case 'success':
      return 'success'
    default:
      return 'neutral'
  }
}

function LoadingEvents() {
  return (
    <div className="flex items-center justify-center py-16">
      <div className="flex flex-col items-center gap-3">
        <Loader2 className="h-8 w-8 animate-spin text-muted-foreground" />
        <p className="text-muted-foreground text-sm">Loading events...</p>
      </div>
    </div>
  )
}

function EmptyEvents() {
  return (
    <Card className="border-dashed">
      <CardContent className="py-16 text-center">
        <div className="mx-auto mb-6 flex h-20 w-20 items-center justify-center rounded-2xl bg-muted text-muted-foreground">
          <Zap className="h-10 w-10" />
        </div>
        <h3 className="text-xl font-semibold mb-2">No events found</h3>
        <p className="text-muted-foreground mb-2 max-w-sm mx-auto">
          Events will appear when an agent sends event data.
        </p>
      </CardContent>
    </Card>
  )
}

function EmptyFilteredEvents() {
  return (
    <div className="text-center py-12 text-muted-foreground">
      <p className="font-medium">No events match your filters</p>
      <p className="text-sm mt-1">Try adjusting your search or alert type filter.</p>
    </div>
  )
}

function eventDetailToggleIcon(hasDetails: boolean, isExpanded: boolean): ReactNode {
  if (hasDetails) {
    if (isExpanded) return <ChevronDown className="h-3.5 w-3.5 text-muted-foreground" />
    return (
      <ChevronRight className="h-3.5 w-3.5 text-muted-foreground opacity-0 group-hover:opacity-100 transition-opacity" />
    )
  }
  return null
}

function EventTable({
  events,
  expandedId,
  onToggleExpanded,
}: {
  readonly events: readonly DdEventResponse[]
  readonly expandedId: string | null
  readonly onToggleExpanded: (eventId: string | null) => void
}) {
  return (
    <Card className="overflow-hidden border-border/60">
      <CardContent className="p-0">
        <Table className="min-w-[980px] table-fixed">
          <TableHeader>
            <TableRow className="hover:bg-transparent bg-muted/30">
              <TableHead className="pl-4 w-8" />
              <TableHead className="w-[46%]">Event</TableHead>
              <TableHead className="w-[140px]">Alert</TableHead>
              <TableHead className="w-[110px]">Priority</TableHead>
              <TableHead className="w-[180px]">Host</TableHead>
              <TableHead className="w-[220px]">Source</TableHead>
              <TableHead className="w-[100px] text-right pr-4">Time</TableHead>
            </TableRow>
          </TableHeader>
          <TableBody>
            {events.map((event: DdEventResponse) => {
              const isExpanded = expandedId === event.eventId
              const tagEntries = event.tags ? Object.entries(event.tags) : []
              const hasDetails = Boolean(event.text || tagEntries.length > 0 || event.aggregationKey || event.deviceName)

              return (
                <TableRow
                  key={event.eventId}
                  className={cn(
                    'group transition-colors border-l-2',
                    alertAccentColor(event.alertType),
                    hasDetails ? 'cursor-pointer' : '',
                    isExpanded ? 'bg-muted/40' : 'hover:bg-muted/50'
                  )}
                  onClick={() => hasDetails && onToggleExpanded(isExpanded ? null : event.eventId)}
                >
                  <TableCell className="pl-4 pr-0 w-8">
                    {eventDetailToggleIcon(hasDetails, isExpanded)}
                  </TableCell>

                  <TableCell className="max-w-0">
                    <div className="min-w-0">
                      <TooltipProvider delayDuration={300}>
                        <Tooltip>
                          <TooltipTrigger asChild>
                            <p className="truncate text-sm font-medium">{event.title}</p>
                          </TooltipTrigger>
                          <TooltipContent side="top" className="max-w-md">
                            <p className="text-xs break-all">{event.title}</p>
                          </TooltipContent>
                        </Tooltip>
                      </TooltipProvider>
                      {!isExpanded && event.text && (
                        <p className="mt-0.5 truncate text-[11px] text-muted-foreground">
                          {event.text}
                        </p>
                      )}
                      {isExpanded && (
                        <div className="mt-2 space-y-2">
                          {event.text && (
                            <p className="text-xs text-muted-foreground whitespace-pre-wrap break-words max-w-lg">
                              {event.text}
                            </p>
                          )}
                          {(event.aggregationKey || event.deviceName) && (
                            <div className="space-y-1 text-[11px] text-muted-foreground">
                              {event.aggregationKey && (
                                <span className="block min-w-0">
                                  <span className="mr-1 font-medium text-foreground/70">agg:</span>
                                  <span className="break-all font-mono">{event.aggregationKey}</span>
                                </span>
                              )}
                              {event.deviceName && (
                                <span className="block min-w-0">
                                  <span className="mr-1 font-medium text-foreground/70">device:</span>
                                  <span className="break-all font-mono">{event.deviceName}</span>
                                </span>
                              )}
                            </div>
                          )}
                          {tagEntries.length > 0 && (
                            <div className="flex flex-wrap gap-1.5 pt-0.5">
                              {tagEntries.map(([k, v]) => (
                                <span
                                  key={k}
                                  className="inline-flex items-center rounded-md bg-muted/80 px-2 py-0.5 text-[11px] font-mono text-muted-foreground border border-border/40"
                                >
                                  <span className="text-foreground/60">{k}:</span>
                                  <span className="ml-0.5">{v}</span>
                                </span>
                              ))}
                            </div>
                          )}
                        </div>
                      )}
                    </div>
                  </TableCell>

                  <TableCell className="w-[140px]">
                    <Badge
                      variant={alertBadgeVariant(event.alertType)}
                      className="gap-1.5 whitespace-nowrap text-[11px] font-medium"
                    >
                      {alertIcon(event.alertType)}
                      {event.alertType}
                    </Badge>
                  </TableCell>

                  <TableCell className="w-[110px]">
                    <span className="text-xs capitalize text-muted-foreground">
                      {priorityLabel(event.priority)}
                    </span>
                  </TableCell>

                  <TableCell className="w-[180px]">
                    <span className="block truncate font-mono text-xs">{event.host || '—'}</span>
                  </TableCell>

                  <TableCell className="w-[220px]">
                    <span className="block truncate text-xs">{event.sourceTypeName || '—'}</span>
                  </TableCell>

                  <TableCell className="w-[100px] text-right pr-4 text-xs text-muted-foreground">
                    {formatRelativeTime(event.timestamp)}
                  </TableCell>
                </TableRow>
              )
            })}
          </TableBody>
        </Table>
      </CardContent>
    </Card>
  )
}

export function EventStream() {
  const [hostFilter, setHostFilter] = useState('')
  const [alertTypeFilter, setAlertTypeFilter] = useState<AlertFilter>('all')
  const [expandedId, setExpandedId] = useState<string | null>(null)

  const {data, isLoading} = useQuery({
    queryKey: ['events', alertTypeFilter === 'all' ? '' : alertTypeFilter],
    queryFn: () =>
      api.getEvents({
        host: undefined,
        alertType: alertTypeFilter === 'all' ? undefined : alertTypeFilter,
        limit: 50,
      }),
    enabled: api.isAuthenticated(),
    refetchInterval: 15000,
  })

  const events = useMemo(() => data?.events ?? [], [data?.events])

  const filtered = useMemo(() => {
    let result = events
    if (hostFilter) {
      const q = hostFilter.toLowerCase()
      result = result.filter(
        (e) =>
          e.host?.toLowerCase().includes(q) ||
          e.title?.toLowerCase().includes(q) ||
          e.sourceTypeName?.toLowerCase().includes(q)
      )
    }
    return result
  }, [events, hostFilter])

  const errorCount = events.filter((e) => e.alertType === 'error').length
  const warningCount = events.filter((e) => e.alertType === 'warning').length
  const infoCount = events.filter((e) => e.alertType === 'info').length
  const successCount = events.filter((e) => e.alertType === 'success').length
  const uniqueHosts = new Set(events.map((e) => e.host).filter(Boolean)).size
  const uniqueSources = new Set(events.map((e) => e.sourceTypeName).filter(Boolean)).size

  const filterCounts: Record<AlertFilter, number> = {
    all: events.length,
    error: errorCount,
    warning: warningCount,
    info: infoCount,
    success: successCount,
  }

  let eventContent: ReactNode
  if (isLoading) {
    eventContent = <LoadingEvents />
  } else if (events.length === 0) {
    eventContent = <EmptyEvents />
  } else if (filtered.length === 0) {
    eventContent = <EmptyFilteredEvents />
  } else {
    eventContent = (
      <EventTable
        events={filtered}
        expandedId={expandedId}
        onToggleExpanded={setExpandedId}
      />
    )
  }

  return (
    <div className="space-y-3">
      <div className="flex flex-col sm:flex-row items-start sm:items-center gap-3">
        {!isLoading && events.length > 0 && (
          <div className="flex items-center gap-3 text-sm flex-wrap">
            <div className="flex items-center gap-1.5">
              <Zap className="h-3.5 w-3.5 text-muted-foreground" />
              <span className="font-semibold tabular-nums">{events.length}</span>
              <span className="text-muted-foreground text-xs">events</span>
            </div>
            <div className="h-4 w-px bg-border" />
            <div className="flex items-center gap-1.5">
              <AlertCircle className="h-3.5 w-3.5 text-danger-fg" />
              <span className="font-semibold tabular-nums">{errorCount}</span>
              <span className="text-muted-foreground text-xs">errors</span>
            </div>
            <div className="h-4 w-px bg-border" />
            <div className="flex items-center gap-1.5">
              <AlertTriangle className="h-3.5 w-3.5 text-warning-fg" />
              <span className="font-semibold tabular-nums">{warningCount}</span>
              <span className="text-muted-foreground text-xs">warnings</span>
            </div>
            <div className="h-4 w-px bg-border" />
            <div className="flex items-center gap-1.5">
              <Server className="h-3.5 w-3.5 text-chart-2" />
              <span className="font-semibold tabular-nums">{uniqueHosts}</span>
              <span className="text-muted-foreground text-xs">{uniqueHosts === 1 ? 'host' : 'hosts'}</span>
            </div>
            <div className="h-4 w-px bg-border" />
            <div className="flex items-center gap-1.5">
              <Tag className="h-3.5 w-3.5 text-chart-3" />
              <span className="font-semibold tabular-nums">{uniqueSources}</span>
              <span className="text-muted-foreground text-xs">{uniqueSources === 1 ? 'source' : 'sources'}</span>
            </div>
          </div>
        )}
      </div>

      <div className="flex flex-col sm:flex-row items-start sm:items-center gap-3">
        <div className="relative flex-1 max-w-md">
          <Search className="absolute left-2.5 top-2.5 h-4 w-4 text-muted-foreground" />
          <Input
            placeholder="Search by host, title, or source..."
            value={hostFilter}
            onChange={(e) => setHostFilter(e.target.value)}
            className="pl-9"
          />
        </div>
        <div className="flex items-center gap-1 rounded-lg border bg-background p-1">
          {(['all', 'error', 'warning', 'info', 'success'] as const).map((f) => (
            <button
              key={f}
              onClick={() => setAlertTypeFilter(f)}
              className={cn(
                'inline-flex items-center gap-1.5 rounded-md px-3 py-1.5 text-xs font-medium transition-colors',
                alertTypeFilter === f
                  ? 'bg-secondary text-secondary-foreground'
                  : 'text-muted-foreground hover:text-foreground hover:bg-muted/50'
              )}
            >
              <StatusDot tone={filterTone(f)} size="sm" />
              <span className="capitalize">{f}</span>
              <span className="ml-0.5 text-[10px] text-muted-foreground">{filterCounts[f]}</span>
            </button>
          ))}
        </div>
      </div>

      {eventContent}
    </div>
  )
}
