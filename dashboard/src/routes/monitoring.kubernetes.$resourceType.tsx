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
import {Layers, Loader2, Search} from 'lucide-react'
import {useMemo, useState} from 'react'
import {cn} from '@/lib/utils'

export const Route = createFileRoute('/monitoring/kubernetes/$resourceType')({
  component: KubernetesResourceList,
})

const RESOURCE_TYPE_MAP: Record<string, string> = {
  nodes: 'Node',
  services: 'Service',
  deployments: 'Deployment',
  daemonsets: 'DaemonSet',
  replicasets: 'ReplicaSet',
}

interface KubernetesResource {
  uid?: string
  name?: string
  namespace?: string
  clusterName?: string
  status?: string
  collectedAt?: string
}

function statusColor(status: string) {
  switch (status) {
    case 'Running':
    case 'Active':
    case 'Ready':
      return 'bg-emerald-500/15 text-emerald-700 dark:text-emerald-300 border-emerald-500/20'
    case 'Pending':
    case 'NotReady':
      return 'bg-amber-500/15 text-amber-700 dark:text-amber-300 border-amber-500/20'
    case 'Failed':
    case 'Error':
      return 'bg-red-500/15 text-red-700 dark:text-red-300 border-red-500/20'
    default:
      return ''
  }
}

function KubernetesResourceList() {
  const {resourceType} = Route.useParams()
  const rt = resourceType ?? ''
  const k8sType = RESOURCE_TYPE_MAP[rt] || rt
  const title = rt.charAt(0).toUpperCase() + rt.slice(1)

  const [searchQuery, setSearchQuery] = useState('')

  const {data, isLoading} = useQuery({
    queryKey: ['k8s-resources', k8sType],
    queryFn: () => api.get<{resources?: KubernetesResource[]}>(`/v1/infra/k8s-resources?resource_type=${k8sType}&limit=100`),
    enabled: Boolean(k8sType),
  })

  const resources: KubernetesResource[] = data?.resources ?? []

  const filtered = useMemo(() => {
    if (!searchQuery) return resources
    const q = searchQuery.toLowerCase()
    return resources.filter((r) =>
      r.name?.toLowerCase().includes(q) ||
      r.namespace?.toLowerCase().includes(q) ||
      r.clusterName?.toLowerCase().includes(q) ||
      r.status?.toLowerCase().includes(q)
    )
  }, [resources, searchQuery])

  if (isLoading) {
    return (
      <div className="flex items-center justify-center py-16">
        <div className="flex flex-col items-center gap-3">
          <Loader2 className="h-8 w-8 animate-spin text-muted-foreground" />
          <p className="text-muted-foreground text-sm">Loading {title.toLowerCase()}...</p>
        </div>
      </div>
    )
  }

  if (resources.length === 0) {
    return (
      <Card className="border-dashed">
        <CardContent className="py-16 text-center">
          <div className="mx-auto mb-6 flex h-16 w-16 items-center justify-center rounded-2xl bg-gradient-to-br from-blue-500/10 to-cyan-500/10">
            <Layers className="h-8 w-8 text-blue-500" />
          </div>
          <h3 className="text-lg font-semibold mb-2">No {title.toLowerCase()} found</h3>
          <p className="text-muted-foreground max-w-sm mx-auto">
            Kubernetes {title.toLowerCase()} data will appear here once collected by the agent.
          </p>
        </CardContent>
      </Card>
    )
  }

  return (
    <div className="space-y-4">
      <div className="flex items-center gap-3">
        <div className="flex items-center gap-1.5 text-sm">
          <Layers className="h-3.5 w-3.5 text-blue-500" />
          <span className="font-semibold tabular-nums">{resources.length}</span>
          <span className="text-muted-foreground text-xs">{title.toLowerCase()}</span>
        </div>
        <div className="h-4 w-px bg-border" />
        <div className="relative flex-1 max-w-md">
          <Search className="absolute left-2.5 top-2.5 h-4 w-4 text-muted-foreground" />
          <Input
            placeholder={`Search ${title.toLowerCase()}...`}
            value={searchQuery}
            onChange={(e) => setSearchQuery(e.target.value)}
            className="pl-9"
          />
        </div>
      </div>

      {filtered.length === 0 ? (
        <div className="text-center py-12 text-muted-foreground">
          <p className="font-medium">No {title.toLowerCase()} match your search</p>
          <p className="text-sm mt-1">Try adjusting your search query.</p>
        </div>
      ) : (
        <Card className="overflow-hidden border-border/60 shadow-sm">
          <CardContent className="p-0">
            <Table>
              <TableHeader>
                <TableRow className="hover:bg-transparent bg-muted/30">
                  <TableHead className="pl-4">Name</TableHead>
                  <TableHead>Namespace</TableHead>
                  <TableHead>Cluster</TableHead>
                  <TableHead>Status</TableHead>
                  <TableHead className="text-right pr-4">Age</TableHead>
                </TableRow>
              </TableHeader>
              <TableBody>
                {filtered.map((r) => (
                  <TableRow key={r.uid || r.name} className="hover:bg-muted/50 transition-colors">
                    <TableCell className="pl-4">
                      <TooltipProvider delayDuration={300}>
                        <Tooltip>
                          <TooltipTrigger asChild>
                            <span className="font-mono text-xs truncate max-w-[280px] block">{r.name}</span>
                          </TooltipTrigger>
                          <TooltipContent side="top">
                            <p className="font-mono text-xs">{r.name}</p>
                          </TooltipContent>
                        </Tooltip>
                      </TooltipProvider>
                    </TableCell>
                    <TableCell>
                      <Badge variant="outline" className="text-xs font-normal">{r.namespace}</Badge>
                    </TableCell>
                    <TableCell className="text-sm">{r.clusterName}</TableCell>
                    <TableCell>
                      <Badge variant="secondary" className={cn('text-xs', statusColor(r.status ?? ''))}>
                        {r.status || 'Unknown'}
                      </Badge>
                    </TableCell>
                    <TableCell className="text-right pr-4 text-xs text-muted-foreground">
                      {r.collectedAt}
                    </TableCell>
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
