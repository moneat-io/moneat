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

import {describe, expect, it} from 'vitest'

import type {
  ConnectorInstallationResponse,
  ConnectorProviderDefinition,
  ConnectorProviderStateDetail,
} from '@/lib/api/types'
import {
  describeConnectorHealth,
  effectiveConnectorState,
  formatProcessingLag,
  installAuthProfileId,
  isInstallationBacked,
} from '../connectorHealth'

const provider: ConnectorProviderDefinition = {
  id: 'revenuecat',
  name: 'RevenueCat',
  description: 'RevenueCat connector',
  authProfiles: [
    {
      id: 'fallback_key',
      name: 'Fallback key',
      authKind: 'api_key',
      subjectKinds: ['service_account'],
      tokenLifecycle: 'rotatable',
      defaultScopes: [],
      separateReconsentRequired: false,
      secretPurpose: 'data_import',
    },
  ],
  uses: [
    {
      id: 'subscription_import',
      name: 'Subscription import',
      family: 'data_import',
      availability: 'available',
      setupMode: 'api_key',
      capabilities: [],
      stateSource: 'connector_installations',
      secretPurpose: 'data_import',
      description: 'Import subscriptions',
      direction: 'read',
      allowedAuthProfileIds: ['project_api_key'],
      uiGroup: 'import_data',
    },
  ],
}

const installation: ConnectorInstallationResponse = {
  id: 'inst-1',
  providerId: 'revenuecat',
  name: 'RevenueCat',
  credentialType: 'api_key',
  authProfileId: 'project_api_key',
  externalProjectId: 'proj_abc',
  externalProjectName: 'Project',
  status: 'healthy',
  statusReason: null,
  enabled: true,
  apiSecretLastFour: '1234',
  webhookTokenPrefix: 'mrc_123',
  webhookToken: null,
  lastTestedAt: null,
  lastTestResult: null,
  lastSuccessfulProviderCallAt: null,
  lastError: null,
  createdAt: '2026-06-16T00:00:00Z',
  updatedAt: '2026-06-16T00:00:00Z',
}

describe('connectorHealth', () => {
  it('detects installation-backed providers and auth profiles from use metadata', () => {
    expect(isInstallationBacked(provider)).toBe(true)
    expect(installAuthProfileId(provider)).toBe('project_api_key')

    const legacyProvider = {
      ...provider,
      uses: provider.uses.map((use) => ({
        ...use,
        stateSource: 'organization_integrations',
        allowedAuthProfileIds: [],
      })),
    }

    expect(isInstallationBacked(legacyProvider)).toBe(false)
    expect(installAuthProfileId(legacyProvider)).toBe('fallback_key')
  })

  it('maps connector health states to UI descriptors', () => {
    expect(describeConnectorHealth('healthy')).toMatchObject({tone: 'success', badge: 'success'})
    expect(describeConnectorHealth('degraded')).toMatchObject({tone: 'warning', badge: 'warning'})
    expect(describeConnectorHealth('error')).toMatchObject({tone: 'danger', badge: 'danger'})
    expect(describeConnectorHealth('needs_mapping')).toMatchObject({
      tone: 'warning',
      label: 'Needs mapping',
    })
    expect(describeConnectorHealth('awaiting_traffic')).toMatchObject({
      tone: 'info',
      label: 'Awaiting traffic',
    })
    expect(describeConnectorHealth('unknown')).toMatchObject({tone: 'neutral', badge: 'neutral'})
    expect(describeConnectorHealth('healthy', false)).toMatchObject({
      tone: 'neutral',
      label: 'Disabled',
    })
  })

  it('prefers awaiting traffic status over healthy detail and falls back to installation status', () => {
    const detail: ConnectorProviderStateDetail = {
      installationId: 'inst-1',
      status: 'awaiting_traffic',
      health: 'healthy',
      message: null,
      mappedResources: 0,
      unmappedEvents: 0,
      failedReceipts: 0,
      sandboxEvents: 0,
      productionEvents: 0,
      lastAcceptedWebhookAt: null,
      lastAppliedAt: null,
      processingLagSeconds: null,
    }

    expect(effectiveConnectorState(detail, installation)).toBe('awaiting_traffic')
    expect(effectiveConnectorState({...detail, status: 'active', health: 'needs_mapping'}, installation)).toBe(
      'needs_mapping'
    )
    expect(effectiveConnectorState(null, {...installation, status: 'degraded'})).toBe('degraded')
  })

  it('formats processing lag compactly', () => {
    expect(formatProcessingLag(null)).toBe('Up to date')
    expect(formatProcessingLag(1)).toBe('Up to date')
    expect(formatProcessingLag(45)).toBe('45s behind')
    expect(formatProcessingLag(300)).toBe('5m behind')
    expect(formatProcessingLag(7200)).toBe('2h behind')
    expect(formatProcessingLag(172800)).toBe('2d behind')
  })
})
