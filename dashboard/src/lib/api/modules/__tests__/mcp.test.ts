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

import {beforeEach, describe, expect, it} from 'vitest'
import {http, HttpResponse} from 'msw'
import {server} from '@/test/mocks/server'
import {api} from '@/lib/api'
import {clearAuthStorage} from '@/test/utils'

const API_BASE = 'http://localhost:8080'

describe('MCP API', () => {
  beforeEach(() => {
    clearAuthStorage()
  })

  it('fetches the MCP catalog and maps field variants', async () => {
    server.use(
      http.get(`${API_BASE}/v1/mcp/tool-catalog`, () => {
        return HttpResponse.json({
          sections: [
            {
              id: 'incidents',
              label: 'Incidents',
              description: 'Incident tools',
              tools: [
                {
                  name: 'list_incidents',
                  description: 'List incidents',
                  read_only: true,
                },
                {
                  name: 'resolve_incident',
                  description: 'Resolve incident',
                  readOnly: false,
                },
              ],
            },
          ],
          resources: [
            {
              uri: 'moneat://org/overview',
              name: 'Overview',
              description: 'Organization overview',
              mime_type: 'application/json',
            },
            {
              uri: 'moneat://projects',
              name: 'Projects',
              mimeType: 'application/json',
            },
          ],
        })
      })
    )

    const catalog = await api.getMcpToolCatalog()

    expect(catalog.sections[0].tools).toEqual([
      {
        name: 'list_incidents',
        description: 'List incidents',
        readOnly: true,
      },
      {
        name: 'resolve_incident',
        description: 'Resolve incident',
        readOnly: false,
      },
    ])
    expect(catalog.resources).toEqual([
      {
        uri: 'moneat://org/overview',
        name: 'Overview',
        description: 'Organization overview',
        mimeType: 'application/json',
      },
      {
        uri: 'moneat://projects',
        name: 'Projects',
        description: undefined,
        mimeType: 'application/json',
      },
    ])
  })

  it('defaults missing catalog arrays to empty lists', async () => {
    server.use(
      http.get(`${API_BASE}/v1/mcp/tool-catalog`, () => {
        return HttpResponse.json({})
      })
    )

    const catalog = await api.getMcpToolCatalog()

    expect(catalog).toEqual({
      sections: [],
      resources: [],
    })
  })

  it('fetches MCP keys and normalizes arrays and timestamp fields', async () => {
    server.use(
      http.get(`${API_BASE}/v1/mcp/api-keys`, () => {
        return HttpResponse.json({
          keys: [
            {
              id: 1,
              name: 'readonly',
              key_prefix: 'mmcp_abc123',
              enabled_tools: ['list_incidents', 42],
              enabled_resources: ['moneat://org/overview', null],
              created_at: '2026-05-01T00:00:00Z',
              last_used_at: '2026-05-02T00:00:00Z',
              expires_at: '2026-06-01T00:00:00Z',
            },
            {
              id: 2,
              name: 'all-access',
              keyPrefix: 'mmcp_def456',
              enabledTools: ['get_logs'],
              enabledResources: ['moneat://projects'],
              createdAt: '2026-05-03T00:00:00Z',
              lastUsedAt: '2026-05-04T00:00:00Z',
              expiresAt: '2026-06-03T00:00:00Z',
            },
            {
              id: 3,
              name: 'defaults',
              key_prefix: 'mmcp_ghi789',
              created_at: '2026-05-05T00:00:00Z',
            },
          ],
        })
      })
    )

    const result = await api.getMcpApiKeys()

    expect(result.keys).toEqual([
      {
        id: 1,
        name: 'readonly',
        keyPrefix: 'mmcp_abc123',
        enabledTools: ['list_incidents'],
        enabledResources: ['moneat://org/overview'],
        createdAt: '2026-05-01T00:00:00Z',
        lastUsedAt: '2026-05-02T00:00:00Z',
        expiresAt: '2026-06-01T00:00:00Z',
      },
      {
        id: 2,
        name: 'all-access',
        keyPrefix: 'mmcp_def456',
        enabledTools: ['get_logs'],
        enabledResources: ['moneat://projects'],
        createdAt: '2026-05-03T00:00:00Z',
        lastUsedAt: '2026-05-04T00:00:00Z',
        expiresAt: '2026-06-03T00:00:00Z',
      },
      {
        id: 3,
        name: 'defaults',
        keyPrefix: 'mmcp_ghi789',
        enabledTools: [],
        enabledResources: [],
        createdAt: '2026-05-05T00:00:00Z',
        lastUsedAt: undefined,
        expiresAt: undefined,
      },
    ])
  })

  it('defaults missing key arrays to an empty list', async () => {
    server.use(
      http.get(`${API_BASE}/v1/mcp/api-keys`, () => {
        return HttpResponse.json({})
      })
    )

    const result = await api.getMcpApiKeys()

    expect(result.keys).toEqual([])
  })

  it('creates an MCP key and includes the one-time secret', async () => {
    let capturedBody: Record<string, unknown> = {}
    server.use(
      http.post(`${API_BASE}/v1/mcp/api-keys`, async ({request}) => {
        capturedBody = (await request.json()) as Record<string, unknown>
        return HttpResponse.json({
          id: 4,
          name: 'ops',
          key: 'mmcp_secret',
          key_prefix: 'mmcp_sec',
          enabled_tools: ['list_incidents'],
          enabled_resources: ['moneat://org/overview'],
          created_at: '2026-05-06T00:00:00Z',
        })
      })
    )

    const result = await api.createMcpApiKey({
      name: 'ops',
      enabledTools: ['list_incidents'],
      enabledResources: ['moneat://org/overview'],
    })

    expect(capturedBody).toEqual({
      name: 'ops',
      enabledTools: ['list_incidents'],
      enabledResources: ['moneat://org/overview'],
    })
    expect(result.key).toBe('mmcp_secret')
    expect(result.keyPrefix).toBe('mmcp_sec')
  })

  it('updates and deletes MCP keys', async () => {
    let capturedUpdate: Record<string, unknown> = {}
    server.use(
      http.put(`${API_BASE}/v1/mcp/api-keys/4`, async ({request}) => {
        capturedUpdate = (await request.json()) as Record<string, unknown>
        return new HttpResponse(null, {status: 204})
      }),
      http.delete(`${API_BASE}/v1/mcp/api-keys/4`, () => {
        return new HttpResponse(null, {status: 204})
      })
    )

    await api.updateMcpApiKey(4, {
      name: 'ops',
      enabledTools: ['get_logs'],
      enabledResources: ['moneat://projects'],
    })
    await expect(api.deleteMcpApiKey(4)).resolves.toBeUndefined()

    expect(capturedUpdate).toEqual({
      name: 'ops',
      enabledTools: ['get_logs'],
      enabledResources: ['moneat://projects'],
    })
  })
})
