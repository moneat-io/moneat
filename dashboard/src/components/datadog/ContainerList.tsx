// Moneat - observability platform
// Copyright (C) 2026 Moneat
//
// This program is free software: you can redistribute it and/or modify
// it under the terms of the GNU Affero General Public License as published by
// the Free Software Foundation, either version 3 of the License, or
// (at your option) any later version.

import {useQuery} from '@tanstack/react-query'
import {api, type DdContainerResponse} from '@/lib/api'
import {Table, TableBody, TableCell, TableHead, TableHeader, TableRow} from '@/components/ui/table'
import {Badge} from '@/components/ui/badge'
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

function stateBadge(state: string): 'default' | 'secondary' | 'destructive' {
  switch (state) {
    case 'running': return 'default'
    case 'exited':
    case 'dead': return 'destructive'
    default: return 'secondary'
  }
}

export function ContainerList() {
  const [hostFilter, setHostFilter] = useState('')

  const {data, isLoading} = useQuery({
    queryKey: ['ddContainers', hostFilter],
    queryFn: () =>
      api.getDdContainers({
        host: hostFilter || undefined,
        limit: 100,
      }),
    enabled: api.isAuthenticated(),
    refetchInterval: 10000,
  })

  const containers = data?.containers ?? []

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
            {data.totalCount.toLocaleString()} containers
          </span>
        )}
      </div>

      {isLoading ? (
        <div className="flex items-center justify-center py-12">
          <Loader2 className="h-6 w-6 animate-spin text-muted-foreground" />
        </div>
      ) : containers.length === 0 ? (
        <div className="text-center py-12 text-muted-foreground">
          <p className="font-medium">No containers found</p>
          <p className="text-sm mt-1">
            Containers will appear when a DD-compatible agent sends container data.
          </p>
        </div>
      ) : (
        <Table>
          <TableHeader>
            <TableRow>
              <TableHead>Name</TableHead>
              <TableHead>State</TableHead>
              <TableHead>Image</TableHead>
              <TableHead>Host</TableHead>
              <TableHead className="text-right">CPU %</TableHead>
              <TableHead className="text-right">Memory</TableHead>
              <TableHead className="text-right">Net RX</TableHead>
              <TableHead className="text-right">Net TX</TableHead>
            </TableRow>
          </TableHeader>
          <TableBody>
            {containers.map((c: DdContainerResponse) => (
              <TableRow key={c.id}>
                <TableCell className="font-medium text-sm max-w-xs truncate">
                  {c.name || c.containerId.slice(0, 12)}
                </TableCell>
                <TableCell>
                  <Badge variant={stateBadge(c.state)}>{c.state}</Badge>
                </TableCell>
                <TableCell className="text-xs font-mono max-w-xs truncate">{c.image || '—'}</TableCell>
                <TableCell className="font-mono text-xs">{c.host}</TableCell>
                <TableCell className="text-right text-sm">
                  {c.cpuPercent.toFixed(1)}%
                </TableCell>
                <TableCell className="text-right text-xs font-mono">
                  {formatBytes(c.memUsage)}
                  {c.memLimit > 0 && (
                    <span className="text-muted-foreground"> / {formatBytes(c.memLimit)}</span>
                  )}
                </TableCell>
                <TableCell className="text-right text-xs font-mono">
                  {formatBytes(c.netRxBytes)}
                </TableCell>
                <TableCell className="text-right text-xs font-mono">
                  {formatBytes(c.netTxBytes)}
                </TableCell>
              </TableRow>
            ))}
          </TableBody>
        </Table>
      )}
    </div>
  )
}
