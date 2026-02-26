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
import {BookOpen, Database, Loader2, Search} from 'lucide-react'
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

  const queries: DatabaseQuery[] = (data as {queries?: DatabaseQuery[]} | undefined)?.queries ?? []

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
      <div className="flex items-center justify-between">
        <div className="flex items-center gap-3">
          <div className="flex items-center justify-center h-10 w-10 rounded-lg bg-gradient-to-br from-emerald-500 to-teal-600">
            <Database className="h-5 w-5 text-white" />
          </div>
          <div>
            <h1 className="text-2xl font-bold tracking-tight">Database Monitoring</h1>
            <p className="text-muted-foreground mt-1">Query performance and active sessions</p>
          </div>
        </div>
        <a href="/docs/datadog-agent/database-monitoring" target="_blank" rel="noreferrer"
          className="inline-flex items-center gap-1.5 text-sm text-muted-foreground hover:text-foreground transition-colors">
          <BookOpen className="h-4 w-4" />
          View docs
        </a>
      </div>

      {isLoading ? (
        <div className="flex items-center justify-center py-16">
          <div className="flex flex-col items-center gap-3">
            <Loader2 className="h-8 w-8 animate-spin text-muted-foreground" />
            <p className="text-muted-foreground text-sm">Loading queries...</p>
          </div>
        </div>
      ) : queries.length === 0 ? (
        <Card className="border-dashed">
          <CardContent className="py-16 text-center">
            <div className="mx-auto mb-6 flex h-16 w-16 items-center justify-center rounded-2xl bg-gradient-to-br from-emerald-500/10 to-teal-500/10">
              <Database className="h-8 w-8 text-emerald-500" />
            </div>
            <h3 className="text-lg font-semibold mb-2">No queries recorded yet</h3>
            <p className="text-muted-foreground max-w-sm mx-auto">
              Database query data will appear here once collected by the agent.
            </p>
          </CardContent>
        </Card>
      ) : (
        <div className="space-y-4">
          <div className="flex items-center gap-3">
            <div className="flex items-center gap-1.5 text-sm">
              <Database className="h-3.5 w-3.5 text-emerald-500" />
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
            <div className="text-center py-12 text-muted-foreground">
              <p className="font-medium">No queries match your search</p>
              <p className="text-sm mt-1">Try adjusting your search query.</p>
            </div>
          ) : (
            <Card className="overflow-hidden border-border/60 shadow-sm">
              <CardContent className="p-0">
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
                                    {q.statement}
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
                          <Badge variant="outline" className="text-xs font-normal">{q.dbName}</Badge>
                        </TableCell>
                        <TableCell>
                          <Badge variant="outline" className="text-xs tabular-nums font-mono">
                            {((q.durationNs ?? 0) / 1e6).toFixed(1)}ms
                          </Badge>
                        </TableCell>
                        <TableCell className="text-right pr-4 text-xs text-muted-foreground">{q.timestamp}</TableCell>
                      </TableRow>
                    ))}
                  </TableBody>
                </Table>
              </CardContent>
            </Card>
          )}
        </div>
      )}
    </div>
  )
}
