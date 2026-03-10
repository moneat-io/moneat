import {createFileRoute, Outlet} from '@tanstack/react-router'
import {BarChart3} from 'lucide-react'
import {useProject} from '@/contexts/ProjectContext'
import {useQuery} from '@tanstack/react-query'
import {api} from '@/lib/api'
import {AnalyticsRealtimeBadge} from '@/components/analytics/AnalyticsRealtimeBadge'

export const Route = createFileRoute('/analytics')({
  component: AnalyticsLayout,
})

function AnalyticsLayout() {
  const {selectedProjectId} = useProject()

  const {data: projects} = useQuery({
    queryKey: ['projects'],
    queryFn: () => api.getProjects(),
  })

  const hasSelectedProject = selectedProjectId != null && projects?.some(p => p.id === selectedProjectId)
  const projectId = (hasSelectedProject ? selectedProjectId : null) || projects?.[0]?.id
  const project = projects?.find(p => p.id === projectId)

  return (
    <div className="p-6 space-y-5">
      <div className="flex items-center justify-between">
        <div className="flex items-center gap-2.5">
          <div className="flex items-center justify-center h-8 w-8 rounded-lg bg-gradient-to-br from-blue-500 to-cyan-600">
            <BarChart3 className="h-4 w-4 text-white" />
          </div>
          <div>
            <h1 className="text-xl font-semibold leading-tight">Analytics</h1>
            <p className="text-muted-foreground text-xs">
              {project ? `${project.name} — ` : ''}Privacy-focused web analytics
            </p>
          </div>
        </div>

        {projectId && <AnalyticsRealtimeBadge projectId={projectId} />}
      </div>

      <Outlet />
    </div>
  )
}
