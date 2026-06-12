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

import type { ApiClientCore } from '../client'
import type {
  CreateMcpApiKeyResponse,
  McpApiKey,
  McpResourceCatalogItem,
  McpToolCatalog,
  McpToolCatalogSection,
  McpToolCatalogTool,
  UpsertMcpApiKeyRequest,
} from '../types'

function readStringArray(value: unknown): string[] {
  return Array.isArray(value) ? value.filter((item): item is string => typeof item === 'string') : []
}

function mapMcpApiKey(row: Record<string, unknown>): McpApiKey {
  return {
    id: row.id as string,
    name: row.name as string,
    keyPrefix: (row.keyPrefix ?? row.key_prefix) as string,
    enabledTools: readStringArray(row.enabledTools ?? row.enabled_tools),
    enabledResources: readStringArray(row.enabledResources ?? row.enabled_resources),
    createdAt: (row.createdAt ?? row.created_at) as string,
    lastUsedAt: (row.lastUsedAt ?? row.last_used_at) as string | undefined,
    expiresAt: (row.expiresAt ?? row.expires_at) as string | undefined,
  }
}

function mapCatalogTool(row: Record<string, unknown>): McpToolCatalogTool {
  return {
    name: row.name as string,
    description: row.description as string,
    readOnly: row.readOnly === true || row.read_only === true,
  }
}

function mapCatalogSection(row: Record<string, unknown>): McpToolCatalogSection {
  const tools = Array.isArray(row.tools) ? row.tools : []
  return {
    id: row.id as string,
    label: row.label as string,
    description: row.description as string,
    tools: tools.map((tool) => mapCatalogTool(tool as Record<string, unknown>)),
  }
}

function mapCatalogResource(row: Record<string, unknown>): McpResourceCatalogItem {
  return {
    uri: row.uri as string,
    name: row.name as string,
    description: row.description as string | undefined,
    mimeType: (row.mimeType ?? row.mime_type) as string | undefined,
  }
}

function mapCreateMcpApiKey(row: Record<string, unknown>): CreateMcpApiKeyResponse {
  return {
    ...mapMcpApiKey(row),
    key: row.key as string,
  }
}

export function mcpMethods(core: ApiClientCore) {
  const base = core.API_BASE

  return {
    getMcpToolCatalog: async (): Promise<McpToolCatalog> => {
      const response = await core.request<{
        sections?: Record<string, unknown>[]
        resources?: Record<string, unknown>[]
      }>(`${base}/mcp/tool-catalog`)
      return {
        sections: (response.sections ?? []).map(mapCatalogSection),
        resources: (response.resources ?? []).map(mapCatalogResource),
      }
    },

    getMcpApiKeys: async (): Promise<{keys: McpApiKey[]}> => {
      const response = await core.request<{keys?: Record<string, unknown>[]}>(
        `${base}/mcp/api-keys`
      )
      return {keys: (response.keys ?? []).map(mapMcpApiKey)}
    },

    createMcpApiKey: async (request: UpsertMcpApiKeyRequest): Promise<CreateMcpApiKeyResponse> => {
      const response = await core.request<Record<string, unknown>>(`${base}/mcp/api-keys`, {
        method: 'POST',
        body: JSON.stringify(request),
      })
      return mapCreateMcpApiKey(response)
    },

    updateMcpApiKey: (id: string, request: UpsertMcpApiKeyRequest) =>
      core.request<void>(`${base}/mcp/api-keys/${encodeURIComponent(id)}`, {
        method: 'PUT',
        body: JSON.stringify(request),
      }),

    deleteMcpApiKey: (id: string) =>
      core.request<void>(`${base}/mcp/api-keys/${encodeURIComponent(id)}`, {method: 'DELETE'}),
  }
}
