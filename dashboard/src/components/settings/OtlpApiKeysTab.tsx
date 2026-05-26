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

import {ScrollText} from 'lucide-react'
import {api, type OtlpApiKey} from '@/lib/api'
import {ApiKeysTabBase} from './ApiKeysTabBase'

export function OtlpApiKeysTab() {
  return (
    <ApiKeysTabBase<OtlpApiKey>
      cardId="otlp-api-keys"
      cardTitle="OTLP API Keys"
      cardDescription="Create org-level API keys for OpenTelemetry ingestion."
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
      createDialogDescription='Give this key a name to identify it (e.g. "Production OTLP"). The full key will be shown once and cannot be retrieved later.'
      inputId="key-name"
      inputPlaceholder="e.g. Production OTLP"
      createdDialogTitle="OTLP API Key Created"
      revokeDialogTitle="Revoke OTLP API Key"
      revokeDialogDescription={(name) =>
        `Are you sure you want to revoke "${name}"? Any clients using this key will no longer be able to send data.`
      }
    />
  )
}
