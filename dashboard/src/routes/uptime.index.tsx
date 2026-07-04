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

import {createFileRoute} from '@tanstack/react-router'
import {useQuery} from '@tanstack/react-query'
import {api} from '@/lib/api'
import {Button} from '@/components/ui/button'
import {PageHeader} from '@/components/ui/page-header'
import {StatCard} from '@/components/ui/stat-card'
import {EmptyState} from '@/components/ui/empty-state'
import {Activity, CheckCircle2, Globe, LayoutGrid, Pause, Plus, Rows3, XCircle} from 'lucide-react'
import {useEffect, useState} from 'react'
import AddMonitorDialog from '@/components/uptime/AddMonitorDialog'
import MonitorListItem from '@/components/uptime/MonitorListItem'
import MonitorCompactTable from '@/components/uptime/MonitorCompactTable'

export const Route = createFileRoute('/uptime/')({
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

  const headerActions = (
    <>
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
    </>
  )

  if (isLoading) {
    return (
      <div className="px-6 py-4 space-y-4">
        <PageHeader
          icon={Globe}
          title="Uptime monitoring"
          description="Monitor your websites, APIs, and services"
          actions={headerActions}
        />
        <div className="flex items-center justify-center h-48">
          <Activity className="h-8 w-8 animate-spin text-muted-foreground" />
        </div>
      </div>
    )
  }

  return (
    <div>
      <div className="px-6 py-4 space-y-4">
        <PageHeader
          icon={Globe}
          title="Uptime monitoring"
          description="Monitor your websites, APIs, and services"
          actions={headerActions}
        />
        {/* Summary Stats */}
        {monitors.length > 0 && (
          <div className="grid gap-4 grid-cols-2 md:grid-cols-4">
            <StatCard label="Total Monitors" tone="info" icon={Globe} value={monitors.length} />
            <StatCard label="Up" tone="success" icon={CheckCircle2} value={upMonitors} />
            <StatCard label="Down" tone="danger" icon={XCircle} value={downMonitors} />
            <StatCard label="Paused" tone="warning" icon={Pause} value={pausedMonitors} />
          </div>
        )}

        {monitors.length === 0 ? (
          <EmptyState
            icon={Globe}
            title="No monitors yet"
            description="Get started by creating your first uptime monitor."
            action={
              <Button size="sm" onClick={() => setAddDialogOpen(true)} className="gap-1.5">
                <Plus className="h-3.5 w-3.5" />
                Create Monitor
              </Button>
            }
          />
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
