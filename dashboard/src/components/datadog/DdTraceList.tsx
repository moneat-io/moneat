// Moneat - observability platform
// Copyright (C) 2026 Moneat
//
// This program is free software: you can redistribute it and/or modify
// it under the terms of the GNU Affero General Public License as published by
// the Free Software Foundation, either version 3 of the License, or
// (at your option) any later version.

import {useQuery} from '@tanstack/react-query'
import {api, type DdTraceListItem} from '@/lib/api'
import {Table, TableBody, TableCell, TableHead, TableHeader, TableRow} from '@/components/ui/table'
import {Badge} from '@/components/ui/badge'
import {Input} from '@/components/ui/input'
import {Loader2, AlertTriangle, Search} from 'lucide-react'
import {useState} from 'react'
import {Link} from '@tanstack/react-router'

function formatDuration(ns: number): string {
  if (ns < 1000) return `${ns}ns`
  if (ns < 1_000_000) return `${(ns / 1000).toFixed(1)}µs`
  if (ns < 1_000_000_000) return `${(ns / 1_000_000).toFixed(1)}ms`
  return `${(ns / 1_000_000_000).toFixed(2)}s`
}

function formatTime(ns: number): string {
  if (!ns) return '—'
  const date = new Date(ns / 1_000_000) // ns to ms
  return date.toLocaleString()
}

export function DdTraceList() {
  const [serviceFilter, setServiceFilter] = useState('')

  const {data, isLoading} = useQuery({
    queryKey: ['ddTraces', serviceFilter],
    queryFn: () =>
      api.getDdTraces({
        service: serviceFilter || undefined,
        limit: 50,
      }),
    enabled: api.isAuthenticated(),
    refetchInterval: 15000,
  })

  const traces = data?.traces ?? []

  return (
    <div className="space-y-4">
      <div className="flex items-center gap-2">
        <div className="relative flex-1 max-w-sm">
          <Search className="absolute left-2.5 top-2.5 h-4 w-4 text-muted-foreground" />
          <Input
            placeholder="Filter by service..."
            value={serviceFilter}
            onChange={(e) => setServiceFilter(e.target.value)}
            className="pl-9"
          />
        </div>
        {data?.totalCount != null && (
          <span className="text-sm text-muted-foreground">
            {data.totalCount.toLocaleString()} traces
          </span>
        )}
      </div>

      {isLoading ? (
        <div className="flex items-center justify-center py-12">
          <Loader2 className="h-6 w-6 animate-spin text-muted-foreground" />
        </div>
      ) : traces.length === 0 ? (
        <div className="text-center py-12 text-muted-foreground">
          <p className="font-medium">No traces found</p>
          <p className="text-sm mt-1">
            Configure a Datadog-compatible agent to send APM traces.
          </p>
        </div>
      ) : (
        <Table>
          <TableHeader>
            <TableRow>
              <TableHead>Service</TableHead>
              <TableHead>Resource</TableHead>
              <TableHead>Spans</TableHead>
              <TableHead>Duration</TableHead>
              <TableHead>Time</TableHead>
              <TableHead>Status</TableHead>
            </TableRow>
          </TableHeader>
          <TableBody>
            {traces.map((trace: DdTraceListItem) => (
              <TableRow key={trace.traceId}>
                <TableCell>
                  <Link
                    to="/dd-traces/$traceId"
                    params={{traceId: trace.traceId}}
                    className="font-medium text-primary hover:underline"
                  >
                    {trace.rootService}
                  </Link>
                </TableCell>
                <TableCell className="max-w-[300px] truncate text-sm text-muted-foreground">
                  {trace.rootResource}
                </TableCell>
                <TableCell className="text-sm">{trace.spanCount}</TableCell>
                <TableCell className="text-sm font-mono">
                  {formatDuration(trace.durationNs)}
                </TableCell>
                <TableCell className="text-sm text-muted-foreground">
                  {formatTime(trace.startNs)}
                </TableCell>
                <TableCell>
                  {trace.hasError ? (
                    <Badge variant="destructive" className="gap-1">
                      <AlertTriangle className="h-3 w-3" />
                      Error
                    </Badge>
                  ) : (
                    <Badge variant="outline">OK</Badge>
                  )}
                </TableCell>
              </TableRow>
            ))}
          </TableBody>
        </Table>
      )}
    </div>
  )
}
