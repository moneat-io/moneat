// Moneat - observability platform
// Copyright (C) 2026 Moneat
//
// This program is free software: you can redistribute it and/or modify
// it under the terms of the GNU Affero General Public License as published by
// the Free Software Foundation, either version 3 of the License, or
// (at your option) any later version.

import {useQuery} from '@tanstack/react-query'
import {api, type DdConnectionResponse} from '@/lib/api'
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

export function NetworkConnections() {
  const [hostFilter, setHostFilter] = useState('')

  const {data, isLoading} = useQuery({
    queryKey: ['connections', hostFilter],
    queryFn: () =>
      api.getConnections({
        host: hostFilter || undefined,
        limit: 100,
      }),
    enabled: api.isAuthenticated(),
    refetchInterval: 10000,
  })

  const connections = data?.connections ?? []

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
            {data.totalCount.toLocaleString()} connections
          </span>
        )}
      </div>

      {isLoading ? (
        <div className="flex items-center justify-center py-12">
          <Loader2 className="h-6 w-6 animate-spin text-muted-foreground" />
        </div>
      ) : connections.length === 0 ? (
        <div className="text-center py-12 text-muted-foreground">
          <p className="font-medium">No connections found</p>
          <p className="text-sm mt-1">
            Network connections will appear when an agent sends connection data.
          </p>
        </div>
      ) : (
        <Table>
          <TableHeader>
            <TableRow>
              <TableHead>PID</TableHead>
              <TableHead>Local</TableHead>
              <TableHead>Remote</TableHead>
              <TableHead>Protocol</TableHead>
              <TableHead>Direction</TableHead>
              <TableHead>Host</TableHead>
              <TableHead className="text-right">Sent</TableHead>
              <TableHead className="text-right">Recv</TableHead>
            </TableRow>
          </TableHeader>
          <TableBody>
            {connections.map((conn: DdConnectionResponse) => (
              <TableRow key={conn.connectionId}>
                <TableCell className="font-mono text-xs">{conn.pid || '—'}</TableCell>
                <TableCell className="font-mono text-xs">
                  {conn.localAddr}:{conn.localPort}
                </TableCell>
                <TableCell className="font-mono text-xs">
                  {conn.remoteAddr}:{conn.remotePort}
                </TableCell>
                <TableCell>
                  <Badge variant="outline">{conn.protocol}</Badge>
                </TableCell>
                <TableCell className="text-xs">{conn.direction || '—'}</TableCell>
                <TableCell className="font-mono text-xs">{conn.host}</TableCell>
                <TableCell className="text-right text-xs font-mono">
                  {formatBytes(conn.bytesSent)}
                </TableCell>
                <TableCell className="text-right text-xs font-mono">
                  {formatBytes(conn.bytesRecv)}
                </TableCell>
              </TableRow>
            ))}
          </TableBody>
        </Table>
      )}
    </div>
  )
}
