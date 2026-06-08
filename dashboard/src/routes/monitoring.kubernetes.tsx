// Moneat - observability platform
// Copyright (C) 2026 Moneat
//
// This program is free software: you can redistribute it and/or modify
// it under the terms of the GNU Affero General Public License as published by
// the Free Software Foundation, either version 3 of the License, or
// (at your option) any later version.

import {createFileRoute, Link, Outlet, useRouterState} from '@tanstack/react-router'
import {useQuery} from '@tanstack/react-query'
import {api} from '@/lib/api'
import {Input} from '@/components/ui/input'
import {Box, Copy, Globe, Layers, RefreshCw, Search, Server} from 'lucide-react'
import {cn} from '@/lib/utils'
import {useState} from 'react'
import {KubernetesPodsSearchContext, KubernetesResourceSearchContext} from './monitoring.kubernetes.context'

export const Route = createFileRoute('/monitoring/kubernetes')({
  component: KubernetesLayout,
})

const RESOURCE_K8S_MAP: Record<string, string> = {
  pods: 'Pod',
  nodes: 'Node',
  services: 'Service',
  deployments: 'Deployment',
  daemonsets: 'DaemonSet',
  replicasets: 'ReplicaSet',
}

const tabs = [
  {id: 'pods', label: 'Pods', href: '/monitoring/kubernetes', icon: Box},
  {id: 'nodes', label: 'Nodes', href: '/monitoring/kubernetes/nodes', icon: Server},
  {id: 'services', label: 'Services', href: '/monitoring/kubernetes/services', icon: Globe},
  {id: 'deployments', label: 'Deployments', href: '/monitoring/kubernetes/deployments', icon: Layers},
  {id: 'daemonsets', label: 'DaemonSets', href: '/monitoring/kubernetes/daemonsets', icon: RefreshCw},
  {id: 'replicasets', label: 'ReplicaSets', href: '/monitoring/kubernetes/replicasets', icon: Copy},
]

function KubernetesLayout() {
  const router = useRouterState()
  const currentPath = router.location.pathname
  const isPodsIndex = currentPath === '/monitoring/kubernetes' || currentPath === '/monitoring/kubernetes/'
  const resourcePath = currentPath.replace(/^\/monitoring\/kubernetes\/?/, '') || 'pods'
  const k8sType = RESOURCE_K8S_MAP[resourcePath] ?? 'Pod'
  const isResourceListPage = isPodsIndex || ['nodes', 'services', 'deployments', 'daemonsets', 'replicasets'].includes(resourcePath)

  const [podsSearchQuery, setPodsSearchQuery] = useState('')
  const [resourceSearchQuery, setResourceSearchQuery] = useState('')
  const searchQuery = isPodsIndex ? podsSearchQuery : resourceSearchQuery
  const setSearchQuery = isPodsIndex ? setPodsSearchQuery : setResourceSearchQuery

  const {data: podsData} = useQuery({
    queryKey: ['k8s-resources', 'Pod'],
    queryFn: () => api.get<{resources?: unknown[]}>('/v1/infra/k8s-resources?resource_type=Pod&limit=100'),
    enabled: isPodsIndex,
  })

  const {data: resourceData} = useQuery({
    queryKey: ['k8s-resources', k8sType],
    queryFn: () => api.get<{resources?: unknown[]}>(`/v1/infra/k8s-resources?resource_type=${k8sType}&limit=100`),
    enabled: isResourceListPage && !isPodsIndex,
  })

  const resourceCount = isPodsIndex ? (podsData?.resources?.length ?? 0) : (resourceData?.resources?.length ?? 0)
  const resourceLabel = resourcePath === 'daemonsets' ? 'DaemonSets' : resourcePath === 'replicasets' ? 'ReplicaSets' : resourcePath.charAt(0).toUpperCase() + resourcePath.slice(1)

  return (
    <KubernetesPodsSearchContext.Provider value={{searchQuery: podsSearchQuery, setSearchQuery: setPodsSearchQuery}}>
    <KubernetesResourceSearchContext.Provider value={{searchQuery: resourceSearchQuery, setSearchQuery: setResourceSearchQuery}}>
      <div className="container mx-auto px-4 py-2.5 space-y-4">
        <div className="border-b pb-2">
          <div className="flex flex-col gap-2 sm:flex-row sm:items-center sm:gap-2">
            <nav className="flex gap-0.5 overflow-x-auto pb-1 sm:flex-1 sm:min-w-0 sm:pb-0" aria-label="Kubernetes tabs">
          {tabs.map((tab) => {
            const isActive = tab.href === '/monitoring/kubernetes'
              ? isPodsIndex
              : currentPath.startsWith(tab.href)
            const Icon = tab.icon
            return (
              <Link key={tab.id} to={tab.href}
                className={cn(
                  'flex items-center gap-1.5 px-2.5 py-2 border-b-2 transition-all font-medium text-xs rounded-t-md whitespace-nowrap shrink-0',
                  isActive
                    ? 'border-primary text-primary bg-primary/5'
                    : 'border-transparent text-muted-foreground hover:text-foreground hover:bg-muted/50'
                )}>
                <Icon className="h-3.5 w-3.5 shrink-0" />
                {tab.label}
              </Link>
            )
          })}
            </nav>
            {isResourceListPage && (
              <div className="flex items-center gap-2 shrink-0">
                <div className="flex items-center gap-1.5 text-sm">
                  <Box className="h-3.5 w-3.5 text-muted-foreground shrink-0" />
                  <span className="font-semibold tabular-nums">{resourceCount}</span>
                  <span className="text-muted-foreground text-xs">{resourceLabel.toLowerCase()}</span>
                </div>
                <div className="h-4 w-px bg-border shrink-0" />
                <div className="relative w-full min-w-[200px] max-w-md ml-2">
                  <Search className="absolute left-2.5 top-2.5 h-4 w-4 text-muted-foreground shrink-0" />
                  <Input
                    placeholder="Search"
                    value={searchQuery}
                    onChange={(e) => setSearchQuery(e.target.value)}
                    className="pl-9"
                  />
                </div>
              </div>
            )}
          </div>
        </div>
        <Outlet />
      </div>
    </KubernetesResourceSearchContext.Provider>
    </KubernetesPodsSearchContext.Provider>
  )
}
