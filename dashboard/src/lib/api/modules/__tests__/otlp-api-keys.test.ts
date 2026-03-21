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

import { describe, it, expect, beforeEach } from 'vitest'
import { http, HttpResponse } from 'msw'
import { server } from '@/test/mocks/server'
import { api } from '@/lib/api'
import { clearAuthStorage } from '@/test/utils'

const API_BASE = 'http://localhost:8080'

describe('OTLP API Keys', () => {
  beforeEach(() => {
    clearAuthStorage()
  })

  // ──── getOtlpApiKeys ────

  describe('getOtlpApiKeys', () => {
    it('fetches OTLP API keys', async () => {
      server.use(
        http.get(`${API_BASE}/v1/logs/api-keys`, () => {
          return HttpResponse.json({
            keys: [
              {
                id: 1,
                name: 'production-key',
                key_prefix: 'motlp_abc123',
                created_at: '2024-01-01T00:00:00Z',
                last_used_at: '2024-06-15T12:00:00Z',
              },
              {
                id: 2,
                name: 'staging-key',
                key_prefix: 'motlp_def456',
                created_at: '2024-02-01T00:00:00Z',
                last_used_at: null,
              },
            ],
          })
        })
      )

      const result = await api.getOtlpApiKeys()
      expect(result.keys).toHaveLength(2)
      expect(result.keys[0].name).toBe('production-key')
      expect(result.keys[0].keyPrefix).toBe('motlp_abc123')
      expect(result.keys[0].lastUsedAt).toBe('2024-06-15T12:00:00Z')
      expect(result.keys[1].lastUsedAt).toBeNull()
    })

    it('returns empty keys array when response has no keys', async () => {
      server.use(
        http.get(`${API_BASE}/v1/logs/api-keys`, () => {
          return HttpResponse.json({})
        })
      )

      const result = await api.getOtlpApiKeys()
      expect(result.keys).toEqual([])
    })
  })

  // ──── createOtlpApiKey ────

  describe('createOtlpApiKey', () => {
    it('creates a new OTLP API key', async () => {
      let capturedBody: Record<string, unknown> | null = null
      server.use(
        http.post(`${API_BASE}/v1/logs/api-keys`, async ({ request }) => {
          capturedBody = (await request.json()) as Record<string, unknown>
          return HttpResponse.json({
            id: 3,
            name: 'my-new-key',
            key: 'motlp_full_secret_key_here',
            keyPrefix: 'motlp_full_s',
          })
        })
      )

      const result = await api.createOtlpApiKey('my-new-key')
      expect(capturedBody?.name).toBe('my-new-key')
      expect(result.key).toBe('motlp_full_secret_key_here')
    })
  })

  // ──── deleteOtlpApiKey ────

  describe('deleteOtlpApiKey', () => {
    it('deletes an OTLP API key by id', async () => {
      server.use(
        http.delete(`${API_BASE}/v1/logs/api-keys/7`, () => {
          return new HttpResponse(null, { status: 204 })
        })
      )

      await expect(api.deleteOtlpApiKey(7)).resolves.toBeUndefined()
    })
  })

  // ──── backward-compatible aliases ────

  describe('backward-compatible aliases', () => {
    it('getLogApiKeys is an alias for getOtlpApiKeys', () => {
      expect(api.getLogApiKeys).toBe(api.getOtlpApiKeys)
    })

    it('createLogApiKey is an alias for createOtlpApiKey', () => {
      expect(api.createLogApiKey).toBe(api.createOtlpApiKey)
    })

    it('deleteLogApiKey is an alias for deleteOtlpApiKey', () => {
      expect(api.deleteLogApiKey).toBe(api.deleteOtlpApiKey)
    })
  })
})
