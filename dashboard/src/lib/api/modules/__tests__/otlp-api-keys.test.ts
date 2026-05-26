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
      let capturedBody: Record<string, unknown> = {}
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

  // ──── service routing ────

  describe('service routing', () => {
    it('fetches observed OTLP services and maps snake_case fields', async () => {
      server.use(
        http.get(`${API_BASE}/v1/otlp/services`, () => {
          return HttpResponse.json({
            services: [
              {
                id: 10,
                mapping_id: 20,
                service_namespace: 'checkout',
                service_name: 'api',
                project_id: 30,
                project_name: 'Web App',
                seen_logs: true,
                seen_traces: true,
                seen_metrics: false,
                last_environment: 'production',
                first_seen_at: '2026-01-01T00:00:00Z',
                last_seen_at: '2026-01-01T01:00:00Z',
              },
            ],
          })
        })
      )

      const result = await api.getOtlpObservedServices()

      expect(result.services).toEqual([
        {
          id: 10,
          mappingId: 20,
          serviceNamespace: 'checkout',
          serviceName: 'api',
          projectId: 30,
          projectName: 'Web App',
          seenLogs: true,
          seenTraces: true,
          seenMetrics: false,
          lastEnvironment: 'production',
          firstSeenAt: '2026-01-01T00:00:00Z',
          lastSeenAt: '2026-01-01T01:00:00Z',
        },
      ])
    })

    it('fetches observed OTLP services and maps camelCase fields with defaults', async () => {
      server.use(
        http.get(`${API_BASE}/v1/otlp/services`, () => {
          return HttpResponse.json({
            services: [
              {
                id: 11,
                serviceName: 'worker',
                firstSeenAt: '2026-01-02T00:00:00Z',
                lastSeenAt: '2026-01-02T01:00:00Z',
              },
            ],
          })
        })
      )

      const result = await api.getOtlpObservedServices()

      expect(result.services[0]).toMatchObject({
        id: 11,
        serviceNamespace: '',
        serviceName: 'worker',
        seenLogs: false,
        seenTraces: false,
        seenMetrics: false,
        firstSeenAt: '2026-01-02T00:00:00Z',
        lastSeenAt: '2026-01-02T01:00:00Z',
      })
    })

    it('returns an empty observed services array when response has no services', async () => {
      server.use(
        http.get(`${API_BASE}/v1/otlp/services`, () => {
          return HttpResponse.json({})
        })
      )

      const result = await api.getOtlpObservedServices()
      expect(result.services).toEqual([])
    })

    it('upserts a service mapping with the selected project and namespace', async () => {
      let capturedBody: Record<string, unknown> = {}
      server.use(
        http.post(`${API_BASE}/v1/otlp/service-mappings`, async ({request}) => {
          capturedBody = (await request.json()) as Record<string, unknown>
          return HttpResponse.json({
            id: 40,
            service_namespace: 'checkout',
            service_name: 'api',
            project_id: 50,
            project_name: 'Backend',
            updated_at: '2026-01-03T00:00:00Z',
          })
        })
      )

      const result = await api.upsertOtlpServiceMapping('api', 50, 'checkout')

      expect(capturedBody).toEqual({
        service_name: 'api',
        service_namespace: 'checkout',
        project_id: 50,
      })
      expect(result).toEqual({
        id: 40,
        serviceNamespace: 'checkout',
        serviceName: 'api',
        projectId: 50,
        projectName: 'Backend',
        updatedAt: '2026-01-03T00:00:00Z',
      })
    })

    it('upserts a service mapping without a namespace by default', async () => {
      let capturedBody: Record<string, unknown> = {}
      server.use(
        http.post(`${API_BASE}/v1/otlp/service-mappings`, async ({request}) => {
          capturedBody = (await request.json()) as Record<string, unknown>
          return HttpResponse.json({
            id: 41,
            serviceNamespace: '',
            serviceName: 'worker',
            projectId: 51,
            projectName: 'Jobs',
            updatedAt: '2026-01-04T00:00:00Z',
          })
        })
      )

      const result = await api.upsertOtlpServiceMapping('worker', 51)

      expect(capturedBody).toEqual({
        service_name: 'worker',
        service_namespace: '',
        project_id: 51,
      })
      expect(result.serviceName).toBe('worker')
      expect(result.projectId).toBe(51)
    })

    it('deletes a service mapping by id', async () => {
      server.use(
        http.delete(`${API_BASE}/v1/otlp/service-mappings/40`, () => {
          return new HttpResponse(null, { status: 204 })
        })
      )

      await expect(api.deleteOtlpServiceMapping(40)).resolves.toBeUndefined()
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
