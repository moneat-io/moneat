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
import {Loader2, Package, Search, ShieldAlert} from 'lucide-react'
import {useMemo, useState} from 'react'

export const Route = createFileRoute('/monitoring/sbom')({
  component: SbomDashboard,
})

interface SbomPackage {
  packageName?: string
  packageVersion?: string
  packageType?: string
  host?: string
  imageName?: string
  cveIds?: string[]
}

function SbomDashboard() {
  const [searchQuery, setSearchQuery] = useState('')

  const {data, isLoading} = useQuery({
    queryKey: ['sbom-packages'],
    queryFn: () => api.get<{packages?: SbomPackage[]}>('/v1/infra/sbom?limit=100'),
  })

  const packages: SbomPackage[] = data?.packages ?? []

  const filtered = useMemo(() => {
    if (!searchQuery) return packages
    const q = searchQuery.toLowerCase()
    return packages.filter((p) =>
      p.packageName?.toLowerCase().includes(q) ||
      p.packageVersion?.toLowerCase().includes(q) ||
      p.packageType?.toLowerCase().includes(q) ||
      p.host?.toLowerCase().includes(q) ||
      p.imageName?.toLowerCase().includes(q)
    )
  }, [packages, searchQuery])

  const cveTotal = packages.reduce((sum: number, p) =>
    sum + (Array.isArray(p.cveIds) ? p.cveIds.length : 0), 0
  )

  return (
    <div className="container mx-auto px-4 py-4 space-y-4">
      {isLoading ? (
        <div className="flex items-center justify-center py-16">
          <div className="flex flex-col items-center gap-3">
            <Loader2 className="h-8 w-8 animate-spin text-muted-foreground" />
            <p className="text-muted-foreground text-sm">Loading packages...</p>
          </div>
        </div>
      ) : packages.length === 0 ? (
        <Card className="border-dashed">
          <CardContent className="py-16 text-center">
            <div className="mx-auto mb-6 flex h-16 w-16 items-center justify-center rounded-2xl bg-gradient-to-br from-amber-500/10 to-orange-500/10">
              <Package className="h-8 w-8 text-amber-500" />
            </div>
            <h3 className="text-lg font-semibold mb-2">No SBOM data collected</h3>
            <p className="text-muted-foreground max-w-sm mx-auto">
              Package inventory will appear here once collected by the agent.
            </p>
          </CardContent>
        </Card>
      ) : (
        <div className="space-y-4">
          <div className="flex items-center gap-3">
            <div className="flex items-center gap-1.5 text-sm">
              <Package className="h-3.5 w-3.5 text-amber-500" />
              <span className="font-semibold tabular-nums">{packages.length}</span>
              <span className="text-muted-foreground text-xs">packages</span>
            </div>
            {cveTotal > 0 && (
              <>
                <div className="h-4 w-px bg-border" />
                <div className="flex items-center gap-1.5 text-sm">
                  <ShieldAlert className="h-3.5 w-3.5 text-red-500" />
                  <span className="font-semibold tabular-nums text-red-600 dark:text-red-400">{cveTotal}</span>
                  <span className="text-muted-foreground text-xs">CVEs</span>
                </div>
              </>
            )}
            <div className="h-4 w-px bg-border" />
            <div className="relative flex-1 max-w-md">
              <Search className="absolute left-2.5 top-2.5 h-4 w-4 text-muted-foreground" />
              <Input
                placeholder="Search by package, version, type, or host..."
                value={searchQuery}
                onChange={(e) => setSearchQuery(e.target.value)}
                className="pl-9"
              />
            </div>
          </div>

          {filtered.length === 0 ? (
            <div className="text-center py-12 text-muted-foreground">
              <p className="font-medium">No packages match your search</p>
              <p className="text-sm mt-1">Try adjusting your search query.</p>
            </div>
          ) : (
            <Card className="overflow-hidden border-border/60 shadow-sm">
              <CardContent className="p-0">
                <Table>
                  <TableHeader>
                    <TableRow className="hover:bg-transparent bg-muted/30">
                      <TableHead className="pl-4">Package</TableHead>
                      <TableHead>Version</TableHead>
                      <TableHead>Type</TableHead>
                      <TableHead>Host</TableHead>
                      <TableHead>Image</TableHead>
                      <TableHead className="text-right pr-4">CVEs</TableHead>
                    </TableRow>
                  </TableHeader>
                  <TableBody>
                    {filtered.map((p, i: number) => {
                      const cveCount = Array.isArray(p.cveIds) ? p.cveIds.length : 0
                      return (
                        <TableRow key={i} className="hover:bg-muted/50 transition-colors">
                          <TableCell className="pl-4 font-medium">{p.packageName}</TableCell>
                          <TableCell className="font-mono text-xs">{p.packageVersion}</TableCell>
                          <TableCell>
                            <Badge variant="outline" className="text-xs">{p.packageType}</Badge>
                          </TableCell>
                          <TableCell>
                            <TooltipProvider delayDuration={300}>
                              <Tooltip>
                                <TooltipTrigger asChild>
                                  <span className="text-xs truncate max-w-[150px] block">{p.host}</span>
                                </TooltipTrigger>
                                {p.host && (
                                  <TooltipContent side="top">
                                    <p className="text-xs">{p.host}</p>
                                  </TooltipContent>
                                )}
                              </Tooltip>
                            </TooltipProvider>
                          </TableCell>
                          <TableCell>
                            <TooltipProvider delayDuration={300}>
                              <Tooltip>
                                <TooltipTrigger asChild>
                                  <span className="text-xs truncate max-w-[150px] block">{p.imageName}</span>
                                </TooltipTrigger>
                                {p.imageName && (
                                  <TooltipContent side="top">
                                    <p className="text-xs">{p.imageName}</p>
                                  </TooltipContent>
                                )}
                              </Tooltip>
                            </TooltipProvider>
                          </TableCell>
                          <TableCell className="text-right pr-4">
                            {cveCount > 0 ? (
                              <Badge variant="destructive" className="text-xs tabular-nums">
                                {cveCount} CVE{cveCount > 1 ? 's' : ''}
                              </Badge>
                            ) : (
                              <span className="text-muted-foreground text-xs">None</span>
                            )}
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
      )}
    </div>
  )
}
