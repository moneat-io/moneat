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
import {Box, HardDrive, Layers3, LayoutGrid, Network, Save, Search, Trash2} from 'lucide-react'
import {useMemo, useState, type ComponentType, type ReactNode} from 'react'
import {api} from '@/lib/api'
import type {DdHostResponse} from '@/lib/api/types/hosts'
import type {DdContainerResponse} from '@/lib/api/types/infrastructure'
import type {ApmFacetItem, ApmServiceMapEntry, ApmTimeRange} from '@/lib/api/types/apm'
import {useToast} from '@/hooks/useToast'
import {ServiceMap} from '@/components/apm/ServiceMap'
import {buildServiceFacetSections} from '@/components/apm/serviceMapModel'
import {MapDetailDock} from '@/components/maps/MapDetailDock'
import {Hostmap} from './Hostmap'
import {toHostmapView} from './hostmapModel'
import {applyInfrastructureFacetFilters, buildInfrastructureFacetSections} from './infrastructureFacets'
import {MapExplorerShell} from './map/MapExplorerShell'
import {MapFacetRail} from './map/MapFacetRail'
import {MapControlSelect, MapModeSwitch, MapSearchInput, type SegmentedOption} from './map/MapControls'
import {HostmapToolbar} from './map/HostmapToolbar'
import {Badge} from '@/components/ui/badge'
import {Button} from '@/components/ui/button'
import {Input} from '@/components/ui/input'
import {Label} from '@/components/ui/label'
import {Popover, PopoverContent, PopoverTrigger} from '@/components/ui/popover'
import {Select, SelectContent, SelectGroup, SelectItem, SelectTrigger, SelectValue} from '@/components/ui/select'
import type {FacetFilter, FacetRailSection, FacetValue} from '@/lib/filters/types'
import {
  buildInfrastructureMapGroups,
  createInfrastructureMapResources,
  type InfrastructureFillBy,
  type InfrastructureGroupBy,
  type InfrastructureMapNode,
  type InfrastructureMapSummary,
  type InfrastructureMapTone,
  type InfrastructureMapViewState,
  type InfrastructureResourceKind,
  type InfrastructureSizeBy,
  type SavedInfrastructureMapView,
} from './infrastructureMapModel'

const HOST_REFRESH_MS = 30_000
const CONTAINER_REFRESH_MS = 10_000
const SERVICE_MAP_REFRESH_MS = 30_000
const CONTAINER_LIMIT = 500
const HOST_PROCESS_LIMIT = 20
const HOST_TOP_PROCESS_COUNT = 5
const SAVED_VIEW_NAME_MAX_LENGTH = 48
const SAVED_VIEW_SEARCH_QUERY_MAX_LENGTH = 200
const NET_FULL_SCALE_BYTES = 1024 ** 3 // 1 GB/s reads as a full Net gauge.
const BYTES_PER_KB = 1024
const BYTES_PER_MB = 1024 ** 2
const BYTES_PER_GB = 1024 ** 3
const HIGH_GAUGE_PERCENT = 80
const MEDIUM_GAUGE_PERCENT = 50
const EMPTY_HOSTS: DdHostResponse[] = []
const EMPTY_CONTAINERS: DdContainerResponse[] = []
const EMPTY_SAVED_VIEWS: SavedInfrastructureMapView[] = []
const EMPTY_FACET_FILTERS: FacetFilter[] = []
const NO_SAVED_VIEW_ID = 'none'
const INFRASTRUCTURE_MAP_SAVED_VIEWS_QUERY_KEY = ['infrastructure-map-saved-views'] as const

type BadgeVariant = 'success' | 'danger' | 'warning' | 'info' | 'neutral'

type Option<TValue extends string> = Readonly<{
  value: TValue
  label: string
}>

export type MonitoringMapScope = InfrastructureResourceKind | 'services'
type MonitoringMapMode = 'services' | 'infrastructure'

const MODE_OPTIONS: ReadonlyArray<SegmentedOption<MonitoringMapMode>> = [
  {value: 'services', label: 'Service map', icon: Network},
  {value: 'infrastructure', label: 'Infrastructure', icon: LayoutGrid},
]

const KIND_OPTIONS: Array<Option<InfrastructureResourceKind> & Readonly<{icon: ComponentType<{className?: string}>}>> = [
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

// --- Services scope (APM service map) ---

const DEFAULT_SERVICE_TIME_RANGE: ApmTimeRange = '24h'
const ALL_ENVS = '__all_envs__'
const ALL_SOURCES = '__all_sources__'
const EMPTY_FACET_ITEMS: ApmFacetItem[] = []
const EMPTY_SERVICE_ENTRIES: ApmServiceMapEntry[] = []
const ENV_FACET_KEY = 'env'

// Rail swatch colors (tailwind utilities backed by the theme tokens), echoing
// the mockup: env=cyan, health=red, type=green; infra status=green.
const SERVICE_FACET_COLORS = {env: 'bg-chart-1', health: 'bg-danger-solid', type: 'bg-chart-4'} as const

const RANGE_OPTIONS: Array<Option<ApmTimeRange>> = [
  {value: '1h', label: '1h'},
  {value: '6h', label: '6h'},
  {value: '24h', label: '24h'},
  {value: '7d', label: '7d'},
  {value: '30d', label: '30d'},
  {value: '90d', label: '90d'},
]

// Tone -> badge variant, the app's shared status language.
const TONE_BADGE: Record<InfrastructureMapTone, BadgeVariant> = {
  success: 'success',
  danger: 'danger',
  warning: 'warning',
  info: 'info',
  neutral: 'neutral',
}

type MonitoringMapProps = Readonly<{
  initialScope?: MonitoringMapScope
  onScopeChange?: (scope: MonitoringMapScope) => void
}>

type SavedViewsPopoverProps = Readonly<{
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
}>

type HostDetailDockProps = Readonly<{
  node: InfrastructureMapNode
  resourceKind: InfrastructureResourceKind
  onClose: () => void
}>

type ServiceMapControlsProps = Readonly<{
  timeRange: ApmTimeRange
  env?: string
  source?: string
  envOptions: ApmFacetItem[]
  sourceOptions: ApmFacetItem[]
  onTimeRangeChange: (timeRange: ApmTimeRange) => void
  onEnvChange: (env?: string) => void
  onSourceChange: (source?: string) => void
}>

type InfrastructureMapControlsProps = Readonly<{
  viewState: InfrastructureMapViewState
  groupOptions: Array<Option<InfrastructureGroupBy>>
  fillOptions: Array<Option<InfrastructureFillBy>>
  sizeOptions: Array<Option<InfrastructureSizeBy>>
  onScopeChange: (resourceKind: InfrastructureResourceKind) => void
  onViewStateChange: (nextViewState: Partial<InfrastructureMapViewState>) => void
}>

type MonitoringMapBodyProps = Readonly<{
  isServicesScope: boolean
  serviceSearchQuery: string
  serviceTimeRange: ApmTimeRange
  serviceEnv?: string
  serviceSource?: string
  serviceFacetFilters: FacetFilter[]
  hostmapView: ReturnType<typeof toHostmapView>
  selectedNode: InfrastructureMapNode | null
  selectedNodeId: string | null
  activeViewState: InfrastructureMapViewState
  summary: InfrastructureMapSummary
  unhealthyResources: number
  fillLabel: string
  containerLimitNotice: string | null
  isLoading: boolean
  isError: boolean
  hasNoResources: boolean
  hasNoVisibleResources: boolean
  onSelectNode: (id: string) => void
  onCloseNode: () => void
}>

export function MonitoringMap({initialScope = 'services', onScopeChange}: MonitoringMapProps) {
  const queryClient = useQueryClient()
  const {toast} = useToast()
  const isAuthenticated = api.isAuthenticated()
  const [mapScope, setMapScope] = useState<MonitoringMapScope>(initialScope)
  const [infraKind, setInfraKind] = useState<InfrastructureResourceKind>(() => getInitialInfrastructureKind(initialScope))
  const [viewState, setViewState] = useState<InfrastructureMapViewState>(() => getInitialViewState(initialScope))
  const [serviceSearchQuery, setServiceSearchQuery] = useState('')
  const [serviceTimeRange, setServiceTimeRange] = useState<ApmTimeRange>(DEFAULT_SERVICE_TIME_RANGE)
  const [serviceEnv, setServiceEnv] = useState<string | undefined>(undefined)
  const [serviceSource, setServiceSource] = useState<string | undefined>(undefined)
  const [selectedSavedViewId, setSelectedSavedViewId] = useState(NO_SAVED_VIEW_ID)
  const [savedViewName, setSavedViewName] = useState('')
  const [selectedNodeId, setSelectedNodeId] = useState<string | null>(null)
  const [infraFacetFilters, setInfraFacetFilters] = useState<FacetFilter[]>(EMPTY_FACET_FILTERS)
  const [serviceFacetFilters, setServiceFacetFilters] = useState<FacetFilter[]>(EMPTY_FACET_FILTERS)
  const [syncedScope, setSyncedScope] = useState<MonitoringMapScope>(initialScope)
  const isServicesScope = mapScope === 'services'

  // Sync local scope when the route's scope prop changes (e.g. browser back),
  // using React's render-time "adjust state on prop change" pattern rather than
  // an effect, so there is no cascading-render lint violation.
  if (initialScope !== syncedScope) {
    setSyncedScope(initialScope)
    setMapScope(initialScope)
    if (isInfrastructureScope(initialScope)) setInfraKind(initialScope)
  }

  const activeViewState = useMemo(() => {
    if (isServicesScope || viewState.resourceKind === mapScope) return viewState
    return {...getDefaultView(mapScope), searchQuery: viewState.searchQuery}
  }, [isServicesScope, mapScope, viewState])

  const hostsQuery = useQuery({
    queryKey: ['hosts'],
    queryFn: () => api.getHosts(),
    enabled: shouldFetchHosts(isAuthenticated, isServicesScope),
    refetchInterval: HOST_REFRESH_MS,
  })

  const containersQuery = useQuery({
    queryKey: ['containers', 'map'],
    queryFn: () => api.getContainers({limit: CONTAINER_LIMIT}),
    enabled: shouldFetchContainers(isAuthenticated, isServicesScope, activeViewState.resourceKind),
    refetchInterval: CONTAINER_REFRESH_MS,
  })

  const savedViewsQuery = useQuery({
    queryKey: INFRASTRUCTURE_MAP_SAVED_VIEWS_QUERY_KEY,
    queryFn: () => api.getInfrastructureMapSavedViews(),
    enabled: shouldFetchSavedViews(isAuthenticated, isServicesScope),
  })

  const serviceFacetsQuery = useQuery({
    queryKey: ['apmServiceMapFacets', serviceTimeRange],
    queryFn: () => api.getApmOverview({timeRange: serviceTimeRange}),
    enabled: shouldFetchServiceData(isAuthenticated, isServicesScope),
    staleTime: 60_000,
  })

  // Same query key as <ServiceMap>, so React Query serves both from one fetch.
  // We read it only to tally the Health/Type facet sections for the rail.
  const serviceMapDataQuery = useQuery({
    queryKey: ['apmServiceMap', serviceTimeRange, serviceEnv ?? '', serviceSource ?? ''],
    queryFn: () => api.getApmServiceMap({timeRange: serviceTimeRange, env: serviceEnv, source: serviceSource}),
    enabled: shouldFetchServiceData(isAuthenticated, isServicesScope),
    refetchInterval: SERVICE_MAP_REFRESH_MS,
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
      toast({title: 'Failed to save view', description: error.message, variant: 'destructive'})
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
      toast({title: 'Failed to delete view', description: error.message, variant: 'destructive'})
    },
  })

  const hosts = hostsQuery.data?.hosts ?? EMPTY_HOSTS
  const containers = containersQuery.data?.containers ?? EMPTY_CONTAINERS
  const savedViews = savedViewsQuery.data?.views ?? EMPTY_SAVED_VIEWS
  const serviceEnvOptions = serviceFacetsQuery.data?.facets.environments ?? EMPTY_FACET_ITEMS
  const serviceSourceOptions = serviceFacetsQuery.data?.facets.sources ?? EMPTY_FACET_ITEMS
  const serviceMapServices = serviceMapDataQuery.data?.services ?? EMPTY_SERVICE_ENTRIES
  const resources = useMemo(
    () => createInfrastructureMapResources({resourceKind: activeViewState.resourceKind, hosts, containers}),
    [activeViewState.resourceKind, containers, hosts],
  )
  const filteredResources = useMemo(
    () => applyInfrastructureFacetFilters(resources, infraFacetFilters),
    [resources, infraFacetFilters],
  )
  const mapResult = useMemo(
    () => buildInfrastructureMapGroups(filteredResources, activeViewState),
    [activeViewState, filteredResources],
  )
  const infraRailSections = useMemo(
    () => buildInfrastructureFacetSections(resources, activeViewState.resourceKind),
    [resources, activeViewState.resourceKind],
  )
  const serviceFacetData = useMemo(() => buildServiceFacetSections(serviceMapServices), [serviceMapServices])
  const serviceRailSections = useMemo<FacetRailSection[]>(
    () => [
      {
        key: ENV_FACET_KEY,
        label: 'Environment',
        color: SERVICE_FACET_COLORS.env,
        singleSelect: true,
        allowExclude: false,
        options: serviceEnvOptions.map(facetItemToValue),
      },
      {key: 'health', label: 'Health', color: SERVICE_FACET_COLORS.health, options: serviceFacetData.health},
      {key: 'type', label: 'Type', color: SERVICE_FACET_COLORS.type, options: serviceFacetData.type},
    ],
    [serviceEnvOptions, serviceFacetData],
  )
  // The rail's env section and the header's env control are two views of one
  // value: compose env into the rail filters, split it back out on change.
  const serviceRailFilters = useMemo<FacetFilter[]>(
    () => (serviceEnv ? [{key: ENV_FACET_KEY, value: serviceEnv}, ...serviceFacetFilters] : serviceFacetFilters),
    [serviceEnv, serviceFacetFilters],
  )
  const hostmapView = useMemo(() => toHostmapView(mapResult), [mapResult])
  const selectedNode = useMemo(
    () => mapResult.groups.flatMap((group) => group.nodes).find((node) => node.id === selectedNodeId) ?? null,
    [mapResult.groups, selectedNodeId],
  )
  const groupOptions = getGroupOptions(activeViewState.resourceKind)
  const fillOptions = getFillOptions(activeViewState.resourceKind)
  const sizeOptions = getSizeOptions(activeViewState.resourceKind)
  const fillLabel = fillOptions.find((option) => option.value === activeViewState.fillBy)?.label ?? 'Utilization'
  const isLoading = isInfrastructureLoading({
    isServicesScope,
    resourceKind: activeViewState.resourceKind,
    isHostsLoading: hostsQuery.isLoading,
    isContainersLoading: containersQuery.isLoading,
  })
  const isError = isInfrastructureError({
    isServicesScope,
    resourceKind: activeViewState.resourceKind,
    isHostsError: hostsQuery.isError,
    isContainersError: containersQuery.isError,
  })
  const unhealthyResources = mapResult.summary.visibleResources - mapResult.summary.healthyResources
  const containerTotalCount = containersQuery.data?.totalCount ?? containers.length
  const containerLimitNotice = getContainerLimitNotice(activeViewState.resourceKind, containerTotalCount)
  // "No data at all" vs "filtered/searched to nothing" — judged against the
  // unfiltered resource set so facets/search show the right empty message.
  const hasNoResources = resources.length === 0
  const hasNoVisibleResources = hasFilteredOutAllResources(resources.length, mapResult.summary.visibleResources)

  function updateViewState(nextViewState: Partial<InfrastructureMapViewState>) {
    setViewState(normalizeViewState({...activeViewState, ...nextViewState}))
    setSelectedSavedViewId(NO_SAVED_VIEW_ID)
    setSelectedNodeId(null)
  }

  function applyScope(nextScope: MonitoringMapScope) {
    setMapScope(nextScope)
    onScopeChange?.(nextScope)
    setSelectedNodeId(null)
    if (nextScope !== 'services') {
      // Host/container facet keys differ (platform vs image), so reset on switch.
      if (nextScope !== infraKind) setInfraFacetFilters(EMPTY_FACET_FILTERS)
      setInfraKind(nextScope)
      const defaults = getDefaultView(nextScope)
      setViewState((current) => ({...defaults, searchQuery: current.searchQuery}))
      setSelectedSavedViewId(NO_SAVED_VIEW_ID)
    }
  }

  function loadSavedView(savedViewId: string) {
    setSelectedSavedViewId(savedViewId)
    if (savedViewId === NO_SAVED_VIEW_ID) return

    const savedView = savedViews.find((view) => view.id === savedViewId)
    if (!savedView) return
    setMapScope(savedView.resourceKind)
    setInfraKind(savedView.resourceKind)
    onScopeChange?.(savedView.resourceKind)
    setViewState(normalizeViewState(savedView))
    setSavedViewName(savedView.name)
    setSelectedNodeId(null)
    setInfraFacetFilters(EMPTY_FACET_FILTERS)
  }

  function saveCurrentView() {
    const name = savedViewName.trim().slice(0, SAVED_VIEW_NAME_MAX_LENGTH)
    if (!name) return
    const searchQuery = activeViewState.searchQuery.trim().slice(0, SAVED_VIEW_SEARCH_QUERY_MAX_LENGTH)
    saveSavedViewMutation.mutate({...activeViewState, name, searchQuery})
  }

  function deleteSelectedView() {
    if (selectedSavedViewId === NO_SAVED_VIEW_ID) return
    deleteSavedViewMutation.mutate(selectedSavedViewId)
  }

  function handleSelectNode(id: string) {
    setSelectedNodeId((currentId) => (currentId === id ? null : id))
  }

  function handleServiceRailChange(next: FacetFilter[]) {
    const envFilter = next.find((filter) => filter.key === ENV_FACET_KEY)
    setServiceEnv(envFilter?.value)
    setServiceFacetFilters(next.filter((filter) => filter.key !== ENV_FACET_KEY))
    setSelectedNodeId(null)
  }

  function handleInfraRailChange(next: FacetFilter[]) {
    setInfraFacetFilters(next)
    setSelectedNodeId(null)
  }

  function handleSearchChange(query: string) {
    if (isServicesScope) {
      setServiceSearchQuery(query)
      return
    }
    updateViewState({searchQuery: query})
  }

  return (
    <div className="h-[calc(100vh-var(--header-height,0px)-43px)] min-h-[520px]">
      <MapExplorerShell
        title={getMapTitle(isServicesScope)}
        icon={<MapScopeIcon isServicesScope={isServicesScope} />}
        modeSwitch={(
          <MapModeSwitch
            ariaLabel="Map mode"
            value={getMapMode(isServicesScope)}
            options={MODE_OPTIONS}
            onChange={(mode) => applyScope(getScopeForMode(mode, infraKind))}
          />
        )}
        search={(
          <MapSearchInput
            value={getSearchValue(isServicesScope, serviceSearchQuery, activeViewState.searchQuery)}
            onChange={handleSearchChange}
            placeholder={getSearchPlaceholder(isServicesScope)}
          />
        )}
        controls={(
          <MonitoringMapControls
            isServicesScope={isServicesScope}
            serviceControls={(
              <ServiceMapControls
                timeRange={serviceTimeRange}
                env={serviceEnv}
                source={serviceSource}
                envOptions={serviceEnvOptions}
                sourceOptions={serviceSourceOptions}
                onTimeRangeChange={setServiceTimeRange}
                onEnvChange={setServiceEnv}
                onSourceChange={setServiceSource}
              />
            )}
            infrastructureControls={(
              <InfrastructureMapControls
                viewState={activeViewState}
                groupOptions={groupOptions}
                fillOptions={fillOptions}
                sizeOptions={sizeOptions}
                onScopeChange={applyScope}
                onViewStateChange={updateViewState}
              />
            )}
          />
        )}
        actions={getInfrastructureActions(isServicesScope, (
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
        ))}
        rail={(
          <MonitoringMapRail
            isServicesScope={isServicesScope}
            serviceRailSections={serviceRailSections}
            serviceRailFilters={serviceRailFilters}
            infraRailSections={infraRailSections}
            infraFacetFilters={infraFacetFilters}
            onServiceRailChange={handleServiceRailChange}
            onInfraRailChange={handleInfraRailChange}
          />
        )}
      >
        <MonitoringMapBody
          isServicesScope={isServicesScope}
          serviceSearchQuery={serviceSearchQuery}
          serviceTimeRange={serviceTimeRange}
          serviceEnv={serviceEnv}
          serviceSource={serviceSource}
          serviceFacetFilters={serviceFacetFilters}
          hostmapView={hostmapView}
          selectedNode={selectedNode}
          selectedNodeId={selectedNodeId}
          activeViewState={activeViewState}
          summary={mapResult.summary}
          unhealthyResources={unhealthyResources}
          fillLabel={fillLabel}
          containerLimitNotice={containerLimitNotice}
          isLoading={isLoading}
          isError={isError}
          hasNoResources={hasNoResources}
          hasNoVisibleResources={hasNoVisibleResources}
          onSelectNode={handleSelectNode}
          onCloseNode={() => setSelectedNodeId(null)}
        />
      </MapExplorerShell>
    </div>
  )
}

type MonitoringMapControlsProps = Readonly<{
  isServicesScope: boolean
  serviceControls: ReactNode
  infrastructureControls: ReactNode
}>

function MonitoringMapControls({
  isServicesScope,
  serviceControls,
  infrastructureControls,
}: MonitoringMapControlsProps) {
  return <>{isServicesScope ? serviceControls : infrastructureControls}</>
}

function ServiceMapControls({
  timeRange,
  env,
  source,
  envOptions,
  sourceOptions,
  onTimeRangeChange,
  onEnvChange,
  onSourceChange,
}: ServiceMapControlsProps) {
  return (
    <>
      <MapControlSelect label="Range" value={timeRange} options={RANGE_OPTIONS} onChange={onTimeRangeChange} />
      <MapControlSelect
        label="env"
        value={env ?? ALL_ENVS}
        options={toScopeOptions(envOptions, 'All envs', ALL_ENVS)}
        onChange={(value) => onEnvChange(value === ALL_ENVS ? undefined : value)}
      />
      {sourceOptions.length > 0 && (
        <MapControlSelect
          label="source"
          value={source ?? ALL_SOURCES}
          options={toScopeOptions(sourceOptions, 'All sources', ALL_SOURCES)}
          onChange={(value) => onSourceChange(value === ALL_SOURCES ? undefined : value)}
        />
      )}
    </>
  )
}

function InfrastructureMapControls({
  viewState,
  groupOptions,
  fillOptions,
  sizeOptions,
  onScopeChange,
  onViewStateChange,
}: InfrastructureMapControlsProps) {
  return (
    <>
      <MapControlSelect label="Kind" value={viewState.resourceKind} options={KIND_OPTIONS} onChange={onScopeChange} />
      <MapControlSelect
        label="Group"
        value={viewState.groupBy}
        options={groupOptions}
        onChange={(groupBy) => onViewStateChange({groupBy})}
      />
      <MapControlSelect
        label="Color"
        value={viewState.fillBy}
        options={fillOptions}
        onChange={(fillBy) => onViewStateChange({fillBy})}
      />
      <MapControlSelect
        label="Size"
        value={viewState.sizeBy}
        options={sizeOptions}
        onChange={(sizeBy) => onViewStateChange({sizeBy})}
      />
    </>
  )
}

type MonitoringMapRailProps = Readonly<{
  isServicesScope: boolean
  serviceRailSections: FacetRailSection[]
  serviceRailFilters: FacetFilter[]
  infraRailSections: FacetRailSection[]
  infraFacetFilters: FacetFilter[]
  onServiceRailChange: (filters: FacetFilter[]) => void
  onInfraRailChange: (filters: FacetFilter[]) => void
}>

function MonitoringMapRail({
  isServicesScope,
  serviceRailSections,
  serviceRailFilters,
  infraRailSections,
  infraFacetFilters,
  onServiceRailChange,
  onInfraRailChange,
}: MonitoringMapRailProps) {
  if (isServicesScope) {
    return (
      <MapFacetRail
        sections={serviceRailSections}
        facetFilters={serviceRailFilters}
        onFacetFiltersChange={onServiceRailChange}
      />
    )
  }

  return (
    <MapFacetRail
      sections={infraRailSections}
      facetFilters={infraFacetFilters}
      onFacetFiltersChange={onInfraRailChange}
    />
  )
}

function MonitoringMapBody({
  isServicesScope,
  serviceSearchQuery,
  serviceTimeRange,
  serviceEnv,
  serviceSource,
  serviceFacetFilters,
  hostmapView,
  selectedNode,
  selectedNodeId,
  activeViewState,
  summary,
  unhealthyResources,
  fillLabel,
  containerLimitNotice,
  isLoading,
  isError,
  hasNoResources,
  hasNoVisibleResources,
  onSelectNode,
  onCloseNode,
}: MonitoringMapBodyProps) {
  if (isServicesScope) {
    return (
      <ServiceMap
        className="service-map h-full rounded-none border-0"
        searchQuery={serviceSearchQuery}
        timeRange={serviceTimeRange}
        env={serviceEnv}
        source={serviceSource}
        facetFilters={serviceFacetFilters}
      />
    )
  }

  return (
    <div className="flex h-full min-h-0 flex-col">
      <HostmapToolbar
        resourceNoun={activeViewState.resourceKind}
        totalResources={summary.visibleResources}
        healthyResources={summary.healthyResources}
        unhealthyResources={unhealthyResources}
        fillLabel={fillLabel}
        limitNotice={containerLimitNotice}
      />
      <div className="relative flex min-h-0 flex-1">
        <Hostmap
          view={hostmapView}
          selectedId={selectedNodeId}
          onSelectNode={onSelectNode}
          isLoading={isLoading}
          isEmpty={isHostmapEmpty(hasNoResources, hasNoVisibleResources)}
          emptyIcon={getInfraEmptyIcon(hasNoVisibleResources, activeViewState.resourceKind)}
          emptyTitle={getInfraEmptyTitle(hasNoVisibleResources, activeViewState.resourceKind)}
          emptyDescription={getInfraEmptyDescription(hasNoVisibleResources)}
          fallback={getInfrastructureFallback(isError)}
        />
        {selectedNode && (
          <HostDetailDock node={selectedNode} resourceKind={activeViewState.resourceKind} onClose={onCloseNode} />
        )}
      </div>
    </div>
  )
}

function MapDataUnavailable() {
  return (
    <div className="max-w-sm text-center">
      <p className="text-sm font-medium text-muted-foreground">Map data unavailable</p>
      <p className="mt-1 text-xs text-muted-foreground/75">
        Refresh the page or try again after the telemetry API responds.
      </p>
    </div>
  )
}

function MapScopeIcon({isServicesScope}: Readonly<{isServicesScope: boolean}>) {
  return (
    <span className="grid h-6 w-6 place-items-center rounded-md bg-primary/10 text-primary">
      {isServicesScope ? <Network className="h-3.5 w-3.5" /> : <Layers3 className="h-3.5 w-3.5" />}
    </span>
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
}: SavedViewsPopoverProps) {
  return (
    <Popover>
      <PopoverTrigger asChild>
        <Button variant="outline" size="sm" className="h-7 gap-1.5 px-2.5 text-xs">
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
              htmlFor="monitoring-map-view-name"
              className="text-[11px] uppercase tracking-wider text-muted-foreground"
            >
              Save as
            </Label>
            <Input
              id="monitoring-map-view-name"
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

function HostDetailDock({node, resourceKind, onClose}: HostDetailDockProps) {
  const isHost = resourceKind === 'hosts' && node.hostId !== undefined

  // Live system gauges + top processes are host-only (containers carry their own
  // metrics in node.details, and getProcesses/getHostMetrics are host-scoped).
  const metricsQuery = useQuery({
    queryKey: ['hostMetrics', node.hostId],
    queryFn: () => api.getHostMetrics(node.hostId ?? ''),
    enabled: api.isAuthenticated() && isHost,
    refetchInterval: HOST_REFRESH_MS,
  })
  const processesQuery = useQuery({
    queryKey: ['hostProcesses', node.label],
    queryFn: () => api.getProcesses({host: node.label, limit: HOST_PROCESS_LIMIT}),
    enabled: api.isAuthenticated() && isHost,
  })
  const topProcesses = useMemo(() => {
    const rows = processesQuery.data?.processes ?? []
    return [...rows].sort((a, b) => b.cpuPercent - a.cpuPercent).slice(0, HOST_TOP_PROCESS_COUNT)
  }, [processesQuery.data])
  const tagEntries = Object.entries(node.tags)

  const points = metricsQuery.data?.data_points ?? []
  const latest = points.at(-1)
  const netBytes = latest ? (latest.net_recv_bytes ?? 0) + (latest.net_sent_bytes ?? 0) : undefined

  return (
    <MapDetailDock title={node.label} subtitle={node.subtitle} onClose={onClose}>
      <div className="flex items-center justify-between gap-2">
        <span className="text-muted-foreground">Status</span>
        <Badge variant={TONE_BADGE[node.fillTone]} className="h-5 text-[10px]">
          {node.statusLabel}
        </Badge>
      </div>

      {isHost ? (
        <div className="space-y-1.5">
          <BarGauge label="CPU" percent={latest?.cpu_percent} value={formatPercentValue(latest?.cpu_percent)} />
          <BarGauge label="Mem" percent={latest?.mem_percent} value={formatPercentValue(latest?.mem_percent)} />
          <BarGauge label="Disk" percent={latest?.disk_percent} value={formatPercentValue(latest?.disk_percent)} />
          <BarGauge
            label="Net"
            percent={getNetPercent(netBytes)}
            value={formatOptionalBytesShort(netBytes)}
          />
        </div>
      ) : (
        <div className="space-y-1">
          {node.details.map((detail) => (
            <div key={detail.label} className="flex justify-between gap-3">
              <span className="text-muted-foreground">{detail.label}</span>
              <span className="truncate font-mono text-xs">{detail.value}</span>
            </div>
          ))}
        </div>
      )}

      {topProcesses.length > 0 && (
        <div className="border-t pt-2">
          <span className="text-[11px] uppercase tracking-wider text-muted-foreground">Top processes</span>
          <div className="mt-1 space-y-1">
            {topProcesses.map((process) => (
              <div key={process.processId} className="flex items-center justify-between gap-3">
                <span className="truncate font-mono text-xs" title={process.command || process.name}>
                  {process.name}
                </span>
                <span className="shrink-0 font-mono text-[11px] text-muted-foreground">
                  {process.cpuPercent.toFixed(1)}%
                </span>
              </div>
            ))}
          </div>
        </div>
      )}

      {tagEntries.length > 0 && (
        <div className="border-t pt-2">
          <span className="text-[11px] uppercase tracking-wider text-muted-foreground">Tags</span>
          <div className="mt-1 flex flex-wrap gap-1">
            {tagEntries.map(([key, value]) => (
              <span
                key={key}
                className="rounded-full border bg-muted/40 px-2 py-0.5 font-mono text-[10px] text-muted-foreground"
              >
                {key}:{value}
              </span>
            ))}
          </div>
        </div>
      )}

      {node.hostId !== undefined && (
        <Button asChild size="sm" className="mt-1 h-8 w-full justify-center">
          <Link to="/monitoring/hosts/$hostId" params={{hostId: String(node.hostId)}}>
            Open host
          </Link>
        </Button>
      )}
    </MapDetailDock>
  )
}

function BarGauge({label, percent, value}: Readonly<{label: string; percent?: number; value: string}>) {
  const pct = Math.max(0, Math.min(100, percent ?? 0))
  return (
    <div className="flex items-center gap-2 text-xs">
      <span className="w-9 shrink-0 text-muted-foreground">{label}</span>
      <span className="h-1.5 flex-1 overflow-hidden rounded-full bg-muted">
        <span className="block h-full rounded-full" style={{width: `${pct}%`, backgroundColor: gaugeColor(pct)}} />
      </span>
      <span className="w-12 shrink-0 text-right font-mono text-muted-foreground">{value}</span>
    </div>
  )
}

function gaugeColor(percent: number): string {
  if (percent >= HIGH_GAUGE_PERCENT) return 'hsl(var(--danger-solid))'
  if (percent >= MEDIUM_GAUGE_PERCENT) return 'hsl(var(--warning-solid))'
  return 'hsl(var(--success-solid))'
}

function formatPercentValue(value?: number): string {
  if (value === undefined) return '—'
  return `${Math.round(value)}%`
}

function getNetPercent(bytes?: number): number | undefined {
  if (bytes === undefined) return undefined
  return Math.min(100, (bytes / NET_FULL_SCALE_BYTES) * 100)
}

function formatOptionalBytesShort(bytes?: number): string {
  if (bytes === undefined) return '—'
  return formatBytesShort(bytes)
}

function formatBytesShort(bytes: number): string {
  if (bytes >= BYTES_PER_GB) return `${(bytes / BYTES_PER_GB).toFixed(1)}GB`
  if (bytes >= BYTES_PER_MB) return `${Math.round(bytes / BYTES_PER_MB)}MB`
  if (bytes >= BYTES_PER_KB) return `${Math.round(bytes / BYTES_PER_KB)}KB`
  return `${Math.round(bytes)}B`
}

function getGroupOptions(resourceKind: InfrastructureResourceKind): Array<Option<InfrastructureGroupBy>> {
  return resourceKind === 'containers' ? CONTAINER_GROUP_OPTIONS : HOST_GROUP_OPTIONS
}

function getInitialInfrastructureKind(scope: MonitoringMapScope): InfrastructureResourceKind {
  if (scope === 'containers') return 'containers'
  return 'hosts'
}

function getInitialViewState(scope: MonitoringMapScope): InfrastructureMapViewState {
  if (scope === 'containers') return DEFAULT_CONTAINER_VIEW
  return DEFAULT_HOST_VIEW
}

function isInfrastructureScope(scope: MonitoringMapScope): scope is InfrastructureResourceKind {
  return scope === 'hosts' || scope === 'containers'
}

function shouldFetchHosts(isAuthenticated: boolean, isServicesScope: boolean): boolean {
  return isAuthenticated && isServicesScope === false
}

function shouldFetchContainers(
  isAuthenticated: boolean,
  isServicesScope: boolean,
  resourceKind: InfrastructureResourceKind,
): boolean {
  return isAuthenticated && isServicesScope === false && resourceKind === 'containers'
}

function shouldFetchSavedViews(isAuthenticated: boolean, isServicesScope: boolean): boolean {
  return isAuthenticated && isServicesScope === false
}

function shouldFetchServiceData(isAuthenticated: boolean, isServicesScope: boolean): boolean {
  return isAuthenticated && isServicesScope
}

type InfrastructureLoadingOptions = Readonly<{
  isServicesScope: boolean
  resourceKind: InfrastructureResourceKind
  isHostsLoading: boolean
  isContainersLoading: boolean
}>

function isInfrastructureLoading({
  isServicesScope,
  resourceKind,
  isHostsLoading,
  isContainersLoading,
}: InfrastructureLoadingOptions): boolean {
  if (isServicesScope) return false
  if (resourceKind === 'containers') return isHostsLoading || isContainersLoading
  return isHostsLoading
}

type InfrastructureErrorOptions = Readonly<{
  isServicesScope: boolean
  resourceKind: InfrastructureResourceKind
  isHostsError: boolean
  isContainersError: boolean
}>

function isInfrastructureError({
  isServicesScope,
  resourceKind,
  isHostsError,
  isContainersError,
}: InfrastructureErrorOptions): boolean {
  if (isServicesScope) return false
  if (resourceKind === 'containers') return isHostsError || isContainersError
  return isHostsError
}

function getContainerLimitNotice(resourceKind: InfrastructureResourceKind, totalCount: number): string | null {
  if (resourceKind === 'containers' && totalCount > CONTAINER_LIMIT) {
    return `Showing first ${CONTAINER_LIMIT} of ${totalCount}`
  }
  return null
}

function hasFilteredOutAllResources(totalResources: number, visibleResources: number): boolean {
  return totalResources > 0 && visibleResources === 0
}

function getMapTitle(isServicesScope: boolean): string {
  if (isServicesScope) return 'Service Map'
  return 'Infrastructure'
}

function getMapMode(isServicesScope: boolean): MonitoringMapMode {
  if (isServicesScope) return 'services'
  return 'infrastructure'
}

function getScopeForMode(mode: MonitoringMapMode, infraKind: InfrastructureResourceKind): MonitoringMapScope {
  if (mode === 'services') return 'services'
  return infraKind
}

function getSearchValue(isServicesScope: boolean, serviceSearchQuery: string, infrastructureSearchQuery: string): string {
  if (isServicesScope) return serviceSearchQuery
  return infrastructureSearchQuery
}

function getSearchPlaceholder(isServicesScope: boolean): string {
  if (isServicesScope) return 'Search services and dependencies...'
  return 'Search hosts, images, tags...'
}

function getInfrastructureActions(isServicesScope: boolean, actions: ReactNode): ReactNode | undefined {
  if (isServicesScope) return undefined
  return actions
}

function isHostmapEmpty(hasNoResources: boolean, hasNoVisibleResources: boolean): boolean {
  return hasNoResources || hasNoVisibleResources
}

function getInfraEmptyDescription(hasNoVisibleResources: boolean): string {
  if (hasNoVisibleResources) return 'Adjust search or facets.'
  return 'Connect infrastructure telemetry to populate this map.'
}

function getInfrastructureFallback(isError: boolean): ReactNode | undefined {
  if (isError) return <MapDataUnavailable />
  return undefined
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

function getInfraEmptyIcon(
  hasNoVisibleResources: boolean,
  resourceKind: InfrastructureResourceKind,
): ComponentType<{className?: string}> {
  if (hasNoVisibleResources) return Search
  if (resourceKind === 'hosts') return HardDrive
  return Box
}

function getInfraEmptyTitle(hasNoVisibleResources: boolean, resourceKind: InfrastructureResourceKind): string {
  if (hasNoVisibleResources) return 'No resources match your filters'
  if (resourceKind === 'hosts') return 'No hosts yet'
  return 'No containers yet'
}

function toScopeOptions(facetItems: ApmFacetItem[], allLabel: string, allValue: string): Array<Option<string>> {
  return [
    {value: allValue, label: allLabel},
    ...facetItems.map((item) => ({value: item.value, label: item.value})),
  ]
}

function facetItemToValue(item: ApmFacetItem): FacetValue {
  return {value: item.value, count: item.count}
}

function upsertSavedView(
  savedViews: SavedInfrastructureMapView[],
  savedView: SavedInfrastructureMapView,
): SavedInfrastructureMapView[] {
  const nextViews = savedViews.filter((view) => view.id !== savedView.id)
  return [savedView, ...nextViews]
}
