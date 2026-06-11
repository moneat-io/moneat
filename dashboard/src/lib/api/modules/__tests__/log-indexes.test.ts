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
const LOG_INDEX_ID_MAIN = '11111111-1111-4111-8111-111111111111'
const LOG_INDEX_ID_ERRORS = '22222222-2222-4222-8222-222222222222'
const LOG_INDEX_ID_DELETE = '33333333-3333-4333-8333-333333333333'

describe('Log Indexes API', () => {
  beforeEach(() => {
    localStorage.clear()
    sessionStorage.clear()
    sessionStorage.setItem('authenticated', 'true')
  })

  // ──── getLogIndexes ────

  it('fetches log indexes', async () => {
    const mockResponse = {
      indexes: [
        { id: LOG_INDEX_ID_MAIN, name: 'main', filter_query: 'service:api', retention_days: 30 },
      ],
    }

    server.use(
      http.get(`${API_BASE}/v1/logs/indexes`, () => {
        return HttpResponse.json(mockResponse)
      })
    )

    const result = await api.getLogIndexes()
    expect(result).toEqual(mockResponse)
  })

  // ──── createLogIndex ────

  it('creates a log index', async () => {
    const request = { name: 'errors', filter_query: 'level:error', retention_days: 90 }
    const mockIndex = { id: LOG_INDEX_ID_ERRORS, ...request }

    server.use(
      http.post(`${API_BASE}/v1/logs/indexes`, async ({ request: req }) => {
        const body = (await req.json()) as Record<string, unknown>
        expect(body.name).toBe('errors')
        expect(body.filter_query).toBe('level:error')
        expect(body.retention_days).toBe(90)
        return HttpResponse.json(mockIndex)
      })
    )

    const result = await api.createLogIndex(request)
    expect(result).toEqual(mockIndex)
  })

  // ──── updateLogIndex ────

  it('updates a log index', async () => {
    const updateReq = { name: 'errors-updated', filter_query: 'level:error OR level:fatal' }
    const mockIndex = { id: LOG_INDEX_ID_ERRORS, ...updateReq, retention_days: 90 }

    server.use(
      http.put(`${API_BASE}/v1/logs/indexes/${LOG_INDEX_ID_ERRORS}`, async ({ request }) => {
        const body = (await request.json()) as Record<string, unknown>
        expect(body.name).toBe('errors-updated')
        return HttpResponse.json(mockIndex)
      })
    )

    const result = await api.updateLogIndex(LOG_INDEX_ID_ERRORS, updateReq)
    expect(result).toEqual(mockIndex)
  })

  // ──── deleteLogIndex ────

  it('deletes a log index', async () => {
    server.use(
      http.delete(`${API_BASE}/v1/logs/indexes/${LOG_INDEX_ID_DELETE}`, () => {
        return new HttpResponse(null, { status: 204 })
      })
    )

    await api.deleteLogIndex(LOG_INDEX_ID_DELETE)
  })

  // ──── testLogIndexFilter ────

  it('tests a log index filter', async () => {
    const mockResult = { matched: 42, sampleLogs: [] }

    server.use(
      http.post(`${API_BASE}/v1/logs/indexes/test`, async ({ request }) => {
        const body = (await request.json()) as Record<string, unknown>
        expect(body.filter_query).toBe('service:api AND level:error')
        return HttpResponse.json(mockResult)
      })
    )

    const result = await api.testLogIndexFilter('service:api AND level:error')
    expect(result).toEqual(mockResult)
  })
})
