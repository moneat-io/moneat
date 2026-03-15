// Moneat - observability platform
// Copyright (C) 2026 Moneat
//
// This program is free software: you can redistribute it and/or modify
// it under the terms of the GNU Affero General Public License as published by
// the Free Software Foundation, either version 3 of the License, or
// (at your option) any later version.
//
// This program is distributed in the hope that it will be useful,
// but WITHOUT ANY WARRANTY; without even the implied warranty of
// MERCHANTABILITY or FITNESS FOR A PARTICULAR PURPOSE. See the
// GNU Affero General Public License for more details.
//
// You should have received a copy of the GNU Affero General Public License
// along with this program. If not, see <https://www.gnu.org/licenses/>.

import {createFileRoute, redirect} from '@tanstack/react-router'
import {useQuery} from '@tanstack/react-query'
import {api} from '@/lib/api'
import {Button} from '@/components/ui/button'
import {Card, CardContent} from '@/components/ui/card'
import {Activity, CheckCircle2, Globe, LayoutGrid, Pause, Plus, Rows3, XCircle} from 'lucide-react'
import {useEffect, useState} from 'react'
import AddMonitorDialog from '@/components/uptime/AddMonitorDialog'
import MonitorListItem from '@/components/uptime/MonitorListItem'
import MonitorCompactTable from '@/components/uptime/MonitorCompactTable'

export const Route = createFileRoute('/uptime/')({
  beforeLoad: () => {
    if (!api.isAuthenticated()) {
      throw redirect({to: '/login'})
    }
  },
  component: UptimeListPage,
})

type UptimeViewMode = 'cards' | 'compact'
const UPTIME_VIEW_MODE_STORAGE_KEY = 'uptime-monitors-view-mode'

function getInitialViewMode(): UptimeViewMode {
  if (typeof window === 'undefined') return 'compact'
  return window.localStorage.getItem(UPTIME_VIEW_MODE_STORAGE_KEY) === 'cards' ? 'cards' : 'compact'
}

function UptimeListPage() {
  const [addDialogOpen, setAddDialogOpen] = useState(false)
  const [viewMode, setViewMode] = useState<UptimeViewMode>(getInitialViewMode)

  useEffect(() => {
    if (typeof window !== 'undefined') {
      window.localStorage.setItem(UPTIME_VIEW_MODE_STORAGE_KEY, viewMode)
    }
  }, [viewMode])

  const {data: monitors = [], isLoading} = useQuery({
    queryKey: ['uptime-monitors'],
    queryFn: () => api.getUptimeMonitors(),
  })

  const upMonitors = monitors.filter((m) => m.status === 'up').length
  const downMonitors = monitors.filter((m) => m.status === 'down').length
  const pausedMonitors = monitors.filter((m) => m.status === 'paused').length

  if (isLoading) {
    return (
      <div className="container mx-auto px-4 py-6">
        <div className="flex items-center justify-center h-48">
          <Activity className="h-8 w-8 animate-spin text-muted-foreground" />
        </div>
      </div>
    )
  }

  return (
    <div>
      {/* Header */}
      <div className="border-b bg-card/50">
        <div className="container mx-auto px-4 py-4">
          <div className="flex flex-col gap-3 sm:flex-row sm:items-center sm:justify-between">
            <div>
              <h1 className="text-xl font-bold tracking-tight">Uptime Monitoring</h1>
              <p className="text-xs text-muted-foreground mt-0.5">
                Monitor your websites, APIs, and services
              </p>
            </div>
            <div className="flex items-center gap-1.5">
              <div className="inline-flex items-center rounded-lg border bg-background p-0.5">
                <Button
                  variant={viewMode === 'cards' ? 'secondary' : 'ghost'}
                  size="sm"
                  className="h-7 gap-1 text-xs"
                  onClick={() => setViewMode('cards')}
                >
                  <LayoutGrid className="h-3 w-3" />
                  Cards
                </Button>
                <Button
                  variant={viewMode === 'compact' ? 'secondary' : 'ghost'}
                  size="sm"
                  className="h-7 gap-1 text-xs"
                  onClick={() => setViewMode('compact')}
                >
                  <Rows3 className="h-3 w-3" />
                  Compact
                </Button>
              </div>
              <Button size="sm" onClick={() => setAddDialogOpen(true)} className="gap-1.5">
                <Plus className="h-3.5 w-3.5" />
                Add Monitor
              </Button>
            </div>
          </div>
        </div>
      </div>

      <div className="container mx-auto px-4 py-4 space-y-4">
        {/* Summary Stats */}
        {monitors.length > 0 && (
          <div className="grid gap-2 grid-cols-2 md:grid-cols-4">
            <Card className="bg-gradient-to-br from-blue-500/5 to-blue-600/10 border-blue-500/10">
              <CardContent className="pt-2.5 pb-2 px-3">
                <div className="flex items-center gap-2">
                  <div className="flex items-center justify-center h-8 w-8 rounded-lg bg-blue-500/15 shrink-0">
                    <Globe className="h-4 w-4 text-blue-600 dark:text-blue-400" />
                  </div>
                  <div className="min-w-0">
                    <p className="text-xl font-bold">{monitors.length}</p>
                    <p className="text-[10px] text-muted-foreground">Total Monitors</p>
                  </div>
                </div>
              </CardContent>
            </Card>

            <Card className="bg-gradient-to-br from-emerald-500/5 to-emerald-600/10 border-emerald-500/10">
              <CardContent className="pt-2.5 pb-2 px-3">
                <div className="flex items-center gap-2">
                  <div className="flex items-center justify-center h-8 w-8 rounded-lg bg-emerald-500/15 shrink-0">
                    <CheckCircle2 className="h-4 w-4 text-emerald-600 dark:text-emerald-400" />
                  </div>
                  <div className="min-w-0">
                    <p className="text-xl font-bold">{upMonitors}</p>
                    <p className="text-[10px] text-muted-foreground">Up</p>
                  </div>
                </div>
              </CardContent>
            </Card>

            <Card className="bg-gradient-to-br from-red-500/5 to-red-600/10 border-red-500/10">
              <CardContent className="pt-2.5 pb-2 px-3">
                <div className="flex items-center gap-2">
                  <div className="flex items-center justify-center h-8 w-8 rounded-lg bg-red-500/15 shrink-0">
                    <XCircle className="h-4 w-4 text-red-600 dark:text-red-400" />
                  </div>
                  <div className="min-w-0">
                    <p className="text-xl font-bold">{downMonitors}</p>
                    <p className="text-[10px] text-muted-foreground">Down</p>
                  </div>
                </div>
              </CardContent>
            </Card>

            <Card className="bg-gradient-to-br from-yellow-500/5 to-yellow-600/10 border-yellow-500/10">
              <CardContent className="pt-2.5 pb-2 px-3">
                <div className="flex items-center gap-2">
                  <div className="flex items-center justify-center h-8 w-8 rounded-lg bg-yellow-500/15 shrink-0">
                    <Pause className="h-4 w-4 text-yellow-600 dark:text-yellow-400" />
                  </div>
                  <div className="min-w-0">
                    <p className="text-xl font-bold">{pausedMonitors}</p>
                    <p className="text-[10px] text-muted-foreground">Paused</p>
                  </div>
                </div>
              </CardContent>
            </Card>
          </div>
        )}

        {monitors.length === 0 ? (
          <Card>
            <CardContent className="flex flex-col items-center justify-center py-10">
              <Globe className="h-12 w-12 text-muted-foreground mb-3" />
              <h3 className="text-base font-semibold mb-1">No monitors yet</h3>
              <p className="text-muted-foreground text-center text-sm mb-4">
                Get started by creating your first uptime monitor
              </p>
              <Button size="sm" onClick={() => setAddDialogOpen(true)} className="gap-1.5">
                <Plus className="h-3.5 w-3.5" />
                Create Monitor
              </Button>
            </CardContent>
          </Card>
        ) : viewMode === 'compact' ? (
          <MonitorCompactTable monitors={monitors} />
        ) : (
          <div className="grid gap-3">
            {monitors.map((monitor) => (
              <MonitorListItem key={monitor.id} monitor={monitor} />
            ))}
          </div>
        )}
      </div>

      <AddMonitorDialog open={addDialogOpen} onOpenChange={setAddDialogOpen} />
    </div>
  )
}
