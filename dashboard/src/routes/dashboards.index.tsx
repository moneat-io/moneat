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

import {createFileRoute, useNavigate} from '@tanstack/react-router'
import {useMutation, useQuery, useQueryClient} from '@tanstack/react-query'
import {useEffect, useRef, useState, type ReactNode} from 'react'
import {
  ChevronDown,
  Database,
  FileJson,
  FolderPlus,
  LayoutDashboard,
  LayoutTemplate,
  Plus,
  Search,
} from 'lucide-react'
import {api, type CreateDashboardRequest, type CreateFolderRequest} from '@/lib/api'
import {Button} from '@/components/ui/button'
import {
  DropdownMenu,
  DropdownMenuContent,
  DropdownMenuItem,
  DropdownMenuSeparator,
  DropdownMenuTrigger,
} from '@/components/ui/dropdown-menu'
import {cn} from '@/lib/utils'
import {ImportExportModal} from '@/components/dashboards/ImportExportModal'
import {
  DashboardsBoardsPanel,
  type FolderFilter,
} from '@/components/dashboards/DashboardsBoardsPanel'
import {DashboardsTemplatesPanel} from '@/components/dashboards/DashboardsTemplatesPanel'
import {DashboardsSourcesPanel} from '@/components/dashboards/DashboardsSourcesPanel'

export const Route = createFileRoute('/dashboards/')({
  component: DashboardsHubPage,
})

type TabKey = 'boards' | 'templates' | 'sources'

const SEARCH_PLACEHOLDER: Record<TabKey, string> = {
  boards: 'Search dashboards…',
  templates: 'Search templates…',
  sources: 'Search data sources…',
}

function DashboardsHubPage() {
  const navigate = useNavigate()
  const queryClient = useQueryClient()

  const [activeTab, setActiveTab] = useState<TabKey | null>(null)
  const [search, setSearch] = useState('')
  const [showImport, setShowImport] = useState(false)
  const [now] = useState(() => Date.now())
  const [isCreatingFolder, setIsCreatingFolder] = useState(false)
  const [newFolderName, setNewFolderName] = useState('')
  const [selectedFolder, setSelectedFolder] = useState<FolderFilter>('all')

  const {data: dashboards, isLoading} = useQuery({
    queryKey: ['custom-dashboards'],
    queryFn: () => api.getDashboards(),
  })

  const {data: folders} = useQuery({
    queryKey: ['dashboard-folders'],
    queryFn: () => api.getDashboardFolders(),
  })

  const {data: templates = [], isLoading: isTemplatesLoading} = useQuery({
    queryKey: ['dashboard-templates'],
    queryFn: () => api.getDashboardTemplates(),
  })

  const {data: dataSources = [], isLoading: isDataSourcesLoading} = useQuery({
    queryKey: ['custom-datasources'],
    queryFn: () => api.listCustomDataSources(),
  })

  const dashboardList = dashboards ?? []
  const folderList = folders ?? []
  const isFirstRun = !isLoading && dashboardList.length === 0
  const resolvedTab: TabKey = activeTab ?? (isFirstRun ? 'templates' : 'boards')

  const createMutation = useMutation({
    mutationFn: (data: CreateDashboardRequest) => api.createDashboard(data),
    onSuccess: (dashboard) => {
      queryClient.invalidateQueries({queryKey: ['custom-dashboards']})
      navigate({
        to: '/dashboards/$dashboardId',
        params: {dashboardId: dashboard.id},
        search: {edit: true},
      })
    },
  })

  const createFromTemplateMutation = useMutation({
    mutationFn: ({templateId, folderId}: {templateId: string; folderId?: string}) =>
      api.createDashboardFromTemplate(templateId, {folder_id: folderId}),
    onSuccess: (dashboard) => {
      queryClient.invalidateQueries({queryKey: ['custom-dashboards']})
      navigate({
        to: '/dashboards/$dashboardId',
        params: {dashboardId: dashboard.id},
        search: {edit: true},
      })
    },
  })

  const deleteMutation = useMutation({
    mutationFn: (id: string) => api.deleteDashboard(id),
    onSuccess: () => queryClient.invalidateQueries({queryKey: ['custom-dashboards']}),
  })

  const duplicateMutation = useMutation({
    mutationFn: (id: string) => api.duplicateDashboard(id),
    onSuccess: (dashboard) => {
      queryClient.invalidateQueries({queryKey: ['custom-dashboards']})
      navigate({
        to: '/dashboards/$dashboardId',
        params: {dashboardId: dashboard.id},
        search: {edit: true},
      })
    },
  })

  const setDefaultMutation = useMutation({
    mutationFn: (id: string) => api.setDefaultDashboard(id),
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
    mutationFn: (id: string) => api.deleteDashboardFolder(id),
    onSuccess: () => {
      queryClient.invalidateQueries({queryKey: ['dashboard-folders']})
      queryClient.invalidateQueries({queryKey: ['custom-dashboards']})
      if (isConcreteFolder(selectedFolder)) setSelectedFolder('all')
    },
  })

  const renameFolderMutation = useMutation({
    mutationFn: ({id, name}: {id: string; name: string}) => api.updateDashboardFolder(id, {name}),
    onSuccess: () => queryClient.invalidateQueries({queryKey: ['dashboard-folders']}),
  })

  const handleCreateBlank = (folderId?: string) => {
    const targetFolderId = folderId ?? activeFolderId(selectedFolder)
    createMutation.mutate({title: 'New Dashboard', folder_id: targetFolderId, widgets: []})
  }

  const handleUseTemplate = (templateId: string) => {
    createFromTemplateMutation.mutate({templateId, folderId: activeFolderId(selectedFolder)})
  }

  const handleDuplicate = (dashboardId: string) => {
    duplicateMutation.mutate(dashboardId)
  }

  const handleSetDefault = (dashboardId: string) => {
    setDefaultMutation.mutate(dashboardId)
  }

  const handleFavoriteToggle = async (dashboardId: string) => {
    await api.toggleDashboardFavorite(dashboardId)
    queryClient.invalidateQueries({queryKey: ['custom-dashboards']})
  }

  const handleMoveToFolder = async (dashboardId: string, folderId: string | null) => {
    await api.moveDashboardToFolder(dashboardId, folderId)
    queryClient.invalidateQueries({queryKey: ['custom-dashboards']})
  }

  const goToAddDataSource = () => navigate({to: '/dashboards/datasources', search: {new: 1}})

  const goToManageDataSource = (id: string) =>
    navigate({to: '/dashboards/datasources', search: {edit: id}})

  const handleNewFolderFromMenu = () => {
    setActiveTab('boards')
    setIsCreatingFolder(true)
  }

  return (
    <div className="flex h-full flex-col">
      <header className="shrink-0 border-b bg-card/50 px-4 pt-3 lg:px-6">
        <div className="flex flex-wrap items-center gap-3">
          <div className="flex min-w-0 items-center gap-2">
            <span className="flex h-[30px] w-[30px] items-center justify-center rounded-md border border-accent-subtle-border bg-accent-subtle-bg text-accent-subtle-fg">
              <LayoutDashboard className="h-4 w-4" />
            </span>
            <h1 className="text-lg font-semibold tracking-tight text-foreground">Dashboards</h1>
            <span className="rounded-full border border-border bg-muted px-2 py-0.5 text-[11px] font-semibold tabular-nums text-muted-foreground">
              {dashboardList.length} yours · {templates.length} templates
            </span>
          </div>

          <div className="ml-auto flex items-center gap-2">
            <HubSearch
              value={search}
              onChange={setSearch}
              placeholder={SEARCH_PLACEHOLDER[resolvedTab]}
            />
            <NewDashboardButton
              onCreateBlank={() => handleCreateBlank()}
              onFromTemplate={() => setActiveTab('templates')}
              onImport={() => setShowImport(true)}
              onNewFolder={handleNewFolderFromMenu}
            />
          </div>
        </div>

        <div className="-mb-px mt-3 flex items-center gap-1 overflow-x-auto">
          <HubTab
            icon={<LayoutDashboard className="h-4 w-4" />}
            label="Your dashboards"
            count={dashboardList.length}
            active={resolvedTab === 'boards'}
            onClick={() => setActiveTab('boards')}
          />
          <HubTab
            icon={<LayoutTemplate className="h-4 w-4" />}
            label="Templates"
            count={templates.length}
            active={resolvedTab === 'templates'}
            onClick={() => setActiveTab('templates')}
          />
          <HubTab
            icon={<Database className="h-4 w-4" />}
            label="Data sources"
            count={dataSources.length + 1}
            active={resolvedTab === 'sources'}
            onClick={() => setActiveTab('sources')}
          />
        </div>
      </header>

      <div className="flex-1 overflow-y-auto px-4 py-4 lg:px-6">
        {resolvedTab === 'boards' && (
          <DashboardsBoardsPanel
            dashboards={dashboardList}
            folders={folderList}
            searchQuery={search}
            now={now}
            isCreatingFolder={isCreatingFolder}
            newFolderName={newFolderName}
            selectedFolder={selectedFolder}
            onSelectFolder={setSelectedFolder}
            onCreateBlank={handleCreateBlank}
            onDeleteDashboard={(id) => deleteMutation.mutate(id)}
            onDuplicateDashboard={handleDuplicate}
            onSetDefaultDashboard={handleSetDefault}
            onFavoriteToggle={handleFavoriteToggle}
            onMoveToFolder={handleMoveToFolder}
            onStartCreateFolder={() => setIsCreatingFolder(true)}
            onCancelCreateFolder={() => {
              setIsCreatingFolder(false)
              setNewFolderName('')
            }}
            onNewFolderNameChange={setNewFolderName}
            onCreateFolder={(name) => createFolderMutation.mutate({name})}
            onRenameFolder={(id, name) => renameFolderMutation.mutate({id, name})}
            onDeleteFolder={(id) => deleteFolderMutation.mutate(id)}
          />
        )}

        {resolvedTab === 'templates' && (
          <DashboardsTemplatesPanel
            templates={templates}
            isLoading={isTemplatesLoading}
            searchQuery={search}
            onUseTemplate={handleUseTemplate}
            onCreateBlank={() => handleCreateBlank()}
            onImport={() => setShowImport(true)}
          />
        )}

        {resolvedTab === 'sources' && (
          <DashboardsSourcesPanel
            dashboards={dashboardList}
            dataSources={dataSources}
            isLoading={isDataSourcesLoading}
            searchQuery={search}
            onAdd={goToAddDataSource}
            onManage={goToManageDataSource}
          />
        )}
      </div>

      <ImportExportModal open={showImport} onOpenChange={setShowImport} mode="import" />
    </div>
  )
}

function activeFolderId(selectedFolder: FolderFilter): string | undefined {
  return isConcreteFolder(selectedFolder) ? selectedFolder : undefined
}

function isConcreteFolder(selectedFolder: FolderFilter): selectedFolder is string {
  return !['all', 'favorites', 'uncategorized'].includes(selectedFolder)
}

function HubSearch({
  value,
  onChange,
  placeholder,
}: Readonly<{value: string; onChange: (value: string) => void; placeholder: string}>) {
  const inputRef = useRef<HTMLInputElement>(null)

  useEffect(() => {
    const onKeyDown = (event: KeyboardEvent) => {
      if (event.key !== '/') return
      const el = document.activeElement
      const typing =
        el instanceof HTMLInputElement ||
        el instanceof HTMLTextAreaElement ||
        (el instanceof HTMLElement && el.isContentEditable)
      if (typing) return
      event.preventDefault()
      inputRef.current?.focus()
    }
    document.addEventListener('keydown', onKeyDown)
    return () => document.removeEventListener('keydown', onKeyDown)
  }, [])

  return (
    <label className="flex h-[30px] w-[230px] items-center gap-2 rounded-md border bg-background px-2.5 text-muted-foreground focus-within:border-primary focus-within:ring-2 focus-within:ring-primary/20">
      <Search className="h-4 w-4 shrink-0" />
      <input
        ref={inputRef}
        type="search"
        value={value}
        onChange={(event) => onChange(event.target.value)}
        placeholder={placeholder}
        className="min-w-0 flex-1 border-0 bg-transparent text-sm text-foreground outline-none placeholder:text-muted-foreground/70"
      />
      <kbd className="flex h-[18px] shrink-0 items-center rounded border bg-muted px-1 font-mono text-[10px] text-muted-foreground">
        /
      </kbd>
    </label>
  )
}

function NewDashboardButton({
  onCreateBlank,
  onFromTemplate,
  onImport,
  onNewFolder,
}: Readonly<{
  onCreateBlank: () => void
  onFromTemplate: () => void
  onImport: () => void
  onNewFolder: () => void
}>) {
  return (
    <div className="inline-flex">
      <Button size="sm" onClick={() => onCreateBlank()} className="gap-1.5 rounded-r-none">
        <Plus className="h-4 w-4" />
        <span className="hidden sm:inline">New dashboard</span>
        <span className="sm:hidden">New</span>
      </Button>
      <DropdownMenu>
        <DropdownMenuTrigger asChild>
          <Button
            size="sm"
            aria-label="More create options"
            className="rounded-l-none border-l border-primary-foreground/25 px-2"
          >
            <ChevronDown className="h-4 w-4" />
          </Button>
        </DropdownMenuTrigger>
        <DropdownMenuContent align="end" className="w-60">
          <DropdownMenuItem onClick={() => onCreateBlank()}>
            <Plus className="mr-2 h-4 w-4" />
            <span className="flex flex-col">
              <span>Blank dashboard</span>
              <span className="text-xs text-muted-foreground">Empty grid, drag in widgets</span>
            </span>
          </DropdownMenuItem>
          <DropdownMenuItem onClick={onFromTemplate}>
            <LayoutTemplate className="mr-2 h-4 w-4" />
            <span className="flex flex-col">
              <span>From a template</span>
              <span className="text-xs text-muted-foreground">Prebuilt panel sets</span>
            </span>
          </DropdownMenuItem>
          <DropdownMenuItem onClick={onImport}>
            <FileJson className="mr-2 h-4 w-4" />
            <span className="flex flex-col">
              <span>Import JSON</span>
              <span className="text-xs text-muted-foreground">Grafana-compatible export</span>
            </span>
          </DropdownMenuItem>
          <DropdownMenuSeparator />
          <DropdownMenuItem onClick={onNewFolder}>
            <FolderPlus className="mr-2 h-4 w-4" />
            New folder
          </DropdownMenuItem>
        </DropdownMenuContent>
      </DropdownMenu>
    </div>
  )
}

function HubTab({
  icon,
  label,
  count,
  active,
  onClick,
}: Readonly<{icon: ReactNode; label: string; count: number; active: boolean; onClick: () => void}>) {
  return (
    <button
      type="button"
      aria-pressed={active}
      onClick={onClick}
      className={cn(
        'inline-flex h-[38px] items-center gap-2 whitespace-nowrap border-b-2 px-3 text-sm font-medium transition-colors',
        active
          ? 'border-primary text-foreground'
          : 'border-transparent text-muted-foreground hover:text-foreground',
      )}
    >
      {icon}
      {label}
      <span
        className={cn(
          'rounded-full border px-[7px] py-px text-[11px] font-semibold tabular-nums',
          active
            ? 'border-accent-subtle-border bg-accent-subtle-bg text-accent-subtle-fg'
            : 'border-border bg-muted text-muted-foreground',
        )}
      >
        {count}
      </span>
    </button>
  )
}
