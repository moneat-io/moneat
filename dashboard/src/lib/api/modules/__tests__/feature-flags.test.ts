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

const API_BASE = 'http://localhost:8080'

const environment = {
  id: 1,
  key: 'production',
  name: 'Production',
  description: null,
  version: 1,
  createdAt: '2026-01-01T00:00:00Z',
  updatedAt: '2026-01-01T00:00:00Z',
}

const flag = {
  id: 10,
  key: 'checkout-flow',
  name: 'Checkout Flow',
  description: 'Controls the checkout experience',
  valueType: 'BOOLEAN',
  clientVisible: true,
  tags: ['checkout'],
  variants: [
    {
      id: 20,
      key: 'on',
      name: 'On',
      value: true,
      sortOrder: 0,
    },
  ],
  configs: [
    {
      environmentKey: 'production',
      environmentName: 'Production',
      enabled: true,
      defaultVariantKey: 'on',
      offVariantKey: null,
      rules: { conditions: [] },
      version: 1,
      updatedAt: '2026-01-01T00:00:00Z',
    },
  ],
  createdAt: '2026-01-01T00:00:00Z',
  updatedAt: '2026-01-01T00:00:00Z',
}

describe('Feature Flags API', () => {
  beforeEach(() => {
    globalThis.localStorage.clear()
    globalThis.sessionStorage.clear()
    globalThis.sessionStorage.setItem('authenticated', 'true')
  })

  // ──── Environments ────

  it('lists feature flag environments', async () => {
    const response = { environments: [environment] }

    server.use(
      http.get(`${API_BASE}/v1/feature-flags/environments`, () => HttpResponse.json(response))
    )

    const result = await api.listFeatureFlagEnvironments()
    expect(result).toEqual(response)
  })

  it('creates a feature flag environment', async () => {
    const request = {
      key: 'staging',
      name: 'Staging',
      description: 'Pre-production',
    }
    const response = { ...environment, id: 2, ...request }

    server.use(
      http.post(`${API_BASE}/v1/feature-flags/environments`, async ({ request: req }) => {
        expect(await req.json()).toEqual(request)
        return HttpResponse.json(response)
      })
    )

    const result = await api.createFeatureFlagEnvironment(request)
    expect(result).toEqual(response)
  })

  // ──── Flags ────

  it('lists feature flags without an environment filter', async () => {
    const response = { environments: [environment], flags: [flag] }

    server.use(
      http.get(`${API_BASE}/v1/feature-flags`, ({ request }) => {
        const url = new URL(request.url)
        expect(url.search).toBe('')
        return HttpResponse.json(response)
      })
    )

    const result = await api.listFeatureFlags()
    expect(result).toEqual(response)
  })

  it('lists feature flags with an environment filter', async () => {
    const response = { environments: [environment], flags: [flag] }

    server.use(
      http.get(`${API_BASE}/v1/feature-flags`, ({ request }) => {
        const url = new URL(request.url)
        expect(url.searchParams.get('environment')).toBe('production')
        return HttpResponse.json(response)
      })
    )

    const result = await api.listFeatureFlags('production')
    expect(result).toEqual(response)
  })

  it('creates a feature flag', async () => {
    const request = {
      key: 'checkout-flow',
      name: 'Checkout Flow',
      description: 'Controls the checkout experience',
      valueType: 'BOOLEAN' as const,
      clientVisible: true,
      tags: ['checkout'],
      variants: [{ key: 'on', name: 'On', value: true }],
      defaultVariantKey: 'on',
      offVariantKey: null,
    }

    server.use(
      http.post(`${API_BASE}/v1/feature-flags`, async ({ request: req }) => {
        expect(await req.json()).toEqual(request)
        return HttpResponse.json(flag)
      })
    )

    const result = await api.createFeatureFlag(request)
    expect(result).toEqual(flag)
  })

  it('fetches a feature flag with encoded key and no environment filter', async () => {
    server.use(
      http.get(`${API_BASE}/v1/feature-flags/checkout%2Fflow`, ({ request }) => {
        const url = new URL(request.url)
        expect(url.search).toBe('')
        return HttpResponse.json(flag)
      })
    )

    const result = await api.getFeatureFlag('checkout/flow')
    expect(result).toEqual(flag)
  })

  it('fetches a feature flag with an environment filter', async () => {
    server.use(
      http.get(`${API_BASE}/v1/feature-flags/checkout-flow`, ({ request }) => {
        const url = new URL(request.url)
        expect(url.searchParams.get('environment')).toBe('production')
        return HttpResponse.json(flag)
      })
    )

    const result = await api.getFeatureFlag('checkout-flow', 'production')
    expect(result).toEqual(flag)
  })

  it('updates and deletes a feature flag', async () => {
    const update = {
      name: 'Updated Checkout Flow',
      description: null,
      clientVisible: false,
      tags: ['checkout', 'beta'],
    }
    const updatedFlag = { ...flag, ...update }
    const calls: string[] = []

    server.use(
      http.put(`${API_BASE}/v1/feature-flags/checkout%2Fflow`, async ({ request }) => {
        calls.push('update')
        expect(await request.json()).toEqual(update)
        return HttpResponse.json(updatedFlag)
      }),
      http.delete(`${API_BASE}/v1/feature-flags/checkout%2Fflow`, () => {
        calls.push('delete')
        return new HttpResponse(null, { status: 204 })
      })
    )

    const updateResult = await api.updateFeatureFlag('checkout/flow', update)
    await api.deleteFeatureFlag('checkout/flow')

    expect(updateResult).toEqual(updatedFlag)
    expect(calls).toEqual(['update', 'delete'])
  })

  // ──── Configs ────

  it('updates feature flag config with encoded keys', async () => {
    const request = {
      enabled: true,
      defaultVariantKey: 'on',
      offVariantKey: null,
      rules: { rollout: 50 },
    }
    const response = { ...flag.configs[0], ...request }

    server.use(
      http.put(`${API_BASE}/v1/feature-flags/checkout%2Fflow/config/prod%2Fus`, async ({ request: req }) => {
        expect(await req.json()).toEqual(request)
        return HttpResponse.json(response)
      })
    )

    const result = await api.updateFeatureFlagConfig('checkout/flow', 'prod/us', request)
    expect(result).toEqual(response)
  })

  // ──── Segments ────

  it('lists, upserts, and deletes feature flag segments', async () => {
    const segment = {
      id: 1,
      key: 'beta-users',
      name: 'Beta Users',
      description: null,
      conditions: { email: { endsWith: '@example.com' } },
      createdAt: '2026-01-01T00:00:00Z',
      updatedAt: '2026-01-01T00:00:00Z',
    }
    const request = {
      key: segment.key,
      name: segment.name,
      description: segment.description,
      conditions: segment.conditions,
    }
    const calls: string[] = []

    server.use(
      http.get(`${API_BASE}/v1/feature-flags/segments`, () => {
        calls.push('list')
        return HttpResponse.json({ segments: [segment] })
      }),
      http.post(`${API_BASE}/v1/feature-flags/segments`, async ({ request: req }) => {
        calls.push('upsert')
        expect(await req.json()).toEqual(request)
        return HttpResponse.json(segment)
      }),
      http.delete(`${API_BASE}/v1/feature-flags/segments/beta%2Fusers`, () => {
        calls.push('delete')
        return new HttpResponse(null, { status: 204 })
      })
    )

    const listResult = await api.listFeatureFlagSegments()
    const upsertResult = await api.upsertFeatureFlagSegment(request)
    await api.deleteFeatureFlagSegment('beta/users')

    expect(listResult).toEqual({ segments: [segment] })
    expect(upsertResult).toEqual(segment)
    expect(calls).toEqual(['list', 'upsert', 'delete'])
  })

  // ──── SDK keys ────

  it('lists, creates, and revokes feature flag SDK keys', async () => {
    const sdkKey = {
      id: 1,
      environmentKey: 'production',
      name: 'Server key',
      keyType: 'server',
      keyPrefix: 'mff_1234',
      createdAt: '2026-01-01T00:00:00Z',
      lastUsedAt: null,
    }
    const request = {
      environmentKey: 'production',
      name: 'Server key',
      keyType: 'server' as const,
    }
    const createdKey = { ...sdkKey, key: 'mff_secret' }
    const calls: string[] = []

    server.use(
      http.get(`${API_BASE}/v1/feature-flags/sdk-keys`, () => {
        calls.push('list')
        return HttpResponse.json({ keys: [sdkKey] })
      }),
      http.post(`${API_BASE}/v1/feature-flags/sdk-keys`, async ({ request: req }) => {
        calls.push('create')
        expect(await req.json()).toEqual(request)
        return HttpResponse.json(createdKey)
      }),
      http.delete(`${API_BASE}/v1/feature-flags/sdk-keys/1`, () => {
        calls.push('revoke')
        return new HttpResponse(null, { status: 204 })
      })
    )

    const listResult = await api.listFeatureFlagSdkKeys()
    const createResult = await api.createFeatureFlagSdkKey(request)
    await api.revokeFeatureFlagSdkKey(1)

    expect(listResult).toEqual({ keys: [sdkKey] })
    expect(createResult).toEqual(createdKey)
    expect(calls).toEqual(['list', 'create', 'revoke'])
  })

  // ──── Audit and analytics ────

  it('lists audit events with the default limit', async () => {
    const response = {
      events: [
        {
          id: 1,
          environmentKey: 'production',
          flagKey: 'checkout-flow',
          actorUserId: 7,
          eventType: 'flag.updated',
          before: null,
          after: { enabled: true },
          createdAt: '2026-01-01T00:00:00Z',
        },
      ],
    }

    server.use(
      http.get(`${API_BASE}/v1/feature-flags/audit`, ({ request }) => {
        const url = new URL(request.url)
        expect(url.searchParams.get('limit')).toBe('50')
        return HttpResponse.json(response)
      })
    )

    const result = await api.listFeatureFlagAuditEvents()
    expect(result).toEqual(response)
  })

  it('lists audit events with a custom limit', async () => {
    const response = { events: [] }

    server.use(
      http.get(`${API_BASE}/v1/feature-flags/audit`, ({ request }) => {
        const url = new URL(request.url)
        expect(url.searchParams.get('limit')).toBe('10')
        return HttpResponse.json(response)
      })
    )

    const result = await api.listFeatureFlagAuditEvents(10)
    expect(result).toEqual(response)
  })

  it('fetches analytics with default hours and no environment filter', async () => {
    const response = {
      evaluations: 10,
      uniqueTargetingKeys: 3,
      variants: [],
      trackingEvents: [],
    }

    server.use(
      http.get(`${API_BASE}/v1/feature-flags/analytics`, ({ request }) => {
        const url = new URL(request.url)
        expect(url.searchParams.get('hours')).toBe('24')
        expect(url.searchParams.has('environment')).toBe(false)
        return HttpResponse.json(response)
      })
    )

    const result = await api.getFeatureFlagAnalytics()
    expect(result).toEqual(response)
  })

  it('fetches analytics with an environment filter and custom hours', async () => {
    const response = {
      evaluations: 20,
      uniqueTargetingKeys: 5,
      variants: [
        {
          flagKey: 'checkout-flow',
          variantKey: 'on',
          evaluations: 20,
          uniqueTargetingKeys: 5,
        },
      ],
      trackingEvents: [
        {
          eventName: 'checkout.completed',
          flagKey: 'checkout-flow',
          variantKey: 'on',
          events: 4,
          uniqueTargetingKeys: 4,
          totalValue: 100,
        },
      ],
    }

    server.use(
      http.get(`${API_BASE}/v1/feature-flags/analytics`, ({ request }) => {
        const url = new URL(request.url)
        expect(url.searchParams.get('hours')).toBe('12')
        expect(url.searchParams.get('environment')).toBe('production')
        return HttpResponse.json(response)
      })
    )

    const result = await api.getFeatureFlagAnalytics('production', 12)
    expect(result).toEqual(response)
  })
})
