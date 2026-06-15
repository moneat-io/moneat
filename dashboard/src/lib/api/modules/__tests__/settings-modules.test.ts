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

import {describe, expect, it, vi} from 'vitest'

import type {ApiClientCore} from '../../client'
import {connectorsMethods} from '../connectors'
import {userMethods} from '../user'

function fakeCore(response: unknown): ApiClientCore & {request: ReturnType<typeof vi.fn>} {
  return {
    API_BASE: '/v1',
    request: vi.fn().mockResolvedValue(response),
    get: vi.fn(),
    fetchWithAuth: vi.fn(),
    logout: vi.fn(),
    checkAuth: vi.fn(),
    isAuthenticated: vi.fn(),
  }
}

describe('settings API modules', () => {
  it('calls connector catalog and state endpoints', async () => {
    const core = fakeCore({providers: []})
    const api = connectorsMethods(core)

    await api.getConnectorProviders()
    await api.getConnectorState()

    expect(core.request).toHaveBeenNthCalledWith(1, '/v1/connectors/providers')
    expect(core.request).toHaveBeenNthCalledWith(2, '/v1/connectors/state')
  })

  it('persists user display preferences and organization settings', async () => {
    const core = fakeCore({devices: undefined})
    const api = userMethods(core)

    await api.updateUserPreferences({density: 'compact', dateFormat: 'iso'})
    await api.getPushDevices()
    await api.getOrganizationAccountSettings('org-1')
    await api.updateOrganizationSettings('org-1', {
      name: 'Acme Labs',
      slug: 'acme-labs',
      defaultTimezone: 'America/New_York',
    })

    expect(core.request).toHaveBeenNthCalledWith(1, '/v1/user/preferences', {
      method: 'PUT',
      body: JSON.stringify({density: 'compact', dateFormat: 'iso'}),
    })
    expect(core.request).toHaveBeenNthCalledWith(2, '/v1/user/push-devices')
    expect(core.request).toHaveBeenNthCalledWith(3, '/v1/organizations/org-1')
    expect(core.request).toHaveBeenNthCalledWith(4, '/v1/organizations/org-1', {
      method: 'PATCH',
      body: JSON.stringify({
        name: 'Acme Labs',
        slug: 'acme-labs',
        defaultTimezone: 'America/New_York',
      }),
    })
  })
})
