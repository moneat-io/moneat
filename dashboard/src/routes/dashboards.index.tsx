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
  type DashboardTemplateSummary,
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
  ArrowRight,
} from 'lucide-react'
import {Button} from '@/components/ui/button'
import {Input} from '@/components/ui/input'
import {PageHeader} from '@/components/ui/page-header'
import {EmptyState} from '@/components/ui/empty-state'
import {useState, memo, useMemo, type MouseEvent, type ReactNode} from 'react'
import {ImportExportModal} from '@/components/dashboards/ImportExportModal'
import {DashboardsGetStarted} from '@/components/dashboards/DashboardsGetStarted'
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

type DashboardPageTitleProps = Readonly<{
  isFirstRun: boolean
}>

type DashboardHeaderActionsProps = Readonly<{
  onImport: () => void
  onCreateBlank: () => void
}>

type DashboardPageContentProps = Readonly<{
  isLoading: boolean
  isFirstRun: boolean
  templates: readonly DashboardTemplateSummary[]
  isTemplatesLoading: boolean
  onCreateBlank: () => void
  onUseTemplate: (templateId: string) => void
  onImport: () => void
  workspace: ReactNode
}>

type DashboardWorkspaceProps = Readonly<{
  dashboards: readonly CustomDashboard[]
  folders: readonly DashboardFolder[]
  filteredDashboards: readonly CustomDashboard[]
  favoritesCount: number
  uncategorizedCount: number
  selectedFolder: FolderFilter
  folderLabel: string
  isCreatingFolder: boolean
  newFolderName: string
  now: number
  onSelectFolder: (folder: FolderFilter) => void
  onStartCreatingFolder: () => void
  onCancelCreatingFolder: () => void
  onNewFolderNameChange: (name: string) => void
  onCreateFolder: (name: string) => void
  onDeleteFolder: (folderId: number) => void
  onCreateBlank: () => void
  onDeleteDashboard: (dashboardId: number) => void
  onDuplicateDashboard: (dashboardId: number) => void
  onFavoriteToggle: (dashboardId: number) => void
  onMoveToFolder: (dashboardId: number, folderId: number | null) => void
}>

type MobileFolderPillsProps = Readonly<{
  dashboards: readonly CustomDashboard[]
  folders: readonly DashboardFolder[]
  favoritesCount: number
  uncategorizedCount: number
  selectedFolder: FolderFilter
  onSelectFolder: (folder: FolderFilter) => void
}>

type FolderSidebarProps = Readonly<{
  dashboards: readonly CustomDashboard[]
  folders: readonly DashboardFolder[]
  favoritesCount: number
  uncategorizedCount: number
  selectedFolder: FolderFilter
  isCreatingFolder: boolean
  newFolderName: string
  onSelectFolder: (folder: FolderFilter) => void
  onDeleteFolder: (folderId: number) => void
  onStartCreatingFolder: () => void
  onCancelCreatingFolder: () => void
  onNewFolderNameChange: (name: string) => void
  onCreateFolder: (name: string) => void
}>

type CreateFolderControlProps = Readonly<{
  isCreating: boolean
  folderName: string
  onStart: () => void
  onCancel: () => void
  onNameChange: (name: string) => void
  onCreate: (name: string) => void
}>

type SidebarButtonProps = Readonly<{
  icon: ReactNode
  label: string
  count: number
  isSelected: boolean
  onClick: () => void
}>

type DashboardsEmptyStateProps = Readonly<{
  selectedFolder: FolderFilter
  folderLabel: string
  onCreateBlank: () => void
}>

type FolderSidebarItemProps = Readonly<{
  folder: DashboardFolder
  count: number
  isSelected: boolean
  onSelect: () => void
  onDelete: () => void
}>

type DashboardCardProps = Readonly<{
  dashboard: CustomDashboard
  folders: readonly DashboardFolder[]
  now: number
  onDelete: () => void
  onDuplicate: () => void
  onFavoriteToggle: () => void
  onMoveToFolder: (folderId: number | null) => void
}>

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

  const dashboardList = dashboards ?? []
  const folderList = folders ?? []

  // First run: no dashboards at all -> show the full-width template gallery
  // (no folder rail to organize an empty set).
  const isFirstRun = !isLoading && dashboardList.length === 0

  const {data: dashboardTemplates = [], isLoading: isTemplatesLoading} = useQuery({
    queryKey: ['dashboard-templates'],
    queryFn: () => api.getDashboardTemplates(),
    enabled: isFirstRun,
  })

  const filteredDashboards = useMemo(() => {
    return filterDashboards(dashboardList, selectedFolder)
  }, [dashboardList, selectedFolder])

  const createMutation = useMutation({
    mutationFn: (data: CreateDashboardRequest) => api.createDashboard(data),
    onSuccess: (dashboard) => {
      queryClient.invalidateQueries({queryKey: ['custom-dashboards']})
      navigate({to: '/dashboards/$dashboardId', params: {dashboardId: String(dashboard.id)}, search: {edit: true}})
    },
  })

  const createFromTemplateMutation = useMutation({
    mutationFn: (templateId: string) =>
      api.createDashboardFromTemplate(templateId, {
        folder_id: typeof selectedFolder === 'number' ? selectedFolder : undefined,
      }),
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

  const handleUseTemplate = (templateId: string) => {
    createFromTemplateMutation.mutate(templateId)
  }

  const handleCreateFolder = (name: string) => {
    createFolderMutation.mutate({name})
  }

  const handleDuplicateDashboard = (dashboardId: number) => {
    api.getDashboard(dashboardId).then((full) => {
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
  }

  const handleFavoriteToggle = async (dashboardId: number) => {
    await api.toggleDashboardFavorite(dashboardId)
    queryClient.invalidateQueries({queryKey: ['custom-dashboards']})
  }

  const handleMoveToFolder = async (dashboardId: number, folderId: number | null) => {
    await api.moveDashboardToFolder(dashboardId, folderId)
    queryClient.invalidateQueries({queryKey: ['custom-dashboards']})
  }

  const favoritesCount = countFavorites(dashboardList)
  const uncategorizedCount = countUncategorized(dashboardList)
  const folderLabel = getFolderLabel(selectedFolder, folderList)

  return (
    <div>
      {/* Page header */}
      <div className="border-b bg-card/50 px-3 sm:px-6 lg:px-8 py-3 sm:py-4">
        <PageHeader
          icon={LayoutDashboard}
          title={<DashboardPageTitle isFirstRun={isFirstRun} />}
          description={getDashboardPageDescription(isFirstRun)}
          actions={
            <DashboardHeaderActions
              onImport={() => setShowImport(true)}
              onCreateBlank={handleCreateBlank}
            />
          }
        />
      </div>

      <div className="px-3 sm:px-6 lg:px-8 py-3 sm:py-4">
        <DashboardPageContent
          isLoading={isLoading}
          isFirstRun={isFirstRun}
          templates={dashboardTemplates}
          isTemplatesLoading={isTemplatesLoading}
          onCreateBlank={handleCreateBlank}
          onUseTemplate={handleUseTemplate}
          onImport={() => setShowImport(true)}
          workspace={
            <DashboardWorkspace
              dashboards={dashboardList}
              folders={folderList}
              filteredDashboards={filteredDashboards}
              favoritesCount={favoritesCount}
              uncategorizedCount={uncategorizedCount}
              selectedFolder={selectedFolder}
              folderLabel={folderLabel}
              isCreatingFolder={isCreatingFolder}
              newFolderName={newFolderName}
              now={now}
              onSelectFolder={setSelectedFolder}
              onStartCreatingFolder={() => setIsCreatingFolder(true)}
              onCancelCreatingFolder={() => {
                setIsCreatingFolder(false)
                setNewFolderName('')
              }}
              onNewFolderNameChange={setNewFolderName}
              onCreateFolder={handleCreateFolder}
              onDeleteFolder={(folderId) => deleteFolderMutation.mutate(folderId)}
              onCreateBlank={handleCreateBlank}
              onDeleteDashboard={(dashboardId) => deleteMutation.mutate(dashboardId)}
              onDuplicateDashboard={handleDuplicateDashboard}
              onFavoriteToggle={handleFavoriteToggle}
              onMoveToFolder={handleMoveToFolder}
            />
          }
        />
      </div>

      <ImportExportModal open={showImport} onOpenChange={setShowImport} mode="import" />
    </div>
  )
}

function filterDashboards(
  dashboards: readonly CustomDashboard[],
  selectedFolder: FolderFilter,
): readonly CustomDashboard[] {
  if (selectedFolder === 'all') return dashboards
  if (selectedFolder === 'favorites') return dashboards.filter((dashboard) => dashboard.is_favorited)
  if (selectedFolder === 'uncategorized') return dashboards.filter((dashboard) => !dashboard.folder_id)
  return dashboards.filter((dashboard) => dashboard.folder_id === selectedFolder)
}

function countFavorites(dashboards: readonly CustomDashboard[]) {
  return dashboards.filter((dashboard) => dashboard.is_favorited).length
}

function countUncategorized(dashboards: readonly CustomDashboard[]) {
  return dashboards.filter((dashboard) => !dashboard.folder_id).length
}

function countDashboardsInFolder(dashboards: readonly CustomDashboard[], folderId: number) {
  return dashboards.filter((dashboard) => dashboard.folder_id === folderId).length
}

function getFolderLabel(selectedFolder: FolderFilter, folders: readonly DashboardFolder[]) {
  if (selectedFolder === 'all') return 'All'
  if (selectedFolder === 'favorites') return 'Favorites'
  if (selectedFolder === 'uncategorized') return 'Uncategorized'
  return folders.find((folder) => folder.id === selectedFolder)?.name ?? 'Folder'
}

function getDashboardPageDescription(isFirstRun: boolean) {
  if (isFirstRun) {
    return 'Compose charts, tables, gauges and KPIs across your telemetry.'
  }
  return 'Build custom dashboards with drag-and-drop widgets'
}

function DashboardPageTitle({isFirstRun}: DashboardPageTitleProps) {
  if (!isFirstRun) return 'Dashboards'

  return (
    <span className="inline-flex items-baseline gap-2">
      <span>Dashboards</span>
      <span className="rounded-full border border-border bg-muted px-2 py-0.5 align-middle text-[10px] font-medium tabular-nums text-muted-foreground">
        0 dashboards
      </span>
    </span>
  )
}

function DashboardHeaderActions({onImport, onCreateBlank}: DashboardHeaderActionsProps) {
  return (
    <>
      <Button asChild variant="outline" size="sm" className="gap-1 text-xs h-8">
        <Link to="/dashboards/datasources" title="Data Sources">
          <Database className="h-3 w-3 shrink-0" />
          <span className="hidden sm:inline">Data Sources</span>
        </Link>
      </Button>
      <Button variant="outline" size="sm" onClick={onImport} className="gap-1 text-xs h-8">
        <Import className="h-3 w-3 shrink-0" />
        Import
      </Button>
      <Button size="sm" onClick={onCreateBlank} className="gap-1 text-xs h-8">
        <Plus className="h-3 w-3 shrink-0" />
        <span className="hidden sm:inline">New Dashboard</span>
        <span className="sm:hidden">New</span>
      </Button>
    </>
  )
}

function DashboardPageContent({
  isLoading,
  isFirstRun,
  templates,
  isTemplatesLoading,
  onCreateBlank,
  onUseTemplate,
  onImport,
  workspace,
}: DashboardPageContentProps) {
  if (isLoading) {
    return <DashboardGridSkeleton />
  }

  if (isFirstRun) {
    return (
      <DashboardsGetStarted
        templates={templates}
        isLoadingTemplates={isTemplatesLoading}
        onCreateBlank={onCreateBlank}
        onUseTemplate={onUseTemplate}
        onImport={onImport}
      />
    )
  }

  return workspace
}

function DashboardWorkspace({
  dashboards,
  folders,
  filteredDashboards,
  favoritesCount,
  uncategorizedCount,
  selectedFolder,
  folderLabel,
  isCreatingFolder,
  newFolderName,
  now,
  onSelectFolder,
  onStartCreatingFolder,
  onCancelCreatingFolder,
  onNewFolderNameChange,
  onCreateFolder,
  onDeleteFolder,
  onCreateBlank,
  onDeleteDashboard,
  onDuplicateDashboard,
  onFavoriteToggle,
  onMoveToFolder,
}: DashboardWorkspaceProps) {
  return (
    <div className="flex flex-col md:flex-row gap-4">
      <MobileFolderPills
        dashboards={dashboards}
        folders={folders}
        favoritesCount={favoritesCount}
        uncategorizedCount={uncategorizedCount}
        selectedFolder={selectedFolder}
        onSelectFolder={onSelectFolder}
      />

      <FolderSidebar
        dashboards={dashboards}
        folders={folders}
        favoritesCount={favoritesCount}
        uncategorizedCount={uncategorizedCount}
        selectedFolder={selectedFolder}
        isCreatingFolder={isCreatingFolder}
        newFolderName={newFolderName}
        onSelectFolder={onSelectFolder}
        onDeleteFolder={onDeleteFolder}
        onStartCreatingFolder={onStartCreatingFolder}
        onCancelCreatingFolder={onCancelCreatingFolder}
        onNewFolderNameChange={onNewFolderNameChange}
        onCreateFolder={onCreateFolder}
      />

      <div className="flex-1 min-w-0">
        {filteredDashboards.length > 0 ? (
          <div className="grid grid-cols-1 md:grid-cols-2 xl:grid-cols-3 gap-3">
            {filteredDashboards.map((dashboard) => (
              <DashboardCard
                key={dashboard.id}
                dashboard={dashboard}
                folders={folders}
                now={now}
                onDelete={() => onDeleteDashboard(dashboard.id)}
                onDuplicate={() => onDuplicateDashboard(dashboard.id)}
                onFavoriteToggle={() => onFavoriteToggle(dashboard.id)}
                onMoveToFolder={(folderId) => onMoveToFolder(dashboard.id, folderId)}
              />
            ))}
          </div>
        ) : (
          <DashboardsEmptyState
            selectedFolder={selectedFolder}
            folderLabel={folderLabel}
            onCreateBlank={onCreateBlank}
          />
        )}
      </div>
    </div>
  )
}

function MobileFolderPills({
  dashboards,
  folders,
  favoritesCount,
  uncategorizedCount,
  selectedFolder,
  onSelectFolder,
}: MobileFolderPillsProps) {
  return (
    <div className="flex md:hidden overflow-x-auto gap-1.5 pb-1 -mx-1">
      <button
        onClick={() => onSelectFolder('all')}
        className={getFolderPillClassName(selectedFolder === 'all')}
      >
        All ({dashboards.length})
      </button>
      <button
        onClick={() => onSelectFolder('favorites')}
        className={getFolderPillClassName(selectedFolder === 'favorites', 'flex items-center gap-1')}
      >
        <Star className="h-3 w-3" /> {favoritesCount}
      </button>
      {folders.map((folder) => {
        const count = countDashboardsInFolder(dashboards, folder.id)
        return (
          <button
            key={folder.id}
            onClick={() => onSelectFolder(folder.id)}
            className={getFolderPillClassName(selectedFolder === folder.id, 'truncate max-w-32')}
          >
            {folder.name} ({count})
          </button>
        )
      })}
      <button
        onClick={() => onSelectFolder('uncategorized')}
        className={getFolderPillClassName(selectedFolder === 'uncategorized')}
      >
        Uncategorized ({uncategorizedCount})
      </button>
    </div>
  )
}

function getFolderPillClassName(isSelected: boolean, extraClassName = '') {
  const base = 'shrink-0 rounded-full px-3 py-1.5 text-xs font-medium transition-colors'
  const color = isSelected ? 'bg-primary text-primary-foreground' : 'bg-muted text-muted-foreground'
  return [base, color, extraClassName].filter(Boolean).join(' ')
}

function FolderSidebar({
  dashboards,
  folders,
  favoritesCount,
  uncategorizedCount,
  selectedFolder,
  isCreatingFolder,
  newFolderName,
  onSelectFolder,
  onDeleteFolder,
  onStartCreatingFolder,
  onCancelCreatingFolder,
  onNewFolderNameChange,
  onCreateFolder,
}: FolderSidebarProps) {
  return (
    <aside className="hidden md:block w-52 shrink-0 space-y-0.5">
      <div className="text-[11px] font-semibold text-muted-foreground uppercase tracking-widest px-2.5 pb-2">
        Folders
      </div>
      <SidebarButton
        icon={<LayoutDashboard className="h-4 w-4 shrink-0" />}
        label="All"
        count={dashboards.length}
        isSelected={selectedFolder === 'all'}
        onClick={() => onSelectFolder('all')}
      />
      <SidebarButton
        icon={<Star className="h-4 w-4 shrink-0" />}
        label="Favorites"
        count={favoritesCount}
        isSelected={selectedFolder === 'favorites'}
        onClick={() => onSelectFolder('favorites')}
      />
      {folders.map((folder) => (
        <FolderSidebarItem
          key={folder.id}
          folder={folder}
          count={countDashboardsInFolder(dashboards, folder.id)}
          isSelected={selectedFolder === folder.id}
          onSelect={() => onSelectFolder(folder.id)}
          onDelete={() => onDeleteFolder(folder.id)}
        />
      ))}
      <SidebarButton
        icon={<Folder className="h-4 w-4 shrink-0" />}
        label="Uncategorized"
        count={uncategorizedCount}
        isSelected={selectedFolder === 'uncategorized'}
        onClick={() => onSelectFolder('uncategorized')}
      />
      <CreateFolderControl
        isCreating={isCreatingFolder}
        folderName={newFolderName}
        onStart={onStartCreatingFolder}
        onCancel={onCancelCreatingFolder}
        onNameChange={onNewFolderNameChange}
        onCreate={onCreateFolder}
      />
    </aside>
  )
}

function CreateFolderControl({
  isCreating,
  folderName,
  onStart,
  onCancel,
  onNameChange,
  onCreate,
}: CreateFolderControlProps) {
  const createTrimmedFolder = () => {
    const trimmed = folderName.trim()
    if (trimmed) onCreate(trimmed)
  }

  if (!isCreating) {
    return (
      <div className="pt-1">
        <button
          onClick={onStart}
          className="flex w-full items-center gap-2 rounded-md px-2.5 py-1.5 text-sm text-muted-foreground hover:bg-muted hover:text-foreground transition-colors"
        >
          <FolderPlus className="h-4 w-4" />
          New folder
        </button>
      </div>
    )
  }

  return (
    <div className="flex items-center gap-1 px-2 py-1 pt-1">
      <Input
        placeholder="Folder name"
        value={folderName}
        onChange={(event) => onNameChange(event.target.value)}
        onKeyDown={(event) => {
          if (event.key === 'Enter') createTrimmedFolder()
          if (event.key === 'Escape') onCancel()
        }}
        className="h-7 text-sm"
        autoFocus
      />
      <Button size="sm" variant="ghost" className="h-7 px-2" onClick={createTrimmedFolder}>
        <ChevronRight className="h-4 w-4" />
      </Button>
    </div>
  )
}

function SidebarButton({
  icon,
  label,
  count,
  isSelected,
  onClick,
}: SidebarButtonProps) {
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

// Decorative per-card accent bars cycle the categorical chart palette (literal
// classes so Tailwind emits them); they encode identity, not status.
const CARD_ACCENT_COLORS = [
  'from-chart-1 to-chart-1/70',
  'from-chart-2 to-chart-2/70',
  'from-chart-3 to-chart-3/70',
  'from-chart-4 to-chart-4/70',
  'from-chart-5 to-chart-5/70',
  'from-chart-6 to-chart-6/70',
]

function getAccentColor(id: number) {
  return CARD_ACCENT_COLORS[id % CARD_ACCENT_COLORS.length]
}

function DashboardsEmptyState({
  selectedFolder,
  folderLabel,
  onCreateBlank,
}: DashboardsEmptyStateProps) {
  if (selectedFolder === 'favorites') {
    return (
      <EmptyState
        icon={Star}
        title="No favorites yet"
        description="Star dashboards you use often for quick access. Click the star icon on any dashboard card to add it here."
      />
    )
  }

  if (selectedFolder === 'uncategorized') {
    return (
      <EmptyState
        icon={Folder}
        title="No uncategorized dashboards"
        description="Dashboards that haven't been moved into a folder will appear here."
        action={
          <Button onClick={onCreateBlank}>
            <Plus className="h-4 w-4 mr-1.5" />
            Create Dashboard
          </Button>
        }
      />
    )
  }

  if (typeof selectedFolder === 'number') {
    return (
      <EmptyState
        icon={Folder}
        title={`${folderLabel} is empty`}
        description="Move existing dashboards here or create a new one to get started."
        action={
          <Button onClick={onCreateBlank}>
            <Plus className="h-4 w-4 mr-1.5" />
            Create Dashboard
          </Button>
        }
      />
    )
  }

  return (
    <EmptyState
      icon={LayoutDashboard}
      title="No dashboards here"
      description="Create a dashboard to start visualizing your telemetry."
      action={
        <Button onClick={onCreateBlank}>
          <Plus className="h-4 w-4 mr-1.5" />
          Create Dashboard
        </Button>
      }
    />
  )
}

/** Compact loading placeholder for the dashboard grid. */
function DashboardGridSkeleton() {
  return (
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
  )
}

const FolderSidebarItem = memo(function FolderSidebarItem({
  folder,
  count,
  isSelected,
  onSelect,
  onDelete,
}: FolderSidebarItemProps) {
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
}: DashboardCardProps) {
  const handleFavorite = (e: MouseEvent) => {
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
      className="group relative block rounded-lg border bg-card overflow-hidden hover:border-primary/40 transition-all duration-200"
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
                className={`h-4 w-4 ${dashboard.is_favorited ? 'fill-warning-solid text-warning-fg' : 'text-muted-foreground'}`}
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
