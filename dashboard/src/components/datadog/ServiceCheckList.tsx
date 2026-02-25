// Moneat - observability platform
// Copyright (C) 2026 Moneat
//
// This program is free software: you can redistribute it and/or modify
// it under the terms of the GNU Affero General Public License as published by
// the Free Software Foundation, either version 3 of the License, or
// (at your option) any later version.

import {useQuery} from '@tanstack/react-query'
import {api, type DdServiceCheckResponse} from '@/lib/api'
import {Table, TableBody, TableCell, TableHead, TableHeader, TableRow} from '@/components/ui/table'
import {Badge} from '@/components/ui/badge'
import {Input} from '@/components/ui/input'
import {Loader2, Search} from 'lucide-react'
import {useState} from 'react'

function statusBadgeVariant(status: string): 'default' | 'secondary' | 'destructive' | 'outline' {
  switch (status) {
    case 'ok': return 'outline'
    case 'warning': return 'secondary'
    case 'critical': return 'destructive'
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

export function ServiceCheckList() {
  const [hostFilter, setHostFilter] = useState('')

  const {data, isLoading} = useQuery({
    queryKey: ['ddServiceChecks', hostFilter],
    queryFn: () =>
      api.getDdServiceChecks({
        host: hostFilter || undefined,
        limit: 50,
      }),
    enabled: api.isAuthenticated(),
    refetchInterval: 15000,
  })

  const checks = data?.serviceChecks ?? []

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
        {data?.totalCount != null && (
          <span className="text-sm text-muted-foreground">
            {data.totalCount.toLocaleString()} checks
          </span>
        )}
      </div>

      {isLoading ? (
        <div className="flex items-center justify-center py-12">
          <Loader2 className="h-6 w-6 animate-spin text-muted-foreground" />
        </div>
      ) : checks.length === 0 ? (
        <div className="text-center py-12 text-muted-foreground">
          <p className="font-medium">No service checks found</p>
          <p className="text-sm mt-1">
            Service checks will appear when a DD-compatible agent reports health checks.
          </p>
        </div>
      ) : (
        <Table>
          <TableHeader>
            <TableRow>
              <TableHead>Check Name</TableHead>
              <TableHead>Status</TableHead>
              <TableHead>Host</TableHead>
              <TableHead>Message</TableHead>
              <TableHead>Time</TableHead>
            </TableRow>
          </TableHeader>
          <TableBody>
            {checks.map((check: DdServiceCheckResponse) => (
              <TableRow key={check.checkId}>
                <TableCell className="font-mono text-sm">{check.checkName}</TableCell>
                <TableCell>
                  <Badge variant={statusBadgeVariant(check.status)}>
                    {check.status}
                  </Badge>
                </TableCell>
                <TableCell className="font-mono text-xs">{check.host || '—'}</TableCell>
                <TableCell className="text-xs max-w-xs truncate">{check.message || '—'}</TableCell>
                <TableCell className="text-xs whitespace-nowrap">
                  {formatTimestamp(check.timestamp)}
                </TableCell>
              </TableRow>
            ))}
          </TableBody>
        </Table>
      )}
    </div>
  )
}
