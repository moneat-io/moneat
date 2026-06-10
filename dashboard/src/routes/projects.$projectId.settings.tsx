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

import {createFileRoute, Link, redirect} from '@tanstack/react-router'
import {ArrowLeft} from 'lucide-react'
import {api} from '@/lib/api'
import {ServiceSettingsCard} from '@/components/projects/ServiceSettingsCard'
import {
  DEFAULT_SELECTED_TELEMETRY_SOURCE_IDS,
  loadTelemetrySourceIdsForService,
} from '@/lib/telemetry-sources'

export const Route = createFileRoute('/projects/$projectId/settings')({
  beforeLoad: async ({location}) => {
    if (!api.isAuthenticated()) {
      throw redirect({to: '/login', search: {redirect: location.href}})
    }
  },
  loader: async ({params}) => {
    const service = await api.getProject(params.projectId)
    return {service}
  },
  component: ServiceSettingsPage,
})

function ServiceSettingsPage() {
  const {service} = Route.useLoaderData()
  const stored = loadTelemetrySourceIdsForService(service.id)
  const sourceIds = stored.length > 0 ? stored : DEFAULT_SELECTED_TELEMETRY_SOURCE_IDS

  return (
    <div>
      <div className="p-6 max-w-4xl mx-auto space-y-6">
        <div className="space-y-2">
          <Link
            to="/projects/$projectId"
            params={{projectId: service.id}}
            className="inline-flex items-center gap-1 text-sm text-muted-foreground hover:text-foreground"
          >
            <ArrowLeft className="h-4 w-4" />
            Back to ingestion setup
          </Link>
          <div>
            <h1 className="text-2xl font-bold">Service settings</h1>
            <p className="text-sm text-muted-foreground">Manage service details and platform configuration.</p>
          </div>
        </div>

        <ServiceSettingsCard serviceId={service.id} sourceIds={sourceIds} />
      </div>
    </div>
  )
}
