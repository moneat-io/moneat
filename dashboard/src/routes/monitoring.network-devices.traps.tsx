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
import {Tooltip, TooltipContent, TooltipProvider, TooltipTrigger} from '@/components/ui/tooltip'
import {AlertTriangle, Loader2, Search} from 'lucide-react'
import {cn} from '@/lib/utils'
import {useMemo, useState} from 'react'

export const Route = createFileRoute('/monitoring/network-devices/traps')({
  component: NdmTraps,
})

const severityColors: Record<string, string> = {
  critical: 'bg-red-500/15 text-red-700 dark:text-red-300 border-red-500/20',
  warning: 'bg-amber-500/15 text-amber-700 dark:text-amber-300 border-amber-500/20',
  info: 'bg-blue-500/15 text-blue-700 dark:text-blue-300 border-blue-500/20',
}

interface NetworkTrap {
  trapId?: string
  deviceIp?: string
  oid?: string
  severity?: string
  message?: string
  receivedAt?: string
}

function NdmTraps() {
  const [searchQuery, setSearchQuery] = useState('')

  const {data, isLoading} = useQuery({
    queryKey: ['ndm-traps'],
    queryFn: () => api.get<{traps?: NetworkTrap[]}>('/v1/network-devices/traps?limit=100'),
  })

  const traps: NetworkTrap[] = data?.traps ?? []

  const filtered = useMemo(() => {
    if (!searchQuery) return traps
    const q = searchQuery.toLowerCase()
    return traps.filter((t) =>
      t.deviceIp?.toLowerCase().includes(q) ||
      t.oid?.toLowerCase().includes(q) ||
      t.severity?.toLowerCase().includes(q) ||
      t.message?.toLowerCase().includes(q)
    )
  }, [traps, searchQuery])

  if (isLoading) {
    return (
      <div className="flex items-center justify-center py-16">
        <div className="flex flex-col items-center gap-3">
          <Loader2 className="h-8 w-8 animate-spin text-muted-foreground" />
          <p className="text-muted-foreground text-sm">Loading traps...</p>
        </div>
      </div>
    )
  }

  if (traps.length === 0) {
    return (
      <Card className="border-dashed">
        <CardContent className="py-16 text-center">
          <div className="mx-auto mb-6 flex h-16 w-16 items-center justify-center rounded-2xl bg-gradient-to-br from-amber-500/10 to-orange-500/10">
            <AlertTriangle className="h-8 w-8 text-amber-500" />
          </div>
          <h3 className="text-lg font-semibold mb-2">No traps received</h3>
          <p className="text-muted-foreground max-w-sm mx-auto">
            SNMP trap data will appear here once received by the agent.
          </p>
        </CardContent>
      </Card>
    )
  }

  return (
    <div className="space-y-4">
      <div className="flex items-center gap-3">
        <div className="flex items-center gap-1.5 text-sm">
          <AlertTriangle className="h-3.5 w-3.5 text-amber-500" />
          <span className="font-semibold tabular-nums">{traps.length}</span>
          <span className="text-muted-foreground text-xs">traps</span>
        </div>
        <div className="h-4 w-px bg-border" />
        <div className="relative flex-1 max-w-md">
          <Search className="absolute left-2.5 top-2.5 h-4 w-4 text-muted-foreground" />
          <Input
            placeholder="Search by IP, OID, severity, or message..."
            value={searchQuery}
            onChange={(e) => setSearchQuery(e.target.value)}
            className="pl-9"
          />
        </div>
      </div>

      {filtered.length === 0 ? (
        <div className="text-center py-12 text-muted-foreground">
          <p className="font-medium">No traps match your search</p>
          <p className="text-sm mt-1">Try adjusting your search query.</p>
        </div>
      ) : (
        <Card className="overflow-hidden border-border/60">
          <CardContent className="p-0">
            <Table>
              <TableHeader>
                <TableRow className="hover:bg-transparent bg-muted/30">
                  <TableHead className="pl-4">Device IP</TableHead>
                  <TableHead>OID</TableHead>
                  <TableHead>Severity</TableHead>
                  <TableHead>Message</TableHead>
                  <TableHead className="text-right pr-4">Received</TableHead>
                </TableRow>
              </TableHeader>
              <TableBody>
                {filtered.map((t) => (
                  <TableRow key={t.trapId} className="hover:bg-muted/50 transition-colors">
                    <TableCell className="pl-4 font-mono text-xs">{t.deviceIp}</TableCell>
                    <TableCell className="font-mono text-xs">{t.oid}</TableCell>
                    <TableCell>
                      <Badge
                        variant="secondary"
                        className={cn('text-xs', severityColors[t.severity ?? ''] || '')}
                      >
                        {t.severity}
                      </Badge>
                    </TableCell>
                    <TableCell>
                      <TooltipProvider delayDuration={300}>
                        <Tooltip>
                          <TooltipTrigger asChild>
                            <span className="text-sm truncate max-w-[300px] block">{t.message}</span>
                          </TooltipTrigger>
                          {t.message && (
                            <TooltipContent side="top" className="max-w-md">
                              <p className="text-xs">{t.message}</p>
                            </TooltipContent>
                          )}
                        </Tooltip>
                      </TooltipProvider>
                    </TableCell>
                    <TableCell className="text-right pr-4 text-xs text-muted-foreground">{t.receivedAt}</TableCell>
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
