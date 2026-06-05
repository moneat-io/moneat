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

import {Link} from '@tanstack/react-router'
import {useMutation, useQuery, useQueryClient} from '@tanstack/react-query'
import {type Edge, type Node, type NodeProps} from '@xyflow/react'
import {
  AlertTriangle,
  Box,
  CircleCheck,
  CircleX,
  Filter,
  HardDrive,
  Layers3,
  Map as MapIcon,
  Save,
  Search,
  Trash2,
} from 'lucide-react'
import {
  memo,
  useCallback,
  useMemo,
  useState,
  type ComponentProps,
  type MouseEvent as ReactMouseEvent,
  type ReactNode,
} from 'react'
import {api} from '@/lib/api'
import type {DdHostResponse} from '@/lib/api/types/hosts'
import type {DdContainerResponse} from '@/lib/api/types/infrastructure'
import {useToast} from '@/hooks/useToast'
import {ExplorerShell} from '@/components/filters/ExplorerShell'
import {SearchFilterBar} from '@/components/filters/SearchFilterBar'
import {MapCanvas} from '@/components/maps/MapCanvas'
import {MapDetailPanel} from '@/components/maps/MapDetailPanel'
import {MapLegend} from '@/components/maps/MapLegend'
import {MapNodeCard} from '@/components/maps/MapNodeCard'
import {Badge} from '@/components/ui/badge'
import {Button} from '@/components/ui/button'
import {Input} from '@/components/ui/input'
import {Label} from '@/components/ui/label'
import {Popover, PopoverContent, PopoverTrigger} from '@/components/ui/popover'
import {Select, SelectContent, SelectGroup, SelectItem, SelectTrigger, SelectValue} from '@/components/ui/select'
import type {FacetFilter, FacetSchema} from '@/lib/filters/types'
import {cn} from '@/lib/utils'
import {
  buildInfrastructureMapGroups,
  createInfrastructureMapResources,
  type InfrastructureFillBy,
  type InfrastructureGroupBy,
  type InfrastructureMapNode,
  type InfrastructureMapTone,
  type InfrastructureMapViewState,
  type InfrastructureResourceKind,
  type InfrastructureSizeBy,
  type SavedInfrastructureMapView,
} from './infrastructureMapModel'

const HOST_REFRESH_MS = 30_000
const CONTAINER_REFRESH_MS = 10_000
const CONTAINER_LIMIT = 500
const SAVED_VIEW_NAME_MAX_LENGTH = 48
const SAVED_VIEW_SEARCH_QUERY_MAX_LENGTH = 200
const EMPTY_HOSTS: DdHostResponse[] = []
const EMPTY_CONTAINERS: DdContainerResponse[] = []
const EMPTY_SAVED_VIEWS: SavedInfrastructureMapView[] = []
const EMPTY_FACET_FILTERS: FacetFilter[] = []
const INFRASTRUCTURE_MAP_SEARCH_SCHEMA: FacetSchema = []
const NO_SAVED_VIEW_ID = 'none'
const INFRA_NODE_WIDTH = 188
const INFRA_NODE_HEIGHT = 96
const INFRA_NODE_GAP = 24
const INFRA_GROUP_HEADER_HEIGHT = 30
const INFRA_GROUP_GAP = 56
const INFRA_GROUP_COLUMNS = 4
const INFRA_GROUP_PADDING = 16
const INFRASTRUCTURE_MAP_SAVED_VIEWS_QUERY_KEY = ['infrastructure-map-saved-views'] as const

type BadgeVariant = NonNullable<ComponentProps<typeof Badge>['variant']>

interface Option<TValue extends string> {
  value: TValue
  label: string
}

interface ResourceOption extends Option<InfrastructureResourceKind> {
  icon: typeof HardDrive
}

interface ToneClasses {
  border: string
  color: string
  icon: string
  iconBackground: string
  badge: BadgeVariant
}

interface InfrastructureFlowNodeData extends InfrastructureMapNode {
  [key: string]: unknown
  resourceKind: InfrastructureResourceKind
  selected: boolean
}

interface InfrastructureGroupNodeData {
  [key: string]: unknown
  label: string
  count: number
  healthyCount: number
  width: number
  height: number
}

const RESOURCE_OPTIONS: ResourceOption[] = [
  {value: 'hosts', label: 'Hosts', icon: HardDrive},
  {value: 'containers', label: 'Containers', icon: Box},
]

const HOST_GROUP_OPTIONS: Array<Option<InfrastructureGroupBy>> = [
  {value: 'status', label: 'Status'},
  {value: 'platform', label: 'Platform'},
  {value: 'os', label: 'OS'},
  {value: 'agent', label: 'Agent version'},
  {value: 'tag:env', label: 'Tag: env'},
  {value: 'tag:service', label: 'Tag: service'},
  {value: 'tag:region', label: 'Tag: region'},
]

const CONTAINER_GROUP_OPTIONS: Array<Option<InfrastructureGroupBy>> = [
  {value: 'status', label: 'State'},
  {value: 'host', label: 'Host'},
  {value: 'image', label: 'Image'},
  {value: 'tag:env', label: 'Tag: env'},
  {value: 'tag:service', label: 'Tag: service'},
  {value: 'tag:region', label: 'Tag: region'},
]

const HOST_FILL_OPTIONS: Array<Option<InfrastructureFillBy>> = [
  {value: 'health', label: 'Health'},
  {value: 'memory', label: 'Memory capacity'},
  {value: 'cpu', label: 'CPU capacity'},
  {value: 'lastSeen', label: 'Last seen'},
]

const CONTAINER_FILL_OPTIONS: Array<Option<InfrastructureFillBy>> = [
  {value: 'health', label: 'Health'},
  {value: 'cpu', label: 'CPU'},
  {value: 'memory', label: 'Memory'},
  {value: 'network', label: 'Network'},
  {value: 'lastSeen', label: 'Last seen'},
]

const HOST_SIZE_OPTIONS: Array<Option<InfrastructureSizeBy>> = [
  {value: 'uniform', label: 'Uniform'},
  {value: 'memory', label: 'Memory capacity'},
  {value: 'cpu', label: 'CPU capacity'},
]

const CONTAINER_SIZE_OPTIONS: Array<Option<InfrastructureSizeBy>> = [
  {value: 'uniform', label: 'Uniform'},
  {value: 'cpu', label: 'CPU'},
  {value: 'memory', label: 'Memory'},
  {value: 'network', label: 'Network'},
]

const DEFAULT_HOST_VIEW: InfrastructureMapViewState = {
  resourceKind: 'hosts',
  groupBy: 'status',
  fillBy: 'health',
  sizeBy: 'memory',
  searchQuery: '',
}

const DEFAULT_CONTAINER_VIEW: InfrastructureMapViewState = {
  resourceKind: 'containers',
  groupBy: 'host',
  fillBy: 'health',
  sizeBy: 'cpu',
  searchQuery: '',
}

const TONE_CLASSES: Record<InfrastructureMapTone, ToneClasses> = {
  success: {
    border: 'border-success-border hover:border-success-fg',
    color: 'hsl(var(--success-solid))',
    icon: 'text-success-fg',
    iconBackground: 'bg-success-bg',
    badge: 'success',
  },
  danger: {
    border: 'border-danger-border hover:border-danger-fg',
    color: 'hsl(var(--danger-solid))',
    icon: 'text-danger-fg',
    iconBackground: 'bg-danger-bg',
    badge: 'danger',
  },
  warning: {
    border: 'border-warning-border hover:border-warning-fg',
    color: 'hsl(var(--warning-solid))',
    icon: 'text-warning-fg',
    iconBackground: 'bg-warning-bg',
    badge: 'warning',
  },
  info: {
    border: 'border-info-border hover:border-info-fg',
    color: 'hsl(var(--info-solid))',
    icon: 'text-info-fg',
    iconBackground: 'bg-info-bg',
    badge: 'info',
  },
  neutral: {
    border: 'border-border hover:border-foreground/30',
    color: 'hsl(var(--muted-foreground))',
    icon: 'text-muted-foreground',
    iconBackground: 'bg-muted',
    badge: 'neutral',
  },
}

const INFRA_LEGEND_ITEMS = [
  {label: 'Healthy', color: TONE_CLASSES.success.color},
  {label: 'Attention', color: TONE_CLASSES.danger.color},
  {label: 'Warning', color: TONE_CLASSES.warning.color},
  {label: 'Neutral', color: TONE_CLASSES.neutral.color},
]

export function InfrastructureMap() {
  const queryClient = useQueryClient()
  const {toast} = useToast()
  const [viewState, setViewState] = useState<InfrastructureMapViewState>(DEFAULT_HOST_VIEW)
  const [selectedSavedViewId, setSelectedSavedViewId] = useState(NO_SAVED_VIEW_ID)
  const [savedViewName, setSavedViewName] = useState('')
  const [selectedNodeId, setSelectedNodeId] = useState<string | null>(null)

  const hostsQuery = useQuery({
    queryKey: ['hosts'],
    queryFn: () => api.getHosts(),
    enabled: api.isAuthenticated(),
    refetchInterval: HOST_REFRESH_MS,
  })

  const containersQuery = useQuery({
    queryKey: ['containers', 'map'],
    queryFn: () => api.getContainers({limit: CONTAINER_LIMIT}),
    enabled: api.isAuthenticated() && viewState.resourceKind === 'containers',
    refetchInterval: CONTAINER_REFRESH_MS,
  })

  const savedViewsQuery = useQuery({
    queryKey: INFRASTRUCTURE_MAP_SAVED_VIEWS_QUERY_KEY,
    queryFn: () => api.getInfrastructureMapSavedViews(),
    enabled: api.isAuthenticated(),
  })

  const saveSavedViewMutation = useMutation({
    mutationFn: api.saveInfrastructureMapView,
    onSuccess: (savedView) => {
      const wasExistingSavedView = savedViews.some((view) => view.id === savedView.id)
      queryClient.setQueryData(
        INFRASTRUCTURE_MAP_SAVED_VIEWS_QUERY_KEY,
        (current: {views: SavedInfrastructureMapView[]} | undefined) => ({
          views: upsertSavedView(current?.views ?? EMPTY_SAVED_VIEWS, savedView),
        }),
      )
      setSelectedSavedViewId(savedView.id)
      setSavedViewName(savedView.name)
      toast({title: wasExistingSavedView ? 'Saved view updated' : 'Saved view created'})
    },
    onError: (error: Error) => {
      toast({
        title: 'Failed to save view',
        description: error.message,
        variant: 'destructive',
      })
    },
  })

  const deleteSavedViewMutation = useMutation({
    mutationFn: api.deleteInfrastructureMapSavedView,
    onSuccess: (_unused, deletedViewId) => {
      queryClient.setQueryData(
        INFRASTRUCTURE_MAP_SAVED_VIEWS_QUERY_KEY,
        (current: {views: SavedInfrastructureMapView[]} | undefined) => ({
          views: (current?.views ?? EMPTY_SAVED_VIEWS).filter((view) => view.id !== deletedViewId),
        }),
      )
      setSelectedSavedViewId(NO_SAVED_VIEW_ID)
      setSavedViewName('')
      toast({title: 'Saved view deleted'})
    },
    onError: (error: Error) => {
      toast({
        title: 'Failed to delete view',
        description: error.message,
        variant: 'destructive',
      })
    },
  })

  const hosts = hostsQuery.data?.hosts ?? EMPTY_HOSTS
  const containers = containersQuery.data?.containers ?? EMPTY_CONTAINERS
  const savedViews = savedViewsQuery.data?.views ?? EMPTY_SAVED_VIEWS
  const resources = useMemo(
    () => createInfrastructureMapResources({
      resourceKind: viewState.resourceKind,
      hosts,
      containers,
    }),
    [containers, hosts, viewState.resourceKind],
  )
  const mapResult = useMemo(
    () => buildInfrastructureMapGroups(resources, viewState),
    [resources, viewState],
  )
  const mapGraph = useMemo(
    () => buildInfrastructureFlowGraph(mapResult, viewState.resourceKind, selectedNodeId),
    [mapResult, selectedNodeId, viewState.resourceKind],
  )
  const fitSignature = [
    viewState.resourceKind,
    viewState.groupBy,
    viewState.fillBy,
    viewState.sizeBy,
    viewState.searchQuery,
    mapResult.summary.groupCount,
    mapResult.summary.visibleResources,
  ].join(':')
  const selectedNode = useMemo(
    () => mapResult.groups.flatMap((group) => group.nodes).find((node) => node.id === selectedNodeId) ?? null,
    [mapResult.groups, selectedNodeId],
  )
  const groupOptions = getGroupOptions(viewState.resourceKind)
  const fillOptions = getFillOptions(viewState.resourceKind)
  const sizeOptions = getSizeOptions(viewState.resourceKind)
  const isLoading = hostsQuery.isLoading || (viewState.resourceKind === 'containers' && containersQuery.isLoading)
  const isError = hostsQuery.isError || (viewState.resourceKind === 'containers' && containersQuery.isError)
  const unhealthyResources = mapResult.summary.visibleResources - mapResult.summary.healthyResources
  const containerTotalCount = containersQuery.data?.totalCount ?? containers.length
  const containerLimitNotice =
    viewState.resourceKind === 'containers' && containerTotalCount > CONTAINER_LIMIT
      ? `Showing first ${CONTAINER_LIMIT} of ${containerTotalCount}`
      : null

  function updateViewState(nextViewState: Partial<InfrastructureMapViewState>) {
    setViewState((current) => normalizeViewState({...current, ...nextViewState}))
    setSelectedSavedViewId(NO_SAVED_VIEW_ID)
    setSelectedNodeId(null)
  }

  function updateResourceKind(resourceKind: InfrastructureResourceKind) {
    const defaults = getDefaultView(resourceKind)
    setViewState((current) => ({...defaults, searchQuery: current.searchQuery}))
    setSelectedSavedViewId(NO_SAVED_VIEW_ID)
    setSelectedNodeId(null)
  }

  function loadSavedView(savedViewId: string) {
    setSelectedSavedViewId(savedViewId)
    if (savedViewId === NO_SAVED_VIEW_ID) return

    const savedView = savedViews.find((view) => view.id === savedViewId)
    if (!savedView) return
    setViewState(normalizeViewState(savedView))
    setSavedViewName(savedView.name)
    setSelectedNodeId(null)
  }

  function saveCurrentView() {
    const name = savedViewName.trim().slice(0, SAVED_VIEW_NAME_MAX_LENGTH)
    if (!name) return
    const searchQuery = viewState.searchQuery.trim().slice(0, SAVED_VIEW_SEARCH_QUERY_MAX_LENGTH)

    saveSavedViewMutation.mutate({...viewState, name, searchQuery})
  }

  function deleteSelectedView() {
    if (selectedSavedViewId === NO_SAVED_VIEW_ID) return
    deleteSavedViewMutation.mutate(selectedSavedViewId)
  }

  const handleNodeClick = useCallback((_event: ReactMouseEvent, node: Node) => {
    if (node.type !== 'infrastructureResource') return
    setSelectedNodeId((currentId) => (currentId === node.id ? null : node.id))
  }, [])

  const handlePaneClick = useCallback(() => setSelectedNodeId(null), [])

  return (
    <div className="h-[calc(100vh-var(--header-height,0px)-51px)] min-h-[520px]">
      <ExplorerShell
        className={cn(
          'min-h-0 overflow-hidden bg-gradient-to-br',
          'from-background via-background to-[hsl(var(--primary)/0.03)]',
        )}
        title="Infrastructure Map"
        icon={(
          <div
            className={cn(
              'rounded-md bg-gradient-to-br from-primary/15 to-[hsl(var(--chart-4)/0.16)]',
              'p-1 ring-1 ring-primary/20',
            )}
          >
            <MapIcon className="h-3.5 w-3.5 text-primary" />
          </div>
        )}
        searchBar={(
          <SearchFilterBar
            query={viewState.searchQuery}
            onQueryChange={(searchQuery) => updateViewState({searchQuery})}
            facetFilters={EMPTY_FACET_FILTERS}
            onFacetFiltersChange={ignoreFacetFilters}
            schema={INFRASTRUCTURE_MAP_SEARCH_SCHEMA}
            placeholder="Search hosts, images, tags..."
          />
        )}
        actions={(
          <>
            <ResourceKindToggle
              resourceKind={viewState.resourceKind}
              onChange={updateResourceKind}
            />
            <SavedViewsPopover
              savedViews={savedViews}
              selectedSavedViewId={selectedSavedViewId}
              savedViewName={savedViewName}
              isLoading={savedViewsQuery.isLoading}
              isSaving={saveSavedViewMutation.isPending}
              isDeleting={deleteSavedViewMutation.isPending}
              onLoadSavedView={loadSavedView}
              onSavedViewNameChange={setSavedViewName}
              onSaveCurrentView={saveCurrentView}
              onDeleteSelectedView={deleteSelectedView}
            />
          </>
        )}
        toolbar={(
          <InfrastructureMapToolbar
            groupBy={viewState.groupBy}
            fillBy={viewState.fillBy}
            sizeBy={viewState.sizeBy}
            groupOptions={groupOptions}
            fillOptions={fillOptions}
            sizeOptions={sizeOptions}
            summary={mapResult.summary}
            unhealthyResources={unhealthyResources}
            limitNotice={containerLimitNotice}
            onGroupByChange={(groupBy) => updateViewState({groupBy})}
            onFillByChange={(fillBy) => updateViewState({fillBy})}
            onSizeByChange={(sizeBy) => updateViewState({sizeBy})}
          />
        )}
      >
        <InfrastructureMapBody
          isError={isError}
          isLoading={isLoading}
          mapGraph={mapGraph}
          mapResult={mapResult}
          resourceKind={viewState.resourceKind}
          fitSignature={fitSignature}
          selectedNode={selectedNode}
          onCloseDetail={() => setSelectedNodeId(null)}
          onNodeClick={handleNodeClick}
          onPaneClick={handlePaneClick}
        />
      </ExplorerShell>
    </div>
  )
}

function ResourceKindToggle({
  resourceKind,
  onChange,
}: {
  resourceKind: InfrastructureResourceKind
  onChange: (resourceKind: InfrastructureResourceKind) => void
}) {
  return (
    <div className="flex rounded-md border bg-background p-0.5">
      {RESOURCE_OPTIONS.map((option) => {
        const Icon = option.icon
        const isActive = option.value === resourceKind
        return (
          <button
            key={option.value}
            type="button"
            onClick={() => onChange(option.value)}
            className={cn(
              'inline-flex h-7 items-center gap-1.5 rounded px-2 text-xs font-medium transition-colors',
              isActive
                ? 'bg-secondary text-secondary-foreground'
                : 'text-muted-foreground hover:bg-muted/50 hover:text-foreground',
            )}
          >
            <Icon className="h-3.5 w-3.5" />
            <span className="hidden sm:inline">{option.label}</span>
          </button>
        )
      })}
    </div>
  )
}

function SavedViewsPopover({
  savedViews,
  selectedSavedViewId,
  savedViewName,
  isLoading,
  isSaving,
  isDeleting,
  onLoadSavedView,
  onSavedViewNameChange,
  onSaveCurrentView,
  onDeleteSelectedView,
}: {
  savedViews: SavedInfrastructureMapView[]
  selectedSavedViewId: string
  savedViewName: string
  isLoading: boolean
  isSaving: boolean
  isDeleting: boolean
  onLoadSavedView: (savedViewId: string) => void
  onSavedViewNameChange: (name: string) => void
  onSaveCurrentView: () => void
  onDeleteSelectedView: () => void
}) {
  return (
    <Popover>
      <PopoverTrigger asChild>
        <Button variant="outline" size="sm" className="h-8 gap-1.5 px-2 text-xs">
          <Save className="h-3.5 w-3.5" />
          <span className="hidden sm:inline">Views</span>
        </Button>
      </PopoverTrigger>
      <PopoverContent align="end" className="w-72 p-3">
        <div className="space-y-4">
          <div className="space-y-1.5">
            <Label className="text-[11px] uppercase tracking-wider text-muted-foreground">Saved view</Label>
            <div className="grid grid-cols-[minmax(0,1fr)_auto] gap-2">
              <Select
                value={selectedSavedViewId}
                onValueChange={onLoadSavedView}
                disabled={isLoading || savedViews.length === 0}
              >
                <SelectTrigger className="h-8 text-xs">
                  <SelectValue placeholder={isLoading ? 'Loading views' : 'Saved views'} />
                </SelectTrigger>
                <SelectContent>
                  <SelectGroup>
                    <SelectItem value={NO_SAVED_VIEW_ID}>Saved views</SelectItem>
                    {savedViews.map((view) => (
                      <SelectItem key={view.id} value={view.id}>
                        {view.name}
                      </SelectItem>
                    ))}
                  </SelectGroup>
                </SelectContent>
              </Select>
              <Button
                type="button"
                variant="outline"
                size="sm"
                disabled={isDeleting || selectedSavedViewId === NO_SAVED_VIEW_ID}
                onClick={onDeleteSelectedView}
                aria-label="Delete saved view"
                className="h-8 gap-1 px-2"
              >
                <Trash2 className="h-3.5 w-3.5" />
              </Button>
            </div>
          </div>

          <div className="space-y-1.5">
            <Label
              htmlFor="infrastructure-map-view-name"
              className="text-[11px] uppercase tracking-wider text-muted-foreground"
            >
              Save as
            </Label>
            <Input
              id="infrastructure-map-view-name"
              value={savedViewName}
              onChange={(event) => onSavedViewNameChange(event.target.value)}
              placeholder="View name"
              maxLength={SAVED_VIEW_NAME_MAX_LENGTH}
              className="h-8 text-xs"
            />
            <Button
              type="button"
              size="sm"
              onClick={onSaveCurrentView}
              disabled={isSaving || savedViewName.trim() === ''}
              className="h-8 w-full justify-center"
            >
              <Save className="h-3.5 w-3.5" />
              Save
            </Button>
          </div>
        </div>
      </PopoverContent>
    </Popover>
  )
}

function InfrastructureMapToolbar({
  groupBy,
  fillBy,
  sizeBy,
  groupOptions,
  fillOptions,
  sizeOptions,
  summary,
  unhealthyResources,
  limitNotice,
  onGroupByChange,
  onFillByChange,
  onSizeByChange,
}: {
  groupBy: InfrastructureGroupBy
  fillBy: InfrastructureFillBy
  sizeBy: InfrastructureSizeBy
  groupOptions: Array<Option<InfrastructureGroupBy>>
  fillOptions: Array<Option<InfrastructureFillBy>>
  sizeOptions: Array<Option<InfrastructureSizeBy>>
  summary: ReturnType<typeof buildInfrastructureMapGroups>['summary']
  unhealthyResources: number
  limitNotice: string | null
  onGroupByChange: (groupBy: InfrastructureGroupBy) => void
  onFillByChange: (fillBy: InfrastructureFillBy) => void
  onSizeByChange: (sizeBy: InfrastructureSizeBy) => void
}) {
  return (
    <div className="flex min-w-0 flex-1 flex-wrap items-center gap-2">
      <CompactSelect
        label="Group"
        value={groupBy}
        options={groupOptions}
        onChange={onGroupByChange}
      />
      <CompactSelect
        label="Color"
        value={fillBy}
        options={fillOptions}
        onChange={onFillByChange}
      />
      <CompactSelect
        label="Size"
        value={sizeBy}
        options={sizeOptions}
        onChange={onSizeByChange}
      />
      <div className="ml-auto flex min-w-0 flex-wrap items-center justify-end gap-2 text-xs text-muted-foreground">
        <SummaryItem icon={<Layers3 className="h-3.5 w-3.5" />} label={`${summary.groupCount} groups`} />
        <SummaryItem icon={<Filter className="h-3.5 w-3.5" />} label={`${summary.visibleResources} shown`} />
        <SummaryItem
          icon={<CircleCheck className="h-3.5 w-3.5 text-success-fg" />}
          label={`${summary.healthyResources} healthy`}
        />
        <SummaryItem
          icon={<CircleX className="h-3.5 w-3.5 text-danger-fg" />}
          label={`${unhealthyResources} attention`}
        />
        {limitNotice && (
          <span className="inline-flex items-center gap-1.5 text-warning-fg">
            <AlertTriangle className="h-3.5 w-3.5" />
            {limitNotice}
          </span>
        )}
      </div>
    </div>
  )
}

function CompactSelect<TValue extends string>({
  label,
  value,
  options,
  onChange,
}: {
  label: string
  value: TValue
  options: Array<Option<TValue>>
  onChange: (value: TValue) => void
}) {
  return (
    <div className="flex items-center gap-1.5">
      <span className="text-[11px] font-medium text-muted-foreground">{label}</span>
      <Select value={value} onValueChange={(nextValue) => onChange(nextValue as TValue)}>
        <SelectTrigger className="h-7 w-[132px] bg-background px-2 text-xs">
          <SelectValue />
        </SelectTrigger>
        <SelectContent>
          <SelectGroup>
            {options.map((option) => (
              <SelectItem key={option.value} value={option.value}>
                {option.label}
              </SelectItem>
            ))}
          </SelectGroup>
        </SelectContent>
      </Select>
    </div>
  )
}

function SummaryItem({icon, label}: {icon: ReactNode; label: string}) {
  return (
    <span className="inline-flex items-center gap-1.5 whitespace-nowrap">
      {icon}
      {label}
    </span>
  )
}

function InfrastructureMapBody({
  isError,
  isLoading,
  mapGraph,
  mapResult,
  resourceKind,
  fitSignature,
  selectedNode,
  onCloseDetail,
  onNodeClick,
  onPaneClick,
}: {
  isError: boolean
  isLoading: boolean
  mapGraph: {nodes: Node[]; edges: Edge[]}
  mapResult: ReturnType<typeof buildInfrastructureMapGroups>
  resourceKind: InfrastructureResourceKind
  fitSignature: string
  selectedNode: InfrastructureMapNode | null
  onCloseDetail: () => void
  onNodeClick: (event: ReactMouseEvent, node: Node) => void
  onPaneClick: () => void
}) {
  const hasNoResources = mapResult.summary.totalResources === 0
  const hasNoVisibleResources = mapResult.summary.totalResources > 0 && mapResult.summary.visibleResources === 0
  const emptyIcon = hasNoVisibleResources ? Search : resourceKind === 'hosts' ? HardDrive : Box
  const emptyTitle = hasNoVisibleResources
    ? 'No resources match your filters'
    : resourceKind === 'hosts'
      ? 'No hosts yet'
      : 'No containers yet'
  const emptyDescription = hasNoVisibleResources
    ? 'Adjust search or map controls.'
    : 'Connect infrastructure telemetry to populate this map.'

  return (
    <MapCanvas
      nodes={mapGraph.nodes}
      edges={mapGraph.edges}
      nodeTypes={infrastructureNodeTypes}
      onNodeClick={onNodeClick}
      onPaneClick={onPaneClick}
      isLoading={isLoading}
      isEmpty={hasNoResources || hasNoVisibleResources}
      emptyIcon={emptyIcon}
      emptyTitle={emptyTitle}
      emptyDescription={emptyDescription}
      fitSignature={fitSignature}
      fallback={isError ? (
        <div className="max-w-sm text-center">
          <MapIcon className="mx-auto h-8 w-8 text-muted-foreground/40" />
          <p className="mt-2 text-sm font-medium text-muted-foreground">Map data unavailable</p>
          <p className="mt-1 text-xs text-muted-foreground/75">
            Refresh the page or try again after the telemetry API responds.
          </p>
        </div>
      ) : undefined}
      className="h-full rounded-none border-0"
    >
      <MapLegend items={INFRA_LEGEND_ITEMS} />
      {selectedNode && (
        <InfrastructureDetailPanel node={selectedNode} onClose={onCloseDetail} />
      )}
    </MapCanvas>
  )
}

const InfrastructureResourceNode = memo(function InfrastructureResourceNode({
  data,
}: NodeProps<Node<InfrastructureFlowNodeData>>) {
  const tone = TONE_CLASSES[data.fillTone]
  const Icon = data.resourceKind === 'hosts' ? HardDrive : Box

  return (
    <div style={{width: INFRA_NODE_WIDTH}}>
      <MapNodeCard
        title={data.label}
        subtitle={data.subtitle}
        icon={<Icon className={cn('h-4 w-4', tone.icon)} />}
        iconContainerClassName={tone.iconBackground}
        borderClassName={tone.border}
        selected={data.selected}
        statusIndicator={(
          <Badge variant={tone.badge} size="sm" className="max-w-[72px] truncate">
            {data.statusLabel}
          </Badge>
        )}
        metrics={(
          <>
            <span className="truncate">{data.metricLabel}</span>
            <span className="truncate">Size: {data.sizeLabel}</span>
          </>
        )}
        footer={(
          <div className="mt-2 h-1.5 overflow-hidden rounded-full bg-muted">
            <div
              className="h-full rounded-full"
              style={{
                width: `${data.sizePercent}%`,
                backgroundColor: tone.color,
              }}
            />
          </div>
        )}
      />
    </div>
  )
})

const InfrastructureGroupNode = memo(function InfrastructureGroupNode({
  data,
}: NodeProps<Node<InfrastructureGroupNodeData>>) {
  return (
    <div
      className="rounded-lg border border-border/70 bg-card/45 shadow-sm backdrop-blur-sm"
      style={{width: data.width, height: data.height}}
    >
      <div className="flex h-[30px] items-center justify-between gap-3 border-b border-border/50 px-3 text-xs">
        <span className="truncate font-semibold">{data.label}</span>
        <span className="shrink-0 text-muted-foreground">
          {data.healthyCount}/{data.count} healthy
        </span>
      </div>
    </div>
  )
})

const infrastructureNodeTypes = {
  infrastructureResource: InfrastructureResourceNode,
  infrastructureGroup: InfrastructureGroupNode,
}

function InfrastructureDetailPanel({
  node,
  onClose,
}: {
  node: InfrastructureMapNode
  onClose: () => void
}) {
  const tone = TONE_CLASSES[node.fillTone]

  return (
    <MapDetailPanel title={node.label} onClose={onClose}>
      <div className="flex items-center justify-between gap-2">
        <span className="text-muted-foreground">Status</span>
        <Badge variant={tone.badge} className="h-5 text-[10px]">
          {node.statusLabel}
        </Badge>
      </div>
      <div className="flex justify-between gap-3">
        <span className="text-muted-foreground">Metric</span>
        <span className="truncate font-mono text-xs">{node.metricLabel}</span>
      </div>
      <div className="flex justify-between gap-3">
        <span className="text-muted-foreground">Size</span>
        <span className="truncate font-mono text-xs">{node.sizeLabel}</span>
      </div>
      <div className="border-t pt-1">
        <span className="text-[11px] text-muted-foreground">Details</span>
        <div className="mt-1 space-y-1">
          {node.details.map((detail) => (
            <div key={detail.label} className="flex justify-between gap-3">
              <span className="text-muted-foreground">{detail.label}</span>
              <span className="truncate font-mono text-xs">{detail.value}</span>
            </div>
          ))}
        </div>
      </div>
      {node.hostId != null && (
        <Button asChild size="sm" className="mt-1 h-8 w-full justify-center">
          <Link to="/monitoring/hosts/$hostId" params={{hostId: String(node.hostId)}}>
            Open host
          </Link>
        </Button>
      )}
    </MapDetailPanel>
  )
}

function buildInfrastructureFlowGraph(
  mapResult: ReturnType<typeof buildInfrastructureMapGroups>,
  resourceKind: InfrastructureResourceKind,
  selectedNodeId: string | null,
): {nodes: Node[]; edges: Edge[]} {
  const nodes: Node[] = []
  const groupWidth =
    INFRA_GROUP_COLUMNS * INFRA_NODE_WIDTH +
    (INFRA_GROUP_COLUMNS - 1) * INFRA_NODE_GAP +
    INFRA_GROUP_PADDING * 2
  let cursorY = INFRA_NODE_GAP

  for (const group of mapResult.groups) {
    const rowCount = Math.max(1, Math.ceil(group.nodes.length / INFRA_GROUP_COLUMNS))
    const groupHeight =
      INFRA_GROUP_HEADER_HEIGHT +
      INFRA_GROUP_PADDING +
      rowCount * INFRA_NODE_HEIGHT +
      Math.max(0, rowCount - 1) * INFRA_NODE_GAP +
      INFRA_GROUP_PADDING
    const groupId = `group-${group.key}`

    nodes.push({
      id: groupId,
      type: 'infrastructureGroup',
      position: {x: INFRA_NODE_GAP, y: cursorY},
      data: {
        label: group.label,
        count: group.nodes.length,
        healthyCount: group.healthyCount,
        width: groupWidth,
        height: groupHeight,
      } satisfies InfrastructureGroupNodeData,
      selectable: false,
      draggable: false,
      style: {width: groupWidth, height: groupHeight},
    })

    group.nodes.forEach((node, index) => {
      const column = index % INFRA_GROUP_COLUMNS
      const row = Math.floor(index / INFRA_GROUP_COLUMNS)
      nodes.push({
        id: node.id,
        type: 'infrastructureResource',
        parentId: groupId,
        extent: 'parent',
        position: {
          x: INFRA_GROUP_PADDING + column * (INFRA_NODE_WIDTH + INFRA_NODE_GAP),
          y: INFRA_GROUP_HEADER_HEIGHT + INFRA_GROUP_PADDING + row * (INFRA_NODE_HEIGHT + INFRA_NODE_GAP),
        },
        data: {
          ...node,
          resourceKind,
          selected: selectedNodeId === node.id,
        } satisfies InfrastructureFlowNodeData,
      })
    })

    cursorY += groupHeight + INFRA_GROUP_GAP
  }

  return {nodes, edges: []}
}

function getGroupOptions(resourceKind: InfrastructureResourceKind): Array<Option<InfrastructureGroupBy>> {
  return resourceKind === 'containers' ? CONTAINER_GROUP_OPTIONS : HOST_GROUP_OPTIONS
}

function getFillOptions(resourceKind: InfrastructureResourceKind): Array<Option<InfrastructureFillBy>> {
  return resourceKind === 'containers' ? CONTAINER_FILL_OPTIONS : HOST_FILL_OPTIONS
}

function getSizeOptions(resourceKind: InfrastructureResourceKind): Array<Option<InfrastructureSizeBy>> {
  return resourceKind === 'containers' ? CONTAINER_SIZE_OPTIONS : HOST_SIZE_OPTIONS
}

function getDefaultView(resourceKind: InfrastructureResourceKind): InfrastructureMapViewState {
  return resourceKind === 'containers' ? DEFAULT_CONTAINER_VIEW : DEFAULT_HOST_VIEW
}

function normalizeViewState(view: InfrastructureMapViewState): InfrastructureMapViewState {
  const defaults = getDefaultView(view.resourceKind)
  return {
    resourceKind: view.resourceKind,
    groupBy: includesOption(getGroupOptions(view.resourceKind), view.groupBy) ? view.groupBy : defaults.groupBy,
    fillBy: includesOption(getFillOptions(view.resourceKind), view.fillBy) ? view.fillBy : defaults.fillBy,
    sizeBy: includesOption(getSizeOptions(view.resourceKind), view.sizeBy) ? view.sizeBy : defaults.sizeBy,
    searchQuery: view.searchQuery,
  }
}

function includesOption<TValue extends string>(options: Array<Option<TValue>>, value: TValue): boolean {
  return options.some((option) => option.value === value)
}

function ignoreFacetFilters() {
  return undefined
}

function upsertSavedView(
  savedViews: SavedInfrastructureMapView[],
  savedView: SavedInfrastructureMapView,
): SavedInfrastructureMapView[] {
  const nextViews = savedViews.filter((view) => view.id !== savedView.id)
  return [savedView, ...nextViews]
}
