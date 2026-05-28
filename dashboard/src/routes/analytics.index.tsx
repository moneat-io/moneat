import {useState, useCallback} from 'react'
import {createFileRoute} from '@tanstack/react-router'
import {useQuery} from '@tanstack/react-query'
import {api} from '@/lib/api'
import {useProject} from '@/contexts/ProjectContext'
import {useAnalyticsParams} from '@/contexts/UseAnalyticsParams'
import {AnalyticsFilterBar} from '@/components/analytics/AnalyticsFilterBar'
import {AnalyticsKpiCards, AnalyticsKpiCardsSkeleton} from '@/components/analytics/AnalyticsKpiCards'
import {AnalyticsChart} from '@/components/analytics/AnalyticsChart'
import {AnalyticsBreakdownTable} from '@/components/analytics/AnalyticsBreakdownTable'
import {AnalyticsRetentionTable} from '@/components/analytics/AnalyticsRetentionTable'
import {Tabs, TabsContent, TabsList, TabsTrigger} from '@/components/ui/tabs'
import {Card, CardContent} from '@/components/ui/card'
import {CopyBlock} from '@/components/ui/copy-block'
import {Button} from '@/components/ui/button'
import {Input} from '@/components/ui/input'
import {
  ArrowRight, BarChart3, BookOpen, FileText, Globe, Laptop, LogIn, LogOut, MapPin, Megaphone,
  MousePointerClick, Plus, Share2, Target, Trash2,
} from 'lucide-react'
import type {
  AnalyticsBreakdownItem,
  AnalyticsFilter,
  AnalyticsFunnelResponse,
  AnalyticsParams,
  Project,
} from '@/lib/api'

export const Route = createFileRoute('/analytics/')({
  component: AnalyticsOverview,
})

const PRODUCT_PRESENCE_PARAMS: AnalyticsParams = {period: '12mo'}
const DEFAULT_PRODUCT_FUNNEL_STEPS = ['signup.completed', 'recording.started', 'export.completed']
const PRODUCT_RETENTION_PERIODS = [1, 7, 14, 30]
const PRODUCT_RETENTION_START_EVENT = 'signup.completed'
const PRODUCT_RETENTION_RETURN_EVENT = 'recording.started'

function AnalyticsOverview() {
  const {selectedProjectId} = useProject()
  const {period, customFrom, customTo} = useAnalyticsParams()
  const [filters, setFilters] = useState<AnalyticsFilter[]>([])
  const [breakdownTab, setBreakdownTab] = useState('pages')
  const [analyticsTab, setAnalyticsTab] = useState('web')

  const {data: projects} = useQuery({
    queryKey: ['projects'],
    queryFn: () => api.getProjects(),
  })

  const hasSelectedProject = selectedProjectId != null && projects?.some(p => p.resourceId === selectedProjectId)
  const projectId = (hasSelectedProject ? selectedProjectId : null) || projects?.[0]?.resourceId
  const project = projects?.find(p => p.resourceId === projectId)

  const buildDateParams = useCallback((): AnalyticsParams => ({
    period,
    ...(period === 'custom' && customFrom && customTo ? {from: customFrom, to: customTo} : {}),
  }), [period, customFrom, customTo])

  const buildParams = useCallback((): AnalyticsParams => ({
    ...buildDateParams(),
    filters: filters.length > 0 ? filters : undefined,
    comparison: 'previous_period',
  }), [buildDateParams, filters])

  const params = buildParams()
  const productParams = buildDateParams()

  const {data: overview, isLoading: overviewLoading} = useQuery({
    queryKey: ['analytics-overview', projectId, params],
    queryFn: () => api.getAnalyticsOverview(projectId!, params),
    enabled: !!projectId,
  })

  const {data: timeseries, isLoading: timeseriesLoading} = useQuery({
    queryKey: ['analytics-timeseries', projectId, params],
    queryFn: () => api.getAnalyticsTimeseries(projectId!, params),
    enabled: !!projectId,
  })

  const {data: pages, isLoading: pagesLoading} = useQuery({
    queryKey: ['analytics-pages', projectId, params],
    queryFn: () => api.getAnalyticsPages(projectId!, params),
    enabled: !!projectId && breakdownTab === 'pages',
  })

  const {data: entryPages, isLoading: entryPagesLoading} = useQuery({
    queryKey: ['analytics-entry-pages', projectId, params],
    queryFn: () => api.getAnalyticsEntryPages(projectId!, params),
    enabled: !!projectId && breakdownTab === 'entry-pages',
  })

  const {data: exitPages, isLoading: exitPagesLoading} = useQuery({
    queryKey: ['analytics-exit-pages', projectId, params],
    queryFn: () => api.getAnalyticsExitPages(projectId!, params),
    enabled: !!projectId && breakdownTab === 'exit-pages',
  })

  const {data: sources, isLoading: sourcesLoading} = useQuery({
    queryKey: ['analytics-sources', projectId, params],
    queryFn: () => api.getAnalyticsSources(projectId!, params),
    enabled: !!projectId && breakdownTab === 'sources',
  })

  const {data: locations, isLoading: locationsLoading} = useQuery({
    queryKey: ['analytics-locations', projectId, params],
    queryFn: () => api.getAnalyticsLocations(projectId!, params),
    enabled: !!projectId && breakdownTab === 'locations',
  })

  const {data: browsers, isLoading: browsersLoading} = useQuery({
    queryKey: ['analytics-devices-browser', projectId, params],
    queryFn: () => api.getAnalyticsDevices(projectId!, 'browser', params),
    enabled: !!projectId && breakdownTab === 'devices',
  })

  const {data: operatingSystems, isLoading: osLoading} = useQuery({
    queryKey: ['analytics-devices-os', projectId, params],
    queryFn: () => api.getAnalyticsDevices(projectId!, 'os', params),
    enabled: !!projectId && breakdownTab === 'devices',
  })

  const {data: deviceTypes, isLoading: deviceTypesLoading} = useQuery({
    queryKey: ['analytics-devices-device', projectId, params],
    queryFn: () => api.getAnalyticsDevices(projectId!, 'device', params),
    enabled: !!projectId && breakdownTab === 'devices',
  })

  const {data: utmSources, isLoading: utmSourcesLoading} = useQuery({
    queryKey: ['analytics-utm-source', projectId, params],
    queryFn: () => api.getAnalyticsUtm(projectId!, 'source', params),
    enabled: !!projectId && breakdownTab === 'utm',
  })

  const {data: customEvents, isLoading: customEventsLoading} = useQuery({
    queryKey: ['analytics-events', projectId, params],
    queryFn: () => api.getAnalyticsEvents(projectId!, params),
    enabled: !!projectId && breakdownTab === 'events',
  })

  const {data: productEventsPreview, isLoading: productPresenceLoading} = useQuery({
    queryKey: ['analytics-product-events-preview', projectId],
    queryFn: () => api.getAnalyticsEvents(projectId!, PRODUCT_PRESENCE_PARAMS, {
      source: 'server',
      groupBy: 'user_id',
      limit: 1,
    }),
    enabled: !!projectId,
  })

  const hasProductEvents = (productEventsPreview?.length ?? 0) > 0
  const hasWebAnalytics = (overview?.uniqueVisitors ?? 0) > 0 || (overview?.totalPageviews ?? 0) > 0
  const activeAnalyticsTab = analyticsTab

  const addFilterFromRow = (property: string) => (item: AnalyticsBreakdownItem) => {
    if (filters.some(f => f.property === property && f.value === item.name)) return
    setFilters([...filters, {property, operator: 'is', value: item.name}])
  }

  if (!projectId) {
    return (
      <div className="text-center py-20">
        <p className="text-muted-foreground">Select a project to view analytics</p>
      </div>
    )
  }

  return (
    <div className="space-y-2">
      <Tabs value={activeAnalyticsTab} onValueChange={setAnalyticsTab}>
        <TabsList className="h-7">
          <TabsTrigger value="web" className="gap-1.5 text-xs">
            <Globe className="h-3.5 w-3.5" /> Web
          </TabsTrigger>
          <TabsTrigger value="product" className="gap-1.5 text-xs">
            <Target className="h-3.5 w-3.5" /> Product
          </TabsTrigger>
        </TabsList>

        <TabsContent value="web" className="mt-2 space-y-2">
          {!overviewLoading && !hasWebAnalytics ? (
            <WebAnalyticsEmptyState project={project} />
          ) : (
            <>
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
                    iconColor="text-blue-500"
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
                    iconColor="text-emerald-500"
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
                    iconColor="text-rose-500"
                    data={exitPages}
                    isLoading={exitPagesLoading}
                    onRowClick={addFilterFromRow('exit_page')}
                  />
                </TabsContent>

                <TabsContent value="sources" className="mt-2">
                  <AnalyticsBreakdownTable
                    title="Top Sources"
                    icon={Share2}
                    iconColor="text-violet-500"
                    data={sources}
                    isLoading={sourcesLoading}
                    onRowClick={addFilterFromRow('referrer_source')}
                  />
                </TabsContent>

                <TabsContent value="locations" className="mt-2">
                  <AnalyticsBreakdownTable
                    title="Countries"
                    icon={MapPin}
                    iconColor="text-amber-500"
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
                      iconColor="text-blue-500"
                      data={browsers}
                      isLoading={browsersLoading}
                      onRowClick={addFilterFromRow('browser')}
                    />
                    <AnalyticsBreakdownTable
                      title="Operating Systems"
                      icon={Laptop}
                      iconColor="text-emerald-500"
                      data={operatingSystems}
                      isLoading={osLoading}
                      onRowClick={addFilterFromRow('os')}
                    />
                    <AnalyticsBreakdownTable
                      title="Device Types"
                      icon={Laptop}
                      iconColor="text-violet-500"
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
                    iconColor="text-cyan-500"
                    data={utmSources}
                    isLoading={utmSourcesLoading}
                  />
                </TabsContent>

                <TabsContent value="events" className="mt-2">
                  <AnalyticsBreakdownTable
                    title="Custom Events"
                    icon={MousePointerClick}
                    iconColor="text-orange-500"
                    data={customEvents}
                    isLoading={customEventsLoading}
                  />
                </TabsContent>
              </Tabs>
            </>
          )}
        </TabsContent>

        <TabsContent value="product" className="mt-2">
          {productPresenceLoading ? (
            <div className="h-[220px] w-full animate-pulse rounded bg-muted" />
          ) : hasProductEvents ? (
            <ProductAnalyticsTab projectId={projectId} params={productParams} />
          ) : (
            <ProductAnalyticsEmptyState project={project} projectId={projectId} />
          )}
        </TabsContent>
      </Tabs>
    </div>
  )
}

function WebAnalyticsEmptyState({project}: {project?: Project}) {
  const scriptHost = getProjectApiHost(project)
  const publicKey = getProjectPublicKey(project)
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
          <div className="mx-auto flex items-center justify-center h-12 w-12 rounded-full bg-blue-500/10 mb-3">
            <BarChart3 className="h-6 w-6 text-blue-500" />
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

function getProjectPublicKey(project?: Project): string {
  if (!project?.dsn) return 'your-project-public-key'

  try {
    const url = new URL(project.dsn)
    return url.username || 'your-project-public-key'
  } catch {
    return 'your-project-public-key'
  }
}

function ProductAnalyticsTab({projectId, params}: {projectId: string; params: AnalyticsParams}) {
  const {data: productEvents, isLoading: productEventsLoading} = useQuery({
    queryKey: ['analytics-product-events', projectId, params],
    queryFn: () => api.getAnalyticsEvents(projectId, params, {
      source: 'server',
      groupBy: 'user_id',
    }),
  })

  const {data: retention, isLoading: retentionLoading} = useQuery({
    queryKey: ['analytics-product-retention', projectId, params],
    queryFn: () => api.getAnalyticsRetention(projectId, {
      ...params,
      startEvent: PRODUCT_RETENTION_START_EVENT,
      returnEvent: PRODUCT_RETENTION_RETURN_EVENT,
      periods: PRODUCT_RETENTION_PERIODS,
    }),
  })

  return (
    <div className="space-y-2">
      <div className="grid gap-2 lg:grid-cols-[minmax(0,0.9fr)_minmax(0,1.1fr)]">
        <AnalyticsBreakdownTable
          title="Product Events"
          icon={MousePointerClick}
          iconColor="text-orange-500"
          data={productEvents}
          isLoading={productEventsLoading}
        />
        <ProductFunnelPanel projectId={projectId} params={params} />
      </div>
      <AnalyticsRetentionTable data={retention} isLoading={retentionLoading} />
    </div>
  )
}

function ProductFunnelPanel({projectId, params}: {projectId: string; params: AnalyticsParams}) {
  const [steps, setSteps] = useState(DEFAULT_PRODUCT_FUNNEL_STEPS)
  const validSteps = steps.map(step => step.trim()).filter(Boolean)
  const {data: funnel, isLoading} = useQuery({
    queryKey: ['analytics-product-funnel', projectId, params, validSteps],
    queryFn: () => api.getAnalyticsFunnel(projectId, validSteps, params, {
      source: 'server',
      groupBy: 'user_id',
    }),
    enabled: validSteps.length >= 2,
  })

  const updateStep = (index: number, value: string) => {
    setSteps(current => current.map((step, stepIndex) => (stepIndex === index ? value : step)))
  }

  const removeStep = (index: number) => {
    setSteps(current => current.filter((_, stepIndex) => stepIndex !== index))
  }

  return (
    <Card>
      <CardContent className="p-3 space-y-3">
        <div className="flex items-center justify-between gap-2">
          <div className="flex items-center gap-1.5 text-xs font-medium">
            <Target className="h-3.5 w-3.5 text-emerald-500" />
            Activation Funnel
          </div>
          <Button
            type="button"
            variant="outline"
            size="sm"
            onClick={() => setSteps(current => [...current, ''])}
          >
            <Plus className="h-3.5 w-3.5" />
            Step
          </Button>
        </div>

        <div className="space-y-1.5">
          {steps.map((step, index) => (
            <div key={index} className="flex items-center gap-1.5">
              <Input
                value={step}
                onChange={(event) => updateStep(index, event.target.value)}
                className="h-7 text-xs"
                placeholder="event.name"
              />
              <Button
                type="button"
                variant="ghost"
                size="icon"
                className="h-7 w-7"
                aria-label="Remove funnel step"
                disabled={steps.length <= 2}
                onClick={() => removeStep(index)}
              >
                <Trash2 className="h-3.5 w-3.5" />
              </Button>
            </div>
          ))}
        </div>

        <ProductFunnelResults data={funnel} isLoading={isLoading} hasEnoughSteps={validSteps.length >= 2} />
      </CardContent>
    </Card>
  )
}

function ProductFunnelResults({
  data,
  isLoading,
  hasEnoughSteps,
}: {
  data?: AnalyticsFunnelResponse
  isLoading: boolean
  hasEnoughSteps: boolean
}) {
  if (!hasEnoughSteps) {
    return <p className="text-xs text-muted-foreground text-center py-4">Add at least two steps</p>
  }

  if (isLoading) {
    return <div className="h-[140px] w-full animate-pulse rounded bg-muted" />
  }

  if (!data || data.steps.length === 0) {
    return <p className="text-xs text-muted-foreground text-center py-4">No funnel data</p>
  }

  const maxVisitors = Math.max(data.steps[0]?.visitors ?? 0, 1)

  return (
    <div className="space-y-2">
      <div className="text-xs text-muted-foreground">
        Overall conversion <span className="font-medium text-foreground">{data.overallConversion.toFixed(1)}%</span>
      </div>
      <div className="space-y-1.5">
        {data.steps.map(step => (
          <div key={step.name} className="space-y-1">
            <div className="flex items-center justify-between gap-2 text-xs">
              <span className="truncate font-medium">{step.name}</span>
              <span className="text-muted-foreground">
                {step.visitors.toLocaleString()} / {step.conversionRate.toFixed(1)}%
              </span>
            </div>
            <div className="h-2 rounded bg-muted">
              <div
                className="h-2 rounded bg-emerald-500"
                style={{width: `${Math.max((step.visitors / maxVisitors) * 100, step.visitors > 0 ? 2 : 0)}%`}}
              />
            </div>
          </div>
        ))}
      </div>
    </div>
  )
}

function ProductAnalyticsEmptyState({project, projectId}: {project?: Project; projectId: string}) {
  const apiHost = getProjectApiHost(project)
  const endpoint = `${apiHost}/v1/analytics/${projectId}/events`
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
          <div className="mx-auto flex items-center justify-center h-12 w-12 rounded-full bg-blue-500/10 mb-3">
            <Target className="h-6 w-6 text-blue-500" />
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

function getProjectApiHost(project?: Project): string {
  if (!project?.dsn) return 'https://your-moneat-instance.com'

  try {
    const url = new URL(project.dsn)
    return `${url.protocol}//${url.host}`
  } catch {
    return 'https://your-moneat-instance.com'
  }
}
