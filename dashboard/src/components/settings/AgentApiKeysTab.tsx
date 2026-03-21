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

import {Shield} from 'lucide-react'
import {api, type DdApiKey} from '@/lib/api'
import {backendBaseUrl} from '@/lib/backend-url'
import {Card, CardContent, CardDescription, CardHeader, CardTitle} from '@/components/ui/card'
import {ApiKeysTabBase} from './ApiKeysTabBase'

const ingestUrl = backendBaseUrl + '/dd'

export function AgentApiKeysTab() {
  return (
    <ApiKeysTabBase<DdApiKey>
      cardId="agent-api-keys"
      cardTitle="Agent Keys"
      cardDescription="Create API keys for compatible agent and SDK ingestion. Keys are shown in full only once when created."
      icon={Shield}
      emptyTitle="No agent keys yet"
      emptyDescription="Create a key to start ingesting data from compatible agents and SDKs."
      queryKey={['agentApiKeys']}
      queryFn={() => api.getAgentApiKeys()}
      queryEnabled={api.isAuthenticated()}
      createMutationFn={(name) => api.createAgentApiKey(name)}
      deleteMutationFn={(id) => api.deleteAgentApiKey(id)}
      createSuccessToast={{
        title: 'Agent key created',
        description: "Copy the key now—it won't be shown again.",
      }}
      revokeSuccessToast={{
        title: 'Key revoked',
        description: 'The agent API key has been revoked.',
      }}
      createDialogTitle="Create Agent Key"
      createDialogDescription='Give this key a name to identify it (e.g. "Production Agent"). The full key will be shown once and cannot be retrieved later.'
      inputId="agent-key-name"
      inputPlaceholder="e.g. Production Agent"
      createdDialogTitle="Agent Key Created"
      revokeDialogTitle="Revoke Agent Key"
      revokeDialogDescription={(name) =>
        `Are you sure you want to revoke "${name}"? Any agents using this key will no longer be able to send data.`
      }
      setupInstructions={
        <Card>
          <CardHeader>
            <CardTitle className="text-base">Agent Configuration</CardTitle>
            <CardDescription>
              Configure a compatible agent to send telemetry to Moneat.
            </CardDescription>
          </CardHeader>
          <CardContent className="space-y-4">
            <div>
              <p className="text-sm font-medium mb-1">datadog.yaml</p>
              <pre className="text-xs bg-muted px-3 py-2 rounded-md break-all whitespace-pre-wrap font-mono">
                {`api_key: <YOUR_API_KEY>
dd_url: ${ingestUrl}

apm_config:
  apm_dd_url: ${ingestUrl}
  profiling_dd_url: ${ingestUrl}/dd/profiling/v1/input

process_config:
  process_dd_url: ${ingestUrl}

logs_config:
  logs_dd_url: ${ingestUrl}

# Route event platform forwarder tracks to Moneat
# (these use only the host, path is appended automatically)
container_lifecycle:
  dd_url: ${backendBaseUrl}
container_image:
  dd_url: ${backendBaseUrl}
sbom:
  dd_url: ${backendBaseUrl}
synthetics:
  forwarder:
    dd_url: ${backendBaseUrl}
data_streams:
  forwarder:
    dd_url: ${backendBaseUrl}
event_management:
  forwarder:
    dd_url: ${backendBaseUrl}
database_monitoring:
  metrics:
    dd_url: ${backendBaseUrl}
  samples:
    dd_url: ${backendBaseUrl}
  activity:
    dd_url: ${backendBaseUrl}`}
              </pre>
            </div>
            <div>
              <p className="text-sm font-medium mb-1">Environment variables (basic)</p>
              <pre className="text-xs bg-muted px-3 py-2 rounded-md break-all whitespace-pre-wrap font-mono">
                {`DD_API_KEY=<YOUR_API_KEY>
DD_DD_URL=${ingestUrl}
DD_APM_DD_URL=${ingestUrl}
DD_APM_PROFILING_DD_URL=${ingestUrl}/dd/profiling/v1/input`}
              </pre>
              <p className="text-xs text-muted-foreground mt-2">
                Note: EPForwarder tracks (container images, SBOM, DBM, synthetics, etc.) require the
                full <code className="bg-muted px-1 rounded">datadog.yaml</code> above — they cannot
                be configured via environment variables alone.
              </p>
            </div>
            <p className="text-sm text-muted-foreground">
              Replace <code className="bg-muted px-1 rounded">&lt;YOUR_API_KEY&gt;</code> with the
              full key shown when you create a new key above.
            </p>
          </CardContent>
        </Card>
      }
    />
  )
}
