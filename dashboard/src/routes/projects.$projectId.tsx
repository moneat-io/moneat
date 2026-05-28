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

import {createFileRoute, Outlet, redirect, useRouterState} from '@tanstack/react-router'
import {api} from '@/lib/api'
import {ProjectTelemetrySetupHub} from '@/components/projects/ProjectTelemetrySetupHub'

export const Route = createFileRoute('/projects/$projectId')({
  validateSearch: (search: Record<string, unknown>): {sources?: string} => {
    return typeof search.sources === 'string' ? {sources: search.sources} : {}
  },
  beforeLoad: async ({ location }) => {
    if (!api.isAuthenticated()) {
      throw redirect({ to: '/login', search: { redirect: location.href } })
    }
  },
  loader: async ({ params }) => {
    const project = await api.getProject(params.projectId)
    return { project }
  },
  component: SetupPage,
})

function SetupPage() {
  const routerState = useRouterState()
  const showingChildPage = /^\/projects\/[^/]+\/(settings|traces|spans)\/?/.test(routerState.location.pathname)
  const { project } = Route.useLoaderData()
  const { sources } = Route.useSearch()

  if (showingChildPage) {
    return <Outlet />
  }

  return <ProjectTelemetrySetupHub project={project} selectedSourcesParam={sources} />
}
