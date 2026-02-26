// Moneat - observability platform
// Copyright (C) 2026 Moneat
//
// This program is free software: you can redistribute it and/or modify
// it under the terms of the GNU Affero General Public License as published by
// the Free Software Foundation, either version 3 of the License, or
// (at your option) any later version.

import {createFileRoute, Link, Outlet, useRouterState} from '@tanstack/react-router'
import {BookOpen, Box, Copy, Globe, Layers, RefreshCw, Server, Ship} from 'lucide-react'
import {cn} from '@/lib/utils'

export const Route = createFileRoute('/monitoring/kubernetes')({
  component: KubernetesLayout,
})

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

  return (
    <div className="container mx-auto px-4 py-4 space-y-4">
      <div className="flex items-center justify-between">
        <div className="flex items-center gap-3">
          <div className="flex items-center justify-center h-10 w-10 rounded-lg bg-gradient-to-br from-blue-600 to-cyan-600">
            <Ship className="h-5 w-5 text-white" />
          </div>
          <div>
            <h1 className="text-2xl font-bold tracking-tight">Kubernetes</h1>
            <p className="text-muted-foreground mt-1">Cluster resources and workloads</p>
          </div>
        </div>
        <a href="/docs/datadog-agent/kubernetes" target="_blank" rel="noreferrer"
          className="inline-flex items-center gap-1.5 text-sm text-muted-foreground hover:text-foreground transition-colors">
          <BookOpen className="h-4 w-4" />
          View docs
        </a>
      </div>
      <div className="border-b">
        <nav className="flex gap-1">
          {tabs.map((tab) => {
            const isActive = tab.href === '/monitoring/kubernetes'
              ? currentPath === '/monitoring/kubernetes' || currentPath === '/monitoring/kubernetes/'
              : currentPath.startsWith(tab.href)
            const Icon = tab.icon
            return (
              <Link key={tab.id} to={tab.href}
                className={cn(
                  'flex items-center gap-2 px-3 py-2.5 border-b-2 transition-all font-medium text-sm rounded-t-md',
                  isActive
                    ? 'border-primary text-primary bg-primary/5'
                    : 'border-transparent text-muted-foreground hover:text-foreground hover:bg-muted/50'
                )}>
                <Icon className="h-4 w-4" />
                {tab.label}
              </Link>
            )
          })}
        </nav>
      </div>
      <Outlet />
    </div>
  )
}
