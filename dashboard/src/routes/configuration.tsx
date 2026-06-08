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

import {useMemo, useState} from 'react'
import {createFileRoute, redirect} from '@tanstack/react-router'
import {useQuery} from '@tanstack/react-query'
import {Plus, SlidersHorizontal} from 'lucide-react'
import {api} from '@/lib/api'
import {Button} from '@/components/ui/button'
import {Card, CardContent, CardDescription, CardHeader, CardTitle} from '@/components/ui/card'
import {Label} from '@/components/ui/label'
import {PageHeader} from '@/components/ui/page-header'
import {EmptyState} from '@/components/ui/empty-state'
import {TelemetrySourcePicker} from '@/components/projects/TelemetrySourcePicker'
import {ServiceSettingsCard} from '@/components/projects/ServiceSettingsCard'
import {
  DEFAULT_SELECTED_TELEMETRY_SOURCE_IDS,
  loadTelemetrySourceIdsForService,
  storeTelemetrySourceIdsForService,
  type TelemetrySourceId,
} from '@/lib/telemetry-sources'

export const Route = createFileRoute('/configuration')({
  beforeLoad: async ({location}) => {
    if (!api.isAuthenticated()) {
      throw redirect({to: '/login', search: {redirect: location.href}})
    }
  },
  component: ConfigurationPage,
})

// Creation is owned by the sidebar's shared dialog — Configuration just opens it.
function openCreateServiceDialog() {
  globalThis.dispatchEvent(new CustomEvent('open-create-service-dialog'))
}

function loadSourcesForService(serviceId: string | null): TelemetrySourceId[] {
  if (!serviceId) return DEFAULT_SELECTED_TELEMETRY_SOURCE_IDS
  const stored = loadTelemetrySourceIdsForService(serviceId)
  return stored.length > 0 ? stored : DEFAULT_SELECTED_TELEMETRY_SOURCE_IDS
}

function ConfigurationPage() {
  const {data: services, isLoading} = useQuery({
    queryKey: ['projects'],
    queryFn: () => api.getProjects(),
    enabled: api.isAuthenticated(),
  })

  // Local service selection for this page only.
  const [chosenId, setChosenId] = useState<string | null>(null)
  const selectedId = useMemo(() => {
    if (!services || services.length === 0) return null
    const ids = services.map((service) => service.resourceId)
    if (chosenId && ids.includes(chosenId)) return chosenId
    return services[0].resourceId
  }, [services, chosenId])

  // Telemetry-source selection for the chosen service, persisted to localStorage.
  // Reload (without an effect) whenever the selected service changes.
  const [sources, setSources] = useState<TelemetrySourceId[]>(() => loadSourcesForService(selectedId))
  const [sourcesServiceId, setSourcesServiceId] = useState<string | null>(selectedId)
  if (selectedId !== sourcesServiceId) {
    setSourcesServiceId(selectedId)
    setSources(loadSourcesForService(selectedId))
  }

  const handleSourcesChange = (next: TelemetrySourceId[]) => {
    setSources(next)
    if (selectedId) storeTelemetrySourceIdsForService(selectedId, next)
  }

  return (
    <div className="mx-auto max-w-4xl space-y-6 p-4 sm:p-6">
      <PageHeader
        icon={SlidersHorizontal}
        title="Configuration"
        description="Create and configure your services and their telemetry sources."
        actions={
          <Button size="sm" onClick={openCreateServiceDialog}>
            <Plus className="h-4 w-4" />
            New Service
          </Button>
        }
      />

      {isLoading ? (
        <p className="text-sm text-muted-foreground">Loading services…</p>
      ) : !services || services.length === 0 ? (
        <EmptyState
          icon={SlidersHorizontal}
          title="No services yet"
          description="Create a service to configure its telemetry sources and platform."
          action={
            <Button onClick={openCreateServiceDialog}>
              <Plus className="h-4 w-4" />
              New Service
            </Button>
          }
        />
      ) : (
        <>
          <div className="flex max-w-sm flex-col gap-1.5">
            <Label htmlFor="service-select">Service</Label>
            <select
              id="service-select"
              value={selectedId ?? ''}
              onChange={(event) => setChosenId(event.target.value)}
              className="h-9 rounded-md border border-input bg-background px-3 text-sm focus:outline-none focus:ring-2 focus:ring-ring"
            >
              {services.map((service) => (
                <option key={service.resourceId} value={service.resourceId}>
                  {service.name}
                </option>
              ))}
            </select>
          </div>

          {selectedId && (
            <>
              <Card>
                <CardHeader>
                  <CardTitle>Telemetry sources</CardTitle>
                  <CardDescription>
                    Choose which integrations this service uses — this controls the configuration shown below.
                  </CardDescription>
                </CardHeader>
                <CardContent>
                  <TelemetrySourcePicker value={sources} onChange={handleSourcesChange} label="" description="" />
                </CardContent>
              </Card>

              <ServiceSettingsCard
                key={selectedId}
                serviceId={selectedId}
                sourceIds={sources}
                onDeleted={() => setChosenId(null)}
              />
            </>
          )}
        </>
      )}
    </div>
  )
}
