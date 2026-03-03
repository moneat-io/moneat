// Moneat - observability platform
// Copyright (C) 2026 Moneat
//
// This program is free software: you can redistribute it and/or modify
// it under the terms of the GNU Affero General Public License as published by
// the Free Software Foundation, either version 3 of the License, or
// (at your option) any later version.

import {createFileRoute} from '@tanstack/react-router'
import {useQuery} from '@tanstack/react-query'
import {api} from '@/lib/api'
import {Badge} from '@/components/ui/badge'
import {Card, CardContent} from '@/components/ui/card'
import {Table, TableBody, TableCell, TableHead, TableHeader, TableRow} from '@/components/ui/table'
import {Input} from '@/components/ui/input'
import {ArrowRightLeft, Loader2, Search} from 'lucide-react'
import {useMemo, useState} from 'react'

export const Route = createFileRoute('/monitoring/network-devices/flows')({
  component: NdmFlows,
})

interface NetworkFlow {
  flowId?: string
  srcIp?: string
  srcPort?: number
  dstIp?: string
  dstPort?: number
  protocol?: string
  bytes?: number
  flowType?: string
  sampledAt?: string
}

function NdmFlows() {
  const [searchQuery, setSearchQuery] = useState('')

  const {data, isLoading} = useQuery({
    queryKey: ['ndm-flows'],
    queryFn: () => api.get<{flows?: NetworkFlow[]}>('/v1/network-devices/flows?limit=100'),
  })

  const flows: NetworkFlow[] = data?.flows ?? []

  const filtered = useMemo(() => {
    if (!searchQuery) return flows
    const q = searchQuery.toLowerCase()
    return flows.filter((f) =>
      f.srcIp?.toLowerCase().includes(q) ||
      f.dstIp?.toLowerCase().includes(q) ||
      f.protocol?.toLowerCase().includes(q) ||
      f.flowType?.toLowerCase().includes(q)
    )
  }, [flows, searchQuery])

  if (isLoading) {
    return (
      <div className="flex items-center justify-center py-16">
        <div className="flex flex-col items-center gap-3">
          <Loader2 className="h-8 w-8 animate-spin text-muted-foreground" />
          <p className="text-muted-foreground text-sm">Loading flows...</p>
        </div>
      </div>
    )
  }

  if (flows.length === 0) {
    return (
      <Card className="border-dashed">
        <CardContent className="py-16 text-center">
          <div className="mx-auto mb-6 flex h-16 w-16 items-center justify-center rounded-2xl bg-gradient-to-br from-cyan-500/10 to-blue-500/10">
            <ArrowRightLeft className="h-8 w-8 text-cyan-500" />
          </div>
          <h3 className="text-lg font-semibold mb-2">No flow data</h3>
          <p className="text-muted-foreground max-w-sm mx-auto">
            Network flow data will appear here once collected by the agent.
          </p>
        </CardContent>
      </Card>
    )
  }

  return (
    <div className="space-y-4">
      <div className="flex items-center gap-3">
        <div className="flex items-center gap-1.5 text-sm">
          <ArrowRightLeft className="h-3.5 w-3.5 text-cyan-500" />
          <span className="font-semibold tabular-nums">{flows.length}</span>
          <span className="text-muted-foreground text-xs">flows</span>
        </div>
        <div className="h-4 w-px bg-border" />
        <div className="relative flex-1 max-w-md">
          <Search className="absolute left-2.5 top-2.5 h-4 w-4 text-muted-foreground" />
          <Input
            placeholder="Search by IP, protocol, or type..."
            value={searchQuery}
            onChange={(e) => setSearchQuery(e.target.value)}
            className="pl-9"
          />
        </div>
      </div>

      {filtered.length === 0 ? (
        <div className="text-center py-12 text-muted-foreground">
          <p className="font-medium">No flows match your search</p>
          <p className="text-sm mt-1">Try adjusting your search query.</p>
        </div>
      ) : (
        <Card className="overflow-hidden border-border/60 shadow-sm">
          <CardContent className="p-0">
            <Table>
              <TableHeader>
                <TableRow className="hover:bg-transparent bg-muted/30">
                  <TableHead className="pl-4">Source</TableHead>
                  <TableHead>Destination</TableHead>
                  <TableHead>Protocol</TableHead>
                  <TableHead>Bytes</TableHead>
                  <TableHead>Type</TableHead>
                  <TableHead className="text-right pr-4">Time</TableHead>
                </TableRow>
              </TableHeader>
              <TableBody>
                {filtered.map((f) => (
                  <TableRow key={f.flowId} className="hover:bg-muted/50 transition-colors">
                    <TableCell className="pl-4 font-mono text-xs">{f.srcIp}:{f.srcPort}</TableCell>
                    <TableCell className="font-mono text-xs">{f.dstIp}:{f.dstPort}</TableCell>
                    <TableCell className="text-sm">{f.protocol}</TableCell>
                    <TableCell className="text-sm tabular-nums">
                      {Number(f.bytes ?? 0).toLocaleString()}
                    </TableCell>
                    <TableCell>
                      <Badge variant="outline" className="text-xs">{f.flowType}</Badge>
                    </TableCell>
                    <TableCell className="text-right pr-4 text-xs text-muted-foreground">{f.sampledAt}</TableCell>
                  </TableRow>
                ))}
              </TableBody>
            </Table>
          </CardContent>
        </Card>
      )}
    </div>
  )
}
