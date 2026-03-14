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

describe('Profiles API', () => {
  beforeEach(() => {
    localStorage.clear()
    sessionStorage.clear()
    sessionStorage.setItem('authenticated', 'true')
  })

  // ──── getProfiles ────

  it('fetches profiles without params', async () => {
    const mockResponse = { profiles: [], total: 0 }

    server.use(
      http.get(`${API_BASE}/v1/profiles`, () => {
        return HttpResponse.json(mockResponse)
      })
    )

    const result = await api.getProfiles()
    expect(result).toEqual(mockResponse)
  })

  it('fetches profiles with filter params', async () => {
    const mockResponse = { profiles: [{ id: 'p1' }], total: 1 }

    server.use(
      http.get(`${API_BASE}/v1/profiles`, ({ request }) => {
        const url = new URL(request.url)
        expect(url.searchParams.get('service')).toBe('my-service')
        expect(url.searchParams.get('type')).toBe('cpu')
        expect(url.searchParams.get('source')).toBe('agent')
        expect(url.searchParams.get('limit')).toBe('10')
        expect(url.searchParams.get('offset')).toBe('5')
        return HttpResponse.json(mockResponse)
      })
    )

    const result = await api.getProfiles({
      service: 'my-service',
      type: 'cpu',
      source: 'agent',
      limit: 10,
      offset: 5,
    })
    expect(result).toEqual(mockResponse)
  })

  // ──── getProfileFlamegraph ────

  it('fetches profile flamegraph', async () => {
    const mockFlamegraph = {
      name: 'root',
      value: 100,
      children: [{ name: 'child', value: 50, children: [] }],
    }

    server.use(
      http.get(`${API_BASE}/v1/profiles/prof-123/flamegraph`, () => {
        return HttpResponse.json(mockFlamegraph)
      })
    )

    const result = await api.getProfileFlamegraph('prof-123')
    expect(result).toEqual(mockFlamegraph)
  })
})
