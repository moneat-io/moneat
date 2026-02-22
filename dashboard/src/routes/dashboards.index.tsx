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

import {createFileRoute, Link, useNavigate} from '@tanstack/react-router'
import {useQuery, useMutation, useQueryClient} from '@tanstack/react-query'
import {api, type CustomDashboard, type CreateDashboardRequest} from '@/lib/api'
import {Plus, Import, MoreHorizontal, Trash2, Copy, LayoutDashboard, Database} from 'lucide-react'
import {Button} from '@/components/ui/button'
import {useState} from 'react'
import {ImportExportModal} from '@/components/dashboards/ImportExportModal'

export const Route = createFileRoute('/dashboards/')({
  component: DashboardListPage,
})

function DashboardListPage() {
  const navigate = useNavigate()
  const queryClient = useQueryClient()
  const [showImport, setShowImport] = useState(false)

  const {data: dashboards, isLoading} = useQuery({
    queryKey: ['custom-dashboards'],
    queryFn: () => api.getDashboards(),
  })

  const createMutation = useMutation({
    mutationFn: (data: CreateDashboardRequest) => api.createDashboard(data),
    onSuccess: (dashboard) => {
      queryClient.invalidateQueries({queryKey: ['custom-dashboards']})
      navigate({to: '/dashboards/$dashboardId', params: {dashboardId: String(dashboard.id)}, search: {edit: true}})
    },
  })

  const deleteMutation = useMutation({
    mutationFn: (id: number) => api.deleteDashboard(id),
    onSuccess: () => queryClient.invalidateQueries({queryKey: ['custom-dashboards']}),
  })

  const handleCreateBlank = () => {
    createMutation.mutate({
      title: 'New Dashboard',
      widgets: [],
    })
  }

  const handleCreateFromTemplate = async () => {
    try {
      const templates = await api.getDashboardTemplates()
      if (templates.length > 0) {
        createMutation.mutate(templates[0])
      }
    } catch {
      handleCreateBlank()
    }
  }

  return (
    <div className="space-y-4">
      <div className="flex items-center justify-between">
        <div />
        <div className="flex items-center gap-2">
          <Link to="/dashboards/datasources">
            <Button variant="outline" size="sm">
              <Database className="h-4 w-4 mr-1.5" />
              Data Sources
            </Button>
          </Link>
          <Button variant="outline" size="sm" onClick={() => setShowImport(true)}>
            <Import className="h-4 w-4 mr-1.5" />
            Import
          </Button>
          <Button size="sm" onClick={handleCreateBlank}>
            <Plus className="h-4 w-4 mr-1.5" />
            New Dashboard
          </Button>
        </div>
      </div>

      {isLoading ? (
        <div className="grid grid-cols-1 md:grid-cols-2 lg:grid-cols-3 gap-4">
          {[1, 2, 3].map((i) => (
            <div key={i} className="h-40 rounded-lg border bg-muted/30 animate-pulse" />
          ))}
        </div>
      ) : dashboards && dashboards.length > 0 ? (
        <div className="grid grid-cols-1 md:grid-cols-2 lg:grid-cols-3 gap-4">
          {dashboards.map((dashboard) => (
            <DashboardCard
              key={dashboard.id}
              dashboard={dashboard}
              onDelete={() => deleteMutation.mutate(dashboard.id)}
              onDuplicate={() => {
                api.getDashboard(dashboard.id).then((full) => {
                  createMutation.mutate({
                    title: `${full.title} (Copy)`,
                    description: full.description,
                    widgets: full.widgets.map((w) => ({
                      title: w.title,
                      widget_type: w.widget_type,
                      grid_x: w.grid_x,
                      grid_y: w.grid_y,
                      grid_w: w.grid_w,
                      grid_h: w.grid_h,
                      query_config: w.query_config,
                      display_config: w.display_config,
                      sort_order: w.sort_order,
                    })),
                  })
                })
              }}
            />
          ))}
        </div>
      ) : (
        <div className="flex flex-col items-center justify-center py-16 text-center">
          <div className="rounded-full bg-muted p-4 mb-4">
            <LayoutDashboard className="h-8 w-8 text-muted-foreground" />
          </div>
          <h3 className="text-lg font-medium mb-1">No dashboards yet</h3>
          <p className="text-muted-foreground text-sm mb-4">
            Create a custom dashboard or import from DataDog/Grafana
          </p>
          <div className="flex items-center gap-2">
            <Button variant="outline" size="sm" onClick={handleCreateFromTemplate}>
              Use Template
            </Button>
            <Button size="sm" onClick={handleCreateBlank}>
              <Plus className="h-4 w-4 mr-1.5" />
              Create Dashboard
            </Button>
          </div>
        </div>
      )}

      <ImportExportModal
        open={showImport}
        onOpenChange={setShowImport}
        mode="import"
      />
    </div>
  )
}

function DashboardCard({
  dashboard,
  onDelete,
  onDuplicate,
}: {
  dashboard: CustomDashboard
  onDelete: () => void
  onDuplicate: () => void
}) {
  const [showMenu, setShowMenu] = useState(false)

  return (
    <Link
      to="/dashboards/$dashboardId"
      params={{dashboardId: String(dashboard.id)}}
      className="group relative block rounded-lg border bg-card p-4 hover:border-primary/50 transition-colors"
    >
      <div className="flex items-start justify-between">
        <div className="space-y-1 flex-1 min-w-0">
          <h3 className="font-medium truncate">{dashboard.title}</h3>
          {dashboard.description && (
            <p className="text-xs text-muted-foreground line-clamp-2">{dashboard.description}</p>
          )}
        </div>
        <div className="relative">
          <button
            onClick={(e) => {
              e.preventDefault()
              e.stopPropagation()
              setShowMenu(!showMenu)
            }}
            className="p-1 rounded hover:bg-muted opacity-0 group-hover:opacity-100 transition-opacity"
          >
            <MoreHorizontal className="h-4 w-4" />
          </button>
          {showMenu && (
            <div className="absolute right-0 top-8 z-10 w-36 rounded-md border bg-popover p-1 shadow-md">
              <button
                onClick={(e) => {
                  e.preventDefault()
                  e.stopPropagation()
                  onDuplicate()
                  setShowMenu(false)
                }}
                className="flex w-full items-center gap-2 rounded px-2 py-1.5 text-sm hover:bg-muted"
              >
                <Copy className="h-3.5 w-3.5" /> Duplicate
              </button>
              <button
                onClick={(e) => {
                  e.preventDefault()
                  e.stopPropagation()
                  onDelete()
                  setShowMenu(false)
                }}
                className="flex w-full items-center gap-2 rounded px-2 py-1.5 text-sm text-destructive hover:bg-muted"
              >
                <Trash2 className="h-3.5 w-3.5" /> Delete
              </button>
            </div>
          )}
        </div>
      </div>
      <div className="mt-3 flex items-center gap-3 text-xs text-muted-foreground">
        <span>{dashboard.widgets.length} widget{dashboard.widgets.length !== 1 ? 's' : ''}</span>
        <span>·</span>
        <span>Updated {new Date(dashboard.updated_at).toLocaleDateString()}</span>
      </div>
    </Link>
  )
}
