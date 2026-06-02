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
import {Badge} from '@/components/ui/badge'
import {SectionCard} from '@/components/ui/section-card'
import {EmptyState} from '@/components/ui/empty-state'
import {Table, TableBody, TableCell, TableHead, TableHeader, TableRow} from '@/components/ui/table'
import {Input} from '@/components/ui/input'
import {AlertTriangle, ArrowRightLeft, Loader2, Router, Search, Wifi} from 'lucide-react'
import {cn} from '@/lib/utils'
import {useMemo, useState} from 'react'

export const Route = createFileRoute('/monitoring/network-devices')({
  component: NetworkDevicesLayout,
})

const tabs = [
  {id: 'devices', label: 'Devices', href: '/monitoring/network-devices', icon: Router},
  {id: 'traps', label: 'Traps', href: '/monitoring/network-devices/traps', icon: AlertTriangle},
  {id: 'flows', label: 'Flows', href: '/monitoring/network-devices/flows', icon: ArrowRightLeft},
  {id: 'paths', label: 'Paths', href: '/monitoring/network-devices/paths', icon: Wifi},
]

interface NetworkDevice {
  deviceId?: string
  hostname?: string
  ipAddress?: string
  vendor?: string
  model?: string
  status?: string
  reachability?: string
}

function NetworkDevicesLayout() {
  const router = useRouterState()
  const currentPath = router.location.pathname
  const [searchQuery, setSearchQuery] = useState('')

  const isIndexPage = currentPath === '/monitoring/network-devices' || currentPath === '/monitoring/network-devices/'

  const {data, isLoading} = useQuery({
    queryKey: ['ndm-devices'],
    queryFn: () => api.get<{devices?: NetworkDevice[]}>('/v1/network-devices?limit=100'),
    enabled: isIndexPage,
  })

  const devices: NetworkDevice[] = data?.devices ?? []

  const filtered = useMemo(() => {
    if (!searchQuery) return devices
    const q = searchQuery.toLowerCase()
    return devices.filter((d) =>
      d.hostname?.toLowerCase().includes(q) ||
      d.deviceId?.toLowerCase().includes(q) ||
      d.ipAddress?.toLowerCase().includes(q) ||
      d.vendor?.toLowerCase().includes(q) ||
      d.model?.toLowerCase().includes(q)
    )
  }, [devices, searchQuery])

  return (
    <div className="container mx-auto px-4 py-4 space-y-4">
      <div className="border-b">
        <nav className="flex gap-1">
          {tabs.map((tab) => {
            const isActive = tab.href === '/monitoring/network-devices'
              ? isIndexPage
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

      {isIndexPage ? (
        isLoading ? (
          <div className="flex items-center justify-center py-16">
            <div className="flex flex-col items-center gap-3">
              <Loader2 className="h-8 w-8 animate-spin text-muted-foreground" />
              <p className="text-muted-foreground text-sm">Loading devices...</p>
            </div>
          </div>
        ) : devices.length === 0 ? (
          <EmptyState
            icon={Router}
            title="No network devices found"
            description="SNMP device data will appear here once collected by the agent."
          />
        ) : (
          <div className="space-y-4">
            <div className="flex items-center gap-3">
              <div className="flex items-center gap-1.5 text-sm">
                <Router className="h-3.5 w-3.5 text-muted-foreground" />
                <span className="font-semibold tabular-nums">{devices.length}</span>
                <span className="text-muted-foreground text-xs">devices</span>
              </div>
              <div className="h-4 w-px bg-border" />
              <div className="relative flex-1 max-w-md">
                <Search className="absolute left-2.5 top-2.5 h-4 w-4 text-muted-foreground" />
                <Input
                  placeholder="Search by hostname, IP, vendor, or model..."
                  value={searchQuery}
                  onChange={(e) => setSearchQuery(e.target.value)}
                  className="pl-9"
                />
              </div>
            </div>

            {filtered.length === 0 ? (
              <EmptyState
                icon={Search}
                title="No devices match your search"
                description="Try adjusting your search query."
              />
            ) : (
              <SectionCard title="Devices" icon={Router} iconTone="info" count={filtered.length} flushBody>
                  <Table>
                    <TableHeader>
                      <TableRow className="hover:bg-transparent bg-muted/30">
                        <TableHead className="pl-4">Hostname</TableHead>
                        <TableHead>IP Address</TableHead>
                        <TableHead>Vendor</TableHead>
                        <TableHead>Model</TableHead>
                        <TableHead>Status</TableHead>
                        <TableHead className="text-right pr-4">Reachability</TableHead>
                      </TableRow>
                    </TableHeader>
                    <TableBody>
                      {filtered.map((d) => (
                        <TableRow key={d.deviceId} className="hover:bg-muted/50 transition-colors">
                          <TableCell className="pl-4 font-medium">{d.hostname || d.deviceId}</TableCell>
                          <TableCell className="font-mono text-xs">{d.ipAddress}</TableCell>
                          <TableCell className="text-sm">{d.vendor}</TableCell>
                          <TableCell className="text-sm">{d.model}</TableCell>
                          <TableCell>
                            <Badge variant={d.status === 'up' ? 'success' : 'danger'} size="sm">
                              {d.status}
                            </Badge>
                          </TableCell>
                          <TableCell className="text-right pr-4">
                            <Badge variant={d.reachability === 'reachable' ? 'success' : 'danger'} size="sm">
                              {d.reachability}
                            </Badge>
                          </TableCell>
                        </TableRow>
                      ))}
                    </TableBody>
                  </Table>
              </SectionCard>
            )}
          </div>
        )
      ) : (
        <Outlet />
      )}
    </div>
  )
}
