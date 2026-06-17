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
import {useQuery} from '@tanstack/react-query'
import {Boxes, Plus} from 'lucide-react'
import {api} from '@/lib/api'
import {Button} from '@/components/ui/button'
import {Label} from '@/components/ui/label'
import {EmptyState} from '@/components/ui/empty-state'
import {SectionCard} from '@/components/ui/section-card'
import {TelemetrySourcePicker} from '@/components/projects/TelemetrySourcePicker'
import {ServiceSettingsCard} from '@/components/projects/ServiceSettingsCard'
import {
  DEFAULT_SELECTED_TELEMETRY_SOURCE_IDS,
  loadTelemetrySourceIdsForService,
  storeTelemetrySourceIdsForService,
  type TelemetrySourceId,
} from '@/lib/telemetry-sources'

// Creation is owned by the sidebar's shared dialog — Setup just opens it.
function openCreateServiceDialog() {
  globalThis.dispatchEvent(new CustomEvent('open-create-service-dialog'))
}

function loadSourcesForService(serviceId: string | null): TelemetrySourceId[] {
  if (!serviceId) return DEFAULT_SELECTED_TELEMETRY_SOURCE_IDS
  const stored = loadTelemetrySourceIdsForService(serviceId)
  return stored.length > 0 ? stored : DEFAULT_SELECTED_TELEMETRY_SOURCE_IDS
}

interface ServicesAndSdksSetupProps {
  readonly selectedServiceId?: string
}

export function ServicesAndSdksSetup({selectedServiceId}: ServicesAndSdksSetupProps) {
  const {data: services, isLoading} = useQuery({
    queryKey: ['projects'],
    queryFn: () => api.getProjects(),
    enabled: api.isAuthenticated(),
  })

  // Local service selection for this page only.
  const [chosenId, setChosenId] = useState<string | null>(null)
  const selectedId = useMemo(() => {
    if (!services || services.length === 0) return null
    const ids = services.map((service) => service.id)
    const requestedId = chosenId ?? selectedServiceId
    if (requestedId && ids.includes(requestedId)) return requestedId
    return services[0].id
  }, [services, chosenId, selectedServiceId])

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

  if (isLoading) {
    return <p className="text-sm text-muted-foreground">Loading services…</p>
  }

  if (!services || services.length === 0) {
    return (
      <EmptyState
        icon={Boxes}
        title="No services yet"
        description="Create a service to configure its telemetry sources and platform."
        action={
          <Button onClick={openCreateServiceDialog}>
            <Plus className="h-4 w-4" />
            New Service
          </Button>
        }
      />
    )
  }

  return (
    <div className="flex flex-col gap-3">
      <div className="flex flex-wrap items-end justify-between gap-3">
        <div className="flex max-w-sm flex-1 flex-col gap-1.5">
          <Label htmlFor="service-select" className="text-[11px] text-muted-foreground">
            Service
          </Label>
          <select
            id="service-select"
            value={selectedId ?? ''}
            onChange={(event) => setChosenId(event.target.value)}
            className="h-8 rounded-md border border-input bg-background px-2.5 text-xs focus:outline-none focus:ring-2 focus:ring-ring"
          >
            {services.map((service) => (
              <option key={service.id} value={service.id}>
                {service.name}
              </option>
            ))}
          </select>
        </div>
        <Button size="sm" onClick={openCreateServiceDialog}>
          <Plus className="h-4 w-4" />
          New Service
        </Button>
      </div>

      {selectedId && (
        <>
          <SectionCard title="Telemetry sources" bodyClassName="flex flex-col gap-3">
            <p className="text-xs text-muted-foreground">
              Choose which integrations this service uses — this controls the configuration shown below.
            </p>
            <TelemetrySourcePicker value={sources} onChange={handleSourcesChange} label="" description="" />
          </SectionCard>

          <ServiceSettingsCard
            key={selectedId}
            serviceId={selectedId}
            sourceIds={sources}
            onDeleted={() => setChosenId(null)}
          />
        </>
      )}
    </div>
  )
}
