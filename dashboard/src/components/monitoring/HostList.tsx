// Moneat - observability platform
// Copyright (C) 2026 Moneat
//
// This program is free software: you can redistribute it and/or modify
// it under the terms of the GNU Affero General Public License as published by
// the Free Software Foundation, either version 3 of the License, or
// (at your option) any later version.

import {useQuery} from '@tanstack/react-query'
import {api, type DdHostResponse} from '@/lib/api'
import {Table, TableBody, TableCell, TableHead, TableHeader, TableRow} from '@/components/ui/table'
import {Badge} from '@/components/ui/badge'
import {Loader2, Server} from 'lucide-react'

function formatBytes(kb: number): string {
  if (kb < 1024) return `${kb} KB`
  const mb = kb / 1024
  if (mb < 1024) return `${mb.toFixed(1)} MB`
  const gb = mb / 1024
  return `${gb.toFixed(1)} GB`
}

function formatTimestamp(ts: string): string {
  if (!ts) return '—'
  try {
    return new Date(ts).toLocaleString()
  } catch {
    return ts
  }
}

function isOnline(lastSeenAt: string): boolean {
  if (!lastSeenAt) return false
  try {
    const diff = Date.now() - new Date(lastSeenAt).getTime()
    return diff < 5 * 60 * 1000 // 5 minutes
  } catch {
    return false
  }
}

export function HostList() {
  const {data, isLoading} = useQuery({
    queryKey: ['hosts'],
    queryFn: () => api.getHosts(),
    enabled: api.isAuthenticated(),
    refetchInterval: 30000,
  })

  const hosts = data?.hosts ?? []

  return (
    <div className="space-y-4">
      <div className="flex items-center gap-2">
        <Server className="h-5 w-5 text-muted-foreground" />
        {data?.totalCount != null && (
          <span className="text-sm text-muted-foreground">
            {data.totalCount.toLocaleString()} hosts
          </span>
        )}
      </div>

      {isLoading ? (
        <div className="flex items-center justify-center py-12">
          <Loader2 className="h-6 w-6 animate-spin text-muted-foreground" />
        </div>
      ) : hosts.length === 0 ? (
        <div className="text-center py-12 text-muted-foreground">
          <p className="font-medium">No hosts reporting</p>
          <p className="text-sm mt-1">
            Hosts will appear when an agent sends metadata.
          </p>
        </div>
      ) : (
        <Table>
          <TableHeader>
            <TableRow>
              <TableHead>Status</TableHead>
              <TableHead>Hostname</TableHead>
              <TableHead>OS / Platform</TableHead>
              <TableHead>CPU Cores</TableHead>
              <TableHead>Memory</TableHead>
              <TableHead>Agent</TableHead>
              <TableHead>Last Seen</TableHead>
            </TableRow>
          </TableHeader>
          <TableBody>
            {hosts.map((host: DdHostResponse) => (
              <TableRow key={host.id}>
                <TableCell>
                  <Badge variant={isOnline(host.lastSeenAt) ? 'default' : 'secondary'}>
                    {isOnline(host.lastSeenAt) ? 'Online' : 'Offline'}
                  </Badge>
                </TableCell>
                <TableCell className="font-mono text-sm font-medium">{host.hostname}</TableCell>
                <TableCell className="text-xs">
                  {host.os || host.platform || '—'}
                  {host.platform && host.os && host.platform !== host.os && (
                    <span className="text-muted-foreground ml-1">({host.platform})</span>
                  )}
                </TableCell>
                <TableCell className="text-sm">{host.cpuCores || '—'}</TableCell>
                <TableCell className="text-sm">
                  {host.memoryTotalKb ? formatBytes(host.memoryTotalKb) : '—'}
                </TableCell>
                <TableCell className="font-mono text-xs">{host.agentVersion || '—'}</TableCell>
                <TableCell className="text-xs whitespace-nowrap">
                  {formatTimestamp(host.lastSeenAt)}
                </TableCell>
              </TableRow>
            ))}
          </TableBody>
        </Table>
      )}
    </div>
  )
}
