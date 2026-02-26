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
import {
  api,
  type CustomDashboard,
  type CreateDashboardRequest,
  type DashboardFolder,
  type CreateFolderRequest,
} from '@/lib/api'
import {
  Plus,
  Import,
  MoreHorizontal,
  Trash2,
  Copy,
  LayoutDashboard,
  Database,
  Star,
  Folder,
  FolderPlus,
  ChevronRight,
  Pencil,
  BarChart3,
  LineChart,
  PieChart,
  Sparkles,
  ArrowRight,
} from 'lucide-react'
import {Button} from '@/components/ui/button'
import {Input} from '@/components/ui/input'
import {Card, CardContent} from '@/components/ui/card'
import {useState, memo, useMemo} from 'react'
import {ImportExportModal} from '@/components/dashboards/ImportExportModal'
import {
  DropdownMenu,
  DropdownMenuContent,
  DropdownMenuItem,
  DropdownMenuSeparator,
  DropdownMenuSub,
  DropdownMenuSubContent,
  DropdownMenuSubTrigger,
  DropdownMenuTrigger,
} from '@/components/ui/dropdown-menu'

export const Route = createFileRoute('/dashboards/')({
  component: DashboardListPage,
})

type FolderFilter = 'all' | 'favorites' | 'uncategorized' | number

function DashboardListPage() {
  const navigate = useNavigate()
  const queryClient = useQueryClient()
  const [showImport, setShowImport] = useState(false)
  const [selectedFolder, setSelectedFolder] = useState<FolderFilter>('all')
  const [newFolderName, setNewFolderName] = useState('')
  const [isCreatingFolder, setIsCreatingFolder] = useState(false)
  const [now] = useState(() => Date.now())

  const {data: dashboards, isLoading} = useQuery({
    queryKey: ['custom-dashboards'],
    queryFn: () => api.getDashboards(),
  })

  const {data: folders} = useQuery({
    queryKey: ['dashboard-folders'],
    queryFn: () => api.getDashboardFolders(),
  })

  const filteredDashboards = useMemo(() => {
    if (!dashboards) return []
    if (selectedFolder === 'all') return dashboards
    if (selectedFolder === 'favorites') return dashboards.filter((d) => d.is_favorited)
    if (selectedFolder === 'uncategorized') return dashboards.filter((d) => !d.folder_id)
    return dashboards.filter((d) => d.folder_id === selectedFolder)
  }, [dashboards, selectedFolder])

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

  const createFolderMutation = useMutation({
    mutationFn: (data: CreateFolderRequest) => api.createDashboardFolder(data),
    onSuccess: () => {
      queryClient.invalidateQueries({queryKey: ['dashboard-folders']})
      setNewFolderName('')
      setIsCreatingFolder(false)
    },
  })

  const deleteFolderMutation = useMutation({
    mutationFn: (id: number) => api.deleteDashboardFolder(id),
    onSuccess: () => {
      queryClient.invalidateQueries({queryKey: ['dashboard-folders']})
      queryClient.invalidateQueries({queryKey: ['custom-dashboards']})
      if (typeof selectedFolder === 'number') setSelectedFolder('all')
    },
  })

  const handleCreateBlank = () => {
    createMutation.mutate({
      title: 'New Dashboard',
      widgets: [],
      folder_id: typeof selectedFolder === 'number' ? selectedFolder : undefined,
    })
  }

  const handleCreateFromTemplate = async () => {
    try {
      const templates = await api.getDashboardTemplates()
      if (templates.length > 0) {
        createMutation.mutate({
          ...templates[0],
          folder_id: typeof selectedFolder === 'number' ? selectedFolder : undefined,
        })
      }
    } catch {
      handleCreateBlank()
    }
  }

  const favoritesCount = dashboards?.filter((d) => d.is_favorited).length ?? 0
  const uncategorizedCount = dashboards?.filter((d) => !d.folder_id).length ?? 0

  const folderLabel =
    selectedFolder === 'all'
      ? 'All'
      : selectedFolder === 'favorites'
        ? 'Favorites'
        : selectedFolder === 'uncategorized'
          ? 'Uncategorized'
          : folders?.find((f) => f.id === selectedFolder)?.name ?? 'Folder'

  return (
    <div>
      {/* Page header */}
      <div className="border-b bg-card/50">
        <div className="px-6 lg:px-8 py-6">
          <div className="flex flex-col gap-4 sm:flex-row sm:items-center sm:justify-between">
            <div className="flex items-center gap-4">
              <div className="flex items-center justify-center h-12 w-12 rounded-xl bg-primary/10 text-primary">
                <LayoutDashboard className="h-6 w-6" />
              </div>
              <div>
                <h1 className="text-2xl font-bold tracking-tight">Dashboards</h1>
                <p className="text-sm text-muted-foreground mt-0.5">
                  Build custom dashboards with drag-and-drop widgets
                </p>
              </div>
            </div>
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
        </div>
      </div>

      <div className="px-6 lg:px-8 py-6">
        <div className="flex gap-6">
          {/* Folder sidebar */}
          <aside className="w-52 shrink-0 space-y-0.5">
            <div className="text-[11px] font-semibold text-muted-foreground uppercase tracking-widest px-2.5 pb-2">
              Folders
            </div>
            <SidebarButton
              icon={<LayoutDashboard className="h-4 w-4 shrink-0" />}
              label="All"
              count={dashboards?.length ?? 0}
              isSelected={selectedFolder === 'all'}
              onClick={() => setSelectedFolder('all')}
            />
            <SidebarButton
              icon={<Star className="h-4 w-4 shrink-0" />}
              label="Favorites"
              count={favoritesCount}
              isSelected={selectedFolder === 'favorites'}
              onClick={() => setSelectedFolder('favorites')}
            />
            {folders?.map((folder) => {
              const count = dashboards?.filter((d) => d.folder_id === folder.id).length ?? 0
              return (
                <FolderSidebarItem
                  key={folder.id}
                  folder={folder}
                  count={count}
                  isSelected={selectedFolder === folder.id}
                  onSelect={() => setSelectedFolder(folder.id)}
                  onDelete={() => deleteFolderMutation.mutate(folder.id)}
                />
              )
            })}
            <SidebarButton
              icon={<Folder className="h-4 w-4 shrink-0" />}
              label="Uncategorized"
              count={uncategorizedCount}
              isSelected={selectedFolder === 'uncategorized'}
              onClick={() => setSelectedFolder('uncategorized')}
            />
            <div className="pt-1">
              {isCreatingFolder ? (
                <div className="flex items-center gap-1 px-2 py-1">
                  <Input
                    placeholder="Folder name"
                    value={newFolderName}
                    onChange={(e) => setNewFolderName(e.target.value)}
                    onKeyDown={(e) => {
                      if (e.key === 'Enter') {
                        if (newFolderName.trim()) createFolderMutation.mutate({name: newFolderName.trim()})
                      }
                      if (e.key === 'Escape') {
                        setIsCreatingFolder(false)
                        setNewFolderName('')
                      }
                    }}
                    className="h-7 text-sm"
                    autoFocus
                  />
                  <Button
                    size="sm"
                    variant="ghost"
                    className="h-7 px-2"
                    onClick={() => {
                      if (newFolderName.trim()) createFolderMutation.mutate({name: newFolderName.trim()})
                    }}
                  >
                    <ChevronRight className="h-4 w-4" />
                  </Button>
                </div>
              ) : (
                <button
                  onClick={() => setIsCreatingFolder(true)}
                  className="flex w-full items-center gap-2 rounded-md px-2.5 py-1.5 text-sm text-muted-foreground hover:bg-muted hover:text-foreground transition-colors"
                >
                  <FolderPlus className="h-4 w-4" />
                  New folder
                </button>
              )}
            </div>
          </aside>

          {/* Main content */}
          <div className="flex-1 min-w-0">
            {isLoading ? (
              <div className="grid grid-cols-1 md:grid-cols-2 xl:grid-cols-3 gap-4">
                {[1, 2, 3].map((i) => (
                  <div key={i} className="rounded-lg border bg-card overflow-hidden">
                    <div className="h-1.5 bg-muted animate-pulse" />
                    <div className="p-4 space-y-3">
                      <div className="h-5 w-3/4 bg-muted rounded animate-pulse" />
                      <div className="h-4 w-1/2 bg-muted rounded animate-pulse" />
                      <div className="flex gap-2 pt-2">
                        <div className="h-5 w-16 bg-muted rounded-full animate-pulse" />
                        <div className="h-5 w-24 bg-muted rounded-full animate-pulse" />
                      </div>
                    </div>
                  </div>
                ))}
              </div>
            ) : filteredDashboards.length > 0 ? (
              <div className="grid grid-cols-1 md:grid-cols-2 xl:grid-cols-3 gap-4">
                {filteredDashboards.map((dashboard) => (
                  <DashboardCard
                    key={dashboard.id}
                    dashboard={dashboard}
                    folders={folders ?? []}
                    now={now}
                    onDelete={() => deleteMutation.mutate(dashboard.id)}
                    onDuplicate={() => {
                      api.getDashboard(dashboard.id).then((full) => {
                        createMutation.mutate({
                          title: `${full.title} (Copy)`,
                          description: full.description,
                          folder_id: full.folder_id,
                          widgets: full.widgets.map((w) => ({
                            title: w.title,
                            widget_type: w.widget_type,
                            grid_x: w.grid_x,
                            grid_y: w.grid_y,
                            grid_w: w.grid_w,
                            grid_h: w.grid_h,
                            query_configs: w.query_configs,
                            display_config: w.display_config,
                            sort_order: w.sort_order,
                          })),
                        })
                      })
                    }}
                    onFavoriteToggle={async () => {
                      await api.toggleDashboardFavorite(dashboard.id)
                      queryClient.invalidateQueries({queryKey: ['custom-dashboards']})
                    }}
                    onMoveToFolder={async (folderId) => {
                      await api.moveDashboardToFolder(dashboard.id, folderId)
                      queryClient.invalidateQueries({queryKey: ['custom-dashboards']})
                    }}
                  />
                ))}
              </div>
            ) : (
              <EmptyState
                selectedFolder={selectedFolder}
                folderLabel={folderLabel}
                onCreateBlank={handleCreateBlank}
                onCreateFromTemplate={handleCreateFromTemplate}
                onImport={() => setShowImport(true)}
              />
            )}
          </div>
        </div>
      </div>

      <ImportExportModal open={showImport} onOpenChange={setShowImport} mode="import" />
    </div>
  )
}

function SidebarButton({
  icon,
  label,
  count,
  isSelected,
  onClick,
}: {
  icon: React.ReactNode
  label: string
  count: number
  isSelected: boolean
  onClick: () => void
}) {
  return (
    <button
      onClick={onClick}
      className={`flex w-full items-center justify-between gap-2 rounded-md px-2.5 py-1.5 text-sm transition-colors ${
        isSelected
          ? 'bg-primary/10 text-primary font-medium'
          : 'text-foreground/80 hover:bg-muted hover:text-foreground'
      }`}
    >
      <span className="flex items-center gap-2 truncate">
        {icon}
        {label}
      </span>
      <span className="shrink-0 min-w-[1.5rem] text-right tabular-nums text-xs text-muted-foreground">{count}</span>
    </button>
  )
}

const CARD_ACCENT_COLORS = [
  'from-blue-500 to-blue-600',
  'from-violet-500 to-purple-600',
  'from-emerald-500 to-teal-600',
  'from-amber-500 to-orange-600',
  'from-rose-500 to-pink-600',
  'from-cyan-500 to-sky-600',
]

function getAccentColor(id: number) {
  return CARD_ACCENT_COLORS[id % CARD_ACCENT_COLORS.length]
}

function EmptyState({
  selectedFolder,
  folderLabel,
  onCreateBlank,
  onCreateFromTemplate,
  onImport,
}: {
  selectedFolder: FolderFilter
  folderLabel: string
  onCreateBlank: () => void
  onCreateFromTemplate: () => void
  onImport: () => void
}) {
  if (selectedFolder === 'favorites') {
    return (
      <Card className="border-dashed border-2">
        <CardContent className="flex flex-col items-center justify-center py-16">
          <div className="bg-amber-500/10 p-4 rounded-full mb-5">
            <Star className="h-8 w-8 text-amber-500" />
          </div>
          <h3 className="text-xl font-semibold mb-2">No favorites yet</h3>
          <p className="text-muted-foreground text-center max-w-sm leading-relaxed">
            Star dashboards you use often for quick access. Click the star icon on any dashboard card to add it here.
          </p>
        </CardContent>
      </Card>
    )
  }

  if (selectedFolder === 'uncategorized') {
    return (
      <Card className="border-dashed border-2">
        <CardContent className="flex flex-col items-center justify-center py-16">
          <div className="bg-muted p-4 rounded-full mb-5">
            <Folder className="h-8 w-8 text-muted-foreground" />
          </div>
          <h3 className="text-xl font-semibold mb-2">No uncategorized dashboards</h3>
          <p className="text-muted-foreground text-center max-w-sm leading-relaxed">
            Dashboards that haven't been moved into a folder will appear here.
          </p>
          <Button className="mt-5" onClick={onCreateBlank}>
            <Plus className="h-4 w-4 mr-1.5" />
            Create Dashboard
          </Button>
        </CardContent>
      </Card>
    )
  }

  if (typeof selectedFolder === 'number') {
    return (
      <Card className="border-dashed border-2">
        <CardContent className="flex flex-col items-center justify-center py-16">
          <div className="bg-primary/10 p-4 rounded-full mb-5">
            <Folder className="h-8 w-8 text-primary" />
          </div>
          <h3 className="text-xl font-semibold mb-2">{folderLabel} is empty</h3>
          <p className="text-muted-foreground text-center max-w-sm leading-relaxed">
            Move existing dashboards here or create a new one to get started.
          </p>
          <Button className="mt-5" onClick={onCreateBlank}>
            <Plus className="h-4 w-4 mr-1.5" />
            Create Dashboard
          </Button>
        </CardContent>
      </Card>
    )
  }

  return (
    <Card className="border-dashed border-2">
      <CardContent className="flex flex-col items-center justify-center py-20">
        <div className="bg-primary/10 p-5 rounded-full mb-6">
          <LayoutDashboard className="h-10 w-10 text-primary" />
        </div>
        <h3 className="text-2xl font-semibold mb-3">Create your first dashboard</h3>
        <p className="text-muted-foreground text-center mb-8 max-w-lg leading-relaxed">
          Visualize your data with custom charts, tables, and metrics.
          Start from scratch, use a template, or import from Grafana and DataDog.
        </p>
        <div className="flex flex-col sm:flex-row gap-3">
          <Button variant="outline" size="lg" onClick={onCreateFromTemplate}>
            <Sparkles className="h-4 w-4 mr-2" />
            Start from Template
          </Button>
          <Button variant="outline" size="lg" onClick={onImport}>
            <Import className="h-4 w-4 mr-2" />
            Import Dashboard
          </Button>
          <Button size="lg" onClick={onCreateBlank}>
            <Plus className="h-4 w-4 mr-2" />
            Blank Dashboard
          </Button>
        </div>
        <div className="mt-12 grid grid-cols-1 sm:grid-cols-3 gap-6 max-w-2xl w-full">
          <div className="flex flex-col items-center text-center gap-2 p-4">
            <div className="h-10 w-10 rounded-lg bg-blue-500/10 flex items-center justify-center">
              <BarChart3 className="h-5 w-5 text-blue-500" />
            </div>
            <span className="text-sm font-medium">Rich Visualizations</span>
            <span className="text-xs text-muted-foreground">Charts, tables & stats</span>
          </div>
          <div className="flex flex-col items-center text-center gap-2 p-4">
            <div className="h-10 w-10 rounded-lg bg-violet-500/10 flex items-center justify-center">
              <LineChart className="h-5 w-5 text-violet-500" />
            </div>
            <span className="text-sm font-medium">Drag & Drop</span>
            <span className="text-xs text-muted-foreground">Flexible grid layout</span>
          </div>
          <div className="flex flex-col items-center text-center gap-2 p-4">
            <div className="h-10 w-10 rounded-lg bg-emerald-500/10 flex items-center justify-center">
              <PieChart className="h-5 w-5 text-emerald-500" />
            </div>
            <span className="text-sm font-medium">Multiple Sources</span>
            <span className="text-xs text-muted-foreground">ClickHouse, Postgres & more</span>
          </div>
        </div>
      </CardContent>
    </Card>
  )
}

const FolderSidebarItem = memo(function FolderSidebarItem({
  folder,
  count,
  isSelected,
  onSelect,
  onDelete,
}: {
  folder: DashboardFolder
  count: number
  isSelected: boolean
  onSelect: () => void
  onDelete: () => void
}) {
  const [isEditing, setIsEditing] = useState(false)
  const [editName, setEditName] = useState(folder.name)
  const queryClient = useQueryClient()
  const updateMutation = useMutation({
    mutationFn: (name: string) => api.updateDashboardFolder(folder.id, {name}),
    onSuccess: () => {
      queryClient.invalidateQueries({queryKey: ['dashboard-folders']})
      setIsEditing(false)
    },
  })

  if (isEditing) {
    return (
      <div className="flex items-center gap-1 px-2 py-1.5">
        <Input
          value={editName}
          onChange={(e) => setEditName(e.target.value)}
          onKeyDown={(e) => {
            if (e.key === 'Enter') updateMutation.mutate(editName.trim())
            if (e.key === 'Escape') setIsEditing(false)
          }}
          className="h-8 text-sm"
          autoFocus
        />
      </div>
    )
  }

  return (
    <div
      className={`group relative flex w-full items-center justify-between gap-2 rounded-md px-2.5 py-1.5 text-sm transition-colors ${
        isSelected ? 'bg-primary/10 text-primary font-medium' : 'hover:bg-muted'
      }`}
    >
      <button onClick={onSelect} className="flex flex-1 items-center gap-2 truncate text-left min-w-0">
        <Folder
          className="h-4 w-4 shrink-0"
          style={folder.color ? {color: folder.color} : undefined}
        />
        <span className="truncate">{folder.name}</span>
      </button>
      <span className="shrink-0 min-w-[1.5rem] text-right tabular-nums text-xs text-muted-foreground group-hover:invisible">{count}</span>
      <div className="absolute right-1.5 top-1/2 -translate-y-1/2 opacity-0 group-hover:opacity-100 transition-opacity">
        <DropdownMenu>
          <DropdownMenuTrigger asChild>
            <button
              onClick={(e) => e.stopPropagation()}
              className="p-1 rounded text-muted-foreground hover:bg-muted-foreground/20 hover:text-foreground"
            >
              <MoreHorizontal className="h-3.5 w-3.5" />
            </button>
          </DropdownMenuTrigger>
          <DropdownMenuContent align="end" className="w-48" side="bottom">
            <DropdownMenuItem onClick={() => setIsEditing(true)}>
              <Pencil className="h-3.5 w-3.5 mr-2" />
              Rename
            </DropdownMenuItem>
            <DropdownMenuSeparator />
            <DropdownMenuItem onClick={onDelete} className="text-destructive">
              <Trash2 className="h-3.5 w-3.5 mr-2" />
              Delete folder
            </DropdownMenuItem>
          </DropdownMenuContent>
        </DropdownMenu>
      </div>
    </div>
  )
})

const DashboardCard = memo(function DashboardCard({
  dashboard,
  folders,
  now,
  onDelete,
  onDuplicate,
  onFavoriteToggle,
  onMoveToFolder,
}: {
  dashboard: CustomDashboard
  folders: DashboardFolder[]
  now: number
  onDelete: () => void
  onDuplicate: () => void
  onFavoriteToggle: () => void
  onMoveToFolder: (folderId: number | null) => void
}) {
  const handleFavorite = (e: React.MouseEvent) => {
    e.preventDefault()
    e.stopPropagation()
    onFavoriteToggle()
  }

  const accent = getAccentColor(dashboard.id)
  const widgetCount = dashboard.widgets.length
  const updatedDate = useMemo(() => new Date(dashboard.updated_at), [dashboard.updated_at])
  const isRecent = now - updatedDate.getTime() < 86400000

  return (
    <Link
      to="/dashboards/$dashboardId"
      params={{dashboardId: String(dashboard.id)}}
      className="group relative block rounded-lg border bg-card overflow-hidden hover:shadow-lg hover:border-primary/40 transition-all duration-200"
    >
      <div className={`h-1.5 w-full bg-gradient-to-r ${accent}`} />
      <div className="p-4">
        <div className="flex items-start justify-between gap-2">
          <div className="flex-1 min-w-0">
            <h3 className="font-semibold truncate leading-tight">{dashboard.title}</h3>
            {dashboard.description ? (
              <p className="text-xs text-muted-foreground line-clamp-2 mt-1">{dashboard.description}</p>
            ) : (
              <p className="text-xs text-muted-foreground/50 italic mt-1">No description</p>
            )}
          </div>
          <div className="flex items-center gap-0.5 shrink-0">
            <button
              onClick={handleFavorite}
              className={`p-1 rounded hover:bg-muted transition-opacity ${
                dashboard.is_favorited ? 'opacity-100' : 'opacity-0 group-hover:opacity-100'
              }`}
              title={dashboard.is_favorited ? 'Remove from favorites' : 'Add to favorites'}
            >
              <Star
                className={`h-4 w-4 ${dashboard.is_favorited ? 'fill-amber-400 text-amber-500' : 'text-muted-foreground'}`}
              />
            </button>
            <DropdownMenu>
              <DropdownMenuTrigger asChild>
                <button
                  onClick={(e) => {
                    e.preventDefault()
                    e.stopPropagation()
                  }}
                  className="p-1 rounded hover:bg-muted opacity-0 group-hover:opacity-100 transition-opacity"
                >
                  <MoreHorizontal className="h-4 w-4" />
                </button>
              </DropdownMenuTrigger>
              <DropdownMenuContent align="end" className="w-48">
                <DropdownMenuItem
                  onClick={(e) => {
                    e.preventDefault()
                    e.stopPropagation()
                    onDuplicate()
                  }}
                >
                  <Copy className="h-3.5 w-3.5 mr-2" />
                  Duplicate
                </DropdownMenuItem>
                <DropdownMenuSub>
                  <DropdownMenuSubTrigger
                    onClick={(e) => {
                      e.preventDefault()
                      e.stopPropagation()
                    }}
                  >
                    <Folder className="h-3.5 w-3.5 mr-2" />
                    Move to folder
                  </DropdownMenuSubTrigger>
                  <DropdownMenuSubContent>
                    <DropdownMenuItem
                      onClick={(e) => {
                        e.preventDefault()
                        e.stopPropagation()
                        onMoveToFolder(null)
                      }}
                    >
                      Uncategorized
                    </DropdownMenuItem>
                    {folders.map((folder) => (
                      <DropdownMenuItem
                        key={folder.id}
                        onClick={(e) => {
                          e.preventDefault()
                          e.stopPropagation()
                          onMoveToFolder(folder.id)
                        }}
                      >
                        {folder.name}
                      </DropdownMenuItem>
                    ))}
                  </DropdownMenuSubContent>
                </DropdownMenuSub>
                <DropdownMenuSeparator />
                <DropdownMenuItem
                  onClick={(e) => {
                    e.preventDefault()
                    e.stopPropagation()
                    onDelete()
                  }}
                  className="text-destructive"
                >
                  <Trash2 className="h-3.5 w-3.5 mr-2" />
                  Delete
                </DropdownMenuItem>
              </DropdownMenuContent>
            </DropdownMenu>
          </div>
        </div>
        <div className="mt-3 flex items-center gap-2 flex-wrap">
          <span className="inline-flex items-center gap-1 rounded-full bg-muted px-2 py-0.5 text-[11px] font-medium text-muted-foreground">
            <LayoutDashboard className="h-3 w-3" />
            {widgetCount} widget{widgetCount !== 1 ? 's' : ''}
          </span>
          <span className="inline-flex items-center gap-1 rounded-full bg-muted px-2 py-0.5 text-[11px] text-muted-foreground">
            {isRecent ? 'Updated today' : `Updated ${updatedDate.toLocaleDateString()}`}
          </span>
        </div>
        <div className="mt-3 flex items-center text-xs text-primary opacity-0 group-hover:opacity-100 transition-opacity">
          Open dashboard
          <ArrowRight className="h-3 w-3 ml-1" />
        </div>
      </div>
    </Link>
  )
})
