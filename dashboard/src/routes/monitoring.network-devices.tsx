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
import {Card, CardContent} from '@/components/ui/card'
import {Table, TableBody, TableCell, TableHead, TableHeader, TableRow} from '@/components/ui/table'
import {Input} from '@/components/ui/input'
import {AlertTriangle, ArrowRightLeft, BookOpen, Loader2, Router, Search, Wifi} from 'lucide-react'
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
    queryFn: () => api.get('/v1/network-devices?limit=100'),
    enabled: isIndexPage,
  })

  const devices: NetworkDevice[] = (data?.devices as NetworkDevice[] | undefined) ?? []

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
      <div className="flex items-center justify-between">
        <div className="flex items-center gap-3">
          <div className="flex items-center justify-center h-10 w-10 rounded-lg bg-gradient-to-br from-cyan-500 to-blue-600">
            <Router className="h-5 w-5 text-white" />
          </div>
          <div>
            <h1 className="text-2xl font-bold tracking-tight">Network Devices</h1>
            <p className="text-muted-foreground mt-1">SNMP devices, traps, and network flows</p>
          </div>
        </div>
        <a href="/docs/datadog-agent/network-devices" target="_blank" rel="noreferrer"
          className="inline-flex items-center gap-1.5 text-sm text-muted-foreground hover:text-foreground transition-colors">
          <BookOpen className="h-4 w-4" />
          View docs
        </a>
      </div>

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
          <Card className="border-dashed">
            <CardContent className="py-16 text-center">
              <div className="mx-auto mb-6 flex h-16 w-16 items-center justify-center rounded-2xl bg-gradient-to-br from-cyan-500/10 to-blue-500/10">
                <Router className="h-8 w-8 text-cyan-500" />
              </div>
              <h3 className="text-lg font-semibold mb-2">No network devices found</h3>
              <p className="text-muted-foreground max-w-sm mx-auto">
                SNMP device data will appear here once collected by the agent.
              </p>
            </CardContent>
          </Card>
        ) : (
          <div className="space-y-4">
            <div className="flex items-center gap-3">
              <div className="flex items-center gap-1.5 text-sm">
                <Router className="h-3.5 w-3.5 text-cyan-500" />
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
              <div className="text-center py-12 text-muted-foreground">
                <p className="font-medium">No devices match your search</p>
                <p className="text-sm mt-1">Try adjusting your search query.</p>
              </div>
            ) : (
              <Card className="overflow-hidden border-border/60 shadow-sm">
                <CardContent className="p-0">
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
                            <Badge
                              variant="secondary"
                              className={cn(
                                'text-xs',
                                d.status === 'up'
                                  ? 'bg-emerald-500/15 text-emerald-700 dark:text-emerald-300 border-emerald-500/20'
                                  : 'bg-red-500/15 text-red-700 dark:text-red-300 border-red-500/20'
                              )}
                            >
                              {d.status}
                            </Badge>
                          </TableCell>
                          <TableCell className="text-right pr-4">
                            <Badge
                              variant="secondary"
                              className={cn(
                                'text-xs',
                                d.reachability === 'reachable'
                                  ? 'bg-emerald-500/15 text-emerald-700 dark:text-emerald-300 border-emerald-500/20'
                                  : 'bg-red-500/15 text-red-700 dark:text-red-300 border-red-500/20'
                              )}
                            >
                              {d.reachability}
                            </Badge>
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
      ) : (
        <Outlet />
      )}
    </div>
  )
}
