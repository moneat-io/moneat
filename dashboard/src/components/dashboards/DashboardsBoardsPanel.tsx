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

import {memo, useMemo, useState, type MouseEvent, type ReactNode} from 'react'
import {Link} from '@tanstack/react-router'
import {
  ArrowUpDown,
  ChevronRight,
  Copy,
  Folder,
  FolderPlus,
  Home,
  LayoutDashboard,
  LayoutGrid,
  List,
  MoreHorizontal,
  Pencil,
  Star,
  Trash2,
} from 'lucide-react'
import {Button} from '@/components/ui/button'
import {Input} from '@/components/ui/input'
import {EmptyState} from '@/components/ui/empty-state'
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
import type {CustomDashboard, DashboardFolder} from '@/lib/api'
import {cn} from '@/lib/utils'
import {DashboardThumb, SparkThumb} from './DashboardThumb'
import {getDashboardThumb, getDashboardSources} from './dashboardThumbHelpers'

// "Your dashboards" tab of the hub: a folder rail beside a dense, sortable list
// (preview · name · widgets · updated · owner) that toggles to a preview-led grid.

type SystemFolderFilter = 'all' | 'favorites' | 'uncategorized'
type FolderIdFilter = string & {readonly __folderIdFilter: unique symbol}
export type FolderFilter = SystemFolderFilter | FolderIdFilter
type SortKey = 'updated' | 'name' | 'widgets' | 'created'
type ViewMode = 'list' | 'grid'

const SYSTEM_FOLDER_FILTERS = new Set<SystemFolderFilter>(['all', 'favorites', 'uncategorized'])
const SORT_LABELS: Record<SortKey, string> = {
  updated: 'Recently updated',
  name: 'Name (A–Z)',
  widgets: 'Most widgets',
  created: 'Recently created',
}

const LIST_GRID = 'grid-cols-[26px_56px_1fr_78px_104px_44px_30px]'

// Categorical palette for folder swatches / owner avatars (identity, not status).
const SWATCHES = [
  'hsl(var(--chart-4))',
  'hsl(var(--chart-2))',
  'hsl(var(--chart-9))',
  'hsl(var(--chart-3))',
  'hsl(var(--chart-6))',
  'hsl(var(--chart-1))',
  'hsl(var(--chart-5))',
] as const

type DashboardsBoardsPanelProps = Readonly<{
  dashboards: readonly CustomDashboard[]
  folders: readonly DashboardFolder[]
  searchQuery: string
  now: number
  isCreatingFolder: boolean
  newFolderName: string
  selectedFolder: FolderFilter
  onSelectFolder: (folder: FolderFilter) => void
  onCreateBlank: (folderId?: string) => void
  onDeleteDashboard: (id: string) => void
  onDuplicateDashboard: (id: string) => void
  onSetDefaultDashboard: (id: string) => void
  onFavoriteToggle: (id: string) => void
  onMoveToFolder: (id: string, folderId: string | null) => void
  onStartCreateFolder: () => void
  onCancelCreateFolder: () => void
  onNewFolderNameChange: (name: string) => void
  onCreateFolder: (name: string) => void
  onRenameFolder: (id: string, name: string) => void
  onDeleteFolder: (id: string) => void
}>

export function DashboardsBoardsPanel({
  dashboards,
  folders,
  searchQuery,
  now,
  isCreatingFolder,
  newFolderName,
  selectedFolder,
  onSelectFolder,
  onCreateBlank,
  onDeleteDashboard,
  onDuplicateDashboard,
  onSetDefaultDashboard,
  onFavoriteToggle,
  onMoveToFolder,
  onStartCreateFolder,
  onCancelCreateFolder,
  onNewFolderNameChange,
  onCreateFolder,
  onRenameFolder,
  onDeleteFolder,
}: DashboardsBoardsPanelProps) {
  const [sort, setSort] = useState<SortKey>('updated')
  const [view, setView] = useState<ViewMode>('list')

  const folderName = useMemo(
    () => getFolderName(selectedFolder, folders),
    [selectedFolder, folders],
  )

  const visible = useMemo(() => {
    const byFolder = filterByFolder(dashboards, selectedFolder)
    const bySearch = filterBySearch(byFolder, searchQuery)
    return sortDashboards(bySearch, sort, now)
  }, [dashboards, selectedFolder, searchQuery, sort, now])

  let panelContent: ReactNode
  if (visible.length === 0) {
    panelContent = (
      <BoardsEmptyState
        selectedFolder={selectedFolder}
        folderName={folderName}
        hasSearch={searchQuery.trim().length > 0}
        onCreateBlank={onCreateBlank}
      />
    )
  } else if (view === 'list') {
    panelContent = (
      <ListView
        dashboards={visible}
        folders={folders}
        now={now}
        onDelete={onDeleteDashboard}
        onDuplicate={onDuplicateDashboard}
        onSetDefault={onSetDefaultDashboard}
        onFavoriteToggle={onFavoriteToggle}
        onMoveToFolder={onMoveToFolder}
      />
    )
  } else {
    panelContent = (
      <GridView
        dashboards={visible}
        folders={folders}
        now={now}
        onDelete={onDeleteDashboard}
        onDuplicate={onDuplicateDashboard}
        onSetDefault={onSetDefaultDashboard}
        onFavoriteToggle={onFavoriteToggle}
        onMoveToFolder={onMoveToFolder}
      />
    )
  }

  return (
    <div className="flex items-start gap-4">
      <FolderRail
        dashboards={dashboards}
        folders={folders}
        selectedFolder={selectedFolder}
        isCreatingFolder={isCreatingFolder}
        newFolderName={newFolderName}
        onSelectFolder={onSelectFolder}
        onStartCreateFolder={onStartCreateFolder}
        onCancelCreateFolder={onCancelCreateFolder}
        onNewFolderNameChange={onNewFolderNameChange}
        onCreateFolder={onCreateFolder}
        onRenameFolder={onRenameFolder}
        onDeleteFolder={onDeleteFolder}
      />

      <div className="min-w-0 flex-1">
        <div className="mb-3 flex flex-wrap items-center gap-2">
          <span className="text-xs text-muted-foreground">
            <b className="text-foreground">{folderName}</b> · {visible.length} dashboard
            {visible.length === 1 ? '' : 's'}
          </span>
          <div className="ml-auto flex items-center gap-2">
            <SortMenu sort={sort} onChange={setSort} />
            <ViewToggle view={view} onChange={setView} />
          </div>
        </div>

        {panelContent}
      </div>
    </div>
  )
}

// ---- Folder rail ----------------------------------------------------------

type FolderRailProps = Readonly<{
  dashboards: readonly CustomDashboard[]
  folders: readonly DashboardFolder[]
  selectedFolder: FolderFilter
  isCreatingFolder: boolean
  newFolderName: string
  onSelectFolder: (folder: FolderFilter) => void
  onStartCreateFolder: () => void
  onCancelCreateFolder: () => void
  onNewFolderNameChange: (name: string) => void
  onCreateFolder: (name: string) => void
  onRenameFolder: (id: string, name: string) => void
  onDeleteFolder: (id: string) => void
}>

function FolderRail({
  dashboards,
  folders,
  selectedFolder,
  isCreatingFolder,
  newFolderName,
  onSelectFolder,
  onStartCreateFolder,
  onCancelCreateFolder,
  onNewFolderNameChange,
  onCreateFolder,
  onRenameFolder,
  onDeleteFolder,
}: FolderRailProps) {
  const favorites = dashboards.filter((d) => d.is_favorited).length
  const uncategorized = dashboards.filter((d) => !d.folder_id).length

  const submitNew = () => {
    const trimmed = newFolderName.trim()
    if (trimmed) onCreateFolder(trimmed)
  }

  return (
    <aside className="hidden w-[184px] shrink-0 md:block">
      <div className="px-2 pb-2 text-[11px] font-semibold uppercase tracking-widest text-muted-foreground/70">
        Folders
      </div>
      <RailButton
        icon={<LayoutDashboard className="h-4 w-4" />}
        label="All"
        count={dashboards.length}
        active={selectedFolder === 'all'}
        onClick={() => onSelectFolder('all')}
      />
      <RailButton
        icon={<Star className="h-4 w-4 text-warning-solid" />}
        label="Favorites"
        count={favorites}
        active={selectedFolder === 'favorites'}
        onClick={() => onSelectFolder('favorites')}
      />
      <div className="my-2 h-px bg-border/60" />
      {folders.map((folder, index) => {
        const filter = folderIdFilter(folder.id)
        return (
          <FolderRailItem
            key={folder.id}
            folder={folder}
            color={folderColor(folder, index)}
            count={dashboards.filter((d) => d.folder_id === folder.id).length}
            active={selectedFolder === filter}
            onSelect={() => onSelectFolder(filter)}
            onRename={(name) => onRenameFolder(folder.id, name)}
            onDelete={() => onDeleteFolder(folder.id)}
          />
        )
      })}
      <RailButton
        icon={<Folder className="h-4 w-4 text-muted-foreground/70" />}
        label="Uncategorized"
        count={uncategorized}
        active={selectedFolder === 'uncategorized'}
        onClick={() => onSelectFolder('uncategorized')}
      />
      <div className="my-2 h-px bg-border/60" />
      {isCreatingFolder ? (
        <div className="flex items-center gap-1 px-1">
          <Input
            placeholder="Folder name"
            value={newFolderName}
            onChange={(e) => onNewFolderNameChange(e.target.value)}
            onKeyDown={(e) => {
              if (e.key === 'Enter') submitNew()
              if (e.key === 'Escape') onCancelCreateFolder()
            }}
            className="h-7 text-sm"
            autoFocus
          />
          <Button size="sm" variant="ghost" className="h-7 px-2" onClick={submitNew}>
            <ChevronRight className="h-4 w-4" />
          </Button>
        </div>
      ) : (
        <button
          type="button"
          onClick={onStartCreateFolder}
          className="flex w-full items-center gap-2 rounded-md px-2 py-1.5 text-sm text-muted-foreground transition-colors hover:bg-accent hover:text-primary"
        >
          <FolderPlus className="h-4 w-4" />
          New folder
        </button>
      )}
    </aside>
  )
}

type RailButtonProps = Readonly<{
  icon: ReactNode
  label: string
  count: number
  active: boolean
  onClick: () => void
}>

function RailButton({icon, label, count, active, onClick}: RailButtonProps) {
  return (
    <button
      type="button"
      onClick={onClick}
      className={cn(
        'flex w-full items-center gap-2 rounded-md px-2 py-1.5 text-left text-sm transition-colors',
        active ? 'bg-primary/10 font-medium text-primary' : 'text-foreground hover:bg-accent',
      )}
    >
      <span className="shrink-0">{icon}</span>
      <span className="min-w-0 flex-1 truncate">{label}</span>
      <span
        className={cn(
          'shrink-0 text-xs tabular-nums',
          active ? 'text-primary' : 'text-muted-foreground/70',
        )}
      >
        {count}
      </span>
    </button>
  )
}

const FolderRailItem = memo(function FolderRailItem({
  folder,
  color,
  count,
  active,
  onSelect,
  onRename,
  onDelete,
}: Readonly<{
  folder: DashboardFolder
  color: string
  count: number
  active: boolean
  onSelect: () => void
  onRename: (name: string) => void
  onDelete: () => void
}>) {
  const [editDraft, setEditDraft] = useState<string | null>(null)
  const editName = editDraft ?? folder.name
  const isEditing = editDraft != null

  if (isEditing) {
    return (
      <div className="flex items-center gap-1 px-1 py-0.5">
        <Input
          value={editName}
          onChange={(e) => setEditDraft(e.target.value)}
          onKeyDown={(e) => {
            if (e.key === 'Enter') {
              const trimmed = editName.trim()
              if (trimmed) onRename(trimmed)
              setEditDraft(null)
            }
            if (e.key === 'Escape') setEditDraft(null)
          }}
          className="h-7 text-sm"
          autoFocus
        />
      </div>
    )
  }

  return (
    <div
      className={cn(
        'group relative flex w-full items-center gap-2 rounded-md px-2 py-1.5 text-sm transition-colors',
        active ? 'bg-primary/10 font-medium text-primary' : 'hover:bg-accent',
      )}
    >
      <button type="button" onClick={onSelect} className="flex min-w-0 flex-1 items-center gap-2 text-left">
        <span
          className="h-[9px] w-[9px] shrink-0 rounded-sm"
          style={{background: folder.color ?? color}}
        />
        <span className="truncate">{folder.name}</span>
      </button>
      <span className="shrink-0 text-xs tabular-nums text-muted-foreground/70 group-hover:invisible">
        {count}
      </span>
      <div className="absolute right-1 top-1/2 -translate-y-1/2 opacity-0 transition-opacity group-hover:opacity-100">
        <DropdownMenu>
          <DropdownMenuTrigger asChild>
            <button
              type="button"
              onClick={(e) => e.stopPropagation()}
              className="rounded p-1 text-muted-foreground hover:bg-muted-foreground/20 hover:text-foreground"
            >
              <MoreHorizontal className="h-3.5 w-3.5" />
            </button>
          </DropdownMenuTrigger>
          <DropdownMenuContent align="end" side="bottom" className="w-44">
            <DropdownMenuItem onClick={() => setEditDraft(folder.name)}>
              <Pencil className="mr-2 h-3.5 w-3.5" />
              Rename
            </DropdownMenuItem>
            <DropdownMenuSeparator />
            <DropdownMenuItem onClick={onDelete} className="text-destructive">
              <Trash2 className="mr-2 h-3.5 w-3.5" />
              Delete folder
            </DropdownMenuItem>
          </DropdownMenuContent>
        </DropdownMenu>
      </div>
    </div>
  )
})

// ---- Toolbar controls -----------------------------------------------------

function SortMenu({sort, onChange}: Readonly<{sort: SortKey; onChange: (s: SortKey) => void}>) {
  return (
    <DropdownMenu>
      <DropdownMenuTrigger asChild>
        <button
          type="button"
          className="inline-flex h-[30px] items-center gap-1.5 rounded-md border bg-card px-2.5 text-xs text-foreground transition-colors hover:bg-accent"
        >
          <ArrowUpDown className="h-3.5 w-3.5 text-muted-foreground" />
          <span className="text-muted-foreground">Sort</span>
          {SORT_LABELS[sort]}
        </button>
      </DropdownMenuTrigger>
      <DropdownMenuContent align="end" className="w-48">
        {(Object.keys(SORT_LABELS) as SortKey[]).map((key) => (
          <DropdownMenuItem key={key} onClick={() => onChange(key)}>
            {SORT_LABELS[key]}
          </DropdownMenuItem>
        ))}
      </DropdownMenuContent>
    </DropdownMenu>
  )
}

function ViewToggle({view, onChange}: Readonly<{view: ViewMode; onChange: (v: ViewMode) => void}>) {
  return (
    <div className="inline-flex overflow-hidden rounded-md border bg-card">
      <SegButton active={view === 'list'} label="List view" onClick={() => onChange('list')}>
        <List className="h-4 w-4" />
      </SegButton>
      <SegButton active={view === 'grid'} label="Grid view" onClick={() => onChange('grid')}>
        <LayoutGrid className="h-4 w-4" />
      </SegButton>
    </div>
  )
}

function SegButton({
  active,
  label,
  onClick,
  children,
}: Readonly<{active: boolean; label: string; onClick: () => void; children: ReactNode}>) {
  return (
    <button
      type="button"
      aria-label={label}
      aria-pressed={active}
      onClick={onClick}
      className={cn(
        'flex h-[30px] w-[30px] items-center justify-center border-l first:border-l-0',
        active ? 'bg-primary/10 text-primary' : 'text-muted-foreground hover:text-foreground',
      )}
    >
      {children}
    </button>
  )
}

// ---- Shared row/card model ------------------------------------------------

type BoardActions = Readonly<{
  onDelete: (id: string) => void
  onDuplicate: (id: string) => void
  onSetDefault: (id: string) => void
  onFavoriteToggle: (id: string) => void
  onMoveToFolder: (id: string, folderId: string | null) => void
}>

type BoardViewProps = BoardActions &
  Readonly<{
    dashboards: readonly CustomDashboard[]
    folders: readonly DashboardFolder[]
    now: number
  }>

// ---- List view ------------------------------------------------------------

function ListView({dashboards, folders, now, ...actions}: BoardViewProps) {
  return (
    <div>
      <div
        className={cn(
          'grid items-center gap-2 px-2 pb-1.5 text-[11px] font-semibold uppercase tracking-wide text-muted-foreground/70',
          LIST_GRID,
        )}
      >
        <span />
        <span />
        <span>Name</span>
        <span className="text-right">Widgets</span>
        <span className="text-right">Updated</span>
        <span className="text-right">Owner</span>
        <span />
      </div>
      <div className="overflow-hidden rounded-lg border bg-card">
        {dashboards.map((dashboard) => (
          <DashboardListRow
            key={dashboard.id}
            dashboard={dashboard}
            folders={folders}
            now={now}
            {...actions}
          />
        ))}
      </div>
    </div>
  )
}

const DashboardListRow = memo(function DashboardListRow({
  dashboard,
  folders,
  now,
  onDelete,
  onDuplicate,
  onSetDefault,
  onFavoriteToggle,
  onMoveToFolder,
}: BoardActions &
  Readonly<{dashboard: CustomDashboard; folders: readonly DashboardFolder[]; now: number}>) {
  const folder = folders.find((f) => f.id === dashboard.folder_id)
  const thumb = getDashboardThumb(dashboard, folder?.name)
  const sources = getDashboardSources(dashboard).slice(0, 3)
  const owner = ownerAvatar(dashboard)

  return (
    <div
      className={cn(
        'group grid items-center gap-2 border-b border-border/60 p-2 last:border-b-0 hover:bg-accent/50',
        LIST_GRID,
      )}
    >
      <FavoriteButton dashboard={dashboard} onToggle={onFavoriteToggle} />
      <Link
        to="/dashboards/$dashboardId"
        params={{dashboardId: String(dashboard.id)}}
        className="col-span-5 grid grid-cols-[56px_1fr_78px_104px_44px] items-center gap-2 text-foreground no-underline"
      >
        <span className="block h-9 w-14 overflow-hidden rounded-sm border border-border/60 bg-[hsl(var(--viz-surface))]">
          <SparkThumb kind={thumb} />
        </span>
        <span className="flex min-w-0 flex-col gap-px">
          <span className="flex items-center gap-1.5 truncate text-sm font-semibold text-foreground">
            <span className="truncate">{dashboard.title}</span>
            {dashboard.is_default && (
              <span className="inline-flex shrink-0 items-center gap-1 rounded-sm border border-accent-subtle-border bg-accent-subtle-bg px-1.5 text-[10px] font-medium text-accent-subtle-fg">
                <Home className="h-2.5 w-2.5" />
                Default
              </span>
            )}
          </span>
          <span className="flex min-w-0 items-center gap-1.5 truncate text-xs text-muted-foreground">
            {dashboard.description && <span className="truncate">{dashboard.description}</span>}
            {folder && (
              <>
                <span className="text-muted-foreground/50">·</span>
                <span
                  className="h-[9px] w-[9px] shrink-0 rounded-sm"
                  style={{background: folderColor(folder, folderIndex(folders, folder.id))}}
                />
                <span className="shrink-0">{folder.name}</span>
              </>
            )}
            {sources.length > 0 && <span className="text-muted-foreground/50">·</span>}
            {sources.map((source) => (
              <SourceChip key={source} label={source} />
            ))}
          </span>
        </span>
        <span className="text-right font-mono text-xs text-foreground">{dashboard.widgets.length}</span>
        <span className="text-right text-xs tabular-nums text-muted-foreground">
          {formatRelative(now, dashboard.updated_at)}
        </span>
        <span className="flex justify-end">
          <OwnerAvatar owner={owner} />
        </span>
      </Link>
      <BoardActionsMenu
        dashboard={dashboard}
        folders={folders}
        onDelete={onDelete}
        onDuplicate={onDuplicate}
        onSetDefault={onSetDefault}
        onMoveToFolder={onMoveToFolder}
        triggerClassName="opacity-0 group-hover:opacity-100"
      />
    </div>
  )
})

// ---- Grid view ------------------------------------------------------------

function GridView({dashboards, folders, now, ...actions}: BoardViewProps) {
  return (
    <div className="grid grid-cols-[repeat(auto-fill,minmax(248px,1fr))] gap-3">
      {dashboards.map((dashboard) => (
        <DashboardGridCard
          key={dashboard.id}
          dashboard={dashboard}
          folders={folders}
          now={now}
          {...actions}
        />
      ))}
    </div>
  )
}

const DashboardGridCard = memo(function DashboardGridCard({
  dashboard,
  folders,
  now,
  onDelete,
  onDuplicate,
  onSetDefault,
  onFavoriteToggle,
  onMoveToFolder,
}: BoardActions &
  Readonly<{dashboard: CustomDashboard; folders: readonly DashboardFolder[]; now: number}>) {
  const folder = folders.find((f) => f.id === dashboard.folder_id)
  const thumb = getDashboardThumb(dashboard, folder?.name)
  const sources = getDashboardSources(dashboard).slice(0, 2)

  return (
    <div className="group relative flex flex-col overflow-hidden rounded-lg border bg-card shadow-xs transition-all hover:-translate-y-px hover:border-primary hover:shadow-sm">
      <Link
        to="/dashboards/$dashboardId"
        params={{dashboardId: String(dashboard.id)}}
        className="flex flex-col text-foreground no-underline"
      >
        <div className="h-[108px] overflow-hidden border-b bg-[hsl(var(--viz-surface))] p-2">
          <DashboardThumb kind={thumb} />
        </div>
        <div className="flex flex-col gap-1.5 px-3 pb-3 pt-2.5">
          <div className="flex items-center gap-2">
            <span className="truncate text-sm font-semibold text-foreground">{dashboard.title}</span>
            <span className="ml-auto shrink-0 font-mono text-[10px] text-muted-foreground/70">
              {dashboard.widgets.length}w
            </span>
          </div>
          <p className="line-clamp-2 min-h-8 text-xs leading-snug text-muted-foreground">
            {dashboard.description || 'No description'}
          </p>
          <div className="mt-px flex items-center gap-1.5">
            {sources.map((source) => (
              <SourceChip key={source} label={source} />
            ))}
            <span className="ml-auto text-[10px] text-muted-foreground/70">
              {formatRelative(now, dashboard.updated_at)}
            </span>
          </div>
        </div>
      </Link>
      <div className="absolute right-2 top-2 flex gap-1">
        <FavoriteButton dashboard={dashboard} onToggle={onFavoriteToggle} overlay />
        <BoardActionsMenu
          dashboard={dashboard}
          folders={folders}
          onDelete={onDelete}
          onDuplicate={onDuplicate}
          onSetDefault={onSetDefault}
          onMoveToFolder={onMoveToFolder}
          overlay
        />
      </div>
    </div>
  )
})

// ---- Small shared pieces --------------------------------------------------

function FavoriteButton({
  dashboard,
  onToggle,
  overlay,
}: Readonly<{dashboard: CustomDashboard; onToggle: (id: string) => void; overlay?: boolean}>) {
  const favorited = !!dashboard.is_favorited
  const handle = (e: MouseEvent) => {
    e.preventDefault()
    e.stopPropagation()
    onToggle(dashboard.id)
  }
  return (
    <button
      type="button"
      onClick={handle}
      title={favorited ? 'Remove from favorites' : 'Add to favorites'}
      aria-label={favorited ? 'Remove from favorites' : 'Add to favorites'}
      aria-pressed={favorited}
      className={cn(
        'flex items-center justify-center rounded-sm',
        overlay
          ? 'h-6 w-6 border border-white/10 bg-black/55 text-[#cfd8e3] hover:text-white'
          : 'h-6 w-6 text-muted-foreground/60 hover:text-warning-solid',
        favorited && 'text-warning-solid',
      )}
    >
      <Star className={cn('h-3.5 w-3.5', favorited && 'fill-current')} />
    </button>
  )
}

function BoardActionsMenu({
  dashboard,
  folders,
  onDelete,
  onDuplicate,
  onSetDefault,
  onMoveToFolder,
  overlay,
  triggerClassName,
}: Readonly<{
  dashboard: CustomDashboard
  folders: readonly DashboardFolder[]
  onDelete: (id: string) => void
  onDuplicate: (id: string) => void
  onSetDefault: (id: string) => void
  onMoveToFolder: (id: string, folderId: string | null) => void
  overlay?: boolean
  triggerClassName?: string
}>) {
  const stop = (e: MouseEvent) => {
    e.preventDefault()
    e.stopPropagation()
  }
  return (
    <DropdownMenu>
      <DropdownMenuTrigger asChild>
        <button
          type="button"
          onClick={stop}
          aria-label="Dashboard actions"
          className={cn(
            'flex items-center justify-center rounded-sm transition-opacity',
            overlay
              ? 'h-6 w-6 border border-white/10 bg-black/55 text-[#cfd8e3] hover:text-white'
              : 'h-[26px] w-[26px] text-muted-foreground hover:bg-accent hover:text-foreground',
            triggerClassName,
          )}
        >
          <MoreHorizontal className="h-4 w-4" />
        </button>
      </DropdownMenuTrigger>
      <DropdownMenuContent align="end" className="w-48">
        <DropdownMenuItem
          onClick={(e) => {
            stop(e)
            onDuplicate(dashboard.id)
          }}
        >
          <Copy className="mr-2 h-3.5 w-3.5" />
          Duplicate
        </DropdownMenuItem>
        {!dashboard.is_default && (
          <DropdownMenuItem
            onClick={(e) => {
              stop(e)
              onSetDefault(dashboard.id)
            }}
          >
            <Home className="mr-2 h-3.5 w-3.5" />
            Set as default
          </DropdownMenuItem>
        )}
        <DropdownMenuSub>
          <DropdownMenuSubTrigger onClick={stop}>
            <Folder className="mr-2 h-3.5 w-3.5" />
            Move to folder
          </DropdownMenuSubTrigger>
          <DropdownMenuSubContent>
            <DropdownMenuItem
              onClick={(e) => {
                stop(e)
                onMoveToFolder(dashboard.id, null)
              }}
            >
              Uncategorized
            </DropdownMenuItem>
            {folders.map((folder) => (
              <DropdownMenuItem
                key={folder.id}
                onClick={(e) => {
                  stop(e)
                  onMoveToFolder(dashboard.id, folder.id)
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
            stop(e)
            onDelete(dashboard.id)
          }}
          className="text-destructive"
        >
          <Trash2 className="mr-2 h-3.5 w-3.5" />
          Delete
        </DropdownMenuItem>
      </DropdownMenuContent>
    </DropdownMenu>
  )
}

function SourceChip({label}: Readonly<{label: string}>) {
  return (
    <span className="inline-flex h-4 shrink-0 items-center rounded-sm border border-border/60 bg-muted px-1.5 text-[10px] font-medium text-muted-foreground">
      {label}
    </span>
  )
}

type Owner = Readonly<{initials: string; color: string; label: string}>

function OwnerAvatar({owner}: Readonly<{owner: Owner}>) {
  return (
    <span
      title={owner.label}
      className="flex h-6 w-6 items-center justify-center rounded-full text-[10px] font-bold text-white"
      style={{background: owner.color}}
    >
      {owner.initials}
    </span>
  )
}

function BoardsEmptyState({
  selectedFolder,
  folderName,
  hasSearch,
  onCreateBlank,
}: Readonly<{
  selectedFolder: FolderFilter
  folderName: string
  hasSearch: boolean
  onCreateBlank: (folderId?: string) => void
}>) {
  if (hasSearch) {
    return (
      <EmptyState
        icon={LayoutDashboard}
        title="No dashboards match your search"
        description="Try a different term, or clear the search to see everything in this folder."
      />
    )
  }
  if (selectedFolder === 'favorites') {
    return (
      <EmptyState
        icon={Star}
        title="No favorites yet"
        description="Star dashboards you use often for quick access. Click the star on any row or card to add it here."
      />
    )
  }
  const createInFolder = () => {
    onCreateBlank(isConcreteFolderFilter(selectedFolder) ? selectedFolder : undefined)
  }
  const createAction = (
    <Button onClick={createInFolder}>
      <LayoutDashboard className="mr-1.5 h-4 w-4" />
      Create dashboard
    </Button>
  )
  if (selectedFolder === 'uncategorized') {
    return (
      <EmptyState
        icon={Folder}
        title="No uncategorized dashboards"
        description="Dashboards that haven't been moved into a folder will appear here."
        action={createAction}
      />
    )
  }
  if (isConcreteFolderFilter(selectedFolder)) {
    return (
      <EmptyState
        icon={Folder}
        title={`${folderName} is empty`}
        description="Move existing dashboards here or create a new one to get started."
        action={createAction}
      />
    )
  }
  return (
    <EmptyState
      icon={LayoutDashboard}
      title="No dashboards here"
      description="Create a dashboard to start visualizing your telemetry, or pick a template."
      action={createAction}
    />
  )
}

// ---- Helpers --------------------------------------------------------------

function filterByFolder(
  dashboards: readonly CustomDashboard[],
  selectedFolder: FolderFilter,
): readonly CustomDashboard[] {
  if (selectedFolder === 'all') return dashboards
  if (selectedFolder === 'favorites') return dashboards.filter((d) => d.is_favorited)
  if (selectedFolder === 'uncategorized') return dashboards.filter((d) => !d.folder_id)
  return dashboards.filter((d) => d.folder_id === selectedFolder)
}

function filterBySearch(
  dashboards: readonly CustomDashboard[],
  search: string,
): readonly CustomDashboard[] {
  const query = search.trim().toLowerCase()
  if (!query) return dashboards
  return dashboards.filter((d) =>
    `${d.title} ${d.description ?? ''}`.toLowerCase().includes(query),
  )
}

function sortDashboards(
  dashboards: readonly CustomDashboard[],
  sort: SortKey,
  now: number,
): CustomDashboard[] {
  const copy = [...dashboards]
  switch (sort) {
    case 'name':
      return copy.sort((a, b) => a.title.localeCompare(b.title))
    case 'widgets':
      return copy.sort((a, b) => b.widgets.length - a.widgets.length)
    case 'created':
      return copy.sort((a, b) => toTime(b.created_at, now) - toTime(a.created_at, now))
    case 'updated':
    default:
      return copy.sort((a, b) => toTime(b.updated_at, now) - toTime(a.updated_at, now))
  }
}

function toTime(iso: string, fallback: number): number {
  const time = new Date(iso).getTime()
  return Number.isNaN(time) ? fallback : time
}

function getFolderName(selectedFolder: FolderFilter, folders: readonly DashboardFolder[]): string {
  if (selectedFolder === 'all') return 'All'
  if (selectedFolder === 'favorites') return 'Favorites'
  if (selectedFolder === 'uncategorized') return 'Uncategorized'
  return folders.find((f) => f.id === selectedFolder)?.name ?? 'Folder'
}

function folderIdFilter(id: string): FolderIdFilter {
  return id as FolderIdFilter
}

function isConcreteFolderFilter(selectedFolder: FolderFilter): selectedFolder is FolderIdFilter {
  return !SYSTEM_FOLDER_FILTERS.has(selectedFolder as SystemFolderFilter)
}

function folderIndex(folders: readonly DashboardFolder[], folderId: string): number {
  return Math.max(0, folders.findIndex((folder) => folder.id === folderId))
}

function folderColor(folder: DashboardFolder, index: number): string {
  return folder.color ?? SWATCHES[index % SWATCHES.length]
}

// Owner identity for the row avatar. Prefers a real owner name and falls back to
// created_by when old rows or clients do not carry one yet.
function ownerAvatar(dashboard: CustomDashboard): Owner {
  const named = dashboard.owner_name?.trim()
  const ownerSeed = stableStringSeed(dashboard.created_by)
  const color = SWATCHES[ownerSeed % SWATCHES.length]
  if (named) {
    return {initials: initialsFromName(named), color, label: named}
  }
  const letters = 'ABCDEFGHIJKLMNOPQRSTUVWXYZ'
  const initials = `${letters[ownerSeed % 26]}${letters[(ownerSeed * 7 + 3) % 26]}`
  return {initials, color, label: `User ${dashboard.created_by.slice(0, 8)}`}
}

function stableStringSeed(value: string): number {
  return Array.from(value).reduce((seed, character) => seed + character.charCodeAt(0), 0)
}

function initialsFromName(name: string): string {
  const parts = name.trim().split(/\s+/).slice(0, 2)
  return parts.map((part) => part.charAt(0).toUpperCase()).join('') || '?'
}

function formatRelative(now: number, iso: string): string {
  const then = new Date(iso).getTime()
  if (Number.isNaN(then)) return '—'
  const diff = now - then
  const minute = 60_000
  const hour = 60 * minute
  const day = 24 * hour
  if (diff < hour) {
    const mins = Math.max(1, Math.round(diff / minute))
    return `${mins}m ago`
  }
  if (diff < day) return `${Math.round(diff / hour)}h ago`
  if (diff < 2 * day) return '1d ago'
  if (diff < 7 * day) return `${Math.floor(diff / day)}d ago`
  return new Date(iso).toLocaleDateString()
}
