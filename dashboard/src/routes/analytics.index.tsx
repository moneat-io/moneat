import {useState, useCallback} from 'react'
import {createFileRoute} from '@tanstack/react-router'
import {useQuery} from '@tanstack/react-query'
import {api} from '@/lib/api'
import {useProject} from '@/contexts/ProjectContext'
import {useAnalyticsParams} from '@/contexts/AnalyticsParamsContext'
import {AnalyticsFilterBar} from '@/components/analytics/AnalyticsFilterBar'
import {AnalyticsKpiCards, AnalyticsKpiCardsSkeleton} from '@/components/analytics/AnalyticsKpiCards'
import {AnalyticsChart} from '@/components/analytics/AnalyticsChart'
import {AnalyticsBreakdownTable} from '@/components/analytics/AnalyticsBreakdownTable'
import {Tabs, TabsContent, TabsList, TabsTrigger} from '@/components/ui/tabs'
import {Card, CardContent} from '@/components/ui/card'
import {CopyBlock} from '@/components/ui/copy-block'
import {
  ArrowRight, BarChart3, BookOpen, FileText, Globe, Laptop, LogIn, LogOut, MapPin, Megaphone, MousePointerClick, Share2,
} from 'lucide-react'
import type {AnalyticsFilter, AnalyticsParams, AnalyticsBreakdownItem} from '@/lib/api'

export const Route = createFileRoute('/analytics/')({
  component: AnalyticsOverview,
})

function AnalyticsOverview() {
  const {selectedProjectId} = useProject()
  const {period, customFrom, customTo} = useAnalyticsParams()
  const [filters, setFilters] = useState<AnalyticsFilter[]>([])
  const [breakdownTab, setBreakdownTab] = useState('pages')

  const {data: projects} = useQuery({
    queryKey: ['projects'],
    queryFn: () => api.getProjects(),
  })

  const hasSelectedProject = selectedProjectId != null && projects?.some(p => p.id === selectedProjectId)
  const projectId = (hasSelectedProject ? selectedProjectId : null) || projects?.[0]?.id
  const project = projects?.find(p => p.id === projectId)

  const buildParams = useCallback((): AnalyticsParams => ({
    period,
    ...(period === 'custom' && customFrom && customTo ? {from: customFrom, to: customTo} : {}),
    filters: filters.length > 0 ? filters : undefined,
    comparison: 'previous_period',
  }), [period, customFrom, customTo, filters])

  const params = buildParams()

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

  if (!overviewLoading && (!overview || (overview.uniqueVisitors === 0 && overview.totalPageviews === 0))) {
    let scriptHost = 'https://your-moneat-instance.com'
    let publicKey = 'your-project-public-key'
    if (project?.dsn) {
      try {
        const url = new URL(project.dsn)
        scriptHost = `${url.protocol}//${url.host}`
        publicKey = url.username || publicKey
      } catch { /* ignore parse errors */ }
    }

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

  return (
    <div className="space-y-2">
      {/* Filters */}
      <AnalyticsFilterBar filters={filters} onFiltersChange={setFilters} />

      {/* KPI Cards */}
      {overviewLoading ? (
        <AnalyticsKpiCardsSkeleton />
      ) : (
        <AnalyticsKpiCards data={overview} isLoading={overviewLoading} />
      )}

      {/* Timeseries Chart */}
      <AnalyticsChart data={timeseries} isLoading={timeseriesLoading} />

      {/* Breakdown Tables with Tabs */}
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
    </div>
  )
}
