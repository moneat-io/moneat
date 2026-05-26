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
import {ApiKeysTabBase} from './ApiKeysTabBase'

export function AgentApiKeysTab() {
  return (
    <ApiKeysTabBase<DdApiKey>
      cardId="agent-api-keys"
      cardTitle="Datadog Agent Keys"
      cardDescription="Create API keys for Datadog-compatible agent ingestion."
      docsHref="/docs/datadog-agent/agent-setup"
      icon={Shield}
      emptyTitle="No Datadog Agent keys yet"
      emptyDescription="Create a key to start ingesting data from Datadog-compatible agents."
      queryKey={['agentApiKeys']}
      queryFn={() => api.getAgentApiKeys()}
      queryEnabled={api.isAuthenticated()}
      createMutationFn={(name) => api.createAgentApiKey(name)}
      deleteMutationFn={(id) => api.deleteAgentApiKey(id)}
      createSuccessToast={{
        title: 'Datadog Agent key created',
        description: "Copy the key now—it won't be shown again.",
      }}
      revokeSuccessToast={{
        title: 'Key revoked',
        description: 'The agent API key has been revoked.',
      }}
      createDialogTitle="Create Datadog Agent Key"
      createDialogDescription='Give this key a name to identify it (e.g. "Production Datadog Agent"). The full key will be shown once and cannot be retrieved later.'
      inputId="agent-key-name"
      inputPlaceholder="e.g. Production Datadog Agent"
      createdDialogTitle="Datadog Agent Key Created"
      revokeDialogTitle="Revoke Datadog Agent Key"
      revokeDialogDescription={(name) =>
        `Are you sure you want to revoke "${name}"? Any agents using this key will no longer be able to send data.`
      }
    />
  )
}
