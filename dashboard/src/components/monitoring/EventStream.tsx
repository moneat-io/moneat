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
import {Input} from '@/components/ui/input'
import {Select, SelectContent, SelectItem, SelectTrigger, SelectValue} from '@/components/ui/select'
import {Loader2, Search} from 'lucide-react'
import {useState} from 'react'

function alertBadgeVariant(alertType: string): 'default' | 'secondary' | 'destructive' | 'outline' {
  switch (alertType) {
    case 'error': return 'destructive'
    case 'warning': return 'secondary'
    case 'success': return 'outline'
    default: return 'default'
  }
}

function formatTimestamp(ts: string): string {
  if (!ts) return '—'
  try {
    return new Date(ts).toLocaleString()
  } catch {
    return ts
  }
}

export function EventStream() {
  const [hostFilter, setHostFilter] = useState('')
  const [alertTypeFilter, setAlertTypeFilter] = useState('')

  const {data, isLoading} = useQuery({
    queryKey: ['events', hostFilter, alertTypeFilter],
    queryFn: () =>
      api.getEvents({
        host: hostFilter || undefined,
        alertType: alertTypeFilter || undefined,
        limit: 50,
      }),
    enabled: api.isAuthenticated(),
    refetchInterval: 15000,
  })

  const events = data?.events ?? []

  return (
    <div className="space-y-4">
      <div className="flex items-center gap-2">
        <div className="relative flex-1 max-w-sm">
          <Search className="absolute left-2.5 top-2.5 h-4 w-4 text-muted-foreground" />
          <Input
            placeholder="Filter by host..."
            value={hostFilter}
            onChange={(e) => setHostFilter(e.target.value)}
            className="pl-9"
          />
        </div>
        <Select value={alertTypeFilter} onValueChange={(v) => setAlertTypeFilter(v === 'all' ? '' : v)}>
          <SelectTrigger className="w-[140px]">
            <SelectValue placeholder="Alert type" />
          </SelectTrigger>
          <SelectContent>
            <SelectItem value="all">All types</SelectItem>
            <SelectItem value="info">Info</SelectItem>
            <SelectItem value="warning">Warning</SelectItem>
            <SelectItem value="error">Error</SelectItem>
            <SelectItem value="success">Success</SelectItem>
          </SelectContent>
        </Select>
        {data?.totalCount != null && (
          <span className="text-sm text-muted-foreground">
            {data.totalCount.toLocaleString()} events
          </span>
        )}
      </div>

      {isLoading ? (
        <div className="flex items-center justify-center py-12">
          <Loader2 className="h-6 w-6 animate-spin text-muted-foreground" />
        </div>
      ) : events.length === 0 ? (
        <div className="text-center py-12 text-muted-foreground">
          <p className="font-medium">No events found</p>
          <p className="text-sm mt-1">
            Events will appear when an agent sends event data.
          </p>
        </div>
      ) : (
        <Table>
          <TableHeader>
            <TableRow>
              <TableHead>Title</TableHead>
              <TableHead>Alert</TableHead>
              <TableHead>Host</TableHead>
              <TableHead>Source</TableHead>
              <TableHead>Time</TableHead>
            </TableRow>
          </TableHeader>
          <TableBody>
            {events.map((event: DdEventResponse) => (
              <TableRow key={event.eventId}>
                <TableCell className="max-w-md">
                  <p className="font-medium truncate">{event.title}</p>
                  {event.text && (
                    <p className="text-xs text-muted-foreground truncate mt-0.5">{event.text}</p>
                  )}
                </TableCell>
                <TableCell>
                  <Badge variant={alertBadgeVariant(event.alertType)}>
                    {event.alertType}
                  </Badge>
                </TableCell>
                <TableCell className="font-mono text-xs">{event.host || '—'}</TableCell>
                <TableCell className="text-xs">{event.sourceTypeName || '—'}</TableCell>
                <TableCell className="text-xs whitespace-nowrap">
                  {formatTimestamp(event.timestamp)}
                </TableCell>
              </TableRow>
            ))}
          </TableBody>
        </Table>
      )}
    </div>
  )
}
