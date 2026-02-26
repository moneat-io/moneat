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
import {Card, CardContent} from '@/components/ui/card'
import {Input} from '@/components/ui/input'
import {Tooltip, TooltipContent, TooltipProvider, TooltipTrigger} from '@/components/ui/tooltip'
import {
  AlertCircle,
  AlertTriangle,
  CheckCircle2,
  ChevronDown,
  ChevronRight,
  HeartPulse,
  Loader2,
  Search,
  Server,
  ShieldCheck,
} from 'lucide-react'
import {useState, useMemo} from 'react'
import {cn} from '@/lib/utils'
import {formatRelativeTime} from '@/lib/utils'

type StatusFilter = 'all' | 'ok' | 'warning' | 'critical' | 'unknown'

function statusIcon(status: string) {
  switch (status) {
    case 'ok':
      return <CheckCircle2 className="h-3.5 w-3.5" />
    case 'warning':
      return <AlertTriangle className="h-3.5 w-3.5" />
    case 'critical':
      return <AlertCircle className="h-3.5 w-3.5" />
    default:
      return <HeartPulse className="h-3.5 w-3.5" />
  }
}

function statusBadgeClasses(status: string): string {
  switch (status) {
    case 'ok':
      return 'bg-emerald-500/15 text-emerald-700 dark:text-emerald-300 border-emerald-500/20'
    case 'warning':
      return 'bg-yellow-500/15 text-yellow-700 dark:text-yellow-300 border-yellow-500/20'
    case 'critical':
      return 'bg-red-500/15 text-red-700 dark:text-red-300 border-red-500/20'
    default:
      return 'bg-muted text-muted-foreground border-border'
  }
}

function statusAccentColor(status: string): string {
  switch (status) {
    case 'ok':
      return 'border-l-emerald-500'
    case 'warning':
      return 'border-l-yellow-500'
    case 'critical':
      return 'border-l-red-500'
    default:
      return 'border-l-muted-foreground'
  }
}

function filterDot(status: StatusFilter): string {
  switch (status) {
    case 'ok':
      return 'bg-emerald-500'
    case 'warning':
      return 'bg-yellow-500'
    case 'critical':
      return 'bg-red-500'
    case 'unknown':
      return 'bg-gray-400'
    default:
      return 'bg-blue-500'
  }
}

export function ServiceCheckList() {
  const [searchQuery, setSearchQuery] = useState('')
  const [statusFilter, setStatusFilter] = useState<StatusFilter>('all')
  const [expandedId, setExpandedId] = useState<string | null>(null)

  const {data, isLoading} = useQuery({
    queryKey: ['serviceChecks', searchQuery],
    queryFn: () =>
      api.getServiceChecks({
        host: undefined,
        limit: 50,
      }),
    enabled: api.isAuthenticated(),
    refetchInterval: 15000,
  })

  const checks = data?.serviceChecks ?? []

  const filtered = useMemo(() => {
    let result = checks
    if (searchQuery) {
      const q = searchQuery.toLowerCase()
      result = result.filter(
        (c) =>
          c.checkName?.toLowerCase().includes(q) ||
          c.host?.toLowerCase().includes(q) ||
          c.message?.toLowerCase().includes(q)
      )
    }
    if (statusFilter !== 'all') {
      result = result.filter((c) => c.status === statusFilter)
    }
    return result
  }, [checks, searchQuery, statusFilter])

  const okCount = checks.filter((c) => c.status === 'ok').length
  const warningCount = checks.filter((c) => c.status === 'warning').length
  const criticalCount = checks.filter((c) => c.status === 'critical').length
  const unknownCount = checks.filter((c) => c.status !== 'ok' && c.status !== 'warning' && c.status !== 'critical').length
  const uniqueHosts = new Set(checks.map((c) => c.host).filter(Boolean)).size
  const uniqueChecks = new Set(checks.map((c) => c.checkName).filter(Boolean)).size

  const filterCounts: Record<StatusFilter, number> = {
    all: checks.length,
    ok: okCount,
    warning: warningCount,
    critical: criticalCount,
    unknown: unknownCount,
  }

  const filters: StatusFilter[] = ['all', 'ok', 'warning', 'critical']
  if (unknownCount > 0) filters.push('unknown')

  return (
    <div className="space-y-3">
      <div className="flex flex-col sm:flex-row items-start sm:items-center gap-3">
        {!isLoading && checks.length > 0 && (
          <div className="flex items-center gap-3 text-sm flex-wrap">
            <div className="flex items-center gap-1.5">
              <ShieldCheck className="h-3.5 w-3.5 text-blue-500" />
              <span className="font-semibold tabular-nums">{uniqueChecks}</span>
              <span className="text-muted-foreground text-xs">checks</span>
            </div>
            <div className="h-4 w-px bg-border" />
            <div className="flex items-center gap-1.5">
              <CheckCircle2 className="h-3.5 w-3.5 text-emerald-500" />
              <span className="font-semibold tabular-nums">{okCount}</span>
              <span className="text-muted-foreground text-xs">passing</span>
            </div>
            <div className="h-4 w-px bg-border" />
            <div className="flex items-center gap-1.5">
              <AlertTriangle className="h-3.5 w-3.5 text-yellow-500" />
              <span className="font-semibold tabular-nums">{warningCount}</span>
              <span className="text-muted-foreground text-xs">warning</span>
            </div>
            <div className="h-4 w-px bg-border" />
            <div className="flex items-center gap-1.5">
              <AlertCircle className="h-3.5 w-3.5 text-red-500" />
              <span className="font-semibold tabular-nums">{criticalCount}</span>
              <span className="text-muted-foreground text-xs">critical</span>
            </div>
            <div className="h-4 w-px bg-border" />
            <div className="flex items-center gap-1.5">
              <Server className="h-3.5 w-3.5 text-sky-500" />
              <span className="font-semibold tabular-nums">{uniqueHosts}</span>
              <span className="text-muted-foreground text-xs">{uniqueHosts === 1 ? 'host' : 'hosts'}</span>
            </div>
          </div>
        )}
      </div>

      <div className="flex flex-col sm:flex-row items-start sm:items-center gap-3">
        <div className="relative flex-1 max-w-md">
          <Search className="absolute left-2.5 top-2.5 h-4 w-4 text-muted-foreground" />
          <Input
            placeholder="Search by check name, host, or message..."
            value={searchQuery}
            onChange={(e) => setSearchQuery(e.target.value)}
            className="pl-9"
          />
        </div>
        <div className="flex items-center gap-1 rounded-lg border bg-background p-1">
          {filters.map((f) => (
            <button
              key={f}
              onClick={() => setStatusFilter(f)}
              className={cn(
                'inline-flex items-center gap-1.5 rounded-md px-3 py-1.5 text-xs font-medium transition-colors',
                statusFilter === f
                  ? 'bg-secondary text-secondary-foreground shadow-sm'
                  : 'text-muted-foreground hover:text-foreground hover:bg-muted/50'
              )}
            >
              <div className={cn('h-1.5 w-1.5 rounded-full', filterDot(f))} />
              <span className="capitalize">{f}</span>
              <span className="ml-0.5 text-[10px] text-muted-foreground">{filterCounts[f]}</span>
            </button>
          ))}
        </div>
      </div>

      {isLoading ? (
        <div className="flex items-center justify-center py-16">
          <div className="flex flex-col items-center gap-3">
            <Loader2 className="h-8 w-8 animate-spin text-muted-foreground" />
            <p className="text-muted-foreground text-sm">Loading service checks...</p>
          </div>
        </div>
      ) : checks.length === 0 ? (
        <Card className="border-dashed">
          <CardContent className="py-16 text-center">
            <div className="mx-auto mb-6 flex h-20 w-20 items-center justify-center rounded-2xl bg-gradient-to-br from-blue-500/10 to-emerald-500/10">
              <ShieldCheck className="h-10 w-10 text-blue-500" />
            </div>
            <h3 className="text-xl font-semibold mb-2">No service checks found</h3>
            <p className="text-muted-foreground mb-2 max-w-sm mx-auto">
              Service checks will appear when an agent reports health checks.
            </p>
          </CardContent>
        </Card>
      ) : filtered.length === 0 ? (
        <div className="text-center py-12 text-muted-foreground">
          <p className="font-medium">No service checks match your filters</p>
          <p className="text-sm mt-1">Try adjusting your search or status filter.</p>
        </div>
      ) : (
        <Card className="overflow-hidden border-border/60 shadow-sm">
          <CardContent className="p-0">
            <Table className="min-w-[750px]">
              <TableHeader>
                <TableRow className="hover:bg-transparent bg-muted/30">
                  <TableHead className="pl-4 w-8" />
                  <TableHead>Check Name</TableHead>
                  <TableHead>Status</TableHead>
                  <TableHead>Host</TableHead>
                  <TableHead>Message</TableHead>
                  <TableHead className="text-right pr-4">Time</TableHead>
                </TableRow>
              </TableHeader>
              <TableBody>
                {filtered.map((check: DdServiceCheckResponse) => {
                  const isExpanded = expandedId === check.checkId
                  const hasTags = check.tags && Object.keys(check.tags).length > 0
                  const hasDetails = (check.message && check.message.length > 60) || hasTags
                  const tagEntries = hasTags ? Object.entries(check.tags) : []

                  return (
                    <TableRow
                      key={check.checkId}
                      className={cn(
                        'group transition-colors border-l-2',
                        statusAccentColor(check.status),
                        hasDetails ? 'cursor-pointer' : '',
                        isExpanded ? 'bg-muted/40' : 'hover:bg-muted/50'
                      )}
                      onClick={() => hasDetails && setExpandedId(isExpanded ? null : check.checkId)}
                    >
                      <TableCell className="pl-4 pr-0 w-8">
                        {hasDetails ? (
                          isExpanded ? (
                            <ChevronDown className="h-3.5 w-3.5 text-muted-foreground" />
                          ) : (
                            <ChevronRight className="h-3.5 w-3.5 text-muted-foreground opacity-0 group-hover:opacity-100 transition-opacity" />
                          )
                        ) : null}
                      </TableCell>

                      <TableCell>
                        <TooltipProvider delayDuration={300}>
                          <Tooltip>
                            <TooltipTrigger asChild>
                              <span className="font-mono text-sm font-medium truncate max-w-[220px] block">
                                {check.checkName}
                              </span>
                            </TooltipTrigger>
                            <TooltipContent side="top">
                              <p className="font-mono text-xs">{check.checkName}</p>
                            </TooltipContent>
                          </Tooltip>
                        </TooltipProvider>
                      </TableCell>

                      <TableCell>
                        <Badge
                          variant="secondary"
                          className={cn('text-[11px] gap-1.5 font-medium', statusBadgeClasses(check.status))}
                        >
                          {statusIcon(check.status)}
                          {check.status}
                        </Badge>
                      </TableCell>

                      <TableCell>
                        <span className="font-mono text-xs">{check.host || '—'}</span>
                      </TableCell>

                      <TableCell className="max-w-xs">
                        {!isExpanded ? (
                          <p className="text-xs text-muted-foreground truncate max-w-[250px]">
                            {check.message || '—'}
                          </p>
                        ) : (
                          <div className="space-y-2">
                            <p className="text-xs text-muted-foreground whitespace-pre-wrap break-words max-w-md">
                              {check.message || '—'}
                            </p>
                            {tagEntries.length > 0 && (
                              <div className="flex flex-wrap gap-1.5 pt-0.5">
                                {tagEntries.map(([k, v]) => (
                                  <span
                                    key={k}
                                    className="inline-flex items-center rounded-md bg-muted/80 px-2 py-0.5 text-[11px] font-mono text-muted-foreground border border-border/40"
                                  >
                                    <span className="text-foreground/60">{k}:</span>
                                    <span className="ml-0.5">{v}</span>
                                  </span>
                                ))}
                              </div>
                            )}
                          </div>
                        )}
                      </TableCell>

                      <TableCell className="text-right pr-4 text-xs text-muted-foreground">
                        {formatRelativeTime(check.timestamp)}
                      </TableCell>
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
