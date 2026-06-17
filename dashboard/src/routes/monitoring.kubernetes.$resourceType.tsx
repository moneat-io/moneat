// Moneat - observability platform
// Copyright (C) 2026 Moneat
//
// This program is free software: you can redistribute it and/or modify
// it under the terms of the GNU Affero General Public License as published by
// the Free Software Foundation, either version 3 of the License, or
// (at your option) any later version.

import {createFileRoute} from '@tanstack/react-router'
import {useContext} from 'react'
import {useQuery} from '@tanstack/react-query'
import {api} from '@/lib/api'
import {KubernetesResourceSearchContext} from './monitoring.kubernetes.context'
import {Badge, type BadgeProps} from '@/components/ui/badge'
import {SectionCard} from '@/components/ui/section-card'
import {EmptyState} from '@/components/ui/empty-state'
import {Table, TableBody, TableCell, TableHead, TableHeader, TableRow} from '@/components/ui/table'
import {Tooltip, TooltipContent, TooltipProvider, TooltipTrigger} from '@/components/ui/tooltip'
import {Layers, Loader2} from 'lucide-react'
import {useMemo} from 'react'

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

// Resource state mapped onto the shared status language.
function statusVariant(status: string): BadgeProps['variant'] {
  switch (status) {
    case 'Running':
    case 'Active':
    case 'Ready':
      return 'success'
    case 'Pending':
    case 'NotReady':
      return 'warning'
    case 'Failed':
    case 'Error':
      return 'danger'
    default:
      return 'neutral'
  }
}

function KubernetesResourceList() {
  const {resourceType} = Route.useParams()
  const {searchQuery} = useContext(KubernetesResourceSearchContext)
  const rt = resourceType ?? ''
  const k8sType = RESOURCE_TYPE_MAP[rt] || rt
  const title = rt.charAt(0).toUpperCase() + rt.slice(1)

  const {data, isLoading} = useQuery({
    queryKey: ['k8s-resources', k8sType],
    queryFn: () => api.get<{resources?: KubernetesResource[]}>(`/v1/infra/k8s-resources?resource_type=${k8sType}&limit=100`),
    enabled: Boolean(k8sType),
  })

  const resources: KubernetesResource[] = useMemo(() => data?.resources ?? [], [data?.resources])

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
      <EmptyState
        icon={Layers}
        title={`No ${title.toLowerCase()} found`}
        description={`Kubernetes ${title.toLowerCase()} data will appear here once collected by the agent.`}
      />
    )
  }

  return (
    <div className="space-y-4">
      {filtered.length === 0 ? (
        <EmptyState
          icon={Layers}
          title={`No ${title.toLowerCase()} match your search`}
          description="Try adjusting your search query."
        />
      ) : (
        <SectionCard title={title} icon={Layers} iconTone="info" count={filtered.length} flushBody>
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
                      <Badge variant="neutral" size="sm">{r.namespace}</Badge>
                    </TableCell>
                    <TableCell className="text-sm">{r.clusterName}</TableCell>
                    <TableCell>
                      <Badge variant={statusVariant(r.status ?? '')} size="sm">
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
        </SectionCard>
      )}
    </div>
  )
}
