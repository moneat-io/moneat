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
const SYNTHETIC_TEST_ID = '11111111-1111-4111-8111-111111111111'
const SYNTHETIC_TEST_CREATED_ID = '22222222-2222-4222-8222-222222222222'
const SYNTHETIC_VARIABLE_ID = '33333333-3333-4333-8333-333333333333'
const SYNTHETIC_VARIABLE_CREATED_ID = '44444444-4444-4444-8444-444444444444'

describe('Synthetics API', () => {
  beforeEach(() => {
    localStorage.clear()
    sessionStorage.clear()
    sessionStorage.setItem('authenticated', 'true')
  })

  // ──── listSyntheticTests ────

  it('lists synthetic tests', async () => {
    const mock = [{ id: SYNTHETIC_TEST_ID, name: 'Homepage Check', type: 'http' }]

    server.use(
      http.get(`${API_BASE}/v1/synthetics/tests`, () => {
        return HttpResponse.json(mock)
      })
    )

    const result = await api.listSyntheticTests()
    expect(result).toEqual(mock)
  })

  // ──── createSyntheticTest ────

  it('creates a synthetic test', async () => {
    const mock = { id: SYNTHETIC_TEST_CREATED_ID, name: 'API Check', type: 'http' }

    server.use(
      http.post(`${API_BASE}/v1/synthetics/tests`, async ({ request }) => {
        const body = (await request.json()) as Record<string, unknown>
        expect(body.name).toBe('API Check')
        return HttpResponse.json(mock)
      })
    )

    const result = await api.createSyntheticTest({ name: 'API Check' } as never)
    expect(result).toEqual(mock)
  })

  // ──── updateSyntheticTest ────

  it('updates a synthetic test', async () => {
    const mock = { id: SYNTHETIC_TEST_ID, name: 'Updated Check', type: 'http' }

    server.use(
      http.put(`${API_BASE}/v1/synthetics/tests/${SYNTHETIC_TEST_ID}`, async ({ request }) => {
        const body = (await request.json()) as Record<string, unknown>
        expect(body.name).toBe('Updated Check')
        return HttpResponse.json(mock)
      })
    )

    const result = await api.updateSyntheticTest(SYNTHETIC_TEST_ID, { name: 'Updated Check' } as never)
    expect(result).toEqual(mock)
  })

  // ──── deleteSyntheticTest ────

  it('deletes a synthetic test', async () => {
    server.use(
      http.delete(`${API_BASE}/v1/synthetics/tests/${SYNTHETIC_TEST_ID}`, () => {
        return new HttpResponse(null, { status: 204 })
      })
    )

    await api.deleteSyntheticTest(SYNTHETIC_TEST_ID)
  })

  // ──── runSyntheticTest ────

  it('runs a synthetic test', async () => {
    server.use(
      http.post(`${API_BASE}/v1/synthetics/tests/${SYNTHETIC_TEST_ID}/run`, () => {
        return new HttpResponse(null, { status: 204 })
      })
    )

    await api.runSyntheticTest(SYNTHETIC_TEST_ID)
  })

  // ──── listSyntheticResults ────

  it('lists synthetic results with default limit', async () => {
    const mock = { results: [], total: 0 }

    server.use(
      http.get(`${API_BASE}/v1/synthetics/results`, ({ request }) => {
        const url = new URL(request.url)
        expect(url.searchParams.get('limit')).toBe('50')
        return HttpResponse.json(mock)
      })
    )

    const result = await api.listSyntheticResults()
    expect(result).toEqual(mock)
  })

  it('lists synthetic results with custom limit', async () => {
    const mock = { results: [], total: 0 }

    server.use(
      http.get(`${API_BASE}/v1/synthetics/results`, ({ request }) => {
        const url = new URL(request.url)
        expect(url.searchParams.get('limit')).toBe('10')
        return HttpResponse.json(mock)
      })
    )

    const result = await api.listSyntheticResults(10)
    expect(result).toEqual(mock)
  })

  // ──── getSyntheticTest ────

  it('fetches a single synthetic test', async () => {
    const mock = { id: SYNTHETIC_TEST_ID, name: 'Homepage Check', type: 'http' }

    server.use(
      http.get(`${API_BASE}/v1/synthetics/tests/${SYNTHETIC_TEST_ID}`, () => {
        return HttpResponse.json(mock)
      })
    )

    const result = await api.getSyntheticTest(SYNTHETIC_TEST_ID)
    expect(result).toEqual(mock)
  })

  // ──── getSyntheticTestResults ────

  it('fetches synthetic test results with default limit', async () => {
    const mock = { results: [], total: 0 }

    server.use(
      http.get(`${API_BASE}/v1/synthetics/tests/${SYNTHETIC_TEST_ID}/results`, ({ request }) => {
        const url = new URL(request.url)
        expect(url.searchParams.get('limit')).toBe('100')
        return HttpResponse.json(mock)
      })
    )

    const result = await api.getSyntheticTestResults(SYNTHETIC_TEST_ID)
    expect(result).toEqual(mock)
  })

  it('fetches synthetic test results with custom limit', async () => {
    const mock = { results: [], total: 0 }

    server.use(
      http.get(`${API_BASE}/v1/synthetics/tests/${SYNTHETIC_TEST_ID}/results`, ({ request }) => {
        const url = new URL(request.url)
        expect(url.searchParams.get('limit')).toBe('25')
        return HttpResponse.json(mock)
      })
    )

    const result = await api.getSyntheticTestResults(SYNTHETIC_TEST_ID, 25)
    expect(result).toEqual(mock)
  })

  // ──── getSyntheticTestSummary ────

  it('fetches synthetic test summary', async () => {
    const mock = { testId: SYNTHETIC_TEST_ID, uptime: 99.9, avgResponseTime: 120 }

    server.use(
      http.get(`${API_BASE}/v1/synthetics/tests/${SYNTHETIC_TEST_ID}/summary`, () => {
        return HttpResponse.json(mock)
      })
    )

    const result = await api.getSyntheticTestSummary(SYNTHETIC_TEST_ID)
    expect(result).toEqual(mock)
  })

  // ──── listSyntheticVariables ────

  it('lists synthetic variables', async () => {
    const mock = [{ id: SYNTHETIC_VARIABLE_ID, key: 'API_KEY', value: '***' }]

    server.use(
      http.get(`${API_BASE}/v1/synthetics/variables`, () => {
        return HttpResponse.json(mock)
      })
    )

    const result = await api.listSyntheticVariables()
    expect(result).toEqual(mock)
  })

  // ──── createSyntheticVariable ────

  it('creates a synthetic variable', async () => {
    const mock = { id: SYNTHETIC_VARIABLE_CREATED_ID, key: 'TOKEN', value: '***' }

    server.use(
      http.post(`${API_BASE}/v1/synthetics/variables`, async ({ request }) => {
        const body = (await request.json()) as Record<string, unknown>
        expect(body.key).toBe('TOKEN')
        return HttpResponse.json(mock)
      })
    )

    const result = await api.createSyntheticVariable({ key: 'TOKEN', value: 'secret' } as never)
    expect(result).toEqual(mock)
  })

  // ──── updateSyntheticVariable ────

  it('updates a synthetic variable', async () => {
    const mock = { id: SYNTHETIC_VARIABLE_ID, key: 'API_KEY', value: '***' }

    server.use(
      http.put(`${API_BASE}/v1/synthetics/variables/${SYNTHETIC_VARIABLE_ID}`, async ({ request }) => {
        const body = (await request.json()) as Record<string, unknown>
        expect(body.key).toBe('API_KEY')
        return HttpResponse.json(mock)
      })
    )

    const result = await api.updateSyntheticVariable(
      SYNTHETIC_VARIABLE_ID,
      { key: 'API_KEY', value: 'new' } as never
    )
    expect(result).toEqual(mock)
  })

  // ──── deleteSyntheticVariable ────

  it('deletes a synthetic variable', async () => {
    server.use(
      http.delete(`${API_BASE}/v1/synthetics/variables/${SYNTHETIC_VARIABLE_ID}`, () => {
        return new HttpResponse(null, { status: 204 })
      })
    )

    await api.deleteSyntheticVariable(SYNTHETIC_VARIABLE_ID)
  })
})
