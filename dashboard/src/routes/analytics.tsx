import {createFileRoute, Outlet} from '@tanstack/react-router'
import {BarChart3} from 'lucide-react'
import {useProject} from '@/contexts/ProjectContext'
import {useQuery} from '@tanstack/react-query'
import {api} from '@/lib/api'
import {AnalyticsParamsProvider, useAnalyticsParams} from '@/contexts/AnalyticsParamsContext'
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

  const hasSelectedProject = selectedProjectId != null && projects?.some(p => p.id === selectedProjectId)
  const projectId = (hasSelectedProject ? selectedProjectId : null) || projects?.[0]?.id
  const project = projects?.find(p => p.id === projectId)

  return (
    <div className="p-3 space-y-2">
      <div className="flex items-center justify-between gap-2">
        <div className="flex items-center gap-2">
          <div className="flex items-center justify-center h-7 w-7 rounded-lg bg-gradient-to-br from-blue-500 to-cyan-600">
            <BarChart3 className="h-3.5 w-3.5 text-white" />
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
