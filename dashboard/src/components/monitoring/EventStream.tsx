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
import {useState, useMemo} from 'react'
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

function alertBadgeClasses(alertType: string): string {
  switch (alertType) {
    case 'error':
      return 'bg-red-500/15 text-red-700 dark:text-red-300 border-red-500/20'
    case 'warning':
      return 'bg-yellow-500/15 text-yellow-700 dark:text-yellow-300 border-yellow-500/20'
    case 'success':
      return 'bg-emerald-500/15 text-emerald-700 dark:text-emerald-300 border-emerald-500/20'
    case 'info':
      return 'bg-blue-500/15 text-blue-700 dark:text-blue-300 border-blue-500/20'
    default:
      return 'bg-muted text-muted-foreground border-border'
  }
}

function alertAccentColor(alertType: string): string {
  switch (alertType) {
    case 'error':
      return 'border-l-red-500'
    case 'warning':
      return 'border-l-yellow-500'
    case 'success':
      return 'border-l-emerald-500'
    case 'info':
      return 'border-l-blue-500'
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

function filterDot(alertType: AlertFilter): string {
  switch (alertType) {
    case 'error':
      return 'bg-red-500'
    case 'warning':
      return 'bg-yellow-500'
    case 'info':
      return 'bg-blue-500'
    case 'success':
      return 'bg-emerald-500'
    default:
      return 'bg-violet-500'
  }
}

export function EventStream() {
  const [hostFilter, setHostFilter] = useState('')
  const [alertTypeFilter, setAlertTypeFilter] = useState<AlertFilter>('all')
  const [expandedId, setExpandedId] = useState<string | null>(null)

  const {data, isLoading} = useQuery({
    queryKey: ['events', hostFilter, alertTypeFilter === 'all' ? '' : alertTypeFilter],
    queryFn: () =>
      api.getEvents({
        host: hostFilter || undefined,
        alertType: alertTypeFilter === 'all' ? undefined : alertTypeFilter,
        limit: 50,
      }),
    enabled: api.isAuthenticated(),
    refetchInterval: 15000,
  })

  const events = data?.events ?? []

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

  return (
    <div className="space-y-3">
      <div className="flex flex-col sm:flex-row items-start sm:items-center gap-3">
        {!isLoading && events.length > 0 && (
          <div className="flex items-center gap-3 text-sm flex-wrap">
            <div className="flex items-center gap-1.5">
              <Zap className="h-3.5 w-3.5 text-violet-500" />
              <span className="font-semibold tabular-nums">{events.length}</span>
              <span className="text-muted-foreground text-xs">events</span>
            </div>
            <div className="h-4 w-px bg-border" />
            <div className="flex items-center gap-1.5">
              <AlertCircle className="h-3.5 w-3.5 text-red-500" />
              <span className="font-semibold tabular-nums">{errorCount}</span>
              <span className="text-muted-foreground text-xs">errors</span>
            </div>
            <div className="h-4 w-px bg-border" />
            <div className="flex items-center gap-1.5">
              <AlertTriangle className="h-3.5 w-3.5 text-yellow-500" />
              <span className="font-semibold tabular-nums">{warningCount}</span>
              <span className="text-muted-foreground text-xs">warnings</span>
            </div>
            <div className="h-4 w-px bg-border" />
            <div className="flex items-center gap-1.5">
              <Server className="h-3.5 w-3.5 text-emerald-500" />
              <span className="font-semibold tabular-nums">{uniqueHosts}</span>
              <span className="text-muted-foreground text-xs">{uniqueHosts === 1 ? 'host' : 'hosts'}</span>
            </div>
            <div className="h-4 w-px bg-border" />
            <div className="flex items-center gap-1.5">
              <Tag className="h-3.5 w-3.5 text-sky-500" />
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
                  ? 'bg-secondary text-secondary-foreground shadow-sm'
                  : 'text-muted-foreground hover:text-foreground hover:bg-muted/50'
              )}
            >
              <div className={cn('h-1.5 w-1.5 rounded-full', filterDot(f))} />
              <span className="capitalize">{f}</span>
              <span className="ml-0.5 text-[10px] text-muted-foreground">{filterCounts[f]}</span>
            </button>
          ))}
        </div>
      </div>

      {isLoading ? (
        <div className="flex items-center justify-center py-16">
          <div className="flex flex-col items-center gap-3">
            <Loader2 className="h-8 w-8 animate-spin text-muted-foreground" />
            <p className="text-muted-foreground text-sm">Loading events...</p>
          </div>
        </div>
      ) : events.length === 0 ? (
        <Card className="border-dashed">
          <CardContent className="py-16 text-center">
            <div className="mx-auto mb-6 flex h-20 w-20 items-center justify-center rounded-2xl bg-gradient-to-br from-violet-500/10 to-sky-500/10">
              <Zap className="h-10 w-10 text-violet-500" />
            </div>
            <h3 className="text-xl font-semibold mb-2">No events found</h3>
            <p className="text-muted-foreground mb-2 max-w-sm mx-auto">
              Events will appear when an agent sends event data.
            </p>
          </CardContent>
        </Card>
      ) : filtered.length === 0 ? (
        <div className="text-center py-12 text-muted-foreground">
          <p className="font-medium">No events match your filters</p>
          <p className="text-sm mt-1">Try adjusting your search or alert type filter.</p>
        </div>
      ) : (
        <Card className="overflow-hidden border-border/60 shadow-sm">
          <CardContent className="p-0">
            <Table className="min-w-[800px]">
              <TableHeader>
                <TableRow className="hover:bg-transparent bg-muted/30">
                  <TableHead className="pl-4 w-8" />
                  <TableHead>Event</TableHead>
                  <TableHead>Alert</TableHead>
                  <TableHead>Priority</TableHead>
                  <TableHead>Host</TableHead>
                  <TableHead>Source</TableHead>
                  <TableHead className="text-right pr-4">Time</TableHead>
                </TableRow>
              </TableHeader>
              <TableBody>
                {filtered.map((event: DdEventResponse) => {
                  const isExpanded = expandedId === event.eventId
                  const hasTags = event.tags && Object.keys(event.tags).length > 0
                  const hasDetails = event.text || hasTags || event.aggregationKey || event.deviceName
                  const tagEntries = hasTags ? Object.entries(event.tags) : []

                  return (
                    <TableRow
                      key={event.eventId}
                      className={cn(
                        'group transition-colors border-l-2',
                        alertAccentColor(event.alertType),
                        hasDetails ? 'cursor-pointer' : '',
                        isExpanded ? 'bg-muted/40' : 'hover:bg-muted/50'
                      )}
                      onClick={() => hasDetails && setExpandedId(isExpanded ? null : event.eventId)}
                    >
                      <TableCell className="pl-4 pr-0 w-8">
                        {hasDetails ? (
                          isExpanded ? (
                            <ChevronDown className="h-3.5 w-3.5 text-muted-foreground" />
                          ) : (
                            <ChevronRight className="h-3.5 w-3.5 text-muted-foreground opacity-0 group-hover:opacity-100 transition-opacity" />
                          )
                        ) : null}
                      </TableCell>

                      <TableCell className="max-w-md">
                        <div className="min-w-0">
                          <TooltipProvider delayDuration={300}>
                            <Tooltip>
                              <TooltipTrigger asChild>
                                <p className="font-medium text-sm truncate max-w-[350px]">{event.title}</p>
                              </TooltipTrigger>
                              <TooltipContent side="top" className="max-w-md">
                                <p className="text-xs break-all">{event.title}</p>
                              </TooltipContent>
                            </Tooltip>
                          </TooltipProvider>
                          {!isExpanded && event.text && (
                            <p className="text-[11px] text-muted-foreground truncate max-w-[350px] mt-0.5">
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
                                <div className="flex items-center gap-3 text-[11px] text-muted-foreground">
                                  {event.aggregationKey && (
                                    <span>
                                      <span className="font-medium text-foreground/70">agg:</span>{' '}
                                      <span className="font-mono">{event.aggregationKey}</span>
                                    </span>
                                  )}
                                  {event.deviceName && (
                                    <span>
                                      <span className="font-medium text-foreground/70">device:</span>{' '}
                                      <span className="font-mono">{event.deviceName}</span>
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

                      <TableCell>
                        <Badge
                          variant="secondary"
                          className={cn('text-[11px] gap-1.5 font-medium', alertBadgeClasses(event.alertType))}
                        >
                          {alertIcon(event.alertType)}
                          {event.alertType}
                        </Badge>
                      </TableCell>

                      <TableCell>
                        <span className="text-xs capitalize text-muted-foreground">
                          {priorityLabel(event.priority)}
                        </span>
                      </TableCell>

                      <TableCell>
                        <span className="font-mono text-xs">{event.host || '—'}</span>
                      </TableCell>

                      <TableCell>
                        <span className="text-xs">{event.sourceTypeName || '—'}</span>
                      </TableCell>

                      <TableCell className="text-right pr-4 text-xs text-muted-foreground">
                        {formatRelativeTime(event.timestamp)}
                      </TableCell>
                    </TableRow>
                  )
                })}
              </TableBody>
            </Table>
          </CardContent>
        </Card>
      )}
    </div>
  )
}
