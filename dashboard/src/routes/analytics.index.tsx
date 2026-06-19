import {useMemo, useState} from 'react'
import {createFileRoute} from '@tanstack/react-router'
import {useQuery} from '@tanstack/react-query'
import {api} from '@/lib/api'
import {useAnalyticsParams} from '@/contexts/UseAnalyticsParams'
import {serviceNamesForQuery} from '@/lib/service-facet-scope'
import {AnalyticsFilterBar} from '@/components/analytics/AnalyticsFilterBar'
import {AnalyticsKpiCards, AnalyticsKpiCardsSkeleton} from '@/components/analytics/AnalyticsKpiCards'
import {AnalyticsChart} from '@/components/analytics/AnalyticsChart'
import {AnalyticsBreakdownTable} from '@/components/analytics/AnalyticsBreakdownTable'
import {ProductAnalyticsView} from '@/components/analytics/product/ProductAnalyticsView'
import {ExplorerShell} from '@/components/filters/ExplorerShell'
import {FacetRail} from '@/components/filters/FacetRail'
import {SegmentedTabs, type SegmentedOption} from '@/components/filters/SegmentedTabs'
import {Tabs, TabsContent, TabsList, TabsTrigger} from '@/components/ui/tabs'
import {Card, CardContent} from '@/components/ui/card'
import {CopyBlock} from '@/components/ui/copy-block'
import {Button} from '@/components/ui/button'
import {EmptyState} from '@/components/ui/empty-state'
import {
  ArrowRight, BarChart3, BookOpen, FileText, Globe, Laptop, LogIn, LogOut, MapPin, Megaphone,
  MousePointerClick, Share2, Target,
} from 'lucide-react'
import type {
  AnalyticsBreakdownItem,
  AnalyticsFilter,
  AnalyticsParams,
  AnalyticsScopeId,
  Project,
} from '@/lib/api'
import type {FacetFilter, FacetRailSection} from '@/lib/filters/types'

export const Route = createFileRoute('/analytics/')({
  component: AnalyticsOverview,
})

type AnalyticsView = 'web' | 'product'
const ANALYTICS_VIEW_TABS = [
  {value: 'web', label: 'Web', icon: Globe},
  {value: 'product', label: 'Product', icon: Target},
] as const satisfies ReadonlyArray<SegmentedOption<AnalyticsView>>

const PRODUCT_PRESENCE_PARAMS: AnalyticsParams = {period: '12mo'}
const ORGANIZATION_ANALYTICS_SCOPE: AnalyticsScopeId = null

function facetValues(filters: readonly FacetFilter[], key: string, exclude: boolean): string[] {
  return filters
    .filter((filter) => filter.key === key && Boolean(filter.exclude) === exclude)
    .map((filter) => filter.value)
}

function analyticsScopeKey(serviceNames: readonly string[], hasServiceFilters: boolean): string {
  if (!hasServiceFilters) return 'all-services'
  if (serviceNames.length === 0) return 'no-services'
  return serviceNames.join('|')
}

function analyticsServiceParams(serviceNames: readonly string[]): Pick<AnalyticsParams, 'services'> {
  return serviceNames.length > 0 ? {services: [...serviceNames]} : {}
}

function analyticsFiltersWithRow(
  current: AnalyticsFilter[],
  property: string,
  item: AnalyticsBreakdownItem
): AnalyticsFilter[] {
  const hasFilter = current.some((filter) => filter.property === property && filter.value === item.name)
  if (hasFilter) return current
  return [...current, {property, operator: 'is', value: item.name}]
}

function findSetupService(services: readonly Project[], serviceNames: readonly string[]): Project | undefined {
  if (serviceNames.length === 0) return services[0]
  return services.find((service) => service.name === serviceNames[0]) ?? services[0]
}

type UseAnalyticsQueriesArgs = Readonly<{
  hasAnalyticsScope: boolean
  scopeKey: string
  params: AnalyticsParams
  productPresenceParams: AnalyticsParams
  breakdownTab: string
}>

function useAnalyticsQueries({
  hasAnalyticsScope,
  scopeKey,
  params,
  productPresenceParams,
  breakdownTab,
}: UseAnalyticsQueriesArgs) {
  const {data: overview, isLoading: overviewLoading} = useQuery({
    queryKey: ['analytics-overview', 'organization', scopeKey, params],
    queryFn: () => api.getAnalyticsOverview(ORGANIZATION_ANALYTICS_SCOPE, params),
    enabled: hasAnalyticsScope,
  })

  const {data: timeseries, isLoading: timeseriesLoading} = useQuery({
    queryKey: ['analytics-timeseries', 'organization', scopeKey, params],
    queryFn: () => api.getAnalyticsTimeseries(ORGANIZATION_ANALYTICS_SCOPE, params),
    enabled: hasAnalyticsScope,
  })

  const {data: pages, isLoading: pagesLoading} = useQuery({
    queryKey: ['analytics-pages', 'organization', scopeKey, params],
    queryFn: () => api.getAnalyticsPages(ORGANIZATION_ANALYTICS_SCOPE, params),
    enabled: hasAnalyticsScope && breakdownTab === 'pages',
  })

  const {data: entryPages, isLoading: entryPagesLoading} = useQuery({
    queryKey: ['analytics-entry-pages', 'organization', scopeKey, params],
    queryFn: () => api.getAnalyticsEntryPages(ORGANIZATION_ANALYTICS_SCOPE, params),
    enabled: hasAnalyticsScope && breakdownTab === 'entry-pages',
  })

  const {data: exitPages, isLoading: exitPagesLoading} = useQuery({
    queryKey: ['analytics-exit-pages', 'organization', scopeKey, params],
    queryFn: () => api.getAnalyticsExitPages(ORGANIZATION_ANALYTICS_SCOPE, params),
    enabled: hasAnalyticsScope && breakdownTab === 'exit-pages',
  })

  const {data: sources, isLoading: sourcesLoading} = useQuery({
    queryKey: ['analytics-sources', 'organization', scopeKey, params],
    queryFn: () => api.getAnalyticsSources(ORGANIZATION_ANALYTICS_SCOPE, params),
    enabled: hasAnalyticsScope && breakdownTab === 'sources',
  })

  const {data: locations, isLoading: locationsLoading} = useQuery({
    queryKey: ['analytics-locations', 'organization', scopeKey, params],
    queryFn: () => api.getAnalyticsLocations(ORGANIZATION_ANALYTICS_SCOPE, params),
    enabled: hasAnalyticsScope && breakdownTab === 'locations',
  })

  const {data: browsers, isLoading: browsersLoading} = useQuery({
    queryKey: ['analytics-devices-browser', 'organization', scopeKey, params],
    queryFn: () => api.getAnalyticsDevices(ORGANIZATION_ANALYTICS_SCOPE, 'browser', params),
    enabled: hasAnalyticsScope && breakdownTab === 'devices',
  })

  const {data: operatingSystems, isLoading: osLoading} = useQuery({
    queryKey: ['analytics-devices-os', 'organization', scopeKey, params],
    queryFn: () => api.getAnalyticsDevices(ORGANIZATION_ANALYTICS_SCOPE, 'os', params),
    enabled: hasAnalyticsScope && breakdownTab === 'devices',
  })

  const {data: deviceTypes, isLoading: deviceTypesLoading} = useQuery({
    queryKey: ['analytics-devices-device', 'organization', scopeKey, params],
    queryFn: () => api.getAnalyticsDevices(ORGANIZATION_ANALYTICS_SCOPE, 'device', params),
    enabled: hasAnalyticsScope && breakdownTab === 'devices',
  })

  const {data: utmSources, isLoading: utmSourcesLoading} = useQuery({
    queryKey: ['analytics-utm-source', 'organization', scopeKey, params],
    queryFn: () => api.getAnalyticsUtm(ORGANIZATION_ANALYTICS_SCOPE, 'source', params),
    enabled: hasAnalyticsScope && breakdownTab === 'utm',
  })

  const {data: customEvents, isLoading: customEventsLoading} = useQuery({
    queryKey: ['analytics-events', 'organization', scopeKey, params],
    queryFn: () => api.getAnalyticsEvents(ORGANIZATION_ANALYTICS_SCOPE, params),
    enabled: hasAnalyticsScope && breakdownTab === 'events',
  })

  const {data: productEventsPreview, isLoading: productPresenceLoading} = useQuery({
    queryKey: ['analytics-product-events-preview', 'organization', scopeKey, productPresenceParams],
    queryFn: () => api.getAnalyticsEvents(ORGANIZATION_ANALYTICS_SCOPE, productPresenceParams, {
      source: 'server',
      groupBy: 'user_id',
      limit: 1,
    }),
    enabled: hasAnalyticsScope,
  })

  return {
    overview,
    overviewLoading,
    timeseries,
    timeseriesLoading,
    pages,
    pagesLoading,
    entryPages,
    entryPagesLoading,
    exitPages,
    exitPagesLoading,
    sources,
    sourcesLoading,
    locations,
    locationsLoading,
    browsers,
    browsersLoading,
    operatingSystems,
    osLoading,
    deviceTypes,
    deviceTypesLoading,
    utmSources,
    utmSourcesLoading,
    customEvents,
    customEventsLoading,
    productEventsPreview,
    productPresenceLoading,
  }
}

function AnalyticsOverview() {
  const {period, customFrom, customTo} = useAnalyticsParams()
  const [filters, setFilters] = useState<AnalyticsFilter[]>([])
  const [facetFilters, setFacetFilters] = useState<FacetFilter[]>([])
  const [breakdownTab, setBreakdownTab] = useState('pages')
  const [analyticsTab, setAnalyticsTab] = useState<AnalyticsView>('web')

  const {data: services, isLoading: servicesLoading, error: servicesError} = useQuery({
    queryKey: ['projects'],
    queryFn: () => api.getProjects(),
  })

  const serviceList = useMemo(() => services ?? [], [services])
  const includedServices = useMemo(
    () => facetValues(facetFilters, 'service', false),
    [facetFilters]
  )
  const excludedServices = useMemo(
    () => facetValues(facetFilters, 'service', true),
    [facetFilters]
  )
  const hasServiceFilters = includedServices.length > 0 || excludedServices.length > 0
  const serviceNames = useMemo(
    () => serviceNamesForQuery(serviceList, includedServices, excludedServices),
    [serviceList, includedServices, excludedServices]
  )
  const scopeKey = analyticsScopeKey(serviceNames, hasServiceFilters)
  const hasAnalyticsScope = serviceList.length > 0 && (!hasServiceFilters || serviceNames.length > 0)
  const serviceParams = useMemo(() => analyticsServiceParams(serviceNames), [serviceNames])
  const setupService = findSetupService(serviceList, serviceNames)

  const dateParams = useMemo((): AnalyticsParams => ({
    period,
    ...(period === 'custom' && customFrom && customTo ? {from: customFrom, to: customTo} : {}),
  }), [period, customFrom, customTo])

  const params = useMemo((): AnalyticsParams => ({
    ...dateParams,
    ...serviceParams,
    filters: filters.length > 0 ? filters : undefined,
    comparison: 'previous_period',
  }), [dateParams, filters, serviceParams])

  const productParams = useMemo((): AnalyticsParams => ({
    ...dateParams,
    ...serviceParams,
  }), [dateParams, serviceParams])

  const productPresenceParams = useMemo((): AnalyticsParams => ({
    ...PRODUCT_PRESENCE_PARAMS,
    ...serviceParams,
  }), [serviceParams])

  const analyticsRailSections: FacetRailSection[] = useMemo(
    () => [
      {
        key: 'service',
        label: 'Service',
        color: 'bg-primary',
        options: serviceList.map((service) => ({value: service.name})),
      },
    ],
    [serviceList]
  )

  const analyticsData = useAnalyticsQueries({
    hasAnalyticsScope,
    scopeKey,
    params,
    productPresenceParams,
    breakdownTab,
  })

  const hasProductEvents = (analyticsData.productEventsPreview?.length ?? 0) > 0
  const hasWebAnalytics =
    (analyticsData.overview?.uniqueVisitors ?? 0) > 0 || (analyticsData.overview?.totalPageviews ?? 0) > 0
  const activeAnalyticsTab = analyticsTab

  const addFilterFromRow = (property: string) => (item: AnalyticsBreakdownItem) => {
    setFilters((current) => analyticsFiltersWithRow(current, property, item))
  }

  if (servicesLoading) {
    return <div className="p-8 text-sm text-muted-foreground">Loading analytics...</div>
  }

  if (servicesError) {
    return (
      <div className="p-8 text-destructive">
        Failed to load services: {servicesError instanceof Error ? servicesError.message : 'Unknown error'}
      </div>
    )
  }

  if (serviceList.length === 0) {
    return (
      <div className="py-10">
        <EmptyState
          icon={BarChart3}
          title="No services yet"
          description="Create a service to start collecting web and product analytics."
        />
      </div>
    )
  }

  return (
    <ExplorerShell
      title="Analytics"
      icon={<BarChart3 className="h-4 w-4 text-muted-foreground" />}
      tabs={
        <SegmentedTabs
          ariaLabel="Analytics view"
          value={activeAnalyticsTab}
          onChange={setAnalyticsTab}
          options={ANALYTICS_VIEW_TABS}
        />
      }
      searchBar={<div />}
      rail={
        <FacetRail
          sections={analyticsRailSections}
          facetFilters={facetFilters}
          onFacetFiltersChange={setFacetFilters}
          title="Analytics"
        />
      }
    >
      <div className="space-y-2 p-3">
        <AnalyticsContent
          hasAnalyticsScope={hasAnalyticsScope}
          activeAnalyticsTab={activeAnalyticsTab}
          hasWebAnalytics={hasWebAnalytics}
          hasProductEvents={hasProductEvents}
          setupService={setupService}
          filters={filters}
          setFilters={setFilters}
          breakdownTab={breakdownTab}
          setBreakdownTab={setBreakdownTab}
          addFilterFromRow={addFilterFromRow}
          productParams={productParams}
          {...analyticsData}
        />
      </div>
    </ExplorerShell>
  )
}

type AddAnalyticsFilter = (property: string) => (item: AnalyticsBreakdownItem) => void

type AnalyticsContentProps = Readonly<ReturnType<typeof useAnalyticsQueries> & {
  hasAnalyticsScope: boolean
  activeAnalyticsTab: AnalyticsView
  hasWebAnalytics: boolean
  hasProductEvents: boolean
  setupService: Project | undefined
  filters: AnalyticsFilter[]
  setFilters: (filters: AnalyticsFilter[]) => void
  breakdownTab: string
  setBreakdownTab: (tab: string) => void
  addFilterFromRow: AddAnalyticsFilter
  productParams: AnalyticsParams
}>

function AnalyticsContent({
  hasAnalyticsScope,
  activeAnalyticsTab,
  hasWebAnalytics,
  hasProductEvents,
  setupService,
  filters,
  setFilters,
  breakdownTab,
  setBreakdownTab,
  addFilterFromRow,
  productParams,
  ...data
}: AnalyticsContentProps) {
  if (!hasAnalyticsScope) {
    return (
      <div className="py-10">
        <EmptyState
          icon={BarChart3}
          title="No services match filters"
          description="Adjust the selected services to view analytics."
        />
      </div>
    )
  }

  if (activeAnalyticsTab === 'web') {
    return (
      <WebAnalyticsContent
        hasWebAnalytics={hasWebAnalytics}
        setupService={setupService}
        filters={filters}
        setFilters={setFilters}
        breakdownTab={breakdownTab}
        setBreakdownTab={setBreakdownTab}
        addFilterFromRow={addFilterFromRow}
        {...data}
      />
    )
  }

  return (
    <ProductAnalyticsContent
      productPresenceLoading={data.productPresenceLoading}
      hasProductEvents={hasProductEvents}
      setupService={setupService}
      productParams={productParams}
    />
  )
}

type WebAnalyticsContentProps = Readonly<ReturnType<typeof useAnalyticsQueries> & {
  hasWebAnalytics: boolean
  setupService: Project | undefined
  filters: AnalyticsFilter[]
  setFilters: (filters: AnalyticsFilter[]) => void
  breakdownTab: string
  setBreakdownTab: (tab: string) => void
  addFilterFromRow: AddAnalyticsFilter
}>

function WebAnalyticsContent({
  hasWebAnalytics,
  setupService,
  filters,
  setFilters,
  breakdownTab,
  setBreakdownTab,
  addFilterFromRow,
  overview,
  overviewLoading,
  timeseries,
  timeseriesLoading,
  pages,
  pagesLoading,
  entryPages,
  entryPagesLoading,
  exitPages,
  exitPagesLoading,
  sources,
  sourcesLoading,
  locations,
  locationsLoading,
  browsers,
  browsersLoading,
  operatingSystems,
  osLoading,
  deviceTypes,
  deviceTypesLoading,
  utmSources,
  utmSourcesLoading,
  customEvents,
  customEventsLoading,
}: WebAnalyticsContentProps) {
  if (!overviewLoading && !hasWebAnalytics) {
    return <WebAnalyticsEmptyState service={setupService} />
  }

  return (
    <div className="space-y-2">
      <AnalyticsFilterBar filters={filters} onFiltersChange={setFilters} />

      {overviewLoading ? (
        <AnalyticsKpiCardsSkeleton />
      ) : (
        <AnalyticsKpiCards data={overview} isLoading={overviewLoading} />
      )}

      <AnalyticsChart data={timeseries} isLoading={timeseriesLoading} />

      <Tabs value={breakdownTab} onValueChange={setBreakdownTab}>
        <TabsList className="h-7">
          <TabsTrigger value="pages" className="gap-1.5 text-xs">
            <FileText className="h-3.5 w-3.5" /> Pages
          </TabsTrigger>
          <TabsTrigger value="entry-pages" className="gap-1.5 text-xs">
            <LogIn className="h-3.5 w-3.5" /> Entry Pages
          </TabsTrigger>
          <TabsTrigger value="exit-pages" className="gap-1.5 text-xs">
            <LogOut className="h-3.5 w-3.5" /> Exit Pages
          </TabsTrigger>
          <TabsTrigger value="sources" className="gap-1.5 text-xs">
            <Share2 className="h-3.5 w-3.5" /> Sources
          </TabsTrigger>
          <TabsTrigger value="locations" className="gap-1.5 text-xs">
            <MapPin className="h-3.5 w-3.5" /> Locations
          </TabsTrigger>
          <TabsTrigger value="devices" className="gap-1.5 text-xs">
            <Laptop className="h-3.5 w-3.5" /> Devices
          </TabsTrigger>
          <TabsTrigger value="utm" className="gap-1.5 text-xs">
            <Megaphone className="h-3.5 w-3.5" /> UTM
          </TabsTrigger>
          <TabsTrigger value="events" className="gap-1.5 text-xs">
            <MousePointerClick className="h-3.5 w-3.5" /> Events
          </TabsTrigger>
        </TabsList>

        <TabsContent value="pages" className="mt-2">
          <AnalyticsBreakdownTable
            title="Top Pages"
            icon={FileText}
            iconColor="text-chart-1"
            data={pages}
            isLoading={pagesLoading}
            showBounceRate
            showDuration
            onRowClick={addFilterFromRow('pathname')}
          />
        </TabsContent>

        <TabsContent value="entry-pages" className="mt-2">
          <AnalyticsBreakdownTable
            title="Entry Pages"
            icon={LogIn}
            iconColor="text-chart-2"
            data={entryPages}
            isLoading={entryPagesLoading}
            showBounceRate
            onRowClick={addFilterFromRow('entry_page')}
          />
        </TabsContent>

        <TabsContent value="exit-pages" className="mt-2">
          <AnalyticsBreakdownTable
            title="Exit Pages"
            icon={LogOut}
            iconColor="text-chart-3"
            data={exitPages}
            isLoading={exitPagesLoading}
            onRowClick={addFilterFromRow('exit_page')}
          />
        </TabsContent>

        <TabsContent value="sources" className="mt-2">
          <AnalyticsBreakdownTable
            title="Top Sources"
            icon={Share2}
            iconColor="text-chart-4"
            data={sources}
            isLoading={sourcesLoading}
            onRowClick={addFilterFromRow('referrer_source')}
          />
        </TabsContent>

        <TabsContent value="locations" className="mt-2">
          <AnalyticsBreakdownTable
            title="Countries"
            icon={MapPin}
            iconColor="text-chart-5"
            data={locations}
            isLoading={locationsLoading}
            onRowClick={addFilterFromRow('country_code')}
          />
        </TabsContent>

        <TabsContent value="devices" className="mt-2">
          <div className="grid gap-2 lg:grid-cols-3">
            <AnalyticsBreakdownTable
              title="Browsers"
              icon={Globe}
              iconColor="text-chart-1"
              data={browsers}
              isLoading={browsersLoading}
              onRowClick={addFilterFromRow('browser')}
            />
            <AnalyticsBreakdownTable
              title="Operating Systems"
              icon={Laptop}
              iconColor="text-chart-2"
              data={operatingSystems}
              isLoading={osLoading}
              onRowClick={addFilterFromRow('os')}
            />
            <AnalyticsBreakdownTable
              title="Device Types"
              icon={Laptop}
              iconColor="text-chart-4"
              data={deviceTypes}
              isLoading={deviceTypesLoading}
              onRowClick={addFilterFromRow('device_type')}
            />
          </div>
        </TabsContent>

        <TabsContent value="utm" className="mt-2">
          <AnalyticsBreakdownTable
            title="UTM Sources"
            icon={Megaphone}
            iconColor="text-chart-6"
            data={utmSources}
            isLoading={utmSourcesLoading}
          />
        </TabsContent>

        <TabsContent value="events" className="mt-2">
          <AnalyticsBreakdownTable
            title="Custom Events"
            icon={MousePointerClick}
            iconColor="text-chart-7"
            data={customEvents}
            isLoading={customEventsLoading}
          />
        </TabsContent>
      </Tabs>
    </div>
  )
}

type ProductAnalyticsContentProps = Readonly<{
  productPresenceLoading: boolean
  hasProductEvents: boolean
  setupService: Project | undefined
  productParams: AnalyticsParams
}>

function ProductAnalyticsContent({
  productPresenceLoading,
  hasProductEvents,
  setupService,
  productParams,
}: ProductAnalyticsContentProps) {
  if (productPresenceLoading) {
    return (
      <div className="space-y-2">
        <div className="h-[220px] w-full animate-pulse rounded bg-muted" />
      </div>
    )
  }

  if (hasProductEvents) {
    return <ProductAnalyticsView scopeId={ORGANIZATION_ANALYTICS_SCOPE} params={productParams} />
  }

  return (
    <div className="space-y-2">
      <ProductAnalyticsEmptyState
        service={setupService}
        serviceId={setupService?.id ?? 'your-service-id'}
      />
    </div>
  )
}

function WebAnalyticsEmptyState({service}: Readonly<{service?: Project}>) {
  const scriptHost = getServiceApiHost(service)
  const publicKey = getServicePublicKey(service)
  const scriptCode = `<script
  defer
  data-domain="yoursite.com"
  data-key="${publicKey}"
  src="${scriptHost}/js/m.js"
></script>`

  return (
    <div className="flex flex-col items-center justify-center py-6">
      <div className="max-w-2xl w-full space-y-4">
        <div className="text-center">
          <div className="mx-auto flex items-center justify-center h-12 w-12 rounded-full bg-[hsl(var(--primary)/0.12)] mb-3">
            <BarChart3 className="h-6 w-6 text-primary" />
          </div>
          <h2 className="text-lg font-semibold mb-1.5">Get Started with Analytics</h2>
          <p className="text-muted-foreground text-sm max-w-md mx-auto">
            Add privacy-focused, cookie-free web analytics to your site. No cookies, no personal data stored.
          </p>
        </div>

        <Card>
          <CardContent className="p-4 space-y-3">
            <div>
              <h3 className="text-xs font-medium mb-1.5">1. Add the tracking script to your site</h3>
              <CopyBlock code={scriptCode} language="html" />
            </div>

            <div>
              <h3 className="text-xs font-medium mb-1.5">2. Or use the NPM package for SPAs</h3>
              <CopyBlock code="npm install @moneat/analytics" language="bash" />
            </div>
          </CardContent>
        </Card>

        <div className="text-center">
          <a
            href="/docs/product-analytics"
            className="inline-flex items-center gap-1.5 bg-primary text-primary-foreground px-3 py-1.5 rounded text-xs font-medium hover:bg-primary/90 transition-colors"
          >
            <BookOpen className="h-3 w-3" />
            View Full Documentation
            <ArrowRight className="h-3 w-3" />
          </a>
        </div>
      </div>
    </div>
  )
}

function getServicePublicKey(service?: Project): string {
  if (!service?.dsn) return 'your-service-public-key'

  try {
    const url = new URL(service.dsn)
    return url.username || 'your-service-public-key'
  } catch {
    return 'your-service-public-key'
  }
}

function ProductAnalyticsEmptyState({service, serviceId}: Readonly<{service?: Project; serviceId: string}>) {
  const apiHost = getServiceApiHost(service)
  const endpoint = `${apiHost}/v1/analytics/${serviceId}/events`
  const nodeCode = `await fetch('${endpoint}', {
  method: 'POST',
  headers: {
    'Authorization': 'Bearer <YOUR_OTLP_API_KEY>',
    'Content-Type': 'application/json',
  },
  body: JSON.stringify({
    events: [{
      name: 'signup.completed',
      user_id: 'user_123',
      props: { plan: 'pro' },
    }],
  }),
})`
  const curlCode = `curl -X POST '${endpoint}' \\
  -H 'Authorization: Bearer <YOUR_OTLP_API_KEY>' \\
  -H 'Content-Type: application/json' \\
  -d '{
    "events": [{
      "name": "signup.completed",
      "user_id": "user_123",
      "props": { "plan": "pro" }
    }]
  }'`

  return (
    <div className="flex flex-col items-center justify-center py-6">
      <div className="max-w-3xl w-full space-y-4">
        <div className="text-center">
          <div className="mx-auto flex items-center justify-center h-12 w-12 rounded-full bg-[hsl(var(--primary)/0.12)] mb-3">
            <Target className="h-6 w-6 text-primary" />
          </div>
          <h2 className="text-lg font-semibold mb-1.5">Get Started with Product Analytics</h2>
          <p className="text-muted-foreground text-sm max-w-md mx-auto">
            Track server-side, user-level events to understand activation, retention, funnels, and product usage.
          </p>
        </div>

        <Card>
          <CardContent className="p-4 space-y-4">
            <div>
              <h3 className="text-xs font-medium mb-1.5">1. Send events from your server</h3>
              <CopyBlock code={nodeCode} language="typescript" />
            </div>

            <div>
              <h3 className="text-xs font-medium mb-1.5">2. Or use cURL for quick testing</h3>
              <CopyBlock code={curlCode} language="bash" />
            </div>
          </CardContent>
        </Card>

        <p className="text-xs text-muted-foreground text-center">
          Server-side product events require an OTLP API key. Create one in{' '}
          <a href="/settings?tab=api-keys" className="text-primary hover:underline">
            Settings &gt; API Keys
          </a>
          .
        </p>

        <div className="text-center">
          <Button asChild size="sm">
            <a href="/docs/product-analytics">
              <BookOpen className="h-3 w-3" />
              View Full Documentation
              <ArrowRight className="h-3 w-3" />
            </a>
          </Button>
        </div>
      </div>
    </div>
  )
}

function getServiceApiHost(service?: Project): string {
  if (!service?.dsn) return 'https://your-moneat-instance.com'

  try {
    const url = new URL(service.dsn)
    return `${url.protocol}//${url.host}`
  } catch {
    return 'https://your-moneat-instance.com'
  }
}
