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

  it('forwards env/host/version/from/to filters to getProfiles', async () => {
    server.use(
      http.get(`${API_BASE}/v1/profiles`, ({ request }) => {
        const url = new URL(request.url)
        expect(url.searchParams.get('env')).toBe('prod')
        expect(url.searchParams.get('host')).toBe('h1')
        expect(url.searchParams.get('version')).toBe('1.0.0')
        expect(url.searchParams.get('from')).toBe('10')
        expect(url.searchParams.get('to')).toBe('20')
        return HttpResponse.json({ profiles: [], totalCount: 0 })
      })
    )

    await api.getProfiles({
      env: 'prod',
      host: 'h1',
      version: '1.0.0',
      from: 10,
      to: 20,
    })
  })

  // ──── getProfile ────

  it('fetches a single profile by id', async () => {
    const mock = { profileId: 'p1', service: 'api' }
    server.use(
      http.get(`${API_BASE}/v1/profiles/p1`, () => HttpResponse.json(mock))
    )
    const result = await api.getProfile('p1')
    expect(result).toEqual(mock)
  })

  // ──── getProfileServices ────

  it('fetches the service rollup with a time window', async () => {
    const mock = {
      services: [],
      totalProfiles: 0,
      totalSizeBytes: 0,
      serviceCount: 0,
      hostCount: 0,
      typeCount: 0,
    }
    server.use(
      http.get(`${API_BASE}/v1/profiles/services`, ({ request }) => {
        const url = new URL(request.url)
        expect(url.searchParams.get('from')).toBe('100')
        expect(url.searchParams.get('to')).toBe('200')
        return HttpResponse.json(mock)
      })
    )
    const result = await api.getProfileServices({ from: 100, to: 200 })
    expect(result).toEqual(mock)
  })

  // ──── getProfileTimeseries ────

  it('fetches the volume time series with params', async () => {
    const mock = { points: [], bucketSeconds: 60 }
    server.use(
      http.get(`${API_BASE}/v1/profiles/timeseries`, ({ request }) => {
        const url = new URL(request.url)
        expect(url.searchParams.get('service')).toBe('api')
        expect(url.searchParams.get('from')).toBe('0')
        expect(url.searchParams.get('to')).toBe('1000')
        expect(url.searchParams.get('buckets')).toBe('48')
        return HttpResponse.json(mock)
      })
    )
    const result = await api.getProfileTimeseries({
      service: 'api',
      from: 0,
      to: 1000,
      buckets: 48,
    })
    expect(result).toEqual(mock)
  })

  // ──── getMergedFlamegraph ────

  it('fetches the merged flamegraph with params', async () => {
    const mock = { frames: [], mergedCount: 0, totalCount: 0 }
    server.use(
      http.get(`${API_BASE}/v1/profiles/merged-flamegraph`, ({ request }) => {
        const url = new URL(request.url)
        expect(url.searchParams.get('service')).toBe('api')
        expect(url.searchParams.get('type')).toBe('jfr')
        expect(url.searchParams.get('sampleType')).toBe('cpu')
        expect(url.searchParams.get('maxProfiles')).toBe('25')
        return HttpResponse.json(mock)
      })
    )
    const result = await api.getMergedFlamegraph({
      service: 'api',
      type: 'jfr',
      sampleType: 'cpu',
      maxProfiles: 25,
    })
    expect(result).toEqual(mock)
  })
})
