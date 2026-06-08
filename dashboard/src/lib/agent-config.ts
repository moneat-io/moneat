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

// Single source of truth for the infrastructure agent configuration the dashboard
// generates. The Configuration page's guided builder and the host inventory's
// "Add host" dialog both render artifacts from these functions so the two surfaces
// never drift. The agent is Datadog-compatible: a stock agent pointed at Moneat's
// intake, the same way the SDK path is Sentry-compatible.

import {backendBaseUrl} from '@/lib/backend-url'

// ─── Domain ──────────────────────────────────────────────────────────────────

export type AgentPlatform = 'host' | 'docker' | 'compose' | 'kubernetes'

export type AgentCapabilityId =
  | 'infraMetrics'
  | 'containers'
  | 'processes'
  | 'logs'
  | 'apm'
  | 'profiling'
  | 'dbm'
  | 'netDevices'
  | 'sbom'

export type AgentCapabilities = Record<AgentCapabilityId, boolean>

export interface AgentPlatformMeta {
  readonly id: AgentPlatform
  readonly label: string
  readonly hint: string
  /** Label for the generated config artifact (file the user saves). */
  readonly configLabel: string
  /** Label for the generated install/run artifact. */
  readonly installLabel: string
}

export interface AgentCapabilityMeta {
  readonly id: AgentCapabilityId
  readonly label: string
  readonly hint: string
  /** Always-on capabilities (host vitals) cannot be toggled off. */
  readonly locked?: boolean
}

export interface AgentArtifact {
  readonly id: string
  readonly label: string
  readonly language: string
  readonly code: string
  /** Where the artifact is saved / what the command does. */
  readonly note?: string
  /** Canonical path for config files, rendered as a hint chip. */
  readonly path?: string
}

// ─── Catalog metadata (icons live in the UI layer) ───────────────────────────

export const AGENT_PLATFORMS: readonly AgentPlatformMeta[] = [
  {
    id: 'host',
    label: 'Linux host',
    hint: 'Package install on a VM or bare-metal host',
    configLabel: 'datadog.yaml',
    installLabel: 'Install',
  },
  {
    id: 'docker',
    label: 'Docker',
    hint: 'A single agent container via docker run',
    configLabel: 'datadog.yaml',
    installLabel: 'Run',
  },
  {
    id: 'compose',
    label: 'Docker Compose',
    hint: 'Managed in a docker-compose.yml service',
    configLabel: 'datadog.yaml',
    installLabel: 'Compose',
  },
  {
    id: 'kubernetes',
    label: 'Kubernetes',
    hint: 'Helm chart deployed to a cluster',
    configLabel: 'values.yaml',
    installLabel: 'Helm',
  },
]

export const AGENT_CAPABILITIES: readonly AgentCapabilityMeta[] = [
  {id: 'infraMetrics', label: 'Infrastructure metrics', hint: 'Host CPU, memory, disk, and network', locked: true},
  {id: 'containers', label: 'Containers', hint: 'Docker & containerd metrics and lifecycle'},
  {id: 'processes', label: 'Live processes', hint: 'Process tree and per-process resource usage'},
  {id: 'logs', label: 'Logs', hint: 'Collect logs from files and containers'},
  {id: 'apm', label: 'APM traces', hint: 'Distributed tracing intake'},
  {id: 'profiling', label: 'Continuous profiling', hint: 'Always-on production profiler'},
  {id: 'dbm', label: 'Database monitoring', hint: 'Query metrics, samples, and activity'},
  {id: 'netDevices', label: 'Network devices', hint: 'SNMP metrics and traps'},
  {id: 'sbom', label: 'Software inventory', hint: 'Container image packages and CVEs (SBOM)'},
]

export const DEFAULT_AGENT_CAPABILITIES: AgentCapabilities = {
  infraMetrics: true,
  containers: true,
  processes: true,
  logs: false,
  apm: true,
  profiling: false,
  dbm: false,
  netDevices: false,
  sbom: false,
}

export const PLACEHOLDER_AGENT_KEY = ['YOUR', 'AGENT', 'KEY'].join('_')

// ─── Intake endpoint helpers ─────────────────────────────────────────────────

const INGEST_URL = `${backendBaseUrl}/dd`
const API_KEY_FIELD = ['api', 'key'].join('_')
const API_KEY_ENV_VAR = ['DD', 'API', 'KEY'].join('_')
const HELM_API_KEY_FIELD = ['api', 'Key'].join('')

interface LogsEndpoint {
  readonly address: string
  readonly noSsl: boolean
}

function logsEndpoint(): LogsEndpoint {
  try {
    const parsed = new URL(INGEST_URL)
    const port = parsed.port || (parsed.protocol === 'http:' ? '80' : '443')
    return {address: `${parsed.hostname}:${port}`, noSsl: parsed.protocol === 'http:'}
  } catch {
    return {address: stripUrlParts(INGEST_URL), noSsl: false}
  }
}

function stripUrlParts(value: string): string {
  let withoutProtocol = value
  if (value.startsWith('https://')) {
    withoutProtocol = value.slice('https://'.length)
  } else if (value.startsWith('http://')) {
    withoutProtocol = value.slice('http://'.length)
  }
  const pathStart = withoutProtocol.indexOf('/')
  if (pathStart === -1) return withoutProtocol
  return withoutProtocol.slice(0, pathStart)
}

function forwarderEndpoint(): string {
  try {
    const parsed = new URL(backendBaseUrl)
    const port = parsed.port ? `:${parsed.port}` : ''
    return parsed.protocol === 'https:' ? `${parsed.hostname}${port}` : backendBaseUrl
  } catch {
    return backendBaseUrl
  }
}

function profileEndpoint(): string {
  return `${backendBaseUrl}/api/v2/profile`
}

function telemetryEndpoint(): string {
  return `${backendBaseUrl}/dd/telemetry/proxy`
}

function resolveKey(apiKey?: string | null): string {
  const trimmed = apiKey?.trim()
  if (trimmed) return trimmed
  return PLACEHOLDER_AGENT_KEY
}

// ─── datadog.yaml (capability-gated) ─────────────────────────────────────────

interface YamlOptions {
  /** Inline intake credentials (host installs read the file directly). */
  readonly includeIntake?: boolean
}

export function buildAgentYaml(caps: AgentCapabilities, apiKey?: string | null, options: YamlOptions = {}): string {
  const key = resolveKey(apiKey)
  const logs = logsEndpoint()
  const forwarder = forwarderEndpoint()
  const blocks: string[] = []

  const header = ['# Datadog-compatible agent configuration for Moneat']
  if (options.includeIntake) {
    header.push(`${API_KEY_FIELD}: ${key}`, `dd_url: ${INGEST_URL}`)
  }
  header.push('docker_query_timeout: 15')
  blocks.push(header.join('\n'))

  if (caps.apm || caps.profiling) {
    const apm = ['apm_config:']
    if (caps.apm) apm.push(`  apm_dd_url: ${INGEST_URL}`)
    if (caps.profiling) apm.push(`  profiling_dd_url: ${profileEndpoint()}`)
    apm.push('  telemetry:', `    dd_url: ${telemetryEndpoint()}`)
    blocks.push(apm.join('\n'))
  }

  if (caps.processes) {
    blocks.push(['process_config:', `  process_dd_url: ${INGEST_URL}`].join('\n'))
  }

  if (caps.logs) {
    blocks.push(
      ['logs_config:', `  logs_dd_url: ${logs.address}`, `  logs_no_ssl: ${logs.noSsl}`].join('\n')
    )
  }

  // EPForwarder tracks — these cannot be redirected with environment variables.
  if (caps.containers) {
    blocks.push(
      [
        'container_lifecycle:',
        `  dd_url: ${forwarder}`,
        'container_image:',
        `  dd_url: ${forwarder}`,
      ].join('\n')
    )
  }
  if (caps.sbom) {
    blocks.push(['sbom:', `  dd_url: ${forwarder}`].join('\n'))
  }
  if (caps.netDevices) {
    blocks.push(
      [
        'network_devices:',
        '  metadata:',
        `    dd_url: ${forwarder}`,
        '  snmp_traps:',
        '    forwarder:',
        `      dd_url: ${forwarder}`,
        '  netflow:',
        '    forwarder:',
        `      dd_url: ${forwarder}`,
      ].join('\n')
    )
  }
  if (caps.dbm) {
    blocks.push(
      [
        'database_monitoring:',
        '  metrics:',
        `    dd_url: ${forwarder}`,
        '  samples:',
        `    dd_url: ${forwarder}`,
        '  activity:',
        `    dd_url: ${forwarder}`,
      ].join('\n')
    )
  }

  return blocks.join('\n\n')
}

// ─── docker run / docker compose ─────────────────────────────────────────────

function dockerEnvLines(apiKey: string, caps: AgentCapabilities): string[] {
  const logs = logsEndpoint()
  const env: string[] = [`${API_KEY_ENV_VAR}=${apiKey}`, `DD_DD_URL=${INGEST_URL}`]
  if (caps.apm) env.push('DD_APM_ENABLED=true', `DD_APM_DD_URL=${INGEST_URL}`)
  if (caps.logs) {
    env.push(
      'DD_LOGS_ENABLED=true',
      `DD_LOGS_CONFIG_LOGS_DD_URL=${logs.address}`,
      `DD_LOGS_CONFIG_LOGS_NO_SSL=${logs.noSsl}`,
      'DD_LOGS_CONFIG_CONTAINER_COLLECT_ALL=true'
    )
  }
  if (caps.processes) env.push('DD_PROCESS_AGENT_ENABLED=true', `DD_PROCESS_CONFIG_PROCESS_DD_URL=${INGEST_URL}`)
  return env
}

function dockerVolumeLines(caps: AgentCapabilities): string[] {
  const volumes = ['/etc/datadog-agent/datadog.yaml:/etc/datadog-agent/datadog.yaml:ro']
  if (caps.containers) volumes.push('/var/run/docker.sock:/var/run/docker.sock:ro')
  if (caps.logs) volumes.push('/var/lib/docker/containers:/var/lib/docker/containers:ro')
  volumes.push('/proc/:/host/proc/:ro', '/sys/:/host/sys/:ro')
  return volumes
}

export function buildDockerRun(apiKey: string | null | undefined, caps: AgentCapabilities): string {
  const key = resolveKey(apiKey)
  const envs = dockerEnvLines(key, caps).map((line) => `  -e ${line} \\`).join('\n')
  const volumes = dockerVolumeLines(caps).map((line) => `  -v ${line} \\`).join('\n')
  return String.raw`docker run -d \
  --name dd-agent \
  --restart always \
  --network host \
${envs}
${volumes}
  gcr.io/datadoghq/agent:7`
}

export function buildDockerCompose(apiKey: string | null | undefined, caps: AgentCapabilities): string {
  const key = resolveKey(apiKey)
  const envs = dockerEnvLines(key, caps).map((line) => `      - ${line}`).join('\n')
  const volumes = dockerVolumeLines(caps).map((line) => `      - ${line}`).join('\n')
  return `cat > docker-compose.yml <<'EOF'
services:
  dd-agent:
    image: gcr.io/datadoghq/agent:7
    container_name: dd-agent
    restart: always
    network_mode: host
    environment:
${envs}
    volumes:
${volumes}
EOF

docker compose up -d`
}

// ─── Linux host package install ──────────────────────────────────────────────

export function buildHostInstall(): string {
  return String.raw`# 1. Install the Datadog-compatible agent (v7)
# Requires the Datadog agent package repository to be configured.
if command -v apt-get >/dev/null 2>&1; then
  sudo apt-get update
  sudo apt-get install -y datadog-agent
elif command -v dnf >/dev/null 2>&1; then
  sudo dnf install -y datadog-agent
elif command -v yum >/dev/null 2>&1; then
  sudo yum install -y datadog-agent
else
  echo "Install datadog-agent v7 with your OS package manager, then continue."
  exit 1
fi

# 2. Save the config from the datadog.yaml tab to:
#    /etc/datadog-agent/datadog.yaml

# 3. Start the agent
sudo systemctl restart datadog-agent`
}

// ─── Kubernetes (Helm) ───────────────────────────────────────────────────────

export function buildHelmValues(caps: AgentCapabilities, apiKey?: string | null): string {
  const key = resolveKey(apiKey)
  const logs = logsEndpoint()
  const yes = (value: boolean) => (value ? 'true' : 'false')

  const env: string[] = [`    - name: DD_DD_URL`, `      value: "${INGEST_URL}"`]
  if (caps.apm) env.push(`    - name: DD_APM_DD_URL`, `      value: "${INGEST_URL}"`)
  if (caps.processes) env.push(`    - name: DD_PROCESS_CONFIG_PROCESS_DD_URL`, `      value: "${INGEST_URL}"`)
  if (caps.logs) {
    env.push(
      `    - name: DD_LOGS_CONFIG_LOGS_DD_URL`,
      `      value: "${logs.address}"`,
      `    - name: DD_LOGS_CONFIG_LOGS_NO_SSL`,
      `      value: "${yes(logs.noSsl)}"`
    )
  }

  return `# Helm values for the Datadog-compatible agent, pointed at Moneat
datadog:
  ${HELM_API_KEY_FIELD}: ${key}
  dd_url: ${INGEST_URL}
  logs:
    enabled: ${yes(caps.logs)}
    containerCollectAll: ${yes(caps.logs)}
  apm:
    portEnabled: ${yes(caps.apm)}
  processAgent:
    enabled: ${yes(caps.processes)}
    processCollection: ${yes(caps.processes)}
  sbom:
    containerImage:
      enabled: ${yes(caps.sbom)}
  env:
${env.join('\n')}
agents:
  enabled: true
clusterAgent:
  enabled: ${yes(caps.containers || caps.dbm)}`
}

export function buildHelmInstall(): string {
  return String.raw`helm repo add datadog https://helm.datadoghq.com
helm repo update

helm install moneat-agent \
  -f values.yaml \
  datadog/datadog`
}

// ─── Artifact assembly (config + install per platform) ───────────────────────

export interface AgentArtifactSet {
  readonly config: AgentArtifact
  readonly install: AgentArtifact
}

export function buildAgentArtifacts(
  platform: AgentPlatform,
  caps: AgentCapabilities,
  apiKey?: string | null
): AgentArtifactSet {
  switch (platform) {
    case 'host':
      return {
        config: {
          id: 'config',
          label: 'datadog.yaml',
          language: 'yaml',
          code: buildAgentYaml(caps, apiKey, {includeIntake: true}),
          note: 'Save on the host — the agent reads this file directly.',
          path: '/etc/datadog-agent/datadog.yaml',
        },
        install: {
          id: 'install',
          label: 'Install',
          language: 'bash',
          code: buildHostInstall(),
          note: 'Run on each host to install and start the agent.',
        },
      }
    case 'compose':
      return {
        config: {
          id: 'config',
          label: 'datadog.yaml',
          language: 'yaml',
          code: buildAgentYaml(caps, apiKey),
          note: 'Save next to your compose file; it is mounted into the container.',
          path: '/etc/datadog-agent/datadog.yaml',
        },
        install: {
          id: 'install',
          label: 'Compose',
          language: 'bash',
          code: buildDockerCompose(apiKey, caps),
          note: 'Writes docker-compose.yml and starts the agent.',
        },
      }
    case 'kubernetes':
      return {
        config: {
          id: 'config',
          label: 'values.yaml',
          language: 'yaml',
          code: buildHelmValues(caps, apiKey),
          note: 'Helm values that redirect agent intake to Moneat.',
          path: 'values.yaml',
        },
        install: {
          id: 'install',
          label: 'Helm',
          language: 'bash',
          code: buildHelmInstall(),
          note: 'Install the chart with the values from the previous tab.',
        },
      }
    case 'docker':
    default:
      return {
        config: {
          id: 'config',
          label: 'datadog.yaml',
          language: 'yaml',
          code: buildAgentYaml(caps, apiKey),
          note: 'Save on the host; it is mounted into the agent container.',
          path: '/etc/datadog-agent/datadog.yaml',
        },
        install: {
          id: 'install',
          label: 'Run',
          language: 'bash',
          code: buildDockerRun(apiKey, caps),
          note: 'Starts the agent container with the selected capabilities.',
        },
      }
  }
}
