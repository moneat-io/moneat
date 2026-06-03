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

import {Link, useNavigate, useRouter} from '@tanstack/react-router'
import {useMutation, useQuery, useQueryClient} from '@tanstack/react-query'
import {
  Check,
  Copy,
  DatabaseZap,
  ExternalLink,
  KeyRound,
  Plus,
  RadioTower,
  ScrollText,
  ServerCog,
  Settings,
  X,
} from 'lucide-react'
import {useEffect, useMemo, useState, type ComponentType, type ReactNode} from 'react'
import {api, type Project, type ProjectKey} from '@/lib/api'
import {getSetupDocs, type PlatformSetupDocs} from '@/lib/setup-docs'
import {getPlatformInfo} from '@/routes/projects'
import {backendBaseUrl} from '@/lib/backend-url'
import {cn} from '@/lib/utils'
import {useToast} from '@/hooks/useToast'
import {Badge} from '@/components/ui/badge'
import {Button} from '@/components/ui/button'
import {CopyBlock} from '@/components/ui/copy-block'
import {Tabs, TabsContent, TabsList, TabsTrigger} from '@/components/ui/tabs'
import {
  ALL_TELEMETRY_SOURCE_IDS,
  TELEMETRY_SOURCES,
  getTelemetrySource,
  getTelemetrySourceStatus,
  loadTelemetrySourceIdsForProject,
  parseTelemetrySourceIds,
  serializeTelemetrySourceIds,
  storeTelemetrySourceIdsForProject,
  toggleTelemetrySourceId,
  type TelemetrySourceId,
  type TelemetrySourceStatusState,
} from '@/lib/telemetry-sources'

interface ProjectTelemetrySetupHubProps {
  readonly project: Project
  readonly selectedSourcesParam?: string
}

const sourceIcons: Record<TelemetrySourceId, ComponentType<{className?: string}>> = {
  'opentelemetry': RadioTower,
  'sentry-sdk': DatabaseZap,
  'datadog-agent': ServerCog,
}

const DATADOG_AGENT_CONFIG_PATH = '/etc/datadog-agent/datadog.yaml'
const DATADOG_AGENT_SETUP_DOCS_HREF = '/docs/datadog-agent/agent-setup'
const COPY_RESET_MS = 2000

const datadogSetupSteps = [
  {
    title: 'Create an agent key',
    description: 'Use the organization-level key below in the agent config. Existing keys stay hidden after creation.',
  },
  {
    title: 'Save datadog.yaml',
    description: `Save it on the host at ${DATADOG_AGENT_CONFIG_PATH}. Docker mounts that file into the same path.`,
  },
  {
    title: 'Run or restart the agent',
    description: 'The Docker command collects host, container, process, APM, profiling, and log telemetry.',
  },
  {
    title: 'Verify telemetry',
    description: 'Hosts should appear in Monitoring > Hosts within a few minutes with CPU, memory, and agent version.',
  },
] as const

const datadogDocsLinks = [
  {
    label: 'Log collection',
    href: '/docs/datadog-agent/log-collection',
  },
  {
    label: 'Continuous profiling',
    href: '/docs/datadog-agent/continuous-profiling',
  },
  {
    label: 'Kubernetes monitoring',
    href: '/docs/datadog-agent/kubernetes',
  },
  {
    label: 'Database monitoring',
    href: '/docs/datadog-agent/database-monitoring',
  },
] as const

function statusVariant(state: TelemetrySourceStatusState): 'default' | 'secondary' | 'outline' {
  if (state === 'receiving') return 'default'
  if (state === 'configured') return 'secondary'
  return 'outline'
}

function getInitialSourceIds(projectId: string | number, selectedSourcesParam?: string): TelemetrySourceId[] {
  const sourceIdsFromSearch = parseTelemetrySourceIds(selectedSourcesParam)
  if (sourceIdsFromSearch.length > 0) return sourceIdsFromSearch

  const storedSourceIds = loadTelemetrySourceIdsForProject(projectId)
  if (storedSourceIds.length > 0) return storedSourceIds

  return ALL_TELEMETRY_SOURCE_IDS
}

function getTargetLabel(target: string): string {
  return target.split('-').map((word) => word.charAt(0).toUpperCase() + word.slice(1)).join(' ')
}

function getProjectKey(project: Project, targetId: string | null): ProjectKey {
  if (!targetId || targetId === 'default') return project.keys[0]
  return project.keys.find((key) => key.platformTarget === targetId) ?? project.keys[0]
}

function getDocsForTarget(
  project: Project,
  selectedTarget: string | null,
  sdkVersions: Record<string, string> | undefined,
  setupOptions: {orgSlug?: string; projectSlug: string; backendUrl: string}
): PlatformSetupDocs | null {
  const key = getProjectKey(project, selectedTarget)
  if (!selectedTarget || selectedTarget === 'default') {
    return getSetupDocs(project.framework, project.dsn, sdkVersions, setupOptions)
  }

  const targetDocs = getSetupDocs(
    `${project.framework}-${selectedTarget}`,
    key.dsn,
    sdkVersions,
    setupOptions
  )
  return targetDocs || getSetupDocs(project.framework, key.dsn, sdkVersions, setupOptions)
}

function keyPlaceholder(createdKey: string | null, fallback: string): string {
  return createdKey ?? fallback
}

function datadogLogsEndpoint(ingestUrl: string): {address: string; noSsl: boolean} {
  try {
    const parsed = new URL(ingestUrl)
    const port = parsed.port || (parsed.protocol === 'http:' ? '80' : '443')
    return {
      address: `${parsed.hostname}:${port}`,
      noSsl: parsed.protocol === 'http:',
    }
  } catch {
    return {
      address: ingestUrl.replace(/^https?:\/\//, '').replace(/\/.*$/, ''),
      noSsl: false,
    }
  }
}

function datadogYaml(apiKey: string): string {
  const baseUrl = backendBaseUrl.replace(/\/$/, '')
  const ingestUrl = baseUrl + '/dd'
  const logsEndpoint = datadogLogsEndpoint(ingestUrl)
  return `# Datadog Agent configuration for Moneat
# Save this file as ${DATADOG_AGENT_CONFIG_PATH}
# Redirects agent telemetry to your Moneat backend.
api_key: ${apiKey}
dd_url: ${ingestUrl}
docker_query_timeout: 15

apm_config:
  apm_dd_url: ${ingestUrl}
  profiling_dd_url: ${ingestUrl}/profiling/v1/input

process_config:
  process_dd_url: ${ingestUrl}

logs_config:
  logs_dd_url: ${logsEndpoint.address}
  logs_no_ssl: ${logsEndpoint.noSsl}

container_lifecycle:
  dd_url: ${baseUrl}
container_image:
  dd_url: ${baseUrl}
sbom:
  dd_url: ${baseUrl}

network_devices:
  metadata:
    dd_url: ${baseUrl}
  snmp_traps:
    forwarder:
      dd_url: ${baseUrl}
  netflow:
    forwarder:
      dd_url: ${baseUrl}
network_config_management:
  forwarder:
    dd_url: ${baseUrl}
network_path:
  forwarder:
    dd_url: ${baseUrl}

synthetics:
  forwarder:
    dd_url: ${baseUrl}
data_streams:
  forwarder:
    dd_url: ${baseUrl}
event_management:
  forwarder:
    dd_url: ${baseUrl}
database_monitoring:
  metrics:
    dd_url: ${baseUrl}
  samples:
    dd_url: ${baseUrl}
  activity:
    dd_url: ${baseUrl}`
}

function datadogDockerCommand(apiKey: string): string {
  return `docker run -d --name moneat-agent \\
  -e DD_API_KEY=${apiKey} \\
  -v ${DATADOG_AGENT_CONFIG_PATH}:${DATADOG_AGENT_CONFIG_PATH}:ro \\
  -v /var/run/docker.sock:/var/run/docker.sock:ro \\
  -v /proc/:/host/proc/:ro \\
  -v /sys/fs/cgroup/:/host/sys/fs/cgroup:ro \\
  --pid host \\
  gcr.io/datadoghq/agent:7`
}

function otelEnvironmentSnippet(apiKey: string): string {
  const baseUrl = backendBaseUrl.replace(/\/$/, '')
  return `export OTEL_SERVICE_NAME=my-service
export OTEL_RESOURCE_ATTRIBUTES=deployment.environment=production
export OTEL_EXPORTER_OTLP_PROTOCOL=http/protobuf
export OTEL_EXPORTER_OTLP_HEADERS="Authorization=Bearer ${apiKey}"
export OTEL_EXPORTER_OTLP_TRACES_ENDPOINT=${baseUrl}/v1/traces/otlp
export OTEL_EXPORTER_OTLP_METRICS_ENDPOINT=${baseUrl}/v1/metrics/otlp
export OTEL_EXPORTER_OTLP_LOGS_ENDPOINT=${baseUrl}/v1/logs/otlp`
}

function httpLogsSnippet(apiKey: string): string {
  const baseUrl = backendBaseUrl.replace(/\/$/, '')
  return `curl -X POST ${baseUrl}/v1/logs/otlp \\
  -H "Content-Type: application/json" \\
  -H "Authorization: Bearer ${apiKey}" \\
  -d '{
    "resourceLogs": [{
      "resource": {
        "attributes": [
          {"key": "service.name", "value": {"stringValue": "my-service"}},
          {"key": "deployment.environment", "value": {"stringValue": "production"}}
        ]
      },
      "scopeLogs": [{
        "logRecords": [{
          "timeUnixNano": "'$(date +%s)'000000000",
          "severityText": "INFO",
          "body": {"stringValue": "Hello from Moneat"}
        }]
      }]
    }]
  }'`
}

function TargetPlatformSection({
  project,
  selectedTarget,
  onSelectedTargetChange,
}: {
  readonly project: Project
  readonly selectedTarget: string | null
  readonly onSelectedTargetChange: (target: string | null) => void
}) {
  const queryClient = useQueryClient()
  const router = useRouter()
  const platformInfo = getPlatformInfo(project.framework)
  const existingTargets = project.keys
    .map((key) => key.platformTarget)
    .filter(Boolean) as string[]
  const availableTargets = platformInfo?.targets?.filter((target) => !existingTargets.includes(target.id)) ?? []
  const [showAddPlatform, setShowAddPlatform] = useState(false)

  const addTargetMutation = useMutation({
    mutationFn: (target: string) => api.addProjectTarget(project.resourceId, target),
    onSuccess: (_key, target) => {
      queryClient.invalidateQueries({queryKey: ['projects']})
      router.invalidate()
      onSelectedTargetChange(target)
      setShowAddPlatform(false)
    },
  })

  if (project.keys.length <= 1 && availableTargets.length === 0) return null

  return (
    <FlatSetupSection
      title="Target platforms"
      description="Each target platform gets its own DSN for separate error monitoring."
      action={availableTargets.length > 0 && !showAddPlatform ? (
        <Button
          type="button"
          variant="outline"
          size="sm"
          onClick={() => setShowAddPlatform(true)}
          className="w-fit gap-1.5"
        >
          <Plus className="h-3.5 w-3.5" />
          Add Platform
        </Button>
      ) : null}
    >
      <div className="flex flex-col gap-4">
        <div className="flex flex-wrap gap-2">
          {project.keys.map((key) => {
            const target = key.platformTarget ?? 'default'
            const isSelected = (selectedTarget ?? 'default') === target
            return (
              <button
                key={target}
                type="button"
                onClick={() => onSelectedTargetChange(key.platformTarget ?? 'default')}
                className={cn(
                  'rounded-md border px-3 py-1.5 text-sm transition-colors',
                  isSelected ? 'border-primary bg-primary/10' : 'border-border hover:bg-muted'
                )}
                aria-pressed={isSelected}
              >
                {key.platformTarget ? getTargetLabel(key.platformTarget) : 'Default'}
              </button>
            )
          })}
        </div>

        {showAddPlatform ? (
          <div className="rounded-lg border border-dashed p-4">
            <div className="mb-3 flex items-center justify-between">
              <span className="text-sm font-medium">Add a target platform</span>
              <Button
                type="button"
                variant="ghost"
                size="icon"
                className="h-6 w-6"
                onClick={() => setShowAddPlatform(false)}
              >
                <X className="h-3.5 w-3.5" />
              </Button>
            </div>
            <div className="grid grid-cols-2 gap-2 sm:grid-cols-3">
              {availableTargets.map((target) => {
                const targetPlatformInfo = getPlatformInfo(target.id)
                const TargetIcon = targetPlatformInfo?.icon
                return (
                  <button
                    key={target.id}
                    type="button"
                    onClick={() => addTargetMutation.mutate(target.id)}
                    disabled={addTargetMutation.isPending}
                    className={cn(
                      'flex items-center gap-2.5 rounded-lg border-2 px-3 py-2.5 text-sm font-medium',
                      'transition-all hover:border-primary hover:bg-primary/5',
                      addTargetMutation.isPending && 'cursor-not-allowed opacity-50'
                    )}
                  >
                    {TargetIcon ? (
                      <span
                        className="flex h-6 w-6 shrink-0 items-center justify-center rounded"
                        style={{backgroundColor: targetPlatformInfo?.color}}
                      >
                        <TargetIcon className="h-4 w-4 text-white" />
                      </span>
                    ) : null}
                    <span>{target.name}</span>
                    <Plus className="ml-auto h-3.5 w-3.5 text-muted-foreground" />
                  </button>
                )
              })}
            </div>
          </div>
        ) : null}
      </div>
    </FlatSetupSection>
  )
}

function DsnSection({
  project,
  selectedTarget,
  onSelectedTargetChange,
}: {
  readonly project: Project
  readonly selectedTarget: string | null
  readonly onSelectedTargetChange: (target: string | null) => void
}) {
  const [copiedTarget, setCopiedTarget] = useState<string | null>(null)

  const handleCopyDsn = async (dsn: string, target: string) => {
    await navigator.clipboard.writeText(dsn)
    setCopiedTarget(target)
    setTimeout(() => setCopiedTarget(null), COPY_RESET_MS)
  }

  if (project.keys.length > 1) {
    return (
      <FlatSetupSection
        title="Project DSNs"
        description="Use the matching DSN for each target platform in your SDK."
      >
        <Tabs value={selectedTarget ?? 'default'} onValueChange={onSelectedTargetChange}>
          <TabsList className="mb-4 flex h-auto flex-wrap gap-1">
            {project.keys.map((key) => {
              const target = key.platformTarget ?? 'default'
              return (
                <TabsTrigger key={target} value={target}>
                  {key.platformTarget ? getTargetLabel(key.platformTarget) : 'Default'}
                </TabsTrigger>
              )
            })}
          </TabsList>
          {project.keys.map((key) => {
            const target = key.platformTarget ?? 'default'
            return (
              <TabsContent key={target} value={target}>
                <div className="flex items-center gap-2">
                  <code className="min-w-0 flex-1 break-all rounded-lg bg-muted px-3 py-2.5 text-sm">
                    {key.dsn}
                  </code>
                  <Button
                    type="button"
                    variant="outline"
                    size="icon"
                    onClick={() => handleCopyDsn(key.dsn, target)}
                    aria-label="Copy DSN"
                  >
                    {copiedTarget === target ? <Check className="h-4 w-4" /> : <Copy className="h-4 w-4" />}
                  </Button>
                </div>
              </TabsContent>
            )
          })}
        </Tabs>
      </FlatSetupSection>
    )
  }

  return (
    <FlatSetupSection title="Project DSN" description="Use this DSN in a Sentry-compatible SDK.">
      <div className="flex items-center gap-2">
        <code className="min-w-0 flex-1 break-all rounded-lg bg-muted px-3 py-2.5 text-sm">
          {project.dsn}
        </code>
        <Button
          type="button"
          variant="outline"
          size="icon"
          onClick={() => handleCopyDsn(project.dsn, 'default')}
          aria-label="Copy DSN"
        >
          {copiedTarget === 'default' ? <Check className="h-4 w-4" /> : <Copy className="h-4 w-4" />}
        </Button>
      </div>
    </FlatSetupSection>
  )
}

function SentrySetupPanel({
  project,
  selectedTarget,
  onSelectedTargetChange,
  docs,
}: {
  readonly project: Project
  readonly selectedTarget: string | null
  readonly onSelectedTargetChange: (target: string | null) => void
  readonly docs: PlatformSetupDocs | null
}) {
  if (!docs) {
    return (
      <div className="rounded-md bg-muted/30 p-4">
        <p className="text-sm text-muted-foreground">Unable to load setup documentation.</p>
      </div>
    )
  }

  return (
    <div className="flex flex-col gap-4">
      <TargetPlatformSection
        project={project}
        selectedTarget={selectedTarget}
        onSelectedTargetChange={onSelectedTargetChange}
      />
      <DsnSection
        project={project}
        selectedTarget={selectedTarget}
        onSelectedTargetChange={onSelectedTargetChange}
      />
      <div className="flex flex-col">
        {docs.steps.map((step, index) => (
          <FlatSetupSection
            key={`${step.title}-${index}`}
            title={(
              <span className="flex items-center gap-3">
                <span className="flex h-8 w-8 items-center justify-center rounded-md bg-primary text-sm text-white">
                  {index + 1}
                </span>
                <span>{step.title}</span>
              </span>
            )}
            description={step.description}
          >
            {step.code ? <CopyBlock code={step.code} language={step.language ?? 'text'} /> : null}
          </FlatSetupSection>
        ))}
      </div>
    </div>
  )
}

function KeyActionPanel({
  title,
  description,
  buttonLabel,
  isPending,
  createdKey,
  hasExistingKey,
  onCreate,
}: {
  readonly title: ReactNode
  readonly description: ReactNode
  readonly buttonLabel: string
  readonly isPending: boolean
  readonly createdKey: string | null
  readonly hasExistingKey: boolean
  readonly onCreate: () => void
}) {
  return (
    <FlatSetupSection
      title={(
        <span className="flex items-center gap-2">
          <KeyRound className="h-4 w-4" />
          {title}
        </span>
      )}
      description={description}
    >
      <div className="flex flex-col gap-3">
        {createdKey ? (
          <div className="rounded-md bg-muted/50 px-3 py-2">
            <p className="mb-2 text-xs font-medium text-muted-foreground">Copy this key now. It is shown once.</p>
            <code className="break-all text-sm">{createdKey}</code>
          </div>
        ) : null}
        {!createdKey && hasExistingKey ? (
          <p className="text-sm text-muted-foreground">
            A reusable key already exists. Existing keys cannot reveal the full secret again.
          </p>
        ) : null}
        <Button type="button" variant="outline" onClick={onCreate} disabled={isPending}>
          {isPending ? 'Creating...' : buttonLabel}
        </Button>
      </div>
    </FlatSetupSection>
  )
}

function OpenTelemetryPanel({
  createdKey,
  hasExistingKey,
  isPending,
  onCreateKey,
}: {
  readonly createdKey: string | null
  readonly hasExistingKey: boolean
  readonly isPending: boolean
  readonly onCreateKey: () => void
}) {
  const apiKey = keyPlaceholder(createdKey, 'YOUR_OTLP_API_KEY')

  return (
    <div className="flex flex-col gap-4">
      <KeyActionPanel
        title="OTLP API key"
        description="Use one organization-level key for OpenTelemetry logs, traces, and metrics."
        buttonLabel={hasExistingKey ? 'Create another OTLP key' : 'Create OTLP key'}
        isPending={isPending}
        createdKey={createdKey}
        hasExistingKey={hasExistingKey}
        onCreate={onCreateKey}
      />
      <FlatSetupSection
        title="Environment variables"
        description="Configure SDKs that support OTLP environment variables with the Moneat endpoints."
      >
        <CopyBlock code={otelEnvironmentSnippet(apiKey)} language="bash" />
      </FlatSetupSection>
      <FlatSetupSection
        title={(
          <span className="flex items-center gap-2">
            <ScrollText className="h-4 w-4" />
            Logs-only HTTP quickstart
          </span>
        )}
        description="For a quick log-only test, post an OTLP JSON log payload directly to the logs endpoint."
      >
        <CopyBlock code={httpLogsSnippet(apiKey)} language="bash" />
      </FlatSetupSection>
    </div>
  )
}

function FlatSetupSection({
  title,
  description,
  action,
  children,
}: {
  readonly title: ReactNode
  readonly description: ReactNode
  readonly action?: ReactNode
  readonly children: ReactNode
}) {
  return (
    <section className="border-t pt-6 first:border-t-0 first:pt-0">
      <div className="mb-4 flex flex-col gap-3 md:flex-row md:items-start md:justify-between">
        <div>
          <h3 className="text-base font-semibold leading-none tracking-tight">{title}</h3>
          <p className="mt-2 text-sm text-muted-foreground">{description}</p>
        </div>
        {action}
      </div>
      {children}
    </section>
  )
}

function DatadogAgentPanel({
  createdKey,
  hasExistingKey,
  isPending,
  onCreateKey,
}: {
  readonly createdKey: string | null
  readonly hasExistingKey: boolean
  readonly isPending: boolean
  readonly onCreateKey: () => void
}) {
  const apiKey = keyPlaceholder(createdKey, 'YOUR_AGENT_API_KEY')

  return (
    <div className="flex flex-col gap-4">
      <FlatSetupSection
        title="Agent quickstart"
        description="Create a key, save the config, run the agent, then confirm host telemetry arrives."
      >
        <ol className="grid gap-3 md:grid-cols-2">
          {datadogSetupSteps.map((step, index) => (
            <li key={step.title} className="flex gap-3 rounded-md bg-muted/30 p-3">
              <span
                className="flex h-7 w-7 shrink-0 items-center justify-center rounded-md bg-primary text-xs text-white"
              >
                {index + 1}
              </span>
              <span>
                <span className="block text-sm font-medium">{step.title}</span>
                <span className="mt-1 block text-xs leading-5 text-muted-foreground">{step.description}</span>
              </span>
            </li>
          ))}
        </ol>
      </FlatSetupSection>
      <FlatSetupSection
        title={(
          <span className="flex items-center gap-2">
            <KeyRound className="h-4 w-4" />
            Agent key
          </span>
        )}
        description="Use this key in datadog.yaml and DD_API_KEY. Existing keys cannot reveal the secret again."
      >
        <div className="flex flex-col gap-3">
          {createdKey ? (
            <div className="rounded-md bg-muted/50 px-3 py-2">
              <p className="mb-2 text-xs font-medium text-muted-foreground">Copy this key now. It is shown once.</p>
              <code className="break-all text-sm">{createdKey}</code>
            </div>
          ) : null}
          {!createdKey && hasExistingKey ? (
            <p className="text-sm text-muted-foreground">
              A reusable key already exists. Existing keys cannot reveal the full secret again.
            </p>
          ) : null}
          <Button type="button" variant="outline" onClick={onCreateKey} disabled={isPending}>
            {isPending ? 'Creating...' : hasExistingKey ? 'Create another agent key' : 'Create agent key'}
          </Button>
        </div>
      </FlatSetupSection>
      <FlatSetupSection
        title="datadog.yaml"
        description={(
          <>
            Save this file on the host as <code>{DATADOG_AGENT_CONFIG_PATH}</code>. If the agent runs in Docker,
            that host file is mounted into the container at the same path.
          </>
        )}
      >
        <div className="mb-4 rounded-md bg-muted/30 px-3 py-2 text-sm">
          <span className="mr-2 font-medium">Config path</span>
          <code className="break-all">{DATADOG_AGENT_CONFIG_PATH}</code>
        </div>
        <CopyBlock code={datadogYaml(apiKey)} language="yaml" />
      </FlatSetupSection>
      <FlatSetupSection
        title="Run the agent"
        description={(
          <>
            Run this on each host after creating <code>{DATADOG_AGENT_CONFIG_PATH}</code>.
          </>
        )}
      >
        <CopyBlock code={datadogDockerCommand(apiKey)} language="bash" />
      </FlatSetupSection>
      <FlatSetupSection
        title="Advanced setup guides"
        description={(
          <>
            Use the full docs for integration-specific options, Kubernetes installs, and feature-specific checks.
          </>
        )}
      >
        <div className="grid gap-2 sm:grid-cols-2">
          {datadogDocsLinks.map((link) => (
            <a
              key={link.href}
              href={link.href}
              target="_blank"
              rel="noopener noreferrer"
              className="flex items-center justify-between rounded-md border px-3 py-2 text-sm hover:bg-muted"
            >
              {link.label}
              <ExternalLink className="h-3.5 w-3.5 text-muted-foreground" />
            </a>
          ))}
        </div>
      </FlatSetupSection>
    </div>
  )
}

export function ProjectTelemetrySetupHub({
  project,
  selectedSourcesParam,
}: ProjectTelemetrySetupHubProps) {
  const navigate = useNavigate({from: '/projects/$projectId'})
  const queryClient = useQueryClient()
  const {toast} = useToast()
  const sourceIdsFromSearch = useMemo(
    () => parseTelemetrySourceIds(selectedSourcesParam),
    [selectedSourcesParam]
  )
  const [storedSourceIds, setStoredSourceIds] = useState<TelemetrySourceId[]>(
    () => getInitialSourceIds(project.resourceId)
  )
  const selectedSourceIds = sourceIdsFromSearch.length > 0 ? sourceIdsFromSearch : storedSourceIds
  const [requestedActiveSourceId, setRequestedActiveSourceId] = useState<TelemetrySourceId>(selectedSourceIds[0])
  const activeSourceId = selectedSourceIds.includes(requestedActiveSourceId)
    ? requestedActiveSourceId
    : selectedSourceIds[0]
  const [selectedTarget, setSelectedTarget] = useState<string | null>(
    () => project.keys[0]?.platformTarget ?? 'default'
  )
  const [copiedResourceId, setCopiedResourceId] = useState(false)
  const [createdOtlpKey, setCreatedOtlpKey] = useState<string | null>(null)
  const [createdAgentKey, setCreatedAgentKey] = useState<string | null>(null)

  const copyResourceId = async () => {
    try {
      await navigator.clipboard.writeText(project.resourceId)
      setCopiedResourceId(true)
      setTimeout(() => setCopiedResourceId(false), COPY_RESET_MS)
    } catch (error) {
      console.error('Failed to copy project resource ID:', error)
      toast({
        title: 'Could not copy resource ID',
        description: 'Check browser clipboard permissions and try again.',
        variant: 'destructive',
      })
    }
  }

  useEffect(() => {
    storeTelemetrySourceIdsForProject(project.resourceId, selectedSourceIds)
  }, [project.resourceId, selectedSourceIds])

  const {data: sdkVersionsResponse} = useQuery({
    queryKey: ['sdk-versions'],
    queryFn: () => api.getSdkVersions(),
    staleTime: 30 * 60 * 1000,
  })
  const {data: currentUser} = useQuery({
    queryKey: ['currentUser'],
    queryFn: () => api.getCurrentUser(),
    staleTime: 30 * 60 * 1000,
  })
  const {data: projectStats} = useQuery({
    queryKey: ['projectStats', project.resourceId, '24h'],
    queryFn: () => api.getProjectStats(project.resourceId, '24h'),
  })
  const {data: otlpKeyResponse} = useQuery({
    queryKey: ['otlpApiKeys'],
    queryFn: () => api.getOtlpApiKeys(),
    enabled: api.isAuthenticated(),
  })
  const {data: agentKeyResponse} = useQuery({
    queryKey: ['agentApiKeys'],
    queryFn: () => api.getAgentApiKeys(),
    enabled: api.isAuthenticated(),
  })
  const {data: monitorHosts = []} = useQuery({
    queryKey: ['monitor-hosts'],
    queryFn: () => api.getMonitorHosts(),
    enabled: api.isAuthenticated(),
  })

  const setupOptions = {
    orgSlug: currentUser?.organizationSlug,
    projectSlug: project.slug,
    backendUrl: backendBaseUrl,
  }
  const sentryDocs = getDocsForTarget(
    project,
    selectedTarget,
    sdkVersionsResponse?.versions,
    setupOptions
  )
  const platformInfo = getPlatformInfo(project.framework)
  const PlatformIcon = platformInfo?.icon
  const sourceStatusInput = useMemo(() => ({
    projectEventCount: projectStats?.totalEvents,
    otlpKeys: otlpKeyResponse?.keys,
    agentKeys: agentKeyResponse?.keys,
    monitorHosts,
  }), [agentKeyResponse?.keys, monitorHosts, otlpKeyResponse?.keys, projectStats?.totalEvents])
  const hasExistingOtlpKey = (otlpKeyResponse?.keys.length ?? 0) > 0
  const hasExistingAgentKey = (agentKeyResponse?.keys.length ?? 0) > 0

  const createOtlpKeyMutation = useMutation({
    mutationFn: () => api.createOtlpApiKey(`${project.name} OTLP`),
    onSuccess: (response) => {
      setCreatedOtlpKey(response.key)
      queryClient.invalidateQueries({queryKey: ['otlpApiKeys']})
      toast({title: 'OTLP API key created', description: 'Copy the key now. It will not be shown again.'})
    },
  })

  const createAgentKeyMutation = useMutation({
    mutationFn: () => api.createAgentApiKey(`${project.name} Agent`),
    onSuccess: (response) => {
      setCreatedAgentKey(response.key)
      queryClient.invalidateQueries({queryKey: ['agentApiKeys']})
      toast({title: 'Agent key created', description: 'Copy the key now. It will not be shown again.'})
    },
  })

  const updateSelectedSources = (sourceIds: TelemetrySourceId[]) => {
    setStoredSourceIds(sourceIds)
    storeTelemetrySourceIdsForProject(project.resourceId, sourceIds)
    navigate({
      search: (previous) => ({
        ...previous,
        sources: serializeTelemetrySourceIds(sourceIds),
      }),
      replace: true,
    })
  }

  const handleSourceToggle = (sourceId: TelemetrySourceId) => {
    updateSelectedSources(toggleTelemetrySourceId(selectedSourceIds, sourceId))
  }

  const activeSource = getTelemetrySource(activeSourceId)
  const activeStatus = getTelemetrySourceStatus(activeSourceId, sourceStatusInput)
  const ActiveIcon = sourceIcons[activeSourceId]
  const activeSourceDocsHref = activeSourceId === 'datadog-agent' ? DATADOG_AGENT_SETUP_DOCS_HREF : null

  return (
    <div>
      <div className="mx-auto flex max-w-5xl flex-col gap-6 p-6">
        <div className="flex flex-col gap-4 md:flex-row md:items-start md:justify-between">
          <div>
            <div className="mb-2 flex flex-wrap items-center gap-2">
              {platformInfo && PlatformIcon ? (
                <Badge className="flex items-center gap-1.5 border-0" style={{backgroundColor: platformInfo.color}}>
                  <PlatformIcon className="h-4 w-4" />
                  {platformInfo.name}
                </Badge>
              ) : null}
              <Badge variant={statusVariant(activeStatus.state)}>{activeStatus.label}</Badge>
            </div>
            <h1 className="text-3xl font-bold">{project.name}</h1>
            <div className="mt-1 flex max-w-full items-center gap-2 text-xs text-muted-foreground">
              <span>Project ID</span>
              <code className="max-w-[min(28rem,70vw)] truncate rounded bg-muted px-1.5 py-0.5 font-mono text-foreground">
                {project.resourceId}
              </code>
              <Button
                type="button"
                variant="ghost"
                size="icon"
                className="h-6 w-6 shrink-0"
                aria-label="Copy project ID"
                onClick={copyResourceId}
              >
                {copiedResourceId ? <Check className="h-3.5 w-3.5" /> : <Copy className="h-3.5 w-3.5" />}
              </Button>
            </div>
          </div>
          <Button asChild variant="outline" size="icon" aria-label="Project settings">
            <Link to="/projects/$projectId/settings" params={{projectId: project.resourceId}}>
              <Settings className="h-4 w-4" />
            </Link>
          </Button>
        </div>

        <div className="grid gap-3 md:grid-cols-2">
          {TELEMETRY_SOURCES.map((source) => {
            const Icon = sourceIcons[source.id]
            const isSelected = selectedSourceIds.includes(source.id)
            const status = getTelemetrySourceStatus(source.id, sourceStatusInput)
            return (
              <button
                key={source.id}
                type="button"
                onClick={() => handleSourceToggle(source.id)}
                className={cn(
                  'flex min-h-28 items-start gap-3 rounded-lg p-3 text-left transition-colors',
                  isSelected ? 'bg-muted/70' : 'bg-muted/30 hover:bg-muted/60'
                )}
                aria-pressed={isSelected}
              >
                <span className="flex h-9 w-9 shrink-0 items-center justify-center rounded-md bg-muted">
                  <Icon className="h-4 w-4" />
                </span>
                <span className="min-w-0 flex-1">
                  <span className="flex items-center justify-between gap-2">
                    <span className="text-sm font-medium">{source.shortLabel}</span>
                    <Badge variant={statusVariant(status.state)}>{status.label}</Badge>
                  </span>
                  <span className="mt-1 block text-xs leading-5 text-muted-foreground">{source.description}</span>
                </span>
              </button>
            )
          })}
        </div>

        <Tabs value={activeSourceId} onValueChange={(value) => setRequestedActiveSourceId(value as TelemetrySourceId)}>
          <TabsList className="flex h-auto flex-wrap gap-1">
            {selectedSourceIds.map((sourceId) => {
              const source = getTelemetrySource(sourceId)
              return (
                <TabsTrigger key={sourceId} value={sourceId}>
                  {source.shortLabel}
                </TabsTrigger>
              )
            })}
          </TabsList>

          <div className="mt-4 rounded-lg border bg-card p-4">
            <div className="mb-4 flex flex-col gap-3 md:flex-row md:items-start md:justify-between">
              <div className="flex items-start gap-3">
                <span className="flex h-10 w-10 shrink-0 items-center justify-center rounded-md bg-muted">
                  <ActiveIcon className="h-5 w-5" />
                </span>
                <div>
                  <h2 className="text-xl font-semibold">{activeSource.setupTitle}</h2>
                  <p className="mt-1 text-sm text-muted-foreground">{activeSource.setupDescription}</p>
                  <p className="mt-1 text-xs text-muted-foreground">{activeStatus.detail}</p>
                </div>
              </div>
              <div className="flex shrink-0 flex-col gap-2 sm:flex-row md:justify-end">
                {activeSourceDocsHref ? (
                  <Button asChild variant="outline" size="sm">
                    <a href={activeSourceDocsHref} target="_blank" rel="noopener noreferrer">
                      Full instructions
                      <ExternalLink className="ml-2 h-3.5 w-3.5" />
                    </a>
                  </Button>
                ) : null}
                <Button asChild variant="outline" size="sm">
                  <a href={activeSource.productHref}>
                    {activeSource.productLabel}
                    <ExternalLink className="ml-2 h-3.5 w-3.5" />
                  </a>
                </Button>
              </div>
            </div>

            <TabsContent value="sentry-sdk" className="mt-0">
              <SentrySetupPanel
                project={project}
                selectedTarget={selectedTarget}
                onSelectedTargetChange={setSelectedTarget}
                docs={sentryDocs}
              />
            </TabsContent>
            <TabsContent value="opentelemetry" className="mt-0">
              <OpenTelemetryPanel
                createdKey={createdOtlpKey}
                hasExistingKey={hasExistingOtlpKey}
                isPending={createOtlpKeyMutation.isPending}
                onCreateKey={() => createOtlpKeyMutation.mutate()}
              />
            </TabsContent>
            <TabsContent value="datadog-agent" className="mt-0">
              <DatadogAgentPanel
                createdKey={createdAgentKey}
                hasExistingKey={hasExistingAgentKey}
                isPending={createAgentKeyMutation.isPending}
                onCreateKey={() => createAgentKeyMutation.mutate()}
              />
            </TabsContent>
          </div>
        </Tabs>
      </div>
    </div>
  )
}
