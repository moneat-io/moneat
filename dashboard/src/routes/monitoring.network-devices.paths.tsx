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
import {Card, CardContent} from '@/components/ui/card'
import {Table, TableBody, TableCell, TableHead, TableHeader, TableRow} from '@/components/ui/table'
import {Input} from '@/components/ui/input'
import {Badge} from '@/components/ui/badge'
import {Loader2, Search, Wifi} from 'lucide-react'
import {useMemo, useState} from 'react'

export const Route = createFileRoute('/monitoring/network-devices/paths')({
  component: NdmPaths,
})

interface NetworkPath {
  pathId?: string
  source?: string
  destination?: string
  hops?: unknown[]
  collectedAt?: string
}

function NdmPaths() {
  const [searchQuery, setSearchQuery] = useState('')

  const {data, isLoading} = useQuery({
    queryKey: ['ndm-paths'],
    queryFn: () => api.get<{paths?: NetworkPath[]}>('/v1/network-devices/paths?limit=100'),
  })

  const paths: NetworkPath[] = data?.paths ?? []

  const filtered = useMemo(() => {
    if (!searchQuery) return paths
    const q = searchQuery.toLowerCase()
    return paths.filter((p) =>
      p.source?.toLowerCase().includes(q) ||
      p.destination?.toLowerCase().includes(q)
    )
  }, [paths, searchQuery])

  if (isLoading) {
    return (
      <div className="flex items-center justify-center py-16">
        <div className="flex flex-col items-center gap-3">
          <Loader2 className="h-8 w-8 animate-spin text-muted-foreground" />
          <p className="text-muted-foreground text-sm">Loading paths...</p>
        </div>
      </div>
    )
  }

  if (paths.length === 0) {
    return (
      <Card className="border-dashed">
        <CardContent className="py-16 text-center">
          <div className="mx-auto mb-6 flex h-16 w-16 items-center justify-center rounded-2xl bg-gradient-to-br from-cyan-500/10 to-blue-500/10">
            <Wifi className="h-8 w-8 text-cyan-500" />
          </div>
          <h3 className="text-lg font-semibold mb-2">No path data</h3>
          <p className="text-muted-foreground max-w-sm mx-auto">
            Network path data will appear here once collected by the agent.
          </p>
        </CardContent>
      </Card>
    )
  }

  return (
    <div className="space-y-4">
      <div className="flex items-center gap-3">
        <div className="flex items-center gap-1.5 text-sm">
          <Wifi className="h-3.5 w-3.5 text-cyan-500" />
          <span className="font-semibold tabular-nums">{paths.length}</span>
          <span className="text-muted-foreground text-xs">paths</span>
        </div>
        <div className="h-4 w-px bg-border" />
        <div className="relative flex-1 max-w-md">
          <Search className="absolute left-2.5 top-2.5 h-4 w-4 text-muted-foreground" />
          <Input
            placeholder="Search by source or destination..."
            value={searchQuery}
            onChange={(e) => setSearchQuery(e.target.value)}
            className="pl-9"
          />
        </div>
      </div>

      {filtered.length === 0 ? (
        <div className="text-center py-12 text-muted-foreground">
          <p className="font-medium">No paths match your search</p>
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
                  <TableHead>Hops</TableHead>
                  <TableHead className="text-right pr-4">Collected</TableHead>
                </TableRow>
              </TableHeader>
              <TableBody>
                {filtered.map((p) => {
                  const hopCount = Array.isArray(p.hops) ? p.hops.length : 0
                  return (
                    <TableRow key={p.pathId} className="hover:bg-muted/50 transition-colors">
                      <TableCell className="pl-4 font-mono text-xs">{p.source}</TableCell>
                      <TableCell className="font-mono text-xs">{p.destination}</TableCell>
                      <TableCell>
                        <Badge variant="outline" className="text-xs tabular-nums">{hopCount} hops</Badge>
                      </TableCell>
                      <TableCell className="text-right pr-4 text-xs text-muted-foreground">{p.collectedAt}</TableCell>
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
