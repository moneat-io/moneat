import {createFileRoute, Outlet} from '@tanstack/react-router'
import {BarChart3} from 'lucide-react'
import {useProject} from '@/contexts/ProjectContext'
import {useQuery} from '@tanstack/react-query'
import {api} from '@/lib/api'
import {AnalyticsParamsProvider} from '@/contexts/AnalyticsParamsProvider'
import {useAnalyticsParams} from '@/contexts/UseAnalyticsParams'
import {AnalyticsDatePicker} from '@/components/analytics/AnalyticsDatePicker'
import {AnalyticsRealtimeBadge} from '@/components/analytics/AnalyticsRealtimeBadge'

export const Route = createFileRoute('/analytics')({
  component: AnalyticsLayout,
})

function AnalyticsLayoutInner() {
  const {selectedProjectId} = useProject()
  const {period, setPeriod, customFrom, customTo, onCustomRangeChange} = useAnalyticsParams()

  const {data: projects} = useQuery({
    queryKey: ['projects'],
    queryFn: () => api.getProjects(),
  })

  const hasSelectedProject = selectedProjectId != null && projects?.some(p => p.resourceId === selectedProjectId)
  const projectId = (hasSelectedProject ? selectedProjectId : null) || projects?.[0]?.resourceId
  const project = projects?.find(p => p.resourceId === projectId)

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
              {project ? `${project.name} — ` : ''}Privacy-focused web analytics
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
          {projectId && <AnalyticsRealtimeBadge projectId={projectId} />}
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
