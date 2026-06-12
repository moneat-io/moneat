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
const OTLP_KEY_ID_PROD = '11111111-1111-4111-8111-111111111111'
const OTLP_KEY_ID_STAGING = '22222222-2222-4222-8222-222222222222'
const OTLP_KEY_ID_CREATED = '33333333-3333-4333-8333-333333333333'
const OTLP_KEY_ID_DELETE = '44444444-4444-4444-8444-444444444444'
const OTLP_SERVICE_ID_API = '55555555-5555-4555-8555-555555555555'
const OTLP_SERVICE_ID_WORKER = '66666666-6666-4666-8666-666666666666'
const OTLP_MAPPING_ID_API = '77777777-7777-4777-8777-777777777777'
const OTLP_MAPPING_ID_WORKER = '88888888-8888-4888-8888-888888888888'
const PROJECT_ID_WEB = '99999999-9999-4999-8999-999999999999'
const PROJECT_ID_BACKEND = 'aaaaaaaa-aaaa-4aaa-8aaa-aaaaaaaaaaaa'
const PROJECT_ID_JOBS = 'bbbbbbbb-bbbb-4bbb-8bbb-bbbbbbbbbbbb'

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
                id: OTLP_KEY_ID_PROD,
                name: 'production-key',
                key_prefix: 'motlp_abc123',
                created_at: '2024-01-01T00:00:00Z',
                last_used_at: '2024-06-15T12:00:00Z',
              },
              {
                id: OTLP_KEY_ID_STAGING,
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
            id: OTLP_KEY_ID_CREATED,
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
        http.delete(`${API_BASE}/v1/logs/api-keys/${OTLP_KEY_ID_DELETE}`, () => {
          return new HttpResponse(null, { status: 204 })
        })
      )

      await expect(api.deleteOtlpApiKey(OTLP_KEY_ID_DELETE)).resolves.toBeUndefined()
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
                id: OTLP_SERVICE_ID_API,
                mapping_id: OTLP_MAPPING_ID_API,
                service_namespace: 'checkout',
                service_name: 'api',
                project_resource_id: PROJECT_ID_WEB,
                project_name: 'Web App',
                seen_logs: true,
                seen_traces: true,
                seen_metrics: false,
                seen_feedback: true,
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
          id: OTLP_SERVICE_ID_API,
          mappingId: OTLP_MAPPING_ID_API,
          serviceNamespace: 'checkout',
          serviceName: 'api',
          projectId: PROJECT_ID_WEB,
          projectName: 'Web App',
          seenLogs: true,
          seenTraces: true,
          seenMetrics: false,
          seenFeedback: true,
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
                id: OTLP_SERVICE_ID_WORKER,
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
        id: OTLP_SERVICE_ID_WORKER,
        serviceNamespace: '',
        serviceName: 'worker',
        seenLogs: false,
        seenTraces: false,
        seenMetrics: false,
        seenFeedback: false,
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
            id: OTLP_MAPPING_ID_API,
            service_namespace: 'checkout',
            service_name: 'api',
            project_resource_id: PROJECT_ID_BACKEND,
            project_name: 'Backend',
            updated_at: '2026-01-03T00:00:00Z',
          })
        })
      )

      const result = await api.upsertOtlpServiceMapping('api', PROJECT_ID_BACKEND, 'checkout')

      expect(capturedBody).toEqual({
        service_name: 'api',
        service_namespace: 'checkout',
        project_resource_id: PROJECT_ID_BACKEND,
      })
      expect(result).toEqual({
        id: OTLP_MAPPING_ID_API,
        serviceNamespace: 'checkout',
        serviceName: 'api',
        projectId: PROJECT_ID_BACKEND,
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
            id: OTLP_MAPPING_ID_WORKER,
            serviceNamespace: '',
            serviceName: 'worker',
            projectResourceId: PROJECT_ID_JOBS,
            projectName: 'Jobs',
            updatedAt: '2026-01-04T00:00:00Z',
          })
        })
      )

      const result = await api.upsertOtlpServiceMapping('worker', PROJECT_ID_JOBS)

      expect(capturedBody).toEqual({
        service_name: 'worker',
        service_namespace: '',
        project_resource_id: PROJECT_ID_JOBS,
      })
      expect(result.serviceName).toBe('worker')
      expect(result.projectId).toBe(PROJECT_ID_JOBS)
    })

    it('deletes a service mapping by id', async () => {
      server.use(
        http.delete(`${API_BASE}/v1/otlp/service-mappings/${OTLP_MAPPING_ID_API}`, () => {
          return new HttpResponse(null, { status: 204 })
        })
      )

      await expect(api.deleteOtlpServiceMapping(OTLP_MAPPING_ID_API)).resolves.toBeUndefined()
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
