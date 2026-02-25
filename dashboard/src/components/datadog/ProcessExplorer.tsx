// Moneat - observability platform
// Copyright (C) 2026 Moneat
//
// This program is free software: you can redistribute it and/or modify
// it under the terms of the GNU Affero General Public License as published by
// the Free Software Foundation, either version 3 of the License, or
// (at your option) any later version.

import {useQuery} from '@tanstack/react-query'
import {api, type DdProcessResponse} from '@/lib/api'
import {Table, TableBody, TableCell, TableHead, TableHeader, TableRow} from '@/components/ui/table'
import {Input} from '@/components/ui/input'
import {Loader2, Search} from 'lucide-react'
import {useState} from 'react'

function formatBytes(bytes: number): string {
  if (bytes < 1024) return `${bytes} B`
  const kb = bytes / 1024
  if (kb < 1024) return `${kb.toFixed(1)} KB`
  const mb = kb / 1024
  if (mb < 1024) return `${mb.toFixed(1)} MB`
  const gb = mb / 1024
  return `${gb.toFixed(2)} GB`
}

export function ProcessExplorer() {
  const [hostFilter, setHostFilter] = useState('')

  const {data, isLoading} = useQuery({
    queryKey: ['ddProcesses', hostFilter],
    queryFn: () =>
      api.getDdProcesses({
        host: hostFilter || undefined,
        limit: 100,
      }),
    enabled: api.isAuthenticated(),
    refetchInterval: 10000,
  })

  const processes = data?.processes ?? []

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
            {data.totalCount.toLocaleString()} processes
          </span>
        )}
      </div>

      {isLoading ? (
        <div className="flex items-center justify-center py-12">
          <Loader2 className="h-6 w-6 animate-spin text-muted-foreground" />
        </div>
      ) : processes.length === 0 ? (
        <div className="text-center py-12 text-muted-foreground">
          <p className="font-medium">No processes found</p>
          <p className="text-sm mt-1">
            Processes will appear when a DD-compatible agent sends process data.
          </p>
        </div>
      ) : (
        <Table>
          <TableHeader>
            <TableRow>
              <TableHead>PID</TableHead>
              <TableHead>Name</TableHead>
              <TableHead>User</TableHead>
              <TableHead>Host</TableHead>
              <TableHead className="text-right">CPU %</TableHead>
              <TableHead className="text-right">RSS</TableHead>
              <TableHead className="text-right">Threads</TableHead>
              <TableHead>State</TableHead>
            </TableRow>
          </TableHeader>
          <TableBody>
            {processes.map((proc: DdProcessResponse) => (
              <TableRow key={proc.processId}>
                <TableCell className="font-mono text-xs">{proc.pid}</TableCell>
                <TableCell className="font-medium text-sm max-w-xs truncate">
                  {proc.name}
                </TableCell>
                <TableCell className="text-xs">{proc.user || '—'}</TableCell>
                <TableCell className="font-mono text-xs">{proc.host}</TableCell>
                <TableCell className="text-right text-sm">
                  {proc.cpuPercent.toFixed(1)}%
                </TableCell>
                <TableCell className="text-right text-xs font-mono">
                  {formatBytes(proc.memRss)}
                </TableCell>
                <TableCell className="text-right text-sm">{proc.threadCount}</TableCell>
                <TableCell className="text-xs">{proc.state || '—'}</TableCell>
              </TableRow>
            ))}
          </TableBody>
        </Table>
      )}
    </div>
  )
}
