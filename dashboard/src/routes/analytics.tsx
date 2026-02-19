// Moneat Enterprise - proprietary module
// Copyright (c) 2026 Moneat. All rights reserved.
// See enterprise/LICENSE for license terms.

import {createFileRoute, Outlet} from '@tanstack/react-router'
import {BarChart3} from 'lucide-react'
import {useProject} from '@/contexts/project-context'
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

  const projectId = selectedProjectId || projects?.[0]?.id
  const project = projects?.find(p => p.id === projectId)

  return (
    <div className="p-6 space-y-6">
      <div className="flex items-center justify-between">
        <div className="flex items-center gap-3">
          <div className="flex items-center justify-center h-10 w-10 rounded-lg bg-gradient-to-br from-blue-500 to-cyan-600 shadow-lg shadow-blue-500/20">
            <BarChart3 className="h-5 w-5 text-white" />
          </div>
          <div>
            <h1 className="text-3xl font-bold">Analytics</h1>
            <p className="text-muted-foreground text-sm">
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
