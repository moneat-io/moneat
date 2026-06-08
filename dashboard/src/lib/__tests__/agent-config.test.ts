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

import {afterEach, describe, expect, it, vi} from 'vitest'
import {
  buildAgentArtifacts,
  buildAgentYaml,
  buildDockerCompose,
  buildDockerRun,
  buildHelmValues,
  buildHostInstall,
  DEFAULT_AGENT_CAPABILITIES,
  PLACEHOLDER_AGENT_KEY,
  type AgentCapabilities,
} from '@/lib/agent-config'

const ALL_ON: AgentCapabilities = {
  infraMetrics: true,
  containers: true,
  processes: true,
  logs: true,
  apm: true,
  profiling: true,
  dbm: true,
  netDevices: true,
  sbom: true,
}

const ONLY_INFRA: AgentCapabilities = {
  infraMetrics: true,
  containers: false,
  processes: false,
  logs: false,
  apm: false,
  profiling: false,
  dbm: false,
  netDevices: false,
  sbom: false,
}

describe('buildAgentYaml', () => {
  it('gates every capability block on its toggle', () => {
    const yaml = buildAgentYaml(ALL_ON, 'key-123')
    expect(yaml).toContain('apm_config:')
    expect(yaml).toContain('process_config:')
    expect(yaml).toContain('logs_config:')
    expect(yaml).toContain('container_lifecycle:')
    expect(yaml).toContain('sbom:')
    expect(yaml).toContain('network_devices:')
    expect(yaml).toContain('database_monitoring:')
  })

  it('emits only the header when nothing but infra metrics is enabled', () => {
    const yaml = buildAgentYaml(ONLY_INFRA, 'key-123')
    expect(yaml).not.toContain('logs_config:')
    expect(yaml).not.toContain('apm_config:')
    expect(yaml).not.toContain('database_monitoring:')
    expect(yaml).toContain('docker_query_timeout: 15')
  })

  it('inlines the intake (api_key + dd_url) only when requested', () => {
    expect(buildAgentYaml(ONLY_INFRA, 'secret-key', {includeIntake: true})).toContain('api_key: secret-key')
    expect(buildAgentYaml(ONLY_INFRA, 'secret-key')).not.toContain('api_key:')
  })

  it('falls back to the placeholder key', () => {
    expect(buildAgentYaml(ONLY_INFRA, null, {includeIntake: true})).toContain(PLACEHOLDER_AGENT_KEY)
  })
})

describe('buildDockerRun / buildDockerCompose', () => {
  it('adds env + volumes per enabled capability', () => {
    const run = buildDockerRun('key-abc', ALL_ON)
    expect(run).toContain('docker run -d')
    expect(run).toContain('DD_API_KEY=key-abc')
    expect(run).toContain('DD_APM_ENABLED=true')
    expect(run).toContain('DD_LOGS_ENABLED=true')
    expect(run).toContain('DD_PROCESS_AGENT_ENABLED=true')
    expect(run).toContain('/var/run/docker.sock')
    expect(run).toContain('/var/lib/docker/containers')
  })

  it('omits disabled capabilities', () => {
    const run = buildDockerRun('key-abc', ONLY_INFRA)
    expect(run).not.toContain('DD_APM_ENABLED')
    expect(run).not.toContain('DD_LOGS_ENABLED')
    expect(run).not.toContain('/var/run/docker.sock')
  })

  it('produces a compose service that starts the agent', () => {
    const compose = buildDockerCompose('key-abc', DEFAULT_AGENT_CAPABILITIES)
    expect(compose).toContain('docker compose up -d')
    expect(compose).toContain('image: gcr.io/datadoghq/agent:7')
  })
})

describe('buildHelmValues / buildHostInstall', () => {
  it('reflects toggles in helm values', () => {
    const values = buildHelmValues(ALL_ON, 'key-helm')
    expect(values).toContain('apiKey: key-helm')
    expect(values).toContain('logs:')
    expect(values).toContain('enabled: true')
    expect(values).toContain('portEnabled: true')
  })

  it('embeds the key in the host install script', () => {
    const script = buildHostInstall('key-host')

    expect(script).toContain('DD_API_KEY="key-host"')
    expect(script).toContain('install_script_agent7.sh')
    expect(script).not.toContain('bash -c "$(curl')
  })
})

describe('backend URL fallback handling', () => {
  afterEach(() => {
    vi.unstubAllEnvs()
    vi.resetModules()
  })

  it('keeps generating artifacts when the backend URL is not parseable', async () => {
    vi.resetModules()
    vi.stubEnv('VITE_BACKEND_URL', '::::')

    const {buildAgentYaml: buildYamlWithEnv} = await import('@/lib/agent-config')
    const caps: AgentCapabilities = {
      ...ONLY_INFRA,
      containers: true,
      logs: true,
    }

    const yaml = buildYamlWithEnv(caps, 'key-url')

    expect(yaml).toContain('logs_dd_url: ::::')
    expect(yaml).toContain('container_lifecycle:')
    expect(yaml).toContain('dd_url: ::::')
  })
})

describe('buildAgentArtifacts', () => {
  it('returns the right config + install artifacts per platform', () => {
    const host = buildAgentArtifacts('host', DEFAULT_AGENT_CAPABILITIES, 'k')
    expect(host.config.label).toBe('datadog.yaml')
    expect(host.config.code).toContain('api_key: k')
    expect(host.install.code).toContain('install_script_agent7.sh')

    const docker = buildAgentArtifacts('docker', DEFAULT_AGENT_CAPABILITIES, 'k')
    expect(docker.install.label).toBe('Run')
    expect(docker.install.code).toContain('docker run -d')

    const compose = buildAgentArtifacts('compose', DEFAULT_AGENT_CAPABILITIES, 'k')
    expect(compose.install.label).toBe('Compose')
    expect(compose.install.code).toContain('docker compose up -d')

    const kubernetes = buildAgentArtifacts('kubernetes', DEFAULT_AGENT_CAPABILITIES, 'k')
    expect(kubernetes.config.label).toBe('values.yaml')
    expect(kubernetes.install.label).toBe('Helm')
    expect(kubernetes.install.code).toContain('helm install')
  })
})
