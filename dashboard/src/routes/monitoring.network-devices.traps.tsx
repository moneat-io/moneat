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
import {Badge, type BadgeProps} from '@/components/ui/badge'
import {SectionCard} from '@/components/ui/section-card'
import {EmptyState} from '@/components/ui/empty-state'
import {Table, TableBody, TableCell, TableHead, TableHeader, TableRow} from '@/components/ui/table'
import {Input} from '@/components/ui/input'
import {Tooltip, TooltipContent, TooltipProvider, TooltipTrigger} from '@/components/ui/tooltip'
import {AlertTriangle, Loader2, Search} from 'lucide-react'
import {useMemo, useState} from 'react'

export const Route = createFileRoute('/monitoring/network-devices/traps')({
  component: NdmTraps,
})

// Trap severity mapped onto the shared status language.
function severityVariant(severity?: string): BadgeProps['variant'] {
  switch ((severity ?? '').toLowerCase()) {
    case 'critical':
      return 'danger'
    case 'warning':
      return 'warning'
    case 'info':
      return 'info'
    default:
      return 'neutral'
  }
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
      <EmptyState
        icon={AlertTriangle}
        title="No traps received"
        description="SNMP trap data will appear here once received by the agent."
      />
    )
  }

  return (
    <div className="space-y-4">
      <div className="flex items-center gap-3">
        <div className="flex items-center gap-1.5 text-sm">
          <AlertTriangle className="h-3.5 w-3.5 text-muted-foreground" />
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
        <EmptyState
          icon={Search}
          title="No traps match your search"
          description="Try adjusting your search query."
        />
      ) : (
        <SectionCard title="Traps" icon={AlertTriangle} iconTone="warning" count={filtered.length} flushBody>
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
                      <Badge variant={severityVariant(t.severity)} size="sm">
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
        </SectionCard>
      )}
    </div>
  )
}
