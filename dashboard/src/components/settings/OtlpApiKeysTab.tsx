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

import {useMutation, useQuery, useQueryClient} from '@tanstack/react-query'
import {Loader2, ScrollText, Trash2} from 'lucide-react'
import {api, type OtlpApiKey, type OtlpObservedService, type Project} from '@/lib/api'
import {Badge} from '@/components/ui/badge'
import {Button} from '@/components/ui/button'
import {Card, CardContent, CardDescription, CardHeader, CardTitle} from '@/components/ui/card'
import {Select, SelectContent, SelectGroup, SelectItem, SelectTrigger, SelectValue} from '@/components/ui/select'
import {Table, TableBody, TableCell, TableHead, TableHeader, TableRow} from '@/components/ui/table'
import {useToast} from '@/hooks/useToast'
import {ApiKeysTabBase} from './ApiKeysTabBase'

const UNMAPPED_PROJECT_VALUE = '__unmapped__'

function serviceLabel(service: OtlpObservedService) {
  if (!service.serviceNamespace) return service.serviceName
  return `${service.serviceNamespace}/${service.serviceName}`
}

function signalBadges(service: OtlpObservedService) {
  return [
    service.seenLogs ? 'logs' : null,
    service.seenTraces ? 'traces' : null,
    service.seenMetrics ? 'metrics' : null,
  ].filter((signal): signal is string => signal != null)
}

export function OtlpApiKeysTab() {
  return (
    <div className="flex flex-col gap-6">
      <ApiKeysTabBase<OtlpApiKey>
        cardId="otlp-api-keys"
        cardTitle="OTLP API Keys"
        cardDescription={
          'Create organization credentials for OpenTelemetry ingestion. ' +
          'Services route to projects by service.name mappings below.'
        }
        docsHref="/docs/logging"
        icon={ScrollText}
        emptyTitle="No OTLP API keys yet"
        emptyDescription="Create a key to send logs, traces, and metrics via OpenTelemetry."
        queryKey={['otlpApiKeys']}
        queryFn={() => api.getOtlpApiKeys()}
        queryEnabled={api.isAuthenticated()}
        createMutationFn={(name) => api.createOtlpApiKey(name)}
        deleteMutationFn={(id) => api.deleteOtlpApiKey(id)}
        createSuccessToast={{
          title: 'OTLP API key created',
          description: "Copy the key now—it won't be shown again.",
        }}
        revokeSuccessToast={{
          title: 'Key revoked',
          description: 'The OTLP API key has been revoked.',
        }}
        createDialogTitle="Create OTLP API Key"
        createDialogDescription={
          'Give this key a name to identify it (e.g. "Production OTLP"). ' +
          'The full key will be shown once and cannot be retrieved later.'
        }
        inputId="key-name"
        inputPlaceholder="e.g. Production OTLP"
        createdDialogTitle="OTLP API Key Created"
        revokeDialogTitle="Revoke OTLP API Key"
        revokeDialogDescription={(name) =>
          `Are you sure you want to revoke "${name}"? Any clients using this key will no longer be able to send data.`
        }
      />
      <OtlpServiceRoutingPanel />
    </div>
  )
}

function OtlpServiceRoutingPanel() {
  const queryClient = useQueryClient()
  const {toast} = useToast()

  const {data: servicesData, isPending: servicesPending} = useQuery({
    queryKey: ['otlpObservedServices'],
    queryFn: () => api.getOtlpObservedServices(),
    enabled: api.isAuthenticated(),
  })
  const {data: projects = []} = useQuery({
    queryKey: ['projects'],
    queryFn: () => api.getProjects(),
    enabled: api.isAuthenticated(),
  })

  const upsertMapping = useMutation({
    mutationFn: (input: {service: OtlpObservedService; projectId: number}) =>
      api.upsertOtlpServiceMapping(
        input.service.serviceName,
        input.projectId,
        input.service.serviceNamespace
      ),
    onSuccess: () => {
      queryClient.invalidateQueries({queryKey: ['otlpObservedServices']})
      toast({title: 'Service mapped', description: 'OTLP telemetry will route to the selected project.'})
    },
    onError: (error: Error) => {
      toast({title: 'Failed to map service', description: error.message, variant: 'destructive'})
    },
  })

  const deleteMapping = useMutation({
    mutationFn: (id: number) => api.deleteOtlpServiceMapping(id),
    onSuccess: () => {
      queryClient.invalidateQueries({queryKey: ['otlpObservedServices']})
      toast({title: 'Mapping removed', description: 'Future telemetry for this service will be unmapped.'})
    },
    onError: (error: Error) => {
      toast({title: 'Failed to remove mapping', description: error.message, variant: 'destructive'})
    },
  })

  const services = servicesData?.services ?? []

  return (
    <Card id="otlp-service-routing">
      <CardHeader>
        <CardTitle className="text-base">Service routing</CardTitle>
        <CardDescription>
          Map OpenTelemetry services to Moneat projects. Unmapped services continue ingesting at
          organization scope.
        </CardDescription>
      </CardHeader>
      <CardContent>
        {servicesPending ? (
          <div className="flex items-center gap-2 text-sm text-muted-foreground py-6">
            <Loader2 className="h-4 w-4 animate-spin" />
            Loading services...
          </div>
        ) : services.length === 0 ? (
          <div className="border rounded-lg p-8 text-center text-sm text-muted-foreground">
            Services appear here after OTLP telemetry is received.
          </div>
        ) : (
          <Table>
            <TableHeader>
              <TableRow>
                <TableHead>Service</TableHead>
                <TableHead>Signals</TableHead>
                <TableHead>Environment</TableHead>
                <TableHead>Project</TableHead>
                <TableHead className="w-[80px]"></TableHead>
              </TableRow>
            </TableHeader>
            <TableBody>
              {services.map((service) => (
                <OtlpServiceRoutingRow
                  key={service.id}
                  service={service}
                  projects={projects}
                  upsertMapping={(projectId) => upsertMapping.mutate({service, projectId})}
                  deleteMapping={() => {
                    if (service.mappingId != null) deleteMapping.mutate(service.mappingId)
                  }}
                  isPending={upsertMapping.isPending || deleteMapping.isPending}
                />
              ))}
            </TableBody>
          </Table>
        )}
      </CardContent>
    </Card>
  )
}

function OtlpServiceRoutingRow(props: {
  readonly service: OtlpObservedService
  readonly projects: Project[]
  readonly upsertMapping: (projectId: number) => void
  readonly deleteMapping: () => void
  readonly isPending: boolean
}) {
  const {service, projects, upsertMapping, deleteMapping, isPending} = props
  const selectedProject = service.projectId == null ? UNMAPPED_PROJECT_VALUE : String(service.projectId)

  return (
    <TableRow>
      <TableCell>
        <div className="flex flex-col gap-1">
          <span className="font-medium">{serviceLabel(service)}</span>
          {service.projectName == null ? (
            <Badge variant="outline">Unmapped</Badge>
          ) : (
            <span className="text-xs text-muted-foreground">Mapped to {service.projectName}</span>
          )}
        </div>
      </TableCell>
      <TableCell>
        <div className="flex flex-wrap gap-1">
          {signalBadges(service).map((signal) => (
            <Badge key={signal} variant="secondary">
              {signal}
            </Badge>
          ))}
        </div>
      </TableCell>
      <TableCell className="text-sm text-muted-foreground">
        {service.lastEnvironment || '—'}
      </TableCell>
      <TableCell className="min-w-[220px]">
        <Select
          value={selectedProject}
          disabled={isPending || projects.length === 0}
          onValueChange={(value) => {
            if (value === UNMAPPED_PROJECT_VALUE) {
              if (service.mappingId != null) deleteMapping()
              return
            }
            upsertMapping(Number(value))
          }}
        >
          <SelectTrigger>
            <SelectValue placeholder="Select project" />
          </SelectTrigger>
          <SelectContent>
            <SelectGroup>
              <SelectItem value={UNMAPPED_PROJECT_VALUE}>Unmapped</SelectItem>
              {projects.map((project) => (
                <SelectItem key={project.id} value={String(project.id)}>
                  {project.name}
                </SelectItem>
              ))}
            </SelectGroup>
          </SelectContent>
        </Select>
      </TableCell>
      <TableCell>
        <Button
          variant="ghost"
          size="sm"
          disabled={isPending || service.mappingId == null}
          onClick={deleteMapping}
          aria-label={`Remove mapping for ${serviceLabel(service)}`}
          title="Remove mapping"
        >
          <Trash2 className="h-4 w-4" />
        </Button>
      </TableCell>
    </TableRow>
  )
}
