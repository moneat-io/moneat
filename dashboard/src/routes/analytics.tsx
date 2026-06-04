import {createFileRoute, Outlet} from '@tanstack/react-router'
import {BarChart3} from 'lucide-react'
import {useQuery} from '@tanstack/react-query'
import {api} from '@/lib/api'
import {AnalyticsParamsProvider} from '@/contexts/AnalyticsParamsProvider'
import {useAnalyticsParams} from '@/contexts/UseAnalyticsParams'
import {AnalyticsDatePicker} from '@/components/analytics/AnalyticsDatePicker'
import {AnalyticsRealtimeBadge} from '@/components/analytics/AnalyticsRealtimeBadge'
import {primaryServiceResourceId} from '@/lib/service-facet-scope'

export const Route = createFileRoute('/analytics')({
  component: AnalyticsLayout,
})

function AnalyticsLayoutInner() {
  const {period, setPeriod, customFrom, customTo, onCustomRangeChange} = useAnalyticsParams()

  const {data: services} = useQuery({
    queryKey: ['projects'],
    queryFn: () => api.getProjects(),
  })

  const serviceId = primaryServiceResourceId(services)
  const service = services?.find((candidate) => candidate.resourceId === serviceId)

  return (
    <div className="p-3 space-y-2">
      <div className="flex items-center justify-between gap-2">
        <div className="flex items-center gap-2">
          <div className="flex items-center justify-center h-7 w-7 rounded-md bg-muted text-muted-foreground">
            <BarChart3 className="h-3.5 w-3.5" />
          </div>
          <div>
            <h1 className="text-lg font-semibold leading-tight">Analytics</h1>
            <p className="text-muted-foreground text-xs">
              {service ? `${service.name} — ` : ''}Privacy-focused web analytics
            </p>
          </div>
        </div>

        <div className="flex items-center gap-2">
          <AnalyticsDatePicker
            period={period}
            onPeriodChange={setPeriod}
            customFrom={customFrom}
            customTo={customTo}
            onCustomRangeChange={onCustomRangeChange}
          />
          {serviceId && <AnalyticsRealtimeBadge serviceId={serviceId} />}
        </div>
      </div>

      <Outlet />
    </div>
  )
}

function AnalyticsLayout() {
  return (
    <AnalyticsParamsProvider>
      <AnalyticsLayoutInner />
    </AnalyticsParamsProvider>
  )
}
