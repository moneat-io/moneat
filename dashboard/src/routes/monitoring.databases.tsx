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
import {SectionCard} from '@/components/ui/section-card'
import {EmptyState} from '@/components/ui/empty-state'
import {Table, TableBody, TableCell, TableHead, TableHeader, TableRow} from '@/components/ui/table'
import {Input} from '@/components/ui/input'
import {Tooltip, TooltipContent, TooltipProvider, TooltipTrigger} from '@/components/ui/tooltip'
import {Database, Loader2, Search} from 'lucide-react'
import SyntaxHighlighter from 'react-syntax-highlighter'
import {atomOneDark} from 'react-syntax-highlighter/dist/esm/styles/hljs'
import {useMemo, useState} from 'react'

export const Route = createFileRoute('/monitoring/databases')({
  component: DatabaseMonitoring,
})

interface DatabaseQuery {
  statement?: string
  dbHost?: string
  dbName?: string
  durationNs?: number
  timestamp?: string
}

function DatabaseMonitoring() {
  const [searchQuery, setSearchQuery] = useState('')

  const {data, isLoading} = useQuery({
    queryKey: ['dbm-queries'],
    queryFn: () => api.get('/v1/infra/dbm/queries?limit=50'),
  })

  const queries: DatabaseQuery[] = useMemo(
    () => (data as {queries?: DatabaseQuery[]} | undefined)?.queries ?? [],
    [data],
  )

  const filtered = useMemo(() => {
    if (!searchQuery) return queries
    const q = searchQuery.toLowerCase()
    return queries.filter((qr) =>
      qr.statement?.toLowerCase().includes(q) ||
      qr.dbHost?.toLowerCase().includes(q) ||
      qr.dbName?.toLowerCase().includes(q)
    )
  }, [queries, searchQuery])

  return (
    <div className="container mx-auto px-4 py-4 space-y-4">
      {isLoading ? (
        <div className="flex items-center justify-center py-16">
          <div className="flex flex-col items-center gap-3">
            <Loader2 className="h-8 w-8 animate-spin text-muted-foreground" />
            <p className="text-muted-foreground text-sm">Loading queries...</p>
          </div>
        </div>
      ) : queries.length === 0 ? (
        <EmptyState
          icon={Database}
          title="No queries recorded yet"
          description="Database query data will appear here once collected by the agent."
        />
      ) : (
        <div className="space-y-4">
          <div className="flex items-center gap-3">
            <div className="flex items-center gap-1.5 text-sm">
              <Database className="h-3.5 w-3.5 text-muted-foreground" />
              <span className="font-semibold tabular-nums">{queries.length}</span>
              <span className="text-muted-foreground text-xs">queries</span>
            </div>
            <div className="h-4 w-px bg-border" />
            <div className="relative flex-1 max-w-md">
              <Search className="absolute left-2.5 top-2.5 h-4 w-4 text-muted-foreground" />
              <Input
                placeholder="Search by query, host, or database..."
                value={searchQuery}
                onChange={(e) => setSearchQuery(e.target.value)}
                className="pl-9"
              />
            </div>
          </div>

          {filtered.length === 0 ? (
            <EmptyState
              icon={Search}
              title="No queries match your search"
              description="Try adjusting your search query."
            />
          ) : (
            <SectionCard title="Queries" icon={Database} iconTone="muted" count={filtered.length} flushBody>
                <Table>
                  <TableHeader>
                    <TableRow className="hover:bg-transparent bg-muted/30">
                      <TableHead className="pl-4 min-w-[300px]">Query</TableHead>
                      <TableHead>Host</TableHead>
                      <TableHead>Database</TableHead>
                      <TableHead>Duration</TableHead>
                      <TableHead className="text-right pr-4">Timestamp</TableHead>
                    </TableRow>
                  </TableHeader>
                  <TableBody>
                    {filtered.map((q, i: number) => (
                      <TableRow key={i} className="hover:bg-muted/50 transition-colors">
                        <TableCell className="pl-4 max-w-md">
                          <TooltipProvider delayDuration={300}>
                            <Tooltip>
                              <TooltipTrigger asChild>
                                <div className="max-w-md">
                                  <SyntaxHighlighter
                                    language="sql"
                                    style={atomOneDark}
                                    customStyle={{
                                      margin: 0,
                                      padding: '4px 8px',
                                      borderRadius: '6px',
                                      fontSize: '0.7rem',
                                      whiteSpace: 'pre-wrap',
                                      wordBreak: 'break-all',
                                    }}
                                    wrapLongLines
                                  >
                                    {String(q.statement ?? '')}
                                  </SyntaxHighlighter>
                                </div>
                              </TooltipTrigger>
                              <TooltipContent side="bottom" className="max-w-lg">
                                <pre className="text-xs font-mono whitespace-pre-wrap">{q.statement}</pre>
                              </TooltipContent>
                            </Tooltip>
                          </TooltipProvider>
                        </TableCell>
                        <TableCell className="text-sm">{q.dbHost}</TableCell>
                        <TableCell>
                          <Badge variant="neutral" size="sm">{q.dbName}</Badge>
                        </TableCell>
                        <TableCell>
                          <Badge variant="neutral" size="sm" className="tabular-nums font-mono">
                            {((q.durationNs ?? 0) / 1e6).toFixed(1)}ms
                          </Badge>
                        </TableCell>
                        <TableCell className="text-right pr-4 text-xs text-muted-foreground">{q.timestamp}</TableCell>
                      </TableRow>
                    ))}
                  </TableBody>
                </Table>
            </SectionCard>
          )}
        </div>
      )}
    </div>
  )
}
